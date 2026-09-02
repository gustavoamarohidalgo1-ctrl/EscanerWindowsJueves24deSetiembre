package com.facturastock.app.data.reporting

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Estado fail-closed de proceso para los SDK de observabilidad.
 *
 * Un override de Analytics habilitado sobrevive a la muerte del proceso. Por eso una instancia
 * nueva siempre parte de `false`, y [onFirebaseInitialized] reaplica esa denegación antes de que
 * [FirebaseBackupRuntime][com.facturastock.app.data.sync.FirebaseBackupRuntime] entregue la app a
 * cualquier transporte. El opt-out no inicializa Firebase solo para limpiar el override.
 */
@Singleton
class FirebaseObservabilityCollectionGate @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val gate = FailClosedFirebaseCollectionGate<FirebaseApp>(::applyToSdk)

    fun update(enabled: Boolean, firebaseApp: FirebaseApp?) {
        gate.update(enabled, firebaseApp)
    }

    fun onFirebaseInitialized(firebaseApp: FirebaseApp) {
        gate.onTargetInitialized(firebaseApp)
    }

    fun isEmissionAllowed(firebaseApp: FirebaseApp): Boolean =
        gate.isEmissionAllowed(firebaseApp)

    private fun applyToSdk(firebaseApp: FirebaseApp, enabled: Boolean) {
        check(firebaseApp.name.isNotBlank())

        // Fase 1: toda transición confirma primero el estado totalmente denegado. La fase de
        // enable no comienza si una sola revocación falla.
        val acquisitionFailures = mutableListOf<Throwable>()
        val analytics = try {
            FirebaseAnalytics.getInstance(context)
        } catch (failure: Throwable) {
            acquisitionFailures += failure
            null
        }
        val crashlytics = try {
            FirebaseCrashlytics.getInstance()
        } catch (failure: Throwable) {
            acquisitionFailures += failure
            null
        }

        fun denyAll(): List<Throwable> {
            val failures = mutableListOf<Throwable>()
            fun attempt(block: () -> Unit) {
                try {
                    block()
                } catch (failure: Throwable) {
                    failures += failure
                }
            }
            analytics?.let { sdk -> attempt { sdk.setAnalyticsCollectionEnabled(false) } }
            crashlytics?.let { sdk -> attempt { sdk.setCrashlyticsCollectionEnabled(false) } }
            analytics?.let { sdk ->
                attempt {
                    sdk.setConsent(
                        mapOf(
                            // La telemetría operacional no correlaciona instalaciones.
                            FirebaseAnalytics.ConsentType.ANALYTICS_STORAGE to
                                FirebaseAnalytics.ConsentStatus.DENIED,
                            FirebaseAnalytics.ConsentType.AD_STORAGE to
                                FirebaseAnalytics.ConsentStatus.DENIED,
                            FirebaseAnalytics.ConsentType.AD_USER_DATA to
                                FirebaseAnalytics.ConsentStatus.DENIED,
                            FirebaseAnalytics.ConsentType.AD_PERSONALIZATION to
                                FirebaseAnalytics.ConsentStatus.DENIED,
                        ),
                    )
                }
            }
            return failures
        }

        fun throwFailures(failures: List<Throwable>): Nothing {
            val first = failures.first()
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }

        if (acquisitionFailures.isNotEmpty()) {
            throwFailures(acquisitionFailures + denyAll() + denyAll())
        }

        applyFailClosedSdkTransition(
            enabled = enabled,
            deny = {
                denyAll().takeIf(List<Throwable>::isNotEmpty)?.let(::throwFailures)
            },
            enable = { requireNotNull(analytics).setAnalyticsCollectionEnabled(true) },
            cleanup = {
                // Higiene adicional después de confirmar la revocación crítica.
                try {
                    analytics?.resetAnalyticsData()
                } catch (_: Throwable) {
                    // No reabre colección ni storage.
                }
                try {
                    crashlytics?.deleteUnsentReports()
                } catch (_: Throwable) {
                    // Crashlytics ya quedó deshabilitado.
                }
            },
        )
    }
}

/** Dos fases con compensación incluso cuando un SDK aplica el side effect antes de lanzar. */
internal fun applyFailClosedSdkTransition(
    enabled: Boolean,
    deny: () -> Unit,
    enable: () -> Unit,
    cleanup: () -> Unit = {},
) {
    fun rollbackAndRethrow(failure: Throwable): Nothing {
        repeat(2) {
            try {
                deny()
                throw failure
            } catch (rollback: Throwable) {
                if (rollback === failure) throw failure
                failure.addSuppressed(rollback)
            }
        }
        throw failure
    }

    try {
        deny()
    } catch (failure: Throwable) {
        rollbackAndRethrow(failure)
    }
    if (!enabled) {
        cleanup()
        return
    }
    try {
        enable()
    } catch (failure: Throwable) {
        rollbackAndRethrow(failure)
    }
}

/** Modelo puro del reinicio: una instancia de proceso nueva siempre deniega antes de habilitar. */
internal class FailClosedFirebaseCollectionGate<T>(
    private val apply: (target: T, enabled: Boolean) -> Unit,
) {
    private val desiredEnabled = AtomicBoolean(false)
    private val stateLock = Any()

    @Volatile
    private var verifiedEnabledTarget: T? = null

    fun update(enabled: Boolean, initializedTarget: T?) {
        synchronized(stateLock) {
            verifiedEnabledTarget = null
            if (!enabled) desiredEnabled.set(false)
            if (initializedTarget == null) {
                desiredEnabled.set(enabled)
                return
            }
            try {
                apply(initializedTarget, enabled)
            } catch (failure: Throwable) {
                desiredEnabled.set(false)
                throw failure
            }
            desiredEnabled.set(enabled)
            if (enabled) verifiedEnabledTarget = initializedTarget
        }
    }

    fun onTargetInitialized(target: T) {
        synchronized(stateLock) {
            verifiedEnabledTarget = null
            val enabled = desiredEnabled.get()
            try {
                apply(target, enabled)
            } catch (failure: Throwable) {
                desiredEnabled.set(false)
                throw failure
            }
            if (enabled) verifiedEnabledTarget = target
        }
    }

    fun isEmissionAllowed(target: T): Boolean = synchronized(stateLock) {
        desiredEnabled.get() && verifiedEnabledTarget === target
    }
}
