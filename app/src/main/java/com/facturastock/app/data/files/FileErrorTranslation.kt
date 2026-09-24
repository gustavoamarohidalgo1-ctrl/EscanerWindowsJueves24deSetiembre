package com.facturastock.app.data.files

import com.facturastock.app.domain.error.FileError
import com.facturastock.app.domain.error.FileException
import java.io.IOException
import java.util.Locale

/**
 * Traducción compartida de fallos de E/S a errores de dominio para la capa de archivos:
 * la falta de espacio se reconoce por la heurística `ENOSPC`/"no space left" (y los mensajes
 * de Windows "not enough space on the disk" / "espacio en disco insuficiente") en el mensaje
 * y se traduce a [FileError.InsufficientSpace]; cualquier otro corte deja el contenido
 * inutilizable y se traduce a [FileError.Corrupt].
 */
internal fun IOException.toFileException(): FileException =
    if (isInsufficientSpace()) {
        FileException(FileError.InsufficientSpace, this)
    } else {
        FileException(FileError.Corrupt, this)
    }

private fun IOException.isInsufficientSpace(): Boolean {
    val text = message?.lowercase(Locale.ROOT).orEmpty()
    return "enospc" in text || "no space left" in text ||
        "not enough space" in text || "disk is full" in text ||
        "espacio suficiente" in text || "espacio en disco insuficiente" in text ||
        (this is java.nio.file.FileSystemException && "space" in text)
}
