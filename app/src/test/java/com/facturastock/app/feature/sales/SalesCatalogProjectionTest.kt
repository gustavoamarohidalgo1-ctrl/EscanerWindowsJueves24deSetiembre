package com.facturastock.app.feature.sales

import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.InventoryCostAmount
import com.facturastock.app.domain.model.InventoryDataAlert
import com.facturastock.app.domain.model.InventoryReadItem
import com.facturastock.app.domain.model.InventoryReadPosition
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.WeightSaleCalculator
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.UnitId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlin.random.Random
import kotlin.system.measureNanoTime

class SalesCatalogProjectionTest {
    @Test
    fun `incremental projection matches previous full projection across changes and source order ties`() =
        runTest {
            val random = Random(5318)
            var products = (1..40).map { product(it, if (it % 3 == 0) "Producto" else "Nombre ${it % 7}") }
            var inventory = products.map { inventory(it, "10") }
            val projector = SalesCatalogProjector()
            repeat(160) { step ->
                val index = random.nextInt(products.size)
                when (step % 8) {
                    0 -> {
                        inventory = inventory.shuffled(random)
                    }

                    1 -> {
                        products =
                            products.mapIndexed { i, product ->
                                if (i == index) product.copy(name = listOf("Producto", "producto", "Árbol", "Alfa")[step % 4]) else product
                            }
                    }

                    2 -> {
                        inventory =
                            inventory.mapIndexed { i, item ->
                                if (i == index) item.copy(positions = item.positions.map { it.copy(quantityOnHand = BigDecimal(step % 3)) }) else item
                            }
                    }

                    3 -> {
                        inventory =
                            inventory.mapIndexed { i, item ->
                                if (i == index) item.copy(positions = item.positions.reversed().map { it.copy(locationName = "Zona ${step % 5}") }) else item
                            }
                    }

                    4 -> {
                        products =
                            products.mapIndexed { i, product ->
                                if (i == index) product.copy(status = if (product.status == CatalogStatus.ACTIVE) CatalogStatus.ARCHIVED else CatalogStatus.ACTIVE) else product
                            }
                    }

                    5 -> {
                        inventory =
                            inventory.mapIndexed { i, item ->
                                if (i == index) item.copy(positions = item.positions.map { it.copy(alerts = if (step % 2 == 0) emptySet() else setOf(InventoryDataAlert.ARCHIVED_LOCATION)) }) else item
                            }
                    }

                    6 -> {
                        products =
                            products.mapIndexed { i, product ->
                                if (i == index) product.copy(barcode = "00123$step", salePrice = Money.ofMinor(step.toLong(), CURRENCY)) else product
                            }
                    }

                    else -> {
                        inventory =
                            inventory.mapIndexed { i, item ->
                                if (i == index) item.copy(unitCode = "KGM", unitSymbol = if (step % 3 == 0) "" else "kg") else item
                            }
                    }
                }
                val actual = projector.project(prepareSalesProducts(products), inventory)
                val expected = fullProjectionBeforeOptimization(products, inventory)
                assertEquals("options at change $step", expected.first, actual.optionsByProduct)
                assertEquals("stable global order at change $step", expected.second, actual.orderedOptions)
            }
        }

    @Test
    fun `stock updates retain all unaffected product options for one thousand and ten thousand products`() =
        runTest {
            for (size in listOf(1_000, 10_000)) {
                val products = (1..size).map { product(it, "Producto ${size - it}") }
                val catalog = prepareSalesProducts(products)
                val stock = products.map { inventory(it, "10", singleLocation = true) }
                val projector = SalesCatalogProjector()
                val before = projector.project(catalog, stock)
                val changed =
                    stock.mapIndexed { index, item ->
                        if (index == size / 2) item.copy(positions = item.positions.map { it.copy(quantityOnHand = BigDecimal("7.5")) }) else item
                    }
                val after = projector.project(catalog, changed)
                assertSame(catalog, after.productsById)
                val reused = before.optionsByProduct.count { (id, options) -> options === after.optionsByProduct[id] }
                assertEquals(size - 1, reused)
                val reference = fullProjectionBeforeOptimization(products, changed)
                assertEquals(reference.second, after.orderedOptions)
                println("Sales projection: products=$size, unchanged option groups reused=$reused, affected groups rebuilt=1, product map reused=true")
            }
        }

