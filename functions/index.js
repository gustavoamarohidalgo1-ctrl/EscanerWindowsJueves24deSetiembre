// Respaldo opcional de FacturaStock. Callable `postPurchase`: valida autenticación con
// email verificado, membresía y rol, clave idempotente, unicidad documental, importes
// recomputados y límites, y escribe el grafo completo en una sola transacción con una
// secuencia monotónica por negocio (sync/metadata.seq). Sin credenciales: corre contra el
// Emulator Suite con el proyecto demo. Los callables de membresía viven en membership.js,
// el pull incremental (`listChanges`) en syncPull.js y la eliminación de cuenta
// (`deleteMyAccount`) en accountDeletion.js; todos se re-exportan al final.
import { onCall, HttpsError } from "firebase-functions/v2/https";
import { getAuth } from "firebase-admin/auth";
import { FieldValue } from "firebase-admin/firestore";
import {
  CALLABLE_OPTIONS,
  UUID_REGEX,
  db,
  sha256,
  invalid,
  requireString,
  requireExpectedUid,
  requireVerifiedEmail,
  requireMemberRole,
  accountDeletionTombstoneRef,
  requireAccountNotDeleting,
  requireBusinessNotDeleting,
} from "./common.js";
import { adjustMembershipQuota } from "./membershipQuota.js";
import {
  buildInventoryUpdates,
  canonicalizeLocationName,
  inventoryBalanceRef,
  nextInventorySequence,
  purchaseInventoryEffects,
  requireMissingBalancesSafeToCreate,
  unitCostForAppliedTotal,
  voidInventoryEffects,
} from "./inventorySync.js";

const SHA256_REGEX = /^[0-9a-f]{64}$/;
const ISO_DATE_REGEX = /^\d{4}-\d{2}-\d{2}$/;
const CURRENCY_REGEX = /^[A-Z]{3}$/;
const RUC_REGEX = /^\d{11}$/;
const CANONICAL_DOCUMENT_SERIES_REGEX = /^[A-Z0-9]{1,4}$/;
const CANONICAL_DOCUMENT_NUMBER_REGEX = /^\d{1,12}$/;
const DECIMAL_REGEX = /^-?\d{1,21}(\.\d{1,18})?$/;
const MAX_LINES = 100;
const MAX_MOVEMENTS = 500;
const MAX_AUDIT_EVENTS = 100;
const MAX_DOCUMENT_BYTES = 1_000_000;
const FIRESTORE_DOCUMENT_LIMIT_BYTES = 1_048_576;
const FIRESTORE_DOCUMENT_SAFETY_MARGIN_BYTES = 148_576;
const MAX_PERSISTED_PURCHASE_BYTES =
  FIRESTORE_DOCUMENT_LIMIT_BYTES - FIRESTORE_DOCUMENT_SAFETY_MARGIN_BYTES;
const MAX_SYNC_CHANGE_BYTES = 64_000;
const MAX_MINOR_UNITS = 1_000_000_000_000_000; // 1e15, exacto en Number
const FIRESTORE_TRANSACTION_WRITE_LIMIT = 500;
const TRANSACTION_WRITE_SAFETY_MARGIN = 20;
const MAX_TRANSACTION_WRITE_BUDGET =
  FIRESTORE_TRANSACTION_WRITE_LIMIT - TRANSACTION_WRITE_SAFETY_MARGIN;
const SYNC_PURCHASE = "SYNC_PURCHASE";
const SYNC_PURCHASE_VOID = "SYNC_PURCHASE_VOID";
const VOID_REASON_MIN = 10;
const VOID_REASON_MAX = 500;
const LEGACY_PURCHASE_PAYLOAD_VERSION = 2;
const CURRENT_PURCHASE_PAYLOAD_VERSION = 3;
const INVENTORY_PURCHASE_PAYLOAD_VERSION = 4;
const LEGACY_PURCHASE_DOCUMENT_VERSION = 1;
const CURRENT_PURCHASE_DOCUMENT_VERSION = 2;
const INVENTORY_PURCHASE_DOCUMENT_VERSION = 3;
const VOID_PAYLOAD_VERSION = 1;

const REQUEST_KEYS = new Set([
  "businessId",
  "idempotencyKey",
  "operationType",
  "payloadVersion",
  "document",
  "expectedUid",
]);
const OPTIONAL_REQUEST_KEYS = new Set(["expectedUid"]);
const PURCHASE_DOCUMENT_V1_KEYS = new Set([
  "version",
  "purchaseId",
  "businessId",
  "status",
  "documentType",
  "documentSeries",
  "documentNumber",
  "issueDate",
  "currency",
  "supplierRuc",
  "supplierLegalName",
  "subtotalMinorUnits",
  "taxMinorUnits",
  "otherChargesMinorUnits",
  "totalMinorUnits",
  "adjustmentMinorUnits",
  "adjustmentReason",
  "preparedLogicalHash",
  "postedAt",
  "idempotencyKey",
  "lines",
  "movements",
  "auditEventIds",
]);
const PURCHASE_DOCUMENT_V2_KEYS = new Set([
  ...PURCHASE_DOCUMENT_V1_KEYS,
  "duplicateOverride",
]);
const PURCHASE_LINE_V1_KEYS = new Set([
  "purchaseLineId",
  "position",
  "productId",
  "productName",
  "unitCode",
  "description",
  "quantity",
  "readUnitCost",
  "taxMinorUnits",
  "totalMinorUnits",
  "appliedUnitCost",
  "inventoryQuantity",
  "discount",
]);
const PURCHASE_LINE_V2_KEYS = new Set([
  ...PURCHASE_LINE_V1_KEYS,
  "taxTreatment",
  "taxEvidence",
  "productProvenance",
]);
const TAX_EVIDENCE_KEYS = new Set(["type", "value"]);
const DUPLICATE_OVERRIDE_KEYS = new Set([
  "existingPurchaseId",
  "sourceDraftId",
  "auditEventId",
  "reason",
]);
const STOCK_MOVEMENT_KEYS = new Set([
  "movementId",
  "purchaseLineId",
  "productId",
  "locationId",
  "type",
  "quantityDelta",
  "unitCost",
  "occurredAt",
]);
const INVENTORY_STOCK_MOVEMENT_KEYS = new Set([
  ...STOCK_MOVEMENT_KEYS,
  "locationName",
  "appliedCostTotal",
]);
const VOID_PAYLOAD_KEYS = new Set([
  "version",
  "purchaseId",
  "impactHash",
  "actorId",
  "role",
  "reason",
  "negativeStockPolicy",
  "averageUnitCostPolicy",
  "negativeImpactCount",
  "impacts",
]);
const VOID_IMPACT_KEYS = new Set([
  "productId",
  "locationId",
  "currentQuantity",
  "reversalQuantity",
  "resultingQuantity",
  "currentAverageUnitCost",
  "currency",
  "balanceVersion",
  "negative",
]);
const PURCHASE_DOCUMENT_TYPES = new Set([
  "INVOICE",
  "SALES_RECEIPT",
  "CREDIT_NOTE",
  "DEBIT_NOTE",
]);
const MOVEMENT_TYPES = new Set(["PURCHASE", "ADJUSTMENT", "VOID"]);
const VOID_ACTOR_ROLES = new Set(["OWNER", "MANAGER"]);
const TAX_TREATMENTS = new Set(["INCLUDED", "EXCLUDED", "EXEMPT"]);
const TAX_EVIDENCE_TYPES = new Set(["NONE", "EXPLICIT_AMOUNT", "EXPLICIT_RATE"]);
const PRODUCT_PROVENANCE = new Set(["EXISTING", "CREATED_IN_DRAFT"]);

const receiptFor = (businessId, idempotencyKey) =>
  `rcpt_${sha256(`facturastock:backup:${businessId}:${idempotencyKey}`).slice(0, 32)}`;

function requireMinorUnits(value, code) {
  if (
    typeof value !== "number" ||
    !Number.isSafeInteger(value) ||
    Math.abs(value) > MAX_MINOR_UNITS
  ) {
    throw invalid(code);
  }
  return value;
}

function requireDecimalText(value, code) {
  if (typeof value !== "string" || !DECIMAL_REGEX.test(value)) throw invalid(code);
  return value;
}

function optionalDecimalText(value, code) {
  if (value === null) return null;
  return requireDecimalText(value, code);
}

function decimalParts(value) {
  const negative = value.startsWith("-");
  const unsigned = negative ? value.slice(1) : value;
  const [integer, fraction = ""] = unsigned.split(".");
  const units = BigInt(`${integer}${fraction}`) * (negative ? -1n : 1n);
  return { units, scale: fraction.length };
}

function decimalUnitsAtScale(parts, scale) {
  return parts.units * 10n ** BigInt(scale - parts.scale);
}

function requireExactKeys(value, keys, code, optionalKeys = null) {
  if (typeof value !== "object" || value === null || Array.isArray(value)) throw invalid(code);
  const actual = Object.keys(value);
  if (
    actual.some((key) => !keys.has(key)) ||
    [...keys].some((key) => !optionalKeys?.has(key) && !Object.hasOwn(value, key))
  ) {
    throw invalid(code);
  }
  return value;
}

function serializedByteLength(value, shapeCode) {
  let serialized;
  try {
    serialized = JSON.stringify(value);
  } catch {
    throw invalid(shapeCode);
  }
  if (typeof serialized !== "string") throw invalid(shapeCode);
  return Buffer.byteLength(serialized, "utf8");
}

function decimalTextToMinorUnits(value, code) {
  const parts = decimalParts(value);
  if (parts.scale <= 2) return parts.units * 10n ** BigInt(2 - parts.scale);
  const divisor = 10n ** BigInt(parts.scale - 2);
  if (parts.units % divisor !== 0n) throw invalid(code);
  return parts.units / divisor;
}

