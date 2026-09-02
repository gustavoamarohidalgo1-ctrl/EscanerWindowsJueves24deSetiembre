package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.model.InvoiceImage
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.repository.InvoiceDraftRepository
import kotlinx.coroutines.flow.Flow

/**
 * Expone las páginas de un borrador como flujo observable, ordenadas por `pageIndex`. Es la
 * fuente de la vista previa: cualquier transformación (giro, recorte, reorden, eliminación)
 * se refleja en la siguiente emisión.
 */
class ObserveDraftImagesUseCase(
    private val invoiceDraftRepository: InvoiceDraftRepository,
) {
    operator fun invoke(draftId: DraftId): Flow<List<InvoiceImage>> =
        invoiceDraftRepository.observeImages(draftId)
}
