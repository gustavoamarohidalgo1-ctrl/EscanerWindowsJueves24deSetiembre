package com.facturastock.app.domain.usecase

import app.cash.turbine.test
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.ExactMonetaryAmount
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.RealizedProfitIssue
import com.facturastock.app.domain.model.RealizedSaleProfit
import com.facturastock.app.domain.model.SalesReportPeriod
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.SaleId
import com.facturastock.app.domain.repository.SaleRepository
import com.facturastock.app.testing.FakeAppConfigurationRepository
import com.facturastock.app.testing.FakeSaleRepository
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObserveSalesReportUseCaseTest {
    private val configuration = FakeAppConfigurationRepository()
    private val sales = FakeSaleRepository()
    private val useCase = ObserveSalesReportUseCase(
        configuration = configuration,
        repository = sales,
        clock = AppClock { NOW },
    )

    @Test
    fun `calendar ranges honor business timezone and ISO week boundaries`() {
        val newYork = ZoneId.of("America/New_York")
        val dstDay = currentSalesReportRange(
            period = SalesReportPeriod.DAY,
            now = Instant.parse("2026-03-08T17:00:00Z"),
            zoneId = newYork,
        )

        assertEquals(Instant.parse("2026-03-08T05:00:00Z"), dstDay.startInclusive)
        assertEquals(Instant.parse("2026-03-09T04:00:00Z"), dstDay.endExclusive)
        assertEquals(Duration.ofHours(23), Duration.between(dstDay.startInclusive, dstDay.endExclusive))

        val lima = ZoneId.of("America/Lima")
        val week = currentSalesReportRange(SalesReportPeriod.WEEK, NOW, lima)
        assertEquals(Instant.parse("2026-08-24T05:00:00Z"), week.startInclusive)
        assertEquals(Instant.parse("2026-08-31T05:00:00Z"), week.endExclusive)

        val month = currentSalesReportRange(SalesReportPeriod.MONTH, NOW, lima)
        assertEquals(Instant.parse("2026-08-01T05:00:00Z"), month.startInclusive)
        assertEquals(Instant.parse("2026-09-01T05:00:00Z"), month.endExclusive)
    }

    @Test
    fun `active business aggregates charged net cost and gross profit exactly`() = runTest {
        configuration.completeOnboarding(
            BUSINESS,
            AppConfiguration.DEFAULT_TAX_RATE,
            AppConfiguration.DEFAULT_COST_POLICY,
        )
        val first = profit(
            saleId = saleId(1),
            postedAt = Instant.parse("2026-08-28T13:00:00Z"),
            totalCharged = 11_800L,
            netRevenue = 10_000L,
            cost = "60.125",
            lineCount = 2,
        )
        val second = profit(
            saleId = saleId(2),
            postedAt = Instant.parse("2026-08-28T14:00:00Z"),
            totalCharged = 5_900L,
            netRevenue = 5_000L,
            cost = "12.375",
        )
        sales.replacePostedProfits(BUSINESS, listOf(first, second))

        useCase(SalesReportPeriod.DAY).test {
            val report = awaitItem()

            assertEquals(listOf(second.saleId, first.saleId), report.sales.map { it.saleId })
            assertEquals(BigDecimal("177.00"), report.primaryTotals.totalCharged.amount)
            assertEquals(BigDecimal("150.00"), report.primaryTotals.netRevenue.amount)
            assertEquals(BigDecimal("72.500"), report.primaryTotals.historicalCost?.amount)
            assertEquals(BigDecimal("77.500"), report.primaryTotals.grossProfit?.amount)
            assertEquals(2, report.primaryTotals.saleCount)
            assertEquals(3, report.primaryTotals.lineCount)
            assertTrue(report.primaryTotals.issues.isEmpty())
            assertEquals(1, sales.observedProfitRequests.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `single pass totals keep currencies separate and primary currency first`() = runTest {
        configuration.completeOnboarding(
            BUSINESS,
            AppConfiguration.DEFAULT_TAX_RATE,
            AppConfiguration.DEFAULT_COST_POLICY,
        )
        val penSale = profit(
            saleId = saleId(10),
            postedAt = Instant.parse("2026-08-28T13:00:00Z"),
            totalCharged = 11_800L,
            netRevenue = 10_000L,
            cost = "60.125",
        )
        val usdSale = profit(
            saleId = saleId(11),
            postedAt = Instant.parse("2026-08-28T14:00:00Z"),
            totalCharged = 2_500L,
            netRevenue = 2_000L,
            cost = "7.50",
            currency = USD,
        )
        sales.replacePostedProfits(BUSINESS, listOf(usdSale, penSale))

        useCase(SalesReportPeriod.DAY).test {
            val report = awaitItem()

            assertEquals(listOf(PEN, USD), report.totalsByCurrency.map { it.currency })
            assertEquals(BigDecimal("118.00"), report.primaryTotals.totalCharged.amount)
            assertEquals(BigDecimal("12.50"), report.totalsByCurrency[1].grossProfit?.amount)
            assertEquals(1, report.primaryTotals.saleCount)
            assertEquals(1, report.totalsByCurrency[1].saleCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `report layer normalizes an unsorted repository emission with stable tie break`() =
        runTest {
            configuration.completeOnboarding(
                BUSINESS,
                AppConfiguration.DEFAULT_TAX_RATE,
                AppConfiguration.DEFAULT_COST_POLICY,
            )
            val older = profit(
                saleId = saleId(3),
                postedAt = Instant.parse("2026-08-28T13:00:00Z"),
                totalCharged = 1_180L,
                netRevenue = 1_000L,
                cost = "5.00",
            )
            val tiedLowerId = profit(
                saleId = saleId(4),
                postedAt = Instant.parse("2026-08-28T14:00:00Z"),
                totalCharged = 1_180L,
                netRevenue = 1_000L,
                cost = "5.00",
            )
            val tiedHigherId = profit(
                saleId = saleId(5),
                postedAt = Instant.parse("2026-08-28T14:00:00Z"),
                totalCharged = 1_180L,
                netRevenue = 1_000L,
                cost = "5.00",
            )
            val unsortedRepository = object : SaleRepository by sales {
                override fun observePostedProfits(
                    businessId: BusinessId,
                    startInclusive: Instant,
                    endExclusive: Instant,
                ): Flow<List<RealizedSaleProfit>> = flowOf(
                    listOf(tiedLowerId, older, tiedHigherId),
                )
            }
            val reportUseCase = ObserveSalesReportUseCase(
                configuration = configuration,
                repository = unsortedRepository,
                clock = AppClock { NOW },
            )

            reportUseCase(SalesReportPeriod.DAY).test {
                val report = awaitItem()

                assertEquals(
                    listOf(tiedHigherId.saleId, tiedLowerId.saleId, older.saleId),
                    report.sales.map(RealizedSaleProfit::saleId),
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `one unknown historical cost makes period profit unavailable instead of partial`() =
        runTest {
            configuration.completeOnboarding(
                BUSINESS,
                AppConfiguration.DEFAULT_TAX_RATE,
                AppConfiguration.DEFAULT_COST_POLICY,
            )
            val complete = profit(
                saleId = saleId(3),
                postedAt = Instant.parse("2026-08-28T13:00:00Z"),
                totalCharged = 11_800L,
                netRevenue = 10_000L,
                cost = "60.00",
            )
            val unknown = RealizedSaleProfit(
                saleId = saleId(4),
                totalCharged = Money.ofMinor(5_900L, PEN),
                netRevenue = Money.ofMinor(5_000L, PEN),
                historicalCost = null,
                grossProfit = null,
                lineCount = 1,
                postedAt = Instant.parse("2026-08-28T14:00:00Z"),
                issues = setOf(RealizedProfitIssue.MISSING_HISTORICAL_COST),
            )
            sales.replacePostedProfits(BUSINESS, listOf(complete, unknown))

            useCase(SalesReportPeriod.DAY).test {
                val totals = awaitItem().primaryTotals

                assertEquals(BigDecimal("177.00"), totals.totalCharged.amount)
                assertEquals(BigDecimal("150.00"), totals.netRevenue.amount)
                assertNull(totals.historicalCost)
                assertNull(totals.grossProfit)
                assertEquals(
                    setOf(RealizedProfitIssue.MISSING_HISTORICAL_COST),
                    totals.issues,
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `no active business emits a zero report without observing tenant data`() = runTest {
        useCase(SalesReportPeriod.DAY).test {
            val report = awaitItem()

            assertTrue(report.sales.isEmpty())
            assertEquals(PEN, report.primaryCurrency)
            assertEquals(BigDecimal.ZERO, report.primaryTotals.totalCharged.amount)
            assertEquals(BigDecimal.ZERO, report.primaryTotals.grossProfit?.amount)
            assertEquals(0, report.primaryTotals.saleCount)
            assertTrue(sales.observedProfitRequests.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun profit(
        saleId: SaleId,
        postedAt: Instant,
        totalCharged: Long,
        netRevenue: Long,
        cost: String,
        lineCount: Int = 1,
        currency: CurrencyCode = PEN,
    ): RealizedSaleProfit {
        val costAmount = BigDecimal(cost)
        val net = Money.ofMinor(netRevenue, currency)
        return RealizedSaleProfit(
            saleId = saleId,
            totalCharged = Money.ofMinor(totalCharged, currency),
            netRevenue = net,
            historicalCost = ExactMonetaryAmount(costAmount, currency),
            grossProfit = ExactMonetaryAmount(net.toMajor().subtract(costAmount), currency),
            lineCount = lineCount,
            postedAt = postedAt,
        )
    }

    private fun saleId(seed: Long): SaleId = SaleId.from(UUID(0L, seed))

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-28T15:00:00Z")
        val BUSINESS: BusinessId = BusinessId.from(UUID(1L, 1L))
        val PEN: CurrencyCode = CurrencyCode.of("PEN")
        val USD: CurrencyCode = CurrencyCode.of("USD")
    }
}
