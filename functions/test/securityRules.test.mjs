// Matriz allow/deny de las reglas Firestore y Storage contra el Emulator Suite (proyecto
// demo-facturastock): aislamiento por tenant, libros de respaldo solo-Function, perfil de
// negocio con forma cerrada y subida/descarga de imágenes por rol y membresía.
// Se ejecuta con:
//   npx firebase emulators:exec --only auth,firestore,functions,storage "npm test"
import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { initializeApp as initializeAdminApp, deleteApp } from "firebase-admin/app";
import { FieldValue, getFirestore } from "firebase-admin/firestore";
import { getAuth } from "firebase-admin/auth";
import { getStorage } from "firebase-admin/storage";
import { randomUUID } from "node:crypto";

process.env.FIRESTORE_EMULATOR_HOST ??= "localhost:8080";
process.env.FIREBASE_AUTH_EMULATOR_HOST ??= "localhost:9099";
process.env.GCLOUD_PROJECT ??= "demo-facturastock";

const PROJECT = process.env.GCLOUD_PROJECT;
const AUTH_URL = `http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}` +
  "/identitytoolkit.googleapis.com/v1";
const PASSWORD = "clave-demo-123";
const firestoreEmulator = new URL(`http://${process.env.FIRESTORE_EMULATOR_HOST}`);
const FIRESTORE_HOST = firestoreEmulator.hostname;
const FIRESTORE_PORT = Number(firestoreEmulator.port || 8080);
const storageEmulator = new URL(
  `http://${process.env.FIREBASE_STORAGE_EMULATOR_HOST ?? "localhost:9199"}`,
);
const STORAGE_HOST = storageEmulator.hostname;
const STORAGE_PORT = Number(storageEmulator.port || 9199);

const adminApp = initializeAdminApp({
  projectId: PROJECT,
  storageBucket: `${PROJECT}.appspot.com`,
});
const db = getFirestore(adminApp);
const adminAuth = getAuth(adminApp);
const adminBucket = getStorage(adminApp).bucket();

const BUSINESS_A = randomUUID();
const BUSINESS_B = randomUUID();
const PURCHASE_ID = randomUUID();
const MOVEMENT_ID = randomUUID();
const PRODUCT_ID = randomUUID();
const SALE_ID = randomUUID();
const DEBT_ID = randomUUID();
const DEBT_PAYMENT_ID = randomUUID();
const BALANCE_ID = randomUUID().replaceAll("-", "");
const INVENTORY_CHANGE_ID = randomUUID().replaceAll("-", "");
const IMAGE_ID = randomUUID();
const imagePath = (businessId, purchaseId = PURCHASE_ID, imageId = IMAGE_ID) =>
  `businesses/${businessId}/invoices/${purchaseId}/${imageId}.jpg`;

const users = {}; // ownerA, adminA, operA, readerA, ownerB, outsider
const clientApps = [];

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

async function createVerifiedUser(prefix) {
  const email = `rules-${prefix}-${randomUUID()}@example.test`;
  const { localId } = await authRest("accounts:signUp", {
    email,
    password: PASSWORD,
    returnSecureToken: true,
  });
  await adminAuth.updateUser(localId, { emailVerified: true });
  return { uid: localId, email };
}

async function createUnverifiedUser(prefix) {
  const email = `rules-${prefix}-${randomUUID()}@example.test`;
  const { localId } = await authRest("accounts:signUp", {
    email,
    password: PASSWORD,
    returnSecureToken: true,
  });
  return { uid: localId, email };
}

async function seedMember(businessId, user, role) {
  await db.doc(`businesses/${businessId}/members/${user.uid}`).set({
    uid: user.uid,
    email: user.email,
    role,
  });
}

