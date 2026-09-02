package com.facturastock.app.domain.normalization

import org.junit.Assert.assertEquals
import org.junit.Test

class ParsedInvoiceConfidenceTest {
    @Test
    fun `confidence boundaries are explicit and stable`() {
        assertEquals(ParsedInvoiceConfidence.UNKNOWN, ParsedInvoiceConfidence.fromPermille(null))
        assertEquals(ParsedInvoiceConfidence.LOW, ParsedInvoiceConfidence.fromPermille(0))
        assertEquals(ParsedInvoiceConfidence.LOW, ParsedInvoiceConfidence.fromPermille(699))
        assertEquals(ParsedInvoiceConfidence.MEDIUM, ParsedInvoiceConfidence.fromPermille(700))
        assertEquals(ParsedInvoiceConfidence.MEDIUM, ParsedInvoiceConfidence.fromPermille(899))
        assertEquals(ParsedInvoiceConfidence.HIGH, ParsedInvoiceConfidence.fromPermille(900))
        assertEquals(ParsedInvoiceConfidence.HIGH, ParsedInvoiceConfidence.fromPermille(1_000))
    }
}
