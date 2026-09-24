package com.facturastock.app.data.files

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import javax.inject.Inject

/**
 * Frontera de durabilidad para archivos privados que después serán referenciados por Room.
 *
 * El orden obligatorio de una publicación es:
 *
 * 1. vaciar los buffers de usuario y ejecutar [java.io.FileDescriptor.sync] sobre el temporal;
 * 2. publicar por rename/link dentro del mismo almacenamiento privado;
 * 3. volver a sincronizar el nombre publicado y los directorios que contienen sus entradas.
 *
 * En escritorio la sincronización de archivos usa [FileChannel.force] con metadatos (`fsync`
 * en POSIX, `FlushFileBuffers` en Windows). La de directorios depende del sistema operativo:
 *
 * - **POSIX (Linux/macOS)**: el JDK permite abrir el directorio en solo lectura y `force`
 *   ejecuta `fsync` sobre él. Solo `EINVAL`, `ENOTSUP` y `EOPNOTSUPP` se consideran una
 *   ausencia explícita de esa capacidad. Cualquier otro error (incluidos `EIO` y `ENOSPC`) se
 *   propaga: el llamador debe fallar antes de guardar la ruta en Room.
 * - **Windows**: el JDK abre archivos con `CreateFileW` sin `FILE_FLAG_BACKUP_SEMANTICS`, de modo
 *   que un directorio no puede abrirse como canal (y `FlushFileBuffers` sobre un directorio
 *   exige además escritura). Se intenta igualmente y el rechazo del sistema se ignora: NTFS
 *   registra en su journal de metadatos el rename/creación de entradas, y la entrada ya quedó
 *   persistida junto con los datos del archivo, que sí se sincronizaron con `force(true)`.
 *
 * Un fallo posterior al rename puede dejar un archivo íntegro pero aún no referenciado; un
 * retry puede revalidarlo y completar la barrera.
 */
internal object DurablePrivateFilePublication {

    /**
     * Reemplazo atómico dentro del mismo directorio con `Files.move(ATOMIC_MOVE)` (`rename(2)` en
     * POSIX, `MoveFileExW(MOVEFILE_REPLACE_EXISTING)` en Windows). Si el sistema no admite el
     * movimiento atómico se falla en lugar de dejar que NIO emule el reemplazo con una copia
     * sobre el nombre publicado, que lo haría observable a medias tras una muerte de proceso.
     */
    @Throws(IOException::class)
    fun replaceByRename(source: File, destination: File) {
        val sourceParent = source.parentFile?.toPath()?.toAbsolutePath()?.normalize()
            ?: throw IOException("El temporal privado no tiene directorio padre")
        val destinationParent = destination.parentFile?.toPath()?.toAbsolutePath()?.normalize()
            ?: throw IOException("El destino privado no tiene directorio padre")
        if (sourceParent != destinationParent) {
            throw IOException("El reemplazo privado debe ocurrir dentro del mismo directorio")
        }
        if (
            Files.isSymbolicLink(sourceParent) ||
            !Files.isDirectory(sourceParent, LinkOption.NOFOLLOW_LINKS)
        ) {
            throw IOException("El reemplazo privado no tiene un directorio padre seguro")
        }
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (failure: AtomicMoveNotSupportedException) {
            throw IOException("El sistema no admite el reemplazo atómico privado", failure)
        } catch (failure: IOException) {
            throw IOException("No se pudo renombrar la publicación privada", failure)
        }
    }

    /**
     * Sincroniza bytes y metadatos del archivo. Se abre en escritura sin truncar y sin seguir
     * enlaces (`NOFOLLOW_LINKS`), se revalida que siga siendo un archivo regular y se ejecuta
     * `force(true)`. Windows exige un handle con escritura para `FlushFileBuffers`.
     */
    @Throws(IOException::class)
    fun syncFile(file: File) {
        val path = file.toPath()
        if (!isRegularNoFollow(path)) {
            throw IOException("La publicación privada no es un archivo regular")
        }
        val channel = try {
            FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
        } catch (failure: IOException) {
            throw IOException("No se pudo abrir la publicación privada para sincronizarla", failure)
        } catch (failure: UnsupportedOperationException) {
            throw IOException("No se pudo abrir la publicación privada para sincronizarla", failure)
        }
        channel.use { opened ->
            // El JDK no expone `fstat` sobre el canal: se revalida la ruta ya abierta.
            if (!isRegularNoFollow(path)) {
                throw IOException("La publicación privada no es un archivo regular")
            }
            opened.force(true)
        }
    }