function canonicalizeTaxEvidence(evidence, treatment, taxMinorUnits) {
  requireExactKeys(evidence, TAX_EVIDENCE_KEYS, "LINE_TAX_EVIDENCE_FIELDS");
  if (!TAX_EVIDENCE_TYPES.has(evidence.type)) throw invalid("LINE_TAX_EVIDENCE_TYPE");
  if (evidence.type === "NONE") {
    if (evidence.value !== null) throw invalid("LINE_TAX_EVIDENCE_VALUE");
  } else {
    requireDecimalText(evidence.value, "LINE_TAX_EVIDENCE_VALUE");
    const parts = decimalParts(evidence.value);
    if (parts.units < 0n) throw invalid("LINE_TAX_EVIDENCE_VALUE");
    if (evidence.type === "EXPLICIT_RATE") {
      const scale = parts.scale;
      if (parts.units > 100n * 10n ** BigInt(scale)) {
        throw invalid("LINE_TAX_EVIDENCE_VALUE");
      }
    } else if (
      decimalTextToMinorUnits(evidence.value, "LINE_TAX_EVIDENCE_VALUE") !==
      BigInt(taxMinorUnits)
    ) {
      throw invalid("LINE_TAX_EVIDENCE_CONFLICT");
    }
  }
  if (treatment === "EXEMPT") {
    if (evidence.type !== "NONE" || taxMinorUnits !== 0) {
      throw invalid("LINE_TAX_EVIDENCE_CONFLICT");
    }
  } else if (evidence.type === "NONE") {
    throw invalid("LINE_TAX_EVIDENCE_REQUIRED");
  }
  return { type: evidence.type, value: evidence.value };
}

function canonicalizeLine(line, index, documentVersion) {
  if (typeof line !== "object" || line === null || Array.isArray(line)) throw invalid("LINE_SHAPE");
  requireExactKeys(
    line,
    documentVersion >= CURRENT_PURCHASE_DOCUMENT_VERSION
      ? PURCHASE_LINE_V2_KEYS
      : PURCHASE_LINE_V1_KEYS,
    "LINE_FIELDS",
  );
  if (!UUID_REGEX.test(line.purchaseLineId ?? "")) throw invalid("LINE_ID");
  if (line.position !== index) throw invalid("LINE_POSITION");
  if (!UUID_REGEX.test(line.productId ?? "")) throw invalid("LINE_PRODUCT_ID");
  requireString(line.productName, "LINE_PRODUCT_NAME", 200);
  requireString(line.unitCode, "LINE_UNIT_CODE", 32);
  requireString(line.description, "LINE_DESCRIPTION", 500);
  requireDecimalText(line.quantity, "LINE_QUANTITY");
  requireDecimalText(line.readUnitCost, "LINE_UNIT_COST");
  requireMinorUnits(line.taxMinorUnits, "LINE_TAX");
  requireMinorUnits(line.totalMinorUnits, "LINE_TOTAL");
  optionalDecimalText(line.appliedUnitCost, "LINE_APPLIED_COST");
  optionalDecimalText(line.inventoryQuantity, "LINE_INVENTORY_QUANTITY");
  optionalDecimalText(line.discount, "LINE_DISCOUNT");
  const canonical = {
    purchaseLineId: line.purchaseLineId,
    position: line.position,
    productId: line.productId,
    productName: line.productName,
    unitCode: line.unitCode,
    description: line.description,
    quantity: line.quantity,
    readUnitCost: line.readUnitCost,
    taxMinorUnits: line.taxMinorUnits,
    totalMinorUnits: line.totalMinorUnits,
    appliedUnitCost: line.appliedUnitCost,
    inventoryQuantity: line.inventoryQuantity,
    discount: line.discount,
  };
  if (documentVersion >= CURRENT_PURCHASE_DOCUMENT_VERSION) {
    if (!TAX_TREATMENTS.has(line.taxTreatment)) throw invalid("LINE_TAX_TREATMENT");
    if (!PRODUCT_PROVENANCE.has(line.productProvenance)) {
      throw invalid("LINE_PRODUCT_PROVENANCE");
    }
    canonical.taxTreatment = line.taxTreatment;
    canonical.taxEvidence = canonicalizeTaxEvidence(
      line.taxEvidence,
      line.taxTreatment,
      line.taxMinorUnits,
    );
    canonical.productProvenance = line.productProvenance;
  }
  return canonical;
}

function canonicalizeMovement(movement, lineById, documentVersion) {
  if (typeof movement !== "object" || movement === null || Array.isArray(movement)) {
    throw invalid("MOVEMENT_SHAPE");
  }
  requireExactKeys(
    movement,
    documentVersion === INVENTORY_PURCHASE_DOCUMENT_VERSION
      ? INVENTORY_STOCK_MOVEMENT_KEYS
      : STOCK_MOVEMENT_KEYS,
    "MOVEMENT_FIELDS",
  );
  if (!UUID_REGEX.test(movement.movementId ?? "")) throw invalid("MOVEMENT_ID");
  const line = lineById.get(movement.purchaseLineId);
  if (line === undefined) throw invalid("MOVEMENT_LINE_LINK");
  if (!UUID_REGEX.test(movement.productId ?? "")) throw invalid("MOVEMENT_PRODUCT_ID");
  if (movement.productId !== line.productId) throw invalid("MOVEMENT_PRODUCT_LINK");
  if (!UUID_REGEX.test(movement.locationId ?? "")) throw invalid("MOVEMENT_LOCATION_ID");
  if (!MOVEMENT_TYPES.has(movement.type)) throw invalid("MOVEMENT_TYPE");
  requireDecimalText(movement.quantityDelta, "MOVEMENT_QUANTITY");
  optionalDecimalText(movement.unitCost, "MOVEMENT_UNIT_COST");
  if (
    typeof movement.occurredAt !== "number" ||
    !Number.isSafeInteger(movement.occurredAt) ||
    movement.occurredAt < 0
  ) {
    throw invalid("MOVEMENT_TIMESTAMP");
  }
  const canonical = {
    movementId: movement.movementId,
    purchaseLineId: movement.purchaseLineId,
    productId: movement.productId,
    locationId: movement.locationId,
    type: movement.type,
    quantityDelta: movement.quantityDelta,
    unitCost: movement.unitCost,
    occurredAt: movement.occurredAt,
  };
  if (documentVersion === INVENTORY_PURCHASE_DOCUMENT_VERSION) {
    requireString(movement.locationName, "MOVEMENT_LOCATION_NAME", 100);
    if (movement.locationName !== movement.locationName.trim()) {
      throw invalid("MOVEMENT_LOCATION_NAME");
    }
    canonical.locationName = movement.locationName;
    requireDecimalText(movement.appliedCostTotal, "MOVEMENT_APPLIED_COST_TOTAL");
    if (decimalParts(movement.appliedCostTotal).units < 0n) {
      throw invalid("MOVEMENT_APPLIED_COST_TOTAL");
    }
    canonical.appliedCostTotal = movement.appliedCostTotal;
  }
  return canonical;
}

function canonicalizeDuplicateOverride(value, purchaseId, auditEventIds) {
  if (value === null) {
    if (auditEventIds.length !== 1) throw invalid("AUDIT_OVERRIDE_MISMATCH");
    return null;
  }
  requireExactKeys(value, DUPLICATE_OVERRIDE_KEYS, "DUPLICATE_OVERRIDE_FIELDS");
  if (!UUID_REGEX.test(value.existingPurchaseId ?? "")) {
    throw invalid("DUPLICATE_OVERRIDE_TARGET");
  }
  if (value.existingPurchaseId === purchaseId) throw invalid("DUPLICATE_OVERRIDE_TARGET");
  if (!UUID_REGEX.test(value.sourceDraftId ?? "")) throw invalid("DUPLICATE_OVERRIDE_DRAFT");
  if (!UUID_REGEX.test(value.auditEventId ?? "")) throw invalid("DUPLICATE_OVERRIDE_AUDIT");
  if (
    auditEventIds.length !== 2 ||
    auditEventIds.filter((id) => id === value.auditEventId).length !== 1
  ) {
    throw invalid("AUDIT_OVERRIDE_MISMATCH");
  }
  if (
    typeof value.reason !== "string" ||
    value.reason !== value.reason.trim() ||
    value.reason.length < VOID_REASON_MIN ||
    value.reason.length > VOID_REASON_MAX
  ) {
    throw invalid("DUPLICATE_OVERRIDE_REASON");
  }
  return {
    existingPurchaseId: value.existingPurchaseId,
    sourceDraftId: value.sourceDraftId,
    auditEventId: value.auditEventId,
    reason: value.reason,
  };
}

