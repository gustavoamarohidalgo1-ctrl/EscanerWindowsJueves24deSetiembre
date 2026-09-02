package com.facturastock.app.domain.repository

import com.facturastock.app.domain.model.PrivateImageDeletionResult
import com.facturastock.app.domain.model.OcrVersionSweepReport
import com.facturastock.app.domain.model.PrivateFileSweepReport
import com.facturastock.app.domain.model.RetainedImageEncryptionState
import com.facturastock.app.domain.model.RetainedImageMigrationState
import com.facturastock.app.domain.model.UserDataExport
import com.facturastock.app.domain.model.UserDataExportWriteStatus
import com.facturastock.app.domain.model.id.DraftId
import java.time.Instant

/**
 * Puerto del almacén de imágenes retenidas (fotos de compras ya confirmadas). Las rutas son
 * relativas al almacenamiento privado de la app, las mismas que persisten las entidades de
 * imagen. Todas las operaciones son de mejor esfuerzo y jamás tocan filas de la base de datos:
 * un archivo ausente o ilegible no es un error, simplemente no hay nada que hacer con él.
 */
interface RetainedImageStore {
    /**
     * Lectura clasificada para UI. Solo [RetainedImageReadResult.Absent] habilita consultar el
     * respaldo remoto; corrupción, ruta inválida o E/S fallida permanecen cerradas localmente.
     */
    suspend fun readForDisplay(relativePath: String): RetainedImageReadResult

    /**
     * Lee el contenido de la imagen descifrándola si está cifrada en reposo. Devuelve null si
     * el archivo no existe, está fuera del almacenamiento privado o no puede descifrarse; la
     * interfaz de usuario muestra entonces el marcador de imagen no disponible.
     */
    suspend fun readDecrypted(relativePath: String): ByteArray?

    /**
     * Variante acotada para procesadores que no pueden cargar una fuente arbitrariamente grande.
     * La implementación productiva valida el tamaño almacenado antes de reservar el ByteArray;
     * el default conserva compatibilidad de fakes y sanea el buffer rechazado.
     */
    suspend fun readDecrypted(relativePath: String, maxBytes: Int): ByteArray? {
        require(maxBytes > 0)
        val bytes = readDecrypted(relativePath) ?: return null
        if (bytes.size <= maxBytes) return bytes
        bytes.fill(0)
        return null
    }

    /**
     * Cifra el archivo en sitio (idempotente: un archivo ya cifrado devuelve true sin tocarlo).
     * Devuelve false si no existe o el cifrado no pudo completarse; el original queda intacto
     * ante cualquier fallo y la migración lo reintenta en la próxima pasada.
     */
    suspend fun encryptInPlace(relativePath: String): Boolean

    /**
     * Clasifica el archivo sin confundir plaintext con un envelope FSE corrupto. `ENCRYPTED`
     * implica que el tag GCM se autenticó, no solo que coincidieron cuatro bytes mágicos.
     */
    suspend fun encryptionState(relativePath: String): RetainedImageEncryptionState

    /**
     * Sondeo de cabecera acotado para migración. No autentica contenido y nunca debe usarse
     * para mostrar/exportar bytes; evita un descifrado O(tamaño) diario de envelopes ya migrados.
     */
    suspend fun encryptionMigrationState(relativePath: String): RetainedImageMigrationState =
        when (encryptionState(relativePath)) {
            RetainedImageEncryptionState.PLAINTEXT -> RetainedImageMigrationState.PLAINTEXT
            RetainedImageEncryptionState.ENCRYPTED -> RetainedImageMigrationState.ENVELOPED
            RetainedImageEncryptionState.CORRUPT -> RetainedImageMigrationState.CORRUPT
            RetainedImageEncryptionState.UNAVAILABLE -> RetainedImageMigrationState.UNAVAILABLE
        }

    suspend fun isEncrypted(relativePath: String): Boolean =
        encryptionState(relativePath) == RetainedImageEncryptionState.ENCRYPTED

    /** Borra el archivo (cifrado o no) y distingue ausencia previa de un fallo no confirmado. */
    suspend fun delete(relativePath: String): PrivateImageDeletionResult
}

