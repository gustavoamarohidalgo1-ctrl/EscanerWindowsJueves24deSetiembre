package com.facturastock.app.domain.model

import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.ProductId
import java.math.BigDecimal

/** Motivo durable y presentable por el que una ganancia no puede calcularse. */
enum class ProductProfitIssue {
    MISSING_SALE_PRICE,
    NO_INVENTORY_BALANCE,
    NON_POSITIVE_STOCK,
    NEGATIVE_STOCK,
    MIXED_INVENTORY_CURRENCIES,
    SALE_PRICE_CURRENCY_MISMATCH,
    INVALID_PERSISTED_VALUE,
    DECIMAL_LIMIT_EXCEEDED,
}

enum class ProductProfitStatus {
    AVAILABLE,
    MISSING_SALE_PRICE,
    NO_STOCK,
    NON_COMPARABLE_CURRENCY,
    INVALID_INVENTORY,
}

/** Importe decimal firmado y con moneda; evita conversiones binarias con pérdida de precisión. */
data class ExactMonetaryAmount(
    val amount: BigDecimal,
    val currency: CurrencyCode,
) {
    init {
        require(ProductProfitDecimalPolicy.supports(amount)) {
            "El importe derivado excede la política decimal de ganancias"
        }
    }
}

/**
 * Proyección estimada antes de descuentos e impuestos.
 *
 * [salePrice] y [unitProfit] son por unidad de inventario. [potentialProfit] aplica la ganancia
 * al stock positivo actual. El margen es `(precio - costo) / precio * 100`, redondeado de forma
 * explícita a [PRODUCT_PROFIT_MARGIN_SCALE] decimales con HALF_EVEN.
 */
data class ProductProfit(
    val productId: ProductId,
    val businessId: BusinessId,
    val productName: String,
    val sku: String?,
    val unitCode: String,
    val productStatus: CatalogStatus,
    val productVersion: Long,
    val salePrice: Money?,
    val totalStockQuantity: BigDecimal?,
    val averageUnitCost: InventoryCostAmount?,
    val unitProfit: ExactMonetaryAmount?,
    val marginPercent: BigDecimal?,
    val potentialProfit: ExactMonetaryAmount?,
    val status: ProductProfitStatus,
    val issues: Set<ProductProfitIssue>,
) {
    init {
        require(productName.isNotBlank())
        require(unitCode.isNotBlank())
        require(productVersion >= 1L)
        totalStockQuantity?.let { require(ProductProfitDecimalPolicy.supports(it)) }
        if (status == ProductProfitStatus.AVAILABLE) {
            require(salePrice != null)
            require(totalStockQuantity != null && totalStockQuantity.signum() > 0)
            require(averageUnitCost != null)
            require(unitProfit != null)
            require(marginPercent != null)
            require(potentialProfit != null)
            require(issues.isEmpty())
        }
    }
}

const val PRODUCT_PROFIT_MARGIN_SCALE: Int = 4

/** Dos decimales persistidos de inventario pueden multiplicarse hasta 256/72. */
internal object ProductProfitDecimalPolicy {
    private const val MAX_PRECISION = 256
    private const val MAX_SCALE = 72

    fun supports(value: BigDecimal): Boolean =
        value.precision() <= MAX_PRECISION && value.scale() in 0..MAX_SCALE
}
