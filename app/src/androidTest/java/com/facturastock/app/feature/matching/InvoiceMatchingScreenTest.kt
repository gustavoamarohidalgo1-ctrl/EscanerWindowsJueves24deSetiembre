package com.facturastock.app.feature.matching

import androidx.annotation.StringRes
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.facturastock.app.R
import com.facturastock.app.domain.model.ScannedItemMatch
import com.facturastock.app.feature.matching.InvoiceMatchingContract.Action
import com.facturastock.app.feature.matching.InvoiceMatchingContract.State
import com.facturastock.app.ui.theme.FacturaStockTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Ejercita los TextField reales; el estado simulado conserva exactamente sus callbacks. */
@RunWith(AndroidJUnit4::class)
class InvoiceMatchingScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun reviewEditorPreservesDecimalCommasForQuantityAndCost() {
        val state =
            mutableStateOf(
                State(
                    isLoading = false,
                    items = listOf(ScannedItemMatch(0, "Arroz")),
                    editingItemIndex = 0,
                ),
            )
        composeRule.setContent {
            FacturaStockTheme {
                InvoiceMatchingScreen(state.value, onAction = { action ->
                    state.value =
                        when (action) {
                            is Action.EditQuantityChanged -> state.value.copy(editQuantity = action.value)
                            is Action.EditCostChanged -> state.value.copy(editCost = action.value)
                            else -> state.value
                        }
                })
            }
        }
        composeRule.onNodeWithTag("matching_edit_quantity").performTextInput("1,5")
        composeRule.onNodeWithTag("matching_edit_cost").performTextInput("12,50")
        composeRule.onNodeWithTag("matching_edit_quantity").assertTextContains("1,5")
        composeRule.onNodeWithTag("matching_edit_cost").assertTextContains("12,50")
        composeRule.runOnIdle {
            assertEquals("1,5", state.value.editQuantity)
            assertEquals("12,50", state.value.editCost)
        }
    }

    @Test
    fun typingDecimalCommasPreservesPriceAndQuantityInCallbacksAndFields() {
        val state =
            mutableStateOf(
                State(isLoading = false, creatingItemIndex = 0, createName = "Arroz"),
            )
        val actions = mutableListOf<Action>()
        composeRule.setContent {
            FacturaStockTheme {
                InvoiceMatchingScreen(
                    state = state.value,
                    onAction = { action ->
                        actions += action
                        state.value =
                            when (action) {
                                is Action.CreatePriceChanged -> state.value.copy(createPrice = action.value)
                                is Action.CreateQuantityChanged -> state.value.copy(createQuantity = action.value)
                                else -> state.value
                            }
                    },
                )
            }
        }

        val price = composeRule.onNodeWithText(text(R.string.catalog_product_price_label))
        listOf("12", ",", "50").forEach { price.performTextInput(it) }
        price.assertTextContains("12,50")

        val quantity = composeRule.onNodeWithText(text(R.string.catalog_product_quantity_label))
        listOf("1", ",", "5").forEach { quantity.performTextInput(it) }
        quantity.assertTextContains("1,5")

        composeRule.runOnIdle {
            assertEquals("12,50", state.value.createPrice)
            assertEquals("1,5", state.value.createQuantity)
            assertEquals(
                listOf("12", "12,", "12,50"),
                actions.filterIsInstance<Action.CreatePriceChanged>().map { it.value },
            )
            assertEquals(
                listOf("1", "1,", "1,5"),
                actions.filterIsInstance<Action.CreateQuantityChanged>().map { it.value },
            )
        }
    }

    @Test
    fun failedProductSearchOffersRetryThenShowsLoadingAndAnEmptyResultSeparately() {
        val state =
            mutableStateOf(
                State(
                    isLoading = false,
                    linkingItemIndex = 0,
                    searchQuery = "Arroz",
                    searchFailed = true,
                ),
            )
        val actions = mutableListOf<Action>()
        composeRule.setContent {
            FacturaStockTheme {
                InvoiceMatchingScreen(
                    state = state.value,
                    onAction = { action ->
                        actions += action
                        if (action == Action.RetrySearch) {
                            state.value = state.value.copy(isSearching = true, searchFailed = false)
                        }
                    },
                )
            }
        }

        composeRule.onNodeWithText(text(R.string.matching_search_error)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(text(R.string.matching_search_loading)).assertDoesNotExist()
        composeRule.onNodeWithText(text(R.string.matching_dialog_link_no_results)).assertDoesNotExist()
        composeRule.onNodeWithText(text(R.string.action_retry)).assertIsEnabled().performClick()

        composeRule.onNodeWithContentDescription(text(R.string.matching_search_loading)).assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.matching_search_error)).assertDoesNotExist()
        composeRule.onNodeWithText(text(R.string.action_retry)).assertDoesNotExist()
        composeRule.onNodeWithText(text(R.string.matching_dialog_link_no_results)).assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(listOf(Action.RetrySearch), actions)
            assertEquals("Arroz", state.value.searchQuery)
            state.value = state.value.copy(isSearching = false)
        }

        composeRule.onNodeWithText(text(R.string.matching_dialog_link_no_results)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(text(R.string.matching_search_loading)).assertDoesNotExist()
        composeRule.onNodeWithText(text(R.string.matching_search_error)).assertDoesNotExist()
        composeRule.onNodeWithText(text(R.string.action_retry)).assertDoesNotExist()
    }

    @Test
    fun savingDisablesProductFieldsAndDialogButtonsUntilTheOperationFinishes() {
        val state =
            mutableStateOf(
                State(
                    isLoading = false,
                    creatingItemIndex = 0,
                    createName = "Arroz",
                    createBarcode = "0001234567895",
                    createPrice = "12,50",
                    createQuantity = "1,5",
                    isSaving = true,
                ),
            )
        val actions = mutableListOf<Action>()
        composeRule.setContent {
            FacturaStockTheme {
                InvoiceMatchingScreen(state = state.value, onAction = actions::add)
            }
        }

        val labels =
            listOf(
                R.string.catalog_barcode_label,
                R.string.catalog_name_label,
                R.string.catalog_product_price_label,
                R.string.catalog_product_quantity_label,
                R.string.action_save,
                R.string.action_cancel,
            )
        labels.forEach { label ->
            composeRule.onNodeWithText(text(label)).assertIsNotEnabled()
        }
        composeRule.runOnIdle {
            assertTrue(actions.isEmpty())
            state.value = state.value.copy(isSaving = false)
        }
        labels.forEach { label ->
            composeRule.onNodeWithText(text(label)).assertIsEnabled()
        }
        composeRule.onNodeWithText(text(R.string.catalog_product_price_label)).assertTextContains("12,50")
        composeRule.onNodeWithText(text(R.string.catalog_product_quantity_label)).assertTextContains("1,5")
    }

    private fun text(
        @StringRes resource: Int,
    ): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(resource)
}
