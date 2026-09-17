package com.facturastock.app.domain.model

import com.facturastock.app.domain.model.id.LocationId
import java.math.BigDecimal

data class ProductInventoryPosition(
    val locationId: LocationId,
    val quantityOnHand: BigDecimal,
    val averageUnitCost: InventoryCostAmount,
)

/** Existencia y valorización exactas; se separan monedas para evitar mezclarlas. */
data class ProductInventorySummary(
    val positions: List<ProductInventoryPosition>,
) {
    val totalQuantityOnHand: BigDecimal = positions.fold(BigDecimal.ZERO) { total, position ->
        total.add(position.quantityOnHand)
    }

    val averageUnitCostsByCurrency: Map<CurrencyCode, InventoryCostAmount?> = positions
        .groupBy { it.averageUnitCost.currency }
        .mapValues { (currency, currencyPositions) ->
            // Las cantidades firmadas conservan su valor, pero no definen un promedio de
            // existencias disponible. Otra moneda independiente sigue siendo consultable.
            if (currencyPositions.any { it.quantityOnHand.signum() < 0 }) return@mapValues null
            // Una sola posición no necesita división: conserva toda la precisión derivada
            // que ya estaba en Room, sin reducirla al límite de una entrada UnitCost.
            currencyPositions.singleOrNull()?.takeIf { it.quantityOnHand.signum() > 0 }?.let {
                return@mapValues it.averageUnitCost
            }
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
            InventoryCostAmount(average, currency)
        }

    val averageUnitCost: InventoryCostAmount?
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