// Cliente SDK (como la app) autenticado con email/contraseña contra los emuladores.
async function clientFor(user) {
  const { initializeApp, deleteApp: _ } = await import("firebase/app");
  const { getAuth, connectAuthEmulator, signInWithEmailAndPassword } = await import("firebase/auth");
  const { getFirestore, connectFirestoreEmulator } = await import("firebase/firestore");
  const { getStorage, connectStorageEmulator } = await import("firebase/storage");

  const app = initializeApp(
    {
      projectId: PROJECT,
      apiKey: "demo-api-key",
      authDomain: "localhost",
      storageBucket: `${PROJECT}.appspot.com`,
    },
    `rules-${user ? user.uid : "anon"}-${Date.now()}`,
  );
  const auth = getAuth(app);
  connectAuthEmulator(auth, `http://${process.env.FIREBASE_AUTH_EMULATOR_HOST}`, {
    disableWarnings: true,
  });
  const firestore = getFirestore(app);
  connectFirestoreEmulator(firestore, FIRESTORE_HOST, FIRESTORE_PORT);
  const storage = getStorage(app);
  connectStorageEmulator(storage, STORAGE_HOST, STORAGE_PORT);
  if (user) {
    await signInWithEmailAndPassword(auth, user.email, PASSWORD);
  }
  clientApps.push(app);
  return { app, auth, firestore, storage };
}

const DENIED_CODES = new Set(["permission-denied", "storage/unauthorized"]);

async function denied(promise, context) {
  const outcome = await promise.then(
    () => "ALLOWED",
    (error) => (DENIED_CODES.has(error.code) ? "DENIED" : `OTRO:${error.code}`),
  );
  assert.equal(outcome, "DENIED", context);
}

async function clearBusiness(businessId) {
  const root = db.doc(`businesses/${businessId}`);
  for (const sub of [
    "members",
    "invitations",
    "purchases",
    "sales",
    "debts",
    "stockMovements",
    "inventoryBalances",
    "inventorySyncChanges",
    "saleSyncKeys",
    "debtPaymentSyncKeys",
    "syncChanges",
    "catalogSyncChanges",
    "catalogSyncOperations",
    "documentBackups",
    "documentBackupMetadata",
    "documentSyncOperations",
    "products",
    "suppliers",
    "sync",
  ]) {
    const snapshot = await root.collection(sub).get();
    for (const doc of snapshot.docs) {
      if (sub === "debts") await db.recursiveDelete(doc.ref);
      else await doc.ref.delete();
    }
  }
  await root.delete();
}

