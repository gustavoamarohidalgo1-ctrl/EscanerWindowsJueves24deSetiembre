package com.facturastock.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.facturastock.app.data.local.PurchaseVoidIdentity
import com.facturastock.app.data.local.PurchaseVoidImpactSeal
import com.facturastock.app.data.local.entity.AuditEventEntity
import com.facturastock.app.data.local.entity.InventoryBalanceEntity
import com.facturastock.app.data.local.entity.OutboxOperationEntity
import com.facturastock.app.data.local.entity.PurchaseEntity
import com.facturastock.app.data.local.entity.StockMovementEntity
import com.facturastock.app.domain.model.AuditEventType
import com.facturastock.app.domain.model.OutboxOperationStatus
import com.facturastock.app.domain.model.PurchaseOverrideRole
import com.facturastock.app.domain.model.PurchaseDocumentType
import com.facturastock.app.domain.model.PurchaseStatus
import com.facturastock.app.domain.model.StockMovementType
import com.facturastock.app.domain.repository.AuditPayloadKey
import com.facturastock.app.domain.repository.AuditPayloadPolicy
import java.math.BigDecimal

data class PurchaseVoidBalanceMutation(
    val expectedVersion: Long,
    val expectedQuantityOnHand: String,
    val expectedAverageUnitCost: String,
    val reversalQuantity: String,
    val balance: InventoryBalanceEntity,
)

data class PurchaseVoidMovementCompensation(
    val originalMovementId: String,
    val reversal: StockMovementEntity,
)

/** Grafo de compensación completo, ya sellado por la instantánea que confirmó el usuario. */
data class PurchaseVoidBatch(
    val purchaseId: String,
    val businessId: String,
    val expectedPurchaseUpdatedAt: Long,
    val expectedImpactHash: String,
    val reason: String,
    val actorId: String,
    val actorRole: String,
    val voidedAt: Long,
    val balanceMutations: List<PurchaseVoidBalanceMutation>,
    val movementCompensations: List<PurchaseVoidMovementCompensation>,
    val auditEvent: AuditEventEntity,
    val outboxOperation: OutboxOperationEntity,
)

/**
 * Coordinador de la única transición destructivamente permitida sobre una compra publicada:
 * `POSTED -> VOIDED`, siempre mediante nuevas entradas append-only.
 */
