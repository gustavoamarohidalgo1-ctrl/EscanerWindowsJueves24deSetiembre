package com.facturastock.app.domain.model

import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InventoryReadModelsTest {
    @Test
    fun `aggregates exact quantities average and value across warehouses`() {
        val item = inventoryItem(
            position("10", "5", 1),
            position("5", "8", 2),
        )

        assertEquals(0, item.totalQuantityOnHand.compareTo(BigDecimal("15")))
        assertEquals(
            0,
            requireNotNull(item.estimatedValuesByCurrency[CURRENCY])
                .compareTo(BigDecimal("90")),
        )
        assertEquals(
            0,
            requireNotNull(item.averageUnitCostsByCurrency[CURRENCY])
                .amount.compareTo(BigDecimal("6.000000000000000000")),
        )
        assertTrue(item.allAlerts.isEmpty())
    }

    @Test
    fun `negative warehouse keeps signed valuation and makes aggregate average undefined`() {
        val item = inventoryItem(
            position("10", "5", 1),
            position("-20", "2", 2, setOf(InventoryDataAlert.NEGATIVE_STOCK)),
        )

        assertEquals(0, item.totalQuantityOnHand.compareTo(BigDecimal("-10")))
        assertEquals(
            0,
            requireNotNull(item.estimatedValuesByCurrency[CURRENCY])
                .compareTo(BigDecimal("10")),
        )
        assertNull(item.averageUnitCostsByCurrency[CURRENCY])
        assertTrue(InventoryDataAlert.NEGATIVE_STOCK in item.allAlerts)
        assertTrue(InventoryDataAlert.UNDEFINED_AGGREGATE_AVERAGE in item.allAlerts)
    }

    private fun inventoryItem(vararg positions: InventoryReadPosition): InventoryReadItem =
        InventoryReadItem(
            productId = ProductId.from(uuid(10)),
            businessId = BusinessId.from(uuid(11)),
            productName = "Producto",
            sku = "SKU-1",
            unitCode = "NIU",
            unitSymbol = "und",
            positions = positions.toList(),
        )

    private fun position(
        quantity: String,
        average: String,
        seed: Int,
        alerts: Set<InventoryDataAlert> = emptySet(),
    ): InventoryReadPosition = InventoryReadPosition(
        locationId = LocationId.from(uuid(seed)),
        locationName = "Almacén $seed",
        quantityOnHand = BigDecimal(quantity),
        averageUnitCost = InventoryCostAmount(BigDecimal(average), CURRENCY),
        version = seed.toLong(),
        updatedAt = Instant.ofEpochMilli(seed.toLong()),
        alerts = alerts,
    )

    private fun uuid(seed: Int): UUID =
        UUID.fromString("00000000-0000-0000-0000-%012d".format(seed))

    private companion object {
        val CURRENCY: CurrencyCode = CurrencyCode.of("PEN")
    }
}
