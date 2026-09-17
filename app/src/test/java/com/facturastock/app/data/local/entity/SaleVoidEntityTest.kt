package com.facturastock.app.data.local.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SaleVoidEntityTest {
    @Test
    fun `receipt preserves financial split and requires accountable authority`() {
        val receipt = receipt()
        assertEquals(75L, receipt.refundedAmountMinorUnits)
        assertEquals(225L, receipt.cancelledDebtBalanceMinorUnits)
        assertThrows(IllegalArgumentException::class.java) { receipt.copy(actorRole = "OPERATOR") }
        assertThrows(IllegalArgumentException::class.java) { receipt.copy(actorId = " owner ") }
        assertThrows(IllegalArgumentException::class.java) { receipt.copy(impactHash = "invalid") }
        assertThrows(IllegalArgumentException::class.java) { receipt.copy(cancelledDebtBalanceMinorUnits = -1) }
    }

    @Test
    fun `return movement must have positive stock exact sale origin cost and deterministic key`() {
        val movement =
            StockMovementEntity(
                movementId = "88888888-8888-4888-8888-888888888888",
                businessId = BUSINESS_ID,
                productId = "33333333-3333-4333-8333-333333333333",
                locationId = "44444444-4444-4444-8444-444444444444",
                type = "SALE_VOID",
                quantityDelta = "2.000",
                unitCost = "3.250",
                currencyCode = "PEN",
                idempotencyKey = "sale-void-stock:v1:$SALE_ID:$LINE_ID",
                occurredAt = 4,
                createdAt = 4,
                saleId = SALE_ID,
                saleLineId = LINE_ID,
            )
        assertEquals("2.000", movement.quantityDelta)
        assertThrows(IllegalArgumentException::class.java) { movement.copy(quantityDelta = "-2.000") }
        assertThrows(IllegalArgumentException::class.java) { movement.copy(unitCost = null) }
        assertThrows(IllegalArgumentException::class.java) { movement.copy(idempotencyKey = "wrong-key") }
        assertThrows(IllegalArgumentException::class.java) { movement.copy(saleId = null, saleLineId = null) }
        assertThrows(IllegalArgumentException::class.java) {
            movement.copy(purchaseId = SALE_ID, purchaseLineId = LINE_ID)
        }
    }

    private fun receipt() =
        SaleVoidEntity(
            saleId = SALE_ID,
            businessId = BUSINESS_ID,
            impactHash = "a".repeat(64),
            refundedAmountMinorUnits = 75,
            cancelledDebtBalanceMinorUnits = 225,
            currencyCode = "PEN",
            actorId = "owner",
            actorRole = "OWNER",
            voidedAt = 4,
        )

    private companion object {
        const val BUSINESS_ID = "11111111-1111-4111-8111-111111111111"
        const val SALE_ID = "55555555-5555-4555-8555-555555555555"
        const val LINE_ID = "66666666-6666-4666-8666-666666666666"
    }
}
