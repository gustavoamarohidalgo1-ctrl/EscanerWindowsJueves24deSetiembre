package com.facturastock.app.data.repository

import com.facturastock.app.data.local.sqlite.SQLiteConstraintException
import com.facturastock.app.data.local.sqlite.SQLiteException
import com.facturastock.app.data.local.sqlite.SQLiteFullException
import com.facturastock.app.data.local.withTransaction
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.PurchaseVoidIdentity
import com.facturastock.app.data.local.PurchaseVoidImpactSeal
import com.facturastock.app.data.local.dao.PurchaseVoidBalanceMutation
import com.facturastock.app.data.local.dao.PurchaseVoidBatch
import com.facturastock.app.data.local.dao.PurchaseVoidMovementCompensation
import com.facturastock.app.data.local.dao.SYNC_PURCHASE_VOID_PAYLOAD_VERSION
import com.facturastock.app.data.local.dao.purchaseVoidPayload
import com.facturastock.app.data.local.dao.purchaseVoidAuditPayload
import com.facturastock.app.data.local.entity.AuditEventEntity
import com.facturastock.app.data.local.entity.InventoryBalanceEntity
import com.facturastock.app.data.local.entity.OutboxOperationEntity
import com.facturastock.app.data.local.entity.PurchaseEntity
import com.facturastock.app.data.local.entity.StockMovementEntity
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.AuditEventType
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.OutboxOperationStatus
import com.facturastock.app.domain.model.PurchaseDocumentType
import com.facturastock.app.domain.model.PurchaseOverrideActor
import com.facturastock.app.domain.model.PurchaseStatus
import com.facturastock.app.domain.model.PurchaseVoidImpact
import com.facturastock.app.domain.model.PurchaseVoidPreview
import com.facturastock.app.domain.model.StockMovementType
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.repository.PreviewPurchaseVoidResult
import com.facturastock.app.domain.repository.PurchaseVoidCommand
import com.facturastock.app.domain.repository.PurchaseVoidRepository
import com.facturastock.app.domain.repository.PurchaseVoidResult
import java.math.BigDecimal
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/** Room es la única autoridad de la previsualización y del commit compensatorio. */
class RoomPurchaseVoidRepository @Inject constructor(
    private val database: FacturaStockDatabase,
    private val appClock: AppClock,
    private val dispatchers: DispatcherProvider,
) : PurchaseVoidRepository {
    override suspend fun preview(
        businessId: BusinessId,
        purchaseId: PurchaseId,
        actor: PurchaseOverrideActor,
    ): PreviewPurchaseVoidResult = withContext(dispatchers.io) {
        if (!actor.canVoidPurchase) return@withContext PreviewPurchaseVoidResult.Unauthorized
        try {
            database.withTransaction {
                when (val loaded = loadSnapshot(businessId, purchaseId, actor)) {
                    is SnapshotLoad.Ready -> PreviewPurchaseVoidResult.Ready(
                        loaded.snapshot.toPreview(actor),
                    )
                    is SnapshotLoad.Result -> loaded.result
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SQLiteFullException) {
            throw StorageException(StorageError.InsufficientSpace, failure)
        } catch (failure: SQLiteException) {
            throw StorageException(StorageError.Unavailable, failure)
        }
    }

    override suspend fun void(command: PurchaseVoidCommand): PurchaseVoidResult =
        withContext(dispatchers.io) {
            try {
                database.withTransaction { voidInTransaction(command) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SQLiteConstraintException) {
                classifyRolledBackConflict(command)
            } catch (_: IllegalArgumentException) {
                classifyRolledBackConflict(command)
            } catch (_: IllegalStateException) {
                classifyRolledBackConflict(command)
            } catch (failure: SQLiteFullException) {
                throw StorageException(StorageError.InsufficientSpace, failure)
            } catch (failure: SQLiteException) {
                throw StorageException(StorageError.Unavailable, failure)
            }
        }

    private suspend fun voidInTransaction(command: PurchaseVoidCommand): PurchaseVoidResult {
        return when (val loaded = loadSnapshot(
            command.businessId,
            command.purchaseId,
            command.actor,
        )) {
            is SnapshotLoad.Result -> loaded.result.toVoidResult()
            is SnapshotLoad.Ready -> {
                val snapshot = loaded.snapshot
                if (snapshot.impactHash != command.expectedImpactHash) {
                    return PurchaseVoidResult.ImpactChanged(snapshot.toPreview(command.actor))
                }
                if (snapshot.balances.any { it.version == Long.MAX_VALUE }) {
                    return PurchaseVoidResult.RetryableConflict
                }
                val latestStoredAt = maxOf(
                    snapshot.purchase.updatedAt,
                    snapshot.purchase.postedAt ?: 0L,
                    snapshot.originalMovements.maxOf(StockMovementEntity::occurredAt),
                    snapshot.originalMovements.maxOf(StockMovementEntity::createdAt),
                    snapshot.balances.maxOf(InventoryBalanceEntity::updatedAt),
                )
                if (latestStoredAt == Long.MAX_VALUE) {
                    return PurchaseVoidResult.RetryableConflict
                }
                val voidedAt = maxOf(
                    appClock.now().toEpochMilli(),
                    latestStoredAt + 1L,
                )
                val batch = snapshot.toBatch(command, voidedAt)
                database.purchaseVoidDao().voidAtomically(batch)
                PurchaseVoidResult.Voided(
                    purchaseId = command.purchaseId,
                    negativeImpacts = snapshot.impacts.filter(PurchaseVoidImpact::becomesNegative),
                )
            }
        }
    }

    private suspend fun loadSnapshot(
        businessId: BusinessId,
        purchaseId: PurchaseId,
        actor: PurchaseOverrideActor,
    ): SnapshotLoad {
        val business = database.businessDao().findById(businessId.value)
        if (business == null || business.status != CatalogStatus.ACTIVE.name) {
            return SnapshotLoad.Result(PreviewPurchaseVoidResult.NoActiveBusiness)
        }
        val purchase = database.purchaseDao().findById(purchaseId.value)
            ?.takeIf { it.businessId == businessId.value }
            ?: return SnapshotLoad.Result(PreviewPurchaseVoidResult.NotFound)
        val status = PurchaseStatus.valueOf(purchase.status)
        if (status == PurchaseStatus.VOIDED) {
            return if (hasCompleteVoidGraph(purchase)) {
                SnapshotLoad.Result(PreviewPurchaseVoidResult.AlreadyVoided(purchaseId))
            } else {
                SnapshotLoad.Result(PreviewPurchaseVoidResult.RetryableConflict)
            }
        }
        if (status != PurchaseStatus.POSTED) {
            return SnapshotLoad.Result(
                PreviewPurchaseVoidResult.NotPosted(purchaseId, status),
            )
        }

        val allMovements = database.inventoryDao().listMovementsForPurchase(
            businessId.value,
            purchaseId.value,
        )
        if (allMovements.any { it.type == StockMovementType.VOID.name }) {
            return SnapshotLoad.Result(PreviewPurchaseVoidResult.RetryableConflict)
        }
        val originals = allMovements.filter { it.type == StockMovementType.PURCHASE.name }
            .sortedBy(StockMovementEntity::movementId)
        val lines = database.purchaseLineDao().listForPurchase(purchaseId.value)
        val lineProducts = lines.associate { it.purchaseLineId to it.productId }
        val expectedOriginalSign = when (PurchaseDocumentType.valueOf(purchase.documentType)) {
            PurchaseDocumentType.CREDIT_NOTE -> -1
            PurchaseDocumentType.INVOICE,
            PurchaseDocumentType.SALES_RECEIPT,
            PurchaseDocumentType.DEBIT_NOTE,
            -> 1
        }
        if (
            originals.isEmpty() || originals.size != lines.size ||
            originals.any { movement ->
                movement.businessId != purchase.businessId ||
                    movement.purchaseId != purchase.purchaseId ||
                    movement.purchaseLineId == null || movement.unitCost == null ||
                    movement.currencyCode != purchase.currencyCode ||
                    movement.quantityDelta.toBigDecimal().signum() != expectedOriginalSign ||
                    lineProducts[movement.purchaseLineId] != movement.productId
            } ||
            originals.mapNotNull(StockMovementEntity::purchaseLineId).toSet() != lineProducts.keys ||
            originals.groupingBy(StockMovementEntity::purchaseLineId).eachCount().values.any { it != 1 }
        ) {
            return SnapshotLoad.Result(PreviewPurchaseVoidResult.RetryableConflict)
        }

        val movementsByKey = originals.groupBy { movement ->
            StockKey(movement.productId, movement.locationId)
        }
        val balances = mutableListOf<InventoryBalanceEntity>()
        val impacts = mutableListOf<PurchaseVoidImpact>()
        for ((key, movements) in movementsByKey.toSortedMap()) {
            val balance = database.inventoryDao().findBalance(
                businessId = businessId.value,
                productId = key.productId,
                locationId = key.locationId,
            ) ?: return SnapshotLoad.Result(PreviewPurchaseVoidResult.RetryableConflict)
            if (balance.currencyCode != purchase.currencyCode) {
                return SnapshotLoad.Result(PreviewPurchaseVoidResult.RetryableConflict)
            }
            val product = database.productDao().findById(key.productId)
                ?.takeIf { it.businessId == businessId.value }
                ?: return SnapshotLoad.Result(PreviewPurchaseVoidResult.RetryableConflict)
            val location = database.inventoryLocationDao().findById(key.locationId)
                ?.takeIf { it.businessId == businessId.value }
                ?: return SnapshotLoad.Result(PreviewPurchaseVoidResult.RetryableConflict)
            val unit = database.unitDao().findById(product.unitId)
                ?.takeIf { it.businessId == businessId.value }
                ?: return SnapshotLoad.Result(PreviewPurchaseVoidResult.RetryableConflict)
            val current = balance.quantityOnHand.toBigDecimalOrNull()
                ?: return SnapshotLoad.Result(PreviewPurchaseVoidResult.RetryableConflict)
            val reversal = movements.fold(BigDecimal.ZERO) { total, movement ->
                val quantity = movement.quantityDelta.toBigDecimalOrNull()
                    ?: return SnapshotLoad.Result(PreviewPurchaseVoidResult.RetryableConflict)
                total.subtract(quantity)
            }
            if (reversal.signum() == 0) {
                return SnapshotLoad.Result(PreviewPurchaseVoidResult.RetryableConflict)
            }
            balances += balance
            impacts += PurchaseVoidImpact(
                productId = ProductId.parse(product.productId)
                    ?: return SnapshotLoad.Result(PreviewPurchaseVoidResult.RetryableConflict),
                productName = product.name,
                locationId = LocationId.parse(location.locationId)
                    ?: return SnapshotLoad.Result(PreviewPurchaseVoidResult.RetryableConflict),
                locationName = location.name,
                unitCode = unit.code,
                currentQuantity = current,
                reversalQuantity = reversal,
                resultingQuantity = current.add(reversal),
                currency = CurrencyCode.of(balance.currencyCode),
            )
        }
        val impactHash = PurchaseVoidImpactSeal.create(
            purchase = purchase,
            purchaseMovements = originals,
            balances = balances,
            actorId = actor.actorId,
            actorRole = actor.role.name,
        )
        return SnapshotLoad.Ready(
            PurchaseVoidSnapshot(
                purchase = purchase,
                originalMovements = originals,
                balances = balances,
                impacts = impacts,
                impactHash = impactHash,
            ),
        )
    }

    private suspend fun hasCompleteVoidGraph(purchase: PurchaseEntity): Boolean {
        val movements = database.inventoryDao().listMovementsForPurchase(
            purchase.businessId,
            purchase.purchaseId,
        )
        val originals = movements.filter { it.type == StockMovementType.PURCHASE.name }
        val reversalsByKey = movements.filter { it.type == StockMovementType.VOID.name }
            .associateBy(StockMovementEntity::idempotencyKey)
        if (originals.isEmpty() || reversalsByKey.size != originals.size) return false
        val movementGraphComplete = originals.all { original ->
            val reversal = reversalsByKey[
                PurchaseVoidIdentity.movementKey(purchase.purchaseId, original.movementId)
            ] ?: return@all false
            reversal.movementId == PurchaseVoidIdentity.movementId(
                purchase.purchaseId,
                original.movementId,
            ) && reversal.businessId == purchase.businessId &&
                reversal.purchaseId == purchase.purchaseId &&
                reversal.purchaseLineId == original.purchaseLineId &&
                reversal.productId == original.productId &&
                reversal.locationId == original.locationId &&
                reversal.quantityDelta == original.quantityDelta.negatedDecimalText() &&
                reversal.unitCost == original.unitCost &&
                reversal.currencyCode == original.currencyCode &&
                reversal.occurredAt == purchase.voidedAt &&
                reversal.createdAt == purchase.voidedAt
        }
        if (!movementGraphComplete) return false
        val auditEvents = database.auditEventDao().listForPurchase(
            purchase.businessId,
            purchase.purchaseId,
        )
        if (auditEvents.count { it.eventType == AuditEventType.PURCHASE_VOIDED.name } != 1) {
            return false
        }
        val voidAudit = auditEvents.singleOrNull { event ->
            event.auditEventId == PurchaseVoidIdentity.auditId(purchase.purchaseId) &&
                event.eventType == AuditEventType.PURCHASE_VOIDED.name &&
                event.businessId == purchase.businessId &&
                event.purchaseId == purchase.purchaseId &&
                event.entityType == "PURCHASE" && event.entityId == purchase.purchaseId &&
                event.occurredAt == purchase.voidedAt
        } ?: return false
        val voidOutbox = database.outboxOperationDao().findByIdempotencyKey(
            PurchaseVoidIdentity.outboxKey(purchase.purchaseId),
        ) ?: return false
        val auditPayload = voidAudit.payload
        val auditPayloadLooksCanonical =
            auditPayload.contains("\"version\":\"1\"") &&
                auditPayload.contains("\"purchaseId\":\"${purchase.purchaseId}\"") &&
                (auditPayload.contains("\"actorRole\":\"OWNER\"") ||
                    auditPayload.contains("\"actorRole\":\"MANAGER\"")) &&
                auditPayload.contains("\"negativeImpactCount\":\"") &&
                auditPayload.contains(
                    "\"negativeStockPolicy\":\"ALLOW_WITH_VISIBLE_WARNING\"",
                ) &&
                auditPayload.contains(
                    "\"averageUnitCostPolicy\":\"PRESERVE_CURRENT\"",
                ) &&
                FORBIDDEN_AUDIT_FIELDS.none(auditPayload::contains)
        val outboxPayloadLooksFunctional =
            voidOutbox.payload.startsWith("{\"version\":1") &&
                voidOutbox.payload.contains("\"purchaseId\":\"${purchase.purchaseId}\"") &&
                IMPACT_HASH_FIELD.containsMatchIn(voidOutbox.payload) &&
                voidOutbox.payload.contains("\"actorId\":\"") &&
                voidOutbox.payload.contains("\"reason\":\"")
        val outboxComplete =
            voidOutbox.operationId == PurchaseVoidIdentity.outboxId(purchase.purchaseId) &&
                voidOutbox.businessId == purchase.businessId &&
                voidOutbox.purchaseId == purchase.purchaseId &&
                voidOutbox.operationType == "SYNC_PURCHASE_VOID" &&
                voidOutbox.createdAt == purchase.voidedAt
        return auditPayloadLooksCanonical && outboxPayloadLooksFunctional && outboxComplete
    }

    private suspend fun classifyRolledBackConflict(
        command: PurchaseVoidCommand,
    ): PurchaseVoidResult = try {
        database.withTransaction {
            when (val loaded = loadSnapshot(
                command.businessId,
                command.purchaseId,
                command.actor,
            )) {
                is SnapshotLoad.Ready -> PurchaseVoidResult.RetryableConflict
                is SnapshotLoad.Result -> loaded.result.toVoidResult()
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: SQLiteFullException) {
        throw StorageException(StorageError.InsufficientSpace, failure)
    } catch (failure: SQLiteException) {
        throw StorageException(StorageError.Unavailable, failure)
    }

    private fun PreviewPurchaseVoidResult.toVoidResult(): PurchaseVoidResult = when (this) {
        is PreviewPurchaseVoidResult.AlreadyVoided -> PurchaseVoidResult.AlreadyVoided(purchaseId)
        PreviewPurchaseVoidResult.NoActiveBusiness -> PurchaseVoidResult.NoActiveBusiness
        PreviewPurchaseVoidResult.NotFound -> PurchaseVoidResult.NotFound
        is PreviewPurchaseVoidResult.NotPosted -> PurchaseVoidResult.NotPosted(purchaseId, status)
        PreviewPurchaseVoidResult.RetryableConflict -> PurchaseVoidResult.RetryableConflict
        PreviewPurchaseVoidResult.Unauthorized -> PurchaseVoidResult.Unauthorized
        is PreviewPurchaseVoidResult.Ready -> PurchaseVoidResult.RetryableConflict
    }

    private fun PurchaseVoidSnapshot.toPreview(actor: PurchaseOverrideActor): PurchaseVoidPreview =
        PurchaseVoidPreview(
            purchaseId = requireNotNull(PurchaseId.parse(purchase.purchaseId)),
            documentNumber = "${purchase.documentSeries}-${purchase.documentNumber}",
            actor = actor,
            impacts = impacts,
            expectedImpactHash = impactHash,
        )

    private fun PurchaseVoidSnapshot.toBatch(
        command: PurchaseVoidCommand,
        voidedAt: Long,
    ): PurchaseVoidBatch {
        val balancesByKey = balances.associateBy { balance ->
            StockKey(balance.productId, balance.locationId)
        }
        val movementsByKey = originalMovements.groupBy { movement ->
            StockKey(movement.productId, movement.locationId)
        }
        val balanceMutations = impacts.map { impact ->
            val key = StockKey(impact.productId.value, impact.locationId.value)
            val current = requireNotNull(balancesByKey[key])
            val reversal = requireNotNull(movementsByKey[key]).fold(BigDecimal.ZERO) { total, row ->
                total.subtract(row.quantityDelta.toBigDecimal())
            }
            PurchaseVoidBalanceMutation(
                expectedVersion = current.version,
                expectedQuantityOnHand = current.quantityOnHand,
                expectedAverageUnitCost = current.averageUnitCost,
                reversalQuantity = reversal.toPlainString(),
                balance = current.copy(
                    quantityOnHand = current.quantityOnHand.toBigDecimal()
                        .add(reversal).toPlainString(),
                    // Salida conservadora: nunca se reconstruye un costo pasado no demostrable.
                    averageUnitCost = current.averageUnitCost,
                    version = current.version + 1L,
                    updatedAt = voidedAt,
                ),
            )
        }
        val compensations = originalMovements.map { original ->
            PurchaseVoidMovementCompensation(
                originalMovementId = original.movementId,
                reversal = StockMovementEntity(
                    movementId = PurchaseVoidIdentity.movementId(
                        purchase.purchaseId,
                        original.movementId,
                    ),
                    businessId = purchase.businessId,
                    purchaseId = purchase.purchaseId,
                    purchaseLineId = original.purchaseLineId,
                    productId = original.productId,
                    locationId = original.locationId,
                    type = StockMovementType.VOID.name,
                    quantityDelta = original.quantityDelta.negatedDecimalText(),
                    unitCost = original.unitCost,
                    currencyCode = original.currencyCode,
                    idempotencyKey = PurchaseVoidIdentity.movementKey(
                        purchase.purchaseId,
                        original.movementId,
                    ),
                    occurredAt = voidedAt,
                    createdAt = voidedAt,
                ),
            )
        }
        val outboxPayload = purchaseVoidPayload(
            purchaseId = purchase.purchaseId,
            impactHash = impactHash,
            actorId = command.actor.actorId,
            actorRole = command.actor.role.name,
            reason = command.reason,
            balanceMutations = balanceMutations,
        )
        return PurchaseVoidBatch(
            purchaseId = purchase.purchaseId,
            businessId = purchase.businessId,
            expectedPurchaseUpdatedAt = purchase.updatedAt,
            expectedImpactHash = impactHash,
            reason = command.reason,
            actorId = command.actor.actorId,
            actorRole = command.actor.role.name,
            voidedAt = voidedAt,
            balanceMutations = balanceMutations,
            movementCompensations = compensations,
            auditEvent = AuditEventEntity(
                auditEventId = PurchaseVoidIdentity.auditId(purchase.purchaseId),
                businessId = purchase.businessId,
                purchaseId = purchase.purchaseId,
                eventType = AuditEventType.PURCHASE_VOIDED.name,
                entityType = "PURCHASE",
                entityId = purchase.purchaseId,
                payload = purchaseVoidAuditPayload(
                    purchaseId = purchase.purchaseId,
                    actorRole = command.actor.role.name,
                    balanceMutations = balanceMutations,
                ),
                occurredAt = voidedAt,
            ),
            outboxOperation = OutboxOperationEntity(
                operationId = PurchaseVoidIdentity.outboxId(purchase.purchaseId),
                businessId = purchase.businessId,
                purchaseId = purchase.purchaseId,
                idempotencyKey = PurchaseVoidIdentity.outboxKey(purchase.purchaseId),
                operationType = "SYNC_PURCHASE_VOID",
                payload = outboxPayload,
                status = OutboxOperationStatus.PENDING.name,
                attemptCount = 0,
                createdAt = voidedAt,
                updatedAt = voidedAt,
                nextAttemptAt = voidedAt,
                payloadVersion = SYNC_PURCHASE_VOID_PAYLOAD_VERSION,
                entityVersion = 2,
            ),
        )
    }

    private fun String.negatedDecimalText(): String =
        if (startsWith('-')) substring(1) else "-$this"

    private data class StockKey(val productId: String, val locationId: String) :
        Comparable<StockKey> {
        override fun compareTo(other: StockKey): Int =
            compareValuesBy(this, other, StockKey::productId, StockKey::locationId)
    }

    private data class PurchaseVoidSnapshot(
        val purchase: PurchaseEntity,
        val originalMovements: List<StockMovementEntity>,
        val balances: List<InventoryBalanceEntity>,
        val impacts: List<PurchaseVoidImpact>,
        val impactHash: String,
    )

    private sealed interface SnapshotLoad {
        data class Ready(val snapshot: PurchaseVoidSnapshot) : SnapshotLoad
        data class Result(val result: PreviewPurchaseVoidResult) : SnapshotLoad
    }

    private companion object {
        val IMPACT_HASH_FIELD = Regex("\\\"impactHash\\\":\\\"[0-9a-f]{64}\\\"")
        val FORBIDDEN_AUDIT_FIELDS = setOf(
            "\"actorId\"",
            "\"reason\"",
            "\"impactHash\"",
            "\"impacts\"",
            "\"currency\"",
            "\"quantity\"",
            "\"unitCost\"",
        )
    }
}
