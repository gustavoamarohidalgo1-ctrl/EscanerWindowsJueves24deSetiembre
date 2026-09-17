// Respaldo documental opcional. El cliente nunca escribe Storage directamente: estos
// callables validan Auth, membresía, compra, bytes e idempotencia antes de usar Admin SDK.
import { onCall, HttpsError } from "firebase-functions/v2/https";
import { onObjectFinalized } from "firebase-functions/v2/storage";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { FieldValue, Timestamp } from "firebase-admin/firestore";
import sharp from "sharp";
import {
  ACCOUNT_DELETION_LOCK_FIELD,
  DOCUMENT_CALLABLE_OPTIONS,
  DOCUMENT_FINALIZE_OPTIONS,
  DOCUMENT_RECONCILIATION_SCHEDULE_OPTIONS,
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
  storageBucket,
} from "./common.js";

const REQUEST_KEYS = new Set([
  "businessId", "idempotencyKey", "operationType", "payloadVersion", "document", "expectedUid",
]);
const OPTIONAL_REQUEST_KEYS = new Set(["expectedUid"]);
const UPLOAD_KEYS = new Set(["version", "purchaseId", "imageId", "sha256", "contentBase64"]);
const PURGE_KEYS = new Set(["version", "purchaseId", "imageId"]);
const WRITER_ROLES = ["OWNER", "ADMIN", "OPERATOR"];
const TERMINAL_PURCHASE_STATUS = new Set(["POSTED", "VOIDED"]);
const SHA256 = /^[0-9a-f]{64}$/;
const BASE64 = /^[A-Za-z0-9+/]*={0,2}$/;
const MAX_IMAGE_BYTES = 2 * 1024 * 1024;
const MAX_DIMENSION = 4096;
const MAX_PIXELS = 16_000_000;
const MAX_PAGES_PER_PURCHASE = 5;
const MAX_BUSINESS_DOCUMENT_BYTES = 50 * 1024 * 1024;
const RESERVATION_GRACE_MILLIS = 10 * 60 * 1000;
const RECONCILIATION_BATCH_LIMIT = 100;
const RECONCILIATION_SCAN_LIMIT = 500;
const RECONCILIATION_RETRY_MILLIS = 10 * 60 * 1000;
const UPLOAD_PREFLIGHT_LEASE_MILLIS = 2 * 60 * 1000;
const FAILED_UPLOAD_RETENTION_MILLIS = 2 * 24 * 60 * 60 * 1000;
const UPLOAD_RATE_LIMIT_NAMESPACE = "facturastock:document-upload-rate:v1:";
const UPLOAD_RATE_LIMIT_RETENTION_MULTIPLIER = 2;
export const DOCUMENT_UPLOAD_RATE_POLICIES = Object.freeze({
  ACTOR: Object.freeze({ limit: 60, windowMillis: 60 * 60 * 1000 }),
  BUSINESS: Object.freeze({ limit: 500, windowMillis: 24 * 60 * 60 * 1000 }),
});
const DEFINITIVE_IMAGE_VALIDATION_ERRORS = new Set([
  "IMAGE_JPEG_SIGNATURE",
  "IMAGE_JPEG_FORMAT",
  "IMAGE_DIMENSIONS",
  "IMAGE_JPEG_DECODE",
]);
const UUID_PATH_SEGMENT = UUID_REGEX.source.slice(1, -1);
const DOCUMENT_OBJECT_PATH = new RegExp(
  `^businesses/(${UUID_PATH_SEGMENT})/invoices/(${UUID_PATH_SEGMENT})/` +
    `(${UUID_PATH_SEGMENT})\\.jpg$`,
);

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

function requireText(value, code, max) {
  if (typeof value !== "string" || value.length === 0 || value.length > max) {
    throw invalid(code);
  }
  return value;
}

function canonicalRequest(request, expectedOperation) {
  const auth = requireExpectedUid(request);
  requireVerifiedEmail(request);
  const data = exactObject(
    request.data ?? {},
    REQUEST_KEYS,
    "DOCUMENT_REQUEST_FIELDS",
    OPTIONAL_REQUEST_KEYS,
  );
  const businessId = requireBusinessId(data.businessId);
  const idempotencyKey = requireText(data.idempotencyKey, "IDEMPOTENCY_KEY", 256);
  if (data.operationType !== expectedOperation) throw invalid("OPERATION_TYPE");
  if (data.payloadVersion !== 1) throw invalid("PAYLOAD_VERSION");
  return { auth, data, businessId, idempotencyKey };
}

function requireUuid(value, code) {
  if (typeof value !== "string" || !UUID_REGEX.test(value)) throw invalid(code);
  return value;
}

function parseUploadDocument(raw, idempotencyKey) {
  const document = exactObject(raw, UPLOAD_KEYS, "DOCUMENT_UPLOAD_FIELDS");
  if (document.version !== 1) throw invalid("DOCUMENT_VERSION");
  const purchaseId = requireUuid(document.purchaseId, "PURCHASE_ID");
  const imageId = requireUuid(document.imageId, "IMAGE_ID");
  if (typeof document.sha256 !== "string" || !SHA256.test(document.sha256)) {
    throw invalid("IMAGE_SHA256");
  }
  const expectedKey = `document-upload:v1:${purchaseId}:${imageId}:${document.sha256}`;
  if (idempotencyKey !== expectedKey) throw invalid("IDEMPOTENCY_KEY_SHAPE");
  const contentBase64 = requireText(
    document.contentBase64,
    "IMAGE_CONTENT",
    Math.ceil(MAX_IMAGE_BYTES / 3) * 4,
  );
  if (contentBase64.length % 4 !== 0 || !BASE64.test(contentBase64)) {
    throw invalid("IMAGE_BASE64");
  }
  const content = Buffer.from(contentBase64, "base64");
  if (content.toString("base64") !== contentBase64) throw invalid("IMAGE_BASE64_CANONICAL");
  if (content.length === 0 || content.length > MAX_IMAGE_BYTES) throw invalid("IMAGE_SIZE");
  if (sha256(content) !== document.sha256) throw invalid("IMAGE_INTEGRITY");
  return { version: 1, purchaseId, imageId, sha256: document.sha256, content };
}

function parsePurgeDocument(raw, idempotencyKey) {
  const document = exactObject(raw, PURGE_KEYS, "DOCUMENT_PURGE_FIELDS");
  if (document.version !== 1) throw invalid("DOCUMENT_VERSION");
  const purchaseId = requireUuid(document.purchaseId, "PURCHASE_ID");
  const imageId = requireUuid(document.imageId, "IMAGE_ID");
  if (idempotencyKey !== `document-purge:v1:${purchaseId}:${imageId}`) {
    throw invalid("IDEMPOTENCY_KEY_SHAPE");
  }
  return { version: 1, purchaseId, imageId };
}

