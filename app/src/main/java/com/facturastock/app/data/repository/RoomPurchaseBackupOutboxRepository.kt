package com.facturastock.app.data.repository

import com.facturastock.app.data.local.withTransaction
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.dao.AuditEventDao
import com.facturastock.app.data.local.dao.OutboxOperationDao
import com.facturastock.app.data.local.dao.OutboxOperationViewRow
import com.facturastock.app.data.local.entity.AuditEventEntity
import com.facturastock.app.data.local.entity.OutboxOperationEntity
import com.facturastock.app.data.local.storageCatching
import com.facturastock.app.domain.model.AuditEventType
import com.facturastock.app.domain.model.OutboxOperationStatus
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.repository.BackupEnvelope
import com.facturastock.app.domain.repository.AuditPayloadKey
import com.facturastock.app.domain.repository.CatalogConflictComparison
import com.facturastock.app.domain.repository.CatalogConflictSide
import com.facturastock.app.domain.repository.OutboxOperationView
import com.facturastock.app.domain.repository.PendingBackupOperation
import com.facturastock.app.domain.repository.PurchaseBackupOutboxRepository
import com.facturastock.app.domain.repository.RemoteConflictMetadata
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Adaptador Room del puerto de la outbox de respaldo. Traduce los CAS del DAO a booleanos:
 * `false` siempre significa "otro dueño o el estado cambió bajo nosotros", nunca un error.
 */