before(async () => {
  for (const [key, user] of Object.entries({
    ownerA: await createVerifiedUser("ownera"),
    adminA: await createVerifiedUser("admina"),
    operA: await createVerifiedUser("opera"),
    readerA: await createVerifiedUser("readera"),
    ownerB: await createVerifiedUser("ownerb"),
    outsider: await createVerifiedUser("outsider"),
    unverified: await createUnverifiedUser("unverified"),
  })) {
    users[key] = user;
  }
  await db.doc(`businesses/${BUSINESS_A}`).set({
    businessId: BUSINESS_A,
    displayName: "Bodega A",
    createdBy: users.ownerA.uid,
  });
  await db.doc(`businesses/${BUSINESS_B}`).set({
    businessId: BUSINESS_B,
    displayName: "Bodega B",
    createdBy: users.ownerB.uid,
  });
  await seedMember(BUSINESS_A, users.ownerA, "OWNER");
  await seedMember(BUSINESS_A, users.adminA, "ADMIN");
  await seedMember(BUSINESS_A, users.operA, "OPERATOR");
  await seedMember(BUSINESS_A, users.readerA, "READER");
  await seedMember(BUSINESS_B, users.ownerB, "OWNER");
  await db.doc(`businesses/${BUSINESS_A}/purchases/${PURCHASE_ID}`).set({
    status: "POSTED",
    totalMinorUnits: 2360,
  });
  await db.doc(`businesses/${BUSINESS_A}/stockMovements/${MOVEMENT_ID}`).set({
    type: "PURCHASE",
    quantityDelta: "2",
  });
  await db.doc(`businesses/${BUSINESS_A}/sales/${SALE_ID}`).set({
    saleId: SALE_ID,
    status: "POSTED",
  });
  await db.doc(`businesses/${BUSINESS_A}/debts/${DEBT_ID}`).set({
    debtId: DEBT_ID,
    debtorNameSnapshot: "María Pérez",
    status: "OPEN",
    balanceMinorUnits: 1_280,
  });
  await db.doc(
    `businesses/${BUSINESS_A}/debts/${DEBT_ID}/payments/${DEBT_PAYMENT_ID}`,
  ).set({
    paymentId: DEBT_PAYMENT_ID,
    debtId: DEBT_ID,
    amountMinorUnits: 800,
  });
  await db.doc(`businesses/${BUSINESS_A}/debtPaymentSyncKeys/key-fixture`).set({
    debtId: DEBT_ID,
    paymentId: DEBT_PAYMENT_ID,
  });
  await db.doc(`businesses/${BUSINESS_A}/inventoryBalances/${BALANCE_ID}`).set({
    productId: PRODUCT_ID,
    quantityOnHand: "8",
  });
  await db.doc(
    `businesses/${BUSINESS_A}/inventorySyncChanges/${INVENTORY_CHANGE_ID}`,
  ).set({ kind: "SALE", seq: 1 });
  await db.doc(`businesses/${BUSINESS_A}/products/${PRODUCT_ID}`).set({
    entityId: PRODUCT_ID,
    snapshot: { salePriceMinorUnits: 111, salePriceCurrencyCode: "PEN" },
  });
  await db.doc(`businesses/${BUSINESS_B}/products/${PRODUCT_ID}`).set({
    entityId: PRODUCT_ID,
    snapshot: { salePriceMinorUnits: 999, salePriceCurrencyCode: "PEN" },
  });
  await db.doc(`businesses/${BUSINESS_A}/sync/metadata`).set({ lastPurchaseId: PURCHASE_ID });
  await db.doc(`businesses/${BUSINESS_A}/documentBackups/${IMAGE_ID}`).set({
    purchaseId: PURCHASE_ID,
    imageId: IMAGE_ID,
    status: "COMPLETE",
    purgeRequested: false,
  });
  await adminBucket.file(imagePath(BUSINESS_A)).save(new Uint8Array(1024).fill(7), {
    metadata: {
      contentType: "image/jpeg",
      metadata: {
        purchaseId: PURCHASE_ID,
        imageId: IMAGE_ID,
      },
    },
  });
});

after(async () => {
  await adminBucket.deleteFiles({ prefix: `businesses/${BUSINESS_A}/` });
  await clearBusiness(BUSINESS_A);
  await clearBusiness(BUSINESS_B);
  const { deleteApp: deleteClientApp } = await import("firebase/app");
  for (const app of clientApps) await deleteClientApp(app);
  await deleteApp(adminApp);
});

// ---------- Firestore: aislamiento por tenant ----------

test("cross-tenant: el miembro de A no lee ni escribe en el negocio B", async () => {
  const { firestore } = await clientFor(users.ownerA);
  const { doc, getDoc, setDoc } = await import("firebase/firestore");

  await denied(getDoc(doc(firestore, `businesses/${BUSINESS_B}`)), "lee doc de B");
  await denied(
    getDoc(doc(firestore, `businesses/${BUSINESS_B}/purchases/${PURCHASE_ID}`)),
    "lee compra de B",
  );
  await denied(
    setDoc(doc(firestore, `businesses/${BUSINESS_B}`), {
      businessId: BUSINESS_B,
      displayName: "Intruso",
      createdBy: users.ownerA.uid,
    }),
    "escribe perfil de B",
  );
  await denied(
    setDoc(doc(firestore, `businesses/${BUSINESS_B}/purchases/${randomUUID()}`), { status: "POSTED" }),
    "escribe compra en B",
  );
});

