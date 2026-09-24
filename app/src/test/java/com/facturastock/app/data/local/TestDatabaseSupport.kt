package com.facturastock.app.data.local

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transactor
import androidx.room.migration.Migration
import androidx.room.testing.MigrationTestHelper
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.facturastock.app.data.local.sqlite.SupportSQLiteDatabase
import com.facturastock.app.data.local.sqlite.TranslatingSQLiteDriver
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import androidx.room.immediateTransaction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Soporte JVM para las pruebas de persistencia que antes corrían en un dispositivo Android.
 *
 * Toda base se construye con [FacturaStockDatabase.buildAt]: mismo driver traductor, mismas
 * migraciones, mismo callback de invariantes (triggers + synchronous=FULL) y WAL que producción.
 */

/** Driver idéntico al de producción: SQLite embebido con la traducción de errores tipados. */
internal fun productionTestDriver(): TranslatingSQLiteDriver = TranslatingSQLiteDriver(BundledSQLiteDriver())

/** Directorio de esquemas exportados por Room (`app/schemas`), relativo al módulo. */
internal val facturaStockSchemaDirectory: Path =
    Paths.get(System.getProperty("user.dir")).resolve("schemas").toAbsolutePath()

/** Base de producción en un archivo nuevo dentro de [folder]. */
internal fun TemporaryFolder.newFacturaStockDatabase(
    name: String = "facturastock-test-${UUID.randomUUID()}.db",
): FacturaStockDatabase = FacturaStockDatabase.buildAt(File(root, name))

/**
 * Base con el esquema vigente, el driver y el WAL de producción pero SIN el callback de
 * invariantes (sin triggers). Equivale al `Room.inMemoryDatabaseBuilder(...).build()` sin
 * `addCallback` de las pruebas Android que solo ejercitan consultas DAO sobre filas sembradas
 * directamente (grafos que los triggers de producción rechazarían por construcción).
 */
internal fun TemporaryFolder.newFacturaStockDatabaseWithoutInvariants(
    name: String = "facturastock-plain-${UUID.randomUUID()}.db",
    driver: SQLiteDriver = productionTestDriver(),
): FacturaStockDatabase =
    Room.databaseBuilder<FacturaStockDatabase>(File(root, name).absolutePath)
        .setDriver(driver)
        .setQueryCoroutineContext(Dispatchers.IO)
        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .build()

/** Sentencia ejecutada según [RecordingSQLiteDriver]: SQL, argumentos y si corrió en transacción. */
internal data class RecordedQuery(val sql: String, val bindArgs: List<Any?>, val inTransaction: Boolean)

/**
 * Driver de producción que además informa cada sentencia al ejecutarla (primer `step()` tras
 * preparar o reiniciar), con sus argumentos y si la conexión estaba dentro de una transacción.
 * Equivalente JVM de `RoomDatabase.Builder.setQueryCallback`, que solo existe en Android.
 */
internal class RecordingSQLiteDriver(
    private val delegate: SQLiteDriver = productionTestDriver(),
    private val onQuery: (RecordedQuery) -> Unit,
) : SQLiteDriver by delegate {
    override fun open(fileName: String): SQLiteConnection {
        val connection = delegate.open(fileName)
        return object : SQLiteConnection by connection {
            override fun prepare(sql: String): SQLiteStatement =
                RecordingStatement(sql, connection, connection.prepare(sql), onQuery)
        }
    }

    private class RecordingStatement(
        private val sql: String,
        private val connection: SQLiteConnection,
        private val delegate: SQLiteStatement,
        private val onQuery: (RecordedQuery) -> Unit,
    ) : SQLiteStatement by delegate {
        private val bindings = sortedMapOf<Int, Any?>()
        private var reported = false

        override fun bindBlob(index: Int, value: ByteArray) = delegate.bindBlob(index, value).also { bindings[index] = value }
        override fun bindDouble(index: Int, value: Double) = delegate.bindDouble(index, value).also { bindings[index] = value }
        override fun bindLong(index: Int, value: Long) = delegate.bindLong(index, value).also { bindings[index] = value }
        override fun bindText(index: Int, value: String) = delegate.bindText(index, value).also { bindings[index] = value }
        override fun bindNull(index: Int) = delegate.bindNull(index).also { bindings[index] = null }

        override fun step(): Boolean {
            if (!reported) {
                reported = true
                onQuery(RecordedQuery(sql, bindings.values.toList(), connection.inTransaction()))
            }
            return delegate.step()
        }

        override fun reset() {
            reported = false
            delegate.reset()
        }

        override fun clearBindings() {
            bindings.clear()
            delegate.clearBindings()
        }
    }
}

