package com.facturastock.app.corpus

import java.io.DataOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.Deflater

internal data class GoldenCorpusRaster(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
)

/**
 * Renderiza una fixture a un PNG real sin depender de `java.awt`: los tests unitarios
 * compilan contra `android.jar`, que no incluye el subsistema de escritorio. El texto se
 * rasteriza con una fuente 5×7 propia, en mayúsculas y sin diacríticos; la imagen es una
 * entrada sintética reproducible, no una reproducción tipográfica ni una salida de ML Kit.
 */
object GoldenCorpusImage {
    internal const val WHITE = 0xFFFFFF
    internal const val BLACK = 0x000000
    private const val GLYPH_WIDTH = 5
    private const val GLYPH_HEIGHT = 7
    private const val GLYPH_ADVANCE = GLYPH_WIDTH + 1

    /** Escala fija 0.5× sobre la capa textual declarada por el manifiesto. */
    fun render(fixture: GoldenCorpusFixture, imageFile: File) {
        val raster = rasterize(fixture)
        writePng(raster.pixels, raster.width, raster.height, imageFile)
    }

    internal fun rasterize(fixture: GoldenCorpusFixture): GoldenCorpusRaster {
        val textLayer = fixture.inputTextLayer
        val width = textLayer.pageWidthPx / 2
        val height = textLayer.pageHeightPx / 2
        val pixels = IntArray(width * height) { WHITE }
        textLayer.cells.forEach { cell ->
            val (left, top, _, bottom) = cell.box
            val boxHeight = (bottom - top) / 2
            drawText(
                pixels = pixels,
                width = width,
                height = height,
                text = normalize(cell.text),
                originX = left / 2,
                originY = top / 2 + (boxHeight - GLYPH_HEIGHT).coerceAtLeast(0) / 2,
            )
        }
        val image = fixture.case.inputImage
        val skewed = if (image.horizontalSkewPermille == 0) {
            pixels
        } else {
            skewHorizontally(pixels, width, height, image.horizontalSkewPermille)
        }
        val rendered = if (image.blurRadiusPx == 0) {
            skewed
        } else {
            blur(skewed, width, height, image.blurRadiusPx)
        }
        return GoldenCorpusRaster(width, height, rendered)
    }

    /** Desplaza cada scanline: la geometría deja de ser ortogonal y el PNG cambia realmente. */
    private fun skewHorizontally(
        source: IntArray,
        width: Int,
        height: Int,
        skewPermille: Int,
    ): IntArray {
        val target = IntArray(source.size) { WHITE }
        val centerY = height / 2
        for (y in 0 until height) {
            val shift = (y - centerY) * skewPermille / 1_000
            for (x in 0 until width) {
                val targetX = x + shift
                if (targetX in 0 until width) {
                    target[y * width + targetX] = source[y * width + x]
                }
            }
        }
        return target
    }

    /** Box blur determinista sobre RGB; crea píxeles grises, no solo metadata de confianza. */
    private fun blur(
        source: IntArray,
        width: Int,
        height: Int,
        radius: Int,
    ): IntArray {
        val target = IntArray(source.size)
        for (y in 0 until height) {
            val top = (y - radius).coerceAtLeast(0)
            val bottom = (y + radius).coerceAtMost(height - 1)
            for (x in 0 until width) {
                val left = (x - radius).coerceAtLeast(0)
                val right = (x + radius).coerceAtMost(width - 1)
                var red = 0
                var green = 0
                var blue = 0
                var count = 0
                for (sampleY in top..bottom) {
                    for (sampleX in left..right) {
                        val rgb = source[sampleY * width + sampleX]
                        red += rgb shr 16 and 0xFF
                        green += rgb shr 8 and 0xFF
                        blue += rgb and 0xFF
                        count++
                    }
                }
                target[y * width + x] =
                    ((red / count) shl 16) or ((green / count) shl 8) or (blue / count)
            }
        }
        return target
    }

    private fun normalize(text: String): String = text
        .uppercase(Locale.ROOT)
        .map { char -> DIACRITICS[char] ?: char }
        .joinToString(separator = "")

