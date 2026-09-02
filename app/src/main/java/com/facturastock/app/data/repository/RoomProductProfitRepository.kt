package com.facturastock.app.data.repository

import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.data.local.dao.ProductDao
import com.facturastock.app.data.local.dao.ProductProfitReadRow
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.ExactMonetaryAmount
import com.facturastock.app.domain.model.InventoryCostAmount
import com.facturastock.app.domain.model.InventoryCostingDecimalPolicy
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.PRODUCT_PROFIT_MARGIN_SCALE
import com.facturastock.app.domain.model.ProductProfit
import com.facturastock.app.domain.model.ProductProfitDecimalPolicy
import com.facturastock.app.domain.model.ProductProfitIssue
import com.facturastock.app.domain.model.ProductProfitStatus
import com.facturastock.app.domain.model.ProductSalePricePolicy
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.repository.ProductProfitRepository
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

class RoomProductProfitRepository @Inject constructor(
    private val productDao: ProductDao,
    private val dispatchers: DispatcherProvider,
) : ProductProfitRepository {
    override fun observeForBusiness(businessId: BusinessId): Flow<List<ProductProfit>> =
        productDao.observeProfitRows(businessId.value)
            .map { rows -> productProfitsFromRows(businessId, rows) }
            // Room puede invalidar las tablas por cambios de otro negocio. Evita publicar de
            // nuevo una proyeccion identica y recomponer toda la lista de rentabilidad.
            .distinctUntilChanged()
            .flowOn(dispatchers.io)
}

internal fun productProfitsFromRows(
    businessId: BusinessId,
    rows: List<ProductProfitReadRow>,
): List<ProductProfit> {
    if (rows.isEmpty()) return emptyList()
    // Filtra y agrupa en una sola pasada. La secuencia anterior retenia una lista filtrada y el
    // mapa completo a la vez, elevando el pico de memoria justo en catalogos grandes.
    val rowsByProduct = LinkedHashMap<String, MutableList<ProductProfitReadRow>>()
    rows.forEach { row ->
        if (row.businessId == businessId.value) {
            rowsByProduct.getOrPut(row.productId, ::mutableListOf) += row
        }
    }
    return rowsByProduct.values.map { productRows ->
        productProfitFromRows(businessId, productRows)
    }
}

