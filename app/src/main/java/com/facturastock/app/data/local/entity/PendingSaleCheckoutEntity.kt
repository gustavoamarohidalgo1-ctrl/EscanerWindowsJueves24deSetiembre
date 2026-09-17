package com.facturastock.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.facturastock.app.data.local.requireCanonicalUuid
import com.facturastock.app.data.local.requireSha256

/** Intención durable: el carrito permanece congelado hasta conocer el resultado del checkout. */
@Entity(
    tableName = "pending_sale_checkouts",
    foreignKeys = [
        ForeignKey(
            entity = SaleEntity::class,
            parentColumns = ["saleId"],
            childColumns = ["saleId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = BusinessEntity::class,
            parentColumns = ["businessId"],
            childColumns = ["businessId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["businessId"])],
)
data class PendingSaleCheckoutEntity(
    @PrimaryKey val saleId: String,
    val businessId: String,
    val expectedVersion: Long,
    val contentHash: String,
    val checkoutIdempotencyKey: String,
    val debtorName: String?,
    val debtDueAt: Long?,
    val cloudBusinessId: String?,
    val createdAt: Long,
) {
    init {
        requireCanonicalUuid(saleId, "saleId")
        requireCanonicalUuid(businessId, "businessId")
        cloudBusinessId?.let { requireCanonicalUuid(it, "cloudBusinessId") }
        requireSha256(contentHash, "contentHash")
        require(expectedVersion >= 0L && createdAt >= 0L)
        require(debtorName != null || debtDueAt == null)
    }
}