/** Catálogo cerrado, proyección canónica y recomputo exacto de importes. */
function canonicalizePurchaseDocument(
  document,
  businessId,
  idempotencyKey,
  expectedDocumentVersion,
) {
  if (typeof document !== "object" || document === null || Array.isArray(document)) {
    throw invalid("DOCUMENT_SHAPE");
  }
  if (serializedByteLength(document, "DOCUMENT_SHAPE") > MAX_DOCUMENT_BYTES) {
    throw invalid("DOCUMENT_TOO_LARGE");
  }
  requireExactKeys(
    document,
    expectedDocumentVersion >= CURRENT_PURCHASE_DOCUMENT_VERSION
      ? PURCHASE_DOCUMENT_V2_KEYS
      : PURCHASE_DOCUMENT_V1_KEYS,
    "DOCUMENT_FIELDS",
  );
  if (document.version !== expectedDocumentVersion) throw invalid("DOCUMENT_VERSION");
  if (!UUID_REGEX.test(document.purchaseId ?? "")) throw invalid("PURCHASE_ID");
  if (document.businessId !== businessId) throw invalid("BUSINESS_MISMATCH");
  if (document.idempotencyKey !== idempotencyKey) throw invalid("KEY_MISMATCH");
  if (idempotencyKey !== `sync-purchase:v1:${document.purchaseId}`) {
    throw invalid("IDEMPOTENCY_KEY");
  }
  if (document.status !== "POSTED") throw invalid("DOCUMENT_STATUS");
  if (!PURCHASE_DOCUMENT_TYPES.has(document.documentType)) throw invalid("DOCUMENT_TYPE");
  if (expectedDocumentVersion >= CURRENT_PURCHASE_DOCUMENT_VERSION) {
    if (!CANONICAL_DOCUMENT_SERIES_REGEX.test(document.documentSeries ?? "")) {
      throw invalid("DOCUMENT_SERIES");
    }
    if (!CANONICAL_DOCUMENT_NUMBER_REGEX.test(document.documentNumber ?? "")) {
      throw invalid("DOCUMENT_NUMBER");
    }
  } else {
    // v1 conserva la forma histórica para que un ACK perdido pueda repetirse sin cambiar hash.
    requireString(document.documentSeries, "DOCUMENT_SERIES", 20);
    requireString(document.documentNumber, "DOCUMENT_NUMBER", 32);
  }
  if (!ISO_DATE_REGEX.test(document.issueDate ?? "")) throw invalid("ISSUE_DATE");
  if (!CURRENCY_REGEX.test(document.currency ?? "")) throw invalid("CURRENCY");
  if (document.supplierRuc !== null && !RUC_REGEX.test(document.supplierRuc ?? "")) {
    throw invalid("SUPPLIER_RUC");
  }
  requireString(document.supplierLegalName, "SUPPLIER_NAME", 200);
  requireMinorUnits(document.subtotalMinorUnits, "SUBTOTAL");
  requireMinorUnits(document.taxMinorUnits, "TAX");
  requireMinorUnits(document.otherChargesMinorUnits, "OTHER_CHARGES");
  requireMinorUnits(document.totalMinorUnits, "TOTAL");
  if (document.adjustmentMinorUnits !== null) {
    requireMinorUnits(document.adjustmentMinorUnits, "ADJUSTMENT");
  }
  if (document.adjustmentMinorUnits !== null && document.adjustmentMinorUnits !== 0) {
    requireString(document.adjustmentReason, "ADJUSTMENT_REASON", VOID_REASON_MAX);
  } else if (document.adjustmentReason !== null) {
    throw invalid("ADJUSTMENT_REASON");
  }
  if (!SHA256_REGEX.test(document.preparedLogicalHash ?? "")) throw invalid("PREPARED_HASH");
  if (
    document.postedAt !== null &&
    (typeof document.postedAt !== "number" ||
      !Number.isSafeInteger(document.postedAt) ||
      document.postedAt < 0)
  ) {
    throw invalid("POSTED_AT");
  }

  const lines = document.lines;
  if (!Array.isArray(lines) || lines.length === 0 || lines.length > MAX_LINES) {
    throw invalid("LINES_LIMIT");
  }
  const canonicalLines = lines.map((line, index) =>
    canonicalizeLine(line, index, expectedDocumentVersion),
  );
  const lineById = new Map(canonicalLines.map((line) => [line.purchaseLineId, line]));
  if (lineById.size !== canonicalLines.length) throw invalid("LINE_ID_DUPLICATED");

  const movements = document.movements;
  if (!Array.isArray(movements) || movements.length > MAX_MOVEMENTS) {
    throw invalid("MOVEMENTS_LIMIT");
  }
  const canonicalMovements = movements.map((movement) =>
    canonicalizeMovement(movement, lineById, expectedDocumentVersion),
  );
  const movementIds = new Set(canonicalMovements.map((movement) => movement.movementId));
  if (movementIds.size !== canonicalMovements.length) throw invalid("MOVEMENT_ID_DUPLICATED");

  const auditEventIds = document.auditEventIds;
  if (!Array.isArray(auditEventIds) || auditEventIds.length > MAX_AUDIT_EVENTS) {
    throw invalid("AUDIT_LIMIT");
  }
  auditEventIds.forEach((auditEventId) => {
    if (!UUID_REGEX.test(auditEventId ?? "")) throw invalid("AUDIT_ID");
  });
  if (new Set(auditEventIds).size !== auditEventIds.length) throw invalid("AUDIT_ID_DUPLICATED");
  const duplicateOverride = expectedDocumentVersion >= CURRENT_PURCHASE_DOCUMENT_VERSION
    ? canonicalizeDuplicateOverride(
      document.duplicateOverride,
      document.purchaseId,
      auditEventIds,
    )
    : null;

  // BigInt evita que un agregado adversarial cruce MAX_SAFE_INTEGER aunque cada importe
  // individual esté dentro de 1e15. Nunca se convierte el acumulado otra vez a Number.
  const adjustment = BigInt(document.adjustmentMinorUnits ?? 0);
  const total = BigInt(document.totalMinorUnits);
  const lineSum = canonicalLines.reduce(
    (sum, line) => sum + BigInt(line.totalMinorUnits),
    0n,
  );
  const headerSum =
    BigInt(document.subtotalMinorUnits) +
    BigInt(document.taxMinorUnits) +
    BigInt(document.otherChargesMinorUnits) +
    adjustment;
  if (lineSum + adjustment !== total || headerSum !== total) throw invalid("TOTAL_MISMATCH");

  const canonical = {
    version: document.version,
    purchaseId: document.purchaseId,
    businessId: document.businessId,
    status: document.status,
    documentType: document.documentType,
    documentSeries: document.documentSeries,
    documentNumber: document.documentNumber,
    issueDate: document.issueDate,
    currency: document.currency,
    supplierRuc: document.supplierRuc,
    supplierLegalName: document.supplierLegalName,
    subtotalMinorUnits: document.subtotalMinorUnits,
    taxMinorUnits: document.taxMinorUnits,
    otherChargesMinorUnits: document.otherChargesMinorUnits,
    totalMinorUnits: document.totalMinorUnits,
    adjustmentMinorUnits: document.adjustmentMinorUnits,
    adjustmentReason: document.adjustmentReason,
    preparedLogicalHash: document.preparedLogicalHash,
    postedAt: document.postedAt,
    idempotencyKey: document.idempotencyKey,
    lines: canonicalLines,
    movements: canonicalMovements,
    auditEventIds: [...auditEventIds],
  };
  if (expectedDocumentVersion >= CURRENT_PURCHASE_DOCUMENT_VERSION) {
    canonical.duplicateOverride = duplicateOverride;
  }
  return canonical;
}

function canonicalizeVoidImpact(impact) {
  requireExactKeys(impact, VOID_IMPACT_KEYS, "VOID_IMPACT_FIELDS");
  if (!UUID_REGEX.test(impact.productId ?? "")) throw invalid("VOID_IMPACT_PRODUCT_ID");
  if (!UUID_REGEX.test(impact.locationId ?? "")) throw invalid("VOID_IMPACT_LOCATION_ID");
  requireDecimalText(impact.currentQuantity, "VOID_IMPACT_CURRENT_QUANTITY");
  requireDecimalText(impact.reversalQuantity, "VOID_IMPACT_REVERSAL_QUANTITY");
  requireDecimalText(impact.resultingQuantity, "VOID_IMPACT_RESULTING_QUANTITY");
  requireDecimalText(impact.currentAverageUnitCost, "VOID_IMPACT_AVERAGE_COST");
  const current = decimalParts(impact.currentQuantity);
  const reversal = decimalParts(impact.reversalQuantity);
  const resulting = decimalParts(impact.resultingQuantity);
  const averageCost = decimalParts(impact.currentAverageUnitCost);
  const quantityScale = Math.max(current.scale, reversal.scale, resulting.scale);
  if (reversal.units === 0n) throw invalid("VOID_IMPACT_ZERO_REVERSAL");
  if (
    decimalUnitsAtScale(current, quantityScale) +
      decimalUnitsAtScale(reversal, quantityScale) !==
    decimalUnitsAtScale(resulting, quantityScale)
  ) {
    throw invalid("VOID_IMPACT_TOTAL_MISMATCH");
  }
  if (averageCost.units < 0n) throw invalid("VOID_IMPACT_AVERAGE_COST");
  if (!CURRENCY_REGEX.test(impact.currency ?? "")) throw invalid("VOID_IMPACT_CURRENCY");
  if (!Number.isSafeInteger(impact.balanceVersion) || impact.balanceVersion < 0) {
    throw invalid("VOID_IMPACT_BALANCE_VERSION");
  }
  if (
    typeof impact.negative !== "boolean" ||
    impact.negative !== (resulting.units < 0n)
  ) {
    throw invalid("VOID_IMPACT_NEGATIVE");
  }
  return {
    productId: impact.productId,
    locationId: impact.locationId,
    currentQuantity: impact.currentQuantity,
    reversalQuantity: impact.reversalQuantity,
    resultingQuantity: impact.resultingQuantity,
    currentAverageUnitCost: impact.currentAverageUnitCost,
    currency: impact.currency,
    balanceVersion: impact.balanceVersion,
    negative: impact.negative,
  };
}

