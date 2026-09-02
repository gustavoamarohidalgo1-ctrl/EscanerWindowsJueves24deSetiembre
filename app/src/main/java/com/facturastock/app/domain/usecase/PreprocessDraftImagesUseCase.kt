package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.repository.InvoiceDraftRepository
import com.facturastock.app.domain.repository.InvoiceImagePreprocessor
import com.facturastock.app.domain.repository.OcrImageFile
import kotlinx.coroutines.flow.first

/**
 * Regenera, en orden y de una en una, las versiones OCR de un borrador. El adaptador publica
 * el lote completo como una unidad; los originales nunca forman parte de esa publicación.
 */
class PreprocessDraftImagesUseCase(
    private val invoiceDraftRepository: InvoiceDraftRepository,
    private val imagePreprocessor: InvoiceImagePreprocessor,
) {
    suspend operator fun invoke(draftId: DraftId): List<OcrImageFile> {
        val images = invoiceDraftRepository.observeImages(draftId).first()
        return imagePreprocessor.preprocess(draftId, images)
    }
}
