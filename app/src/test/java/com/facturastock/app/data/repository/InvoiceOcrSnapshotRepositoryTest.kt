package com.facturastock.app.data.repository

import com.facturastock.app.data.local.newFacturaStockDatabase
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.domain.model.Business
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.InvoiceOcrSnapshot
import com.facturastock.app.domain.model.InvoiceTextBlock
import com.facturastock.app.domain.model.InvoiceTextDocument
import com.facturastock.app.domain.model.InvoiceTextGeometry
import com.facturastock.app.domain.model.InvoiceTextPage
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.model.id.OcrRunId
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InvoiceOcrSnapshotRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var database: FacturaStockDatabase
    private lateinit var clock: TestClock
    private lateinit var businesses: RoomBusinessRepository
    private lateinit var drafts: RoomInvoiceDraftRepository
    private lateinit var snapshots: RoomInvoiceOcrSnapshotRepository

    @Before
    fun setUp() {
        database = tempFolder.newFacturaStockDatabase()
        clock = TestClock(Instant.parse("2026-08-08T12:00:00Z"))
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
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun publishAtomicallyMarksReadyAndFindRestoresTheDocument() = runBlocking {
        seedCapturedDraft()
        val runId = ocrRunId(80)
        val expected = snapshot(
            runId = runId,
            completedAt = clock.now(),
            pageTexts = listOf("FACTURA F001-1", "TOTAL S/ 248.50"),
        )
        assertTrue(drafts.beginOcrRun(DRAFT_ID, runId))

        assertTrue(snapshots.publish(expected))

        assertEquals(expected, snapshots.find(DRAFT_ID))
        val ready = requireNotNull(drafts.findDraft(DRAFT_ID))
        assertEquals(DraftStatus.OCR_READY, ready.status)
        assertNull(ready.activeOcrRunId)
        assertNull(ready.lastError)
        assertEquals(expected.completedAt, ready.updatedAt)
        assertEquals(2, database.invoiceOcrSnapshotDao().findPages(DRAFT_ID.value).size)
    }

    @Test
    fun stalePublishCannotMutateOldSnapshotAndAuthorizedRetryReplacesEveryPage() = runBlocking {
        seedCapturedDraft()
        val firstRun = ocrRunId(80)
        val first = snapshot(
            runId = firstRun,
            completedAt = clock.now(),
            pageTexts = listOf("PÁGINA ANTIGUA 1", "PÁGINA ANTIGUA 2"),
        )
        assertTrue(drafts.beginOcrRun(DRAFT_ID, firstRun))
        assertTrue(snapshots.publish(first))

        clock.advanceSeconds(60)
        val ready = requireNotNull(drafts.findDraft(DRAFT_ID))
        assertTrue(drafts.updateDraft(ready.copy(status = DraftStatus.CAPTURED)))
        val secondRun = ocrRunId(81)
        assertTrue(drafts.beginOcrRun(DRAFT_ID, secondRun))

        val stale = snapshot(
            runId = ocrRunId(82),
            completedAt = clock.now(),
            pageTexts = listOf("NO DEBE PUBLICARSE"),
        )
        assertFalse(snapshots.publish(stale))
        assertEquals(first, snapshots.find(DRAFT_ID))
        val stillProcessing = requireNotNull(drafts.findDraft(DRAFT_ID))
        assertEquals(DraftStatus.OCR_PROCESSING, stillProcessing.status)
        assertEquals(secondRun, stillProcessing.activeOcrRunId)

        val replacement = snapshot(
            runId = secondRun,
            completedAt = clock.now(),
            pageTexts = listOf("ÚNICA PÁGINA NUEVA"),
        )
        assertTrue(snapshots.publish(replacement))
        assertEquals(replacement, snapshots.find(DRAFT_ID))
        val storedPages = database.invoiceOcrSnapshotDao().findPages(DRAFT_ID.value)
        assertEquals(1, storedPages.size)
        assertEquals(
            replacement.document.pages.single().sourceImageId.value,
            storedPages.single().sourceImageId,
        )
    }

    @Test
    fun concurrentDuplicatePublishAllowsExactlyOneCasWinner() = runBlocking {
        seedCapturedDraft()
        val runId = ocrRunId(80)
        val expected = snapshot(runId, clock.now(), listOf("RESULTADO CONCURRENTE"))
        assertTrue(drafts.beginOcrRun(DRAFT_ID, runId))

        val results = coroutineScope {
            List(2) {
                async(Dispatchers.Default) { snapshots.publish(expected) }
            }.awaitAll()
        }

        assertEquals(1, results.count { it })
        assertEquals(expected, snapshots.find(DRAFT_ID))
        assertEquals(DraftStatus.OCR_READY, drafts.findDraft(DRAFT_ID)?.status)
    }

    @Test
    fun deletingDraftCascadesSnapshotHeaderAndPages() = runBlocking {
        seedCapturedDraft()
        val runId = ocrRunId(80)
        assertTrue(drafts.beginOcrRun(DRAFT_ID, runId))
        assertTrue(
            snapshots.publish(
                snapshot(runId, clock.now(), listOf("PÁGINA 1", "PÁGINA 2")),
            ),
        )

        assertTrue(drafts.deleteDraft(DRAFT_ID))

        assertNull(snapshots.find(DRAFT_ID))
        assertNull(database.invoiceOcrSnapshotDao().findHeader(DRAFT_ID.value))
        assertTrue(database.invoiceOcrSnapshotDao().findPages(DRAFT_ID.value).isEmpty())
    }

    private suspend fun seedCapturedDraft() {
        businesses.create(
            Business(
                businessId = BUSINESS_ID,
                legalName = "Negocio OCR SAC",
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
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            ),
        )
    }

    private fun snapshot(
        runId: OcrRunId,
        completedAt: Instant,
        pageTexts: List<String>,
    ): InvoiceOcrSnapshot = InvoiceOcrSnapshot(
        draftId = DRAFT_ID,
        runId = runId,
        completedAt = completedAt,
        document = InvoiceTextDocument(
            pageTexts.mapIndexed { index, text ->
                InvoiceTextPage(
                    sourceImageId = imageId(100 + index),
                    pageIndex = index,
                    widthPx = 1_200,
                    heightPx = 1_600,
                    text = text,
                    blocks = listOf(
                        InvoiceTextBlock(
                            position = 0,
                            text = text,
                            languageTag = "es-PE",
                            geometry = InvoiceTextGeometry(null, emptyList()),
                            lines = emptyList(),
                        ),
                    ),
                )
            },
        ),
    )

    private fun ocrRunId(seed: Int) = OcrRunId.from(uuid(seed))
    private fun imageId(seed: Int) = ImageId.from(uuid(seed))

    private fun uuid(seed: Int): UUID =
        UUID.fromString("00000000-0000-0000-0000-%012d".format(seed))

    private companion object {
        val BUSINESS_ID = BusinessId.from(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
        )
        val DRAFT_ID = DraftId.from(
            UUID.fromString("00000000-0000-0000-0000-000000000002"),
        )
    }
}