function canonicalizeVoidPayload(payloadText, idempotencyKey) {
  if (
    typeof payloadText !== "string" ||
    Buffer.byteLength(payloadText, "utf8") > MAX_DOCUMENT_BYTES
  ) {
    throw invalid("DOCUMENT_TOO_LARGE");
  }
  let payload;
  try {
    payload = JSON.parse(payloadText);
  } catch {
    throw invalid("DOCUMENT_JSON");
  }
  requireExactKeys(payload, VOID_PAYLOAD_KEYS, "VOID_FIELDS");
  if (payload.version !== VOID_PAYLOAD_VERSION) throw invalid("DOCUMENT_VERSION");
  if (!UUID_REGEX.test(payload.purchaseId ?? "")) throw invalid("PURCHASE_ID");
  if (idempotencyKey !== `sync-purchase-void:v1:${payload.purchaseId}`) {
    throw invalid("IDEMPOTENCY_KEY");
  }
  if (!SHA256_REGEX.test(payload.impactHash ?? "")) throw invalid("IMPACT_HASH");
  // Este actor es el principal local que forma parte de impactHash; no es un Firebase UID.
  // La cuenta autenticada se persiste por separado como voidRecord.cloudActorUid.
  if (!UUID_REGEX.test(payload.actorId ?? "")) throw invalid("ACTOR_ID");
  if (!VOID_ACTOR_ROLES.has(payload.role)) throw invalid("ACTOR_ROLE");
  if (
    typeof payload.reason !== "string" ||
    payload.reason.length < VOID_REASON_MIN ||
    payload.reason.length > VOID_REASON_MAX ||
    payload.reason !== payload.reason.trim()
  ) {
    throw invalid("VOID_REASON");
  }
  if (payload.negativeStockPolicy !== "ALLOW_WITH_VISIBLE_WARNING") {
    throw invalid("NEGATIVE_STOCK_POLICY");
  }
  if (payload.averageUnitCostPolicy !== "PRESERVE_CURRENT") {
    throw invalid("AVERAGE_UNIT_COST_POLICY");
  }
  if (!Array.isArray(payload.impacts) || payload.impacts.length > MAX_LINES) {
    throw invalid("VOID_IMPACTS_LIMIT");
  }
  const impacts = payload.impacts
    .map(canonicalizeVoidImpact)
    .sort((left, right) =>
      left.productId.localeCompare(right.productId) ||
      left.locationId.localeCompare(right.locationId),
    );
  const impactKeys = new Set(
    impacts.map((impact) => `${impact.productId}\u001f${impact.locationId}`),
  );
  if (impactKeys.size !== impacts.length) throw invalid("VOID_IMPACT_DUPLICATED");
  if (
    !Number.isSafeInteger(payload.negativeImpactCount) ||
    payload.negativeImpactCount < 0 ||
    payload.negativeImpactCount !== impacts.filter((impact) => impact.negative).length
  ) {
    throw invalid("NEGATIVE_IMPACT_COUNT");
  }
  const canonical = {
    version: payload.version,
    purchaseId: payload.purchaseId,
    impactHash: payload.impactHash,
    actorId: payload.actorId,
    role: payload.role,
    reason: payload.reason,
    negativeStockPolicy: payload.negativeStockPolicy,
    averageUnitCostPolicy: payload.averageUnitCostPolicy,
    negativeImpactCount: payload.negativeImpactCount,
    impacts,
  };
  return { payload: canonical, payloadText: JSON.stringify(canonical) };
}

function normalizedDecimalParts(parts) {
  let units = parts.units;
  let scale = parts.scale;
  while (scale > 0 && units % 10n === 0n) {
    units /= 10n;
    scale -= 1;
  }
  return { units, scale };
}

function normalizedDecimalText(parts) {
  const normalized = normalizedDecimalParts(parts);
  return `${normalized.units.toString()}e-${normalized.scale}`;
}

function sumDecimalParts(values) {
  const parsed = values.map(decimalParts);
  const scale = Math.max(...parsed.map((parts) => parts.scale));
  return normalizedDecimalParts({
    units: parsed.reduce((total, parts) => total + decimalUnitsAtScale(parts, scale), 0n),
    scale,
  });
}

/**
 * El impactHash de Android sella saldos/timestamps locales que la réplica cloud no posee y no se
 * puede recalcular sin romper el wire v1. Esta frontera sí demuestra lo que el servidor conoce:
 * cada destino debe aparecer una vez y su reversa debe ser el opuesto decimal exacto de todos los
 * movimientos PURCHASE respaldados para ese producto/almacén. Devuelve un hash server-side de esa
 * relación validada; el hash local se conserva aparte como evidencia opaca e idempotente.
 */
function validateVoidImpactSemantics(purchase, payload) {
  if (!Array.isArray(purchase.movements)) {
    throw new HttpsError("data-loss", "PURCHASE_MOVEMENTS_INVALID");
  }
  const expectedSign = purchase.documentType === "CREDIT_NOTE" ? -1n : 1n;
  const originals = purchase.movements.filter((movement) => movement?.type === "PURCHASE");
  if (originals.length === 0) {
    throw new HttpsError("data-loss", "PURCHASE_MOVEMENTS_INVALID");
  }
  const quantitiesByKey = new Map();
  for (const movement of originals) {
    if (
      !UUID_REGEX.test(movement.productId ?? "") ||
      !UUID_REGEX.test(movement.locationId ?? "") ||
      typeof movement.quantityDelta !== "string" ||
      !DECIMAL_REGEX.test(movement.quantityDelta)
    ) {
      throw new HttpsError("data-loss", "PURCHASE_MOVEMENTS_INVALID");
    }
    const quantity = decimalParts(movement.quantityDelta);
    if (quantity.units === 0n || (quantity.units > 0n ? 1n : -1n) !== expectedSign) {
      throw new HttpsError("data-loss", "PURCHASE_MOVEMENTS_INVALID");
    }
    const key = `${movement.productId}\u001f${movement.locationId}`;
    const saved = quantitiesByKey.get(key) ?? [];
    saved.push(movement.quantityDelta);
    quantitiesByKey.set(key, saved);
  }
  if (payload.impacts.length !== quantitiesByKey.size) {
    throw invalid("VOID_IMPACT_SET_MISMATCH");
  }

  const validated = payload.impacts.map((impact) => {
    const key = `${impact.productId}\u001f${impact.locationId}`;
    const originalValues = quantitiesByKey.get(key);
    if (originalValues === undefined) throw invalid("VOID_IMPACT_SET_MISMATCH");
    const original = sumDecimalParts(originalValues);
    const reversal = normalizedDecimalParts(decimalParts(impact.reversalQuantity));
    const comparisonScale = Math.max(original.scale, reversal.scale);
    if (
      decimalUnitsAtScale(original, comparisonScale) +
        decimalUnitsAtScale(reversal, comparisonScale) !== 0n
    ) {
      throw invalid("VOID_IMPACT_REVERSAL_MISMATCH");
    }
    return {
      productId: impact.productId,
      locationId: impact.locationId,
      originalQuantity: normalizedDecimalText(original),
      reversalQuantity: normalizedDecimalText(reversal),
      currentQuantity: normalizedDecimalText(decimalParts(impact.currentQuantity)),
      resultingQuantity: normalizedDecimalText(decimalParts(impact.resultingQuantity)),
      currentAverageUnitCost: normalizedDecimalText(decimalParts(impact.currentAverageUnitCost)),
      currency: impact.currency,
      balanceVersion: impact.balanceVersion,
      negative: impact.negative,
    };
  });
  return sha256(JSON.stringify({ version: 1, purchaseId: payload.purchaseId, impacts: validated }));
}

function requirePurchaseWriteBudget(document, inventoryBalanceCount = 0) {
  // Cada mutación documental cuenta una unidad. Además contamos conservadoramente cada
  // serverTimestamp como otra unidad de wire (purchase + syncChange + auditorías + metadata),
  // aunque comparta documento, y dejamos 20 unidades (4 %) bajo el límite duro de 500 para
  // cambios futuros del contrato. Esta guarda corre antes de abrir la transacción.
  const estimatedWriteUnits =
    10 + document.lines.length + document.movements.length +
    document.auditEventIds.length * 2 + inventoryBalanceCount;
  if (estimatedWriteUnits > MAX_TRANSACTION_WRITE_BUDGET) {
    throw invalid("WRITE_BUDGET_EXCEEDED");
  }
}

function movementSummaryFor(document) {
  const lineNameById = new Map(
    document.lines.map((line) => [line.purchaseLineId, line.productName]),
  );
  return document.movements.map((movement) => ({
    productId: movement.productId,
    productName: lineNameById.get(movement.purchaseLineId) ?? null,
    type: movement.type,
    quantityDelta: movement.quantityDelta,
  }));
}

function syncChangeFor(
  document,
  { seq, status = document.status, movementSummary, receiptId, syncedAt },
) {
  return {
    seq,
    purchaseId: document.purchaseId,
    status,
    documentType: document.documentType,
    documentSeries: document.documentSeries,
    documentNumber: document.documentNumber,
    issueDate: document.issueDate,
    currency: document.currency,
    supplierRuc: document.supplierRuc,
    supplierLegalName: document.supplierLegalName,
    totalMinorUnits: document.totalMinorUnits,
    movementSummary,
    receiptId,
    syncedAt,
  };
}

function requireSyncChangeSize(document, movementSummary) {
  // El contrato de listChanges acepta hasta 200, aunque hoy consulta ventanas internas de 20.
  // Limitar cada proyección a 64 KB evita reintroducir el OOM del agregado completo y deja
  // margen para evolucionar la ventana sin volver a cargar documentos raíz de hasta 900 KB.
  const projection = syncChangeFor(document, {
    seq: Number.MAX_SAFE_INTEGER,
    movementSummary,
    receiptId: `rcpt_${"f".repeat(32)}`,
    syncedAt: "9999-12-31T23:59:59.999999999Z",
  });
  if (serializedByteLength(projection, "DOCUMENT_SHAPE") > MAX_SYNC_CHANGE_BYTES) {
    throw invalid("SYNC_CHANGE_TOO_LARGE");
  }
}

function decimalTextsEqual(left, right) {
  const leftParts = decimalParts(left);
  const rightParts = decimalParts(right);
  const scale = Math.max(leftParts.scale, rightParts.scale);
  return decimalUnitsAtScale(leftParts, scale) === decimalUnitsAtScale(rightParts, scale);
}

/**
 * La compra cloud replica un hecho POSTED ya validado por Room. El cliente normal construye
 * exactamente un movimiento PURCHASE por línea, con la cantidad/costo congelados y el mismo
 * instante de publicación. Estas relaciones también se validan aquí: un cliente autenticado pero
 * modificado no puede publicar una proyección que luego sea imposible de reconciliar o anular.
 *
 * El factor de conversión de unidad no viaja en el wire actual, por lo que el servidor no intenta
 * recalcular inventoryQuantity desde quantity. Sí puede demostrar su igualdad exacta con el
 * movimiento respaldado y todas las demás relaciones cerradas presentes en el documento.
 */
