package com.facturastock.app.navigation

import androidx.room.useReaderConnection
import androidx.sqlite.SQLITE_DATA_BLOB
import com.facturastock.app.data.local.FacturaStockDatabase
import kotlinx.coroutines.runBlocking

/**
 * Instantáneas de filas crudas para los recorridos de navegación: equivalen a leer cada tabla
 * con un `Cursor` de Android (`getString` para valores, BLOB en hexadecimal).
 */
internal fun FacturaStockDatabase.snapshotAllTables(): Map<String, List<List<String?>>> {
    val names = runBlocking {
        useReaderConnection { connection ->
            connection.usePrepared(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
            ) { statement ->
                buildList { while (statement.step()) add(statement.getText(0)) }
            }
        }
    }
    return names.associateWith { table -> snapshotTable(table) }
}

internal fun FacturaStockDatabase.snapshotTable(table: String): List<List<String?>> = runBlocking {
    useReaderConnection { connection ->
        connection.usePrepared("SELECT * FROM \"${table.replace("\"", "\"\"")}\" ORDER BY rowid") { statement ->
            buildList {
                while (statement.step()) {
                    add(
                        (0 until statement.getColumnCount()).map { column ->
                            when {
                                statement.isNull(column) -> null
                                statement.getColumnType(column) == SQLITE_DATA_BLOB ->
                                    statement.getBlob(column).joinToString("") { byte -> "%02x".format(byte) }
                                else -> statement.getText(column)
                            }
                        },
                    )
                }
            }
        }
    }
}
