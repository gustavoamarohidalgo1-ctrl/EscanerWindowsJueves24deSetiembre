package com.facturastock.app.domain.repository

import com.facturastock.app.domain.model.InvoiceLine
import com.facturastock.app.domain.model.InvoiceLinesEdit
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.LineId
import kotlinx.coroutines.flow.Flow

/** Snapshot parcial y proyección tipada que Room confirma en una sola transacción. */
data class InvoiceLinesEditPublication(
    /**
     * Los enlaces de catálogo son una copia consultiva salvo para [catalogLinkLineIds]: Room
     * reemplaza los demás dentro de la transacción por el estado exacto de `invoice_lines`,
     * incluidos los null de un unlink.
     */
    val edit: InvoiceLinesEdit,
    /**
     * Líneas activas que ya tienen una representación tipada válida. Una línea nueva todavía
     * parcial puede existir solo en [edit]; al resolverse entra a esta proyección con el mismo ID.
     */
    val projectedLines: List<InvoiceLine>,
    /** Revisión Room desde la que nació el snapshot; null significa que aún no había fila. */
    val expectedRevision: Long? = null,
    /**
     * IDs cuyo enlace de catálogo cambia deliberadamente en esta publicación. Para esos IDs,
     * y solo para ellos, [edit] y [projectedLines] son autoritativos sobre la copia física
     * anterior. Un producto staged permanece solo en [edit] hasta la confirmacion; su proyeccion
     * fisica conserva productId null para no violar la FK. Room valida ambos casos en el CAS.
     */
    val catalogLinkLineIds: Set<LineId> = emptySet(),
) {
    init {
        require(expectedRevision == null || expectedRevision >= 0L) {
            "La revisión base no puede ser negativa"
        }
        require(expectedRevision == null || edit.revision >= expectedRevision) {
            "La edición no puede retroceder respecto de su revisión base"
        }
        require(projectedLines.all { it.draftId == edit.draftId }) {
            "Las líneas proyectadas pertenecen a otro borrador"
        }
        require(projectedLines.map(InvoiceLine::lineId).distinct().size == projectedLines.size) {
            "La proyección contiene lineId duplicado"
        }
        val activeIds = edit.activeLines.mapTo(mutableSetOf()) { it.lineId }
        require(projectedLines.all { it.lineId in activeIds }) {
            "Una línea eliminada o ajena no puede permanecer proyectada"
        }
        val projectedById = projectedLines.associateBy(InvoiceLine::lineId)
        val editById = edit.activeLines.associateBy { it.lineId }
        require(catalogLinkLineIds.all { it in projectedById }) {
            "Un enlace autoritativo debe pertenecer a una línea activa proyectada"
        }
        require(catalogLinkLineIds.all { lineId ->
            val edited = editById.getValue(lineId)
            val projected = projectedById.getValue(lineId)
            edited.linkedProductId != null &&
                edited.linkedUnitId != null &&
                edited.linkConfidence != null &&
                projected.productId == edited.linkedProductId.takeIf {
                    edited.stagedProduct == null
                } &&
                projected.unitId == edited.linkedUnitId &&
                projected.linkConfidence == edited.linkConfidence
        }) {
            "Un enlace autoritativo debe definir el mismo producto, unidad y confianza en ambas vistas"
        }
        require(projectedLines.map(InvoiceLine::position) == projectedLines.indices.toList()) {
            "Las posiciones proyectadas deben ser correlativas desde cero"
        }
        require(projectedLines.map(InvoiceLine::businessId).distinct().size <= 1) {
            "La proyección no puede mezclar negocios"
        }
        require(projectedLines.all { !it.createdAt.isAfter(edit.updatedAt) }) {
            "Una línea proyectada no puede crearse después del snapshot"
        }
    }
}

enum class SaveInvoiceLinesEditResult {
    SAVED,
    ALREADY_SAVED,
    STALE_REVISION,
    DRAFT_NOT_EDITABLE,
    CONFLICT,
}

/** Puerto durable: el CAS agregado serializa edit/add/delete/restore/reorder. */
interface InvoiceLinesReviewRepository {
    suspend fun find(draftId: DraftId): InvoiceLinesEdit?

    fun observe(draftId: DraftId): Flow<InvoiceLinesEdit?>

    suspend fun saveIfNewer(
        publication: InvoiceLinesEditPublication,
    ): SaveInvoiceLinesEditResult
}
