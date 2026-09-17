package com.facturastock.app.feature.matching

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.config.CostPolicy
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.InventoryLocation
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.InvoiceLine
import com.facturastock.app.domain.model.InvoiceMatchUnitChoice
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.ProductInventorySummary
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.ScannedItemMatch
import com.facturastock.app.domain.model.UnitCost
import com.facturastock.app.domain.model.UnitOfMeasure
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.repository.InventoryStockAddition
import com.facturastock.app.domain.repository.InvoiceMatchingCommitRepository
import com.facturastock.app.domain.repository.ProductInventoryRepository
import com.facturastock.app.domain.repository.ProductRepository
import com.facturastock.app.domain.usecase.ConfirmInvoiceMatchingResult
import com.facturastock.app.domain.usecase.ConfirmInvoiceMatchingUseCase
import com.facturastock.app.domain.usecase.DeleteDraftUseCase
import com.facturastock.app.domain.usecase.MatchScannedInvoiceLinesUseCase
import com.facturastock.app.domain.usecase.ProductMatchingUseCase
import com.facturastock.app.domain.usecase.SaveSupplierAliasUseCase
import com.facturastock.app.feature.common.RouteArgumentKeys
import com.facturastock.app.feature.matching.InvoiceMatchingContract.Action
import com.facturastock.app.testing.FakeAppConfigurationRepository
import com.facturastock.app.testing.FakeCloudBusinessBindingRepository
import com.facturastock.app.testing.FakeDraftFileStore
import com.facturastock.app.testing.FakeInventoryLocationRepository
import com.facturastock.app.testing.FakeInvoiceDraftRepository
import com.facturastock.app.testing.FakeProductRepository
import com.facturastock.app.testing.FakeSupplierProductAliasRepository
import com.facturastock.app.testing.FakeSupplierRepository
import com.facturastock.app.testing.FakeUnitRepository
import com.facturastock.app.testing.MainDispatcherRule
import com.facturastock.app.testing.TestDispatcherProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class InvoiceMatchingViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `comma decimals keep their value when a product is staged`() =
        runTest(mainDispatcherRule.dispatcher) {
            val fixture = Fixture()
            fixture.seed()
            val vm = fixture.viewModel()
            advanceUntilIdle()
            openForm(vm, price = "12,50", quantity = "1,5")
            vm.onAction(Action.SubmitCreateProduct)
            advanceUntilIdle()

            val item =
                vm.uiState.value.items
                    .single()
            assertEquals(BigDecimal("1.5"), item.quantity)
            assertEquals(1250L, item.matchedProduct!!.salePrice!!.minorUnits)
            assertNull(vm.uiState.value.createError)
            assertNull(vm.uiState.value.creatingItemIndex)
            // It is still a staged product until the complete invoice is confirmed.
            assertNull(fixture.products.findById(item.matchedProduct.productId))
        }

    @Test
    fun `malformed and nonpositive prices stay editable instead of silently dropping the price`() =
        runTest(mainDispatcherRule.dispatcher) {
            val fixture = Fixture()
            fixture.seed()
            val vm = fixture.viewModel()
            advanceUntilIdle()
            for (input in listOf("12,50.0", "-2", "0", "1e3", "90071992547409.92")) {
                openForm(vm, price = input, quantity = "1")
                vm.onAction(Action.SubmitCreateProduct)
                advanceUntilIdle()
                assertEquals(input, vm.uiState.value.createPrice)
                assertNotNull(vm.uiState.value.createError)
                assertNotNull(vm.uiState.value.creatingItemIndex)
                assertTrue(
                    vm.uiState.value.items
                        .isEmpty(),
                )
                assertFalse(vm.uiState.value.isSaving)
            }
        }

    @Test
    fun `malformed quantities cannot become a product with a missing quantity`() =
        runTest(mainDispatcherRule.dispatcher) {
            val fixture = Fixture()
            fixture.seed()
            val vm = fixture.viewModel()
            advanceUntilIdle()
            for (input in listOf("1,5.0", "-1", "0", "1e3")) {
                openForm(vm, price = "12.50", quantity = input)
                vm.onAction(Action.SubmitCreateProduct)
                advanceUntilIdle()
                assertEquals(input, vm.uiState.value.createQuantity)
                assertNotNull(vm.uiState.value.createError)
                assertTrue(
                    vm.uiState.value.items
                        .isEmpty(),
                )
            }
        }

    @Test
    fun `a double submit keeps one consistent form snapshot`() =
        runTest(mainDispatcherRule.dispatcher) {
            val fixture = Fixture()
            fixture.seed()
            val vm = fixture.viewModel()
            advanceUntilIdle()
            openForm(vm, price = "12.50", quantity = "1.5")
            vm.onAction(Action.SubmitCreateProduct)
            assertTrue(vm.uiState.value.isSaving)
            vm.onAction(Action.CreateNameChanged("Nombre durante el guardado"))
            vm.onAction(Action.CreatePriceChanged("99"))
            vm.onAction(Action.CreateQuantityChanged("88"))
            vm.onAction(Action.SubmitCreateProduct)
            vm.onAction(Action.CloseCreateDialog)
            advanceUntilIdle()

            val item =
                vm.uiState.value.items
                    .single()
            assertEquals("Producto nuevo", item.matchedProduct!!.name)
            assertEquals(1250L, item.matchedProduct.salePrice!!.minorUnits)
            assertEquals(BigDecimal("1.5"), item.quantity)
            assertEquals(2, fixture.generatedIds)
            assertFalse(vm.uiState.value.isSaving)
        }

    @Test
    fun `active units and warehouses are found after more than five archived entries`() =
        runTest(mainDispatcherRule.dispatcher) {
            val fixture = Fixture()
            fixture.seed(archivedCount = 6)
            val vm = fixture.viewModel()
            advanceUntilIdle()
            openForm(vm, price = "12.50", quantity = "1")
            vm.onAction(Action.SubmitCreateProduct)
            advanceUntilIdle()

            val product =
                vm.uiState.value.items
                    .single()
                    .matchedProduct!!
            assertEquals(UNIT_ID, product.unitId)
            assertEquals(LOCATION_ID, product.locationId)
            assertNull(vm.uiState.value.createError)
        }

    @Test
    fun `warehouse double submit applies the original lines only once`() =
        runTest(mainDispatcherRule.dispatcher) {
            val fixture = Fixture()
            fixture.seed()
            val vm = fixture.viewModel()
            advanceUntilIdle()
            openForm(vm, price = "12,50", quantity = "1,5")
            vm.onAction(Action.SubmitCreateProduct)
            advanceUntilIdle()

            reviewLine(vm, 0, "1,5", "2,50")
            vm.onAction(Action.SaveToWarehouse)
            assertTrue(vm.uiState.value.isSaving)
            vm.onAction(Action.OpenCreateDialog(0))
            vm.onAction(Action.CreateQuantityChanged("99"))
            vm.onAction(Action.UnlinkItem(0))
            vm.onAction(Action.SaveToWarehouse)
            advanceUntilIdle()

            assertEquals(
                BigDecimal("1.5"),
                fixture.stockBatches
                    .single()
                    .single()
                    .quantityToAdd,
            )
            assertFalse(vm.uiState.value.isSaving)
            assertNull(vm.uiState.value.creatingItemIndex)
            assertNull(vm.uiState.value.saveFailure)
        }

    @Test
    fun `mixed complete and unknown quantities cannot commit or silently discard a line`() =
        runTest(mainDispatcherRule.dispatcher) {
            val fixture = Fixture()
            fixture.seed()
            val vm = fixture.viewModel()
            advanceUntilIdle()
            openForm(vm, "12", "2")
            vm.onAction(Action.SubmitCreateProduct)
            advanceUntilIdle()
            reviewLine(vm, 0, "2", "3")
            openForm(vm, "9", "")
            vm.onAction(Action.SubmitCreateProduct)
            advanceUntilIdle()

            assertEquals(1, vm.uiState.value.readyCount)
            assertFalse(vm.uiState.value.canSaveToWarehouse)
            vm.onAction(Action.SaveToWarehouse)
            advanceUntilIdle()
            assertTrue(fixture.stockBatches.isEmpty())
            assertEquals(2, vm.uiState.value.items.size)
        }

    @Test
    fun `existing OCR product quantity and cost can be corrected without creating a duplicate`() =
        runTest(mainDispatcherRule.dispatcher) {
            val fixture = Fixture()
            fixture.seed()
            val rice = product(77, "Arroz")
            fixture.products.create(rice)
            fixture.addSourceLine("Arroz")
            val vm = fixture.viewModel()
            advanceUntilIdle()
            reviewLine(vm, 0, "1,5", "2,25")
            val item =
                vm.uiState.value.items
                    .single()
            assertEquals(rice.productId, item.matchedProduct?.productId)
            assertEquals(BigDecimal("1.5"), item.quantity)
            assertEquals(BigDecimal("2.25"), item.unitCost)
            assertTrue(vm.uiState.value.canSaveToWarehouse)
            assertEquals(0, fixture.generatedIds)
        }

    @Test
    fun `a fresh view model restores staged identity decisions and an unfinished form from bounded saved state`() =
        runTest(mainDispatcherRule.dispatcher) {
            val fixture = Fixture()
            fixture.seed()
            val handle = SavedStateHandle(mapOf(RouteArgumentKeys.DRAFT_ID to DRAFT_ID.value))
            val vm = fixture.viewModel(handle)
            advanceUntilIdle()
            openForm(vm, "12,50", "2")
            vm.onAction(Action.SubmitCreateProduct)
            advanceUntilIdle()
            reviewLine(vm, 0, "3", "4,25")
            val id =
                vm.uiState.value.items
                    .single()
                    .matchedProduct!!
                    .productId
            val manualLineId =
                vm.uiState.value.items
                    .single()
                    .manualLineId
            openForm(vm, "12,", "1,")
            vm.onAction(Action.CreateNameChanged("Otro producto pendiente"))
            val bytes = requireNotNull(handle.get<ByteArray>(MatchingSavedDraft.KEY)).copyOf()
            assertTrue(bytes.size < MatchingSavedDraft.MAX_BYTES)

            val restored =
                fixture.viewModel(
                    SavedStateHandle(
                        mapOf(
                            RouteArgumentKeys.DRAFT_ID to DRAFT_ID.value,
                            MatchingSavedDraft.KEY to bytes,
                        ),
                    ),
                )
            advanceUntilIdle()
            val state = restored.uiState.value
            assertEquals(
                id,
                state.items
                    .single()
                    .matchedProduct
                    ?.productId,
            )
            assertEquals(manualLineId, state.items.single().manualLineId)
            assertEquals(BigDecimal("3"), state.items.single().quantity)
            assertEquals(BigDecimal("4.25"), state.items.single().unitCost)
            assertEquals(InvoiceMatchUnitChoice.INVENTORY, state.items.single().unitChoice)
            assertEquals("Otro producto pendiente", state.createName)
            assertEquals("12,", state.createPrice)
            assertEquals("1,", state.createQuantity)
            assertEquals(1, state.creatingItemIndex)
            assertNull(fixture.products.findById(id))
            assertEquals(2, fixture.generatedIds)
        }

    @Test
    fun `recreation after a completed commit recovers its receipt without importing again`() =
        runTest(mainDispatcherRule.dispatcher) {
            val fixture = Fixture()
            fixture.seed()
            fixture.appliedCount = 2
            val vm = fixture.viewModel()
            vm.effects.test {
                advanceUntilIdle()
                assertEquals(InvoiceMatchingContract.Effect.MatchingConfirmed(2), awaitItem())
                assertTrue(fixture.stockBatches.isEmpty())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `restoration revalidates existing products instead of reviving an archived catalog row`() =
        runTest(mainDispatcherRule.dispatcher) {
            val fixture = Fixture()
            fixture.seed()
            val rice = product(78, "Arroz")
            fixture.products.create(rice)
            fixture.addSourceLine("Producto leído")
            val handle = SavedStateHandle(mapOf(RouteArgumentKeys.DRAFT_ID to DRAFT_ID.value))
            val vm = fixture.viewModel(handle)
            advanceUntilIdle()
            vm.onAction(Action.ProductSelected(0, rice))
            advanceUntilIdle()
            reviewLine(vm, 0, "2", "3")
            fixture.products.archive(rice.productId)
            val restored =
                fixture.viewModel(
                    SavedStateHandle(
                        mapOf(
                            RouteArgumentKeys.DRAFT_ID to DRAFT_ID.value,
                            MatchingSavedDraft.KEY to requireNotNull(handle.get<ByteArray>(MatchingSavedDraft.KEY)).copyOf(),
                        ),
                    ),
                )
            advanceUntilIdle()
            assertNull(
                restored.uiState.value.items
                    .single()
                    .matchedProduct,
            )
            assertEquals(
                BigDecimal("2"),
                restored.uiState.value.items
                    .single()
                    .quantity,
            )
            assertFalse(restored.uiState.value.canSaveToWarehouse)
        }

    private fun reviewLine(
        vm: InvoiceMatchingViewModel,
        index: Int,
        quantity: String,
        cost: String,
    ) {
        vm.onAction(Action.OpenLineEditor(index))
        vm.onAction(Action.EditQuantityChanged(quantity))
        vm.onAction(Action.EditCostChanged(cost))
        vm.onAction(Action.EditCurrencyChanged("PEN"))
        vm.onAction(Action.EditUnitChoiceChanged(InvoiceMatchUnitChoice.INVENTORY))
        vm.onAction(Action.SubmitLineEdit)
    }

    @Test
    fun `a failed product search can retry without recreating the view model`() =
        runTest(mainDispatcherRule.dispatcher) {
            val fixture = Fixture()
            fixture.seed()
            fixture.products.create(product(10, "Arroz"))
            val vm = fixture.viewModel()
            advanceUntilIdle()
            openForm(vm, price = "", quantity = "1")
            vm.onAction(Action.SubmitCreateProduct)
            advanceUntilIdle()

            fixture.searchFailure = true
            vm.onAction(Action.OpenLinkingDialog(0))
            assertTrue(vm.uiState.value.isSearching)
            advanceUntilIdle()
            assertTrue(vm.uiState.value.searchFailed)
            assertFalse(vm.uiState.value.isSearching)
            assertTrue(
                vm.uiState.value.searchResults
                    .isEmpty(),
            )

            fixture.searchFailure = false
            vm.onAction(Action.RetrySearch)
            advanceUntilIdle()
            assertFalse(vm.uiState.value.searchFailed)
            assertEquals(
                listOf("Arroz"),
                vm.uiState.value.searchResults
                    .map { it.name },
            )
            assertEquals(2, fixture.searchCalls)
        }

    @Test
    fun `typing after a failed name search starts a fresh successful search`() =
        runTest(mainDispatcherRule.dispatcher) {
            val fixture = Fixture()
            fixture.seed()
            fixture.products.create(product(10, "Arroz"))
            val vm = fixture.viewModel()
            advanceUntilIdle()
            openForm(vm, price = "", quantity = "1")
            vm.onAction(Action.SubmitCreateProduct)
            advanceUntilIdle()
            vm.onAction(Action.OpenLinkingDialog(0))
            advanceUntilIdle()

            fixture.searchFailure = true
            vm.onAction(Action.SearchQueryChanged("Aceite"))
            assertTrue(
                vm.uiState.value.searchResults
                    .isEmpty(),
            )
            advanceUntilIdle()
            assertTrue(vm.uiState.value.searchFailed)
            fixture.searchFailure = false
            vm.onAction(Action.SearchQueryChanged("Arr"))
            vm.onAction(Action.SearchQueryChanged("Arroz"))
            advanceUntilIdle()
            assertFalse(vm.uiState.value.searchFailed)
            assertFalse(vm.uiState.value.isSearching)
            assertEquals(
                listOf("Arroz"),
                vm.uiState.value.searchResults
                    .map { it.name },
            )
        }

    @Test
    fun `closing the dialog cancels the debounce without querying the catalog`() =
        runTest(mainDispatcherRule.dispatcher) {
            val fixture = Fixture()
            fixture.seed()
            val vm = fixture.viewModel()
            advanceUntilIdle()
            openForm(vm, price = "", quantity = "1")
            vm.onAction(Action.SubmitCreateProduct)
            advanceUntilIdle()

            vm.onAction(Action.OpenLinkingDialog(0))
            runCurrent()
            advanceTimeBy(100)
            vm.onAction(Action.CloseLinkingDialog)
            advanceUntilIdle()
            assertEquals(0, fixture.searchCalls)
            assertNull(vm.uiState.value.linkingItemIndex)
            assertFalse(vm.uiState.value.isSearching)
            assertFalse(vm.uiState.value.searchFailed)
        }

    @Test
    fun `late results of a closed dialog cannot overwrite a reopened search`() =
        runTest(mainDispatcherRule.dispatcher) {
            val fixture = Fixture()
            fixture.seed()
            fixture.products.create(product(10, "Arroz"))
            val vm = fixture.viewModel()
            advanceUntilIdle()
            openForm(vm, price = "", quantity = "1")
            vm.onAction(Action.SubmitCreateProduct)
            advanceUntilIdle()

            val oldResult = CompletableDeferred<List<Product>>()
            fixture.searchOverride = { withContext(NonCancellable) { oldResult.await() } }
            vm.onAction(Action.OpenLinkingDialog(0))
            advanceTimeBy(251)
            runCurrent()
            assertEquals(1, fixture.searchCalls)
            vm.onAction(Action.CloseLinkingDialog)
            fixture.searchOverride = null
            vm.onAction(Action.OpenLinkingDialog(0))
            advanceTimeBy(251)
            runCurrent()
            assertEquals(
                listOf("Arroz"),
                vm.uiState.value.searchResults
                    .map { it.name },
            )
            oldResult.complete(listOf(product(11, "Resultado obsoleto")))
            advanceUntilIdle()
            assertEquals(
                listOf("Arroz"),
                vm.uiState.value.searchResults
                    .map { it.name },
            )
            assertFalse(vm.uiState.value.searchFailed)
        }

    private fun openForm(
        vm: InvoiceMatchingViewModel,
        price: String,
        quantity: String,
    ) {
        vm.onAction(Action.OpenAddNewProductDialog)
        vm.onAction(Action.CreateNameChanged("Producto nuevo"))
        vm.onAction(Action.CreatePriceChanged(price))
        vm.onAction(Action.CreateQuantityChanged(quantity))
    }

    private inner class Fixture {
        private val clock = AppClock { NOW }
        private val configuration = FakeAppConfigurationRepository()
        private val drafts = FakeInvoiceDraftRepository(clock)
        val products = FakeProductRepository(clock)
        private val units = FakeUnitRepository(clock)
        private val locations = FakeInventoryLocationRepository(clock)
        private val aliases = FakeSupplierProductAliasRepository(clock)
        var generatedIds = 0
        var searchCalls = 0
        var searchFailure = false
        var appliedCount: Int? = null
        val stockBatches = mutableListOf<List<InventoryStockAddition>>()
        var searchOverride: (suspend () -> List<Product>)? = null
        private val ids = UuidGenerator { uuid(100 + generatedIds++) }
        private val searchingProducts =
            object : ProductRepository by products {
                override suspend fun searchActiveByName(
                    businessId: BusinessId,
                    query: String,
                    limit: Int,
                ): List<Product> {
                    searchCalls++
                    if (searchFailure) throw IllegalStateException("Storage unavailable")
                    return searchOverride?.invoke() ?: products.searchActiveByName(businessId, query, limit)
                }
            }
        private val matching = ProductMatchingUseCase(searchingProducts, aliases)
        private val inventory =
            object : ProductInventoryRepository {
                override suspend fun summaryForProduct(
                    businessId: BusinessId,
                    productId: ProductId,
                ) = ProductInventorySummary(emptyList())

                override suspend fun addStockBatch(
                    businessId: BusinessId,
                    currency: CurrencyCode,
                    items: List<InventoryStockAddition>,
                ): Int {
                    stockBatches += items
                    return items.size
                }
            }

        suspend fun seed(archivedCount: Int = 0) {
            configuration.completeOnboarding(BUSINESS_ID, AppConfiguration.DEFAULT_TAX_RATE, CostPolicy.NET)
            drafts.createDraft(InvoiceDraft(DRAFT_ID, BUSINESS_ID, createdAt = NOW, updatedAt = NOW))
            repeat(archivedCount) { index ->
                units.create(UnitOfMeasure(UnitId.from(uuid(200 + index)), BUSINESS_ID, "OLD$index", "A archivada $index", status = CatalogStatus.ARCHIVED, createdAt = NOW, updatedAt = NOW))
                locations.create(InventoryLocation(LocationId.from(uuid(300 + index)), BUSINESS_ID, "A archivado $index", CatalogStatus.ARCHIVED, NOW, NOW))
            }
            units.create(UnitOfMeasure(UNIT_ID, BUSINESS_ID, "NIU", "Unidad", createdAt = NOW, updatedAt = NOW))
            locations.create(InventoryLocation(LOCATION_ID, BUSINESS_ID, "Principal", createdAt = NOW, updatedAt = NOW))
        }

        suspend fun addSourceLine(description: String) {
            drafts.addLine(
                InvoiceLine(
                    LineId.from(uuid(90)),
                    DRAFT_ID,
                    BUSINESS_ID,
                    0,
                    description,
                    quantity = Quantity.of("7"),
                    unitRaw = "NIU",
                    unitCost = UnitCost.of("5", CurrencyCode.of("PEN")),
                    createdAt = NOW,
                    updatedAt = NOW,
                ),
            )
        }

        fun viewModel(handle: SavedStateHandle = SavedStateHandle(mapOf(RouteArgumentKeys.DRAFT_ID to DRAFT_ID.value))) =
            InvoiceMatchingViewModel(
                savedStateHandle = handle,
                appConfigurationRepository = configuration,
                productRepository = searchingProducts,
                unitRepository = units,
                locationRepository = locations,
                matchScannedInvoiceLinesUseCase = MatchScannedInvoiceLinesUseCase(configuration, drafts, matching, units),
                productMatchingUseCase = matching,
                confirmInvoiceMatchingUseCase =
                    ConfirmInvoiceMatchingUseCase(
                        configuration,
                        object : InvoiceMatchingCommitRepository {
                            override suspend fun findAppliedCount(
                                businessId: BusinessId,
                                draftId: DraftId,
                            ): Int? = appliedCount

                            override suspend fun confirm(
                                businessId: BusinessId,
                                currency: CurrencyCode,
                                draftId: DraftId,
                                items: List<ScannedItemMatch>,
                            ): ConfirmInvoiceMatchingResult {
                                val additions =
                                    items.map { item ->
                                        InventoryStockAddition(
                                            item.matchedProduct!!.productId,
                                            LOCATION_ID,
                                            requireNotNull(item.quantity),
                                            item.unitCost,
                                            "test:${item.lineIndex}",
                                        )
                                    }
                                inventory.addStockBatch(businessId, currency, additions)
                                return ConfirmInvoiceMatchingResult.Applied(items.size)
                            }
                        },
                    ),
                uuidGenerator = ids,
                appClock = clock,
                dispatcherProvider = TestDispatcherProvider(mainDispatcherRule.dispatcher),
            )
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-04T12:00:00Z")

        fun uuid(seed: Int): UUID = UUID(0L, seed.toLong())

        val BUSINESS_ID = BusinessId.from(uuid(1))
        val UNIT_ID = UnitId.from(uuid(2))
        val LOCATION_ID = LocationId.from(uuid(3))
        val DRAFT_ID = DraftId.from(uuid(4))

        fun product(
            seed: Int,
            name: String,
        ) = Product(ProductId.from(uuid(seed)), BUSINESS_ID, UNIT_ID, name, locationId = LOCATION_ID, createdAt = NOW, updatedAt = NOW)
    }
}
