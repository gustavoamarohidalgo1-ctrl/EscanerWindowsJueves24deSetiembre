// Integración Emulator del catálogo optimista: CAS, replay, roles/tenant, claves semánticas y pull.
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
const app = initializeAdminApp({ projectId: PROJECT }, `catalog-${randomUUID()}`);
const db = getFirestore(app);
const auth = getAuth(app);
const businesses = new Set();

async function authRest(path, payload) {
  const response = await fetch(`${AUTH_URL}/${path}?key=demo-api-key`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
  const body = await response.json();
  assert.ok(body.idToken, JSON.stringify(body));
  return body;
}

async function verifiedUser() {
  const email = `catalog-${randomUUID()}@example.test`;
  const created = await authRest("accounts:signUp", {
    email, password: PASSWORD, returnSecureToken: true,
  });
  await auth.updateUser(created.localId, { emailVerified: true });
  const session = await authRest("accounts:signInWithPassword", {
    email, password: PASSWORD, returnSecureToken: true,
  });
  return { uid: created.localId, token: session.idToken };
}

async function call(name, data, token) {
  const response = await fetch(`${FUNCTIONS_URL}/${name}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify({ data }),
  });
  return { status: response.status, body: await response.json() };
}

async function seed(uid, role, businessId = randomUUID()) {
  businesses.add(businessId);
  await db.doc(`businesses/${businessId}`).set({ businessId });
  await db.doc(`businesses/${businessId}/members/${uid}`).set({ uid, role });
  return businessId;
}

function productSnapshot(overrides = {}) {
  return {
    name: "ARROZ EXTRA",
    sku: "SKU-001",
    barcode: "000123450001",
    inventoryUnit: { code: "NIU", name: "Unidad", symbol: "und", status: "ACTIVE" },
    purchaseUnit: { code: "BX", name: "Caja", symbol: "caj", status: "ACTIVE" },
    purchaseFactor: "12.0000",
    location: { name: "Almacén principal", status: "ACTIVE" },
    status: "ACTIVE",
    createdAt: 1_787_000_000_000,
    updatedAt: 1_787_000_000_000,
    ...overrides,
  };
}

function productSnapshotV2(overrides = {}) {
  return {
    ...productSnapshot(),
    salePriceMinorUnits: 1290,
    salePriceCurrencyCode: "PEN",
    ...overrides,
  };
}

function productRequest(businessId, entityId = randomUUID(), expectedVersion = 0, overrides = {}) {
  const targetVersion = expectedVersion + 1;
  return {
    businessId,
    idempotencyKey: `sync-product:v1:${entityId}:${targetVersion}`,
    operationType: "SYNC_PRODUCT",
    payloadVersion: 1,
    document: {
      version: 1,
      entityId,
      expectedVersion,
      targetVersion,
      mutation: "UPSERT",
      snapshot: productSnapshot(overrides),
    },
  };
}

function productRequestV2(
  businessId,
  entityId = randomUUID(),
  expectedVersion = 0,
  overrides = {},
) {
  const targetVersion = expectedVersion + 1;
  return {
    businessId,
    idempotencyKey: `sync-product:v2:${entityId}:${targetVersion}`,
    operationType: "SYNC_PRODUCT",
    payloadVersion: 2,
    document: {
      version: 2,
      entityId,
      expectedVersion,
      targetVersion,
      mutation: "UPSERT",
      snapshot: productSnapshotV2(overrides),
    },
  };
}

function supplierRequest(businessId, entityId = randomUUID(), expectedVersion = 0, overrides = {}) {
  const targetVersion = expectedVersion + 1;
  return {
    businessId,
    idempotencyKey: `sync-supplier:v1:${entityId}:${targetVersion}`,
    operationType: "SYNC_SUPPLIER",
    payloadVersion: 1,
    document: {
      version: 1,
      entityId,
      expectedVersion,
      targetVersion,
      mutation: "UPSERT",
      snapshot: {
        legalName: "PROVEEDOR DEMO SAC",
        ruc: "20123456789",
        tradeName: null,
        status: "ACTIVE",
        createdAt: 1_787_000_000_000,
        updatedAt: 1_787_000_000_000,
        ...overrides,
      },
    },
  };
}

after(async () => {
  for (const businessId of businesses) {
    await db.recursiveDelete(db.doc(`businesses/${businessId}`));
  }
  await deleteApp(app);
});

test("syncCatalogEntity rechaza expectedUid ajeno sin reservar ni escribir entidad", async () => {
  const user = await verifiedUser();
  const businessId = await seed(user.uid, "OWNER");
  const request = productRequest(businessId);
  request.expectedUid = "another-account";
  const entityRef = db.doc(`businesses/${businessId}/products/${request.document.entityId}`);

  const rejected = await call("syncCatalogEntity", request, user.token);
  assert.equal(rejected.body.error?.status, "UNAUTHENTICATED", JSON.stringify(rejected.body));
  assert.equal(rejected.body.error?.message, "AUTH_IDENTITY_CHANGED");
  assert.equal((await entityRef.get()).exists, false);
  assert.equal(
    (await db.collection(`businesses/${businessId}/catalogSyncOperations`).get()).empty,
    true,
  );

  request.expectedUid = user.uid;
  const accepted = await call("syncCatalogEntity", request, user.token);
  assert.equal(accepted.body.error, undefined, JSON.stringify(accepted.body));
  assert.equal((await entityRef.get()).exists, true);

  const rejectedPull = await call(
    "listCatalogChanges",
    { businessId, sinceSeq: 0, limit: 200, expectedUid: "another-account" },
    user.token,
  );
  assert.equal(rejectedPull.body.error?.status, "UNAUTHENTICATED");
  assert.equal(rejectedPull.body.error?.message, "AUTH_IDENTITY_CHANGED");
  const acceptedPull = await call(
    "listCatalogChanges",
    { businessId, sinceSeq: 0, limit: 200, expectedUid: user.uid },
    user.token,
  );
  assert.equal(acceptedPull.body.error, undefined, JSON.stringify(acceptedPull.body));
  assert.equal(acceptedPull.body.result.changes.length, 1);
});

test("OWNER crea, replay exacto no duplica y pull omite identidad de cuenta", async () => {
  const user = await verifiedUser();
  const businessId = await seed(user.uid, "OWNER");
  const request = productRequest(businessId);
  const first = await call("syncCatalogEntity", request, user.token);
  assert.equal(first.body.error, undefined, JSON.stringify(first.body));
  assert.equal(first.body.result.replayed, false);
  assert.equal(first.body.result.seq, 1);

  // Un downgrade posterior no rompe la recuperación de un ACK perdido exacto.
  await db.doc(`businesses/${businessId}/members/${user.uid}`).update({ role: "READER" });
  const replay = await call("syncCatalogEntity", request, user.token);
  assert.equal(replay.body.result.replayed, true, JSON.stringify(replay.body));
  assert.equal(replay.body.result.receiptId, first.body.result.receiptId);

  const pulled = await call(
    "listCatalogChanges", { businessId, sinceSeq: 0, limit: 200 }, user.token,
  );
  assert.equal(pulled.body.result.changes.length, 1, JSON.stringify(pulled.body));
  const change = pulled.body.result.changes[0];
  assert.equal(change.entityId, request.document.entityId);
  assert.equal(change.remoteVersion, 1);
  assert.equal(change.snapshotPayload.includes("businessId"), false);
  assert.equal(JSON.stringify(change).includes(user.uid), false);
  assert.equal(JSON.stringify(change).includes("unitId"), false);
  assert.equal(change.snapshotPayload.includes("salePriceMinorUnits"), false);
  const audit = await db.doc(
    `businesses/${businessId}/auditEvents/${first.body.result.receiptId}`,
  ).get();
  assert.equal(audit.exists, true);
  assert.equal(audit.data().eventType, "CATALOG_SYNCED");
  assert.equal(
    (await db.doc(`businesses/${businessId}/audit/${first.body.result.receiptId}`).get()).exists,
    false,
  );
});

test("producto v2 conserva precio y replay exacto no duplica", async () => {
  const user = await verifiedUser();
  const businessId = await seed(user.uid, "OWNER");
  const request = productRequestV2(businessId, randomUUID(), 0, {
    salePriceMinorUnits: 2590,
    salePriceCurrencyCode: "PEN",
  });

  const first = await call("syncCatalogEntity", request, user.token);
  assert.equal(first.body.error, undefined, JSON.stringify(first.body));
  assert.equal(first.body.result.replayed, false);
  const replay = await call("syncCatalogEntity", request, user.token);
  assert.equal(replay.body.result.replayed, true, JSON.stringify(replay.body));

  const remote = await db.doc(
    `businesses/${businessId}/products/${request.document.entityId}`,
  ).get();
  assert.equal(remote.data().snapshot.salePriceMinorUnits, 2590);
  assert.equal(remote.data().snapshot.salePriceCurrencyCode, "PEN");
  const pulled = await call(
    "listCatalogChanges", { businessId, sinceSeq: 0, limit: 200 }, user.token,
  );
  const snapshot = JSON.parse(pulled.body.result.changes[0].snapshotPayload);
  assert.equal(snapshot.salePriceMinorUnits, 2590);
  assert.equal(snapshot.salePriceCurrencyCode, "PEN");
});

test("producto puede subir v1 a v2 pero nunca degradar ni borrar precio", async () => {
  const user = await verifiedUser();
  const businessId = await seed(user.uid, "OWNER");
  const entityId = randomUUID();
  const legacy = productRequest(businessId, entityId);
  assert.equal((await call("syncCatalogEntity", legacy, user.token)).body.error, undefined);

  const upgraded = productRequestV2(businessId, entityId, 1, {
    updatedAt: 1_787_000_001_000,
    salePriceMinorUnits: 3490,
    salePriceCurrencyCode: "PEN",
  });
  assert.equal((await call("syncCatalogEntity", upgraded, user.token)).body.error, undefined);

  const downgrade = productRequest(businessId, entityId, 2, {
    updatedAt: 1_787_000_002_000,
  });
  const denied = await call("syncCatalogEntity", downgrade, user.token);
  assert.equal(denied.body.error?.message, "CATALOG_PRODUCT_SCHEMA_DOWNGRADE");
  assert.equal(denied.body.error?.details?.remoteVersion, 2);
  const remote = await db.doc(`businesses/${businessId}/products/${entityId}`).get();
  assert.equal(remote.data().version, 2);
  assert.equal(remote.data().snapshot.salePriceMinorUnits, 3490);
  assert.equal(remote.data().snapshot.salePriceCurrencyCode, "PEN");
});

test("producto v1 y v2 exigen formas exactas y precio v2 pareado", async () => {
  const user = await verifiedUser();
  const businessId = await seed(user.uid, "OWNER");

  const legacyWithPrice = productRequest(businessId);
  legacyWithPrice.document.snapshot.salePriceMinorUnits = 100;
  legacyWithPrice.document.snapshot.salePriceCurrencyCode = "PEN";
  assert.equal(
    (await call("syncCatalogEntity", legacyWithPrice, user.token)).body.error?.message,
    "PRODUCT_FIELDS",
  );

  const v2WithoutPriceFields = productRequestV2(businessId);
  delete v2WithoutPriceFields.document.snapshot.salePriceMinorUnits;
  delete v2WithoutPriceFields.document.snapshot.salePriceCurrencyCode;
  assert.equal(
    (await call("syncCatalogEntity", v2WithoutPriceFields, user.token)).body.error?.message,
    "PRODUCT_FIELDS",
  );

  const mismatchedVersion = productRequestV2(businessId);
  mismatchedVersion.payloadVersion = 1;
  assert.equal(
    (await call("syncCatalogEntity", mismatchedVersion, user.token)).body.error?.message,
    "CATALOG_DOCUMENT_VERSION",
  );

  for (const snapshotOverride of [
    { salePriceMinorUnits: null, salePriceCurrencyCode: "PEN" },
    { salePriceMinorUnits: 100, salePriceCurrencyCode: null },
  ]) {
    const denied = await call(
      "syncCatalogEntity",
      productRequestV2(businessId, randomUUID(), 0, snapshotOverride),
      user.token,
    );
    assert.equal(denied.body.error?.message, "PRODUCT_SALE_PRICE_PAIR");
  }

  for (const invalidMinorUnits of [0, -1, 1.5, Number.MAX_SAFE_INTEGER + 1, "100"]) {
    const denied = await call(
      "syncCatalogEntity",
      productRequestV2(businessId, randomUUID(), 0, {
        salePriceMinorUnits: invalidMinorUnits,
        salePriceCurrencyCode: "PEN",
      }),
      user.token,
    );
    assert.equal(denied.body.error?.message, "PRODUCT_SALE_PRICE_MINOR_UNITS");
  }

  for (const invalidCurrency of ["pen", "PE", "PEN1", " P", "ZZZ"]) {
    const denied = await call(
      "syncCatalogEntity",
      productRequestV2(businessId, randomUUID(), 0, {
        salePriceMinorUnits: 100,
        salePriceCurrencyCode: invalidCurrency,
      }),
      user.token,
    );
    assert.equal(denied.body.error?.message, "PRODUCT_SALE_PRICE_CURRENCY");
  }

  for (const validCurrency of ["PEN", "USD"]) {
    const accepted = await call(
      "syncCatalogEntity",
      productRequestV2(businessId, randomUUID(), 0, {
        sku: `SKU-CURRENCY-${validCurrency}`,
        barcode: null,
        salePriceMinorUnits: 100,
        salePriceCurrencyCode: validCurrency,
      }),
      user.token,
    );
    assert.equal(accepted.body.error, undefined, JSON.stringify(accepted.body));
  }

  const nullPrice = await call(
    "syncCatalogEntity",
    productRequestV2(businessId, randomUUID(), 0, {
      sku: "SKU-NULL-PRICE",
      barcode: null,
      salePriceMinorUnits: null,
      salePriceCurrencyCode: null,
    }),
    user.token,
  );
  assert.equal(nullPrice.body.error, undefined, JSON.stringify(nullPrice.body));
});

test("precio de producto queda aislado por tenant en sync y pull", async () => {
  const ownerA = await verifiedUser();
  const ownerB = await verifiedUser();
  const businessA = await seed(ownerA.uid, "OWNER");
  const businessB = await seed(ownerB.uid, "OWNER");
  const sharedEntityId = randomUUID();
  const requestA = productRequestV2(businessA, sharedEntityId, 0, {
    sku: "TENANT-A",
    barcode: null,
    salePriceMinorUnits: 111,
  });
  const requestB = productRequestV2(businessB, sharedEntityId, 0, {
    sku: "TENANT-B",
    barcode: null,
    salePriceMinorUnits: 999,
  });
  assert.equal((await call("syncCatalogEntity", requestA, ownerA.token)).body.error, undefined);
  assert.equal((await call("syncCatalogEntity", requestB, ownerB.token)).body.error, undefined);

  const denied = await call(
    "listCatalogChanges", { businessId: businessA, sinceSeq: 0, limit: 200 }, ownerB.token,
  );
  assert.equal(denied.body.error?.message, "NOT_A_MEMBER");
  const pullA = await call(
    "listCatalogChanges", { businessId: businessA, sinceSeq: 0, limit: 200 }, ownerA.token,
  );
  const pullB = await call(
    "listCatalogChanges", { businessId: businessB, sinceSeq: 0, limit: 200 }, ownerB.token,
  );
  assert.equal(JSON.parse(pullA.body.result.changes[0].snapshotPayload).salePriceMinorUnits, 111);
  assert.equal(JSON.parse(pullB.body.result.changes[0].snapshotPayload).salePriceMinorUnits, 999);
});

test("OPERATOR puede editar catálogo; READER y otro tenant no pueden escribir", async () => {
  const operator = await verifiedUser();
  const reader = await verifiedUser();
  const outsider = await verifiedUser();
  const businessId = await seed(operator.uid, "OPERATOR");
  await db.doc(`businesses/${businessId}/members/${reader.uid}`).set({
    uid: reader.uid, role: "READER",
  });
  const allowed = await call("syncCatalogEntity", supplierRequest(businessId), operator.token);
  assert.equal(allowed.body.error, undefined, JSON.stringify(allowed.body));
  const denied = await call("syncCatalogEntity", productRequest(businessId), reader.token);
  assert.equal(denied.body.error?.message, "ROLE_FORBIDDEN");
  const crossTenant = await call("syncCatalogEntity", productRequest(businessId), outsider.token);
  assert.equal(crossTenant.body.error?.message, "NOT_A_MEMBER");
});

test("CAS obsoleto devuelve comparación remota y nunca sobrescribe", async () => {
  const user = await verifiedUser();
  const businessId = await seed(user.uid, "ADMIN");
  const entityId = randomUUID();
  const created = productRequest(businessId, entityId);
  assert.equal((await call("syncCatalogEntity", created, user.token)).body.error, undefined);

  const updated = productRequest(businessId, entityId, 1, {
    name: "ARROZ EXTRA ACTUALIZADO", updatedAt: 1_787_000_001_000,
  });
  assert.equal((await call("syncCatalogEntity", updated, user.token)).body.error, undefined);

  const stale = productRequest(businessId, entityId, 1, {
    name: "EDICIÓN OBSOLETA", updatedAt: 1_787_000_002_000,
  });
  const conflict = await call("syncCatalogEntity", stale, user.token);
  assert.equal(conflict.body.error?.message, "CATALOG_VERSION_CONFLICT");
  assert.equal(conflict.body.error?.details?.remoteEntityId, entityId);
  assert.equal(conflict.body.error?.details?.remoteVersion, 2);
  assert.equal(
    JSON.parse(conflict.body.error.details.remoteSnapshotPayload).name,
    "ARROZ EXTRA ACTUALIZADO",
  );
  const remote = await db.doc(`businesses/${businessId}/products/${entityId}`).get();
  assert.equal(remote.data().version, 2);
  assert.equal(remote.data().snapshot.name, "ARROZ EXTRA ACTUALIZADO");
});

test("dos dispositivos con el mismo expectedVersion producen un ACK y un CONFLICT", async () => {
  const user = await verifiedUser();
  const businessId = await seed(user.uid, "OWNER");
  const entityId = randomUUID();
  assert.equal(
    (await call("syncCatalogEntity", productRequest(businessId, entityId), user.token)).body.error,
    undefined,
  );
  const left = productRequest(businessId, entityId, 1, {
    name: "DISPOSITIVO A", updatedAt: 1_787_000_001_000,
  });
  const right = productRequest(businessId, entityId, 1, {
    name: "DISPOSITIVO B", updatedAt: 1_787_000_001_001,
  });
  const outcomes = await Promise.all([
    call("syncCatalogEntity", left, user.token),
    call("syncCatalogEntity", right, user.token),
  ]);
  assert.equal(outcomes.filter((value) => value.body.result?.ok).length, 1);
  assert.equal(
    outcomes.filter((value) => value.body.error?.message === "CATALOG_VERSION_CONFLICT").length,
    1,
  );
  const remote = await db.doc(`businesses/${businessId}/products/${entityId}`).get();
  assert.equal(remote.data().version, 2);
});

test("SKU y RUC son índices semánticos: otra entidad queda en CONFLICT", async () => {
  const user = await verifiedUser();
  const businessId = await seed(user.uid, "OWNER");
  const productA = productRequest(businessId);
  assert.equal((await call("syncCatalogEntity", productA, user.token)).body.error, undefined);
  const productCollision = await call("syncCatalogEntity", productRequest(businessId), user.token);
  assert.equal(productCollision.body.error?.message, "CATALOG_SEMANTIC_CONFLICT");
  assert.equal(productCollision.body.error?.details?.remoteEntityId, productA.document.entityId);

  const supplierA = supplierRequest(businessId);
  assert.equal((await call("syncCatalogEntity", supplierA, user.token)).body.error, undefined);
  const supplierCollision = await call("syncCatalogEntity", supplierRequest(businessId), user.token);
  assert.equal(supplierCollision.body.error?.message, "CATALOG_SEMANTIC_CONFLICT");
});

test("wire estricto rechaza IDs locales de referencias y businessId dentro del snapshot", async () => {
  const user = await verifiedUser();
  const businessId = await seed(user.uid, "OWNER");
  const withLocalUnit = productRequest(businessId);
  withLocalUnit.document.snapshot.inventoryUnit.unitId = randomUUID();
  const unitDenied = await call("syncCatalogEntity", withLocalUnit, user.token);
  assert.equal(unitDenied.body.error?.message, "INVENTORY_UNIT_FIELDS");

  const withBusiness = supplierRequest(businessId);
  withBusiness.document.snapshot.businessId = randomUUID();
  const businessDenied = await call("syncCatalogEntity", withBusiness, user.token);
  assert.equal(businessDenied.body.error?.message, "SUPPLIER_FIELDS");
});

test("purchaseFactor aplica precisión/escala BigDecimal sin convertir a Number", async () => {
  const user = await verifiedUser();
  const businessId = await seed(user.uid, "OWNER");
  const invalidFactors = [
    "0",
    "0.000000000000000000",
    "-1",
    "01",
    "1.0000000000000000000",
    "999999999999999999999999999999999999999",
  ];
  for (const [index, purchaseFactor] of invalidFactors.entries()) {
    const denied = await call(
      "syncCatalogEntity",
      productRequest(businessId, randomUUID(), 0, {
        sku: `SKU-DECIMAL-INVALID-${index}`,
        barcode: null,
        purchaseFactor,
      }),
      user.token,
    );
    assert.equal(denied.body.error?.message, "PURCHASE_FACTOR", purchaseFactor);
  }

  const tiny = productRequest(businessId, randomUUID(), 0, {
    sku: "SKU-DECIMAL-TINY",
    barcode: null,
    purchaseFactor: "0.000000000000000001",
  });
  const accepted = await call("syncCatalogEntity", tiny, user.token);
  assert.equal(accepted.body.error, undefined, JSON.stringify(accepted.body));
});
