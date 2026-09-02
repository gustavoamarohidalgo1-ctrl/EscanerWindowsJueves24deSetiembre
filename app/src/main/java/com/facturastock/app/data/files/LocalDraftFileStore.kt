package com.facturastock.app.data.files

import android.content.Context
import com.facturastock.app.domain.model.PrivateImageDeletionResult
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.repository.DraftFileStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Almacén local de archivos de borradores. Resuelve cada ruta relativa contra
 * [Context.getFilesDir] y solo borra lo que quede estrictamente dentro de ese directorio:
 * la comparación por ruta canónica neutraliza los intentos de path traversal (`../`).
 * El borrado es de mejor esfuerzo — los archivos inexistentes o no eliminables se ignoran —
 * y nunca lanza por un lote parcialmente fallido.
 */
@Singleton
class LocalDraftFileStore @Inject constructor(
    @ApplicationContext context: Context,
    private val mutationCoordinator: PrivateImageMutationCoordinator =
        PrivateImageMutationCoordinator(),
    private val deletionDurability: PrivateDeletionDurability = PrivateDeletionDurability(),
) : DraftFileStore {
    private val rootDirectory: File = context.filesDir

    override suspend fun deleteFiles(
        relativePaths: List<String>,
    ): List<PrivateImageDeletionResult> = relativePaths.map { relativePath ->
        mutationCoordinator.withRelativePathLock(relativePath) {
            if (!PrivateFileResolver.isDraftImageFilePath(relativePath)) {
                return@withRelativePathLock PrivateImageDeletionResult.REJECTED_UNSAFE
            }
            val file = runCatching {
                PrivateFileResolver.resolveForNoFollowDeletion(rootDirectory, relativePath)
            }.getOrNull()
            if (file == null) {
                return@withRelativePathLock PrivateImageDeletionResult.REJECTED_UNSAFE
            }
            val path = file.toPath()
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                return@withRelativePathLock PrivateImageDeletionResult.REJECTED_UNSAFE
            }
            try {
                val deleted = Files.deleteIfExists(path)
                deletionDurability.syncAfterDeletion(file, rootDirectory)
                if (deleted) {
                    PrivateImageDeletionResult.DELETED
                } else {
                    PrivateImageDeletionResult.ALREADY_ABSENT
                }
            } catch (_: Exception) {
                PrivateImageDeletionResult.FAILED
            }
        }
    }

    override suspend fun deleteDraftTree(draftId: DraftId) {
        mutationCoordinator.withDraftLock(draftId) {
            deleteDraftTreeUnlocked(draftId)
        }
    }

    override suspend fun deleteDraftTreeIf(
        draftId: DraftId,
        pathIsReferencedAnywhere: suspend (String) -> Boolean,
        shouldDelete: suspend () -> Boolean,
    ): Boolean = mutationCoordinator.withDraftLock(draftId) {
        if (!shouldDelete()) return@withDraftLock false
        val directory = draftDirectoryForDeletion(draftId) ?: return@withDraftLock false
        val leafPaths = NoFollowFileTree.relativeLeafPaths(rootDirectory, directory)
            ?: return@withDraftLock false
        for (relativePath in leafPaths) {
            if (pathIsReferencedAnywhere(relativePath)) return@withDraftLock false
        }
        deleteDraftTreeUnlocked(draftId)
    }

    override suspend fun deleteOcrVersions(draftId: DraftId) {
        mutationCoordinator.withDraftLock(draftId) {
            PrivateFileResolver.resolveForNoFollowDeletion(
                rootDirectory,
                "${LocalDraftImageImporter.IMAGE_DIRECTORY}/${draftId.value}/" +
                    LocalInvoiceImagePreprocessor.OCR_DIRECTORY,
            )
                ?.let { directory ->
                    runCatching { deletionDurability.deleteTree(directory, rootDirectory) }
                }
        }
    }

    private fun draftDirectoryForDeletion(draftId: DraftId): File? =
        PrivateFileResolver.resolveForNoFollowDeletion(
            rootDirectory,
            "${LocalDraftImageImporter.IMAGE_DIRECTORY}/${draftId.value}",
        )

    /** Debe invocarse únicamente con el lock del borrador ya adquirido. */
    private fun deleteDraftTreeUnlocked(draftId: DraftId): Boolean =
        draftDirectoryForDeletion(draftId)?.let {
            runCatching { deletionDurability.deleteTree(it, rootDirectory) }.getOrDefault(false)
        } ?: false
}