@Dao
abstract class PurchaseVoidDao {
    @Transaction
    open suspend fun voidAtomically(batch: PurchaseVoidBatch): PurchaseEntity {
        validateEnvelope(batch)
        val purchase = requireNotNull(findPurchase(batch.businessId, batch.purchaseId)) {
            "La compra ya no existe en el negocio"
        }
        require(purchase.status == PurchaseStatus.POSTED.name && purchase.voidedAt == null) {
            "Solo una compra POSTED puede anularse"
        }
        require(purchase.updatedAt == batch.expectedPurchaseUpdatedAt) {
            "La compra cambió desde la previsualización"
        }
        require(batch.voidedAt > purchase.updatedAt) {
            "La anulación debe ocurrir después de la última mutación de la compra"
        }

        val allMovements = listMovements(batch.businessId, batch.purchaseId)
        require(allMovements.none { it.type == StockMovementType.VOID.name }) {
            "La compra ya tiene movimientos de anulación"
        }
        val originals = allMovements.filter { it.type == StockMovementType.PURCHASE.name }
        require(originals.isNotEmpty()) { "La compra no conserva movimientos PURCHASE" }
        require(originals.size == countPurchaseLines(batch.purchaseId)) {
            "Cada línea debe conservar exactamente un movimiento PURCHASE"
        }
        require(originals.groupingBy { it.purchaseLineId }.eachCount().values.all { it == 1 }) {
            "Cada línea debe conservar exactamente un movimiento PURCHASE"
        }
        val expectedOriginalSign = when (PurchaseDocumentType.valueOf(purchase.documentType)) {
            PurchaseDocumentType.CREDIT_NOTE -> -1
            PurchaseDocumentType.INVOICE,
            PurchaseDocumentType.SALES_RECEIPT,
            PurchaseDocumentType.DEBIT_NOTE,
            -> 1
        }
        require(originals.all { original ->
            original.businessId == purchase.businessId &&
                original.purchaseId == purchase.purchaseId &&
                original.purchaseLineId != null &&
                original.type == StockMovementType.PURCHASE.name &&
                original.quantityDelta.toBigDecimal().signum() == expectedOriginalSign &&
                original.unitCost != null && original.currencyCode == purchase.currencyCode
        }) { "El libro PURCHASE original no es reversible de forma segura" }

        val currentBalances = batch.balanceMutations.map { mutation ->
            requireNotNull(
                findBalance(
                    businessId = batch.businessId,
                    productId = mutation.balance.productId,
                    locationId = mutation.balance.locationId,
                ),
            ) { "Falta el saldo materializado que debe compensarse" }
        }
        require(
            PurchaseVoidImpactSeal.create(
                purchase = purchase,
                purchaseMovements = originals,
                balances = currentBalances,
                actorId = batch.actorId,
                actorRole = batch.actorRole,
            ) ==
                batch.expectedImpactHash,
        ) { "El impacto cambió desde la previsualización" }

        validateCompensations(batch, purchase, originals)
        validateBalances(batch, purchase, originals, currentBalances)

        batch.balanceMutations.forEach { mutation ->
            val final = mutation.balance
            check(
                updateBalanceIfSnapshot(
                    businessId = final.businessId,
                    productId = final.productId,
                    locationId = final.locationId,
                    expectedVersion = mutation.expectedVersion,
                    expectedQuantityOnHand = mutation.expectedQuantityOnHand,
                    expectedAverageUnitCost = mutation.expectedAverageUnitCost,
                    currencyCode = final.currencyCode,
                    quantityOnHand = final.quantityOnHand,
                    averageUnitCost = final.averageUnitCost,
                    updatedAt = final.updatedAt,
                ) == 1,
            ) { "El saldo cambió durante la anulación" }
        }
        insertMovements(batch.movementCompensations.map(PurchaseVoidMovementCompensation::reversal))
        insertAudit(batch.auditEvent)
        insertOutbox(batch.outboxOperation)

        check(
            markVoided(
                purchaseId = batch.purchaseId,
                businessId = batch.businessId,
                expectedUpdatedAt = batch.expectedPurchaseUpdatedAt,
                voidedAt = batch.voidedAt,
                postedStatus = PurchaseStatus.POSTED.name,
                voidedStatus = PurchaseStatus.VOIDED.name,
            ) == 1,
        ) { "La compra cambió durante la anulación" }
        return purchase.copy(
            status = PurchaseStatus.VOIDED.name,
            updatedAt = batch.voidedAt,
            voidedAt = batch.voidedAt,
        )
    }

    private fun validateEnvelope(batch: PurchaseVoidBatch) {
        require(batch.expectedPurchaseUpdatedAt >= 0L)
        require(batch.voidedAt > batch.expectedPurchaseUpdatedAt)
        require(Regex("[0-9a-f]{64}").matches(batch.expectedImpactHash))
        require(batch.reason == batch.reason.trim() && batch.reason.length in 10..500)
        require(batch.actorId.isNotBlank() && batch.actorId.length <= 128)
        require(batch.actorRole in setOf(
            PurchaseOverrideRole.OWNER.name,
            PurchaseOverrideRole.MANAGER.name,
        ))
        require(batch.balanceMutations.isNotEmpty())
        require(batch.movementCompensations.isNotEmpty())
        require(batch.balanceMutations.map { it.balance.stockKey() }.toSet().size ==
            batch.balanceMutations.size
        ) { "Un saldo no puede mutarse dos veces" }
    }