function requirePurchaseMovementSemantics(document) {
  if (document.postedAt === null) throw invalid("POSTED_AT_REQUIRED");

  for (const line of document.lines) {
    if (decimalParts(line.quantity).units <= 0n) throw invalid("LINE_QUANTITY_NON_POSITIVE");
    if (decimalParts(line.readUnitCost).units < 0n) throw invalid("LINE_UNIT_COST_NEGATIVE");
    if (line.inventoryQuantity === null) throw invalid("LINE_INVENTORY_QUANTITY_REQUIRED");
    if (decimalParts(line.inventoryQuantity).units <= 0n) {
      throw invalid("LINE_INVENTORY_QUANTITY_NON_POSITIVE");
    }
    if (line.appliedUnitCost === null) throw invalid("LINE_APPLIED_COST_REQUIRED");
    if (decimalParts(line.appliedUnitCost).units < 0n) {
      throw invalid("LINE_APPLIED_COST_NEGATIVE");
    }
  }

  if (document.movements.length !== document.lines.length) {
    throw invalid("MOVEMENT_LINE_CARDINALITY");
  }
  const movementByLineId = new Map();
  for (const movement of document.movements) {
    if (movementByLineId.has(movement.purchaseLineId)) {
      throw invalid("MOVEMENT_LINE_CARDINALITY");
    }
    movementByLineId.set(movement.purchaseLineId, movement);
  }

  const expectedSign = document.documentType === "CREDIT_NOTE" ? -1n : 1n;
  for (const line of document.lines) {
    const movement = movementByLineId.get(line.purchaseLineId);
    if (movement === undefined) throw invalid("MOVEMENT_LINE_CARDINALITY");
    if (movement.type !== "PURCHASE") throw invalid("PURCHASE_MOVEMENT_TYPE");
    const quantity = decimalParts(movement.quantityDelta);
    if (quantity.units === 0n || (quantity.units > 0n ? 1n : -1n) !== expectedSign) {
      throw invalid("PURCHASE_MOVEMENT_SIGN");
    }
    const absoluteQuantity = movement.quantityDelta.startsWith("-")
      ? movement.quantityDelta.slice(1)
      : movement.quantityDelta;
    if (!decimalTextsEqual(absoluteQuantity, line.inventoryQuantity)) {
      throw invalid("PURCHASE_MOVEMENT_QUANTITY_MISMATCH");
    }
    if (movement.unitCost === null) throw invalid("PURCHASE_MOVEMENT_COST_REQUIRED");
    if (!decimalTextsEqual(movement.unitCost, line.appliedUnitCost)) {
      throw invalid("PURCHASE_MOVEMENT_COST_MISMATCH");
    }
    if (document.version === INVENTORY_PURCHASE_DOCUMENT_VERSION) {
      const recomputedUnitCost = unitCostForAppliedTotal(
        movement.appliedCostTotal,
        movement.quantityDelta,
      );
      if (!decimalTextsEqual(recomputedUnitCost, movement.unitCost)) {
        throw invalid("MOVEMENT_APPLIED_COST_TOTAL_MISMATCH");
      }
    }
    if (movement.occurredAt !== document.postedAt) {
      throw invalid("PURCHASE_MOVEMENT_TIMESTAMP_MISMATCH");
    }
  }
}

function purchaseRecordFor(
  document,
  { payloadHash, movementSummary, seq, receiptId, syncedAt, syncedBy, overrideMetadata },
) {
  const record = {
    version: document.version,
    purchaseId: document.purchaseId,
    businessId: document.businessId,
    status: document.status,
    documentType: document.documentType,
    documentSeries: document.documentSeries,
    documentNumber: document.documentNumber,
    issueDate: document.issueDate,
    currency: document.currency,
    supplierRuc: document.supplierRuc,
    supplierLegalName: document.supplierLegalName,
    subtotalMinorUnits: document.subtotalMinorUnits,
    taxMinorUnits: document.taxMinorUnits,
    otherChargesMinorUnits: document.otherChargesMinorUnits,
    totalMinorUnits: document.totalMinorUnits,
    adjustmentMinorUnits: document.adjustmentMinorUnits,
    adjustmentReason: document.adjustmentReason,
    preparedLogicalHash: document.preparedLogicalHash,
    postedAt: document.postedAt,
    idempotencyKey: document.idempotencyKey,
    lines: document.lines,
    movements: document.movements,
    auditEventIds: document.auditEventIds,
    syncPayloadHash: payloadHash,
    seq,
    movementSummary,
    receiptId,
    syncedAt,
  };
  if (document.version === LEGACY_PURCHASE_DOCUMENT_VERSION) {
    // Compatibilidad de replay: los registros v1 conservan exactamente su forma publicada.
    record.syncedBy = syncedBy;
  } else {
    // v2 minimiza identidad: el callable ya autorizó al principal; el documento legible por
    // miembros conserva solo referencias y rol efectivo, nunca UID, motivo ni huella reversible.
    record.duplicateOverride = overrideMetadata;
  }
  return record;
}

function requirePersistedPurchaseSize(document, payloadHash, movementSummary, uid) {
  // Firestore limita cada documento a 1 MiB. JSON no es una medida exacta del wire de
  // Firestore, por eso medimos la proyección FINAL (incluido movementSummary y campos server)
  // y fijamos un techo de 900 000 bytes: quedan 148 576 bytes (14,2 %) para diferencias de
  // codificación y evolución. Los placeholders tienen el tamaño máximo de sus campos.
  const projection = purchaseRecordFor(document, {
    payloadHash,
    movementSummary,
    seq: Number.MAX_SAFE_INTEGER,
    receiptId: `rcpt_${"f".repeat(32)}`,
    syncedAt: "9999-12-31T23:59:59.999999999Z",
    syncedBy: uid,
    overrideMetadata: document.duplicateOverride === undefined
      ? undefined
      : sanitizedOverrideMetadata(document.duplicateOverride, "OWNER"),
  });
  if (serializedByteLength(projection, "DOCUMENT_SHAPE") > MAX_PERSISTED_PURCHASE_BYTES) {
    throw invalid("PERSISTED_DOCUMENT_TOO_LARGE");
  }
}

function sanitizedOverrideMetadata(override, authorizedRole) {
  if (override === null) return null;
  return {
    existingPurchaseId: override.existingPurchaseId,
    sourceDraftId: override.sourceDraftId,
    auditEventId: override.auditEventId,
    authorizedRole,
  };
}

function purchasePayloadHash(document) {
  if (document.version === LEGACY_PURCHASE_DOCUMENT_VERSION) {
    return sha256(JSON.stringify(document));
  }
  const sanitized = { ...document };
  sanitized.duplicateOverride = document.duplicateOverride === null
    ? null
    : {
      existingPurchaseId: document.duplicateOverride.existingPurchaseId,
      sourceDraftId: document.duplicateOverride.sourceDraftId,
      auditEventId: document.duplicateOverride.auditEventId,
    };
  return sha256(JSON.stringify(sanitized));
}

const documentIdentityHash = (document) =>
  sha256(
    JSON.stringify([
      document.supplierRuc ?? "",
      document.documentType,
      document.documentSeries,
      document.documentNumber,
    ]),
  );

const duplicateOverrideSlotHash = (identityHash, sourceDraftId) =>
  sha256(JSON.stringify(["OVERRIDE", identityHash, sourceDraftId]));

function nextSequence(metadata) {
  const current = metadata.data()?.seq ?? 0;
  if (!Number.isSafeInteger(current) || current < 0 || current === Number.MAX_SAFE_INTEGER) {
    throw new HttpsError("data-loss", "SYNC_SEQUENCE_INVALID");
  }
  return current + 1;
}

