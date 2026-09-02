// Eliminación de cuenta: antes de tocar Auth descubre y valida todo el alcance. Los
// negocios donde el caller es OWNER y único miembro se borran completos; en los que
// sobreviven se eliminan sus membresías e invitaciones dirigidas a su email verificado,
// y las referencias históricas a su uid se sustituyen por un sentinel cerrado.
import { onCall, HttpsError } from "firebase-functions/v2/https";
import { getAuth } from "firebase-admin/auth";
import { Timestamp } from "firebase-admin/firestore";
import {
  ACCOUNT_DELETION_CALLABLE_OPTIONS,
  ACCOUNT_DELETION_LOCK_FIELD,
  UUID_REGEX,
  accountDeletionEmailLockRef,
  accountDeletionTombstoneRef,
  db,
  invalid,
  requireExpectedUid,
  requireRecentAuth,
  normalizeEmail,
  storageBucket,
} from "./common.js";
import { membershipQuotaCounterRef } from "./membershipQuota.js";

const DELETED_ACCOUNT_UID = "deleted-account";
const WRITE_BATCH_SIZE = 400;
// Un voidRecord puede acercarse al límite de 1 MiB. Páginas pequeñas mantienen el working set
// muy por debajo de los 512 MiB del callable; cada commit elimina el documento de la consulta
// (delete o sustitución del UID), por lo que un retry continúa desde el estado durable sin cursor.
const QUERY_PAGE_SIZE = 50;
// Firestore admite como máximo 500 escrituras por transacción. Reservamos 20 para la
// envolvente/posibles transforms y, adicionalmente, el tombstone y el lock temporal de email.
const MAX_OWNER_BUSINESSES_PER_DELETION = 480;
const EMAIL_LOCK_TTL_MILLIS = 24 * 60 * 60 * 1000;
// Mayor que el timeout de los callables documentales: ningún file.save reservado antes del
// lock puede aterrizar después de la última pasada programada de este prefijo.
const STORAGE_LATE_WRITE_GRACE_MILLIS = 10 * 60 * 1000;

function requireAccountDeletionPayload(value) {
  const data = value ?? {};
  if (
    typeof data !== "object" || Array.isArray(data) ||
    Object.keys(data).some((key) => key !== "expectedUid")
  ) {
    throw invalid("ACCOUNT_DELETION_FIELDS");
  }
  return data;
}

/**
 * Falla antes de crear tombstones o locks si el cierre inicial no cabe con margen en una sola
 * transacción. No se hace una limpieza parcial: soporte debe segmentar el alcance de la cuenta.
 */
export function requireAccountDeletionScopeWithinLimit(ownerBusinessCount) {
  if (
    !Number.isSafeInteger(ownerBusinessCount) ||
    ownerBusinessCount < 0 ||
    ownerBusinessCount > MAX_OWNER_BUSINESSES_PER_DELETION
  ) {
    throw new HttpsError("failed-precondition", "ACCOUNT_DELETION_SCOPE_TOO_LARGE");
  }
}

function directBusinessRefFor(ref) {
  const businessRef = ref.parent.parent;
  if (
    businessRef === null ||
    businessRef.parent.id !== "businesses" ||
    businessRef.parent.parent !== null
  ) {
    throw new HttpsError("internal", "ACCOUNT_DELETION_DATA_INCONSISTENT");
  }
  return businessRef;
}

function businessRootPathFor(ref) {
  const [collectionId, businessId] = ref.path.split("/");
  return collectionId === "businesses" && businessId
    ? `businesses/${businessId}`
    : null;
}

function belongsToDeletedBusiness(ref, deletedBusinessPaths) {
  const rootPath = businessRootPathFor(ref);
  return rootPath !== null && deletedBusinessPaths.has(rootPath);
}

function addDelete(mutations, document) {
  mutations.set(document.ref.path, {
    type: "delete",
    ref: document.ref,
    lastUpdateTime: document.updateTime,
  });
}

function sameUpdateTime(left, right) {
  return left === right || left?.isEqual?.(right) === true;
}

function addUpdateFields(mutations, document, data) {
  const current = mutations.get(document.ref.path);
  if (current?.type === "delete") return;
  if (current?.type === "update") {
    if (!sameUpdateTime(current.lastUpdateTime, document.updateTime)) {
      throw new HttpsError("aborted", "ACCOUNT_DELETION_RETRY");
    }
    Object.assign(current.data, data);
    return;
  }
  mutations.set(document.ref.path, {
    type: "update",
    ref: document.ref,
    data: { ...data },
    lastUpdateTime: document.updateTime,
  });
}

function addUpdate(mutations, document, field) {
  addUpdateFields(mutations, document, { [field]: DELETED_ACCOUNT_UID });
}

