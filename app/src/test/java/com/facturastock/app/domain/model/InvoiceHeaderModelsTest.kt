package com.facturastock.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InvoiceHeaderModelsTest {
    @Test
    fun `document number keeps series and correlative including leading zeroes`() {
        val number = requireNotNull(InvoiceDocumentNumber.parseCanonical("F001-00012345"))

        assertEquals("F001", number.series)
        assertEquals("00012345", number.correlative)
        assertEquals("F001-00012345", number.normalized)
    }

    @Test
    fun `canonical parser rejects partial or malformed document numbers`() {
        listOf(
            "F001 - 12345",
            "F001-12345 extra",
            "F001/12345",
            "F001-",
            "ABCDE-12345",
            "F001-1234567890123",
        ).forEach { raw -> assertNull(raw, InvoiceDocumentNumber.parseCanonical(raw)) }
    }
}
