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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.feature.common.ScannerCodeInput
import com.facturastock.app.ui.theme.FacturaStockDesign
import com.facturastock.app.ui.theme.FacturaStockTheme
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal
import java.util.UUID

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
    fun recoveredReadIsExplicitAndNeverPresentedAsTheResultOfAnotherRead() {
        val recoveryNotice = "Código recuperado: 77511111111"
        var state by mutableStateOf(
            readyState().copy(lastScanAdded = receipt().copy(recoveredFromBarcode = "77511111111")),
        )
        composeRule.setContent {
            FacturaStockTheme { SalesScannerFeedback(state) }
        }

        assertFeedback("Recuperado: Café tostado")
        assertFeedback("En venta: 3 NIU · Tienda")
        assertRecoveryDescription(recoveryNotice)

        composeRule.runOnIdle {
            state = state.copy(lastScanAdded = requireNotNull(state.lastScanAdded).copy(alreadyInCart = true))
        }
        assertFeedback("Ya en venta (recuperado): Café tostado")
        assertRecoveryDescription(recoveryNotice)

        composeRule.runOnIdle { state = state.copy(pendingBarcodeCount = 1) }
        assertFeedback("Procesando lecturas: 1")
        assertRecoveryDescription(null)

        composeRule.runOnIdle {
            state = state.copy(pendingBarcodeCount = 0, failure = SalesContract.Failure.PRODUCT_UNAVAILABLE)
        }
        assertFeedback("Revisa la lectura")
        assertRecoveryDescription(null)

        composeRule.runOnIdle { state = readyState().copy(lastScanAdded = receipt()) }
        assertFeedback("Agregado: Café tostado")
        assertRecoveryDescription(null)
    }

    @Test
    fun recoveredWarehouseSelectionKeepsTheProductAndOriginalReadVisibleUntilSaved() {
        var state by mutableStateOf(
            readyState().copy(
                pendingLocations = listOf(productOption()),
                pendingRecoveredBarcode = "77511111111",
                lastScanAdded = receipt().copy(productName = "Producto anterior"),
            ),
        )
        composeRule.setContent {
            FacturaStockTheme { SalesScannerFeedback(state) }
        }

        assertFeedback("Recuperado: Café tostado")
        assertFeedback("Elige un almacén; aún no se agregó.")
        assertRecoveryDescription("Código recuperado: 77511111111")
        composeRule.onNodeWithText("Agregado: Producto anterior").assertDoesNotExist()
        composeRule.onNodeWithText("En venta: 3 NIU · Tienda").assertDoesNotExist()

        composeRule.runOnIdle { state = state.copy(failure = SalesContract.Failure.PRODUCT_UNAVAILABLE) }
        assertFeedback("Revisa la lectura")
        assertRecoveryDescription(null)

        composeRule.runOnIdle {
            state = readyState().copy(lastScanAdded = receipt().copy(recoveredFromBarcode = "77511111111"))
        }
        assertFeedback("Recuperado: Café tostado")
        assertFeedback("En venta: 3 NIU · Tienda")
        assertRecoveryDescription("Código recuperado: 77511111111")
    }

    @Test
    fun selectionReasonExplainsWhyASingleSellableSuggestionNeedsConfirmation() {
        var state by mutableStateOf(
            readyState().copy(
                pendingAssociationBarcode = "77511111111",
                barcodeSuggestions = listOf(SalesContract.BarcodeSuggestion(productOption(), 2)),
                barcodeSelectionReason = SalesContract.BarcodeSelectionReason.AMBIGUOUS,
                lastScanAdded = receipt(),
            ),
        )
        composeRule.setContent {
            FacturaStockTheme { SalesScannerFeedback(state) }
        }

        assertFeedback("Confirma el producto")
        assertFeedback("La lectura coincide con otros códigos.")
        assertRecoveryDescription(null)

        composeRule.runOnIdle { state = state.copy(barcodeSelectionReason = SalesContract.BarcodeSelectionReason.CATALOG_CHANGED) }
        assertFeedback("Revisa el producto")
        assertFeedback("El catálogo cambió durante la lectura.")

        composeRule.runOnIdle { state = state.copy(barcodeSelectionReason = null) }
        assertFeedback("Elige el producto correcto")
        assertFeedback("Esta lectura aún no se agregó a la venta.")
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
                lastScanAdded = receipt().copy(productName = longProductName, recoveredFromBarcode = "77511111111"),
                cartLines = listOf(cartLine()),
                total = Money.ofMinor(1050, CurrencyCode.of("PEN")),
            )
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                FacturaStockTheme {
                    Box(Modifier.size(width = 320.dp, height = 360.dp)) {
                        Column(Modifier.fillMaxSize()) {
                            // En Android este campo era un EditText (AndroidView) que no heredaba
                            // el fontScale del LocalDensity de Compose; en escritorio es Compose y
                            // su marcador de posición se partiría en varias líneas. Se conserva
                            // la escala base para reproducir el mismo encabezado fijo.
                            CompositionLocalProvider(LocalDensity provides density) {
                                ScannerCodeInput(
                                    enabled = false,
                                    onCode = {},
                                    modifier = Modifier.padding(FacturaStockDesign.spacing.md),
                                )
                            }
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

        assertFeedback("Recuperado: $longProductName")
        assertFeedback("En venta: 3 NIU · Tienda")
        assertRecoveryDescription("Código recuperado: 77511111111")
        composeRule
            .onNodeWithTag(SalesTestTags.SCREEN)
            .assertIsDisplayed()
            .performScrollToNode(hasTestTag(SalesTestTags.CHECKOUT))
        composeRule.onNodeWithTag(SalesTestTags.CHECKOUT).assertIsDisplayed()
        composeRule.onNodeWithTag(SalesTestTags.SCANNER_FEEDBACK).assertIsDisplayed()
        composeRule.onNodeWithTag(SalesTestTags.SCANNER_STATUS).assertDoesNotExist()
    }

    @Test
    fun compactSelectionFeedbackKeepsTheCartReachableWithLargeText() {
        val base =
            readyState().copy(
                cartLines = listOf(cartLine()),
                total = Money.ofMinor(1050, CurrencyCode.of("PEN")),
            )
        val option = productOption().copy(productName = "Café tostado con una descripción extensa para identificar el producto")
        val selections =
            listOf(
                base.copy(pendingLocations = listOf(option), pendingRecoveredBarcode = "77511111111") to
                    "Recuperado: ${option.productName}",
                base.copy(
                    pendingAssociationBarcode = "77511111111",
                    barcodeSuggestions = listOf(SalesContract.BarcodeSuggestion(option, 2)),
                    barcodeSelectionReason = SalesContract.BarcodeSelectionReason.AMBIGUOUS,
                ) to "Confirma el producto",
                base.copy(
                    pendingAssociationBarcode = "77511111111",
                    barcodeSelectionReason = SalesContract.BarcodeSelectionReason.CATALOG_CHANGED,
                ) to "Revisa el producto",
            )
        var state by mutableStateOf(selections.first().first)
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                FacturaStockTheme {
                    Box(Modifier.size(width = 320.dp, height = 360.dp)) {
                        Column(Modifier.fillMaxSize()) {
                            // En Android este campo era un EditText (AndroidView) que no heredaba
                            // el fontScale del LocalDensity de Compose; en escritorio es Compose y
                            // su marcador de posición se partiría en varias líneas. Se conserva
                            // la escala base para reproducir el mismo encabezado fijo.
                            CompositionLocalProvider(LocalDensity provides density) {
                                ScannerCodeInput(
                                    enabled = false,
                                    onCode = {},
                                    modifier = Modifier.padding(FacturaStockDesign.spacing.md),
                                )
                            }
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

        selections.forEach { (selection, title) ->
            composeRule.runOnIdle { state = selection }
            assertFeedback(title)
            composeRule
                .onNodeWithTag(SalesTestTags.SCREEN)
                .assertIsDisplayed()
                .performScrollToNode(hasTestTag(SalesTestTags.CHECKOUT))
            composeRule.onNodeWithTag(SalesTestTags.CHECKOUT).assertIsDisplayed()
            composeRule.onNodeWithTag(SalesTestTags.SCANNER_FEEDBACK).assertIsDisplayed()
        }
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

    private fun assertRecoveryDescription(expected: String?) {
        val matcher =
            if (expected == null) {
                SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription)
            } else {
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expected)
            }
        composeRule.onNodeWithTag(SalesTestTags.SCANNER_FEEDBACK).assert(matcher)
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

    private fun productOption() =
        SalesContract.ProductOption(
            productId = PRODUCT_ID,
            productName = "Café tostado",
            locationId = LOCATION_ID,
            locationName = "Tienda",
            unitCode = "NIU",
            availableQuantity = BigDecimal.TEN,
            sku = "CAF-01",
            barcode = "7751111111111",
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
