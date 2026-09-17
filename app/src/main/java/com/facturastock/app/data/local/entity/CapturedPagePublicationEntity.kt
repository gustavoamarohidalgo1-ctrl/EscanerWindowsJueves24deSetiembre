package com.facturastock.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.facturastock.app.data.local.requireCanonicalUuid
import com.facturastock.app.data.local.requireSha256
import com.facturastock.app.data.local.requireText

/**
 * Recibo idempotente de una captura. Vive hasta que se elimina el borrador, incluso si la página
 * se reordena o se borra: así un ID reservado nunca puede reutilizarse con otra intención. El
 * tipo especial de reintento conserva el ID de la única página que resolvió la transacción.
 */
@Entity(
    tableName = "captured_page_publications",
    foreignKeys = [
        ForeignKey(
            entity = InvoiceDraftEntity::class,
            parentColumns = ["draftId"],
            childColumns = ["draftId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = BusinessEntity::class,
            parentColumns = ["businessId"],
            childColumns = ["businessId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["draftId"]),
        Index(value = ["businessId"]),
    ],
)
data class CapturedPagePublicationEntity(
    @PrimaryKey val imageId: String,
    val draftId: String,
    val businessId: String,
    val intentKind: String,
    val replaceTargetImageId: String?,
    val replacedFilePath: String?,
    val filePath: String,
    val sha256: String,
    val mimeType: String,
    val widthPx: Int,
    val heightPx: Int,
    val fileSizeBytes: Long,
    val rotationDegrees: Int,
    val publishedPageIndex: Int,
    val publishedAt: Long,
) {
    init {
        requireCanonicalUuid(imageId, "imageId")
        requireCanonicalUuid(draftId, "draftId")
        requireCanonicalUuid(businessId, "businessId")
        require(intentKind in INTENT_KINDS) { "intentKind inválido" }
        require((intentKind == APPEND) == (replaceTargetImageId == null)) {
            "APPEND no lleva objetivo y las intenciones de reemplazo exigen uno"
        }
        require((intentKind == APPEND) == (replacedFilePath == null)) {
            "APPEND no limpia archivo sustituido y las intenciones de reemplazo exigen su ruta"
        }
        replaceTargetImageId?.let { requireCanonicalUuid(it, "replaceTargetImageId") }
        replacedFilePath?.let { requireText(it, "replacedFilePath", 512) }
        requireText(filePath, "filePath", 512)
        requireSha256(sha256, "sha256")
        require(mimeType in ALLOWED_MIME_TYPES) { "mimeType de captura inválido" }
        require(widthPx > 0 && heightPx > 0) { "dimensiones de captura inválidas" }
        require(fileSizeBytes > 0L) { "tamaño de captura inválido" }
        require(rotationDegrees in ROTATIONS) { "rotación de captura inválida" }
        require(publishedPageIndex >= 0) { "publishedPageIndex inválido" }
        require(publishedAt >= 0L) { "publishedAt inválido" }
    }

    companion object {
        const val APPEND = "APPEND"
        const val REPLACE = "REPLACE"
        const val REPLACE_SOLE_INVOICE_SCAN = "REPLACE_SOLE_INVOICE_SCAN"
        private val INTENT_KINDS = setOf(APPEND, REPLACE, REPLACE_SOLE_INVOICE_SCAN)
        private val ALLOWED_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")
        private val ROTATIONS = setOf(0, 90, 180, 270)
    }
}
