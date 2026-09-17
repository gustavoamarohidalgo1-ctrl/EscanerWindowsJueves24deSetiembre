package com.facturastock.app.domain.model

import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.SaleId
import com.facturastock.app.domain.model.id.SaleLineId
import com.facturastock.app.domain.model.id.UnitId
import java.math.RoundingMode
import java.time.Instant

/** Línea comercial: el precio es explícito y nunca se deriva del costo promedio. */
data class SaleCartLine(
    val saleLineId: SaleLineId,
    val productId: ProductId,
    val unitId: UnitId,
    val locationId: LocationId,
    val position: Int,
    val productName: String,
    val unitCode: String,
    val locationName: String,
    val barcode: String?,
    val quantity: Quantity,
    /** Nulo hasta que la persona ingresa un precio comercial; venta gratuita no se admite en v1. */
    val unitPrice: Money?,
    val discount: Money,
    val tax: Money,
    val lineTotal: Money?,
) {
    init {
        require(position >= 0) { "position no puede ser negativa" }
        require(productName.isNotBlank()) { "productName no puede estar vacío" }
        require(unitCode.isNotBlank()) { "unitCode no puede estar vacío" }
        require(locationName.isNotBlank()) { "locationName no puede estar vacío" }
        require(discount.minorUnits >= 0L) { "discount no puede ser negativo" }
        require(tax.minorUnits >= 0L) { "tax no puede ser negativo" }
        if (unitPrice == null) {
            require(lineTotal == null && discount.minorUnits == 0L && tax.minorUnits == 0L) {
                "Una línea sin precio no puede tener total, descuento ni impuesto"
            }
            require(discount.currency == tax.currency) { "Los importes deben compartir moneda" }
        } else {
            require(unitPrice.minorUnits > 0L) { "La venta gratuita no está soportada en v1" }
            requireNotNull(lineTotal) { "Una línea con precio requiere lineTotal" }
            require(
                setOf(unitPrice.currency, discount.currency, tax.currency, lineTotal.currency).size == 1,
            ) { "Los importes de la línea deben compartir moneda" }
            require(lineTotal == calculateSaleLineTotal(unitPrice, quantity, discount, tax)) {
                "lineTotal no coincide con precio, cantidad, descuento e impuesto"
            }
        }
    }
}

data class PendingSaleCheckout(
    val debtorName: String?,
    val debtDueAt: Instant?,
)

data class SaleCart(
    val saleId: SaleId,
    val businessId: BusinessId,
    val status: SaleStatus,
    val currency: CurrencyCode,
    val lines: List<SaleCartLine>,
    val subtotal: Money,
    val discount: Money,
    val tax: Money,
    val total: Money,
    /** SHA-256 del contenido y orden exactos que se someterán a doble confirmación. */
    val contentHash: String,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val postedAt: Instant?,
    val pendingCheckout: PendingSaleCheckout? = null,
) {
    init {
        require(version >= 0L) { "version no puede ser negativa" }
        require(CONTENT_HASH.matches(contentHash)) { "contentHash debe ser SHA-256 canónico" }
        require(lines.map(SaleCartLine::position) == lines.indices.toList()) {
            "Las posiciones del carrito deben ser densas y ordenadas"
        }
        require(lines.map(SaleCartLine::saleLineId).distinct().size == lines.size) {
            "El carrito no puede repetir saleLineId"
        }
        require(lines.all { (it.unitPrice?.currency ?: it.discount.currency) == currency }) {
            "Todas las líneas deben usar la moneda de la venta"
        }
        require(setOf(subtotal.currency, discount.currency, tax.currency, total.currency) == setOf(currency)) {
            "Los totales deben usar la moneda de la venta"
        }
        val expectedSubtotal = lines.fold(0L) { running, line ->
            val gross = line.unitPrice?.let { calculateSaleLineGross(it, line.quantity).minorUnits } ?: 0L
            Math.addExact(running, gross)
        }
        val expectedDiscount = lines.fold(0L) { running, line ->
            Math.addExact(running, line.discount.minorUnits)
        }
        val expectedTax = lines.fold(0L) { running, line ->
            Math.addExact(running, line.tax.minorUnits)
        }
        val expectedTotal = Math.addExact(
            Math.subtractExact(expectedSubtotal, expectedDiscount),
            expectedTax,
        )
        require(
            subtotal.minorUnits == expectedSubtotal && discount.minorUnits == expectedDiscount &&
                tax.minorUnits == expectedTax && total.minorUnits == expectedTotal,
        ) { "Los totales del carrito no coinciden con sus líneas" }
        require(updatedAt >= createdAt) { "updatedAt no puede preceder createdAt" }
        when (status) {
            SaleStatus.DRAFT -> require(postedAt == null) { "Una venta DRAFT no puede tener postedAt" }
            SaleStatus.POSTED -> {
                require(postedAt != null && postedAt in createdAt..updatedAt) {
                    "Una venta POSTED requiere postedAt válido"
                }
                require(lines.isNotEmpty()) { "Una venta POSTED debe tener líneas" }
                require(lines.all { it.unitPrice != null && it.lineTotal != null }) {
                    "Una venta POSTED no puede contener líneas sin precio confirmado"
                }
            }
        }
    }

    private companion object {
        val CONTENT_HASH = Regex("[0-9a-f]{64}")
    }
}

/** Lectura acotada para historial local, sin cargar líneas completas. */
data class SaleSummary(
    val saleId: SaleId,
    val total: Money,
    val lineCount: Int,
    val postedAt: Instant,
) {
    init {
        require(lineCount > 0) { "Una venta publicada debe tener líneas" }
    }
}

/** Política cerrada: el bruto se redondea a la unidad menor con HALF_UP. */
fun calculateSaleLineGross(unitPrice: Money, quantity: Quantity): Money = Money.fromMajor(
    amount = unitPrice.toMajor().multiply(quantity.value),
    currency = unitPrice.currency,
    roundingMode = RoundingMode.HALF_UP,
)

fun calculateSaleLineTotal(
    unitPrice: Money,
    quantity: Quantity,
    discount: Money,
    tax: Money,
): Money {
    require(unitPrice.currency == discount.currency && unitPrice.currency == tax.currency) {
        "Precio, descuento e impuesto deben compartir moneda"
    }
    val gross = calculateSaleLineGross(unitPrice, quantity)
    require(discount.minorUnits <= gross.minorUnits) {
        "El descuento no puede superar el bruto de la línea"
    }
    return gross - discount + tax
}
