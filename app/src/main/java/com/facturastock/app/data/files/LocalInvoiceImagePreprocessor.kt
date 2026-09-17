package com.facturastock.app.data.files

import android.content.Context
import android.graphics.Bitmap
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.domain.error.FileError
import com.facturastock.app.domain.error.FileException
import com.facturastock.app.domain.model.InvoiceImage
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.repository.InvoiceImagePreprocessor
import com.facturastock.app.domain.repository.OcrImageFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Preprocesador OCR local. Una ejecución escribe todas sus páginas en un `run` nuevo y solo
 * publica un manifiesto durable después de completarlas. Cada página y el manifiesto ejecutan
 * `flush` + `FileDescriptor.sync` antes del rename, y después sincronizan sus directorios. Así
 * cancelación, fallo o muerte de proceso no sustituyen el último lote válido. Decode/píxeles están
 * acotados a 2048 px por lado, escala de grises, contraste 112 % y JPEG 88; el original solo se
 * abre para lectura.
 */
@Singleton
class LocalInvoiceImagePreprocessor @Inject constructor(
    @ApplicationContext context: Context,
    private val uuidGenerator: UuidGenerator,
    private val dispatcherProvider: DispatcherProvider,
    private val mutationCoordinator: PrivateImageMutationCoordinator =
        PrivateImageMutationCoordinator(),
    private val deletionDurability: PrivateDeletionDurability = PrivateDeletionDurability(),
) : InvoiceImagePreprocessor {
    private val rootDirectory: File = context.filesDir
    /** Acota el pico de bitmap: solo un lote OCR pesado puede decodificar píxeles a la vez. */
    private val processingMutex = Mutex()

    override suspend fun preprocess(
        draftId: DraftId,
        images: List<InvoiceImage>,
    ): List<OcrImageFile> = processingMutex.withLock {
        mutationCoordinator.withDraftLock(draftId) {
            if (images.isEmpty() || images.any { it.draftId != draftId }) {
                throw FileException(FileError.Corrupt)
            }
            currentCoroutineContext().ensureActive()
            // Un SIGKILL no ejecuta el catch inferior. Limpiar antes de reservar otro lote evita
            // que un run no publicado consuma el espacio que precisamente necesita el retry; el
            // run señalado por el manifiesto vigente se conserva siempre.
            cleanupUnpublishedRuns(draftId, keepRunId = publishedRunIdOrNull(draftId))
            val runId = uuidGenerator.newUuid().toString()
            val runDirectory = runDirectory(draftId, runId)
            withContext(dispatcherProvider.io) {
                if (!runDirectory.mkdirs() && !runDirectory.isDirectory) {
                    throw FileException(FileError.InsufficientSpace)
                }
            }
            var manifestMayReferenceRun = false
            try {
                val prepared = images.map { image -> preprocessPage(image, runId) }
                publishManifest(draftId, runId, prepared) {
                    // Si una barrera posterior al rename falla, el manifiesto ya puede señalar al
                    // run. Se conserva para que jamás quede apuntando a páginas borradas.
                    manifestMayReferenceRun = true
                }
                cleanupUnpublishedRuns(draftId, keepRunId = runId)
                prepared
            } catch (failure: Throwable) {
                if (!manifestMayReferenceRun) {
                    withContext(NonCancellable + dispatcherProvider.io) {
                        runCatching {
                            deletionDurability.deleteTree(runDirectory, rootDirectory)
                        }
                    }
                }
                throw failure
            }
        }
    }

    override suspend fun findPrepared(draftId: DraftId): List<OcrImageFile> =
        mutationCoordinator.withDraftLock(draftId) {
            withContext(dispatcherProvider.io) { readManifest(draftId) }
        }

    override suspend fun clearOcrVersions(draftId: DraftId) {
        // Esperar otro lote debe seguir siendo cancelable. Una vez adquirido el mutex, el borrado
        // corto sí se completa en NonCancellable para no dejar un árbol OCR a medias.
        mutationCoordinator.withDraftLock(draftId) {
            withContext(NonCancellable + dispatcherProvider.io) {
                runCatching {
                    deletionDurability.deleteTree(
                        ocrRootDirectoryForDeletion(draftId),
                        rootDirectory,
                    )
                }
            }
        }
    }

    private suspend fun preprocessPage(image: InvoiceImage, runId: String): OcrImageFile =
        withContext(dispatcherProvider.default) {
            currentCoroutineContext().ensureActive()
            val source = InvoiceBitmapTransforms.resolveSource(rootDirectory, image)
            val loaded = InvoiceBitmapTransforms.loadTransformed(
                source = source,
                image = image,
                maximumSidePx = OCR_MAX_SIDE_PX,
            )
            var bitmap = loaded.bitmap
            try {
                if (!bitmap.isMutable) {
                    val mutableCopy = try {
                        bitmap.copy(Bitmap.Config.ARGB_8888, true)
                    } catch (failure: OutOfMemoryError) {
                        throw FileException(FileError.TooLarge, failure)
                    } ?: throw FileException(FileError.Corrupt)
                    bitmap.recycleSafely()
                    bitmap = mutableCopy
                }
                applyGrayscaleAndContrast(bitmap)
                writeOcrVersion(image, bitmap, runId)
            } catch (failure: OutOfMemoryError) {
                throw FileException(FileError.TooLarge, failure)
            } finally {
                bitmap.recycleSafely()
            }
        }

    internal suspend fun applyGrayscaleAndContrast(bitmap: Bitmap) {
        val width = bitmap.width
        val height = bitmap.height
        // Un bloque acotado evita cruzar JNI dos veces por fila sin retener otra página.
        // A 2048 px de ancho el scratch ocupa como máximo 128 KiB.
        val pixels = IntArray(width * minOf(PIXEL_BLOCK_ROWS, height))
        for (top in 0 until height step PIXEL_BLOCK_ROWS) {
            currentCoroutineContext().ensureActive()
            val rows = minOf(PIXEL_BLOCK_ROWS, height - top)
            bitmap.getPixels(pixels, 0, width, 0, top, width, rows)
            for (row in 0 until rows) {
                currentCoroutineContext().ensureActive()
                val offset = row * width
                for (x in 0 until width) {
                    val index = offset + x
                    val color = pixels[index]
                    val alpha = color ushr 24 and 0xFF
                    val red = compositeOnWhite(color ushr 16 and 0xFF, alpha)
                    val green = compositeOnWhite(color ushr 8 and 0xFF, alpha)
                    val blue = compositeOnWhite(color and 0xFF, alpha)
                    val gray = (red * 77 + green * 150 + blue * 29) ushr 8
                    val contrasted = (
                        (gray - CONTRAST_PIVOT) * CONTRAST_PERCENT / 100 + CONTRAST_PIVOT
                        ).coerceIn(0, 255)
                    pixels[index] = OPAQUE_ALPHA or
                        (contrasted shl 16) or
                        (contrasted shl 8) or
                        contrasted
                }
            }
            currentCoroutineContext().ensureActive()
            bitmap.setPixels(pixels, 0, width, 0, top, width, rows)
        }
    }

    private suspend fun writeOcrVersion(
        image: InvoiceImage,
        bitmap: Bitmap,
        runId: String,
    ): OcrImageFile = withContext(dispatcherProvider.io) {
        val relativePath = ocrImagePath(image.draftId, runId, image.imageId)
        val destination = safePrivatePath(relativePath)
        if (Files.isSymbolicLink(destination.toPath())) throw FileException(FileError.Corrupt)
        val directory = destination.parentFile ?: throw FileException(FileError.Corrupt)
        if (!directory.mkdirs() && !directory.isDirectory) {
            throw FileException(FileError.InsufficientSpace)
        }
        requireSafeDirectory(directory)
        val tempFile = try {
            File.createTempFile(OCR_TEMP_PREFIX, OCR_TEMP_SUFFIX, directory)
        } catch (failure: IOException) {
            throw failure.toFileException()
        }
        try {
            val job = currentCoroutineContext()[Job]
            FileOutputStream(tempFile).use { rawOutput ->
                val bufferedOutput = BufferedOutputStream(rawOutput)
                val cancellable = CancellationCheckingOutputStream(bufferedOutput, job)
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, cancellable)) {
                    throw FileException(FileError.Corrupt)
                }
                cancellable.flush()
                rawOutput.fd.sync()
            }
            currentCoroutineContext().ensureActive()
            moveReplacing(tempFile, destination)
            DurablePrivateFilePublication.syncFile(destination)
            currentCoroutineContext().ensureActive()
            val outputSize = destination.length().takeIf { it > 0L }
                ?: throw FileException(FileError.Corrupt)
            OcrImageFile(
                sourceImageId = image.imageId,
                relativePath = relativePath,
                mimeType = OCR_MIME_TYPE,
                widthPx = bitmap.width,
                heightPx = bitmap.height,
                fileSizeBytes = outputSize,
            )
        } catch (failure: IOException) {
            destination.delete()
            throw failure.toFileException()
        } catch (failure: Throwable) {
            destination.delete()
            throw failure
        } finally {
            tempFile.delete()
        }
    }

    private suspend fun publishManifest(
        draftId: DraftId,
        runId: String,
        pages: List<OcrImageFile>,
        onRenamed: () -> Unit,
    ) = withContext(dispatcherProvider.io) {
        val ocrRoot = ocrRootDirectory(draftId)
        if (!ocrRoot.mkdirs() && !ocrRoot.isDirectory) {
            throw FileException(FileError.InsufficientSpace)
        }
        requireSafeDirectory(ocrRoot)
        val manifest = manifestFile(draftId)
        if (Files.isSymbolicLink(manifest.toPath())) throw FileException(FileError.Corrupt)
        val temp = try {
            File.createTempFile(MANIFEST_TEMP_PREFIX, MANIFEST_TEMP_SUFFIX, ocrRoot)
        } catch (failure: IOException) {
            throw failure.toFileException()
        }
        try {
            // Todas las páginas comparten el mismo run. Una única barrera aquí persiste sus
            // nombres y los ancestros creados antes de hacer visible el manifiesto, evitando una
            // cadena de fsync redundante por cada página sin abrir una ventana de publicación.
            val firstPage = pages.firstOrNull() ?: throw FileException(FileError.Corrupt)
            DurablePrivateFilePublication.syncParentChainAfterRename(
                safePrivatePath(firstPage.relativePath),
                rootDirectory,
            )
            currentCoroutineContext().ensureActive()
            FileOutputStream(temp).use { rawOutput ->
                val writer = rawOutput.writer(Charsets.UTF_8).buffered()
                writer.appendLine("recipe=$RECIPE_VERSION")
                writer.appendLine("run=$runId")
                pages.forEach { page ->
                    currentCoroutineContext().ensureActive()
                    writer.appendLine(
                        listOf(
                            page.sourceImageId.value,
                            page.widthPx,
                            page.heightPx,
                            page.fileSizeBytes,
                        ).joinToString(MANIFEST_SEPARATOR),
                    )
                }
                writer.flush()
                rawOutput.fd.sync()
            }
            currentCoroutineContext().ensureActive()
            moveReplacing(temp, manifest)
            onRenamed()
            DurablePrivateFilePublication.syncFile(manifest)
            DurablePrivateFilePublication.syncParentChainAfterRename(
                manifest,
                rootDirectory,
            )
            currentCoroutineContext().ensureActive()
        } catch (failure: IOException) {
            throw failure.toFileException()
        } finally {
            temp.delete()
        }
    }

    private suspend fun readManifest(draftId: DraftId): List<OcrImageFile> {
        val manifest = manifestFile(draftId)
        if (Files.notExists(manifest.toPath(), LinkOption.NOFOLLOW_LINKS)) return emptyList()
        if (!Files.isRegularFile(manifest.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw FileException(FileError.Corrupt)
        }
        return try {
            manifest.bufferedReader().use { reader ->
                if (reader.readLine() != "recipe=$RECIPE_VERSION") {
                    throw FileException(FileError.Corrupt)
                }
                val runId = reader.readLine()?.removePrefix("run=")
                    ?.takeIf(::isCanonicalUuid)
                    ?: throw FileException(FileError.Corrupt)
                val pages = mutableListOf<OcrImageFile>()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = reader.readLine() ?: break
                    val fields = line.split(MANIFEST_SEPARATOR)
                    if (fields.size != MANIFEST_PAGE_FIELDS) {
                        throw FileException(FileError.Corrupt)
                    }
                    val imageId = ImageId.parse(fields[0])
                        ?: throw FileException(FileError.Corrupt)
                    val width = fields[1].toIntOrNull()?.takeIf { it > 0 }
                        ?: throw FileException(FileError.Corrupt)
                    val height = fields[2].toIntOrNull()?.takeIf { it > 0 }
                        ?: throw FileException(FileError.Corrupt)
                    val size = fields[3].toLongOrNull()?.takeIf { it > 0L }
                        ?: throw FileException(FileError.Corrupt)
                    val relativePath = ocrImagePath(draftId, runId, imageId)
                    val file = safePrivatePath(relativePath)
                    if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                        file.length() != size
                    ) {
                        throw FileException(FileError.Corrupt)
                    }
                    pages += OcrImageFile(
                        sourceImageId = imageId,
                        relativePath = relativePath,
                        mimeType = OCR_MIME_TYPE,
                        widthPx = width,
                        heightPx = height,
                        fileSizeBytes = size,
                    )
                }
                pages
            }
        } catch (failure: IOException) {
            throw failure.toFileException()
        }
    }

    private suspend fun cleanupUnpublishedRuns(draftId: DraftId, keepRunId: String?) {
        withContext(NonCancellable + dispatcherProvider.io) {
            val runs = runsDirectory(draftId)
            if (Files.exists(runs.toPath(), LinkOption.NOFOLLOW_LINKS)) requireSafeDirectory(runs)
            runs.listFiles()
                ?.filter { it.name != keepRunId }
                ?.forEach { directory ->
                    runCatching { deletionDurability.deleteTree(directory, rootDirectory) }
                }
            val ocrRoot = ocrRootDirectory(draftId)
            if (Files.exists(ocrRoot.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                requireSafeDirectory(ocrRoot)
            }
            ocrRoot.listFiles()
                ?.filter { file ->
                    Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                        file.name.startsWith(MANIFEST_TEMP_PREFIX) &&
                        file.name.endsWith(MANIFEST_TEMP_SUFFIX)
                }
                ?.forEach { temporary ->
                    runCatching {
                        Files.deleteIfExists(temporary.toPath())
                        deletionDurability.syncAfterDeletion(temporary, rootDirectory)
                    }
                }
        }
    }

    /** Lee solo la identidad publicada; nunca elimina ese run aunque el resto esté corrupto. */
    private suspend fun publishedRunIdOrNull(draftId: DraftId): String? =
        withContext(dispatcherProvider.io) {
            val manifest = manifestFile(draftId)
            if (Files.notExists(manifest.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                return@withContext null
            }
            if (!Files.isRegularFile(manifest.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw FileException(FileError.Corrupt)
            }
            val headers = try {
                manifest.bufferedReader().use { reader ->
                    listOfNotNull(reader.readLine(), reader.readLine())
                }
            } catch (failure: IOException) {
                throw failure.toFileException()
            }
            if (headers.size != MANIFEST_HEADER_LINES || headers[0] != "recipe=$RECIPE_VERSION") {
                return@withContext null
            }
            headers[1].removePrefix("run=").takeIf(::isCanonicalUuid)
        }

    /** `rename(2)` reemplaza dentro del mismo directorio; nunca copia un nombre publicado. */
    private fun moveReplacing(source: File, destination: File) {
        DurablePrivateFilePublication.replaceByRename(source, destination)
    }

    private fun ocrRootDirectory(draftId: DraftId): File =
        safeDirectoryPath(
            "${LocalDraftImageImporter.IMAGE_DIRECTORY}/${draftId.value}/$OCR_DIRECTORY",
        )

    private fun ocrRootDirectoryForDeletion(draftId: DraftId): File =
        PrivateFileResolver.resolveForNoFollowDeletion(
            rootDirectory,
            "${LocalDraftImageImporter.IMAGE_DIRECTORY}/${draftId.value}/$OCR_DIRECTORY",
        ) ?: throw FileException(FileError.Corrupt)

    private fun runsDirectory(draftId: DraftId): File = safeDirectoryPath(
        "${LocalDraftImageImporter.IMAGE_DIRECTORY}/${draftId.value}/$OCR_DIRECTORY/" +
            RUNS_DIRECTORY,
    )

    private fun runDirectory(draftId: DraftId, runId: String): File =
        safeDirectoryPath(
            "${LocalDraftImageImporter.IMAGE_DIRECTORY}/${draftId.value}/$OCR_DIRECTORY/" +
                "$RUNS_DIRECTORY/$runId",
        )

    private fun manifestFile(draftId: DraftId): File = safePrivatePath(
        "${LocalDraftImageImporter.IMAGE_DIRECTORY}/${draftId.value}/$OCR_DIRECTORY/" +
            CURRENT_MANIFEST,
    )

    private fun safeDirectoryPath(relativePath: String): File = safePrivatePath(relativePath)
        .also { directory ->
            if (Files.exists(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                requireSafeDirectory(directory)
            }
        }

    private fun safePrivatePath(relativePath: String): File =
        PrivateFileResolver.resolveForNoFollowDeletion(rootDirectory, relativePath)
            ?: throw FileException(FileError.Corrupt)

    private fun requireSafeDirectory(directory: File) {
        if (
            Files.isSymbolicLink(directory.toPath()) ||
            !Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)
        ) {
            throw FileException(FileError.Corrupt)
        }
    }

    private fun ocrImagePath(draftId: DraftId, runId: String, imageId: ImageId): String =
        "${LocalDraftImageImporter.IMAGE_DIRECTORY}/${draftId.value}/$OCR_DIRECTORY/" +
            "$RUNS_DIRECTORY/$runId/${imageId.value}-$RECIPE_VERSION.$OCR_EXTENSION"

    private fun compositeOnWhite(channel: Int, alpha: Int): Int =
        (channel * alpha + 255 * (255 - alpha) + 127) / 255

    private fun isCanonicalUuid(value: String): Boolean = try {
        UUID.fromString(value).toString() == value
    } catch (_: IllegalArgumentException) {
        false
    }

    /** Hace cooperativa la compresión nativa al comprobar el Job en cada escritura. */
    private class CancellationCheckingOutputStream(
        output: OutputStream,
        private val job: Job?,
    ) : FilterOutputStream(output) {
        override fun write(value: Int) {
            job?.ensureActive()
            out.write(value)
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            job?.ensureActive()
            out.write(buffer, offset, length)
        }
    }

    internal companion object {
        const val OCR_MAX_SIDE_PX = 2_048
        const val JPEG_QUALITY = 88
        const val CONTRAST_PERCENT = 112
        const val OCR_DIRECTORY = "ocr"
        const val RECIPE_VERSION = "v1"

        private const val CONTRAST_PIVOT = 128
        private const val PIXEL_BLOCK_ROWS = 16
        private const val OPAQUE_ALPHA = -0x1000000
        private const val RUNS_DIRECTORY = "runs"
        private const val CURRENT_MANIFEST = "current-v1.manifest"
        private const val OCR_EXTENSION = "jpg"
        private const val OCR_MIME_TYPE = "image/jpeg"
        private const val OCR_TEMP_PREFIX = "ocr-page-"
        private const val OCR_TEMP_SUFFIX = ".tmp"
        private const val MANIFEST_TEMP_PREFIX = "ocr-manifest-"
        private const val MANIFEST_TEMP_SUFFIX = ".tmp"
        private const val MANIFEST_SEPARATOR = "|"
        private const val MANIFEST_HEADER_LINES = 2
        private const val MANIFEST_PAGE_FIELDS = 4
    }
}