test("precio de producto solo es legible en su tenant y nunca se escribe directo", async () => {
  const { firestore } = await clientFor(users.ownerA);
  const { doc, getDoc, updateDoc } = await import("firebase/firestore");

  const own = await getDoc(doc(firestore, `businesses/${BUSINESS_A}/products/${PRODUCT_ID}`));
  assert.equal(own.data().snapshot.salePriceMinorUnits, 111);
  await denied(
    getDoc(doc(firestore, `businesses/${BUSINESS_B}/products/${PRODUCT_ID}`)),
    "lee precio de producto de B",
  );
  await denied(
    updateDoc(doc(firestore, `businesses/${BUSINESS_A}/products/${PRODUCT_ID}`), {
      "snapshot.salePriceMinorUnits": 1,
    }),
    "OWNER altera precio sin Function",
  );
});

test("sin autenticación no hay lectura ni escritura", async () => {
  const { firestore } = await clientFor(null);
  const { doc, getDoc, setDoc } = await import("firebase/firestore");

  await denied(getDoc(doc(firestore, `businesses/${BUSINESS_A}`)), "lee sin auth");
  await denied(
    setDoc(doc(firestore, `businesses/${BUSINESS_A}/sync/metadata`), { x: 1 }),
    "escribe sin auth",
  );
});

test("correo no verificado no lee aunque exista una membresía", async () => {
  await seedMember(BUSINESS_A, users.unverified, "READER");
  const { firestore, storage } = await clientFor(users.unverified);
  const { doc, getDoc } = await import("firebase/firestore");
  const { ref, getBytes } = await import("firebase/storage");
  await denied(getDoc(doc(firestore, `businesses/${BUSINESS_A}`)), "root sin verificar");
  await denied(
    getDoc(doc(firestore, `businesses/${BUSINESS_A}/purchases/${PURCHASE_ID}`)),
    "compra sin verificar",
  );
  await denied(getBytes(ref(storage, imagePath(BUSINESS_A))), "imagen sin verificar");
});

// ---------- Firestore: libros de respaldo solo-Function ----------

test("el cliente no modifica compras publicadas ni stock directamente (ni siendo OWNER)", async () => {
  const { firestore } = await clientFor(users.ownerA);
  const { doc, setDoc, updateDoc, deleteDoc } = await import("firebase/firestore");

  await denied(
    setDoc(doc(firestore, `businesses/${BUSINESS_A}/purchases/${randomUUID()}`), { status: "POSTED" }),
    "crea compra",
  );
  await denied(
    updateDoc(doc(firestore, `businesses/${BUSINESS_A}/purchases/${PURCHASE_ID}`), { status: "VOIDED" }),
    "edita compra",
  );
  await denied(
    deleteDoc(doc(firestore, `businesses/${BUSINESS_A}/purchases/${PURCHASE_ID}`)),
    "borra compra",
  );
  await denied(
    setDoc(doc(firestore, `businesses/${BUSINESS_A}/stockMovements/${randomUUID()}`), {
      type: "ADJUSTMENT",
      quantityDelta: "99",
    }),
    "crea movimiento de stock",
  );
  await denied(
    updateDoc(doc(firestore, `businesses/${BUSINESS_A}/stockMovements/${MOVEMENT_ID}`), {
      quantityDelta: "99",
    }),
    "edita movimiento de stock",
  );
  await denied(
    deleteDoc(doc(firestore, `businesses/${BUSINESS_A}/stockMovements/${MOVEMENT_ID}`)),
    "borra movimiento de stock",
  );
  await denied(
    setDoc(doc(firestore, `businesses/${BUSINESS_A}/sync/metadata`), { lastPurchaseId: "x" }),
    "escribe metadata de sync",
  );
});

