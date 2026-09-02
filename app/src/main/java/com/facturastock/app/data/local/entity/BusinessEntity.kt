package com.facturastock.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.facturastock.app.data.local.requireEnumName
import com.facturastock.app.data.local.requireCanonicalUuid
import com.facturastock.app.data.local.requireRucOrNull
import com.facturastock.app.data.local.requireText
import com.facturastock.app.data.local.requireTextOrNull
import com.facturastock.app.data.local.requireTimestamps
import com.facturastock.app.domain.model.CatalogStatus

/**
 * Negocio propietario de los datos. Toda unicidad operativa del esquema es por negocio. El
 * [ruc] es opcional (el onboarding y el modo demo pueden no tenerlo); cuando está presente es
 * único globalmente y los `NULL` no colisionan en el índice único. [status] persiste
 * [CatalogStatus] por nombre.
 */
@Entity(
    tableName = "businesses",
    indices = [Index(value = ["ruc"], unique = true)],
)
data class BusinessEntity(
    @PrimaryKey val businessId: String,
    val legalName: String,
    val createdAt: Long,
    val updatedAt: Long,
    val ruc: String? = null,
    val tradeName: String? = null,
    val status: String = CatalogStatus.ACTIVE.name,
) {
    init {
        requireCanonicalUuid(businessId, "businessId")
        requireText(legalName, "legalName", 200)
        requireRucOrNull(ruc, "ruc")
        requireTextOrNull(tradeName, "tradeName", 200)
        requireEnumName<CatalogStatus>(status, "status")
        requireTimestamps(createdAt, updatedAt)
    }
}
