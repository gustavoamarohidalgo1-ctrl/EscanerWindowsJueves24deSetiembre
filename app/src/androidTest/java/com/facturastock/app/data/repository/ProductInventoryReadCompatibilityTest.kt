package com.facturastock.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.entity.BusinessEntity
import com.facturastock.app.data.local.entity.InventoryBalanceEntity
import com.facturastock.app.data.local.entity.InventoryLocationEntity
import com.facturastock.app.data.local.entity.ProductEntity
import com.facturastock.app.data.local.entity.UnitEntity
import com.facturastock.app.data.local.postingPersistenceCallback
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.InventoryDataAlert
import com.facturastock.app.domain.model.InventoryReadItem
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.repository.ProductEditingResult
import com.facturastock.app.domain.repository.ProductStockEdit
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/** Lee valores históricos válidos y cuenta SQL real; nunca depende de tiempos de ejecución. */
@RunWith(AndroidJUnit4::class)
class ProductInventoryReadCompatibilityTest {
    private lateinit var database: FacturaStockDatabase
    private lateinit var stock: RoomProductInventoryRepository
    private lateinit var editor: RoomProductEditingRepository
    private val queries = CopyOnWriteArrayList<String>()
    private val clock = TestClock(Instant.parse("2026-09-05T20:00:00Z"))
    private val ids = UuidGenerator { UUID.randomUUID() }