/** Decodificación real: metadata y raster completo deben ser JPEG íntegros de una sola página. */
export async function decodeJpeg(bytes) {
  if (!Buffer.isBuffer(bytes) || bytes.length < 10 || bytes[0] !== 0xff || bytes[1] !== 0xd8 ||
      bytes.at(-2) !== 0xff || bytes.at(-1) !== 0xd9) {
    throw invalid("IMAGE_JPEG_SIGNATURE");
  }
  try {
    const image = sharp(bytes, {
      failOn: "error",
      limitInputPixels: MAX_PIXELS,
      sequentialRead: true,
    });
    const metadata = await image.metadata();
    if (metadata.format !== "jpeg" || (metadata.pages ?? 1) !== 1) {
      throw invalid("IMAGE_JPEG_FORMAT");
    }
    const width = metadata.width;
    const height = metadata.height;
    if (!Number.isInteger(width) || !Number.isInteger(height) ||
        width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION ||
        width * height > MAX_PIXELS) {
      throw invalid("IMAGE_DIMENSIONS");
    }
    // metadata() solo recorre cabeceras; raw().toBuffer fuerza la decodificación completa y
    // hace fallar scans truncados/corruptos antes de reservar cuota o escribir Storage.
    await image.clone().raw().toBuffer();
    return { width, height };
  } catch (failure) {
    if (failure instanceof HttpsError) throw failure;
    throw invalid("IMAGE_JPEG_DECODE");
  }
}

function receiptFor(prefix, idempotencyKey) {
  return `${prefix}_${sha256(`facturastock:${prefix}:v1:${idempotencyKey}`).slice(0, 32)}`;
}

export function documentUploadRateLimitDocumentId(scope, value) {
  if (
    !Object.hasOwn(DOCUMENT_UPLOAD_RATE_POLICIES, scope) ||
    typeof value !== "string" || value.length === 0
  ) {
    throw new Error("DOCUMENT_UPLOAD_RATE_LIMIT_SCOPE_INVALID");
  }
  return sha256(`${UPLOAD_RATE_LIMIT_NAMESPACE}${scope}:${value}`);
}

function documentUploadRateLimitRef(scope, value) {
  return db.doc(
    `documentUploadRateLimits/${documentUploadRateLimitDocumentId(scope, value)}`,
  );
}

function nextDocumentUploadRateState(snapshot, scope, policy, nowMillis) {
  let windowStartedAtMillis = nowMillis;
  let count = 0;
  if (snapshot.exists) {
    const data = snapshot.data();
    const savedWindow = data.windowStartedAt?.toMillis?.();
    if (
      data.schemaVersion !== 1 || data.scope !== scope ||
      !Number.isSafeInteger(savedWindow) || savedWindow > nowMillis + 30_000 ||
      !Number.isSafeInteger(data.count) || data.count < 0
    ) {
      throw new HttpsError("data-loss", "DOCUMENT_UPLOAD_RATE_LIMIT_INVALID");
    }
    if (nowMillis - savedWindow < policy.windowMillis) {
      windowStartedAtMillis = savedWindow;
      count = data.count;
    }
  }
  if (count >= policy.limit) {
    throw new HttpsError("resource-exhausted", "DOCUMENT_UPLOAD_RATE_LIMIT", {
      scope,
      retryAfterMillis: Math.max(1, windowStartedAtMillis + policy.windowMillis - nowMillis),
    });
  }
  return {
    schemaVersion: 1,
    scope,
    count: count + 1,
    windowStartedAt: Timestamp.fromMillis(windowStartedAtMillis),
    expiresAt: Timestamp.fromMillis(
      windowStartedAtMillis +
        policy.windowMillis * UPLOAD_RATE_LIMIT_RETENTION_MULTIPLIER,
    ),
  };
}

function refs(businessId, uid, purchaseId, imageId, idempotencyKey) {
  const businessRef = db.doc(`businesses/${businessId}`);
  return {
    businessRef,
    tombstoneRef: accountDeletionTombstoneRef(uid),
    memberRef: businessRef.collection("members").doc(uid),
    purchaseRef: businessRef.collection("purchases").doc(purchaseId),
    backupRef: businessRef.collection("documentBackups").doc(imageId),
    purchaseMetaRef: businessRef.collection("documentBackupMetadata").doc(purchaseId),
    businessMetaRef: businessRef.collection("sync").doc("documentMetadata"),
    operationRef: businessRef.collection("documentSyncOperations").doc(sha256(idempotencyKey)),
    actorRateRef: documentUploadRateLimitRef("ACTOR", uid),
    businessRateRef: documentUploadRateLimitRef("BUSINESS", businessId),
  };
}

function documentQuota(purchaseMeta, businessMeta, contentSize) {
  const pageCount = purchaseMeta.data()?.pageCount ?? 0;
  const totalBytes = businessMeta.data()?.totalBytes ?? 0;
  if (!Number.isSafeInteger(pageCount) || pageCount < 0 ||
      !Number.isSafeInteger(totalBytes) || totalBytes < 0) {
    throw new HttpsError("data-loss", "DOCUMENT_QUOTA_INVALID");
  }
  if (pageCount >= MAX_PAGES_PER_PURCHASE) {
    throw new HttpsError("resource-exhausted", "DOCUMENT_PAGE_QUOTA");
  }
  if (totalBytes + contentSize > MAX_BUSINESS_DOCUMENT_BYTES) {
    throw new HttpsError("resource-exhausted", "DOCUMENT_BUSINESS_QUOTA");
  }
  return { pageCount, totalBytes };
}

function requireCoreSnapshots(business, tombstone, member, purchase) {
  requireAccountNotDeleting(tombstone);
  requireBusinessNotDeleting(business);
  if (!member.exists) throw new HttpsError("permission-denied", "NOT_A_MEMBER");
  if (!purchase.exists) throw new HttpsError("not-found", "PURCHASE_NOT_FOUND");
  if (!TERMINAL_PURCHASE_STATUS.has(purchase.data()?.status)) {
    throw new HttpsError("failed-precondition", "PURCHASE_NOT_TERMINAL");
  }
}

function objectPath(businessId, purchaseId, imageId) {
  return `businesses/${businessId}/invoices/${purchaseId}/${imageId}.jpg`;
}

async function deleteObjectIfPresent(file) {
  try {
    await file.delete({ ignoreNotFound: true });
  } catch (failure) {
    if (failure?.code !== 404) throw failure;
  }
}

function parseDocumentObjectPath(name) {
  if (typeof name !== "string") return null;
  const match = DOCUMENT_OBJECT_PATH.exec(name);
  if (!match) return null;
  return { businessId: match[1], purchaseId: match[2], imageId: match[3], name };
}

function timestampMillis(value) {
  const millis = value?.toMillis?.();
  return Number.isSafeInteger(millis) && millis >= 0 ? millis : null;
}

function documentStateRefs(database, ids) {
  const businessRef = database.doc(`businesses/${ids.businessId}`);
  return {
    businessRef,
    backupRef: businessRef.collection("documentBackups").doc(ids.imageId),
    purchaseMetaRef: businessRef.collection("documentBackupMetadata").doc(ids.purchaseId),
    businessMetaRef: businessRef.collection("sync").doc("documentMetadata"),
  };
}

/**
 * Convierte una reserva vencida en tombstone dentro de Firestore antes de borrar Storage.
 * Si finalize ganó la carrera, la transacción se reintenta y devuelve KEEP; así nunca se
 * elimina un objeto que ya obtuvo marcador COMPLETE.
 */
