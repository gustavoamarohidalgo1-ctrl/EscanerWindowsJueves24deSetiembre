package com.facturastock.app.data.files

import com.facturastock.app.core.coroutines.DefaultDispatcherProvider
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.core.platform.AppDirectories
import com.facturastock.app.data.files.FilesTestSupport.createSymbolicLinkOrSkip
import com.facturastock.app.domain.error.FileException
import com.facturastock.app.domain.model.ImageCrop
import com.facturastock.app.domain.model.InvoiceImage
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import java.awt.image.BufferedImage
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Pruebas físicas de orientación, recorte, escala, memoria y conservación del original. */
class LocalInvoiceImagePreprocessorTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var directories: AppDirectories
    private lateinit var filesDir: File
    private lateinit var preprocessor: LocalInvoiceImagePreprocessor
    private var runSequence: Long = 0

    @Before
    fun setUp() {
        directories = AppDirectories(tempFolder.newFolder("app").canonicalFile)
        filesDir = directories.filesDir
        preprocessor = LocalInvoiceImagePreprocessor(
            directories = directories,
            uuidGenerator = UuidGenerator { UUID(0L, ++runSequence) },
            dispatcherProvider = DefaultDispatcherProvider(),
        )
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
        val output = File(filesDir, prepared.relativePath)
        assertTrue(output.isFile)

        val decoded = checkNotNull(DesktopImageCodec.decodeFile(output))
        val color = decoded.getRGB(decoded.width / 2, decoded.height / 2)
        val channels = listOf(color ushr 16 and 0xFF, color ushr 8 and 0xFF, color and 0xFF)
        assertTrue(channels.max() - channels.min() <= 2)
        // El lado izquierdo negro pasa arriba con el giro horario de 90°; esto detecta tanto
        // el sentido equivocado como una prueba de giro basada solo en dimensiones.
        assertTrue(luminance(decoded.getRGB(decoded.width / 2, 10)) < 30)
        assertTrue(luminance(decoded.getRGB(decoded.width / 2, decoded.height - 10)) > 225)

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
        assertTrue(File(filesDir, prepared.relativePath).isFile)
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
    fun blockProcessingMatchesPreviousRowsExactlyIncludingAlphaAndPartialBlocks() = runBlocking {
        for (height in listOf(1, 15, 16, 17, 33)) {
            val width = if (height == 33) 2_048 else 37
            val actual = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            val alphas = intArrayOf(0, 1, 63, 128, 254, 255)
            val source = IntArray(width * height) { index ->
                val alpha = alphas[index % alphas.size]
                (alpha shl 24) or ((index * 101 and 255) shl 16) or
                    ((index * 73 and 255) shl 8) or (index * 29 and 255)
            }
            actual.setRGB(0, 0, width, height, source, 0, width)
            val expected = copyOf(actual)
            applyPreviousRows(expected)
            preprocessor.applyGrayscaleAndContrast(actual)

            val expectedPixels = IntArray(source.size)
            val actualPixels = IntArray(source.size)
            expected.getRGB(0, 0, width, height, expectedPixels, 0, width)
            actual.getRGB(0, 0, width, height, actualPixels, 0, width)
            assertArrayEquals("height=$height", expectedPixels, actualPixels)
        }
    }

    @Test
    fun canceledPixelProcessingLeavesTheBitmapUnchanged() = runBlocking {
        val bitmap = FilesTestSupport.solidImage(37, 33, 0x806A4C28.toInt())
        val previous = copyOf(bitmap)
        val processing = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            preprocessor.applyGrayscaleAndContrast(bitmap)
            fail("La transformación debe respetar la cancelación")
        }
        processing.join()
        assertTrue(processing.isCancelled)
        assertArrayEquals(pixelsOf(previous), pixelsOf(bitmap))
    }

    /** Referencia de la receta anterior: deliberadamente conserva lecturas por fila. */
    private fun applyPreviousRows(bitmap: BufferedImage) {
        val width = bitmap.width
        val row = IntArray(width)
        for (y in 0 until bitmap.height) {
            bitmap.getRGB(0, y, width, 1, row, 0, width)
            for (x in row.indices) {
                val color = row[x]
                val alpha = color ushr 24 and 255
                fun composite(channel: Int): Int = (channel * alpha + 255 * (255 - alpha) + 127) / 255
                val red = composite(color ushr 16 and 255)
                val green = composite(color ushr 8 and 255)
                val blue = composite(color and 255)
                val gray = (red * 77 + green * 150 + blue * 29) ushr 8
                val contrasted = ((gray - 128) * 112 / 100 + 128).coerceIn(0, 255)
                row[x] = -0x1000000 or (contrasted shl 16) or (contrasted shl 8) or contrasted
            }
            bitmap.setRGB(0, y, width, 1, row, 0, width)
        }
    }

    /** Copia ARGB independiente, equivalente a `Bitmap.copy(ARGB_8888, ...)`. */
    private fun copyOf(image: BufferedImage): BufferedImage =
        BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB).also { copy ->
            copy.setRGB(0, 0, image.width, image.height, pixelsOf(image), 0, image.width)
        }

    private fun pixelsOf(image: BufferedImage): IntArray =
        image.getRGB(0, 0, image.width, image.height, null, 0, image.width)

    @Test
    fun clearOcrVersionsNeverRemovesTheOriginal() = runBlocking {
        val original = writeJpeg("clear.jpg", 64, 64, 0xFF808080.toInt())
        val published = prepare(image(relativePathOf(original), width = 64, height = 64))
        assertEquals(listOf(published), preprocessor.findPrepared(DRAFT_ID))
        val ocrDirectory = File(filesDir, "draft_images/${DRAFT_ID.value}/ocr")
        assertTrue(ocrDirectory.listFiles()?.isNotEmpty() == true)

        preprocessor.clearOcrVersions(DRAFT_ID)

        assertFalse(ocrDirectory.exists())
        assertTrue(original.isFile)
    }

    @Test
    fun ocrRootSymlinkIsRejectedWithoutWritingIntoItsTarget() = runBlocking {
        val original = writeJpeg("symlink-source.jpg", 64, 64, 0xFF808080.toInt())
        val sentinelRoot = File(filesDir, "ocr-sentinel").apply {
            check(mkdirs() || isDirectory)
        }
        val sentinel = File(sentinelRoot, "keep.txt").apply { writeText("intacto") }
        val ocrRoot = File(filesDir, "draft_images/${DRAFT_ID.value}/ocr")
        createSymbolicLinkOrSkip(ocrRoot.toPath(), sentinelRoot.toPath())

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
            filesDir,
            "draft_images/${DRAFT_ID.value}/ocr/current-v1.manifest",
        )
        assertTrue(manifest.delete())
        val sentinel = File(filesDir, "ocr-manifest-sentinel.txt").apply {
            writeText("no leer ni borrar")
        }
        createSymbolicLinkOrSkip(manifest.toPath(), sentinel.toPath())

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
        assertTrue(File(filesDir, stable.relativePath).isFile)
        assertTrue(original.isFile)
    }

    @Test
    fun retryRemovesKilledUnpublishedRunBeforeProcessingAndKeepsPublishedEvidence() = runBlocking {
        val original = writeJpeg("published.jpg", 96, 128, 0xFF808080.toInt())
        val stable = prepare(image(relativePathOf(original), width = 96, height = 128))
        val publishedRunId = stable.relativePath.split('/').let { parts ->
            parts[parts.indexOf("runs") + 1]
        }
        val runs = File(filesDir, "draft_images/${DRAFT_ID.value}/ocr/runs")
        val orphan = File(runs, UUID(0L, 999L).toString()).apply {
            check(mkdirs() || isDirectory)
        }
        File(orphan, "orphan.bin").writeBytes(ByteArray(64 * 1_024) { 1 })
        val abandonedManifestTemp = File(
            File(filesDir, "draft_images/${DRAFT_ID.value}/ocr"),
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
        assertTrue(File(filesDir, stable.relativePath).isFile)
    }

    @Test
    fun clearWaitingForAnActiveBatchRemainsCancelable() = runBlocking {
        val original = writeJpeg("cancel-clear.jpg", 96, 128, 0xFF808080.toInt())
        val enteredLockedBatch = CountDownLatch(1)
        val releaseLockedBatch = CountDownLatch(1)
        val gated = LocalInvoiceImagePreprocessor(
            directories = directories,
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

    private fun writeJpeg(name: String, width: Int, height: Int, color: Int): File =
        FilesTestSupport.writeJpeg(
            sourceFile(name),
            FilesTestSupport.solidImage(width, height, color),
            quality = 95,
        )

    private fun writeSplitJpeg(name: String, width: Int, height: Int): File {
        val bitmap = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val pixels = IntArray(width * height) { index ->
            if (index % width < width / 2) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        bitmap.setRGB(0, 0, width, height, pixels, 0, width)
        return FilesTestSupport.writeJpeg(sourceFile(name), bitmap, quality = 100)
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
        val directory = File(filesDir, "draft_images/${DRAFT_ID.value}")
        check(directory.mkdirs() || directory.isDirectory)
        return File(directory, name)
    }

    private fun relativePathOf(file: File): String =
        file.relativeTo(filesDir).invariantSeparatorsPath

    private fun decodeBounds(file: File): DecodedImageBounds = DesktopImageCodec.decodeBounds(file)

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
