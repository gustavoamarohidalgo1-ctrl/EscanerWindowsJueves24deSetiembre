package com.facturastock.app.domain.model

import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.LineId
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InvoiceLinesEditTest {
    @Test
    fun `procedencia elegida es explicita y conserva los otros valores`() {
        val value = InvoiceLineEditValue(
            ocr = "11.80",
            calculated = "11.79",
            written = "11,81",
            selectedSource = InvoiceLineValueSource.WRITTEN,
        )

        assertEquals("11,81", value.selectedValue)
        assertEquals("11.80", value.ocr)
        assertEquals("11.79", value.calculated)
        assertThrows(IllegalArgumentException::class.java) {
            value.copy(written = null)
        }
    }

    @Test
    fun `confirmacion de fila resuelve dudas pero no campos imprescindibles ausentes`() {
        val doubtful = line(1).copy(
            requiresReview = true,
            reviewRequiredFields = setOf(InvoiceLineEditField.TOTAL),
        )
        assertTrue(doubtful.isPending)
        assertFalse(doubtful.copy(reviewConfirmedByUser = true).isPending)

        val missingQuantity = doubtful.copy(
            quantity = InvoiceLineEditValue(written = "", selectedSource = InvoiceLineValueSource.WRITTEN),
            reviewConfirmedByUser = true,
        )
        assertTrue(missingQuantity.isPending)
    }

    @Test
    fun `linea exonerada con IGV positivo permanece pendiente y visible para correccion`() {
        val exempt = line(1)

        assertFalse(exempt.isPending)
        assertTrue(exempt.copy(igv = written("0,01")).isPending)
        assertFalse(exempt.copy(igv = written("0.00")).isPending)
    }

    @Test
    fun `admite cien activas y quinientos tombstones pero rechaza excedentes`() {
        val active = (1..100).map { index -> line(index, position = index - 1) }
        val accepted = InvoiceLinesEdit(DRAFT_ID, active, 1, TIME)
        assertEquals(100, accepted.activeLines.size)

        assertThrows(IllegalArgumentException::class.java) {
            InvoiceLinesEdit(
                DRAFT_ID,
                active + line(101, position = 100),
                2,
                TIME,
            )
        }

        val tombstones = (1..500).map { index ->
            line(index, position = index - 1).copy(deletedAt = TIME)
        }
        assertEquals(500, InvoiceLinesEdit(DRAFT_ID, tombstones, 3, TIME).lines.size)
        assertThrows(IllegalArgumentException::class.java) {
            InvoiceLinesEdit(
                DRAFT_ID,
                tombstones + line(501).copy(deletedAt = TIME),
                4,
                TIME,
            )
        }
    }

    @Test
    fun `tombstone no puede fecharse despues de la propia fila`() {
        assertThrows(IllegalArgumentException::class.java) {
            line(1).copy(deletedAt = TIME.plusSeconds(1))
        }
    }

    private fun line(seed: Int, position: Int = 0) = InvoiceLineEdit(
        lineId = lineId(seed),
        position = position,
        origin = InvoiceLineEditOrigin.USER,
        taxTreatment = InventoryTaxTreatment.EXEMPT,
        productProvenance = PurchaseProductProvenance.EXISTING,
        description = InvoiceLineEditValue(
            written = "Producto $seed",
            selectedSource = InvoiceLineValueSource.WRITTEN,
        ),
        quantity = InvoiceLineEditValue(
            written = "1",
            selectedSource = InvoiceLineValueSource.WRITTEN,
        ),
        createdAt = TIME,
        updatedAt = TIME,
    )

    private fun written(value: String) = InvoiceLineEditValue(
        written = value,
        selectedSource = InvoiceLineValueSource.WRITTEN,
    )

    private fun lineId(seed: Int): LineId = LineId.from(
        UUID.fromString("00000000-0000-0000-0000-%012d".format(seed)),
    )

    private companion object {
        val TIME: Instant = Instant.parse("2026-08-10T12:00:00Z")
        val DRAFT_ID: DraftId = DraftId.from(
            UUID.fromString("00000000-0000-0000-0000-000000000999"),
        )
    }
}
