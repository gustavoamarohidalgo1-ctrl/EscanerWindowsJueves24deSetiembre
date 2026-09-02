package com.facturastock.app.data.demo

import com.facturastock.app.domain.config.TaxRate
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.InvoiceOcrSnapshot
import com.facturastock.app.domain.model.RucValidator
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.model.id.OcrRunId
import com.facturastock.app.domain.normalization.InvoiceParseContext
import com.facturastock.app.domain.normalization.InvoiceParser
import com.facturastock.app.domain.normalization.InvoiceTotalsDifferenceConvention
import com.facturastock.app.domain.repository.OcrImageFile
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DemoInvoiceFixtureTest {
    @Test
    fun `fixture has exactly 38 synthetic lines and the three product cases`() {
        val lines = DemoInvoiceFixture.lines

        assertEquals(DemoInvoiceFixture.EXPECTED_LINE_COUNT, lines.size)
        assertEquals((0 until 38).toList(), lines.map(DemoInvoiceLine::position))
        assertEquals(
            mapOf(
                DemoProductCase.EXISTING to 36,
                DemoProductCase.AMBIGUOUS to 1,
                DemoProductCase.NEW to 1,
            ),
            lines.groupingBy(DemoInvoiceLine::productCase).eachCount(),
        )
        assertTrue(lines.all { line -> line.description.startsWith("[DEMO]") })
        assertTrue(DemoInvoiceFixture.SUPPLIER_LEGAL_NAME.startsWith("[DEMO]"))
        assertTrue(RucValidator.hasValidChecksum(DemoInvoiceFixture.SUPPLIER_RUC))
    }

    @Test
    fun `fixture amounts preserve exact subtotal IGV target and positive adjustment`() {
        val lineSum = DemoInvoiceFixture.lines.sumOf(DemoInvoiceLine::lineTotalMinorUnits)
        val lineTax = DemoInvoiceFixture.lines.sumOf(DemoInvoiceLine::taxMinorUnits)
        val lineNet = DemoInvoiceFixture.lines.sumOf {
            it.lineTotalMinorUnits - it.taxMinorUnits
        }

        assertEquals(9_780L, lineSum)
        assertEquals(1_492L, lineTax)
        assertEquals(8_288L, lineNet)
        assertEquals(29, DemoInvoiceFixture.lines.count { it.taxMinorUnits == 39L })
        assertEquals(8, DemoInvoiceFixture.lines.count { it.taxMinorUnits == 40L })
        assertEquals(1, DemoInvoiceFixture.lines.count { it.taxMinorUnits == 41L })
        assertEquals(
            lineSum,
            DemoInvoiceFixture.SUBTOTAL_MINOR_UNITS + DemoInvoiceFixture.IGV_MINOR_UNITS,
        )
        assertEquals(9_783L, DemoInvoiceFixture.TARGET_TOTAL_MINOR_UNITS)
        assertEquals(
            3L,
            DemoInvoiceFixture.TARGET_TOTAL_MINOR_UNITS - lineSum,
        )
        assertTrue(DemoInvoiceFixture.REQUIRED_ADJUSTMENT_REASON.startsWith("[DEMO]"))
    }

    @Test
    fun `parser checks cancellation cooperatively while building the line audit`() = runTest {
        val document = DemoInvoiceFixture.recognize(listOf(OCR_PAGE))
        var checkpoints = 0

        try {
            InvoiceParser().parse(
                snapshot = InvoiceOcrSnapshot(
                    draftId = DRAFT_ID,
                    runId = RUN_ID,
                    completedAt = Instant.parse("2026-08-14T15:00:00Z"),
                    document = document,
                ),
                context = InvoiceParseContext(
                    parserVersion = 1,
                    contextFingerprint = "d".repeat(64),
                    buyerRuc = null,
                    fallbackCurrency = PEN,
                    referenceIgvRate = TaxRate(BigDecimal("18")),
                ),
                checkpoint = {
                    checkpoints++
                    if (checkpoints == PARSER_CANCELLATION_CHECKPOINT) {
                        throw CancellationException("cancelled")
                    }
                },
            )
            fail("se esperaba cancelación")
        } catch (_: CancellationException) {
            assertEquals(PARSER_CANCELLATION_CHECKPOINT, checkpoints)
        }
    }

    @Test
    fun `fake OCR is one geometric page and parser returns 38 lines plus three-cent reconciliation`() =
        runTest {
            val document = DemoInvoiceFixture.recognize(listOf(OCR_PAGE))
            val page = document.pages.single()
            assertEquals(0, page.pageIndex)
            assertEquals(OCR_PAGE.sourceImageId, page.sourceImageId)
            assertTrue(page.blocks.isNotEmpty())
            assertTrue(page.blocks.all { block -> block.geometry.boundingBox != null })

            val parsed = InvoiceParser().parse(
                snapshot = InvoiceOcrSnapshot(
                    draftId = DRAFT_ID,
                    runId = RUN_ID,
                    completedAt = Instant.parse("2026-08-14T15:00:00Z"),
                    document = document,
                ),
                context = InvoiceParseContext(
                    parserVersion = 1,
                    contextFingerprint = "d".repeat(64),
                    buyerRuc = null,
                    fallbackCurrency = PEN,
                    referenceIgvRate = TaxRate(BigDecimal("18")),
                ),
            )

            assertEquals(DemoInvoiceFixture.SUPPLIER_RUC, parsed.header.issuerRuc.selected?.value)
            assertEquals(
                DemoInvoiceFixture.SUPPLIER_LEGAL_NAME,
                parsed.header.issuerLegalName.selected?.value,
            )
            assertEquals(
                DemoInvoiceFixture.DOCUMENT_NUMBER,
                parsed.header.documentNumber.selected?.value?.normalized,
            )
            assertEquals(DemoInvoiceFixture.issueDate, parsed.header.issueDate.selected?.value)
            assertEquals(PEN, parsed.header.currency.selected?.value)

            val parsedLines = parsed.lineItems.items
            assertEquals(38, parsedLines.size)
            assertEquals((0 until 38).toList(), parsedLines.map { line -> line.position })
            DemoInvoiceFixture.lines.zip(parsedLines).forEach { (expected, actual) ->
                assertEquals(expected.supplierCode, actual.code?.value)
                assertEquals(expected.description, actual.description?.value)
                assertEquals(BigDecimal(expected.quantityText), actual.quantity?.value?.value)
                assertEquals(expected.unitCode, actual.unit?.value?.name)
                assertEquals(expected.lineTotalMinorUnits, actual.total?.value?.minorUnits)
            }
            assertEquals(
                9_780L,
                parsedLines.sumOf { line -> requireNotNull(line.total?.value).minorUnits },
            )
            assertTrue(parsed.blockers.isEmpty())

            assertEquals(
                8_288L,
                parsed.totals.read.subtotal.selected?.candidate?.value?.minorUnits,
            )
            assertEquals(
                1_492L,
                parsed.totals.read.igv.selected?.candidate?.value?.minorUnits,
            )
            assertEquals(
                9_783L,
                parsed.totals.read.total.selected?.candidate?.value?.minorUnits,
            )
            assertEquals(9_780L, parsed.totals.calculated.total?.value?.minorUnits)

            val reconciliation = parsed.totals.reconciliation
            assertNotNull(reconciliation)
            assertEquals(
                InvoiceTotalsDifferenceConvention.READ_MINUS_CALCULATED,
                reconciliation?.convention,
            )
            assertEquals(3L, reconciliation?.difference?.minorUnits)
        }

    private companion object {
        const val PARSER_CANCELLATION_CHECKPOINT = 12
        val PEN: CurrencyCode = CurrencyCode.of("PEN")
        val DRAFT_ID: DraftId = DraftId.from(
            UUID.fromString("35000000-0000-0000-0000-000000000001"),
        )
        val RUN_ID: OcrRunId = OcrRunId.from(
            UUID.fromString("35000000-0000-0000-0000-000000000002"),
        )
        val IMAGE_ID: ImageId = ImageId.from(
            UUID.fromString("35000000-0000-0000-0000-000000000003"),
        )
        val OCR_PAGE = OcrImageFile(
            sourceImageId = IMAGE_ID,
            relativePath = "draft_images/demo/ocr/demo-invoice.jpg",
            mimeType = "image/jpeg",
            widthPx = DemoInvoiceFixture.BASE_PAGE_WIDTH_PX,
            heightPx = DemoInvoiceFixture.BASE_PAGE_HEIGHT_PX,
            fileSizeBytes = 256_000L,
        )
    }
}
