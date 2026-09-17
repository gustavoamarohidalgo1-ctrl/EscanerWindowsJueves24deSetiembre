package com.facturastock.app.domain.model

import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.SaleId
import com.facturastock.app.domain.model.id.SaleLineId
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

/** Periodos comerciales de calendario; nunca son ventanas deslizantes de 24 h/7 d/30 d. */
enum class SalesReportPeriod {
    DAY,
    WEEK,
    MONTH,
}

/** Intervalo semiabierto [startInclusive, endExclusive) calculado en la zona del negocio. */
data class SalesReportRange(
    val period: SalesReportPeriod,
    val startInclusive: Instant,
    val endExclusive: Instant,
    val zoneId: ZoneId,
) {
    init {
        require(startInclusive < endExclusive) {
            "El rango del reporte debe tener una duración positiva"
        }
    }
}

/**
 * Motivo por el que el costo o la ganancia realizada no pueden publicarse con seguridad.
 * Los ingresos siguen disponibles; nunca se reemplaza un costo desconocido por cero.
 */
enum class RealizedProfitIssue {
    MISSING_HISTORICAL_COST,
    COST_CURRENCY_MISMATCH,
    INVALID_PERSISTED_DATA,
    DECIMAL_LIMIT_EXCEEDED,
}

/** Producto vendido y su ganancia realizada usando exclusivamente valores históricos. */
data class RealizedSaleLineProfit(
    val saleLineId: SaleLineId,
    val productId: ProductId,
    val position: Int,
    val productName: String,
    val unitCode: String,
    val locationName: String,
    /** Nula únicamente si una persistencia inválida impide reconstruirla con seguridad. */
    val quantity: Quantity?,
    val totalCharged: Money,
    val netRevenue: Money,
    val historicalCost: ExactMonetaryAmount?,
    val grossProfit: ExactMonetaryAmount?,
    val issues: Set<RealizedProfitIssue> = emptySet(),
) {
    init {
        require(position >= 0) { "La posición de una línea realizada no puede ser negativa" }
        require(productName.isNotBlank() && unitCode.isNotBlank() && locationName.isNotBlank()) {
            "Una línea realizada debe conservar sus descripciones históricas"
        }
        require(quantity != null || RealizedProfitIssue.INVALID_PERSISTED_DATA in issues) {
            "Una cantidad ausente debe quedar marcada como dato histórico inválido"
        }
        require(totalCharged.currency == netRevenue.currency) {
            "Total cobrado e ingreso neto de línea deben compartir moneda"
        }
        require(
            totalCharged.minorUnits >= 0L &&
                netRevenue.minorUnits in 0L..totalCharged.minorUnits,
        ) { "Los ingresos de una línea realizada no pueden ser negativos" }
        require((historicalCost == null) == (grossProfit == null)) {
            "Costo histórico y ganancia de línea deben estar ambos disponibles o ambos ausentes"
        }
        require(issues.isEmpty() == (historicalCost != null)) {
            "Una ganancia de línea disponible no puede ocultar alertas de costeo"
        }
        historicalCost?.let { cost ->
            val profit = requireNotNull(grossProfit)
            require(cost.currency == totalCharged.currency && profit.currency == totalCharged.currency) {
                "Ingreso, costo y ganancia de línea deben compartir moneda"
            }
            require(cost.amount.signum() >= 0) { "El costo histórico de línea no puede ser negativo" }
            require(
                profit.amount.compareTo(netRevenue.toMajor().subtract(cost.amount)) == 0,
            ) { "La ganancia de línea no coincide con ingreso neto menos costo histórico" }
        }
    }
}

/**
 * Resultado realizado e inmutable de una venta confirmada.
 *
 * [totalCharged] conserva exactamente `sales.totalMinorUnits`, incluidos impuestos. El ingreso
 * para margen es [netRevenue], calculado como la suma de `(lineTotal - tax)` de sus líneas. El
 * costo es el que quedó congelado en el movimiento SALE, no el costo promedio vigente.
 */
