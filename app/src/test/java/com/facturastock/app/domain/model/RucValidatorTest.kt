package com.facturastock.app.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RucValidatorTest {
    @Test
    fun `el formato exige exactamente 11 digitos tras recortar espacios`() {
        assertTrue(RucValidator.isWellFormed("20123456789"))
        // El recorte de extremos es deliberado: la entrada con espacios es bien formada.
        assertTrue(RucValidator.isWellFormed("  20123456789  "))

        assertFalse(RucValidator.isWellFormed(""))
        assertFalse(RucValidator.isWellFormed("   "))
        assertFalse(RucValidator.isWellFormed("2012345678"))
        assertFalse(RucValidator.isWellFormed("201234567890"))
        assertFalse(RucValidator.isWellFormed("2012345678a"))
        assertFalse(RucValidator.isWellFormed("20123 456789"))
        assertFalse(RucValidator.isWellFormed("２０１３１３１２９５５"))
        assertFalse(RucValidator.isWellFormed("٢٠١٣١٣١٢٩٥5"))
    }

    @Test
    fun `el checksum acepta los vectores construidos con el algoritmo`() {
        val bases = listOf(
            "2012345678",
            "1098765432",
            "1046415896",
            "2000000000",
            "2051234567",
        )

        bases.forEach { base ->
            val ruc = base + expectedCheckDigit(base)
            assertTrue(ruc, RucValidator.hasValidChecksum(ruc))
        }
    }

    @Test
    fun `el checksum rechaza el mismo vector con el ultimo digito alterado`() {
        val bases = listOf(
            "2012345678",
            "1098765432",
            "1046415896",
            "2000000000",
            "2051234567",
        )

        bases.forEach { base ->
            val altered = base + (expectedCheckDigit(base) + 1) % 10
            assertFalse(altered, RucValidator.hasValidChecksum(altered))
        }
    }

    @Test
    fun `un RUC publico de los ejemplos de facturacion de SUNAT supera el checksum`() {
        // 20131312955 aparece en ejemplos públicos de facturación electrónica de SUNAT; la
        // aserción se apoya en la implementación independiente de este test.
        assertTrue(RucValidator.hasValidChecksum("20131312955"))
    }

    @Test
    fun `los complementos diez y once usan los digitos especiales correctos`() {
        assertTrue(RucValidator.hasValidChecksum("20100070970"))
        assertTrue(RucValidator.hasValidChecksum("20100003351"))
        assertTrue(RucValidator.hasValidChecksum("20000000010"))
        assertTrue(RucValidator.hasValidChecksum("20000000061"))

        assertFalse(RucValidator.hasValidChecksum("20100070971"))
        assertFalse(RucValidator.hasValidChecksum("20100003350"))
    }

    @Test
    fun `el checksum exige el formato antes de evaluar`() {
        assertFalse(RucValidator.hasValidChecksum(""))
        assertFalse(RucValidator.hasValidChecksum("2012345678"))
        assertFalse(RucValidator.hasValidChecksum("2012345678a"))
    }

    /** Implementación independiente del módulo 11 SUNAT, solo para construir los vectores. */
    private fun expectedCheckDigit(base10: String): Int {
        require(base10.length == 10 && base10.all(Char::isDigit))
        val weights = listOf(5, 4, 3, 2, 7, 6, 5, 4, 3, 2)
        val sum = base10.mapIndexed { index, digit -> (digit - '0') * weights[index] }.sum()
        val complement = 11 - (sum % 11)
        return when (complement) {
            10 -> 0
            11 -> 1
            else -> complement
        }
    }
}
