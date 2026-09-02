package com.facturastock.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.facturastock.app.data.local.codec.PreparedPurchaseCodec
import com.facturastock.app.data.local.entity.AuditEventEntity
import com.facturastock.app.data.local.entity.InventoryBalanceEntity
import com.facturastock.app.data.local.entity.OutboxOperationEntity
import com.facturastock.app.data.local.entity.PurchaseEntity
import com.facturastock.app.data.local.entity.PurchaseLineEntity
import com.facturastock.app.data.local.entity.PreparedPurchaseEntity
import com.facturastock.app.data.local.entity.StockMovementEntity
import com.facturastock.app.domain.model.AuditEventType
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InvoiceDocumentNumber
import com.facturastock.app.domain.model.InventoryCostRoundingPolicy
import com.facturastock.app.domain.model.InventoryAverageCostRequest
import com.facturastock.app.domain.model.InventoryAverageCostResult
import com.facturastock.app.domain.model.InventoryCostingRequest
import com.facturastock.app.domain.model.InventoryCostingResult
import com.facturastock.app.domain.model.InventoryTaxEvidence
import com.facturastock.app.domain.model.InventoryTaxEvidenceType
import com.facturastock.app.domain.model.InventoryTaxTreatment
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.OutboxOperationStatus
import com.facturastock.app.domain.model.PreparedPurchase
import com.facturastock.app.domain.model.PurchaseDocumentType
import com.facturastock.app.domain.model.PurchaseProductProvenance
import com.facturastock.app.domain.model.PurchaseStatus
import com.facturastock.app.domain.model.StockMovementType
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.config.CostPolicy
import com.facturastock.app.domain.usecase.InventoryCostingService
import java.math.BigDecimal
import java.math.RoundingMode

private const val INVENTORY_COST_SCALE = 18

/**
 * Escritura optimista de la proyección materializada de inventario.
 *
 * Un [expectedVersion] nulo significa que el saldo todavía no existe y [balance] debe comenzar
 * en la versión cero. Cuando existe, [balance] contiene la versión siguiente completa.
 */
data class InventoryBalanceMutation(
    val expectedVersion: Long?,
    val balance: InventoryBalanceEntity,
)

/** Columnas de producto necesarias para convertir compra a unidad de inventario. */
data class ProductPostingResolution(
    val productId: String,
    val inventoryUnitId: String,
    val purchaseUnitId: String?,
    val purchaseFactor: String?,
)

/** Grafo completo que debe confirmarse como una sola operación SQLite. */
data class PurchasePostingBatch(
    val purchase: PurchaseEntity,
    /** Hash lógico del `PreparedPurchase` exacto que autorizó este commit. */
    val expectedPreparedLogicalHash: String,
    val lines: List<PurchaseLineEntity>,
    val balanceMutations: List<InventoryBalanceMutation>,
    val movements: List<StockMovementEntity>,
    val auditEvents: List<AuditEventEntity>,
    val outboxOperations: List<OutboxOperationEntity>,
)

/**
 * Coordinador transaccional del libro de compras.
 *
 * La compra se inserta temporalmente como `DRAFT`, se escriben líneas, saldos, movimientos,
 * auditoría y outbox, se enlaza el borrador preparado, la compra se cambia a `POSTED` y solo
 * entonces el borrador pasa de `READY_TO_POST` a `COMMITTED`. Cualquier restricción, versión
 * obsoleta o carrera revierte el grafo completo.
 */
@Dao
abstract class PurchasePostingDao {
    private val inventoryCostingService = InventoryCostingService()

