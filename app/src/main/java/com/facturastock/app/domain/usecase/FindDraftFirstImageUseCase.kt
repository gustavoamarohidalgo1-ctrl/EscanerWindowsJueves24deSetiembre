package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.repository.InvoiceDraftRepository
import kotlinx.coroutines.flow.first

/**
 * Resuelve la primera página de un borrador (la de menor `pageIndex`). La usa la
 * reanudación desde Inicio para abrir la vista previa en una página concreta; devuelve
 * null cuando el borrador no tiene páginas (el llamador decide el destino alternativo).
 */
class FindDraftFirstImageUseCase(
    private val invoiceDraftRepository: InvoiceDraftRepository,
) {
    suspend operator fun invoke(draftId: DraftId): ImageId? =
        invoiceDraftRepository.observeImages(draftId).first()
            .firstOrNull()
            ?.imageId
}
