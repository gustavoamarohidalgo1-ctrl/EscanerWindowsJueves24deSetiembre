package com.facturastock.app.data.local

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.useWriterConnection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.facturastock.app.data.local.entity.SupplierEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalDatabaseOperationalPolicyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var database: FacturaStockDatabase? = null

    @Before
    fun deleteBefore() {
        context.deleteDatabase(FacturaStockDatabase.NAME)
    }

    @After
    fun closeAndDeleteAfter() {
        database?.close()
        context.deleteDatabase(FacturaStockDatabase.NAME)
    }

    @Test
    fun productionBuilderEnablesWalAndForeignKeysAndCreatesAConsistentDatabase() = runBlocking {
        val opened = FacturaStockDatabase.build(context, FailClosedSQLiteOpenHelperFactory())
        database = opened

        // Room 2.8 administra sus propias conexiones. Consultar openHelper directamente evita ese
        // camino y no representa una escritura DAO productiva; WAL e integridad se comprueban en
        // la conexión Room y las FKs mediante el rechazo observable de una escritura huérfana.
        val policy = opened.useWriterConnection { connection ->
            val journalMode = connection.usePrepared("PRAGMA journal_mode") { statement ->
                check(statement.step())
                statement.getText(0)
            }
            val foreignKeyViolations =
                connection.usePrepared("PRAGMA foreign_key_check") { statement ->
                    var count = 0
                    while (statement.step()) count += 1
                    count
                }
            val quickCheck = connection.usePrepared("PRAGMA quick_check(1)") { statement ->
                check(statement.step())
                statement.getText(0)
            }
            OperationalPolicy(journalMode, foreignKeyViolations, quickCheck)
        }

        assertEquals("wal", policy.journalMode.lowercase())
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking {
                opened.supplierDao().insert(
                    SupplierEntity(
                        supplierId = "10000000-0000-4000-8000-000000000001",
                        businessId = "20000000-0000-4000-8000-000000000001",
                        legalName = "Proveedor huérfano",
                        createdAt = 1L,
                        updatedAt = 1L,
                    ),
                )
            }
        }
        assertEquals(0, policy.foreignKeyViolations)
        assertEquals("ok", policy.quickCheck.lowercase())
    }
}

private data class OperationalPolicy(
    val journalMode: String,
    val foreignKeyViolations: Int,
    val quickCheck: String,
)
