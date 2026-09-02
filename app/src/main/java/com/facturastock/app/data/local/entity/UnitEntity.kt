package com.facturastock.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.facturastock.app.data.local.requireEnumName
import com.facturastock.app.data.local.requireCanonicalUuid
import com.facturastock.app.data.local.requireText
import com.facturastock.app.data.local.requireTextOrNull
import com.facturastock.app.data.local.requireTimestamps
import com.facturastock.app.data.local.requireUnitCode
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.CatalogCanonicalizer

/**
 * Unidad de medida del catálogo (p. ej. NIU, KGM del catálogo 03 de SUNAT). El [code] se
 * normaliza a mayúsculas y es único por negocio. Una unidad referenciada no puede eliminarse;
 * se archiva para conservar productos, líneas OCR y compras históricas.
 */
@Entity(
    tableName = "units",
    foreignKeys = [
        ForeignKey(
            entity = BusinessEntity::class,
            parentColumns = ["businessId"],
            childColumns = ["businessId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["businessId", "code"], unique = true)],
)
data class UnitEntity(
    @PrimaryKey val unitId: String,
    val businessId: String,
    val code: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val symbol: String? = null,
    val status: String = CatalogStatus.ACTIVE.name,
) {
    init {
        requireCanonicalUuid(unitId, "unitId")
        requireCanonicalUuid(businessId, "businessId")
        requireUnitCode(code, "code")
        require(code == CatalogCanonicalizer.unitCode(code)) { "code debe almacenarse canónico" }
        requireText(name, "name", 100)
        requireTextOrNull(symbol, "symbol", 16)
        requireEnumName<CatalogStatus>(status, "status")
        requireTimestamps(createdAt, updatedAt)
    }
}
