package com.facturastock.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.facturastock.app.data.local.requireEnumName
import com.facturastock.app.data.local.requireCanonicalUuid
import com.facturastock.app.data.local.requireRucOrNull
import com.facturastock.app.data.local.requireText
import com.facturastock.app.data.local.requireTextOrNull
import com.facturastock.app.data.local.requireTimestamps
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.CatalogCanonicalizer

/**
 * Proveedor del catálogo. El RUC es único por negocio cuando existe; SQLite admite varios
 * proveedores sin RUC porque los `NULL` no colisionan en el índice único. Eliminar el negocio
 * elimina al proveedor; un guard rechaza eliminar directamente un proveedor referenciado.
 */
@Entity(
    tableName = "suppliers",
    foreignKeys = [
        ForeignKey(
            entity = BusinessEntity::class,
            parentColumns = ["businessId"],
            childColumns = ["businessId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["businessId", "ruc"], unique = true)],
)
data class SupplierEntity(
    @PrimaryKey val supplierId: String,
    val businessId: String,
    val legalName: String,
    val createdAt: Long,
    val updatedAt: Long,
    val ruc: String? = null,
    val tradeName: String? = null,
    val status: String = CatalogStatus.ACTIVE.name,
    /** Versión optimista: el `UPDATE` la exige como CAS y la incrementa en la misma sentencia. */
    val version: Long = 1,
) {
    init {
        requireCanonicalUuid(supplierId, "supplierId")
        requireCanonicalUuid(businessId, "businessId")
        requireText(legalName, "legalName", 200)
        requireRucOrNull(ruc, "ruc")
        require(ruc == CatalogCanonicalizer.ruc(ruc)) { "ruc debe almacenarse canónico" }
        requireTextOrNull(tradeName, "tradeName", 200)
        requireEnumName<CatalogStatus>(status, "status")
        requireTimestamps(createdAt, updatedAt)
        require(version >= 1L) { "version debe ser >= 1: $version" }
    }
}
