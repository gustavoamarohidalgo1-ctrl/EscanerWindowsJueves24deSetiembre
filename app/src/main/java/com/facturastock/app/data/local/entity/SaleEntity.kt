package com.facturastock.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.facturastock.app.data.local.requireCanonicalUuid
import com.facturastock.app.data.local.requireCurrencyCode
import com.facturastock.app.data.local.requireEnumName
import com.facturastock.app.data.local.requireMinorUnits
import com.facturastock.app.data.local.requireSha256
import com.facturastock.app.data.local.requireText
import com.facturastock.app.data.local.requireTimestamps
import com.facturastock.app.domain.model.SaleStatus

/** Venta local; mientras está DRAFT es el carrito persistente y versionado. */
@Entity(
    tableName = "sales",
    foreignKeys = [
        ForeignKey(
            entity = BusinessEntity::class,
            parentColumns = ["businessId"],
            childColumns = ["businessId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["businessId", "status", "postedAt", "saleId"]),
        /** DRAFT ocupa este slot; POSTED lo libera poniéndolo en NULL. */
        Index(value = ["draftSlot"], unique = true),
        Index(value = ["checkoutIdempotencyKey"], unique = true),
    ],
)
data class SaleEntity(
    @PrimaryKey val saleId: String,
    val businessId: String,
    val status: String,
    val currencyCode: String,
    val subtotalMinorUnits: Long,
    val discountMinorUnits: Long,
    val taxMinorUnits: Long,
    val totalMinorUnits: Long,
    val contentHash: String,
    val draftSlot: String? = null,
    val checkoutIdempotencyKey: String? = null,
    val version: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val postedAt: Long? = null,
) {
    init {
        requireCanonicalUuid(saleId, "saleId")
        requireCanonicalUuid(businessId, "businessId")
        requireEnumName<SaleStatus>(status, "status")
        requireCurrencyCode(currencyCode, "currencyCode")
        requireMinorUnits(subtotalMinorUnits, "subtotalMinorUnits")
        requireMinorUnits(discountMinorUnits, "discountMinorUnits")
        requireMinorUnits(taxMinorUnits, "taxMinorUnits")
        requireMinorUnits(totalMinorUnits, "totalMinorUnits")
        require(discountMinorUnits <= subtotalMinorUnits) {
            "discountMinorUnits no puede superar subtotalMinorUnits"
        }
        val expectedTotal = try {
            Math.addExact(Math.subtractExact(subtotalMinorUnits, discountMinorUnits), taxMinorUnits)
        } catch (failure: ArithmeticException) {
            throw IllegalArgumentException("Los totales de venta desbordan Long", failure)
        }
        require(totalMinorUnits == expectedTotal) { "totalMinorUnits no cuadra" }
        requireSha256(contentHash, "contentHash")
        draftSlot?.let { requireText(it, "draftSlot", 80) }
        checkoutIdempotencyKey?.let {
            requireText(it, "checkoutIdempotencyKey", 256)
            require(it == it.trim()) { "checkoutIdempotencyKey debe estar recortada" }
        }
        require(version >= 0L) { "version no puede ser negativa" }
        requireTimestamps(createdAt, updatedAt)
        require(postedAt == null || postedAt in createdAt..updatedAt) {
            "postedAt debe estar entre createdAt y updatedAt"
        }
        when (SaleStatus.valueOf(status)) {
            SaleStatus.DRAFT -> require(
                postedAt == null && checkoutIdempotencyKey == null &&
                    (
                        draftSlot == "$businessId:$currencyCode" ||
                            draftSlot == "remote:$saleId"
                    ),
            ) {
                "Una venta DRAFT no puede estar confirmada"
            }
            SaleStatus.POSTED -> require(
                postedAt != null && checkoutIdempotencyKey != null && draftSlot == null,
            ) {
                "Una venta POSTED requiere postedAt y clave idempotente"
            }
        }
    }
}
