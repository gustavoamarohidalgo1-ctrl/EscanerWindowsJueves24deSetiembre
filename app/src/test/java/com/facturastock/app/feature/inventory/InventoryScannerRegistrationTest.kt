package com.facturastock.app.feature.inventory

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.facturastock.app.core.input.KeyboardWedgeReadError
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.ProductProfit
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.repository.ProductProfitRepository
import com.facturastock.app.domain.repository.ProductRepository
import com.facturastock.app.domain.usecase.DiagnoseInventoryUseCase
import com.facturastock.app.domain.usecase.ObserveInventoryProductUseCase
import com.facturastock.app.domain.usecase.ObserveInventoryUseCase
import com.facturastock.app.domain.usecase.ObserveProductProfitsUseCase
import com.facturastock.app.domain.usecase.UpdateProductSalePriceUseCase
import com.facturastock.app.testing.FakeAppConfigurationRepository
import com.facturastock.app.testing.FakeInventoryReadRepository
import com.facturastock.app.testing.FakeProductRepository
import com.facturastock.app.testing.MainDispatcherRule
import com.facturastock.app.testing.TestDispatcherProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * En registro, el lector abre el formulario del código tanto si es nuevo como si ya existe;
 * el catálogo resuelve la identidad. En consulta, un producto existente sigue abriendo detalle.
 * Ningún producto ni movimiento se crea de forma implícita desde el escaneo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InventoryScannerRegistrationTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val configuration = FakeAppConfigurationRepository()
    private val inventoryReads = FakeInventoryReadRepository()
    private val products = FakeProductRepository(AppClock { UPDATED_AT })
    private val productProfits = FakeProductProfitRepository()
    private val dispatchers = TestDispatcherProvider(main = mainDispatcherRule.dispatcher)

    @Test
    fun `an unmatched scan is remembered and registers with the scanned code`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            val viewModel = createViewModel()
            viewModel.onAction(
                InventoryContract.Action.InputModeChanged(InventoryContract.InputMode.SCANNER),
            )
            runCurrent()

            viewModel.effects.test {
                viewModel.onAction(InventoryContract.Action.BarcodeScanned(UNMATCHED_BARCODE))
                runCurrent()

                expectNoEvents()
                assertEquals(
                    InventoryContract.ScannerFailure.BARCODE_NOT_FOUND,
                    viewModel.uiState.value.scannerFailure,
                )
                assertEquals(UNMATCHED_BARCODE, viewModel.uiState.value.lastUnmatchedBarcode)

                viewModel.onAction(InventoryContract.Action.RegisterScannedBarcode)
                runCurrent()
                assertEquals(
                    InventoryContract.Effect.OpenProductCreation(UNMATCHED_BARCODE),
                    awaitItem(),
                )
            }
        }

    @Test
    fun `a matched scan clears the remembered code`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            products.create(catalogProduct(barcode = MATCHED_BARCODE))
            val viewModel = createViewModel()
            viewModel.onAction(
                InventoryContract.Action.InputModeChanged(InventoryContract.InputMode.SCANNER),
            )
            runCurrent()

            viewModel.effects.test {
                viewModel.onAction(InventoryContract.Action.BarcodeScanned(MATCHED_BARCODE))
                runCurrent()

                assertEquals(
                    InventoryContract.Effect.OpenProduct(PRODUCT_ID),
                    awaitItem(),
                )
                assertNull(viewModel.uiState.value.lastUnmatchedBarcode)
            }
        }

    @Test
    fun `focusing product search pauses the reader and releasing focus preserves the query`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            products.create(catalogProduct(MATCHED_BARCODE))
            val controlled = ControlledProductRepository(products)
            val viewModel = createViewModel(productRepository = controlled)
            viewModel.onAction(InventoryContract.Action.BeginProductRegistration)
            runCurrent()
            assertTrue(viewModel.uiState.value.canRouteScannerInput)

            viewModel.effects.test {
                viewModel.onAction(InventoryContract.Action.SearchFocusChanged(true))
                viewModel.onAction(InventoryContract.Action.SearchChanged("az"))
                runCurrent()

                assertTrue(viewModel.uiState.value.isSearchFocused)
                assertFalse(viewModel.uiState.value.canRouteScannerInput)
                assertEquals("az", viewModel.uiState.value.query)
                viewModel.onAction(InventoryContract.Action.BarcodeScanned(MATCHED_BARCODE))
                runCurrent()
                assertTrue(controlled.pending.isEmpty())
                expectNoEvents()

                viewModel.onAction(InventoryContract.Action.SearchFocusChanged(false))
                runCurrent()
                assertFalse(viewModel.uiState.value.isSearchFocused)
                assertTrue(viewModel.uiState.value.canRouteScannerInput)
                assertEquals("az", viewModel.uiState.value.query)

                viewModel.onAction(InventoryContract.Action.BarcodeScanned(MATCHED_BARCODE))
                runCurrent()
                assertEquals(1, controlled.pending.size)
                controlled.pending.single().complete(Unit)
                runCurrent()
                assertEquals(InventoryContract.Effect.OpenProductCreation(MATCHED_BARCODE), awaitItem())
                expectNoEvents()
            }
        }

    @Test
    fun `manual registration opens the empty product form`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            val viewModel = createViewModel()

            viewModel.effects.test {
                viewModel.onAction(InventoryContract.Action.RegisterProductManual)
                runCurrent()

                assertEquals(
                    InventoryContract.Effect.OpenProductCreation(null),
                    awaitItem(),
                )
            }
        }

    @Test
    fun `product registration opens the scanned form once without creating a product`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            val viewModel = createViewModel()
            viewModel.onAction(InventoryContract.Action.BeginProductRegistration)
            runCurrent()
            val code = "0007790001112223"

            viewModel.effects.test {
                viewModel.onAction(InventoryContract.Action.BarcodeScanned(code))
                runCurrent()
                assertEquals(InventoryContract.Effect.OpenProductCreation(code), awaitItem())
                assertNull(products.findByBarcode(BUSINESS_ID, code))

                viewModel.onAction(InventoryContract.Action.BarcodeScanned(code))
                runCurrent()
                expectNoEvents()

                viewModel.onAction(InventoryContract.Action.EndProductRegistration)
                runCurrent()
                viewModel.onAction(InventoryContract.Action.BeginProductRegistration)
                runCurrent()
                viewModel.onAction(InventoryContract.Action.BarcodeScanned(code))
                runCurrent()
                assertEquals(InventoryContract.Effect.OpenProductCreation(code), awaitItem())
            }
        }

    @Test
    fun `product registration opens the existing barcode editor once without mutating the product`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            val existing = products.create(catalogProduct(MATCHED_BARCODE))
            val viewModel = createViewModel()
            viewModel.onAction(InventoryContract.Action.BeginProductRegistration)
            runCurrent()

            viewModel.effects.test {
                viewModel.onAction(InventoryContract.Action.BarcodeScanned(MATCHED_BARCODE))
                runCurrent()
                assertEquals(InventoryContract.Effect.OpenProductCreation(MATCHED_BARCODE), awaitItem())
                assertEquals(existing, products.findByBarcode(BUSINESS_ID, MATCHED_BARCODE))
                assertTrue(viewModel.uiState.value.registrationNavigationPending)
                assertFalse(viewModel.uiState.value.canRouteScannerInput)

                viewModel.onAction(InventoryContract.Action.BarcodeScanned(MATCHED_BARCODE))
                runCurrent()
                expectNoEvents()
            }
        }

    @Test
    fun `an archived barcode opens the registration form without restoring or duplicating it`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            val archived = products.create(catalogProduct(MATCHED_BARCODE).copy(status = CatalogStatus.ARCHIVED))
            val viewModel = createViewModel()
            viewModel.onAction(InventoryContract.Action.BeginProductRegistration)
            runCurrent()

            viewModel.effects.test {
                viewModel.onAction(InventoryContract.Action.BarcodeScanned(MATCHED_BARCODE))
                runCurrent()
                assertEquals(InventoryContract.Effect.OpenProductCreation(MATCHED_BARCODE), awaitItem())
                assertEquals(archived, products.findByBarcode(BUSINESS_ID, MATCHED_BARCODE))
                assertTrue(viewModel.uiState.value.registrationNavigationPending)
                assertNull(viewModel.uiState.value.scannerFailure)
                expectNoEvents()
            }
        }

    @Test
    fun `leaving registration cancels a delayed lookup and never navigates on its completion`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            products.create(catalogProduct(MATCHED_BARCODE))
            val controlled = ControlledProductRepository(products)
            val viewModel = createViewModel(productRepository = controlled)
            viewModel.onAction(InventoryContract.Action.BeginProductRegistration)
            runCurrent()

            viewModel.effects.test {
                viewModel.onAction(InventoryContract.Action.BarcodeScanned(MATCHED_BARCODE))
                runCurrent()
                assertTrue(viewModel.uiState.value.isBarcodeLookupRunning)
                viewModel.onAction(InventoryContract.Action.EndProductRegistration)
                runCurrent()
                assertFalse(viewModel.uiState.value.isRegisteringProducts)
                assertFalse(viewModel.uiState.value.canRouteScannerInput)

                controlled.pending.single().complete(Unit)
                runCurrent()
                expectNoEvents()
                assertFalse(viewModel.uiState.value.isBarcodeLookupRunning)
                assertFalse(viewModel.uiState.value.registrationNavigationPending)
            }
        }

    @Test
    fun `an old cancellation cannot unlock a new lookup after returning to registration`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            val controlled = ControlledProductRepository(products)
            val viewModel = createViewModel(productRepository = controlled)
            viewModel.onAction(InventoryContract.Action.BeginProductRegistration)
            runCurrent()

            viewModel.effects.test {
                viewModel.onAction(InventoryContract.Action.BarcodeScanned(UNMATCHED_BARCODE))
                runCurrent()
                viewModel.onAction(InventoryContract.Action.EndProductRegistration)
                runCurrent()
                viewModel.onAction(InventoryContract.Action.BeginProductRegistration)
                runCurrent()
                viewModel.onAction(InventoryContract.Action.BarcodeScanned(MATCHED_BARCODE))
                runCurrent()
                assertEquals(2, controlled.pending.size)

                controlled.pending.first().complete(Unit)
                runCurrent()
                assertTrue(viewModel.uiState.value.isBarcodeLookupRunning)
                assertFalse(viewModel.uiState.value.canRouteScannerInput)
                expectNoEvents()

                viewModel.onAction(InventoryContract.Action.BarcodeScanned("0001234567895"))
                runCurrent()
                assertEquals(2, controlled.pending.size)
                controlled.pending.last().complete(Unit)
                runCurrent()
                assertEquals(InventoryContract.Effect.OpenProductCreation(MATCHED_BARCODE), awaitItem())
                assertTrue(viewModel.uiState.value.registrationNavigationPending)

                // Un segundo ON_RESUME sin salida no libera una navegación ya solicitada.
                viewModel.onAction(InventoryContract.Action.BeginProductRegistration)
                runCurrent()
                assertTrue(viewModel.uiState.value.registrationNavigationPending)
                assertFalse(viewModel.uiState.value.canRouteScannerInput)
            }
        }

    @Test
    fun `registration resets a restored profit section and filter before enabling the reader`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            val handle =
                SavedStateHandle(
                    mapOf(
                        "inventory.section" to InventoryContract.ListSection.ESTIMATED_PROFIT.name,
                        "inventory.query" to "arroz",
                    ),
                )
            val viewModel = createViewModel(savedStateHandle = handle)
            runCurrent()
            viewModel.onAction(InventoryContract.Action.BeginProductRegistration)
            runCurrent()

            assertEquals(InventoryContract.ListSection.STOCK, viewModel.uiState.value.section)
            assertEquals("", viewModel.uiState.value.query)
            assertTrue(viewModel.uiState.value.canRouteScannerInput)
            assertEquals(InventoryContract.ListSection.STOCK.name, handle.get<String>("inventory.section"))
        }

    @Test
    fun `failed lookup retries the exact existing barcode and opens its registration form`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            val code = "0007790001112223"
            val existing = products.create(catalogProduct(code))
            val viewModel = createViewModel()
            viewModel.onAction(InventoryContract.Action.BeginProductRegistration)
            runCurrent()
            products.nextFailure = StorageError.Unavailable

            viewModel.effects.test {
                viewModel.onAction(InventoryContract.Action.BarcodeScanned(code))
                runCurrent()
                assertEquals(InventoryContract.ScannerFailure.LOOKUP_FAILED, viewModel.uiState.value.scannerFailure)
                expectNoEvents()

                viewModel.onAction(InventoryContract.Action.Retry)
                runCurrent()
                assertEquals(InventoryContract.Effect.OpenProductCreation(code), awaitItem())
                assertEquals(existing, products.findByBarcode(BUSINESS_ID, code))
            }
        }

    @Test
    fun `reset clears hardware errors without writes and permits the next barcode`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            val existing = products.create(catalogProduct(MATCHED_BARCODE))
            val viewModel = createViewModel()
            viewModel.onAction(InventoryContract.Action.BeginProductRegistration)
            runCurrent()

            viewModel.effects.test {
                listOf(
                    KeyboardWedgeReadError.INCOMPLETE to InventoryContract.ScannerFailure.INCOMPLETE_BARCODE,
                    KeyboardWedgeReadError.TOO_LONG to InventoryContract.ScannerFailure.BARCODE_TOO_LONG,
                    KeyboardWedgeReadError.INVALID_CHARACTER to InventoryContract.ScannerFailure.INVALID_BARCODE,
                ).forEach { (error, expected) ->
                    viewModel.onAction(InventoryContract.Action.ScannerReadFailed(error))
                    runCurrent()
                    assertEquals(expected, viewModel.uiState.value.scannerFailure)
                    assertTrue(viewModel.uiState.value.canRouteScannerInput)
                    expectNoEvents()

                    viewModel.onAction(InventoryContract.Action.ScannerReadReset)
                    runCurrent()
                    assertNull(viewModel.uiState.value.scannerFailure)
                    assertNull(viewModel.uiState.value.lastUnmatchedBarcode)
                    assertTrue(viewModel.uiState.value.canRouteScannerInput)
                    assertEquals(existing, products.findByBarcode(BUSINESS_ID, MATCHED_BARCODE))
                    expectNoEvents()
                }
                viewModel.onAction(InventoryContract.Action.BarcodeScanned(MATCHED_BARCODE))
                runCurrent()
                assertEquals(InventoryContract.Effect.OpenProductCreation(MATCHED_BARCODE), awaitItem())
                assertEquals(existing, products.findByBarcode(BUSINESS_ID, MATCHED_BARCODE))
            }
        }

    @Test
    fun `reset forgets an unmatched barcode instead of registering it later`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            val viewModel = createViewModel()
            viewModel.onAction(InventoryContract.Action.InputModeChanged(InventoryContract.InputMode.SCANNER))
            runCurrent()

            viewModel.effects.test {
                viewModel.onAction(InventoryContract.Action.BarcodeScanned(UNMATCHED_BARCODE))
                runCurrent()
                assertEquals(UNMATCHED_BARCODE, viewModel.uiState.value.lastUnmatchedBarcode)

                viewModel.onAction(InventoryContract.Action.ScannerReadReset)
                runCurrent()
                assertNull(viewModel.uiState.value.lastUnmatchedBarcode)
                assertNull(viewModel.uiState.value.scannerFailure)
                viewModel.onAction(InventoryContract.Action.RegisterScannedBarcode)
                runCurrent()
                expectNoEvents()
                assertNull(products.findByBarcode(BUSINESS_ID, UNMATCHED_BARCODE))
            }
        }

    @Test
    fun `reset during a lookup preserves the pending operation and its single result`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            val existing = products.create(catalogProduct(MATCHED_BARCODE))
            val controlled = ControlledProductRepository(products)
            val viewModel = createViewModel(productRepository = controlled)
            viewModel.onAction(InventoryContract.Action.BeginProductRegistration)
            runCurrent()

            viewModel.effects.test {
                viewModel.onAction(InventoryContract.Action.BarcodeScanned(MATCHED_BARCODE))
                runCurrent()
                val pendingState = viewModel.uiState.value
                assertTrue(pendingState.isBarcodeLookupRunning)

                viewModel.onAction(InventoryContract.Action.ScannerReadReset)
                runCurrent()
                assertEquals(pendingState, viewModel.uiState.value)
                assertEquals(1, controlled.pending.size)
                expectNoEvents()

                controlled.pending.single().complete(Unit)
                runCurrent()
                assertEquals(InventoryContract.Effect.OpenProductCreation(MATCHED_BARCODE), awaitItem())
                assertEquals(existing, products.findByBarcode(BUSINESS_ID, MATCHED_BARCODE))
                assertTrue(viewModel.uiState.value.registrationNavigationPending)

                viewModel.onAction(InventoryContract.Action.ScannerReadReset)
                runCurrent()
                assertTrue(viewModel.uiState.value.registrationNavigationPending)
                assertFalse(viewModel.uiState.value.canRouteScannerInput)
                expectNoEvents()
            }
        }

    @Test
    fun `a lookup finishing after the active business changes cannot open the previous product`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activate()
            products.create(catalogProduct(MATCHED_BARCODE))
            val controlled = ControlledProductRepository(products)
            val viewModel = createViewModel(productRepository = controlled)
            viewModel.onAction(InventoryContract.Action.BeginProductRegistration)
            runCurrent()

            viewModel.effects.test {
                viewModel.onAction(InventoryContract.Action.BarcodeScanned(MATCHED_BARCODE))
                runCurrent()
                configuration.completeOnboarding(
                    BusinessId.from(UUID.fromString("00000000-0000-0000-0000-000000000902")),
                    AppConfiguration.DEFAULT_TAX_RATE,
                    AppConfiguration.DEFAULT_COST_POLICY,
                )
                controlled.pending.single().complete(Unit)
                runCurrent()
                expectNoEvents()
                assertFalse(viewModel.uiState.value.isBarcodeLookupRunning)
                assertEquals(InventoryContract.ScannerFailure.LOOKUP_FAILED, viewModel.uiState.value.scannerFailure)
            }
        }

    private suspend fun activate() {
        configuration.completeOnboarding(
            BUSINESS_ID,
            AppConfiguration.DEFAULT_TAX_RATE,
            AppConfiguration.DEFAULT_COST_POLICY,
        )
    }

    private fun createViewModel(
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
        productRepository: ProductRepository = products,
    ): InventoryViewModel =
        InventoryViewModel(
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

    private fun catalogProduct(barcode: String): Product =
        Product(
            productId = PRODUCT_ID,
            businessId = BUSINESS_ID,
            unitId = UNIT_ID,
            name = "Azúcar rubia",
            barcode = barcode,
            salePrice = null,
            status = CatalogStatus.ACTIVE,
            createdAt = UPDATED_AT,
            updatedAt = UPDATED_AT,
            version = 1L,
        )

    private class ControlledProductRepository(
        private val delegate: ProductRepository,
    ) : ProductRepository by delegate {
        val pending = mutableListOf<CompletableDeferred<Unit>>()

        override suspend fun findByBarcode(
            businessId: BusinessId,
            barcode: String,
        ): Product? {
            val completion = CompletableDeferred<Unit>()
            pending += completion
            // Simula una operación de almacenamiento que tarda en observar la cancelación.
            withContext(NonCancellable) { completion.await() }
            return delegate.findByBarcode(businessId, barcode)
        }
    }

    private class FakeProductProfitRepository : ProductProfitRepository {
        private val values = MutableStateFlow<List<ProductProfit>>(emptyList())

        override fun observeForBusiness(businessId: BusinessId): Flow<List<ProductProfit>> = values
    }

    private companion object {
        val BUSINESS_ID: BusinessId =
            BusinessId.from(UUID.fromString("00000000-0000-0000-0000-000000000901"))
        val PRODUCT_ID: ProductId =
            ProductId.from(UUID.fromString("00000000-0000-0000-0000-000000000011"))
        val UNIT_ID: UnitId =
            UnitId.from(UUID.fromString("00000000-0000-0000-0000-000000000032"))
        val UPDATED_AT: Instant = Instant.parse("2026-08-20T09:00:00Z")
        const val MATCHED_BARCODE = "7751234567890"
        const val UNMATCHED_BARCODE = "7790001112223"
    }
}
