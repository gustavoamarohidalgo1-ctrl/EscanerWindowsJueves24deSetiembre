// Callables de membresía de FacturaStock: creación de negocio, invitaciones por email y
// administración de miembros/roles. Todos exigen autenticación con email verificado y la
// autorización sale siempre del documento de membresía del servidor, nunca del cliente.
import { onCall, HttpsError } from "firebase-functions/v2/https";
import { FieldPath, FieldValue, Timestamp } from "firebase-admin/firestore";
import {
  CALLABLE_OPTIONS,
  UUID_REGEX,
  db,
  sha256,
  requireExpectedUid,
  requireVerifiedEmail,
  requireMemberRole,
  requireBusinessId,
  requireString,
  normalizeEmail,
  requireRoleCatalog,
  tokenEmail,
  requireBusinessNotDeleting,
  accountDeletionEmailLockRef,
  accountDeletionTombstoneRef,
  requireAccountNotDeleting,
  requireEmailNotDeleting,
} from "./common.js";
import {
  MAX_BUSINESSES_PER_ACCOUNT,
  adjustMembershipQuota,
  membershipQuotaCounterDocumentId,
} from "./membershipQuota.js";

const INVITATION_TTL_MILLIS = 7 * 24 * 60 * 60 * 1000; // 7 días
const DEFAULT_LIST_LIMIT = 100;
const MAX_LIST_LIMIT = 100;
const INVITATION_REPLAY_MILLIS = 60 * 1000;
const INVITATION_RATE_LIMIT_NAMESPACE = "facturastock:invitation-rate:v1:";
const INVITATION_RATE_LIMIT_RETENTION_MULTIPLIER = 2;
export const INVITATION_RATE_POLICIES = Object.freeze({
  ACTOR: Object.freeze({ limit: 30, windowMillis: 60 * 60 * 1000 }),
  BUSINESS: Object.freeze({ limit: 100, windowMillis: 24 * 60 * 60 * 1000 }),
  RECIPIENT: Object.freeze({ limit: 10, windowMillis: 24 * 60 * 60 * 1000 }),
});

const businessRef = (businessId) => db.doc(`businesses/${businessId}`);
const memberRef = (businessId, uid) => db.doc(`businesses/${businessId}/members/${uid}`);
const invitationsCol = (businessId) => db.collection(`businesses/${businessId}/invitations`);
// El documento de invitación se identifica por el hash del email normalizado del invitado.
const invitationRef = (businessId, email) =>
  db.doc(`businesses/${businessId}/invitations/${sha256(email)}`);

const isExpired = (data, nowMillis) => (data.expiresAt?.toMillis() ?? 0) <= nowMillis;

export const invitationRateLimitDocumentId = (scope, value) => {
  if (
    !Object.hasOwn(INVITATION_RATE_POLICIES, scope) ||
    typeof value !== "string" || value.length === 0
  ) {
    throw new Error("INVITATION_RATE_LIMIT_SCOPE_INVALID");
  }
  return sha256(`${INVITATION_RATE_LIMIT_NAMESPACE}${scope}:${value}`);
};

const invitationRateLimitRef = (scope, value) =>
  db.doc(`invitationRateLimits/${invitationRateLimitDocumentId(scope, value)}`);

function requireListLimit(value) {
  const limit = value ?? DEFAULT_LIST_LIMIT;
  if (!Number.isInteger(limit) || limit < 1 || limit > MAX_LIST_LIMIT) {
    throw new HttpsError("invalid-argument", "LIST_LIMIT");
  }
  return limit;
}

function requireOptionalCursor(value, validator) {
  if (value === undefined || value === null) return null;
  if (typeof value !== "string" || value.length === 0 || value.length > 1024 || !validator(value)) {
    throw new HttpsError("invalid-argument", "LIST_CURSOR");
  }
  return value;
}