async function recordPurchase({
  businessId,
  idempotencyKey,
  document,
  documentVersion,
  uid,
}) {
  const canonicalDocument = canonicalizePurchaseDocument(
    document,
    businessId,
    idempotencyKey,
    documentVersion,
  );
  // En v2 el motivo solo existe durante validación. Tampoco se conserva indirectamente en la
  // huella idempotente: el hash usa la proyección cerrada sin texto ni derivadas de baja entropía.
  const payloadHash = purchasePayloadHash(canonicalDocument);
  const movementSummary = movementSummaryFor(canonicalDocument);
  requirePersistedPurchaseSize(canonicalDocument, payloadHash, movementSummary, uid);
  requireSyncChangeSize(canonicalDocument, movementSummary);
  requirePurchaseMovementSemantics(canonicalDocument);
  const inventoryEnabled = documentVersion === INVENTORY_PURCHASE_DOCUMENT_VERSION;
  const inventoryEffects = inventoryEnabled ? purchaseInventoryEffects(canonicalDocument) : [];
  requirePurchaseWriteBudget(canonicalDocument, inventoryEffects.length);
  const businessRef = db.doc(`businesses/${businessId}`);
  const tombstoneRef = accountDeletionTombstoneRef(uid);
  const memberRef = db.doc(`businesses/${businessId}/members/${uid}`);
  const purchaseRef = db.doc(
    `businesses/${businessId}/purchases/${canonicalDocument.purchaseId}`,
  );
  const keyRef = db.doc(`businesses/${businessId}/syncKeys/${sha256(idempotencyKey)}`);
  const identityHash = documentIdentityHash(canonicalDocument);
  const identityRef = db.doc(`businesses/${businessId}/documentIndex/${identityHash}`);
  const duplicateOverride = canonicalDocument.duplicateOverride ?? null;
  const overrideTargetRef = duplicateOverride === null
    ? null
    : db.doc(
      `businesses/${businessId}/purchases/${duplicateOverride.existingPurchaseId}`,
    );
  const overrideSlotRef = duplicateOverride === null
    ? null
    : db.doc(
      `businesses/${businessId}/documentIndex/${
        duplicateOverrideSlotHash(identityHash, duplicateOverride.sourceDraftId)
      }`,
    );
  const metadataRef = db.doc(`businesses/${businessId}/sync/metadata`);
  const inventoryMetadataRef = db.doc(`businesses/${businessId}/sync/inventoryMetadata`);
  const syncChangeRef = db.doc(
    `businesses/${businessId}/syncChanges/${canonicalDocument.purchaseId}`,
  );
  const receiptId = receiptFor(businessId, idempotencyKey);
  const inventoryChangeRef = businessRef
    .collection("inventorySyncChanges")
    .doc(receiptId);
  const balanceRefs = inventoryEffects.map((effect) =>
    inventoryBalanceRef(businessRef, effect.productId, effect.canonicalLocationName));
  const movementRefs = canonicalDocument.movements.map((movement) =>
    db.doc(`businesses/${businessId}/stockMovements/${movement.movementId}`),
  );
  const auditRefs = canonicalDocument.auditEventIds.map((auditEventId) =>
    db.doc(`businesses/${businessId}/auditEvents/${auditEventId}`),
  );

  return db.runTransaction(async (tx) => {
    const fixedRefs = [
      businessRef,
      tombstoneRef,
      memberRef,
      purchaseRef,
      keyRef,
      identityRef,
      metadataRef,
      syncChangeRef,
    ];
    if (overrideTargetRef !== null) fixedRefs.push(overrideTargetRef);
    if (overrideSlotRef !== null) fixedRefs.push(overrideSlotRef);
    if (inventoryEnabled) fixedRefs.push(inventoryMetadataRef, inventoryChangeRef);
    const snapshots = await tx.getAll(
      ...fixedRefs,
      ...movementRefs,
      ...auditRefs,
      ...balanceRefs,
    );
    let cursor = 0;
    const business = snapshots[cursor++];
    const tombstone = snapshots[cursor++];
    const member = snapshots[cursor++];
    const existing = snapshots[cursor++];
    const keyOwner = snapshots[cursor++];
    const identity = snapshots[cursor++];
    const metadata = snapshots[cursor++];
    const syncChange = snapshots[cursor++];
    const overrideTarget = overrideTargetRef === null ? null : snapshots[cursor++];
    const overrideSlot = overrideSlotRef === null ? null : snapshots[cursor++];
    const inventoryMetadata = inventoryEnabled ? snapshots[cursor++] : null;
    const inventoryChange = inventoryEnabled ? snapshots[cursor++] : null;
    const movementOwners = snapshots.slice(cursor, cursor + movementRefs.length);
    cursor += movementRefs.length;
    const auditOwners = snapshots.slice(cursor, cursor + auditRefs.length);
    cursor += auditRefs.length;
    const balanceSnapshots = snapshots.slice(cursor, cursor + balanceRefs.length);
    requireAccountNotDeleting(tombstone);
    if (!member.exists) throw new HttpsError("permission-denied", "NOT_A_MEMBER");
    requireBusinessNotDeleting(business);
    if (existing.exists) {
      const stored = existing.data();
      // Repetición exacta: mismo acuse, sin reescribir nada (la secuencia no avanza).
      if (stored.idempotencyKey === idempotencyKey && stored.syncPayloadHash === payloadHash) {
        if (
          !syncChange.exists ||
          syncChange.data()?.purchaseId !== canonicalDocument.purchaseId ||
          syncChange.data()?.status !== stored.status ||
          syncChange.data()?.seq !== stored.seq ||
          syncChange.data()?.receiptId !== stored.receiptId
        ) {
          throw new HttpsError("data-loss", "SYNC_CHANGE_INCONSISTENT");
        }
        if (inventoryEnabled && (
          !inventoryChange.exists ||
          inventoryChange.data()?.kind !== "PURCHASE" ||
          inventoryChange.data()?.seq !== stored.inventorySeq ||
          inventoryChange.data()?.receiptId !== stored.receiptId
        )) {
          throw new HttpsError("data-loss", "INVENTORY_CHANGE_INCONSISTENT");
        }
        return {
          receiptId: stored.receiptId,
          idempotencyKey,
          status: "ALREADY_RECORDED",
          ...(inventoryEnabled ? {
            inventorySeq: stored.inventorySeq,
            balances: inventoryChange.data().balances,
          } : {}),
        };
      }
      throw new HttpsError("failed-precondition", "PURCHASE_REPLAY_MISMATCH", {
        existingPurchaseId: existing.id,
        receiptId: stored.receiptId,
      });
    }
    // Un ACK perdido no vuelve a escribir ni eleva privilegios: cualquier miembro que ya puede
    // leer la compra obtiene el mismo acuse. Solo una alta nueva revalida el rol efectivo.
    const effectiveRole = requireMemberRole(member, SYNC_PURCHASE_ROLES);
    if (!inventoryEnabled) {
      throw new HttpsError("failed-precondition", "INVENTORY_WIRE_MIGRATION_REQUIRED");
    }
    if (
      duplicateOverride !== null &&
      !SYNC_DUPLICATE_OVERRIDE_ROLES.includes(effectiveRole)
    ) {
      throw new HttpsError("permission-denied", "DUPLICATE_OVERRIDE_ROLE_FORBIDDEN");
    }
    if (keyOwner.exists) throw new HttpsError("already-exists", "IDEMPOTENCY_KEY_REUSED");
    if (duplicateOverride === null && identity.exists) {
      throw new HttpsError("already-exists", "DOCUMENT_IDENTITY_EXISTS", {
        existingPurchaseId: identity.data().purchaseId,
        receiptId: identity.data().receiptId,
      });
    }
    if (duplicateOverride !== null) {
      if (overrideTarget === null || !overrideTarget.exists) {
        // El teléfono puede haber autorizado contra una compra local cuyo alta aún está en
        // otra outbox/dispositivo. Reintentar conserva causalidad y nunca roba el slot PRIMARY.
        throw new HttpsError("unavailable", "DUPLICATE_TARGET_NOT_SYNCED");
      }
      const target = overrideTarget.data();
      if (target.status !== "POSTED" && target.status !== "VOIDED") {
        throw new HttpsError("failed-precondition", "DUPLICATE_TARGET_STATUS", {
          existingPurchaseId: overrideTarget.id,
          receiptId: target.receiptId ?? null,
        });
      }
      if (
        target.businessId !== businessId ||
        documentIdentityHash(target) !== identityHash
      ) {
        throw new HttpsError(
          "failed-precondition",
          "DUPLICATE_TARGET_IDENTITY_MISMATCH",
          { existingPurchaseId: overrideTarget.id, receiptId: target.receiptId ?? null },
        );
      }
      if (!identity.exists) {
        throw new HttpsError("data-loss", "DOCUMENT_PRIMARY_INDEX_MISSING");
      }
      if (overrideSlot?.exists) {
        throw new HttpsError("already-exists", "DUPLICATE_OVERRIDE_SLOT_EXISTS", {
          existingPurchaseId: overrideSlot.data()?.purchaseId ?? null,
          receiptId: overrideSlot.data()?.receiptId ?? null,
        });
      }
    }
    if (syncChange.exists) {
      throw new HttpsError("already-exists", "SYNC_CHANGE_ID_EXISTS", {
        existingPurchaseId: syncChange.data()?.purchaseId ?? syncChange.id,
      });
    }
    const movementCollision = movementOwners.find((snapshot) => snapshot.exists);
    if (movementCollision !== undefined) {
      throw new HttpsError("already-exists", "MOVEMENT_ID_EXISTS", {
        movementId: movementCollision.id,
        existingPurchaseId: movementCollision.data()?.purchaseId ?? null,
      });
    }
    const auditCollision = auditOwners.find((snapshot) => snapshot.exists);
    if (auditCollision !== undefined) {
      throw new HttpsError("already-exists", "AUDIT_ID_EXISTS", {
        auditEventId: auditCollision.id,
        existingPurchaseId: auditCollision.data()?.purchaseId ?? null,
      });
    }
    if (inventoryChange.exists) {
      throw new HttpsError("data-loss", "INVENTORY_CHANGE_COLLISION");
    }
    await requireMissingBalancesSafeToCreate(
      tx,
      businessRef,
      inventoryEffects,
      balanceSnapshots,
      inventoryMetadata,
    );

    // Secuencia monotónica por negocio: cada alta toma el siguiente seq; el pull
    // incremental consulta la proyección compacta syncChanges por seq.
    const newSeq = nextSequence(metadata);
    const newInventorySeq = nextInventorySequence(inventoryMetadata);
    const inventoryUpdates = buildInventoryUpdates({
      snapshots: balanceSnapshots,
      effects: inventoryEffects,
      refs: balanceRefs,
      currency: canonicalDocument.currency,
      seq: newInventorySeq,
      updatedAtMillis: canonicalDocument.postedAt,
      mode: "PURCHASE_CREATE",
    });
    const inventoryBalances = inventoryUpdates.map((update) => update.projection);
    const overrideMetadata = canonicalDocument.version >= CURRENT_PURCHASE_DOCUMENT_VERSION
      ? sanitizedOverrideMetadata(duplicateOverride, effectiveRole)
      : undefined;
    const purchaseRecord = purchaseRecordFor(canonicalDocument, {
      payloadHash,
      movementSummary,
      seq: newSeq,
      receiptId,
      syncedAt: FieldValue.serverTimestamp(),
      syncedBy: uid,
      overrideMetadata,
    });
    purchaseRecord.inventorySeq = newInventorySeq;
    tx.set(
      purchaseRef,
      purchaseRecord,
    );
    tx.set(
      syncChangeRef,
      syncChangeFor(canonicalDocument, {
        seq: newSeq,
        movementSummary,
        receiptId,
        syncedAt: FieldValue.serverTimestamp(),
      }),
    );
    for (const line of canonicalDocument.lines) {
      // `line` ya es una proyección nueva con catálogo cerrado creada por canonicalizeLine.
      tx.set(purchaseRef.collection("lines").doc(line.purchaseLineId), line);
    }
    canonicalDocument.movements.forEach((movement, index) => {
      const movementRecord = {
        movementId: movement.movementId,
        purchaseLineId: movement.purchaseLineId,
        productId: movement.productId,
        locationId: movement.locationId,
        type: movement.type,
        quantityDelta: movement.quantityDelta,
        unitCost: movement.unitCost,
        occurredAt: movement.occurredAt,
        purchaseId: canonicalDocument.purchaseId,
      };
      if (canonicalDocument.version === INVENTORY_PURCHASE_DOCUMENT_VERSION) {
        const canonicalLocation = canonicalizeLocationName(
          movement.locationName,
          "MOVEMENT_LOCATION_NAME",
        );
        movementRecord.locationName = movement.locationName;
        movementRecord.canonicalLocationName = canonicalLocation.canonical;
        movementRecord.currency = canonicalDocument.currency;
        movementRecord.appliedCostTotal = movement.appliedCostTotal;
      }
      tx.set(movementRefs[index], movementRecord);
    });
    canonicalDocument.auditEventIds.forEach((auditEventId, index) => {
      const auditRecord = {
        auditEventId,
        purchaseId: canonicalDocument.purchaseId,
        syncedAt: FieldValue.serverTimestamp(),
      };
      if (canonicalDocument.version >= CURRENT_PURCHASE_DOCUMENT_VERSION) {
        if (duplicateOverride?.auditEventId === auditEventId) {
          Object.assign(auditRecord, {
            eventType: "PURCHASE_DUPLICATE_OVERRIDE",
            existingPurchaseId: duplicateOverride.existingPurchaseId,
            authorizedRole: effectiveRole,
          });
        } else {
          auditRecord.eventType = "PURCHASE_POSTED";
        }
      }
      tx.set(auditRefs[index], auditRecord);
    });
    tx.set(keyRef, { purchaseId: canonicalDocument.purchaseId, receiptId });
    if (duplicateOverride === null) {
      const primaryIndex = { purchaseId: canonicalDocument.purchaseId, receiptId };
      if (canonicalDocument.version >= CURRENT_PURCHASE_DOCUMENT_VERSION) {
        primaryIndex.slot = "PRIMARY";
      }
      tx.set(identityRef, primaryIndex);
    } else {
      tx.set(overrideSlotRef, {
        purchaseId: canonicalDocument.purchaseId,
        receiptId,
        slot: "OVERRIDE",
        primaryIdentityHash: identityHash,
        sourceDraftId: duplicateOverride.sourceDraftId,
        existingPurchaseId: duplicateOverride.existingPurchaseId,
      });
    }
    tx.set(
      metadataRef,
      {
        seq: newSeq,
        lastSyncedAt: FieldValue.serverTimestamp(),
        lastPurchaseId: canonicalDocument.purchaseId,
        lastReceiptId: receiptId,
      },
      { merge: true },
    );
    for (const update of inventoryUpdates) tx.set(update.ref, update.record);
    tx.create(inventoryChangeRef, {
      schemaVersion: 1,
      kind: "PURCHASE",
      seq: newInventorySeq,
      receiptId,
      sale: null,
      balances: inventoryBalances,
      syncedAt: FieldValue.serverTimestamp(),
    });
    tx.set(inventoryMetadataRef, {
      seq: newInventorySeq,
      lastKind: "PURCHASE",
      lastReceiptId: receiptId,
      updatedAt: FieldValue.serverTimestamp(),
    }, { merge: true });
    return {
      receiptId,
      idempotencyKey,
      status: "RECORDED",
      inventorySeq: newInventorySeq,
      balances: inventoryBalances,
    };
  });
}

