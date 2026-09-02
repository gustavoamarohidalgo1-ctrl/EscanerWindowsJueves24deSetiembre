package com.facturastock.app.data.files

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import com.facturastock.app.domain.model.id.DraftId

/**
 * Resolución segura de rutas relativas contra el almacenamiento privado de la app. Compara
 * por ruta canónica para neutralizar intentos de path traversal (`../`): solo se acepta lo
 * que quede estrictamente dentro del directorio raíz. La comparten el almacén de archivos y
 * el importador de imágenes para garantizar que ambos hablan de las mismas rutas.
 */
internal object PrivateFileResolver {

    /**
     * Namespace destructivo mínimo de una fuente original. Impide que una ruta corrupta de Room
     * convierta un puerto de imágenes en borrado/cifrado arbitrario dentro de `filesDir`.
     * Se permiten nombres legacy, pero solo como archivo directo de un draft UUID y con extensión
     * de imagen soportada; `ocr/`, DataStore y bases quedan fuera por construcción.
     */
    fun isDraftImageFilePath(relativePath: String): Boolean {
        val normalized = relativePath.replace('\\', '/')
        if (normalized != relativePath || normalized.startsWith('/') || normalized.contains("//")) {
            return false
        }
        val parts = normalized.split('/')
        if (parts.size != 3 || parts[0] != LocalDraftImageImporter.IMAGE_DIRECTORY) return false
        if (DraftId.parse(parts[1]) == null) return false
        val name = parts[2]
        if (name == "." || name == ".." || name.contains("..")) return false
        return name.substringAfterLast('.', missingDelimiterValue = "").lowercase() in
            SUPPORTED_IMAGE_EXTENSIONS
    }

    /**
     * Resuelve solo por componentes léxicos. Es apto exclusivamente para operaciones NIO que no
     * siguen symlinks; permite borrar el enlace mismo sin resolver su destino fuera del sandbox.
     */
    fun resolveLexicallyInside(rootDirectory: File, relativePath: String): File? {
        val root = rootDirectory.toPath().toAbsolutePath().normalize()
        val resolved = root.resolve(relativePath).normalize()
        return resolved
            .takeIf { it != root && it.startsWith(root) }
            ?.toFile()
    }

    /**
     * Resuelve para borrar sin seguir links y verifica cada ancestro existente con NOFOLLOW.
     * El leaf puede ser un symlink (se borra el enlace); ningún componente intermedio puede serlo.
     */
    fun resolveForNoFollowDeletion(rootDirectory: File, relativePath: String): File? {
        val root = rootDirectory.toPath().toAbsolutePath().normalize()
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
            return null
        }
        val target = root.resolve(relativePath).normalize()
        if (target == root || !target.startsWith(root)) return null

        var current = root
        val relative = root.relativize(target)
        for (index in 0 until relative.nameCount - 1) {
            current = current.resolve(relative.getName(index))
            if (Files.notExists(current, LinkOption.NOFOLLOW_LINKS)) break
            if (
                Files.isSymbolicLink(current) ||
                !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)
            ) {
                return null
            }
        }
        return target.toFile()
    }

    /**
     * Resuelve [relativePath] dentro de [rootDirectory]; devuelve null si la ruta canónica
     * resultante cae fuera de la raíz (o coincide con ella).
     */
    fun resolveInside(rootDirectory: File, relativePath: String): File? {
        val rootPath = rootDirectory.canonicalPath
        val resolved = File(rootDirectory, relativePath).canonicalFile
        return resolved.takeIf { it.path.startsWith(rootPath + File.separator) }
    }

    private val SUPPORTED_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
}
