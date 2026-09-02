package com.facturastock.app.domain.usecase

import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.InvoiceDraftRepository

/**
 * Materializa el borrador en Room antes de abrir cámara o galería.
 *
 * Esta frontera deliberadamente precede cualquier escritura de imagen: si el almacenamiento de
 * archivos se llena durante la primera captura, Inicio todavía puede observar y reanudar el
 * borrador `CREATED`. El ID lo genera la capa de presentación una sola vez y el insert de Room
 * sigue siendo la autoridad frente a colisiones o dobles toques.
 */
class StartInvoiceDraftUseCase(
    private val appConfigurationRepository: AppConfigurationRepository,
    private val invoiceDraftRepository: InvoiceDraftRepository,
    private val appClock: AppClock,
) {
    suspend operator fun invoke(draftId: DraftId): InvoiceDraft {
        val businessId = appConfigurationRepository.current().activeBusinessId
            ?: throw StorageException(StorageError.Unavailable)
        val now = appClock.now()
        return invoiceDraftRepository.createDraft(
            InvoiceDraft(
                draftId = draftId,
                businessId = businessId,
                status = DraftStatus.CREATED,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }
}
