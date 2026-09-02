// Integración real contra el Emulator Suite (sin credenciales, proyecto demo-facturastock).
// Cubre el pull incremental: secuencia monotónica por negocio, cursor sinceSeq, paginación,
// validaciones y el criterio multi-teléfono (detalles de conflicto + reenvío idempotente).
// Se ejecuta con: npx firebase emulators:exec --only auth,firestore,functions,storage "npm test"
import { test, after } from "node:test";
import assert from "node:assert/strict";
import { initializeApp as initializeAdminApp, deleteApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { getAuth } from "firebase-admin/auth";
import { randomUUID } from "node:crypto";

process.env.FIRESTORE_EMULATOR_HOST ??= "localhost:8080";
process.env.FIREBASE_AUTH_EMULATOR_HOST ??= "localhost:9099";
process.env.GCLOUD_PROJECT ??= "demo-facturastock";

const PROJECT = process.env.GCLOUD_PROJECT;
const FUNCTIONS_URL = `http://localhost:5001/${PROJECT}/us-central1`;
const AUTH_URL = "http://localhost:9099/identitytoolkit.googleapis.com/v1";
const PASSWORD = "clave-demo-123";

const adminApp = initializeAdminApp({ projectId: PROJECT });
const db = getFirestore(adminApp);
const adminAuth = getAuth(adminApp);

const LINE_1 = "33333333-3333-4333-8333-333333333331";
const PRODUCT_1 = "44444444-4444-4444-8444-444444444441";
const LOCATION_ID = "55555555-5555-4555-8555-555555555555";

// Cada test trabaja con negocios propios (secuencia limpia desde 1); se limpian al final.
const createdBusinesses = new Set();

// Número de documento único por compra: la unicidad documental (ruc|tipo|serie|número) es
// por negocio; dentro de un mismo negocio dos compras de prueba no deben chocar.
let docNumberCounter = 0;
function nextDocNumber() {
  docNumberCounter += 1;
  return String(docNumberCounter).padStart(12, "0");
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
    taxTreatment: "INCLUDED",
    taxEvidence: { type: "EXPLICIT_AMOUNT", value: "3.60" },
    productProvenance: "EXISTING",
    ...overrides,
  };
}

function validDocument(overrides = {}) {
  return {
    version: 3,
    purchaseId: randomUUID(),
    businessId: null, // lo fija purchaseRequest
    status: "POSTED",
    documentType: "INVOICE",
    documentSeries: "F001",
    documentNumber: nextDocNumber(),
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
    idempotencyKey: null, // lo fija purchaseRequest
    lines: [validLine()],
    movements: [
      {
        movementId: randomUUID(),
        purchaseLineId: LINE_1,
        productId: PRODUCT_1,
        locationId: LOCATION_ID,
        locationName: "Almacén principal",
        type: "PURCHASE",
        quantityDelta: "2",
        unitCost: "10.00",
        appliedCostTotal: "20.00",
        occurredAt: 1_787_000_000_000,
      },
    ],
    auditEventIds: [randomUUID()],
    duplicateOverride: null,
    ...overrides,
  };
}

// Sobre SYNC_PURCHASE autocontenido: purchaseId e idempotencyKey nuevos salvo overrides.
function purchaseRequest(businessId, overrides = {}) {
  const purchaseId = overrides.purchaseId ?? randomUUID();
  const idempotencyKey = overrides.idempotencyKey ?? `sync-purchase:v1:${purchaseId}`;
  return {
    businessId,
    idempotencyKey,
    operationType: "SYNC_PURCHASE",
    payloadVersion: 4,
    document: validDocument({ ...overrides, purchaseId, businessId, idempotencyKey }),
  };
}

