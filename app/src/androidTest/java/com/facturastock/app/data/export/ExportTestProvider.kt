package com.facturastock.app.data.export

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

/** Provider privado y exclusivo del APK de pruebas para verificar un cierre SAF real. */
class ExportTestProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        openCallCount++
        lastOpenMode = mode
        val file = File(
            checkNotNull(outputDirectory) { "El test debe fijar un directorio privado escribible" },
            "user-export-test.json",
        )
        lastOutputFile = file
        if (blockPrimaryWrite && openCallCount == 1) {
            file.writeText("{\"partial\":", Charsets.UTF_8)
            primaryWriteEntered.countDown()
            check(releasePrimaryWrite.await(5, TimeUnit.SECONDS)) {
                "El test no libero el provider bloqueado"
            }
        }
        if (cancelPrimaryWrite && openCallCount == 1) {
            file.writeText("{\"partial\":", Charsets.UTF_8)
            throw CancellationException("cancelación SAF simulada")
        }
        if (failPrimaryWrite && openCallCount == 1) {
            file.writeText("{\"partial\":", Charsets.UTF_8)
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        if (failCleanupWrite && openCallCount >= 2 && mode.contains('w')) {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        if (!mode.contains('w')) {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        return ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_TRUNCATE or
                ParcelFileDescriptor.MODE_READ_WRITE,
        )
    }

    override fun getType(uri: Uri): String = "application/json"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        deleteCallCount++
        if (!deleteSucceeds) return 0
        return if (lastOutputFile?.delete() == true) 1 else 0
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        @Volatile
        var lastOutputFile: File? = null

        @Volatile
        var outputDirectory: File? = null

        @Volatile
        var openCallCount: Int = 0

        @Volatile
        var lastOpenMode: String? = null

        @Volatile
        var deleteCallCount: Int = 0

        @Volatile
        var deleteSucceeds: Boolean = false

        @Volatile
        var failPrimaryWrite: Boolean = false

        @Volatile
        var failCleanupWrite: Boolean = false

        @Volatile
        var cancelPrimaryWrite: Boolean = false

        @Volatile
        var blockPrimaryWrite: Boolean = false

        @Volatile
        var primaryWriteEntered: CountDownLatch = CountDownLatch(1)

        @Volatile
        var releasePrimaryWrite: CountDownLatch = CountDownLatch(1)
    }
}
