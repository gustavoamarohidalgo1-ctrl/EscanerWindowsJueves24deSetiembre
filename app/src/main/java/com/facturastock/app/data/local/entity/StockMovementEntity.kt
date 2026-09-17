package com.facturastock.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.facturastock.app.data.local.requireCanonicalUuid
import com.facturastock.app.data.local.requireCanonicalUuidOrNull
import com.facturastock.app.data.local.requireCurrencyCode
import com.facturastock.app.data.local.requireCostingDecimalTextOrNull
import com.facturastock.app.data.local.requireEnumName
import com.facturastock.app.data.local.requireSignedNonZeroCostingDecimalText
import com.facturastock.app.data.local.requireText
import com.facturastock.app.domain.model.StockMovementType

/** Entrada inmutable del libro de stock. DAO/triggers reforzarán su semántica append-only. */
@Entity(
    tableName = "stock_movements",
    foreignKeys = [
        ForeignKey(
            entity = BusinessEntity::class,
            parentColumns = ["businessId"],
            childColumns = ["businessId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PurchaseEntity::class,
            parentColumns = ["purchaseId"],
            childColumns = ["purchaseId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = PurchaseLineEntity::class,
            parentColumns = ["purchaseLineId"],
            childColumns = ["purchaseLineId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = SaleEntity::class,
            parentColumns = ["saleId"],
            childColumns = ["saleId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = SaleLineEntity::class,
            parentColumns = ["saleLineId"],
            childColumns = ["saleLineId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["productId"],
            childColumns = ["productId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = InventoryLocationEntity::class,
            parentColumns = ["locationId"],
            childColumns = ["locationId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["idempotencyKey"], unique = true),
        Index(value = ["businessId", "productId", "locationId", "occurredAt"]),
        Index(value = ["businessId", "productId", "occurredAt", "createdAt", "movementId"]),
        Index(value = ["purchaseId", "occurredAt", "createdAt", "movementId"]),
        Index(value = ["purchaseLineId"]),
        Index(value = ["saleId", "occurredAt", "createdAt", "movementId"]),
        Index(value = ["saleLineId"]),
        Index(value = ["productId"]),
        Index(value = ["locationId"]),
    ],
)
data class StockMovementEntity(
    @PrimaryKey val movementId: String,
    val businessId: String,
    val purchaseId: String? = null,
    val purchaseLineId: String? = null,
    val saleId: String? = null,
    val saleLineId: String? = null,
    val productId: String,
    val locationId: String,
    val type: String,
    val quantityDelta: String,
    val unitCost: String? = null,
    val currencyCode: String,
    val idempotencyKey: String,
    val occurredAt: Long,
    val createdAt: Long,
) {
    init {
        requireCanonicalUuid(movementId, "movementId")
        requireCanonicalUuid(businessId, "businessId")
        requireCanonicalUuidOrNull(purchaseId, "purchaseId")
        requireCanonicalUuidOrNull(purchaseLineId, "purchaseLineId")
        requireCanonicalUuidOrNull(saleId, "saleId")
        requireCanonicalUuidOrNull(saleLineId, "saleLineId")
        requireCanonicalUuid(productId, "productId")
        requireCanonicalUuid(locationId, "locationId")
        requireEnumName<StockMovementType>(type, "type")
        requireSignedNonZeroCostingDecimalText(quantityDelta, "quantityDelta")
        requireCostingDecimalTextOrNull(unitCost, "unitCost", allowZero = true)
        requireCurrencyCode(currencyCode, "currencyCode")
        requireText(idempotencyKey, "idempotencyKey", 256)
        require(idempotencyKey == idempotencyKey.trim()) {
            "idempotencyKey no puede tener espacios exteriores"
        }
        require(occurredAt >= 0L) { "occurredAt no puede ser negativo: $occurredAt" }
        require(createdAt >= 0L) { "createdAt no puede ser negativo: $createdAt" }
        require((purchaseId == null) == (purchaseLineId == null)) {
            "purchaseId y purchaseLineId se definen juntos o no se definen"
        }
        require((saleId == null) == (saleLineId == null)) {
            "saleId y saleLineId se definen juntos o no se definen"
        }
        val hasPurchaseOrigin = purchaseId != null
        val hasSaleOrigin = saleId != null
        when (StockMovementType.valueOf(type)) {
            StockMovementType.PURCHASE,
            StockMovementType.VOID,
            -> require(hasPurchaseOrigin && !hasSaleOrigin) {
                "Los movimientos PURCHASE/VOID deben tener solo origen de compra"
            }
            StockMovementType.SALE,
            StockMovementType.SALE_VOID,
            -> require(hasSaleOrigin && !hasPurchaseOrigin) {
                "Los movimientos SALE/SALE_VOID deben tener solo origen de venta"
            }
            StockMovementType.ADJUSTMENT -> require(!hasPurchaseOrigin && !hasSaleOrigin) {
                "Los movimientos ADJUSTMENT no admiten origen de compra o venta"
            }
        }
        if (type == StockMovementType.SALE_VOID.name) {
            require(quantityDelta.toBigDecimal().signum() > 0 && unitCost != null)
            require(idempotencyKey == "sale-void-stock:v1:$saleId:$saleLineId")
        }
    }
}
