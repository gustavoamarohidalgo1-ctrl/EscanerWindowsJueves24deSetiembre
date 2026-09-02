package com.facturastock.app.feature.linking

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.model.BarcodeValue
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.InvoiceLine
import com.facturastock.app.domain.model.InvoiceLinesEdit
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.PurchaseProductProvenance
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.Supplier
import com.facturastock.app.domain.model.StagedPurchaseProduct
import com.facturastock.app.domain.model.UnitOfMeasure
import com.facturastock.app.domain.model.WorkflowSnapshot
import com.facturastock.app.domain.model.WorkflowStage
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.SupplierId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.repository.ProductRepository
import com.facturastock.app.domain.usecase.CreateLinkedProductUseCase
import com.facturastock.app.domain.usecase.LoadInvoiceLinesReviewUseCase
import com.facturastock.app.domain.usecase.ProductMatchReason
import com.facturastock.app.domain.usecase.ProductMatchingUseCase
import com.facturastock.app.domain.usecase.RunDraftStageUseCase
import com.facturastock.app.domain.usecase.SaveInvoiceLinesEditUseCase
import com.facturastock.app.domain.usecase.SaveSupplierAliasUseCase
import com.facturastock.app.domain.usecase.UpdateProductSalePriceUseCase
import com.facturastock.app.feature.common.RouteArgumentKeys
import com.facturastock.app.feature.linking.ProductLinkingContract.Action
import com.facturastock.app.feature.linking.ProductLinkingContract.Effect
import com.facturastock.app.feature.linking.ProductLinkingContract.Failure
import com.facturastock.app.feature.linking.ProductLinkingContract.LinkStatus
import com.facturastock.app.feature.linking.ProductLinkingContract.SearchStatus
import com.facturastock.app.testing.ControlledDraftWorkflowRepository
import com.facturastock.app.testing.FakeInvoiceDraftRepository
import com.facturastock.app.testing.FakeInvoiceLinesReviewRepository
import com.facturastock.app.testing.FakeAppConfigurationRepository
import com.facturastock.app.testing.FakeParsedInvoiceRepository
import com.facturastock.app.testing.FakeProductRepository
import com.facturastock.app.testing.FakeSupplierProductAliasRepository
import com.facturastock.app.testing.FakeSupplierRepository
import com.facturastock.app.testing.FakeUnitRepository
import com.facturastock.app.testing.MainDispatcherRule
import com.facturastock.app.testing.TestDispatcherProvider
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
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
class ProductLinkingViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `el arranque vincula los exactos y propone candidatos en las ambiguas`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(
                lines = listOf(
                    line(seed = 1, position = 0, description = "Arroz Extra Costeño"),
                    line(seed = 2, position = 1, description = "Azúcar Rubia"),
                ),
                products = listOf(
                    product(seed = 10, name = "Arroz Extra Costeño"),
                    product(seed = 11, name = "Azúcar Rubia"),
                    product(seed = 12, name = "Azúcar Rubia"),
                ),
            )
            // La ruta abre la primera línea, pero tras auto-vincularla la UI debe enfocar la
            // primera decisión humana pendiente en lugar de obligar a recorrer enlaces resueltos.
            val viewModel = fixture.viewModel(initialLineId = lineId(1))
            viewModel.onAction(Action.Start)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            val autoLinked = state.lines.first { it.lineId == lineId(1) }
            assertEquals(LinkStatus.AUTO_LINKED, autoLinked.status)
            assertEquals("Arroz Extra Costeño", autoLinked.linkedProductName)
            assertEquals(ProductMatchReason.EXACT_NAME, autoLinked.linkReason)

            val persisted = fixture.reviews.find(DRAFT_ID)!!
                .activeLines.first { it.lineId == lineId(1) }
            assertEquals(productId(10), persisted.linkedProductId)
            assertEquals(unitId(60), persisted.linkedUnitId)
            assertEquals(900, persisted.linkConfidence)

            val ambiguous = state.lines.first { it.lineId == lineId(2) }
            assertEquals(LinkStatus.NEEDS_CHOICE, ambiguous.status)
            assertEquals(lineId(2), state.currentLineId)
            assertEquals(
                setOf(productId(11), productId(12)),
                state.candidates.map { it.product.productId }.toSet(),
            )
            // El auto-enlace nunca persiste alias: solo la confirmación humana lo hace.
            assertTrue(
                fixture.aliases.findByNormalizedAlias(BUSINESS_ID, "Arroz Extra Costeño").isEmpty(),
            )
        }

    @Test
    fun `confirmar un candidato persiste el enlace y guarda los alias del proveedor`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(
                lines = listOf(
                    line(seed = 1, position = 0, description = "AZUCAR RUBIA X KG", code = "COD-77"),
                ),
                products = listOf(product(seed = 10, name = "Azúcar Rubia")),
                withSupplier = true,
            )
            val viewModel = fixture.viewModel(initialLineId = lineId(1))
            viewModel.onAction(Action.Start)
            advanceUntilIdle()

            val before = viewModel.uiState.value
            assertEquals(LinkStatus.NEEDS_CHOICE, before.lines.single().status)
            val candidate = before.candidates.single()
            assertEquals(productId(10), candidate.product.productId)

            viewModel.onAction(Action.CandidateConfirmed(productId(10)))
            advanceUntilIdle()

            val state = viewModel.uiState.value
            val linked = state.lines.single()
            assertEquals(LinkStatus.CONFIRMED, linked.status)
            assertEquals("Azúcar Rubia", linked.linkedProductName)
            assertTrue(state.allResolved)

            val persisted = fixture.reviews.find(DRAFT_ID)!!.activeLines.single()
            assertEquals(productId(10), persisted.linkedProductId)
            assertEquals(unitId(60), persisted.linkedUnitId)
            assertEquals(candidate.confidencePermille, persisted.linkConfidence)
            assertEquals(setOf(lineId(1)), fixture.reviews.lastCatalogLinkLineIds)

            val descriptionAlias = fixture.aliases
                .findByNormalizedAlias(BUSINESS_ID, "AZUCAR RUBIA X KG")
                .single()
            assertEquals(productId(10), descriptionAlias.productId)
            assertEquals(supplierId(50), descriptionAlias.supplierId)
            val codeAlias = fixture.aliases
                .findByNormalizedAlias(BUSINESS_ID, "COD-77")
                .single()
            assertEquals(productId(10), codeAlias.productId)
        }

    @Test
    fun `una busqueda nueva invalida candidatos previos y un fallo exige reintento`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(
                lines = listOf(
                    line(seed = 1, position = 0, description = "AZUCAR RUBIA X KG"),
                ),
                products = listOf(
                    product(seed = 10, name = "Azúcar Rubia"),
                    product(seed = 11, name = "Leche Fresca"),
                ),
            )
            val viewModel = fixture.viewModel(initialLineId = lineId(1))
            viewModel.onAction(Action.Start)
            advanceUntilIdle()
            assertEquals(
                productId(10),
                viewModel.uiState.value.candidates.single().product.productId,
            )

            val searchGate = CompletableDeferred<Unit>()
            fixture.matchingProducts.manualSearchGate = searchGate
            fixture.matchingProducts.nextManualSearchFailure =
                StorageException(StorageError.Unavailable)

            viewModel.onAction(Action.SearchChanged("Leche Fresca"))
            // La invalidación también protege la ventana antes de que StateFlow publique el cambio.
            viewModel.onAction(Action.CandidateConfirmed(productId(10)))
            runCurrent()

            val searching = viewModel.uiState.value
            assertEquals("Leche Fresca", searching.searchQuery)
            assertEquals(SearchStatus.SEARCHING, searching.searchStatus)
            assertTrue(searching.candidates.isEmpty())
            assertFalse(searching.canSelectCandidate)

            // Una acción sintetizada durante la consulta tampoco puede usar el id anterior.
            viewModel.onAction(Action.CandidateConfirmed(productId(10)))
            runCurrent()
            assertNull(
                fixture.reviews.find(DRAFT_ID)?.activeLines?.single()?.linkedProductId,
            )

            searchGate.complete(Unit)
            advanceUntilIdle()

            val failed = viewModel.uiState.value
            assertEquals(SearchStatus.FAILED, failed.searchStatus)
            assertTrue(failed.candidates.isEmpty())
            viewModel.onAction(Action.CandidateConfirmed(productId(10)))
            runCurrent()
            assertNull(
                fixture.reviews.find(DRAFT_ID)?.activeLines?.single()?.linkedProductId,
            )

            fixture.matchingProducts.manualSearchGate = null
            viewModel.onAction(Action.RetrySearch)
            advanceUntilIdle()

            val recovered = viewModel.uiState.value
            assertEquals(SearchStatus.IDLE, recovered.searchStatus)
            assertEquals(
                productId(11),
                recovered.candidates.single().product.productId,
            )
            assertTrue(recovered.canSelectCandidate)
        }

    @Test
    fun `un candidato legacy sin precio exige CAS antes de vincular`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(
                lines = listOf(line(seed = 1, position = 0, description = "Azúcar Rubia")),
                products = listOf(product(seed = 10, name = "Azúcar Rubia", salePrice = null)),
            )
            val viewModel = fixture.viewModel(initialLineId = lineId(1))
            viewModel.onAction(Action.Start)
            advanceUntilIdle()

            assertEquals(LinkStatus.NEEDS_CHOICE, viewModel.uiState.value.lines.single().status)
            viewModel.onAction(Action.CandidateConfirmed(productId(10)))
            runCurrent()
            assertEquals(productId(10), viewModel.uiState.value.salePriceForm?.productId)

            viewModel.onAction(Action.SalePriceChanged("12.50"))
            runCurrent()
            viewModel.onAction(Action.SalePriceSubmitted)
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.salePriceForm)
            assertEquals(LinkStatus.CONFIRMED, viewModel.uiState.value.lines.single().status)
            assertEquals(
                Money.fromMajor("12.50", PEN),
                fixture.products.findById(productId(10))?.salePrice,
            )
            assertEquals(
                productId(10),
                fixture.reviews.find(DRAFT_ID)?.activeLines?.single()?.linkedProductId,
            )
        }

    @Test
    fun `un precio con version obsoleta conserva el formulario y no vincula`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val original = product(seed = 10, name = "Azúcar Rubia", salePrice = null)
            val fixture = fixture(
                lines = listOf(line(seed = 1, position = 0, description = "Azúcar Rubia")),
                products = listOf(original),
            )
            val viewModel = fixture.viewModel(initialLineId = lineId(1))
            viewModel.onAction(Action.Start)
            advanceUntilIdle()
            viewModel.onAction(Action.CandidateConfirmed(productId(10)))
            runCurrent()
            assertTrue(fixture.products.update(original.copy(name = "Azúcar Rubia premium")))

            viewModel.onAction(Action.SalePriceChanged("12.50"))
            runCurrent()
            viewModel.onAction(Action.SalePriceSubmitted)
            advanceUntilIdle()

            assertNotNull(viewModel.uiState.value.salePriceForm)
            assertEquals(
                ProductLinkingContract.SalePriceFailure.STALE,
                viewModel.uiState.value.salePriceFailure,
            )
            assertEquals(LinkStatus.NEEDS_CHOICE, viewModel.uiState.value.lines.single().status)
            assertNull(fixture.reviews.find(DRAFT_ID)?.activeLines?.single()?.linkedProductId)
        }

    @Test
    fun `dejar pendiente marca la linea sin persistir ningun enlace`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(
                lines = listOf(
                    line(seed = 1, position = 0, description = "PRODUCTO SIN CATALOGO XQZ"),
                ),
            )
            val viewModel = fixture.viewModel(initialLineId = lineId(1))
            viewModel.onAction(Action.Start)
            advanceUntilIdle()
            assertEquals(LinkStatus.NO_MATCH, viewModel.uiState.value.lines.single().status)

            viewModel.onAction(Action.SkipLine)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(LinkStatus.SKIPPED, state.lines.single().status)
            assertTrue(state.allResolved)
            assertTrue(state.canContinue)
            assertNull(
                fixture.reviews.find(DRAFT_ID)!!.activeLines.single().linkedProductId,
            )
        }

    @Test
    fun `a newer durable link is rendered from Flow without reopening the screen`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val linkedProduct = product(seed = 10, name = "Producto Room")
            val fixture = fixture(
                lines = listOf(
                    line(seed = 1, position = 0, description = "SIN COINCIDENCIA INICIAL"),
                ),
                products = listOf(linkedProduct),
            )
            val viewModel = fixture.viewModel(initialLineId = lineId(1))
            viewModel.onAction(Action.Start)
            advanceUntilIdle()
            val persisted = requireNotNull(fixture.reviews.find(DRAFT_ID))

            fixture.reviews.seed(
                persisted.copy(
                    lines = persisted.lines.map { line ->
                        line.copy(
                            linkedProductId = linkedProduct.productId,
                            linkedUnitId = linkedProduct.unitId,
                            linkConfidence = 1_000,
                        )
                    },
                    revision = persisted.revision + 1,
                    updatedAt = NOW.plusSeconds(1),
                ),
            )
            advanceUntilIdle()

            assertEquals(LinkStatus.CONFIRMED, viewModel.uiState.value.lines.single().status)
            assertEquals(persisted.revision + 1, fixture.reviews.find(DRAFT_ID)?.revision)
        }

    @Test
    fun `un enlace existente durable sin precio no reaparece confirmado`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val linkedProduct = product(seed = 10, name = "Producto legacy", salePrice = null)
            val fixture = fixture(
                lines = listOf(line(seed = 1, position = 0, description = "Producto legacy")),
                products = listOf(linkedProduct),
            )
            val edit = fixture.materializeReview()
            fixture.reviews.seed(
                edit.copy(
                    lines = edit.lines.map { line ->
                        line.copy(
                            linkedProductId = linkedProduct.productId,
                            linkedUnitId = linkedProduct.unitId,
                            linkConfidence = 1_000,
                            productProvenance = PurchaseProductProvenance.EXISTING,
                        )
                    },
                ),
            )

            val viewModel = fixture.viewModel(initialLineId = lineId(1))
            viewModel.onAction(Action.Start)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(LinkStatus.NEEDS_CHOICE, state.lines.single().status)
            assertTrue(state.lines.single().requiresSalePrice)
            assertFalse(state.canContinue)
            assertEquals(linkedProduct.productId, state.salePriceForm?.productId)
        }

    @Test
    fun `un staged legacy sin precio exige reemplazar el enlace y no puede omitirse`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(
                lines = listOf(line(seed = 1, position = 0, description = "Producto staged")),
            )
            val edit = fixture.materializeReview()
            val staged = StagedPurchaseProduct(
                productId = productId(90),
                businessId = BUSINESS_ID,
                unitId = unitId(60),
                name = "Producto staged",
                salePrice = null,
            )
            fixture.reviews.seed(
                edit.copy(
                    lines = edit.lines.map { line ->
                        line.copy(
                            linkedProductId = staged.productId,
                            linkedUnitId = staged.unitId,
                            linkConfidence = 1_000,
                            productProvenance = PurchaseProductProvenance.CREATED_IN_DRAFT,
                            stagedProduct = staged,
                        )
                    },
                ),
            )
            val viewModel = fixture.viewModel(initialLineId = lineId(1))
            viewModel.onAction(Action.Start)
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.lines.single().requiresSalePrice)
            viewModel.onAction(Action.SkipLine)
            runCurrent()
            assertEquals(LinkStatus.NEEDS_CHOICE, viewModel.uiState.value.lines.single().status)
            assertFalse(viewModel.uiState.value.canContinue)
        }

    @Test
    fun `crear un producto cierra el dialogo vincula la linea y conserva el resto`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(
                lines = listOf(
                    line(seed = 1, position = 0, description = "Arroz Extra Costeño"),
                    line(seed = 2, position = 1, description = "GALLETA SORPRESA DISPLAY"),
                ),
                products = listOf(product(seed = 10, name = "Arroz Extra Costeño")),
                withSupplier = true,
            )
            val viewModel = fixture.viewModel(initialLineId = lineId(2))
            viewModel.onAction(Action.Start)
            advanceUntilIdle()
            assertEquals(LinkStatus.NO_MATCH, viewModel.uiState.value.currentLine?.status)

            viewModel.onAction(Action.OpenCreateForm)
            runCurrent()
            val form = viewModel.uiState.value.createForm
            assertNotNull(form)
            assertEquals("GALLETA SORPRESA DISPLAY", form?.name)

            viewModel.onAction(
                Action.CreateFormChanged(
                    requireNotNull(form).copy(
                        name = "Galleta Sorpresa",
                        unitId = unitId(60),
                        salePrice = "8.50",
                    ),
                ),
            )
            runCurrent()
            viewModel.onAction(Action.CreateSubmitted)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertNull(state.createForm)
            assertTrue(
                fixture.products.findByNormalizedName(BUSINESS_ID, "Galleta Sorpresa").isEmpty(),
            )
            val createdLine = state.lines.first { it.lineId == lineId(2) }
            assertEquals(LinkStatus.CONFIRMED, createdLine.status)
            assertEquals("Galleta Sorpresa", createdLine.linkedProductName)
            val persistedLine = fixture.reviews.find(DRAFT_ID)!!.activeLines
                .first { it.lineId == lineId(2) }
            val staged = requireNotNull(persistedLine.stagedProduct)
            assertEquals(Money.fromMajor("8.50", PEN), staged.salePrice)
            assertEquals(staged.productId, persistedLine.linkedProductId)
            assertEquals(
                PurchaseProductProvenance.CREATED_IN_DRAFT,
                persistedLine.productProvenance,
            )
            // El resto de líneas conserva su estado; crear no abandona la revisión.
            assertEquals(2, state.lines.size)
            assertEquals(
                LinkStatus.AUTO_LINKED,
                state.lines.first { it.lineId == lineId(1) }.status,
            )
            assertTrue(state.allResolved)
            assertTrue(
                fixture.aliases
                    .findByNormalizedAlias(BUSINESS_ID, "GALLETA SORPRESA DISPLAY")
                    .isEmpty(),
            )

            val reloaded = fixture.viewModel(initialLineId = lineId(2))
            reloaded.onAction(Action.Start)
            advanceUntilIdle()
            assertEquals(
                LinkStatus.CONFIRMED,
                reloaded.uiState.value.lines.first { it.lineId == lineId(2) }.status,
            )
        }

    @Test
    fun `formulario de vinculacion no acepta barcode mediante truncamiento`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(
                lines = listOf(
                    line(seed = 1, position = 0, description = "PRODUCTO SIN COINCIDENCIA"),
                ),
            )
            val viewModel = fixture.viewModel(initialLineId = lineId(1))
            viewModel.onAction(Action.Start)
            advanceUntilIdle()
            viewModel.onAction(Action.OpenCreateForm)
            runCurrent()

            val form = requireNotNull(viewModel.uiState.value.createForm)
            viewModel.onAction(
                Action.CreateFormChanged(
                    form.copy(barcode = "A".repeat(BarcodeValue.MAX_LENGTH + 1)),
                ),
            )
            runCurrent()

            assertEquals(
                BarcodeValue.MAX_LENGTH + 1,
                viewModel.uiState.value.createForm?.barcode?.length,
            )
            assertFalse(requireNotNull(viewModel.uiState.value.createForm).isBarcodeValid)
        }

    @Test
    fun `un duplicado ofrece vincular al producto existente`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(
                lines = listOf(
                    line(seed = 1, position = 0, description = "LECHE FRESCA 900ML"),
                ),
                products = listOf(product(seed = 10, name = "Leche Gloria Entera")),
            )
            val viewModel = fixture.viewModel(initialLineId = lineId(1))
            viewModel.onAction(Action.Start)
            advanceUntilIdle()
            assertEquals(LinkStatus.NO_MATCH, viewModel.uiState.value.lines.single().status)

            viewModel.onAction(Action.OpenCreateForm)
            runCurrent()
            val form = requireNotNull(viewModel.uiState.value.createForm)
            viewModel.onAction(
                Action.CreateFormChanged(
                    form.copy(
                        name = "Leche Gloria Entera",
                        unitId = unitId(60),
                        salePrice = "8.50",
                    ),
                ),
            )
            runCurrent()
            viewModel.onAction(Action.CreateSubmitted)
            advanceUntilIdle()

            val duplicateForm = viewModel.uiState.value.createForm
            assertEquals(productId(10), duplicateForm?.duplicate?.productId)
            // No se insertó un segundo producto.
            assertEquals(
                1,
                fixture.products.findByNormalizedName(BUSINESS_ID, "Leche Gloria Entera").size,
            )

            viewModel.onAction(Action.CreateDuplicateLinkExisting)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertNull(state.createForm)
            assertEquals(LinkStatus.CONFIRMED, state.lines.single().status)
            assertEquals("Leche Gloria Entera", state.lines.single().linkedProductName)
            assertEquals(
                productId(10),
                fixture.reviews.find(DRAFT_ID)!!.activeLines.single().linkedProductId,
            )
            assertTrue(state.allResolved)
        }

    @Test
    fun `continuar con todas las lineas resueltas ejecuta la etapa y abre el resumen`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(
                lines = listOf(
                    line(seed = 1, position = 0, description = "Arroz Extra Costeño"),
                ),
                products = listOf(product(seed = 10, name = "Arroz Extra Costeño")),
            )
            val viewModel = fixture.viewModel(initialLineId = lineId(1))
            viewModel.onAction(Action.Start)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.allResolved)

            viewModel.effects.test {
                viewModel.onAction(Action.ContinueSelected)
                runCurrent()
                val call = fixture.workflow.takeCall()
                assertEquals(WorkflowStage.PRODUCTS_LINKED, call.input.stage)
                assertEquals(DRAFT_ID, call.input.draftId)
                assertEquals(lineId(1), call.input.lineId)
                call.succeed(
                    WorkflowSnapshot(
                        draftId = DRAFT_ID,
                        stage = WorkflowStage.PRODUCTS_LINKED,
                        lineId = lineId(1),
                        updatedAt = NOW,
                    ),
                )
                advanceUntilIdle()
                assertEquals(Effect.OpenSummary(DRAFT_ID), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `un borrador no editable termina en DRAFT_NOT_EDITABLE`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(lines = emptyList(), seedDraft = false)
            val viewModel = fixture.viewModel(initialLineId = lineId(1))
            viewModel.onAction(Action.Start)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(Failure.DRAFT_NOT_EDITABLE, state.failure)
            assertTrue(state.lines.isEmpty())
        }

    @Test
    fun `una ruta con ids invalidos emite CloseInvalidRoute`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(lines = emptyList(), seedDraft = false)
            val viewModel = fixture.viewModel(
                handle = SavedStateHandle(
                    mapOf(
                        RouteArgumentKeys.DRAFT_ID to "no-es-uuid",
                        RouteArgumentKeys.LINE_ID to "tampoco-es-uuid",
                    ),
                ),
            )
            assertEquals(Failure.INVALID_ROUTE, viewModel.uiState.value.failure)

            viewModel.effects.test {
                viewModel.onAction(Action.Start)
                runCurrent()
                assertEquals(Effect.CloseInvalidRoute, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    private suspend fun fixture(
        lines: List<InvoiceLine>,
        products: List<Product> = emptyList(),
        withSupplier: Boolean = false,
        seedDraft: Boolean = true,
    ): Fixture {
        val clock = MutableClock(NOW)
        val drafts = FakeInvoiceDraftRepository(clock)
        val suppliers = FakeSupplierRepository(clock)
        val supplier = if (withSupplier) {
            suppliers.create(
                Supplier(
                    supplierId = supplierId(50),
                    businessId = BUSINESS_ID,
                    legalName = "Proveedor Mayorista SAC",
                    ruc = "20123456789",
                    createdAt = NOW,
                    updatedAt = NOW,
                ),
            )
        } else {
            null
        }
        if (seedDraft) {
            drafts.createDraft(
                InvoiceDraft(
                    draftId = DRAFT_ID,
                    businessId = BUSINESS_ID,
                    status = DraftStatus.NEEDS_REVIEW,
                    supplierId = supplier?.supplierId,
                    supplierRucNormalized = supplier?.ruc,
                    currency = PEN,
                    createdAt = NOW,
                    updatedAt = NOW,
                ),
            )
            if (lines.isNotEmpty()) {
                drafts.replaceLines(DRAFT_ID, lines)
            }
        }
        val units = FakeUnitRepository(clock)
        units.create(
            UnitOfMeasure(
                unitId = unitId(60),
                businessId = BUSINESS_ID,
                code = "NIU",
                name = "Unidad",
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )
        val productRepository = FakeProductRepository(clock)
        products.forEach { productRepository.create(it) }
        val configuration = FakeAppConfigurationRepository()
        configuration.completeOnboarding(
            BUSINESS_ID,
            com.facturastock.app.domain.config.AppConfiguration.DEFAULT_TAX_RATE,
            com.facturastock.app.domain.config.AppConfiguration.DEFAULT_COST_POLICY,
        )
        return Fixture(
            clock = clock,
            drafts = drafts,
            reviews = FakeInvoiceLinesReviewRepository(drafts),
            parsed = FakeParsedInvoiceRepository(drafts),
            products = productRepository,
            configuration = configuration,
            aliases = FakeSupplierProductAliasRepository(clock),
            suppliers = suppliers,
            units = units,
            workflow = ControlledDraftWorkflowRepository(),
            dispatchers = TestDispatcherProvider(mainDispatcherRule.dispatcher),
        )
    }

    private inner class Fixture(
        val clock: MutableClock,
        val drafts: FakeInvoiceDraftRepository,
        val reviews: FakeInvoiceLinesReviewRepository,
        val parsed: FakeParsedInvoiceRepository,
        val products: FakeProductRepository,
        val configuration: FakeAppConfigurationRepository,
        val aliases: FakeSupplierProductAliasRepository,
        val suppliers: FakeSupplierRepository,
        val units: FakeUnitRepository,
        val workflow: ControlledDraftWorkflowRepository,
        val dispatchers: TestDispatcherProvider,
    ) {
        private var nextUuidSeed = 10_000
        val matchingProducts = ControlledManualSearchProductRepository(products)

        suspend fun materializeReview(): InvoiceLinesEdit = requireNotNull(
            LoadInvoiceLinesReviewUseCase(
                invoiceDraftRepository = drafts,
                invoiceLinesReviewRepository = reviews,
                parsedInvoiceRepository = parsed,
                appClock = clock,
            )(DRAFT_ID),
        ).edit

        fun viewModel(
            initialLineId: LineId = lineId(1),
            handle: SavedStateHandle = routeHandle(initialLineId),
        ): ProductLinkingViewModel {
            val uuidGenerator = UuidGenerator { uuid(nextUuidSeed++) }
            val saveAlias = SaveSupplierAliasUseCase(aliases, uuidGenerator, clock)
            return ProductLinkingViewModel(
                savedStateHandle = handle,
                loadInvoiceLinesReviewUseCase = LoadInvoiceLinesReviewUseCase(
                    invoiceDraftRepository = drafts,
                    invoiceLinesReviewRepository = reviews,
                    parsedInvoiceRepository = parsed,
                    appClock = clock,
                ),
                saveInvoiceLinesEditUseCase = SaveInvoiceLinesEditUseCase(
                    invoiceDraftRepository = drafts,
                    invoiceLinesReviewRepository = reviews,
                ),
                productMatchingUseCase = ProductMatchingUseCase(matchingProducts, aliases),
                createLinkedProductUseCase = CreateLinkedProductUseCase(
                    appConfigurationRepository = configuration,
                    productRepository = products,
                    uuidGenerator = uuidGenerator,
                ),
                saveSupplierAliasUseCase = saveAlias,
                runDraftStageUseCase = RunDraftStageUseCase(workflow),
                supplierRepository = suppliers,
                unitRepository = units,
                productRepository = products,
                appConfigurationRepository = configuration,
                updateProductSalePriceUseCase = UpdateProductSalePriceUseCase(
                    configuration,
                    products,
                ),
                dispatcherProvider = dispatchers,
            )
        }
    }

    private class ControlledManualSearchProductRepository(
        private val delegate: ProductRepository,
    ) : ProductRepository by delegate {
        var manualSearchGate: CompletableDeferred<Unit>? = null
        var nextManualSearchFailure: Throwable? = null

        override suspend fun findBySku(businessId: BusinessId, sku: String): Product? {
            manualSearchGate?.await()
            nextManualSearchFailure?.let { failure ->
                nextManualSearchFailure = null
                throw failure
            }
            return delegate.findBySku(businessId, sku)
        }
    }

    private class MutableClock(private var value: Instant) : AppClock {
        override fun now(): Instant = value
    }

    private fun routeHandle(initialLineId: LineId): SavedStateHandle = SavedStateHandle(
        mapOf(
            RouteArgumentKeys.DRAFT_ID to DRAFT_ID.value,
            RouteArgumentKeys.LINE_ID to initialLineId.value,
        ),
    )

    private fun line(
        seed: Int,
        position: Int,
        description: String,
        code: String? = null,
    ): InvoiceLine = InvoiceLine(
        lineId = lineId(seed),
        draftId = DRAFT_ID,
        businessId = BUSINESS_ID,
        position = position,
        descriptionRaw = description,
        descriptionNormalized = description,
        codeRaw = code,
        codeNormalized = code,
        quantity = Quantity.of("1"),
        ocrConfidence = 950,
        createdAt = NOW,
        updatedAt = NOW,
    )

    private fun product(
        seed: Int,
        name: String,
        sku: String? = null,
        salePrice: Money? = Money.fromMajor("8.50", PEN),
    ): Product = Product(
        productId = productId(seed),
        businessId = BUSINESS_ID,
        unitId = unitId(60),
        name = name,
        sku = sku,
        salePrice = salePrice,
        status = CatalogStatus.ACTIVE,
        createdAt = NOW,
        updatedAt = NOW,
    )

    private fun uuid(seed: Int): UUID =
        UUID.fromString("00000000-0000-0000-0000-%012d".format(seed))

    private fun lineId(seed: Int): LineId = LineId.from(uuid(seed))

    private fun productId(seed: Int): ProductId = ProductId.from(uuid(seed))

    private fun unitId(seed: Int): UnitId = UnitId.from(uuid(seed))

    private fun supplierId(seed: Int): SupplierId = SupplierId.from(uuid(seed))

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-10T12:00:00Z")
        val PEN: CurrencyCode = CurrencyCode.of("PEN")
        val DRAFT_ID: DraftId = DraftId.from(
            UUID.fromString("10000000-0000-0000-0000-000000000001"),
        )
        val BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("20000000-0000-0000-0000-000000000001"),
        )
    }
}
