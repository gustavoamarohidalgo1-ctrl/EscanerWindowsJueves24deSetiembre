package com.facturastock.app.domain.usecase

import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.DebtPaymentReportItem
import com.facturastock.app.domain.model.ExactMonetaryAmount
import com.facturastock.app.domain.model.RealizedProfitIssue
import com.facturastock.app.domain.model.RealizedSaleProfit
import com.facturastock.app.domain.model.SalesReport
import com.facturastock.app.domain.model.SalesReportPeriod
import com.facturastock.app.domain.model.SalesReportRange
import com.facturastock.app.domain.model.SalesReportTotals
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.DebtRepository
import com.facturastock.app.domain.repository.SaleRepository
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Reporte reactivo del negocio activo. Las ventas ya se guardan al confirmarse; este caso de uso
 * solo proyecta el día, semana o mes calendario vigente sin ejecutar un lote destructivo cada
 * 24 horas.
 */
class ObserveSalesReportUseCase(
    private val configuration: AppConfigurationRepository,
    private val repository: SaleRepository,
    private val clock: AppClock,
    private val debtRepository: DebtRepository,
) {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    operator fun invoke(period: SalesReportPeriod): Flow<SalesReport> = configuration.observe()
        .map { current ->
            ReportContext(
                businessId = current.activeBusinessId,
                primaryCurrency = current.currency,
                zoneId = current.zoneId,
            )
        }
        .distinctUntilChanged()
        .flatMapLatest { context ->
            val generatedAt = clock.now()
            val range = currentSalesReportRange(period, generatedAt, context.zoneId)
            val businessId = context.businessId
                ?: return@flatMapLatest flowOf(
                    buildSalesReport(
                        range = range,
                        generatedAt = generatedAt,
                        primaryCurrency = context.primaryCurrency,
                        sales = emptyList(),
                    ),
                )
            combine(
                repository.observePostedProfits(businessId, range.startInclusive, range.endExclusive),
                debtRepository.observePaymentsInRange(businessId, range.startInclusive, range.endExclusive),
            ) { sales, payments ->
                buildSalesReport(
                    range = range,
                    generatedAt = generatedAt,
                    primaryCurrency = context.primaryCurrency,
                    sales = sales,
                    businessId = businessId,
                    debtPayments = payments,
                )
            }
        }

    private data class ReportContext(
        val businessId: BusinessId?,
        val primaryCurrency: CurrencyCode,
        val zoneId: ZoneId,
    )
}

/** Crea límites de calendario locales y luego los convierte a instantes UTC semiabiertos. */
fun currentSalesReportRange(
    period: SalesReportPeriod,
    now: Instant,
    zoneId: ZoneId,
): SalesReportRange {
    val today = now.atZone(zoneId).toLocalDate()
    val startDate = when (period) {
        SalesReportPeriod.DAY -> today
        SalesReportPeriod.WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        SalesReportPeriod.MONTH -> today.withDayOfMonth(1)
    }
    val endDate = when (period) {
        SalesReportPeriod.DAY -> startDate.plusDays(1)
        SalesReportPeriod.WEEK -> startDate.plusWeeks(1)
        SalesReportPeriod.MONTH -> startDate.plusMonths(1)
    }
    return SalesReportRange(
        period = period,
        startInclusive = startDate.atStartOfDay(zoneId).toInstant(),
        endExclusive = endDate.atStartOfDay(zoneId).toInstant(),
        zoneId = zoneId,
    )
}

private fun buildSalesReport(
    range: SalesReportRange,
    generatedAt: Instant,
    primaryCurrency: CurrencyCode,
    sales: List<RealizedSaleProfit>,
    businessId: BusinessId? = null,
    debtPayments: List<DebtPaymentReportItem> = emptyList(),
): SalesReport {
    val orderedSales = sales.sortedWith(
        compareByDescending<RealizedSaleProfit>(RealizedSaleProfit::postedAt)
            .thenByDescending { it.saleId.value },
    )
    require(orderedSales.all {
        it.postedAt >= range.startInclusive && it.postedAt < range.endExclusive
    }) { "El repositorio devolvió ventas fuera del intervalo solicitado" }

    val totalsByCurrency = linkedMapOf(
        primaryCurrency to SalesReportTotalsAccumulator(primaryCurrency),
    )
    orderedSales.forEach { sale ->
        totalsByCurrency.getOrPut(sale.totalCharged.currency) {
            SalesReportTotalsAccumulator(sale.totalCharged.currency)
        }.add(sale)
    }
    return SalesReport(
        range = range,
        generatedAt = generatedAt,
        primaryCurrency = primaryCurrency,
        sales = orderedSales,
        totalsByCurrency = totalsByCurrency.values.map(SalesReportTotalsAccumulator::build),
        businessId = businessId,
        debtPayments = debtPayments.sortedWith(
            compareByDescending<DebtPaymentReportItem> { it.payment.occurredAt }
                .thenByDescending { it.payment.paymentId.value },
        ),
    )
}

private class SalesReportTotalsAccumulator(
    private val currency: CurrencyCode,
) {
    private var totalCharged = BigDecimal.ZERO
    private var netRevenue = BigDecimal.ZERO
    private var historicalCost = BigDecimal.ZERO
    private var saleCount = 0
    private var lineCount = 0
    private val issues = linkedSetOf<RealizedProfitIssue>()

    fun add(sale: RealizedSaleProfit) {
        require(sale.totalCharged.currency == currency) {
            "La venta no pertenece a la moneda que se está agregando"
        }
        totalCharged = totalCharged.add(sale.totalCharged.toMajor())
        netRevenue = netRevenue.add(sale.netRevenue.toMajor())
        saleCount += 1
        lineCount += sale.lineCount
        issues += sale.issues
        if (sale.issues.isEmpty()) {
            historicalCost = historicalCost.add(requireNotNull(sale.historicalCost).amount)
        }
    }

    fun build(): SalesReportTotals {
        val availableCost = historicalCost.takeIf { issues.isEmpty() }
        val grossProfit = availableCost?.let(netRevenue::subtract)
        return SalesReportTotals(
            currency = currency,
            totalCharged = ExactMonetaryAmount(totalCharged, currency),
            netRevenue = ExactMonetaryAmount(netRevenue, currency),
            historicalCost = availableCost?.let { ExactMonetaryAmount(it, currency) },
            grossProfit = grossProfit?.let { ExactMonetaryAmount(it, currency) },
            saleCount = saleCount,
            lineCount = lineCount,
            issues = issues.toSet(),
        )
    }
}
