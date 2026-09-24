package com.facturastock.app.data.files

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import org.junit.Assume.assumeTrue

/**
 * Utilidades compartidas por las pruebas JVM de la capa de archivos: imágenes sintéticas con
 * el códec de escritorio, etiquetado EXIF mínimo sin `androidx.exifinterface` y enlaces
 * simbólicos que se omiten donde el sistema no permite crearlos (Windows sin modo desarrollador).
 */
internal object FilesTestSupport {

    /** Imagen ARGB de un solo color, equivalente a `Bitmap.eraseColor`. */
    fun solidImage(width: Int, height: Int, argb: Int): BufferedImage =
        BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply {
            val row = IntArray(width) { argb }
            for (y in 0 until height) setRGB(0, y, width, 1, row, 0, width)
        }

    /** JPEG con el mismo códec que usa producción (`Bitmap.compress(JPEG, q)`). */
    fun jpegBytes(image: BufferedImage, quality: Int = 92): ByteArray =
        ByteArrayOutputStream().use { output ->
            check(DesktopImageCodec.compressJpeg(image, quality, output))
            output.toByteArray()
        }

    fun pngBytes(image: BufferedImage): ByteArray =
        ByteArrayOutputStream().use { output ->
            check(ImageIO.write(image, "png", output))
            output.toByteArray()
        }

    fun writeJpeg(file: File, image: BufferedImage, quality: Int = 92): File =
        file.apply { writeBytes(jpegBytes(image, quality)) }

    fun writePng(file: File, image: BufferedImage): File =
        file.apply { writeBytes(pngBytes(image)) }

    /**
     * Inserta un segmento APP1 `Exif` con orientación, fabricante y GPS justo después del SOI y
     * del APP0 JFIF (si existe), como lo dejaría `ExifInterface.saveAttributes`.
     */
    fun withExif(
        jpeg: ByteArray,
        orientation: Int,
        make: String = PRIVATE_MAKE,
    ): ByteArray {
        require(jpeg.size > 4 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte())
        var insertAt = 2
        if (jpeg[2] == 0xFF.toByte() && jpeg[3] == 0xE0.toByte()) {
            val app0Length = ((jpeg[4].toInt() and 0xFF) shl 8) or (jpeg[5].toInt() and 0xFF)
            insertAt = 4 + app0Length
        }
        val payload = EXIF_HEADER + tiffWithOrientationMakeAndGps(orientation, make)
        val segmentLength = payload.size + 2
        val segment = byteArrayOf(
            0xFF.toByte(),
            0xE1.toByte(),
            (segmentLength shr 8).toByte(),
            segmentLength.toByte(),
        ) + payload
        return jpeg.copyOfRange(0, insertAt) + segment + jpeg.copyOfRange(insertAt, jpeg.size)
    }

    /** TIFF big-endian: IFD0 con Make, Orientation y puntero GPS; GPS con latitud y referencia. */
    private fun tiffWithOrientationMakeAndGps(orientation: Int, make: String): ByteArray {
        val makeBytes = make.toByteArray(Charsets.US_ASCII) + 0.toByte()
        val ifd0Offset = 8
        val ifd0Entries = 3
        val makeOffset = ifd0Offset + 2 + ifd0Entries * 12 + 4
        val gpsOffset = (makeOffset + makeBytes.size).let { it + (it and 1) }
        val gpsEntries = 2
        val latitudeOffset = gpsOffset + 2 + gpsEntries * 12 + 4
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeBytes("MM")
            out.writeShort(42)
            out.writeInt(ifd0Offset)
            out.writeShort(ifd0Entries)
            // 0x010F Make (ASCII)
            out.writeShort(0x010F); out.writeShort(2); out.writeInt(makeBytes.size); out.writeInt(makeOffset)
            // 0x0112 Orientation (SHORT, valor alineado a la izquierda)
            out.writeShort(0x0112); out.writeShort(3); out.writeInt(1)
            out.writeShort(orientation); out.writeShort(0)
            // 0x8825 GPSInfo (LONG)
            out.writeShort(0x8825); out.writeShort(4); out.writeInt(1); out.writeInt(gpsOffset)
            out.writeInt(0)
            out.write(makeBytes)
            while (out.size() < gpsOffset) out.writeByte(0)
            out.writeShort(gpsEntries)
            // 0x0001 GPSLatitudeRef "S"
            out.writeShort(0x0001); out.writeShort(2); out.writeInt(2)
            out.writeByte('S'.code); out.writeByte(0); out.writeShort(0)
            // 0x0002 GPSLatitude 12/1, 2/1, 3/1 (RATIONAL x3)
            out.writeShort(0x0002); out.writeShort(5); out.writeInt(3); out.writeInt(latitudeOffset)
            out.writeInt(0)
            listOf(12, 2, 3).forEach { value -> out.writeInt(value); out.writeInt(1) }
        }
        return bytes.toByteArray()
    }

    /** Crea el enlace o salta la prueba si el sistema no admite symlinks sin privilegios. */
    fun createSymbolicLinkOrSkip(link: Path, target: Path) {
        val created = try {
            Files.createSymbolicLink(link, target)
            true
        } catch (_: UnsupportedOperationException) {
            false
        } catch (_: IOException) {
            false
        }
        assumeTrue("El sistema no permite crear enlaces simbólicos", created)
    }

    fun ByteArray.containsSequence(needle: ByteArray): Boolean =
        (0..size - needle.size).any { offset ->
            needle.indices.all { this[it + offset] == needle[it] }
        }

    fun ascii(value: String): ByteArray = value.toByteArray(Charsets.US_ASCII)

    const val PRIVATE_MAKE = "MarcaPrivada"
    private val EXIF_HEADER = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00)
}
