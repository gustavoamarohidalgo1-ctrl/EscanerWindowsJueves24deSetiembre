package com.facturastock.app.testing

import com.facturastock.app.domain.model.OutboxOperationStatus
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.repository.BackupEnvelope
import com.facturastock.app.domain.repository.OutboxOperationView
import com.facturastock.app.domain.repository.PendingBackupOperation
import com.facturastock.app.domain.repository.PurchaseBackupOutboxRepository
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Fake local de la cola durable con la misma semántica CAS que el DAO Room: el claim es
 * atómico (Mutex), completar/fallar/liberar exigen el token del intento y la recuperación
 * respeta el lease. No abre sockets ni archivos; las aserciones leen el estado por [row].
 */
class FakePurchaseBackupOutboxRepository : PurchaseBackupOutboxRepository {

    data class Row(
        val envelope: BackupEnvelope,
        val createdAt: Instant,
        var status: OutboxOperationStatus,
        var attemptCount: Int,
        var nextAttemptAt: Instant?,
        var completedAt: Instant?,
        var lastError: String?,
        var claimToken: String?,
        var claimLeaseUntil: Instant?,
        var updatedAt: Instant,
        var conflictRemotePurchaseId: String?,
        var conflictReceiptId: String?,
    )

    /** Resolución KEEP_REMOTE persistida: la decisión se audita en la misma "transacción". */
    data class ConflictResolution(
        val operationId: String,
        val purchaseId: PurchaseId,
        val remotePurchaseId: String?,
        val remoteReceiptId: String?,
        val actorId: String,
        val resolvedAt: Instant,
    )

    private val mutex = Mutex()
    private val rows = mutableListOf<Row>()
    private val operationsFlow = MutableStateFlow<List<OutboxOperationView>>(emptyList())

    /** Resoluciones cuyo CAS se ganó, en orden; los intentos rechazados no se registran. */
    val conflictResolutions = mutableListOf<ConflictResolution>()

    /** Inserta una operación PENDING lista para su primer intento. */
    fun insert(
        envelope: BackupEnvelope,
        nextAttemptAt: Instant? = null,
        createdAt: Instant = Instant.EPOCH,
    ) {
        rows += Row(
            envelope = envelope,
            createdAt = createdAt,
            status = OutboxOperationStatus.PENDING,
            attemptCount = 0,
            nextAttemptAt = nextAttemptAt,
            completedAt = null,
            lastError = null,
            claimToken = null,
            claimLeaseUntil = null,
            updatedAt = Instant.EPOCH,
            conflictRemotePurchaseId = null,
            conflictReceiptId = null,
        )
        refreshOperations()
    }

    /** Lectura de aserción; las pruebas la usan una vez terminada la concurrencia. */
    fun row(operationId: String): Row? = rows.firstOrNull { it.envelope.operationId == operationId }

    val size: Int get() = rows.size

    override suspend fun recoverExpiredClaims(now: Instant): Int = mutex.withLock {
        var recovered = 0
        rows.forEach { row ->
            val leaseExpired = row.claimLeaseUntil == null || row.claimLeaseUntil!! <= now
            if (
                row.status == OutboxOperationStatus.PROCESSING &&
                row.completedAt == null &&
                leaseExpired
            ) {
                row.status = OutboxOperationStatus.PENDING
                row.nextAttemptAt = null
                row.lastError = null
                row.claimToken = null
                row.claimLeaseUntil = null
                row.updatedAt = maxOf(row.updatedAt, now)
                recovered++
            }
        }
        if (recovered > 0) refreshOperations()
        recovered
    }

    override suspend fun listReady(
        now: Instant,
        limit: Int,
        onlyDocumentPurges: Boolean,
        targetCloudBusinessId: BusinessId?,
    ): List<PendingBackupOperation> =
        mutex.withLock {
            rows.filter { row ->
                row.status == OutboxOperationStatus.PENDING &&
                    (targetCloudBusinessId == null ||
                        row.envelope.targetCloudBusinessId == targetCloudBusinessId) &&
                    (!onlyDocumentPurges ||
                        row.envelope.operationType == "SYNC_DOCUMENT_PURGE") &&
                    row.completedAt == null &&
                    (row.nextAttemptAt == null || row.nextAttemptAt!! <= now) &&
                    isCausallyReady(row)
            }
                .sortedBy { it.nextAttemptAt ?: Instant.EPOCH }
                .take(limit)
                .map {
                    PendingBackupOperation(
                        envelope = it.envelope,
                        attemptCount = it.attemptCount,
                        createdAt = it.createdAt,
                    )
                }
        }

