package com.facturastock.app.feature.inventory

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.facturastock.app.core.coroutines.DispatcherProvider
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
import com.facturastock.app.domain.repository.ProductDeletionResult
import com.facturastock.app.domain.repository.InventoryReadRepository
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
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Contrato de presentación del inventario: el diagnóstico compara el libro con la proyección
 * cacheada y marca cada divergencia, un informe viejo se descarta en cuanto la proyección cambia,
 * la búsqueda cubre nombres desde dos letras, y un movimiento siempre puede abrir su compra.
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
    fun `healthy products reuse their aggregates while diagnostics copy only changed alerts`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            val sugar = sugar()
            val rice = rice()
            inventoryReads.replaceInventory(BUSINESS_ID, listOf(sugar, rice))
            val viewModel = createViewModel()
            runCurrent()

            val initial = viewModel.uiState.value.allItems
            assertSame(sugar, initial[0])
            assertSame(rice, initial[1])
            assertSame(initial, viewModel.uiState.value.items)

            inventoryReads.diagnosticReport = report(divergent = setOf(SUGAR_ID))
            viewModel.onAction(InventoryContract.Action.RunDiagnostic)
            runCurrent()
            val divergent = viewModel.uiState.value.allItems
            assertTrue(InventoryDataAlert.PROJECTION_DIVERGENCE in divergent[0].alerts)
            assertSame(rice, divergent[1])

            // Cambiar la fecha fuerza un nuevo estado, aunque los avisos sean idénticos.
            inventoryReads.diagnosticReport = report(divergent = setOf(SUGAR_ID))
                .copy(generatedAt = GENERATED_AT.plusSeconds(1))
            viewModel.onAction(InventoryContract.Action.RunDiagnostic)
            runCurrent()
            assertSame(divergent, viewModel.uiState.value.allItems)

            inventoryReads.diagnosticReport = report()
            viewModel.onAction(InventoryContract.Action.RunDiagnostic)
            runCurrent()
            assertSame(rice, viewModel.uiState.value.allItems[1])
            assertEquals(sugar, viewModel.uiState.value.allItems[0])
        }

    @Test
    fun `typing stays responsive while Default is busy and cancelled searches skip the catalog`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            val source = CountingInventoryList(
                List(1_001) { index ->
                    sugar().copy(productId = ProductId.from(uuid(10_000 + index)))
                },
            )
            val repository = object : InventoryReadRepository by inventoryReads {
                override fun observeInventory(businessId: BusinessId): Flow<List<InventoryReadItem>> = flowOf(source)
            }
            val heldDefault = HeldInventoryDispatcher(dispatchers.default)
            val provider = object : DispatcherProvider by dispatchers {
                override val default: CoroutineDispatcher = heldDefault
            }
            val handle = SavedStateHandle()
            val viewModel = createViewModel(handle, inventoryRepository = repository, dispatcherProvider = provider)
            runCurrent()
            assertSame(source, viewModel.uiState.value.allItems)
            source.reads = 0
            heldDefault.hold = true

            listOf("az", "ar", "zz").forEach { query ->
                viewModel.onAction(InventoryContract.Action.SearchChanged(query))
                runCurrent()
                assertEquals(query, viewModel.uiState.value.query)
                assertEquals(query, handle.get<String>(QUERY_KEY))
                assertEquals(0, source.reads)
            }
            assertEquals(1_001, viewModel.uiState.value.items.size)

            heldDefault.releaseAll()
            runCurrent()
            assertTrue(viewModel.uiState.value.items.isEmpty())
            // Las dos consultas canceladas no leen las 1.001 filas; sólo se recorre la última.
            assertEquals(1_001, source.reads)
        }

    @Test
    fun `query changes and newer inventory emissions supersede work waiting on Default`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            inventoryReads.replaceInventory(BUSINESS_ID, listOf(sugar(), rice()))
            val heldDefault = HeldInventoryDispatcher(dispatchers.default)
            val provider = object : DispatcherProvider by dispatchers {
                override val default: CoroutineDispatcher = heldDefault
            }
            val viewModel = createViewModel(dispatcherProvider = provider)
            runCurrent()
            heldDefault.hold = true

            inventoryReads.replaceInventory(BUSINESS_ID, listOf(sugar(version = 5L), rice()))
            runCurrent()
            heldDefault.releaseNext()
            runCurrent() // La proyección está preparada; su filtro sigue en espera.
            viewModel.onAction(InventoryContract.Action.SearchChanged("az"))
            runCurrent()
            val latestRice = rice().copy(productName = "Arroz integral reciente")
            inventoryReads.replaceInventory(BUSINESS_ID, listOf(sugar(version = 6L), latestRice))
            runCurrent()
            // «ar» también coincide con el final de Azúcar tras normalizar las tildes.
            viewModel.onAction(InventoryContract.Action.SearchChanged("rr"))
            runCurrent()
            assertEquals("rr", viewModel.uiState.value.query)

            heldDefault.releaseAll()
            runCurrent()
            assertEquals(listOf(latestRice), viewModel.uiState.value.items)
            assertEquals(6L, viewModel.uiState.value.allItems[0].positions.first().version)
            assertEquals("rr", viewModel.uiState.value.query)
        }

    @Test
    fun `a business switch discards an uncommitted renamed index and searches only current names`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            inventoryReads.replaceInventory(BUSINESS_ID, listOf(sugar(), rice()))
            val heldDefault = HeldInventoryDispatcher(dispatchers.default)
            val provider = object : DispatcherProvider by dispatchers {
                override val default: CoroutineDispatcher = heldDefault
            }
            val viewModel = createViewModel(dispatcherProvider = provider)
            runCurrent()
            viewModel.onAction(InventoryContract.Action.SearchChanged("café"))
            runCurrent()
            heldDefault.hold = true

            inventoryReads.replaceInventory(BUSINESS_ID,
                listOf(sugar().copy(productName = "Café cancelado"), rice()))
            runCurrent()
            heldDefault.releaseNext()
            runCurrent() // Nombre normalizado en el candidato; su filtro todavía no se publica.

            val nextBusiness = BusinessId.from(uuid(902))
            val currentProduct = sugar().copy(businessId = nextBusiness, productName = "CAFÉ actual")
            inventoryReads.replaceInventory(nextBusiness, listOf(currentProduct))
            configuration.completeOnboarding(nextBusiness,
                AppConfiguration.DEFAULT_TAX_RATE, AppConfiguration.DEFAULT_COST_POLICY)
            runCurrent()
            heldDefault.releaseAll()
            runCurrent()

            assertEquals(listOf(currentProduct), viewModel.uiState.value.allItems)
            assertEquals(listOf(currentProduct), viewModel.uiState.value.items)
            assertEquals(listOf(SUGAR_ID), viewModel.searchFor("actual"))
            assertEquals(emptyList<ProductId>(), viewModel.searchFor("cancelado"))
            assertEquals(emptyList<ProductId>(), viewModel.searchFor("arroz"))

            val renamed = currentProduct.copy(productName = "Piña reciente")
            inventoryReads.replaceInventory(nextBusiness, listOf(renamed))
            runCurrent()
            assertEquals(listOf(SUGAR_ID), viewModel.searchFor("PINA"))
            assertEquals(emptyList<ProductId>(), viewModel.searchFor("café"))
        }

    @Test
    fun `a diagnostic finishing after a stock update cannot attach outdated divergence alerts`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            inventoryReads.replaceInventory(BUSINESS_ID, listOf(sugar(), rice()))
            val pending = CompletableDeferred<InventoryDiagnosticReport>()
            inventoryReads.diagnoseHandler = { pending.await() }
            val viewModel = createViewModel()
            runCurrent()
            viewModel.onAction(InventoryContract.Action.RunDiagnostic)
            runCurrent()

            inventoryReads.replaceInventory(BUSINESS_ID, listOf(sugar(version = 5L), rice()))
            runCurrent()
            pending.complete(report(divergent = setOf(SUGAR_ID)))
            runCurrent()

            assertFalse(viewModel.uiState.value.isDiagnosing)
            assertNull(viewModel.uiState.value.diagnosticReport)
            assertFalse(InventoryDataAlert.PROJECTION_DIVERGENCE in viewModel.uiState.value.alertsOf(SUGAR_ID))
            assertEquals(5L, viewModel.uiState.value.allItems[0].positions.first().version)
        }

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
    fun `search matches product names only and is truncated and persisted`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            inventoryReads.replaceInventory(BUSINESS_ID, listOf(sugar(), rice()))
            val savedStateHandle = SavedStateHandle()
            val viewModel = createViewModel(savedStateHandle)
            runCurrent()

            assertEquals(listOf(SUGAR_ID, RICE_ID), viewModel.searchFor(""))
            assertEquals(listOf(SUGAR_ID), viewModel.searchFor("  AZÚCAR "))
            assertEquals(listOf(RICE_ID), viewModel.searchFor("arroz"))
            assertEquals(emptyList<ProductId>(), viewModel.searchFor("arz-9"))
            assertEquals(emptyList<ProductId>(), viewModel.searchFor("kg"))
            assertEquals(emptyList<ProductId>(), viewModel.searchFor("depósito"))
            assertEquals(emptyList<ProductId>(), viewModel.searchFor("al"))
            assertEquals(emptyList<ProductId>(), viewModel.searchFor("mostaza"))
            assertEquals(2, viewModel.uiState.value.allItems.size)

            assertEquals(emptyList<ProductId>(), viewModel.searchFor("z".repeat(260)))
            assertEquals("z".repeat(MAX_QUERY_LENGTH), viewModel.uiState.value.query)
            assertEquals("z".repeat(MAX_QUERY_LENGTH), savedStateHandle.get<String>(QUERY_KEY))
        }

    @Test
    fun `two character name fragments ignore accents and case while shorter queries show every product`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            inventoryReads.replaceInventory(BUSINESS_ID, listOf(sugar(), rice()))
            val viewModel = createViewModel()
            runCurrent()

            assertEquals(listOf(SUGAR_ID, RICE_ID), viewModel.searchFor("z"))
            assertEquals(listOf(SUGAR_ID), viewModel.searchFor("az"))
            assertEquals(listOf(SUGAR_ID), viewModel.searchFor("  AZ  "))
            assertEquals(listOf(SUGAR_ID), viewModel.searchFor("zu"))
            assertEquals(listOf(SUGAR_ID), viewModel.searchFor("ZÚ"))
            assertEquals(listOf(SUGAR_ID), viewModel.searchFor("zu\u0301"))
            assertEquals(listOf(RICE_ID), viewModel.searchFor("RR"))
            assertEquals(emptyList<ProductId>(), viewModel.searchFor("zz"))
            assertEquals(listOf(SUGAR_ID, RICE_ID), viewModel.searchFor(""))
            assertEquals(2, viewModel.uiState.value.allItems.size)
        }

    @Test
    fun `name search remains available while the inventory reader is active`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            inventoryReads.replaceInventory(BUSINESS_ID, listOf(sugar(), rice()))
            val viewModel = createViewModel()
            runCurrent()
            viewModel.onAction(InventoryContract.Action.BeginProductRegistration)
            runCurrent()

            assertEquals(InventoryContract.InputMode.SCANNER, viewModel.uiState.value.inputMode)
            assertEquals(listOf(SUGAR_ID), viewModel.searchFor("az"))
            assertEquals("az", viewModel.uiState.value.query)
            assertEquals(listOf(SUGAR_ID, RICE_ID), viewModel.searchFor(""))
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
        assertFalse(ready.copy(isSearchFocused = true).canRouteScannerInput)
        assertFalse(ready.copy(isDiagnosing = true).canRouteScannerInput)
        assertFalse(ready.copy(isBarcodeLookupRunning = true).canRouteScannerInput)
        assertFalse(ready.copy(productId = SUGAR_ID).canRouteScannerInput)
        assertFalse(
            ready.copy(failure = InventoryContract.Failure.LOAD_FAILED).canRouteScannerInput,
        )
        assertFalse(ready.copy(isSavingSalePrice = true).canRouteScannerInput)
        assertFalse(
            ready.copy(
                pendingDeletion = InventoryContract.PendingProductDeletion(
                    productId = SUGAR_ID,
                    businessId = BUSINESS_ID,
                    productName = "Azúcar rubia",
                    expectedVersion = 4L,
                ),
            ).canRouteScannerInput,
        )
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

    @Test
    fun `legacy archived filter and pending archive are ignored and cannot block registration scanner`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            val original = products.create(catalogProduct(version = 4L, barcode = "000123"))
            val exhausted = sugar().copy(positions = sugar().positions.map { it.copy(quantityOnHand = BigDecimal.ZERO) })
            val archived = rice().copy(alerts = setOf(InventoryDataAlert.ARCHIVED_PRODUCT))
            inventoryReads.replaceInventory(BUSINESS_ID, listOf(exhausted, archived))
            val repository = RecordingProductRepository(products)
            val handle = SavedStateHandle(mapOf(
                "inventory.productFilter" to "ARCHIVED",
                "inventory.productAction" to arrayListOf(SUGAR_ID.value, BUSINESS_ID.value, original.name, "ARCHIVE", "4"),
                "inventory.inputMode" to "SCANNER",
            ))
            val viewModel = createViewModel(handle, repository)
            runCurrent()
            assertEquals(listOf(exhausted), viewModel.uiState.value.items)
            assertEquals(listOf(exhausted, archived), viewModel.uiState.value.allItems)
            assertTrue(viewModel.uiState.value.canRouteScannerInput)
            assertTrue(viewModel.uiState.value.canStartProductAction)
            assertFalse(viewModel.uiState.value.isChangingProduct)
            assertNull(handle.get<Any>("inventory.productFilter"))
            assertNull(handle.get<Any>("inventory.productAction"))
            assertNull(viewModel.uiState.value.pendingDeletion)
            viewModel.onAction(InventoryContract.Action.ConfirmProductDeletion)
            runCurrent()
            assertTrue(repository.deletions.isEmpty())

            viewModel.effects.test {
                viewModel.onAction(InventoryContract.Action.BeginProductRegistration)
                runCurrent()
                assertTrue(viewModel.uiState.value.canRouteScannerInput)
                viewModel.onAction(InventoryContract.Action.BarcodeScanned("000123"))
                runCurrent()
                assertEquals(InventoryContract.Effect.OpenProductCreation("000123"), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(listOf(BUSINESS_ID to "000123"), repository.barcodeLookups)
            assertTrue(repository.archives.isEmpty())
            assertTrue(repository.restores.isEmpty())
            assertEquals(original, products.findById(SUGAR_ID))
        }

    @Test
    fun `zero stock remains visible through search reload and legacy state recreation`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            val exhausted = sugar().copy(positions = sugar().positions.map { it.copy(quantityOnHand = BigDecimal.ZERO) })
            val archivedRice = rice().copy(alerts = setOf(InventoryDataAlert.ARCHIVED_PRODUCT))
            inventoryReads.replaceInventory(BUSINESS_ID, listOf(exhausted, archivedRice))
            val handle = SavedStateHandle(mapOf(
                "inventory.productFilter" to "ARCHIVED",
                "inventory.productAction" to "malformed retired dialog",
                "inventory.query" to "az",
            ))
            val viewModel = createViewModel(handle)
            runCurrent()
            assertEquals(listOf(exhausted), viewModel.uiState.value.items)
            assertEquals(BigDecimal.ZERO, viewModel.uiState.value.items.single().totalQuantityOnHand)
            val restored = createViewModel(SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }))
            runCurrent()
            assertEquals(listOf(exhausted), restored.uiState.value.items)
            assertEquals(listOf(exhausted, archivedRice), restored.uiState.value.allItems)
            assertEquals("az", restored.uiState.value.query)
            assertEquals(emptyList<ProductId>(), restored.searchFor("arroz"))
            assertEquals(listOf(SUGAR_ID), restored.searchFor(""))
            restored.onAction(InventoryContract.Action.Load)
            runCurrent()
            assertEquals(listOf(exhausted), restored.uiState.value.items)
            assertEquals(emptyList<ProductId>(), restored.searchFor("arroz"))
            assertEquals(listOf(exhausted, archivedRice), restored.uiState.value.allItems)
        }

    @Test
    fun `edit resolves one product identity and blocks concurrent edit and scan only while reading`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            val original = products.create(catalogProduct(version = 4L))
            inventoryReads.replaceInventory(BUSINESS_ID, listOf(sugar()))
            val repository = RecordingProductRepository(products).apply { idGate = CompletableDeferred() }
            val viewModel = createViewModel(productRepository = repository)
            runCurrent()
            viewModel.onAction(InventoryContract.Action.InputModeChanged(InventoryContract.InputMode.SCANNER))
            runCurrent()
            viewModel.effects.test {
                viewModel.onAction(InventoryContract.Action.EditProduct(SUGAR_ID))
                viewModel.onAction(InventoryContract.Action.EditProduct(SUGAR_ID))
                runCurrent()
                assertEquals(listOf(SUGAR_ID), repository.idLookups)
                assertTrue(viewModel.uiState.value.isChangingProduct)
                assertFalse(viewModel.uiState.value.canRouteScannerInput)
                viewModel.onAction(InventoryContract.Action.BarcodeScanned("000123"))
                viewModel.onAction(InventoryContract.Action.BeginProductRegistration)
                runCurrent()
                assertTrue(repository.barcodeLookups.isEmpty())
                assertFalse(viewModel.uiState.value.canRouteScannerInput)
                repository.idGate!!.complete(Unit)
                runCurrent()
                assertEquals(InventoryContract.Effect.EditProduct(SUGAR_ID), awaitItem())
                assertFalse(viewModel.uiState.value.isChangingProduct)
                assertTrue(viewModel.uiState.value.canRouteScannerInput)
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(original, products.findById(SUGAR_ID))
            assertTrue(repository.archives.isEmpty())
            assertTrue(repository.restores.isEmpty())
        }

    @Test
    fun `changing business during an edit lookup cannot navigate to the previous product`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            val original = products.create(catalogProduct(version = 4L))
            inventoryReads.replaceInventory(BUSINESS_ID, listOf(sugar()))
            val repository = RecordingProductRepository(products).apply { idGate = CompletableDeferred() }
            val viewModel = createViewModel(productRepository = repository)
            runCurrent()
            viewModel.effects.test {
                viewModel.onAction(InventoryContract.Action.EditProduct(SUGAR_ID))
                runCurrent()
                configuration.completeOnboarding(BusinessId.from(UUID.randomUUID()),
                    AppConfiguration.DEFAULT_TAX_RATE, AppConfiguration.DEFAULT_COST_POLICY)
                repository.idGate!!.complete(Unit)
                runCurrent()
                assertEquals(InventoryContract.ProductActionFailure.BUSINESS_CHANGED, viewModel.uiState.value.productActionFailure)
                assertFalse(viewModel.uiState.value.isChangingProduct)
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(original, products.findById(SUGAR_ID))
        }

    @Test
    fun `edit rejects missing foreign replaced or concurrently archived products without mutations`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            val original = products.create(catalogProduct(version = 4L))
            inventoryReads.replaceInventory(BUSINESS_ID, listOf(sugar()))
            for (returned in listOf(null, original.copy(businessId = BusinessId.from(UUID.randomUUID())),
                original.copy(productId = RICE_ID), original.copy(status = CatalogStatus.ARCHIVED))) {
                val repository = object : ProductRepository by products {
                    override suspend fun findById(productId: ProductId): Product? = returned
                }
                val viewModel = createViewModel(productRepository = repository)
                runCurrent()
                viewModel.effects.test {
                    viewModel.onAction(InventoryContract.Action.EditProduct(SUGAR_ID))
                    runCurrent()
                    assertEquals(
                        if (returned?.status == CatalogStatus.ARCHIVED) InventoryContract.ProductActionFailure.STALE_PRODUCT
                        else InventoryContract.ProductActionFailure.PRODUCT_UNAVAILABLE,
                        viewModel.uiState.value.productActionFailure,
                    )
                    assertFalse(viewModel.uiState.value.isChangingProduct)
                    expectNoEvents()
                    cancelAndIgnoreRemainingEvents()
                }
            }
            assertEquals(original, products.findById(SUGAR_ID))
        }

    @Test
    fun `failed edit lookup releases controls and retries only after another edit action`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            val original = products.create(catalogProduct(version = 4L))
            inventoryReads.replaceInventory(BUSINESS_ID, listOf(sugar()))
            val repository = RecordingProductRepository(products).apply { failNextIdLookup = true }
            val viewModel = createViewModel(productRepository = repository)
            runCurrent()
            viewModel.onAction(InventoryContract.Action.EditProduct(SUGAR_ID))
            runCurrent()
            assertEquals(InventoryContract.ProductActionFailure.LOAD_FAILED, viewModel.uiState.value.productActionFailure)
            assertFalse(viewModel.uiState.value.isChangingProduct)
            assertEquals(listOf(SUGAR_ID), repository.idLookups)
            viewModel.onAction(InventoryContract.Action.DismissProductActionFailure)
            runCurrent()
            assertNull(viewModel.uiState.value.productActionFailure)
            viewModel.effects.test {
                viewModel.onAction(InventoryContract.Action.EditProduct(SUGAR_ID))
                runCurrent()
                assertEquals(InventoryContract.Effect.EditProduct(SUGAR_ID), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(listOf(SUGAR_ID, SUGAR_ID), repository.idLookups)
            assertEquals(original, products.findById(SUGAR_ID))
        }

    @Test
    fun `canceling deletion makes no writes and registration scanner resumes`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            val original = products.create(catalogProduct(version = 4L, barcode = "000123"))
            inventoryReads.replaceInventory(BUSINESS_ID, listOf(sugar().copy(positions = emptyList())))
            val repository = RecordingProductRepository(products)
            val viewModel = createViewModel(productRepository = repository)
            runCurrent()
            viewModel.onAction(InventoryContract.Action.BeginProductRegistration)
            runCurrent()
            assertTrue(viewModel.uiState.value.canRouteScannerInput)

            viewModel.effects.test {
                viewModel.onAction(InventoryContract.Action.DeleteProduct(SUGAR_ID))
                runCurrent()
                assertEquals(
                    InventoryContract.PendingProductDeletion(SUGAR_ID, BUSINESS_ID, original.name, 4L),
                    viewModel.uiState.value.pendingDeletion,
                )
                assertTrue(repository.deletions.isEmpty())
                assertFalse(viewModel.uiState.value.canRouteScannerInput)
                assertFalse(viewModel.uiState.value.canStartProductAction)
                viewModel.onAction(InventoryContract.Action.BarcodeScanned("000123"))
                viewModel.onAction(InventoryContract.Action.RegisterProductManual)
                viewModel.onAction(InventoryContract.Action.EditProduct(SUGAR_ID))
                runCurrent()
                assertTrue(repository.barcodeLookups.isEmpty())
                assertEquals(listOf(SUGAR_ID), repository.idLookups)
                expectNoEvents()

                viewModel.onAction(InventoryContract.Action.DismissProductDeletion)
                viewModel.onAction(InventoryContract.Action.ConfirmProductDeletion)
                runCurrent()
                assertNull(viewModel.uiState.value.pendingDeletion)
                assertTrue(viewModel.uiState.value.canRouteScannerInput)
                assertTrue(repository.deletions.isEmpty())
                assertEquals(original, products.findById(SUGAR_ID))
                viewModel.onAction(InventoryContract.Action.BarcodeScanned("000123"))
                runCurrent()
                assertEquals(InventoryContract.Effect.OpenProductCreation("000123"), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            assertTrue(repository.archives.isEmpty())
            assertTrue(repository.restores.isEmpty())
        }

    @Test
    fun `permanent deletion requires explicit confirmation and ignores repeated confirmation during save`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            val original = products.create(catalogProduct(version = 4L))
            inventoryReads.replaceInventory(BUSINESS_ID, listOf(sugar().copy(positions = emptyList())))
            val repository = RecordingProductRepository(products).apply { deletionGate = CompletableDeferred() }
            val viewModel = createViewModel(productRepository = repository)
            runCurrent()
            viewModel.onAction(InventoryContract.Action.DeleteProduct(SUGAR_ID))
            runCurrent()
            assertTrue(repository.deletions.isEmpty())
            assertEquals(original, products.findById(SUGAR_ID))

            viewModel.onAction(InventoryContract.Action.ConfirmProductDeletion)
            viewModel.onAction(InventoryContract.Action.ConfirmProductDeletion)
            runCurrent()
            assertEquals(listOf(Triple(BUSINESS_ID, SUGAR_ID, 4L)), repository.deletions)
            assertTrue(viewModel.uiState.value.isChangingProduct)
            assertEquals(original, products.findById(SUGAR_ID))
            viewModel.onAction(InventoryContract.Action.DismissProductDeletion)
            runCurrent()
            assertEquals(4L, viewModel.uiState.value.pendingDeletion?.expectedVersion)

            repository.deletionGate!!.complete(Unit)
            runCurrent()
            assertNull(products.findById(SUGAR_ID))
            assertNull(viewModel.uiState.value.pendingDeletion)
            assertNull(viewModel.uiState.value.productActionFailure)
            assertFalse(viewModel.uiState.value.isChangingProduct)
            assertTrue(repository.archives.isEmpty())
            assertTrue(repository.restores.isEmpty())
        }

    @Test
    fun `deletion retains the reviewed version and refuses a concurrent product edit`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            val original = products.create(catalogProduct(version = 4L))
            inventoryReads.replaceInventory(BUSINESS_ID, listOf(sugar().copy(positions = emptyList())))
            val repository = RecordingProductRepository(products)
            val viewModel = createViewModel(productRepository = repository)
            runCurrent()
            viewModel.onAction(InventoryContract.Action.DeleteProduct(SUGAR_ID))
            runCurrent()
            assertTrue(products.update(original.copy(name = "Azúcar actualizada")))
            val changed = requireNotNull(products.findById(SUGAR_ID))
            assertEquals(5L, changed.version)

            viewModel.onAction(InventoryContract.Action.ConfirmProductDeletion)
            runCurrent()
            assertEquals(listOf(Triple(BUSINESS_ID, SUGAR_ID, 4L)), repository.deletions)
            assertEquals(InventoryContract.ProductActionFailure.STALE_PRODUCT, viewModel.uiState.value.productActionFailure)
            assertEquals(4L, viewModel.uiState.value.pendingDeletion?.expectedVersion)
            assertEquals(changed, products.findById(SUGAR_ID))
            viewModel.onAction(InventoryContract.Action.ConfirmProductDeletion)
            runCurrent()
            assertEquals(1, repository.deletions.size)
            assertEquals(listOf(SUGAR_ID), repository.idLookups)
            assertTrue(repository.archives.isEmpty())
            assertTrue(repository.restores.isEmpty())
        }

    @Test
    fun `shared business and missing product refusals keep the product and require closing the review`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            val original = products.create(catalogProduct(version = 4L))
            inventoryReads.replaceInventory(BUSINESS_ID, listOf(sugar().copy(positions = emptyList())))
            for ((result, failure) in listOf(
                ProductDeletionResult.SHARED_BUSINESS to InventoryContract.ProductActionFailure.SHARED_BUSINESS,
                ProductDeletionResult.NOT_FOUND to InventoryContract.ProductActionFailure.PRODUCT_UNAVAILABLE,
            )) {
                val repository = RecordingProductRepository(products).apply { deletionResult = result }
                val viewModel = createViewModel(productRepository = repository)
                runCurrent()
                viewModel.onAction(InventoryContract.Action.DeleteProduct(SUGAR_ID))
                runCurrent()
                viewModel.onAction(InventoryContract.Action.ConfirmProductDeletion)
                runCurrent()
                assertEquals(failure, viewModel.uiState.value.productActionFailure)
                assertEquals(4L, viewModel.uiState.value.pendingDeletion?.expectedVersion)
                assertFalse(viewModel.uiState.value.isChangingProduct)
                assertEquals(original, products.findById(SUGAR_ID))
                viewModel.onAction(InventoryContract.Action.ConfirmProductDeletion)
                runCurrent()
                assertEquals(listOf(Triple(BUSINESS_ID, SUGAR_ID, 4L)), repository.deletions)
                assertTrue(repository.archives.isEmpty())
                assertTrue(repository.restores.isEmpty())
                viewModel.onAction(InventoryContract.Action.DismissProductDeletion)
                runCurrent()
                assertNull(viewModel.uiState.value.pendingDeletion)
                assertNull(viewModel.uiState.value.productActionFailure)
            }
        }

    @Test
    fun `business switch after review cannot delete the previous business product`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            val original = products.create(catalogProduct(version = 4L))
            inventoryReads.replaceInventory(BUSINESS_ID, listOf(sugar().copy(positions = emptyList())))
            val repository = RecordingProductRepository(products)
            val viewModel = createViewModel(productRepository = repository)
            runCurrent()
            viewModel.onAction(InventoryContract.Action.DeleteProduct(SUGAR_ID))
            runCurrent()
            configuration.completeOnboarding(
                BusinessId.from(UUID.randomUUID()),
                AppConfiguration.DEFAULT_TAX_RATE,
                AppConfiguration.DEFAULT_COST_POLICY,
            )
            runCurrent()
            viewModel.onAction(InventoryContract.Action.ConfirmProductDeletion)
            runCurrent()
            assertEquals(InventoryContract.ProductActionFailure.BUSINESS_CHANGED, viewModel.uiState.value.productActionFailure)
            assertTrue(repository.deletions.isEmpty())
            assertEquals(original, products.findById(SUGAR_ID))
            assertFalse(viewModel.uiState.value.isChangingProduct)
        }

    @Test
    fun `business switch during deletion lookup cannot authorize a later confirmation`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            val original = products.create(catalogProduct(version = 4L))
            inventoryReads.replaceInventory(BUSINESS_ID, listOf(sugar().copy(positions = emptyList())))
            val repository = RecordingProductRepository(products).apply { idGate = CompletableDeferred() }
            val viewModel = createViewModel(productRepository = repository)
            runCurrent()
            viewModel.onAction(InventoryContract.Action.DeleteProduct(SUGAR_ID))
            runCurrent()
            assertTrue(viewModel.uiState.value.isChangingProduct)
            configuration.completeOnboarding(
                BusinessId.from(UUID.randomUUID()),
                AppConfiguration.DEFAULT_TAX_RATE,
                AppConfiguration.DEFAULT_COST_POLICY,
            )
            repository.idGate!!.complete(Unit)
            runCurrent()
            assertEquals(InventoryContract.ProductActionFailure.BUSINESS_CHANGED, viewModel.uiState.value.productActionFailure)
            assertNull(viewModel.uiState.value.pendingDeletion?.expectedVersion)
            assertFalse(viewModel.uiState.value.isChangingProduct)
            viewModel.onAction(InventoryContract.Action.ConfirmProductDeletion)
            runCurrent()
            assertEquals(listOf(SUGAR_ID), repository.idLookups)
            assertTrue(repository.deletions.isEmpty())
            assertEquals(original, products.findById(SUGAR_ID))
        }

    @Test
    fun `failed deletion lookup retries the review and still needs separate confirmation to write`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            val original = products.create(catalogProduct(version = 4L))
            inventoryReads.replaceInventory(BUSINESS_ID, listOf(sugar().copy(positions = emptyList())))
            val repository = RecordingProductRepository(products).apply { failNextIdLookup = true }
            val viewModel = createViewModel(productRepository = repository)
            runCurrent()
            viewModel.onAction(InventoryContract.Action.DeleteProduct(SUGAR_ID))
            runCurrent()
            assertEquals(InventoryContract.ProductActionFailure.LOAD_FAILED, viewModel.uiState.value.productActionFailure)
            assertNull(viewModel.uiState.value.pendingDeletion?.expectedVersion)
            assertTrue(repository.deletions.isEmpty())

            viewModel.onAction(InventoryContract.Action.ConfirmProductDeletion)
            runCurrent()
            assertEquals(listOf(SUGAR_ID, SUGAR_ID), repository.idLookups)
            assertEquals(4L, viewModel.uiState.value.pendingDeletion?.expectedVersion)
            assertNull(viewModel.uiState.value.productActionFailure)
            assertTrue(repository.deletions.isEmpty())
            assertEquals(original, products.findById(SUGAR_ID))

            viewModel.onAction(InventoryContract.Action.ConfirmProductDeletion)
            runCurrent()
            assertEquals(listOf(Triple(BUSINESS_ID, SUGAR_ID, 4L)), repository.deletions)
            assertNull(products.findById(SUGAR_ID))
        }

    @Test
    fun `failed delete retries the same captured version without silently reviewing newer metadata`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            val original = products.create(catalogProduct(version = 4L))
            inventoryReads.replaceInventory(BUSINESS_ID, listOf(sugar().copy(positions = emptyList())))
            val repository = RecordingProductRepository(products)
            val viewModel = createViewModel(productRepository = repository)
            runCurrent()
            viewModel.onAction(InventoryContract.Action.DeleteProduct(SUGAR_ID))
            runCurrent()
            products.nextFailure = StorageError.Unavailable
            viewModel.onAction(InventoryContract.Action.ConfirmProductDeletion)
            runCurrent()
            assertEquals(InventoryContract.ProductActionFailure.SAVE_FAILED, viewModel.uiState.value.productActionFailure)
            assertEquals(4L, viewModel.uiState.value.pendingDeletion?.expectedVersion)
            assertEquals(original, products.findById(SUGAR_ID))
            assertTrue(products.update(original.copy(name = "Nombre corregido mientras se reintenta")))
            val changed = products.findById(SUGAR_ID)
            viewModel.onAction(InventoryContract.Action.ConfirmProductDeletion)
            runCurrent()
            assertEquals(List(2) { Triple(BUSINESS_ID, SUGAR_ID, 4L) }, repository.deletions)
            assertEquals(listOf(SUGAR_ID), repository.idLookups)
            assertEquals(InventoryContract.ProductActionFailure.STALE_PRODUCT, viewModel.uiState.value.productActionFailure)
            assertEquals(changed, products.findById(SUGAR_ID))
        }

    @Test
    fun `process recreation does not restore or execute a pending permanent deletion`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            val original = products.create(catalogProduct(version = 4L))
            inventoryReads.replaceInventory(BUSINESS_ID, listOf(sugar().copy(positions = emptyList())))
            val repository = RecordingProductRepository(products)
            val handle = SavedStateHandle(mapOf("inventory.inputMode" to "SCANNER"))
            val viewModel = createViewModel(handle, repository)
            runCurrent()
            viewModel.onAction(InventoryContract.Action.DeleteProduct(SUGAR_ID))
            runCurrent()
            assertEquals(4L, viewModel.uiState.value.pendingDeletion?.expectedVersion)
            val restored = createViewModel(
                SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }),
                repository,
            )
            runCurrent()
            assertNull(restored.uiState.value.pendingDeletion)
            assertTrue(restored.uiState.value.canRouteScannerInput)
            restored.onAction(InventoryContract.Action.ConfirmProductDeletion)
            runCurrent()
            assertTrue(repository.deletions.isEmpty())
            assertEquals(listOf(SUGAR_ID), repository.idLookups)
            assertEquals(original, products.findById(SUGAR_ID))
        }

    private fun InventoryContract.State.alertsOf(productId: ProductId): Set<InventoryDataAlert> =
        allItems.single { it.productId == productId }.allAlerts

    private fun InventoryViewModel.searchFor(query: String): List<ProductId> {
        onAction(InventoryContract.Action.SearchChanged(query))
        mainDispatcherRule.scheduler.runCurrent()
        return uiState.value.items.map { it.productId }
    }

    @Test
    fun `one deletion confirmation removes a product with history without another question`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            products.create(catalogProduct(version = 4L))
            val snapshot = sugar()
            inventoryReads.replaceInventory(BUSINESS_ID, listOf(snapshot))
            val repository = RecordingProductRepository(products).apply { deletionResult = ProductDeletionResult.HAS_HISTORY }
            val viewModel = createViewModel(productRepository = repository)
            runCurrent()
            viewModel.onAction(InventoryContract.Action.DeleteProduct(SUGAR_ID))
            runCurrent()
            assertTrue(repository.archives.isEmpty())
            viewModel.onAction(InventoryContract.Action.ConfirmProductDeletion)
            runCurrent()
            assertEquals(listOf(Triple(BUSINESS_ID, SUGAR_ID, 4L)), repository.deletions)
            assertEquals(listOf(Triple(BUSINESS_ID, SUGAR_ID, 4L)), repository.archives)
            assertNull(viewModel.uiState.value.pendingDeletion)
            assertNull(viewModel.uiState.value.productActionFailure)
            assertEquals(CatalogStatus.ARCHIVED, products.findById(SUGAR_ID)?.status)
            val historicalSnapshot = snapshot.copy(alerts = setOf(InventoryDataAlert.ARCHIVED_PRODUCT))
            inventoryReads.replaceInventory(BUSINESS_ID, listOf(historicalSnapshot))
            runCurrent()
            assertTrue(viewModel.uiState.value.items.isEmpty())
            assertEquals(snapshot.positions, viewModel.uiState.value.allItems.single().positions)
            val recreated = createViewModel(productRepository = repository)
            runCurrent()
            assertTrue(recreated.uiState.value.items.isEmpty())
            assertTrue(repository.restores.isEmpty())
        }

    @Test
    fun `deletion with stock preserves balances and performs one write despite repeated confirmation`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            products.create(catalogProduct(version = 4L, barcode = "000123"))
            val snapshot = sugar()
            inventoryReads.replaceInventory(BUSINESS_ID, listOf(snapshot))
            val repository = RecordingProductRepository(products).apply {
                deletionResult = ProductDeletionResult.HAS_STOCK
                statusGate = CompletableDeferred()
            }
            val viewModel = createViewModel(productRepository = repository)
            runCurrent()
            viewModel.onAction(InventoryContract.Action.DeleteProduct(SUGAR_ID))
            runCurrent()
            viewModel.onAction(InventoryContract.Action.ConfirmProductDeletion)
            viewModel.onAction(InventoryContract.Action.ConfirmProductDeletion)
            runCurrent()
            assertEquals(listOf(Triple(BUSINESS_ID, SUGAR_ID, 4L)), repository.archives)
            assertEquals(1, repository.deletions.size)
            assertTrue(viewModel.uiState.value.isChangingProduct)
            viewModel.onAction(InventoryContract.Action.DismissProductDeletion)
            runCurrent()
            assertNotNull(viewModel.uiState.value.pendingDeletion)
            repository.statusGate!!.complete(Unit)
            runCurrent()
            assertEquals(CatalogStatus.ARCHIVED, products.findById(SUGAR_ID)?.status)
            assertEquals(5L, products.findById(SUGAR_ID)?.version)
            assertNull(viewModel.uiState.value.pendingDeletion)
            assertNull(viewModel.uiState.value.productActionFailure)
            assertEquals(listOf(snapshot), viewModel.uiState.value.allItems)
            assertTrue(repository.restores.isEmpty())
        }

    @Test
    fun `automatic historical deletion rejects edits and business changes between persistence calls`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            val original = products.create(catalogProduct(version = 4L))
            inventoryReads.replaceInventory(BUSINESS_ID, listOf(sugar()))
            val repository = RecordingProductRepository(products).apply {
                deletionResult = ProductDeletionResult.HAS_HISTORY
                deletionGate = CompletableDeferred()
            }
            val viewModel = createViewModel(productRepository = repository)
            runCurrent()
            viewModel.onAction(InventoryContract.Action.DeleteProduct(SUGAR_ID))
            runCurrent()
            viewModel.onAction(InventoryContract.Action.ConfirmProductDeletion)
            runCurrent()
            assertTrue(products.update(original.copy(name = "Nombre corregido")))
            repository.deletionGate!!.complete(Unit)
            runCurrent()
            assertEquals(InventoryContract.ProductActionFailure.STALE_PRODUCT, viewModel.uiState.value.productActionFailure)
            assertEquals(listOf(Triple(BUSINESS_ID, SUGAR_ID, 4L)), repository.archives)
            assertEquals(CatalogStatus.ACTIVE, products.findById(SUGAR_ID)?.status)
            viewModel.onAction(InventoryContract.Action.DismissProductDeletion)
            runCurrent()
            repository.deletionGate = CompletableDeferred()
            viewModel.onAction(InventoryContract.Action.DeleteProduct(SUGAR_ID))
            runCurrent()
            viewModel.onAction(InventoryContract.Action.ConfirmProductDeletion)
            runCurrent()
            configuration.completeOnboarding(BusinessId.from(UUID.randomUUID()), AppConfiguration.DEFAULT_TAX_RATE, AppConfiguration.DEFAULT_COST_POLICY)
            runCurrent()
            repository.deletionGate!!.complete(Unit)
            runCurrent()
            assertEquals(InventoryContract.ProductActionFailure.BUSINESS_CHANGED, viewModel.uiState.value.productActionFailure)
            assertEquals(1, repository.archives.size)
            assertEquals(CatalogStatus.ACTIVE, products.findById(SUGAR_ID)?.status)
        }

    @Test
    fun `scanning a retired product never restores it or changes its stock`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            val retired = products.create(catalogProduct(version = 4L, barcode = "000123").copy(status = CatalogStatus.ARCHIVED))
            val snapshot = sugar().copy(alerts = setOf(InventoryDataAlert.ARCHIVED_PRODUCT))
            inventoryReads.replaceInventory(BUSINESS_ID, listOf(snapshot))
            val repository = RecordingProductRepository(products)
            val viewModel = createViewModel(productRepository = repository)
            runCurrent()
            assertFalse(viewModel.uiState.value.canRouteScannerInput)
            viewModel.onAction(InventoryContract.Action.BeginProductRegistration)
            runCurrent()
            viewModel.effects.test {
                viewModel.onAction(InventoryContract.Action.BarcodeScanned("000123"))
                runCurrent()
                assertEquals(InventoryContract.Effect.OpenProductCreation("000123"), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            assertTrue(repository.restores.isEmpty())
            assertTrue(repository.archives.isEmpty())
            assertEquals(retired, products.findById(SUGAR_ID))
            assertEquals(listOf(snapshot), viewModel.uiState.value.allItems)
            assertTrue(viewModel.uiState.value.items.isEmpty())
        }

    private fun createViewModel(
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
        productRepository: ProductRepository = products,
        inventoryRepository: InventoryReadRepository = inventoryReads,
        dispatcherProvider: DispatcherProvider = dispatchers,
    ): InventoryViewModel = InventoryViewModel(
        savedStateHandle = savedStateHandle,
        observeInventory = ObserveInventoryUseCase(configuration, inventoryRepository),
        observeProduct = ObserveInventoryProductUseCase(configuration, inventoryRepository),
        diagnoseInventory = DiagnoseInventoryUseCase(configuration, inventoryRepository),
        observeProductProfits = ObserveProductProfitsUseCase(configuration, productProfits),
        updateProductSalePrice = UpdateProductSalePriceUseCase(configuration, products),
        products = productRepository,
        configuration = configuration,
        dispatcherProvider = dispatcherProvider,
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
        var idGate: CompletableDeferred<Unit>? = null
        var failNextIdLookup: Boolean = false
        val idLookups = mutableListOf<ProductId>()
        val archives = mutableListOf<Triple<BusinessId, ProductId, Long>>()
        val restores = mutableListOf<Triple<BusinessId, ProductId, Long>>()
        val deletions = mutableListOf<Triple<BusinessId, ProductId, Long>>()
        var deletionResult: ProductDeletionResult? = null
        var deletionGate: CompletableDeferred<Unit>? = null
        var statusGate: CompletableDeferred<Unit>? = null

        override suspend fun deletePermanently(
            businessId: BusinessId,
            productId: ProductId,
            expectedVersion: Long,
        ): ProductDeletionResult {
            deletions += Triple(businessId, productId, expectedVersion)
            deletionGate?.await()
            return deletionResult ?: delegate.deletePermanently(businessId, productId, expectedVersion)
        }

        override suspend fun archive(businessId: BusinessId, productId: ProductId, expectedVersion: Long): Boolean {
            archives += Triple(businessId, productId, expectedVersion)
            statusGate?.await()
            return delegate.archive(businessId, productId, expectedVersion)
        }

        override suspend fun restore(businessId: BusinessId, productId: ProductId, expectedVersion: Long): Boolean {
            restores += Triple(businessId, productId, expectedVersion)
            statusGate?.await()
            return delegate.restore(businessId, productId, expectedVersion)
        }

        override suspend fun findById(productId: ProductId): Product? {
            idLookups += productId
            idGate?.await()
            if (failNextIdLookup) {
                failNextIdLookup = false
                throw StorageException(StorageError.Unavailable)
            }
            return delegate.findById(productId)
        }

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

private class CountingInventoryList(
    private val entries: List<InventoryReadItem>,
) : AbstractList<InventoryReadItem>() {
    var reads = 0
    override val size: Int get() = entries.size
    override fun get(index: Int): InventoryReadItem {
        reads++
        return entries[index]
    }
}

/** Retiene sólo Default; Main e IO siguen usando el scheduler habitual de la prueba. */
private class HeldInventoryDispatcher(
    private val delegate: CoroutineDispatcher,
) : CoroutineDispatcher() {
    var hold = false
    private val pending = ArrayDeque<Pair<CoroutineContext, Runnable>>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (hold) pending.addLast(context to block) else delegate.dispatch(context, block)
    }

    fun releaseNext() {
        val (context, block) = pending.removeFirst()
        delegate.dispatch(context, block)
    }

    fun releaseAll() {
        hold = false
        while (pending.isNotEmpty()) releaseNext()
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