    @Test
    fun `inventory costs versions and metadata refresh without replacing visible sale options`() =
        runTest {
            val product = product(1, "Producto")
            val catalog = prepareSalesProducts(listOf(product))
            val stock = inventory(product, "10")
            val projector = SalesCatalogProjector()
            val before = projector.project(catalog, listOf(stock))
            val nextStock =
                stock.copy(
                    productName = "Nombre de la proyección",
                    alerts = setOf(InventoryDataAlert.PROJECTION_DIVERGENCE),
                    positions = stock.positions.map { it.copy(version = 9, averageUnitCost = InventoryCostAmount(BigDecimal("7.25"), CURRENCY)) },
                )
            val after = projector.project(catalog, listOf(nextStock))
            assertSame(nextStock, after.inventoryById[product.productId])
            assertSame(before.optionsByProduct, after.optionsByProduct)
            assertSame(before.orderedOptions, after.orderedOptions)
        }

    @Test
    fun `catalog price updates retain unchanged options and stock visibility still invalidates ordering`() =
        runTest {
            val products = listOf(product(1, "Mismo"), product(2, "Mismo"))
            val stock = products.map { inventory(it, "10") }
            val projector = SalesCatalogProjector()
            val initial = projector.project(prepareSalesProducts(products), stock)
            val repriced = products.mapIndexed { index, product -> if (index == 0) product.copy(salePrice = Money.ofMinor(500, CURRENCY)) else product }
            val prices = projector.project(prepareSalesProducts(repriced), stock)
            assertSame(initial.optionsByProduct[products[1].productId], prices.optionsByProduct[products[1].productId])
            assertEquals(fullProjectionBeforeOptimization(repriced, stock).second, prices.orderedOptions)
            val reordered = projector.project(prepareSalesProducts(repriced), stock.reversed())
            assertEquals(fullProjectionBeforeOptimization(repriced, stock.reversed()).second, reordered.orderedOptions)
            assertNotEquals(prices.orderedOptions, reordered.orderedOptions)
            val depleted = stock.map { it.copy(positions = it.positions.map { position -> position.copy(quantityOnHand = BigDecimal.ZERO) }) }
            assertTrue(projector.project(prepareSalesProducts(repriced), depleted).orderedOptions.isEmpty())
            assertEquals(initial.orderedOptions, projector.project(prepareSalesProducts(products), stock).orderedOptions)
        }

    @Test
    fun `cancelled preparation does not publish a partially replaced cache`() =
        runTest {
            val products = (1..256).map { product(it, "Producto $it") }
            val catalog = prepareSalesProducts(products)
            val stock = products.map { inventory(it, "10") }
            val projector = SalesCatalogProjector()
            val before = projector.project(catalog, stock)
            val job = Job()
            val cancellingStock =
                object : AbstractList<InventoryReadItem>() {
                    override val size = stock.size

                    override fun get(index: Int): InventoryReadItem {
                        if (index == 80) job.cancel()
                        return stock[index].copy(positions = stock[index].positions.map { it.copy(quantityOnHand = BigDecimal.ONE) })
                    }
                }
            var cancelled = false
            try {
                withContext(job) { projector.project(catalog, cancellingStock) }
            } catch (_: CancellationException) {
                cancelled = true
            }
            assertTrue(cancelled)
            val after = projector.project(catalog, stock)
            assertSame(before.orderedOptions, after.orderedOptions)
            assertSame(before.optionsByProduct, after.optionsByProduct)
        }

