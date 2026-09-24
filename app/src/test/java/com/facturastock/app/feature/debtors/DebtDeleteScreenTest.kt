package com.facturastock.app.feature.debtors

import com.facturastock.app.resources.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.DebtDetail
import com.facturastock.app.domain.model.DebtLine
import com.facturastock.app.domain.model.DebtStatus
import com.facturastock.app.domain.model.DebtSummary
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DebtId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.SaleId
import com.facturastock.app.domain.model.id.SaleLineId
import com.facturastock.app.domain.repository.SaleVoidLine
import com.facturastock.app.domain.repository.SaleVoidPreview
import com.facturastock.app.ui.format.formatForDisplay
import com.facturastock.app.ui.theme.FacturaStockTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.UUID

class DebtDeleteScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun deleteActionIsReachableForBothOpenAndPaidDebts() {
        val actions = mutableListOf<DebtorsContract.Action>()
        var state by mutableStateOf(screenState())
        composeRule.setContent { FacturaStockTheme { DebtDetailScreen(state, actions::add) } }
        for (paid in listOf(false, true)) {
            composeRule.runOnIdle { state = screenState(paid) }
            composeRule.onNodeWithTag(DebtorsTestTags.DETAIL_SCREEN).performScrollToNode(hasTestTag(DebtorsTestTags.DELETE))
            composeRule
                .onNodeWithTag(DebtorsTestTags.DELETE)
                .assertIsDisplayed()
                .assertIsEnabled()
                .assertHeightIsAtLeast(48.dp)
                .performClick()
        }
        assertEquals(listOf(DebtorsContract.Action.DeleteRequested, DebtorsContract.Action.DeleteRequested), actions)
    }

    @Test
    fun confirmationShowsExactDebtStockAndRefundAndRequiresExplicitCancelOrConfirm() {
        val actions = mutableListOf<DebtorsContract.Action>()
        val state = dialogState()
        val target = requireNotNull(state.deleteTarget)
        val preview = requireNotNull(state.deletePreview)
        composeRule.setContent { FacturaStockTheme { DebtDetailScreen(state, actions::add) } }
        composeRule.onNodeWithTag(DebtorsTestTags.DELETE_DIALOG).assertIsDisplayed()
        composeRule.onNodeWithText(str(Res.string.debt_delete_debtor, target.debtorName)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(str(Res.string.debt_delete_identity, target.debtId.value)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(str(Res.string.debt_delete_date, target.createdAt.formatForDisplay())).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(str(Res.string.debt_delete_original, target.originalAmount.formatForDisplay())).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(str(Res.string.debt_delete_balance, target.balance.formatForDisplay())).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(str(Res.string.debt_delete_line, "Arroz", "2", "NIU", "Principal")).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(str(Res.string.debt_delete_refund, preview.refundAmount.formatForDisplay())).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(str(Res.string.debt_delete_history)).performScrollTo().assertIsDisplayed()
        assertTrue(actions.isEmpty())
        dialogButton(Res.string.debt_delete_cancel).assertIsEnabled().performClick()
        dialogButton(Res.string.debt_delete_confirm).assertIsEnabled().performClick()
        assertEquals(listOf(DebtorsContract.Action.DeleteDismissed, DebtorsContract.Action.DeleteConfirmed), actions)
    }

    @Test
    fun previewLoadingAndCommitBlockConfirmationAndStaleOffersReviewInsteadOfMutation() {
        val actions = mutableListOf<DebtorsContract.Action>()
        var state by mutableStateOf(dialogState().copy(deletePreview = null, isLoadingDeletePreview = true))
        composeRule.setContent { FacturaStockTheme { DebtDetailScreen(state, actions::add) } }
        dialogButton(Res.string.debt_delete_loading).assertIsNotEnabled()
        dialogButton(Res.string.debt_delete_cancel).assertIsEnabled()
        composeRule.runOnIdle { state = dialogState().copy(isDeletingDebt = true) }
        dialogButton(Res.string.debt_delete_saving).assertIsNotEnabled()
        dialogButton(Res.string.debt_delete_cancel).assertIsNotEnabled()
        composeRule.runOnIdle { state = dialogState().copy(deletePreview = null, deleteFailure = DebtorsContract.DeleteFailure.STALE) }
        composeRule
            .onNodeWithTag(DebtorsTestTags.DELETE_ERROR)
            .performScrollTo()
            .assertTextContains(str(Res.string.debt_delete_error_stale))
        dialogButton(Res.string.debt_delete_review).assertIsEnabled().performClick()
        assertEquals(listOf(DebtorsContract.Action.DeletePreviewRetry), actions)
    }

    @Test
    fun doubleFontSizeKeepsBothActionsVisibleWhileImpactScrolls() {
        val state = dialogState()
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                FacturaStockTheme { DebtDetailScreen(state, {}) }
            }
        }
        dialogButton(Res.string.debt_delete_confirm).assertIsDisplayed().assertIsEnabled()
        dialogButton(Res.string.debt_delete_cancel).assertIsDisplayed().assertIsEnabled()
        composeRule
            .onNodeWithText(str(Res.string.debt_delete_refund, money("5").formatForDisplay()))
            .performScrollTo()
            .assertIsDisplayed()
        dialogButton(Res.string.debt_delete_confirm).assertIsDisplayed()
        dialogButton(Res.string.debt_delete_cancel).assertIsDisplayed()
    }

    private fun dialogButton(label: StringResource) =
        composeRule.onNode(
            hasText(str(label)) and hasAnyAncestor(hasTestTag(DebtorsTestTags.DELETE_DIALOG)) and
                androidx.compose.ui.test
                    .hasClickAction(),
        )

    private fun dialogState(): DebtorsContract.State {
        val state = screenState()
        val debt = requireNotNull(state.detail).debt
        return state.copy(
            deleteTarget = debt,
            deletePreview =
                SaleVoidPreview(
                    debt.businessId,
                    debt.saleId,
                    debt.createdAt,
                    debt.originalAmount,
                    money("5"),
                    debt.balance,
                    listOf(SaleVoidLine("Arroz", "Principal", "NIU", Quantity.of("2"))),
                    "a".repeat(64),
                ),
        )
    }

    private fun screenState(paid: Boolean = false): DebtorsContract.State {
        val now = Instant.parse("2026-09-12T15:00:00Z")
        val debt =
            DebtSummary(
                DebtId.from(UUID(2L, 1L)),
                BusinessId.from(UUID(1L, 1L)),
                SaleId.from(UUID(3L, 1L)),
                "Ana Torres",
                money("20"),
                money(if (paid) "0" else "15"),
                if (paid) DebtStatus.PAID else DebtStatus.OPEN,
                1,
                null,
                2L,
                now,
                now,
                now.takeIf { paid },
            )
        return DebtorsContract.State(
            debtId = debt.debtId,
            isLoading = false,
            detail =
                DebtDetail(
                    debt,
                    listOf(
                        DebtLine(
                            SaleLineId.from(UUID(4L, 1L)),
                            ProductId.from(UUID(5L, 1L)),
                            "Arroz",
                            null,
                            Quantity.of("2"),
                            money("10"),
                            money("20"),
                        ),
                    ),
                    emptyList(),
                ),
        )
    }

    private fun money(value: String) = Money.fromMajor(value, CurrencyCode.of("PEN"))

    private fun str(res: StringResource, vararg args: Any): String = runBlocking { getString(res, *args) }
}