    private fun validateCompensations(
        batch: PurchaseVoidBatch,
        purchase: PurchaseEntity,
        originals: List<StockMovementEntity>,
    ) {
        val originalsById = originals.associateBy(StockMovementEntity::movementId)
        require(originalsById.size == originals.size)
        require(batch.movementCompensations.size == originals.size)
        require(batch.movementCompensations.map { it.originalMovementId }.toSet() ==
            originalsById.keys
        ) { "Debe existir una reversa por movimiento original" }
        require(batch.movementCompensations.map { it.reversal.movementId }.toSet().size ==
            originals.size
        )
        require(batch.movementCompensations.map { it.reversal.idempotencyKey }.toSet().size ==
            originals.size
        )

        batch.movementCompensations.forEach { compensation ->
            val original = requireNotNull(originalsById[compensation.originalMovementId])
            val reversal = compensation.reversal
            require(reversal.movementId == PurchaseVoidIdentity.movementId(
                purchase.purchaseId,
                original.movementId,
            ))
            require(reversal.idempotencyKey == PurchaseVoidIdentity.movementKey(
                purchase.purchaseId,
                original.movementId,
            ))
            require(
                reversal.businessId == purchase.businessId &&
                    reversal.purchaseId == purchase.purchaseId &&
                    reversal.purchaseLineId == original.purchaseLineId &&
                    reversal.productId == original.productId &&
                    reversal.locationId == original.locationId &&
                    reversal.type == StockMovementType.VOID.name &&
                    reversal.unitCost == original.unitCost &&
                    reversal.currencyCode == original.currencyCode &&
                    reversal.currencyCode == purchase.currencyCode &&
                    reversal.occurredAt == batch.voidedAt &&
                    reversal.createdAt == batch.voidedAt
            ) { "La reversa no conserva la trazabilidad exacta del movimiento original" }
            require(
                reversal.quantityDelta.toBigDecimal().compareTo(
                    original.quantityDelta.toBigDecimal().negate(),
                ) == 0,
            ) { "La cantidad VOID debe ser el opuesto exacto de PURCHASE" }
            // También se exige la representación canónica opuesta; el trigger puede comprobarla
            // sin CAST NUMERIC y no pierde decimales grandes.
            require(reversal.quantityDelta == original.quantityDelta.negatedDecimalText())
        }
    }

    private fun validateBalances(
        batch: PurchaseVoidBatch,
        purchase: PurchaseEntity,
        originals: List<StockMovementEntity>,
        currentBalances: List<InventoryBalanceEntity>,
    ) {
        val reversalByKey = originals.groupBy { it.stockKey() }.mapValues { (_, rows) ->
            rows.fold(BigDecimal.ZERO) { total, row ->
                total.subtract(row.quantityDelta.toBigDecimal())
            }
        }
        val mutationsByKey = batch.balanceMutations.associateBy { it.balance.stockKey() }
        require(reversalByKey.keys == mutationsByKey.keys) {
            "Debe existir una mutación por cada destino compensado"
        }
        val currentByKey = currentBalances.associateBy { it.stockKey() }
        require(currentByKey.keys == reversalByKey.keys)

        mutationsByKey.forEach { (key, mutation) ->
            val current = requireNotNull(currentByKey[key])
            val final = mutation.balance
            val exactReversal = requireNotNull(reversalByKey[key])
            require(mutation.expectedVersion == current.version)
            require(current.version < Long.MAX_VALUE)
            require(mutation.expectedQuantityOnHand == current.quantityOnHand)
            require(mutation.expectedAverageUnitCost == current.averageUnitCost)
            require(mutation.reversalQuantity.toBigDecimal().compareTo(exactReversal) == 0)
            require(
                final.businessId == purchase.businessId &&
                    final.productId == current.productId &&
                    final.locationId == current.locationId &&
                    final.currencyCode == purchase.currencyCode &&
                    final.currencyCode == current.currencyCode &&
                    final.version == current.version + 1L &&
                    final.updatedAt == batch.voidedAt && final.updatedAt > current.updatedAt
            )
            require(final.quantityOnHand.toBigDecimal().compareTo(
                current.quantityOnHand.toBigDecimal().add(exactReversal),
            ) == 0) { "El saldo final no aplica la reversa exacta" }
            // Regla conservadora v1: una salida no reescribe el promedio vigente. unitCost del
            // VOID es evidencia, no una promesa de reconstruir costo histórico tras consumos.
            require(final.averageUnitCost == current.averageUnitCost) {
                "La anulación debe conservar el costo promedio vigente"
            }
        }

        val audit = batch.auditEvent
        val expectedAuditPayload = purchaseVoidAuditPayload(
            purchaseId = purchase.purchaseId,
            actorRole = batch.actorRole,
            balanceMutations = batch.balanceMutations,
        )
        val expectedOutboxPayload = purchaseVoidPayload(
            purchaseId = purchase.purchaseId,
            impactHash = batch.expectedImpactHash,
            actorId = batch.actorId,
            actorRole = batch.actorRole,
            reason = batch.reason,
            balanceMutations = batch.balanceMutations,
        )
        require(
            audit.auditEventId == PurchaseVoidIdentity.auditId(purchase.purchaseId) &&
                audit.businessId == purchase.businessId &&
                audit.purchaseId == purchase.purchaseId &&
                audit.eventType == AuditEventType.PURCHASE_VOIDED.name &&
                audit.entityType == "PURCHASE" && audit.entityId == purchase.purchaseId &&
                audit.occurredAt == batch.voidedAt && audit.payload == expectedAuditPayload
        )
        val outbox = batch.outboxOperation
        require(
            outbox.operationId == PurchaseVoidIdentity.outboxId(purchase.purchaseId) &&
                outbox.businessId == purchase.businessId &&
                outbox.purchaseId == purchase.purchaseId &&
                outbox.idempotencyKey == PurchaseVoidIdentity.outboxKey(purchase.purchaseId) &&
                outbox.operationType == "SYNC_PURCHASE_VOID" &&
                outbox.status == OutboxOperationStatus.PENDING.name &&
                outbox.attemptCount == 0 && outbox.createdAt == batch.voidedAt &&
                outbox.updatedAt == batch.voidedAt && outbox.nextAttemptAt == batch.voidedAt &&
                outbox.completedAt == null && outbox.lastError == null &&
                outbox.payload == expectedOutboxPayload
        )
    }