function paginationData(raw, allowedKeys, cursorValidator) {
  const data = raw ?? {};
  if (!data || typeof data !== "object" || Array.isArray(data)) {
    throw new HttpsError("invalid-argument", "LIST_FIELDS");
  }
  if (Object.keys(data).some((key) => !allowedKeys.has(key))) {
    throw new HttpsError("invalid-argument", "LIST_FIELDS");
  }
  return {
    limit: requireListLimit(data.limit),
    cursor: requireOptionalCursor(data.cursor, cursorValidator),
  };
}

function boundedPage(snapshot, limit, cursorForDocument) {
  const hasMore = snapshot.size > limit;
  const documents = snapshot.docs.slice(0, limit);
  return {
    documents,
    hasMore,
    nextCursor: hasMore && documents.length > 0
      ? cursorForDocument(documents.at(-1))
      : null,
  };
}

function membershipCursor(uid, value) {
  const parts = value.split("/");
  return parts.length === 4 && parts[0] === "businesses" && UUID_REGEX.test(parts[1]) &&
    parts[2] === "members" && parts[3] === uid;
}

function myInvitationCursor(email, value) {
  const parts = value.split("/");
  return parts.length === 4 && parts[0] === "businesses" && UUID_REGEX.test(parts[1]) &&
    parts[2] === "invitations" && parts[3] === sha256(email);
}

function collectionDocumentCursor(value) {
  return !value.includes("/") && value.length <= 128;
}

function isExactPendingReplay(invitation, { email, role, uid, nowMillis }) {
  if (!invitation.exists) return false;
  const data = invitation.data();
  const createdAtMillis = data.createdAt?.toMillis?.();
  return data.status === "PENDING" && data.email === email && data.role === role &&
    data.invitedBy === uid && !isExpired(data, nowMillis) &&
    Number.isSafeInteger(createdAtMillis) && createdAtMillis <= nowMillis &&
    nowMillis - createdAtMillis <= INVITATION_REPLAY_MILLIS;
}

function nextInvitationRateState(snapshot, scope, policy, nowMillis) {
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
      throw new HttpsError("data-loss", "INVITATION_RATE_LIMIT_INVALID");
    }
    if (nowMillis - savedWindow < policy.windowMillis) {
      windowStartedAtMillis = savedWindow;
      count = data.count;
    }
  }
  if (count >= policy.limit) {
    throw new HttpsError("resource-exhausted", "INVITATION_RATE_LIMIT", {
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
        policy.windowMillis * INVITATION_RATE_LIMIT_RETENTION_MULTIPLIER,
    ),
  };
}

// Crea el negocio y registra al caller como OWNER. Si el negocio ya existe, BUSINESS_EXISTS.
export const createBusiness = onCall(CALLABLE_OPTIONS, async (request) => {
  requireExpectedUid(request);
  requireVerifiedEmail(request);
  const data = request.data ?? {};
  const businessId = requireBusinessId(data.businessId);
  const displayName = requireString(data.displayName, "DISPLAY_NAME", 120);
  const uid = request.auth.uid;
  const email = tokenEmail(request);
  const selfMemberRef = memberRef(businessId, uid);

  const created = await db.runTransaction(async (tx) => {
    const [existing, tombstone, orphanMember] = await tx.getAll(
      businessRef(businessId),
      accountDeletionTombstoneRef(uid),
      selfMemberRef,
    );
    requireAccountNotDeleting(tombstone);
    if (existing.exists) throw new HttpsError("failed-precondition", "BUSINESS_EXISTS");
    // El mismo UUID no puede adquirir una generacion nueva mientras cualquier borrado anterior
    // conserve su prefijo Storage. Esta query y la limpieza del marker participan en
    // transacciones: create-vs-sweep entra en conflicto y se reevalua despues del delete.
    const [retiredGeneration, pendingStorageCleanup, legacyStorageCleanup] = await Promise.all([
      tx.get(
        db.collection("accountDeletionTombstones")
          .where("retiredBusinessIds", "array-contains", businessId)
          .limit(1),
      ),
      tx.get(
        db.collection("accountDeletionTombstones")
          .where("storageCleanupBusinessIds", "array-contains", businessId)
          .limit(1),
      ),
      // Compatibilidad con tombstones creados por versiones anteriores al retiro permanente.
      tx.get(
        db.collection("accountDeletionTombstones")
          .where("soleOwnedBusinessIds", "array-contains", businessId)
          .limit(1),
      ),
    ]);
    if (!retiredGeneration.empty || !pendingStorageCleanup.empty ||
        !legacyStorageCleanup.empty) {
      throw new HttpsError("failed-precondition", "BUSINESS_ID_PENDING_STORAGE_CLEANUP");
    }
    if (orphanMember.exists) {
      throw new HttpsError("data-loss", "BUSINESS_MEMBERSHIP_ORPHANED");
    }
    const quota = await adjustMembershipQuota(tx, uid, 1);
    // Para formato legacy lleno, adjustMembershipQuota confirma solo el baseline reconstruido.
    // El error se emite después del commit para no perder esa reconciliación durable.
    if (!quota.allowed) return false;
    tx.set(businessRef(businessId), {
      businessId,
      displayName,
      createdBy: uid,
      createdAt: FieldValue.serverTimestamp(),
    });
    tx.set(selfMemberRef, {
      uid,
      email,
      role: "OWNER",
      addedAt: FieldValue.serverTimestamp(),
      addedVia: "create",
    });
    return true;
  });
  if (!created) throw new HttpsError("resource-exhausted", "BUSINESS_QUOTA");
  return { businessId, role: "OWNER" };
});

