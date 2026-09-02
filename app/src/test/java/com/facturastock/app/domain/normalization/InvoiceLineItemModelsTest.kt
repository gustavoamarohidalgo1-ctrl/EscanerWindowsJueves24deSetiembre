package com.facturastock.app.domain.normalization

import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.id.ImageId
import java.util.UUID
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InvoiceLineItemModelsTest {
    @Test
    fun `review requirement is derived from unresolved cell candidates`() {
        val quantity = requireNotNull(PeruvianValueParser.parseQuantity("I"))
        val item = ParsedInvoiceLineItem(
            position = 0,
            pageIndex = 0,
            rawText = "I",
            evidence = listOf(quantity.evidence),
            boundingBox = null,
            quantity = quantity,
        )

        assertTrue(quantity.requiresReview)
        assertTrue(item.requiresReview)
    }

    @Test
    fun `an adjustment amount rejects a negative Money even though Money itself is signed`() {
        assertThrows(IllegalArgumentException::class.java) {
            InvoiceLineAdjustment.Amount(
                Money.ofMinor(-1L, CurrencyCode.of("PEN")),
            )
        }
    }

    @Test
    fun `row evidence cannot claim a different OCR page`() {
        val foreignEvidence = PeruvianTextNormalizer.normalize(
            CandidateSource(
                rawText = "PRODUCTO",
                sourceImageId = ImageId.from(UUID(0L, 99L)),
                pageIndex = 1,
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            ParsedInvoiceLineItem(
                position = 0,
                pageIndex = 0,
                rawText = "PRODUCTO",
                evidence = listOf(foreignEvidence),
                boundingBox = null,
            )
        }
    }
}
