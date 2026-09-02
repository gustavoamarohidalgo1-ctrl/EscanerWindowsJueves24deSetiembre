package com.facturastock.app.domain.repository

import com.facturastock.app.domain.model.InvoiceImage
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId

/**
 * Puerto que materializa una versión preparada exclusivamente para OCR sin modificar el
 * original. La rotación registrada en [InvoiceImage] incluye la orientación EXIF rescatada
 * durante la importación; después se aplica el recorte, una reducción proporcional, escala
 * de grises, contraste moderado y compresión JPEG documentada por la implementación.
 */
interface InvoiceImagePreprocessor {
    /** Prepara y publica como un único conjunto las páginas del snapshot indicado. */
    suspend fun preprocess(
        draftId: DraftId,
        images: List<InvoiceImage>,
    ): List<OcrImageFile>

    /** Lee únicamente el último conjunto completo publicado para [draftId]. */
    suspend fun findPrepared(draftId: DraftId): List<OcrImageFile>

    /** Elimina únicamente versiones OCR regenerables del borrador. */
    suspend fun clearOcrVersions(draftId: DraftId)
}

/** Metadatos de la copia OCR; [relativePath] nunca apunta al original. */
data class OcrImageFile(
    val sourceImageId: ImageId,
    val relativePath: String,
    val mimeType: String,
    val widthPx: Int,
    val heightPx: Int,
    val fileSizeBytes: Long,
)
