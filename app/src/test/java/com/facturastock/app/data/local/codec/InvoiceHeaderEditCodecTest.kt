package com.facturastock.app.data.local.codec

import com.facturastock.app.domain.model.InvoiceHeaderEdit
import com.facturastock.app.domain.model.InvoiceHeaderEditField
import com.facturastock.app.domain.model.id.DraftId
import java.io.IOException
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class InvoiceHeaderEditCodecTest {
    @Test
    fun `round trip conserva texto parcial vacio unicode y campos tocados`() {
        val edit = edit()

        val payload = InvoiceHeaderEditCodec.encode(edit)

        assertEquals(edit, InvoiceHeaderEditCodec.decode(payload))
        assertEquals(64, InvoiceHeaderEditCodec.sha256(payload).length)
    }

    @Test
    fun `payload es determinista aunque el set tenga distinto orden de insercion`() {
        val first = edit().copy(
            touchedFields = linkedSetOf(
                InvoiceHeaderEditField.TOTAL,
                InvoiceHeaderEditField.SUPPLIER_RUC,
                InvoiceHeaderEditField.ISSUE_DATE,
            ),
        )
        val second = first.copy(
            touchedFields = linkedSetOf(
                InvoiceHeaderEditField.ISSUE_DATE,
                InvoiceHeaderEditField.SUPPLIER_RUC,
                InvoiceHeaderEditField.TOTAL,
            ),
        )

        assertArrayEquals(
            InvoiceHeaderEditCodec.encode(first),
            InvoiceHeaderEditCodec.encode(second),
        )
    }

    @Test
    fun `rechaza payload truncado con bytes sobrantes o version desconocida`() {
        val payload = InvoiceHeaderEditCodec.encode(edit())
        val unknownVersion = payload.copyOf().apply {
            // Los bytes 4..7 son la versión big-endian.
            this[7] = 2
        }

        assertThrows(IOException::class.java) {
            InvoiceHeaderEditCodec.decode(payload.copyOf(payload.size - 1))
        }
        assertThrows(IOException::class.java) {
            InvoiceHeaderEditCodec.decode(payload + byteArrayOf(0))
        }
        assertThrows(IOException::class.java) {
            InvoiceHeaderEditCodec.decode(unknownVersion)
        }
    }

    private fun edit() = InvoiceHeaderEdit(
        draftId = DRAFT_ID,
        supplierRuc = "20",
        supplierLegalName = "Distribuidora Pacífico S.A.C.",
        documentType = "FACT",
        documentSeries = "F00",
        documentNumber = "",
        issueDate = "20/",
        currency = "S/",
        subtotal = "12,",
        igv = null,
        otherCharges = "0.2",
        total = "14.36",
        revision = 7,
        touchedFields = setOf(
            InvoiceHeaderEditField.SUPPLIER_RUC,
            InvoiceHeaderEditField.ISSUE_DATE,
            InvoiceHeaderEditField.TOTAL,
        ),
        updatedAt = Instant.parse("2026-08-10T12:34:56Z"),
    )

    private companion object {
        val DRAFT_ID: DraftId = DraftId.from(
            UUID.fromString("00000000-0000-0000-0000-000000000023"),
        )
    }
}
