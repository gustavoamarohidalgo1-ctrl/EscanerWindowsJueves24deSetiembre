package com.facturastock.app.domain.model

import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.model.id.SaleId
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.Collections

/** Advertencias derivadas de la proyeccion local; nunca ocultan el valor que las produjo. */
enum class InventoryDataAlert {
    NEGATIVE_STOCK,
    ARCHIVED_PRODUCT,
    ARCHIVED_LOCATION,
    MIXED_CURRENCIES,
    MISSING_MOVEMENT_COST,
    PROJECTION_DIVERGENCE,
    UNDEFINED_AGGREGATE_AVERAGE,
}

/** Costo compatible con la politica decimal persistida de inventario (128/36). */
data class InventoryCostAmount(
    val amount: BigDecimal,
    val currency: CurrencyCode,
) {
    init {
        require(amount.signum() >= 0 && InventoryCostingDecimalPolicy.supportsPersisted(amount))
    }
}

/** Saldo materializado de un producto en un almacen. */
data class InventoryReadPosition(
    val locationId: LocationId,
    val locationName: String,
    val quantityOnHand: BigDecimal,
    val averageUnitCost: InventoryCostAmount,
    val version: Long,
    val updatedAt: Instant,
    val alerts: Set<InventoryDataAlert> = emptySet(),
) {
    init {
        require(locationName.isNotBlank())
        require(version >= 0L)
    }

    /** Valor mayor exacto; se redondea solo al presentarlo, no para diagnosticar. */
    val estimatedValue: BigDecimal = quantityOnHand.multiply(averageUnitCost.amount)
}

/** Una tarjeta de inventario, agregada por producto y separada por moneda. */
data class InventoryReadItem(
    val productId: ProductId,
    val businessId: BusinessId,
    val productName: String,
    val sku: String?,
    val unitCode: String,
    val unitSymbol: String?,
    val positions: List<InventoryReadPosition>,
    val alerts: Set<InventoryDataAlert> = emptySet(),
) {
    val totalQuantityOnHand: BigDecimal
    val estimatedValuesByCurrency: Map<CurrencyCode, BigDecimal>
    val averageUnitCostsByCurrency: Map<CurrencyCode, InventoryCostAmount?>
    val allAlerts: Set<InventoryDataAlert>

    init {
        require(productName.isNotBlank())
        require(unitCode.isNotBlank())
        // Cada emisión de Room puede reconstruir miles de tarjetas. Una pasada conserva el
        // orden y la escala decimal de los folds anteriores, sin dos groupBy ni listas auxiliares.
        val locations = HashSet<LocationId>(positions.size)
        val currencies = linkedMapOf<CurrencyCode, InventoryCurrencyAccumulator>()
        val combinedAlerts = linkedSetOf<InventoryDataAlert>().apply { addAll(alerts) }
        var totalQuantity = BigDecimal.ZERO
        for (position in positions) {
            require(locations.add(position.locationId))
            totalQuantity = totalQuantity.add(position.quantityOnHand)
            currencies.getOrPut(position.averageUnitCost.currency) { InventoryCurrencyAccumulator() }
                .add(position)
            combinedAlerts.addAll(position.alerts)
        }
        val values = LinkedHashMap<CurrencyCode, BigDecimal>(currencies.size)
        val averages = LinkedHashMap<CurrencyCode, InventoryCostAmount?>(currencies.size)
        var undefinedAverage = false
        for ((currency, aggregate) in currencies) {
            values[currency] = aggregate.value
            val average = when {
                aggregate.hasNegativeQuantity || aggregate.quantity.signum() <= 0 -> null
                else -> aggregate.value.divide(aggregate.quantity, COST_SCALE, RoundingMode.HALF_EVEN)
                    .takeIf { it.signum() >= 0 }
            }
            averages[currency] = average?.let { InventoryCostAmount(it, currency) }
            if (average == null) undefinedAverage = true
        }
        if (currencies.size > 1) combinedAlerts.add(InventoryDataAlert.MIXED_CURRENCIES)
        if (undefinedAverage) combinedAlerts.add(InventoryDataAlert.UNDEFINED_AGGREGATE_AVERAGE)
        totalQuantityOnHand = totalQuantity
        estimatedValuesByCurrency = values
        averageUnitCostsByCurrency = averages
        allAlerts = Collections.unmodifiableSet(combinedAlerts)
    }

    private companion object {
        const val COST_SCALE = 18
    }
}

