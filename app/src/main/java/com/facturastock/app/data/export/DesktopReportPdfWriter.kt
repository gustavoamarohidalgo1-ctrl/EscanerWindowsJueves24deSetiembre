package com.facturastock.app.data.export

import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.platform.AppDirectories
import com.facturastock.app.domain.model.PreparedReportPdf
import com.facturastock.app.domain.model.ReportPdfWriteStatus
import com.facturastock.app.domain.repository.ReportPdfWriter
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Genera en caché privada y luego copia al archivo que el usuario eligió en "Guardar como". */
@Singleton
class DesktopReportPdfWriter
    @Inject
    constructor(
        private val directories: AppDirectories,
        private val dispatchers: DispatcherProvider,
    ) : ReportPdfWriter {
        override suspend fun write(
            documentUri: String,
            prepared: PreparedReportPdf,
        ): ReportPdfWriteStatus {
            val destination = documentFileOrNull(documentUri)
                ?: return ReportPdfWriteStatus.FAILED_DESTINATION_CLEAN
            var touched = false
            var staging: File? = null
            return try {
                withContext(dispatchers.io) {
                    val temporary = File.createTempFile("report-pdf-", ".pdf", directories.cacheDir)
                    staging = temporary
                    temporary.outputStream().use { ReportPdfRenderer().write(prepared, it) }
                    currentCoroutineContext().ensureActive()
                    touched = true
                    Files.copy(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    ReportPdfWriteStatus.WRITTEN
                }
            } catch (cancelled: CancellationException) {
                if (!touched || cleanup(destination)) throw cancelled
                ReportPdfWriteStatus.FAILED_DESTINATION_MAY_CONTAIN_PARTIAL_DATA
            } catch (_: Exception) {
                if (!touched || cleanup(destination)) {
                    ReportPdfWriteStatus.FAILED_DESTINATION_CLEAN
                } else {
                    ReportPdfWriteStatus.FAILED_DESTINATION_MAY_CONTAIN_PARTIAL_DATA
                }
            } finally {
                withContext(NonCancellable + dispatchers.io) { staging?.delete() }
            }
        }

        private suspend fun cleanup(file: File): Boolean =
            withContext(NonCancellable + dispatchers.io) { cleanupPartialDocument(file) }
    }