    @Transaction
    open suspend fun postAtomically(batch: PurchasePostingBatch): PurchaseEntity {
        validate(batch)

        val postedPurchase = batch.purchase
        val preparedPurchase = loadPreparedPurchase(batch)
        validateAgainstPrepared(batch, preparedPurchase)
        val draftPurchase = postedPurchase.copy(
            status = PurchaseStatus.DRAFT.name,
            updatedAt = postedPurchase.createdAt,
            postedAt = null,
            voidedAt = null,
        )

        require(
            supplierMatchesPreparedPurchase(
                supplierId = postedPurchase.supplierId,
                businessId = postedPurchase.businessId,
                preparedSupplierId = preparedPurchase.supplierId?.value,
                supplierRuc = preparedPurchase.supplierRuc,
            ),
        ) { "El proveedor no coincide con la compra preparada" }
        require(
            draftMatchesPreparedPurchase(
                draftId = postedPurchase.sourceDraftId,
                businessId = postedPurchase.businessId,
                readyStatus = DraftStatus.READY_TO_POST.name,
                logicalHash = batch.expectedPreparedLogicalHash,
            ),
        ) { "La instantánea preparada ya no autoriza esta publicación" }
        val resolutionByLineId = batch.lines.associate { line ->
            val resolution = requireNotNull(
                findProductPostingResolution(
                    productId = line.productId,
                    unitId = line.unitId,
                    businessId = postedPurchase.businessId,
                    activeStatus = CatalogStatus.ACTIVE.name,
                ),
            ) { "La resolución de catálogo de la línea ${line.purchaseLineId} es inválida" }
            line.purchaseLineId to resolution
        }
        validateMovementConversions(batch, resolutionByLineId)
        batch.balanceMutations.forEach { mutation ->
            val balance = mutation.balance
            require(
                stockTargetBelongsToBusiness(
                    productId = balance.productId,
                    locationId = balance.locationId,
                    businessId = postedPurchase.businessId,
                    activeStatus = CatalogStatus.ACTIVE.name,
                ),
            ) { "El destino de stock ${balance.productId}/${balance.locationId} es inválido" }
        }
        val costingExpectations = validateInventoryCosting(batch, resolutionByLineId)

        insertPurchase(draftPurchase)
        insertLines(batch.lines)

        check(
            linkPreparedDraft(
                draftId = postedPurchase.sourceDraftId,
                businessId = postedPurchase.businessId,
                purchaseId = postedPurchase.purchaseId,
                readyStatus = DraftStatus.READY_TO_POST.name,
                logicalHash = batch.expectedPreparedLogicalHash,
                updatedAt = postedPurchase.updatedAt,
            ) == 1,
        ) { "El borrador ya no está preparado o fue registrado por otra operación" }

        for (mutation in batch.balanceMutations) {
            applyBalanceMutation(
                mutation = mutation,
                movements = batch.movements,
                costingExpectation = costingExpectations[mutation.balance.stockKey()],
            )
        }
        insertMovements(batch.movements)
        insertAuditEvents(batch.auditEvents)
        insertOutboxOperations(batch.outboxOperations)

        check(
            markPosted(
                purchaseId = postedPurchase.purchaseId,
                businessId = postedPurchase.businessId,
                expectedUpdatedAt = draftPurchase.updatedAt,
                postedAt = requireNotNull(postedPurchase.postedAt),
                updatedAt = postedPurchase.updatedAt,
                draftStatus = PurchaseStatus.DRAFT.name,
                postedStatus = PurchaseStatus.POSTED.name,
            ) == 1,
        ) { "La compra cambió durante la publicación" }

        check(
            markDraftCommitted(
                draftId = postedPurchase.sourceDraftId,
                businessId = postedPurchase.businessId,
                purchaseId = postedPurchase.purchaseId,
                logicalHash = batch.expectedPreparedLogicalHash,
                readyStatus = DraftStatus.READY_TO_POST.name,
                committedStatus = DraftStatus.COMMITTED.name,
                postedStatus = PurchaseStatus.POSTED.name,
                updatedAt = postedPurchase.updatedAt,
            ) == 1,
        ) { "El borrador enlazado ya no puede marcarse como confirmado" }

        return postedPurchase
    }

    private suspend fun applyBalanceMutation(
        mutation: InventoryBalanceMutation,
        movements: List<StockMovementEntity>,
        costingExpectation: CostingBalanceExpectation?,
    ) {
        val balance = mutation.balance
        val expectedVersion = mutation.expectedVersion
        if (expectedVersion == null) {
            require(balance.version == 0L) { "Un saldo nuevo debe comenzar en versión 0" }
            val expectedQuantity = costingExpectation?.quantity
                ?: expectedQuantityFor(balance, BigDecimal.ZERO, movements)
            require(balance.quantityOnHand.toBigDecimal().compareTo(expectedQuantity) == 0) {
                "El saldo nuevo no coincide con los movimientos de la compra"
            }
            val expectedAverage = costingExpectation?.averageUnitCost
                ?: expectedAverageCostForNewBalance(balance, movements)
            require(balance.averageUnitCost.toBigDecimal().compareTo(expectedAverage) == 0) {
                "El costo promedio nuevo no coincide con los movimientos de la compra"
            }
            check(insertBalanceIfAbsent(balance) != -1L) {
                "El saldo apareció durante la publicación; se requiere recalcular"
            }
        } else {
            require(expectedVersion < Long.MAX_VALUE) { "La versión del saldo se agotó" }
            require(balance.version == expectedVersion + 1L) {
                "La versión final del saldo debe ser expectedVersion + 1"
            }
            val current = requireNotNull(
                findBalance(balance.businessId, balance.productId, balance.locationId),
            ) { "El saldo esperado ya no existe" }
            require(current.version == expectedVersion) {
                "El saldo cambió durante la publicación; se requiere recalcular"
            }
            require(current.currencyCode == balance.currencyCode) {
                "No se puede cambiar la moneda de un saldo existente"
            }
            val expectedQuantity = costingExpectation?.quantity
                ?: expectedQuantityFor(
                        balance = balance,
                        currentQuantity = current.quantityOnHand.toBigDecimal(),
                        movements = movements,
                    )
            require(balance.quantityOnHand.toBigDecimal().compareTo(expectedQuantity) == 0) {
                "El saldo final no coincide con los movimientos de la compra"
            }
            val expectedAverage = costingExpectation?.averageUnitCost
                ?: expectedAverageCostForExistingBalance(current, balance, movements)
            require(balance.averageUnitCost.toBigDecimal().compareTo(expectedAverage) == 0) {
                "El costo promedio final no coincide con saldo y movimientos"
            }
            check(
                updateBalanceIfVersion(
                    businessId = balance.businessId,
                    productId = balance.productId,
                    locationId = balance.locationId,
                    expectedVersion = expectedVersion,
                    quantityOnHand = balance.quantityOnHand,
                    averageUnitCost = balance.averageUnitCost,
                    currencyCode = balance.currencyCode,
                    newVersion = balance.version,
                    updatedAt = balance.updatedAt,
                ) == 1,
            ) { "El saldo cambió durante la publicación; se requiere recalcular" }
        }
    }

