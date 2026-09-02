package com.facturastock.app.feature.reports

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.facturastock.app.R
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.ExactMonetaryAmount
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.RealizedProfitIssue
import com.facturastock.app.domain.model.RealizedSaleProfit
import com.facturastock.app.domain.model.SalesReport
import com.facturastock.app.domain.model.SalesReportPeriod
import com.facturastock.app.domain.model.SalesReportRange
import com.facturastock.app.domain.model.SalesReportTotals
import com.facturastock.app.domain.model.id.SaleId
import com.facturastock.app.ui.theme.FacturaStockTheme
import java.math.BigDecimal
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReportsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun periodSelectorIsAccessibleAndDispatchesCalendarPeriod() {
        var selected: SalesReportPeriod? = null
        composeRule.setContent {
            FacturaStockTheme {
                ReportsScreen(
                    state = ReportsContract.State(isLoading = false, report = report()),
                    onAction = { action ->
                        if (action is ReportsContract.Action.PeriodSelected) {
                            selected = action.period
                        }
                    },
                )
            }
        }

        val radioRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        composeRule.onNodeWithTag(ReportsTestTags.PERIOD_DAY)
            .assertIsDisplayed()
            .assertIsSelected()
            .assertHasClickAction()
            .assert(radioRole)
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag(ReportsTestTags.PERIOD_WEEK)
            .assertIsDisplayed()
            .assertHasClickAction()
            .assert(radioRole)
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        assertEquals(SalesReportPeriod.WEEK, selected)
    }

    @Test
    fun showsTheProfitFormulaAndEverySaleWithoutExtraCopy() {
        val report = report()
        composeRule.setContent {
            FacturaStockTheme {
                ReportsScreen(
                    state = ReportsContract.State(isLoading = false, report = report),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithTag(ReportsTestTags.HERO).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.reports_profit_formula))
            .assertIsDisplayed()
        composeRule.onNodeWithTag(ReportsTestTags.SCREEN).performScrollToNode(
            hasTestTag(ReportsTestTags.sale(SALE_ID.value)),
        )
        composeRule.onNodeWithTag(ReportsTestTags.sale(SALE_ID.value)).assertIsDisplayed()
    }

    @Test
    fun missingHistoricalCostNeverAppearsAsZeroProfit() {
        val issue = RealizedProfitIssue.MISSING_HISTORICAL_COST
        val unavailableSale = realizedSale(costAvailable = false)
        val unavailableTotals = totals(costAvailable = false)
        val report = report(
            sale = unavailableSale,
            totals = unavailableTotals,
        )
        composeRule.setContent {
            FacturaStockTheme {
                ReportsScreen(
                    state = ReportsContract.State(isLoading = false, report = report),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithTag(ReportsTestTags.SCREEN).performScrollToNode(
            hasTestTag(ReportsTestTags.PROFIT_UNAVAILABLE),
        )
        composeRule.onNodeWithTag(ReportsTestTags.PROFIT_UNAVAILABLE).assertIsDisplayed()
        composeRule.onNodeWithTag(ReportsTestTags.PROFIT_UNAVAILABLE)
            .assertTextContains(
                context.getString(R.string.reports_issue_missing_cost),
                substring = true,
            )
        assertEquals(setOf(issue), unavailableTotals.issues)
    }

    private fun report(
        sale: RealizedSaleProfit = realizedSale(),
        totals: SalesReportTotals = totals(),
    ): SalesReport = SalesReport(
        range = RANGE,
        generatedAt = NOW,
        primaryCurrency = PEN,
        sales = listOf(sale),
        totalsByCurrency = listOf(totals),
    )

    private fun realizedSale(costAvailable: Boolean = true): RealizedSaleProfit =
        RealizedSaleProfit(
            saleId = SALE_ID,
            totalCharged = Money.ofMinor(1_180L, PEN),
            netRevenue = Money.ofMinor(1_000L, PEN),
            historicalCost = if (costAvailable) exact("5.00") else null,
            grossProfit = if (costAvailable) exact("5.00") else null,
            lineCount = 2,
            postedAt = NOW,
            issues = if (costAvailable) {
                emptySet()
            } else {
                setOf(RealizedProfitIssue.MISSING_HISTORICAL_COST)
            },
        )

    private fun totals(costAvailable: Boolean = true): SalesReportTotals = SalesReportTotals(
        currency = PEN,
        totalCharged = exact("11.80"),
        netRevenue = exact("10.00"),
        historicalCost = if (costAvailable) exact("5.00") else null,
        grossProfit = if (costAvailable) exact("5.00") else null,
        saleCount = 1,
        lineCount = 2,
        issues = if (costAvailable) {
            emptySet()
        } else {
            setOf(RealizedProfitIssue.MISSING_HISTORICAL_COST)
        },
    )

    private fun exact(value: String): ExactMonetaryAmount =
        ExactMonetaryAmount(BigDecimal(value), PEN)

    private companion object {
        val PEN: CurrencyCode = CurrencyCode.of("PEN")
        val SALE_ID: SaleId = checkNotNull(
            SaleId.parse("11111111-1111-4111-8111-111111111111"),
        )
        val NOW: Instant = Instant.parse("2026-08-28T15:00:00Z")
        val RANGE = SalesReportRange(
            period = SalesReportPeriod.DAY,
            startInclusive = Instant.parse("2026-08-28T05:00:00Z"),
            endExclusive = Instant.parse("2026-08-29T05:00:00Z"),
            zoneId = AppConfiguration.DEFAULT_ZONE_ID,
        )
    }
}
