package com.facturastock.app.data.repository

import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.data.local.dao.OutboxOperationDao
import com.facturastock.app.data.local.entity.OutboxOperationEntity
import com.facturastock.app.data.local.storageCatching
import com.facturastock.app.domain.model.OutboxOperationStatus
import com.facturastock.app.domain.model.PurchaseBackupSnapshot
import com.facturastock.app.domain.model.PurchaseSyncState
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.repository.PurchaseBackupRepository
import com.facturastock.app.domain.repository.RetryPurchaseBackupResult
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class RoomPurchaseBackupRepository @Inject constructor(
    private val outbox: OutboxOperationDao,
    private val clock: AppClock,
    private val dispatchers: DispatcherProvider,
) : PurchaseBackupRepository {
    override fun observe(
        businessId: BusinessId,
        purchaseId: PurchaseId,
    ): Flow<PurchaseBackupSnapshot?> = outbox
        .observeLatestForPurchase(businessId.value, purchaseId.value)
        .map { operation -> operation?.toBackupSnapshot() }
        .flowOn(dispatchers.io)

    override suspend fun retry(
        businessId: BusinessId,
        purchaseId: PurchaseId,
    ): RetryPurchaseBackupResult = withContext(dispatchers.io) {
        storageCatching {
            val target = outbox.findRetryTargetForPurchase(
                businessId = businessId.value,
                purchaseId = purchaseId.value,
                purchaseOperationType = SYNC_PURCHASE,
                failedStatus = OutboxOperationStatus.FAILED.name,
                conflictStatus = OutboxOperationStatus.CONFLICT.name,
                completedStatus = OutboxOperationStatus.COMPLETED.name,
            )
                ?: return@storageCatching RetryPurchaseBackupResult.NotFound
            when (target.status) {
                OutboxOperationStatus.COMPLETED.name -> RetryPurchaseBackupResult.AlreadySynced
                OutboxOperationStatus.PENDING.name,
                OutboxOperationStatus.PROCESSING.name,
                -> RetryPurchaseBackupResult.AlreadyPending
                OutboxOperationStatus.FAILED.name,
                OutboxOperationStatus.CONFLICT.name,
                -> if (
                    outbox.retryFailedOrConflicted(
                        operationId = target.operationId,
                        businessId = businessId.value,
                        purchaseId = purchaseId.value,
                        failedStatus = OutboxOperationStatus.FAILED.name,
                        conflictStatus = OutboxOperationStatus.CONFLICT.name,
                        pendingStatus = OutboxOperationStatus.PENDING.name,
                        retriedAt = clock.now().toEpochMilli(),
                    ) == 1
                ) {
                    RetryPurchaseBackupResult.Requeued
                } else {
                    // Otra llamada ganó el CAS. Releer evita presentar un falso error.
                    when (
                        outbox.findRetryTargetForPurchase(
                            businessId = businessId.value,
                            purchaseId = purchaseId.value,
                            purchaseOperationType = SYNC_PURCHASE,
                            failedStatus = OutboxOperationStatus.FAILED.name,
                            conflictStatus = OutboxOperationStatus.CONFLICT.name,
                            completedStatus = OutboxOperationStatus.COMPLETED.name,
                        )?.status
                    ) {
                        OutboxOperationStatus.COMPLETED.name -> RetryPurchaseBackupResult.AlreadySynced
                        OutboxOperationStatus.PENDING.name,
                        OutboxOperationStatus.PROCESSING.name,
                        -> RetryPurchaseBackupResult.AlreadyPending
                        else -> RetryPurchaseBackupResult.NotFound
                    }
                }
                else -> RetryPurchaseBackupResult.NotFound
            }
        }
    }

    override suspend fun recoverInterrupted(): Int = withContext(dispatchers.io) {
        storageCatching {
            outbox.recoverInterrupted(
                processingStatus = OutboxOperationStatus.PROCESSING.name,
                pendingStatus = OutboxOperationStatus.PENDING.name,
                recoveredAt = clock.now().toEpochMilli(),
            )
        }
    }
}

private const val SYNC_PURCHASE = "SYNC_PURCHASE"

private fun OutboxOperationEntity.toBackupSnapshot(): PurchaseBackupSnapshot {
    val linkedPurchaseId = purchaseId?.let(PurchaseId::parse) ?: return corruptBackup()
    return PurchaseBackupSnapshot(
        purchaseId = linkedPurchaseId,
        state = when (status) {
            OutboxOperationStatus.PENDING.name -> PurchaseSyncState.PENDING_SYNC
            OutboxOperationStatus.PROCESSING.name -> PurchaseSyncState.SYNCING
            OutboxOperationStatus.COMPLETED.name -> PurchaseSyncState.SYNCED
            OutboxOperationStatus.FAILED.name -> PurchaseSyncState.ERROR
            OutboxOperationStatus.CONFLICT.name -> PurchaseSyncState.CONFLICT
            OutboxOperationStatus.RESOLVED.name -> PurchaseSyncState.RESOLVED
            else -> return corruptBackup()
        },
        attemptCount = attemptCount,
        updatedAt = Instant.ofEpochMilli(updatedAt),
        lastError = lastError,
    )
}

private fun corruptBackup(): Nothing = throw IllegalStateException("Outbox local inconsistente")
