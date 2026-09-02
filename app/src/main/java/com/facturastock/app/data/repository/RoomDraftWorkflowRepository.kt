package com.facturastock.app.data.repository

import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.WorkflowRequest
import com.facturastock.app.domain.model.WorkflowSnapshot
import com.facturastock.app.domain.model.WorkflowStage
import com.facturastock.app.domain.repository.DraftWorkflowRepository
import com.facturastock.app.domain.repository.InvoiceDraftRepository
import com.facturastock.app.domain.repository.InvoiceLinesReviewRepository
import javax.inject.Inject

/**
 * Adaptador de compatibilidad para las dos rutas que aún emiten un `WorkflowSnapshot`.
 * No conserva una segunda máquina de estados: valida y reconstruye el resultado desde Room.
 */
class RoomDraftWorkflowRepository @Inject constructor(
    private val drafts: InvoiceDraftRepository,
    private val lineReviews: InvoiceLinesReviewRepository,
) : DraftWorkflowRepository {
    override suspend fun run(request: WorkflowRequest): WorkflowSnapshot {
        require(request.stage != WorkflowStage.CREATED) {
            "Los borradores se crean en Room antes de abrir el origen, no en un estado paralelo"
        }
        val draftId = requireNotNull(request.draftId) {
            "draftId es obligatorio para ${request.stage}"
        }
        val draft = requireNotNull(drafts.findDraft(draftId)) {
            "El borrador no existe en Room"
        }
        require(draft.status != DraftStatus.ERROR && draft.status != DraftStatus.OCR_PROCESSING) {
            "El borrador no está disponible para avanzar"
        }

        var updatedAt = draft.updatedAt
        if (request.stage == WorkflowStage.LINES_REVIEWED ||
            request.stage == WorkflowStage.PRODUCTS_LINKED
        ) {
            require(draft.status == DraftStatus.NEEDS_REVIEW ||
                draft.status == DraftStatus.READY_TO_POST
            ) { "El borrador no está en revisión" }
            val review = requireNotNull(lineReviews.find(draftId)) {
                "La revisión de líneas no existe en Room"
            }
            require(review.activeLines.isNotEmpty() && review.activeLines.none { it.isPending }) {
                "La revisión de líneas aún tiene datos pendientes"
            }
            if (request.stage == WorkflowStage.PRODUCTS_LINKED) {
                require(request.lineId != null) { "lineId es obligatorio para PRODUCTS_LINKED" }
                require(review.activeLines.all { line ->
                    line.linkedProductId != null && line.linkedUnitId != null
                }) { "Aún existen líneas sin producto en Room" }
            }
            if (review.updatedAt.isAfter(updatedAt)) updatedAt = review.updatedAt
        }

        if (request.stage == WorkflowStage.CONFIRMED) {
            require(draft.status == DraftStatus.COMMITTED && draft.confirmedPurchaseId != null) {
                "La compra no está confirmada en Room"
            }
        }

        return WorkflowSnapshot(
            draftId = draft.draftId,
            stage = request.stage,
            captureId = request.captureId,
            lineId = request.lineId,
            purchaseId = draft.confirmedPurchaseId,
            updatedAt = updatedAt,
        )
    }
}
