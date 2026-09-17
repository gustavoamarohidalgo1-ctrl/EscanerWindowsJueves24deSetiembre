package com.facturastock.app.domain.model

import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

class InventoryAggregationOptimizationTest {
    @Test
    fun singlePassPreservesExactScaleCurrencyOrderAndAlertsAgainstPreviousAlgorithm() {
        val random = Random(92371)
        repeat(160) { example ->
            val positions =
                List(if (example == 159) 1_000 else example % 25) { index ->
                    position(
                        index,
                        BigDecimal.valueOf(random.nextLong(-100, 10_000), random.nextInt(0, 7)),
                        BigDecimal.valueOf(random.nextLong(0, 10_000), random.nextInt(0, 7)),
                        CURRENCIES[random.nextInt(CURRENCIES.size)],
                    )
                }
            val alerts = if (example % 3 == 0) setOf(InventoryDataAlert.ARCHIVED_PRODUCT) else emptySet()
            val expected = previousAggregation(positions, alerts)
            val actual = item(positions, alerts)
            assertEquals("Exact quantity and scale, example $example", expected.quantity, actual.totalQuantityOnHand)
            assertEquals(expected.values, actual.estimatedValuesByCurrency)
            assertEquals(expected.values.keys.toList(), actual.estimatedValuesByCurrency.keys.toList())
            assertEquals(expected.averages, actual.averageUnitCostsByCurrency)
            assertEquals(expected.averages.keys.toList(), actual.averageUnitCostsByCurrency.keys.toList())
            assertEquals(expected.alerts.toList(), actual.allAlerts.toList())
        }
    }

    @Test
    fun eachPositionIsReadOnceInsteadOfRepeatedFullTraversals() {
        val positions = List(1_000) { index -> position(index, BigDecimal("1.00"), BigDecimal("2.50")) }
        val currentRows = CountingPositions(positions)
        val expectedRows = CountingPositions(positions)
        val actual = item(currentRows)
        val expected = previousAggregation(expectedRows, emptySet())
        assertEquals(expected.quantity, actual.totalQuantityOnHand)
        assertEquals(1_000, currentRows.reads)
        assertEquals(6_000, expectedRows.reads)
    }

    @Test
    fun duplicateLocationsStillFailBeforePublishingAnInventoryCard() {
        val first = position(1, BigDecimal.ONE, BigDecimal.ONE)
        assertThrows(IllegalArgumentException::class.java) { item(listOf(first, first.copy())) }
    }

    @Test
    fun aggregateAlertsRemainImmutableAfterConstruction() {
        val snapshot = item(listOf(position(0, BigDecimal.ONE, BigDecimal.ONE)))
        assertThrows(UnsupportedOperationException::class.java) {
            (snapshot.allAlerts as MutableSet<InventoryDataAlert>).clear()
        }
        assertEquals(setOf(InventoryDataAlert.ARCHIVED_LOCATION), snapshot.allAlerts)
    }

    private fun item(
        positions: List<InventoryReadPosition>,
        alerts: Set<InventoryDataAlert> = emptySet(),
    ) = InventoryReadItem(PRODUCT, BUSINESS, "Producto", "SKU", "NIU", "un", positions, alerts)

    private fun position(
        index: Int,
        quantity: BigDecimal,
        cost: BigDecimal,
        currency: CurrencyCode = CURRENCIES.first(),
    ) = InventoryReadPosition(
        locationId = LocationId.from(UUID(0, index.toLong() + 100)),
        locationName = "Almacén $index",
        quantityOnHand = quantity,
        averageUnitCost = InventoryCostAmount(cost, currency),
        version = 0,
        updatedAt = Instant.EPOCH,
        alerts =
            when {
                quantity.signum() < 0 -> setOf(InventoryDataAlert.NEGATIVE_STOCK)
                index % 7 == 0 -> setOf(InventoryDataAlert.ARCHIVED_LOCATION)
                else -> emptySet()
            },
    )

    /** Reference retained from the implementation before this optimization, including validation. */
    private fun previousAggregation(
        positions: List<InventoryReadPosition>,
        alerts: Set<InventoryDataAlert>,
    ): Aggregates {
        require(positions.map(InventoryReadPosition::locationId).distinct().size == positions.size)
        val quantity = positions.fold(BigDecimal.ZERO) { total, position -> total.add(position.quantityOnHand) }
        val values =
            positions.groupBy { it.averageUnitCost.currency }.mapValues { (_, rows) ->
                rows.fold(BigDecimal.ZERO) { total, position -> total.add(position.estimatedValue) }
            }
        val averages =
            positions.groupBy { it.averageUnitCost.currency }.mapValues { (currency, rows) ->
                val totalQuantity = rows.fold(BigDecimal.ZERO) { total, position -> total.add(position.quantityOnHand) }
                val value = rows.fold(BigDecimal.ZERO) { total, position -> total.add(position.estimatedValue) }
                val average =
                    when {
                        rows.any { it.quantityOnHand.signum() < 0 } -> null
                        totalQuantity.signum() <= 0 -> null
                        else -> value.divide(totalQuantity, 18, RoundingMode.HALF_EVEN).takeIf { it.signum() >= 0 }
                    }
                average?.let { InventoryCostAmount(it, currency) }
            }
        val allAlerts =
            buildSet {
                addAll(alerts)
                positions.forEach { addAll(it.alerts) }
                if (positions.map { it.averageUnitCost.currency }.distinct().size > 1) add(InventoryDataAlert.MIXED_CURRENCIES)
                if (averages.values.any { it == null }) add(InventoryDataAlert.UNDEFINED_AGGREGATE_AVERAGE)
            }
        return Aggregates(quantity, values, averages, allAlerts)
    }

    private data class Aggregates(
        val quantity: BigDecimal,
        val values: Map<CurrencyCode, BigDecimal>,
        val averages: Map<CurrencyCode, InventoryCostAmount?>,
        val alerts: Set<InventoryDataAlert>,
    )

    private class CountingPositions(
        private val delegate: List<InventoryReadPosition>,
    ) : AbstractList<InventoryReadPosition>() {
        var reads = 0
        override val size: Int get() = delegate.size

        override fun get(index: Int): InventoryReadPosition {
            reads++
            return delegate[index]
        }
    }

    private companion object {
        val CURRENCIES = listOf(CurrencyCode.of("PEN"), CurrencyCode.of("USD"), CurrencyCode.of("JPY"))
        val PRODUCT = ProductId.from(UUID(0, 1))
        val BUSINESS = BusinessId.from(UUID(0, 2))
    }
}