    override suspend fun isPurchasePostCompleted(
        businessId: BusinessId,
        purchaseId: PurchaseId,
        targetCloudBusinessId: BusinessId,
    ): Boolean = mutex.withLock {
        hasCompletedPurchasePost(businessId, purchaseId, targetCloudBusinessId)
    }

    override suspend fun claim(
        operationId: String,
        claimToken: String,
        claimedAt: Instant,
        leaseUntil: Instant,
        targetCloudBusinessId: BusinessId?,
    ): Boolean = mutex.withLock {
        val row = rows.firstOrNull { it.envelope.operationId == operationId } ?: return@withLock false
        val expectedTarget = targetCloudBusinessId ?: row.envelope.targetCloudBusinessId
        val claimable = row.status == OutboxOperationStatus.PENDING &&
            row.envelope.targetCloudBusinessId == expectedTarget &&
            row.completedAt == null &&
            (row.nextAttemptAt == null || row.nextAttemptAt!! <= claimedAt)
        if (!claimable) return@withLock false
        row.status = OutboxOperationStatus.PROCESSING
        row.attemptCount++
        row.lastError = null
        row.claimToken = claimToken
        row.claimLeaseUntil = leaseUntil
        row.updatedAt = maxOf(row.updatedAt, claimedAt)
        refreshOperations()
        true
    }

    override suspend fun complete(
        operationId: String,
        claimToken: String,
        completedAt: Instant,
    ): Boolean = mutex.withLock {
        val row = ownedClaim(operationId, claimToken) ?: return@withLock false
        row.status = OutboxOperationStatus.COMPLETED
        row.completedAt = completedAt
        row.nextAttemptAt = null
        row.lastError = null
        row.claimToken = null
        row.claimLeaseUntil = null
        row.updatedAt = maxOf(row.updatedAt, completedAt)
        refreshOperations()
        true
    }

    override suspend fun fail(
        operationId: String,
        claimToken: String,
        targetStatus: OutboxOperationStatus,
        error: String,
        nextAttemptAt: Instant?,
        failedAt: Instant,
        conflictRemotePurchaseId: String?,
        conflictReceiptId: String?,
    ): Boolean = mutex.withLock {
        val row = ownedClaim(operationId, claimToken) ?: return@withLock false
        row.status = targetStatus
        row.lastError = error
        row.nextAttemptAt = nextAttemptAt
        row.claimToken = null
        row.claimLeaseUntil = null
        row.updatedAt = maxOf(row.updatedAt, failedAt)
        row.conflictRemotePurchaseId = conflictRemotePurchaseId
        row.conflictReceiptId = conflictReceiptId
        refreshOperations()
        true
    }

    override fun observeOutboxOperations(businessId: BusinessId): Flow<List<OutboxOperationView>> =
        operationsFlow.map { operations ->
            operations.filter { operation -> operation.businessId == businessId }
        }

    /** Misma regla que Room: solo CONFLICT pasa a RESOLVED y la compra local no se toca. */
    override suspend fun resolveConflictKeepRemote(
        activeBusinessId: BusinessId,
        operationId: String,
        purchaseId: PurchaseId,
        remotePurchaseId: String?,
        remoteReceiptId: String?,
        actorId: String,
        resolvedAt: Instant,
    ): Boolean = mutex.withLock {
        val row = rows.firstOrNull { it.envelope.operationId == operationId }
            ?: return@withLock false
        if (
            row.envelope.businessId != activeBusinessId ||
            row.status != OutboxOperationStatus.CONFLICT ||
            row.completedAt != null
        ) {
            return@withLock false
        }
        conflictResolutions += ConflictResolution(
            operationId = operationId,
            purchaseId = purchaseId,
            remotePurchaseId = remotePurchaseId,
            remoteReceiptId = remoteReceiptId,
            actorId = actorId,
            resolvedAt = resolvedAt,
        )
        row.status = OutboxOperationStatus.RESOLVED
        row.completedAt = null
        row.nextAttemptAt = null
        row.claimToken = null
        row.claimLeaseUntil = null
        row.updatedAt = maxOf(row.updatedAt, resolvedAt)
        if (row.envelope.operationType == SYNC_PURCHASE) {
            rows.filter { candidate ->
                candidate.envelope.businessId == row.envelope.businessId &&
                    candidate.envelope.purchaseId == purchaseId &&
                    candidate.envelope.operationType == SYNC_PURCHASE_VOID &&
                    candidate.status in DEPENDENT_VOID_OPEN_STATUSES
            }.forEach { dependent ->
                dependent.status = OutboxOperationStatus.RESOLVED
                dependent.completedAt = null
                dependent.nextAttemptAt = null
                dependent.lastError = null
                dependent.claimToken = null
                dependent.claimLeaseUntil = null
                dependent.conflictRemotePurchaseId = null
                dependent.conflictReceiptId = null
                dependent.updatedAt = maxOf(dependent.updatedAt, resolvedAt)
            }
        }
        refreshOperations()
        true
    }

