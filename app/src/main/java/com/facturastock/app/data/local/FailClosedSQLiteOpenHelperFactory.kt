package com.facturastock.app.data.local

import android.database.sqlite.SQLiteException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Conserva la base ante corrupción en lugar de aceptar la eliminación automática heredada de
 * [SupportSQLiteOpenHelper.Callback]. La aplicación queda bloqueada hasta que exista una ruta de
 * recuperación explícita; preservar el único original es preferible a sustituirlo
 * silenciosamente.
 */
@Singleton
class FailClosedSQLiteOpenHelperFactory @Inject constructor() : SupportSQLiteOpenHelper.Factory {
    private val delegate = FrameworkSQLiteOpenHelperFactory()

    override fun create(
        configuration: SupportSQLiteOpenHelper.Configuration,
    ): SupportSQLiteOpenHelper {
        check(!configuration.allowDataLossOnRecovery) {
            "La recuperación destructiva de la base local está prohibida"
        }
        return delegate.create(configuration.withFailClosedCorruptionHandler())
    }
}

internal fun SupportSQLiteOpenHelper.Configuration.withFailClosedCorruptionHandler():
    SupportSQLiteOpenHelper.Configuration =
    SupportSQLiteOpenHelper.Configuration.builder(context)
        .name(name)
        .callback(callback.failClosedOnCorruption())
        .noBackupDirectory(useNoBackupDirectory)
        .allowDataLossOnRecovery(false)
        .build()

internal fun SupportSQLiteOpenHelper.Callback.failClosedOnCorruption():
    SupportSQLiteOpenHelper.Callback {
    val delegate = this
    return object : SupportSQLiteOpenHelper.Callback(delegate.version) {
        override fun onConfigure(db: SupportSQLiteDatabase) = delegate.onConfigure(db)

        override fun onCreate(db: SupportSQLiteDatabase) = delegate.onCreate(db)

        override fun onUpgrade(
            db: SupportSQLiteDatabase,
            oldVersion: Int,
            newVersion: Int,
        ) = delegate.onUpgrade(db, oldVersion, newVersion)

        override fun onDowngrade(
            db: SupportSQLiteDatabase,
            oldVersion: Int,
            newVersion: Int,
        ) = delegate.onDowngrade(db, oldVersion, newVersion)

        override fun onOpen(db: SupportSQLiteDatabase) = delegate.onOpen(db)

        override fun onCorruption(db: SupportSQLiteDatabase) {
            throw SQLiteException(CORRUPTION_FAILURE_MESSAGE)
        }
    }
}

internal const val CORRUPTION_FAILURE_MESSAGE: String =
    "La base local no superó la comprobación de integridad; no se eliminó automáticamente"
