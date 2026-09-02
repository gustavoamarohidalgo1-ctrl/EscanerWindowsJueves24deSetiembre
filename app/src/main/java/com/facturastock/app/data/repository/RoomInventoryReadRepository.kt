package com.facturastock.app.data.repository

import androidx.room.withTransaction
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.dao.InventoryDiagnosticBalanceRow
import com.facturastock.app.data.local.dao.InventoryDiagnosticMovementRow
import com.facturastock.app.data.local.dao.InventoryMovementReadRow
import com.facturastock.app.data.local.dao.InventoryPositionReadRow
import com.facturastock.app.data.local.dao.InventoryProductHeaderReadRow
import com.facturastock.app.data.local.storageCatching
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.InventoryAverageCostRequest
import com.facturastock.app.domain.model.InventoryAverageCostResult
import com.facturastock.app.domain.model.InventoryCostRoundingPolicy
import com.facturastock.app.domain.model.InventoryCostAmount
import com.facturastock.app.domain.model.InventoryCostingDecimalPolicy
import com.facturastock.app.domain.model.InventoryDataAlert
import com.facturastock.app.domain.model.InventoryDiagnosticIssue
import com.facturastock.app.domain.model.InventoryDiagnosticPosition
import com.facturastock.app.domain.model.InventoryDiagnosticReport
import com.facturastock.app.domain.model.InventoryProductDetail
import com.facturastock.app.domain.model.InventoryReadItem
import com.facturastock.app.domain.model.InventoryReadMovement
import com.facturastock.app.domain.model.InventoryReadPosition
import com.facturastock.app.domain.model.StockMovementType
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.model.id.SaleId
import com.facturastock.app.domain.repository.InventoryReadRepository
import com.facturastock.app.domain.usecase.InventoryCostingService
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext

class RoomInventoryReadRepository @Inject constructor(
    private val database: FacturaStockDatabase,
    private val appClock: AppClock,
    private val dispatchers: DispatcherProvider,
) : InventoryReadRepository {
    private val costingService = InventoryCostingService()

    override fun observeInventory(businessId: BusinessId): Flow<List<InventoryReadItem>> =
        database.inventoryDao().observeReadPositions(businessId.value)
            .map(::mapInventoryItems)
            // Una mutacion de inventario ajena al negocio invalida la tabla completa en Room;
            // suprimir snapshots iguales reduce recomposiciones y trabajo de proyeccion.
            .distinctUntilChanged()
            .flowOn(dispatchers.io)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun observeProduct(
        businessId: BusinessId,
        productId: ProductId,
    ): Flow<InventoryProductDetail?> = database.inventoryDao()
        .observeProductRevision(businessId.value, productId.value)
        .mapLatest { revision ->
            if (revision == null) {
                null
            } else {
                database.withTransaction {
                    val header = database.inventoryDao().findReadProductHeader(
                        businessId.value,
                        productId.value,
                    ) ?: return@withTransaction null
                    val positionRows = database.inventoryDao().listReadPositionsForProduct(
                        businessId.value,
                        productId.value,
                    )
                    val movementRows = database.inventoryDao().listReadMovementsForProduct(
                        businessId.value,
                        productId.value,
                    )
                    InventoryProductDetail(
                        item = header.toItem(positionRows),
                        movements = movementRows.map(InventoryMovementReadRow::toDomain),
                    )
                }
            }
        }
        .flowOn(dispatchers.io)

    override suspend fun diagnose(businessId: BusinessId): InventoryDiagnosticReport =
        withContext(dispatchers.io) {
            storageCatching {
                database.withTransaction {
                    buildDiagnosticReport(
                        balances = database.inventoryDao().listDiagnosticBalances(businessId.value),
                        movements = database.inventoryDao().listDiagnosticMovements(businessId.value),
                        generatedAt = appClock.now(),
                        costingService = costingService,
                    )
                }
            }
        }
}

