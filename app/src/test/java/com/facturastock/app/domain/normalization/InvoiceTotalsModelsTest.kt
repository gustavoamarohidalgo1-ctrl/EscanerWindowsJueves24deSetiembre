package com.facturastock.app.domain.normalization

import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.id.ImageId
import java.util.UUID
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InvoiceTotalsModelsTest {
    private val pen = CurrencyCode.of("PEN")

    @Test
    fun `rounding direction must match the exact signed amount`() {
        assertThrows(IllegalArgumentException::class.java) {
            InvoiceRoundingAdjustment(
                signedAmount = Money.ofMinor(-3L, pen),
                direction = InvoiceRoundingDirection.INCREASE,
                reason = InvoiceRoundingReason.PRINTED_ROUNDING,
            )
        }
    }

    @Test
    fun `occurrence evidence cannot mix OCR pages`() {
        val parsed = requireNotNull(PeruvianValueParser.parseMoney("S/ 1.00"))
        val foreign = PeruvianTextNormalizer.normalize(
            CandidateSource(
                rawText = "TOTAL",
                sourceImageId = ImageId.from(UUID(0L, 91L)),
                pageIndex = 1,
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            InvoiceTotalOccurrence(
                candidate = parsed,
                label = "TOTAL",
                rawText = "TOTAL S/ 1.00",
                pageIndex = 0,
                evidence = listOf(foreign),
                boundingBox = null,
            )
        }
    }

    @Test
    fun `result review is derived from an unresolved printed candidate`() {
        val uncertain = requireNotNull(PeruvianValueParser.parseMoney("S/ I8.00"))
        val occurrence = InvoiceTotalOccurrence(
            candidate = uncertain,
            label = "IGV",
            rawText = "IGV S/ I8.00",
            pageIndex = 0,
            evidence = listOf(uncertain.evidence),
            boundingBox = null,
        )
        val result = InvoiceTotalsParseResult(
            draftId = com.facturastock.app.domain.model.id.DraftId.from(UUID(0L, 92L)),
            runId = com.facturastock.app.domain.model.id.OcrRunId.from(UUID(0L, 93L)),
            currency = pen,
            referenceIgvRate = com.facturastock.app.domain.config.AppConfiguration.DEFAULT_TAX_RATE,
            taxRoundingMode = null,
            read = ReadInvoiceTotals(igv = InvoiceTotalField(selected = occurrence)),
            calculated = CalculatedInvoiceTotals(),
            reconciliation = null,
        )

        assertTrue(uncertain.requiresReview)
        assertTrue(result.requiresReview)
    }
}