/** Abre de inmediato la base (equivalente a `openHelper.writableDatabase`). */
internal fun <T : RoomDatabase> T.openNow(): T = apply {
    runBlocking { useWriterConnection { it.usePrepared("SELECT 1") { statement -> statement.step() } } }
}

/** `PRAGMA user_version` de la base abierta por Room (equivalente a `SupportSQLiteDatabase.version`). */
internal suspend fun RoomDatabase.userVersion(): Int =
    querySql("PRAGMA user_version").use { cursor ->
        check(cursor.moveToFirst())
        cursor.getInt(0)
    }

/** SQL crudo sobre la conexión de escritura de Room (equivalente a `openHelper.writableDatabase`). */
internal val RoomDatabase.writableSql: TestSql get() = RoomTestSql(this, writer = true)

/** SQL crudo sobre una conexión de lectura de Room (equivalente a `openHelper.readableDatabase`). */
internal val RoomDatabase.readableSql: TestSql get() = RoomTestSql(this, writer = false)

/** Forma mínima de `SupportSQLiteDatabase` que comparten fixtures SQL de Room y de migraciones. */
internal interface TestSql {
    fun execSQL(sql: String) = execSQL(sql, emptyArray())

    fun execSQL(sql: String, bindArgs: Array<out Any?>)

    fun query(sql: String): TestCursor = query(sql, emptyArray())

    fun query(sql: String, bindArgs: Array<out Any?>): TestCursor
}

/** Adapta la fachada de migraciones a [TestSql]. */
internal fun SupportSQLiteDatabase.asTestSql(): TestSql = object : TestSql {
    override fun execSQL(sql: String, bindArgs: Array<out Any?>) = this@asTestSql.execSQL(sql, bindArgs)

    override fun query(sql: String, bindArgs: Array<out Any?>): TestCursor =
        connection.prepare(sql).use { statement -> statement.readAll(bindArgs) }
}

/**
 * Cursor en memoria con la API de `android.database.Cursor` que usan las pruebas (incluye
 * `getType` y las constantes `FIELD_TYPE_*`).
 */
internal class TestCursor(
    private val names: List<String>,
    private val rows: List<Array<Any?>>,
) : java.io.Closeable {
    private var position = -1

    val count: Int get() = rows.size
    val columnCount: Int get() = names.size
    val columnNames: Array<String> get() = names.toTypedArray()

    fun moveToFirst(): Boolean = moveToPosition(0)

    fun moveToNext(): Boolean = moveToPosition(position + 1)

    fun moveToPosition(target: Int): Boolean {
        position = target.coerceIn(-1, rows.size)
        return position in rows.indices
    }

    fun getColumnIndex(name: String): Int = names.indexOf(name)

    fun getColumnIndexOrThrow(name: String): Int =
        getColumnIndex(name).takeIf { it >= 0 } ?: throw IllegalArgumentException("Columna $name")

    fun getColumnName(index: Int): String = names[index]

    fun getType(index: Int): Int = when (value(index)) {
        null -> FIELD_TYPE_NULL
        is Long -> FIELD_TYPE_INTEGER
        is Double -> FIELD_TYPE_FLOAT
        is ByteArray -> FIELD_TYPE_BLOB
        else -> FIELD_TYPE_STRING
    }

    fun isNull(index: Int): Boolean = value(index) == null

    fun getString(index: Int): String? = when (val raw = value(index)) {
        null -> null
        is ByteArray -> String(raw)
        else -> raw.toString()
    }

    fun getLong(index: Int): Long = when (val raw = value(index)) {
        null -> 0L
        is Long -> raw
        is Double -> raw.toLong()
        is String -> raw.toLongOrNull() ?: raw.toDoubleOrNull()?.toLong() ?: 0L
        else -> 0L
    }

    fun getInt(index: Int): Int = getLong(index).toInt()

    fun getDouble(index: Int): Double = when (val raw = value(index)) {
        null -> 0.0
        is Long -> raw.toDouble()
        is Double -> raw
        is String -> raw.toDoubleOrNull() ?: 0.0
        else -> 0.0
    }

    fun getFloat(index: Int): Float = getDouble(index).toFloat()

    fun getBlob(index: Int): ByteArray? = when (val raw = value(index)) {
        null -> null
        is ByteArray -> raw
        is String -> raw.toByteArray()
        else -> raw.toString().toByteArray()
    }

    private fun value(index: Int): Any? {
        check(position in rows.indices) { "Cursor fuera de rango" }
        return rows[position][index]
    }

    override fun close() = Unit

    companion object {
        const val FIELD_TYPE_NULL = 0
        const val FIELD_TYPE_INTEGER = 1
        const val FIELD_TYPE_FLOAT = 2
        const val FIELD_TYPE_STRING = 3
        const val FIELD_TYPE_BLOB = 4
    }
}

