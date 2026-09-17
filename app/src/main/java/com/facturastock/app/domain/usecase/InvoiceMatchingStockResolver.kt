package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.model.CatalogCanonicalizer
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.ExactDecimalPolicy
import com.facturastock.app.domain.model.InventoryCostingDecimalPolicy
import com.facturastock.app.domain.model.InvoiceMatchUnitChoice
import com.facturastock.app.domain.model.InvoiceMatchingIssue
import com.facturastock.app.domain.model.ScannedItemMatch
import java.math.BigDecimal
import java.math.RoundingMode

internal data class ResolvedInvoiceMatchingStock(
    val quantity: BigDecimal,
    val unitCost: BigDecimal,
    val appliedCostTotal: BigDecimal,
)

/** Nunca supone que una unidad desconocida es la unidad de compra del producto. */
internal object InvoiceMatchingStockResolver {
    fun issues(
        item: ScannedItemMatch,
        currency: CurrencyCode,
        inventoryUnitCode: String?,
        purchaseUnitCode: String?,
    ): Set<InvoiceMatchingIssue> =
        buildSet {
            if (item.matchedProduct == null) add(InvoiceMatchingIssue.PRODUCT_REQUIRED)
            if (item.quantity == null || item.quantity.signum() <= 0 ||
                !ExactDecimalPolicy.supportsValue(item.quantity)
            ) {
                add(InvoiceMatchingIssue.QUANTITY_REQUIRED)
            }
            if (item.unitCost == null || item.unitCost.signum() < 0 ||
                !ExactDecimalPolicy.supportsValue(item.unitCost)
            ) {
                add(InvoiceMatchingIssue.COST_REQUIRED)
            }
            when (item.sourceCurrency) {
                null -> add(InvoiceMatchingIssue.CURRENCY_REQUIRED)
                currency -> Unit
                else -> add(InvoiceMatchingIssue.CURRENCY_MISMATCH)
            }
            if (factor(item, inventoryUnitCode, purchaseUnitCode) == null) {
                add(InvoiceMatchingIssue.UNIT_REQUIRED)
            }
        }

    fun resolve(
        item: ScannedItemMatch,
        inventoryUnitCode: String?,
        purchaseUnitCode: String?,
    ): ResolvedInvoiceMatchingStock? {
        val factor = factor(item, inventoryUnitCode, purchaseUnitCode) ?: return null
        val quantity = item.quantity?.multiply(factor) ?: return null
        val readCost = item.unitCost ?: return null
        val total = item.quantity.multiply(readCost)
        val cost = readCost.divide(factor, 18, RoundingMode.HALF_EVEN)
        if (!listOf(quantity, total, cost).all(InventoryCostingDecimalPolicy::supportsPersisted)) return null
        return ResolvedInvoiceMatchingStock(quantity, cost, total)
    }

    private fun factor(
        item: ScannedItemMatch,
        inventoryUnitCode: String?,
        purchaseUnitCode: String?,
    ): BigDecimal? {
        val product = item.matchedProduct ?: return null
        if (inventoryUnitCode == null) return null
        val purchaseFactor =
            product.purchaseFactor?.takeIf {
                purchaseUnitCode != null && product.purchaseUnitId != product.unitId && it.signum() > 0
            }
        return when (item.unitChoice) {
            InvoiceMatchUnitChoice.INVENTORY -> {
                BigDecimal.ONE
            }

            InvoiceMatchUnitChoice.PURCHASE -> {
                purchaseFactor
            }

            null -> {
                val source =
                    item.sourceUnitCode
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                        ?.let(CatalogCanonicalizer::unitCode) ?: return null
                when (source) {
                    CatalogCanonicalizer.unitCode(inventoryUnitCode) -> BigDecimal.ONE
                    purchaseUnitCode?.let(CatalogCanonicalizer::unitCode) -> purchaseFactor
                    else -> null
                }
            }
        }
    }
}