function voidRequest(businessId, purchaseId) {
  const idempotencyKey = `sync-purchase-void:v1:${purchaseId}`;
  return {
    businessId,
    idempotencyKey,
    operationType: "SYNC_PURCHASE_VOID",
    payloadVersion: 1,
    document: JSON.stringify({
      version: 1,
      purchaseId,
      impactHash: "b".repeat(64),
      // ID interno del negocio; el UID Firebase del actor se deriva solo del token en servidor.
      actorId: businessId,
      role: "OWNER",
      reason: "Documento emitido por error material",
      negativeStockPolicy: "ALLOW_WITH_VISIBLE_WARNING",
      averageUnitCostPolicy: "PRESERVE_CURRENT",
      negativeImpactCount: 0,
      impacts: [
        {
          productId: PRODUCT_1,
          locationId: LOCATION_ID,
          currentQuantity: "2",
          reversalQuantity: "-2",
          resultingQuantity: "0",
          currentAverageUnitCost: "10.00",
          currency: "PEN",
          balanceVersion: 1,
          negative: false,
        },
      ],
    }),
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
  const email = `sp-${randomUUID()}@example.test`;
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

// Negocio nuevo con el uid como miembro del rol indicado; queda registrado para limpieza.
async function seedMembership(uid, role, businessId) {
  createdBusinesses.add(businessId);
  await db.doc(`businesses/${businessId}`).set({ businessId }, { merge: true });
  await db.doc(`businesses/${businessId}/members/${uid}`).set({ uid, role });
}

const listChanges = (businessId, token, extra = {}) =>
  callCallable("listChanges", { businessId, ...extra }, token);

const metadataSeq = async (businessId) =>
  (await db.doc(`businesses/${businessId}/sync/metadata`).get()).data()?.seq ?? 0;

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

test("secuencia monotónica: el alta suma 1 y la anulación suma 1", async () => {
  const businessId = randomUUID();
  const { idToken, localId } = await verifiedEmailToken();
  await seedMembership(localId, "OWNER", businessId);

  const request = purchaseRequest(businessId);
  const recorded = await callCallable("postPurchase", request, idToken);
  assert.equal(recorded.body.error, undefined, JSON.stringify(recorded.body));

  const purchaseRef = db.doc(
    `businesses/${businessId}/purchases/${request.document.purchaseId}`,
  );
  const posted = await purchaseRef.get();
  assert.equal(posted.data().seq, 1);
  assert.deepEqual(posted.data().movementSummary, [
    { productId: PRODUCT_1, productName: "ARROZ EXTRA", type: "PURCHASE", quantityDelta: "2" },
  ]);
  assert.equal(await metadataSeq(businessId), 1);

  // Repetición exacta: no incrementa la secuencia.
  const repeat = await callCallable("postPurchase", request, idToken);
  assert.equal(repeat.body.result.status, "ALREADY_RECORDED");
  assert.equal(await metadataSeq(businessId), 1);

  const voided = await callCallable(
    "postPurchase",
    voidRequest(businessId, request.document.purchaseId),
    idToken,
  );
  assert.equal(voided.body.error, undefined, JSON.stringify(voided.body));

  const voidedDoc = await purchaseRef.get();
  assert.equal(voidedDoc.data().status, "VOIDED");
  assert.equal(voidedDoc.data().seq, 2);
  assert.equal(await metadataSeq(businessId), 2);
});

test("listChanges incremental: cursor sinceSeq y reaparición de anuladas", async () => {
  const businessId = randomUUID();
  const { idToken, localId } = await verifiedEmailToken();
  await seedMembership(localId, "OWNER", businessId);

  const first = purchaseRequest(businessId);
  const second = purchaseRequest(businessId);
  await callCallable("postPurchase", first, idToken);
  await callCallable("postPurchase", second, idToken);

  const all = await listChanges(businessId, idToken, { sinceSeq: 0 });
  assert.equal(all.body.error, undefined, JSON.stringify(all.body));
  assert.equal(all.body.result.nextCursor, 2);
  assert.equal(all.body.result.hasMore, false);
  assert.deepEqual(
    all.body.result.changes.map((change) => change.seq),
    [1, 2],
  );
  assert.deepEqual(
    all.body.result.changes.map((change) => change.purchaseId),
    [first.document.purchaseId, second.document.purchaseId],
  );
  const change = all.body.result.changes[0];
  assert.equal(change.status, "POSTED");
  assert.equal(change.documentType, "INVOICE");
  assert.equal(change.documentSeries, "F001");
  assert.equal(change.documentNumber, first.document.documentNumber);
  assert.equal(change.issueDate, "2026-08-14");
  assert.equal(change.currency, "PEN");
  assert.equal(change.supplierRuc, "20123456789");
  assert.equal(change.supplierLegalName, "PROVEEDOR DEMO SAC");
  assert.equal(change.totalMinorUnits, 2360);
  assert.deepEqual(change.movementSummary, [
    { productId: PRODUCT_1, productName: "ARROZ EXTRA", type: "PURCHASE", quantityDelta: "2" },
  ]);
  assert.match(change.receiptId, /^rcpt_[0-9a-f]{32}$/);
  assert.equal(typeof change.syncedAtMillis, "number");
  assert.equal(change.syncedBy, null);

  const incremental = await listChanges(businessId, idToken, { sinceSeq: 1 });
  assert.deepEqual(
    incremental.body.result.changes.map((c) => c.purchaseId),
    [second.document.purchaseId],
  );
  assert.equal(incremental.body.result.nextCursor, 2);
  assert.equal(incremental.body.result.hasMore, false);

  // La anulación reaparece como cambio nuevo: status VOIDED y seq nueva.
  await callCallable(
    "postPurchase",
    voidRequest(businessId, second.document.purchaseId),
    idToken,
  );
  const afterVoid = await listChanges(businessId, idToken, { sinceSeq: 2 });
  assert.equal(afterVoid.body.result.nextCursor, 3);
  assert.equal(afterVoid.body.result.hasMore, false);
  assert.equal(afterVoid.body.result.changes.length, 1);
  assert.equal(afterVoid.body.result.changes[0].purchaseId, second.document.purchaseId);
  assert.equal(afterVoid.body.result.changes[0].status, "VOIDED");
  assert.equal(afterVoid.body.result.changes[0].seq, 3);
});

test("paginación conserva rango y no salta un commit entre páginas", async () => {
  const businessId = randomUUID();
  const { idToken, localId } = await verifiedEmailToken();
  await seedMembership(localId, "OWNER", businessId);

  const first = purchaseRequest(businessId);
  const second = purchaseRequest(businessId);
  await callCallable("postPurchase", first, idToken);
  await callCallable("postPurchase", second, idToken);

  const page1 = await listChanges(businessId, idToken, { sinceSeq: 0, limit: 1 });
  assert.equal(page1.body.result.changes.length, 1);
  assert.equal(page1.body.result.changes[0].purchaseId, first.document.purchaseId);
  assert.equal(page1.body.result.nextCursor, 1);
  assert.equal(page1.body.result.hasMore, true);

  // Un commit posterior a la primera página no puede convertir el seq global en cursor
  // consumido. Debe aparecer después de la segunda compra, sin saltarse ninguna.
  const third = purchaseRequest(businessId);
  await callCallable("postPurchase", third, idToken);

  const cursor = page1.body.result.nextCursor;
  const page2 = await listChanges(businessId, idToken, { sinceSeq: cursor, limit: 1 });
  assert.equal(page2.body.result.changes.length, 1);
  assert.equal(page2.body.result.changes[0].purchaseId, second.document.purchaseId);
  assert.equal(page2.body.result.nextCursor, 2);
  assert.equal(page2.body.result.hasMore, true);

  const page3 = await listChanges(businessId, idToken, {
    sinceSeq: page2.body.result.nextCursor,
    limit: 1,
  });
  assert.equal(page3.body.result.changes.length, 1);
  assert.equal(page3.body.result.changes[0].purchaseId, third.document.purchaseId);
  assert.equal(page3.body.result.nextCursor, 3);
  assert.equal(page3.body.result.hasMore, false);

  const empty = await listChanges(businessId, idToken, {
    sinceSeq: page3.body.result.nextCursor,
    limit: 1,
  });
  assert.equal(empty.body.result.changes.length, 0);
  assert.equal(empty.body.result.nextCursor, 3);
  assert.equal(empty.body.result.hasMore, false);
});

test("limit 200 usa páginas compactas acotadas y no expone latestSeq ni actor", async () => {
  const businessId = randomUUID();
  const owner = await verifiedEmailToken();
  await seedMembership(owner.localId, "OWNER", businessId);
  const batch = db.batch();
  for (let seq = 1; seq <= 21; seq += 1) {
    const purchaseId = randomUUID();
    batch.set(db.doc(`businesses/${businessId}/syncChanges/${purchaseId}`), {
      seq,
      purchaseId,
      status: "POSTED",
      documentType: "INVOICE",
      documentSeries: "F001",
      documentNumber: String(seq).padStart(8, "0"),
      issueDate: "2026-08-20",
      currency: "PEN",
      supplierRuc: null,
      supplierLegalName: "PROVEEDOR",
      totalMinorUnits: 100,
      movementSummary: [],
      receiptId: `rcpt_${seq.toString(16).padStart(32, "0")}`,
      syncedAt: null,
    });
  }
  await batch.commit();

  const first = await listChanges(businessId, owner.idToken, { sinceSeq: 0, limit: 200 });
  assert.equal(first.body.error, undefined, JSON.stringify(first.body));
  assert.equal(first.body.result.changes.length, 20);
  assert.equal(first.body.result.nextCursor, 20);
  assert.equal(first.body.result.hasMore, true);
  assert.equal(first.body.result.changes.every((change) => change.syncedBy === null), true);
  assert.equal(Object.hasOwn(first.body.result, "latestSeq"), false);

  const second = await listChanges(businessId, owner.idToken, {
    sinceSeq: first.body.result.nextCursor,
    limit: 200,
  });
  assert.equal(second.body.result.changes.length, 1);
  assert.equal(second.body.result.nextCursor, 21);
  assert.equal(second.body.result.hasMore, false);
});

test("validaciones: businessId, sinceSeq y limit con códigos cerrados", async () => {
  const businessId = randomUUID();
  const { idToken, localId } = await verifiedEmailToken();
  await seedMembership(localId, "OWNER", businessId);

  const badBusiness = await listChanges("no-es-un-uuid", idToken);
  assert.equal(badBusiness.body.error?.status, "INVALID_ARGUMENT");
  assert.equal(badBusiness.body.error?.message, "BUSINESS_ID");

  const badSince = await listChanges(businessId, idToken, { sinceSeq: -1 });
  assert.equal(badSince.body.error?.status, "INVALID_ARGUMENT");
  assert.equal(badSince.body.error?.message, "SINCE_SEQ");

  const unsafeSince = await listChanges(businessId, idToken, {
    sinceSeq: Number.MAX_SAFE_INTEGER + 1,
  });
  assert.equal(unsafeSince.body.error?.status, "INVALID_ARGUMENT");
  assert.equal(unsafeSince.body.error?.message, "SINCE_SEQ");

  const maxSafeSince = await listChanges(businessId, idToken, {
    sinceSeq: Number.MAX_SAFE_INTEGER,
  });
  assert.equal(maxSafeSince.body.error, undefined, JSON.stringify(maxSafeSince.body));
  assert.deepEqual(maxSafeSince.body.result, {
    changes: [],
    nextCursor: Number.MAX_SAFE_INTEGER,
    hasMore: false,
  });

  const limitZero = await listChanges(businessId, idToken, { limit: 0 });
  assert.equal(limitZero.body.error?.status, "INVALID_ARGUMENT");
  assert.equal(limitZero.body.error?.message, "LIMIT");

  const limitOver = await listChanges(businessId, idToken, { limit: 201 });
  assert.equal(limitOver.body.error?.status, "INVALID_ARGUMENT");
  assert.equal(limitOver.body.error?.message, "LIMIT");
});

test("sin membresía rechaza con NOT_A_MEMBER; READER sí puede listar cambios", async () => {
  const businessId = randomUUID();
  const owner = await verifiedEmailToken();
  await seedMembership(owner.localId, "OWNER", businessId);
  const reader = await verifiedEmailToken();
  await seedMembership(reader.localId, "READER", businessId);
  const outsider = await verifiedEmailToken();

  const denied = await listChanges(businessId, outsider.idToken);
  assert.equal(denied.body.error?.status, "PERMISSION_DENIED");
  assert.equal(denied.body.error?.message, "NOT_A_MEMBER");

  await callCallable("postPurchase", purchaseRequest(businessId), owner.idToken);

  const listed = await listChanges(businessId, reader.idToken, { sinceSeq: 0 });
  assert.equal(listed.body.error, undefined, JSON.stringify(listed.body));
  assert.equal(listed.body.result.changes.length, 1);
  assert.equal(listed.body.result.nextCursor, 1);
  assert.equal(listed.body.result.hasMore, false);
});

test("dos teléfonos: identidad duplicada trae details y el reenvío no duplica", async () => {
  const businessId = randomUUID();
  const phoneA = await verifiedEmailToken();
  await seedMembership(phoneA.localId, "OWNER", businessId);
  const phoneB = await verifiedEmailToken();
  await seedMembership(phoneB.localId, "OPERATOR", businessId);

  // Teléfono A respalda el documento.
  const requestA = purchaseRequest(businessId);
  const recordedA = await callCallable("postPurchase", requestA, phoneA.idToken);
  assert.equal(recordedA.body.error, undefined, JSON.stringify(recordedA.body));

  // Teléfono B (mismo negocio) envía OTRO purchaseId con la MISMA identidad documental.
  const requestB = purchaseRequest(businessId, {
    documentNumber: requestA.document.documentNumber,
  });
  assert.notEqual(requestB.document.purchaseId, requestA.document.purchaseId);
  const conflictB = await callCallable("postPurchase", requestB, phoneB.idToken);
  assert.equal(conflictB.body.error?.status, "ALREADY_EXISTS");
  assert.equal(conflictB.body.error?.message, "DOCUMENT_IDENTITY_EXISTS");
  assert.equal(
    conflictB.body.error?.details?.existingPurchaseId,
    requestA.document.purchaseId,
  );
  assert.equal(conflictB.body.error?.details?.receiptId, recordedA.body.result.receiptId);

  // La colección purchases sigue teniendo un solo documento con esa identidad.
  const purchases = await db.collection(`businesses/${businessId}/purchases`).get();
  const withIdentity = purchases.docs.filter(
    (doc) => doc.data().documentNumber === requestA.document.documentNumber,
  );
  assert.equal(withIdentity.length, 1);
  assert.equal(withIdentity[0].id, requestA.document.purchaseId);

  // Reenvío del mismo sobre por A: mismo acuse, sin duplicar ni avanzar la secuencia.
  const resent = await callCallable("postPurchase", requestA, phoneA.idToken);
  assert.equal(resent.body.result.status, "ALREADY_RECORDED");
  assert.equal(resent.body.result.receiptId, recordedA.body.result.receiptId);
  const afterResend = await db.collection(`businesses/${businessId}/purchases`).get();
  assert.equal(afterResend.size, 1);
  assert.equal(await metadataSeq(businessId), 1);
});
