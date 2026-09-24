package com.facturastock.app.feature.catalogs

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import app.cash.turbine.test
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.config.CostPolicy
import com.facturastock.app.domain.config.TaxRate
import com.facturastock.app.domain.model.CatalogDuplicateField
import com.facturastock.app.domain.model.CatalogSearch
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.CatalogMutationResult
import com.facturastock.app.domain.model.CatalogPage
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.InventoryCostAmount
import com.facturastock.app.domain.model.InventoryLocation
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.ProductInventoryPosition
import com.facturastock.app.domain.model.ProductInventorySummary
import com.facturastock.app.domain.model.Supplier
import com.facturastock.app.domain.model.SupplierProductAlias
import com.facturastock.app.domain.model.UnitCost
import com.facturastock.app.domain.model.UnitOfMeasure
import com.facturastock.app.domain.model.id.AliasId
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.SupplierId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.repository.ProductEditingRepository
import com.facturastock.app.domain.repository.ProductEditingSnapshot
import com.facturastock.app.domain.repository.ProductEditingPosition
import com.facturastock.app.domain.repository.ProductEditingResult
import com.facturastock.app.domain.repository.ProductEditingInvalidField
import com.facturastock.app.domain.repository.ProductStockEdit
import com.facturastock.app.domain.repository.ProductRepository
import com.facturastock.app.domain.repository.ProductInventoryRepository
import com.facturastock.app.domain.repository.ProductRegistrationRepository
import com.facturastock.app.domain.usecase.ObserveAppConfigurationUseCase
import com.facturastock.app.feature.catalogs.CatalogsContract.Action
import com.facturastock.app.feature.catalogs.CatalogsContract.Detail
import com.facturastock.app.feature.catalogs.CatalogsContract.Failure
import com.facturastock.app.feature.catalogs.CatalogsContract.Row
import com.facturastock.app.feature.catalogs.CatalogsContract.Section
import com.facturastock.app.feature.catalogs.CatalogsContract.Form
import com.facturastock.app.feature.common.RouteArgumentKeys
import com.facturastock.app.testing.FakeAppConfigurationRepository
import com.facturastock.app.testing.FakeInventoryLocationRepository
import com.facturastock.app.testing.FakeProductRepository
import com.facturastock.app.testing.FakeSupplierProductAliasRepository
import com.facturastock.app.testing.FakeSupplierRepository
import com.facturastock.app.testing.FakeUnitRepository
import com.facturastock.app.testing.MainDispatcherRule
import com.facturastock.app.testing.TestDispatcherProvider
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val now = Instant.parse("2026-08-13T01:00:00Z")
    private val clock = AppClock { now }
    private val businessId = BusinessId.from(uuid(1))
    private val unitId = UnitId.from(uuid(2))
    private val locationId = LocationId.from(uuid(3))
    private val supplierId = SupplierId.from(uuid(4))
    private var nextUuid = 100L

    private lateinit var config: FakeAppConfigurationRepository
    private lateinit var products: FakeProductRepository
    private lateinit var suppliers: FakeSupplierRepository
    private lateinit var units: FakeUnitRepository
    private lateinit var locations: FakeInventoryLocationRepository
    private lateinit var aliases: FakeSupplierProductAliasRepository
    private lateinit var inventory: FakeProductInventoryRepository
    private val registrations = mutableListOf<RegistrationWrite>()
    private var failRegistration = false
    private val editingPositions = mutableMapOf<ProductId, List<ProductEditingPosition>>()
    private val editingWrites = mutableListOf<ProductEditWrite>()
    private var failEditingLoad = false
    private var failEditingSave = false
    private var inventoryEditable = true

    @Before
    fun setUp() = runTest {
        config = FakeAppConfigurationRepository()
        products = FakeProductRepository(clock)
        suppliers = FakeSupplierRepository(clock)
        units = FakeUnitRepository(clock, products)
        locations = FakeInventoryLocationRepository(clock)
        aliases = FakeSupplierProductAliasRepository(clock)
        inventory = FakeProductInventoryRepository()
        config.completeOnboarding(
            businessId,
            TaxRate(BigDecimal("18")),
            CostPolicy.NET,
        )
        units.create(unit(unitId, "NIU", "Unidad"))
        locations.create(location(locationId, "Principal"))
        suppliers.create(supplier(supplierId, "Proveedor base"))
    }

    @Test
    fun `search debounces latest query and pages without materializing the entire catalog`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            repeat(75) { index ->
                products.create(
                    product(
                        ProductId.from(uuid(1_000L + index)),
                        "Producto %03d".format(index),
                    ),
                )
            }
            val viewModel = createViewModel()
            settleSearch()

            assertEquals(75, viewModel.uiState.value.total)
            assertEquals(50, viewModel.uiState.value.rows.size)
            assertTrue(viewModel.uiState.value.hasMore)

            viewModel.onAction(Action.LoadMore)
            runCurrent()
            assertEquals(75, viewModel.uiState.value.rows.size)
            assertFalse(viewModel.uiState.value.hasMore)

            viewModel.onAction(Action.QueryChanged("Producto 00"))
            runCurrent()
            advanceTimeBy(100)
            viewModel.onAction(Action.QueryChanged("Producto 074"))
            runCurrent()
            advanceTimeBy(251)
            runCurrent()

            assertEquals(1, viewModel.uiState.value.rows.size)
            assertEquals("Producto 074", viewModel.uiState.value.rows.single().title)
        }

    @Test
    fun `creates and edits all four local catalogs without a delete action`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()
            settleSearch()

            viewModel.onAction(Action.AddSelected)
            runCurrent()
            viewModel.onAction(Action.ProductNameChanged("Arroz extra"))
            viewModel.onAction(Action.ProductSkuChanged("arr-1"))
            viewModel.onAction(Action.ProductUnitSelected(unitId))
            viewModel.onAction(Action.ProductLocationSelected(locationId))
            runCurrent()
            viewModel.onAction(Action.SaveForm)
            runCurrent()
            val createdProduct = products.findBySku(businessId, "ARR-1")
            assertNotNull(createdProduct)

            viewModel.onAction(Action.SectionSelected(Section.SUPPLIERS))
            runCurrent()
            viewModel.onAction(Action.AddSelected)
            runCurrent()
            viewModel.onAction(Action.SupplierLegalNameChanged("Proveedor manual SAC"))
            runCurrent()
            viewModel.onAction(Action.SaveForm)
            runCurrent()
            val createdSupplier = suppliers.search(businessId, "Proveedor manual").single()
            assertEquals("Proveedor manual SAC", createdSupplier.legalName)

            viewModel.onAction(Action.RowSelected(Row.SupplierRow(createdSupplier)))
            runCurrent()
            viewModel.onAction(Action.EditSelected)
            runCurrent()
            viewModel.onAction(Action.SupplierTradeNameChanged("Mercado Norte"))
            runCurrent()
            viewModel.onAction(Action.SaveForm)
            runCurrent()
            assertEquals("Mercado Norte", suppliers.findById(createdSupplier.supplierId)?.tradeName)

            viewModel.onAction(Action.SectionSelected(Section.UNITS))
            runCurrent()
            viewModel.onAction(Action.AddSelected)
            runCurrent()
            viewModel.onAction(Action.UnitNameChanged("Caja"))
            viewModel.onAction(Action.UnitCodeChanged("bx"))
            runCurrent()
            viewModel.onAction(Action.SaveForm)
            runCurrent()
            assertNotNull(units.findByCode(businessId, "BX"))

            viewModel.onAction(Action.SectionSelected(Section.LOCATIONS))
            runCurrent()
            viewModel.onAction(Action.AddSelected)
            runCurrent()
            viewModel.onAction(Action.LocationNameChanged("Tienda 2"))
            runCurrent()
            viewModel.onAction(Action.SaveForm)
            runCurrent()
            assertNotNull(locations.findByName(businessId, "Tienda 2"))
            viewModel.effects.test { expectNoEvents() }
        }

    @Test
    fun `saving a product with quantity writes opening stock at the chosen location`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()
            settleSearch()

            viewModel.onAction(Action.AddSelected)
            runCurrent()
            viewModel.onAction(Action.ProductNameChanged("Azúcar rubia"))
            viewModel.onAction(Action.ProductUnitSelected(unitId))
            viewModel.onAction(Action.ProductLocationSelected(locationId))
            viewModel.onAction(Action.ProductQuantityChanged("12"))
            runCurrent()
            viewModel.onAction(Action.SaveForm)
            runCurrent()

            assertEquals(1, inventory.stockWrites.size)
            val write = inventory.stockWrites.single()
            assertEquals(locationId, write.locationId)
            assertEquals(BigDecimal("12"), write.quantity)
            assertEquals("Azúcar rubia", products.findById(write.productId)?.name)
        }

    @Test
    fun `inventory and scanned editors never observe the hidden catalog or refresh it after saving`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val existing = products.create(product(ProductId.from(uuid(901)), "Aceite").copy(barcode = "000901"))
            var subscriptions = 0
            val repository = object : ProductRepository by products {
                override fun observeSearch(businessId: BusinessId, search: CatalogSearch): Flow<CatalogPage<Product>> {
                    subscriptions++
                    return products.observeSearch(businessId, search)
                }
            }
            for (arguments in listOf(inventoryArguments(existing.productId), scannedArguments("000901"))) {
                val viewModel = createViewModel(arguments, repository)
                settleSearch()
                assertTrue((viewModel.uiState.value.form as Form.ProductForm).isInventoryOrigin)
                assertFalse(viewModel.uiState.value.isLoading)
                assertEquals(0, subscriptions)
                viewModel.onAction(Action.SaveForm)
                settleSearch()
                viewModel.effects.test { assertEquals(CatalogsContract.Effect.Back, awaitItem()) }
                assertEquals(0, subscriptions)
            }
        }

    @Test
    fun `new queries release the previous catalog observer before debounce and keep the latest search`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            products.create(product(ProductId.from(uuid(902)), "Aceite"))
            var active = 0
            var subscriptions = 0
            val repository = object : ProductRepository by products {
                override fun observeSearch(businessId: BusinessId, search: CatalogSearch): Flow<CatalogPage<Product>> = flow {
                    active++
                    subscriptions++
                    try {
                        emitAll(products.observeForBusiness(businessId).map { products.search(businessId, search) })
                    } finally {
                        active--
                    }
                }
            }
            val viewModel = createViewModel(productRepository = repository)
            settleSearch()
            assertEquals(1, active)
            viewModel.onAction(Action.QueryChanged("ac"))
            runCurrent()
            assertEquals(0, active)
            viewModel.onAction(Action.QueryChanged("ace"))
            settleSearch()
            assertEquals(1, active)
            assertEquals(2, subscriptions)
            assertEquals(listOf("Aceite"), viewModel.uiState.value.rows.map { it.title })
        }

    @Test
    fun `scanned registration keeps barcode fixed and requires all four product fields`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel(scannedArguments())
            settleSearch()
            val form = viewModel.uiState.value.form as Form.ProductForm
            assertEquals("0001234567895", form.barcode)
            assertTrue(form.isScannedRegistration)

            viewModel.onAction(Action.ProductBarcodeChanged("DIFFERENT"))
            viewModel.onAction(Action.ProductNameChanged("Arroz extra"))
            viewModel.onAction(Action.ProductQuantityChanged("2"))
            viewModel.onAction(Action.ProductPriceChanged("6.50"))
            runCurrent()
            viewModel.onAction(Action.SaveForm)
            runCurrent()

            assertEquals(Failure.INVALID_FIELDS, viewModel.uiState.value.failure)
            assertEquals("0001234567895", (viewModel.uiState.value.form as Form.ProductForm).barcode)
            assertTrue(registrations.isEmpty())
            assertTrue(products.search(businessId, "").isEmpty())
            assertTrue(inventory.stockWrites.isEmpty())
        }

    @Test
    fun `scanned registration submits exact barcode stock cost and sale price once`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val arguments = scannedArguments()
            val viewModel = createViewModel(arguments)
            settleSearch()
            fillScannedProduct(viewModel)
            runCurrent()

            viewModel.onAction(Action.SaveForm)
            viewModel.onAction(Action.SaveForm)
            runCurrent()

            val saved = registrations.single()
            assertEquals("0001234567895", saved.product.barcode)
            assertEquals("Arroz extra", saved.product.name)
            assertEquals(locationId, saved.product.locationId)
            assertEquals(BigDecimal("2.5"), saved.quantity)
            assertEquals(UnitCost.of("4.25", CurrencyCode.of("PEN")), saved.unitCost)
            assertEquals(BigDecimal("6.50"), saved.product.salePrice?.toMajor())
            assertTrue(inventory.stockWrites.isEmpty())
            assertNull(viewModel.uiState.value.form)
            assertTrue(viewModel.uiState.value.savedFeedback)
            viewModel.effects.test {
                assertEquals(CatalogsContract.Effect.Back, awaitItem())
                expectNoEvents()
            }
            val recreated = createViewModel(recreatedArguments(arguments))
            settleSearch()
            assertNull(recreated.uiState.value.form)
            assertEquals(1, registrations.size)
        }

    @Test
    fun `consecutive scanned registrations each return to scanner after their own save`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val codes = listOf("0001234567895", "0009876543210")
            codes.forEachIndexed { index, code ->
                val viewModel = createViewModel(scannedArguments(code))
                settleSearch()
                assertEquals(code, (viewModel.uiState.value.form as Form.ProductForm).barcode)
                fillScannedProduct(viewModel)
                viewModel.onAction(Action.ProductNameChanged("Producto ${index + 1}"))
                runCurrent()
                viewModel.onAction(Action.SaveForm)
                runCurrent()
                viewModel.effects.test {
                    assertEquals(CatalogsContract.Effect.Back, awaitItem())
                    expectNoEvents()
                }
                assertNull(viewModel.uiState.value.form)
            }

            assertEquals(codes, registrations.map { it.product.barcode })
            assertEquals(listOf("Producto 1", "Producto 2"), registrations.map { it.product.name })
            assertEquals(2, registrations.map { it.product.productId }.distinct().size)
        }

    @Test
    fun `sales registration returns the saved identity and replays only its result after recreation`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val arguments = salesRegistrationArguments()
            val viewModel = createViewModel(arguments)
            settleSearch()
            fillScannedProduct(viewModel)
            runCurrent()
            viewModel.onAction(Action.SaveForm)
            viewModel.onAction(Action.SaveForm)
            runCurrent()

            val saved = registrations.single().product
            val expected = CatalogsContract.Effect.ProductSaved("sale-request-1", saved.productId, businessId)
            viewModel.effects.test {
                assertEquals(expected, awaitItem())
                expectNoEvents()
            }
            assertNull(viewModel.uiState.value.form)

            val recreated = createViewModel(recreatedArguments(arguments))
            settleSearch()
            recreated.effects.test {
                assertEquals(expected, awaitItem())
                expectNoEvents()
            }
            recreated.onAction(Action.AddSelected)
            recreated.onAction(Action.SaveForm)
            runCurrent()
            assertNull(recreated.uiState.value.form)
            assertEquals(1, registrations.size)
        }

    @Test
    fun `canceling sales registration never returns a saved product or reopens its form`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val arguments = salesRegistrationArguments()
            val viewModel = createViewModel(arguments)
            settleSearch()
            fillScannedProduct(viewModel)
            runCurrent()
            viewModel.onAction(Action.CloseForm)
            viewModel.onAction(Action.SaveForm)
            runCurrent()

            viewModel.effects.test {
                assertEquals(CatalogsContract.Effect.Back, awaitItem())
                expectNoEvents()
            }
            assertTrue(registrations.isEmpty())
            val recreated = createViewModel(recreatedArguments(arguments))
            settleSearch()
            assertNull(recreated.uiState.value.form)
            recreated.effects.test {
                assertEquals(CatalogsContract.Effect.Back, awaitItem())
                expectNoEvents()
            }
        }

    @Test
    fun `sales registration rejects another business before opening the form`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel(salesRegistrationArguments(BusinessId.from(uuid(170))))
            settleSearch()
            viewModel.onAction(Action.AddSelected)
            fillScannedProduct(viewModel)
            viewModel.onAction(Action.SaveForm)
            runCurrent()

            assertNull(viewModel.uiState.value.form)
            assertTrue(viewModel.uiState.value.rows.isEmpty())
            assertTrue(viewModel.uiState.value.unitOptions.isEmpty())
            assertTrue(registrations.isEmpty())
            viewModel.effects.test {
                assertEquals(CatalogsContract.Effect.Back, awaitItem())
                expectNoEvents()
            }
        }

    @Test
    fun `changing business closes sales registration and cannot save its fields into the new business`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val arguments = salesRegistrationArguments()
            val viewModel = createViewModel(arguments)
            settleSearch()
            fillScannedProduct(viewModel)
            runCurrent()
            config.enterDemoMode(BusinessId.from(uuid(171)))
            runCurrent()
            viewModel.onAction(Action.SaveForm)
            runCurrent()

            assertNull(viewModel.uiState.value.form)
            assertTrue(registrations.isEmpty())
            viewModel.effects.test {
                assertEquals(CatalogsContract.Effect.Back, awaitItem())
                expectNoEvents()
            }
            config.enterDemoMode(businessId)
            settleSearch()
            assertNull(viewModel.uiState.value.form)
            viewModel.effects.test { expectNoEvents() }
        }

    @Test
    fun `failed sales registration keeps the form and emits no success until retry is saved`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel(salesRegistrationArguments())
            settleSearch()
            fillScannedProduct(viewModel)
            runCurrent()
            failRegistration = true
            viewModel.onAction(Action.SaveForm)
            runCurrent()

            assertNotNull(viewModel.uiState.value.form)
            assertEquals(Failure.SAVE_FAILED, viewModel.uiState.value.failure)
            viewModel.effects.test { expectNoEvents() }
            failRegistration = false
            viewModel.onAction(Action.SaveForm)
            runCurrent()
            viewModel.effects.test {
                assertEquals(
                    CatalogsContract.Effect.ProductSaved("sale-request-1", registrations.last().product.productId, businessId),
                    awaitItem(),
                )
                expectNoEvents()
            }
        }

    @Test
    fun `sales registration resolves an existing barcode and returns that product after saving`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val existing = products.create(product(ProductId.from(uuid(172)), "Arroz").copy(barcode = "0001234567895"))
            editingPositions[existing.productId] = listOf(editPosition(quantity = "3", cost = "4.25", version = 1))
            val viewModel = createViewModel(salesRegistrationArguments())
            settleSearch()
            assertEquals(existing.productId, (viewModel.uiState.value.form as Form.ProductForm).productId)
            viewModel.onAction(Action.SaveForm)
            runCurrent()

            assertTrue(registrations.isEmpty())
            viewModel.effects.test {
                assertEquals(CatalogsContract.Effect.ProductSaved("sale-request-1", existing.productId, businessId), awaitItem())
                expectNoEvents()
            }
        }

    @Test
    fun `editing an existing product keeps its normal editable form and stays in catalog`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val existing = product(ProductId.from(uuid(72)), "Arroz").copy(barcode = "000123")
            products.create(existing)
            val viewModel = createViewModel()
            settleSearch()
            viewModel.onAction(Action.RowSelected(Row.ProductRow(existing)))
            runCurrent()
            viewModel.onAction(Action.EditSelected)
            runCurrent()
            val form = viewModel.uiState.value.form as Form.ProductForm
            assertTrue(form.isEditing)
            assertFalse(form.isScannedRegistration)

            viewModel.onAction(Action.ProductNameChanged("Arroz extra"))
            viewModel.onAction(Action.ProductBarcodeChanged("000456"))
            runCurrent()
            viewModel.onAction(Action.SaveForm)
            runCurrent()

            val saved = requireNotNull(products.findById(existing.productId))
            assertEquals("Arroz extra", saved.name)
            assertEquals("000456", saved.barcode)
            assertNull(viewModel.uiState.value.form)
            assertTrue(registrations.isEmpty())
            viewModel.effects.test { expectNoEvents() }
        }

    @Test
    fun `scanning an existing product opens the inventory editor and metadata save preserves stock`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val existing = products.create(product(ProductId.from(uuid(73)), "Arroz").copy(barcode = "000123"))
            val position = editPosition(quantity = "12.5", cost = "3.25", version = 4)
            editingPositions[existing.productId] = listOf(position)
            val viewModel = createViewModel(scannedArguments("000123"))
            settleSearch()
            val form = viewModel.uiState.value.form as Form.ProductForm
            assertEquals(existing, form.original)
            assertEquals(existing.productId, form.productId)
            assertTrue(form.isInventoryOrigin)
            assertFalse(form.isScannerOrigin)
            assertTrue(form.isEditing)
            assertFalse(form.isScannedRegistration)
            assertEquals("12.5", form.quantity)
            assertEquals("3.25", form.purchasePrice)
            val directEditor = createViewModel(inventoryArguments(existing.productId))
            settleSearch()
            assertEquals(directEditor.uiState.value.form, form)
            assertEquals(directEditor.uiState.value.inventoryBalances, viewModel.uiState.value.inventoryBalances)
            assertTrue(viewModel.uiState.value.isInventoryStockEditable)
            assertTrue(inventory.stockWrites.isEmpty())
            viewModel.onAction(Action.ProductNameChanged("Arroz extra"))
            runCurrent()
            viewModel.onAction(Action.SaveForm)
            runCurrent()
            val saved = requireNotNull(products.findById(existing.productId))
            assertEquals("000123", saved.barcode)
            assertEquals("Arroz extra", saved.name)
            assertEquals(1, products.search(businessId, "").size)
            assertTrue(registrations.isEmpty())
            assertTrue(inventory.stockWrites.isEmpty())
            assertTrue(editingWrites.single().stockEdits.isEmpty())
            assertEquals(position, editingPositions[existing.productId]?.single())
            viewModel.effects.test { assertEquals(CatalogsContract.Effect.Back, awaitItem()); expectNoEvents() }
        }

    @Test
    fun `existing scanned product saves quantity purchase cost and sale price through the inventory editor`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val existing = products.create(product(ProductId.from(uuid(78)), "Aceite", "ACE-78").copy(
                barcode = "000178", salePrice = Money.fromMajor("8.90", CurrencyCode.of("PEN")),
            ))
            val position = editPosition(quantity = "12.5", cost = "4.75", version = 7)
            editingPositions[existing.productId] = listOf(position)
            val viewModel = createViewModel(scannedArguments("000178"))
            settleSearch()
            val form = viewModel.uiState.value.form as Form.ProductForm
            assertEquals("12.5", form.quantity)
            assertEquals("4.75", form.purchasePrice)
            assertEquals("8.90", form.salePrice)
            assertTrue(editingWrites.isEmpty())
            viewModel.onAction(Action.ProductQuantityChanged("10,5"))
            viewModel.onAction(Action.ProductPurchasePriceChanged("5,25"))
            viewModel.onAction(Action.ProductPriceChanged("9,50"))
            runCurrent()
            viewModel.onAction(Action.SaveForm)
            viewModel.onAction(Action.SaveForm)
            runCurrent()
            val write = editingWrites.single()
            assertEquals(existing, write.expected.product)
            assertEquals(position, write.expected.positions.single())
            assertEquals(existing.productId, write.candidate.productId)
            assertEquals(Money.fromMajor("9.50", CurrencyCode.of("PEN")), write.candidate.salePrice)
            assertEquals(listOf(ProductStockEdit(locationId, BigDecimal("10.5"), BigDecimal("5.25"))), write.stockEdits)
            assertEquals(BigDecimal("10.5"), editingPositions[existing.productId]?.single()?.quantityOnHand)
            assertEquals(BigDecimal("5.25"), editingPositions[existing.productId]?.single()?.averageUnitCost)
            assertEquals(1, products.search(businessId, "").size)
            assertTrue(registrations.isEmpty())
            assertTrue(inventory.stockWrites.isEmpty())
            viewModel.effects.test { assertEquals(CatalogsContract.Effect.Back, awaitItem()); expectNoEvents() }
        }

    @Test
    fun `scanning archived product preserves archived status and does not set missing location`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val existing = products.create(product(ProductId.from(uuid(74)), "Archivado").copy(
                barcode = "000124", status = CatalogStatus.ARCHIVED, locationId = null,
            ))
            val viewModel = createViewModel(scannedArguments("000124"))
            settleSearch()
            assertEquals(CatalogStatus.ARCHIVED, (viewModel.uiState.value.form as Form.ProductForm).original?.status)
            viewModel.onAction(Action.ProductNameChanged("Archivado corregido"))
            runCurrent()
            viewModel.onAction(Action.SaveForm)
            runCurrent()
            val saved = requireNotNull(products.findById(existing.productId))
            assertEquals(CatalogStatus.ARCHIVED, saved.status)
            assertNull(saved.locationId)
            assertTrue(registrations.isEmpty())
            assertTrue(inventory.stockWrites.isEmpty())
            viewModel.effects.test { assertEquals(CatalogsContract.Effect.Back, awaitItem()) }
        }

    @Test
    fun `existing scanned draft restores identity fields and original version then cancels without writes`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val existing = products.create(product(ProductId.from(uuid(75)), "Arroz").copy(barcode = "000125"))
            val position = editPosition(quantity = "15", cost = "2.5", version = 6)
            editingPositions[existing.productId] = listOf(position)
            val arguments = scannedArguments("000125")
            val viewModel = createViewModel(arguments)
            settleSearch()
            viewModel.onAction(Action.ProductNameChanged("Nombre pendiente"))
            viewModel.onAction(Action.ProductQuantityChanged("12,"))
            viewModel.onAction(Action.ProductPurchasePriceChanged("3,25"))
            viewModel.onAction(Action.ProductPriceChanged("4,50"))
            runCurrent()
            val form = viewModel.uiState.value.form as Form.ProductForm
            val restoredArguments = recreatedArguments(arguments)
            val recreated = createViewModel(restoredArguments)
            settleSearch()
            assertEquals(form, recreated.uiState.value.form)
            assertTrue((recreated.uiState.value.form as Form.ProductForm).isInventoryOrigin)
            recreated.onAction(Action.CloseForm)
            runCurrent()
            assertEquals(existing, products.findById(existing.productId))
            assertTrue(registrations.isEmpty())
            assertTrue(inventory.stockWrites.isEmpty())
            assertTrue(editingWrites.isEmpty())
            assertEquals(position, editingPositions[existing.productId]?.single())
            recreated.effects.test { assertEquals(CatalogsContract.Effect.Back, awaitItem()) }
            val afterCancel = createViewModel(recreatedArguments(restoredArguments))
            settleSearch()
            assertNull(afterCancel.uiState.value.form)
        }

    @Test
    fun `restoring scanned existing edit keeps original CAS after concurrent update`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val existing = products.create(product(ProductId.from(uuid(76)), "Arroz").copy(barcode = "000126"))
            val arguments = scannedArguments("000126")
            val viewModel = createViewModel(arguments)
            settleSearch()
            viewModel.onAction(Action.ProductNameChanged("Edición vieja"))
            runCurrent()
            products.update(existing.copy(name = "Edición concurrente"))
            val recreated = createViewModel(recreatedArguments(arguments))
            settleSearch()
            assertEquals(existing.version, (recreated.uiState.value.form as Form.ProductForm).original?.version)
            recreated.onAction(Action.SaveForm)
            runCurrent()
            assertEquals(Failure.STALE, recreated.uiState.value.failure)
            assertEquals("Edición concurrente", products.findById(existing.productId)?.name)
            assertTrue(inventory.stockWrites.isEmpty())
            recreated.effects.test { expectNoEvents() }
        }

    @Test
    fun `a pending scanned registration that now exists loads current inventory instead of replaying opening stock`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val arguments = scannedArguments()
            val pending = createViewModel(arguments)
            settleSearch()
            fillScannedProduct(pending)
            runCurrent()
            val existing = products.create(product(ProductId.from(uuid(79)), "Registrado en paralelo", "PAR-79").copy(
                barcode = "0001234567895", salePrice = Money.fromMajor("7.50", CurrencyCode.of("PEN")),
            ))
            val position = editPosition(quantity = "18", cost = "3.75", version = 9)
            editingPositions[existing.productId] = listOf(position)
            val recreated = createViewModel(recreatedArguments(arguments))
            settleSearch()
            val form = recreated.uiState.value.form as Form.ProductForm
            assertTrue(form.isInventoryOrigin)
            assertFalse(form.isScannedRegistration)
            assertEquals(existing, form.original)
            assertEquals("Registrado en paralelo", form.title)
            assertEquals("PAR-79", form.sku)
            assertEquals("18", form.quantity)
            assertEquals("3.75", form.purchasePrice)
            assertEquals("7.50", form.salePrice)
            assertTrue(editingWrites.isEmpty())
            assertTrue(registrations.isEmpty())
            recreated.onAction(Action.SaveForm)
            runCurrent()
            assertTrue(editingWrites.single().stockEdits.isEmpty())
            assertEquals(existing, products.findById(existing.productId))
            assertEquals(position, editingPositions[existing.productId]?.single())
            assertTrue(registrations.isEmpty())
            assertTrue(inventory.stockWrites.isEmpty())
            recreated.effects.test { assertEquals(CatalogsContract.Effect.Back, awaitItem()); expectNoEvents() }
        }

    @Test
    fun `legacy scanned edit restores metadata and original CAS while loading current stock into inventory editor`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val existing = products.create(product(ProductId.from(uuid(80)), "Original", "OLD-80").copy(barcode = "000180"))
            val arguments = SavedStateHandle(mapOf(
                "catalogs.scannedRegistration" to arrayListOf(
                    businessId.value, "000180", "Nombre pendiente", "99", "88.50", "7,50",
                    unitId.value, locationId.value, existing.productId.value, existing.version.toString(),
                    "DRAFT-80", "", "",
                ),
            ))
            products.update(existing.copy(name = "Cambio concurrente"))
            val position = editPosition(quantity = "8", cost = "2.50", version = 10)
            editingPositions[existing.productId] = listOf(position)
            val viewModel = createViewModel(arguments)
            settleSearch()
            val form = viewModel.uiState.value.form as Form.ProductForm
            assertTrue(form.isInventoryOrigin)
            assertEquals(existing.productId, form.productId)
            assertEquals(existing.version, form.original?.version)
            assertEquals("Nombre pendiente", form.title)
            assertEquals("DRAFT-80", form.sku)
            assertEquals("7,50", form.salePrice)
            assertEquals("8", form.quantity)
            assertEquals("2.5", form.purchasePrice)
            assertEquals(position, form.inventorySnapshot?.positions?.single())
            viewModel.onAction(Action.SaveForm)
            runCurrent()
            assertEquals(Failure.STALE, viewModel.uiState.value.failure)
            assertEquals("Cambio concurrente", products.findById(existing.productId)?.name)
            assertEquals(position, editingPositions[existing.productId]?.single())
            assertTrue(editingWrites.single().stockEdits.isEmpty())
            assertTrue(registrations.isEmpty())
            assertTrue(inventory.stockWrites.isEmpty())
            viewModel.effects.test { expectNoEvents() }
        }

    @Test
    fun `existing scanned product stays blocked when inventory load fails and retry opens the full editor`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val existing = products.create(product(ProductId.from(uuid(81)), "Arroz").copy(barcode = "000181"))
            val position = editPosition(quantity = "11", cost = "2.75", version = 12)
            editingPositions[existing.productId] = listOf(position)
            failEditingLoad = true
            val viewModel = createViewModel(scannedArguments("000181"))
            settleSearch()
            assertTrue(viewModel.uiState.value.isInventoryEntryPending)
            assertEquals(Failure.LOAD_FAILED, viewModel.uiState.value.inventoryEntryFailure)
            assertNull(viewModel.uiState.value.form)
            viewModel.onAction(Action.AddSelected)
            viewModel.onAction(Action.SaveForm)
            runCurrent()
            assertTrue(registrations.isEmpty())
            assertTrue(editingWrites.isEmpty())
            failEditingLoad = false
            viewModel.onAction(Action.Retry)
            runCurrent()
            val form = viewModel.uiState.value.form as Form.ProductForm
            assertTrue(form.isInventoryOrigin)
            assertEquals("11", form.quantity)
            assertEquals("2.75", form.purchasePrice)
            assertFalse(viewModel.uiState.value.isInventoryEntryPending)
            viewModel.onAction(Action.CloseForm)
            runCurrent()
            assertEquals(existing, products.findById(existing.productId))
            assertTrue(editingWrites.isEmpty())
            viewModel.effects.test { assertEquals(CatalogsContract.Effect.Back, awaitItem()); expectNoEvents() }
        }

    @Test
    fun `barcode lookup error stays cancelable and retry opens existing editor instead of new product`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val existing = products.create(product(ProductId.from(uuid(77)), "Arroz").copy(barcode = "000127"))
            var failLookup = true
            val repository = object : ProductRepository by products {
                override suspend fun findByBarcode(businessId: BusinessId, barcode: String): Product? {
                    if (failLookup) error("No se pudo consultar el código")
                    return products.findByBarcode(businessId, barcode)
                }
            }
            val viewModel = createViewModel(scannedArguments("000127"), repository)
            settleSearch()
            assertTrue(viewModel.uiState.value.isScannerEntryPending)
            assertEquals(Failure.LOAD_FAILED, viewModel.uiState.value.scannerEntryFailure)
            assertNull(viewModel.uiState.value.form)
            viewModel.onAction(Action.AddSelected)
            viewModel.onAction(Action.SaveForm)
            runCurrent()
            assertNull(viewModel.uiState.value.form)
            assertTrue(registrations.isEmpty())
            failLookup = false
            viewModel.onAction(Action.Retry)
            runCurrent()
            assertEquals(existing.productId, (viewModel.uiState.value.form as Form.ProductForm).productId)
            assertFalse(viewModel.uiState.value.isScannerEntryPending)
            viewModel.onAction(Action.CloseForm)
            runCurrent()
            assertEquals(existing, products.findById(existing.productId))
            assertTrue(inventory.stockWrites.isEmpty())
            viewModel.effects.test { assertEquals(CatalogsContract.Effect.Back, awaitItem()) }
        }

    @Test
    fun `canceling failed barcode lookup consumes the route without opening another form after recreation`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val arguments = scannedArguments()
            val repository = object : ProductRepository by products {
                override suspend fun findByBarcode(businessId: BusinessId, barcode: String): Product? = error("Fallo")
            }
            val viewModel = createViewModel(arguments, repository)
            settleSearch()
            assertEquals(Failure.LOAD_FAILED, viewModel.uiState.value.scannerEntryFailure)
            viewModel.onAction(Action.CloseForm)
            runCurrent()
            viewModel.effects.test { assertEquals(CatalogsContract.Effect.Back, awaitItem()) }
            val recreated = createViewModel(recreatedArguments(arguments))
            settleSearch()
            assertNull(recreated.uiState.value.form)
            assertFalse(recreated.uiState.value.isScannerEntryPending)
            assertTrue(products.search(businessId, "").isEmpty())
            assertTrue(registrations.isEmpty())
            assertTrue(inventory.stockWrites.isEmpty())
        }

    @Test
    fun `registration failure preserves all fields across recreation and allows retry`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val arguments = scannedArguments()
            val viewModel = createViewModel(arguments)
            settleSearch()
            fillScannedProduct(viewModel)
            runCurrent()
            val form = viewModel.uiState.value.form
            failRegistration = true
            viewModel.onAction(Action.SaveForm)
            runCurrent()
            assertEquals(Failure.SAVE_FAILED, viewModel.uiState.value.failure)
            assertEquals(form, viewModel.uiState.value.form)
            assertFalse(viewModel.uiState.value.isSaving)
            viewModel.effects.test { expectNoEvents() }

            val recreated = createViewModel(recreatedArguments(arguments))
            settleSearch()
            assertEquals(form, recreated.uiState.value.form)
            assertFalse(recreated.uiState.value.isSaving)
            failRegistration = false
            recreated.onAction(Action.SaveForm)
            runCurrent()
            assertNull(recreated.uiState.value.form)
            assertEquals(2, registrations.size)
        }

    @Test
    fun `invalid quantities or prices cannot create a scanned product`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel(scannedArguments())
            settleSearch()
            val invalidActions = listOf(
                Action.ProductNameChanged(" "),
                Action.ProductQuantityChanged("0"),
                Action.ProductQuantityChanged("-2"),
                Action.ProductPurchasePriceChanged("-1"),
                Action.ProductPurchasePriceChanged("1,2.5"),
                Action.ProductPriceChanged("0"),
                Action.ProductPriceChanged("6,501"),
            )
            invalidActions.forEach { invalidAction ->
                fillScannedProduct(viewModel)
                viewModel.onAction(invalidAction)
                runCurrent()
                viewModel.onAction(Action.SaveForm)
                runCurrent()
                assertEquals(invalidAction.toString(), Failure.INVALID_FIELDS, viewModel.uiState.value.failure)
                assertNotNull(viewModel.uiState.value.form)
            }
            assertTrue(registrations.isEmpty())
            assertTrue(products.search(businessId, "").isEmpty())
        }

    @Test
    fun `scanned form restores the exact partial input and selected inventory ids after process recreation`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val arguments = scannedArguments()
            val viewModel = createViewModel(arguments)
            settleSearch()
            fillScannedProduct(viewModel)
            // Conserva también la edición todavía incompleta, sin normalizar ni guardarla.
            viewModel.onAction(Action.ProductQuantityChanged("2,"))
            runCurrent()
            val expected = viewModel.uiState.value.form as Form.ProductForm
            assertNull(arguments.get<String>(RouteArgumentKeys.PREFILL_BARCODE))

            val recreated = createViewModel(recreatedArguments(arguments))
            settleSearch()
            val restored = recreated.uiState.value.form as Form.ProductForm
            assertEquals(expected, restored)
            assertEquals("0001234567895", restored.barcode)
            assertEquals("2,", restored.quantity)
            assertEquals("4,25", restored.purchasePrice)
            assertEquals("6,50", restored.salePrice)
            assertEquals(unitId, restored.unitId)
            assertEquals(locationId, restored.locationId)
            assertTrue(restored.isScannedRegistration)
            assertTrue(registrations.isEmpty())
        }

    @Test
    fun `canceling a scanned form prevents it reopening on recreation or later manual registration`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val arguments = scannedArguments()
            val viewModel = createViewModel(arguments)
            settleSearch()
            fillScannedProduct(viewModel)
            runCurrent()
            viewModel.onAction(Action.CloseForm)
            runCurrent()
            viewModel.effects.test {
                assertEquals(CatalogsContract.Effect.Back, awaitItem())
                expectNoEvents()
            }

            val recreated = createViewModel(recreatedArguments(arguments))
            settleSearch()
            assertNull(recreated.uiState.value.form)
            recreated.onAction(Action.AddSelected)
            runCurrent()
            val manual = recreated.uiState.value.form as Form.ProductForm
            assertEquals("", manual.barcode)
            assertEquals("", manual.title)
            assertFalse(manual.isScannedRegistration)
            assertTrue(registrations.isEmpty())
            recreated.onAction(Action.CloseForm)
            runCurrent()
            recreated.effects.test { expectNoEvents() }
            recreated.onAction(Action.AddSelected)
            runCurrent()
            val nextManual = recreated.uiState.value.form as Form.ProductForm
            assertEquals("", nextManual.barcode)
            assertFalse(nextManual.isScannedRegistration)
        }

    @Test
    fun `a scanned draft from a different business is discarded even when route arguments remain`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val arguments = scannedArguments()
            val viewModel = createViewModel(arguments)
            settleSearch()
            fillScannedProduct(viewModel)
            runCurrent()
            val previousProcessState = recreatedArguments(arguments)
            previousProcessState[RouteArgumentKeys.PREFILL_BARCODE] = "0001234567895"

            config.completeOnboarding(BusinessId.from(uuid(71)), TaxRate(BigDecimal("18")), CostPolicy.NET)
            settleSearch()
            assertNull(viewModel.uiState.value.form)
            val recreated = createViewModel(previousProcessState)
            settleSearch()
            assertNull(recreated.uiState.value.form)
            assertNull(previousProcessState.get<String>(RouteArgumentKeys.PREFILL_BARCODE))

            // Volver al negocio original tampoco resucita un formulario descartado al cambiar.
            config.completeOnboarding(businessId, TaxRate(BigDecimal("18")), CostPolicy.NET)
            settleSearch()
            assertNull(viewModel.uiState.value.form)
            assertNull(recreated.uiState.value.form)
            assertTrue(registrations.isEmpty())
        }

    @Test
    fun `inventory editor resolves product without barcode and saves metadata preserving identity and stock`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val existing = products.create(product(ProductId.from(uuid(180)), "Sin código", "INV-180").copy(
                locationId = null,
                status = CatalogStatus.ARCHIVED,
                salePrice = Money.fromMajor("2.50", CurrencyCode.of("USD")),
            ))
            val arguments = inventoryArguments(existing.productId)
            val repository = object : ProductRepository by products {
                override suspend fun findByBarcode(businessId: BusinessId, barcode: String): Product? =
                    error("Un producto sin código debe resolverse por ID")
            }
            val viewModel = createViewModel(arguments, repository)
            settleSearch()
            val form = viewModel.uiState.value.form as Form.ProductForm
            assertTrue(form.isInventoryOrigin)
            assertFalse(form.isScannerOrigin)
            assertFalse(form.isScannedRegistration)
            assertEquals(existing, form.original)
            assertEquals("", form.barcode)
            assertEquals("", form.quantity)
            assertEquals("", form.purchasePrice)
            assertEquals(locationId, form.locationId)
            assertNull(form.original?.locationId)
            viewModel.onAction(Action.ProductNameChanged("Nombre corregido"))
            viewModel.onAction(Action.ProductPriceChanged("3,75"))
            // Eventos que no pertenecen a este editor tampoco pueden alterar datos ocultos.
            viewModel.onAction(Action.ProductUnitSelected(UnitId.from(uuid(199))))
            viewModel.onAction(Action.ProductLocationSelected(locationId))
            viewModel.onAction(Action.AddSelected)
            runCurrent()
            viewModel.onAction(Action.SaveForm)
            viewModel.onAction(Action.SaveForm)
            runCurrent()
            assertEquals(existing.copy(
                name = "Nombre corregido", salePrice = Money.fromMajor("3.75", CurrencyCode.of("USD")),
                version = existing.version + 1,
            ), products.findById(existing.productId))
            assertTrue(inventory.stockWrites.isEmpty())
            assertTrue(registrations.isEmpty())
            assertEquals(100L, nextUuid)
            viewModel.effects.test { assertEquals(CatalogsContract.Effect.Back, awaitItem()); expectNoEvents() }
            assertNull(arguments.get<String>(RouteArgumentKeys.EDIT_PRODUCT_ID))
            val recreated = createViewModel(recreatedArguments(arguments))
            settleSearch()
            assertNull(recreated.uiState.value.form)
        }

    @Test
    fun `inventory draft restores partial fields and original CAS even after concurrent metadata changes`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val existing = products.create(product(ProductId.from(uuid(181)), "Original", "INV-181").copy(
                salePrice = Money.fromMajor("4.50", CurrencyCode.of("USD")),
                locationId = null,
            ))
            val arguments = inventoryArguments(existing.productId)
            val viewModel = createViewModel(arguments)
            settleSearch()
            viewModel.onAction(Action.ProductNameChanged("Nombre pendiente"))
            viewModel.onAction(Action.ProductPriceChanged("6,"))
            viewModel.onAction(Action.ProductSkuChanged("DRAFT-SKU"))
            viewModel.onAction(Action.ProductBarcodeChanged("000181"))
            runCurrent()
            val expectedForm = viewModel.uiState.value.form
            products.update(existing.copy(name = "Cambio simultáneo", barcode = "123456", sku = "NEW-SKU"))
            val restoredArguments = recreatedArguments(arguments)
            val recreated = createViewModel(restoredArguments)
            settleSearch()
            assertEquals(expectedForm, recreated.uiState.value.form)
            val restored = recreated.uiState.value.form as Form.ProductForm
            assertEquals("6,", restored.salePrice)
            assertEquals("DRAFT-SKU", restored.sku)
            assertEquals("000181", restored.barcode)
            assertEquals(existing.version, restored.original?.version)
            assertNull(restored.original?.barcode)
            assertEquals(CurrencyCode.of("USD"), restored.saleCurrency)
            recreated.onAction(Action.SaveForm)
            runCurrent()
            assertEquals(Failure.STALE, recreated.uiState.value.failure)
            assertEquals("Cambio simultáneo", products.findById(existing.productId)?.name)
            assertEquals("123456", products.findById(existing.productId)?.barcode)
            assertTrue(inventory.stockWrites.isEmpty())
            assertTrue(registrations.isEmpty())
            recreated.effects.test { expectNoEvents() }
            recreated.onAction(Action.CloseForm)
            runCurrent()
            recreated.effects.test { assertEquals(CatalogsContract.Effect.Back, awaitItem()) }
            val afterCancel = createViewModel(recreatedArguments(restoredArguments))
            settleSearch()
            assertNull(afterCancel.uiState.value.form)
        }

    @Test
    fun `inventory ID lookup failure shows retry and never opens creation`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val existing = products.create(product(ProductId.from(uuid(182)), "Sin código"))
            var failLookup = true
            val repository = object : ProductRepository by products {
                override suspend fun findById(productId: ProductId): Product? {
                    if (failLookup) error("Lectura fallida")
                    return products.findById(productId)
                }
            }
            val arguments = inventoryArguments(existing.productId)
            val viewModel = createViewModel(arguments, repository)
            settleSearch()
            assertTrue(viewModel.uiState.value.isInventoryEntryPending)
            assertEquals(Failure.LOAD_FAILED, viewModel.uiState.value.inventoryEntryFailure)
            viewModel.onAction(Action.AddSelected)
            viewModel.onAction(Action.SaveForm)
            runCurrent()
            assertNull(viewModel.uiState.value.form)
            failLookup = false
            viewModel.onAction(Action.Retry)
            runCurrent()
            assertFalse(viewModel.uiState.value.isInventoryEntryPending)
            assertEquals(existing, (viewModel.uiState.value.form as Form.ProductForm).original)
            viewModel.onAction(Action.CloseForm)
            runCurrent()
            viewModel.effects.test { assertEquals(CatalogsContract.Effect.Back, awaitItem()) }
            assertEquals(existing, products.findById(existing.productId))
            assertTrue(inventory.stockWrites.isEmpty())
            assertTrue(registrations.isEmpty())
        }

    @Test
    fun `invalid missing and foreign inventory IDs remain cancelable without writing`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val foreign = products.create(product(ProductId.from(uuid(183)), "Ajeno").copy(
                businessId = BusinessId.from(uuid(184)),
            ))
            listOf("invalid-id", "", ProductId.from(uuid(185)).value, foreign.productId.value).forEach { rawId ->
                val arguments = SavedStateHandle(mapOf(RouteArgumentKeys.EDIT_PRODUCT_ID to rawId))
                val viewModel = createViewModel(arguments)
                settleSearch()
                assertTrue(viewModel.uiState.value.isInventoryEntryPending)
                assertEquals(Failure.NOT_FOUND, viewModel.uiState.value.inventoryEntryFailure)
                viewModel.onAction(Action.SaveForm)
                viewModel.onAction(Action.AddSelected)
                runCurrent()
                assertNull(viewModel.uiState.value.form)
                viewModel.onAction(Action.CloseForm)
                runCurrent()
                viewModel.effects.test { assertEquals(CatalogsContract.Effect.Back, awaitItem()) }
                val recreated = createViewModel(recreatedArguments(arguments))
                settleSearch()
                assertFalse(recreated.uiState.value.isInventoryEntryPending)
                assertNull(recreated.uiState.value.form)
            }
            assertEquals(foreign, products.findById(foreign.productId))
            assertTrue(inventory.stockWrites.isEmpty())
            assertTrue(registrations.isEmpty())
        }

    @Test
    fun `inventory edit cannot recreate a product that disappears before saving`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val existing = products.create(product(ProductId.from(uuid(186)), "Original"))
            var missing = false
            var createCalls = 0
            val repository = object : ProductRepository by products {
                override suspend fun findById(productId: ProductId): Product? =
                    if (missing) null else products.findById(productId)
                override suspend fun create(product: Product): Product {
                    createCalls++
                    return products.create(product)
                }
            }
            val viewModel = createViewModel(inventoryArguments(existing.productId), repository)
            settleSearch()
            viewModel.onAction(Action.ProductNameChanged("Edición"))
            runCurrent()
            missing = true
            viewModel.onAction(Action.SaveForm)
            runCurrent()
            assertEquals(Failure.NOT_FOUND, viewModel.uiState.value.failure)
            assertEquals(0, createCalls)
            assertEquals(existing, products.findById(existing.productId))
            assertTrue(inventory.stockWrites.isEmpty())
            assertTrue(registrations.isEmpty())
            viewModel.effects.test { expectNoEvents() }
        }

    @Test
    fun `inventory editor validates duplicate SKU and barcode without modifying original`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val existing = products.create(product(ProductId.from(uuid(187)), "Original", "SKU-187").copy(barcode = "00187"))
            val conflicting = product(ProductId.from(uuid(188)), "Conflicto", "SKU-187").copy(barcode = "00187")
            var duplicateSku = true
            val repository = object : ProductRepository by products {
                override suspend fun findBySku(businessId: BusinessId, sku: String): Product? =
                    if (duplicateSku) conflicting else products.findBySku(businessId, sku)
                override suspend fun findByBarcode(businessId: BusinessId, barcode: String): Product = conflicting
            }
            val viewModel = createViewModel(inventoryArguments(existing.productId), repository)
            settleSearch()
            viewModel.onAction(Action.ProductNameChanged("Edición"))
            runCurrent()
            viewModel.onAction(Action.SaveForm)
            runCurrent()
            assertEquals(Failure.DUPLICATE_SKU, viewModel.uiState.value.failure)
            duplicateSku = false
            viewModel.onAction(Action.SaveForm)
            runCurrent()
            assertEquals(Failure.DUPLICATE_BARCODE, viewModel.uiState.value.failure)
            assertEquals(existing, products.findById(existing.productId))
            assertTrue(inventory.stockWrites.isEmpty())
            viewModel.effects.test { expectNoEvents() }
        }

    @Test
    fun `business change consumes inventory editor and ignores a late ID lookup`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val existing = products.create(product(ProductId.from(uuid(189)), "Original"))
            val lookup = CompletableDeferred<Product?>()
            val repository = object : ProductRepository by products {
                override suspend fun findById(productId: ProductId): Product? = withContext(NonCancellable) { lookup.await() }
            }
            val arguments = inventoryArguments(existing.productId)
            val viewModel = createViewModel(arguments, repository)
            settleSearch()
            assertTrue(viewModel.uiState.value.isInventoryEntryPending)
            config.enterDemoMode(BusinessId.from(uuid(190)))
            settleSearch()
            lookup.complete(existing)
            runCurrent()
            assertNull(viewModel.uiState.value.form)
            assertFalse(viewModel.uiState.value.isInventoryEntryPending)
            assertNull(arguments.get<String>(RouteArgumentKeys.EDIT_PRODUCT_ID))
            viewModel.onAction(Action.SaveForm)
            runCurrent()
            viewModel.effects.test { assertEquals(CatalogsContract.Effect.Back, awaitItem()); expectNoEvents() }
            assertEquals(existing, products.findById(existing.productId))
            assertTrue(inventory.stockWrites.isEmpty())
            assertTrue(registrations.isEmpty())
        }

    @Test
    fun `inventory draft from a different active business cannot be restored or saved`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val existing = products.create(product(ProductId.from(uuid(191)), "Original"))
            val arguments = inventoryArguments(existing.productId)
            val viewModel = createViewModel(arguments)
            settleSearch()
            viewModel.onAction(Action.ProductNameChanged("Pendiente"))
            runCurrent()
            val oldSnapshot = recreatedArguments(arguments)
            config.enterDemoMode(BusinessId.from(uuid(192)))
            settleSearch()
            assertNull(viewModel.uiState.value.form)
            viewModel.effects.test { assertEquals(CatalogsContract.Effect.Back, awaitItem()) }
            val recreated = createViewModel(oldSnapshot)
            settleSearch()
            assertNull(recreated.uiState.value.form)
            assertEquals(Failure.NOT_FOUND, recreated.uiState.value.inventoryEntryFailure)
            recreated.onAction(Action.SaveForm)
            runCurrent()
            assertEquals(existing, products.findById(existing.productId))
            assertTrue(inventory.stockWrites.isEmpty())
            assertTrue(registrations.isEmpty())
        }

    @Test
    fun `inventory SKU and barcode edits survive recreation and save on the same original CAS`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val existing = products.create(product(ProductId.from(uuid(193)), "Sin código", "OLD-SKU"))
            val arguments = inventoryArguments(existing.productId)
            val viewModel = createViewModel(arguments)
            settleSearch()
            viewModel.onAction(Action.ProductSkuChanged("NEW-SKU"))
            viewModel.onAction(Action.ProductBarcodeChanged("000193"))
            runCurrent()
            val recreated = createViewModel(recreatedArguments(arguments))
            settleSearch()
            val restored = recreated.uiState.value.form as Form.ProductForm
            assertEquals("NEW-SKU", restored.sku)
            assertEquals("000193", restored.barcode)
            assertEquals(existing, restored.original)
            assertEquals(existing.version, restored.original?.version)
            recreated.onAction(Action.SaveForm)
            runCurrent()
            assertEquals(existing.copy(sku = "NEW-SKU", barcode = "000193", version = existing.version + 1), products.findById(existing.productId))
            assertTrue(inventory.stockWrites.isEmpty())
            assertTrue(registrations.isEmpty())
            assertEquals(100L, nextUuid)
            recreated.effects.test { assertEquals(CatalogsContract.Effect.Back, awaitItem()) }
        }

    @Test
    fun `oversized inventory identifiers stay invalid after bounded draft restoration`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val existing = products.create(product(ProductId.from(uuid(194)), "Original", "OLD-SKU"))
            // Recortar estos espacios convertiría un código pegado en ausencia de código.
            val oversizedBarcodes = listOf("9".repeat(10_000), " ".repeat(129) + "123")
            oversizedBarcodes.forEach { barcode ->
                val arguments = inventoryArguments(existing.productId)
                val viewModel = createViewModel(arguments)
                settleSearch()
                viewModel.onAction(Action.ProductSkuChanged("S".repeat(10_000)))
                viewModel.onAction(Action.ProductBarcodeChanged(barcode))
                runCurrent()
                val recreated = createViewModel(recreatedArguments(arguments))
                settleSearch()
                val form = recreated.uiState.value.form as Form.ProductForm
                assertTrue(form.isSkuInputTooLong)
                assertTrue(form.isBarcodeInputTooLong)
                assertTrue(form.sku.length <= 65)
                assertTrue(form.barcode.length <= 129)
                recreated.onAction(Action.SaveForm)
                runCurrent()
                assertEquals(Failure.INVALID_FIELDS, recreated.uiState.value.failure)
                assertEquals(existing, products.findById(existing.productId))
                recreated.onAction(Action.ProductSkuChanged("VALID-SKU"))
                runCurrent()
                recreated.onAction(Action.SaveForm)
                runCurrent()
                assertEquals(Failure.INVALID_FIELDS, recreated.uiState.value.failure)
                recreated.onAction(Action.ProductBarcodeChanged(""))
                runCurrent()
                assertFalse((recreated.uiState.value.form as Form.ProductForm).isBarcodeInputTooLong)
                recreated.onAction(Action.CloseForm)
                runCurrent()
            }
            assertTrue(inventory.stockWrites.isEmpty())
            assertTrue(registrations.isEmpty())
        }

    @Test
    fun `inventory editor preloads exact current quantity average cost and sale currency without writing`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val existing = products.create(product(ProductId.from(uuid(200)), "Aceite").copy(
                salePrice = Money.fromMajor("8.90", CurrencyCode.of("USD")),
            ))
            val position = editPosition(quantity = "12.5", cost = "0.123456789012345678901234567890123456", version = 7)
            editingPositions[existing.productId] = listOf(position)
            val viewModel = createViewModel(inventoryArguments(existing.productId))
            settleSearch()
            val form = viewModel.uiState.value.form as Form.ProductForm
            assertEquals("12.5", form.quantity)
            assertEquals(position.averageUnitCost?.toPlainString(), form.purchasePrice)
            assertEquals("8.90", form.salePrice)
            assertEquals(CurrencyCode.of("USD"), form.saleCurrency)
            assertEquals(position.currency, form.inventoryCurrency)
            assertEquals(7L, form.inventorySnapshot?.positions?.single()?.balanceVersion)
            assertTrue(form.hasValidProductFields(CurrencyCode.of("USD")))
            assertTrue(editingWrites.isEmpty())
            assertTrue(inventory.stockWrites.isEmpty())
            viewModel.onAction(Action.CloseForm)
            runCurrent()
            assertTrue(editingWrites.isEmpty())
            assertEquals(existing, products.findById(existing.productId))
            assertEquals(position, editingPositions[existing.productId]?.single())
        }

    @Test
    fun `historical cost precision survives quantity edits but new overprecise cost is rejected`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val existing = products.create(product(ProductId.from(uuid(220)), "Promedio histórico"))
            val position = editPosition(quantity = "10", cost = "0.123456789012345678901234567890123456", version = 9)
            editingPositions[existing.productId] = listOf(position)
            val viewModel = createViewModel(inventoryArguments(existing.productId))
            settleSearch()
            viewModel.onAction(Action.ProductQuantityChanged("11"))
            runCurrent()
            val originalCostForm = viewModel.uiState.value.form as Form.ProductForm
            assertTrue(originalCostForm.hasValidProductFields(CurrencyCode.of("PEN")))
            assertEquals(position.averageUnitCost, originalCostForm.inventoryStockEditsOrNull()?.single()?.averageUnitCost)
            viewModel.onAction(Action.ProductPurchasePriceChanged("0.9876543210987654321"))
            runCurrent()
            assertFalse((viewModel.uiState.value.form as Form.ProductForm).hasValidProductFields(CurrencyCode.of("PEN")))
            viewModel.onAction(Action.SaveForm)
            runCurrent()
            assertTrue(editingWrites.isEmpty())
            assertEquals(Failure.INVALID_FIELDS, viewModel.uiState.value.failure)
            viewModel.onAction(Action.CloseForm)
            runCurrent()
        }

    @Test
    fun `inventory editor submits metadata final quantity and current unit cost in one atomic command`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val existing = products.create(product(ProductId.from(uuid(201)), "Arroz", "A201"))
            val position = editPosition(quantity = "10", cost = "2.50", version = 8)
            editingPositions[existing.productId] = listOf(position)
            val viewModel = createViewModel(inventoryArguments(existing.productId))
            settleSearch()
            viewModel.onAction(Action.ProductNameChanged("Arroz extra"))
            viewModel.onAction(Action.ProductQuantityChanged("6,5"))
            viewModel.onAction(Action.ProductPurchasePriceChanged("3,25"))
            viewModel.onAction(Action.ProductPriceChanged("4,50"))
            runCurrent()
            viewModel.onAction(Action.SaveForm)
            viewModel.onAction(Action.SaveForm)
            runCurrent()
            val write = editingWrites.single()
            assertEquals(existing, write.expected.product)
            assertEquals(position, write.expected.positions.single())
            assertEquals("Arroz extra", write.candidate.name)
            assertEquals(existing.productId, write.candidate.productId)
            assertEquals(existing.version, write.candidate.version)
            assertEquals(Money.fromMajor("4.50", CurrencyCode.of("PEN")), write.candidate.salePrice)
            assertEquals(listOf(ProductStockEdit(locationId, BigDecimal("6.5"), BigDecimal("3.25"))), write.stockEdits)
            assertEquals(BigDecimal("6.5"), editingPositions[existing.productId]?.single()?.quantityOnHand)
            assertEquals(BigDecimal("3.25"), editingPositions[existing.productId]?.single()?.averageUnitCost)
            assertTrue(inventory.stockWrites.isEmpty())
            assertTrue(registrations.isEmpty())
            viewModel.effects.test { assertEquals(CatalogsContract.Effect.Back, awaitItem()); expectNoEvents() }
        }

    @Test
    fun `multiple inventory positions require selection and retain each draft and balance CAS on recreation`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val existing = products.create(product(ProductId.from(uuid(202)), "Dos almacenes"))
            val secondId = LocationId.from(uuid(203))
            val positions = listOf(editPosition(quantity = "10", cost = "2", version = 11),
                editPosition(secondId, "Segundo", "20", "3", 12))
            editingPositions[existing.productId] = positions
            val arguments = inventoryArguments(existing.productId)
            val viewModel = createViewModel(arguments)
            settleSearch()
            assertNull((viewModel.uiState.value.form as Form.ProductForm).locationId)
            assertEquals("", (viewModel.uiState.value.form as Form.ProductForm).quantity)
            assertEquals(2, viewModel.uiState.value.inventoryBalances.size)
            viewModel.onAction(Action.ProductLocationSelected(locationId))
            runCurrent()
            assertEquals("10", (viewModel.uiState.value.form as Form.ProductForm).quantity)
            viewModel.onAction(Action.ProductQuantityChanged("7,"))
            runCurrent()
            viewModel.onAction(Action.ProductLocationSelected(secondId))
            runCurrent()
            viewModel.onAction(Action.ProductPurchasePriceChanged("4,50"))
            runCurrent()
            val expectedForm = viewModel.uiState.value.form
            val recreated = createViewModel(recreatedArguments(arguments))
            settleSearch()
            assertEquals(expectedForm, recreated.uiState.value.form)
            recreated.onAction(Action.ProductLocationSelected(locationId))
            runCurrent()
            assertEquals("7,", (recreated.uiState.value.form as Form.ProductForm).quantity)
            recreated.onAction(Action.SaveForm)
            runCurrent()
            val write = editingWrites.single()
            assertEquals(positions, write.expected.positions)
            assertEquals(listOf(ProductStockEdit(locationId, BigDecimal("7"), BigDecimal("2")),
                ProductStockEdit(secondId, BigDecimal("20"), BigDecimal("4.50"))), write.stockEdits)
            assertTrue(inventory.stockWrites.isEmpty())
        }

    @Test
    fun `metadata save never replays unchanged stock after concurrent balance update`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val existing = products.create(product(ProductId.from(uuid(204)), "Original"))
            val originalPosition = editPosition(quantity = "10", cost = "0.123456789012345678901234567890123456", version = 21)
            editingPositions[existing.productId] = listOf(originalPosition)
            val arguments = inventoryArguments(existing.productId)
            val viewModel = createViewModel(arguments)
            settleSearch()
            viewModel.onAction(Action.ProductNameChanged("Nuevo nombre"))
            runCurrent()
            val concurrent = originalPosition.copy(quantityOnHand = BigDecimal("8"), balanceVersion = 22)
            editingPositions[existing.productId] = listOf(concurrent)
            val recreated = createViewModel(recreatedArguments(arguments))
            settleSearch()
            assertEquals("10", (recreated.uiState.value.form as Form.ProductForm).quantity)
            assertEquals(21L, (recreated.uiState.value.form as Form.ProductForm).inventorySnapshot?.positions?.single()?.balanceVersion)
            recreated.onAction(Action.SaveForm)
            runCurrent()
            assertTrue(editingWrites.single().stockEdits.isEmpty())
            assertEquals(concurrent, editingPositions[existing.productId]?.single())
            assertEquals("Nuevo nombre", products.findById(existing.productId)?.name)
            assertTrue(inventory.stockWrites.isEmpty())
        }

    @Test
    fun `inventory changed balance CAS stays stale after process recreation and rolls back metadata`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val existing = products.create(product(ProductId.from(uuid(205)), "Original"))
            val originalPosition = editPosition(quantity = "10", cost = "2", version = 31)
            editingPositions[existing.productId] = listOf(originalPosition)
            val arguments = inventoryArguments(existing.productId)
            val viewModel = createViewModel(arguments)
            settleSearch()
            viewModel.onAction(Action.ProductNameChanged("Pendiente"))
            viewModel.onAction(Action.ProductQuantityChanged("6"))
            viewModel.onAction(Action.ProductPurchasePriceChanged("4"))
            runCurrent()
            val concurrent = originalPosition.copy(quantityOnHand = BigDecimal("8"), balanceVersion = 32)
            editingPositions[existing.productId] = listOf(concurrent)
            val recreated = createViewModel(recreatedArguments(arguments))
            settleSearch()
            recreated.onAction(Action.SaveForm)
            runCurrent()
            assertEquals(Failure.STALE, recreated.uiState.value.failure)
            assertEquals(31L, editingWrites.single().expected.positions.single().balanceVersion)
            assertEquals(existing, products.findById(existing.productId))
            assertEquals(concurrent, editingPositions[existing.productId]?.single())
            recreated.effects.test { expectNoEvents() }
        }

    @Test
    fun `inventory no-op preserves product and balance versions and creates no stock command`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val existing = products.create(product(ProductId.from(uuid(206)), "Original"))
            val position = editPosition(quantity = "10.00", cost = "7.500000000000", version = 41)
            editingPositions[existing.productId] = listOf(position)
            val viewModel = createViewModel(inventoryArguments(existing.productId))
            settleSearch()
            val form = viewModel.uiState.value.form as Form.ProductForm
            assertEquals("7.5", form.purchasePrice)
            assertEquals("7.5", viewModel.uiState.value.inventoryBalances.single().unitCost)
            assertTrue(requireNotNull(form.inventoryStockEditsOrNull()).isEmpty())
            val legacyDraft = form.copy(inventoryBalanceDrafts = form.inventoryBalanceDrafts.map {
                it.copy(purchasePrice = requireNotNull(position.averageUnitCost).toPlainString())
            })
            assertTrue(requireNotNull(legacyDraft.inventoryStockEditsOrNull()).isEmpty())
            viewModel.onAction(Action.ProductQuantityChanged("10"))
            viewModel.onAction(Action.ProductPurchasePriceChanged("7,5"))
            runCurrent()
            viewModel.onAction(Action.SaveForm)
            runCurrent()
            assertTrue(editingWrites.single().stockEdits.isEmpty())
            assertEquals(existing, products.findById(existing.productId))
            assertEquals(position, editingPositions[existing.productId]?.single())
            assertTrue(inventory.stockWrites.isEmpty())
            viewModel.effects.test { assertEquals(CatalogsContract.Effect.Back, awaitItem()) }
        }

    @Test
    fun `balance read error blocks saving and retries without inventing unknown quantity or cost`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val existing = products.create(product(ProductId.from(uuid(207)), "Sin saldo"))
            failEditingLoad = true
            val viewModel = createViewModel(inventoryArguments(existing.productId))
            settleSearch()
            assertEquals(Failure.LOAD_FAILED, viewModel.uiState.value.inventoryBalanceFailure)
            assertNull(viewModel.uiState.value.form)
            viewModel.onAction(Action.SaveForm)
            runCurrent()
            assertTrue(editingWrites.isEmpty())
            failEditingLoad = false
            viewModel.onAction(Action.Retry)
            runCurrent()
            val form = viewModel.uiState.value.form as Form.ProductForm
            assertFalse(viewModel.uiState.value.isInventoryBalanceLoading)
            assertNull(viewModel.uiState.value.inventoryBalanceFailure)
            assertEquals("", form.quantity)
            assertEquals("", form.purchasePrice)
            assertNull(form.inventorySnapshot?.positions?.single()?.balanceVersion)
            viewModel.onAction(Action.ProductNameChanged("Nombre corregido"))
            runCurrent()
            viewModel.onAction(Action.SaveForm)
            runCurrent()
            assertTrue(editingWrites.single().stockEdits.isEmpty())
        }

    @Test
    fun `atomic inventory failure keeps all edited fields and shared inventory ignores stock events`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val existing = products.create(product(ProductId.from(uuid(208)), "Original"))
            val position = editPosition(quantity = "10", cost = "2", version = 51)
            editingPositions[existing.productId] = listOf(position)
            val viewModel = createViewModel(inventoryArguments(existing.productId))
            settleSearch()
            viewModel.onAction(Action.ProductNameChanged("Pendiente"))
            viewModel.onAction(Action.ProductQuantityChanged("5"))
            viewModel.onAction(Action.ProductPurchasePriceChanged("3"))
            runCurrent()
            failEditingSave = true
            viewModel.onAction(Action.SaveForm)
            runCurrent()
            assertEquals(Failure.SAVE_FAILED, viewModel.uiState.value.failure)
            assertEquals("5", (viewModel.uiState.value.form as Form.ProductForm).quantity)
            assertEquals("3", (viewModel.uiState.value.form as Form.ProductForm).purchasePrice)
            assertEquals(existing, products.findById(existing.productId))
            assertEquals(position, editingPositions[existing.productId]?.single())
            failEditingSave = false
            inventoryEditable = false
            val shared = createViewModel(inventoryArguments(existing.productId))
            settleSearch()
            assertFalse(shared.uiState.value.isInventoryStockEditable)
            shared.onAction(Action.ProductQuantityChanged("999"))
            shared.onAction(Action.ProductPurchasePriceChanged("999"))
            shared.onAction(Action.ProductNameChanged("Compartido"))
            runCurrent()
            assertEquals("10", (shared.uiState.value.form as Form.ProductForm).quantity)
            assertEquals("2", (shared.uiState.value.form as Form.ProductForm).purchasePrice)
            shared.onAction(Action.SaveForm)
            runCurrent()
            assertTrue(editingWrites.last().stockEdits.isEmpty())
            assertEquals(position, editingPositions[existing.productId]?.single())
        }

    @Test
    fun `unknown purchase cost requires explicit value when quantity increases and zero is a real value`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val existing = products.create(product(ProductId.from(uuid(209)), "Sin saldo"))
            val viewModel = createViewModel(inventoryArguments(existing.productId))
            settleSearch()
            viewModel.onAction(Action.ProductQuantityChanged("2"))
            runCurrent()
            viewModel.onAction(Action.SaveForm)
            runCurrent()
            assertEquals(Failure.INVALID_FIELDS, viewModel.uiState.value.failure)
            assertTrue(editingWrites.isEmpty())
            viewModel.onAction(Action.ProductPurchasePriceChanged("0"))
            runCurrent()
            viewModel.onAction(Action.SaveForm)
            runCurrent()
            val edit = editingWrites.single().stockEdits.single()
            assertEquals(BigDecimal("2"), edit.quantityOnHand)
            assertEquals(BigDecimal.ZERO, edit.averageUnitCost)
            assertNull(editingWrites.single().expected.positions.single().balanceVersion)
        }

    @Test
    fun `inventory price overflow cannot become a valid trimmed prefix after draft recreation`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val existing = products.create(product(ProductId.from(uuid(212)), "Original"))
            val invalidPrices = listOf("5" + " ".repeat(24) + "9", "9".repeat(26), "90071992547409.92")
            invalidPrices.forEach { price ->
                val arguments = inventoryArguments(existing.productId)
                val viewModel = createViewModel(arguments)
                settleSearch()
                viewModel.onAction(Action.ProductPriceChanged(price))
                runCurrent()
                val recreated = createViewModel(recreatedArguments(arguments))
                settleSearch()
                assertEquals(price.take(25), (recreated.uiState.value.form as Form.ProductForm).salePrice)
                recreated.onAction(Action.SaveForm)
                runCurrent()
                assertEquals(price, Failure.INVALID_FIELDS, recreated.uiState.value.failure)
                assertTrue(editingWrites.isEmpty())
                assertEquals(existing, products.findById(existing.productId))
                recreated.onAction(Action.CloseForm)
                runCurrent()
            }
            val corrected = createViewModel(inventoryArguments(existing.productId))
            settleSearch()
            corrected.onAction(Action.ProductPriceChanged(" 6,50 "))
            runCurrent()
            corrected.onAction(Action.SaveForm)
            runCurrent()
            assertEquals(Money.fromMajor("6.50", CurrencyCode.of("PEN")), products.findById(existing.productId)?.salePrice)
            assertTrue(editingWrites.single().stockEdits.isEmpty())
            assertTrue(inventory.stockWrites.isEmpty())
        }

    @Test
    fun `manual and scanned product prices share the raw input bound and keep comma decimal support`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            for (scanned in listOf(false, true)) {
                val viewModel = createViewModel(if (scanned) scannedArguments("000213") else SavedStateHandle())
                settleSearch()
                if (!scanned) {
                    viewModel.onAction(Action.AddSelected)
                    runCurrent()
                }
                viewModel.onAction(Action.ProductNameChanged(if (scanned) "Escaneado" else "Manual"))
                viewModel.onAction(Action.ProductQuantityChanged("2"))
                viewModel.onAction(Action.ProductPurchasePriceChanged("1,25"))
                runCurrent()
                for (price in listOf("5" + " ".repeat(24), "9".repeat(26), "90071992547409.92")) {
                    viewModel.onAction(Action.ProductPriceChanged(price))
                    runCurrent()
                    viewModel.onAction(Action.SaveForm)
                    runCurrent()
                    assertEquals(Failure.INVALID_FIELDS, viewModel.uiState.value.failure)
                    assertTrue(registrations.isEmpty())
                }
                viewModel.onAction(Action.ProductPriceChanged(" 6,50 "))
                runCurrent()
                viewModel.onAction(Action.SaveForm)
                runCurrent()
                val saved = if (scanned) registrations.single().product else products.search(businessId, "Manual").single()
                assertEquals(Money.fromMajor("6.50", CurrencyCode.of("PEN")), saved.salePrice)
                assertNull(viewModel.uiState.value.form)
            }
        }

    private fun editPosition(
        id: LocationId = locationId,
        name: String = "Principal",
        quantity: String,
        cost: String?,
        version: Long?,
    ) = ProductEditingPosition(id, name, CatalogStatus.ACTIVE, BigDecimal(quantity), cost?.let(::BigDecimal), CurrencyCode.of("PEN"), version)

    private fun inventoryArguments(productId: ProductId) =
        SavedStateHandle(mapOf(RouteArgumentKeys.EDIT_PRODUCT_ID to productId.value))

    @Test
    fun `manual form validation requires opening values and rejects barcode sku or purchase conversion`() {
        val valid = Form.ProductForm(
            isManualRegistration = true,
            title = "Producto manual",
            locationId = locationId,
            quantity = "2,5",
            purchasePrice = "0",
            salePrice = "6,50",
        )
        val currency = CurrencyCode.of("PEN")
        assertTrue(valid.hasValidProductFields(currency))
        listOf(
            valid.copy(title = ""), valid.copy(quantity = ""), valid.copy(purchasePrice = ""),
            valid.copy(salePrice = ""), valid.copy(locationId = null), valid.copy(barcode = "123456"),
            valid.copy(sku = "SKU-001"), valid.copy(purchaseUnitId = unitId), valid.copy(purchaseFactor = "2"),
        ).forEach { assertFalse(it.hasValidProductFields(currency)) }
    }

    @Test
    fun `manual registration opens directly with NIU and atomically saves all four fields once without identifiers`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val kilogram = units.create(unit(UnitId.from(uuid(970)), "KGM", "Kilogramo"))
            units.create(unit(UnitId.from(uuid(971)), "UND", "Unidad alternativa"))
            var catalogSubscriptions = 0
            val repository = object : ProductRepository by products {
                override fun observeSearch(businessId: BusinessId, search: CatalogSearch): Flow<CatalogPage<Product>> {
                    catalogSubscriptions += 1
                    return products.observeSearch(businessId, search)
                }
            }
            val arguments = manualArguments()
            val viewModel = createViewModel(arguments, repository)
            settleSearch()
            val form = viewModel.uiState.value.form as Form.ProductForm
            assertTrue(form.isManualRegistration)
            assertFalse(form.isSpecialRegistration)
            assertFalse(form.isScannedRegistration)
            assertEquals(unitId, form.unitId)
            assertEquals(locationId, form.locationId)
            assertEquals(0, catalogSubscriptions)
            assertTrue(viewModel.uiState.value.rows.isEmpty())
            viewModel.onAction(Action.ProductBarcodeChanged("7751234567890"))
            viewModel.onAction(Action.ProductSkuChanged("SKU-001"))
            viewModel.onAction(Action.ProductUnitSelected(kilogram.unitId))
            fillScannedProduct(viewModel)
            runCurrent()

            viewModel.onAction(Action.SaveForm)
            viewModel.onAction(Action.SaveForm)
            settleSearch()

            val write = registrations.single()
            assertEquals(form.registrationProductId, write.product.productId)
            assertEquals("Arroz extra", write.product.name)
            assertNull(write.product.barcode)
            assertNull(write.product.sku)
            assertEquals(unitId, write.product.unitId)
            assertEquals(locationId, write.product.locationId)
            assertEquals(BigDecimal("2.5"), write.quantity)
            assertEquals(UnitCost.of("4.25", CurrencyCode.of("PEN")), write.unitCost)
            assertEquals(Money.fromMajor("6.50", CurrencyCode.of("PEN")), write.product.salePrice)
            assertTrue(inventory.stockWrites.isEmpty())
            assertTrue(products.search(businessId, "").isEmpty())
            assertEquals(0, catalogSubscriptions)
            viewModel.effects.test { assertEquals(CatalogsContract.Effect.Back, awaitItem()); expectNoEvents() }
            val restored = createViewModel(recreatedArguments(arguments))
            settleSearch()
            assertNull(restored.uiState.value.form)
            assertEquals(1, registrations.size)
        }

    @Test
    fun `manual registration creates missing NIU only after validation and keeps identity through a failed save`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            units = FakeUnitRepository(clock, products)
            units.create(unit(UnitId.from(uuid(972)), "KGM", "Kilogramo"))
            val arguments = manualArguments()
            val viewModel = createViewModel(arguments)
            settleSearch()
            assertNull((viewModel.uiState.value.form as Form.ProductForm).unitId)
            assertNull(units.findByCode(businessId, "NIU"))
            viewModel.onAction(Action.SaveForm)
            runCurrent()
            assertNull(units.findByCode(businessId, "NIU"))
            assertTrue(registrations.isEmpty())

            fillScannedProduct(viewModel)
            viewModel.onAction(Action.ProductPurchasePriceChanged("0"))
            runCurrent()
            failRegistration = true
            viewModel.onAction(Action.SaveForm)
            runCurrent()
            val normal = requireNotNull(units.findByCode(businessId, "NIU"))
            assertEquals("Unidad", normal.name)
            assertEquals("und", normal.symbol)
            assertEquals(Failure.SAVE_FAILED, viewModel.uiState.value.failure)
            assertTrue((viewModel.uiState.value.form as Form.ProductForm).isManualRegistration)
            val restored = createViewModel(recreatedArguments(arguments))
            settleSearch()
            failRegistration = false
            restored.onAction(Action.SaveForm)
            runCurrent()

            assertEquals(2, registrations.size)
            assertEquals(registrations.first().product.productId, registrations.last().product.productId)
            assertTrue(registrations.all { it.product.unitId == normal.unitId && it.unitCost == UnitCost.of("0", CurrencyCode.of("PEN")) })
            assertNull(units.findByCode(businessId, "UND"))
            assertTrue(inventory.stockWrites.isEmpty())
            restored.effects.test { assertEquals(CatalogsContract.Effect.Back, awaitItem()); expectNoEvents() }
        }

    @Test
    fun `manual registration can reuse an existing UND alias but never restores archived normal units`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            units.archive(unitId)
            val alternate = units.create(unit(UnitId.from(uuid(973)), "UND", "Unidad alternativa"))
            val viewModel = createViewModel(manualArguments())
            settleSearch()
            assertEquals(alternate.unitId, (viewModel.uiState.value.form as Form.ProductForm).unitId)
            units.archive(alternate.unitId)
            fillScannedProduct(viewModel)
            runCurrent()

            viewModel.onAction(Action.SaveForm)
            runCurrent()

            assertEquals(Failure.INVALID_FIELDS, viewModel.uiState.value.failure)
            assertTrue(registrations.isEmpty())
            assertEquals(CatalogStatus.ARCHIVED, units.findById(unitId)?.status)
            assertEquals(CatalogStatus.ARCHIVED, units.findById(alternate.unitId)?.status)
        }

    @Test
    fun `manual registration requires name positive quantity purchase cost and positive sale price`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel(manualArguments())
            settleSearch()
            val invalidActions = listOf(
                Action.ProductNameChanged(" "),
                Action.ProductQuantityChanged(""),
                Action.ProductQuantityChanged("0"),
                Action.ProductQuantityChanged("-2"),
                Action.ProductPurchasePriceChanged(""),
                Action.ProductPurchasePriceChanged("-1"),
                Action.ProductPurchasePriceChanged("1,2.5"),
                Action.ProductPriceChanged(""),
                Action.ProductPriceChanged("0"),
                Action.ProductPriceChanged("6,501"),
            )
            invalidActions.forEach { invalid ->
                fillScannedProduct(viewModel)
                viewModel.onAction(invalid)
                runCurrent()
                viewModel.onAction(Action.SaveForm)
                runCurrent()
                assertEquals(invalid.toString(), Failure.INVALID_FIELDS, viewModel.uiState.value.failure)
                assertTrue((viewModel.uiState.value.form as Form.ProductForm).isManualRegistration)
            }
            assertTrue(registrations.isEmpty())
            assertTrue(inventory.stockWrites.isEmpty())
        }

    @Test
    fun `manual partial draft restores exactly and cancellation wins over a queued save`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val arguments = manualArguments()
            val viewModel = createViewModel(arguments)
            settleSearch()
            viewModel.onAction(Action.ProductNameChanged("Producto manual"))
            viewModel.onAction(Action.ProductQuantityChanged("3,"))
            viewModel.onAction(Action.ProductPurchasePriceChanged("1,234"))
            viewModel.onAction(Action.ProductPriceChanged("8,"))
            runCurrent()
            val before = viewModel.uiState.value.form as Form.ProductForm
            val restoredArguments = recreatedArguments(arguments)
            val restored = createViewModel(restoredArguments)
            settleSearch()
            assertEquals(before, restored.uiState.value.form)

            restored.onAction(Action.CloseForm)
            restored.onAction(Action.SaveForm)
            runCurrent()

            assertTrue(registrations.isEmpty())
            assertTrue(inventory.stockWrites.isEmpty())
            restored.effects.test { assertEquals(CatalogsContract.Effect.Back, awaitItem()); expectNoEvents() }
            val afterCancel = createViewModel(recreatedArguments(restoredArguments))
            settleSearch()
            assertNull(afterCancel.uiState.value.form)
            assertFalse(afterCancel.uiState.value.isManualEntryPending)
        }

    @Test
    fun `manual draft committed before process death never applies its opening stock again`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val arguments = manualArguments()
            val viewModel = createViewModel(arguments)
            settleSearch()
            fillScannedProduct(viewModel)
            runCurrent()
            val draft = viewModel.uiState.value.form as Form.ProductForm
            products.create(product(requireNotNull(draft.registrationProductId), "Ya registrado")
                .copy(unitId = unitId, barcode = null, sku = null))

            val restored = createViewModel(recreatedArguments(arguments))
            settleSearch()
            restored.onAction(Action.SaveForm)
            runCurrent()

            assertNull(restored.uiState.value.form)
            assertFalse(restored.uiState.value.isManualEntryPending)
            assertTrue(registrations.isEmpty())
            assertTrue(inventory.stockWrites.isEmpty())
            restored.effects.test { assertEquals(CatalogsContract.Effect.Back, awaitItem()); expectNoEvents() }
        }

    @Test
    fun `manual completion survives recreation before the pending back effect is consumed`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            for (save in listOf(true, false)) {
                val arguments = manualArguments()
                val viewModel = createViewModel(arguments)
                settleSearch()
                fillScannedProduct(viewModel)
                runCurrent()
                val writesBefore = registrations.size
                viewModel.onAction(if (save) Action.SaveForm else Action.CloseForm)
                runCurrent()
                assertNull(viewModel.uiState.value.form)
                val restored = createViewModel(recreatedArguments(arguments))
                settleSearch()

                restored.effects.test { assertEquals(CatalogsContract.Effect.Back, awaitItem()); expectNoEvents() }
                restored.onAction(Action.AddSelected)
                restored.onAction(Action.SaveForm)
                restored.onAction(Action.CloseForm)
                runCurrent()

                assertNull(restored.uiState.value.form)
                assertEquals(writesBefore + if (save) 1 else 0, registrations.size)
                assertTrue(inventory.stockWrites.isEmpty())
                restored.effects.test { expectNoEvents() }
            }
        }

    @Test
    fun `manual identity lookup failure stays in registration and cancellation consumes its saved route`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            var catalogSubscriptions = 0
            val repository = object : ProductRepository by products {
                override suspend fun findById(productId: ProductId): Product? = error("identity lookup unavailable")

                override fun observeSearch(businessId: BusinessId, search: CatalogSearch): Flow<CatalogPage<Product>> {
                    catalogSubscriptions += 1
                    return products.observeSearch(businessId, search)
                }
            }
            val arguments = manualArguments()
            val viewModel = createViewModel(arguments, repository)
            settleSearch()
            assertNull(viewModel.uiState.value.form)
            assertTrue(viewModel.uiState.value.isManualEntryPending)
            assertEquals(Failure.LOAD_FAILED, viewModel.uiState.value.manualEntryFailure)
            assertEquals(0, catalogSubscriptions)

            viewModel.onAction(Action.AddSelected)
            viewModel.onAction(Action.SaveForm)
            viewModel.onAction(Action.CloseForm)
            runCurrent()

            assertFalse(viewModel.uiState.value.isManualEntryPending)
            assertEquals(0, catalogSubscriptions)
            assertTrue(registrations.isEmpty())
            viewModel.effects.test { assertEquals(CatalogsContract.Effect.Back, awaitItem()); expectNoEvents() }
            val restored = createViewModel(recreatedArguments(arguments))
            settleSearch()
            assertNull(restored.uiState.value.form)
        }

    @Test
    fun `manual registration validates the warehouse before creating NIU`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            units = FakeUnitRepository(clock, products)
            val viewModel = createViewModel(manualArguments())
            settleSearch()
            fillScannedProduct(viewModel)
            runCurrent()
            locations.archive(locationId)

            viewModel.onAction(Action.SaveForm)
            runCurrent()

            assertEquals(Failure.INVALID_FIELDS, viewModel.uiState.value.failure)
            assertNull(units.findByCode(businessId, "NIU"))
            assertTrue(registrations.isEmpty())
        }

    @Test
    fun `manual draft cannot register into another business after a switch or process recreation`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val arguments = manualArguments()
            val viewModel = createViewModel(arguments)
            settleSearch()
            fillScannedProduct(viewModel)
            runCurrent()
            val oldProcess = recreatedArguments(arguments)

            config.completeOnboarding(BusinessId.from(uuid(974)), TaxRate(BigDecimal("18")), CostPolicy.NET)
            runCurrent()
            viewModel.onAction(Action.SaveForm)
            runCurrent()
            assertNull(viewModel.uiState.value.form)
            viewModel.effects.test { assertEquals(CatalogsContract.Effect.Back, awaitItem()); expectNoEvents() }
            val restored = createViewModel(oldProcess)
            settleSearch()

            assertNull(restored.uiState.value.form)
            assertTrue(registrations.isEmpty())
            assertTrue(inventory.stockWrites.isEmpty())
        }

    @Test
    fun `special registration reuses kilogram and atomically submits barcode free fractional opening stock once`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val kgId = UnitId.from(uuid(980))
            units.create(unit(kgId, "KGM", "Kilogramo"))
            val arguments = specialArguments()
            val viewModel = createViewModel(arguments)
            settleSearch()
            val form = viewModel.uiState.value.form as Form.ProductForm
            assertTrue(form.isSpecialRegistration)
            assertFalse(form.isScannedRegistration)
            assertFalse(form.isScannerOrigin)
            assertFalse(form.isEditing)
            assertEquals(kgId, form.unitId)
            viewModel.onAction(Action.ProductBarcodeChanged("012345"))
            viewModel.onAction(Action.ProductUnitSelected(unitId))
            fillScannedProduct(viewModel)
            runCurrent()
            viewModel.onAction(Action.SaveForm)
            viewModel.onAction(Action.SaveForm)
            runCurrent()
            val write = registrations.single()
            assertNull(write.product.barcode)
            assertEquals(kgId, write.product.unitId)
            assertEquals(form.registrationProductId, write.product.productId)
            assertEquals(locationId, write.product.locationId)
            assertEquals(BigDecimal("2.5"), write.quantity)
            assertEquals(UnitCost.of("4.25", CurrencyCode.of("PEN")), write.unitCost)
            assertEquals(BigDecimal("6.50"), write.product.salePrice?.toMajor())
            assertTrue(inventory.stockWrites.isEmpty())
            assertTrue(products.search(businessId, "").isEmpty())
            viewModel.effects.test { assertEquals(CatalogsContract.Effect.Back, awaitItem()); expectNoEvents() }
            val recreated = createViewModel(recreatedArguments(arguments))
            settleSearch()
            assertNull(recreated.uiState.value.form)
            assertEquals(1, registrations.size)
        }

    @Test
    fun `special registration creates missing kilogram only on valid save and retries with stable product identity`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel(specialArguments())
            settleSearch()
            assertNull(units.findByCode(businessId, "KGM"))
            viewModel.onAction(Action.SaveForm)
            runCurrent()
            assertNull(units.findByCode(businessId, "KGM"))
            fillScannedProduct(viewModel)
            viewModel.onAction(Action.ProductQuantityChanged("0"))
            runCurrent()
            viewModel.onAction(Action.SaveForm)
            runCurrent()
            assertNull(units.findByCode(businessId, "KGM"))
            assertTrue(registrations.isEmpty())
            viewModel.onAction(Action.ProductQuantityChanged("0,5"))
            viewModel.onAction(Action.ProductPurchasePriceChanged("0"))
            runCurrent()
            failRegistration = true
            viewModel.onAction(Action.SaveForm)
            runCurrent()
            val kg = requireNotNull(units.findByCode(businessId, "KGM"))
            assertEquals(Failure.SAVE_FAILED, viewModel.uiState.value.failure)
            failRegistration = false
            viewModel.onAction(Action.SaveForm)
            runCurrent()
            assertEquals(2, registrations.size)
            assertEquals(registrations[0].product.productId, registrations[1].product.productId)
            assertTrue(registrations.all { it.product.unitId == kg.unitId && it.quantity == BigDecimal("0.5") })
            assertTrue(inventory.stockWrites.isEmpty())
        }

    @Test
    fun `special registration uses active KG alias and never restores archived kilogram units implicitly`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val kgm = units.create(unit(UnitId.from(uuid(981)), "KGM", "Kilogramo"))
            units.archive(kgm.unitId)
            val kg = units.create(unit(UnitId.from(uuid(982)), "KG", "Kilo"))
            val viewModel = createViewModel(specialArguments())
            settleSearch()
            assertEquals(kg.unitId, (viewModel.uiState.value.form as Form.ProductForm).unitId)
            units.archive(kg.unitId)
            fillScannedProduct(viewModel)
            runCurrent()
            viewModel.onAction(Action.SaveForm)
            runCurrent()
            assertEquals(Failure.INVALID_FIELDS, viewModel.uiState.value.failure)
            assertTrue(registrations.isEmpty())
            assertEquals(CatalogStatus.ARCHIVED, units.findById(kgm.unitId)?.status)
            assertEquals(CatalogStatus.ARCHIVED, units.findById(kg.unitId)?.status)
        }

    @Test
    fun `special draft restores exact partial input and cancellation cannot reopen it`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val arguments = specialArguments()
            val viewModel = createViewModel(arguments)
            settleSearch()
            viewModel.onAction(Action.ProductNameChanged("Comida por kilo"))
            viewModel.onAction(Action.ProductQuantityChanged("0,"))
            viewModel.onAction(Action.ProductPurchasePriceChanged("1,234"))
            viewModel.onAction(Action.ProductPriceChanged("12,"))
            runCurrent()
            val form = viewModel.uiState.value.form as Form.ProductForm
            val restoredArguments = recreatedArguments(arguments)
            val restored = createViewModel(restoredArguments)
            settleSearch()
            assertEquals(form, restored.uiState.value.form)
            restored.onAction(Action.CloseForm)
            restored.onAction(Action.SaveForm)
            runCurrent()
            restored.effects.test { assertEquals(CatalogsContract.Effect.Back, awaitItem()); expectNoEvents() }
            val afterCancel = createViewModel(recreatedArguments(restoredArguments))
            settleSearch()
            assertNull(afterCancel.uiState.value.form)
            assertNull(units.findByCode(businessId, "KGM"))
            assertTrue(registrations.isEmpty())
        }

    @Test
    fun `special draft never moves opening stock into another business`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val arguments = specialArguments()
            val viewModel = createViewModel(arguments)
            settleSearch()
            fillScannedProduct(viewModel)
            runCurrent()
            val priorProcess = recreatedArguments(arguments)
            config.completeOnboarding(BusinessId.from(uuid(983)), TaxRate(BigDecimal("18")), CostPolicy.NET)
            runCurrent()
            assertNull(viewModel.uiState.value.form)
            viewModel.onAction(Action.SaveForm)
            runCurrent()
            viewModel.effects.test { assertEquals(CatalogsContract.Effect.Back, awaitItem()); expectNoEvents() }
            val restored = createViewModel(priorProcess)
            settleSearch()
            assertNull(restored.uiState.value.form)
            assertTrue(registrations.isEmpty())
            assertNull(units.findByCode(businessId, "KGM"))
        }

    @Test
    fun `special draft confirmed before process death never registers opening kilos again`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val kg = units.create(unit(UnitId.from(uuid(984)), "KGM", "Kilogramo"))
            val arguments = specialArguments()
            val viewModel = createViewModel(arguments)
            settleSearch()
            fillScannedProduct(viewModel)
            runCurrent()
            val draft = viewModel.uiState.value.form as Form.ProductForm
            products.create(product(requireNotNull(draft.registrationProductId), "Producto ya confirmado")
                .copy(unitId = kg.unitId, barcode = null))
            val restored = createViewModel(recreatedArguments(arguments))
            settleSearch()
            assertNull(restored.uiState.value.form)
            assertFalse(restored.uiState.value.isSpecialEntryPending)
            restored.effects.test { assertEquals(CatalogsContract.Effect.Back, awaitItem()); expectNoEvents() }
            restored.onAction(Action.SaveForm)
            runCurrent()
            assertTrue(registrations.isEmpty())
            assertTrue(inventory.stockWrites.isEmpty())
        }

    @Test
    fun `special registration validates warehouse before creating a missing unit`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel(specialArguments())
            settleSearch()
            fillScannedProduct(viewModel)
            runCurrent()
            locations.archive(locationId)
            viewModel.onAction(Action.SaveForm)
            runCurrent()
            assertEquals(Failure.INVALID_FIELDS, viewModel.uiState.value.failure)
            assertNull(units.findByCode(businessId, "KGM"))
            assertTrue(registrations.isEmpty())
        }

    private fun specialArguments() = SavedStateHandle(mapOf("specialProduct" to "true"))

    private fun manualArguments() = SavedStateHandle(mapOf("manualProduct" to "true"))

    private fun recreatedArguments(arguments: SavedStateHandle): SavedStateHandle =
        SavedStateHandle(arguments.keys().associateWith { key -> arguments.get<Any?>(key) })

    private fun scannedArguments(barcode: String = "0001234567895") =
        SavedStateHandle(mapOf(RouteArgumentKeys.PREFILL_BARCODE to barcode))

    private fun salesRegistrationArguments(requestBusinessId: BusinessId = businessId) = SavedStateHandle(
        mapOf(
            RouteArgumentKeys.PREFILL_BARCODE to "0001234567895",
            RouteArgumentKeys.REGISTRATION_REQUEST_ID to "sale-request-1",
            RouteArgumentKeys.REGISTRATION_BUSINESS_ID to requestBusinessId.value,
        ),
    )

    private fun fillScannedProduct(viewModel: CatalogsViewModel) {
        viewModel.onAction(Action.ProductNameChanged("Arroz extra"))
        viewModel.onAction(Action.ProductQuantityChanged("2,5"))
        viewModel.onAction(Action.ProductPurchasePriceChanged("4,25"))
        viewModel.onAction(Action.ProductPriceChanged("6,50"))
    }

    @Test
    fun `maps duplicate sku and ruc and requires explicit local checksum acceptance`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            products.create(product(ProductId.from(uuid(20)), "Existente", sku = "DUP-1"))
            suppliers.create(supplier(SupplierId.from(uuid(21)), "Con RUC", ruc = "20123456786"))
            val viewModel = createViewModel()
            settleSearch()

            viewModel.onAction(Action.AddSelected)
            runCurrent()
            viewModel.onAction(Action.ProductNameChanged("Duplicado"))
            viewModel.onAction(Action.ProductSkuChanged("dup-1"))
            viewModel.onAction(Action.ProductUnitSelected(unitId))
            runCurrent()
            viewModel.onAction(Action.SaveForm)
            runCurrent()
            assertEquals(Failure.DUPLICATE_SKU, viewModel.uiState.value.failure)

            viewModel.onAction(Action.CloseForm)
            viewModel.onAction(Action.SectionSelected(Section.SUPPLIERS))
            runCurrent()
            viewModel.onAction(Action.AddSelected)
            runCurrent()
            viewModel.onAction(Action.SupplierLegalNameChanged("RUC repetido"))
            viewModel.onAction(Action.SupplierRucChanged("20123456786"))
            runCurrent()
            viewModel.onAction(Action.SaveForm)
            runCurrent()
            assertEquals(Failure.DUPLICATE_RUC, viewModel.uiState.value.failure)

            viewModel.onAction(Action.SupplierRucChanged("12345678901"))
            runCurrent()
            viewModel.onAction(Action.SaveForm)
            runCurrent()
            assertTrue(viewModel.uiState.value.showRucChecksumWarning)
            assertNull(suppliers.findByRuc(businessId, "12345678901"))

            viewModel.onAction(Action.DismissRucChecksumWarning)
            runCurrent()
            assertFalse(viewModel.uiState.value.showRucChecksumWarning)
            assertNotNull(viewModel.uiState.value.form)

            viewModel.onAction(Action.SaveForm)
            runCurrent()
            viewModel.onAction(Action.AcceptRucChecksumAndSave)
            runCurrent()
            assertNotNull(suppliers.findByRuc(businessId, "12345678901"))
        }

    @Test
    fun `product detail exposes real unit stock average cost and aliases`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val productId = ProductId.from(uuid(30))
            val product = products.create(product(productId, "Aceite"))
            aliases.create(
                SupplierProductAlias(
                    aliasId = AliasId.from(uuid(31)),
                    businessId = businessId,
                    supplierId = supplierId,
                    productId = productId,
                    alias = "ACEITE PROV 1L",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            inventory.values[productId] = ProductInventorySummary(
                listOf(
                    ProductInventoryPosition(
                        locationId = locationId,
                        quantityOnHand = BigDecimal("8.5"),
                        averageUnitCost = InventoryCostAmount(BigDecimal("12.30"), CurrencyCode.of("PEN")),
                    ),
                ),
            )
            val viewModel = createViewModel()
            settleSearch()

            viewModel.onAction(Action.RowSelected(Row.ProductRow(product)))
            runCurrent()

            val detail = viewModel.uiState.value.detail as Detail.ProductDetail
            assertEquals("NIU", detail.catalogDetail?.unit?.code)
            assertEquals(BigDecimal("8.5"), detail.catalogDetail?.inventory?.totalQuantityOnHand)
            assertEquals("ACEITE PROV 1L", detail.catalogDetail?.aliases?.single()?.alias)
            assertEquals("Principal", detail.positions.single().locationName)
            assertEquals("12.30 PEN", detail.positions.single().averageCost)
        }

    @Test
    fun `product status actions cannot archive or restore products`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val active = products.create(product(ProductId.from(uuid(40)), "Activo"))
            val archived = products.create(product(ProductId.from(uuid(41)), "Histórico").copy(status = CatalogStatus.ARCHIVED))
            val viewModel = createViewModel()
            settleSearch()
            for (product in listOf(active, archived)) {
                viewModel.onAction(Action.RowSelected(Row.ProductRow(product)))
                runCurrent()
                viewModel.onAction(Action.RequestStatusChange)
                runCurrent()
                assertNull(viewModel.uiState.value.pendingStatusChange)
                viewModel.onAction(Action.ConfirmStatusChange)
                runCurrent()
                assertEquals(product, products.findById(product.productId))
            }
        }

    @Test
    fun `products discard legacy status filters and exclude retired products from every page`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            repeat(55) { index ->
                products.create(product(ProductId.from(uuid(300L + index)), "Producto %03d".format(index)).copy(
                    status = if (index == 54) CatalogStatus.ARCHIVED else CatalogStatus.ACTIVE,
                ))
            }
            for (legacy in listOf(CatalogsContract.StatusFilter.ACTIVE, CatalogsContract.StatusFilter.ARCHIVED)) {
                val arguments = SavedStateHandle(mapOf("catalogs.section" to Section.PRODUCTS.name, "catalogs.status" to legacy.name))
                val viewModel = createViewModel(arguments)
                assertEquals(CatalogsContract.StatusFilter.ACTIVE, viewModel.uiState.value.statusFilter)
                settleSearch()
                assertEquals(54, viewModel.uiState.value.total)
                assertEquals(50, viewModel.uiState.value.rows.size)
                viewModel.onAction(Action.StatusFilterSelected(legacy))
                runCurrent()
                assertEquals(CatalogsContract.StatusFilter.ACTIVE, viewModel.uiState.value.statusFilter)
                viewModel.onAction(Action.LoadMore)
                runCurrent()
                assertEquals(54, viewModel.uiState.value.rows.size)
                assertTrue(viewModel.uiState.value.rows.all { it.status == CatalogStatus.ACTIVE })
                assertEquals(CatalogsContract.StatusFilter.ACTIVE.name, arguments.get<String>("catalogs.status"))
            }
        }

    @Test
    fun `supplier and unit status workflows stay available while entering products resets filter`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val baseSupplier = requireNotNull(suppliers.findById(supplierId))
            val viewModel = createViewModel()
            settleSearch()
            viewModel.onAction(Action.SectionSelected(Section.SUPPLIERS))
            runCurrent()
            viewModel.onAction(Action.RowSelected(Row.SupplierRow(baseSupplier)))
            runCurrent()
            viewModel.onAction(Action.RequestStatusChange)
            runCurrent()
            assertNotNull(viewModel.uiState.value.pendingStatusChange)
            viewModel.onAction(Action.ConfirmStatusChange)
            runCurrent()
            assertEquals(CatalogStatus.ARCHIVED, suppliers.findById(supplierId)?.status)
            viewModel.onAction(Action.StatusFilterSelected(CatalogsContract.StatusFilter.ARCHIVED))
            settleSearch()
            assertEquals(CatalogsContract.StatusFilter.ARCHIVED, viewModel.uiState.value.statusFilter)
            assertEquals(listOf(baseSupplier.supplierId.value), viewModel.uiState.value.rows.map { it.stableId })
            viewModel.onAction(Action.SectionSelected(Section.PRODUCTS))
            settleSearch()
            assertEquals(CatalogsContract.StatusFilter.ACTIVE, viewModel.uiState.value.statusFilter)
            viewModel.onAction(Action.SectionSelected(Section.UNITS))
            runCurrent()
            val originalUnit = requireNotNull(units.findById(unitId))
            viewModel.onAction(Action.RowSelected(Row.UnitRow(originalUnit)))
            runCurrent()
            viewModel.onAction(Action.RequestStatusChange)
            runCurrent()
            viewModel.onAction(Action.ConfirmStatusChange)
            runCurrent()
            assertEquals(CatalogStatus.ARCHIVED, units.findById(unitId)?.status)
            viewModel.onAction(Action.RowSelected(Row.UnitRow(requireNotNull(units.findById(unitId)))))
            runCurrent()
            viewModel.onAction(Action.RequestStatusChange)
            runCurrent()
            viewModel.onAction(Action.ConfirmStatusChange)
            runCurrent()
            assertEquals(CatalogStatus.ACTIVE, units.findById(unitId)?.status)
        }

    @Test
    fun `switching active business clears dialogs options and cannot mutate previous tenant`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val original = products.create(product(ProductId.from(uuid(50)), "Negocio original"))
            val otherBusinessId = BusinessId.from(uuid(51))
            val otherUnitId = UnitId.from(uuid(52))
            units.create(
                UnitOfMeasure(
                    unitId = otherUnitId,
                    businessId = otherBusinessId,
                    code = "ZZ",
                    name = "Otra unidad",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            products.create(
                Product(
                    productId = ProductId.from(uuid(53)),
                    businessId = otherBusinessId,
                    unitId = otherUnitId,
                    name = "Producto de otro negocio",
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            val viewModel = createViewModel()
            settleSearch()

            viewModel.onAction(Action.RowSelected(Row.SupplierRow(requireNotNull(suppliers.findById(supplierId)))))
            runCurrent()
            viewModel.onAction(Action.RequestStatusChange)
            runCurrent()
            assertNotNull(viewModel.uiState.value.pendingStatusChange)

            config.enterDemoMode(otherBusinessId)
            settleSearch()

            val state = viewModel.uiState.value
            assertNull(state.detail)
            assertNull(state.form)
            assertNull(state.pendingStatusChange)
            assertTrue(state.unitOptions.all { it.unitId == otherUnitId })
            assertEquals(listOf("Producto de otro negocio"), state.rows.map { it.title })

            viewModel.onAction(Action.ConfirmStatusChange)
            runCurrent()
            assertEquals(CatalogStatus.ACTIVE, products.findById(original.productId)?.status)
            assertEquals(CatalogStatus.ACTIVE, suppliers.findById(supplierId)?.status)
        }

    private fun createViewModel(
        arguments: SavedStateHandle = SavedStateHandle(),
        productRepository: ProductRepository = products,
        editingRepository: ProductEditingRepository? = null,
    ): CatalogsViewModel = CatalogsViewModel(
        savedStateHandle = arguments,
        observeAppConfiguration = ObserveAppConfigurationUseCase(config),
        products = productRepository,
        suppliers = suppliers,
        units = units,
        locations = locations,
        aliases = aliases,
        productInventory = inventory,
        uuidGenerator = UuidGenerator { uuid(nextUuid++) },
        clock = clock,
        dispatcherProvider = TestDispatcherProvider(mainDispatcherRule.dispatcher),
        productRegistration = object : ProductRegistrationRepository {
            override suspend fun register(product: Product, quantity: BigDecimal, unitCost: UnitCost): CatalogMutationResult<Product> {
                registrations += RegistrationWrite(product, quantity, unitCost)
                if (failRegistration) error("Fallo de almacenamiento simulado")
                return CatalogMutationResult.Saved(product)
            }
        },
        productEditing = editingRepository ?: fakeEditingRepository(productRepository),
    )

    private data class ProductEditWrite(
        val expected: ProductEditingSnapshot,
        val candidate: Product,
        val stockEdits: List<ProductStockEdit>,
    )

    private fun fakeEditingRepository(repository: ProductRepository): ProductEditingRepository = object : ProductEditingRepository {
        override suspend fun load(businessId: BusinessId, productId: ProductId, defaultCurrency: CurrencyCode): ProductEditingSnapshot? {
            if (failEditingLoad) error("No se pudieron leer los saldos")
            val product = repository.findById(productId)?.takeIf { it.businessId == businessId } ?: return null
            val positions = editingPositions[productId] ?: locations.search(businessId, CatalogSearch(limit = 200)).items.map { location ->
                val stock = inventory.values[productId]?.positions?.singleOrNull { it.locationId == location.locationId }
                ProductEditingPosition(
                    location.locationId, location.name, location.status, stock?.quantityOnHand ?: BigDecimal.ZERO,
                    stock?.averageUnitCost?.amount, stock?.averageUnitCost?.currency ?: defaultCurrency,
                    if (stock == null) null else 1L,
                )
            }
            return ProductEditingSnapshot(product, positions, inventoryEditable, defaultCurrency)
        }

        override suspend fun save(expected: ProductEditingSnapshot, candidate: Product, stockEdits: List<ProductStockEdit>): ProductEditingResult {
            editingWrites += ProductEditWrite(expected, candidate, stockEdits)
            if (failEditingSave) error("La transacción falló antes de confirmar")
            val current = load(candidate.businessId, candidate.productId, expected.defaultCurrency) ?: return ProductEditingResult.NotFound
            if (current.product.version != expected.product.version) return ProductEditingResult.Stale
            candidate.sku?.let { sku ->
                if (repository.findBySku(candidate.businessId, sku)?.productId?.let { it != candidate.productId } == true) {
                    return ProductEditingResult.Duplicate(CatalogDuplicateField.SKU)
                }
            }
            candidate.barcode?.let { barcode ->
                if (repository.findByBarcode(candidate.businessId, barcode)?.productId?.let { it != candidate.productId } == true) {
                    return ProductEditingResult.Duplicate(CatalogDuplicateField.BARCODE)
                }
            }
            if (stockEdits.isNotEmpty() && !current.inventoryEditable) return ProductEditingResult.Invalid(ProductEditingInvalidField.SHARED_INVENTORY)
            stockEdits.forEach { edit ->
                if (current.positions.singleOrNull { it.locationId == edit.locationId }?.balanceVersion !=
                    expected.positions.singleOrNull { it.locationId == edit.locationId }?.balanceVersion) return ProductEditingResult.Stale
            }
            val metadataChanged = candidate != current.product
            if (metadataChanged && !repository.update(candidate)) return ProductEditingResult.Stale
            if (stockEdits.isNotEmpty()) {
                editingPositions[candidate.productId] = current.positions.map { position ->
                    stockEdits.singleOrNull { it.locationId == position.locationId }?.let { edit ->
                        position.copy(quantityOnHand = edit.quantityOnHand,
                            averageUnitCost = edit.averageUnitCost ?: position.averageUnitCost,
                            balanceVersion = (position.balanceVersion ?: 0L) + 1L)
                    } ?: position
                }
            }
            val result = requireNotNull(load(candidate.businessId, candidate.productId, expected.defaultCurrency))
            return ProductEditingResult.Saved(result, metadataChanged || stockEdits.isNotEmpty())
        }
    }

    private data class RegistrationWrite(val product: Product, val quantity: BigDecimal, val unitCost: UnitCost)

    private suspend fun kotlinx.coroutines.test.TestScope.settleSearch() {
        runCurrent()
        advanceTimeBy(251)
        runCurrent()
    }

    private fun product(
        id: ProductId,
        name: String,
        sku: String? = null,
    ) = Product(
        productId = id,
        businessId = businessId,
        unitId = unitId,
        name = name,
        locationId = locationId,
        sku = sku,
        createdAt = now,
        updatedAt = now,
    )

    private fun supplier(id: SupplierId, name: String, ruc: String? = null) = Supplier(
        supplierId = id,
        businessId = businessId,
        legalName = name,
        ruc = ruc,
        createdAt = now,
        updatedAt = now,
    )

    private fun unit(id: UnitId, code: String, name: String) = UnitOfMeasure(
        unitId = id,
        businessId = businessId,
        code = code,
        name = name,
        createdAt = now,
        updatedAt = now,
    )

    private fun location(id: LocationId, name: String) = InventoryLocation(
        locationId = id,
        businessId = businessId,
        name = name,
        createdAt = now,
        updatedAt = now,
    )

    private class FakeProductInventoryRepository : ProductInventoryRepository {
        data class StockWrite(
            val productId: ProductId,
            val locationId: LocationId,
            val quantity: BigDecimal,
        )

        val values = mutableMapOf<ProductId, ProductInventorySummary>()
        val stockWrites = mutableListOf<StockWrite>()

        override suspend fun summaryForProduct(
            businessId: BusinessId,
            productId: ProductId,
        ): ProductInventorySummary = values[productId] ?: ProductInventorySummary(emptyList())

        override suspend fun setStock(
            businessId: BusinessId,
            productId: ProductId,
            locationId: LocationId,
            quantity: BigDecimal,
            currency: CurrencyCode,
        ) {
            stockWrites += StockWrite(productId, locationId, quantity)
            values[productId] =
                ProductInventorySummary(
                    listOf(
                        ProductInventoryPosition(
                            locationId = locationId,
                            quantityOnHand = quantity,
                            averageUnitCost = InventoryCostAmount(BigDecimal.ZERO, currency),
                        ),
                    ),
                )
        }
    }

    private companion object {
        fun uuid(value: Long): UUID = UUID(0L, value)
    }
}
