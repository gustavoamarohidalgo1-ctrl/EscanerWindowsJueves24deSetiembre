package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.ImageCrop
import com.facturastock.app.domain.model.InvoiceImage
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.repository.InvoiceDraftRepository

/**
 * Fija o limpia el recorte normalizado de una página. El rectángulo ya viene validado por
 * la construcción de [ImageCrop] (imposible fuera de rango); un rectángulo de imagen
 * completa se normaliza a `null` (sin recorte). El archivo original no se toca: el recorte
 * vive en los metadatos y solo se materializa al generar la versión OCR.
 *
 * Devuelve la imagen actualizada; lanza `StorageException` si la página no existe.
 */
class CropDraftImageUseCase(
    private val invoiceDraftRepository: InvoiceDraftRepository,
) {
    suspend operator fun invoke(imageId: ImageId, crop: ImageCrop?): InvoiceImage {
        val normalized = crop?.takeUnless { it.isFullImage() }
        return invoiceDraftRepository.setImageCrop(imageId, normalized)
            ?: throw StorageException(StorageError.Unavailable)
    }
}
