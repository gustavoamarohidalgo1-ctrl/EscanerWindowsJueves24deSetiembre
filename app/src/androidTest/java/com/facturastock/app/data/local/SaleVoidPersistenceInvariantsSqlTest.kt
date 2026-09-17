package com.facturastock.app.data.local

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.facturastock.app.data.local.SaleVoidSqlFixture.Companion.BUSINESS_ID
import com.facturastock.app.data.local.SaleVoidSqlFixture.Companion.SALE_ID
import com.facturastock.app.data.local.dao.PostedSaleSummaryRow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SaleVoidPersistenceInvariantsSqlTest {
    private lateinit var database: FacturaStockDatabase
    private lateinit var sql: SupportSQLiteDatabase
    private lateinit var fixture: SaleVoidSqlFixture

    @Before
    fun setUp() {
        database =
            Room
                .inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext<Context>(),
                    FacturaStockDatabase::class.java,
                ).allowMainThreadQueries()
                .addCallback(postingPersistenceCallback)
                .build()
        sql = database.openHelper.writableDatabase
        fixture = SaleVoidSqlFixture(sql)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun receiptRequiresEveryInverseAndBalanceAndAuditBeforeSealingHistory() {
        fixture.seedPosted()
        val before = fixture.historicalRows()
        assertThrows(SQLiteException::class.java) { fixture.insertReceipt() }
        fixture.returnBalance(0)
        fixture.insertMovement(0)
        assertThrows(SQLiteException::class.java) { fixture.insertReceipt() }
        fixture.insertMovement(1)
        fixture.insertAudit()
        // A complete ledger alone cannot seal an unmaterialized inventory position.
        assertThrows(SQLiteException::class.java) { fixture.insertReceipt() }
        fixture.returnBalance(1)
        fixture.insertReceipt()
        assertEquals(before, fixture.historicalRows())
        assertCount("sale_voids", 1)
        assertCount("stock_movements", 4)
        sql.query("SELECT quantityOnHand FROM inventory_balances").use { cursor ->
            assertEquals(2, cursor.count)
            while (cursor.moveToNext()) assertEquals("10", cursor.getString(0))
        }
    }

    @Test
    fun inverseRejectsRoundedQuantityDifferentCostCurrencyProductAndKey() {
        fixture.seedPosted()
        // Numeric equality cannot discard the original ledger's decimal representation.
        assertThrows(SQLiteException::class.java) { fixture.insertMovement(0, quantity = "2") }
        assertThrows(SQLiteException::class.java) { fixture.insertMovement(0, quantity = "-2.000") }
        assertThrows(SQLiteException::class.java) { fixture.insertMovement(0, quantity = "3.000") }
        assertThrows(SQLiteException::class.java) { fixture.insertMovement(0, cost = "3.251") }
        assertThrows(SQLiteException::class.java) { fixture.insertMovement(0, currency = "USD") }
        assertThrows(SQLiteException::class.java) {
            fixture.insertMovement(0, productId = SaleVoidSqlFixture.productIds[1])
        }
        assertThrows(SQLiteException::class.java) { fixture.insertMovement(0, key = "arbitrary-key") }
        assertCount("stock_movements", 2)
        fixture.prepareCompleteReturn()
        fixture.insertReceipt()
    }

    @Test
    fun receiptRejectsInvalidMoneyAuthorityAndHashAndCannotBeChangedOrDuplicated() {
        fixture.seedPosted()
        fixture.prepareCompleteReturn()
        assertThrows(SQLiteException::class.java) { fixture.insertReceipt(refund = 300, cancelled = 0) }
        assertThrows(SQLiteException::class.java) { fixture.insertReceipt(refund = 76) }
        assertThrows(SQLiteException::class.java) { fixture.insertReceipt(cancelled = -1) }
        assertThrows(SQLiteException::class.java) { fixture.insertReceipt(currency = "USD") }
        assertThrows(SQLiteException::class.java) { fixture.insertReceipt(role = "OPERATOR") }
        assertThrows(SQLiteException::class.java) { fixture.insertReceipt(hash = "not-a-hash") }
        fixture.insertReceipt()
        assertThrows(SQLiteException::class.java) { fixture.insertReceipt() }
        assertThrows(SQLiteException::class.java) { fixture.insertReceipt(replace = true) }
        assertThrows(SQLiteException::class.java) { sql.execSQL("UPDATE sale_voids SET voidedAt=5") }
        assertThrows(SQLiteException::class.java) { sql.execSQL("DELETE FROM sale_voids") }
        assertThrows(SQLiteException::class.java) {
            fixture.insertMovement(0, movementId = "99999999-9999-4999-8999-999999999999")
        }
        assertThrows(SQLiteException::class.java) {
            fixture.insertAudit(auditId = "99999999-9999-4999-8999-999999999999")
        }
        assertThrows(SQLiteException::class.java) { sql.execSQL("UPDATE sales SET totalMinorUnits=0") }
        assertThrows(SQLiteException::class.java) { sql.execSQL("DELETE FROM sale_lines") }
        assertThrows(SQLiteException::class.java) { sql.execSQL("DELETE FROM stock_movements") }
        assertThrows(SQLiteException::class.java) { sql.execSQL("DELETE FROM audit_events") }
        assertThrows(SQLiteException::class.java) {
            fixture.insertPayment(
                paymentId = "99999999-9999-4999-8999-999999999999",
                expectedVersion = 2,
                balanceAfter = 150,
                occurredAt = 5,
            )
        }
        assertCount("sale_voids", 1)
        assertCount("debt_payments", 1)
    }

    @Test
    fun cashVoidRefundsFullSaleAndInvalidatesVisibleReportsWhileRetainingOriginalGraph() =
        runBlocking {
            fixture.seedPosted(withDebt = false)
            val dao = database.saleDao()
            val emissions = Channel<List<PostedSaleSummaryRow>>(Channel.UNLIMITED)
            val collector = launch { dao.observeRecentPosted(BUSINESS_ID, 20).collect { emissions.send(it) } }
            try {
                assertEquals(listOf(SALE_ID), withTimeout(5_000) { emissions.receive() }.map { it.saleId })
                assertEquals(1, dao.listPostedProfitPageKeys(BUSINESS_ID, 0, 10, null, null, 20).size)
                assertEquals(2, dao.listPostedProfitRowsForSales(BUSINESS_ID, listOf(SALE_ID)).size)
                database.runInTransaction {
                    fixture.prepareCompleteReturn()
                    fixture.insertReceipt(refund = 300, cancelled = 0)
                }
                withTimeout(5_000) {
                    while (emissions.receive().isNotEmpty()) Unit
                }
                assertTrue(dao.listPostedProfitPageKeys(BUSINESS_ID, 0, 10, null, null, 20).isEmpty())
                assertTrue(dao.observePostedProfitPageKeys(BUSINESS_ID, 0, 10, null, null, 20).first().isEmpty())
                assertTrue(dao.listPostedProfitRowsForSales(BUSINESS_ID, listOf(SALE_ID)).isEmpty())
                assertNotNull(dao.findWithLines(SALE_ID))
                assertEquals(300L, database.saleVoidDao().findBySaleId(BUSINESS_ID, SALE_ID)?.refundedAmountMinorUnits)
            } finally {
                collector.cancel()
                emissions.close()
            }
        }

    private fun assertCount(
        table: String,
        expected: Int,
    ) {
        sql.query("SELECT COUNT(*) FROM $table").use {
            assertTrue(it.moveToFirst())
            assertEquals(expected, it.getInt(0))
        }
    }
}
