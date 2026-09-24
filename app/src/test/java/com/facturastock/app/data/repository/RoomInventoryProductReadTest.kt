package com.facturastock.app.data.repository

import com.facturastock.app.data.local.RecordingSQLiteDriver
import java.io.File
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import com.facturastock.app.data.local.readableSql
import androidx.room.RoomDatabase
import com.facturastock.app.data.local.withTransaction
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.entity.BusinessEntity
import com.facturastock.app.data.local.entity.InventoryBalanceEntity
import com.facturastock.app.data.local.entity.InventoryLocationEntity
import com.facturastock.app.data.local.entity.ProductEntity
import com.facturastock.app.data.local.entity.StockMovementEntity
import com.facturastock.app.data.local.entity.UnitEntity
import com.facturastock.app.domain.model.InventoryDataAlert
import com.facturastock.app.domain.model.InventoryProductDetail
import com.facturastock.app.domain.model.InventoryReadItem
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Fixtures de lectura sintéticos: verifica SQL y emisiones, sin depender de tiempos de benchmark. */
class RoomInventoryProductReadTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var database: FacturaStockDatabase
    private lateinit var repository: RoomInventoryReadRepository
    private val queries = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() =
        runBlocking {
            database =
                FacturaStockDatabase.buildAt(
                    databaseFile = File(tempFolder.root, "recorded.db"),
                    driver = RecordingSQLiteDriver { recorded -> queries += recorded.sql },
                )
            repository = RoomInventoryReadRepository(database, AppClock { Instant.EPOCH }, testDispatchers)
            database.withTransaction {
                database.businessDao().insert(BusinessEntity(BUSINESS.value, "Principal", 1L, 1L))
                database.businessDao().insert(BusinessEntity(OTHER_BUSINESS.value, "Otro", 1L, 1L))
                database.unitDao().insert(unit())
                database.inventoryLocationDao().insert(location())
                database.inventoryLocationDao().insert(location(SECOND_LOCATION, "Reserva", 100L))
                database.productDao().insert(product())
                database.productDao().insert(product(EMPTY_PRODUCT))
                database.inventoryDao().insertBalanceIfAbsent(balance())
            }
            queries.clear()
        }

    @After
    fun tearDown() = database.close()

    @Test
    fun itemMatchesFullDetailWithMultipleLocationsAnd1500MovementsWithoutReadingHistory() =
        runBlocking {
            database.withTransaction {
                database.inventoryDao().insertBalanceIfAbsent(
                    balance().copy(
                        locationId = SECOND_LOCATION,
                        quantityOnHand = "-0.000000000000000000000000000000000001",
                        averageUnitCost = "123456.123456789012345678123456789012345678",
                        currencyCode = "USD",
                    ),
                )
                database.inventoryLocationDao().update(location(SECOND_LOCATION, "Reserva", 100L).copy(status = "ARCHIVED"))
                database.inventoryDao().insertMovements((0 until 1_500).map(::movement))
            }
            val detail = requireNotNull(repository.observeProduct(BUSINESS, PRODUCT).first())
            assertEquals(1_500, detail.movements.size)
            queries.clear()

            val item = requireNotNull(repository.observeProductItem(BUSINESS, PRODUCT).first())
            assertEquals(detail.item, item)
            assertEquals(2, item.positions.size)
            assertTrue(InventoryDataAlert.NEGATIVE_STOCK in item.allAlerts)
            assertTrue(InventoryDataAlert.MIXED_CURRENCIES in item.allAlerts)
            assertTrue(InventoryDataAlert.ARCHIVED_LOCATION in item.allAlerts)
            val itemReads = queries.filter { it.startsWith("SELECT p.businessId") }
            assertEquals(1, itemReads.size)
            assertFalse(queries.any { it.startsWith("SELECT") && it.contains("stock_movements") })
            assertFalse(queries.any { it.startsWith("SELECT") && it.contains("FROM purchases") })
            database.readableSql
                .query(
                    "EXPLAIN QUERY PLAN ${itemReads.single()}",
                    arrayOf<Any>(BUSINESS.value, PRODUCT.value),
                ).use { cursor ->
                    val plan = buildList { while (cursor.moveToNext()) add(cursor.getString(3)!!) }
                    assertTrue(plan.any { it.contains("SEARCH") && it.contains("sqlite_autoindex_products_1") })
                    assertFalse(plan.any { it.contains("SCAN p") || it.contains("SCAN b") })
                }
        }

    @Test
    fun itemPreservesEmptyProductsMissingIdsAndTenantBoundary() =
        runBlocking {
            val empty = requireNotNull(repository.observeProductItem(BUSINESS, EMPTY_PRODUCT).first())
            assertEquals(repository.observeProduct(BUSINESS, EMPTY_PRODUCT).first()?.item, empty)
            assertTrue(empty.positions.isEmpty())
            assertNull(repository.observeProductItem(OTHER_BUSINESS, PRODUCT).first())
            assertNull(repository.observeProductItem(BUSINESS, ProductId.from(UUID(0L, 999L))).first())
        }

    @Test
    fun itemEmitsBalanceUnitLocationAndProductChangesWithoutLoadingMovements() =
        runBlocking {
            val emissions = Channel<InventoryReadItem?>(Channel.UNLIMITED)
            val collector = launch { repository.observeProductItem(BUSINESS, PRODUCT).collect(emissions::send) }
            try {
                assertEquals(BigDecimal("3"), emissions.nextItem().totalQuantityOnHand)
                queries.clear()
                // Room invalida toda la tabla; el producto ajeno y una escritura idéntica no deben emitirse.
                database.unitDao().update(unit())
                database.inventoryDao().insertBalanceIfAbsent(balance().copy(productId = EMPTY_PRODUCT.value))
                assertEquals(
                    1,
                    database.inventoryDao().updateBalanceIfVersion(
                        BUSINESS.value,
                        PRODUCT.value,
                        LOCATION,
                        0L,
                        "2",
                        "4.125",
                        "PEN",
                        1L,
                    ),
                )
                val changedBalance = emissions.nextItem()
                assertEquals(BigDecimal("2"), changedBalance.totalQuantityOnHand)
                assertEquals(
                    BigDecimal("4.125"),
                    changedBalance.positions
                        .single()
                        .averageUnitCost.amount,
                )

                // Los timestamps son deliberadamente iguales: no sirven como firma del contenido.
                database.unitDao().update(unit().copy(symbol = "ud"))
                assertEquals("ud", emissions.nextItem().unitSymbol)
                database.inventoryLocationDao().update(location().copy(name = "Mostrador", status = "ARCHIVED"))
                val changedLocation = emissions.nextItem()
                assertEquals("Mostrador", changedLocation.positions.single().locationName)
                assertTrue(InventoryDataAlert.ARCHIVED_LOCATION in changedLocation.allAlerts)

                assertEquals(
                    1,
                    database.productDao().update(
                        productId = PRODUCT.value,
                        unitId = UNIT,
                        name = "Actualizado",
                        normalizedName = "actualizado",
                        locationId = LOCATION,
                        sku = "NUEVO",
                        barcode = null,
                        purchaseUnitId = null,
                        purchaseFactor = null,
                        salePriceMinorUnits = null,
                        salePriceCurrencyCode = null,
                        status = "ARCHIVED",
                        expectedVersion = 1L,
                        updatedAt = 1L,
                    ),
                )
                val changedProduct = emissions.nextItem()
                assertEquals("Actualizado", changedProduct.productName)
                assertEquals("NUEVO", changedProduct.sku)
                assertTrue(InventoryDataAlert.ARCHIVED_PRODUCT in changedProduct.allAlerts)
                assertFalse(queries.any { it.startsWith("SELECT") && it.contains("stock_movements") })
            } finally {
                collector.cancelAndJoin()
                emissions.close()
            }
        }

    @Test
    fun fullDetailStillEmitsChangedContentsWhenRevisionCountsAndMaximumTimestampsAreIdentical() =
        runBlocking {
            database.inventoryDao().insertBalanceIfAbsent(balance().copy(locationId = SECOND_LOCATION))
            database.inventoryDao().insertMovements(listOf(movement(0)))
            val emissions = Channel<InventoryProductDetail?>(Channel.UNLIMITED)
            val collector = launch { repository.observeProduct(BUSINESS, PRODUCT).distinctUntilChanged().collect(emissions::send) }
            try {
                assertEquals(1, emissions.nextDetail().movements.size)
                val revision = database.inventoryDao().observeProductRevision(BUSINESS.value, PRODUCT.value).first()
                database.unitDao().update(unit())
                database.inventoryDao().insertBalanceIfAbsent(balance().copy(productId = EMPTY_PRODUCT.value))
                database.unitDao().update(unit().copy(symbol = "ud"))
                assertEquals("ud", emissions.nextDetail().item.unitSymbol)
                assertEquals(revision, database.inventoryDao().observeProductRevision(BUSINESS.value, PRODUCT.value).first())

                database.inventoryLocationDao().update(location().copy(name = "Mostrador"))
                val locationChanged = emissions.nextDetail()
                assertEquals("Mostrador", locationChanged.movements.single().locationName)
                assertEquals(
                    "Mostrador",
                    locationChanged.item.positions
                        .first { it.locationId == LocationId.parse(LOCATION) }
                        .locationName,
                )
                assertEquals(revision, database.inventoryDao().observeProductRevision(BUSINESS.value, PRODUCT.value).first())

                database.inventoryDao().updateBalanceIfVersion(BUSINESS.value, PRODUCT.value, LOCATION, 0L, "2", "3.5", "PEN", 1L)
                assertEquals(BigDecimal("5"), emissions.nextDetail().item.totalQuantityOnHand)
                assertEquals(revision, database.inventoryDao().observeProductRevision(BUSINESS.value, PRODUCT.value).first())

                database.inventoryDao().insertMovements(listOf(movement(1)))
                assertEquals(2, emissions.nextDetail().movements.size)
            } finally {
                collector.cancelAndJoin()
                emissions.close()
            }
        }

    private suspend fun Channel<InventoryReadItem?>.nextItem() = requireNotNull(withTimeout(5_000) { receive() })

    private suspend fun Channel<InventoryProductDetail?>.nextDetail() = requireNotNull(withTimeout(5_000) { receive() })

    private fun unit() = UnitEntity(UNIT, BUSINESS.value, "NIU", "Unidad", 1L, 1L)

    private fun location(
        id: String = LOCATION,
        name: String = "Principal",
        updatedAt: Long = 1L,
    ) = InventoryLocationEntity(id, BUSINESS.value, name, 1L, updatedAt)

    private fun product(id: ProductId = PRODUCT) = ProductEntity(id.value, BUSINESS.value, UNIT, "Producto ${id.value}", 1L, 1L)

    private fun balance() = InventoryBalanceEntity(BUSINESS.value, PRODUCT.value, LOCATION, "3", "3.5", "PEN", 0L, 1L)

    private fun movement(index: Int) =
        StockMovementEntity(
            movementId = UUID(10L, index.toLong()).toString(),
            businessId = BUSINESS.value,
            productId = PRODUCT.value,
            locationId = LOCATION,
            type = "ADJUSTMENT",
            quantityDelta = "1",
            unitCost = "3.5",
            currencyCode = "PEN",
            idempotencyKey = "read-$index",
            occurredAt = 1L,
            createdAt = 1L,
        )

    private companion object {
        val BUSINESS = BusinessId.from(UUID(0L, 1L))
        val OTHER_BUSINESS = BusinessId.from(UUID(0L, 2L))
        val PRODUCT = ProductId.from(UUID(0L, 3L))
        val EMPTY_PRODUCT = ProductId.from(UUID(0L, 4L))
        val UNIT = UUID(0L, 5L).toString()
        val LOCATION = UUID(0L, 6L).toString()
        val SECOND_LOCATION = UUID(0L, 7L).toString()
    }
}
