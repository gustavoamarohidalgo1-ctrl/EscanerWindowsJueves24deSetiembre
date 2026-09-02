package com.facturastock.app.data.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.coroutines.DefaultDispatcherProvider
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.domain.error.FileException
import com.facturastock.app.domain.model.ImageCrop
import com.facturastock.app.domain.model.InvoiceImage
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Pruebas físicas de orientación, recorte, escala, memoria y conservación del original. */
@RunWith(AndroidJUnit4::class)
class LocalInvoiceImagePreprocessorTest {
    private lateinit var context: Context
    private lateinit var preprocessor: LocalInvoiceImagePreprocessor
    private var runSequence: Long = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        preprocessor = LocalInvoiceImagePreprocessor(
            context = context,
            uuidGenerator = UuidGenerator { UUID(0L, ++runSequence) },
            dispatcherProvider = DefaultDispatcherProvider(),
        )
    }

    @After
    fun tearDown() {
        NoFollowFileTree.delete(File(context.filesDir, "draft_images/${DRAFT_ID.value}"))
        NoFollowFileTree.delete(File(context.filesDir, "ocr-sentinel"))
        NoFollowFileTree.delete(File(context.filesDir, "ocr-manifest-sentinel.txt"))
    }

    @Test
    fun preprocessRotatesAndWritesGrayscaleCopyWithoutTouchingOriginal() = runBlocking {
        val original = writeSplitJpeg("rotation.jpg", width = 160, height = 80)
        val originalBytes = original.readBytes()

        val prepared = prepare(
            image(relativePathOf(original), width = 160, height = 80, rotationDegrees = 90),
        )

        assertNotEquals(relativePathOf(original), prepared.relativePath)
        assertTrue(prepared.relativePath.startsWith("draft_images/${DRAFT_ID.value}/ocr/"))
        assertEquals(80, prepared.widthPx)
        assertEquals(160, prepared.heightPx)
        val output = File(context.filesDir, prepared.relativePath)
        assertTrue(output.isFile)

        val decoded = BitmapFactory.decodeFile(output.absolutePath)
        val color = decoded.getPixel(decoded.width / 2, decoded.height / 2)
        val channels = listOf(color ushr 16 and 0xFF, color ushr 8 and 0xFF, color and 0xFF)
        assertTrue(channels.max() - channels.min() <= 2)
        // El lado izquierdo negro pasa arriba con el giro horario de 90°; esto detecta tanto
        // el sentido equivocado como una prueba de giro basada solo en dimensiones.
        assertTrue(luminance(decoded.getPixel(decoded.width / 2, 10)) < 30)
        assertTrue(luminance(decoded.getPixel(decoded.width / 2, decoded.height - 10)) > 225)
        decoded.recycle()

        assertTrue(original.readBytes().contentEquals(originalBytes))
        assertEquals(160, decodeBounds(original).outWidth)
    }

    @Test
    fun preprocessAppliesCropAndProportionalDownsampling() = runBlocking {
        val original = writeJpeg("scale.jpg", width = 3_000, height = 1_500, color = 0xFFE8E0D0.toInt())
        val prepared = prepare(
            image(
                filePath = relativePathOf(original),
                width = 3_000,
                height = 1_500,
                crop = ImageCrop(0, 0, 5_000, 5_000),
            ),
        )

        // Se decodifica primero la región original; el recorte 1500×750 ya cabe completo.
        assertEquals(1_500, prepared.widthPx)
        assertEquals(750, prepared.heightPx)
        assertTrue(maxOf(prepared.widthPx, prepared.heightPx) <= LocalInvoiceImagePreprocessor.OCR_MAX_SIDE_PX)
        assertEquals(prepared.widthPx, prepared.heightPx * 2)
    }

    @Test
    fun eightThousandPixelImageIsSampledAndDoesNotEscapeAsOutOfMemory() = runBlocking {
        val original = writeSolidGrayscalePng("large.png", width = 8_000, height = 8_000, gray = 210)
        val originalLength = original.length()

        val prepared = prepare(
            image(
                filePath = relativePathOf(original),
                width = 8_000,
                height = 8_000,
                mimeType = "image/png",
            ),
        )

        assertEquals(2_000, prepared.widthPx)
        assertEquals(2_000, prepared.heightPx)
        assertTrue(File(context.filesDir, prepared.relativePath).isFile)
        assertEquals(originalLength, original.length())
        assertEquals(8_000, decodeBounds(original).outWidth)
    }

    @Test
    fun samplingUsesTheLongestSideForSquareAndPanoramicImages() {
        assertEquals(4, InvoiceBitmapTransforms.sampleSizeFor(8_000, 8_000, 2_048))
        assertEquals(4, InvoiceBitmapTransforms.sampleSizeFor(8_000, 1_000, 2_048))
        assertEquals(2, InvoiceBitmapTransforms.sampleSizeFor(3_000, 1_500, 2_048))
        assertEquals(1, InvoiceBitmapTransforms.sampleSizeFor(2_048, 2_048, 2_048))
    }

    @Test
    fun clearOcrVersionsNeverRemovesTheOriginal() = runBlocking {
        val original = writeJpeg("clear.jpg", 64, 64, 0xFF808080.toInt())
        val published = prepare(image(relativePathOf(original), width = 64, height = 64))
        assertEquals(listOf(published), preprocessor.findPrepared(DRAFT_ID))
        val ocrDirectory = File(context.filesDir, "draft_images/${DRAFT_ID.value}/ocr")
        assertTrue(ocrDirectory.listFiles()?.isNotEmpty() == true)

        preprocessor.clearOcrVersions(DRAFT_ID)

        assertFalse(ocrDirectory.exists())
        assertTrue(original.isFile)
    }

    @Test
    fun ocrRootSymlinkIsRejectedWithoutWritingIntoItsTarget() = runBlocking {
        val original = writeJpeg("symlink-source.jpg", 64, 64, 0xFF808080.toInt())
        val sentinelRoot = File(context.filesDir, "ocr-sentinel").apply {
            check(mkdirs() || isDirectory)
        }
        val sentinel = File(sentinelRoot, "keep.txt").apply { writeText("intacto") }
        val ocrRoot = File(context.filesDir, "draft_images/${DRAFT_ID.value}/ocr")
        Files.createSymbolicLink(ocrRoot.toPath(), sentinelRoot.toPath())

        val failure = runCatching {
            prepare(image(relativePathOf(original), width = 64, height = 64))
        }.exceptionOrNull()

        assertTrue(failure is FileException)
        assertTrue(Files.isSymbolicLink(ocrRoot.toPath()))
        assertEquals("intacto", sentinel.readText())
        assertEquals(listOf("keep.txt"), sentinelRoot.listFiles().orEmpty().map(File::getName))
        NoFollowFileTree.delete(sentinelRoot)
        Unit
    }

    @Test
    fun manifestSymlinkIsRejectedAndClearDeletesOnlyTheLinkTree() = runBlocking {
        val original = writeJpeg("manifest-source.jpg", 64, 64, 0xFF808080.toInt())
        prepare(image(relativePathOf(original), width = 64, height = 64))
        val manifest = File(
            context.filesDir,
            "draft_images/${DRAFT_ID.value}/ocr/current-v1.manifest",
        )
        assertTrue(manifest.delete())
        val sentinel = File(context.filesDir, "ocr-manifest-sentinel.txt").apply {
            writeText("no leer ni borrar")
        }
        Files.createSymbolicLink(manifest.toPath(), sentinel.toPath())

        val failure = runCatching { preprocessor.findPrepared(DRAFT_ID) }.exceptionOrNull()
        assertTrue(failure is FileException)

        preprocessor.clearOcrVersions(DRAFT_ID)

        assertTrue(sentinel.isFile)
        assertEquals("no leer ni borrar", sentinel.readText())
        sentinel.delete()
        Unit
    }

    @Test
    fun failedRegenerationKeepsThePreviousCompleteManifest() = runBlocking {
        val original = writeJpeg("stable.jpg", 96, 128, 0xFF808080.toInt())
        val stable = prepare(image(relativePathOf(original), width = 96, height = 128))
        val broken = sourceFile("broken.jpg").apply {
            outputStream().use { it.write("not-an-image".toByteArray()) }
        }

        try {
            prepare(image(relativePathOf(broken), width = 96, height = 128))
            fail("se esperaba FileException")
        } catch (_: FileException) {
            // El run incompleto se descarta y el manifiesto anterior sigue siendo legible.
        }

        assertEquals(listOf(stable), preprocessor.findPrepared(DRAFT_ID))
        assertTrue(File(context.filesDir, stable.relativePath).isFile)
        assertTrue(original.isFile)
    }

    @Test
    fun retryRemovesKilledUnpublishedRunBeforeProcessingAndKeepsPublishedEvidence() = runBlocking {
        val original = writeJpeg("published.jpg", 96, 128, 0xFF808080.toInt())
        val stable = prepare(image(relativePathOf(original), width = 96, height = 128))
        val publishedRunId = stable.relativePath.split('/').let { parts ->
            parts[parts.indexOf("runs") + 1]
        }
        val runs = File(context.filesDir, "draft_images/${DRAFT_ID.value}/ocr/runs")
        val orphan = File(runs, UUID(0L, 999L).toString()).apply {
            check(mkdirs() || isDirectory)
        }
        File(orphan, "orphan.bin").writeBytes(ByteArray(64 * 1_024) { 1 })
        val abandonedManifestTemp = File(
            File(context.filesDir, "draft_images/${DRAFT_ID.value}/ocr"),
            "ocr-manifest-killed.tmp",
        ).apply { writeText("partial") }
        val broken = sourceFile("retry-broken.jpg").apply { writeText("not an image") }

        try {
            prepare(image(relativePathOf(broken), width = 96, height = 128))
            fail("se esperaba FileException")
        } catch (_: FileException) {
            // El fallo actual no impide la limpieza previa del run muerto.
        }

        assertFalse(orphan.exists())
        assertFalse(abandonedManifestTemp.exists())
        assertTrue(File(runs, publishedRunId).isDirectory)
        assertEquals(listOf(stable), preprocessor.findPrepared(DRAFT_ID))
        assertTrue(File(context.filesDir, stable.relativePath).isFile)
    }

    @Test
    fun clearWaitingForAnActiveBatchRemainsCancelable() = runBlocking {
        val original = writeJpeg("cancel-clear.jpg", 96, 128, 0xFF808080.toInt())
        val enteredLockedBatch = CountDownLatch(1)
        val releaseLockedBatch = CountDownLatch(1)
        val gated = LocalInvoiceImagePreprocessor(
            context = context,
            uuidGenerator = UuidGenerator {
                enteredLockedBatch.countDown()
                check(releaseLockedBatch.await(5, TimeUnit.SECONDS))
                UUID(0L, 500L)
            },
            dispatcherProvider = object : DispatcherProvider {
                override val io = Dispatchers.IO
                override val default = Dispatchers.Default
                override val main = Dispatchers.Main
            },
        )
        val processing = async(Dispatchers.Default) {
            gated.preprocess(
                DRAFT_ID,
                listOf(image(relativePathOf(original), width = 96, height = 128)),
            )
        }
        try {
            assertTrue(enteredLockedBatch.await(5, TimeUnit.SECONDS))
            // UNDISPATCHED garantiza que clear llegó hasta el mutex ocupado antes de cancelar.
            val clear = launch(start = CoroutineStart.UNDISPATCHED) {
                gated.clearOcrVersions(DRAFT_ID)
            }
            withTimeout(1_000) { clear.cancelAndJoin() }

            releaseLockedBatch.countDown()
            assertEquals(1, processing.await().size)
        } finally {
            releaseLockedBatch.countDown()
            if (processing.isActive) processing.cancelAndJoin()
        }
    }

    private fun writeJpeg(name: String, width: Int, height: Int, color: Int): File {
        val file = sourceFile(name)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        file.outputStream().buffered().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
        }
        bitmap.recycle()
        return file
    }

    private fun writeSplitJpeg(name: String, width: Int, height: Int): File {
        val file = sourceFile(name)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height) { index ->
            if (index % width < width / 2) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        file.outputStream().buffered().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output))
        }
        bitmap.recycle()
        return file
    }

    private suspend fun prepare(image: InvoiceImage) =
        preprocessor.preprocess(DRAFT_ID, listOf(image)).single()

    private fun luminance(color: Int): Int =
        ((color ushr 16 and 0xFF) + (color ushr 8 and 0xFF) + (color and 0xFF)) / 3

    /** PNG gris sólido generado por filas: crea cabeceras 8000×8000 sin bitmap de 256 MiB. */
    private fun writeSolidGrayscalePng(
        name: String,
        width: Int,
        height: Int,
        gray: Int,
    ): File {
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { deflater ->
            val row = ByteArray(width + 1) { index -> if (index == 0) 0 else gray.toByte() }
            repeat(height) { deflater.write(row) }
        }
        val file = sourceFile(name)
        DataOutputStream(file.outputStream().buffered()).use { output ->
            output.write(PNG_SIGNATURE)
            val ihdr = ByteArrayOutputStream().also { bytes ->
                DataOutputStream(bytes).use { header ->
                    header.writeInt(width)
                    header.writeInt(height)
                    header.writeByte(8)
                    header.writeByte(0)
                    header.writeByte(0)
                    header.writeByte(0)
                    header.writeByte(0)
                }
            }.toByteArray()
            writePngChunk(output, "IHDR", ihdr)
            writePngChunk(output, "IDAT", compressed.toByteArray())
            writePngChunk(output, "IEND", ByteArray(0))
        }
        return file
    }

    private fun writePngChunk(output: DataOutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }
        output.writeInt(data.size)
        output.write(typeBytes)
        output.write(data)
        output.writeInt(crc.value.toInt())
    }

    private fun sourceFile(name: String): File {
        val directory = File(context.filesDir, "draft_images/${DRAFT_ID.value}")
        check(directory.mkdirs() || directory.isDirectory)
        return File(directory, name)
    }

    private fun relativePathOf(file: File): String =
        file.relativeTo(context.filesDir).invariantSeparatorsPath

    private fun decodeBounds(file: File): BitmapFactory.Options =
        BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            BitmapFactory.decodeFile(file.absolutePath, this)
        }

    private fun image(
        filePath: String,
        width: Int,
        height: Int,
        mimeType: String = "image/jpeg",
        rotationDegrees: Int = 0,
        crop: ImageCrop? = null,
    ) = InvoiceImage(
        imageId = IMAGE_ID,
        draftId = DRAFT_ID,
        businessId = BUSINESS_ID,
        pageIndex = 0,
        filePath = filePath,
        sha256 = "d".repeat(64),
        mimeType = mimeType,
        widthPx = width,
        heightPx = height,
        fileSizeBytes = 2_000L,
        rotationDegrees = rotationDegrees,
        crop = crop,
        createdAt = Instant.EPOCH,
    )

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        val BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("00000000-0000-0000-0000-0000000000b1"),
        )
        val DRAFT_ID: DraftId = DraftId.from(
            UUID.fromString("00000000-0000-4000-8000-00000000dd01"),
        )
        val IMAGE_ID: ImageId = ImageId.from(
            UUID.fromString("00000000-0000-4000-8000-000000001a01"),
        )
    }
}
