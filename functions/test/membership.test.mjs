// Integración de los callables de membresía contra el Emulator Suite (proyecto
// demo-facturastock). Se ejecuta con:
//   npx firebase emulators:exec --only auth,firestore,functions,storage "npm test"
// Mismos helpers de cuentas verificadas que postPurchase.test.mjs (emails con prefijo mb-).
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
const firestoreEmulator = new URL(`http://${process.env.FIRESTORE_EMULATOR_HOST}`);
const FIRESTORE_HOST = firestoreEmulator.hostname;
const FIRESTORE_PORT = Number(firestoreEmulator.port || 8080);

// Mantener este cliente de siembra con nombre propio: una regresión importa common.js para
// calcular la misma referencia pseudónima que producción, y common.js inicializa el app
// [DEFAULT] usado por Functions. Compartir ese nombre con opciones distintas hace que el test
// dependa del orden/aislamiento del runner de Node.
const adminApp = initializeAdminApp({ projectId: PROJECT }, "membership-test");
const db = getFirestore(adminApp);
const adminAuth = getAuth(adminApp);

const sha256 = (text) => createHash("sha256").update(text, "utf8").digest("hex");

// Negocios creados durante la corrida; se borran al final.
const createdBusinesses = new Set();

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
  const email = `mb-${randomUUID()}@example.test`;
  const { localId } = await authRest("accounts:signUp", {
    email,
    password: PASSWORD,
    returnSecureToken: true,
  });
  await adminAuth.updateUser(localId, { emailVerified: true });
  const { idToken } = await authRest("accounts:signInWithPassword", {
    email,
    password: PASSWORD,
    returnSecureToken: true,
  });
  return { idToken, localId, email };
}

