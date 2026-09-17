// Recuperación real Firestore/Auth en Emulator Suite; dependencias fallan solo en el punto
// declarado para probar muerte de proceso, ACK perdido y aislamiento entre generaciones.
import { afterEach, test } from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { getAuth } from "firebase-admin/auth";
import { FieldValue, Timestamp } from "firebase-admin/firestore";

process.env.GCLOUD_PROJECT ??= "demo-facturastock";
process.env.FIRESTORE_EMULATOR_HOST ??= "127.0.0.1:8080";
process.env.FIREBASE_AUTH_EMULATOR_HOST ??= "127.0.0.1:9099";
process.env.FIREBASE_STORAGE_EMULATOR_HOST ??= "127.0.0.1:9199";
process.env.FIREBASE_CONFIG ??= JSON.stringify({
  projectId: process.env.GCLOUD_PROJECT,
  storageBucket: `${process.env.GCLOUD_PROJECT}.appspot.com`,
});
const { db, accountDeletionTombstoneRef, accountDeletionEmailLockRef } = await import("../common.js");
const {
  accountDeletionJobRef, lockAccountDeletion, runAccountDeletion, resumeAccountDeletionsHandler,
  deleteMyAccountHandler,
} = await import("../accountDeletion.js");
const { createBusiness, listMyMemberships } = await import("../membership.js");
const users = [];
const businesses = [];
const extraRefs = [];
const request = (user, data = {}) => ({
  auth: { uid: user.uid, token: {
    email: user.email, email_verified: true, auth_time: Math.floor(Date.now() / 1000),
  } },
  data: { expectedUid: user.uid, ...data },
});
async function owner() {
  const user = await getAuth().createUser({
    email: `recovery-${randomUUID()}@example.test`,
    password: "recovery-password-123",
    emailVerified: true,
  });
  users.push(user);
  const businessId = randomUUID();
  businesses.push(businessId);
  await createBusiness.run(request(user, { businessId, displayName: "Recovery" }));
  return { user, businessId };
}
afterEach(async () => {
  for (const id of businesses.splice(0)) await db.recursiveDelete(db.doc(`businesses/${id}`));
  for (const user of users.splice(0)) {
    await getAuth().deleteUser(user.uid).catch(() => {});
    await accountDeletionJobRef(user.uid).delete();
    await accountDeletionTombstoneRef(user.uid).delete();
    await accountDeletionEmailLockRef(user.email).delete();
  }
  for (const ref of extraRefs.splice(0)) await ref.delete();
});

test("un fallo tras el lock se completa desde scheduler sin otro login", async () => {
  const { user, businessId } = await owner();
  const { user: other, businessId: otherBusinessId } = await owner();
  const now = Date.now();
  await assert.rejects(runAccountDeletion(user.uid, user.email, {
    nowMillis: now,
    recursiveDelete: async () => { throw new Error("INJECTED_FIRESTORE_OUTAGE"); },
  }), /INJECTED_FIRESTORE_OUTAGE/);
  assert.equal((await db.doc(`businesses/${businessId}`).get()).data().accountDeletionLocked, true);
  assert.equal((await accountDeletionTombstoneRef(user.uid).get()).data().cleanupComplete, false);
  assert.equal((await accountDeletionJobRef(user.uid).get()).data().uid, user.uid);
  await assert.rejects(listMyMemberships.run(request(user)), /ACCOUNT_DELETION_IN_PROGRESS/);
  assert.equal((await getAuth().getUser(user.uid)).uid, user.uid);

  const result = await resumeAccountDeletionsHandler(null, { nowMillis: now + 61_000 });
  assert.equal(result.completed, 1);
  assert.equal((await db.doc(`businesses/${businessId}`).get()).exists, false);
  await assert.rejects(getAuth().getUser(user.uid), (error) => error.code === "auth/user-not-found");
  assert.equal((await accountDeletionJobRef(user.uid).get()).exists, false);
  assert.equal((await accountDeletionTombstoneRef(user.uid).get()).data().authDeleted, true);
  assert.equal((await getAuth().getUser(other.uid)).uid, other.uid);
  assert.equal((await db.doc(`businesses/${otherBusinessId}`).get()).exists, true);
  assert.deepEqual(await resumeAccountDeletionsHandler(null, { nowMillis: now + 62_000 }), {
    completed: 0, deferred: 0, invalid: 0,
  });
});