async function tombstoneStaleReservation(database, ids, cutoffMillis) {
  const paths = documentStateRefs(database, ids);
  return database.runTransaction(async (tx) => {
    const [business, backup, purchaseMeta, businessMeta] = await tx.getAll(
      paths.businessRef,
      paths.backupRef,
      paths.purchaseMetaRef,
      paths.businessMetaRef,
    );
    if (!business.exists || business.data()?.[ACCOUNT_DELETION_LOCK_FIELD] === true) {
      return "DELETE";
    }
    if (!backup.exists) return "DELETE";
    const state = backup.data();
    if (state.purchaseId !== ids.purchaseId || state.imageId !== ids.imageId) return "DELETE";
    if (state.status === "COMPLETE" && state.purgeRequested !== true) return "KEEP";
    if (state.status === "PURGED" || state.purgeRequested === true) return "DELETE";
    const reservedAt = timestampMillis(state.createdAt);
    if (state.status !== "RESERVED" || reservedAt === null) return "DELETE";
    if (reservedAt > cutoffMillis) return "DEFER";

    tx.update(paths.backupRef, {
      status: "PURGED",
      purgeRequested: true,
      reconciliationReason: "STALE_RESERVATION",
      purgedAt: FieldValue.serverTimestamp(),
    });
    const size = state.size;
    const pageCount = purchaseMeta.data()?.pageCount;
    if (purchaseMeta.exists && Number.isSafeInteger(pageCount) && pageCount >= 1) {
      tx.set(paths.purchaseMetaRef, {
        pageCount: pageCount - 1,
        updatedAt: FieldValue.serverTimestamp(),
      }, { merge: true });
    }
    const totalBytes = businessMeta.data()?.totalBytes;
    if (
      businessMeta.exists && Number.isSafeInteger(size) && size >= 0 &&
      Number.isSafeInteger(totalBytes) && totalBytes >= size
    ) {
      tx.set(paths.businessMetaRef, {
        totalBytes: totalBytes - size,
        updatedAt: FieldValue.serverTimestamp(),
      }, { merge: true });
    }
    return "DELETE";
  });
}

async function reconcileDocumentObject({
  ids,
  database,
  bucket,
  nowMillis,
}) {
  const paths = documentStateRefs(database, ids);
  const [business, backup] = await database.getAll(paths.businessRef, paths.backupRef);
  let action = "DELETE";
  if (
    business.exists && business.data()?.[ACCOUNT_DELETION_LOCK_FIELD] !== true && backup.exists
  ) {
    const state = backup.data();
    if (state.purchaseId === ids.purchaseId && state.imageId === ids.imageId) {
      if (state.status === "COMPLETE" && state.purgeRequested !== true) {
        action = "KEEP";
      } else if (state.status === "RESERVED" && state.purgeRequested !== true) {
        const reservedAt = timestampMillis(state.createdAt);
        action = reservedAt !== null && reservedAt > nowMillis - RESERVATION_GRACE_MILLIS
          ? "DEFER"
          : await tombstoneStaleReservation(
            database,
            ids,
            nowMillis - RESERVATION_GRACE_MILLIS,
          );
      }
    }
  }
  if (action === "DELETE") await deleteObjectIfPresent(bucket.file(ids.name));
  return action;
}

/**
 * Un objeto con hold o un fallo aislado de Storage no debe ocupar para siempre la primera página.
 * El timestamp consultado actúa también como watermark de retry; el valor original queda retenido
 * para auditoría y la transacción no pisa una finalización concurrente.
 */
async function deferPurgedMarkerAfterFailure(database, backupRef, nowMillis) {
  await database.runTransaction(async (tx) => {
    const current = await tx.get(backupRef);
    if (!current.exists || current.data()?.status !== "PURGED" ||
        current.data()?.purgeRequested !== true) return;
    const state = current.data();
    tx.update(backupRef, {
      originalPurgedAt: state.originalPurgedAt ?? state.purgedAt,
      purgedAt: Timestamp.fromMillis(nowMillis),
      reconciliationFailureReason: "STORAGE_DELETE_UNAVAILABLE",
      reconciliationFailedAt: FieldValue.serverTimestamp(),
    });
  });
}

async function deferReservedMarkerAfterFailure(database, backupRef, nowMillis) {
  await database.runTransaction(async (tx) => {
    const current = await tx.get(backupRef);
    if (!current.exists || current.data()?.status !== "RESERVED" ||
        current.data()?.purgeRequested === true) return;
    const state = current.data();
    tx.update(backupRef, {
      originalCreatedAt: state.originalCreatedAt ?? state.createdAt,
      createdAt: Timestamp.fromMillis(nowMillis),
      reconciliationFailureReason: "DOCUMENT_RECONCILIATION_UNAVAILABLE",
      reconciliationFailedAt: FieldValue.serverTimestamp(),
    });
  });
}

async function deferAccountStorageCleanupAfterFailure(database, tombstoneRef, nowMillis) {
  await database.runTransaction(async (tx) => {
    const current = await tx.get(tombstoneRef);
    if (!current.exists || current.data()?.cleanupComplete !== true ||
        current.data()?.storageCleanupPending !== true) return;
    tx.update(tombstoneRef, {
      storageCleanupEligibleAt: Timestamp.fromMillis(
        nowMillis + RECONCILIATION_RETRY_MILLIS,
      ),
      storageCleanupFailureReason: "STORAGE_DELETE_UNAVAILABLE",
      storageCleanupFailedAt: FieldValue.serverTimestamp(),
    });
  });
}

/**
 * Evento inmediato: niega huérfanos/tombstones y conserva una reserva fresca para que el
 * callable normal pueda ejecutar finalize. Las reservas que sobreviven a un crash se recogen
 * después del período de gracia por el reconciliador programado.
 */
export async function reconcileFinalizedPurchaseDocument(
  event,
  {
    database = db,
    bucketProvider = storageBucket,
    nowMillis = Date.now(),
  } = {},
) {
  try {
    const object = event?.data ?? event;
    const bucket = bucketProvider();
    if (!object || object.bucket !== bucket.name) return "IGNORED_BUCKET";
    const ids = parseDocumentObjectPath(object.name);
    if (ids === null) {
      if (typeof object.name === "string" && object.name.startsWith("businesses/")) {
        await deleteObjectIfPresent(bucket.file(object.name));
        return "DELETE";
      }
      return "IGNORED_PATH";
    }
    return await reconcileDocumentObject({ ids, database, bucket, nowMillis });
  } catch (_failure) {
    throw new Error("DOCUMENT_RECONCILIATION_UNAVAILABLE");
  }
}