/**
 * Fachada bloqueante con la forma de `SupportSQLiteDatabase` sobre las conexiones del pool de
 * Room: triggers, claves foráneas y PRAGMAs son los de la conexión real, y cada escritura refresca
 * el rastreador de invalidación igual que un DAO.
 *
 * No debe usarse desde dentro de un bloque `withTransaction` (esperaría la conexión de escritura
 * que el propio bloque retiene); ahí se usan las variantes `suspend` de [RoomDatabase].
 */
private class RoomTestSql(private val database: RoomDatabase, private val writer: Boolean) : TestSql {
    override fun execSQL(sql: String, bindArgs: Array<out Any?>) {
        val transaction = activeTransaction.get()
        if (transaction != null) {
            transaction.submit(sql, bindArgs, read = false)
        } else {
            runBlocking { database.execSql(sql, *bindArgs) }
        }
    }

    override fun query(sql: String, bindArgs: Array<out Any?>): TestCursor {
        val transaction = activeTransaction.get()
        if (transaction != null) return requireNotNull(transaction.submit(sql, bindArgs, read = true))
        return runBlocking {
            if (writer) database.querySql(sql, *bindArgs) else database.queryReadSql(sql, *bindArgs)
        }
    }
}

/**
 * Canal hacia la corrutina que retiene la transacción de [runInTransaction]: Room solo permite
 * usar la conexión desde esa corrutina, así que el bloque bloqueante (en otro hilo) le envía cada
 * sentencia y espera su resultado.
 */
private class TransactionSqlChannel {
    class Request(val sql: String, val bindArgs: Array<out Any?>, val read: Boolean) {
        val response = CompletableDeferred<TestCursor?>()
    }

    val requests = Channel<Request>(Channel.RENDEZVOUS)

    fun submit(sql: String, bindArgs: Array<out Any?>, read: Boolean): TestCursor? = runBlocking {
        val request = Request(sql, bindArgs, read)
        requests.send(request)
        request.response.await()
    }
}

private val activeTransaction = ThreadLocal<TransactionSqlChannel?>()

/**
 * Equivalente de `RoomDatabase.runInTransaction` de Android: ejecuta [block] bloqueante dentro de
 * una transacción IMMEDIATE de la conexión de escritura; el SQL de [writableSql]/[readableSql]
 * emitido desde el bloque corre en esa misma transacción y todo se revierte si el bloque lanza.
 */
internal fun <R> RoomDatabase.runInTransaction(block: () -> R): R = runBlocking {
    useWriterConnection { transactor ->
        transactor.immediateTransaction {
            val channel = TransactionSqlChannel()
            val outcome = async(Dispatchers.IO) {
                activeTransaction.set(channel)
                try {
                    block()
                } finally {
                    activeTransaction.remove()
                    channel.requests.close()
                }
            }
            for (request in channel.requests) {
                val result = runCatching {
                    usePrepared(request.sql) { statement ->
                        if (request.read) {
                            statement.readAll(request.bindArgs)
                        } else {
                            statement.bindAll(request.bindArgs)
                            while (statement.step()) Unit
                            null
                        }
                    }
                }
                result.fold(request.response::complete, request.response::completeExceptionally)
            }
            outcome.await()
        }
    }
}

/** Ejecuta [sql] en la conexión de escritura (se une a la transacción en curso si la hay). */
internal suspend fun RoomDatabase.execSql(sql: String, vararg bindArgs: Any?) {
    useWriterConnection { transactor -> transactor.exec(sql, bindArgs) }
}

/** Consulta [sql] en la conexión de escritura (ve lo no confirmado de la transacción en curso). */
internal suspend fun RoomDatabase.querySql(sql: String, vararg bindArgs: Any?): TestCursor =
    useWriterConnection { transactor -> transactor.read(sql, bindArgs) }

/** Consulta [sql] en una conexión de lectura del pool. */
internal suspend fun RoomDatabase.queryReadSql(sql: String, vararg bindArgs: Any?): TestCursor =
    useReaderConnection { transactor -> transactor.read(sql, bindArgs) }

private suspend fun Transactor.exec(sql: String, bindArgs: Array<out Any?>) {
    usePrepared(sql) { statement ->
        statement.bindAll(bindArgs)
        while (statement.step()) Unit
    }
}

private suspend fun Transactor.read(sql: String, bindArgs: Array<out Any?>): TestCursor =
    usePrepared(sql) { statement -> statement.readAll(bindArgs) }