    override suspend fun retryOperation(
        operationId: String,
        businessId: BusinessId,
        entityType: String,
        entityId: String,
        retriedAt: Instant,
    ): Boolean = mutex.withLock {
        val row = rows.firstOrNull { it.envelope.operationId == operationId }
            ?: return@withLock false
        if (
            row.envelope.businessId != businessId || row.envelope.entityType != entityType ||
            row.envelope.entityId != entityId ||
            row.status !in setOf(OutboxOperationStatus.FAILED, OutboxOperationStatus.CONFLICT)
        ) {
            return@withLock false
        }
        row.status = OutboxOperationStatus.PENDING
        row.lastError = null
        row.nextAttemptAt = null
        row.updatedAt = maxOf(row.updatedAt, retriedAt)
        row.conflictRemotePurchaseId = null
        row.conflictReceiptId = null
        refreshOperations()
        true
    }

    override suspend fun release(
        operationId: String,
        claimToken: String,
        releasedAt: Instant,
    ): Boolean = mutex.withLock {
        val row = ownedClaim(operationId, claimToken) ?: return@withLock false
        if (row.attemptCount <= 0) return@withLock false
        row.status = OutboxOperationStatus.PENDING
        row.attemptCount--
        row.claimToken = null
        row.claimLeaseUntil = null
        row.updatedAt = maxOf(row.updatedAt, releasedAt)
        refreshOperations()
        true
    }

    override suspend fun resolveSuppressed(
        operationId: String,
        claimToken: String,
        resolvedAt: Instant,
    ): Boolean = mutex.withLock {
        val row = ownedClaim(operationId, claimToken) ?: return@withLock false
        row.status = OutboxOperationStatus.RESOLVED
        row.nextAttemptAt = null
        row.lastError = null
        row.claimToken = null
        row.claimLeaseUntil = null
        row.updatedAt = maxOf(row.updatedAt, resolvedAt)
        refreshOperations()
        true
    }

    override suspend fun findNextAttemptAt(
        now: Instant,
        targetCloudBusinessId: BusinessId?,
    ): Instant? = mutex.withLock {
        rows.filter { row ->
            row.status == OutboxOperationStatus.PENDING &&
                (targetCloudBusinessId == null ||
                    row.envelope.targetCloudBusinessId == targetCloudBusinessId) &&
                row.completedAt == null &&
                row.nextAttemptAt != null &&
                row.nextAttemptAt!! > now
        }.minOfOrNull { it.nextAttemptAt!! }
    }

    override suspend fun findNextClaimLeaseExpiry(
        now: Instant,
        targetCloudBusinessId: BusinessId?,
    ): Instant? = mutex.withLock {
        rows.filter { row ->
            row.status == OutboxOperationStatus.PROCESSING &&
                (targetCloudBusinessId == null ||
                    row.envelope.targetCloudBusinessId == targetCloudBusinessId) &&
                row.completedAt == null &&
                row.claimLeaseUntil != null &&
                row.claimLeaseUntil!! > now
        }.minOfOrNull { it.claimLeaseUntil!! }
    }

