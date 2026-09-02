package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.repository.EnterManualInvoiceReviewResult
import com.facturastock.app.domain.repository.ManualInvoiceReviewRepository

/** Abre una revisión manual solo después de que su estado vacío quedó persistido por completo. */
class EnterManualInvoiceReviewUseCase(
    private val repository: ManualInvoiceReviewRepository,
) {
    suspend operator fun invoke(draftId: DraftId): EnterManualInvoiceReviewResult =
        repository.enter(draftId)
}
