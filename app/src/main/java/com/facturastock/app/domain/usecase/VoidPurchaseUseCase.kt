package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.observability.DisabledProductionObservability
import com.facturastock.app.domain.observability.InternalIdentifiers
import com.facturastock.app.domain.observability.OperationalAction
import com.facturastock.app.domain.observability.OperationalAuditEvent
import com.facturastock.app.domain.observability.OperationalErrorCode
import com.facturastock.app.domain.observability.OperationalOutcome
import com.facturastock.app.domain.observability.ProductionObservability
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.PreviewPurchaseVoidResult
import com.facturastock.app.domain.repository.PurchaseBackupScheduler
import com.facturastock.app.domain.repository.PurchaseOverrideAuthorizationRepository
import com.facturastock.app.domain.repository.PurchaseVoidCommand
import com.facturastock.app.domain.repository.PurchaseVoidRepository
import com.facturastock.app.domain.repository.PurchaseVoidRequest
import com.facturastock.app.domain.repository.PurchaseVoidResult
import com.facturastock.app.domain.repository.enqueueBestEffort
import java.util.concurrent.CancellationException

class PreviewPurchaseVoidUseCase(
    private val appConfigurationRepository: AppConfigurationRepository,
    private val authorizationRepository: PurchaseOverrideAuthorizationRepository,
    private val purchaseVoidRepository: PurchaseVoidRepository,
) {
    suspend operator fun invoke(purchaseId: PurchaseId): PreviewPurchaseVoidResult {
        val businessId = appConfigurationRepository.current().activeBusinessId
            ?: return PreviewPurchaseVoidResult.NoActiveBusiness
        val actor = authorizationRepository.currentActor(businessId)
            ?.takeIf { it.canVoidPurchase }
            ?: return PreviewPurchaseVoidResult.Unauthorized
        return purchaseVoidRepository.preview(businessId, purchaseId, actor)
    }
}

class VoidPurchaseUseCase(
    private val appConfigurationRepository: AppConfigurationRepository,
    private val authorizationRepository: PurchaseOverrideAuthorizationRepository,
    private val purchaseVoidRepository: PurchaseVoidRepository,
    private val purchaseBackupScheduler: PurchaseBackupScheduler,
    private val observability: ProductionObservability = DisabledProductionObservability,
) {
    suspend operator fun invoke(request: PurchaseVoidRequest): PurchaseVoidResult {
        return try {
            void(request).also { result -> recordVoid(request.purchaseId, result) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            observability.record(
                OperationalAuditEvent(
                    action = OperationalAction.PURCHASE_VOID,
                    outcome = OperationalOutcome.FAILED,
                    identifiers = InternalIdentifiers(purchaseId = request.purchaseId),
                ),
                failure,
            )
            throw failure
        }
    }

    private suspend fun void(request: PurchaseVoidRequest): PurchaseVoidResult {
        val reason = request.reason.trim()
        if (reason.length !in PurchaseVoidRequest.MIN_REASON_LENGTH..
            PurchaseVoidRequest.MAX_REASON_LENGTH
        ) {
            return PurchaseVoidResult.InvalidReason
        }
        if (!request.confirmed) return PurchaseVoidResult.ConfirmationRequired
        if (!IMPACT_HASH.matches(request.expectedImpactHash)) {
            return PurchaseVoidResult.RetryableConflict
        }
        val businessId = appConfigurationRepository.current().activeBusinessId
            ?: return PurchaseVoidResult.NoActiveBusiness
        val actor = authorizationRepository.currentActor(businessId)
            ?.takeIf { it.canVoidPurchase }
            ?: return PurchaseVoidResult.Unauthorized
        return purchaseVoidRepository.void(
            PurchaseVoidCommand(
                businessId = businessId,
                purchaseId = request.purchaseId,
                reason = reason,
                actor = actor,
                expectedImpactHash = request.expectedImpactHash,
            ),
        ).also { result ->
            // La anulación nace con su operación SYNC_PURCHASE_VOID pendiente de respaldo.
            if (result is PurchaseVoidResult.Voided) {
                // La anulación y la compensación ya hicieron commit atómico. La programación
                // se recupera después si WorkManager está temporalmente sin almacenamiento.
                purchaseBackupScheduler.enqueueBestEffort()
            }
        }
    }

    private suspend fun recordVoid(purchaseId: PurchaseId, result: PurchaseVoidResult) {
        observability.record(
            OperationalAuditEvent(
                action = OperationalAction.PURCHASE_VOID,
                outcome = when (result) {
                    is PurchaseVoidResult.Voided -> OperationalOutcome.SUCCEEDED
                    is PurchaseVoidResult.AlreadyVoided -> OperationalOutcome.ALREADY_APPLIED
                    is PurchaseVoidResult.ImpactChanged,
                    PurchaseVoidResult.RetryableConflict,
                    -> OperationalOutcome.CONFLICT
                    PurchaseVoidResult.ConfirmationRequired,
                    PurchaseVoidResult.InvalidReason,
                    PurchaseVoidResult.NoActiveBusiness,
                    PurchaseVoidResult.NotFound,
                    is PurchaseVoidResult.NotPosted,
                    PurchaseVoidResult.Unauthorized,
                    -> OperationalOutcome.BLOCKED
                },
                identifiers = InternalIdentifiers(purchaseId = purchaseId),
                errorCode = when (result) {
                    PurchaseVoidResult.InvalidReason -> OperationalErrorCode.INVALID_REASON
                    PurchaseVoidResult.NoActiveBusiness -> OperationalErrorCode.NO_ACTIVE_BUSINESS
                    PurchaseVoidResult.NotFound -> OperationalErrorCode.NOT_FOUND
                    PurchaseVoidResult.Unauthorized -> OperationalErrorCode.UNAUTHORIZED
                    is PurchaseVoidResult.ImpactChanged,
                    PurchaseVoidResult.RetryableConflict,
                    -> OperationalErrorCode.INTEGRITY_CONFLICT
                    PurchaseVoidResult.ConfirmationRequired,
                    is PurchaseVoidResult.NotPosted,
                    -> OperationalErrorCode.VALIDATION_FAILED
                    else -> null
                },
            ),
        )
    }

    private companion object {
        val IMPACT_HASH = Regex("[0-9a-f]{64}")
    }
}