test("lease no duplica el trabajo activo y una caída sin catch se recupera al vencer", async () => {
  const { user, businessId } = await owner();
  const now = Date.now();
  await lockAccountDeletion(user.uid, user.email, { nowMillis: now });
  const duplicate = await runAccountDeletion(user.uid, user.email, {
    nowMillis: now + 1,
    recursiveDelete: async () => assert.fail("No debe entrar al trabajo activo"),
  });
  assert.equal(duplicate.status, "PENDING");
  assert.deepEqual(duplicate.businessesDeleted, [businessId]);
  assert.deepEqual(await resumeAccountDeletionsHandler(null, { nowMillis: now + 60_000 }), {
    completed: 0, deferred: 0, invalid: 0,
  });
  assert.equal((await resumeAccountDeletionsHandler(null, {
    nowMillis: now + 6 * 60_000 + 1,
  })).completed, 1);
  assert.equal((await accountDeletionJobRef(user.uid).get()).exists, false);
});

test("ACK perdido de Auth no repite el barrido de un email reutilizado", async () => {
  const { user, businessId } = await owner();
  const { businessId: survivingBusinessId } = await owner();
  const now = Date.now();
  await assert.rejects(runAccountDeletion(user.uid, user.email, {
    nowMillis: now,
    deleteAuthUser: async (uid) => {
      await getAuth().deleteUser(uid);
      throw new Error("INJECTED_AUTH_ACK_LOSS");
    },
  }), /INJECTED_AUTH_ACK_LOSS/);
  assert.equal((await accountDeletionJobRef(user.uid).get()).data().phase, "AUTH");
  const replacement = await getAuth().createUser({ email: user.email, emailVerified: true });
  users.push(replacement);
  const newInvitation = db.doc(`businesses/${survivingBusinessId}/invitations/new-generation`);
  await newInvitation.set({ email: replacement.email, status: "PENDING" });
  const newLock = accountDeletionEmailLockRef(user.email);
  await newLock.set({
    blocksInvitations: true, deletionJobId: "another-deletion-job",
    expiresAt: Timestamp.fromMillis(now + 86_400_000),
  });
  assert.equal((await resumeAccountDeletionsHandler(null, { nowMillis: now + 61_000 })).completed, 1);
  assert.equal((await newInvitation.get()).exists, true);
  assert.equal((await newLock.get()).data().deletionJobId, "another-deletion-job");
  assert.equal((await getAuth().getUser(replacement.uid)).email, user.email);
  assert.deepEqual(await runAccountDeletion(user.uid, user.email), {
    businessesDeleted: [businessId], membershipsRemoved: 0,
  });
  assert.equal((await newInvitation.get()).exists, true);
  // Un cierre de la versión anterior no tenía authDeleted/deletionSummary. Auth ausente
  // permite migrarlo sin tocar ninguna invitación de la nueva generación del correo.
  await accountDeletionTombstoneRef(user.uid).update({
    authDeleted: FieldValue.delete(), deletionSummary: FieldValue.delete(),
  });
  await runAccountDeletion(user.uid, user.email);
  assert.equal((await newInvitation.get()).exists, true);
});

test("job con UID alterado no puede borrar otra cuenta y sale de la cola", async () => {
  const { user } = await owner();
  const { user: victim, businessId } = await owner();
  const now = Date.now();
  await lockAccountDeletion(user.uid, user.email, { nowMillis: now });
  await accountDeletionJobRef(user.uid).update({
    uid: victim.uid, nextAttemptAt: Timestamp.fromMillis(now),
  });
  const result = await resumeAccountDeletionsHandler(null, { nowMillis: now + 1 });
  assert.equal(result.invalid, 1);
  assert.equal((await getAuth().getUser(victim.uid)).uid, victim.uid);
  assert.equal((await db.doc(`businesses/${businessId}`).get()).exists, true);
  assert.equal((await accountDeletionJobRef(user.uid).get()).data().phase, "INVALID");
});

