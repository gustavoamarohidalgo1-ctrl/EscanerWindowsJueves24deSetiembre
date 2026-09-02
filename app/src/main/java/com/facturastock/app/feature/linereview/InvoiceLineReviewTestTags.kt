package com.facturastock.app.feature.linereview

import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.FieldId

object InvoiceLineReviewTestTags {
    const val SCREEN = "line_review_screen"
    const val SEARCH = "line_review_search"
    const val PENDING_FILTER = "line_review_pending_filter"
    const val ADD_LINE = "line_review_add_line"
    const val EMPTY_ADD_LINE = "line_review_empty_add_line"
    const val LIST = "line_review_list"
    const val EMPTY_FILTER = "line_review_empty_filter"
    const val RESTORE_BANNER = "line_review_restore_banner"
    const val RESTORE = "line_review_restore"
    const val SUMMARY = "line_review_summary"
    const val DIFFERENCE = "line_review_difference"
    const val LINK_PRODUCTS = "line_review_link_products"
    const val EDITOR = "line_review_editor"
    const val EDITOR_LIST = "line_review_editor_list"
    const val EDITOR_SUMMARY = "line_review_editor_summary"
    const val EDITOR_DONE = "line_review_editor_done"
    const val CLOSE_EDITOR = "line_review_close_editor"
    const val DELETE_DIALOG = "line_review_delete_dialog"
    const val TAX_TREATMENT = "line_review_tax_treatment"

    fun card(lineId: LineId): String = "line_review_card_${lineId.value}"

    fun edit(lineId: LineId): String = "line_review_edit_${lineId.value}"

    fun delete(lineId: LineId): String = "line_review_delete_${lineId.value}"

    fun confirm(lineId: LineId): String = "line_review_confirm_${lineId.value}"

    fun moreActions(lineId: LineId): String = "line_review_more_actions_${lineId.value}"

    fun moveUp(lineId: LineId): String = "line_review_move_up_${lineId.value}"

    fun moveDown(lineId: LineId): String = "line_review_move_down_${lineId.value}"

    fun editorField(field: FieldId): String = "line_review_editor_field_${field.name.lowercase()}"

    fun editorFieldOrigin(field: FieldId): String =
        "line_review_editor_field_origin_${field.name.lowercase()}"
}