private data class InventoryItemAccumulator(
    val first: InventoryPositionReadRow,
    val positions: MutableList<InventoryReadPosition> = mutableListOf(),
)

private fun mapInventoryItems(rows: List<InventoryPositionReadRow>): List<InventoryReadItem> {
    if (rows.isEmpty()) return emptyList()
    // Convierte cada fila mientras se agrupa. `groupBy` retenia una segunda coleccion completa
    // de filas y luego creaba una tercera lista de posiciones, elevando el pico de memoria.
    val grouped = LinkedHashMap<String, InventoryItemAccumulator>()
    rows.forEach { row ->
        grouped.getOrPut(row.productId) { InventoryItemAccumulator(row) }
            .positions += row.toPosition()
    }
    return grouped.values.map { accumulator ->
        val first = accumulator.first
        InventoryReadItem(
            productId = parseProductId(first.productId),
            businessId = parseBusinessId(first.businessId),
            productName = first.productName,
            sku = first.sku,
            unitCode = first.unitCode,
            unitSymbol = first.unitSymbol,
            positions = accumulator.positions,
            alerts = buildSet {
                if (first.productStatus != CatalogStatus.ACTIVE.name) {
                    add(InventoryDataAlert.ARCHIVED_PRODUCT)
                }
            },
        )
    }
}

private fun InventoryProductHeaderReadRow.toItem(
    positions: List<InventoryPositionReadRow>,
): InventoryReadItem = InventoryReadItem(
    productId = parseProductId(productId),
    businessId = parseBusinessId(businessId),
    productName = productName,
    sku = sku,
    unitCode = unitCode,
    unitSymbol = unitSymbol,
    positions = positions.map(InventoryPositionReadRow::toPosition),
    alerts = buildSet {
        if (productStatus != CatalogStatus.ACTIVE.name) {
            add(InventoryDataAlert.ARCHIVED_PRODUCT)
        }
    },
)

private fun InventoryPositionReadRow.toPosition(): InventoryReadPosition {
    val currency = CurrencyCode.of(currencyCode)
    val quantity = decimal(quantityOnHand, "quantityOnHand")
    return InventoryReadPosition(
        locationId = parseLocationId(locationId),
        locationName = locationName,
        quantityOnHand = quantity,
        averageUnitCost = InventoryCostAmount(decimal(averageUnitCost, "averageUnitCost"), currency),
        version = version,
        updatedAt = Instant.ofEpochMilli(updatedAt),
        alerts = buildSet {
            if (quantity.signum() < 0) add(InventoryDataAlert.NEGATIVE_STOCK)
            if (locationStatus != CatalogStatus.ACTIVE.name) {
                add(InventoryDataAlert.ARCHIVED_LOCATION)
            }
        },
    )
}

private fun InventoryMovementReadRow.toDomain(): InventoryReadMovement {
    val currency = CurrencyCode.of(currencyCode)
    val parsedPurchaseId = purchaseId?.let(::parsePurchaseId)
    return InventoryReadMovement(
        movementId = movementId,
        productId = parseProductId(productId),
        locationId = parseLocationId(locationId),
        locationName = locationName,
        type = enumValue(type, "movement type"),
        quantityDelta = decimal(quantityDelta, "quantityDelta"),
        unitCost = unitCost?.let { InventoryCostAmount(decimal(it, "unitCost"), currency) },
        purchaseId = parsedPurchaseId,
        purchaseDocumentNumber = purchaseDocumentNumber,
        occurredAt = Instant.ofEpochMilli(occurredAt),
        createdAt = Instant.ofEpochMilli(createdAt),
        alerts = if (unitCost == null) {
            setOf(InventoryDataAlert.MISSING_MOVEMENT_COST)
        } else {
            emptySet()
        },
        saleId = saleId?.let(::parseSaleId),
    )
}

private data class DiagnosticKey(val productId: String, val locationId: String)

private data class ReplayState(
    val quantity: BigDecimal?,
    val averageUnitCost: BigDecimal?,
    val issues: Set<InventoryDiagnosticIssue>,
)

