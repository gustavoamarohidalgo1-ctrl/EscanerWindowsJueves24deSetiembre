// Integración real contra el Emulator Suite (sin credenciales, proyecto demo-facturastock).
// Se ejecuta con: npx firebase emulators:exec --only auth,firestore,functions,storage "npm test"
// Los callables exigen cuentas email/contraseña VERIFICADAS: los helpers crean el usuario
// por REST contra el Auth emulator, lo marcan verificado con Admin SDK y re-ingresan para
// obtener un idToken fresco que ya incluye el claim email_verified.
import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { initializeApp as initializeAdminApp, deleteApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { getAuth } from "firebase-admin/auth";
import { createHash, randomUUID } from "node:crypto";

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

const adminApp = initializeAdminApp({ projectId: PROJECT });
const db = getFirestore(adminApp);
const adminAuth = getAuth(adminApp);

const BUSINESS_ID = "11111111-1111-4111-8111-111111111111";
const BUSINESS_ID_B = "99999999-9999-4999-8999-999999999999";
const PURCHASE_ID = "22222222-2222-4222-8222-222222222222";
const LINE_1 = "33333333-3333-4333-8333-333333333331";
const PRODUCT_1 = "44444444-4444-4444-8444-444444444441";
const LOCATION_ID = "55555555-5555-4555-8555-555555555555";
const MOVEMENT_1 = "66666666-6666-4666-8666-666666666666";
const AUDIT_1 = "77777777-7777-4777-8777-777777777777";
const IDEMPOTENCY_KEY = `sync-purchase:v1:${PURCHASE_ID}`;
const PURCHASE_CLIENT_FIELDS = [
  "adjustmentMinorUnits",
  "adjustmentReason",
  "auditEventIds",
  "businessId",
  "currency",
  "documentNumber",
  "documentSeries",
  "documentType",
  "idempotencyKey",
  "issueDate",
  "lines",
  "movements",
  "otherChargesMinorUnits",
  "postedAt",
  "preparedLogicalHash",
  "purchaseId",
  "status",
  "subtotalMinorUnits",
  "supplierLegalName",
  "supplierRuc",
  "taxMinorUnits",
  "totalMinorUnits",
  "version",
];
const CURRENT_PURCHASE_CLIENT_FIELDS = [
  ...PURCHASE_CLIENT_FIELDS,
  "duplicateOverride",
];
const LINE_FIELDS = [
  "appliedUnitCost",
  "description",
  "discount",
  "inventoryQuantity",
  "position",
  "productId",
  "productName",
  "purchaseLineId",
  "quantity",
  "readUnitCost",
  "taxMinorUnits",
  "totalMinorUnits",
  "unitCode",
];
const CURRENT_LINE_FIELDS = [
  ...LINE_FIELDS,
  "productProvenance",
  "taxEvidence",
  "taxTreatment",
];
const MOVEMENT_FIELDS = [
  "locationId",
  "movementId",
  "occurredAt",
  "productId",
  "purchaseId",
  "purchaseLineId",
  "quantityDelta",
  "type",
  "unitCost",
];
const CURRENT_MOVEMENT_FIELDS = [
  ...MOVEMENT_FIELDS,
  "appliedCostTotal",
  "locationName",
];
const SYNC_CHANGE_FIELDS = [
  "currency",
  "documentNumber",
  "documentSeries",
  "documentType",
  "issueDate",
  "movementSummary",
  "purchaseId",
  "receiptId",
  "seq",
  "status",
  "supplierLegalName",
  "supplierRuc",
  "syncedAt",
  "totalMinorUnits",
];
const VOID_RECORD_FIELDS = [
  "cloudActorUid",
  "impactHash",
  "payload",
  "semanticImpactHash",
  "syncedAt",
];

// Número de documento único por compra de prueba: la unicidad documental
// (ruc|tipo|serie|número) es por negocio y sin esto compras de tests distintos
// chocarían con ALREADY_EXISTS (DOCUMENT_IDENTITY_EXISTS).
let docNumberCounter = 0;
let currentDocNumberCounter = 0;
function nextDocNumber() {
  docNumberCounter += 1;
  return `T${String(docNumberCounter).padStart(5, "0")}`;
}

function nextCurrentDocNumber() {
  currentDocNumberCounter += 1;
  return `9${String(currentDocNumberCounter).padStart(11, "0")}`;
}

function validLine(overrides = {}) {
  return {
    purchaseLineId: LINE_1,
    position: 0,
    productId: PRODUCT_1,
    productName: "ARROZ EXTRA",
    unitCode: "NIU",
    description: "ARROZ EXTRA 5 KG",
    quantity: "2",
    readUnitCost: "10.00",
    taxMinorUnits: 360,
    totalMinorUnits: 2360,
    appliedUnitCost: "10.00",
    inventoryQuantity: "2",
    discount: null,
    ...overrides,
  };
}

function validLineV2(overrides = {}) {
  return {
    ...validLine(),
    taxTreatment: "INCLUDED",
    taxEvidence: { type: "EXPLICIT_AMOUNT", value: "3.60" },
    productProvenance: "EXISTING",
    ...overrides,
  };
}

function validMovement(overrides = {}) {
  return {
    movementId: randomUUID(),
    purchaseLineId: LINE_1,
    productId: PRODUCT_1,
    locationId: LOCATION_ID,
    type: "PURCHASE",
    quantityDelta: "2",
    unitCost: "10.00",
    occurredAt: 1_787_000_000_000,
    ...overrides,
  };
}

function validMovementV3(overrides = {}) {
  return {
    ...validMovement(),
    locationName: "Almacén principal",
    appliedCostTotal: "20.00",
    ...overrides,
  };
}

function validDocument(overrides = {}) {
  const purchaseId = overrides.purchaseId ?? PURCHASE_ID;
  const idempotencyKey = overrides.idempotencyKey ?? `sync-purchase:v1:${purchaseId}`;
  return {
    version: 1,
    purchaseId,
    businessId: BUSINESS_ID,
    status: "POSTED",
    documentType: "INVOICE",
    documentSeries: "F001",
    documentNumber: "000123",
    issueDate: "2026-08-14",
    currency: "PEN",
    supplierRuc: "20123456789",
    supplierLegalName: "PROVEEDOR DEMO SAC",
    subtotalMinorUnits: 2000,
    taxMinorUnits: 360,
    otherChargesMinorUnits: 0,
    totalMinorUnits: 2360,
    adjustmentMinorUnits: null,
    adjustmentReason: null,
    preparedLogicalHash: "a".repeat(64),
    postedAt: 1_787_000_000_000,
    idempotencyKey,
    lines: [validLine()],
    movements: [validMovement()],
    auditEventIds: [randomUUID()],
    ...overrides,
  };
}

function validDocumentV2(overrides = {}) {
  const legacy = validDocument(overrides);
  return {
    ...legacy,
    version: 2,
    lines: overrides.lines ?? [validLineV2()],
    duplicateOverride: overrides.duplicateOverride ?? null,
  };
}

function validDocumentV3(overrides = {}) {
  const taxAudit = validDocumentV2(overrides);
  return {
    ...taxAudit,
    version: 3,
    movements: overrides.movements ?? [validMovementV3()],
  };
}

function validRequest(overrides = {}) {
  const document = overrides.document ?? validDocument();
  return {
    businessId: document.businessId,
    idempotencyKey: document.idempotencyKey,
    operationType: "SYNC_PURCHASE",
    payloadVersion: 2,
    document,
    ...overrides,
  };
}

function validRequestV3(overrides = {}) {
  const document = overrides.document ?? validDocumentV2();
  return {
    businessId: document.businessId,
    idempotencyKey: document.idempotencyKey,
    operationType: "SYNC_PURCHASE",
    payloadVersion: 3,
    document,
    ...overrides,
  };
}

function validRequestV4(overrides = {}) {
  const document = overrides.document ?? validDocumentV3();
  return {
    businessId: document.businessId,
    idempotencyKey: document.idempotencyKey,
    operationType: "SYNC_PURCHASE",
    payloadVersion: 4,
    document,
    ...overrides,
  };
}

function validVoidImpact(overrides = {}) {
  return {
    productId: PRODUCT_1,
    locationId: LOCATION_ID,
    currentQuantity: "2",
    reversalQuantity: "-2",
    resultingQuantity: "0",
    currentAverageUnitCost: "10.00",
    currency: "PEN",
    balanceVersion: 1,
    negative: false,
    ...overrides,
  };
}

function validVoidPayload(purchaseId, overrides = {}) {
  return {
    version: 1,
    purchaseId,
    impactHash: "b".repeat(64),
    // Principal local estable (BusinessId), deliberadamente distinto del Firebase UID.
    actorId: BUSINESS_ID,
    role: "OWNER",
    reason: "Documento emitido por error material",
    negativeStockPolicy: "ALLOW_WITH_VISIBLE_WARNING",
    averageUnitCostPolicy: "PRESERVE_CURRENT",
    negativeImpactCount: 0,
    impacts: [validVoidImpact()],
    ...overrides,
  };
}

function validVoidRequest(purchaseId, payloadOverrides = {}, requestOverrides = {}) {
  return {
    businessId: BUSINESS_ID,
    idempotencyKey: `sync-purchase-void:v1:${purchaseId}`,
    operationType: "SYNC_PURCHASE_VOID",
    payloadVersion: 1,
    document: JSON.stringify(validVoidPayload(purchaseId, payloadOverrides)),
    ...requestOverrides,
  };
}

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

// Usuario email/contraseña verificado: signUp → updateUser(emailVerified) con Admin SDK →
// signInWithPassword devuelve un idToken fresco que ya incluye email_verified=true.
async function verifiedEmailToken() {
  const email = `pp-${randomUUID()}@example.test`;
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

// Usuario email/contraseña SIN verificar (el claim email_verified queda en false).
async function unverifiedEmailToken() {
  const email = `pp-${randomUUID()}@example.test`;
  const { idToken, localId } = await authRest("accounts:signUp", {
    email,
    password: PASSWORD,
    returnSecureToken: true,
  });
  return { idToken, localId, email };
}

async function callCallable(name, data, token) {
  const headers = { "Content-Type": "application/json" };
  if (token) headers.Authorization = `Bearer ${token}`;
  const response = await fetch(`${FUNCTIONS_URL}/${name}`, {
    method: "POST",
    headers,
    body: JSON.stringify({ data }),
  });
  return { httpStatus: response.status, body: await response.json() };
}

async function seedMembership(uid, role = "OWNER", businessId = BUSINESS_ID) {
  await db.doc(`businesses/${businessId}`).set({ businessId }, { merge: true });
  await db.doc(`businesses/${businessId}/members/${uid}`).set({ uid, role });
}

// Lee un documento con el cliente SDK (reglas aplican). Con email: signInWithEmailAndPassword;
// sin email: anónimo. Devuelve "ALLOWED"/"DENIED".
async function clientRead(path, email = null) {
  const { initializeApp, deleteApp: deleteClientApp } = await import("firebase/app");
  const {
    getAuth: getClientAuth,
    connectAuthEmulator,
    signInAnonymously,
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

  if (email) {
    await signInWithEmailAndPassword(auth, email, PASSWORD);
  } else {
    await signInAnonymously(auth);
  }
  const result = await getDoc(doc(firestore, path)).then(
    () => "ALLOWED",
    (error) => (error.code === "permission-denied" ? "DENIED" : `OTRO:${error.code}`),
  );
  await deleteClientApp(clientApp);
  return result;
}

async function clearBusiness(businessId) {
  const root = db.doc(`businesses/${businessId}`);
  for (const sub of [
    "members",
    "invitations",
    "purchases",
    "stockMovements",
    "auditEvents",
    "sync",
    "syncChanges",
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

before(async () => {
  await clearBusiness(BUSINESS_ID);
  await clearBusiness(BUSINESS_ID_B);
});

after(async () => {
  await clearBusiness(BUSINESS_ID);
  await clearBusiness(BUSINESS_ID_B);
  await deleteApp(adminApp);
});

test("sin token la función rechaza con UNAUTHENTICATED", async () => {
  const { body } = await callCallable("postPurchase", validRequest(), null);
  assert.equal(body.error?.status, "UNAUTHENTICATED");
});

test("email sin verificar rechaza con EMAIL_NOT_VERIFIED aunque sea miembro", async () => {
  const { idToken, localId } = await unverifiedEmailToken();
  await seedMembership(localId);
  const { body } = await callCallable("postPurchase", validRequest(), idToken);
  assert.equal(body.error?.status, "PERMISSION_DENIED");
  assert.equal(body.error?.message, "EMAIL_NOT_VERIFIED");
});

test("verificado sin membresía rechaza con PERMISSION_DENIED", async () => {
  const { idToken } = await verifiedEmailToken();
  const { body } = await callCallable("postPurchase", validRequest(), idToken);
  assert.equal(body.error?.status, "PERMISSION_DENIED");
  assert.equal(body.error?.message, "NOT_A_MEMBER");
});

test("escritura atómica: compra, líneas, movimientos, auditoría y metadata", async () => {
  const { idToken, localId } = await verifiedEmailToken();
  await seedMembership(localId);

  const { body } = await callCallable(
    "postPurchase",
    validRequestV4({
      document: validDocumentV3({
        documentNumber: nextCurrentDocNumber(),
        movements: [validMovementV3({ movementId: MOVEMENT_1 })],
        auditEventIds: [AUDIT_1],
      }),
    }),
    idToken,
  );
  assert.equal(body.error, undefined, JSON.stringify(body));
  assert.equal(body.result.idempotencyKey, IDEMPOTENCY_KEY);
  assert.match(body.result.receiptId, /^rcpt_[0-9a-f]{32}$/);

  const root = db.doc(`businesses/${BUSINESS_ID}`);
  const purchase = await root.collection("purchases").doc(PURCHASE_ID).get();
  assert.ok(purchase.exists);
  assert.equal(purchase.data().idempotencyKey, IDEMPOTENCY_KEY);
  assert.equal(purchase.data().totalMinorUnits, 2360);
  assert.deepEqual(
    Object.keys(purchase.data()).sort(),
    [
      ...CURRENT_PURCHASE_CLIENT_FIELDS,
      "movementSummary",
      "receiptId",
      "seq",
      "syncedAt",
      "syncPayloadHash",
      "inventorySeq",
    ].sort(),
  );
  assert.deepEqual(Object.keys(purchase.data().lines[0]).sort(), CURRENT_LINE_FIELDS.sort());
  assert.deepEqual(
    Object.keys(purchase.data().movements[0]).sort(),
    CURRENT_MOVEMENT_FIELDS.filter((field) => field !== "purchaseId").sort(),
  );
  const lines = await root.collection("purchases").doc(PURCHASE_ID).collection("lines").get();
  assert.equal(lines.size, 1);
  assert.deepEqual(Object.keys(lines.docs[0].data()).sort(), CURRENT_LINE_FIELDS.sort());
  const movements = await root.collection("stockMovements").get();
  assert.equal(movements.size, 1);
  assert.deepEqual(
    Object.keys(movements.docs[0].data()).sort(),
    [...CURRENT_MOVEMENT_FIELDS, "canonicalLocationName", "currency"].sort(),
  );
  const audit = await root.collection("auditEvents").doc(AUDIT_1).get();
  assert.ok(audit.exists);
  const metadata = await root.collection("sync").doc("metadata").get();
  assert.equal(metadata.data().lastPurchaseId, PURCHASE_ID);
  const syncChange = await root.collection("syncChanges").doc(PURCHASE_ID).get();
  assert.equal(syncChange.exists, true);
  assert.deepEqual(Object.keys(syncChange.data()).sort(), SYNC_CHANGE_FIELDS);
  assert.equal(syncChange.data().seq, purchase.data().seq);
  assert.equal(syncChange.data().status, "POSTED");
  assert.equal(syncChange.data().purchaseId, PURCHASE_ID);
  assert.equal(syncChange.data().receiptId, purchase.data().receiptId);
  assert.deepEqual(syncChange.data().movementSummary, purchase.data().movementSummary);
  assert.equal("syncedBy" in syncChange.data(), false);
  assert.equal("cloudActorUid" in syncChange.data(), false);
});

test("rechaza compras cuya geometría de movimientos contradice el hecho POSTED local", async () => {
  const { idToken, localId } = await verifiedEmailToken();
  await seedMembership(localId);
  const cases = [
    {
      expected: "POSTED_AT_REQUIRED",
      overrides: { postedAt: null },
    },
    {
      expected: "LINE_QUANTITY_NON_POSITIVE",
      overrides: { lines: [validLine({ quantity: "0" })] },
    },
    {
      expected: "LINE_UNIT_COST_NEGATIVE",
      overrides: { lines: [validLine({ readUnitCost: "-1" })] },
    },
    {
      expected: "LINE_INVENTORY_QUANTITY_REQUIRED",
      overrides: { lines: [validLine({ inventoryQuantity: null })] },
    },
    {
      expected: "LINE_INVENTORY_QUANTITY_NON_POSITIVE",
      overrides: { lines: [validLine({ inventoryQuantity: "0.0" })] },
    },
    {
      expected: "LINE_APPLIED_COST_REQUIRED",
      overrides: { lines: [validLine({ appliedUnitCost: null })] },
    },
    {
      expected: "LINE_APPLIED_COST_NEGATIVE",
      overrides: { lines: [validLine({ appliedUnitCost: "-1" })] },
    },
    {
      expected: "MOVEMENT_LINE_CARDINALITY",
      overrides: { movements: [] },
    },
    {
      expected: "PURCHASE_MOVEMENT_TYPE",
      overrides: { movements: [validMovement({ type: "ADJUSTMENT" })] },
    },
    {
      expected: "PURCHASE_MOVEMENT_SIGN",
      overrides: { movements: [validMovement({ quantityDelta: "-2" })] },
    },
    {
      expected: "PURCHASE_MOVEMENT_QUANTITY_MISMATCH",
      overrides: { movements: [validMovement({ quantityDelta: "3" })] },
    },
    {
      expected: "PURCHASE_MOVEMENT_COST_REQUIRED",
      overrides: { movements: [validMovement({ unitCost: null })] },
    },
    {
      expected: "PURCHASE_MOVEMENT_COST_MISMATCH",
      overrides: { movements: [validMovement({ unitCost: "10.01" })] },
    },
    {
      expected: "PURCHASE_MOVEMENT_TIMESTAMP_MISMATCH",
      overrides: { movements: [validMovement({ occurredAt: 1_787_000_000_001 })] },
    },
  ];

  for (const entry of cases) {
    const purchaseId = randomUUID();
    const document = validDocument({
      purchaseId,
      documentNumber: nextDocNumber(),
      ...entry.overrides,
    });
    const { body } = await callCallable("postPurchase", validRequest({ document }), idToken);
    assert.equal(body.error?.status, "INVALID_ARGUMENT", JSON.stringify(body));
    assert.equal(body.error?.message, entry.expected, JSON.stringify(body));
    assert.equal(
      (await db.doc(`businesses/${BUSINESS_ID}/purchases/${purchaseId}`).get()).exists,
      false,
    );
  }
});

test("exige exactamente un movimiento por línea aunque el total de arrays coincida", async () => {
  const { idToken, localId } = await verifiedEmailToken();
  await seedMembership(localId);
  const purchaseId = randomUUID();
  const secondLineId = randomUUID();
  const document = validDocument({
    purchaseId,
    documentNumber: nextDocNumber(),
    lines: [
      validLine(),
      validLine({
        purchaseLineId: secondLineId,
        position: 1,
        taxMinorUnits: 0,
        totalMinorUnits: 0,
      }),
    ],
    movements: [validMovement(), validMovement()],
  });

  const { body } = await callCallable("postPurchase", validRequest({ document }), idToken);
  assert.equal(body.error?.status, "INVALID_ARGUMENT", JSON.stringify(body));
  assert.equal(body.error?.message, "MOVEMENT_LINE_CARDINALITY");
  assert.equal(
    (await db.doc(`businesses/${BUSINESS_ID}/purchases/${purchaseId}`).get()).exists,
    false,
  );
});

test("acepta equivalencia decimal exacta y signo negativo solo para nota de crédito", async () => {
  const { idToken, localId } = await verifiedEmailToken();
  await seedMembership(localId);
  const purchaseId = randomUUID();
  const document = validDocumentV3({
    purchaseId,
    documentNumber: nextCurrentDocNumber(),
    documentType: "CREDIT_NOTE",
    lines: [validLineV2({
      quantity: "2.0",
      readUnitCost: "10.000",
      appliedUnitCost: "10.0",
      inventoryQuantity: "2.00",
    })],
    movements: [validMovementV3({
      quantityDelta: "-2.000",
      unitCost: "10.00",
      appliedCostTotal: "20.000",
    })],
  });

  const { body } = await callCallable("postPurchase", validRequestV4({ document }), idToken);
  assert.equal(body.error, undefined, JSON.stringify(body));
  assert.equal(body.result.status, "RECORDED");
});

test("repetición con la misma clave devuelve el mismo acuse sin duplicar", async () => {
  const { idToken, localId } = await verifiedEmailToken();
  await seedMembership(localId);
  const purchaseId = randomUUID();
  const key = `sync-purchase:v1:${purchaseId}`;
  const request = validRequestV4({
    idempotencyKey: key,
    document: validDocumentV3({
      purchaseId,
      idempotencyKey: key,
      documentNumber: nextCurrentDocNumber(),
    }),
  });

  const first = await callCallable("postPurchase", request, idToken);
  const second = await callCallable("postPurchase", request, idToken);

  assert.equal(first.body.result.status, "RECORDED");
  assert.equal(second.body.result.status, "ALREADY_RECORDED");
  assert.equal(first.body.result.receiptId, second.body.result.receiptId);

  const altered = await callCallable(
    "postPurchase",
    validRequestV4({
      idempotencyKey: key,
      document: { ...request.document, supplierLegalName: "OTRO PROVEEDOR DEMO SAC" },
    }),
    idToken,
  );
  assert.equal(altered.body.error?.status, "FAILED_PRECONDITION");
  assert.equal(altered.body.error?.message, "PURCHASE_REPLAY_MISMATCH");

  const purchases = await db
    .doc(`businesses/${BUSINESS_ID}`)
    .collection("purchases")
    .get();
  assert.equal(purchases.docs.filter((doc) => doc.id === purchaseId).length, 1);
});

test("v4 conserva tributación; altas legacy fallan cerradas y sus replays sobreviven", async () => {
  const { idToken, localId } = await verifiedEmailToken();
  await seedMembership(localId);
  const purchaseId = randomUUID();
  const idempotencyKey = `sync-purchase:v1:${purchaseId}`;
  const request = validRequestV4({
    document: validDocumentV3({
      purchaseId,
      idempotencyKey,
      documentNumber: nextCurrentDocNumber(),
      auditEventIds: [randomUUID()],
    }),
  });

  const first = await callCallable("postPurchase", request, idToken);
  const replay = await callCallable("postPurchase", request, idToken);
  assert.equal(first.body.error, undefined, JSON.stringify(first.body));
  assert.equal(replay.body.result.status, "ALREADY_RECORDED");
  assert.equal(first.body.result.receiptId, replay.body.result.receiptId);

  const stored = await db.doc(`businesses/${BUSINESS_ID}/purchases/${purchaseId}`).get();
  assert.equal(stored.data().version, 3);
  assert.equal(stored.data().syncedBy, undefined);
  assert.equal(stored.data().duplicateOverride, null);
  assert.deepEqual(stored.data().lines[0].taxEvidence, {
    type: "EXPLICIT_AMOUNT",
    value: "3.60",
  });
  assert.equal(stored.data().lines[0].taxTreatment, "INCLUDED");
  assert.equal(stored.data().lines[0].productProvenance, "EXISTING");

  const legacyV3Request = validRequestV3({
    document: validDocumentV2({
      purchaseId: randomUUID(),
      documentNumber: nextCurrentDocNumber(),
    }),
  });
  const legacyV3 = await callCallable("postPurchase", legacyV3Request, idToken);
  assert.equal(legacyV3.body.error?.message, "INVENTORY_WIRE_MIGRATION_REQUIRED");
  const legacyV2Request = validRequest({
    document: validDocument({ purchaseId: randomUUID(), documentNumber: nextDocNumber() }),
  });
  const legacyV2 = await callCallable("postPurchase", legacyV2Request, idToken);
  assert.equal(legacyV2.body.error?.message, "INVENTORY_WIRE_MIGRATION_REQUIRED");

  // Un despliegue anterior pudo haber confirmado el commit y perder solo el ACK. El contrato
  // vigente no acepta otra alta legacy, pero sí debe devolver el mismo recibo del hecho existente.
  for (const [legacyRequest, seq] of [
    [legacyV2Request, 101],
    [legacyV3Request, 102],
  ]) {
    const receiptId = `rcpt_${String(seq).padStart(32, "0")}`;
    const syncPayloadHash = createHash("sha256")
      .update(JSON.stringify(legacyRequest.document), "utf8")
      .digest("hex");
    await db.doc(
      `businesses/${BUSINESS_ID}/purchases/${legacyRequest.document.purchaseId}`,
    ).set({
      idempotencyKey: legacyRequest.idempotencyKey,
      syncPayloadHash,
      status: "POSTED",
      seq,
      receiptId,
    });
    await db.doc(
      `businesses/${BUSINESS_ID}/syncChanges/${legacyRequest.document.purchaseId}`,
    ).set({
      purchaseId: legacyRequest.document.purchaseId,
      status: "POSTED",
      seq,
      receiptId,
    });
    const legacyReplay = await callCallable("postPurchase", legacyRequest, idToken);
    assert.equal(legacyReplay.body.error, undefined, JSON.stringify(legacyReplay.body));
    assert.equal(legacyReplay.body.result.status, "ALREADY_RECORDED");
    assert.equal(legacyReplay.body.result.receiptId, receiptId);
  }

  const legacyIntoV3 = await callCallable(
    "postPurchase",
    validRequestV3({
      document: validDocument({ purchaseId: randomUUID(), documentNumber: nextCurrentDocNumber() }),
    }),
    idToken,
  );
  assert.equal(legacyIntoV3.body.error?.message, "DOCUMENT_FIELDS");
  const currentIntoV2 = await callCallable(
    "postPurchase",
    validRequest({
      document: validDocumentV2({
        purchaseId: randomUUID(),
        documentNumber: nextCurrentDocNumber(),
      }),
    }),
    idToken,
  );
  assert.equal(currentIntoV2.body.error?.message, "DOCUMENT_FIELDS");
});

test("override v4 autorizado usa slot secundario y persiste auditoría minimizada", async () => {
  const { idToken, localId } = await verifiedEmailToken();
  await seedMembership(localId);
  const targetId = randomUUID();
  const targetKey = `sync-purchase:v1:${targetId}`;
  const documentNumber = nextCurrentDocNumber();
  const targetDocument = validDocumentV3({
    purchaseId: targetId,
    idempotencyKey: targetKey,
    documentNumber,
    auditEventIds: [randomUUID()],
  });
  const targetResult = await callCallable(
    "postPurchase",
    validRequestV4({ idempotencyKey: targetKey, document: targetDocument }),
    idToken,
  );
  assert.equal(targetResult.body.error, undefined, JSON.stringify(targetResult.body));

  const purchaseId = randomUUID();
  const idempotencyKey = `sync-purchase:v1:${purchaseId}`;
  const sourceDraftId = randomUUID();
  const overrideAuditId = randomUUID();
  const postedAuditId = randomUUID();
  const reason = "Recepción separada autorizada con evidencia interna";
  const overrideDocument = validDocumentV3({
    purchaseId,
    idempotencyKey,
    documentNumber,
    auditEventIds: [overrideAuditId, postedAuditId],
    duplicateOverride: {
      existingPurchaseId: targetId,
      sourceDraftId,
      auditEventId: overrideAuditId,
      reason,
    },
  });
  const request = validRequestV4({ idempotencyKey, document: overrideDocument });
  const first = await callCallable("postPurchase", request, idToken);
  await seedMembership(localId, "READER");
  const replay = await callCallable(
    "postPurchase",
    {
      ...request,
      document: {
        ...request.document,
        duplicateOverride: {
          ...request.document.duplicateOverride,
          reason: "Otro texto válido tampoco debe quedar en hash remoto",
        },
      },
    },
    idToken,
  );
  assert.equal(first.body.error, undefined, JSON.stringify(first.body));
  assert.equal(replay.body.result.status, "ALREADY_RECORDED");

  const root = db.doc(`businesses/${BUSINESS_ID}`);
  const stored = await root.collection("purchases").doc(purchaseId).get();
  assert.deepEqual(stored.data().duplicateOverride, {
    existingPurchaseId: targetId,
    sourceDraftId,
    auditEventId: overrideAuditId,
    authorizedRole: "OWNER",
  });
  assert.equal("reason" in stored.data().duplicateOverride, false);
  assert.equal("reasonHash" in stored.data().duplicateOverride, false);
  assert.equal("reasonLength" in stored.data().duplicateOverride, false);
  assert.equal("syncedBy" in stored.data(), false);

  const overrideAudit = await root.collection("auditEvents").doc(overrideAuditId).get();
  assert.equal(overrideAudit.data().eventType, "PURCHASE_DUPLICATE_OVERRIDE");
  assert.equal(overrideAudit.data().existingPurchaseId, targetId);
  assert.equal(overrideAudit.data().authorizedRole, "OWNER");
  assert.equal("reason" in overrideAudit.data(), false);
  assert.equal("reasonHash" in overrideAudit.data(), false);
  assert.equal("reasonLength" in overrideAudit.data(), false);
  assert.equal("uid" in overrideAudit.data(), false);
  assert.equal(JSON.stringify(stored.data()).includes(reason), false);
  assert.equal(JSON.stringify(overrideAudit.data()).includes(reason), false);

  const indexes = await root.collection("documentIndex").get();
  const targetIndexes = indexes.docs.filter((entry) => entry.data().purchaseId === targetId);
  const overrideIndexes = indexes.docs.filter((entry) => entry.data().purchaseId === purchaseId);
  assert.equal(targetIndexes.length, 1);
  assert.equal(overrideIndexes.length, 1);
  assert.equal(overrideIndexes[0].data().slot, "OVERRIDE");
  assert.equal(overrideIndexes[0].data().existingPurchaseId, targetId);
});

test("override v4 revalida rol OWNER ADMIN y rechaza OPERATOR READER", async () => {
  const owner = await verifiedEmailToken();
  await seedMembership(owner.localId, "OWNER");
  const targetId = randomUUID();
  const targetKey = `sync-purchase:v1:${targetId}`;
  const documentNumber = nextCurrentDocNumber();
  await callCallable(
    "postPurchase",
    validRequestV4({
      idempotencyKey: targetKey,
      document: validDocumentV3({
        purchaseId: targetId,
        idempotencyKey: targetKey,
        documentNumber,
      }),
    }),
    owner.idToken,
  );

  for (const [role, expected] of [
    ["OPERATOR", "DUPLICATE_OVERRIDE_ROLE_FORBIDDEN"],
    ["READER", "ROLE_FORBIDDEN"],
  ]) {
    const member = await verifiedEmailToken();
    await seedMembership(member.localId, role);
    const purchaseId = randomUUID();
    const idempotencyKey = `sync-purchase:v1:${purchaseId}`;
    const overrideAuditId = randomUUID();
    const document = validDocumentV3({
      purchaseId,
      idempotencyKey,
      documentNumber,
      auditEventIds: [overrideAuditId, randomUUID()],
      duplicateOverride: {
        existingPurchaseId: targetId,
        sourceDraftId: randomUUID(),
        auditEventId: overrideAuditId,
        reason: "Excepción solicitada sin rol remoto suficiente",
      },
    });
    const { body } = await callCallable(
      "postPurchase",
      validRequestV4({ idempotencyKey, document }),
      member.idToken,
    );
    assert.equal(body.error?.status, "PERMISSION_DENIED");
    assert.equal(body.error?.message, expected);
    assert.equal(
      (await db.doc(`businesses/${BUSINESS_ID}/purchases/${purchaseId}`).get()).exists,
      false,
    );
  }
});

test("override v3 valida motivo 10 a 500 y rechaza actor declarado por cliente", async () => {
  const { idToken, localId } = await verifiedEmailToken();
  await seedMembership(localId);
  const cases = [
    { reason: "demasiado", expected: "DUPLICATE_OVERRIDE_REASON" },
    {
      reason: " motivo válido en longitud pero con espacios ",
      expected: "DUPLICATE_OVERRIDE_REASON",
    },
    { reason: "x".repeat(501), expected: "DUPLICATE_OVERRIDE_REASON" },
    {
      reason: "Motivo válido pero el cliente intenta declarar rol",
      actorRole: "OWNER",
      expected: "DUPLICATE_OVERRIDE_FIELDS",
    },
  ];

  for (const entry of cases) {
    const purchaseId = randomUUID();
    const auditEventId = randomUUID();
    const duplicateOverride = {
      existingPurchaseId: randomUUID(),
      sourceDraftId: randomUUID(),
      auditEventId,
      reason: entry.reason,
    };
    if (entry.actorRole !== undefined) duplicateOverride.actorRole = entry.actorRole;
    const document = validDocumentV2({
      purchaseId,
      documentNumber: nextCurrentDocNumber(),
      auditEventIds: [auditEventId, randomUUID()],
      duplicateOverride,
    });
    const { body } = await callCallable(
      "postPurchase",
      validRequestV3({ document }),
      idToken,
    );
    assert.equal(body.error?.status, "INVALID_ARGUMENT", JSON.stringify(body));
    assert.equal(body.error?.message, entry.expected);
  }
});

test("override v4 distingue target pendiente de identidad remota distinta", async () => {
  const { idToken, localId } = await verifiedEmailToken();
  await seedMembership(localId);

  const missingPurchaseId = randomUUID();
  const missingKey = `sync-purchase:v1:${missingPurchaseId}`;
  const missingAudit = randomUUID();
  const missingDocument = validDocumentV3({
    purchaseId: missingPurchaseId,
    idempotencyKey: missingKey,
    documentNumber: nextCurrentDocNumber(),
    auditEventIds: [missingAudit, randomUUID()],
    duplicateOverride: {
      existingPurchaseId: randomUUID(),
      sourceDraftId: randomUUID(),
      auditEventId: missingAudit,
      reason: "Target local todavía pendiente de respaldo remoto",
    },
  });
  const pending = await callCallable(
    "postPurchase",
    validRequestV4({ idempotencyKey: missingKey, document: missingDocument }),
    idToken,
  );
  assert.equal(pending.body.error?.status, "UNAVAILABLE");
  assert.equal(pending.body.error?.message, "DUPLICATE_TARGET_NOT_SYNCED");

  const targetId = randomUUID();
  const targetKey = `sync-purchase:v1:${targetId}`;
  const targetDocumentNumber = nextCurrentDocNumber();
  await callCallable(
    "postPurchase",
    validRequestV4({
      idempotencyKey: targetKey,
      document: validDocumentV3({
        purchaseId: targetId,
        idempotencyKey: targetKey,
        documentNumber: targetDocumentNumber,
      }),
    }),
    idToken,
  );
  const targetRef = db.doc(`businesses/${BUSINESS_ID}/purchases/${targetId}`);
  await targetRef.update({ status: "DRAFT" });
  const statusPurchaseId = randomUUID();
  const statusKey = `sync-purchase:v1:${statusPurchaseId}`;
  const statusAudit = randomUUID();
  const invalidStatusDocument = validDocumentV3({
    purchaseId: statusPurchaseId,
    idempotencyKey: statusKey,
    documentNumber: targetDocumentNumber,
    auditEventIds: [statusAudit, randomUUID()],
    duplicateOverride: {
      existingPurchaseId: targetId,
      sourceDraftId: randomUUID(),
      auditEventId: statusAudit,
      reason: "Target remoto todavía no tiene estado terminal",
    },
  });
  const invalidStatus = await callCallable(
    "postPurchase",
    validRequestV4({ idempotencyKey: statusKey, document: invalidStatusDocument }),
    idToken,
  );
  assert.equal(invalidStatus.body.error?.status, "FAILED_PRECONDITION");
  assert.equal(invalidStatus.body.error?.message, "DUPLICATE_TARGET_STATUS");
  await targetRef.update({ status: "POSTED" });

  const mismatchPurchaseId = randomUUID();
  const mismatchKey = `sync-purchase:v1:${mismatchPurchaseId}`;
  const mismatchAudit = randomUUID();
  const mismatchDocument = validDocumentV3({
    purchaseId: mismatchPurchaseId,
    idempotencyKey: mismatchKey,
    documentNumber: nextCurrentDocNumber(),
    auditEventIds: [mismatchAudit, randomUUID()],
    duplicateOverride: {
      existingPurchaseId: targetId,
      sourceDraftId: randomUUID(),
      auditEventId: mismatchAudit,
      reason: "Target remoto pertenece a otra identidad documental",
    },
  });
  const mismatch = await callCallable(
    "postPurchase",
    validRequestV4({ idempotencyKey: mismatchKey, document: mismatchDocument }),
    idToken,
  );
  assert.equal(mismatch.body.error?.status, "FAILED_PRECONDITION");
  assert.equal(mismatch.body.error?.message, "DUPLICATE_TARGET_IDENTITY_MISMATCH");
});

test("documento v2 rechaza UNKNOWN y evidencia tributaria contradictoria", async () => {
  const { idToken, localId } = await verifiedEmailToken();
  await seedMembership(localId);
  const cases = [
    {
      expected: "LINE_TAX_TREATMENT",
      line: validLineV2({ taxTreatment: "UNKNOWN" }),
    },
    {
      expected: "LINE_TAX_EVIDENCE_CONFLICT",
      line: validLineV2({
        taxTreatment: "EXEMPT",
        taxEvidence: { type: "NONE", value: null },
      }),
    },
    {
      expected: "LINE_TAX_EVIDENCE_REQUIRED",
      line: validLineV2({ taxEvidence: { type: "NONE", value: null } }),
    },
    {
      expected: "LINE_TAX_EVIDENCE_CONFLICT",
      line: validLineV2({
        taxEvidence: { type: "EXPLICIT_AMOUNT", value: "3.61" },
      }),
    },
  ];

  for (const entry of cases) {
    const purchaseId = randomUUID();
    const document = validDocumentV2({
      purchaseId,
      documentNumber: nextCurrentDocNumber(),
      lines: [entry.line],
    });
    const { body } = await callCallable(
      "postPurchase",
      validRequestV3({ document }),
      idToken,
    );
    assert.equal(body.error?.status, "INVALID_ARGUMENT", JSON.stringify(body));
    assert.equal(body.error?.message, entry.expected);
  }
});

test("documento v2 exige serie y correlativo canónicos sin delimitadores ni normalización", async () => {
  const { idToken, localId } = await verifiedEmailToken();
  await seedMembership(localId);
  const cases = [
    { field: "documentSeries", value: "F|01", expected: "DOCUMENT_SERIES" },
    { field: "documentSeries", value: "f001", expected: "DOCUMENT_SERIES" },
    { field: "documentSeries", value: " F1", expected: "DOCUMENT_SERIES" },
    { field: "documentSeries", value: "ABCDE", expected: "DOCUMENT_SERIES" },
    { field: "documentNumber", value: "12A", expected: "DOCUMENT_NUMBER" },
    { field: "documentNumber", value: " 12", expected: "DOCUMENT_NUMBER" },
    { field: "documentNumber", value: "1234567890123", expected: "DOCUMENT_NUMBER" },
  ];

  for (const entry of cases) {
    const purchaseId = randomUUID();
    const document = validDocumentV2({
      purchaseId,
      documentNumber: nextCurrentDocNumber(),
      [entry.field]: entry.value,
    });
    const { body } = await callCallable(
      "postPurchase",
      validRequestV3({ document }),
      idToken,
    );
    assert.equal(body.error?.status, "INVALID_ARGUMENT", JSON.stringify(body));
    assert.equal(body.error?.message, entry.expected);
  }
});

test("clave no ligada a la compra se rechaza; identidad documental duplicada también", async () => {
  const { idToken, localId } = await verifiedEmailToken();
  await seedMembership(localId);
  const purchaseId = randomUUID();
  const key = `sync-purchase:v1:${purchaseId}`;
  const docNumber = nextDocNumber();
  await callCallable(
    "postPurchase",
    validRequestV4({
      idempotencyKey: key,
      document: validDocumentV3({
        purchaseId,
        idempotencyKey: key,
        documentNumber: nextCurrentDocNumber(),
      }),
    }),
    idToken,
  );

  const otherKey = `sync-purchase:v1:otra-clave-${purchaseId}`;
  const repeat = await callCallable(
    "postPurchase",
    validRequest({
      idempotencyKey: otherKey,
      document: validDocument({ purchaseId, idempotencyKey: otherKey, documentNumber: docNumber }),
    }),
    idToken,
  );
  assert.equal(repeat.body.error?.status, "INVALID_ARGUMENT");
  assert.equal(repeat.body.error?.message, "IDEMPOTENCY_KEY");

  const otherPurchaseId = randomUUID();
  const otherKey2 = `sync-purchase:v1:${otherPurchaseId}`;
  const storedDocument = await db.doc(
    `businesses/${BUSINESS_ID}/purchases/${purchaseId}`,
  ).get();
  const duplicatedDocument = await callCallable(
    "postPurchase",
    validRequestV4({
      idempotencyKey: otherKey2,
      document: validDocumentV3({
        purchaseId: otherPurchaseId,
        idempotencyKey: otherKey2,
        documentNumber: storedDocument.data().documentNumber,
      }),
    }),
    idToken,
  );
  assert.equal(duplicatedDocument.body.error?.status, "ALREADY_EXISTS");
});

test("la identidad documental canónica no colisiona al redistribuir serie y correlativo", async () => {
  const { idToken, localId } = await verifiedEmailToken();
  await seedMembership(localId);
  const firstId = randomUUID();
  const secondId = randomUUID();
  const first = validRequestV4({
    document: validDocumentV3({
      purchaseId: firstId,
      documentSeries: "A1",
      documentNumber: "23",
    }),
  });
  const second = validRequestV4({
    document: validDocumentV3({
      purchaseId: secondId,
      documentSeries: "A",
      documentNumber: "123",
    }),
  });

  const firstResult = await callCallable("postPurchase", first, idToken);
  const secondResult = await callCallable("postPurchase", second, idToken);
  assert.equal(firstResult.body.error, undefined, JSON.stringify(firstResult.body));
  assert.equal(secondResult.body.error, undefined, JSON.stringify(secondResult.body));
  assert.equal(
    (await db.doc(`businesses/${BUSINESS_ID}/purchases/${firstId}`).get()).exists,
    true,
  );
  assert.equal(
    (await db.doc(`businesses/${BUSINESS_ID}/purchases/${secondId}`).get()).exists,
    true,
  );
});

test("IDs globales de movimiento y auditoría no pueden sobrescribirse secuencialmente", async () => {
  const { idToken, localId } = await verifiedEmailToken();
  await seedMembership(localId);
  const originalPurchaseId = randomUUID();
  const movementCollisionPurchaseId = randomUUID();
  const auditCollisionPurchaseId = randomUUID();
  const sharedMovementId = randomUUID();
  const sharedAuditId = randomUUID();
  const original = validRequestV4({
    document: validDocumentV3({
      purchaseId: originalPurchaseId,
      documentNumber: nextCurrentDocNumber(),
      movements: [validMovementV3({ movementId: sharedMovementId })],
      auditEventIds: [sharedAuditId],
    }),
  });
  const originalResult = await callCallable("postPurchase", original, idToken);
  assert.equal(originalResult.body.error, undefined, JSON.stringify(originalResult.body));

  const movementCollision = validRequestV4({
    document: validDocumentV3({
      purchaseId: movementCollisionPurchaseId,
      documentNumber: nextCurrentDocNumber(),
      movements: [validMovementV3({ movementId: sharedMovementId })],
    }),
  });
  const movementResult = await callCallable("postPurchase", movementCollision, idToken);
  assert.equal(movementResult.body.error?.status, "ALREADY_EXISTS");
  assert.equal(movementResult.body.error?.message, "MOVEMENT_ID_EXISTS");

  const auditCollision = validRequestV4({
    document: validDocumentV3({
      purchaseId: auditCollisionPurchaseId,
      documentNumber: nextCurrentDocNumber(),
      auditEventIds: [sharedAuditId],
    }),
  });
  const auditResult = await callCallable("postPurchase", auditCollision, idToken);
  assert.equal(auditResult.body.error?.status, "ALREADY_EXISTS");
  assert.equal(auditResult.body.error?.message, "AUDIT_ID_EXISTS");

  const movement = await db.doc(
    `businesses/${BUSINESS_ID}/stockMovements/${sharedMovementId}`,
  ).get();
  const audit = await db.doc(`businesses/${BUSINESS_ID}/auditEvents/${sharedAuditId}`).get();
  assert.equal(movement.data().purchaseId, originalPurchaseId);
  assert.equal(audit.data().purchaseId, originalPurchaseId);
  assert.equal(
    (await db.doc(`businesses/${BUSINESS_ID}/purchases/${movementCollisionPurchaseId}`).get())
      .exists,
    false,
  );
  assert.equal(
    (await db.doc(`businesses/${BUSINESS_ID}/purchases/${auditCollisionPurchaseId}`).get()).exists,
    false,
  );
});

test("dos compras concurrentes con el mismo movementId registran exactamente una", async () => {
  const { idToken, localId } = await verifiedEmailToken();
  await seedMembership(localId);
  const purchaseIds = [randomUUID(), randomUUID()];
  const sharedMovementId = randomUUID();
  const requests = purchaseIds.map((purchaseId) =>
    validRequestV4({
      document: validDocumentV3({
        purchaseId,
        documentNumber: nextCurrentDocNumber(),
        movements: [validMovementV3({ movementId: sharedMovementId })],
      }),
    }),
  );

  const results = await Promise.all(
    requests.map((request) => callCallable("postPurchase", request, idToken)),
  );
  const winnerIndexes = results
    .map((result, index) => (result.body.error === undefined ? index : null))
    .filter((index) => index !== null);
  const failures = results.filter((result) => result.body.error !== undefined);
  assert.equal(winnerIndexes.length, 1, JSON.stringify(results.map((result) => result.body)));
  assert.equal(failures.length, 1);
  assert.equal(failures[0].body.error?.status, "ALREADY_EXISTS");
  assert.equal(failures[0].body.error?.message, "MOVEMENT_ID_EXISTS");

  const winnerId = purchaseIds[winnerIndexes[0]];
  const storedMovement = await db.doc(
    `businesses/${BUSINESS_ID}/stockMovements/${sharedMovementId}`,
  ).get();
  assert.equal(storedMovement.data().purchaseId, winnerId);
  const storedPurchases = await Promise.all(
    purchaseIds.map((purchaseId) =>
      db.doc(`businesses/${BUSINESS_ID}/purchases/${purchaseId}`).get(),
    ),
  );
  assert.equal(storedPurchases.filter((purchase) => purchase.exists).length, 1);
});

test("dos compras concurrentes con el mismo auditEventId registran exactamente una", async () => {
  const { idToken, localId } = await verifiedEmailToken();
  await seedMembership(localId);
  const purchaseIds = [randomUUID(), randomUUID()];
  const sharedAuditId = randomUUID();
  const requests = purchaseIds.map((purchaseId) =>
    validRequestV4({
      document: validDocumentV3({
        purchaseId,
        documentNumber: nextCurrentDocNumber(),
        auditEventIds: [sharedAuditId],
      }),
    }),
  );

  const results = await Promise.all(
    requests.map((request) => callCallable("postPurchase", request, idToken)),
  );
  const winnerIndexes = results
    .map((result, index) => (result.body.error === undefined ? index : null))
    .filter((index) => index !== null);
  const failures = results.filter((result) => result.body.error !== undefined);
  assert.equal(winnerIndexes.length, 1, JSON.stringify(results.map((result) => result.body)));
  assert.equal(failures.length, 1);
  assert.equal(failures[0].body.error?.status, "ALREADY_EXISTS");
  assert.equal(failures[0].body.error?.message, "AUDIT_ID_EXISTS");

  const winnerId = purchaseIds[winnerIndexes[0]];
  const storedAudit = await db.doc(
    `businesses/${BUSINESS_ID}/auditEvents/${sharedAuditId}`,
  ).get();
  assert.equal(storedAudit.data().purchaseId, winnerId);
  const storedPurchases = await Promise.all(
    purchaseIds.map((purchaseId) =>
      db.doc(`businesses/${BUSINESS_ID}/purchases/${purchaseId}`).get(),
    ),
  );
  assert.equal(storedPurchases.filter((purchase) => purchase.exists).length, 1);
});

test("importes que no reconcilian son INVALID_ARGUMENT y no escriben nada", async () => {
  const { idToken, localId } = await verifiedEmailToken();
  await seedMembership(localId);
  const purchaseId = randomUUID();
  const key = `sync-purchase:v1:${purchaseId}`;

  const broken = validRequest({
    idempotencyKey: key,
    document: validDocument({
      purchaseId,
      idempotencyKey: key,
      totalMinorUnits: 9999,
      documentNumber: nextDocNumber(),
    }),
  });
  const { body } = await callCallable("postPurchase", broken, idToken);
  assert.equal(body.error?.status, "INVALID_ARGUMENT");
  assert.equal(body.error?.message, "TOTAL_MISMATCH");

  const purchase = await db.doc(`businesses/${BUSINESS_ID}/purchases/${purchaseId}`).get();
  assert.ok(!purchase.exists);
});

test("catálogos cerrados rechazan extras en envelope, compra, línea y movimiento", async () => {
  const { idToken, localId } = await verifiedEmailToken();
  await seedMembership(localId);

  const cases = [
    {
      expected: "REQUEST_FIELDS",
      build: () => {
        const request = validRequest({
          document: validDocument({ purchaseId: randomUUID(), documentNumber: nextDocNumber() }),
        });
        return { ...request, debugBypass: true };
      },
    },
    {
      expected: "DOCUMENT_FIELDS",
      build: () => {
        const purchaseId = randomUUID();
        const document = validDocument({
          purchaseId,
          documentNumber: nextDocNumber(),
          privateMetadata: { rawOcr: "no debe persistirse" },
        });
        return validRequest({ document });
      },
    },
    {
      expected: "LINE_FIELDS",
      build: () => {
        const purchaseId = randomUUID();
        const document = validDocument({
          purchaseId,
          documentNumber: nextDocNumber(),
          lines: [validLine({ hidden: { fiscalText: "extra anidado" } })],
        });
        return validRequest({ document });
      },
    },
    {
      expected: "MOVEMENT_FIELDS",
      build: () => {
        const purchaseId = randomUUID();
        const document = validDocument({
          purchaseId,
          documentNumber: nextDocNumber(),
          movements: [validMovement({ extension: { arbitrary: true } })],
        });
        return validRequest({ document });
      },
    },
    {
      expected: "MOVEMENT_PRODUCT_LINK",
      build: () => {
        const purchaseId = randomUUID();
        const document = validDocument({
          purchaseId,
          documentNumber: nextDocNumber(),
          movements: [validMovement({ productId: randomUUID() })],
        });
        return validRequest({ document });
      },
    },
  ];

  for (const entry of cases) {
    const request = entry.build();
    const { body } = await callCallable("postPurchase", request, idToken);
    assert.equal(body.error?.status, "INVALID_ARGUMENT", JSON.stringify(body));
    assert.equal(body.error?.message, entry.expected);
    if (request.document?.purchaseId) {
      const stored = await db
        .doc(`businesses/${BUSINESS_ID}/purchases/${request.document.purchaseId}`)
        .get();
      assert.equal(stored.exists, false);
    }
  }
});

test("fuzz determinista de payloads nunca atraviesa los catálogos cerrados ni escribe", async () => {
  const { idToken, localId } = await verifiedEmailToken();
  await seedMembership(localId);
  let state = 0x46555a5a;
  const nextUint32 = () => {
    state ^= state << 13;
    state ^= state >>> 17;
    state ^= state << 5;
    return state >>> 0;
  };
  const targets = [
    {
      expected: "REQUEST_FIELDS",
      select: (request) => request,
    },
    {
      expected: "DOCUMENT_FIELDS",
      select: (request) => request.document,
    },
    {
      expected: "LINE_FIELDS",
      select: (request) => request.document.lines[0],
    },
    {
      expected: "MOVEMENT_FIELDS",
      select: (request) => request.document.movements[0],
    },
    {
      expected: "LINE_TAX_EVIDENCE_FIELDS",
      select: (request) => request.document.lines[0].taxEvidence,
    },
  ];
  const hostileValues = [
    null,
    Number.MAX_SAFE_INTEGER,
    "\u0000🧾".repeat(8),
    { nested: { fiscalText: "private" } },
    ["unexpected", 1],
  ];
  const purchaseRefs = [];

  for (let sample = 0; sample < 100; sample += 1) {
    const suffix = String(sample + 1).padStart(12, "0");
    const purchaseId = `90000000-0000-4000-8000-${suffix}`;
    const idempotencyKey = `sync-purchase:v1:${purchaseId}`;
    const request = validRequestV3({
      idempotencyKey,
      document: validDocumentV2({
        purchaseId,
        idempotencyKey,
        documentNumber: nextCurrentDocNumber(),
      }),
    });
    const target = targets[nextUint32() % targets.length];
    const extraKey = `__fuzz_${sample}_${nextUint32().toString(16)}`;
    target.select(request)[extraKey] = hostileValues[nextUint32() % hostileValues.length];

    const { body } = await callCallable("postPurchase", request, idToken);
    assert.equal(
      body.error?.status,
      "INVALID_ARGUMENT",
      `seed=0x46555a5a sample=${sample} body=${JSON.stringify(body)}`,
    );
    assert.equal(
      body.error?.message,
      target.expected,
      `seed=0x46555a5a sample=${sample} target=${target.expected}`,
    );
    purchaseRefs.push(db.doc(`businesses/${BUSINESS_ID}/purchases/${purchaseId}`));
  }

  const snapshots = await db.getAll(...purchaseRefs);
  assert.equal(snapshots.filter((snapshot) => snapshot.exists).length, 0);
});

test("el límite documental mide bytes UTF-8 y no unidades UTF-16", async () => {
  const { idToken, localId } = await verifiedEmailToken();
  await seedMembership(localId);
  const purchaseId = randomUUID();
  const document = validDocument({
    purchaseId,
    documentNumber: nextDocNumber(),
    // 250 001 símbolos de cuatro bytes exceden 1 000 000 bytes aunque su length JS sea otro.
    supplierLegalName: "🧾".repeat(250_001),
  });
  const { body } = await callCallable("postPurchase", validRequest({ document }), idToken);
  assert.equal(body.error?.status, "INVALID_ARGUMENT");
  assert.equal(body.error?.message, "DOCUMENT_TOO_LARGE");
  assert.equal(
    (await db.doc(`businesses/${BUSINESS_ID}/purchases/${purchaseId}`).get()).exists,
    false,
  );
});

test("rechaza la proyección Firestore final si movementSummary la lleva sobre 1 MiB", async () => {
  const { idToken, localId } = await verifiedEmailToken();
  const purchaseId = randomUUID();
  const escaped = "\u0001";
  const lines = Array.from({ length: 100 }, (_, position) =>
    validLine({
      purchaseLineId: position === 0 ? LINE_1 : randomUUID(),
      position,
      productName: escaped.repeat(200),
      unitCode: escaped.repeat(32),
      description: escaped.repeat(500),
      taxMinorUnits: position === 0 ? 360 : 0,
      totalMinorUnits: position === 0 ? 2360 : 0,
    }),
  );
  // Base 8 + 100 líneas + 372 movimientos = presupuesto exacto de 480 unidades.
  const movements = Array.from({ length: 372 }, () => validMovement());
  const document = validDocument({
    purchaseId,
    documentNumber: nextDocNumber(),
    lines,
    movements,
    auditEventIds: [],
  });
  const inputBytes = Buffer.byteLength(JSON.stringify(document), "utf8");
  const movementSummary = movements.map((movement) => ({
    productId: movement.productId,
    productName: lines[0].productName,
    type: movement.type,
    quantityDelta: movement.quantityDelta,
  }));
  const projectedBytes = Buffer.byteLength(
    JSON.stringify({
      ...document,
      syncPayloadHash: "f".repeat(64),
      seq: Number.MAX_SAFE_INTEGER,
      movementSummary,
      receiptId: `rcpt_${"f".repeat(32)}`,
      syncedAt: "9999-12-31T23:59:59.999999999Z",
      syncedBy: localId,
    }),
    "utf8",
  );
  assert.ok(inputBytes < 1_000_000, `input inesperado: ${inputBytes}`);
  assert.ok(projectedBytes > 1_048_576, `proyección inesperada: ${projectedBytes}`);

  const { body } = await callCallable("postPurchase", validRequest({ document }), idToken);
  assert.equal(body.error?.status, "INVALID_ARGUMENT");
  assert.equal(body.error?.message, "PERSISTED_DOCUMENT_TOO_LARGE");
  assert.equal(
    (await db.doc(`businesses/${BUSINESS_ID}/purchases/${purchaseId}`).get()).exists,
    false,
  );
});

test("limita syncChanges a 64 KB para que una página no reconstruya el OOM", async () => {
  const { idToken } = await verifiedEmailToken();
  const purchaseId = randomUUID();
  const document = validDocument({
    purchaseId,
    documentNumber: nextDocNumber(),
    lines: [validLine({ productName: "\u0001".repeat(200) })],
    movements: Array.from({ length: 100 }, () => validMovement()),
    auditEventIds: [],
  });
  const { body } = await callCallable("postPurchase", validRequest({ document }), idToken);
  assert.equal(body.error?.status, "INVALID_ARGUMENT");
  assert.equal(body.error?.message, "SYNC_CHANGE_TOO_LARGE");
  assert.equal(
    (await db.doc(`businesses/${BUSINESS_ID}/purchases/${purchaseId}`).get()).exists,
    false,
  );
  assert.equal(
    (await db.doc(`businesses/${BUSINESS_ID}/syncChanges/${purchaseId}`).get()).exists,
    false,
  );
});

test("la suma BigInt detecta un agregado que Number redondearía", async () => {
  const { idToken, localId } = await verifiedEmailToken();
  await seedMembership(localId);
  const purchaseId = randomUUID();
  const positive = 1_000_000_000_000_000;
  const negative = -(positive - 1);
  const totals = [...Array(11).fill(positive), ...Array(11).fill(negative)];
  // Number produce 10 por pérdida de precisión; la suma matemática exacta es 11.
  assert.equal(totals.reduce((sum, value) => sum + value, 0), 10);
  assert.equal(totals.reduce((sum, value) => sum + BigInt(value), 0n), 11n);
  const lines = totals.map((totalMinorUnits, position) =>
    validLine({
      purchaseLineId: randomUUID(),
      position,
      taxMinorUnits: 0,
      totalMinorUnits,
    }),
  );
  const document = validDocument({
    purchaseId,
    documentNumber: nextDocNumber(),
    subtotalMinorUnits: 10,
    taxMinorUnits: 0,
    totalMinorUnits: 10,
    lines,
    movements: [],
    auditEventIds: [],
  });
  const { body } = await callCallable("postPurchase", validRequest({ document }), idToken);
  assert.equal(body.error?.status, "INVALID_ARGUMENT");
  assert.equal(body.error?.message, "TOTAL_MISMATCH");
  assert.equal(
    (await db.doc(`businesses/${BUSINESS_ID}/purchases/${purchaseId}`).get()).exists,
    false,
  );
});

test("la cardinalidad vigente rechaza un grafo sobredimensionado antes de la transacción", async () => {
  const { idToken } = await verifiedEmailToken();
  const purchaseId = randomUUID();
  const movements = Array.from({ length: 475 }, () => validMovementV3());
  const document = validDocumentV3({
    purchaseId,
    documentNumber: nextCurrentDocNumber(),
    movements,
  });
  const { body } = await callCallable("postPurchase", validRequestV4({ document }), idToken);
  assert.equal(body.error?.status, "INVALID_ARGUMENT");
  assert.equal(body.error?.message, "MOVEMENT_LINE_CARDINALITY");
  // No había membresía: obtener el error estructural demuestra que la guarda ocurrió
  // antes de la lectura/transacción de autorización y, por supuesto, no hubo escritura.
  assert.equal(
    (await db.doc(`businesses/${BUSINESS_ID}/purchases/${purchaseId}`).get()).exists,
    false,
  );
});

test("más de 100 líneas supera el límite", async () => {
  const { idToken, localId } = await verifiedEmailToken();
  await seedMembership(localId);
  const purchaseId = randomUUID();
  const key = `sync-purchase:v1:${purchaseId}`;

  const lines = Array.from({ length: 101 }, (_, index) =>
    validLine({
      purchaseLineId: `33333333-3333-4333-8333-${String(index + 100).padStart(12, "0")}`,
      position: index,
    }),
  );
  const tooMany = validRequest({
    idempotencyKey: key,
    document: validDocument({
      purchaseId,
      idempotencyKey: key,
      lines,
      totalMinorUnits: 2360 * 101,
      documentNumber: nextDocNumber(),
    }),
  });
  const { body } = await callCallable("postPurchase", tooMany, idToken);
  assert.equal(body.error?.status, "INVALID_ARGUMENT");
  assert.equal(body.error?.message, "LINES_LIMIT");
});

test("anulación: marca VOIDED, persiste actor cloud, es idempotente y liga su clave", async () => {
  const { idToken, localId } = await verifiedEmailToken();
  await seedMembership(localId);
  const purchaseId = randomUUID();
  const key = `sync-purchase:v1:${purchaseId}`;
  await callCallable(
    "postPurchase",
    validRequestV4({
      idempotencyKey: key,
      document: validDocumentV3({
        purchaseId,
        idempotencyKey: key,
        documentNumber: nextCurrentDocNumber(),
      }),
    }),
    idToken,
  );

  const voidRequest = validVoidRequest(purchaseId);
  const first = await callCallable("postPurchase", voidRequest, idToken);
  assert.equal(first.body.error, undefined, JSON.stringify(first.body));
  assert.equal(first.body.result.status, "RECORDED");

  const purchase = await db.doc(`businesses/${BUSINESS_ID}/purchases/${purchaseId}`).get();
  assert.equal(purchase.data().status, "VOIDED");
  assert.equal(purchase.data().voidReason, "Documento emitido por error material");
  assert.match(purchase.data().voidSemanticImpactHash, /^[0-9a-f]{64}$/);
  assert.notEqual(purchase.data().voidSemanticImpactHash, "b".repeat(64));
  const voidSyncChange = await db.doc(
    `businesses/${BUSINESS_ID}/syncChanges/${purchaseId}`,
  ).get();
  assert.deepEqual(Object.keys(voidSyncChange.data()).sort(), SYNC_CHANGE_FIELDS);
  assert.equal(voidSyncChange.data().status, "VOIDED");
  assert.equal(voidSyncChange.data().seq, purchase.data().seq);
  assert.equal(voidSyncChange.data().receiptId, purchase.data().receiptId);
  assert.equal("voidedBy" in voidSyncChange.data(), false);
  assert.equal("cloudActorUid" in voidSyncChange.data(), false);
  const voidRecord = await purchase.ref.collection("voidRecord").doc("record").get();
  assert.deepEqual(Object.keys(voidRecord.data()).sort(), VOID_RECORD_FIELDS);
  assert.equal(voidRecord.data().cloudActorUid, localId);
  assert.equal(voidRecord.data().impactHash, "b".repeat(64));
  assert.equal(
    voidRecord.data().semanticImpactHash,
    purchase.data().voidSemanticImpactHash,
  );
  assert.equal(voidRecord.data().payload, JSON.stringify(validVoidPayload(purchaseId)));
  assert.deepEqual(JSON.parse(voidRecord.data().payload), validVoidPayload(purchaseId));

  const second = await callCallable("postPurchase", voidRequest, idToken);
  assert.equal(second.body.result.status, "ALREADY_RECORDED");
  assert.equal(first.body.result.receiptId, second.body.result.receiptId);

  // ACK perdido: otro ADMIN vigente puede confirmar el mismo resultado; la identidad del
  // actor histórico permanece en la auditoría, pero no forma parte de la idempotencia.
  const replayActor = await verifiedEmailToken();
  await seedMembership(replayActor.localId, "ADMIN");
  const crossUserReplay = await callCallable(
    "postPurchase",
    voidRequest,
    replayActor.idToken,
  );
  assert.equal(crossUserReplay.body.error, undefined, JSON.stringify(crossUserReplay.body));
  assert.equal(crossUserReplay.body.result.status, "ALREADY_RECORDED");
  assert.equal(first.body.result.receiptId, crossUserReplay.body.result.receiptId);
  assert.equal(
    (await db.doc(`businesses/${BUSINESS_ID}/purchases/${purchaseId}`).get()).data().voidedBy,
    localId,
  );
  await db.doc(`businesses/${BUSINESS_ID}/members/${replayActor.localId}`).update({
    role: "READER",
  });
  const downgradedReplay = await callCallable(
    "postPurchase",
    voidRequest,
    replayActor.idToken,
  );
  assert.equal(downgradedReplay.body.error?.status, "PERMISSION_DENIED");
  assert.equal(downgradedReplay.body.error?.message, "ROLE_FORBIDDEN");

  const alteredReplay = await callCallable(
    "postPurchase",
    validVoidRequest(purchaseId, { reason: "Documento anulado por otra razón válida" }),
    idToken,
  );
  assert.equal(alteredReplay.body.error?.status, "FAILED_PRECONDITION");
  assert.equal(alteredReplay.body.error?.message, "VOID_CONFLICT");

  const invalidKey = await callCallable(
    "postPurchase",
    { ...voidRequest, idempotencyKey: `sync-purchase-void:v1:otra-${purchaseId}` },
    idToken,
  );
  assert.equal(invalidKey.body.error?.status, "INVALID_ARGUMENT");
  assert.equal(invalidKey.body.error?.message, "IDEMPOTENCY_KEY");
});

test("anulación valida que los impactos sean la reversa exacta de la compra cloud", async () => {
  const { idToken, localId } = await verifiedEmailToken();
  await seedMembership(localId);

  const missingImpactPurchaseId = randomUUID();
  const missingImpactKey = `sync-purchase:v1:${missingImpactPurchaseId}`;
  await callCallable(
    "postPurchase",
    validRequestV4({
      idempotencyKey: missingImpactKey,
      document: validDocumentV3({
        purchaseId: missingImpactPurchaseId,
        idempotencyKey: missingImpactKey,
        documentNumber: nextCurrentDocNumber(),
      }),
    }),
    idToken,
  );
  const missing = await callCallable(
    "postPurchase",
    validVoidRequest(missingImpactPurchaseId, { impacts: [] }),
    idToken,
  );
  assert.equal(missing.body.error?.status, "INVALID_ARGUMENT");
  assert.equal(missing.body.error?.message, "VOID_IMPACT_SET_MISMATCH");
  assert.equal(
    (await db.doc(
      `businesses/${BUSINESS_ID}/purchases/${missingImpactPurchaseId}`,
    ).get()).data().status,
    "POSTED",
  );

  const wrongQuantityPurchaseId = randomUUID();
  const wrongQuantityKey = `sync-purchase:v1:${wrongQuantityPurchaseId}`;
  await callCallable(
    "postPurchase",
    validRequestV4({
      idempotencyKey: wrongQuantityKey,
      document: validDocumentV3({
        purchaseId: wrongQuantityPurchaseId,
        idempotencyKey: wrongQuantityKey,
        documentNumber: nextCurrentDocNumber(),
      }),
    }),
    idToken,
  );
  const wrongQuantity = await callCallable(
    "postPurchase",
    validVoidRequest(wrongQuantityPurchaseId, {
      impacts: [validVoidImpact({
        reversalQuantity: "-3",
        resultingQuantity: "-1",
        negative: true,
      })],
      negativeImpactCount: 1,
    }),
    idToken,
  );
  assert.equal(wrongQuantity.body.error?.status, "INVALID_ARGUMENT");
  assert.equal(wrongQuantity.body.error?.message, "VOID_IMPACT_REVERSAL_MISMATCH");
  assert.equal(
    (await db.doc(
      `businesses/${BUSINESS_ID}/purchases/${wrongQuantityPurchaseId}`,
    ).get()).data().status,
    "POSTED",
  );
});

test("anulación rechaza extras superiores/anidados y un actor local no canónico", async () => {
  const { idToken, localId } = await verifiedEmailToken();
  await seedMembership(localId);
  const cases = [
    {
      expected: "VOID_FIELDS",
      payload: (purchaseId) => validVoidPayload(purchaseId, { rawInvoice: "prohibido" }),
    },
    {
      expected: "VOID_IMPACT_FIELDS",
      payload: (purchaseId) =>
        validVoidPayload(purchaseId, {
          impacts: [validVoidImpact({ nestedExtra: { serverRole: "OWNER" } })],
        }),
    },
    {
      expected: "ACTOR_ID",
      payload: (purchaseId) => validVoidPayload(purchaseId, { actorId: localId }),
    },
    {
      expected: "NEGATIVE_IMPACT_COUNT",
      payload: (purchaseId) =>
        validVoidPayload(purchaseId, {
          negativeImpactCount: 0,
          impacts: [
            validVoidImpact({ reversalQuantity: "-3", resultingQuantity: "-1", negative: true }),
          ],
        }),
    },
    {
      expected: "VOID_IMPACT_TOTAL_MISMATCH",
      payload: (purchaseId) =>
        validVoidPayload(purchaseId, {
          impacts: [validVoidImpact({ resultingQuantity: "1" })],
        }),
    },
  ];

  for (const entry of cases) {
    const purchaseId = randomUUID();
    const request = validVoidRequest(purchaseId, {}, {
      document: JSON.stringify(entry.payload(purchaseId)),
    });
    const { body } = await callCallable("postPurchase", request, idToken);
    assert.equal(body.error?.status, "INVALID_ARGUMENT", JSON.stringify(body));
    assert.equal(body.error?.message, entry.expected);
  }
});

test("READER es miembro pero no puede respaldar compras (ROLE_FORBIDDEN)", async () => {
  const { idToken, localId } = await verifiedEmailToken();
  await seedMembership(localId, "READER");
  const purchaseId = randomUUID();
  const key = `sync-purchase:v1:${purchaseId}`;
  const { body } = await callCallable(
    "postPurchase",
    validRequest({
      idempotencyKey: key,
      document: validDocument({ purchaseId, idempotencyKey: key }),
    }),
    idToken,
  );
  assert.equal(body.error?.status, "PERMISSION_DENIED");
  assert.equal(body.error?.message, "ROLE_FORBIDDEN");
});

test("OPERATOR respalda compras pero no puede anularlas", async () => {
  const { idToken, localId } = await verifiedEmailToken();
  await seedMembership(localId, "OPERATOR");
  const purchaseId = randomUUID();
  const key = `sync-purchase:v1:${purchaseId}`;

  const recorded = await callCallable(
    "postPurchase",
    validRequestV4({
      idempotencyKey: key,
      document: validDocumentV3({
        purchaseId,
        idempotencyKey: key,
        documentNumber: nextCurrentDocNumber(),
      }),
    }),
    idToken,
  );
  assert.equal(recorded.body.error, undefined, JSON.stringify(recorded.body));
  assert.equal(recorded.body.result.status, "RECORDED");

  const denied = await callCallable(
    "postPurchase",
    validVoidRequest(purchaseId),
    idToken,
  );
  assert.equal(denied.body.error?.status, "PERMISSION_DENIED");
  assert.equal(denied.body.error?.message, "ROLE_FORBIDDEN");
});

test("miembro de un negocio no puede escribir en otro del que no es miembro", async () => {
  const { idToken, localId } = await verifiedEmailToken();
  await seedMembership(localId, "OWNER", BUSINESS_ID);
  const { localId: otherOwner } = await verifiedEmailToken();
  await seedMembership(otherOwner, "OWNER", BUSINESS_ID_B);

  const purchaseId = randomUUID();
  const key = `sync-purchase:v1:${purchaseId}`;
  const { body } = await callCallable(
    "postPurchase",
    {
      businessId: BUSINESS_ID_B,
      idempotencyKey: key,
      operationType: "SYNC_PURCHASE",
      payloadVersion: 2,
      document: validDocument({
        purchaseId,
        businessId: BUSINESS_ID_B,
        idempotencyKey: key,
        documentNumber: nextDocNumber(),
      }),
    },
    idToken,
  );
  assert.equal(body.error?.status, "PERMISSION_DENIED");
  assert.equal(body.error?.message, "NOT_A_MEMBER");
});

test("miembro revocado pierde acceso al callable y a la lectura por reglas", async () => {
  const { idToken, localId, email } = await verifiedEmailToken();
  await seedMembership(localId);
  const purchaseId = randomUUID();
  const key = `sync-purchase:v1:${purchaseId}`;
  const request = validRequestV4({
    idempotencyKey: key,
    document: validDocumentV3({
      purchaseId,
      idempotencyKey: key,
      documentNumber: nextCurrentDocNumber(),
    }),
  });

  const recorded = await callCallable("postPurchase", request, idToken);
  assert.equal(recorded.body.error, undefined, JSON.stringify(recorded.body));

  // Revocación: se borra la membresía con Admin SDK (fuera de banda).
  await db.doc(`businesses/${BUSINESS_ID}/members/${localId}`).delete();

  const denied = await callCallable("postPurchase", request, idToken);
  assert.equal(denied.body.error?.status, "PERMISSION_DENIED");

  const read = await clientRead(
    `businesses/${BUSINESS_ID}/purchases/${purchaseId}`,
    email,
  );
  assert.equal(read, "DENIED");
});

test("reglas Firestore: sin membresía no hay lectura de clientes", async () => {
  const denied = await clientRead(`businesses/${BUSINESS_ID}/purchases/${PURCHASE_ID}`);
  assert.equal(denied, "DENIED");
});
