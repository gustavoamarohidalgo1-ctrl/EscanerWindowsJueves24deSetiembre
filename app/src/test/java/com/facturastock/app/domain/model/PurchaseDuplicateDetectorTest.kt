package com.facturastock.app.domain.model

import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.model.id.SupplierId
import java.time.LocalDate
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PurchaseDuplicateDetectorTest {
    private val detector = PurchaseDuplicateDetector()

    @Test
    fun `espacios guiones y mayusculas no evitan coincidencia exacta`() {
        val probe = probe(
            supplierRuc = "20-123 456 789",
            documentSeries = " f-001 ",
            documentNumber = " 000-123 ",
        )
        val candidate = purchase(
            supplierRuc = "20123456789",
            series = "F001",
            number = "000123",
        )

        val result = detector.assess(probe, listOf(candidate))

        assertEquals(PurchaseDuplicateKind.EXACT, result.kind)
        assertEquals(candidate.purchaseId, result.match?.purchase?.purchaseId)
        assertTrue(PurchaseDuplicateReason.SAME_DOCUMENT_NUMBER in result.match!!.reasons)
    }

    @Test
    fun `correlativo con padding diferente es probable y no exacto`() {
        val probe = probe(documentNumber = "123")
        val candidate = purchase(series = "F001", number = "000123")

        val result = detector.assess(probe, listOf(candidate))

        assertEquals(PurchaseDuplicateKind.PROBABLE, result.kind)
        assertTrue(
            PurchaseDuplicateReason.CORRELATIVE_PADDING_VARIANT in requireNotNull(result.match).reasons,
        )
    }

    @Test
    fun `serie fecha y total iguales son senales secundarias de probable`() {
        val probe = probe(documentNumber = "999")
        val candidate = purchase(series = "F001", number = "777")

        val result = detector.assess(probe, listOf(candidate))

        assertEquals(PurchaseDuplicateKind.PROBABLE, result.kind)
        val match = requireNotNull(result.match)
        assertTrue(PurchaseDuplicateReason.SAME_ISSUE_DATE in match.reasons)
        assertTrue(PurchaseDuplicateReason.SAME_TOTAL in match.reasons)
    }

    @Test
    fun `fecha y total solos no convierten otro documento en probable`() {
        val result = detector.assess(
            probe(documentSeries = "F001", documentNumber = "999"),
            listOf(purchase(series = "B002", number = "777")),
        )

        assertEquals(PurchaseDuplicateKind.DISTINCT, result.kind)
        assertNull(result.match)
    }

    @Test
    fun `hash de imagen por si solo nunca clasifica duplicado`() {
        val probe = probe(
            documentNumber = "999",
            issueDate = LocalDate.of(2026, 8, 13),
            totalMinor = 9_999,
            imageHashes = setOf(HASH),
        )
        val candidate = purchase(
            series = "B002",
            number = "777",
            issueDate = LocalDate.of(2026, 8, 12),
            totalMinor = 1_000,
            imageHashes = setOf(HASH),
        )

        val result = detector.assess(probe, listOf(candidate))

        assertEquals(PurchaseDuplicateKind.DISTINCT, result.kind)
        assertNull(result.match)
    }

    @Test
    fun `otro proveedor tipo o negocio permanece distinto`() {
        val candidates = listOf(
            purchase(
                businessId = BusinessId.from(uuid(90)),
                series = "F001",
                number = "000123",
            ),
            purchase(
                supplierId = SupplierId.from(uuid(91)),
                supplierRuc = "20999999999",
                series = "F001",
                number = "000123",
            ),
            purchase(
                documentType = PurchaseDocumentType.SALES_RECEIPT,
                series = "F001",
                number = "000123",
            ),
        )

        candidates.forEach { candidate ->
            val result = detector.assess(probe(), listOf(candidate))
            assertEquals(PurchaseDuplicateKind.DISTINCT, result.kind)
        }
    }

    private fun probe(
        supplierRuc: String = "20123456789",
        documentSeries: String = "F001",
        documentNumber: String = "000123",
        issueDate: LocalDate = DATE,
        totalMinor: Long = 1_000,
        imageHashes: Set<String> = emptySet(),
    ): PurchaseDuplicateProbe = PurchaseDuplicateProbe(
        draftId = DRAFT_ID,
        preparedLogicalHash = "a".repeat(64),
        businessId = BUSINESS_ID,
        supplierId = null,
        supplierRuc = supplierRuc,
        documentType = PurchaseDocumentType.INVOICE,
        documentSeries = documentSeries,
        documentNumber = documentNumber,
        issueDate = issueDate,
        currency = PEN,
        total = Money.ofMinor(totalMinor, PEN),
        imageHashes = imageHashes,
    )

    private fun purchase(
        businessId: BusinessId = BUSINESS_ID,
        supplierId: SupplierId = SUPPLIER_ID,
        supplierRuc: String = "20123456789",
        documentType: PurchaseDocumentType = PurchaseDocumentType.INVOICE,
        series: String,
        number: String,
        issueDate: LocalDate = DATE,
        totalMinor: Long = 1_000,
        imageHashes: Set<String> = emptySet(),
    ): RecordedPurchase = RecordedPurchase(
        purchaseId = PURCHASE_ID,
        businessId = businessId,
        sourceDraftId = OLD_DRAFT_ID,
        supplierId = supplierId,
        supplierRuc = supplierRuc,
        supplierLegalName = "Proveedor SAC",
        documentType = documentType,
        documentSeries = series,
        documentNumber = number,
        issueDate = issueDate,
        currency = PEN,
        total = Money.ofMinor(totalMinor, PEN),
        status = PurchaseStatus.POSTED,
        imageHashes = imageHashes,
    )

    private companion object {
        val PEN = CurrencyCode.of("PEN")
        val DATE: LocalDate = LocalDate.of(2026, 8, 12)
        val BUSINESS_ID: BusinessId = BusinessId.from(uuid(1))
        val DRAFT_ID: DraftId = DraftId.from(uuid(2))
        val OLD_DRAFT_ID: DraftId = DraftId.from(uuid(3))
        val SUPPLIER_ID: SupplierId = SupplierId.from(uuid(4))
        val PURCHASE_ID: PurchaseId = PurchaseId.from(uuid(5))
        const val HASH = "a234567890123456789012345678901234567890123456789012345678901234"

        fun uuid(seed: Int): UUID =
            UUID.fromString("00000000-0000-0000-0000-%012d".format(seed))
    }
}