sealed interface RetainedImageReadResult {
    data class Available(val bytes: ByteArray) : RetainedImageReadResult
    data object Absent : RetainedImageReadResult
    data object IntegrityRejected : RetainedImageReadResult
    data object Unavailable : RetainedImageReadResult
}

/**
 * Puerto de los barridos de archivos del ciclo de vida de imágenes. Cada operación devuelve
 * cuántos elementos eliminó realmente. Ninguna escribe filas de la base de datos. Los callbacks
 * permiten revalidar Room dentro de la exclusión del subárbol, inmediatamente antes de borrar.
 */
interface RetentionFileSweep {
    /** Borra los temporales `import-*.tmp` con más de una hora respecto a [now]. */
    suspend fun sweepStaleImports(
        now: Instant,
        forceDeletionCutoff: Instant? = null,
    ): PrivateFileSweepReport

    /** Borra archivos regulares de `cacheDir` inactivos por más de una hora. */
    suspend fun sweepStaleCache(
        now: Instant,
        forceDeletionCutoff: Instant? = null,
    ): PrivateFileSweepReport

    /** Borra temporales internos conocidos de OCR/cifrado inactivos por más de una hora. */
    suspend fun sweepStalePrivateTemps(
        now: Instant,
        forceDeletionCutoff: Instant? = null,
    ): PrivateFileSweepReport

    /**
     * Borra subdirectorios canónicos de `draft_images/` con más de una hora solo si
     * [draftExists] confirma, bajo el mismo lock que las escrituras, que el borrador sigue
     * ausente. La gracia y la revalidación eliminan el TOCTOU de un snapshot global.
     */
    suspend fun sweepOrphanDraftImageDirs(
        now: Instant,
        existingDraftIds: Set<DraftId>,
        forceDeletionCutoff: Instant? = null,
        /** Impide borrar un árbol que contiene una hoja compartida por una fila legacy. */
        pathIsReferencedAnywhere: suspend (String) -> Boolean = { false },
        draftExists: suspend (DraftId) -> Boolean,
    ): PrivateFileSweepReport

    /**
     * Borra finales directos que Room ya no referencia. La lectura exacta ocurre dentro del lock
     * del draft; en modo force el cutoff excluye capturas posteriores y los archivos frescos se
     * difieren para no competir con una publicación filesystem→Room todavía en curso.
     */
    suspend fun sweepUnreferencedDraftImages(
        now: Instant,
        existingDraftIds: Set<DraftId>,
        forceDeletionCutoff: Instant? = null,
        /** Defensa ante filas legacy corruptas que apunten al directorio de otro draft. */
        pathIsReferencedAnywhere: suspend (String) -> Boolean,
        referencedPaths: suspend (DraftId) -> Set<String>?,
    ): PrivateFileSweepReport

    /**
     * Enumera los subárboles `ocr/` existentes y los borra solo si [draftIsCommitted] confirma
     * el estado actual dentro del lock del borrador. No materializa todo el historial en memoria.
     */
    suspend fun sweepCommittedOcrVersions(
        committedDraftIds: Set<DraftId>,
        draftIsCommitted: suspend (DraftId) -> Boolean,
    ): PrivateFileSweepReport

    /**
     * Borrado manual de OCR para el snapshot de drafts seleccionado. La decisión se reevalúa
     * dentro del lock: OCR_PROCESSING se difiere y nunca se rompe una lectura ML Kit activa.
     */
    suspend fun sweepAllDraftOcrVersions(
        existingDraftIds: Set<DraftId>,
        decision: suspend (DraftId) -> OcrVersionSweepDecision,
    ): OcrVersionSweepReport
}

enum class OcrVersionSweepDecision {
    DELETE,
    SKIP,
    RETRY_LATER,
}

/**
 * Destino elegido por el usuario para su exportación. La implementación Android solo admite
 * URI `content://` entregadas por Storage Access Framework; el dominio nunca recibe bytes de
 * factura ni una ruta absoluta.
 */
interface UserDataExportWriter {
    /**
     * Solo devuelve WRITTEN si el documento completo se escribió y cerró. Ante un fallo intenta
     * borrar/truncar y distingue si pudo confirmar un destino limpio o si el usuario debe retirarlo.
     */
    suspend fun write(documentUri: String, export: UserDataExport): UserDataExportWriteStatus
}
