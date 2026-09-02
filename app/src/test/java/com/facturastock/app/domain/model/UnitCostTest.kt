package com.facturastock.app.domain.model

import com.facturastock.app.domain.error.DomainRuleViolation
import com.facturastock.app.domain.error.ValidationError
import java.math.BigDecimal
import java.math.RoundingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UnitCostTest {
    private val pen = CurrencyCode.of("PEN")

    @Test
    fun `conserva exactamente el costo unitario y su escala`() {
        val cost = UnitCost.of("0.333300", pen)

        assertEquals(BigDecimal("0.333300"), cost.amount)
        assertEquals(6, cost.scale)
    }

    @Test
    fun `calcula el total fraccionario antes de redondear a dinero`() {
        val total = UnitCost.of("2.3456", pen).totalFor(
            quantity = Quantity.of("1.5"),
            roundingMode = RoundingMode.HALF_UP,
        )

        assertEquals(352L, total.minorUnits)
        assertEquals(BigDecimal("3.52"), total.toMajor())
    }

    @Test
    fun `produce un total exacto cuando no hace falta redondear`() {
        val total = UnitCost.of("12.40", pen).totalFor(
            quantity = Quantity.of("1.25"),
            roundingMode = RoundingMode.UNNECESSARY,
        )

        assertEquals(1_550L, total.minorUnits)
        assertEquals(BigDecimal("15.50"), total.toMajor())
    }

    @Test
    fun `respeta diferentes modos al reducir a unidades menores`() {
        val cost = UnitCost.of("0.335", pen)
        val quantity = Quantity.of("3")

        assertEquals(101L, cost.totalFor(quantity, RoundingMode.HALF_UP).minorUnits)
        assertEquals(100L, cost.totalFor(quantity, RoundingMode.HALF_EVEN).minorUnits)
    }

    @Test
    fun `no pierde escala de forma silenciosa`() {
        val violation = assertThrows(DomainRuleViolation::class.java) {
            UnitCost.of("1.234", pen).withScale(2, RoundingMode.UNNECESSARY)
        }

        assertEquals(
            ValidationError.DecimalScaleLoss(value = "1.234", targetScale = 2),
            violation.error,
        )
    }

    @Test
    fun `limita precision y escala antes de multiplicar`() {
        val excessiveScale = assertThrows(DomainRuleViolation::class.java) {
            UnitCost.of("0.0000000000000000001", pen)
        }
        val excessivePrecision = assertThrows(DomainRuleViolation::class.java) {
            UnitCost.of("123456789012345678901234567890123456789", pen)
        }

        assertEquals(
            ValidationError.InvalidUnitCost("0.0000000000000000001"),
            excessiveScale.error,
        )
        assertEquals(
            ValidationError.InvalidUnitCost("123456789012345678901234567890123456789"),
            excessivePrecision.error,
        )
    }
}