/** Barrido acotado e idempotente de uploads guardados cuyo callable cayó antes de finalize. */
export async function reconcileStaleReservedDocumentsHandler(
  _event,
  {
    database = db,
    bucketProvider = storageBucket,
    nowMillis = Date.now(),
  } = {},
) {
  try {
    const cutoff = nowMillis - RESERVATION_GRACE_MILLIS;
    const bucket = bucketProvider();
    let scanned = 0;
    let deleted = 0;
    let invalid = 0;
    let failed = 0;
    let reservationCursor = null;
    while (scanned < RECONCILIATION_SCAN_LIMIT) {
      const pageLimit = Math.min(
        RECONCILIATION_BATCH_LIMIT,
        RECONCILIATION_SCAN_LIMIT - scanned,
      );
      let query = database.collectionGroup("documentBackups")
        .where("status", "==", "RESERVED")
        .where("createdAt", "<=", Timestamp.fromMillis(cutoff))
        .orderBy("createdAt", "asc")
        .limit(pageLimit);
      if (reservationCursor !== null) query = query.startAfter(reservationCursor);
      const reservations = await query.get();
      if (reservations.empty) break;
      reservationCursor = reservations.docs.at(-1);
      scanned += reservations.size;
      for (const backup of reservations.docs) {
        try {
          const businessRef = backup.ref.parent.parent;
          const purchaseId = backup.data()?.purchaseId;
          const imageId = backup.id;
          if (
            businessRef?.parent?.id !== "businesses" ||
            !UUID_REGEX.test(businessRef.id) || !UUID_REGEX.test(purchaseId ?? "") ||
            !UUID_REGEX.test(imageId)
          ) {
            // Un documento corrupto/admin-only no puede ocupar para siempre una plaza. Se relee
            // transaccionalmente y se mueve a un estado terminal sin construir rutas de Storage.
            const quarantined = await database.runTransaction(async (tx) => {
              const current = await tx.get(backup.ref);
              if (!current.exists || current.data()?.status !== "RESERVED") return false;
              const currentBusinessRef = current.ref.parent.parent;
              const currentPurchaseId = current.data()?.purchaseId;
              if (
                currentBusinessRef?.parent?.id === "businesses" &&
                UUID_REGEX.test(currentBusinessRef.id) &&
                UUID_REGEX.test(currentPurchaseId ?? "") &&
                UUID_REGEX.test(current.id)
              ) {
                return false;
              }
              tx.update(current.ref, {
                status: "INVALID",
                reconciliationReason: "INVALID_RESERVATION_IDENTITY",
                reconciledAt: FieldValue.serverTimestamp(),
              });
              return true;
            });
            if (quarantined) invalid += 1;
            continue;
          }
          const ids = {
            businessId: businessRef.id,
            purchaseId,
            imageId,
            name: objectPath(businessRef.id, purchaseId, imageId),
          };
          const action = await reconcileDocumentObject({ ids, database, bucket, nowMillis });
          if (action === "DELETE") deleted += 1;
        } catch (_failure) {
          failed += 1;
          try {
            await deferReservedMarkerAfterFailure(database, backup.ref, nowMillis);
          } catch (_deferFailure) {
            // El cursor de esta pasada ya avanzó; una caída global se reintentará en la siguiente.
          }
        }
      }
      if (reservations.size < pageLimit) break;
    }

    // Un tombstone PURGED permanece pendiente durante toda la vida máxima del callable de
    // upload. Cada pasada vuelve a borrar el objeto y solo después cierra la marca, cubriendo
    // delete-before-late-save incluso cuando onFinalize agota sus retries.
    let purgedScanned = 0;
    let purgedDeleted = 0;
    let purgedInvalid = 0;
    let purgedFailed = 0;
    let purgeCursor = null;
    while (purgedScanned < RECONCILIATION_SCAN_LIMIT) {
      const pageLimit = Math.min(
        RECONCILIATION_BATCH_LIMIT,
        RECONCILIATION_SCAN_LIMIT - purgedScanned,
      );
      let query = database.collectionGroup("documentBackups")
        .where("purgeRequested", "==", true)
        .where("purgedAt", "<=", Timestamp.fromMillis(cutoff))
        .orderBy("purgedAt", "asc")
        .limit(pageLimit);
      if (purgeCursor !== null) query = query.startAfter(purgeCursor);
      const purgedMarkers = await query.get();
      if (purgedMarkers.empty) break;
      purgeCursor = purgedMarkers.docs.at(-1);
      purgedScanned += purgedMarkers.size;
      for (const backup of purgedMarkers.docs) {
        try {
          const businessRef = backup.ref.parent.parent;
          const purchaseId = backup.data()?.purchaseId;
          const imageId = backup.id;
          if (
            businessRef?.parent?.id !== "businesses" ||
            !UUID_REGEX.test(businessRef.id) || !UUID_REGEX.test(purchaseId ?? "") ||
            !UUID_REGEX.test(imageId)
          ) {
            // Un tombstone corrupto se cierra de modo auditable y jamás forma una ruta Storage.
            const quarantined = await database.runTransaction(async (tx) => {
              const current = await tx.get(backup.ref);
              if (!current.exists || current.data()?.status !== "PURGED" ||
                  current.data()?.purgeRequested !== true) return false;
              const currentBusinessRef = current.ref.parent.parent;
              const currentPurchaseId = current.data()?.purchaseId;
              if (
                currentBusinessRef?.parent?.id === "businesses" &&
                UUID_REGEX.test(currentBusinessRef.id) &&
                UUID_REGEX.test(currentPurchaseId ?? "") &&
                UUID_REGEX.test(current.id)
              ) {
                return false;
              }
              tx.update(current.ref, {
                purgeRequested: false,
                reconciliationReason: "INVALID_PURGE_IDENTITY",
                reconciledAt: FieldValue.serverTimestamp(),
              });
              return true;
            });
            if (quarantined) purgedInvalid += 1;
            continue;
          }
          await deleteObjectIfPresent(
            bucket.file(objectPath(businessRef.id, purchaseId, imageId)),
          );
          const confirmed = await database.runTransaction(async (tx) => {
            const current = await tx.get(backup.ref);
            if (!current.exists || current.data()?.status !== "PURGED" ||
                current.data()?.purgeRequested !== true) return false;
            tx.update(current.ref, {
              purgeRequested: false,
              storagePurgedAt: FieldValue.serverTimestamp(),
              reconciliationFailureReason: FieldValue.delete(),
              reconciliationFailedAt: FieldValue.delete(),
            });
            return true;
          });
          if (confirmed) purgedDeleted += 1;
        } catch (_failure) {
          purgedFailed += 1;
          try {
            await deferPurgedMarkerAfterFailure(database, backup.ref, nowMillis);
          } catch (_deferFailure) {
            // El cursor evita que este tenant impida procesar los siguientes en esta pasada.
          }
        }
      }
      if (purgedMarkers.size < pageLimit) break;
    }

    // Account deletion retiene prefijos pseudónimos hasta una purga posterior a la ventana
    // de late-write. Firestore puede haber desaparecido por completo: Storage se barre por el
    // marker durable, sin depender de membresía, compra ni negocio existentes.
    let accountMarkersScanned = 0;
    let accountPrefixesDeleted = 0;
    let accountMarkersInvalid = 0;
    let accountMarkersFailed = 0;
    let accountCursor = null;
    while (accountMarkersScanned < RECONCILIATION_SCAN_LIMIT) {
      const pageLimit = Math.min(
        RECONCILIATION_BATCH_LIMIT,
        RECONCILIATION_SCAN_LIMIT - accountMarkersScanned,
      );
      let query = database.collection("accountDeletionTombstones")
        .where("cleanupComplete", "==", true)
        .where("storageCleanupPending", "==", true)
        .where("storageCleanupEligibleAt", "<=", Timestamp.fromMillis(nowMillis))
        .orderBy("storageCleanupEligibleAt", "asc")
        .limit(pageLimit);
      if (accountCursor !== null) query = query.startAfter(accountCursor);
      const deletionTombstones = await query.get();
      if (deletionTombstones.empty) break;
      accountCursor = deletionTombstones.docs.at(-1);
      accountMarkersScanned += deletionTombstones.size;
      for (const tombstone of deletionTombstones.docs) {
        try {
          const businessIds = tombstone.data()?.storageCleanupBusinessIds ??
            tombstone.data()?.soleOwnedBusinessIds;
          if (!Array.isArray(businessIds) ||
              businessIds.some((businessId) => !UUID_REGEX.test(businessId))) {
            // Un marker corrupto se cuarentena y no puede ocupar las primeras páginas otra vez.
            const quarantined = await database.runTransaction(async (tx) => {
              const current = await tx.get(tombstone.ref);
              if (!current.exists || current.data()?.cleanupComplete !== true ||
                  current.data()?.storageCleanupPending !== true) return false;
              const currentIds = current.data()?.storageCleanupBusinessIds ??
                current.data()?.soleOwnedBusinessIds;
              if (Array.isArray(currentIds) &&
                  currentIds.every((businessId) => UUID_REGEX.test(businessId))) {
                return false;
              }
              tx.update(current.ref, {
                storageCleanupPending: false,
                storageCleanupFailureReason: "INVALID_BUSINESS_IDENTIFIERS",
                storageCleanupFailedAt: FieldValue.serverTimestamp(),
              });
              return true;
            });
            if (quarantined) accountMarkersInvalid += 1;
            continue;
          }
          for (const businessId of businessIds) {
            await bucket.deleteFiles({ prefix: `businesses/${businessId}/` });
            accountPrefixesDeleted += 1;
          }
          await tombstone.ref.update({
            storageCleanupPending: false,
            storageCleanupBusinessIds: [],
            soleOwnedBusinessIds: [],
            // El UUID queda retirado permanentemente para aislar purgas cross-generation.
            retiredBusinessIds: [...new Set([
              ...(Array.isArray(tombstone.data()?.retiredBusinessIds)
                ? tombstone.data().retiredBusinessIds.filter((id) => UUID_REGEX.test(id))
                : []),
              ...businessIds,
            ])].sort(),
            storageCleanupFailureReason: FieldValue.delete(),
            storageCleanupFailedAt: FieldValue.delete(),
            storageCleanupCompletedAt: FieldValue.serverTimestamp(),
          });
        } catch (_failure) {
          accountMarkersFailed += 1;
          try {
            await deferAccountStorageCleanupAfterFailure(database, tombstone.ref, nowMillis);
          } catch (_deferFailure) {
            // El cursor de la pasada mantiene aislados los demás tenants.
          }
        }
      }
      if (deletionTombstones.size < pageLimit) break;
    }
    return {
      scanned,
      deleted,
      invalid,
      failed,
      purgedScanned,
      purgedDeleted,
      purgedInvalid,
      purgedFailed,
      accountMarkersScanned,
      accountPrefixesDeleted,
      accountMarkersInvalid,
      accountMarkersFailed,
    };
  } catch (_failure) {
    throw new Error("DOCUMENT_RECONCILIATION_UNAVAILABLE");
  }
}

