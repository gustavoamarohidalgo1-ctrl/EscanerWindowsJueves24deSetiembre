package com.facturastock.app.data.local

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException

/**
 * Traduce los fallos de disco de Room/SQLite a los errores de dominio [StorageError]:
 * conflictos de restricción (unicidad, claves foráneas) llegan como
 * [StorageError.ConstraintConflict], un volumen lleno como [StorageError.InsufficientSpace] y
 * el resto de fallos de SQLite o E/S como [StorageError.Unavailable]. La cancelación
 * estructurada nunca se convierte: se vuelve a lanzar intacta (regla 16 de ARCHITECTURE.md).
 */
internal suspend inline fun <T> storageCatching(block: () -> T): T {
    try {
        return block()
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: SQLiteConstraintException) {
        throw StorageException(
            StorageError.ConstraintConflict(exception.message ?: "conflicto de restricción"),
            exception,
        )
    } catch (exception: SQLiteFullException) {
        throw StorageException(StorageError.InsufficientSpace, exception)
    } catch (exception: SQLiteException) {
        throw StorageException(
            if (exception.indicatesFullStorage()) {
                StorageError.InsufficientSpace
            } else {
                StorageError.Unavailable
            },
            exception,
        )
    } catch (exception: IOException) {
        throw StorageException(
            if (exception.indicatesFullStorage()) {
                StorageError.InsufficientSpace
            } else {
                StorageError.Unavailable
            },
            exception,
        )
    }
}

/** Algunos fabricantes envuelven SQLITE_FULL/ENOSPC en una excepción más genérica. */
private fun Throwable.indicatesFullStorage(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        val message = current.message?.lowercase(Locale.ROOT).orEmpty()
        if (
            "sqlite_full" in message ||
            "database or disk is full" in message ||
            "enospc" in message ||
            "no space left" in message
        ) {
            return true
        }
        current = current.cause?.takeUnless { it === current }
    }
    return false
}
