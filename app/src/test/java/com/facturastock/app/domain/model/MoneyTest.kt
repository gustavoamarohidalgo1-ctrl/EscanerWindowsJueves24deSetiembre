package com.facturastock.app.domain.model

import com.facturastock.app.domain.error.DomainRuleViolation
import com.facturastock.app.domain.error.ValidationError
import java.math.BigDecimal
import java.math.RoundingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MoneyTest {
    private val pen = CurrencyCode.of("PEN")

    @Test
    fun `suma unidades menores exactamente en la misma moneda`() {
        val result = Money.ofMinor(1_234L, pen) + Money.ofMinor(66L, pen)

        assertEquals(1_300L, result.minorUnits)
        assertEquals(BigDecimal("13.00"), result.toMajor())
        assertEquals(pen, result.currency)
    }

    @Test
    fun `rechaza la suma de monedas diferentes`() {
        val usd = CurrencyCode.of("USD")

        val violation = assertThrows(DomainRuleViolation::class.java) {
            Money.ofMinor(100L, pen) + Money.ofMinor(100L, usd)
        }

        assertEquals(
            ValidationError.CurrencyMismatch(expected = pen, actual = usd),
            violation.error,
        )
    }

    @Test
    fun `detecta overflow sin envolver el Long`() {
        val violation = assertThrows(DomainRuleViolation::class.java) {
            Money.ofMinor(Long.MAX_VALUE, pen) + Money.ofMinor(1L, pen)
        }

        assertEquals(
            ValidationError.ArithmeticOverflow("money.add"),
            violation.error,
        )
    }

    @Test
    fun `detecta underflow y montos mayores al rango Long`() {
        val subtraction = assertThrows(DomainRuleViolation::class.java) {
            Money.ofMinor(Long.MIN_VALUE, pen) - Money.ofMinor(1L, pen)
        }
        val conversion = assertThrows(DomainRuleViolation::class.java) {
            Money.fromMajor("92233720368547758.08", pen)
        }

        assertEquals(
            ValidationError.ArithmeticOverflow("money.subtract"),
            subtraction.error,
        )
        assertEquals(
            ValidationError.ArithmeticOverflow("money.fromMajor"),
            conversion.error,
        )
    }

    @Test
    fun `usa la escala ISO 4217 de cada moneda`() {
        val cases = listOf(
            Triple("10.25", "PEN", 1_025L),
            Triple("10", "JPY", 10L),
            Triple("10.125", "KWD", 10_125L),
        )

        cases.forEach { (major, code, expectedMinor) ->
            val money = Money.fromMajor(major, CurrencyCode.of(code))
            assertEquals(code, expectedMinor, money.minorUnits)
        }
    }

    @Test
    fun `redondea solo con el modo solicitado`() {
        assertEquals(
            101L,
            Money.fromMajor("1.005", pen, RoundingMode.HALF_UP).minorUnits,
        )
        assertEquals(
            100L,
            Money.fromMajor("1.005", pen, RoundingMode.HALF_EVEN).minorUnits,
        )
        assertEquals(
            -101L,
            Money.fromMajor("-1.005", pen, RoundingMode.HALF_UP).minorUnits,
        )
    }

    @Test
    fun `impide perder escala cuando no se autoriza redondeo`() {
        val violation = assertThrows(DomainRuleViolation::class.java) {
            Money.fromMajor("1.001", pen)
        }

        assertEquals(
            ValidationError.DecimalScaleLoss(value = "1.001", targetScale = 2),
            violation.error,
        )
    }
}
