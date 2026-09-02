package com.facturastock.app.data.local.codec

import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.InventoryTaxEvidence
import com.facturastock.app.domain.model.InventoryTaxTreatment
import com.facturastock.app.domain.model.PreparedPurchase
import com.facturastock.app.domain.model.PreparedPurchaseLine
import com.facturastock.app.domain.model.PurchaseDocumentType
import com.facturastock.app.domain.model.PurchaseProductProvenance
import com.facturastock.app.domain.model.PurchaseReconciliationAdjustment
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.StagedPurchaseProduct
import com.facturastock.app.domain.model.UnitCost
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.SupplierId
import com.facturastock.app.domain.model.id.UnitId
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparedPurchaseCodecTest {
    @Test
    fun `round trip conserva todos los campos con exactitud`() {
        val purchase = purchase()

        val decoded = PreparedPurchaseCodec.decode(PreparedPurchaseCodec.encode(purchase))

        assertEquals(purchase, decoded)
    }

    @Test
    fun `la codificacion es determinista`() {
        val purchase = purchase()

        assertArrayEquals(
            PreparedPurchaseCodec.encode(purchase),
            PreparedPurchaseCodec.encode(purchase),
        )
    }

    @Test
    fun `round trip conserva precio de venta del producto staged`() {
        val current = purchase()
        val baseLine = current.lines.last()
        val staged = StagedPurchaseProduct(
            productId = baseLine.productId,
            businessId = current.businessId,
            unitId = baseLine.unitId,
            name = "Aceite staged",
            salePrice = Money.ofMinor(850L, current.currency),
        )
        val lines = current.lines.dropLast(1) + baseLine.copy(
            productProvenance = PurchaseProductProvenance.CREATED_IN_DRAFT,
            stagedProduct = staged,
        )
        val withStaged = current.copy(
            lines = lines,
            logicalHash = PreparedPurchase.logicalHash(
                draftId = current.draftId,
                businessId = current.businessId,
                supplierId = current.supplierId,
                supplierRuc = current.supplierRuc,
                supplierLegalName = current.supplierLegalName,
                documentType = current.documentType,
                documentNumber = current.documentNumber,
                issueDate = current.issueDate,
                currency = current.currency,
                lines = lines,
                subtotal = current.subtotal,
                tax = current.tax,
                otherCharges = current.otherCharges,
                total = current.total,
                acceptedWarnings = current.acceptedWarnings,
                reconciliationAdjustment = current.reconciliationAdjustment,
            ),
        )

        val decoded = PreparedPurchaseCodec.decode(PreparedPurchaseCodec.encode(withStaged))

        assertEquals(withStaged, decoded)
        assertEquals(Money.ofMinor(850L, current.currency), decoded.lines.last().stagedProduct?.salePrice)
    }

    @Test
    fun `rechaza payload truncado y datos sobrantes`() {
        val payload = PreparedPurchaseCodec.encode(purchase())

        assertThrows(IOException::class.java) {
            PreparedPurchaseCodec.decode(payload.copyOf(payload.size - 4))
        }
        assertThrows(IOException::class.java) {
            PreparedPurchaseCodec.decode(payload + byteArrayOf(0))
        }
    }

    @Test
    fun `rechaza version desconocida`() {
        val payload = PreparedPurchaseCodec.encode(purchase())
        // La versión ocupa los bytes 4..7 tras el MAGIC.
        payload[7] = (PreparedPurchaseCodec.VERSION + 1).toByte()

        assertThrows(IOException::class.java) { PreparedPurchaseCodec.decode(payload) }
    }

    @Test
    fun `sha256 es estable y en minusculas`() {
        val payload = PreparedPurchaseCodec.encode(purchase())

        val hash = PreparedPurchaseCodec.sha256(payload)

        assertEquals(64, hash.length)
        assertEquals(hash, PreparedPurchaseCodec.sha256(payload))
        assertEquals(hash, hash.lowercase())
    }

    @Test
    fun `payloads v2 y v3 conservan datos y quedan UNKNOWN para revision explicita`() {
        (2..3).forEach { version ->
            val expected = legacyPurchase(version)
            val payload = LegacyCodecPayloadFixtures.preparedLegacy(
                currentPayload = PreparedPurchaseCodec.encode(expected),
                targetVersion = version,
            )

            val decoded = PreparedPurchaseCodec.decode(payload)

            assertEquals(expected, decoded)
            assertTrue(PreparedPurchaseCodec.supports(version))
            assertTrue(decoded.lines.all {
                it.taxTreatment == InventoryTaxTreatment.UNKNOWN &&
                    it.taxEvidence == InventoryTaxEvidence.None &&
                    it.productProvenance == PurchaseProductProvenance.UNKNOWN_LEGACY &&
                    it.stagedProduct == null
            })
        }
    }

    @Test
    fun `payload v4 conserva decisiones y valida su hash delimitado historico`() {
        val current = purchase()
        val expected = current.copy(
            logicalHash = PreparedPurchase.legacyV4LogicalHash(
                draftId = current.draftId,
                businessId = current.businessId,
                supplierId = current.supplierId,
                supplierRuc = current.supplierRuc,
                supplierLegalName = current.supplierLegalName,
                documentType = current.documentType,
                documentNumber = current.documentNumber,
                issueDate = current.issueDate,
                currency = current.currency,
                lines = current.lines,
                subtotal = current.subtotal,
                tax = current.tax,
                otherCharges = current.otherCharges,
                total = current.total,
                acceptedWarnings = current.acceptedWarnings,
                reconciliationAdjustment = current.reconciliationAdjustment,
            ),
        )
        val payload = LegacyCodecPayloadFixtures.preparedV4(
            PreparedPurchaseCodec.encode(expected),
        )

        assertEquals(expected, PreparedPurchaseCodec.decode(payload))
        assertTrue(PreparedPurchaseCodec.supports(4))
    }

    @Test
    fun `payload v5 conserva producto staged sin inventar precio de venta`() {
        val current = purchase()
        val baseLine = current.lines.last()
        val staged = StagedPurchaseProduct(
            productId = baseLine.productId,
            businessId = current.businessId,
            unitId = baseLine.unitId,
            name = "Aceite staged legacy",
        )
        val lines = current.lines.dropLast(1) + baseLine.copy(
            productProvenance = PurchaseProductProvenance.CREATED_IN_DRAFT,
            stagedProduct = staged,
        )
        val expected = current.copy(
            lines = lines,
            logicalHash = PreparedPurchase.legacyV5LogicalHash(
                draftId = current.draftId,
                businessId = current.businessId,
                supplierId = current.supplierId,
                supplierRuc = current.supplierRuc,
                supplierLegalName = current.supplierLegalName,
                documentType = current.documentType,
                documentNumber = current.documentNumber,
                issueDate = current.issueDate,
                currency = current.currency,
                lines = lines,
                subtotal = current.subtotal,
                tax = current.tax,
                otherCharges = current.otherCharges,
                total = current.total,
                acceptedWarnings = current.acceptedWarnings,
                reconciliationAdjustment = current.reconciliationAdjustment,
            ),
        )
        val payload = LegacyCodecPayloadFixtures.preparedV5(
            PreparedPurchaseCodec.encode(expected),
        )

        val decoded = PreparedPurchaseCodec.decode(payload)

        assertEquals(expected, decoded)
        assertEquals(null, decoded.lines.last().stagedProduct?.salePrice)
        assertTrue(PreparedPurchaseCodec.supports(5))
    }

    private fun legacyPurchase(version: Int): PreparedPurchase {
        val current = purchase()
        val lines = current.lines.map { line ->
            line.copy(
                taxTreatment = InventoryTaxTreatment.UNKNOWN,
                taxEvidence = InventoryTaxEvidence.None,
                productProvenance = PurchaseProductProvenance.UNKNOWN_LEGACY,
                stagedProduct = null,
            )
        }
        val reconciliation = current.reconciliationAdjustment.takeIf { version >= 3 }
        val hash = PreparedPurchase.legacyLogicalHash(
            draftId = current.draftId,
            businessId = current.businessId,
            supplierId = current.supplierId,
            supplierRuc = current.supplierRuc,
            supplierLegalName = current.supplierLegalName,
            documentType = current.documentType,
            documentNumber = current.documentNumber,
            issueDate = current.issueDate,
            currency = current.currency,
            lines = lines,
            subtotal = current.subtotal,
            tax = current.tax,
            otherCharges = current.otherCharges,
            total = current.total,
            acceptedWarnings = current.acceptedWarnings,
            reconciliationAdjustment = reconciliation,
        )
        return current.copy(
            lines = lines,
            logicalHash = hash,
            reconciliationAdjustment = reconciliation,
        )
    }

    private fun purchase(): PreparedPurchase {
        val pen = CurrencyCode.of("PEN")
        val lines = listOf(
            PreparedPurchaseLine(
                lineId = LineId.from(uuid(10)),
                position = 0,
                productId = ProductId.from(uuid(4)),
                unitId = UnitId.from(uuid(3)),
                description = "Arroz costeño × 5kg",
                rawText = "2.5 ARROZ COSTENO X 5KG 4.00",
                quantity = Quantity.of("2.5"),
                unitCost = UnitCost.of("4.00", pen),
                discount = Money.ofMinor(50, pen),
                tax = Money.ofMinor(153, pen),
                lineTotal = Money.ofMinor(1_103, pen),
                linkConfidence = 950,
                taxTreatment = InventoryTaxTreatment.INCLUDED,
                taxEvidence = InventoryTaxEvidence.ExplicitAmount(java.math.BigDecimal("1.53")),
                productProvenance = PurchaseProductProvenance.EXISTING,
            ),
            PreparedPurchaseLine(
                lineId = LineId.from(uuid(11)),
                position = 1,
                productId = ProductId.from(uuid(5)),
                unitId = UnitId.from(uuid(3)),
                description = "Aceite",
                quantity = Quantity.of("1"),
            ),
        )
        val adjustment = PurchaseReconciliationAdjustment(
            amount = Money.ofMinor(3, pen),
            reason = "Ajuste sintético documentado para la prueba",
        )
        return PreparedPurchase(
            draftId = DraftId.from(uuid(2)),
            businessId = BusinessId.from(uuid(1)),
            supplierId = SupplierId.from(uuid(6)),
            supplierRuc = "20123456789",
            supplierLegalName = "Proveedor SA",
            documentType = PurchaseDocumentType.INVOICE,
            documentNumber = "F001-123",
            issueDate = LocalDate.of(2026, 8, 8),
            currency = pen,
            lines = lines,
            subtotal = Money.ofMinor(847, pen),
            tax = Money.ofMinor(153, pen),
            otherCharges = null,
            total = Money.ofMinor(1_000, pen),
            acceptedWarnings = listOf("TOTAL_DIFFERENCE"),
            logicalHash = PreparedPurchase.logicalHash(
                draftId = DraftId.from(uuid(2)),
                businessId = BusinessId.from(uuid(1)),
                supplierId = SupplierId.from(uuid(6)),
                supplierRuc = "20123456789",
                supplierLegalName = "Proveedor SA",
                documentType = PurchaseDocumentType.INVOICE,
                documentNumber = "F001-123",
                issueDate = LocalDate.of(2026, 8, 8),
                currency = pen,
                lines = lines,
                subtotal = Money.ofMinor(847, pen),
                tax = Money.ofMinor(153, pen),
                otherCharges = null,
                total = Money.ofMinor(1_000, pen),
                acceptedWarnings = listOf("TOTAL_DIFFERENCE"),
                reconciliationAdjustment = adjustment,
            ),
            preparedAt = Instant.parse("2026-08-08T12:00:00Z"),
            reconciliationAdjustment = adjustment,
        )
    }

    private companion object {
        fun uuid(seed: Int): UUID =
            UUID.fromString("00000000-0000-0000-0000-%012d".format(seed))
    }
}