// Membresías del caller: cursor estable por path y presentación por nombre dentro de cada página.
export const listMyMemberships = onCall(CALLABLE_OPTIONS, async (request) => {
  requireExpectedUid(request);
  requireVerifiedEmail(request);
  const uid = request.auth.uid;
  const { limit, cursor } = paginationData(
    request.data,
    new Set(["cursor", "limit", "expectedUid"]),
    (value) => membershipCursor(uid, value),
  );
  return db.runTransaction(async (tx) => {
    const tombstone = await tx.get(accountDeletionTombstoneRef(uid));
    requireAccountNotDeleting(tombstone);
    let query = db.collectionGroup("members")
      .where("uid", "==", uid)
      .orderBy(FieldPath.documentId())
      .limit(limit + 1);
    if (cursor !== null) query = query.startAfter(cursor);
    const snapshot = await tx.get(query);
    const page = boundedPage(snapshot, limit, (member) => member.ref.path);
    const businessSnapshots =
      page.documents.length === 0
        ? []
        : await tx.getAll(...page.documents.map((member) => member.ref.parent.parent));
    const memberships = page.documents.map((member, index) => ({
      businessId: member.ref.parent.parent.id,
      displayName: businessSnapshots[index].data()?.displayName ?? null,
      role: member.data().role,
    }));
    memberships.sort((a, b) => (a.displayName ?? "").localeCompare(b.displayName ?? ""));
    return { memberships, nextCursor: page.nextCursor, hasMore: page.hasMore };
  });
});

