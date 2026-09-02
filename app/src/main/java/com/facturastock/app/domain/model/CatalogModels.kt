package com.facturastock.app.domain.model

import com.facturastock.app.domain.model.id.AliasId
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.SupplierId
import com.facturastock.app.domain.model.id.UnitId
import java.math.BigDecimal
import java.time.Instant

/**
 * Negocio propietario de los datos. Su [ruc] es opcional (onboarding y modo demo pueden no
 * tenerlo); cuando está presente es único globalmente y los `NULL` no colisionan.
 */
data class Business(
    val businessId: BusinessId,
    val legalName: String,
    val ruc: String? = null,
    val tradeName: String? = null,
    val status: CatalogStatus = CatalogStatus.ACTIVE,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * Proveedor del catálogo. El RUC es único por negocio cuando existe.
 *
 * [version] es la concurrencia optimista: el guardado la exige como CAS y la incrementa; una
 * edición sobre una versión obsoleta no escribe nada y se reporta como conflicto de lectura.
 */
data class Supplier(
    val supplierId: SupplierId,
    val businessId: BusinessId,
    val legalName: String,
    val ruc: String? = null,
    val tradeName: String? = null,
    val status: CatalogStatus = CatalogStatus.ACTIVE,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long = 1,
)

/**
 * Unidad de medida del catálogo (p. ej. NIU, KGM del catálogo 03 de SUNAT). Se llama
 * `UnitOfMeasure` y no `Unit` para no chocar con `kotlin.Unit`. El [code] se normaliza a
 * mayúsculas y es único por negocio.
 */
data class UnitOfMeasure(
    val unitId: UnitId,
    val businessId: BusinessId,
    val code: String,
    val name: String,
    val symbol: String? = null,
    val status: CatalogStatus = CatalogStatus.ACTIVE,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * Ubicación física de inventario (almacén, mostrador, etc.), única por nombre dentro del
 * negocio.
 */
data class InventoryLocation(
    val locationId: LocationId,
    val businessId: BusinessId,
    val name: String,
    val status: CatalogStatus = CatalogStatus.ACTIVE,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * Producto del catálogo. [sku] y [barcode] son únicos por negocio cuando existen. La unidad es
 * obligatoria; la ubicación es opcional.
 *
 * [purchaseUnitId] y [purchaseFactor] describen cómo se compra el producto cuando la unidad de
 * compra difiere de la de inventario ([unitId]): p. ej. una caja ([purchaseUnitId]) que contiene
 * 12 ([purchaseFactor]) unidades. Son ambos nulos o ambos presentes, y el factor es siempre
 * positivo y exacto según la política decimal del dominio.
 *
 * [salePrice] es el precio de venta por una [unitId] de inventario. Puede ser nulo para productos
 * migrados que todavía no han sido valorizados; cuando existe es positivo y conserva su moneda.
 *
 * [version] es la concurrencia optimista: el guardado la exige como CAS y la incrementa; una
 * edición sobre una versión obsoleta no escribe nada y se reporta como conflicto de lectura.
 */
data class Product(
    val productId: ProductId,
    val businessId: BusinessId,
    val unitId: UnitId,
    val name: String,
    val locationId: LocationId? = null,
    val sku: String? = null,
    val barcode: String? = null,
    val purchaseUnitId: UnitId? = null,
    val purchaseFactor: BigDecimal? = null,
    val salePrice: Money? = null,
    val status: CatalogStatus = CatalogStatus.ACTIVE,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long = 1,
) {
    init {
        require((purchaseUnitId == null) == (purchaseFactor == null)) {
            "La unidad de compra y su factor se definen juntos o no se definen."
        }
        if (purchaseFactor != null) {
            require(purchaseFactor.signum() > 0 && ExactDecimalPolicy.supportsValue(purchaseFactor)) {
                "El factor de compra debe ser positivo y exacto."
            }
        }
        salePrice?.let { price ->
            require(ProductSalePricePolicy.supports(price)) {
                "El precio de venta debe ser positivo e interoperable."
            }
        }
    }

    /**
     * Convierte una cantidad expresada en la unidad de compra a unidades de inventario. Sin
     * factor configurado la cantidad ya está en unidades de inventario (conversión identidad).
     */
    fun inventoryUnitsFor(purchaseQuantity: Quantity): Quantity =
        purchaseFactor?.let(purchaseQuantity::times) ?: purchaseQuantity
}

/**
 * Nombre o código con el que un proveedor identifica un producto en sus facturas. El alias es
 * único por negocio y proveedor; la forma normalizada para búsquedas es un derivado de
 * persistencia y no forma parte del dominio.
 */
data class SupplierProductAlias(
    val aliasId: AliasId,
    val businessId: BusinessId,
    val supplierId: SupplierId,
    val productId: ProductId,
    val alias: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)
