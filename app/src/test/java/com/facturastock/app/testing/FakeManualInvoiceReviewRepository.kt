package com.facturastock.app.testing

import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.repository.EnterManualInvoiceReviewResult
import com.facturastock.app.domain.repository.ManualInvoiceReviewRepository

class FakeManualInvoiceReviewRepository(
    private val drafts: FakeInvoiceDraftRepository? = null,
) : ManualInvoiceReviewRepository {
    val calls = mutableListOf<DraftId>()
    val emptyHeaderDrafts = mutableSetOf<DraftId>()
    val emptyLinesDrafts = mutableSetOf<DraftId>()
    var nextResult: EnterManualInvoiceReviewResult = EnterManualInvoiceReviewResult.ENTERED
    var nextException: Exception? = null
    var beforeEnter: suspend (DraftId) -> Unit = {}

    override suspend fun enter(draftId: DraftId): EnterManualInvoiceReviewResult {
        calls += draftId
        beforeEnter(draftId)
        nextException?.let { failure ->
            nextException = null
            throw failure
        }
        if (nextResult != EnterManualInvoiceReviewResult.ENTERED) return nextResult
        val repository = drafts
        if (repository != null) {
            val current = repository.findDraft(draftId)
                ?: return EnterManualInvoiceReviewResult.NOT_AVAILABLE
            if (
                current.status !in setOf(
                    DraftStatus.ERROR,
                    DraftStatus.OCR_PROCESSING,
                    DraftStatus.OCR_READY,
                ) ||
                current.confirmedPurchaseId != null
            ) {
                return EnterManualInvoiceReviewResult.NOT_AVAILABLE
            }
            repository.updateDraft(
                current.copy(
                    status = DraftStatus.NEEDS_REVIEW,
                    activeOcrRunId = null,
                    lastError = null,
                ),
            )
        }
        emptyHeaderDrafts += draftId
        emptyLinesDrafts += draftId
        return EnterManualInvoiceReviewResult.ENTERED
    }
}
