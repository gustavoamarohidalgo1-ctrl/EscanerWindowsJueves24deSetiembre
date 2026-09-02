package com.facturastock.app.domain.repository

import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.InvoiceHeaderEdit
import com.facturastock.app.domain.model.id.DraftId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Edit y proyección tipada que Room debe confirmar en una única transacción. */
data class InvoiceHeaderEditPublication(
    val edit: InvoiceHeaderEdit,
    val projectedDraft: InvoiceDraft,
    /** Revisión Room desde la que nació el formulario; null significa que aún no existía fila. */
    val expectedRevision: Long? = null,
) {
    init {
        require(edit.draftId == projectedDraft.draftId) {
            "El formulario y su proyección pertenecen a borradores distintos"
        }
        require(projectedDraft.status == DraftStatus.NEEDS_REVIEW) {
            "La cabecera solo puede editarse en NEEDS_REVIEW"
        }
        require(projectedDraft.activeOcrRunId == null) {
            "No se puede editar durante una ejecución OCR"
        }
        require(projectedDraft.confirmedPurchaseId == null) {
            "Una compra confirmada ya no admite edición de cabecera"
        }
        require(expectedRevision == null || expectedRevision >= 0L) {
            "La revisión base no puede ser negativa"
        }
        require(expectedRevision == null || edit.revision >= expectedRevision) {
            "La edición no puede retroceder respecto de su revisión base"
        }
    }
}

enum class SaveInvoiceHeaderEditResult {
    SAVED,
    ALREADY_SAVED,
    STALE_REVISION,
    DRAFT_NOT_EDITABLE,
    CONFLICT,
}

/** Puerto de autosave durable y ordenado por revisión. */
interface InvoiceHeaderReviewRepository {
    suspend fun find(draftId: DraftId): InvoiceHeaderEdit?

    /**
     * Fuente observable del formulario durable. La implementación de producción es una consulta
     * Room; el fallback de una sola emisión mantiene compatibles implementaciones de prueba muy
     * pequeñas sin convertirlas en una fuente remota de UI.
     */
    fun observe(draftId: DraftId): Flow<InvoiceHeaderEdit?> = flow { emit(find(draftId)) }

    suspend fun saveIfNewer(
        publication: InvoiceHeaderEditPublication,
    ): SaveInvoiceHeaderEditResult
}
