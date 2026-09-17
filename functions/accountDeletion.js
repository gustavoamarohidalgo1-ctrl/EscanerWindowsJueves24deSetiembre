// Eliminación de cuenta: antes de tocar Auth descubre y valida todo el alcance. Los
// negocios donde el caller es OWNER y único miembro se borran completos; en los que
// sobreviven se eliminan sus membresías e invitaciones dirigidas a su email verificado,
// y las referencias históricas a su uid se sustituyen por un sentinel cerrado.
import { onCall, HttpsError } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { randomUUID } from "node:crypto";
import { getAuth } from "firebase-admin/auth";
import { FieldValue, Timestamp } from "firebase-admin/firestore";
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
// El lease supera el timeout de 300 s del callable/scheduler. Un proceso caído deja trabajo
// elegible sin necesitar otro login ni conservar un token de autenticación.
const DELETION_LEASE_MILLIS = 6 * 60 * 1000;
const DELETION_RETRY_MILLIS = 60 * 1000;
const DELETION_RECOVERY_LIMIT = 20;
export const accountDeletionJobRef = (uid) =>
  db.collection("accountDeletionJobs").doc(accountDeletionTombstoneRef(uid).id);

function validDeletionJob(job, id) {
  return job?.schemaVersion === 1 && typeof job.uid === "string" &&
    job.uid.length > 0 && job.uid.length <= 128 &&
    accountDeletionJobRef(job.uid).id === id &&
    (job.email === null || (typeof job.email === "string" &&
      normalizeEmail(job.email) === job.email)) &&
    ["CLEANUP", "AUTH"].includes(job.phase) &&
    Array.isArray(job.summary?.businessesDeleted) &&
    job.summary.businessesDeleted.every((value) => UUID_REGEX.test(value)) &&
    Number.isSafeInteger(job.summary?.membershipsRemoved) &&
    job.summary.membershipsRemoved >= 0 &&
    typeof job.leaseToken === "string" &&
    job.nextAttemptAt instanceof Timestamp;
}

function completedSummary(tombstone) {
  return tombstone.deletionSummary ?? {
    businessesDeleted: tombstone.retiredBusinessIds ?? [],
    membershipsRemoved: 0,
  };
}

