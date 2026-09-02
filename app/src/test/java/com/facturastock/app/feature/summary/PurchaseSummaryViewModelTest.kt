package com.facturastock.app.feature.summary

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.InvoiceHeaderEdit
import com.facturastock.app.domain.model.InvoiceLineEdit
import com.facturastock.app.domain.model.InvoiceLineEditOrigin
import com.facturastock.app.domain.model.InvoiceLineEditValue
import com.facturastock.app.domain.model.InvoiceLinesEdit
import com.facturastock.app.domain.model.InvoiceLineValueSource
import com.facturastock.app.domain.model.InventoryTaxTreatment
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.PrepareBlockerCode
import com.facturastock.app.domain.model.PreparedPurchase
import com.facturastock.app.domain.model.PreparedPurchaseLine
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.PurchaseDocumentType
import com.facturastock.app.domain.model.PurchaseProductProvenance
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.UnitOfMeasure
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.repository.InvoiceHeaderEditPublication
import com.facturastock.app.domain.repository.InvoiceHeaderReviewRepository
import com.facturastock.app.domain.repository.SaveInvoiceHeaderEditResult
import com.facturastock.app.domain.repository.PublishPreparedPurchaseResult
import com.facturastock.app.domain.usecase.ObserveInvoiceDraftUseCase
import com.facturastock.app.domain.usecase.ObservePreparedPurchaseUseCase
import com.facturastock.app.domain.usecase.PreparePurchaseUseCase
import com.facturastock.app.feature.common.RouteArgumentKeys
import com.facturastock.app.feature.summary.PurchaseSummaryContract.Action
import com.facturastock.app.feature.summary.PurchaseSummaryContract.Effect
import com.facturastock.app.feature.summary.PurchaseSummaryContract.Mode
import com.facturastock.app.testing.FakeInvoiceDraftRepository
import com.facturastock.app.testing.FakeInvoiceLinesReviewRepository
import com.facturastock.app.testing.FakePreparedPurchaseRepository
import com.facturastock.app.testing.FakeProductRepository
import com.facturastock.app.testing.FakeUnitRepository
import com.facturastock.app.testing.MainDispatcherRule
import com.facturastock.app.testing.TestDispatcherProvider
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PurchaseSummaryViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `prepared snapshot is rendered and announces that editable history must be pruned`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = preparedFixture()
            val viewModel = fixture.viewModel()

            viewModel.effects.test {
                viewModel.onAction(Action.Start)
                advanceUntilIdle()

                val state = viewModel.uiState.value
                assertEquals(Mode.PREPARED, state.mode)
                assertFalse(state.isLoading)
                assertSame(fixture.purchase, state.prepared)
                assertEquals(Effect.PreparedStateEntered(DRAFT_ID), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `volver a editar invalidates the snapshot before navigation is emitted`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = preparedFixture()
            val viewModel = fixture.viewModel()

            viewModel.effects.test {
                viewModel.onAction(Action.Start)
                advanceUntilIdle()
                assertEquals(Effect.PreparedStateEntered(DRAFT_ID), awaitItem())

                viewModel.onAction(Action.ReopenSelected)
                advanceUntilIdle()

                assertEquals(DraftStatus.NEEDS_REVIEW, fixture.drafts.findDraft(DRAFT_ID)?.status)
                assertNull(fixture.prepared.find(DRAFT_ID))
                assertEquals(Effect.OpenLineReview(DRAFT_ID), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `registrar compra only opens confirmation and keeps the prepared snapshot untouched`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = preparedFixture()
            val viewModel = fixture.viewModel()

            viewModel.effects.test {
                viewModel.onAction(Action.Start)
                advanceUntilIdle()
                assertEquals(Effect.PreparedStateEntered(DRAFT_ID), awaitItem())

                viewModel.onAction(Action.RegisterSelected)
                advanceUntilIdle()

                assertEquals(
                    Effect.OpenConfirmation(DRAFT_ID, fixture.purchase.logicalHash),
                    awaitItem(),
                )
                assertEquals(DraftStatus.READY_TO_POST, fixture.drafts.findDraft(DRAFT_ID)?.status)
                assertSame(fixture.purchase, fixture.prepared.find(DRAFT_ID))
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `poco espacio al preparar conserva revision y permite reintentar desde Room`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = editableFixture()
            val draftBefore = fixture.drafts.findDraft(DRAFT_ID)
            val headerBefore = fixture.headers.find(DRAFT_ID)
            val linesBefore = fixture.lines.find(DRAFT_ID)
            fixture.prepared.nextException = StorageException(StorageError.InsufficientSpace)
            val viewModel = fixture.viewModel()

            viewModel.effects.test {
                viewModel.onAction(Action.Start)
                advanceUntilIdle()
                assertTrue(viewModel.uiState.value.canPrepare)

                viewModel.onAction(Action.PrepareSelected)
                advanceUntilIdle()

                assertEquals(
                    PurchaseSummaryContract.Failure.STORAGE_FULL,
                    viewModel.uiState.value.failure,
                )
                assertEquals(draftBefore, fixture.drafts.findDraft(DRAFT_ID))
                assertEquals(headerBefore, fixture.headers.find(DRAFT_ID))
                assertEquals(linesBefore, fixture.lines.find(DRAFT_ID))
                assertNull(fixture.prepared.find(DRAFT_ID))
                expectNoEvents()

                viewModel.onAction(Action.Retry)
                advanceUntilIdle()
                assertTrue(viewModel.uiState.value.canPrepare)
                viewModel.onAction(Action.PrepareSelected)
                advanceUntilIdle()

                assertEquals(Effect.PreparedStateEntered(DRAFT_ID), awaitItem())
                assertEquals(DraftStatus.READY_TO_POST, fixture.drafts.findDraft(DRAFT_ID)?.status)
                assertEquals(
                    fixture.prepared.find(DRAFT_ID)?.logicalHash,
                    viewModel.uiState.value.prepared?.logicalHash,
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `cambios durables refrescan bloqueos y totales sin borrar entradas locales`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = editableFixture()
            val viewModel = fixture.viewModel()

            viewModel.onAction(Action.Start)
            advanceUntilIdle()
            val financeBefore = viewModel.uiState.value.finance
            assertTrue(viewModel.uiState.value.canPrepare)

            val localReason = "Diferencia aceptada según documento del proveedor"
            viewModel.onAction(Action.RoundingAcceptanceChanged(true))
            viewModel.onAction(Action.AdjustmentReasonChanged(localReason))
            advanceUntilIdle()

            val currentLines = requireNotNull(fixture.lines.find(DRAFT_ID))
            fixture.lines.seed(
                currentLines.copy(
                    lines = currentLines.lines.map { line ->
                        line.copy(
                            quantity = written("1"),
                            unitCost = written("11.00"),
                            total = written("11.00"),
                            requiresReview = true,
                            reviewConfirmedByUser = false,
                            updatedAt = NOW.plusSeconds(1),
                        )
                    },
                    revision = currentLines.revision + 1L,
                    updatedAt = NOW.plusSeconds(1),
                ),
            )
            (fixture.headers as StoredHeaderReviewRepository).replaceExternally(
                requireNotNull(fixture.headers.find(DRAFT_ID)).copy(
                    subtotal = "9.47",
                    igv = "1.53",
                    total = "11.00",
                    revision = 2L,
                    updatedAt = NOW.plusSeconds(1),
                ),
            )
            val currentDraft = requireNotNull(fixture.drafts.findDraft(DRAFT_ID))
            val currency = requireNotNull(currentDraft.currency)
            fixture.drafts.updateDraft(
                currentDraft.copy(
                    subtotal = Money.ofMinor(947, currency),
                    total = Money.ofMinor(1_100, currency),
                ),
            )
            advanceUntilIdle()

            val refreshed = viewModel.uiState.value
            assertEquals(Mode.EDITING, refreshed.mode)
            assertFalse(refreshed.isLoading)
            assertTrue(
                refreshed.blockers.any { blocker ->
                    blocker.code == PrepareBlockerCode.LINE_REVIEW_PENDING
                },
            )
            assertFalse(refreshed.canPrepare)
            assertTrue(refreshed.finance != financeBefore)
            assertFalse(requireNotNull(refreshed.finance).hasDifference)
            assertTrue(refreshed.roundingAccepted)
            assertEquals(localReason, refreshed.adjustmentReason)
        }

    private suspend fun preparedFixture(): Fixture {
        val drafts = FakeInvoiceDraftRepository(CLOCK)
        drafts.createDraft(
            InvoiceDraft(
                draftId = DRAFT_ID,
                businessId = BUSINESS_ID,
                status = DraftStatus.NEEDS_REVIEW,
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )
        val prepared = FakePreparedPurchaseRepository(drafts, CLOCK)
        val lines = FakeInvoiceLinesReviewRepository(drafts)
        val purchase = preparedPurchase()
        assertEquals(
            PublishPreparedPurchaseResult.PREPARED,
            prepared.publish(
                purchase = purchase,
                expectedDraftUpdatedAt = NOW,
                expectedHeaderRevision = 0L,
                expectedLinesRevision = 0L,
            ),
        )
        val useCase = PreparePurchaseUseCase(
            invoiceDraftRepository = drafts,
            invoiceHeaderReviewRepository = EmptyHeaderReviewRepository,
            invoiceLinesReviewRepository = lines,
            preparedPurchaseRepository = prepared,
            productRepository = FakeProductRepository(CLOCK),
            unitRepository = FakeUnitRepository(CLOCK),
            appClock = CLOCK,
        )
        return Fixture(
            drafts = drafts,
            prepared = prepared,
            headers = EmptyHeaderReviewRepository,
            lines = lines,
            purchase = purchase,
            useCase = useCase,
            dispatcherProvider = TestDispatcherProvider(mainDispatcherRule.dispatcher),
        )
    }

    private suspend fun editableFixture(): Fixture {
        val pen = CurrencyCode.of("PEN")
        val drafts = FakeInvoiceDraftRepository(CLOCK)
        drafts.createDraft(
            InvoiceDraft(
                draftId = DRAFT_ID,
                businessId = BUSINESS_ID,
                status = DraftStatus.NEEDS_REVIEW,
                supplierRucNormalized = "20123456789",
                supplierLegalNameNormalized = "Proveedor Preparado SAC",
                documentType = PurchaseDocumentType.INVOICE,
                documentNumberNormalized = "F001-42",
                issueDate = LocalDate.of(2026, 8, 12),
                currency = pen,
                subtotal = Money.ofMinor(847, pen),
                tax = Money.ofMinor(153, pen),
                total = Money.ofMinor(1_000, pen),
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )
        val headers = StoredHeaderReviewRepository(
            InvoiceHeaderEdit(
                draftId = DRAFT_ID,
                supplierRuc = "20123456789",
                supplierLegalName = "Proveedor Preparado SAC",
                documentType = PurchaseDocumentType.INVOICE.name,
                documentSeries = "F001",
                documentNumber = "42",
                issueDate = "12/08/2026",
                currency = "PEN",
                subtotal = "8.47",
                igv = "1.53",
                total = "10.00",
                revision = 1L,
                updatedAt = NOW,
            ),
        )
        val lines = FakeInvoiceLinesReviewRepository(drafts)
        lines.seed(
            InvoiceLinesEdit(
                draftId = DRAFT_ID,
                lines = listOf(
                    InvoiceLineEdit(
                        lineId = LineId.from(uuid(3)),
                        position = 0,
                        origin = InvoiceLineEditOrigin.USER,
                        linkedProductId = ProductId.from(uuid(4)),
                        linkedUnitId = UnitId.from(uuid(5)),
                        linkConfidence = 1_000,
                        taxTreatment = InventoryTaxTreatment.INCLUDED,
                        productProvenance = PurchaseProductProvenance.EXISTING,
                        description = written("Arroz preparado"),
                        quantity = written("2"),
                        unitCost = written("5.00"),
                        igv = written("1.53"),
                        total = written("10.00"),
                        reviewConfirmedByUser = true,
                        createdAt = NOW,
                        updatedAt = NOW,
                    ),
                ),
                revision = 1L,
                updatedAt = NOW,
            ),
        )
        val products = FakeProductRepository(CLOCK)
        val units = FakeUnitRepository(CLOCK, products)
        units.create(
            UnitOfMeasure(
                unitId = UnitId.from(uuid(5)),
                businessId = BUSINESS_ID,
                code = "NIU",
                name = "Unidad",
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )
        products.create(
            Product(
                productId = ProductId.from(uuid(4)),
                businessId = BUSINESS_ID,
                unitId = UnitId.from(uuid(5)),
                name = "Arroz preparado",
                status = CatalogStatus.ACTIVE,
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )
        val prepared = FakePreparedPurchaseRepository(drafts, CLOCK)
        val useCase = PreparePurchaseUseCase(
            invoiceDraftRepository = drafts,
            invoiceHeaderReviewRepository = headers,
            invoiceLinesReviewRepository = lines,
            preparedPurchaseRepository = prepared,
            productRepository = products,
            unitRepository = units,
            appClock = CLOCK,
        )
        return Fixture(
            drafts = drafts,
            prepared = prepared,
            headers = headers,
            lines = lines,
            purchase = preparedPurchase(),
            useCase = useCase,
            dispatcherProvider = TestDispatcherProvider(mainDispatcherRule.dispatcher),
        )
    }

    private data class Fixture(
        val drafts: FakeInvoiceDraftRepository,
        val prepared: FakePreparedPurchaseRepository,
        val headers: InvoiceHeaderReviewRepository,
        val lines: FakeInvoiceLinesReviewRepository,
        val purchase: PreparedPurchase,
        val useCase: PreparePurchaseUseCase,
        val dispatcherProvider: DispatcherProvider,
    ) {
        fun viewModel(): PurchaseSummaryViewModel = PurchaseSummaryViewModel(
            savedStateHandle = SavedStateHandle(
                mapOf(RouteArgumentKeys.DRAFT_ID to DRAFT_ID.value),
            ),
            preparePurchaseUseCase = useCase,
            observeInvoiceDraft = ObserveInvoiceDraftUseCase(drafts),
            observePreparedPurchase = ObservePreparedPurchaseUseCase(prepared),
            dispatcherProvider = dispatcherProvider,
        )
    }

    private fun preparedPurchase(): PreparedPurchase {
        val pen = CurrencyCode.of("PEN")
        return PreparedPurchase(
            draftId = DRAFT_ID,
            businessId = BUSINESS_ID,
            supplierId = null,
            supplierRuc = "20123456789",
            supplierLegalName = "Proveedor Preparado SAC",
            documentType = null,
            documentNumber = "F001-42",
            issueDate = LocalDate.of(2026, 8, 12),
            currency = pen,
            lines = listOf(
                PreparedPurchaseLine(
                    lineId = LineId.from(uuid(3)),
                    position = 0,
                    productId = ProductId.from(uuid(4)),
                    unitId = UnitId.from(uuid(5)),
                    description = "Arroz preparado",
                    quantity = Quantity.of("2"),
                    lineTotal = Money.ofMinor(1_000, pen),
                ),
            ),
            subtotal = Money.ofMinor(847, pen),
            tax = Money.ofMinor(153, pen),
            otherCharges = null,
            total = Money.ofMinor(1_000, pen),
            acceptedWarnings = emptyList(),
            logicalHash = "a".repeat(64),
            preparedAt = NOW,
        )
    }

    private object EmptyHeaderReviewRepository : InvoiceHeaderReviewRepository {
        override suspend fun find(draftId: DraftId): InvoiceHeaderEdit? = null

        override suspend fun saveIfNewer(
            publication: InvoiceHeaderEditPublication,
        ): SaveInvoiceHeaderEditResult = SaveInvoiceHeaderEditResult.DRAFT_NOT_EDITABLE
    }

    private class StoredHeaderReviewRepository(
        private var stored: InvoiceHeaderEdit,
    ) : InvoiceHeaderReviewRepository {
        override suspend fun find(draftId: DraftId): InvoiceHeaderEdit? =
            stored.takeIf { it.draftId == draftId }

        override suspend fun saveIfNewer(
            publication: InvoiceHeaderEditPublication,
        ): SaveInvoiceHeaderEditResult {
            stored = publication.edit
            return SaveInvoiceHeaderEditResult.SAVED
        }

        fun replaceExternally(edit: InvoiceHeaderEdit) {
            stored = edit
        }
    }

    private fun written(value: String): InvoiceLineEditValue = InvoiceLineEditValue(
        written = value,
        selectedSource = InvoiceLineValueSource.WRITTEN,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-12T12:00:00Z")
        val CLOCK: AppClock = AppClock { NOW }
        val BUSINESS_ID: BusinessId = BusinessId.from(uuid(1))
        val DRAFT_ID: DraftId = DraftId.from(uuid(2))

        fun uuid(seed: Int): UUID =
            UUID.fromString("00000000-0000-0000-0000-%012d".format(seed))
    }
}
