package com.facturastock.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.codec.InvoiceHeaderEditCodec
import com.facturastock.app.data.local.codec.InvoiceLinesEditCodec
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.Business
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.InvoiceLine
import com.facturastock.app.domain.model.InvoiceOcrSnapshot
import com.facturastock.app.domain.model.InvoiceTextDocument
import com.facturastock.app.domain.model.InvoiceTextPage
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.model.id.OcrRunId
import com.facturastock.app.domain.repository.EnterManualInvoiceReviewResult
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManualInvoiceReviewRepositoryTest {
    private lateinit var database: FacturaStockDatabase
    private lateinit var clock: TestClock
    private lateinit var businesses: RoomBusinessRepository
    private lateinit var drafts: RoomInvoiceDraftRepository
    private lateinit var snapshots: RoomInvoiceOcrSnapshotRepository
    private lateinit var manualReview: RoomManualInvoiceReviewRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, FacturaStockDatabase::class.java).build()
        clock = TestClock(BASE_TIME)
        businesses = RoomBusinessRepository(database.businessDao(), testDispatchers, clock)
        drafts = RoomInvoiceDraftRepository(
            database = database,
            invoiceDraftDao = database.invoiceDraftDao(),
            invoiceImageDao = database.invoiceImageDao(),
            invoiceLineDao = database.invoiceLineDao(),
            dispatchers = testDispatchers,
            clock = clock,
        )
        snapshots = RoomInvoiceOcrSnapshotRepository(
            database = database,
            snapshotDao = database.invoiceOcrSnapshotDao(),
            dispatchers = testDispatchers,
        )
        manualReview = RoomManualInvoiceReviewRepository(
            database = database,
            invoiceDraftDao = database.invoiceDraftDao(),
            invoiceLineDao = database.invoiceLineDao(),
            headerEditDao = database.invoiceHeaderEditDao(),
            linesEditDao = database.invoiceLinesEditDao(),
            dispatchers = testDispatchers,
            clock = clock,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun recognitionFallbackInvalidatesRunPersistsEmptyEditsAndRejectsLateSnapshot() = runBlocking {
        seedCapturedDraft()
        drafts.addLine(
            InvoiceLine(
                lineId = LINE_ID,
                draftId = DRAFT_ID,
                businessId = BUSINESS_ID,
                position = 0,
                descriptionRaw = "PROYECCIÓN RESIDUAL",
                createdAt = BASE_TIME,
                updatedAt = BASE_TIME,
            ),
        )
        assertTrue(drafts.beginOcrRun(DRAFT_ID, RUN_ID))

        assertEquals(EnterManualInvoiceReviewResult.ENTERED, manualReview.enter(DRAFT_ID))

        val stored = requireNotNull(drafts.findDraft(DRAFT_ID))
        assertEquals(DraftStatus.NEEDS_REVIEW, stored.status)
        assertNull(stored.activeOcrRunId)
        assertNull(stored.lastError)
        assertNull(stored.supplierRucRaw)
        assertEquals(0, database.invoiceLineDao().countForDraft(DRAFT_ID.value))
        assertEmptyDurableReview()

        // Ni el callback de cleanup ni la publicación del job anterior pueden degradar o
        // sobrescribir el formulario que ya se abrió manualmente.
        assertFalse(drafts.finishOcrRun(DRAFT_ID, RUN_ID, DraftStatus.ERROR, "LATE_FAILURE"))
        assertFalse(snapshots.publish(snapshot(RUN_ID)))
        assertNull(snapshots.find(DRAFT_ID))
        assertEquals(DraftStatus.NEEDS_REVIEW, drafts.findDraft(DRAFT_ID)?.status)
    }

    @Test
    fun concurrentSnapshotPublicationCannotLeaveManualReviewPartialOrLoseEvidence() = runBlocking {
        seedCapturedDraft()
        assertTrue(drafts.beginOcrRun(DRAFT_ID, RUN_ID))
        val expectedSnapshot = snapshot(RUN_ID)

        val (manualResult, snapshotPublished) = coroutineScope {
            val manual = async(Dispatchers.Default) { manualReview.enter(DRAFT_ID) }
            val ocr = async(Dispatchers.Default) { snapshots.publish(expectedSnapshot) }
            manual.await() to ocr.await()
        }

        assertEquals(EnterManualInvoiceReviewResult.ENTERED, manualResult)
        assertEquals(DraftStatus.NEEDS_REVIEW, drafts.findDraft(DRAFT_ID)?.status)
        assertEmptyDurableReview()
        if (snapshotPublished) {
            assertEquals(expectedSnapshot, snapshots.find(DRAFT_ID))
        } else {
            assertNull(snapshots.find(DRAFT_ID))
        }
    }

    @Test
    fun parserFallbackPreservesPublishedOcrSnapshotAndCreatesEmptyReview() = runBlocking {
        seedCapturedDraft()
        assertTrue(drafts.beginOcrRun(DRAFT_ID, RUN_ID))
        val published = snapshot(RUN_ID)
        assertTrue(snapshots.publish(published))
        assertEquals(DraftStatus.OCR_READY, drafts.findDraft(DRAFT_ID)?.status)

        assertEquals(EnterManualInvoiceReviewResult.ENTERED, manualReview.enter(DRAFT_ID))

        assertEquals(DraftStatus.NEEDS_REVIEW, drafts.findDraft(DRAFT_ID)?.status)
        assertEquals(published, snapshots.find(DRAFT_ID))
        assertEmptyDurableReview()
    }

    @Test
    fun modeGuardsRejectSnapshotStatusMismatchesWithoutChangingEvidence() = runBlocking {
        seedCapturedDraft()
        val captured = requireNotNull(database.invoiceDraftDao().findById(DRAFT_ID.value))
        database.invoiceDraftDao().update(captured.copy(status = DraftStatus.OCR_READY.name))

        assertEquals(EnterManualInvoiceReviewResult.NOT_AVAILABLE, manualReview.enter(DRAFT_ID))
        assertEquals(DraftStatus.OCR_READY, drafts.findDraft(DRAFT_ID)?.status)
        assertNull(database.invoiceHeaderEditDao().findByDraftId(DRAFT_ID.value))

        database.invoiceDraftDao().update(captured)
        assertTrue(drafts.beginOcrRun(DRAFT_ID, RUN_ID))
        val published = snapshot(RUN_ID)
        assertTrue(snapshots.publish(published))
        val ready = requireNotNull(database.invoiceDraftDao().findById(DRAFT_ID.value))
        database.invoiceDraftDao().update(ready.copy(status = DraftStatus.ERROR.name))

        assertEquals(EnterManualInvoiceReviewResult.NOT_AVAILABLE, manualReview.enter(DRAFT_ID))
        assertEquals(DraftStatus.ERROR, drafts.findDraft(DRAFT_ID)?.status)
        assertEquals(published, snapshots.find(DRAFT_ID))
        assertNull(database.invoiceHeaderEditDao().findByDraftId(DRAFT_ID.value))
        assertNull(database.invoiceLinesEditDao().findByDraftId(DRAFT_ID.value))
    }

    @Test
    fun failedSecondInsertRollsBackStatusHeaderAndLineProjection() = runBlocking {
        seedCapturedDraft()
        drafts.addLine(
            InvoiceLine(
                lineId = LINE_ID,
                draftId = DRAFT_ID,
                businessId = BUSINESS_ID,
                position = 0,
                descriptionRaw = "SE CONSERVA EN ROLLBACK",
                createdAt = BASE_TIME,
                updatedAt = BASE_TIME,
            ),
        )
        assertTrue(drafts.beginOcrRun(DRAFT_ID, RUN_ID))
        assertTrue(drafts.finishOcrRun(DRAFT_ID, RUN_ID, DraftStatus.ERROR, "OCR_NO_TEXT"))
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_manual_line_edit BEFORE INSERT ON invoice_line_edits " +
                "BEGIN SELECT RAISE(ABORT, 'scheduled manual fallback failure'); END",
        )

        assertThrows(StorageException::class.java) {
            runBlocking { manualReview.enter(DRAFT_ID) }
        }

        val rolledBack = requireNotNull(drafts.findDraft(DRAFT_ID))
        assertEquals(DraftStatus.ERROR, rolledBack.status)
        assertEquals("OCR_NO_TEXT", rolledBack.lastError)
        assertNull(database.invoiceHeaderEditDao().findByDraftId(DRAFT_ID.value))
        assertNull(database.invoiceLinesEditDao().findByDraftId(DRAFT_ID.value))
        assertEquals(1, database.invoiceLineDao().countForDraft(DRAFT_ID.value))
    }

    private suspend fun seedCapturedDraft() {
        businesses.create(
            Business(
                businessId = BUSINESS_ID,
                legalName = "Negocio Manual SAC",
                ruc = "20123456789",
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            ),
        )
        drafts.createDraft(
            InvoiceDraft(
                draftId = DRAFT_ID,
                businessId = BUSINESS_ID,
                status = DraftStatus.CAPTURED,
                supplierRucRaw = "20123456789",
                supplierRucNormalized = "20123456789",
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            ),
        )
    }

    private suspend fun assertEmptyDurableReview() {
        val headerEntity = requireNotNull(
            database.invoiceHeaderEditDao().findByDraftId(DRAFT_ID.value),
        )
        val header = InvoiceHeaderEditCodec.decode(headerEntity.payload)
        assertEquals(0L, header.revision)
        assertNull(header.supplierRuc)
        assertNull(header.documentNumber)
        assertTrue(header.touchedFields.isEmpty())
        assertEquals(InvoiceHeaderEditCodec.sha256(headerEntity.payload), headerEntity.payloadSha256)

        val linesEntity = requireNotNull(
            database.invoiceLinesEditDao().findByDraftId(DRAFT_ID.value),
        )
        val lines = InvoiceLinesEditCodec.decode(linesEntity.payload)
        assertEquals(0L, lines.revision)
        assertTrue(lines.lines.isEmpty())
        assertEquals(InvoiceLinesEditCodec.sha256(linesEntity.payload), linesEntity.payloadSha256)
    }

    private fun snapshot(runId: OcrRunId): InvoiceOcrSnapshot = InvoiceOcrSnapshot(
        draftId = DRAFT_ID,
        runId = runId,
        completedAt = BASE_TIME.plusSeconds(5),
        document = InvoiceTextDocument(
            pages = listOf(
                InvoiceTextPage(
                    sourceImageId = IMAGE_ID,
                    pageIndex = 0,
                    widthPx = 1_200,
                    heightPx = 1_600,
                    text = "FACTURA F001-1 TOTAL 10.00",
                    blocks = emptyList(),
                ),
            ),
        ),
    )

    private companion object {
        val BASE_TIME: Instant = Instant.parse("2026-08-12T12:00:00Z")
        val BUSINESS_ID: BusinessId = BusinessId.from(UUID(0L, 1L))
        val DRAFT_ID: DraftId = DraftId.from(UUID(0L, 2L))
        val RUN_ID: OcrRunId = OcrRunId.from(UUID(0L, 3L))
        val IMAGE_ID: ImageId = ImageId.from(UUID(0L, 4L))
        val LINE_ID: LineId = LineId.from(UUID(0L, 5L))
    }
}
