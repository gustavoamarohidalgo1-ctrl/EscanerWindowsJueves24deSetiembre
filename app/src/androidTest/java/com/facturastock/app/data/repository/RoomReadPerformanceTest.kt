package com.facturastock.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.facturastock.app.core.coroutines.DefaultDispatcherProvider
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.dao.ProductDao
import com.facturastock.app.data.local.dao.ProductProfitReadRow
import com.facturastock.app.data.local.entity.BusinessEntity
import com.facturastock.app.data.local.entity.InventoryLocationEntity
import com.facturastock.app.data.local.entity.ProductEntity
import com.facturastock.app.data.local.entity.SaleEntity
import com.facturastock.app.data.local.entity.SaleLineEntity
import com.facturastock.app.data.local.entity.UnitEntity
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.id.BusinessId
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/** Contratos de lectura; los fixtures SQL no sustituyen las pruebas de checkout con triggers. */
@RunWith(AndroidJUnit4::class)
class RoomReadPerformanceTest {
    private lateinit var database: FacturaStockDatabase
    private val queries = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() =
        runBlocking {
            database =
                Room
                    .inMemoryDatabaseBuilder(
                        ApplicationProvider.getApplicationContext<Context>(),
                        FacturaStockDatabase::class.java,
                    ).setQueryCallback(
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
            database.businessDao().insert(BusinessEntity(BUSINESS.value, "Principal", 1L, 1L))
            database.businessDao().insert(BusinessEntity(OTHER_BUSINESS.value, "Otro", 1L, 1L))
            database.unitDao().insert(UnitEntity(UNIT, BUSINESS.value, "NIU", "Unidad", 1L, 1L))
            database.inventoryLocationDao().insert(
                InventoryLocationEntity(LOCATION, BUSINESS.value, "Principal", 1L, 1L),
            )
            database.productDao().insertAll((0..2).map(::product))
        }

    @After
    fun tearDown() = database.close()

    @Test
    fun recentSalesKeepOrderCountsLimitsAndReactivityWithoutSortingTheFullHistory() =
        runBlocking {
            repeat(75) { index -> insertSale(index, postedAt = 100L + index / 3, lineCount = index % 3 + 1) }
            insertSale(100, postedAt = 1_000L, lineCount = 0)
            insertSale(101, postedAt = 1_001L, lineCount = 1, posted = false)
            insertSale(102, postedAt = 1_002L, lineCount = 0, business = OTHER_BUSINESS)
            val dao = database.saleDao()

            for (limit in listOf(1, 20, 50)) {
                val rows = dao.observeRecentPosted(BUSINESS.value, limit).first()
                val expectedIndices =
                    (0 until 75)
                        .sortedWith(
                            compareByDescending<Int> { 100L + it / 3 }.thenByDescending { saleId(it) },
                        ).take(limit)
                assertEquals(expectedIndices.map(::saleId), rows.map { it.saleId })
                assertEquals(expectedIndices.map { it % 3 + 1 }, rows.map { it.lineCount })
                assertEquals(expectedIndices.map { (it % 3 + 1) * 100L }, rows.map { it.totalMinorUnits })
            }
            assertTrue(dao.observeRecentPosted(OTHER_BUSINESS.value, 50).first().isEmpty())
            val sql = queries.last { it.startsWith("SELECT s.saleId") && it.contains("AS lineCount") }
            database.openHelper.readableDatabase
                .query(
                    "EXPLAIN QUERY PLAN $sql",
                    arrayOf<Any>(BUSINESS.value, 20),
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        assertFalse(cursor.getString(3).contains("USE TEMP B-TREE"))
                    }
                }

            val emissions = Channel<List<com.facturastock.app.data.local.dao.PostedSaleSummaryRow>>(Channel.UNLIMITED)
            val collector = launch { dao.observeRecentPosted(BUSINESS.value, 20).collect(emissions::send) }
            try {
                assertEquals(20, withTimeout(5_000) { emissions.receive() }.size)
                insertSale(103, postedAt = 2_000L, lineCount = 2)
                val changed = withTimeout(5_000) { emissions.receive() }
                assertEquals(20, changed.size)
                assertEquals(saleId(103), changed.first().saleId)
                assertEquals(2, changed.first().lineCount)
            } finally {
                collector.cancelAndJoin()
                emissions.close()
            }
        }

    @Test
    fun identicalCatalogRowsSkipMaterializationWhileNamePriceAndStatusChangesStillEmit() =
        runBlocking {
            val rows = (0 until 1_000).map(::product)
            val initial = IterationCountingList(rows)
            val repeated = IterationCountingList(rows.map { it.copy() })
            val changed =
                IterationCountingList(
                    rows.mapIndexed { index, row ->
                        if (index == 500) {
                            row.copy(
                                name = "Nombre actualizado",
                                normalizedName = "nombre actualizado",
                                salePriceMinorUnits = 250L,
                                salePriceCurrencyCode = "PEN",
                                status = CatalogStatus.ARCHIVED.name,
                                version = 2L,
                            )
                        } else {
                            row
                        }
                    },
                )
            val dao =
                object : ProductDao by database.productDao() {
                    override fun observeForBusiness(businessId: String): Flow<List<ProductEntity>> = flowOf(initial, repeated, changed)
                }
            val products =
                RoomProductRepository(
                    database,
                    dao,
                    DefaultDispatcherProvider(),
                    AppClock { Instant.EPOCH },
                ).observeForBusiness(BUSINESS).toList()

            assertEquals(2, products.size)
            assertEquals(1_000, products.first().size)
            val updated = products.last()[500]
            assertEquals("Nombre actualizado", updated.name)
            assertEquals(250L, updated.salePrice?.minorUnits)
            assertEquals(CatalogStatus.ARCHIVED, updated.status)
            assertEquals(1, initial.iterations)
            assertEquals(0, repeated.iterations)
            assertEquals(1, changed.iterations)
        }

