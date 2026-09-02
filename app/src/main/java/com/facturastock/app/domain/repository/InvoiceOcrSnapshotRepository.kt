package com.facturastock.app.domain.repository

import com.facturastock.app.domain.model.InvoiceOcrSnapshot
import com.facturastock.app.domain.model.id.DraftId

/** Persistencia local del último documento OCR completo publicado para cada borrador. */
interface InvoiceOcrSnapshotRepository {
    /**
     * Reemplaza el snapshot y finaliza el borrador como OCR_READY en una sola transacción.
     * Devuelve false si el borrador ya no está OCR_PROCESSING con el mismo run activo.
     */
    suspend fun publish(snapshot: InvoiceOcrSnapshot): Boolean

    /** Devuelve el snapshot completo o null si nunca se publicó uno para el borrador. */
    suspend fun find(draftId: DraftId): InvoiceOcrSnapshot?
}
