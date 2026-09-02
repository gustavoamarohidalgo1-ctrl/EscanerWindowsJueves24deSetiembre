package com.facturastock.app.feature.sync

object SyncTestTags {
    const val SYNC_NOW_ACTION = "sync:sync-now-action"
    const val COMPARE_ACTION = "sync:compare-action"
    const val RECORD_REVIEW_ACTION = "sync:record-review-action"
    const val RETRY_OPERATION_ACTION = "sync:retry-operation-action"
    const val KEEP_REMOTE_ACTION = "sync:keep-remote-action"
    const val RETRY_CONFLICT_ACTION = "sync:retry-conflict-action"
    const val DOCUMENT_SECTION = "sync:document-section"

    fun ambiguity(remotePurchaseId: String): String =
        "sync:reconciliation-ambiguity:$remotePurchaseId"

    fun productAmbiguity(remoteProductId: String): String =
        "sync:reconciliation-product-ambiguity:$remoteProductId"

    fun documentOperation(index: Int): String = "sync:document-operation:$index"
}
