package com.facturastock.app.feature.purchases

import com.facturastock.app.domain.model.PurchaseStatus
import com.facturastock.app.domain.model.PurchaseSyncState
import com.facturastock.app.domain.model.id.PurchaseId

object PurchaseListTestTags {
    const val SCREEN = "purchases_list_screen"
    const val LIST = "purchases_list"
    const val SEARCH = "purchases_search"
    const val EMPTY = "purchases_empty"
    const val LOADING = "purchases_loading"
    const val NEW_PURCHASE = "purchases_new_purchase"
    const val LOAD_MORE = "purchases_load_more"

    fun statusFilter(status: PurchaseStatus?): String =
        "purchases_status_${status?.name ?: "ALL"}"

    fun syncFilter(state: PurchaseSyncState?): String =
        "purchases_sync_${state?.name ?: "ALL"}"

    fun row(purchaseId: PurchaseId): String = "purchases_row_${purchaseId.value}"

    fun retryBackup(purchaseId: PurchaseId): String =
        "purchases_retry_backup_${purchaseId.value}"
}

object PurchaseDetailTestTags {
    const val SCREEN = "purchase_detail_screen"
    const val LIST = "purchase_detail_list"
    const val DOCUMENT = "purchase_detail_document"
    const val PERSISTENCE = "purchase_detail_persistence"
    const val DUPLICATE_OVERRIDE = "purchase_detail_duplicate_override"
    const val RETRY_BACKUP = "purchase_detail_retry_backup"
    const val TOTALS = "purchase_detail_totals"
    const val LINES = "purchase_detail_lines"
    const val TECHNICAL_TOGGLE = "purchase_detail_technical_toggle"
    const val MOVEMENTS = "purchase_detail_movements"
    const val IMAGES = "purchase_detail_images"
    const val AUDIT = "purchase_detail_audit"
    const val INTEGRITY = "purchase_detail_integrity"
    const val VOID_ACTION = "purchase_detail_void_action"
    const val VOIDED_NOTICE = "purchase_detail_voided_notice"
    const val BACK = "purchase_detail_back"

    fun line(position: Int): String = "purchase_detail_line_$position"

    fun movement(id: String): String = "purchase_detail_movement_$id"

    fun image(pageIndex: Int): String = "purchase_detail_image_$pageIndex"

    fun audit(id: String): String = "purchase_detail_audit_$id"
}

object PurchaseVoidTestTags {
    const val SCREEN = "purchase_void_screen"
    const val CONTENT = "purchase_void_content"
    const val DOCUMENT = "purchase_void_document"
    const val AUTHORIZED_ROLE = "purchase_void_authorized_role"
    const val IMPACTS = "purchase_void_impacts"
    const val NEGATIVE_WARNING = "purchase_void_negative_warning"
    const val REASON = "purchase_void_reason"
    const val CONFIRMATION = "purchase_void_confirmation"
    const val SUBMIT = "purchase_void_submit"
    const val RETRY = "purchase_void_retry"
    const val BACK = "purchase_void_back"

    fun impact(productId: String, locationId: String): String =
        "purchase_void_impact_${productId}_$locationId"
}

object PurchaseSuccessTestTags {
    const val SCREEN = "purchase_success_screen"
    const val SUMMARY = "purchase_success_summary"
    const val PERSISTENCE = "purchase_success_persistence"
    const val RETRY_BACKUP = "purchase_success_retry_backup"
    const val ADJUSTMENT = "purchase_success_adjustment"
    const val VIEW_DETAIL = "purchase_success_view_detail"
    const val VIEW_INVENTORY = "purchase_success_view_inventory"
}
