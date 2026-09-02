package com.facturastock.app.data.files

import com.facturastock.app.domain.error.FileError
import com.facturastock.app.domain.error.FileException
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ImageMetadataStripperTest {

    // --- JPEG ---

    @Test
    fun `jpeg strips exif xmp iptc and comment segments keeping the image structure`() {
        val app0 = jpegSegment(0xE0, ascii("JFIF\u0000") + byteArrayOf(1, 1))
        val app1Exif = jpegSegment(0xE1, ascii("Exif\u0000\u0000") + ByteArray(32) { it.toByte() })
        val app1Xmp = jpegSegment(0xE1, ascii("http://ns.adobe.com/xap/1.0/\u0000") + ByteArray(8))
        val app13 = jpegSegment(0xED, ascii("Photoshop 3.0\u0000") + ByteArray(6))
        val comment = jpegSegment(0xFE, ascii("comentario privado"))
        val dqt = jpegSegment(0xDB, ByteArray(69) { 1 })
        val sof0 = jpegSegment(0xC0, ByteArray(17) { 2 })
        val dht = jpegSegment(0xC4, ByteArray(20) { 3 })
        val sos = jpegSegment(0xDA, ByteArray(12) { 4 })
        val scan = byteArrayOf(
            0x11, 0x22, 0xFF.toByte(), 0x00, 0x33, // dato "stuffed" FF00
            0xFF.toByte(), 0xD0.toByte(), // reinicio RST0 dentro del scan
            0x44, 0x55,
        )
        val eoi = byteArrayOf(0xFF.toByte(), 0xD9.toByte())

        val jpeg = JPEG_SOI + app0 + app1Exif + app1Xmp + app13 + comment + dqt + sof0 +
            dht + sos + scan + eoi + ascii("basura tras el EOI")

        val stripped = ImageMetadataStripper.strip(jpeg, "image/jpeg")

        val expected = JPEG_SOI + app0 + dqt + sof0 + dht + sos + scan + eoi
        assertTrue(stripped.contentEquals(expected))
    }

    @Test
    fun `jpeg without metadata keeps every byte`() {
        val app0 = jpegSegment(0xE0, ascii("JFIF\u0000"))
        val dqt = jpegSegment(0xDB, ByteArray(69))
        val sos = jpegSegment(0xDA, ByteArray(12))
        val jpeg = JPEG_SOI + app0 + dqt + sos + byteArrayOf(0x10, 0x20) + JPEG_EOI

        val stripped = ImageMetadataStripper.strip(jpeg, "image/jpeg")

        assertTrue(stripped.contentEquals(jpeg))
    }

    @Test
    fun `jpeg with progressive scans keeps all scan data verbatim`() {
        val sos = jpegSegment(0xDA, ByteArray(12))
        val firstScan = byteArrayOf(0x01, 0xFF.toByte(), 0x00, 0x02)
        val dht = jpegSegment(0xC4, ByteArray(20))
        val secondScan = byteArrayOf(0x03, 0xFF.toByte(), 0xD3.toByte(), 0x04)
        val jpeg = JPEG_SOI + sos + firstScan + dht + sos + secondScan + JPEG_EOI

        val stripped = ImageMetadataStripper.strip(jpeg, "image/jpeg")

        assertTrue(stripped.contentEquals(jpeg))
    }

    @Test
    fun `jpeg malformed structures are rejected as corrupt`() {
        assertCorrupt { ImageMetadataStripper.strip(ascii("no es jpeg"), "image/jpeg") }
        assertCorrupt { ImageMetadataStripper.strip(JPEG_SOI, "image/jpeg") }
        assertCorrupt {
            // Segmento que declara más longitud de la disponible.
            ImageMetadataStripper.strip(
                JPEG_SOI + byteArrayOf(0xFF.toByte(), 0xDB.toByte(), 0x7F, 0x7F, 0x01),
                "image/jpeg",
            )
        }
        assertCorrupt {
            // Scan sin EOI: archivo truncado.
            val sos = jpegSegment(0xDA, ByteArray(12))
            ImageMetadataStripper.strip(JPEG_SOI + sos + byteArrayOf(0x01, 0x02), "image/jpeg")
        }
    }

    // --- PNG ---

    @Test
    fun `png strips exif and text chunks keeping critical chunks with their crc`() {
        val ihdr = pngChunk("IHDR", ByteArray(13) { it.toByte() })
        val exif = pngChunk("eXIf", ByteArray(24) { 9 })
        val text = pngChunk("tEXt", ascii("Title\u0000Factura privada"))
        val ztxt = pngChunk("zTXt", ByteArray(10))
        val itxt = pngChunk("iTXt", ByteArray(12))
        val time = pngChunk("tIME", ByteArray(7))
        val idat = pngChunk("IDAT", ByteArray(30) { 7 })
        val iend = pngChunk("IEND", byteArrayOf())

        val png = PNG_SIGNATURE + ihdr + exif + text + ztxt + itxt + time + idat + iend +
            ascii("cola no estándar")

        val stripped = ImageMetadataStripper.strip(png, "image/png")

        assertTrue(stripped.contentEquals(PNG_SIGNATURE + ihdr + idat + iend))
    }

    @Test
    fun `png without metadata keeps every byte`() {
        val png = PNG_SIGNATURE + pngChunk("IHDR", ByteArray(13)) +
            pngChunk("IDAT", ByteArray(5)) + pngChunk("IEND", byteArrayOf())

        val stripped = ImageMetadataStripper.strip(png, "image/png")

        assertTrue(stripped.contentEquals(png))
    }

    @Test
    fun `png malformed structures are rejected as corrupt`() {
        assertCorrupt { ImageMetadataStripper.strip(ascii("no es png"), "image/png") }
        assertCorrupt {
            // Primer chunk que no es IHDR.
            ImageMetadataStripper.strip(PNG_SIGNATURE + pngChunk("IDAT", ByteArray(4)), "image/png")
        }
        assertCorrupt {
            // Chunk truncado: falta IEND.
            ImageMetadataStripper.strip(PNG_SIGNATURE + pngChunk("IHDR", ByteArray(13)), "image/png")
        }
    }

    // --- WebP ---

    @Test
    fun `webp strips exif and xmp chunks and clears their vp8x flags`() {
        val vp8x = webpChunk("VP8X", byteArrayOf(0x0C, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        val exif = webpChunk("EXIF", ByteArray(18) { 5 })
        val xmp = webpChunk("XMP ", ascii("<x:xmpmeta>privado</x:xmpmeta>"))
        val vp8l = webpChunk("VP8L", byteArrayOf(0x2F, 1, 2)) // tamaño impar: lleva relleno

        val webp = webpFile(vp8x + exif + xmp + vp8l)

        val stripped = ImageMetadataStripper.strip(webp, "image/webp")

        val cleanVp8x = webpChunk("VP8X", byteArrayOf(0x00, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        val expected = webpFile(cleanVp8x + vp8l)
        assertTrue(stripped.contentEquals(expected))
    }

    @Test
    fun `webp without metadata keeps every byte`() {
        val webp = webpFile(webpChunk("VP8 ", ByteArray(16) { 3 }))

        val stripped = ImageMetadataStripper.strip(webp, "image/webp")

        assertTrue(stripped.contentEquals(webp))
    }

    @Test
    fun `webp malformed structures are rejected as corrupt`() {
        assertCorrupt { ImageMetadataStripper.strip(ascii("no es webp"), "image/webp") }
        assertCorrupt {
            // El tamaño RIFF declara más de lo disponible.
            val webp = webpFile(webpChunk("VP8 ", ByteArray(4)))
            webp[7] = 0x7F
            ImageMetadataStripper.strip(webp, "image/webp")
        }
        assertCorrupt {
            // VP8X con payload distinto de 10 bytes no es válido.
            ImageMetadataStripper.strip(
                webpFile(webpChunk("VP8X", ByteArray(4))),
                "image/webp",
            )
        }
    }

    @Test
    fun `an unsupported mime type is rejected without touching the bytes`() {
        try {
            ImageMetadataStripper.strip(ByteArray(8), "image/heic")
            fail("se esperaba FileException")
        } catch (expected: FileException) {
            assertEquals(FileError.UnsupportedFormat, expected.error)
        }
    }

    @Test
    fun `streaming file path matches the pure stripper for large container ranges`() {
        val jpegMetadata = jpegSegment(0xE1, ascii("Exif\u0000\u0000privado"))
        val jpegScan = jpegSegment(0xDA, ByteArray(12)) +
            ByteArray(180_000) { index -> if (index % 251 == 0) 0x7E else 0x11 } + JPEG_EOI
        val jpeg = JPEG_SOI + jpegMetadata + jpegScan + ascii("cola")
        val png = PNG_SIGNATURE + pngChunk("IHDR", ByteArray(13)) +
            pngChunk("tEXt", ascii("Title\u0000privado")) +
            pngChunk("IDAT", ByteArray(96_000) { 3 }) + pngChunk("IEND", byteArrayOf())
        val webp = webpFile(
            webpChunk("VP8X", byteArrayOf(0x0C, 0, 0, 0, 0, 0, 0, 0, 0, 0)) +
                webpChunk("EXIF", ByteArray(24)) + webpChunk("VP8 ", ByteArray(90_000) { 4 }),
        )

        listOf(
            "image/jpeg" to jpeg,
            "image/png" to png,
            "image/webp" to webp,
        ).forEach { (mimeType, sourceBytes) ->
            val source = File.createTempFile("metadata-source-", ".tmp")
            val destination = File.createTempFile("metadata-destination-", ".tmp")
            try {
                source.writeBytes(sourceBytes)
                var checkpoints = 0
                val changed = ImageMetadataStripper.stripFile(
                    source = source,
                    destination = destination,
                    mimeType = mimeType,
                    checkpoint = { checkpoints++ },
                )

                assertTrue(mimeType, changed)
                assertTrue(
                    mimeType,
                    destination.readBytes().contentEquals(
                        ImageMetadataStripper.strip(sourceBytes, mimeType),
                    ),
                )
                assertTrue("$mimeType debe comprobar cancelación por bloques", checkpoints > 2)
            } finally {
                source.delete()
                destination.delete()
            }
        }
    }

    @Test
    fun `streaming path reports unchanged input and aborts cooperatively`() {
        val plain = JPEG_SOI + jpegSegment(0xDA, ByteArray(12)) +
            ByteArray(180_000) { 0x25 } + JPEG_EOI
        val source = File.createTempFile("metadata-source-", ".jpg")
        val destination = File.createTempFile("metadata-destination-", ".tmp")
        try {
            source.writeBytes(plain)
            assertFalse(
                ImageMetadataStripper.stripFile(source, destination, "image/jpeg"),
            )
            assertTrue(destination.readBytes().contentEquals(plain))

            var checkpoints = 0
            try {
                ImageMetadataStripper.stripFile(
                    source,
                    destination,
                    "image/jpeg",
                ) {
                    checkpoints++
                    if (checkpoints == 3) throw CancellationException("cancelled")
                }
                fail("se esperaba cancelación")
            } catch (_: CancellationException) {
                assertEquals(3, checkpoints)
                assertTrue(source.readBytes().contentEquals(plain))
            }
        } finally {
            source.delete()
            destination.delete()
        }
    }

    // --- Orientación EXIF ---

    @Test
    fun `exif orientation values map to their base rotation degrees`() {
        val expected = mapOf(
            1 to 0, // NORMAL
            2 to 0, // espejo horizontal → rotación base 0
            3 to 180,
            4 to 180, // espejo vertical → rotación base 180
            5 to 90, // transpuesta → rotación base 90
            6 to 90,
            7 to 270, // transversa → rotación base 270
            8 to 270,
        )
        expected.forEach { (orientation, degrees) ->
            assertEquals("orientación $orientation", degrees, exifOrientationToDegrees(orientation))
        }
        assertEquals(0, exifOrientationToDegrees(0))
        assertEquals(0, exifOrientationToDegrees(99))
    }

    // --- Fixtures ---

    private fun assertCorrupt(block: () -> Unit) {
        try {
            block()
            fail("se esperaba FileException")
        } catch (expected: FileException) {
            assertEquals(FileError.Corrupt, expected.error)
        }
    }

    private fun ascii(value: String): ByteArray = value.toByteArray(Charsets.US_ASCII)

    private fun jpegSegment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(
            0xFF.toByte(),
            marker.toByte(),
            (length shr 8).toByte(),
            length.toByte(),
        ) + payload
    }

    /** Chunk PNG: longitud(4 BE) + tipo(4) + datos + CRC(4, ficticio: no se valida). */
    private fun pngChunk(type: String, payload: ByteArray): ByteArray {
        val length = ByteBuffer.allocate(4).putInt(payload.size).array()
        return length + ascii(type) + payload + byteArrayOf(0x12, 0x34, 0x56, 0x78)
    }

    /** Chunk RIFF: fourcc(4) + tamaño(4 LE) + datos + relleno si el tamaño es impar. */
    private fun webpChunk(fourcc: String, payload: ByteArray): ByteArray {
        val size = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.size).array()
        val padding = if (payload.size % 2 == 1) byteArrayOf(0) else byteArrayOf()
        return ascii(fourcc) + size + payload + padding
    }

    private fun webpFile(chunks: ByteArray): ByteArray {
        val riffSize = chunks.size + 4 // "WEBP" + chunks
        val size = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(riffSize).array()
        return ascii("RIFF") + size + ascii("WEBP") + chunks
    }

    private companion object {
        val JPEG_SOI = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
        val JPEG_EOI = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
    }
}
