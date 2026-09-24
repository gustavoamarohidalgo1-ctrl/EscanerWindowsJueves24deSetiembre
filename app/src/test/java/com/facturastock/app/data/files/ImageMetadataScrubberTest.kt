package com.facturastock.app.data.files

import com.facturastock.app.data.files.FilesTestSupport.ascii
import com.facturastock.app.data.files.FilesTestSupport.containsSequence
import java.io.File
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Limpieza de metadatos sobre imágenes reales producidas por el códec de escritorio y
 * etiquetadas con un segmento EXIF (orientación, fabricante y GPS).
 */
class ImageMetadataScrubberTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var workDirectory: File

    @Before
    fun setUp() {
        workDirectory = tempFolder.newFolder("scrubber-test").canonicalFile
    }

    @Test
    fun scrubRemovesGpsExifAndOrientationFromJpegAndKeepsItDecodable() {
        val jpeg = jpegWithExif(orientation = ExifOrientationReader.ORIENTATION_ROTATE_90)
        // Precondición: el fixture sí declara la orientación y los metadatos privados.
        assertEquals(ExifOrientationReader.ORIENTATION_ROTATE_90, ExifOrientationReader.readOrientation(jpeg))

        val rotationDegrees = ImageMetadataScrubber.scrub(jpeg, "image/jpeg")

        // La orientación EXIF se rescata antes de limpiar: 6 = ROTATE_90.
        assertEquals(90, rotationDegrees)

        // El archivo limpio ya no declara GPS, fabricante ni orientación.
        val bytes = jpeg.readBytes()
        assertEquals(ExifOrientationReader.ORIENTATION_NORMAL, ExifOrientationReader.readOrientation(jpeg))
        assertFalse(bytes.containsSequence(ascii(FilesTestSupport.PRIVATE_MAKE)))
        // No queda ningún segmento APP1 ("Exif") en el contenedor.
        assertFalse(bytes.containsSequence(ascii("Exif")))

        // La imagen sigue decodificable y con sus dimensiones intactas.
        val bounds = DesktopImageCodec.decodeBounds(jpeg)
        assertEquals(64, bounds.outWidth)
        assertEquals(48, bounds.outHeight)
    }

    @Test
    fun scrubReturnsZeroForJpegWithoutExifAndKeepsEveryByte() {
        val jpeg = FilesTestSupport.writeJpeg(
            File(workDirectory, "plain.jpg"),
            FilesTestSupport.solidImage(32, 32, 0xFF884422.toInt()),
            quality = 90,
        )
        val original = jpeg.readBytes()

        val rotationDegrees = ImageMetadataScrubber.scrub(jpeg, "image/jpeg")

        assertEquals(0, rotationDegrees)
        assertTrue(jpeg.readBytes().contentEquals(original))
    }

    @Test
    fun scrubCancellationKeepsTheOriginalAndDeletesItsStreamingTemp() {
        val jpeg = FilesTestSupport.writeJpeg(
            File(workDirectory, "cancelled.jpg"),
            FilesTestSupport.solidImage(64, 64, 0xFF224466.toInt()),
            quality = 90,
        )
        val original = jpeg.readBytes()

        try {
            ImageMetadataScrubber.scrub(jpeg, "image/jpeg") {
                throw CancellationException("cancelled")
            }
            throw AssertionError("se esperaba cancelación")
        } catch (_: CancellationException) {
            // El reemplazo solo ocurre después de completar todo el contenedor.
        }

        assertTrue(jpeg.readBytes().contentEquals(original))
        assertTrue(
            workDirectory.listFiles()
                ?.none { it.name.startsWith("import-metadata-stripped-") }
                ?: true,
        )
    }

    @Test
    fun scrubStripsPngExifAndTextChunksAndKeepsItDecodable() {
        val png = FilesTestSupport.writePng(
            File(workDirectory, "tagged.png"),
            FilesTestSupport.solidImage(40, 24, 0xFF226688.toInt()),
        )

        // Inserta chunks eXIf y tEXt justo antes de IEND (últimos 12 bytes).
        val original = png.readBytes()
        val iendStart = original.size - PNG_IEND_BYTES
        val tagged = original.copyOfRange(0, iendStart) +
            pngChunk("eXIf", ByteArray(16) { 7 }) +
            pngChunk("tEXt", ascii("Title\u0000Privado")) +
            original.copyOfRange(iendStart, original.size)
        png.writeBytes(tagged)

        val rotationDegrees = ImageMetadataScrubber.scrub(png, "image/png")

        assertEquals(0, rotationDegrees)
        val stripped = png.readBytes()
        assertTrue(stripped.contentEquals(original))
        val bounds = DesktopImageCodec.decodeBounds(png)
        assertEquals(40, bounds.outWidth)
        assertEquals(24, bounds.outHeight)
    }

    private fun jpegWithExif(orientation: Int): File {
        val plain = FilesTestSupport.jpegBytes(
            FilesTestSupport.solidImage(64, 48, 0xFF336699.toInt()),
            quality = 92,
        )
        return File(workDirectory, "with-exif.jpg").apply {
            writeBytes(FilesTestSupport.withExif(plain, orientation))
        }
    }

    private fun pngChunk(type: String, payload: ByteArray): ByteArray {
        val length = payload.size
        return byteArrayOf(
            (length shr 24).toByte(), (length shr 16).toByte(),
            (length shr 8).toByte(), length.toByte(),
        ) + ascii(type) + payload + byteArrayOf(0x11, 0x22, 0x33, 0x44)
    }

    private companion object {
        const val PNG_IEND_BYTES = 12
    }
}
