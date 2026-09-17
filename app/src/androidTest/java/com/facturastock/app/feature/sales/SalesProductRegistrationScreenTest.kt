package com.facturastock.app.feature.sales

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.ui.theme.FacturaStockTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SalesProductRegistrationScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun unknownCodeOpensRegistrationWithTheExactReadIncludingLeadingZeros() {
        val actions = mutableListOf<SalesContract.Action>()
        render(unknownState(), actions)

        scrollToTag(SalesTestTags.REGISTER_PRODUCT)
        composeRule.onNodeWithTag(SalesTestTags.REGISTER_PRODUCT).assertIsEnabled().performClick()

        assertEquals(SalesContract.Action.RegisterProductRequested(SCANNED_BARCODE), actions.single())
    }

    @Test
    fun registrationWaitsForQueuedReadsAndPendingLineWrites() {
        val actions = mutableListOf<SalesContract.Action>()
        var state by mutableStateOf(unknownState().copy(pendingBarcodeCount = 1))
        composeRule.setContent {
            FacturaStockTheme { SalesScreen(state = state, onAction = actions::add) }
        }

        scrollToTag(SalesTestTags.REGISTER_PRODUCT)
        composeRule.onNodeWithTag(SalesTestTags.REGISTER_PRODUCT).assertIsNotEnabled().performClick()
        composeRule.runOnIdle {
            state = state.copy(pendingBarcodeCount = 0, isSavingLineEdits = true)
        }
        scrollToTag(SalesTestTags.REGISTER_PRODUCT)
        composeRule.onNodeWithTag(SalesTestTags.REGISTER_PRODUCT).assertIsNotEnabled().performClick()
        assertTrue(actions.isEmpty())

        composeRule.runOnIdle { state = state.copy(isSavingLineEdits = false) }
        composeRule.onNodeWithTag(SalesTestTags.REGISTER_PRODUCT).assertIsEnabled().performClick()
        assertEquals(SalesContract.Action.RegisterProductRequested(SCANNED_BARCODE), actions.single())
    }

    @Test
    fun suggestionsKeepTheirSelectionAndAlsoOfferSeparateRegistration() {
        val actions = mutableListOf<SalesContract.Action>()
        render(unknownState(withSuggestion = true), actions)
        val suggestionTag = SalesTestTags.barcodeSuggestionAdd(PRODUCT_ID.value, LOCATION_ID.value)

        scrollToTag(suggestionTag)
        composeRule.onNodeWithTag(suggestionTag).assertIsEnabled().performClick()
        assertEquals(
            SalesContract.Action.BarcodeSuggestionSelected(PRODUCT_ID, LOCATION_ID, SCANNED_BARCODE),
            actions.single(),
        )
        scrollToTag(SalesTestTags.REGISTER_PRODUCT)
        composeRule.onNodeWithTag(SalesTestTags.REGISTER_PRODUCT).assertIsEnabled().performClick()
        assertEquals(SalesContract.Action.RegisterProductRequested(SCANNED_BARCODE), actions.last())
        assertEquals(2, actions.size)
        scrollToTag(SalesTestTags.BARCODE_SUGGESTIONS_MANUAL)
        composeRule.onNodeWithTag(SalesTestTags.BARCODE_SUGGESTIONS_MANUAL).assertIsEnabled()
    }

    @Test
    fun registrationIsNotOfferedWithoutAnUnresolvedRead() {
        render(unknownState().copy(pendingAssociationBarcode = null))

        composeRule.onNodeWithTag(SalesTestTags.REGISTER_PRODUCT).assertDoesNotExist()
    }

    @Test
    fun registrationRemainsReachableAfterSuggestionsWithLargeTextInASmallWindow() {
        val actions = mutableListOf<SalesContract.Action>()
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                FacturaStockTheme {
                    Box(Modifier.size(width = 320.dp, height = 360.dp)) {
                        SalesScreen(state = unknownState(withSuggestion = true), onAction = actions::add)
                    }
                }
            }
        }

        scrollToTag(SalesTestTags.REGISTER_PRODUCT)
        composeRule.onNodeWithTag(SalesTestTags.REGISTER_PRODUCT).assertIsEnabled().performClick()
        assertEquals(SalesContract.Action.RegisterProductRequested(SCANNED_BARCODE), actions.single())
    }

    private fun render(
        state: SalesContract.State,
        actions: MutableList<SalesContract.Action> = mutableListOf(),
    ) {
        composeRule.setContent {
            FacturaStockTheme { SalesScreen(state = state, onAction = actions::add) }
        }
    }

    private fun scrollToTag(tag: String) {
        composeRule.onNodeWithTag(SalesTestTags.SCREEN).performScrollToNode(hasTestTag(tag))
        composeRule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    }

    private fun unknownState(withSuggestion: Boolean = false): SalesContract.State {
        val product =
            SalesContract.ProductOption(
                productId = PRODUCT_ID,
                productName = "Café tostado",
                locationId = LOCATION_ID,
                locationName = "Tienda",
                unitCode = "NIU",
                availableQuantity = BigDecimal.TEN,
                sku = "CAF-01",
                barcode = "0001234567890",
            )
        return SalesContract.State(
            isLoading = false,
            entryStep = SalesContract.EntryStep.SELL,
            mode = SalesContract.EntryMode.SCANNER,
            cartId = "00000000-0000-4000-8000-0000000000c1",
            pendingAssociationBarcode = SCANNED_BARCODE,
            availableProducts = listOf(product),
            barcodeSuggestions =
                if (withSuggestion) {
                    listOf(SalesContract.BarcodeSuggestion(product, missingDigits = 1))
                } else {
                    emptyList()
                },
        )
    }

    private companion object {
        const val SCANNED_BARCODE = "001234567890"
        val PRODUCT_ID = ProductId.from(UUID.fromString("00000000-0000-4000-8000-0000000000a1"))
        val LOCATION_ID = LocationId.from(UUID.fromString("00000000-0000-4000-8000-0000000000b1"))
    }
}