class RoomPurchaseBackupOutboxRepository @Inject constructor(
    private val database: FacturaStockDatabase,
    private val outbox: OutboxOperationDao,
    private val auditEvents: AuditEventDao,
    private val uuids: UuidGenerator,
    private val dispatchers: DispatcherProvider,
) : PurchaseBackupOutboxRepository {

    override suspend fun recoverExpiredClaims(now: Instant): Int = withContext(dispatchers.io) {
        storageCatching {
            outbox.recoverInterrupted(
                processingStatus = OutboxOperationStatus.PROCESSING.name,
                pendingStatus = OutboxOperationStatus.PENDING.name,
                recoveredAt = now.toEpochMilli(),
            )
        }
    }

    override suspend fun listReady(
        now: Instant,
        limit: Int,
        onlyDocumentPurges: Boolean,
        targetCloudBusinessId: BusinessId?,
    ): List<PendingBackupOperation> =
        withContext(dispatchers.io) {
            storageCatching {
                val rows = if (onlyDocumentPurges && targetCloudBusinessId == null) {
                    outbox.listReadyDocumentPurgesAcrossTargets(
                        pendingStatus = OutboxOperationStatus.PENDING.name,
                        completedStatus = OutboxOperationStatus.COMPLETED.name,
                        purchaseOperationType = SYNC_PURCHASE,
                        documentPurgeOperationType = SYNC_DOCUMENT_PURGE,
                        now = now.toEpochMilli(),
                        limit = limit,
                    )
                } else {
                    val target = targetCloudBusinessId
                        ?: return@storageCatching emptyList()
                    outbox.listCausallyReady(
                        pendingStatus = OutboxOperationStatus.PENDING.name,
                        targetCloudBusinessId = target.value,
                        completedStatus = OutboxOperationStatus.COMPLETED.name,
                        purchaseOperationType = SYNC_PURCHASE,
                        voidOperationType = SYNC_PURCHASE_VOID,
                        documentUploadOperationType = SYNC_DOCUMENT_UPLOAD,
                        documentPurgeOperationType = SYNC_DOCUMENT_PURGE,
                        onlyDocumentPurges = onlyDocumentPurges,
                        now = now.toEpochMilli(),
                        limit = limit,
                    )
                }
                rows.map(OutboxOperationEntity::toPendingBackupOperation)
            }
        }

    override suspend fun isPurchasePostCompleted(
        businessId: BusinessId,
        purchaseId: PurchaseId,
        targetCloudBusinessId: BusinessId,
    ): Boolean = withContext(dispatchers.io) {
        storageCatching {
            outbox.isPurchasePostCompleted(
                businessId = businessId.value,
                purchaseId = purchaseId.value,
                targetCloudBusinessId = targetCloudBusinessId.value,
                purchaseOperationType = SYNC_PURCHASE,
                completedStatus = OutboxOperationStatus.COMPLETED.name,
            )
        }
    }

    override suspend fun listReadyDocumentPurges(
        now: Instant,
        limit: Int,
        authorizedTargetCloudBusinessIds: Set<BusinessId>,
    ): List<PendingBackupOperation> = withContext(dispatchers.io) {
        storageCatching {
            if (authorizedTargetCloudBusinessIds.isEmpty()) return@storageCatching emptyList()
            outbox.listReadyDocumentPurgesForTargets(
                pendingStatus = OutboxOperationStatus.PENDING.name,
                completedStatus = OutboxOperationStatus.COMPLETED.name,
                purchaseOperationType = SYNC_PURCHASE,
                documentPurgeOperationType = SYNC_DOCUMENT_PURGE,
                authorizedTargets = authorizedTargetCloudBusinessIds.map { it.value },
                now = now.toEpochMilli(),
                limit = limit,
            ).map(OutboxOperationEntity::toPendingBackupOperation)
        }
    }

    override suspend fun claim(
        operationId: String,
        claimToken: String,
        claimedAt: Instant,
        leaseUntil: Instant,
        targetCloudBusinessId: BusinessId?,
    ): Boolean = withContext(dispatchers.io) {
        storageCatching {
            val target = targetCloudBusinessId ?: return@storageCatching false
            outbox.claim(
                operationId = operationId,
                targetCloudBusinessId = target.value,
                pendingStatus = OutboxOperationStatus.PENDING.name,
                claimedStatus = OutboxOperationStatus.PROCESSING.name,
                claimedAt = claimedAt.toEpochMilli(),
                claimToken = claimToken,
                claimLeaseUntil = leaseUntil.toEpochMilli(),
            ) == 1
        }
    }

    override suspend fun complete(
        operationId: String,
        claimToken: String,
        completedAt: Instant,
    ): Boolean = withContext(dispatchers.io) {
        storageCatching {
            database.withTransaction {
                val operation = outbox.findById(operationId) ?: return@withTransaction false
                val completed = outbox.complete(
                    operationId = operationId,
                    claimedStatus = OutboxOperationStatus.PROCESSING.name,
                    completedStatus = OutboxOperationStatus.COMPLETED.name,
                    completedAt = completedAt.toEpochMilli(),
                    claimToken = claimToken,
                ) == 1
                if (!completed) return@withTransaction false
                operation.remoteEntityId?.let { remoteEntityId ->
                    check(
                        database.catalogSyncLinkDao().advanceVersionByLocal(
                            localBusinessId = operation.businessId,
                            entityType = operation.entityType,
                            localEntityId = operation.entityId,
                            remoteEntityId = remoteEntityId,
                            remoteVersion = operation.entityVersion,
                            updatedAt = completedAt.toEpochMilli(),
                        ) == 1,
                    ) { "Falta el enlace cloud durable de la operación de catálogo" }
                }
                true
            }
        }
    }

    override suspend fun resolveSuppressed(
        operationId: String,
        claimToken: String,
        resolvedAt: Instant,
    ): Boolean = withContext(dispatchers.io) {
        storageCatching {
            outbox.resolveSuppressed(
                operationId = operationId,
                claimedStatus = OutboxOperationStatus.PROCESSING.name,
                resolvedStatus = OutboxOperationStatus.RESOLVED.name,
                resolvedAt = resolvedAt.toEpochMilli(),
                claimToken = claimToken,
            ) == 1
        }
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
    ): Boolean = withContext(dispatchers.io) {
        storageCatching {
            outbox.fail(
                operationId = operationId,
                claimedStatus = OutboxOperationStatus.PROCESSING.name,
                failedStatus = targetStatus.name,
                lastError = error,
                nextAttemptAt = nextAttemptAt?.toEpochMilli(),
                failedAt = failedAt.toEpochMilli(),
                claimToken = claimToken,
                conflictRemotePurchaseId = conflictRemotePurchaseId,
                conflictReceiptId = conflictReceiptId,
            ) == 1
        }
    }

    override suspend fun failWithRemoteConflict(
        operationId: String,
        claimToken: String,
        targetStatus: OutboxOperationStatus,
        error: String,
        nextAttemptAt: Instant?,
        failedAt: Instant,
        conflictRemotePurchaseId: String?,
        conflictReceiptId: String?,
        remoteEntity: RemoteConflictMetadata,
    ): Boolean = withContext(dispatchers.io) {
        storageCatching {
            requireNotNull(remoteEntity.cloudBusinessId) {
                "Un conflicto de catálogo debe identificar el negocio cloud validado"
            }
            outbox.fail(
                operationId = operationId,
                claimedStatus = OutboxOperationStatus.PROCESSING.name,
                failedStatus = targetStatus.name,
                lastError = error,
                nextAttemptAt = nextAttemptAt?.toEpochMilli(),
                failedAt = failedAt.toEpochMilli(),
                claimToken = claimToken,
                conflictRemotePurchaseId = conflictRemotePurchaseId,
                conflictReceiptId = conflictReceiptId,
                conflictRemoteEntityId = remoteEntity.entityId,
                conflictRemoteVersion = remoteEntity.version,
                conflictRemoteSnapshotPayload = remoteEntity.snapshotPayload,
                conflictRemoteSyncedAt = remoteEntity.syncedAtMillis,
                conflictRemoteOrigin = remoteEntity.origin,
                conflictCloudBusinessId = remoteEntity.cloudBusinessId,
            ) == 1
        }
    }

    override fun observeOutboxOperations(businessId: BusinessId): Flow<List<OutboxOperationView>> =
        outbox.observeViewsForBusiness(businessId.value)
            .map { operations -> operations.map(OutboxOperationViewRow::toOutboxOperationView) }
            .flowOn(dispatchers.io)

    /**
     * La transición CONFLICT → RESOLVED y su auditoría son una sola transacción: jamás queda
     * una operación resuelta sin el evento que explica la decisión, ni el evento sin ella.
     */
    override suspend fun resolveConflictKeepRemote(
        activeBusinessId: BusinessId,
        operationId: String,
        purchaseId: PurchaseId,
        remotePurchaseId: String?,
        remoteReceiptId: String?,
        actorId: String,
        resolvedAt: Instant,
    ): Boolean = withContext(dispatchers.io) {
        storageCatching {
            database.withTransaction {
                val operation = outbox.findByIdForBusiness(
                    operationId = operationId,
                    businessId = activeBusinessId.value,
                ) ?: return@withTransaction false
                if (operation.purchaseId != purchaseId.value) return@withTransaction false
                val resolved = outbox.resolveConflictKeepRemote(
                    operationId = operationId,
                    businessId = activeBusinessId.value,
                    conflictStatus = OutboxOperationStatus.CONFLICT.name,
                    resolvedStatus = OutboxOperationStatus.RESOLVED.name,
                    resolvedAt = resolvedAt.toEpochMilli(),
                ) == 1
                if (!resolved) return@withTransaction false
                if (operation.operationType == SYNC_PURCHASE) {
                    outbox.resolveDependentVoidsAfterKeepRemote(
                        businessId = operation.businessId,
                        purchaseId = purchaseId.value,
                        voidOperationType = SYNC_PURCHASE_VOID,
                        pendingStatus = OutboxOperationStatus.PENDING.name,
                        failedStatus = OutboxOperationStatus.FAILED.name,
                        conflictStatus = OutboxOperationStatus.CONFLICT.name,
                        resolvedStatus = OutboxOperationStatus.RESOLVED.name,
                        resolvedAt = resolvedAt.toEpochMilli(),
                    )
                }
                auditEvents.insert(
                    AuditEventEntity(
                        auditEventId = uuids.newUuid().toString(),
                        businessId = operation.businessId,
                        purchaseId = purchaseId.value,
                        eventType = AuditEventType.SYNC_CONFLICT_RESOLVED.name,
                        entityType = "purchase",
                        entityId = purchaseId.value,
                        payload = conflictResolutionPayload(
                            operationId = operationId,
                            purchaseId = purchaseId,
                            remotePurchaseId = remotePurchaseId,
                            remoteReceiptId = remoteReceiptId,
                        ),
                        occurredAt = resolvedAt.toEpochMilli(),
                    ),
                )
                true
            }
        }
    }

    override suspend fun retryOperation(
        operationId: String,
        businessId: BusinessId,
        entityType: String,
        entityId: String,
        retriedAt: Instant,
    ): Boolean = withContext(dispatchers.io) {
        storageCatching {
            outbox.retryOperation(
                operationId = operationId,
                businessId = businessId.value,
                entityType = entityType,
                entityId = entityId,
                failedStatus = OutboxOperationStatus.FAILED.name,
                conflictStatus = OutboxOperationStatus.CONFLICT.name,
                pendingStatus = OutboxOperationStatus.PENDING.name,
                retriedAt = retriedAt.toEpochMilli(),
            ) == 1
        }
    }

    override suspend fun release(
        operationId: String,
        claimToken: String,
        releasedAt: Instant,
    ): Boolean = withContext(dispatchers.io) {
        storageCatching {
            outbox.release(
                operationId = operationId,
                processingStatus = OutboxOperationStatus.PROCESSING.name,
                pendingStatus = OutboxOperationStatus.PENDING.name,
                claimToken = claimToken,
                releasedAt = releasedAt.toEpochMilli(),
            ) == 1
        }
    }

    override suspend fun findNextAttemptAt(
        now: Instant,
        targetCloudBusinessId: BusinessId?,
    ): Instant? = withContext(dispatchers.io) {
        storageCatching {
            val target = targetCloudBusinessId ?: return@storageCatching null
            outbox.findNextAttemptAt(
                pendingStatus = OutboxOperationStatus.PENDING.name,
                targetCloudBusinessId = target.value,
                now = now.toEpochMilli(),
            )?.let(Instant::ofEpochMilli)
        }
    }

    override suspend fun findNextClaimLeaseExpiry(
        now: Instant,
        targetCloudBusinessId: BusinessId?,
    ): Instant? =
        withContext(dispatchers.io) {
            storageCatching {
                val target = targetCloudBusinessId ?: return@storageCatching null
                outbox.findNextClaimLeaseExpiry(
                    processingStatus = OutboxOperationStatus.PROCESSING.name,
                    targetCloudBusinessId = target.value,
                    now = now.toEpochMilli(),
                )?.let(Instant::ofEpochMilli)
            }
        }

    override suspend fun findNextDocumentPurgeAttemptAt(now: Instant): Instant? =
        withContext(dispatchers.io) {
            storageCatching {
                outbox.findNextDocumentPurgeAttemptAt(
                    pendingStatus = OutboxOperationStatus.PENDING.name,
                    documentPurgeOperationType = SYNC_DOCUMENT_PURGE,
                    now = now.toEpochMilli(),
                )?.let(Instant::ofEpochMilli)
            }
        }

    override suspend fun findNextDocumentPurgeClaimLeaseExpiry(now: Instant): Instant? =
        withContext(dispatchers.io) {
            storageCatching {
                outbox.findNextDocumentPurgeClaimLeaseExpiry(
                    processingStatus = OutboxOperationStatus.PROCESSING.name,
                    documentPurgeOperationType = SYNC_DOCUMENT_PURGE,
                    now = now.toEpochMilli(),
                )?.let(Instant::ofEpochMilli)
            }
        }

    override suspend fun findOldestOutstandingCreatedAt(
        targetCloudBusinessId: BusinessId,
    ): Instant? =
        withContext(dispatchers.io) {
            storageCatching {
                outbox.findOldestOutstandingCreatedAt(
                    openStatuses = OPEN_OUTBOX_STATUSES,
                    targetCloudBusinessId = targetCloudBusinessId.value,
                )
                    ?.let(Instant::ofEpochMilli)
            }
        }
}

