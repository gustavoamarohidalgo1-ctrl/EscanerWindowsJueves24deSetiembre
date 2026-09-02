package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.OcrRunId
import com.facturastock.app.domain.repository.InvoiceDraftRepository

/**
 * Repite el OCR de un borrador interrumpido devolviéndolo a [DraftStatus.CAPTURED]; la
 * pantalla de procesamiento lo retoma desde ahí con las imágenes ya capturadas. La transición
 * compara el token visto por la UI: una acción atrasada no puede cancelar otro intento ni
 * degradar un borrador avanzado o confirmado.
 */
class RetryDraftOcrUseCase(
    private val invoiceDraftRepository: InvoiceDraftRepository,
) {
    suspend operator fun invoke(draftId: DraftId, expectedRunId: OcrRunId?): Boolean =
        invoiceDraftRepository.resetInterruptedOcr(draftId, expectedRunId)
}