async function unverifiedEmailToken() {
  const email = `mb-${randomUUID()}@example.test`;
  const { idToken, localId } = await authRest("accounts:signUp", {
    email,
    password: PASSWORD,
    returnSecureToken: true,
  });
  return { idToken, localId, email };
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

// Crea un negocio vía callable y lo registra para limpieza.
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

// Siembra directa de miembros con Admin SDK (atajo para montar escenarios de roles).
async function seedBusiness(businessId, members) {
  createdBusinesses.add(businessId);
  await db.doc(`businesses/${businessId}`).set({ businessId, displayName: "Negocio sembrado" });
  for (const member of members) {
    await db.doc(`businesses/${businessId}/members/${member.uid}`).set({
      uid: member.uid,
      email: member.email ?? null,
      role: member.role,
    });
  }
}

async function clearBusiness(businessId) {
  const root = db.doc(`businesses/${businessId}`);
  for (const sub of [
    "members",
    "invitations",
    "purchases",
    "stockMovements",
    "auditEvents",
    "syncChanges",
    "sync",
    "syncKeys",
    "documentIndex",
    "inventoryBalances",
    "inventorySyncChanges",
  ]) {
    const snapshot = await root.collection(sub).get();
    for (const doc of snapshot.docs) {
      if (sub === "purchases") {
        const lines = await doc.ref.collection("lines").get();
        for (const line of lines.docs) await line.ref.delete();
        const voids = await doc.ref.collection("voidRecord").get();
        for (const record of voids.docs) await record.ref.delete();
      }
      await doc.ref.delete();
    }
  }
  await root.delete();
}

after(async () => {
  for (const businessId of createdBusinesses) await clearBusiness(businessId);
  await deleteApp(adminApp);
});

let minimalPurchaseCounter = 0;

// Compra mínima vigente (wire v4/documento v3) para probar autorización por negocio.
function minimalPurchaseRequest(businessId) {
  const purchaseId = randomUUID();
  const purchaseLineId = randomUUID();
  const productId = randomUUID();
  const postedAt = 1_787_000_000_000;
  const key = `sync-purchase:v1:${purchaseId}`;
  return {
    businessId,
    idempotencyKey: key,
    operationType: "SYNC_PURCHASE",
    payloadVersion: 4,
    document: {
      version: 3,
      purchaseId,
      businessId,
      status: "POSTED",
      documentType: "INVOICE",
      documentSeries: "F001",
      // Número único por compra (la unicidad documental es ruc|tipo|serie|número por negocio).
      documentNumber: String(++minimalPurchaseCounter).padStart(12, "0"),
      issueDate: "2026-08-14",
      currency: "PEN",
      supplierRuc: "20123456789",
      supplierLegalName: "PROVEEDOR DEMO SAC",
      subtotalMinorUnits: 100,
      taxMinorUnits: 0,
      otherChargesMinorUnits: 0,
      totalMinorUnits: 100,
      adjustmentMinorUnits: null,
      adjustmentReason: null,
      preparedLogicalHash: "c".repeat(64),
      postedAt,
      idempotencyKey: key,
      lines: [
        {
          purchaseLineId,
          position: 0,
          productId,
          productName: "PRODUCTO DEMO",
          unitCode: "NIU",
          description: "PRODUCTO DEMO",
          quantity: "1",
          readUnitCost: "1.00",
          taxMinorUnits: 0,
          totalMinorUnits: 100,
          appliedUnitCost: "1.00",
          inventoryQuantity: "1",
          discount: null,
          taxTreatment: "EXEMPT",
          taxEvidence: { type: "NONE", value: null },
          productProvenance: "EXISTING",
        },
      ],
      movements: [
        {
          movementId: randomUUID(),
          purchaseLineId,
          productId,
          locationId: randomUUID(),
          locationName: "Almacén principal",
          type: "PURCHASE",
          quantityDelta: "1",
          unitCost: "1.00",
          appliedCostTotal: "1.00",
          occurredAt: postedAt,
        },
      ],
      auditEventIds: [randomUUID()],
      duplicateOverride: null,
    },
  };
}

test("createBusiness crea el negocio y registra al caller como OWNER; re-crear falla", async () => {
  const owner = await verifiedEmailToken();
  const businessId = randomUUID();
  createdBusinesses.add(businessId);

  const { body } = await callCallable(
    "createBusiness",
    { businessId, displayName: "Bodega Demo" },
    owner.idToken,
  );
  assert.equal(body.error, undefined, JSON.stringify(body));
  assert.deepEqual(body.result, { businessId, role: "OWNER" });

  const business = await db.doc(`businesses/${businessId}`).get();
  assert.equal(business.data().displayName, "Bodega Demo");
  assert.equal(business.data().createdBy, owner.localId);
  const member = await db.doc(`businesses/${businessId}/members/${owner.localId}`).get();
  assert.equal(member.data().role, "OWNER");
  assert.equal(member.data().email, owner.email);
  assert.equal(member.data().addedVia, "create");

  const again = await callCallable(
    "createBusiness",
    { businessId, displayName: "Otro nombre" },
    owner.idToken,
  );
  assert.equal(again.body.error?.status, "FAILED_PRECONDITION");
  assert.equal(again.body.error?.message, "BUSINESS_EXISTS");

  const mine = await callCallable("listMyMemberships", {}, owner.idToken);
  const entry = mine.body.result.memberships.find((m) => m.businessId === businessId);
  assert.deepEqual(entry, { businessId, displayName: "Bodega Demo", role: "OWNER" });
});

test("createBusiness aplica cuota por cuenta antes de escribir otro tenant", async () => {
  const owner = await verifiedEmailToken();
  const batch = db.batch();
  for (let index = 0; index < 20; index += 1) {
    const businessId = randomUUID();
    createdBusinesses.add(businessId);
    batch.set(db.doc(`businesses/${businessId}`), {
      businessId,
      displayName: `Cuota ${index}`,
      createdBy: owner.localId,
    });
    batch.set(db.doc(`businesses/${businessId}/members/${owner.localId}`), {
      uid: owner.localId,
      email: owner.email,
      role: "OWNER",
    });
  }
  await batch.commit();
  const extraId = randomUUID();
  const denied = await callCallable(
    "createBusiness",
    { businessId: extraId, displayName: "Excede cuota" },
    owner.idToken,
  );
  assert.equal(denied.body.error?.status, "RESOURCE_EXHAUSTED");
  assert.equal(denied.body.error?.message, "BUSINESS_QUOTA");
  assert.equal((await db.doc(`businesses/${extraId}`).get()).exists, false);
});

test("dos createBusiness concurrentes en 19 membresías serializan el cupo veinte", async () => {
  const owner = await verifiedEmailToken();
  const batch = db.batch();
  for (let index = 0; index < 19; index += 1) {
    const legacyBusinessId = randomUUID();
    createdBusinesses.add(legacyBusinessId);
    batch.set(db.doc(`businesses/${legacyBusinessId}`), {
      businessId: legacyBusinessId,
      displayName: `Legacy ${index}`,
    });
    batch.set(db.doc(`businesses/${legacyBusinessId}/members/${owner.localId}`), {
      uid: owner.localId,
      email: owner.email,
      role: "OWNER",
    });
  }
  await batch.commit();

  const candidates = [randomUUID(), randomUUID()];
  candidates.forEach((businessId) => createdBusinesses.add(businessId));
  const results = await Promise.all(candidates.map((businessId, index) =>
    callCallable(
      "createBusiness",
      { businessId, displayName: `Concurrente ${index}` },
      owner.idToken,
    )
  ));
  assert.equal(results.filter(({ body }) => body.error === undefined).length, 1);
  assert.equal(
    results.filter(({ body }) => body.error?.message === "BUSINESS_QUOTA").length,
    1,
  );
  const memberships = await db.collectionGroup("members")
    .where("uid", "==", owner.localId)
    .count()
    .get();
  assert.equal(memberships.data().count, 20);
  const { membershipQuotaCounterDocumentId } = await import("../membership.js");
  const counter = await db.doc(
    `membershipQuotaCounters/${membershipQuotaCounterDocumentId(owner.localId)}`,
  ).get();
  assert.equal(counter.data().schemaVersion, 1);
  assert.equal(counter.data().count, 20);
  assert.equal("uid" in counter.data(), false);
});

test("los callables de membresía exigen email verificado", async () => {
  const user = await unverifiedEmailToken();
  const businessId = randomUUID();
  const created = await callCallable(
    "createBusiness",
    { businessId, displayName: "Sin verificar" },
    user.idToken,
  );
  assert.equal(created.body.error?.status, "PERMISSION_DENIED");
  assert.equal(created.body.error?.message, "EMAIL_NOT_VERIFIED");

  const mine = await callCallable("listMyMemberships", {}, user.idToken);
  assert.equal(mine.body.error?.message, "EMAIL_NOT_VERIFIED");
});

test("expectedUid rechaza las seis mutaciones antes de cualquier efecto", async () => {
  const owner = await verifiedEmailToken();
  const guest = await verifiedEmailToken();
  const untouched = await verifiedEmailToken();
  const assertIdentityChanged = ({ body }) => {
    assert.equal(body.error?.status, "UNAUTHENTICATED", JSON.stringify(body));
    assert.equal(body.error?.message, "AUTH_IDENTITY_CHANGED");
  };

  const rejectedBusinessId = randomUUID();
  createdBusinesses.add(rejectedBusinessId);
  assertIdentityChanged(await callCallable(
    "createBusiness",
    {
      businessId: rejectedBusinessId,
      displayName: "No debe existir",
      expectedUid: guest.localId,
    },
    owner.idToken,
  ));
  assert.equal((await db.doc(`businesses/${rejectedBusinessId}`).get()).exists, false);

  const businessId = await createBusinessAs(owner, "Negocio con intención de identidad");
  const untouchedInvitation = db.doc(
    `businesses/${businessId}/invitations/${sha256(untouched.email)}`,
  );
  const rateDocumentsBefore = (await db.collection("invitationRateLimits").get()).size;
  assertIdentityChanged(await callCallable(
    "inviteMember",
    {
      businessId,
      email: untouched.email,
      role: "READER",
      expectedUid: guest.localId,
    },
    owner.idToken,
  ));
  assert.equal((await untouchedInvitation.get()).exists, false);
  assert.equal((await db.collection("invitationRateLimits").get()).size, rateDocumentsBefore);

  const invitationRef = db.doc(
    `businesses/${businessId}/invitations/${sha256(guest.email)}`,
  );
  const guestMemberRef = db.doc(`businesses/${businessId}/members/${guest.localId}`);
  const invited = await callCallable(
    "inviteMember",
    {
      businessId,
      email: guest.email,
      role: "READER",
      expectedUid: owner.localId,
    },
    owner.idToken,
  );
  assert.equal(invited.body.error, undefined, JSON.stringify(invited.body));

  assertIdentityChanged(await callCallable(
    "acceptInvitation",
    { businessId, expectedUid: owner.localId },
    guest.idToken,
  ));
  assert.equal((await guestMemberRef.get()).exists, false);
  assert.equal((await invitationRef.get()).data()?.status, "PENDING");

  assertIdentityChanged(await callCallable(
    "declineInvitation",
    { businessId, expectedUid: owner.localId },
    guest.idToken,
  ));
  assert.equal((await guestMemberRef.get()).exists, false);
  assert.equal((await invitationRef.get()).data()?.status, "PENDING");

  const accepted = await callCallable(
    "acceptInvitation",
    { businessId, expectedUid: guest.localId },
    guest.idToken,
  );
  assert.equal(accepted.body.error, undefined, JSON.stringify(accepted.body));
  assert.equal((await guestMemberRef.get()).data()?.role, "READER");

  assertIdentityChanged(await callCallable(
    "changeMemberRole",
    {
      businessId,
      uid: guest.localId,
      role: "OPERATOR",
      expectedUid: guest.localId,
    },
    owner.idToken,
  ));
  assert.equal((await guestMemberRef.get()).data()?.role, "READER");

  assertIdentityChanged(await callCallable(
    "removeMember",
    { businessId, uid: guest.localId, expectedUid: guest.localId },
    owner.idToken,
  ));
  assert.equal((await guestMemberRef.get()).exists, true);

  const reads = [
    ["listMyMemberships", {}],
    ["listMembers", { businessId }],
    ["listBusinessInvitations", { businessId }],
    ["listMyInvitations", {}],
  ];
  for (const [name, data] of reads) {
    assertIdentityChanged(await callCallable(
      name,
      { ...data, expectedUid: guest.localId },
      owner.idToken,
    ));
    const matching = await callCallable(
      name,
      { ...data, expectedUid: owner.localId },
      owner.idToken,
    );
    assert.equal(matching.body.error, undefined, `${name}: ${JSON.stringify(matching.body)}`);
  }
});

test("postPurchase liga la escritura vigente al expectedUid", async () => {
  const owner = await verifiedEmailToken();
  const otherAccount = await verifiedEmailToken();
  const businessId = await createBusinessAs(owner, "Negocio sync con identidad estable");
  const request = minimalPurchaseRequest(businessId);
  request.expectedUid = otherAccount.localId;

  const rejected = await callCallable("postPurchase", request, owner.idToken);
  assert.equal(rejected.body.error?.status, "UNAUTHENTICATED", JSON.stringify(rejected.body));
  assert.equal(rejected.body.error?.message, "AUTH_IDENTITY_CHANGED");
  const purchaseRef = db.doc(
    `businesses/${businessId}/purchases/${request.document.purchaseId}`,
  );
  assert.equal((await purchaseRef.get()).exists, false);

  request.expectedUid = owner.localId;
  const accepted = await callCallable("postPurchase", request, owner.idToken);
  assert.equal(accepted.body.error, undefined, JSON.stringify(accepted.body));
  assert.equal((await purchaseRef.get()).exists, true);

  const rejectedPull = await callCallable(
    "listChanges",
    { businessId, sinceSeq: 0, limit: 100, expectedUid: otherAccount.localId },
    owner.idToken,
  );
  assert.equal(rejectedPull.body.error?.status, "UNAUTHENTICATED");
  assert.equal(rejectedPull.body.error?.message, "AUTH_IDENTITY_CHANGED");
  const acceptedPull = await callCallable(
    "listChanges",
    { businessId, sinceSeq: 0, limit: 100, expectedUid: owner.localId },
    owner.idToken,
  );
  assert.equal(acceptedPull.body.error, undefined, JSON.stringify(acceptedPull.body));
  assert.equal(acceptedPull.body.result.changes.length, 1);
});

test("invitar → listar → aceptar crea la membresía con el rol invitado (e idempotente)", async () => {
  const owner = await verifiedEmailToken();
  const guest = await verifiedEmailToken();
  const businessId = await createBusinessAs(owner, "Inversiones Demo");

  const invite = await callCallable(
    "inviteMember",
    { businessId, email: guest.email.toUpperCase(), role: "OPERATOR" },
    owner.idToken,
  );
  assert.equal(invite.body.error, undefined, JSON.stringify(invite.body));
  assert.deepEqual(invite.body.result, { ok: true });

  const businessInvitations = await callCallable(
    "listBusinessInvitations",
    { businessId },
    owner.idToken,
  );
  const pending = businessInvitations.body.result.invitations;
  assert.equal(pending.length, 1);
  assert.equal(pending[0].email, guest.email);
  assert.equal(pending[0].role, "OPERATOR");
  assert.equal(pending[0].status, "PENDING");
  assert.ok(pending[0].expiresAtMillis > Date.now());

  const myInvitations = await callCallable("listMyInvitations", {}, guest.idToken);
  const own = myInvitations.body.result.invitations.find((i) => i.businessId === businessId);
  assert.equal(own.displayName, "Inversiones Demo");
  assert.equal(own.role, "OPERATOR");

  const accepted = await callCallable("acceptInvitation", { businessId }, guest.idToken);
  assert.equal(accepted.body.error, undefined, JSON.stringify(accepted.body));
  assert.deepEqual(accepted.body.result, { businessId, role: "OPERATOR" });

  const member = await db.doc(`businesses/${businessId}/members/${guest.localId}`).get();
  assert.equal(member.data().role, "OPERATOR");
  assert.equal(member.data().addedVia, "invitation");
  const invitation = await db
    .doc(`businesses/${businessId}/invitations/${sha256(guest.email)}`)
    .get();
  assert.equal(invitation.data().status, "ACCEPTED");

  // Una re-invitación concurrente o tardía no puede volver PENDING una invitación ya
  // aceptada ni borrar su rastro acceptedBy.
  const reinvite = await callCallable(
    "inviteMember",
    { businessId, email: guest.email, role: "READER" },
    owner.idToken,
  );
  assert.equal(reinvite.body.error?.status, "ALREADY_EXISTS");
  assert.equal(reinvite.body.error?.message, "INVITATION_ALREADY_ACCEPTED");
  const afterReinvite = await invitation.ref.get();
  assert.equal(afterReinvite.data().status, "ACCEPTED");
  assert.equal(afterReinvite.data().acceptedBy, guest.localId);
  assert.equal(afterReinvite.data().role, "OPERATOR");

  // Re-aceptar siendo miembro es idempotente: devuelve el rol actual sin error.
  const again = await callCallable("acceptInvitation", { businessId }, guest.idToken);
  assert.deepEqual(again.body.result, { businessId, role: "OPERATOR" });

  const members = await callCallable("listMembers", { businessId }, owner.idToken);
  const roles = Object.fromEntries(
    members.body.result.members.map((m) => [m.uid, m.role]),
  );
  assert.equal(roles[owner.localId], "OWNER");
  assert.equal(roles[guest.localId], "OPERATOR");

  // Los listados conservan la respuesta histórica y añaden un cursor opaco. Ninguna
  // llamada puede leer una colección completa sin un techo server-side.
  const firstMembersPage = await callCallable(
    "listMembers",
    { businessId, limit: 1 },
    owner.idToken,
  );
  assert.equal(firstMembersPage.body.error, undefined, JSON.stringify(firstMembersPage.body));
  assert.equal(firstMembersPage.body.result.members.length, 1);
  assert.equal(firstMembersPage.body.result.hasMore, true);
  assert.equal(typeof firstMembersPage.body.result.nextCursor, "string");
  const secondMembersPage = await callCallable(
    "listMembers",
    {
      businessId,
      limit: 1,
      cursor: firstMembersPage.body.result.nextCursor,
    },
    owner.idToken,
  );
  assert.equal(secondMembersPage.body.error, undefined, JSON.stringify(secondMembersPage.body));
  assert.equal(secondMembersPage.body.result.members.length, 1);
  assert.notEqual(
    firstMembersPage.body.result.members[0].uid,
    secondMembersPage.body.result.members[0].uid,
  );
  assert.equal(secondMembersPage.body.result.hasMore, false);
  assert.equal(secondMembersPage.body.result.nextCursor, null);
});

test("inviteMember limita durablemente y un retry idéntico no consume cuota ni renueva", async () => {
  const owner = await verifiedEmailToken();
  const guest = await verifiedEmailToken();
  const businessId = await createBusinessAs(owner, "Negocio Cuotas");
  const request = { businessId, email: guest.email, role: "READER" };
  const { invitationRateLimitDocumentId } = await import("../membership.js");
  const rateRefs = [
    ["ACTOR", owner.localId],
    ["BUSINESS", businessId],
    ["RECIPIENT", guest.email],
  ].map(([scope, value]) => db.doc(
    `invitationRateLimits/${invitationRateLimitDocumentId(scope, value)}`,
  ));

  const first = await callCallable("inviteMember", request, owner.idToken);
  assert.equal(first.body.error, undefined, JSON.stringify(first.body));
  const invitationRef = db.doc(
    `businesses/${businessId}/invitations/${sha256(guest.email)}`,
  );
  const invitationBefore = await invitationRef.get();
  const ratesBefore = await Promise.all(rateRefs.map((ref) => ref.get()));
  for (const snapshot of ratesBefore) {
    assert.equal(snapshot.exists, true);
    assert.equal(snapshot.data().schemaVersion, 1);
    assert.equal(snapshot.data().count, 1);
    assert.ok(snapshot.data().expiresAt.toMillis() > Date.now());
    assert.equal("uid" in snapshot.data(), false);
    assert.equal("email" in snapshot.data(), false);
    assert.equal("businessId" in snapshot.data(), false);
  }

  const replay = await callCallable("inviteMember", request, owner.idToken);
  assert.equal(replay.body.error, undefined, JSON.stringify(replay.body));
  const invitationAfter = await invitationRef.get();
  assert.equal(
    invitationAfter.data().createdAt.toMillis(),
    invitationBefore.data().createdAt.toMillis(),
  );
  assert.equal(
    invitationAfter.data().expiresAt.toMillis(),
    invitationBefore.data().expiresAt.toMillis(),
  );
  const ratesAfter = await Promise.all(rateRefs.map((ref) => ref.get()));
  assert.deepEqual(ratesAfter.map((snapshot) => snapshot.data().count), [1, 1, 1]);
});

test("inviteMember no deja PENDING a un email ya miembro y consume una redundante legacy", async () => {
  const owner = await verifiedEmailToken();
  const member = await verifiedEmailToken();
  const businessId = await createBusinessAs(owner, "Negocio sin reinvitación lateral");
  const memberRef = db.doc(`businesses/${businessId}/members/${member.localId}`);
  const invitationRef = db.doc(
    `businesses/${businessId}/invitations/${sha256(member.email)}`,
  );
  await memberRef.set({
    uid: member.localId,
    email: member.email,
    role: "ADMIN",
  });

  const absent = await callCallable(
    "inviteMember",
    { businessId, email: member.email, role: "ADMIN" },
    owner.idToken,
  );
  assert.equal(absent.body.error?.status, "ALREADY_EXISTS");
  assert.equal(absent.body.error?.message, "INVITATION_ALREADY_ACCEPTED");
  assert.equal((await invitationRef.get()).exists, false);

  await invitationRef.set({
    email: member.email,
    role: "ADMIN",
    status: "PENDING",
    invitedBy: owner.localId,
    businessId,
    businessDisplayName: "Negocio sin reinvitación lateral",
    createdAt: Timestamp.fromMillis(Date.now()),
    expiresAt: Timestamp.fromMillis(Date.now() + 24 * 60 * 60 * 1000),
  });
  const redundant = await callCallable(
    "inviteMember",
    { businessId, email: member.email, role: "ADMIN" },
    owner.idToken,
  );
  assert.equal(redundant.body.error?.status, "ALREADY_EXISTS");
  const cancelled = await invitationRef.get();
  assert.equal(cancelled.data().status, "CANCELLED");
  assert.equal(cancelled.data().cancellationReason, "ALREADY_MEMBER");
  assert.equal(cancelled.data().cancelledBy, owner.localId);
  assert.ok(cancelled.data().cancelledAt.toMillis() > 0);

  await invitationRef.set({
    email: member.email,
    role: "ADMIN",
    status: "PENDING",
    invitedBy: owner.localId,
    businessId,
    businessDisplayName: "Negocio sin reinvitación lateral",
    createdAt: Timestamp.fromMillis(Date.now()),
    expiresAt: Timestamp.fromMillis(Date.now() + 24 * 60 * 60 * 1000),
  });
  const idempotentAcceptance = await callCallable(
    "acceptInvitation",
    { businessId },
    member.idToken,
  );
  assert.equal(
    idempotentAcceptance.body.error,
    undefined,
    JSON.stringify(idempotentAcceptance.body),
  );
  const cancelledOnAcceptance = await invitationRef.get();
  assert.equal(cancelledOnAcceptance.data().status, "CANCELLED");
  assert.equal(cancelledOnAcceptance.data().cancellationReason, "ALREADY_MEMBER");
  assert.equal(cancelledOnAcceptance.data().cancelledBy, member.localId);

  // Una invitación redundante legacy que aparezca sin pasar por inviteMember tampoco puede
  // sobrevivir a la baja y convertirse en una vía de reingreso.
  await invitationRef.set({
    email: member.email,
    role: "ADMIN",
    status: "PENDING",
    invitedBy: owner.localId,
    businessId,
    businessDisplayName: "Negocio sin reinvitación lateral",
    createdAt: Timestamp.fromMillis(Date.now()),
    expiresAt: Timestamp.fromMillis(Date.now() + 24 * 60 * 60 * 1000),
  });
  const removed = await callCallable(
    "removeMember",
    { businessId, uid: member.localId },
    owner.idToken,
  );
  assert.equal(removed.body.error, undefined, JSON.stringify(removed.body));
  const cancelledOnRemoval = await invitationRef.get();
  assert.equal(cancelledOnRemoval.data().status, "CANCELLED");
  assert.equal(cancelledOnRemoval.data().cancellationReason, "MEMBER_REMOVED");
  assert.equal(cancelledOnRemoval.data().cancelledBy, owner.localId);
  assert.ok(cancelledOnRemoval.data().cancelledAt.toMillis() > 0);
  const reentry = await callCallable("acceptInvitation", { businessId }, member.idToken);
  assert.equal(reentry.body.error?.message, "INVITATION_NOT_PENDING");
  const { membershipQuotaCounterDocumentId } = await import("../membership.js");
  assert.equal(
    (await db.doc(
      `membershipQuotaCounters/${membershipQuotaCounterDocumentId(member.localId)}`,
    ).get()).exists,
    false,
  );
});

test("inviteMember rechaza atómicamente al agotar la cuota durable del destinatario", async () => {
  const owner = await verifiedEmailToken();
  const guest = await verifiedEmailToken();
  const businessId = await createBusinessAs(owner, "Negocio Límite Destinatario");
  const {
    INVITATION_RATE_POLICIES,
    invitationRateLimitDocumentId,
  } = await import("../membership.js");
  const recipientRateRef = db.doc(
    `invitationRateLimits/${invitationRateLimitDocumentId("RECIPIENT", guest.email)}`,
  );
  const now = Date.now();
  await recipientRateRef.set({
    schemaVersion: 1,
    scope: "RECIPIENT",
    count: INVITATION_RATE_POLICIES.RECIPIENT.limit,
    windowStartedAt: Timestamp.fromMillis(now - 1_000),
    expiresAt: Timestamp.fromMillis(now + 2 * 24 * 60 * 60 * 1000),
  });
  try {
    const denied = await callCallable(
      "inviteMember",
      { businessId, email: guest.email, role: "READER" },
      owner.idToken,
    );
    assert.equal(denied.body.error?.status, "RESOURCE_EXHAUSTED");
    assert.equal(denied.body.error?.message, "INVITATION_RATE_LIMIT");
    assert.equal(denied.body.error?.details?.scope, "RECIPIENT");
    assert.ok(denied.body.error?.details?.retryAfterMillis > 0);
    assert.equal(
      (await db.doc(
        `businesses/${businessId}/invitations/${sha256(guest.email)}`,
      ).get()).exists,
      false,
    );
  } finally {
    await recipientRateRef.delete();
  }
});

test("aceptar con otro email no revela invitaciones ajenas y siempre responde NOT_FOUND", async () => {
  const owner = await verifiedEmailToken();
  const invited = await verifiedEmailToken();
  const outsider = await verifiedEmailToken();
  const businessId = await createBusinessAs(owner, "Negocio A");
  const emptyBusinessId = await createBusinessAs(owner, "Negocio Vacío");

  await callCallable(
    "inviteMember",
    { businessId, email: invited.email, role: "READER" },
    owner.idToken,
  );

  const mismatch = await callCallable("acceptInvitation", { businessId }, outsider.idToken);
  assert.equal(mismatch.body.error?.status, "NOT_FOUND");
  assert.equal(mismatch.body.error?.message, "INVITATION_NOT_FOUND");

  const decline = await callCallable("declineInvitation", { businessId }, outsider.idToken);
  assert.equal(decline.body.error?.status, "NOT_FOUND");
  assert.equal(decline.body.error?.message, "INVITATION_NOT_FOUND");

  const notFound = await callCallable(
    "acceptInvitation",
    { businessId: emptyBusinessId },
    outsider.idToken,
  );
  assert.equal(notFound.body.error?.status, "NOT_FOUND");
  assert.equal(notFound.body.error?.message, "INVITATION_NOT_FOUND");
});

test("un outsider no puede usar inviteMember para enumerar el email-lock", async () => {
  const owner = await verifiedEmailToken();
  const outsider = await verifiedEmailToken();
  const target = await verifiedEmailToken();
  const businessId = await createBusinessAs(owner, "Negocio sin oráculo de lock");
  const request = { businessId, email: target.email, role: "READER" };

  const withoutLock = await callCallable("inviteMember", request, outsider.idToken);
  assert.equal(withoutLock.body.error?.status, "PERMISSION_DENIED");
  assert.equal(withoutLock.body.error?.message, "NOT_A_MEMBER");

  const { accountDeletionEmailLockRef } = await import("../common.js");
  const lock = accountDeletionEmailLockRef(target.email);
  await lock.set({
    schemaVersion: 1,
    blocksInvitations: true,
    expiresAt: Timestamp.fromMillis(Date.now() + 60_000),
  });
  try {
    const withLock = await callCallable("inviteMember", request, outsider.idToken);
    assert.equal(withLock.body.error?.status, "PERMISSION_DENIED");
    assert.equal(withLock.body.error?.message, "NOT_A_MEMBER");
  } finally {
    await lock.delete();
  }
});

test("invitación expirada no se puede aceptar (INVITATION_EXPIRED)", async () => {
  const owner = await verifiedEmailToken();
  const guest = await verifiedEmailToken();
  const businessId = await createBusinessAs(owner, "Negocio Expira");

  // Siembra una invitación PENDING ya vencida.
  await db.doc(`businesses/${businessId}/invitations/${sha256(guest.email)}`).set({
    email: guest.email,
    role: "OPERATOR",
    status: "PENDING",
    invitedBy: owner.localId,
    businessId,
    businessDisplayName: "Negocio Expira",
    createdAt: Timestamp.fromMillis(Date.now() - 8 * 24 * 60 * 60 * 1000),
    expiresAt: Timestamp.fromMillis(Date.now() - 60_000),
  });

  const { body } = await callCallable("acceptInvitation", { businessId }, guest.idToken);
  assert.equal(body.error?.status, "FAILED_PRECONDITION");
  assert.equal(body.error?.message, "INVITATION_EXPIRED");
});

test("aceptar invitación también respeta la cuota máxima de negocios", async () => {
  const owner = await verifiedEmailToken();
  const guest = await verifiedEmailToken();
  const batch = db.batch();
  for (let index = 0; index < 20; index += 1) {
    const existingBusinessId = randomUUID();
    createdBusinesses.add(existingBusinessId);
    batch.set(db.doc(`businesses/${existingBusinessId}`), {
      businessId: existingBusinessId,
      displayName: `Membresía existente ${index}`,
    });
    batch.set(db.doc(`businesses/${existingBusinessId}/members/${guest.localId}`), {
      uid: guest.localId,
      email: guest.email,
      role: "READER",
    });
  }
  await batch.commit();

  const businessId = await createBusinessAs(owner, "Negocio Invitación 21");
  const invitationRef = db.doc(
    `businesses/${businessId}/invitations/${sha256(guest.email)}`,
  );
  await invitationRef.set({
    email: guest.email,
    role: "READER",
    status: "PENDING",
    invitedBy: owner.localId,
    businessId,
    businessDisplayName: "Negocio Invitación 21",
    createdAt: Timestamp.fromMillis(Date.now()),
    expiresAt: Timestamp.fromMillis(Date.now() + 24 * 60 * 60 * 1000),
  });

  const denied = await callCallable("acceptInvitation", { businessId }, guest.idToken);
  assert.equal(denied.body.error?.status, "RESOURCE_EXHAUSTED");
  assert.equal(denied.body.error?.message, "BUSINESS_QUOTA");
  assert.equal(
    (await db.doc(`businesses/${businessId}/members/${guest.localId}`).get()).exists,
    false,
  );
  assert.equal((await invitationRef.get()).data().status, "PENDING");
});

test("dos aceptaciones concurrentes en 19 membresías no crean la número 21", async () => {
  const ownerA = await verifiedEmailToken();
  const ownerB = await verifiedEmailToken();
  const guest = await verifiedEmailToken();
  const batch = db.batch();
  for (let index = 0; index < 19; index += 1) {
    const legacyBusinessId = randomUUID();
    createdBusinesses.add(legacyBusinessId);
    batch.set(db.doc(`businesses/${legacyBusinessId}`), {
      businessId: legacyBusinessId,
      displayName: `Aceptación legacy ${index}`,
    });
    batch.set(db.doc(`businesses/${legacyBusinessId}/members/${guest.localId}`), {
      uid: guest.localId,
      email: guest.email,
      role: "READER",
    });
  }
  await batch.commit();
  const businessIds = [
    await createBusinessAs(ownerA, "Aceptación concurrente A"),
    await createBusinessAs(ownerB, "Aceptación concurrente B"),
  ];
  for (const businessId of businessIds) {
    await db.doc(`businesses/${businessId}/invitations/${sha256(guest.email)}`).set({
      email: guest.email,
      role: "READER",
      status: "PENDING",
      invitedBy: businessId === businessIds[0] ? ownerA.localId : ownerB.localId,
      businessId,
      businessDisplayName: "Aceptación concurrente",
      createdAt: Timestamp.fromMillis(Date.now()),
      expiresAt: Timestamp.fromMillis(Date.now() + 24 * 60 * 60 * 1000),
    });
  }

  const results = await Promise.all(businessIds.map((businessId) =>
    callCallable("acceptInvitation", { businessId }, guest.idToken)
  ));
  assert.equal(results.filter(({ body }) => body.error === undefined).length, 1);
  assert.equal(
    results.filter(({ body }) => body.error?.message === "BUSINESS_QUOTA").length,
    1,
  );
  assert.equal(
    (await db.collectionGroup("members")
      .where("uid", "==", guest.localId)
      .count()
      .get()).data().count,
    20,
  );
  const invitationStates = await Promise.all(businessIds.map(async (businessId) =>
    (await db.doc(
      `businesses/${businessId}/invitations/${sha256(guest.email)}`,
    ).get()).data().status
  ));
  assert.deepEqual(invitationStates.sort(), ["ACCEPTED", "PENDING"]);
});

test("declinar bloquea una aceptación posterior (INVITATION_NOT_PENDING)", async () => {
  const owner = await verifiedEmailToken();
  const guest = await verifiedEmailToken();
  const businessId = await createBusinessAs(owner, "Negocio Declina");

  await callCallable(
    "inviteMember",
    { businessId, email: guest.email, role: "READER" },
    owner.idToken,
  );
  const declined = await callCallable("declineInvitation", { businessId }, guest.idToken);
  assert.equal(declined.body.error, undefined, JSON.stringify(declined.body));

  const invitation = await db
    .doc(`businesses/${businessId}/invitations/${sha256(guest.email)}`)
    .get();
  assert.equal(invitation.data().status, "DECLINED");

  const accepted = await callCallable("acceptInvitation", { businessId }, guest.idToken);
  assert.equal(accepted.body.error?.status, "FAILED_PRECONDITION");
  assert.equal(accepted.body.error?.message, "INVITATION_NOT_PENDING");
});

test("OPERATOR no puede cambiar roles (ROLE_FORBIDDEN)", async () => {
  const owner = await verifiedEmailToken();
  const operator = await verifiedEmailToken();
  const reader = await verifiedEmailToken();
  const businessId = randomUUID();
  await seedBusiness(businessId, [
    { uid: owner.localId, role: "OWNER" },
    { uid: operator.localId, role: "OPERATOR" },
    { uid: reader.localId, role: "READER" },
  ]);

  const { body } = await callCallable(
    "changeMemberRole",
    { businessId, uid: reader.localId, role: "OPERATOR" },
    operator.idToken,
  );
  assert.equal(body.error?.status, "PERMISSION_DENIED");
  assert.equal(body.error?.message, "ROLE_FORBIDDEN");
});

test("ADMIN cambia OPERATOR→READER pero no puede tocar ni asignar OWNER", async () => {
  const owner = await verifiedEmailToken();
  const admin = await verifiedEmailToken();
  const operator = await verifiedEmailToken();
  const businessId = randomUUID();
  await seedBusiness(businessId, [
    { uid: owner.localId, role: "OWNER" },
    { uid: admin.localId, role: "ADMIN" },
    { uid: operator.localId, role: "OPERATOR" },
  ]);

  const changed = await callCallable(
    "changeMemberRole",
    { businessId, uid: operator.localId, role: "READER" },
    admin.idToken,
  );
  assert.equal(changed.body.error, undefined, JSON.stringify(changed.body));
  const member = await db.doc(`businesses/${businessId}/members/${operator.localId}`).get();
  assert.equal(member.data().role, "READER");
  assert.equal(member.data().roleUpdatedBy, admin.localId);

  const touchOwner = await callCallable(
    "changeMemberRole",
    { businessId, uid: owner.localId, role: "ADMIN" },
    admin.idToken,
  );
  assert.equal(touchOwner.body.error?.status, "PERMISSION_DENIED");
  assert.equal(touchOwner.body.error?.message, "ROLE_FORBIDDEN");

  const grantOwner = await callCallable(
    "changeMemberRole",
    { businessId, uid: operator.localId, role: "OWNER" },
    admin.idToken,
  );
  assert.equal(grantOwner.body.error?.status, "PERMISSION_DENIED");
  assert.equal(grantOwner.body.error?.message, "ROLE_FORBIDDEN");

  const inviteOwner = await callCallable(
    "inviteMember",
    { businessId, email: `mb-${randomUUID()}@example.test`, role: "OWNER" },
    admin.idToken,
  );
  assert.equal(inviteOwner.body.error?.status, "PERMISSION_DENIED");
  assert.equal(inviteOwner.body.error?.message, "ROLE_FORBIDDEN");
});

test("el último OWNER no puede ser degradado ni salir/ser eliminado", async () => {
  const owner = await verifiedEmailToken();
  const businessId = randomUUID();
  await seedBusiness(businessId, [{ uid: owner.localId, role: "OWNER" }]);

  const demote = await callCallable(
    "changeMemberRole",
    { businessId, uid: owner.localId, role: "ADMIN" },
    owner.idToken,
  );
  assert.equal(demote.body.error?.status, "FAILED_PRECONDITION");
  assert.equal(demote.body.error?.message, "LAST_OWNER_REQUIRED");

  const leave = await callCallable(
    "removeMember",
    { businessId, uid: owner.localId },
    owner.idToken,
  );
  assert.equal(leave.body.error?.status, "FAILED_PRECONDITION");
  assert.equal(leave.body.error?.message, "LAST_OWNER_REQUIRED");
});

test("removeMember revoca: el expulsado ya no puede respaldar compras", async () => {
  const owner = await verifiedEmailToken();
  const operator = await verifiedEmailToken();
  const businessId = await createBusinessAs(owner, "Negocio Expulsa");

  await callCallable(
    "inviteMember",
    { businessId, email: operator.email, role: "OPERATOR" },
    owner.idToken,
  );
  await callCallable("acceptInvitation", { businessId }, operator.idToken);

  const recorded = await callCallable(
    "postPurchase",
    minimalPurchaseRequest(businessId),
    operator.idToken,
  );
  assert.equal(recorded.body.error, undefined, JSON.stringify(recorded.body));

  const removed = await callCallable(
    "removeMember",
    { businessId, uid: operator.localId },
    owner.idToken,
  );
  assert.deepEqual(removed.body.result, { ok: true });

  const denied = await callCallable(
    "postPurchase",
    minimalPurchaseRequest(businessId),
    operator.idToken,
  );
  assert.equal(denied.body.error?.status, "PERMISSION_DENIED");
  assert.equal(denied.body.error?.message, "NOT_A_MEMBER");

  // La invitación ACCEPTED histórica no bloquea para siempre: tras revocar la membresía,
  // el mismo email puede recibir una invitación nueva con otro rol.
  const reinvited = await callCallable(
    "inviteMember",
    { businessId, email: operator.email, role: "READER" },
    owner.idToken,
  );
  assert.equal(reinvited.body.error, undefined, JSON.stringify(reinvited.body));
  const invitation = await db
    .doc(`businesses/${businessId}/invitations/${sha256(operator.email)}`)
    .get();
  assert.equal(invitation.data().status, "PENDING");
  assert.equal(invitation.data().acceptedBy, undefined);
  const acceptedAgain = await callCallable("acceptInvitation", { businessId }, operator.idToken);
  assert.deepEqual(acceptedAgain.body.result, { businessId, role: "READER" });
});

test("multi-negocio: vale en ambos; tras abandonar uno, queda denegado ahí", async () => {
  const ownerA = await verifiedEmailToken();
  const ownerB = await verifiedEmailToken();
  const nomad = await verifiedEmailToken();
  const businessA = await createBusinessAs(ownerA, "Zeta Primero");
  const businessB = await createBusinessAs(ownerB, "Alpha Segundo");

  for (const [owner, businessId] of [
    [ownerA, businessA],
    [ownerB, businessB],
  ]) {
    await callCallable(
      "inviteMember",
      { businessId, email: nomad.email, role: "OPERATOR" },
      owner.idToken,
    );
    const accepted = await callCallable("acceptInvitation", { businessId }, nomad.idToken);
    assert.equal(accepted.body.error, undefined, JSON.stringify(accepted.body));
  }

  // listMyMemberships ordena por displayName: Alpha Segundo antes que Zeta Primero.
  const mine = await callCallable("listMyMemberships", {}, nomad.idToken);
  const names = mine.body.result.memberships.map((m) => m.displayName);
  assert.deepEqual(names, ["Alpha Segundo", "Zeta Primero"]);

  const firstMembershipPage = await callCallable(
    "listMyMemberships",
    { limit: 1 },
    nomad.idToken,
  );
  assert.equal(
    firstMembershipPage.body.error,
    undefined,
    JSON.stringify(firstMembershipPage.body),
  );
  assert.equal(firstMembershipPage.body.result.memberships.length, 1);
  assert.equal(firstMembershipPage.body.result.hasMore, true);
  const secondMembershipPage = await callCallable(
    "listMyMemberships",
    { limit: 1, cursor: firstMembershipPage.body.result.nextCursor },
    nomad.idToken,
  );
  assert.equal(
    secondMembershipPage.body.error,
    undefined,
    JSON.stringify(secondMembershipPage.body),
  );
  assert.equal(secondMembershipPage.body.result.memberships.length, 1);
  assert.equal(secondMembershipPage.body.result.hasMore, false);
  assert.deepEqual(
    new Set([
      firstMembershipPage.body.result.memberships[0].businessId,
      secondMembershipPage.body.result.memberships[0].businessId,
    ]),
    new Set([businessA, businessB]),
  );

  const inA = await callCallable(
    "postPurchase",
    minimalPurchaseRequest(businessA),
    nomad.idToken,
  );
  assert.equal(inA.body.error, undefined, JSON.stringify(inA.body));
  const inB = await callCallable(
    "postPurchase",
    minimalPurchaseRequest(businessB),
    nomad.idToken,
  );
  assert.equal(inB.body.error, undefined, JSON.stringify(inB.body));

  // Auto-salida del segundo negocio.
  const left = await callCallable(
    "removeMember",
    { businessId: businessB, uid: nomad.localId },
    nomad.idToken,
  );
  assert.deepEqual(left.body.result, { ok: true });

  const deniedB = await callCallable(
    "postPurchase",
    minimalPurchaseRequest(businessB),
    nomad.idToken,
  );
  assert.equal(deniedB.body.error?.status, "PERMISSION_DENIED");

  const stillA = await callCallable(
    "postPurchase",
    minimalPurchaseRequest(businessA),
    nomad.idToken,
  );
  assert.equal(stillA.body.error, undefined, JSON.stringify(stillA.body));
});

test("listados de invitaciones paginan sin mezclar destinatarios ni negocios", async () => {
  const ownerA = await verifiedEmailToken();
  const ownerB = await verifiedEmailToken();
  const guest = await verifiedEmailToken();
  const other = await verifiedEmailToken();
  const businessA = await createBusinessAs(ownerA, "Paginación A");
  const businessB = await createBusinessAs(ownerB, "Paginación B");

  for (const [owner, businessId, email] of [
    [ownerA, businessA, guest.email],
    [ownerA, businessA, other.email],
    [ownerB, businessB, guest.email],
  ]) {
    const invited = await callCallable(
      "inviteMember",
      { businessId, email, role: "READER" },
      owner.idToken,
    );
    assert.equal(invited.body.error, undefined, JSON.stringify(invited.body));
  }

  const businessFirst = await callCallable(
    "listBusinessInvitations",
    { businessId: businessA, limit: 1 },
    ownerA.idToken,
  );
  assert.equal(businessFirst.body.result.invitations.length, 1);
  assert.equal(businessFirst.body.result.hasMore, true);
  const businessSecond = await callCallable(
    "listBusinessInvitations",
    {
      businessId: businessA,
      limit: 1,
      cursor: businessFirst.body.result.nextCursor,
    },
    ownerA.idToken,
  );
  assert.equal(businessSecond.body.error, undefined, JSON.stringify(businessSecond.body));
  assert.equal(businessSecond.body.result.invitations.length, 1);
  assert.equal(businessSecond.body.result.hasMore, false);
  assert.deepEqual(
    new Set([
      businessFirst.body.result.invitations[0].email,
      businessSecond.body.result.invitations[0].email,
    ]),
    new Set([guest.email, other.email]),
  );

  const mineFirst = await callCallable("listMyInvitations", { limit: 1 }, guest.idToken);
  assert.equal(mineFirst.body.error, undefined, JSON.stringify(mineFirst.body));
  assert.equal(mineFirst.body.result.invitations.length, 1);
  assert.equal(mineFirst.body.result.hasMore, true);
  const mineSecond = await callCallable(
    "listMyInvitations",
    { limit: 1, cursor: mineFirst.body.result.nextCursor },
    guest.idToken,
  );
  assert.equal(mineSecond.body.error, undefined, JSON.stringify(mineSecond.body));
  assert.equal(mineSecond.body.result.invitations.length, 1);
  assert.equal(mineSecond.body.result.hasMore, false);
  assert.deepEqual(
    new Set([
      mineFirst.body.result.invitations[0].businessId,
      mineSecond.body.result.invitations[0].businessId,
    ]),
    new Set([businessA, businessB]),
  );

  const invalidLimit = await callCallable(
    "listBusinessInvitations",
    { businessId: businessA, limit: 101 },
    ownerA.idToken,
  );
  assert.equal(invalidLimit.body.error?.status, "INVALID_ARGUMENT");
  assert.equal(invalidLimit.body.error?.message, "LIST_LIMIT");
});

test("reglas: el invitado no lee directo y obtiene solo las suyas por callable", async () => {
  const owner = await verifiedEmailToken();
  const guest = await verifiedEmailToken();
  const other = await verifiedEmailToken();
  const businessId = await createBusinessAs(owner, "Negocio Reglas");

  await callCallable(
    "inviteMember",
    { businessId, email: guest.email, role: "READER" },
    owner.idToken,
  );
  await callCallable(
    "inviteMember",
    { businessId, email: other.email, role: "READER" },
    owner.idToken,
  );

  const { initializeApp, deleteApp: deleteClientApp } = await import("firebase/app");
  const {
    getAuth: getClientAuth,
    connectAuthEmulator,
    signInWithEmailAndPassword,
  } = await import("firebase/auth");
  const {
    getFirestore: getClientFirestore,
    connectFirestoreEmulator,
    doc,
    getDoc,
  } = await import("firebase/firestore");

  const clientApp = initializeApp(
    { projectId: PROJECT, apiKey: "demo-api-key", authDomain: "localhost" },
    `client-${randomUUID()}`,
  );
  const auth = getClientAuth(clientApp);
  connectAuthEmulator(auth, "http://localhost:9099", { disableWarnings: true });
  const firestore = getClientFirestore(clientApp);
  connectFirestoreEmulator(firestore, FIRESTORE_HOST, FIRESTORE_PORT);
  await signInWithEmailAndPassword(auth, guest.email, PASSWORD);

  const ownPath = `businesses/${businessId}/invitations/${sha256(guest.email)}`;
  const own = await getDoc(doc(firestore, ownPath)).then(
    () => "ALLOWED",
    (error) => (error.code === "permission-denied" ? "DENIED" : `OTRO:${error.code}`),
  );
  assert.equal(own, "DENIED");

  const otherPath = `businesses/${businessId}/invitations/${sha256(other.email)}`;
  const denied = await getDoc(doc(firestore, otherPath)).then(
    () => "ALLOWED",
    (error) => (error.code === "permission-denied" ? "DENIED" : `OTRO:${error.code}`),
  );
  assert.equal(denied, "DENIED");

  const callable = await callCallable("listMyInvitations", {}, guest.idToken);
  assert.equal(callable.body.error, undefined, JSON.stringify(callable.body));
  assert.deepEqual(
    callable.body.result.invitations.map((invitation) => invitation.businessId),
    [businessId],
  );

  await deleteClientApp(clientApp);
});
