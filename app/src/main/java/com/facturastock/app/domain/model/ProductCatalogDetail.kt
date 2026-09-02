package com.facturastock.app.domain.model

import com.facturastock.app.domain.model.id.LocationId
import java.math.BigDecimal

data class ProductInventoryPosition(
    val locationId: LocationId,
    val quantityOnHand: BigDecimal,
    val averageUnitCost: UnitCost,
)

/** Existencia y valorización exactas; se separan monedas para evitar mezclarlas. */
data class ProductInventorySummary(
    val positions: List<ProductInventoryPosition>,
) {
    val totalQuantityOnHand: BigDecimal = positions.fold(BigDecimal.ZERO) { total, position ->
        total.add(position.quantityOnHand)
    }

    val averageUnitCostsByCurrency: Map<CurrencyCode, UnitCost> = positions
        .groupBy { it.averageUnitCost.currency }
        .mapValues { (currency, currencyPositions) ->
            val quantity = currencyPositions.fold(BigDecimal.ZERO) { total, position ->
                total.add(position.quantityOnHand)
            }
            val value = currencyPositions.fold(BigDecimal.ZERO) { total, position ->
                total.add(position.quantityOnHand.multiply(position.averageUnitCost.amount))
            }
            val average = if (quantity.signum() == 0) {
                BigDecimal.ZERO
            } else {
                value.divide(quantity, INVENTORY_COST_SCALE, java.math.RoundingMode.HALF_EVEN)
            }
            UnitCost.of(average, currency)
        }

    val averageUnitCost: UnitCost?
        get() = averageUnitCostsByCurrency.values.singleOrNull()

    private companion object {
        const val INVENTORY_COST_SCALE: Int = 18
    }
}

data class ProductCatalogDetail(
    val product: Product,
    val unit: UnitOfMeasure,
    val aliases: List<SupplierProductAlias>,
    val inventory: ProductInventorySummary,
)

