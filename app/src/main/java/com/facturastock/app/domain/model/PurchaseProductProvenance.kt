package com.facturastock.app.domain.model

import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.UnitId
import java.math.BigDecimal

/** Procedencia durable del producto usado por una linea de compra. */
enum class PurchaseProductProvenance {
    EXISTING,
    CREATED_IN_DRAFT,
    UNKNOWN_LEGACY,
}

/**
 * Producto nuevo congelado en el borrador, aun no visible en el catalogo. [productId] se genera
 * una sola vez al vincular y se reutiliza al confirmar para que los reintentos sean estables.
 */
data class StagedPurchaseProduct(
    val productId: ProductId,
    val businessId: BusinessId,
    val unitId: UnitId,
    val name: String,
    val sku: String? = null,
    val barcode: String? = null,
    val purchaseUnitId: UnitId? = null,
    val purchaseFactor: BigDecimal? = null,
    /** Precio por unidad de inventario; no se confunde con el costo de la factura. */
    val salePrice: Money? = null,
) {
    init {
        require(name.isNotBlank() && name.length <= 200) {
            "El producto staged necesita un nombre valido"
        }
        require(sku == CatalogCanonicalizer.sku(sku)) { "sku staged debe ser canonico" }
        require(barcode == CatalogCanonicalizer.barcode(barcode)) {
            "barcode staged debe ser canonico"
        }
        require((purchaseUnitId == null) == (purchaseFactor == null)) {
            "La unidad de compra staged y su factor se definen juntos"
        }
        purchaseFactor?.let { factor ->
            require(factor.signum() > 0 && ExactDecimalPolicy.supportsValue(factor)) {
                "El factor de compra staged debe ser positivo y exacto"
            }
        }
        salePrice?.let { price ->
            require(ProductSalePricePolicy.supports(price)) {
                "El precio de venta staged debe ser positivo e interoperable"
            }
        }
    }
}
