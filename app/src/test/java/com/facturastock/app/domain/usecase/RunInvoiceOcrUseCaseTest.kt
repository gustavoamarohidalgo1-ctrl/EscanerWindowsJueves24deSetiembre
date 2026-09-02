package com.facturastock.app.domain.usecase

import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.error.FileError
import com.facturastock.app.domain.error.FileException
import com.facturastock.app.domain.error.OcrError
import com.facturastock.app.domain.error.OcrException
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.ImageRetentionPolicy
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.InvoiceImage
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.model.id.OcrRunId
import com.facturastock.app.testing.FakeAppConfigurationRepository
import com.facturastock.app.testing.FakeDraftFileStore
import com.facturastock.app.testing.FakeInvoiceDraftRepository
import com.facturastock.app.testing.FakeInvoiceImagePreprocessor
import com.facturastock.app.testing.FakeInvoiceOcrSnapshotRepository
import com.facturastock.app.testing.FakeInvoiceTextRecognizer
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RunInvoiceOcrUseCaseTest {
    @Test
    fun `success executes real stages in order and atomically publishes OCR ready`() = runTest {
        val fixture = fixture(pageCount = 3)
        val stages = mutableListOf<InvoiceOcrStage>()

        val result = fixture.useCase(DRAFT_ID) { stages += it }

        assertEquals(
            listOf(
                InvoiceOcrStage.PREPARING,
                InvoiceOcrStage.READING,
                InvoiceOcrStage.MERGING_PAGES,
            ),
            stages,
        )
        assertEquals(3, result.pageCount)
        assertEquals(NOW, result.completedAt)
        assertEquals(3, fixture.preprocessor.preprocessCalls.size)
        assertEquals(1, fixture.recognizer.calls.size)
        assertEquals(1, fixture.snapshots.publications.size)
        assertEquals(result.runId, fixture.snapshots.find(DRAFT_ID)?.runId)
        assertEquals(DraftStatus.OCR_READY, fixture.drafts.findDraft(DRAFT_ID)?.status)
        assertNull(fixture.drafts.findDraft(DRAFT_ID)?.activeOcrRunId)
    }

    @Test
    fun `two concurrent invocations let only the CAS winner execute OCR`() = runTest {
        val fixture = fixture(pageCount = 1)
        val preparing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var preparations = 0
        fixture.preprocessor.beforePreprocess = {
            preparations += 1
            preparing.complete(Unit)
            release.await()
        }

        val first = async { fixture.useCase(DRAFT_ID) }
        runCurrent()
        preparing.await()

        val secondFailure = runCatching { fixture.useCase(DRAFT_ID) }.exceptionOrNull()
        assertEquals(OcrError.DraftNotOpen, (secondFailure as OcrException).error)
        assertEquals(1, preparations)
        assertEquals(DraftStatus.OCR_PROCESSING, fixture.drafts.findDraft(DRAFT_ID)?.status)

        release.complete(Unit)
        first.await()
        assertEquals(1, fixture.recognizer.calls.size)
        assertEquals(1, fixture.snapshots.publications.size)
    }

    @Test
    fun `recognition error stores a closed code and leaves a retryable ERROR draft`() = runTest {
        val fixture = fixture(pageCount = 1)
        fixture.recognizer.nextException = IllegalStateException("fallo sintético")
        val stages = mutableListOf<InvoiceOcrStage>()

        val failure = runCatching {
            fixture.useCase(DRAFT_ID) { stages += it }
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(
            listOf(InvoiceOcrStage.PREPARING, InvoiceOcrStage.READING),
            stages,
        )
        val draft = fixture.drafts.findDraft(DRAFT_ID)
        assertEquals(DraftStatus.ERROR, draft?.status)
        assertEquals("OCR_PIPELINE_FAILED", draft?.lastError)
        assertNull(draft?.activeOcrRunId)
        assertNull(fixture.snapshots.find(DRAFT_ID))
    }

    @Test
    fun `out of memory is normalized and closes the durable run token`() = runTest {
        val fixture = fixture(pageCount = 1)

        val failure = runCatching {
            fixture.useCase(DRAFT_ID) { stage ->
                if (stage == InvoiceOcrStage.READING) {
                    throw OutOfMemoryError("fallo de asignación sintético")
                }
            }
        }.exceptionOrNull()

        val ocrFailure = failure as OcrException
        assertEquals(OcrError.RecognitionFailed, ocrFailure.error)
        assertTrue(ocrFailure.cause is OutOfMemoryError)
        val draft = fixture.drafts.findDraft(DRAFT_ID)
        assertEquals(DraftStatus.ERROR, draft?.status)
        assertEquals("OCR_RECOGNITION_FAILED", draft?.lastError)
        assertNull(draft?.activeOcrRunId)
        assertNull(fixture.snapshots.find(DRAFT_ID))
    }

    @Test
    fun `insufficient space during preprocessing keeps source pages and a retryable draft`() =
        runTest {
            val fixture = fixture(pageCount = 2)
            fixture.preprocessor.nextException = FileException(FileError.InsufficientSpace)
            val pagesBefore = fixture.drafts.observeImages(DRAFT_ID).first()

            val failure = runCatching { fixture.useCase(DRAFT_ID) }.exceptionOrNull()

            assertEquals(FileError.InsufficientSpace, (failure as FileException).error)
            val draft = fixture.drafts.findDraft(DRAFT_ID)
            assertEquals(DraftStatus.ERROR, draft?.status)
            assertEquals("OCR_PREPARATION_FAILED", draft?.lastError)
            assertNull(draft?.activeOcrRunId)
            assertEquals(pagesBefore, fixture.drafts.observeImages(DRAFT_ID).first())
            assertNull(fixture.snapshots.find(DRAFT_ID))
        }

    @Test
    fun `cancellation restores CAPTURED and never publishes a partial snapshot`() = runTest {
        val fixture = fixture(pageCount = 1)
        val reading = CompletableDeferred<Unit>()
        fixture.recognizer.beforeRecognizePage = { _, _ ->
            reading.complete(Unit)
            CompletableDeferred<Unit>().await()
        }
        val job = launch { fixture.useCase(DRAFT_ID) }
        runCurrent()
        reading.await()

        job.cancelAndJoin()

        val draft = fixture.drafts.findDraft(DRAFT_ID)
        assertEquals(DraftStatus.CAPTURED, draft?.status)
        assertNull(draft?.activeOcrRunId)
        assertNull(draft?.lastError)
        assertNull(fixture.snapshots.find(DRAFT_ID))
    }

    @Test
    fun `cancellation racing an atomic publish cannot reopen the completed draft`() = runTest {
        val fixture = fixture(pageCount = 1)
        val publishing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.snapshots.afterPublish = {
            publishing.complete(Unit)
            release.await()
        }
        val job = launch { fixture.useCase(DRAFT_ID) }
        runCurrent()
        publishing.await()

        job.cancel()
        release.complete(Unit)
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(DraftStatus.OCR_READY, fixture.drafts.findDraft(DRAFT_ID)?.status)
        assertEquals(1, fixture.snapshots.snapshotCount)
    }

    @Test
    fun `retry replaces the prior snapshot instead of appending another one`() = runTest {
        val fixture = fixture(pageCount = 1)
        fixture.recognizer.seedText(PAGE_IDS[0], "PRIMER RESULTADO")
        val first = fixture.useCase(DRAFT_ID)
        val ready = requireNotNull(fixture.drafts.findDraft(DRAFT_ID))
        fixture.drafts.updateDraft(ready.copy(status = DraftStatus.ERROR))
        fixture.clock.now = NOW.plusSeconds(30)
        fixture.recognizer.seedText(PAGE_IDS[0], "RESULTADO REEMPLAZADO")

        val second = fixture.useCase(DRAFT_ID)

        assertFalse(first.runId == second.runId)
        assertEquals(2, fixture.snapshots.publications.size)
        assertEquals(1, fixture.snapshots.snapshotCount)
        val stored = requireNotNull(fixture.snapshots.find(DRAFT_ID))
        assertEquals(second.runId, stored.runId)
        assertEquals("RESULTADO REEMPLAZADO", stored.document.pages.single().text)
        assertEquals(NOW.plusSeconds(30), stored.completedAt)
    }

    @Test
    fun `process restart recovers an interrupted token and starts a fresh idempotent run`() =
        runTest {
            val fixture = fixture(pageCount = 1)
            val interruptedRun = OcrRunId.from(UUID(0L, 90L))
            assertTrue(fixture.drafts.beginOcrRun(DRAFT_ID, interruptedRun))

            val blocked = runCatching { fixture.useCase(DRAFT_ID) }.exceptionOrNull()
            assertEquals(OcrError.DraftNotOpen, (blocked as OcrException).error)
            assertTrue(RetryDraftOcrUseCase(fixture.drafts)(DRAFT_ID, interruptedRun))

            val recovered = fixture.useCase(DRAFT_ID)

            assertFalse(recovered.runId == interruptedRun)
            assertEquals(DraftStatus.OCR_READY, fixture.drafts.findDraft(DRAFT_ID)?.status)
            assertEquals(1, fixture.recognizer.calls.size)
        }

    @Test
    fun `an OCR ready draft replays its snapshot without duplicating work`() = runTest {
        val fixture = fixture(pageCount = 2)
        val first = fixture.useCase(DRAFT_ID)
        val preprocessCount = fixture.preprocessor.preprocessCalls.size
        val recognizeCount = fixture.recognizer.calls.size
        val replayStages = mutableListOf<InvoiceOcrStage>()

        val replay = fixture.useCase(DRAFT_ID) { replayStages += it }

        assertEquals(first, replay)
        assertTrue(replayStages.isEmpty())
        assertEquals(preprocessCount, fixture.preprocessor.preprocessCalls.size)
        assertEquals(recognizeCount, fixture.recognizer.calls.size)
        assertEquals(1, fixture.snapshots.publications.size)
    }

    @Test
    fun `a parsed draft replays the OCR snapshot after process recreation`() = runTest {
        val fixture = fixture(pageCount = 1)
        val first = fixture.useCase(DRAFT_ID)
        val ready = requireNotNull(fixture.drafts.findDraft(DRAFT_ID))
        fixture.drafts.updateDraft(ready.copy(status = DraftStatus.NEEDS_REVIEW))

        val replay = fixture.useCase(DRAFT_ID)

        assertEquals(first, replay)
        assertEquals(1, fixture.recognizer.calls.size)
        assertEquals(1, fixture.snapshots.publications.size)
    }

    @Test
    fun `a draft without pages is rejected before claiming or preprocessing`() = runTest {
        val fixture = fixture(pageCount = 0)

        val failure = runCatching { fixture.useCase(DRAFT_ID) }.exceptionOrNull()

        assertEquals(OcrError.RecognitionFailed, (failure as OcrException).error)
        assertEquals(DraftStatus.CAPTURED, fixture.drafts.findDraft(DRAFT_ID)?.status)
        assertTrue(fixture.preprocessor.preprocessCalls.isEmpty())
        assertTrue(fixture.recognizer.calls.isEmpty())
        assertNull(fixture.snapshots.find(DRAFT_ID))
    }

    @Test
    fun `a gap in source page indexes is rejected before claiming the run`() = runTest {
        val fixture = fixture(pageCount = 1)
        fixture.drafts.seedImage(page(2))

        val failure = runCatching { fixture.useCase(DRAFT_ID) }.exceptionOrNull()

        assertEquals(OcrError.RecognitionFailed, (failure as OcrException).error)
        assertEquals(DraftStatus.CAPTURED, fixture.drafts.findDraft(DRAFT_ID)?.status)
        assertTrue(fixture.preprocessor.preprocessCalls.isEmpty())
        assertTrue(fixture.recognizer.calls.isEmpty())
    }

    @Test
    fun `a reordered prepared batch is rejected before recognition`() = runTest {
        val fixture = fixture(pageCount = 2)
        fixture.preprocessor.transformPreparedBatch = { pages -> pages.reversed() }

        val failure = runCatching { fixture.useCase(DRAFT_ID) }.exceptionOrNull()

        assertEquals(OcrError.RecognitionFailed, (failure as OcrException).error)
        assertEquals(DraftStatus.ERROR, fixture.drafts.findDraft(DRAFT_ID)?.status)
        assertEquals("OCR_RECOGNITION_FAILED", fixture.drafts.findDraft(DRAFT_ID)?.lastError)
        assertTrue(fixture.recognizer.calls.isEmpty())
        assertNull(fixture.snapshots.find(DRAFT_ID))
    }

    @Test
    fun `with the default policy the originals survive the published run`() = runTest {
        val fixture = fixture(pageCount = 2)

        fixture.useCase(DRAFT_ID)

        assertTrue(fixture.draftFiles.deletions.isEmpty())
    }

    @Test
    fun `with AFTER_OCR the originals are deleted once the run is published`() = runTest {
        val fixture = fixture(pageCount = 2)
        fixture.retentionConfiguration.updateImageRetentionPolicy(ImageRetentionPolicy.AFTER_OCR)

        fixture.useCase(DRAFT_ID)

        val expectedPaths = listOf(0, 1).map { index ->
            "draft_images/${DRAFT_ID.value}/original/${PAGE_IDS[index].value}.jpg"
        }
        assertEquals(listOf(expectedPaths), fixture.draftFiles.deletions)
    }

    private suspend fun fixture(pageCount: Int): Fixture {
        val clock = MutableClock(NOW)
        val drafts = FakeInvoiceDraftRepository(clock)
        drafts.createDraft(
            InvoiceDraft(
                draftId = DRAFT_ID,
                businessId = BUSINESS_ID,
                status = DraftStatus.CAPTURED,
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )
        repeat(pageCount) { index -> drafts.seedImage(page(index)) }
        val preprocessor = FakeInvoiceImagePreprocessor()
        val recognizer = FakeInvoiceTextRecognizer()
        val snapshots = FakeInvoiceOcrSnapshotRepository(drafts)
        val retentionConfiguration = FakeAppConfigurationRepository()
        val draftFiles = FakeDraftFileStore()
        var sequence = 100L
        return Fixture(
            clock = clock,
            drafts = drafts,
            preprocessor = preprocessor,
            recognizer = recognizer,
            snapshots = snapshots,
            retentionConfiguration = retentionConfiguration,
            draftFiles = draftFiles,
            useCase = RunInvoiceOcrUseCase(
                invoiceDraftRepository = drafts,
                invoiceImagePreprocessor = preprocessor,
                invoiceTextRecognizer = recognizer,
                invoiceOcrSnapshotRepository = snapshots,
                uuidGenerator = UuidGenerator { UUID(0L, ++sequence) },
                appClock = clock,
                applyImageRetentionAfterOcr = ApplyImageRetentionAfterOcrUseCase(
                    retentionConfiguration,
                    draftFiles,
                    drafts,
                ),
            ),
        )
    }

    private fun page(index: Int) = InvoiceImage(
        imageId = PAGE_IDS[index],
        draftId = DRAFT_ID,
        businessId = BUSINESS_ID,
        pageIndex = index,
        filePath = "draft_images/${DRAFT_ID.value}/original/${PAGE_IDS[index].value}.jpg",
        sha256 = "%064x".format(index + 1),
        mimeType = "image/jpeg",
        widthPx = 1_200,
        heightPx = 1_600,
        fileSizeBytes = 4_000L,
        createdAt = NOW,
    )

    private data class Fixture(
        val clock: MutableClock,
        val drafts: FakeInvoiceDraftRepository,
        val preprocessor: FakeInvoiceImagePreprocessor,
        val recognizer: FakeInvoiceTextRecognizer,
        val snapshots: FakeInvoiceOcrSnapshotRepository,
        val retentionConfiguration: FakeAppConfigurationRepository,
        val draftFiles: FakeDraftFileStore,
        val useCase: RunInvoiceOcrUseCase,
    )

    private class MutableClock(var now: Instant) : AppClock {
        override fun now(): Instant = now
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-09T12:00:00Z")
        val DRAFT_ID: DraftId = DraftId.from(UUID(0L, 10L))
        val BUSINESS_ID: BusinessId = BusinessId.from(UUID(0L, 20L))
        val PAGE_IDS: List<ImageId> = List(4) { index ->
            ImageId.from(UUID(0L, index + 1L))
        }
    }
}
