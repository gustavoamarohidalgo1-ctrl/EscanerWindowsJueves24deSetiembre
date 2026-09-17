import { test, after } from "node:test";
import assert from "node:assert/strict";
import { initializeApp as initializeAdminApp, deleteApp } from "firebase-admin/app";
import { getFirestore, Timestamp } from "firebase-admin/firestore";
import { getAuth } from "firebase-admin/auth";
import { getStorage } from "firebase-admin/storage";
import { createHash, randomUUID } from "node:crypto";
import sharp from "sharp";

process.env.FIRESTORE_EMULATOR_HOST ??= "localhost:8080";
process.env.FIREBASE_AUTH_EMULATOR_HOST ??= "localhost:9099";
process.env.FIREBASE_STORAGE_EMULATOR_HOST ??= "localhost:9199";
process.env.GCLOUD_PROJECT ??= "demo-facturastock";
const {
  DOCUMENT_UPLOAD_RATE_POLICIES,
  decodeJpeg,
  documentUploadRateLimitDocumentId,
  finalizeReservedDocumentUpload,
  purgePurchaseDocumentHandler,
  reconcileFinalizedPurchaseDocument,
  reconcileStaleReservedDocumentsHandler,
  uploadPurchaseDocumentHandler,
} =
  await import("../documentBackup.js");
const { DOCUMENT_FINALIZE_OPTIONS } = await import("../common.js");

const PROJECT = process.env.GCLOUD_PROJECT;
const FUNCTIONS_URL = `http://localhost:5001/${PROJECT}/us-central1`;
const AUTH_URL = "http://localhost:9099/identitytoolkit.googleapis.com/v1";
const PASSWORD = "clave-demo-123";
const BUCKET = `${PROJECT}.appspot.com`;
const app = initializeAdminApp({ projectId: PROJECT, storageBucket: BUCKET }, `documents-${randomUUID()}`);
const db = getFirestore(app);
const auth = getAuth(app);
const bucket = getStorage(app).bucket();
const businesses = new Set();

const FAKE_JPEG = Buffer.from([
  0xff, 0xd8,
  0xff, 0xc0, 0x00, 0x0b, 0x08, 0x00, 0x10, 0x00, 0x10, 0x01, 0x01, 0x11, 0x00,
  0xff, 0xd9,
]);
const JPEG = await sharp({
  create: { width: 16, height: 16, channels: 3, background: { r: 7, g: 11, b: 13 } },
}).jpeg({ quality: 88 }).toBuffer();
const sha = (bytes) => createHash("sha256").update(bytes).digest("hex");

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
  const email = `documents-${randomUUID()}@example.test`;
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

async function seed(user, role = "OWNER", status = "POSTED") {
  const businessId = randomUUID();
  const purchaseId = randomUUID();
  businesses.add(businessId);
  await db.doc(`businesses/${businessId}`).set({ businessId });
  await db.doc(`businesses/${businessId}/members/${user.uid}`).set({ uid: user.uid, role });
  await db.doc(`businesses/${businessId}/purchases/${purchaseId}`).set({ status });
  return { businessId, purchaseId };
}

function uploadRequest(businessId, purchaseId, imageId = randomUUID(), bytes = JPEG) {
  const digest = sha(bytes);
  return {
    businessId,
    idempotencyKey: `document-upload:v1:${purchaseId}:${imageId}:${digest}`,
    operationType: "SYNC_DOCUMENT_UPLOAD",
    payloadVersion: 1,
    document: {
      version: 1,
      purchaseId,
      imageId,
      sha256: digest,
      contentBase64: bytes.toString("base64"),
    },
  };
}

function purgeRequest(businessId, purchaseId, imageId) {
  return {
    businessId,
    idempotencyKey: `document-purge:v1:${purchaseId}:${imageId}`,
    operationType: "SYNC_DOCUMENT_PURGE",
    payloadVersion: 1,
    document: { version: 1, purchaseId, imageId },
  };
}

const pathFor = (request) =>
  `businesses/${request.businessId}/invoices/${request.document.purchaseId}/${request.document.imageId}.jpg`;

function recordingBucket() {
  const deleted = [];
  const prefixesDeleted = [];
  return {
    name: BUCKET,
    deleted,
    prefixesDeleted,
    file: (name) => ({
      delete: async () => {
        deleted.push(name);
      },
    }),
    deleteFiles: async ({ prefix }) => {
      prefixesDeleted.push(prefix);
    },
  };
}

async function seedReservedDocument(user, createdAt) {
  const { businessId, purchaseId } = await seed(user);
  const imageId = randomUUID();
  await db.doc(`businesses/${businessId}/documentBackups/${imageId}`).set({
    purchaseId,
    imageId,
    sha256: sha(JPEG),
    size: JPEG.length,
    status: "RESERVED",
    purgeRequested: false,
    createdAt: Timestamp.fromMillis(createdAt),
  });
  await db.doc(`businesses/${businessId}/documentBackupMetadata/${purchaseId}`).set({
    pageCount: 1,
  });
  await db.doc(`businesses/${businessId}/sync/documentMetadata`).set({
    totalBytes: JPEG.length,
  });
  return {
    businessId,
    purchaseId,
    imageId,
    name: `businesses/${businessId}/invoices/${purchaseId}/${imageId}.jpg`,
  };
}

after(async () => {
  for (const businessId of businesses) {
    await bucket.deleteFiles({ prefix: `businesses/${businessId}/` });
    await db.recursiveDelete(db.doc(`businesses/${businessId}`));
  }
  await deleteApp(app);
});