export const reconcilePurchaseDocumentObject = onObjectFinalized(
  DOCUMENT_FINALIZE_OPTIONS,
  (event) => reconcileFinalizedPurchaseDocument(event),
);

export const reconcileStaleReservedDocuments = onSchedule(
  DOCUMENT_RECONCILIATION_SCHEDULE_OPTIONS,
  (event) => reconcileStaleReservedDocumentsHandler(event),
);

/**
 * Segunda frontera después de escribir bytes. Account deletion puede fijar el lock y purgar
 * Storage entre la reserva y file.save; por eso la finalización relee todo el control-plane.
 * Un fallo del actor o de Firestore no autoriza a borrar bytes compartidos: otro retry puede
 * haber confirmado el mismo objeto. Solo una purga durable del documento/negocio permite
 * eliminarlo; las reservas que no terminan quedan a cargo del reconciliador programado.
 */
export async function finalizeReservedDocumentUpload({
  businessId,
  uid,
  purchaseId,
  imageId,
  idempotencyKey,
  requestHash,
  file,
}, { database = db } = {}) {
  const paths = refs(businessId, uid, purchaseId, imageId, idempotencyKey);
  let mustPurge;
  try {
    mustPurge = await database.runTransaction(async (tx) => {
      const [business, tombstone, member, purchase, operation, backup] = await tx.getAll(
        paths.businessRef,
        paths.tombstoneRef,
        paths.memberRef,
        paths.purchaseRef,
        paths.operationRef,
        paths.backupRef,
      );
      requireCoreSnapshots(business, tombstone, member, purchase);
      if (!operation.exists || !backup.exists || operation.data()?.requestHash !== requestHash) {
        throw new HttpsError("data-loss", "DOCUMENT_RESERVATION_LOST");
      }
      const purged = backup.data()?.status === "PURGED" || backup.data()?.purgeRequested === true;
      if (operation.data()?.status !== "COMPLETE") {
        tx.update(paths.operationRef, {
          status: "COMPLETE",
          completedAt: FieldValue.serverTimestamp(),
        });
      }
      if (!purged && backup.data()?.status !== "COMPLETE") {
        tx.update(paths.backupRef, {
          status: "COMPLETE",
          completedAt: FieldValue.serverTimestamp(),
        });
      }
      return purged;
    });
  } catch (failure) {
    try {
      const deletionConfirmed = await database.runTransaction(async (tx) => {
        const [business, backup] = await tx.getAll(paths.businessRef, paths.backupRef);
        if (!business.exists || business.data()?.[ACCOUNT_DELETION_LOCK_FIELD] === true) {
          return true;
        }
        const saved = backup.data();
        return backup.exists && saved.purchaseId === purchaseId && saved.imageId === imageId &&
          (saved.status === "PURGED" || saved.purgeRequested === true);
      });
      if (deletionConfirmed) await deleteObjectIfPresent(file);
    } catch (_cleanupFailure) {
      // Sin confirmación no se destruyen bytes. Los tombstones de purga/borrado y las reservas
      // vencidas tienen recuperación durable; se conserva el error original del callable.
    }
    throw failure;
  }
  if (mustPurge) await deleteObjectIfPresent(file);
  return mustPurge;
}

function uploadPreflightHash(businessId, idempotencyKey, document) {
  return sha256(JSON.stringify({
    businessId,
    idempotencyKey,
    operationType: "SYNC_DOCUMENT_UPLOAD",
    payloadVersion: 1,
    document: {
      version: 1,
      purchaseId: document.purchaseId,
      imageId: document.imageId,
      sha256: document.sha256,
      size: document.content.length,
    },
  }));
}

function requireMatchingUploadOperation(saved, { document, idempotencyKey, preflightHash }) {
  if (
    saved.operationType !== "SYNC_DOCUMENT_UPLOAD" ||
    saved.idempotencyKey !== idempotencyKey ||
    saved.purchaseId !== document.purchaseId || saved.imageId !== document.imageId ||
    (saved.preflightHash !== undefined && saved.preflightHash !== preflightHash)
  ) {
    throw new HttpsError("already-exists", "DOCUMENT_REPLAY_MISMATCH");
  }
}

function requireMatchingPurgeOperation(saved, { document, idempotencyKey, requestHash }) {
  if (
    saved.operationType !== "SYNC_DOCUMENT_PURGE" ||
    saved.idempotencyKey !== idempotencyKey || saved.requestHash !== requestHash ||
    saved.purchaseId !== document.purchaseId || saved.imageId !== document.imageId
  ) {
    throw new HttpsError("already-exists", "DOCUMENT_REPLAY_MISMATCH");
  }
}

function requireMatchingBackupIdentity(backup, document) {
  if (
    backup.exists &&
    (backup.data()?.purchaseId !== document.purchaseId ||
      backup.data()?.imageId !== document.imageId)
  ) {
    throw new HttpsError("failed-precondition", "DOCUMENT_IMAGE_PURCHASE_MISMATCH");
  }
}

