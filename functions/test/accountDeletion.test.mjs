// Integración del callable `deleteMyAccount` contra el Emulator Suite (proyecto
// demo-facturastock). Se ejecuta con:
//   npx firebase emulators:exec --only auth,firestore,functions,storage "npm test"
// Mismos helpers de cuentas verificadas que membership.test.mjs (emails con prefijo ad-).
import { test, after } from "node:test";
import assert from "node:assert/strict";
import { initializeApp as initializeAdminApp, deleteApp } from "firebase-admin/app";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
import { getAuth } from "firebase-admin/auth";
import { randomUUID, createHash } from "node:crypto";

process.env.FIRESTORE_EMULATOR_HOST ??= "localhost:8080";
process.env.FIREBASE_AUTH_EMULATOR_HOST ??= "localhost:9099";
process.env.GCLOUD_PROJECT ??= "demo-facturastock";

const PROJECT = process.env.GCLOUD_PROJECT;
const FUNCTIONS_URL = `http://localhost:5001/${PROJECT}/us-central1`;
const AUTH_URL = "http://localhost:9099/identitytoolkit.googleapis.com/v1";
const PASSWORD = "clave-demo-123";

const adminApp = initializeAdminApp({ projectId: PROJECT }, "account-deletion-test");
const db = getFirestore(adminApp);
const adminAuth = getAuth(adminApp);

const sha256 = (text) => createHash("sha256").update(text, "utf8").digest("hex");

// Negocios y usuarios creados durante la corrida; se borran al final (los ya eliminados
// por el propio callable se ignoran).
const createdBusinesses = new Set();
const createdUsers = new Set();