private data class MovementBatchKey(
    val occurredAt: Long,
    val createdAt: Long,
    val purchaseId: String?,
    val saleId: String?,
    val standaloneMovementId: String?,
)

private fun buildDiagnosticReport(
    balances: List<InventoryDiagnosticBalanceRow>,
    movements: List<InventoryDiagnosticMovementRow>,
    generatedAt: Instant,
    costingService: InventoryCostingService,
): InventoryDiagnosticReport {
    val balancesByKey = balances.associateBy { DiagnosticKey(it.productId, it.locationId) }
    val movementsByKey = movements.groupBy { DiagnosticKey(it.productId, it.locationId) }
    val keys = (balancesByKey.keys + movementsByKey.keys).sortedWith(
        compareBy(DiagnosticKey::productId, DiagnosticKey::locationId),
    )
    val positions = keys.map { key ->
        val balance = balancesByKey[key]
        val ledger = movementsByKey[key].orEmpty()
        diagnosePosition(key, balance, ledger, costingService)
    }
    return InventoryDiagnosticReport(generatedAt = generatedAt, positions = positions)
}

private fun diagnosePosition(
    key: DiagnosticKey,
    balance: InventoryDiagnosticBalanceRow?,
    movements: List<InventoryDiagnosticMovementRow>,
    costingService: InventoryCostingService,
): InventoryDiagnosticPosition {
    val issues = linkedSetOf<InventoryDiagnosticIssue>()
    if (balance == null) issues += InventoryDiagnosticIssue.MISSING_CACHED_BALANCE

    val cachedQuantity = balance?.quantityOnHand.toDecimalOrIssue(issues)
    val cachedAverage = balance?.averageUnitCost.toDecimalOrIssue(issues, nonNegative = true)
    val balanceCurrency = balance?.currencyCode.toCurrencyOrIssue(issues)
    val movementCurrencies = movements.mapNotNull { row ->
        row.currencyCode.toCurrencyOrIssue(issues)
    }.toSet()
    val currency = balanceCurrency ?: movementCurrencies.singleOrNull()
    if (movementCurrencies.size > 1 || (balanceCurrency != null && movementCurrencies.any {
            it != balanceCurrency
        })
    ) {
        issues += InventoryDiagnosticIssue.CURRENCY_DIVERGENCE
    }

    val replay = replayMovements(movements, costingService)
    issues += replay.issues
    var ledgerAverage = replay.averageUnitCost
    val currencyMismatch = InventoryDiagnosticIssue.CURRENCY_DIVERGENCE in issues
    when {
        balance == null || cachedQuantity == null || replay.quantity == null -> {
            ledgerAverage = null
            issues += InventoryDiagnosticIssue.COST_UNVERIFIABLE
        }
        cachedQuantity.compareTo(replay.quantity) != 0 -> {
            issues += InventoryDiagnosticIssue.QUANTITY_DIVERGENCE
            issues += InventoryDiagnosticIssue.UNEXPLAINED_OPENING_BALANCE
            issues += InventoryDiagnosticIssue.COST_UNVERIFIABLE
            ledgerAverage = null
        }
        currencyMismatch || InventoryDiagnosticIssue.INVALID_LEDGER_DATA in issues -> {
            issues += InventoryDiagnosticIssue.COST_UNVERIFIABLE
            ledgerAverage = null
        }
    }
    if (
        balance != null && cachedAverage != null && ledgerAverage != null &&
        cachedAverage.compareTo(ledgerAverage) != 0
    ) {
        issues += InventoryDiagnosticIssue.AVERAGE_COST_DIVERGENCE
    }

    val exemplar = balance ?: movements.firstOrNull()
        ?: corrupt("Clave diagnostica sin saldo ni movimientos")
    return InventoryDiagnosticPosition(
        productId = parseProductId(key.productId),
        productName = when (exemplar) {
            is InventoryDiagnosticBalanceRow -> exemplar.productName
            is InventoryDiagnosticMovementRow -> exemplar.productName
            else -> corrupt("Tipo diagnostico inesperado")
        },
        locationId = parseLocationId(key.locationId),
        locationName = when (exemplar) {
            is InventoryDiagnosticBalanceRow -> exemplar.locationName
            is InventoryDiagnosticMovementRow -> exemplar.locationName
            else -> corrupt("Tipo diagnostico inesperado")
        },
        currency = currency,
        cachedQuantity = cachedQuantity,
        ledgerQuantity = replay.quantity,
        cachedAverageUnitCost = cachedAverage,
        ledgerAverageUnitCost = ledgerAverage,
        movementCount = movements.size,
        cachedVersion = balance?.version,
        cachedUpdatedAt = balance?.updatedAt?.let(Instant::ofEpochMilli),
        issues = issues,
    )
}

