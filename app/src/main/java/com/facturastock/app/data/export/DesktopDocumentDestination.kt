package com.facturastock.app.data.export

import java.io.File
import java.net.URI

/**
 * En Windows el usuario elige el destino con el diálogo "Guardar como"; la ruta viaja por el
 * dominio como URI `file:` (mismo contrato de texto que el `content://` de Android).
 */
internal fun documentFileOrNull(documentUri: String): File? = runCatching {
    val uri = URI(documentUri)
    if (!uri.scheme.equals("file", ignoreCase = true)) return null
    File(uri).absoluteFile.takeIf { it.parentFile?.isDirectory == true && !it.isDirectory }
}.getOrNull()

/** Intenta borrar el destino parcial; si no puede, lo trunca y confirma que quedó vacío. */
internal fun cleanupPartialDocument(file: File): Boolean {
    if (!file.exists()) return true
    if (runCatching { file.delete() }.getOrDefault(false)) return true
    val truncated = runCatching { file.outputStream().use { } ; true }.getOrDefault(false)
    return truncated && runCatching { file.length() == 0L }.getOrDefault(false)
}
