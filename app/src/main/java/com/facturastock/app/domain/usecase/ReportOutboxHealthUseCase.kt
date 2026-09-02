package com.facturastock.app.domain.usecase

import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.observability.OperationalAgeBucket
import com.facturastock.app.domain.observability.OperationalAction
import com.facturastock.app.domain.observability.OperationalAuditEvent
import com.facturastock.app.domain.observability.OperationalOutcome
import com.facturastock.app.domain.observability.ProductionObservability
import com.facturastock.app.domain.repository.CloudBusinessBindingRepository
import com.facturastock.app.domain.repository.PurchaseBackupOutboxRepository

/**
 * Toma una sola muestra de salud de la outbox del binding activo. No materializa payload ni
 * cuenta filas: emite únicamente si la cola está vacía y, si no, su rango de antigüedad.
 */
class ReportOutboxHealthUseCase(
    private val outbox: PurchaseBackupOutboxRepository,
    private val cloudBusinessBindings: CloudBusinessBindingRepository,
    private val clock: AppClock,
    private val observability: ProductionObservability,
) {
    suspend operator fun invoke(configuration: AppConfiguration) {
        // Evita abrir bindings/outbox cuando la persona no autorizó diagnósticos. La capa de
        // observabilidad vuelve a verificar el consentimiento antes de emitir (defensa en
        // profundidad), pero esta salida temprana también elimina trabajo de arranque inútil.
        if (!configuration.diagnosticsEnabled) return

        val localBusinessId = configuration.activeBusinessId
        val expectedToDrain = configuration.backupEnabled && !configuration.isDemoMode &&
            localBusinessId != null
        val target = if (expectedToDrain) {
            cloudBusinessBindings.targetFor(requireNotNull(localBusinessId))
        } else {
            null
        }
        val oldestCreatedAt = target?.let { outbox.findOldestOutstandingCreatedAt(it) }
        val ageBucket = oldestCreatedAt?.let { createdAt ->
            operationalAgeBucket(now = clock.now(), createdAt = createdAt)
        }
        observability.record(
            OperationalAuditEvent(
                action = OperationalAction.OUTBOX_HEALTH,
                outcome = when {
                    !expectedToDrain || target == null -> OperationalOutcome.SKIPPED
                    oldestCreatedAt == null -> OperationalOutcome.SUCCEEDED
                    ageBucket == OperationalAgeBucket.UNDER_15_MINUTES -> OperationalOutcome.SUCCEEDED
                    else -> OperationalOutcome.BLOCKED
                },
                ageBucket = ageBucket,
            ),
        )
    }
}
