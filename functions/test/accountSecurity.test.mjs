import { test } from "node:test";
import assert from "node:assert/strict";

process.env.GCLOUD_PROJECT ??= "demo-facturastock";
const {
  ACCOUNT_DELETION_CALLABLE_OPTIONS,
  CALLABLE_OPTIONS,
  DOCUMENT_CALLABLE_OPTIONS,
  DOCUMENT_FINALIZE_OPTIONS,
  DOCUMENT_RECONCILIATION_SCHEDULE_OPTIONS,
  callableOptionsForEnvironment,
  requireExpectedUid,
  requireRecentAuth,
} = await import("../common.js");
const { purgeOwnedBusinessDocuments } = await import("../accountDeletion.js");

test("recent-auth acepta login reciente y rechaza token viejo o futuro", () => {
  const now = 2_000_000;
  assert.equal(
    requireRecentAuth({ auth: { uid: "u", token: { auth_time: now - 299 } } }, now).uid,
    "u",
  );
  for (const authTime of [now - 301, now + 31, undefined]) {
    assert.throws(
      () => requireRecentAuth({ auth: { uid: "u", token: { auth_time: authTime } } }, now),
      (error) => error.code === "failed-precondition" && error.message === "RECENT_AUTH_REQUIRED",
    );
  }
});

test("expectedUid conserva compatibilidad legacy y corta un cambio de identidad", () => {
  const auth = { uid: "account-b", token: {} };
  assert.equal(requireExpectedUid({ auth, data: {} }), auth);
  assert.equal(requireExpectedUid({ auth, data: { expectedUid: "account-b" } }), auth);
  assert.throws(
    () => requireExpectedUid({ auth, data: { expectedUid: "account-a" } }),
    (error) => error.code === "unauthenticated" && error.message === "AUTH_IDENTITY_CHANGED",
  );
  for (const expectedUid of [null, "", "x".repeat(129)]) {
    assert.throws(
      () => requireExpectedUid({ auth, data: { expectedUid } }),
      (error) => error.code === "invalid-argument" && error.message === "EXPECTED_UID",
    );
  }
});

test("App Check solo se relaja para FUNCTIONS_EMULATOR=true y los recursos quedan acotados", () => {
  assert.equal(callableOptionsForEnvironment({}).enforceAppCheck, true);
  assert.equal(callableOptionsForEnvironment({ FUNCTIONS_EMULATOR: "false" }).enforceAppCheck, true);
  assert.equal(callableOptionsForEnvironment({ FUNCTIONS_EMULATOR: "TRUE" }).enforceAppCheck, true);
  assert.equal(callableOptionsForEnvironment({ FUNCTIONS_EMULATOR: "true" }).enforceAppCheck, false);
  assert.deepEqual(
    {
      maxInstances: CALLABLE_OPTIONS.maxInstances,
      concurrency: CALLABLE_OPTIONS.concurrency,
      timeoutSeconds: CALLABLE_OPTIONS.timeoutSeconds,
      memory: CALLABLE_OPTIONS.memory,
    },
    { maxInstances: 20, concurrency: 20, timeoutSeconds: 60, memory: "256MiB" },
  );
  assert.deepEqual(
    {
      maxInstances: DOCUMENT_CALLABLE_OPTIONS.maxInstances,
      concurrency: DOCUMENT_CALLABLE_OPTIONS.concurrency,
      timeoutSeconds: DOCUMENT_CALLABLE_OPTIONS.timeoutSeconds,
      memory: DOCUMENT_CALLABLE_OPTIONS.memory,
    },
    { maxInstances: 5, concurrency: 2, timeoutSeconds: 90, memory: "512MiB" },
  );
  assert.deepEqual(
    {
      maxInstances: ACCOUNT_DELETION_CALLABLE_OPTIONS.maxInstances,
      concurrency: ACCOUNT_DELETION_CALLABLE_OPTIONS.concurrency,
      timeoutSeconds: ACCOUNT_DELETION_CALLABLE_OPTIONS.timeoutSeconds,
      memory: ACCOUNT_DELETION_CALLABLE_OPTIONS.memory,
    },
    { maxInstances: 3, concurrency: 1, timeoutSeconds: 300, memory: "512MiB" },
  );
  assert.deepEqual(
    {
      retry: DOCUMENT_FINALIZE_OPTIONS.retry,
      maxInstances: DOCUMENT_FINALIZE_OPTIONS.maxInstances,
      concurrency: DOCUMENT_FINALIZE_OPTIONS.concurrency,
      timeoutSeconds: DOCUMENT_FINALIZE_OPTIONS.timeoutSeconds,
      memory: DOCUMENT_FINALIZE_OPTIONS.memory,
    },
    { retry: true, maxInstances: 3, concurrency: 2, timeoutSeconds: 90, memory: "256MiB" },
  );
  assert.deepEqual(
    {
      maxInstances: DOCUMENT_RECONCILIATION_SCHEDULE_OPTIONS.maxInstances,
      concurrency: DOCUMENT_RECONCILIATION_SCHEDULE_OPTIONS.concurrency,
      timeoutSeconds: DOCUMENT_RECONCILIATION_SCHEDULE_OPTIONS.timeoutSeconds,
      retryCount: DOCUMENT_RECONCILIATION_SCHEDULE_OPTIONS.retryCount,
      maxRetrySeconds: DOCUMENT_RECONCILIATION_SCHEDULE_OPTIONS.maxRetrySeconds,
    },
    {
      maxInstances: 1,
      concurrency: 1,
      timeoutSeconds: 300,
      retryCount: 3,
      maxRetrySeconds: 900,
    },
  );
});

test("purga Storage de todos los negocios propios antes de continuar", async () => {
  const prefixes = [];
  await purgeOwnedBusinessDocuments(
    [{ id: "a" }, { id: "b" }],
    { deleteFiles: async ({ prefix }) => prefixes.push(prefix) },
  );
  assert.deepEqual(prefixes, ["businesses/a/", "businesses/b/"]);
});

test("fallo de purga Storage corta el barrido", async () => {
  const prefixes = [];
  await assert.rejects(
    purgeOwnedBusinessDocuments(
      [{ id: "a" }, { id: "b" }],
      {
        deleteFiles: async ({ prefix }) => {
          prefixes.push(prefix);
          throw new Error("storage unavailable");
        },
      },
    ),
  );
  assert.deepEqual(prefixes, ["businesses/a/"]);
});