    private fun drawText(
        pixels: IntArray,
        width: Int,
        height: Int,
        text: String,
        originX: Int,
        originY: Int,
    ) {
        text.forEachIndexed { index, char ->
            val glyph = FONT[char] ?: FONT.getValue('?')
            val glyphX = originX + index * GLYPH_ADVANCE
            glyph.forEachIndexed { row, bits ->
                for (column in 0 until GLYPH_WIDTH) {
                    if (bits and (1 shl (GLYPH_WIDTH - 1 - column)) != 0) {
                        val x = glyphX + column
                        val y = originY + row
                        if (x in 0 until width && y in 0 until height) {
                            pixels[y * width + x] = BLACK
                        }
                    }
                }
            }
        }
    }

    /** PNG RGB de 8 bits por canal, sin compresión intermedia ni filtros de scanline. */
    private fun writePng(pixels: IntArray, width: Int, height: Int, imageFile: File) {
        val raw = ByteArray(height * (1 + width * 3))
        var offset = 0
        for (y in 0 until height) {
            raw[offset++] = 0
            for (x in 0 until width) {
                val rgb = pixels[y * width + x]
                raw[offset++] = (rgb shr 16).toByte()
                raw[offset++] = (rgb shr 8).toByte()
                raw[offset++] = rgb.toByte()
            }
        }
        val deflater = Deflater()
        val compressed = try {
            deflater.setInput(raw)
            deflater.finish()
            val buffer = ByteArray(raw.size + 1_024)
            val count = deflater.deflate(buffer)
            buffer.copyOf(count)
        } finally {
            deflater.end()
        }

        checkNotNull(imageFile.parentFile).mkdirs()
        DataOutputStream(imageFile.outputStream().buffered()).use { out ->
            out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            val ihdr = ByteArray(13)
            writeInt(ihdr, 0, width)
            writeInt(ihdr, 4, height)
            ihdr[8] = 8 // profundidad de bits
            ihdr[9] = 2 // color RGB verdadero
            writeChunk(out, "IHDR", ihdr)
            writeChunk(out, "IDAT", compressed)
            writeChunk(out, "IEND", ByteArray(0))
        }
    }

