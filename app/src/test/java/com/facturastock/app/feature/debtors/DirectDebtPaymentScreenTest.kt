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
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
import com.facturastock.app.ui.format.formatForDisplay
import com.facturastock.app.ui.theme.FacturaStockTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.UUID

class DirectDebtPaymentScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun principalActionShowsTheFullBalanceAndEmitsOnePaymentWhilePartialPaymentIsSeparate() {
        val actions = mutableListOf<DebtorsContract.Action>()
        composeRule.setContent { FacturaStockTheme { DebtDetailScreen(screenState(), actions::add) } }
        scrollTo(DebtorsTestTags.PAYMENT)
        composeRule
            .onNodeWithTag(DebtorsTestTags.PAYMENT)
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule
            .onNodeWithTag(DebtorsTestTags.PAYMENT_FULL_BALANCE)
            .assertTextContains(str(Res.string.pago_directo_saldo_completo, money("6").formatForDisplay()))
        composeRule.onNodeWithTag(DebtorsTestTags.PAYMENT_DIALOG).assertDoesNotExist()
        assertEquals(listOf(DebtorsContract.Action.PaymentRequested), actions)
        scrollTo(DebtorsTestTags.PARTIAL_PAYMENT)
        composeRule.onNodeWithTag(DebtorsTestTags.PARTIAL_PAYMENT).assertIsEnabled().performClick()
        assertEquals(DebtorsContract.Action.PartialPaymentRequested, actions.last())
    }

    @Test
    fun savingDisablesBothPaymentsAndDeletionAndFailuresRemainVisibleWithoutADialog() {
        var state by mutableStateOf(screenState().copy(isSavingPayment = true))
        composeRule.setContent { FacturaStockTheme { DebtDetailScreen(state, {}) } }
        scrollTo(DebtorsTestTags.PAYMENT)
        composeRule.onNodeWithTag(DebtorsTestTags.PAYMENT).assertIsNotEnabled()
        composeRule.onNodeWithTag(DebtorsTestTags.PARTIAL_PAYMENT).assertIsNotEnabled()
        scrollTo(DebtorsTestTags.DELETE)
        composeRule.onNodeWithTag(DebtorsTestTags.DELETE).assertIsNotEnabled()
        scrollTo(DebtorsTestTags.PAYMENT_PROGRESS)
        composeRule.onNodeWithTag(DebtorsTestTags.PAYMENT_PROGRESS).assertIsDisplayed()
        composeRule.runOnIdle { state = state.copy(isSavingPayment = false, failure = DebtorsContract.Failure.SAVE_PAYMENT_FAILED) }
        scrollTo(DebtorsTestTags.PAYMENT_ERROR)
        composeRule
            .onNodeWithTag(DebtorsTestTags.PAYMENT_ERROR)
            .assertIsDisplayed()
            .assertTextContains(str(Res.string.pago_directo_error_guardar))
        composeRule.onNodeWithTag(DebtorsTestTags.PAYMENT_DIALOG).assertDoesNotExist()
        composeRule.runOnIdle { state = state.copy(failure = DebtorsContract.Failure.STALE_DEBT) }
        composeRule
            .onNodeWithTag(DebtorsTestTags.PAYMENT_ERROR)
            .assertTextContains(str(Res.string.pago_directo_error_actualizado))
    }

    @Test
    fun paidDebtHasNoPaymentActionsAndLargeFontKeepsTheFullBalanceActionReachable() {
        var state by mutableStateOf(screenState())
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                FacturaStockTheme { DebtDetailScreen(state, {}) }
            }
        }
        scrollTo(DebtorsTestTags.PAYMENT)
        composeRule.onNodeWithTag(DebtorsTestTags.PAYMENT).assertIsDisplayed().assertIsEnabled()
        scrollTo(DebtorsTestTags.PAYMENT_FULL_BALANCE)
        composeRule.onNodeWithTag(DebtorsTestTags.PAYMENT_FULL_BALANCE).assertIsDisplayed()
        composeRule.runOnIdle {
            val detail = requireNotNull(state.detail)
            state = state.copy(detail = detail.copy(debt = detail.debt.copy(balance = money("0"), status = DebtStatus.PAID, paidAt = detail.debt.createdAt)))
        }
        composeRule.onNodeWithTag(DebtorsTestTags.PAYMENT).assertDoesNotExist()
        composeRule.onNodeWithTag(DebtorsTestTags.PARTIAL_PAYMENT).assertDoesNotExist()
    }

    private fun scrollTo(tag: String) {
        composeRule.onNodeWithTag(DebtorsTestTags.DETAIL_SCREEN).performScrollToNode(hasTestTag(tag))
    }

    private fun screenState(): DebtorsContract.State {
        val now = Instant.parse("2026-09-12T15:00:00Z")
        val debt =
            DebtSummary(
                DebtId.from(UUID(2L, 1L)),
                BusinessId.from(UUID(1L, 1L)),
                SaleId.from(UUID(3L, 1L)),
                "Ana Torres",
                money("10"),
                money("6"),
                DebtStatus.OPEN,
                1,
                null,
                2L,
                now,
                now,
                null,
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
                            Quantity.of("1"),
                            money("10"),
                            money("10"),
                        ),
                    ),
                    emptyList(),
                ),
        )
    }

    private fun money(value: String) = Money.fromMajor(value, CurrencyCode.of("PEN"))

    private fun str(res: StringResource, vararg args: Any): String = runBlocking { getString(res, *args) }
}