// Invita por email con un rol. OWNER/ADMIN invitan; ADMIN no puede invitar con rol OWNER.
// Re-invitación sobre PENDING (o DECLINED) renueva rol y expiración.
export const inviteMember = onCall(CALLABLE_OPTIONS, async (request) => {
  requireExpectedUid(request);
  requireVerifiedEmail(request);
  const data = request.data ?? {};
  const businessId = requireBusinessId(data.businessId);
  const email = normalizeEmail(data.email);
  const role = requireRoleCatalog(data.role);
  const targetInvitationRef = invitationRef(businessId, email);
  const nowMillis = Date.now();
  const rateLimits = [
    {
      scope: "ACTOR",
      ref: invitationRateLimitRef("ACTOR", request.auth.uid),
      policy: INVITATION_RATE_POLICIES.ACTOR,
    },
    {
      scope: "BUSINESS",
      ref: invitationRateLimitRef("BUSINESS", businessId),
      policy: INVITATION_RATE_POLICIES.BUSINESS,
    },
    {
      scope: "RECIPIENT",
      ref: invitationRateLimitRef("RECIPIENT", email),
      policy: INVITATION_RATE_POLICIES.RECIPIENT,
    },
  ];

  const outcome = await db.runTransaction(async (tx) => {
    const [
      business,
      tombstone,
      callerMember,
      existingInvitation,
      deletedEmail,
    ] = await tx.getAll(
      businessRef(businessId),
      accountDeletionTombstoneRef(request.auth.uid),
      memberRef(businessId, request.auth.uid),
      targetInvitationRef,
      accountDeletionEmailLockRef(email),
    );
    requireAccountNotDeleting(tombstone);
    if (!callerMember.exists) {
      throw new HttpsError("permission-denied", "NOT_A_MEMBER");
    }
    requireBusinessNotDeleting(business);
    const callerRole = requireMemberRole(callerMember, ["OWNER", "ADMIN"]);
    if (callerRole === "ADMIN" && role === "OWNER") {
      throw new HttpsError("permission-denied", "ROLE_FORBIDDEN");
    }
    // Solo una persona ya autorizada conoce si el destinatario está en borrado; evaluarlo antes
    // de membresía permitiría usar businessIds ajenos como oráculo del email-lock.
    const expiredEmailLock = requireEmailNotDeleting(deletedEmail, nowMillis);
    const matchingMembers = await tx.get(
      db.collection(`businesses/${businessId}/members`).where("email", "==", email).limit(2),
    );
    if (matchingMembers.size > 1) {
      throw new HttpsError("data-loss", "MEMBERSHIP_EMAIL_DUPLICATED");
    }
    const matchingMember = matchingMembers.docs[0] ?? null;
    if (
      matchingMember !== null &&
      (matchingMember.id !== matchingMember.data()?.uid ||
        typeof matchingMember.data()?.role !== "string")
    ) {
      throw new HttpsError("data-loss", "MEMBERSHIP_EMAIL_INVALID");
    }
    let acceptedMember = null;
    let invitationStatus = null;
    if (existingInvitation.exists) {
      const invitation = existingInvitation.data();
      invitationStatus = invitation.status;
      if (invitation.email !== email) {
        throw new HttpsError("data-loss", "INVITATION_EMAIL_MISMATCH");
      }
      if (invitationStatus === "ACCEPTED") {
        const acceptedBy = invitation.acceptedBy;
        if (typeof acceptedBy !== "string" || acceptedBy.length === 0 || acceptedBy.length > 128) {
          throw new HttpsError("data-loss", "INVITATION_ACCEPTED_BY_INVALID");
        }
        acceptedMember = matchingMember?.id === acceptedBy
          ? matchingMember
          : await tx.get(memberRef(businessId, acceptedBy));
      }
      if (!["PENDING", "DECLINED", "ACCEPTED", "CANCELLED"].includes(invitationStatus)) {
        throw new HttpsError("failed-precondition", "INVITATION_STATE_INVALID");
      }
    }
    if (matchingMember !== null || acceptedMember?.exists === true) {
      // TTL retira también invitaciones ACCEPTED. Sin esta consulta, un ADMIN antiguo podría
      // crearse una PENDING a sí mismo, conservarla tras ser revocado y reingresar. Si ya existe
      // una PENDING redundante, se consume en el mismo commit antes de devolver el error estable.
      if (invitationStatus === "PENDING") {
        tx.update(targetInvitationRef, {
          status: "CANCELLED",
          cancellationReason: "ALREADY_MEMBER",
          cancelledAt: FieldValue.serverTimestamp(),
          cancelledBy: request.auth.uid,
        });
      }
      if (expiredEmailLock) tx.delete(deletedEmail.ref);
      return "ALREADY_MEMBER";
    }
    // Un retry de transporte idéntico durante un minuto es ACK-idempotente: no renueva la
    // invitación ni consume tres contadores otra vez. Una re-invitación explícita posterior,
    // con otro rol/actor o tras el cooldown conserva la semántica histórica y sí consume cuota.
    if (isExactPendingReplay(existingInvitation, {
      email,
      role,
      uid: request.auth.uid,
      nowMillis,
    })) {
      if (expiredEmailLock) tx.delete(deletedEmail.ref);
      return "OK";
    }

    const rateSnapshots = await tx.getAll(...rateLimits.map(({ ref }) => ref));
    rateLimits.forEach(({ scope, ref, policy }, index) => {
      tx.set(ref, nextInvitationRateState(rateSnapshots[index], scope, policy, nowMillis));
    });
    if (expiredEmailLock) tx.delete(deletedEmail.ref);
    tx.set(targetInvitationRef, {
      email,
      role,
      status: "PENDING",
      invitedBy: request.auth.uid,
      businessId,
      businessDisplayName: business.data()?.displayName ?? null,
      createdAt: Timestamp.fromMillis(nowMillis),
      expiresAt: Timestamp.fromMillis(nowMillis + INVITATION_TTL_MILLIS),
    });
    return "OK";
  });
  if (outcome === "ALREADY_MEMBER") {
    throw new HttpsError("already-exists", "INVITATION_ALREADY_ACCEPTED");
  }
  return { ok: true };
});

