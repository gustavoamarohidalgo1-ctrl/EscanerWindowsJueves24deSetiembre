package com.facturastock.app.data.local.sqlite

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import java.io.Closeable

/**
 * Fachada mínima con la forma de `androidx.sqlite.db.SupportSQLiteDatabase` sobre una
 * [SQLiteConnection]. Permite que migraciones, triggers e invariantes escritos para Android se
 * ejecuten sin cambios de SQL ni de orden sobre el driver de escritorio.
 */
class SupportSQLiteDatabase(val connection: SQLiteConnection) {
    private var transactionDepth = 0
    private val successfulDepths = mutableSetOf<Int>()

    fun execSQL(sql: String) {
        connection.prepare(sql).use { statement -> statement.step() }
    }

    fun execSQL(sql: String, bindArgs: Array<out Any?>) {
        connection.prepare(sql).use { statement ->
            statement.bindAll(bindArgs)
            statement.step()
        }
    }

    fun query(sql: String): Cursor = query(sql, emptyArray())

    fun query(sql: String, bindArgs: Array<out Any?>): Cursor =
        connection.prepare(sql).use { statement ->
            statement.bindAll(bindArgs)
            val names = List(statement.getColumnCount()) { statement.getColumnName(it) }
            val rows = mutableListOf<Array<Any?>>()
            while (statement.step()) {
                rows += Array(names.size) { index -> statement.readValue(index) }
            }
            Cursor(names, rows)
        }

    fun inTransaction(): Boolean = connection.inTransaction()

    fun beginTransaction() {
        if (transactionDepth == 0) execSQL("BEGIN IMMEDIATE TRANSACTION") else execSQL("SAVEPOINT sp_$transactionDepth")
        transactionDepth += 1
    }

    fun setTransactionSuccessful() {
        check(transactionDepth > 0) { "No hay transacción activa" }
        successfulDepths += transactionDepth
    }

    fun endTransaction() {
        check(transactionDepth > 0) { "No hay transacción activa" }
        val depth = transactionDepth
        val successful = successfulDepths.remove(depth)
        transactionDepth -= 1
        if (depth == 1) {
            execSQL(if (successful) "COMMIT TRANSACTION" else "ROLLBACK TRANSACTION")
        } else {
            val name = "sp_${depth - 1}"
            if (!successful) execSQL("ROLLBACK TRANSACTION TO SAVEPOINT $name")
            execSQL("RELEASE SAVEPOINT $name")
        }
    }
}

/** Cursor en memoria con la API de `android.database.Cursor` que usan las migraciones. */
class Cursor internal constructor(
    private val columnNames: List<String>,
    private val rows: List<Array<Any?>>,
) : Closeable {
    private var position = -1

    val count: Int get() = rows.size
    val columnCount: Int get() = columnNames.size

    fun moveToFirst(): Boolean {
        position = 0
        return rows.isNotEmpty()
    }

    fun moveToNext(): Boolean {
        if (position < rows.size) position += 1
        return position < rows.size
    }

    fun getColumnIndex(name: String): Int = columnNames.indexOf(name)

    fun getColumnIndexOrThrow(name: String): Int =
        getColumnIndex(name).takeIf { it >= 0 } ?: throw IllegalArgumentException("Columna $name")

    fun getColumnName(index: Int): String = columnNames[index]

    fun isNull(index: Int): Boolean = value(index) == null

    fun getString(index: Int): String? = when (val raw = value(index)) {
        null -> null
        is ByteArray -> String(raw)
        is Double -> raw.toString()
        else -> raw.toString()
    }

    fun getLong(index: Int): Long = when (val raw = value(index)) {
        null -> 0L
        is Long -> raw
        is Double -> raw.toLong()
        is String -> raw.toLongOrNull() ?: 0L
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

    fun getBlob(index: Int): ByteArray? = value(index) as? ByteArray

    private fun value(index: Int): Any? {
        check(position in rows.indices) { "Cursor fuera de rango" }
        return rows[position][index]
    }

    override fun close() = Unit
}

/** Migración escrita contra [SupportSQLiteDatabase]; Room la ejecuta con la conexión del driver. */
abstract class LegacyMigration(startVersion: Int, endVersion: Int) : Migration(startVersion, endVersion) {
    abstract fun migrate(db: SupportSQLiteDatabase)

    final override fun migrate(connection: SQLiteConnection) {
        val db = SupportSQLiteDatabase(connection)
        // Las migraciones reconstruyen tablas con "DROP viejo + ALTER nuevo RENAME TO viejo" mientras
        // hay triggers de invariantes que nombran la tabla borrada. El SQLite embebido moderno
        // valida todo el esquema en cada RENAME y aborta ("no such table" dentro del trigger); el
        // SQLite de Android con el que se validaron estas migraciones lo aceptaba. El modo legacy
        // restaura ese comportamiento solo mientras corre la migración.
        val previousLegacyAlterTable = db.query("PRAGMA legacy_alter_table").use { cursor ->
            cursor.moveToFirst() && cursor.getInt(0) != 0
        }
        db.execSQL("PRAGMA legacy_alter_table = ON")
        try {
            migrate(db)
        } finally {
            db.execSQL("PRAGMA legacy_alter_table = ${if (previousLegacyAlterTable) "ON" else "OFF"}")
        }
    }
}

private fun SQLiteStatement.bindAll(args: Array<out Any?>) {
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

private fun SQLiteStatement.readValue(index: Int): Any? = when (getColumnType(index)) {
    SQLITE_INTEGER -> getLong(index)
    SQLITE_FLOAT -> getDouble(index)
    SQLITE_TEXT -> getText(index)
    SQLITE_BLOB -> getBlob(index)
    else -> null
}
