package com.facturastock.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.facturastock.app.data.local.requireEnumName
import com.facturastock.app.data.local.requireCanonicalUuid
import com.facturastock.app.data.local.requireText
import com.facturastock.app.data.local.requireTimestamps
import com.facturastock.app.domain.model.CatalogStatus

/**
 * Ubicación física de inventario (almacén, mostrador, etc.), única por nombre dentro del
 * negocio. Un guard rechaza eliminar una ubicación referenciada; el catálogo la archiva.
 */
@Entity(
    tableName = "inventory_locations",
    foreignKeys = [
        ForeignKey(
            entity = BusinessEntity::class,
            parentColumns = ["businessId"],
            childColumns = ["businessId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["businessId", "name"], unique = true)],
)
data class InventoryLocationEntity(
    @PrimaryKey val locationId: String,
    val businessId: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val status: String = CatalogStatus.ACTIVE.name,
) {
    init {
        requireCanonicalUuid(locationId, "locationId")
        requireCanonicalUuid(businessId, "businessId")
        requireText(name, "name", 100)
        requireEnumName<CatalogStatus>(status, "status")
        requireTimestamps(createdAt, updatedAt)
    }
}