// Invitaciones PENDING no expiradas de un negocio; solo OWNER/ADMIN las listan.
export const listBusinessInvitations = onCall(CALLABLE_OPTIONS, async (request) => {
  requireExpectedUid(request);
  requireVerifiedEmail(request);
  const data = request.data ?? {};
  const businessId = requireBusinessId(data.businessId);
  const { limit, cursor } = paginationData(
    data,
    new Set(["businessId", "cursor", "limit", "expectedUid"]),
    collectionDocumentCursor,
  );
  const now = Date.now();
  return db.runTransaction(async (tx) => {
    const [tombstone, callerMember] = await tx.getAll(
      accountDeletionTombstoneRef(request.auth.uid),
      memberRef(businessId, request.auth.uid),
    );
    requireAccountNotDeleting(tombstone);
    if (!callerMember.exists) {
      throw new HttpsError("permission-denied", "NOT_A_MEMBER");
    }
    requireMemberRole(callerMember, ["OWNER", "ADMIN"]);
    let query = invitationsCol(businessId)
      .where("status", "==", "PENDING")
      .orderBy(FieldPath.documentId())
      .limit(limit + 1);
    if (cursor !== null) query = query.startAfter(cursor);
    const snapshot = await tx.get(query);
    const page = boundedPage(snapshot, limit, (invitation) => invitation.id);
    const invitations = page.documents
      .map((doc) => doc.data())
      .filter((data) => !isExpired(data, now))
      .map((data) => ({
        email: data.email,
        role: data.role,
        status: data.status,
        expiresAtMillis: data.expiresAt.toMillis(),
      }));
    return { invitations, nextCursor: page.nextCursor, hasMore: page.hasMore };
  });
});

// Miembros del negocio; los lee cualquier miembro.
export const listMembers = onCall(CALLABLE_OPTIONS, async (request) => {
  requireExpectedUid(request);
  requireVerifiedEmail(request);
  const data = request.data ?? {};
  const businessId = requireBusinessId(data.businessId);
  const { limit, cursor } = paginationData(
    data,
    new Set(["businessId", "cursor", "limit", "expectedUid"]),
    collectionDocumentCursor,
  );
  return db.runTransaction(async (tx) => {
    const [tombstone, callerMember] = await tx.getAll(
      accountDeletionTombstoneRef(request.auth.uid),
      memberRef(businessId, request.auth.uid),
    );
    requireAccountNotDeleting(tombstone);
    if (!callerMember.exists) {
      throw new HttpsError("permission-denied", "NOT_A_MEMBER");
    }
    let query = db.collection(`businesses/${businessId}/members`)
      .orderBy(FieldPath.documentId())
      .limit(limit + 1);
    if (cursor !== null) query = query.startAfter(cursor);
    const snapshot = await tx.get(query);
    const page = boundedPage(snapshot, limit, (member) => member.id);
    const members = page.documents.map((doc) => ({
      uid: doc.id,
      email: doc.data().email ?? null,
      role: doc.data().role,
    }));
    return { members, nextCursor: page.nextCursor, hasMore: page.hasMore };
  });
});