    private fun String.negatedDecimalText(): String =
        if (startsWith('-')) substring(1) else "-$this"

    private fun StockMovementEntity.stockKey(): StockKey = StockKey(productId, locationId)
    private fun InventoryBalanceEntity.stockKey(): StockKey = StockKey(productId, locationId)
    private data class StockKey(val productId: String, val locationId: String)

    @Query(
        "SELECT * FROM purchases WHERE businessId = :businessId AND purchaseId = :purchaseId",
    )
    protected abstract suspend fun findPurchase(
        businessId: String,
        purchaseId: String,
    ): PurchaseEntity?

    @Query(
        "SELECT * FROM stock_movements WHERE businessId = :businessId " +
            "AND purchaseId = :purchaseId ORDER BY movementId ASC",
    )
    protected abstract suspend fun listMovements(
        businessId: String,
        purchaseId: String,
    ): List<StockMovementEntity>

    @Query("SELECT COUNT(*) FROM purchase_lines WHERE purchaseId = :purchaseId")
    protected abstract suspend fun countPurchaseLines(purchaseId: String): Int

    @Query(
        "SELECT * FROM inventory_balances WHERE businessId = :businessId " +
            "AND productId = :productId AND locationId = :locationId",
    )
    protected abstract suspend fun findBalance(
        businessId: String,
        productId: String,
        locationId: String,
    ): InventoryBalanceEntity?

