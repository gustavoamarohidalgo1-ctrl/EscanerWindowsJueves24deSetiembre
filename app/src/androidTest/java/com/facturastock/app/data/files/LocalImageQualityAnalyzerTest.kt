package com.facturastock.app.data.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