/**
 * Segunda validación + locks transaccionales antes de consultar el alcance a limpiar. El
 * tombstone pseudónimo se conserva para bloquear JWT viejos; los locks de negocio impiden que
 * otro usuario acepte una invitación o escriba bajo un árbol que va a desaparecer.
 */
export async function lockAccountDeletion(uid, email = null) {
  return db.runTransaction(async (tx) => {
    const tombstoneRef = accountDeletionTombstoneRef(uid);
    const emailLockRef = email === null ? null : accountDeletionEmailLockRef(email);
    const deletionLocks = await tx.getAll(
      tombstoneRef,
      ...(emailLockRef === null ? [] : [emailLockRef]),
    );
    const tombstone = deletionLocks[0];
    const memberships = await tx.get(
      db.collectionGroup("members").where("uid", "==", uid),
    );
    const savedCleanupIds = tombstone.data()?.storageCleanupBusinessIds ??
      tombstone.data()?.soleOwnedBusinessIds ?? [];
    const savedRetiredIds = tombstone.data()?.retiredBusinessIds ?? [];
    if (!Array.isArray(savedCleanupIds) || !Array.isArray(savedRetiredIds)) {
      throw new HttpsError("internal", "ACCOUNT_DELETION_DATA_INCONSISTENT");
    }
    const pendingBusinessIds = new Set(savedCleanupIds);
    const retiredBusinessIds = new Set(savedRetiredIds);
    if ([...pendingBusinessIds].some((businessId) => !UUID_REGEX.test(businessId))) {
      throw new HttpsError("internal", "ACCOUNT_DELETION_DATA_INCONSISTENT");
    }
    if ([...retiredBusinessIds].some((businessId) => !UUID_REGEX.test(businessId))) {
      throw new HttpsError("internal", "ACCOUNT_DELETION_DATA_INCONSISTENT");
    }
    const currentOwnerRefs = new Map();
    for (const member of memberships.docs) {
      if (member.data().role === "OWNER") {
        const businessRef = directBusinessRefFor(member.ref);
        if (!UUID_REGEX.test(businessRef.id)) {
          throw new HttpsError("internal", "ACCOUNT_DELETION_DATA_INCONSISTENT");
        }
        currentOwnerRefs.set(businessRef.id, businessRef);
      }
    }
    const businessRefs = [...new Set([...pendingBusinessIds, ...currentOwnerRefs.keys()])]
      .sort()
      .map((businessId) => db.doc(`businesses/${businessId}`));
    requireAccountDeletionScopeWithinLimit(businessRefs.length);
    const businessSnapshots =
      businessRefs.length === 0 ? [] : await tx.getAll(...businessRefs);
    const checks = [];
    for (let index = 0; index < businessRefs.length; index += 1) {
      const businessRef = businessRefs[index];
      // Solo importa distinguir 0, 1 o más miembros. Acotar evita cargar una empresa
      // completa en memoria para rechazar el borrado del único OWNER.
      const members = await tx.get(businessRef.collection("members").limit(2));
      checks.push({ businessRef, business: businessSnapshots[index], members });
    }
    const blocked = checks
      .filter(({ business, members }) => {
        if (!business.exists) {
          if (pendingBusinessIds.has(business.ref.id)) return false;
          throw new HttpsError("internal", "ACCOUNT_DELETION_DATA_INCONSISTENT");
        }
        if (members.size === 0 && pendingBusinessIds.has(business.ref.id)) return false;
        if (members.size !== 1) return true;
        const soleMember = members.docs[0].data();
        return soleMember.uid !== uid || soleMember.role !== "OWNER";
      })
      .map(({ businessRef, business }) => ({
        businessId: businessRef.id,
        legalName: business.data()?.displayName ?? null,
      }))
      .sort((left, right) => left.businessId.localeCompare(right.businessId));
    if (blocked.length > 0) {
      throw new HttpsError("failed-precondition", "OWNED_BUSINESS_HAS_MEMBERS", {
        businesses: blocked,
      });
    }
    tx.set(tombstoneRef, {
      schemaVersion: 1,
      blocksMutations: true,
      cleanupComplete: businessRefs.length === 0,
      storageCleanupBusinessIds: businessRefs.map((businessRef) => businessRef.id),
      retiredBusinessIds: [...new Set([
        ...retiredBusinessIds,
        ...businessRefs.map((businessRef) => businessRef.id),
      ])].sort(),
      storageCleanupPending: businessRefs.length > 0,
      storageCleanupEligibleAt: Timestamp.fromMillis(
        Date.now() + STORAGE_LATE_WRITE_GRACE_MILLIS,
      ),
    });
    if (emailLockRef !== null) {
      tx.set(emailLockRef, {
        schemaVersion: 1,
        blocksInvitations: true,
        // Defensa de recuperación: si Auth se elimina pero falla el delete final de esta guarda,
        // nunca puede inmovilizar ese correo para siempre. inviteMember limpia una guarda vencida
        // dentro de la misma transacción que crea la nueva invitación.
        expiresAt: Timestamp.fromMillis(Date.now() + EMAIL_LOCK_TTL_MILLIS),
      });
    }
    for (const { businessRef, business } of checks) {
      if (business.exists) {
        tx.update(businessRef, { [ACCOUNT_DELETION_LOCK_FIELD]: true });
      }
    }
    const deletedBusinessPaths = new Set(businessRefs.map((ref) => ref.path));
    return {
      soleOwnedRefs: businessRefs,
      retiredBusinessIds: [...new Set([
        ...retiredBusinessIds,
        ...businessRefs.map((businessRef) => businessRef.id),
      ])].sort(),
      survivingMembers: memberships.docs.filter(
        (member) => !deletedBusinessPaths.has(directBusinessRefFor(member.ref).path),
      ),
    };
  });
}