    private fun validate(batch: PurchasePostingBatch) {
        val purchase = batch.purchase
        require(purchase.status == PurchaseStatus.POSTED.name) {
            "postAtomically requiere una compra final POSTED"
        }
        require(batch.lines.isNotEmpty()) { "La compra debe tener al menos una línea" }
        require(batch.movements.isNotEmpty()) { "La publicación debe generar movimientos" }
        require(batch.auditEvents.isNotEmpty()) { "La publicación debe generar auditoría" }
        require(batch.outboxOperations.isNotEmpty()) { "La publicación debe generar outbox" }

        require(batch.lines.map { it.position }.sorted() == batch.lines.indices.toList()) {
            "Las posiciones de las líneas deben ser contiguas desde cero"
        }
        require(batch.lines.map { it.purchaseLineId }.toSet().size == batch.lines.size) {
            "Los identificadores de línea no pueden repetirse"
        }
        require(
            batch.lines.none {
                it.productProvenance == PurchaseProductProvenance.UNKNOWN_LEGACY.name
            },
        ) { "Una publicación nueva exige procedencia explícita del producto" }
        require(
            batch.lines.groupBy(PurchaseLineEntity::productId).values.all { productLines ->
                productLines.map(PurchaseLineEntity::productProvenance).distinct().size == 1
            },
        ) { "Un producto no puede tener procedencias distintas dentro de la misma compra" }
        require(
            batch.lines.all {
                it.purchaseId == purchase.purchaseId && it.currencyCode == purchase.currencyCode
            },
        ) { "Todas las líneas deben pertenecer a la compra y usar su moneda" }

        val lineById = batch.lines.associateBy { it.purchaseLineId }
        val expectedMovementSign = when (PurchaseDocumentType.valueOf(purchase.documentType)) {
            PurchaseDocumentType.CREDIT_NOTE -> -1
            PurchaseDocumentType.INVOICE,
            PurchaseDocumentType.SALES_RECEIPT,
            PurchaseDocumentType.DEBIT_NOTE,
            -> 1
        }
        require(batch.movements.map { it.movementId }.toSet().size == batch.movements.size) {
            "Los identificadores de movimiento no pueden repetirse"
        }
        require(batch.movements.map { it.idempotencyKey }.toSet().size == batch.movements.size) {
            "Las claves idempotentes de movimiento no pueden repetirse"
        }
        require(
            batch.movements.all { movement ->
                val line = movement.purchaseLineId?.let(lineById::get)
                movement.businessId == purchase.businessId &&
                    movement.purchaseId == purchase.purchaseId &&
                    movement.type == StockMovementType.PURCHASE.name &&
                    movement.quantityDelta.toBigDecimal().signum() == expectedMovementSign &&
                    movement.currencyCode == purchase.currencyCode &&
                    line != null && line.productId == movement.productId
            },
        ) { "Cada movimiento debe corresponder a una línea y producto de esta compra" }
        val movementsByLineId = batch.movements.groupBy(StockMovementEntity::purchaseLineId)
        require(
            batch.movements.size == batch.lines.size &&
                batch.lines.all { line ->
                    movementsByLineId[line.purchaseLineId]?.size == 1
                },
        ) { "Cada línea debe producir exactamente un movimiento de stock" }

        val balanceKeys = batch.balanceMutations.map { it.balance.stockKey() }
        require(balanceKeys.toSet().size == balanceKeys.size) {
            "Un saldo no puede mutarse dos veces en la misma publicación"
        }
        require(batch.balanceMutations.all { mutation ->
            val balance = mutation.balance
            balance.businessId == purchase.businessId &&
                balance.currencyCode == purchase.currencyCode
        }) { "Los saldos deben pertenecer al negocio y moneda de la compra" }
        val movementKeys = batch.movements.map { it.stockKey() }.toSet()
        require(balanceKeys.toSet() == movementKeys) {
            "Debe existir exactamente una mutación de saldo por destino de movimiento"
        }

        require(batch.auditEvents.map { it.auditEventId }.toSet().size == batch.auditEvents.size) {
            "Los identificadores de auditoría no pueden repetirse"
        }
        require(batch.auditEvents.count { event ->
            event.eventType == AuditEventType.PURCHASE_POSTED.name
        } == 1) { "La publicación necesita exactamente un evento PURCHASE_POSTED" }
        val expectedAuditTypes = if (purchase.duplicateOverrideOfPurchaseId == null) {
            setOf(AuditEventType.PURCHASE_POSTED.name)
        } else {
            setOf(
                AuditEventType.PURCHASE_POSTED.name,
                AuditEventType.PURCHASE_DUPLICATE_OVERRIDE.name,
            )
        }
        require(
            batch.auditEvents.size == expectedAuditTypes.size &&
                batch.auditEvents.mapTo(linkedSetOf(), AuditEventEntity::eventType) == expectedAuditTypes,
        ) { "La auditoría no coincide con la identidad documental de la compra" }
        require(batch.auditEvents.all { event ->
            event.businessId == purchase.businessId &&
                event.purchaseId == purchase.purchaseId &&
                event.entityId == purchase.purchaseId &&
                event.entityType == "PURCHASE" &&
                event.occurredAt == purchase.postedAt
        }) { "La auditoría debe describir la publicación de esta compra" }

        require(
            batch.outboxOperations.map { it.operationId }.toSet().size ==
                batch.outboxOperations.size,
        ) { "Los identificadores de outbox no pueden repetirse" }
        require(
            batch.outboxOperations.map { it.idempotencyKey }.toSet().size ==
                batch.outboxOperations.size,
        ) { "Las claves idempotentes de outbox no pueden repetirse" }
        require(
            batch.outboxOperations.count { it.operationType == "SYNC_PURCHASE" } == 1,
        ) { "La publicación necesita exactamente una operación SYNC_PURCHASE" }
        require(batch.outboxOperations.all { operation ->
                operation.businessId == purchase.businessId &&
                operation.purchaseId == purchase.purchaseId &&
                operation.status == OutboxOperationStatus.PENDING.name &&
                operation.operationType in POSTING_OUTBOX_OPERATION_TYPES &&
                operation.createdAt == purchase.postedAt &&
                operation.updatedAt == purchase.postedAt
        }) { "Las operaciones outbox deben quedar pendientes para esta compra" }
        require(batch.movements.all { movement ->
            movement.occurredAt == purchase.postedAt && movement.createdAt == purchase.postedAt
        }) { "Los movimientos deben compartir el timestamp de publicación" }
        require(batch.balanceMutations.all { it.balance.updatedAt == purchase.postedAt }) {
            "Los saldos deben compartir el timestamp de publicación"
        }
    }

