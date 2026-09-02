package com.facturastock.app.data.local.entity

import org.junit.Assert.assertThrows
import org.junit.Test

class OutboxOperationEntityTest {
    @Test
    fun `purchase operations require matching purchase aggregate`() {
        assertThrows(IllegalArgumentException::class.java) {
            operation(operationType = "SYNC_PURCHASE", entityType = "PURCHASE")
        }
        assertThrows(IllegalArgumentException::class.java) {
            operation(
                operationType = "SYNC_PURCHASE_VOID",
                entityType = "PURCHASE",
                purchaseId = PURCHASE_ID,
                entityId = PURCHASE_ID,
                entityVersion = 1,
            )
        }
    }

    @Test
    fun `catalog operation cannot smuggle a purchase identity`() {
        assertThrows(IllegalArgumentException::class.java) {
            operation(
                operationType = "SYNC_PRODUCT",
                entityType = "PRODUCT",
                purchaseId = PURCHASE_ID,
                entityId = PRODUCT_ID,
            )
        }
    }

    private fun operation(
        operationType: String,
        entityType: String,
        purchaseId: String? = null,
        entityId: String = OPERATION_ID,
        entityVersion: Long = 1,
    ) = OutboxOperationEntity(
        operationId = OPERATION_ID,
        businessId = BUSINESS_ID,
        purchaseId = purchaseId,
        idempotencyKey = "matrix-$operationType",
        operationType = operationType,
        payload = "{\"version\":1}",
        createdAt = 1_000L,
        updatedAt = 1_000L,
        payloadVersion = 1,
        entityType = entityType,
        entityId = entityId,
        entityVersion = entityVersion,
    )

    private companion object {
        const val OPERATION_ID = "00000000-0000-4000-8000-000000009001"
        const val BUSINESS_ID = "00000000-0000-4000-8000-000000009002"
        const val PURCHASE_ID = "00000000-0000-4000-8000-000000009003"
        const val PRODUCT_ID = "00000000-0000-4000-8000-000000009004"
    }
}
