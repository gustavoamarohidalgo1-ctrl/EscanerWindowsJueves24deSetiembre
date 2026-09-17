import { after, before, test } from "node:test";
import assert from "node:assert/strict";
import { createHash, randomUUID } from "node:crypto";
import { deleteApp as deleteAdminApp, initializeApp as initializeAdminApp } from "firebase-admin/app";
import { getAuth as getAdminAuth } from "firebase-admin/auth";
import { getFirestore as getAdminFirestore } from "firebase-admin/firestore";

const ENABLED = process.env.FACTURASTOCK_SPARK_RULES_TEST === "1";

if (!ENABLED) {
  test("reglas Spark se ejecutan solo con firebase.spark.json", { skip: true }, () => {});
} else {
  process.env.FIRESTORE_EMULATOR_HOST ??= "127.0.0.1:8180";
  process.env.FIREBASE_AUTH_EMULATOR_HOST ??= "127.0.0.1:9198";
  process.env.GCLOUD_PROJECT ??= "demo-facturastock-spark";

  const PROJECT = process.env.GCLOUD_PROJECT;
  const AUTH_URL = `http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}` +
    "/identitytoolkit.googleapis.com/v1";
  const firestoreUrl = new URL(`http://${process.env.FIRESTORE_EMULATOR_HOST}`);
  const PASSWORD = "spark-rules-password-123";
  const businessId = randomUUID();
  const productId = randomUUID();
  const supplierId = randomUUID();
  const purchaseId = randomUUID();
  const saleId = randomUUID();
  const saleLineId = randomUUID();
  const debtId = randomUUID();
  const paymentId = randomUUID();
  const unitId = randomUUID();
  const locationId = randomUUID();
  const balanceId = "b".repeat(64);
  const bootstrapBusinessId = randomUUID();
  const bootstrapProductId = randomUUID();
  const bootstrapBalanceId = "9".repeat(64);
  const largeBootstrapBusinessId = randomUUID();
  const overrideBusinessId = randomUUID();
  const runtimeCreateBusinessId = randomUUID();
  const clients = [];
  const users = {};
  const adminApp = initializeAdminApp({ projectId: PROJECT }, `spark-rules-${Date.now()}`);
  const adminAuth = getAdminAuth(adminApp);
  const adminDb = getAdminFirestore(adminApp);

  async function authRest(path, payload) {
    const response = await fetch(`${AUTH_URL}/${path}?key=demo-api-key`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });
    const body = await response.json();
    assert.ok(body.idToken, `${path}: ${JSON.stringify(body)}`);
    return body;
  }

  async function createUser(prefix, verified) {
    const email = `spark-${prefix}-${randomUUID()}@example.test`;
    const { localId } = await authRest("accounts:signUp", {
      email,
      password: PASSWORD,
      returnSecureToken: true,
    });
    if (verified) await adminAuth.updateUser(localId, { emailVerified: true });
    return { uid: localId, email };
  }

  async function clientFor(user) {
    const { deleteApp, initializeApp } = await import("firebase/app");
    const {
      connectAuthEmulator,
      getAuth,
      signInWithEmailAndPassword,
    } = await import("firebase/auth");
    const {
      connectFirestoreEmulator,
      getFirestore,
    } = await import("firebase/firestore");
    const app = initializeApp({
      projectId: PROJECT,
      apiKey: "demo-api-key",
      authDomain: "localhost",
    }, `spark-${user.uid}-${Date.now()}-${randomUUID()}`);
    const auth = getAuth(app);
    connectAuthEmulator(auth, `http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}`, {
      disableWarnings: true,
    });
    await signInWithEmailAndPassword(auth, user.email, PASSWORD);
    const firestore = getFirestore(app);
    connectFirestoreEmulator(
      firestore,
      firestoreUrl.hostname,
      Number(firestoreUrl.port),
    );
    clients.push({ app, deleteApp });
    return firestore;
  }

  async function denied(promise, label) {
    const outcome = await promise.then(
      () => "ALLOWED",
      (error) => error.code === "permission-denied" ? "DENIED" : `OTHER:${error.code}`,
    );
    assert.equal(outcome, "DENIED", label);
  }

  function sha256(value) {
    return createHash("sha256").update(value, "utf8").digest("hex");
  }

  function purchaseIdentityId(purchase) {
    return sha256(JSON.stringify([
      purchase.supplierRuc ?? "",
      purchase.documentType,
      purchase.documentSeries,
      purchase.documentNumber,
    ]));
  }

  function purchaseOverrideSlotId(identityId, sourceDraftId) {
    return sha256(JSON.stringify(["OVERRIDE", identityId, sourceDraftId]));
  }

  function businessDocuments(user, id = businessId) {
    const now = Date.now();
    return {
      business: {
        schemaVersion: 1,
        businessId: id,
        displayName: "Bodega Spark",
        ownerUid: user.uid,
        createdBy: user.uid,
        createdAt: now,
      },
      member: {
        schemaVersion: 1,
        uid: user.uid,
        email: user.email,
        role: "OWNER",
        addedAt: now,
        addedVia: "spark_direct",
        ownerUid: user.uid,
      },
      membership: {
        schemaVersion: 1,
        businessId: id,
        uid: user.uid,
        displayName: "Bodega Spark",
        role: "OWNER",
        createdAt: now,
      },
    };
  }

  async function commitBusinessCreateBatch(firestore, user, id = businessId) {
    const { doc, writeBatch } = await import("firebase/firestore");
    const values = businessDocuments(user, id);
    const batch = writeBatch(firestore);
    batch.set(doc(firestore, `businesses/${id}`), values.business);
    batch.set(doc(firestore, `businesses/${id}/members/${user.uid}`), values.member);
    batch.set(doc(firestore, `users/${user.uid}/memberships/${id}`), values.membership);
    await batch.commit();
  }

  async function createBusiness(firestore, user, id = businessId) {
    await commitBusinessCreateBatch(firestore, user, id);
  }

  function catalogDocuments(user, version, seq, receiptId) {
    const snapshot = {
      productId,
      businessId,
      name: version === 1 ? "Arroz" : "Arroz premium",
      status: "ACTIVE",
      sku: version === 1 ? "SKU-ARROZ" : `SKU-ARROZ-${version}`,
      barcode: "7750000000001",
    };
    const payload = JSON.stringify(snapshot);
    const sha = sha256(payload);
    const idempotencyKey = `product:v1:${productId}:${version}`;
    const operationId = sha256(idempotencyKey);
    const base = {
      schemaVersion: 1,
      businessId,
      ownerUid: user.uid,
      operationId,
      entityId: productId,
      entityType: "PRODUCT",
      version,
      mutation: "UPSERT",
      snapshot,
      snapshotPayload: payload,
      snapshotSha256: sha,
      receiptId,
      syncedAt: Date.now(),
    };
    return {
      entity: base,
      change: {
        schemaVersion: 1,
        seq,
        entityType: "PRODUCT",
        entityId: productId,
        version,
        mutation: "UPSERT",
        snapshot,
        snapshotPayload: payload,
        snapshotSha256: sha,
        receiptId,
        syncedAt: Date.now(),
        ownerUid: user.uid,
      },
      operation: {
        schemaVersion: 1,
        businessId,
        ownerUid: user.uid,
        operationId,
        idempotencyKey,
        requestHash: String(version).repeat(64),
        operationType: "SYNC_PRODUCT",
        entityId: productId,
        version,
        receiptId,
        seq,
        createdAt: Date.now(),
      },
      metadata: {
        schemaVersion: 1,
        businessId,
        ownerUid: user.uid,
        seq,
        lastOperationId: operationId,
        updatedAt: Date.now(),
      },
    };
  }

  async function writeCatalogVersion(firestore, user, version, seq) {
    const { doc, writeBatch } = await import("firebase/firestore");
    const receiptId = `rcpt_${String(version).repeat(32)}`;
    const values = catalogDocuments(user, version, seq, receiptId);
    const batch = writeBatch(firestore);
    batch.set(doc(firestore, `businesses/${businessId}/products/${productId}`), values.entity);
    batch.set(doc(firestore, `businesses/${businessId}/catalogSyncChanges/${receiptId}`), values.change);
    batch.set(
      doc(firestore, `businesses/${businessId}/catalogSyncOperations/${values.operation.operationId}`),
      values.operation,
    );
    batch.set(doc(firestore, `businesses/${businessId}/sync/catalogMetadata`), values.metadata);
    const skuId = sha256(values.entity.snapshot.sku);
    const barcodeId = sha256(values.entity.snapshot.barcode);
    if (version > 1) {
      const previous = catalogDocuments(
        user,
        version - 1,
        seq - 1,
        `rcpt_${String(version - 1).repeat(32)}`,
      );
      if (previous.entity.snapshot.sku !== values.entity.snapshot.sku) {
        batch.delete(doc(
          firestore,
          `businesses/${businessId}/productSkuIndex/${sha256(previous.entity.snapshot.sku)}`,
        ));
      }
    }
    batch.set(doc(firestore, `businesses/${businessId}/productSkuIndex/${skuId}`), {
      entityId: productId,
      version,
    });
    batch.set(doc(firestore, `businesses/${businessId}/productBarcodeIndex/${barcodeId}`), {
      entityId: productId,
      version,
    });
    await batch.commit();
  }

  before(async () => {
    users.owner = await createUser("owner", true);
    users.outsider = await createUser("outsider", true);
    users.unverified = await createUser("unverified", false);
  });

  after(async () => {
    await adminDb.recursiveDelete(adminDb.doc(`businesses/${businessId}`));
    await adminDb.recursiveDelete(adminDb.doc(`businesses/${bootstrapBusinessId}`));
    await adminDb.recursiveDelete(adminDb.doc(`businesses/${largeBootstrapBusinessId}`));
    await adminDb.recursiveDelete(adminDb.doc(`businesses/${overrideBusinessId}`));
    await adminDb.recursiveDelete(adminDb.doc(`businesses/${runtimeCreateBusinessId}`));
    for (const user of Object.values(users)) {
      await adminDb.recursiveDelete(adminDb.doc(`users/${user.uid}`));
    }
    for (const { app, deleteApp } of clients) await deleteApp(app);
    await deleteAdminApp(adminApp);
  });

  test("alta requiere root, miembro OWNER e índice propio en el mismo batch", async () => {
    const firestore = await clientFor(users.owner);
    await createBusiness(firestore, users.owner);
    const { doc, getDoc } = await import("firebase/firestore");
    assert.equal((await getDoc(doc(firestore, `businesses/${businessId}`))).exists(), true);
    assert.equal(
      (await getDoc(doc(
        firestore,
        `users/${users.owner.uid}/memberships/${businessId}`,
      ))).exists(),
      true,
    );

    const missingCompanionId = randomUUID();
    await denied(
      (await import("firebase/firestore")).setDoc(
        doc(firestore, `businesses/${missingCompanionId}`),
        businessDocuments(users.owner, missingCompanionId).business,
      ),
      "root aislado",
    );
  });

  test("alta runtime usa batch sin leer ausentes y replay verifica la tripleta existente", async () => {
    const firestore = await clientFor(users.owner);
    const {
      collection,
      doc,
      getDoc,
      getDocs,
      runTransaction,
    } = await import("firebase/firestore");
    const businessRef = doc(firestore, `businesses/${runtimeCreateBusinessId}`);
    const memberRef = doc(
      firestore,
      `businesses/${runtimeCreateBusinessId}/members/${users.owner.uid}`,
    );
    const membershipRef = doc(
      firestore,
      `users/${users.owner.uid}/memberships/${runtimeCreateBusinessId}`,
    );

    await denied(getDoc(businessRef), "GET de root ausente no abre un oráculo");
    await denied(getDoc(memberRef), "GET de miembro ausente no abre un oráculo");
    await denied(
      runTransaction(firestore, async (transaction) => transaction.get(businessRef)),
      "la transacción antigua no puede preleer el root ausente",
    );
    await denied(
      getDocs(collection(firestore, `businesses/${runtimeCreateBusinessId}/members`)),
      "la excepción de alta no habilita listados",
    );

    await commitBusinessCreateBatch(firestore, users.owner, runtimeCreateBusinessId);

    await denied(
      commitBusinessCreateBatch(firestore, users.owner, runtimeCreateBusinessId),
      "el segundo set se evalúa como update y permanece bloqueado",
    );

    const [business, member, membership] = await Promise.all([
      getDoc(businessRef),
      getDoc(memberRef),
      getDoc(membershipRef),
    ]);
    const expected = businessDocuments(users.owner, runtimeCreateBusinessId);
    assert.deepEqual(new Set(Object.keys(business.data())), new Set(Object.keys(expected.business)));
    assert.deepEqual(new Set(Object.keys(member.data())), new Set(Object.keys(expected.member)));
    assert.deepEqual(
      new Set(Object.keys(membership.data())),
      new Set(Object.keys(expected.membership)),
    );
    assert.equal(business.data().businessId, runtimeCreateBusinessId);
    assert.equal(business.data().ownerUid, users.owner.uid);
    assert.equal(business.data().displayName, expected.business.displayName);
    assert.equal(member.data().uid, users.owner.uid);
    assert.equal(member.data().email, users.owner.email);
    assert.equal(member.data().role, "OWNER");
    assert.equal(membership.data().uid, users.owner.uid);
    assert.equal(membership.data().businessId, runtimeCreateBusinessId);
    assert.equal(membership.data().displayName, expected.membership.displayName);
    assert.equal(membership.data().role, "OWNER");
  });

  test("correo no verificado y otra cuenta no acceden al negocio", async () => {
    const { doc, getDoc, setDoc } = await import("firebase/firestore");
    const unverified = await clientFor(users.unverified);
    const outsider = await clientFor(users.outsider);
    await denied(getDoc(doc(unverified, `businesses/${businessId}`)), "lectura sin verificar");
    await denied(getDoc(doc(outsider, `businesses/${businessId}`)), "lectura cross-account");
    const foreignId = randomUUID();
    await denied(
      setDoc(doc(unverified, `businesses/${foreignId}`), businessDocuments(users.unverified, foreignId).business),
      "alta sin verificar",
    );
  });

  test("catálogo usa CAS + feed + operación y nunca permite borrar", async () => {
    const firestore = await clientFor(users.owner);
    await writeCatalogVersion(firestore, users.owner, 1, 1);
    await writeCatalogVersion(firestore, users.owner, 2, 2);
    const { deleteDoc, doc, getDoc, setDoc, writeBatch } = await import("firebase/firestore");
    const product = doc(firestore, `businesses/${businessId}/products/${productId}`);
    assert.equal((await getDoc(product)).data().version, 2);
    assert.equal((await getDoc(doc(
      firestore,
      `businesses/${businessId}/productSkuIndex/${sha256("SKU-ARROZ")}`,
    ))).exists(), false);
    assert.deepEqual((await getDoc(doc(
      firestore,
      `businesses/${businessId}/productSkuIndex/${sha256("SKU-ARROZ-2")}`,
    ))).data(), { entityId: productId, version: 2 });
    assert.deepEqual((await getDoc(doc(
      firestore,
      `businesses/${businessId}/productBarcodeIndex/${sha256("7750000000001")}`,
    ))).data(), { entityId: productId, version: 2 });

    const wrongHashValues = catalogDocuments(users.owner, 3, 3, `rcpt_${"3".repeat(32)}`);
    const wrongHashBatch = writeBatch(firestore);
    wrongHashBatch.set(product, wrongHashValues.entity);
    wrongHashBatch.set(
      doc(firestore, `businesses/${businessId}/catalogSyncChanges/${wrongHashValues.change.receiptId}`),
      wrongHashValues.change,
    );
    wrongHashBatch.set(
      doc(firestore, `businesses/${businessId}/catalogSyncOperations/${wrongHashValues.operation.operationId}`),
      wrongHashValues.operation,
    );
    wrongHashBatch.set(
      doc(firestore, `businesses/${businessId}/sync/catalogMetadata`),
      wrongHashValues.metadata,
    );
    wrongHashBatch.delete(doc(
      firestore,
      `businesses/${businessId}/productSkuIndex/${sha256("SKU-ARROZ-2")}`,
    ));
    wrongHashBatch.set(doc(
      firestore,
      `businesses/${businessId}/productSkuIndex/${sha256("VALOR-DIFERENTE")}`,
    ), { entityId: productId, version: 3 });
    wrongHashBatch.set(doc(
      firestore,
      `businesses/${businessId}/productBarcodeIndex/${sha256("7750000000001")}`,
    ), { entityId: productId, version: 3 });
    await denied(wrongHashBatch.commit(), "hash de índice distinto del snapshot");

    await denied(deleteDoc(product), "borrado físico de producto");

    const invalid = catalogDocuments(users.owner, 4, 3, `rcpt_${"4".repeat(32)}`);
    await denied(setDoc(product, invalid.entity), "salto de versión sin CAS");
    await denied(
      setDoc(product, {
        ...catalogDocuments(users.owner, 3, 3, `rcpt_${"3".repeat(32)}`).entity,
        admin: true,
      }),
      "campo no permitido",
    );
  });

  test("índice RUC de proveedor sigue el mismo CAS y libera la clave anterior", async () => {
    const firestore = await clientFor(users.owner);
    const { doc, getDoc, writeBatch } = await import("firebase/firestore");
    const writeSupplier = async (version, seq, ruc, oldRuc, receiptCharacter) => {
      const receiptId = `rcpt_${receiptCharacter.repeat(32)}`;
      const idempotencyKey = `supplier:v1:${supplierId}:${version}`;
      const operationId = sha256(idempotencyKey);
      const snapshot = {
        supplierId,
        businessId,
        legalName: "Proveedor Spark",
        ruc,
      };
      const snapshotPayload = JSON.stringify(snapshot);
      const snapshotSha256 = sha256(snapshotPayload);
      const entity = {
        schemaVersion: 1,
        businessId,
        ownerUid: users.owner.uid,
        operationId,
        entityId: supplierId,
        entityType: "SUPPLIER",
        version,
        mutation: "UPSERT",
        snapshot,
        snapshotPayload,
        snapshotSha256,
        receiptId,
        syncedAt: Date.now(),
      };
      const batch = writeBatch(firestore);
      batch.set(doc(firestore, `businesses/${businessId}/suppliers/${supplierId}`), entity);
      batch.set(doc(firestore, `businesses/${businessId}/catalogSyncChanges/${receiptId}`), {
        schemaVersion: 1,
        seq,
        entityType: "SUPPLIER",
        entityId: supplierId,
        version,
        mutation: "UPSERT",
        snapshot,
        snapshotPayload,
        snapshotSha256,
        receiptId,
        syncedAt: Date.now(),
        ownerUid: users.owner.uid,
      });
      batch.set(doc(
        firestore,
        `businesses/${businessId}/catalogSyncOperations/${operationId}`,
      ), {
        schemaVersion: 1,
        businessId,
        ownerUid: users.owner.uid,
        operationId,
        idempotencyKey,
        requestHash: receiptCharacter.repeat(64),
        operationType: "SYNC_SUPPLIER",
        entityId: supplierId,
        version,
        receiptId,
        seq,
        createdAt: Date.now(),
      });
      batch.set(doc(firestore, `businesses/${businessId}/sync/catalogMetadata`), {
        schemaVersion: 1,
        businessId,
        ownerUid: users.owner.uid,
        seq,
        lastOperationId: operationId,
        updatedAt: Date.now(),
      });
      if (oldRuc !== null) {
        batch.delete(doc(
          firestore,
          `businesses/${businessId}/supplierRucIndex/${sha256(oldRuc)}`,
        ));
      }
      batch.set(doc(
        firestore,
        `businesses/${businessId}/supplierRucIndex/${sha256(ruc)}`,
      ), { entityId: supplierId, version });
      await batch.commit();
    };

    await writeSupplier(1, 3, "20123456789", null, "b");
    await writeSupplier(2, 4, "20987654321", "20123456789", "c");
    assert.equal((await getDoc(doc(
      firestore,
      `businesses/${businessId}/supplierRucIndex/${sha256("20123456789")}`,
    ))).exists(), false);
    assert.deepEqual((await getDoc(doc(
      firestore,
      `businesses/${businessId}/supplierRucIndex/${sha256("20987654321")}`,
    ))).data(), { entityId: supplierId, version: 2 });
  });

  test("compra e inventario se confirman atómicamente y quedan sin borrado", async () => {
    const firestore = await clientFor(users.owner);
    const { deleteDoc, doc, writeBatch } = await import("firebase/firestore");
    const operationId = `sync-purchase:v1:${purchaseId}`;
    const receiptId = `rcpt_${"a".repeat(32)}`;
    const now = Date.now();
    const projection = {
      productId,
      locationName: "Tienda",
      quantityOnHand: "5",
      averageUnitCost: "2.5",
      currency: "PEN",
      version: 0,
      updatedAtMillis: now,
      seq: 1,
    };
    const movementSummary = [{
      productId,
      productName: "Arroz",
      type: "PURCHASE",
      quantityDelta: "5",
    }];
    const purchase = {
      version: 3,
      purchaseId,
      businessId,
      status: "POSTED",
      documentType: "INVOICE",
      documentSeries: "F001",
      documentNumber: "42",
      issueDate: "2026-08-31",
      currency: "PEN",
      supplierRuc: null,
      supplierLegalName: "Proveedor Demo",
      subtotalMinorUnits: 1_250,
      taxMinorUnits: 0,
      otherChargesMinorUnits: 0,
      totalMinorUnits: 1_250,
      adjustmentMinorUnits: null,
      adjustmentReason: null,
      preparedLogicalHash: "b".repeat(64),
      postedAt: now,
      idempotencyKey: operationId,
      lines: [{ purchaseLineId: randomUUID() }],
      movements: [{ productId, locationId }],
      auditEventIds: [randomUUID()],
      duplicateOverride: null,
      schemaVersion: 1,
      ownerUid: users.owner.uid,
      syncPayloadHash: "c".repeat(64),
      seq: 1,
      movementSummary,
      receiptId,
      syncedAt: now,
      syncedBy: users.owner.uid,
      inventorySeq: 1,
      lastOperationId: operationId,
    };
    const batch = writeBatch(firestore);
    batch.set(doc(firestore, `businesses/${businessId}/purchases/${purchaseId}`), purchase);
    batch.set(doc(firestore, `businesses/${businessId}/syncChanges/${purchaseId}`), {
      schemaVersion: 1,
      businessId,
      ownerUid: users.owner.uid,
      operationId,
      seq: 1,
      purchaseId,
      status: "POSTED",
      documentType: "INVOICE",
      documentSeries: "F001",
      documentNumber: "42",
      issueDate: "2026-08-31",
      currency: "PEN",
      supplierRuc: null,
      supplierLegalName: "Proveedor Demo",
      totalMinorUnits: 1_250,
      movementSummary,
      receiptId,
      syncedAt: now,
    });
    batch.set(doc(firestore, `businesses/${businessId}/syncKeys/${"d".repeat(64)}`), {
      schemaVersion: 1,
      businessId,
      ownerUid: users.owner.uid,
      operationId,
      idempotencyKey: operationId,
      purchaseId,
      receiptId,
      seq: 1,
      inventorySeq: 1,
      requestHash: "c".repeat(64),
      createdAt: now,
    });
    batch.set(doc(
      firestore,
      `businesses/${businessId}/documentIndex/${purchaseIdentityId(purchase)}`,
    ), {
      schemaVersion: 1,
      businessId,
      ownerUid: users.owner.uid,
      purchaseId,
      receiptId,
      slot: "PRIMARY",
      createdAt: now,
    });
    batch.set(doc(firestore, `businesses/${businessId}/sync/metadata`), {
      schemaVersion: 1,
      businessId,
      seq: 1,
      lastSyncedAt: now,
      lastPurchaseId: purchaseId,
      lastReceiptId: receiptId,
      lastOperationId: operationId,
    });
    batch.set(doc(firestore, `businesses/${businessId}/inventoryBalances/${balanceId}`), {
      schemaVersion: 1,
      businessId,
      productId,
      locationName: "Tienda",
      canonicalLocationName: "tienda",
      quantityOnHand: "5",
      averageUnitCost: "2.5",
      currency: "PEN",
      version: 0,
      lastSeq: 1,
      updatedAtMillis: now,
      lastSourceLocationId: locationId,
      lastOperationId: operationId,
      expectedVersion: null,
      syncedAt: now,
    });
    batch.set(doc(firestore, `businesses/${businessId}/inventorySyncChanges/${receiptId}`), {
      schemaVersion: 1,
      businessId,
      operationId,
      kind: "PURCHASE",
      seq: 1,
      receiptId,
      sale: null,
      balances: [projection],
      syncedAt: now,
    });
    batch.set(doc(firestore, `businesses/${businessId}/sync/inventoryMetadata`), {
      schemaVersion: 1,
      businessId,
      seq: 1,
      lastKind: "PURCHASE",
      lastReceiptId: receiptId,
      lastOperationId: operationId,
      updatedAt: now,
    });
    await batch.commit();
    await denied(
      deleteDoc(doc(firestore, `businesses/${businessId}/purchases/${purchaseId}`)),
      "borrado físico de compra",
    );
    await denied(
      deleteDoc(doc(firestore, `businesses/${businessId}/inventoryBalances/${balanceId}`)),
      "borrado físico de saldo",
    );
  });

  test("override de compra exige target PRIMARY y slot hash compatible con Blaze", async () => {
    const firestore = await clientFor(users.owner);
    await createBusiness(firestore, users.owner, overrideBusinessId);
    const { doc, getDoc, writeBatch } = await import("firebase/firestore");
    const targetPurchaseId = randomUUID();
    const targetReceiptId = `rcpt_${"4".repeat(32)}`;
    const target = {
      purchaseId: targetPurchaseId,
      businessId: overrideBusinessId,
      status: "POSTED",
      supplierRuc: "20123456789",
      documentType: "INVOICE",
      documentSeries: "F001",
      documentNumber: "77",
      receiptId: targetReceiptId,
    };
    const identityId = purchaseIdentityId(target);
    const overrideProductId = randomUUID();
    const overrideLocationId = randomUUID();
    const overrideBalanceId = "7".repeat(64);
    const seedTime = Date.now();
    const root = adminDb.doc(`businesses/${overrideBusinessId}`);
    await Promise.all([
      root.collection("purchases").doc(targetPurchaseId).set(target),
      root.collection("documentIndex").doc(identityId).set({
        purchaseId: targetPurchaseId,
        receiptId: targetReceiptId,
        slot: "PRIMARY",
      }),
      root.collection("sync").doc("metadata").set({
        schemaVersion: 1,
        businessId: overrideBusinessId,
        seq: 1,
        lastSyncedAt: seedTime,
        lastPurchaseId: targetPurchaseId,
        lastReceiptId: targetReceiptId,
        lastOperationId: `sync-purchase:v1:${targetPurchaseId}`,
      }),
      root.collection("sync").doc("inventoryMetadata").set({
        schemaVersion: 1,
        businessId: overrideBusinessId,
        seq: 1,
        lastKind: "PURCHASE",
        lastReceiptId: targetReceiptId,
        lastOperationId: `sync-purchase:v1:${targetPurchaseId}`,
        updatedAt: seedTime,
      }),
      root.collection("inventoryBalances").doc(overrideBalanceId).set({
        schemaVersion: 1,
        businessId: overrideBusinessId,
        productId: overrideProductId,
        locationName: "Tienda",
        canonicalLocationName: "tienda",
        quantityOnHand: "5",
        averageUnitCost: "2.5",
        currency: "PEN",
        version: 0,
        lastSeq: 1,
        updatedAtMillis: seedTime,
        lastSourceLocationId: overrideLocationId,
        lastOperationId: `sync-purchase:v1:${targetPurchaseId}`,
        expectedVersion: null,
        syncedAt: seedTime,
      }),
    ]);

    const duplicatePurchaseId = randomUUID();
    const sourceDraftId = randomUUID();
    const overrideAuditId = randomUUID();
    const operationId = `sync-purchase:v1:${duplicatePurchaseId}`;
    const receiptId = `rcpt_${"5".repeat(32)}`;
    const slotId = purchaseOverrideSlotId(identityId, sourceDraftId);
    const now = Date.now();
    const movementSummary = [{
      productId: overrideProductId,
      productName: "Arroz",
      type: "PURCHASE",
      quantityDelta: "1",
    }];
    const projection = {
      productId: overrideProductId,
      locationName: "Tienda",
      quantityOnHand: "6",
      averageUnitCost: "2.5",
      currency: "PEN",
      version: 1,
      updatedAtMillis: now,
      seq: 2,
    };
    const purchase = {
      version: 3,
      purchaseId: duplicatePurchaseId,
      businessId: overrideBusinessId,
      status: "POSTED",
      documentType: target.documentType,
      documentSeries: target.documentSeries,
      documentNumber: target.documentNumber,
      issueDate: "2026-08-31",
      currency: "PEN",
      supplierRuc: target.supplierRuc,
      supplierLegalName: "Proveedor Demo",
      subtotalMinorUnits: 250,
      taxMinorUnits: 0,
      otherChargesMinorUnits: 0,
      totalMinorUnits: 250,
      adjustmentMinorUnits: null,
      adjustmentReason: null,
      preparedLogicalHash: "6".repeat(64),
      postedAt: now,
      idempotencyKey: operationId,
      lines: [{ purchaseLineId: randomUUID() }],
      movements: [{ productId: overrideProductId, locationId: overrideLocationId }],
      auditEventIds: [overrideAuditId, randomUUID()],
      duplicateOverride: {
        existingPurchaseId: targetPurchaseId,
        sourceDraftId,
        auditEventId: overrideAuditId,
        authorizedRole: "OWNER",
      },
      schemaVersion: 1,
      ownerUid: users.owner.uid,
      syncPayloadHash: "8".repeat(64),
      seq: 2,
      movementSummary,
      receiptId,
      syncedAt: now,
      syncedBy: users.owner.uid,
      inventorySeq: 2,
      lastOperationId: operationId,
    };

    const overrideBatch = (indexDocumentId) => {
      const batch = writeBatch(firestore);
      batch.set(doc(
        firestore,
        `businesses/${overrideBusinessId}/purchases/${duplicatePurchaseId}`,
      ), purchase);
      batch.set(doc(
        firestore,
        `businesses/${overrideBusinessId}/syncChanges/${duplicatePurchaseId}`,
      ), {
        schemaVersion: 1,
        businessId: overrideBusinessId,
        ownerUid: users.owner.uid,
        operationId,
        seq: 2,
        purchaseId: duplicatePurchaseId,
        status: "POSTED",
        documentType: purchase.documentType,
        documentSeries: purchase.documentSeries,
        documentNumber: purchase.documentNumber,
        issueDate: purchase.issueDate,
        currency: "PEN",
        supplierRuc: purchase.supplierRuc,
        supplierLegalName: purchase.supplierLegalName,
        totalMinorUnits: 250,
        movementSummary,
        receiptId,
        syncedAt: now,
      });
      batch.set(doc(
        firestore,
        `businesses/${overrideBusinessId}/syncKeys/${sha256(operationId)}`,
      ), {
        schemaVersion: 1,
        businessId: overrideBusinessId,
        ownerUid: users.owner.uid,
        operationId,
        idempotencyKey: operationId,
        purchaseId: duplicatePurchaseId,
        receiptId,
        seq: 2,
        inventorySeq: 2,
        requestHash: "8".repeat(64),
        createdAt: now,
      });
      batch.set(doc(
        firestore,
        `businesses/${overrideBusinessId}/documentIndex/${indexDocumentId}`,
      ), {
        schemaVersion: 1,
        businessId: overrideBusinessId,
        ownerUid: users.owner.uid,
        purchaseId: duplicatePurchaseId,
        receiptId,
        slot: "OVERRIDE",
        primaryIdentityHash: identityId,
        sourceDraftId,
        existingPurchaseId: targetPurchaseId,
        createdAt: now,
      });
      batch.set(doc(firestore, `businesses/${overrideBusinessId}/sync/metadata`), {
        schemaVersion: 1,
        businessId: overrideBusinessId,
        seq: 2,
        lastSyncedAt: now,
        lastPurchaseId: duplicatePurchaseId,
        lastReceiptId: receiptId,
        lastOperationId: operationId,
      });
      batch.set(doc(
        firestore,
        `businesses/${overrideBusinessId}/inventoryBalances/${overrideBalanceId}`,
      ), {
        schemaVersion: 1,
        businessId: overrideBusinessId,
        productId: overrideProductId,
        locationName: "Tienda",
        canonicalLocationName: "tienda",
        quantityOnHand: "6",
        averageUnitCost: "2.5",
        currency: "PEN",
        version: 1,
        lastSeq: 2,
        updatedAtMillis: now,
        lastSourceLocationId: overrideLocationId,
        lastOperationId: operationId,
        expectedVersion: 0,
        syncedAt: now,
      });
      batch.set(doc(
        firestore,
        `businesses/${overrideBusinessId}/inventorySyncChanges/${receiptId}`,
      ), {
        schemaVersion: 1,
        businessId: overrideBusinessId,
        operationId,
        kind: "PURCHASE",
        seq: 2,
        receiptId,
        sale: null,
        balances: [projection],
        syncedAt: now,
      });
      batch.set(doc(
        firestore,
        `businesses/${overrideBusinessId}/sync/inventoryMetadata`,
      ), {
        schemaVersion: 1,
        businessId: overrideBusinessId,
        seq: 2,
        lastKind: "PURCHASE",
        lastReceiptId: receiptId,
        lastOperationId: operationId,
        updatedAt: now,
      });
      return batch;
    };

    await denied(
      overrideBatch("f".repeat(64)).commit(),
      "slot override que no usa el hash canónico",
    );
    await overrideBatch(slotId).commit();
    assert.deepEqual((await getDoc(doc(
      firestore,
      `businesses/${overrideBusinessId}/documentIndex/${slotId}`,
    ))).data(), {
      schemaVersion: 1,
      businessId: overrideBusinessId,
      ownerUid: users.owner.uid,
      purchaseId: duplicatePurchaseId,
      receiptId,
      slot: "OVERRIDE",
      primaryIdentityHash: identityId,
      sourceDraftId,
      existingPurchaseId: targetPurchaseId,
      createdAt: now,
    });
  });

  test("venta descuenta el saldo con CAS y sus hechos son append-only", async () => {
    const firestore = await clientFor(users.owner);
    const { deleteDoc, doc, updateDoc, writeBatch } = await import("firebase/firestore");
    const operationId = `sync-sale:v1:${saleId}`;
    const receiptId = `sale_${"f".repeat(32)}`;
    const movementId = randomUUID();
    const now = Date.now();
    const line = {
      saleLineId,
      position: 0,
      productId,
      unitId,
      locationId,
      productName: "Arroz",
      unitCode: "UND",
      locationName: "Tienda",
      barcode: null,
      quantity: "1",
      unitPriceMinorUnits: 400,
      discountMinorUnits: 0,
      taxMinorUnits: 0,
      lineTotalMinorUnits: 400,
    };
    const projection = {
      productId,
      locationName: "Tienda",
      quantityOnHand: "4",
      averageUnitCost: "2.5",
      currency: "PEN",
      version: 1,
      updatedAtMillis: now,
      seq: 2,
    };
    const sale = {
      version: 1,
      saleId,
      businessId,
      status: "POSTED",
      currency: "PEN",
      subtotalMinorUnits: 400,
      discountMinorUnits: 0,
      taxMinorUnits: 0,
      totalMinorUnits: 400,
      contentHash: "1".repeat(64),
      checkoutIdempotencyKey: `checkout:v1:${saleId}:${"1".repeat(64)}`,
      createdAt: now,
      updatedAt: now,
      postedAt: now,
      lines: [line],
      schemaVersion: 1,
      operationId,
      idempotencyKey: operationId,
      syncPayloadHash: "2".repeat(64),
      actorUid: users.owner.uid,
      authorizedRole: "OWNER",
      receiptId,
      inventorySeq: 2,
      expectedInventoryVersions: { [balanceId]: 0 },
      resultingInventoryVersions: { [balanceId]: 1 },
      balances: [projection],
      syncedAt: now,
    };
    const movement = {
      movementId,
      saleId,
      saleLineId,
      productId,
      sourceLocationId: locationId,
      locationName: "Tienda",
      canonicalLocationName: "tienda",
      type: "SALE",
      quantityDelta: "-1",
      unitCost: "2.5",
      currency: "PEN",
      occurredAt: now,
      schemaVersion: 1,
      businessId,
      operationId,
      expectedInventoryVersion: 0,
      resultingInventoryVersion: 1,
      syncedAt: now,
    };
    const batch = writeBatch(firestore);
    batch.set(doc(firestore, `businesses/${businessId}/sales/${saleId}`), sale);
    batch.set(doc(firestore, `businesses/${businessId}/sales/${saleId}/lines/${saleLineId}`), {
      ...line,
      schemaVersion: 1,
      businessId,
      saleId,
      operationId,
      syncedAt: now,
    });
    batch.set(doc(firestore, `businesses/${businessId}/stockMovements/${movementId}`), movement);
    batch.set(doc(firestore, `businesses/${businessId}/inventoryBalances/${balanceId}`), {
      schemaVersion: 1,
      businessId,
      productId,
      locationName: "Tienda",
      canonicalLocationName: "tienda",
      quantityOnHand: "4",
      averageUnitCost: "2.5",
      currency: "PEN",
      version: 1,
      lastSeq: 2,
      updatedAtMillis: now,
      lastSourceLocationId: locationId,
      lastOperationId: operationId,
      expectedVersion: 0,
      syncedAt: now,
    });
    batch.set(doc(firestore, `businesses/${businessId}/inventorySyncChanges/${receiptId}`), {
      schemaVersion: 1,
      businessId,
      operationId,
      kind: "SALE",
      seq: 2,
      receiptId,
      sale: { ...sale, receiptId, seq: 2, movements: [movement] },
      balances: [projection],
      syncedAt: now,
    });
    batch.set(doc(firestore, `businesses/${businessId}/saleSyncKeys/${"3".repeat(64)}`), {
      schemaVersion: 1,
      businessId,
      operationId,
      saleId,
      receiptId,
      seq: 2,
      requestHash: "2".repeat(64),
      expectedInventoryVersions: { [balanceId]: 0 },
      resultingInventoryVersions: { [balanceId]: 1 },
      createdAt: now,
    });
    batch.set(doc(firestore, `businesses/${businessId}/sync/inventoryMetadata`), {
      schemaVersion: 1,
      businessId,
      seq: 2,
      lastKind: "SALE",
      lastReceiptId: receiptId,
      lastOperationId: operationId,
      updatedAt: now,
    });
    await batch.commit();
    await denied(
      updateDoc(doc(firestore, `businesses/${businessId}/sales/${saleId}`), { totalMinorUnits: 1 }),
      "edición de venta",
    );
    await denied(
      deleteDoc(doc(firestore, `businesses/${businessId}/stockMovements/${movementId}`)),
      "borrado de movimiento",
    );
  });

  test("abono reduce deuda por una versión y registra pago inmutable", async () => {
    const firestore = await clientFor(users.owner);
    const openingOperationId = `sync-sale:v1:${randomUUID()}`;
    const openingReceiptId = `sale_${"8".repeat(32)}`;
    const openedAt = Date.now() - 1_000;
    const debtRef = adminDb.doc(`businesses/${businessId}/debts/${debtId}`);
    await debtRef.set({
      debtId,
      businessId,
      saleId,
      debtorNameSnapshot: "María Pérez",
      currency: "PEN",
      originalMinorUnits: 1_000,
      balanceMinorUnits: 1_000,
      status: "OPEN",
      dueAt: null,
      version: 1,
      createdAt: openedAt,
      updatedAt: openedAt,
      paidAt: null,
      schemaVersion: 1,
      operationId: openingOperationId,
      openingOperationId,
      lastOperationId: openingOperationId,
      receiptId: openingReceiptId,
      inventorySeq: 2,
      actorUid: users.owner.uid,
      authorizedRole: "OWNER",
      syncedAt: new Date(openedAt),
    });

    const operationId = `debt-payment:v1:${debtId}:${paymentId}`;
    const receiptId = `debt_payment_${"9".repeat(32)}`;
    const now = Date.now();
    const debtSnapshot = {
      debtId,
      businessId,
      saleId,
      debtorNameSnapshot: "María Pérez",
      currency: "PEN",
      originalMinorUnits: 1_000,
      balanceMinorUnits: 600,
      status: "OPEN",
      dueAt: null,
      version: 2,
      createdAt: openedAt,
      updatedAt: now,
      paidAt: null,
    };
    const paymentSnapshot = {
      version: 1,
      paymentId,
      debtId,
      businessId,
      currency: "PEN",
      amountMinorUnits: 400,
      method: "YAPE",
      note: null,
      reference: "OP-1",
      expectedDebtVersion: 1,
      balanceAfterMinorUnits: 600,
      idempotencyKey: operationId,
      occurredAt: now,
      createdAt: now,
    };
    const batch = (await import("firebase/firestore")).writeBatch(firestore);
    const { deleteDoc, doc, updateDoc } = await import("firebase/firestore");
    batch.set(doc(firestore, `businesses/${businessId}/debts/${debtId}`), {
      ...debtSnapshot,
      schemaVersion: 1,
      operationId: openingOperationId,
      openingOperationId,
      lastOperationId: operationId,
      receiptId: openingReceiptId,
      expectedDebtVersion: 1,
      lastPaymentId: paymentId,
      lastPaymentReceiptId: receiptId,
      inventorySeq: 3,
      actorUid: users.owner.uid,
      authorizedRole: "OWNER",
      syncedAt: now,
    });
    batch.set(doc(firestore, `businesses/${businessId}/debts/${debtId}/payments/${paymentId}`), {
      ...paymentSnapshot,
      schemaVersion: 1,
      operationId,
      syncPayloadHash: "4".repeat(64),
      actorUid: users.owner.uid,
      authorizedRole: "OWNER",
      receiptId,
      inventorySeq: 3,
      resultingDebt: debtSnapshot,
      syncedAt: now,
    });
    batch.set(doc(firestore, `businesses/${businessId}/inventorySyncChanges/${receiptId}`), {
      schemaVersion: 1,
      businessId,
      operationId,
      kind: "DEBT_PAYMENT",
      seq: 3,
      receiptId,
      sale: null,
      balances: [],
      debt: debtSnapshot,
      payment: paymentSnapshot,
      syncedAt: now,
    });
    batch.set(doc(firestore, `businesses/${businessId}/debtPaymentSyncKeys/${"5".repeat(64)}`), {
      schemaVersion: 1,
      businessId,
      operationId,
      debtId,
      paymentId,
      receiptId,
      seq: 3,
      requestHash: "4".repeat(64),
      expectedDebtVersion: 1,
      resultingDebtVersion: 2,
      createdAt: now,
    });
    batch.set(doc(firestore, `businesses/${businessId}/sync/inventoryMetadata`), {
      schemaVersion: 1,
      businessId,
      seq: 3,
      lastKind: "DEBT_PAYMENT",
      lastReceiptId: receiptId,
      lastOperationId: operationId,
      updatedAt: now,
    });
    await batch.commit();
    await denied(
      updateDoc(doc(firestore, `businesses/${businessId}/debts/${debtId}`), {
        balanceMinorUnits: 0,
      }),
      "edición de saldo fuera de pago",
    );
    await denied(
      deleteDoc(doc(firestore, `businesses/${businessId}/debts/${debtId}/payments/${paymentId}`)),
      "borrado de pago",
    );
  });

  test("anulación compensa compra una sola vez sin borrar el hecho original", async () => {
    const firestore = await clientFor(users.owner);
    const { deleteDoc, doc, getDoc, updateDoc, writeBatch } = await import("firebase/firestore");
    const purchaseRef = doc(firestore, `businesses/${businessId}/purchases/${purchaseId}`);
    const changeRef = doc(firestore, `businesses/${businessId}/syncChanges/${purchaseId}`);
    const originalPurchase = (await getDoc(purchaseRef)).data();
    const originalChange = (await getDoc(changeRef)).data();
    const operationId = `sync-purchase-void:v1:${purchaseId}`;
    const receiptId = `rcpt_${"7".repeat(32)}`;
    const now = Date.now();
    const updatedPurchase = {
      ...originalPurchase,
      status: "VOIDED",
      seq: 2,
      voidReceiptId: receiptId,
      voidIdempotencyKey: operationId,
      voidPayloadHash: "6".repeat(64),
      voidReason: "Factura anulada por devolución completa",
      voidedAt: now,
      voidedBy: users.owner.uid,
      voidInventorySeq: 4,
      lastOperationId: operationId,
    };
    const projection = {
      productId,
      locationName: "Tienda",
      quantityOnHand: "-1",
      averageUnitCost: "2.5",
      currency: "PEN",
      version: 2,
      updatedAtMillis: now,
      seq: 4,
    };
    const batch = writeBatch(firestore);
    batch.set(purchaseRef, updatedPurchase);
    batch.set(changeRef, {
      ...originalChange,
      status: "VOIDED",
      seq: 2,
      operationId,
      syncedAt: now,
    });
    batch.set(doc(firestore, `businesses/${businessId}/purchases/${purchaseId}/voidRecord/record`), {
      schemaVersion: 1,
      businessId,
      ownerUid: users.owner.uid,
      cloudActorUid: users.owner.uid,
      purchaseId,
      operationId,
      idempotencyKey: operationId,
      receiptId,
      payload: JSON.stringify({ version: 1, purchaseId, reason: "devolución" }),
      impactHash: "a".repeat(64),
      voidReason: "Factura anulada por devolución completa",
      syncedAt: now,
    });
    batch.set(doc(firestore, `businesses/${businessId}/syncKeys/${"6".repeat(64)}`), {
      schemaVersion: 1,
      businessId,
      ownerUid: users.owner.uid,
      operationId,
      idempotencyKey: operationId,
      purchaseId,
      receiptId,
      seq: 2,
      inventorySeq: 4,
      requestHash: "6".repeat(64),
      createdAt: now,
    });
    batch.set(doc(firestore, `businesses/${businessId}/sync/metadata`), {
      schemaVersion: 1,
      businessId,
      seq: 2,
      lastSyncedAt: now,
      lastPurchaseId: purchaseId,
      lastReceiptId: receiptId,
      lastOperationId: operationId,
    });
    batch.set(doc(firestore, `businesses/${businessId}/inventoryBalances/${balanceId}`), {
      schemaVersion: 1,
      businessId,
      productId,
      locationName: "Tienda",
      canonicalLocationName: "tienda",
      quantityOnHand: "-1",
      averageUnitCost: "2.5",
      currency: "PEN",
      version: 2,
      lastSeq: 4,
      updatedAtMillis: now,
      lastSourceLocationId: locationId,
      lastOperationId: operationId,
      expectedVersion: 1,
      syncedAt: now,
    });
    batch.set(doc(firestore, `businesses/${businessId}/inventorySyncChanges/${receiptId}`), {
      schemaVersion: 1,
      businessId,
      operationId,
      kind: "PURCHASE_VOID",
      seq: 4,
      receiptId,
      sale: null,
      balances: [projection],
      syncedAt: now,
    });
    batch.set(doc(firestore, `businesses/${businessId}/sync/inventoryMetadata`), {
      schemaVersion: 1,
      businessId,
      seq: 4,
      lastKind: "PURCHASE_VOID",
      lastReceiptId: receiptId,
      lastOperationId: operationId,
      updatedAt: now,
    });
    await batch.commit();
    await denied(
      updateDoc(purchaseRef, { status: "POSTED" }),
      "reabre compra anulada",
    );
    await denied(
      deleteDoc(doc(firestore, `businesses/${businessId}/purchases/${purchaseId}/voidRecord/record`)),
      "borra compensación",
    );
  });

  test("bootstrap crea inventario una vez y su evidencia se preserva al avanzar", async () => {
    const firestore = await clientFor(users.owner);
    await createBusiness(firestore, users.owner, bootstrapBusinessId);
    await adminDb.doc(
      `businesses/${bootstrapBusinessId}/products/${bootstrapProductId}`,
    ).set({ entityId: bootstrapProductId, ownerUid: users.owner.uid });
    const { doc, setDoc, writeBatch } = await import("firebase/firestore");
    const operationId = `inventory-bootstrap:v1:${bootstrapBusinessId}`;
    const receiptId = `inventory_bootstrap_${"8".repeat(32)}`;
    const ledgerHash = "7".repeat(64);
    const now = Date.now();
    const projection = {
      productId: bootstrapProductId,
      locationName: "Almacén",
      quantityOnHand: "10",
      averageUnitCost: "3",
      currency: "PEN",
      version: 0,
      updatedAtMillis: now,
      seq: 1,
    };
    const batch = writeBatch(firestore);
    batch.set(doc(firestore, `businesses/${bootstrapBusinessId}/inventoryBalances/${bootstrapBalanceId}`), {
      schemaVersion: 1,
      businessId: bootstrapBusinessId,
      productId: bootstrapProductId,
      locationName: "Almacén",
      canonicalLocationName: "almacen",
      quantityOnHand: "10",
      averageUnitCost: "3",
      currency: "PEN",
      version: 0,
      lastSeq: 1,
      updatedAtMillis: now,
      lastSourceLocationId: locationId,
      lastOperationId: operationId,
      expectedVersion: null,
      syncedAt: now,
    });
    batch.set(doc(firestore, `businesses/${bootstrapBusinessId}/sync/inventoryBootstrap`), {
      schemaVersion: 1,
      businessId: bootstrapBusinessId,
      ownerUid: users.owner.uid,
      operationId,
      idempotencyKey: operationId,
      requestHash: "6".repeat(64),
      ledgerHash,
      locations: [{ sourceLocationId: locationId, locationName: "Almacén" }],
      receiptId,
      seq: 1,
      balances: [projection],
      authorizedRole: "OWNER",
      syncedAt: now,
    });
    batch.set(doc(firestore, `businesses/${bootstrapBusinessId}/inventorySyncChanges/${receiptId}`), {
      schemaVersion: 1,
      businessId: bootstrapBusinessId,
      operationId,
      kind: "PURCHASE",
      seq: 1,
      receiptId,
      sale: null,
      balances: [projection],
      bootstrap: true,
      syncedAt: now,
    });
    batch.set(doc(firestore, `businesses/${bootstrapBusinessId}/sync/inventoryMetadata`), {
      schemaVersion: 1,
      businessId: bootstrapBusinessId,
      seq: 1,
      bootstrapComplete: true,
      bootstrapLedgerHash: ledgerHash,
      lastKind: "PURCHASE",
      lastReceiptId: receiptId,
      lastOperationId: operationId,
      updatedAt: now,
    });
    await batch.commit();

    const nextReceipt = `sale_${"6".repeat(32)}`;
    const nextOperation = `sync-sale:v1:${randomUUID()}`;
    await adminDb.doc(
      `businesses/${bootstrapBusinessId}/inventorySyncChanges/${nextReceipt}`,
    ).set({
      schemaVersion: 1,
      businessId: bootstrapBusinessId,
      operationId: nextOperation,
      kind: "SALE",
      seq: 2,
      receiptId: nextReceipt,
      sale: {},
      balances: [],
      syncedAt: new Date(),
    });
    await setDoc(
      doc(firestore, `businesses/${bootstrapBusinessId}/sync/inventoryMetadata`),
      {
        schemaVersion: 1,
        businessId: bootstrapBusinessId,
        seq: 2,
        bootstrapComplete: true,
        bootstrapLedgerHash: ledgerHash,
        lastKind: "SALE",
        lastReceiptId: nextReceipt,
        lastOperationId: nextOperation,
        updatedAt: Date.now(),
      },
    );
    const thirdReceipt = `sale_${"5".repeat(32)}`;
    const thirdOperation = `sync-sale:v1:${randomUUID()}`;
    await adminDb.doc(
      `businesses/${bootstrapBusinessId}/inventorySyncChanges/${thirdReceipt}`,
    ).set({
      schemaVersion: 1,
      businessId: bootstrapBusinessId,
      operationId: thirdOperation,
      kind: "SALE",
      seq: 3,
      receiptId: thirdReceipt,
      sale: {},
      balances: [],
      syncedAt: new Date(),
    });
    await denied(
      setDoc(doc(firestore, `businesses/${bootstrapBusinessId}/sync/inventoryMetadata`), {
        schemaVersion: 1,
        businessId: bootstrapBusinessId,
        seq: 3,
        lastKind: "SALE",
        lastReceiptId: thirdReceipt,
        lastOperationId: thirdOperation,
        updatedAt: Date.now(),
      }),
      "elimina evidencia de bootstrap",
    );
  });

  test("bootstrap con más de veinte saldos no agota access calls de rules", async () => {
    const firestore = await clientFor(users.owner);
    await createBusiness(firestore, users.owner, largeBootstrapBusinessId);
    const { doc, writeBatch } = await import("firebase/firestore");
    const operationId = `inventory-bootstrap:v1:${largeBootstrapBusinessId}`;
    const receiptId = `inventory_bootstrap_${"4".repeat(32)}`;
    const ledgerHash = "3".repeat(64);
    const now = Date.now();
    const balances = Array.from({ length: 25 }, (_, index) => ({
      productId: randomUUID(),
      locationName: "Almacén",
      quantityOnHand: String(index + 1),
      averageUnitCost: "1",
      currency: "PEN",
      version: 0,
      updatedAtMillis: now,
      seq: 1,
    }));
    const batch = writeBatch(firestore);
    for (const [index, projection] of balances.entries()) {
      batch.set(doc(
        firestore,
        `businesses/${largeBootstrapBusinessId}/inventoryBalances/${String(index).padStart(64, "0")}`,
      ), {
        schemaVersion: 1,
        businessId: largeBootstrapBusinessId,
        productId: projection.productId,
        locationName: projection.locationName,
        canonicalLocationName: "almacen",
        quantityOnHand: projection.quantityOnHand,
        averageUnitCost: projection.averageUnitCost,
        currency: projection.currency,
        version: 0,
        lastSeq: 1,
        updatedAtMillis: now,
        lastSourceLocationId: locationId,
        lastOperationId: operationId,
        expectedVersion: null,
        syncedAt: now,
      });
    }
    batch.set(doc(
      firestore,
      `businesses/${largeBootstrapBusinessId}/sync/inventoryBootstrap`,
    ), {
      schemaVersion: 1,
      businessId: largeBootstrapBusinessId,
      ownerUid: users.owner.uid,
      operationId,
      idempotencyKey: operationId,
      requestHash: "2".repeat(64),
      ledgerHash,
      locations: [{ sourceLocationId: locationId, locationName: "Almacén" }],
      receiptId,
      seq: 1,
      balances,
      authorizedRole: "OWNER",
      syncedAt: now,
    });
    batch.set(doc(
      firestore,
      `businesses/${largeBootstrapBusinessId}/inventorySyncChanges/${receiptId}`,
    ), {
      schemaVersion: 1,
      businessId: largeBootstrapBusinessId,
      operationId,
      kind: "PURCHASE",
      seq: 1,
      receiptId,
      sale: null,
      balances,
      bootstrap: true,
      syncedAt: now,
    });
    batch.set(doc(
      firestore,
      `businesses/${largeBootstrapBusinessId}/sync/inventoryMetadata`,
    ), {
      schemaVersion: 1,
      businessId: largeBootstrapBusinessId,
      seq: 1,
      bootstrapComplete: true,
      bootstrapLedgerHash: ledgerHash,
      lastKind: "PURCHASE",
      lastReceiptId: receiptId,
      lastOperationId: operationId,
      updatedAt: now,
    });
    await batch.commit();
  });

  test("el ledger de membresía y el root no se editan ni eliminan", async () => {
    const firestore = await clientFor(users.owner);
    const { deleteDoc, doc, updateDoc } = await import("firebase/firestore");
    await denied(
      updateDoc(doc(firestore, `businesses/${businessId}`), { ownerUid: users.outsider.uid }),
      "cambio de propietario",
    );
    await denied(
      deleteDoc(doc(firestore, `businesses/${businessId}/members/${users.owner.uid}`)),
      "borra miembro",
    );
    await denied(
      deleteDoc(doc(firestore, `users/${users.owner.uid}/memberships/${businessId}`)),
      "borra índice de membresía",
    );
  });

  test("membresías paginan después de cien sin perder nombres repetidos", async () => {
    users.paged = await createUser("paged", true);
    const firestore = await clientFor(users.paged);
    const {
      collection, documentId, getDocs, limit, orderBy, query, startAfter,
    } = await import("firebase/firestore");
    const ids = [];
    try {
      for (let index = 0; index < 101; index += 1) {
        const id = randomUUID();
        ids.push(id);
        await createBusiness(firestore, users.paged, id);
      }
      const base = query(
        collection(firestore, `users/${users.paged.uid}/memberships`),
        orderBy("displayName"), orderBy(documentId()), limit(100),
      );
      const found = [];
      let cursor = null;
      let page;
      do {
        page = await getDocs(cursor === null ? base : query(base, startAfter(cursor)));
        found.push(...page.docs.map((document) => document.id));
        cursor = page.docs.at(-1) ?? null;
      } while (page.size === 100);
      assert.equal(found.length, 101);
      assert.deepEqual([...found].sort(), [...ids].sort());
    } finally {
      for (const id of ids) await adminDb.recursiveDelete(adminDb.doc(`businesses/${id}`));
    }
  });

}
