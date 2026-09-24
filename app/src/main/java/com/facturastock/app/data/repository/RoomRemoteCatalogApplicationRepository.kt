package com.facturastock.app.data.repository

import com.facturastock.app.data.local.sqlite.SQLiteConstraintException
import com.facturastock.app.data.local.withTransaction
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.entity.InventoryLocationEntity
import com.facturastock.app.data.local.entity.AuditEventEntity
import com.facturastock.app.data.local.entity.CatalogSyncLinkEntity
import com.facturastock.app.data.local.entity.OutboxOperationEntity
import com.facturastock.app.data.local.entity.ProductEntity
import com.facturastock.app.data.local.entity.RemoteCatalogChangeEntity
import com.facturastock.app.data.local.entity.SupplierEntity
import com.facturastock.app.data.local.entity.UnitEntity
import com.facturastock.app.data.local.storageCatching
import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.CatalogCanonicalizer
import com.facturastock.app.domain.model.AuditEventType
import com.facturastock.app.domain.model.OutboxOperationStatus
import com.facturastock.app.domain.model.canonicalLocationName
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.CatalogApplicationOutcome
import com.facturastock.app.domain.repository.AuditPayloadKey
import com.facturastock.app.domain.repository.CatalogOutboxConflictResolution
import com.facturastock.app.domain.repository.OutboxOperationView
import com.facturastock.app.domain.repository.RemoteCatalogApplicationRepository
import com.facturastock.app.domain.repository.RemoteConflictMetadata
import java.time.Instant
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/** Aplica el espejo de catálogo a Room sin generar ecos en la outbox. */
@Singleton
class RoomRemoteCatalogApplicationRepository @Inject constructor(
    private val database: FacturaStockDatabase,
    private val uuids: UuidGenerator,
    private val dispatchers: DispatcherProvider,
) : RemoteCatalogApplicationRepository {
    override suspend fun applyPending(
        localBusinessId: BusinessId,
        cloudBusinessId: BusinessId,
        appliedAt: Instant,
    ): DomainResult<CatalogApplicationOutcome> = withContext(dispatchers.io) {
        try {
            val pending = storageCatching {
                database.remoteSyncDao().listPendingCatalogChanges(cloudBusinessId.value)
            }
            var applied = 0
            var conflicts = 0
            var blocked = 0
            pending.forEach { candidate ->
                when (applyOne(localBusinessId, cloudBusinessId, candidate, appliedAt)) {
                    ApplyResult.APPLIED -> applied++
                    ApplyResult.CONFLICT -> conflicts++
                    ApplyResult.BLOCKED -> blocked++
                    ApplyResult.ALREADY_HANDLED -> Unit
                }
            }
            DomainResult.Success(CatalogApplicationOutcome(applied, conflicts, blocked))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (storage: StorageException) {
            DomainResult.Failure(storage.error)
        } catch (_: Exception) {
            DomainResult.Failure(AccountError.Unexpected)
        }
    }

    override suspend fun resolveOutboxConflict(
        activeBusinessId: BusinessId,
        operation: OutboxOperationView,
        resolution: CatalogOutboxConflictResolution,
        actorId: String,
        resolvedAt: Instant,
    ): DomainResult<Boolean> = withContext(dispatchers.io) {
        if (
            actorId.isBlank() || operation.status != OutboxOperationStatus.CONFLICT ||
            operation.businessId != activeBusinessId ||
            operation.entityType !in setOf(PRODUCT_ENTITY_TYPE, SUPPLIER_ENTITY_TYPE)
        ) {
            return@withContext DomainResult.Success(false)
        }
        try {
            val resolved = database.withTransaction {
                val stored = database.outboxOperationDao().findByIdForBusiness(
                    operationId = operation.operationId,
                    businessId = activeBusinessId.value,
                )
                    ?: return@withTransaction false
                val remote = stored.persistedConflictRemoteEntity()
                    ?: throw CatalogResolutionStale()
                val binding = database.cloudBusinessBindingDao().findByLocal(stored.businessId)
                    ?: throw CatalogResolutionStale()
                if (
                    stored.status != OutboxOperationStatus.CONFLICT.name ||
                    stored.businessId != operation.businessId.value ||
                    stored.operationType != operation.operationType ||
                    stored.entityType != operation.entityType ||
                    stored.entityId != operation.entityId ||
                    stored.entityVersion != operation.entityVersion ||
                    stored.attemptCount != operation.attemptCount ||
                    stored.updatedAt != operation.updatedAt.toEpochMilli() ||
                    stored.targetCloudBusinessId == null ||
                    stored.targetCloudBusinessId != operation.targetCloudBusinessId?.value ||
                    stored.targetCloudBusinessId != remote.cloudBusinessId ||
                    stored.targetCloudBusinessId != binding.cloudBusinessId ||
                    operation.conflictRemoteEntity != remote
                ) {
                    // El diálogo representa otra revisión/intento. No se permite que una
                    // decisión tomada sobre v2 aplique el snapshot v3 que Room recibió después.
                    throw CatalogResolutionStale()
                }
                val cloudBusinessId = BusinessId.parse(remote.cloudBusinessId)
                    ?: throw CatalogResolutionStale()
                val localVersion = when (stored.entityType) {
                    PRODUCT_ENTITY_TYPE -> database.productDao().findById(stored.entityId)?.version
                    SUPPLIER_ENTITY_TYPE -> database.supplierDao().findById(stored.entityId)?.version
                    else -> null
                } ?: return@withTransaction false
                persistCatalogLinkOrThrow(
                    localBusinessId = stored.businessId,
                    cloudBusinessId = cloudBusinessId.value,
                    entityType = stored.entityType,
                    localEntityId = stored.entityId,
                    remoteEntityId = remote.entityId,
                    remoteVersion = remote.version,
                    linkedAt = resolvedAt.toEpochMilli(),
                )
                when (resolution) {
                    CatalogOutboxConflictResolution.APPLY_REMOTE -> {
                        if (!applyRemoteConflictSnapshot(stored, remote, resolvedAt)) {
                            throw CatalogResolutionAbort()
                        }
                        database.outboxOperationDao().resolveAllOpenCatalogOperations(
                            businessId = stored.businessId,
                            entityType = stored.entityType,
                            entityId = stored.entityId,
                            pendingStatus = OutboxOperationStatus.PENDING.name,
                            failedStatus = OutboxOperationStatus.FAILED.name,
                            conflictStatus = OutboxOperationStatus.CONFLICT.name,
                            resolvedStatus = OutboxOperationStatus.RESOLVED.name,
                            resolvedAt = resolvedAt.toEpochMilli(),
                        )
                    }
                    CatalogOutboxConflictResolution.KEEP_LOCAL -> {
                        if (!rebaseLocalConflict(stored, remote.version, resolvedAt)) {
                            throw CatalogResolutionAbort()
                        }
                    }
                }
                insertCatalogResolutionAudit(
                    operation = stored,
                    localVersion = localVersion,
                    remoteEntityId = remote.entityId,
                    remoteVersion = remote.version,
                    resolution = resolution,
                    resolvedAt = resolvedAt,
                )
                true
            }
            DomainResult.Success(resolved)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: CatalogResolutionStale) {
            DomainResult.Failure(AccountError.Conflict)
        } catch (_: CatalogResolutionAbort) {
            DomainResult.Success(false)
        } catch (storage: StorageException) {
            DomainResult.Failure(storage.error)
        } catch (_: SQLiteConstraintException) {
            DomainResult.Failure(AccountError.Conflict)
        } catch (_: CatalogReferenceConflict) {
            DomainResult.Failure(AccountError.Conflict)
        } catch (_: Exception) {
            DomainResult.Failure(AccountError.Unexpected)
        }
    }

    private suspend fun applyOne(
        localBusinessId: BusinessId,
        cloudBusinessId: BusinessId,
        candidate: RemoteCatalogChangeEntity,
        appliedAt: Instant,
    ): ApplyResult = try {
        database.withTransaction {
            val change = pendingChangeOrNull(cloudBusinessId, candidate.seq)
                ?: return@withTransaction ApplyResult.ALREADY_HANDLED
            if (isBlockedByEarlierConflict(change)) {
                return@withTransaction ApplyResult.BLOCKED
            }
            if (database.businessDao().findById(localBusinessId.value) == null) {
                return@withTransaction markConflict(
                    change = change,
                    code = CONFLICT_REFERENCE_MISSING,
                    detectedAt = appliedAt,
                )
            }
                when (change.entityType) {
                    PRODUCT_ENTITY_TYPE -> applyProduct(localBusinessId, change, appliedAt)
                    SUPPLIER_ENTITY_TYPE -> applySupplier(localBusinessId, change, appliedAt)
                    else -> markConflict(change, CONFLICT_INVALID_PAYLOAD, appliedAt)
                }
        }
    } catch (_: SQLiteConstraintException) {
        // La excepción sale de la transacción: cualquier unidad/almacén creado antes del fallo
        // se revierte. Recién después, en otra transacción, se persiste solo el conflicto.
        markRolledBackConflict(
            cloudBusinessId,
            candidate.seq,
            CONFLICT_SEMANTIC_KEY_COLLISION,
            appliedAt,
        )
    } catch (conflict: CatalogReferenceConflict) {
        markRolledBackConflict(cloudBusinessId, candidate.seq, conflict.code, appliedAt)
    }

    private suspend fun pendingChangeOrNull(
        cloudBusinessId: BusinessId,
        seq: Long,
    ): RemoteCatalogChangeEntity? = database.remoteSyncDao()
        .findCatalogChange(cloudBusinessId.value, seq)
        ?.takeIf { it.applicationStatus == STATUS_PENDING }

    private suspend fun isBlockedByEarlierConflict(change: RemoteCatalogChangeEntity): Boolean =
        database.remoteSyncDao().hasUnresolvedCatalogPredecessor(
            cloudBusinessId = change.cloudBusinessId,
            entityType = change.entityType,
            remoteEntityId = change.remoteEntityId,
            seq = change.seq,
        )

    private suspend fun markRolledBackConflict(
        cloudBusinessId: BusinessId,
        seq: Long,
        code: String,
        detectedAt: Instant,
    ): ApplyResult = database.withTransaction {
        val change = pendingChangeOrNull(cloudBusinessId, seq)
            ?: return@withTransaction ApplyResult.ALREADY_HANDLED
        markConflict(change, code, detectedAt)
    }

    private suspend fun applyProduct(
        localBusinessId: BusinessId,
        change: RemoteCatalogChangeEntity,
        appliedAt: Instant,
    ): ApplyResult {
        val snapshot = CatalogSnapshotCodec.decodeProduct(change.snapshotPayload)
            ?: return markConflict(change, CONFLICT_INVALID_PAYLOAD, appliedAt)
        val productDao = database.productDao()
        val durableLink = database.catalogSyncLinkDao().findByRemote(
            change.cloudBusinessId,
            change.entityType,
            change.remoteEntityId,
        )
        val legacyMapping = if (durableLink == null) {
            database.remoteSyncDao().findLatestCatalogMapping(
                change.cloudBusinessId,
                change.entityType,
                change.remoteEntityId,
            )
        } else {
            null
        }
        if (durableLink != null && durableLink.localBusinessId != localBusinessId.value) {
            return markConflict(change, CONFLICT_SEMANTIC_KEY_COLLISION, appliedAt)
        }
        val mappedId = durableLink?.localEntityId ?: legacyMapping?.localEntityId
        val mapped = mappedId?.let { productDao.findById(it) }
        if (mappedId != null && mapped == null) {
            return markConflict(change, CONFLICT_REFERENCE_MISSING, appliedAt)
        }
        val exact = productDao.findById(change.remoteEntityId)
        if (exact != null && exact.businessId != localBusinessId.value) {
            return markConflict(change, CONFLICT_SEMANTIC_KEY_COLLISION, appliedAt)
        }
        val bySku = snapshot.sku?.let { productDao.findBySku(localBusinessId.value, it) }
        val byBarcode = snapshot.barcode?.let {
            productDao.findByBarcode(localBusinessId.value, it)
        }
        val matches = listOfNotNull(mapped, exact, bySku, byBarcode).distinctBy { it.productId }
        if (matches.size > 1) {
            return markConflict(change, CONFLICT_SEMANTIC_KEY_COLLISION, appliedAt)
        }
        val local = matches.singleOrNull()
        if (local == null) {
            if (change.remoteVersion != 1L) {
                return markConflict(change, CONFLICT_VERSION_MISMATCH, appliedAt)
            }
            val references = resolveProductReferences(localBusinessId, snapshot, appliedAt)
            val inserted = ProductEntity(
                productId = change.remoteEntityId,
                businessId = localBusinessId.value,
                unitId = references.inventoryUnit.unitId,
                name = snapshot.name,
                createdAt = snapshot.createdAt,
                updatedAt = snapshot.updatedAt,
                locationId = references.location?.locationId,
                sku = snapshot.sku,
                barcode = snapshot.barcode,
                normalizedName = snapshot.name.trim().lowercase(Locale.ROOT),
                purchaseUnitId = references.purchaseUnit?.unitId,
                purchaseFactor = snapshot.purchaseFactor,
                salePriceMinorUnits = snapshot.salePriceMinorUnits,
                salePriceCurrencyCode = snapshot.salePriceCurrencyCode,
                status = snapshot.status,
                version = change.remoteVersion,
            )
            productDao.insert(inserted)
            return markApplied(change, inserted, references)
        }

        val localSnapshot = productSnapshot(local)
            ?: return markConflict(
                change,
                CONFLICT_REFERENCE_MISSING,
                appliedAt,
                local,
                null,
            )
        if (isAcknowledgedLocalFact(local, change)) {
            return markApplied(change, local.productId, local.version, localSnapshot)
        }
        val comparableRemoteSnapshot = CatalogSnapshotCodec.encode(
            snapshot.copy(
                salePriceMinorUnits = if (snapshot.includesSalePrice) {
                    snapshot.salePriceMinorUnits
                } else {
                    local.salePriceMinorUnits
                },
                salePriceCurrencyCode = if (snapshot.includesSalePrice) {
                    snapshot.salePriceCurrencyCode
                } else {
                    local.salePriceCurrencyCode
                },
                includesSalePrice = true,
            ),
        )
        if (local.version == change.remoteVersion && localSnapshot == comparableRemoteSnapshot) {
            return markApplied(change, local.productId, local.version, localSnapshot)
        }
        if (database.outboxOperationDao().hasOpenForEntity(
                localBusinessId.value,
                PRODUCT_ENTITY_TYPE,
                local.productId,
            )
        ) {
            return markConflict(
                change,
                CONFLICT_LOCAL_CHANGES,
                appliedAt,
                local,
                localSnapshot,
            )
        }
        if (
            local.version != change.remoteVersion - 1L ||
            snapshot.createdAt != local.createdAt ||
            snapshot.updatedAt < local.updatedAt
        ) {
            return markConflict(
                change,
                CONFLICT_VERSION_MISMATCH,
                appliedAt,
                local,
                localSnapshot,
            )
        }
        val references = resolveProductReferences(localBusinessId, snapshot, appliedAt)
        val changed = productDao.applyRemoteVersion(
            productId = local.productId,
            businessId = localBusinessId.value,
            unitId = references.inventoryUnit.unitId,
            name = snapshot.name,
            normalizedName = snapshot.name.trim().lowercase(Locale.ROOT),
            locationId = references.location?.locationId,
            sku = snapshot.sku,
            barcode = snapshot.barcode,
            purchaseUnitId = references.purchaseUnit?.unitId,
            purchaseFactor = snapshot.purchaseFactor,
            salePriceMinorUnits = if (snapshot.includesSalePrice) {
                snapshot.salePriceMinorUnits
            } else {
                local.salePriceMinorUnits
            },
            salePriceCurrencyCode = if (snapshot.includesSalePrice) {
                snapshot.salePriceCurrencyCode
            } else {
                local.salePriceCurrencyCode
            },
            status = snapshot.status,
            expectedLocalVersion = local.version,
            remoteVersion = change.remoteVersion,
            createdAt = snapshot.createdAt,
            updatedAt = snapshot.updatedAt,
        )
        if (changed != 1) {
            return markConflict(
                change,
                CONFLICT_LOCAL_CHANGES,
                appliedAt,
                local,
                localSnapshot,
            )
        }
        val stored = requireNotNull(productDao.findById(local.productId))
        return markApplied(change, stored, references)
    }

    private suspend fun applySupplier(
        localBusinessId: BusinessId,
        change: RemoteCatalogChangeEntity,
        appliedAt: Instant,
    ): ApplyResult {
        val snapshot = CatalogSnapshotCodec.decodeSupplier(change.snapshotPayload)
            ?: return markConflict(change, CONFLICT_INVALID_PAYLOAD, appliedAt)
        val supplierDao = database.supplierDao()
        val durableLink = database.catalogSyncLinkDao().findByRemote(
            change.cloudBusinessId,
            change.entityType,
            change.remoteEntityId,
        )
        val legacyMapping = if (durableLink == null) {
            database.remoteSyncDao().findLatestCatalogMapping(
                change.cloudBusinessId,
                change.entityType,
                change.remoteEntityId,
            )
        } else {
            null
        }
        if (durableLink != null && durableLink.localBusinessId != localBusinessId.value) {
            return markConflict(change, CONFLICT_SEMANTIC_KEY_COLLISION, appliedAt)
        }
        val mappedId = durableLink?.localEntityId ?: legacyMapping?.localEntityId
        val mapped = mappedId?.let { supplierDao.findById(it) }
        if (mappedId != null && mapped == null) {
            return markConflict(change, CONFLICT_REFERENCE_MISSING, appliedAt)
        }
        val exact = supplierDao.findById(change.remoteEntityId)
        if (exact != null && exact.businessId != localBusinessId.value) {
            return markConflict(change, CONFLICT_SEMANTIC_KEY_COLLISION, appliedAt)
        }
        val byRuc = snapshot.ruc?.let { supplierDao.findByRuc(localBusinessId.value, it) }
        val matches = listOfNotNull(mapped, exact, byRuc).distinctBy { it.supplierId }
        if (matches.size > 1) {
            return markConflict(change, CONFLICT_SEMANTIC_KEY_COLLISION, appliedAt)
        }
        val local = matches.singleOrNull()
        if (local == null) {
            if (change.remoteVersion != 1L) {
                return markConflict(change, CONFLICT_VERSION_MISMATCH, appliedAt)
            }
            val inserted = SupplierEntity(
                supplierId = change.remoteEntityId,
                businessId = localBusinessId.value,
                legalName = snapshot.legalName,
                createdAt = snapshot.createdAt,
                updatedAt = snapshot.updatedAt,
                ruc = snapshot.ruc,
                tradeName = snapshot.tradeName,
                status = snapshot.status,
                version = change.remoteVersion,
            )
            supplierDao.insert(inserted)
            return markApplied(
                change,
                inserted.supplierId,
                inserted.version,
                CatalogSnapshotCodec.encode(inserted.toSnapshot()),
            )
        }

        val localSnapshot = CatalogSnapshotCodec.encode(local.toSnapshot())
        if (isAcknowledgedLocalFact(local, change)) {
            return markApplied(change, local.supplierId, local.version, localSnapshot)
        }
        if (local.version == change.remoteVersion && localSnapshot == change.snapshotPayload) {
            return markApplied(change, local.supplierId, local.version, localSnapshot)
        }
        if (database.outboxOperationDao().hasOpenForEntity(
                localBusinessId.value,
                SUPPLIER_ENTITY_TYPE,
                local.supplierId,
            )
        ) {
            return markConflict(
                change,
                CONFLICT_LOCAL_CHANGES,
                appliedAt,
                local,
                localSnapshot,
            )
        }
        if (
            local.version != change.remoteVersion - 1L ||
            snapshot.createdAt != local.createdAt ||
            snapshot.updatedAt < local.updatedAt
        ) {
            return markConflict(
                change,
                CONFLICT_VERSION_MISMATCH,
                appliedAt,
                local,
                localSnapshot,
            )
        }
        val changed = supplierDao.applyRemoteVersion(
            supplierId = local.supplierId,
            businessId = localBusinessId.value,
            legalName = snapshot.legalName,
            ruc = snapshot.ruc,
            tradeName = snapshot.tradeName,
            status = snapshot.status,
            expectedLocalVersion = local.version,
            remoteVersion = change.remoteVersion,
            createdAt = snapshot.createdAt,
            updatedAt = snapshot.updatedAt,
        )
        if (changed != 1) {
            return markConflict(
                change,
                CONFLICT_LOCAL_CHANGES,
                appliedAt,
                local,
                localSnapshot,
            )
        }
        val stored = requireNotNull(supplierDao.findById(local.supplierId))
        return markApplied(
            change,
            stored.supplierId,
            stored.version,
            CatalogSnapshotCodec.encode(stored.toSnapshot()),
        )
    }

    private suspend fun applyRemoteConflictSnapshot(
        operation: OutboxOperationEntity,
        remote: RemoteConflictMetadata,
        resolvedAt: Instant,
    ): Boolean = when (operation.entityType) {
        PRODUCT_ENTITY_TYPE -> {
            val snapshot = CatalogSnapshotCodec.decodeProduct(remote.snapshotPayload)
                ?: return false
            val local = database.productDao().findById(operation.entityId) ?: return false
            if (local.businessId != operation.businessId) {
                return false
            }
            val references = resolveProductReferences(
                BusinessId.parse(operation.businessId) ?: return false,
                snapshot,
                resolvedAt,
            )
            database.productDao().applyRemoteVersion(
                productId = local.productId,
                businessId = local.businessId,
                unitId = references.inventoryUnit.unitId,
                name = snapshot.name,
                normalizedName = snapshot.name.trim().lowercase(Locale.ROOT),
                locationId = references.location?.locationId,
                sku = snapshot.sku,
                barcode = snapshot.barcode,
                purchaseUnitId = references.purchaseUnit?.unitId,
                purchaseFactor = snapshot.purchaseFactor,
                salePriceMinorUnits = if (snapshot.includesSalePrice) {
                    snapshot.salePriceMinorUnits
                } else {
                    local.salePriceMinorUnits
                },
                salePriceCurrencyCode = if (snapshot.includesSalePrice) {
                    snapshot.salePriceCurrencyCode
                } else {
                    local.salePriceCurrencyCode
                },
                status = snapshot.status,
                expectedLocalVersion = local.version,
                remoteVersion = remote.version,
                createdAt = snapshot.createdAt,
                updatedAt = snapshot.updatedAt,
            ) == 1
        }
        SUPPLIER_ENTITY_TYPE -> {
            val snapshot = CatalogSnapshotCodec.decodeSupplier(remote.snapshotPayload)
                ?: return false
            val local = database.supplierDao().findById(operation.entityId) ?: return false
            if (local.businessId != operation.businessId) {
                return false
            }
            database.supplierDao().applyRemoteVersion(
                supplierId = local.supplierId,
                businessId = local.businessId,
                legalName = snapshot.legalName,
                ruc = snapshot.ruc,
                tradeName = snapshot.tradeName,
                status = snapshot.status,
                expectedLocalVersion = local.version,
                remoteVersion = remote.version,
                createdAt = snapshot.createdAt,
                updatedAt = snapshot.updatedAt,
            ) == 1
        }
        else -> false
    }

    private suspend fun rebaseLocalConflict(
        operation: OutboxOperationEntity,
        remoteVersion: Long,
        resolvedAt: Instant,
    ): Boolean {
        if (remoteVersion == Long.MAX_VALUE) return false
        val dao = database.outboxOperationDao()
        dao.resolveCatalogPrefix(
            businessId = operation.businessId,
            entityType = operation.entityType,
            entityId = operation.entityId,
            throughVersion = remoteVersion,
            pendingStatus = OutboxOperationStatus.PENDING.name,
            failedStatus = OutboxOperationStatus.FAILED.name,
            conflictStatus = OutboxOperationStatus.CONFLICT.name,
            resolvedStatus = OutboxOperationStatus.RESOLVED.name,
            resolvedAt = resolvedAt.toEpochMilli(),
        )
        val remoteEntityId = operation.conflictRemoteEntityId ?: return false
        dao.listOpenCatalogSuccessors(
            businessId = operation.businessId,
            entityType = operation.entityType,
            entityId = operation.entityId,
            afterVersion = remoteVersion,
        ).forEach { successor ->
            val rebound = CatalogSyncOutbox.rebind(successor, remoteEntityId)
            if (
                dao.rebindCatalogOperation(
                    operationId = successor.operationId,
                    businessId = successor.businessId,
                    entityType = successor.entityType,
                    entityId = successor.entityId,
                    remoteEntityId = remoteEntityId,
                    idempotencyKey = rebound.idempotencyKey,
                    payload = rebound.payload,
                    updatedAt = resolvedAt.toEpochMilli(),
                ) != 1
            ) {
                return false
            }
        }
        val targetVersion = remoteVersion + 1L
        val successor = dao.findForEntityVersion(
            businessId = operation.businessId,
            entityType = operation.entityType,
            entityId = operation.entityId,
            entityVersion = targetVersion,
        )
        if (successor != null) {
            return when (successor.status) {
                OutboxOperationStatus.PENDING.name -> true
                OutboxOperationStatus.FAILED.name,
                OutboxOperationStatus.CONFLICT.name,
                -> dao.retryOperation(
                    operationId = successor.operationId,
                    businessId = successor.businessId,
                    entityType = successor.entityType,
                    entityId = successor.entityId,
                    failedStatus = OutboxOperationStatus.FAILED.name,
                    conflictStatus = OutboxOperationStatus.CONFLICT.name,
                    pendingStatus = OutboxOperationStatus.PENDING.name,
                    retriedAt = resolvedAt.toEpochMilli(),
                ) == 1
                else -> false
            }
        }
        return createRebasedCatalogOperation(operation, remoteVersion, resolvedAt)
    }

    private suspend fun createRebasedCatalogOperation(
        operation: OutboxOperationEntity,
        remoteVersion: Long,
        resolvedAt: Instant,
    ): Boolean {
        val targetVersion = remoteVersion + 1L
        return when (operation.entityType) {
            PRODUCT_ENTITY_TYPE -> {
                val local = database.productDao().findById(operation.entityId) ?: return false
                if (local.businessId != operation.businessId || local.version > remoteVersion) {
                    return false
                }
                val timestamp = maxOf(local.updatedAt, resolvedAt.toEpochMilli())
                if (
                    database.productDao().rebaseVersion(
                        productId = local.productId,
                        businessId = local.businessId,
                        expectedLocalVersion = local.version,
                        rebasedVersion = targetVersion,
                        updatedAt = timestamp,
                    ) != 1
                ) {
                    return false
                }
                val rebased = requireNotNull(database.productDao().findById(local.productId))
                val inventoryUnit = database.unitDao().findById(rebased.unitId) ?: return false
                val purchaseUnit = rebased.purchaseUnitId?.let {
                    database.unitDao().findById(it) ?: return false
                }
                val location = rebased.locationId?.let {
                    database.inventoryLocationDao().findById(it) ?: return false
                }
                database.outboxOperationDao().insert(
                    CatalogSyncOutbox.product(
                        entity = rebased,
                        expectedVersion = remoteVersion,
                        inventoryUnit = inventoryUnit,
                        purchaseUnit = purchaseUnit,
                        location = location,
                        remoteEntityId = operation.conflictRemoteEntityId,
                    ),
                )
                true
            }
            SUPPLIER_ENTITY_TYPE -> {
                val local = database.supplierDao().findById(operation.entityId) ?: return false
                if (local.businessId != operation.businessId || local.version > remoteVersion) {
                    return false
                }
                val timestamp = maxOf(local.updatedAt, resolvedAt.toEpochMilli())
                if (
                    database.supplierDao().rebaseVersion(
                        supplierId = local.supplierId,
                        businessId = local.businessId,
                        expectedLocalVersion = local.version,
                        rebasedVersion = targetVersion,
                        updatedAt = timestamp,
                    ) != 1
                ) {
                    return false
                }
                val rebased = requireNotNull(database.supplierDao().findById(local.supplierId))
                database.outboxOperationDao().insert(
                    CatalogSyncOutbox.supplier(
                        entity = rebased,
                        expectedVersion = remoteVersion,
                        remoteEntityId = operation.conflictRemoteEntityId,
                    ),
                )
                true
            }
            else -> false
        }
    }

    private suspend fun insertCatalogResolutionAudit(
        operation: OutboxOperationEntity,
        localVersion: Long,
        remoteEntityId: String,
        remoteVersion: Long,
        resolution: CatalogOutboxConflictResolution,
        resolvedAt: Instant,
    ) {
        database.auditEventDao().insert(
            AuditEventEntity(
                auditEventId = uuids.newUuid().toString(),
                businessId = operation.businessId,
                purchaseId = null,
                eventType = AuditEventType.CATALOG_SYNC_CONFLICT_RESOLVED.name,
                entityType = operation.entityType,
                entityId = operation.entityId,
                payload = mapOf(
                    AuditPayloadKey.VERSION to "1",
                    AuditPayloadKey.OPERATION_ID to operation.operationId,
                    AuditPayloadKey.ENTITY_TYPE to operation.entityType,
                    AuditPayloadKey.ENTITY_ID to operation.entityId,
                    AuditPayloadKey.REMOTE_ENTITY_ID to remoteEntityId,
                    AuditPayloadKey.LOCAL_VERSION to localVersion.toString(),
                    AuditPayloadKey.REMOTE_VERSION to remoteVersion.toString(),
                    AuditPayloadKey.RESOLUTION to resolution.name,
                ).toAuditPayloadJson(AuditEventType.CATALOG_SYNC_CONFLICT_RESOLVED),
                occurredAt = resolvedAt.toEpochMilli(),
            ),
        )
    }

    private suspend fun resolveProductReferences(
        businessId: BusinessId,
        snapshot: CatalogProductSnapshot,
        appliedAt: Instant,
    ): ProductReferences {
        val inventoryUnit = resolveUnit(businessId, snapshot.inventoryUnit, appliedAt)
        val purchaseUnit = snapshot.purchaseUnit?.let { unit ->
            if (unit.code == inventoryUnit.code) {
                if (!inventoryUnit.matches(unit)) {
                    throw CatalogReferenceConflict(CONFLICT_SEMANTIC_KEY_COLLISION)
                }
                inventoryUnit
            } else {
                resolveUnit(businessId, unit, appliedAt)
            }
        }
        val location = snapshot.location?.let { resolveLocation(businessId, it, appliedAt) }
        return ProductReferences(inventoryUnit, purchaseUnit, location)
    }

    private suspend fun resolveUnit(
        businessId: BusinessId,
        snapshot: CatalogUnitSnapshot,
        appliedAt: Instant,
    ): UnitEntity {
        database.unitDao().findByCode(businessId.value, snapshot.code)?.let { existing ->
            if (!existing.matches(snapshot)) {
                throw CatalogReferenceConflict(CONFLICT_SEMANTIC_KEY_COLLISION)
            }
            return existing
        }
        val timestamp = appliedAt.toEpochMilli()
        return UnitEntity(
            unitId = uuids.newUuid().toString(),
            businessId = businessId.value,
            code = CatalogCanonicalizer.unitCode(snapshot.code),
            name = snapshot.name,
            symbol = snapshot.symbol,
            status = snapshot.status,
            createdAt = timestamp,
            updatedAt = timestamp,
        ).also { database.unitDao().insert(it) }
    }

    private suspend fun resolveLocation(
        businessId: BusinessId,
        snapshot: CatalogLocationSnapshot,
        appliedAt: Instant,
    ): InventoryLocationEntity {
        val remoteKey = canonicalLocationName(snapshot.name)
        val matches = database.inventoryLocationDao().listForBusiness(businessId.value)
            .filter { canonicalLocationName(it.name) == remoteKey }
        if (matches.size > 1) throw CatalogReferenceConflict(CONFLICT_REFERENCE_AMBIGUOUS)
        matches.singleOrNull()?.let { existing ->
            if (existing.status != snapshot.status) {
                throw CatalogReferenceConflict(CONFLICT_SEMANTIC_KEY_COLLISION)
            }
            return existing
        }
        val timestamp = appliedAt.toEpochMilli()
        return InventoryLocationEntity(
            locationId = uuids.newUuid().toString(),
            businessId = businessId.value,
            name = snapshot.name.trim(),
            status = snapshot.status,
            createdAt = timestamp,
            updatedAt = timestamp,
        ).also { database.inventoryLocationDao().insert(it) }
    }

    private suspend fun productSnapshot(product: ProductEntity): String? {
        val inventoryUnit = database.unitDao().findById(product.unitId) ?: return null
        val purchaseUnit = product.purchaseUnitId?.let { database.unitDao().findById(it) ?: return null }
        val location = product.locationId?.let {
            database.inventoryLocationDao().findById(it) ?: return null
        }
        return CatalogSnapshotCodec.encode(
            product.toSnapshot(inventoryUnit, purchaseUnit, location),
        )
    }

    private suspend fun isAcknowledgedLocalFact(
        local: ProductEntity,
        change: RemoteCatalogChangeEntity,
    ): Boolean = change.remoteEntityId == local.productId &&
        database.outboxOperationDao().findForEntityVersion(
            businessId = local.businessId,
            entityType = PRODUCT_ENTITY_TYPE,
            entityId = local.productId,
            entityVersion = change.remoteVersion,
        )?.isCompletedFact(change.snapshotPayload) == true

    private suspend fun isAcknowledgedLocalFact(
        local: SupplierEntity,
        change: RemoteCatalogChangeEntity,
    ): Boolean = change.remoteEntityId == local.supplierId &&
        database.outboxOperationDao().findForEntityVersion(
            businessId = local.businessId,
            entityType = SUPPLIER_ENTITY_TYPE,
            entityId = local.supplierId,
            entityVersion = change.remoteVersion,
        )?.isCompletedFact(change.snapshotPayload) == true

    private suspend fun markApplied(
        change: RemoteCatalogChangeEntity,
        product: ProductEntity,
        references: ProductReferences,
    ): ApplyResult = markApplied(
        change,
        product.productId,
        product.version,
        CatalogSnapshotCodec.encode(
            product.toSnapshot(
                references.inventoryUnit,
                references.purchaseUnit,
                references.location,
            ),
        ),
    )

    private suspend fun markApplied(
        change: RemoteCatalogChangeEntity,
        localEntityId: String,
        localVersion: Long,
        localSnapshot: String,
    ): ApplyResult {
        val localBusinessId = when (change.entityType) {
            PRODUCT_ENTITY_TYPE -> database.productDao().findById(localEntityId)?.businessId
            SUPPLIER_ENTITY_TYPE -> database.supplierDao().findById(localEntityId)?.businessId
            else -> null
        } ?: throw CatalogReferenceConflict(CONFLICT_REFERENCE_MISSING)
        persistCatalogLinkOrThrow(
            localBusinessId = localBusinessId,
            cloudBusinessId = change.cloudBusinessId,
            entityType = change.entityType,
            localEntityId = localEntityId,
            remoteEntityId = change.remoteEntityId,
            remoteVersion = change.remoteVersion,
            linkedAt = maxOf(change.syncedAtMillis ?: 0L, change.receivedAt),
        )
        return if (
            database.remoteSyncDao().markCatalogApplied(
                cloudBusinessId = change.cloudBusinessId,
                seq = change.seq,
                localEntityId = localEntityId,
                localVersion = localVersion,
                localSnapshotPayload = localSnapshot,
            ) == 1
        ) {
            ApplyResult.APPLIED
        } else {
            ApplyResult.ALREADY_HANDLED
        }
    }

    private suspend fun persistCatalogLinkOrThrow(
        localBusinessId: String,
        cloudBusinessId: String,
        entityType: String,
        localEntityId: String,
        remoteEntityId: String,
        remoteVersion: Long,
        linkedAt: Long,
    ) {
        val dao = database.catalogSyncLinkDao()
        val byLocal = dao.findByLocal(localBusinessId, entityType, localEntityId)
        val byRemote = dao.findByRemote(cloudBusinessId, entityType, remoteEntityId)
        if (byLocal == null && byRemote == null) {
            dao.insert(
                CatalogSyncLinkEntity(
                    localBusinessId = localBusinessId,
                    cloudBusinessId = cloudBusinessId,
                    entityType = entityType,
                    localEntityId = localEntityId,
                    remoteEntityId = remoteEntityId,
                    remoteVersion = remoteVersion,
                    createdAt = linkedAt,
                    updatedAt = linkedAt,
                ),
            )
            return
        }
        val sameLocal = byLocal?.let {
            it.cloudBusinessId == cloudBusinessId && it.remoteEntityId == remoteEntityId
        } == true
        val sameRemote = byRemote == null || (
            byRemote.localBusinessId == localBusinessId &&
                byRemote.localEntityId == localEntityId
            )
        if (
            !sameLocal || !sameRemote ||
            dao.advanceVersion(
                localBusinessId = localBusinessId,
                cloudBusinessId = cloudBusinessId,
                entityType = entityType,
                localEntityId = localEntityId,
                remoteEntityId = remoteEntityId,
                remoteVersion = remoteVersion,
                updatedAt = linkedAt,
            ) != 1
        ) {
            throw CatalogReferenceConflict(CONFLICT_SEMANTIC_KEY_COLLISION)
        }
    }

    private suspend fun markConflict(
        change: RemoteCatalogChangeEntity,
        code: String,
        detectedAt: Instant,
        local: ProductEntity? = null,
        localSnapshot: String? = null,
    ): ApplyResult = persistConflict(
        change = change,
        code = code,
        detectedAt = detectedAt,
        localEntityId = local?.productId,
        localVersion = local?.version,
        localSnapshot = localSnapshot,
    )

    private suspend fun markConflict(
        change: RemoteCatalogChangeEntity,
        code: String,
        detectedAt: Instant,
        local: SupplierEntity,
        localSnapshot: String,
    ): ApplyResult = persistConflict(
        change = change,
        code = code,
        detectedAt = detectedAt,
        localEntityId = local.supplierId,
        localVersion = local.version,
        localSnapshot = localSnapshot,
    )

    private suspend fun persistConflict(
        change: RemoteCatalogChangeEntity,
        code: String,
        detectedAt: Instant,
        localEntityId: String? = null,
        localVersion: Long? = null,
        localSnapshot: String? = null,
    ): ApplyResult = if (
        database.remoteSyncDao().markCatalogConflict(
            cloudBusinessId = change.cloudBusinessId,
            seq = change.seq,
            localEntityId = localEntityId,
            localVersion = localVersion,
            localSnapshotPayload = localSnapshot,
            conflictCode = code,
            detectedAt = maxOf(detectedAt.toEpochMilli(), change.receivedAt),
        ) == 1
    ) {
        ApplyResult.CONFLICT
    } else {
        ApplyResult.ALREADY_HANDLED
    }
}

private fun OutboxOperationEntity.isCompletedFact(remoteSnapshot: String): Boolean =
    status == OutboxOperationStatus.COMPLETED.name &&
        completedAt != null && catalogSnapshotPayload() == remoteSnapshot

/** Metadata reconstruida únicamente desde la fila leída dentro de la transacción. */
private fun OutboxOperationEntity.persistedConflictRemoteEntity(): RemoteConflictMetadata? {
    val remoteEntityId = conflictRemoteEntityId ?: return null
    val remoteVersion = conflictRemoteVersion ?: return null
    val remoteSnapshot = conflictRemoteSnapshotPayload ?: return null
    val remoteOrigin = conflictRemoteOrigin ?: return null
    val cloudBusinessId = conflictCloudBusinessId ?: return null
    return RemoteConflictMetadata(
        entityId = remoteEntityId,
        version = remoteVersion,
        snapshotPayload = remoteSnapshot,
        syncedAtMillis = conflictRemoteSyncedAt,
        origin = remoteOrigin,
        cloudBusinessId = cloudBusinessId,
    )
}

private data class ProductReferences(
    val inventoryUnit: UnitEntity,
    val purchaseUnit: UnitEntity?,
    val location: InventoryLocationEntity?,
)

private fun UnitEntity.matches(snapshot: CatalogUnitSnapshot): Boolean =
    code == snapshot.code && name == snapshot.name && symbol == snapshot.symbol &&
        status == snapshot.status

private class CatalogReferenceConflict(val code: String) : IllegalArgumentException(code)
private class CatalogResolutionAbort : RuntimeException()
private class CatalogResolutionStale : RuntimeException()

private enum class ApplyResult { APPLIED, CONFLICT, BLOCKED, ALREADY_HANDLED }

private const val STATUS_PENDING = "PENDING"
private const val CONFLICT_VERSION_MISMATCH = "VERSION_MISMATCH"
private const val CONFLICT_SEMANTIC_KEY_COLLISION = "SEMANTIC_KEY_COLLISION"
private const val CONFLICT_REFERENCE_MISSING = "REFERENCE_MISSING"
private const val CONFLICT_REFERENCE_AMBIGUOUS = "REFERENCE_AMBIGUOUS"
private const val CONFLICT_LOCAL_CHANGES = "LOCAL_CHANGES"
private const val CONFLICT_INVALID_PAYLOAD = "INVALID_PAYLOAD"