/** Materializa el resultado completo en un [Cursor] en memoria (API de `android.database.Cursor`). */
internal fun SQLiteStatement.readAll(bindArgs: Array<out Any?> = emptyArray()): TestCursor {
    bindAll(bindArgs)
    val names = List(getColumnCount()) { getColumnName(it) }
    val rows = mutableListOf<Array<Any?>>()
    while (step()) {
        rows += Array(names.size) { index ->
            when (getColumnType(index)) {
                SQLITE_INTEGER -> getLong(index)
                SQLITE_FLOAT -> getDouble(index)
                SQLITE_TEXT -> getText(index)
                SQLITE_BLOB -> getBlob(index)
                else -> null
            }
        }
    }
    return TestCursor(names, rows)
}

internal fun SQLiteStatement.bindAll(args: Array<out Any?>) {
    args.forEachIndexed { zeroBased, value ->
        val index = zeroBased + 1
        when (value) {
            null -> bindNull(index)
            is String -> bindText(index, value)
            is Long -> bindLong(index, value)
            is Int -> bindLong(index, value.toLong())
            is Short -> bindLong(index, value.toLong())
            is Byte -> bindLong(index, value.toLong())
            is Boolean -> bindLong(index, if (value) 1L else 0L)
            is Double -> bindDouble(index, value)
            is Float -> bindDouble(index, value.toDouble())
            is ByteArray -> bindBlob(index, value)
            else -> bindText(index, value.toString())
        }
    }
}

private const val SQLITE_INTEGER = 1
private const val SQLITE_FLOAT = 2
private const val SQLITE_TEXT = 3
private const val SQLITE_BLOB = 4

/** Cierra la conexión subyacente de la fachada de migraciones. */
internal fun SupportSQLiteDatabase.close() = connection.close()

/** Uso con cierre garantizado, como `SupportSQLiteDatabase.use {}` en Android. */
internal inline fun <R> SupportSQLiteDatabase.use(block: (SupportSQLiteDatabase) -> R): R =
    try {
        block(this)
    } finally {
        close()
    }

/** Conexión cruda (sin Room) al archivo [file], con claves foráneas activas como en Room. */
internal fun openRawDatabase(file: File, foreignKeys: Boolean = true): SupportSQLiteDatabase {
    val db = SupportSQLiteDatabase(productionTestDriver().open(file.absolutePath))
    db.execSQL("PRAGMA busy_timeout = 5000")
    if (foreignKeys) db.execSQL("PRAGMA foreign_keys = ON")
    return db
}

/**
 * Equivalente JVM del `MigrationTestHelper` instrumentado: cada nombre de base obtiene su propio
 * helper de `room-testing` sobre un archivo temporal, con el driver de producción, y devuelve la
 * fachada [SupportSQLiteDatabase] que usan las migraciones.
 */
class FacturaStockMigrationTestHelper : TestRule {
    private val folder = TemporaryFolder()
    private val helpers = mutableMapOf<String, MigrationTestHelper>()
    private val connections = mutableListOf<SQLiteConnection>()

    override fun apply(base: Statement, description: Description): Statement =
        RuleChain.outerRule(folder).around(
            object : ExternalResource() {
                override fun after() = closeAll()
            },
        ).apply(base, description)

    /** Ruta del archivo de la base [name] (equivalente a `context.getDatabasePath(name)`). */
    fun databaseFile(name: String): File = File(folder.root, name)

    fun createDatabase(name: String, version: Int): SupportSQLiteDatabase =
        track(helperFor(name).createDatabase(version))

    /**
     * Ejecuta [migrations] y valida contra el esquema exportado de [version]. `room-testing` en JVM
     * siempre valida tablas sobrantes, así que [validateDroppedTables] se acepta por compatibilidad.
     */
    @Suppress("UNUSED_PARAMETER")
    fun runMigrationsAndValidate(
        name: String,
        version: Int,
        validateDroppedTables: Boolean,
        vararg migrations: Migration,
    ): SupportSQLiteDatabase = track(helperFor(name).runMigrationsAndValidate(version, migrations.toList()))

    fun closeWhenFinished(db: SupportSQLiteDatabase) {
        if (db.connection !in connections) connections += db.connection
    }

    fun closeWhenFinished(database: RoomDatabase) {
        roomDatabases += database
    }

    private val roomDatabases = mutableListOf<RoomDatabase>()

    private fun helperFor(name: String): MigrationTestHelper = helpers.getOrPut(name) {
        MigrationTestHelper(
            schemaDirectoryPath = facturaStockSchemaDirectory,
            databasePath = databaseFile(name).toPath(),
            driver = productionTestDriver(),
            databaseClass = FacturaStockDatabase::class,
        )
    }

    private fun track(connection: SQLiteConnection): SupportSQLiteDatabase {
        connections += connection
        return SupportSQLiteDatabase(connection)
    }

    private fun closeAll() {
        roomDatabases.forEach { runCatching { it.close() } }
        connections.forEach { runCatching { it.close() } }
        roomDatabases.clear()
        connections.clear()
    }
}