/**
 * PURCHASE v13+ se reproduce por compra, usando appliedCostTotal exacto de la linea congelada.
 * unitCost del movimiento esta redondeado y no basta para reconstruir el saldo. VOID,
 * ADJUSTMENT, historia legacy y empates causales conservan cantidad exacta, pero declaran el
 * costo no verificable para evitar falsos positivos.
 */
private fun replayMovements(
    movements: List<InventoryDiagnosticMovementRow>,
    costingService: InventoryCostingService,
): ReplayState {
    val issues = linkedSetOf<InventoryDiagnosticIssue>()
    val parsed = movements.mapNotNull { row ->
        val delta = row.quantityDelta.toDecimalOrIssue(issues) ?: return@mapNotNull null
        val type = runCatching { enumValue<StockMovementType>(row.type, "movement type") }
            .getOrElse {
                issues += InventoryDiagnosticIssue.INVALID_LEDGER_DATA
                return@mapNotNull null
        }
        ParsedDiagnosticMovement(row, delta, type)
    }
    val quantity = if (parsed.size == movements.size) {
        parsed.fold(BigDecimal.ZERO) { total, movement -> total.add(movement.delta) }
    } else {
        null
    }
    var average: BigDecimal? = if (quantity == null) null else BigDecimal.ZERO

    val purchaseLineIds = parsed.filter { it.type == StockMovementType.PURCHASE }
        .mapNotNull { it.row.purchaseLineId }
    if (purchaseLineIds.distinct().size != purchaseLineIds.size) {
        issues += InventoryDiagnosticIssue.INVALID_LEDGER_DATA
        issues += InventoryDiagnosticIssue.COST_UNVERIFIABLE
        average = null
    }

    val groups = parsed.groupBy { movement ->
        val row = movement.row
        MovementBatchKey(
            occurredAt = row.occurredAt,
            createdAt = row.createdAt,
            purchaseId = row.purchaseId,
            saleId = row.saleId,
            standaloneMovementId = if (row.purchaseId == null && row.saleId == null) {
                row.movementId
            } else {
                null
            },
        )
    }.entries.sortedWith(
        compareBy(
            { it.key.occurredAt },
            { it.key.createdAt },
            { it.key.purchaseId.orEmpty() },
            { it.key.saleId.orEmpty() },
            { it.key.standaloneMovementId.orEmpty() },
        ),
    )
    if (groups.zipWithNext().any { (first, second) ->
            first.key.occurredAt == second.key.occurredAt &&
                first.key.createdAt == second.key.createdAt
        }
    ) {
        issues += InventoryDiagnosticIssue.COST_UNVERIFIABLE
        issues += InventoryDiagnosticIssue.ORDER_AMBIGUOUS
        average = null
    }

    var runningQuantity = BigDecimal.ZERO
    groups.forEach { (_, batch) ->
        if (batch.all { it.type == StockMovementType.SALE }) {
            val validSale = batch.all { movement ->
                val movementCost = movement.row.unitCost
                    .toDecimalOrIssue(issues, nonNegative = true)
                movement.delta.signum() < 0 && movement.row.saleId != null &&
                    movement.row.saleLineId != null && movementCost != null &&
                    (average == null || movementCost.compareTo(average) == 0)
            }
            val outgoing = batch.fold(BigDecimal.ZERO) { total, movement ->
                total.add(movement.delta)
            }
            if (!validSale || runningQuantity.add(outgoing).signum() < 0) {
                issues += InventoryDiagnosticIssue.INVALID_LEDGER_DATA
                issues += InventoryDiagnosticIssue.COST_UNVERIFIABLE
                average = null
            }
            runningQuantity = runningQuantity.add(outgoing)
            return@forEach
        }
        val replayable = batch.toReplayablePurchase(issues)
        if (replayable == null || average == null) {
            if (batch.any { it.row.unitCost == null }) {
                issues += InventoryDiagnosticIssue.MISSING_MOVEMENT_COST
            }
            issues += InventoryDiagnosticIssue.COST_UNVERIFIABLE
            runningQuantity = batch.fold(runningQuantity) { total, movement ->
                total.add(movement.delta)
            }
            average = null
            return@forEach
        }
        val result = runCatching {
            costingService.calculateAverage(
                InventoryAverageCostRequest(
                    previousQuantity = runningQuantity,
                    previousAverageUnitCost = requireNotNull(average),
                    incomingInventoryQuantity = replayable.quantity,
                    incomingAppliedCostTotal = replayable.appliedCostTotal,
                    roundingPolicy = replayable.rounding,
                ),
            )
        }.getOrNull()
        when (result) {
            is InventoryAverageCostResult.Calculated -> {
                runningQuantity = result.calculation.resultingQuantity
                average = result.calculation.resultingAverageUnitCost
            }
            else -> {
                runningQuantity = runningQuantity.add(replayable.quantity)
                average = null
                issues += InventoryDiagnosticIssue.COST_UNVERIFIABLE
            }
        }
    }
    if (movements.isEmpty()) {
        issues += InventoryDiagnosticIssue.UNEXPLAINED_OPENING_BALANCE
        average = null
    }
    return ReplayState(quantity = quantity, averageUnitCost = average, issues = issues)
}

