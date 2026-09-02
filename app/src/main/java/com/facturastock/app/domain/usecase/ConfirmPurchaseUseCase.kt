package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.model.PurchaseDuplicateOverride
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.observability.DisabledProductionObservability
import com.facturastock.app.domain.observability.InternalIdentifiers
import com.facturastock.app.domain.observability.OperationalAction
import com.facturastock.app.domain.observability.OperationalAuditEvent
import com.facturastock.app.domain.observability.OperationalErrorCode
import com.facturastock.app.domain.observability.OperationalOutcome
import com.facturastock.app.domain.observability.ProductionObservability
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.ConfirmPurchaseCommand
import com.facturastock.app.domain.repository.ConfirmPurchaseResult
import com.facturastock.app.domain.repository.PurchaseConfirmationBlocker
import com.facturastock.app.domain.repository.PurchaseConfirmationContext
import com.facturastock.app.domain.repository.PurchasePostingRepository
import com.facturastock.app.domain.repository.PurchaseBackupScheduler
import com.facturastock.app.domain.repository.enqueueBestEffort
import java.util.concurrent.CancellationException

/**
 * Confirma una compra preparada usando el negocio y la política vigentes al iniciar la orden.
 * El hash esperado liga la orden a la instantánea de solo lectura que el usuario revisó.
 * Tras un commit exitoso encola el drenado de la outbox: publicar nunca espera conectividad,
 * pero el respaldo pendiente se procesa en cuanto el programador lo permita.
 *
 * Tras el commit también corre el hook de ciclo de vida de imágenes
 * ([ApplyImageRetentionAfterConfirmUseCase]): purga las versiones OCR de trabajo y, para una
 * política de borrado por hito, pasa por el mantenimiento que registra primero cualquier
 * tombstone remoto necesario. Es de mejor esfuerzo y jamás cambia el resultado del commit.
 */
class ConfirmPurchaseUseCase(
    private val appConfigurationRepository: AppConfigurationRepository,
    private val purchasePostingRepository: PurchasePostingRepository,
    private val purchaseBackupScheduler: PurchaseBackupScheduler,
    private val applyImageRetentionAfterConfirm: ApplyImageRetentionAfterConfirmUseCase,
    private val observability: ProductionObservability = DisabledProductionObservability,
) {
    suspend operator fun invoke(
        draftId: DraftId,
        expectedPreparedLogicalHash: String,
        duplicateOverride: PurchaseDuplicateOverride? = null,
    ): ConfirmPurchaseResult {
        var activeBusinessId: BusinessId? = null
        return try {
            val configuration = appConfigurationRepository.current()
            activeBusinessId = configuration.activeBusinessId
            val result = activeBusinessId?.let { businessId ->
                purchasePostingRepository.confirm(
                    command = ConfirmPurchaseCommand(
                        draftId = draftId,
                        expectedPreparedLogicalHash = expectedPreparedLogicalHash,
                        duplicateOverride = duplicateOverride,
                    ),
                    context = PurchaseConfirmationContext(
                        activeBusinessId = businessId,
                        costPolicy = configuration.costPolicy,
                        backupEnabled = configuration.backupEnabled,
                        documentBackupEnabled = configuration.documentBackupEnabled,
                        imageRetentionPolicy = configuration.imageRetentionPolicy,
                    ),
                )
            } ?: ConfirmPurchaseResult.Blocked(
                setOf(PurchaseConfirmationBlocker.NoActiveBusiness),
            )
            // Posted y AlreadyPosted garantizan la fila outbox durable; el KEEP del
            // programador absorbe la duplicación entre ambos caminos.
            if (result is ConfirmPurchaseResult.Posted ||
                result is ConfirmPurchaseResult.AlreadyPosted
            ) {
                applyImageRetentionAfterConfirm(draftId, activeBusinessId)
                // El commit y su outbox ya son durables. Un fallo al escribir la base interna
                // de WorkManager no puede convertir ese éxito real en un falso error de UI.
                purchaseBackupScheduler.enqueueBestEffort()
            }
            recordConfirmation(draftId, activeBusinessId, result)
            result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            observability.record(
                OperationalAuditEvent(
                    action = OperationalAction.PURCHASE_CONFIRMATION,
                    outcome = OperationalOutcome.FAILED,
                    identifiers = InternalIdentifiers(
                        businessId = activeBusinessId,
                        draftId = draftId,
                    ),
                ),
                failure,
            )
            throw failure
        }
    }

    private suspend fun recordConfirmation(
        draftId: DraftId,
        businessId: BusinessId?,
        result: ConfirmPurchaseResult,
    ) {
        val purchaseId = when (result) {
            is ConfirmPurchaseResult.Posted -> result.purchaseId
            is ConfirmPurchaseResult.AlreadyPosted -> result.purchaseId
            is ConfirmPurchaseResult.ExactDuplicate -> result.existingPurchaseId
            else -> null
        }
        observability.record(
            OperationalAuditEvent(
                action = OperationalAction.PURCHASE_CONFIRMATION,
                outcome = when (result) {
                    is ConfirmPurchaseResult.Posted -> OperationalOutcome.SUCCEEDED
                    is ConfirmPurchaseResult.AlreadyPosted -> OperationalOutcome.ALREADY_APPLIED
                    is ConfirmPurchaseResult.ExactDuplicate,
                    ConfirmPurchaseResult.PreparedChanged,
                    -> OperationalOutcome.CONFLICT
                    is ConfirmPurchaseResult.Blocked -> OperationalOutcome.BLOCKED
                    ConfirmPurchaseResult.RetryableConflict -> OperationalOutcome.RETRY_SCHEDULED
                },
                identifiers = InternalIdentifiers(
                    businessId = businessId,
                    draftId = draftId,
                    purchaseId = purchaseId,
                ),
                errorCode = when (result) {
                    is ConfirmPurchaseResult.ExactDuplicate,
                    ConfirmPurchaseResult.PreparedChanged,
                    ConfirmPurchaseResult.RetryableConflict,
                    -> OperationalErrorCode.INTEGRITY_CONFLICT
                    is ConfirmPurchaseResult.Blocked -> if (
                        result.reasons.contains(PurchaseConfirmationBlocker.NoActiveBusiness)
                    ) {
                        OperationalErrorCode.NO_ACTIVE_BUSINESS
                    } else {
                        OperationalErrorCode.VALIDATION_FAILED
                    }
                    else -> null
                },
            ),
        )
    }
}
