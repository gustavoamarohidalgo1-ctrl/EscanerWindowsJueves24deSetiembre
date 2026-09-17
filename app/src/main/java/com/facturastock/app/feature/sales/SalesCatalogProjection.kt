package com.facturastock.app.feature.sales

import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.InventoryDataAlert
import com.facturastock.app.domain.model.InventoryReadItem
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.WeightSaleCalculator
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Locale

internal val SALES_PRODUCT_OPTION_ORDER =
    compareBy<SalesContract.ProductOption> { it.productName.lowercase(Locale.ROOT) }
        .thenBy { it.locationName.lowercase(Locale.ROOT) }

private val LOCATION_OPTION_ORDER =
    compareBy<SalesContract.ProductOption> { it.locationName.lowercase(Locale.ROOT) }
        .thenBy { it.locationId.value }

internal data class SalesCatalogProjection(
    val productsById: Map<ProductId, Product>,
    val inventoryById: Map<ProductId, InventoryReadItem>,
    val optionsByProduct: Map<ProductId, List<SalesContract.ProductOption>>,
    val orderedOptions: List<SalesContract.ProductOption>,
)

/** Prepared once per catalog emission, never once per sale/stock update. */
internal suspend fun prepareSalesProducts(products: List<Product>): Map<ProductId, Product> {
    val context = currentCoroutineContext()
    val result = LinkedHashMap<ProductId, Product>(products.size)
    products.forEachIndexed { index, product ->
        if (index % 64 == 0) context.ensureActive()
        result[product.productId] = product
    }
    context.ensureActive()
    return result
}

/**
 * One instance per observed business. Only changed products rebuild their sale options. Quantity,
 * cost and version updates cannot change the sort keys, so they retain the existing global order.
 * Stable sorting also depends on the source product order: reordering equal-name products must
 * invalidate that order. Cache publication happens only after cancellation has been checked.
 */
internal class SalesCatalogProjector {
    private var previous: SalesCatalogProjection? = null

    suspend fun project(
        products: Map<ProductId, Product>,
        inventory: List<InventoryReadItem>,
    ): SalesCatalogProjection {
        val context = currentCoroutineContext()
        context.ensureActive()
        val before = previous
        val inventoryById = LinkedHashMap<ProductId, InventoryReadItem>(inventory.size)
        inventory.forEachIndexed { index, item ->
            if (index % 64 == 0) context.ensureActive()
            inventoryById[item.productId] = item
        }
        val optionsByProduct = LinkedHashMap<ProductId, List<SalesContract.ProductOption>>()
        val replacements = HashMap<ProductId, Map<LocationId, SalesContract.ProductOption>>()
        var mustSort = before == null
        inventoryById.values.forEachIndexed { index, item ->
            if (index % 64 == 0) context.ensureActive()
            val product = products[item.productId] ?: return@forEachIndexed
            if (product.status != CatalogStatus.ACTIVE) return@forEachIndexed
            val oldOptions = before?.optionsByProduct?.get(product.productId)
            val options =
                if (before != null && oldOptions != null && before.productsById[product.productId] == product &&
                    before.inventoryById[item.productId] == item
                ) {
                    oldOptions
                } else {
                    buildSalesProductOptions(product, item).let { fresh ->
                        // Cost/version changes need fresh inventory for the cart, but no new UI rows.
                        if (oldOptions != null && fresh == oldOptions) oldOptions else fresh
                    }
                }
            if (options.isEmpty()) return@forEachIndexed
            optionsByProduct[product.productId] = options
            if (options !== oldOptions) {
                if (!sameOptionOrder(oldOptions, options)) mustSort = true
                if (!mustSort) replacements[product.productId] = options.associateBy { it.locationId }
            }
        }
        // LinkedHashMap preserves the first occurrence of a product, including duplicate inputs,
        // exactly as the previous associateBy/flatten/stable-sort implementation did.
        if (before != null && !sameIterationOrder(before.optionsByProduct.keys, optionsByProduct.keys)) {
            mustSort = true
        }
        val orderedOptions =
            when {
                mustSort -> {
                    sortSalesProductOptions(optionsByProduct.values.flatten())
                }

                replacements.isEmpty() -> {
                    requireNotNull(before).orderedOptions
                }

                else -> {
                    requireNotNull(before).orderedOptions.mapIndexed { index, option ->
                        if (index % 64 == 0) context.ensureActive()
                        replacements[option.productId]?.get(option.locationId) ?: option
                    }
                }
            }
        context.ensureActive()
        return SalesCatalogProjection(
            productsById = products,
            inventoryById = inventoryById,
            optionsByProduct =
                if (!mustSort && replacements.isEmpty()) {
                    requireNotNull(before).optionsByProduct
                } else {
                    optionsByProduct
                },
            orderedOptions = orderedOptions,
        ).also { previous = it }
    }
}

private fun sameIterationOrder(
    first: Set<ProductId>,
    second: Set<ProductId>,
): Boolean {
    if (first.size != second.size) return false
    val iterator = second.iterator()
    return first.all { it == iterator.next() }
}

private fun sameOptionOrder(
    previous: List<SalesContract.ProductOption>?,
    current: List<SalesContract.ProductOption>,
): Boolean {
    if (previous == null || previous.size != current.size) return false
    return previous.indices.all { index ->
        val old = previous[index]
        val next = current[index]
        old.locationId == next.locationId &&
            (old.productName == next.productName || old.productName.lowercase(Locale.ROOT) == next.productName.lowercase(Locale.ROOT)) &&
            (old.locationName == next.locationName || old.locationName.lowercase(Locale.ROOT) == next.locationName.lowercase(Locale.ROOT))
    }
}

/** Also used for the single-product lookup before a combined catalog emission arrives. */
internal fun buildSalesProductOptions(
    product: Product,
    item: InventoryReadItem,
): List<SalesContract.ProductOption> {
    if (product.status != CatalogStatus.ACTIVE) return emptyList()
    val unitCode = item.unitSymbol?.takeIf(String::isNotBlank) ?: item.unitCode
    val isWeightProduct = product.barcode == null && WeightSaleCalculator.isKilogramUnit(item.unitCode)
    val options =
        item.positions.mapNotNull { position ->
            if (position.quantityOnHand.signum() <= 0 || InventoryDataAlert.ARCHIVED_LOCATION in position.alerts) {
                null
            } else {
                SalesContract.ProductOption(
                    productId = product.productId,
                    productName = product.name,
                    locationId = position.locationId,
                    locationName = position.locationName,
                    unitCode = unitCode,
                    availableQuantity = position.quantityOnHand,
                    sku = product.sku,
                    barcode = product.barcode,
                    suggestedSalePrice = product.salePrice,
                    isWeightProduct = isWeightProduct,
                )
            }
        }
    return if (options.size < 2) options else options.sortedWith(LOCATION_OPTION_ORDER)
}

private data class SortableSalesOption(
    val option: SalesContract.ProductOption,
    val productName: String,
    val locationName: String,
)

/** Lowercase once per option, instead of twice per comparison during the initial O(n log n) sort. */
private suspend fun sortSalesProductOptions(options: List<SalesContract.ProductOption>): List<SalesContract.ProductOption> {
    if (options.size < 2) return options
    val context = currentCoroutineContext()
    val keyed =
        options.mapIndexed { index, option ->
            if (index % 64 == 0) context.ensureActive()
            SortableSalesOption(option, option.productName.lowercase(Locale.ROOT), option.locationName.lowercase(Locale.ROOT))
        }
    return keyed
        .sortedWith(
            compareBy<SortableSalesOption> { it.productName }.thenBy { it.locationName },
        ).map { it.option }
}