test("callable valida JPEG, sube por Admin e idempotencia sobrevive downgrade", async () => {
  const user = await verifiedUser();
  const { businessId, purchaseId } = await seed(user);
  const request = uploadRequest(businessId, purchaseId);
  request.expectedUid = user.uid;
  const first = await call("uploadPurchaseDocument", request, user.token);
  assert.equal(first.body.error, undefined, JSON.stringify(first.body));
  assert.equal((await bucket.file(pathFor(request)).exists())[0], true);
  const [metadata] = await bucket.file(pathFor(request)).getMetadata();
  assert.equal(metadata.metadata.sha256, request.document.sha256);
  assert.equal(JSON.stringify(metadata).includes(user.uid), false);
  const actorRateRef = db.doc(
    `documentUploadRateLimits/${documentUploadRateLimitDocumentId("ACTOR", user.uid)}`,
  );
  const businessRateRef = db.doc(
    `documentUploadRateLimits/${documentUploadRateLimitDocumentId("BUSINESS", businessId)}`,
  );
  assert.equal((await actorRateRef.get()).data().count, 1);
  assert.equal((await businessRateRef.get()).data().count, 1);

  await db.doc(`businesses/${businessId}/members/${user.uid}`).update({ role: "READER" });
  const replay = await call("uploadPurchaseDocument", request, user.token);
  assert.equal(replay.body.error, undefined, JSON.stringify(replay.body));
  assert.equal(replay.body.result.receiptId, first.body.result.receiptId);
  assert.equal((await actorRateRef.get()).data().count, 1);
  assert.equal((await businessRateRef.get()).data().count, 1);
});

test("READER, compra no terminal y bytes falsos quedan denegados sin objeto", async () => {
  const reader = await verifiedUser();
  const readerScope = await seed(reader, "READER");
  const readerRequest = uploadRequest(readerScope.businessId, readerScope.purchaseId);
  const deniedRole = await call("uploadPurchaseDocument", readerRequest, reader.token);
  assert.equal(deniedRole.body.error?.message, "ROLE_FORBIDDEN");
  assert.equal((await bucket.file(pathFor(readerRequest)).exists())[0], false);

  const owner = await verifiedUser();
  const draftScope = await seed(owner, "OWNER", "DRAFT");
  const draftRequest = uploadRequest(draftScope.businessId, draftScope.purchaseId);
  assert.equal(
    (await call("uploadPurchaseDocument", draftRequest, owner.token)).body.error?.message,
    "PURCHASE_NOT_TERMINAL",
  );
  const terminalScope = await seed(owner);
  const fake = uploadRequest(
    terminalScope.businessId,
    terminalScope.purchaseId,
    randomUUID(),
    Buffer.from("not jpeg"),
  );
  assert.equal(
    (await call("uploadPurchaseDocument", fake, owner.token)).body.error?.message,
    "IMAGE_JPEG_SIGNATURE",
  );
  const framedButNotDecodable = uploadRequest(
    terminalScope.businessId,
    terminalScope.purchaseId,
    randomUUID(),
    FAKE_JPEG,
  );
  assert.equal(
    (await call("uploadPurchaseDocument", framedButNotDecodable, owner.token)).body.error?.message,
    "IMAGE_JPEG_DECODE",
  );
});

test("preflight niega outsider y READER antes de invocar el decoder", async () => {
  const owner = await verifiedUser();
  const outsider = await verifiedUser();
  const ownerScope = await seed(owner);
  const readerScope = await seed(outsider, "READER");
  let decodeCalls = 0;
  const decoder = async () => {
    decodeCalls += 1;
    return { width: 16, height: 16 };
  };
  const directRequest = (uid, data) => ({
    auth: { uid, token: { email_verified: true } },
    data,
  });

  await assert.rejects(
    uploadPurchaseDocumentHandler(
      directRequest(outsider.uid, uploadRequest(ownerScope.businessId, ownerScope.purchaseId)),
      decoder,
    ),
    (error) => error.message === "NOT_A_MEMBER",
  );
  await assert.rejects(
    uploadPurchaseDocumentHandler(
      directRequest(outsider.uid, uploadRequest(readerScope.businessId, readerScope.purchaseId)),
      decoder,
    ),
    (error) => error.message === "ROLE_FORBIDDEN",
  );
  assert.equal(decodeCalls, 0);
});

test("preflight corta imageId, tombstone y cuotas llenas antes de Sharp", async () => {
  const user = await verifiedUser();
  let decodeCalls = 0;
  let bucketCalls = 0;
  const decoder = async () => {
    decodeCalls += 1;
    return { width: 16, height: 16 };
  };
  const bucketProvider = () => {
    bucketCalls += 1;
    return recordingBucket();
  };
  const direct = (data) => ({
    auth: { uid: user.uid, token: { email_verified: true } },
    data: { ...data, expectedUid: user.uid },
  });

  const existingScope = await seed(user);
  const existing = uploadRequest(existingScope.businessId, existingScope.purchaseId);
  await db.doc(
    `businesses/${existingScope.businessId}/documentBackups/${existing.document.imageId}`,
  ).set({
    purchaseId: existingScope.purchaseId,
    imageId: existing.document.imageId,
    sha256: existing.document.sha256,
    size: JPEG.length,
    width: 16,
    height: 16,
    status: "COMPLETE",
  });
  await assert.rejects(
    uploadPurchaseDocumentHandler(direct(existing), decoder, bucketProvider),
    (error) => error.message === "DOCUMENT_IMAGE_ID_EXISTS",
  );

  const purgedScope = await seed(user);
  const purged = uploadRequest(purgedScope.businessId, purgedScope.purchaseId);
  await db.doc(
    `businesses/${purgedScope.businessId}/documentBackups/${purged.document.imageId}`,
  ).set({
    purchaseId: purgedScope.purchaseId,
    imageId: purged.document.imageId,
    size: 0,
    status: "PURGED",
    purgeRequested: true,
  });
  assert.deepEqual(
    await uploadPurchaseDocumentHandler(direct(purged), decoder, bucketProvider),
    {
      ok: true,
      idempotencyKey: purged.idempotencyKey,
      receiptId: `doc_${sha(`facturastock:doc:v1:${purged.idempotencyKey}`).slice(0, 32)}`,
      discardedByPurge: true,
    },
  );

  const pageScope = await seed(user);
  await db.doc(
    `businesses/${pageScope.businessId}/documentBackupMetadata/${pageScope.purchaseId}`,
  ).set({ pageCount: 5 });
  await assert.rejects(
    uploadPurchaseDocumentHandler(
      direct(uploadRequest(pageScope.businessId, pageScope.purchaseId)),
      decoder,
      bucketProvider,
    ),
    (error) => error.message === "DOCUMENT_PAGE_QUOTA",
  );

  const bytesScope = await seed(user);
  await db.doc(`businesses/${bytesScope.businessId}/sync/documentMetadata`).set({
    totalBytes: 50 * 1024 * 1024,
  });
  await assert.rejects(
    uploadPurchaseDocumentHandler(
      direct(uploadRequest(bytesScope.businessId, bytesScope.purchaseId)),
      decoder,
      bucketProvider,
    ),
    (error) => error.message === "DOCUMENT_BUSINESS_QUOTA",
  );
  assert.equal(decodeCalls, 0);
  assert.equal(bucketCalls, 0);
});