    private companion object {
        val POSTING_OUTBOX_OPERATION_TYPES = setOf(
            "SYNC_PURCHASE",
            "SYNC_DOCUMENT_UPLOAD",
        )
    }

    private suspend fun loadPreparedPurchase(batch: PurchasePostingBatch): PreparedPurchase {
        val entity = requireNotNull(findPreparedPurchase(batch.purchase.sourceDraftId)) {
            "No existe una instantánea preparada para el borrador"
        }
        require(entity.logicalHash == batch.expectedPreparedLogicalHash) {
            "El hash preparado ya no coincide"
        }
        require(PreparedPurchaseCodec.supports(entity.payloadCodecVersion)) {
            "La versión de la instantánea preparada no es compatible"
        }
        require(PreparedPurchaseCodec.sha256(entity.payload) == entity.payloadSha256) {
            "El payload preparado no supera su checksum"
        }
        val prepared = runCatching { PreparedPurchaseCodec.decode(entity.payload) }
            .getOrElse { failure ->
                throw IllegalArgumentException("La instantánea preparada está corrupta", failure)
            }
        require(prepared.logicalHash == entity.logicalHash) {
            "El contenido preparado no coincide con su hash lógico"
        }
        return prepared
    }

    private fun validateAgainstPrepared(
        batch: PurchasePostingBatch,
        prepared: PreparedPurchase,
    ) {
        val purchase = batch.purchase
        val document = requireNotNull(InvoiceDocumentNumber.parseCanonical(prepared.documentNumber)) {
            "El documento preparado no tiene formato canónico"
        }
        require(prepared.draftId.value == purchase.sourceDraftId)
        require(prepared.businessId.value == purchase.businessId)
        require(prepared.documentType?.name == purchase.documentType)
        require(document.series == purchase.documentSeries)
        require(document.correlative == purchase.documentNumber)
        require(prepared.issueDate.toString() == purchase.issueDate)
        require(prepared.currency.value == purchase.currencyCode)
        require((prepared.subtotal?.minorUnits ?: 0L) == purchase.subtotalMinorUnits)
        require((prepared.tax?.minorUnits ?: 0L) == purchase.taxMinorUnits)
        require((prepared.otherCharges?.minorUnits ?: 0L) == purchase.otherChargesMinorUnits)
        require(prepared.total.minorUnits == purchase.totalMinorUnits)

        val preparedByPosition = prepared.lines.associateBy { it.position }
        require(preparedByPosition.size == batch.lines.size) {
            "La cantidad de líneas no coincide con la instantánea preparada"
        }
        batch.lines.forEach { line ->
            val frozen = requireNotNull(preparedByPosition[line.position])
            require(frozen.productId.value == line.productId)
            require(frozen.unitId.value == line.unitId)
            require(frozen.rawText == line.rawText)
            require(frozen.description == line.description)
            require(frozen.quantity.value.compareTo(line.quantity.toBigDecimal()) == 0)
            val frozenCost = requireNotNull(frozen.unitCost) {
                "La línea preparada ${line.position} no tiene costo unitario"
            }
            require(frozenCost.amount.compareTo(line.readUnitCost.toBigDecimal()) == 0)
            require(
                (frozen.discount?.toMajor() ?: BigDecimal.ZERO).compareTo(
                    requireNotNull(line.discount) {
                        "La línea ${line.position} no conserva el descuento leído"
                    }.toBigDecimal(),
                ) == 0,
            ) { "El descuento no coincide con la instantánea preparada" }
            require((frozen.tax?.minorUnits ?: 0L) == line.taxMinorUnits)
            require((frozen.lineTotal?.minorUnits ?: 0L) == line.totalMinorUnits)
            require(frozen.linkConfidence == line.confidence)
            require(frozen.productProvenance.name == line.productProvenance) {
                "La procedencia del producto no coincide con la instantanea preparada"
            }
            require(frozen.taxTreatment.name == line.taxTreatment) {
                "El tratamiento tributario no coincide con la instantanea preparada"
            }
            require(frozen.taxEvidence.type.name == line.taxEvidenceType) {
                "El tipo de evidencia tributaria no coincide con la instantanea preparada"
            }
            val frozenEvidenceValue = frozen.taxEvidence.value
            val persistedEvidenceValue = line.taxEvidenceValue?.toBigDecimal()
            require(
                if (frozenEvidenceValue == null || persistedEvidenceValue == null) {
                    frozenEvidenceValue == null && persistedEvidenceValue == null
                } else {
                    frozenEvidenceValue.compareTo(persistedEvidenceValue) == 0
                },
            ) { "La evidencia tributaria no coincide con la instantanea preparada" }
        }
    }

