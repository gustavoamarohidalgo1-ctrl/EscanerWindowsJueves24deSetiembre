// Helpers compartidos del backend FacturaStock: inicialización admin, catálogo cerrado de
// roles, guardas de autenticación/membresía y validadores usados por varios callables.
import { HttpsError } from "firebase-functions/v2/https";
import { initializeApp } from "firebase-admin/app";
import { getFirestore } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import { createHash } from "node:crypto";

initializeApp();
export const db = getFirestore();

/** Bucket administrado del proyecto; el fallback solo cubre el proyecto marcador/emuladores. */
export function storageBucket() {
  let configured = null;
  try {
    configured = JSON.parse(process.env.FIREBASE_CONFIG ?? "{}").storageBucket ?? null;
  } catch (_failure) {
    // Configuración inválida: no se filtra el contenido; el fallback del proyecto fallará cerrado.
  }
  const projectId = process.env.GCLOUD_PROJECT ?? process.env.GCP_PROJECT;
  const bucketName = configured ?? (projectId ? `${projectId}.appspot.com` : null);
  if (!bucketName) throw new HttpsError("failed-precondition", "STORAGE_NOT_CONFIGURED");
  return getStorage().bucket(bucketName);
}

export const REGION = "us-central1";

// App Check es obligatorio en cualquier runtime desplegado. El único bypass permitido es el
// proceso de Functions iniciado por Emulator Suite, que fija FUNCTIONS_EMULATOR exactamente a
// "true". No se aceptan flags de build, projectIds demo ni variables propias como bypass porque
// podrían filtrarse por error a producción.
export const isFunctionsEmulator = (environment = process.env) =>
  environment.FUNCTIONS_EMULATOR === "true";

export const callableOptionsForEnvironment = (environment = process.env) => ({
  region: REGION,
  enforceAppCheck: !isFunctionsEmulator(environment),
  // Techo de coste y fan-out para el backend pequeño. Los callables pesados reducen aún más estos
  // valores mediante opciones especializadas, sin eliminar nunca el enforcement de App Check.
  maxInstances: 20,
  concurrency: 20,
  timeoutSeconds: 60,
  memory: "256MiB",
});

export const ENFORCE_APP_CHECK = !isFunctionsEmulator();
export const CALLABLE_OPTIONS = Object.freeze(callableOptionsForEnvironment());
export const DOCUMENT_CALLABLE_OPTIONS = Object.freeze({
  ...CALLABLE_OPTIONS,
  maxInstances: 5,
  concurrency: 2,
  timeoutSeconds: 90,
  memory: "512MiB",
});
export const ACCOUNT_DELETION_CALLABLE_OPTIONS = Object.freeze({
  ...CALLABLE_OPTIONS,
  maxInstances: 3,
  concurrency: 1,
  timeoutSeconds: 300,
  memory: "512MiB",
});
export const DOCUMENT_FINALIZE_OPTIONS = Object.freeze({
  region: REGION,
  // El evento es la última defensa incluso si account deletion ya eliminó la reserva Firestore:
  // debe reintentar una purga transitoria de bytes tardíos. Las rutas malformadas terminan como
  // IGNORED y las reservas corruptas pasan a INVALID, evitando eventos venenosos permanentes.
  retry: true,
  maxInstances: 3,
  concurrency: 2,
  timeoutSeconds: 90,
  memory: "256MiB",
});
export const DOCUMENT_RECONCILIATION_SCHEDULE_OPTIONS = Object.freeze({
  region: REGION,
  schedule: "every 15 minutes",
  maxInstances: 1,
  concurrency: 1,
  timeoutSeconds: 300,
  memory: "256MiB",
  retryCount: 3,
  maxRetrySeconds: 15 * 60,
  minBackoffSeconds: 30,
  maxBackoffSeconds: 5 * 60,
  maxDoublings: 3,
});
export const UUID_REGEX = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
export const EMAIL_REGEX = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
export const MAX_EMAIL_LENGTH = 254;
export const ACCOUNT_DELETION_LOCK_FIELD = "accountDeletionLocked";
const ACCOUNT_DELETION_TOMBSTONE_NAMESPACE = "facturastock:account-deletion:v1:";
const ACCOUNT_DELETION_EMAIL_LOCK_NAMESPACE =
  "facturastock:account-deletion-email:v1:";

// Catálogo cerrado de roles de membresía (members/{uid}.role).
export const ROLES = ["OWNER", "ADMIN", "OPERATOR", "READER"];
export const OWNER_ADMIN = ["OWNER", "ADMIN"];

export const sha256 = (text) => createHash("sha256").update(text, "utf8").digest("hex");

// Tombstone de seguridad sin UID/email en campos ni ruta legible. Se conserva tras borrar Auth:
// es la frontera que impide que un JWT ya emitido recree datos. El hash usa un namespace cerrado
// y un UID Firebase aleatorio; no se expone al cliente ni se usa para analítica.
export const accountDeletionTombstoneRef = (uid) =>
  db.doc(`accountDeletionTombstones/${sha256(`${ACCOUNT_DELETION_TOMBSTONE_NAMESPACE}${uid}`)}`);

// Guarda pseudónima temporal del email durante el barrido. Serializa invitaciones concurrentes,
// pero se elimina al final para que una cuenta nueva con el mismo correo pueda volver a entrar.
// El documento nunca guarda el literal ni el UID.
export const accountDeletionEmailLockRef = (email) =>
  db.doc(
    `accountDeletionEmailLocks/${
      sha256(`${ACCOUNT_DELETION_EMAIL_LOCK_NAMESPACE}${email}`)
    }`,
  );