private fun OutboxOperationEntity.toPendingBackupOperation(): PendingBackupOperation {
    val envelope = BackupEnvelope(
        operationId = operationId,
        businessId = BusinessId.parse(businessId) ?: return corruptOutboxRow(),
        targetCloudBusinessId = targetCloudBusinessId?.let { raw ->
            BusinessId.parse(raw) ?: return corruptOutboxRow()
        } ?: return corruptOutboxRow(),
        purchaseId = purchaseId?.let { PurchaseId.parse(it) ?: return corruptOutboxRow() },
        idempotencyKey = idempotencyKey,
        operationType = operationType,
        payloadVersion = payloadVersion,
        payload = payload,
        entityType = entityType,
        entityId = entityId,
        entityVersion = entityVersion,
        remoteEntityId = remoteEntityId,
    )
    return PendingBackupOperation(
        envelope = envelope,
        attemptCount = attemptCount,
        createdAt = Instant.ofEpochMilli(createdAt),
    )
}

/** Room proyecta el enum persistido; cualquier nombre ajeno al catálogo falla cerrado. */
private fun OutboxOperationViewRow.toOutboxOperationView(): OutboxOperationView =
    OutboxOperationView(
        operationId = operationId,
        operationType = operationType,
        purchaseId = purchaseId?.let { PurchaseId.parse(it) ?: return corruptOutboxRow() },
        status = OutboxOperationStatus.valueOf(status),
        attemptCount = attemptCount,
        lastError = lastError,
        nextAttemptAt = nextAttemptAt?.let(Instant::ofEpochMilli),
        updatedAt = Instant.ofEpochMilli(updatedAt),
        conflictRemotePurchaseId = conflictRemotePurchaseId,
        conflictReceiptId = conflictReceiptId,
        conflictRemoteEntity = conflictRemoteEntityId?.let { remoteEntityId ->
            RemoteConflictMetadata(
                entityId = remoteEntityId,
                version = conflictRemoteVersion ?: return corruptOutboxRow(),
                snapshotPayload = conflictRemoteSnapshotPayload ?: return corruptOutboxRow(),
                syncedAtMillis = conflictRemoteSyncedAt,
                origin = conflictRemoteOrigin ?: return corruptOutboxRow(),
                cloudBusinessId = conflictCloudBusinessId ?: return corruptOutboxRow(),
            )
        },
        entityType = entityType,
        entityId = entityId,
        entityVersion = entityVersion,
        catalogConflictComparison = toCatalogConflictComparison(),
        businessId = BusinessId.parse(businessId) ?: return corruptOutboxRow(),
        targetCloudBusinessId = targetCloudBusinessId?.let { raw ->
            BusinessId.parse(raw) ?: return corruptOutboxRow()
        },
    )

