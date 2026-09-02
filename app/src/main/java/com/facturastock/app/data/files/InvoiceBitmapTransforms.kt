package com.facturastock.app.data.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import androidx.exifinterface.media.ExifInterface
import com.facturastock.app.domain.error.FileError
import com.facturastock.app.domain.error.FileException
import com.facturastock.app.domain.model.ImageCrop
import com.facturastock.app.domain.model.InvoiceImage
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Transformaciones compartidas por análisis y preprocesamiento, siempre sobre una muestra. */
internal object InvoiceBitmapTransforms {
    data class Loaded(
        val bitmap: Bitmap,
        val sourceWidthPx: Int,
        val sourceHeightPx: Int,
        val appliedRotationDegrees: Int,
    )

    fun resolveSource(rootDirectory: File, image: InvoiceImage): File =
        image.filePath.takeIf(PrivateFileResolver::isDraftImageFilePath)
            ?.let { PrivateFileResolver.resolveForNoFollowDeletion(rootDirectory, it) }
            ?.takeIf { file ->
                Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(file.toPath())
            }
            ?: throw FileException(FileError.NotFound)

    /**
     * Convierte primero el recorte orientado a coordenadas de la fuente y decodifica solo esa
     * región con una muestra potencia de dos. Si el decoder regional no está disponible para un
     * archivo válido, vuelve al camino seguro de página completa acotada y recorte posterior.
     * La rotación persistida tiene prioridad; para archivos legacy con rotación 0 se consulta
     * EXIF de forma defensiva.
     */
    suspend fun loadTransformed(
        source: File,
        image: InvoiceImage,
        maximumSidePx: Int,
    ): Loaded {
        require(maximumSidePx > 0)
        currentCoroutineContext().ensureActive()
        val bounds = decodeBounds(source)
        val rotationDegrees = resolvedRotationDegrees(source, image.rotationDegrees)
        val crop = image.crop?.takeUnless(ImageCrop::isFullImage)
        val sourceCrop = crop?.let {
            sourceCropRect(
                crop = it,
                sourceWidthPx = bounds.outWidth,
                sourceHeightPx = bounds.outHeight,
                rotationDegrees = rotationDegrees,
            )
        }
        val regionDecoded = sourceCrop?.let { region ->
            decodeRegionSampledOrNull(source, region, maximumSidePx)
        }
        val cropDecodedAtSource = regionDecoded != null
        var current = regionDecoded ?: decodeSampled(source, bounds, maximumSidePx)
        try {
            currentCoroutineContext().ensureActive()
            current = replace(current, rotate(current, rotationDegrees))
            if (!cropDecodedAtSource) {
                currentCoroutineContext().ensureActive()
                current = replace(current, crop(current, crop))
            }
            currentCoroutineContext().ensureActive()
            return Loaded(
                bitmap = current,
                sourceWidthPx = bounds.outWidth,
                sourceHeightPx = bounds.outHeight,
                appliedRotationDegrees = rotationDegrees,
            )
        } catch (failure: Throwable) {
            current.recycleSafely()
            throw failure
        }
    }

    fun effectiveDimensions(loaded: Loaded, crop: ImageCrop?): Pair<Int, Int> {
        val rotatedWidth = if (loaded.appliedRotationDegrees in RIGHT_ANGLE_ROTATIONS) {
            loaded.sourceHeightPx
        } else {
            loaded.sourceWidthPx
        }
        val rotatedHeight = if (loaded.appliedRotationDegrees in RIGHT_ANGLE_ROTATIONS) {
            loaded.sourceWidthPx
        } else {
            loaded.sourceHeightPx
        }
        if (crop == null) return rotatedWidth to rotatedHeight
        val cropRect = orientedCropRect(crop, rotatedWidth, rotatedHeight)
        return cropRect.width() to cropRect.height()
    }

    internal fun sampleSizeFor(widthPx: Int, heightPx: Int, maximumSidePx: Int): Int {
        var sampleSize = 1
        val largestSide = maxOf(widthPx, heightPx)
        while (ceilDiv(largestSide, sampleSize) > maximumSidePx) {
            if (sampleSize > Int.MAX_VALUE / 2) break
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun decodeBounds(source: File): BitmapFactory.Options {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw FileException(FileError.Corrupt)
        }
        return bounds
    }

    private fun decodeSampled(
        source: File,
        bounds: BitmapFactory.Options,
        maximumSidePx: Int,
    ): Bitmap {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maximumSidePx)
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        return try {
            BitmapFactory.decodeFile(source.absolutePath, options)
                ?: throw FileException(FileError.Corrupt)
        } catch (failure: OutOfMemoryError) {
            // La muestra está acotada; si el dispositivo aun así no tiene margen, el fallo se
            // vuelve recuperable en lugar de derribar el proceso.
            throw FileException(FileError.TooLarge, failure)
        }
    }

