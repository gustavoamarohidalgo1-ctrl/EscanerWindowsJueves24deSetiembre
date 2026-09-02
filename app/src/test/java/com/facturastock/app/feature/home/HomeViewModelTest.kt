package com.facturastock.app.feature.home

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.InvoiceImage
import com.facturastock.app.domain.model.Supplier
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.model.id.OcrRunId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.model.id.SupplierId
import com.facturastock.app.domain.usecase.DeleteDraftUseCase
import com.facturastock.app.domain.usecase.FindDraftFirstImageUseCase
import com.facturastock.app.domain.usecase.ObserveHomeDashboardUseCase
import com.facturastock.app.domain.usecase.RetryDraftOcrUseCase
import com.facturastock.app.domain.usecase.StartInvoiceDraftUseCase
import com.facturastock.app.testing.FakeAppConfigurationRepository
import com.facturastock.app.testing.FakeBusinessRepository
import com.facturastock.app.testing.FakeDraftFileStore
import com.facturastock.app.testing.FakeHomeDashboardReadRepository
import com.facturastock.app.testing.FakeInvoiceDraftRepository
import com.facturastock.app.testing.FakeRecentDraftReadRepository
import com.facturastock.app.testing.FakeSupplierRepository
import com.facturastock.app.testing.MainDispatcherRule
import com.facturastock.app.testing.TestDispatcherProvider
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class HomeViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private var now: Instant = Instant.parse("2026-08-01T12:00:00Z")
    private val clock = AppClock { now }
    private val appConfig = FakeAppConfigurationRepository()
    private val businesses = FakeBusinessRepository(clock)
    private val drafts = FakeInvoiceDraftRepository(clock)
    private val suppliers = FakeSupplierRepository(clock)
    private val fileStore = FakeDraftFileStore()
    private val dispatchers = TestDispatcherProvider(main = mainDispatcherRule.dispatcher)

    @Test
    fun `scan invoice persists the generated draft before navigating and does not replay`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activateBusiness()
            val viewModel = createViewModel()

            viewModel.effects.test {
                viewModel.onAction(HomeContract.Action.ScanInvoiceSelected)
                runCurrent()

                assertEquals(
                    HomeContract.Effect.OpenDraftCamera(DraftId.from(GENERATED_UUID)),
                    awaitItem(),
                )
                assertEquals(
                    DraftStatus.CREATED,
                    drafts.findDraft(DraftId.from(GENERATED_UUID))?.status,
                )
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }

            viewModel.effects.test {
                runCurrent()
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `storage failure creating a draft keeps existing drafts and never navigates`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activateBusiness()
            drafts.createDraft(draft(draftId(1)))
            val viewModel = createViewModel()
            runCurrent()
            drafts.nextFailure = StorageError.InsufficientSpace

            viewModel.effects.test {
                viewModel.onAction(HomeContract.Action.ScanInvoiceSelected)
                runCurrent()

                assertEquals(HomeContract.Effect.ShowDraftCreationFailure, awaitItem())
                assertNull(drafts.findDraft(DraftId.from(GENERATED_UUID)))
                assertEquals(draftId(1), drafts.findDraft(draftId(1))?.draftId)
                assertFalse(viewModel.uiState.value.isCreatingDraft)
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `observed drafts arrive ordered by recency with resolved supplier names`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activateBusiness()
            suppliers.create(
                Supplier(
                    supplierId = SUPPLIER_ID,
                    businessId = BUSINESS_ID,
                    legalName = "Distribuidora Andina S.A.C.",
                    createdAt = Instant.EPOCH,
                    updatedAt = Instant.EPOCH,
                ),
            )
            drafts.createDraft(draft(draftId(1), supplierId = SUPPLIER_ID))
            advanceClock()
            drafts.createDraft(draft(draftId(2), status = DraftStatus.CAPTURED))

            val viewModel = createViewModel()
            runCurrent()

            val state = viewModel.uiState.value
            assertFalse(state.isLoading)
            assertEquals(
                listOf(draftId(2), draftId(1)),
                state.drafts?.map { it.draft.draftId },
            )
            assertEquals("Distribuidora Andina S.A.C.", state.drafts?.get(1)?.supplierName)
            assertNull(state.drafts?.get(0)?.supplierName)
            assertNotNull(state.dashboard)
            assertEquals(2, state.dashboard?.overview?.drafts?.openCount)
        }

    @Test
    fun `drafts reappear when the view model is recreated over the same repositories`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activateBusiness()
            drafts.createDraft(draft(draftId(1)))

            val firstViewModel = createViewModel()
            runCurrent()
            val firstDrafts = firstViewModel.uiState.value.drafts

            val recreatedViewModel = createViewModel(savedStateHandle = SavedStateHandle())
            runCurrent()

            assertEquals(listOf(draftId(1)), firstDrafts?.map { it.draft.draftId })
            assertEquals(firstDrafts, recreatedViewModel.uiState.value.drafts)
        }

    @Test
    fun `selecting a draft emits the resume effect of its status`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activateBusiness()
            drafts.createDraft(draft(draftId(1), status = DraftStatus.CREATED))
            drafts.createDraft(draft(draftId(2), status = DraftStatus.CAPTURED))
            drafts.seedImage(image(imageId(1), draftId(2), pageIndex = 0))
            drafts.createDraft(draft(draftId(3), status = DraftStatus.OCR_PROCESSING))
            drafts.createDraft(draft(draftId(4), status = DraftStatus.OCR_READY))
            drafts.createDraft(draft(draftId(5), status = DraftStatus.NEEDS_REVIEW))
            drafts.createDraft(draft(draftId(6), status = DraftStatus.READY_TO_POST))
            drafts.createDraft(draft(draftId(7), status = DraftStatus.ERROR))
            drafts.createDraft(
                draft(
                    draftId(8),
                    status = DraftStatus.READY_TO_POST,
                    confirmedPurchaseId = PURCHASE_ID,
                ),
            )
            val viewModel = createViewModel()
            runCurrent()

            viewModel.effects.test {
                viewModel.onAction(HomeContract.Action.DraftSelected(draftId(1)))
                runCurrent()
                assertEquals(HomeContract.Effect.OpenDraftCamera(draftId(1)), awaitItem())

                viewModel.onAction(HomeContract.Action.DraftSelected(draftId(2)))
                runCurrent()
                assertEquals(HomeContract.Effect.OpenProcessing(draftId(2)), awaitItem())

                // OCR_PROCESSING no navega: abre el diálogo de reanudar o repetir.
                viewModel.onAction(HomeContract.Action.DraftSelected(draftId(3)))
                runCurrent()
                expectNoEvents()
                assertEquals(
                    draftId(3),
                    viewModel.uiState.value.draftIdPendingOcrChoice,
                )
                viewModel.onAction(HomeContract.Action.OcrChoiceDismissed)
                runCurrent()

                viewModel.onAction(HomeContract.Action.DraftSelected(draftId(4)))
                runCurrent()
                assertEquals(HomeContract.Effect.OpenProcessing(draftId(4)), awaitItem())

                viewModel.onAction(HomeContract.Action.DraftSelected(draftId(5)))
                runCurrent()
                assertEquals(HomeContract.Effect.OpenProcessing(draftId(5)), awaitItem())

                viewModel.onAction(HomeContract.Action.DraftSelected(draftId(6)))
                runCurrent()
                assertEquals(HomeContract.Effect.OpenSummary(draftId(6)), awaitItem())

                viewModel.onAction(HomeContract.Action.DraftSelected(draftId(7)))
                runCurrent()
                assertEquals(HomeContract.Effect.OpenProcessing(draftId(7)), awaitItem())

                viewModel.onAction(HomeContract.Action.DraftSelected(draftId(8)))
                runCurrent()
                // Una compra confirmada ya no forma parte del feed de trabajo de Inicio.
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `shortcut selections emit their navigation effects`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()

            viewModel.effects.test {
                viewModel.onAction(HomeContract.Action.ProductsSelected)
                runCurrent()
                assertEquals(HomeContract.Effect.OpenProducts, awaitItem())

                viewModel.onAction(HomeContract.Action.PurchasesSelected)
                runCurrent()
                assertEquals(HomeContract.Effect.OpenPurchases, awaitItem())

                viewModel.onAction(HomeContract.Action.InventorySelected)
                runCurrent()
                assertEquals(HomeContract.Effect.OpenInventory, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `confirmed deletion removes the draft and its files, keeping other drafts`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activateBusiness()
            drafts.createDraft(draft(draftId(1)))
            drafts.createDraft(draft(draftId(2)))
            drafts.seedImage(image(imageId(1), draftId(1), pageIndex = 0))
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(HomeContract.Action.DeleteRequested(draftId(1)))
            runCurrent()
            assertEquals(draftId(1), viewModel.uiState.value.draftIdPendingDeletion)

            viewModel.effects.test {
                viewModel.onAction(HomeContract.Action.DeleteConfirmed)
                runCurrent()
                assertEquals(HomeContract.Effect.ShowDeleteSuccess, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            runCurrent()

            assertNull(viewModel.uiState.value.draftIdPendingDeletion)
            assertNull(drafts.findDraft(draftId(1)))
            assertEquals(draftId(2), drafts.findDraft(draftId(2))?.draftId)
            assertEquals(listOf(draftId(1)), fileStore.draftTreeDeletions)
            assertTrue(fileStore.deletions.isEmpty())
            assertEquals(
                listOf(draftId(2)),
                viewModel.uiState.value.drafts?.map { it.draft.draftId },
            )
        }

    @Test
    fun `failed deletion emits ShowDeleteFailure and the retry deletes the same draft`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activateBusiness()
            drafts.createDraft(draft(draftId(1)))
            val viewModel = createViewModel()
            runCurrent()
            drafts.nextFailure = StorageError.Unavailable

            viewModel.onAction(HomeContract.Action.DeleteRequested(draftId(1)))
            runCurrent()
            viewModel.effects.test {
                viewModel.onAction(HomeContract.Action.DeleteConfirmed)
                runCurrent()
                assertEquals(
                    HomeContract.Effect.ShowDeleteFailure(draftId(1)),
                    awaitItem(),
                )
                assertEquals(draftId(1), drafts.findDraft(draftId(1))?.draftId)

                // Mismo par de acciones que despacha la acción "Reintentar" del snackbar.
                viewModel.onAction(HomeContract.Action.DeleteRequested(draftId(1)))
                runCurrent()
                viewModel.onAction(HomeContract.Action.DeleteConfirmed)
                runCurrent()
                assertEquals(HomeContract.Effect.ShowDeleteSuccess, awaitItem())
                assertNull(drafts.findDraft(draftId(1)))
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `dismissing the delete dialog closes it without effects`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activateBusiness()
            drafts.createDraft(draft(draftId(1)))
            val viewModel = createViewModel()
            runCurrent()

            viewModel.effects.test {
                viewModel.onAction(HomeContract.Action.DeleteRequested(draftId(1)))
                runCurrent()
                assertEquals(draftId(1), viewModel.uiState.value.draftIdPendingDeletion)

                viewModel.onAction(HomeContract.Action.DeleteDismissed)
                runCurrent()
                expectNoEvents()
                assertNull(viewModel.uiState.value.draftIdPendingDeletion)
                assertEquals(draftId(1), drafts.findDraft(draftId(1))?.draftId)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `interrupted OCR opens the choice dialog, resume navigates and retry resets to CAPTURED`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activateBusiness()
            drafts.createDraft(draft(draftId(1), status = DraftStatus.OCR_PROCESSING))
            drafts.createDraft(draft(draftId(2), status = DraftStatus.OCR_PROCESSING))
            val viewModel = createViewModel()
            runCurrent()

            viewModel.effects.test {
                viewModel.onAction(HomeContract.Action.DraftSelected(draftId(1)))
                runCurrent()
                expectNoEvents()
                assertEquals(draftId(1), viewModel.uiState.value.draftIdPendingOcrChoice)

                viewModel.onAction(HomeContract.Action.OcrResumeSelected)
                runCurrent()
                assertEquals(HomeContract.Effect.OpenProcessing(draftId(1)), awaitItem())
                assertNull(viewModel.uiState.value.draftIdPendingOcrChoice)
                assertEquals(DraftStatus.CAPTURED, drafts.findDraft(draftId(1))?.status)

                viewModel.onAction(HomeContract.Action.DraftSelected(draftId(2)))
                runCurrent()
                assertEquals(draftId(2), viewModel.uiState.value.draftIdPendingOcrChoice)

                viewModel.onAction(HomeContract.Action.OcrRetrySelected)
                runCurrent()
                assertEquals(HomeContract.Effect.OpenProcessing(draftId(2)), awaitItem())
                assertNull(viewModel.uiState.value.draftIdPendingOcrChoice)
                assertEquals(DraftStatus.CAPTURED, drafts.findDraft(draftId(2))?.status)
                assertEquals(2, drafts.resetOcrCalls.size)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `double recovery action launches a single compare and set`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activateBusiness()
            drafts.createDraft(draft(draftId(1), status = DraftStatus.OCR_PROCESSING))
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var calls = 0
            drafts.beforeResetInterruptedOcr = { _, _ ->
                calls += 1
                started.complete(Unit)
                release.await()
            }
            val viewModel = createViewModel()
            runCurrent()
            viewModel.onAction(HomeContract.Action.DraftSelected(draftId(1)))
            runCurrent()

            viewModel.effects.test {
                viewModel.onAction(HomeContract.Action.OcrResumeSelected)
                runCurrent()
                started.await()
                assertTrue(viewModel.uiState.value.isRecoveringOcr)

                viewModel.onAction(HomeContract.Action.OcrRetrySelected)
                runCurrent()
                assertEquals(1, calls)

                release.complete(Unit)
                runCurrent()
                assertEquals(HomeContract.Effect.OpenProcessing(draftId(1)), awaitItem())
                assertFalse(viewModel.uiState.value.isRecoveringOcr)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `stale recovery token keeps the dialog and reports a recoverable failure`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            activateBusiness()
            drafts.createDraft(draft(draftId(1), status = DraftStatus.OCR_PROCESSING))
            val viewModel = createViewModel()
            runCurrent()
            viewModel.onAction(HomeContract.Action.DraftSelected(draftId(1)))
            runCurrent()

            assertTrue(drafts.resetInterruptedOcr(draftId(1), expectedRunId = null))
            assertTrue(drafts.beginOcrRun(draftId(1), OCR_RUN_ID))

            viewModel.effects.test {
                viewModel.onAction(HomeContract.Action.OcrResumeSelected)
                runCurrent()

                assertEquals(HomeContract.Effect.ShowOcrRecoveryFailure, awaitItem())
                assertEquals(draftId(1), viewModel.uiState.value.draftIdPendingOcrChoice)
                assertEquals(OCR_RUN_ID, drafts.findDraft(draftId(1))?.activeOcrRunId)
                assertFalse(viewModel.uiState.value.isRecoveringOcr)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `pending dialogs are restored from the saved state handle`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel(
                savedStateHandle = SavedStateHandle(
                    mapOf(
                        "home.pendingDeletionDraftId" to draftId(1).value,
                        "home.pendingOcrChoiceDraftId" to draftId(2).value,
                        "home.pendingOcrChoiceRunId" to OCR_RUN_ID.value,
                    ),
                ),
            )

            assertEquals(draftId(1), viewModel.uiState.value.draftIdPendingDeletion)
            assertEquals(draftId(2), viewModel.uiState.value.draftIdPendingOcrChoice)
            assertEquals(OCR_RUN_ID, viewModel.uiState.value.ocrRunIdPendingChoice)
        }

    private fun createViewModel(
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ): HomeViewModel = HomeViewModel(
        savedStateHandle = savedStateHandle,
        observeHomeDashboardUseCase = ObserveHomeDashboardUseCase(
            configurationRepository = appConfig,
            businessRepository = businesses,
            dashboardReadRepository = FakeHomeDashboardReadRepository(
                FakeRecentDraftReadRepository(drafts, suppliers),
            ),
            clock = clock,
        ),
        deleteDraftUseCase = DeleteDraftUseCase(
            invoiceDraftRepository = drafts,
            draftFileStore = fileStore,
        ),
        retryDraftOcrUseCase = RetryDraftOcrUseCase(drafts),
        findDraftFirstImageUseCase = FindDraftFirstImageUseCase(drafts),
        startInvoiceDraftUseCase = StartInvoiceDraftUseCase(
            appConfigurationRepository = appConfig,
            invoiceDraftRepository = drafts,
            appClock = clock,
        ),
        uuidGenerator = UuidGenerator { GENERATED_UUID },
        dispatcherProvider = dispatchers,
    )

    private suspend fun activateBusiness() {
        appConfig.completeOnboarding(
            BUSINESS_ID,
            AppConfiguration.DEFAULT_TAX_RATE,
            AppConfiguration.DEFAULT_COST_POLICY,
        )
    }

    private fun advanceClock() {
        now = now.plusSeconds(60)
    }

    private fun draft(
        id: DraftId,
        status: DraftStatus = DraftStatus.CREATED,
        supplierId: SupplierId? = null,
        confirmedPurchaseId: PurchaseId? = null,
    ) = InvoiceDraft(
        draftId = id,
        businessId = BUSINESS_ID,
        status = status,
        supplierId = supplierId,
        confirmedPurchaseId = confirmedPurchaseId,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private fun image(id: ImageId, draftId: DraftId, pageIndex: Int) = InvoiceImage(
        imageId = id,
        draftId = draftId,
        businessId = BUSINESS_ID,
        pageIndex = pageIndex,
        filePath = "captures/${draftId.value}/page-$pageIndex.jpg",
        sha256 = "a".repeat(64),
        mimeType = "image/jpeg",
        widthPx = 3_000,
        heightPx = 4_000,
        fileSizeBytes = 1_000L,
        createdAt = Instant.EPOCH,
    )

    private companion object {
        val GENERATED_UUID: UUID = UUID.fromString("aaaaaaaa-0000-4000-8000-0000000000aa")
        val OCR_RUN_ID: OcrRunId = OcrRunId.from(
            UUID.fromString("aaaaaaaa-0000-4000-8000-0000000000ab"),
        )
        val BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("00000000-0000-0000-0000-0000000000b1"),
        )
        val SUPPLIER_ID: SupplierId = SupplierId.from(
            UUID.fromString("00000000-0000-0000-0000-0000000000c1"),
        )
        val PURCHASE_ID: PurchaseId = PurchaseId.from(
            UUID.fromString("00000000-0000-0000-0000-0000000000d1"),
        )

        fun uuid(seed: Int): UUID =
            UUID.fromString("00000000-0000-0000-0000-%012d".format(seed))

        fun draftId(seed: Int): DraftId = DraftId.from(uuid(seed))

        fun imageId(seed: Int): ImageId = ImageId.from(uuid(seed + 100))
    }
}
