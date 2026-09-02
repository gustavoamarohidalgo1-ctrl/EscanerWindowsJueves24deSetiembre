package com.facturastock.app.data.local.entity

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class InvoiceLineEntityTest {
    private val businessId = "123e4567-e89b-42d3-a456-426614174000"
    private val draftId = "223e4567-e89b-42d3-a456-426614174000"
    private val lineId = "423e4567-e89b-42d3-a456-426614174000"

    private fun line(
        quantity: String? = "1.500",
        unitCost: String? = "12.3400",
        unitCostCurrency: String? = "PEN",
        lineTotalMinorUnits: Long? = 1_851L,
    ) = InvoiceLineEntity(
        lineId = lineId,
        draftId = draftId,
        businessId = businessId,
        position = 0,
        descriptionRaw = "ARROZ EXTRA COSTEÑO X 50 KG",
        createdAt = 1_000L,
        updatedAt = 1_000L,
        quantity = quantity,
        unitCost = unitCost,
        unitCostCurrency = unitCostCurrency,
        lineTotalMinorUnits = lineTotalMinorUnits,
        ocrConfidence = 998,
    )

    @Test
    fun `cantidad y costo unitario conservan exactamente valor y escala`() {
        val entity = line()

        assertEquals("1.500", entity.quantity)
        assertEquals(3, BigDecimal(entity.quantity).scale())
        assertEquals("12.3400", entity.unitCost)
        assertEquals(4, BigDecimal(entity.unitCost).scale())
        assertEquals(0, BigDecimal(entity.unitCost).compareTo(BigDecimal("12.34")))
    }

    @Test
    fun `admite la máxima precisión y escala de la política decimal`() {
        val entity = line(quantity = "99999999999999999999.999999999999999999")

        assertEquals(38, BigDecimal(entity.quantity).precision())
        assertEquals(18, BigDecimal(entity.quantity).scale())
    }

    @Test
    fun `rechaza cantidad cero y costo con formato no plano`() {
        assertThrows(IllegalArgumentException::class.java) {
            line(quantity = "0.00")
        }
        assertThrows(IllegalArgumentException::class.java) {
            line(unitCost = "1E2")
        }
    }

    @Test
    fun `moneda acompana cualquier importe y admite total sin costo resuelto`() {
        assertEquals(
            1_851L,
            line(unitCost = null, lineTotalMinorUnits = 1_851L).lineTotalMinorUnits,
        )
        assertThrows(IllegalArgumentException::class.java) {
            line(unitCost = null, lineTotalMinorUnits = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            line(unitCostCurrency = null)
        }
    }

    @Test
    fun `total firmado conserva notas de credito y ajustes no admiten signo negativo`() {
        assertEquals(-1_851L, line().copy(lineTotalMinorUnits = -1_851L).lineTotalMinorUnits)
        assertThrows(IllegalArgumentException::class.java) {
            line().copy(discountMinorUnits = -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            line().copy(taxMinorUnits = -1L)
        }
    }

    @Test
    fun `la confianza OCR se limita a la escala 0 a 1000`() {
        assertThrows(IllegalArgumentException::class.java) {
            line().copy(ocrConfidence = 1001)
        }
    }

    @Test
    fun `los enlaces a producto y unidad son opcionales y nulos por defecto`() {
        val entity = line()

        assertEquals(null, entity.productId)
        assertEquals(null, entity.unitId)
        assertEquals(null, entity.linkConfidence)
    }
}