test("un JPEG inválido se decodifica una sola vez por clave idempotente", async () => {
  const user = await verifiedUser();
  const { businessId, purchaseId } = await seed(user);
  const request = uploadRequest(businessId, purchaseId, randomUUID(), FAKE_JPEG);
  request.expectedUid = user.uid;
  let decodeCalls = 0;
  let bucketCalls = 0;
  const invoke = () => uploadPurchaseDocumentHandler(
    { auth: { uid: user.uid, token: { email_verified: true } }, data: request },
    async (bytes) => {
      decodeCalls += 1;
      return decodeJpeg(bytes);
    },
    () => {
      bucketCalls += 1;
      return recordingBucket();
    },
  );

  await assert.rejects(invoke(), (error) => error.message === "IMAGE_JPEG_DECODE");
  await assert.rejects(invoke(), (error) => error.message === "IMAGE_JPEG_DECODE");
  assert.equal(decodeCalls, 1);
  assert.equal(bucketCalls, 0);
  const operation = await db.doc(
    `businesses/${businessId}/documentSyncOperations/${sha(request.idempotencyKey)}`,
  ).get();
  assert.equal(operation.data().status, "VALIDATION_FAILED");
  assert.equal(operation.data().validationError, "IMAGE_JPEG_DECODE");
  for (const [scope, value] of [["ACTOR", user.uid], ["BUSINESS", businessId]]) {
    const rate = await db.doc(
      `documentUploadRateLimits/${documentUploadRateLimitDocumentId(scope, value)}`,
    ).get();
    assert.equal(rate.data().count, 1, scope);
  }
});

test("retry de una reserva validada reutiliza dimensiones y no vuelve a abrir Sharp", async () => {
  const user = await verifiedUser();
  const { businessId, purchaseId } = await seed(user);
  const request = uploadRequest(businessId, purchaseId);
  request.expectedUid = user.uid;
  const requestHash = sha(JSON.stringify({
    businessId,
    idempotencyKey: request.idempotencyKey,
    operationType: request.operationType,
    payloadVersion: 1,
    document: {
      version: 1,
      purchaseId,
      imageId: request.document.imageId,
      sha256: request.document.sha256,
      size: JPEG.length,
      dimensions: { width: 16, height: 16 },
    },
  }));
  const operationRef = db.doc(
    `businesses/${businessId}/documentSyncOperations/${sha(request.idempotencyKey)}`,
  );
  await operationRef.set({
    idempotencyKey: request.idempotencyKey,
    requestHash,
    operationType: "SYNC_DOCUMENT_UPLOAD",
    purchaseId,
    imageId: request.document.imageId,
    receiptId: "doc_existing_reservation",
    status: "RESERVED",
  });
  await db.doc(
    `businesses/${businessId}/documentBackups/${request.document.imageId}`,
  ).set({
    purchaseId,
    imageId: request.document.imageId,
    sha256: request.document.sha256,
    size: JPEG.length,
    width: 16,
    height: 16,
    status: "RESERVED",
    purgeRequested: false,
  });
  await db.doc(`businesses/${businessId}/documentBackupMetadata/${purchaseId}`).set({
    pageCount: 1,
  });
  await db.doc(`businesses/${businessId}/sync/documentMetadata`).set({
    totalBytes: JPEG.length,
  });
  let decodeCalls = 0;
  let saveCalls = 0;
  const file = {
    save: async () => {
      saveCalls += 1;
    },
    delete: async () => {},
  };

  const result = await uploadPurchaseDocumentHandler(
    { auth: { uid: user.uid, token: { email_verified: true } }, data: request },
    async () => {
      decodeCalls += 1;
      return { width: 16, height: 16 };
    },
    () => ({ file: () => file }),
  );

  assert.equal(result.ok, true);
  assert.equal(decodeCalls, 0);
  assert.equal(saveCalls, 1);
  assert.equal((await operationRef.get()).data().status, "COMPLETE");
});

test("lease idempotente evita dos decodes concurrentes del mismo upload", async () => {
  const user = await verifiedUser();
  const { businessId, purchaseId } = await seed(user);
  const request = uploadRequest(businessId, purchaseId);
  request.expectedUid = user.uid;
  const directRequest = {
    auth: { uid: user.uid, token: { email_verified: true } },
    data: request,
  };
  let decodeCalls = 0;
  let signalDecoderStarted;
  let releaseDecoder;
  const decoderStarted = new Promise((resolve) => {
    signalDecoderStarted = resolve;
  });
  const decoderMayFinish = new Promise((resolve) => {
    releaseDecoder = resolve;
  });
  const decoder = async () => {
    decodeCalls += 1;
    signalDecoderStarted();
    await decoderMayFinish;
    return { width: 16, height: 16 };
  };
  const file = {
    save: async () => {},
    delete: async () => {},
  };
  const bucketProvider = () => ({ file: () => file });

  const first = uploadPurchaseDocumentHandler(directRequest, decoder, bucketProvider);
  await decoderStarted;
  try {
    await assert.rejects(
      uploadPurchaseDocumentHandler(directRequest, decoder, bucketProvider),
      (error) => error.code === "unavailable" && error.message === "DOCUMENT_UPLOAD_IN_PROGRESS",
    );
  } finally {
    releaseDecoder();
  }
  assert.equal((await first).ok, true);
  assert.equal(decodeCalls, 1);
  for (const [scope, value] of [["ACTOR", user.uid], ["BUSINESS", businessId]]) {
    assert.equal(
      (await db.doc(
        `documentUploadRateLimits/${documentUploadRateLimitDocumentId(scope, value)}`,
      ).get()).data().count,
      1,
      scope,
    );
  }
});

