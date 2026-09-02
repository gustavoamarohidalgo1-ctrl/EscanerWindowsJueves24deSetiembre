package com.facturastock.app.feature.headerreview

object InvoiceHeaderReviewTestTags {
    const val SCREEN = "header_review.screen"
    const val LIST = "header_review.list"
    const val DOCUMENT_THUMBNAIL = "header_review.document_thumbnail"
    const val EXPANDED_DOCUMENT = "header_review.expanded_document"
    const val EVIDENCE_DIALOG = "header_review.evidence_dialog"
    const val BLOCKING_EXPLANATION = "header_review.blocking_explanation"
    const val WARNING_SUMMARY = "header_review.warning_summary"
    const val REVIEW_PRODUCTS = "header_review.review_products"

    fun field(id: InvoiceHeaderReviewContract.FieldId): String =
        "header_review.field.${id.name.lowercase()}"

    fun confidence(id: InvoiceHeaderReviewContract.FieldId): String =
        "header_review.confidence.${id.name.lowercase()}"

    fun evidence(id: InvoiceHeaderReviewContract.FieldId): String =
        "header_review.evidence.${id.name.lowercase()}"

    fun confirmation(id: InvoiceHeaderReviewContract.FieldId): String =
        "header_review.confirmation.${id.name.lowercase()}"
}