// Invitaciones PENDING no expiradas dirigidas al email del caller, en todos los negocios.
export const listMyInvitations = onCall(CALLABLE_OPTIONS, async (request) => {
  requireExpectedUid(request);
  requireVerifiedEmail(request);
  const email = tokenEmail(request);
  const { limit, cursor } = paginationData(
    request.data,
    new Set(["cursor", "limit", "expectedUid"]),
    (value) => myInvitationCursor(email, value),
  );

  const now = Date.now();
  return db.runTransaction(async (tx) => {
    const tombstone = await tx.get(accountDeletionTombstoneRef(request.auth.uid));
    requireAccountNotDeleting(tombstone);
    let query = db.collectionGroup("invitations")
      .where("email", "==", email)
      .orderBy(FieldPath.documentId())
      .limit(limit + 1);
    if (cursor !== null) query = query.startAfter(cursor);
    const snapshot = await tx.get(query);
    const page = boundedPage(snapshot, limit, (invitation) => invitation.ref.path);
    const current = page.documents.filter((doc) => {
      const data = doc.data();
      return data.status === "PENDING" && !isExpired(data, now);
    });
    const businesses =
      current.length === 0
        ? []
        : await tx.getAll(...current.map((doc) => doc.ref.parent.parent));
    return {
      invitations: current.map((doc, index) => ({
        businessId: doc.ref.parent.parent.id,
        displayName: businesses[index].data()?.displayName ?? null,
        role: doc.data().role,
        expiresAtMillis: doc.data().expiresAt.toMillis(),
      })),
      nextCursor: page.nextCursor,
      hasMore: page.hasMore,
    };
  });
});

// Acepta la invitación del caller: crea la membresía con el rol invitado y marca la
// invitación ACCEPTED. Si ya es miembro es idempotente: devuelve su rol actual sin error.
export const acceptInvitation = onCall(CALLABLE_OPTIONS, async (request) => {
  requireExpectedUid(request);
  requireVerifiedEmail(request);
  const businessId = requireBusinessId(request.data?.businessId);
  const uid = request.auth.uid;
  const email = tokenEmail(request);
  const selfMemberRef = memberRef(businessId, uid);
  const selfInvitationRef = invitationRef(businessId, email);

  const outcome = await db.runTransaction(async (tx) => {
    const [member, invitation, business, tombstone] = await tx.getAll(
      selfMemberRef,
      selfInvitationRef,
      businessRef(businessId),
      accountDeletionTombstoneRef(uid),
    );
    requireAccountNotDeleting(tombstone);
    requireBusinessNotDeleting(business);
    if (member.exists) {
      // Aceptar es idempotente si la membresía ya existe, pero no debe dejar viva una PENDING
      // redundante que pudiera reutilizarse después de una revocación.
      let redundantPending = false;
      if (invitation.exists) {
        const redundantInvitation = invitation.data();
        if (redundantInvitation.email !== email) {
          throw new HttpsError("data-loss", "INVITATION_EMAIL_MISMATCH");
        }
        redundantPending = redundantInvitation.status === "PENDING";
      }
      await adjustMembershipQuota(tx, uid, 0);
      if (redundantPending) {
        tx.update(selfInvitationRef, {
          status: "CANCELLED",
          cancellationReason: "ALREADY_MEMBER",
          cancelledAt: FieldValue.serverTimestamp(),
          cancelledBy: uid,
        });
      }
      return { kind: "RECORDED", role: member.data().role };
    }
    if (!invitation.exists) throw new HttpsError("not-found", "INVITATION_NOT_FOUND");
    const data = invitation.data();
    if (data.email !== email) {
      throw new HttpsError("failed-precondition", "INVITATION_EMAIL_MISMATCH");
    }
    if (data.status !== "PENDING") {
      throw new HttpsError("failed-precondition", "INVITATION_NOT_PENDING");
    }
    if (isExpired(data, Date.now())) {
      throw new HttpsError("failed-precondition", "INVITATION_EXPIRED");
    }
    const quota = await adjustMembershipQuota(tx, uid, 1);
    if (!quota.allowed) return { kind: "QUOTA" };
    tx.set(selfMemberRef, {
      uid,
      email,
      role: data.role,
      addedAt: FieldValue.serverTimestamp(),
      addedVia: "invitation",
    });
    tx.update(selfInvitationRef, {
      status: "ACCEPTED",
      acceptedAt: FieldValue.serverTimestamp(),
      acceptedBy: uid,
    });
    return { kind: "RECORDED", role: data.role };
  });
  if (outcome.kind === "QUOTA") {
    throw new HttpsError("resource-exhausted", "BUSINESS_QUOTA");
  }
  return { businessId, role: outcome.role };
});