test("rate limit actor y negocio rechaza antes de decoder y sin reservar operación", async () => {
  let decodeCalls = 0;
  for (const scope of ["ACTOR", "BUSINESS"]) {
    const user = await verifiedUser();
    const { businessId, purchaseId } = await seed(user);
    const request = uploadRequest(businessId, purchaseId);
    request.expectedUid = user.uid;
    const value = scope === "ACTOR" ? user.uid : businessId;
    const policy = DOCUMENT_UPLOAD_RATE_POLICIES[scope];
    const now = Date.now();
    await db.doc(
      `documentUploadRateLimits/${documentUploadRateLimitDocumentId(scope, value)}`,
    ).set({
      schemaVersion: 1,
      scope,
      count: policy.limit,
      windowStartedAt: Timestamp.fromMillis(now),
      expiresAt: Timestamp.fromMillis(now + policy.windowMillis * 2),
    });

    await assert.rejects(
      uploadPurchaseDocumentHandler(
        { auth: { uid: user.uid, token: { email_verified: true } }, data: request },
        async () => {
          decodeCalls += 1;
          return { width: 16, height: 16 };
        },
      ),
      (error) =>
        error.message === "DOCUMENT_UPLOAD_RATE_LIMIT" && error.details?.scope === scope,
    );
    assert.equal(
      (await db.doc(
        `businesses/${businessId}/documentSyncOperations/${sha(request.idempotencyKey)}`,
      ).get()).exists,
      false,
    );
  }
  assert.equal(decodeCalls, 0);
});

test("expectedUid frena upload y purge antes de decoder, Firestore o Storage", async () => {
  const user = await verifiedUser();
  const { businessId, purchaseId } = await seed(user);
  const imageId = randomUUID();
  let decodeCalls = 0;
  let bucketCalls = 0;
  const mismatched = (data) => ({
    auth: { uid: user.uid, token: { email_verified: true } },
    data: { ...data, expectedUid: "another-account" },
  });
  const bucketProvider = () => {
    bucketCalls += 1;
    return recordingBucket();
  };

  await assert.rejects(
    uploadPurchaseDocumentHandler(
      mismatched(uploadRequest(businessId, purchaseId, imageId)),
      async () => {
        decodeCalls += 1;
        return { width: 16, height: 16 };
      },
      bucketProvider,
    ),
    (error) => error.code === "unauthenticated" && error.message === "AUTH_IDENTITY_CHANGED",
  );
  await assert.rejects(
    purgePurchaseDocumentHandler(
      mismatched(purgeRequest(businessId, purchaseId, imageId)),
      bucketProvider,
    ),
    (error) => error.code === "unauthenticated" && error.message === "AUTH_IDENTITY_CHANGED",
  );
  assert.equal(decodeCalls, 0);
  assert.equal(bucketCalls, 0);
  assert.equal(
    (await db.doc(`businesses/${businessId}/documentBackups/${imageId}`).get()).exists,
    false,
  );
  assert.equal(
    (await db.collection(`businesses/${businessId}/documentSyncOperations`).get()).empty,
    true,
  );
});

test("la frontera callable descarta rutas y mensajes brutos del Admin SDK", async () => {
  const user = await verifiedUser();
  const { businessId, purchaseId } = await seed(user);
  const sentinel = "gs://private-bucket/businesses/secret/invoices/private.jpg";
  const logged = [];
  const originalConsoleError = console.error;
  console.error = (...values) => logged.push(values.join(" "));
  try {
    await assert.rejects(
      uploadPurchaseDocumentHandler(
        {
          auth: { uid: user.uid, token: { email_verified: true } },
          data: uploadRequest(businessId, purchaseId),
        },
        async () => ({ width: 16, height: 16 }),
        () => {
          throw new Error(`Admin Storage failed at ${sentinel}`);
        },
      ),
      (error) => {
        assert.equal(error.code, "unavailable");
        assert.equal(error.message, "DOCUMENT_STORAGE_UNAVAILABLE");
        assert.equal(`${error}`.includes(sentinel), false);
        assert.equal(JSON.stringify(error).includes(sentinel), false);
        return true;
      },
    );
    await assert.rejects(
      purgePurchaseDocumentHandler(
        {
          auth: { uid: user.uid, token: { email_verified: true } },
          data: purgeRequest(businessId, purchaseId, randomUUID()),
        },
        () => {
          throw new Error(`Admin Storage purge failed at ${sentinel}`);
        },
      ),
      (error) => {
        assert.equal(error.code, "unavailable");
        assert.equal(error.message, "DOCUMENT_STORAGE_UNAVAILABLE");
        assert.equal(`${error}`.includes(sentinel), false);
        assert.equal(JSON.stringify(error).includes(sentinel), false);
        return true;
      },
    );
  } finally {
    console.error = originalConsoleError;
  }
  assert.equal(logged.join("\n").includes(sentinel), false);
});

test("purga es idempotente y deja tombstone que gana a un upload tardío", async () => {
  const user = await verifiedUser();
  const { businessId, purchaseId } = await seed(user);
  const imageId = randomUUID();
  const purge = purgeRequest(businessId, purchaseId, imageId);
  const upload = uploadRequest(businessId, purchaseId, imageId);

  const purged = await call("purgePurchaseDocument", purge, user.token);
  assert.equal(purged.body.error, undefined, JSON.stringify(purged.body));
  const late = await call("uploadPurchaseDocument", upload, user.token);
  assert.equal(late.body.error, undefined, JSON.stringify(late.body));
  assert.equal(late.body.result.discardedByPurge, true);
  assert.equal((await bucket.file(pathFor(upload)).exists())[0], false);
  assert.equal(
    (await db.doc(`businesses/${businessId}/documentBackups/${imageId}`).get()).data().status,
    "PURGED",
  );

  await db.doc(`businesses/${businessId}/members/${user.uid}`).update({ role: "READER" });
  const replay = await call("purgePurchaseDocument", purge, user.token);
  assert.equal(replay.body.error, undefined, JSON.stringify(replay.body));
});