function requireDimensions(value) {
  const width = value?.width;
  const height = value?.height;
  if (!Number.isInteger(width) || !Number.isInteger(height) ||
      width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION ||
      width * height > MAX_PIXELS) {
    throw invalid("IMAGE_DIMENSIONS");
  }
  return { width, height };
}

function dimensionsFromReservedBackup(backup, document) {
  if (!backup.exists) {
    throw new HttpsError("data-loss", "DOCUMENT_RESERVATION_LOST");
  }
  const saved = backup.data();
  if (
    !["RESERVED", "COMPLETE", "PURGED"].includes(saved.status) ||
    saved.purchaseId !== document.purchaseId || saved.imageId !== document.imageId ||
    saved.sha256 !== document.sha256 || saved.size !== document.content.length
  ) {
    throw new HttpsError("data-loss", "DOCUMENT_RESERVATION_LOST");
  }
  try {
    return requireDimensions(saved);
  } catch (_failure) {
    throw new HttpsError("data-loss", "DOCUMENT_RESERVATION_LOST");
  }
}

function validationFailureFor(saved) {
  if (!DEFINITIVE_IMAGE_VALIDATION_ERRORS.has(saved.validationError)) {
    throw new HttpsError("data-loss", "DOCUMENT_VALIDATION_STATE_INVALID");
  }
  return invalid(saved.validationError);
}

/**
 * Autoriza y reserva el intento antes de Sharp. La reserva pseudónima consume exactamente una
 * unidad actor/negocio por clave idempotente; un fallo JPEG definitivo queda memorizado para que
 * el mismo cuerpo nunca vuelva a gastar CPU. Las cuotas documentales se vuelven a comprobar en
 * la transacción final, por lo que este rechazo temprano no se usa como autoridad ante carreras.
 */
async function preflightDocumentUpload({
  businessId,
  uid,
  document,
  idempotencyKey,
  preflightHash,
  receiptId,
  nowMillis = Date.now(),
}) {
  const paths = refs(businessId, uid, document.purchaseId, document.imageId, idempotencyKey);
  return db.runTransaction(async (tx) => {
    const [
      business,
      tombstone,
      member,
      purchase,
      operation,
      backup,
      purchaseMeta,
      businessMeta,
      actorRate,
      businessRate,
    ] = await tx.getAll(
      paths.businessRef,
      paths.tombstoneRef,
      paths.memberRef,
      paths.purchaseRef,
      paths.operationRef,
      paths.backupRef,
      paths.purchaseMetaRef,
      paths.businessMetaRef,
      paths.actorRateRef,
      paths.businessRateRef,
    );
    requireCoreSnapshots(business, tombstone, member, purchase);

    if (operation.exists) {
      const saved = operation.data();
      requireMatchingUploadOperation(saved, { document, idempotencyKey, preflightHash });
      if (saved.status === "COMPLETE") {
        return {
          replay: {
            ok: true,
            idempotencyKey,
            receiptId: saved.receiptId,
            discardedByPurge:
              saved.discardedByPurge === true || backup.data()?.status === "PURGED",
          },
        };
      }
      if (saved.status === "VALIDATION_FAILED") throw validationFailureFor(saved);
      if (saved.status === "RESERVED") {
        if (typeof saved.requestHash !== "string" || !SHA256.test(saved.requestHash)) {
          throw new HttpsError("data-loss", "DOCUMENT_RESERVATION_LOST");
        }
        return {
          replay: null,
          claimGeneration: null,
          dimensions: dimensionsFromReservedBackup(backup, document),
        };
      }
      if (saved.status !== "PREFLIGHT") {
        throw new HttpsError("data-loss", "DOCUMENT_PREFLIGHT_STATE_INVALID");
      }
      const leaseExpiresAtMillis = saved.leaseExpiresAt?.toMillis?.();
      if (!Number.isSafeInteger(leaseExpiresAtMillis) ||
          !Number.isSafeInteger(saved.claimGeneration) || saved.claimGeneration < 1) {
        throw new HttpsError("data-loss", "DOCUMENT_PREFLIGHT_STATE_INVALID");
      }
      if (leaseExpiresAtMillis > nowMillis) {
        // UNAVAILABLE es transitorio en Android. ABORTED se presenta como conflicto manual y
        // rompería la convergencia si dos dispositivos envían la misma clave al mismo tiempo.
        throw new HttpsError("unavailable", "DOCUMENT_UPLOAD_IN_PROGRESS", {
          retryAfterMillis: leaseExpiresAtMillis - nowMillis,
        });
      }
      const authorizedRole = requireMemberRole(member, WRITER_ROLES);
      if (backup.exists) {
        if (backup.data()?.status !== "PURGED") {
          throw new HttpsError("already-exists", "DOCUMENT_IMAGE_ID_EXISTS");
        }
        tx.update(paths.operationRef, {
          requestHash: preflightHash,
          requestHashVersion: "PREFLIGHT_V1",
          status: "COMPLETE",
          discardedByPurge: true,
          authorizedRole,
          leaseExpiresAt: FieldValue.delete(),
          expiresAt: FieldValue.delete(),
          completedAt: FieldValue.serverTimestamp(),
        });
        return {
          replay: { ok: true, idempotencyKey, receiptId: saved.receiptId, discardedByPurge: true },
        };
      } else {
        documentQuota(purchaseMeta, businessMeta, document.content.length);
      }
      const claimGeneration = saved.claimGeneration + 1;
      tx.update(paths.operationRef, {
        authorizedRole,
        claimGeneration,
        leaseExpiresAt: Timestamp.fromMillis(nowMillis + UPLOAD_PREFLIGHT_LEASE_MILLIS),
        expiresAt: Timestamp.fromMillis(nowMillis + FAILED_UPLOAD_RETENTION_MILLIS),
      });
      return { replay: null, claimGeneration, dimensions: null };
    }

    const authorizedRole = requireMemberRole(member, WRITER_ROLES);
    if (backup.exists) {
      if (backup.data()?.status !== "PURGED") {
        throw new HttpsError("already-exists", "DOCUMENT_IMAGE_ID_EXISTS");
      }
      // El tombstone de purga es causal y durable. Registrar el ACK aquí evita incluso abrir
      // Sharp para bytes que, por definición, no pueden volver a Storage.
      tx.create(paths.operationRef, {
        idempotencyKey,
        preflightHash,
        requestHash: preflightHash,
        requestHashVersion: "PREFLIGHT_V1",
        operationType: "SYNC_DOCUMENT_UPLOAD",
        purchaseId: document.purchaseId,
        imageId: document.imageId,
        receiptId,
        status: "COMPLETE",
        discardedByPurge: true,
        authorizedRole,
        createdAt: FieldValue.serverTimestamp(),
        completedAt: FieldValue.serverTimestamp(),
      });
      return {
        replay: { ok: true, idempotencyKey, receiptId, discardedByPurge: true },
      };
    }

    documentQuota(purchaseMeta, businessMeta, document.content.length);
    const rateLimits = [
      {
        scope: "ACTOR",
        ref: paths.actorRateRef,
        snapshot: actorRate,
        policy: DOCUMENT_UPLOAD_RATE_POLICIES.ACTOR,
      },
      {
        scope: "BUSINESS",
        ref: paths.businessRateRef,
        snapshot: businessRate,
        policy: DOCUMENT_UPLOAD_RATE_POLICIES.BUSINESS,
      },
    ];
    const rateStates = rateLimits.map((rate) => ({
      ref: rate.ref,
      state: nextDocumentUploadRateState(
        rate.snapshot,
        rate.scope,
        rate.policy,
        nowMillis,
      ),
    }));
    const claimGeneration = 1;
    tx.create(paths.operationRef, {
      idempotencyKey,
      preflightHash,
      operationType: "SYNC_DOCUMENT_UPLOAD",
      purchaseId: document.purchaseId,
      imageId: document.imageId,
      receiptId,
      status: "PREFLIGHT",
      authorizedRole,
      claimGeneration,
      leaseExpiresAt: Timestamp.fromMillis(nowMillis + UPLOAD_PREFLIGHT_LEASE_MILLIS),
      expiresAt: Timestamp.fromMillis(nowMillis + FAILED_UPLOAD_RETENTION_MILLIS),
      createdAt: FieldValue.serverTimestamp(),
    });
    for (const rate of rateStates) tx.set(rate.ref, rate.state);
    return { replay: null, claimGeneration, dimensions: null };
  });
}

