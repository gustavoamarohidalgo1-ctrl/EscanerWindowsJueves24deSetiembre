package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.model.InvoiceImage
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.repository.InvoiceDraftRepository

/**
 * Mueve una página una posición hacia el inicio ([moveUp] = true) o hacia el final del
 * borrador e interpersiste el nuevo orden completo de forma atómica. Los índices quedan
 * correlativos desde cero, así la sucesión de páginas se conserva tras una muerte del
 * proceso.
 *
 * Devuelve las páginas en su nuevo orden. Es un no-op controlado si la página ya está en
 * el extremo pedido; lanza `StorageException` si la página no pertenece al borrador.
 */
class ReorderDraftImagesUseCase(
    private val invoiceDraftRepository: InvoiceDraftRepository,
) {
    suspend operator fun invoke(
        draftId: DraftId,
        imageId: ImageId,
        moveUp: Boolean,
    ): List<InvoiceImage> =
        invoiceDraftRepository.moveImageOneStep(draftId, imageId, moveUp)
}
