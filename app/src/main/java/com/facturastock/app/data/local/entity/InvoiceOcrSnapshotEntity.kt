package com.facturastock.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.facturastock.app.data.local.requireCanonicalUuid

/** Cabecera del único snapshot OCR publicado para un borrador. */
@Entity(
    tableName = "invoice_ocr_snapshots",
    foreignKeys = [
        ForeignKey(
            entity = InvoiceDraftEntity::class,
            parentColumns = ["draftId"],
            childColumns = ["draftId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["runId"], unique = true),
        Index(value = ["draftId", "runId"], unique = true),
    ],
)
data class InvoiceOcrSnapshotEntity(
    @PrimaryKey val draftId: String,
    val runId: String,
    val completedAt: Long,
    val codecVersion: Int,
    val pageCount: Int,
) {
    init {
        requireCanonicalUuid(draftId, "draftId")
        requireCanonicalUuid(runId, "runId")
        require(completedAt >= 0L) { "completedAt no puede ser negativo: $completedAt" }
        require(codecVersion > 0) { "codecVersion debe ser positivo: $codecVersion" }
        require(pageCount > 0) { "pageCount debe ser positivo: $pageCount" }
    }
}