async function recordVoid({ businessId, idempotencyKey, document, uid }) {
  const { payload, payloadText } = canonicalizeVoidPayload(document, idempotencyKey);
  const payloadHash = sha256(payloadText);
  const businessRef = db.doc(`businesses/${businessId}`);
  const tombstoneRef = accountDeletionTombstoneRef(uid);
  const memberRef = db.doc(`businesses/${businessId}/members/${uid}`);
  const purchaseRef = db.doc(`businesses/${businessId}/purchases/${payload.purchaseId}`);
  const keyRef = db.doc(`businesses/${businessId}/syncKeys/${sha256(idempotencyKey)}`);
  const metadataRef = db.doc(`businesses/${businessId}/sync/metadata`);
  const inventoryMetadataRef = db.doc(`businesses/${businessId}/sync/inventoryMetadata`);
  const inventoryBootstrapRef = db.doc(`businesses/${businessId}/sync/inventoryBootstrap`);
  const syncChangeRef = db.doc(`businesses/${businessId}/syncChanges/${payload.purchaseId}`);
  const receiptId = receiptFor(businessId, idempotencyKey);
  const inventoryChangeRef = businessRef
    .collection("inventorySyncChanges")
    .doc(receiptId);
  const inventoryUpdatedAtMillis = Date.now();

  return db.runTransaction(async (tx) => {
    const [
      business,
      tombstone,
      member,
      existing,
      keyOwner,
      metadata,
      syncChange,
      inventoryMetadata,
      inventoryChange,
      inventoryBootstrap,
    ] =
      await tx.getAll(
        businessRef,
        tombstoneRef,
        memberRef,
        purchaseRef,
        keyRef,
        metadataRef,
        syncChangeRef,
        inventoryMetadataRef,
        inventoryChangeRef,
        inventoryBootstrapRef,
      );
    requireAccountNotDeleting(tombstone);
    if (!member.exists) throw new HttpsError("permission-denied", "NOT_A_MEMBER");
    requireBusinessNotDeleting(business);
    requireMemberRole(member, SYNC_VOID_ROLES);
    if (!existing.exists) {
      throw new HttpsError("failed-precondition", "PURCHASE_NOT_SYNCED");
    }
    const purchase = existing.data();
    if (purchase.status === "VOIDED") {
      // Repetición exacta: mismo acuse, sin reescribir nada (la secuencia no avanza).
      if (
        purchase.voidIdempotencyKey === idempotencyKey &&
        purchase.voidPayloadHash === payloadHash
      ) {
        if (
          !keyOwner.exists ||
          keyOwner.data()?.purchaseId !== payload.purchaseId ||
          keyOwner.data()?.receiptId !== purchase.voidReceiptId
        ) {
          throw new HttpsError("data-loss", "IDEMPOTENCY_KEY_INCONSISTENT");
        }
        if (
          !syncChange.exists ||
          syncChange.data()?.purchaseId !== payload.purchaseId ||
          syncChange.data()?.status !== "VOIDED" ||
          syncChange.data()?.seq !== purchase.seq
        ) {
          throw new HttpsError("data-loss", "SYNC_CHANGE_INCONSISTENT");
        }
        const hasInventoryVoid = Number.isSafeInteger(purchase.voidInventorySeq);
        if (
          hasInventoryVoid &&
          (
            !inventoryChange.exists ||
            inventoryChange.data()?.kind !== "PURCHASE_VOID" ||
            inventoryChange.data()?.seq !== purchase.voidInventorySeq ||
            inventoryChange.data()?.receiptId !== purchase.voidReceiptId
          )
        ) {
          throw new HttpsError("data-loss", "INVENTORY_CHANGE_INCONSISTENT");
        }
        return {
          receiptId: purchase.voidReceiptId,
          idempotencyKey,
          status: "ALREADY_RECORDED",
          ...(hasInventoryVoid ? {
            inventorySeq: purchase.voidInventorySeq,
            balances: inventoryChange.data().balances,
          } : {}),
        };
      }
      throw new HttpsError("failed-precondition", "VOID_CONFLICT", {
        existingPurchaseId: existing.id,
        receiptId: purchase.voidReceiptId ?? null,
      });
    }
    if (keyOwner.exists) throw new HttpsError("already-exists", "IDEMPOTENCY_KEY_REUSED");
    if (purchase.status !== "POSTED") {
      throw new HttpsError("failed-precondition", "PURCHASE_STATUS_CONFLICT");
    }
    if (
      !syncChange.exists ||
      syncChange.data()?.purchaseId !== payload.purchaseId ||
      syncChange.data()?.status !== "POSTED" ||
      syncChange.data()?.seq !== purchase.seq ||
      !Array.isArray(purchase.movementSummary)
    ) {
      throw new HttpsError("data-loss", "SYNC_CHANGE_INCONSISTENT");
    }
    if (payload.impacts.some((impact) => impact.currency !== purchase.currency)) {
      throw invalid("VOID_IMPACT_CURRENCY_MISMATCH");
    }
    const semanticImpactHash = validateVoidImpactSemantics(purchase, payload);
    const migratedLegacy = purchase.version < INVENTORY_PURCHASE_DOCUMENT_VERSION;
    if (migratedLegacy && (
      inventoryMetadata.data()?.bootstrapComplete !== true ||
      inventoryBootstrap.data()?.schemaVersion !== 1 ||
      !Array.isArray(inventoryBootstrap.data()?.locations)
    )) throw new HttpsError("failed-precondition", "INVENTORY_WIRE_MIGRATION_REQUIRED");
    if (inventoryChange.exists) {
      throw new HttpsError("data-loss", "INVENTORY_CHANGE_COLLISION");
    }
    const inventoryEffects = voidInventoryEffects(
      purchase,
      payload,
      migratedLegacy ? inventoryBootstrap.data().locations : [],
    );
    const balanceRefs = inventoryEffects.map((effect) =>
      inventoryBalanceRef(businessRef, effect.productId, effect.canonicalLocationName));
    const balanceSnapshots = await tx.getAll(...balanceRefs);

    // La anulación también avanza la secuencia: la compra reaparece como cambio (VOIDED)
    // en el pull incremental.
    const newSeq = nextSequence(metadata);
    const newInventorySeq = nextInventorySequence(inventoryMetadata);
    const inventoryUpdates = buildInventoryUpdates({
      snapshots: balanceSnapshots,
      effects: inventoryEffects,
      refs: balanceRefs,
      currency: purchase.currency,
      seq: newInventorySeq,
      updatedAtMillis: inventoryUpdatedAtMillis,
      mode: "VOID",
    });
    const inventoryBalances = inventoryUpdates.map((update) => update.projection);
    tx.update(purchaseRef, {
      status: "VOIDED",
      seq: newSeq,
      voidReceiptId: receiptId,
      voidIdempotencyKey: idempotencyKey,
      voidPayloadHash: payloadHash,
      voidSemanticImpactHash: semanticImpactHash,
      voidReason: payload.reason,
      voidedAt: FieldValue.serverTimestamp(),
      voidedBy: uid,
      voidInventorySeq: newInventorySeq,
      ...(migratedLegacy ? { voidInventoryMigratedLegacy: true } : {}),
    });
    tx.set(
      syncChangeRef,
      syncChangeFor(purchase, {
        seq: newSeq,
        status: "VOIDED",
        movementSummary: purchase.movementSummary,
        // El pull conserva el acuse del alta; voidReceiptId queda en la compra completa.
        receiptId: purchase.receiptId,
        syncedAt: FieldValue.serverTimestamp(),
      }),
    );
    tx.set(purchaseRef.collection("voidRecord").doc("record"), {
      cloudActorUid: uid,
      payload: payloadText,
      impactHash: payload.impactHash,
      semanticImpactHash,
      syncedAt: FieldValue.serverTimestamp(),
    });
    tx.set(db.doc(`businesses/${businessId}/auditEvents/void-audit-${payload.purchaseId}`), {
      auditEventId: `void-audit-${payload.purchaseId}`,
      purchaseId: payload.purchaseId,
      syncedAt: FieldValue.serverTimestamp(),
    });
    tx.set(keyRef, { purchaseId: payload.purchaseId, receiptId });
    tx.set(
      metadataRef,
      {
        seq: newSeq,
        lastSyncedAt: FieldValue.serverTimestamp(),
        lastPurchaseId: payload.purchaseId,
        lastReceiptId: receiptId,
      },
      { merge: true },
    );
    for (const update of inventoryUpdates) tx.set(update.ref, update.record);
    tx.create(inventoryChangeRef, {
      schemaVersion: 1,
      kind: "PURCHASE_VOID",
      seq: newInventorySeq,
      receiptId,
      sale: null,
      balances: inventoryBalances,
      syncedAt: FieldValue.serverTimestamp(),
    });
    tx.set(inventoryMetadataRef, {
      seq: newInventorySeq,
      lastKind: "PURCHASE_VOID",
      lastReceiptId: receiptId,
      updatedAt: FieldValue.serverTimestamp(),
    }, { merge: true });
    return {
      receiptId,
      idempotencyKey,
      status: "RECORDED",
      inventorySeq: newInventorySeq,
      balances: inventoryBalances,
    };
  });
}

