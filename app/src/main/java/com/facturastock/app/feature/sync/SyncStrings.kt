package com.facturastock.app.feature.sync

import androidx.annotation.StringRes
import com.facturastock.app.R
import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.FacturaStockError
import com.facturastock.app.domain.model.OutboxOperationStatus
import com.facturastock.app.domain.model.PurchaseDocumentType
import com.facturastock.app.domain.model.PurchaseStatus

/**
 * Mapeos cerrados de la pantalla de sincronización. Los errores del puerto nunca transportan
 * detalle sensible: cualquier error ajeno al dominio de cuenta cae en el mensaje genérico.
 */
@StringRes
fun syncErrorMessageRes(error: FacturaStockError): Int = when (error) {
    AccountError.Unavailable -> R.string.account_error_unavailable
    AccountError.NotAuthenticated -> R.string.account_error_not_authenticated
    AccountError.SessionExpired -> R.string.account_error_session_expired
    AccountError.PermissionDenied -> R.string.account_error_permission_denied
    AccountError.NotFound -> R.string.account_error_not_found
    AccountError.Conflict -> R.string.account_error_conflict
    AccountError.NetworkUnavailable -> R.string.account_error_network
    else -> R.string.account_error_generic
}

/** Etiqueta visible de una operación de la cola de respaldo. */
@StringRes
fun outboxStatusLabelRes(status: OutboxOperationStatus): Int = when (status) {
    OutboxOperationStatus.PENDING -> R.string.sync_outbox_status_pending
    OutboxOperationStatus.PROCESSING -> R.string.sync_outbox_status_processing
    OutboxOperationStatus.COMPLETED -> R.string.sync_outbox_status_completed
    OutboxOperationStatus.FAILED -> R.string.sync_outbox_status_failed
    OutboxOperationStatus.CONFLICT -> R.string.sync_outbox_status_conflict
    OutboxOperationStatus.RESOLVED -> R.string.sync_outbox_status_resolved
}

/** Tipo de operación durable tal como la escribe el libro local ("SYNC_PURCHASE", …). */
@StringRes
fun operationTypeLabelRes(operationType: String): Int = when (operationType) {
    OPERATION_TYPE_SYNC_PURCHASE -> R.string.sync_operation_purchase
    OPERATION_TYPE_SYNC_PURCHASE_VOID -> R.string.sync_operation_purchase_void
    OPERATION_TYPE_SYNC_PRODUCT -> R.string.sync_operation_product
    OPERATION_TYPE_SYNC_SUPPLIER -> R.string.sync_operation_supplier
    OPERATION_TYPE_SYNC_DOCUMENT_UPLOAD -> R.string.sync_operation_document_upload
    OPERATION_TYPE_SYNC_DOCUMENT_PURGE -> R.string.sync_operation_document_purge
    else -> R.string.sync_operation_unknown
}

@StringRes
fun documentSyncStateLabelRes(state: SyncContract.DocumentSyncState): Int = when (state) {
    SyncContract.DocumentSyncState.LOCAL_ONLY -> R.string.sync_document_state_local
    SyncContract.DocumentSyncState.PENDING_UPLOAD -> R.string.sync_document_state_pending
    SyncContract.DocumentSyncState.UPLOADING -> R.string.sync_document_state_uploading
    SyncContract.DocumentSyncState.BACKED_UP -> R.string.sync_document_state_backed_up
    SyncContract.DocumentSyncState.ERROR -> R.string.sync_document_state_error
    SyncContract.DocumentSyncState.PURGE_PENDING -> R.string.sync_document_state_purge_pending
    SyncContract.DocumentSyncState.DELETED_FROM_CLOUD ->
        R.string.sync_document_state_deleted
}

/** El código durable permanece técnico en Room; la pantalla solo muestra categorías cerradas. */
@StringRes
fun outboxErrorMessageRes(errorCode: String): Int = when {
    errorCode == "CONFLICT" || errorCode == "HTTP_409" ->
        R.string.sync_outbox_error_conflict
    errorCode == "INVALID_ACK" -> R.string.sync_outbox_error_invalid_confirmation
    errorCode == "PERMANENT_FAILURE" || errorCode.startsWith("HTTP_4") ->
        R.string.sync_outbox_error_permanent
    errorCode == "NETWORK_UNAVAILABLE" || errorCode == "TRANSPORT_UNAVAILABLE" ->
        R.string.sync_outbox_error_connection
    errorCode == "TRANSIENT_FAILURE" || errorCode == "UNEXPECTED_TRANSPORT_ERROR" ||
        errorCode.startsWith("HTTP_5") -> R.string.sync_outbox_error_temporary
    else -> R.string.sync_outbox_error_generic
}

/** El libro remoto transporta el tipo de comprobante como el nombre del enum local. */
@StringRes
fun remoteDocumentTypeLabelRes(documentType: String): Int =
    when (runCatching { PurchaseDocumentType.valueOf(documentType) }.getOrNull()) {
        PurchaseDocumentType.INVOICE -> R.string.header_review_document_type_invoice
        PurchaseDocumentType.SALES_RECEIPT -> R.string.header_review_document_type_sales_receipt
        PurchaseDocumentType.CREDIT_NOTE -> R.string.header_review_document_type_credit_note
        PurchaseDocumentType.DEBIT_NOTE -> R.string.header_review_document_type_debit_note
        null -> R.string.sync_document_type_unknown
    }

/** Estado de una compra (local o remota) con las mismas etiquetas del libro de compras. */
@StringRes
fun syncPurchaseStatusLabelRes(status: PurchaseStatus): Int = when (status) {
    PurchaseStatus.DRAFT -> R.string.purchase_status_draft
    PurchaseStatus.POSTED -> R.string.purchase_status_posted
    PurchaseStatus.VOIDED -> R.string.purchase_status_voided
}

private const val OPERATION_TYPE_SYNC_PURCHASE = "SYNC_PURCHASE"
private const val OPERATION_TYPE_SYNC_PURCHASE_VOID = "SYNC_PURCHASE_VOID"
private const val OPERATION_TYPE_SYNC_PRODUCT = "SYNC_PRODUCT"
private const val OPERATION_TYPE_SYNC_SUPPLIER = "SYNC_SUPPLIER"
private const val OPERATION_TYPE_SYNC_DOCUMENT_UPLOAD = "SYNC_DOCUMENT_UPLOAD"
private const val OPERATION_TYPE_SYNC_DOCUMENT_PURGE = "SYNC_DOCUMENT_PURGE"
