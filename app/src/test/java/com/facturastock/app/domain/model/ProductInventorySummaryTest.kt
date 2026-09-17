package com.facturastock.app.domain.model

import com.facturastock.app.domain.model.id.LocationId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.math.BigDecimal
import java.util.UUID

class ProductInventorySummaryTest {
    @Test
    fun `single positive position preserves all stored derived precision without rounding`() {
        val cost = InventoryCostAmount(BigDecimal("123456.123456789012345678123456789012345678"), PEN)
        val position = ProductInventoryPosition(location(1), BigDecimal("1.000000000000000000000000000000000001"), cost)
        val summary = ProductInventorySummary(listOf(position))

        assertSame(cost, summary.averageUnitCost)
        assertEquals(
            cost.amount.toPlainString(),
            summary.positions
                .single()
                .averageUnitCost.amount
                .toPlainString(),
        )
        assertEquals(position.quantityOnHand, summary.totalQuantityOnHand)
    }

    @Test
    fun `large persisted cost is a read amount without widening the input UnitCost policy`() {
        val amount = BigDecimal("123456789012345678901234567890123456789012345678901234567890.123456789012345678")
        val summary = ProductInventorySummary(listOf(position(1, "2", amount.toPlainString(), PEN)))

        assertEquals(amount, summary.averageUnitCost?.amount)
    }

    @Test
    fun `multiple positions retain existing weighted average scale and half even rounding`() {
        val summary = ProductInventorySummary(listOf(position(1, "1", "1", PEN), position(2, "2", "2", PEN)))

        assertEquals(BigDecimal("3"), summary.totalQuantityOnHand)
        assertEquals(BigDecimal("1.666666666666666667"), summary.averageUnitCost?.amount)
        assertEquals(listOf(BigDecimal("1"), BigDecimal("2")), summary.positions.map { it.averageUnitCost.amount })
    }

    @Test
    fun `zero quantity retains previous aggregate policy without discarding the position cost`() {
        val stored = "4.123456789012345678123456789012345678"
        val summary = ProductInventorySummary(listOf(position(1, "0", stored, PEN)))

        assertEquals(BigDecimal.ZERO, summary.averageUnitCost?.amount)
        assertEquals(
            stored,
            summary.positions
                .single()
                .averageUnitCost.amount
                .toPlainString(),
        )
    }

    @Test
    fun `currencies stay separate and retain each original single position precision`() {
        val penCost = "2.1234567890123456789"
        val usdCost = "3.9876543210987654321"
        val summary = ProductInventorySummary(listOf(position(1, "2", penCost, PEN), position(2, "3", usdCost, USD)))

        assertNull(summary.averageUnitCost)
        assertEquals(penCost, requireNotNull(summary.averageUnitCostsByCurrency.getValue(PEN)).amount.toPlainString())
        assertEquals(usdCost, requireNotNull(summary.averageUnitCostsByCurrency.getValue(USD)).amount.toPlainString())
    }

    @Test
    fun `negative quantity makes only that currency average undefined and preserves every position`() {
        val positions = listOf(position(1, "2", "1", PEN), position(2, "-1", "100", PEN), position(3, "3", "4.125", USD))
        val signed = ProductInventorySummary(positions.take(2))
        assertEquals(BigDecimal.ONE, signed.totalQuantityOnHand)
        assertNull(signed.averageUnitCost)
        assertEquals(positions.take(2), signed.positions)

        val mixed = ProductInventorySummary(positions)
        assertEquals(BigDecimal("4"), mixed.totalQuantityOnHand)
        assertEquals(positions, mixed.positions)
        assertNull(mixed.averageUnitCostsByCurrency.getValue(PEN))
        assertEquals(BigDecimal("4.125"), mixed.averageUnitCostsByCurrency.getValue(USD)?.amount)
        assertNull(mixed.averageUnitCost)
    }

    private fun position(
        seed: Int,
        quantity: String,
        cost: String,
        currency: CurrencyCode,
    ) = ProductInventoryPosition(location(seed), BigDecimal(quantity), InventoryCostAmount(BigDecimal(cost), currency))

    private fun location(seed: Int) = LocationId.from(UUID(0L, seed.toLong()))

    private companion object {
        val PEN = CurrencyCode.of("PEN")
        val USD = CurrencyCode.of("USD")
    }
}