async function authRest(path, payload) {
  const response = await fetch(`${AUTH_URL}/${path}?key=demo-api-key`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
  const body = await response.json();
  assert.ok(body.idToken, `${path} falló: ${JSON.stringify(body)}`);
  return body;
}

async function verifiedEmailToken() {
  const email = `ad-${randomUUID()}@example.test`;
  const { localId } = await authRest("accounts:signUp", {
    email,
    password: PASSWORD,
    returnSecureToken: true,
  });
  createdUsers.add(localId);
  await adminAuth.updateUser(localId, { emailVerified: true });
  const { idToken, refreshToken } = await authRest("accounts:signInWithPassword", {
    email,
    password: PASSWORD,
    returnSecureToken: true,
  });
  return { idToken, refreshToken, localId, email };
}

async function unverifiedEmailToken() {
  const email = `ad-${randomUUID()}@example.test`;
  const { idToken, refreshToken, localId } = await authRest("accounts:signUp", {
    email,
    password: PASSWORD,
    returnSecureToken: true,
  });
  createdUsers.add(localId);
  return { idToken, refreshToken, localId, email };
}

async function callCallable(name, data, token) {
  const response = await fetch(`${FUNCTIONS_URL}/${name}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify({ data }),
  });
  return { httpStatus: response.status, body: await response.json() };
}

// Crea un negocio vía callable (el caller queda como OWNER) y lo registra para limpieza.
async function createBusinessAs(user, displayName) {
  const businessId = randomUUID();
  const { body } = await callCallable(
    "createBusiness",
    { businessId, displayName },
    user.idToken,
  );
  assert.equal(body.error, undefined, JSON.stringify(body));
  createdBusinesses.add(businessId);
  return businessId;
}

function minimalPurchaseRequest(businessId) {
  const purchaseId = randomUUID();
  const purchaseLineId = randomUUID();
  const productId = randomUUID();
  const postedAt = 1_787_500_000_000;
  const idempotencyKey = `sync-purchase:v1:${purchaseId}`;
  return {
    businessId,
    idempotencyKey,
    operationType: "SYNC_PURCHASE",
    payloadVersion: 2,
    document: {
      version: 1,
      purchaseId,
      businessId,
      status: "POSTED",
      documentType: "INVOICE",
      documentSeries: "F001",
      documentNumber: purchaseId.replaceAll("-", "").slice(-8),
      issueDate: "2026-08-20",
      currency: "PEN",
      supplierRuc: "20123456789",
      supplierLegalName: "PROVEEDOR PRUEBA SAC",
      subtotalMinorUnits: 100,
      taxMinorUnits: 0,
      otherChargesMinorUnits: 0,
      totalMinorUnits: 100,
      adjustmentMinorUnits: null,
      adjustmentReason: null,
      preparedLogicalHash: "a".repeat(64),
      postedAt,
      idempotencyKey,
      lines: [
        {
          purchaseLineId,
          position: 0,
          productId,
          productName: "PRODUCTO PRUEBA",
          unitCode: "NIU",
          description: "PRODUCTO PRUEBA",
          quantity: "1",
          readUnitCost: "1.00",
          taxMinorUnits: 0,
          totalMinorUnits: 100,
          appliedUnitCost: "1.00",
          inventoryQuantity: "1",
          discount: null,
        },
      ],
      movements: [
        {
          movementId: randomUUID(),
          purchaseLineId,
          productId,
          locationId: randomUUID(),
          type: "PURCHASE",
          quantityDelta: "1",
          unitCost: "1.00",
          occurredAt: postedAt,
        },
      ],
      auditEventIds: [randomUUID()],
    },
  };
}

// Siembra directa con Admin SDK para montar escenarios sin pasar por las invitaciones.
async function seedMember(businessId, uid, role) {
  await db.doc(`businesses/${businessId}/members/${uid}`).set({
    uid,
    email: null,
    role,
  });
}

async function seedInvitation(businessId, email, status) {
  await db.doc(`businesses/${businessId}/invitations/${sha256(email)}`).set({
    email,
    role: "READER",
    status,
    invitedBy: "seed",
    businessId,
  });
}

async function seedInBatches(documents, batchSize = 400) {
  for (let offset = 0; offset < documents.length; offset += batchSize) {
    const batch = db.batch();
    for (const { ref, data } of documents.slice(offset, offset + batchSize)) {
      batch.set(ref, data);
    }
    await batch.commit();
  }
}

async function userExists(uid) {
  try {
    await adminAuth.getUser(uid);
    return true;
  } catch (error) {
    if (error.code === "auth/user-not-found") return false;
    throw error;
  }
}

async function clearBusiness(businessId) {
  await db.recursiveDelete(db.doc(`businesses/${businessId}`));
}

after(async () => {
  for (const businessId of createdBusinesses) await clearBusiness(businessId);
  for (const uid of createdUsers) {
    await adminAuth.deleteUser(uid).catch(() => {});
  }
  await deleteApp(adminApp);
});

test("expectedUid frena el borrado antes del tombstone, datos y Auth", async () => {
  const owner = await verifiedEmailToken();
  const otherAccount = await verifiedEmailToken();
  const businessId = await createBusinessAs(owner, "Negocio que debe sobrevivir");
  const { accountDeletionTombstoneRef } = await import("../common.js");

  const unknownField = await callCallable(
    "deleteMyAccount",
    { unexpected: true },
    owner.idToken,
  );
  assert.equal(unknownField.body.error?.status, "INVALID_ARGUMENT");
  assert.equal(unknownField.body.error?.message, "ACCOUNT_DELETION_FIELDS");

  const { body } = await callCallable(
    "deleteMyAccount",
    { expectedUid: otherAccount.localId },
    owner.idToken,
  );
  assert.equal(body.error?.status, "UNAUTHENTICATED", JSON.stringify(body));
  assert.equal(body.error?.message, "AUTH_IDENTITY_CHANGED");
  assert.equal(await userExists(owner.localId), true);
  assert.equal((await db.doc(`businesses/${businessId}`).get()).exists, true);
  assert.equal(
    (await db.doc(`businesses/${businessId}/members/${owner.localId}`).get()).exists,
    true,
  );
  assert.equal((await accountDeletionTombstoneRef(owner.localId).get()).exists, false);
});

test("OWNER único miembro: borra el árbol completo del negocio y su usuario de Auth", async () => {
  const owner = await verifiedEmailToken();
  const businessId = await createBusinessAs(owner, "Negocio Unipersonal");
  const { membershipQuotaCounterDocumentId } = await import("../membershipQuota.js");
  const membershipQuotaRef = db.doc(
    `membershipQuotaCounters/${membershipQuotaCounterDocumentId(owner.localId)}`,
  );
  const quotaBefore = await membershipQuotaRef.get();
  assert.equal(quotaBefore.exists, true);
  assert.equal(quotaBefore.data().count, 1);

  // Árbol representativo: compra con líneas, movimientos, claves, índice, sync e invitación.
  const purchaseId = randomUUID();
  await db.doc(`businesses/${businessId}/purchases/${purchaseId}`).set({ seq: 1 });
  await db
    .doc(`businesses/${businessId}/purchases/${purchaseId}/lines/${randomUUID()}`)
    .set({ position: 0 });
  await db.doc(`businesses/${businessId}/stockMovements/${randomUUID()}`).set({ seq: 1 });
  await db.doc(`businesses/${businessId}/syncKeys/clave-demo`).set({ purchaseId });
  await db.doc(`businesses/${businessId}/documentIndex/hash-demo`).set({ purchaseId });
  await db.doc(`businesses/${businessId}/sync/metadata`).set({ seq: 1 });
  await seedInvitation(businessId, `ad-otro-${randomUUID()}@example.test`, "PENDING");

  const { body } = await callCallable("deleteMyAccount", {}, owner.idToken);
  assert.equal(body.error, undefined, JSON.stringify(body));
  assert.deepEqual(body.result, {
    businessesDeleted: [businessId],
    // La membresía de OWNER cae con el árbol del negocio: no cuenta como membresía suelta.
    membershipsRemoved: 0,
  });

  assert.equal((await db.doc(`businesses/${businessId}`).get()).exists, false);
  for (const sub of [
    "members",
    "invitations",
    "purchases",
    "stockMovements",
    "syncChanges",
    "syncKeys",
    "documentIndex",
    "sync",
  ]) {
    const snapshot = await db.collection(`businesses/${businessId}/${sub}`).get();
    assert.equal(snapshot.size, 0, `la subcolección ${sub} debió quedar vacía`);
  }
  const lines = await db
    .collection(`businesses/${businessId}/purchases/${purchaseId}/lines`)
    .get();
  assert.equal(lines.size, 0);
  assert.equal(
    (await membershipQuotaRef.get()).exists,
    false,
    "el contador durable no debe sobrevivir al borrado de la cuenta",
  );
  assert.equal(await userExists(owner.localId), false);

  // El UUID se retira permanentemente: primero mientras Storage sigue pendiente y luego aun
  // despues de simular que el scheduler confirmo la purga. Asi una outbox antigua no puede
  // aplicar una purga de la generacion A sobre documentos de una generacion B.
  const replacement = await verifiedEmailToken();
  const pendingReuse = await callCallable(
    "createBusiness",
    { businessId, displayName: "Generacion insegura" },
    replacement.idToken,
  );
  assert.equal(pendingReuse.body.error?.status, "FAILED_PRECONDITION");
  assert.equal(
    pendingReuse.body.error?.message,
    "BUSINESS_ID_PENDING_STORAGE_CLEANUP",
  );
  const { accountDeletionTombstoneRef } = await import("../common.js");
  const tombstoneRef = accountDeletionTombstoneRef(owner.localId);
  const tombstoneBeforeSweep = (await tombstoneRef.get()).data();
  assert.deepEqual(tombstoneBeforeSweep.storageCleanupBusinessIds, [businessId]);
  assert.deepEqual(tombstoneBeforeSweep.retiredBusinessIds, [businessId]);
  await tombstoneRef.update({
    storageCleanupPending: false,
    storageCleanupBusinessIds: [],
  });
  const retiredReuse = await callCallable(
    "createBusiness",
    { businessId, displayName: "Generacion tambien insegura" },
    replacement.idToken,
  );
  assert.equal(retiredReuse.body.error?.status, "FAILED_PRECONDITION");
  assert.equal(
    retiredReuse.body.error?.message,
    "BUSINESS_ID_PENDING_STORAGE_CLEANUP",
  );
});

test("OWNER con otros miembros: FAILED_PRECONDITION con detalle y no se borra nada", async () => {
  const owner = await verifiedEmailToken();
  const partner = await verifiedEmailToken();
  const blockedId = await createBusinessAs(owner, "Negocio Compartido");
  await seedMember(blockedId, partner.localId, "OPERATOR");
  await seedInvitation(blockedId, owner.email, "ACCEPTED");
  const blockedPurchaseRef = db.doc(
    `businesses/${blockedId}/purchases/${randomUUID()}`,
  );
  await blockedPurchaseRef.set({
    syncedBy: owner.localId,
    voidedBy: owner.localId,
    totalMinorUnits: 12345,
  });
  // Segundo negocio del que sí es único dueño: tampoco debe tocarse (todo o nada).
  const soleId = await createBusinessAs(owner, "Negocio Propio");

  const { body } = await callCallable("deleteMyAccount", {}, owner.idToken);
  assert.equal(body.error?.status, "FAILED_PRECONDITION");
  assert.equal(body.error?.message, "OWNED_BUSINESS_HAS_MEMBERS");
  assert.deepEqual(body.error?.details?.businesses, [
    { businessId: blockedId, legalName: "Negocio Compartido" },
  ]);

  // Nada se borró: ambos negocios con sus miembros intactos y el usuario de Auth vivo.
  assert.equal((await db.doc(`businesses/${blockedId}`).get()).exists, true);
  assert.equal((await db.doc(`businesses/${soleId}`).get()).exists, true);
  const blockedMembers = await db.collection(`businesses/${blockedId}/members`).get();
  assert.equal(blockedMembers.size, 2);
  const soleMembers = await db.collection(`businesses/${soleId}/members`).get();
  assert.equal(soleMembers.size, 1);
  const invitation = await db
    .doc(`businesses/${blockedId}/invitations/${sha256(owner.email)}`)
    .get();
  assert.equal(invitation.exists, true, "no debe borrar invitaciones antes de abortar");
  assert.equal(invitation.data().status, "ACCEPTED");
  assert.deepEqual((await blockedPurchaseRef.get()).data(), {
    syncedBy: owner.localId,
    voidedBy: owner.localId,
    totalMinorUnits: 12345,
  });
  const { accountDeletionTombstoneRef } = await import("../common.js");
  assert.equal(
    (await accountDeletionTombstoneRef(owner.localId).get()).exists,
    false,
    "un preflight bloqueado no debe dejar tombstone ni cerrar la cuenta",
  );
  assert.equal(await userExists(owner.localId), true);
});

test("la revalidación bloquea una membresía nueva y el lock impide aceptarla", async () => {
  const owner = await verifiedEmailToken();
  const invited = await verifiedEmailToken();
  const businessId = await createBusinessAs(owner, "Negocio en cierre");
  const businessRef = db.doc(`businesses/${businessId}`);
  await seedInvitation(businessId, invited.email, "PENDING");

  // Simula que la invitación se aceptó después del preflight inicial: la segunda lectura
  // transaccional debe abortar sin fijar el lock ni mutar el negocio.
  await seedMember(businessId, invited.localId, "READER");
  const { lockAccountDeletion } = await import("../accountDeletion.js");
  const { accountDeletionTombstoneRef } = await import("../common.js");
  await assert.rejects(
    lockAccountDeletion(owner.localId),
    (error) =>
      error?.code === "failed-precondition" &&
      error?.message === "OWNED_BUSINESS_HAS_MEMBERS",
  );
  assert.equal((await businessRef.get()).data().accountDeletionLocked, undefined);

  // Tras retirar el miembro, la revalidación fija el lock. Una aceptación que compita a partir
  // de aquí lee ese mismo documento dentro de su transacción y no puede recrear membresía.
  await db.doc(`businesses/${businessId}/members/${invited.localId}`).delete();
  await lockAccountDeletion(owner.localId);
  assert.equal((await businessRef.get()).data().accountDeletionLocked, true);
  const tombstone = await accountDeletionTombstoneRef(owner.localId).get();
  assert.equal(tombstone.exists, true);
  const tombstoneData = tombstone.data();
  assert.equal(tombstoneData.schemaVersion, 1);
  assert.equal(tombstoneData.blocksMutations, true);
  assert.equal(tombstoneData.cleanupComplete, false);
  assert.deepEqual(tombstoneData.storageCleanupBusinessIds, [businessId]);
  assert.deepEqual(tombstoneData.retiredBusinessIds, [businessId]);
  assert.equal(tombstoneData.storageCleanupPending, true);
  assert.ok(tombstoneData.storageCleanupEligibleAt instanceof Timestamp);
  assert.equal(JSON.stringify(tombstone.data()).includes(owner.localId), false);
  assert.equal(tombstone.ref.id.includes(owner.localId), false);
  const acceptance = await callCallable(
    "acceptInvitation",
    { businessId },
    invited.idToken,
  );
  assert.equal(acceptance.body.error?.status, "FAILED_PRECONDITION");
  assert.equal(acceptance.body.error?.message, "BUSINESS_DELETION_IN_PROGRESS");
  assert.equal(
    (await db.doc(`businesses/${businessId}/members/${invited.localId}`).get()).exists,
    false,
  );
});

test("miembro no-OWNER: pierde solo su membresía y su usuario de Auth", async () => {
  const owner = await verifiedEmailToken();
  const member = await verifiedEmailToken();
  const businessId = await createBusinessAs(owner, "Negocio con Equipo");
  await seedMember(businessId, member.localId, "OPERATOR");

  const { body } = await callCallable("deleteMyAccount", {}, member.idToken);
  assert.equal(body.error, undefined, JSON.stringify(body));
  assert.deepEqual(body.result, { businessesDeleted: [], membershipsRemoved: 1 });

  // El negocio y el OWNER quedan intactos; solo desaparece la membresía del caller.
  assert.equal((await db.doc(`businesses/${businessId}`).get()).exists, true);
  assert.equal(
    (await db.doc(`businesses/${businessId}/members/${owner.localId}`).get()).exists,
    true,
  );
  assert.equal(
    (await db.doc(`businesses/${businessId}/members/${member.localId}`).get()).exists,
    false,
  );
  assert.equal(await userExists(member.localId), false);
  assert.equal(await userExists(owner.localId), true);
});

test("un miembro con tombstone no puede ser promovido durante su eliminación", async () => {
  const owner = await verifiedEmailToken();
  const departing = await verifiedEmailToken();
  const businessId = await createBusinessAs(owner, "Negocio promoción concurrente");
  const departingRef = db.doc(`businesses/${businessId}/members/${departing.localId}`);
  await seedMember(businessId, departing.localId, "OPERATOR");

  // Fija primero la guarda global, como ocurriría al comenzar deleteMyAccount. El cambio de rol
  // debe leer la guarda del target dentro de su propia transacción, no solo la del OWNER caller.
  const { lockAccountDeletion } = await import("../accountDeletion.js");
  await lockAccountDeletion(departing.localId, departing.email);
  const promoted = await callCallable(
    "changeMemberRole",
    { businessId, uid: departing.localId, role: "OWNER" },
    owner.idToken,
  );
  assert.equal(promoted.body.error?.status, "FAILED_PRECONDITION");
  assert.equal(promoted.body.error?.message, "ACCOUNT_DELETION_IN_PROGRESS");
  assert.equal((await departingRef.get()).data().role, "OPERATOR");

  const duringDeletion = await callCallable(
    "inviteMember",
    { businessId, email: departing.email, role: "OPERATOR" },
    owner.idToken,
  );
  assert.equal(duringDeletion.body.error?.status, "FAILED_PRECONDITION");
  assert.equal(duringDeletion.body.error?.message, "ACCOUNT_DELETION_IN_PROGRESS");

  // El reintento normal termina el barrido; la promoción rechazada no deja la cuenta atascada
  // como OWNER de un negocio compartido.
  const deleted = await callCallable("deleteMyAccount", {}, departing.idToken);
  assert.equal(deleted.body.error, undefined, JSON.stringify(deleted.body));
  assert.equal((await departingRef.get()).exists, false);

  const reinvite = await callCallable(
    "inviteMember",
    { businessId, email: departing.email, role: "OPERATOR" },
    owner.idToken,
  );
  assert.equal(reinvite.body.error, undefined, JSON.stringify(reinvite.body));
  assert.equal(
    (await db.doc(`businesses/${businessId}/invitations/${sha256(departing.email)}`).get()).exists,
    true,
  );
});

test("un email-lock huérfano tras borrar Auth caduca y se limpia al reinvitar", async () => {
  const owner = await verifiedEmailToken();
  const departing = await verifiedEmailToken();
  const businessId = await createBusinessAs(owner, "Negocio recupera lock temporal");

  const deleted = await callCallable("deleteMyAccount", {}, departing.idToken);
  assert.equal(deleted.body.error, undefined, JSON.stringify(deleted.body));
  assert.equal(await userExists(departing.localId), false);

  // Simula que el delete best-effort del lock falló justo después de eliminar Auth. El hash
  // pseudónimo puede sobrevivir, pero su TTL cerrado permite recuperación sin el JWT borrado.
  const { accountDeletionEmailLockRef } = await import("../common.js");
  const orphanedLock = accountDeletionEmailLockRef(departing.email);
  await orphanedLock.set({
    schemaVersion: 1,
    blocksInvitations: true,
    expiresAt: Timestamp.fromMillis(Date.now() - 1),
  });

  const reinvite = await callCallable(
    "inviteMember",
    { businessId, email: departing.email, role: "OPERATOR" },
    owner.idToken,
  );
  assert.equal(reinvite.body.error, undefined, JSON.stringify(reinvite.body));
  assert.equal((await orphanedLock.get()).exists, false);
  assert.equal(
    (await db.doc(`businesses/${businessId}/invitations/${sha256(departing.email)}`).get()).exists,
    true,
  );
});

test("fallo al borrar el email-lock después de Auth es best-effort y no falsea el éxito", async () => {
  const { deleteEmailLockBestEffort } = await import("../accountDeletion.js");
  let attempts = 0;

  await assert.doesNotReject(
    deleteEmailLockBestEffort("recovery@example.test", async (ref) => {
      attempts += 1;
      assert.equal(ref.parent.id, "accountDeletionEmailLocks");
      throw new Error("fallo sintético posterior a Auth");
    }),
  );
  assert.equal(attempts, 1);
});

test("sin autenticación es UNAUTHENTICATED", async () => {
  const { body } = await callCallable("deleteMyAccount", {});
  assert.equal(body.error?.status, "UNAUTHENTICATED");
  assert.equal(body.error?.message, "AUTH_REQUIRED");
});

test("cuenta no verificada se borra sin autorizar limpieza por email", async () => {
  const owner = await verifiedEmailToken();
  const unverified = await unverifiedEmailToken();
  const businessId = await createBusinessAs(owner, "Negocio con Invitación no verificada");
  await seedInvitation(businessId, unverified.email, "PENDING");

  const { body } = await callCallable("deleteMyAccount", {}, unverified.idToken);
  assert.equal(body.error, undefined, JSON.stringify(body));
  assert.deepEqual(body.result, { businessesDeleted: [], membershipsRemoved: 0 });

  const invitation = await db
    .doc(`businesses/${businessId}/invitations/${sha256(unverified.email)}`)
    .get();
  assert.equal(invitation.exists, true);
  assert.equal(await userExists(unverified.localId), false);
});

test("borra invitaciones de cualquier estado dirigidas a su email (y solo esas)", async () => {
  const owner = await verifiedEmailToken();
  const guest = await verifiedEmailToken();
  const businessId = await createBusinessAs(owner, "Negocio Invitador");
  const acceptedBusinessId = await createBusinessAs(owner, "Negocio Aceptado");
  const declinedBusinessId = await createBusinessAs(owner, "Negocio Rechazado");

  const otherEmail = `ad-otro-${randomUUID()}@example.test`;
  await seedInvitation(businessId, guest.email, "PENDING");
  await seedInvitation(businessId, otherEmail, "PENDING");
  await seedInvitation(acceptedBusinessId, guest.email, "ACCEPTED");
  await seedInvitation(declinedBusinessId, guest.email, "DECLINED");

  const { body } = await callCallable("deleteMyAccount", {}, guest.idToken);
  assert.equal(body.error, undefined, JSON.stringify(body));
  assert.deepEqual(body.result, { businessesDeleted: [], membershipsRemoved: 0 });

  const invitations = db.collection(`businesses/${businessId}/invitations`);
  assert.equal((await invitations.doc(sha256(guest.email)).get()).exists, false);
  assert.equal((await invitations.doc(sha256(otherEmail)).get()).exists, true);
  const accepted = await db
    .doc(`businesses/${acceptedBusinessId}/invitations/${sha256(guest.email)}`)
    .get();
  const declined = await db
    .doc(`businesses/${declinedBusinessId}/invitations/${sha256(guest.email)}`)
    .get();
  assert.equal(accepted.exists, false);
  assert.equal(declined.exists, false);
  assert.equal(await userExists(guest.localId), false);
});

test("anonimiza referencias UID y conserva intactos negocio, compras y campos ajenos", async () => {
  const departing = await verifiedEmailToken();
  const owner = await verifiedEmailToken();
  const businessId = await createBusinessAs(departing, "Negocio Transferido");
  const businessRef = db.doc(`businesses/${businessId}`);
  await businessRef.update({
    commercialCode: "COM-42",
    settings: { currency: "PEN", inventoryEnabled: true },
  });
  await db.doc(`businesses/${businessId}/members/${departing.localId}`).update({
    role: "OPERATOR",
  });
  await seedMember(businessId, owner.localId, "OWNER");
  const ownerMemberRef = db.doc(`businesses/${businessId}/members/${owner.localId}`);
  await ownerMemberRef.update({
    roleUpdatedBy: departing.localId,
    roleUpdateReason: "transferencia",
  });

  const recipientHistoryRef = db.doc(
    `businesses/${businessId}/invitations/${randomUUID()}`,
  );
  const historicalEmail = `ad-hist-${randomUUID()}@example.test`;
  await recipientHistoryRef.set({
    email: historicalEmail,
    role: "ADMIN",
    status: "ACCEPTED",
    invitedBy: departing.localId,
    acceptedBy: departing.localId,
    untouchedUid: owner.localId,
    auditLabel: "conservar",
  });
  const declinedHistoryRef = db.doc(
    `businesses/${businessId}/invitations/${randomUUID()}`,
  );
  await declinedHistoryRef.set({
    email: `ad-declined-${randomUUID()}@example.test`,
    role: "READER",
    status: "DECLINED",
    invitedBy: owner.localId,
    declinedBy: departing.localId,
    untouchedUid: owner.localId,
  });
  const authoredInvitationRef = db.doc(
    `businesses/${businessId}/invitations/${randomUUID()}`,
  );
  const authoredEmail = `ad-authored-${randomUUID()}@example.test`;
  await authoredInvitationRef.set({
    email: authoredEmail,
    role: "READER",
    status: "PENDING",
    invitedBy: departing.localId,
    untouchedUid: owner.localId,
    auditLabel: "conservar-autoria",
  });
  const cancelledInvitationRef = db.doc(
    `businesses/${businessId}/invitations/${randomUUID()}`,
  );
  const cancelledEmail = `ad-cancelled-${randomUUID()}@example.test`;
  await cancelledInvitationRef.set({
    email: cancelledEmail,
    role: "READER",
    status: "CANCELLED",
    invitedBy: owner.localId,
    cancelledBy: departing.localId,
    untouchedUid: owner.localId,
    cancellationReason: "MEMBER_REMOVED",
  });

  const purchaseRef = db.doc(`businesses/${businessId}/purchases/${randomUUID()}`);
  await purchaseRef.set({
    syncedBy: departing.localId,
    voidedBy: departing.localId,
    status: "VOIDED",
    totalMinorUnits: 98765,
    supplier: { ruc: "20123456789", legalName: "Proveedor Histórico" },
  });
  const voidRecordRef = purchaseRef.collection("voidRecord").doc("record");
  await voidRecordRef.set({
    payload: JSON.stringify({
      version: 1,
      purchaseId: purchaseRef.id,
      impactHash: "b".repeat(64),
      actorId: businessId,
      role: "OWNER",
      reason: "Documento emitido por error material",
      negativeStockPolicy: "ALLOW_WITH_VISIBLE_WARNING",
      averageUnitCostPolicy: "PRESERVE_CURRENT",
      negativeImpactCount: 0,
      impacts: [],
    }),
    cloudActorUid: departing.localId,
    impactHash: "b".repeat(64),
    immutableMarker: "conservar",
  });

  const { body } = await callCallable("deleteMyAccount", {}, departing.idToken);
  assert.equal(body.error, undefined, JSON.stringify(body));
  assert.deepEqual(body.result, { businessesDeleted: [], membershipsRemoved: 1 });

  const business = await businessRef.get();
  assert.equal(business.exists, true);
  assert.equal(business.data().createdBy, "deleted-account");
  assert.equal(business.data().displayName, "Negocio Transferido");
  assert.equal(business.data().commercialCode, "COM-42");
  assert.deepEqual(business.data().settings, {
    currency: "PEN",
    inventoryEnabled: true,
  });
  assert.equal(
    (await db.doc(`businesses/${businessId}/members/${departing.localId}`).get()).exists,
    false,
  );
  const ownerMember = await ownerMemberRef.get();
  assert.equal(ownerMember.exists, true);
  assert.equal(ownerMember.data().uid, owner.localId);
  assert.equal(ownerMember.data().role, "OWNER");
  assert.equal(ownerMember.data().roleUpdatedBy, "deleted-account");
  assert.equal(ownerMember.data().roleUpdateReason, "transferencia");

  assert.equal(
    (await recipientHistoryRef.get()).exists,
    false,
    "acceptedBy debe borrar toda la invitación, incluido un email anterior",
  );
  assert.equal(
    (await declinedHistoryRef.get()).exists,
    false,
    "declinedBy debe borrar toda la invitación, incluido un email anterior",
  );
  const invitation = (await authoredInvitationRef.get()).data();
  assert.equal(invitation.invitedBy, "deleted-account");
  assert.equal(invitation.email, authoredEmail);
  assert.equal(invitation.role, "READER");
  assert.equal(invitation.status, "PENDING");
  assert.equal(invitation.untouchedUid, owner.localId);
  assert.equal(invitation.auditLabel, "conservar-autoria");
  const cancelledInvitation = (await cancelledInvitationRef.get()).data();
  assert.equal(cancelledInvitation.invitedBy, owner.localId);
  assert.equal(cancelledInvitation.cancelledBy, "deleted-account");
  assert.equal(cancelledInvitation.email, cancelledEmail);
  assert.equal(cancelledInvitation.status, "CANCELLED");
  assert.equal(cancelledInvitation.untouchedUid, owner.localId);
  assert.equal(cancelledInvitation.cancellationReason, "MEMBER_REMOVED");

  const purchase = (await purchaseRef.get()).data();
  assert.equal(purchase.syncedBy, "deleted-account");
  assert.equal(purchase.voidedBy, "deleted-account");
  assert.equal(purchase.status, "VOIDED");
  assert.equal(purchase.totalMinorUnits, 98765);
  assert.deepEqual(purchase.supplier, {
    ruc: "20123456789",
    legalName: "Proveedor Histórico",
  });
  const voidRecord = (await voidRecordRef.get()).data();
  const voidPayload = JSON.parse(voidRecord.payload);
  assert.equal(voidRecord.cloudActorUid, "deleted-account");
  assert.equal(voidPayload.actorId, businessId);
  assert.equal(voidPayload.purchaseId, purchaseRef.id);
  assert.equal(voidPayload.reason, "Documento emitido por error material");
  assert.deepEqual(voidPayload.impacts, []);
  assert.equal(voidRecord.impactHash, "b".repeat(64));
  assert.equal(voidRecord.immutableMarker, "conservar");
  assert.equal(await userExists(departing.localId), false);
  assert.equal(await userExists(owner.localId), true);
});

test("procesa 501 mutaciones en lotes secuenciales sin perder compras", async () => {
  const owner = await verifiedEmailToken();
  const departing = await verifiedEmailToken();
  const businessId = await createBusinessAs(owner, "Negocio con Historial Extenso");
  await seedMember(businessId, departing.localId, "OPERATOR");

  // 500 updates de compra + el delete de la membresía fuerzan 501 escrituras.
  const purchaseCount = 500;
  const purchases = Array.from({ length: purchaseCount }, (_, index) => ({
    ref: db.doc(
      `businesses/${businessId}/purchases/batch-${String(index).padStart(4, "0")}`,
    ),
    data: {
      seq: index + 1,
      syncedBy: departing.localId,
      voidedBy: departing.localId,
      totalMinorUnits: index,
    },
  }));
  await seedInBatches(purchases);

  const { body } = await callCallable("deleteMyAccount", {}, departing.idToken);
  assert.equal(body.error, undefined, JSON.stringify(body));
  assert.deepEqual(body.result, { businessesDeleted: [], membershipsRemoved: 1 });

  const storedPurchases = await db.collection(`businesses/${businessId}/purchases`).get();
  assert.equal(storedPurchases.size, purchaseCount);
  for (const purchase of storedPurchases.docs) {
    assert.equal(purchase.data().syncedBy, "deleted-account");
    assert.equal(purchase.data().voidedBy, "deleted-account");
    assert.equal(typeof purchase.data().totalMinorUnits, "number");
  }
  assert.equal((await db.doc(`businesses/${businessId}`).get()).exists, true);
  assert.equal(await userExists(departing.localId), false);
});

test("el alcance inicial acepta 480 negocios y falla cerrado antes de 481", async () => {
  const { requireAccountDeletionScopeWithinLimit } = await import("../accountDeletion.js");

  assert.doesNotThrow(() => requireAccountDeletionScopeWithinLimit(480));
  assert.throws(
    () => requireAccountDeletionScopeWithinLimit(481),
    (error) =>
      error?.code === "failed-precondition" &&
      error?.message === "ACCOUNT_DELETION_SCOPE_TOO_LARGE",
  );
});

test("un fallo después del primer lote de 400 permite reintentar las 501 mutaciones", async () => {
  const { commitAccountDeletionMutations } = await import("../accountDeletion.js");
  const applied = new Map();
  let commitNumber = 0;
  let failSecondBatchOnce = true;
  const fakeFirestore = {
    batch() {
      const pending = [];
      return {
        update(ref, data) {
          pending.push({ ref, data });
        },
        delete(ref) {
          pending.push({ ref, data: null });
        },
        async commit() {
          commitNumber += 1;
          if (failSecondBatchOnce && commitNumber === 2) {
            failSecondBatchOnce = false;
            throw new Error("fallo transitorio sintético");
          }
          for (const mutation of pending) applied.set(mutation.ref.path, mutation.data);
        },
      };
    },
  };
  const mutations = Array.from({ length: 501 }, (_, index) => ({
    type: "update",
    ref: { path: `businesses/demo/purchases/${String(index).padStart(4, "0")}` },
    data: { syncedBy: "deleted-account" },
  }));

  await assert.rejects(
    commitAccountDeletionMutations(fakeFirestore, mutations, 400),
    /fallo transitorio sintético/,
  );
  assert.equal(applied.size, 400, "el primer lote ya confirmado se conserva");

  commitNumber = 0;
  await commitAccountDeletionMutations(fakeFirestore, mutations, 400);
  assert.equal(applied.size, 501);
  for (const value of applied.values()) {
    assert.deepEqual(value, { syncedBy: "deleted-account" });
  }
});

test("la consulta paginada conserva progreso durable y reanuda sin cargar todo el alcance", async () => {
  const { consumeAccountDeletionQuery } = await import("../accountDeletion.js");
  const documents = Array.from({ length: 8 }, (_, index) => ({
    matches: true,
    ref: { path: `businesses/demo/purchases/page-${index}` },
  }));
  let largestPage = 0;
  const query = {
    limit(pageSize) {
      return {
        async get() {
          const docs = documents.filter((document) => document.matches).slice(0, pageSize);
          largestPage = Math.max(largestPage, docs.length);
          return { docs, empty: docs.length === 0, size: docs.length };
        },
      };
    },
  };
  let commitCount = 0;
  let failSecondCommitOnce = true;
  const fakeFirestore = {
    batch() {
      const pending = [];
      return {
        update(ref) {
          pending.push(ref);
        },
        async commit() {
          commitCount += 1;
          if (failSecondCommitOnce && commitCount === 2) {
            failSecondCommitOnce = false;
            throw new Error("fallo paginado sintético");
          }
          for (const ref of pending) {
            documents.find((document) => document.ref.path === ref.path).matches = false;
          }
        },
      };
    },
  };
  const collectMutation = (mutations, document) => mutations.set(document.ref.path, {
    type: "update",
    ref: document.ref,
    data: { syncedBy: "deleted-account" },
  });

  await assert.rejects(
    consumeAccountDeletionQuery(query, collectMutation, fakeFirestore, 3),
    /fallo paginado sintético/,
  );
  assert.equal(documents.filter((document) => document.matches).length, 5);

  commitCount = 0;
  const resumed = await consumeAccountDeletionQuery(query, collectMutation, fakeFirestore, 3);
  assert.equal(resumed, 5);
  assert.equal(documents.some((document) => document.matches), false);
  assert.equal(largestPage, 3);
});

test("tras el borrado, la sesión queda inválida: sin membresías y sin renovar token", async () => {
  const owner = await verifiedEmailToken();
  const businessId = await createBusinessAs(owner, "Negocio Efímero");

  const before = await callCallable("listMyMemberships", {}, owner.idToken);
  assert.equal(before.body.error, undefined, JSON.stringify(before.body));
  assert.equal(before.body.result.memberships.length, 1);

  const deleted = await callCallable("deleteMyAccount", {}, owner.idToken);
  assert.equal(deleted.body.error, undefined, JSON.stringify(deleted.body));

  // El idToken firmado sigue verificando hasta su expiración, pero el tombstone bloquea incluso
  // los callables de lectura específicos de la cuenta.
  const after = await callCallable("listMyMemberships", {}, owner.idToken);
  assert.equal(after.body.error?.status, "FAILED_PRECONDITION");
  assert.equal(after.body.error?.message, "ACCOUNT_DELETION_IN_PROGRESS");

  // Un JWT viejo tampoco puede crear un negocio nuevo y reintroducir UID/email después del
  // barrido. La guarda vive dentro de la misma transacción que escribiría el negocio.
  const staleBusinessId = randomUUID();
  const staleCreate = await callCallable(
    "createBusiness",
    { businessId: staleBusinessId, displayName: "No debe existir" },
    owner.idToken,
  );
  assert.equal(staleCreate.body.error?.status, "FAILED_PRECONDITION");
  assert.equal(staleCreate.body.error?.message, "ACCOUNT_DELETION_IN_PROGRESS");
  assert.equal((await db.doc(`businesses/${staleBusinessId}`).get()).exists, false);

  const stalePost = await callCallable(
    "postPurchase",
    minimalPurchaseRequest(staleBusinessId),
    owner.idToken,
  );
  assert.equal(stalePost.body.error?.status, "FAILED_PRECONDITION");
  assert.equal(stalePost.body.error?.message, "ACCOUNT_DELETION_IN_PROGRESS");
  assert.equal(
    (await db.collection(`businesses/${staleBusinessId}/purchases`).get()).empty,
    true,
  );

  // Un reintento con el mismo JWT (todavía vigente) también termina correctamente. El tombstone
  // conserva el UUID retirado y repite idempotentemente la purga Storage antes de responder el
  // mismo resumen; Auth trata user-not-found como el estado final deseado.
  const retried = await callCallable("deleteMyAccount", {}, owner.idToken);
  assert.equal(retried.body.error, undefined, JSON.stringify(retried.body));
  assert.deepEqual(retried.body.result, {
    businessesDeleted: [businessId],
    membershipsRemoved: 0,
  });

  // …y la sesión no se puede renovar: el refresh token del usuario borrado muere con él,
  // así que el cliente pierde el acceso en cuanto expira el idToken en curso.
  const refresh = await fetch(
    "http://localhost:9099/securetoken.googleapis.com/v1/token?key=demo-api-key",
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ grant_type: "refresh_token", refresh_token: owner.refreshToken }),
    },
  );
  const refreshBody = await refresh.json();
  assert.equal(refreshBody.id_token, undefined, JSON.stringify(refreshBody));
  assert.equal(refreshBody.error?.message, "INVALID_REFRESH_TOKEN");
});
