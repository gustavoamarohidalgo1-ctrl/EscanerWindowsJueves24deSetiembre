package com.facturastock.app.data.export

import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.domain.model.UserDataExport
import com.facturastock.app.domain.model.UserDataExportWriteStatus
import com.facturastock.app.domain.repository.UserDataExportWriter
import java.io.File
import java.io.OutputStreamWriter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Escribe la exportación JSON en el archivo elegido con el diálogo "Guardar como". */
@Singleton
class DesktopUserDataExportWriter @Inject constructor(
    private val dispatchers: DispatcherProvider,
) : UserDataExportWriter {
    override suspend fun write(
        documentUri: String,
        export: UserDataExport,
    ): UserDataExportWriteStatus {
        val destination = documentFileOrNull(documentUri)
            ?: return UserDataExportWriteStatus.FAILED_DESTINATION_CLEAN
        var destinationTouched = false
        return try {
            withContext(dispatchers.io) {
                destinationTouched = true
                OutputStreamWriter(destination.outputStream(), Charsets.UTF_8).use { writer ->
                    UserDataExportJson.writeTo(export, writer)
                    writer.flush()
                }
                UserDataExportWriteStatus.WRITTEN
            }
        } catch (cancelled: CancellationException) {
            if (!destinationTouched || cleanupAfterFailure(destination)) throw cancelled
            UserDataExportWriteStatus.FAILED_DESTINATION_MAY_CONTAIN_PARTIAL_DATA
        } catch (_: Exception) {
            if (!destinationTouched || cleanupAfterFailure(destination)) {
                UserDataExportWriteStatus.FAILED_DESTINATION_CLEAN
            } else {
                UserDataExportWriteStatus.FAILED_DESTINATION_MAY_CONTAIN_PARTIAL_DATA
            }
        }
    }

    private suspend fun cleanupAfterFailure(file: File): Boolean =
        withContext(NonCancellable + dispatchers.io) { cleanupPartialDocument(file) }
}
