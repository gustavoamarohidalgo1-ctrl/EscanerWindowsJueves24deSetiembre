package com.facturastock.app.feature.reports

import com.facturastock.app.resources.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.DebtPayment
import com.facturastock.app.domain.model.DebtPaymentMethod
import com.facturastock.app.domain.model.DebtPaymentReportItem
import com.facturastock.app.domain.model.ExactMonetaryAmount
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.RealizedProfitIssue
import com.facturastock.app.domain.model.RealizedSaleLineProfit
import com.facturastock.app.domain.model.RealizedSaleProfit
import com.facturastock.app.domain.model.SalesReport
import com.facturastock.app.domain.model.SalesReportPeriod
import com.facturastock.app.domain.model.SalesReportRange
import com.facturastock.app.domain.model.SalesReportTotals
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.DebtId
import com.facturastock.app.domain.model.id.DebtPaymentId
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.SaleId
import com.facturastock.app.domain.model.id.SaleLineId
import com.facturastock.app.domain.repository.SaleVoidLine
import com.facturastock.app.domain.repository.SaleVoidPreview
import com.facturastock.app.ui.format.formatForDisplay
import com.facturastock.app.ui.theme.FacturaStockTheme
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReportsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()


    @Test
    fun debtCollectionIsVisibleAtDoubleFontSizeWithoutAddingItToSalesTotals() {
        val payment = DebtPaymentReportItem(
            BUSINESS_ID, SALE_ID, "Cliente cobro completo",
            DebtPayment(DebtPaymentId.from(UUID(42L, 1L)), DebtId.from(UUID(43L, 1L)),
                Money.ofMinor(600L, PEN), DebtPaymentMethod.OTHER, null, null, 2L,
                Money.ofMinor(0L, PEN), NOW, NOW),
        )
        val report = report().copy(debtPayments = listOf(payment))
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                FacturaStockTheme { ReportsScreen(ReportsContract.State(isLoading = false, report = report), {}) }
            }
        }
        composeRule.onNodeWithTag(ReportsTestTags.DEBT_PAYMENTS).assertIsDisplayed()
        composeRule.onNodeWithText(str(Res.string.reports_debt_payments_total, payment.payment.amount.formatForDisplay()))
            .assertIsDisplayed()
        composeRule.onNodeWithTag(ReportsTestTags.SCREEN)
            .performScrollToNode(hasTestTag(ReportsTestTags.debtPayment(payment.payment.paymentId.value)))
        composeRule.onNodeWithTag(ReportsTestTags.debtPayment(payment.payment.paymentId.value)).assertIsDisplayed()
        composeRule.onNodeWithText(str(Res.string.reports_debt_payment_amount, payment.payment.amount.formatForDisplay()))
            .assertIsDisplayed()
        assertEquals("11.80", report.primaryTotals.totalCharged.amount.toPlainString())
    }

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

        composeRule.onNodeWithTag(ReportsTestTags.SCREEN).performScrollToNode(hasTestTag(ReportsTestTags.PERIOD_SELECTOR))
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
    fun refreshingKeepsReportGeometryStableAndProgressAboveThePeriodSelector() {
        var state by mutableStateOf(ReportsContract.State(isLoading = false, report = report()))
        composeRule.setContent {
            FacturaStockTheme { ReportsScreen(state = state, onAction = {}) }
        }
        composeRule.onNodeWithTag(ReportsTestTags.SCREEN).performScrollToNode(hasTestTag(ReportsTestTags.PERIOD_SELECTOR))
        val tags = listOf(ReportsTestTags.RANGE, ReportsTestTags.HERO, ReportsTestTags.METRICS)
        val initialBounds = tags.associateWith { composeRule.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        val selectorBounds = composeRule.onNodeWithTag(ReportsTestTags.PERIOD_SELECTOR).fetchSemanticsNode().boundsInRoot
        composeRule.onNodeWithTag(ReportsTestTags.REFRESHING).assertDoesNotExist()

        composeRule.runOnIdle { state = state.copy(isRefreshing = true) }
        val progress = composeRule.onNodeWithTag(ReportsTestTags.REFRESHING).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(progress.bottom <= selectorBounds.top)
        assertEquals(selectorBounds.left, progress.left, 0f)
        assertEquals(selectorBounds.right, progress.right, 0f)
        tags.forEach { tag ->
            assertEquals(initialBounds.getValue(tag), composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot)
        }

        composeRule.runOnIdle { state = state.copy(isRefreshing = false) }
        composeRule.onNodeWithTag(ReportsTestTags.REFRESHING).assertDoesNotExist()
        tags.forEach { tag ->
            assertEquals(initialBounds.getValue(tag), composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot)
        }
    }

    @Test
    fun refreshingPreservesTheVisibleScrollPositionAndExpandedSaleDetails() {
        var state by mutableStateOf(ReportsContract.State(isLoading = false, report = report()))
        composeRule.setContent {
            FacturaStockTheme { ReportsScreen(state = state, onAction = {}) }
        }
        val toggleTag = ReportsTestTags.saleToggle(SALE_ID.value)
        val lineTag = ReportsTestTags.saleLineProfit(SALE_ID.value, FIRST_LINE_ID.value)
        composeRule.onNodeWithTag(ReportsTestTags.SCREEN).performScrollToNode(hasTestTag(toggleTag))
        composeRule.onNodeWithTag(toggleTag).performClick()
        composeRule.onNodeWithTag(ReportsTestTags.SCREEN).performScrollToNode(hasTestTag(lineTag))
        val initialBounds = composeRule.onNodeWithTag(lineTag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val expanded = SemanticsMatcher.expectValue(
            SemanticsProperties.StateDescription,
            str(Res.string.reports_sale_details_expanded),
        )

        composeRule.runOnIdle { state = state.copy(isRefreshing = true) }
        composeRule.onNodeWithTag(ReportsTestTags.REFRESHING).assertIsDisplayed()
        composeRule.onNodeWithTag(toggleTag).assert(expanded)
        assertEquals(initialBounds, composeRule.onNodeWithTag(lineTag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot)

        composeRule.runOnIdle {
            state = state.copy(
                isRefreshing = false,
                report = requireNotNull(state.report).copy(generatedAt = NOW.plusSeconds(30)),
            )
        }
        composeRule.onNodeWithTag(ReportsTestTags.REFRESHING).assertDoesNotExist()
        composeRule.onNodeWithTag(toggleTag).assert(expanded)
        assertEquals(initialBounds, composeRule.onNodeWithTag(lineTag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
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

        composeRule.onNodeWithTag(ReportsTestTags.SCREEN).performScrollToNode(hasTestTag(ReportsTestTags.HERO))
        composeRule.onNodeWithTag(ReportsTestTags.HERO).assertIsDisplayed()
        composeRule.onNodeWithText(str(Res.string.reports_profit_formula))
            .assertIsDisplayed()
        composeRule.onNodeWithTag(ReportsTestTags.SCREEN).performScrollToNode(
            hasTestTag(ReportsTestTags.sale(SALE_ID.value)),
        )
        composeRule.onNodeWithTag(ReportsTestTags.sale(SALE_ID.value)).assertIsDisplayed()
    }

    @Test
    fun saleSummaryExpandsAccessibleProductProfitDetailsAndCollapsesAgain() {
        composeRule.setContent {
            FacturaStockTheme {
                ReportsScreen(
                    state = ReportsContract.State(isLoading = false, report = report()),
                    onAction = {},
                )
            }
        }
        val toggleTag = ReportsTestTags.saleToggle(SALE_ID.value)
        composeRule.onNodeWithTag(ReportsTestTags.SCREEN).performScrollToNode(
            hasTestTag(ReportsTestTags.sale(SALE_ID.value)),
        )
        composeRule.onNodeWithText("Café molido").assertIsDisplayed()
        composeRule.onNodeWithText("Azúcar rubia").assertIsDisplayed()
        composeRule.onNodeWithText("2 NIU").assertIsDisplayed()
        composeRule.onNodeWithText("1.5 KGM").assertIsDisplayed()
        composeRule.onNodeWithText(
            str(
                Res.string.reports_sale_title,
                NOW.formatForDisplay(RANGE.zoneId),
            ),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            runBlocking { getPluralString(Res.plurals.reports_line_count, 2, 2) },
        ).assertIsDisplayed()
        composeRule.onNodeWithText(SALE_ID.value.takeLast(8), substring = true)
            .assertDoesNotExist()
        composeRule.onNodeWithTag(ReportsTestTags.saleTotal(SALE_ID.value))
            .assertIsDisplayed()
            .assertTextContains(Money.ofMinor(1_180L, PEN).formatForDisplay())
        composeRule.onNodeWithTag(ReportsTestTags.saleDetails(SALE_ID.value)).assertDoesNotExist()
        composeRule.onNodeWithTag(ReportsTestTags.saleLine(SALE_ID.value, FIRST_LINE_ID.value))
            .assertDoesNotExist()
        composeRule.onNodeWithTag(ReportsTestTags.saleLineProfit(SALE_ID.value, FIRST_LINE_ID.value))
            .assertDoesNotExist()

        composeRule.onNodeWithTag(ReportsTestTags.SCREEN).performScrollToNode(hasTestTag(toggleTag))
        val buttonRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
        composeRule.onNodeWithTag(toggleTag)
            .assertIsDisplayed()
            .assertHasClickAction()
            .assert(buttonRole)
            .assertHeightIsAtLeast(48.dp)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    str(Res.string.reports_sale_details_collapsed),
                ),
            )
            .performClick()

        composeRule.onNodeWithTag(ReportsTestTags.SCREEN).performScrollToNode(
            hasTestTag(ReportsTestTags.saleDetails(SALE_ID.value)),
        )
        composeRule.onNodeWithTag(ReportsTestTags.saleDetails(SALE_ID.value)).assertIsDisplayed()
        composeRule.onNodeWithTag(ReportsTestTags.saleLine(SALE_ID.value, FIRST_LINE_ID.value))
            .assertIsDisplayed()
        composeRule.onNodeWithTag(ReportsTestTags.saleLineProfit(SALE_ID.value, FIRST_LINE_ID.value))
            .assertIsDisplayed()
            .assertTextContains(str(Res.string.reports_sale_line_profit))
        composeRule.onNodeWithTag(ReportsTestTags.SCREEN).performScrollToNode(hasTestTag(toggleTag))
        composeRule.onNodeWithTag(toggleTag)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    str(Res.string.reports_sale_details_expanded),
                ),
            )
            .performClick()

        composeRule.onNodeWithTag(ReportsTestTags.saleDetails(SALE_ID.value)).assertDoesNotExist()
        composeRule.onNodeWithTag(ReportsTestTags.saleLineProfit(SALE_ID.value, FIRST_LINE_ID.value))
            .assertDoesNotExist()
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
            hasTestTag(ReportsTestTags.saleToggle(SALE_ID.value)),
        )
        composeRule.onNodeWithTag(ReportsTestTags.saleToggle(SALE_ID.value)).performClick()
        composeRule.onNodeWithTag(ReportsTestTags.SCREEN).performScrollToNode(
            hasTestTag(ReportsTestTags.saleLineProfit(SALE_ID.value, FIRST_LINE_ID.value)),
        )
        composeRule.onNodeWithTag(
            ReportsTestTags.saleLineProfit(SALE_ID.value, FIRST_LINE_ID.value),
        ).assertTextContains(str(Res.string.reports_amount_unavailable))
        composeRule.onNodeWithTag(ReportsTestTags.SCREEN).performScrollToNode(
            hasTestTag(ReportsTestTags.saleWarning(SALE_ID.value)),
        )
        composeRule.onNodeWithTag(ReportsTestTags.saleWarning(SALE_ID.value))
            .assertIsDisplayed()
            .assertTextContains(
                str(Res.string.reports_issue_missing_cost),
                substring = true,
            )
        assertEquals(setOf(issue), unavailableTotals.issues)
    }

    @Test
    fun eachSaleHasAnAccessibleVoidActionSeparateFromItsDetails() {
        val actions = mutableListOf<ReportsContract.Action>()
        composeRule.setContent {
            FacturaStockTheme {
                ReportsScreen(ReportsContract.State(isLoading = false, report = report()), actions::add)
            }
        }
        composeRule.onNodeWithTag(ReportsTestTags.SCREEN).performScrollToNode(
            hasTestTag(ReportsTestTags.saleVoid(SALE_ID.value)),
        )
        composeRule.onNodeWithTag(ReportsTestTags.saleVoid(SALE_ID.value))
            .assertIsDisplayed().assertIsEnabled().assertHasClickAction()
            .assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf(ReportsContract.Action.VoidRequested(SALE_ID)), actions)
        composeRule.onNodeWithTag(ReportsTestTags.SCREEN).performScrollToNode(
            hasTestTag(ReportsTestTags.saleToggle(SALE_ID.value)),
        )
        composeRule.onNodeWithTag(ReportsTestTags.saleToggle(SALE_ID.value)).performClick()
        composeRule.onNodeWithTag(ReportsTestTags.saleDetails(SALE_ID.value)).assertExists()
        assertEquals(1, actions.size)
    }

    @Test
    fun voidConfirmationShowsDateStockWarehousesDebtAndManualRefundBeforeExplicitActions() {
        val actions = mutableListOf<ReportsContract.Action>()
        val preview = voidPreview()
        composeRule.setContent {
            FacturaStockTheme {
                ReportsScreen(
                    ReportsContract.State(
                        isLoading = false, report = report(), voidSaleId = SALE_ID, voidPreview = preview,
                    ),
                    actions::add,
                )
            }
        }
        composeRule.onNodeWithTag(ReportsTestTags.VOID_DIALOG).assertIsDisplayed()
        composeRule.onNodeWithText(str(
            Res.string.reports_void_date, NOW.formatForDisplay(RANGE.zoneId),
        )).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(str(
            Res.string.reports_void_total, preview.total.formatForDisplay(),
        )).performScrollTo().assertIsDisplayed()
        preview.lines.forEach { line ->
            composeRule.onNodeWithText(str(
                Res.string.reports_void_line, line.productName,
                line.quantity.value.stripTrailingZeros().toPlainString(), line.unitCode, line.locationName,
            )).performScrollTo().assertIsDisplayed()
        }
        composeRule.onNodeWithText(str(
            Res.string.reports_void_debt, requireNotNull(preview.debtBalanceToCancel).formatForDisplay(),
        )).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(str(
            Res.string.reports_void_refund, preview.refundAmount.formatForDisplay(),
        )).performScrollTo().assertIsDisplayed()
        assertTrue(actions.isEmpty())
        dialogButton(Res.string.reports_void_cancel).assertIsEnabled().performClick()
        assertEquals(listOf(ReportsContract.Action.VoidDismissed), actions)
        dialogButton(Res.string.reports_void_action).assertIsEnabled().performClick()
        assertEquals(ReportsContract.Action.VoidConfirmed, actions.last())
    }

    @Test
    fun previewLoadingDisablesConfirmationAndMutationDisablesDismissalAndPeriodChanges() {
        var state by mutableStateOf(ReportsContract.State(
            isLoading = false, report = report(), voidSaleId = SALE_ID, isLoadingVoidPreview = true,
        ))
        val actions = mutableListOf<ReportsContract.Action>()
        composeRule.setContent {
            FacturaStockTheme { ReportsScreen(state, actions::add) }
        }
        dialogButton(Res.string.reports_void_action).assertIsNotEnabled()
        dialogButton(Res.string.reports_void_cancel).assertIsEnabled()
        composeRule.runOnIdle {
            state = state.copy(isLoadingVoidPreview = false, voidPreview = voidPreview(), isVoiding = true)
        }
        dialogButton(Res.string.reports_void_action).assertIsNotEnabled()
        dialogButton(Res.string.reports_void_cancel).assertIsNotEnabled()
        composeRule.onNodeWithTag(ReportsTestTags.PERIOD_WEEK).assertIsNotEnabled()
        assertTrue(actions.isEmpty())
    }

    @Test
    fun sharedBusinessErrorOffersReviewAndStalePreviewExplainsWhyConfirmationIsNeededAgain() {
        var state by mutableStateOf(ReportsContract.State(
            isLoading = false, report = report(), voidSaleId = SALE_ID,
            voidFailure = ReportsContract.VoidFailure.SHARED_BUSINESS_UNSUPPORTED,
        ))
        val actions = mutableListOf<ReportsContract.Action>()
        composeRule.setContent {
            FacturaStockTheme { ReportsScreen(state, actions::add) }
        }
        composeRule.onNodeWithTag(ReportsTestTags.VOID_ERROR)
            .assertTextContains(str(Res.string.reports_void_error_shared))
        dialogButton(Res.string.reports_void_retry).performClick()
        assertEquals(listOf(ReportsContract.Action.VoidPreviewRetry), actions)
        composeRule.runOnIdle {
            state = state.copy(voidPreview = voidPreview(), voidFailure = ReportsContract.VoidFailure.STALE)
        }
        composeRule.onNodeWithTag(ReportsTestTags.VOID_ERROR)
            .assertTextContains(str(Res.string.reports_void_error_stale))
        dialogButton(Res.string.reports_void_action).assertIsEnabled()
        assertEquals(1, actions.size)
    }

    @Test
    fun confirmationAtDoubleFontSizeKeepsActionsVisibleAndImpactScrollable() {
        val preview = voidPreview()
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                FacturaStockTheme {
                    ReportsScreen(
                        ReportsContract.State(
                            isLoading = false, report = report(), voidSaleId = SALE_ID, voidPreview = preview,
                        ),
                        {},
                    )
                }
            }
        }
        dialogButton(Res.string.reports_void_action).assertIsDisplayed().assertIsEnabled()
        dialogButton(Res.string.reports_void_cancel).assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithText(str(
            Res.string.reports_void_refund, preview.refundAmount.formatForDisplay(),
        )).performScrollTo().assertIsDisplayed()
        dialogButton(Res.string.reports_void_action).assertIsDisplayed()
        dialogButton(Res.string.reports_void_cancel).assertIsDisplayed()
    }

    @Test
    fun successfulVoidNoticeRemainsVisibleWithoutScrollingAndCanBeDismissed() {
        val actions = mutableListOf<ReportsContract.Action>()
        composeRule.setContent {
            FacturaStockTheme {
                ReportsScreen(
                    ReportsContract.State(isLoading = false, report = report(), voidSucceeded = true),
                    actions::add,
                )
            }
        }
        composeRule.onNodeWithTag(ReportsTestTags.VOID_SUCCESS).assertIsDisplayed()
        composeRule.onNodeWithText(str(Res.string.reports_void_notice_dismiss)).performClick()
        assertEquals(listOf(ReportsContract.Action.VoidNoticeDismissed), actions)
    }

    @Test
    fun debtorsTabSitsBesidePeriodsAndPdfCardOnlyAppearsWithNotices() {
        val actions = mutableListOf<ReportsContract.Action>()
        composeRule.setContent {
            FacturaStockTheme {
                ReportsScreen(
                    ReportsContract.State(isLoading = false, report = report(), selectedPeriod = SalesReportPeriod.MONTH),
                    actions::add,
                )
            }
        }
        composeRule.onNodeWithTag(ReportsTestTags.PDF_ACTIONS).assertDoesNotExist()
        composeRule.onNode(
            hasTestTag(ReportsTestTags.OPEN_DEBTORS) and hasAnyAncestor(hasTestTag(ReportsTestTags.PERIOD_SELECTOR)),
        ).assertIsDisplayed()
        // Sin contenido integrado, la pestaña conserva la navegación a la pantalla de deudores.
        composeRule.onNodeWithTag(ReportsTestTags.OPEN_DEBTORS)
            .assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf<ReportsContract.Action>(ReportsContract.Action.OpenDebtors), actions)
    }

    @Test
    fun debtorsTabShowsEmbeddedDebtorsAndPeriodTabReturnsToReport() {
        val actions = mutableListOf<ReportsContract.Action>()
        var showingDebtors by mutableStateOf(false)
        composeRule.setContent {
            FacturaStockTheme {
                ReportsScreen(
                    state = ReportsContract.State(isLoading = false, report = report()),
                    onAction = actions::add,
                    showingDebtors = showingDebtors,
                    onShowingDebtorsChange = { showingDebtors = it },
                    debtorsContent = { modifier -> Text("lista de deudores", modifier) },
                )
            }
        }
        composeRule.onNodeWithTag(ReportsTestTags.OPEN_DEBTORS).performClick()
        composeRule.onNodeWithTag(ReportsTestTags.DEBTORS_CONTENT).assertIsDisplayed()
        composeRule.onNodeWithText("lista de deudores").assertIsDisplayed()
        composeRule.onNodeWithTag(ReportsTestTags.HERO).assertDoesNotExist()
        assertTrue(actions.isEmpty())

        composeRule.onNodeWithTag(ReportsTestTags.PERIOD_WEEK).performClick()
        composeRule.onNodeWithTag(ReportsTestTags.DEBTORS_CONTENT).assertDoesNotExist()
        assertEquals(listOf<ReportsContract.Action>(ReportsContract.Action.PeriodSelected(SalesReportPeriod.WEEK)), actions)
    }

    @Test
    fun debtorsTabIsDisabledWithoutBusinessAndProgressShowsDuringEveryExportStage() {
        var state by mutableStateOf(ReportsContract.State(isLoading = false, report = report().copy(businessId = null)))
        composeRule.setContent {
            FacturaStockTheme { ReportsScreen(state, {}) }
        }
        composeRule.onNodeWithTag(ReportsTestTags.OPEN_DEBTORS).assertIsNotEnabled()
        for (stage in listOf(ReportsContract.PdfStage.PREPARING, ReportsContract.PdfStage.CHOOSING_DESTINATION, ReportsContract.PdfStage.WRITING)) {
            composeRule.runOnIdle { state = state.copy(report = report(), pdfStage = stage) }
            assertTrue(!state.canExportPdf)
            composeRule.onNodeWithTag(ReportsTestTags.PDF_PROGRESS).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun savedPdfOffersExplicitOpenAndMissingViewerKeepsSuccessVisible() {
        val actions = mutableListOf<ReportsContract.Action>()
        var state by mutableStateOf(ReportsContract.State(isLoading = false, report = report(), pdfSaved = true))
        composeRule.setContent {
            FacturaStockTheme { ReportsScreen(state, actions::add) }
        }
        assertTrue(actions.isEmpty())
        composeRule.onNodeWithTag(ReportsTestTags.PDF_SUCCESS).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(ReportsTestTags.PDF_OPEN_SAVED).performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf(ReportsContract.Action.OpenSavedPdf), actions)
        composeRule.runOnIdle { state = state.copy(pdfViewerUnavailable = true) }
        composeRule.onNodeWithTag(ReportsTestTags.PDF_NO_VIEWER).performScrollTo()
            .assertTextContains(str(Res.string.reports_pdf_no_viewer))
        composeRule.onNodeWithTag(ReportsTestTags.PDF_SUCCESS).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(ReportsTestTags.PDF_ERROR).assertDoesNotExist()
    }

    @Test
    fun pdfControlsAndPartialFileWarningRemainReachableAtDoubleFontSize() {
        val actions = mutableListOf<ReportsContract.Action>()
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                FacturaStockTheme {
                    ReportsScreen(
                        ReportsContract.State(isLoading = false, report = report(), pdfFailure = ReportsContract.PdfFailure.DESTINATION_MAY_CONTAIN_PARTIAL_DATA),
                        actions::add,
                    )
                }
            }
        }
        composeRule.onNodeWithTag(ReportsTestTags.PDF_ERROR).performScrollTo().assertIsDisplayed()
            .assertTextContains(str(Res.string.reports_pdf_destination_partial))
        composeRule.onNodeWithTag(ReportsTestTags.OPEN_DEBTORS).performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithText(str(Res.string.reports_pdf_dismiss_notice)).performScrollTo().performClick()
        assertEquals(listOf(
            ReportsContract.Action.OpenDebtors,
            ReportsContract.Action.PdfNoticeDismissed,
        ), actions)
    }

    private fun dialogButton(label: StringResource) = composeRule.onNode(
        hasText(str(label)) and hasAnyAncestor(hasTestTag(ReportsTestTags.VOID_DIALOG)),
    )

    private fun voidPreview(): SaleVoidPreview {
        val sale = realizedSale()
        return SaleVoidPreview(
            businessId = BUSINESS_ID,
            saleId = sale.saleId,
            postedAt = sale.postedAt,
            total = sale.totalCharged,
            refundAmount = Money.ofMinor(400L, PEN),
            debtBalanceToCancel = Money.ofMinor(780L, PEN),
            lines = sale.lines.map {
                SaleVoidLine(it.productName, it.locationName, it.unitCode, requireNotNull(it.quantity))
            },
            impactHash = "a".repeat(64),
        )
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
        businessId = BUSINESS_ID,
    )

    private fun realizedSale(costAvailable: Boolean = true): RealizedSaleProfit =
        RealizedSaleProfit(
            saleId = SALE_ID,
            totalCharged = Money.ofMinor(1_180L, PEN),
            netRevenue = Money.ofMinor(1_000L, PEN),
            historicalCost = if (costAvailable) exact("5.00") else null,
            grossProfit = if (costAvailable) exact("5.00") else null,
            lines = listOf(
                realizedLine(
                    saleLineId = FIRST_LINE_ID,
                    productId = FIRST_PRODUCT_ID,
                    position = 0,
                    name = "Café molido",
                    unitCode = "NIU",
                    quantity = "2",
                    cost = "2.00",
                    profit = "3.00",
                    costAvailable = costAvailable,
                ),
                realizedLine(
                    saleLineId = SECOND_LINE_ID,
                    productId = SECOND_PRODUCT_ID,
                    position = 1,
                    name = "Azúcar rubia",
                    unitCode = "KGM",
                    quantity = "1.5",
                    cost = "3.00",
                    profit = "2.00",
                    costAvailable = costAvailable,
                ),
            ),
            postedAt = NOW,
            issues = if (costAvailable) {
                emptySet()
            } else {
                setOf(RealizedProfitIssue.MISSING_HISTORICAL_COST)
            },
        )

    private fun realizedLine(
        saleLineId: SaleLineId,
        productId: ProductId,
        position: Int,
        name: String,
        unitCode: String,
        quantity: String,
        cost: String,
        profit: String,
        costAvailable: Boolean,
    ) = RealizedSaleLineProfit(
        saleLineId = saleLineId,
        productId = productId,
        position = position,
        productName = name,
        unitCode = unitCode,
        locationName = "Almacén Principal",
        quantity = Quantity.of(quantity),
        totalCharged = Money.ofMinor(590L, PEN),
        netRevenue = Money.ofMinor(500L, PEN),
        historicalCost = if (costAvailable) exact(cost) else null,
        grossProfit = if (costAvailable) exact(profit) else null,
        issues = if (costAvailable) emptySet()
        else setOf(RealizedProfitIssue.MISSING_HISTORICAL_COST),
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
        val BUSINESS_ID = BusinessId.from(java.util.UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"))
        val PEN: CurrencyCode = CurrencyCode.of("PEN")
        val SALE_ID: SaleId = checkNotNull(
            SaleId.parse("11111111-1111-4111-8111-111111111111"),
        )
        val FIRST_LINE_ID = SaleLineId.from(
            java.util.UUID.fromString("22222222-2222-4222-8222-222222222222"),
        )
        val SECOND_LINE_ID = SaleLineId.from(
            java.util.UUID.fromString("33333333-3333-4333-8333-333333333333"),
        )
        val FIRST_PRODUCT_ID = ProductId.from(
            java.util.UUID.fromString("44444444-4444-4444-8444-444444444444"),
        )
        val SECOND_PRODUCT_ID = ProductId.from(
            java.util.UUID.fromString("55555555-5555-4555-8555-555555555555"),
        )
        val NOW: Instant = Instant.parse("2026-08-28T15:00:00Z")
        val RANGE = SalesReportRange(
            period = SalesReportPeriod.DAY,
            startInclusive = Instant.parse("2026-08-28T05:00:00Z"),
            endExclusive = Instant.parse("2026-08-29T05:00:00Z"),
            zoneId = AppConfiguration.DEFAULT_ZONE_ID,
        )
    }

    private fun str(res: StringResource, vararg args: Any): String = runBlocking { getString(res, *args) }
}
