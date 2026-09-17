package com.facturastock.app.data.repository

import com.facturastock.app.data.local.dao.PostedSaleProfitPageKey
import com.facturastock.app.data.local.dao.PostedSaleProfitRow
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.RealizedProfitIssue
import java.math.BigDecimal
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomSaleReportMappingTest {
    @Test
    fun `paged loading is equivalent to full mapping and keeps raw batches bounded`() = runTest {
        val pageSize = 32
        val keys = (1..205).map { index ->
            PostedSaleProfitPageKey(
                saleId = UUID(1L, index.toLong()).toString(),
                postedAt = 50_000L - (index / 3),
            )
        }.sortedWith(
            compareByDescending<PostedSaleProfitPageKey>(PostedSaleProfitPageKey::postedAt)
                .thenByDescending(PostedSaleProfitPageKey::saleId),
        )
        val rows = keys.mapIndexed { index, key ->
            row(
                saleId = key.saleId,
                saleLineId = UUID(2L, index.toLong() + 1L).toString(),
                movementId = UUID(3L, index.toLong() + 1L).toString(),
                postedAt = key.postedAt,
            )
        }
        val rowBatchSizes = mutableListOf<Int>()
        val requestedLimits = mutableListOf<Int>()

        val paged = loadPostedSaleProfitsPaged(
            firstPage = keys.take(pageSize),
            pageSize = pageSize,
            loadNextPage = { cursor, limit ->
                requestedLimits += limit
                val cursorIndex = keys.indexOf(cursor)
                check(cursorIndex >= 0)
                keys.drop(cursorIndex + 1).take(limit)
            },
            loadRows = { saleIds ->
                rowBatchSizes += saleIds.size
                val requested = saleIds.toSet()
                rows.filter { it.saleId in requested }
            },
        )

        assertEquals(realizedSaleProfitsFromRows(rows), paged)
        assertEquals(keys.map(PostedSaleProfitPageKey::saleId), paged.map { it.saleId.value })
        assertEquals(7, rowBatchSizes.size)
        assertTrue(rowBatchSizes.all { it in 1..pageSize })
        assertTrue(requestedLimits.all { it == pageSize })
    }

    @Test
    fun `mapping preserves first sale appearance and leaves final ordering to report layer`() {
        val result = realizedSaleProfitsFromRows(
            listOf(
                row(
                    saleId = SALE_ID,
                    saleLineId = SALE_LINE_ID,
                    movementId = MOVEMENT_ID,
                    postedAt = 10_000L,
                ),
                row(
                    saleId = SECOND_SALE_ID,
                    saleLineId = SECOND_SALE_LINE_ID,
                    movementId = SECOND_MOVEMENT_ID,
                    postedAt = 20_000L,
                ),
            ),
        )

        assertEquals(listOf(SALE_ID, SECOND_SALE_ID), result.map { it.saleId.value })
    }

    @Test
    fun `maps charged total but excludes tax from realized gross profit`() {
        val result = realizedSaleProfitsFromRows(
            listOf(
                row(
                    totalMinorUnits = 1_180L,
                    lineTotalMinorUnits = 1_180L,
                    lineTaxMinorUnits = 180L,
                    movementQuantityDelta = "-2.000",
                    movementUnitCost = "2.5000",
                ),
            ),
        ).single()

        assertEquals(1_180L, result.totalCharged.minorUnits)
        assertEquals(1_000L, result.netRevenue.minorUnits)
        assertEquals(BigDecimal("5.0000000"), result.historicalCost?.amount)
        assertEquals(BigDecimal("5.0000000"), result.grossProfit?.amount)
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun `maps line snapshots quantities and exact profits in stable position order`() {
        val result = realizedSaleProfitsFromRows(
            listOf(
                row(
                    saleLineId = SECOND_SALE_LINE_ID,
                    productId = SECOND_PRODUCT_ID,
                    linePosition = 1,
                    productNameSnapshot = "Leche",
                    unitCodeSnapshot = "BOT",
                    locationNameSnapshot = "Tienda",
                    totalMinorUnits = 1_770L,
                    lineQuantity = "3.000",
                    lineTotalMinorUnits = 1_180L,
                    lineTaxMinorUnits = 180L,
                    movementId = SECOND_MOVEMENT_ID,
                    movementQuantityDelta = "-3.000",
                    movementUnitCost = "1.2500",
                ),
                row(
                    productId = PRODUCT_ID,
                    linePosition = 0,
                    productNameSnapshot = "Arroz",
                    unitCodeSnapshot = "KG",
                    locationNameSnapshot = "Almacén",
                    totalMinorUnits = 1_770L,
                ),
            ),
        ).single()

        assertEquals(listOf("Arroz", "Leche"), result.lines.map { it.productName })
        assertEquals(listOf(PRODUCT_ID, SECOND_PRODUCT_ID), result.lines.map { it.productId.value })
        assertEquals(listOf(0, 1), result.lines.map { it.position })
        assertEquals(Quantity.of("2.000"), result.lines[0].quantity)
        assertEquals(Quantity.of("3.000"), result.lines[1].quantity)
        assertEquals("KG", result.lines[0].unitCode)
        assertEquals("Tienda", result.lines[1].locationName)
        assertEquals(BigDecimal("5.0000000"), result.lines[0].historicalCost?.amount)
        assertEquals(BigDecimal("0.0000000"), result.lines[0].grossProfit?.amount)
        assertEquals(BigDecimal("3.7500000"), result.lines[1].historicalCost?.amount)
        assertEquals(BigDecimal("6.2500000"), result.lines[1].grossProfit?.amount)
        assertEquals(2, result.lineCount)
        assertEquals(BigDecimal("8.7500000"), result.historicalCost?.amount)
        assertEquals(BigDecimal("6.2500000"), result.grossProfit?.amount)
    }

    @Test
    fun `healthy line keeps its profit while incomplete sale aggregate stays unavailable`() {
        val result = realizedSaleProfitsFromRows(
            listOf(
                row(totalMinorUnits = 1_180L),
                row(
                    saleLineId = SECOND_SALE_LINE_ID,
                    productId = SECOND_PRODUCT_ID,
                    linePosition = 1,
                    productNameSnapshot = "Leche",
                    totalMinorUnits = 1_180L,
                    movementId = null,
                    movementQuantityDelta = null,
                    movementUnitCost = null,
                    movementCurrencyCode = null,
                ),
            ),
        ).single()

        assertEquals(BigDecimal("5.0000000"), result.lines[0].historicalCost?.amount)
        assertEquals(BigDecimal("0.0000000"), result.lines[0].grossProfit?.amount)
        assertTrue(result.lines[0].issues.isEmpty())
        assertNull(result.lines[1].historicalCost)
        assertNull(result.lines[1].grossProfit)
        assertEquals(
            setOf(RealizedProfitIssue.MISSING_HISTORICAL_COST),
            result.lines[1].issues,
        )
        assertNull(result.historicalCost)
        assertNull(result.grossProfit)
        assertEquals(setOf(RealizedProfitIssue.MISSING_HISTORICAL_COST), result.issues)
    }

    @Test
    fun `missing movement cost never becomes zero or partial profit`() {
        val result = realizedSaleProfitsFromRows(
            listOf(
                row(
                    movementId = null,
                    movementQuantityDelta = null,
                    movementUnitCost = null,
                    movementCurrencyCode = null,
                ),
            ),
        ).single()

        assertEquals(setOf(RealizedProfitIssue.MISSING_HISTORICAL_COST), result.issues)
        assertNull(result.historicalCost)
        assertNull(result.grossProfit)
    }

    @Test
    fun `historical cost in another currency is explicitly non comparable`() {
        val result = realizedSaleProfitsFromRows(
            listOf(row(movementCurrencyCode = "USD")),
        ).single()

        assertEquals(setOf(RealizedProfitIssue.COST_CURRENCY_MISMATCH), result.issues)
        assertNull(result.historicalCost)
        assertNull(result.grossProfit)
    }

    @Test
    fun `line currency mismatch is invalid instead of silently relabeled`() {
        val result = realizedSaleProfitsFromRows(
            listOf(row(lineCurrencyCode = "USD")),
        ).single()

        assertEquals(setOf(RealizedProfitIssue.INVALID_PERSISTED_DATA), result.lines.single().issues)
        assertNull(result.lines.single().historicalCost)
        assertNull(result.grossProfit)
    }

    @Test
    fun `duplicate sale movements invalidate the line instead of doubling its profit`() {
        val result = realizedSaleProfitsFromRows(
            listOf(
                row(),
                row(movementId = SECOND_MOVEMENT_ID),
            ),
        ).single()

        assertEquals(setOf(RealizedProfitIssue.INVALID_PERSISTED_DATA), result.lines.single().issues)
        assertNull(result.lines.single().historicalCost)
        assertNull(result.historicalCost)
        assertNull(result.grossProfit)
    }

    private fun row(
        saleId: String = SALE_ID,
        saleLineId: String = SALE_LINE_ID,
        productId: String = PRODUCT_ID,
        linePosition: Int = 0,
        productNameSnapshot: String = "Producto",
        unitCodeSnapshot: String = "UND",
        locationNameSnapshot: String = "Principal",
        lineCurrencyCode: String = "PEN",
        totalMinorUnits: Long = 590L,
        postedAt: Long = 10_000L,
        lineQuantity: String = "2.000",
        lineTotalMinorUnits: Long = 590L,
        lineTaxMinorUnits: Long = 90L,
        movementId: String? = MOVEMENT_ID,
        movementQuantityDelta: String? = "-2.000",
        movementUnitCost: String? = "2.5000",
        movementCurrencyCode: String? = "PEN",
    ): PostedSaleProfitRow = PostedSaleProfitRow(
        saleId = saleId,
        totalMinorUnits = totalMinorUnits,
        saleCurrencyCode = "PEN",
        postedAt = postedAt,
        saleLineId = saleLineId,
        productId = productId,
        linePosition = linePosition,
        productNameSnapshot = productNameSnapshot,
        unitCodeSnapshot = unitCodeSnapshot,
        locationNameSnapshot = locationNameSnapshot,
        lineCurrencyCode = lineCurrencyCode,
        lineQuantity = lineQuantity,
        lineTotalMinorUnits = lineTotalMinorUnits,
        lineTaxMinorUnits = lineTaxMinorUnits,
        movementId = movementId,
        movementQuantityDelta = movementQuantityDelta,
        movementUnitCost = movementUnitCost,
        movementCurrencyCode = movementCurrencyCode,
    )

    private companion object {
        const val SALE_ID = "11111111-1111-4111-8111-111111111111"
        const val SALE_LINE_ID = "22222222-2222-4222-8222-222222222222"
        const val PRODUCT_ID = "77777777-7777-4777-8777-777777777777"
        const val MOVEMENT_ID = "33333333-3333-4333-8333-333333333333"
        const val SECOND_SALE_ID = "44444444-4444-4444-8444-444444444444"
        const val SECOND_SALE_LINE_ID = "55555555-5555-4555-8555-555555555555"
        const val SECOND_PRODUCT_ID = "88888888-8888-4888-8888-888888888888"
        const val SECOND_MOVEMENT_ID = "66666666-6666-4666-8666-666666666666"
    }
}
