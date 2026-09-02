package com.facturastock.app.feature.linereview

import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.model.InventoryTaxTreatment
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.Confidence
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.Field
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.FieldError
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.FieldId
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.Line
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.State
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InvoiceLineReviewContractTest {
    @Test
    fun `search ignores case and accents and keeps the persisted order`() {
        val state = State(
            draftId = DRAFT_ID,
            isLoading = false,
            // Deliberately not supplied in position order: the screen must never inherit the
            // incidental order of a repository emission.
            lines = listOf(
                line(seed = 2, position = 1, description = "ARROZ EXTRA", code = "AR-02"),
                line(seed = 1, position = 0, description = "AZÚCAR RUBIA", code = "AZ-01"),
                line(seed = 3, position = 2, description = "ACEITE VEGETAL", code = "AC-03"),
            ),
            searchQuery = "azucar",
        )

        assertEquals(listOf(lineId(1)), state.visibleLines.map(Line::lineId))
        assertEquals(
            listOf(lineId(1), lineId(2), lineId(3)),
            state.copy(searchQuery = "").visibleLines.map(Line::lineId),
        )
        assertEquals(
            listOf(lineId(2)),
            state.copy(searchQuery = "ar-02").visibleLines.map(Line::lineId),
        )
    }

    @Test
    fun `pending filter distinguishes unresolved OCR from a human confirmation`() {
        val unresolvedLow = line(
            seed = 1,
            position = 0,
            confidence = Confidence.LOW,
            requiresReview = true,
        )
        val confirmedLow = line(
            seed = 2,
            position = 1,
            confidence = Confidence.LOW,
            requiresReview = true,
            confirmedByUser = true,
        )
        val missingQuantity = line(seed = 3, position = 2, quantity = "")
        val malformedOptionalAmount = line(
            seed = 4,
            position = 3,
            fieldErrors = mapOf(FieldId.IGV to FieldError.INVALID_AMOUNT),
        )
        val state = State(
            draftId = DRAFT_ID,
            isLoading = false,
            lines = listOf(unresolvedLow, confirmedLow, missingQuantity, malformedOptionalAmount),
            pendingOnly = true,
        )

        assertTrue(unresolvedLow.isPending)
        assertFalse(confirmedLow.isPending)
        assertEquals(
            listOf(lineId(1), lineId(3), lineId(4)),
            state.visibleLines.map(Line::lineId),
        )
        assertEquals(3, state.pendingCount)
        assertFalse(state.canLinkProducts)
    }

    @Test
    fun `exonerada con IGV positivo queda pendiente y aparece en el filtro`() {
        val positiveTax = line(
            seed = 1,
            position = 0,
            taxTreatment = InventoryTaxTreatment.EXEMPT,
            igv = "0,01",
        )
        val zeroTax = line(
            seed = 2,
            position = 1,
            taxTreatment = InventoryTaxTreatment.EXEMPT,
            igv = "0.00",
        )
        val state = State(
            draftId = DRAFT_ID,
            isLoading = false,
            lines = listOf(positiveTax, zeroTax),
            pendingOnly = true,
        )

        assertTrue(positiveTax.isPending)
        assertFalse(zeroTax.isPending)
        assertEquals(listOf(positiveTax.lineId), state.visibleLines.map(Line::lineId))
        assertEquals(1, state.pendingCount)
        assertFalse(state.canLinkProducts)
    }

    @Test
    fun `one hundred stable lines remain addressable without using their list index as identity`() {
        val lines = (0 until 100).map { index ->
            line(
                seed = index + 1,
                position = 99 - index,
                description = "Producto ${index + 1}",
                code = "SKU-${index + 1}",
            )
        }
        val state = State(
            draftId = DRAFT_ID,
            isLoading = false,
            lines = lines,
        )

        assertEquals(100, state.visibleLines.size)
        assertEquals((0 until 100).toList(), state.visibleLines.map(Line::position))
        assertEquals(
            lines.single { it.field(FieldId.CODE).value == "SKU-100" }.lineId,
            state.copy(searchQuery = "sku-100").visibleLines.single().lineId,
        )
    }

    @Test
    fun `long requested scroll jumps near destination and animates only four rows`() {
        assertEquals(
            96,
            InvoiceLineReviewScrollPolicy.stagingIndex(
                currentIndex = 0,
                targetIndex = 100,
                lastIndex = 100,
            ),
        )
        assertEquals(
            5,
            InvoiceLineReviewScrollPolicy.stagingIndex(
                currentIndex = 100,
                targetIndex = 1,
                lastIndex = 100,
            ),
        )
    }

    @Test
    fun `near requested scroll keeps its direct animation`() {
        assertEquals(
            null,
            InvoiceLineReviewScrollPolicy.stagingIndex(
                currentIndex = 4,
                targetIndex = 12,
                lastIndex = 100,
            ),
        )
        assertEquals(
            null,
            InvoiceLineReviewScrollPolicy.stagingIndex(
                currentIndex = 0,
                targetIndex = 101,
                lastIndex = 100,
            ),
        )
    }

    @Test
    fun `card description limit preserves emoji code points and communicates truncation`() {
        val prefix = "a".repeat(InvoiceLineReviewContract.MAX_CARD_DESCRIPTION_LENGTH - 2)
        val oversized = prefix + "😀bc"

        val limited = InvoiceLineReviewContract.limitCardDescription(oversized)

        assertEquals(prefix + "😀…", limited)
        assertEquals(
            InvoiceLineReviewContract.MAX_CARD_DESCRIPTION_LENGTH,
            limited.codePointCount(0, limited.length),
        )
        assertEquals(
            "😀",
            InvoiceLineReviewContract.limitCardDescription("😀"),
        )
    }

    @Test
    fun `duplicate stable keys or positions are rejected before LazyColumn receives them`() {
        val first = line(seed = 1, position = 0)

        assertThrows(IllegalArgumentException::class.java) {
            State(lines = listOf(first, first.copy(position = 1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            State(lines = listOf(first, line(seed = 2, position = 0)))
        }
    }

    private fun line(
        seed: Int,
        position: Int,
        description: String = "Producto $seed",
        code: String = "SKU-$seed",
        quantity: String = "1",
        igv: String = "1.80",
        confidence: Confidence = Confidence.HIGH,
        requiresReview: Boolean = false,
        confirmedByUser: Boolean = false,
        taxTreatment: InventoryTaxTreatment = InventoryTaxTreatment.EXCLUDED,
        fieldErrors: Map<FieldId, FieldError> = emptyMap(),
    ): Line {
        val values = mapOf(
            FieldId.DESCRIPTION to description,
            FieldId.CODE to code,
            FieldId.QUANTITY to quantity,
            FieldId.UNIT to "NIU",
            FieldId.UNIT_COST to "10.00",
            FieldId.DISCOUNT to "0.00",
            FieldId.IGV to igv,
            FieldId.TOTAL to "11.80",
        )
        return Line(
            lineId = lineId(seed),
            position = position,
            fields = FieldId.entries.map { id ->
                Field(
                    id = id,
                    value = values.getValue(id),
                    confidence = confidence,
                    error = fieldErrors[id],
                )
            },
            confidence = confidence,
            confidencePercent = when (confidence) {
                Confidence.HIGH -> 95
                Confidence.MEDIUM -> 80
                Confidence.LOW -> 50
                Confidence.UNKNOWN -> null
            },
            requiresReview = requiresReview,
            confirmedByUser = confirmedByUser,
            taxTreatment = taxTreatment,
        )
    }

    private fun lineId(seed: Int): LineId = LineId.from(
        UUID.fromString("00000000-0000-0000-0000-%012d".format(seed)),
    )

    private companion object {
        val DRAFT_ID: DraftId = DraftId.from(
            UUID.fromString("10000000-0000-0000-0000-000000000001"),
        )
    }
}