test("ventas, deudas, pagos, saldos y feed son legibles por miembros pero solo Functions muta", async () => {
  const { doc, getDoc, setDoc, updateDoc } = await import("firebase/firestore");
  const reader = (await clientFor(users.readerA)).firestore;
  const owner = (await clientFor(users.ownerA)).firestore;
  const outsider = (await clientFor(users.outsider)).firestore;
  const paths = [
    `businesses/${BUSINESS_A}/sales/${SALE_ID}`,
    `businesses/${BUSINESS_A}/debts/${DEBT_ID}`,
    `businesses/${BUSINESS_A}/debts/${DEBT_ID}/payments/${DEBT_PAYMENT_ID}`,
    `businesses/${BUSINESS_A}/inventoryBalances/${BALANCE_ID}`,
    `businesses/${BUSINESS_A}/inventorySyncChanges/${INVENTORY_CHANGE_ID}`,
  ];
  for (const path of paths) {
    assert.equal((await getDoc(doc(reader, path))).exists(), true, path);
    await denied(getDoc(doc(outsider, path)), `cross-tenant ${path}`);
    await denied(updateDoc(doc(owner, path), { tampered: true }), `write directo ${path}`);
  }
  const newPaymentPath =
    `businesses/${BUSINESS_A}/debts/${DEBT_ID}/payments/${randomUUID()}`;
  await denied(setDoc(doc(owner, newPaymentPath), {
    amountMinorUnits: 1,
  }), "crea pago directo");
  const keyPath = `businesses/${BUSINESS_A}/debtPaymentSyncKeys/key-fixture`;
  await denied(getDoc(doc(reader, keyPath)), "miembro lee índice interno de idempotencia");
  await denied(updateDoc(doc(owner, keyPath), { tampered: true }), "OWNER edita índice interno");
});

test("membresías: ni el OWNER las escribe desde el cliente", async () => {
  const { firestore } = await clientFor(users.ownerA);
  const { doc, setDoc, deleteDoc } = await import("firebase/firestore");

  await denied(
    setDoc(doc(firestore, `businesses/${BUSINESS_A}/members/${users.readerA.uid}`), {
      uid: users.readerA.uid,
      role: "OWNER",
    }),
    "autoasciende a READER por cliente",
  );
  await denied(
    deleteDoc(doc(firestore, `businesses/${BUSINESS_A}/members/${users.readerA.uid}`)),
    "elimina miembro por cliente",
  );
});

// ---------- Firestore: perfil de negocio solo-Function ----------

test("perfil de negocio: ningún rol lo modifica directamente", async () => {
  const { doc, getDoc, updateDoc, deleteDoc, deleteField } = await import("firebase/firestore");
  const businessDoc = (fs) => doc(fs, `businesses/${BUSINESS_A}`);

  const ownerClient = (await clientFor(users.ownerA)).firestore;
  const adminClient = (await clientFor(users.adminA)).firestore;

  await denied(
    updateDoc(businessDoc(ownerClient), { displayName: "Bodega A Centro" }),
    "OWNER edita root sin callable",
  );
  await denied(
    updateDoc(businessDoc(adminClient), { displayName: "Bodega A Centro" }),
    "ADMIN edita root sin callable",
  );

  // Campo fuera del catálogo.
  await denied(
    updateDoc(businessDoc(ownerClient), { notes: "campo no permitido" }),
    "campo ajeno al catálogo",
  );
  // Tamaño: displayName > 120.
  await denied(
    updateDoc(businessDoc(ownerClient), { displayName: "x".repeat(121) }),
    "displayName demasiado largo",
  );
  // Identidad e inmutabilidad del alta.
  await denied(
    updateDoc(businessDoc(ownerClient), { businessId: BUSINESS_B }),
    "cambia businessId",
  );
  await denied(
    updateDoc(businessDoc(ownerClient), { createdBy: users.adminA.uid }),
    "cambia createdBy",
  );
  // Nunca se borra.
  await denied(deleteDoc(businessDoc(ownerClient)), "borra el negocio");

  // No miembro: ni con forma válida.
  const outsiderClient = (await clientFor(users.outsider)).firestore;
  await denied(
    updateDoc(businessDoc(outsiderClient), { displayName: "Intruso" }),
    "no miembro actualiza perfil",
  );

  // El Admin SDK fija este lock al iniciar un borrado. Aunque el documento histórico no lo
  // tuviera al crearse, ningún cliente miembro puede quitarlo ni cambiar su valor para volver
  // a escribir durante la eliminación.
  await db.doc(`businesses/${BUSINESS_A}`).update({ accountDeletionLocked: true });
  await denied(
    updateDoc(businessDoc(ownerClient), { accountDeletionLocked: deleteField() }),
    "elimina el lock de borrado",
  );
  await denied(
    updateDoc(businessDoc(ownerClient), { accountDeletionLocked: false }),
    "desactiva el lock de borrado",
  );
  await denied(
    updateDoc(businessDoc(ownerClient), { displayName: "No cambia durante borrado" }),
    "edita el negocio mientras el lock de borrado está activo",
  );
  await denied(
    getDoc(businessDoc(ownerClient)),
    "lee el negocio mientras el lock de borrado está activo",
  );
  await denied(
    getDoc(doc(ownerClient, `businesses/${BUSINESS_A}/purchases/${PURCHASE_ID}`)),
    "lee una compra mientras el lock de borrado está activo",
  );

  const { ref, uploadBytes } = await import("firebase/storage");
  const operatorStorage = (await clientFor(users.operA)).storage;
  await denied(
    uploadBytes(
      ref(operatorStorage, imagePath(BUSINESS_A, PURCHASE_ID, randomUUID())),
      new Uint8Array(64),
      { contentType: "image/jpeg" },
    ),
    "sube una imagen mientras el lock de borrado está activo",
  );

  // Restaura el fixture con Admin SDK para que la matriz Storage posterior pruebe su ruta
  // normal; el cliente acaba de demostrar que no puede hacerlo.
  await db.doc(`businesses/${BUSINESS_A}`).update({
    accountDeletionLocked: FieldValue.delete(),
  });
});

