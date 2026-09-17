package com.facturastock.app.data.sync

import android.content.Context
import com.facturastock.app.core.config.CloudBackupConfig
import com.facturastock.app.data.reporting.FirebaseObservabilityCollectionGate
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.storage.FirebaseStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Inicialización perezosa y única de Firebase para el respaldo opcional.
 *
 * - Debug: el source set `cloudDebug` aporta un proyecto marcador contra el Emulator Suite y
 *   App Check con provider de depuración. Esos valores no existen en los fuentes release.
 * - Release: valores de `local.properties` (no versionado) vía BuildConfig; App Check con
 *   Play Integrity, exigido por los callables desplegados. Si faltan,
 *   la configuración es `null` y el transporte declara no estar disponible — el flujo local
 *   no cambia.
 *
 * Nada de esto se ejecuta si no hay configuración: Firebase no se inicializa en frío.
 */
@Singleton
class FirebaseBackupRuntime @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val appCheckInstaller: AppCheckInstaller,
    private val environment: FirebaseRuntimeEnvironment,
    private val observabilityCollectionGate: FirebaseObservabilityCollectionGate,
) {
    val config: CloudBackupConfig? = environment.config
    val backendMode: FirebaseBackendMode = environment.backendMode

    private val initLock = Any()

    @Volatile
    private var app: FirebaseApp? = null

    /** Fuerza una nueva denegación antes de volver a publicar Firebase tras un fallo del SDK. */
    @Volatile
    private var firebasePrivacyRetryRequired = false

    /** Devuelve la app lista o `null` cuando no hay configuración. */
    fun ready(): FirebaseApp? {
        val cfg = config ?: return null
        app?.let { return it }
        synchronized(initLock) {
            app?.let { return it }
            val firebaseApp = FirebaseApp.getApps(context).firstOrNull()
                ?: FirebaseApp.initializeApp(
                    context,
                    FirebaseOptions.Builder()
                        .setProjectId(cfg.projectId)
                        .setApplicationId(cfg.applicationId)
                        .setApiKey(cfg.apiKey)
                        .apply { cfg.storageBucket?.let(::setStorageBucket) }
                        .build(),
                    )
            // El default de cada proceso es false. Esto revoca cualquier override persistido de
            // Analytics antes de entregar Firebase a auth, backup o transporte.
            try {
                observabilityCollectionGate.onFirebaseInitialized(firebaseApp)
            } catch (_: Throwable) {
                quarantine(firebaseApp)
                return null
            }
            firebasePrivacyRetryRequired = false
            environment.configure(firebaseApp)
            appCheckInstaller.install(firebaseApp)
            app = firebaseApp
            return firebaseApp
        }
    }

    /**
     * Linealiza consentimiento e inicialización bajo el mismo monitor. Si un opt-out compite con
     * [ready], o bien cambia el default antes de crear la app o bien niega la instancia creada.
     */
    fun updateObservabilityCollection(enabled: Boolean) {
        synchronized(initLock) {
            // Si una revocación previa falló, un nuevo opt-out debe volver a tocar el SDK. Solo
            // este camino excepcional puede reinicializar Firebase durante un opt-out; la ruta
            // normal con app=null continúa siendo un cambio puramente local del gate.
            if (!enabled && firebasePrivacyRetryRequired && ready() == null) {
                error("Firebase privacy denial could not be retried safely")
            }
            // Actualiza el default fail-closed sin consultar ni crear una instancia Firebase.
            // `ready()` aplica el valor bajo el mismo lock antes de entregar la app a cualquier
            // consumidor.
            val currentApp = app
            try {
                observabilityCollectionGate.update(enabled, currentApp)
            } catch (failure: Throwable) {
                // Un opt-out no depende de que exista otra llamada futura. El gate ya retiró la
                // autorización antes del primer intento; repetimos una sola vez sobre la misma
                // instancia y bajo el mismo lock. Si confirma la denegación, backup sigue vivo.
                if (
                    !enabled &&
                    currentApp != null &&
                    retryFirebasePrivacyDenialOnce(failure) {
                        observabilityCollectionGate.update(enabled = false, currentApp)
                    }
                ) {
                    firebasePrivacyRetryRequired = false
                    return
                }
                quarantine(currentApp)
                throw failure
            }
            firebasePrivacyRetryRequired = false
        }
    }

    /** Linealiza el check fail-closed y la emisión con cualquier opt-out concurrente. */
    fun emitIfObservabilityEnabled(block: () -> Unit): Boolean = synchronized(initLock) {
        val currentApp = app ?: return false
        if (!observabilityCollectionGate.isEmissionAllowed(currentApp)) return false
        block()
        true
    }

    private fun quarantine(firebaseApp: FirebaseApp?) {
        firebasePrivacyRetryRequired = true
        app = null
        if (firebaseApp != null) {
            try {
                firebaseApp.delete()
            } catch (_: Throwable) {
                // La instancia nunca vuelve a publicarse aunque el SDK también falle al cerrarla.
            }
        }
    }

    /** Auth de la app inicializada; `null` sin configuración. La sesión es de cuenta, nunca anónima. */
    fun auth(): FirebaseAuth? = ready()?.let(FirebaseAuth::getInstance)

    /** Functions de la app inicializada; `null` sin configuración. */
    fun functions(): FirebaseFunctions? {
        if (backendMode != FirebaseBackendMode.CALLABLES) return null
        return ready()?.let(FirebaseFunctions::getInstance)
    }

    /** Delega cualquier preparación específica del build type sin filtrar detalles a release. */
    suspend fun prepareBusiness(functions: FirebaseFunctions, businessId: String) {
        environment.prepareBusiness(functions, businessId)
    }

    /** Firestore de la app inicializada; `null` sin configuración. */
    fun firestore(): FirebaseFirestore? = ready()?.let(FirebaseFirestore::getInstance)

    /** Storage autenticado de solo lectura; los uploads cliente permanecen prohibidos. */
    fun storage(): FirebaseStorage? {
        if (backendMode != FirebaseBackendMode.CALLABLES) return null
        if (config?.storageBucket == null) return null
        return ready()?.let(FirebaseStorage::getInstance)
    }
}

/** Reintento único que preserva la causa sin caer en self-suppression del mismo Throwable SDK. */
internal fun retryFirebasePrivacyDenialOnce(
    firstFailure: Throwable,
    retry: () -> Unit,
): Boolean = try {
    retry()
    true
} catch (retryFailure: Throwable) {
    if (retryFailure !== firstFailure) firstFailure.addSuppressed(retryFailure)
    false
}
