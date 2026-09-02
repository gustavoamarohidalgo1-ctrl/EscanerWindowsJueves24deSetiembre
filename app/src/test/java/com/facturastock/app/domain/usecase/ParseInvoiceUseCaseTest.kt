package com.facturastock.app.domain.usecase

import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.InvoiceOcrSnapshot
import com.facturastock.app.domain.model.InvoiceTextBlock
import com.facturastock.app.domain.model.InvoiceTextBoundingBox
import com.facturastock.app.domain.model.InvoiceTextDocument
import com.facturastock.app.domain.model.InvoiceTextGeometry
import com.facturastock.app.domain.model.InvoiceTextLine
import com.facturastock.app.domain.model.InvoiceTextPage
import com.facturastock.app.domain.model.PurchaseDocumentType
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.model.id.OcrRunId
import com.facturastock.app.domain.normalization.ParsedInvoice
import com.facturastock.app.domain.normalization.ParsedInvoiceBlockerCode
import com.facturastock.app.domain.normalization.ParsedInvoiceConfidence
import com.facturastock.app.domain.normalization.ParsedInvoiceFieldKind
import com.facturastock.app.domain.normalization.ParsedInvoiceValueOrigin
import com.facturastock.app.domain.normalization.ParsedInvoiceWarningCode
import com.facturastock.app.testing.FakeAppConfigurationRepository
import com.facturastock.app.testing.FakeBusinessRepository
import com.facturastock.app.testing.FakeInvoiceDraftRepository
import com.facturastock.app.testing.FakeInvoiceOcrSnapshotRepository
import com.facturastock.app.testing.FakeParsedInvoiceRepository
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParseInvoiceUseCaseTest {
    @Test
    fun `complete geometric snapshot crosses all parsers with high traceable confidence`() = runTest {
        val fixture = fixture(completeSnapshot())

        val parsed = fixture.useCase(DRAFT_ID)

        assertEquals("20131312955", parsed.header.issuerRuc.selected?.value)
        assertEquals("F001-12345", parsed.header.documentNumber.selected?.value?.normalized)
        assertEquals(LocalDate.of(2026, 8, 17), parsed.header.issueDate.selected?.value)
        assertEquals("ARROZ EXTRA 5 KG", parsed.lineItems.items.single().description?.value)
        assertEquals("2", parsed.lineItems.items.single().quantity?.value?.value?.toPlainString())
        assertEquals(11_800L, parsed.totals.read.total.selected?.candidate?.value?.minorUnits)
        assertEquals(ParsedInvoiceConfidence.HIGH, parsed.confidence)
        assertEquals(ParseInvoiceUseCase.CURRENT_PARSER_VERSION, parsed.parserVersion)

        REQUIRED_COMPLETE_FIELDS.forEach { kind ->
            val trace = parsed.trace(kind)
            val selected = trace.selectedCandidateIndex?.let(trace.candidates::get)
            assertEquals(kind.name, ParsedInvoiceConfidence.HIGH, trace.confidence)
            assertFalse(kind.name, trace.requiresReview)
            assertEquals(kind.name, ParsedInvoiceValueOrigin.OCR, selected?.origin)
            assertTrue(kind.name, !selected?.rawText.isNullOrBlank())
            assertTrue(kind.name, selected?.evidence?.isNotEmpty() == true)
            assertTrue(
                kind.name,
                selected?.evidence?.all { evidence ->
                    evidence.sourceImageId == IMAGE_ID && evidence.pageIndex == 0 &&
                        evidence.rawText.isNotBlank()
                } == true,
            )
        }
        val selectedOcrCandidates = parsed.audit.fields.mapNotNull { field ->
            field.selectedCandidateIndex?.let(field.candidates::get)
                ?.takeIf { candidate -> candidate.origin == ParsedInvoiceValueOrigin.OCR }
        }
        assertTrue(selectedOcrCandidates.isNotEmpty())
        assertTrue(selectedOcrCandidates.all { candidate ->
            !candidate.rawText.isNullOrBlank() && candidate.evidence.isNotEmpty()
        })

        val projectedDraft = requireNotNull(fixture.drafts.findDraft(DRAFT_ID))
        assertEquals(DraftStatus.NEEDS_REVIEW, projectedDraft.status)
        assertEquals("20131312955", projectedDraft.supplierRucNormalized)
        assertEquals("COMERCIAL ANDINA S.A.C.", projectedDraft.supplierLegalNameNormalized)
        assertEquals(PurchaseDocumentType.INVOICE, projectedDraft.documentType)
        assertEquals("F001-12345", projectedDraft.documentNumberNormalized)
        assertEquals(LocalDate.of(2026, 8, 17), projectedDraft.issueDate)
        assertEquals(11_800L, projectedDraft.total?.minorUnits)
        val projectedLine = fixture.drafts.observeLines(DRAFT_ID).first().single()
        assertEquals("ARROZ EXTRA 5 KG", projectedLine.descriptionNormalized)
        assertEquals("2", projectedLine.quantity?.value?.toPlainString())
        assertEquals(1, fixture.parsed.publications.size)
    }

    @Test
    fun `printed charges are projected explicitly without replacing other totals`() = runTest {
        val fixture = fixture(completeSnapshot(total = "S/ 120.00", otherCharge = "S/ 2.00"))

        fixture.useCase(DRAFT_ID)

        val projected = requireNotNull(fixture.drafts.findDraft(DRAFT_ID))
        assertEquals(1_800L, projected.tax?.minorUnits)
        assertEquals(200L, projected.otherCharges?.minorUnits)
        assertEquals(12_000L, projected.total?.minorUnits)
    }

    @Test
    fun `incomplete snapshot warns for four document fields and blocks each incomplete line`() =
        runTest {
            val fixture = fixture(incompleteSnapshot())

            val parsed = fixture.useCase(DRAFT_ID)

            val missingDocumentFields = parsed.warnings
                .filter { warning -> warning.code == ParsedInvoiceWarningCode.MISSING_REQUIRED_FIELD }
                .mapNotNull { warning -> warning.field }
                .toSet()
            assertEquals(
                setOf(
                    ParsedInvoiceFieldKind.ISSUER_RUC,
                    ParsedInvoiceFieldKind.DOCUMENT_NUMBER,
                    ParsedInvoiceFieldKind.ISSUE_DATE,
                    ParsedInvoiceFieldKind.DOCUMENT_TOTAL,
                ),
                missingDocumentFields,
            )
            assertEquals(ParsedInvoiceConfidence.UNKNOWN, parsed.confidence)
            assertFalse(parsed.audit.eligibleForAutomaticConfirmation)
            assertEquals(
                setOf(
                    0 to ParsedInvoiceBlockerCode.MISSING_OR_UNRESOLVED_QUANTITY,
                    1 to ParsedInvoiceBlockerCode.MISSING_OR_UNRESOLVED_DESCRIPTION,
                ),
                parsed.blockers.map { blocker -> blocker.linePosition to blocker.code }.toSet(),
            )
            assertTrue(parsed.blockers.all { blocker -> blocker.evidence.isNotEmpty() })
            assertEquals(listOf(0, 1), parsed.lineItems.items.map { item -> item.position })
            assertEquals(DraftStatus.NEEDS_REVIEW, fixture.drafts.findDraft(DRAFT_ID)?.status)
            assertEquals(2, fixture.drafts.observeLines(DRAFT_ID).first().size)
        }

    @Test
    fun `contradictory total keeps exact difference evidence and deterministic warnings`() = runTest {
        val fixture = fixture(completeSnapshot(total = "S/ 118.03"))

        val first = fixture.useCase(DRAFT_ID)
        val second = fixture.useCase(DRAFT_ID)

        assertEquals(11_803L, first.totals.read.total.selected?.candidate?.value?.minorUnits)
        assertEquals(11_800L, first.totals.calculated.total?.value?.minorUnits)
        assertEquals(3L, first.totals.reconciliation?.difference?.minorUnits)
        val differenceWarning = first.warnings.single { warning ->
            warning.code == ParsedInvoiceWarningCode.FINANCIAL_DIFFERENCE
        }
        assertEquals(ParsedInvoiceFieldKind.DOCUMENT_TOTAL, differenceWarning.field)
        assertEquals("PEN:3", differenceWarning.detail)
        assertTrue(
            differenceWarning.evidence.any { evidence -> "118.03" in evidence.rawText },
        )
        assertTrue(first.warnings.any { warning ->
            warning.code == ParsedInvoiceWarningCode.PARSER_WARNING &&
                warning.detail == "TOTALS.PRINTED_TOTAL_DIFFERS_FROM_CALCULATED"
        })
        assertEquals(first.warnings, second.warnings)
        assertEquals(first.warnings.distinct(), first.warnings)
        assertFalse(first.audit.eligibleForAutomaticConfirmation)
    }

    @Test
    fun `reprocessing is a no-op with stable line ids and original publication timestamp`() = runTest {
        val fixture = fixture(completeSnapshot())

        val first = fixture.useCase(DRAFT_ID)
        val firstLines = fixture.drafts.observeLines(DRAFT_ID).first()
        val firstDraftTimestamp = fixture.drafts.findDraft(DRAFT_ID)?.updatedAt
        val firstPersisted = requireNotNull(fixture.parsed.find(DRAFT_ID))
        fixture.clock.current = LATER

        val second = fixture.useCase(DRAFT_ID)

        val secondLines = fixture.drafts.observeLines(DRAFT_ID).first()
        val secondPersisted = requireNotNull(fixture.parsed.find(DRAFT_ID))
        assertEquals(first.audit, second.audit)
        assertEquals(firstLines.map { line -> line.lineId }, secondLines.map { line -> line.lineId })
        assertEquals(1, secondLines.size)
        assertEquals(firstDraftTimestamp, fixture.drafts.findDraft(DRAFT_ID)?.updatedAt)
        assertEquals(firstPersisted.parsedAt, secondPersisted.parsedAt)
        assertEquals(NOW, secondPersisted.parsedAt)
        assertEquals(2, fixture.parsed.attempts.size)
        assertEquals(
            fixture.parsed.attempts[0].lines.map { line -> line.lineId },
            fixture.parsed.attempts[1].lines.map { line -> line.lineId },
        )
        assertEquals(1, fixture.parsed.publications.size)
    }

    @Test
    fun `low confidence values always require explicit confirmation`() = runTest {
        val fixture = fixture(completeSnapshot().withConfidence(699))

        val parsed = fixture.useCase(DRAFT_ID)

        assertEquals(ParsedInvoiceConfidence.LOW, parsed.confidence)
        assertTrue(parsed.warnings.any { warning ->
            warning.code == ParsedInvoiceWarningCode.LOW_CONFIDENCE_REQUIRES_CONFIRMATION &&
                warning.requiresReview
        })
        assertFalse(parsed.audit.eligibleForAutomaticConfirmation)
        assertEquals(DraftStatus.NEEDS_REVIEW, fixture.drafts.findDraft(DRAFT_ID)?.status)
    }

    private suspend fun fixture(snapshot: InvoiceOcrSnapshot): Fixture = Fixture().also { fixture ->
        fixture.drafts.createDraft(
            InvoiceDraft(
                draftId = DRAFT_ID,
                businessId = BUSINESS_ID,
                status = DraftStatus.CAPTURED,
                createdAt = NOW,
                updatedAt = NOW,
            ),
        )
        check(fixture.drafts.beginOcrRun(DRAFT_ID, RUN_ID))
        check(fixture.snapshots.publish(snapshot))
    }

    private fun completeSnapshot(
        total: String = "S/ 118.00",
        otherCharge: String? = null,
    ): InvoiceOcrSnapshot = snapshot(
        cell("COMERCIAL ANDINA S.A.C.", 40, 35, 560, 70),
        cell("RUC: 20131312955", 40, 80, 370, 115),
        cell("FACTURA ELECTRÓNICA", 650, 35, 1_160, 70),
        cell("F001-12345", 760, 85, 1_050, 120),
        cell("Fecha de emisión: 17/08/2026", 650, 140, 1_160, 175),
        cell("Moneda: PEN", 650, 190, 1_000, 225),
        cell("DESCRIPCIÓN", 60, 300, 650, 335),
        cell("CANTIDAD", 680, 300, 800, 335),
        cell("IMPORTE", 900, 300, 1_150, 335),
        cell("ARROZ EXTRA 5 KG", 60, 365, 650, 400),
        cell("2", 680, 365, 800, 400),
        cell("S/ 100.00", 900, 365, 1_150, 400),
        *totalsCells(total, otherCharge).toTypedArray(),
    )

    private fun incompleteSnapshot(): InvoiceOcrSnapshot = snapshot(
        cell("DESCRIPCIÓN", 170, 300, 590, 335),
        cell("CANT", 600, 300, 690, 335),
        cell("UNIDAD", 700, 300, 790, 335),
        cell("P.UNIT", 810, 300, 950, 335),
        cell("CAJA PLÁSTICA", 170, 365, 590, 400),
        cell("NIU", 700, 365, 790, 400),
        cell("18.00", 810, 365, 950, 400),
        cell("2", 600, 440, 690, 475),
        cell("NIU", 700, 440, 790, 475),
        cell("5.00", 810, 440, 950, 475),
    )

    private fun totalsCells(total: String, otherCharge: String?): List<Cell> = listOfNotNull(
        summaryCell("OP. GRAVADA", "S/ 100.00", 900),
        summaryCell("OP. EXONERADA", "S/ 0.00", 960),
        summaryCell("OP. INAFECTA", "S/ 0.00", 1_020),
        summaryCell("IGV 18%", "S/ 18.00", 1_080),
        otherCharge?.let { amount -> summaryCell("OTROS CARGOS", amount, 1_140) },
        summaryCell("TOTAL A PAGAR", total, if (otherCharge == null) 1_140 else 1_200),
    ).flatten()

    private fun summaryCell(label: String, value: String, top: Int): List<Cell> = listOf(
        cell(label, 650, top, 930, top + 35),
        cell(value, 960, top, 1_160, top + 35),
    )

    private fun snapshot(vararg cells: Cell): InvoiceOcrSnapshot {
        val blocks = cells.mapIndexed { blockPosition, cell ->
            val geometry = InvoiceTextGeometry(cell.box, emptyList())
            InvoiceTextBlock(
                position = blockPosition,
                text = cell.text,
                languageTag = "es-PE",
                geometry = geometry,
                lines = listOf(
                    InvoiceTextLine(
                        position = 0,
                        text = cell.text,
                        languageTag = "es-PE",
                        geometry = geometry,
                        confidencePermille = cell.confidencePermille,
                        clockwiseAngleTenths = 0,
                        elements = emptyList(),
                    ),
                ),
            )
        }
        return InvoiceOcrSnapshot(
            draftId = DRAFT_ID,
            runId = RUN_ID,
            completedAt = OCR_COMPLETED_AT,
            document = InvoiceTextDocument(
                listOf(
                    InvoiceTextPage(
                        sourceImageId = IMAGE_ID,
                        pageIndex = 0,
                        widthPx = 1_200,
                        heightPx = 1_600,
                        text = cells.joinToString("\n", transform = Cell::text),
                        blocks = blocks,
                    ),
                ),
            ),
        )
    }

    private fun InvoiceOcrSnapshot.withConfidence(confidencePermille: Int): InvoiceOcrSnapshot =
        copy(
            document = document.copy(
                pages = document.pages.map { page ->
                    page.copy(
                        blocks = page.blocks.map { block ->
                            block.copy(
                                lines = block.lines.map { line ->
                                    line.copy(confidencePermille = confidencePermille)
                                },
                            )
                        },
                    )
                },
            ),
        )

    private fun cell(
        text: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        confidencePermille: Int = 950,
    ): Cell = Cell(
        text = text,
        box = InvoiceTextBoundingBox(left, top, right, bottom),
        confidencePermille = confidencePermille,
    )

    private fun ParsedInvoice.trace(kind: ParsedInvoiceFieldKind) = audit.fields.single { field ->
        field.kind == kind && field.position == kind.expectedPosition
    }

    private val ParsedInvoiceFieldKind.expectedPosition: Int?
        get() = when (this) {
            ParsedInvoiceFieldKind.LINE_DESCRIPTION,
            ParsedInvoiceFieldKind.LINE_QUANTITY,
            -> 0
            else -> null
        }

    private data class Cell(
        val text: String,
        val box: InvoiceTextBoundingBox,
        val confidencePermille: Int,
    )

    private class MutableClock(var current: Instant) : AppClock {
        override fun now(): Instant = current
    }

    private class Fixture {
        val clock = MutableClock(NOW)
        val drafts = FakeInvoiceDraftRepository(clock)
        val snapshots = FakeInvoiceOcrSnapshotRepository(drafts)
        val parsed = FakeParsedInvoiceRepository(drafts)
        val useCase = ParseInvoiceUseCase(
            invoiceDraftRepository = drafts,
            invoiceOcrSnapshotRepository = snapshots,
            parsedInvoiceRepository = parsed,
            appConfigurationRepository = FakeAppConfigurationRepository(),
            businessRepository = FakeBusinessRepository(clock),
            appClock = clock,
            dispatcherProvider = PARSER_DISPATCHERS,
        )
    }

    private companion object {
        val PARSER_DISPATCHERS = object : DispatcherProvider {
            override val io = Dispatchers.IO
            override val default = Dispatchers.Default
            override val main = Dispatchers.Default
        }
        val BUSINESS_ID: BusinessId = BusinessId.from(UUID(0L, 1L))
        val DRAFT_ID: DraftId = DraftId.from(UUID(0L, 2L))
        val RUN_ID: OcrRunId = OcrRunId.from(UUID(0L, 3L))
        val IMAGE_ID: ImageId = ImageId.from(UUID(0L, 4L))
        val NOW: Instant = Instant.parse("2026-08-10T15:00:00Z")
        val LATER: Instant = Instant.parse("2026-08-10T16:00:00Z")
        val OCR_COMPLETED_AT: Instant = Instant.parse("2026-08-10T14:59:00Z")

        val REQUIRED_COMPLETE_FIELDS = listOf(
            ParsedInvoiceFieldKind.ISSUER_RUC,
            ParsedInvoiceFieldKind.DOCUMENT_NUMBER,
            ParsedInvoiceFieldKind.ISSUE_DATE,
            ParsedInvoiceFieldKind.LINE_DESCRIPTION,
            ParsedInvoiceFieldKind.LINE_QUANTITY,
            ParsedInvoiceFieldKind.DOCUMENT_TOTAL,
        )
    }
}
