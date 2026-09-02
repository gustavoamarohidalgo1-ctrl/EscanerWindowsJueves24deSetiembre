// Catálogo optimista multi-dispositivo. Toda escritura deriva tenant/rol de Auth+membresía,
// usa CAS de versión y deja un feed incremental cerrado; nunca confía en businessId local ni
// en UUIDs de unidad/almacén de otro teléfono.
import { onCall, HttpsError } from "firebase-functions/v2/https";
import { FieldValue } from "firebase-admin/firestore";
import {
  CALLABLE_OPTIONS,
  UUID_REGEX,
  accountDeletionTombstoneRef,
  db,
  invalid,
  requireAccountNotDeleting,
  requireExpectedUid,
  requireBusinessId,
  requireBusinessNotDeleting,
  requireMemberRole,
  requireVerifiedEmail,
  sha256,
} from "./common.js";

const REQUEST_KEYS = new Set([
  "businessId",
  "idempotencyKey",
  "operationType",
  "payloadVersion",
  "document",
  "expectedUid",
]);
const OPTIONAL_REQUEST_KEYS = new Set(["expectedUid"]);
const CATALOG_PULL_KEYS = new Set(["businessId", "sinceSeq", "limit", "expectedUid"]);
const DOCUMENT_KEYS = new Set([
  "version",
  "entityId",
  "expectedVersion",
  "targetVersion",
  "mutation",
  "snapshot",
]);
const LEGACY_PRODUCT_KEYS = new Set([
  "name", "sku", "barcode", "inventoryUnit", "purchaseUnit", "purchaseFactor",
  "location", "status", "createdAt", "updatedAt",
]);
const PRODUCT_V2_KEYS = new Set([
  ...LEGACY_PRODUCT_KEYS,
  "salePriceMinorUnits",
  "salePriceCurrencyCode",
]);
const SUPPLIER_KEYS = new Set([
  "legalName", "ruc", "tradeName", "status", "createdAt", "updatedAt",
]);
const UNIT_KEYS = new Set(["code", "name", "symbol", "status"]);
const LOCATION_KEYS = new Set(["name", "status"]);
const WRITER_ROLES = ["OWNER", "ADMIN", "OPERATOR"];
const CATALOG_TYPES = {
  SYNC_PRODUCT: { entityType: "PRODUCT", collection: "products" },
  SYNC_SUPPLIER: { entityType: "SUPPLIER", collection: "suppliers" },
};
const STATUS = new Set(["ACTIVE", "ARCHIVED"]);
const RUC = /^\d{11}$/;
const UNIT_CODE = /^[A-Z0-9]{1,16}$/;
const CURRENCY_CODE = /^[A-Z]{3}$/;
const ISO_CURRENCY_CODES = new Set(Intl.supportedValuesOf("currency"));
const DECIMAL = /^(0|[1-9]\d*)(\.\d+)?$/;
const MAX_DECIMAL_PRECISION = 38;
const MAX_DECIMAL_SCALE = 18;
const MAX_SAFE_SEQ = Number.MAX_SAFE_INTEGER;
const MAX_SNAPSHOT_BYTES = 64_000;
const MAX_LIMIT = 200;
const MAX_QUERY_WINDOW = 20;
const MAX_RESPONSE_BYTES = 4_000_000;