    @Query(
        "UPDATE inventory_balances SET quantityOnHand = :quantityOnHand, " +
            "averageUnitCost = :averageUnitCost, currencyCode = :currencyCode, " +
            "version = version + 1, updatedAt = :updatedAt " +
            "WHERE businessId = :businessId AND productId = :productId " +
            "AND locationId = :locationId AND version = :expectedVersion " +
            "AND quantityOnHand = :expectedQuantityOnHand " +
            "AND averageUnitCost = :expectedAverageUnitCost " +
            "AND currencyCode = :currencyCode AND :updatedAt >= updatedAt",
    )
    protected abstract suspend fun updateBalanceIfSnapshot(
        businessId: String,
        productId: String,
        locationId: String,
        expectedVersion: Long,
        expectedQuantityOnHand: String,
        expectedAverageUnitCost: String,
        currencyCode: String,
        quantityOnHand: String,
        averageUnitCost: String,
        updatedAt: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertMovements(movements: List<StockMovementEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertAudit(event: AuditEventEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertOutbox(operation: OutboxOperationEntity)

    @Query(
        "UPDATE purchases SET status = :voidedStatus, voidedAt = :voidedAt, " +
            "updatedAt = :voidedAt WHERE purchaseId = :purchaseId " +
            "AND businessId = :businessId AND status = :postedStatus " +
            "AND updatedAt = :expectedUpdatedAt AND postedAt IS NOT NULL " +
            "AND voidedAt IS NULL AND :voidedAt >= postedAt",
    )
    protected abstract suspend fun markVoided(
        purchaseId: String,
        businessId: String,
        expectedUpdatedAt: Long,
        voidedAt: Long,
        postedStatus: String,
        voidedStatus: String,
    ): Int
}

/** Versión del payload JSON de `SYNC_PURCHASE_VOID`; una sola fuente para cuerpo y columna. */
internal const val SYNC_PURCHASE_VOID_PAYLOAD_VERSION = 1

/** Payload de auditoría mínimo; el cuerpo funcional del outbox se construye por separado. */
internal fun purchaseVoidAuditPayload(
    purchaseId: String,
    actorRole: String,
    balanceMutations: List<PurchaseVoidBalanceMutation>,
): String = AuditPayloadPolicy.encode(
    AuditEventType.PURCHASE_VOIDED,
    mapOf(
        AuditPayloadKey.VERSION to "1",
        AuditPayloadKey.PURCHASE_ID to purchaseId,
        AuditPayloadKey.ACTOR_ROLE to actorRole,
        AuditPayloadKey.NEGATIVE_IMPACT_COUNT to balanceMutations.count { mutation ->
            mutation.balance.quantityOnHand.toBigDecimal().signum() < 0
        }.toString(),
        AuditPayloadKey.NEGATIVE_STOCK_POLICY to "ALLOW_WITH_VISIBLE_WARNING",
        AuditPayloadKey.AVERAGE_UNIT_COST_POLICY to "PRESERVE_CURRENT",
    ),
)

/** Payload canónico compartido por el factory y el coordinador que lo vuelve a derivar. */
internal fun purchaseVoidPayload(
    purchaseId: String,
    impactHash: String,
    actorId: String,
    actorRole: String,
    reason: String,
    balanceMutations: List<PurchaseVoidBalanceMutation>,
): String = buildString {
    append("{\"version\":").append(SYNC_PURCHASE_VOID_PAYLOAD_VERSION)
    append(",\"purchaseId\":\"").append(purchaseId).append('"')
    append(",\"impactHash\":\"").append(impactHash).append('"')
    append(",\"actorId\":\"").append(actorId.jsonEscaped()).append('"')
    append(",\"role\":\"").append(actorRole).append('"')
    append(",\"reason\":\"").append(reason.jsonEscaped()).append('"')
    append(",\"negativeStockPolicy\":\"ALLOW_WITH_VISIBLE_WARNING\"")
    append(",\"averageUnitCostPolicy\":\"PRESERVE_CURRENT\"")
    append(",\"negativeImpactCount\":").append(
        balanceMutations.count { mutation ->
            mutation.balance.quantityOnHand.toBigDecimal().signum() < 0
        },
    )
    append(",\"impacts\":[")
    append(
        balanceMutations.sortedWith(
            compareBy<PurchaseVoidBalanceMutation> { it.balance.productId }
                .thenBy { it.balance.locationId },
        ).joinToString(",") { mutation ->
            val balance = mutation.balance
            buildString {
                append("{\"productId\":\"").append(balance.productId).append('"')
                append(",\"locationId\":\"").append(balance.locationId).append('"')
                append(",\"currentQuantity\":\"")
                    .append(mutation.expectedQuantityOnHand).append('"')
                append(",\"reversalQuantity\":\"")
                    .append(mutation.reversalQuantity).append('"')
                append(",\"resultingQuantity\":\"")
                    .append(balance.quantityOnHand).append('"')
                append(",\"currentAverageUnitCost\":\"")
                    .append(mutation.expectedAverageUnitCost).append('"')
                append(",\"currency\":\"").append(balance.currencyCode).append('"')
                append(",\"balanceVersion\":").append(mutation.expectedVersion)
                append(",\"negative\":")
                    .append(balance.quantityOnHand.toBigDecimal().signum() < 0).append('}')
            }
        },
    )
    append("]}")
}

private fun String.jsonEscaped(): String = buildString(length) {
    this@jsonEscaped.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) {
                append("\\u%04x".format(character.code))
            } else {
                append(character)
            }
        }
    }
}
