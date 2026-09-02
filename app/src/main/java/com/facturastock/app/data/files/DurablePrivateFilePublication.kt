package com.facturastock.app.data.files

import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
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
 * La sincronización de directorios no está implementada de forma uniforme por todos los
 * filesystems/OEM Android. Solo `EINVAL`, `ENOTSUP` y `EOPNOTSUPP` se consideran una ausencia
 * explícita de esa capacidad. Cualquier otro error (incluidos `EIO` y `ENOSPC`) se propaga: el
 * llamador debe fallar antes de guardar la ruta en Room. Un fallo posterior al rename puede dejar
 * un archivo íntegro pero aún no referenciado; un retry puede revalidarlo y completar la barrera.
 */
internal object DurablePrivateFilePublication {

    /**
     * Reemplazo POSIX atómico dentro del mismo directorio. Usar directamente `rename(2)` evita
     * que un proveedor NIO emule el fallback de `Files.move` mediante copia sobre el nombre
     * publicado, lo que lo haría observable a medias tras una muerte de proceso.
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
            Os.rename(source.absolutePath, destination.absolutePath)
        } catch (failure: ErrnoException) {
            throw IOException("No se pudo renombrar la publicación privada", failure)
        }
    }

    /**
     * Sincroniza bytes y metadatos del archivo. Se abre `O_RDWR|O_NOFOLLOW`, sin `O_TRUNC`, y se
     * revalida el descriptor; [flush] precede explícitamente a `fd.sync()` aunque esta llamada
     * normalmente no tenga datos nuevos en Java.
     */
    @Throws(IOException::class)
    fun syncFile(file: File) {
        val descriptor = try {
            Os.open(
                file.absolutePath,
                compatibleOpenFlags(OsConstants.O_RDWR or OsConstants.O_NOFOLLOW),
                0,
            )
        } catch (failure: ErrnoException) {
            throw IOException("No se pudo abrir la publicación privada para sincronizarla", failure)
        }
        FileOutputStream(descriptor).use { output ->
            val stat = try {
                Os.fstat(descriptor)
            } catch (failure: ErrnoException) {
                throw IOException("No se pudo revalidar la publicación privada", failure)
            }
            if (!OsConstants.S_ISREG(stat.st_mode)) {
                throw IOException("La publicación privada no es un archivo regular")
            }
            output.flush()
            output.fd.sync()
        }
    }

    /**
     * Sincroniza el directorio padre y cada ancestro hasta [durabilityRoot], inclusive. Esto
     * persiste tanto el nombre final como cualquier directorio creado con `mkdirs()` para llegar
     * a él. La ruta se comprueba léxicamente y cada descriptor se revalida sin seguir symlinks.
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
        if (
            Files.isSymbolicLink(directory.toPath()) ||
            !Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)
        ) {
            throw IOException("El padre de la publicación privada no es un directorio seguro")
        }
        val descriptor = try {
            Os.open(
                directory.absolutePath,
                compatibleOpenFlags(OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW),
                0,
            )
        } catch (failure: ErrnoException) {
            throw IOException("No se pudo abrir el directorio para sincronizarlo", failure)
        }
        try {
            val stat = try {
                Os.fstat(descriptor)
            } catch (failure: ErrnoException) {
                throw IOException("No se pudo revalidar el directorio privado", failure)
            }
            if (!OsConstants.S_ISDIR(stat.st_mode)) {
                throw IOException("La ruta abierta ya no es un directorio privado")
            }
            try {
                Os.fsync(descriptor)
            } catch (failure: ErrnoException) {
                if (!isUnsupportedDirectorySyncErrno(failure.errno)) {
                    throw IOException("No se pudo sincronizar el directorio privado", failure)
                }
            }
        } finally {
            // Después de un fsync exitoso, un fallo al cerrar no revierte la durabilidad. Además,
            // algunos wrappers OEM reportan EBADF al cerrar un descriptor ya invalidado.
            try {
                Os.close(descriptor)
            } catch (_: ErrnoException) {
                // El descriptor ya no puede reutilizarse; no se degrada un fsync confirmado.
            }
        }
    }

    /** `O_CLOEXEC` no forma parte del SDK público de Android 8.0 (API 26). */
    private fun compatibleOpenFlags(baseFlags: Int): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            baseFlags or OsConstants.O_CLOEXEC
        } else {
            baseFlags
        }
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
    errno == OsConstants.EINVAL ||
        errno == OsConstants.ENOTSUP ||
        errno == OsConstants.EOPNOTSUPP
