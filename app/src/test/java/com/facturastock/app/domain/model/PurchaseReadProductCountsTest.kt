package com.facturastock.app.domain.model

import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.PurchaseId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PurchaseReadProductCountsTest {
    @Test
    fun `legacy summary keeps every product unknown instead of inventing provenance`() {
        val summary = summary(lineCount = 3, productCount = 2)

        assertEquals(0, summary.createdProductCount)
        assertEquals(0, summary.existingProductCount)
        assertEquals(2, summary.unknownProductCount)
    }

    @Test
    fun `summary requires provenance buckets to partition distinct products`() {
        assertThrows(IllegalArgumentException::class.java) {
            summary(
                lineCount = 3,
                productCount = 2,
                createdProductCount = 1,
                existingProductCount = 0,
                unknownProductCount = 0,
            )
        }
    }

    private fun summary(
        lineCount: Int,
        productCount: Int,
        createdProductCount: Int = 0,
        existingProductCount: Int = 0,
        unknownProductCount: Int = productCount,
    ): PurchaseReadSummary = PurchaseReadSummary(
        purchaseId = PurchaseId.from(uuid(1)),
        businessId = BusinessId.from(uuid(2)),
        sourceDraftId = DraftId.from(uuid(3)),
        supplierRuc = "20123456789",
        supplierLegalName = "Proveedor local",
        documentType = PurchaseDocumentType.INVOICE,
        documentSeries = "F001",
        documentNumber = "1",
        issueDate = LocalDate.of(2026, 8, 21),
        currency = CurrencyCode.of("PEN"),
        total = Money.ofMinor(100L, CurrencyCode.of("PEN")),
        status = PurchaseStatus.POSTED,
        syncState = PurchaseSyncState.PENDING_SYNC,
        lineCount = lineCount,
        productCount = productCount,
        postedAt = Instant.parse("2026-08-21T12:00:00Z"),
        createdProductCount = createdProductCount,
        existingProductCount = existingProductCount,
        unknownProductCount = unknownProductCount,
    )

    private fun uuid(seed: Int): UUID =
        UUID.fromString("00000000-0000-0000-0000-%012d".format(seed))
}