test("invitaciones: el destinatario no lee directo con un JWT viejo", async () => {
  const invitationId = randomUUID();
  await db.doc(`businesses/${BUSINESS_A}/invitations/${invitationId}`).set({
    businessId: BUSINESS_A,
    email: users.outsider.email,
    role: "READER",
    status: "PENDING",
  });

  const { doc, getDoc } = await import("firebase/firestore");
  const path = `businesses/${BUSINESS_A}/invitations/${invitationId}`;
  const ownerClient = (await clientFor(users.ownerA)).firestore;
  const invitedClient = (await clientFor(users.outsider)).firestore;

  assert.equal((await getDoc(doc(ownerClient, path))).exists(), true);
  await denied(
    getDoc(doc(invitedClient, path)),
    "destinatario intenta leer la invitación sin callable protegido por tombstone",
  );
});

test("las guardas pseudónimas de borrado no son legibles ni mutables por clientes", async () => {
  const { doc, getDoc, setDoc } = await import("firebase/firestore");
  const client = (await clientFor(users.ownerA)).firestore;
  for (const collection of [
    "accountDeletionTombstones",
    "accountDeletionEmailLocks",
    "invitationRateLimits",
    "documentUploadRateLimits",
    "membershipQuotaCounters",
    "syncMutationRateLimits",
  ]) {
    const id = randomUUID().replaceAll("-", "");
    await db.doc(`${collection}/${id}`).set({ schemaVersion: 1 });
    await denied(getDoc(doc(client, collection, id)), `lee ${collection}`);
    await denied(setDoc(doc(client, collection, id), { schemaVersion: 2 }), `edita ${collection}`);
  }
});

// ---------- Storage: imágenes por rol y tenant ----------

test("un objeto creado por backend se descarga por cualquier miembro, incluido READER", async () => {
  const { ref, getBytes } = await import("firebase/storage");
  const readerStorage = (await clientFor(users.readerA)).storage;
  const downloaded = await getBytes(ref(readerStorage, imagePath(BUSINESS_A)));
  assert.equal(downloaded.byteLength, 1024);
});

