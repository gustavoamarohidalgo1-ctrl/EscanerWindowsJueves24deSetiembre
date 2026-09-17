package com.facturastock.app.feature.ocr

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.model.Business
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.InvoiceImage
import com.facturastock.app.domain.model.InvoiceTextBlock
import com.facturastock.app.domain.model.InvoiceTextBoundingBox
import com.facturastock.app.domain.model.InvoiceTextDocument
import com.facturastock.app.domain.model.InvoiceTextGeometry
import com.facturastock.app.domain.model.InvoiceTextLine
import com.facturastock.app.domain.model.InvoiceTextPage
import com.facturastock.app.domain.model.UnitOfMeasure
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.model.id.OcrRunId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.repository.EnterManualInvoiceReviewResult
import com.facturastock.app.domain.repository.InvoiceDraftRepository
import com.facturastock.app.domain.repository.InvoiceTextRecognizer
import com.facturastock.app.domain.repository.OcrImageFile
import com.facturastock.app.domain.usecase.ApplyImageRetentionAfterOcrUseCase
import com.facturastock.app.domain.usecase.DeleteDraftUseCase
import com.facturastock.app.domain.usecase.EnterManualInvoiceReviewUseCase
import com.facturastock.app.domain.usecase.ImportScannedInvoiceProductsUseCase
import com.facturastock.app.domain.usecase.InvoiceOcrStage
import com.facturastock.app.domain.usecase.ObserveInvoiceDraftUseCase
import com.facturastock.app.domain.usecase.ParseInvoiceUseCase
import com.facturastock.app.domain.usecase.RetryDraftOcrUseCase
import com.facturastock.app.domain.usecase.RunInvoiceOcrUseCase
import com.facturastock.app.feature.common.RouteArgumentKeys
import com.facturastock.app.testing.FakeAppConfigurationRepository
import com.facturastock.app.testing.FakeBusinessRepository
import com.facturastock.app.testing.FakeDraftFileStore
import com.facturastock.app.testing.FakeInvoiceDraftRepository
import com.facturastock.app.testing.FakeInvoiceImagePreprocessor
import com.facturastock.app.testing.FakeInvoiceOcrSnapshotRepository
import com.facturastock.app.testing.FakeManualInvoiceReviewRepository
import com.facturastock.app.testing.FakeParsedInvoiceRepository
import com.facturastock.app.testing.FakeProductRepository
import com.facturastock.app.testing.FakeUnitRepository
import com.facturastock.app.testing.MainDispatcherRule
import com.facturastock.app.testing.TestDispatcherProvider
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
class OcrViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `auto start imports only catalog products deletes draft and opens products`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(pageCount = 2)
            val preparingStarted = CompletableDeferred<Unit>()
            val releasePreparing = CompletableDeferred<Unit>()
            val readingStarted = CompletableDeferred<Unit>()
            val releaseReading = CompletableDeferred<Unit>()
            val mergingStarted = CompletableDeferred<Unit>()
            val releaseMerging = CompletableDeferred<Unit>()
            fixture.preprocessor.beforePreprocess = {
                preparingStarted.complete(Unit)
                releasePreparing.await()
            }
            fixture.recognizer.beforeRecognizePage = { _, _ ->
                readingStarted.complete(Unit)
                releaseReading.await()
            }
            fixture.snapshots.beforePublish = {
                mergingStarted.complete(Unit)
                releaseMerging.await()
            }

            fixture.viewModel.effects.test {
                runCurrent()
                preparingStarted.await()
                assertRunningStage(fixture, InvoiceOcrStage.PREPARING)

                releasePreparing.complete(Unit)
                runCurrent()
                readingStarted.await()
                assertRunningStage(fixture, InvoiceOcrStage.READING)

                releaseReading.complete(Unit)
                runCurrent()
                mergingStarted.await()
                assertRunningStage(fixture, InvoiceOcrStage.MERGING_PAGES)

                releaseMerging.complete(Unit)
                runCurrent()
                assertEquals(OcrContract.Effect.OpenMatching(DRAFT_ID), awaitItem())
                assertEquals(
                    OcrContract.State(
                        draftId = DRAFT_ID,
                        completedPageCount = 2,
                        startedAt = NOW,
                    ),
                    fixture.viewModel.uiState.value,
                )
                assertEquals(1, fixture.snapshots.snapshotCount)
                assertNotNull(fixture.drafts.findDraft(DRAFT_ID))
                assertEquals(1, fixture.parsed.publications.size)
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `back to back start actions cannot replace the automatic run`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(pageCount = 1)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.preprocessor.beforePreprocess = {
                started.complete(Unit)
                release.await()
            }

            fixture.viewModel.onAction(OcrContract.Action.Start)
            fixture.viewModel.onAction(OcrContract.Action.Start)
            runCurrent()
            started.await()

            assertEquals(1, fixture.uuidGenerator.calls)
            assertEquals(DraftStatus.OCR_PROCESSING, fixture.drafts.findDraft(DRAFT_ID)?.status)
            fixture.viewModel.onAction(OcrContract.Action.Cancel)
            runCurrent()
        }

    @Test
    fun `duplicate cancel waits for cleanup and emits a single cancellation`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(pageCount = 1)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val cleanupStarted = CompletableDeferred<Unit>()
            val releaseCleanup = CompletableDeferred<Unit>()
            fixture.preprocessor.beforePreprocess = {
                started.complete(Unit)
                release.await()
            }
            fixture.controlledDrafts.beforeCancellationCleanup = {
                cleanupStarted.complete(Unit)
                releaseCleanup.await()
            }

            fixture.viewModel.effects.test {
                runCurrent()
                started.await()
                fixture.viewModel.onAction(OcrContract.Action.Cancel)
                fixture.viewModel.onAction(OcrContract.Action.Cancel)
                fixture.viewModel.onAction(OcrContract.Action.Start)
                runCurrent()

                cleanupStarted.await()
                assertFalse(fixture.viewModel.uiState.value.isRunning)
                assertTrue(fixture.viewModel.uiState.value.isCancelling)
                expectNoEvents()

                releaseCleanup.complete(Unit)
                runCurrent()
                assertEquals(OcrContract.Effect.Cancelled, awaitItem())
                assertEquals(
                    OcrContract.State(
                        draftId = DRAFT_ID,
                        startedAt = NOW,
                    ),
                    fixture.viewModel.uiState.value,
                )
                assertEquals(1, fixture.uuidGenerator.calls)
                assertEquals(0, fixture.recognizer.calls.size)
                assertEquals(DraftStatus.CAPTURED, fixture.drafts.findDraft(DRAFT_ID)?.status)
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `cancel is ignored once the atomic product save has started`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(pageCount = 1)
            val parseStarted = CompletableDeferred<Unit>()
            val releaseParse = CompletableDeferred<Unit>()
            fixture.snapshots.beforePublish = {
                parseStarted.complete(Unit)
                releaseParse.await()
            }

            fixture.viewModel.effects.test {
                runCurrent()
                parseStarted.await()
                assertTrue(fixture.viewModel.uiState.value.isRunning)

                releaseParse.complete(Unit)
                runCurrent()
                assertEquals(OcrContract.Effect.OpenMatching(DRAFT_ID), awaitItem())
                assertNotNull(fixture.drafts.findDraft(DRAFT_ID))
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `failure offers one retry with a fresh controlled timestamp`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(pageCount = 1)
            fixture.recognizer.nextException = IllegalStateException("scheduled")
            runCurrent()

            assertEquals(OcrContract.Failure.RECOGNITION_FAILED, fixture.viewModel.uiState.value.failure)
            assertFalse(fixture.viewModel.uiState.value.isRunning)
            assertEquals(DraftStatus.ERROR, fixture.drafts.findDraft(DRAFT_ID)?.status)
            fixture.clock.advanceSeconds(30)

            fixture.viewModel.effects.test {
                fixture.viewModel.onAction(OcrContract.Action.Retry)
                fixture.viewModel.onAction(OcrContract.Action.Retry)
                runCurrent()

                assertEquals(OcrContract.Effect.OpenMatching(DRAFT_ID), awaitItem())
                assertEquals(2, fixture.uuidGenerator.calls)
                assertEquals(2, fixture.recognizer.calls.size)
                assertEquals(NOW.plusSeconds(30), fixture.viewModel.uiState.value.startedAt)
                assertEquals(1, fixture.viewModel.uiState.value.completedPageCount)
                assertNull(fixture.viewModel.uiState.value.failure)
                assertNotNull(fixture.drafts.findDraft(DRAFT_ID))
                assertEquals(1, fixture.parsed.publications.size)
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `restored processing draft waits for explicit CAS recovery then resumes once from Room`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val interruptedRun = OcrRunId.from(UUID(0L, 90L))
            val fixture = fixture(
                pageCount = 2,
                initialStatus = DraftStatus.OCR_PROCESSING,
                initialRunId = interruptedRun,
            )

            fixture.viewModel.effects.test {
                runCurrent()

                assertEquals(OcrContract.Failure.INTERRUPTED, fixture.viewModel.uiState.value.failure)
                assertFalse(fixture.viewModel.uiState.value.isRunning)
                assertEquals(0, fixture.uuidGenerator.calls)
                assertEquals(0, fixture.recognizer.calls.size)
                assertEquals(2, fixture.drafts.observeImages(DRAFT_ID).first().size)

                fixture.viewModel.onAction(OcrContract.Action.Retry)
                fixture.viewModel.onAction(OcrContract.Action.Retry)
                runCurrent()

                assertEquals(OcrContract.Effect.OpenMatching(DRAFT_ID), awaitItem())
                assertEquals(listOf(DRAFT_ID to interruptedRun), fixture.drafts.resetOcrCalls)
                assertEquals(1, fixture.uuidGenerator.calls)
                assertEquals(1, fixture.recognizer.calls.size)
                assertEquals(2, fixture.recognizer.calls.single().size)
                assertNotNull(fixture.drafts.findDraft(DRAFT_ID))
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `stale interrupted token cannot clear or duplicate a newer OCR run`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val interruptedRun = OcrRunId.from(UUID(0L, 91L))
            val newerRun = OcrRunId.from(UUID(0L, 92L))
            val fixture = fixture(
                pageCount = 1,
                initialStatus = DraftStatus.OCR_PROCESSING,
                initialRunId = interruptedRun,
            )
            runCurrent()
            fixture.drafts.beforeResetInterruptedOcr = { draftId, _ ->
                val current = requireNotNull(fixture.drafts.findDraft(draftId))
                fixture.drafts.updateDraft(current.copy(activeOcrRunId = newerRun))
            }

            fixture.viewModel.effects.test {
                fixture.viewModel.onAction(OcrContract.Action.Retry)
                fixture.viewModel.onAction(OcrContract.Action.Retry)
                runCurrent()

                expectNoEvents()
                assertEquals(listOf(DRAFT_ID to interruptedRun), fixture.drafts.resetOcrCalls)
                assertEquals(DraftStatus.OCR_PROCESSING, fixture.drafts.findDraft(DRAFT_ID)?.status)
                assertEquals(newerRun, fixture.drafts.findDraft(DRAFT_ID)?.activeOcrRunId)
                assertEquals(OcrContract.Failure.INTERRUPTED, fixture.viewModel.uiState.value.failure)
                assertFalse(fixture.viewModel.uiState.value.isRecoveringInterruptedOcr)
                assertEquals(0, fixture.uuidGenerator.calls)
                assertEquals(0, fixture.recognizer.calls.size)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `recognition failure enters durable manual review before navigation`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(pageCount = 1)
            fixture.recognizer.nextException = IllegalStateException("scheduled")
            runCurrent()

            assertEquals(OcrContract.Failure.RECOGNITION_FAILED, fixture.viewModel.uiState.value.failure)
            assertEquals(DraftStatus.ERROR, fixture.drafts.findDraft(DRAFT_ID)?.status)
            assertEquals(0, fixture.snapshots.snapshotCount)

            fixture.viewModel.effects.test {
                fixture.viewModel.onAction(OcrContract.Action.EnterManually)
                fixture.viewModel.onAction(OcrContract.Action.EnterManually)
                runCurrent()

                assertEquals(OcrContract.Effect.OpenManualReview(DRAFT_ID), awaitItem())
                assertEquals(listOf(DRAFT_ID), fixture.manualReview.calls)
                assertEquals(setOf(DRAFT_ID), fixture.manualReview.emptyHeaderDrafts)
                assertEquals(setOf(DRAFT_ID), fixture.manualReview.emptyLinesDrafts)
                assertEquals(DraftStatus.NEEDS_REVIEW, fixture.drafts.findDraft(DRAFT_ID)?.status)
                assertNull(fixture.drafts.findDraft(DRAFT_ID)?.activeOcrRunId)
                assertFalse(fixture.viewModel.uiState.value.isEnteringManually)
                assertFalse(fixture.viewModel.uiState.value.manualEntryFailed)
                assertEquals(0, fixture.parsed.publications.size)
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `manual fallback does not navigate when atomic transition is unavailable`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(pageCount = 1)
            fixture.recognizer.nextException = IllegalStateException("scheduled")
            fixture.manualReview.nextResult = EnterManualInvoiceReviewResult.NOT_AVAILABLE
            runCurrent()

            fixture.viewModel.effects.test {
                fixture.viewModel.onAction(OcrContract.Action.EnterManually)
                runCurrent()

                expectNoEvents()
                assertTrue(fixture.viewModel.uiState.value.manualEntryFailed)
                assertFalse(fixture.viewModel.uiState.value.isEnteringManually)
                assertEquals(DraftStatus.ERROR, fixture.drafts.findDraft(DRAFT_ID)?.status)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `manual action is single flight while durable transition is pending`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(pageCount = 1)
            fixture.recognizer.nextException = IllegalStateException("scheduled")
            runCurrent()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.manualReview.beforeEnter = {
                entered.complete(Unit)
                release.await()
            }

            fixture.viewModel.effects.test {
                fixture.viewModel.onAction(OcrContract.Action.EnterManually)
                fixture.viewModel.onAction(OcrContract.Action.EnterManually)
                runCurrent()
                entered.await()

                assertEquals(listOf(DRAFT_ID), fixture.manualReview.calls)
                assertTrue(fixture.viewModel.uiState.value.isEnteringManually)
                expectNoEvents()

                release.complete(Unit)
                runCurrent()
                assertEquals(OcrContract.Effect.OpenManualReview(DRAFT_ID), awaitItem())
                assertEquals(listOf(DRAFT_ID), fixture.manualReview.calls)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `parse failure retries the existing snapshot without recognizing pages again`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(pageCount = 1, failFirstParse = true)
            runCurrent()

            assertEquals(OcrContract.Failure.PARSING_FAILED, fixture.viewModel.uiState.value.failure)
            assertFalse(fixture.viewModel.uiState.value.isRunning)
            assertEquals(DraftStatus.OCR_READY, fixture.drafts.findDraft(DRAFT_ID)?.status)
            assertEquals(1, fixture.snapshots.snapshotCount)
            assertEquals(1, fixture.recognizer.calls.size)
            assertEquals(1, fixture.uuidGenerator.calls)
            assertEquals(0, fixture.parsed.publications.size)

            fixture.viewModel.effects.test {
                fixture.viewModel.onAction(OcrContract.Action.Retry)
                fixture.viewModel.onAction(OcrContract.Action.Retry)
                runCurrent()

                assertEquals(OcrContract.Effect.OpenMatching(DRAFT_ID), awaitItem())
                assertNull(fixture.viewModel.uiState.value.failure)
                assertNotNull(fixture.drafts.findDraft(DRAFT_ID))
                assertEquals(1, fixture.recognizer.calls.size)
                assertEquals(1, fixture.uuidGenerator.calls)
                assertEquals(1, fixture.parsed.publications.size)
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `parse failure can preserve OCR evidence and navigate to manual header review`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(pageCount = 1, failFirstParse = true)
            runCurrent()

            assertEquals(OcrContract.Failure.PARSING_FAILED, fixture.viewModel.uiState.value.failure)
            assertEquals(DraftStatus.OCR_READY, fixture.drafts.findDraft(DRAFT_ID)?.status)
            assertEquals(1, fixture.snapshots.snapshotCount)
            assertEquals(0, fixture.parsed.publications.size)

            fixture.viewModel.effects.test {
                fixture.viewModel.onAction(OcrContract.Action.EnterManually)
                runCurrent()

                assertEquals(OcrContract.Effect.OpenManualReview(DRAFT_ID), awaitItem())
                assertEquals(DraftStatus.NEEDS_REVIEW, fixture.drafts.findDraft(DRAFT_ID)?.status)
                assertEquals(1, fixture.snapshots.snapshotCount)
                assertEquals(setOf(DRAFT_ID), fixture.manualReview.emptyHeaderDrafts)
                assertEquals(setOf(DRAFT_ID), fixture.manualReview.emptyLinesDrafts)
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `invoice without eligible products stays recoverable and keeps its draft`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(pageCount = 1)
            fixture.recognizer.includeProductRow = false

            fixture.viewModel.effects.test {
                runCurrent()

                expectNoEvents()
                assertEquals(
                    OcrContract.Failure.NO_PRODUCTS_FOUND,
                    fixture.viewModel.uiState.value.failure,
                )
                assertFalse(fixture.viewModel.uiState.value.isRunning)
                assertFalse(fixture.viewModel.uiState.value.isSavingProducts)
                assertEquals(DraftStatus.NEEDS_REVIEW, fixture.drafts.findDraft(DRAFT_ID)?.status)
                assertEquals(1, fixture.snapshots.snapshotCount)
                assertEquals(1, fixture.parsed.publications.size)
                assertTrue(fixture.products.observeForBusiness(BUSINESS_ID).first().isEmpty())
                assertTrue(fixture.fileStore.draftTreeDeletions.isEmpty())

                fixture.viewModel.onAction(OcrContract.Action.BackSelected)
                runCurrent()
                assertEquals(OcrContract.Effect.Back, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `invalid route does not auto start and Start closes it`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(
                pageCount = 1,
                savedStateHandle = SavedStateHandle(
                    mapOf(RouteArgumentKeys.DRAFT_ID to "not-a-draft-id"),
                ),
            )

            fixture.viewModel.effects.test {
                runCurrent()
                assertEquals(OcrContract.Failure.INVALID_DRAFT_ID, fixture.viewModel.uiState.value.failure)
                assertEquals(0, fixture.uuidGenerator.calls)

                fixture.viewModel.onAction(OcrContract.Action.Start)
                runCurrent()
                assertEquals(OcrContract.Effect.CloseInvalidRoute, awaitItem())
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `valid uuid without a Room draft closes once and never starts OCR`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(pageCount = 1, createDraft = false)

            fixture.viewModel.effects.test {
                runCurrent()
                assertEquals(OcrContract.Effect.CloseInvalidRoute, awaitItem())
                assertEquals(
                    OcrContract.Failure.INVALID_DRAFT_ID,
                    fixture.viewModel.uiState.value.failure,
                )
                assertEquals(0, fixture.uuidGenerator.calls)

                fixture.viewModel.onAction(OcrContract.Action.Start)
                runCurrent()
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `restored post-parse draft retries product import without rerunning OCR`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val fixture = fixture(pageCount = 1, initialStatus = DraftStatus.NEEDS_REVIEW)

            fixture.viewModel.effects.test {
                runCurrent()
                assertEquals(OcrContract.Effect.OpenMatching(DRAFT_ID), awaitItem())
                assertEquals(0, fixture.uuidGenerator.calls)
                assertEquals(0, fixture.recognizer.calls.size)
                assertNotNull(fixture.drafts.findDraft(DRAFT_ID))

                fixture.viewModel.onAction(OcrContract.Action.Start)
                runCurrent()
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    private fun assertRunningStage(fixture: Fixture, expected: InvoiceOcrStage) {
        val state = fixture.viewModel.uiState.value
        assertEquals(expected, state.stage)
        assertTrue(state.isRunning)
        assertFalse(state.isCancelling)
        assertEquals(0, state.completedPageCount)
        assertNull(state.failure)
    }

    private suspend fun fixture(
        pageCount: Int,
        failFirstParse: Boolean = false,
        initialStatus: DraftStatus = DraftStatus.CAPTURED,
        initialRunId: OcrRunId? = null,
        createDraft: Boolean = true,
        savedStateHandle: SavedStateHandle = SavedStateHandle(
            mapOf(RouteArgumentKeys.DRAFT_ID to DRAFT_ID.value),
        ),
    ): Fixture {
        val clock = MutableTestClock(NOW)
        val drafts = FakeInvoiceDraftRepository(clock)
        if (createDraft) {
            drafts.createDraft(
                InvoiceDraft(
                    draftId = DRAFT_ID,
                    businessId = BUSINESS_ID,
                    status = if (initialStatus == DraftStatus.NEEDS_REVIEW) {
                        DraftStatus.CAPTURED
                    } else {
                        initialStatus
                    },
                    activeOcrRunId = initialRunId,
                    createdAt = NOW,
                    updatedAt = NOW,
                ),
            )
            repeat(pageCount) { index ->
                drafts.seedImage(sourceImage(index))
            }
        }
        val preprocessor = FakeInvoiceImagePreprocessor()
        val recognizer = StructuredInvoiceTextRecognizer()
        val snapshots = FakeInvoiceOcrSnapshotRepository(drafts)
        val parsed = FakeParsedInvoiceRepository(drafts)
        val manualReview = FakeManualInvoiceReviewRepository(drafts)
        val configuration = FakeAppConfigurationRepository()
        configuration.completeOnboarding(
            businessId = BUSINESS_ID,
            taxRate = AppConfiguration.DEFAULT_TAX_RATE,
            costPolicy = AppConfiguration.DEFAULT_COST_POLICY,
        )
        val businesses = FakeBusinessRepository(clock)
        businesses.create(
            Business(
                businessId = BUSINESS_ID,
                legalName = "Negocio OCR",
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )
        val products = FakeProductRepository(clock)
        val units = FakeUnitRepository(clock, products)
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
        val uuidGenerator = CountingUuidGenerator()
        val productUuidGenerator = CountingUuidGenerator(firstValue = 100L)
        val controlledDrafts = ControlledCleanupDraftRepository(drafts)
        val fileStore = FakeDraftFileStore()
        val dispatchers = TestDispatcherProvider(main = mainDispatcherRule.dispatcher)
        val runInvoiceOcrUseCase = RunInvoiceOcrUseCase(
            invoiceDraftRepository = controlledDrafts,
            invoiceImagePreprocessor = preprocessor,
            invoiceTextRecognizer = recognizer,
            invoiceOcrSnapshotRepository = snapshots,
            uuidGenerator = uuidGenerator,
            appClock = clock,
            // Repositorio propio: el hook lee la configuración al publicar el run y no debe
            // consumir el guion de fallos que la prueba programa para el parseo.
            applyImageRetentionAfterOcr = ApplyImageRetentionAfterOcrUseCase(
                FakeAppConfigurationRepository(),
                FakeDraftFileStore(),
                controlledDrafts,
            ),
        )
        val parseInvoiceUseCase = ParseInvoiceUseCase(
            invoiceDraftRepository = controlledDrafts,
            invoiceOcrSnapshotRepository = snapshots,
            parsedInvoiceRepository = parsed,
            appConfigurationRepository = configuration,
            businessRepository = businesses,
            appClock = clock,
            dispatcherProvider = dispatchers,
        )
        if (createDraft && initialStatus == DraftStatus.NEEDS_REVIEW) {
            runInvoiceOcrUseCase(DRAFT_ID)
            parseInvoiceUseCase(DRAFT_ID)
            recognizer.clearRecordedCalls()
            uuidGenerator.reset()
        }
        if (failFirstParse) configuration.nextFailure = StorageError.Unavailable
        val importProductsUseCase = ImportScannedInvoiceProductsUseCase(
            appConfigurationRepository = configuration,
            businessRepository = businesses,
            invoiceDraftRepository = controlledDrafts,
            productRepository = products,
            unitRepository = units,
            uuidGenerator = productUuidGenerator,
            appClock = clock,
            deleteDraftUseCase = DeleteDraftUseCase(controlledDrafts, fileStore),
        )
        return Fixture(
            clock = clock,
            drafts = drafts,
            controlledDrafts = controlledDrafts,
            preprocessor = preprocessor,
            recognizer = recognizer,
            snapshots = snapshots,
            parsed = parsed,
            manualReview = manualReview,
            products = products,
            fileStore = fileStore,
            uuidGenerator = uuidGenerator,
            viewModel = OcrViewModel(
                savedStateHandle = savedStateHandle,
                runInvoiceOcrUseCase = runInvoiceOcrUseCase,
                parseInvoiceUseCase = parseInvoiceUseCase,
                importScannedInvoiceProductsUseCase = importProductsUseCase,
                enterManualInvoiceReviewUseCase = EnterManualInvoiceReviewUseCase(manualReview),
                observeInvoiceDraftUseCase = ObserveInvoiceDraftUseCase(controlledDrafts),
                retryDraftOcrUseCase = RetryDraftOcrUseCase(controlledDrafts),
                appClock = clock,
                dispatcherProvider = dispatchers,
            ),
        )
    }

    private fun sourceImage(index: Int): InvoiceImage {
        val imageId = ImageId.from(UUID(0L, index + 1L))
        return InvoiceImage(
            imageId = imageId,
            draftId = DRAFT_ID,
            businessId = BUSINESS_ID,
            pageIndex = index,
            filePath = "draft_images/${DRAFT_ID.value}/${imageId.value}.jpg",
            sha256 = (index + 1).toString().repeat(64).take(64),
            mimeType = "image/jpeg",
            widthPx = 1_200,
            heightPx = 1_600,
            fileSizeBytes = 1_000L,
            createdAt = NOW,
        )
    }

    private class MutableTestClock(private var current: Instant) : AppClock {
        override fun now(): Instant = current

        fun advanceSeconds(seconds: Long) {
            current = current.plusSeconds(seconds)
        }
    }

    private class CountingUuidGenerator(
        private val firstValue: Long = 30L,
    ) : UuidGenerator {
        var calls: Int = 0
            private set

        override fun newUuid(): UUID {
            calls += 1
            return UUID(0L, firstValue + calls)
        }

        fun reset() {
            calls = 0
        }
    }

    /**
     * OCR geométrico mínimo que atraviesa el parser real. Solo la primera página aporta una fila
     * de producto; las restantes siguen siendo documentos válidos sin duplicar el catálogo.
     */
    private class StructuredInvoiceTextRecognizer : InvoiceTextRecognizer {
        private val recordedCalls = mutableListOf<List<OcrImageFile>>()

        var includeProductRow: Boolean = true
        var beforeRecognizePage: suspend (pageIndex: Int, page: OcrImageFile) -> Unit = { _, _ -> }
        var nextException: Exception? = null

        val calls: List<List<OcrImageFile>>
            get() = recordedCalls.map(List<OcrImageFile>::toList)

        override suspend fun recognize(pages: List<OcrImageFile>): InvoiceTextDocument {
            recordedCalls += pages.toList()
            return InvoiceTextDocument(
                pages.mapIndexed { pageIndex, page ->
                    beforeRecognizePage(pageIndex, page)
                    nextException?.let { scheduled ->
                        nextException = null
                        throw scheduled
                    }
                    if (pageIndex == 0 && includeProductRow) {
                        page.productInvoicePage(pageIndex)
                    } else {
                        page.genericInvoicePage(pageIndex)
                    }
                },
            )
        }

        fun clearRecordedCalls() {
            recordedCalls.clear()
        }

        private fun OcrImageFile.productInvoicePage(pageIndex: Int): InvoiceTextPage {
            val cells = listOf(
                OcrCell("COMERCIAL ANDINA S.A.C.", 40, 35, 560, 70),
                OcrCell("RUC: 20131312955", 40, 80, 370, 115),
                OcrCell("FACTURA ELECTRÓNICA", 650, 35, 1_160, 70),
                OcrCell("F001-12345", 760, 85, 1_050, 120),
                OcrCell("Fecha de emisión: 17/08/2026", 650, 140, 1_160, 175),
                OcrCell("Moneda: PEN", 650, 190, 1_000, 225),
                OcrCell("DESCRIPCIÓN", 60, 300, 650, 335),
                OcrCell("CANTIDAD", 680, 300, 800, 335),
                OcrCell("IMPORTE", 900, 300, 1_150, 335),
                OcrCell("ARROZ EXTRA 5 KG", 60, 365, 650, 400),
                OcrCell("2", 680, 365, 800, 400),
                OcrCell("S/ 100.00", 900, 365, 1_150, 400),
                OcrCell("OP. GRAVADA", 650, 900, 930, 935),
                OcrCell("S/ 100.00", 960, 900, 1_160, 935),
                OcrCell("IGV 18%", 650, 960, 930, 995),
                OcrCell("S/ 18.00", 960, 960, 1_160, 995),
                OcrCell("TOTAL A PAGAR", 650, 1_020, 930, 1_055),
                OcrCell("S/ 118.00", 960, 1_020, 1_160, 1_055),
            )
            return page(pageIndex, cells)
        }

        private fun OcrImageFile.genericInvoicePage(pageIndex: Int): InvoiceTextPage = page(
            pageIndex = pageIndex,
            cells = listOf(OcrCell("FACTURA PAGINA ${pageIndex + 1}", 20, 20, 400, 60)),
        )

        private fun OcrImageFile.page(
            pageIndex: Int,
            cells: List<OcrCell>,
        ): InvoiceTextPage {
            val blocks = cells.mapIndexed { position, cell ->
                val geometry = InvoiceTextGeometry(
                    boundingBox = InvoiceTextBoundingBox(
                        leftPx = cell.left,
                        topPx = cell.top,
                        rightPx = cell.right,
                        bottomPx = cell.bottom,
                    ),
                    cornerPoints = emptyList(),
                )
                InvoiceTextBlock(
                    position = position,
                    text = cell.text,
                    languageTag = "es-PE",
                    geometry = geometry,
                    lines = listOf(
                        InvoiceTextLine(
                            position = 0,
                            text = cell.text,
                            languageTag = "es-PE",
                            geometry = geometry,
                            confidencePermille = 950,
                            clockwiseAngleTenths = 0,
                            elements = emptyList(),
                        ),
                    ),
                )
            }
            return InvoiceTextPage(
                sourceImageId = sourceImageId,
                pageIndex = pageIndex,
                widthPx = widthPx,
                heightPx = heightPx,
                text = cells.joinToString("\n", transform = OcrCell::text),
                blocks = blocks,
            )
        }
    }

    private data class OcrCell(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    private class ControlledCleanupDraftRepository(
        private val delegate: InvoiceDraftRepository,
    ) : InvoiceDraftRepository by delegate {
        var beforeCancellationCleanup: suspend () -> Unit = {}

        override suspend fun finishOcrRun(
            draftId: DraftId,
            runId: OcrRunId,
            newStatus: DraftStatus,
            lastError: String?,
        ): Boolean {
            if (newStatus == DraftStatus.CAPTURED) beforeCancellationCleanup()
            return delegate.finishOcrRun(draftId, runId, newStatus, lastError)
        }
    }

    private data class Fixture(
        val clock: MutableTestClock,
        val drafts: FakeInvoiceDraftRepository,
        val controlledDrafts: ControlledCleanupDraftRepository,
        val preprocessor: FakeInvoiceImagePreprocessor,
        val recognizer: StructuredInvoiceTextRecognizer,
        val snapshots: FakeInvoiceOcrSnapshotRepository,
        val parsed: FakeParsedInvoiceRepository,
        val manualReview: FakeManualInvoiceReviewRepository,
        val products: FakeProductRepository,
        val fileStore: FakeDraftFileStore,
        val uuidGenerator: CountingUuidGenerator,
        val viewModel: OcrViewModel,
    )

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-09T12:00:00Z")
        val DRAFT_ID: DraftId = DraftId.from(UUID(0L, 10L))
        val BUSINESS_ID: BusinessId = BusinessId.from(UUID(0L, 20L))
        val UNIT_ID: UnitId = UnitId.from(UUID(0L, 21L))
    }
}