    @Before
    fun setUp() =
        runBlocking {
            database =
                Room
                    .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), FacturaStockDatabase::class.java)
                    .allowMainThreadQueries()
                    .addCallback(postingPersistenceCallback)
                    .setQueryCallback(
                        object : RoomDatabase.QueryCallback {
                            override fun onQuery(
                                sqlQuery: String,
                                bindArgs: List<Any?>,
                            ) {
                                queries += sqlQuery
                            }
                        },
                        Executor { it.run() },
                    ).build()
            val now = clock.now().toEpochMilli()
            database.businessDao().insert(BusinessEntity(BUSINESS.value, "Negocio", now, now))
            database.unitDao().insert(UnitEntity(UNIT, BUSINESS.value, "NIU", "Unidad", now, now))
            database.inventoryLocationDao().insert(InventoryLocationEntity(LOCATION.value, BUSINESS.value, "Principal", now, now))
            database.productDao().insert(ProductEntity(PRODUCT.value, BUSINESS.value, UNIT, "Producto", now, now, locationId = LOCATION.value))
            stock = RoomProductInventoryRepository(database, database.inventoryDao(), testDispatchers, clock, ids)
            editor = RoomProductEditingRepository(database, testDispatchers, clock, ids)
            queries.clear()
        }

    @After
    fun tearDown() = database.close()

    @Test
    fun summaryReadsDerivedPrecisionWithoutRoundingOrChangingPersistedRows() =
        runBlocking {
            val cost = "123456.123456789012345678123456789012345678"
            val before =
                InventoryBalanceEntity(
                    BUSINESS.value,
                    PRODUCT.value,
                    LOCATION.value,
                    "2.123456789012345678123456789012345678",
                    cost,
                    PEN.value,
                    0L,
                    clock.now().toEpochMilli(),
                )
            database.inventoryDao().insertBalanceIfAbsent(before)
            val summary = stock.summaryForProduct(BUSINESS, PRODUCT)

            assertEquals(
                cost,
                summary.positions
                    .single()
                    .averageUnitCost.amount
                    .toPlainString(),
            )
            assertEquals(cost, summary.averageUnitCost?.amount?.toPlainString())
            assertEquals(before.quantityOnHand, summary.totalQuantityOnHand.toPlainString())
            assertEquals(before, database.inventoryDao().findBalance(BUSINESS.value, PRODUCT.value, LOCATION.value))
            assertEquals(0, database.inventoryDao().listReadMovementsForProduct(BUSINESS.value, PRODUCT.value).size)
        }

    @Test
    fun snapshotSkipsHistoryForAbsentBalancesAndForNonzeroStoredCosts() =
        runBlocking {
            val absent = requireNotNull(editor.load(BUSINESS, PRODUCT, PEN)).positions.single()
            assertNull(absent.balanceVersion)
            assertNull(absent.averageUnitCost)
            assertEquals(0, historyQueryCount())

            stock.addStock(BUSINESS, PRODUCT, LOCATION, BigDecimal.TEN, PEN, BigDecimal("2.125"), "read-known")
            queries.clear()
            val known = requireNotNull(editor.load(BUSINESS, PRODUCT, PEN)).positions.single()
            assertEquals(BigDecimal.TEN, known.quantityOnHand)
            assertEquals(0, BigDecimal("2.125").compareTo(requireNotNull(known.averageUnitCost)))
            assertEquals(0L, known.balanceVersion)
            assertEquals(0, historyQueryCount())
        }

    @Test
    fun zeroStoredCostStillReadsHistoryAndDistinguishesUnknownFromConfirmedZero() =
        runBlocking {
            stock.addStock(BUSINESS, PRODUCT, LOCATION, BigDecimal("3"), PEN, null, "read-unknown")
            queries.clear()
            val unknown = requireNotNull(editor.load(BUSINESS, PRODUCT, PEN))
            assertNull(unknown.positions.single().averageUnitCost)
            assertEquals(1, historyQueryCount())

            val saved =
                editor.save(
                    unknown,
                    unknown.product,
                    listOf(ProductStockEdit(LOCATION, BigDecimal("3"), BigDecimal.ZERO)),
                ) as ProductEditingResult.Saved
            queries.clear()
            val knownZero = requireNotNull(editor.load(BUSINESS, PRODUCT, PEN))
            assertEquals(BigDecimal.ZERO, knownZero.positions.single().averageUnitCost)
            assertEquals(saved.snapshot, knownZero)
            assertEquals(1, historyQueryCount())
        }

    @Test
    fun productWithoutBalancesIsVisibleAtZeroWithoutInventingCostOrWritingData() =
        runBlocking {
            val otherBusiness = BusinessId.from(UUID(0L, 21L))
            val otherUnit = UUID(0L, 22L).toString()
            val otherProduct = ProductId.from(UUID(0L, 23L))
            val now = clock.now().toEpochMilli()
            database.businessDao().insert(BusinessEntity(otherBusiness.value, "Otro negocio", now, now))
            database.unitDao().insert(UnitEntity(otherUnit, otherBusiness.value, "NIU", "Unidad", now, now))
            database.productDao().insert(ProductEntity(otherProduct.value, otherBusiness.value, otherUnit, "Ajeno", now, now))
            val before = database.productDao().findById(PRODUCT.value)
            val inventory = RoomInventoryReadRepository(database, clock, testDispatchers)

            val item = inventory.observeInventory(BUSINESS).first().single()
            assertEquals(PRODUCT, item.productId)
            assertEquals(BUSINESS, item.businessId)
            assertEquals("Producto", item.productName)
            assertEquals(0, BigDecimal.ZERO.compareTo(item.totalQuantityOnHand))
            assertTrue(item.positions.isEmpty())
            assertTrue(item.averageUnitCostsByCurrency.isEmpty())
            assertTrue(item.estimatedValuesByCurrency.isEmpty())
            assertEquals(
                otherProduct,
                inventory
                    .observeInventory(otherBusiness)
                    .first()
                    .single()
                    .productId,
            )
            assertNull(database.inventoryDao().findBalance(BUSINESS.value, PRODUCT.value, LOCATION.value))
            assertTrue(database.inventoryDao().listReadMovementsForProduct(BUSINESS.value, PRODUCT.value).isEmpty())
            assertEquals(before, database.productDao().findById(PRODUCT.value))
        }

    @Test
    fun inventoryObserverStillEmitsCostQuantityAndArchiveChangesOnTheSameSubscription() =
        runBlocking {
            val updates = Channel<List<InventoryReadItem>>(Channel.UNLIMITED)
            val inventory = RoomInventoryReadRepository(database, clock, testDispatchers)
            val observation = launch { inventory.observeInventory(BUSINESS).collect { updates.send(it) } }
            try {
                val withoutBalance = withTimeout(5_000) { updates.receive() }.single()
                assertEquals(PRODUCT, withoutBalance.productId)
                assertTrue(withoutBalance.positions.isEmpty())
                assertEquals(0, BigDecimal.ZERO.compareTo(withoutBalance.totalQuantityOnHand))
                stock.addStock(BUSINESS, PRODUCT, LOCATION, BigDecimal("3"), PEN, BigDecimal("2"), "observer-initial")
                val initial = withTimeout(5_000) { updates.receive() }.single().positions.single()
                assertEquals(0, initial.quantityOnHand.compareTo(BigDecimal("3")))
                assertEquals(0, initial.averageUnitCost.amount.compareTo(BigDecimal("2")))

                val original = requireNotNull(editor.load(BUSINESS, PRODUCT, PEN))
                val costSaved =
                    editor.save(
                        original,
                        original.product,
                        listOf(ProductStockEdit(LOCATION, BigDecimal("3"), BigDecimal("4"))),
                    ) as ProductEditingResult.Saved
                val changedCost = withTimeout(5_000) { updates.receive() }.single().positions.single()
                assertEquals(0, changedCost.quantityOnHand.compareTo(BigDecimal("3")))
                assertEquals(0, changedCost.averageUnitCost.amount.compareTo(BigDecimal("4")))

                val quantitySaved =
                    editor.save(
                        costSaved.snapshot,
                        costSaved.snapshot.product,
                        listOf(ProductStockEdit(LOCATION, BigDecimal("5"), null)),
                    ) as ProductEditingResult.Saved
                val changedQuantity = withTimeout(5_000) { updates.receive() }.single().positions.single()
                assertEquals(0, changedQuantity.quantityOnHand.compareTo(BigDecimal("5")))
                assertEquals(0, changedQuantity.averageUnitCost.amount.compareTo(BigDecimal("4")))

                val depleted =
                    editor.save(
                        quantitySaved.snapshot,
                        quantitySaved.snapshot.product,
                        listOf(ProductStockEdit(LOCATION, BigDecimal.ZERO, null)),
                    ) as ProductEditingResult.Saved
                val zeroItem = withTimeout(5_000) { updates.receive() }.single()
                assertEquals(PRODUCT, zeroItem.productId)
                assertEquals(0, BigDecimal.ZERO.compareTo(zeroItem.totalQuantityOnHand))
                assertEquals(0, BigDecimal.ZERO.compareTo(zeroItem.positions.single().quantityOnHand))
                assertTrue(InventoryDataAlert.ARCHIVED_PRODUCT !in zeroItem.allAlerts)

                val replenished =
                    editor.save(
                        depleted.snapshot,
                        depleted.snapshot.product,
                        listOf(ProductStockEdit(LOCATION, BigDecimal("5"), null)),
                    ) as ProductEditingResult.Saved
                val replenishedItem = withTimeout(5_000) { updates.receive() }.single()
                assertEquals(0, BigDecimal("5").compareTo(replenishedItem.totalQuantityOnHand))

                val products = RoomProductRepository(database, database.productDao(), testDispatchers, clock)
                assertTrue(products.archive(BUSINESS, PRODUCT, replenished.snapshot.product.version))
                val archived = withTimeout(5_000) { updates.receive() }.single()
                assertTrue(InventoryDataAlert.ARCHIVED_PRODUCT in archived.allAlerts)
                assertEquals(replenishedItem.positions.single(), archived.positions.single())
            } finally {
                observation.cancelAndJoin()
                updates.close()
            }
        }

    private fun historyQueryCount() =
        queries.count {
            it.startsWith("SELECT m.movementId", ignoreCase = true) && it.contains("FROM stock_movements m", ignoreCase = true)
        }

    private companion object {
        val BUSINESS = BusinessId.from(UUID(0L, 1L))
        val PRODUCT = ProductId.from(UUID(0L, 2L))
        val LOCATION = LocationId.from(UUID(0L, 3L))
        val UNIT = UUID(0L, 4L).toString()
        val PEN = CurrencyCode.of("PEN")
    }
}
