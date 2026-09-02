package com.facturastock.app.feature.inventory

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.ExactMonetaryAmount
import com.facturastock.app.domain.model.InventoryCostAmount
import com.facturastock.app.domain.model.InventoryDataAlert
import com.facturastock.app.domain.model.InventoryProductDetail
import com.facturastock.app.domain.model.InventoryReadItem
import com.facturastock.app.domain.model.InventoryReadMovement
import com.facturastock.app.domain.model.InventoryReadPosition
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.ProductProfit
import com.facturastock.app.domain.model.ProductProfitStatus
import com.facturastock.app.domain.model.StockMovementType
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.ui.theme.FacturaStockTheme
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InventoryScreensTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun listShowsSearchInventorySummaryAndAlertsAndDispatchesActions() {
        val item = inventoryItem()
        val openedProducts = mutableListOf<ProductId>()
        var diagnosticRuns = 0
        var lastQuery = ""

        composeRule.setContent {
            var query by remember { mutableStateOf("") }
            FacturaStockTheme {
                InventoryListScreen(
                    items = listOf(item),
                    query = query,
                    diagnosticReport = null,
                    isLoading = false,
                    isDiagnosing = false,
                    diagnosticFailed = false,
                    onQueryChange = {
                        query = it
                        lastQuery = it
                    },
                    onProductClick = openedProducts::add,
                    onRunDiagnostic = { diagnosticRuns++ },
                )
            }
        }

        composeRule.onNodeWithTag(InventoryTestTags.SEARCH)
            .assertIsDisplayed()
            .performTextInput("café")
        composeRule.onNodeWithTag(InventoryTestTags.SEARCH)
            .assertTextContains("café", substring = true)
        composeRule.runOnIdle { assertEquals("café", lastQuery) }

        composeRule.onNodeWithTag(InventoryTestTags.RUN_DIAGNOSTIC)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag(InventoryTestTags.product(item.productId))
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        composeRule.onNodeWithText("Existencia total: 8 und")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("2 almacenes")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Costo promedio total: No disponible")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Valor estimado total: PEN 37.5")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("La existencia es negativa.")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("El promedio agregado no tiene una interpretación válida.")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Revisar datos de inventario")
            .performScrollTo()
            .assertIsDisplayed()

        composeRule.runOnIdle {
            assertEquals(listOf(item.productId), openedProducts)
            assertEquals(1, diagnosticRuns)
        }
    }

    @Test
    fun normalInventoryHidesDiagnosticAndZeroCountButFailureKeepsRetryAccessible() {
        var diagnosticFailed by mutableStateOf(false)
        var section by mutableStateOf(InventoryContract.ListSection.STOCK)
        var diagnosticRuns = 0

        composeRule.setContent {
            FacturaStockTheme {
                InventoryListScreen(
                    items = emptyList(),
                    query = "",
                    diagnosticReport = null,
                    isLoading = false,
                    isDiagnosing = false,
                    diagnosticFailed = diagnosticFailed,
                    onQueryChange = {},
                    onProductClick = {},
                    onRunDiagnostic = { diagnosticRuns++ },
                    section = section,
                    onSectionChange = { section = it },
                )
            }
        }

        composeRule.onNodeWithTag(InventoryTestTags.DIAGNOSTIC).assertDoesNotExist()
        composeRule.onNodeWithTag(InventoryTestTags.RUN_DIAGNOSTIC).assertDoesNotExist()
        composeRule.onNodeWithText("Inventario").assertDoesNotExist()
        composeRule.onNodeWithText("0 productos con existencias").assertDoesNotExist()
        composeRule.onNodeWithText("Inventario por preparar")
            .performScrollTo()
            .assertIsDisplayed()

        composeRule.runOnIdle { diagnosticFailed = true }
        composeRule.onNodeWithTag(InventoryTestTags.DIAGNOSTIC)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(InventoryTestTags.RUN_DIAGNOSTIC)
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, diagnosticRuns) }

        composeRule.onNodeWithTag(InventoryTestTags.SECTION_PROFIT).performClick()
        composeRule.onNodeWithText("0 productos en ganancias").assertDoesNotExist()
        composeRule.onAllNodesWithText("Ganancias").assertCountEquals(1)
        composeRule.onNodeWithText("Sin productos para mostrar")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun scannerModeShowsAccessibleLiveStatusAndEveryRecoverableResult() {
        var inputMode by mutableStateOf(InventoryContract.InputMode.SEARCH)
        var scannerActive by mutableStateOf(true)
        var lookupRunning by mutableStateOf(false)
        var scannerFailure by mutableStateOf<InventoryContract.ScannerFailure?>(null)

        composeRule.setContent {
            FacturaStockTheme {
                InventoryListScreen(
                    items = listOf(inventoryItem()),
                    query = "",
                    diagnosticReport = null,
                    isLoading = false,
                    isDiagnosing = false,
                    diagnosticFailed = false,
                    onQueryChange = {},
                    onProductClick = {},
                    onRunDiagnostic = {},
                    inputMode = inputMode,
                    scannerActive = scannerActive,
                    isBarcodeLookupRunning = lookupRunning,
                    scannerFailure = scannerFailure,
                    onInputModeChange = { inputMode = it },
                )
            }
        }

        composeRule.onNodeWithTag(InventoryTestTags.SEARCH_MODE)
            .assertIsDisplayed()
            .assertIsSelected()
        composeRule.onNodeWithTag(InventoryTestTags.SEARCH).assertIsDisplayed()
        composeRule.onNodeWithTag(InventoryTestTags.SCANNER_STATUS).assertDoesNotExist()

        composeRule.onNodeWithTag(InventoryTestTags.SCANNER_MODE)
            .assertIsDisplayed()
            .performClick()
            .assertIsSelected()
        composeRule.onNodeWithTag(InventoryTestTags.SEARCH).assertDoesNotExist()
        composeRule.onNodeWithTag(InventoryTestTags.SCANNER_STATUS)
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            )
        composeRule.onNodeWithText(
            "Lector listo. Escanea el código del producto y presiona Enter.",
        ).assertIsDisplayed()

        composeRule.runOnIdle { lookupRunning = true }
        composeRule.onNodeWithText("Buscando el producto…").assertIsDisplayed()

        composeRule.runOnIdle {
            lookupRunning = false
            scannerFailure = InventoryContract.ScannerFailure.INVALID_BARCODE
        }
        composeRule.onNodeWithText(
            "El código debe tener entre 1 y 128 caracteres ASCII visibles.",
        ).assertIsDisplayed()

        composeRule.runOnIdle {
            scannerFailure = InventoryContract.ScannerFailure.BARCODE_NOT_FOUND
        }
        composeRule.onNodeWithText(
            "El código no está asociado a ningún producto de este negocio.",
        ).assertIsDisplayed()

        composeRule.runOnIdle {
            scannerFailure = InventoryContract.ScannerFailure.NO_ACTIVE_BUSINESS
        }
        composeRule.onNodeWithText(
            "Configura un negocio antes de buscar productos.",
        ).assertIsDisplayed()

        composeRule.runOnIdle {
            scannerFailure = InventoryContract.ScannerFailure.LOOKUP_FAILED
        }
        composeRule.onNodeWithText(
            "No se pudo buscar el producto. Vuelve a escanear el código.",
        ).assertIsDisplayed()

        composeRule.runOnIdle {
            scannerFailure = null
            scannerActive = false
        }
        composeRule.onNodeWithText(
            "Escáner en pausa. Conecta o activa un lector USB o Bluetooth tipo teclado.",
        ).assertIsDisplayed()

        composeRule.onNodeWithTag(InventoryTestTags.SEARCH_MODE).performClick()
        composeRule.onNodeWithTag(InventoryTestTags.SEARCH_MODE).assertIsSelected()
        composeRule.onNodeWithTag(InventoryTestTags.SEARCH).assertIsDisplayed()
        composeRule.onNodeWithTag(InventoryTestTags.SCANNER_STATUS).assertDoesNotExist()
    }

    @Test
    fun detailKeepsChronologicalMovementsOpensPurchaseAndDoesNotLinkAdjustment() {
        val item = inventoryItem()
        val purchaseId = PurchaseId.from(uuid(20))
        val purchaseMovement = InventoryReadMovement(
            movementId = "movement-purchase",
            productId = item.productId,
            locationId = item.positions.first().locationId,
            locationName = item.positions.first().locationName,
            type = StockMovementType.PURCHASE,
            quantityDelta = BigDecimal("10"),
            unitCost = InventoryCostAmount(BigDecimal("5.25"), CurrencyCode.of("PEN")),
            purchaseId = purchaseId,
            purchaseDocumentNumber = "F001-42",
            occurredAt = Instant.parse("2026-08-14T14:00:00Z"),
            createdAt = Instant.parse("2026-08-14T14:00:01Z"),
        )
        val adjustmentMovement = InventoryReadMovement(
            movementId = "movement-adjustment",
            productId = item.productId,
            locationId = item.positions.last().locationId,
            locationName = item.positions.last().locationName,
            type = StockMovementType.ADJUSTMENT,
            quantityDelta = BigDecimal("-2"),
            unitCost = null,
            purchaseId = null,
            purchaseDocumentNumber = null,
            occurredAt = Instant.parse("2026-08-14T15:00:00Z"),
            createdAt = Instant.parse("2026-08-14T15:00:01Z"),
            alerts = setOf(InventoryDataAlert.MISSING_MOVEMENT_COST),
        )
        val openedPurchases = mutableListOf<PurchaseId>()
        val detail = InventoryProductDetail(
            item = item,
            movements = listOf(purchaseMovement, adjustmentMovement),
        )

        assertTrue(detail.movements.zipWithNext().all { (first, second) ->
            first.occurredAt <= second.occurredAt
        })

        composeRule.setContent {
            FacturaStockTheme {
                InventoryProductDetailScreen(
                    detail = detail,
                    onOpenPurchase = openedPurchases::add,
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag(InventoryTestTags.DETAIL_LIST)
            .performScrollToNode(
                hasTestTag(InventoryTestTags.movement(purchaseMovement.movementId)),
            )
        composeRule.onNodeWithTag(InventoryTestTags.movement(purchaseMovement.movementId))
            .assertIsDisplayed()
        composeRule.onNodeWithText("Entrada por compra").assertIsDisplayed()
        composeRule.onNodeWithTag(InventoryTestTags.openPurchase(purchaseMovement.movementId))
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle { assertEquals(listOf(purchaseId), openedPurchases) }

        composeRule.onNodeWithTag(InventoryTestTags.DETAIL_LIST)
            .performScrollToNode(
                hasTestTag(InventoryTestTags.movement(adjustmentMovement.movementId)),
            )
        composeRule.onNodeWithTag(InventoryTestTags.movement(adjustmentMovement.movementId))
            .assertIsDisplayed()
        composeRule.onNodeWithText("Ajuste").assertIsDisplayed()
        composeRule.onNodeWithText("Sin costo verificable").assertIsDisplayed()
        composeRule.onNodeWithTag(InventoryTestTags.openPurchase(adjustmentMovement.movementId))
            .assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(listOf(purchaseId), openedPurchases) }
    }

    @Test
    fun profitSectionShowsExactMetricsMissingStatesAndValidatesPriceEditor() {
        val available = availableProfit()
        val missing = missingPriceProfit()
        var savedPrice = false

        composeRule.setContent {
            var section by remember {
                mutableStateOf(InventoryContract.ListSection.STOCK)
            }
            var editor by remember {
                mutableStateOf<InventoryContract.SalePriceEditor?>(null)
            }
            FacturaStockTheme {
                InventoryListScreen(
                    items = emptyList(),
                    query = "",
                    diagnosticReport = null,
                    isLoading = false,
                    isDiagnosing = false,
                    diagnosticFailed = false,
                    onQueryChange = {},
                    onProductClick = {},
                    onRunDiagnostic = {},
                    section = section,
                    profits = listOf(available, missing),
                    onSectionChange = { section = it },
                    onEditSalePrice = { productId ->
                        val profit = listOf(available, missing).single { it.productId == productId }
                        editor = InventoryContract.SalePriceEditor(
                            productId = productId,
                            productName = profit.productName,
                            expectedVersion = profit.productVersion,
                            value = "",
                        )
                    },
                    salePriceEditor = editor,
                    onSalePriceChange = { value -> editor = editor?.copy(value = value) },
                    onSaveSalePrice = {
                        if (editor?.isValid == true) savedPrice = true
                        else editor = editor?.copy(submitAttempted = true)
                    },
                    onDismissSalePrice = { editor = null },
                )
            }
        }

        composeRule.onNodeWithTag(InventoryTestTags.SECTION_PROFIT)
            .assertIsDisplayed()
            .performClick()
        composeRule.onAllNodesWithText("Ganancias").assertCountEquals(1)
        composeRule.onNodeWithTag(InventoryTestTags.LIST_SCREEN).performScrollToNode(
            hasTestTag(InventoryTestTags.profit(available.productId)),
        )
        composeRule.onNodeWithText("Ganancia estimada por unidad: PEN 4.5")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Margen estimado: 45%")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Ganancia potencial estimada sobre el stock: PEN 45")
            .assertIsDisplayed()

        composeRule.onNodeWithTag(InventoryTestTags.LIST_SCREEN).performScrollToNode(
            hasTestTag(InventoryTestTags.profit(missing.productId)),
        )
        composeRule.onNodeWithText("Falta precio de venta").assertIsDisplayed()
        composeRule.onNodeWithText("Precio de venta: PEN 0 por NIU").assertDoesNotExist()
        composeRule.onNodeWithTag(InventoryTestTags.editPrice(missing.productId))
            .assertContentDescriptionEquals("Corregir precio de venta de Galleta sin precio")
            .performClick()

        composeRule.onNodeWithTag(InventoryTestTags.PRICE_DIALOG).assertIsDisplayed()
        composeRule.onNodeWithTag(InventoryTestTags.PRICE_FIELD)
            .performTextReplacement("0")
        composeRule.onNodeWithText("Guardar precio").performClick()
        composeRule.onNodeWithText("Ingresa un precio válido mayor que cero.")
            .assertIsDisplayed()
        composeRule.runOnIdle { assertTrue(!savedPrice) }
    }

    private fun inventoryItem(): InventoryReadItem {
        val currency = CurrencyCode.of("PEN")
        return InventoryReadItem(
            productId = ProductId.from(uuid(1)),
            businessId = BusinessId.from(uuid(2)),
            productName = "Café molido",
            sku = "CAF-001",
            unitCode = "NIU",
            unitSymbol = "und",
            positions = listOf(
                InventoryReadPosition(
                    locationId = LocationId.from(uuid(3)),
                    locationName = "Almacén principal",
                    quantityOnHand = BigDecimal("10"),
                    averageUnitCost = InventoryCostAmount(BigDecimal("5.25"), currency),
                    version = 3,
                    updatedAt = Instant.parse("2026-08-14T15:00:00Z"),
                ),
                InventoryReadPosition(
                    locationId = LocationId.from(uuid(4)),
                    locationName = "Tienda Miraflores",
                    quantityOnHand = BigDecimal("-2"),
                    averageUnitCost = InventoryCostAmount(BigDecimal("7.50"), currency),
                    version = 1,
                    updatedAt = Instant.parse("2026-08-14T15:30:00Z"),
                    alerts = setOf(InventoryDataAlert.NEGATIVE_STOCK),
                ),
            ),
        )
    }

    private fun availableProfit(): ProductProfit {
        val currency = CurrencyCode.of("PEN")
        return ProductProfit(
            productId = ProductId.from(uuid(40)),
            businessId = BusinessId.from(uuid(2)),
            productName = "Café rentable",
            sku = "CAF-G",
            unitCode = "NIU",
            productStatus = CatalogStatus.ACTIVE,
            productVersion = 3L,
            salePrice = Money.fromMajor("10.00", currency),
            totalStockQuantity = BigDecimal("10"),
            averageUnitCost = InventoryCostAmount(BigDecimal("5.5"), currency),
            unitProfit = ExactMonetaryAmount(BigDecimal("4.5"), currency),
            marginPercent = BigDecimal("45.0000"),
            potentialProfit = ExactMonetaryAmount(BigDecimal("45"), currency),
            status = ProductProfitStatus.AVAILABLE,
            issues = emptySet(),
        )
    }

    private fun missingPriceProfit(): ProductProfit = ProductProfit(
        productId = ProductId.from(uuid(41)),
        businessId = BusinessId.from(uuid(2)),
        productName = "Galleta sin precio",
        sku = null,
        unitCode = "NIU",
        productStatus = CatalogStatus.ACTIVE,
        productVersion = 1L,
        salePrice = null,
        totalStockQuantity = BigDecimal("3"),
        averageUnitCost = null,
        unitProfit = null,
        marginPercent = null,
        potentialProfit = null,
        status = ProductProfitStatus.MISSING_SALE_PRICE,
        issues = emptySet(),
    )

    private fun uuid(seed: Int): UUID =
        UUID.fromString("00000000-0000-0000-0000-%012d".format(seed))
}