private class InventoryCurrencyAccumulator {
    var quantity: BigDecimal = BigDecimal.ZERO
        private set
    var value: BigDecimal = BigDecimal.ZERO
        private set
    var hasNegativeQuantity: Boolean = false
        private set

    fun add(position: InventoryReadPosition) {
        quantity = quantity.add(position.quantityOnHand)
        value = value.add(position.estimatedValue)
        hasNegativeQuantity = hasNegativeQuantity || position.quantityOnHand.signum() < 0
    }
}

/** Entrada cronologica e inmutable del libro; purchaseId habilita la trazabilidad al origen. */
data class InventoryReadMovement(
    val movementId: String,
    val productId: ProductId,
    val locationId: LocationId,
    val locationName: String,
    val type: StockMovementType,
    val quantityDelta: BigDecimal,
    val unitCost: InventoryCostAmount?,
    val purchaseId: PurchaseId?,
    val purchaseDocumentNumber: String?,
    val occurredAt: Instant,
    val createdAt: Instant,
    val alerts: Set<InventoryDataAlert> = emptySet(),
    val saleId: SaleId? = null,
) {
    init {
        require(movementId.isNotBlank())
        require(quantityDelta.signum() != 0)
        require((purchaseId == null) == (purchaseDocumentNumber == null))
        when (type) {
            StockMovementType.PURCHASE,
            StockMovementType.VOID,
            -> require(purchaseId != null && saleId == null)
            StockMovementType.SALE,
            StockMovementType.SALE_VOID,
            -> require(purchaseId == null && saleId != null)
            StockMovementType.ADJUSTMENT -> require(purchaseId == null && saleId == null)
        }
    }
}

data class InventoryProductDetail(
    val item: InventoryReadItem,
    /** Orden ascendente estable: occurredAt, createdAt y movementId. */
    val movements: List<InventoryReadMovement>,
) {
    init {
        require(movements.all { it.productId == item.productId })
        val iterator = movements.iterator()
        if (iterator.hasNext()) {
            var first = iterator.next()
            while (iterator.hasNext()) {
                val second = iterator.next()
                require(first.occurredAt < second.occurredAt ||
                    (
                        first.occurredAt == second.occurredAt &&
                            (
                                first.createdAt < second.createdAt ||
                                    (
                                        first.createdAt == second.createdAt &&
                                            first.movementId <= second.movementId
                                        )
                        )
                    ))
                first = second
            }
        }
    }
}

enum class InventoryDiagnosticIssue {
    MISSING_CACHED_BALANCE,
    QUANTITY_DIVERGENCE,
    AVERAGE_COST_DIVERGENCE,
    CURRENCY_DIVERGENCE,
    MISSING_MOVEMENT_COST,
    COST_UNVERIFIABLE,
    ORDER_AMBIGUOUS,
    UNEXPLAINED_OPENING_BALANCE,
    INVALID_LEDGER_DATA,
}

/** Comparacion exacta para una clave producto/almacen. */
data class InventoryDiagnosticPosition(
    val productId: ProductId,
    val productName: String,
    val locationId: LocationId,
    val locationName: String,
    val currency: CurrencyCode?,
    val cachedQuantity: BigDecimal?,
    val ledgerQuantity: BigDecimal?,
    val cachedAverageUnitCost: BigDecimal?,
    val ledgerAverageUnitCost: BigDecimal?,
    val movementCount: Int,
    val cachedVersion: Long?,
    val cachedUpdatedAt: Instant?,
    val issues: Set<InventoryDiagnosticIssue>,
) {
    init {
        require(productName.isNotBlank())
        require(locationName.isNotBlank())
        require(movementCount >= 0)
    }

    val matches: Boolean
        get() = issues.isEmpty()
}

/** Resultado de solo lectura: no muta saldos ni movimientos. */
data class InventoryDiagnosticReport(
    val generatedAt: Instant,
    val positions: List<InventoryDiagnosticPosition>,
) {
    val movementCount: Int = positions.sumOf(InventoryDiagnosticPosition::movementCount)
    val divergenceCount: Int = positions.count { !it.matches }
    val isConsistent: Boolean
        get() = divergenceCount == 0
}
