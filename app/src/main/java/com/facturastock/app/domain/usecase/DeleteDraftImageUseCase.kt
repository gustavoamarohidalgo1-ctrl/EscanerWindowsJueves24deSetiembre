package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.repository.DraftFileStore
import com.facturastock.app.domain.repository.InvoiceDraftRepository
import java.util.concurrent.CancellationException

data class DeleteDraftImageResult(
    val removed: Boolean,
    val draftIsEmpty: Boolean,
)

/**
 * Elimina una página del borrador. El repositorio devuelve el registro retirado mientras
 * reindexa las páginas y, si corresponde, devuelve el borrador de `CAPTURED` a
 * [DraftStatus.CREATED] en la misma transacción. Después se elimina el archivo físico de ESA
 * página en mejor esfuerzo, sin tocar los demás.
 *
 * Devuelve además si el borrador quedó vacío, calculado dentro de esa mutación durable. La UI
 * no debe inferirlo desde un Flow que puede adelantarse a su callback.
 */
class DeleteDraftImageUseCase(
    private val invoiceDraftRepository: InvoiceDraftRepository,
    private val draftFileStore: DraftFileStore,
) {
    suspend operator fun invoke(imageId: ImageId): DeleteDraftImageResult {
        val deletion = invoiceDraftRepository.deleteImage(imageId)
            ?: return DeleteDraftImageResult(removed = false, draftIsEmpty = false)
        try {
            // Bases antiguas pudieron contener dos filas con la misma ruta. La fila objetivo ya
            // salió de Room; si queda cualquier alias, conserva el único archivo compartido.
            if (!invoiceDraftRepository.isImagePathReferenced(deletion.image.filePath)) {
                draftFileStore.deleteFiles(listOf(deletion.image.filePath))
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Mejor esfuerzo: el registro ya quedó eliminado; el huérfano se tolera.
        }
        return DeleteDraftImageResult(
            removed = true,
            draftIsEmpty = deletion.draftIsEmpty,
        )
    }

}
