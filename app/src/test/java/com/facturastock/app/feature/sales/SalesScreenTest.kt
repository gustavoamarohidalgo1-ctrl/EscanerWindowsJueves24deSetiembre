package com.facturastock.app.feature.sales

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import com.facturastock.app.resources.Res
import com.facturastock.app.resources.sales_processing
import com.facturastock.app.resources.sales_scanner_help
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.ui.theme.FacturaStockTheme
import java.math.BigDecimal
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SalesScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun initialSelectorOffersCashAndCreditWithoutDebtorsShortcut() {
        val actions = mutableListOf<SalesContract.Action>()
        composeRule.setContent {
            FacturaStockTheme {
                SalesScreen(state = SalesContract.State(isLoading = false), onAction = actions::add)
            }
        }

        composeRule.onNodeWithTag(SalesTestTags.CASH_ENTRY).assertIsDisplayed()
        composeRule.onNodeWithTag(SalesTestTags.CREDIT_ENTRY).assertIsDisplayed()
        composeRule.onNodeWithTag(SalesTestTags.OPEN_DEBTORS).assertDoesNotExist()
        assertTrue(actions.isEmpty())
    }

    @Test
    fun debtorsShortcutStaysAbsentDuringMutationAndOutsideTheInitialSelector() {
        var state by mutableStateOf(SalesContract.State(isLoading = false, isMutating = true))
        var allowEntryKindSelection by mutableStateOf(true)
        val actions = mutableListOf<SalesContract.Action>()
        composeRule.setContent {
            FacturaStockTheme {
                SalesScreen(
                    state = state,
                    onAction = actions::add,
                    allowEntryKindSelection = allowEntryKindSelection,
                )
            }
        }

        composeRule.onNodeWithTag(SalesTestTags.OPEN_DEBTORS).assertDoesNotExist()
        composeRule.runOnIdle {
            state = state.copy(isMutating = false, entryStep = SalesContract.EntryStep.SELL)
        }
        composeRule.onNodeWithTag(SalesTestTags.OPEN_DEBTORS).assertDoesNotExist()
        composeRule.runOnIdle {
            state = state.copy(entryStep = SalesContract.EntryStep.SELECT_KIND)
            allowEntryKindSelection = false
        }
        composeRule.onNodeWithTag(SalesTestTags.OPEN_DEBTORS).assertDoesNotExist()
        assertTrue(actions.isEmpty())
    }

    @Test
    fun emptyCartShowsOneUsefulPromptWithoutPendingCheckoutControls() {
        composeRule.setContent {
            FacturaStockTheme {
                SalesScreen(
                    state = SalesContract.State(
                        isLoading = false,
                        entryStep = SalesContract.EntryStep.SELL,
                        mode = SalesContract.EntryMode.MANUAL,
                    ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithText("Nueva venta").assertDoesNotExist()
        composeRule.onNodeWithTag(SalesTestTags.SCREEN).performScrollToNode(
            hasText("Toca un producto del catálogo para agregarlo a la venta."),
        )
        composeRule.onNodeWithText("Toca un producto del catálogo para agregarlo a la venta.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Total: precio pendiente").assertDoesNotExist()
        composeRule.onNodeWithTag(SalesTestTags.CHECKOUT).assertDoesNotExist()
    }

    @Test
    fun manualModeSearchesOnlyByNameWithoutExposingBarcodeEntry() {
        val actions = mutableListOf<SalesContract.Action>()
        composeRule.setContent {
            FacturaStockTheme {
                SalesScreen(
                    state = SalesContract.State(
                        entryStep = SalesContract.EntryStep.SELL,
                        isLoading = false,
                        mode = SalesContract.EntryMode.MANUAL,
                    ),
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithTag(SalesTestTags.SEARCH)
            .assertIsDisplayed()
            .performTextInput("Café")
        composeRule.onNodeWithText("Código de barras").assertDoesNotExist()
        assertTrue(actions.contains(SalesContract.Action.SearchChanged("Café")))
    }

    @Test
    fun unifiedInputShowsProductsFromTwoLettersWithoutASecondFieldOrModePicker() {
        val actions = mutableListOf<SalesContract.Action>()
        var state by mutableStateOf(
            SalesContract.State(
                entryStep = SalesContract.EntryStep.SELL,
                isLoading = false,
                mode = SalesContract.EntryMode.SCANNER,
                unifiedInput = true,
                availableProducts = listOf(option()),
                productOptions = listOf(option()),
            ),
        )
        composeRule.setContent {
            FacturaStockTheme { SalesScreen(state = state, onAction = actions::add) }
        }

        val optionTag = SalesTestTags.option(PRODUCT_ID.value, LOCATION_ID.value)
        composeRule.onNodeWithText("Escribe al menos 2 letras para ver productos.").assertIsDisplayed()
        composeRule.onNodeWithTag(optionTag).assertDoesNotExist()
        composeRule.onNodeWithTag(SalesTestTags.SEARCH).assertDoesNotExist()
        composeRule.onNodeWithTag(SalesTestTags.SCANNER_MODE).assertDoesNotExist()
        composeRule.onNodeWithTag(SalesTestTags.MANUAL_MODE).assertDoesNotExist()

        composeRule.runOnIdle { state = state.copy(query = "C") }
        composeRule.onNodeWithTag(optionTag).assertDoesNotExist()

        composeRule.runOnIdle { state = state.copy(query = "Ca") }
        composeRule.onNodeWithTag(SalesTestTags.SCREEN).performScrollToNode(hasTestTag(optionTag))
        composeRule.onNodeWithTag(optionTag).assertIsDisplayed().performClick()
        assertEquals(listOf(SalesContract.Action.ProductSelected(PRODUCT_ID, LOCATION_ID)), actions)
    }

    @Test
    fun unifiedCreditAssociationKeepsDebtorAndCancelWithoutAnotherSearchField() {
        val actions = mutableListOf<SalesContract.Action>()
        composeRule.setContent {
            FacturaStockTheme {
                SalesScreen(
                    state = SalesContract.State(
                        entryStep = SalesContract.EntryStep.SELL,
                        entryKind = SalesContract.EntryKind.CREDIT,
                        isLoading = false,
                        mode = SalesContract.EntryMode.SCANNER,
                        unifiedInput = true,
                        query = "Ca",
                        pendingAssociationBarcode = "NEW-CODE",
                        productOptions = listOf(option()),
                    ),
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithTag(SalesTestTags.DEBTOR_NAME).assertIsDisplayed()
        composeRule.onNodeWithTag(SalesTestTags.SEARCH).assertDoesNotExist()
        composeRule.onNodeWithTag(SalesTestTags.SCANNER_MODE).assertDoesNotExist()
        composeRule.onNodeWithTag(SalesTestTags.MANUAL_MODE).assertDoesNotExist()
        composeRule.onNodeWithTag(SalesTestTags.ASSOCIATION_CANCEL)
            .performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf(SalesContract.Action.AssociationDismissed), actions)
    }

    @Test
    fun saleTypeSelectorCanStartCreditFromTheRegularSalesScreen() {
        val actions = mutableListOf<SalesContract.Action>()
        composeRule.setContent {
            FacturaStockTheme {
                SalesScreen(
                    state = SalesContract.State(isLoading = false),
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithText("Tipo de venta").assertIsDisplayed()
        composeRule.onNodeWithTag(SalesTestTags.CREDIT_ENTRY)
            .assertIsDisplayed()
            .performClick()

        assertEquals(
            SalesContract.Action.EntryKindChanged(SalesContract.EntryKind.CREDIT),
            actions.last(),
        )
    }

    @Test
    fun cashOpensASeparateModeScreenAndManualShowsProductsToTap() {
        val actions = mutableListOf<SalesContract.Action>()
        renderStepNavigation(
            initialState = SalesContract.State(
                isLoading = false,
                availableProducts = listOf(option()),
            ),
            actions = actions,
        )

        composeRule.onNodeWithTag(SalesTestTags.ENTRY_KIND_SCREEN).assertIsDisplayed()
        composeRule.onNodeWithTag(SalesTestTags.SCANNER_MODE).assertDoesNotExist()
        composeRule.onNodeWithTag(SalesTestTags.MANUAL_MODE).assertDoesNotExist()
        composeRule.onNodeWithTag(SalesTestTags.SEARCH).assertDoesNotExist()
        composeRule.onNodeWithTag(SalesTestTags.CASH_ENTRY).performClick()

        composeRule.onNodeWithTag(SalesTestTags.ENTRY_KIND_SCREEN).assertDoesNotExist()
        composeRule.onNodeWithTag(SalesTestTags.ENTRY_MODE_SCREEN).assertIsDisplayed()
        composeRule.onNodeWithTag(SalesTestTags.SCANNER_MODE).assertIsDisplayed()
        composeRule.onNodeWithTag(SalesTestTags.MANUAL_MODE).assertIsDisplayed().performClick()

        composeRule.onNodeWithTag(SalesTestTags.ENTRY_MODE_SCREEN).assertDoesNotExist()
        composeRule.onNodeWithTag(SalesTestTags.SEARCH).assertIsDisplayed()
        composeRule.onNodeWithTag(SalesTestTags.SCANNER_STATUS).assertDoesNotExist()
        composeRule.onNodeWithTag(SalesTestTags.SCREEN).performScrollToNode(
            hasTestTag(SalesTestTags.option(PRODUCT_ID.value, LOCATION_ID.value)),
        )
        composeRule.onNodeWithTag(SalesTestTags.option(PRODUCT_ID.value, LOCATION_ID.value))
            .assertIsDisplayed()
            .performClick()

        assertEquals(
            listOf(
                SalesContract.Action.EntryKindChanged(SalesContract.EntryKind.CASH),
                SalesContract.Action.ModeChanged(SalesContract.EntryMode.MANUAL),
                SalesContract.Action.ProductSelected(PRODUCT_ID, LOCATION_ID),
            ),
            actions,
        )
    }

    @Test
    fun cashScannerScreenKeepsTheManualCatalogHidden() {
        val actions = mutableListOf<SalesContract.Action>()
        renderStepNavigation(
            initialState = SalesContract.State(
                isLoading = false,
                availableProducts = listOf(option()),
            ),
            actions = actions,
        )

        composeRule.onNodeWithTag(SalesTestTags.CASH_ENTRY).performClick()
        composeRule.onNodeWithTag(SalesTestTags.SCANNER_MODE).assertIsDisplayed().performClick()

        composeRule.onNodeWithTag(SalesTestTags.ENTRY_MODE_SCREEN).assertDoesNotExist()
        composeRule.onNodeWithTag(SalesTestTags.SCANNER_STATUS).assertIsDisplayed()
        composeRule.onNodeWithTag(SalesTestTags.SEARCH).assertDoesNotExist()
        composeRule.onNodeWithTag(SalesTestTags.OPTIONS).assertDoesNotExist()
        composeRule.onNodeWithTag(SalesTestTags.option(PRODUCT_ID.value, LOCATION_ID.value))
            .assertDoesNotExist()
        assertEquals(
            SalesContract.Action.ModeChanged(SalesContract.EntryMode.SCANNER),
            actions.last(),
        )
    }

    @Test
    fun stepBackReturnsThroughBothSelectorsAndKeepsTheVisibleCart() {
        val actions = mutableListOf<SalesContract.Action>()
        val initialState = checkoutReadyState().copy(mode = SalesContract.EntryMode.MANUAL)
        renderStepNavigation(initialState, actions)

        composeRule.onNodeWithTag(SalesTestTags.STEP_BACK).performScrollTo().performClick()
        composeRule.onNodeWithTag(SalesTestTags.ENTRY_MODE_SCREEN).assertIsDisplayed()
        composeRule.onNodeWithTag(SalesTestTags.CART).assertDoesNotExist()
        composeRule.onNodeWithTag(SalesTestTags.STEP_BACK).performClick()
        composeRule.onNodeWithTag(SalesTestTags.ENTRY_KIND_SCREEN).assertIsDisplayed()
        composeRule.onNodeWithTag(SalesTestTags.CASH_ENTRY).performClick()
        composeRule.onNodeWithTag(SalesTestTags.MANUAL_MODE).performClick()

        val line = initialState.cartLines.single()
        composeRule.onNodeWithTag(SalesTestTags.SCREEN)
            .performScrollToNode(hasTestTag(SalesTestTags.quantity(line.lineId)))
        composeRule.onNodeWithTag(SalesTestTags.quantity(line.lineId))
            .assert(hasText(line.quantityInput))
        composeRule.onNodeWithTag(SalesTestTags.price(line.lineId))
            .performScrollTo()
            .assert(hasText(line.unitPriceInput))
        composeRule.onNodeWithTag(SalesTestTags.SCREEN)
            .performScrollToNode(hasTestTag(SalesTestTags.CHECKOUT))
        composeRule.onNodeWithTag(SalesTestTags.CHECKOUT).assertIsEnabled()
        assertEquals(
            2,
            actions.count { it == SalesContract.Action.StepBackSelected },
        )
    }

    @Test
    fun creditSaleCollectsThePersonAndUsesDebtCopy() {
        val actions = mutableListOf<SalesContract.Action>()
        var state by mutableStateOf(
            checkoutReadyState().copy(entryKind = SalesContract.EntryKind.CREDIT),
        )
        composeRule.setContent {
            FacturaStockTheme {
                SalesScreen(
                    state = state,
                    onAction = { action ->
                        actions += action
                        state = when (action) {
                            is SalesContract.Action.DebtorNameChanged ->
                                state.copy(debtorNameInput = action.value)
                            SalesContract.Action.StepBackSelected ->
                                state.copy(entryStep = SalesContract.EntryStep.SELECT_MODE)
                            is SalesContract.Action.ModeChanged ->
                                state.copy(entryStep = SalesContract.EntryStep.SELL, mode = action.mode)
                            else -> state
                        }
                    },
                )
            }
        }

        composeRule.onNodeWithTag(SalesTestTags.DEBTOR_NAME)
            .assertIsDisplayed()
            .performTextInput("María Quispe")
        assertTrue(actions.contains(SalesContract.Action.TextInputFocusChanged("debtor", true)))
        composeRule.onNodeWithTag(SalesTestTags.STEP_BACK).performScrollTo().performClick()
        composeRule.onNodeWithTag(SalesTestTags.SCANNER_MODE).performClick()
        assertTrue(actions.contains(SalesContract.Action.TextInputFocusChanged("debtor", false)))
        composeRule.onNodeWithTag(SalesTestTags.SCREEN)
            .performScrollToNode(hasTestTag(SalesTestTags.CHECKOUT))
        composeRule.onNodeWithText("Productos por pagar").assertIsDisplayed()
        composeRule.onNodeWithTag(SalesTestTags.CHECKOUT)
            .assertIsEnabled()
            .performClick()

        assertTrue(actions.contains(SalesContract.Action.DebtorNameChanged("María Quispe")))
        assertEquals(SalesContract.Action.CheckoutRequested, actions.last())
    }

    @Test
    fun scannerResumeClearsTheRealDebtorFieldFocusAfterEditing() {
        val actions = mutableListOf<SalesContract.Action>()
        var state by mutableStateOf(
            SalesContract.State(
                isLoading = false,
                entryStep = SalesContract.EntryStep.SELL,
                entryKind = SalesContract.EntryKind.CREDIT,
                mode = SalesContract.EntryMode.SCANNER,
                scannerActive = true,
            ),
        )
        composeRule.setContent {
            FacturaStockTheme {
                SalesScreen(
                    state = state,
                    onAction = { action ->
                        actions += action
                        state = when (action) {
                            is SalesContract.Action.DebtorNameChanged ->
                                state.copy(debtorNameInput = action.value)
                            is SalesContract.Action.TextInputFocusChanged ->
                                state.copy(isTextInputFocused = action.focused)
                            else -> state
                        }
                    },
                )
            }
        }

        composeRule.onNodeWithTag(SalesTestTags.SCANNER_RESUME).assertDoesNotExist()
        composeRule.onNodeWithTag(SalesTestTags.DEBTOR_NAME)
            .assertIsDisplayed()
            .performTextInput("María Quispe")
        assertTrue(actions.contains(SalesContract.Action.TextInputFocusChanged("debtor", true)))

        composeRule.onNodeWithTag(SalesTestTags.SCANNER_RESUME)
            .performScrollTo()
            .assertIsDisplayed()
            .assert(hasText("Continuar escaneando"))
            .performClick()

        assertEquals(
            SalesContract.Action.TextInputFocusChanged("debtor", false),
            actions.filterIsInstance<SalesContract.Action.TextInputFocusChanged>().last(),
        )
        composeRule.onNodeWithTag(SalesTestTags.SCANNER_RESUME).assertDoesNotExist()
        composeRule.onNodeWithTag(SalesTestTags.DEBTOR_NAME).assert(hasText("María Quispe"))
    }

    @Test
    fun creditRegistersDirectlyAndDisablesTheActionWhileSaving() {
        val actions = mutableListOf<SalesContract.Action>()
        var state by mutableStateOf(
            checkoutReadyState().copy(
                entryKind = SalesContract.EntryKind.CREDIT,
                debtorNameInput = "María Quispe",
            ),
        )
        composeRule.setContent {
            FacturaStockTheme {
                SalesScreen(
                    state = state,
                    onAction = { action ->
                        actions += action
                        if (action == SalesContract.Action.CheckoutRequested) {
                            state = state.copy(isMutating = true)
                        }
                    },
                )
            }
        }

        composeRule.onNodeWithTag(SalesTestTags.DEBTOR_NAME).assert(hasText("María Quispe"))
        composeRule.onNodeWithTag(SalesTestTags.SCREEN)
            .performScrollToNode(hasTestTag(SalesTestTags.CHECKOUT))
        composeRule.onAllNodesWithTag(SalesTestTags.CHECKOUT).assertCountEquals(1)
        composeRule.onAllNodes(isDialog()).assertCountEquals(0)
        composeRule.onNodeWithTag(SalesTestTags.CHECKOUT)
            .assert(hasText("Registrar deuda"))
            .assertIsEnabled()
            .performClick()

        composeRule.onAllNodes(isDialog()).assertCountEquals(0)
        composeRule.onNodeWithTag(SalesTestTags.CHECKOUT)
            .assertIsNotEnabled()
            .performClick()
        assertEquals(1, actions.count { it == SalesContract.Action.CheckoutRequested })
    }

    @Test
    fun manualNameSearchAnnouncesProgressAndLabelsSimilarMatches() {
        var state by mutableStateOf(
            SalesContract.State(
                entryStep = SalesContract.EntryStep.SELL,
                isLoading = false,
                mode = SalesContract.EntryMode.MANUAL,
                isNameSearchRunning = true,
                query = "Café",
            ),
        )
        composeRule.setContent {
            FacturaStockTheme {
                SalesScreen(state = state, onAction = {})
            }
        }

        composeRule.onNodeWithTag(SalesTestTags.NAME_SEARCH_PROGRESS).assertIsDisplayed()

        composeRule.runOnIdle {
            state = state.copy(
                isNameSearchRunning = false,
                productOptions = listOf(
                    option().copy(nameMatchKind = SalesContract.NameMatchKind.SIMILAR),
                ),
            )
        }
        composeRule.onNodeWithTag(SalesTestTags.SCREEN)
            .performScrollToNode(hasText("Nombre similar"))
        composeRule.onNodeWithText("Nombre similar").assertIsDisplayed()
    }

    @Test
    fun failedManualSearchShowsRetryWithoutAMisleadingEmptyState() {
        val actions = mutableListOf<SalesContract.Action>()
        composeRule.setContent {
            FacturaStockTheme {
                SalesScreen(
                    state = checkoutReadyState().copy(
                        mode = SalesContract.EntryMode.MANUAL,
                        searchFailed = true,
                    ),
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithText(
            "No se pudo buscar. El carrito no cambió.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Sin productos con stock").assertDoesNotExist()
        composeRule.onNodeWithText("Intentar de nuevo")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag(SalesTestTags.SCREEN)
            .performScrollToNode(hasTestTag(SalesTestTags.CHECKOUT))
        composeRule.onNodeWithTag(SalesTestTags.CHECKOUT)
            .assertIsEnabled()
            .performClick()

        assertEquals(
            listOf(SalesContract.Action.RetrySearch, SalesContract.Action.CheckoutRequested),
            actions,
        )
    }

    @Test
    fun failedCatalogDisablesCheckoutAndRetriesOnlyTheCatalog() {
        val actions = mutableListOf<SalesContract.Action>()
        composeRule.setContent {
            FacturaStockTheme {
                SalesScreen(
                    state = checkoutReadyState().copy(catalogLoadFailed = true),
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithTag(SalesTestTags.CATALOG_FAILURE)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Intentar de nuevo").performClick()
        composeRule.onNodeWithTag(SalesTestTags.SCREEN)
            .performScrollToNode(hasTestTag(SalesTestTags.CHECKOUT))
        composeRule.onNodeWithTag(SalesTestTags.CHECKOUT).assertIsNotEnabled()

        assertEquals(SalesContract.Action.RetryCatalog, actions.single())
    }

    @Test
    fun failedCartDisablesCheckoutAndRetriesOnlyTheCart() {
        val actions = mutableListOf<SalesContract.Action>()
        composeRule.setContent {
            FacturaStockTheme {
                SalesScreen(
                    state = checkoutReadyState().copy(cartLoadFailed = true),
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithTag(SalesTestTags.CART_FAILURE)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Intentar de nuevo").performClick()
        composeRule.onNodeWithTag(SalesTestTags.SCREEN)
            .performScrollToNode(hasTestTag(SalesTestTags.CHECKOUT))
        composeRule.onNodeWithTag(SalesTestTags.CHECKOUT).assertIsNotEnabled()

        assertEquals(SalesContract.Action.RetryCart, actions.single())
    }

    @Test
    fun scannerAndProgressCopyDescribeTheActualUiState() {
        val scannerHelp = runBlocking { getString(Res.string.sales_scanner_help) }
        val processing = runBlocking { getString(Res.string.sales_processing) }
        var state by mutableStateOf(
            SalesContract.State(
                entryStep = SalesContract.EntryStep.SELL,
                isLoading = false,
                scannerActive = true,
            ),
        )
        composeRule.setContent {
            FacturaStockTheme {
                SalesScreen(
                    state = state,
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithTag(SalesTestTags.SCREEN).performScrollToNode(hasText(scannerHelp))
        composeRule.onNodeWithText(scannerHelp).assertIsDisplayed()
        composeRule.runOnIdle {
            state = state.copy(isMutating = true, scannerActive = false)
        }
        // La tarjeta de progreso sólo entra si la mutación dura más que su espera.
        composeRule.onNodeWithContentDescription(processing).assertDoesNotExist()
        composeRule.mainClock.advanceTimeBy(MUTATION_PROGRESS_DELAY_MILLIS + 100)
        composeRule
            .onNodeWithTag(SalesTestTags.SCREEN)
            .performScrollToNode(hasContentDescription(processing))
        composeRule.onNodeWithContentDescription(processing).assertIsDisplayed()
    }

    @Test
    fun manualProductSelectionKeepsTheConcreteWarehouse() {
        val actions = mutableListOf<SalesContract.Action>()
        val option = option()
        composeRule.setContent {
            FacturaStockTheme {
                SalesScreen(
                    state = SalesContract.State(
                        entryStep = SalesContract.EntryStep.SELL,
                        isLoading = false,
                        mode = SalesContract.EntryMode.MANUAL,
                        availableProducts = listOf(option),
                    ),
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithTag(SalesTestTags.SCREEN).performScrollToNode(
            hasTestTag(SalesTestTags.option(PRODUCT_ID.value, LOCATION_ID.value)),
        )
        composeRule.onNodeWithTag(SalesTestTags.option(PRODUCT_ID.value, LOCATION_ID.value))
            .assertIsDisplayed()
            .performClick()

        assertEquals(
            SalesContract.Action.ProductSelected(PRODUCT_ID, LOCATION_ID),
            actions.last(),
        )
    }

    @Test
    fun checkoutRequiresAPricedLineAndSubmitsOnceWithoutAConfirmationDialog() {
        val actions = mutableListOf<SalesContract.Action>()
        val pending = line(price = "", total = null)
        var state by mutableStateOf(
            SalesContract.State(
                entryStep = SalesContract.EntryStep.SELL,
                isLoading = false,
                cartLines = listOf(pending),
            ),
        )
        composeRule.setContent {
            FacturaStockTheme {
                SalesScreen(
                    state = state,
                    onAction = { action ->
                        actions += action
                        if (action == SalesContract.Action.CheckoutRequested) {
                            state = state.copy(isMutating = true)
                        }
                    },
                )
            }
        }
        composeRule.onNodeWithTag(SalesTestTags.SCREEN)
            .performScrollToNode(hasTestTag(SalesTestTags.CHECKOUT))
        composeRule.onNodeWithTag(SalesTestTags.CHECKOUT)
            .assertIsNotEnabled()
            .performClick()
        assertEquals(0, actions.count { it == SalesContract.Action.CheckoutRequested })

        composeRule.runOnIdle { state = checkoutReadyState() }
        composeRule.onNodeWithTag(SalesTestTags.SCREEN)
            .performScrollToNode(hasTestTag(SalesTestTags.CHECKOUT))
        composeRule.onAllNodesWithTag(SalesTestTags.CHECKOUT).assertCountEquals(1)
        composeRule.onAllNodes(isDialog()).assertCountEquals(0)
        composeRule.onNodeWithTag(SalesTestTags.CHECKOUT)
            .assert(hasText("Concluir venta"))
            .assertIsEnabled()
            .performClick()

        composeRule.onAllNodes(isDialog()).assertCountEquals(0)
        // Si la operación se prolonga, «Procesando…» entra arriba y el botón puede salir de la vista.
        composeRule.onNodeWithTag(SalesTestTags.SCREEN)
            .performScrollToNode(hasTestTag(SalesTestTags.CHECKOUT))
        composeRule.onNodeWithTag(SalesTestTags.CHECKOUT)
            .assertIsNotEnabled()
            .performClick()
        assertEquals(1, actions.count { it == SalesContract.Action.CheckoutRequested })
    }

    @Test
    fun manualAssociationAlwaysExposesOneCancelAction() {
        val actions = mutableListOf<SalesContract.Action>()
        composeRule.setContent {
            FacturaStockTheme {
                SalesScreen(
                    state = SalesContract.State(
                        entryStep = SalesContract.EntryStep.SELL,
                        isLoading = false,
                        mode = SalesContract.EntryMode.MANUAL,
                        pendingAssociationBarcode = "001234",
                        productOptions = listOf(option()),
                    ),
                    onAction = actions::add,
                )
            }
        }

        composeRule.onAllNodesWithTag(SalesTestTags.ASSOCIATION_CANCEL).assertCountEquals(1)
        composeRule.onNodeWithTag(SalesTestTags.ASSOCIATION_CANCEL)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        assertEquals(SalesContract.Action.AssociationDismissed, actions.last())
    }

    @Test
    fun savedAssociationFailureIsAnnouncedWithoutACancelAssociationAction() {
        composeRule.setContent {
            FacturaStockTheme {
                SalesScreen(
                    state = SalesContract.State(
                        entryStep = SalesContract.EntryStep.SELL,
                        isLoading = false,
                        barcodeAssociatedWithoutCartAdd = true,
                        failure = SalesContract.Failure.STALE_CART,
                    ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithTag(SalesTestTags.SCREEN)
            .performScrollToNode(hasTestTag(SalesTestTags.ASSOCIATION_SAVED_NOTICE))
        composeRule.onNodeWithTag(SalesTestTags.ASSOCIATION_SAVED_NOTICE)
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            )
        composeRule.onNodeWithText("El código ya está asociado").assertIsDisplayed()
        composeRule.onAllNodesWithTag(SalesTestTags.ASSOCIATION_CANCEL).assertCountEquals(0)
    }

    @Test
    fun locationSelectionIsTaggedAndAnnounced() {
        composeRule.setContent {
            FacturaStockTheme {
                SalesScreen(
                    state = SalesContract.State(
                        entryStep = SalesContract.EntryStep.SELL,
                        isLoading = false,
                        mode = SalesContract.EntryMode.MANUAL,
                        pendingLocations = listOf(option()),
                        productOptions = listOf(option()),
                    ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithTag(SalesTestTags.SCREEN)
            .performScrollToNode(hasTestTag(SalesTestTags.LOCATION_SELECTION))
        composeRule.onNodeWithTag(SalesTestTags.LOCATION_SELECTION)
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            )
    }

    @Test
    fun actionableCardsAndLineControlsExposeContextualTalkBackLabels() {
        val cartLine = line(price = "3.50", total = Money.ofMinor(350, PEN))
        composeRule.setContent {
            FacturaStockTheme {
                SalesScreen(
                    state = SalesContract.State(
                        entryStep = SalesContract.EntryStep.SELL,
                        isLoading = false,
                        mode = SalesContract.EntryMode.MANUAL,
                        availableProducts = listOf(option()),
                        cartLines = listOf(cartLine),
                    ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithTag(SalesTestTags.SCREEN).performScrollToNode(
            hasTestTag(SalesTestTags.option(PRODUCT_ID.value, LOCATION_ID.value)),
        )
        composeRule
            .onNodeWithTag(SalesTestTags.option(PRODUCT_ID.value, LOCATION_ID.value))
            .assert(hasClickLabel("Agregar Café tostado del almacén Tienda al carrito"))

        composeRule.onNodeWithTag(SalesTestTags.SCREEN)
            .performScrollToNode(hasTestTag(SalesTestTags.CART))
        composeRule.onNodeWithTag(SalesTestTags.CART).assertIsDisplayed()

        listOf(
            SalesTestTags.quantityDecrease(cartLine.lineId) to "Quitar uno de Café tostado",
            SalesTestTags.quantityIncrease(cartLine.lineId) to "Agregar uno de Café tostado",
            SalesTestTags.remove(cartLine.lineId) to "Quitar Café tostado del carrito",
        ).forEach { (tag, label) ->
            composeRule.onNodeWithTag(tag)
                .performScrollTo()
                .assert(hasClickLabel(label))
        }
    }

    @Test
    fun discardReviewRequiresExplicitConfirmation() {
        val actions = mutableListOf<SalesContract.Action>()
        composeRule.setContent {
            FacturaStockTheme {
                SalesScreen(
                    state = SalesContract.State(
                        entryStep = SalesContract.EntryStep.SELL,
                        isLoading = false,
                        discardEditsReview = true,
                    ),
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithTag(SalesTestTags.DISCARD_EDITS_DIALOG).assertIsDisplayed()
        composeRule.onNodeWithText("Descartar y salir").performClick()

        assertTrue(actions.contains(SalesContract.Action.DiscardEditsConfirmed))
    }

    @Test
    fun discardReviewCanBeDismissedWithoutDiscarding() {
        val actions = mutableListOf<SalesContract.Action>()
        composeRule.setContent {
            FacturaStockTheme {
                SalesScreen(
                    state = SalesContract.State(
                        entryStep = SalesContract.EntryStep.SELL,
                        isLoading = false,
                        discardEditsReview = true,
                    ),
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithText("Cancelar").performClick()

        assertEquals(SalesContract.Action.DiscardEditsDismissed, actions.single())
    }

    /** Supplies the state responses to navigation actions; persistence is covered by VM tests. */
    private fun renderStepNavigation(
        initialState: SalesContract.State,
        actions: MutableList<SalesContract.Action>,
    ) {
        var state by mutableStateOf(initialState)
        composeRule.setContent {
            FacturaStockTheme {
                SalesScreen(
                    state = state,
                    onAction = { action ->
                        actions += action
                        state = when (action) {
                            is SalesContract.Action.EntryKindChanged -> state.copy(
                                entryKind = action.kind,
                                entryStep = SalesContract.EntryStep.SELECT_MODE,
                            )
                            is SalesContract.Action.ModeChanged -> state.copy(
                                mode = action.mode,
                                entryStep = SalesContract.EntryStep.SELL,
                            )
                            SalesContract.Action.StepBackSelected -> state.copy(
                                entryStep = when (state.entryStep) {
                                    SalesContract.EntryStep.SELL -> SalesContract.EntryStep.SELECT_MODE
                                    else -> SalesContract.EntryStep.SELECT_KIND
                                },
                            )
                            else -> state
                        }
                    },
                )
            }
        }
    }

    private fun option() = SalesContract.ProductOption(
        productId = PRODUCT_ID,
        productName = "Café tostado",
        locationId = LOCATION_ID,
        locationName = "Tienda",
        unitCode = "NIU",
        availableQuantity = BigDecimal("8"),
        sku = "CAF-01",
        barcode = "001234",
    )

    private fun line(price: String, total: Money?) = SalesContract.CartLine(
        lineId = "00000000-0000-4000-8000-0000000000c1",
        productId = PRODUCT_ID,
        productName = "Café tostado",
        locationId = LOCATION_ID,
        locationName = "Tienda",
        unitCode = "NIU",
        availableQuantity = BigDecimal("8"),
        quantityInput = "1",
        unitPriceInput = price,
        lineTotal = total,
    )

    private fun checkoutReadyState(): SalesContract.State {
        val total = Money.ofMinor(350, PEN)
        return SalesContract.State(
            entryStep = SalesContract.EntryStep.SELL,
            isLoading = false,
            cartId = "00000000-0000-4000-8000-0000000000d1",
            cartContentHash = "a".repeat(64),
            cartLines = listOf(line(price = "3.50", total = total)),
            total = total,
        )
    }

    private fun hasClickLabel(expected: String) = SemanticsMatcher(
        "has click label '$expected'",
    ) { node ->
        SemanticsActions.OnClick in node.config &&
            node.config[SemanticsActions.OnClick].label == expected
    }

    private companion object {
        val PEN = CurrencyCode.of("PEN")
        val PRODUCT_ID = ProductId.from(
            UUID.fromString("00000000-0000-4000-8000-0000000000a1"),
        )
        val LOCATION_ID = LocationId.from(
            UUID.fromString("00000000-0000-4000-8000-0000000000b1"),
        )
    }
}