async function rememberDocumentValidationFailure({
  businessId,
  uid,
  document,
  idempotencyKey,
  preflightHash,
  claimGeneration,
  failure,
  nowMillis = Date.now(),
}) {
  if (
    !(failure instanceof HttpsError) || failure.code !== "invalid-argument" ||
    !DEFINITIVE_IMAGE_VALIDATION_ERRORS.has(failure.message) || claimGeneration === null
  ) {
    return;
  }
  const operationRef = refs(
    businessId,
    uid,
    document.purchaseId,
    document.imageId,
    idempotencyKey,
  ).operationRef;
  await db.runTransaction(async (tx) => {
    const operation = await tx.get(operationRef);
    if (!operation.exists) return;
    const saved = operation.data();
    if (
      saved.status !== "PREFLIGHT" || saved.preflightHash !== preflightHash ||
      saved.claimGeneration !== claimGeneration
    ) {
      return;
    }
    tx.update(operationRef, {
      status: "VALIDATION_FAILED",
      validationError: failure.message,
      leaseExpiresAt: FieldValue.delete(),
      expiresAt: Timestamp.fromMillis(nowMillis + FAILED_UPLOAD_RETENTION_MILLIS),
      completedAt: FieldValue.serverTimestamp(),
    });
  });
}

async function performPurchaseDocumentUpload(request, decoder, bucketProvider) {
  const { auth, data, businessId, idempotencyKey } = canonicalRequest(
    request, "SYNC_DOCUMENT_UPLOAD",
  );
  const parsed = parseUploadDocument(data.document, idempotencyKey);
  const receiptId = receiptFor("doc", idempotencyKey);
  const preflightHash = uploadPreflightHash(businessId, idempotencyKey, parsed);
  const preflight = await preflightDocumentUpload({
    businessId,
    uid: auth.uid,
    document: parsed,
    idempotencyKey,
    preflightHash,
    receiptId,
  });
  if (preflight.replay) return preflight.replay;
  let dimensions = preflight.dimensions;
  if (dimensions === null) {
    try {
      dimensions = requireDimensions(await decoder(parsed.content));
    } catch (failure) {
      await rememberDocumentValidationFailure({
        businessId,
        uid: auth.uid,
        document: parsed,
        idempotencyKey,
        preflightHash,
        claimGeneration: preflight.claimGeneration,
        failure,
      });
      throw failure;
    }
  }
  const document = { ...parsed, dimensions };
  const requestHash = sha256(JSON.stringify({
    businessId,
    idempotencyKey,
    operationType: data.operationType,
    payloadVersion: 1,
    document: {
      version: 1,
      purchaseId: document.purchaseId,
      imageId: document.imageId,
      sha256: document.sha256,
      size: document.content.length,
      dimensions: document.dimensions,
    },
  }));
  const paths = refs(businessId, auth.uid, document.purchaseId, document.imageId, idempotencyKey);
  const reservation = await db.runTransaction(async (tx) => {
    const snapshots = await tx.getAll(
      paths.businessRef, paths.tombstoneRef, paths.memberRef, paths.purchaseRef,
      paths.backupRef, paths.purchaseMetaRef, paths.businessMetaRef, paths.operationRef,
    );
    const [business, tombstone, member, purchase, backup, purchaseMeta, businessMeta, operation] =
      snapshots;
    requireCoreSnapshots(business, tombstone, member, purchase);
    if (!operation.exists) {
      throw new HttpsError("data-loss", "DOCUMENT_PREFLIGHT_LOST");
    }
    const saved = operation.data();
    requireMatchingUploadOperation(saved, { document, idempotencyKey, preflightHash });
    if (saved.status === "COMPLETE") {
      if (saved.requestHashVersion !== "PREFLIGHT_V1" && saved.requestHash !== requestHash) {
        throw new HttpsError("already-exists", "DOCUMENT_REPLAY_MISMATCH");
      }
      return { complete: true, purged: backup.data()?.status === "PURGED" };
    }
    if (saved.status === "RESERVED") {
      if (saved.requestHash !== requestHash) {
        throw new HttpsError("already-exists", "DOCUMENT_REPLAY_MISMATCH");
      }
      return { complete: false, purged: backup.data()?.status === "PURGED" };
    }
    if (saved.status === "VALIDATION_FAILED") throw validationFailureFor(saved);
    if (
      saved.status !== "PREFLIGHT" ||
      saved.claimGeneration !== preflight.claimGeneration
    ) {
      throw new HttpsError("unavailable", "DOCUMENT_PREFLIGHT_LEASE_LOST");
    }
    const authorizedRole = saved.authorizedRole;
    if (!WRITER_ROLES.includes(authorizedRole)) {
      throw new HttpsError("data-loss", "DOCUMENT_PREFLIGHT_STATE_INVALID");
    }
    if (backup.exists) {
      if (backup.data()?.status !== "PURGED") {
        throw new HttpsError("already-exists", "DOCUMENT_IMAGE_ID_EXISTS");
      }
      // Una purga pudo ganar la carrera antes de que llegara este upload. Registrar el ACK
      // descartado impide reintentos eternos y, sobre todo, nunca recrea el objeto.
      tx.update(paths.operationRef, {
        requestHash,
        status: "COMPLETE",
        discardedByPurge: true,
        leaseExpiresAt: FieldValue.delete(),
        expiresAt: FieldValue.delete(),
        completedAt: FieldValue.serverTimestamp(),
      });
      return { complete: true, purged: true };
    }
    const { pageCount, totalBytes } = documentQuota(
      purchaseMeta,
      businessMeta,
      document.content.length,
    );
    tx.update(paths.operationRef, {
      requestHash,
      status: "RESERVED",
      leaseExpiresAt: FieldValue.delete(),
      expiresAt: FieldValue.delete(),
      reservedAt: FieldValue.serverTimestamp(),
    });
    tx.create(paths.backupRef, {
      purchaseId: document.purchaseId, imageId: document.imageId, sha256: document.sha256,
      size: document.content.length, width: document.dimensions.width,
      height: document.dimensions.height, status: "RESERVED", purgeRequested: false,
      authorizedRole, createdAt: FieldValue.serverTimestamp(),
    });
    tx.set(paths.purchaseMetaRef, {
      pageCount: pageCount + 1, updatedAt: FieldValue.serverTimestamp(),
    });
    tx.set(paths.businessMetaRef, {
      totalBytes: totalBytes + document.content.length, updatedAt: FieldValue.serverTimestamp(),
    });
    return { complete: false, purged: false };
  });

  const file = bucketProvider().file(
    objectPath(businessId, document.purchaseId, document.imageId),
  );
  if (!reservation.complete) {
    try {
      await file.save(document.content, {
        resumable: false,
        validation: false,
        preconditionOpts: { ifGenerationMatch: 0 },
        metadata: {
          contentType: "image/jpeg",
          cacheControl: "private,no-store",
          metadata: {
            sha256: document.sha256,
            purchaseId: document.purchaseId,
            imageId: document.imageId,
          },
        },
      });
    } catch (failure) {
      if (failure?.code !== 412) throw failure;
      const [metadata] = await file.getMetadata();
      if (metadata.metadata?.sha256 !== document.sha256 ||
          metadata.metadata?.purchaseId !== document.purchaseId ||
          metadata.metadata?.imageId !== document.imageId) {
        throw new HttpsError("already-exists", "DOCUMENT_OBJECT_COLLISION");
      }
    }
  }

  const mustPurge = await finalizeReservedDocumentUpload({
    businessId,
    uid: auth.uid,
    purchaseId: document.purchaseId,
    imageId: document.imageId,
    idempotencyKey,
    requestHash,
    file,
  });
  return { ok: true, idempotencyKey, receiptId, discardedByPurge: reservation.purged || mustPurge };
}

