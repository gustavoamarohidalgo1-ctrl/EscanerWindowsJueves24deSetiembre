package com.facturastock.app.domain.model

import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.OcrRunId
import java.time.Instant

/**
 * Resultado OCR completo y recuperable de una ejecución concreta. El snapshot es reemplazable:
 * para cada borrador solo la publicación del último [runId] autorizado queda visible.
 */
data class InvoiceOcrSnapshot(
    val draftId: DraftId,
    val runId: OcrRunId,
    val completedAt: Instant,
    val document: InvoiceTextDocument,
) {
    init {
        require(document.pages.isNotEmpty()) { "Un snapshot OCR debe contener al menos una página" }
        require(!completedAt.isBefore(Instant.EPOCH)) {
            "completedAt no puede ser anterior al epoch"
        }
        require(runCatching(completedAt::toEpochMilli).isSuccess) {
            "completedAt queda fuera del rango persistible"
        }
    }
}
