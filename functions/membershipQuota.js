// Contador serializable de membresías por cuenta. El documento pseudónimo es el punto único
// de contención para create/accept/remove: evita que dos altas sobre negocios distintos superen
// la cuota tras observar simultáneamente el mismo conjunto de documentos members.
import { HttpsError } from "firebase-functions/v2/https";
import { FieldValue } from "firebase-admin/firestore";
import { db, sha256 } from "./common.js";

export const MAX_BUSINESSES_PER_ACCOUNT = 20;
const MEMBERSHIP_QUOTA_NAMESPACE = "facturastock:membership-quota:v1:";

export const membershipQuotaCounterDocumentId = (uid) => {
  if (typeof uid !== "string" || uid.length === 0 || uid.length > 128) {
    throw new Error("MEMBERSHIP_QUOTA_UID_INVALID");
  }
  return sha256(`${MEMBERSHIP_QUOTA_NAMESPACE}${uid}`);
};

export const membershipQuotaCounterRef = (uid) =>
  db.doc(`membershipQuotaCounters/${membershipQuotaCounterDocumentId(uid)}`);

function requireStoredCount(snapshot) {
  const data = snapshot.data();
  if (
    data?.schemaVersion !== 1 ||
    !Number.isSafeInteger(data.count) ||
    data.count < 0
  ) {
    throw new HttpsError("data-loss", "MEMBERSHIP_QUOTA_INVALID");
  }
  return data.count;
}

async function legacyMembershipCount(tx, uid) {
  // AggregateQuery evita cargar documentos completos. Ejecutada dentro de la misma transacción,
  // bloquea los matches legacy; el counter inexistente, leído antes, serializa además cualquier
  // alta/baja nueva que use esta frontera.
  const aggregate = await tx.get(
    db.collectionGroup("members").where("uid", "==", uid).count(),
  );
  const count = aggregate.data().count;
  if (!Number.isSafeInteger(count) || count < 0) {
    throw new HttpsError("data-loss", "MEMBERSHIP_QUOTA_INVALID");
  }
  return count;
}

function counterRecord(count) {
  return {
    schemaVersion: 1,
    count,
    updatedAt: FieldValue.serverTimestamp(),
  };
}

/**
 * Ajusta la cuota en la misma transacción que muta members/{uid}.
 *
 * Un counter ausente es formato legacy: se reconstruye exactamente desde collectionGroup. Si una
 * alta excedería el límite, se persiste solo el baseline reconciliado y el caller debe confirmar
 * la transacción sin crear la membresía, para luego devolver BUSINESS_QUOTA fuera de ella.
 */
export async function adjustMembershipQuota(tx, uid, delta) {
  if (![-1, 0, 1].includes(delta)) throw new RangeError("MEMBERSHIP_QUOTA_DELTA_INVALID");
  const ref = membershipQuotaCounterRef(uid);
  const snapshot = await tx.get(ref);
  const legacy = !snapshot.exists;
  const current = legacy ? await legacyMembershipCount(tx, uid) : requireStoredCount(snapshot);
  const next = current + delta;
  if (!Number.isSafeInteger(next) || next < 0) {
    throw new HttpsError("data-loss", "MEMBERSHIP_QUOTA_INCONSISTENT");
  }
  if (delta > 0 && next > MAX_BUSINESSES_PER_ACCOUNT) {
    if (legacy && current > 0) tx.set(ref, counterRecord(current));
    return { allowed: false, count: current, nextCount: current };
  }
  if (next === 0) {
    if (snapshot.exists) tx.delete(ref);
  } else if (legacy || delta !== 0) {
    tx.set(ref, counterRecord(next));
  }
  return { allowed: true, count: current, nextCount: next };
}