private fun productProfitFromRows(
    businessId: BusinessId,
    rows: List<ProductProfitReadRow>,
): ProductProfit {
    require(rows.isNotEmpty())
    val first = rows.first()
    require(first.businessId == businessId.value)
    val issues = linkedSetOf<ProductProfitIssue>()
    if (rows.any { row -> !row.sameProductHeaderAs(first) || row.businessId != businessId.value }) {
        issues += ProductProfitIssue.INVALID_PERSISTED_VALUE
    }

    val salePrice = first.salePrice(issues)
    val balances = rows.mapNotNull { row -> row.balanceOrNull(issues) }
    if (balances.isEmpty()) issues += ProductProfitIssue.NO_INVENTORY_BALANCE
    if (balances.any { it.quantity.signum() < 0 }) {
        issues += ProductProfitIssue.NEGATIVE_STOCK
    }
    val positiveBalances = balances.filter { it.quantity.signum() > 0 }
    val inventoryCurrencies = positiveBalances.map(Balance::currency).toSet()
    if (inventoryCurrencies.size > 1) {
        issues += ProductProfitIssue.MIXED_INVENTORY_CURRENCIES
    }
    if (positiveBalances.isEmpty()) {
        issues += ProductProfitIssue.NON_POSITIVE_STOCK
    }
    if (
        salePrice != null && positiveBalances.any { balance ->
            balance.currency != salePrice.currency
        }
    ) {
        issues += ProductProfitIssue.SALE_PRICE_CURRENCY_MISMATCH
    }

    val totalStock = if (balances.isEmpty()) {
        null
    } else {
        balances.sumDecimal(Balance::quantity)
            .takeIf(ProductProfitDecimalPolicy::supports)
            .also { if (it == null) issues += ProductProfitIssue.DECIMAL_LIMIT_EXCEEDED }
    }

    var averageCost: InventoryCostAmount? = null
    var unitProfit: ExactMonetaryAmount? = null
    var marginPercent: BigDecimal? = null
    var potentialProfit: ExactMonetaryAmount? = null
    val comparableInventory =
        ProductProfitIssue.INVALID_PERSISTED_VALUE !in issues &&
            ProductProfitIssue.NEGATIVE_STOCK !in issues &&
            ProductProfitIssue.MIXED_INVENTORY_CURRENCIES !in issues &&
            positiveBalances.isNotEmpty()
    if (comparableInventory) {
        val positiveQuantity = positiveBalances.sumDecimal(Balance::quantity)
        val inventoryValue = positiveBalances.fold(BigDecimal.ZERO) { total, balance ->
            total.add(balance.quantity.multiply(balance.averageUnitCost))
        }
        if (
            ProductProfitDecimalPolicy.supports(positiveQuantity) &&
            ProductProfitDecimalPolicy.supports(inventoryValue)
        ) {
            val currency = positiveBalances.first().currency
            val average = inventoryValue.divide(
                positiveQuantity,
                AVERAGE_COST_SCALE,
                RoundingMode.HALF_EVEN,
            )
            if (InventoryCostingDecimalPolicy.supportsPersisted(average)) {
                averageCost = InventoryCostAmount(average, currency)
            } else {
                issues += ProductProfitIssue.DECIMAL_LIMIT_EXCEEDED
            }
            if (
                salePrice != null && salePrice.currency == currency && averageCost != null
            ) {
                val saleMajor = salePrice.toMajor()
                val unitProfitAmount = saleMajor.subtract(average)
                val potentialAmount = saleMajor.multiply(positiveQuantity).subtract(inventoryValue)
                val margin = unitProfitAmount.multiply(ONE_HUNDRED).divide(
                    saleMajor,
                    PRODUCT_PROFIT_MARGIN_SCALE,
                    RoundingMode.HALF_EVEN,
                )
                if (
                    listOf(unitProfitAmount, potentialAmount, margin)
                        .all(ProductProfitDecimalPolicy::supports)
                ) {
                    unitProfit = ExactMonetaryAmount(unitProfitAmount, currency)
                    potentialProfit = ExactMonetaryAmount(potentialAmount, currency)
                    marginPercent = margin
                } else {
                    issues += ProductProfitIssue.DECIMAL_LIMIT_EXCEEDED
                }
            }
        } else {
            issues += ProductProfitIssue.DECIMAL_LIMIT_EXCEEDED
        }
    }
    if (salePrice == null && ProductProfitIssue.INVALID_PERSISTED_VALUE !in issues) {
        issues += ProductProfitIssue.MISSING_SALE_PRICE
    }

    val status = when {
        ProductProfitIssue.INVALID_PERSISTED_VALUE in issues ||
            ProductProfitIssue.DECIMAL_LIMIT_EXCEEDED in issues ||
            ProductProfitIssue.NEGATIVE_STOCK in issues -> ProductProfitStatus.INVALID_INVENTORY
        salePrice == null -> ProductProfitStatus.MISSING_SALE_PRICE
        positiveBalances.isEmpty() -> ProductProfitStatus.NO_STOCK
        ProductProfitIssue.MIXED_INVENTORY_CURRENCIES in issues ||
            ProductProfitIssue.SALE_PRICE_CURRENCY_MISMATCH in issues ->
            ProductProfitStatus.NON_COMPARABLE_CURRENCY
        averageCost != null && unitProfit != null && marginPercent != null &&
            potentialProfit != null -> ProductProfitStatus.AVAILABLE
        else -> ProductProfitStatus.INVALID_INVENTORY
    }
    val visibleIssues = if (status == ProductProfitStatus.AVAILABLE) emptySet() else issues
    return ProductProfit(
        productId = requireNotNull(ProductId.parse(first.productId)),
        businessId = businessId,
        productName = first.productName,
        sku = first.sku,
        unitCode = first.unitCode,
        productStatus = CatalogStatus.valueOf(first.productStatus),
        productVersion = first.productVersion,
        salePrice = salePrice,
        totalStockQuantity = totalStock,
        averageUnitCost = averageCost,
        unitProfit = unitProfit,
        marginPercent = marginPercent,
        potentialProfit = potentialProfit,
        status = status,
        issues = visibleIssues,
    )
}