// Exportado para poder probar el particionado sin depender del Emulator Suite. Cada
// commit se espera antes de crear el siguiente: si uno falla, el caller no llega a Auth.
export async function commitAccountDeletionMutations(
  firestore,
  mutations,
  batchSize = WRITE_BATCH_SIZE,
) {
  if (!Number.isInteger(batchSize) || batchSize < 1 || batchSize > 500) {
    throw new RangeError("INVALID_ACCOUNT_DELETION_BATCH_SIZE");
  }
  const ordered = [...mutations].sort((left, right) =>
    left.ref.path.localeCompare(right.ref.path),
  );
  for (let offset = 0; offset < ordered.length; offset += batchSize) {
    const batch = firestore.batch();
    for (const mutation of ordered.slice(offset, offset + batchSize)) {
      const precondition = mutation.lastUpdateTime
        ? { lastUpdateTime: mutation.lastUpdateTime }
        : undefined;
      if (mutation.type === "delete") {
        batch.delete(mutation.ref, precondition);
      } else {
        batch.update(mutation.ref, mutation.data, precondition);
      }
    }
    await batch.commit();
  }
}

/**
 * Consume una consulta por igualdad en páginas acotadas. collectMutation debe borrar el documento
 * o cambiar el campo que lo hizo coincidir; esa propiedad convierte la propia consulta en cursor
 * durable y evita tanto retener todos los snapshots como reiniciar trabajo ya confirmado.
 */
export async function consumeAccountDeletionQuery(
  query,
  collectMutation,
  firestore = db,
  pageSize = QUERY_PAGE_SIZE,
) {
  if (!Number.isInteger(pageSize) || pageSize < 1 || pageSize > WRITE_BATCH_SIZE) {
    throw new RangeError("INVALID_ACCOUNT_DELETION_QUERY_PAGE_SIZE");
  }
  let processed = 0;
  while (true) {
    const snapshot = await query.limit(pageSize).get();
    if (snapshot.empty) return processed;
    const mutations = new Map();
    for (const document of snapshot.docs) collectMutation(mutations, document);
    // Sin esta propiedad la siguiente página devolvería los mismos documentos indefinidamente.
    if (mutations.size !== snapshot.size) {
      throw new HttpsError("internal", "ACCOUNT_DELETION_QUERY_DID_NOT_ADVANCE");
    }
    await commitAccountDeletionMutations(firestore, mutations.values());
    processed += snapshot.size;
  }
}

async function deleteAuthUserIdempotently(uid) {
  try {
    await getAuth().deleteUser(uid);
  } catch (error) {
    if (error?.code !== "auth/user-not-found") throw error;
  }
}

// Auth es el punto irreversible. Una vez eliminado, un fallo de red al retirar la guarda de
// email no puede convertir el callable en un falso fallo: su TTL es la recuperación durable.
// La dependencia opcional permite probar ese borde sin tocar Admin SDK ni registrar el error.
export async function deleteEmailLockBestEffort(
  email,
  deleteLock = (ref) => ref.delete(),
) {
  if (email === null) return;
  try {
    await deleteLock(accountDeletionEmailLockRef(email));
  } catch (_failure) {
    // Best-effort deliberado: inviteMember limpia la guarda al vencer como máximo en 24 h.
  }
}

/** Purga Storage antes del punto irreversible de Auth; cualquier fallo deja la cuenta intacta. */
export async function purgeOwnedBusinessDocuments(
  businessRefs,
  bucket = storageBucket(),
) {
  for (const businessRef of businessRefs) {
    await bucket.deleteFiles({ prefix: `businesses/${businessRef.id}/` });
  }
}

