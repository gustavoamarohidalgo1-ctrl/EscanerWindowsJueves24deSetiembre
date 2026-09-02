package com.facturastock.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.facturastock.app.data.local.requireCanonicalUuid
import com.facturastock.app.data.local.requireText
import com.facturastock.app.data.local.requireTimestamps
import java.util.Locale

/**
 * Nombre o código con el que un proveedor identifica un producto en sus facturas. El alias es
 * único por negocio y proveedor; la búsqueda durante la vinculación usa el índice
 * `(businessId, aliasNormalized)`. Eliminar el proveedor o el producto elimina el alias.
 */
@Entity(
    tableName = "supplier_product_aliases",
    foreignKeys = [
        ForeignKey(
            entity = BusinessEntity::class,
            parentColumns = ["businessId"],
            childColumns = ["businessId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SupplierEntity::class,
            parentColumns = ["supplierId"],
            childColumns = ["supplierId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["productId"],
            childColumns = ["productId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["businessId", "supplierId", "aliasNormalized"], unique = true),
        Index(value = ["businessId", "aliasNormalized"]),
        Index(value = ["supplierId"]),
        Index(value = ["productId"]),
    ],
)
data class SupplierProductAliasEntity(
    @PrimaryKey val aliasId: String,
    val businessId: String,
    val supplierId: String,
    val productId: String,
    val alias: String,
    val createdAt: Long,
    val updatedAt: Long,
    val aliasNormalized: String = alias.trim().lowercase(Locale.ROOT),
) {
    init {
        requireCanonicalUuid(aliasId, "aliasId")
        requireCanonicalUuid(businessId, "businessId")
        requireCanonicalUuid(supplierId, "supplierId")
        requireCanonicalUuid(productId, "productId")
        requireText(alias, "alias", 200)
        requireText(aliasNormalized, "aliasNormalized", 200)
        requireTimestamps(createdAt, updatedAt)
    }
}