/**
 * Los errores esperados ya son HttpsError con un código público cerrado. Cualquier excepción
 * bruta del Admin SDK se reemplaza aquí, antes de que el wrapper callable pueda registrar o
 * devolver mensajes que contengan nombres de bucket, rutas privadas o detalles del proveedor.
 */
export async function sanitizeDocumentCallable(operation) {
  try {
    return await operation();
  } catch (failure) {
    if (failure instanceof HttpsError) throw failure;
    throw new HttpsError("unavailable", "DOCUMENT_STORAGE_UNAVAILABLE");
  }
}

export async function uploadPurchaseDocumentHandler(
  request,
  decoder = decodeJpeg,
  bucketProvider = storageBucket,
) {
  return sanitizeDocumentCallable(
    () => performPurchaseDocumentUpload(request, decoder, bucketProvider),
  );
}

export const uploadPurchaseDocument = onCall(
  DOCUMENT_CALLABLE_OPTIONS,
  (request) => uploadPurchaseDocumentHandler(request),
);

async function performPurchaseDocumentPurge(request, bucketProvider) {
  const { auth, data, businessId, idempotencyKey } = canonicalRequest(
    request, "SYNC_DOCUMENT_PURGE",
  );
  const document = parsePurgeDocument(data.document, idempotencyKey);
  const receiptId = receiptFor("purge", idempotencyKey);
  const requestHash = sha256(JSON.stringify({
    businessId, idempotencyKey, operationType: data.operationType,
    payloadVersion: 1, document,
  }));
  const paths = refs(businessId, auth.uid, document.purchaseId, document.imageId, idempotencyKey);
  const reservation = await db.runTransaction(async (tx) => {
    const snapshots = await tx.getAll(
      paths.businessRef, paths.tombstoneRef, paths.memberRef, paths.purchaseRef,
      paths.backupRef, paths.purchaseMetaRef, paths.businessMetaRef, paths.operationRef,
    );
    const [business, tombstone, member, purchase, backup, purchaseMeta, businessMeta, operation] =
      snapshots;
    if (operation.exists) {
      const saved = operation.data();
      requireMatchingPurgeOperation(saved, { document, idempotencyKey, requestHash });
      requireMatchingBackupIdentity(backup, document);
      // El primer commit ya autorizó y fijó esta intención. Un retry que solo completa el
      // delete de Storage no vuelve a depender de una membresía/compra que pudo desaparecer;
      // sí exige el mismo actor pseudonimizado y el cuerpo exacto guardados antes del efecto.
      if (
        saved.authorizedActorHash === sha256(`facturastock:document-purge-actor:v1:${auth.uid}`) &&
        ["RESERVED", "COMPLETE"].includes(saved.status) &&
        (!backup.exists || backup.data()?.status === "PURGED")
      ) {
        return { complete: saved.status === "COMPLETE" };
      }
    }
    requireCoreSnapshots(business, tombstone, member, purchase);
    if (operation.exists) {
      // Operación legacy sin authorizedUid: conserva la autorización fresca anterior.
      return { complete: operation.data()?.status === "COMPLETE" };
    }
    const authorizedRole = requireMemberRole(member, WRITER_ROLES);
    // documentBackups está indexado por imageId a nivel de negocio. Nunca se permite combinar
    // una compra terminal válida con la imagen de otra compra del mismo tenant.
    requireMatchingBackupIdentity(backup, document);
    tx.create(paths.operationRef, {
      idempotencyKey, requestHash, operationType: "SYNC_DOCUMENT_PURGE",
      purchaseId: document.purchaseId, imageId: document.imageId, receiptId,
      status: "RESERVED",
      authorizedRole,
      // Pseudónimo cerrado: permite autenticar el replay sin retener el UID personal crudo.
      authorizedActorHash: sha256(`facturastock:document-purge-actor:v1:${auth.uid}`),
      createdAt: FieldValue.serverTimestamp(),
    });
    if (backup.exists && backup.data()?.status !== "PURGED") {
      const pageCount = purchaseMeta.data()?.pageCount ?? 0;
      const totalBytes = businessMeta.data()?.totalBytes ?? 0;
      const size = backup.data()?.size;
      if (!Number.isSafeInteger(pageCount) || pageCount < 1 ||
          !Number.isSafeInteger(totalBytes) || totalBytes < 0 ||
          !Number.isSafeInteger(size) || size < 1 || totalBytes < size) {
        throw new HttpsError("data-loss", "DOCUMENT_QUOTA_INVALID");
      }
      tx.update(paths.backupRef, {
        status: "PURGED", purgeRequested: true, purgedAt: FieldValue.serverTimestamp(),
      });
      tx.set(paths.purchaseMetaRef, {
        pageCount: pageCount - 1, updatedAt: FieldValue.serverTimestamp(),
      });
      tx.set(paths.businessMetaRef, {
        totalBytes: totalBytes - size, updatedAt: FieldValue.serverTimestamp(),
      });
    } else if (!backup.exists) {
      // Tombstone causal: un upload iniciado antes del toggle puede llegar después de esta
      // purga. Encontrará PURGED y se registrará como descartado sin escribir Storage.
      tx.create(paths.backupRef, {
        purchaseId: document.purchaseId,
        imageId: document.imageId,
        status: "PURGED",
        purgeRequested: true,
        size: 0,
        authorizedRole,
        purgedAt: FieldValue.serverTimestamp(),
      });
    }
    return { complete: false };
  });
  const file = bucketProvider().file(
    objectPath(businessId, document.purchaseId, document.imageId),
  );
  await deleteObjectIfPresent(file);
  await paths.operationRef.update({
    status: "COMPLETE",
    completedAt: FieldValue.serverTimestamp(),
  });
  return { ok: true, idempotencyKey, receiptId };
}

export async function purgePurchaseDocumentHandler(request, bucketProvider = storageBucket) {
  return sanitizeDocumentCallable(
    () => performPurchaseDocumentPurge(request, bucketProvider),
  );
}

export const purgePurchaseDocument = onCall(
  DOCUMENT_CALLABLE_OPTIONS,
  (request) => purgePurchaseDocumentHandler(request),
);
