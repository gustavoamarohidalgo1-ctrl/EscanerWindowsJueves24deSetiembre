package com.facturastock.app.feature.linking

import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.model.id.ProductId

object ProductLinkingTestTags {
    const val SCREEN = "product_linking_screen"
    const val LIST = "product_linking_list"
    const val PROGRESS = "product_linking_progress"
    const val CURRENT_LINE = "product_linking_current_line"
    const val SEARCH = "product_linking_search"
    const val SEARCH_PROGRESS = "product_linking_search_progress"
    const val SEARCH_FAILURE = "product_linking_search_failure"
    const val NO_CANDIDATES = "product_linking_no_candidates"
    const val CREATE = "product_linking_create"
    const val SKIP = "product_linking_skip"
    const val CONTINUE = "product_linking_continue"
    const val FAILURE_BANNER = "product_linking_failure_banner"
    const val CREATE_DIALOG = "product_linking_create_dialog"
    const val CREATE_NAME = "product_linking_create_name"
    const val CREATE_UNIT = "product_linking_create_unit"
    const val CREATE_SKU = "product_linking_create_sku"
    const val CREATE_BARCODE = "product_linking_create_barcode"
    const val CREATE_SALE_PRICE = "product_linking_create_sale_price"
    const val CREATE_SALE_PRICE_ERROR = "product_linking_create_sale_price_error"
    const val CREATE_PURCHASE_UNIT = "product_linking_create_purchase_unit"
    const val CREATE_FACTOR = "product_linking_create_factor"
    const val CREATE_EQUIVALENCE = "product_linking_create_equivalence"
    const val CREATE_DUPLICATE = "product_linking_create_duplicate"
    const val CREATE_DUPLICATE_LINK = "product_linking_create_duplicate_link"
    const val CREATE_SUBMIT = "product_linking_create_submit"
    const val CREATE_DISMISS = "product_linking_create_dismiss"
    const val SALE_PRICE_DIALOG = "product_linking_sale_price_dialog"
    const val SALE_PRICE_FIELD = "product_linking_sale_price_field"
    const val SALE_PRICE_ERROR = "product_linking_sale_price_error"

    fun lineRow(lineId: LineId): String = "product_linking_line_${lineId.value}"

    fun changeLink(lineId: LineId): String = "product_linking_change_${lineId.value}"

    fun candidate(productId: ProductId): String = "product_linking_candidate_${productId.value}"

    fun candidateLink(productId: ProductId): String =
        "product_linking_candidate_link_${productId.value}"
}
