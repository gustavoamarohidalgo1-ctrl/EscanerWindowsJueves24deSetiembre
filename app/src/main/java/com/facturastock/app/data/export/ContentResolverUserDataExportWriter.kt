package com.facturastock.app.data.export

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.domain.model.UserDataExport
import com.facturastock.app.domain.model.UserDataExportWriteStatus
import com.facturastock.app.domain.repository.UserDataExportWriter
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.io.OutputStreamWriter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Escribe la exportación en el documento `content://` elegido mediante SAF. */
@Singleton
class ContentResolverUserDataExportWriter @Inject constructor(
    @ApplicationContext context: Context,
    private val dispatchers: DispatcherProvider,
) : UserDataExportWriter {
    private val contentResolver: ContentResolver = context.contentResolver

    override suspend fun write(
        documentUri: String,
        export: UserDataExport,
    ): UserDataExportWriteStatus {
        val uri = documentUri.toUri()
        if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri.authority.isNullOrBlank()) {
            return UserDataExportWriteStatus.FAILED_DESTINATION_CLEAN
        }
        var destinationTouched = false
        return try {
            withContext(dispatchers.io) {
                // `openOutputStream("wt")` puede truncar antes de lanzar: desde este punto el
                // destino se considera tocado aunque no llegue a devolver un stream.
                destinationTouched = true
                val output = contentResolver.openOutputStream(uri, "wt")
                    ?: throw IOException("El proveedor SAF no abrió el destino")
                OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
                    UserDataExportJson.writeTo(export, writer)
                    writer.flush()
                }
                UserDataExportWriteStatus.WRITTEN
            }
        } catch (cancelled: CancellationException) {
            // También captura la cancelación prompt que withContext lanza DESPUÉS de que el
            // provider bloqueante terminó. La limpieza corta no hereda el Job ya cancelado.
            if (!destinationTouched || cleanupAfterFailure(uri)) throw cancelled
            UserDataExportWriteStatus.FAILED_DESTINATION_MAY_CONTAIN_PARTIAL_DATA
        } catch (_: Exception) {
            if (!destinationTouched || cleanupAfterFailure(uri)) {
                UserDataExportWriteStatus.FAILED_DESTINATION_CLEAN
            } else {
                UserDataExportWriteStatus.FAILED_DESTINATION_MAY_CONTAIN_PARTIAL_DATA
            }
        }
    }

    private suspend fun cleanupAfterFailure(uri: Uri): Boolean =
        withContext(NonCancellable + dispatchers.io) { cleanupPartialDocument(uri) }

    /** SAF no ofrece rename atómico genérico: intenta delete y luego truncate verificable. */
    private fun cleanupPartialDocument(uri: Uri): Boolean {
        val deleted = runCatching { contentResolver.delete(uri, null, null) > 0 }
            .getOrDefault(false)
        if (deleted) return true
        val truncated = runCatching {
            contentResolver.openOutputStream(uri, "wt")?.use { Unit } ?: return@runCatching false
            true
        }.getOrDefault(false)
        if (!truncated) return false
        return runCatching {
            contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.statSize == 0L
            } == true
        }.getOrDefault(false)
    }
}