function exactObject(value, keys, code, optionalKeys = null) {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw invalid(code);
  const actual = Object.keys(value);
  if (
    actual.some((key) => !keys.has(key)) ||
    [...keys].some((key) => !optionalKeys?.has(key) && !Object.hasOwn(value, key))
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
  if (value === null) return null;
  return text(value, code, max);
}

function timestampMillis(value, code) {
  if (!Number.isSafeInteger(value) || value < 0) throw invalid(code);
  return value;
}

/** Emula la política BigDecimal Android sin convertir a Number ni perder precisión. */
function positiveExactDecimal(value) {
  if (!DECIMAL.test(value)) return false;
  const [integerPart, fractionalPart = ""] = value.split(".");
  if (fractionalPart.length > MAX_DECIMAL_SCALE) return false;
  const unscaled = `${integerPart}${fractionalPart}`;
  const significant = unscaled.replace(/^0+/, "");
  const precision = significant.length === 0 ? 1 : significant.length;
  return significant.length > 0 && precision <= MAX_DECIMAL_PRECISION;
}

function catalogStatus(value, code) {
  if (!STATUS.has(value)) throw invalid(code);
  return value;
}

function canonicalUnit(value, prefix) {
  exactObject(value, UNIT_KEYS, `${prefix}_FIELDS`);
  const code = text(value.code, `${prefix}_CODE`, 16);
  if (!UNIT_CODE.test(code)) throw invalid(`${prefix}_CODE`);
  const symbol = nullableText(value.symbol, `${prefix}_SYMBOL`, 16);
  return {
    code,
    name: text(value.name, `${prefix}_NAME`, 100),
    symbol,
    status: catalogStatus(value.status, `${prefix}_STATUS`),
  };
}

function canonicalLocation(value) {
  if (value === null) return null;
  exactObject(value, LOCATION_KEYS, "LOCATION_FIELDS");
  return {
    name: text(value.name, "LOCATION_NAME", 100),
    status: catalogStatus(value.status, "LOCATION_STATUS"),
  };
}

function canonicalProduct(snapshot, version) {
  exactObject(
    snapshot,
    version === 1 ? LEGACY_PRODUCT_KEYS : PRODUCT_V2_KEYS,
    "PRODUCT_FIELDS",
  );
  const sku = nullableText(snapshot.sku, "PRODUCT_SKU", 64);
  if (sku !== null && sku !== sku.toUpperCase()) throw invalid("PRODUCT_SKU_CANONICAL");
  const barcode = nullableText(snapshot.barcode, "PRODUCT_BARCODE", 128);
  let salePriceMinorUnits;
  let salePriceCurrencyCode;
  if (version === 2) {
    salePriceMinorUnits = snapshot.salePriceMinorUnits;
    salePriceCurrencyCode = snapshot.salePriceCurrencyCode;
    if ((salePriceMinorUnits === null) !== (salePriceCurrencyCode === null)) {
      throw invalid("PRODUCT_SALE_PRICE_PAIR");
    }
    if (salePriceMinorUnits !== null &&
        (!Number.isSafeInteger(salePriceMinorUnits) || salePriceMinorUnits <= 0)) {
      throw invalid("PRODUCT_SALE_PRICE_MINOR_UNITS");
    }
    if (salePriceCurrencyCode !== null &&
        (typeof salePriceCurrencyCode !== "string" ||
          !CURRENCY_CODE.test(salePriceCurrencyCode) ||
          !ISO_CURRENCY_CODES.has(salePriceCurrencyCode))) {
      throw invalid("PRODUCT_SALE_PRICE_CURRENCY");
    }
  }
  const purchaseUnit = snapshot.purchaseUnit === null ? null :
    canonicalUnit(snapshot.purchaseUnit, "PURCHASE_UNIT");
  const purchaseFactor = nullableText(snapshot.purchaseFactor, "PURCHASE_FACTOR", 64);
  if ((purchaseUnit === null) !== (purchaseFactor === null)) {
    throw invalid("PURCHASE_UNIT_FACTOR");
  }
  if (purchaseFactor !== null && !positiveExactDecimal(purchaseFactor)) {
    throw invalid("PURCHASE_FACTOR");
  }
  const createdAt = timestampMillis(snapshot.createdAt, "PRODUCT_CREATED_AT");
  const updatedAt = timestampMillis(snapshot.updatedAt, "PRODUCT_UPDATED_AT");
  if (updatedAt < createdAt) throw invalid("PRODUCT_TIMESTAMPS");
  return {
    name: text(snapshot.name, "PRODUCT_NAME", 200),
    sku,
    barcode,
    ...(version === 2 ? { salePriceMinorUnits, salePriceCurrencyCode } : {}),
    inventoryUnit: canonicalUnit(snapshot.inventoryUnit, "INVENTORY_UNIT"),
    purchaseUnit,
    purchaseFactor,
    location: canonicalLocation(snapshot.location),
    status: catalogStatus(snapshot.status, "PRODUCT_STATUS"),
    createdAt,
    updatedAt,
  };
}

function canonicalSupplier(snapshot) {
  exactObject(snapshot, SUPPLIER_KEYS, "SUPPLIER_FIELDS");
  const ruc = nullableText(snapshot.ruc, "SUPPLIER_RUC", 11);
  if (ruc !== null && !RUC.test(ruc)) throw invalid("SUPPLIER_RUC");
  const createdAt = timestampMillis(snapshot.createdAt, "SUPPLIER_CREATED_AT");
  const updatedAt = timestampMillis(snapshot.updatedAt, "SUPPLIER_UPDATED_AT");
  if (updatedAt < createdAt) throw invalid("SUPPLIER_TIMESTAMPS");
  return {
    legalName: text(snapshot.legalName, "SUPPLIER_LEGAL_NAME", 200),
    ruc,
    tradeName: nullableText(snapshot.tradeName, "SUPPLIER_TRADE_NAME", 200),
    status: catalogStatus(snapshot.status, "SUPPLIER_STATUS"),
    createdAt,
    updatedAt,
  };
}

function canonicalDocument(raw, operationType, payloadVersion) {
  exactObject(raw, DOCUMENT_KEYS, "CATALOG_DOCUMENT_FIELDS");
  const allowedVersions = operationType === "SYNC_PRODUCT" ? new Set([1, 2]) : new Set([1]);
  if (!allowedVersions.has(raw.version) || raw.version !== payloadVersion) {
    throw invalid("CATALOG_DOCUMENT_VERSION");
  }
  if (!UUID_REGEX.test(raw.entityId ?? "")) throw invalid("CATALOG_ENTITY_ID");
  if (!Number.isSafeInteger(raw.expectedVersion) || raw.expectedVersion < 0) {
    throw invalid("CATALOG_EXPECTED_VERSION");
  }
  if (!Number.isSafeInteger(raw.targetVersion) ||
      raw.targetVersion !== raw.expectedVersion + 1) {
    throw invalid("CATALOG_TARGET_VERSION");
  }
  if (raw.mutation !== "UPSERT") throw invalid("CATALOG_MUTATION");
  const snapshot = operationType === "SYNC_PRODUCT" ?
    canonicalProduct(raw.snapshot, raw.version) : canonicalSupplier(raw.snapshot);
  const snapshotPayload = JSON.stringify(snapshot);
  if (Buffer.byteLength(snapshotPayload, "utf8") > MAX_SNAPSHOT_BYTES) {
    throw invalid("CATALOG_SNAPSHOT_TOO_LARGE");
  }
  return {
    version: raw.version,
    entityId: raw.entityId,
    expectedVersion: raw.expectedVersion,
    targetVersion: raw.targetVersion,
    mutation: "UPSERT",
    snapshot,
    snapshotPayload,
    snapshotSha256: sha256(snapshotPayload),
  };
}

function semanticKeys(entityType, snapshot) {
  if (entityType === "SUPPLIER") {
    return snapshot.ruc === null ? [] : [{ kind: "supplierRuc", value: snapshot.ruc }];
  }
  return [
    snapshot.sku === null ? null : { kind: "productSku", value: snapshot.sku },
    snapshot.barcode === null ? null : { kind: "productBarcode", value: snapshot.barcode },
  ].filter(Boolean);
}

function indexRef(businessRef, semantic) {
  const collection = semantic.kind === "supplierRuc" ? "supplierRucIndex" :
    semantic.kind === "productSku" ? "productSkuIndex" : "productBarcodeIndex";
  return businessRef.collection(collection).doc(sha256(semantic.value));
}

function receiptFor(idempotencyKey) {
  return `cat_${sha256(`facturastock:catalog-receipt:v1:${idempotencyKey}`).slice(0, 32)}`;
}

function conflictDetails(entityId, entityData, conflictCode) {
  if (!entityData) return null;
  const snapshotPayload = JSON.stringify(entityData.snapshot);
  return {
    conflictCode,
    remoteEntityId: entityId,
    remoteVersion: entityData.version,
    remoteSnapshotPayload: snapshotPayload,
    remoteSyncedAtMillis: entityData.syncedAt?.toMillis?.() ?? null,
    remoteOrigin: "CLOUD",
  };
}

function versionConflict(entityId, entityData, code = "CATALOG_VERSION_CONFLICT") {
  throw new HttpsError("aborted", code, conflictDetails(entityId, entityData, code));
}

async function syncCatalog(request) {
  requireExpectedUid(request);
  requireVerifiedEmail(request);
  const data = exactObject(
    request.data ?? {},
    REQUEST_KEYS,
    "CATALOG_REQUEST_FIELDS",
    OPTIONAL_REQUEST_KEYS,
  );
  const businessId = requireBusinessId(data.businessId);
  const idempotencyKey = text(data.idempotencyKey, "IDEMPOTENCY_KEY", 256);
  const descriptor = CATALOG_TYPES[data.operationType];
  if (!descriptor) throw invalid("OPERATION_TYPE");
  const allowedPayloadVersions = data.operationType === "SYNC_PRODUCT" ?
    new Set([1, 2]) : new Set([1]);
  if (!allowedPayloadVersions.has(data.payloadVersion)) throw invalid("PAYLOAD_VERSION");
  const document = canonicalDocument(data.document, data.operationType, data.payloadVersion);
  const expectedKey = data.operationType === "SYNC_PRODUCT" ?
    `sync-product:v${document.version}:${document.entityId}:${document.targetVersion}` :
    `sync-supplier:v1:${document.entityId}:${document.targetVersion}`;
  if (idempotencyKey !== expectedKey) throw invalid("IDEMPOTENCY_KEY_SHAPE");
  const canonicalRequest = {
    businessId,
    idempotencyKey,
    operationType: data.operationType,
    payloadVersion: data.payloadVersion,
    document: {
      version: document.version,
      entityId: document.entityId,
      expectedVersion: document.expectedVersion,
      targetVersion: document.targetVersion,
      mutation: document.mutation,
      snapshot: document.snapshot,
    },
  };
  const requestHash = sha256(JSON.stringify(canonicalRequest));
  const receiptId = receiptFor(idempotencyKey);
  const businessRef = db.doc(`businesses/${businessId}`);
  const memberRef = businessRef.collection("members").doc(request.auth.uid);
  const entityRef = businessRef.collection(descriptor.collection).doc(document.entityId);
  const operationRef = businessRef.collection("catalogSyncOperations").doc(sha256(idempotencyKey));
  const metadataRef = businessRef.collection("sync").doc("catalogMetadata");
  const tombstoneRef = accountDeletionTombstoneRef(request.auth.uid);

  return db.runTransaction(async (tx) => {
    const [business, tombstone, member, existingOperation, existingEntity, metadata] =
      await tx.getAll(
        businessRef,
        tombstoneRef,
        memberRef,
        operationRef,
        entityRef,
        metadataRef,
      );
    requireAccountNotDeleting(tombstone);
    requireBusinessNotDeleting(business);
    if (!member.exists) throw new HttpsError("permission-denied", "NOT_A_MEMBER");

    if (existingOperation.exists) {
      const saved = existingOperation.data();
      if (saved.requestHash !== requestHash || saved.idempotencyKey !== idempotencyKey) {
        // Dos dispositivos parten de la misma versión y generan la misma clave causal
        // entity+target con snapshots distintos. No es un overwrite ni un fallo opaco de
        // idempotencia: se devuelve la comparación ganadora para resolución humana.
        versionConflict(
          document.entityId,
          existingEntity.exists ? existingEntity.data() : null,
          "CATALOG_VERSION_CONFLICT",
        );
      }
      return {
        ok: true,
        replayed: true,
        idempotencyKey,
        receiptId: saved.receiptId,
        entityId: saved.entityId,
        version: saved.version,
        seq: saved.seq,
      };
    }

    const authorizedRole = requireMemberRole(member, WRITER_ROLES);
    const current = existingEntity.exists ? existingEntity.data() : null;
    const currentVersion = current?.version ?? 0;
    if (!Number.isSafeInteger(currentVersion) || currentVersion < 0) {
      throw new HttpsError("data-loss", "CATALOG_REMOTE_VERSION_INVALID");
    }
    if (currentVersion !== document.expectedVersion) {
      versionConflict(document.entityId, current);
    }
    if (current && current.entityType !== descriptor.entityType) {
      throw new HttpsError("data-loss", "CATALOG_REMOTE_TYPE_INVALID");
    }
    if (current && descriptor.entityType === "PRODUCT") {
      const hasMinorUnits = Object.hasOwn(current.snapshot ?? {}, "salePriceMinorUnits");
      const hasCurrency = Object.hasOwn(current.snapshot ?? {}, "salePriceCurrencyCode");
      if (hasMinorUnits !== hasCurrency) {
        throw new HttpsError("data-loss", "CATALOG_REMOTE_PRODUCT_SCHEMA_INVALID");
      }
      if (hasMinorUnits && document.version === 1) {
        // Un cliente legado puede seguir editando productos que aún son v1, pero nunca debe
        // borrar un precio que otro cliente ya publicó con el contrato v2.
        versionConflict(document.entityId, current, "CATALOG_PRODUCT_SCHEMA_DOWNGRADE");
      }
    }
    if (current && current.snapshot?.createdAt !== document.snapshot.createdAt) {
      throw new HttpsError("failed-precondition", "CATALOG_CREATED_AT_IMMUTABLE");
    }

    const oldKeys = current ? semanticKeys(descriptor.entityType, current.snapshot) : [];
    const newKeys = semanticKeys(descriptor.entityType, document.snapshot);
    const refsByPath = new Map();
    [...oldKeys, ...newKeys].forEach((semantic) => {
      const ref = indexRef(businessRef, semantic);
      refsByPath.set(ref.path, { ref, semantic });
    });
    const indexEntries = [...refsByPath.values()];
    const indexSnapshots = indexEntries.length === 0 ? [] :
      await tx.getAll(...indexEntries.map(({ ref }) => ref));

    for (let index = 0; index < indexEntries.length; index++) {
      const semantic = indexEntries[index].semantic;
      const isOld = oldKeys.some((candidate) =>
        candidate.kind === semantic.kind && candidate.value === semantic.value);
      if (!newKeys.some((candidate) =>
        candidate.kind === semantic.kind && candidate.value === semantic.value)) {
        if (isOld && indexSnapshots[index].data()?.entityId !== document.entityId) {
          throw new HttpsError("data-loss", "CATALOG_INDEX_OWNERSHIP_INVALID");
        }
        continue;
      }
      const snapshot = indexSnapshots[index];
      const ownerId = snapshot.exists ? snapshot.data()?.entityId : null;
      if (ownerId && ownerId !== document.entityId) {
        const collision = await tx.get(
          businessRef.collection(descriptor.collection).doc(ownerId),
        );
        throw new HttpsError(
          "already-exists",
          "CATALOG_SEMANTIC_CONFLICT",
          conflictDetails(
            ownerId,
            collision.exists ? collision.data() : null,
            "CATALOG_SEMANTIC_CONFLICT",
          ),
        );
      }
    }

    const previousSeq = metadata.exists ? metadata.data()?.seq : 0;
    if (!Number.isSafeInteger(previousSeq) || previousSeq < 0 || previousSeq >= MAX_SAFE_SEQ) {
      throw new HttpsError("resource-exhausted", "CATALOG_SEQUENCE_EXHAUSTED");
    }
    const seq = previousSeq + 1;
    const entityRecord = {
      entityId: document.entityId,
      entityType: descriptor.entityType,
      version: document.targetVersion,
      mutation: document.mutation,
      snapshot: document.snapshot,
      snapshotSha256: document.snapshotSha256,
      receiptId,
      syncedAt: FieldValue.serverTimestamp(),
    };
    const changeRef = businessRef.collection("catalogSyncChanges").doc(receiptId);
    const auditRef = businessRef.collection("auditEvents").doc(receiptId);
    tx.set(entityRef, entityRecord);
    tx.set(metadataRef, { seq, updatedAt: FieldValue.serverTimestamp() });
    tx.create(changeRef, {
      seq,
      entityType: descriptor.entityType,
      entityId: document.entityId,
      version: document.targetVersion,
      mutation: document.mutation,
      snapshot: document.snapshot,
      snapshotSha256: document.snapshotSha256,
      receiptId,
      syncedAt: FieldValue.serverTimestamp(),
    });
    tx.create(operationRef, {
      idempotencyKey,
      requestHash,
      operationType: data.operationType,
      entityId: document.entityId,
      version: document.targetVersion,
      receiptId,
      seq,
      createdAt: FieldValue.serverTimestamp(),
    });
    tx.create(auditRef, {
      eventType: "CATALOG_SYNCED",
      entityType: descriptor.entityType,
      entityId: document.entityId,
      version: document.targetVersion,
      authorizedRole,
      occurredAt: FieldValue.serverTimestamp(),
    });

    const newPaths = new Set(newKeys.map((semantic) => indexRef(businessRef, semantic).path));
    for (const semantic of oldKeys) {
      const ref = indexRef(businessRef, semantic);
      if (!newPaths.has(ref.path)) tx.delete(ref);
    }
    for (const semantic of newKeys) {
      const ref = indexRef(businessRef, semantic);
      tx.set(ref, { entityId: document.entityId, version: document.targetVersion });
    }
    return {
      ok: true,
      replayed: false,
      idempotencyKey,
      receiptId,
      entityId: document.entityId,
      version: document.targetVersion,
      seq,
    };
  });
}

export const syncCatalogEntity = onCall(CALLABLE_OPTIONS, syncCatalog);

function requireSinceSeq(value) {
  const seq = value ?? 0;
  if (!Number.isSafeInteger(seq) || seq < 0) throw invalid("SINCE_SEQ");
  return seq;
}

function requireLimit(value) {
  const limit = value ?? 100;
  if (!Number.isInteger(limit) || limit < 1 || limit > MAX_LIMIT) throw invalid("LIMIT");
  return limit;
}

function catalogChange(document) {
  const data = document.data();
  const snapshotPayload = JSON.stringify(data.snapshot);
  if (sha256(snapshotPayload) !== data.snapshotSha256) {
    throw new HttpsError("data-loss", "CATALOG_CHANGE_INTEGRITY");
  }
  return {
    seq: data.seq,
    entityType: data.entityType,
    entityId: data.entityId,
    remoteVersion: data.version,
    mutation: data.mutation,
    snapshotPayload,
    snapshotSha256: data.snapshotSha256,
    receiptId: data.receiptId,
    syncedAtMillis: data.syncedAt?.toMillis?.() ?? null,
  };
}

export const listCatalogChanges = onCall(CALLABLE_OPTIONS, async (request) => {
  requireExpectedUid(request);
  requireVerifiedEmail(request);
  const raw = request.data ?? {};
  exactObject(raw, CATALOG_PULL_KEYS, "CATALOG_PULL_FIELDS", OPTIONAL_REQUEST_KEYS);
  const businessId = requireBusinessId(raw.businessId);
  const sinceSeq = requireSinceSeq(raw.sinceSeq);
  const limit = requireLimit(raw.limit);
  const businessRef = db.doc(`businesses/${businessId}`);
  const memberRef = businessRef.collection("members").doc(request.auth.uid);
  const tombstoneRef = accountDeletionTombstoneRef(request.auth.uid);
  const window = Math.min(limit, MAX_QUERY_WINDOW);
  const query = businessRef.collection("catalogSyncChanges")
    .where("seq", ">", sinceSeq).orderBy("seq", "asc").limit(window + 1);
  return db.runTransaction(async (tx) => {
    const [business, tombstone, member] = await tx.getAll(
      businessRef, tombstoneRef, memberRef,
    );
    requireAccountNotDeleting(tombstone);
    requireBusinessNotDeleting(business);
    if (!member.exists) throw new HttpsError("permission-denied", "NOT_A_MEMBER");
    const snapshot = await tx.get(query);
    const changes = [];
    let bytes = 0;
    for (const document of snapshot.docs.slice(0, window)) {
      const change = catalogChange(document);
      const size = Buffer.byteLength(JSON.stringify(change), "utf8");
      if (changes.length > 0 && bytes + size > MAX_RESPONSE_BYTES) break;
      if (size > MAX_RESPONSE_BYTES) throw new HttpsError("data-loss", "CATALOG_CHANGE_TOO_LARGE");
      changes.push(change);
      bytes += size;
    }
    return {
      changes,
      nextCursor: changes.at(-1)?.seq ?? sinceSeq,
      hasMore: snapshot.size > changes.length,
    };
  });
});
