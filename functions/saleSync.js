// Sincronizacion multi-dispositivo de ventas, deudas, pagos e inventario. La venta a credito,
// el saldo por cobrar y todos los descuentos de stock se confirman en una sola transaccion;
// cada pago posterior avanza el mismo feed autoritativo sin volver a tocar existencias.
import { createHash } from "node:crypto";
import { onCall, HttpsError } from "firebase-functions/v2/https";
import { FieldValue, Timestamp } from "firebase-admin/firestore";
import {
  CALLABLE_OPTIONS,
  UUID_REGEX,
  accountDeletionTombstoneRef,
  db,
  invalid,
  requireAccountNotDeleting,
  requireBusinessId,
  requireBusinessNotDeleting,
  requireExpectedUid,
  requireMemberRole,
  requireVerifiedEmail,
  sha256,
} from "./common.js";
import {
  buildInventoryUpdates,
  canonicalizeLocationName,
  inventoryBalanceRef,
  nextInventorySequence,
  saleGrossMinorUnits,
  saleInventoryEffects,
} from "./inventorySync.js";

const REQUEST_KEYS = new Set([
  "businessId", "expectedUid", "idempotencyKey", "operationType", "payloadVersion", "document",
]);
const OPTIONAL_REQUEST_KEYS = new Set(["expectedUid"]);
const SALE_DOCUMENT_V1_KEYS = new Set([
  "version", "saleId", "businessId", "status", "currency", "subtotalMinorUnits",
  "discountMinorUnits", "taxMinorUnits", "totalMinorUnits", "contentHash",
  "checkoutIdempotencyKey", "createdAt", "updatedAt", "postedAt", "lines",
]);
const SALE_DOCUMENT_V2_KEYS = new Set([...SALE_DOCUMENT_V1_KEYS, "credit"]);
const CREDIT_KEYS = new Set(["version", "debtId", "debtorNameSnapshot", "dueAt"]);
const LINE_KEYS = new Set([
  "saleLineId", "position", "productId", "unitId", "locationId", "productName",
  "unitCode", "locationName", "barcode", "quantity", "unitPriceMinorUnits",
  "discountMinorUnits", "taxMinorUnits", "lineTotalMinorUnits",
]);
const DEBT_PAYMENT_DOCUMENT_KEYS = new Set([
  "version", "paymentId", "debtId", "businessId", "currency", "amountMinorUnits",
  "method", "note", "reference", "expectedDebtVersion", "occurredAt", "createdAt",
]);
const DEBT_SNAPSHOT_KEYS = new Set([
  "debtId", "businessId", "saleId", "debtorNameSnapshot", "currency",
  "originalMinorUnits", "balanceMinorUnits", "status", "dueAt", "version",
  "createdAt", "updatedAt", "paidAt",
]);
const DEBT_PAYMENT_SNAPSHOT_KEYS = new Set([
  "version", "paymentId", "debtId", "businessId", "currency", "amountMinorUnits",
  "method", "note", "reference", "expectedDebtVersion", "balanceAfterMinorUnits",
  "idempotencyKey", "occurredAt", "createdAt",
]);
const PULL_KEYS = new Set(["businessId", "expectedUid", "sinceSeq", "limit"]);
const OPTIONAL_PULL_KEYS = new Set(["expectedUid", "sinceSeq", "limit"]);
const WRITER_ROLES = ["OWNER", "ADMIN", "OPERATOR"];
const CURRENCY = /^[A-Z]{3}$/;
const SHA256 = /^[0-9a-f]{64}$/;
const QUANTITY = /^(0|[1-9]\d{0,20})(\.\d{1,18})?$/;
const CHECKOUT_KEY = /^sale-checkout:v1:([0-9a-f-]{36}):(\d+):([0-9a-f]{64})$/;
const MAX_LINES = 100;
const MAX_MINOR_UNITS = 1_000_000_000_000_000;
const MAX_DOCUMENT_BYTES = 256_000;
const MAX_LIMIT = 200;
const MAX_QUERY_WINDOW = 20;
const MAX_RESPONSE_BYTES = 4_000_000;
const QUOTA_WINDOW_MILLIS = 60 * 60 * 1000;
const QUOTA_RETENTION_MILLIS = 24 * 60 * 60 * 1000;
const ACTOR_BUSINESS_SALES_PER_HOUR = 300;
const BUSINESS_SALES_PER_HOUR = 2_000;
const ACTOR_BUSINESS_DEBT_PAYMENTS_PER_HOUR = 600;
const BUSINESS_DEBT_PAYMENTS_PER_HOUR = 4_000;
const MAX_PAYMENT_DOCUMENT_BYTES = 32_000;
const DEBT_PAYMENT_METHODS = new Set([
  "CASH", "YAPE", "PLIN", "BANK_TRANSFER", "OTHER",
]);

function exactObject(value, keys, code, optional = null) {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw invalid(code);
  const actual = Object.keys(value);
  if (
    actual.some((key) => !keys.has(key)) ||
    [...keys].some((key) => !optional?.has(key) && !Object.hasOwn(value, key))
  ) {
    throw invalid(code);
  }
  return value;
}

function text(value, code, max) {
  if (typeof value !== "string" || value.length === 0 || value.length > max ||
      value !== value.trim()) {
    throw invalid(code);
  }
  return value;
}

function nullableText(value, code, max) {
  return value === null ? null : text(value, code, max);
}

function normalizedDebtorNameOrNull(value) {
  if (typeof value !== "string") return null;
  const normalized = value.normalize("NFKC").trim().replace(/\s+/gu, " ");
  if (
    normalized.length < 2 || normalized.length > 120 ||
    /[\u0000-\u001f\u007f-\u009f]/u.test(normalized)
  ) {
    return null;
  }
  return normalized;
}

function debtorName(value, code) {
  const normalized = normalizedDebtorNameOrNull(value);
  if (normalized === null || normalized !== value) throw invalid(code);
  return normalized;
}

function normalizedOptionalDebtTextOrNull(value, max) {
  if (typeof value !== "string") return null;
  const normalized = value.normalize("NFKC").trim();
  if (
    normalized.length === 0 || normalized.length > max ||
    /[\u0000-\u001f\u007f-\u009f]/u.test(normalized)
  ) {
    return null;
  }
  return normalized;
}

function optionalDebtText(value, code, max) {
  if (value === null) return null;
  const normalized = normalizedOptionalDebtTextOrNull(value, max);
  if (normalized === null || normalized !== value) throw invalid(code);
  return normalized;
}

function minorUnits(value, code, { positive = false } = {}) {
  if (!Number.isSafeInteger(value) || Math.abs(value) > MAX_MINOR_UNITS ||
      (positive ? value <= 0 : value < 0)) {
    throw invalid(code);
  }
  return value;
}

function millis(value, code) {
  if (!Number.isSafeInteger(value) || value < 0) throw invalid(code);
  return value;
}

function lengthPrefixedDigest(parts) {
  const digest = createHash("sha256");
  for (const part of parts) {
    const bytes = Buffer.from(part, "utf8");
    const length = Buffer.allocUnsafe(4);
    length.writeInt32BE(bytes.length);
    digest.update(length);
    digest.update(bytes);
  }
  return digest.digest("hex");
}