private data class Balance(
    val quantity: BigDecimal,
    val averageUnitCost: BigDecimal,
    val currency: CurrencyCode,
)

private fun ProductProfitReadRow.sameProductHeaderAs(other: ProductProfitReadRow): Boolean =
    businessId == other.businessId && productId == other.productId &&
        productName == other.productName && sku == other.sku &&
        productStatus == other.productStatus && productVersion == other.productVersion &&
        unitCode == other.unitCode && salePriceMinorUnits == other.salePriceMinorUnits &&
        salePriceCurrencyCode == other.salePriceCurrencyCode

private fun ProductProfitReadRow.salePrice(
    issues: MutableSet<ProductProfitIssue>,
): Money? {
    val minorUnits = salePriceMinorUnits
    val currencyCode = salePriceCurrencyCode
    if (minorUnits == null && currencyCode == null) return null
    if (
        minorUnits == null || currencyCode == null ||
        minorUnits !in 1L..ProductSalePricePolicy.MAX_MINOR_UNITS
    ) {
        issues += ProductProfitIssue.INVALID_PERSISTED_VALUE
        return null
    }
    val currency = runCatching { CurrencyCode.of(currencyCode) }.getOrNull()
    if (currency == null) {
        issues += ProductProfitIssue.INVALID_PERSISTED_VALUE
        return null
    }
    return Money.ofMinor(minorUnits, currency)
}

private fun ProductProfitReadRow.balanceOrNull(
    issues: MutableSet<ProductProfitIssue>,
): Balance? {
    val values = listOf(locationId, quantityOnHand, averageUnitCost, inventoryCurrencyCode)
    if (values.all { it == null }) return null
    if (values.any { it == null }) {
        issues += ProductProfitIssue.INVALID_PERSISTED_VALUE
        return null
    }
    val quantity = quantityOnHand?.toBoundedPlainDecimalOrNull(signed = true)
    val cost = averageUnitCost?.toBoundedPlainDecimalOrNull(signed = false)
    val currency = inventoryCurrencyCode?.let { code ->
        runCatching { CurrencyCode.of(code) }.getOrNull()
    }
    if (
        quantity == null || cost == null || currency == null || cost.signum() < 0 ||
        !InventoryCostingDecimalPolicy.supportsPersisted(quantity) ||
        !InventoryCostingDecimalPolicy.supportsPersisted(cost)
    ) {
        issues += ProductProfitIssue.INVALID_PERSISTED_VALUE
        return null
    }
    return Balance(quantity, cost, currency)
}

private inline fun <T> List<T>.sumDecimal(value: (T) -> BigDecimal): BigDecimal =
    fold(BigDecimal.ZERO) { total, item -> total.add(value(item)) }

private fun String.toBoundedPlainDecimalOrNull(signed: Boolean): BigDecimal? {
    if (length > MAX_PERSISTED_DECIMAL_CHARACTERS) return null
    val pattern = if (signed) SIGNED_PLAIN_DECIMAL else PLAIN_DECIMAL
    if (!pattern.matches(this)) return null
    return runCatching { BigDecimal(this) }.getOrNull()
}

private const val AVERAGE_COST_SCALE = 18
private const val MAX_PERSISTED_DECIMAL_CHARACTERS = 166
private val ONE_HUNDRED = BigDecimal("100")
private val PLAIN_DECIMAL = Regex("^\\d+(\\.\\d+)?$")
private val SIGNED_PLAIN_DECIMAL = Regex("^-?\\d+(\\.\\d+)?$")