// Rechaza la invitación del caller: la marca DECLINED y no crea membresía.
export const declineInvitation = onCall(CALLABLE_OPTIONS, async (request) => {
  requireExpectedUid(request);
  requireVerifiedEmail(request);
  const businessId = requireBusinessId(request.data?.businessId);
  const email = tokenEmail(request);
  const selfInvitationRef = invitationRef(businessId, email);

  return db.runTransaction(async (tx) => {
    const [invitation, business, tombstone] = await tx.getAll(
      selfInvitationRef,
      businessRef(businessId),
      accountDeletionTombstoneRef(request.auth.uid),
    );
    requireAccountNotDeleting(tombstone);
    requireBusinessNotDeleting(business);
    if (!invitation.exists) throw new HttpsError("not-found", "INVITATION_NOT_FOUND");
    const data = invitation.data();
    if (data.email !== email) {
      throw new HttpsError("failed-precondition", "INVITATION_EMAIL_MISMATCH");
    }
    if (data.status !== "PENDING") {
      throw new HttpsError("failed-precondition", "INVITATION_NOT_PENDING");
    }
    if (isExpired(data, Date.now())) {
      throw new HttpsError("failed-precondition", "INVITATION_EXPIRED");
    }
    tx.update(selfInvitationRef, {
      status: "DECLINED",
      declinedAt: FieldValue.serverTimestamp(),
      declinedBy: request.auth.uid,
    });
    return { ok: true };
  });
});

// Cambia el rol de un miembro. Solo OWNER/ADMIN; un OWNER solo lo toca otro OWNER; ADMIN
// no puede asignar OWNER; el último OWNER no puede ser degradado (LAST_OWNER_REQUIRED).
export const changeMemberRole = onCall(CALLABLE_OPTIONS, async (request) => {
  requireExpectedUid(request);
  requireVerifiedEmail(request);
  const data = request.data ?? {};
  const businessId = requireBusinessId(data.businessId);
  const targetUid = requireString(data.uid, "TARGET_UID", 128);
  const newRole = requireRoleCatalog(data.role);

  const callerRef = memberRef(businessId, request.auth.uid);
  const targetRef = memberRef(businessId, targetUid);
  await db.runTransaction(async (tx) => {
    const [business, tombstone, callerMember] = await tx.getAll(
      businessRef(businessId),
      accountDeletionTombstoneRef(request.auth.uid),
      callerRef,
    );
    requireAccountNotDeleting(tombstone);
    requireBusinessNotDeleting(business);
    if (!callerMember.exists) {
      throw new HttpsError("permission-denied", "NOT_A_MEMBER");
    }
    const callerRole = requireMemberRole(callerMember, ["OWNER", "ADMIN"]);
    if (newRole === "OWNER" && callerRole !== "OWNER") {
      throw new HttpsError("permission-denied", "ROLE_FORBIDDEN");
    }
    const targetTombstone = targetUid === request.auth.uid
      ? tombstone
      : await tx.get(accountDeletionTombstoneRef(targetUid));
    requireAccountNotDeleting(targetTombstone);
    const target = targetUid === request.auth.uid ? callerMember : await tx.get(targetRef);
    if (!target.exists) throw new HttpsError("not-found", "MEMBER_NOT_FOUND");
    const targetRole = target.data().role;
    if (targetRole === "OWNER" && callerRole !== "OWNER") {
      throw new HttpsError("permission-denied", "ROLE_FORBIDDEN");
    }
    if (targetRole === "OWNER" && newRole !== "OWNER") {
      const owners = await tx.get(
        db.collection(`businesses/${businessId}/members`)
          .where("role", "==", "OWNER")
          .limit(2),
      );
      if (owners.size <= 1) throw new HttpsError("failed-precondition", "LAST_OWNER_REQUIRED");
    }
    tx.update(targetRef, {
      role: newRole,
      roleUpdatedAt: FieldValue.serverTimestamp(),
      roleUpdatedBy: request.auth.uid,
    });
  });
  return { ok: true };
});

