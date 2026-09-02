package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.OcrRunId

/**
 * Lease exclusivamente de proceso para lectores OCR vivos. Room conserva la recuperación tras
 * muerte, mientras este registro distingue un `OCR_PROCESSING` residual de ML Kit que todavía
 * está consumiendo los archivos preparados. No contiene texto ni rutas.
 */
class OcrRunActivityRegistry {
    private val guard = Any()
    private val activeRuns = mutableMapOf<DraftId, OcrRunId>()

    fun markActive(draftId: DraftId, runId: OcrRunId) {
        synchronized(guard) { activeRuns[draftId] = runId }
    }

    fun markFinished(draftId: DraftId, runId: OcrRunId) {
        synchronized(guard) {
            if (activeRuns[draftId] == runId) activeRuns.remove(draftId)
        }
    }

    fun isActive(draftId: DraftId): Boolean = synchronized(guard) { draftId in activeRuns }
}
