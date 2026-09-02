package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.repository.InvoiceImagePreprocessor
import com.facturastock.app.domain.repository.OcrImageFile

/** Recupera el último lote OCR completo; nunca expone un run parcial. */
class LoadPreparedOcrImagesUseCase(
    private val imagePreprocessor: InvoiceImagePreprocessor,
) {
    suspend operator fun invoke(draftId: DraftId): List<OcrImageFile> =
        imagePreprocessor.findPrepared(draftId)
}
