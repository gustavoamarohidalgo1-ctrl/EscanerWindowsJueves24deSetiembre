package com.facturastock.app.data.files

import com.facturastock.app.core.platform.AppDirectories
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.domain.model.OcrVersionSweepReport
import com.facturastock.app.domain.model.PrivateFileSweepReport
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.repository.OcrVersionSweepDecision
import com.facturastock.app.domain.repository.RetentionFileSweep
import java.io.File
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * Barridos de archivos del ciclo de vida de imágenes sobre el almacenamiento privado. Cada
 * barrido es de mejor esfuerzo por elemento (un fallo puntual no detiene el resto) y devuelve
 * cuántos elementos eliminó de verdad. La base de datos jamás se toca aquí: los conjuntos de
 * IDs llegan ya resueltos desde la capa de dominio.
 */
@Singleton
class LocalRetentionFileSweep @Inject constructor(
    private val directories: AppDirectories,
    private val staleImportCleanup: StaleImportCleanup,
    private val dispatchers: DispatcherProvider,
    private val mutationCoordinator: PrivateImageMutationCoordinator =
        PrivateImageMutationCoordinator(),
    private val deletionDurability: PrivateDeletionDurability = PrivateDeletionDurability(),
) : RetentionFileSweep {
    private val rootDirectory: File = directories.filesDir
    private val cacheDirectory: File = directories.cacheDir

    override suspend fun sweepStaleImports(
        now: Instant,
        forceDeletionCutoff: Instant?,
    ): PrivateFileSweepReport =
        withContext(dispatchers.io) {
            staleImportCleanup.sweepOrphanedImportTemps(now, forceDeletionCutoff)
        }

    override suspend fun sweepStaleCache(
        now: Instant,
        forceDeletionCutoff: Instant?,
    ): PrivateFileSweepReport =
        withContext(dispatchers.io) {
            val thresholdMillis = now.toEpochMilli() - STALE_AGE_MILLIS
            val forceMillis = forceDeletionCutoff?.toEpochMilli()
            val rootPath = safeTraversalRoot(cacheDirectory)
                ?: return@withContext unsafeRootReport(cacheDirectory)
            var attempted = 0
            var deleted = 0
            var absent = 0
            var failed = 0
            Files.walkFileTree(
                rootPath,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(
                        dir: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        try {
                            deletionDurability.syncDirectory(dir.toFile(), cacheDirectory)
                        } catch (_: Exception) {
                            attempted++
                            failed++
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        val modified = attrs.lastModifiedTime().toMillis()
                        if (
                            modified <= thresholdMillis ||
                            (forceMillis != null && modified <= forceMillis)
                        ) {
                            attempted++
                            try {
                                val wasDeleted = Files.deleteIfExists(file)
                                deletionDurability.syncAfterDeletion(
                                    file.toFile(),
                                    cacheDirectory,
                                )
                                if (wasDeleted) deleted++ else absent++
                            } catch (_: Exception) {
                                failed++
                            }
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(
                        file: Path,
                        exc: java.io.IOException,
                    ): FileVisitResult {
                        attempted++
                        failed++
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(
                        dir: Path,
                        exc: java.io.IOException?,
                    ): FileVisitResult {
                        if (dir != rootPath && exc == null) {
                            runCatching {
                                // Solo retira el contenedor si el recorrido lo dejó vacío.
                                // Un deleteTree aquí borraría también archivos frescos que
                                // visitFile excluyó deliberadamente por su edad.
                                if (Files.deleteIfExists(dir)) {
                                    deletionDurability.syncAfterDeletion(
                                        dir.toFile(),
                                        cacheDirectory,
                                    )
                                }
                            }
                        }
                        return FileVisitResult.CONTINUE
                    }
                },
            )
            PrivateFileSweepReport(attempted, deleted, absent, failed)
        }

    override suspend fun sweepStalePrivateTemps(
        now: Instant,
        forceDeletionCutoff: Instant?,
    ): PrivateFileSweepReport =
        withContext(dispatchers.io) {
            val thresholdMillis = now.toEpochMilli() - STALE_AGE_MILLIS
            val forceMillis = forceDeletionCutoff?.toEpochMilli()
            val rootPath = safeTraversalRoot(rootDirectory)
                ?: return@withContext unsafeRootReport(rootDirectory)
            var attempted = 0
            var deleted = 0
            var absent = 0
            var failed = 0
            val candidates = mutableListOf<Path>()
            try {
                Files.walkFileTree(
                    rootPath,
                    object : SimpleFileVisitor<Path>() {
                        override fun preVisitDirectory(
                            dir: Path,
                            attrs: BasicFileAttributes,
                        ): FileVisitResult {
                            try {
                                deletionDurability.syncDirectory(dir.toFile(), rootDirectory)
                            } catch (_: Exception) {
                                attempted++
                                failed++
                            }
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFile(
                            file: Path,
                            attrs: BasicFileAttributes,
                        ): FileVisitResult {
                            if (file.toFile().isKnownPrivateTemp()) candidates.add(file)
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFileFailed(
                            file: Path,
                            exc: java.io.IOException,
                        ): FileVisitResult {
                            attempted++
                            failed++
                            return FileVisitResult.CONTINUE
                        }
                    },
                )
            } catch (_: Exception) {
                attempted++
                failed++
            }
            for (path in candidates) {
                val normalizedPath = path.toAbsolutePath().normalize()
                if (!normalizedPath.startsWith(rootPath)) {
                    attempted++
                    failed++
                    continue
                }
                val relativePath = rootPath.relativize(normalizedPath).toString()
                    .replace(File.separatorChar, '/')
                mutationCoordinator.withRelativePathLock(relativePath) {
                    if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
                        attempted++
                        absent++
                        return@withRelativePathLock
                    }
                    val modified = runCatching {
                        Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis()
                    }.getOrNull()
                    if (modified == null || modified <= 0L) {
                        attempted++
                        failed++
                        return@withRelativePathLock
                    }
                    val isStale = modified in 1L..thresholdMillis
                    val belongsToForce = forceMillis != null && modified in 1L..forceMillis
                    if ((!isStale && !belongsToForce) || !path.toFile().isKnownPrivateTemp()) {
                        return@withRelativePathLock
                    }
                    attempted++
                    if (!isStale) {
                        // Un temporal fresco puede pertenecer a cifrado/OCR todavía activo.
                        failed++
                    } else {
                        try {
                            val wasDeleted = Files.deleteIfExists(path)
                            deletionDurability.syncAfterDeletion(path.toFile(), rootDirectory)
                            if (wasDeleted) deleted++ else absent++
                        } catch (_: Exception) {
                            failed++
                        }
                    }
                }
            }
            PrivateFileSweepReport(attempted, deleted, absent, failed)
        }

    override suspend fun sweepOrphanDraftImageDirs(
        now: Instant,
        existingDraftIds: Set<DraftId>,
        forceDeletionCutoff: Instant?,
        pathIsReferencedAnywhere: suspend (String) -> Boolean,
        draftExists: suspend (DraftId) -> Boolean,
    ): PrivateFileSweepReport =
        withContext(dispatchers.io) {
            val draftsRoot = when (val root = draftsRootState()) {
                DraftsRootState.Absent -> return@withContext PrivateFileSweepReport()
                DraftsRootState.Unsafe -> return@withContext unsafeDraftsRootReport()
                is DraftsRootState.Ready -> root.directory
            }
            val candidates = draftsRoot.listFiles()
                ?: return@withContext unavailableDraftsRootReport()
            val staleThreshold = now.toEpochMilli() - STALE_AGE_MILLIS
            val forceMillis = forceDeletionCutoff?.toEpochMilli()
            var attempted = 0
            var deleted = 0
            var absent = 0
            var failed = 0
            for (candidate in candidates) {
                val candidatePath = candidate.toPath()
                if (!Files.isDirectory(candidatePath, LinkOption.NOFOLLOW_LINKS)) continue
                if (!candidate.isSafeDirectDirectoryOf(draftsRoot)) {
                    attempted++
                    failed++
                    continue
                }
                val initialModified = runCatching {
                    Files.getLastModifiedTime(candidatePath, LinkOption.NOFOLLOW_LINKS).toMillis()
                }.getOrNull()
                val initiallyEligible = initialModified != null && initialModified > 0L &&
                    (initialModified <= staleThreshold ||
                        forceMillis != null && initialModified <= forceMillis)
                // Una edad positiva y reciente excluye el directorio. Una edad desconocida debe
                // llegar a la revalidación: si además no hay draft, es trabajo retryable.
                if (initialModified != null && initialModified > 0L && !initiallyEligible) continue
                val draftId = DraftId.parse(candidate.name)
                if (draftId == null) {
                    mutationCoordinator.withRelativePathLock(
                        "${LocalDraftImageImporter.IMAGE_DIRECTORY}/${candidate.name}",
                    ) {
                        if (Files.notExists(candidatePath, LinkOption.NOFOLLOW_LINKS)) return@withRelativePathLock
                        if (!candidate.isSafeDirectDirectoryOf(draftsRoot)) {
                            attempted++
                            failed++
                            return@withRelativePathLock
                        }
                        val modified = runCatching {
                            Files.getLastModifiedTime(
                                candidatePath,
                                LinkOption.NOFOLLOW_LINKS,
                            ).toMillis()
                        }.getOrNull()
                        if (modified == null || modified <= 0L) {
                            attempted++
                            failed++
                            return@withRelativePathLock
                        }
                        if (modified > staleThreshold &&
                            (forceMillis == null || modified > forceMillis)
                        ) {
                            return@withRelativePathLock
                        }
                        when (
                            directoryReferenceState(candidate, pathIsReferencedAnywhere)
                        ) {
                            DirectoryReferenceState.REFERENCED -> return@withRelativePathLock
                            DirectoryReferenceState.UNAVAILABLE -> {
                                attempted++
                                failed++
                                return@withRelativePathLock
                            }
                            DirectoryReferenceState.CLEAR -> Unit
                        }
                        attempted++
                        when (deleteDirectoryResult(candidate)) {
                            DeleteTreeResult.DELETED -> deleted++
                            DeleteTreeResult.ABSENT -> absent++
                            DeleteTreeResult.FAILED -> failed++
                        }
                    }
                    continue
                }
                // El snapshot solo sirve como pista; todos los IDs se revalidan bajo el lock.
                // Saltar los que estaban presentes perdería un borrado concurrente del draft.
                mutationCoordinator.withDraftLock(draftId) {
                    val exists = try {
                        draftExists(draftId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        attempted++
                        failed++
                        return@withDraftLock
                    }
                    if (exists) return@withDraftLock
                    if (Files.notExists(candidatePath, LinkOption.NOFOLLOW_LINKS)) return@withDraftLock
                    if (!candidate.isSafeDirectDirectoryOf(draftsRoot)) {
                        attempted++
                        failed++
                        return@withDraftLock
                    }
                    val modified = runCatching {
                        Files.getLastModifiedTime(
                            candidatePath,
                            LinkOption.NOFOLLOW_LINKS,
                        ).toMillis()
                    }.getOrNull()
                    if (modified == null || modified <= 0L) {
                        attempted++
                        failed++
                        return@withDraftLock
                    }
                    if (modified > staleThreshold &&
                        (forceMillis == null || modified > forceMillis)
                    ) return@withDraftLock
                    when (directoryReferenceState(candidate, pathIsReferencedAnywhere)) {
                        DirectoryReferenceState.REFERENCED -> return@withDraftLock
                        DirectoryReferenceState.UNAVAILABLE -> {
                            attempted++
                            failed++
                            return@withDraftLock
                        }
                        DirectoryReferenceState.CLEAR -> Unit
                    }
                    attempted++
                    when (deleteDirectoryResult(candidate)) {
                        DeleteTreeResult.DELETED -> deleted++
                        DeleteTreeResult.ABSENT -> absent++
                        DeleteTreeResult.FAILED -> failed++
                    }
                }
            }
            PrivateFileSweepReport(attempted, deleted, absent, failed)
        }

    private suspend fun directoryReferenceState(
        directory: File,
        pathIsReferencedAnywhere: suspend (String) -> Boolean,
    ): DirectoryReferenceState {
        val relativeLeaves = NoFollowFileTree.relativeLeafPaths(rootDirectory, directory)
            ?: return DirectoryReferenceState.UNAVAILABLE
        for (relativePath in relativeLeaves) {
            val referenced = try {
                pathIsReferencedAnywhere(relativePath)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return DirectoryReferenceState.UNAVAILABLE
            }
            if (referenced) return DirectoryReferenceState.REFERENCED
        }
        return DirectoryReferenceState.CLEAR
    }

    override suspend fun sweepCommittedOcrVersions(
        committedDraftIds: Set<DraftId>,
        draftIsCommitted: suspend (DraftId) -> Boolean,
    ): PrivateFileSweepReport =
        withContext(dispatchers.io) {
            val draftsRoot = when (val root = draftsRootState()) {
                DraftsRootState.Absent -> return@withContext PrivateFileSweepReport()
                DraftsRootState.Unsafe -> return@withContext unsafeDraftsRootReport()
                is DraftsRootState.Ready -> root.directory
            }
            val draftDirectories = draftsRoot.listFiles()
                ?: return@withContext unavailableDraftsRootReport()
            var attempted = 0
            var deleted = 0
            var absent = 0
            var failed = 0
            for (draftDirectory in draftDirectories) {
                if (!draftDirectory.isSafeDirectDirectoryOf(draftsRoot)) continue
                val draftId = DraftId.parse(draftDirectory.name) ?: continue
                if (draftId !in committedDraftIds) continue
                mutationCoordinator.withDraftLock(draftId) {
                    val committed = try {
                        draftIsCommitted(draftId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        attempted++
                        failed++
                        return@withDraftLock
                    }
                    if (!committed) return@withDraftLock
                    attempted++
                    val ocrDirectory = File(
                        draftDirectory,
                        LocalInvoiceImagePreprocessor.OCR_DIRECTORY,
                    )
                    when (deleteDirectoryResult(ocrDirectory)) {
                        DeleteTreeResult.DELETED -> deleted++
                        DeleteTreeResult.ABSENT -> absent++
                        DeleteTreeResult.FAILED -> failed++
                    }
                }
            }
            PrivateFileSweepReport(attempted, deleted, absent, failed)
        }

    override suspend fun sweepUnreferencedDraftImages(
        now: Instant,
        existingDraftIds: Set<DraftId>,
        forceDeletionCutoff: Instant?,
        pathIsReferencedAnywhere: suspend (String) -> Boolean,
        referencedPaths: suspend (DraftId) -> Set<String>?,
    ): PrivateFileSweepReport = withContext(dispatchers.io) {
        val draftsRoot = when (val root = draftsRootState()) {
            DraftsRootState.Absent -> return@withContext PrivateFileSweepReport()
            DraftsRootState.Unsafe -> return@withContext unsafeDraftsRootReport()
            is DraftsRootState.Ready -> root.directory
        }
        val draftDirectories = draftsRoot.listFiles()
            ?: return@withContext unavailableDraftsRootReport()
        val staleThreshold = now.toEpochMilli() - STALE_AGE_MILLIS
        val forceMillis = forceDeletionCutoff?.toEpochMilli()
        var attempted = 0
        var deleted = 0
        var absent = 0
        var failed = 0
        for (draftDirectory in draftDirectories) {
            val draftId = DraftId.parse(draftDirectory.name) ?: continue
            if (!draftDirectory.isSafeDirectDirectoryOf(draftsRoot)) {
                continue
            }
            mutationCoordinator.withDraftLock(draftId) {
                val referenced = try {
                    referencedPaths(draftId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    attempted++
                    failed++
                    return@withDraftLock
                } ?: return@withDraftLock
                val entries = draftDirectory.listFiles()
                if (entries == null) {
                    attempted++
                    failed++
                    return@withDraftLock
                }
                for (entry in entries) {
                    val path = entry.toPath()
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue
                    val relativePath =
                        "${LocalDraftImageImporter.IMAGE_DIRECTORY}/${draftId.value}/${entry.name}"
                    if (!PrivateFileResolver.isDraftImageFilePath(relativePath) ||
                        relativePath in referenced
                    ) {
                        continue
                    }
                    if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) continue
                    val modified = runCatching {
                        Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis()
                    }.getOrNull()
                    if (modified == null || modified <= 0L) {
                        attempted++
                        failed++
                        continue
                    }
                    val isStale = modified in 1L..staleThreshold
                    val belongsToForce = forceMillis != null && modified in 1L..forceMillis
                    if (!isStale && !belongsToForce) continue
                    val referencedAnywhere = try {
                        pathIsReferencedAnywhere(relativePath)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        attempted++
                        failed++
                        continue
                    }
                    if (referencedAnywhere) continue
                    attempted++
                    if (!isStale) {
                        failed++
                        continue
                    }
                    try {
                        val wasDeleted = Files.deleteIfExists(path)
                        deletionDurability.syncAfterDeletion(path.toFile(), rootDirectory)
                        if (wasDeleted) deleted++ else absent++
                    } catch (_: Exception) {
                        failed++
                    }
                }
            }
        }
        PrivateFileSweepReport(attempted, deleted, absent, failed)
    }

    override suspend fun sweepAllDraftOcrVersions(
        existingDraftIds: Set<DraftId>,
        decision: suspend (DraftId) -> OcrVersionSweepDecision,
    ): OcrVersionSweepReport = withContext(dispatchers.io) {
        val draftsRoot = when (val root = draftsRootState()) {
            DraftsRootState.Absent -> return@withContext OcrVersionSweepReport()
            DraftsRootState.Unsafe -> return@withContext unsafeDraftsRootOcrReport()
            is DraftsRootState.Ready -> root.directory
        }
        val draftDirectories = draftsRoot.listFiles()
            ?: return@withContext unavailableDraftsRootOcrReport()
        var attempted = 0
        var deleted = 0
        var alreadyAbsent = 0
        var failed = 0
        var retryableFailed = 0
        for (draftDirectory in draftDirectories) {
            val draftId = DraftId.parse(draftDirectory.name) ?: continue
            mutationCoordinator.withDraftLock(draftId) {
                val currentDecision = try {
                    decision(draftId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    attempted++
                    failed++
                    retryableFailed++
                    return@withDraftLock
                }
                when (currentDecision) {
                    OcrVersionSweepDecision.SKIP -> return@withDraftLock
                    OcrVersionSweepDecision.RETRY_LATER -> {
                        attempted++
                        failed++
                        retryableFailed++
                        return@withDraftLock
                    }
                    OcrVersionSweepDecision.DELETE -> attempted++
                }

                if (!draftDirectory.isSafeDirectDirectoryOf(draftsRoot)) {
                    failed++
                    return@withDraftLock
                }
                val ocrDirectory = File(
                    draftDirectory,
                    LocalInvoiceImagePreprocessor.OCR_DIRECTORY,
                )
                val ocrPath = ocrDirectory.toPath()
                when (deleteDirectoryResult(ocrDirectory)) {
                    DeleteTreeResult.DELETED -> deleted++
                    DeleteTreeResult.ABSENT -> alreadyAbsent++
                    DeleteTreeResult.FAILED -> {
                        failed++
                        retryableFailed++
                    }
                }
            }
        }
        OcrVersionSweepReport(
            attempted = attempted,
            deleted = deleted,
            alreadyAbsent = alreadyAbsent,
            failed = failed,
            retryableFailed = retryableFailed,
        )
    }

    private fun deleteDirectoryResult(directory: File): DeleteTreeResult {
        val path = directory.toPath()
        val existed = !Files.notExists(path, LinkOption.NOFOLLOW_LINKS)
        return try {
            if (!deletionDurability.deleteTree(directory, rootDirectory) ||
                !Files.notExists(path, LinkOption.NOFOLLOW_LINKS)
            ) {
                DeleteTreeResult.FAILED
            } else if (existed) {
                DeleteTreeResult.DELETED
            } else {
                DeleteTreeResult.ABSENT
            }
        } catch (_: Exception) {
            DeleteTreeResult.FAILED
        }
    }

    private enum class DeleteTreeResult { DELETED, ABSENT, FAILED }
    private enum class DirectoryReferenceState { CLEAR, REFERENCED, UNAVAILABLE }

    private fun File.isSafeDirectDirectoryOf(parent: File): Boolean = runCatching {
        isDirectory &&
            !Files.isSymbolicLink(toPath()) &&
            canonicalFile.parentFile == parent.canonicalFile
    }.getOrDefault(false)

    private fun draftsRootState(): DraftsRootState {
        val storagePath = rootDirectory.toPath().toAbsolutePath().normalize()
        if (Files.notExists(storagePath, LinkOption.NOFOLLOW_LINKS)) {
            return DraftsRootState.Absent
        }
        if (safeTraversalRoot(rootDirectory) == null) return DraftsRootState.Unsafe
        val root = PrivateFileResolver.resolveLexicallyInside(
            rootDirectory,
            LocalDraftImageImporter.IMAGE_DIRECTORY,
        ) ?: return DraftsRootState.Unsafe
        val path = root.toPath()
        return when {
            Files.notExists(path, LinkOption.NOFOLLOW_LINKS) -> DraftsRootState.Absent
            Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(path) -> DraftsRootState.Ready(root)
            else -> DraftsRootState.Unsafe
        }
    }

    private fun unsafeDraftsRootReport(): PrivateFileSweepReport =
        PrivateFileSweepReport(attempted = 1, failed = 1, retryableFailed = 0)

    private fun unavailableDraftsRootReport(): PrivateFileSweepReport =
        PrivateFileSweepReport(attempted = 1, failed = 1, retryableFailed = 1)

    private fun unsafeDraftsRootOcrReport(): OcrVersionSweepReport =
        OcrVersionSweepReport(attempted = 1, failed = 1, retryableFailed = 0)

    private fun unavailableDraftsRootOcrReport(): OcrVersionSweepReport =
        OcrVersionSweepReport(attempted = 1, failed = 1, retryableFailed = 1)

    private sealed interface DraftsRootState {
        data object Absent : DraftsRootState
        data object Unsafe : DraftsRootState
        data class Ready(val directory: File) : DraftsRootState
    }

    /**
     * Conserva la identidad léxica de la raíz. Resolverla con `canonicalFile` antes de caminarla
     * seguiría un enlace colocado exactamente en la raíz y convertiría el target en ámbito de
     * borrado. `walkFileTree` ya opera sin FOLLOW_LINKS; aquí cerramos el caso del nodo inicial.
     */
    private fun safeTraversalRoot(directory: File): Path? {
        val path = directory.toPath().toAbsolutePath().normalize()
        return path.takeIf {
            Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)
        }
    }

    private fun unsafeRootReport(directory: File): PrivateFileSweepReport {
        val path = directory.toPath().toAbsolutePath().normalize()
        return if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
            PrivateFileSweepReport()
        } else {
            PrivateFileSweepReport(attempted = 1, failed = 1, retryableFailed = 0)
        }
    }

    private companion object {
        const val STALE_AGE_MILLIS: Long = 3_600_000L
        val PRIVATE_TEMP_PREFIXES = listOf(
            "encrypt-",
            "ocr-page-",
            "ocr-manifest-",
            "import-metadata-stripped-",
        )
    }

    private fun File.isKnownPrivateTemp(): Boolean =
        name.endsWith(".tmp") && PRIVATE_TEMP_PREFIXES.any(name::startsWith)
}
