package com.facturastock.app.domain.model

import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.PurchaseId

/**
 * Destino al que se reanuda un borrador desde la lista de inicio. Es una decisión pura del
 * dominio: la UI solo traduce cada caso a su ruta de navegación.
 *
 * [InterruptedOcr] no navega por sí mismo: un borrador en [DraftStatus.OCR_PROCESSING] se
 * considera interrumpido (la app se cerró a mitad del OCR) y la UI debe ofrecer reanudar o
 * repetir el reconocimiento antes de continuar.
 */
sealed interface DraftResumeTarget {
    data class Source(val draftId: DraftId) : DraftResumeTarget

    /** Destino legacy conservado para enlaces y herramientas que aún abren la vista previa. */
    data class Preview(val draftId: DraftId) : DraftResumeTarget

    data class Processing(val draftId: DraftId) : DraftResumeTarget

    data class Header(val draftId: DraftId) : DraftResumeTarget

    data class Lines(val draftId: DraftId) : DraftResumeTarget

    data class Summary(val draftId: DraftId) : DraftResumeTarget

    /** El borrador ya confirmó su compra: se abre el detalle de solo lectura. */
    data class PurchaseDetail(val purchaseId: PurchaseId) : DraftResumeTarget

    /** OCR interrumpido: la UI muestra el diálogo de reanudar o repetir. */
    data object InterruptedOcr : DraftResumeTarget
}

/**
 * Resuelve el destino de reanudación de un borrador. Una compra ya confirmada
 * (`confirmedPurchaseId` distinto de null) tiene prioridad sobre cualquier estado.
 */
fun InvoiceDraft.resumeTarget(): DraftResumeTarget {
    confirmedPurchaseId?.let { return DraftResumeTarget.PurchaseDetail(it) }
    return when (status) {
        DraftStatus.CREATED -> DraftResumeTarget.Source(draftId)
        DraftStatus.CAPTURED -> DraftResumeTarget.Processing(draftId)
        DraftStatus.OCR_PROCESSING -> DraftResumeTarget.InterruptedOcr
        // El snapshot existe, pero todavía falta publicar el ParsedInvoice durable. Processing
        // toma el fast-path OCR y ejecuta el parseo idempotente antes de abrir la revisión.
        DraftStatus.OCR_READY -> DraftResumeTarget.Processing(draftId)
        // El parseo ya quedó publicado: el orquestador reanuda la importación de productos.
        DraftStatus.NEEDS_REVIEW -> DraftResumeTarget.Processing(draftId)
        DraftStatus.READY_TO_POST,
        DraftStatus.COMMITTED,
        -> DraftResumeTarget.Summary(draftId)
        DraftStatus.ERROR -> DraftResumeTarget.Processing(draftId)
    }
}