export const invalid = (code) => new HttpsError("invalid-argument", code);

export function requireString(value, code, maxLength) {
  if (typeof value !== "string" || value.length === 0 || value.length > maxLength) {
    throw invalid(code);
  }
  return value;
}

export function requireAuth(request) {
  if (!request.auth) throw new HttpsError("unauthenticated", "AUTH_REQUIRED");
  return request.auth;
}

/**
 * Vincula una operación iniciada por el cliente con la identidad que estaba activa al iniciarla.
 *
 * `expectedUid` es opcional solo durante la transición de clientes ya publicados. Los clientes
 * actuales lo envían en cada callable autenticado. Si la sesión cambia mientras una operación
 * espera en el dispositivo, el token de la llamada puede pertenecer a otra cuenta; esta guarda
 * rechaza esa llamada antes de cualquier lectura o escritura. No es una autorización delegable:
 * la autorización efectiva sigue saliendo de `request.auth` y de los documentos del servidor.
 */
export function requireExpectedUid(request) {
  const auth = requireAuth(request);
  const expectedUid = request.data?.expectedUid;
  if (expectedUid === undefined) return auth;
  requireString(expectedUid, "EXPECTED_UID", 128);
  if (expectedUid !== auth.uid) {
    throw new HttpsError("unauthenticated", "AUTH_IDENTITY_CHANGED");
  }
  return auth;
}

/** Operaciones irreversibles requieren un login reciente; refresh de token no renueva auth_time. */
export function requireRecentAuth(request, nowSeconds = Math.floor(Date.now() / 1000)) {
  const auth = requireAuth(request);
  const authTime = auth.token?.auth_time;
  if (!Number.isSafeInteger(nowSeconds) || !Number.isSafeInteger(authTime)) {
    throw new HttpsError("failed-precondition", "RECENT_AUTH_REQUIRED");
  }
  const ageSeconds = nowSeconds - authTime;
  if (ageSeconds < -30 || ageSeconds > 5 * 60) {
    throw new HttpsError("failed-precondition", "RECENT_AUTH_REQUIRED");
  }
  return auth;
}

// Toda cuenta debe tener el email verificado; los usuarios anónimos del emulador y las
// cuentas sin verificar no pasan esta guarda.
export function requireVerifiedEmail(request) {
  if (request.auth?.token?.email_verified !== true) {
    throw new HttpsError("permission-denied", "EMAIL_NOT_VERIFIED");
  }
}

// Devuelve el documento de membresía (incluye .data().role); NOT_A_MEMBER si no existe.
export async function requireMembership(businessId, uid) {
  const member = await db.doc(`businesses/${businessId}/members/${uid}`).get();
  if (!member.exists) throw new HttpsError("permission-denied", "NOT_A_MEMBER");
  return member;
}

// Guarda de rol del miembro: ROLE_FORBIDDEN si su rol no está entre los permitidos.
export function requireMemberRole(member, roles) {
  const role = member.data()?.role;
  if (!roles.includes(role)) throw new HttpsError("permission-denied", "ROLE_FORBIDDEN");
  return role;
}

// Atajo para los callables que solo pueden invocar OWNER/ADMIN de un negocio.
export async function requireOwnerAdmin(businessId, uid) {
  const member = await requireMembership(businessId, uid);
  return requireMemberRole(member, OWNER_ADMIN);
}

export function requireBusinessId(value) {
  if (typeof value !== "string" || !UUID_REGEX.test(value)) throw invalid("BUSINESS_ID");
  return value;
}

// Email normalizado (trim + minúsculas) con validación básica y límite de 254 caracteres.
export function normalizeEmail(value) {
  if (typeof value !== "string") throw invalid("EMAIL");
  const email = value.trim().toLowerCase();
  if (email.length === 0 || email.length > MAX_EMAIL_LENGTH || !EMAIL_REGEX.test(email)) {
    throw invalid("EMAIL");
  }
  return email;
}

// Email normalizado del token de autenticación (las cuentas verificadas siempre traen email).
export function tokenEmail(request) {
  return normalizeEmail(request.auth.token.email ?? "");
}

export function requireRoleCatalog(value) {
  if (!ROLES.includes(value)) throw invalid("ROLE");
  return value;
}

// Toda mutación de un negocio lee este documento dentro de su propia transacción. La
// eliminación de cuenta fija el lock en una transacción que vuelve a contar miembros; así una
// aceptación o escritura concurrente no puede recrear datos bajo un árbol que se está borrando.
export function requireBusinessNotDeleting(business) {
  if (!business.exists) throw new HttpsError("not-found", "BUSINESS_NOT_FOUND");
  if (business.data()?.[ACCOUNT_DELETION_LOCK_FIELD] === true) {
    throw new HttpsError("failed-precondition", "BUSINESS_DELETION_IN_PROGRESS");
  }
  return business;
}

export function requireAccountNotDeleting(tombstone) {
  if (tombstone.exists) {
    throw new HttpsError("failed-precondition", "ACCOUNT_DELETION_IN_PROGRESS");
  }
}

export function requireEmailNotDeleting(lock, nowMillis = Date.now()) {
  if (!lock.exists) return false;
  const expiresAtMillis = lock.data()?.expiresAt?.toMillis?.();
  // Una guarda antigua o malformada sigue cerrada: solo una caducidad íntegra permite reabrir.
  if (!Number.isSafeInteger(expiresAtMillis) || expiresAtMillis > nowMillis) {
    throw new HttpsError("failed-precondition", "ACCOUNT_DELETION_IN_PROGRESS");
  }
  // El caller está dentro de una transacción y debe retirar la guarda caducada antes de escribir.
  return true;
}