private data class ReplayablePurchaseBatch(
    val quantity: BigDecimal,
    val appliedCostTotal: BigDecimal,
    val rounding: InventoryCostRoundingPolicy,
)

private fun List<ParsedDiagnosticMovement>.toReplayablePurchase(
    issues: MutableSet<InventoryDiagnosticIssue>,
): ReplayablePurchaseBatch? {
    if (isEmpty() || any { it.type != StockMovementType.PURCHASE || it.delta.signum() <= 0 }) {
        issues += InventoryDiagnosticIssue.COST_UNVERIFIABLE
        return null
    }
    var valid = true
    var quantity = BigDecimal.ZERO
    var appliedCostTotal = BigDecimal.ZERO
    val roundingPolicies = linkedSetOf<InventoryCostRoundingPolicy>()
    forEach { movement ->
        val row = movement.row
        if (row.purchaseId == null || row.purchaseLineId == null) valid = false
        val frozenQuantity = row.inventoryQuantity.toDecimalOrIssue(issues, nonNegative = true)
        val purchaseQuantity = row.purchaseQuantity.toDecimalOrIssue(issues, nonNegative = true)
        val factor = row.purchaseUnitFactor.toDecimalOrIssue(issues, nonNegative = true)
        val costTotal = row.appliedCostTotal.toDecimalOrIssue(issues, nonNegative = true)
        val movementUnitCost = row.unitCost.toDecimalOrIssue(issues, nonNegative = true)
        val frozenUnitCost = row.appliedUnitCost.toDecimalOrIssue(issues, nonNegative = true)
        val scale = row.roundingScale
        val mode = row.roundingMode?.let { raw ->
            runCatching { RoundingMode.valueOf(raw) }.getOrNull()
        }
        if (
            frozenQuantity == null || purchaseQuantity == null || factor == null ||
            costTotal == null || movementUnitCost == null || frozenUnitCost == null ||
            scale !in 0..18 || mode == null
        ) {
            if (row.unitCost == null) issues += InventoryDiagnosticIssue.MISSING_MOVEMENT_COST
            valid = false
            return@forEach
        }
        if (
            movement.delta.compareTo(frozenQuantity) != 0 ||
            purchaseQuantity.multiply(factor).compareTo(frozenQuantity) != 0 ||
            movementUnitCost.compareTo(frozenUnitCost) != 0
        ) {
            valid = false
        }
        quantity = quantity.add(frozenQuantity)
        appliedCostTotal = appliedCostTotal.add(costTotal)
        roundingPolicies += InventoryCostRoundingPolicy(requireNotNull(scale), mode)
    }
    if (!valid || roundingPolicies.size != 1 || quantity.signum() <= 0) {
        issues += InventoryDiagnosticIssue.INVALID_LEDGER_DATA
        issues += InventoryDiagnosticIssue.COST_UNVERIFIABLE
        return null
    }
    return ReplayablePurchaseBatch(
        quantity = quantity,
        appliedCostTotal = appliedCostTotal,
        rounding = roundingPolicies.single(),
    )
}

