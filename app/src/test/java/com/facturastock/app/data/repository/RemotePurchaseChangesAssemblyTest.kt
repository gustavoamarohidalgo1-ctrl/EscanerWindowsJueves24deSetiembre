package com.facturastock.app.data.repository

import com.facturastock.app.data.local.entity.RemoteMovementSummaryEntity
import com.facturastock.app.data.local.entity.RemotePurchaseChangeEntity
import com.facturastock.app.domain.model.PurchaseStatus
import com.facturastock.app.domain.model.StockMovementType
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePurchaseChangesAssemblyTest {
    @Test
    fun `bulk rows preserve header and movement order without cross tenant leakage`() {
        val headers = listOf(
            header(seq = 1L, purchaseId = PURCHASE_ONE),
            header(seq = 2L, purchaseId = PURCHASE_TWO),
        )
        val movements = listOf(
            movement(seq = 1L, position = 0, productId = PRODUCT_ONE, quantity = "1.250"),
            movement(seq = 1L, position = 1, productId = PRODUCT_TWO, quantity = "2.000"),
            movement(
                seq = 1L,
                position = 0,
                productId = FOREIGN_PRODUCT,
                quantity = "99.000",
                businessId = FOREIGN_BUSINESS,
            ),
        )

        val result = remotePurchaseChangesFromRows(headers, movements)

        assertEquals(listOf(1L, 2L), result.map { change -> change.seq })
        assertEquals(
            listOf(PRODUCT_ONE, PRODUCT_TWO),
            result.first().movementSummary.map { movement -> movement.productId },
        )
        assertEquals(BigDecimal("1.250"), result.first().movementSummary.first().quantityDelta)
        assertTrue(result.last().movementSummary.isEmpty())
    }

    @Test
    fun `orphan bulk movements are ignored`() {
        val result = remotePurchaseChangesFromRows(
            headers = listOf(header(seq = 2L, purchaseId = PURCHASE_TWO)),
            movements = listOf(
                movement(seq = 1L, position = 0, productId = PRODUCT_ONE, quantity = "1.000"),
            ),
        )

        assertTrue(result.single().movementSummary.isEmpty())
    }

    private fun header(
        seq: Long,
        purchaseId: String,
    ) = RemotePurchaseChangeEntity(
        cloudBusinessId = BUSINESS,
        seq = seq,
        purchaseId = purchaseId,
        status = PurchaseStatus.POSTED.name,
        documentType = "INVOICE",
        documentSeries = "F001",
        documentNumber = seq.toString(),
        issueDate = "2026-08-24",
        currency = "PEN",
        supplierRuc = null,
        supplierLegalName = "Proveedor",
        totalMinorUnits = 100L * seq,
        receiptId = "receipt-$seq",
        syncedAtMillis = seq,
    )

    private fun movement(
        seq: Long,
        position: Int,
        productId: String,
        quantity: String,
        businessId: String = BUSINESS,
    ) = RemoteMovementSummaryEntity(
        cloudBusinessId = businessId,
        seq = seq,
        position = position,
        productId = productId,
        productName = "Producto $position",
        type = StockMovementType.PURCHASE.name,
        quantityDelta = quantity,
    )

    private companion object {
        const val BUSINESS = "00000000-0000-0000-0000-000000000001"
        const val FOREIGN_BUSINESS = "00000000-0000-0000-0000-000000000002"
        const val PURCHASE_ONE = "00000000-0000-0000-0000-000000000101"
        const val PURCHASE_TWO = "00000000-0000-0000-0000-000000000102"
        const val PRODUCT_ONE = "00000000-0000-0000-0000-000000000201"
        const val PRODUCT_TWO = "00000000-0000-0000-0000-000000000202"
        const val FOREIGN_PRODUCT = "00000000-0000-0000-0000-000000000203"
    }
}