    private fun validateMovementConversions(
        batch: PurchasePostingBatch,
        resolutionByLineId: Map<String, ProductPostingResolution>,
    ) {
        val sign = if (batch.purchase.documentType == PurchaseDocumentType.CREDIT_NOTE.name) {
            BigDecimal.ONE.negate()
        } else {
            BigDecimal.ONE
        }
        batch.lines.forEach { line ->
            val resolution = requireNotNull(resolutionByLineId[line.purchaseLineId])
            val factor = if (line.unitId == resolution.purchaseUnitId) {
                requireNotNull(resolution.purchaseFactor).toBigDecimal()
            } else {
                require(line.unitId == resolution.inventoryUnitId)
                BigDecimal.ONE
            }
            require(line.hasCompleteCostingAudit()) {
                "La línea ${line.position} no congela una decisión de costo completa"
            }
            require(line.taxTreatment != "UNKNOWN") {
                "La línea ${line.position} requiere una decisión tributaria explícita"
            }
            require(requireNotNull(line.purchaseUnitFactor).toBigDecimal().compareTo(factor) == 0) {
                "El factor aplicado no coincide con el catálogo para la línea ${line.position}"
            }
            val lineMovements = batch.movements.filter { it.purchaseLineId == line.purchaseLineId }
            val movementQuantity = lineMovements.sumOfExact { it.quantityDelta.toBigDecimal() }
            val expectedQuantity = line.quantity.toBigDecimal().multiply(factor).multiply(sign)
            require(
                requireNotNull(line.inventoryQuantity).toBigDecimal()
                    .compareTo(expectedQuantity.abs()) == 0,
            ) { "La cantidad de inventario auditada no coincide con la línea ${line.position}" }
            require(movementQuantity.compareTo(expectedQuantity) == 0) {
                "Los movimientos no coinciden con la cantidad convertida de la línea ${line.position}"
            }
            lineMovements.forEach { movement ->
                val movementCost = requireNotNull(movement.unitCost).toBigDecimal()
                require(
                    movementCost.compareTo(requireNotNull(line.appliedUnitCost).toBigDecimal()) == 0,
                ) { "El costo aplicado del movimiento no coincide con la línea ${line.position}" }
            }
        }
    }

    private fun PurchaseLineEntity.hasCompleteCostingAudit(): Boolean =
        appliedUnitCost != null &&
            purchaseUnitFactor != null &&
            inventoryQuantity != null &&
            discount != null &&
            taxTreatment != null &&
            costPolicy != null &&
            appliedCostTotal != null &&
            taxEvidenceType != null &&
            roundingScale != null &&
            roundingMode != null &&
            costingWarnings != null

