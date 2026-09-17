import { test } from "node:test";
import assert from "node:assert/strict";

process.env.GCLOUD_PROJECT ??= "demo-facturastock";
process.env.FIREBASE_CONFIG ??= JSON.stringify({
  projectId: process.env.GCLOUD_PROJECT,
  storageBucket: `${process.env.GCLOUD_PROJECT}.appspot.com`,
});
const { finalizeReservedDocumentUpload } = await import("../documentBackup.js");

const businessId = "00000000-0000-4000-8000-000000000001";
const purchaseId = "00000000-0000-4000-8000-000000000002";
const imageId = "00000000-0000-4000-8000-000000000003";
const requestHash = "a".repeat(64);
const snapshot = (data) => ({ exists: data !== undefined, data: () => data });

function scenario({ business = {}, backupStatus = "COMPLETE", firstFailure = null,
  cleanupUnavailable = false } = {}) {
  const backup = { purchaseId, imageId, status: backupStatus, purgeRequested: false };
  const operation = { status: "COMPLETE", requestHash };
  let deletes = 0;
  let transactionCount = 0;
  const database = {
    async runTransaction(callback) {
      transactionCount += 1;
      if (transactionCount === 1 && firstFailure !== null) throw firstFailure;
      if (transactionCount > 1 && cleanupUnavailable) throw new Error("synthetic read outage");
      return callback({
        async getAll(...refs) {
          return refs.map((ref) => {
            if (ref.path === `businesses/${businessId}`) return snapshot(business);
            if (ref.path.includes("/documentBackups/")) return snapshot(backup);
            if (ref.path.includes("/documentSyncOperations/")) return snapshot(operation);
            if (ref.path.includes("/purchases/")) return snapshot({ status: "POSTED" });
            // Both the account tombstone and the revoked membership are absent.
            return snapshot(undefined);
          });
        },
        update() { assert.fail("An unauthorized finalizer must not modify Firestore"); },
      });
    },
  };
  return {
    backup,
    operation,
    get deletes() { return deletes; },
    finalize: () => finalizeReservedDocumentUpload({
      businessId, purchaseId, imageId, requestHash,
      uid: "revoked-member", idempotencyKey: "test-upload",
      file: { async delete() { deletes += 1; } },
    }, { database }),
  };
}

test("revoked late finalizer preserves the object confirmed by another invocation", async () => {
  const state = scenario();
  await assert.rejects(state.finalize(), error => error.message === "NOT_A_MEMBER");
  assert.equal(state.deletes, 0);
  assert.equal(state.backup.status, "COMPLETE");
  assert.equal(state.operation.status, "COMPLETE");
});

test("transient finalization failure preserves COMPLETE and the original error", async () => {
  const outage = new Error("synthetic commit acknowledgement outage");
  const state = scenario({ firstFailure: outage });
  await assert.rejects(state.finalize(), error => error === outage);
  assert.equal(state.deletes, 0);
  assert.equal(state.backup.status, "COMPLETE");
});

test("unconfirmed cleanup preserves bytes during a Firestore outage", async () => {
  const outage = new Error("synthetic Firestore outage");
  const state = scenario({ firstFailure: outage, cleanupUnavailable: true });
  await assert.rejects(state.finalize(), error => error === outage);
  assert.equal(state.deletes, 0);
});

test("revoked late finalizer leaves RESERVED to the durable reconciler", async () => {
  const state = scenario({ backupStatus: "RESERVED" });
  await assert.rejects(state.finalize(), error => error.message === "NOT_A_MEMBER");
  assert.equal(state.deletes, 0);
  assert.equal(state.backup.status, "RESERVED");
});

test("document purge still removes late bytes even when the actor was revoked", async () => {
  const state = scenario({ backupStatus: "PURGED" });
  await assert.rejects(state.finalize(), error => error.message === "NOT_A_MEMBER");
  assert.equal(state.deletes, 1);
});

test("business deletion lock still removes late bytes after finalization fails", async () => {
  const state = scenario({ business: { accountDeletionLocked: true } });
  await assert.rejects(state.finalize(), error => error.message === "BUSINESS_DELETION_IN_PROGRESS");
  assert.equal(state.deletes, 1);
});