test("Storage niega RESERVED, purgeRequested, negocio bloqueado y marcador ausente", async () => {
  const { ref, getBytes } = await import("firebase/storage");
  const readerStorage = (await clientFor(users.readerA)).storage;
  const marker = db.doc(`businesses/${BUSINESS_A}/documentBackups/${IMAGE_ID}`);

  await marker.update({ status: "RESERVED" });
  await denied(getBytes(ref(readerStorage, imagePath(BUSINESS_A))), "lee reserva sin finalize");
  await marker.update({ status: "COMPLETE", purgeRequested: true });
  await denied(getBytes(ref(readerStorage, imagePath(BUSINESS_A))), "lee tombstone de purga");
  await marker.update({ purgeRequested: false });
  await db.doc(`businesses/${BUSINESS_A}`).update({ accountDeletionLocked: true });
  await denied(getBytes(ref(readerStorage, imagePath(BUSINESS_A))), "lee durante account deletion");
  await db.doc(`businesses/${BUSINESS_A}`).update({
    accountDeletionLocked: FieldValue.delete(),
  });
  await marker.delete();
  await denied(getBytes(ref(readerStorage, imagePath(BUSINESS_A))), "lee sin marcador documental");
  await marker.set({
    purchaseId: PURCHASE_ID,
    imageId: IMAGE_ID,
    status: "COMPLETE",
    purgeRequested: false,
  });
});

test("archivo ajeno no se descarga: otro tenant ni anónimo leen la imagen", async () => {
  const { ref, getBytes } = await import("firebase/storage");

  const otherTenant = (await clientFor(users.ownerB)).storage;
  await denied(
    getBytes(ref(otherTenant, imagePath(BUSINESS_A))),
    "OWNER de B descarga imagen de A",
  );

  const anonymous = (await clientFor(null)).storage;
  await denied(getBytes(ref(anonymous, imagePath(BUSINESS_A))), "anónimo descarga");
});

test("subida directa: todos los roles, tipos, nombres y tenants se rechazan", async () => {
  const { ref, uploadBytes } = await import("firebase/storage");
  const small = new Uint8Array(64).fill(1);
  const oversize = new Uint8Array(2 * 1024 * 1024 + 1);

  const readerStorage = (await clientFor(users.readerA)).storage;
  await denied(
    uploadBytes(ref(readerStorage, imagePath(BUSINESS_A, PURCHASE_ID, randomUUID())), small, {
      contentType: "image/jpeg",
    }),
    "READER sube",
  );

  const operStorage = (await clientFor(users.operA)).storage;
  await denied(
    uploadBytes(ref(operStorage, imagePath(BUSINESS_A, PURCHASE_ID, randomUUID())), small, {
      contentType: "image/jpeg",
    }),
    "OPERATOR intenta subida JPEG válida sin callable",
  );
  await denied(
    uploadBytes(ref(operStorage, imagePath(BUSINESS_A, PURCHASE_ID, randomUUID())), small, {
      contentType: "image/png",
    }),
    "tipo no JPEG",
  );
  await denied(
    uploadBytes(ref(operStorage, imagePath(BUSINESS_A, PURCHASE_ID, randomUUID())), oversize, {
      contentType: "image/jpeg",
    }),
    "supera 2 MiB",
  );
  await denied(
    uploadBytes(
      ref(operStorage, `businesses/${BUSINESS_A}/invoices/${PURCHASE_ID}/foto.jpg`),
      small,
      { contentType: "image/jpeg" },
    ),
    "nombre no UUID",
  );
  await denied(
    uploadBytes(ref(operStorage, imagePath(BUSINESS_B, PURCHASE_ID, randomUUID())), small, {
      contentType: "image/jpeg",
    }),
    "sube al negocio B sin ser miembro",
  );
  await denied(
    uploadBytes(
      ref(operStorage, `businesses/${BUSINESS_A}/otra-carpeta/${randomUUID()}.jpg`),
      small,
      { contentType: "image/jpeg" },
    ),
    "ruta fuera de invoices/",
  );
});

test("el objeto es inmutable: ni sobrescritura ni borrado desde el cliente", async () => {
  const { ref, uploadBytes, deleteObject } = await import("firebase/storage");
  const small = new Uint8Array(64).fill(2);

  const ownerStorage = (await clientFor(users.ownerA)).storage;
  await denied(
    uploadBytes(ref(ownerStorage, imagePath(BUSINESS_A)), small, {
      contentType: "image/jpeg",
    }),
    "sobrescribe la imagen existente",
  );
  await denied(deleteObject(ref(ownerStorage, imagePath(BUSINESS_A))), "borra la imagen");
});