    /**
     * Recalcula la decisión congelada de cada entrada con el servicio único de dominio. El
     * llamador no puede enviar saldos o costos ya "resueltos" y eludir fórmula, política,
     * evidencia tributaria ni redondeo. Las líneas que llegan al mismo saldo se procesan por
     * posición, haciendo determinista el promedio incluso dentro de una compra multilínea.
     *
     * Las notas de crédito no son entradas: su reversión conserva la validación específica del
     * libro existente y no se disfraza como una cantidad positiva para este servicio.
     */
    private suspend fun validateInventoryCosting(
        batch: PurchasePostingBatch,
        resolutionByLineId: Map<String, ProductPostingResolution>,
    ): Map<StockKey, CostingBalanceExpectation> {
        if (batch.purchase.documentType == PurchaseDocumentType.CREDIT_NOTE.name) {
            return emptyMap()
        }
        val mutationByKey = batch.balanceMutations.associateBy { it.balance.stockKey() }
        val linesByKey = mutableMapOf<StockKey, MutableList<CostingLineExpectation>>()
        val currency = CurrencyCode.of(batch.purchase.currencyCode)

        batch.lines.sortedBy(PurchaseLineEntity::position).forEach { line ->
            require(line.hasCompleteCostingAudit()) {
                "La línea ${line.position} no congela una decisión de costo completa"
            }
            val lineMovements = batch.movements.filter { movement ->
                movement.purchaseLineId == line.purchaseLineId
            }
            val movementKeys = lineMovements.map { movement -> movement.stockKey() }.distinct()
            require(movementKeys.size == 1) {
                "Una línea debe ingresar a un único destino para conservar el costo total exacto"
            }
            val key = movementKeys.single()
            val mutation = requireNotNull(mutationByKey[key]) {
                "No existe mutación de saldo para la línea ${line.position}"
            }
            val resolution = requireNotNull(resolutionByLineId[line.purchaseLineId])
            val catalogFactor = if (line.unitId == resolution.purchaseUnitId) {
                requireNotNull(resolution.purchaseFactor).toBigDecimal()
            } else {
                BigDecimal.ONE
            }
            val request = InventoryCostingRequest(
                currency = currency,
                purchaseQuantity = line.quantity.toBigDecimal(),
                purchaseUnitFactor = catalogFactor,
                readPurchaseUnitCost = line.readUnitCost.toBigDecimal(),
                lineDiscount = requireNotNull(line.discount).toBigDecimal(),
                taxTreatment = InventoryTaxTreatment.valueOf(requireNotNull(line.taxTreatment)),
                taxEvidence = line.toTaxEvidence(),
                costPolicy = CostPolicy.valueOf(requireNotNull(line.costPolicy)),
                // El cálculo de línea valida conversión/tributo/costo aplicado. El promedio del
                // saldo se calcula una sola vez con el agregado exacto más abajo.
                previousQuantity = BigDecimal.ZERO,
                previousAverageUnitCost = BigDecimal.ZERO,
                roundingPolicy = InventoryCostRoundingPolicy(
                    scale = requireNotNull(line.roundingScale),
                    mode = RoundingMode.valueOf(requireNotNull(line.roundingMode)),
                ),
            )
            val result = inventoryCostingService.calculate(request)
            val calculation = when (result) {
                is InventoryCostingResult.Calculated -> result.calculation
                is InventoryCostingResult.DecisionRequired -> throw IllegalArgumentException(
                    "La línea ${line.position} requiere decisión de costo: " +
                        result.reasons.sortedBy(Enum<*>::name).joinToString { it.name },
                )
            }

            require(calculation.inventoryQuantity.compareTo(
                requireNotNull(line.inventoryQuantity).toBigDecimal(),
            ) == 0) { "La cantidad auditada no coincide con la línea ${line.position}" }
            require(calculation.appliedInventoryUnitCost.compareTo(
                requireNotNull(line.appliedUnitCost).toBigDecimal(),
            ) == 0) { "El costo unitario aplicado no coincide con la línea ${line.position}" }
            require(calculation.appliedCostTotal.compareTo(
                requireNotNull(line.appliedCostTotal).toBigDecimal(),
            ) == 0) { "El costo total aplicado no coincide con la línea ${line.position}" }
            // Un importe explícito debe coincidir con el IGV monetario leído. Una tasa es la
            // evidencia elegida: puede producir fracciones sub-centavo que taxMinorUnits no
            // representa, por lo que se conserva la lectura pero no se la confunde con el
            // impuesto exacto aplicado.
            if (calculation.taxEvidence is InventoryTaxEvidence.ExplicitAmount) {
                require(calculation.taxTotal.compareTo(
                    Money.ofMinor(line.taxMinorUnits, currency).toMajor(),
                ) == 0) { "El impuesto calculado no coincide con la línea ${line.position}" }
            }
            val lineWarnings = calculation.warnings.filterNot { warning ->
                warning.name == "PREVIOUS_NON_POSITIVE_BALANCE_REBASED"
            }
            val movementQuantity = lineMovements.sumOfExact {
                it.quantityDelta.toBigDecimal()
            }
            require(movementQuantity.compareTo(calculation.inventoryQuantity) == 0) {
                "El movimiento no coincide con el cálculo de la línea ${line.position}"
            }
            lineMovements.forEach { movement ->
                require(requireNotNull(movement.unitCost).toBigDecimal().compareTo(
                    calculation.appliedInventoryUnitCost,
                ) == 0) { "El movimiento no conserva el costo aplicado de la línea ${line.position}" }
            }
            linesByKey.getOrPut(key, ::mutableListOf) += CostingLineExpectation(
                inventoryQuantity = calculation.inventoryQuantity,
                appliedCostTotal = calculation.appliedCostTotal,
                roundingPolicy = calculation.roundingPolicy,
                lineWarnings = lineWarnings.map(Enum<*>::name).toSet(),
                persistedWarnings = requireNotNull(line.costingWarnings)
                    .takeUnless(String::isEmpty)?.split(',')?.toSet().orEmpty(),
                position = line.position,
            )
        }
        require(linesByKey.keys == mutationByKey.keys) {
            "Cada saldo debe tener al menos una línea de costo calculada"
        }
        return linesByKey.mapValues { (key, costingLines) ->
            val mutation = requireNotNull(mutationByKey[key])
            val opening = loadCostingOpeningBalance(mutation)
            val roundingPolicies = costingLines.map(CostingLineExpectation::roundingPolicy).distinct()
            require(roundingPolicies.size == 1) {
                "Las líneas de un mismo saldo deben usar el mismo redondeo"
            }
            val averageResult = inventoryCostingService.calculateAverage(
                InventoryAverageCostRequest(
                    previousQuantity = opening.quantity,
                    previousAverageUnitCost = opening.averageUnitCost,
                    incomingInventoryQuantity = costingLines.sumOfExact {
                        it.inventoryQuantity
                    },
                    incomingAppliedCostTotal = costingLines.sumOfExact {
                        it.appliedCostTotal
                    },
                    roundingPolicy = roundingPolicies.single(),
                ),
            )
            val average = when (averageResult) {
                is InventoryAverageCostResult.Calculated -> averageResult.calculation
                is InventoryAverageCostResult.DecisionRequired -> throw IllegalArgumentException(
                    "El promedio requiere decisión: " +
                        averageResult.reasons.sortedBy(Enum<*>::name).joinToString { it.name },
                )
            }
            val averageWarnings = average.warnings.map(Enum<*>::name).toSet()
            costingLines.forEach { costingLine ->
                require(
                    costingLine.persistedWarnings == costingLine.lineWarnings + averageWarnings,
                ) {
                    "Las advertencias de costo no coinciden con la línea " +
                        costingLine.position
                }
            }
            CostingBalanceExpectation(
                quantity = average.resultingQuantity,
                averageUnitCost = average.resultingAverageUnitCost,
            )
        }
    }

