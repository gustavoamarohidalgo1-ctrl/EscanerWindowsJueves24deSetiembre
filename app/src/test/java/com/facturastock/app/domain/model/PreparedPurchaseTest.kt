package com.facturastock.app.domain.model

import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.UnitId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparedPurchaseTest {
    private val pen = CurrencyCode.of("PEN")

    @Test
    fun `hash logico identico para el mismo contenido aunque cambie el timestamp`() {
        val first = purchase(preparedAt = Instant.parse("2026-08-08T12:00:00Z"))
        val second = purchase(preparedAt = Instant.parse("2026-08-09T18:30:00Z"))

        assertEquals(first.logicalHash, second.logicalHash)
    }

    @Test
    fun `hash logico cambia cuando cambia cualquier dato relevante`() {
        val base = purchase()
        val otraCantidad = purchase(
            lines = listOf(line().copy(quantity = Quantity.of("3"))),
        )
        val otroTotal = purchase(total = Money.ofMinor(1_200, pen))
        val otraAdvertencia = purchase(warnings = listOf("TOTAL_DIFFERENCE"))
        val otroMotivo = purchase(
            adjustment = PurchaseReconciliationAdjustment(
                Money.ofMinor(3, pen),
                "Motivo sintético que cambia el contenido lógico",
            ),
        )

        assertNotEquals(base.logicalHash, otraCantidad.logicalHash)
        assertNotEquals(base.logicalHash, otroTotal.logicalHash)
        assertNotEquals(base.logicalHash, otraAdvertencia.logicalHash)
        assertNotEquals(base.logicalHash, otroMotivo.logicalHash)
    }

    @Test
    fun `hash logico distingue texto libre que contiene antiguos separadores`() {
        val separatorInRawText = purchase(
            lines = listOf(line().copy(rawText = "a|b", description = "c")),
        )
        val separatorInDescription = purchase(
            lines = listOf(line().copy(rawText = "a", description = "b|c")),
        )

        assertNotEquals(separatorInRawText.logicalHash, separatorInDescription.logicalHash)

        val separatorInsideWarning = purchase(warnings = listOf("A,B"))
        val twoWarnings = purchase(warnings = listOf("A", "B"))
        assertNotEquals(separatorInsideWarning.logicalHash, twoWarnings.logicalHash)
    }

    @Test
    fun `exige al menos una linea y hash sha256`() {
        assertThrows(IllegalArgumentException::class.java) { purchase(lines = emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { purchase(hash = "no-es-sha") }
    }

    @Test
    fun `las posiciones son correlativas desde cero y ninguna linea se repite`() {
        assertThrows(IllegalArgumentException::class.java) {
            purchase(lines = listOf(line().copy(position = 2)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            purchase(lines = listOf(line(), line().copy(position = 1)))
        }
    }

    @Test
    fun `una linea congelada necesita descripcion y confianza dentro del rango`() {
        assertThrows(IllegalArgumentException::class.java) { line().copy(description = "   ") }
        assertThrows(IllegalArgumentException::class.java) { line().copy(position = -1) }
        assertThrows(IllegalArgumentException::class.java) { line().copy(linkConfidence = 1_001) }
    }

    @Test
    fun `ningun importe puede escapar de la moneda del documento`() {
        val usd = CurrencyCode.of("USD")

        assertThrows(IllegalArgumentException::class.java) { purchase(total = Money.ofMinor(1_000, usd)) }
        assertThrows(IllegalArgumentException::class.java) {
            purchase(lines = listOf(line().copy(lineTotal = Money.ofMinor(1_000, usd))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            purchase(lines = listOf(line().copy(unitCost = UnitCost.of("5.00", usd))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            purchase(
                adjustment = PurchaseReconciliationAdjustment(
                    Money.ofMinor(3, usd),
                    "Motivo sintético del ajuste de conciliación",
                ),
            )
        }
    }

    @Test
    fun `la marca de tiempo de preparacion no puede ser anterior al epoch`() {
        assertThrows(IllegalArgumentException::class.java) {
            purchase(preparedAt = Instant.EPOCH.minusMillis(1))
        }
    }

    @Test
    fun `un ajuste aceptado exige monto no nulo y motivo escrito`() {
        assertThrows(IllegalArgumentException::class.java) {
            PurchaseReconciliationAdjustment(Money.ofMinor(0, pen), "Motivo suficientemente largo")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PurchaseReconciliationAdjustment(Money.ofMinor(3, pen), "corto")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PurchaseReconciliationAdjustment(Money.ofMinor(3, pen), " Motivo con espacios extremos ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PurchaseReconciliationAdjustment(
                Money.ofMinor(3, pen),
                "M".repeat(PurchaseReconciliationAdjustment.MAX_REASON_LENGTH + 1),
            )
        }
    }

    @Test
    fun `solo un motivo de longitud suficiente habilita la aceptacion del redondeo`() {
        assertFalse(PurchaseReconciliationAdjustment.isValidReason(null))
        assertFalse(PurchaseReconciliationAdjustment.isValidReason("   "))
        assertFalse(PurchaseReconciliationAdjustment.isValidReason("corto"))
        assertTrue(PurchaseReconciliationAdjustment.isValidReason("  Diferencia de redondeo aceptada  "))
    }

    private fun purchase(
        lines: List<PreparedPurchaseLine> = listOf(line()),
        total: Money = Money.ofMinor(1_000, pen),
        warnings: List<String> = emptyList(),
        adjustment: PurchaseReconciliationAdjustment? = null,
        hash: String? = null,
        preparedAt: Instant = Instant.parse("2026-08-08T12:00:00Z"),
    ): PreparedPurchase {
        val computedHash = hash ?: PreparedPurchase.logicalHash(
            draftId = DraftId.from(uuid(2)),
            businessId = BusinessId.from(uuid(1)),
            supplierId = null,
            supplierRuc = "20123456789",
            supplierLegalName = "PROVEEDOR SA",
            documentType = PurchaseDocumentType.INVOICE,
            documentNumber = "F001-123",
            issueDate = LocalDate.of(2026, 8, 8),
            currency = pen,
            lines = lines,
            subtotal = Money.ofMinor(847, pen),
            tax = Money.ofMinor(153, pen),
            otherCharges = null,
            total = total,
            acceptedWarnings = warnings,
            reconciliationAdjustment = adjustment,
        )
        return PreparedPurchase(
            draftId = DraftId.from(uuid(2)),
            businessId = BusinessId.from(uuid(1)),
            supplierId = null,
            supplierRuc = "20123456789",
            supplierLegalName = "PROVEEDOR SA",
            documentType = PurchaseDocumentType.INVOICE,
            documentNumber = "F001-123",
            issueDate = LocalDate.of(2026, 8, 8),
            currency = pen,
            lines = lines,
            subtotal = Money.ofMinor(847, pen),
            tax = Money.ofMinor(153, pen),
            otherCharges = null,
            total = total,
            acceptedWarnings = warnings,
            logicalHash = computedHash,
            preparedAt = preparedAt,
            reconciliationAdjustment = adjustment,
        )
    }

    private fun line(): PreparedPurchaseLine = PreparedPurchaseLine(
        lineId = LineId.from(uuid(10)),
        position = 0,
        productId = ProductId.from(uuid(4)),
        unitId = UnitId.from(uuid(3)),
        description = "Arroz",
        quantity = Quantity.of("2"),
        unitCost = UnitCost.of("5.00", pen),
        lineTotal = Money.ofMinor(1_000, pen),
        linkConfidence = 1_000,
    )

    private companion object {
        fun uuid(seed: Int): UUID =
            UUID.fromString("00000000-0000-0000-0000-%012d".format(seed))
    }
}
