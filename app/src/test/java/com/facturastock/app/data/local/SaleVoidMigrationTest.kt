package com.facturastock.app.data.local

import com.facturastock.app.data.local.sqlite.SQLiteException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SaleVoidMigrationTest {
    @get:Rule
    val helper = FacturaStockMigrationTestHelper()

    @Test
    fun migration28To29PreservesPostedSaleDebtPaymentLedgerAndEnforcesVoidGraph() {
        val name = "sale-void-migration.db"
        val old = helper.createDatabase(name, 28)
        // Exported schemas do not contain triggers; seed through the same v28 guards as production.
        installPostingPersistenceInvariants(old)
        installSalesPersistenceInvariants(old)
        installDebtPersistenceInvariants(old)
        val oldFixture = SaleVoidSqlFixture(old)
        oldFixture.seedPosted()
        val before = oldFixture.historicalRows()
        old.close()

        val migrated = helper.runMigrationsAndValidate(name, 29, true, FacturaStockDatabase.MIGRATION_28_29)
        helper.closeWhenFinished(migrated)
        val fixture = SaleVoidSqlFixture(migrated)
        assertEquals(before, fixture.historicalRows())
        migrated.query("SELECT COUNT(*) FROM sale_voids").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
        assertThrows(SQLiteException::class.java) { fixture.insertReceipt() }
        fixture.prepareCompleteReturn()
        fixture.insertReceipt()
        assertEquals(before, fixture.historicalRows())
        assertThrows(SQLiteException::class.java) { fixture.insertReceipt(replace = true) }
        assertThrows(SQLiteException::class.java) { migrated.execSQL("DELETE FROM sale_voids") }
        assertThrows(SQLiteException::class.java) { migrated.execSQL("DELETE FROM sales") }
        migrated.query("PRAGMA integrity_check").use {
            assertTrue(it.moveToFirst())
            assertEquals("ok", it.getString(0))
        }
        migrated.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
    }

    @Test
    fun migrationPreservesIncompleteHistoricalSaleButDoesNotAllowVoidingIt() {
        val name = "sale-void-incomplete-history-migration.db"
        helper.createDatabase(name, 28).apply {
            // Model an old damaged/imported ledger without silently rewriting or legitimizing it.
            SaleVoidSqlFixture(this).seedPosted(withDebt = false)
            execSQL("DELETE FROM audit_events WHERE eventType='SALE_POSTED'")
            close()
        }
        val migrated = helper.runMigrationsAndValidate(name, 29, true, FacturaStockDatabase.MIGRATION_28_29)
        helper.closeWhenFinished(migrated)
        val fixture = SaleVoidSqlFixture(migrated)
        val before = fixture.historicalRows()
        fixture.prepareCompleteReturn()
        assertThrows(SQLiteException::class.java) { fixture.insertReceipt(refund = 300, cancelled = 0) }
        assertEquals(before, fixture.historicalRows())
    }
}