    private suspend fun loadCostingOpeningBalance(
        mutation: InventoryBalanceMutation,
    ): CostingBalanceExpectation {
        val balance = mutation.balance
        val current = findBalance(balance.businessId, balance.productId, balance.locationId)
        return if (mutation.expectedVersion == null) {
            // La escritura optimista validará `insertBalanceIfAbsent`. No se usa un saldo que el
            // llamador declaró inexistente: hacerlo convertiría una carrera en un promedio
            // silencioso. El grafo completo se revierte si la fila ya existe al aplicar.
            CostingBalanceExpectation(BigDecimal.ZERO, BigDecimal.ZERO)
        } else {
            val stored = requireNotNull(current) { "El saldo esperado ya no existe" }
            require(stored.version == mutation.expectedVersion) {
                "El saldo cambió antes de calcular el costo"
            }
            require(stored.currencyCode == balance.currencyCode) {
                "No se puede calcular una entrada en otra moneda"
            }
            CostingBalanceExpectation(
                quantity = stored.quantityOnHand.toBigDecimal(),
                averageUnitCost = stored.averageUnitCost.toBigDecimal(),
            )
        }
    }

    private fun PurchaseLineEntity.toTaxEvidence(): InventoryTaxEvidence =
        when (InventoryTaxEvidenceType.valueOf(requireNotNull(taxEvidenceType))) {
            InventoryTaxEvidenceType.NONE -> {
                require(taxEvidenceValue == null) { "La evidencia NONE no admite valor" }
                InventoryTaxEvidence.None
            }
            InventoryTaxEvidenceType.EXPLICIT_AMOUNT -> InventoryTaxEvidence.ExplicitAmount(
                requireNotNull(taxEvidenceValue).toBigDecimal(),
            )
            InventoryTaxEvidenceType.EXPLICIT_RATE -> InventoryTaxEvidence.ExplicitRate(
                requireNotNull(taxEvidenceValue).toBigDecimal(),
            )
        }

    private fun expectedQuantityFor(
        balance: InventoryBalanceEntity,
        currentQuantity: BigDecimal,
        movements: List<StockMovementEntity>,
    ): BigDecimal {
        val delta = movements
            .asSequence()
            .filter { it.productId == balance.productId && it.locationId == balance.locationId }
            .map { it.quantityDelta.toBigDecimal() }
            .fold(BigDecimal.ZERO, BigDecimal::add)
        return currentQuantity.add(delta)
    }

    private fun expectedAverageCostForNewBalance(
        balance: InventoryBalanceEntity,
        movements: List<StockMovementEntity>,
    ): BigDecimal {
        val matching = movements.filter { movement -> balance.matches(movement) }
        val quantity = matching.sumOfExact { it.quantityDelta.toBigDecimal() }
        require(quantity.signum() > 0) { "El saldo inicial requiere cantidad positiva" }
        val value = matching.sumOfExact { movement ->
            movement.quantityDelta.toBigDecimal().multiply(
                requireNotNull(movement.unitCost).toBigDecimal(),
            )
        }
        return value.divide(quantity, INVENTORY_COST_SCALE, RoundingMode.HALF_EVEN)
    }

    private fun expectedAverageCostForExistingBalance(
        current: InventoryBalanceEntity,
        balance: InventoryBalanceEntity,
        movements: List<StockMovementEntity>,
    ): BigDecimal {
        val currentQuantity = current.quantityOnHand.toBigDecimal()
        val matching = movements.filter { movement -> balance.matches(movement) }
        val movementQuantity = matching.sumOfExact { it.quantityDelta.toBigDecimal() }
        val finalQuantity = currentQuantity.add(movementQuantity)
        require(finalQuantity.signum() >= 0) { "El saldo final no puede ser negativo" }
        if (finalQuantity.signum() == 0) {
            return BigDecimal.ZERO
        }
        val currentValue = currentQuantity.multiply(current.averageUnitCost.toBigDecimal())
        val movementValue = matching.sumOfExact { movement ->
            movement.quantityDelta.toBigDecimal().multiply(
                requireNotNull(movement.unitCost).toBigDecimal(),
            )
        }
        return currentValue.add(movementValue).divide(
            finalQuantity,
            INVENTORY_COST_SCALE,
            RoundingMode.HALF_EVEN,
        )
    }

    private fun InventoryBalanceEntity.matches(movement: StockMovementEntity): Boolean =
        productId == movement.productId && locationId == movement.locationId

    private inline fun <T> Iterable<T>.sumOfExact(transform: (T) -> BigDecimal): BigDecimal =
        fold(BigDecimal.ZERO) { total, value -> total.add(transform(value)) }

    private fun InventoryBalanceEntity.stockKey(): StockKey =
        StockKey(productId = productId, locationId = locationId)

    private fun StockMovementEntity.stockKey(): StockKey =
        StockKey(productId = productId, locationId = locationId)

    private data class StockKey(
        val productId: String,
        val locationId: String,
    )

    private data class CostingBalanceExpectation(
        val quantity: BigDecimal,
        val averageUnitCost: BigDecimal,
    )

