package com.facturastock.app.domain.model

import com.facturastock.app.domain.error.DomainRuleViolation
import com.facturastock.app.domain.error.ValidationError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CurrencyCodeTest {
    @Test
    fun `normaliza y acepta codigos ISO 4217`() {
        assertEquals("PEN", CurrencyCode.of(" pen ").value)
        assertEquals("USD", CurrencyCode.of("usd").value)
    }

    @Test
    fun `rechaza un codigo que no pertenece a ISO 4217`() {
        val violation = assertThrows(DomainRuleViolation::class.java) {
            CurrencyCode.of("ZZZ")
        }

        assertEquals(ValidationError.InvalidCurrencyCode("ZZZ"), violation.error)
    }
}
