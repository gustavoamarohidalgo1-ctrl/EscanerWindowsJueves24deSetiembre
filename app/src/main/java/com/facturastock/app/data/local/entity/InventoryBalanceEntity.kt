package com.facturastock.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.facturastock.app.data.local.requireCanonicalUuid
import com.facturastock.app.data.local.requireCostingDecimalText
import com.facturastock.app.data.local.requireCurrencyCode
import com.facturastock.app.data.local.requireDecimalText

/** Saldo materializado y versionado de un producto en un almacén. */
@Entity(
    tableName = "inventory_balances",
    primaryKeys = ["businessId", "productId", "locationId"],
    foreignKeys = [
        ForeignKey(
            entity = BusinessEntity::class,
            parentColumns = ["businessId"],
            childColumns = ["businessId"],
            onDelete = ForeignKey.CASCADE,
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
        Index(value = ["productId"]),
        Index(value = ["locationId"]),
        Index(value = ["businessId", "locationId", "productId"]),
    ],
)
data class InventoryBalanceEntity(
    val businessId: String,
    val productId: String,
    val locationId: String,
    val quantityOnHand: String,
    val averageUnitCost: String,
    val currencyCode: String,
    val version: Long,
    val updatedAt: Long,
) {
    init {
        requireCanonicalUuid(businessId, "businessId")
        requireCanonicalUuid(productId, "productId")
        requireCanonicalUuid(locationId, "locationId")
        // Un ajuste o una importación histórica puede dejar existencia negativa. La próxima
        // entrada reinicia el promedio según InventoryCostingService; el costo nunca es firmado.
        requireCostingDecimalText(
            quantityOnHand,
            "quantityOnHand",
            allowZero = true,
            allowNegative = true,
        )
        requireCostingDecimalText(averageUnitCost, "averageUnitCost", allowZero = true)
        requireCurrencyCode(currencyCode, "currencyCode")
        require(version >= 0L) { "version no puede ser negativa: $version" }
        require(updatedAt >= 0L) { "updatedAt no puede ser negativo: $updatedAt" }
    }
}
