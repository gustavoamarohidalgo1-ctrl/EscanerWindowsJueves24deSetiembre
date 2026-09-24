package com.facturastock.app.feature.inventory

import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.InventoryCostAmount
import com.facturastock.app.domain.model.InventoryDataAlert
import com.facturastock.app.domain.model.InventoryReadItem
import com.facturastock.app.domain.model.InventoryReadPosition
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class InventorySearchIndexTest {
    @Test
    fun `five thousand stock cost and diagnostic changes normalize no names and one rename normalizes one`() =
        runTest {
            val catalog = List(5_000) { item(it) }
            val normalize = CountingNormalizer()
            val initial = InventorySearchIndex.Empty.prepare(catalog, normalize::apply)
            assertEquals(5_000, normalize.calls)

            val changedStock =
                catalog.map {
                    it.copy(
                        positions = listOf(position(quantity = "3.125", cost = "8.750")),
                        alerts = setOf(InventoryDataAlert.PROJECTION_DIVERGENCE),
                    )
                }
            normalize.calls = 0
            val updated = initial.prepare(changedStock, normalize::apply)
            assertEquals(0, normalize.calls)
            assertEquals(initial.termsByProduct, updated.termsByProduct)
            catalog.forEach { assertSame(initial.termsByProduct[it.productId], updated.termsByProduct[it.productId]) }

            val renamed =
                changedStock.toMutableList().apply {
                    this[2_501] = this[2_501].copy(productName = "  CAFÉ con Azúcar  ")
                }
            val afterRename = updated.prepare(renamed, normalize::apply)
            assertEquals(1, normalize.calls)
            assertEquals(listOf("cafe con azucar"), afterRename.termsByProduct[renamed[2_501].productId])
            assertEquals(catalog.map { it.productId }, afterRename.termsByProduct.keys.toList())
            assertEquals(listOf("producto 2501"), initial.termsByProduct[catalog[2_501].productId])
        }

    @Test
    fun `reordering retains only current products and removed names are not retained for reinsertion`() =
        runTest {
            val catalog = List(4) { item(it) }
            val normalize = CountingNormalizer()
            val initial = InventorySearchIndex.Empty.prepare(catalog, normalize::apply)
            normalize.calls = 0
            val remaining = listOf(catalog[3], catalog[1])
            val pruned = initial.prepare(remaining, normalize::apply)
            assertEquals(0, normalize.calls)
            assertEquals(remaining.map { it.productId }, pruned.termsByProduct.keys.toList())
            assertNull(pruned.termsByProduct[catalog[0].productId])

            val reinserted = pruned.prepare(listOf(catalog[0]) + remaining, normalize::apply)
            assertEquals(1, normalize.calls)
            assertEquals(
                listOf(catalog[0].productId, catalog[3].productId, catalog[1].productId),
                reinserted.termsByProduct.keys.toList(),
            )

            val empty = reinserted.prepare(emptyList(), normalize::apply)
            assertTrue(empty.termsByProduct.isEmpty())
            empty.prepare(remaining, normalize::apply)
            assertEquals(3, normalize.calls)
        }

    @Test
    fun `changing business replaces the cache even when product identity and name happen to match`() =
        runTest {
            val catalog = List(3) { item(it) }
            val normalize = CountingNormalizer()
            val initial = InventorySearchIndex.Empty.prepare(catalog, normalize::apply)
            normalize.calls = 0
            val other = catalog[1].copy(businessId = BusinessId.from(UUID(0, 999)))
            val switched = initial.prepare(listOf(other), normalize::apply)
            assertEquals(1, normalize.calls)
            assertEquals(listOf(other.productId), switched.termsByProduct.keys.toList())

            switched.prepare(catalog, normalize::apply)
            assertEquals(4, normalize.calls)
        }

    @Test
    fun `name and query normalization preserve accents case whitespace and canonical equivalence`() =
        runTest {
            val names = listOf("  CAFÉ  ", "AZU\u0301CAR", "PiÑa", "İNDIGO", "Arroz integral")
            val catalog = names.mapIndexed { index, name -> item(index).copy(productName = name) }
            val index = InventorySearchIndex.Empty.prepare(catalog)
            assertEquals(
                listOf("cafe", "azucar", "pina", "indigo", "arroz integral"),
                index.termsByProduct.values.map { it.single() },
            )
            listOf(" café ", "AZÚCAR", "piña", "ÍNDIGO", "ARROZ INTEGRAL").forEachIndexed { position, query ->
                assertEquals(index.termsByProduct[catalog[position].productId]?.single(), query.inventorySearchKey())
            }
        }

    @Test
    fun `cancellation midway through a candidate leaves the accepted index complete and unchanged`() =
        runTest {
            val catalog = List(300) { item(it) }
            val accepted = InventorySearchIndex.Empty.prepare(catalog)
            val renamed = catalog.map { it.copy(productName = "Nuevo ${it.productName}") }
            var candidate: InventorySearchIndex? = null
            var calls = 0
            val preparation =
                launch {
                    val job = currentCoroutineContext().job
                    candidate =
                        accepted.prepare(renamed) { name ->
                            calls++
                            if (calls == 3) job.cancel()
                            name.inventorySearchKey()
                        }
                }
            runCurrent()
            assertTrue(preparation.isCancelled)
            assertNull(candidate)
            assertTrue(calls in 3..64)

            val normalize = CountingNormalizer()
            val unchanged = accepted.prepare(catalog, normalize::apply)
            assertEquals(0, normalize.calls)
            assertEquals(accepted.termsByProduct, unchanged.termsByProduct)
            val completed = accepted.prepare(renamed, normalize::apply)
            assertEquals(300, normalize.calls)
            assertTrue(completed.termsByProduct.values.all { it.single().startsWith("nuevo ") })
            assertTrue(accepted.termsByProduct.values.none { it.single().startsWith("nuevo ") })
        }

    private class CountingNormalizer {
        var calls = 0

        fun apply(value: String): String {
            calls++
            return value.inventorySearchKey()
        }
    }

    private fun item(index: Int) =
        InventoryReadItem(
            productId = ProductId.from(UUID(0, index.toLong() + 1)),
            businessId = BusinessId.from(UUID(0, 500_000)),
            productName = "Producto $index",
            sku = null,
            unitCode = "NIU",
            unitSymbol = "und",
            positions = listOf(position()),
        )

    private fun position(
        quantity: String = "1",
        cost: String = "2.50",
    ) = InventoryReadPosition(
        locationId = LocationId.from(UUID(0, 600_000)),
        locationName = "Almacén",
        quantityOnHand = BigDecimal(quantity),
        averageUnitCost = InventoryCostAmount(BigDecimal(cost), CurrencyCode.of("PEN")),
        version = 1,
        updatedAt = Instant.EPOCH,
    )
}
