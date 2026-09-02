package com.facturastock.app.data.local

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class EntityValidationTest {
    private val canonicalUuid = "123e4567-e89b-42d3-a456-426614174000"

    @Test
    fun `acepta UUID canónico en minúsculas`() {
        assertEquals(canonicalUuid, requireCanonicalUuid(canonicalUuid, "id"))
    }

    @Test
    fun `rechaza UUID mayúsculo, corto y nulo`() {
        assertThrows(IllegalArgumentException::class.java) {
            requireCanonicalUuid(canonicalUuid.uppercase(), "id")
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireCanonicalUuid("123e4567-e89b-42d3-a456", "id")
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireCanonicalUuid("00000000-0000-0000-0000-000000000000", "id")
        }
    }

    @Test
    fun `el texto decimal conserva valor y escala al leerse de vuelta`() {
        val stored = requireDecimalText("1234567890.1234567800", "quantity", allowZero = false)

        val restored = BigDecimal(stored)
        assertEquals(BigDecimal("1234567890.1234567800"), restored)
        assertEquals(10, restored.scale())
        assertEquals("1234567890.1234567800", stored)
    }

    @Test
    fun `distingue escalas declaradas distintas como valores persistidos distintos`() {
        val one = requireDecimalText("1.0", "quantity", allowZero = false)
        val two = requireDecimalText("1.00", "quantity", allowZero = false)

        assertEquals(1, BigDecimal(one).scale())
        assertEquals(2, BigDecimal(two).scale())
        assertEquals(0, BigDecimal(one).compareTo(BigDecimal(two)))
    }

    @Test
    fun `rechaza decimales con signo, exponente o precisión excesiva`() {
        assertThrows(IllegalArgumentException::class.java) {
            requireDecimalText("-1.5", "quantity", allowZero = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireDecimalText("1.0E+2", "quantity", allowZero = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireDecimalText("123456789012345678901234567890123456789", "quantity", allowZero = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireDecimalText("0.1234567890123456789", "quantity", allowZero = false)
        }
    }

    @Test
    fun `aplica la regla de cero según el tipo de valor`() {
        assertThrows(IllegalArgumentException::class.java) {
            requireDecimalText("0", "quantity", allowZero = false)
        }
        assertEquals("0.00", requireDecimalText("0.00", "unitCost", allowZero = true))
    }

    @Test
    fun `normaliza y valida códigos de moneda ISO 4217`() {
        assertEquals("PEN", requireCurrencyCode("pen", "currency"))
        assertThrows(Exception::class.java) {
            requireCurrencyCode("XXX-invalid", "currency")
        }
    }

    @Test
    fun `valida RUC de 11 dígitos y recorta espacios`() {
        assertEquals("20123456789", requireRuc(" 20123456789 ", "ruc"))
        assertThrows(IllegalArgumentException::class.java) {
            requireRuc("2012345678", "ruc")
        }
        assertNull(requireRucOrNull(null, "ruc"))
    }

    @Test
    fun `valida hash SHA-256 en minúsculas`() {
        val hash = "a".repeat(64)
        assertEquals(hash, requireSha256(hash, "sha256"))
        assertThrows(IllegalArgumentException::class.java) {
            requireSha256("A".repeat(64), "sha256")
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireSha256("a".repeat(63), "sha256")
        }
    }

    @Test
    fun `la confianza admite 0 a 1000 y nulo`() {
        assertNull(requireConfidenceOrNull(null, "confidence"))
        assertEquals(0, requireConfidenceOrNull(0, "confidence"))
        assertEquals(1000, requireConfidenceOrNull(1000, "confidence"))
        assertThrows(IllegalArgumentException::class.java) {
            requireConfidenceOrNull(1001, "confidence")
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireConfidenceOrNull(-1, "confidence")
        }
    }

    @Test
    fun `las marcas de tiempo exigen orden cronológico`() {
        requireTimestamps(1_000L, 1_000L)
        assertThrows(IllegalArgumentException::class.java) {
            requireTimestamps(2_000L, 1_000L)
        }
    }
}
