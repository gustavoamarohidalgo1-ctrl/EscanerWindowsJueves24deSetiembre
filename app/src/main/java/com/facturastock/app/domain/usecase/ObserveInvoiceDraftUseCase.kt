package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.repository.InvoiceDraftRepository
import kotlinx.coroutines.flow.Flow

/** Expone el estado durable del borrador; la pantalla OCR nunca infiere el estado del proceso. */
class ObserveInvoiceDraftUseCase(
    private val invoiceDraftRepository: InvoiceDraftRepository,
) {
    operator fun invoke(draftId: DraftId): Flow<InvoiceDraft?> =
        invoiceDraftRepository.observeDraft(draftId)
}