function requireAccountDeletionPayload(value) {
  const data = value ?? {};
  if (
    typeof data !== "object" || Array.isArray(data) ||
    Object.keys(data).some((key) => !["expectedUid", "responseVersion"].includes(key))
  ) {
    throw invalid("ACCOUNT_DELETION_FIELDS");
  }
  if (data.responseVersion !== undefined && data.responseVersion !== 2) {
    throw invalid("ACCOUNT_DELETION_RESPONSE_VERSION");
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
export async function lockAccountDeletion(
  uid,
  email = null,
  { nowMillis = Date.now(), leaseToken = randomUUID() } = {},
) {
  return db.runTransaction(async (tx) => {
    const tombstoneRef = accountDeletionTombstoneRef(uid);
    const jobRef = accountDeletionJobRef(uid);
    const deletionLocks = await tx.getAll(
      tombstoneRef,
      jobRef,
    );
    const tombstone = deletionLocks[0];
    const savedJob = deletionLocks[1];
    if (savedJob.exists) {
      const job = savedJob.data();
      if (!validDeletionJob(job, jobRef.id) || !tombstone.exists) {
        throw new HttpsError("internal", "ACCOUNT_DELETION_DATA_INCONSISTENT");
      }
      if (job.nextAttemptAt.toMillis() > nowMillis) {
        return { pending: true, summary: job.summary };
      }
      // Una cuenta verificada después de iniciar un borrado no puede ampliar la autorización
      // durable para barrer invitaciones de email que no formaban parte de la solicitud.
      email = job.email;
      if (job.phase === "AUTH") {
        tx.update(jobRef, {
          leaseToken,
          nextAttemptAt: Timestamp.fromMillis(nowMillis + DELETION_LEASE_MILLIS),
        });
        return { authOnly: true, summary: job.summary, email, leaseToken };
      }
    } else if (tombstone.data()?.authDeleted === true) {
      // Un JWT todavía vigente nunca vuelve a borrar invitaciones de una cuenta nueva que
      // reutilizó el correo. El cierre terminal se puede consultar idempotentemente.
      return { completed: true, summary: completedSummary(tombstone.data()) };
    } else if (tombstone.data()?.cleanupComplete === true &&
        tombstone.data()?.deletionSummary !== undefined) {
      // Un cierre moderno perdió el job después de confirmar el barrido pero antes de Auth.
      const summary = completedSummary(tombstone.data());
      tx.create(jobRef, {
        schemaVersion: 1, uid, email: null, phase: "AUTH", summary, leaseToken,
        nextAttemptAt: Timestamp.fromMillis(nowMillis + DELETION_LEASE_MILLIS),
      });
      return { authOnly: true, summary, email: null, leaseToken };
    } else if (tombstone.exists) {
      // Los tombstones legacy de cuentas sin negocio propio podían marcar cleanupComplete
      // antes del barrido. Solo Auth ausente permite tratarlos como terminales sin volver a
      // tocar invitaciones de un correo que ya pudo reutilizarse.
      let authDeleted = false;
      try {
        await getAuth().getUser(uid);
      } catch (failure) {
        if (failure?.code !== "auth/user-not-found") throw failure;
        authDeleted = true;
      }
      if (authDeleted) {
        const summary = completedSummary(tombstone.data());
        tx.set(tombstoneRef, { authDeleted: true, deletionSummary: summary }, { merge: true });
        return { completed: true, summary };
      }
    }
    const emailLockRef = email === null ? null : accountDeletionEmailLockRef(email);
    if (emailLockRef !== null) await tx.get(emailLockRef);
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
      cleanupComplete: false,
      storageCleanupBusinessIds: businessRefs.map((businessRef) => businessRef.id),
      retiredBusinessIds: [...new Set([
        ...retiredBusinessIds,
        ...businessRefs.map((businessRef) => businessRef.id),
      ])].sort(),
      storageCleanupPending: businessRefs.length > 0,
      storageCleanupEligibleAt: Timestamp.fromMillis(
        nowMillis + STORAGE_LATE_WRITE_GRACE_MILLIS,
      ),
    });
    if (emailLockRef !== null) {
      tx.set(emailLockRef, {
        schemaVersion: 1,
        blocksInvitations: true,
        deletionJobId: jobRef.id,
        // Defensa de recuperación: si Auth se elimina pero falla el delete final de esta guarda,
        // nunca puede inmovilizar ese correo para siempre. inviteMember limpia una guarda vencida
        // dentro de la misma transacción que crea la nueva invitación.
        expiresAt: Timestamp.fromMillis(nowMillis + EMAIL_LOCK_TTL_MILLIS),
      });
    }
    for (const { businessRef, business } of checks) {
      if (business.exists) {
        tx.update(businessRef, { [ACCOUNT_DELETION_LOCK_FIELD]: true });
      }
    }
    const deletedBusinessPaths = new Set(businessRefs.map((ref) => ref.path));
    const survivingMembers = memberships.docs.filter(
      (member) => !deletedBusinessPaths.has(directBusinessRefFor(member.ref).path),
    );
    const summary = savedJob.data()?.summary ?? {
      businessesDeleted: businessRefs.map((ref) => ref.id),
      membershipsRemoved: survivingMembers.length,
    };
    // Único almacenamiento temporal de UID/email: solo Admin SDK, sin índices de identidad,
    // sin tokens ni contraseñas, eliminado atómicamente al confirmar el cierre de Auth.
    tx.set(jobRef, {
      schemaVersion: 1, uid, email, phase: "CLEANUP", summary, leaseToken,
      nextAttemptAt: Timestamp.fromMillis(nowMillis + DELETION_LEASE_MILLIS),
    });
    return {
      email,
      leaseToken,
      summary,
      soleOwnedRefs: businessRefs,
      retiredBusinessIds: [...new Set([
        ...retiredBusinessIds,
        ...businessRefs.map((businessRef) => businessRef.id),
      ])].sort(),
      survivingMembers,
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

/** Cierre recuperable compartido por la solicitud autenticada y el scheduler. */
export async function runAccountDeletion(
  uid,
  email = null,
  {
    nowMillis = Date.now(),
    recursiveDelete = (ref) => db.recursiveDelete(ref),
    deleteAuthUser = deleteAuthUserIdempotently,
  } = {},
) {
  const state = await lockAccountDeletion(uid, email, { nowMillis });
  if (state.pending) return { ...state.summary, status: "PENDING" };
  if (state.completed) return state.summary;
  const { leaseToken, summary } = state;
  email = state.email;
  try {
    if (!state.authOnly) {
      await cleanAccountData(uid, email, state, recursiveDelete);
    }
    await deleteAuthUser(uid);
    await finishAccountDeletion(uid, email, leaseToken, summary);
    return summary;
  } catch (failure) {
    await deferAccountDeletion(uid, leaseToken, nowMillis).catch(() => {});
    throw failure;
  }
}

async function cleanAccountData(uid, email, state, recursiveDelete) {
  const { soleOwnedRefs, retiredBusinessIds, survivingMembers, summary, leaseToken } = state;
  const deletedBusinessPaths = new Set(soleOwnedRefs.map((ref) => ref.path));

  // El tombstone ya impide que el UID cree referencias nuevas. Primero se retiran árboles propios:
  // así ninguna consulta collectionGroup posterior queda atascada en documentos que de todos modos
  // pertenecen a un negocio que se debe borrar completo.
  await purgeOwnedBusinessDocuments(soleOwnedRefs);
  for (const businessRef of soleOwnedRefs) {
    await recursiveDelete(businessRef);
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
  await db.runTransaction(async (completion) => {
    const job = await completion.get(accountDeletionJobRef(uid));
    if (!job.exists || job.data()?.leaseToken !== leaseToken) {
      throw new HttpsError("aborted", "ACCOUNT_DELETION_RETRY");
    }
    completion.delete(membershipQuotaCounterRef(uid));
    completion.set(accountDeletionTombstoneRef(uid), {
      schemaVersion: 1,
      blocksMutations: true,
      cleanupComplete: true,
      deletionSummary: summary,
      // Se conservan hasta que el scheduler confirme otra purga pasada la ventana máxima de
      // file.save tardío. Borrar estos IDs aquí dejaría un objeto sin control-plane recuperable.
      storageCleanupBusinessIds: soleOwnedRefs.map((ref) => ref.id),
      retiredBusinessIds,
      storageCleanupPending: soleOwnedRefs.length > 0,
      storageCleanupEligibleAt: Timestamp.fromMillis(
        Date.now() + STORAGE_LATE_WRITE_GRACE_MILLIS,
      ),
    });
    completion.update(accountDeletionJobRef(uid), { phase: "AUTH", leaseToken });
  });
}

async function finishAccountDeletion(uid, email, leaseToken, summary) {
  const jobRef = accountDeletionJobRef(uid);
  const lockRef = email === null ? null : accountDeletionEmailLockRef(email);
  await db.runTransaction(async (tx) => {
    const [job, lock] = await tx.getAll(jobRef, ...(lockRef === null ? [] : [lockRef]));
    if (!job.exists) return;
    if (job.data()?.leaseToken !== leaseToken || job.data()?.phase !== "AUTH") {
      throw new HttpsError("aborted", "ACCOUNT_DELETION_RETRY");
    }
    // El job y la PII temporal desaparecen junto a la marca terminal. Nunca borrar un lock
    // posterior perteneciente a otra solicitud que reutilizó ese email.
    if (lock?.data()?.deletionJobId === jobRef.id) tx.delete(lockRef);
    tx.set(accountDeletionTombstoneRef(uid), {
      authDeleted: true,
      deletionSummary: summary,
    }, { merge: true });
    tx.delete(jobRef);
  });
}

async function deferAccountDeletion(uid, leaseToken, nowMillis) {
  const ref = accountDeletionJobRef(uid);
  await db.runTransaction(async (tx) => {
    const snapshot = await tx.get(ref);
    if (!snapshot.exists || snapshot.data()?.leaseToken !== leaseToken) return;
    tx.update(ref, {
      nextAttemptAt: Timestamp.fromMillis(nowMillis + DELETION_RETRY_MILLIS),
    });
  });
}

export async function deleteMyAccountHandler(
  request,
  { runDeletion = runAccountDeletion } = {},
) {
  requireExpectedUid(request);
  const data = requireAccountDeletionPayload(request.data);
  const auth = requireRecentAuth(request);
  const email = auth.token.email_verified === true && typeof auth.token.email === "string"
    ? normalizeEmail(auth.token.email) : null;
  const supportsPending = data.responseVersion === 2;
  try {
    const result = await runDeletion(auth.uid, email);
    // Clientes publicados antes de v2 ignoran campos desconocidos. Para ellos un éxito debe
    // seguir significando borrado completo; jamás presentarles un PENDING como éxito legacy.
    if (result.status === "PENDING" && !supportsPending) {
      throw new HttpsError("aborted", "ACCOUNT_DELETION_PENDING");
    }
    return result;
  } catch (failure) {
    // Solo un job confirmado en Firestore significa que la solicitud fue aceptada. Un error
    // anterior al commit sigue siendo rechazo/fallo; jamás se inventa una confirmación.
    if (supportsPending) {
      const job = await accountDeletionJobRef(auth.uid).get();
      if (job.exists && validDeletionJob(job.data(), job.id)) {
        return { ...job.data().summary, status: "PENDING" };
      }
    }
    throw failure;
  }
}

export const deleteMyAccount = onCall(
  ACCOUNT_DELETION_CALLABLE_OPTIONS,
  (request) => deleteMyAccountHandler(request),
);

/** Borrados aceptados sobreviven a timeout, pérdida del ACK y cierre de la aplicación. */
export async function resumeAccountDeletionsHandler(
  _event,
  { nowMillis = Date.now(), runDeletion = runAccountDeletion } = {},
) {
  const candidates = await db.collection("accountDeletionJobs")
    .where("nextAttemptAt", "<=", Timestamp.fromMillis(nowMillis))
    .orderBy("nextAttemptAt")
    .limit(DELETION_RECOVERY_LIMIT)
    .get();
  let completed = 0;
  let deferred = 0;
  let invalid = 0;
  const startedAt = Date.now();
  for (const snapshot of candidates.docs) {
    if (Date.now() - startedAt > 240_000) break;
    const job = snapshot.data();
    let valid = false;
    try { valid = validDeletionJob(job, snapshot.id); } catch (_) { /* fail closed */ }
    if (!valid) {
      await snapshot.ref.update({
        nextAttemptAt: FieldValue.delete(),
        phase: "INVALID",
      });
      invalid += 1;
      continue;
    }
    try {
      const result = await runDeletion(job.uid, job.email, { nowMillis });
      if (result.status === "PENDING") deferred += 1;
      else completed += 1;
    } catch (_) {
      // También difiere un fallo de revalidación previo a adquirir un lease nuevo, para que
      // un tenant inconsistente no ocupe siempre las primeras veinte plazas.
      await deferAccountDeletion(job.uid, job.leaseToken, nowMillis).catch(() => {});
      deferred += 1;
    }
  }
  return { completed, deferred, invalid };
}

export const resumeAccountDeletions = onSchedule({
  region: ACCOUNT_DELETION_CALLABLE_OPTIONS.region,
  schedule: "every 5 minutes",
  maxInstances: 1,
  concurrency: 1,
  timeoutSeconds: 300,
  memory: "512MiB",
  retryCount: 3,
}, resumeAccountDeletionsHandler);
