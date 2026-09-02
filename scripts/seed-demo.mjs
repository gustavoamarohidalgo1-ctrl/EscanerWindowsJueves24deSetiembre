// Siembra un negocio demo y la membresía de un uid contra el Emulator Suite.
// Uso: FIRESTORE_EMULATOR_HOST=localhost:8080 node scripts/seed-demo.mjs <uid> [businessId]
import { initializeApp } from "firebase-admin/app";
import { getFirestore, FieldValue } from "firebase-admin/firestore";

const [, , uid, businessId = "11111111-1111-4111-8111-111111111111"] = process.argv;
if (!uid) {
  console.error("Falta el uid anónimo del Auth emulator: node scripts/seed-demo.mjs <uid>");
  process.exit(1);
}

process.env.GCLOUD_PROJECT ??= "demo-facturastock";
const db = getFirestore(initializeApp({ projectId: process.env.GCLOUD_PROJECT }));

await db.doc(`businesses/${businessId}`).set({ businessId, seededBy: "seed-demo" });
await db.doc(`businesses/${businessId}/members/${uid}`).set({
  // Misma forma que las membresías de los callables: `uid` habilita listMyMemberships.
  uid,
  email: null,
  role: "OWNER",
  addedAt: FieldValue.serverTimestamp(),
  addedVia: "dev",
});
console.log(`Membresía demo creada: businesses/${businessId}/members/${uid}`);
