package com.facturastock.app.data.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.facturastock.app.core.coroutines.DefaultDispatcherProvider
import com.facturastock.app.domain.model.ImageQualityWarning
import com.facturastock.app.domain.model.InvoiceImage
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.tan
import kotlin.random.Random
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Cobertura de luz, desenfoque aproximado, giro y estimación de inclinación. */
@RunWith(AndroidJUnit4::class)
class LocalImageQualityAnalyzerTest {
    private lateinit var context: Context
    private lateinit var analyzer: LocalImageQualityAnalyzer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        analyzer = LocalImageQualityAnalyzer(context, DefaultDispatcherProvider())
    }

    @After
    fun tearDown() {
        File(context.filesDir, "draft_images/${DRAFT_ID.value}").deleteRecursively()
    }

    @Test
    fun darkAndBrightPagesProduceMeasuredNonBlockingWarnings() = runBlocking {
        val dark = writeSolidJpeg("dark.jpg", 1_200, 1_600, Color.rgb(25, 25, 25))
        val darkReport = analyzer.analyze(image(dark, 1_200, 1_600))
        assertTrue(darkReport.warnings.any { it is ImageQualityWarning.PossibleUnderexposure })
        assertTrue(darkReport.warnings.any { it is ImageQualityWarning.PossibleBlur })
        assertTrue(darkReport.meanLuminance < LocalImageQualityAnalyzer.MIN_MEAN_LUMINANCE)

        val bright = writeSolidJpeg("bright.jpg", 1_200, 1_600, Color.rgb(245, 245, 245))
        val brightReport = analyzer.analyze(image(bright, 1_200, 1_600))
        assertTrue(brightReport.warnings.any { it is ImageQualityWarning.PossibleOverexposure })
        assertTrue(brightReport.meanLuminance > LocalImageQualityAnalyzer.MAX_MEAN_LUMINANCE)
    }

    @Test
    fun registeredExifOrUserRotationGovernsEffectiveDimensions() = runBlocking {
        val source = writeRuledJpeg("rotated.jpg", 1_600, 1_000)

        val report = analyzer.analyze(
            image(source, width = 1_600, height = 1_000, rotationDegrees = 90),
        )

        assertEquals(1_000, report.effectiveWidthPx)
        assertEquals(1_600, report.effectiveHeightPx)
        assertFalse(report.warnings.any { it is ImageQualityWarning.LowResolution })
    }

    @Test
    fun wellExposedRuledInvoiceProducesNoWarnings() = runBlocking {
        val source = writeRuledJpeg("acceptable.jpg", 1_200, 1_600)

        val report = analyzer.analyze(image(source, 1_200, 1_600))

        assertTrue("advertencias inesperadas: ${report.warnings}", report.warnings.isEmpty())
    }

    @Test
    fun smallEffectiveDimensionsAreReportedAsARecommendation() = runBlocking {
        val source = writeRuledJpeg("small.jpg", 600, 800)

        val report = analyzer.analyze(image(source, 600, 800))

        val warning = report.warnings.filterIsInstance<ImageQualityWarning.LowResolution>()
            .single()
        assertEquals(600, warning.widthPx)
        assertEquals(800, warning.heightPx)
        assertEquals(LocalImageQualityAnalyzer.MIN_SHORT_SIDE_PX, warning.minimumShortSidePx)
    }

    @Test
    fun contentTouchingTheFrameProducesPossibleIncompleteCropWarning() = runBlocking {
        val source = sourceFile("border.jpg")
        val bitmap = Bitmap.createBitmap(1_200, 1_600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply {
            color = Color.BLACK
            strokeWidth = 30f
            style = Paint.Style.STROKE
        }
        canvas.drawRect(0f, 0f, 1_199f, 1_599f, paint)
        source.outputStream().buffered().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 96, output))
        }
        bitmap.recycle()

        val report = analyzer.analyze(image(source, 1_200, 1_600))

        assertTrue(
            report.warnings.any { it is ImageQualityWarning.PossibleIncompleteCrop },
        )
    }

    @Test
    fun slantedTextLinesProduceApproximateSkewWarning() = runBlocking {
        val source = sourceFile("skew.jpg")
        val bitmap = Bitmap.createBitmap(1_200, 1_600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply {
            color = Color.BLACK
            strokeWidth = 9f
            isAntiAlias = false
        }
        repeat(18) { index ->
            val startY = 180f + index * 70f
            canvas.drawLine(100f, startY, 1_100f, startY + 105f, paint)
        }
        source.outputStream().buffered().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 96, output))
        }
        bitmap.recycle()

        val report = analyzer.analyze(image(source, 1_200, 1_600))

        val warning = report.warnings.filterIsInstance<ImageQualityWarning.PossibleSkew>()
            .single()
        assertTrue(kotlin.math.abs(warning.estimatedDegreesTenths) in 40..80)
        assertTrue(warning.confidencePermille >= LocalImageQualityAnalyzer.MIN_SKEW_CONFIDENCE_PERMILLE)
    }

    @Test
    fun blockLuminanceMatchesPreviousRowsExactlyWithAlphaAndPartialBlocks() = runBlocking {
        for (height in listOf(1, 15, 16, 17, 33)) {
            val width = if (height == 33) 768 else 37
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val random = Random(height)
            val pixels = IntArray(width * height) { random.nextInt() }
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            try {
                assertArrayEquals(
                    "height=$height",
                    previousLuminance(bitmap),
                    analyzer.readLuminance(bitmap),
                )
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test
    fun precomputedInkMatchesPreviousSkewExactlyAcrossSparseDenseAndBoundaryInputs() = runBlocking {
        for ((width, height) in listOf(95 to 160, 96 to 96, 97 to 129, 400 to 600, 767 to 768)) {
            for (fixture in 0..5) {
                val gray = qualityFixture(width, height, fixture)
                val mean = meanLuminance(gray)
                assertEquals(
                    "${width}x$height fixture=$fixture",
                    previousSkew(gray, width, height, mean),
                    analyzer.estimateSkew(gray, width, height, mean),
                )
            }
        }
    }

    @Test
    fun canceledQualityKernelsDoNotReturnPartialResults() = runBlocking {
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        try {
            val luminance = launch(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel()
                analyzer.readLuminance(bitmap)
                fail("El cálculo de luminancia debe respetar la cancelación")
            }
            val skew = launch(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel()
                analyzer.estimateSkew(ByteArray(96 * 96), 96, 96, 200)
                fail("El cálculo de inclinación debe respetar la cancelación")
            }
            luminance.join()
            skew.join()
            assertTrue(luminance.isCancelled)
            assertTrue(skew.isCancelled)
        } finally {
            bitmap.recycle()
        }
    }

    /** Diagnóstico del kernel; no es un presupuesto ni una medición de fluidez de la app. */
    @Test
    fun recordSkewKernelDiagnosticWithTheSameSyntheticInvoice() = runBlocking {
        val width = 768
        val height = 768
        for ((fixture, label) in listOf(4 to "sparse", 5 to "dense", 1 to "uniformDark")) {
            val gray = qualityFixture(width, height, fixture)
            val mean = meanLuminance(gray)
            val expected = previousSkew(gray, width, height, mean)
            repeat(3) {
                previousSkew(gray, width, height, mean)
                analyzer.estimateSkew(gray, width, height, mean)
            }
            val before = LongArray(9)
            val after = LongArray(9)
            repeat(9) { index ->
                fun measurePrevious() {
                    val start = System.nanoTime()
                    val result = previousSkew(gray, width, height, mean)
                    before[index] = System.nanoTime() - start
                    assertEquals(expected, result)
                }
                suspend fun measureCurrent() {
                    val start = System.nanoTime()
                    val result = analyzer.estimateSkew(gray, width, height, mean)
                    after[index] = System.nanoTime() - start
                    assertEquals(expected, result)
                }
                if (index % 2 == 0) {
                    measurePrevious()
                    measureCurrent()
                } else {
                    measureCurrent()
                    measurePrevious()
                }
            }
            val threshold = minOf(180, mean - 25)
            var sampledPoints = 0
            var inkPoints = 0
            for (y in height / 20 until height - height / 20 step 2) {
                for (x in width / 20 until width - width / 20 step 2) {
                    sampledPoints++
                    if ((gray[y * width + x].toInt() and 255) <= threshold) inkPoints++
                }
            }
            val pointScratchBytes = if (threshold > 0) sampledPoints * Int.SIZE_BYTES else 0
            Log.i(
                "ImageKernelDiagnostic",
                "skew768 synthetic $label; sampledPoints=$sampledPoints; inkPoints=$inkPoints; " +
                    "pointScratchBytes=$pointScratchBytes; " +
                    "previousNs=${before.joinToString()}; currentNs=${after.joinToString()}; " +
                    "previousMedianNs=${before.sorted()[4]}; currentMedianNs=${after.sorted()[4]}",
            )
        }
    }

    private fun previousLuminance(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val gray = ByteArray(width * bitmap.height)
        val row = IntArray(width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            for (x in row.indices) {
                val color = row[x]
                val alpha = color ushr 24 and 255
                fun composite(channel: Int): Int = (channel * alpha + 255 * (255 - alpha) + 127) / 255
                val red = composite(color ushr 16 and 255)
                val green = composite(color ushr 8 and 255)
                val blue = composite(color and 255)
                gray[y * width + x] = ((red * 77 + green * 150 + blue * 29) ushr 8).toByte()
            }
        }
        return gray
    }

    private fun qualityFixture(width: Int, height: Int, fixture: Int): ByteArray {
        val random = Random(width * height + fixture)
        return ByteArray(width * height) { index ->
            val x = index % width
            val y = index / width
            when (fixture) {
                0 -> 255
                1 -> 25
                2 -> random.nextInt(256)
                3 -> if ((x + y) % 2 == 0) 0 else 255
                4 -> if (x in 70 until width - 70 && (y - x / 10) % 45 in 0..2) 10 else 245
                else -> if (x < width / 4 || y % 9 < 4) 45 else 210
            }.toByte()
        }
    }

    private fun meanLuminance(gray: ByteArray): Int =
        (gray.sumOf { (it.toInt() and 255).toLong() } / gray.size).toInt()

    /** Referencia anterior con 21 barridos: conserva orden, umbrales y redondeo. */
    private fun previousSkew(
        gray: ByteArray,
        width: Int,
        height: Int,
        meanLuminance: Int,
    ): LocalImageQualityAnalyzer.SkewEstimate {
        if (width < 96 || height < 96) return LocalImageQualityAnalyzer.SkewEstimate()
        val threshold = minOf(180, meanLuminance - 25)
        if (threshold <= 0) return LocalImageQualityAnalyzer.SkewEstimate()
        val marginX = width / 20
        val marginY = height / 20
        val centerX = width / 2
        var bestAngle = 0
        var bestScore = Long.MIN_VALUE
        var zeroScore = 0L
        for (angle in -10..10) {
            val tangent = tan(angle * PI / 180.0)
            val histogram = IntArray(height + 320)
            for (y in marginY until height - marginY step 2) {
                for (x in marginX until width - marginX step 2) {
                    if ((gray[y * width + x].toInt() and 255) <= threshold) {
                        val projected = (y - tangent * (x - centerX)).roundToInt() + 160
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
        if (bestScore <= 0 || bestAngle == 0) return LocalImageQualityAnalyzer.SkewEstimate()
        val improvement = ((bestScore - zeroScore).coerceAtLeast(0) * 1_000 / bestScore).toInt()
        if (improvement < 30) return LocalImageQualityAnalyzer.SkewEstimate()
        return LocalImageQualityAnalyzer.SkewEstimate(bestAngle * 10, improvement)
    }

    private fun writeSolidJpeg(name: String, width: Int, height: Int, color: Int): File {
        val file = sourceFile(name)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        file.outputStream().buffered().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
        }
        bitmap.recycle()
        return file
    }

    private fun writeRuledJpeg(name: String, width: Int, height: Int): File {
        val file = sourceFile(name)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply { color = Color.BLACK; strokeWidth = 5f }
        repeat(12) { index ->
            val y = 100f + index * 65f
            canvas.drawLine(100f, y, width - 100f, y, paint)
        }
        file.outputStream().buffered().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
        }
        bitmap.recycle()
        return file
    }

    private fun sourceFile(name: String): File {
        val directory = File(context.filesDir, "draft_images/${DRAFT_ID.value}")
        check(directory.mkdirs() || directory.isDirectory)
        return File(directory, name)
    }

    private fun image(
        source: File,
        width: Int,
        height: Int,
        rotationDegrees: Int = 0,
    ) = InvoiceImage(
        imageId = IMAGE_ID,
        draftId = DRAFT_ID,
        businessId = BUSINESS_ID,
        pageIndex = 0,
        filePath = source.relativeTo(context.filesDir).invariantSeparatorsPath,
        sha256 = "a".repeat(64),
        mimeType = "image/jpeg",
        widthPx = width,
        heightPx = height,
        fileSizeBytes = source.length(),
        rotationDegrees = rotationDegrees,
        createdAt = Instant.EPOCH,
    )

    private companion object {
        val BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("00000000-0000-0000-0000-0000000000b1"),
        )
        val DRAFT_ID: DraftId = DraftId.from(
            UUID.fromString("00000000-0000-4000-8000-00000000dd02"),
        )
        val IMAGE_ID: ImageId = ImageId.from(
            UUID.fromString("00000000-0000-4000-8000-000000001a02"),
        )
    }
}
