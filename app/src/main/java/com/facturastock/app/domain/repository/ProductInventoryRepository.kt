package com.facturastock.app.domain.repository

import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.ProductInventorySummary
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import java.math.BigDecimal

/**
 * Entrada de almacén que no nace de una compra publicada. Cada ítem se materializa como un
 * movimiento `ADJUSTMENT` y un CAS del saldo; no sustituye el posting de una factura.
 */
data class InventoryStockAddition(
    val productId: ProductId,
    val locationId: LocationId,
    val quantityToAdd: BigDecimal,
    val unitCost: BigDecimal? = null,
    val idempotencyKey: String? = null,
    /** Total exacto antes de dividir un costo de caja entre sus unidades. */
    val appliedCostTotal: BigDecimal? = null,
) {
    init {
        require(quantityToAdd.signum() != 0) { "quantityToAdd no puede ser cero" }
        require(unitCost == null || unitCost.signum() >= 0) {
            "unitCost no puede ser negativo"
        }
        require(idempotencyKey == null || idempotencyKey.isNotBlank()) {
            "idempotencyKey no puede estar en blanco"
        }
        require(appliedCostTotal == null || appliedCostTotal.signum() >= 0)
    }
}

interface ProductInventoryRepository {
    suspend fun summaryForProduct(
        businessId: BusinessId,
        productId: ProductId,
    ): ProductInventorySummary

    suspend fun setStock(
        businessId: BusinessId,
        productId: ProductId,
        locationId: LocationId,
        quantity: BigDecimal,
        currency: CurrencyCode,
    ) {
        throw UnsupportedOperationException("setStock requiere una implementación de inventario")
    }

    suspend fun addStock(
        businessId: BusinessId,
        productId: ProductId,
        locationId: LocationId,
        quantityToAdd: BigDecimal,
        currency: CurrencyCode,
        unitCost: BigDecimal? = null,
        idempotencyKey: String? = null,
    ) {
        throw UnsupportedOperationException("addStock requiere una implementación de inventario")
    }

    /**
     * Aplica todas las entradas en una sola transacción. Un fallo revierte el lote completo.
     * El default recorre [addStock]; los adaptadores de prueba que no implementan [addStock]
     * deben sobrescribir este método.
     */
    suspend fun addStockBatch(
        businessId: BusinessId,
        currency: CurrencyCode,
        items: List<InventoryStockAddition>,
    ): Int {
        var applied = 0
        for (item in items) {
            addStock(
                businessId = businessId,
                productId = item.productId,
                locationId = item.locationId,
                quantityToAdd = item.quantityToAdd,
                currency = currency,
                unitCost = item.unitCost,
                idempotencyKey = item.idempotencyKey,
            )
            applied += 1
        }
        return applied
    }
}
