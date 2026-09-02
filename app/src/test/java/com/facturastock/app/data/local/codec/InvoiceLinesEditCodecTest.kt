package com.facturastock.app.data.local.codec

import com.facturastock.app.domain.model.InvoiceLineEdit
import com.facturastock.app.domain.model.InvoiceLineEditField
import com.facturastock.app.domain.model.InvoiceLineEditOrigin
import com.facturastock.app.domain.model.InvoiceLineEditValue
import com.facturastock.app.domain.model.InvoiceLineValueSource
import com.facturastock.app.domain.model.InvoiceLinesEdit
import com.facturastock.app.domain.model.InventoryTaxTreatment
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.PurchaseProductProvenance
import com.facturastock.app.domain.model.StagedPurchaseProduct
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.UnitId
import java.io.IOException
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class InvoiceLinesEditCodecTest {
    @Test
    fun `round trip conserva OCR calculado escrito confianza y tombstone`() {
        val edit = edit()

        val payload = InvoiceLinesEditCodec.encode(edit)

        assertEquals(edit, InvoiceLinesEditCodec.decode(payload))
        assertEquals(64, InvoiceLinesEditCodec.sha256(payload).length)
    }

    @Test
    fun `payload ordena mapas y sets de manera determinista`() {
        val first = edit()
        val active = first.lines.first()
        val reordered = active.copy(
            fieldConfidencePermille = linkedMapOf(
                InvoiceLineEditField.TOTAL to 650,
                InvoiceLineEditField.DESCRIPTION to 990,
            ),
            reviewRequiredFields = linkedSetOf(
                InvoiceLineEditField.TOTAL,
                InvoiceLineEditField.QUANTITY,
            ),
            touchedFields = linkedSetOf(
                InvoiceLineEditField.QUANTITY,
                InvoiceLineEditField.TOTAL,
            ),
        )
        val canonical = reordered.copy(
            fieldConfidencePermille = linkedMapOf(
                InvoiceLineEditField.DESCRIPTION to 990,
                InvoiceLineEditField.TOTAL to 650,
            ),
            reviewRequiredFields = linkedSetOf(
                InvoiceLineEditField.QUANTITY,
                InvoiceLineEditField.TOTAL,
            ),
            touchedFields = linkedSetOf(
                InvoiceLineEditField.TOTAL,
                InvoiceLineEditField.QUANTITY,
            ),
        )

        assertArrayEquals(
            InvoiceLinesEditCodec.encode(first.copy(lines = listOf(reordered, first.lines[1]))),
            InvoiceLinesEditCodec.encode(first.copy(lines = listOf(canonical, first.lines[1]))),
        )
    }

    @Test
    fun `round trip conserva producto staged completo`() {
        val current = edit()
        val active = current.lines.first()
        val staged = StagedPurchaseProduct(
            productId = requireNotNull(active.linkedProductId),
            businessId = BusinessId.from(
                UUID.fromString("00000000-0000-0000-0000-000000000300"),
            ),
            unitId = requireNotNull(active.linkedUnitId),
            name = "Café orgánico staged",
            sku = "CAFE-STAGED",
            salePrice = Money.ofMinor(725L, CurrencyCode.of("PEN")),
        )
        val withStaged = current.copy(
            lines = listOf(
                active.copy(
                    productProvenance = PurchaseProductProvenance.CREATED_IN_DRAFT,
                    stagedProduct = staged,
                ),
                current.lines[1],
            ),
        )

        assertEquals(
            withStaged,
            InvoiceLinesEditCodec.decode(InvoiceLinesEditCodec.encode(withStaged)),
        )
    }

    @Test
    fun `payload v2 conserva campos historicos y exige revisar tax y procedencia`() {
        val current = edit()
        val legacyPayload = LegacyCodecPayloadFixtures.invoiceLinesV2(
            InvoiceLinesEditCodec.encode(current),
        )

        val decoded = InvoiceLinesEditCodec.decode(legacyPayload)

        assertEquals(
            current.copy(
                lines = current.lines.map { line ->
                    line.copy(
                        taxTreatment = InventoryTaxTreatment.UNKNOWN,
                        productProvenance = PurchaseProductProvenance.UNKNOWN_LEGACY,
                        stagedProduct = null,
                    )
                },
            ),
            decoded,
        )
        assertEquals(true, decoded.activeLines.single().isPending)
    }

    @Test
    fun `payload v3 conserva producto staged legacy sin inventar precio`() {
        val current = edit()
        val active = current.lines.first()
        val staged = StagedPurchaseProduct(
            productId = requireNotNull(active.linkedProductId),
            businessId = BusinessId.from(
                UUID.fromString("00000000-0000-0000-0000-000000000300"),
            ),
            unitId = requireNotNull(active.linkedUnitId),
            name = "Café staged legacy",
            sku = "CAFE-LEGACY",
        )
        val expected = current.copy(
            lines = listOf(
                active.copy(
                    productProvenance = PurchaseProductProvenance.CREATED_IN_DRAFT,
                    stagedProduct = staged,
                ),
                current.lines[1],
            ),
        )
        val payload = LegacyCodecPayloadFixtures.invoiceLinesV3(
            InvoiceLinesEditCodec.encode(expected),
        )

        val decoded = InvoiceLinesEditCodec.decode(payload)

        assertEquals(expected, decoded)
        assertEquals(null, decoded.lines.first().stagedProduct?.salePrice)
    }

    @Test
    fun `rechaza truncado bytes sobrantes y version desconocida`() {
        val payload = InvoiceLinesEditCodec.encode(edit())
        val unknownVersion = payload.copyOf().apply {
            this[7] = (InvoiceLinesEditCodec.VERSION + 1).toByte()
        }

        assertThrows(IOException::class.java) {
            InvoiceLinesEditCodec.decode(payload.copyOf(payload.size - 1))
        }
        assertThrows(IOException::class.java) {
            InvoiceLinesEditCodec.decode(payload + byteArrayOf(0))
        }
        assertThrows(IOException::class.java) {
            InvoiceLinesEditCodec.decode(unknownVersion)
        }
    }

    private fun edit(): InvoiceLinesEdit {
        val active = InvoiceLineEdit(
            lineId = lineId(1),
            position = 0,
            origin = InvoiceLineEditOrigin.OCR,
            sourcePosition = 4,
            ocrRawText = "P001 Café orgánico 2 UND 5.90 11.80",
            linkedProductId = ProductId.from(
                UUID.fromString("00000000-0000-0000-0000-000000000100"),
            ),
            linkedUnitId = UnitId.from(
                UUID.fromString("00000000-0000-0000-0000-000000000200"),
            ),
            linkConfidence = 940,
            taxTreatment = InventoryTaxTreatment.EXCLUDED,
            productProvenance = PurchaseProductProvenance.EXISTING,
            description = InvoiceLineEditValue(
                ocr = "Café orgánico",
                written = "Café orgánico premium",
                selectedSource = InvoiceLineValueSource.WRITTEN,
            ),
            code = InvoiceLineEditValue(ocr = "P001", selectedSource = InvoiceLineValueSource.OCR),
            quantity = InvoiceLineEditValue(
                ocr = "1.50",
                ocrRaw = "01,50",
                selectedSource = InvoiceLineValueSource.OCR,
            ),
            unit = InvoiceLineEditValue(ocr = "UND", selectedSource = InvoiceLineValueSource.OCR),
            unitCost = InvoiceLineEditValue(ocr = "5.90", selectedSource = InvoiceLineValueSource.OCR),
            discount = InvoiceLineEditValue(calculated = "0.00", selectedSource = InvoiceLineValueSource.CALCULATED),
            igv = InvoiceLineEditValue(calculated = "1.80", selectedSource = InvoiceLineValueSource.CALCULATED),
            total = InvoiceLineEditValue(
                ocr = "11.80",
                ocrRaw = "S/ 11,80",
                calculated = "11.79",
                selectedSource = InvoiceLineValueSource.OCR,
            ),
            confidencePermille = 650,
            fieldConfidencePermille = linkedMapOf(
                InvoiceLineEditField.DESCRIPTION to 990,
                InvoiceLineEditField.TOTAL to 650,
            ),
            requiresReview = true,
            reviewConfirmedByUser = true,
            reviewRequiredFields = linkedSetOf(
                InvoiceLineEditField.QUANTITY,
                InvoiceLineEditField.TOTAL,
            ),
            touchedFields = linkedSetOf(
                InvoiceLineEditField.TOTAL,
                InvoiceLineEditField.QUANTITY,
            ),
            createdAt = TIME,
            updatedAt = TIME.plusSeconds(2),
        )
        val deleted = InvoiceLineEdit(
            lineId = lineId(2),
            position = 1,
            origin = InvoiceLineEditOrigin.USER,
            description = InvoiceLineEditValue(
                written = "",
                selectedSource = InvoiceLineValueSource.WRITTEN,
            ),
            quantity = InvoiceLineEditValue(
                written = "-",
                selectedSource = InvoiceLineValueSource.WRITTEN,
            ),
            deletedAt = TIME.plusSeconds(3),
            createdAt = TIME.plusSeconds(1),
            updatedAt = TIME.plusSeconds(3),
        )
        return InvoiceLinesEdit(
            draftId = DRAFT_ID,
            lines = listOf(active, deleted),
            revision = 7,
            updatedAt = TIME.plusSeconds(3),
        )
    }

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
