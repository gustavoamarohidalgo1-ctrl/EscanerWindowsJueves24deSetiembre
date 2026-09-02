package com.facturastock.app.domain.usecase

import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.model.OutboxOperationStatus
import com.facturastock.app.domain.model.PurchaseBackupSnapshot
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.PurchaseBackupRepository
import com.facturastock.app.domain.repository.PurchaseBackupOutboxRepository
import com.facturastock.app.domain.repository.PurchaseBackupScheduler
import com.facturastock.app.domain.repository.OutboxOperationView
import com.facturastock.app.domain.repository.RetryPurchaseBackupResult
import com.facturastock.app.domain.repository.enqueueBestEffort
import com.facturastock.app.domain.repository.enqueuePrivacyPurgeBestEffort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

sealed interface RetryActivePurchaseBackupResult {
    data object Requeued : RetryActivePurchaseBackupResult
    data object AlreadyPending : RetryActivePurchaseBackupResult
    data object AlreadySynced : RetryActivePurchaseBackupResult
    data object NotFound : RetryActivePurchaseBackupResult
    data object NoActiveBusiness : RetryActivePurchaseBackupResult
}

class RetryPurchaseBackupUseCase(
    private val configuration: AppConfigurationRepository,
    private val backups: PurchaseBackupRepository,
    private val purchaseBackupScheduler: PurchaseBackupScheduler,
) {
    suspend operator fun invoke(purchaseId: PurchaseId): RetryActivePurchaseBackupResult {
        val businessId = configuration.current().activeBusinessId
            ?: return RetryActivePurchaseBackupResult.NoActiveBusiness
        return forBusiness(businessId, purchaseId)
    }

    /** Identidad ya capturada por una acción de UI; evita releer otro tenant durante la carrera. */
    suspend fun forBusiness(
        businessId: BusinessId,
        purchaseId: PurchaseId,
    ): RetryActivePurchaseBackupResult {
        return when (backups.retry(businessId, purchaseId)) {
            // El CAS devolvió la operación a PENDING; el drenado corre en cuanto sea posible.
            RetryPurchaseBackupResult.Requeued -> RetryActivePurchaseBackupResult.Requeued
                .also { purchaseBackupScheduler.enqueueBestEffort() }
            RetryPurchaseBackupResult.AlreadyPending -> RetryActivePurchaseBackupResult.AlreadyPending
            RetryPurchaseBackupResult.AlreadySynced -> RetryActivePurchaseBackupResult.AlreadySynced
            RetryPurchaseBackupResult.NotFound -> RetryActivePurchaseBackupResult.NotFound
        }
    }
}

sealed interface RetrySyncOperationResult {
    data object Requeued : RetrySyncOperationResult
    data object NotRetryable : RetrySyncOperationResult
    data object NotFound : RetrySyncOperationResult
}

/** Reintento genérico por operación; catálogo no depende de un purchaseId artificial. */
class RetrySyncOperationUseCase(
    private val outbox: PurchaseBackupOutboxRepository,
    private val scheduler: PurchaseBackupScheduler,
    private val clock: AppClock,
) {
    suspend operator fun invoke(
        activeBusinessId: BusinessId,
        operation: OutboxOperationView,
    ): RetrySyncOperationResult {
        if (
            operation.status != OutboxOperationStatus.FAILED ||
            operation.businessId != activeBusinessId
        ) {
            return RetrySyncOperationResult.NotRetryable
        }
        val requeued = outbox.retryOperation(
            operationId = operation.operationId,
            businessId = activeBusinessId,
            entityType = operation.entityType,
            entityId = operation.entityId,
            retriedAt = clock.now(),
        )
        return if (requeued) {
            if (operation.operationType == SYNC_DOCUMENT_PURGE) {
                // El canal ordinario está cerrado con el master OFF; una purga manual nunca
                // debe depender de que el usuario vuelva a activar su respaldo comercial.
                scheduler.enqueuePrivacyPurgeBestEffort()
            } else {
                scheduler.enqueueBestEffort()
            }
            RetrySyncOperationResult.Requeued
        } else {
            RetrySyncOperationResult.NotFound
        }
    }

    private companion object {
        const val SYNC_DOCUMENT_PURGE = "SYNC_DOCUMENT_PURGE"
    }
}

/** Observa exclusivamente la copia Room del negocio activo. */
class ObservePurchaseBackupUseCase(
    private val configuration: AppConfigurationRepository,
    private val backups: PurchaseBackupRepository,
) {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    operator fun invoke(purchaseId: PurchaseId): Flow<PurchaseBackupSnapshot?> =
        configuration.observe()
            .map { it.activeBusinessId }
            .distinctUntilChanged()
            .flatMapLatest { businessId ->
                if (businessId == null) flowOf(null) else backups.observe(businessId, purchaseId)
            }
}

class RecoverInterruptedPurchaseBackupsUseCase(
    private val backups: PurchaseBackupRepository,
) {
    suspend operator fun invoke(): Int = backups.recoverInterrupted()
}
