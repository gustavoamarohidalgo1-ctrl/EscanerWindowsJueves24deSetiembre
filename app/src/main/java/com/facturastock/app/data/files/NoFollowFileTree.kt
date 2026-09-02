package com.facturastock.app.data.files

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Operaciones destructivas que jamás siguen enlaces simbólicos, ni siquiera anidados. */
internal object NoFollowFileTree {
    /**
     * Enumera hojas sin seguir symlinks y las devuelve relativas a [baseDirectory]. `null`
     * significa que la forma del árbol o su lectura no pudo verificarse de manera segura.
     */
    fun relativeLeafPaths(baseDirectory: File, target: File): List<String>? {
        val base = baseDirectory.toPath().toAbsolutePath().normalize()
        val root = target.toPath().toAbsolutePath().normalize()
        if (root == base || !root.startsWith(base)) return null
        if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        if (
            Files.isSymbolicLink(root) ||
            !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
        ) {
            return null
        }
        val relativePaths = mutableListOf<String>()
        return try {
            Files.walkFileTree(
                root,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        val normalized = file.toAbsolutePath().normalize()
                        if (!normalized.startsWith(base)) throw IOException("Hoja fuera del root")
                        relativePaths += base.relativize(normalized).toString()
                            .replace(File.separatorChar, '/')
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                        throw exc
                    }

                    override fun postVisitDirectory(
                        dir: Path,
                        exc: IOException?,
                    ): FileVisitResult {
                        if (exc != null) throw exc
                        return FileVisitResult.CONTINUE
                    }
                },
            )
            relativePaths
        } catch (_: Exception) {
            null
        }
    }

    /** true solo cuando [target] ya no existe como archivo, directorio ni enlace. */
    fun delete(target: File): Boolean {
        val root = target.toPath()
        if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) return true
        return try {
            Files.walkFileTree(
                root,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        Files.delete(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                        throw exc
                    }

                    override fun postVisitDirectory(
                        dir: Path,
                        exc: IOException?,
                    ): FileVisitResult {
                        if (exc != null) throw exc
                        Files.delete(dir)
                        return FileVisitResult.CONTINUE
                    }
                },
            )
            Files.notExists(root, LinkOption.NOFOLLOW_LINKS)
        } catch (_: Exception) {
            false
        }
    }
}
