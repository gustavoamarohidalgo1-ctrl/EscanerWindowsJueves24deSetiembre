import { readFile } from "node:fs/promises";
import { test } from "node:test";
import assert from "node:assert/strict";

const INDEX_CONFIGURATION = new URL("../../firestore.indexes.json", import.meta.url);

test("los collectionGroup usados por membresía y borrado tienen índice declarado", async () => {
  const configuration = JSON.parse(await readFile(INDEX_CONFIGURATION, "utf8"));
  const configured = new Set(
    configuration.fieldOverrides
      .filter((override) =>
        override.indexes?.some(
          (index) =>
            index.queryScope === "COLLECTION_GROUP" && index.order === "ASCENDING",
        ),
      )
      .map((override) => `${override.collectionGroup}.${override.fieldPath}`),
  );
  const required = new Set([
    "members.uid",
    "members.roleUpdatedBy",
    "invitations.email",
    "invitations.invitedBy",
    "invitations.acceptedBy",
    "invitations.declinedBy",
    "invitations.cancelledBy",
    "purchases.syncedBy",
    "purchases.voidedBy",
    "voidRecord.cloudActorUid",
  ]);

  assert.deepEqual(
    [...required].filter((field) => !configured.has(field)),
    [],
  );
});

test("el lock pseudónimo de email tiene TTL versionado y el campo no se indexa", async () => {
  const configuration = JSON.parse(await readFile(INDEX_CONFIGURATION, "utf8"));
  const policy = configuration.fieldOverrides.find(
    (override) =>
      override.collectionGroup === "accountDeletionEmailLocks" &&
      override.fieldPath === "expiresAt",
  );

  assert.equal(policy?.ttl, true);
  assert.deepEqual(policy?.indexes, []);
});

test("invitaciones y estados efímeros antiabuso tienen TTL físico", async () => {
  const configuration = JSON.parse(await readFile(INDEX_CONFIGURATION, "utf8"));
  for (const collectionGroup of [
    "invitations",
    "invitationRateLimits",
    "documentUploadRateLimits",
    "documentSyncOperations",
    "syncMutationRateLimits",
  ]) {
    const policy = configuration.fieldOverrides.find(
      (override) =>
        override.collectionGroup === collectionGroup && override.fieldPath === "expiresAt",
    );
    assert.equal(policy?.ttl, true, collectionGroup);
    assert.deepEqual(policy?.indexes, [], collectionGroup);
  }
});

test("el counter durable de membresías no indexa campos que nunca se consultan", async () => {
  const configuration = JSON.parse(await readFile(INDEX_CONFIGURATION, "utf8"));
  for (const fieldPath of ["count", "updatedAt", "schemaVersion"]) {
    const policy = configuration.fieldOverrides.find(
      (override) =>
        override.collectionGroup === "membershipQuotaCounters" &&
        override.fieldPath === fieldPath,
    );
    assert.deepEqual(policy?.indexes, [], fieldPath);
    assert.equal(policy?.ttl, undefined, fieldPath);
  }
});

test("reservas documentales vencidas tienen índice collectionGroup para reconciliación", async () => {
  const configuration = JSON.parse(await readFile(INDEX_CONFIGURATION, "utf8"));
  const index = configuration.indexes.find(
    (candidate) =>
      candidate.collectionGroup === "documentBackups" &&
      candidate.queryScope === "COLLECTION_GROUP" &&
      candidate.fields.some((field) => field.fieldPath === "status"),
  );

  assert.deepEqual(index?.fields, [
    { fieldPath: "status", order: "ASCENDING" },
    { fieldPath: "createdAt", order: "ASCENDING" },
  ]);
});

test("purgas documentales pendientes tienen índice collectionGroup para reconciliación", async () => {
  const configuration = JSON.parse(await readFile(INDEX_CONFIGURATION, "utf8"));
  const index = configuration.indexes.find(
    (candidate) =>
      candidate.collectionGroup === "documentBackups" &&
      candidate.queryScope === "COLLECTION_GROUP" &&
      candidate.fields.some((field) => field.fieldPath === "purgeRequested"),
  );

  assert.deepEqual(index?.fields, [
    { fieldPath: "purgeRequested", order: "ASCENDING" },
    { fieldPath: "purgedAt", order: "ASCENDING" },
  ]);
});

test("limpieza Storage de cuentas exige el índice compuesto exacto", async () => {
  const configuration = JSON.parse(await readFile(INDEX_CONFIGURATION, "utf8"));
  const index = configuration.indexes.find(
    (candidate) =>
      candidate.collectionGroup === "accountDeletionTombstones" &&
      candidate.queryScope === "COLLECTION",
  );

  assert.deepEqual(index?.fields, [
    { fieldPath: "cleanupComplete", order: "ASCENDING" },
    { fieldPath: "storageCleanupPending", order: "ASCENDING" },
    { fieldPath: "storageCleanupEligibleAt", order: "ASCENDING" },
  ]);
});


test("la identidad temporal de trabajos de borrado no se indexa ni vence antes de completarse", async () => {
  const configuration = JSON.parse(await readFile(INDEX_CONFIGURATION, "utf8"));
  for (const fieldPath of ["uid", "email", "summary", "leaseToken"]) {
    const policy = configuration.fieldOverrides.find((override) =>
      override.collectionGroup === "accountDeletionJobs" && override.fieldPath === fieldPath);
    assert.deepEqual(policy?.indexes, [], fieldPath);
    assert.equal(policy?.ttl, undefined, fieldPath);
  }
});