private fun OutboxOperationViewRow.toCatalogConflictComparison(): CatalogConflictComparison? {
    val remoteVersion = conflictRemoteVersion ?: return null
    val remotePayload = conflictRemoteSnapshotPayload ?: return null
    val localPayload = localConflictPayload?.catalogSnapshotPayload()
    val local = localPayload?.toCatalogConflictSide(
        entityType = entityType,
        version = entityVersion,
        origin = ORIGIN_LOCAL,
    )
    val remote = remotePayload.toCatalogConflictSide(
        entityType = entityType,
        version = remoteVersion,
        origin = conflictRemoteOrigin ?: ORIGIN_CLOUD,
        changedAtOverride = conflictRemoteSyncedAt,
    )
    if (local == null && remote == null) return null
    return CatalogConflictComparison(local = local, remote = remote)
}

private fun String.toCatalogConflictSide(
    entityType: String,
    version: Long,
    origin: String,
    changedAtOverride: Long? = null,
): CatalogConflictSide? = when (entityType) {
    PRODUCT_ENTITY_TYPE -> CatalogSnapshotCodec.decodeProduct(this)?.let { snapshot ->
        CatalogConflictSide(
            displayName = snapshot.name,
            semanticIdentifier = snapshot.sku ?: snapshot.barcode,
            status = snapshot.status,
            version = version,
            changedAt = changedAtOverride?.let(Instant::ofEpochMilli)
                ?: Instant.ofEpochMilli(snapshot.updatedAt),
            origin = origin,
        )
    }
    SUPPLIER_ENTITY_TYPE -> CatalogSnapshotCodec.decodeSupplier(this)?.let { snapshot ->
        CatalogConflictSide(
            displayName = snapshot.legalName,
            semanticIdentifier = snapshot.ruc,
            status = snapshot.status,
            version = version,
            changedAt = changedAtOverride?.let(Instant::ofEpochMilli)
                ?: Instant.ofEpochMilli(snapshot.updatedAt),
            origin = origin,
        )
    }
    else -> null
}

