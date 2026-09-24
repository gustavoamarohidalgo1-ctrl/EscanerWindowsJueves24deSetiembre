package com.facturastock.app.data.local

import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import com.facturastock.app.data.local.entity.SupplierEntity
import com.facturastock.app.data.local.sqlite.SQLiteConstraintException
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalDatabaseOperationalPolicyTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private var database: FacturaStockDatabase? = null

    @After
    fun closeAfter() {
        database?.close()
    }

    @Test
    fun productionBuilderEnablesWalAndForeignKeysAndCreatesAConsistentDatabase() = runBlocking {
        val opened = FacturaStockDatabase.build(File(tempFolder.root, "facturastock.db"))
        database = opened

        // Room 2.8 administra sus propias conexiones: WAL e integridad se comprueban en la conexión
        // de escritura de Room y las FKs mediante el rechazo observable de una escritura huérfana.
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

    /**
     * El callback exige synchronous=FULL en la conexión que confirma commits. Room abre sus
     * conexiones de forma perezosa: si la primera operación es una lectura, la conexión que se
     * configura primero es la de lectura y la de escritura se abre después. Ambas rutas deben
     * terminar con la conexión de escritura en FULL (2).
     */
    @Test
    fun writerConnectionIsSynchronousFullEvenWhenAReadOpensTheDatabaseFirst() = runBlocking {
        val opened = FacturaStockDatabase.build(File(tempFolder.root, "read-first.db"))
        database = opened

        // Primera operación: una lectura (abre y configura primero una conexión de lectura).
        opened.useReaderConnection { connection ->
            connection.usePrepared("SELECT COUNT(*) FROM businesses") { statement -> statement.step() }
        }
        val writerSynchronous = opened.useWriterConnection { connection ->
            connection.usePrepared("PRAGMA synchronous") { statement ->
                check(statement.step())
                statement.getLong(0)
            }
        }

        assertEquals(SYNCHRONOUS_FULL, writerSynchronous)
    }

    private companion object {
        const val SYNCHRONOUS_FULL = 2L
    }
}

private data class OperationalPolicy(
    val journalMode: String,
    val foreignKeyViolations: Int,
    val quickCheck: String,
)