private data class ParsedDiagnosticMovement(
    val row: InventoryDiagnosticMovementRow,
    val delta: BigDecimal,
    val type: StockMovementType,
)

private fun String?.toDecimalOrIssue(
    issues: MutableSet<InventoryDiagnosticIssue>,
    nonNegative: Boolean = false,
): BigDecimal? {
    val value = this ?: return null
    if (value.length > MAX_COSTING_TEXT || !SIGNED_PLAIN_DECIMAL.matches(value)) {
        issues += InventoryDiagnosticIssue.INVALID_LEDGER_DATA
        return null
    }
    return try {
        BigDecimal(value).also { decimal ->
            if (
                !InventoryCostingDecimalPolicy.supportsPersisted(decimal) ||
                (nonNegative && decimal.signum() < 0)
            ) {
                issues += InventoryDiagnosticIssue.INVALID_LEDGER_DATA
                return null
            }
        }
    } catch (_: NumberFormatException) {
        issues += InventoryDiagnosticIssue.INVALID_LEDGER_DATA
        null
    }
}

private fun String?.toCurrencyOrIssue(
    issues: MutableSet<InventoryDiagnosticIssue>,
): CurrencyCode? {
    val value = this ?: return null
    return runCatching { CurrencyCode.of(value) }.getOrElse {
        issues += InventoryDiagnosticIssue.INVALID_LEDGER_DATA
        null
    }
}

private fun parseBusinessId(value: String): BusinessId =
    BusinessId.parse(value) ?: corrupt("businessId invalido")

private fun parseProductId(value: String): ProductId =
    ProductId.parse(value) ?: corrupt("productId invalido")

private fun parseLocationId(value: String): LocationId =
    LocationId.parse(value) ?: corrupt("locationId invalido")

private fun parsePurchaseId(value: String): PurchaseId =
    PurchaseId.parse(value) ?: corrupt("purchaseId invalido")

private fun parseSaleId(value: String): SaleId =
    SaleId.parse(value) ?: corrupt("saleId invalido")

private fun decimal(value: String, field: String): BigDecimal = try {
    BigDecimal(value)
} catch (failure: NumberFormatException) {
    corrupt("Decimal invalido en $field", failure)
}

private inline fun <reified T : Enum<T>> enumValue(value: String, field: String): T =
    enumValues<T>().firstOrNull { it.name == value } ?: corrupt("Enum invalido en $field")

private fun corrupt(message: String, cause: Throwable? = null): Nothing =
    throw IOException(message, cause)

private val SIGNED_PLAIN_DECIMAL = Regex("^-?\\d+(\\.\\d+)?$")
private const val MAX_COSTING_TEXT = 166
