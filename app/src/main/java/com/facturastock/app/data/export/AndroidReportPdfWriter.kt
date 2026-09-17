package com.facturastock.app.data.export

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.domain.model.PreparedReportPdf
import com.facturastock.app.domain.model.ReportPdfWriteStatus
import com.facturastock.app.domain.repository.ReportPdfWriter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Genera en caché privada antes de tocar el documento elegido por el usuario mediante SAF. */
@Singleton
class AndroidReportPdfWriter
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val dispatchers: DispatcherProvider,
    ) : ReportPdfWriter {
        private val resolver = context.contentResolver

        override suspend fun write(
            documentUri: String,
            prepared: PreparedReportPdf,
        ): ReportPdfWriteStatus {
            val uri = documentUri.toUri()
            if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri.authority.isNullOrBlank()) {
                return ReportPdfWriteStatus.FAILED_DESTINATION_CLEAN
            }
            var touched = false
            var staging: File? = null
            return try {
                withContext(dispatchers.io) {
                    val temporary = File.createTempFile("report-pdf-", ".pdf", context.cacheDir)
                    staging = temporary
                    temporary.outputStream().use { ReportPdfRenderer().write(prepared, it) }
                    currentCoroutineContext().ensureActive()
                    touched = true
                    val destination = resolver.openOutputStream(uri, "wt") ?: throw IOException("Destino PDF no disponible")
                    destination.use { output ->
                        temporary.inputStream().use { input ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                            output.flush()
                        }
                    }
                    ReportPdfWriteStatus.WRITTEN
                }
            } catch (cancelled: CancellationException) {
                if (!touched || cleanup(uri)) throw cancelled
                ReportPdfWriteStatus.FAILED_DESTINATION_MAY_CONTAIN_PARTIAL_DATA
            } catch (_: Exception) {
                if (!touched || cleanup(uri)) {
                    ReportPdfWriteStatus.FAILED_DESTINATION_CLEAN
                } else {
                    ReportPdfWriteStatus.FAILED_DESTINATION_MAY_CONTAIN_PARTIAL_DATA
                }
            } finally {
                withContext(NonCancellable + dispatchers.io) { staging?.delete() }
            }
        }

        private suspend fun cleanup(uri: Uri): Boolean =
            withContext(NonCancellable + dispatchers.io) {
                val deleted =
                    runCatching {
                        // DocumentsProvider.delete es final y rechaza el delete genérico de ContentResolver.
                        if (DocumentsContract.isDocumentUri(context, uri)) {
                            DocumentsContract.deleteDocument(resolver, uri)
                        } else {
                            resolver.delete(uri, null, null) > 0
                        }
                    }.getOrDefault(false)
                if (deleted) return@withContext true
                val truncated = runCatching { resolver.openOutputStream(uri, "wt")?.use { true } ?: false }.getOrDefault(false)
                truncated && runCatching { resolver.openFileDescriptor(uri, "r")?.use { it.statSize == 0L } == true }.getOrDefault(false)
            }
    }
