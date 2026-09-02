// Controles de Auth usados exclusivamente por el E2E Android contra Emulator Suite.
import { after, test } from "node:test";
import assert from "node:assert/strict";
import { initializeApp, deleteApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { randomUUID } from "node:crypto";

process.env.FIREBASE_AUTH_EMULATOR_HOST ??= "localhost:9099";
process.env.GCLOUD_PROJECT ??= "demo-facturastock";

const PROJECT = process.env.GCLOUD_PROJECT;
const AUTH_URL = "http://localhost:9099/identitytoolkit.googleapis.com/v1";
const FUNCTIONS_URL = `http://localhost:5001/${PROJECT}/us-central1`;
const PASSWORD = "clave-demo-123";
const app = initializeApp({ projectId: PROJECT }, `dev-controls-${randomUUID()}`);
const auth = getAuth(app);

after(async () => deleteApp(app));

async function authRest(path, payload) {
  const response = await fetch(`${AUTH_URL}/${path}?key=demo-api-key`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
  const body = await response.json();
  assert.equal(response.ok, true, JSON.stringify(body));
  return body;
}

async function call(name, token, data = {}) {
  const response = await fetch(`${FUNCTIONS_URL}/${name}`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ data }),
  });
  const body = await response.json();
  assert.equal(response.ok, true, JSON.stringify(body));
  return body.result;
}

test("dev controls verify and expire only the authenticated emulator user", async () => {
  const email = `android-e2e-${randomUUID()}@example.test`;
  const created = await authRest("accounts:signUp", {
    email,
    password: PASSWORD,
    returnSecureToken: true,
  });
  assert.equal((await auth.getUser(created.localId)).emailVerified, false);

  assert.deepEqual(await call("devVerifyCurrentUser", created.idToken), { ok: true });
  assert.equal((await auth.getUser(created.localId)).emailVerified, true);

  const signedIn = await authRest("accounts:signInWithPassword", {
    email,
    password: PASSWORD,
    returnSecureToken: true,
  });
  assert.deepEqual(await call("devExpireCurrentUser", signedIn.idToken), { ok: true });
  await assert.rejects(() => auth.getUser(created.localId), /no user record/i);
});

test("dev controls reject arbitrary payload fields", async () => {
  const email = `android-e2e-shape-${randomUUID()}@example.test`;
  const created = await authRest("accounts:signUp", {
    email,
    password: PASSWORD,
    returnSecureToken: true,
  });
  const response = await fetch(`${FUNCTIONS_URL}/devVerifyCurrentUser`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${created.idToken}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ data: { email } }),
  });
  const body = await response.json();
  assert.equal(response.status, 400);
  assert.equal(body.error.status, "INVALID_ARGUMENT");
  assert.equal((await auth.getUser(created.localId)).emailVerified, false);
  await auth.deleteUser(created.localId);
});
