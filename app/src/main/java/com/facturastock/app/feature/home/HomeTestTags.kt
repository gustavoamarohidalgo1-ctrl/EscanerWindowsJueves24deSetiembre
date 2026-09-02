package com.facturastock.app.feature.home

import com.facturastock.app.domain.model.id.DraftId

/** Test tags estables de la pantalla de inicio para las pruebas de Compose. */
object HomeTestTags {
    const val SCAN_CTA = "home_scan_cta"
    const val SALES_CTA = "home_sales_cta"
    const val SHORTCUT_PRODUCTS = "home_shortcut_products"
    const val SHORTCUT_PURCHASES = "home_shortcut_purchases"
    const val SHORTCUT_DEBTORS = "home_shortcut_debtors"
    const val DASHBOARD_HEADER = "home_dashboard_header"
    const val PULSE_GRID = "home_pulse_grid"
    const val ATTENTION_PANEL = "home_attention_panel"
    const val GETTING_STARTED = "home_getting_started"
    const val DRAFTS_LIST = "home_drafts_list"
    const val DELETE_DIALOG = "home_delete_dialog"
    const val DELETE_DIALOG_CONFIRM = "home_delete_dialog_confirm"
    const val DELETE_DIALOG_CANCEL = "home_delete_dialog_cancel"
    const val OCR_DIALOG = "home_ocr_dialog"
    const val OCR_DIALOG_RESUME = "home_ocr_dialog_resume"
    const val OCR_DIALOG_RETRY = "home_ocr_dialog_retry"
    const val OCR_DIALOG_CANCEL = "home_ocr_dialog_cancel"
    const val SNACKBAR = "home_snackbar"

    /** Tag de la tarjeta de un borrador: `home_draft_card_<uuid>`. */
    fun draftCard(draftId: DraftId): String = "home_draft_card_${draftId.value}"

    /** Tag del affordance de eliminar de un borrador: `home_draft_delete_<uuid>`. */
    fun draftDelete(draftId: DraftId): String = "home_draft_delete_${draftId.value}"
}
