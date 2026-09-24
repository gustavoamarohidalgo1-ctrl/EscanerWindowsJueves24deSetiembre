package com.facturastock.app.data.local.sqlite

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement

/**
 * Garantiza `synchronous=FULL` en TODAS las conexiones que Room abre sobre el archivo.
 *
 * Con un driver, Room solo ejecuta el callback `onOpen` (donde vive `enforceWriteDurability`) en
 * la primera conexión que configura la base; cada conexión posterior del pool recibe
 * `PRAGMA synchronous = NORMAL` por estar en WAL. Si la primera operación es una lectura, la
 * conexión de escritura —la única que confirma commits— se abre después y quedaría en NORMAL,
 * que puede perder la transacción más reciente ante un corte de energía. Este envoltorio fija
 * FULL al abrir y reescribe esa asignación de Room para que ninguna conexión la degrade.
 */
internal class FullSynchronousSQLiteDriver(private val delegate: SQLiteDriver) : SQLiteDriver by delegate {
    override fun open(fileName: String): SQLiteConnection {
        val connection = delegate.open(fileName)
        connection.prepare(SYNCHRONOUS_FULL).use { statement -> statement.step() }
        return FullSynchronousConnection(connection)
    }

    private class FullSynchronousConnection(private val delegate: SQLiteConnection) : SQLiteConnection by delegate {
        override fun prepare(sql: String): SQLiteStatement =
            delegate.prepare(if (ROOM_SYNCHRONOUS_NORMAL.matches(sql)) SYNCHRONOUS_FULL else sql)
    }

    private companion object {
        const val SYNCHRONOUS_FULL = "PRAGMA synchronous = FULL"
        val ROOM_SYNCHRONOUS_NORMAL = Regex("""\s*PRAGMA\s+synchronous\s*=\s*(NORMAL|1)\s*;?\s*""", RegexOption.IGNORE_CASE)
    }
}
