import { after, test } from "node:test";
import assert from "node:assert/strict";
import { createHash, randomUUID } from "node:crypto";
import { initializeApp as initializeAdminApp, deleteApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { getFirestore } from "firebase-admin/firestore";

process.env.FIRESTORE_EMULATOR_HOST ??= "localhost:8080";
process.env.FIREBASE_AUTH_EMULATOR_HOST ??= "localhost:9099";
process.env.GCLOUD_PROJECT ??= "demo-facturastock";

const PROJECT = process.env.GCLOUD_PROJECT;
const FUNCTIONS_PORT = Number(process.env.FUNCTIONS_EMULATOR_PORT ?? 5001);
const FUNCTIONS_URL = `http://localhost:${FUNCTIONS_PORT}/${PROJECT}/us-central1`;
const AUTH_ORIGIN = `http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}`;
const AUTH_URL = `${AUTH_ORIGIN}/identitytoolkit.googleapis.com/v1`;
const PASSWORD = "clave-demo-123";
const app = initializeAdminApp({ projectId: PROJECT }, `sale-inventory-${randomUUID()}`);
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
  const email = `sale-${randomUUID()}@example.test`;
  const created = await authRest("accounts:signUp", {
    email,
    password: PASSWORD,
    returnSecureToken: true,
  });
  await auth.updateUser(created.localId, { emailVerified: true });
  const session = await authRest("accounts:signInWithPassword", {
    email,
    password: PASSWORD,
    returnSecureToken: true,
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

async function seedBusiness(uid, role = "OWNER") {
  const businessId = randomUUID();
  businesses.add(businessId);
  await db.doc(`businesses/${businessId}`).set({ businessId });
  await db.doc(`businesses/${businessId}/members/${uid}`).set({ uid, role });
  return businessId;
}

function canonicalLocation(value) {
  return value.normalize("NFKC").trim().replace(/\s+/gu, " ").toLocaleLowerCase("es-PE");
}

function balanceId(productId, locationName) {
  return createHash("sha256")
    .update(JSON.stringify([
      "inventory-balance-v1",
      productId,
      canonicalLocation(locationName),
    ]), "utf8")
    .digest("hex");
}

function saleContentHash(currency, lines) {
  const digest = createHash("sha256");
  const parts = ["sale-content-v1", currency];
  for (const line of lines) {
    parts.push(
      line.saleLineId,
      String(line.position),
      line.productId,
      line.unitId,
      line.locationId,
      line.quantity,
      String(line.unitPriceMinorUnits),
      String(line.discountMinorUnits),
      String(line.taxMinorUnits),
      String(line.lineTotalMinorUnits),
      currency,
    );
  }
  for (const part of parts) {
    const bytes = Buffer.from(part, "utf8");
    const length = Buffer.alloc(4);
    length.writeInt32BE(bytes.length);
    digest.update(length);
    digest.update(bytes);
  }
  return digest.digest("hex");
}

function deterministicUuid(...parts) {
  const digest = createHash("sha256");
  for (const part of parts) {
    const bytes = Buffer.from(part, "utf8");
    const length = Buffer.alloc(4);
    length.writeInt32BE(bytes.length);
    digest.update(length);
    digest.update(bytes);
  }
  const bytes = digest.digest().subarray(0, 16);
  bytes[6] = (bytes[6] & 0x0f) | 0x50;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = bytes.toString("hex");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${
    hex.slice(16, 20)}-${hex.slice(20)}`;
}

async function seedProductAndBalance(
  businessId,
  productId,
  locationName = "Almacén principal",
  overrides = {},
) {
  await db.doc(`businesses/${businessId}/products/${productId}`).set({
    entityId: productId,
    entityType: "PRODUCT",
    version: 1,
    snapshot: {
      name: "Arroz extra",
      inventoryUnit: { code: "NIU", name: "Unidad", symbol: "und", status: "ACTIVE" },
      salePriceMinorUnits: 1_000,
      salePriceCurrencyCode: "PEN",
      status: "ACTIVE",
    },
  });
  const ref = db.doc(
    `businesses/${businessId}/inventoryBalances/${balanceId(productId, locationName)}`,
  );
  await ref.set({
    schemaVersion: 1,
    productId,
    locationName,
    canonicalLocationName: canonicalLocation(locationName),
    quantityOnHand: "10",
    averageUnitCost: "5",
    currency: "PEN",
    version: 1,
    lastSeq: 0,
    updatedAtMillis: 1_787_000_000_000,
    lastSourceLocationId: randomUUID(),
    ...overrides,
  });
  return ref;
}

function saleRequest(businessId, productId, overrides = {}) {
  const saleId = overrides.saleId ?? randomUUID();
  const line = {
    saleLineId: randomUUID(),
    position: 0,
    productId,
    unitId: randomUUID(),
    locationId: randomUUID(),
    productName: "Arroz extra",
    unitCode: "NIU",
    locationName: "ALMACÉN   PRINCIPAL",
    barcode: "775000000001",
    quantity: "2",
    unitPriceMinorUnits: 1_000,
    discountMinorUnits: 100,
    taxMinorUnits: 180,
    lineTotalMinorUnits: 2_080,
    ...(overrides.line ?? {}),
  };
  const currency = overrides.currency ?? "PEN";
  const contentHash = saleContentHash(currency, [line]);
  const postedAt = 1_787_000_001_000;
  const document = {
    version: 1,
    saleId,
    businessId,
    status: "POSTED",
    currency,
    subtotalMinorUnits: 2_000,
    discountMinorUnits: 100,
    taxMinorUnits: 180,
    totalMinorUnits: 2_080,
    contentHash,
    checkoutIdempotencyKey: `sale-checkout:v1:${saleId}:3:${contentHash}`,
    createdAt: postedAt - 1_000,
    updatedAt: postedAt,
    postedAt,
    lines: [line],
    ...(overrides.document ?? {}),
  };
  return {
    businessId,
    idempotencyKey: `sync-sale:v1:${saleId}`,
    operationType: "SYNC_SALE",
    payloadVersion: 1,
    document,
  };
}

function creditSaleRequest(businessId, productId, overrides = {}) {
  const request = saleRequest(businessId, productId, overrides);
  const debtId = deterministicUuid("sale-debt", request.document.saleId);
  request.payloadVersion = 2;
  request.document.version = 2;
  request.document.credit = {
    version: 1,
    debtId,
    debtorNameSnapshot: overrides.debtorNameSnapshot ?? "María Pérez",
    dueAt: Object.hasOwn(overrides, "dueAt")
      ? overrides.dueAt
      : request.document.postedAt + 7 * 24 * 60 * 60 * 1000,
  };
  return request;
}

function debtPaymentRequest(businessId, debtId, expectedDebtVersion, amountMinorUnits, overrides = {}) {
  const paymentId = overrides.paymentId ?? randomUUID();
  const createdAt = overrides.createdAt ?? 1_787_000_003_000;
  return {
    businessId,
    idempotencyKey: `debt-payment:v1:${debtId}:${paymentId}`,
    operationType: "SYNC_DEBT_PAYMENT",
    payloadVersion: 1,
    document: {
      version: 1,
      paymentId,
      debtId,
      businessId,
      currency: "PEN",
      amountMinorUnits,
      method: overrides.method ?? "YAPE",
      note: Object.hasOwn(overrides, "note") ? overrides.note : "Abono semanal",
      reference: Object.hasOwn(overrides, "reference")
        ? overrides.reference
        : "YP-0001",
      expectedDebtVersion,
      occurredAt: overrides.occurredAt ?? createdAt - 1_000,
      createdAt,
    },
  };
}

function purchaseV4Request(businessId, productId, locationName = "Almacén principal") {
  const purchaseId = randomUUID();
  const purchaseLineId = randomUUID();
  const postedAt = 1_787_100_000_000;
  return {
    businessId,
    idempotencyKey: `sync-purchase:v1:${purchaseId}`,
    operationType: "SYNC_PURCHASE",
    payloadVersion: 4,
    document: {
      version: 3,
      purchaseId,
      businessId,
      status: "POSTED",
      documentType: "INVOICE",
      documentSeries: "F001",
      documentNumber: String(Math.floor(Math.random() * 1e12)).padStart(12, "0"),
      issueDate: "2026-08-31",
      currency: "PEN",
      supplierRuc: "20123456789",
      supplierLegalName: "Proveedor demo SAC",
      subtotalMinorUnits: 2_000,
      taxMinorUnits: 360,
      otherChargesMinorUnits: 0,
      totalMinorUnits: 2_360,
      adjustmentMinorUnits: null,
      adjustmentReason: null,
      preparedLogicalHash: "a".repeat(64),
      postedAt,
      idempotencyKey: `sync-purchase:v1:${purchaseId}`,
      lines: [{
        purchaseLineId,
        position: 0,
        productId,
        productName: "Arroz extra",
        unitCode: "NIU",
        description: "Arroz extra",
        quantity: "2",
        readUnitCost: "10",
        taxMinorUnits: 360,
        totalMinorUnits: 2_360,
        appliedUnitCost: "10",
        inventoryQuantity: "2",
        discount: null,
        taxTreatment: "INCLUDED",
        taxEvidence: { type: "EXPLICIT_AMOUNT", value: "3.60" },
        productProvenance: "EXISTING",
      }],
      movements: [{
        movementId: randomUUID(),
        purchaseLineId,
        productId,
        locationId: randomUUID(),
        locationName,
        type: "PURCHASE",
        quantityDelta: "2",
        unitCost: "10",
        appliedCostTotal: "20",
        occurredAt: postedAt,
      }],
      auditEventIds: [randomUUID()],
      duplicateOverride: null,
    },
  };
}

after(async () => {
  for (const businessId of businesses) {
    await db.recursiveDelete(db.doc(`businesses/${businessId}`));
  }
  await deleteApp(app);
});

test("postSale descuenta stock atomico, conserva costo, replays y publica feed completo", async () => {
  const user = await verifiedUser();
  const businessId = await seedBusiness(user.uid);
  const productId = randomUUID();
  const balanceRef = await seedProductAndBalance(businessId, productId);
  const request = saleRequest(businessId, productId);

  const first = await call("postSale", request, user.token);
  assert.equal(first.body.error, undefined, JSON.stringify(first.body));
  assert.equal(first.body.result.status, "RECORDED");
  assert.equal(first.body.result.seq, 1);
  assert.deepEqual(Object.keys(first.body.result).sort(), [
    "balances", "idempotencyKey", "postedAtMillis", "receiptId", "seq", "status",
  ]);
  assert.ok(first.body.result.postedAtMillis >= request.document.postedAt);
  assert.equal(first.body.result.balances[0].quantityOnHand, "8");
  assert.equal(first.body.result.balances[0].averageUnitCost, "5");
  assert.equal(first.body.result.balances[0].locationName, "Almacén principal");

  const replay = await call("postSale", request, user.token);
  assert.equal(replay.body.result.status, "ALREADY_RECORDED", JSON.stringify(replay.body));
  assert.equal(replay.body.result.seq, 1);
  assert.deepEqual(replay.body.result.balances, first.body.result.balances);
  assert.equal((await balanceRef.get()).data().quantityOnHand, "8");

  const pull = await call(
    "listSalesInventoryChanges",
    { businessId, sinceSeq: 0, limit: 200, expectedUid: user.uid },
    user.token,
  );
  assert.equal(pull.body.error, undefined, JSON.stringify(pull.body));
  assert.equal(pull.body.result.nextCursor, 1);
  assert.equal(pull.body.result.latestSeq, 1);
  assert.equal(pull.body.result.changes[0].kind, "SALE");
  assert.deepEqual(Object.keys(pull.body.result.changes[0]).sort(), [
    "balances", "kind", "receiptId", "sale", "seq", "syncedAtMillis",
  ]);
  assert.equal(
    pull.body.result.changes[0].sale.postedAt,
    first.body.result.postedAtMillis,
  );
  assert.equal(
    pull.body.result.changes[0].sale.updatedAt,
    first.body.result.postedAtMillis,
  );
  assert.deepEqual(
    pull.body.result.changes[0].sale.lines,
    request.document.lines.map((line) => ({ ...line, locationName: "ALMACÉN PRINCIPAL" })),
  );
  assert.equal(pull.body.result.changes[0].sale.movements[0].unitCost, "5");
  assert.match(
    pull.body.result.changes[0].sale.movements[0].movementId,
    /^[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
  );
  assert.equal(pull.body.result.changes[0].balances[0].quantityOnHand, "8");
});

test("postSale v2 abre deuda atomica y conserva la forma exacta del feed SALE", async () => {
  const user = await verifiedUser();
  const businessId = await seedBusiness(user.uid);
  const productId = randomUUID();
  const balanceRef = await seedProductAndBalance(businessId, productId);
  const request = creditSaleRequest(businessId, productId);
  const debtId = request.document.credit.debtId;

  const first = await call("postSale", request, user.token);
  assert.equal(first.body.error, undefined, JSON.stringify(first.body));
  assert.deepEqual(Object.keys(first.body.result).sort(), [
    "balances", "debt", "idempotencyKey", "postedAtMillis", "receiptId", "seq", "status",
  ]);
  assert.equal(first.body.result.status, "RECORDED");
  assert.equal(first.body.result.seq, 1);
  assert.deepEqual(first.body.result.debt, {
    debtId,
    businessId,
    saleId: request.document.saleId,
    debtorNameSnapshot: "María Pérez",
    currency: "PEN",
    originalMinorUnits: 2_080,
    balanceMinorUnits: 2_080,
    status: "OPEN",
    dueAt: request.document.credit.dueAt,
    version: 1,
    createdAt: first.body.result.postedAtMillis,
    updatedAt: first.body.result.postedAtMillis,
    paidAt: null,
  });
  assert.equal((await balanceRef.get()).data().quantityOnHand, "8");
  const debt = (await db.doc(`businesses/${businessId}/debts/${debtId}`).get()).data();
  assert.equal(debt.debtorNameSnapshot, "María Pérez");
  assert.equal(debt.recordedByUid, undefined);
  assert.equal(debt.createdByUid, undefined);

  const replay = await call("postSale", request, user.token);
  assert.equal(replay.body.result.status, "ALREADY_RECORDED", JSON.stringify(replay.body));
  assert.deepEqual(replay.body.result.debt, first.body.result.debt);
  assert.equal((await balanceRef.get()).data().quantityOnHand, "8");

  const pull = await call(
    "listSalesInventoryChanges",
    { businessId, sinceSeq: 0, limit: 200 },
    user.token,
  );
  assert.equal(pull.body.error, undefined, JSON.stringify(pull.body));
  const change = pull.body.result.changes[0];
  assert.deepEqual(Object.keys(change).sort(), [
    "balances", "kind", "receiptId", "sale", "seq", "syncedAtMillis",
  ]);
  assert.equal(change.kind, "SALE");
  assert.equal(change.sale.version, 2);
  assert.deepEqual(change.sale.credit, request.document.credit);

  const invalidName = creditSaleRequest(businessId, productId, {
    debtorNameSnapshot: "x".repeat(121),
  });
  const rejected = await call("postSale", invalidName, user.token);
  assert.equal(rejected.body.error?.status, "INVALID_ARGUMENT", JSON.stringify(rejected.body));
  assert.equal(rejected.body.error?.message, "SALE_DEBTOR_NAME");
});

test("recordDebtPayment aplica CAS, publica pago, liquida y recupera ACK perdido sin doble cobro", async () => {
  const user = await verifiedUser();
  const businessId = await seedBusiness(user.uid);
  const productId = randomUUID();
  await seedProductAndBalance(businessId, productId);
  const sale = creditSaleRequest(businessId, productId);
  const debtId = sale.document.credit.debtId;
  const posted = await call("postSale", sale, user.token);
  assert.equal(posted.body.error, undefined, JSON.stringify(posted.body));

  const memberRef = db.doc(`businesses/${businessId}/members/${user.uid}`);
  await memberRef.update({ role: "READER" });
  const readerRequest = debtPaymentRequest(businessId, debtId, 1, 100);
  const denied = await call("recordDebtPayment", readerRequest, user.token);
  assert.equal(denied.body.error?.status, "PERMISSION_DENIED", JSON.stringify(denied.body));
  assert.equal(denied.body.error?.message, "ROLE_FORBIDDEN");
  await memberRef.update({ role: "OWNER" });

  const overpayRequest = debtPaymentRequest(businessId, debtId, 1, 2_081);
  const overpay = await call("recordDebtPayment", overpayRequest, user.token);
  assert.equal(overpay.body.error?.status, "FAILED_PRECONDITION", JSON.stringify(overpay.body));
  assert.equal(overpay.body.error?.message, "DEBT_PAYMENT_EXCEEDS_BALANCE");
  assert.equal(
    (await db.doc(`businesses/${businessId}/sync/inventoryMetadata`).get()).data().seq,
    1,
  );
  const longReferenceRequest = debtPaymentRequest(businessId, debtId, 1, 100, {
    reference: "r".repeat(121),
  });
  const longReference = await call("recordDebtPayment", longReferenceRequest, user.token);
  assert.equal(
    longReference.body.error?.status,
    "INVALID_ARGUMENT",
    JSON.stringify(longReference.body),
  );
  assert.equal(longReference.body.error?.message, "DEBT_PAYMENT_REFERENCE");

  const partialRequest = debtPaymentRequest(businessId, debtId, 1, 800);
  const partial = await call("recordDebtPayment", partialRequest, user.token);
  assert.equal(partial.body.error, undefined, JSON.stringify(partial.body));
  assert.equal(partial.body.result.status, "RECORDED");
  assert.equal(partial.body.result.seq, 2);
  assert.equal(partial.body.result.debt.version, 2);
  assert.equal(partial.body.result.debt.balanceMinorUnits, 1_280);
  assert.equal(partial.body.result.debt.status, "OPEN");
  assert.deepEqual(partial.body.result.payment, {
    version: 1,
    paymentId: partialRequest.document.paymentId,
    debtId,
    businessId,
    currency: "PEN",
    amountMinorUnits: 800,
    method: "YAPE",
    note: "Abono semanal",
    reference: "YP-0001",
    expectedDebtVersion: 1,
    balanceAfterMinorUnits: 1_280,
    idempotencyKey: partialRequest.idempotencyKey,
    occurredAt: partialRequest.document.occurredAt,
    createdAt: partial.body.result.debt.updatedAt,
  });
  assert.ok(partial.body.result.payment.createdAt >= partialRequest.document.createdAt);
  assert.equal(partial.body.result.payment.createdAt, partial.body.result.debt.updatedAt);

  const staleRequest = debtPaymentRequest(businessId, debtId, 1, 100);
  const stale = await call("recordDebtPayment", staleRequest, user.token);
  assert.equal(stale.body.error?.status, "ABORTED", JSON.stringify(stale.body));
  assert.equal(stale.body.error?.message, "DEBT_VERSION_CONFLICT");
  assert.equal(
    (await db.doc(
      `businesses/${businessId}/debts/${debtId}/payments/${staleRequest.document.paymentId}`,
    ).get()).exists,
    false,
  );

  const finalRequest = debtPaymentRequest(businessId, debtId, 2, 1_280, {
    method: "CASH",
    note: null,
    reference: null,
    createdAt: partialRequest.document.createdAt + 2_000,
  });
  const final = await call("recordDebtPayment", finalRequest, user.token);
  assert.equal(final.body.error, undefined, JSON.stringify(final.body));
  assert.equal(final.body.result.seq, 3);
  assert.equal(final.body.result.debt.version, 3);
  assert.equal(final.body.result.debt.balanceMinorUnits, 0);
  assert.equal(final.body.result.debt.status, "PAID");
  assert.equal(final.body.result.debt.paidAt, final.body.result.debt.updatedAt);

  // El primer ACK se perdió y se reintenta después de otro pago: debe devolver su snapshot
  // original sin volver a descontar ni exigir que la deuda siga en version 2.
  const recovered = await call("recordDebtPayment", partialRequest, user.token);
  assert.equal(recovered.body.result.status, "ALREADY_RECORDED", JSON.stringify(recovered.body));
  assert.equal(recovered.body.result.seq, 2);
  assert.equal(recovered.body.result.debt.version, 2);
  assert.equal(recovered.body.result.debt.balanceMinorUnits, 1_280);

  const mutatedReplay = structuredClone(partialRequest);
  mutatedReplay.document.amountMinorUnits = 801;
  const mismatch = await call("recordDebtPayment", mutatedReplay, user.token);
  assert.equal(mismatch.body.error?.status, "FAILED_PRECONDITION", JSON.stringify(mismatch.body));
  assert.equal(mismatch.body.error?.message, "DEBT_PAYMENT_REPLAY_MISMATCH");
  const savedDebt = (await db.doc(`businesses/${businessId}/debts/${debtId}`).get()).data();
  assert.equal(savedDebt.balanceMinorUnits, 0);
  assert.equal(savedDebt.version, 3);
  assert.equal(savedDebt.recordedByUid, undefined);

  const pull = await call(
    "listSalesInventoryChanges",
    { businessId, sinceSeq: 1, limit: 200 },
    user.token,
  );
  assert.equal(pull.body.error, undefined, JSON.stringify(pull.body));
  assert.deepEqual(
    pull.body.result.changes.map((change) => [change.seq, change.kind]),
    [[2, "DEBT_PAYMENT"], [3, "DEBT_PAYMENT"]],
  );
  const change = pull.body.result.changes[0];
  assert.deepEqual(Object.keys(change).sort(), [
    "balances", "debt", "kind", "payment", "receiptId", "sale", "seq", "syncedAtMillis",
  ]);
  assert.equal(change.sale, null);
  assert.deepEqual(change.balances, []);
  assert.deepEqual(change.debt, partial.body.result.debt);
  assert.deepEqual(change.payment, partial.body.result.payment);
});

test("stock insuficiente revierte venta, movimientos, feed, cuota y saldo", async () => {
  const user = await verifiedUser();
  const businessId = await seedBusiness(user.uid);
  const productId = randomUUID();
  const balanceRef = await seedProductAndBalance(
    businessId,
    productId,
    "Almacén principal",
    { quantityOnHand: "1" },
  );
  const request = saleRequest(businessId, productId);

  const rejected = await call("postSale", request, user.token);
  assert.equal(rejected.body.error?.status, "FAILED_PRECONDITION", JSON.stringify(rejected.body));
  assert.equal(rejected.body.error?.message, "INSUFFICIENT_STOCK");
  assert.deepEqual(rejected.body.error?.details, {
    productId,
    locationName: "ALMACÉN PRINCIPAL",
    requested: "2",
    available: "1",
  });
  assert.equal((await balanceRef.get()).data().quantityOnHand, "1");
  assert.equal(
    (await db.doc(`businesses/${businessId}/sales/${request.document.saleId}`).get()).exists,
    false,
  );
  assert.equal((await db.collection(`businesses/${businessId}/inventorySyncChanges`).get()).empty, true);
});

test("READER y moneda incompatible no pueden mutar inventario", async () => {
  const reader = await verifiedUser();
  const readerBusiness = await seedBusiness(reader.uid, "READER");
  const readerProduct = randomUUID();
  await seedProductAndBalance(readerBusiness, readerProduct);
  const denied = await call("postSale", saleRequest(readerBusiness, readerProduct), reader.token);
  assert.equal(denied.body.error?.status, "PERMISSION_DENIED", JSON.stringify(denied.body));
  assert.equal(denied.body.error?.message, "ROLE_FORBIDDEN");

  const owner = await verifiedUser();
  const ownerBusiness = await seedBusiness(owner.uid);
  const ownerProduct = randomUUID();
  await seedProductAndBalance(ownerBusiness, ownerProduct);
  const wrongCurrency = saleRequest(ownerBusiness, ownerProduct, { currency: "USD" });
  wrongCurrency.document.subtotalMinorUnits = 2_000;
  wrongCurrency.document.discountMinorUnits = 100;
  wrongCurrency.document.taxMinorUnits = 180;
  wrongCurrency.document.totalMinorUnits = 2_080;
  const rejected = await call("postSale", wrongCurrency, owner.token);
  assert.equal(rejected.body.error?.status, "FAILED_PRECONDITION", JSON.stringify(rejected.body));
  assert.equal(rejected.body.error?.message, "SALE_CURRENCY_MISMATCH");
});

test("postSale rechaza dos lineas del mismo producto y almacen canonico", async () => {
  const user = await verifiedUser();
  const businessId = await seedBusiness(user.uid);
  const productId = randomUUID();
  await seedProductAndBalance(businessId, productId);
  const request = saleRequest(businessId, productId);
  const second = {
    ...request.document.lines[0],
    saleLineId: randomUUID(),
    position: 1,
    locationId: randomUUID(),
    locationName: "  Almacén principal  ",
  };
  // El nombre del wire no permite whitespace exterior, pero sí una variante canónica interna.
  second.locationName = "Almacén   principal";
  request.document.lines.push(second);
  request.document.subtotalMinorUnits *= 2;
  request.document.discountMinorUnits *= 2;
  request.document.taxMinorUnits *= 2;
  request.document.totalMinorUnits *= 2;
  request.document.contentHash = saleContentHash("PEN", request.document.lines);
  request.document.checkoutIdempotencyKey =
    `sale-checkout:v1:${request.document.saleId}:3:${request.document.contentHash}`;

  const rejected = await call("postSale", request, user.token);
  assert.equal(rejected.body.error?.status, "INVALID_ARGUMENT", JSON.stringify(rejected.body));
  assert.equal(rejected.body.error?.message, "SALE_PRODUCT_LOCATION_DUPLICATED");
  assert.equal(
    (await db.doc(`businesses/${businessId}/sales/${request.document.saleId}`).get()).exists,
    false,
  );
});

test("postSale rechaza timestamp que su propio feed no puede materializar", async () => {
  const user = await verifiedUser();
  const businessId = await seedBusiness(user.uid);
  const productId = randomUUID();
  await seedProductAndBalance(businessId, productId);
  const request = saleRequest(businessId, productId);
  request.document.updatedAt = request.document.postedAt + 1;

  const rejected = await call("postSale", request, user.token);
  assert.equal(rejected.body.error?.status, "INVALID_ARGUMENT", JSON.stringify(rejected.body));
  assert.equal(rejected.body.error?.message, "SALE_TIMESTAMPS");
  assert.equal(
    (await db.doc(`businesses/${businessId}/sales/${request.document.saleId}`).get()).exists,
    false,
  );
});

test("purchase v4 crea saldo con costo exacto y void emite la segunda secuencia", async () => {
  const user = await verifiedUser();
  const businessId = await seedBusiness(user.uid);
  const productId = randomUUID();
  const purchase = purchaseV4Request(businessId, productId);

  const posted = await call("postPurchase", purchase, user.token);
  assert.equal(posted.body.error, undefined, JSON.stringify(posted.body));
  assert.equal(posted.body.result.inventorySeq, 1);
  assert.equal(posted.body.result.balances[0].quantityOnHand, "2");
  assert.equal(posted.body.result.balances[0].averageUnitCost, "10");

  const movement = purchase.document.movements[0];
  const voidPayload = {
    version: 1,
    purchaseId: purchase.document.purchaseId,
    impactHash: "b".repeat(64),
    actorId: businessId,
    role: "OWNER",
    reason: "Documento emitido por error material",
    negativeStockPolicy: "ALLOW_WITH_VISIBLE_WARNING",
    averageUnitCostPolicy: "PRESERVE_CURRENT",
    negativeImpactCount: 0,
    impacts: [{
      productId,
      locationId: movement.locationId,
      currentQuantity: "2",
      reversalQuantity: "-2",
      resultingQuantity: "0",
      currentAverageUnitCost: "10",
      currency: "PEN",
      balanceVersion: 0,
      negative: false,
    }],
  };
  const voided = await call("postPurchase", {
    businessId,
    idempotencyKey: `sync-purchase-void:v1:${purchase.document.purchaseId}`,
    operationType: "SYNC_PURCHASE_VOID",
    payloadVersion: 1,
    document: JSON.stringify(voidPayload),
  }, user.token);
  assert.equal(voided.body.error, undefined, JSON.stringify(voided.body));
  assert.equal(voided.body.result.inventorySeq, 2);
  assert.equal(voided.body.result.balances[0].quantityOnHand, "0");
  assert.equal(voided.body.result.balances[0].averageUnitCost, "10");

  const pull = await call(
    "listSalesInventoryChanges",
    { businessId, sinceSeq: 0, limit: 200 },
    user.token,
  );
  assert.deepEqual(
    pull.body.result.changes.map((change) => [change.seq, change.kind]),
    [[1, "PURCHASE"], [2, "PURCHASE_VOID"]],
  );
});

test("alta purchase legacy falla cerrada y no reserva identidad ni secuencia", async () => {
  const user = await verifiedUser();
  const businessId = await seedBusiness(user.uid);
  const productId = randomUUID();
  const request = purchaseV4Request(businessId, productId);
  request.payloadVersion = 3;
  request.document.version = 2;
  delete request.document.movements[0].locationName;
  delete request.document.movements[0].appliedCostTotal;

  const rejected = await call("postPurchase", request, user.token);
  assert.equal(rejected.body.error?.status, "FAILED_PRECONDITION", JSON.stringify(rejected.body));
  assert.equal(rejected.body.error?.message, "INVENTORY_WIRE_MIGRATION_REQUIRED");
  assert.equal(
    (await db.doc(`businesses/${businessId}/purchases/${request.document.purchaseId}`).get()).exists,
    false,
  );
  assert.equal(
    (await db.doc(`businesses/${businessId}/sync/inventoryMetadata`).get()).exists,
    false,
  );
});

test("bootstrap legacy acota saldo por compras, audita ventas locales y habilita venta seq 2", async () => {
  const user = await verifiedUser();
  const businessId = await seedBusiness(user.uid);
  const productId = randomUUID();
  const sourceLocationId = randomUUID();
  await db.doc(`businesses/${businessId}/products/${productId}`).set({
    entityId: productId,
    entityType: "PRODUCT",
    version: 1,
    snapshot: {
      name: "Arroz extra",
      inventoryUnit: { code: "NIU", name: "Unidad", symbol: "und", status: "ACTIVE" },
      salePriceMinorUnits: 1_000,
      salePriceCurrencyCode: "PEN",
      status: "ACTIVE",
    },
  });
  await db.doc(`businesses/${businessId}/purchases/${randomUUID()}`).set({
    version: 2,
    status: "POSTED",
    seq: 1,
    currency: "PEN",
    movements: [{
      movementId: randomUUID(),
      productId,
      locationId: sourceLocationId,
      type: "PURCHASE",
      quantityDelta: "10",
      unitCost: "4.5",
      occurredAt: 1_787_000_000_000,
    }],
  });
  const request = {
    businessId,
    expectedUid: user.uid,
    idempotencyKey: `inventory-bootstrap:v1:${businessId}`,
    payloadVersion: 1,
    locations: [{ sourceLocationId, locationName: "Almacén principal" }],
    balances: [{
      productId,
      locationName: "ALMACÉN   PRINCIPAL",
      quantityOnHand: "7",
      averageUnitCost: "4.5",
      currency: "PEN",
    }],
  };
  const bootstrapped = await call("bootstrapInventoryBalances", request, user.token);
  assert.equal(bootstrapped.body.error, undefined, JSON.stringify(bootstrapped.body));
  assert.equal(bootstrapped.body.result.status, "BOOTSTRAPPED");
  assert.equal(bootstrapped.body.result.seq, 1);
  assert.match(
    bootstrapped.body.result.receiptId,
    /^inventory_bootstrap_[0-9a-f]{32}$/,
  );
  assert.equal(bootstrapped.body.result.balances[0].quantityOnHand, "7");
  const audit = (await db.doc(
    `businesses/${businessId}/auditEvents/inventory-bootstrap-audit`,
  ).get()).data();
  assert.equal(audit.legacyUnreplicatedSales[0].quantity, "3");

  const replay = await call("bootstrapInventoryBalances", request, user.token);
  assert.equal(replay.body.result.status, "ALREADY_BOOTSTRAPPED", JSON.stringify(replay.body));
  assert.equal(replay.body.result.seq, 1);

  const sale = await call("postSale", saleRequest(businessId, productId), user.token);
  assert.equal(sale.body.error, undefined, JSON.stringify(sale.body));
  assert.equal(sale.body.result.seq, 2);
  assert.equal(sale.body.result.balances[0].quantityOnHand, "5");
  assert.equal(
    (await db.doc(`businesses/${businessId}/sync/inventoryMetadata`).get())
      .data().bootstrapComplete,
    true,
  );
  const replayAfterSale = await call("bootstrapInventoryBalances", request, user.token);
  assert.equal(
    replayAfterSale.body.result.status,
    "ALREADY_BOOTSTRAPPED",
    JSON.stringify(replayAfterSale.body),
  );
  assert.equal(replayAfterSale.body.result.seq, 1);
  const pull = await call(
    "listSalesInventoryChanges",
    { businessId, sinceSeq: 0, limit: 200 },
    user.token,
  );
  assert.deepEqual(
    pull.body.result.changes.map((change) => [change.seq, change.kind]),
    [[1, "PURCHASE"], [2, "SALE"]],
  );
});

test("bootstrap legacy rechaza cantidad mayor a compras sin escribir estado", async () => {
  const user = await verifiedUser();
  const businessId = await seedBusiness(user.uid);
  const productId = randomUUID();
  const sourceLocationId = randomUUID();
  await db.doc(`businesses/${businessId}/products/${productId}`).set({
    entityType: "PRODUCT",
    snapshot: { status: "ACTIVE" },
  });
  await db.doc(`businesses/${businessId}/purchases/${randomUUID()}`).set({
    version: 1,
    status: "POSTED",
    seq: 1,
    currency: "PEN",
    movements: [{
      productId,
      locationId: sourceLocationId,
      type: "PURCHASE",
      quantityDelta: "2",
      occurredAt: 1_787_000_000_000,
    }],
  });
  const rejected = await call("bootstrapInventoryBalances", {
    businessId,
    idempotencyKey: `inventory-bootstrap:v1:${businessId}`,
    payloadVersion: 1,
    locations: [{ sourceLocationId, locationName: "Principal" }],
    balances: [{
      productId,
      locationName: "Principal",
      quantityOnHand: "3",
      averageUnitCost: "1",
      currency: "PEN",
    }],
  }, user.token);
  assert.equal(rejected.body.error?.status, "FAILED_PRECONDITION", JSON.stringify(rejected.body));
  assert.equal(rejected.body.error?.message, "INVENTORY_BOOTSTRAP_QUANTITY_EXCEEDS_LEDGER");
  assert.equal(
    (await db.doc(`businesses/${businessId}/sync/inventoryMetadata`).get()).exists,
    false,
  );
  assert.equal(
    (await db.collection(`businesses/${businessId}/inventoryBalances`).get()).empty,
    true,
  );
});

test("bootstrap habilita anulacion legacy y conserva replay e inventario secuencial", async () => {
  const user = await verifiedUser();
  const businessId = await seedBusiness(user.uid);
  const productId = randomUUID();
  const source = purchaseV4Request(businessId, productId);
  const legacy = structuredClone(source.document);
  legacy.version = 2;
  delete legacy.movements[0].locationName;
  delete legacy.movements[0].appliedCostTotal;
  const movement = legacy.movements[0];
  const receiptId = `rcpt_${"1".repeat(32)}`;
  await db.doc(`businesses/${businessId}/products/${productId}`).set({
    entityType: "PRODUCT",
    snapshot: { status: "ACTIVE" },
  });
  await db.doc(`businesses/${businessId}/purchases/${legacy.purchaseId}`).set({
    ...legacy,
    seq: 1,
    receiptId,
    movementSummary: [{
      productId,
      productName: legacy.lines[0].productName,
      type: "PURCHASE",
      quantityDelta: "2",
    }],
  });
  await db.doc(`businesses/${businessId}/syncChanges/${legacy.purchaseId}`).set({
    purchaseId: legacy.purchaseId,
    status: "POSTED",
    seq: 1,
    receiptId,
  });
  await db.doc(`businesses/${businessId}/sync/metadata`).set({ seq: 1 });
  const bootstrap = await call("bootstrapInventoryBalances", {
    businessId,
    idempotencyKey: `inventory-bootstrap:v1:${businessId}`,
    payloadVersion: 1,
    locations: [{
      sourceLocationId: movement.locationId,
      locationName: "Principal",
    }],
    balances: [{
      productId,
      locationName: "Principal",
      quantityOnHand: "1",
      averageUnitCost: "10",
      currency: "PEN",
    }],
  }, user.token);
  assert.equal(bootstrap.body.error, undefined, JSON.stringify(bootstrap.body));

  const payload = {
    version: 1,
    purchaseId: legacy.purchaseId,
    impactHash: "c".repeat(64),
    actorId: businessId,
    role: "OWNER",
    reason: "Anulación de compra histórica migrada",
    negativeStockPolicy: "ALLOW_WITH_VISIBLE_WARNING",
    averageUnitCostPolicy: "PRESERVE_CURRENT",
    negativeImpactCount: 1,
    impacts: [{
      productId,
      locationId: movement.locationId,
      currentQuantity: "1",
      reversalQuantity: "-2",
      resultingQuantity: "-1",
      currentAverageUnitCost: "10",
      currency: "PEN",
      balanceVersion: 0,
      negative: true,
    }],
  };
  const request = {
    businessId,
    idempotencyKey: `sync-purchase-void:v1:${legacy.purchaseId}`,
    operationType: "SYNC_PURCHASE_VOID",
    payloadVersion: 1,
    document: JSON.stringify(payload),
  };
  const voided = await call("postPurchase", request, user.token);
  assert.equal(voided.body.error, undefined, JSON.stringify(voided.body));
  assert.equal(voided.body.result.inventorySeq, 2);
  assert.equal(voided.body.result.balances[0].quantityOnHand, "-1");
  const replay = await call("postPurchase", request, user.token);
  assert.equal(replay.body.result.status, "ALREADY_RECORDED", JSON.stringify(replay.body));
  assert.equal(replay.body.result.inventorySeq, 2);
});
