package com.facturastock.app.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.IOException

/**
 * Equivalente de escritorio de `ActivityResultContracts.CreateDocument`: abre el diálogo nativo
 * "Guardar como" de Windows y devuelve el destino como URI `file:` (o `null` si se cancela).
 */
class SaveFileLauncher internal constructor(
    private val extension: String,
    private val onResult: () -> (String?) -> Unit,
) {
    fun launch(suggestedFileName: String) {
        val dialog = FileDialog(null as Frame?, "Guardar como", FileDialog.SAVE).apply {
            directory = defaultDocumentsDirectory().absolutePath
            file = suggestedFileName
            setFilenameFilter { _, name -> name.endsWith(".$extension", ignoreCase = true) }
            isMultipleMode = false
        }
        dialog.isVisible = true
        val chosenName = dialog.file
        val chosenDirectory = dialog.directory
        dialog.dispose()
        val uri = if (chosenName.isNullOrBlank() || chosenDirectory.isNullOrBlank()) {
            null
        } else {
            val withExtension = if (chosenName.endsWith(".$extension", ignoreCase = true)) {
                chosenName
            } else {
                "$chosenName.$extension"
            }
            File(chosenDirectory, withExtension).absoluteFile.toURI().toString()
        }
        onResult()(uri)
    }
}

@Composable
fun rememberSaveFileLauncher(extension: String, onResult: (String?) -> Unit): SaveFileLauncher {
    val currentOnResult = rememberUpdatedState(onResult)
    return remember(extension) { SaveFileLauncher(extension) { currentOnResult.value } }
}

/** Abre un documento con la aplicación predeterminada de Windows (visor de PDF, etc.). */
fun openWithSystemViewer(documentUri: String): Boolean = try {
    val file = File(java.net.URI(documentUri))
    if (!file.isFile || !Desktop.isDesktopSupported() ||
        !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)
    ) {
        false
    } else {
        Desktop.getDesktop().open(file)
        true
    }
} catch (_: IOException) {
    false
} catch (_: IllegalArgumentException) {
    false
} catch (_: SecurityException) {
    false
} catch (_: UnsupportedOperationException) {
    false
}

private fun defaultDocumentsDirectory(): File {
    val home = File(System.getProperty("user.home"))
    return listOf(home.resolve("Documents"), home.resolve("Documentos"), home)
        .first { it.isDirectory }
}

/**
 * Equivalente de `PickVisualMedia(ImageOnly)`: diálogo nativo "Abrir" filtrado a imágenes.
 * Devuelve la imagen como URI `file:`; el importador la copia de inmediato al área privada.
 */
fun pickImageFile(): String? {
    val dialog = FileDialog(null as Frame?, "Elegir imagen de la factura", FileDialog.LOAD).apply {
        directory = defaultPicturesDirectory().absolutePath
        file = "*.jpg;*.jpeg;*.png;*.webp;*.bmp"
        setFilenameFilter { _, name ->
            IMAGE_EXTENSIONS.any { extension -> name.endsWith(".$extension", ignoreCase = true) }
        }
        isMultipleMode = false
    }
    dialog.isVisible = true
    val name = dialog.file
    val directory = dialog.directory
    dialog.dispose()
    if (name.isNullOrBlank() || directory.isNullOrBlank()) return null
    val file = File(directory, name).absoluteFile
    return file.takeIf(File::isFile)?.toURI()?.toString()
}

private val IMAGE_EXTENSIONS = listOf("jpg", "jpeg", "png", "webp", "bmp")

private fun defaultPicturesDirectory(): File {
    val home = File(System.getProperty("user.home"))
    return listOf(home.resolve("Pictures"), home.resolve("Imágenes"), home.resolve("Downloads"), home)
        .first { it.isDirectory }
}
