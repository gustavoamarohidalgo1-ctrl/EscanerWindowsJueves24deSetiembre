package com.facturastock.app.data.local.sqlite

/**
 * Jerarquía tipada equivalente a `android.database.sqlite`: el driver de escritorio solo expone
 * [androidx.sqlite.SQLiteException] con el código en el mensaje. [TranslatingSQLiteDriver]
 * convierte cada fallo en estas clases para que los repositorios distingan restricciones y
 * disco lleno exactamente igual que en Android.
 */
open class SQLiteException(message: String? = null, cause: Throwable? = null) :
    RuntimeException(message, cause)

class SQLiteConstraintException(message: String? = null, cause: Throwable? = null) :
    SQLiteException(message, cause)

class SQLiteFullException(message: String? = null, cause: Throwable? = null) :
    SQLiteException(message, cause)

class SQLiteDatabaseCorruptException(message: String? = null, cause: Throwable? = null) :
    SQLiteException(message, cause)

private val ERROR_CODE = Regex("""Error code:\s*(\d+)""")

private const val SQLITE_CORRUPT = 11
private const val SQLITE_FULL = 13
private const val SQLITE_CONSTRAINT = 19
private const val SQLITE_NOTADB = 26

/** Traduce el código primario de SQLite (byte bajo del código extendido). */
internal fun translateSQLiteFailure(failure: Throwable): Throwable {
    if (failure is SQLiteException) return failure
    if (failure !is androidx.sqlite.SQLiteException) return failure
    val message = failure.message
    val code = message?.let { ERROR_CODE.find(it)?.groupValues?.get(1)?.toIntOrNull() }
    val primary = code?.and(0xff)
    return when {
        primary == SQLITE_CONSTRAINT || message?.contains("constraint failed", ignoreCase = true) == true ->
            SQLiteConstraintException(message, failure)
        primary == SQLITE_FULL -> SQLiteFullException(message, failure)
        primary == SQLITE_CORRUPT || primary == SQLITE_NOTADB ->
            SQLiteDatabaseCorruptException(message, failure)
        else -> SQLiteException(message, failure)
    }
}

internal inline fun <T> translatingSQLite(block: () -> T): T = try {
    block()
} catch (failure: androidx.sqlite.SQLiteException) {
    throw translateSQLiteFailure(failure)
}
