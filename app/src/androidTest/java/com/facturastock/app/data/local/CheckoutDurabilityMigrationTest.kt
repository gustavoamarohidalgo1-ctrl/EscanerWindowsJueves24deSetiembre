package com.facturastock.app.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CheckoutDurabilityMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), FacturaStockDatabase::class.java)

    @Test
    fun migration27To28PreservesBusinessAndCreatesEmptyDurableReceipts() {
        val name = "checkout-durability-migration.db"
        helper.createDatabase(name, 27).apply {
            execSQL(
                "INSERT INTO businesses (businessId,legalName,createdAt,updatedAt,ruc,tradeName,status) " +
                    "VALUES ('11111111-1111-4111-8111-111111111111','Negocio conservado',1,2,NULL,NULL,'ACTIVE')",
            )
            close()
        }
        val database = helper.runMigrationsAndValidate(name, 28, true, FacturaStockDatabase.MIGRATION_27_28)
        database.query("SELECT legalName,updatedAt FROM businesses").use {
            assertTrue(it.moveToFirst())
            assertEquals("Negocio conservado", it.getString(0))
            assertEquals(2L, it.getLong(1))
        }
        for (table in listOf("pending_sale_checkouts", "invoice_inventory_receipts")) {
            database.query("SELECT COUNT(*) FROM $table").use {
                assertTrue(it.moveToFirst())
                assertEquals(0L, it.getLong(0))
            }
        }
        database.query("PRAGMA integrity_check").use {
            assertTrue(it.moveToFirst())
            assertEquals("ok", it.getString(0))
        }
        database.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
        helper.closeWhenFinished(database)
    }
}