function deterministicUuid(parts) {
  const bytes = Buffer.from(lengthPrefixedDigest(parts), "hex").subarray(0, 16);
  bytes[6] = (bytes[6] & 0x0f) | 0x50;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = bytes.toString("hex");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${
    hex.slice(16, 20)}-${hex.slice(20)}`;
}

function saleContentHash(currency, lines) {
  const parts = ["sale-content-v1", currency];
  for (const line of lines) {
    parts.push(
      line.saleLineId,
      String(line.position),
      line.productId,
      line.unitId,
      line.locationId,
      line.quantity,
      String(line.unitPriceMinorUnits),
      String(line.discountMinorUnits),
      String(line.taxMinorUnits),
      String(line.lineTotalMinorUnits),
      currency,
    );
  }
  return lengthPrefixedDigest(parts);
}

function canonicalLine(raw, position) {
  exactObject(raw, LINE_KEYS, "SALE_LINE_FIELDS");
  if (!UUID_REGEX.test(raw.saleLineId ?? "")) throw invalid("SALE_LINE_ID");
  if (raw.position !== position) throw invalid("SALE_LINE_POSITION");
  if (!UUID_REGEX.test(raw.productId ?? "")) throw invalid("SALE_LINE_PRODUCT_ID");
  if (!UUID_REGEX.test(raw.unitId ?? "")) throw invalid("SALE_LINE_UNIT_ID");
  if (!UUID_REGEX.test(raw.locationId ?? "")) throw invalid("SALE_LINE_LOCATION_ID");
  const productName = text(raw.productName, "SALE_LINE_PRODUCT_NAME", 200);
  const unitCode = text(raw.unitCode, "SALE_LINE_UNIT_CODE", 16);
  const location = canonicalizeLocationName(
    text(raw.locationName, "SALE_LINE_LOCATION_NAME", 100),
    "SALE_LINE_LOCATION_NAME",
  );
  const barcode = nullableText(raw.barcode, "SALE_LINE_BARCODE", 128);
  if (typeof raw.quantity !== "string" || !QUANTITY.test(raw.quantity) ||
      /^0(?:\.0+)?$/.test(raw.quantity)) {
    throw invalid("SALE_LINE_QUANTITY");
  }
  const unitPriceMinorUnits = minorUnits(
    raw.unitPriceMinorUnits,
    "SALE_LINE_UNIT_PRICE",
    { positive: true },
  );
  const discountMinorUnits = minorUnits(raw.discountMinorUnits, "SALE_LINE_DISCOUNT");
  const taxMinorUnits = minorUnits(raw.taxMinorUnits, "SALE_LINE_TAX");
  const lineTotalMinorUnits = minorUnits(raw.lineTotalMinorUnits, "SALE_LINE_TOTAL");
  let gross;
  try {
    gross = saleGrossMinorUnits(raw.quantity, unitPriceMinorUnits);
  } catch (failure) {
    if (failure instanceof HttpsError) throw invalid(failure.message);
    throw failure;
  }
  if (discountMinorUnits > gross || gross - discountMinorUnits + taxMinorUnits !==
      lineTotalMinorUnits) {
    throw invalid("SALE_LINE_TOTAL_MISMATCH");
  }
  return {
    saleLineId: raw.saleLineId,
    position,
    productId: raw.productId,
    unitId: raw.unitId,
    locationId: raw.locationId,
    productName,
    unitCode,
    locationName: location.display,
    barcode,
    quantity: raw.quantity,
    unitPriceMinorUnits,
    discountMinorUnits,
    taxMinorUnits,
    lineTotalMinorUnits,
    grossMinorUnits: gross,
  };
}

function canonicalCredit(raw, saleId, totalMinorUnits) {
  exactObject(raw, CREDIT_KEYS, "SALE_CREDIT_FIELDS");
  if (raw.version !== 1) throw invalid("SALE_CREDIT_VERSION");
  const expectedDebtId = deterministicUuid(["sale-debt", saleId]);
  if (raw.debtId !== expectedDebtId) throw invalid("SALE_DEBT_ID");
  const debtorNameSnapshot = debtorName(raw.debtorNameSnapshot, "SALE_DEBTOR_NAME");
  const dueAt = raw.dueAt === null ? null : millis(raw.dueAt, "SALE_DEBT_DUE_AT");
  if (totalMinorUnits <= 0) throw invalid("SALE_CREDIT_TOTAL");
  return {
    version: 1,
    debtId: expectedDebtId,
    debtorNameSnapshot,
    dueAt,
  };
}

function canonicalDocument(raw, businessId, idempotencyKey, payloadVersion) {
  const documentKeys = payloadVersion === 1
    ? SALE_DOCUMENT_V1_KEYS
    : payloadVersion === 2
      ? SALE_DOCUMENT_V2_KEYS
      : null;
  if (documentKeys === null) throw invalid("PAYLOAD_VERSION");
  exactObject(raw, documentKeys, "SALE_DOCUMENT_FIELDS");
  if (Buffer.byteLength(JSON.stringify(raw), "utf8") > MAX_DOCUMENT_BYTES) {
    throw invalid("SALE_DOCUMENT_TOO_LARGE");
  }
  if (raw.version !== payloadVersion) throw invalid("SALE_DOCUMENT_VERSION");
  if (!UUID_REGEX.test(raw.saleId ?? "")) throw invalid("SALE_ID");
  if (raw.businessId !== businessId) throw invalid("BUSINESS_MISMATCH");
  if (raw.status !== "POSTED") throw invalid("SALE_STATUS");
  if (idempotencyKey !== `sync-sale:v1:${raw.saleId}`) throw invalid("IDEMPOTENCY_KEY");
  if (typeof raw.currency !== "string" || !CURRENCY.test(raw.currency) ||
      !Intl.supportedValuesOf("currency").includes(raw.currency)) {
    throw invalid("SALE_CURRENCY");
  }
  const subtotalMinorUnits = minorUnits(raw.subtotalMinorUnits, "SALE_SUBTOTAL");
  const discountMinorUnits = minorUnits(raw.discountMinorUnits, "SALE_DISCOUNT");
  const taxMinorUnits = minorUnits(raw.taxMinorUnits, "SALE_TAX");
  const totalMinorUnits = minorUnits(raw.totalMinorUnits, "SALE_TOTAL");
  if (!SHA256.test(raw.contentHash ?? "")) throw invalid("SALE_CONTENT_HASH");
  const checkoutIdempotencyKey = text(
    raw.checkoutIdempotencyKey,
    "SALE_CHECKOUT_KEY",
    256,
  );
  const checkoutMatch = CHECKOUT_KEY.exec(checkoutIdempotencyKey);
  if (
    checkoutMatch === null || checkoutMatch[1] !== raw.saleId ||
    checkoutMatch[3] !== raw.contentHash ||
    !Number.isSafeInteger(Number(checkoutMatch[2]))
  ) {
    throw invalid("SALE_CHECKOUT_KEY");
  }
  const createdAt = millis(raw.createdAt, "SALE_CREATED_AT");
  const updatedAt = millis(raw.updatedAt, "SALE_UPDATED_AT");
  const postedAt = millis(raw.postedAt, "SALE_POSTED_AT");
  if (createdAt > updatedAt || updatedAt > postedAt) throw invalid("SALE_TIMESTAMPS");
  if (!Array.isArray(raw.lines) || raw.lines.length === 0 || raw.lines.length > MAX_LINES) {
    throw invalid("SALE_LINES_LIMIT");
  }
  const lines = raw.lines.map(canonicalLine);
  if (new Set(lines.map((line) => line.saleLineId)).size !== lines.length) {
    throw invalid("SALE_LINE_ID_DUPLICATED");
  }
  const productLocationKeys = lines.map((line) => {
    const location = canonicalizeLocationName(line.locationName, "SALE_LINE_LOCATION_NAME");
    return `${line.productId}\u001f${location.canonical}`;
  });
  if (new Set(productLocationKeys).size !== productLocationKeys.length) {
    throw invalid("SALE_PRODUCT_LOCATION_DUPLICATED");
  }
  const expectedSubtotal = lines.reduce((sum, line) => sum + BigInt(line.grossMinorUnits), 0n);
  const expectedDiscount = lines.reduce(
    (sum, line) => sum + BigInt(line.discountMinorUnits),
    0n,
  );
  const expectedTax = lines.reduce((sum, line) => sum + BigInt(line.taxMinorUnits), 0n);
  const expectedTotal = expectedSubtotal - expectedDiscount + expectedTax;
  if (
    expectedSubtotal !== BigInt(subtotalMinorUnits) ||
    expectedDiscount !== BigInt(discountMinorUnits) ||
    expectedTax !== BigInt(taxMinorUnits) ||
    expectedTotal !== BigInt(totalMinorUnits)
  ) {
    throw invalid("SALE_TOTAL_MISMATCH");
  }
  const persistedLines = lines.map(({ grossMinorUnits: _gross, ...line }) => line);
  if (saleContentHash(raw.currency, persistedLines) !== raw.contentHash) {
    throw invalid("SALE_CONTENT_HASH_MISMATCH");
  }
  const document = {
    version: payloadVersion,
    saleId: raw.saleId,
    businessId,
    status: "POSTED",
    currency: raw.currency,
    subtotalMinorUnits,
    discountMinorUnits,
    taxMinorUnits,
    totalMinorUnits,
    contentHash: raw.contentHash,
    checkoutIdempotencyKey,
    createdAt,
    updatedAt,
    postedAt,
    lines: persistedLines,
  };
  if (payloadVersion === 2) {
    document.credit = canonicalCredit(raw.credit, raw.saleId, totalMinorUnits);
  }
  return document;
}

function canonicalDebtPaymentDocument(raw, businessId, idempotencyKey) {
  exactObject(raw, DEBT_PAYMENT_DOCUMENT_KEYS, "DEBT_PAYMENT_DOCUMENT_FIELDS");
  if (Buffer.byteLength(JSON.stringify(raw), "utf8") > MAX_PAYMENT_DOCUMENT_BYTES) {
    throw invalid("DEBT_PAYMENT_DOCUMENT_TOO_LARGE");
  }
  if (raw.version !== 1) throw invalid("DEBT_PAYMENT_DOCUMENT_VERSION");
  if (!UUID_REGEX.test(raw.paymentId ?? "")) throw invalid("DEBT_PAYMENT_ID");
  if (!UUID_REGEX.test(raw.debtId ?? "")) throw invalid("DEBT_ID");
  if (raw.businessId !== businessId) throw invalid("BUSINESS_MISMATCH");
  if (idempotencyKey !== `debt-payment:v1:${raw.debtId}:${raw.paymentId}`) {
    throw invalid("IDEMPOTENCY_KEY");
  }
  if (typeof raw.currency !== "string" || !CURRENCY.test(raw.currency) ||
      !Intl.supportedValuesOf("currency").includes(raw.currency)) {
    throw invalid("DEBT_PAYMENT_CURRENCY");
  }
  const amountMinorUnits = minorUnits(
    raw.amountMinorUnits,
    "DEBT_PAYMENT_AMOUNT",
    { positive: true },
  );
  if (!DEBT_PAYMENT_METHODS.has(raw.method)) throw invalid("DEBT_PAYMENT_METHOD");
  const note = optionalDebtText(raw.note, "DEBT_PAYMENT_NOTE", 500);
  const reference = optionalDebtText(raw.reference, "DEBT_PAYMENT_REFERENCE", 120);
  if (!Number.isSafeInteger(raw.expectedDebtVersion) || raw.expectedDebtVersion < 1 ||
      raw.expectedDebtVersion >= Number.MAX_SAFE_INTEGER) {
    throw invalid("DEBT_PAYMENT_EXPECTED_VERSION");
  }
  const occurredAt = millis(raw.occurredAt, "DEBT_PAYMENT_OCCURRED_AT");
  const createdAt = millis(raw.createdAt, "DEBT_PAYMENT_CREATED_AT");
  if (occurredAt > createdAt) throw invalid("DEBT_PAYMENT_TIMESTAMPS");
  return {
    version: 1,
    paymentId: raw.paymentId,
    debtId: raw.debtId,
    businessId,
    currency: raw.currency,
    amountMinorUnits,
    method: raw.method,
    note,
    reference,
    expectedDebtVersion: raw.expectedDebtVersion,
    occurredAt,
    createdAt,
  };
}

function exactKeys(value, keys) {
  return value !== null && typeof value === "object" && !Array.isArray(value) &&
    Object.keys(value).length === keys.size &&
    Object.keys(value).every((key) => keys.has(key));
}

function sameScalarSnapshot(left, right, keys) {
  return exactKeys(left, keys) && exactKeys(right, keys) &&
    [...keys].every((key) => left[key] === right[key]);
}

function validDebtorName(value) {
  return typeof value === "string" && normalizedDebtorNameOrNull(value) === value;
}

function validNullableTrimmedText(value, max) {
  return value === null || normalizedOptionalDebtTextOrNull(value, max) === value;
}

function validMillis(value) {
  return Number.isSafeInteger(value) && value >= 0;
}

function validCurrency(value) {
  return typeof value === "string" && CURRENCY.test(value) &&
    Intl.supportedValuesOf("currency").includes(value);
}

function requireDebtSnapshot(value) {
  const valid = exactKeys(value, DEBT_SNAPSHOT_KEYS) &&
    UUID_REGEX.test(value.debtId ?? "") &&
    UUID_REGEX.test(value.businessId ?? "") &&
    UUID_REGEX.test(value.saleId ?? "") &&
    validDebtorName(value.debtorNameSnapshot) &&
    validCurrency(value.currency) &&
    Number.isSafeInteger(value.originalMinorUnits) && value.originalMinorUnits > 0 &&
    value.originalMinorUnits <= MAX_MINOR_UNITS &&
    Number.isSafeInteger(value.balanceMinorUnits) && value.balanceMinorUnits >= 0 &&
    value.balanceMinorUnits <= value.originalMinorUnits &&
    ["OPEN", "PAID"].includes(value.status) &&
    (value.dueAt === null || validMillis(value.dueAt)) &&
    Number.isSafeInteger(value.version) && value.version >= 1 &&
    value.version < Number.MAX_SAFE_INTEGER &&
    validMillis(value.createdAt) && validMillis(value.updatedAt) &&
    value.createdAt <= value.updatedAt &&
    (value.paidAt === null || validMillis(value.paidAt)) &&
    ((value.balanceMinorUnits === 0 && value.status === "PAID" &&
      value.paidAt !== null && value.paidAt === value.updatedAt) ||
     (value.balanceMinorUnits > 0 && value.status === "OPEN" && value.paidAt === null));
  if (!valid) throw new HttpsError("data-loss", "DEBT_SNAPSHOT_INVALID");
  return value;
}

function debtSnapshotFromRecord(value) {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    throw new HttpsError("data-loss", "DEBT_SNAPSHOT_INVALID");
  }
  return requireDebtSnapshot(Object.fromEntries(
    [...DEBT_SNAPSHOT_KEYS].map((key) => [key, value[key]]),
  ));
}

function requireDebtPaymentSnapshot(value) {
  const valid = exactKeys(value, DEBT_PAYMENT_SNAPSHOT_KEYS) &&
    value.version === 1 &&
    UUID_REGEX.test(value.paymentId ?? "") &&
    UUID_REGEX.test(value.debtId ?? "") &&
    UUID_REGEX.test(value.businessId ?? "") &&
    validCurrency(value.currency) &&
    Number.isSafeInteger(value.amountMinorUnits) && value.amountMinorUnits > 0 &&
    value.amountMinorUnits <= MAX_MINOR_UNITS &&
    DEBT_PAYMENT_METHODS.has(value.method) &&
    validNullableTrimmedText(value.note, 500) &&
    validNullableTrimmedText(value.reference, 120) &&
    Number.isSafeInteger(value.expectedDebtVersion) && value.expectedDebtVersion >= 1 &&
    value.expectedDebtVersion < Number.MAX_SAFE_INTEGER &&
    Number.isSafeInteger(value.balanceAfterMinorUnits) &&
    value.balanceAfterMinorUnits >= 0 && value.balanceAfterMinorUnits <= MAX_MINOR_UNITS &&
    value.idempotencyKey === `debt-payment:v1:${value.debtId}:${value.paymentId}` &&
    validMillis(value.occurredAt) && validMillis(value.createdAt) &&
    value.occurredAt <= value.createdAt;
  if (!valid) throw new HttpsError("data-loss", "DEBT_PAYMENT_SNAPSHOT_INVALID");
  return value;
}

function debtPaymentSnapshotFromRecord(value) {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    throw new HttpsError("data-loss", "DEBT_PAYMENT_SNAPSHOT_INVALID");
  }
  return requireDebtPaymentSnapshot(Object.fromEntries(
    [...DEBT_PAYMENT_SNAPSHOT_KEYS].map((key) => [key, value[key]]),
  ));
}

function recordedDebtPaymentFor(
  document,
  idempotencyKey,
  balanceAfterMinorUnits,
  effectiveCreatedAt,
) {
  return {
    version: 1,
    paymentId: document.paymentId,
    debtId: document.debtId,
    businessId: document.businessId,
    currency: document.currency,
    amountMinorUnits: document.amountMinorUnits,
    method: document.method,
    note: document.note,
    reference: document.reference,
    expectedDebtVersion: document.expectedDebtVersion,
    balanceAfterMinorUnits,
    idempotencyKey,
    occurredAt: document.occurredAt,
    createdAt: effectiveCreatedAt,
  };
}

function openedDebtFor(document, effectivePostedAt) {
  const credit = document.credit;
  return {
    debtId: credit.debtId,
    businessId: document.businessId,
    saleId: document.saleId,
    debtorNameSnapshot: credit.debtorNameSnapshot,
    currency: document.currency,
    originalMinorUnits: document.totalMinorUnits,
    balanceMinorUnits: document.totalMinorUnits,
    status: "OPEN",
    dueAt: credit.dueAt,
    version: 1,
    createdAt: effectivePostedAt,
    updatedAt: effectivePostedAt,
    paidAt: null,
  };
}

function receiptFor(businessId, idempotencyKey) {
  return `sale_${sha256(`facturastock:sale:v1:${businessId}:${idempotencyKey}`).slice(0, 32)}`;
}

function debtPaymentReceiptFor(businessId, idempotencyKey) {
  return `debt_payment_${sha256(
    `facturastock:debt-payment:v1:${businessId}:${idempotencyKey}`,
  ).slice(0, 32)}`;
}

function quotaRef(scope, identity) {
  return db.doc(
    `syncMutationRateLimits/${sha256(`facturastock:sync-quota:v1:${scope}:${identity}`)}`,
  );
}

function nextQuota(snapshot, scope, limit, nowMillis, errorCode = "SALE_SYNC_QUOTA") {
  let windowStartedAtMillis = nowMillis;
  let count = 0;
  if (snapshot.exists) {
    const data = snapshot.data();
    const savedStart = data.windowStartedAt?.toMillis?.();
    if (
      data.schemaVersion !== 1 || data.scope !== scope ||
      !Number.isSafeInteger(savedStart) || savedStart > nowMillis + 30_000 ||
      !Number.isSafeInteger(data.count) || data.count < 0
    ) {
      throw new HttpsError("data-loss", "SYNC_QUOTA_INVALID");
    }
    if (nowMillis - savedStart < QUOTA_WINDOW_MILLIS) {
      windowStartedAtMillis = savedStart;
      count = data.count;
    }
  }
  if (count >= limit) {
    throw new HttpsError("resource-exhausted", errorCode, {
      scope,
      retryAfterMillis: Math.max(1, windowStartedAtMillis + QUOTA_WINDOW_MILLIS - nowMillis),
    });
  }
  return {
    schemaVersion: 1,
    scope,
    count: count + 1,
    windowStartedAt: Timestamp.fromMillis(windowStartedAtMillis),
    expiresAt: Timestamp.fromMillis(windowStartedAtMillis + QUOTA_RETENTION_MILLIS),
  };
}

function productData(snapshot, productId) {
  if (!snapshot.exists) {
    throw new HttpsError("failed-precondition", "SALE_PRODUCT_NOT_SYNCED", { productId });
  }
  const data = snapshot.data();
  if (data?.entityType !== "PRODUCT" || data.snapshot?.status !== "ACTIVE") {
    throw new HttpsError("failed-precondition", "SALE_PRODUCT_UNAVAILABLE", { productId });
  }
  const hasPrice = Object.hasOwn(data.snapshot, "salePriceMinorUnits");
  const hasCurrency = Object.hasOwn(data.snapshot, "salePriceCurrencyCode");
  if (hasPrice !== hasCurrency) throw new HttpsError("data-loss", "CATALOG_REMOTE_PRODUCT_SCHEMA_INVALID");
  return data;
}

function saleForFeed(document, receiptId, seq, movements) {
  return { ...document, receiptId, seq, movements };
}

function movementForLine(document, line, openingAverage) {
  const location = canonicalizeLocationName(line.locationName, "SALE_LINE_LOCATION_NAME");
  const movementId = deterministicUuid([
    "sale-stock-movement",
    document.saleId,
    line.saleLineId,
  ]);
  return {
    movementId,
    saleId: document.saleId,
    saleLineId: line.saleLineId,
    productId: line.productId,
    sourceLocationId: line.locationId,
    locationName: location.display,
    canonicalLocationName: location.canonical,
    type: "SALE",
    quantityDelta: line.quantity.startsWith("-") ? line.quantity.slice(1) : `-${line.quantity}`,
    unitCost: openingAverage,
    currency: document.currency,
    occurredAt: document.postedAt,
  };
}

async function postSaleHandler(request) {
  requireExpectedUid(request);
  requireVerifiedEmail(request);
  const data = exactObject(
    request.data ?? {},
    REQUEST_KEYS,
    "SALE_REQUEST_FIELDS",
    OPTIONAL_REQUEST_KEYS,
  );
  const businessId = requireBusinessId(data.businessId);
  const idempotencyKey = text(data.idempotencyKey, "IDEMPOTENCY_KEY", 256);
  if (data.operationType !== "SYNC_SALE") throw invalid("OPERATION_TYPE");
  if (![1, 2].includes(data.payloadVersion)) throw invalid("PAYLOAD_VERSION");
  const document = canonicalDocument(
    data.document,
    businessId,
    idempotencyKey,
    data.payloadVersion,
  );
  const requestHash = sha256(JSON.stringify({
    businessId,
    idempotencyKey,
    operationType: data.operationType,
    payloadVersion: data.payloadVersion,
    document,
  }));
  const uid = request.auth.uid;
  const nowMillis = Date.now();
  const businessRef = db.doc(`businesses/${businessId}`);
  const saleRef = businessRef.collection("sales").doc(document.saleId);
  const debtRef = document.credit === undefined
    ? null
    : businessRef.collection("debts").doc(document.credit.debtId);
  const keyRef = businessRef.collection("saleSyncKeys").doc(sha256(idempotencyKey));
  const inventoryMetadataRef = businessRef.collection("sync").doc("inventoryMetadata");
  const receiptId = receiptFor(businessId, idempotencyKey);
  const changeRef = businessRef.collection("inventorySyncChanges").doc(receiptId);
  const actorQuotaRef = quotaRef("ACTOR_BUSINESS_SALE", `${businessId}\u001f${uid}`);
  const businessQuotaRef = quotaRef("BUSINESS_SALE", businessId);
  const productIds = [...new Set(document.lines.map((line) => line.productId))].sort();
  const productRefs = productIds.map((productId) => businessRef.collection("products").doc(productId));
  const effects = saleInventoryEffects(document.lines);
  const balanceRefs = effects.map((effect) =>
    inventoryBalanceRef(businessRef, effect.productId, effect.canonicalLocationName));

  return db.runTransaction(async (tx) => {
    const fixedRefs = [
      businessRef,
      accountDeletionTombstoneRef(uid),
      businessRef.collection("members").doc(uid),
      saleRef,
      keyRef,
      inventoryMetadataRef,
      changeRef,
      actorQuotaRef,
      businessQuotaRef,
      ...(debtRef === null ? [] : [debtRef]),
    ];
    const snapshots = await tx.getAll(...fixedRefs, ...productRefs, ...balanceRefs);
    const [
      business, tombstone, member, existingSale, keyOwner, inventoryMetadata,
      existingChange, actorQuota, businessQuota,
    ] = snapshots;
    const existingDebt = debtRef === null ? null : snapshots[fixedRefs.length - 1];
    const productSnapshots = snapshots.slice(fixedRefs.length, fixedRefs.length + productRefs.length);
    const balanceSnapshots = snapshots.slice(fixedRefs.length + productRefs.length);
    requireAccountNotDeleting(tombstone);
    requireBusinessNotDeleting(business);
    if (!member.exists) throw new HttpsError("permission-denied", "NOT_A_MEMBER");

    if (existingSale.exists) {
      const saved = existingSale.data();
      if (saved.idempotencyKey !== idempotencyKey || saved.syncPayloadHash !== requestHash) {
        throw new HttpsError("failed-precondition", "SALE_REPLAY_MISMATCH", {
          existingSaleId: existingSale.id,
          receiptId: saved.receiptId ?? null,
        });
      }
      if (
        !keyOwner.exists || keyOwner.data()?.saleId !== document.saleId ||
        keyOwner.data()?.receiptId !== saved.receiptId ||
        !existingChange.exists || existingChange.data()?.seq !== saved.inventorySeq
      ) {
        throw new HttpsError("data-loss", "SALE_IDEMPOTENCY_INCONSISTENT");
      }
      if (document.credit !== undefined) {
        const initialDebt = requireDebtSnapshot(saved.debt);
        const expectedInitialDebt = openedDebtFor(saved, saved.postedAt);
        if (!sameScalarSnapshot(initialDebt, expectedInitialDebt, DEBT_SNAPSHOT_KEYS) ||
            !existingDebt?.exists) {
          throw new HttpsError("data-loss", "SALE_DEBT_IDEMPOTENCY_INCONSISTENT");
        }
        const currentDebt = debtSnapshotFromRecord(existingDebt.data());
        if (
          currentDebt.debtId !== initialDebt.debtId ||
          currentDebt.businessId !== initialDebt.businessId ||
          currentDebt.saleId !== initialDebt.saleId ||
          currentDebt.debtorNameSnapshot !== initialDebt.debtorNameSnapshot ||
          currentDebt.currency !== initialDebt.currency ||
          currentDebt.originalMinorUnits !== initialDebt.originalMinorUnits ||
          currentDebt.dueAt !== initialDebt.dueAt ||
          currentDebt.createdAt !== initialDebt.createdAt ||
          currentDebt.version < initialDebt.version ||
          currentDebt.updatedAt < initialDebt.updatedAt
        ) {
          throw new HttpsError("data-loss", "SALE_DEBT_IDEMPOTENCY_INCONSISTENT");
        }
      }
      const replay = {
        receiptId: saved.receiptId,
        idempotencyKey,
        status: "ALREADY_RECORDED",
        seq: saved.inventorySeq,
        postedAtMillis: saved.postedAt,
        balances: saved.balances,
      };
      if (document.credit !== undefined) replay.debt = saved.debt;
      return replay;
    }

    const effectiveRole = requireMemberRole(member, WRITER_ROLES);
    if (keyOwner.exists) throw new HttpsError("already-exists", "IDEMPOTENCY_KEY_REUSED");
    if (existingChange.exists) throw new HttpsError("data-loss", "SALE_CHANGE_COLLISION");
    if (existingDebt?.exists) {
      throw new HttpsError("failed-precondition", "SALE_DEBT_COLLISION", {
        debtId: document.credit.debtId,
      });
    }
    const productById = new Map(
      productIds.map((productId, index) => [productId, productData(productSnapshots[index], productId)]),
    );
    for (const line of document.lines) {
      const product = productById.get(line.productId);
      if (product.snapshot.inventoryUnit?.code !== line.unitCode) {
        throw new HttpsError("failed-precondition", "SALE_UNIT_MISMATCH", {
          productId: line.productId,
        });
      }
      if (product.snapshot.salePriceCurrencyCode !== null &&
          product.snapshot.salePriceCurrencyCode !== undefined &&
          product.snapshot.salePriceCurrencyCode !== document.currency) {
        throw new HttpsError("failed-precondition", "SALE_CURRENCY_MISMATCH", {
          productId: line.productId,
        });
      }
    }

    const seq = nextInventorySequence(inventoryMetadata);
    const effectivePostedAt = Math.max(
      nowMillis,
      document.postedAt,
      ...balanceSnapshots.map((snapshot) => snapshot.data()?.updatedAtMillis ?? 0),
    );
    const effectiveDocument = {
      ...document,
      // El hecho cloud puede confirmarse despues del POSTED local. Para que un segundo
      // dispositivo materialice una venta valida, updatedAt y postedAt avanzan juntos.
      updatedAt: effectivePostedAt,
      postedAt: effectivePostedAt,
    };
    const updates = buildInventoryUpdates({
      snapshots: balanceSnapshots,
      effects,
      refs: balanceRefs,
      currency: document.currency,
      seq,
      updatedAtMillis: effectivePostedAt,
      mode: "SALE",
    });
    const effectIndex = new Map(
      effects.map((effect, index) => [
        `${effect.productId}\u001f${effect.canonicalLocationName}`,
        index,
      ]),
    );
    const movements = document.lines.map((line) => {
      const location = canonicalizeLocationName(line.locationName, "SALE_LINE_LOCATION_NAME");
      const index = effectIndex.get(`${line.productId}\u001f${location.canonical}`);
      const openingAverage = balanceSnapshots[index].data().averageUnitCost;
      return movementForLine(effectiveDocument, line, openingAverage);
    });
    const balances = updates.map((update) => update.projection);
    const feedSale = saleForFeed(effectiveDocument, receiptId, seq, movements);
    const openedDebt = document.credit === undefined
      ? null
      : openedDebtFor(effectiveDocument, effectivePostedAt);

    tx.set(actorQuotaRef, nextQuota(
      actorQuota,
      "ACTOR_BUSINESS_SALE",
      ACTOR_BUSINESS_SALES_PER_HOUR,
      nowMillis,
    ));
    tx.set(businessQuotaRef, nextQuota(
      businessQuota,
      "BUSINESS_SALE",
      BUSINESS_SALES_PER_HOUR,
      nowMillis,
    ));
    const saleRecord = {
      ...effectiveDocument,
      idempotencyKey,
      syncPayloadHash: requestHash,
      authorizedRole: effectiveRole,
      receiptId,
      inventorySeq: seq,
      balances,
      syncedAt: FieldValue.serverTimestamp(),
    };
    if (openedDebt !== null) saleRecord.debt = openedDebt;
    tx.create(saleRef, saleRecord);
    for (const line of document.lines) {
      tx.create(saleRef.collection("lines").doc(line.saleLineId), line);
    }
    for (const movement of movements) {
      tx.create(businessRef.collection("stockMovements").doc(movement.movementId), movement);
    }
    if (openedDebt !== null) {
      tx.create(debtRef, {
        schemaVersion: 1,
        ...openedDebt,
        receiptId,
        inventorySeq: seq,
        authorizedRole: effectiveRole,
        syncedAt: FieldValue.serverTimestamp(),
      });
      tx.create(businessRef.collection("auditEvents").doc(`debt-audit-${openedDebt.debtId}`), {
        auditEventId: `debt-audit-${openedDebt.debtId}`,
        eventType: "DEBT_OPENED",
        entityType: "DEBT",
        entityId: openedDebt.debtId,
        saleId: document.saleId,
        authorizedRole: effectiveRole,
        occurredAt: effectivePostedAt,
        syncedAt: FieldValue.serverTimestamp(),
      });
    }
    for (const update of updates) tx.set(update.ref, update.record);
    tx.create(changeRef, {
      schemaVersion: 1,
      kind: "SALE",
      seq,
      receiptId,
      sale: feedSale,
      balances,
      syncedAt: FieldValue.serverTimestamp(),
    });
    tx.create(keyRef, { saleId: document.saleId, receiptId, seq, requestHash });
    tx.create(businessRef.collection("auditEvents").doc(`sale-audit-${document.saleId}`), {
      auditEventId: `sale-audit-${document.saleId}`,
      eventType: "SALE_POSTED",
      entityType: "SALE",
      entityId: document.saleId,
      authorizedRole: effectiveRole,
      occurredAt: effectivePostedAt,
      syncedAt: FieldValue.serverTimestamp(),
    });
    tx.set(inventoryMetadataRef, {
      seq,
      lastKind: "SALE",
      lastReceiptId: receiptId,
      updatedAt: FieldValue.serverTimestamp(),
    }, { merge: true });
    const result = {
      receiptId,
      idempotencyKey,
      status: "RECORDED",
      seq,
      postedAtMillis: effectivePostedAt,
      balances,
    };
    if (openedDebt !== null) result.debt = openedDebt;
    return result;
  });
}

export const postSale = onCall(CALLABLE_OPTIONS, postSaleHandler);

async function recordDebtPaymentHandler(request) {
  requireExpectedUid(request);
  requireVerifiedEmail(request);
  const data = exactObject(
    request.data ?? {},
    REQUEST_KEYS,
    "DEBT_PAYMENT_REQUEST_FIELDS",
    OPTIONAL_REQUEST_KEYS,
  );
  const businessId = requireBusinessId(data.businessId);
  const idempotencyKey = text(data.idempotencyKey, "IDEMPOTENCY_KEY", 256);
  if (data.operationType !== "SYNC_DEBT_PAYMENT") throw invalid("OPERATION_TYPE");
  if (data.payloadVersion !== 1) throw invalid("PAYLOAD_VERSION");
  const document = canonicalDebtPaymentDocument(
    data.document,
    businessId,
    idempotencyKey,
  );
  const requestHash = sha256(JSON.stringify({
    businessId,
    idempotencyKey,
    operationType: data.operationType,
    payloadVersion: data.payloadVersion,
    document,
  }));
  const uid = request.auth.uid;
  const nowMillis = Date.now();
  const businessRef = db.doc(`businesses/${businessId}`);
  const debtRef = businessRef.collection("debts").doc(document.debtId);
  const paymentRef = debtRef.collection("payments").doc(document.paymentId);
  const keyRef = businessRef.collection("debtPaymentSyncKeys").doc(sha256(idempotencyKey));
  const inventoryMetadataRef = businessRef.collection("sync").doc("inventoryMetadata");
  const receiptId = debtPaymentReceiptFor(businessId, idempotencyKey);
  const changeRef = businessRef.collection("inventorySyncChanges").doc(receiptId);
  const actorQuotaRef = quotaRef(
    "ACTOR_BUSINESS_DEBT_PAYMENT",
    `${businessId}\u001f${uid}`,
  );
  const businessQuotaRef = quotaRef("BUSINESS_DEBT_PAYMENT", businessId);

  return db.runTransaction(async (tx) => {
    const [
      business,
      tombstone,
      member,
      debt,
      existingPayment,
      keyOwner,
      inventoryMetadata,
      existingChange,
      actorQuota,
      businessQuota,
    ] = await tx.getAll(
      businessRef,
      accountDeletionTombstoneRef(uid),
      businessRef.collection("members").doc(uid),
      debtRef,
      paymentRef,
      keyRef,
      inventoryMetadataRef,
      changeRef,
      actorQuotaRef,
      businessQuotaRef,
    );
    requireAccountNotDeleting(tombstone);
    requireBusinessNotDeleting(business);
    if (!member.exists) throw new HttpsError("permission-denied", "NOT_A_MEMBER");

    if (existingPayment.exists) {
      const saved = existingPayment.data();
      if (saved.idempotencyKey !== idempotencyKey || saved.syncPayloadHash !== requestHash) {
        throw new HttpsError("failed-precondition", "DEBT_PAYMENT_REPLAY_MISMATCH", {
          debtId: document.debtId,
          paymentId: document.paymentId,
          receiptId: saved.receiptId ?? null,
        });
      }
      const payment = debtPaymentSnapshotFromRecord(saved);
      const resultingDebt = requireDebtSnapshot(saved.resultingDebt);
      const change = existingChange.data();
      if (
        !debt.exists ||
        !keyOwner.exists ||
        keyOwner.data()?.debtId !== document.debtId ||
        keyOwner.data()?.paymentId !== document.paymentId ||
        keyOwner.data()?.receiptId !== saved.receiptId ||
        keyOwner.data()?.requestHash !== requestHash ||
        !existingChange.exists ||
        change?.kind !== "DEBT_PAYMENT" ||
        change.seq !== saved.inventorySeq ||
        change.receiptId !== saved.receiptId ||
        !sameScalarSnapshot(
          requireDebtSnapshot(change.debt),
          resultingDebt,
          DEBT_SNAPSHOT_KEYS,
        ) ||
        !sameScalarSnapshot(
          requireDebtPaymentSnapshot(change.payment),
          payment,
          DEBT_PAYMENT_SNAPSHOT_KEYS,
        )
      ) {
        throw new HttpsError("data-loss", "DEBT_PAYMENT_IDEMPOTENCY_INCONSISTENT");
      }
      const currentDebt = debtSnapshotFromRecord(debt.data());
      if (
        currentDebt.debtId !== resultingDebt.debtId ||
        currentDebt.businessId !== resultingDebt.businessId ||
        currentDebt.saleId !== resultingDebt.saleId ||
        currentDebt.debtorNameSnapshot !== resultingDebt.debtorNameSnapshot ||
        currentDebt.currency !== resultingDebt.currency ||
        currentDebt.originalMinorUnits !== resultingDebt.originalMinorUnits ||
        currentDebt.dueAt !== resultingDebt.dueAt ||
        currentDebt.createdAt !== resultingDebt.createdAt ||
        currentDebt.version < resultingDebt.version ||
        currentDebt.updatedAt < resultingDebt.updatedAt
      ) {
        throw new HttpsError("data-loss", "DEBT_PAYMENT_IDEMPOTENCY_INCONSISTENT");
      }
      return {
        receiptId: saved.receiptId,
        idempotencyKey,
        status: "ALREADY_RECORDED",
        seq: saved.inventorySeq,
        debt: resultingDebt,
        payment,
      };
    }

    const effectiveRole = requireMemberRole(member, WRITER_ROLES);
    if (keyOwner.exists || existingChange.exists) {
      throw new HttpsError("data-loss", "DEBT_PAYMENT_IDEMPOTENCY_INCONSISTENT");
    }
    if (!debt.exists) {
      throw new HttpsError("failed-precondition", "DEBT_NOT_FOUND", {
        debtId: document.debtId,
      });
    }
    if (debt.data()?.schemaVersion !== 1) {
      throw new HttpsError("data-loss", "DEBT_SNAPSHOT_INVALID");
    }
    const currentDebt = debtSnapshotFromRecord(debt.data());
    if (
      currentDebt.debtId !== document.debtId ||
      currentDebt.businessId !== businessId ||
      deterministicUuid(["sale-debt", currentDebt.saleId]) !== currentDebt.debtId
    ) {
      throw new HttpsError("data-loss", "DEBT_SNAPSHOT_INVALID");
    }
    if (currentDebt.currency !== document.currency) {
      throw new HttpsError("failed-precondition", "DEBT_PAYMENT_CURRENCY_MISMATCH", {
        debtId: document.debtId,
      });
    }
    if (currentDebt.version !== document.expectedDebtVersion) {
      throw new HttpsError("aborted", "DEBT_VERSION_CONFLICT", {
        debtId: document.debtId,
        expectedVersion: document.expectedDebtVersion,
        actualVersion: currentDebt.version,
      });
    }
    if (currentDebt.status !== "OPEN" || currentDebt.balanceMinorUnits === 0) {
      throw new HttpsError("failed-precondition", "DEBT_ALREADY_PAID", {
        debtId: document.debtId,
      });
    }
    if (document.amountMinorUnits > currentDebt.balanceMinorUnits) {
      throw new HttpsError("failed-precondition", "DEBT_PAYMENT_EXCEEDS_BALANCE", {
        debtId: document.debtId,
        balanceMinorUnits: currentDebt.balanceMinorUnits,
      });
    }

    const seq = nextInventorySequence(inventoryMetadata);
    const effectiveUpdatedAt = Math.max(
      nowMillis,
      document.createdAt,
      currentDebt.updatedAt,
    );
    const balanceAfterMinorUnits =
      currentDebt.balanceMinorUnits - document.amountMinorUnits;
    const resultingDebt = requireDebtSnapshot({
      ...currentDebt,
      balanceMinorUnits: balanceAfterMinorUnits,
      status: balanceAfterMinorUnits === 0 ? "PAID" : "OPEN",
      version: currentDebt.version + 1,
      updatedAt: effectiveUpdatedAt,
      paidAt: balanceAfterMinorUnits === 0 ? effectiveUpdatedAt : null,
    });
    const payment = requireDebtPaymentSnapshot(recordedDebtPaymentFor(
      document,
      idempotencyKey,
      balanceAfterMinorUnits,
      effectiveUpdatedAt,
    ));

    tx.set(actorQuotaRef, nextQuota(
      actorQuota,
      "ACTOR_BUSINESS_DEBT_PAYMENT",
      ACTOR_BUSINESS_DEBT_PAYMENTS_PER_HOUR,
      nowMillis,
      "DEBT_PAYMENT_SYNC_QUOTA",
    ));
    tx.set(businessQuotaRef, nextQuota(
      businessQuota,
      "BUSINESS_DEBT_PAYMENT",
      BUSINESS_DEBT_PAYMENTS_PER_HOUR,
      nowMillis,
      "DEBT_PAYMENT_SYNC_QUOTA",
    ));
    tx.update(debtRef, {
      ...resultingDebt,
      lastPaymentId: document.paymentId,
      lastPaymentReceiptId: receiptId,
      inventorySeq: seq,
      authorizedRole: effectiveRole,
      syncedAt: FieldValue.serverTimestamp(),
    });
    tx.create(paymentRef, {
      schemaVersion: 1,
      ...payment,
      syncPayloadHash: requestHash,
      authorizedRole: effectiveRole,
      receiptId,
      inventorySeq: seq,
      resultingDebt,
      syncedAt: FieldValue.serverTimestamp(),
    });
    tx.create(changeRef, {
      schemaVersion: 1,
      kind: "DEBT_PAYMENT",
      seq,
      receiptId,
      sale: null,
      balances: [],
      debt: resultingDebt,
      payment,
      syncedAt: FieldValue.serverTimestamp(),
    });
    tx.create(keyRef, {
      debtId: document.debtId,
      paymentId: document.paymentId,
      receiptId,
      seq,
      requestHash,
    });
    tx.create(
      businessRef.collection("auditEvents").doc(`debt-payment-audit-${document.paymentId}`),
      {
        auditEventId: `debt-payment-audit-${document.paymentId}`,
        eventType: "DEBT_PAYMENT_RECORDED",
        entityType: "DEBT_PAYMENT",
        entityId: document.paymentId,
        debtId: document.debtId,
        authorizedRole: effectiveRole,
        occurredAt: document.occurredAt,
        syncedAt: FieldValue.serverTimestamp(),
      },
    );
    tx.set(inventoryMetadataRef, {
      seq,
      lastKind: "DEBT_PAYMENT",
      lastReceiptId: receiptId,
      updatedAt: FieldValue.serverTimestamp(),
    }, { merge: true });
    return {
      receiptId,
      idempotencyKey,
      status: "RECORDED",
      seq,
      debt: resultingDebt,
      payment,
    };
  });
}

export const recordDebtPayment = onCall(CALLABLE_OPTIONS, recordDebtPaymentHandler);

function pullLimit(value) {
  const limit = value ?? 100;
  if (!Number.isInteger(limit) || limit < 1 || limit > MAX_LIMIT) throw invalid("LIMIT");
  return limit;
}

function sinceSeq(value) {
  const seq = value ?? 0;
  if (!Number.isSafeInteger(seq) || seq < 0) throw invalid("SINCE_SEQ");
  return seq;
}

function feedChange(snapshot) {
  const data = snapshot.data();
  const kind = data?.kind;
  if (
    data?.schemaVersion !== 1 ||
    !["SALE", "PURCHASE", "PURCHASE_VOID", "DEBT_PAYMENT"].includes(kind) ||
    !Number.isSafeInteger(data.seq) || data.seq < 1 ||
    typeof data.receiptId !== "string" ||
    !Array.isArray(data.balances) ||
    (kind === "SALE") !== (data.sale !== null) ||
    (kind !== "DEBT_PAYMENT" &&
      (Object.hasOwn(data, "debt") || Object.hasOwn(data, "payment")))
  ) {
    throw new HttpsError("data-loss", "INVENTORY_CHANGE_INVALID");
  }
  if (kind === "SALE") {
    const sale = data.sale;
    const validCredit = sale?.version === 1
      ? !Object.hasOwn(sale, "credit")
      : sale?.version === 2 && exactKeys(sale.credit, CREDIT_KEYS) &&
        sale.credit.version === 1 &&
        sale.credit.debtId === deterministicUuid(["sale-debt", sale.saleId]) &&
        validDebtorName(sale.credit.debtorNameSnapshot) &&
        (sale.credit.dueAt === null || validMillis(sale.credit.dueAt)) &&
        Number.isSafeInteger(sale.totalMinorUnits) && sale.totalMinorUnits > 0;
    if (!validCredit) throw new HttpsError("data-loss", "INVENTORY_CHANGE_INVALID");
  }
  const change = {
    kind,
    seq: data.seq,
    receiptId: data.receiptId,
    sale: data.sale,
    balances: data.balances,
    syncedAtMillis: data.syncedAt?.toMillis?.() ?? null,
  };
  if (kind === "DEBT_PAYMENT") {
    if (data.sale !== null || data.balances.length !== 0) {
      throw new HttpsError("data-loss", "INVENTORY_CHANGE_INVALID");
    }
    const debt = requireDebtSnapshot(data.debt);
    const payment = requireDebtPaymentSnapshot(data.payment);
    if (
      debt.debtId !== payment.debtId ||
      debt.businessId !== payment.businessId ||
      debt.currency !== payment.currency ||
      debt.version !== payment.expectedDebtVersion + 1 ||
      debt.balanceMinorUnits !== payment.balanceAfterMinorUnits ||
      debt.updatedAt !== payment.createdAt
    ) {
      throw new HttpsError("data-loss", "INVENTORY_CHANGE_INVALID");
    }
    change.debt = debt;
    change.payment = payment;
  }
  return change;
}

export const listSalesInventoryChanges = onCall(CALLABLE_OPTIONS, async (request) => {
  requireExpectedUid(request);
  requireVerifiedEmail(request);
  const data = exactObject(
    request.data ?? {},
    PULL_KEYS,
    "INVENTORY_PULL_FIELDS",
    OPTIONAL_PULL_KEYS,
  );
  const businessId = requireBusinessId(data.businessId);
  const cursor = sinceSeq(data.sinceSeq);
  const window = Math.min(pullLimit(data.limit), MAX_QUERY_WINDOW);
  const businessRef = db.doc(`businesses/${businessId}`);
  const metadataRef = businessRef.collection("sync").doc("inventoryMetadata");
  const query = businessRef.collection("inventorySyncChanges")
    .where("seq", ">", cursor)
    .orderBy("seq", "asc")
    .limit(window + 1);
  return db.runTransaction(async (tx) => {
    const [business, tombstone, member, metadata] = await tx.getAll(
      businessRef,
      accountDeletionTombstoneRef(request.auth.uid),
      businessRef.collection("members").doc(request.auth.uid),
      metadataRef,
    );
    requireAccountNotDeleting(tombstone);
    requireBusinessNotDeleting(business);
    if (!member.exists) throw new HttpsError("permission-denied", "NOT_A_MEMBER");
    const snapshot = await tx.get(query);
    const metadataSeq = metadata.data()?.seq ?? 0;
    if (!Number.isSafeInteger(metadataSeq) || metadataSeq < 0) {
      throw new HttpsError("data-loss", "INVENTORY_SEQUENCE_INVALID");
    }
    if (cursor > metadataSeq) {
      throw new HttpsError("failed-precondition", "INVENTORY_CURSOR_AHEAD", {
        cursor,
        latestSeq: metadataSeq,
      });
    }
    const changes = [];
    let bytes = 0;
    for (const document of snapshot.docs.slice(0, window)) {
      const change = feedChange(document);
      const expectedSeq = cursor + changes.length + 1;
      if (change.seq !== expectedSeq) {
        throw new HttpsError("data-loss", "INVENTORY_CHANGE_GAP", {
          expectedSeq,
          actualSeq: change.seq,
        });
      }
      const size = Buffer.byteLength(JSON.stringify(change), "utf8");
      if (changes.length > 0 && bytes + size > MAX_RESPONSE_BYTES) break;
      if (size > MAX_RESPONSE_BYTES) {
        throw new HttpsError("data-loss", "INVENTORY_CHANGE_TOO_LARGE");
      }
      changes.push(change);
      bytes += size;
    }
    if (changes.length === 0 && cursor < metadataSeq) {
      throw new HttpsError("data-loss", "INVENTORY_CHANGE_GAP", {
        expectedSeq: cursor + 1,
      });
    }
    return {
      changes,
      nextCursor: changes.at(-1)?.seq ?? cursor,
      hasMore: snapshot.size > changes.length,
      latestSeq: metadataSeq,
    };
  });
});