    private fun writeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value shr 24).toByte()
        target[offset + 1] = (value shr 16).toByte()
        target[offset + 2] = (value shr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private fun writeChunk(out: DataOutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.writeInt(data.size)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        out.writeInt(crc.value.toInt())
    }

    private val DIACRITICS = mapOf(
        'Á' to 'A', 'É' to 'E', 'Í' to 'I', 'Ó' to 'O', 'Ú' to 'U', 'Ü' to 'U', 'Ñ' to 'N',
    )

    /** Fuente 5×7 propia: cada glifo son 7 filas de 5 bits, del borde superior al inferior. */
    private val FONT: Map<Char, IntArray> = mapOf(
        ' ' to intArrayOf(0b00000, 0b00000, 0b00000, 0b00000, 0b00000, 0b00000, 0b00000),
        '0' to intArrayOf(0b01110, 0b10001, 0b10011, 0b10101, 0b11001, 0b10001, 0b01110),
        '1' to intArrayOf(0b00100, 0b01100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110),
        '2' to intArrayOf(0b01110, 0b10001, 0b00001, 0b00010, 0b00100, 0b01000, 0b11111),
        '3' to intArrayOf(0b11111, 0b00010, 0b00100, 0b00010, 0b00001, 0b10001, 0b01110),
        '4' to intArrayOf(0b00010, 0b00110, 0b01010, 0b10010, 0b11111, 0b00010, 0b00010),
        '5' to intArrayOf(0b11111, 0b10000, 0b11110, 0b00001, 0b00001, 0b10001, 0b01110),
        '6' to intArrayOf(0b00110, 0b01000, 0b10000, 0b11110, 0b10001, 0b10001, 0b01110),
        '7' to intArrayOf(0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b01000, 0b01000),
        '8' to intArrayOf(0b01110, 0b10001, 0b10001, 0b01110, 0b10001, 0b10001, 0b01110),
        '9' to intArrayOf(0b01110, 0b10001, 0b10001, 0b01111, 0b00001, 0b00010, 0b01100),
        'A' to intArrayOf(0b01110, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001),
        'B' to intArrayOf(0b11110, 0b10001, 0b10001, 0b11110, 0b10001, 0b10001, 0b11110),
        'C' to intArrayOf(0b01110, 0b10001, 0b10000, 0b10000, 0b10000, 0b10001, 0b01110),
        'D' to intArrayOf(0b11100, 0b10010, 0b10001, 0b10001, 0b10001, 0b10010, 0b11100),
        'E' to intArrayOf(0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b11111),
        'F' to intArrayOf(0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b10000),
        'G' to intArrayOf(0b01110, 0b10001, 0b10000, 0b10111, 0b10001, 0b10001, 0b01111),
        'H' to intArrayOf(0b10001, 0b10001, 0b10001, 0b11111, 0b10001, 0b10001, 0b10001),
        'I' to intArrayOf(0b01110, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110),
        'J' to intArrayOf(0b00111, 0b00010, 0b00010, 0b00010, 0b00010, 0b10010, 0b01100),
        'K' to intArrayOf(0b10001, 0b10010, 0b10100, 0b11000, 0b10100, 0b10010, 0b10001),
        'L' to intArrayOf(0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b11111),
        'M' to intArrayOf(0b10001, 0b11011, 0b10101, 0b10101, 0b10001, 0b10001, 0b10001),
        'N' to intArrayOf(0b10001, 0b10001, 0b11001, 0b10101, 0b10011, 0b10001, 0b10001),
        'O' to intArrayOf(0b01110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110),
        'P' to intArrayOf(0b11110, 0b10001, 0b10001, 0b11110, 0b10000, 0b10000, 0b10000),
        'Q' to intArrayOf(0b01110, 0b10001, 0b10001, 0b10001, 0b10101, 0b10010, 0b01101),
        'R' to intArrayOf(0b11110, 0b10001, 0b10001, 0b11110, 0b10100, 0b10010, 0b10001),
        'S' to intArrayOf(0b01111, 0b10000, 0b10000, 0b01110, 0b00001, 0b00001, 0b11110),
        'T' to intArrayOf(0b11111, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100),
        'U' to intArrayOf(0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01110),
        'V' to intArrayOf(0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01010, 0b00100),
        'W' to intArrayOf(0b10001, 0b10001, 0b10001, 0b10101, 0b10101, 0b10101, 0b01010),
        'X' to intArrayOf(0b10001, 0b10001, 0b01010, 0b00100, 0b01010, 0b10001, 0b10001),
        'Y' to intArrayOf(0b10001, 0b10001, 0b01010, 0b00100, 0b00100, 0b00100, 0b00100),
        'Z' to intArrayOf(0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b10000, 0b11111),
        '.' to intArrayOf(0b00000, 0b00000, 0b00000, 0b00000, 0b00000, 0b01100, 0b01100),
        ',' to intArrayOf(0b00000, 0b00000, 0b00000, 0b00000, 0b01100, 0b00100, 0b01000),
        ':' to intArrayOf(0b00000, 0b01100, 0b01100, 0b00000, 0b01100, 0b01100, 0b00000),
        '/' to intArrayOf(0b00001, 0b00010, 0b00010, 0b00100, 0b01000, 0b01000, 0b10000),
        '%' to intArrayOf(0b11001, 0b11010, 0b00010, 0b00100, 0b01000, 0b01011, 0b10011),
        '-' to intArrayOf(0b00000, 0b00000, 0b00000, 0b11111, 0b00000, 0b00000, 0b00000),
        '(' to intArrayOf(0b00010, 0b00100, 0b01000, 0b01000, 0b01000, 0b00100, 0b00010),
        ')' to intArrayOf(0b01000, 0b00100, 0b00010, 0b00010, 0b00010, 0b00100, 0b01000),
        '+' to intArrayOf(0b00000, 0b00100, 0b00100, 0b11111, 0b00100, 0b00100, 0b00000),
        '?' to intArrayOf(0b01110, 0b10001, 0b00001, 0b00010, 0b00100, 0b00000, 0b00100),
    )
}