test("retry autorizado completa Storage aunque membresia y compra ya no existan", async () => {
  const user = await verifiedUser();
  const { businessId, purchaseId } = await seed(user);
  const imageId = randomUUID();
  const purge = purgeRequest(businessId, purchaseId, imageId);
  purge.expectedUid = user.uid;
  const object = bucket.file(
    `businesses/${businessId}/invoices/${purchaseId}/${imageId}.jpg`,
  );
  await object.save(JPEG, { contentType: "image/jpeg" });
  const request = {
    auth: { uid: user.uid, token: { email_verified: true } },
    data: purge,
  };

  await assert.rejects(
    purgePurchaseDocumentHandler(request, () => ({
      file: () => ({ delete: async () => { throw new Error("synthetic delete outage"); } }),
    })),
    (error) => error.code === "unavailable" &&
      error.message === "DOCUMENT_STORAGE_UNAVAILABLE",
  );
  const operationRef = db.doc(
    `businesses/${businessId}/documentSyncOperations/${sha(purge.idempotencyKey)}`,
  );
  const reserved = (await operationRef.get()).data();
  assert.equal(reserved.status, "RESERVED");
  assert.equal(JSON.stringify(reserved).includes(user.uid), false);
  // El commit ya dejó un tombstone PURGED. El trigger onObjectFinalized puede ganar la carrera
  // y borrar el objeto aunque el delete sintético del callable haya fallado; ese estado
  // intermedio no es parte del contrato. El retry debe converger tanto si aún existe como si no.

  await db.doc(`businesses/${businessId}/members/${user.uid}`).delete();
  await db.doc(`businesses/${businessId}/purchases/${purchaseId}`).delete();
  const completed = await purgePurchaseDocumentHandler(request, () => bucket);

  assert.equal(completed.ok, true);
  assert.equal((await object.exists())[0], false);
  assert.equal((await operationRef.get()).data().status, "COMPLETE");
});

test("purga rechaza una imagen perteneciente a otra compra sin mutar cuota ni Storage", async () => {
  const user = await verifiedUser();
  const { businessId, purchaseId: firstPurchaseId } = await seed(user);
  const secondPurchaseId = randomUUID();
  const imageId = randomUUID();
  const backupRef = db.doc(`businesses/${businessId}/documentBackups/${imageId}`);
  const firstMetaRef = db.doc(
    `businesses/${businessId}/documentBackupMetadata/${firstPurchaseId}`,
  );
  const secondMetaRef = db.doc(
    `businesses/${businessId}/documentBackupMetadata/${secondPurchaseId}`,
  );
  const businessMetaRef = db.doc(`businesses/${businessId}/sync/documentMetadata`);
  await db.doc(`businesses/${businessId}/purchases/${secondPurchaseId}`).set({ status: "POSTED" });
  await backupRef.set({
    purchaseId: firstPurchaseId,
    imageId,
    sha256: sha(JPEG),
    size: JPEG.length,
    status: "COMPLETE",
    purgeRequested: false,
  });
  await firstMetaRef.set({ pageCount: 1 });
  // Reproduce el estado que antes permitía decrementar el contador de la compra equivocada.
  await secondMetaRef.set({ pageCount: 1 });
  await businessMetaRef.set({ totalBytes: JPEG.length });
  const object = bucket.file(
    `businesses/${businessId}/invoices/${firstPurchaseId}/${imageId}.jpg`,
  );
  await object.save(JPEG, { contentType: "image/jpeg" });
  const purge = purgeRequest(businessId, secondPurchaseId, imageId);

  await assert.rejects(
    purgePurchaseDocumentHandler({
      auth: { uid: user.uid, token: { email_verified: true } },
      data: purge,
    }),
    (error) => error.code === "failed-precondition" &&
      error.message === "DOCUMENT_IMAGE_PURCHASE_MISMATCH",
  );

  assert.equal((await backupRef.get()).data().status, "COMPLETE");
  assert.equal((await backupRef.get()).data().purgeRequested, false);
  assert.equal((await firstMetaRef.get()).data().pageCount, 1);
  assert.equal((await secondMetaRef.get()).data().pageCount, 1);
  assert.equal((await businessMetaRef.get()).data().totalBytes, JPEG.length);
  assert.equal((await object.exists())[0], true);
  assert.equal(
    (await db.doc(
      `businesses/${businessId}/documentSyncOperations/${sha(purge.idempotencyKey)}`,
    ).get()).exists,
    false,
  );
});

test("upload y purge concurrentes nunca dejan objeto huérfano", async () => {
  const user = await verifiedUser();
  const { businessId, purchaseId } = await seed(user);
  const imageId = randomUUID();
  const upload = uploadRequest(businessId, purchaseId, imageId);
  const purge = purgeRequest(businessId, purchaseId, imageId);
  const results = await Promise.all([
    call("uploadPurchaseDocument", upload, user.token),
    call("purgePurchaseDocument", purge, user.token),
  ]);
  assert.equal(results.every(({ body }) => body.error === undefined), true, JSON.stringify(results));
  assert.equal((await bucket.file(pathFor(upload)).exists())[0], false);
});

