package com.facturastock.app.data.files

import com.facturastock.app.domain.model.id.DraftId
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex

/**
 * Exclusión compartida para toda mutación del árbol privado de un borrador.
 *
 * Importación, OCR, cifrado, retención y barridos viven en singletons distintos, pero operan
 * sobre `draft_images/{draftId}`. Un mutex local en cada clase no evita que un borrado se
 * intercale con un replace atómico y termine borrando el nuevo archivo o resucitando el antiguo.
 * Este coordinador usa una sola clave por borrador y libera entradas inactivas para que el mapa
 * no crezca con el historial de facturas.
 */
@Singleton
class PrivateImageMutationCoordinator @Inject constructor() {
    private val entriesGuard = Any()
    private val entries = mutableMapOf<String, LockEntry>()

    suspend fun <T> withDraftLock(draftId: DraftId, action: suspend () -> T): T =
        withKey("draft:${draftId.value}", action)

    /**
     * Usa el lock del borrador cuando la ruta tiene el esquema canónico de la app. Las rutas
     * legacy o inválidas reciben una clave exacta; la validación anti-traversal sigue siendo
     * responsabilidad de [PrivateFileResolver].
     */
    suspend fun <T> withRelativePathLock(relativePath: String, action: suspend () -> T): T {
        val normalizedPath = runCatching {
            File(relativePath).toPath().normalize().toString().replace(File.separatorChar, '/')
        }.getOrDefault(relativePath)
        val draftId = normalizedPath.split('/', limit = 3)
            .takeIf { parts ->
                parts.size >= 2 && parts[0] == LocalDraftImageImporter.IMAGE_DIRECTORY
            }
            ?.get(1)
            ?.let(DraftId::parse)
        return if (draftId != null) {
            withDraftLock(draftId, action)
        } else {
            withKey("path:$normalizedPath", action)
        }
    }

    private suspend fun <T> withKey(key: String, action: suspend () -> T): T {
        val entry = synchronized(entriesGuard) {
            entries.getOrPut(key) { LockEntry() }.also { it.users += 1 }
        }
        var locked = false
        return try {
            entry.mutex.lock()
            locked = true
            action()
        } finally {
            if (locked) entry.mutex.unlock()
            synchronized(entriesGuard) {
                entry.users -= 1
                if (entry.users == 0 && entries[key] === entry) entries.remove(key)
            }
        }
    }

    private class LockEntry(
        val mutex: Mutex = Mutex(),
        var users: Int = 0,
    )
}
