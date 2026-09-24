package com.facturastock.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DebtMigrationTest {
    @get:Rule
    val helper = FacturaStockMigrationTestHelper()

    @Test
    fun migration26To27PreservesExistingDataAndInstallsDebtLedger() {
        helper.createDatabase(DATABASE_NAME, 26).apply {
            execSQL(
                "INSERT INTO `businesses` (`businessId`,`legalName`,`createdAt`,`updatedAt`," +
                    "`ruc`,`tradeName`,`status`) VALUES " +
                    "('$BUSINESS_ID','Negocio migrado',1,1,NULL,NULL,'ACTIVE')",
            )
            close()
        }

        val database = helper.runMigrationsAndValidate(
            DATABASE_NAME,
            27,
            true,
            FacturaStockDatabase.MIGRATION_26_27,
        )

        assertSingleLong(database, "SELECT COUNT(*) FROM `businesses`", 1L)
        assertSingleLong(database, "SELECT COUNT(*) FROM `debts`", 0L)
        assertSingleLong(database, "SELECT COUNT(*) FROM `debt_payments`", 0L)
        assertSingleLong(
            database,
            "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name IN (" +
                "'index_debts_saleId'," +
                "'index_debts_businessId_status_updatedAt_debtId'," +
                "'index_debts_businessId_normalizedDebtorName_status_updatedAt_debtId'," +
                "'index_debt_payments_debtId_expectedDebtVersion'," +
                "'index_debt_payments_idempotencyKey'," +
                "'index_debt_payments_businessId_occurredAt_paymentId')",
            6L,
        )
        assertSingleLong(
            database,
            "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name IN (" +
                "'debts_block_replace','debts_require_posted_sale_insert'," +
                "'debts_validate_payment_update','debts_block_delete'," +
                "'debt_payments_block_replace','debt_payments_block_update'," +
                "'debt_payments_block_delete','debt_payments_validate_graph_insert')",
            8L,
        )
        database.query("PRAGMA foreign_key_check").use { cursor ->
            assertEquals(0, cursor.count)
        }
        database.query("PRAGMA integrity_check").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("ok", cursor.getString(0))
            assertFalse(cursor.moveToNext())
        }

        helper.closeWhenFinished(database)
    }

    private fun assertSingleLong(
        database: com.facturastock.app.data.local.sqlite.SupportSQLiteDatabase,
        sql: String,
        expected: Long,
    ) {
        database.query(sql).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(expected, cursor.getLong(0))
            assertFalse(cursor.moveToNext())
        }
    }

    private companion object {
        const val DATABASE_NAME = "debt-migration.db"
        const val BUSINESS_ID = "11111111-1111-4111-8111-111111111111"
    }
}