    /**
     * Sincroniza el directorio padre y cada ancestro hasta [durabilityRoot], inclusive. Esto
     * persiste tanto el nombre final como cualquier directorio creado con `mkdirs()` para llegar
     * a él. La ruta se comprueba léxicamente y cada directorio se revalida sin seguir symlinks.
     */
    @Throws(IOException::class)
    fun syncParentChainAfterRename(destination: File, durabilityRoot: File) {
        val rootPath = durabilityRoot.toPath().toAbsolutePath().normalize()
        var directory = destination.parentFile
            ?: throw IOException("La publicación privada no tiene directorio padre")
        while (true) {
            val directoryPath = directory.toPath().toAbsolutePath().normalize()
            if (!directoryPath.startsWith(rootPath)) {
                throw IOException("La publicación privada escapa de su raíz de durabilidad")
            }
            syncDirectoryIfSupported(directory)
            if (directoryPath == rootPath) return
            directory = directory.parentFile
                ?: throw IOException("La raíz de durabilidad no contiene la publicación")
        }
    }

    /** Sincroniza solo el padre, para reemplazos temporales que no crean ancestros nuevos. */
    @Throws(IOException::class)
    fun syncParentAfterRename(destination: File) {
        val parent = destination.parentFile
            ?: throw IOException("La publicación privada no tiene directorio padre")
        syncDirectoryIfSupported(parent)
    }

    /** Misma barrera estrecha, expuesta a la frontera de eliminaciones durables. */
    @Throws(IOException::class)
    fun syncDirectoryAfterMutation(directory: File) {
        syncDirectoryIfSupported(directory)
    }

    private fun syncDirectoryIfSupported(directory: File) {
        val path = directory.toPath()
        if (
            Files.isSymbolicLink(path) ||
            !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
        ) {
            throw IOException("El padre de la publicación privada no es un directorio seguro")
        }
        val channel = try {
            FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
        } catch (failure: IOException) {
            // Windows no permite abrir un directorio como canal desde el JDK (ver cabecera).
            if (isWindows) return
            throw IOException("No se pudo abrir el directorio para sincronizarlo", failure)
        } catch (failure: UnsupportedOperationException) {
            if (isWindows) return
            throw IOException("No se pudo abrir el directorio para sincronizarlo", failure)
        }
        try {
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
                throw IOException("La ruta abierta ya no es un directorio privado")
            }
            try {
                channel.force(true)
            } catch (failure: IOException) {
                if (!isWindows && !isUnsupportedDirectorySyncFailure(failure)) {
                    throw IOException("No se pudo sincronizar el directorio privado", failure)
                }
                // Windows: `FlushFileBuffers` sobre un handle de solo lectura se rechaza con
                // acceso denegado; es la misma ausencia de capacidad descrita en la cabecera.
            }
        } finally {
            // Después de un fsync exitoso, un fallo al cerrar no revierte la durabilidad.
            try {
                channel.close()
            } catch (_: IOException) {
                // El canal ya no puede reutilizarse; no se degrada un fsync confirmado.
            }
        }
    }

    private fun isRegularNoFollow(path: java.nio.file.Path): Boolean = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            .isRegularFile
    } catch (_: IOException) {
        false
    }

    private val isWindows: Boolean =
        System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT).startsWith("windows")
}

/**
 * Barreras posteriores a un rename, inyectables para comprobar retries de fallos que ocurren
 * cuando el nombre final ya es visible. El llamador solo puede confirmar una publicación tras
 * completar ambas barreras, en este orden.
 */
