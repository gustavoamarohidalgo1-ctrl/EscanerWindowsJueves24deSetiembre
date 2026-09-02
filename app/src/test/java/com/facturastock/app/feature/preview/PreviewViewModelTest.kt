package com.facturastock.app.feature.preview

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.ImageCrop
import com.facturastock.app.domain.model.ImageQualityReport
import com.facturastock.app.domain.model.ImageQualityWarning
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.InvoiceImage
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.repository.InvoiceDraftRepository
import com.facturastock.app.domain.usecase.AnalyzeDraftImagesUseCase
import com.facturastock.app.domain.usecase.CropDraftImageUseCase
import com.facturastock.app.domain.usecase.DeleteDraftImageUseCase
import com.facturastock.app.domain.usecase.ObserveDraftImagesUseCase
import com.facturastock.app.domain.usecase.ReorderDraftImagesUseCase
import com.facturastock.app.domain.usecase.RotateDraftImageUseCase
import com.facturastock.app.testing.FakeDraftFileStore
import com.facturastock.app.testing.FakeImageQualityAnalyzer
import com.facturastock.app.testing.FakeInvoiceDraftRepository
import com.facturastock.app.testing.MainDispatcherRule
import com.facturastock.app.testing.TestDispatcherProvider
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PreviewViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val clock = AppClock { Instant.parse("2026-08-01T12:00:00Z") }
    private val fileStore = FakeDraftFileStore()
    private val drafts = FakeInvoiceDraftRepository(clock)
    private val qualityAnalyzer = FakeImageQualityAnalyzer()
    private val dispatchers = TestDispatcherProvider(main = mainDispatcherRule.dispatcher)

    @Test
    fun `an invalid route draft id fails the state and Start closes the route`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel(
                savedStateHandle = SavedStateHandle(mapOf("draftId" to "no-es-un-uuid")),
            )
            assertEquals(PreviewContract.Failure.INVALID_ROUTE, viewModel.uiState.value.failure)

            viewModel.effects.test {
                viewModel.onAction(PreviewContract.Action.Start)
                runCurrent()
                assertEquals(PreviewContract.Effect.CloseInvalidRoute, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `pages arrive from the repository and the route capture selects the initial page`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            seedDraftWithPages(3)
            val viewModel = createViewModel(
                savedStateHandle = SavedStateHandle(
                    mapOf(
                        "draftId" to DRAFT_ID.value,
                        "captureId" to PAGE_IDS[1].value,
                    ),
                ),
            )
            runCurrent()

            val state = viewModel.uiState.value
            assertEquals(3, state.pages.size)
            assertEquals(1, state.currentIndex)
            assertEquals(PAGE_IDS[1], state.currentPage?.imageId)
            assertEquals(90, state.currentPage?.rotationDegrees)
        }

    @Test
    fun `saved page selection wins over immutable capture argument after process recreation`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            seedDraftWithPages(3)
            val viewModel = createViewModel(
                savedStateHandle = SavedStateHandle(
                    mapOf(
                        "draftId" to DRAFT_ID.value,
                        "captureId" to PAGE_IDS[0].value,
                        "preview.currentIndex" to 2,
                    ),
                ),
            )
            runCurrent()

            assertEquals(2, viewModel.uiState.value.currentIndex)
            assertEquals(PAGE_IDS[2], viewModel.uiState.value.currentPage?.imageId)
        }

    @Test
    fun `fallo inicial de paginas expone error y RetryLoad reinicia la coleccion`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            seedDraftWithPages(2)
            drafts.observeImagesFailure = IllegalStateException("images unavailable")
            val viewModel = createViewModel()
            runCurrent()

            assertEquals(PreviewContract.Failure.LOAD_FAILED, viewModel.uiState.value.failure)
            assertTrue(viewModel.uiState.value.pages.isEmpty())

            drafts.observeImagesFailure = null
            viewModel.onAction(PreviewContract.Action.RetryLoad)
            runCurrent()

            assertNull(viewModel.uiState.value.failure)
            assertEquals(2, viewModel.uiState.value.pages.size)
        }

    @Test
    fun `rotate persists the new rotation and the flow reflects it`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            seedDraftWithPages(2)
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(PreviewContract.Action.RotateClicked)
            runCurrent()

            assertEquals(90, drafts.findImage(PAGE_IDS[0])?.rotationDegrees)
            assertEquals(90, viewModel.uiState.value.currentPage?.rotationDegrees)
        }

    @Test
    fun `failed page edit is visible and a later retry clears the warning`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            seedDraftWithPages(1)
            var shouldFail = true
            val failingOnce = object : InvoiceDraftRepository by drafts {
                override suspend fun rotateImage90(imageId: ImageId): InvoiceImage? {
                    if (shouldFail) throw IllegalStateException("storage unavailable")
                    return drafts.rotateImage90(imageId)
                }
            }
            val viewModel = createViewModel(failingOnce)
            runCurrent()

            viewModel.onAction(PreviewContract.Action.RotateClicked)
            runCurrent()

            assertEquals(
                PreviewContract.Failure.MUTATION_FAILED,
                viewModel.uiState.value.failure,
            )
            assertEquals(0, drafts.findImage(PAGE_IDS[0])?.rotationDegrees)

            shouldFail = false
            viewModel.onAction(PreviewContract.Action.RotateClicked)
            runCurrent()

            assertNull(viewModel.uiState.value.failure)
            assertEquals(90, drafts.findImage(PAGE_IDS[0])?.rotationDegrees)
        }

    @Test
    fun `doble toque de mutacion se serializa y procesar espera su commit`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            seedDraftWithPages(1)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var rotations = 0
            val blockingRepository = object : InvoiceDraftRepository by drafts {
                override suspend fun rotateImage90(imageId: ImageId): InvoiceImage? {
                    rotations++
                    started.complete(Unit)
                    release.await()
                    return drafts.rotateImage90(imageId)
                }
            }
            val viewModel = createViewModel(blockingRepository)
            runCurrent()

            viewModel.onAction(PreviewContract.Action.RotateClicked)
            viewModel.onAction(PreviewContract.Action.RotateClicked)
            runCurrent()
            started.await()

            assertEquals(1, rotations)
            assertTrue(viewModel.uiState.value.isMutating)
            viewModel.onAction(PreviewContract.Action.ProcessClicked)
            runCurrent()
            assertTrue(qualityAnalyzer.analyses.isEmpty())

            release.complete(Unit)
            runCurrent()

            assertEquals(90, drafts.findImage(PAGE_IDS[0])?.rotationDegrees)
            assertEquals(1, rotations)
            assertTrue(!viewModel.uiState.value.isMutating)
        }

    @Test
    fun `a cancelled mutation releases its claim and the next edit can proceed`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            seedDraftWithPages(1)
            var rotations = 0
            val cancelOnce = object : InvoiceDraftRepository by drafts {
                override suspend fun rotateImage90(imageId: ImageId): InvoiceImage? {
                    rotations++
                    if (rotations == 1) throw CancellationException("cancelled mutation")
                    return drafts.rotateImage90(imageId)
                }
            }
            val viewModel = createViewModel(cancelOnce)
            runCurrent()

            viewModel.onAction(PreviewContract.Action.RotateClicked)
            runCurrent()
            assertTrue(!viewModel.uiState.value.isMutating)

            viewModel.onAction(PreviewContract.Action.RotateClicked)
            runCurrent()

            assertEquals(2, rotations)
            assertEquals(90, drafts.findImage(PAGE_IDS[0])?.rotationDegrees)
            assertTrue(!viewModel.uiState.value.isMutating)
        }

    @Test
    fun `crop editing clamps corners to the frame and confirm persists the rectangle`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            seedDraftWithPages(1)
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(PreviewContract.Action.CropClicked)
            runCurrent()
            assertEquals(PreviewContract.Mode.CROPPING, viewModel.uiState.value.mode)
            assertNull(drafts.findImage(PAGE_IDS[0])?.crop)

            // Arrastres fuera de rango quedan clampados: ni negativos ni invertidos.
            viewModel.onAction(
                PreviewContract.Action.CropCornerDragged(
                    corner = PreviewContract.CropCorner.TOP_LEFT,
                    xFraction = -500,
                    yFraction = 2_000,
                ),
            )
            runCurrent()
            viewModel.onAction(
                PreviewContract.Action.CropCornerDragged(
                    corner = PreviewContract.CropCorner.BOTTOM_RIGHT,
                    xFraction = 12_000,
                    yFraction = 11_000,
                ),
            )
            runCurrent()
            val draft = viewModel.uiState.value.cropDraft!!
            assertEquals(0, draft.left)
            assertEquals(2_000, draft.top)
            assertEquals(ImageCrop.FRACTION_MAX, draft.right)
            assertEquals(ImageCrop.FRACTION_MAX, draft.bottom)

            viewModel.onAction(PreviewContract.Action.CropConfirmed)
            runCurrent()
            assertEquals(draft, drafts.findImage(PAGE_IDS[0])?.crop)
            assertEquals(PreviewContract.Mode.VIEWING, viewModel.uiState.value.mode)

            // Un recorte de imagen completa se persiste como sin recorte.
            viewModel.onAction(PreviewContract.Action.CropClicked)
            runCurrent()
            viewModel.onAction(
                PreviewContract.Action.CropCornerDragged(
                    corner = PreviewContract.CropCorner.TOP_LEFT,
                    xFraction = 0,
                    yFraction = 0,
                ),
            )
            runCurrent()
            viewModel.onAction(PreviewContract.Action.CropConfirmed)
            runCurrent()
            assertNull(drafts.findImage(PAGE_IDS[0])?.crop)
        }

    @Test
    fun `crop cancel discards the draft rectangle without persisting`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            seedDraftWithPages(1)
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(PreviewContract.Action.CropClicked)
            runCurrent()
            viewModel.onAction(
                PreviewContract.Action.CropCornerDragged(
                    corner = PreviewContract.CropCorner.TOP_LEFT,
                    xFraction = 1_000,
                    yFraction = 1_000,
                ),
            )
            runCurrent()
            viewModel.onAction(PreviewContract.Action.CropCancelled)
            runCurrent()

            assertEquals(PreviewContract.Mode.VIEWING, viewModel.uiState.value.mode)
            assertNull(viewModel.uiState.value.cropDraft)
            assertNull(drafts.findImage(PAGE_IDS[0])?.crop)
        }

    @Test
    fun `retake and add page navigate to source with and without replace target`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            seedDraftWithPages(2)
            val viewModel = createViewModel()
            runCurrent()

            viewModel.effects.test {
                viewModel.onAction(PreviewContract.Action.RetakeClicked)
                runCurrent()
                assertEquals(
                    PreviewContract.Effect.OpenSource(DRAFT_ID, replaceImageId = PAGE_IDS[0]),
                    awaitItem(),
                )

                viewModel.onAction(PreviewContract.Action.AddPageClicked)
                runCurrent()
                assertEquals(
                    PreviewContract.Effect.OpenSource(DRAFT_ID, replaceImageId = null),
                    awaitItem(),
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `reorder moves the current page and keeps it selected`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            seedDraftWithPages(3)
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(PreviewContract.Action.MoveDownClicked)
            runCurrent()

            assertEquals(
                listOf(PAGE_IDS[1], PAGE_IDS[0], PAGE_IDS[2]),
                drafts.observeImages(DRAFT_ID).first().map(InvoiceImage::imageId),
            )
            assertEquals(1, viewModel.uiState.value.currentIndex)
        }

    @Test
    fun `delete asks confirmation and removing the page reindexes and deletes its file`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            seedDraftWithPages(2)
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(PreviewContract.Action.DeleteClicked)
            runCurrent()
            assertTrue(viewModel.uiState.value.showDeleteConfirm)

            viewModel.onAction(PreviewContract.Action.DeleteConfirmed)
            runCurrent()

            val remaining = drafts.observeImages(DRAFT_ID).first()
            assertEquals(listOf(PAGE_IDS[1]), remaining.map(InvoiceImage::imageId))
            assertEquals(0, remaining.single().pageIndex)
            assertEquals(
                listOf(listOf("draft_images/${DRAFT_ID.value}/p0.jpg")),
                fileStore.deletions,
            )
        }

    @Test
    fun `deleting the last page returns to source for a fresh capture`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            seedDraftWithPages(1)
            val viewModel = createViewModel()
            runCurrent()

            viewModel.effects.test {
                viewModel.onAction(PreviewContract.Action.DeleteClicked)
                runCurrent()
                viewModel.onAction(PreviewContract.Action.DeleteConfirmed)
                runCurrent()

                assertEquals(DraftStatus.CREATED, drafts.findDraft(DRAFT_ID)?.status)
                assertEquals(
                    PreviewContract.Effect.OpenSource(DRAFT_ID, replaceImageId = null),
                    awaitItem(),
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `process analyzes pages then delegates the complete pipeline to the OCR step`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            seedDraftWithPages(2)
            val viewModel = createViewModel()
            runCurrent()

            viewModel.effects.test {
                viewModel.onAction(PreviewContract.Action.ProcessClicked)
                runCurrent()

                assertEquals(PAGE_IDS.take(2), qualityAnalyzer.analyses)
                // El preprocesado pertenece al orquestador del siguiente destino.
                assertEquals(PreviewContract.Effect.OpenProcessing(DRAFT_ID), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `a quality analysis failure keeps the pages and offers retry without navigating`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            seedDraftWithPages(1)
            qualityAnalyzer.nextException = com.facturastock.app.domain.error.FileException(
                com.facturastock.app.domain.error.FileError.InsufficientSpace,
            )
            val viewModel = createViewModel()
            runCurrent()

            viewModel.effects.test {
                viewModel.onAction(PreviewContract.Action.ProcessClicked)
                runCurrent()

                assertTrue(viewModel.uiState.value.processFailed)
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `processing cannot start while a crop draft is being edited`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            seedDraftWithPages(1)
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(PreviewContract.Action.CropClicked)
            runCurrent()
            viewModel.onAction(PreviewContract.Action.ProcessClicked)
            runCurrent()

            assertEquals(PreviewContract.Mode.CROPPING, viewModel.uiState.value.mode)
            assertTrue(qualityAnalyzer.analyses.isEmpty())
        }

    @Test
    fun `quality warning is explained and continuing delegates the same pages`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            seedDraftWithPages(2)
            qualityAnalyzer.reportForImage = { image ->
                qualityReport(
                    image = image,
                    warnings = if (image.imageId == PAGE_IDS[1]) {
                        listOf(
                            ImageQualityWarning.PossibleUnderexposure(
                                meanLuminance = 42,
                                darkPixelsPermille = 680,
                            ),
                        )
                    } else {
                        emptyList()
                    },
                )
            }
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(PreviewContract.Action.ProcessClicked)
            runCurrent()

            assertEquals(1, viewModel.uiState.value.qualityWarnings.size)
            assertEquals(1, viewModel.uiState.value.currentIndex)
            viewModel.effects.test {
                viewModel.onAction(PreviewContract.Action.ContinueWithWarningsClicked)
                viewModel.onAction(PreviewContract.Action.ContinueWithWarningsClicked)
                runCurrent()

                assertTrue(viewModel.uiState.value.qualityWarnings.isEmpty())
                assertEquals(PreviewContract.Effect.OpenProcessing(DRAFT_ID), awaitItem())
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `repeating a warned page returns to source without starting OCR`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            seedDraftWithPages(2)
            qualityAnalyzer.reportForImage = { image ->
                qualityReport(
                    image = image,
                    warnings = listOf(
                        ImageQualityWarning.PossibleBlur(
                            sharpnessScore = 20,
                            recommendedMinimum = 180,
                        ),
                    ),
                )
            }
            val viewModel = createViewModel()
            runCurrent()
            viewModel.onAction(PreviewContract.Action.ProcessClicked)
            runCurrent()

            viewModel.effects.test {
                viewModel.onAction(PreviewContract.Action.RetakeWarnedPageClicked)
                runCurrent()

                assertEquals(
                    PreviewContract.Effect.OpenSource(DRAFT_ID, PAGE_IDS[0]),
                    awaitItem(),
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `two process clicks start only one analysis and emit one navigation`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            seedDraftWithPages(1)
            val viewModel = createViewModel()
            runCurrent()

            viewModel.effects.test {
                viewModel.onAction(PreviewContract.Action.ProcessClicked)
                viewModel.onAction(PreviewContract.Action.ProcessClicked)
                runCurrent()

                assertEquals(listOf(PAGE_IDS[0]), qualityAnalyzer.analyses)
                assertEquals(PreviewContract.Effect.OpenProcessing(DRAFT_ID), awaitItem())
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `back cancels active quality analysis before OCR starts`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            seedDraftWithPages(1)
            qualityAnalyzer.beforeAnalyze = { awaitCancellation() }
            val viewModel = createViewModel()
            runCurrent()
            viewModel.onAction(PreviewContract.Action.ProcessClicked)
            runCurrent()
            assertTrue(viewModel.uiState.value.isWorking)

            viewModel.effects.test {
                viewModel.onAction(PreviewContract.Action.BackSelected)
                runCurrent()

                assertEquals(PreviewContract.Effect.Back, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    // --- Soporte ---

    private fun qualityReport(
        image: InvoiceImage,
        warnings: List<ImageQualityWarning>,
    ): ImageQualityReport = ImageQualityReport(
        imageId = image.imageId,
        effectiveWidthPx = image.widthPx,
        effectiveHeightPx = image.heightPx,
        meanLuminance = 128,
        darkPixelsPermille = 0,
        brightPixelsPermille = 0,
        sharpnessScore = 1_000,
        estimatedSkewTenths = 0,
        borderContentPermille = 0,
        warnings = warnings,
    )

    private suspend fun seedDraftWithPages(pageCount: Int) {
        drafts.createDraft(
            InvoiceDraft(
                draftId = DRAFT_ID,
                businessId = BUSINESS_ID,
                status = DraftStatus.CAPTURED,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            ),
        )
        repeat(pageCount) { index ->
            drafts.seedImage(
                InvoiceImage(
                    imageId = PAGE_IDS[index],
                    draftId = DRAFT_ID,
                    businessId = BUSINESS_ID,
                    pageIndex = index,
                    filePath = "draft_images/${DRAFT_ID.value}/p$index.jpg",
                    sha256 = "%064x".format(index + 1),
                    mimeType = "image/jpeg",
                    widthPx = 3_000,
                    heightPx = 4_000,
                    fileSizeBytes = 1_000L,
                    rotationDegrees = if (index == 1) 90 else 0,
                    createdAt = Instant.EPOCH,
                ),
            )
        }
    }

    private fun createViewModel(
        repository: InvoiceDraftRepository = drafts,
        savedStateHandle: SavedStateHandle = SavedStateHandle(
            mapOf("draftId" to DRAFT_ID.value),
        ),
    ): PreviewViewModel = PreviewViewModel(
        savedStateHandle = savedStateHandle,
        observeDraftImagesUseCase = ObserveDraftImagesUseCase(repository),
        rotateDraftImageUseCase = RotateDraftImageUseCase(repository),
        cropDraftImageUseCase = CropDraftImageUseCase(repository),
        reorderDraftImagesUseCase = ReorderDraftImagesUseCase(repository),
        deleteDraftImageUseCase = DeleteDraftImageUseCase(repository, fileStore),
        analyzeDraftImagesUseCase = AnalyzeDraftImagesUseCase(repository, qualityAnalyzer),
        dispatcherProvider = dispatchers,
    )

    private companion object {
        val BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("00000000-0000-0000-0000-0000000000b1"),
        )
        val DRAFT_ID: DraftId = DraftId.from(
            UUID.fromString("00000000-0000-4000-8000-0000000000d1"),
        )
        val PAGE_IDS: List<ImageId> = listOf(
            ImageId.from(UUID.fromString("00000000-0000-4000-8000-0000000000a1")),
            ImageId.from(UUID.fromString("00000000-0000-4000-8000-0000000000a2")),
            ImageId.from(UUID.fromString("00000000-0000-4000-8000-0000000000a3")),
        )
    }
}