// Elimina a un miembro (o abandona, si el caller es el propio uid). ADMIN no puede
// eliminar a un OWNER; el último OWNER no puede salir ni ser eliminado.
export const removeMember = onCall(CALLABLE_OPTIONS, async (request) => {
  requireExpectedUid(request);
  requireVerifiedEmail(request);
  const data = request.data ?? {};
  const businessId = requireBusinessId(data.businessId);
  const targetUid = requireString(data.uid, "TARGET_UID", 128);

  const isSelf = request.auth.uid === targetUid;
  const callerRef = memberRef(businessId, request.auth.uid);
  const targetRef = memberRef(businessId, targetUid);
  await db.runTransaction(async (tx) => {
    const [business, tombstone, callerMember] = await tx.getAll(
      businessRef(businessId),
      accountDeletionTombstoneRef(request.auth.uid),
      callerRef,
    );
    requireAccountNotDeleting(tombstone);
    requireBusinessNotDeleting(business);
    if (!callerMember.exists) {
      throw new HttpsError("permission-denied", "NOT_A_MEMBER");
    }
    const callerRole = callerMember.data().role;
    if (!isSelf && !["OWNER", "ADMIN"].includes(callerRole)) {
      throw new HttpsError("permission-denied", "ROLE_FORBIDDEN");
    }
    const targetTombstone = isSelf
      ? tombstone
      : await tx.get(accountDeletionTombstoneRef(targetUid));
    requireAccountNotDeleting(targetTombstone);
    const target = isSelf ? callerMember : await tx.get(targetRef);
    if (!target.exists) throw new HttpsError("not-found", "MEMBER_NOT_FOUND");
    // Un documento PENDING dirigido al mismo email solo puede ser residuo legacy o una
    // reinvitación concurrente defectuosa: si sobreviviere a la baja permitiría reingresar sin
    // una nueva decisión del administrador. Lo consumimos dentro de la misma transacción.
    let redundantInvitationRef = null;
    let redundantInvitation = null;
    const targetEmail = target.data().email;
    if (typeof targetEmail === "string") {
      let normalizedTargetEmail;
      try {
        normalizedTargetEmail = normalizeEmail(targetEmail);
      } catch {
        throw new HttpsError("data-loss", "MEMBER_EMAIL_INVALID");
      }
      redundantInvitationRef = invitationRef(businessId, normalizedTargetEmail);
      redundantInvitation = await tx.get(redundantInvitationRef);
      if (
        redundantInvitation.exists &&
        redundantInvitation.data().status === "PENDING" &&
        redundantInvitation.data().email !== normalizedTargetEmail
      ) {
        throw new HttpsError("data-loss", "INVITATION_EMAIL_MISMATCH");
      }
    }
    if (target.data().role === "OWNER") {
      if (!isSelf && callerRole !== "OWNER") {
        throw new HttpsError("permission-denied", "ROLE_FORBIDDEN");
      }
      const owners = await tx.get(
        db.collection(`businesses/${businessId}/members`)
          .where("role", "==", "OWNER")
          .limit(2),
      );
      if (owners.size <= 1) throw new HttpsError("failed-precondition", "LAST_OWNER_REQUIRED");
    }
    await adjustMembershipQuota(tx, targetUid, -1);
    if (redundantInvitation?.data().status === "PENDING") {
      tx.update(redundantInvitationRef, {
        status: "CANCELLED",
        cancellationReason: "MEMBER_REMOVED",
        cancelledAt: FieldValue.serverTimestamp(),
        cancelledBy: request.auth.uid,
      });
    }
    tx.delete(targetRef);
  });
  return { ok: true };
});

export { MAX_BUSINESSES_PER_ACCOUNT, membershipQuotaCounterDocumentId };
