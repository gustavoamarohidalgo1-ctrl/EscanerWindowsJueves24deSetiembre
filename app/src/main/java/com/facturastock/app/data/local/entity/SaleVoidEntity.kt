package com.facturastock.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.facturastock.app.data.local.requireCanonicalUuid
import com.facturastock.app.data.local.requireCurrencyCode
import com.facturastock.app.data.local.requireMinorUnits
import com.facturastock.app.data.local.requireText
import com.facturastock.app.domain.model.AsciiPatterns

/** Recibo inmutable: anula el efecto económico de la venta sin modificar su historia. */
@Entity(
    tableName = "sale_voids",
    foreignKeys = [
        ForeignKey(
            entity = SaleEntity::class,
            parentColumns = ["saleId"],
            childColumns = ["saleId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = BusinessEntity::class,
            parentColumns = ["businessId"],
            childColumns = ["businessId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("businessId")],
)
data class SaleVoidEntity(
    @PrimaryKey val saleId: String,
    val businessId: String,
    val impactHash: String,
    val refundedAmountMinorUnits: Long,
    val cancelledDebtBalanceMinorUnits: Long,
    val currencyCode: String,
    val actorId: String,
    val actorRole: String,
    val voidedAt: Long,
) {
    init {
        requireCanonicalUuid(saleId, "saleId")
        requireCanonicalUuid(businessId, "businessId")
        require(AsciiPatterns.isLowerHex(impactHash, 64))
        requireMinorUnits(refundedAmountMinorUnits, "refundedAmountMinorUnits")
        requireMinorUnits(cancelledDebtBalanceMinorUnits, "cancelledDebtBalanceMinorUnits")
        requireCurrencyCode(currencyCode, "currencyCode")
        requireText(actorId, "actorId", 128)
        requireText(actorRole, "actorRole", 32)
        require(actorId == actorId.trim() && actorRole == actorRole.trim())
        require(actorRole in setOf("OWNER", "MANAGER"))
        require(voidedAt >= 0L)
    }
}
