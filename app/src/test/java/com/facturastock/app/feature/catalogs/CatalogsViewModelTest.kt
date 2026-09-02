package com.facturastock.app.feature.catalogs

import androidx.lifecycle.SavedStateHandle
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.config.CostPolicy
import com.facturastock.app.domain.config.TaxRate
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.InventoryLocation
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
import com.facturastock.app.domain.repository.ProductInventoryRepository
import com.facturastock.app.domain.usecase.ObserveAppConfigurationUseCase
import com.facturastock.app.feature.catalogs.CatalogsContract.Action
import com.facturastock.app.feature.catalogs.CatalogsContract.Detail
import com.facturastock.app.feature.catalogs.CatalogsContract.Failure
import com.facturastock.app.feature.catalogs.CatalogsContract.Row
import com.facturastock.app.feature.catalogs.CatalogsContract.Section
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
                        averageUnitCost = UnitCost.of("12.30", CurrencyCode.of("PEN")),
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
    fun `archive and restore preserve the same historical product record`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val product = products.create(product(ProductId.from(uuid(40)), "Histórico"))
            val viewModel = createViewModel()
            settleSearch()

            viewModel.onAction(Action.RowSelected(Row.ProductRow(product)))
            runCurrent()
            viewModel.onAction(Action.RequestStatusChange)
            runCurrent()
            viewModel.onAction(Action.ConfirmStatusChange)
            runCurrent()
            val archived = requireNotNull(products.findById(product.productId))
            assertEquals(CatalogStatus.ARCHIVED, archived.status)
            assertEquals(product.productId, archived.productId)

            viewModel.onAction(Action.RowSelected(Row.ProductRow(archived)))
            runCurrent()
            viewModel.onAction(Action.RequestStatusChange)
            runCurrent()
            viewModel.onAction(Action.ConfirmStatusChange)
            runCurrent()
            assertEquals(CatalogStatus.ACTIVE, products.findById(product.productId)?.status)
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

            viewModel.onAction(Action.RowSelected(Row.ProductRow(original)))
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
        }

    private fun createViewModel(): CatalogsViewModel = CatalogsViewModel(
        savedStateHandle = SavedStateHandle(),
        observeAppConfiguration = ObserveAppConfigurationUseCase(config),
        products = products,
        suppliers = suppliers,
        units = units,
        locations = locations,
        aliases = aliases,
        productInventory = inventory,
        uuidGenerator = UuidGenerator { uuid(nextUuid++) },
        clock = clock,
        dispatcherProvider = TestDispatcherProvider(mainDispatcherRule.dispatcher),
    )

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
        val values = mutableMapOf<ProductId, ProductInventorySummary>()

        override suspend fun summaryForProduct(
            businessId: BusinessId,
            productId: ProductId,
        ): ProductInventorySummary = values[productId] ?: ProductInventorySummary(emptyList())
    }

    private companion object {
        fun uuid(value: Long): UUID = UUID(0L, value)
    }
}
