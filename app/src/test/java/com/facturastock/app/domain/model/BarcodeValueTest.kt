package com.facturastock.app.domain.model

import com.facturastock.app.domain.error.DomainRuleViolation
import com.facturastock.app.domain.error.ValidationError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BarcodeValueTest {
    @Test
    fun `canonical form trims ASCII edge spaces and preserves meaningful text exactly`() {
        val value = BarcodeValue.of("  0012aB-Z / 9  ")

        assertEquals("0012aB-Z / 9", value.value)
        assertEquals("0012aB-Z / 9", value.toString())
        assertEquals("0012aB-Z / 9", CatalogCanonicalizer.barcode("  0012aB-Z / 9  "))
    }

    @Test
    fun `one and 128 printable ASCII characters are accepted without GTIN checksum`() {
        assertEquals("0", BarcodeValue.of("0").value)
        assertEquals("A".repeat(BarcodeValue.MAX_LENGTH), BarcodeValue.of("A".repeat(128)).value)
        assertEquals("7750001", BarcodeValue.of("7750001").value)
    }

    @Test
    fun `optional form distinguishes absence from invalid content`() {
        assertNull(BarcodeValue.optionalOf(null))
        assertNull(BarcodeValue.optionalOf(""))
        assertNull(BarcodeValue.optionalOf("   "))
        assertTrue(BarcodeValue.isValidOptional("   "))
        assertFalse(BarcodeValue.isValidOptional("\t"))
    }

    @Test
    fun `empty overlong control bidi and non ASCII values are rejected`() {
        val invalid = listOf(
            "",
            "   ",
            "A".repeat(BarcodeValue.MAX_LENGTH + 1),
            "ABC\u0000DEF",
            "\tABC",
            "ABC\n",
            "ABC\rDEF",
            "ABC\u007F",
            "ABC\u202E123",
            "ABC\u200F123",
            "ABC\u2066123",
            "café",
            "ＡＢＣ123",
        )

        invalid.forEach { input ->
            assertNull("parse accepted ${input.toSafeDescription()}", BarcodeValue.parse(input))
            val error = assertThrows(DomainRuleViolation::class.java) { BarcodeValue.of(input) }
            assertEquals(ValidationError.InvalidBarcode, error.error)
        }
    }

    private fun String.toSafeDescription(): String =
        codePoints().toArray().joinToString(prefix = "[", postfix = "]") { codePoint ->
            "U+${codePoint.toString(16).uppercase().padStart(4, '0')}"
        }
}
