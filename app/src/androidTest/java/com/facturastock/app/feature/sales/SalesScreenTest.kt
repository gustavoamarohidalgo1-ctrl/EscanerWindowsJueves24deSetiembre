package com.facturastock.app.feature.sales

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.ui.theme.FacturaStockTheme
import java.math.BigDecimal
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SalesScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyCartShowsOneUsefulPromptWithoutPendingCheckoutControls() {
        composeRule.setContent {
            FacturaStockTheme {
                SalesScreen(
                    state = SalesContract.State(isLoading = false),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithText("Nueva venta").assertDoesNotExist()
        composeRule.onNodeWithText("Agrega un producto para comenzar.").assertIsDisplayed()
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
                        if (action is SalesContract.Action.DebtorNameChanged) {
                            state = state.copy(debtorNameInput = action.value)
                        }
                    },
                )
            }
        }

        composeRule.onNodeWithTag(SalesTestTags.DEBTOR_NAME)
            .assertIsDisplayed()
            .performTextInput("María Quispe")
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
    fun creditConfirmationNamesTheDebtorAndAmount() {
        val total = Money.ofMinor(350, PEN)
        composeRule.setContent {
            FacturaStockTheme {
                SalesScreen(
                    state = checkoutReadyState().copy(
                        entryKind = SalesContract.EntryKind.CREDIT,
                        debtorNameInput = "María Quispe",
                        checkoutReview = SalesContract.CheckoutReview(
                            cartId = "00000000-0000-4000-8000-0000000000d1",
                            version = 0L,
                            contentHash = "a".repeat(64),
                            total = total,
                            debtorName = "María Quispe",
                        ),
                    ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithTag(SalesTestTags.CHECKOUT_DIALOG).assertIsDisplayed()
        composeRule.onNodeWithText("María Quispe deberá", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("Registrar deuda").assertIsDisplayed()
    }

    @Test
    fun manualNameSearchAnnouncesProgressAndLabelsSimilarMatches() {
        var state by mutableStateOf(
            SalesContract.State(
                isLoading = false,
                mode = SalesContract.EntryMode.MANUAL,
                isNameSearchRunning = true,
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
        var state by mutableStateOf(
            SalesContract.State(
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

        composeRule.onNodeWithText(
            "Usa un lector USB o Bluetooth tipo teclado; escanea el código y presiona Enter.",
        ).assertIsDisplayed()
        composeRule.runOnIdle {
            state = state.copy(isMutating = true, scannerActive = false)
        }
        composeRule
            .onNodeWithTag(SalesTestTags.SCREEN)
            .performScrollToNode(hasContentDescription("Procesando…"))
        composeRule.onNodeWithContentDescription("Procesando…").assertIsDisplayed()
    }

    @Test
    fun manualProductSelectionKeepsTheConcreteWarehouse() {
        val actions = mutableListOf<SalesContract.Action>()
        val option = option()
        composeRule.setContent {
            FacturaStockTheme {
                SalesScreen(
                    state = SalesContract.State(
                        isLoading = false,
                        mode = SalesContract.EntryMode.MANUAL,
                        productOptions = listOf(option),
                    ),
                    onAction = actions::add,
                )
            }
        }

        composeRule
            .onNodeWithTag(SalesTestTags.option(PRODUCT_ID.value, LOCATION_ID.value))
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        assertEquals(
            SalesContract.Action.ProductSelected(PRODUCT_ID, LOCATION_ID),
            actions.last(),
        )
    }

    @Test
    fun checkoutRequiresAPricedLineAndSecondConfirmation() {
        val actions = mutableListOf<SalesContract.Action>()
        val pending = line(price = "", total = null)
        var state by mutableStateOf(
            SalesContract.State(
                isLoading = false,
                cartLines = listOf(pending),
            ),
        )
        composeRule.setContent {
            FacturaStockTheme {
                SalesScreen(
                    state = state,
                    onAction = actions::add,
                )
            }
        }
        composeRule
            .onNodeWithTag(SalesTestTags.SCREEN)
            .performScrollToNode(hasTestTag(SalesTestTags.CHECKOUT))
        composeRule.onNodeWithTag(SalesTestTags.CHECKOUT).assertIsNotEnabled()

        val total = Money.ofMinor(350, PEN)
        composeRule.runOnIdle {
            state = SalesContract.State(
                isLoading = false,
                cartId = "00000000-0000-4000-8000-0000000000d1",
                cartContentHash = "a".repeat(64),
                cartLines = listOf(line(price = "3.50", total = total)),
                total = total,
                checkoutReview = SalesContract.CheckoutReview(
                    cartId = "00000000-0000-4000-8000-0000000000d1",
                    version = 0L,
                    contentHash = "a".repeat(64),
                    total = total,
                ),
            )
        }

        composeRule.onNodeWithTag(SalesTestTags.CHECKOUT_DIALOG).assertIsDisplayed()
        composeRule.onNodeWithText("Confirmar venta").performClick()
        assertTrue(actions.contains(SalesContract.Action.CheckoutConfirmed))
    }

    @Test
    fun manualAssociationAlwaysExposesOneCancelAction() {
        val actions = mutableListOf<SalesContract.Action>()
        composeRule.setContent {
            FacturaStockTheme {
                SalesScreen(
                    state = SalesContract.State(
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
                        isLoading = false,
                        mode = SalesContract.EntryMode.MANUAL,
                        productOptions = listOf(option()),
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
