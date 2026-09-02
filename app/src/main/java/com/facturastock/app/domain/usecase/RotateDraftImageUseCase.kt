package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.InvoiceImage
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.repository.InvoiceDraftRepository

/**
 * Gira una página 90° en sentido horario mediante una mutación atómica sobre la fila vigente.
 * El repositorio transforma también el recorte al nuevo marco dentro de la misma transacción:
 * una edición concurrente no puede perderse por una copia obsoleta. Cuatro giros devuelven la
 * página a su orientación original (rotación y recorte incluidos).
 *
 * Devuelve la imagen actualizada; lanza `StorageException` si la página no existe.
 */
class RotateDraftImageUseCase(
    private val invoiceDraftRepository: InvoiceDraftRepository,
) {
    suspend operator fun invoke(imageId: ImageId): InvoiceImage {
        return invoiceDraftRepository.rotateImage90(imageId)
            ?: throw StorageException(StorageError.Unavailable)
    }
}
