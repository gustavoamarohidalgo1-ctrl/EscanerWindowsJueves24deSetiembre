package com.facturastock.app.data.restore

import java.io.Closeable
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Locale

/**
 * Barrera de durabilidad de un directorio. En POSIX abre el directorio y ejecuta `fsync`; en
 * Windows el JDK no puede abrir un directorio como canal y NTFS registra en su diario las
 * entradas de directorio junto con el rename, así que la barrera solo revalida la ruta.
 */
internal class DirectoryDurability private constructor(
    private val path: Path,
    private val channel: FileChannel?,
) : Closeable {
    @Throws(IOException::class)
    fun force(metaData: Boolean) {
        if (channel != null) {
            channel.force(metaData)
        } else if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("El directorio ya no existe o dejó de ser seguro")
        }
    }

    override fun close() {
        channel?.close()
    }

    companion object {
        private val isWindows: Boolean =
            System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT).startsWith("windows")

        @Throws(IOException::class)
        fun open(path: Path): DirectoryDurability {
            if (isWindows) {
                if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw IOException("El directorio no es seguro")
                }
                return DirectoryDurability(path, null)
            }
            return DirectoryDurability(
                path,
                FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
            )
        }
    }
}
