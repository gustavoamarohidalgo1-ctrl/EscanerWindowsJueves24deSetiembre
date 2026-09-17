package com.facturastock.app.feature.sales

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.config.TaxRate
import com.facturastock.app.domain.model.BarcodeValue
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.InventoryCostAmount
import com.facturastock.app.domain.model.InventoryDataAlert
import com.facturastock.app.domain.model.InventoryLocation
import com.facturastock.app.domain.model.InventoryProductDetail
import com.facturastock.app.domain.model.InventoryReadItem
import com.facturastock.app.domain.model.InventoryReadPosition
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.PendingSaleCheckout
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
import com.facturastock.app.domain.repository.InventoryReadRepository
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

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
    fun `opening debtors from the initial selector only emits navigation and preserves the cart`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = true), enterSelling = false, unifiedInput = true)
            val before = viewModel.uiState.value
            val navigation = async { viewModel.effects.first() }

            viewModel.onAction(SalesContract.Action.OpenDebtorsSelected)
            runCurrent()

            assertEquals(SalesContract.Effect.OpenDebtors, navigation.await())
            assertEquals(before, viewModel.uiState.value)
            assertTrue(sales.saveLineCalls.isEmpty())
            assertTrue(sales.checkoutCalls.isEmpty())
        }

    @Test
    fun `debtors navigation uses the exit guard and cancellation preserves an invalid cart edit`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = true), unifiedInput = true)
            viewModel.onAction(SalesContract.Action.QuantityChanged(LINE_ID.value, ""))
            viewModel.onAction(SalesContract.Action.StepBackSelected)
            runCurrent()
            assertEquals(SalesContract.EntryStep.SELECT_KIND, viewModel.uiState.value.entryStep)
            val before = viewModel.uiState.value
            val navigation = async { viewModel.effects.first() }

            viewModel.onAction(SalesContract.Action.OpenDebtorsSelected)
            runCurrent()
            assertEquals(SalesContract.Effect.OpenDebtors, navigation.await())
            assertEquals(before, viewModel.uiState.value)

            // The app forwards this navigation intent through the registered sales exit request.
            val exit = async { viewModel.effects.first() }
            viewModel.onAction(SalesContract.Action.BackSelected)
            runCurrent()
            assertTrue(viewModel.uiState.value.discardEditsReview)
            assertFalse(exit.isCompleted)
            viewModel.onAction(SalesContract.Action.DiscardEditsDismissed)
            runCurrent()
            assertFalse(viewModel.uiState.value.discardEditsReview)
            assertTrue(viewModel.uiState.value.hasPendingEdits)
            assertEquals(before.cartLines, viewModel.uiState.value.cartLines)
            assertFalse(exit.isCompleted)

            viewModel.onAction(SalesContract.Action.BackSelected)
            runCurrent()
            assertTrue(viewModel.uiState.value.discardEditsReview)
            viewModel.onAction(SalesContract.Action.DiscardEditsConfirmed)
            runCurrent()
            assertEquals(SalesContract.Effect.Back, exit.await())
            assertFalse(viewModel.uiState.value.hasPendingEdits)
            assertEquals(before.cartId, viewModel.uiState.value.cartId)
            assertTrue(sales.saveLineCalls.isEmpty())
            assertTrue(sales.checkoutCalls.isEmpty())
        }

    @Test
    fun `debtors shortcut is ignored outside the initial sales selector`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = true), unifiedInput = true)
            val before = viewModel.uiState.value
            val effect = backgroundScope.async { viewModel.effects.first() }

            viewModel.onAction(SalesContract.Action.OpenDebtorsSelected)
            runCurrent()

            assertFalse(effect.isCompleted)
            assertEquals(before, viewModel.uiState.value)
            assertTrue(sales.saveLineCalls.isEmpty())
            assertTrue(sales.checkoutCalls.isEmpty())
        }

    @Test
    fun `weight sale amount previews exact kilos without writing then saves only after confirmation`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createWeightViewModel()
            viewModel.onAction(SalesContract.Action.ProductSelected(PRODUCT_ID, LOCATION_ID))
            runCurrent()
            assertFalse(viewModel.uiState.value.canRouteScannerInput)
            assertFalse(viewModel.uiState.value.canCheckout)
            assertEquals(SalesContract.WeightEntryMode.AMOUNT, viewModel.uiState.value.weightSaleEditor?.mode)
            viewModel.onAction(SalesContract.Action.WeightAmountChanged("7,00"))
            runCurrent()
            val editor = requireNotNull(viewModel.uiState.value.weightSaleEditor)
            assertEquals(0, BigDecimal("0.875").compareTo(requireNotNull(editor.quantity).value))
            assertEquals(700L, editor.total?.minorUnits)
            assertTrue(editor.isValid)
            assertTrue(sales.saveLineCalls.isEmpty())
            viewModel.onAction(SalesContract.Action.BarcodeScanned(EXISTING_BARCODE))
            runCurrent()
            assertEquals(0, products.findByBarcodeCalls)
            viewModel.onAction(SalesContract.Action.WeightSaleConfirmed)
            runCurrent()
            val saved = sales.saveLineCalls.single()
            assertEquals(0, BigDecimal("0.875").compareTo(saved.quantity.value))
            assertEquals(800L, saved.unitPrice?.minorUnits)
            assertEquals(LOCATION_ID, saved.locationId)
            assertEquals(0L, saved.discount?.minorUnits)
            assertEquals(0L, saved.tax?.minorUnits)
            assertNull(viewModel.uiState.value.weightSaleEditor)
            assertTrue(viewModel.uiState.value.canRouteScannerInput)
        }

    @Test
    fun `weight sale rejects invalid amounts and excess stock without saving and back cancels only editor`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createWeightViewModel()
            viewModel.onAction(SalesContract.Action.ProductSelected(PRODUCT_ID, LOCATION_ID))
            runCurrent()
            for (input in listOf("", "0", "-1", "abc", "0.001", "100")) {
                viewModel.onAction(SalesContract.Action.WeightAmountChanged(input))
                viewModel.onAction(SalesContract.Action.WeightSaleConfirmed)
                runCurrent()
                assertFalse(requireNotNull(viewModel.uiState.value.weightSaleEditor).isValid)
                assertTrue(sales.saveLineCalls.isEmpty())
            }
            assertTrue(requireNotNull(viewModel.uiState.value.weightSaleEditor).exceedsStock)
            viewModel.onAction(SalesContract.Action.StepBackSelected)
            runCurrent()
            assertNull(viewModel.uiState.value.weightSaleEditor)
            assertEquals(SalesContract.EntryStep.SELL, viewModel.uiState.value.entryStep)
            assertTrue(viewModel.uiState.value.cartLines.isEmpty())
            assertTrue(viewModel.uiState.value.canRouteScannerInput)
        }

    @Test
    fun `weight sale can enter kilos and calculates the charge`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createWeightViewModel()
            viewModel.onAction(SalesContract.Action.ProductSelected(PRODUCT_ID, LOCATION_ID))
            runCurrent()
            viewModel.onAction(SalesContract.Action.WeightEntryModeChanged(SalesContract.WeightEntryMode.QUANTITY))
            viewModel.onAction(SalesContract.Action.WeightQuantityChanged("0,25"))
            runCurrent()
            assertEquals(200L, viewModel.uiState.value.weightSaleEditor?.total?.minorUnits)
            viewModel.onAction(SalesContract.Action.WeightSaleConfirmed)
            runCurrent()
            assertEquals(0, BigDecimal("0.25").compareTo(sales.saveLineCalls.single().quantity.value))
        }

    @Test
    fun `weight confirmation rereads stock and rejects a quantity that is no longer available`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createWeightViewModel()
            viewModel.onAction(SalesContract.Action.ProductSelected(PRODUCT_ID, LOCATION_ID))
            viewModel.onAction(SalesContract.Action.WeightAmountChanged("7"))
            runCurrent()
            setWeightInventory(BigDecimal("0.5"))
            runCurrent()
            viewModel.onAction(SalesContract.Action.WeightSaleConfirmed)
            runCurrent()
            assertTrue(sales.saveLineCalls.isEmpty())
            assertTrue(requireNotNull(viewModel.uiState.value.weightSaleEditor).exceedsStock)
            viewModel.onAction(SalesContract.Action.WeightAmountChanged("4"))
            viewModel.onAction(SalesContract.Action.WeightSaleConfirmed)
            runCurrent()
            assertEquals(0, BigDecimal("0.5").compareTo(sales.saveLineCalls.single().quantity.value))
        }

    @Test
    fun `weight confirmation shows changed price before accepting the recalculated kilos`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createWeightViewModel()
            viewModel.onAction(SalesContract.Action.ProductSelected(PRODUCT_ID, LOCATION_ID))
            viewModel.onAction(SalesContract.Action.WeightAmountChanged("7"))
            runCurrent()
            val product = requireNotNull(products.findById(PRODUCT_ID))
            assertTrue(products.update(product.copy(salePrice = Money.fromMajor("10.00", CURRENCY))))
            runCurrent()
            viewModel.onAction(SalesContract.Action.WeightSaleConfirmed)
            runCurrent()
            val changed = requireNotNull(viewModel.uiState.value.weightSaleEditor)
            assertEquals(SalesContract.WeightSaleFailure.PRODUCT_CHANGED, changed.failure)
            assertEquals(0, BigDecimal("0.7").compareTo(requireNotNull(changed.quantity).value))
            assertEquals(700L, changed.total?.minorUnits)
            assertTrue(sales.saveLineCalls.isEmpty())
            viewModel.onAction(SalesContract.Action.WeightSaleConfirmed)
            runCurrent()
            assertEquals(1000L, sales.saveLineCalls.single().unitPrice?.minorUnits)
        }

    @Test
    fun `weight sale editor closes on business switch and its confirmation cannot save there`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createWeightViewModel()
            viewModel.onAction(SalesContract.Action.ProductSelected(PRODUCT_ID, LOCATION_ID))
            viewModel.onAction(SalesContract.Action.WeightAmountChanged("7"))
            runCurrent()
            configuration.enterDemoMode(BusinessId.from(UUID.randomUUID()))
            runCurrent()
            assertNull(viewModel.uiState.value.weightSaleEditor)
            viewModel.onAction(SalesContract.Action.WeightSaleConfirmed)
            runCurrent()
            assertTrue(sales.saveLineCalls.isEmpty())
        }

    @Test
    fun `editing weighted amount keeps the saved unit price and replaces the existing line`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val original = cart(withLine = true)
            val price = Money.fromMajor("8.00", CURRENCY)
            val weighted = original.copy(
                lines = original.lines.map { it.copy(unitCode = "KGM", unitPrice = price, lineTotal = price) },
                subtotal = price,
                total = price,
            )
            val viewModel = createWeightViewModel(weighted, productPrice = Money.fromMajor("10.00", CURRENCY))
            assertTrue(viewModel.uiState.value.cartLines.single().isWeightProduct)
            viewModel.onAction(SalesContract.Action.EditWeightSale(LINE_ID.value))
            runCurrent()
            assertEquals(800L, viewModel.uiState.value.weightSaleEditor?.pricePerKg?.minorUnits)
            viewModel.onAction(SalesContract.Action.WeightAmountChanged("4"))
            viewModel.onAction(SalesContract.Action.WeightSaleConfirmed)
            runCurrent()
            val saved = sales.saveLineCalls.single()
            assertEquals(LINE_ID, saved.saleLineId)
            assertEquals(800L, saved.unitPrice?.minorUnits)
            assertEquals(0, BigDecimal("0.5").compareTo(saved.quantity.value))
        }

    @Test
    fun `weight save failure retains editable amount and allows retry without changing the catalog`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createWeightViewModel()
            sales.saveLineHandler = { throw IllegalStateException("storage unavailable") }
            viewModel.onAction(SalesContract.Action.ProductSelected(PRODUCT_ID, LOCATION_ID))
            viewModel.onAction(SalesContract.Action.WeightAmountChanged("7"))
            viewModel.onAction(SalesContract.Action.WeightSaleConfirmed)
            runCurrent()
            assertEquals(SalesContract.WeightSaleFailure.SAVE_FAILED, viewModel.uiState.value.weightSaleEditor?.failure)
            assertEquals("7", viewModel.uiState.value.weightSaleEditor?.amountInput)
            assertEquals(0, products.updateCalls)
            sales.saveLineHandler = { SaleCartMutationResult.Saved(cart(withLine = false)) }
            viewModel.onAction(SalesContract.Action.WeightSaleConfirmed)
            runCurrent()
            assertNull(viewModel.uiState.value.weightSaleEditor)
        }

    @Test
    fun `editing a weighted line does not infer different kilos from its rounded cent total`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val price = Money.fromMajor("8.00", CURRENCY)
            val charged = Money.fromMajor("0.01", CURRENCY)
            val base = cart(withLine = true)
            val original = base.copy(
                lines = base.lines.map {
                    it.copy(unitCode = "KGM", unitPrice = price, quantity = Quantity.of("0.001"), lineTotal = charged)
                },
                subtotal = charged,
                total = charged,
            )
            val viewModel = createWeightViewModel(original)
            viewModel.onAction(SalesContract.Action.EditWeightSale(LINE_ID.value))
            runCurrent()
            assertEquals(0, BigDecimal("0.001").compareTo(requireNotNull(viewModel.uiState.value.weightSaleEditor?.quantity).value))
            viewModel.onAction(SalesContract.Action.WeightEntryModeChanged(SalesContract.WeightEntryMode.QUANTITY))
            viewModel.onAction(SalesContract.Action.WeightEntryModeChanged(SalesContract.WeightEntryMode.AMOUNT))
            viewModel.onAction(SalesContract.Action.WeightSaleConfirmed)
            runCurrent()
            assertEquals(0, BigDecimal("0.001").compareTo(sales.saveLineCalls.single().quantity.value))
        }

    @Test
    fun `recovering a replaced cart closes its weight editor and cannot save the old amount`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createWeightViewModel()
            viewModel.onAction(SalesContract.Action.ProductSelected(PRODUCT_ID, LOCATION_ID))
            viewModel.onAction(SalesContract.Action.WeightAmountChanged("7"))
            runCurrent()
            assertNotNull(viewModel.uiState.value.weightSaleEditor)
            sales.prepareCartToOpen(cart(withLine = false, saleId = SALE_ID_2))
            sales.emitObserved(SALE_ID, null)
            runCurrent()
            assertEquals(SALE_ID_2.value, viewModel.uiState.value.cartId)
            assertNull(viewModel.uiState.value.weightSaleEditor)
            viewModel.onAction(SalesContract.Action.WeightSaleConfirmed)
            runCurrent()
            assertTrue(sales.saveLineCalls.isEmpty())
            assertTrue(viewModel.uiState.value.canRouteScannerInput)
        }

    private suspend fun TestScope.createWeightViewModel(
        initialCart: SaleCart = cart(withLine = false),
        productPrice: Money = Money.fromMajor("8.00", CURRENCY),
    ): SalesViewModel {
        val viewModel = createReadyViewModel(
            initialCart,
            productBarcode = null,
            productSalePrice = productPrice,
            unifiedInput = true,
            unitCode = "KGM",
        )
        setWeightInventory(BigDecimal.TEN)
        runCurrent()
        return viewModel
    }

    private fun setWeightInventory(quantity: BigDecimal) {
        val item = inventoryItem(quantity).copy(unitCode = "KGM", unitSymbol = "kg")
        inventory.replaceInventory(BUSINESS_ID, listOf(item))
        inventory.setProductDetail(BUSINESS_ID, PRODUCT_ID, InventoryProductDetail(item, emptyList()))
    }

    @Test
    fun `unified cash entry finds two letters and accepts manual choice then scanner`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            assertUnifiedEntryAcceptsNamesAndCodes(SalesContract.EntryKind.CASH, true)
        }

    @Test
    fun `unified direct credit finds two letters and accepts manual choice then scanner`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            assertUnifiedEntryAcceptsNamesAndCodes(SalesContract.EntryKind.CREDIT, false)
        }

    private suspend fun TestScope.assertUnifiedEntryAcceptsNamesAndCodes(
        kind: SalesContract.EntryKind,
        allowKindSelection: Boolean,
    ) {
        val viewModel = createReadyViewModel(
            cart(withLine = false),
            enterSelling = false,
            entryKind = kind,
            allowEntryKindSelection = allowKindSelection,
            unifiedInput = true,
        )
        if (allowKindSelection) {
            assertEquals(SalesContract.EntryStep.SELECT_KIND, viewModel.uiState.value.entryStep)
            viewModel.onAction(SalesContract.Action.EntryKindChanged(kind))
            runCurrent()
        }
        assertEquals(SalesContract.EntryStep.SELL, viewModel.uiState.value.entryStep)
        assertEquals(kind, viewModel.uiState.value.entryKind)
        assertTrue(viewModel.uiState.value.canRouteScannerInput)

        viewModel.onAction(SalesContract.Action.SearchChanged("P"))
        advanceTimeBy(NAME_SEARCH_DEBOUNCE_MILLIS)
        runCurrent()
        assertFalse(viewModel.uiState.value.isNameSearchRunning)
        assertTrue(viewModel.uiState.value.productOptions.isEmpty())
        assertEquals(0, products.nameSearchCalls)

        viewModel.onAction(SalesContract.Action.SearchChanged("Pr"))
        advanceTimeBy(NAME_SEARCH_DEBOUNCE_MILLIS)
        runCurrent()
        assertEquals(PRODUCT_ID, viewModel.uiState.value.productOptions.single().productId)
        assertEquals(0, products.findByBarcodeCalls)
        assertTrue(sales.saveLineCalls.isEmpty())
        assertTrue(viewModel.uiState.value.canRouteScannerInput)

        viewModel.onAction(SalesContract.Action.ProductSelected(PRODUCT_ID, LOCATION_ID))
        runCurrent()
        assertEquals(1, sales.saveLineCalls.size)
        assertEquals("", viewModel.uiState.value.query)
        viewModel.onAction(SalesContract.Action.SearchChanged("Pr"))
        runCurrent()
        viewModel.onAction(SalesContract.Action.BarcodeScanned(EXISTING_BARCODE))
        runCurrent()
        assertEquals(1, products.findByBarcodeCalls)
        assertEquals(2, sales.saveLineCalls.size)
        assertEquals("", viewModel.uiState.value.query)
        assertTrue(viewModel.uiState.value.productOptions.isEmpty())

        viewModel.onAction(SalesContract.Action.TextInputFocusChanged("debtor", true))
        runCurrent()
        assertFalse(viewModel.uiState.value.canRouteScannerInput)
        viewModel.onAction(SalesContract.Action.TextInputFocusChanged("debtor", false))
        runCurrent()
        assertTrue(viewModel.uiState.value.canRouteScannerInput)
    }

    @Test
    fun `shortening a two letter search cancels its pending result and clears suggestions`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = false), unifiedInput = true)
            viewModel.onAction(SalesContract.Action.SearchChanged("Pr"))
            advanceTimeBy(NAME_SEARCH_DEBOUNCE_MILLIS)
            runCurrent()
            assertEquals(1, viewModel.uiState.value.productOptions.size)
            val calls = products.nameSearchCalls

            viewModel.onAction(SalesContract.Action.SearchChanged("Pro"))
            runCurrent()
            viewModel.onAction(SalesContract.Action.SearchChanged(" P "))
            advanceTimeBy(NAME_SEARCH_DEBOUNCE_MILLIS)
            runCurrent()
            assertTrue(viewModel.uiState.value.productOptions.isEmpty())
            assertFalse(viewModel.uiState.value.isNameSearchRunning)
            assertEquals(calls, products.nameSearchCalls)
            assertTrue(sales.saveLineCalls.isEmpty())
        }

    @Test
    fun `unified entry replaces restored manual selector without changing saved cart`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val saved = SavedStateHandle(
                mapOf(
                    "sales.entry.initialized" to true,
                    "sales.entry.kind" to "CREDIT",
                    "sales.entry.mode" to "MANUAL",
                    "sales.entry.step" to "SELECT_MODE",
                ),
            )
            val viewModel = createReadyViewModel(
                cart(withLine = true),
                enterSelling = false,
                savedStateHandle = saved,
                entryKind = SalesContract.EntryKind.CREDIT,
                allowEntryKindSelection = false,
                unifiedInput = true,
            )
            assertEquals(SalesContract.EntryStep.SELL, viewModel.uiState.value.entryStep)
            assertEquals(SalesContract.EntryMode.SCANNER, viewModel.uiState.value.mode)
            assertTrue(viewModel.uiState.value.unifiedInput)
            val restored = newViewModel(SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
            restored.onAction(SalesContract.Action.InitializeEntry(SalesContract.EntryKind.CREDIT, false, true))
            runCurrent()
            assertEquals(viewModel.uiState.value.cartLines, restored.uiState.value.cartLines)
            assertEquals(SalesContract.EntryStep.SELL, restored.uiState.value.entryStep)
            assertTrue(restored.uiState.value.unifiedInput)
            assertTrue(restored.uiState.value.canRouteScannerInput)
            assertTrue(sales.saveLineCalls.isEmpty())
        }

    @Test
    fun `unified back skips mode selector while keeping invalid cart input`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = true), unifiedInput = true)
            viewModel.onAction(SalesContract.Action.QuantityChanged(LINE_ID.value, ""))
            runCurrent()
            val lines = viewModel.uiState.value.cartLines
            viewModel.onAction(SalesContract.Action.StepBackSelected)
            runCurrent()
            assertEquals(SalesContract.EntryStep.SELECT_KIND, viewModel.uiState.value.entryStep)
            viewModel.onAction(SalesContract.Action.EntryKindChanged(SalesContract.EntryKind.CREDIT))
            runCurrent()
            assertEquals(SalesContract.EntryStep.SELL, viewModel.uiState.value.entryStep)
            assertEquals(lines, viewModel.uiState.value.cartLines)
            assertTrue(viewModel.uiState.value.hasPendingEdits)
            assertFalse(viewModel.uiState.value.canCheckout)
            assertTrue(sales.saveLineCalls.isEmpty())
        }

    @Test
    fun `unified direct credit back keeps invalid input behind existing discard confirmation`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(
                cart(withLine = true),
                entryKind = SalesContract.EntryKind.CREDIT,
                allowEntryKindSelection = false,
                unifiedInput = true,
            )
            val navigation = backgroundScope.async { viewModel.effects.first() }
            viewModel.onAction(SalesContract.Action.QuantityChanged(LINE_ID.value, ""))
            runCurrent()
            viewModel.onAction(SalesContract.Action.StepBackSelected)
            runCurrent()
            assertTrue(viewModel.uiState.value.discardEditsReview)
            assertTrue(viewModel.uiState.value.hasPendingEdits)
            assertEquals(SalesContract.EntryStep.SELL, viewModel.uiState.value.entryStep)
            assertFalse(navigation.isCompleted)
        }

    @Test
    fun `cash flow opens kind then mode and only accepts scanner input in the sale`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = false), enterSelling = false)

            assertEquals(SalesContract.EntryStep.SELECT_KIND, viewModel.uiState.value.entryStep)
            viewModel.onAction(SalesContract.Action.BarcodeScanned(EXISTING_BARCODE))
            viewModel.onAction(SalesContract.Action.EntryKindChanged(SalesContract.EntryKind.CASH))
            runCurrent()

            assertEquals(SalesContract.EntryStep.SELECT_MODE, viewModel.uiState.value.entryStep)
            viewModel.onAction(SalesContract.Action.BarcodeScanned(EXISTING_BARCODE))
            viewModel.onAction(SalesContract.Action.ProductSelected(PRODUCT_ID, LOCATION_ID))
            viewModel.onAction(SalesContract.Action.ScannerAvailabilityChanged(true))
            runCurrent()
            assertEquals(0, products.findByBarcodeCalls)
            assertTrue(sales.saveLineCalls.isEmpty())
            assertFalse(viewModel.uiState.value.scannerActive)

            viewModel.onAction(SalesContract.Action.ModeChanged(SalesContract.EntryMode.SCANNER))
            runCurrent()
            assertEquals(SalesContract.EntryStep.SELL, viewModel.uiState.value.entryStep)
            assertTrue(viewModel.uiState.value.canRouteScannerInput)

            viewModel.onAction(SalesContract.Action.BarcodeScanned(EXISTING_BARCODE))
            runCurrent()
            assertEquals(1, products.findByBarcodeCalls)
            assertEquals(1, sales.saveLineCalls.size)
        }

    @Test
    fun `direct credit starts at mode selection and restores the chosen sale screen`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val savedState = SavedStateHandle()
            val viewModel =
                createReadyViewModel(
                    cart(withLine = true),
                    enterSelling = false,
                    savedStateHandle = savedState,
                    entryKind = SalesContract.EntryKind.CREDIT,
                    allowEntryKindSelection = false,
                )

            assertEquals(SalesContract.EntryKind.CREDIT, viewModel.uiState.value.entryKind)
            assertEquals(SalesContract.EntryStep.SELECT_MODE, viewModel.uiState.value.entryStep)
            viewModel.onAction(SalesContract.Action.ModeChanged(SalesContract.EntryMode.MANUAL))
            runCurrent()
            viewModel.onAction(
                SalesContract.Action.InitializeEntry(SalesContract.EntryKind.CASH, true),
            )
            runCurrent()
            assertEquals(SalesContract.EntryKind.CREDIT, viewModel.uiState.value.entryKind)
            assertEquals(SalesContract.EntryStep.SELL, viewModel.uiState.value.entryStep)

            val restoredHandle =
                SavedStateHandle(
                    savedState.keys().associateWith { savedState.get<Any?>(it) },
                )
            val restored = newViewModel(restoredHandle)
            restored.onAction(
                SalesContract.Action.InitializeEntry(SalesContract.EntryKind.CREDIT, false),
            )
            runCurrent()

            assertEquals(SalesContract.EntryKind.CREDIT, restored.uiState.value.entryKind)
            assertEquals(SalesContract.EntryMode.MANUAL, restored.uiState.value.mode)
            assertEquals(SalesContract.EntryStep.SELL, restored.uiState.value.entryStep)
            assertEquals(viewModel.uiState.value.cartLines, restored.uiState.value.cartLines)
            assertFalse(restored.uiState.value.canRouteScannerInput)
        }

    @Test
    fun `step back keeps cart and unsaved inputs while moving through both selectors`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = true))
            val navigation = backgroundScope.async { viewModel.effects.first() }
            viewModel.onAction(SalesContract.Action.QuantityChanged(LINE_ID.value, ""))
            viewModel.onAction(SalesContract.Action.TextInputFocusChanged("quantity", true))
            runCurrent()
            val cartBeforeBack = viewModel.uiState.value.cartLines
            val cartId = viewModel.uiState.value.cartId

            viewModel.onAction(SalesContract.Action.StepBackSelected)
            runCurrent()
            assertEquals(SalesContract.EntryStep.SELECT_MODE, viewModel.uiState.value.entryStep)
            assertEquals(cartBeforeBack, viewModel.uiState.value.cartLines)
            assertTrue(viewModel.uiState.value.hasPendingEdits)
            assertFalse(viewModel.uiState.value.isTextInputFocused)
            assertFalse(viewModel.uiState.value.canCheckout)

            viewModel.onAction(SalesContract.Action.StepBackSelected)
            runCurrent()
            assertEquals(SalesContract.EntryStep.SELECT_KIND, viewModel.uiState.value.entryStep)
            viewModel.onAction(SalesContract.Action.EntryKindChanged(SalesContract.EntryKind.CASH))
            viewModel.onAction(SalesContract.Action.ModeChanged(SalesContract.EntryMode.MANUAL))
            runCurrent()

            assertEquals(SalesContract.EntryStep.SELL, viewModel.uiState.value.entryStep)
            assertEquals(cartId, viewModel.uiState.value.cartId)
            assertEquals(cartBeforeBack, viewModel.uiState.value.cartLines)
            assertTrue(viewModel.uiState.value.hasPendingEdits)
            assertTrue(sales.saveLineCalls.isEmpty())
            assertFalse(navigation.isCompleted)
        }

    @Test
    fun `step back exits a pending cash checkout without opening a locked selector`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            assertPendingCheckoutCanExitAndResume(debtorName = null)
        }

    @Test
    fun `step back preserves pending credit terms and verification after reentry`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            assertPendingCheckoutCanExitAndResume(debtorName = "Mayda")
        }

    private suspend fun TestScope.assertPendingCheckoutCanExitAndResume(debtorName: String?) {
        val pending =
            cart(withLine = true).copy(
                pendingCheckout = PendingSaleCheckout(debtorName, NOW.plusSeconds(86_400)),
            )
        val viewModel = createReadyViewModel(pending)
        val navigation = backgroundScope.async { viewModel.effects.first() }
        assertTrue(viewModel.uiState.value.isCheckoutPending)
        assertTrue(viewModel.uiState.value.canCheckout)

        viewModel.onAction(SalesContract.Action.StepBackSelected)
        runCurrent()

        assertEquals(SalesContract.Effect.Back, navigation.await())
        assertEquals(SalesContract.EntryStep.SELL, viewModel.uiState.value.entryStep)
        assertEquals(pending.saleId.value, viewModel.uiState.value.cartId)
        assertTrue(viewModel.uiState.value.isCheckoutPending)
        assertTrue(sales.saveLineCalls.isEmpty())
        assertTrue(sales.checkoutCalls.isEmpty())

        val restored = newViewModel()
        runCurrent()
        assertEquals(SalesContract.EntryStep.SELL, restored.uiState.value.entryStep)
        assertEquals(pending.saleId.value, restored.uiState.value.cartId)
        assertTrue(restored.uiState.value.isCheckoutPending)
        assertTrue(restored.uiState.value.canCheckout)
        restored.onAction(SalesContract.Action.CheckoutRequested)
        runCurrent()
        assertEquals(
            debtorName,
            restored.uiState.value.checkoutReview
                ?.debtorName,
        )
        assertNotNull(restored.uiState.value.checkoutReview)
        assertTrue(sales.checkoutCalls.isEmpty())
    }

    @Test
    fun `step back cancels queued scans and ignores the lookup that finishes in a selector`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = false))
            val lookupGate = CompletableDeferred<Unit>()
            products.barcodeLookupGate = lookupGate
            viewModel.onAction(SalesContract.Action.BarcodeScanned(EXISTING_BARCODE))
            runCurrent()
            viewModel.onAction(SalesContract.Action.BarcodeScanned(NEW_BARCODE))
            viewModel.onAction(SalesContract.Action.StepBackSelected)
            runCurrent()
            lookupGate.complete(Unit)
            runCurrent()

            assertEquals(SalesContract.EntryStep.SELECT_MODE, viewModel.uiState.value.entryStep)
            assertEquals(0, viewModel.uiState.value.pendingBarcodeCount)
            assertEquals(1, products.findByBarcodeCalls)
            assertTrue(sales.saveLineCalls.isEmpty())
            assertNull(viewModel.uiState.value.pendingAssociationBarcode)

            viewModel.onAction(SalesContract.Action.ModeChanged(SalesContract.EntryMode.SCANNER))
            runCurrent()
            viewModel.onAction(SalesContract.Action.BarcodeScanned(EXISTING_BARCODE))
            runCurrent()
            assertEquals(1, sales.saveLineCalls.size)
        }

    @Test
    fun `manual entry clears scanner association and previous search to show the full catalog`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = false))
            viewModel.onAction(SalesContract.Action.BarcodeScanned(NEW_BARCODE))
            runCurrent()
            assertEquals(NEW_BARCODE, viewModel.uiState.value.pendingAssociationBarcode)
            viewModel.onAction(SalesContract.Action.SearchChanged("Sin coincidencia"))
            runCurrent()
            assertTrue(viewModel.uiState.value.isNameSearchRunning)

            viewModel.onAction(SalesContract.Action.ModeChanged(SalesContract.EntryMode.MANUAL))
            runCurrent()
            advanceTimeBy(300L)
            runCurrent()

            assertEquals(SalesContract.EntryStep.SELL, viewModel.uiState.value.entryStep)
            assertEquals(SalesContract.EntryMode.MANUAL, viewModel.uiState.value.mode)
            assertEquals("", viewModel.uiState.value.query)
            assertFalse(viewModel.uiState.value.isNameSearchRunning)
            assertNull(viewModel.uiState.value.pendingAssociationBarcode)
            assertNull(viewModel.uiState.value.pendingReplacement)
            assertEquals(
                listOf(PRODUCT_ID),
                viewModel.uiState.value.availableProducts
                    .map { it.productId },
            )
        }

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
            assertEquals(
                0,
                sales.saveLineCalls
                    .single()
                    .quantity.value
                    .compareTo(BigDecimal("2")),
            )
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
            val back =
                async {
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
            val viewModel =
                createReadyViewModel(
                    cart = cart(withLine = false),
                    availableQuantity = BigDecimal("0.25"),
                )

            viewModel.onAction(SalesContract.Action.ProductSelected(PRODUCT_ID, LOCATION_ID))
            runCurrent()

            assertEquals(1, sales.saveLineCalls.size)
            assertEquals(
                0,
                sales.saveLineCalls
                    .single()
                    .quantity.value
                    .compareTo(BigDecimal("0.25")),
            )
            assertNull(viewModel.uiState.value.failure)
        }

    @Test
    fun `a new cart line starts with the catalog sale price in the cart currency`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val suggested = Money.fromMajor("8.75", CURRENCY)
            val viewModel =
                createReadyViewModel(
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
            val adjusted =
                original.copy(
                    lines =
                        listOf(
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
            val viewModel =
                createReadyViewModel(
                    cart = adjusted,
                    productSalePrice = Money.fromMajor("99.00", CURRENCY),
                )

            viewModel.onAction(SalesContract.Action.ModeChanged(SalesContract.EntryMode.MANUAL))
            runCurrent()
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
            val viewModel =
                createReadyViewModel(
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
            val completed =
                async {
                    viewModel.effects.first { it == SalesContract.Effect.CreditSalePosted }
                }

            viewModel.onAction(
                SalesContract.Action.EntryKindChanged(SalesContract.EntryKind.CREDIT),
            )
            viewModel.onAction(SalesContract.Action.ModeChanged(SalesContract.EntryMode.SCANNER))
            viewModel.onAction(SalesContract.Action.DebtorNameChanged("  María   Quispe  "))
            runCurrent()
            viewModel.onAction(SalesContract.Action.CheckoutRequested)
            runCurrent()

            assertEquals(
                "María Quispe",
                viewModel.uiState.value.checkoutReview
                    ?.debtorName,
            )
            viewModel.onAction(SalesContract.Action.CheckoutConfirmed)
            runCurrent()

            assertEquals("María Quispe", sales.checkoutCalls.single().debtorName)
            assertEquals(SalesContract.Effect.CreditSalePosted, completed.await())
            assertEquals(1, sales.openCalls.size)
        }

    @Test
    fun `confirmed credit checkout does not depend on a second cart read`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            assertSuccessfulCheckoutSurvivesReadFailure(CheckoutSaleResult.Posted(SALE_ID))
        }

    @Test
    fun `already posted credit checkout still reports success when subsequent reads fail`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            assertSuccessfulCheckoutSurvivesReadFailure(CheckoutSaleResult.AlreadyPosted(SALE_ID))
        }

    private suspend fun TestScope.assertSuccessfulCheckoutSurvivesReadFailure(result: CheckoutSaleResult) {
        val viewModel =
            createReadyViewModel(
                cart(withLine = true).copy(
                    pendingCheckout = PendingSaleCheckout("Mayda", NOW.plusSeconds(86_400)),
                ),
            )
        val observationsBefore = sales.observeCartCalls
        sales.checkoutHandler = {
            sales.failNewObservations = true
            result
        }
        val completed = backgroundScope.async { viewModel.effects.first() }
        viewModel.onAction(SalesContract.Action.CheckoutRequested)
        runCurrent()
        viewModel.onAction(SalesContract.Action.CheckoutConfirmed)
        runCurrent()

        assertEquals(SalesContract.Effect.CreditSalePosted, completed.await())
        assertNull(viewModel.uiState.value.failure)
        assertEquals(observationsBefore, sales.observeCartCalls)
        assertEquals("Mayda", sales.checkoutCalls.single().debtorName)
        assertEquals(NOW.plusSeconds(86_400), sales.checkoutCalls.single().debtDueAt)
        assertEquals(1, sales.openCalls.size)
    }

    @Test
    fun `failed refresh preserves pending checkout terms and requires reading before editing`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val pending =
                cart(withLine = true).copy(
                    pendingCheckout = PendingSaleCheckout("Mayda", NOW.plusSeconds(86_400)),
                )
            val viewModel = createReadyViewModel(pending)
            sales.checkoutHandler = {
                sales.failNewObservations = true
                CheckoutSaleResult.OnlineRequired
            }
            viewModel.onAction(SalesContract.Action.CheckoutRequested)
            runCurrent()
            viewModel.onAction(SalesContract.Action.CheckoutConfirmed)
            runCurrent()

            assertTrue(viewModel.uiState.value.isCheckoutPending)
            assertTrue(viewModel.uiState.value.cartLoadFailed)
            assertEquals(SalesContract.Failure.ONLINE_REQUIRED, viewModel.uiState.value.failure)
            assertEquals("Mayda", viewModel.uiState.value.debtorNameInput)
            assertEquals(pending.saleId.value, viewModel.uiState.value.cartId)
            viewModel.onAction(SalesContract.Action.QuantityChanged(LINE_ID.value, "9"))
            viewModel.onAction(SalesContract.Action.DebtorNameChanged("Otra persona"))
            runCurrent()
            assertTrue(sales.saveLineCalls.isEmpty())
            assertEquals("Mayda", viewModel.uiState.value.debtorNameInput)
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
            val viewModel =
                createReadyViewModel(
                    cart = cart(withLine = false),
                    productBarcode = null,
                )
            viewModel.onAction(SalesContract.Action.BarcodeScanned(NEW_BARCODE))
            runCurrent()
            assertEquals(NEW_BARCODE, viewModel.uiState.value.pendingAssociationBarcode)
            val catalogEmissionGate = CompletableDeferred<Unit>()
            products.catalogEmissionGate = catalogEmissionGate

            viewModel.onAction(SalesContract.Action.ProductSelected(PRODUCT_ID, LOCATION_ID))
            runCurrent()

            assertEquals(1, products.updateCalls)
            assertEquals(1, sales.saveLineCalls.size)
            assertNull(viewModel.uiState.value.pendingReplacement)
            assertNull(viewModel.uiState.value.pendingAssociationBarcode)
            assertEquals(
                NEW_BARCODE,
                viewModel.uiState.value.availableProducts
                    .single()
                    .barcode,
            )
            catalogEmissionGate.complete(Unit)
            runCurrent()
            assertEquals(
                NEW_BARCODE,
                viewModel.uiState.value.availableProducts
                    .single()
                    .barcode,
            )
        }

    @Test
    fun `first registered product accepts a second scan during its initial save without duplicating and checks out`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val registeredBarcode = "0012345678905"
            var persisted = cart(withLine = false)
            val viewModel =
                createReadyViewModel(
                    cart = persisted,
                    productBarcode = registeredBarcode,
                    productSalePrice = Money.fromMajor("5.00", CURRENCY),
                )
            val firstSaveGate = CompletableDeferred<Unit>()
            sales.saveLineHandler = { command ->
                if (sales.saveLineCalls.size == 1) firstSaveGate.await()
                persisted =
                    cart(withLine = true).withQuantity(
                        command.quantity.value.toPlainString(),
                        version = persisted.version + 1,
                    )
                SaleCartMutationResult.Saved(persisted)
            }

            viewModel.onAction(SalesContract.Action.BarcodeScanned(registeredBarcode))
            runCurrent()
            assertTrue(viewModel.uiState.value.isProcessingBarcode)
            assertTrue(viewModel.uiState.value.canRouteScannerInput)
            viewModel.onAction(SalesContract.Action.BarcodeScanned(registeredBarcode))
            runCurrent()
            assertEquals(SalesContract.EntryStep.SELL, viewModel.uiState.value.entryStep)
            assertEquals(1, viewModel.uiState.value.pendingBarcodeCount)
            assertEquals(1, sales.saveLineCalls.size)

            firstSaveGate.complete(Unit)
            runCurrent()
            assertEquals(listOf("1"), sales.saveLineCalls.map { it.quantity.value.toPlainString() })
            assertEquals(listOf(1L), sales.saveLineCalls.map { it.expectedVersion })
            assertEquals(
                "1",
                viewModel.uiState.value.cartLines
                    .single()
                    .quantityInput,
            )
            assertEquals(Money.fromMajor("5.00", CURRENCY), viewModel.uiState.value.total)
            assertEquals(false, viewModel.uiState.value.lastScanAdded?.alreadyInCart)
            assertEquals(1L, viewModel.uiState.value.lastScanAdded?.sequence)
            viewModel.onAction(SalesContract.Action.BarcodeScanned(registeredBarcode))
            runCurrent()
            assertEquals(1, sales.saveLineCalls.size)
            assertEquals(true, viewModel.uiState.value.lastScanAdded?.alreadyInCart)
            assertEquals(2L, viewModel.uiState.value.lastScanAdded?.sequence)
            assertTrue(viewModel.uiState.value.canCheckout)
            assertTrue(viewModel.uiState.value.canRouteScannerInput)
            assertEquals(SalesContract.EntryStep.SELL, viewModel.uiState.value.entryStep)

            sales.prepareCartToOpen(cart(withLine = false, saleId = SALE_ID_2))
            val posted =
                async {
                    viewModel.effects.first {
                        it == SalesContract.Effect.ShowMessage(SalesContract.Message.SALE_POSTED)
                    }
                }
            viewModel.onAction(SalesContract.Action.CheckoutRequested)
            runCurrent()
            viewModel.onAction(SalesContract.Action.CheckoutConfirmed)
            runCurrent()

            assertEquals(SALE_ID, sales.checkoutCalls.single().saleId)
            assertEquals(persisted.version, sales.checkoutCalls.single().expectedVersion)
            assertEquals(SalesContract.Effect.ShowMessage(SalesContract.Message.SALE_POSTED), posted.await())
            assertEquals(SALE_ID_2.value, viewModel.uiState.value.cartId)
            assertEquals(SalesContract.EntryStep.SELL, viewModel.uiState.value.entryStep)
            assertTrue(
                viewModel.uiState.value.cartLines
                    .isEmpty(),
            )
            assertTrue(viewModel.uiState.value.canRouteScannerInput)
        }

    @Test
    fun `registered SKU is recognized twice but adds once without asking for association or changing the product`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel =
                createReadyViewModel(
                    cart(withLine = false),
                    productBarcode = null,
                    productSku = "0012345678905",
                    productSalePrice = Money.fromMajor("5.00", CURRENCY),
                )
            val original = requireNotNull(products.findById(PRODUCT_ID))
            sales.saveLineHandler = { command ->
                SaleCartMutationResult.Saved(
                    cart(withLine = true).withQuantity(
                        command.quantity.value.toPlainString(),
                        command.expectedVersion + 1,
                    ),
                )
            }

            repeat(2) {
                viewModel.onAction(SalesContract.Action.BarcodeScanned("0012345678905"))
                runCurrent()
                assertNull(viewModel.uiState.value.pendingAssociationBarcode)
                assertNull(viewModel.uiState.value.failure)
            }

            assertEquals(listOf(PRODUCT_ID), sales.saveLineCalls.map { it.productId })
            assertEquals(
                "1",
                viewModel.uiState.value.cartLines
                    .single()
                    .quantityInput,
            )
            assertEquals(original, products.findById(PRODUCT_ID))
            assertEquals(0, products.updateCalls)
            assertEquals(true, viewModel.uiState.value.lastScanAdded?.alreadyInCart)
        }

    @Test
    fun `SKU lookup uses the saved price and preserves a different barcode`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = false), productSku = "COD-001")
            val catalogEmissionGate = CompletableDeferred<Unit>()
            products.catalogEmissionGate = catalogEmissionGate
            val saved =
                requireNotNull(products.findById(PRODUCT_ID)).copy(
                    salePrice = Money.fromMajor("8.75", CURRENCY),
                )
            assertTrue(products.update(saved))
            val updatesBeforeScan = products.updateCalls
            runCurrent()

            viewModel.onAction(SalesContract.Action.BarcodeScanned("cod-001"))
            runCurrent()

            assertEquals(saved.salePrice, sales.saveLineCalls.single().unitPrice)
            assertEquals(PRODUCT_ID, sales.saveLineCalls.single().productId)
            assertNull(viewModel.uiState.value.pendingAssociationBarcode)
            assertEquals(EXISTING_BARCODE, products.findById(PRODUCT_ID)?.barcode)
            assertEquals(updatesBeforeScan, products.updateCalls)
            catalogEmissionGate.complete(Unit)
            runCurrent()
        }

    @Test
    fun `exact barcode takes priority over the same SKU of another product`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = false))
            products.create(
                requireNotNull(products.findById(PRODUCT_ID)).copy(
                    productId = ProductId.from(uuid(50L)),
                    name = "Otro producto con SKU",
                    barcode = null,
                    sku = EXISTING_BARCODE,
                ),
            )
            runCurrent()

            viewModel.onAction(SalesContract.Action.BarcodeScanned(EXISTING_BARCODE))
            runCurrent()

            assertEquals(PRODUCT_ID, sales.saveLineCalls.single().productId)
            assertNull(viewModel.uiState.value.pendingAssociationBarcode)
            assertEquals(0, products.updateCalls)
        }

    @Test
    fun `SKU belonging to another business never adds or associates a product automatically`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = false))
            products.create(
                requireNotNull(products.findById(PRODUCT_ID)).copy(
                    productId = ProductId.from(uuid(50L)),
                    businessId = BusinessId.from(uuid(99L)),
                    barcode = null,
                    sku = NEW_BARCODE,
                ),
            )
            runCurrent()

            viewModel.onAction(SalesContract.Action.BarcodeScanned(NEW_BARCODE))
            runCurrent()

            assertEquals(NEW_BARCODE, viewModel.uiState.value.pendingAssociationBarcode)
            assertTrue(sales.saveLineCalls.isEmpty())
            assertEquals(0, products.updateCalls)
        }

    @Test
    fun `registered SKU without available stock is recognized without association`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel =
                createReadyViewModel(
                    cart(withLine = false),
                    availableQuantity = BigDecimal.ZERO,
                    productBarcode = null,
                    productSku = "SKU-001",
                )

            viewModel.onAction(SalesContract.Action.BarcodeScanned("SKU-001"))
            runCurrent()

            assertNull(viewModel.uiState.value.pendingAssociationBarcode)
            assertEquals(SalesContract.Failure.PRODUCT_UNAVAILABLE, viewModel.uiState.value.failure)
            assertTrue(sales.saveLineCalls.isEmpty())
            assertEquals(0, products.updateCalls)
        }

    @Test
    fun `scanner uses the persisted product price before a delayed catalog emission arrives`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = false))
            val catalogEmissionGate = CompletableDeferred<Unit>()
            products.catalogEmissionGate = catalogEmissionGate
            val product = requireNotNull(products.findById(PRODUCT_ID))
            val currentPrice = Money.fromMajor("8.75", CURRENCY)
            assertTrue(products.update(product.copy(salePrice = currentPrice)))
            runCurrent()
            assertNull(
                viewModel.uiState.value.availableProducts
                    .single()
                    .suggestedSalePrice,
            )

            viewModel.onAction(SalesContract.Action.BarcodeScanned(EXISTING_BARCODE))
            runCurrent()

            assertEquals(currentPrice, sales.saveLineCalls.single().unitPrice)
            assertNull(viewModel.uiState.value.failure)
            catalogEmissionGate.complete(Unit)
            runCurrent()
        }

    @Test
    fun `first scan finds registered stock before catalog observations arrive and can check out`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = false))
            inventory.replaceInventory(BUSINESS_ID, emptyList())
            runCurrent()
            val catalogEmissionGate = CompletableDeferred<Unit>()
            products.catalogEmissionGate = catalogEmissionGate
            val registered =
                products.create(
                    requireNotNull(products.findById(PRODUCT_ID)).copy(
                        productId = ProductId.from(uuid(50L)),
                        name = "Primer producto registrado",
                        barcode = NEW_BARCODE,
                        salePrice = Money.fromMajor("5.00", CURRENCY),
                    ),
                )
            val registeredStock =
                inventoryItem(BigDecimal("2.5")).copy(
                    productId = registered.productId,
                    productName = registered.name,
                )
            inventory.setProductDetail(
                BUSINESS_ID,
                registered.productId,
                InventoryProductDetail(registeredStock, emptyList()),
            )
            val registeredCart =
                cart(withLine = true).let { cart ->
                    cart.copy(lines = cart.lines.map { it.copy(productId = registered.productId, productName = registered.name) })
                }
            sales.saveLineHandler = { command ->
                SaleCartMutationResult.Saved(
                    registeredCart.withQuantity(command.quantity.value.toPlainString(), command.expectedVersion + 1),
                )
            }
            runCurrent()
            assertTrue(
                viewModel.uiState.value.availableProducts
                    .isEmpty(),
            )

            viewModel.onAction(SalesContract.Action.BarcodeScanned(NEW_BARCODE))
            runCurrent()

            assertEquals(registered.productId, sales.saveLineCalls.single().productId)
            assertEquals(registered.salePrice, sales.saveLineCalls.single().unitPrice)
            assertEquals(listOf(BUSINESS_ID to registered.productId), inventory.observedProducts)
            assertEquals(
                BigDecimal("2.5"),
                viewModel.uiState.value.cartLines
                    .single()
                    .availableQuantity,
            )
            assertTrue(
                viewModel.uiState.value.cartLines
                    .single()
                    .quantityValid,
            )
            assertTrue(viewModel.uiState.value.canCheckout)
            assertNull(viewModel.uiState.value.failure)

            viewModel.onAction(SalesContract.Action.BarcodeScanned(NEW_BARCODE))
            runCurrent()
            assertEquals(1, sales.saveLineCalls.size)
            assertEquals(1, inventory.observedProducts.size)
            assertEquals(
                "1",
                viewModel.uiState.value.cartLines
                    .single()
                    .quantityInput,
            )
            assertTrue(viewModel.uiState.value.canCheckout)
            assertEquals(true, viewModel.uiState.value.lastScanAdded?.alreadyInCart)

            inventory.replaceInventory(BUSINESS_ID, listOf(registeredStock))
            catalogEmissionGate.complete(Unit)
            runCurrent()
            assertTrue(viewModel.uiState.value.canCheckout)
        }

    @Test
    fun `scanner fallback still rejects zero stock and archived warehouses`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = false))
            inventory.replaceInventory(BUSINESS_ID, emptyList())
            runCurrent()
            val archived =
                inventoryItem().let { item ->
                    item.copy(positions = item.positions.map { it.copy(alerts = setOf(InventoryDataAlert.ARCHIVED_LOCATION)) })
                }
            listOf(inventoryItem(BigDecimal.ZERO), archived).forEach { item ->
                inventory.setProductDetail(BUSINESS_ID, PRODUCT_ID, InventoryProductDetail(item, emptyList()))
                viewModel.onAction(SalesContract.Action.BarcodeScanned(EXISTING_BARCODE))
                runCurrent()
                assertEquals(SalesContract.Failure.PRODUCT_UNAVAILABLE, viewModel.uiState.value.failure)
                assertTrue(sales.saveLineCalls.isEmpty())
                assertTrue(
                    viewModel.uiState.value.availableProducts
                        .isEmpty(),
                )
            }
        }

    @Test
    fun `leaving the scanner ignores a product detail that finishes after the step changes`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val detailGate = CompletableDeferred<Unit>()
            var detailLookupStarted = false
            val delayedInventory =
                object : InventoryReadRepository by inventory {
                    override fun observeProductItem(
                        businessId: BusinessId,
                        productId: ProductId,
                    ) = flow {
                        detailLookupStarted = true
                        detailGate.await()
                        emit(inventoryItem())
                    }
                }
            val viewModel =
                createReadyViewModel(
                    cart = cart(withLine = false),
                    inventoryReadRepository = delayedInventory,
                )
            inventory.replaceInventory(BUSINESS_ID, emptyList())
            runCurrent()
            viewModel.onAction(SalesContract.Action.BarcodeScanned(EXISTING_BARCODE))
            runCurrent()
            assertTrue(detailLookupStarted)

            viewModel.onAction(SalesContract.Action.StepBackSelected)
            runCurrent()
            detailGate.complete(Unit)
            runCurrent()

            assertEquals(SalesContract.EntryStep.SELECT_MODE, viewModel.uiState.value.entryStep)
            assertTrue(sales.saveLineCalls.isEmpty())
            assertTrue(
                viewModel.uiState.value.availableProducts
                    .isEmpty(),
            )
            assertTrue(
                viewModel.uiState.value.pendingLocations
                    .isEmpty(),
            )
            assertEquals(0, viewModel.uiState.value.pendingBarcodeCount)
        }

    @Test
    fun `scanned warehouse selection uses current price while catalog emission is delayed`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = false))
            val item = inventoryItem()
            inventory.replaceInventory(
                BUSINESS_ID,
                listOf(
                    item.copy(
                        positions =
                            item.positions +
                                item.positions.single().copy(
                                    locationId = LocationId.from(uuid(20L)),
                                    locationName = "Almacén 2",
                                ),
                    ),
                ),
            )
            runCurrent()
            viewModel.onAction(SalesContract.Action.BarcodeScanned(EXISTING_BARCODE))
            runCurrent()
            assertEquals(2, viewModel.uiState.value.pendingLocations.size)

            val catalogEmissionGate = CompletableDeferred<Unit>()
            products.catalogEmissionGate = catalogEmissionGate
            val currentPrice = Money.fromMajor("8.75", CURRENCY)
            assertTrue(products.update(requireNotNull(products.findById(PRODUCT_ID)).copy(salePrice = currentPrice)))
            runCurrent()
            viewModel.onAction(SalesContract.Action.LocationSelected(PRODUCT_ID, LOCATION_ID))
            runCurrent()

            assertEquals(currentPrice, sales.saveLineCalls.single().unitPrice)
            catalogEmissionGate.complete(Unit)
            runCurrent()
        }

    @Test
    fun `rapid scans of different products stay registered and save with the next cart version`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            var persisted = cart(withLine = false)
            val viewModel = createReadyViewModel(persisted)
            val second = seedAdditionalProduct(50L, NEW_BARCODE)
            val third = seedAdditionalProduct(51L, "7759999999999")
            val storageGate = CompletableDeferred<Unit>()
            sales.saveLineHandler = { command ->
                if (sales.saveLineCalls.size == 1) storageGate.await()
                val line = cart(withLine = true).lines.single().copy(
                    saleLineId = SaleLineId.from(uuid(200L + persisted.lines.size)),
                    productId = command.productId,
                    quantity = command.quantity,
                    position = persisted.lines.size,
                )
                val total = Money.ofMinor((persisted.lines.size + 1) * 500L, CURRENCY)
                persisted =
                    persisted.copy(
                        lines = persisted.lines + line,
                        version = command.expectedVersion + 1,
                        subtotal = total,
                        total = total,
                        contentHash = (command.expectedVersion + 1).toString().repeat(64),
                    )
                SaleCartMutationResult.Saved(persisted)
            }

            viewModel.onAction(SalesContract.Action.BarcodeScanned(EXISTING_BARCODE))
            runCurrent()
            assertTrue(viewModel.uiState.value.isMutating)
            assertTrue(viewModel.uiState.value.canRouteScannerInput)
            viewModel.onAction(SalesContract.Action.BarcodeScanned(requireNotNull(second.barcode)))
            viewModel.onAction(SalesContract.Action.BarcodeScanned(requireNotNull(third.barcode)))
            runCurrent()

            assertEquals(3, viewModel.uiState.value.pendingBarcodeCount)
            assertEquals(1, sales.saveLineCalls.size)
            assertFalse(viewModel.uiState.value.canCheckout)
            storageGate.complete(Unit)
            runCurrent()

            assertEquals(listOf("1", "1", "1"), sales.saveLineCalls.map { it.quantity.value.toPlainString() })
            assertEquals(listOf(PRODUCT_ID, second.productId, third.productId), sales.saveLineCalls.map { it.productId })
            assertEquals(listOf(1L, 2L, 3L), sales.saveLineCalls.map { it.expectedVersion })
            assertEquals(3, products.findByBarcodeCalls)
            assertEquals(0, viewModel.uiState.value.pendingBarcodeCount)
            assertEquals(3, viewModel.uiState.value.cartLines.size)
            assertEquals(Money.fromMajor("15.00", CURRENCY), viewModel.uiState.value.total)
            assertTrue(viewModel.uiState.value.canRouteScannerInput)
            assertTrue(viewModel.uiState.value.canCheckout)
        }

    @Test
    fun `editing focus pauses scanner before the first character and releases each field separately`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = true))
            viewModel.onAction(SalesContract.Action.TextInputFocusChanged("debtor", true))
            viewModel.onAction(SalesContract.Action.TextInputFocusChanged("quantity", true))
            viewModel.onAction(SalesContract.Action.TextInputFocusChanged("debtor", false))
            viewModel.onAction(SalesContract.Action.BarcodeScanned(EXISTING_BARCODE))
            runCurrent()

            assertFalse(viewModel.uiState.value.canRouteScannerInput)
            assertEquals(0, products.findByBarcodeCalls)
            assertTrue(sales.saveLineCalls.isEmpty())
            viewModel.onAction(SalesContract.Action.TextInputFocusChanged("quantity", false))
            viewModel.onAction(SalesContract.Action.BarcodeScanned(EXISTING_BARCODE))
            runCurrent()

            assertTrue(viewModel.uiState.value.canRouteScannerInput)
            assertTrue(sales.saveLineCalls.isEmpty())
            assertEquals(1, products.findByBarcodeCalls)
            assertEquals(true, viewModel.uiState.value.lastScanAdded?.alreadyInCart)
        }

    @Test
    fun `an unknown barcode does not block the corrected scan or change the existing cart price`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val original = cart(withLine = true)
            val viewModel = createReadyViewModel(original)
            viewModel.onAction(SalesContract.Action.BarcodeScanned(NEW_BARCODE))
            runCurrent()
            assertEquals(NEW_BARCODE, viewModel.uiState.value.pendingAssociationBarcode)
            assertTrue(viewModel.uiState.value.canRouteScannerInput)

            viewModel.onAction(SalesContract.Action.BarcodeScanned(EXISTING_BARCODE))
            runCurrent()

            assertTrue(sales.saveLineCalls.isEmpty())
            assertEquals(
                "1",
                viewModel.uiState.value.cartLines
                    .single()
                    .quantityInput,
            )
            assertEquals("5.00", viewModel.uiState.value.cartLines.single().unitPriceInput)
            assertEquals(true, viewModel.uiState.value.lastScanAdded?.alreadyInCart)
            assertNull(viewModel.uiState.value.pendingAssociationBarcode)
            assertTrue(viewModel.uiState.value.canRouteScannerInput)
            assertEquals(0, products.updateCalls)
            assertEquals(EXISTING_BARCODE, productDelegate.findById(PRODUCT_ID)?.barcode)
        }

    @Test
    fun `association search focus still rejects incoming scans until the editor releases focus`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = false))
            viewModel.onAction(SalesContract.Action.BarcodeScanned(NEW_BARCODE))
            runCurrent()
            viewModel.onAction(SalesContract.Action.TextInputFocusChanged("search", true))
            viewModel.onAction(SalesContract.Action.BarcodeScanned(EXISTING_BARCODE))
            runCurrent()

            assertEquals(NEW_BARCODE, viewModel.uiState.value.pendingAssociationBarcode)
            assertEquals(1, products.findByBarcodeCalls)
            assertTrue(sales.saveLineCalls.isEmpty())
            assertFalse(viewModel.uiState.value.canRouteScannerInput)

            viewModel.onAction(SalesContract.Action.TextInputFocusChanged("search", false))
            viewModel.onAction(SalesContract.Action.BarcodeScanned(EXISTING_BARCODE))
            runCurrent()

            assertEquals(1, sales.saveLineCalls.size)
            assertNull(viewModel.uiState.value.pendingAssociationBarcode)
            assertEquals(0, products.updateCalls)
        }

    @Test
    fun `a known scan queued behind an unknown scan continues without canceling association manually`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = false))
            val lookupGate = CompletableDeferred<Unit>()
            products.barcodeLookupGate = lookupGate
            viewModel.onAction(SalesContract.Action.BarcodeScanned(NEW_BARCODE))
            runCurrent()
            viewModel.onAction(SalesContract.Action.BarcodeScanned(EXISTING_BARCODE))
            runCurrent()
            lookupGate.complete(Unit)
            runCurrent()

            // La elección de asociación ya no bloquea lecturas aceptadas antes de mostrarla.
            assertNull(viewModel.uiState.value.pendingAssociationBarcode)
            assertEquals(2, products.findByBarcodeCalls)
            assertEquals(listOf(NEW_BARCODE, EXISTING_BARCODE), products.barcodeLookups.map { it.second })
            assertEquals(1, sales.saveLineCalls.size)
            assertEquals(0, viewModel.uiState.value.pendingBarcodeCount)
            assertTrue(viewModel.uiState.value.canRouteScannerInput)
            assertEquals(0, products.updateCalls)
        }

    @Test
    fun `leaving the scanner session cancels pending scans and a late lookup before saving`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = false))
            val lookupGate = CompletableDeferred<Unit>()
            products.barcodeLookupGate = lookupGate
            viewModel.onAction(SalesContract.Action.BarcodeScanned(EXISTING_BARCODE))
            runCurrent()
            viewModel.onAction(SalesContract.Action.BarcodeScanned(NEW_BARCODE))
            viewModel.onAction(SalesContract.Action.ScannerSessionStopped)
            lookupGate.complete(Unit)
            runCurrent()

            assertEquals(1, products.findByBarcodeCalls)
            assertTrue(sales.saveLineCalls.isEmpty())
            assertEquals(0, viewModel.uiState.value.pendingBarcodeCount)
            viewModel.onAction(SalesContract.Action.BarcodeScanned(EXISTING_BARCODE))
            runCurrent()
            assertEquals(1, sales.saveLineCalls.size)
        }

    @Test
    fun `switching to manual cancels backlog and ignores barcode callbacks`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = false))
            val lookupGate = CompletableDeferred<Unit>()
            products.barcodeLookupGate = lookupGate
            viewModel.onAction(SalesContract.Action.BarcodeScanned(EXISTING_BARCODE))
            runCurrent()
            viewModel.onAction(SalesContract.Action.BarcodeScanned(NEW_BARCODE))
            viewModel.onAction(SalesContract.Action.ModeChanged(SalesContract.EntryMode.MANUAL))
            runCurrent()
            lookupGate.complete(Unit)
            runCurrent()
            viewModel.onAction(SalesContract.Action.BarcodeScanned(EXISTING_BARCODE))
            runCurrent()

            assertEquals(1, products.findByBarcodeCalls)
            assertTrue(sales.saveLineCalls.isEmpty())
            assertEquals(0, viewModel.uiState.value.pendingBarcodeCount)
            viewModel.onAction(SalesContract.Action.ModeChanged(SalesContract.EntryMode.SCANNER))
            runCurrent()
            assertTrue(sales.saveLineCalls.isEmpty())
        }

    @Test
    fun `scanner queue is bounded for different codes and keeps an overflow warning through processing`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = false))
            sales.saveLineHandler = { SaleCartMutationResult.Saved(cart(withLine = true)) }
            val lookupGate = CompletableDeferred<Unit>()
            products.barcodeLookupGate = lookupGate
            val codes = listOf(EXISTING_BARCODE) + (1..32).map { "7751111000" + it.toString().padStart(3, '0') }
            codes.forEach { code ->
                viewModel.onAction(SalesContract.Action.BarcodeScanned(code))
            }
            runCurrent()

            assertEquals(32, viewModel.uiState.value.pendingBarcodeCount)
            assertEquals(SalesContract.ScannerFailure.QUEUE_FULL, viewModel.uiState.value.scannerFailure)
            lookupGate.complete(Unit)
            runCurrent()

            assertEquals(1, sales.saveLineCalls.size)
            assertEquals(codes.take(32), products.barcodeLookups.map { it.second })
            assertEquals(0, viewModel.uiState.value.pendingBarcodeCount)
            assertEquals(SalesContract.ScannerFailure.QUEUE_FULL, viewModel.uiState.value.scannerFailure)
        }

    @Test
    fun `scanner reset only clears the warning and the next barcode can still identify the cart product`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = true))
            val registeredProduct = requireNotNull(productDelegate.findById(PRODUCT_ID))
            val stock = inventory.observeInventory(BUSINESS_ID).first()

            SalesContract.ScannerFailure.entries.forEach { failure ->
                viewModel.onAction(SalesContract.Action.ScannerReadFailed(failure))
                runCurrent()
                val before = viewModel.uiState.value
                assertEquals(failure, before.scannerFailure)

                viewModel.onAction(SalesContract.Action.ScannerReadReset)
                runCurrent()

                assertEquals(before.copy(scannerFailure = null), viewModel.uiState.value)
                assertEquals(0, products.findByBarcodeCalls)
                assertEquals(0, products.updateCalls)
                assertTrue(sales.saveLineCalls.isEmpty())
                assertTrue(sales.checkoutCalls.isEmpty())
                assertEquals(registeredProduct, productDelegate.findById(PRODUCT_ID))
                assertEquals(stock, inventory.observeInventory(BUSINESS_ID).first())
            }

            viewModel.onAction(SalesContract.Action.BarcodeScanned(EXISTING_BARCODE))
            runCurrent()

            assertEquals(1, products.findByBarcodeCalls)
            assertTrue(sales.saveLineCalls.isEmpty())
            assertEquals(true, viewModel.uiState.value.lastScanAdded?.alreadyInCart)
            assertNull(viewModel.uiState.value.scannerFailure)
        }

    @Test
    fun `scanner reset during lookup preserves accepted barcode and SKU without duplicating their product`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val sku = "ALIAS-001"
            val viewModel = createReadyViewModel(cart(withLine = false), productSku = sku)
            sales.saveLineHandler = { SaleCartMutationResult.Saved(cart(withLine = true)) }
            val lookupGate = CompletableDeferred<Unit>()
            products.barcodeLookupGate = lookupGate
            viewModel.onAction(SalesContract.Action.BarcodeScanned(EXISTING_BARCODE))
            runCurrent()
            viewModel.onAction(SalesContract.Action.BarcodeScanned(sku))
            viewModel.onAction(SalesContract.Action.ScannerReadFailed(SalesContract.ScannerFailure.TOO_LONG))
            runCurrent()
            val pending = viewModel.uiState.value
            assertTrue(pending.isProcessingBarcode)
            assertTrue(pending.canRouteScannerInput)
            assertEquals(2, pending.pendingBarcodeCount)
            assertTrue(sales.saveLineCalls.isEmpty())

            viewModel.onAction(SalesContract.Action.ScannerReadReset)
            runCurrent()

            assertEquals(pending.copy(scannerFailure = null), viewModel.uiState.value)
            assertEquals(1, products.findByBarcodeCalls)
            assertTrue(sales.saveLineCalls.isEmpty())
            lookupGate.complete(Unit)
            runCurrent()

            assertEquals(listOf(EXISTING_BARCODE, sku), products.barcodeLookups.map { it.second })
            assertEquals(1, sales.saveLineCalls.size)
            assertEquals(true, viewModel.uiState.value.lastScanAdded?.alreadyInCart)
            assertEquals(0, viewModel.uiState.value.pendingBarcodeCount)
            assertFalse(viewModel.uiState.value.isProcessingBarcode)
            assertNull(viewModel.uiState.value.scannerFailure)
            assertEquals(0, products.updateCalls)
            assertTrue(sales.checkoutCalls.isEmpty())
        }

    @Test
    fun `scanner reset is ignored while a cart field owns input focus`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = true))
            viewModel.onAction(SalesContract.Action.ScannerReadFailed(SalesContract.ScannerFailure.INCOMPLETE))
            viewModel.onAction(SalesContract.Action.TextInputFocusChanged("quantity", true))
            runCurrent()
            val before = viewModel.uiState.value
            assertFalse(before.canRouteScannerInput)

            viewModel.onAction(SalesContract.Action.ScannerReadReset)
            runCurrent()

            assertEquals(before, viewModel.uiState.value)
            assertEquals(0, products.findByBarcodeCalls)
            assertTrue(sales.saveLineCalls.isEmpty())
            assertTrue(sales.checkoutCalls.isEmpty())
        }

    @Test
    fun `selecting a scanned location refreshes stock and sale price before adding`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel =
                createReadyViewModel(
                    cart(withLine = false),
                    productSalePrice = Money.fromMajor("5.00", CURRENCY),
                )
            val original = inventoryItem()
            val secondPosition =
                original.positions.single().copy(
                    locationId = LocationId.from(uuid(20L)),
                    locationName = "Almacén 2",
                )
            inventory.replaceInventory(
                BUSINESS_ID,
                listOf(original.copy(positions = original.positions + secondPosition)),
            )
            runCurrent()
            viewModel.onAction(SalesContract.Action.BarcodeScanned(EXISTING_BARCODE))
            runCurrent()
            assertEquals(2, viewModel.uiState.value.pendingLocations.size)

            val currentPrice = Money.fromMajor("7.50", CURRENCY)
            productDelegate.update(requireNotNull(productDelegate.findById(PRODUCT_ID)).copy(salePrice = currentPrice))
            inventory.replaceInventory(
                BUSINESS_ID,
                listOf(
                    inventoryItem(BigDecimal("0.25")).copy(
                        positions = inventoryItem(BigDecimal("0.25")).positions + secondPosition,
                    ),
                ),
            )
            runCurrent()
            viewModel.onAction(SalesContract.Action.LocationSelected(PRODUCT_ID, LOCATION_ID))
            runCurrent()

            val command = sales.saveLineCalls.single()
            assertEquals(0, BigDecimal("0.25").compareTo(command.quantity.value))
            assertEquals(currentPrice, command.unitPrice)
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
    fun `scanner preserves registered leading zeros and uses that product stock and sale price`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val registeredBarcode = "0012345678905"
            val salePrice = Money.fromMajor("8.75", CURRENCY)
            val stock = BigDecimal("0.75")
            val viewModel =
                createReadyViewModel(
                    cart = cart(withLine = false),
                    availableQuantity = stock,
                    productBarcode = registeredBarcode,
                    productSalePrice = salePrice,
                )
            val registeredProduct = requireNotNull(productDelegate.findById(PRODUCT_ID))
            val registeredStock = inventory.observeInventory(BUSINESS_ID).first().single()

            viewModel.onAction(SalesContract.Action.BarcodeScanned(registeredBarcode))
            runCurrent()

            val command = sales.saveLineCalls.single()
            assertEquals(listOf(BUSINESS_ID to registeredBarcode), products.barcodeLookups)
            assertEquals(registeredBarcode, registeredProduct.barcode)
            assertEquals(registeredProduct.productId, command.productId)
            assertEquals(registeredStock.productId, command.productId)
            assertEquals(registeredStock.positions.single().locationId, command.locationId)
            assertEquals(0, stock.compareTo(command.quantity.value))
            // El costo de inventario de la fixture es 1.00; la venta debe usar 8.75.
            assertEquals(salePrice, command.unitPrice)
            assertEquals(listOf(BUSINESS_ID), sales.saveLineBusinessIds)
            assertEquals(0, products.updateCalls)
            assertNull(viewModel.uiState.value.pendingAssociationBarcode)
            assertTrue(
                viewModel.uiState.value.pendingLocations
                    .isEmpty(),
            )
            assertNull(viewModel.uiState.value.failure)
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
            assertTrue(
                viewModel.uiState.value.productOptions
                    .isEmpty(),
            )
            advanceTimeBy(NAME_SEARCH_DEBOUNCE_MILLIS)
            runCurrent()
            assertEquals(
                SalesContract.NameMatchKind.EXACT,
                viewModel.uiState.value.productOptions
                    .single()
                    .nameMatchKind,
            )

            viewModel.onAction(SalesContract.Action.SearchChanged("Producto extra"))
            advanceTimeBy(NAME_SEARCH_DEBOUNCE_MILLIS)
            runCurrent()
            assertEquals(
                SalesContract.NameMatchKind.SIMILAR,
                viewModel.uiState.value.productOptions
                    .single()
                    .nameMatchKind,
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
            assertFalse(
                viewModel.uiState.value.cartLines
                    .single()
                    .quantityValid,
            )

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
            assertTrue(
                viewModel.uiState.value.productOptions
                    .isEmpty(),
            )
        }

    @Test
    fun `large manual catalog is reused while typing searching and editing the cart`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = true))
            val template = requireNotNull(products.findById(PRODUCT_ID))
            val extraInventory =
                (1..1_000).map { index ->
                    val product =
                        products.create(
                            template.copy(
                                productId = ProductId.from(uuid(100L + index)),
                                name = "Articulo ${(1_001 - index).toString().padStart(4, '0')}",
                                barcode = null,
                            ),
                        )
                    inventoryItem().copy(productId = product.productId, productName = product.name)
                }
            inventory.replaceInventory(BUSINESS_ID, extraInventory + inventoryItem())
            viewModel.onAction(SalesContract.Action.ModeChanged(SalesContract.EntryMode.MANUAL))
            runCurrent()
            val catalog = viewModel.uiState.value.availableProducts
            assertEquals(1_001, catalog.size)
            assertEquals("Articulo 0001", catalog.first().productName)
            assertEquals("Producto", catalog.last().productName)

            repeat(30) { index ->
                viewModel.onAction(SalesContract.Action.SearchChanged("Articulo $index"))
                viewModel.onAction(
                    SalesContract.Action.QuantityChanged(LINE_ID.value, ((index % 9) + 1).toString()),
                )
                runCurrent()
                assertSame(catalog, viewModel.uiState.value.availableProducts)
            }
            viewModel.onAction(SalesContract.Action.SearchChanged("Articulo 0001"))
            advanceTimeBy(NAME_SEARCH_DEBOUNCE_MILLIS)
            runCurrent()

            assertSame(catalog, viewModel.uiState.value.availableProducts)
            assertEquals(
                "Articulo 0001",
                viewModel.uiState.value.productOptions
                    .first()
                    .productName,
            )
            viewModel.onAction(SalesContract.Action.SearchChanged(""))
            runCurrent()
            assertSame(catalog, viewModel.uiState.value.availableProducts)
            assertTrue(
                viewModel.uiState.value.productOptions
                    .isEmpty(),
            )
        }

    @Test
    fun `cached manual catalog refreshes order price and stock after repository changes`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = false))
            val original = requireNotNull(products.findById(PRODUCT_ID))
            val second =
                products.create(
                    original.copy(productId = ProductId.from(uuid(100L)), name = "Zeta", barcode = null),
                )
            val secondInventory = inventoryItem().copy(productId = second.productId, productName = second.name)
            inventory.replaceInventory(BUSINESS_ID, listOf(inventoryItem(), secondInventory))
            runCurrent()
            assertEquals(
                listOf("Producto", "Zeta"),
                viewModel.uiState.value.availableProducts
                    .map { it.productName },
            )

            val newPrice = Money.fromMajor("7.25", CURRENCY)
            assertTrue(products.update(second.copy(name = "Alfa", salePrice = newPrice)))
            inventory.replaceInventory(
                BUSINESS_ID,
                listOf(inventoryItem(BigDecimal("7.5")), secondInventory),
            )
            runCurrent()

            val refreshed = viewModel.uiState.value.availableProducts
            assertEquals(listOf("Alfa", "Producto"), refreshed.map { it.productName })
            assertEquals(newPrice, refreshed.first().suggestedSalePrice)
            assertEquals(0, refreshed.last().availableQuantity.compareTo(BigDecimal("7.5")))

            inventory.replaceInventory(
                BUSINESS_ID,
                listOf(inventoryItem(BigDecimal.ZERO), secondInventory),
            )
            runCurrent()
            assertEquals(
                listOf(second.productId),
                viewModel.uiState.value.availableProducts
                    .map { it.productId },
            )
        }

    @Test
    fun `dismissing barcode association cancels its pending hidden name search`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = false))
            viewModel.onAction(SalesContract.Action.BarcodeScanned(NEW_BARCODE))
            runCurrent()
            viewModel.onAction(SalesContract.Action.SearchChanged("Producto"))
            runCurrent()
            assertTrue(viewModel.uiState.value.isNameSearchRunning)

            viewModel.onAction(SalesContract.Action.AssociationDismissed)
            runCurrent()
            advanceTimeBy(NAME_SEARCH_DEBOUNCE_MILLIS)
            runCurrent()

            assertEquals(0, products.nameSearchCalls)
            assertEquals("", viewModel.uiState.value.query)
            assertFalse(viewModel.uiState.value.isNameSearchRunning)
            assertNull(viewModel.uiState.value.pendingAssociationBarcode)
            assertEquals(
                listOf(PRODUCT_ID),
                viewModel.uiState.value.availableProducts
                    .map { it.productId },
            )
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
                viewModel.uiState.value.productOptions
                    .single()
                    .nameMatchKind,
            )

            val current = requireNotNull(products.findById(PRODUCT_ID))
            assertTrue(products.update(current.copy(name = "Articulo renombrado")))
            runCurrent()

            assertTrue(viewModel.uiState.value.isNameSearchRunning)
            assertTrue(
                viewModel.uiState.value.productOptions
                    .isEmpty(),
            )
            advanceTimeBy(NAME_SEARCH_DEBOUNCE_MILLIS)
            runCurrent()

            assertFalse(viewModel.uiState.value.isNameSearchRunning)
            assertTrue(
                viewModel.uiState.value.productOptions
                    .isEmpty(),
            )
        }

    @Test
    fun `archived inventory locations are excluded from sale options`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = false))
            viewModel.onAction(SalesContract.Action.SearchChanged("Producto"))
            advanceTimeBy(NAME_SEARCH_DEBOUNCE_MILLIS)
            runCurrent()
            assertEquals(1, viewModel.uiState.value.productOptions.size)
            val archived =
                inventoryItem().let { item ->
                    item.copy(
                        positions =
                            item.positions.map { position ->
                                position.copy(alerts = setOf(InventoryDataAlert.ARCHIVED_LOCATION))
                            },
                    )
                }

            inventory.replaceInventory(BUSINESS_ID, listOf(archived))
            runCurrent()

            assertTrue(
                viewModel.uiState.value.productOptions
                    .isEmpty(),
            )
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
            assertEquals(
                0,
                sales.saveLineCalls
                    .last()
                    .quantity.value
                    .compareTo(BigDecimal("2")),
            )
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
            assertEquals(
                750L,
                sales.saveLineCalls
                    .single()
                    .unitPrice
                    ?.minorUnits,
            )
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

            assertEquals(
                "3",
                viewModel.uiState.value.cartLines
                    .single()
                    .quantityInput,
            )
            assertNull(viewModel.uiState.value.checkoutReview)

            firstSave.complete(SaleCartMutationResult.Saved(savedTwo))
            runCurrent()

            assertFalse(viewModel.uiState.value.isSavingLineEdits)
            assertTrue(viewModel.uiState.value.hasPendingEdits)
            assertEquals(
                "3",
                viewModel.uiState.value.cartLines
                    .single()
                    .quantityInput,
            )

            advanceTimeBy(350L)
            runCurrent()

            assertEquals(listOf(1L, 2L), sales.saveLineCalls.map { it.expectedVersion })
            assertFalse(viewModel.uiState.value.isSavingLineEdits)
            assertFalse(viewModel.uiState.value.hasPendingEdits)
            assertEquals(
                "3",
                viewModel.uiState.value.cartLines
                    .single()
                    .quantityInput,
            )
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
            val adjusted =
                original.copy(
                    lines =
                        listOf(
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

            assertEquals(
                adjustedTotal,
                viewModel.uiState.value.cartLines
                    .single()
                    .lineTotal,
            )
            assertEquals(adjustedTotal, viewModel.uiState.value.total)
        }

    @Test
    fun `incomplete scans suggest one to three missing digits without changing product or cart`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val complete = "7753176004930"
            val viewModel = createReadyViewModel(cart(withLine = false), productBarcode = complete)
            val original = products.findById(PRODUCT_ID)
            listOf("753176004930", "53176004930", "3176004930").forEachIndexed { index, scanned ->
                viewModel.onAction(SalesContract.Action.BarcodeScanned(scanned))
                runCurrent()
                val suggestion =
                    viewModel.uiState.value.barcodeSuggestions
                        .single()
                assertEquals(index + 1, suggestion.missingDigits)
                assertEquals(complete, suggestion.product.barcode)
                assertEquals(scanned, viewModel.uiState.value.pendingAssociationBarcode)
                assertTrue(viewModel.uiState.value.canRouteScannerInput)
            }
            assertTrue(sales.saveLineCalls.isEmpty())
            assertEquals(0, products.updateCalls)
            assertEquals(original, products.findById(PRODUCT_ID))
        }

    @Test
    fun `complete scan can suggest a product whose stored barcode is missing digits`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = false), productBarcode = "3176004930")
            viewModel.onAction(SalesContract.Action.BarcodeScanned("7753176004930"))
            runCurrent()
            assertEquals(
                3,
                viewModel.uiState.value.barcodeSuggestions
                    .single()
                    .missingDigits,
            )
            assertTrue(sales.saveLineCalls.isEmpty())
            assertEquals("3176004930", products.findById(PRODUCT_ID)?.barcode)
        }

    @Test
    fun `confirming a similar code adds the selected product with its saved price without associating it`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val stored = "7753176004930"
            val scanned = "753176004930"
            val price = Money.fromMajor("7.50", CURRENCY)
            val viewModel =
                createReadyViewModel(
                    cart(withLine = false),
                    productBarcode = stored,
                    productSalePrice = price,
                )
            val original = products.findById(PRODUCT_ID)
            viewModel.onAction(SalesContract.Action.BarcodeScanned(scanned))
            runCurrent()
            sales.saveLineHandler = { SaleCartMutationResult.Saved(cart(withLine = true)) }
            viewModel.onAction(SalesContract.Action.BarcodeSuggestionSelected(PRODUCT_ID, LOCATION_ID, scanned))
            runCurrent()
            assertEquals(PRODUCT_ID, sales.saveLineCalls.single().productId)
            assertEquals(LOCATION_ID, sales.saveLineCalls.single().locationId)
            assertEquals(price, sales.saveLineCalls.single().unitPrice)
            assertEquals(original, products.findById(PRODUCT_ID))
            assertEquals(0, products.updateCalls)
            assertNull(viewModel.uiState.value.pendingAssociationBarcode)
            assertNull(viewModel.uiState.value.pendingReplacement)
            assertTrue(
                viewModel.uiState.value.barcodeSuggestions
                    .isEmpty(),
            )
            assertEquals(
                PRODUCT_ID,
                viewModel.uiState.value.lastScanAdded
                    ?.productId,
            )
            assertEquals(
                BigDecimal.ONE,
                viewModel.uiState.value.lastScanAdded
                    ?.quantity,
            )
            assertEquals(
                1L,
                viewModel.uiState.value.lastScanAdded
                    ?.sequence,
            )
        }

    @Test
    fun `rescanning the exact code clears suggestions and adds once`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val stored = "7753176004930"
            val scanned = "753176004930"
            val viewModel = createReadyViewModel(cart(withLine = false), productBarcode = stored)
            viewModel.onAction(SalesContract.Action.BarcodeScanned(scanned))
            runCurrent()
            assertEquals(1, viewModel.uiState.value.barcodeSuggestions.size)
            viewModel.onAction(SalesContract.Action.BarcodeScanned(stored))
            runCurrent()
            viewModel.onAction(SalesContract.Action.BarcodeSuggestionSelected(PRODUCT_ID, LOCATION_ID, scanned))
            runCurrent()
            assertEquals(1, sales.saveLineCalls.size)
            assertTrue(
                viewModel.uiState.value.barcodeSuggestions
                    .isEmpty(),
            )
            assertNull(viewModel.uiState.value.pendingAssociationBarcode)
            assertEquals(0, products.updateCalls)
        }

    @Test
    fun `an old suggestion cannot select a product for a different scan or after cancellation`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = false), productBarcode = "7753176004930")
            val first = "753176004930"
            val second = "53176004930"
            viewModel.onAction(SalesContract.Action.BarcodeScanned(first))
            runCurrent()
            viewModel.onAction(SalesContract.Action.BarcodeScanned(second))
            runCurrent()
            viewModel.onAction(SalesContract.Action.BarcodeSuggestionSelected(PRODUCT_ID, LOCATION_ID, first))
            runCurrent()
            assertTrue(sales.saveLineCalls.isEmpty())
            assertEquals(second, viewModel.uiState.value.pendingAssociationBarcode)
            viewModel.onAction(SalesContract.Action.AssociationDismissed)
            runCurrent()
            viewModel.onAction(SalesContract.Action.BarcodeSuggestionSelected(PRODUCT_ID, LOCATION_ID, second))
            runCurrent()
            assertTrue(sales.saveLineCalls.isEmpty())
            assertTrue(
                viewModel.uiState.value.barcodeSuggestions
                    .isEmpty(),
            )
        }

    @Test
    fun `suggestion is removed when stock disappears or its barcode changes`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = false), productBarcode = "7753176004930")
            val scanned = "753176004930"
            viewModel.onAction(SalesContract.Action.BarcodeScanned(scanned))
            runCurrent()
            inventory.replaceInventory(BUSINESS_ID, listOf(inventoryItem(BigDecimal.ZERO)))
            runCurrent()
            assertTrue(
                viewModel.uiState.value.barcodeSuggestions
                    .isEmpty(),
            )
            viewModel.onAction(SalesContract.Action.BarcodeSuggestionSelected(PRODUCT_ID, LOCATION_ID, scanned))
            runCurrent()
            assertTrue(sales.saveLineCalls.isEmpty())

            inventory.replaceInventory(BUSINESS_ID, listOf(inventoryItem()))
            runCurrent()
            viewModel.onAction(SalesContract.Action.BarcodeScanned(scanned))
            runCurrent()
            assertEquals(1, viewModel.uiState.value.barcodeSuggestions.size)
            productDelegate.update(requireNotNull(productDelegate.findById(PRODUCT_ID)).copy(barcode = "9999999999999"))
            runCurrent()
            assertTrue(
                viewModel.uiState.value.barcodeSuggestions
                    .isEmpty(),
            )
            viewModel.onAction(SalesContract.Action.BarcodeSuggestionSelected(PRODUCT_ID, LOCATION_ID, scanned))
            runCurrent()
            assertTrue(sales.saveLineCalls.isEmpty())
        }

    @Test
    fun `failed cart save preserves the suggestion without replacing the stored code`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = false), productBarcode = "7753176004930")
            val scanned = "753176004930"
            viewModel.onAction(SalesContract.Action.BarcodeScanned(scanned))
            runCurrent()
            sales.saveLineHandler = { SaleCartMutationResult.Stale }
            viewModel.onAction(SalesContract.Action.BarcodeSuggestionSelected(PRODUCT_ID, LOCATION_ID, scanned))
            runCurrent()
            assertEquals(SalesContract.Failure.STALE_CART, viewModel.uiState.value.failure)
            assertEquals(scanned, viewModel.uiState.value.pendingAssociationBarcode)
            assertEquals(1, viewModel.uiState.value.barcodeSuggestions.size)
            assertEquals(0, products.updateCalls)
            assertEquals("7753176004930", products.findById(PRODUCT_ID)?.barcode)
        }

    @Test
    fun `similar selection rechecks context after lookup and ignores double taps`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = false), productBarcode = "7753176004930")
            val scanned = "753176004930"
            viewModel.onAction(SalesContract.Action.BarcodeScanned(scanned))
            runCurrent()
            val gate = CompletableDeferred<Unit>()
            products.idLookupGate = gate
            val action = SalesContract.Action.BarcodeSuggestionSelected(PRODUCT_ID, LOCATION_ID, scanned)
            viewModel.onAction(action)
            viewModel.onAction(action)
            runCurrent()
            viewModel.onAction(SalesContract.Action.StepBackSelected)
            runCurrent()
            gate.complete(Unit)
            runCurrent()
            assertTrue(sales.saveLineCalls.isEmpty())
            assertTrue(
                viewModel.uiState.value.barcodeSuggestions
                    .isEmpty(),
            )
            assertEquals(SalesContract.EntryStep.SELECT_MODE, viewModel.uiState.value.entryStep)
        }

    @Test
    fun `double tapping a suggestion adds exactly one unit`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = false), productBarcode = "7753176004930")
            val scanned = "753176004930"
            viewModel.onAction(SalesContract.Action.BarcodeScanned(scanned))
            runCurrent()
            val gate = CompletableDeferred<Unit>()
            products.idLookupGate = gate
            sales.saveLineHandler = { SaleCartMutationResult.Saved(cart(withLine = true)) }
            val action = SalesContract.Action.BarcodeSuggestionSelected(PRODUCT_ID, LOCATION_ID, scanned)
            viewModel.onAction(action)
            viewModel.onAction(action)
            runCurrent()
            gate.complete(Unit)
            runCurrent()
            viewModel.onAction(action)
            runCurrent()
            assertEquals(1, sales.saveLineCalls.size)
            assertEquals(
                "1",
                viewModel.uiState.value.cartLines
                    .single()
                    .quantityInput,
            )
            assertEquals(0, products.updateCalls)
        }

    @Test
    fun `checkout waits for a pending suggestion to be resolved before starting the next cart`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = true), productBarcode = "7753176004930")
            val scanned = "753176004930"
            viewModel.onAction(SalesContract.Action.BarcodeScanned(scanned))
            runCurrent()
            assertEquals(1, viewModel.uiState.value.barcodeSuggestions.size)
            assertFalse(viewModel.uiState.value.canCheckout)
            viewModel.onAction(SalesContract.Action.CheckoutRequested)
            runCurrent()
            assertNull(viewModel.uiState.value.checkoutReview)
            assertTrue(sales.checkoutCalls.isEmpty())
            assertEquals(scanned, viewModel.uiState.value.pendingAssociationBarcode)
            viewModel.onAction(SalesContract.Action.AssociationDismissed)
            runCurrent()
            assertTrue(viewModel.uiState.value.canCheckout)
            sales.prepareCartToOpen(cart(withLine = false, saleId = SALE_ID_2))
            val posted =
                async {
                    viewModel.effects.first {
                        it == SalesContract.Effect.ShowMessage(SalesContract.Message.SALE_POSTED)
                    }
                }
            viewModel.onAction(SalesContract.Action.CheckoutRequested)
            runCurrent()
            viewModel.onAction(SalesContract.Action.CheckoutConfirmed)
            runCurrent()
            posted.await()
            assertEquals(SALE_ID_2.value, viewModel.uiState.value.cartId)
            assertNull(viewModel.uiState.value.pendingAssociationBarcode)
            assertTrue(
                viewModel.uiState.value.barcodeSuggestions
                    .isEmpty(),
            )
            viewModel.onAction(SalesContract.Action.BarcodeSuggestionSelected(PRODUCT_ID, LOCATION_ID, scanned))
            runCurrent()
            assertTrue(sales.saveLineCalls.isEmpty())
            assertTrue(viewModel.uiState.value.canRouteScannerInput)
        }

    @Test
    fun `last scanner addition appears only after the line has actually been saved`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val original = cart(withLine = false)
            val viewModel = createReadyViewModel(original)
            val saveGate = CompletableDeferred<SaleCartMutationResult>()
            sales.saveLineHandler = { saveGate.await() }

            viewModel.onAction(SalesContract.Action.BarcodeScanned(EXISTING_BARCODE))
            runCurrent()

            assertEquals(1, sales.saveLineCalls.size)
            assertTrue(viewModel.uiState.value.isProcessingBarcode)
            assertNull(viewModel.uiState.value.lastScanAdded)
            saveGate.complete(SaleCartMutationResult.Saved(cart(withLine = true)))
            runCurrent()

            val added = requireNotNull(viewModel.uiState.value.lastScanAdded)
            assertEquals(PRODUCT_ID, added.productId)
            assertEquals(LOCATION_ID, added.locationId)
            assertEquals("Producto", added.productName)
            assertEquals("Principal", added.locationName)
            assertEquals("NIU", added.unitCode)
            assertEquals(BigDecimal.ONE, added.quantity)
            assertEquals(1L, added.sequence)
            assertFalse(added.alreadyInCart)
            assertEquals(0, viewModel.uiState.value.pendingBarcodeCount)
        }

    @Test
    fun `scanner feedback follows persisted quantity changes and clears when its line is removed`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = false))
            val saved = cart(withLine = true)
            sales.saveLineHandler = { SaleCartMutationResult.Saved(saved) }
            viewModel.onAction(SalesContract.Action.BarcodeScanned(EXISTING_BARCODE))
            runCurrent()
            val scanned = requireNotNull(viewModel.uiState.value.lastScanAdded)
            assertEquals(BigDecimal.ONE, scanned.quantity)
            assertEquals(1L, scanned.sequence)

            sales.emitObserved(SALE_ID, saved.withQuantity("4", version = 2L))
            runCurrent()

            assertEquals(scanned.copy(quantity = BigDecimal("4")), viewModel.uiState.value.lastScanAdded)
            assertEquals(
                "4",
                viewModel.uiState.value.cartLines
                    .single()
                    .quantityInput,
            )
            sales.emitObserved(SALE_ID, cart(withLine = false).copy(version = 3L))
            runCurrent()

            assertNull(viewModel.uiState.value.lastScanAdded)
            assertTrue(
                viewModel.uiState.value.cartLines
                    .isEmpty(),
            )
            assertEquals(1, sales.saveLineCalls.size)
            assertEquals(1, products.findByBarcodeCalls)
        }

    @Test
    fun `an older observed cart cannot erase a saved scan or roll back a repeated scan and manual edit`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val original = cart(withLine = false)
            val viewModel = createReadyViewModel(original)
            sales.saveLineHandler = { command ->
                SaleCartMutationResult.Saved(
                    cart(withLine = true).withQuantity(
                        command.quantity.value.toPlainString(),
                        command.expectedVersion + 1,
                    ),
                )
            }
            viewModel.onAction(SalesContract.Action.BarcodeScanned(EXISTING_BARCODE))
            runCurrent()
            val savedFeedback = requireNotNull(viewModel.uiState.value.lastScanAdded)
            assertEquals(2L, viewModel.uiState.value.cartVersion)
            assertEquals(BigDecimal.ONE, savedFeedback.quantity)

            sales.emitObserved(SALE_ID, original)
            runCurrent()

            assertEquals(2L, viewModel.uiState.value.cartVersion)
            assertEquals(
                "1",
                viewModel.uiState.value.cartLines
                    .single()
                    .quantityInput,
            )
            assertEquals(savedFeedback, viewModel.uiState.value.lastScanAdded)
            viewModel.onAction(SalesContract.Action.BarcodeScanned(EXISTING_BARCODE))
            runCurrent()

            assertEquals(listOf(1L), sales.saveLineCalls.map { it.expectedVersion })
            assertEquals(listOf("1"), sales.saveLineCalls.map { it.quantity.value.toPlainString() })
            assertEquals(2L, viewModel.uiState.value.cartVersion)
            assertEquals(
                "1",
                viewModel.uiState.value.cartLines
                    .single()
                    .quantityInput,
            )
            assertEquals(
                BigDecimal.ONE,
                viewModel.uiState.value.lastScanAdded
                    ?.quantity,
            )
            assertEquals(
                2L,
                viewModel.uiState.value.lastScanAdded
                    ?.sequence,
            )
            assertEquals(true, viewModel.uiState.value.lastScanAdded?.alreadyInCart)
            viewModel.onAction(SalesContract.Action.QuantityChanged(LINE_ID.value, "2"))
            advanceTimeBy(400L)
            runCurrent()
            assertEquals(listOf(1L, 2L), sales.saveLineCalls.map { it.expectedVersion })
            assertEquals(listOf("1", "2"), sales.saveLineCalls.map { it.quantity.value.toPlainString() })
            assertEquals(3L, viewModel.uiState.value.cartVersion)
        }

    @Test
    fun `a failed first scanner save never reports a product as added`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = false))
            sales.saveLineHandler = { SaleCartMutationResult.Stale }

            viewModel.onAction(SalesContract.Action.BarcodeScanned(EXISTING_BARCODE))
            runCurrent()

            assertEquals(SalesContract.Failure.STALE_CART, viewModel.uiState.value.failure)
            assertNull(viewModel.uiState.value.lastScanAdded)
            assertTrue(
                viewModel.uiState.value.cartLines
                    .isEmpty(),
            )
            sales.saveLineHandler = { SaleCartMutationResult.Saved(cart(withLine = true)) }
            viewModel.onAction(SalesContract.Action.Retry)
            runCurrent()
            viewModel.onAction(SalesContract.Action.BarcodeScanned(EXISTING_BARCODE))
            runCurrent()
            assertEquals(2, products.findByBarcodeCalls)
            assertEquals(2, sales.saveLineCalls.size)
            assertEquals(BigDecimal.ONE, viewModel.uiState.value.lastScanAdded?.quantity)
            assertEquals(false, viewModel.uiState.value.lastScanAdded?.alreadyInCart)
        }

    @Test
    fun `a failed different product preserves the last saved scanner quantity without a false confirmation`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = false))
            val second = seedAdditionalProduct(50L, NEW_BARCODE)
            sales.saveLineHandler = { SaleCartMutationResult.Saved(cart(withLine = true)) }
            viewModel.onAction(SalesContract.Action.BarcodeScanned(EXISTING_BARCODE))
            runCurrent()
            val lastSaved = requireNotNull(viewModel.uiState.value.lastScanAdded)
            sales.saveLineHandler = { SaleCartMutationResult.Stale }

            viewModel.onAction(SalesContract.Action.BarcodeScanned(NEW_BARCODE))
            runCurrent()

            assertEquals(listOf(PRODUCT_ID, second.productId), sales.saveLineCalls.map { it.productId })
            assertEquals(listOf("1", "1"), sales.saveLineCalls.map { it.quantity.value.toPlainString() })
            assertEquals(lastSaved, viewModel.uiState.value.lastScanAdded)
            assertEquals(
                "1",
                viewModel.uiState.value.cartLines
                    .single()
                    .quantityInput,
            )
            assertEquals(SalesContract.Failure.STALE_CART, viewModel.uiState.value.failure)
        }

    @Test
    fun `thirty two identical shots share one lookup and save exactly one unit`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel =
                createReadyViewModel(
                    cart(withLine = false),
                    availableQuantity = BigDecimal("100"),
                )
            val saveGate = CompletableDeferred<Unit>()
            sales.saveLineHandler = { command ->
                saveGate.await()
                SaleCartMutationResult.Saved(
                    cart(withLine = true).withQuantity(
                        command.quantity.value.toPlainString(),
                        command.expectedVersion + 1,
                    ),
                )
            }
            repeat(32) {
                viewModel.onAction(SalesContract.Action.BarcodeScanned(EXISTING_BARCODE))
            }
            runCurrent()
            assertEquals(1, viewModel.uiState.value.pendingBarcodeCount)
            assertEquals(1, sales.saveLineCalls.size)
            assertNull(viewModel.uiState.value.lastScanAdded)
            assertNull(viewModel.uiState.value.scannerFailure)
            assertFalse(viewModel.uiState.value.canCheckout)
            saveGate.complete(Unit)
            runCurrent()
            val added = requireNotNull(viewModel.uiState.value.lastScanAdded)
            assertEquals(BigDecimal.ONE, added.quantity)
            assertEquals(1L, added.sequence)
            assertFalse(added.alreadyInCart)
            assertEquals(0, viewModel.uiState.value.pendingBarcodeCount)
            assertEquals(1, sales.saveLineCalls.size)
            assertEquals(1, products.findByBarcodeCalls)
            assertEquals(BigDecimal.ONE, sales.saveLineCalls.single().quantity.value)
            assertEquals(1L, sales.saveLineCalls.single().expectedVersion)
            assertEquals(
                "1",
                viewModel.uiState.value.cartLines
                    .single()
                    .quantityInput,
            )
            assertEquals(Money.fromMajor("5.00", CURRENCY), viewModel.uiState.value.total)
            assertEquals(0, products.updateCalls)
            assertTrue(sales.checkoutCalls.isEmpty())
            assertTrue(viewModel.uiState.value.canRouteScannerInput)
            assertTrue(viewModel.uiState.value.canCheckout)
        }

    @Test
    fun `barcode and SKU resolve the same cart product without adding another unit`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val sku = "COD-001"
            val viewModel = createReadyViewModel(cart(withLine = false), productSku = sku)
            val original = requireNotNull(products.findById(PRODUCT_ID))
            sales.saveLineHandler = { SaleCartMutationResult.Saved(cart(withLine = true)) }

            listOf(EXISTING_BARCODE, sku, "cod-001").forEach { code ->
                viewModel.onAction(SalesContract.Action.BarcodeScanned(code))
                runCurrent()
            }

            assertEquals(1, sales.saveLineCalls.size)
            assertEquals(3, products.findByBarcodeCalls)
            assertEquals("1", viewModel.uiState.value.cartLines.single().quantityInput)
            assertEquals(PRODUCT_ID, viewModel.uiState.value.lastScanAdded?.productId)
            assertEquals(true, viewModel.uiState.value.lastScanAdded?.alreadyInCart)
            assertEquals(3L, viewModel.uiState.value.lastScanAdded?.sequence)
            assertNull(viewModel.uiState.value.pendingAssociationBarcode)
            assertEquals(original, products.findById(PRODUCT_ID))
            assertEquals(0, products.updateCalls)
        }

    @Test
    fun `a repeated product after other queued codes still clears an unknown reading`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val initial = cart(withLine = true)
            val viewModel = createReadyViewModel(initial)
            val second = seedAdditionalProduct(50L, NEW_BARCODE)
            sales.saveLineHandler = { command ->
                val newLine = initial.lines.single().copy(
                    saleLineId = SaleLineId.from(uuid(150L)),
                    productId = second.productId,
                    productName = second.name,
                    quantity = command.quantity,
                    position = 1,
                )
                val total = Money.fromMajor("10.00", CURRENCY)
                SaleCartMutationResult.Saved(
                    initial.copy(
                        lines = initial.lines + newLine,
                        subtotal = total,
                        total = total,
                        version = command.expectedVersion + 1,
                        contentHash = "2".repeat(64),
                    ),
                )
            }
            val gate = CompletableDeferred<Unit>()
            products.barcodeLookupGate = gate
            val codes = listOf(EXISTING_BARCODE, NEW_BARCODE, "5555555555555", EXISTING_BARCODE)
            codes.forEach { viewModel.onAction(SalesContract.Action.BarcodeScanned(it)) }
            runCurrent()
            assertEquals(4, viewModel.uiState.value.pendingBarcodeCount)
            gate.complete(Unit)
            runCurrent()

            assertEquals(codes, products.barcodeLookups.map { it.second })
            assertEquals(listOf(second.productId), sales.saveLineCalls.map { it.productId })
            assertEquals(2, viewModel.uiState.value.cartLines.size)
            assertNull(viewModel.uiState.value.pendingAssociationBarcode)
            assertTrue(viewModel.uiState.value.barcodeSuggestions.isEmpty())
            assertEquals(PRODUCT_ID, viewModel.uiState.value.lastScanAdded?.productId)
            assertEquals(BigDecimal.ONE, viewModel.uiState.value.lastScanAdded?.quantity)
            assertEquals(true, viewModel.uiState.value.lastScanAdded?.alreadyInCart)
            assertEquals(0, viewModel.uiState.value.pendingBarcodeCount)
            assertEquals(0, products.updateCalls)
        }

    @Test
    fun `rescanning preserves the manually edited quantity and price`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val initial = cart(withLine = true)
            val viewModel = createReadyViewModel(initial)
            sales.saveLineHandler = { command ->
                SaleCartMutationResult.Saved(
                    initial.withQuantity(command.quantity.value.toPlainString(), command.expectedVersion + 1),
                )
            }
            viewModel.onAction(SalesContract.Action.QuantityChanged(LINE_ID.value, "7"))
            advanceTimeBy(400L)
            runCurrent()
            assertEquals(1, sales.saveLineCalls.size)

            repeat(3) {
                viewModel.onAction(SalesContract.Action.BarcodeScanned(EXISTING_BARCODE))
                runCurrent()
            }

            assertEquals(1, sales.saveLineCalls.size)
            assertEquals("7", viewModel.uiState.value.cartLines.single().quantityInput)
            assertEquals("5.00", viewModel.uiState.value.cartLines.single().unitPriceInput)
            assertEquals(BigDecimal("7"), viewModel.uiState.value.lastScanAdded?.quantity)
            assertEquals(true, viewModel.uiState.value.lastScanAdded?.alreadyInCart)
            assertEquals(3L, viewModel.uiState.value.lastScanAdded?.sequence)
        }

    @Test
    fun `removing the scanned product from the observed cart lets the next scan add it again`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = false))
            sales.saveLineHandler = { command ->
                SaleCartMutationResult.Saved(
                    cart(withLine = true).withQuantity(command.quantity.value.toPlainString(), command.expectedVersion + 1),
                )
            }
            viewModel.onAction(SalesContract.Action.BarcodeScanned(EXISTING_BARCODE))
            runCurrent()
            viewModel.onAction(SalesContract.Action.BarcodeScanned(EXISTING_BARCODE))
            runCurrent()
            assertEquals(1, sales.saveLineCalls.size)
            assertEquals(true, viewModel.uiState.value.lastScanAdded?.alreadyInCart)

            sales.emitObserved(SALE_ID, cart(withLine = false).copy(version = 3L))
            runCurrent()
            assertNull(viewModel.uiState.value.lastScanAdded)
            viewModel.onAction(SalesContract.Action.BarcodeScanned(EXISTING_BARCODE))
            runCurrent()

            assertEquals(listOf(1L, 3L), sales.saveLineCalls.map { it.expectedVersion })
            assertEquals(listOf("1", "1"), sales.saveLineCalls.map { it.quantity.value.toPlainString() })
            assertEquals(4L, viewModel.uiState.value.cartVersion)
            assertEquals("1", viewModel.uiState.value.cartLines.single().quantityInput)
            assertEquals(false, viewModel.uiState.value.lastScanAdded?.alreadyInCart)
        }

    @Test
    fun `a product already in another cart warehouse does not ask to choose or add it again`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val otherLocationId = LocationId.from(uuid(80L))
            val initial = cart(withLine = true).let { current ->
                current.copy(lines = current.lines.map { it.copy(locationId = otherLocationId, locationName = "Secundario") })
            }
            val viewModel = createReadyViewModel(initial)
            locations.create(
                InventoryLocation(
                    locationId = otherLocationId,
                    businessId = BUSINESS_ID,
                    name = "Secundario",
                    createdAt = NOW,
                    updatedAt = NOW,
                ),
            )
            val item = inventoryItem()
            inventory.replaceInventory(
                BUSINESS_ID,
                listOf(item.copy(positions = item.positions + item.positions.single().copy(
                    locationId = otherLocationId,
                    locationName = "Secundario",
                ))),
            )
            runCurrent()

            viewModel.onAction(SalesContract.Action.BarcodeScanned(EXISTING_BARCODE))
            runCurrent()

            assertTrue(sales.saveLineCalls.isEmpty())
            assertTrue(viewModel.uiState.value.pendingLocations.isEmpty())
            assertEquals(otherLocationId, viewModel.uiState.value.lastScanAdded?.locationId)
            assertEquals(true, viewModel.uiState.value.lastScanAdded?.alreadyInCart)
            assertEquals("1", viewModel.uiState.value.cartLines.single().quantityInput)
        }

    @Test
    fun `confirming a similar code for an existing cart product only identifies its saved quantity`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val stored = "7753176004930"
            val scanned = "753176004930"
            val viewModel = createReadyViewModel(cart(withLine = true).withQuantity("4", version = 2L), productBarcode = stored)
            viewModel.onAction(SalesContract.Action.BarcodeScanned(scanned))
            runCurrent()
            assertEquals(1, viewModel.uiState.value.barcodeSuggestions.size)

            viewModel.onAction(SalesContract.Action.BarcodeSuggestionSelected(PRODUCT_ID, LOCATION_ID, scanned))
            runCurrent()

            assertTrue(sales.saveLineCalls.isEmpty())
            assertEquals(BigDecimal("4"), viewModel.uiState.value.lastScanAdded?.quantity)
            assertEquals(true, viewModel.uiState.value.lastScanAdded?.alreadyInCart)
            assertNull(viewModel.uiState.value.pendingAssociationBarcode)
            assertTrue(viewModel.uiState.value.barcodeSuggestions.isEmpty())
            assertEquals(stored, products.findById(PRODUCT_ID)?.barcode)
            assertEquals(0, products.updateCalls)
        }

    @Test
    fun `changing entry clears scanner feedback and manual selections do not recreate it`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = false))
            sales.saveLineHandler = { command ->
                SaleCartMutationResult.Saved(
                    cart(withLine = true).withQuantity(
                        command.quantity.value.toPlainString(),
                        command.expectedVersion + 1,
                    ),
                )
            }
            viewModel.onAction(SalesContract.Action.BarcodeScanned(EXISTING_BARCODE))
            runCurrent()
            assertEquals(
                BigDecimal.ONE,
                viewModel.uiState.value.lastScanAdded
                    ?.quantity,
            )

            viewModel.onAction(SalesContract.Action.ModeChanged(SalesContract.EntryMode.MANUAL))
            runCurrent()
            assertNull(viewModel.uiState.value.lastScanAdded)
            viewModel.onAction(SalesContract.Action.ProductSelected(PRODUCT_ID, LOCATION_ID))
            runCurrent()
            assertEquals(
                "2",
                viewModel.uiState.value.cartLines
                    .single()
                    .quantityInput,
            )
            assertNull(viewModel.uiState.value.lastScanAdded)
            viewModel.onAction(SalesContract.Action.ModeChanged(SalesContract.EntryMode.SCANNER))
            runCurrent()
            assertNull(viewModel.uiState.value.lastScanAdded)
        }

    @Test
    fun `checkout starts a new cart without carrying its previous scanner confirmation`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = false))
            sales.saveLineHandler = { SaleCartMutationResult.Saved(cart(withLine = true)) }
            viewModel.onAction(SalesContract.Action.BarcodeScanned(EXISTING_BARCODE))
            runCurrent()
            assertEquals(
                BigDecimal.ONE,
                viewModel.uiState.value.lastScanAdded
                    ?.quantity,
            )
            sales.prepareCartToOpen(cart(withLine = false, saleId = SALE_ID_2))
            val posted =
                async {
                    viewModel.effects.first {
                        it == SalesContract.Effect.ShowMessage(SalesContract.Message.SALE_POSTED)
                    }
                }

            viewModel.onAction(SalesContract.Action.CheckoutRequested)
            runCurrent()
            viewModel.onAction(SalesContract.Action.CheckoutConfirmed)
            runCurrent()
            posted.await()

            assertEquals(SALE_ID_2.value, viewModel.uiState.value.cartId)
            assertNull(viewModel.uiState.value.lastScanAdded)
            assertTrue(
                viewModel.uiState.value.cartLines
                    .isEmpty(),
            )
        }

    @Test
    fun `checkout waits for a rejected scanner read to be explicitly reset`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = true))
            viewModel.onAction(SalesContract.Action.ScannerReadFailed(SalesContract.ScannerFailure.INCOMPLETE))
            runCurrent()
            assertFalse(viewModel.uiState.value.canCheckout)
            assertTrue(viewModel.uiState.value.canRouteScannerInput)
            viewModel.onAction(SalesContract.Action.CheckoutRequested)
            runCurrent()
            assertNull(viewModel.uiState.value.checkoutReview)
            assertTrue(sales.checkoutCalls.isEmpty())

            viewModel.onAction(SalesContract.Action.ScannerReadReset)
            runCurrent()
            assertTrue(viewModel.uiState.value.canCheckout)
            viewModel.onAction(SalesContract.Action.CheckoutRequested)
            runCurrent()
            assertEquals(
                SALE_ID.value,
                viewModel.uiState.value.checkoutReview
                    ?.cartId,
            )
            assertTrue(sales.checkoutCalls.isEmpty())
        }

    @Test
    fun `registering an unknown reading opens its correlated form and blocks competing scans`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = true))
            val request = beginProductRegistration(viewModel)

            assertEquals(NEW_BARCODE, request.barcode)
            assertEquals(BUSINESS_ID, request.businessId)
            assertEquals(SALE_ID, request.saleId)
            assertFalse(viewModel.uiState.value.canRouteScannerInput)
            assertFalse(viewModel.uiState.value.canCheckout)
            assertFalse(viewModel.uiState.value.canRegisterProduct)
            val lookupsBefore = products.findByBarcodeCalls
            viewModel.onAction(SalesContract.Action.BarcodeScanned(EXISTING_BARCODE))
            viewModel.onAction(SalesContract.Action.RegisterProductRequested(NEW_BARCODE))
            viewModel.onAction(SalesContract.Action.CheckoutRequested)
            runCurrent()

            assertEquals(request, viewModel.uiState.value.productRegistration)
            assertEquals(lookupsBefore, products.findByBarcodeCalls)
            assertTrue(sales.saveLineCalls.isEmpty())
            assertNull(viewModel.uiState.value.checkoutReview)
        }

    @Test
    fun `registration result adds the saved product and stock before catalog emissions arrive`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = false))
            val request = beginProductRegistration(viewModel)
            val catalogGate = CompletableDeferred<Unit>()
            products.catalogEmissionGate = catalogGate
            val registered = seedAdditionalProduct(50L, NEW_BARCODE)
            val price = Money.fromMajor("8.75", CURRENCY)
            products.update(registered.copy(salePrice = price))
            val detail = inventoryItem(BigDecimal("6")).copy(productId = registered.productId, productName = registered.name)
            inventory.setProductDetail(BUSINESS_ID, registered.productId, InventoryProductDetail(detail, emptyList()))
            sales.saveLineHandler = { command ->
                val saved = cart(withLine = true).withQuantity(command.quantity.value.toPlainString(), command.expectedVersion + 1)
                SaleCartMutationResult.Saved(saved.copy(
                    subtotal = price,
                    total = price,
                    lines = saved.lines.map {
                        it.copy(productId = registered.productId, productName = registered.name, unitPrice = price, lineTotal = price)
                    },
                ))
            }
            runCurrent()
            assertTrue(viewModel.uiState.value.availableProducts.none { it.productId == registered.productId })

            viewModel.onAction(SalesContract.Action.ProductRegistrationFinished(
                SalesContract.ProductRegistrationResult(request.requestId, registered.productId, BUSINESS_ID),
            ))
            runCurrent()

            val command = sales.saveLineCalls.single()
            assertEquals(registered.productId, command.productId)
            assertEquals(LOCATION_ID, command.locationId)
            assertEquals(price, command.unitPrice)
            assertEquals(BigDecimal.ONE, command.quantity.value)
            assertEquals(listOf(BUSINESS_ID to registered.productId), inventory.observedProducts)
            assertEquals(BigDecimal("6"), viewModel.uiState.value.cartLines.single().availableQuantity)
            assertEquals(false, viewModel.uiState.value.lastScanAdded?.alreadyInCart)
            assertNull(viewModel.uiState.value.productRegistration)
            assertNull(viewModel.uiState.value.pendingAssociationBarcode)
            assertFalse(viewModel.uiState.value.productRegisteredWithoutCartAdd)
            assertTrue(viewModel.uiState.value.canRouteScannerInput)
            catalogGate.complete(Unit)
            runCurrent()
        }

    @Test
    fun `canceling registration keeps the unknown reading and does not add or alter a product`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = false))
            val request = beginProductRegistration(viewModel)
            val original = products.findById(PRODUCT_ID)

            viewModel.onAction(SalesContract.Action.ProductRegistrationFinished(
                SalesContract.ProductRegistrationResult(request.requestId),
            ))
            runCurrent()

            assertNull(viewModel.uiState.value.productRegistration)
            assertEquals(NEW_BARCODE, viewModel.uiState.value.pendingAssociationBarcode)
            assertTrue(viewModel.uiState.value.canRouteScannerInput)
            assertTrue(viewModel.uiState.value.canRegisterProduct)
            assertTrue(sales.saveLineCalls.isEmpty())
            assertEquals(original, products.findById(PRODUCT_ID))
            assertEquals(0, products.updateCalls)
            assertFalse(viewModel.uiState.value.productRegisteredWithoutCartAdd)
        }

    @Test
    fun `registration ignores another request and rejects a result belonging to another business`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = false))
            val request = beginProductRegistration(viewModel)
            viewModel.onAction(SalesContract.Action.ProductRegistrationFinished(
                SalesContract.ProductRegistrationResult(UUID.randomUUID().toString(), PRODUCT_ID, BUSINESS_ID),
            ))
            runCurrent()
            assertEquals(request, viewModel.uiState.value.productRegistration)
            assertTrue(sales.saveLineCalls.isEmpty())

            viewModel.onAction(SalesContract.Action.ProductRegistrationFinished(
                SalesContract.ProductRegistrationResult(request.requestId, PRODUCT_ID, BusinessId.from(uuid(90L))),
            ))
            runCurrent()

            assertNull(viewModel.uiState.value.productRegistration)
            assertTrue(sales.saveLineCalls.isEmpty())
            assertEquals(NEW_BARCODE, viewModel.uiState.value.pendingAssociationBarcode)
            assertEquals(0, products.updateCalls)
        }

    @Test
    fun `a registration result for the previous cart cannot add to its replacement`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val original = cart(withLine = false)
            val viewModel = createReadyViewModel(original)
            val request = beginProductRegistration(viewModel)
            sales.prepareCartToOpen(cart(withLine = false, saleId = SALE_ID_2))
            sales.emitObserved(SALE_ID, null)
            runCurrent()
            assertEquals(SALE_ID_2.value, viewModel.uiState.value.cartId)
            assertNull(viewModel.uiState.value.productRegistration)

            viewModel.onAction(SalesContract.Action.ProductRegistrationFinished(
                SalesContract.ProductRegistrationResult(request.requestId, PRODUCT_ID, BUSINESS_ID),
            ))
            runCurrent()

            assertTrue(sales.saveLineCalls.isEmpty())
            assertTrue(viewModel.uiState.value.cartLines.isEmpty())
            assertNull(viewModel.uiState.value.lastScanAdded)
        }

    @Test
    fun `leaving the active business while registration lookup is pending never adds its result`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = false))
            val request = beginProductRegistration(viewModel)
            val registered = seedAdditionalProduct(50L, NEW_BARCODE)
            setRegisteredProductDetail(registered)
            val gate = CompletableDeferred<Unit>()
            products.idLookupGate = gate
            viewModel.onAction(SalesContract.Action.ProductRegistrationFinished(
                SalesContract.ProductRegistrationResult(request.requestId, registered.productId, BUSINESS_ID),
            ))
            runCurrent()
            configuration.exitDemoMode()
            runCurrent()
            gate.complete(Unit)
            runCurrent()

            assertTrue(sales.saveLineCalls.isEmpty())
            assertNull(viewModel.uiState.value.lastScanAdded)
            assertNull(viewModel.uiState.value.productRegistration)
            assertEquals(SalesContract.Failure.NO_ACTIVE_BUSINESS, viewModel.uiState.value.failure)
        }

    @Test
    fun `duplicate registration results during lookup and after completion add only once`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = false))
            val request = beginProductRegistration(viewModel)
            val registered = seedAdditionalProduct(50L, NEW_BARCODE)
            setRegisteredProductDetail(registered)
            sales.saveLineHandler = { command ->
                SaleCartMutationResult.Saved(cart(withLine = true).copy(
                    version = command.expectedVersion + 1,
                    lines = cart(withLine = true).lines.map { it.copy(productId = registered.productId) },
                ))
            }
            val gate = CompletableDeferred<Unit>()
            products.idLookupGate = gate
            val action = SalesContract.Action.ProductRegistrationFinished(
                SalesContract.ProductRegistrationResult(request.requestId, registered.productId, BUSINESS_ID),
            )
            viewModel.onAction(action)
            viewModel.onAction(action)
            runCurrent()
            assertTrue(sales.saveLineCalls.isEmpty())
            gate.complete(Unit)
            runCurrent()
            viewModel.onAction(action)
            runCurrent()

            assertEquals(1, sales.saveLineCalls.size)
            assertEquals(BigDecimal.ONE, sales.saveLineCalls.single().quantity.value)
            assertEquals(1L, viewModel.uiState.value.lastScanAdded?.sequence)
            assertNull(viewModel.uiState.value.productRegistration)
        }

    @Test
    fun `registration result for a product already in the cart preserves its manually chosen quantity`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = true).withQuantity("4", version = 2L))
            val request = beginProductRegistration(viewModel)

            viewModel.onAction(SalesContract.Action.ProductRegistrationFinished(
                SalesContract.ProductRegistrationResult(request.requestId, PRODUCT_ID, BUSINESS_ID),
            ))
            runCurrent()

            assertTrue(sales.saveLineCalls.isEmpty())
            assertTrue(inventory.observedProducts.isEmpty())
            assertEquals(BigDecimal("4"), viewModel.uiState.value.lastScanAdded?.quantity)
            assertEquals(true, viewModel.uiState.value.lastScanAdded?.alreadyInCart)
            assertFalse(viewModel.uiState.value.productRegisteredWithoutCartAdd)
            assertNull(viewModel.uiState.value.productRegistration)
            assertNull(viewModel.uiState.value.pendingAssociationBarcode)
        }

    @Test
    fun `restored registration request and result wait for catalog then complete exactly once`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val savedState = SavedStateHandle()
            val original = createReadyViewModel(cart(withLine = false), savedStateHandle = savedState)
            val request = beginProductRegistration(original)
            val registered = seedAdditionalProduct(50L, NEW_BARCODE)
            setRegisteredProductDetail(registered)
            val lookupGate = CompletableDeferred<Unit>()
            products.idLookupGate = lookupGate
            original.onAction(SalesContract.Action.ProductRegistrationFinished(
                SalesContract.ProductRegistrationResult(request.requestId, registered.productId, BUSINESS_ID),
            ))
            runCurrent()
            assertTrue(original.uiState.value.isMutating)
            val restoredHandle = SavedStateHandle(savedState.keys().associateWith { savedState.get<Any?>(it) })
            original.viewModelScope.cancel()
            runCurrent()
            val catalogGate = CompletableDeferred<Unit>()
            val delayedInventory = object : InventoryReadRepository by inventory {
                override fun observeInventory(businessId: BusinessId): Flow<List<InventoryReadItem>> =
                    inventory.observeInventory(businessId).map { rows -> catalogGate.await(); rows }
            }
            sales.saveLineHandler = { command ->
                SaleCartMutationResult.Saved(cart(withLine = true).copy(
                    version = command.expectedVersion + 1,
                    lines = cart(withLine = true).lines.map { it.copy(productId = registered.productId) },
                ))
            }
            val restored = newViewModel(restoredHandle, delayedInventory)
            restored.onAction(SalesContract.Action.InitializeEntry(SalesContract.EntryKind.CASH, true))
            runCurrent()
            assertEquals(request, restored.uiState.value.productRegistration)
            assertTrue(sales.saveLineCalls.isEmpty())
            assertFalse(restored.uiState.value.canRouteScannerInput)
            catalogGate.complete(Unit)
            runCurrent()
            assertTrue(sales.saveLineCalls.isEmpty())
            lookupGate.complete(Unit)
            runCurrent()

            assertEquals(1, sales.saveLineCalls.size)
            assertEquals(registered.productId, sales.saveLineCalls.single().productId)
            assertEquals(BigDecimal.ONE, restored.uiState.value.lastScanAdded?.quantity)
            assertNull(restored.uiState.value.productRegistration)
            assertFalse(restored.uiState.value.productRegisteredWithoutCartAdd)
            assertTrue(restored.uiState.value.canRouteScannerInput)
        }

    @Test
    fun `a saved product remains registered when adding its sales line fails`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createReadyViewModel(cart(withLine = false))
            val request = beginProductRegistration(viewModel)
            val registered = seedAdditionalProduct(50L, NEW_BARCODE)
            setRegisteredProductDetail(registered)
            sales.saveLineHandler = { SaleCartMutationResult.Stale }

            viewModel.onAction(SalesContract.Action.ProductRegistrationFinished(
                SalesContract.ProductRegistrationResult(request.requestId, registered.productId, BUSINESS_ID),
            ))
            runCurrent()

            assertEquals(1, sales.saveLineCalls.size)
            assertEquals(registered, products.findById(registered.productId))
            assertEquals(SalesContract.Failure.STALE_CART, viewModel.uiState.value.failure)
            assertTrue(viewModel.uiState.value.productRegisteredWithoutCartAdd)
            assertNull(viewModel.uiState.value.productRegistration)
            assertNull(viewModel.uiState.value.pendingAssociationBarcode)
            assertNull(viewModel.uiState.value.lastScanAdded)
            assertTrue(viewModel.uiState.value.cartLines.isEmpty())
        }

    private suspend fun TestScope.beginProductRegistration(
        viewModel: SalesViewModel,
        barcode: String = NEW_BARCODE,
    ): SalesContract.ProductRegistrationRequest {
        viewModel.onAction(SalesContract.Action.BarcodeScanned(barcode))
        runCurrent()
        val navigation = async { viewModel.effects.first { it is SalesContract.Effect.RegisterProduct } }
        viewModel.onAction(SalesContract.Action.RegisterProductRequested(barcode))
        runCurrent()
        val request = (navigation.await() as SalesContract.Effect.RegisterProduct).request
        assertEquals(request, viewModel.uiState.value.productRegistration)
        return request
    }

    private fun setRegisteredProductDetail(product: Product) {
        val detail = inventoryItem().copy(productId = product.productId, productName = product.name)
        inventory.setProductDetail(BUSINESS_ID, product.productId, InventoryProductDetail(detail, emptyList()))
    }

    private suspend fun TestScope.seedAdditionalProduct(id: Long, barcode: String): Product {
        val product = products.create(
            requireNotNull(products.findById(PRODUCT_ID)).copy(
                productId = ProductId.from(uuid(id)),
                name = "Producto $id",
                barcode = barcode,
                sku = null,
            ),
        )
        val items = inventory.observeInventory(BUSINESS_ID).first()
        inventory.replaceInventory(
            BUSINESS_ID,
            items + inventoryItem().copy(productId = product.productId, productName = product.name),
        )
        runCurrent()
        return product
    }

    private suspend fun TestScope.createReadyViewModel(
        cart: SaleCart,
        availableQuantity: BigDecimal = BigDecimal.TEN,
        productBarcode: String? = EXISTING_BARCODE,
        productSku: String? = null,
        productSalePrice: Money? = null,
        enterSelling: Boolean = true,
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
        entryKind: SalesContract.EntryKind = SalesContract.EntryKind.CASH,
        allowEntryKindSelection: Boolean = true,
        unifiedInput: Boolean = false,
        inventoryReadRepository: InventoryReadRepository = inventory,
        unitCode: String = "NIU",
    ): SalesViewModel {
        configuration.enterDemoMode(BUSINESS_ID)
        units.create(
            UnitOfMeasure(
                unitId = UNIT_ID,
                businessId = BUSINESS_ID,
                code = unitCode,
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
                sku = productSku,
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
        val viewModel = newViewModel(savedStateHandle, inventoryReadRepository)
        viewModel.onAction(SalesContract.Action.InitializeEntry(entryKind, allowEntryKindSelection, unifiedInput))
        if (enterSelling) {
            viewModel.onAction(SalesContract.Action.EntryKindChanged(entryKind))
            viewModel.onAction(SalesContract.Action.ModeChanged(SalesContract.EntryMode.SCANNER))
        }
        runCurrent()
        assertFalse(viewModel.uiState.value.isLoading)
        assertTrue(
            viewModel.uiState.value.productOptions
                .isEmpty(),
        )
        return viewModel
    }

    private fun newViewModel(
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
        inventoryReadRepository: InventoryReadRepository = inventory,
    ) = SalesViewModel(
        configuration = configuration,
        products = products,
        inventoryReads = inventoryReadRepository,
        createCart = CreateSaleCartUseCase(configuration, sales),
        observeCart = ObserveSaleCartUseCase(sales),
        saveLine = SaveSaleCartLineUseCase(configuration, sales),
        removeLine = RemoveSaleCartLineUseCase(configuration, sales),
        checkout = CheckoutSaleUseCase(configuration, sales),
        saveProduct = SaveProductCatalogUseCase(products, units, locations),
        productMatching = ProductMatchingUseCase(products, aliases),
        dispatcherProvider = TestDispatcherProvider(mainDispatcherRule.dispatcher),
        savedStateHandle = savedStateHandle,
    )

    private fun inventoryItem(availableQuantity: BigDecimal = BigDecimal.TEN) =
        InventoryReadItem(
            productId = PRODUCT_ID,
            businessId = BUSINESS_ID,
            productName = "Producto",
            sku = null,
            unitCode = "NIU",
            unitSymbol = "u",
            positions =
                listOf(
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
        val lines =
            if (withLine) {
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

    private fun SaleCart.withQuantity(
        quantity: String,
        version: Long,
    ): SaleCart {
        val parsedQuantity = Quantity.of(BigDecimal(quantity))
        val lineTotal =
            Money.fromMajor(
                BigDecimal(quantity).multiply(BigDecimal("5.00")).toPlainString(),
                CURRENCY,
            )
        return copy(
            lines =
                listOf(
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
        val barcodeLookups = mutableListOf<Pair<BusinessId, String>>()
        var observeForBusinessCalls: Int = 0
            private set
        var nameSearchCalls: Int = 0
            private set
        var updateGate: CompletableDeferred<Unit>? = null
        var catalogEmissionGate: CompletableDeferred<Unit>? = null
        var barcodeLookupGate: CompletableDeferred<Unit>? = null
        var idLookupGate: CompletableDeferred<Unit>? = null
        var nextNameSearchFailure: Throwable? = null

        fun failCatalogObservation() {
            check(catalogObservationFailures.tryEmit(Unit))
        }

        override suspend fun findById(productId: ProductId): Product? {
            idLookupGate?.await()
            return delegate.findById(productId)
        }

        override fun observeForBusiness(businessId: BusinessId): Flow<List<Product>> {
            observeForBusinessCalls += 1
            val failures =
                flow<List<Product>> {
                    catalogObservationFailures.collect {
                        throw IllegalStateException("catalog observation unavailable")
                    }
                }
            return merge(
                delegate.observeForBusiness(businessId).map { rows ->
                    catalogEmissionGate?.await()
                    rows
                },
                failures,
            )
        }

        override suspend fun findByBarcode(
            businessId: BusinessId,
            barcode: String,
        ): Product? {
            findByBarcodeCalls += 1
            barcodeLookups += businessId to barcode
            barcodeLookupGate?.await()
            return delegate.findByBarcode(businessId, barcode)
        }

        override suspend fun findByNormalizedName(
            businessId: BusinessId,
            normalizedName: String,
        ): List<Product> {
            nameSearchCalls += 1
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
        var failNewObservations = false
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

        fun emitObserved(
            saleId: SaleId,
            value: SaleCart?,
        ) {
            carts.getOrPut(saleId) { MutableStateFlow(null) }.value = value
        }

        fun failCartObservation(saleId: SaleId) {
            val signal =
                cartObservationFailures.getOrPut(saleId) {
                    MutableSharedFlow(extraBufferCapacity = 1)
                }
            check(signal.tryEmit(Unit))
        }

        override fun observe(saleId: SaleId): Flow<SaleCart?> {
            observeCartCalls += 1
            if (failNewObservations) return flow { throw IllegalStateException("cart read unavailable") }
            val values =
                carts.getOrPut(saleId) {
                    MutableStateFlow(cartToOpen?.takeIf { it.saleId == saleId })
                }
            val failures =
                flow<SaleCart?> {
                    cartObservationFailures
                        .getOrPut(saleId) {
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
