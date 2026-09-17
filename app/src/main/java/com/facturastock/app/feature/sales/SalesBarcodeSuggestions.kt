package com.facturastock.app.feature.sales

import com.facturastock.app.domain.model.BarcodeValue
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.usecase.BarcodeSimilarity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class BarcodeSuggestionMatch(
    val productId: ProductId,
    val barcode: String,
    val missingDigits: Int,
)

/** Recorre el catálogo ya observado, en Default, conservando como máximo cinco productos. */
internal suspend fun findBarcodeSuggestionMatches(
    scannedBarcode: String,
    businessId: BusinessId,
    products: Collection<Product>,
    availableProductIds: Set<ProductId>,
): List<BarcodeSuggestionMatch> {
    val context = currentCoroutineContext()
    context.ensureActive()
    // These properties of the scan are invariant for every candidate in the catalog.
    if (scannedBarcode.length !in 5..BarcodeValue.MAX_LENGTH || scannedBarcode.any { it !in '0'..'9' }) {
        return emptyList()
    }
    val best = ArrayList<BarcodeSuggestionMatch>(MAX_BARCODE_SUGGESTION_PRODUCTS + 1)
    products.forEachIndexed { index, product ->
        if (index % 64 == 0) context.ensureActive()
        if (product.businessId != businessId || product.status != CatalogStatus.ACTIVE ||
            product.productId !in availableProductIds
        ) {
            return@forEachIndexed
        }
        val barcode = product.barcode ?: return@forEachIndexed
        val missing = BarcodeSimilarity.missingDigits(scannedBarcode, barcode) ?: return@forEachIndexed
        val match = BarcodeSuggestionMatch(product.productId, barcode, missing)
        if (best.size == MAX_BARCODE_SUGGESTION_PRODUCTS && BARCODE_SUGGESTION_ORDER.compare(match, best.last()) >= 0) {
            return@forEachIndexed
        }
        // Insert into the already sorted five slots; never sort/allocate another list per match.
        val insertion = best.indexOfFirst { BARCODE_SUGGESTION_ORDER.compare(match, it) < 0 }
        best.add(if (insertion < 0) best.size else insertion, match)
        if (best.size > MAX_BARCODE_SUGGESTION_PRODUCTS) best.removeAt(best.lastIndex)
    }
    context.ensureActive()
    return best
}

private const val MAX_BARCODE_SUGGESTION_PRODUCTS = 5
private val BARCODE_SUGGESTION_ORDER =
    compareBy<BarcodeSuggestionMatch> { it.missingDigits }
        .thenBy { it.barcode }
        .thenBy { it.productId.value }