    private data class CostingLineExpectation(
        val inventoryQuantity: BigDecimal,
        val appliedCostTotal: BigDecimal,
        val roundingPolicy: InventoryCostRoundingPolicy,
        val lineWarnings: Set<String>,
        val persistedWarnings: Set<String>,
        val position: Int,
    )

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertPurchase(purchase: PurchaseEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertLines(lines: List<PurchaseLineEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertBalanceIfAbsent(balance: InventoryBalanceEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertMovements(movements: List<StockMovementEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertAuditEvents(events: List<AuditEventEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertOutboxOperations(operations: List<OutboxOperationEntity>)

    @Query(
        "SELECT EXISTS(SELECT 1 FROM suppliers WHERE supplierId = :supplierId " +
            "AND businessId = :businessId AND ruc = :supplierRuc AND " +
            "(:preparedSupplierId IS NULL OR supplierId = :preparedSupplierId))",
    )
    protected abstract suspend fun supplierMatchesPreparedPurchase(
        supplierId: String,
        businessId: String,
        preparedSupplierId: String?,
        supplierRuc: String,
    ): Boolean

    @Query(
        "SELECT EXISTS(SELECT 1 FROM invoice_drafts d " +
            "JOIN prepared_purchases pp ON pp.draftId = d.draftId " +
            "WHERE d.draftId = :draftId AND d.businessId = :businessId " +
            "AND d.status = :readyStatus AND d.confirmedPurchaseId IS NULL " +
            "AND pp.logicalHash = :logicalHash)",
    )
    protected abstract suspend fun draftMatchesPreparedPurchase(
        draftId: String,
        businessId: String,
        readyStatus: String,
        logicalHash: String,
    ): Boolean

    @Query(
        "SELECT p.productId AS productId, p.unitId AS inventoryUnitId, " +
            "p.purchaseUnitId AS purchaseUnitId, p.purchaseFactor AS purchaseFactor " +
            "FROM products p JOIN units u ON u.unitId = :unitId " +
            "WHERE p.productId = :productId AND p.businessId = :businessId " +
            "AND u.businessId = :businessId AND p.status = :activeStatus " +
            "AND u.status = :activeStatus AND " +
            "(p.unitId = :unitId OR p.purchaseUnitId = :unitId)",
    )
    protected abstract suspend fun findProductPostingResolution(
        productId: String,
        unitId: String,
        businessId: String,
        activeStatus: String,
    ): ProductPostingResolution?

    @Query("SELECT * FROM prepared_purchases WHERE draftId = :draftId")
    protected abstract suspend fun findPreparedPurchase(draftId: String): PreparedPurchaseEntity?

    @Query(
        "SELECT EXISTS(SELECT 1 FROM products p " +
            "JOIN inventory_locations l ON l.locationId = :locationId " +
            "WHERE p.productId = :productId AND p.businessId = :businessId " +
            "AND l.businessId = :businessId AND p.status = :activeStatus " +
            "AND l.status = :activeStatus)",
    )
    protected abstract suspend fun stockTargetBelongsToBusiness(
        productId: String,
        locationId: String,
        businessId: String,
        activeStatus: String,
    ): Boolean

    @Query(
        "UPDATE invoice_drafts SET confirmedPurchaseId = :purchaseId, " +
            "updatedAt = MAX(updatedAt, :updatedAt) WHERE draftId = :draftId " +
            "AND businessId = :businessId AND status = :readyStatus " +
            "AND confirmedPurchaseId IS NULL AND EXISTS (" +
            "SELECT 1 FROM prepared_purchases WHERE draftId = :draftId " +
            "AND logicalHash = :logicalHash)",
    )
    protected abstract suspend fun linkPreparedDraft(
        draftId: String,
        businessId: String,
        purchaseId: String,
        readyStatus: String,
        logicalHash: String,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE invoice_drafts SET status = :committedStatus, " +
            "updatedAt = MAX(updatedAt, :updatedAt) WHERE draftId = :draftId " +
            "AND businessId = :businessId AND status = :readyStatus " +
            "AND confirmedPurchaseId = :purchaseId AND EXISTS (" +
            "SELECT 1 FROM prepared_purchases WHERE draftId = :draftId " +
            "AND logicalHash = :logicalHash) AND EXISTS (" +
            "SELECT 1 FROM purchases WHERE purchaseId = :purchaseId " +
            "AND businessId = :businessId AND sourceDraftId = :draftId " +
            "AND status = :postedStatus)",
    )
    protected abstract suspend fun markDraftCommitted(
        draftId: String,
        businessId: String,
        purchaseId: String,
        logicalHash: String,
        readyStatus: String,
        committedStatus: String,
        postedStatus: String,
        updatedAt: Long,
    ): Int

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
            "version = :newVersion, updatedAt = :updatedAt " +
            "WHERE businessId = :businessId AND productId = :productId " +
            "AND locationId = :locationId AND version = :expectedVersion " +
            "AND :updatedAt >= updatedAt",
    )
    protected abstract suspend fun updateBalanceIfVersion(
        businessId: String,
        productId: String,
        locationId: String,
        expectedVersion: Long,
        quantityOnHand: String,
        averageUnitCost: String,
        currencyCode: String,
        newVersion: Long,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE purchases SET status = :postedStatus, postedAt = :postedAt, " +
            "updatedAt = :updatedAt WHERE purchaseId = :purchaseId " +
            "AND businessId = :businessId AND status = :draftStatus " +
            "AND updatedAt = :expectedUpdatedAt AND postedAt IS NULL AND voidedAt IS NULL",
    )
    protected abstract suspend fun markPosted(
        purchaseId: String,
        businessId: String,
        expectedUpdatedAt: Long,
        postedAt: Long,
        updatedAt: Long,
        draftStatus: String,
        postedStatus: String,
    ): Int
}
