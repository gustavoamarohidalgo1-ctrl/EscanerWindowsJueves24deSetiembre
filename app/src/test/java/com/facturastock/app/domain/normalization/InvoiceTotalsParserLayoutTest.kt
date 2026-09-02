package com.facturastock.app.domain.normalization

import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.model.InvoiceOcrSnapshot
import com.facturastock.app.domain.model.InvoiceTextBlock
import com.facturastock.app.domain.model.InvoiceTextBoundingBox
import com.facturastock.app.domain.model.InvoiceTextDocument
import com.facturastock.app.domain.model.InvoiceTextElement
import com.facturastock.app.domain.model.InvoiceTextGeometry
import com.facturastock.app.domain.model.InvoiceTextLine
import com.facturastock.app.domain.model.InvoiceTextPage
import com.facturastock.app.domain.model.InvoiceTextPoint
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.model.id.OcrRunId
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InvoiceTotalsParserLayoutTest {
    @Test
    fun `layout 1 reads a complete right-aligned financial summary and reconciles rounding`() {
        val result = parse(
            page(
                blocks = summaryRows(
                    "OP. GRAVADA" to "S/ 100.00",
                    "OP. EXONERADA" to "S/ 20.00",
                    "OP. INAFECTA" to "S/ 5.00",
                    "DESCUENTO GLOBAL" to "S/ 10.00",
                    "SUBTOTAL" to "S/ 115.00",
                    "IGV 18%" to "S/ 18.00",
                    "OTROS CARGOS" to "S/ 2.00",
                    "DIFERENCIA DE REDONDEO" to "- S/ 0.01",
                    "TOTAL A PAGAR" to "S/ 134.99",
                ),
            ),
        )

        assertEquals(DRAFT_ID, result.draftId)
        assertEquals(RUN_ID, result.runId)
        assertEquals(PEN, result.currency)
        assertMoney(10_000L, result.read.taxableOperations)
        assertMoney(2_000L, result.read.exemptOperations)
        assertMoney(500L, result.read.unaffectedOperations)
        assertMoney(1_000L, result.read.discounts)
        assertMoney(11_500L, result.read.subtotal)
        assertMoney(1_800L, result.read.igv)
        assertEquals(BigDecimal("18"), result.read.printedIgvRate.selected?.candidate?.value?.percent)
        assertEquals(1, result.read.otherCharges.size)
        assertEquals(InvoiceOtherChargeKind.OTHER_CHARGES, result.read.otherCharges.single().kind)
        assertMoney(200L, result.read.otherCharges.single().amount.candidate.value)

        val rounding = result.read.rounding.selected
        assertEquals(InvoicePrintedSign.NEGATIVE, rounding?.printedSign)
        assertEquals(-1L, rounding?.candidate?.value?.signedAmount?.minorUnits)
        assertEquals(InvoiceRoundingDirection.DECREASE, rounding?.candidate?.value?.direction)
        assertEquals(
            InvoiceRoundingReason.PRINTED_ROUNDING_DIFFERENCE,
            rounding?.candidate?.value?.reason,
        )
        assertMoney(13_499L, result.read.total)

        assertNull(result.calculated.subtotal)
        assertMoney(1_800L, result.calculated.igv?.value)
        assertNull(result.calculated.totalBeforeRounding)
        assertNull(result.calculated.total)
        assertNull(result.reconciliation)
        assertTrue(InvoiceTotalsWarning.DISCOUNT_SEMANTICS_NOT_EVALUATED in result.warnings)
    }

    @Test
    fun `layout 2 sorts shuffled corner-only blocks and derives their effective geometry`() {
        val blocks = summaryRows(
            "OPERACIONES GRAVADAS" to "S/ 80.00",
            "OPERACIONES EXONERADAS" to "S/ 0.00",
            "OPERACIONES INAFECTAS" to "S/ 0.00",
            "SUB TOTAL" to "S/ 80.00",
            "TOTAL IGV 18%" to "S/ 14.40",
            "IMPORTE TOTAL" to "S/ 94.40",
            cornerOnly = true,
        ).reversed()
        val sourcePage = page(blocks = blocks)
        val result = parse(sourcePage)

        assertTrue(
            sourcePage.blocks.flatMap(InvoiceTextBlock::lines)
                .all { line -> line.geometry.boundingBox == null && line.geometry.cornerPoints.size == 4 },
        )
        assertMoney(8_000L, result.read.taxableOperations)
        assertMoney(0L, result.read.exemptOperations)
        assertMoney(0L, result.read.unaffectedOperations)
        assertMoney(8_000L, result.read.subtotal)
        assertMoney(1_440L, result.read.igv)
        assertMoney(9_440L, result.read.total)

        val selected = listOf(
            result.read.taxableOperations.selected,
            result.read.exemptOperations.selected,
            result.read.unaffectedOperations.selected,
            result.read.subtotal.selected,
            result.read.igv.selected,
            result.read.total.selected,
        )
        assertTrue(selected.all { occurrence -> occurrence?.boundingBox != null })
        assertTrue(
            selected.flatMap { occurrence -> occurrence?.evidence.orEmpty() }
                .all { evidence -> evidence.cornerPoints.size == 4 },
        )
        assertEquals(
            listOf(480, 540, 600, 660, 720, 780),
            selected.map { occurrence -> occurrence?.boundingBox?.topPx },
        )
    }

    @Test
    fun `layout 3 uses element geometry for narrow inline thermal rows`() {
        val result = parse(
            page(
                widthPx = 600,
                blocks = listOf(
                    inlineRow("SUBTOTAL", "S/ 50.00", 720, widthPx = 600),
                    inlineRow("DESCUENTO", "S/ 5.00", 780, widthPx = 600),
                    inlineRow("IGV 18%", "S/ 8.10", 840, widthPx = 600),
                    inlineRow("TOTAL VENTA", "S/ 53.10", 900, widthPx = 600),
                ),
            ),
        )

        assertMoney(5_000L, result.read.subtotal)
        assertMoney(500L, result.read.discounts)
        assertMoney(810L, result.read.igv)
        assertMoney(5_310L, result.read.total)
        assertNull(result.calculated.total)
        assertTrue(InvoiceTotalsWarning.DISCOUNT_SEMANTICS_NOT_EVALUATED in result.warnings)
        assertTrue(
            result.read.total.selected?.reasons.orEmpty().contains(InvoiceTotalReason.SAME_ROW_AS_LABEL),
        )
        assertEquals(
            listOf("TOTAL VENTA", "S/ 53.10"),
            result.read.total.selected?.evidence?.map(CandidateEvidence::rawText),
        )
        assertTrue(
            result.read.total.selected?.evidence.orEmpty()
                .all { evidence -> evidence.elementPosition != null },
        )
    }

    @Test
    fun `layout 4 keeps multiple charge kinds individually ordered and calculates all of them`() {
        val result = parse(
            page(
                blocks = summaryRows(
                    "SUBTOTAL" to "S/ 100.00",
                    "IGV" to "S/ 0.00",
                    "ISC" to "S/ 3.00",
                    "ICBPER" to "S/ 0.50",
                    "OTROS CARGOS" to "S/ 1.00",
                    "TOTAL DEL COMPROBANTE" to "S/ 104.50",
                ),
            ),
        )

        assertEquals(listOf(0, 1, 2), result.read.otherCharges.map(InvoiceOtherCharge::position))
        assertEquals(
            listOf(
                InvoiceOtherChargeKind.ISC,
                InvoiceOtherChargeKind.ICBPER,
                InvoiceOtherChargeKind.OTHER_CHARGES,
            ),
            result.read.otherCharges.map(InvoiceOtherCharge::kind),
        )
        assertEquals(
            listOf(300L, 50L, 100L),
            result.read.otherCharges.map { charge -> charge.amount.candidate.value?.minorUnits },
        )
        assertEquals(listOf("ISC", "ICBPER", "OTROS CARGOS"), result.read.otherCharges.map(InvoiceOtherCharge::label))
        assertMoney(10_450L, result.calculated.totalBeforeRounding?.value)
        assertMoney(10_450L, result.calculated.total?.value)
        assertMoney(0L, result.reconciliation?.difference)
    }

    @Test
    fun `two charges with the same label in one summary remain additive not alternative`() {
        val result = parse(
            page(
                blocks = summaryRows(
                    "SUBTOTAL" to "S/ 100.00",
                    "IGV" to "S/ 0.00",
                    "OTROS CARGOS" to "S/ 1.00",
                    "OTROS CARGOS" to "S/ 2.00",
                    "TOTAL A PAGAR" to "S/ 103.00",
                ),
            ),
        )

        assertEquals(listOf(100L, 200L), result.read.otherCharges.map { charge ->
            charge.amount.candidate.value?.minorUnits
        })
        assertMoney(10_300L, result.calculated.total?.value)
        assertMoney(0L, result.reconciliation?.difference)
        assertFalse(InvoiceTotalsWarning.MULTIPLE_PRINTED_VALUES in result.warnings)
    }

    @Test
    fun `layout 5 preserves printed signs for discounts charges and rounding`() {
        val result = parse(
            page(
                blocks = summaryRows(
                    "SUBTOTAL" to "S/ 100.00",
                    "DESCUENTO GLOBAL" to "- S/ 10.00",
                    "OTROS CARGOS" to "+ S/ 2.00",
                    "REDONDEO" to "- S/ 0.01",
                    "TOTAL A PAGAR" to "S/ 91.99",
                ),
            ),
        )

        assertMoney(-1_000L, result.read.discounts)
        assertEquals(InvoicePrintedSign.NEGATIVE, result.read.discounts.selected?.printedSign)
        assertMoney(200L, result.read.otherCharges.single().amount.candidate.value)
        assertEquals(InvoicePrintedSign.POSITIVE, result.read.otherCharges.single().amount.printedSign)

        val rounding = result.read.rounding.selected
        assertEquals(InvoicePrintedSign.NEGATIVE, rounding?.printedSign)
        assertMoney(-1L, rounding?.candidate?.value?.signedAmount)
        assertEquals(InvoiceRoundingDirection.DECREASE, rounding?.candidate?.value?.direction)
        assertFalse(InvoiceTotalOccurrenceWarning.MISSING_EXPLICIT_SIGN in rounding?.warnings.orEmpty())
        assertNull(result.calculated.totalBeforeRounding)
        assertNull(result.calculated.total)
        assertNull(result.reconciliation)
        assertTrue(InvoiceTotalsWarning.DISCOUNT_SEMANTICS_NOT_EVALUATED in result.warnings)
    }

    @Test
    fun `layout 6 distinguishes an absent component from a printed zero`() {
        val result = parse(
            page(
                blocks = summaryRows(
                    "OPERACIONES GRAVADAS" to "S/ 100.00",
                    "OPERACIONES EXONERADAS" to "S/ 0.00",
                    "IGV 18%" to "S/ 18.00",
                    "TOTAL" to "S/ 118.00",
                ),
            ),
        )

        assertMoney(0L, result.read.exemptOperations)
        assertNull(result.read.unaffectedOperations.selected)
        assertTrue(result.read.unaffectedOperations.all.isEmpty())
        assertNull(result.read.discounts.selected)
        assertNull(result.read.subtotal.selected)
        assertTrue(result.read.otherCharges.isEmpty())
        assertNull(result.read.rounding.selected)
        assertMoney(1_800L, result.calculated.igv?.value)
        assertNull(result.calculated.subtotal)
        assertNull(result.calculated.totalBeforeRounding)
        assertNull(result.calculated.total)
        assertNull(result.reconciliation)
    }

    @Test
    fun `layout 7 ignores page carryovers and selects the final multipage summary`() {
        val firstPage = page(
            pageIndex = 0,
            blocks = listOf(
                wideLine(
                    element("DESCRIPCIÓN", rect(80, 300, 650, 336)),
                    element("CANT", rect(680, 300, 790, 336)),
                    element("TOTAL", rect(930, 300, 1_150, 336)),
                ),
                wideLine(
                    element("CEREAL TOTAL 500G", rect(80, 360, 650, 396)),
                    element("2", rect(680, 360, 790, 396)),
                    element("S/ 80.00", rect(930, 360, 1_150, 396)),
                ),
                cell("SUBTOTAL PÁGINA S/ 80.00", 700, 1_280, 1_150, 1_320),
                cell("CONTINÚA EN PÁGINA 2", 700, 1_340, 1_150, 1_380),
            ),
        )
        val finalPage = page(
            pageIndex = 1,
            blocks = summaryRows(
                "OPERACIONES GRAVADAS" to "S/ 100.00",
                "OPERACIONES EXONERADAS" to "S/ 0.00",
                "OPERACIONES INAFECTAS" to "S/ 0.00",
                "IGV 18%" to "S/ 18.00",
                "TOTAL A PAGAR" to "S/ 118.00",
            ),
        )
        val result = parse(firstPage, finalPage)

        assertTrue(result.read.subtotal.all.isEmpty())
        assertMoney(10_000L, result.read.taxableOperations)
        assertMoney(1_800L, result.read.igv)
        assertMoney(11_800L, result.read.total)
        assertEquals(1, result.read.total.selected?.pageIndex)
        assertTrue(
            result.read.total.selected?.evidence.orEmpty().all { evidence -> evidence.pageIndex == 1 },
        )
        assertFalse(
            result.read.total.all.flatMap(InvoiceTotalOccurrence<Money>::evidence)
                .any { evidence -> "CEREAL" in evidence.rawText || "PÁGINA" in evidence.rawText },
        )
    }

    @Test
    fun `repeated multipage summary does not add the same charge twice`() {
        val first = page(
            pageIndex = 0,
            blocks = summaryRows(
                "SUBTOTAL" to "S/ 100.00",
                "IGV" to "S/ 0.00",
                "OTROS CARGOS" to "S/ 2.00",
                "TOTAL A PAGAR" to "S/ 102.00",
            ),
        )
        val final = page(
            pageIndex = 1,
            blocks = summaryRows(
                "SUBTOTAL" to "S/ 100.00",
                "IGV" to "S/ 0.00",
                "OTROS CARGOS" to "S/ 2.00",
                "TOTAL A PAGAR" to "S/ 102.00",
            ),
        )

        val result = parse(first, final)

        assertEquals(1, result.read.otherCharges.size)
        assertEquals(1, result.read.otherCharges.single().amount.pageIndex)
        assertMoney(200L, result.read.otherCharges.single().amount.candidate.value)
        assertTrue(InvoiceTotalsWarning.PRIOR_SUMMARY_ALTERNATIVES in result.warnings)
        assertMoney(10_200L, result.calculated.total?.value)
        assertMoney(0L, result.reconciliation?.difference)
    }

    @Test
    fun `two summary clusters on one page use only the lower cluster charges`() {
        val result = parse(
            page(
                blocks = summaryRows(
                    "SUBTOTAL" to "S/ 100.00",
                    "IGV" to "S/ 0.00",
                    "OTROS CARGOS" to "S/ 2.00",
                    "TOTAL A PAGAR" to "S/ 102.00",
                    startTop = 420,
                ) + summaryRows(
                    "SUBTOTAL" to "S/ 100.00",
                    "IGV" to "S/ 0.00",
                    "OTROS CARGOS" to "S/ 3.00",
                    "TOTAL A PAGAR" to "S/ 103.00",
                    startTop = 1_000,
                ),
            ),
        )

        assertEquals(1, result.read.otherCharges.size)
        assertMoney(300L, result.read.otherCharges.single().amount.candidate.value)
        assertMoney(10_300L, result.calculated.total?.value)
        assertNull(result.reconciliation)
        assertTrue(InvoiceTotalsWarning.MULTIPLE_PRINTED_VALUES in result.warnings)
    }

    @Test
    fun `final summary never borrows a missing component from a prior page`() {
        val first = page(
            pageIndex = 0,
            blocks = summaryRows(
                "IGV" to "S/ 18.00",
                "TOTAL A PAGAR" to "S/ 68.00",
            ),
        )
        val final = page(
            pageIndex = 1,
            blocks = summaryRows(
                "SUBTOTAL" to "S/ 50.00",
                "TOTAL A PAGAR" to "S/ 68.00",
            ),
        )

        val result = parse(first, final)

        assertNull(result.read.igv.selected)
        assertEquals(1, result.read.igv.alternatives.size)
        assertTrue(InvoiceTotalsWarning.PRIOR_SUMMARY_ALTERNATIVES in result.warnings)
        assertMoney(5_000L, result.read.subtotal)
        assertNull(result.calculated.total)
        assertNull(result.reconciliation)
        assertTrue(InvoiceTotalsWarning.MISSING_TAX_TREATMENT_EVIDENCE in result.warnings)
    }

    @Test
    fun `different totals in prior and final summaries remain explicit alternatives`() {
        val first = page(
            pageIndex = 0,
            blocks = summaryRows(
                "SUBTOTAL" to "S/ 100.00",
                "TOTAL A PAGAR" to "S/ 100.00",
            ),
        )
        val final = page(
            pageIndex = 1,
            blocks = summaryRows(
                "SUBTOTAL" to "S/ 200.00",
                "TOTAL A PAGAR" to "S/ 200.00",
            ),
        )

        val result = parse(first, final)

        assertMoney(20_000L, result.read.total.selected?.candidate?.value)
        assertMoney(10_000L, result.read.total.alternatives.single().candidate.value)
        assertTrue(InvoiceTotalsWarning.MULTIPLE_PRINTED_VALUES in result.warnings)
        assertNull(result.reconciliation)
        assertTrue(result.requiresReview)
    }

    @Test
    fun `lower complete summary wins over an earlier stronger total alias`() {
        val result = parse(
            page(
                blocks = listOf(
                    cell("TOTAL A PAGAR", 700, 500, 930, 536),
                    cell("S/ 50.00", 970, 500, 1_150, 536),
                ) + summaryRows(
                    "SUBTOTAL" to "S/ 100.00",
                    "IGV" to "S/ 18.00",
                    "TOTAL" to "S/ 118.00",
                    startTop = 1_200,
                ),
            ),
        )

        assertMoney(11_800L, result.read.total.selected?.candidate?.value)
        assertMoney(5_000L, result.read.total.alternatives.single().candidate.value)
        assertMoney(10_000L, result.read.subtotal)
        assertTrue(InvoiceTotalsWarning.MULTIPLE_PRINTED_VALUES in result.warnings)
    }

    @Test
    fun `strong total paired only with a value below remains reviewable table-like evidence`() {
        val result = parse(
            page(
                blocks = listOf(
                    cell("IMPORTE TOTAL", 700, 400, 930, 435),
                    cell("S/ 10.00", 700, 450, 930, 485),
                ),
            ),
        )

        val total = requireNotNull(result.read.total.selected)
        assertMoney(1_000L, total.candidate.value)
        assertEquals(InvoiceTotalReason.DIRECTLY_BELOW_LABEL, total.reasons.last())
        assertTrue(InvoiceTotalOccurrenceWarning.WEAK_SUMMARY_CONTEXT in total.warnings)
        assertTrue(total.requiresReview)
        assertNull(result.reconciliation)
    }

    @Test
    fun `layout 8 preserves noisy OCR while exposing corrected alternatives for review`() {
        val result = parse(
            page(
                blocks = summaryRows(
                    "0P. GRAVADA" to "S/ 1OO.OO",
                    "lGV 18%" to "S/ I8.OO",
                    "T0TAL" to "S/ 1I8.OO",
                ),
            ),
        )

        val taxable = requireNotNull(result.read.taxableOperations.selected)
        assertNull(taxable.candidate.value)
        assertMoney(10_000L, taxable.candidate.alternatives.single())
        assertEquals("0P. GRAVADA S/ 1OO.OO", taxable.rawText)
        assertEquals(listOf("0P. GRAVADA", "S/ 1OO.OO"), taxable.evidence.map(CandidateEvidence::rawText))
        assertTrue(InvoiceTotalOccurrenceWarning.OCR_LABEL_CORRECTION in taxable.warnings)
        assertTrue(CandidateWarning.OCR_CORRECTION_APPLIED in taxable.candidate.warnings)

        val igv = requireNotNull(result.read.igv.selected)
        assertNull(igv.candidate.value)
        assertMoney(1_800L, igv.candidate.alternatives.single())
        assertNull(result.read.printedIgvRate.selected?.candidate?.value)
        assertEquals(
            BigDecimal("18"),
            result.read.printedIgvRate.selected?.candidate?.alternatives?.single()?.percent,
        )

        val total = requireNotNull(result.read.total.selected)
        assertNull(total.candidate.value)
        assertMoney(11_800L, total.candidate.alternatives.single())
        assertEquals("T0TAL S/ 1I8.OO", total.rawText)
        assertTrue(InvoiceTotalsWarning.OCR_CORRECTION_APPLIED in result.warnings)
        assertTrue(result.requiresReview)
    }

    @Test
    fun `layout 9 excludes table totals and product text containing TOTAL`() {
        val result = parse(
            page(
                blocks = listOf(
                    wideLine(
                        element("DESCRIPCIÓN", rect(60, 280, 600, 316)),
                        element("CANT", rect(630, 280, 750, 316)),
                        element("P.UNIT", rect(780, 280, 930, 316)),
                        element("TOTAL", rect(960, 280, 1_150, 316)),
                    ),
                    wideLine(
                        element("CEREAL TOTAL 500G", rect(60, 340, 600, 376)),
                        element("2", rect(630, 340, 750, 376)),
                        element("S/ 5.00", rect(780, 340, 930, 376)),
                        element("S/ 10.00", rect(960, 340, 1_150, 376)),
                    ),
                    wideLine(
                        element("TOTAL CLEAN LIMPIADOR", rect(60, 400, 600, 436)),
                        element("1", rect(630, 400, 750, 436)),
                        element("S/ 7.00", rect(780, 400, 930, 436)),
                        element("S/ 7.00", rect(960, 400, 1_150, 436)),
                    ),
                    cell("QR|20131312955|F001|123", 80, 1_100, 620, 1_140),
                    cell("TOTAL A PAGAR", 700, 1_240, 930, 1_280),
                    cell("S/ 118.00", 970, 1_240, 1_150, 1_280),
                ),
            ),
        )

        val total = requireNotNull(result.read.total.selected)
        assertMoney(11_800L, total.candidate.value)
        assertEquals("TOTAL A PAGAR S/ 118.00", total.rawText)
        assertEquals(1, result.read.total.all.size)
        assertEquals(listOf("TOTAL A PAGAR", "S/ 118.00"), total.evidence.map(CandidateEvidence::rawText))
        assertFalse(
            total.evidence.any { evidence ->
                "CEREAL" in evidence.rawText || "LIMPIADOR" in evidence.rawText || '|' in evidence.rawText
            },
        )
    }

    @Test
    fun `layout 10 leaves unsupported rounding precision unresolved and rejects QR footer noise`() {
        val result = parse(
            page(
                blocks = summaryRows(
                    "SUBTOTAL" to "S/ 100.00",
                    "IGV 18%" to "S/ 18.00",
                    "REDONDEO" to "S/ 0.005",
                    "TOTAL A PAGAR" to "S/ 118.00",
                ) + listOf(
                    cell("QR|TOTAL|S/ 999.00|HASH", 80, 1_180, 650, 1_220),
                    cell("FIRMA DIGITAL TOTAL S/ 999.00", 80, 1_240, 650, 1_280),
                    cell("TOTAL PÁGINA S/ 999.00", 700, 1_300, 1_150, 1_340),
                ),
            ),
        )

        val rounding = requireNotNull(result.read.rounding.selected)
        assertNull(rounding.candidate.value)
        assertTrue(rounding.candidate.alternatives.isNotEmpty())
        assertTrue(CandidateWarning.UNSUPPORTED_FRACTION_PRECISION in rounding.candidate.warnings)
        assertTrue(CandidateWarning.AMBIGUOUS_NUMBER_SEPARATOR in rounding.candidate.warnings)
        assertTrue(CandidateWarning.MISSING_EXPLICIT_SIGN in rounding.candidate.warnings)
        assertTrue(InvoiceTotalOccurrenceWarning.MISSING_EXPLICIT_SIGN in rounding.warnings)
        assertTrue(rounding.requiresReview)

        assertMoney(11_800L, result.read.total)
        assertEquals(1, result.read.total.all.size)
        assertMoney(11_800L, result.calculated.totalBeforeRounding?.value)
        assertNull(result.calculated.total)
        assertNull(result.reconciliation)
        assertFalse(
            result.read.total.all.flatMap(InvoiceTotalOccurrence<Money>::evidence)
                .any { evidence -> "999.00" in evidence.rawText || '|' in evidence.rawText },
        )
    }

    private fun parse(vararg pages: InvoiceTextPage): InvoiceTotalsParseResult {
        val source = snapshot(*pages)
        return InvoiceTotalsParser().parse(
            snapshot = source,
            context = InvoiceTotalsParseContext(
                draftId = source.draftId,
                runId = source.runId,
                referenceIgvRate = AppConfiguration.DEFAULT_TAX_RATE,
                currency = PEN,
            ),
        )
    }

    private fun snapshot(vararg pages: InvoiceTextPage): InvoiceOcrSnapshot = InvoiceOcrSnapshot(
        draftId = DRAFT_ID,
        runId = RUN_ID,
        completedAt = COMPLETED_AT,
        document = InvoiceTextDocument(pages.toList()),
    )

    private fun summaryRows(
        vararg rows: Pair<String, String>,
        cornerOnly: Boolean = false,
        startTop: Int = 480,
    ): List<BlockSpec> = rows.flatMapIndexed { index, (label, amount) ->
        val top = startTop + index * 60
        listOf(
            cell(label, 620, top, 940, top + 36, cornerOnly),
            cell(amount, 970, top, 1_160, top + 36, cornerOnly),
        )
    }

    private fun inlineRow(
        label: String,
        amount: String,
        top: Int,
        widthPx: Int,
    ): BlockSpec = wideLine(
        element(label, rect(30, top, widthPx - 210, top + 38)),
        element(amount, rect(widthPx - 190, top, widthPx - 20, top + 38)),
    )

    private fun page(
        blocks: List<BlockSpec>,
        pageIndex: Int = 0,
        widthPx: Int = 1_200,
        heightPx: Int = 1_600,
    ): InvoiceTextPage {
        val mapped = blocks.mapIndexed { blockIndex, spec ->
            val blockBounds = spec.lines.mapNotNull(LineSpec::bounds).union()
            InvoiceTextBlock(
                position = blockIndex,
                text = spec.lines.joinToString("\n", transform = LineSpec::text),
                languageTag = "es-PE",
                geometry = blockBounds?.toGeometry(spec.lines.all(LineSpec::cornerOnly))
                    ?: InvoiceTextGeometry(null, emptyList()),
                lines = spec.lines.mapIndexed { lineIndex, line ->
                    InvoiceTextLine(
                        position = lineIndex,
                        text = line.text,
                        languageTag = "es-PE",
                        geometry = line.bounds?.toGeometry(line.cornerOnly)
                            ?: InvoiceTextGeometry(null, emptyList()),
                        confidencePermille = line.confidencePermille,
                        clockwiseAngleTenths = 0,
                        elements = line.toElements(),
                    )
                },
            )
        }
        return InvoiceTextPage(
            sourceImageId = ImageId.from(UUID(0L, 700L + pageIndex)),
            pageIndex = pageIndex,
            widthPx = widthPx,
            heightPx = heightPx,
            text = mapped.joinToString("\n", transform = InvoiceTextBlock::text),
            blocks = mapped,
        )
    }

    private fun LineSpec.toElements(): List<InvoiceTextElement> {
        val elementSpecs = when {
            elements.isNotEmpty() -> elements
            bounds != null -> listOf(ElementSpec(text, bounds, cornerOnly))
            else -> emptyList()
        }
        return elementSpecs.mapIndexed { elementIndex, element ->
            InvoiceTextElement(
                position = elementIndex,
                text = element.text,
                languageTag = "es-PE",
                geometry = element.bounds.toGeometry(element.cornerOnly),
                confidencePermille = confidencePermille,
                clockwiseAngleTenths = 0,
            )
        }
    }

    private fun cell(
        text: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        cornerOnly: Boolean = false,
    ): BlockSpec = block(
        line(
            text = text,
            bounds = rect(left, top, right, bottom),
            cornerOnly = cornerOnly,
        ),
    )

    private fun wideLine(vararg elements: ElementSpec): BlockSpec {
        val bounds = requireNotNull(elements.map(ElementSpec::bounds).union())
        return block(
            line(
                text = elements.joinToString(" ", transform = ElementSpec::text),
                bounds = bounds,
                elements = elements.toList(),
                cornerOnly = elements.all(ElementSpec::cornerOnly),
            ),
        )
    }

    private fun block(vararg lines: LineSpec): BlockSpec = BlockSpec(lines.toList())

    private fun line(
        text: String,
        bounds: RectSpec?,
        elements: List<ElementSpec> = emptyList(),
        cornerOnly: Boolean = false,
        confidencePermille: Int = 930,
    ): LineSpec = LineSpec(text, bounds, elements, cornerOnly, confidencePermille)

    private fun element(
        text: String,
        bounds: RectSpec,
        cornerOnly: Boolean = false,
    ): ElementSpec = ElementSpec(text, bounds, cornerOnly)

    private fun rect(left: Int, top: Int, right: Int, bottom: Int): RectSpec =
        RectSpec(left, top, right, bottom)

    private fun List<RectSpec>.union(): RectSpec? = if (isEmpty()) {
        null
    } else {
        RectSpec(
            left = minOf(RectSpec::left),
            top = minOf(RectSpec::top),
            right = maxOf(RectSpec::right),
            bottom = maxOf(RectSpec::bottom),
        )
    }

    private fun RectSpec.toGeometry(cornerOnly: Boolean): InvoiceTextGeometry =
        InvoiceTextGeometry(
            boundingBox = if (cornerOnly) null else InvoiceTextBoundingBox(left, top, right, bottom),
            cornerPoints = if (cornerOnly) {
                listOf(
                    InvoiceTextPoint(left, top),
                    InvoiceTextPoint(right - 1, top),
                    InvoiceTextPoint(right - 1, bottom - 1),
                    InvoiceTextPoint(left, bottom - 1),
                )
            } else {
                emptyList()
            },
        )

    private fun assertMoney(expectedMinorUnits: Long, field: InvoiceTotalField<Money>) {
        assertMoney(expectedMinorUnits, field.selected?.candidate?.value)
    }

    private fun assertMoney(expectedMinorUnits: Long, actual: Money?) {
        assertEquals(expectedMinorUnits, actual?.minorUnits)
        assertEquals(PEN, actual?.currency)
    }

    private data class BlockSpec(val lines: List<LineSpec>)

    private data class LineSpec(
        val text: String,
        val bounds: RectSpec?,
        val elements: List<ElementSpec>,
        val cornerOnly: Boolean,
        val confidencePermille: Int,
    )

    private data class ElementSpec(
        val text: String,
        val bounds: RectSpec,
        val cornerOnly: Boolean,
    )

    private data class RectSpec(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    private companion object {
        val DRAFT_ID: DraftId = DraftId.from(UUID(0L, 601L))
        val RUN_ID: OcrRunId = OcrRunId.from(UUID(0L, 602L))
        val COMPLETED_AT: Instant = Instant.parse("2026-08-09T20:00:00Z")
        val PEN: CurrencyCode = CurrencyCode.of("PEN")
    }
}
