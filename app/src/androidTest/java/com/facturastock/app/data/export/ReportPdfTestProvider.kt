package com.facturastock.app.data.export

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Stub SAF privado: deleteDocument llega por call; el delete genérico se rechaza como en DocumentsProvider. */
class ReportPdfTestProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        openCallCount++
        val file = File(checkNotNull(outputDirectory), "destination.pdf")
        lastOutputFile = file
        if (openCallCount == 1 && (failPrimaryWrite || cancelPrimaryWrite || blockPrimaryWrite)) {
            file.writeText(PARTIAL_CONTENT)
            if (blockPrimaryWrite) {
                primaryWriteEntered.countDown()
                check(releasePrimaryWrite.await(10, TimeUnit.SECONDS)) { "El test no liberó el proveedor" }
            }
            if (cancelPrimaryWrite) throw CancellationException("Cancelación del proveedor SAF")
            if (failPrimaryWrite) return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        if (openCallCount > 1 && mode.contains('w') && failCleanupWrite) {
            throw FileNotFoundException("No se puede reabrir el destino para truncarlo")
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    @Suppress("DEPRECATION")
    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle? {
        if (method != "android:deleteDocument") throw UnsupportedOperationException(method)
        val uri = checkNotNull(extras?.getParcelable<Uri>("uri"))
        deleteDocument(DocumentsContract.getDocumentId(uri))
        return Bundle.EMPTY
    }

    private fun deleteDocument(documentId: String) {
        check(documentId == DOCUMENT_ID)
        deleteDocumentCalls++
        if (!deleteSucceeds) throw FileNotFoundException("El proveedor rechazó borrar el documento")
        check(checkNotNull(lastOutputFile).delete()) { "No se pudo eliminar el documento del test" }
    }

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        genericDeleteCalls++
        throw UnsupportedOperationException("DocumentsProvider.delete no soporta esta operación")
    }

    override fun getType(uri: Uri): String = "application/pdf"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        const val AUTHORITY = "com.facturastock.app.report-pdf-test"
        const val DOCUMENT_ID = "report.pdf"
        const val PARTIAL_CONTENT = "%PDF-partial-test"

        @Volatile var outputDirectory: File? = null

        @Volatile var lastOutputFile: File? = null

        @Volatile var openCallCount = 0

        @Volatile var deleteDocumentCalls = 0

        @Volatile var genericDeleteCalls = 0

        @Volatile var deleteSucceeds = true

        @Volatile var failPrimaryWrite = false

        @Volatile var failCleanupWrite = false

        @Volatile var cancelPrimaryWrite = false

        @Volatile var blockPrimaryWrite = false

        @Volatile var primaryWriteEntered = CountDownLatch(1)

        @Volatile var releasePrimaryWrite = CountDownLatch(1)

        fun reset(directory: File) {
            outputDirectory = directory
            lastOutputFile = null
            openCallCount = 0
            deleteDocumentCalls = 0
            genericDeleteCalls = 0
            deleteSucceeds = true
            failPrimaryWrite = false
            failCleanupWrite = false
            cancelPrimaryWrite = false
            blockPrimaryWrite = false
            primaryWriteEntered = CountDownLatch(1)
            releasePrimaryWrite = CountDownLatch(1)
        }
    }
}