data class RealizedSaleProfit(
    val saleId: SaleId,
    val totalCharged: Money,
    val netRevenue: Money,
    val historicalCost: ExactMonetaryAmount?,
    val grossProfit: ExactMonetaryAmount?,
    val lines: List<RealizedSaleLineProfit>,
    val postedAt: Instant,
    val issues: Set<RealizedProfitIssue> = emptySet(),
) {
    val lineCount: Int
        get() = lines.size

    init {
        require(lines.isNotEmpty()) { "Una venta confirmada debe tener líneas" }
        require(lines.map(RealizedSaleLineProfit::saleLineId).distinct().size == lines.size) {
            "Una venta realizada no puede repetir saleLineId"
        }
        require(lines.map(RealizedSaleLineProfit::position) == lines.indices.toList()) {
            "Las líneas realizadas deben conservar posiciones densas y ordenadas"
        }
        require(totalCharged.currency == netRevenue.currency) {
            "Total cobrado e ingreso neto deben compartir moneda"
        }
        require(lines.all { it.totalCharged.currency == totalCharged.currency }) {
            "Todas las líneas realizadas deben usar la moneda de la venta"
        }
        require(
            lines.fold(0L) { total, line -> Math.addExact(total, line.totalCharged.minorUnits) } ==
                totalCharged.minorUnits,
        ) { "El total cobrado no coincide con sus líneas realizadas" }
        require(
            lines.fold(0L) { total, line -> Math.addExact(total, line.netRevenue.minorUnits) } ==
                netRevenue.minorUnits,
        ) { "El ingreso neto no coincide con sus líneas realizadas" }
        require(totalCharged.minorUnits >= 0L && netRevenue.minorUnits >= 0L) {
            "Los ingresos de una venta no pueden ser negativos"
        }
        val lineIssues = lines.flatMap(RealizedSaleLineProfit::issues).toSet()
        require(issues.containsAll(lineIssues)) {
            "La venta debe conservar todas las alertas de sus líneas"
        }
        require((issues - lineIssues).all { it == RealizedProfitIssue.DECIMAL_LIMIT_EXCEEDED }) {
            "Una venta sólo puede añadir alertas propias por límites del agregado"
        }
        require((historicalCost == null) == (grossProfit == null)) {
            "Costo histórico y ganancia deben estar ambos disponibles o ambos ausentes"
        }
        require(issues.isEmpty() == (historicalCost != null)) {
            "Una ganancia disponible no puede ocultar alertas de costeo"
        }
        historicalCost?.let { cost ->
            val profit = requireNotNull(grossProfit)
            require(cost.currency == totalCharged.currency && profit.currency == totalCharged.currency) {
                "Ingreso, costo y ganancia deben compartir moneda"
            }
            require(cost.amount.signum() >= 0) { "El costo histórico no puede ser negativo" }
            val expectedCost = lines.fold(BigDecimal.ZERO) { total, line ->
                total.add(requireNotNull(line.historicalCost).amount)
            }
            require(cost.amount.compareTo(expectedCost) == 0) {
                "El costo histórico no coincide con sus líneas realizadas"
            }
            require(
                profit.amount.compareTo(netRevenue.toMajor().subtract(cost.amount)) == 0,
            ) { "La ganancia no coincide con ingreso neto menos costo histórico" }
        }
    }
}

/** Totales de un periodo para una sola moneda; monedas distintas jamás se mezclan. */
data class SalesReportTotals(
    val currency: CurrencyCode,
    val totalCharged: ExactMonetaryAmount,
    val netRevenue: ExactMonetaryAmount,
    val historicalCost: ExactMonetaryAmount?,
    val grossProfit: ExactMonetaryAmount?,
    val saleCount: Int,
    val lineCount: Int,
    val issues: Set<RealizedProfitIssue> = emptySet(),
) {
    init {
        require(saleCount >= 0 && lineCount >= 0)
        require(totalCharged.currency == currency && netRevenue.currency == currency)
        require(totalCharged.amount.signum() >= 0 && netRevenue.amount.signum() >= 0)
        require((historicalCost == null) == (grossProfit == null))
        require(issues.isEmpty() == (historicalCost != null))
        historicalCost?.let { cost ->
            val profit = requireNotNull(grossProfit)
            require(cost.currency == currency && profit.currency == currency)
            require(cost.amount.signum() >= 0)
            require(
                profit.amount.compareTo(netRevenue.amount.subtract(cost.amount)) == 0,
            )
        }
    }
}

/** Lectura lista para UI, con una fila por venta y totales separados por moneda. */
data class SalesReport(
    val range: SalesReportRange,
    val generatedAt: Instant,
    val primaryCurrency: CurrencyCode,
    val sales: List<RealizedSaleProfit>,
    val totalsByCurrency: List<SalesReportTotals>,
    /** Identidad del negocio que produjo estas filas, también cuando cambia la configuración. */
    val businessId: BusinessId? = null,
    val debtPayments: List<DebtPaymentReportItem> = emptyList(),
) {
    init {
        validateDebtPaymentsInReport(debtPayments, businessId, range)
        require(sales.map(RealizedSaleProfit::saleId).distinct().size == sales.size) {
            "El reporte no puede repetir una venta"
        }
        require(sales.zipWithNext().all { (first, second) ->
            first.postedAt > second.postedAt ||
                (first.postedAt == second.postedAt && first.saleId.value >= second.saleId.value)
        }) { "Las ventas deben estar en orden cronológico descendente estable" }
        require(sales.all { it.postedAt >= range.startInclusive && it.postedAt < range.endExclusive }) {
            "El reporte no puede contener ventas fuera de su intervalo"
        }
        require(totalsByCurrency.map(SalesReportTotals::currency).distinct().size == totalsByCurrency.size)
        require(totalsByCurrency.any { it.currency == primaryCurrency }) {
            "Los totales deben incluir la moneda principal, incluso sin ventas"
        }
    }

    val primaryTotals: SalesReportTotals
        get() = totalsByCurrency.first { it.currency == primaryCurrency }
}
