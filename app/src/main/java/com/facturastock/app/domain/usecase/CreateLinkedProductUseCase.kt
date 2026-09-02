package com.facturastock.app.domain.usecase

import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.CatalogCanonicalizer
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.ProductSalePricePolicy
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.StagedPurchaseProduct
import com.facturastock.app.domain.model.SupplierProductAlias
import com.facturastock.app.domain.model.id.AliasId
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.SupplierId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.repository.ProductRepository
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.SupplierProductAliasRepository
import java.math.BigDecimal
import java.util.Locale

/**
 * Datos de un producto nuevo creado desde la vinculación. La unidad de compra
 * ([purchaseUnitId]) y su factor hacia inventario ([purchaseFactor]) se definen juntos o no se
 * definen; el factor es siempre positivo (p. ej. caja de 12 → factor `12`).
 */
data class NewLinkedProduct(
    val businessId: BusinessId,
    val name: String,
    val unitId: UnitId,
    val sku: String? = null,
    val barcode: String? = null,
    val purchaseUnitId: UnitId? = null,
    val purchaseFactor: BigDecimal? = null,
    /** Precio por unidad de inventario, en la moneda configurada por el negocio. */
    val salePrice: Money? = null,
) {
    init {
        require(name.isNotBlank()) { "El nombre del producto no puede estar vacío" }
        require((purchaseUnitId == null) == (purchaseFactor == null)) {
            "La unidad de compra y su factor se definen juntos o no se definen"
        }
        if (purchaseFactor != null) {
            require(purchaseFactor.signum() > 0) { "El factor de compra debe ser positivo" }
        }
        salePrice?.let { price ->
            require(ProductSalePricePolicy.supports(price)) {
                "El precio de venta debe ser positivo e interoperable"
            }
        }
    }
}

/** Campo por el que un producto nuevo choca con uno existente. */
enum class LinkedProductDuplicateField {
    BARCODE,
    SKU,
    NAME,
}

sealed interface CreateLinkedProductResult {
    /** Producto congelado en el borrador; aun no existe en el catalogo. */
    data class Staged(val product: StagedPurchaseProduct) : CreateLinkedProductResult

    /** Ya existe un producto equivalente; la revisión ofrece vincular al existente. */
    data class Duplicate(
        val existing: Product,
        val field: LinkedProductDuplicateField,
    ) : CreateLinkedProductResult

    data object NoActiveBusiness : CreateLinkedProductResult

    data class BusinessMismatch(
        val active: BusinessId,
        val requested: BusinessId,
    ) : CreateLinkedProductResult

    data object SalePriceRequired : CreateLinkedProductResult

    data class CurrencyMismatch(
        val expected: CurrencyCode,
        val actual: CurrencyCode,
    ) : CreateLinkedProductResult
}

/**
 * Prepara un producto nuevo desde la vinculacion sin tocar el catalogo. Detecta duplicados
 * existentes (codigo de barras, SKU y nombre normalizado, en ese orden) y congela un ID estable
 * en el borrador. La insercion y sus aliases ocurren despues dentro de la transaccion de compra.
 */
class CreateLinkedProductUseCase(
    private val appConfigurationRepository: AppConfigurationRepository,
    private val productRepository: ProductRepository,
    private val uuidGenerator: UuidGenerator,
) {
    suspend operator fun invoke(draft: NewLinkedProduct): CreateLinkedProductResult {
        val configuration = appConfigurationRepository.current()
        val activeBusinessId = configuration.activeBusinessId
            ?: return CreateLinkedProductResult.NoActiveBusiness
        if (activeBusinessId != draft.businessId) {
            return CreateLinkedProductResult.BusinessMismatch(
                active = activeBusinessId,
                requested = draft.businessId,
            )
        }
        val barcode = CatalogCanonicalizer.barcode(draft.barcode)
        val sku = CatalogCanonicalizer.sku(draft.sku)
        barcode?.let {
            productRepository.findByBarcode(draft.businessId, barcode)?.let { existing ->
                return CreateLinkedProductResult.Duplicate(existing, LinkedProductDuplicateField.BARCODE)
            }
        }
        sku?.let {
            productRepository.findBySku(draft.businessId, sku)?.let { existing ->
                return CreateLinkedProductResult.Duplicate(existing, LinkedProductDuplicateField.SKU)
            }
        }
        productRepository.findByNormalizedName(draft.businessId, draft.name)
            .firstOrNull()?.let { existing ->
                return CreateLinkedProductResult.Duplicate(existing, LinkedProductDuplicateField.NAME)
            }

        val salePrice = draft.salePrice ?: return CreateLinkedProductResult.SalePriceRequired
        if (salePrice.currency != configuration.currency) {
            return CreateLinkedProductResult.CurrencyMismatch(
                expected = configuration.currency,
                actual = salePrice.currency,
            )
        }
        val candidate = StagedPurchaseProduct(
            productId = ProductId.from(uuidGenerator.newUuid()),
            businessId = draft.businessId,
            unitId = draft.unitId,
            name = draft.name.trim(),
            sku = sku,
            barcode = barcode,
            purchaseUnitId = draft.purchaseUnitId,
            purchaseFactor = draft.purchaseFactor,
            salePrice = salePrice,
        )
        return CreateLinkedProductResult.Staged(candidate)
    }
}

/**
 * Persiste el alias proveedor→producto únicamente tras una confirmación humana de vinculación
 * (nunca desde el OCR). Un alias idéntico al nombre normalizado del producto no aporta y se
 * omite; un choque de unicidad degrada a `false` (idempotente) en lugar de fallar la revisión.
 */
class SaveSupplierAliasUseCase(
    private val supplierProductAliasRepository: SupplierProductAliasRepository,
    private val uuidGenerator: UuidGenerator,
    private val appClock: AppClock,
) {
    /** Devuelve true si el alias confirmado quedó persistido. */
    suspend operator fun invoke(
        product: Product,
        supplierId: SupplierId,
        aliasText: String,
    ): Boolean {
        val trimmed = aliasText.trim()
        if (trimmed.isEmpty()) return false
        val normalizedName = product.name.trim().lowercase(Locale.ROOT)
        if (trimmed.lowercase(Locale.ROOT) == normalizedName) return false
        val now = appClock.now()
        return try {
            supplierProductAliasRepository.create(
                SupplierProductAlias(
                    aliasId = AliasId.from(uuidGenerator.newUuid()),
                    businessId = product.businessId,
                    supplierId = supplierId,
                    productId = product.productId,
                    alias = trimmed,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            true
        } catch (exception: StorageException) {
            if (exception.error is StorageError.ConstraintConflict) {
                false
            } else {
                throw exception
            }
        }
    }
}
