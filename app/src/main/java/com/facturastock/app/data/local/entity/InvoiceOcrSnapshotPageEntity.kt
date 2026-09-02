package com.facturastock.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import com.facturastock.app.data.local.requireCanonicalUuid
import com.facturastock.app.data.local.requireSha256

/** Una página serializada del snapshot; las filas pequeñas evitan una única BLOB documental. */
@Entity(
    tableName = "invoice_ocr_snapshot_pages",
    primaryKeys = ["draftId", "pageIndex"],
    foreignKeys = [
        ForeignKey(
            entity = InvoiceOcrSnapshotEntity::class,
            parentColumns = ["draftId"],
            childColumns = ["draftId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class InvoiceOcrSnapshotPageEntity(
    val draftId: String,
    val pageIndex: Int,
    val sourceImageId: String,
    val widthPx: Int,
    val heightPx: Int,
    val payloadSha256: String,
    val payload: ByteArray,
) {
    init {
        requireCanonicalUuid(draftId, "draftId")
        requireCanonicalUuid(sourceImageId, "sourceImageId")
        require(pageIndex >= 0) { "pageIndex no puede ser negativo: $pageIndex" }
        require(widthPx > 0 && heightPx > 0) {
            "Dimensiones OCR inválidas: ${widthPx}x$heightPx"
        }
        requireSha256(payloadSha256, "payloadSha256")
        require(payload.isNotEmpty()) { "payload OCR vacío" }
    }
}
