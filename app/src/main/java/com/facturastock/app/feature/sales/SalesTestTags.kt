package com.facturastock.app.feature.sales

object SalesTestTags {
    const val REGISTER_PRODUCT = "sales_register_product"
    const val REGISTERED_PRODUCT_NOTICE = "sales_registered_product_notice"
    const val SCREEN = "sales_screen"
    const val ENTRY_KIND_SCREEN = "sales_entry_kind_screen"
    const val ENTRY_MODE_SCREEN = "sales_entry_mode_screen"
    const val STEP_BACK = "sales_step_back"
    const val CASH_ENTRY = "sales_cash_entry"
    const val CREDIT_ENTRY = "sales_credit_entry"
    const val OPEN_DEBTORS = "sales_open_debtors"
    const val DEBTOR_NAME = "sales_debtor_name"
    const val SCANNER_MODE = "sales_scanner_mode"
    const val MANUAL_MODE = "sales_manual_mode"
    const val SCANNER_STATUS = "sales_scanner_status"
    const val SCANNER_FEEDBACK = "sales_scanner_feedback"
    const val SCANNER_RESUME = "sales_scanner_resume"
    const val SEARCH = "sales_search"
    const val NAME_SEARCH_PROGRESS = "sales_name_search_progress"
    const val SEARCH_FAILURE = "sales_search_failure"
    const val CATALOG_FAILURE = "sales_catalog_failure"
    const val CART_FAILURE = "sales_cart_failure"
    const val OPTIONS = "sales_options"
    const val LOCATION_SELECTION = "sales_location_selection"
    const val ASSOCIATION_CANCEL = "sales_association_cancel"
    const val ASSOCIATION_SAVED_NOTICE = "sales_association_saved_notice"
    const val BARCODE_SUGGESTIONS = "sales_barcode_suggestions"
    const val BARCODE_SUGGESTIONS_RESCAN = "sales_barcode_suggestions_rescan"
    const val BARCODE_SUGGESTIONS_MANUAL = "sales_barcode_suggestions_manual"
    const val CART = "sales_cart"
    const val LINE_SAVE_PROGRESS = "sales_line_save_progress"
    const val CHECKOUT = "sales_checkout"
    const val CHECKOUT_DIALOG = "sales_checkout_dialog"
    const val REPLACEMENT_DIALOG = "sales_replacement_dialog"
    const val DISCARD_EDITS_DIALOG = "sales_discard_edits_dialog"

    fun option(productId: String, locationId: String) =
        "sales_option_${productId}_$locationId"

    fun barcodeSuggestion(productId: String, locationId: String) =
        "sales_barcode_suggestion_${productId}_$locationId"

    fun barcodeSuggestionAdd(productId: String, locationId: String) =
        "sales_barcode_suggestion_add_${productId}_$locationId"

    fun line(lineId: String) = "sales_line_$lineId"
    fun quantity(lineId: String) = "sales_quantity_$lineId"
    fun quantityDecrease(lineId: String) = "sales_quantity_decrease_$lineId"
    fun quantityIncrease(lineId: String) = "sales_quantity_increase_$lineId"
    fun price(lineId: String) = "sales_price_$lineId"
    fun remove(lineId: String) = "sales_remove_$lineId"
}
