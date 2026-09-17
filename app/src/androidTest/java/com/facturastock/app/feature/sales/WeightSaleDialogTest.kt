package com.facturastock.app.feature.sales

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.facturastock.app.R
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.feature.sales.SalesContract.Action
import com.facturastock.app.feature.sales.SalesContract.WeightEntryMode
import com.facturastock.app.feature.sales.SalesContract.WeightSaleEditor
import com.facturastock.app.feature.sales.SalesContract.WeightSaleFailure
import com.facturastock.app.ui.format.formatForDisplay
import com.facturastock.app.ui.theme.FacturaStockTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WeightSaleDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun amountIsDefaultAndDecimalCommaShowsExactKilosBeforeConfirming() {
        val actions = mutableListOf<Action>()
        renderEditor(editor(), actions)

        composeRule.onNodeWithTag(WeightSaleTestTags.AMOUNT_MODE).assertIsSelected()
        composeRule.onNodeWithText(string(R.string.weight_sale_add)).assertIsNotEnabled()
        composeRule.onNodeWithTag(WeightSaleTestTags.INPUT).performTextInput("5,25")
        composeRule
            .onNodeWithTag(WeightSaleTestTags.PRICE)
            .assertTextContains(string(R.string.weight_sale_price_per_kg, money("6").formatForDisplay()))
        composeRule
            .onNodeWithTag(WeightSaleTestTags.QUANTITY, useUnmergedTree = true)
            .assertTextContains("Cantidad: 0,875 kg")
        composeRule
            .onNodeWithTag(WeightSaleTestTags.TOTAL, useUnmergedTree = true)
            .assertTextContains(string(R.string.weight_sale_total, money("5.25").formatForDisplay()))
        composeRule.onNodeWithText(string(R.string.weight_sale_add)).assertIsEnabled().performClick()

        assertTrue(actions.contains(Action.WeightAmountChanged("5,25")))
        assertEquals(Action.WeightSaleConfirmed, actions.last())
    }

    @Test
    fun kilosModeCalculatesChargeAndUsesQuantityAction() {
        val actions = mutableListOf<Action>()
        renderEditor(editor(), actions)

        composeRule.onNodeWithTag(WeightSaleTestTags.QUANTITY_MODE).performClick().assertIsSelected()
        composeRule.onNodeWithTag(WeightSaleTestTags.INPUT).performTextInput("1,25")
        composeRule
            .onNodeWithTag(WeightSaleTestTags.QUANTITY, useUnmergedTree = true)
            .assertTextContains("Cantidad: 1,25 kg")
        composeRule
            .onNodeWithTag(WeightSaleTestTags.TOTAL, useUnmergedTree = true)
            .assertTextContains(string(R.string.weight_sale_total, money("7.50").formatForDisplay()))
        composeRule.onNodeWithText(string(R.string.weight_sale_add)).assertIsEnabled().performClick()

        assertTrue(actions.contains(Action.WeightEntryModeChanged(WeightEntryMode.QUANTITY)))
        assertTrue(actions.contains(Action.WeightQuantityChanged("1,25")))
        assertEquals(Action.WeightSaleConfirmed, actions.last())
    }

    @Test
    fun invalidOrOverStockAmountCannotConfirmButExactAvailableStockCan() {
        val state = renderEditor(editor())
        for (input in listOf("0", "-2", "abc", "12,01")) {
            composeRule.runOnIdle { state.value = state.value.copy(amountInput = input) }
            composeRule.onNodeWithText(string(R.string.weight_sale_add)).assertIsNotEnabled()
            composeRule.onNodeWithTag(WeightSaleTestTags.ERROR).performScrollTo().assertIsDisplayed()
        }
        composeRule
            .onNodeWithTag(WeightSaleTestTags.ERROR)
            .assertTextContains(string(R.string.weight_sale_exceeds_stock))

        composeRule.runOnIdle { state.value = state.value.copy(amountInput = "12") }
        composeRule.onNodeWithText(string(R.string.weight_sale_add)).assertIsEnabled()
        composeRule.onNodeWithTag(WeightSaleTestTags.ERROR).assertDoesNotExist()
    }

    @Test
    fun unavailablePriceAndStaleCartExplainWhyConfirmationIsBlocked() {
        val initial = editor(amountInput = "3")
        val state = renderEditor(initial.copy(product = initial.product.copy(suggestedSalePrice = null)))

        composeRule.onNodeWithText(string(R.string.weight_sale_add)).assertIsNotEnabled()
        composeRule
            .onNodeWithTag(WeightSaleTestTags.ERROR)
            .assertTextContains(string(R.string.weight_sale_missing_price))

        composeRule.runOnIdle { state.value = initial.copy(failure = WeightSaleFailure.STALE_CART) }
        composeRule.onNodeWithText(string(R.string.weight_sale_add)).assertIsNotEnabled()
        composeRule
            .onNodeWithTag(WeightSaleTestTags.ERROR)
            .assertTextContains(string(R.string.weight_sale_stale_cart))
    }

    @Test
    fun savingBlocksBothModesInputConfirmationAndDismissal() {
        renderEditor(editor(amountInput = "3", lineId = "weight-line"), isMutating = true)

        composeRule.onNodeWithTag(WeightSaleTestTags.INPUT).assertIsNotEnabled()
        composeRule.onNodeWithTag(WeightSaleTestTags.AMOUNT_MODE).assertIsNotEnabled()
        composeRule.onNodeWithTag(WeightSaleTestTags.QUANTITY_MODE).assertIsNotEnabled()
        composeRule.onNodeWithText(string(R.string.weight_sale_save)).assertIsNotEnabled()
        composeRule.onNodeWithText(string(R.string.action_cancel)).assertIsNotEnabled()
    }

    @Test
    fun periodicKilosHaveAnApproximationMarkWhileTheAmountRemainsExact() {
        val initial = editor(amountInput = "1")
        renderEditor(initial.copy(product = initial.product.copy(suggestedSalePrice = money("3"))))

        composeRule
            .onNodeWithTag(WeightSaleTestTags.QUANTITY, useUnmergedTree = true)
            .assertTextContains("Cantidad: ≈ 0,333333 kg")
        composeRule
            .onNodeWithTag(WeightSaleTestTags.TOTAL, useUnmergedTree = true)
            .assertTextContains(string(R.string.weight_sale_total, money("1").formatForDisplay()))
        composeRule.onNodeWithText(string(R.string.weight_sale_add)).assertIsEnabled()
    }

    @Test
    fun weightCartLineEditsAmountAndNormalLineKeepsItsQuantityAndPriceControls() {
        val actions = mutableListOf<Action>()
        val weight = line("weight-line", isWeightProduct = true)
        val normal = line("normal-line", isWeightProduct = false)
        composeRule.setContent {
            FacturaStockTheme {
                SalesScreen(
                    state =
                        SalesContract.State(
                            isLoading = false,
                            entryStep = SalesContract.EntryStep.SELL,
                            mode = SalesContract.EntryMode.MANUAL,
                            cartLines = listOf(weight, normal),
                            total = money("10.50"),
                        ),
                    onAction = actions::add,
                )
            }
        }

        composeRule
            .onNodeWithTag(SalesTestTags.SCREEN)
            .performScrollToNode(hasTestTag(WeightSaleTestTags.edit(weight.lineId)))
        composeRule
            .onNodeWithTag(WeightSaleTestTags.summary(weight.lineId))
            .assertTextContains("0,875 kg", substring = true)
        composeRule.onNodeWithTag(SalesTestTags.quantity(weight.lineId)).assertDoesNotExist()
        composeRule.onNodeWithTag(SalesTestTags.price(weight.lineId)).assertDoesNotExist()
        composeRule.onNodeWithTag(SalesTestTags.quantityIncrease(weight.lineId)).assertDoesNotExist()
        composeRule.onNodeWithTag(SalesTestTags.quantityDecrease(weight.lineId)).assertDoesNotExist()
        composeRule.onNodeWithTag(WeightSaleTestTags.edit(weight.lineId)).performClick()
        assertEquals(Action.EditWeightSale(weight.lineId), actions.last())

        composeRule
            .onNodeWithTag(SalesTestTags.SCREEN)
            .performScrollToNode(hasTestTag(SalesTestTags.quantity(normal.lineId)))
        composeRule.onNodeWithTag(SalesTestTags.quantity(normal.lineId)).assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithTag(SalesTestTags.quantityIncrease(normal.lineId)).assertIsEnabled()
        composeRule.onNodeWithTag(SalesTestTags.quantityDecrease(normal.lineId)).assertIsEnabled()
        composeRule.onNodeWithTag(SalesTestTags.price(normal.lineId)).performScrollTo().assertIsEnabled()
        composeRule.onNodeWithTag(WeightSaleTestTags.edit(normal.lineId)).assertDoesNotExist()
    }

    @Test
    fun salesScreenHostsWeightEditorForCreditSaleAndDismissesThroughItsAction() {
        val actions = mutableListOf<Action>()
        composeRule.setContent {
            FacturaStockTheme {
                SalesScreen(
                    state =
                        SalesContract.State(
                            isLoading = false,
                            entryStep = SalesContract.EntryStep.SELL,
                            entryKind = SalesContract.EntryKind.CREDIT,
                            weightSaleEditor = editor(),
                        ),
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithTag(WeightSaleTestTags.DIALOG).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.action_cancel)).performClick()
        assertEquals(Action.WeightSaleDismissed, actions.last())
    }

    private fun renderEditor(
        initial: WeightSaleEditor,
        actions: MutableList<Action> = mutableListOf(),
        isMutating: Boolean = false,
    ): MutableState<WeightSaleEditor> {
        val editorState = mutableStateOf(initial)
        composeRule.setContent {
            FacturaStockTheme {
                WeightSaleDialog(editorState.value, isMutating) { action ->
                    actions.add(action)
                    editorState.value =
                        when (action) {
                            is Action.WeightEntryModeChanged -> editorState.value.copy(mode = action.mode)
                            is Action.WeightAmountChanged -> editorState.value.copy(amountInput = action.value)
                            is Action.WeightQuantityChanged -> editorState.value.copy(quantityInput = action.value)
                            else -> editorState.value
                        }
                }
            }
        }
        return editorState
    }

    private fun editor(
        amountInput: String = "",
        lineId: String? = null,
    ) = WeightSaleEditor(
        product =
            SalesContract.ProductOption(
                productId = PRODUCT_ID,
                productName = "Arroz a granel",
                locationId = LOCATION_ID,
                locationName = "Tienda",
                unitCode = "KGM",
                availableQuantity = BigDecimal("2"),
                sku = null,
                barcode = null,
                suggestedSalePrice = money("6"),
                isWeightProduct = true,
            ),
        cartId = "weight-cart",
        cartVersion = 1L,
        businessId = BUSINESS_ID,
        lineId = lineId,
        amountInput = amountInput,
    )

    private fun line(
        id: String,
        isWeightProduct: Boolean,
    ) = SalesContract.CartLine(
        lineId = id,
        productId = PRODUCT_ID,
        productName = if (isWeightProduct) "Arroz a granel" else "Producto normal",
        locationId = LOCATION_ID,
        locationName = "Tienda",
        unitCode = if (isWeightProduct) "KGM" else "NIU",
        availableQuantity = BigDecimal("2"),
        quantityInput = "0.875",
        unitPriceInput = "6.00",
        lineTotal = money("5.25"),
        isWeightProduct = isWeightProduct,
    )

    private fun money(amount: String) = Money.fromMajor(amount, CurrencyCode.of("PEN"))

    private fun string(
        id: Int,
        vararg args: Any,
    ): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    companion object {
        private val BUSINESS_ID = BusinessId.from(UUID.randomUUID())
        private val PRODUCT_ID = ProductId.from(UUID.randomUUID())
        private val LOCATION_ID = LocationId.from(UUID.randomUUID())
    }
}
