package com.facturastock.app.startup

import androidx.room.useWriterConnection
import com.facturastock.app.core.coroutines.ApplicationScope
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.data.files.StaleImportCleanup
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.reporting.PreviousProcessExitReporter
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
import com.facturastock.app.runIndependentStartupRecovery
import dagger.Lazy
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Trabajo posterior al primer frame, igual que `FacturaStockApplication` en Android: abre Room
 * fuera del camino crítico, limpia importaciones huérfanas, recupera claims interrumpidos y
 * programa el mantenimiento de privacidad.
 */
@Singleton
class DesktopStartup @Inject constructor(
    @param:ApplicationScope private val applicationScope: CoroutineScope,
    private val dispatcherProvider: DispatcherProvider,
    private val staleImportCleanup: Lazy<StaleImportCleanup>,
    private val recoverInterruptedPurchaseBackups: Lazy<RecoverInterruptedPurchaseBackupsUseCase>,
    private val purchaseBackupScheduler: Lazy<PurchaseBackupScheduler>,
    private val privacyMaintenanceScheduler: Lazy<PrivacyMaintenanceScheduler>,
    private val appConfigurationRepository: Lazy<AppConfigurationRepository>,
    private val productionObservability: Lazy<ProductionObservability>,
    private val previousProcessExitReporter: Lazy<PreviousProcessExitReporter>,
    private val reportOutboxHealth: Lazy<ReportOutboxHealthUseCase>,
    private val database: Lazy<FacturaStockDatabase>,
) {
    private val started = AtomicBoolean(false)

    /** Idempotente: la ventana puede recomponer varias veces antes de estabilizarse. */
    fun onFirstAppFrameRendered() {
        if (!started.compareAndSet(false, true)) return
        applicationScope.launch(dispatcherProvider.io) { runDeferredStartupWork() }
    }

    private suspend fun runDeferredStartupWork() {
        val startupConfiguration = try {
            appConfigurationRepository.get().observe().first()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val diagnosticsEnabled = startupConfiguration?.diagnosticsEnabled == true
        productionObservability.get().syncInitialConsent(diagnosticsEnabled)
        if (diagnosticsEnabled) {
            try {
                previousProcessExitReporter.get().report(productionObservability.get())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Un checkpoint local corrupto no puede impedir abrir Room ni recuperar claims.
            }
        }

        try {
            database.get().useWriterConnection { }
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
            startupConfiguration?.let { configuration ->
                reportOutboxHealth.get().invoke(configuration)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Telemetría best-effort: nunca bloquea recuperación ni scheduling.
        }

        privacyMaintenanceScheduler.get().enqueueBestEffort()
        privacyMaintenanceScheduler.get().enqueueImmediateBestEffort()
        runIndependentStartupRecovery(
            cleanOrphanedImports = { staleImportCleanup.get().cleanOrphanedImportTemps() },
            recoverOutboxClaims = { recoverInterruptedPurchaseBackups.get().invoke() },
            enqueueRegularBackup = { purchaseBackupScheduler.get().enqueue() },
            enqueuePrivacyPurge = { purchaseBackupScheduler.get().enqueuePrivacyPurge() },
        )
    }
}
