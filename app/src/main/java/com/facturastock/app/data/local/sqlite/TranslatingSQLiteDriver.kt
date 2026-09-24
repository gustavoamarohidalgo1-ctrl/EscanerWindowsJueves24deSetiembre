package com.facturastock.app.data.local.sqlite

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement

/**
 * Envuelve el driver para que todo error de SQLite llegue como la jerarquía tipada local.
 *
 * Consecuencia: el código interno de Room que captura `androidx.sqlite.SQLiteException` (clase
 * final) ya no la ve. En particular `@Upsert` deja de caer en UPDATE ante un conflicto de clave
 * y lanza; los DAO deben expresar el upsert con `@Update` + `@Insert` en una `@Transaction`.
 */
class TranslatingSQLiteDriver(private val delegate: SQLiteDriver) : SQLiteDriver {
    override fun open(fileName: String): SQLiteConnection =
        TranslatingConnection(translatingSQLite { delegate.open(fileName) })
}

private class TranslatingConnection(private val delegate: SQLiteConnection) : SQLiteConnection {
    override fun inTransaction(): Boolean = delegate.inTransaction()

    override fun prepare(sql: String): SQLiteStatement =
        TranslatingStatement(translatingSQLite { delegate.prepare(sql) })

    override fun close() = translatingSQLite { delegate.close() }
}

private class TranslatingStatement(private val delegate: SQLiteStatement) : SQLiteStatement {
    override fun bindBlob(index: Int, value: ByteArray) = translatingSQLite { delegate.bindBlob(index, value) }
    override fun bindDouble(index: Int, value: Double) = translatingSQLite { delegate.bindDouble(index, value) }
    override fun bindLong(index: Int, value: Long) = translatingSQLite { delegate.bindLong(index, value) }
    override fun bindText(index: Int, value: String) = translatingSQLite { delegate.bindText(index, value) }
    override fun bindNull(index: Int) = translatingSQLite { delegate.bindNull(index) }
    override fun getBlob(index: Int): ByteArray = translatingSQLite { delegate.getBlob(index) }
    override fun getDouble(index: Int): Double = translatingSQLite { delegate.getDouble(index) }
    override fun getLong(index: Int): Long = translatingSQLite { delegate.getLong(index) }
    override fun getText(index: Int): String = translatingSQLite { delegate.getText(index) }
    override fun isNull(index: Int): Boolean = translatingSQLite { delegate.isNull(index) }
    override fun getColumnCount(): Int = translatingSQLite { delegate.getColumnCount() }
    override fun getColumnName(index: Int): String = translatingSQLite { delegate.getColumnName(index) }
    override fun getColumnType(index: Int): Int = translatingSQLite { delegate.getColumnType(index) }
    override fun step(): Boolean = translatingSQLite { delegate.step() }
    override fun reset() = translatingSQLite { delegate.reset() }
    override fun clearBindings() = translatingSQLite { delegate.clearBindings() }
    override fun close() = translatingSQLite { delegate.close() }
}
