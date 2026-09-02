package com.facturastock.app.data.files

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.core.net.toUri
import com.facturastock.app.domain.error.FileError
import com.facturastock.app.domain.error.FileException
import com.facturastock.app.domain.model.CaptureImagePolicy
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.repository.DraftImageImporter
import com.facturastock.app.domain.repository.ImportedImageFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Importador local de imágenes de factura con dos entradas que comparten el mismo núcleo
 * de validación, limpieza y persistencia:
 *
 * - **Galería** ([import]): copia el contenido de la URI a un archivo temporal dentro de
 *   [Context.getFilesDir] en una sola pasada contando bytes, y valida el MIME declarado
 *   por el proveedor.
 * - **Cámara** ([importBytes]): escribe los bytes JPEG ya capturados en memoria (su tamaño
 *   se conoce antes de tocar disco) y valida el MIME real al decodificar las cabeceras:
 *   solo se acepta `image/jpeg`, que es lo que produce `ImageCapture` de CameraX.
 *
 * Pipeline de cada página (todo o nada):
 * 1. Temporal aleatorio `import-*.tmp` en la raíz privada — nombre no identificable.
 * 2. Validación contra [CaptureImagePolicy]: tamaño, dimensiones y decodificabilidad.
 * 3. **Limpieza de metadatos** ([ImageMetadataScrubber]): la copia de trabajo queda sin
 *    geolocalización, EXIF ni XMP; la orientación EXIF se rescata ANTES de limpiar y se
 *    devuelve en [ImportedImageFile.rotationDegrees] para que viaje a los metadatos de
 *    dominio (misma convención que la rotación de sensor en cámara: el archivo se guarda
 *    sin rotar y la vista lo aplica).
 * 4. **Revalidación cerrada**: si la limpieza rompiera la imagen, la segunda decodificación
 *    de cabeceras y la prueba de decodificación lo detectan y la importación se rechaza.
 * 5. SHA-256 y tamaño se calculan sobre el archivo YA LIMPIO, de modo que el hash y los
 *    metadatos persistidos coinciden exactamente con el contenido guardado.
 * 6. Barrera durable (`flush` + `FileDescriptor.sync`) y publicación sin reemplazo al destino
 *    definitivo, seguida de `fsync` del padre y sus ancestros privados,
 *    `draft_images/{draftId}/{imageId}.{ext}`. Todas las mutaciones del subárbol se serializan
 *    por borrador: si el destino ya existe solo se reutiliza cuando su SHA-256 coincide; una
 *    colisión con contenido distinto falla preservando siempre el archivo anterior.
 *
 * Antes de empezar, cada entrada dispara [StaleImportCleanup]: solo borra temporales
 * `import-*.tmp` huérfanos por antigüedad, nunca páginas definitivas ni en curso.
 *
 * Las rutas relativas que devuelve son las mismas que borra [LocalDraftFileStore] gracias
 * al [PrivateFileResolver] compartido. Todo fallo llega como [FileException]: el temporal se
 * elimina (incluida la cancelación, que se observa por chunk) y el destino jamás queda a medias.
 * Un error real de fsync posterior al rename puede dejar un final completo pero todavía no
 * referenciado; el retry verifica su hash y repite la barrera antes de permitir que Room lo use.
 * La traducción de fallos de E/S vive en
 * [toFileException] (ENOSPC → [FileError.InsufficientSpace]; otro [IOException] →
 * [FileError.Corrupt]).
 */
