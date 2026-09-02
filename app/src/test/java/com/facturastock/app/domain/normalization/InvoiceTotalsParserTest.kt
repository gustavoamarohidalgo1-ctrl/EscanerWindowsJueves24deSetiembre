package com.facturastock.app.domain.normalization

import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.model.InvoiceOcrSnapshot
import com.facturastock.app.domain.model.InvoiceTextBlock
import com.facturastock.app.domain.model.InvoiceTextBoundingBox
import com.facturastock.app.domain.model.InvoiceTextDocument
import com.facturastock.app.domain.model.InvoiceTextGeometry
import com.facturastock.app.domain.model.InvoiceTextLine
import com.facturastock.app.domain.model.InvoiceTextPage
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.model.id.OcrRunId
import com.facturastock.app.domain.config.TaxRate
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InvoiceTotalsParserTest {
    private val pen = CurrencyCode.of("PEN")

    @Test
    fun `reconciliation exposes an exact three cent difference without changing the read total`() {
        val result = parse(
            row("OP. GRAVADA", "S/ 100.00", 800),
            row("OP. EXONERADA", "S/ 0.00", 850),
            row("OP. INAFECTA", "S/ 0.00", 900),
            row("IGV", "S/ 18.00", 950),
            row("TOTAL A PAGAR", "S/ 118.03", 1_000),
        )

        assertMoney(11_803L, result.read.total.selected?.candidate?.value)
        assertMoney(11_800L, result.calculated.total?.value)
        assertMoney(3L, result.reconciliation?.difference)
        assertTrue(InvoiceTotalsWarning.PRINTED_TOTAL_DIFFERS_FROM_CALCULATED in result.warnings)
    }

    @Test
    fun `document without an IGV label never receives automatic tax`() {
        val result = parse(
            row("OP. GRAVADA", "S/ 100.00", 800),
            row("OP. EXONERADA", "S/ 0.00", 850),
            row("OP. INAFECTA", "S/ 0.00", 900),
            row("TOTAL", "S/ 100.00", 1_000),
        )

        assertNull(result.read.igv.selected)
        assertNull(result.calculated.igv)
        assertNull(result.calculated.appliedIgvRate)
        assertNull(result.calculated.total)
        assertNull(result.reconciliation)
        assertTrue(InvoiceTotalsWarning.MISSING_IGV_FOR_TAXABLE_OPERATIONS in result.warnings)
    }

    @Test
    fun `exempt-only document reconciles without inventing IGV`() {
        val result = parse(
            row("OP. GRAVADA", "S/ 0.00", 800),
            row("OP. EXONERADA", "S/ 100.00", 850),
            row("OP. INAFECTA", "S/ 0.00", 900),
            row("TOTAL A PAGAR", "S/ 100.00", 1_000),
        )

        assertMoney(10_000L, result.read.exemptOperations.selected?.candidate?.value)
        assertNull(result.read.igv.selected)
        assertNull(result.calculated.igv)
        assertNull(result.calculated.appliedIgvRate)
        assertMoney(10_000L, result.calculated.total?.value)
        assertMoney(0L, result.reconciliation?.difference)
    }

    @Test
    fun `printed rounding retains sign reason and before-after differences`() {
        val result = parse(
            row("OP. GRAVADA", "S/ 100.00", 800),
            row("OP. EXONERADA", "S/ 0.00", 850),
            row("OP. INAFECTA", "S/ 0.00", 900),
            row("IGV 18%", "S/ 18.00", 950),
            row("REDONDEO", "- S/ 0.03", 1_000),
            row("TOTAL A PAGAR", "S/ 117.97", 1_050),
        )

        val rounding = result.read.rounding.selected?.candidate?.value
        assertMoney(-3L, rounding?.signedAmount)
        assertEquals(InvoiceRoundingDirection.DECREASE, rounding?.direction)
        assertEquals(InvoiceRoundingReason.PRINTED_ROUNDING, rounding?.reason)
        assertMoney(-3L, result.reconciliation?.differenceBeforeRounding)
        assertMoney(0L, result.reconciliation?.difference)
    }

    @Test
    fun `missing fields stay empty while an explicitly printed zero remains present`() {
        val result = parse(
            row("OP. GRAVADA", "S/ 20.00", 850),
            row("OP. EXONERADA", "S/ 0.00", 900),
            row("TOTAL", "S/ 20.00", 1_000),
        )

        assertMoney(0L, result.read.exemptOperations.selected?.candidate?.value)
        assertNull(result.read.unaffectedOperations.selected)
        assertNull(result.read.discounts.selected)
        assertNull(result.read.subtotal.selected)
        assertNull(result.read.igv.selected)
        assertNull(result.calculated.subtotal)
        assertNull(result.reconciliation)
    }

    @Test
    fun `bare amounts do not consume confusable letters from their labels`() {
        val result = parse(
            row("OP. GRAVADA", "100.00", 800),
            row("OP. EXONERADA", "0.00", 850),
            row("OP. INAFECTA", "0.00", 900),
            row("IGV", "18.00", 950),
            row("TOTAL", "118.00", 1_000),
        )

        assertMoney(10_000L, result.read.taxableOperations.selected?.candidate?.value)
        assertMoney(1_800L, result.read.igv.selected?.candidate?.value)
        assertMoney(11_800L, result.read.total.selected?.candidate?.value)
    }

    @Test
    fun `a TOTAL table header above its first numeric cell is never a financial total`() {
        val result = parseCells(
            cells = listOf(
                Cell("DESCRIPCIÓN", InvoiceTextBoundingBox(100, 220, 600, 255)),
                Cell("TOTAL", InvoiceTextBoundingBox(900, 220, 1_150, 255)),
                Cell("PRODUCTO", InvoiceTextBoundingBox(100, 270, 600, 305)),
                Cell("10.00", InvoiceTextBoundingBox(900, 270, 1_150, 305)),
            ),
            currencyContext = pen,
        )

        assertTrue(result.read.total.all.isEmpty())
        assertNull(result.reconciliation)
    }

    @Test
    fun `invalid printed tax rate is retained unresolved and never replaced by reference rate`() {
        val result = parse(
            row("OP. GRAVADA", "S/ 100.00", 800),
            row("OP. EXONERADA", "S/ 0.00", 850),
            row("OP. INAFECTA", "S/ 0.00", 900),
            row("IGV 180%", "S/ 18.00", 950),
            row("TOTAL", "S/ 118.00", 1_000),
        )

        val rate = requireNotNull(result.read.printedIgvRate.selected?.candidate)
        assertNull(rate.value)
        assertTrue(CandidateWarning.UNSUPPORTED_NUMERIC_VALUE in rate.warnings)
        assertNull(result.calculated.appliedIgvRate)
        assertTrue(result.requiresReview)
    }

    @Test
    fun `two printed tax rates remain ambiguous instead of selecting one`() {
        val result = parse(
            row("OP. GRAVADA", "S/ 100.00", 800),
            row("IGV 18% 10%", "S/ 18.00", 850),
            row("TOTAL A PAGAR", "S/ 118.00", 900),
        )

        val rate = requireNotNull(result.read.printedIgvRate.selected?.candidate)
        assertNull(rate.value)
        assertEquals(2, rate.alternatives.size)
        assertTrue(CandidateWarning.MULTIPLE_VALUES_FOUND in rate.warnings)
        assertNull(result.calculated.appliedIgvRate)
    }

    @Test
    fun `signed or partially matched printed rates stay unresolved and block the reference rate`() {
        listOf("-18%", "−18%", ".5%").forEach { printedRate ->
            val result = parse(
                row("OP. GRAVADA", "S/ 100.00", 800),
                row("IGV $printedRate", "S/ 18.00", 850),
                row("TOTAL A PAGAR", "S/ 118.00", 900),
            )

            val rate = requireNotNull(result.read.printedIgvRate.selected?.candidate)
            assertNull(rate.value)
            assertTrue(CandidateWarning.UNSUPPORTED_NUMERIC_VALUE in rate.warnings)
            assertNull(result.calculated.appliedIgvRate)
            assertTrue(result.requiresReview)
        }
    }

    @Test
    fun `printed rate candidate points to its original label atom`() {
        val result = parse(
            row("OP. GRAVADA", "S/ 100.00", 800),
            row("IGV 18%", "S/ 18.00", 850),
            row("TOTAL A PAGAR", "S/ 118.00", 900),
        )

        assertEquals("IGV 18%", result.read.printedIgvRate.selected?.candidate?.evidence?.rawText)
    }

    @Test
    fun `rates split across OCR atoms remain multiple alternatives`() {
        val result = parseCells(
            cells = listOf(
                Cell("IGV 18%", InvoiceTextBoundingBox(650, 900, 790, 935)),
                Cell("1O%", InvoiceTextBoundingBox(810, 900, 900, 935)),
                Cell("S/ 18.00", InvoiceTextBoundingBox(960, 900, 1_160, 935)),
                Cell("TOTAL A PAGAR", InvoiceTextBoundingBox(650, 950, 930, 985)),
                Cell("S/ 118.00", InvoiceTextBoundingBox(960, 950, 1_160, 985)),
            ),
            currencyContext = pen,
        )

        val rate = requireNotNull(result.read.printedIgvRate.selected?.candidate)
        assertNull(rate.value)
        assertEquals(setOf("18", "10"), rate.alternatives.map { it.percent.toPlainString() }.toSet())
        assertTrue(CandidateWarning.MULTIPLE_VALUES_FOUND in rate.warnings)
        val rateOccurrence = requireNotNull(result.read.printedIgvRate.selected)
        val correctedEvidence = rateOccurrence.evidence.single { evidence -> evidence.rawText == "1O%" }
        assertTrue(
            correctedEvidence.corrections.any { correction ->
                correction.originalFragment == "1O" && correction.correctedFragment == "10"
            },
        )
        assertNull(result.calculated.appliedIgvRate)
    }

    @Test
    fun `a valid printed rate cannot hide another malformed rate`() {
        val result = parse(
            row("OP. GRAVADA", "S/ 100.00", 800),
            row("IGV 18% -10%", "S/ 18.00", 850),
            row("TOTAL A PAGAR", "S/ 118.00", 900),
        )

        val rate = requireNotNull(result.read.printedIgvRate.selected?.candidate)
        assertNull(rate.value)
        assertTrue(rate.alternatives.isEmpty())
        assertTrue(CandidateWarning.UNSUPPORTED_NUMERIC_VALUE in rate.warnings)
        assertNull(result.calculated.appliedIgvRate)
    }

    @Test
    fun `a sign after a printed rate makes the rate unsupported`() {
        listOf("18%-", "18%+", "18%−", "18% -", "- 18%").forEach { printedRate ->
            val result = parse(
                row("OP. GRAVADA", "S/ 100.00", 800),
                row("IGV $printedRate", "S/ 18.00", 850),
                row("TOTAL A PAGAR", "S/ 118.00", 900),
            )

            val rate = requireNotNull(result.read.printedIgvRate.selected?.candidate)
            assertNull(rate.value)
            assertTrue(CandidateWarning.UNSUPPORTED_NUMERIC_VALUE in rate.warnings)
            assertNull(result.calculated.appliedIgvRate)
        }
    }

    @Test
    fun `a malformed rate sign never migrates to an amount in another OCR atom`() {
        val result = parse(
            row("OP. GRAVADA", "S/ 100.00", 800),
            row("IGV 18%-", "S/ 18.00", 850),
            row("TOTAL A PAGAR", "S/ 118.00", 900),
        )

        val printedIgv = requireNotNull(result.read.igv.selected)
        assertMoney(1_800L, printedIgv.candidate.value)
        assertEquals(InvoicePrintedSign.NONE, printedIgv.printedSign)
        assertNull(result.read.printedIgvRate.selected?.candidate?.value)
        assertNull(result.calculated.appliedIgvRate)
    }

    @Test
    fun `a dedicated OCR sign atom remains attached to rounding`() {
        val result = parseCells(
            cells = listOf(
                Cell("SUBTOTAL", InvoiceTextBoundingBox(650, 800, 900, 834)),
                Cell("S/ 100.00", InvoiceTextBoundingBox(960, 800, 1_160, 834)),
                Cell("IGV", InvoiceTextBoundingBox(650, 850, 900, 884)),
                Cell("S/ 0.00", InvoiceTextBoundingBox(960, 850, 1_160, 884)),
                Cell("REDONDEO", InvoiceTextBoundingBox(650, 900, 870, 934)),
                Cell("-", InvoiceTextBoundingBox(900, 900, 920, 934)),
                Cell("S/ 0.01", InvoiceTextBoundingBox(960, 900, 1_160, 934)),
                Cell("TOTAL A PAGAR", InvoiceTextBoundingBox(650, 950, 930, 984)),
                Cell("S/ 99.99", InvoiceTextBoundingBox(960, 950, 1_160, 984)),
            ),
            currencyContext = pen,
        )

        val rounding = requireNotNull(result.read.rounding.selected?.candidate?.value)
        assertMoney(-1L, rounding.signedAmount)
        assertEquals(InvoiceRoundingDirection.DECREASE, rounding.direction)
        assertMoney(0L, result.reconciliation?.difference)
    }

    @Test
    fun `parenthesized printed rate is label metadata rather than a negative amount`() {
        val result = parse(
            row("OP. GRAVADA", "S/ 100.00", 750),
            row("OP. EXONERADA", "S/ 0.00", 800),
            row("OP. INAFECTA", "S/ 0.00", 850),
            row("IGV (18%)", "S/ 18.00", 900),
            row("TOTAL A PAGAR", "S/ 118.00", 950),
        )

        assertEquals(
            "18",
            result.read.printedIgvRate.selected?.candidate?.value?.percent?.toPlainString(),
        )
        assertMoney(1_800L, result.read.igv.selected?.candidate?.value)
        assertMoney(0L, result.reconciliation?.difference)
    }

    @Test
    fun `equivalent reference rate scale does not prevent exact tax calculation`() {
        val source = snapshot(
            listOf(
                row("OP. GRAVADA", "S/ 100.00", 800),
                row("OP. EXONERADA", "S/ 0.00", 850),
                row("OP. INAFECTA", "S/ 0.00", 900),
                row("IGV", "S/ 18.00", 950),
                row("TOTAL A PAGAR", "S/ 118.00", 1_000),
            ).flatten(),
        )
        val result = InvoiceTotalsParser().parse(
            snapshot = source,
            context = source.context(
                referenceIgvRate = TaxRate(BigDecimal("18.0000000000000000000")),
                currency = pen,
            ),
        )

        assertMoney(1_800L, result.calculated.igv?.value)
        assertFalse(InvoiceTotalsWarning.CALCULATION_OVERFLOW in result.warnings)
    }

    @Test
    fun `configured reference rate is applied only when an IGV label supports it`() {
        val supportedSource = snapshot(
            listOf(
                row("OP. GRAVADA", "S/ 100.00", 800),
                row("OP. EXONERADA", "S/ 0.00", 850),
                row("OP. INAFECTA", "S/ 0.00", 900),
                row("IGV", "S/ 10.00", 950),
                row("TOTAL A PAGAR", "S/ 110.00", 1_000),
            ).flatten(),
        )
        val unsupportedSource = snapshot(
            listOf(
                row("OP. GRAVADA", "S/ 100.00", 800),
                row("OP. EXONERADA", "S/ 0.00", 850),
                row("OP. INAFECTA", "S/ 0.00", 900),
                row("TOTAL A PAGAR", "S/ 100.00", 1_000),
            ).flatten(),
        )
        val configuredRate = TaxRate(BigDecimal("10"))

        val supported = InvoiceTotalsParser().parse(
            snapshot = supportedSource,
            context = supportedSource.context(referenceIgvRate = configuredRate, currency = pen),
        )
        val unsupported = InvoiceTotalsParser().parse(
            snapshot = unsupportedSource,
            context = unsupportedSource.context(referenceIgvRate = configuredRate, currency = pen),
        )

        assertMoney(1_000L, supported.calculated.igv?.value)
        assertEquals(configuredRate, supported.calculated.appliedIgvRate?.rate)
        assertEquals(
            InvoiceTaxRateSource.CONFIGURED_REFERENCE_SUPPORTED_BY_IGV_LABEL,
            supported.calculated.appliedIgvRate?.source,
        )
        assertEquals(
            InvoiceTotalsCalculationReason.TAXABLE_OPERATIONS_TIMES_SUPPORTED_REFERENCE_RATE,
            supported.calculated.igv?.reason,
        )
        assertMoney(11_000L, supported.calculated.total?.value)
        assertMoney(0L, supported.reconciliation?.difference)
        assertNull(unsupported.calculated.igv)
        assertNull(unsupported.calculated.appliedIgvRate)
        assertTrue(InvoiceTotalsWarning.MISSING_IGV_FOR_TAXABLE_OPERATIONS in unsupported.warnings)
    }

    @Test
    fun `printed tax rate stays distinct and takes precedence over a different reference rate`() {
        val source = snapshot(
            listOf(
                row("OP. GRAVADA", "S/ 100.00", 800),
                row("OP. EXONERADA", "S/ 0.00", 850),
                row("OP. INAFECTA", "S/ 0.00", 900),
                row("IGV 10%", "S/ 10.00", 950),
                row("TOTAL A PAGAR", "S/ 110.00", 1_000),
            ).flatten(),
        )
        val referenceRate = TaxRate(BigDecimal("18"))

        val result = InvoiceTotalsParser().parse(
            snapshot = source,
            context = source.context(referenceIgvRate = referenceRate, currency = pen),
        )

        assertEquals(referenceRate, result.referenceIgvRate)
        assertEquals(
            BigDecimal("10"),
            result.read.printedIgvRate.selected?.candidate?.value?.percent,
        )
        assertMoney(1_000L, result.calculated.igv?.value)
        assertEquals(TaxRate(BigDecimal("10")), result.calculated.appliedIgvRate?.rate)
        assertEquals(
            InvoiceTaxRateSource.PRINTED_ON_DOCUMENT,
            result.calculated.appliedIgvRate?.source,
        )
        assertEquals(
            InvoiceTotalsCalculationReason.TAXABLE_OPERATIONS_TIMES_PRINTED_RATE,
            result.calculated.igv?.reason,
        )
        assertMoney(11_000L, result.calculated.total?.value)
        assertMoney(0L, result.reconciliation?.difference)
        assertTrue(InvoiceTotalsWarning.REFERENCE_TAX_RATE_DIFFERS_FROM_PRINTED in result.warnings)
    }

    @Test
    fun `recognized bare summary without currency reports missing context instead of looking empty`() {
        val result = parseCells(
            cells = listOf(Cell("TOTAL A PAGAR 118.00", InvoiceTextBoundingBox(700, 1_000, 1_160, 1_040))),
            currencyContext = null,
        )

        assertTrue(result.read.total.all.isEmpty())
        assertTrue(InvoiceTotalsWarning.UNRESOLVED_PRINTED_FIELD in result.warnings)
        assertTrue(InvoiceTotalsWarning.MISSING_CURRENCY_FOR_PRINTED_AMOUNTS in result.warnings)
        assertTrue(result.requiresReview)
    }

    @Test
    fun `a leading decimal separator is never consumed as a different amount`() {
        val result = parseCells(
            cells = listOf(
                Cell("TOTAL A PAGAR .5", InvoiceTextBoundingBox(700, 1_000, 1_160, 1_040)),
            ),
            currencyContext = pen,
        )

        assertTrue(result.read.total.all.isEmpty())
        assertTrue(InvoiceTotalsWarning.UNRESOLVED_PRINTED_FIELD in result.warnings)
        assertTrue(result.requiresReview)
    }

    @Test
    fun `compact figure-dash rounding and currency-outside parentheses retain negative sign`() {
        val figureDash = parseCells(
            cells = listOf(
                Cell("SUBTOTAL S/ 100.00", InvoiceTextBoundingBox(700, 850, 1_160, 885)),
                Cell("REDONDEO‒S/0.01", InvoiceTextBoundingBox(700, 900, 1_160, 935)),
                Cell("TOTAL A PAGAR S/99.99", InvoiceTextBoundingBox(700, 950, 1_160, 985)),
            ),
            currencyContext = pen,
        )
        val parentheses = parseCells(
            cells = listOf(
                Cell("SUBTOTAL S/ 100.00", InvoiceTextBoundingBox(700, 850, 1_160, 885)),
                Cell("REDONDEO S/ (0.01)", InvoiceTextBoundingBox(700, 900, 1_160, 935)),
                Cell("TOTAL A PAGAR S/99.99", InvoiceTextBoundingBox(700, 950, 1_160, 985)),
            ),
            currencyContext = pen,
        )

        assertMoney(-1L, figureDash.read.rounding.selected?.candidate?.value?.signedAmount)
        assertEquals(InvoicePrintedSign.NEGATIVE, figureDash.read.rounding.selected?.printedSign)
        assertMoney(-1L, parentheses.read.rounding.selected?.candidate?.value?.signedAmount)
        assertEquals(InvoicePrintedSign.PARENTHESES_NEGATIVE, parentheses.read.rounding.selected?.printedSign)
    }

    @Test
    fun `compact currency markers parse without changing their OCR evidence`() {
        listOf(
            "S/100.00" to "PEN",
            "PEN100.00" to "PEN",
            "100.00PEN" to "PEN",
            "US$100.00" to "USD",
            "USD100.00" to "USD",
            "100.00USD" to "USD",
        ).forEach { (printed, expectedCurrency) ->
            val result = parseCells(
                cells = listOf(
                    Cell("TOTAL A PAGAR $printed", InvoiceTextBoundingBox(700, 1_000, 1_160, 1_040)),
                ),
                currencyContext = null,
            )
            val total = requireNotNull(result.read.total.selected?.candidate?.value)
            assertEquals(10_000L, total.minorUnits)
            assertEquals(expectedCurrency, total.currency.value)
            val selected = requireNotNull(result.read.total.selected)
            assertEquals(
                "TOTAL A PAGAR $printed",
                selected.candidate.evidence.rawText,
            )
        }
    }

    @Test
    fun `ISO currency is generic and repeated markers never resolve silently`() {
        val eur = parseCells(
            cells = listOf(
                Cell("TOTAL A PAGAR EUR100.00", InvoiceTextBoundingBox(700, 1_000, 1_160, 1_040)),
            ),
            currencyContext = null,
        )
        val repeated = parseCells(
            cells = listOf(
                Cell("TOTAL A PAGAR PEN100PEN", InvoiceTextBoundingBox(700, 1_000, 1_160, 1_040)),
            ),
            currencyContext = null,
        )

        assertEquals(10_000L, eur.read.total.selected?.candidate?.value?.minorUnits)
        assertEquals("EUR", eur.read.total.selected?.candidate?.value?.currency?.value)
        val repeatedCandidate = requireNotNull(repeated.read.total.selected?.candidate)
        assertNull(repeatedCandidate.value)
        assertMoney(10_000L, repeatedCandidate.alternatives.single())
        assertTrue(CandidateWarning.MULTIPLE_VALUES_FOUND in repeatedCandidate.warnings)
        assertTrue(repeated.requiresReview)
    }

    @Test
    fun `numeric OCR confusables are not mistaken for ISO currencies`() {
        listOf(
            "S/ I,OOO.OO" to 100_000L,
            "S/ OOO.OO" to 0L,
        ).forEach { (printed, expectedMinorUnits) ->
            val result = parseCells(
                cells = listOf(
                    Cell("TOTAL A PAGAR $printed", InvoiceTextBoundingBox(700, 1_000, 1_160, 1_040)),
                ),
                currencyContext = null,
            )
            val candidate = requireNotNull(result.read.total.selected?.candidate)
            assertNull(candidate.value)
            assertMoney(expectedMinorUnits, candidate.alternatives.single())
            assertTrue(CandidateWarning.OCR_CORRECTION_APPLIED in candidate.warnings)
            assertEquals("TOTAL A PAGAR $printed", candidate.evidence.rawText)
        }
    }

    @Test
    fun `invalid ISO-like currency and duplicated signs remain unresolved signals`() {
        listOf(
            "TOTAL A PAGAR ABC 100.00",
            "REDONDEO S/--0.01",
            "IGV 18%% S/18.00",
        ).forEachIndexed { index, raw ->
            val result = parseCells(
                cells = listOf(
                    Cell(raw, InvoiceTextBoundingBox(700, 900, 1_160, 935)),
                    Cell("TOTAL A PAGAR S/100.00", InvoiceTextBoundingBox(700, 950, 1_160, 985)),
                ),
                currencyContext = pen,
            )

            assertTrue("case $index", InvoiceTotalsWarning.UNRESOLVED_PRINTED_FIELD in result.warnings)
            assertTrue("case $index", result.requiresReview)
            if (raw.startsWith("REDONDEO")) assertNull(result.calculated.total)
            if (raw.startsWith("IGV")) assertNull(result.calculated.appliedIgvRate)
        }
    }

    @Test
    fun `a sign printed after the currency marker remains attached to rounding`() {
        val result = parse(
            row("IGV", "S/0.00", 800),
            row("SUBTOTAL", "S/100.00", 850),
            row("REDONDEO", "S/-0.01", 900),
            row("TOTAL A PAGAR", "S/99.99", 950),
        )

        assertMoney(-1L, result.read.rounding.selected?.candidate?.value?.signedAmount)
        assertEquals(InvoicePrintedSign.NEGATIVE, result.read.rounding.selected?.printedSign)
        assertMoney(0L, result.reconciliation?.difference)
    }

    @Test
    fun `subtotal and total alone do not imply that missing tax was zero`() {
        val result = parse(
            row("SUBTOTAL", "S/ 100.00", 900),
            row("TOTAL A PAGAR", "S/ 100.00", 950),
        )

        assertNull(result.read.igv.selected)
        assertNull(result.calculated.total)
        assertNull(result.reconciliation)
        assertTrue(InvoiceTotalsWarning.MISSING_TAX_TREATMENT_EVIDENCE in result.warnings)
        assertTrue(result.requiresReview)
    }

    @Test
    fun `negative other charge is preserved for review and never subtracted as a charge`() {
        val result = parse(
            row("SUBTOTAL", "S/ 100.00", 850),
            row("OTROS CARGOS", "- S/ 10.00", 900),
            row("TOTAL A PAGAR", "S/ 90.00", 950),
        )

        val charge = result.read.otherCharges.single().amount
        assertMoney(-1_000L, charge.candidate.value)
        assertTrue(InvoiceTotalOccurrenceWarning.NEGATIVE_CHARGE_REQUIRES_REVIEW in charge.warnings)
        assertTrue(charge.requiresReview)
        assertNull(result.calculated.total)
        assertNull(result.reconciliation)
    }

    @Test
    fun `recognized component with malformed amount blocks calculations instead of becoming absent`() {
        val result = parseCells(
            cells = listOf(
                Cell("SUBTOTAL S/100.00", InvoiceTextBoundingBox(700, 850, 1_160, 885)),
                Cell("OTROS CARGOS S/ XX", InvoiceTextBoundingBox(700, 900, 1_160, 935)),
                Cell("TOTAL A PAGAR S/100.00", InvoiceTextBoundingBox(700, 950, 1_160, 985)),
            ),
            currencyContext = pen,
        )

        assertTrue(result.read.otherCharges.isEmpty())
        assertTrue(InvoiceTotalsWarning.UNRESOLVED_PRINTED_FIELD in result.warnings)
        assertNull(result.calculated.total)
        assertNull(result.reconciliation)
        assertTrue(result.requiresReview)
    }

    @Test
    fun `malformed printed IGV blocks final reconciliation while reference remains diagnostic`() {
        val result = parseCells(
            cells = listOf(
                Cell("OP. GRAVADA S/100.00", InvoiceTextBoundingBox(700, 750, 1_160, 785)),
                Cell("OP. EXONERADA S/0.00", InvoiceTextBoundingBox(700, 800, 1_160, 835)),
                Cell("OP. INAFECTA S/0.00", InvoiceTextBoundingBox(700, 850, 1_160, 885)),
                Cell("IGV S/XX", InvoiceTextBoundingBox(700, 900, 1_160, 935)),
                Cell("TOTAL A PAGAR S/118.00", InvoiceTextBoundingBox(700, 950, 1_160, 985)),
            ),
            currencyContext = pen,
        )

        assertTrue(result.read.igv.all.isEmpty())
        assertTrue(InvoiceTotalsWarning.UNRESOLVED_PRINTED_FIELD in result.warnings)
        assertMoney(1_800L, result.calculated.igv?.value)
        assertEquals(
            InvoiceTaxRateSource.CONFIGURED_REFERENCE_SUPPORTED_BY_IGV_LABEL,
            result.calculated.appliedIgvRate?.source,
        )
        assertNull(result.calculated.totalBeforeRounding)
        assertNull(result.calculated.total)
        assertNull(result.reconciliation)
        assertTrue(result.requiresReview)
    }

    @Test
    fun `ambiguous printed IGV remains visible and blocks final reconciliation`() {
        val result = parse(
            row("OP. GRAVADA", "S/ 100.00", 750),
            row("OP. EXONERADA", "S/ 0.00", 800),
            row("OP. INAFECTA", "S/ 0.00", 850),
            row("IGV", "S/ 1,800", 900),
            row("TOTAL A PAGAR", "S/ 118.00", 950),
        )

        val printedIgv = requireNotNull(result.read.igv.selected)
        assertTrue(printedIgv.requiresReview)
        assertNull(printedIgv.candidate.value)
        assertTrue(printedIgv.candidate.alternatives.isNotEmpty())
        assertTrue(CandidateWarning.AMBIGUOUS_NUMBER_SEPARATOR in printedIgv.candidate.warnings)
        assertMoney(1_800L, result.calculated.igv?.value)
        assertNull(result.calculated.totalBeforeRounding)
        assertNull(result.calculated.total)
        assertNull(result.reconciliation)
        assertTrue(result.requiresReview)
    }

    @Test
    fun `isolated simple TOTAL is accepted only in the final page region`() {
        val lower = parseCells(
            cells = listOf(Cell("TOTAL S/100.00", InvoiceTextBoundingBox(700, 1_400, 1_160, 1_440))),
            currencyContext = pen,
        )
        val middle = parseCells(
            cells = listOf(Cell("TOTAL S/100.00", InvoiceTextBoundingBox(700, 700, 1_160, 740))),
            currencyContext = pen,
        )

        assertMoney(10_000L, lower.read.total.selected?.candidate?.value)
        assertTrue(lower.read.total.selected?.requiresReview == true)
        assertTrue(
            InvoiceTotalOccurrenceWarning.WEAK_SUMMARY_CONTEXT in
                lower.read.total.selected?.warnings.orEmpty(),
        )
        assertTrue(middle.read.total.all.isEmpty())
    }

    @Test
    fun `IGV and TOTAL product-like rows in the middle do not form a summary`() {
        val result = parse(
            row("IGV", "S/ 20.00", 800),
            row("TOTAL", "S/ 20.00", 850),
        )

        assertTrue(result.read.igv.all.isEmpty())
        assertTrue(result.read.total.all.isEmpty())
        assertNull(result.reconciliation)
    }

    @Test
    fun `candidate evidence preserves fullwidth OCR exactly`() {
        val result = parseCells(
            cells = listOf(
                Cell("SUBTOTAL", InvoiceTextBoundingBox(700, 900, 930, 935)),
                Cell("S/ １２３．４５", InvoiceTextBoundingBox(960, 900, 1_160, 935)),
                Cell("TOTAL A PAGAR", InvoiceTextBoundingBox(700, 950, 930, 985)),
                Cell("S/ １２３．４５", InvoiceTextBoundingBox(960, 950, 1_160, 985)),
            ),
            currencyContext = pen,
        )

        assertEquals("S/ １２３．４５", result.read.total.selected?.candidate?.evidence?.rawText)
        assertEquals("S/ １２３．４５", result.read.total.selected?.evidence?.last()?.rawText)
    }

    @Test
    fun `subtotal and IGV discrepancies remain visible even when total is absent`() {
        val result = parse(
            row("OP. GRAVADA", "S/ 100.00", 800),
            row("OP. EXONERADA", "S/ 0.00", 850),
            row("OP. INAFECTA", "S/ 0.00", 900),
            row("SUBTOTAL", "S/ 90.00", 950),
            row("IGV 18%", "S/ 17.00", 1_000),
        )

        assertNull(result.reconciliation)
        assertTrue(InvoiceTotalsWarning.PRINTED_SUBTOTAL_DIFFERS_FROM_CALCULATED in result.warnings)
        assertTrue(InvoiceTotalsWarning.PRINTED_IGV_DIFFERS_FROM_CALCULATED in result.warnings)
    }

    @Test
    fun `conflicting printed subtotals do not fabricate a component difference`() {
        val result = parse(
            row("OP. GRAVADA", "S/ 100.00", 750),
            row("OP. EXONERADA", "S/ 0.00", 800),
            row("OP. INAFECTA", "S/ 0.00", 850),
            row("SUBTOTAL", "S/ 90.00", 900),
            row("SUBTOTAL", "S/ 100.00", 950),
            row("IGV 18%", "S/ 18.00", 1_000),
            row("TOTAL A PAGAR", "S/ 118.00", 1_050),
        )

        assertTrue(InvoiceTotalsWarning.MULTIPLE_PRINTED_VALUES in result.warnings)
        assertNull(result.reconciliation?.subtotalDifference)
    }

    @Test
    fun `conflicting printed rounding rows block reconciliation`() {
        val result = parse(
            row("SUBTOTAL", "S/ 100.00", 850),
            row("REDONDEO", "+ S/ 0.01", 900),
            row("DIFERENCIA DE REDONDEO", "- S/ 0.02", 950),
            row("TOTAL A PAGAR", "S/ 99.98", 1_000),
        )

        assertEquals(2, result.read.rounding.all.size)
        assertTrue(InvoiceTotalsWarning.MULTIPLE_PRINTED_VALUES in result.warnings)
        assertNull(result.calculated.total)
        assertNull(result.reconciliation)
        assertFalse(result.read.rounding.all.any { occurrence -> occurrence.candidate.alternatives.isNotEmpty() })
    }

    @Test
    fun `financial context from another draft or OCR run is rejected`() {
        val source = snapshot(row("TOTAL A PAGAR", "10.00", 900))
        val parser = InvoiceTotalsParser()

        val valid = parser.parse(
            source,
            source.context(currency = pen, taxRoundingMode = RoundingMode.HALF_UP),
        )

        val wrongDraft = runCatching {
            parser.parse(
                source,
                source.context(draftId = DraftId.from(UUID(0L, 999L)), currency = pen),
            )
        }.exceptionOrNull()
        val wrongRun = runCatching {
            parser.parse(
                source,
                source.context(
                    runId = OcrRunId.from(UUID(0L, 998L)),
                    currency = CurrencyCode.of("USD"),
                ),
            )
        }.exceptionOrNull()

        assertTrue(wrongDraft is IllegalArgumentException)
        assertTrue(wrongRun is IllegalArgumentException)
        assertEquals(pen, valid.currency)
        assertMoney(1_000L, valid.read.total.selected?.candidate?.value)
        assertEquals(AppConfiguration.DEFAULT_TAX_RATE, valid.referenceIgvRate)
        assertEquals(RoundingMode.HALF_UP, valid.taxRoundingMode)
        assertTrue(InvoiceTotalsWarning.CURRENCY_FROM_CONTEXT in valid.warnings)
    }

    private fun parse(vararg rows: List<Cell>): InvoiceTotalsParseResult {
        val source = snapshot(rows.flatMap { it })
        return InvoiceTotalsParser().parse(source, source.context(currency = pen))
    }

    private fun parseCells(
        cells: List<Cell>,
        currencyContext: CurrencyCode?,
    ): InvoiceTotalsParseResult {
        val source = snapshot(cells)
        return InvoiceTotalsParser().parse(source, source.context(currency = currencyContext))
    }

    private fun InvoiceOcrSnapshot.context(
        draftId: DraftId = this.draftId,
        runId: OcrRunId = this.runId,
        referenceIgvRate: TaxRate = AppConfiguration.DEFAULT_TAX_RATE,
        currency: CurrencyCode? = null,
        taxRoundingMode: RoundingMode? = null,
    ): InvoiceTotalsParseContext = InvoiceTotalsParseContext(
        draftId = draftId,
        runId = runId,
        referenceIgvRate = referenceIgvRate,
        currency = currency,
        taxRoundingMode = taxRoundingMode,
    )

    private fun row(label: String, value: String, top: Int): List<Cell> = listOf(
        Cell(label, InvoiceTextBoundingBox(650, top, 930, top + 34)),
        Cell(value, InvoiceTextBoundingBox(960, top, 1_160, top + 34)),
    )

    private fun snapshot(cells: List<Cell>): InvoiceOcrSnapshot {
        val page = InvoiceTextPage(
            sourceImageId = ImageId.from(UUID(0L, 203L)),
            pageIndex = 0,
            widthPx = 1_200,
            heightPx = 1_600,
            text = cells.joinToString("\n", transform = Cell::text),
            blocks = cells.mapIndexed { index, cell ->
                val geometry = InvoiceTextGeometry(cell.box, emptyList())
                InvoiceTextBlock(
                    position = index,
                    text = cell.text,
                    languageTag = "es-PE",
                    geometry = geometry,
                    lines = listOf(
                        InvoiceTextLine(
                            position = 0,
                            text = cell.text,
                            languageTag = "es-PE",
                            geometry = geometry,
                            confidencePermille = 990,
                            clockwiseAngleTenths = 0,
                            elements = emptyList(),
                        ),
                    ),
                )
            },
        )
        return InvoiceOcrSnapshot(
            draftId = DraftId.from(UUID(0L, 201L)),
            runId = OcrRunId.from(UUID(0L, 202L)),
            completedAt = Instant.parse("2026-08-09T12:00:00Z"),
            document = InvoiceTextDocument(listOf(page)),
        )
    }

    private fun assertMoney(expectedMinorUnits: Long, actual: Money?) {
        assertEquals(expectedMinorUnits, requireNotNull(actual).minorUnits)
        assertEquals(pen, actual.currency)
    }

    private data class Cell(val text: String, val box: InvoiceTextBoundingBox)
}
