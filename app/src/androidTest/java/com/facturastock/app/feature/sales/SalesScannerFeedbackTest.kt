package com.facturastock.app.feature.sales

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.feature.common.ScannerCodeInput
import com.facturastock.app.ui.theme.FacturaStockDesign
import com.facturastock.app.ui.theme.FacturaStockTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SalesScannerFeedbackTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun savedReadShowsTheProductAccumulatedQuantityAndLocation() {
        var state by mutableStateOf(readyState())
        composeRule.setContent {
            FacturaStockTheme { SalesScannerFeedback(state) }
        }

        assertFeedback("Lector listo")
        composeRule.runOnIdle { state = state.copy(lastScanAdded = receipt()) }
        assertFeedback("Agregado: Café tostado")
        assertFeedback("En venta: 3 NIU · Tienda")

        composeRule.runOnIdle {
            state = state.copy(lastScanAdded = receipt().copy(quantity = BigDecimal("4.000"), sequence = 2))
        }
        assertFeedback("En venta: 4 NIU · Tienda")
        composeRule.onNodeWithText("En venta: 3 NIU · Tienda").assertDoesNotExist()
    }

    @Test
    fun repeatedProductShowsAlreadyPresentAndKeepsItsQuantityWithoutClaimingAnotherAddition() {
        var state by mutableStateOf(readyState().copy(lastScanAdded = receipt()))
        composeRule.setContent {
            FacturaStockTheme { SalesScannerFeedback(state) }
        }

        assertFeedback("Agregado: Café tostado")
        assertFeedback("En venta: 3 NIU · Tienda")
        composeRule.runOnIdle {
            state = state.copy(lastScanAdded = receipt().copy(alreadyInCart = true, sequence = 2))
        }

        assertFeedback("Ya está en la venta: Café tostado")
        assertFeedback("En venta: 3 NIU · Tienda")
        composeRule.onNodeWithText("Agregado: Café tostado").assertDoesNotExist()
        composeRule.onNodeWithText("En venta: 4 NIU · Tienda").assertDoesNotExist()
    }

    @Test
    fun unsuccessfulReadDoesNotShowThePreviousSuccessAsItsResult() {
        render(readyState().copy(lastScanAdded = receipt(), failure = SalesContract.Failure.INSUFFICIENT_STOCK))

        assertFeedback("Revisa la lectura")
        composeRule.onNodeWithText("Agregado: Café tostado").assertDoesNotExist()
    }

    @Test
    fun queuedReadsRemainVisibleWhileThePreviousReceiptExists() {
        render(
            readyState().copy(
                lastScanAdded = receipt(),
                pendingBarcodeCount = 4,
                isProcessingBarcode = true,
                isMutating = true,
            ),
        )

        assertFeedback("Procesando lecturas: 4")
        assertFeedback("Cada producto se agrega una sola vez.")
        composeRule.onNodeWithText("Agregado: Café tostado").assertDoesNotExist()
    }

    @Test
    fun failedReadTakesPriorityOverTheQueueAndPreviousReceipt() {
        render(
            readyState().copy(
                lastScanAdded = receipt(),
                pendingBarcodeCount = 4,
                scannerFailure = SalesContract.ScannerFailure.QUEUE_FULL,
            ),
        )

        assertFeedback("Revisa la lectura")
        assertFeedback("Espera y vuelve a escanear el último producto.")
        composeRule.onNodeWithText("Procesando lecturas: 4").assertDoesNotExist()
    }

    @Test
    fun unresolvedProductClearlySaysItHasNotBeenAdded() {
        render(
            readyState().copy(
                lastScanAdded = receipt(),
                pendingBarcodeCount = 2,
                pendingAssociationBarcode = "012345678",
            ),
        )

        assertFeedback("Código no encontrado")
        assertFeedback("Esta lectura aún no se agregó a la venta.")
        composeRule.onNodeWithText("Agregado: Café tostado").assertDoesNotExist()
    }

    @Test
    fun editingPausesFeedbackAndOffersAnExplicitResumeAction() {
        render(readyState().copy(lastScanAdded = receipt(), isTextInputFocused = true))

        assertFeedback("Lector en pausa")
        composeRule.onNodeWithTag(SalesTestTags.SCANNER_RESUME).assertIsDisplayed()
        composeRule.onNodeWithText("Agregado: Café tostado").assertDoesNotExist()
    }

    @Test
    fun compactPinnedFeedbackLeavesTheCartReachableWithLargeText() {
        val longProductName = "Café tostado de origen con una descripción extensa para identificar el producto"
        val state =
            readyState().copy(
                lastScanAdded = receipt().copy(productName = longProductName),
                cartLines = listOf(cartLine()),
                total = Money.ofMinor(1050, CurrencyCode.of("PEN")),
            )
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                FacturaStockTheme {
                    Box(Modifier.size(width = 320.dp, height = 360.dp)) {
                        Column(Modifier.fillMaxSize()) {
                            ScannerCodeInput(
                                enabled = false,
                                onCode = {},
                                modifier = Modifier.padding(FacturaStockDesign.spacing.md),
                            )
                            SalesScannerFeedback(state)
                            SalesScreen(
                                state = state,
                                onAction = {},
                                modifier = Modifier.weight(1f),
                                showScannerStatus = false,
                            )
                        }
                    }
                }
            }
        }

        assertFeedback("Agregado: $longProductName")
        assertFeedback("En venta: 3 NIU · Tienda")
        composeRule
            .onNodeWithTag(SalesTestTags.SCREEN)
            .performScrollToNode(hasTestTag(SalesTestTags.CHECKOUT))
        composeRule.onNodeWithTag(SalesTestTags.CHECKOUT).assertIsDisplayed()
        composeRule.onNodeWithTag(SalesTestTags.SCANNER_FEEDBACK).assertIsDisplayed()
        composeRule.onNodeWithTag(SalesTestTags.SCANNER_STATUS).assertDoesNotExist()
    }

    @Test
    fun queuedBarcodeDoesNotInsertTheGenericFullSizeLoadingCard() {
        composeRule.setContent {
            FacturaStockTheme {
                SalesScreen(
                    state = readyState().copy(isMutating = true, isProcessingBarcode = true, pendingBarcodeCount = 2),
                    onAction = {},
                    showScannerStatus = false,
                )
            }
        }

        composeRule.onNodeWithText("Procesando…").assertDoesNotExist()
        composeRule.onNodeWithTag(SalesTestTags.CART).assertIsDisplayed()
    }

    private fun render(state: SalesContract.State) {
        composeRule.setContent {
            FacturaStockTheme { SalesScannerFeedback(state) }
        }
    }

    private fun assertFeedback(text: String) {
        composeRule.onNodeWithTag(SalesTestTags.SCANNER_FEEDBACK).assertIsDisplayed().assert(hasText(text))
    }

    private fun readyState() =
        SalesContract.State(
            isLoading = false,
            entryStep = SalesContract.EntryStep.SELL,
            mode = SalesContract.EntryMode.SCANNER,
            scannerActive = true,
        )

    private fun receipt() =
        SalesContract.LastScanAdded(
            productId = PRODUCT_ID,
            locationId = LOCATION_ID,
            productName = "Café tostado",
            locationName = "Tienda",
            quantity = BigDecimal("3"),
            unitCode = "NIU",
            sequence = 1,
        )

    private fun cartLine() =
        SalesContract.CartLine(
            lineId = "00000000-0000-4000-8000-0000000000c1",
            productId = PRODUCT_ID,
            productName = "Café tostado",
            locationId = LOCATION_ID,
            locationName = "Tienda",
            unitCode = "NIU",
            availableQuantity = BigDecimal.TEN,
            quantityInput = "3",
            unitPriceInput = "3.50",
            lineTotal = Money.ofMinor(1050, CurrencyCode.of("PEN")),
        )

    private companion object {
        val PRODUCT_ID = ProductId.from(UUID.fromString("00000000-0000-4000-8000-0000000000a1"))
        val LOCATION_ID = LocationId.from(UUID.fromString("00000000-0000-4000-8000-0000000000b1"))
    }
}