    @Test
    fun identicalProfitRowsSkipDecimalCalculationsWhileCostQuantityAndPriceChangesStillEmit() =
        runBlocking {
            val rows =
                (0 until 1_000).map { index ->
                    ProductProfitReadRow(
                        BUSINESS.value,
                        product(index).productId,
                        "Producto $index",
                        null,
                        "ACTIVE",
                        1L,
                        "NIU",
                        600L,
                        "PEN",
                        LOCATION,
                        "2",
                        "3.005",
                        "PEN",
                    )
                }
            val initial = IterationCountingList(rows)
            val repeated = IterationCountingList(rows.map { it.copy() })
            val costChanged =
                IterationCountingList(
                    rows.mapIndexed { index, row ->
                        if (index == 500) row.copy(averageUnitCost = "4.125") else row
                    },
                )
            val quantityChanged =
                IterationCountingList(
                    costChanged.values.mapIndexed { index, row ->
                        if (index == 500) row.copy(quantityOnHand = "3") else row
                    },
                )
            val priceChanged =
                IterationCountingList(
                    quantityChanged.values.mapIndexed { index, row ->
                        if (index == 500) row.copy(salePriceMinorUnits = 700L, productVersion = 2L) else row
                    },
                )
            val dao =
                object : ProductDao by database.productDao() {
                    override fun observeProfitRows(businessId: String): Flow<List<ProductProfitReadRow>> = flowOf(initial, repeated, costChanged, quantityChanged, priceChanged)
                }
            val profits =
                RoomProductProfitRepository(dao, DefaultDispatcherProvider())
                    .observeForBusiness(BUSINESS)
                    .toList()

            assertEquals(4, profits.size)
            assertEquals(1_000, profits.first().size)
            assertEquals(0, requireNotNull(profits[1][500].averageUnitCost).amount.compareTo(BigDecimal("4.125")))
            assertEquals(BigDecimal("3"), profits[2][500].totalStockQuantity)
            assertEquals(700L, profits[3][500].salePrice?.minorUnits)
            assertEquals(0, repeated.iterations)
            listOf(initial, costChanged, quantityChanged, priceChanged).forEach { assertEquals(1, it.iterations) }
        }

    private suspend fun insertSale(
        index: Int,
        postedAt: Long,
        lineCount: Int,
        posted: Boolean = true,
        business: BusinessId = BUSINESS,
    ) = database.withTransaction {
        val id = saleId(index)
        database.saleDao().insertSale(
            SaleEntity(
                id,
                business.value,
                if (posted) "POSTED" else "DRAFT",
                "PEN",
                lineCount * 100L,
                0L,
                0L,
                lineCount * 100L,
                "0".repeat(64),
                draftSlot = if (posted) null else "${business.value}:PEN",
                checkoutIdempotencyKey = if (posted) "read-sale-$index" else null,
                version = 1L,
                createdAt = 1L,
                updatedAt = postedAt,
                postedAt = postedAt.takeIf { posted },
            ),
        )
        database.saleDao().insertLines(
            (0 until lineCount).map { position ->
                SaleLineEntity(
                    UUID(30L + index, position.toLong()).toString(),
                    id,
                    product(position).productId,
                    UNIT,
                    LOCATION,
                    position,
                    "Producto $position",
                    "NIU",
                    "Principal",
                    quantity = "1",
                    unitPriceMinorUnits = 100L,
                    discountMinorUnits = 0L,
                    taxMinorUnits = 0L,
                    lineTotalMinorUnits = 100L,
                    currencyCode = "PEN",
                )
            },
        )
    }

    private fun product(index: Int) =
        ProductEntity(
            UUID(2L, index.toLong()).toString(),
            BUSINESS.value,
            UNIT,
            "Producto $index",
            1L,
            1L,
        )

    private fun saleId(index: Int) = UUID(20L, index.toLong()).toString()

    /** Cuenta recorridos de mapeo; equals conserva igualdad estructural sin contarlos. */
    private class IterationCountingList<T>(
        val values: List<T>,
    ) : List<T> by values {
        var iterations = 0
            private set

        override fun iterator(): Iterator<T> {
            iterations += 1
            return values.iterator()
        }

        override fun equals(other: Any?): Boolean =
            values ==
                if (other is IterationCountingList<*>) other.values else other

        override fun hashCode(): Int = values.hashCode()
    }

    private companion object {
        val BUSINESS = BusinessId.from(UUID(0L, 1L))
        val OTHER_BUSINESS = BusinessId.from(UUID(0L, 2L))
        val UNIT = UUID(0L, 3L).toString()
        val LOCATION = UUID(0L, 4L).toString()
    }
}
