package com.facturastock.app.data.ocr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.facturastock.app.core.coroutines.DefaultDispatcherProvider
import com.facturastock.app.data.demo.DemoInvoiceFixture
import com.facturastock.app.data.demo.DemoInvoiceImageGenerator
import com.facturastock.app.domain.model.InvoiceTextBoundingBox
import com.facturastock.app.domain.model.InvoiceTextGeometry
import com.facturastock.app.domain.model.InvoiceTextPage
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.repository.OcrImageFile
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Smoke test físico: el modelo latino integrado reconoce una factura sin permiso de red. */
@RunWith(AndroidJUnit4::class)
class MlKitInvoiceTextRecognizerTest {
    private lateinit var context: Context
    private lateinit var testDirectory: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        testDirectory = File(context.filesDir, "draft_images/ocr-smoke").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        testDirectory.deleteRecursively()
    }

    @Test
    fun bundledLatinModelRecognizesSyntheticInvoiceWithoutNetworkPermission() = runBlocking {
        assertEquals(
            PackageManager.PERMISSION_DENIED,
            context.packageManager.checkPermission(Manifest.permission.INTERNET, context.packageName),
        )
        val firstImage = writeSyntheticInvoice("synthetic-invoice-1.jpg", "FACTURA 001-123")
        val secondImage = writeSyntheticInvoice("synthetic-invoice-2.jpg", "FACTURA PAGINA 2")
        val pages = listOf(
            firstImage.toOcrPage(FIRST_IMAGE_ID),
            secondImage.toOcrPage(SECOND_IMAGE_ID),
        )
        val recognizer = MlKitInvoiceTextRecognizer(context, DefaultDispatcherProvider())

        val result = withTimeout(30_000) { recognizer.recognize(pages) }

        assertEquals(2, result.pages.size)
        assertEquals(listOf(FIRST_IMAGE_ID, SECOND_IMAGE_ID), result.pages.map { it.sourceImageId })
        assertEquals(listOf(0, 1), result.pages.map { it.pageIndex })
        assertTrue(result.text.contains("FACTURA", ignoreCase = true))
        assertTrue(result.text.contains("TOTAL", ignoreCase = true))
        assertTrue(result.pages.all { it.blocks.isNotEmpty() })
        result.pages.forEach(::assertGeometryInsidePage)
    }

    @Test
    fun bundledLatinModelCanReadTheExactOnePageDemoWhenDeviceResourcesAllowIt() = runBlocking {
        val file = File(testDirectory, "demo-invoice-35.jpg").apply {
            writeBytes(DemoInvoiceImageGenerator.jpegBytes())
        }
        val page = OcrImageFile(
            sourceImageId = FIRST_IMAGE_ID,
            relativePath = file.relativeTo(context.filesDir).invariantSeparatorsPath,
            mimeType = "image/jpeg",
            widthPx = DemoInvoiceFixture.BASE_PAGE_WIDTH_PX,
            heightPx = DemoInvoiceFixture.BASE_PAGE_HEIGHT_PX,
            fileSizeBytes = file.length(),
        )

        val result = withTimeout(45_000) {
            MlKitInvoiceTextRecognizer(context, DefaultDispatcherProvider()).recognize(listOf(page))
        }

        assertEquals(1, result.pages.size)
        assertTrue(result.pages.single().blocks.isNotEmpty())
        assertTrue(
            result.text.contains("FACTURA", ignoreCase = true) ||
                result.text.contains("TOTAL", ignoreCase = true),
        )
    }

    private fun File.toOcrPage(imageId: ImageId) = OcrImageFile(
        sourceImageId = imageId,
        relativePath = relativeTo(context.filesDir).invariantSeparatorsPath,
        mimeType = "image/jpeg",
        widthPx = WIDTH,
        heightPx = HEIGHT,
        fileSizeBytes = length(),
    )

    private fun assertGeometryInsidePage(page: InvoiceTextPage) {
        page.blocks.forEach { block ->
            assertGeometryInside(block.geometry, page.widthPx, page.heightPx)
            assertTrue(block.lines.isNotEmpty())
            block.lines.forEach { line ->
                assertGeometryInside(line.geometry, page.widthPx, page.heightPx)
                line.confidencePermille?.let { assertTrue(it in 0..1_000) }
                line.clockwiseAngleTenths?.let { assertTrue(it in -1_800..1_800) }
                assertTrue(line.elements.isNotEmpty())
                line.elements.forEach { element ->
                    assertGeometryInside(element.geometry, page.widthPx, page.heightPx)
                }
            }
        }
    }

    private fun assertGeometryInside(geometry: InvoiceTextGeometry, widthPx: Int, heightPx: Int) {
        assertBoxInside(geometry.boundingBox, widthPx, heightPx)
        assertFalse(geometry.cornerPoints.any { point ->
            point.xPx !in 0 until widthPx || point.yPx !in 0 until heightPx
        })
    }

    private fun assertBoxInside(
        box: InvoiceTextBoundingBox?,
        widthPx: Int,
        heightPx: Int,
    ) {
        box?.let {
            assertTrue(it.leftPx >= 0 && it.rightPx <= widthPx)
            assertTrue(it.topPx >= 0 && it.bottomPx <= heightPx)
        }
    }

    private fun writeSyntheticInvoice(name: String, heading: String): File {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 110f
                typeface = Typeface.DEFAULT_BOLD
            }
            val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 76f
                typeface = Typeface.MONOSPACE
            }
            canvas.drawText(heading, 80f, 180f, title)
            canvas.drawText("RUC 20123456789", 80f, 340f, body)
            canvas.drawText("ARROZ 2 x 10.00", 80f, 520f, body)
            canvas.drawText("TOTAL 20.00 PEN", 80f, 720f, title)
            return File(testDirectory, name).also { file ->
                file.outputStream().buffered().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 96, output))
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val WIDTH = 1_600
        const val HEIGHT = 1_000
        val FIRST_IMAGE_ID: ImageId = ImageId.from(
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
        )
        val SECOND_IMAGE_ID: ImageId = ImageId.from(
            UUID.fromString("22222222-2222-2222-2222-222222222222"),
        )
    }
}
