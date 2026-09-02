package com.facturastock.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.facturastock.app.data.local.requireCanonicalUuid
import com.facturastock.app.data.local.requireSha256
import com.facturastock.app.data.local.requireText

/**
 * Página capturada de un borrador. La base de datos guarda únicamente metadatos: [filePath]
 * es una ruta relativa al almacenamiento privado de la app (sin `..` ni raíz absoluta) y el
 * contenido nunca se persiste como BLOB. [sha256] permite detectar capturas duplicadas.
 * El recorte se expresa en coordenadas normalizadas (diezmilésimas 0..10000) sobre la
 * imagen ya rotada; las cuatro aristas vienen juntas o no hay recorte (`NULL` = imagen
 * completa). `(draftId, pageIndex)` es único: recapturar una página reemplaza el registro
 * anterior.
 */
@Entity(
    tableName = "invoice_images",
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
        Index(value = ["draftId", "pageIndex"], unique = true),
        Index(value = ["businessId"]),
        Index(value = ["businessId", "sha256"]),
        Index(value = ["filePath"]),
    ],
)
data class InvoiceImageEntity(
    @PrimaryKey val imageId: String,
    val draftId: String,
    val businessId: String,
    val pageIndex: Int,
    val filePath: String,
    val sha256: String,
    val mimeType: String,
    val widthPx: Int,
    val heightPx: Int,
    val fileSizeBytes: Long,
    val createdAt: Long,
    val rotationDegrees: Int = 0,
    val cropLeftFraction: Int? = null,
    val cropTopFraction: Int? = null,
    val cropRightFraction: Int? = null,
    val cropBottomFraction: Int? = null,
) {
    init {
        requireCanonicalUuid(imageId, "imageId")
        requireCanonicalUuid(draftId, "draftId")
        requireCanonicalUuid(businessId, "businessId")
        require(pageIndex >= 0) { "pageIndex no puede ser negativo: $pageIndex" }
        requireText(filePath, "filePath", 512)
        require(!filePath.startsWith("/") && !filePath.contains("..")) {
            "filePath debe ser relativo al almacenamiento privado: ${filePath.take(64)}"
        }
        requireSha256(sha256, "sha256")
        require(mimeType.startsWith("image/") && mimeType.length <= 64) {
            "mimeType debe ser de imagen: $mimeType"
        }
        require(widthPx > 0 && heightPx > 0) { "dimensiones inválidas: ${widthPx}x$heightPx" }
        require(fileSizeBytes > 0L) { "fileSizeBytes debe ser positivo: $fileSizeBytes" }
        require(rotationDegrees in ROTATIONS) { "rotationDegrees inválido: $rotationDegrees" }
        require(createdAt >= 0L) { "createdAt no puede ser negativo: $createdAt" }
        val edges = listOfNotNull(
            cropLeftFraction, cropTopFraction, cropRightFraction, cropBottomFraction,
        )
        require(edges.isEmpty() || edges.size == 4) {
            "el recorte define las cuatro aristas o ninguna"
        }
        if (edges.size == 4) {
            require(edges.all { it in 0..CROP_FRACTION_MAX }) {
                "fracción de recorte fuera de rango: $edges"
            }
            require(cropLeftFraction!! < cropRightFraction!!) {
                "recorte horizontal invertido: [$cropLeftFraction, $cropRightFraction]"
            }
            require(cropTopFraction!! < cropBottomFraction!!) {
                "recorte vertical invertido: [$cropTopFraction, $cropBottomFraction]"
            }
        }
    }

    private companion object {
        val ROTATIONS = setOf(0, 90, 180, 270)
        const val CROP_FRACTION_MAX = 10_000
    }
}
