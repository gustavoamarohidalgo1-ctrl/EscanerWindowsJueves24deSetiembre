package com.facturastock.app.data.files

import com.facturastock.app.domain.error.FileError
import com.facturastock.app.domain.error.FileException
import com.facturastock.app.domain.model.ImageCrop
import com.facturastock.app.domain.model.InvoiceImage
import java.awt.image.BufferedImage
import java.awt.image.RasterFormatException
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Transformaciones compartidas por análisis y preprocesamiento, siempre sobre una muestra.
 * En escritorio el "bitmap" es un [BufferedImage] ARGB decodificado por [DesktopImageCodec].
 */
internal object InvoiceBitmapTransforms {
    data class Loaded(
        val bitmap: BufferedImage,
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

    private fun decodeBounds(source: File): DecodedImageBounds {
        val bounds = DesktopImageCodec.decodeBounds(source)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw FileException(FileError.Corrupt)
        }
        return bounds
    }

    private fun decodeSampled(
        source: File,
        bounds: DecodedImageBounds,
        maximumSidePx: Int,
    ): BufferedImage {
        val sampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maximumSidePx)
        return try {
            DesktopImageCodec.decodeFile(source, sampleSize)
                ?: throw FileException(FileError.Corrupt)
        } catch (failure: OutOfMemoryError) {
            // La muestra está acotada; si el equipo aun así no tiene margen, el fallo se
            // vuelve recuperable en lugar de derribar el proceso.
            throw FileException(FileError.TooLarge, failure)
        }
    }

    /**
     * Intenta decodificar únicamente [sourceCrop] (`ImageReadParam.sourceRegion`). `null`
     * solicita el fallback seguro de página completa; cancelación y OOM siempre se propagan.
     */
    private suspend fun decodeRegionSampledOrNull(
        source: File,
        sourceCrop: PixelRect,
        maximumSidePx: Int,
    ): BufferedImage? {
        currentCoroutineContext().ensureActive()
        val sampleSize = sampleSizeFor(sourceCrop.width(), sourceCrop.height(), maximumSidePx)
        val decoded = try {
            DesktopImageCodec.decodeFile(source, sampleSize, sourceCrop.toAwtRectangle())
        } catch (failure: OutOfMemoryError) {
            throw FileException(FileError.TooLarge, failure)
        } ?: return null
        try {
            currentCoroutineContext().ensureActive()
            return decoded
        } catch (failure: Throwable) {
            decoded.recycleSafely()
            throw failure
        }
    }

    private fun resolvedRotationDegrees(source: File, persistedDegrees: Int): Int {
        if (persistedDegrees != 0) return persistedDegrees
        return try {
            exifOrientationToDegrees(ExifOrientationReader.readOrientation(source))
        } catch (_: IOException) {
            0
        } catch (_: RuntimeException) {
            0
        }
    }

    private fun rotate(source: BufferedImage, rotationDegrees: Int): BufferedImage {
        if (rotationDegrees == 0) return source
        return try {
            DesktopImageCodec.rotate(source, rotationDegrees, filter = false)
        } catch (failure: IllegalArgumentException) {
            throw FileException(FileError.Corrupt, failure)
        } catch (failure: OutOfMemoryError) {
            throw FileException(FileError.TooLarge, failure)
        }
    }

    private fun crop(source: BufferedImage, crop: ImageCrop?): BufferedImage {
        if (crop == null || crop.isFullImage()) return source
        val cropRect = orientedCropRect(crop, source.width, source.height)
        return try {
            DesktopImageCodec.crop(
                source,
                cropRect.left,
                cropRect.top,
                cropRect.width(),
                cropRect.height(),
            )
        } catch (failure: IllegalArgumentException) {
            throw FileException(FileError.Corrupt, failure)
        } catch (failure: RasterFormatException) {
            throw FileException(FileError.Corrupt, failure)
        } catch (failure: OutOfMemoryError) {
            throw FileException(FileError.TooLarge, failure)
        }
    }

    private fun replace(previous: BufferedImage, next: BufferedImage): BufferedImage {
        if (previous !== next) previous.recycleSafely()
        return next
    }

    /** Recorte normalizado en el marco ya orientado, con bordes inclusivo/exclusivo. */
    private fun orientedCropRect(crop: ImageCrop, widthPx: Int, heightPx: Int): PixelRect {
        val left = fractionToPx(crop.left, widthPx).coerceIn(0, widthPx - 1)
        val top = fractionToPx(crop.top, heightPx).coerceIn(0, heightPx - 1)
        val right = fractionToPxCeil(crop.right, widthPx).coerceIn(left + 1, widthPx)
        val bottom = fractionToPxCeil(crop.bottom, heightPx).coerceIn(top + 1, heightPx)
        return PixelRect(left, top, right, bottom)
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
    ): PixelRect {
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
            0 -> PixelRect(oriented)
            90 -> PixelRect(
                oriented.top,
                sourceHeightPx - oriented.right,
                oriented.bottom,
                sourceHeightPx - oriented.left,
            )
            180 -> PixelRect(
                sourceWidthPx - oriented.right,
                sourceHeightPx - oriented.bottom,
                sourceWidthPx - oriented.left,
                sourceHeightPx - oriented.top,
            )
            270 -> PixelRect(
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

/**
 * Rectángulo entero con bordes inclusivo/exclusivo, equivalente a `android.graphics.Rect`
 * en lo que necesitan estas transformaciones.
 */
internal data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    constructor(other: PixelRect) : this(other.left, other.top, other.right, other.bottom)

    fun width(): Int = right - left

    fun height(): Int = bottom - top

    fun toAwtRectangle(): java.awt.Rectangle = java.awt.Rectangle(left, top, width(), height())
}

/**
 * Equivalente de `Bitmap.recycle()`: un [BufferedImage] lo libera el GC, pero `flush` suelta
 * de inmediato cualquier copia acelerada que Java2D haya creado para la imagen.
 */
internal fun BufferedImage.recycleSafely() {
    flush()
}
