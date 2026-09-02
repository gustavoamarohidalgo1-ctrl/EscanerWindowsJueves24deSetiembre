package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.repository.DraftFileStore
import com.facturastock.app.domain.repository.InvoiceDraftRepository
import java.util.concurrent.CancellationException

/**
 * Elimina un borrador con todo su contenido. Tras la cascada Room solicita el borrado físico
 * mediante una operación que toma el lock exclusivo del árbol y, ya dentro, revalida que el
 * borrador siga ausente. Si otra captura recreó el mismo ID mientras tanto, el árbol nuevo se
 * conserva completo.
 *
 * El borrado de archivos es de mejor esfuerzo: un fallo al eliminarlos no revierte ni
 * invalida el borrado del registro; los archivos huérfanos resultantes se toleran.
 *
 * Devuelve false si el borrador no existía (nada que eliminar).
 */
class DeleteDraftUseCase(
    private val invoiceDraftRepository: InvoiceDraftRepository,
    private val draftFileStore: DraftFileStore,
) {
    suspend operator fun invoke(draftId: DraftId): Boolean {
        val removed = invoiceDraftRepository.deleteDraft(draftId)
        if (removed) {
            deleteTreeIfStillDiscardedBestEffort(draftId)
        }
        return removed
    }

    private suspend fun deleteTreeIfStillDiscardedBestEffort(draftId: DraftId) {
        try {
            draftFileStore.deleteDraftTreeIf(
                draftId = draftId,
                pathIsReferencedAnywhere = invoiceDraftRepository::isImagePathReferenced,
            ) { invoiceDraftRepository.findDraft(draftId) == null }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Fail-closed: una revalidación incierta nunca autoriza borrar el árbol.
        }
    }
}
