package com.facturastock.app.feature.sales

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.ui.theme.FacturaStockTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal
import java.util.UUID

class BarcodeSuggestionsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun suggestionsShowReadAndSavedCodesDifferencesAndStockBeforeManualAssociation() {
        render(suggestionState())

        scrollToText("Códigos parecidos")
        scrollToText("Código leído: $SCANNED_BARCODE")
        scrollToText("Café tostado")
        scrollToText("Código guardado: 01234567890")
        scrollToText("1 dígito de diferencia")
        scrollToText("8 NIU · Tienda")
        scrollToText("Chocolate")
        scrollToText("Código guardado: 0012345678900")
        scrollToText("3 dígitos de diferencia")
        scrollToText("4 NIU · Almacén")
        scrollToText("Si ninguno corresponde, vuelve a escanear o busca por nombre.")
        composeRule.onNodeWithTag(SalesTestTags.SEARCH).assertDoesNotExist()
        composeRule
            .onNodeWithTag(SalesTestTags.option(PRODUCT_ID.value, LOCATION_ID.value))
            .assertDoesNotExist()
    }

    @Test
    fun singleSellableSuggestionExplainsThatUnavailableCodesCanStillCompete() {
        val state = suggestionState()
        render(
            state.copy(
                barcodeSuggestions = state.barcodeSuggestions.take(1),
                barcodeSelectionReason = SalesContract.BarcodeSelectionReason.AMBIGUOUS,
            ),
        )

        scrollToText(
            "La lectura coincide con varios códigos del catálogo, incluidos productos agotados o archivados. " +
                "Aquí aparecen las opciones disponibles para vender. Comprueba el producto y su código antes de agregarlo.",
        )
        scrollToTag(SalesTestTags.barcodeSuggestionAdd(PRODUCT_ID.value, LOCATION_ID.value))
        composeRule.onNodeWithTag(SalesTestTags.barcodeSuggestionAdd(PRODUCT_ID.value, LOCATION_ID.value)).assertIsEnabled()
    }

    @Test
    fun changedCatalogExplainsThePendingReadEvenWithoutSuggestions() {
        render(
            suggestionState().copy(
                barcodeSuggestions = emptyList(),
                barcodeSelectionReason = SalesContract.BarcodeSelectionReason.CATALOG_CHANGED,
            ),
        )

        scrollToText(
            "El catálogo cambió durante la lectura. Comprueba el producto y su código antes de agregarlo, o vuelve a escanear.",
        )
    }

    @Test
    fun exactRegisteredCandidateIsNotDescribedAsMissingDigits() {
        val state = suggestionState()
        val exact =
            state.barcodeSuggestions.first().copy(
                product =
                    state.barcodeSuggestions
                        .first()
                        .product
                        .copy(barcode = SCANNED_BARCODE),
                missingDigits = 0,
            )
        render(
            state.copy(
                barcodeSuggestions = listOf(exact) + state.barcodeSuggestions.drop(1),
                barcodeSelectionReason = SalesContract.BarcodeSelectionReason.AMBIGUOUS,
            ),
        )

        scrollToText("Código guardado: $SCANNED_BARCODE")
        scrollToText("Código exacto registrado")
        composeRule.onNodeWithText("0 dígitos de diferencia").assertDoesNotExist()
    }

    @Test
    fun recoveredWarehouseSelectionShowsProductAndSavedCodeBeforeChoosing() {
        val actions = mutableListOf<SalesContract.Action>()
        val first = suggestionState().barcodeSuggestions.first().product
        val second = first.copy(locationId = SECOND_LOCATION_ID, locationName = "Almacén")
        render(
            suggestionState().copy(
                pendingAssociationBarcode = null,
                barcodeSuggestions = emptyList(),
                pendingLocations = listOf(first, second),
                pendingRecoveredBarcode = SCANNED_BARCODE,
            ),
            actions,
        )

        scrollToText("Café tostado")
        scrollToText("Código guardado: 01234567890")
        scrollToText("Tienda · 8 NIU disponibles")
        composeRule.onNodeWithText("Tienda · 8 NIU disponibles").performClick()
        assertEquals(SalesContract.Action.LocationSelected(PRODUCT_ID, LOCATION_ID), actions.last())
    }

    @Test
    fun choosingSuggestionAddsWithTheReadCodeSnapshotWithoutSelectingAnAssociation() {
        val actions = mutableListOf<SalesContract.Action>()
        render(suggestionState(), actions)

        val addTag = SalesTestTags.barcodeSuggestionAdd(PRODUCT_ID.value, LOCATION_ID.value)
        scrollToTag(addTag)
        composeRule.onNodeWithTag(addTag).assertIsEnabled().performClick()

        assertEquals(
            SalesContract.Action.BarcodeSuggestionSelected(
                productId = PRODUCT_ID,
                locationId = LOCATION_ID,
                scannedBarcode = SCANNED_BARCODE,
            ),
            actions.last(),
        )
        assertFalse(actions.any { it is SalesContract.Action.ProductSelected })
        composeRule.onNodeWithTag(SalesTestTags.REPLACEMENT_DIALOG).assertDoesNotExist()
    }

    @Test
    fun mutationDisablesSuggestionAndManualAssociationActions() {
        render(suggestionState().copy(isMutating = true))

        val addTag = SalesTestTags.barcodeSuggestionAdd(PRODUCT_ID.value, LOCATION_ID.value)
        scrollToTag(addTag)
        composeRule.onNodeWithTag(addTag).assertIsNotEnabled()
        scrollToTag(SalesTestTags.BARCODE_SUGGESTIONS_MANUAL)
        composeRule.onNodeWithTag(SalesTestTags.BARCODE_SUGGESTIONS_MANUAL).assertIsNotEnabled()
        scrollToTag(SalesTestTags.BARCODE_SUGGESTIONS_RESCAN)
        composeRule.onNodeWithTag(SalesTestTags.BARCODE_SUGGESTIONS_RESCAN).assertIsNotEnabled()
    }

    @Test
    fun unsavedLineEditsDisableSuggestionSelection() {
        render(suggestionState().copy(hasPendingEdits = true))

        val addTag = SalesTestTags.barcodeSuggestionAdd(PRODUCT_ID.value, LOCATION_ID.value)
        scrollToTag(addTag)
        composeRule.onNodeWithTag(addTag).assertIsNotEnabled()
    }

    @Test
    fun manualAssociationRequiresOpeningItsOwnSectionWhenSuggestionsExist() {
        val actions = mutableListOf<SalesContract.Action>()
        render(suggestionState(), actions)

        composeRule.onNodeWithTag(SalesTestTags.SEARCH).assertDoesNotExist()
        scrollToTag(SalesTestTags.BARCODE_SUGGESTIONS_MANUAL)
        composeRule.onNodeWithTag(SalesTestTags.BARCODE_SUGGESTIONS_MANUAL).performClick()

        scrollToText("Asociar código a un producto recibido")
        scrollToText(
            "Verifica primero que el código leído esté completo. Esta opción guardará ese código " +
                "en el producto al confirmar la asociación.",
        )
        scrollToTag(SalesTestTags.SEARCH)
        val optionTag = SalesTestTags.option(PRODUCT_ID.value, LOCATION_ID.value)
        scrollToTag(optionTag)
        composeRule.onNodeWithTag(optionTag).performClick()

        assertEquals(SalesContract.Action.ProductSelected(PRODUCT_ID, LOCATION_ID), actions.last())
    }

    @Test
    fun unknownCodeWithoutSuggestionsKeepsTheExistingSearchAndAssociationFlow() {
        val actions = mutableListOf<SalesContract.Action>()
        render(suggestionState().copy(barcodeSuggestions = emptyList()), actions)

        composeRule.onNodeWithTag(SalesTestTags.BARCODE_SUGGESTIONS).assertDoesNotExist()
        composeRule.onNodeWithTag(SalesTestTags.BARCODE_SUGGESTIONS_MANUAL).assertDoesNotExist()
        scrollToText("Asociar código a un producto recibido")
        scrollToTag(SalesTestTags.SEARCH)
        val optionTag = SalesTestTags.option(PRODUCT_ID.value, LOCATION_ID.value)
        scrollToTag(optionTag)
        composeRule.onNodeWithTag(optionTag).performClick()

        assertEquals(SalesContract.Action.ProductSelected(PRODUCT_ID, LOCATION_ID), actions.last())
    }

    @Test
    fun rescanAndCancelDismissThePendingRead() {
        val actions = mutableListOf<SalesContract.Action>()
        render(suggestionState(), actions)

        scrollToTag(SalesTestTags.BARCODE_SUGGESTIONS_RESCAN)
        composeRule.onNodeWithTag(SalesTestTags.BARCODE_SUGGESTIONS_RESCAN).performClick()
        assertEquals(SalesContract.Action.AssociationDismissed, actions.last())

        scrollToTag(SalesTestTags.ASSOCIATION_CANCEL)
        composeRule.onNodeWithTag(SalesTestTags.ASSOCIATION_CANCEL).performClick()
        assertEquals(2, actions.count { it == SalesContract.Action.AssociationDismissed })
    }

    @Test
    fun suggestionsRequireAnUnresolvedRead() {
        render(suggestionState().copy(pendingAssociationBarcode = null))

        composeRule.onNodeWithTag(SalesTestTags.BARCODE_SUGGESTIONS).assertDoesNotExist()
        composeRule.onNodeWithTag(SalesTestTags.BARCODE_SUGGESTIONS_MANUAL).assertDoesNotExist()
        composeRule.onNodeWithTag(SalesTestTags.SEARCH).assertDoesNotExist()
    }

    @Test
    fun suggestionButtonRemainsReachableWithLargeTextInASmallWindow() {
        val actions = mutableListOf<SalesContract.Action>()
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                FacturaStockTheme {
                    Box(Modifier.size(width = 320.dp, height = 360.dp)) {
                        SalesScreen(state = suggestionState(), onAction = actions::add)
                    }
                }
            }
        }

        val addTag = SalesTestTags.barcodeSuggestionAdd(SECOND_PRODUCT_ID.value, SECOND_LOCATION_ID.value)
        scrollToTag(addTag)
        composeRule.onNodeWithTag(addTag).assertIsEnabled().performClick()

        assertEquals(
            SalesContract.Action.BarcodeSuggestionSelected(
                productId = SECOND_PRODUCT_ID,
                locationId = SECOND_LOCATION_ID,
                scannedBarcode = SCANNED_BARCODE,
            ),
            actions.last(),
        )
    }

    private fun render(
        state: SalesContract.State,
        actions: MutableList<SalesContract.Action> = mutableListOf(),
    ) {
        composeRule.setContent {
            FacturaStockTheme {
                SalesScreen(state = state, onAction = actions::add)
            }
        }
    }

    private fun scrollToText(text: String) {
        composeRule.onNodeWithTag(SalesTestTags.SCREEN).performScrollToNode(hasText(text))
        composeRule.onNodeWithText(text).performScrollTo().assertIsDisplayed()
    }

    private fun scrollToTag(tag: String) {
        composeRule.onNodeWithTag(SalesTestTags.SCREEN).performScrollToNode(hasTestTag(tag))
        composeRule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    }

    private fun suggestionState(): SalesContract.State {
        val first =
            SalesContract.ProductOption(
                productId = PRODUCT_ID,
                productName = "Café tostado",
                locationId = LOCATION_ID,
                locationName = "Tienda",
                unitCode = "NIU",
                availableQuantity = BigDecimal("8"),
                sku = "CAF-01",
                barcode = "01234567890",
            )
        val second =
            first.copy(
                productId = SECOND_PRODUCT_ID,
                productName = "Chocolate",
                locationId = SECOND_LOCATION_ID,
                locationName = "Almacén",
                availableQuantity = BigDecimal("4"),
                sku = "CHO-01",
                barcode = "0012345678900",
            )
        return SalesContract.State(
            isLoading = false,
            entryStep = SalesContract.EntryStep.SELL,
            mode = SalesContract.EntryMode.SCANNER,
            availableProducts = listOf(first, second),
            pendingAssociationBarcode = SCANNED_BARCODE,
            barcodeSuggestions =
                listOf(
                    SalesContract.BarcodeSuggestion(first, missingDigits = 1),
                    SalesContract.BarcodeSuggestion(second, missingDigits = 3),
                ),
        )
    }

    private companion object {
        const val SCANNED_BARCODE = "1234567890"
        val PRODUCT_ID = ProductId.from(UUID.fromString("00000000-0000-4000-8000-0000000000a1"))
        val SECOND_PRODUCT_ID = ProductId.from(UUID.fromString("00000000-0000-4000-8000-0000000000a2"))
        val LOCATION_ID = LocationId.from(UUID.fromString("00000000-0000-4000-8000-0000000000b1"))
        val SECOND_LOCATION_ID = LocationId.from(UUID.fromString("00000000-0000-4000-8000-0000000000b2"))
    }
}