    override suspend fun findOldestOutstandingCreatedAt(
        targetCloudBusinessId: BusinessId,
    ): Instant? = mutex.withLock {
        rows.asSequence()
            .filter { row ->
                row.status in OPEN_STATUSES &&
                    row.completedAt == null &&
                    row.envelope.targetCloudBusinessId == targetCloudBusinessId
            }
            .minOfOrNull(Row::createdAt)
    }

    /** Fila reclamada por este intento: PROCESSING, sin completar y con este token exacto. */
    private fun ownedClaim(operationId: String, claimToken: String): Row? =
        rows.firstOrNull { row ->
            row.envelope.operationId == operationId &&
                row.status == OutboxOperationStatus.PROCESSING &&
                row.completedAt == null &&
                row.claimToken == claimToken
        }

    private fun isCausallyReady(row: Row): Boolean {
        val hasUnresolvedPredecessor = rows.any { predecessor ->
            predecessor.envelope.businessId == row.envelope.businessId &&
                predecessor.envelope.entityType == row.envelope.entityType &&
                predecessor.envelope.entityId == row.envelope.entityId &&
                predecessor.envelope.entityVersion < row.envelope.entityVersion &&
                predecessor.status != OutboxOperationStatus.COMPLETED &&
                !(row.envelope.entityType != PURCHASE_ENTITY_TYPE &&
                    predecessor.status == OutboxOperationStatus.RESOLVED)
        }
        if (hasUnresolvedPredecessor) return false
        if (row.envelope.operationType !in PURCHASE_POST_DEPENDENT_OPERATIONS) return true
        val purchaseId = row.envelope.purchaseId ?: return false
        return hasCompletedPurchasePost(
            row.envelope.businessId,
            purchaseId,
            row.envelope.targetCloudBusinessId,
        )
    }

    private fun hasCompletedPurchasePost(
        businessId: BusinessId,
        purchaseId: PurchaseId,
        targetCloudBusinessId: BusinessId,
    ): Boolean = rows.any { prerequisite ->
        prerequisite.envelope.businessId == businessId &&
            prerequisite.envelope.purchaseId == purchaseId &&
            prerequisite.envelope.targetCloudBusinessId == targetCloudBusinessId &&
            prerequisite.envelope.operationType == SYNC_PURCHASE &&
            prerequisite.status == OutboxOperationStatus.COMPLETED &&
            prerequisite.completedAt != null
    }

    private fun refreshOperations() {
        operationsFlow.value = rows.map { row ->
            OutboxOperationView(
                operationId = row.envelope.operationId,
                operationType = row.envelope.operationType,
                purchaseId = row.envelope.purchaseId,
                status = row.status,
                attemptCount = row.attemptCount,
                lastError = row.lastError,
                nextAttemptAt = row.nextAttemptAt,
                updatedAt = row.updatedAt,
                conflictRemotePurchaseId = row.conflictRemotePurchaseId,
                conflictReceiptId = row.conflictReceiptId,
                entityType = row.envelope.entityType,
                entityId = row.envelope.entityId,
                entityVersion = row.envelope.entityVersion,
                businessId = row.envelope.businessId,
                targetCloudBusinessId = row.envelope.targetCloudBusinessId,
            )
        }
    }

    private companion object {
        const val SYNC_PURCHASE = "SYNC_PURCHASE"
        const val SYNC_PURCHASE_VOID = "SYNC_PURCHASE_VOID"
        const val SYNC_DOCUMENT_UPLOAD = "SYNC_DOCUMENT_UPLOAD"
        const val SYNC_DOCUMENT_PURGE = "SYNC_DOCUMENT_PURGE"
        const val PURCHASE_ENTITY_TYPE = "PURCHASE"
        val PURCHASE_POST_DEPENDENT_OPERATIONS = setOf(
            SYNC_PURCHASE_VOID,
            SYNC_DOCUMENT_UPLOAD,
            SYNC_DOCUMENT_PURGE,
        )
        val DEPENDENT_VOID_OPEN_STATUSES = setOf(
            OutboxOperationStatus.PENDING,
            OutboxOperationStatus.FAILED,
            OutboxOperationStatus.CONFLICT,
        )
        val OPEN_STATUSES = setOf(
            OutboxOperationStatus.PENDING,
            OutboxOperationStatus.PROCESSING,
            OutboxOperationStatus.FAILED,
            OutboxOperationStatus.CONFLICT,
        )
    }
}
