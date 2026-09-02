package com.facturastock.app.ui.format

import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.Money
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class FormattersTest {
    @Test
    fun `decimal money uses the same localized presentation as typed money`() {
        assertEquals(
            Money.fromMajor("1234.56", CurrencyCode.of("PEN")).formatForDisplay(),
            formatCurrencyAmountForDisplay("PEN", "1234.56"),
        )
        assertEquals("S/", currencyLabelForDisplay("PEN"))
    }

    @Test
    fun `a presentation symbol formats directly without requiring an ISO code`() {
        assertEquals("S/", currencyLabelForDisplay("S/"))
        assertEquals("S/ 1.18", formatCurrencyAmountForDisplay("S/", "1.18"))
    }

    @Test
    fun `money in PEN shows the sol symbol and the ISO scale`() {
        val formatted = Money.ofMinor(123_456, CurrencyCode.of("PEN")).formatForDisplay()

        assertTrue(formatted.contains("S/"))
        assertTrue(formatted.contains("1,234.56"))
    }

    @Test
    fun `money keeps the fraction digits of its currency`() {
        val yen = Money.ofMinor(1_250, CurrencyCode.of("JPY")).formatForDisplay()

        assertTrue(yen.contains("1,250"))
        assertTrue(!yen.contains("1,250."))
    }

    @Test
    fun `local date renders in the Spanish medium style`() {
        val formatted = LocalDate.of(2026, 8, 1).formatForDisplay()

        assertTrue(formatted.contains("2026"))
        assertTrue(formatted.contains("1"))
        assertTrue(formatted.lowercase(Locale.ROOT).contains("ago"))
    }

    @Test
    fun `instant renders with date and time in the given zone`() {
        val formatted = Instant.parse("2026-08-01T17:30:00Z")
            .formatForDisplay(ZoneId.of("America/Lima"))

        assertTrue(formatted.contains("2026"))
        assertTrue(formatted.contains("12:30"))
        assertTrue(formatted.lowercase(Locale.ROOT).contains("ago"))
    }

    @Test
    fun `instant defaults to the configured Lima presentation zone`() {
        val formatted = Instant.parse("2026-08-01T17:30:00Z").formatForDisplay()

        assertTrue(formatted.contains("12:30"))
    }
}