@Singleton
class LocalDraftImageImporter @Inject constructor(
    @ApplicationContext context: Context,
    private val staleImportCleanup: StaleImportCleanup,
    private val mutationCoordinator: PrivateImageMutationCoordinator =
        PrivateImageMutationCoordinator(),
) : DraftImageImporter {
    private val rootDirectory: File = context.filesDir
    private val contentResolver: ContentResolver = context.contentResolver

    override suspend fun import(
        draftId: DraftId,
        imageId: ImageId,
        sourceUri: String,
    ): ImportedImageFile = mutationCoordinator.withDraftLock(draftId) {
        staleImportCleanup.cleanOrphanedImportTemps()
        val mimeType = resolveAllowedMimeType(sourceUri)
        val tempFile = createTempFile()
        try {
            copySourceToTemp(sourceUri, tempFile)
            scrubValidateAndMoveIntoPlace(draftId, imageId, mimeType, tempFile)
        } finally {
            // Tras el movimiento el temporal ya no existe; ante cualquier fallo se borra aquí.
            tempFile.delete()
        }
    }

    override suspend fun importBytes(
        draftId: DraftId,
        imageId: ImageId,
        jpegBytes: ByteArray,
        rotationDegrees: Int,
    ): ImportedImageFile = mutationCoordinator.withDraftLock(draftId) {
        // La rotación del sensor no se aplica al archivo: la registra el caso de uso en los
        // metadatos y la vista la aplica al mostrar la imagen. Aun así el JPEG se limpia de
        // cualquier EXIF que pudiera traer.
        staleImportCleanup.cleanOrphanedImportTemps()
        if (!CaptureImagePolicy.isSizeAllowed(jpegBytes.size.toLong())) {
            throw FileException(FileError.TooLarge)
        }
        val tempFile = createTempFile()
        try {
            writeBytesToTemp(jpegBytes, tempFile)
            val bounds = decodeBounds(tempFile)
            // El MIME se verifica en el contenido real, no en la declaración del origen.
            if (bounds.outMimeType?.trim()?.lowercase(Locale.ROOT) != JPEG_MIME_TYPE) {
                throw FileException(FileError.UnsupportedFormat)
            }
            scrubValidateAndMoveIntoPlace(
                draftId = draftId,
                imageId = imageId,
                mimeType = JPEG_MIME_TYPE,
                tempFile = tempFile,
                bounds = bounds,
            )
        } finally {
            tempFile.delete()
        }
    }

    /** MIME declarado por el proveedor, rechazado si la política no lo admite. */
    private fun resolveAllowedMimeType(sourceUri: String): String {
        val mimeType = try {
            contentResolver.getType(sourceUri.toUri())
        } catch (failure: SecurityException) {
            // El proveedor revocó el acceso al contenido: equivale a que ya no existe.
            throw FileException(FileError.NotFound, failure)
        } catch (failure: RuntimeException) {
            // Proveedor roto o URI mal formada: sin tipo confiable no se acepta.
            null
        }?.trim()?.lowercase(Locale.ROOT)
        if (!CaptureImagePolicy.isMimeTypeAllowed(mimeType)) {
            throw FileException(FileError.UnsupportedFormat)
        }
        return checkNotNull(mimeType)
    }

    private fun createTempFile(): File = try {
        File.createTempFile(TEMP_FILE_PREFIX, TEMP_FILE_SUFFIX, rootDirectory)
    } catch (failure: IOException) {
        throw failure.toFileException()
    }

    /** Copia en una sola pasada contando bytes; la cancelación se observa por chunk. */
    private suspend fun copySourceToTemp(sourceUri: String, tempFile: File) {
        var totalBytes = 0L
        try {
            val input = contentResolver.openInputStream(sourceUri.toUri())
                ?: throw FileException(FileError.NotFound)
            input.use { source ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = source.read(buffer)
                        if (read < 0) break
                        totalBytes += read
                        if (!CaptureImagePolicy.isSizeAllowed(totalBytes)) {
                            throw FileException(FileError.TooLarge)
                        }
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
        } catch (failure: FileException) {
            throw failure
        } catch (failure: FileNotFoundException) {
            throw FileException(FileError.NotFound, failure)
        } catch (failure: SecurityException) {
            throw FileException(FileError.NotFound, failure)
        } catch (failure: IOException) {
            throw failure.toFileException()
        }
    }

    /** Escribe los bytes ya validados en bloques para observar cancelación durante JPEG grandes. */
    private suspend fun writeBytesToTemp(bytes: ByteArray, tempFile: File) {
        try {
            FileOutputStream(tempFile).use { output ->
                var offset = 0
                while (offset < bytes.size) {
                    currentCoroutineContext().ensureActive()
                    val length = minOf(COPY_BUFFER_BYTES, bytes.size - offset)
                    output.write(bytes, offset, length)
                    offset += length
                }
                output.flush()
                output.fd.sync()
            }
        } catch (failure: IOException) {
            throw failure.toFileException()
        }
    }

    /**
     * Núcleo compartido por ambas entradas: valida dimensiones, limpia los metadatos del
     * temporal, revalida que la imagen siga íntegra, calcula el SHA-256 del contenido
     * limpio y lo mueve a su destino final. [bounds] permite reutilizar las cabeceras ya
     * leídas por la entrada de bytes.
     */
    private suspend fun scrubValidateAndMoveIntoPlace(
        draftId: DraftId,
        imageId: ImageId,
        mimeType: String,
        tempFile: File,
        bounds: BitmapFactory.Options = decodeBounds(tempFile),
    ): ImportedImageFile {
        if (!CaptureImagePolicy.areDimensionsAllowed(bounds.outWidth, bounds.outHeight)) {
            throw FileException(FileError.TooLarge)
        }
        val job = currentCoroutineContext()[Job]
        val rotationDegrees = ImageMetadataScrubber.scrub(tempFile, mimeType) {
            job?.ensureActive()
        }
        currentCoroutineContext().ensureActive()
        // Revalidación cerrada: la limpieza jamás debe dejar una imagen rota.
        val cleanBounds = decodeBounds(tempFile)
        probeDecodable(tempFile, cleanBounds)
        currentCoroutineContext().ensureActive()
        val hashed = hashFile(tempFile)
        currentCoroutineContext().ensureActive()
        try {
            // El scrub puede haber reemplazado el inode: la última versión validada es la que
            // debe llegar estable al medio antes de hacer visible su nombre definitivo.
            DurablePrivateFilePublication.syncFile(tempFile)
        } catch (failure: IOException) {
            throw failure.toFileException()
        }
        currentCoroutineContext().ensureActive()
        val relativePath = relativePathFor(draftId, imageId, mimeType)
        publishOrReuse(tempFile, relativePath, hashed)
        return ImportedImageFile(
            relativePath = relativePath,
            sha256 = hashed.sha256,
            mimeType = mimeType,
            widthPx = cleanBounds.outWidth,
            heightPx = cleanBounds.outHeight,
            fileSizeBytes = hashed.fileSizeBytes,
            rotationDegrees = rotationDegrees,
        )
    }

    /** Cabeceras decodificadas sin cargar píxeles; un archivo ilegible es [FileError.Corrupt]. */
    private fun decodeBounds(tempFile: File): BitmapFactory.Options {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(tempFile.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw FileException(FileError.Corrupt)
        }
        return bounds
    }

    /**
     * Prueba de decodificabilidad: con las dimensiones ya conocidas ([bounds]) se confirma
     * que el contenido es legible con una decodificación real con muestreo, sin reventar la
     * memoria.
     */
    private fun probeDecodable(tempFile: File, bounds: BitmapFactory.Options) {
        val probe = BitmapFactory.Options().apply {
            inSampleSize = decodeSampleSize(bounds.outWidth, bounds.outHeight)
        }
        val decoded = try {
            BitmapFactory.decodeFile(tempFile.absolutePath, probe)
                ?: throw FileException(FileError.Corrupt)
        } catch (failure: OutOfMemoryError) {
            throw FileException(FileError.TooLarge, failure)
        }
        decoded.recycle()
    }

    /** Muestra potencia de dos que limita el lado mayor, incluidas imágenes panorámicas. */
    private fun decodeSampleSize(widthPx: Int, heightPx: Int): Int {
        return InvoiceBitmapTransforms.sampleSizeFor(
            widthPx = widthPx,
            heightPx = heightPx,
            maximumSidePx = DECODE_PROBE_PX,
        )
    }

    /** SHA-256 y tamaño del archivo ya limpio, en una pasada. */
    private suspend fun hashFile(file: File): HashedContent {
        val digest = MessageDigest.getInstance("SHA-256")
        var totalBytes = 0L
        try {
            file.inputStream().use { input ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    totalBytes += read
                    digest.update(buffer, 0, read)
                }
            }
        } catch (failure: IOException) {
            throw failure.toFileException()
        }
        return HashedContent(fileSizeBytes = totalBytes, sha256 = digest.toHexDigest())
    }

    /**
     * El lock compartido del borrador ya cubre esta publicación. Una repetición con el mismo
     * contenido reutiliza el final íntegro; una colisión distinta se rechaza sin tocarlo.
     */
    private suspend fun publishOrReuse(
        tempFile: File,
        relativePath: String,
        expected: HashedContent,
    ) {
        // La ruta se construye con IDs canónicos internos; la resolución nunca debería fallar.
        val destination = PrivateFileResolver.resolveForNoFollowDeletion(rootDirectory, relativePath)
            ?: throw FileException(FileError.Corrupt)
        if (Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw FileException(FileError.Corrupt)
            }
            requireMatchingExisting(destination, expected)
            try {
                // Un retry también confirma la durabilidad de un nombre dejado por un intento
                // anterior que alcanzó el rename pero no llegó a publicar su fila de Room.
                DurablePrivateFilePublication.syncFile(destination)
                DurablePrivateFilePublication.syncParentChainAfterRename(
                    destination,
                    rootDirectory,
                )
            } catch (failure: IOException) {
                throw failure.toFileException()
            }
            currentCoroutineContext().ensureActive()
            return
        }

        try {
            val parent = destination.parentFile ?: throw FileException(FileError.Corrupt)
            if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
                throw IOException("No se pudo crear el directorio privado de imágenes")
            }
            if (
                Files.isSymbolicLink(parent.toPath()) ||
                !Files.isDirectory(parent.toPath(), LinkOption.NOFOLLOW_LINKS)
            ) {
                throw FileException(FileError.Corrupt)
            }
            currentCoroutineContext().ensureActive()
            publishWithoutReplacing(tempFile, destination)
            DurablePrivateFilePublication.syncFile(destination)
            DurablePrivateFilePublication.syncParentChainAfterRename(
                destination,
                rootDirectory,
            )
            currentCoroutineContext().ensureActive()
        } catch (_: FileAlreadyExistsException) {
            // Defensa adicional ante una publicación desde fuera de este singleton.
            requireMatchingExisting(destination, expected)
            try {
                DurablePrivateFilePublication.syncFile(destination)
                DurablePrivateFilePublication.syncParentChainAfterRename(
                    destination,
                    rootDirectory,
                )
            } catch (failure: IOException) {
                throw failure.toFileException()
            }
            currentCoroutineContext().ensureActive()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: IOException) {
            throw failure.toFileException()
        }
    }

    /**
     * Publica sin reemplazo. El hard-link es una operación atómica del filesystem y conserva el
     * inode ya sincronizado; el temporal raíz se elimina en el `finally` del llamador después de
     * confirmar el directorio destino. En filesystems Android que no admiten hard-links se usa
     * `Files.move` sin `REPLACE_EXISTING`, siempre dentro del mismo `filesDir`; a diferencia del
     * fallback anterior, jamás se copia una secuencia parcial directamente al nombre publicado.
     * Android puede expresar el bloqueo SELinux de hard-links como `EACCES` o `EPERM`; ambos
     * permiten este fallback porque origen y destino ya están resueltos dentro del mismo
     * `filesDir` privado y el movimiento sigue prohibiendo reemplazar un nombre existente.
     */
    private fun publishWithoutReplacing(source: File, destination: File) {
        try {
            Os.link(source.absolutePath, destination.absolutePath)
        } catch (failure: ErrnoException) {
            when (failure.errno) {
                OsConstants.EEXIST -> throw FileAlreadyExistsException(destination.path)
                OsConstants.EACCES,
                OsConstants.EPERM,
                OsConstants.ENOTSUP,
                OsConstants.EOPNOTSUPP,
                OsConstants.EXDEV,
                -> Files.move(source.toPath(), destination.toPath())
                else -> throw IOException("No se pudo publicar la imagen privada", failure)
            }
        }
    }

    private suspend fun requireMatchingExisting(destination: File, expected: HashedContent) {
        if (!Files.isRegularFile(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw FileException(FileError.Corrupt)
        }
        val existing = hashFile(destination)
        if (existing != expected) {
            throw FileException(FileError.Corrupt)
        }
    }

    private fun relativePathFor(draftId: DraftId, imageId: ImageId, mimeType: String): String =
        "$IMAGE_DIRECTORY/${draftId.value}/${imageId.value}.${extensionFor(mimeType)}"

    private fun extensionFor(mimeType: String): String = when (mimeType) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        else -> error("MIME no cubierto por la política ya validada: $mimeType")
    }

    private fun MessageDigest.toHexDigest(): String {
        val bytes = digest()
        val encoded = CharArray(bytes.size * 2)
        bytes.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xFF
            encoded[index * 2] = HEX_DIGITS[value ushr 4]
            encoded[index * 2 + 1] = HEX_DIGITS[value and 0x0F]
        }
        return encoded.concatToString()
    }

    private data class HashedContent(val fileSizeBytes: Long, val sha256: String)

    internal companion object {
        const val IMAGE_DIRECTORY = "draft_images"
        const val TEMP_FILE_PREFIX = "import-"
        const val TEMP_FILE_SUFFIX = ".tmp"
        private const val JPEG_MIME_TYPE = "image/jpeg"
        private const val COPY_BUFFER_BYTES = 8 * 1024
        private const val DECODE_PROBE_PX = 1_024
        private const val HEX_DIGITS = "0123456789abcdef"
    }
}
