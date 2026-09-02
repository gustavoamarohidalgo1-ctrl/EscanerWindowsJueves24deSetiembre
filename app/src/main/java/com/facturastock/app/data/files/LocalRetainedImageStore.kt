package com.facturastock.app.data.files

import android.content.Context
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.domain.model.PrivateImageDeletionResult
import com.facturastock.app.domain.model.RetainedImageEncryptionState
import com.facturastock.app.domain.model.RetainedImageMigrationState
import com.facturastock.app.domain.repository.DocumentUploadDecodePolicy
import com.facturastock.app.domain.repository.RetainedImageStore
import com.facturastock.app.domain.repository.RetainedImageReadResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext

/**
 * Almacén local de imágenes retenidas. Resuelve cada ruta relativa con [PrivateFileResolver]
 * (anti-traversal: nada fuera del almacenamiento privado) y aplica el cifrado en reposo de
 * [RetainedImageCipher]. Todas las operaciones son de mejor esfuerzo: devuelven null/false ante
 * archivos ausentes o fallos de E/S y nunca lanzan por un lote parcialmente fallido.
 */
@Singleton
class LocalRetainedImageStore @Inject constructor(
    @ApplicationContext context: Context,
    private val cipher: RetainedImageCipher,
    private val dispatchers: DispatcherProvider,
    private val mutationCoordinator: PrivateImageMutationCoordinator =
        PrivateImageMutationCoordinator(),
    private val deletionDurability: PrivateDeletionDurability = PrivateDeletionDurability(),
) : RetainedImageStore {
    private val rootDirectory: File = context.filesDir

    override suspend fun readForDisplay(relativePath: String): RetainedImageReadResult =
        withContext(dispatchers.io) {
            mutationCoordinator.withRelativePathLock(relativePath) {
                val file = resolve(relativePath)
                    ?: return@withRelativePathLock RetainedImageReadResult.Unavailable
                readForDisplayLocked(file)
            }
        }

    override suspend fun readDecrypted(relativePath: String): ByteArray? =
        when (val result = readForDisplay(relativePath)) {
            is RetainedImageReadResult.Available -> result.bytes
            RetainedImageReadResult.Absent,
            RetainedImageReadResult.IntegrityRejected,
            RetainedImageReadResult.Unavailable,
            -> null
        }

    override suspend fun readDecrypted(relativePath: String, maxBytes: Int): ByteArray? =
        withContext(dispatchers.io) {
            if (maxBytes <= 0) return@withContext null
            mutationCoordinator.withRelativePathLock(relativePath) {
                val file = resolve(relativePath) ?: return@withRelativePathLock null
                if (file.isPresentNonRegularFile()) return@withRelativePathLock null
                when (cipher.migrationState(file)) {
                    RetainedImageCipher.MigrationState.ENVELOPED ->
                        cipher.decrypt(file, maxBytes)
                    RetainedImageCipher.MigrationState.PLAINTEXT -> file.readBounded(maxBytes)
                    RetainedImageCipher.MigrationState.ABSENT -> null
                    RetainedImageCipher.MigrationState.CORRUPT,
                    RetainedImageCipher.MigrationState.UNAVAILABLE,
                    -> null
                }
            }
        }

    override suspend fun encryptInPlace(relativePath: String): Boolean =
        withContext(dispatchers.io) {
            mutationCoordinator.withRelativePathLock(relativePath) {
                val file = resolve(relativePath) ?: return@withRelativePathLock false
                if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    return@withRelativePathLock false
                }
                cipher.encrypt(file)
            }
        }

    override suspend fun encryptionState(
        relativePath: String,
    ): RetainedImageEncryptionState =
        withContext(dispatchers.io) {
            mutationCoordinator.withRelativePathLock(relativePath) {
                val file = resolve(relativePath)
                    ?: return@withRelativePathLock RetainedImageEncryptionState.UNAVAILABLE
                if (file.isPresentNonRegularFile()) {
                    return@withRelativePathLock RetainedImageEncryptionState.UNAVAILABLE
                }
                when (cipher.inspect(file)) {
                    RetainedImageCipher.EnvelopeState.PLAINTEXT ->
                        RetainedImageEncryptionState.PLAINTEXT
                    RetainedImageCipher.EnvelopeState.ENCRYPTED ->
                        RetainedImageEncryptionState.ENCRYPTED
                    RetainedImageCipher.EnvelopeState.CORRUPT ->
                        RetainedImageEncryptionState.CORRUPT
                    RetainedImageCipher.EnvelopeState.UNAVAILABLE ->
                        RetainedImageEncryptionState.UNAVAILABLE
                }
            }
        }

    override suspend fun encryptionMigrationState(
        relativePath: String,
    ): RetainedImageMigrationState =
        withContext(dispatchers.io) {
            mutationCoordinator.withRelativePathLock(relativePath) {
                val file = resolve(relativePath)
                    ?: return@withRelativePathLock RetainedImageMigrationState.CORRUPT
                if (file.isPresentNonRegularFile()) {
                    return@withRelativePathLock RetainedImageMigrationState.CORRUPT
                }
                when (cipher.migrationState(file)) {
                    RetainedImageCipher.MigrationState.PLAINTEXT ->
                        RetainedImageMigrationState.PLAINTEXT
                    RetainedImageCipher.MigrationState.ENVELOPED ->
                        RetainedImageMigrationState.ENVELOPED
                    RetainedImageCipher.MigrationState.ABSENT ->
                        RetainedImageMigrationState.ABSENT
                    RetainedImageCipher.MigrationState.CORRUPT ->
                        RetainedImageMigrationState.CORRUPT
                    RetainedImageCipher.MigrationState.UNAVAILABLE ->
                        RetainedImageMigrationState.UNAVAILABLE
                }
            }
        }

    override suspend fun delete(relativePath: String): PrivateImageDeletionResult =
        withContext(dispatchers.io) {
            mutationCoordinator.withRelativePathLock(relativePath) {
                val file = resolve(relativePath)
                    ?: return@withRelativePathLock PrivateImageDeletionResult.REJECTED_UNSAFE
                val path = file.toPath()
                if (!Files.notExists(path, LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                ) {
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

    private fun resolve(relativePath: String): File? {
        if (!PrivateFileResolver.isDraftImageFilePath(relativePath)) return null
        return runCatching {
            PrivateFileResolver.resolveForNoFollowDeletion(rootDirectory, relativePath)
        }.getOrNull()
    }

    private fun readForDisplayLocked(file: File): RetainedImageReadResult {
        if (Files.notExists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return RetainedImageReadResult.Absent
        }
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return RetainedImageReadResult.Unavailable
        }
        return when (cipher.migrationState(file)) {
            RetainedImageCipher.MigrationState.ENVELOPED ->
                cipher.decrypt(file, MAX_DISPLAY_BYTES)?.let(RetainedImageReadResult::Available)
                    ?: RetainedImageReadResult.IntegrityRejected
            RetainedImageCipher.MigrationState.PLAINTEXT ->
                file.readBounded(MAX_DISPLAY_BYTES)?.let(RetainedImageReadResult::Available)
                    ?: RetainedImageReadResult.Unavailable
            RetainedImageCipher.MigrationState.ABSENT -> RetainedImageReadResult.Absent
            RetainedImageCipher.MigrationState.CORRUPT ->
                RetainedImageReadResult.IntegrityRejected
            RetainedImageCipher.MigrationState.UNAVAILABLE ->
                RetainedImageReadResult.Unavailable
        }
    }

    /** Lee exactamente el tamaño ya validado; nunca reserva según contenido no acotado. */
    private fun File.readBounded(maxBytes: Int): ByteArray? {
        if (!Files.isRegularFile(toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        val expected = runCatching { length() }.getOrNull()
            ?.takeIf { it in 1L..maxBytes.toLong() }
            ?.toInt()
            ?: return null
        val bytes = ByteArray(expected)
        return try {
            inputStream().use { input ->
                var offset = 0
                while (offset < bytes.size) {
                    val read = input.read(bytes, offset, bytes.size - offset)
                    if (read < 0) return@use false
                    if (read > 0) offset += read
                }
                input.read() == -1
            }.let { complete ->
                if (complete) bytes else null.also { bytes.fill(0) }
            }
        } catch (_: Exception) {
            bytes.fill(0)
            null
        }
    }

    private fun File.isPresentNonRegularFile(): Boolean =
        !Files.notExists(toPath(), LinkOption.NOFOLLOW_LINKS) &&
            !Files.isRegularFile(toPath(), LinkOption.NOFOLLOW_LINKS)

    private companion object {
        const val MAX_DISPLAY_BYTES: Int = DocumentUploadDecodePolicy.MAX_SOURCE_BYTES
    }
}