    @Test
    fun `diagnostic alternates full and incremental stock projection without timing thresholds`() =
        runTest {
            for (size in listOf(1_000, 10_000)) {
                val products = (1..size).map { product(it, "Producto ${size - it}") }
                val catalog = prepareSalesProducts(products)
                val stock = products.map { inventory(it, "10", singleLocation = true) }
                val projector = SalesCatalogProjector()
                projector.project(catalog, stock)
                val fullNanos = mutableListOf<Long>()
                val incrementalNanos = mutableListOf<Long>()
                repeat(10) { iteration ->
                    val changed =
                        stock.mapIndexed { index, item ->
                            if (index == 0) {
                                item.copy(positions = item.positions.map { it.copy(quantityOnHand = BigDecimal(iteration + 1)) })
                            } else {
                                item
                            }
                        }
                    var reference: List<SalesContract.ProductOption> = emptyList()
                    var incremental: List<SalesContract.ProductOption> = emptyList()
                    var fullTime = 0L
                    var incrementalTime = 0L

                    suspend fun runFull() {
                        fullTime = measureNanoTime { reference = fullProjectionBeforeOptimization(products, changed).second }
                    }

                    suspend fun runIncremental() {
                        incrementalTime = measureNanoTime { incremental = projector.project(catalog, changed).orderedOptions }
                    }
                    if (iteration % 2 == 0) {
                        runFull()
                        runIncremental()
                    } else {
                        runIncremental()
                        runFull()
                    }
                    assertEquals(reference, incremental)
                    if (iteration >= 2) {
                        fullNanos += fullTime
                        incrementalNanos += incrementalTime
                    }
                }
                println("Sales stock projection diagnostic (JVM, ns, $size products): previous=$fullNanos; incremental=$incrementalNanos; excludes one-time catalog preparation; no device/FPS claim")
            }
        }

    // Reference copied from the previous full-rebuild pipeline, intentionally independent of
    // the production incremental builder and its comparators.
    private fun fullProjectionBeforeOptimization(
        products: List<Product>,
        inventory: List<InventoryReadItem>,
    ): Pair<Map<ProductId, List<SalesContract.ProductOption>>, List<SalesContract.ProductOption>> {
        val byId = products.associateBy(Product::productId)
        val rows =
            inventory
                .associateBy(InventoryReadItem::productId)
                .values
                .flatMap { item ->
                    val product = byId[item.productId]?.takeIf { it.status == CatalogStatus.ACTIVE }
                    if (product == null) {
                        emptyList()
                    } else {
                        item.positions
                            .filter {
                                it.quantityOnHand.signum() > 0 && InventoryDataAlert.ARCHIVED_LOCATION !in it.alerts
                            }.map { position ->
                                SalesContract.ProductOption(
                                    product.productId,
                                    product.name,
                                    position.locationId,
                                    position.locationName,
                                    item.unitSymbol?.takeIf(String::isNotBlank) ?: item.unitCode,
                                    position.quantityOnHand,
                                    product.sku,
                                    product.barcode,
                                    product.salePrice,
                                    isWeightProduct = product.barcode == null && WeightSaleCalculator.isKilogramUnit(item.unitCode),
                                )
                            }
                    }
                }.groupBy { it.productId }
                .mapValues { (_, options) ->
                    options.sortedWith(
                        compareBy<SalesContract.ProductOption> { it.locationName.lowercase(Locale.ROOT) }.thenBy { it.locationId.value },
                    )
                }
        return rows to
            rows.values.flatten().sortedWith(
                compareBy<SalesContract.ProductOption> { it.productName.lowercase(Locale.ROOT) }.thenBy { it.locationName.lowercase(Locale.ROOT) },
            )
    }

    private fun product(
        id: Int,
        name: String,
    ) = Product(
        ProductId.from(UUID(0, id.toLong())),
        BUSINESS,
        UNIT,
        name,
        salePrice = Money.ofMinor(200, CURRENCY),
        createdAt = NOW,
        updatedAt = NOW,
    )

    private fun inventory(
        product: Product,
        quantity: String,
        singleLocation: Boolean = false,
    ) = InventoryReadItem(
        product.productId,
        BUSINESS,
        product.name,
        product.sku,
        "NIU",
        "un",
        (if (singleLocation) listOf(1) else listOf(2, 1)).map { index ->
            InventoryReadPosition(
                LocationId.from(UUID(0, index.toLong())),
                "Almacén $index",
                BigDecimal(quantity),
                InventoryCostAmount(BigDecimal.ONE, CURRENCY),
                0,
                NOW,
            )
        },
    )

    private companion object {
        val BUSINESS = BusinessId.from(UUID(0, 1))
        val UNIT = UnitId.from(UUID(0, 1))
        val CURRENCY = CurrencyCode.of("PEN")
        val NOW = Instant.parse("2026-09-12T12:00:00Z")
    }
}
