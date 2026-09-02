package com.facturastock.app.data.files

import android.content.Context
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.model.PrivateFileSweepReport
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Limpieza selectiva de temporales de importación huérfanos. Si el proceso muere a mitad
 * de una importación, su `import-*.tmp` queda en la raíz del almacenamiento privado; aquí
 * se borran únicamente esos temporales con una antigüedad superior a [ORPHAN_TTL_MILLIS].
 *
 * La selectividad es la garantía de seguridad:
 * - Solo se examina la RAÍZ de [Context.getFilesDir]; jamás se entra a subdirectorios ni
 *   se toca el directorio `draft_images`, donde viven las páginas definitivas.
 * - Un temporal de una importación EN CURSO es reciente (segundos), así que el umbral de
 *   antigüedad lo excluye: la limpieza nunca borra páginas de sesiones activas.
 *
 * Es de mejor esfuerzo y nunca lanza: un temporal no eliminable simplemente se reintenta
 * en la próxima pasada. Debe llamarse fuera del hilo principal (los llamadores ya están
 * en el dispatcher de IO).
 */
@Singleton
class StaleImportCleanup @Inject constructor(
    @ApplicationContext context: Context,
    private val appClock: AppClock,
    private val deletionDurability: PrivateDeletionDurability = PrivateDeletionDurability(),
) {
    private val rootDirectory: File = context.filesDir

    /** Borra los temporales huérfanos y devuelve cuántos se eliminaron. */
    fun cleanOrphanedImportTemps(): Int = cleanOrphanedImportTemps(appClock.now())

    /**
     * Igual que [cleanOrphanedImportTemps] pero con el instante de referencia explícito, para
     * que el barrido de mantenimiento comparta un único `now` en todas sus operaciones.
     */
    fun cleanOrphanedImportTemps(now: Instant): Int {
        return sweepOrphanedImportTemps(now).deleted
    }

    /** Reporte cerrado para mantenimiento; force difiere temporales todavía potencialmente vivos. */
    fun sweepOrphanedImportTemps(
        now: Instant,
        forceDeletionCutoff: Instant? = null,
    ): PrivateFileSweepReport {
        val cutoffMillis = now.toEpochMilli() - ORPHAN_TTL_MILLIS
        val forceMillis = forceDeletionCutoff?.toEpochMilli()
        val rootPath = rootDirectory.toPath().toAbsolutePath().normalize()
        if (Files.notExists(rootPath, LinkOption.NOFOLLOW_LINKS)) {
            return PrivateFileSweepReport()
        }
        if (!Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(rootPath)
        ) {
            return PrivateFileSweepReport(attempted = 1, failed = 1, retryableFailed = 0)
        }
        try {
            // Los temporales de importación son hijos directos: esto cierra el retry de un
            // unlink previo que retiró el nombre pero no alcanzó a sincronizar filesDir.
            deletionDurability.syncDirectory(rootDirectory, rootDirectory)
        } catch (_: Exception) {
            return PrivateFileSweepReport(attempted = 1, failed = 1)
        }
        val candidates = rootDirectory.listFiles()
            ?.filter { file ->
                val path = file.toPath()
                !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) &&
                    file.name.startsWith(LocalDraftImageImporter.TEMP_FILE_PREFIX) &&
                    file.name.endsWith(LocalDraftImageImporter.TEMP_FILE_SUFFIX)
            }
            ?: return PrivateFileSweepReport(attempted = 1, failed = 1)
        var attempted = 0
        var deleted = 0
        var absent = 0
        var failed = 0
        candidates.forEach { candidate ->
            val path = candidate.toPath()
            val lastModifiedResult = runCatching {
                Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis()
            }
            val lastModified = lastModifiedResult.getOrNull()
            if (lastModified == null || lastModified <= 0L) {
                // Un candidato conocido no puede desaparecer silenciosamente del reporte:
                // mientras su metadata sea ilegible, una orden force debe permanecer durable.
                attempted++
                if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) absent++ else failed++
                return@forEach
            }
            val isStale = lastModified in 1L until cutoffMillis
            val belongsToForce = forceMillis != null && lastModified in 1L..forceMillis
            if (!isStale && !belongsToForce) return@forEach
            attempted++
            if (!isStale) {
                // Puede seguir siendo la copia que un import activo aún valida/publicará.
                failed++
                return@forEach
            }
            try {
                val wasDeleted = Files.deleteIfExists(path)
                deletionDurability.syncAfterDeletion(candidate, rootDirectory)
                if (wasDeleted) deleted++ else absent++
            } catch (_: Exception) {
                failed++
            }
        }
        return PrivateFileSweepReport(
            attempted = attempted,
            deleted = deleted,
            alreadyAbsent = absent,
            failed = failed,
        )
    }

    private companion object {
        /** Antigüedad a partir de la cual un temporal se considera huérfano: 1 hora. */
        val ORPHAN_TTL_MILLIS: Long = TimeUnit.HOURS.toMillis(1)
    }
}