export const deleteMyAccount = onCall(ACCOUNT_DELETION_CALLABLE_OPTIONS, async (request) => {
  requireExpectedUid(request);
  requireAccountDeletionPayload(request.data);
  const auth = requireRecentAuth(request);
  const uid = auth.uid;
  // Solo un email verificado autoriza a borrar invitaciones dirigidas a ese email.
  const rawEmail = auth.token.email;
  const email =
    auth.token.email_verified === true && typeof rawEmail === "string"
      ? normalizeEmail(rawEmail)
      : null;

  const { soleOwnedRefs, retiredBusinessIds, survivingMembers } =
    await lockAccountDeletion(uid, email);
  const deletedBusinessPaths = new Set(soleOwnedRefs.map((ref) => ref.path));

  // El tombstone ya impide que el UID cree referencias nuevas. Primero se retiran árboles propios:
  // así ninguna consulta collectionGroup posterior queda atascada en documentos que de todos modos
  // pertenecen a un negocio que se debe borrar completo.
  await purgeOwnedBusinessDocuments(soleOwnedRefs);
  for (const businessRef of soleOwnedRefs) {
    await db.recursiveDelete(businessRef);
  }
  // Cierra la ventana de un upload que ya estaba reservado y terminó su `file.save` durante
  // el recursiveDelete. El trigger con retry:true sigue siendo la defensa durable si el save
  // aterriza todavía después de esta segunda pasada.
  await purgeOwnedBusinessDocuments(soleOwnedRefs);

  const membershipMutations = new Map();
  for (const member of survivingMembers) {
    if (!belongsToDeletedBusiness(member.ref, deletedBusinessPaths)) addDelete(membershipMutations, member);
  }
  await commitAccountDeletionMutations(db, membershipMutations.values());

  const deleteMatches = (mutations, document) => addDelete(mutations, document);
  const anonymize = (field) => (mutations, document) => addUpdate(mutations, document, field);
  // El borrado gana sobre cualquier anonimización. email/acceptedBy/declinedBy se procesan primero;
  // los documentos que sobreviven pueden después anonimizar autoría/cancelación de forma segura.
  if (email !== null) {
    await consumeAccountDeletionQuery(
      db.collectionGroup("invitations").where("email", "==", email),
      deleteMatches,
    );
  }
  await consumeAccountDeletionQuery(
    db.collectionGroup("invitations").where("acceptedBy", "==", uid),
    deleteMatches,
  );
  await consumeAccountDeletionQuery(
    db.collectionGroup("invitations").where("declinedBy", "==", uid),
    deleteMatches,
  );
  for (const [query, field] of [
    [db.collection("businesses").where("createdBy", "==", uid), "createdBy"],
    [db.collectionGroup("invitations").where("invitedBy", "==", uid), "invitedBy"],
    [db.collectionGroup("invitations").where("cancelledBy", "==", uid), "cancelledBy"],
    [db.collectionGroup("members").where("roleUpdatedBy", "==", uid), "roleUpdatedBy"],
    [db.collectionGroup("purchases").where("syncedBy", "==", uid), "syncedBy"],
    [db.collectionGroup("purchases").where("voidedBy", "==", uid), "voidedBy"],
    [db.collectionGroup("voidRecord").where("cloudActorUid", "==", uid), "cloudActorUid"],
  ]) {
    await consumeAccountDeletionQuery(query, anonymize(field));
  }
  // El tombstone ya bloquea cualquier alta/baja concurrente. Cerrar contador y estado de limpieza
  // en el mismo batch evita dejar una cuota huérfana si el proceso cae antes de borrar Auth.
  const completion = db.batch();
  completion.delete(membershipQuotaCounterRef(uid));
  completion.set(accountDeletionTombstoneRef(uid), {
    schemaVersion: 1,
    blocksMutations: true,
    cleanupComplete: true,
    // Se conservan hasta que el scheduler confirme otra purga pasada la ventana máxima de
    // file.save tardío. Borrar estos IDs aquí dejaría un objeto sin control-plane recuperable.
    storageCleanupBusinessIds: soleOwnedRefs.map((ref) => ref.id),
    retiredBusinessIds,
    storageCleanupPending: soleOwnedRefs.length > 0,
    storageCleanupEligibleAt: Timestamp.fromMillis(
      Date.now() + STORAGE_LATE_WRITE_GRACE_MILLIS,
    ),
  });
  await completion.commit();
  await deleteAuthUserIdempotently(uid);
  // El lock del email solo serializa el barrido. Se retira después de limpiar datos y Auth:
  // una transacción inviteMember que lo hubiera leído entra en conflicto y solo puede
  // reintentarse como una invitación nueva posterior a la eliminación.
  await deleteEmailLockBestEffort(email);

  return {
    businessesDeleted: soleOwnedRefs.map((ref) => ref.id),
    membershipsRemoved: survivingMembers.length,
  };
});