    /**
     * Intenta decodificar únicamente [sourceCrop]. `null` solicita el fallback seguro de página
     * completa; cancelación y OOM siempre se propagan, y todo decoder/bitmap intermedio se libera.
     */
    @Suppress("DEPRECATION")
    private suspend fun decodeRegionSampledOrNull(
        source: File,
        sourceCrop: Rect,
        maximumSidePx: Int,
    ): Bitmap? {
        currentCoroutineContext().ensureActive()
        val decoder = try {
            BitmapRegionDecoder.newInstance(source.absolutePath, false)
        } catch (_: IOException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        } catch (failure: OutOfMemoryError) {
            throw FileException(FileError.TooLarge, failure)
        }

        var decoded: Bitmap? = null
        try {
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(sourceCrop.width(), sourceCrop.height(), maximumSidePx)
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = true
            }
            decoded = try {
                decoder.decodeRegion(sourceCrop, options)
            } catch (_: IllegalArgumentException) {
                return null
            } catch (failure: OutOfMemoryError) {
                throw FileException(FileError.TooLarge, failure)
            }
            if (decoded == null) return null
            currentCoroutineContext().ensureActive()
            return decoded
        } catch (failure: Throwable) {
            decoded?.recycleSafely()
            throw failure
        } finally {
            decoder.recycle()
        }
    }

    private fun resolvedRotationDegrees(source: File, persistedDegrees: Int): Int {
        if (persistedDegrees != 0) return persistedDegrees
        return try {
            exifOrientationToDegrees(
                ExifInterface(source).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                ),
            )
        } catch (_: IOException) {
            0
        } catch (_: RuntimeException) {
            0
        }
    }

    private fun rotate(source: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return source
        return try {
            Bitmap.createBitmap(
                source,
                0,
                0,
                source.width,
                source.height,
                Matrix().apply { postRotate(rotationDegrees.toFloat()) },
                false,
            )
        } catch (failure: IllegalArgumentException) {
            throw FileException(FileError.Corrupt, failure)
        } catch (failure: OutOfMemoryError) {
            throw FileException(FileError.TooLarge, failure)
        }
    }

    private fun crop(source: Bitmap, crop: ImageCrop?): Bitmap {
        if (crop == null || crop.isFullImage()) return source
        val cropRect = orientedCropRect(crop, source.width, source.height)
        return try {
            Bitmap.createBitmap(
                source,
                cropRect.left,
                cropRect.top,
                cropRect.width(),
                cropRect.height(),
            )
        } catch (failure: IllegalArgumentException) {
            throw FileException(FileError.Corrupt, failure)
        } catch (failure: OutOfMemoryError) {
            throw FileException(FileError.TooLarge, failure)
        }
    }

    private fun replace(previous: Bitmap, next: Bitmap): Bitmap {
        if (previous !== next) previous.recycleSafely()
        return next
    }

    /** Recorte normalizado en el marco ya orientado, con bordes inclusivo/exclusivo. */
    private fun orientedCropRect(crop: ImageCrop, widthPx: Int, heightPx: Int): Rect {
        val left = fractionToPx(crop.left, widthPx).coerceIn(0, widthPx - 1)
        val top = fractionToPx(crop.top, heightPx).coerceIn(0, heightPx - 1)
        val right = fractionToPxCeil(crop.right, widthPx).coerceIn(left + 1, widthPx)
        val bottom = fractionToPxCeil(crop.bottom, heightPx).coerceIn(top + 1, heightPx)
        return Rect(left, top, right, bottom)
    }

    /**
     * Transformación inversa del rectángulo mostrado (ya rotado en sentido horario) al marco
     * original. Las fórmulas trabajan con bordes, por lo que conservan el área exacta.
     */
    private fun sourceCropRect(
        crop: ImageCrop,
        sourceWidthPx: Int,
        sourceHeightPx: Int,
        rotationDegrees: Int,
    ): Rect {
        val orientedWidth = if (rotationDegrees in RIGHT_ANGLE_ROTATIONS) {
            sourceHeightPx
        } else {
            sourceWidthPx
        }
        val orientedHeight = if (rotationDegrees in RIGHT_ANGLE_ROTATIONS) {
            sourceWidthPx
        } else {
            sourceHeightPx
        }
        val oriented = orientedCropRect(crop, orientedWidth, orientedHeight)
        return when (rotationDegrees) {
            0 -> Rect(oriented)
            90 -> Rect(
                oriented.top,
                sourceHeightPx - oriented.right,
                oriented.bottom,
                sourceHeightPx - oriented.left,
            )
            180 -> Rect(
                sourceWidthPx - oriented.right,
                sourceHeightPx - oriented.bottom,
                sourceWidthPx - oriented.left,
                sourceHeightPx - oriented.top,
            )
            270 -> Rect(
                sourceWidthPx - oriented.bottom,
                oriented.left,
                sourceWidthPx - oriented.top,
                oriented.right,
            )
            else -> throw FileException(FileError.Corrupt)
        }
    }

    private fun fractionToPx(fraction: Int, dimensionPx: Int): Int =
        ((fraction.toLong() * dimensionPx) / ImageCrop.FRACTION_MAX).toInt()

    private fun fractionToPxCeil(fraction: Int, dimensionPx: Int): Int =
        ((fraction.toLong() * dimensionPx + ImageCrop.FRACTION_MAX - 1L) /
            ImageCrop.FRACTION_MAX).toInt()

    private fun ceilDiv(value: Int, divisor: Int): Int =
        ((value.toLong() + divisor - 1L) / divisor).toInt()

    private val RIGHT_ANGLE_ROTATIONS = setOf(90, 270)
}

internal fun Bitmap.recycleSafely() {
    if (!isRecycled) recycle()
}
