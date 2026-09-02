package com.facturastock.app.domain.repository

import com.facturastock.app.domain.model.id.DraftId

enum class EnterManualInvoiceReviewResult {
    ENTERED,
    NOT_AVAILABLE,
}

/**
 * Materializa el punto de partida vacío de una revisión manual en un único commit.
 *
 * La implementación acepta un reconocimiento fallido sin snapshot o un snapshot `OCR_READY` que
 * el parser no pudo publicar. Debe invalidar el token si todavía existe, preservar toda evidencia
 * OCR publicada y persistir cabecera y líneas antes de exponer `NEEDS_REVIEW`.
 */
interface ManualInvoiceReviewRepository {
    suspend fun enter(draftId: DraftId): EnterManualInvoiceReviewResult
}