test("un finalizador revocado conserva el objeto confirmado por un retry concurrente", async () => {
  const user = await verifiedUser();
  const { businessId, purchaseId } = await seed(user);
  const upload = uploadRequest(businessId, purchaseId);
  const request = { auth: { uid: user.uid, token: { email_verified: true } }, data: upload };
  const file = bucket.file(pathFor(upload));
  let signalSaved;
  let releaseFirstUpload;
  const saved = new Promise((resolve) => { signalSaved = resolve; });
  const released = new Promise((resolve) => { releaseFirstUpload = resolve; });
  const first = uploadPurchaseDocumentHandler(request, decodeJpeg, () => ({
    file: () => ({
      async save(...args) {
        await file.save(...args);
        signalSaved();
        await released;
      },
      getMetadata: (...args) => file.getMetadata(...args),
      delete: (...args) => file.delete(...args),
    }),
  })).then(
    value => ({ value }),
    error => ({ error }),
  );
  try {
    // The first invocation has a durable RESERVED marker and bytes, but has not finalized.
    await Promise.race([
      saved,
      first.then((result) => { throw result.error ?? new Error("upload did not pause"); }),
    ]);
    const replay = await uploadPurchaseDocumentHandler(request, decodeJpeg, () => bucket);
    assert.equal(replay.ok, true);
    const backupRef = db.doc(`businesses/${businessId}/documentBackups/${upload.document.imageId}`);
    assert.equal((await backupRef.get()).data().status, "COMPLETE");

    await db.doc(`businesses/${businessId}/members/${user.uid}`).delete();
    releaseFirstUpload();
    const late = await first;
    assert.equal(late.error?.message, "NOT_A_MEMBER");
    assert.equal((await file.exists())[0], true);
    assert.equal((await backupRef.get()).data().status, "COMPLETE");
    assert.equal(
      (await db.doc(`businesses/${businessId}/documentSyncOperations/${sha(upload.idempotencyKey)}`).get())
        .data().status,
      "COMPLETE",
    );
  } finally {
    releaseFirstUpload();
    await first;
  }
});

test("lock de account deletion gana tras reserva y limpia un save tardío", async () => {
  const user = await verifiedUser();
  const { businessId, purchaseId } = await seed(user);
  const imageId = randomUUID();
  const digest = sha(JPEG);
  const idempotencyKey = `document-upload:v1:${purchaseId}:${imageId}:${digest}`;
  const requestHash = "a".repeat(64);
  const operationId = sha(idempotencyKey);
  await db.doc(`businesses/${businessId}/documentSyncOperations/${operationId}`).set({
    idempotencyKey,
    requestHash,
    status: "RESERVED",
  });
  await db.doc(`businesses/${businessId}/documentBackups/${imageId}`).set({
    purchaseId,
    imageId,
    sha256: digest,
    size: JPEG.length,
    status: "RESERVED",
    purgeRequested: false,
  });
  const path = `businesses/${businessId}/invoices/${purchaseId}/${imageId}.jpg`;
  const file = bucket.file(path);

  // Account deletion fija primero el lock y purga el prefix; el request reservado completa
  // su transferencia después. La segunda validación debe borrarlo de nuevo y no dar ACK.
  await db.doc(`businesses/${businessId}`).update({ accountDeletionLocked: true });
  await bucket.deleteFiles({ prefix: `businesses/${businessId}/` });
  await file.save(JPEG, { contentType: "image/jpeg" });
  await assert.rejects(
    finalizeReservedDocumentUpload({
      businessId,
      uid: user.uid,
      purchaseId,
      imageId,
      idempotencyKey,
      requestHash,
      file,
    }),
    (error) => error.message === "BUSINESS_DELETION_IN_PROGRESS",
  );
  assert.equal((await file.exists())[0], false);
  assert.equal(
    (await db.doc(`businesses/${businessId}/documentSyncOperations/${operationId}`).get())
      .data().status,
    "RESERVED",
  );
});

test("onFinalize conserva RESERVED fresca para que el callable normal pueda completar", async () => {
  const user = await verifiedUser();
  const now = Date.now();
  const reserved = await seedReservedDocument(user, now - 1_000);
  const fakeBucket = recordingBucket();

  const action = await reconcileFinalizedPurchaseDocument(
    { data: { bucket: BUCKET, name: reserved.name } },
    { database: db, bucketProvider: () => fakeBucket, nowMillis: now },
  );

  assert.equal(action, "DEFER");
  assert.deepEqual(fakeBucket.deleted, []);
  assert.equal(
    (await db.doc(`businesses/${reserved.businessId}/documentBackups/${reserved.imageId}`).get())
      .data().status,
    "RESERVED",
  );
});

test("onFinalize elimina un objeto documental sin marcador Firestore permitido", async () => {
  const user = await verifiedUser();
  const { businessId, purchaseId } = await seed(user);
  const imageId = randomUUID();
  const name = `businesses/${businessId}/invoices/${purchaseId}/${imageId}.jpg`;
  const fakeBucket = recordingBucket();

  const action = await reconcileFinalizedPurchaseDocument(
    { data: { bucket: BUCKET, name } },
    { database: db, bucketProvider: () => fakeBucket, nowMillis: Date.now() },
  );

  assert.equal(action, "DELETE");
  assert.deepEqual(fakeBucket.deleted, [name]);
});

test("retry del onFinalize y barrido comparten recuperacion durable", async () => {
  const user = await verifiedUser();
  const now = Date.now();
  const reserved = await seedReservedDocument(user, now - 11 * 60 * 1_000);
  const fakeBucket = recordingBucket();

  assert.equal(DOCUMENT_FINALIZE_OPTIONS.retry, true);
  await assert.rejects(
    reconcileFinalizedPurchaseDocument(
      { data: { bucket: BUCKET, name: reserved.name } },
      {
        database: {
          doc: (path) => db.doc(path),
          getAll: async () => {
            throw new Error("transient firestore failure");
          },
        },
        bucketProvider: () => fakeBucket,
        nowMillis: now,
      },
    ),
    /DOCUMENT_RECONCILIATION_UNAVAILABLE/,
  );
  assert.deepEqual(fakeBucket.deleted, [], "el evento fallido no hizo una purga parcial");

  const result = await reconcileStaleReservedDocumentsHandler(
    {},
    { database: db, bucketProvider: () => fakeBucket, nowMillis: now },
  );

  assert.ok(result.scanned >= 1);
  assert.ok(result.deleted >= 1);
  assert.deepEqual(fakeBucket.deleted, [reserved.name]);
  const marker = (
    await db.doc(
      `businesses/${reserved.businessId}/documentBackups/${reserved.imageId}`,
    ).get()
  ).data();
  assert.equal(marker.status, "PURGED");
  assert.equal(marker.purgeRequested, true);
  assert.equal(marker.reconciliationReason, "STALE_RESERVATION");
  assert.equal(
    (await db.doc(
      `businesses/${reserved.businessId}/documentBackupMetadata/${reserved.purchaseId}`,
    ).get()).data().pageCount,
    0,
  );
  assert.equal(
    (await db.doc(`businesses/${reserved.businessId}/sync/documentMetadata`).get())
      .data().totalBytes,
    0,
  );
});

