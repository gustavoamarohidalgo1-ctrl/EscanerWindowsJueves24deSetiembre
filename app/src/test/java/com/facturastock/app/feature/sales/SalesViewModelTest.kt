package com.facturastock.app.feature.sales

import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.config.TaxRate
import com.facturastock.app.domain.model.BarcodeValue
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.InventoryCostAmount
import com.facturastock.app.domain.model.InventoryDataAlert
import com.facturastock.app.domain.model.InventoryLocation
import com.facturastock.app.domain.model.InventoryReadItem
import com.facturastock.app.domain.model.InventoryReadPosition
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.SaleCart
import com.facturastock.app.domain.model.SaleCartLine
import com.facturastock.app.domain.model.SaleStatus
import com.facturastock.app.domain.model.UnitOfMeasure
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.SaleId
import com.facturastock.app.domain.model.id.SaleLineId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.repository.CheckoutSaleCommand
import com.facturastock.app.domain.repository.CheckoutSaleResult
import com.facturastock.app.domain.repository.OpenedSaleCart
import com.facturastock.app.domain.repository.ProductRepository
import com.facturastock.app.domain.repository.SaleCartMutationResult
import com.facturastock.app.domain.repository.SaleRepository
import com.facturastock.app.domain.repository.SaveSaleCartLineCommand
import com.facturastock.app.domain.usecase.CheckoutSaleUseCase
import com.facturastock.app.domain.usecase.CreateSaleCartUseCase
import com.facturastock.app.domain.usecase.ObserveSaleCartUseCase
import com.facturastock.app.domain.usecase.ProductMatchingUseCase
import com.facturastock.app.domain.usecase.RemoveSaleCartLineUseCase
import com.facturastock.app.domain.usecase.SaveProductCatalogUseCase
import com.facturastock.app.domain.usecase.SaveSaleCartLineUseCase
import com.facturastock.app.testing.FakeAppConfigurationRepository
import com.facturastock.app.testing.FakeInventoryLocationRepository
import com.facturastock.app.testing.FakeInventoryReadRepository
import com.facturastock.app.testing.FakeProductRepository
import com.facturastock.app.testing.FakeSupplierProductAliasRepository
import com.facturastock.app.testing.FakeUnitRepository
import com.facturastock.app.testing.MainDispatcherRule
import com.facturastock.app.testing.TestDispatcherProvider
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SalesViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val clock = AppClock { NOW }
    private val configuration = FakeAppConfigurationRepository()
    private val productDelegate = FakeProductRepository(clock)
    private val products = CountingProductRepository(productDelegate)
    private val aliases = FakeSupplierProductAliasRepository(clock)
    private val units = FakeUnitRepository(clock, productDelegate)
    private val locations = FakeInventoryLocationRepository(clock)
    private val inventory = FakeInventoryReadRepository()
    private val sales = RecordingSaleRepository()

    @Test
    fun `back persists a pending line edit before emitting navigation`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val originalCart = cart(withLine = true)
            val viewModel = createReadyViewModel(originalCart)
            val saveResult = CompletableDeferred<SaleCartMutationResult>()
            sales.saveLineHandler = { saveResult.await() }
            val back = async { viewModel.effects.first() }

            viewModel.onAction(
                SalesContract.Action.QuantityChanged(
                    lineId = LINE_ID.value,
                    value = "2",
                ),
            )
            runCurrent()
            assertTrue(viewModel.uiState.value.hasPendingEdits)

            viewModel.onAction(SalesContract.Action.BackSelected)
            runCurrent()

            assertEquals(1, sales.saveLineCalls.size)
            assertEquals(0, sales.saveLineCalls.single().quantity.value.compareTo(BigDecimal("2")))
            assertFalse(back.isCompleted)

            saveResult.complete(SaleCartMutationResult.Saved(originalCart))
            runCurrent()

            assertEquals(SalesContract.Effect.Back, back.await())
            assertFalse(viewModel.uiState.value.hasPendingEdits)
            advanceTimeBy(400L)
            runCurrent()
            assertEquals(1, sales.saveLineCalls.size)
        }

    @Test
    fun `double product selection starts only one cart mutation`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val originalCart = cart(withLine = false)
            val viewModel = createReadyViewModel(originalCart)
            val saveResult = CompletableDeferred<SaleCartMutationResult>()
            sales.saveLineHandler = { saveResult.await() }
            val selection = SalesContract.Action.ProductSelected(PRODUCT_ID, LOCATION_ID)

            viewModel.onAction(selection)
            viewModel.onAction(selection)
            runCurrent()

            assertEquals(1, sales.saveLineCalls.size)

            saveResult.complete(SaleCartMutationResult.Saved(originalCart))
            runCurrent()
            assertEquals(1, sales.saveLineCalls.size)
        }

    @Test
    fun `back requested during a cart mutation waits for it before navigating`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val originalCart = cart(withLine = false)
            val viewModel = createReadyViewModel(originalCart)
            val saveResult = CompletableDeferred<SaleCartMutationResult>()
            sales.saveLineHandler = { saveResult.await() }
            val back = async {
                viewModel.effects.first { it == SalesContract.Effect.Back }
            }

            viewModel.onAction(SalesContract.Action.ProductSelected(PRODUCT_ID, LOCATION_ID))
            runCurrent()
            assertTrue(viewModel.uiState.value.isMutating)

            viewModel.onAction(SalesContract.Action.BackSelected)
            runCurrent()
            assertFalse(back.isCompleted)

            saveResult.complete(SaleCartMutationResult.Saved(originalCart))
            runCurrent()

            assertEquals(SalesContract.Effect.Back, back.await())
        }

    @Test
    fun `fractional stock uses the available amount as the initial quantity`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(
                cart = cart(withLine = false),
                availableQuantity = BigDecimal("0.25"),
            )

            viewModel.onAction(SalesContract.Action.ProductSelected(PRODUCT_ID, LOCATION_ID))
            runCurrent()

            assertEquals(1, sales.saveLineCalls.size)
            assertEquals(
                0,
                sales.saveLineCalls.single().quantity.value.compareTo(BigDecimal("0.25")),
            )
            assertNull(viewModel.uiState.value.failure)
        }

    @Test
    fun `a new cart line starts with the catalog sale price in the cart currency`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val suggested = Money.fromMajor("8.75", CURRENCY)
            val viewModel = createReadyViewModel(
                cart = cart(withLine = false),
                productSalePrice = suggested,
            )

            viewModel.onAction(SalesContract.Action.ProductSelected(PRODUCT_ID, LOCATION_ID))
            runCurrent()

            val command = sales.saveLineCalls.single()
            assertEquals(suggested, command.unitPrice)
            assertEquals(Money.zero(CURRENCY), command.discount)
            assertEquals(Money.zero(CURRENCY), command.tax)
        }

    @Test
    fun `catalog price never reprices an existing cart line or its adjustments`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val original = cart(withLine = true)
            val discount = Money.fromMajor("1.00", CURRENCY)
            val tax = Money.fromMajor("0.50", CURRENCY)
            val adjustedTotal = Money.fromMajor("4.50", CURRENCY)
            val adjusted = original.copy(
                lines = listOf(
                    original.lines.single().copy(
                        discount = discount,
                        tax = tax,
                        lineTotal = adjustedTotal,
                    ),
                ),
                discount = discount,
                tax = tax,
                total = adjustedTotal,
            )
            val viewModel = createReadyViewModel(
                cart = adjusted,
                productSalePrice = Money.fromMajor("99.00", CURRENCY),
            )

            viewModel.onAction(SalesContract.Action.ProductSelected(PRODUCT_ID, LOCATION_ID))
            runCurrent()

            val command = sales.saveLineCalls.single()
            assertEquals(original.lines.single().unitPrice, command.unitPrice)
            assertEquals(discount, command.discount)
            assertEquals(tax, command.tax)
        }

    @Test
    fun `a catalog price in another currency is not suggested to a new line`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(
                cart = cart(withLine = false),
                productSalePrice = Money.fromMajor("8.75", CurrencyCode.of("USD")),
            )

            viewModel.onAction(SalesContract.Action.ProductSelected(PRODUCT_ID, LOCATION_ID))
            runCurrent()

            val command = sales.saveLineCalls.single()
            assertNull(command.unitPrice)
            assertNull(command.discount)
            assertNull(command.tax)
        }

    @Test
    fun `credit checkout sends the normalized debtor and finishes without opening another cart`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = true))
            sales.checkoutHandler = { CheckoutSaleResult.Posted(SALE_ID) }
            val completed = async {
                viewModel.effects.first { it == SalesContract.Effect.CreditSalePosted }
            }

            viewModel.onAction(
                SalesContract.Action.EntryKindChanged(SalesContract.EntryKind.CREDIT),
            )
            viewModel.onAction(SalesContract.Action.DebtorNameChanged("  María   Quispe  "))
            runCurrent()
            viewModel.onAction(SalesContract.Action.CheckoutRequested)
            runCurrent()

            assertEquals("María Quispe", viewModel.uiState.value.checkoutReview?.debtorName)
            viewModel.onAction(SalesContract.Action.CheckoutConfirmed)
            runCurrent()

            assertEquals("María Quispe", sales.checkoutCalls.single().debtorName)
            assertEquals(SalesContract.Effect.CreditSalePosted, completed.await())
            assertEquals(1, sales.openCalls.size)
        }

    @Test
    fun `double association confirmation updates and adds only once`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            createReadyViewModel(cart(withLine = false)).also { viewModel ->
                viewModel.onAction(SalesContract.Action.BarcodeScanned(NEW_BARCODE))
                runCurrent()
                assertEquals(NEW_BARCODE, viewModel.uiState.value.pendingAssociationBarcode)

                viewModel.onAction(SalesContract.Action.ProductSelected(PRODUCT_ID, LOCATION_ID))
                runCurrent()
                assertNotNull(viewModel.uiState.value.pendingReplacement)

                val updateGate = CompletableDeferred<Unit>()
                val saveGate = CompletableDeferred<SaleCartMutationResult>()
                products.updateGate = updateGate
                sales.saveLineHandler = { saveGate.await() }
                viewModel.onAction(SalesContract.Action.BarcodeReplacementConfirmed)
                viewModel.onAction(SalesContract.Action.BarcodeReplacementConfirmed)
                runCurrent()

                assertEquals(1, products.updateCalls)
                assertTrue(sales.saveLineCalls.isEmpty())

                updateGate.complete(Unit)
                runCurrent()

                assertEquals(1, products.updateCalls)
                assertEquals(1, sales.saveLineCalls.size)
                assertNull(viewModel.uiState.value.pendingAssociationBarcode)
                assertNull(viewModel.uiState.value.pendingReplacement)

                saveGate.complete(SaleCartMutationResult.Saved(cart(withLine = false)))
                runCurrent()
            }
        }

    @Test
    fun `saved barcode association is no longer cancelable when cart add fails`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val original = cart(withLine = false)
            val viewModel = createReadyViewModel(original)
            viewModel.onAction(SalesContract.Action.BarcodeScanned(NEW_BARCODE))
            runCurrent()
            viewModel.onAction(SalesContract.Action.ProductSelected(PRODUCT_ID, LOCATION_ID))
            runCurrent()
            assertNotNull(viewModel.uiState.value.pendingReplacement)
            sales.saveLineHandler = { SaleCartMutationResult.Stale }

            viewModel.onAction(SalesContract.Action.BarcodeReplacementConfirmed)
            runCurrent()

            assertEquals(1, products.updateCalls)
            assertNull(viewModel.uiState.value.pendingAssociationBarcode)
            assertNull(viewModel.uiState.value.pendingReplacement)
            assertTrue(viewModel.uiState.value.barcodeAssociatedWithoutCartAdd)
            assertEquals(SalesContract.Failure.STALE_CART, viewModel.uiState.value.failure)
            assertEquals(1, sales.saveLineCalls.size)

            sales.saveLineHandler = { SaleCartMutationResult.Saved(original) }
            viewModel.onAction(SalesContract.Action.ProductSelected(PRODUCT_ID, LOCATION_ID))
            runCurrent()

            assertEquals(1, products.updateCalls)
            assertEquals(2, sales.saveLineCalls.size)
            assertNull(viewModel.uiState.value.pendingAssociationBarcode)
            assertFalse(viewModel.uiState.value.barcodeAssociatedWithoutCartAdd)
        }

    @Test
    fun `product without barcode is associated directly without replacement review`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(
                cart = cart(withLine = false),
                productBarcode = null,
            )
            viewModel.onAction(SalesContract.Action.BarcodeScanned(NEW_BARCODE))
            runCurrent()
            assertEquals(NEW_BARCODE, viewModel.uiState.value.pendingAssociationBarcode)

            viewModel.onAction(SalesContract.Action.ProductSelected(PRODUCT_ID, LOCATION_ID))
            runCurrent()

            assertEquals(1, products.updateCalls)
            assertEquals(1, sales.saveLineCalls.size)
            assertNull(viewModel.uiState.value.pendingReplacement)
            assertNull(viewModel.uiState.value.pendingAssociationBarcode)
        }

    @Test
    fun `scanner never competes with a pending local quantity edit`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = true))

            viewModel.onAction(SalesContract.Action.QuantityChanged(LINE_ID.value, "3"))
            runCurrent()
            viewModel.onAction(SalesContract.Action.BarcodeScanned(EXISTING_BARCODE))
            runCurrent()

            assertTrue(viewModel.uiState.value.hasPendingEdits)
            assertFalse(viewModel.uiState.value.canRouteScannerInput)
            assertEquals(0, products.findByBarcodeCalls)
            assertTrue(sales.saveLineCalls.isEmpty())
        }

    @Test
    fun `scanner remains usable after a recoverable invalid barcode`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val original = cart(withLine = false)
            val viewModel = createReadyViewModel(original)

            viewModel.onAction(SalesContract.Action.BarcodeScanned("\n"))
            runCurrent()
            assertEquals(SalesContract.Failure.INVALID_BARCODE, viewModel.uiState.value.failure)
            assertTrue(viewModel.uiState.value.canRouteScannerInput)

            viewModel.onAction(SalesContract.Action.BarcodeScanned(EXISTING_BARCODE))
            runCurrent()

            assertEquals(1, products.findByBarcodeCalls)
            assertEquals(1, sales.saveLineCalls.size)
        }

    @Test
    fun `manual name search exposes exact and similar catalog matches`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = false))

            viewModel.onAction(SalesContract.Action.SearchChanged("Producto"))
            runCurrent()
            assertTrue(viewModel.uiState.value.isNameSearchRunning)
            assertTrue(viewModel.uiState.value.productOptions.isEmpty())
            advanceTimeBy(NAME_SEARCH_DEBOUNCE_MILLIS)
            runCurrent()
            assertEquals(
                SalesContract.NameMatchKind.EXACT,
                viewModel.uiState.value.productOptions.single().nameMatchKind,
            )

            viewModel.onAction(SalesContract.Action.SearchChanged("Producto extra"))
            advanceTimeBy(NAME_SEARCH_DEBOUNCE_MILLIS)
            runCurrent()
            assertEquals(
                SalesContract.NameMatchKind.SIMILAR,
                viewModel.uiState.value.productOptions.single().nameMatchKind,
            )
        }

    @Test
    fun `failed name search is scoped and retry recovers without blocking checkout`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = true))
            assertTrue(viewModel.uiState.value.canCheckout)
            products.nextNameSearchFailure = IllegalStateException("search unavailable")

            viewModel.onAction(SalesContract.Action.SearchChanged("Producto"))
            advanceTimeBy(NAME_SEARCH_DEBOUNCE_MILLIS)
            runCurrent()

            val failed = viewModel.uiState.value
            assertTrue(failed.searchFailed)
            assertNull(failed.failure)
            assertTrue(failed.productOptions.isEmpty())
            assertTrue(failed.canCheckout)

            viewModel.onAction(SalesContract.Action.RetrySearch)
            advanceTimeBy(NAME_SEARCH_DEBOUNCE_MILLIS)
            runCurrent()

            val recovered = viewModel.uiState.value
            assertFalse(recovered.searchFailed)
            assertNull(recovered.failure)
            assertEquals(1, recovered.productOptions.size)
            assertTrue(recovered.canCheckout)
        }

    @Test
    fun `retry cart reobserves while preserving an invalid local input`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = true))
            viewModel.onAction(
                SalesContract.Action.QuantityChanged(
                    lineId = LINE_ID.value,
                    value = "cantidad inválida",
                ),
            )
            runCurrent()
            assertTrue(viewModel.uiState.value.hasPendingEdits)
            assertFalse(viewModel.uiState.value.cartLines.single().quantityValid)

            sales.failCartObservation(SALE_ID)
            runCurrent()

            assertTrue(viewModel.uiState.value.cartLoadFailed)
            assertEquals(1, sales.observeCartCalls)

            viewModel.onAction(SalesContract.Action.RetryCart)
            runCurrent()

            val recovered = viewModel.uiState.value
            assertEquals(2, sales.observeCartCalls)
            assertFalse(recovered.cartLoadFailed)
            assertTrue(recovered.hasPendingEdits)
            assertEquals("cantidad inválida", recovered.cartLines.single().quantityInput)
            assertFalse(recovered.cartLines.single().quantityValid)
        }

    @Test
    fun `retry catalog starts a fresh observation and clears its health flag`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = true))
            assertEquals(1, products.observeForBusinessCalls)

            products.failCatalogObservation()
            runCurrent()

            assertTrue(viewModel.uiState.value.catalogLoadFailed)
            assertFalse(viewModel.uiState.value.canCheckout)

            viewModel.onAction(SalesContract.Action.RetryCatalog)
            runCurrent()

            assertEquals(2, products.observeForBusinessCalls)
            assertFalse(viewModel.uiState.value.catalogLoadFailed)
            assertTrue(viewModel.uiState.value.canCheckout)
        }

    @Test
    fun `numeric manual name query never performs barcode lookup`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = false))

            viewModel.onAction(SalesContract.Action.SearchChanged(EXISTING_BARCODE))
            advanceTimeBy(NAME_SEARCH_DEBOUNCE_MILLIS)
            runCurrent()

            assertEquals(0, products.findByBarcodeCalls)
            assertTrue(viewModel.uiState.value.productOptions.isEmpty())
        }

    @Test
    fun `catalog inventory changes refresh the projected product options`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = false))
            viewModel.onAction(SalesContract.Action.SearchChanged("Producto"))
            advanceTimeBy(NAME_SEARCH_DEBOUNCE_MILLIS)
            runCurrent()
            assertEquals(1, viewModel.uiState.value.productOptions.size)

            inventory.replaceInventory(
                BUSINESS_ID,
                listOf(inventoryItem(BigDecimal("7.5"))),
            )
            runCurrent()

            assertEquals(
                0,
                viewModel.uiState.value.productOptions.single().availableQuantity.compareTo(
                    BigDecimal("7.5"),
                ),
            )
        }

    @Test
    fun `catalog rename invalidates candidates from an active name search`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = false))
            viewModel.onAction(SalesContract.Action.SearchChanged("Producto"))
            advanceTimeBy(NAME_SEARCH_DEBOUNCE_MILLIS)
            runCurrent()
            assertEquals(
                SalesContract.NameMatchKind.EXACT,
                viewModel.uiState.value.productOptions.single().nameMatchKind,
            )

            val current = requireNotNull(products.findById(PRODUCT_ID))
            assertTrue(products.update(current.copy(name = "Articulo renombrado")))
            runCurrent()

            assertTrue(viewModel.uiState.value.isNameSearchRunning)
            assertTrue(viewModel.uiState.value.productOptions.isEmpty())
            advanceTimeBy(NAME_SEARCH_DEBOUNCE_MILLIS)
            runCurrent()

            assertFalse(viewModel.uiState.value.isNameSearchRunning)
            assertTrue(viewModel.uiState.value.productOptions.isEmpty())
        }

    @Test
    fun `archived inventory locations are excluded from sale options`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = false))
            viewModel.onAction(SalesContract.Action.SearchChanged("Producto"))
            advanceTimeBy(NAME_SEARCH_DEBOUNCE_MILLIS)
            runCurrent()
            assertEquals(1, viewModel.uiState.value.productOptions.size)
            val archived = inventoryItem().let { item ->
                item.copy(
                    positions = item.positions.map { position ->
                        position.copy(alerts = setOf(InventoryDataAlert.ARCHIVED_LOCATION))
                    },
                )
            }

            inventory.replaceInventory(BUSINESS_ID, listOf(archived))
            runCurrent()

            assertTrue(viewModel.uiState.value.productOptions.isEmpty())
        }

    @Test
    fun `unrelated settings changes do not restart sale catalog observations`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            createReadyViewModel(cart(withLine = false))
            assertEquals(1, products.observeForBusinessCalls)

            configuration.updateTaxRate(TaxRate(BigDecimal("10")))
            runCurrent()

            assertEquals(1, products.observeForBusinessCalls)
        }

    @Test
    fun `retry preserves valid edits after failure and persists them before reload`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val original = cart(withLine = true)
            val viewModel = createReadyViewModel(original)
            sales.saveLineHandler = { SaleCartMutationResult.Stale }
            viewModel.onAction(SalesContract.Action.QuantityChanged(LINE_ID.value, "2"))
            runCurrent()

            viewModel.onAction(SalesContract.Action.Retry)
            runCurrent()

            assertTrue(viewModel.uiState.value.hasPendingEdits)
            assertEquals(SalesContract.Failure.STALE_CART, viewModel.uiState.value.failure)
            assertEquals(1, sales.openCalls.size)

            sales.saveLineHandler = { SaleCartMutationResult.Saved(original) }
            viewModel.onAction(SalesContract.Action.Retry)
            runCurrent()

            assertFalse(viewModel.uiState.value.hasPendingEdits)
            assertEquals(2, sales.saveLineCalls.size)
            assertEquals(0, sales.saveLineCalls.last().quantity.value.compareTo(BigDecimal("2")))
            assertEquals(2, sales.openCalls.size)
        }

    @Test
    fun `business change flushes old cart through its captured tenant before detaching`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = true))
            viewModel.onAction(SalesContract.Action.UnitPriceChanged(LINE_ID.value, "7.50"))
            runCurrent()

            configuration.exitDemoMode()
            runCurrent()

            assertEquals(listOf(BUSINESS_ID), sales.saveLineBusinessIds)
            assertEquals(750L, sales.saveLineCalls.single().unitPrice?.minorUnits)
            assertFalse(viewModel.uiState.value.hasPendingEdits)
            assertEquals(SalesContract.Failure.NO_ACTIVE_BUSINESS, viewModel.uiState.value.failure)
        }

    @Test
    fun `null and posted observations recover into the current active draft`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val original = cart(withLine = true)
            val viewModel = createReadyViewModel(original)
            val second = cart(withLine = true, saleId = SALE_ID_2)
            sales.prepareCartToOpen(second)

            sales.emitObserved(original.saleId, null)
            runCurrent()
            assertEquals(second.saleId.value, viewModel.uiState.value.cartId)

            val third = cart(withLine = false, saleId = SALE_ID_3)
            sales.prepareCartToOpen(third)
            sales.emitObserved(
                second.saleId,
                second.copy(status = SaleStatus.POSTED, postedAt = NOW),
            )
            runCurrent()

            assertEquals(third.saleId.value, viewModel.uiState.value.cartId)
            assertEquals(3, sales.openCalls.size)
        }

    @Test
    fun `invalid cart observation preserves a pending edit until explicit discard`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val original = cart(withLine = true)
            val viewModel = createReadyViewModel(original)
            sales.saveLineHandler = { SaleCartMutationResult.Stale }
            sales.prepareCartToOpen(cart(withLine = false, saleId = SALE_ID_2))

            viewModel.onAction(SalesContract.Action.QuantityChanged(LINE_ID.value, "2"))
            runCurrent()
            sales.emitObserved(original.saleId, null)
            runCurrent()

            val state = viewModel.uiState.value
            assertEquals(original.saleId.value, state.cartId)
            assertEquals("2", state.cartLines.single().quantityInput)
            assertTrue(state.hasPendingEdits)
            assertTrue(state.discardEditsReview)
            assertEquals(SalesContract.Failure.STALE_CART, state.failure)
            assertEquals(1, sales.saveLineCalls.size)
            assertEquals(1, sales.openCalls.size)
        }

    @Test
    fun `back waits an active debounced save without cancelling or duplicating it`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val original = cart(withLine = true)
            val viewModel = createReadyViewModel(original)
            val saveResult = CompletableDeferred<SaleCartMutationResult>()
            sales.saveLineHandler = { saveResult.await() }
            val back = async { viewModel.effects.first() }
            viewModel.onAction(SalesContract.Action.QuantityChanged(LINE_ID.value, "2"))
            runCurrent()
            advanceTimeBy(350L)
            runCurrent()
            assertEquals(1, sales.saveLineCalls.size)

            viewModel.onAction(SalesContract.Action.BackSelected)
            runCurrent()
            assertFalse(back.isCompleted)
            assertEquals(1, sales.saveLineCalls.size)

            saveResult.complete(SaleCartMutationResult.Saved(original))
            runCurrent()
            assertEquals(SalesContract.Effect.Back, back.await())
            assertEquals(1, sales.saveLineCalls.size)
        }

    @Test
    fun `debounced save keeps line editing responsive and serializes the next CAS version`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val original = cart(withLine = true)
            val savedTwo = original.withQuantity(quantity = "2", version = 2L)
            val savedThree = original.withQuantity(quantity = "3", version = 3L)
            val firstSave = CompletableDeferred<SaleCartMutationResult>()
            sales.saveLineHandler = {
                if (sales.saveLineCalls.size == 1) {
                    firstSave.await()
                } else {
                    SaleCartMutationResult.Saved(savedThree)
                }
            }
            val viewModel = createReadyViewModel(original)

            viewModel.onAction(SalesContract.Action.QuantityChanged(LINE_ID.value, "2"))
            advanceTimeBy(350L)
            runCurrent()

            assertEquals(1, sales.saveLineCalls.size)
            assertTrue(viewModel.uiState.value.isSavingLineEdits)
            assertFalse(viewModel.uiState.value.isMutating)
            assertFalse(viewModel.uiState.value.canCheckout)

            viewModel.onAction(SalesContract.Action.QuantityChanged(LINE_ID.value, "3"))
            viewModel.onAction(SalesContract.Action.CheckoutRequested)
            runCurrent()

            assertEquals("3", viewModel.uiState.value.cartLines.single().quantityInput)
            assertNull(viewModel.uiState.value.checkoutReview)

            firstSave.complete(SaleCartMutationResult.Saved(savedTwo))
            runCurrent()

            assertFalse(viewModel.uiState.value.isSavingLineEdits)
            assertTrue(viewModel.uiState.value.hasPendingEdits)
            assertEquals("3", viewModel.uiState.value.cartLines.single().quantityInput)

            advanceTimeBy(350L)
            runCurrent()

            assertEquals(listOf(1L, 2L), sales.saveLineCalls.map { it.expectedVersion })
            assertFalse(viewModel.uiState.value.isSavingLineEdits)
            assertFalse(viewModel.uiState.value.hasPendingEdits)
            assertEquals("3", viewModel.uiState.value.cartLines.single().quantityInput)
            assertTrue(viewModel.uiState.value.canCheckout)
        }

    @Test
    fun `invalid pending edit requires explicit discard confirmation before back`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = true))
            val back = async { viewModel.effects.first() }
            viewModel.onAction(SalesContract.Action.QuantityChanged(LINE_ID.value, ""))
            runCurrent()

            viewModel.onAction(SalesContract.Action.BackSelected)
            runCurrent()
            assertTrue(viewModel.uiState.value.discardEditsReview)
            assertTrue(viewModel.uiState.value.hasPendingEdits)
            assertFalse(back.isCompleted)

            viewModel.onAction(SalesContract.Action.DiscardEditsConfirmed)
            runCurrent()
            assertEquals(SalesContract.Effect.Back, back.await())
            assertFalse(viewModel.uiState.value.hasPendingEdits)
        }

    @Test
    fun `quantity above stock can leave only after explicit discard confirmation`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = true))
            val back = async { viewModel.effects.first() }
            viewModel.onAction(SalesContract.Action.QuantityChanged(LINE_ID.value, "11"))
            runCurrent()

            viewModel.onAction(SalesContract.Action.BackSelected)
            runCurrent()

            assertEquals(SalesContract.Failure.INSUFFICIENT_STOCK, viewModel.uiState.value.failure)
            assertTrue(viewModel.uiState.value.discardEditsReview)
            assertTrue(viewModel.uiState.value.hasPendingEdits)
            assertFalse(back.isCompleted)

            viewModel.onAction(SalesContract.Action.DiscardEditsConfirmed)
            runCurrent()
            assertEquals(SalesContract.Effect.Back, back.await())
        }

    @Test
    fun `scanner barcode overflow is rejected without lookup update or cart mutation`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = false))
            val overflow = "1".repeat(129)

            viewModel.onAction(SalesContract.Action.BarcodeScanned(overflow))
            runCurrent()

            assertEquals(SalesContract.Failure.INVALID_BARCODE, viewModel.uiState.value.failure)
            assertEquals(0, products.findByBarcodeCalls)
            assertEquals(0, products.updateCalls)
            assertTrue(sales.saveLineCalls.isEmpty())
        }

    @Test
    fun `scanner barcode overflow with spaces never becomes the valid prefix`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = false))
            val overflow = EXISTING_BARCODE + " ".repeat(BarcodeValue.MAX_LENGTH) + "X"

            viewModel.onAction(SalesContract.Action.BarcodeScanned(overflow))
            runCurrent()

            assertEquals(SalesContract.Failure.INVALID_BARCODE, viewModel.uiState.value.failure)
            assertEquals(0, products.findByBarcodeCalls)
            assertEquals(0, products.updateCalls)
            assertTrue(sales.saveLineCalls.isEmpty())
        }

    @Test
    fun `persisted line total keeps its discount and tax in the UI`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val original = cart(withLine = true)
            val discount = Money.fromMajor("1.00", CURRENCY)
            val tax = Money.fromMajor("0.50", CURRENCY)
            val adjustedTotal = Money.fromMajor("4.50", CURRENCY)
            val adjusted = original.copy(
                lines = listOf(
                    original.lines.single().copy(
                        discount = discount,
                        tax = tax,
                        lineTotal = adjustedTotal,
                    ),
                ),
                discount = discount,
                tax = tax,
                total = adjustedTotal,
            )

            val viewModel = createReadyViewModel(adjusted)

            assertEquals(adjustedTotal, viewModel.uiState.value.cartLines.single().lineTotal)
            assertEquals(adjustedTotal, viewModel.uiState.value.total)
        }

    private suspend fun TestScope.createReadyViewModel(
        cart: SaleCart,
        availableQuantity: BigDecimal = BigDecimal.TEN,
        productBarcode: String? = EXISTING_BARCODE,
        productSalePrice: Money? = null,
    ): SalesViewModel {
        configuration.enterDemoMode(BUSINESS_ID)
        units.create(
            UnitOfMeasure(
                unitId = UNIT_ID,
                businessId = BUSINESS_ID,
                code = "NIU",
                name = "Unidad",
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )
        locations.create(
            InventoryLocation(
                locationId = LOCATION_ID,
                businessId = BUSINESS_ID,
                name = "Principal",
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )
        products.create(
            Product(
                productId = PRODUCT_ID,
                businessId = BUSINESS_ID,
                unitId = UNIT_ID,
                name = "Producto",
                locationId = LOCATION_ID,
                barcode = productBarcode,
                salePrice = productSalePrice,
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )
        inventory.replaceInventory(
            BUSINESS_ID,
            listOf(inventoryItem(availableQuantity)),
        )
        sales.replaceCart(cart)
        val viewModel = SalesViewModel(
            configuration = configuration,
            products = products,
            inventoryReads = inventory,
            createCart = CreateSaleCartUseCase(configuration, sales),
            observeCart = ObserveSaleCartUseCase(sales),
            saveLine = SaveSaleCartLineUseCase(configuration, sales),
            removeLine = RemoveSaleCartLineUseCase(configuration, sales),
            checkout = CheckoutSaleUseCase(configuration, sales),
            saveProduct = SaveProductCatalogUseCase(products, units, locations),
            productMatching = ProductMatchingUseCase(products, aliases),
            dispatcherProvider = TestDispatcherProvider(mainDispatcherRule.dispatcher),
        )
        runCurrent()
        assertFalse(viewModel.uiState.value.isLoading)
        assertTrue(viewModel.uiState.value.productOptions.isEmpty())
        return viewModel
    }

    private fun inventoryItem(availableQuantity: BigDecimal = BigDecimal.TEN) = InventoryReadItem(
        productId = PRODUCT_ID,
        businessId = BUSINESS_ID,
        productName = "Producto",
        sku = null,
        unitCode = "NIU",
        unitSymbol = "u",
        positions = listOf(
            InventoryReadPosition(
                locationId = LOCATION_ID,
                locationName = "Principal",
                quantityOnHand = availableQuantity,
                averageUnitCost = InventoryCostAmount(BigDecimal.ONE, CURRENCY),
                version = 1L,
                updatedAt = NOW,
            ),
        ),
    )

    private fun cart(
        withLine: Boolean,
        saleId: SaleId = SALE_ID,
    ): SaleCart {
        val price = Money.fromMajor("5.00", CURRENCY)
        val zero = Money.zero(CURRENCY)
        val lines = if (withLine) {
            listOf(
                SaleCartLine(
                    saleLineId = LINE_ID,
                    productId = PRODUCT_ID,
                    unitId = UNIT_ID,
                    locationId = LOCATION_ID,
                    position = 0,
                    productName = "Producto",
                    unitCode = "NIU",
                    locationName = "Principal",
                    barcode = null,
                    quantity = Quantity.of(BigDecimal.ONE),
                    unitPrice = price,
                    discount = zero,
                    tax = zero,
                    lineTotal = price,
                ),
            )
        } else {
            emptyList()
        }
        val total = if (withLine) price else zero
        return SaleCart(
            saleId = saleId,
            businessId = BUSINESS_ID,
            status = SaleStatus.DRAFT,
            currency = CURRENCY,
            lines = lines,
            subtotal = total,
            discount = zero,
            tax = zero,
            total = total,
            contentHash = if (withLine) "1".repeat(64) else "0".repeat(64),
            version = 1L,
            createdAt = NOW,
            updatedAt = NOW,
            postedAt = null,
        )
    }

    private fun SaleCart.withQuantity(quantity: String, version: Long): SaleCart {
        val parsedQuantity = Quantity.of(BigDecimal(quantity))
        val lineTotal = Money.fromMajor(
            BigDecimal(quantity).multiply(BigDecimal("5.00")).toPlainString(),
            CURRENCY,
        )
        return copy(
            lines = listOf(
                lines.single().copy(
                    quantity = parsedQuantity,
                    lineTotal = lineTotal,
                ),
            ),
            subtotal = lineTotal,
            total = lineTotal,
            contentHash = version.toString().takeLast(1).repeat(64),
            version = version,
        )
    }

    private class CountingProductRepository(
        private val delegate: ProductRepository,
    ) : ProductRepository by delegate {
        private val catalogObservationFailures = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        var updateCalls: Int = 0
            private set
        var findByBarcodeCalls: Int = 0
            private set
        var observeForBusinessCalls: Int = 0
            private set
        var updateGate: CompletableDeferred<Unit>? = null
        var nextNameSearchFailure: Throwable? = null

        fun failCatalogObservation() {
            check(catalogObservationFailures.tryEmit(Unit))
        }

        override fun observeForBusiness(businessId: BusinessId): Flow<List<Product>> {
            observeForBusinessCalls += 1
            val failures = flow<List<Product>> {
                catalogObservationFailures.collect {
                    throw IllegalStateException("catalog observation unavailable")
                }
            }
            return merge(delegate.observeForBusiness(businessId), failures)
        }

        override suspend fun findByBarcode(businessId: BusinessId, barcode: String): Product? {
            findByBarcodeCalls += 1
            return delegate.findByBarcode(businessId, barcode)
        }

        override suspend fun findByNormalizedName(
            businessId: BusinessId,
            normalizedName: String,
        ): List<Product> {
            nextNameSearchFailure?.let { failure ->
                nextNameSearchFailure = null
                throw failure
            }
            return delegate.findByNormalizedName(businessId, normalizedName)
        }

        override suspend fun update(product: Product): Boolean {
            updateCalls += 1
            updateGate?.await()
            return delegate.update(product)
        }
    }

    private class RecordingSaleRepository : SaleRepository {
        private val carts = mutableMapOf<SaleId, MutableStateFlow<SaleCart?>>()
        private val cartObservationFailures =
            mutableMapOf<SaleId, MutableSharedFlow<Unit>>()
        private var cartToOpen: SaleCart? = null
        val saveLineCalls = mutableListOf<SaveSaleCartLineCommand>()
        val saveLineBusinessIds = mutableListOf<BusinessId>()
        val openCalls = mutableListOf<Pair<BusinessId, CurrencyCode>>()
        val checkoutCalls = mutableListOf<CheckoutSaleCommand>()
        var observeCartCalls: Int = 0
            private set
        var saveLineHandler: suspend (SaveSaleCartLineCommand) -> SaleCartMutationResult = {
            SaleCartMutationResult.Saved(requireNotNull(cartToOpen))
        }
        var checkoutHandler: suspend (CheckoutSaleCommand) -> CheckoutSaleResult = {
            CheckoutSaleResult.Posted(it.saleId)
        }

        fun replaceCart(value: SaleCart) {
            cartToOpen = value
            carts.getOrPut(value.saleId) { MutableStateFlow(value) }.value = value
        }

        fun prepareCartToOpen(value: SaleCart) {
            cartToOpen = value
            carts.getOrPut(value.saleId) { MutableStateFlow(value) }.value = value
        }

        fun emitObserved(saleId: SaleId, value: SaleCart?) {
            carts.getOrPut(saleId) { MutableStateFlow(null) }.value = value
        }

        fun failCartObservation(saleId: SaleId) {
            val signal = cartObservationFailures.getOrPut(saleId) {
                MutableSharedFlow(extraBufferCapacity = 1)
            }
            check(signal.tryEmit(Unit))
        }

        override fun observe(saleId: SaleId): Flow<SaleCart?> {
            observeCartCalls += 1
            val values = carts.getOrPut(saleId) {
                MutableStateFlow(cartToOpen?.takeIf { it.saleId == saleId })
            }
            val failures = flow<SaleCart?> {
                cartObservationFailures.getOrPut(saleId) {
                    MutableSharedFlow(extraBufferCapacity = 1)
                }.collect {
                    throw IllegalStateException("cart observation unavailable")
                }
            }
            return merge(values, failures)
        }

        override fun observeRecentPosted(
            businessId: BusinessId,
            limit: Int,
        ) = flowOf(emptyList<com.facturastock.app.domain.model.SaleSummary>())

        override fun observePostedProfits(
            businessId: BusinessId,
            startInclusive: Instant,
            endExclusive: Instant,
        ) = flowOf(emptyList<com.facturastock.app.domain.model.RealizedSaleProfit>())

        override suspend fun createOrResume(
            businessId: BusinessId,
            currency: CurrencyCode,
        ): OpenedSaleCart {
            openCalls += businessId to currency
            return OpenedSaleCart(requireNotNull(cartToOpen), created = false)
        }

        override suspend fun saveLine(
            businessId: BusinessId,
            command: SaveSaleCartLineCommand,
        ): SaleCartMutationResult {
            saveLineBusinessIds += businessId
            saveLineCalls += command
            return saveLineHandler(command).also { result ->
                if (result is SaleCartMutationResult.Saved) {
                    cartToOpen = result.cart
                    carts.getOrPut(result.cart.saleId) { MutableStateFlow(result.cart) }.value = result.cart
                }
            }
        }

        override suspend fun removeLine(
            businessId: BusinessId,
            saleId: SaleId,
            saleLineId: SaleLineId,
            expectedVersion: Long,
        ): SaleCartMutationResult = error("removeLine no se usa en estas pruebas")

        override suspend fun checkout(
            businessId: BusinessId,
            command: CheckoutSaleCommand,
        ): CheckoutSaleResult {
            checkoutCalls += command
            return checkoutHandler(command)
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-24T12:00:00Z")
        val CURRENCY: CurrencyCode = CurrencyCode.of("PEN")
        val BUSINESS_ID: BusinessId = BusinessId.from(uuid(1L))
        val UNIT_ID: UnitId = UnitId.from(uuid(2L))
        val LOCATION_ID: LocationId = LocationId.from(uuid(3L))
        val PRODUCT_ID: ProductId = ProductId.from(uuid(4L))
        val SALE_ID: SaleId = SaleId.from(uuid(5L))
        val LINE_ID: SaleLineId = SaleLineId.from(uuid(6L))
        val SALE_ID_2: SaleId = SaleId.from(uuid(7L))
        val SALE_ID_3: SaleId = SaleId.from(uuid(8L))
        const val NEW_BARCODE = "7751234567890"
        const val EXISTING_BARCODE = "7751111111111"
        const val NAME_SEARCH_DEBOUNCE_MILLIS = 250L

        fun uuid(value: Long): UUID = UUID(0L, value)
    }
}
