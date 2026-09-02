package com.facturastock.app.domain.model

import com.facturastock.app.domain.error.DomainRuleViolation
import com.facturastock.app.domain.error.ValidationError
import java.math.BigDecimal
import java.math.RoundingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class QuantityTest {
    @Test
    fun `conserva exactamente una cantidad fraccionaria y su escala`() {
        val quantity = Quantity.of("1.2500")

        assertEquals(BigDecimal("1.2500"), quantity.value)
        assertEquals(4, quantity.scale)
        assertNotEquals(quantity, Quantity.of("1.250"))
    }

    @Test
    fun `suma cantidades fraccionarias sin conversion binaria`() {
        val result = Quantity.of("1.5") + Quantity.of("0.125")

        assertEquals(BigDecimal("1.625"), result.value)
        assertEquals(3, result.scale)
    }

    @Test
    fun `cambia escala solo con una regla de redondeo explicita`() {
        val result = Quantity.of("1.235").withScale(2, RoundingMode.HALF_UP)

        assertEquals(BigDecimal("1.24"), result.value)
    }

    @Test
    fun `rechaza cero y cantidades negativas`() {
        listOf("0", "-0.001").forEach { input ->
            val violation = assertThrows(DomainRuleViolation::class.java) {
                Quantity.of(input)
            }
            assertEquals(ValidationError.InvalidQuantity(input), violation.error)
        }
    }

    @Test
    fun `rechaza escalas patologicas con un error de dominio`() {
        val violation = assertThrows(DomainRuleViolation::class.java) {
            Quantity.of("1E-2147483647")
        }

        assertEquals(
            ValidationError.InvalidQuantity("1E-2147483647"),
            violation.error,
        )
    }

    @Test
    fun `multiplica por un factor entero conservando exactitud`() {
        val result = Quantity.of("1") * BigDecimal("12")

        assertEquals(BigDecimal("12"), result.value)
    }

    @Test
    fun `multiplica por un factor fraccionario conservando la escala resultante`() {
        val result = Quantity.of("1.20") * BigDecimal("1.5")

        assertEquals(BigDecimal("1.800"), result.value)
    }

    @Test
    fun `rechaza factores nulos o negativos al multiplicar`() {
        listOf(BigDecimal.ZERO, BigDecimal("-2")).forEach { factor ->
            assertThrows(DomainRuleViolation::class.java) {
                Quantity.of("1") * factor
            }
        }
    }
}