// Roles que pueden respaldar compras; la anulación es más restrictiva (solo OWNER/ADMIN).
const SYNC_PURCHASE_ROLES = ["OWNER", "ADMIN", "OPERATOR"];
const SYNC_DUPLICATE_OVERRIDE_ROLES = ["OWNER", "ADMIN"];
const SYNC_VOID_ROLES = ["OWNER", "ADMIN"];

export const postPurchase = onCall(CALLABLE_OPTIONS, async (request) => {
  requireExpectedUid(request);
  requireVerifiedEmail(request);
  const data = request.data ?? {};
  requireExactKeys(data, REQUEST_KEYS, "REQUEST_FIELDS", OPTIONAL_REQUEST_KEYS);
  const { businessId, idempotencyKey, operationType, payloadVersion, document } = data;
  if (!UUID_REGEX.test(businessId ?? "")) throw invalid("BUSINESS_ID");
  requireString(idempotencyKey, "IDEMPOTENCY_KEY", 256);
  if (idempotencyKey !== idempotencyKey.trim()) throw invalid("IDEMPOTENCY_KEY");

  if (operationType === SYNC_PURCHASE) {
    const documentVersion = payloadVersion === LEGACY_PURCHASE_PAYLOAD_VERSION
      ? LEGACY_PURCHASE_DOCUMENT_VERSION
      : payloadVersion === CURRENT_PURCHASE_PAYLOAD_VERSION
        ? CURRENT_PURCHASE_DOCUMENT_VERSION
        : payloadVersion === INVENTORY_PURCHASE_PAYLOAD_VERSION
          ? INVENTORY_PURCHASE_DOCUMENT_VERSION
        : null;
    if (documentVersion === null) throw invalid("PAYLOAD_VERSION");
    return recordPurchase({
      businessId,
      idempotencyKey,
      document,
      documentVersion,
      uid: request.auth.uid,
    });
  }
  if (operationType === SYNC_PURCHASE_VOID) {
    if (payloadVersion !== VOID_PAYLOAD_VERSION) throw invalid("PAYLOAD_VERSION");
    return recordVoid({ businessId, idempotencyKey, document, uid: request.auth.uid });
  }
  throw invalid("OPERATION_TYPE");
});

// Solo desarrollo: siembra la membresía del usuario actual sin exigir email verificado.
// Fuera del Emulator Suite no existe (responde not-found); la membresía real se administra
// con los callables de membership.js.
export const devEnsureMembership = onCall(CALLABLE_OPTIONS, async (request) => {
  if (process.env.FUNCTIONS_EMULATOR !== "true") {
    throw new HttpsError("not-found", "DISABLED_OUTSIDE_EMULATOR");
  }
  if (!request.auth) throw new HttpsError("unauthenticated", "AUTH_REQUIRED");
  const businessId = request.data?.businessId;
  if (!UUID_REGEX.test(businessId ?? "")) throw invalid("BUSINESS_ID");
  const businessRef = db.doc(`businesses/${businessId}`);
  const memberRef = db.doc(`businesses/${businessId}/members/${request.auth.uid}`);
  const tombstoneRef = accountDeletionTombstoneRef(request.auth.uid);
  const ensured = await db.runTransaction(async (tx) => {
    const [business, tombstone, member] = await tx.getAll(
      businessRef,
      tombstoneRef,
      memberRef,
    );
    requireAccountNotDeleting(tombstone);
    if (business.exists) requireBusinessNotDeleting(business);
    const quota = await adjustMembershipQuota(tx, request.auth.uid, member.exists ? 0 : 1);
    if (!quota.allowed) return false;
    tx.set(businessRef, { businessId, seededBy: "devEnsureMembership" }, { merge: true });
    tx.set(memberRef, {
      // Misma forma que las membresías creadas por los callables: `uid` habilita
      // listMyMemberships (collectionGroup) en el flujo de desarrollo.
      uid: request.auth.uid,
      email: request.auth.token.email ?? null,
      role: "OWNER",
      addedAt: FieldValue.serverTimestamp(),
      addedVia: "dev",
    });
    return true;
  });
  if (!ensured) throw new HttpsError("resource-exhausted", "BUSINESS_QUOTA");
  return { ok: true };
});

// Controles exclusivos del Emulator Suite para los E2E del cliente Android. Permiten recorrer
// la verificación y la expiración real de Firebase Auth sin correo externo ni credenciales Admin
// dentro del APK. La guarda depende únicamente de FUNCTIONS_EMULATOR, igual que App Check.
export const devVerifyCurrentUser = onCall(CALLABLE_OPTIONS, async (request) => {
  if (process.env.FUNCTIONS_EMULATOR !== "true") {
    throw new HttpsError("not-found", "DISABLED_OUTSIDE_EMULATOR");
  }
  if (!request.auth) throw new HttpsError("unauthenticated", "AUTH_REQUIRED");
  const fields = Object.keys(request.data ?? {});
  if (fields.length !== 0) throw invalid("DEV_CONTROL_FIELDS");
  await getAuth().updateUser(request.auth.uid, { emailVerified: true });
  return { ok: true };
});

export const devExpireCurrentUser = onCall(CALLABLE_OPTIONS, async (request) => {
  if (process.env.FUNCTIONS_EMULATOR !== "true") {
    throw new HttpsError("not-found", "DISABLED_OUTSIDE_EMULATOR");
  }
  if (!request.auth) throw new HttpsError("unauthenticated", "AUTH_REQUIRED");
  const fields = Object.keys(request.data ?? {});
  if (fields.length !== 0) throw invalid("DEV_CONTROL_FIELDS");
  await getAuth().deleteUser(request.auth.uid);
  return { ok: true };
});

// Callables de membresía: creación de negocio, invitaciones por email y administración de
// miembros/roles. Todos exigen email verificado (ver membership.js).
export {
  createBusiness,
  listMyMemberships,
  inviteMember,
  listBusinessInvitations,
  listMembers,
  listMyInvitations,
  acceptInvitation,
  declineInvitation,
  changeMemberRole,
  removeMember,
} from "./membership.js";

// Pull incremental de respaldos: `listChanges` devuelve los cambios de compras por cursor
// de secuencia (cualquier miembro, incluido READER).
export { listChanges } from "./syncPull.js";

// Catálogos optimistas y feed incremental por versión (Functions es la única escritora).
export { syncCatalogEntity, listCatalogChanges } from "./catalogSync.js";

// Ventas, deudas e inventario compartido: commits atomicos y feed bootstrap/incremental.
export { postSale, recordDebtPayment, listSalesInventoryChanges } from "./saleSync.js";

// Migracion one-shot del saldo local legacy antes de activar el ledger compartido.
export { bootstrapInventoryBalances } from "./inventoryBootstrap.js";

// Respaldo documental: bytes solo por callable/Admin SDK; Storage Rules deniega writes cliente.
export {
  uploadPurchaseDocument,
  purgePurchaseDocument,
  reconcilePurchaseDocumentObject,
  reconcileStaleReservedDocuments,
} from "./documentBackup.js";

// Eliminación de cuenta: `deleteMyAccount` borra negocios propios sin más miembros,
// membresías, invitaciones de cualquier estado ligadas al email verificado, referencias
// históricas del caller y, al final, su usuario de Auth.
export { deleteMyAccount, resumeAccountDeletions } from "./accountDeletion.js";