test("reconciliador pone en cuarentena reservas corruptas y libera la ventana", async () => {
  const user = await verifiedUser();
  const { businessId } = await seed(user);
  const malformedRef = db.doc(`businesses/${businessId}/documentBackups/not-a-uuid`);
  await malformedRef.set({
    purchaseId: "not-a-purchase",
    status: "RESERVED",
    createdAt: Timestamp.fromMillis(Date.now() - 11 * 60 * 1_000),
  });
  const fakeBucket = recordingBucket();

  const result = await reconcileStaleReservedDocumentsHandler(
    {},
    { database: db, bucketProvider: () => fakeBucket, nowMillis: Date.now() },
  );

  assert.ok(result.scanned >= 1);
  assert.ok(result.invalid >= 1);
  assert.deepEqual(fakeBucket.deleted, []);
  const quarantined = (await malformedRef.get()).data();
  assert.equal(quarantined.status, "INVALID");
  assert.equal(quarantined.reconciliationReason, "INVALID_RESERVATION_IDENTITY");
  assert.ok(quarantined.reconciledAt instanceof Timestamp);
});

test("markers corruptos liberan ambos lotes y los validos posteriores se purgan", async () => {
  const user = await verifiedUser();
  const { businessId, purchaseId } = await seed(user);
  const validImageId = randomUUID();
  const validPath = `businesses/${businessId}/invoices/${purchaseId}/${validImageId}.jpg`;
  const validBackupRef = db.doc(
    `businesses/${businessId}/documentBackups/${validImageId}`,
  );
  const accountMarkerToken = randomUUID();
  const validAccountRef = db.doc(
    `accountDeletionTombstones/reconcile-valid-${accountMarkerToken}`,
  );
  const invalidAccountRefs = [];
  const batch = db.batch();
  for (let index = 0; index < 100; index += 1) {
    batch.set(
      db.doc(`businesses/${businessId}/documentBackups/invalid-purge-${index}`),
      {
        purchaseId: "invalid-purchase",
        status: "PURGED",
        purgeRequested: true,
        purgedAt: Timestamp.fromMillis(index + 1),
      },
    );
    const invalidAccountRef = db.doc(
      `accountDeletionTombstones/reconcile-invalid-${accountMarkerToken}-${index}`,
    );
    invalidAccountRefs.push(invalidAccountRef);
    batch.set(invalidAccountRef, {
      cleanupComplete: true,
      storageCleanupPending: true,
      storageCleanupBusinessIds: ["invalid-business-id"],
      retiredBusinessIds: [],
      storageCleanupEligibleAt: Timestamp.fromMillis(index + 1),
    });
  }
  batch.set(validBackupRef, {
    purchaseId,
    imageId: validImageId,
    status: "PURGED",
    purgeRequested: true,
    purgedAt: Timestamp.fromMillis(1_000),
  });
  batch.set(validAccountRef, {
    cleanupComplete: true,
    storageCleanupPending: true,
    storageCleanupBusinessIds: [businessId],
    retiredBusinessIds: [businessId],
    storageCleanupEligibleAt: Timestamp.fromMillis(1_000),
  });
  await batch.commit();
  const fakeBucket = recordingBucket();

  try {
    const first = await reconcileStaleReservedDocumentsHandler(
      {},
      { database: db, bucketProvider: () => fakeBucket, nowMillis: Date.now() },
    );
    assert.equal(first.purgedScanned, 101);
    assert.equal(first.purgedInvalid, 100);
    assert.equal(first.accountMarkersScanned, 101);
    assert.equal(first.accountMarkersInvalid, 100);
    assert.equal((await validBackupRef.get()).data().purgeRequested, false);
    assert.equal((await validAccountRef.get()).data().storageCleanupPending, false);
    const quarantinedPurge = (await db.doc(
      `businesses/${businessId}/documentBackups/invalid-purge-0`,
    ).get()).data();
    assert.equal(quarantinedPurge.purgeRequested, false);
    assert.equal(quarantinedPurge.reconciliationReason, "INVALID_PURGE_IDENTITY");
    const quarantinedAccount = (await invalidAccountRefs[0].get()).data();
    assert.equal(quarantinedAccount.storageCleanupPending, false);
    assert.equal(
      quarantinedAccount.storageCleanupFailureReason,
      "INVALID_BUSINESS_IDENTIFIERS",
    );

    assert.ok(first.purgedDeleted >= 1);
    assert.ok(first.accountPrefixesDeleted >= 1);
    assert.ok(fakeBucket.deleted.includes(validPath));
    assert.ok(fakeBucket.prefixesDeleted.includes(`businesses/${businessId}/`));
    const validBackup = (await validBackupRef.get()).data();
    assert.equal(validBackup.purgeRequested, false);
    assert.ok(validBackup.storagePurgedAt instanceof Timestamp);
    const validAccount = (await validAccountRef.get()).data();
    assert.equal(validAccount.storageCleanupPending, false);
    assert.deepEqual(validAccount.storageCleanupBusinessIds, []);
    assert.deepEqual(validAccount.retiredBusinessIds, [businessId]);
    assert.ok(validAccount.storageCleanupCompletedAt instanceof Timestamp);
  } finally {
    const cleanup = db.batch();
    for (const ref of invalidAccountRefs) cleanup.delete(ref);
    cleanup.delete(validAccountRef);
    await cleanup.commit();
  }
});

