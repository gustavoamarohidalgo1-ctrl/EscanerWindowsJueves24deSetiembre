package com.facturastock.app.data.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.CancellationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pruebas instrumentadas de la limpieza de metadatos sobre imágenes reales producidas por
 * el decodificador de la plataforma y etiquetadas con la propia [ExifInterface].
 */
@RunWith(AndroidJUnit4::class)
class ImageMetadataScrubberTest {
    private lateinit var context: Context
    private lateinit var workDirectory: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        workDirectory = File(context.cacheDir, "scrubber-test-${UUID.randomUUID()}")
        check(workDirectory.mkdirs()) { "no se pudo crear el directorio de trabajo" }
    }

    @After
    fun tearDown() {
        workDirectory.deleteRecursively()
    }

    @Test
    fun scrubRemovesGpsExifAndOrientationFromJpegAndKeepsItDecodable() {
        val jpeg = jpegWithExif(orientation = ExifInterface.ORIENTATION_ROTATE_90)

        val rotationDegrees = ImageMetadataScrubber.scrub(jpeg, "image/jpeg")

        // La orientación EXIF se rescata antes de limpiar: 6 = ROTATE_90.
        assertEquals(90, rotationDegrees)

        // El archivo limpio ya no declara GPS, EXIF ni orientación.
        val cleanExif = ExifInterface(jpeg)
        assertNull(cleanExif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertNull(cleanExif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE))
        assertNull(cleanExif.getAttribute(ExifInterface.TAG_MAKE))
        // ExifInterface 1.4.x sintetiza ORIENTATION_UNDEFINED por compatibilidad cuando el
        // contenedor no declara el tag; por eso el valor correcto no es el fallback -1.
        assertEquals(
            ExifInterface.ORIENTATION_UNDEFINED,
            cleanExif.getAttributeInt(ExifInterface.TAG_ORIENTATION, -1),
        )

        // No queda ningún segmento APP1 ("Exif") en el contenedor.
        val bytes = jpeg.readBytes()
        assertFalse(bytes.containsSequence(ascii("Exif")))

        // La imagen sigue decodificable y con sus dimensiones intactas.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(jpeg.absolutePath, bounds)
        assertEquals(64, bounds.outWidth)
        assertEquals(48, bounds.outHeight)
    }

    @Test
    fun scrubReturnsZeroForJpegWithoutExifAndKeepsEveryByte() {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF884422.toInt())
        val jpeg = File(workDirectory, "plain.jpg")
        FileOutputStream(jpeg).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        val original = jpeg.readBytes()

        val rotationDegrees = ImageMetadataScrubber.scrub(jpeg, "image/jpeg")

        assertEquals(0, rotationDegrees)
        assertTrue(jpeg.readBytes().contentEquals(original))
    }

    @Test
    fun scrubCancellationKeepsTheOriginalAndDeletesItsStreamingTemp() {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF224466.toInt())
        val jpeg = File(workDirectory, "cancelled.jpg")
        FileOutputStream(jpeg).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
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
        val bitmap = Bitmap.createBitmap(40, 24, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF226688.toInt())
        val png = File(workDirectory, "tagged.png")
        FileOutputStream(png).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()

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
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(png.absolutePath, bounds)
        assertEquals(40, bounds.outWidth)
        assertEquals(24, bounds.outHeight)
    }

    private fun jpegWithExif(orientation: Int): File {
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF336699.toInt())
        val file = File(workDirectory, "with-exif.jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bitmap.recycle()
        val exif = ExifInterface(file)
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, "12/1,2/1,3/1")
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "S")
        exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "77/1,2/1,3/1")
        exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "W")
        exif.setAttribute(ExifInterface.TAG_MAKE, "MarcaPrivada")
        exif.saveAttributes()
        return file
    }

    private fun pngChunk(type: String, payload: ByteArray): ByteArray {
        val length = payload.size
        return byteArrayOf(
            (length shr 24).toByte(), (length shr 16).toByte(),
            (length shr 8).toByte(), length.toByte(),
        ) + ascii(type) + payload + byteArrayOf(0x11, 0x22, 0x33, 0x44)
    }

    private fun ascii(value: String): ByteArray = value.toByteArray(Charsets.US_ASCII)

    private fun ByteArray.containsSequence(needle: ByteArray): Boolean =
        (0..size - needle.size).any { offset ->
            needle.indices.all { this[it + offset] == needle[it] }
        }

    private companion object {
        const val PNG_IEND_BYTES = 12
    }
}
