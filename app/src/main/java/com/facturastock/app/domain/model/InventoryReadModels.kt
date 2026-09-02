package com.facturastock.app.domain.model

import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.model.id.SaleId
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

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
    init {
        require(productName.isNotBlank())
        require(unitCode.isNotBlank())
        require(positions.map(InventoryReadPosition::locationId).distinct().size == positions.size)
    }

    val totalQuantityOnHand: BigDecimal = positions.fold(BigDecimal.ZERO) { total, position ->
        total.add(position.quantityOnHand)
    }

    val estimatedValuesByCurrency: Map<CurrencyCode, BigDecimal> = positions
        .groupBy { it.averageUnitCost.currency }
        .mapValues { (_, currencyPositions) ->
            currencyPositions.fold(BigDecimal.ZERO) { total, position ->
                total.add(position.estimatedValue)
            }
        }

    val averageUnitCostsByCurrency: Map<CurrencyCode, InventoryCostAmount?> = positions
        .groupBy { it.averageUnitCost.currency }
        .mapValues { (currency, currencyPositions) ->
            val quantity = currencyPositions.fold(BigDecimal.ZERO) { total, position ->
                total.add(position.quantityOnHand)
            }
            val value = currencyPositions.fold(BigDecimal.ZERO) { total, position ->
                total.add(position.estimatedValue)
            }
            val average = when {
                currencyPositions.any { it.quantityOnHand.signum() < 0 } -> null
                quantity.signum() <= 0 -> null
                else -> value.divide(quantity, COST_SCALE, RoundingMode.HALF_EVEN)
                    .takeIf { it.signum() >= 0 }
            }
            average?.let { InventoryCostAmount(it, currency) }
        }

    val allAlerts: Set<InventoryDataAlert> = buildSet {
        addAll(alerts)
        positions.forEach { addAll(it.alerts) }
        if (positions.map { it.averageUnitCost.currency }.distinct().size > 1) {
            add(InventoryDataAlert.MIXED_CURRENCIES)
        }
        if (averageUnitCostsByCurrency.values.any { it == null }) {
            add(InventoryDataAlert.UNDEFINED_AGGREGATE_AVERAGE)
        }
    }

    private companion object {
        const val COST_SCALE = 18
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
            StockMovementType.SALE -> require(purchaseId == null && saleId != null)
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
        require(
            movements.zipWithNext().all { (first, second) ->
                first.occurredAt < second.occurredAt ||
                    (
                        first.occurredAt == second.occurredAt &&
                            (
                                first.createdAt < second.createdAt ||
                                    (
                                        first.createdAt == second.createdAt &&
                                            first.movementId <= second.movementId
                                        )
                                )
                        )
            },
        )
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
