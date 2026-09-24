package com.facturastock.app.data.repository

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.facturastock.app.core.coroutines.DefaultDispatcherProvider
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.dao.updateCas
import com.facturastock.app.data.local.entity.BusinessEntity
import com.facturastock.app.data.local.entity.InventoryBalanceEntity
import com.facturastock.app.data.local.entity.InventoryLocationEntity
import com.facturastock.app.data.local.entity.ProductEntity
import com.facturastock.app.data.local.entity.UnitEntity
import com.facturastock.app.domain.model.InventoryDataAlert
import com.facturastock.app.domain.model.InventoryReadItem
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.ProductId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil

/** Las escrituras de fixture omiten coordinadores; esta clase mide sólo el contrato de lectura. */
@RunWith(AndroidJUnit4::class)
class InventoryIncrementalReadPerformanceTest {
    private lateinit var database: FacturaStockDatabase
    private lateinit var repository: RoomInventoryReadRepository

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext<Context>(),
                    FacturaStockDatabase::class.java,
                ).build()
        repository = RoomInventoryReadRepository(database, AppClock { Instant.EPOCH }, DefaultDispatcherProvider())
    }

    @After
    fun tearDown() = database.close()

    /**
     * Compatible con el APK anterior: sólo usa APIs públicas preexistentes de Room y del
     * repositorio. No invoca materializadores nuevos ni exige reutilización o tiempos mejores.
     * Mide escritura + invalidación + consulta + entrega de la emisión, tras cinco calentamientos.
     */
    @Test
    fun benchmarkInventorySingleProductUpdates() =
        runBlocking {
            seed(BUSINESS, productCount = 2_000)
            val emissions = Channel<TimedInventory>(Channel.UNLIMITED)
            val initialStart = SystemClock.elapsedRealtimeNanos()
            val collector = collectInventory(repository.observeInventory(BUSINESS), emissions)
            try {
                val initial = emissions.next()
                val initialMs = (initial.receivedAtNanos - initialStart) / 1_000_000.0
                var previous = initial.items
                assertEquals(2_000, previous.size)
                assertEquals(6_000, previous.sumOf { it.positions.size })
                val warmupDurations = mutableListOf<Double>()
                val durations = mutableListOf<Double>()
                val reusedCounts = mutableListOf<Int>()
                repeat(WARMUPS + SAMPLES) { iteration ->
                    val startedAt = SystemClock.elapsedRealtimeNanos()
                    updateBalance(BUSINESS, productIndex = 1_000, quantity = (20 + iteration).toString())
                    val next = emissions.next()
                    val elapsedMs = (next.receivedAtNanos - startedAt) / 1_000_000.0
                    assertEquals(previous.map { it.productId }, next.items.map { it.productId })
                    val reused = next.items.indices.count { next.items[it] === previous[it] }
                    if (iteration >= WARMUPS) {
                        durations += elapsedMs
                        reusedCounts += reused
                    } else {
                        warmupDurations += elapsedMs
                    }
                    previous = next.items
                }
                val sorted = durations.sorted()
                val metrics =
                    buildString {
                        append("inventory_incremental products=2000 positions=6000 warmups=$WARMUPS samples=$SAMPLES")
                        append(" initial_ms=${initialMs.decimal()}")
                        append(" p50_ms=${sorted.percentile(0.50).decimal()}")
                        append(" p95_ms=${sorted.percentile(0.95).decimal()}")
                        append(" reused_mean=${reusedCounts.average().decimal()}")
                        append(" rebuilt_mean=${(2_000 - reusedCounts.average()).decimal()}")
                        append(" warmup_ms=${warmupDurations.joinToString(",") { it.decimal() }}")
                        append(" emission_ms=${durations.joinToString(",") { it.decimal() }}")
                        append(" reused=${reusedCounts.joinToString(",")}")
                    }
                Log.i("InventoryReadBenchmark", metrics)
                InstrumentationRegistry.getInstrumentation().sendStatus(
                    0,
                    Bundle().apply {
                        putString("stream", "$metrics\n")
                        putString("inventory_incremental_benchmark", metrics)
                    },
                )
            } finally {
                collector.cancelAndJoin()
                emissions.close()
            }
        }

    @Test
    fun unchangedProductsAreReusedWhileCompleteRowsOrderAndDeletionRemainExact() =
        runBlocking {
            seed(BUSINESS, productCount = 3)
            val emptyProduct = product(BUSINESS, 3)
            database.productDao().insert(emptyProduct)
            val emissions = Channel<TimedInventory>(Channel.UNLIMITED)
            val collector = collectInventory(repository.observeInventory(BUSINESS), emissions)
            try {
                val initial = emissions.next().items
                assertEquals(4, initial.size)
                assertTrue(initial.item(3).positions.isEmpty())
                assertEquivalentToSingleProductReads(initial)

                updateBalance(BUSINESS, 0, quantity = "12.123456789", cost = "1.234567890123456789", currency = "USD")
                val balanceChanged = emissions.next().items
                assertNotSame(initial.item(0), balanceChanged.item(0))
                assertSame(initial.item(1), balanceChanged.item(1))
                assertSame(initial.item(2), balanceChanged.item(2))
                assertSame(initial.item(3), balanceChanged.item(3))
                assertEquals(
                    BigDecimal("10.125"),
                    initial
                        .item(0)
                        .positions
                        .first()
                        .quantityOnHand,
                )
                assertEquals(
                    BigDecimal("12.123456789"),
                    balanceChanged
                        .item(0)
                        .positions
                        .first()
                        .quantityOnHand,
                )
                assertEquals(
                    BigDecimal("1.234567890123456789"),
                    balanceChanged
                        .item(0)
                        .positions
                        .first()
                        .averageUnitCost.amount,
                )
                assertTrue(InventoryDataAlert.MIXED_CURRENCIES in balanceChanged.item(0).allAlerts)
                assertEquivalentToSingleProductReads(balanceChanged)

                val changedProduct = requireNotNull(database.productDao().findById(productId(BUSINESS, 1)))
                assertEquals(
                    1,
                    database.productDao().updateCas(
                        changedProduct.copy(
                            name = "AAA renombrado",
                            normalizedName = "aaa renombrado",
                            sku = "CAFE-001",
                            status = "ARCHIVED",
                        ),
                    ),
                )
                val renamed = emissions.next().items
                assertEquals(productId(BUSINESS, 1), renamed.first().productId.value)
                assertEquals("CAFE-001", renamed.first().sku)
                assertTrue(InventoryDataAlert.ARCHIVED_PRODUCT in renamed.first().alerts)
                assertSame(balanceChanged.item(0), renamed.item(0))
                assertEquivalentToSingleProductReads(renamed)

                val unit = requireNotNull(database.unitDao().findById(unitId(BUSINESS)))
                assertEquals(1, database.unitDao().update(unit.copy(symbol = "u.", updatedAt = 2L)))
                val unitChanged = emissions.next().items
                renamed.forEach { assertNotSame(it, unitChanged.single { next -> next.productId == it.productId }) }
                assertTrue(unitChanged.all { it.unitSymbol == "u." })
                assertEquivalentToSingleProductReads(unitChanged)

                val location = requireNotNull(database.inventoryLocationDao().findById(locationId(BUSINESS, 1)))
                assertEquals(
                    1,
                    database.inventoryLocationDao().update(
                        location.copy(
                            name = "AAA almacén",
                            status = "ARCHIVED",
                            updatedAt = 2L,
                        ),
                    ),
                )
                val locationChanged = emissions.next().items
                assertSame(unitChanged.item(3), locationChanged.item(3))
                assertEquals(
                    "AAA almacén",
                    locationChanged
                        .item(0)
                        .positions
                        .first()
                        .locationName,
                )
                assertTrue(
                    InventoryDataAlert.ARCHIVED_LOCATION in
                        locationChanged
                            .item(0)
                            .positions
                            .first()
                            .alerts,
                )
                assertEquivalentToSingleProductReads(locationChanged)

                database.withTransaction {
                    database.productDao().deleteUnusedBalances(BUSINESS.value, productId(BUSINESS, 2))
                    database.productDao().deleteById(productId(BUSINESS, 2))
                }
                val deleted = emissions.next().items
                assertEquals(3, deleted.size)
                assertFalse(deleted.any { it.productId.value == productId(BUSINESS, 2) })
                assertSame(locationChanged.item(0), deleted.item(0))
                assertEquivalentToSingleProductReads(deleted)

                database.productDao().deleteById(emptyProduct.productId)
                val withoutEmpty = emissions.next().items
                assertEquals(2, withoutEmpty.size)
                database.productDao().insert(emptyProduct)
                val restored = emissions.next().items
                assertEquals(deleted.item(3), restored.item(3))
                assertNotSame(deleted.item(3), restored.item(3))
                assertEquivalentToSingleProductReads(restored)
            } finally {
                collector.cancelAndJoin()
                emissions.close()
            }
        }

    @Test
    fun collectorsBusinessesAndCancellationDoNotShareMaterializedSnapshots() =
        runBlocking {
            seed(BUSINESS, productCount = 3)
            seed(OTHER_BUSINESS, productCount = 2)
            val flow = repository.observeInventory(BUSINESS)
            val firstEvents = Channel<TimedInventory>(Channel.UNLIMITED)
            val secondEvents = Channel<TimedInventory>(Channel.UNLIMITED)
            val otherEvents = Channel<TimedInventory>(Channel.UNLIMITED)
            val firstCollector = collectInventory(flow, firstEvents)
            val secondCollector = collectInventory(flow, secondEvents)
            val otherCollector = collectInventory(repository.observeInventory(OTHER_BUSINESS), otherEvents)
            try {
                val first = firstEvents.next().items
                val second = secondEvents.next().items
                val other = otherEvents.next().items
                assertEquals(first, second)
                first.indices.forEach { assertNotSame(first[it], second[it]) }

                updateBalance(OTHER_BUSINESS, 0, quantity = "19")
                val otherChanged = otherEvents.next().items
                assertNotSame(other.first(), otherChanged.first())
                assertSame(other[1], otherChanged[1])
                assertTrue(otherChanged.all { it.businessId == OTHER_BUSINESS })
                assertNull(withTimeoutOrNull(300) { firstEvents.receive() })
                assertNull(withTimeoutOrNull(300) { secondEvents.receive() })

                updateBalance(BUSINESS, 0, quantity = "25")
                val firstChanged = firstEvents.next().items
                val secondChanged = secondEvents.next().items
                assertEquals(firstChanged, secondChanged)
                assertSame(first.item(1), firstChanged.item(1))
                assertSame(second.item(1), secondChanged.item(1))
                assertNotSame(firstChanged.item(1), secondChanged.item(1))

                firstCollector.cancelAndJoin()
                updateBalance(BUSINESS, 1, quantity = "30")
                val continued = secondEvents.next().items
                assertSame(secondChanged.item(0), continued.item(0))
                assertNotSame(secondChanged.item(1), continued.item(1))
                val freshCollector = flow.first()
                assertEquals(continued, freshCollector)
                continued.indices.forEach { assertNotSame(continued[it], freshCollector[it]) }
                assertEquivalentToSingleProductReads(freshCollector)
            } finally {
                firstCollector.cancelAndJoin()
                secondCollector.cancelAndJoin()
                otherCollector.cancelAndJoin()
                firstEvents.close()
                secondEvents.close()
                otherEvents.close()
            }
        }

    private suspend fun assertEquivalentToSingleProductReads(items: List<InventoryReadItem>) {
        items.forEach { item ->
            val independent = requireNotNull(repository.observeProductItem(item.businessId, item.productId).first())
            assertEquals(independent, item)
            assertEquals(independent.totalQuantityOnHand, item.totalQuantityOnHand)
            assertEquals(independent.estimatedValuesByCurrency, item.estimatedValuesByCurrency)
            assertEquals(independent.averageUnitCostsByCurrency, item.averageUnitCostsByCurrency)
            assertEquals(independent.allAlerts, item.allAlerts)
        }
    }

    private suspend fun seed(
        business: BusinessId,
        productCount: Int,
    ) = database.withTransaction {
        database.businessDao().insert(BusinessEntity(business.value, "Negocio ${business.value}", 1L, 1L))
        database.unitDao().insert(UnitEntity(unitId(business), business.value, "NIU", "Unidad", 1L, 1L))
        repeat(3) { index ->
            database.inventoryLocationDao().insert(
                InventoryLocationEntity(
                    locationId(business, index),
                    business.value,
                    "Almacén $index",
                    1L,
                    1L,
                ),
            )
        }
        database.productDao().insertAll((0 until productCount).map { product(business, it) })
        repeat(productCount) { productIndex ->
            repeat(3) { locationIndex ->
                database.inventoryDao().insertBalanceIfAbsent(
                    InventoryBalanceEntity(
                        business.value,
                        productId(business, productIndex),
                        locationId(business, locationIndex),
                        "10.125",
                        "2.375",
                        "PEN",
                        version = 1L,
                        updatedAt = 1L,
                    ),
                )
            }
        }
    }

    private suspend fun updateBalance(
        business: BusinessId,
        productIndex: Int,
        quantity: String,
        cost: String = "2.375",
        currency: String = "PEN",
    ) {
        val productId = productId(business, productIndex)
        val locationId = locationId(business, 0)
        val balance = requireNotNull(database.inventoryDao().findBalance(business.value, productId, locationId))
        assertEquals(
            1,
            database.inventoryDao().updateBalanceIfVersion(
                business.value,
                productId,
                locationId,
                balance.version,
                quantity,
                cost,
                currency,
                balance.updatedAt + 1L,
            ),
        )
    }

    private fun product(
        business: BusinessId,
        index: Int,
    ) = ProductEntity(
        productId(business, index),
        business.value,
        unitId(business),
        "Producto ${index.toString().padStart(5, '0')}",
        1L,
        1L,
    )

    private fun productId(
        business: BusinessId,
        index: Int,
    ) = UUID(businessMarker(business) + 100L, index.toLong()).toString()

    private fun unitId(business: BusinessId) = UUID(businessMarker(business) + 200L, 0L).toString()

    private fun locationId(
        business: BusinessId,
        index: Int,
    ) = UUID(businessMarker(business) + 300L, index.toLong()).toString()

    private fun businessMarker(business: BusinessId) = UUID.fromString(business.value).leastSignificantBits * 1_000L

    private fun List<InventoryReadItem>.item(index: Int) = single { it.productId == ProductId.from(UUID.fromString(productId(BUSINESS, index))) }

    private fun Double.decimal() = String.format(Locale.ROOT, "%.3f", this)

    private fun List<Double>.percentile(fraction: Double) = this[(ceil(size * fraction).toInt() - 1).coerceIn(indices)]

    private suspend fun Channel<TimedInventory>.next() = withTimeout(30_000) { receive() }

    private fun CoroutineScope.collectInventory(
        flow: Flow<List<InventoryReadItem>>,
        destination: Channel<TimedInventory>,
    ): Job =
        launch {
            flow.collect { destination.send(TimedInventory(it, SystemClock.elapsedRealtimeNanos())) }
        }

    private data class TimedInventory(
        val items: List<InventoryReadItem>,
        val receivedAtNanos: Long,
    )

    private companion object {
        val BUSINESS = BusinessId.from(UUID(0L, 81L))
        val OTHER_BUSINESS = BusinessId.from(UUID(0L, 82L))
        const val WARMUPS = 5
        const val SAMPLES = 20
    }
}
