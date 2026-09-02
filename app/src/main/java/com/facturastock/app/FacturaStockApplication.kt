package com.facturastock.app

import android.app.Application
import androidx.work.Configuration
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.data.files.StaleImportCleanup
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.reporting.PreviousProcessExitReporter
import com.facturastock.app.data.sync.BackupSyncWorkerFactory
import com.facturastock.app.domain.observability.OperationalAction
import com.facturastock.app.domain.observability.OperationalAuditEvent
import com.facturastock.app.domain.observability.OperationalOutcome
import com.facturastock.app.domain.observability.ProductionObservability
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.PrivacyMaintenanceScheduler
import com.facturastock.app.domain.repository.PurchaseBackupScheduler
import com.facturastock.app.domain.repository.enqueueBestEffort
import com.facturastock.app.domain.repository.enqueueImmediateBestEffort
import com.facturastock.app.domain.usecase.RecoverInterruptedPurchaseBackupsUseCase
import com.facturastock.app.domain.usecase.ReportOutboxHealthUseCase
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltAndroidApp
class FacturaStockApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var staleImportCleanup: Lazy<StaleImportCleanup>

    @Inject
    lateinit var dispatcherProvider: DispatcherProvider

    @Inject
    lateinit var recoverInterruptedPurchaseBackups: Lazy<RecoverInterruptedPurchaseBackupsUseCase>

    @Inject
    lateinit var backupSyncWorkerFactory: Lazy<BackupSyncWorkerFactory>

    @Inject
    lateinit var purchaseBackupScheduler: Lazy<PurchaseBackupScheduler>

    @Inject
    lateinit var privacyMaintenanceScheduler: Lazy<PrivacyMaintenanceScheduler>

    @Inject
    lateinit var appConfigurationRepository: Lazy<AppConfigurationRepository>

    @Inject
    lateinit var productionObservability: Lazy<ProductionObservability>

    @Inject
    lateinit var previousProcessExitReporter: Lazy<PreviousProcessExitReporter>

    @Inject
    lateinit var reportOutboxHealth: Lazy<ReportOutboxHealthUseCase>

    @Inject
    lateinit var database: Lazy<FacturaStockDatabase>

    /**
     * Alcance de vida del proceso para trabajos posteriores al primer frame. Room y WorkManager
     * conservan el estado durable si el proceso termina antes de que la UI emita la señal.
     */
    private val applicationScope = CoroutineScope(SupervisorJob())
    private val deferredStartup = DeferredStartupRunOnce()
    private val deferredStartupSuppressedForProcess = AtomicBoolean(false)

    /**
     * WorkManager se inicializa de forma perezosa con esta configuración (el inicializador
     * por defecto está eliminado del manifiesto) para que el worker de respaldo reciba sus
     * dependencias por constructor vía [backupSyncWorkerFactory].
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(backupSyncWorkerFactory.get())
            .build()

    /**
     * Señal explícita de Compose una vez que el primer destino, bloqueo o error recuperable ya tuvo
     * un frame. Es idempotente entre recomposiciones y recreaciones de Activity dentro del proceso.
     */
    fun onFirstAppFrameRendered() {
        // La variante no distribuible `profile` captura únicamente el recorrido crítico hasta
        // Home. Ejecutar mantenimiento dentro de esa ventana contaminaría el layout DEX generado.
        if (
            !BuildConfig.RUN_DEFERRED_STARTUP ||
            deferredStartupSuppressedForProcess.get()
        ) {
            return
        }
        deferredStartup.run {
            applicationScope.launch(dispatcherProvider.io) {
                runDeferredStartupWork()
            }
        }
    }

    /**
     * La preparación del harness abre Home antes de medir. Solo las variantes no distribuibles
     * pueden impedir que esa apertura auxiliar programe trabajo durable para la muestra siguiente.
     */
    fun suppressDeferredStartupForHarnessPreparation() {
        check(allowsDeferredStartupHarnessSuppression(BuildConfig.BUILD_TYPE)) {
            "La supresión de arranque diferido solo está disponible en benchmark/profile"
        }
        deferredStartupSuppressedForProcess.set(true)
    }

    private suspend fun runDeferredStartupWork() {
        // Firebase permanece sin inicializar durante onCreate. Tras el primer frame solo una
        // lectura válida puede habilitar observabilidad; al inicializarse, el gate cloud aplica
        // primero su default denegado y ante cualquier fallo nunca llega a habilitarla.
        val startupConfiguration = try {
            // Reutiliza la lectura caliente que ya mantienen el bloqueo y la compuerta de
            // onboarding. Si ninguna sigue activa, DataStore reinicia una lectura recuperable.
            appConfigurationRepository.get().observe().first()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val diagnosticsEnabled = startupConfiguration?.diagnosticsEnabled == true
        productionObservability.get().syncInitialConsent(diagnosticsEnabled)
        if (diagnosticsEnabled) {
            // API 30+ solo entrega la categoria del proceso anterior. El reporter no abre
            // trazas ni descripciones y deduplica localmente antes de emitir el bucket cerrado.
            try {
                previousProcessExitReporter.get().report(productionObservability.get())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Un checkpoint local corrupto no puede impedir abrir Room ni recuperar claims.
            }
        }

        // Fuerza y observa la primera apertura real en IO, ya fuera del primer frame. No se
        // emiten nombre de archivo, version de esquema, SQL ni tiempos exactos.
        try {
            database.get().openHelper.writableDatabase
            productionObservability.get().record(
                OperationalAuditEvent(
                    action = OperationalAction.ROOM_OPEN,
                    outcome = OperationalOutcome.SUCCEEDED,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            productionObservability.get().record(
                OperationalAuditEvent(
                    action = OperationalAction.ROOM_OPEN,
                    outcome = OperationalOutcome.FAILED,
                ),
                failure,
            )
        }

        try {
            // Una sola muestra del binding activo por arranque diferido. El caso de uso sale
            // antes de tocar Room si no hay consentimiento y evita amplificar eventos en las
            // pasadas del worker.
            startupConfiguration?.let { configuration ->
                reportOutboxHealth.get().invoke(configuration)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Telemetría best-effort: nunca bloquea recuperación ni scheduling.
        }

        // WorkManager y sus dependencias se materializan fuera del camino crítico. Sus nombres
        // únicos conservan idempotencia, y sus requests ya persistidos sobreviven a este retraso.
        privacyMaintenanceScheduler.get().enqueueBestEffort()
        privacyMaintenanceScheduler.get().enqueueImmediateBestEffort()
        runIndependentStartupRecovery(
            cleanOrphanedImports = {
                // Solo borra `import-*.tmp` huérfanos por antigüedad; nunca páginas de borrador.
                staleImportCleanup.get().cleanOrphanedImportTemps()
            },
            recoverOutboxClaims = {
                // Room conserva la outbox. Un claim PROCESSING cuyo proceso murió vuelve a
                // PENDING antes de despertar el transporte.
                recoverInterruptedPurchaseBackups.get().invoke()
            },
            enqueueRegularBackup = {
                // Sin transporte configurado es no-op; con transporte drena la outbox durable.
                purchaseBackupScheduler.get().enqueue()
            },
            enqueuePrivacyPurge = {
                // Un ENOSPC comercial no impide despertar tombstones explícitos de privacidad.
                purchaseBackupScheduler.get().enqueuePrivacyPurge()
            },
        )
    }
}

internal fun allowsDeferredStartupHarnessSuppression(buildType: String): Boolean =
    buildType == "benchmark" || buildType == "profile"

/** Ejecuta el bloque exactamente una vez aunque varias composiciones señalen el mismo proceso. */
internal class DeferredStartupRunOnce {
    private val started = AtomicBoolean(false)

    fun run(block: () -> Unit): Boolean {
        if (!started.compareAndSet(false, true)) return false
        block()
        return true
    }
}