open class PrivatePublicationDurability @Inject constructor() {
    @Throws(IOException::class)
    open fun syncFile(file: File) {
        DurablePrivateFilePublication.syncFile(file)
    }

    @Throws(IOException::class)
    open fun syncParentAfterRename(destination: File) {
        DurablePrivateFilePublication.syncParentAfterRename(destination)
    }

    /** Persiste la creación de un directorio antes de publicar contenido dependiente dentro. */
    @Throws(IOException::class)
    open fun syncDirectoryAfterMutation(directory: File) {
        DurablePrivateFilePublication.syncDirectoryAfterMutation(directory)
    }
}

/**
 * Barrera de metadata para un unlink. Es una clase inyectable para poder simular EIO/ENOSPC en
 * pruebas sin debilitar la política productiva. Una ruta ya ausente también se sincroniza: puede
 * ser el retry de un unlink cuyo fsync anterior falló después de retirar el nombre.
 */
open class PrivateDeletionDurability @Inject constructor() {
    @Throws(IOException::class)
    open fun syncAfterDeletion(target: File, durabilityRoot: File) {
        val root = durabilityRoot.toPath().toAbsolutePath().normalize()
        val targetPath = target.toPath().toAbsolutePath().normalize()
        if (targetPath == root || !targetPath.startsWith(root)) {
            throw IOException("La eliminación privada escapa de su raíz de durabilidad")
        }
        if (
            Files.isSymbolicLink(root) ||
            !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
        ) {
            throw IOException("La raíz de eliminación privada no es segura")
        }
        var directory = targetPath.parent
            ?: throw IOException("La eliminación privada no tiene directorio padre")
        while (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) {
            if (directory == root) break
            directory = directory.parent
                ?: throw IOException("No existe un ancestro durable para la eliminación")
        }
        if (!directory.startsWith(root) || Files.isSymbolicLink(directory) ||
            !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
        ) {
            throw IOException("El padre durable de la eliminación no es seguro")
        }
        DurablePrivateFilePublication.syncDirectoryAfterMutation(directory.toFile())
    }

    /** Sincroniza una carpeta recorrida para cerrar retries de hojas ya ausentes. */
    @Throws(IOException::class)
    open fun syncDirectory(directory: File, durabilityRoot: File) {
        val root = durabilityRoot.toPath().toAbsolutePath().normalize()
        val path = directory.toPath().toAbsolutePath().normalize()
        if (!path.startsWith(root) || Files.isSymbolicLink(path) ||
            !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
        ) {
            throw IOException("El directorio de eliminación privada no es seguro")
        }
        DurablePrivateFilePublication.syncDirectoryAfterMutation(directory)
    }

    @Throws(IOException::class)
    fun deleteTree(target: File, durabilityRoot: File): Boolean {
        if (!NoFollowFileTree.delete(target)) return false
        syncAfterDeletion(target, durabilityRoot)
        return true
    }
}

/** Política estrecha y comprobable: errores de medio/capacidad nunca se confunden con soporte. */
internal fun isUnsupportedDirectorySyncErrno(errno: Int): Boolean =
    errno in UNSUPPORTED_DIRECTORY_SYNC_ERRNOS

/**
 * El JDK no expone `errno` en [IOException]: sus mensajes nativos reproducen `strerror`. Se
 * reconocen solo los textos de `EINVAL`, `ENOTSUP` y `EOPNOTSUPP`; un acceso denegado explícito
 * ([AccessDeniedException]) nunca se interpreta como falta de soporte.
 */
internal fun isUnsupportedDirectorySyncFailure(failure: IOException): Boolean {
    if (failure is AccessDeniedException) return false
    val text = failure.message?.lowercase(Locale.ROOT).orEmpty()
    return "invalid argument" in text ||
        "operation not supported" in text ||
        "not supported" in text
}

// EINVAL es 22 en todas las plataformas; ENOTSUP/EOPNOTSUPP son 95 en Linux y 45/102 en macOS.
private val UNSUPPORTED_DIRECTORY_SYNC_ERRNOS = setOf(22, 95, 45, 102)
