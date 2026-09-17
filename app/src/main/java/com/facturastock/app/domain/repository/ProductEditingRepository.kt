package com.facturastock.app.domain.repository

import com.facturastock.app.domain.model.CatalogDuplicateField
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import java.math.BigDecimal

/** Una lectura consistente del producto y de cada saldo, también de su ausencia. */
data class ProductEditingSnapshot(
    val product: Product,
    val positions: List<ProductEditingPosition>,
    val inventoryEditable: Boolean,
    val defaultCurrency: CurrencyCode = CurrencyCode.of("PEN"),
)

data class ProductEditingPosition(
    val locationId: LocationId,
    val locationName: String,
    val locationStatus: CatalogStatus,
    val quantityOnHand: BigDecimal,
    /** null significa costo desconocido; nunca debe precargarse como un cero conocido. */
    val averageUnitCost: BigDecimal?,
    val currency: CurrencyCode,
    /** null es el CAS de ausencia, no la versión cero de un saldo existente. */
    val balanceVersion: Long?,
)

/** Cantidad final en la unidad de inventario y costo promedio final de una sola ubicación. */
data class ProductStockEdit(
    val locationId: LocationId,
    val quantityOnHand: BigDecimal,
    /**
     * null conserva el costo; un cero explícito establece un costo conocido igual a cero.
     * Un costo nuevo admite precisión38/escala18; conservar un promedio derivado existente
     * admite toda su precisión128/escala36 sin redondeo.
     */
    val averageUnitCost: BigDecimal?,
)

enum class ProductEditingInvalidField {
    NAME,
    SKU,
    BARCODE,
    QUANTITY,
    COST,
    LOCATION,
    CURRENCY,
    OWNERSHIP,
    IMMUTABLE_FIELD,
    SHARED_INVENTORY,
}

sealed interface ProductEditingResult {
    data class Saved(
        val snapshot: ProductEditingSnapshot,
        val changed: Boolean,
    ) : ProductEditingResult

    data object Stale : ProductEditingResult

    data object NotFound : ProductEditingResult

    data class Duplicate(
        val field: CatalogDuplicateField,
    ) : ProductEditingResult

    data class Invalid(
        val field: ProductEditingInvalidField,
    ) : ProductEditingResult
}

interface ProductEditingRepository {
    suspend fun load(
        businessId: BusinessId,
        productId: ProductId,
        defaultCurrency: CurrencyCode,
    ): ProductEditingSnapshot?

    /**
     * Confirma metadatos y todos los saldos editados en una sola transacción. Sólo nombre,
     * SKU, código de barras y precio de venta son editables en [candidate]. Cada saldo usa
     * su propia versión original; las ubicaciones no incluidas permanecen intactas. Los
     * movimientos anteriores nunca se modifican: las diferencias son ajustes nuevos.
     * Un conflicto o fallo no confirma parte del formulario. Un no-op no avanza versiones.
     */
    suspend fun save(
        expected: ProductEditingSnapshot,
        candidate: Product,
        stockEdits: List<ProductStockEdit>,
    ): ProductEditingResult
}