/** Payload JSON de auditoría: solo IDs internos validados y la resolución cerrada. */
private fun conflictResolutionPayload(
    operationId: String,
    purchaseId: PurchaseId,
    remotePurchaseId: String?,
    remoteReceiptId: String?,
): String = buildMap {
    put(AuditPayloadKey.VERSION, "1")
    put(AuditPayloadKey.OPERATION_ID, operationId)
    put(AuditPayloadKey.PURCHASE_ID, purchaseId.value)
    put(AuditPayloadKey.RESOLUTION, "KEEP_REMOTE")
    remotePurchaseId?.let(PurchaseId::parse)?.let { internalId ->
        put(AuditPayloadKey.REMOTE_PURCHASE_ID, internalId.value)
    }
    remoteReceiptId?.takeIf(INTERNAL_RECEIPT_ID::matches)?.let { internalId ->
        put(AuditPayloadKey.REMOTE_RECEIPT_ID, internalId)
    }
}.toAuditPayloadJson(AuditEventType.SYNC_CONFLICT_RESOLVED)

private val INTERNAL_RECEIPT_ID = Regex("rcpt_[0-9a-f]{32}")

private const val SYNC_PURCHASE = "SYNC_PURCHASE"
private const val SYNC_PURCHASE_VOID = "SYNC_PURCHASE_VOID"
private const val SYNC_DOCUMENT_UPLOAD = "SYNC_DOCUMENT_UPLOAD"
private const val SYNC_DOCUMENT_PURGE = "SYNC_DOCUMENT_PURGE"
private val OPEN_OUTBOX_STATUSES = listOf(
    OutboxOperationStatus.PENDING.name,
    OutboxOperationStatus.PROCESSING.name,
    OutboxOperationStatus.FAILED.name,
    OutboxOperationStatus.CONFLICT.name,
)
private const val ORIGIN_LOCAL = "LOCAL"
private const val ORIGIN_CLOUD = "CLOUD"

private fun corruptOutboxRow(): Nothing = throw IllegalStateException("Outbox local inconsistente")