test("fallos Storage por tenant se difieren sin ocultar el elemento 101 ni account cleanup", async () => {
  const user = await verifiedUser();
  const { businessId, purchaseId } = await seed(user);
  const now = Date.now();
  const poisonPaths = new Set();
  const poisonPrefixes = new Set();
  const accountRefs = [];
  let firstPoisonBackupRef = null;
  const batch = db.batch();
  for (let index = 0; index < 100; index += 1) {
    const imageId = randomUUID();
    const path = `businesses/${businessId}/invoices/${purchaseId}/${imageId}.jpg`;
    poisonPaths.add(path);
    const backupRef = db.doc(`businesses/${businessId}/documentBackups/${imageId}`);
    firstPoisonBackupRef ??= backupRef;
    batch.set(backupRef, {
      purchaseId,
      imageId,
      status: "PURGED",
      purgeRequested: true,
      purgedAt: Timestamp.fromMillis(index + 1),
    });
    const cleanupBusinessId = randomUUID();
    const accountRef = db.doc(
      `accountDeletionTombstones/storage-poison-${randomUUID()}`,
    );
    accountRefs.push(accountRef);
    poisonPrefixes.add(`businesses/${cleanupBusinessId}/`);
    batch.set(accountRef, {
      cleanupComplete: true,
      storageCleanupPending: true,
      storageCleanupBusinessIds: [cleanupBusinessId],
      retiredBusinessIds: [cleanupBusinessId],
      storageCleanupEligibleAt: Timestamp.fromMillis(index + 1),
    });
  }
  const validImageId = randomUUID();
  const validPath =
    `businesses/${businessId}/invoices/${purchaseId}/${validImageId}.jpg`;
  const validBackupRef = db.doc(
    `businesses/${businessId}/documentBackups/${validImageId}`,
  );
  batch.set(validBackupRef, {
    purchaseId,
    imageId: validImageId,
    status: "PURGED",
    purgeRequested: true,
    purgedAt: Timestamp.fromMillis(1_000),
  });
  const validCleanupBusinessId = randomUUID();
  const validAccountRef = db.doc(
    `accountDeletionTombstones/storage-valid-${randomUUID()}`,
  );
  accountRefs.push(validAccountRef);
  batch.set(validAccountRef, {
    cleanupComplete: true,
    storageCleanupPending: true,
    storageCleanupBusinessIds: [validCleanupBusinessId],
    retiredBusinessIds: [validCleanupBusinessId],
    storageCleanupEligibleAt: Timestamp.fromMillis(1_000),
  });
  await batch.commit();
  const deleted = [];
  const prefixesDeleted = [];
  const fakeBucket = {
    name: BUCKET,
    file: (name) => ({
      delete: async () => {
        if (poisonPaths.has(name)) throw Object.assign(new Error("held"), { code: 412 });
        deleted.push(name);
      },
    }),
    deleteFiles: async ({ prefix }) => {
      if (poisonPrefixes.has(prefix)) throw Object.assign(new Error("held"), { code: 412 });
      prefixesDeleted.push(prefix);
    },
  };

  try {
    const result = await reconcileStaleReservedDocumentsHandler(
      {},
      { database: db, bucketProvider: () => fakeBucket, nowMillis: now },
    );

    assert.equal(result.purgedFailed, 100);
    assert.ok(result.purgedScanned >= 101);
    assert.ok(result.purgedDeleted >= 1);
    assert.ok(deleted.includes(validPath));
    assert.equal((await validBackupRef.get()).data().purgeRequested, false);
    const deferredPurge = (await firstPoisonBackupRef.get()).data();
    assert.equal(deferredPurge.purgeRequested, true);
    assert.equal(deferredPurge.reconciliationFailureReason, "STORAGE_DELETE_UNAVAILABLE");
    assert.ok(deferredPurge.originalPurgedAt instanceof Timestamp);

    assert.equal(result.accountMarkersFailed, 100);
    assert.ok(result.accountMarkersScanned >= 101);
    assert.ok(result.accountPrefixesDeleted >= 1);
    assert.ok(prefixesDeleted.includes(`businesses/${validCleanupBusinessId}/`));
    assert.equal((await validAccountRef.get()).data().storageCleanupPending, false);
    const deferredAccount = (await accountRefs[0].get()).data();
    assert.equal(deferredAccount.storageCleanupPending, true);
    assert.equal(
      deferredAccount.storageCleanupFailureReason,
      "STORAGE_DELETE_UNAVAILABLE",
    );
    assert.ok(deferredAccount.storageCleanupEligibleAt.toMillis() > now);
  } finally {
    const cleanup = db.batch();
    for (const ref of accountRefs) cleanup.delete(ref);
    await cleanup.commit();
  }
});

test("onFinalize elimina un save tardío cuando account deletion ya fijó el lock", async () => {
  const user = await verifiedUser();
  const now = Date.now();
  const reserved = await seedReservedDocument(user, now - 1_000);
  await db.doc(`businesses/${reserved.businessId}`).update({ accountDeletionLocked: true });
  const fakeBucket = recordingBucket();

  const action = await reconcileFinalizedPurchaseDocument(
    { data: { bucket: BUCKET, name: reserved.name } },
    { database: db, bucketProvider: () => fakeBucket, nowMillis: now },
  );

  assert.equal(action, "DELETE");
  assert.deepEqual(fakeBucket.deleted, [reserved.name]);
});

test("cuota limita páginas por compra", async () => {
  const user = await verifiedUser();
  const { businessId, purchaseId } = await seed(user);
  for (let index = 0; index < 5; index += 1) {
    const result = await call(
      "uploadPurchaseDocument",
      uploadRequest(businessId, purchaseId, randomUUID()),
      user.token,
    );
    assert.equal(result.body.error, undefined, JSON.stringify(result.body));
  }
  const sixth = await call(
    "uploadPurchaseDocument",
    uploadRequest(businessId, purchaseId, randomUUID()),
    user.token,
  );
  assert.equal(sixth.body.error?.message, "DOCUMENT_PAGE_QUOTA");
});
