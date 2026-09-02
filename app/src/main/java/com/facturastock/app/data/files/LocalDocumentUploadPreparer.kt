package com.facturastock.app.data.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.core.graphics.scale
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.domain.model.PrivateImageDeletionResult
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.repository.DocumentUploadDecodePolicy
import com.facturastock.app.domain.repository.DocumentUploadArtifactSweepReport
import com.facturastock.app.domain.repository.DocumentUploadPreparer
import com.facturastock.app.domain.repository.DocumentUploadSource
import com.facturastock.app.domain.repository.PreparedDocumentUpload
import com.facturastock.app.domain.repository.guardDocumentUploadPreparation
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Prepara y conserva cifrado el JPEG exacto que el callable recibirá. */
@Singleton
class LocalDocumentUploadPreparer @Inject constructor(
    @ApplicationContext context: Context,
    private val retainedImages: com.facturastock.app.domain.repository.RetainedImageStore,
    private val cipher: RetainedImageCipher,
    private val dispatchers: DispatcherProvider,
    private val deletionDurability: PrivateDeletionDurability = PrivateDeletionDurability(),
    private val directoryPublicationDurability: PrivatePublicationDurability =
        PrivatePublicationDurability(),
) : DocumentUploadPreparer {
    private val rootDirectory = context.filesDir
    private val directory = File(context.filesDir, DIRECTORY)
    private val mutex = Mutex()
    // Protegido por [mutex]. Una instancia recién creada vuelve a confirmar el ancestro: esto
    // cierra tanto un fallo de fsync en caliente como el reinicio del proceso anterior al reboot.
    private var directoryPublicationConfirmed = false

    override suspend fun prepare(
        source: DocumentUploadSource,
    ): PreparedDocumentUpload? = guardDocumentUploadPreparation {
        withContext(dispatchers.io) {
            mutex.withLock {
                if (!UUID.matches(source.imageId) || !SHA256.matches(source.sourceSha256) ||
                    source.mimeType !in SUPPORTED_SOURCE_MIME_TYPES ||
                    source.rotationDegrees !in ROTATIONS ||
                    source.relativeFilePath.isBlank() || source.relativeFilePath.startsWith('/') ||
                    source.relativeFilePath.contains("..")
                ) {
                    return@withLock null
                }
                if (!ensureSafeDirectory()) return@withLock null
                val destination = File(directory, "${source.imageId}.fse")
                if (destination.isPresentNonRegularFile()) return@withLock null
                when (val stored = readDurablePrepared(destination)) {
                    is DurablePreparedRead.Available -> return@withLock stored.prepared
                    DurablePreparedRead.DurabilityFailed -> return@withLock null
                    DurablePreparedRead.Unavailable -> Unit
                }

                val original = retainedImages.readDecrypted(
                    relativePath = source.relativeFilePath,
                    maxBytes = DocumentUploadDecodePolicy.MAX_SOURCE_BYTES,
                )
                    ?: return@withLock null
                val jpeg = try {
                    if (original.size !in 1..DocumentUploadDecodePolicy.MAX_SOURCE_BYTES) {
                        return@withLock null
                    }
                    if (sha256(original) != source.sourceSha256) return@withLock null
                    transcode(
                        source = original,
                        declaredMimeType = source.mimeType,
                        rotationDegrees = source.rotationDegrees,
                        cancellationJob = kotlinx.coroutines.currentCoroutineContext()[Job],
                    )
                        ?: return@withLock null
                } finally {
                    original.fill(0)
                }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                try {
                    if (!cipher.encryptBytesToFile(jpeg.content, destination)) {
                        return@withLock null
                    }
                    // La lectura siguiente autentica el mismo envelope y valida JPEG/dimensiones;
                    // un `isEncrypted` previo solo repetía el descifrado completo.
                    readPrepared(destination)?.takeIf { it.sha256 == jpeg.sha256 }
                } finally {
                    jpeg.content.fill(0)
                }
            }
        }
    }

    override suspend fun discard(imageId: String): PrivateImageDeletionResult =
        withContext(dispatchers.io) {
            if (!UUID.matches(imageId)) {
                return@withContext PrivateImageDeletionResult.REJECTED_UNSAFE
            }
            mutex.withLock {
                when (directoryState()) {
                    ArtifactDirectoryState.ABSENT ->
                        deleteArtifact(File(directory, "$imageId.fse"))
                    ArtifactDirectoryState.UNSAFE -> PrivateImageDeletionResult.REJECTED_UNSAFE
                    ArtifactDirectoryState.READY ->
                        deleteArtifact(File(directory, "$imageId.fse"))
                }
            }
        }

    override suspend fun sweepOrphans(
        retainedImageIds: Set<ImageId>,
    ): DocumentUploadArtifactSweepReport = withContext(dispatchers.io) {
        mutex.withLock {
            when (directoryState()) {
                ArtifactDirectoryState.ABSENT ->
                    return@withLock DocumentUploadArtifactSweepReport()
                ArtifactDirectoryState.UNSAFE ->
                    throw IOException("Directorio documental no disponible")
                ArtifactDirectoryState.READY -> Unit
            }
            // Confirma también un unlink de una pasada anterior cuyo fsync pudo fallar.
            deletionDurability.syncDirectory(directory, rootDirectory)
            val retained = retainedImageIds.mapTo(mutableSetOf()) { it.value }
            val orphans = directory.listFiles()
                ?.filter { file ->
                    file.name.endsWith(ARTIFACT_SUFFIX) &&
                        file.name.removeSuffix(ARTIFACT_SUFFIX) !in retained
                }
                ?: throw IOException("No se pudo enumerar el directorio documental")
            var deleted = 0
            var absent = 0
            var failed = 0
            orphans.forEach { file ->
                when (deleteArtifact(file)) {
                    PrivateImageDeletionResult.DELETED -> deleted++
                    PrivateImageDeletionResult.ALREADY_ABSENT -> absent++
                    PrivateImageDeletionResult.REJECTED_UNSAFE,
                    PrivateImageDeletionResult.FAILED -> failed++
                }
            }
            DocumentUploadArtifactSweepReport(
                attempted = orphans.size,
                deleted = deleted,
                alreadyAbsent = absent,
                failed = failed,
            )
        }
    }

    private fun deleteArtifact(file: File): PrivateImageDeletionResult {
        val path = file.toPath()
        if (!Files.notExists(path, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
        ) {
            return PrivateImageDeletionResult.REJECTED_UNSAFE
        }
        return try {
            val deleted = Files.deleteIfExists(path)
            deletionDurability.syncAfterDeletion(file, rootDirectory)
            if (deleted && Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
                PrivateImageDeletionResult.DELETED
            } else if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) {
                PrivateImageDeletionResult.ALREADY_ABSENT
            } else {
                PrivateImageDeletionResult.FAILED
            }
        } catch (_: Exception) {
            PrivateImageDeletionResult.FAILED
        }
    }

    private fun readPrepared(file: File): PreparedDocumentUpload? {
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        val maximumStoredBytes = PreparedDocumentUpload.MAX_UPLOAD_BYTES.toLong() +
            RetainedImageCipher.ENVELOPE_OVERHEAD_BYTES
        if (file.length() !in 1L..maximumStoredBytes) {
            return null
        }
        val bytes = cipher.decrypt(file, PreparedDocumentUpload.MAX_UPLOAD_BYTES) ?: return null
        if (bytes.size !in 1..PreparedDocumentUpload.MAX_UPLOAD_BYTES) {
            bytes.fill(0)
            return null
        }
        val dimensions = jpegDimensions(bytes) ?: run {
            bytes.fill(0)
            return null
        }
        return runCatching {
            PreparedDocumentUpload(
                content = bytes,
                sha256 = sha256(bytes),
                widthPx = dimensions.first,
                heightPx = dimensions.second,
            )
        }.getOrElse {
            bytes.fill(0)
            null
        }
    }

    private fun readDurablePrepared(file: File): DurablePreparedRead {
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return DurablePreparedRead.Unavailable
        }
        val maximumStoredBytes = PreparedDocumentUpload.MAX_UPLOAD_BYTES.toLong() +
            RetainedImageCipher.ENVELOPE_OVERHEAD_BYTES
        if (file.length() !in 1L..maximumStoredBytes) return DurablePreparedRead.Unavailable
        return when (
            val decrypted = cipher.decryptAndSync(
                file,
                PreparedDocumentUpload.MAX_UPLOAD_BYTES,
            )
        ) {
            RetainedImageCipher.DurableDecryption.AuthenticationFailed ->
                DurablePreparedRead.Unavailable
            RetainedImageCipher.DurableDecryption.DurabilityFailed ->
                DurablePreparedRead.DurabilityFailed
            is RetainedImageCipher.DurableDecryption.Success -> {
                val bytes = decrypted.plaintext
                val dimensions = jpegDimensions(bytes)
                if (dimensions == null) {
                    bytes.fill(0)
                    DurablePreparedRead.Unavailable
                } else {
                    runCatching {
                        PreparedDocumentUpload(
                            content = bytes,
                            sha256 = sha256(bytes),
                            widthPx = dimensions.first,
                            heightPx = dimensions.second,
                        )
                    }.fold(
                        onSuccess = { DurablePreparedRead.Available(it) },
                        onFailure = {
                            bytes.fill(0)
                            DurablePreparedRead.Unavailable
                        },
                    )
                }
            }
        }
    }

    private fun ensureSafeDirectory(): Boolean {
        when (directoryState()) {
            ArtifactDirectoryState.UNSAFE -> return false
            ArtifactDirectoryState.ABSENT -> try {
                Files.createDirectory(directory.toPath())
            } catch (_: Exception) {
                return false
            }
            ArtifactDirectoryState.READY -> Unit
        }
        if (directoryState() != ArtifactDirectoryState.READY) return false
        if (!directoryPublicationConfirmed) {
            try {
                // El cipher sincroniza `document_uploads` tras el rename; esta barrera distinta
                // hace durable la propia entrada `filesDir/document_uploads`.
                directoryPublicationDurability.syncDirectoryAfterMutation(rootDirectory)
                directoryPublicationConfirmed = true
            } catch (_: Exception) {
                return false
            }
        }
        return true
    }

    private fun directoryState(): ArtifactDirectoryState {
        val rootPath = rootDirectory.toPath().toAbsolutePath().normalize()
        if (!Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(rootPath)
        ) {
            return ArtifactDirectoryState.UNSAFE
        }
        val path = directory.toPath().toAbsolutePath().normalize()
        if (path.parent != rootPath) return ArtifactDirectoryState.UNSAFE
        return when {
            Files.notExists(path, LinkOption.NOFOLLOW_LINKS) -> ArtifactDirectoryState.ABSENT
            Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(path) -> ArtifactDirectoryState.READY
            else -> ArtifactDirectoryState.UNSAFE
        }
    }

    private fun File.isPresentNonRegularFile(): Boolean {
        val path = toPath()
        return !Files.notExists(path, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
    }

    private enum class ArtifactDirectoryState { ABSENT, READY, UNSAFE }

    private fun transcode(
        source: ByteArray,
        declaredMimeType: String,
        rotationDegrees: Int,
        cancellationJob: Job?,
    ): PreparedDocumentUpload? {
        cancellationJob?.ensureActive()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        val actualMime = bounds.outMimeType?.lowercase()
        if (!DocumentUploadDecodePolicy.acceptsSourceDimensions(
                bounds.outWidth,
                bounds.outHeight,
            ) ||
            actualMime != declaredMimeType
        ) {
            return null
        }
        val targetScale = targetScale(bounds.outWidth, bounds.outHeight, rotationDegrees)
        val sampleSize = max(
            sampleSizeFor(targetScale),
            DocumentUploadDecodePolicy.sampleSize(bounds.outWidth, bounds.outHeight),
        )
        val decoded = BitmapFactory.decodeByteArray(
            source,
            0,
            source.size,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: return null
        var working = decoded
        try {
            cancellationJob?.ensureActive()
            val sampledScale = min(1.0, targetScale * sampleSize)
            if (sampledScale < 1.0) {
                val scaled = working.scale(
                    (working.width * sampledScale).toInt().coerceAtLeast(1),
                    (working.height * sampledScale).toInt().coerceAtLeast(1),
                    filter = true,
                )
                if (scaled !== working) {
                    working.recycle()
                    working = scaled
                }
            }
            if (rotationDegrees != 0) {
                cancellationJob?.ensureActive()
                val rotated = Bitmap.createBitmap(
                    working,
                    0,
                    0,
                    working.width,
                    working.height,
                    Matrix().apply { postRotate(rotationDegrees.toFloat()) },
                    true,
                )
                if (rotated !== working) {
                    working.recycle()
                    working = rotated
                }
            }
            repeat(MAX_RESIZE_PASSES) {
                var quality = INITIAL_QUALITY
                while (quality >= MIN_QUALITY) {
                    cancellationJob?.ensureActive()
                    val bytes = CappedCancellationByteArrayOutputStream(
                        maximumBytes = PreparedDocumentUpload.MAX_UPLOAD_BYTES,
                        cancellationJob = cancellationJob,
                    ).use { output ->
                        if (!working.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                            // Algunos codecs traducen una excepción del OutputStream a `false`.
                            // Recomprueba el Job para no degradar cancelación a un resultado nulo.
                            cancellationJob?.ensureActive()
                            return null
                        }
                        output.toByteArrayOrNull()
                    }
                    cancellationJob?.ensureActive()
                    if (bytes != null) {
                        return PreparedDocumentUpload(
                            content = bytes,
                            sha256 = sha256(bytes),
                            widthPx = working.width,
                            heightPx = working.height,
                        )
                    }
                    quality -= QUALITY_STEP
                }
                if (working.width <= MIN_DOWNSCALE_DIMENSION &&
                    working.height <= MIN_DOWNSCALE_DIMENSION
                ) {
                    return null
                }
                val scaled = working.scale(
                    (working.width * RESIZE_FACTOR).toInt().coerceAtLeast(1),
                    (working.height * RESIZE_FACTOR).toInt().coerceAtLeast(1),
                    filter = true,
                )
                if (scaled === working) return null
                working.recycle()
                working = scaled
            }
            return null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            return null
        } finally {
            if (!working.isRecycled) working.recycle()
        }
    }

    private fun targetScale(width: Int, height: Int, rotationDegrees: Int): Double {
        val rotatedWidth = if (rotationDegrees == 90 || rotationDegrees == 270) height else width
        val rotatedHeight = if (rotationDegrees == 90 || rotationDegrees == 270) width else height
        val dimensionScale = min(
            PreparedDocumentUpload.MAX_DIMENSION.toDouble() / rotatedWidth,
            PreparedDocumentUpload.MAX_DIMENSION.toDouble() / rotatedHeight,
        )
        val pixelScale = sqrt(
            PreparedDocumentUpload.MAX_PIXELS.toDouble() /
                (rotatedWidth.toLong() * rotatedHeight.toLong()).toDouble(),
        )
        return min(1.0, min(dimensionScale, pixelScale))
    }

    private fun sampleSizeFor(scale: Double): Int {
        var sample = 1
        while (sample * 2 <= MAX_SAMPLE_SIZE && 1.0 / (sample * 2) >= scale) sample *= 2
        return sample
    }

    private fun jpegDimensions(bytes: ByteArray): Pair<Int, Int>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return if (bounds.outMimeType?.lowercase() == JPEG_MIME_TYPE &&
            bounds.outWidth in 1..PreparedDocumentUpload.MAX_DIMENSION &&
            bounds.outHeight in 1..PreparedDocumentUpload.MAX_DIMENSION &&
            bounds.outWidth.toLong() * bounds.outHeight.toLong() <= PreparedDocumentUpload.MAX_PIXELS
        ) {
            bounds.outWidth to bounds.outHeight
        } else {
            null
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val DIRECTORY = "document_uploads"
        const val ARTIFACT_SUFFIX = ".fse"
        const val JPEG_MIME_TYPE = "image/jpeg"
        const val MAX_SAMPLE_SIZE = 32
        const val INITIAL_QUALITY = 90
        const val MIN_QUALITY = 45
        const val QUALITY_STEP = 5
        const val MAX_RESIZE_PASSES = 8
        const val MIN_DOWNSCALE_DIMENSION = 256
        const val RESIZE_FACTOR = 0.8
        val ROTATIONS = setOf(0, 90, 180, 270)
        val SUPPORTED_SOURCE_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")
        val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        val SHA256 = Regex("^[0-9a-f]{64}$")
    }

    private sealed interface DurablePreparedRead {
        data class Available(val prepared: PreparedDocumentUpload) : DurablePreparedRead
        data object Unavailable : DurablePreparedRead
        data object DurabilityFailed : DurablePreparedRead
    }
}

/**
 * Conserva como máximo el payload admitido. Los bytes que exceden el límite se descartan
 * mientras `Bitmap.compress` termina, y el buffer interno se limpia al cerrar incluso si la
 * compresión se cancela o falla.
 */
internal class CappedCancellationByteArrayOutputStream(
    private val maximumBytes: Int,
    private val cancellationJob: Job? = null,
) : ByteArrayOutputStream(minOf(maximumBytes.coerceAtLeast(1), INITIAL_BUFFER_BYTES)) {
    private var overflowed: Boolean = false

    init {
        require(maximumBytes > 0)
    }

    override fun write(value: Int) {
        cancellationJob?.ensureActive()
        if (count < maximumBytes) {
            super.write(value)
        } else {
            overflowed = true
        }
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        cancellationJob?.ensureActive()
        require(offset >= 0 && length >= 0 && offset + length <= buffer.size)
        val accepted = minOf(length, maximumBytes - count)
        if (accepted > 0) super.write(buffer, offset, accepted)
        if (accepted != length) overflowed = true
    }

    fun toByteArrayOrNull(): ByteArray? = if (overflowed) null else toByteArray()

    override fun close() {
        buf.fill(0)
        reset()
        overflowed = false
        super.close()
    }

    private companion object {
        const val INITIAL_BUFFER_BYTES: Int = 32 * 1024
    }
}
