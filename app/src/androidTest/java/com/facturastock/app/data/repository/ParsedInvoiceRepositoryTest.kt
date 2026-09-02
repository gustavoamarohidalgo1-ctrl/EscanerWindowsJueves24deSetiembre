package com.facturastock.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.domain.config.TaxRate
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.Business
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.InvoiceLine
import com.facturastock.app.domain.model.InvoiceOcrSnapshot
import com.facturastock.app.domain.model.InvoiceTextDocument
import com.facturastock.app.domain.model.InvoiceTextPage
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.PurchaseDocumentType
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.model.id.OcrRunId
import com.facturastock.app.domain.normalization.CalculatedInvoiceTotals
import com.facturastock.app.domain.normalization.HeaderField
import com.facturastock.app.domain.normalization.InvoiceHeaderParseResult
import com.facturastock.app.domain.normalization.InvoiceLineItemsParseResult
import com.facturastock.app.domain.normalization.InvoiceTotalsParseResult
import com.facturastock.app.domain.normalization.ParsedInvoice
import com.facturastock.app.domain.normalization.ParsedInvoiceAudit
import com.facturastock.app.domain.normalization.ParsedInvoiceConfidence
import com.facturastock.app.domain.normalization.ReadInvoiceTotals
import com.facturastock.app.domain.repository.ParsedInvoicePublication
import com.facturastock.app.domain.repository.PublishParsedInvoiceResult
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ParsedInvoiceRepositoryTest {
    private lateinit var database: FacturaStockDatabase
    private lateinit var clock: TestClock
    private lateinit var businesses: RoomBusinessRepository
    private lateinit var drafts: RoomInvoiceDraftRepository
    private lateinit var snapshots: RoomInvoiceOcrSnapshotRepository
    private lateinit var parsedInvoices: RoomParsedInvoiceRepository

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
        parsedInvoices = RoomParsedInvoiceRepository(
            database = database,
            parsedInvoiceDao = database.parsedInvoiceDao(),
            invoiceDraftDao = database.invoiceDraftDao(),
            invoiceLineDao = database.invoiceLineDao(),
            invoiceOcrSnapshotDao = database.invoiceOcrSnapshotDao(),
            dispatchers = testDispatchers,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun publishPersistsAuditProjectionAndNeedsReviewInOneCommit() = runBlocking {
        val originalDraft = seedReadyDraft(RUN_ID)
        val parsedAt = BASE_TIME.plusSeconds(20)
        val publication = publication(originalDraft, parsedAt = parsedAt)

        val result = parsedInvoices.publish(publication)

        assertEquals(PublishParsedInvoiceResult.PUBLISHED, result)
        val stored = requireNotNull(parsedInvoices.find(DRAFT_ID))
        assertEquals(publication.parsedInvoice.audit, stored.audit)
        assertEquals(parsedAt, stored.parsedAt)
        val draft = requireNotNull(drafts.findDraft(DRAFT_ID))
        assertEquals(DraftStatus.NEEDS_REVIEW, draft.status)
        assertEquals(originalDraft.createdAt, draft.createdAt)
        assertEquals(parsedAt, draft.updatedAt)
        assertEquals("20123456789", draft.supplierRucNormalized)
        assertEquals("Proveedor Parser SAC", draft.supplierLegalNameNormalized)
        assertEquals(PurchaseDocumentType.INVOICE, draft.documentType)
        assertEquals("F001-00000042", draft.documentNumberNormalized)
        assertEquals(34L, draft.otherCharges?.minorUnits)
        assertEquals(1_234L, draft.total?.minorUnits)
        val line = drafts.observeLines(DRAFT_ID).first().single()
        assertEquals("CAFÉ MOLIDO", line.descriptionNormalized)
        assertEquals(parsedAt, line.createdAt)
        assertEquals(parsedAt, line.updatedAt)
    }

    @Test
    fun exactRetryIsANoOpEvenWhenItCarriesANewerParsedAt() = runBlocking {
        val originalDraft = seedReadyDraft(RUN_ID)
        val firstParsedAt = BASE_TIME.plusSeconds(20)
        val first = publication(originalDraft, parsedAt = firstParsedAt)
        assertEquals(PublishParsedInvoiceResult.PUBLISHED, parsedInvoices.publish(first))

        val retry = first.copy(parsedAt = BASE_TIME.plusSeconds(90))
        val result = parsedInvoices.publish(retry)

        assertEquals(PublishParsedInvoiceResult.ALREADY_PUBLISHED, result)
        assertEquals(firstParsedAt, parsedInvoices.find(DRAFT_ID)?.parsedAt)
        assertEquals(firstParsedAt, drafts.findDraft(DRAFT_ID)?.updatedAt)
        val line = drafts.observeLines(DRAFT_ID).first().single()
        assertEquals(firstParsedAt, line.createdAt)
        assertEquals(firstParsedAt, line.updatedAt)
    }

    @Test
    fun concurrentExactPublishHasOneWriterAndOneIdempotentReader() = runBlocking {
        val originalDraft = seedReadyDraft(RUN_ID)
        val publication = publication(originalDraft, parsedAt = BASE_TIME.plusSeconds(20))

        val results = coroutineScope {
            List(2) {
                async(Dispatchers.Default) { parsedInvoices.publish(publication) }
            }.awaitAll()
        }

        assertEquals(1, results.count { it == PublishParsedInvoiceResult.PUBLISHED })
        assertEquals(1, results.count { it == PublishParsedInvoiceResult.ALREADY_PUBLISHED })
        assertEquals(DraftStatus.NEEDS_REVIEW, drafts.findDraft(DRAFT_ID)?.status)
        assertEquals(1, drafts.observeLines(DRAFT_ID).first().size)
    }

    @Test
    fun differentAuditCannotOverwriteAnExistingNeedsReviewPublication() = runBlocking {
        val originalDraft = seedReadyDraft(RUN_ID)
        val first = publication(originalDraft, parsedAt = BASE_TIME.plusSeconds(20))
        assertEquals(PublishParsedInvoiceResult.PUBLISHED, parsedInvoices.publish(first))
        val conflictingParsed = parsedInvoice(RUN_ID, fingerprint = "b".repeat(64))

        val result = parsedInvoices.publish(
            first.copy(parsedInvoice = conflictingParsed, parsedAt = BASE_TIME.plusSeconds(30)),
        )

        assertEquals(PublishParsedInvoiceResult.CONFLICT, result)
        assertEquals(first.parsedInvoice.audit, parsedInvoices.find(DRAFT_ID)?.audit)
        assertEquals(BASE_TIME.plusSeconds(20), drafts.findDraft(DRAFT_ID)?.updatedAt)
    }

    @Test
    fun staleRunIsRejectedBeforeAnyProjectionIsWritten() = runBlocking {
        val originalDraft = seedReadyDraft(RUN_ID)
        val stale = publication(
            originalDraft,
            parsedAt = BASE_TIME.plusSeconds(20),
            runId = ocrRunId(99),
        )

        val result = parsedInvoices.publish(stale)

        assertEquals(PublishParsedInvoiceResult.STALE_SNAPSHOT, result)
        assertNull(parsedInvoices.find(DRAFT_ID))
        assertEquals(DraftStatus.OCR_READY, drafts.findDraft(DRAFT_ID)?.status)
        assertTrue(drafts.observeLines(DRAFT_ID).first().isEmpty())
    }

    @Test
    fun nonReadyDraftIsRejectedEvenWhenTheSnapshotStillMatches() = runBlocking {
        val originalDraft = seedReadyDraft(RUN_ID)
        val ready = requireNotNull(drafts.findDraft(DRAFT_ID))
        assertTrue(drafts.updateDraft(ready.copy(status = DraftStatus.READY_TO_POST)))

        val result = parsedInvoices.publish(
            publication(originalDraft, parsedAt = BASE_TIME.plusSeconds(20)),
        )

        assertEquals(PublishParsedInvoiceResult.DRAFT_NOT_READY, result)
        assertNull(parsedInvoices.find(DRAFT_ID))
        assertEquals(DraftStatus.READY_TO_POST, drafts.findDraft(DRAFT_ID)?.status)
        assertTrue(drafts.observeLines(DRAFT_ID).first().isEmpty())
    }

    @Test
    fun lineConstraintFailureRollsBackAuditDeletionProjectionAndStatus() = runBlocking {
        val originalDraft = seedReadyDraft(RUN_ID)
        val originalLine = line(lineId(70), position = 0, description = "LÍNEA ANTERIOR")
        drafts.addLine(originalLine)
        val duplicateId = lineId(80)
        val badPublication = publication(
            originalDraft,
            parsedAt = BASE_TIME.plusSeconds(20),
            lines = listOf(
                line(duplicateId, position = 0, description = "NUEVA UNO"),
                line(duplicateId, position = 1, description = "NUEVA DOS"),
            ),
        )

        assertThrows(StorageException::class.java) {
            runBlocking { parsedInvoices.publish(badPublication) }
        }

        assertNull(parsedInvoices.find(DRAFT_ID))
        assertEquals(DraftStatus.OCR_READY, drafts.findDraft(DRAFT_ID)?.status)
        val restoredLines = drafts.observeLines(DRAFT_ID).first()
        assertEquals(listOf(originalLine.lineId), restoredLines.map(InvoiceLine::lineId))
    }

    private suspend fun seedReadyDraft(runId: OcrRunId): InvoiceDraft {
        businesses.create(
            Business(
                businessId = BUSINESS_ID,
                legalName = "Negocio Parser SAC",
                ruc = "20987654321",
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
        assertTrue(drafts.beginOcrRun(DRAFT_ID, runId))
        assertTrue(
            snapshots.publish(
                InvoiceOcrSnapshot(
                    draftId = DRAFT_ID,
                    runId = runId,
                    completedAt = BASE_TIME.plusSeconds(10),
                    document = InvoiceTextDocument(
                        listOf(
                            InvoiceTextPage(
                                sourceImageId = imageId(50),
                                pageIndex = 0,
                                widthPx = 1_200,
                                heightPx = 1_600,
                                text = "FACTURA DE PRUEBA",
                                blocks = emptyList(),
                            ),
                        ),
                    ),
                ),
            ),
        )
        return requireNotNull(drafts.findDraft(DRAFT_ID))
    }

    private fun publication(
        originalDraft: InvoiceDraft,
        parsedAt: Instant,
        runId: OcrRunId = RUN_ID,
        lines: List<InvoiceLine> = listOf(
            line(lineId(60), position = 0, description = "CAFÉ MOLIDO"),
        ),
    ): ParsedInvoicePublication {
        val currency = CurrencyCode.of("PEN")
        return ParsedInvoicePublication(
            parsedInvoice = parsedInvoice(runId),
            draft = originalDraft.copy(
                status = DraftStatus.NEEDS_REVIEW,
                supplierRucRaw = "RUC 20123456789",
                supplierRucNormalized = "20123456789",
                supplierLegalNameRaw = "PROVEEDOR PARSER SAC",
                supplierLegalNameNormalized = "Proveedor Parser SAC",
                documentType = PurchaseDocumentType.INVOICE,
                documentNumberRaw = "F001-42",
                documentNumberNormalized = "F001-00000042",
                currency = currency,
                subtotal = Money.ofMinor(1_000, currency),
                tax = Money.ofMinor(234, currency),
                otherCharges = Money.ofMinor(34, currency),
                total = Money.ofMinor(1_234, currency),
                headerConfidence = 910,
                updatedAt = parsedAt,
            ),
            lines = lines,
            parsedAt = parsedAt,
        )
    }

    private fun parsedInvoice(
        runId: OcrRunId,
        fingerprint: String = "a".repeat(64),
    ): ParsedInvoice = ParsedInvoice(
        header = InvoiceHeaderParseResult(
            draftId = DRAFT_ID,
            runId = runId,
            documentType = HeaderField(),
            issuerRuc = HeaderField(),
            issuerLegalName = HeaderField(),
            documentNumber = HeaderField(),
            issueDate = HeaderField(),
            currency = HeaderField(),
        ),
        lineItems = InvoiceLineItemsParseResult(
            draftId = DRAFT_ID,
            runId = runId,
            currency = null,
            items = emptyList(),
        ),
        totals = InvoiceTotalsParseResult(
            draftId = DRAFT_ID,
            runId = runId,
            currency = null,
            referenceIgvRate = TaxRate(BigDecimal("18")),
            taxRoundingMode = null,
            read = ReadInvoiceTotals(),
            calculated = CalculatedInvoiceTotals(),
            reconciliation = null,
        ),
        audit = ParsedInvoiceAudit(
            draftId = DRAFT_ID,
            runId = runId,
            parserVersion = 1,
            contextFingerprint = fingerprint,
            confidence = ParsedInvoiceConfidence.UNKNOWN,
            fields = emptyList(),
            warnings = emptyList(),
            blockers = emptyList(),
        ),
    )

    private fun line(lineId: LineId, position: Int, description: String): InvoiceLine = InvoiceLine(
        lineId = lineId,
        draftId = DRAFT_ID,
        businessId = BUSINESS_ID,
        position = position,
        descriptionRaw = description,
        descriptionNormalized = description,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private fun ocrRunId(seed: Int) = OcrRunId.from(uuid(seed))
    private fun imageId(seed: Int) = ImageId.from(uuid(seed))
    private fun lineId(seed: Int) = LineId.from(uuid(seed))

    private fun uuid(seed: Int): UUID =
        UUID.fromString("00000000-0000-0000-0000-%012d".format(seed))

    private companion object {
        val BASE_TIME: Instant = Instant.parse("2026-08-10T12:00:00Z")
        val BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
        )
        val DRAFT_ID: DraftId = DraftId.from(
            UUID.fromString("00000000-0000-0000-0000-000000000002"),
        )
        val RUN_ID: OcrRunId = OcrRunId.from(
            UUID.fromString("00000000-0000-0000-0000-000000000003"),
        )
    }
}
