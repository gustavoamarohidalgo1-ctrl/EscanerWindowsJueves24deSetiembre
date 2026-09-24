package com.facturastock.app.testing

import androidx.room.useReaderConnection
import com.facturastock.app.data.local.FacturaStockDatabase
import kotlinx.coroutines.runBlocking

/**
 * Filas de [table] como texto, ordenadas por la primera columna: sustituto de escritorio del
 * `openHelper.readableDatabase.query("SELECT * FROM table ORDER BY 1")` que usaban los recorridos
 * instrumentados para comprobar que una acción no alteró (o sí alteró) una tabla.
 */
fun FacturaStockDatabase.snapshotTable(table: String): List<List<String?>> = runBlocking {
    useReaderConnection { transactor ->
        transactor.usePrepared("SELECT * FROM $table ORDER BY 1") { statement ->
            buildList {
                while (statement.step()) {
                    add(
                        (0 until statement.getColumnCount()).map { index ->
                            if (statement.isNull(index)) null else statement.getText(index)
                        },
                    )
                }
            }
        }
    }
}
