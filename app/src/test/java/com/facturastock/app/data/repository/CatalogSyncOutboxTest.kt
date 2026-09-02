package com.facturastock.app.data.repository

import com.facturastock.app.data.local.entity.InventoryLocationEntity
import com.facturastock.app.data.local.entity.ProductEntity
import com.facturastock.app.data.local.entity.SupplierEntity
import com.facturastock.app.data.local.entity.UnitEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogSyncOutboxTest {
    @Test
    fun `product payload is identical across devices with different reference UUIDs`() {
        val first = productGraph(unitSeed = 11, locationSeed = 12)
        val second = productGraph(unitSeed = 91, locationSeed = 92)

        val firstOperation = CatalogSyncOutbox.product(
            first.product,
            expectedVersion = 0L,
            inventoryUnit = first.unit,
            purchaseUnit = null,
            location = first.location,
        )
        val secondOperation = CatalogSyncOutbox.product(
            second.product,
            expectedVersion = 0L,
            inventoryUnit = second.unit,
            purchaseUnit = null,
            location = second.location,
        )

        assertEquals(firstOperation.payload, secondOperation.payload)
        assertEquals(firstOperation.idempotencyKey, secondOperation.idempotencyKey)
        assertEquals(firstOperation.operationId, secondOperation.operationId)
        assertFalse(firstOperation.payload.contains(first.unit.unitId))
        assertFalse(firstOperation.payload.contains(first.location.locationId))
        assertFalse(firstOperation.payload.contains(BUSINESS_ID))
        assertEquals(2, firstOperation.payloadVersion)
        assertTrue(firstOperation.payload.startsWith("{\"version\":2"))
        assertTrue(firstOperation.payload.contains("\"salePriceMinorUnits\":600"))
        assertTrue(firstOperation.payload.contains("\"salePriceCurrencyCode\":\"PEN\""))
        assertTrue(firstOperation.payload.contains("\"inventoryUnit\":{\"code\":\"NIU\""))
        assertTrue(firstOperation.payload.contains("\"location\":{\"name\":\"Principal\""))
    }

    @Test
    fun `supplier payload is canonical versioned and omits local business identity`() {
        val supplier = SupplierEntity(
            supplierId = SUPPLIER_ID,
            businessId = BUSINESS_ID,
            legalName = "Proveedor \"Norte\"",
            ruc = "20123456789",
            tradeName = null,
            createdAt = 1_000L,
            updatedAt = 2_000L,
            version = 3L,
        )

        val operation = CatalogSyncOutbox.supplier(supplier, expectedVersion = 2L)

        assertEquals(SYNC_SUPPLIER, operation.operationType)
        assertEquals(3L, operation.entityVersion)
        assertEquals("sync-supplier:v1:$SUPPLIER_ID:3", operation.idempotencyKey)
        assertFalse(operation.payload.contains(BUSINESS_ID))
        assertEquals(
            "{\"version\":1,\"entityId\":\"$SUPPLIER_ID\",\"expectedVersion\":2," +
                "\"targetVersion\":3,\"mutation\":\"UPSERT\",\"snapshot\":{" +
                "\"legalName\":\"Proveedor \\\"Norte\\\"\",\"ruc\":\"20123456789\"," +
                "\"tradeName\":null,\"status\":\"ACTIVE\",\"createdAt\":1000," +
                "\"updatedAt\":2000}}",
            operation.payload,
        )
    }

    @Test
    fun `linked aggregate keeps local causal id but uses remote id on wire and idempotency`() {
        val graph = productGraph(unitSeed = 11, locationSeed = 12)
        val remoteId = uuid(44)

        val operation = CatalogSyncOutbox.product(
            entity = graph.product,
            expectedVersion = 0,
            inventoryUnit = graph.unit,
            purchaseUnit = null,
            location = graph.location,
            remoteEntityId = remoteId,
        )

        assertEquals(PRODUCT_ID, operation.entityId)
        assertEquals(remoteId, operation.remoteEntityId)
        assertEquals("sync-product:v2:$remoteId:1", operation.idempotencyKey)
        assertTrue(operation.payload.contains("\"entityId\":\"$remoteId\""))
        assertFalse(operation.payload.contains("\"entityId\":\"$PRODUCT_ID\""))
    }

    private fun productGraph(unitSeed: Int, locationSeed: Int): ProductGraph {
        val unit = UnitEntity(
            unitId = uuid(unitSeed),
            businessId = BUSINESS_ID,
            code = "NIU",
            name = "Unidad",
            symbol = "u",
            createdAt = 100L,
            updatedAt = 100L,
        )
        val location = InventoryLocationEntity(
            locationId = uuid(locationSeed),
            businessId = BUSINESS_ID,
            name = "Principal",
            createdAt = 100L,
            updatedAt = 100L,
        )
        return ProductGraph(
            product = ProductEntity(
                productId = PRODUCT_ID,
                businessId = BUSINESS_ID,
                unitId = unit.unitId,
                name = "Arroz",
                createdAt = 1_000L,
                updatedAt = 1_000L,
                locationId = location.locationId,
                sku = "ARR-1",
                salePriceMinorUnits = 600L,
                salePriceCurrencyCode = "PEN",
                version = 1L,
            ),
            unit = unit,
            location = location,
        )
    }

    private data class ProductGraph(
        val product: ProductEntity,
        val unit: UnitEntity,
        val location: InventoryLocationEntity,
    )

    private fun uuid(seed: Int): String = "00000000-0000-4000-8000-%012d".format(seed)

    private companion object {
        const val BUSINESS_ID = "00000000-0000-4000-8000-000000000001"
        const val PRODUCT_ID = "00000000-0000-4000-8000-000000000002"
        const val SUPPLIER_ID = "00000000-0000-4000-8000-000000000003"
    }
}