test("rechazo previo no crea un trabajo que el scheduler pudiera interpretar como autorización", async () => {
  const { user, businessId } = await owner();
  const { user: member } = await owner();
  await db.doc(`businesses/${businessId}/members/${member.uid}`).set({
    uid: member.uid, role: "READER", email: member.email,
  });
  await assert.rejects(runAccountDeletion(user.uid, user.email), /OWNED_BUSINESS_HAS_MEMBERS/);
  assert.equal((await accountDeletionJobRef(user.uid).get()).exists, false);
  assert.equal((await accountDeletionTombstoneRef(user.uid).get()).exists, false);
});


test("tombstone legacy sin negocio propio no omite la limpieza de membresías pendientes", async () => {
  const { businessId } = await owner();
  const departing = await getAuth().createUser({
    email: `legacy-${randomUUID()}@example.test`, emailVerified: true,
  });
  users.push(departing);
  const membership = db.doc(`businesses/${businessId}/members/${departing.uid}`);
  await membership.set({ uid: departing.uid, email: departing.email, role: "OPERATOR" });
  await accountDeletionTombstoneRef(departing.uid).set({
    schemaVersion: 1, blocksMutations: true, cleanupComplete: true,
    storageCleanupBusinessIds: [], retiredBusinessIds: [],
  });
  await runAccountDeletion(departing.uid, departing.email);
  assert.equal((await membership.get()).exists, false);
  assert.equal((await db.doc(`businesses/${businessId}`).get()).exists, true);
  await assert.rejects(getAuth().getUser(departing.uid), (error) => error.code === "auth/user-not-found");
});


test("respuesta pendiente requiere opt-in v2 para no falsear éxito en clientes legacy", async () => {
  const { user, businessId } = await owner();
  await lockAccountDeletion(user.uid, user.email);
  await assert.rejects(deleteMyAccountHandler(request(user)), (failure) =>
    failure.code === "aborted" && failure.message === "ACCOUNT_DELETION_PENDING");
  const pending = await deleteMyAccountHandler(request(user, { responseVersion: 2 }));
  assert.deepEqual(pending, {
    businessesDeleted: [businessId], membershipsRemoved: 0, status: "PENDING",
  });
  assert.equal((await getAuth().getUser(user.uid)).uid, user.uid);
});

test("v2 confirma aceptación durable después del fallo, pero nunca antes de guardar el job", async () => {
  const { user, businessId } = await owner();
  const pending = await deleteMyAccountHandler(request(user, { responseVersion: 2 }), {
    runDeletion: (uid, email) => runAccountDeletion(uid, email, {
      recursiveDelete: async () => { throw new Error("INJECTED_AFTER_ACCEPTANCE"); },
    }),
  });
  assert.deepEqual(pending, {
    businessesDeleted: [businessId], membershipsRemoved: 0, status: "PENDING",
  });
  const { user: neverAccepted } = await owner();
  await assert.rejects(deleteMyAccountHandler(request(neverAccepted, { responseVersion: 2 }), {
    runDeletion: async () => { throw new Error("INJECTED_BEFORE_ACCEPTANCE"); },
  }), /INJECTED_BEFORE_ACCEPTANCE/);
  await assert.rejects(deleteMyAccountHandler(request(neverAccepted, { responseVersion: "2" })),
    /ACCOUNT_DELETION_RESPONSE_VERSION/);
  assert.equal((await accountDeletionJobRef(neverAccepted.uid).get()).exists, false);
  assert.equal((await accountDeletionTombstoneRef(neverAccepted.uid).get()).exists, false);
});
