package com.facturastock.app.feature.inventory

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.InventoryCostAmount
import com.facturastock.app.domain.model.InventoryDataAlert
import com.facturastock.app.domain.model.InventoryDiagnosticIssue
import com.facturastock.app.domain.model.InventoryDiagnosticPosition
import com.facturastock.app.domain.model.InventoryDiagnosticReport
import com.facturastock.app.domain.model.InventoryProductDetail
import com.facturastock.app.domain.model.InventoryReadItem
import com.facturastock.app.domain.model.InventoryReadMovement
import com.facturastock.app.domain.model.InventoryReadPosition
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.ProductProfit
import com.facturastock.app.domain.model.ProductProfitStatus
import com.facturastock.app.domain.model.StockMovementType
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.repository.ProductProfitRepository
import com.facturastock.app.domain.repository.ProductRepository
import com.facturastock.app.domain.usecase.DiagnoseInventoryUseCase
import com.facturastock.app.domain.usecase.ObserveInventoryProductUseCase
import com.facturastock.app.domain.usecase.ObserveInventoryUseCase
import com.facturastock.app.domain.usecase.ObserveProductProfitsUseCase
import com.facturastock.app.domain.usecase.UpdateProductSalePriceUseCase
import com.facturastock.app.feature.common.RouteArgumentKeys
import com.facturastock.app.testing.FakeAppConfigurationRepository
import com.facturastock.app.testing.FakeInventoryReadRepository
import com.facturastock.app.testing.FakeProductRepository
import com.facturastock.app.testing.MainDispatcherRule
import com.facturastock.app.testing.TestDispatcherProvider
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Contrato de presentación del inventario: el diagnóstico compara el libro con la proyección
 * cacheada y marca cada divergencia, un informe viejo se descarta en cuanto la proyección cambia,
 * la búsqueda cubre nombre, SKU, unidad y almacén, y un movimiento siempre puede abrir su compra.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InventoryViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val configuration = FakeAppConfigurationRepository()
    private val inventoryReads = FakeInventoryReadRepository()
    private val products = FakeProductRepository(AppClock { UPDATED_AT })
    private val productProfits = FakeProductProfitRepository()
    private val dispatchers = TestDispatcherProvider(main = mainDispatcherRule.dispatcher)

    @Test
    fun `the diagnostic marks every divergence and clears the alert when the ledger agrees`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            inventoryReads.replaceInventory(BUSINESS_ID, listOf(sugar(), rice()))
            val viewModel = createViewModel()
            runCurrent()

            assertFalse(viewModel.uiState.value.isLoading)
            assertEquals(emptySet<InventoryDataAlert>(), viewModel.uiState.value.items.first().allAlerts)
            assertNull(viewModel.uiState.value.diagnosticReport)

            inventoryReads.diagnosticReport = report(divergent = setOf(SUGAR_ID))
            viewModel.onAction(InventoryContract.Action.RunDiagnostic)
            runCurrent()

            val diagnosed = viewModel.uiState.value
            assertFalse(diagnosed.isDiagnosing)
            assertEquals(1, diagnosed.diagnosticReport?.divergenceCount)
            assertEquals(3, diagnosed.diagnosticReport?.movementCount)
            assertFalse(diagnosed.diagnosticReport!!.isConsistent)
            assertTrue(InventoryDataAlert.PROJECTION_DIVERGENCE in diagnosed.alertsOf(SUGAR_ID))
            assertFalse(InventoryDataAlert.PROJECTION_DIVERGENCE in diagnosed.alertsOf(RICE_ID))

            inventoryReads.diagnosticReport = report()
            viewModel.onAction(InventoryContract.Action.RunDiagnostic)
            runCurrent()

            val healed = viewModel.uiState.value
            assertTrue(healed.diagnosticReport!!.isConsistent)
            assertTrue(healed.allItems.none { InventoryDataAlert.PROJECTION_DIVERGENCE in it.allAlerts })
            assertEquals(2, inventoryReads.diagnoseCalls.size)
        }

    @Test
    fun `diagnostic chrome stays hidden for normal data and appears only when actionable`() {
        val healthy = listOf(sugar())

        assertFalse(
            inventoryDiagnosticIsRelevant(
                items = healthy,
                report = null,
                isRunning = false,
                failed = false,
            ),
        )
        assertFalse(
            inventoryDiagnosticIsRelevant(
                items = healthy,
                report = report(),
                isRunning = false,
                failed = false,
            ),
        )
        assertTrue(
            inventoryDiagnosticIsRelevant(
                items = healthy,
                report = null,
                isRunning = true,
                failed = false,
            ),
        )
        assertTrue(
            inventoryDiagnosticIsRelevant(
                items = healthy,
                report = null,
                isRunning = false,
                failed = true,
            ),
        )
        assertTrue(
            inventoryDiagnosticIsRelevant(
                items = healthy,
                report = report(divergent = setOf(SUGAR_ID)),
                isRunning = false,
                failed = false,
            ),
        )
        assertTrue(
            inventoryDiagnosticIsRelevant(
                items = listOf(sugar().copy(alerts = setOf(InventoryDataAlert.NEGATIVE_STOCK))),
                report = null,
                isRunning = false,
                failed = false,
            ),
        )
        assertFalse(
            inventoryDiagnosticIsRelevant(
                items = listOf(sugar().copy(alerts = setOf(InventoryDataAlert.ARCHIVED_PRODUCT))),
                report = null,
                isRunning = false,
                failed = false,
            ),
        )
    }

    @Test
    fun `a stale report is dropped as soon as the cached projection moves`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            inventoryReads.replaceInventory(BUSINESS_ID, listOf(sugar(), rice()))
            val viewModel = createViewModel()
            runCurrent()
            inventoryReads.diagnosticReport = report(divergent = setOf(SUGAR_ID))
            viewModel.onAction(InventoryContract.Action.RunDiagnostic)
            runCurrent()
            assertTrue(InventoryDataAlert.PROJECTION_DIVERGENCE in viewModel.uiState.value.alertsOf(SUGAR_ID))

            // Otro dato de la tarjeta cambia, pero la versión y la marca del saldo siguen iguales.
            inventoryReads.replaceInventory(
                BUSINESS_ID,
                listOf(sugar().copy(productName = "Azúcar rubia bolsa"), rice()),
            )
            runCurrent()
            assertEquals(GENERATED_AT, viewModel.uiState.value.diagnosticReport?.generatedAt)
            assertTrue(InventoryDataAlert.PROJECTION_DIVERGENCE in viewModel.uiState.value.alertsOf(SUGAR_ID))

            // La versión del saldo avanza: el informe ya no describe esta proyección.
            inventoryReads.replaceInventory(BUSINESS_ID, listOf(sugar(version = 5L), rice()))
            runCurrent()
            assertNull(viewModel.uiState.value.diagnosticReport)
            assertFalse(InventoryDataAlert.PROJECTION_DIVERGENCE in viewModel.uiState.value.alertsOf(SUGAR_ID))

            inventoryReads.diagnosticReport = report(divergent = setOf(SUGAR_ID), sugarVersion = 5L)
            viewModel.onAction(InventoryContract.Action.RunDiagnostic)
            runCurrent()
            assertTrue(InventoryDataAlert.PROJECTION_DIVERGENCE in viewModel.uiState.value.alertsOf(SUGAR_ID))

            // Desaparece una clave producto/almacén comparada: el informe tampoco sirve.
            inventoryReads.replaceInventory(BUSINESS_ID, listOf(sugar(version = 5L)))
            runCurrent()
            assertNull(viewModel.uiState.value.diagnosticReport)
            assertEquals(1, viewModel.uiState.value.allItems.size)
            assertFalse(InventoryDataAlert.PROJECTION_DIVERGENCE in viewModel.uiState.value.alertsOf(SUGAR_ID))
        }

    @Test
    fun `the diagnostic runs once at a time and never from the product detail`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            inventoryReads.replaceInventory(BUSINESS_ID, listOf(sugar()))
            val pending = CompletableDeferred<InventoryDiagnosticReport>()
            inventoryReads.diagnoseHandler = { pending.await() }
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(InventoryContract.Action.RunDiagnostic)
            runCurrent()
            viewModel.onAction(InventoryContract.Action.RunDiagnostic)
            runCurrent()

            assertEquals(1, inventoryReads.diagnoseCalls.size)
            assertTrue(viewModel.uiState.value.isDiagnosing)

            pending.complete(report(divergent = setOf(SUGAR_ID)))
            runCurrent()

            assertFalse(viewModel.uiState.value.isDiagnosing)
            assertEquals(1, inventoryReads.diagnoseCalls.size)
            assertTrue(InventoryDataAlert.PROJECTION_DIVERGENCE in viewModel.uiState.value.alertsOf(SUGAR_ID))

            val detailViewModel = createViewModel(
                SavedStateHandle(mapOf(RouteArgumentKeys.PRODUCT_ID to SUGAR_ID.value)),
            )
            runCurrent()
            detailViewModel.onAction(InventoryContract.Action.RunDiagnostic)
            runCurrent()

            assertEquals(1, inventoryReads.diagnoseCalls.size)
            assertFalse(detailViewModel.uiState.value.isDiagnosing)
        }

    @Test
    fun `a failed diagnostic is reported and survives reloading the projection`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            inventoryReads.replaceInventory(BUSINESS_ID, listOf(sugar()))
            inventoryReads.diagnoseHandler = { throw StorageException(StorageError.Unavailable) }
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(InventoryContract.Action.RunDiagnostic)
            runCurrent()

            assertEquals(InventoryContract.Failure.DIAGNOSTIC_FAILED, viewModel.uiState.value.failure)
            assertFalse(viewModel.uiState.value.isDiagnosing)
            assertNull(viewModel.uiState.value.diagnosticReport)

            viewModel.onAction(InventoryContract.Action.Retry)
            runCurrent()

            assertEquals(InventoryContract.Failure.DIAGNOSTIC_FAILED, viewModel.uiState.value.failure)
            assertEquals(1, viewModel.uiState.value.items.size)

            inventoryReads.diagnoseHandler = null
            inventoryReads.diagnosticReport = report()
            viewModel.onAction(InventoryContract.Action.RunDiagnostic)
            runCurrent()

            assertNull(viewModel.uiState.value.failure)
            assertTrue(viewModel.uiState.value.diagnosticReport!!.isConsistent)
        }

    @Test
    fun `search covers name sku unit and warehouse and is truncated and persisted`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            inventoryReads.replaceInventory(BUSINESS_ID, listOf(sugar(), rice()))
            val savedStateHandle = SavedStateHandle()
            val viewModel = createViewModel(savedStateHandle)
            runCurrent()

            assertEquals(listOf(SUGAR_ID, RICE_ID), viewModel.searchFor(""))
            assertEquals(listOf(SUGAR_ID), viewModel.searchFor("  AZÚCAR "))
            assertEquals(listOf(RICE_ID), viewModel.searchFor("arz-9"))
            assertEquals(listOf(SUGAR_ID), viewModel.searchFor("kg"))
            assertEquals(listOf(RICE_ID), viewModel.searchFor("depósito"))
            assertEquals(emptyList<ProductId>(), viewModel.searchFor("mostaza"))
            assertEquals(2, viewModel.uiState.value.allItems.size)

            assertEquals(emptyList<ProductId>(), viewModel.searchFor("z".repeat(260)))
            assertEquals("z".repeat(MAX_QUERY_LENGTH), viewModel.uiState.value.query)
            assertEquals("z".repeat(MAX_QUERY_LENGTH), savedStateHandle.get<String>(QUERY_KEY))
        }

    @Test
    fun `scanner routing requires the explicit stock mode and no blocking work`() {
        val ready = InventoryContract.State(
            inputMode = InventoryContract.InputMode.SCANNER,
        )

        assertTrue(ready.canRouteScannerInput)
        assertTrue(
            ready.copy(
                scannerFailure = InventoryContract.ScannerFailure.INVALID_BARCODE,
            ).canRouteScannerInput,
        )
        assertTrue(
            ready.copy(
                scannerFailure = InventoryContract.ScannerFailure.BARCODE_NOT_FOUND,
            ).canRouteScannerInput,
        )
        assertTrue(
            ready.copy(
                scannerFailure = InventoryContract.ScannerFailure.LOOKUP_FAILED,
            ).canRouteScannerInput,
        )
        assertFalse(
            ready.copy(inputMode = InventoryContract.InputMode.SEARCH).canRouteScannerInput,
        )
        assertFalse(
            ready.copy(
                section = InventoryContract.ListSection.ESTIMATED_PROFIT,
            ).canRouteScannerInput,
        )
        assertFalse(ready.copy(isLoading = true).canRouteScannerInput)
        assertFalse(ready.copy(isDiagnosing = true).canRouteScannerInput)
        assertFalse(ready.copy(isBarcodeLookupRunning = true).canRouteScannerInput)
        assertFalse(ready.copy(productId = SUGAR_ID).canRouteScannerInput)
        assertFalse(
            ready.copy(failure = InventoryContract.Failure.LOAD_FAILED).canRouteScannerInput,
        )
        assertFalse(ready.copy(isSavingSalePrice = true).canRouteScannerInput)
        assertFalse(
            ready.copy(
                salePriceEditor = InventoryContract.SalePriceEditor(
                    productId = SUGAR_ID,
                    productName = "Azúcar rubia",
                    expectedVersion = 1L,
                ),
            ).canRouteScannerInput,
        )
    }

    @Test
    fun `scanner canonicalizes one exact tenant lookup opens detail and ignores a concurrent scan`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            // El lookup pertenece al catálogo: también debe abrir un producto que aún no tiene
            // saldo y, por ello, no forma parte de la proyección visible de inventario.
            inventoryReads.replaceInventory(BUSINESS_ID, listOf(rice()))
            products.create(catalogProduct(version = 1L, barcode = "0012aB-Z"))
            val lookupGate = CompletableDeferred<Unit>()
            val recordingProducts = RecordingProductRepository(products).apply {
                gate = lookupGate
            }
            val viewModel = createViewModel(productRepository = recordingProducts)
            runCurrent()
            viewModel.onAction(
                InventoryContract.Action.InputModeChanged(InventoryContract.InputMode.SCANNER),
            )
            viewModel.onAction(InventoryContract.Action.ScannerAvailabilityChanged(true))
            runCurrent()

            viewModel.effects.test {
                viewModel.onAction(InventoryContract.Action.BarcodeScanned("  0012aB-Z  "))
                runCurrent()

                assertTrue(viewModel.uiState.value.isBarcodeLookupRunning)
                assertFalse(viewModel.uiState.value.canRouteScannerInput)
                assertEquals(
                    listOf(BUSINESS_ID to "0012aB-Z"),
                    recordingProducts.barcodeLookups,
                )

                viewModel.onAction(InventoryContract.Action.BarcodeScanned("0012aB-Z"))
                runCurrent()
                assertEquals(1, recordingProducts.barcodeLookups.size)

                lookupGate.complete(Unit)
                runCurrent()

                assertEquals(InventoryContract.Effect.OpenProduct(SUGAR_ID), awaitItem())
                assertFalse(viewModel.uiState.value.isBarcodeLookupRunning)
                assertNull(viewModel.uiState.value.scannerFailure)
                assertEquals("", viewModel.uiState.value.query)
                assertEquals(listOf(RICE_ID), viewModel.uiState.value.items.map { it.productId })
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `invalid unknown and failed scanner lookups are recoverable without changing inventory`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            inventoryReads.replaceInventory(BUSINESS_ID, listOf(sugar(), rice()))
            val recordingProducts = RecordingProductRepository(products)
            val viewModel = createViewModel(productRepository = recordingProducts)
            runCurrent()
            viewModel.onAction(
                InventoryContract.Action.InputModeChanged(InventoryContract.InputMode.SCANNER),
            )
            viewModel.onAction(InventoryContract.Action.ScannerAvailabilityChanged(true))
            runCurrent()

            products.nextFailure = StorageError.Unavailable
            viewModel.onAction(InventoryContract.Action.BarcodeScanned("A".repeat(129)))
            runCurrent()

            assertEquals(
                InventoryContract.ScannerFailure.INVALID_BARCODE,
                viewModel.uiState.value.scannerFailure,
            )
            assertEquals(StorageError.Unavailable, products.nextFailure)
            assertTrue(recordingProducts.barcodeLookups.isEmpty())
            assertTrue(viewModel.uiState.value.canRouteScannerInput)

            viewModel.onAction(InventoryContract.Action.BarcodeScanned("7750000000001"))
            runCurrent()

            assertEquals(
                InventoryContract.ScannerFailure.LOOKUP_FAILED,
                viewModel.uiState.value.scannerFailure,
            )
            assertEquals(listOf(BUSINESS_ID to "7750000000001"), recordingProducts.barcodeLookups)
            assertTrue(viewModel.uiState.value.canRouteScannerInput)

            viewModel.onAction(InventoryContract.Action.BarcodeScanned("7750000000002"))
            runCurrent()

            assertEquals(
                InventoryContract.ScannerFailure.BARCODE_NOT_FOUND,
                viewModel.uiState.value.scannerFailure,
            )
            assertEquals(2, recordingProducts.barcodeLookups.size)
            assertTrue(viewModel.uiState.value.canRouteScannerInput)
            assertEquals("", viewModel.uiState.value.query)
            assertEquals(
                listOf(SUGAR_ID, RICE_ID),
                viewModel.uiState.value.items.map { it.productId },
            )
        }

    @Test
    fun `scanner without an active business fails before repository access`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val recordingProducts = RecordingProductRepository(products)
            val viewModel = createViewModel(productRepository = recordingProducts)
            runCurrent()
            viewModel.onAction(
                InventoryContract.Action.InputModeChanged(InventoryContract.InputMode.SCANNER),
            )
            viewModel.onAction(InventoryContract.Action.ScannerAvailabilityChanged(true))
            runCurrent()

            viewModel.onAction(InventoryContract.Action.BarcodeScanned("7750000000001"))
            runCurrent()

            assertEquals(
                InventoryContract.ScannerFailure.NO_ACTIVE_BUSINESS,
                viewModel.uiState.value.scannerFailure,
            )
            assertTrue(recordingProducts.barcodeLookups.isEmpty())
            assertFalse(viewModel.uiState.value.isBarcodeLookupRunning)
            assertTrue(viewModel.uiState.value.canRouteScannerInput)
        }

    @Test
    fun `a filtered list keeps every item when the projection is reloaded`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            inventoryReads.replaceInventory(BUSINESS_ID, listOf(sugar(), rice()))
            val viewModel = createViewModel(SavedStateHandle(mapOf(QUERY_KEY to "arroz")))
            runCurrent()

            assertEquals("arroz", viewModel.uiState.value.query)
            assertEquals(listOf(RICE_ID), viewModel.uiState.value.items.map { it.productId })
            assertEquals(listOf(SUGAR_ID, RICE_ID), viewModel.uiState.value.allItems.map { it.productId })

            inventoryReads.replaceInventory(
                BUSINESS_ID,
                listOf(sugar(), rice(), rice().copy(productId = THIRD_ID, productName = "Arroz integral")),
            )
            runCurrent()

            assertEquals(listOf(RICE_ID, THIRD_ID), viewModel.uiState.value.items.map { it.productId })
            assertEquals(3, viewModel.uiState.value.allItems.size)
        }

    @Test
    fun `profit section and name filter survive state restoration`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            productProfits.emit(listOf(missingProfit(SUGAR_ID, "Azúcar rubia"), missingProfit(RICE_ID, "Arroz extra")))
            val handle = SavedStateHandle()
            val viewModel = createViewModel(handle)
            runCurrent()

            viewModel.onAction(
                InventoryContract.Action.SectionChanged(
                    InventoryContract.ListSection.ESTIMATED_PROFIT,
                ),
            )
            viewModel.onAction(InventoryContract.Action.SearchChanged("azúcar"))
            runCurrent()

            assertEquals(
                InventoryContract.ListSection.ESTIMATED_PROFIT,
                viewModel.uiState.value.section,
            )
            assertEquals(listOf(SUGAR_ID), viewModel.uiState.value.profits.map { it.productId })
            assertEquals("ESTIMATED_PROFIT", handle.get<String>(SECTION_KEY))
            assertEquals("azúcar", handle.get<String>(QUERY_KEY))

            val restored = createViewModel(handle)
            runCurrent()
            assertEquals(
                InventoryContract.ListSection.ESTIMATED_PROFIT,
                restored.uiState.value.section,
            )
            assertEquals(listOf(SUGAR_ID), restored.uiState.value.profits.map { it.productId })
        }

    @Test
    fun `changing section applies the current query to its own projection`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            inventoryReads.replaceInventory(BUSINESS_ID, listOf(sugar(), rice()))
            productProfits.emit(
                listOf(
                    missingProfit(SUGAR_ID, "Azucar rubia"),
                    missingProfit(RICE_ID, "Arroz extra"),
                ),
            )
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(InventoryContract.Action.SearchChanged("arroz"))
            runCurrent()
            assertEquals(listOf(RICE_ID), viewModel.uiState.value.items.map { it.productId })

            viewModel.onAction(
                InventoryContract.Action.SectionChanged(
                    InventoryContract.ListSection.ESTIMATED_PROFIT,
                ),
            )
            runCurrent()

            assertEquals(listOf(RICE_ID), viewModel.uiState.value.profits.map { it.productId })
        }

    @Test
    fun `only the visible inventory projection is observed`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            inventoryReads.replaceInventory(BUSINESS_ID, listOf(sugar()))
            productProfits.emit(listOf(missingProfit(SUGAR_ID, "Azúcar rubia")))

            val stockViewModel = createViewModel()
            runCurrent()

            assertEquals(0, productProfits.observeCalls)
            assertEquals(listOf(BUSINESS_ID), inventoryReads.observedInventoryBusinesses)

            stockViewModel.onAction(
                InventoryContract.Action.SectionChanged(
                    InventoryContract.ListSection.ESTIMATED_PROFIT,
                ),
            )
            runCurrent()

            assertEquals(1, productProfits.observeCalls)
            assertEquals(listOf(SUGAR_ID), stockViewModel.uiState.value.profits.map { it.productId })

            val profitViewModel = createViewModel(
                SavedStateHandle(mapOf(SECTION_KEY to "ESTIMATED_PROFIT")),
            )
            runCurrent()

            assertEquals(2, productProfits.observeCalls)
            assertEquals(1, inventoryReads.observedInventoryBusinesses.size)
            assertEquals(listOf(SUGAR_ID), profitViewModel.uiState.value.profits.map { it.productId })
        }

    @Test
    fun `sale price editor saves configured currency with captured product version`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            products.create(catalogProduct(version = 1L))
            productProfits.emit(listOf(missingProfit(SUGAR_ID, "Azúcar rubia", version = 1L)))
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(
                InventoryContract.Action.SectionChanged(
                    InventoryContract.ListSection.ESTIMATED_PROFIT,
                ),
            )
            runCurrent()
            viewModel.onAction(InventoryContract.Action.EditSalePrice(SUGAR_ID))
            runCurrent()
            assertEquals(1L, viewModel.uiState.value.salePriceEditor?.expectedVersion)
            viewModel.onAction(InventoryContract.Action.SalePriceChanged("12,50"))
            runCurrent()
            viewModel.onAction(InventoryContract.Action.SaveSalePrice)
            runCurrent()

            assertNull(viewModel.uiState.value.salePriceEditor)
            assertEquals(Money.fromMajor("12.50", PEN), products.findById(SUGAR_ID)?.salePrice)
        }

    @Test
    fun `stale sale price keeps the entered value and never retries another version`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            products.create(catalogProduct(version = 2L))
            productProfits.emit(listOf(missingProfit(SUGAR_ID, "Azúcar rubia", version = 1L)))
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(
                InventoryContract.Action.SectionChanged(
                    InventoryContract.ListSection.ESTIMATED_PROFIT,
                ),
            )
            runCurrent()
            viewModel.onAction(InventoryContract.Action.EditSalePrice(SUGAR_ID))
            runCurrent()
            viewModel.onAction(InventoryContract.Action.SalePriceChanged("12.50"))
            runCurrent()
            viewModel.onAction(InventoryContract.Action.SaveSalePrice)
            runCurrent()

            assertEquals("12.50", viewModel.uiState.value.salePriceEditor?.value)
            assertEquals(
                InventoryContract.ProfitFailure.STALE_PRICE,
                viewModel.uiState.value.profitFailure,
            )
            assertNull(products.findById(SUGAR_ID)?.salePrice)
        }

    @Test
    fun `sale price editor rejects values outside interoperable policy`() {
        val editor = InventoryContract.SalePriceEditor(
            productId = SUGAR_ID,
            productName = "Azúcar rubia",
            expectedVersion = 1L,
            currency = PEN,
            value = "90071992547410.00",
        )

        assertFalse(editor.isValid)
        assertNull(editor.parsedPrice)
    }

    @Test
    fun `a movement opens its origin purchase and a card opens the product detail`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            inventoryReads.replaceInventory(BUSINESS_ID, listOf(sugar()))
            val viewModel = createViewModel()
            runCurrent()

            viewModel.effects.test {
                viewModel.onAction(InventoryContract.Action.ProductSelected(SUGAR_ID))
                runCurrent()
                assertEquals(InventoryContract.Effect.OpenProduct(SUGAR_ID), awaitItem())

                viewModel.onAction(InventoryContract.Action.OriginPurchaseSelected(PURCHASE_ID))
                runCurrent()
                assertEquals(InventoryContract.Effect.OpenPurchase(PURCHASE_ID), awaitItem())

                viewModel.onAction(InventoryContract.Action.BackSelected)
                runCurrent()
                assertEquals(InventoryContract.Effect.Back, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `the detail reports a missing product and then exposes its chronological movements`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            val viewModel = createViewModel(
                SavedStateHandle(mapOf(RouteArgumentKeys.PRODUCT_ID to SUGAR_ID.value)),
            )
            runCurrent()

            assertEquals(SUGAR_ID, viewModel.uiState.value.productId)
            assertEquals(InventoryContract.Failure.PRODUCT_NOT_FOUND, viewModel.uiState.value.failure)
            assertNull(viewModel.uiState.value.detail)
            assertEquals(listOf(BUSINESS_ID to SUGAR_ID), inventoryReads.observedProducts)
            assertTrue(inventoryReads.observedInventoryBusinesses.isEmpty())

            inventoryReads.setProductDetail(BUSINESS_ID, SUGAR_ID, detail())
            runCurrent()

            val state = viewModel.uiState.value
            assertNull(state.failure)
            assertFalse(state.isLoading)
            val detail = requireNotNull(state.detail)
            assertEquals(
                listOf("movement-001", "movement-002"),
                detail.movements.map { it.movementId },
            )
            assertEquals(PURCHASE_ID, detail.movements.first().purchaseId)
            assertEquals("F001-00000123", detail.movements.first().purchaseDocumentNumber)
            assertNull(detail.movements.last().purchaseId)
        }

    @Test
    fun `an invalid product id closes the route without observing anything`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            val viewModel = createViewModel(
                SavedStateHandle(mapOf(RouteArgumentKeys.PRODUCT_ID to "no-es-un-uuid")),
            )
            runCurrent()

            assertEquals(InventoryContract.Failure.INVALID_PRODUCT_ID, viewModel.uiState.value.failure)
            assertNull(viewModel.uiState.value.productId)
            assertTrue(inventoryReads.observedProducts.isEmpty())
            assertTrue(inventoryReads.observedInventoryBusinesses.isEmpty())

            viewModel.effects.test {
                assertEquals(InventoryContract.Effect.CloseInvalidRoute, awaitItem())
                viewModel.onAction(InventoryContract.Action.Retry)
                runCurrent()
                assertEquals(InventoryContract.Effect.CloseInvalidRoute, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            assertTrue(inventoryReads.observedInventoryBusinesses.isEmpty())
        }

    private fun InventoryContract.State.alertsOf(productId: ProductId): Set<InventoryDataAlert> =
        allItems.single { it.productId == productId }.allAlerts

    private fun InventoryViewModel.searchFor(query: String): List<ProductId> {
        onAction(InventoryContract.Action.SearchChanged(query))
        mainDispatcherRule.scheduler.runCurrent()
        return uiState.value.items.map { it.productId }
    }

    private fun createViewModel(
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
        productRepository: ProductRepository = products,
    ): InventoryViewModel = InventoryViewModel(
        savedStateHandle = savedStateHandle,
        observeInventory = ObserveInventoryUseCase(configuration, inventoryReads),
        observeProduct = ObserveInventoryProductUseCase(configuration, inventoryReads),
        diagnoseInventory = DiagnoseInventoryUseCase(configuration, inventoryReads),
        observeProductProfits = ObserveProductProfitsUseCase(configuration, productProfits),
        updateProductSalePrice = UpdateProductSalePriceUseCase(configuration, products),
        products = productRepository,
        configuration = configuration,
        dispatcherProvider = dispatchers,
    )

    private suspend fun activate() {
        configuration.completeOnboarding(
            BUSINESS_ID,
            AppConfiguration.DEFAULT_TAX_RATE,
            AppConfiguration.DEFAULT_COST_POLICY,
        )
    }

    private fun sugar(version: Long = 4L): InventoryReadItem = InventoryReadItem(
        productId = SUGAR_ID,
        businessId = BUSINESS_ID,
        productName = "Azúcar rubia",
        sku = "SKU-AZU-1",
        unitCode = "KG",
        unitSymbol = "kg",
        positions = listOf(position(CENTRAL_ID, "Almacén Central", BigDecimal("12.000000"), version)),
    )

    private fun rice(): InventoryReadItem = InventoryReadItem(
        productId = RICE_ID,
        businessId = BUSINESS_ID,
        productName = "Arroz extra",
        sku = "ARZ-9",
        unitCode = "SAC",
        unitSymbol = null,
        positions = listOf(position(NORTH_ID, "Depósito Norte", BigDecimal("5.000000"), version = 4L)),
    )

    private fun catalogProduct(version: Long, barcode: String? = null): Product = Product(
        productId = SUGAR_ID,
        businessId = BUSINESS_ID,
        unitId = UNIT_ID,
        name = "Azúcar rubia",
        barcode = barcode,
        salePrice = null,
        status = CatalogStatus.ACTIVE,
        createdAt = UPDATED_AT,
        updatedAt = UPDATED_AT,
        version = version,
    )

    private class RecordingProductRepository(
        private val delegate: ProductRepository,
    ) : ProductRepository by delegate {
        val barcodeLookups = mutableListOf<Pair<BusinessId, String>>()
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun findByBarcode(businessId: BusinessId, barcode: String): Product? {
            barcodeLookups += businessId to barcode
            gate?.await()
            return delegate.findByBarcode(businessId, barcode)
        }
    }

    private fun missingProfit(
        productId: ProductId,
        name: String,
        version: Long = 1L,
    ): ProductProfit = ProductProfit(
        productId = productId,
        businessId = BUSINESS_ID,
        productName = name,
        sku = null,
        unitCode = "NIU",
        productStatus = CatalogStatus.ACTIVE,
        productVersion = version,
        salePrice = null,
        totalStockQuantity = BigDecimal.ONE,
        averageUnitCost = null,
        unitProfit = null,
        marginPercent = null,
        potentialProfit = null,
        status = ProductProfitStatus.MISSING_SALE_PRICE,
        issues = emptySet(),
    )

    private fun position(
        locationId: LocationId,
        locationName: String,
        quantity: BigDecimal,
        version: Long,
    ): InventoryReadPosition = InventoryReadPosition(
        locationId = locationId,
        locationName = locationName,
        quantityOnHand = quantity,
        averageUnitCost = InventoryCostAmount(BigDecimal("2.500000"), PEN),
        version = version,
        updatedAt = UPDATED_AT,
    )

    /** Informe que describe exactamente las claves de [sugar] y [rice], como hace Room. */
    private fun report(
        divergent: Set<ProductId> = emptySet(),
        sugarVersion: Long = 4L,
        riceVersion: Long = 4L,
    ): InventoryDiagnosticReport = InventoryDiagnosticReport(
        generatedAt = GENERATED_AT,
        positions = listOf(
            diagnosticPosition(
                productId = SUGAR_ID,
                productName = "Azúcar rubia",
                locationId = CENTRAL_ID,
                locationName = "Almacén Central",
                cachedVersion = sugarVersion,
                divergent = SUGAR_ID in divergent,
                movementCount = 2,
            ),
            diagnosticPosition(
                productId = RICE_ID,
                productName = "Arroz extra",
                locationId = NORTH_ID,
                locationName = "Depósito Norte",
                cachedVersion = riceVersion,
                divergent = RICE_ID in divergent,
                movementCount = 1,
            ),
        ),
    )

    private fun diagnosticPosition(
        productId: ProductId,
        productName: String,
        locationId: LocationId,
        locationName: String,
        cachedVersion: Long,
        divergent: Boolean,
        movementCount: Int,
    ): InventoryDiagnosticPosition = InventoryDiagnosticPosition(
        productId = productId,
        productName = productName,
        locationId = locationId,
        locationName = locationName,
        currency = PEN,
        cachedQuantity = BigDecimal("12.000000"),
        ledgerQuantity = if (divergent) BigDecimal("11.000000") else BigDecimal("12.000000"),
        cachedAverageUnitCost = BigDecimal("2.500000"),
        ledgerAverageUnitCost = BigDecimal("2.500000"),
        movementCount = movementCount,
        cachedVersion = cachedVersion,
        cachedUpdatedAt = UPDATED_AT,
        issues = if (divergent) setOf(InventoryDiagnosticIssue.QUANTITY_DIVERGENCE) else emptySet(),
    )

    private fun detail(): InventoryProductDetail = InventoryProductDetail(
        item = sugar(),
        movements = listOf(
            movement(seed = 1, occurredAt = Instant.parse("2026-08-18T15:00:00Z"), purchaseId = PURCHASE_ID),
            movement(seed = 2, occurredAt = Instant.parse("2026-08-19T09:30:00Z"), purchaseId = null),
        ),
    )

    private fun movement(
        seed: Int,
        occurredAt: Instant,
        purchaseId: PurchaseId?,
    ): InventoryReadMovement = InventoryReadMovement(
        movementId = "movement-%03d".format(seed),
        productId = SUGAR_ID,
        locationId = CENTRAL_ID,
        locationName = "Almacén Central",
        type = if (purchaseId == null) StockMovementType.ADJUSTMENT else StockMovementType.PURCHASE,
        quantityDelta = BigDecimal("6.000000"),
        unitCost = InventoryCostAmount(BigDecimal("2.500000"), PEN),
        purchaseId = purchaseId,
        purchaseDocumentNumber = purchaseId?.let { "F001-00000123" },
        occurredAt = occurredAt,
        createdAt = occurredAt,
    )

    private companion object {
        const val QUERY_KEY = "inventory.query"
        const val SECTION_KEY = "inventory.section"
        const val MAX_QUERY_LENGTH = 200

        val PEN: CurrencyCode = CurrencyCode.of("PEN")
        val BUSINESS_ID: BusinessId = BusinessId.from(uuid(901))
        val SUGAR_ID: ProductId = ProductId.from(uuid(11))
        val RICE_ID: ProductId = ProductId.from(uuid(12))
        val THIRD_ID: ProductId = ProductId.from(uuid(13))
        val CENTRAL_ID: LocationId = LocationId.from(uuid(21))
        val NORTH_ID: LocationId = LocationId.from(uuid(22))
        val PURCHASE_ID: PurchaseId = PurchaseId.from(uuid(31))
        val UNIT_ID: UnitId = UnitId.from(uuid(32))
        val UPDATED_AT: Instant = Instant.parse("2026-08-20T09:00:00Z")
        val GENERATED_AT: Instant = Instant.parse("2026-08-20T10:00:00Z")

        fun uuid(seed: Int): UUID =
            UUID.fromString("00000000-0000-0000-0000-%012d".format(seed))
    }
}

private class FakeProductProfitRepository : ProductProfitRepository {
    private val values = MutableStateFlow<List<ProductProfit>>(emptyList())
    var observeCalls: Int = 0
        private set

    override fun observeForBusiness(businessId: BusinessId): Flow<List<ProductProfit>> {
        observeCalls += 1
        return values
    }

    fun emit(profits: List<ProductProfit>) {
        values.value = profits
    }
}
