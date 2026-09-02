package com.facturastock.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.facturastock.app.data.local.requireDecimalTextOrNull
import com.facturastock.app.data.local.requireEnumName
import com.facturastock.app.data.local.requireCanonicalUuid
import com.facturastock.app.data.local.requireCanonicalUuidOrNull
import com.facturastock.app.data.local.requireCurrencyCodeOrNull
import com.facturastock.app.data.local.requireMinorUnitsOrNull
import com.facturastock.app.data.local.requirePersistedLegacyBarcodeOrNull
import com.facturastock.app.data.local.requireText
import com.facturastock.app.data.local.requireTextOrNull
import com.facturastock.app.data.local.requireTimestamps
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.CatalogCanonicalizer
import com.facturastock.app.domain.model.ProductSalePricePolicy
import java.util.Locale

/**
 * Producto del catálogo. `sku` y `barcode` son únicos por negocio cuando existen (varios
 * productos pueden carecer de ellos porque los `NULL` no colisionan). `normalizedName` se
 * deriva de [name] para búsquedas insensibles a mayúsculas. La unidad es obligatoria y se
 * protege con `RESTRICT`; la ubicación es opcional. Los guards de catálogo evitan `DELETE`
 * directo de cualquier fila referenciada aunque una FK legada declare `SET_NULL`.
 * `purchaseUnitId`/`purchaseFactor` describen la unidad de compra cuando difiere de la de
 * inventario (p. ej. caja de 12): son ambos `NULL` o ambos presentes, el factor se guarda en
 * texto plano (`BigDecimal.toPlainString`) y la unidad de compra se desvincula con `SET_NULL`.
 */
@Entity(
    tableName = "products",
    foreignKeys = [
        ForeignKey(
            entity = BusinessEntity::class,
            parentColumns = ["businessId"],
            childColumns = ["businessId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = UnitEntity::class,
            parentColumns = ["unitId"],
            childColumns = ["unitId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = InventoryLocationEntity::class,
            parentColumns = ["locationId"],
            childColumns = ["locationId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = UnitEntity::class,
            parentColumns = ["unitId"],
            childColumns = ["purchaseUnitId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["businessId", "sku"], unique = true),
        Index(value = ["businessId", "barcode"], unique = true),
        Index(value = ["businessId", "normalizedName"]),
        Index(value = ["unitId"]),
        Index(value = ["locationId"]),
        Index(value = ["purchaseUnitId"]),
    ],
)
data class ProductEntity(
    @PrimaryKey val productId: String,
    val businessId: String,
    val unitId: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val locationId: String? = null,
    val sku: String? = null,
    val barcode: String? = null,
    val normalizedName: String = name.trim().lowercase(Locale.ROOT),
    val purchaseUnitId: String? = null,
    val purchaseFactor: String? = null,
    val salePriceMinorUnits: Long? = null,
    val salePriceCurrencyCode: String? = null,
    val status: String = CatalogStatus.ACTIVE.name,
    /**
     * Versión optimista: todo `UPDATE` la exige como CAS y la incrementa en SQL, de modo que
     * dos ediciones concurrentes nunca se pisan en silencio (la segunda afecta cero filas).
     */
    val version: Long = 1,
) {
    init {
        requireCanonicalUuid(productId, "productId")
        requireCanonicalUuid(businessId, "businessId")
        requireCanonicalUuid(unitId, "unitId")
        requireCanonicalUuidOrNull(locationId, "locationId")
        requireCanonicalUuidOrNull(purchaseUnitId, "purchaseUnitId")
        requireText(name, "name", 200)
        requireText(normalizedName, "normalizedName", 200)
        requireTextOrNull(sku, "sku", 64)
        // Room también construye esta entidad al leer. Una fila v1..v20 puede contener Unicode
        // previamente válido; se conserva para lectura, pero los mappers y triggers de escritura
        // siguen exigiendo el contrato ASCII actual.
        requirePersistedLegacyBarcodeOrNull(barcode, "barcode")
        require(sku == CatalogCanonicalizer.sku(sku)) { "sku debe almacenarse canónico" }
        requireDecimalTextOrNull(purchaseFactor, "purchaseFactor", allowZero = false)
        require((purchaseUnitId == null) == (purchaseFactor == null)) {
            "purchaseUnitId y purchaseFactor se definen juntos o no se definen."
        }
        requireMinorUnitsOrNull(salePriceMinorUnits, "salePriceMinorUnits")
        requireCurrencyCodeOrNull(salePriceCurrencyCode, "salePriceCurrencyCode")
        require((salePriceMinorUnits == null) == (salePriceCurrencyCode == null)) {
            "salePriceMinorUnits y salePriceCurrencyCode se definen juntos o no se definen."
        }
        salePriceMinorUnits?.let { price ->
            require(price in 1L..ProductSalePricePolicy.MAX_MINOR_UNITS) {
                "salePriceMinorUnits debe ser positivo e interoperable"
            }
        }
        requireEnumName<CatalogStatus>(status, "status")
        requireTimestamps(createdAt, updatedAt)
        require(version >= 1L) { "version debe ser >= 1: $version" }
    }
}
