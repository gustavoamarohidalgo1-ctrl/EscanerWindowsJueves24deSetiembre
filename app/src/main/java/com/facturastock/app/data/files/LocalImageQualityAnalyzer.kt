package com.facturastock.app.data.files

import android.content.Context
import android.graphics.Bitmap
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.domain.model.ImageQualityReport
import com.facturastock.app.domain.model.ImageQualityWarning
import com.facturastock.app.domain.model.InvoiceImage
import com.facturastock.app.domain.repository.ImageQualityAnalyzer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.tan
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Analizador local y acotado: trabaja sobre una muestra de hasta 768 px y calcula señales
 * aproximadas de luz, nitidez, inclinación y contenido tocando el borde. Los umbrales son
 * conservadores y explicables; el resultado nunca decide por el usuario.
 */
@Singleton
class LocalImageQualityAnalyzer @Inject constructor(
    @ApplicationContext context: Context,
    private val dispatcherProvider: DispatcherProvider,
) : ImageQualityAnalyzer {
    private val rootDirectory: File = context.filesDir

    override suspend fun analyze(image: InvoiceImage): ImageQualityReport =
        withContext(dispatcherProvider.default) {
            currentCoroutineContext().ensureActive()
            val source = InvoiceBitmapTransforms.resolveSource(rootDirectory, image)
            val loaded = InvoiceBitmapTransforms.loadTransformed(
                source = source,
                image = image,
                maximumSidePx = ANALYSIS_MAX_SIDE_PX,
            )
            try {
                analyzeBitmap(image, loaded)
            } finally {
                loaded.bitmap.recycleSafely()
            }
        }

    private suspend fun analyzeBitmap(
        image: InvoiceImage,
        loaded: InvoiceBitmapTransforms.Loaded,
    ): ImageQualityReport {
        val bitmap = loaded.bitmap
        val gray = readLuminance(bitmap)
        val pixelCount = gray.size.coerceAtLeast(1)
        var luminanceSum = 0L
        var darkPixels = 0
        var brightPixels = 0
        gray.forEachIndexed { index, value ->
            if (index % CANCELLATION_PIXEL_INTERVAL == 0) {
                currentCoroutineContext().ensureActive()
            }
            val luminance = value.toInt() and 0xFF
            luminanceSum += luminance
            if (luminance <= DARK_LUMINANCE) darkPixels++
            if (luminance >= BRIGHT_LUMINANCE) brightPixels++
        }
        val meanLuminance = (luminanceSum / pixelCount).toInt()
        val darkPermille = ratioPermille(darkPixels, pixelCount)
        val brightPermille = ratioPermille(brightPixels, pixelCount)
        val sharpness = laplacianEnergy(gray, bitmap.width, bitmap.height)
        val skew = estimateSkew(gray, bitmap.width, bitmap.height, meanLuminance)
        val borderContent = borderContentPermille(
            gray = gray,
            width = bitmap.width,
            height = bitmap.height,
            meanLuminance = meanLuminance,
        )
        val (effectiveWidth, effectiveHeight) =
            InvoiceBitmapTransforms.effectiveDimensions(loaded, image.crop)

        val warnings = buildList {
            val shortSide = minOf(effectiveWidth, effectiveHeight)
            val longSide = maxOf(effectiveWidth, effectiveHeight)
            if (shortSide < MIN_SHORT_SIDE_PX || longSide < MIN_LONG_SIDE_PX) {
                add(
                    ImageQualityWarning.LowResolution(
                        widthPx = effectiveWidth,
                        heightPx = effectiveHeight,
                        minimumShortSidePx = MIN_SHORT_SIDE_PX,
                        minimumLongSidePx = MIN_LONG_SIDE_PX,
                    ),
                )
            }
            if (sharpness < MIN_SHARPNESS_SCORE) {
                add(ImageQualityWarning.PossibleBlur(sharpness, MIN_SHARPNESS_SCORE))
            }
            if (meanLuminance < MIN_MEAN_LUMINANCE || darkPermille > MAX_DARK_PERMILLE) {
                add(ImageQualityWarning.PossibleUnderexposure(meanLuminance, darkPermille))
            }
            // El papel blanco bien expuesto suele tener muchos píxeles claros. Solo avisamos
            // si además casi no quedan bordes nítidos o la saturación es prácticamente total.
            if ((meanLuminance >= MAX_MEAN_LUMINANCE && sharpness < MIN_SHARPNESS_SCORE) ||
                brightPermille >= MAX_BRIGHT_PERMILLE
            ) {
                add(ImageQualityWarning.PossibleOverexposure(meanLuminance, brightPermille))
            }
            if (abs(skew.degreesTenths) >= MIN_SKEW_TENTHS &&
                skew.confidencePermille >= MIN_SKEW_CONFIDENCE_PERMILLE
            ) {
                add(
                    ImageQualityWarning.PossibleSkew(
                        estimatedDegreesTenths = skew.degreesTenths,
                        confidencePermille = skew.confidencePermille,
                    ),
                )
            }
            if (borderContent >= BORDER_CONTENT_WARNING_PERMILLE) {
                add(
                    ImageQualityWarning.PossibleIncompleteCrop(
                        borderContentPermille = borderContent,
                        warningThresholdPermille = BORDER_CONTENT_WARNING_PERMILLE,
                    ),
                )
            }
        }

        return ImageQualityReport(
            imageId = image.imageId,
            effectiveWidthPx = effectiveWidth,
            effectiveHeightPx = effectiveHeight,
            meanLuminance = meanLuminance,
            darkPixelsPermille = darkPermille,
            brightPixelsPermille = brightPermille,
            sharpnessScore = sharpness,
            estimatedSkewTenths = skew.degreesTenths,
            borderContentPermille = borderContent,
            warnings = warnings,
        )
    }

    private suspend fun readLuminance(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val gray = ByteArray(width * height)
        val row = IntArray(width)
        for (y in 0 until height) {
            currentCoroutineContext().ensureActive()
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            val offset = y * width
            for (x in 0 until width) {
                val color = row[x]
                val alpha = color ushr 24 and 0xFF
                val red = compositeOnWhite(color ushr 16 and 0xFF, alpha)
                val green = compositeOnWhite(color ushr 8 and 0xFF, alpha)
                val blue = compositeOnWhite(color and 0xFF, alpha)
                gray[offset + x] = ((red * 77 + green * 150 + blue * 29) ushr 8).toByte()
            }
        }
        return gray
    }

    /** Energía media de Laplaciano: baja cuando apenas existen transiciones nítidas. */
    private suspend fun laplacianEnergy(gray: ByteArray, width: Int, height: Int): Int {
        if (width < 3 || height < 3) return 0
        var energy = 0L
        var samples = 0L
        for (y in 1 until height - 1 step LAPLACIAN_STEP) {
            currentCoroutineContext().ensureActive()
            for (x in 1 until width - 1 step LAPLACIAN_STEP) {
                val center = grayAt(gray, width, x, y)
                val laplacian = center * 4 -
                    grayAt(gray, width, x - 1, y) -
                    grayAt(gray, width, x + 1, y) -
                    grayAt(gray, width, x, y - 1) -
                    grayAt(gray, width, x, y + 1)
                energy += laplacian.toLong() * laplacian
                samples++
            }
        }
        return if (samples == 0L) 0 else (energy / samples).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * Busca el ángulo (-10°..10°) que más concentra píxeles oscuros en renglones. Solo se
     * informa si mejora de forma mensurable respecto de 0°, de modo que el ruido no invente
     * una inclinación con falsa precisión.
     */
    private suspend fun estimateSkew(
        gray: ByteArray,
        width: Int,
        height: Int,
        meanLuminance: Int,
    ): SkewEstimate {
        if (width < MIN_SKEW_DIMENSION || height < MIN_SKEW_DIMENSION) return SkewEstimate()
        val inkThreshold = minOf(MAX_INK_LUMINANCE, meanLuminance - INK_DISTANCE_FROM_MEAN)
        if (inkThreshold <= 0) return SkewEstimate()
        val marginX = width / 20
        val marginY = height / 20
        val centerX = width / 2
        var bestAngle = 0
        var bestScore = Long.MIN_VALUE
        var zeroScore = 0L
        for (angle in -MAX_SKEW_DEGREES..MAX_SKEW_DEGREES) {
            currentCoroutineContext().ensureActive()
            val tangent = tan(angle * PI / 180.0)
            val histogram = IntArray(height + SKEW_HISTOGRAM_PADDING * 2)
            for (y in marginY until height - marginY step SKEW_PIXEL_STEP) {
                for (x in marginX until width - marginX step SKEW_PIXEL_STEP) {
                    if (grayAt(gray, width, x, y) <= inkThreshold) {
                        val projected =
                            (y - tangent * (x - centerX)).roundToInt() + SKEW_HISTOGRAM_PADDING
                        if (projected in histogram.indices) histogram[projected]++
                    }
                }
            }
            var score = 0L
            histogram.forEach { count -> score += count.toLong() * count }
            if (angle == 0) zeroScore = score
            if (score > bestScore) {
                bestScore = score
                bestAngle = angle
            }
        }
        if (bestScore <= 0L || bestAngle == 0) return SkewEstimate()
        val improvement = ((bestScore - zeroScore).coerceAtLeast(0L) * 1_000L / bestScore).toInt()
        if (improvement < MIN_SKEW_CONFIDENCE_PERMILLE) return SkewEstimate()
        return SkewEstimate(degreesTenths = bestAngle * 10, confidencePermille = improvement)
    }

    private suspend fun borderContentPermille(
        gray: ByteArray,
        width: Int,
        height: Int,
        meanLuminance: Int,
    ): Int {
        val inkThreshold = minOf(MAX_INK_LUMINANCE, meanLuminance - INK_DISTANCE_FROM_MEAN)
        if (inkThreshold <= 0) return 0
        val band = maxOf(MIN_BORDER_BAND_PX, minOf(width, height) / BORDER_BAND_DIVISOR)
        var ink = 0
        var borderInk = 0
        for (y in gray.indices step width) {
            currentCoroutineContext().ensureActive()
            val row = y / width
            for (x in 0 until width) {
                if ((gray[y + x].toInt() and 0xFF) <= inkThreshold) {
                    ink++
                    if (x < band || x >= width - band || row < band || row >= height - band) {
                        borderInk++
                    }
                }
            }
        }
        return if (ink < MIN_INK_PIXELS) 0 else ratioPermille(borderInk, ink)
    }

    private fun grayAt(gray: ByteArray, width: Int, x: Int, y: Int): Int =
        gray[y * width + x].toInt() and 0xFF

    private fun ratioPermille(part: Int, total: Int): Int =
        if (total <= 0) 0 else ((part.toLong() * 1_000L) / total).toInt().coerceIn(0, 1_000)

    private fun compositeOnWhite(channel: Int, alpha: Int): Int =
        (channel * alpha + 255 * (255 - alpha) + 127) / 255

    private data class SkewEstimate(
        val degreesTenths: Int = 0,
        val confidencePermille: Int = 0,
    )

    internal companion object {
        const val ANALYSIS_MAX_SIDE_PX = 768
        const val MIN_SHORT_SIDE_PX = 900
        const val MIN_LONG_SIDE_PX = 1_200
        const val MIN_SHARPNESS_SCORE = 180
        const val MIN_MEAN_LUMINANCE = 65
        const val MAX_MEAN_LUMINANCE = 240
        const val MAX_DARK_PERMILLE = 550
        const val MAX_BRIGHT_PERMILLE = 990
        const val BORDER_CONTENT_WARNING_PERMILLE = 120
        const val MIN_SKEW_TENTHS = 20
        const val MIN_SKEW_CONFIDENCE_PERMILLE = 30

        private const val DARK_LUMINANCE = 48
        private const val BRIGHT_LUMINANCE = 235
        private const val MAX_INK_LUMINANCE = 180
        private const val INK_DISTANCE_FROM_MEAN = 25
        private const val CANCELLATION_PIXEL_INTERVAL = 16_384
        private const val LAPLACIAN_STEP = 2
        private const val MIN_SKEW_DIMENSION = 96
        private const val MAX_SKEW_DEGREES = 10
        private const val SKEW_PIXEL_STEP = 2
        private const val SKEW_HISTOGRAM_PADDING = 160
        private const val MIN_BORDER_BAND_PX = 3
        private const val BORDER_BAND_DIVISOR = 32
        private const val MIN_INK_PIXELS = 200
    }
}
