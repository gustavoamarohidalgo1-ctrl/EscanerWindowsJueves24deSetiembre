package com.facturastock.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.facturastock.app.domain.model.AsciiPatterns

/** Sobrevive al borrado del borrador y permite reconocer un ACK local perdido. */
@Entity(
    tableName = "invoice_inventory_receipts",
    foreignKeys = [
        ForeignKey(
            entity = BusinessEntity::class,
            parentColumns = ["businessId"],
            childColumns = ["businessId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("businessId")],
)
data class InvoiceInventoryReceiptEntity(
    @PrimaryKey val draftId: String,
    val businessId: String,
    val contentHash: String,
    val appliedLineCount: Int,
    val appliedAt: Long,
) {
    init {
        com.facturastock.app.data.local
            .requireCanonicalUuid(draftId, "draftId")
        com.facturastock.app.data.local
            .requireCanonicalUuid(businessId, "businessId")
        require(AsciiPatterns.isLowerHex(contentHash, 64))
        require(appliedLineCount > 0)
        require(appliedAt >= 0)
    }
}
