// Pull incremental del respaldo de FacturaStock: callable `listChanges`. Devuelve las
// compras (altas y anulaciones) con seq mayor que el cursor del cliente, ordenadas por la
// secuencia monotónica del negocio que asignan recordPurchase/recordVoid (index.js).
// Exige autenticación con email verificado y membresía; cualquier rol lista, incluido
// READER (es lectura del propio respaldo del negocio).
import { onCall, HttpsError } from "firebase-functions/v2/https";
import {
  CALLABLE_OPTIONS,
  accountDeletionTombstoneRef,
  db,
  invalid,
  requireExpectedUid,
  requireVerifiedEmail,
  requireAccountNotDeleting,
  requireBusinessNotDeleting,
  requireBusinessId,
} from "./common.js";

const DEFAULT_LIMIT = 100;
const MAX_LIMIT = 200;
// Aunque el contrato acepte hasta 200, cada lectura interna usa una ventana compacta y un
// presupuesto serializado. El cliente ya pagina por hasMore/nextCursor, por lo que una página
// menor no cambia semántica y evita cargar cientos de compras cercanas al límite de Firestore.
const MAX_QUERY_WINDOW = 20;
const MAX_RESPONSE_BYTES = 4_000_000;
const LIST_CHANGE_KEYS = new Set(["businessId", "sinceSeq", "limit", "expectedUid"]);

function requireListChangeShape(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw invalid("SYNC_PULL_FIELDS");
  }
  if (Object.keys(value).some((key) => !LIST_CHANGE_KEYS.has(key))) {
    throw invalid("SYNC_PULL_FIELDS");
  }
  return value;
}

function requireSinceSeq(value) {
  const sinceSeq = value ?? 0;
  if (!Number.isSafeInteger(sinceSeq) || sinceSeq < 0) throw invalid("SINCE_SEQ");
  return sinceSeq;
}

function requireLimit(value) {
  const limit = value ?? DEFAULT_LIMIT;
  if (!Number.isInteger(limit) || limit < 1 || limit > MAX_LIMIT) throw invalid("LIMIT");
  return limit;
}

// Proyección cerrada del documento de compra para el pull: cabecera, totales, resumen de
// movimientos y acuse. `syncedAt` es Timestamp del servidor; puede ser null recién
// escrito, así que viaja como millis o null.
function toChange(doc) {
  const data = doc.data();
  return {
    seq: data.seq,
    purchaseId: doc.id,
    status: data.status,
    documentType: data.documentType,
    documentSeries: data.documentSeries,
    documentNumber: data.documentNumber,
    issueDate: data.issueDate,
    currency: data.currency,
    supplierRuc: data.supplierRuc ?? null,
    supplierLegalName: data.supplierLegalName,
    totalMinorUnits: data.totalMinorUnits,
    movementSummary: data.movementSummary ?? [],
    receiptId: data.receiptId,
    syncedAtMillis: data.syncedAt?.toMillis() ?? null,
    // La proyección incremental no necesita identidad de cuenta. El root autorizado conserva
    // autoría para detalle/auditoría; el feed compacto devuelve siempre null.
    syncedBy: null,
  };
}

function responsePage(snapshot, requestedLimit, sinceSeq) {
  const changes = [];
  let serializedBytes = 0;
  const deliverable = snapshot.docs.slice(0, requestedLimit);
  for (const document of deliverable) {
    const change = toChange(document);
    const changeBytes = Buffer.byteLength(JSON.stringify(change), "utf8");
    if (changes.length > 0 && serializedBytes + changeBytes > MAX_RESPONSE_BYTES) break;
    if (changeBytes > MAX_RESPONSE_BYTES) {
      throw new HttpsError("data-loss", "SYNC_CHANGE_TOO_LARGE");
    }
    changes.push(change);
    serializedBytes += changeBytes;
  }
  return {
    changes,
    nextCursor: changes.at(-1)?.seq ?? sinceSeq,
    hasMore: snapshot.size > changes.length,
  };
}

export const listChanges = onCall(CALLABLE_OPTIONS, async (request) => {
  requireExpectedUid(request);
  requireVerifiedEmail(request);
  const data = requireListChangeShape(request.data ?? {});
  const businessId = requireBusinessId(data.businessId);
  const sinceSeq = requireSinceSeq(data.sinceSeq);
  const limit = requireLimit(data.limit);

  const businessRef = db.doc(`businesses/${businessId}`);
  const memberRef = businessRef.collection("members").doc(request.auth.uid);
  const tombstoneRef = accountDeletionTombstoneRef(request.auth.uid);
  const queryWindow = Math.min(limit, MAX_QUERY_WINDOW);
  const query = db
      // recordPurchase/recordVoid mantienen esta proyección cerrada sin líneas, payloads ni
      // identificadores de cuenta. Nunca se cargan aquí los documentos raíz de hasta 900 KB.
      .collection(`businesses/${businessId}/syncChanges`)
      .where("seq", ">", sinceSeq)
      .orderBy("seq", "asc")
      // El elemento adicional decide hasMore en el mismo snapshot; nunca se usa el seq global
      // como cursor consumido porque una escritura concurrente podría quedar saltada.
      .limit(queryWindow + 1);

  // Cuenta, negocio, membresía y página pertenecen a un único snapshot transaccional. Una
  // baja/lock concurrente invalida la lectura en lugar de autorizarla con un dato obsoleto.
  return db.runTransaction(async (tx) => {
    const [business, tombstone, member] = await tx.getAll(
      businessRef,
      tombstoneRef,
      memberRef,
    );
    requireAccountNotDeleting(tombstone);
    requireBusinessNotDeleting(business);
    if (!member.exists) throw new HttpsError("permission-denied", "NOT_A_MEMBER");
    const snapshot = await tx.get(query);
    // Único cursor durable: la seq del último cambio efectivamente entregado, o el cursor de
    // entrada si la página está vacía. `hasMore` decide si el cliente debe pedir otra página.
    return responsePage(snapshot, queryWindow, sinceSeq);
  });
});
