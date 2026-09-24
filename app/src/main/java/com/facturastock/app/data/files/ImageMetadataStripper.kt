package com.facturastock.app.data.files

import com.facturastock.app.domain.error.FileError
import com.facturastock.app.domain.error.FileException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.min

/**
 * Limpieza estructural de metadatos a nivel de contenedor para los formatos de
 * [com.facturastock.app.domain.model.CaptureImagePolicy]. Es pura (solo Kotlin/JDK) para
 * evaluarse igual en la app y en las pruebas de JVM.
 *
 * A diferencia de reescribir EXIF tag a tag, trabajar sobre la estructura del contenedor
 * elimina por construcción TODO lo que no es imagen: GPS, EXIF, XMP, IPTC, comentarios y
 * maker notes, sin tocar los datos de píxeles ni re-codificar (el contenido limpio es
 * bit a bit el original salvo los segmentos retirados).
 *
 * - **JPEG**: se conservan SOI, APP0 (JFIF), APP2 (perfil ICC), APP14 (Adobe), DQT, DHT,
 *   DAC, DRI y los SOF; se retiran el resto de segmentos con longitud (APP1 con EXIF/XMP,
 *   APP13 con IPTC, COM y cualquier otro APPn) y lo que siga al EOI.
 * - **PNG**: se retiran los chunks `eXIf`, `tEXt`, `zTXt`, `iTXt` y `tIME`; el resto se
 *   copia intacto con su CRC y se descarta lo que siga a `IEND`.
 * - **WebP**: se retiran los chunks `EXIF` y `XMP `, se limpian sus banderas en `VP8X` y
 *   se recalcula el tamaño RIFF.
 *
 * La política es cerrada: cualquier anomalía estructural (truncados, longitudes
 * inválidas, firmas incorrectas) produce [FileException] con [FileError.Corrupt] y la
 * importación se rechaza — la privacidad no se negocia ante un archivo dudoso.
 */
internal object ImageMetadataStripper {

    /** Devuelve [bytes] sin metadatos; si no había nada que retirar, devuelve la entrada. */
    fun strip(bytes: ByteArray, mimeType: String): ByteArray = when (mimeType) {
        MIME_JPEG -> stripJpeg(bytes)
        MIME_PNG -> stripPng(bytes)
        MIME_WEBP -> stripWebp(bytes)
        else -> throw FileException(FileError.UnsupportedFormat)
    }

    /**
     * Variante de archivo usada por producción. El origen se lee una sola vez a un búfer y los
     * rangos que sí pertenecen a la imagen se copian directamente al canal de salida; por tanto,
     * un archivo admitido por la política no se duplica varias veces en el heap. No se usa
     * `FileChannel.map`: en Windows un mapeo sigue vivo hasta que el GC lo libera y, mientras
     * tanto, el archivo no puede reemplazarse ni borrarse (`AccessDeniedException`). [checkpoint] se
     * ejecuta durante el escaneo y cada bloque copiado para que el llamador pueda hacer la tarea
     * cooperativamente cancelable.
     *
     * Devuelve `true` cuando se retiró metadata, se limpiaron flags o se descartaron bytes tras el
     * final lógico del contenedor. [destination] siempre queda estructuralmente completo cuando la
     * función retorna; ante un fallo pertenece al llamador eliminarlo.
     */
    fun stripFile(
        source: File,
        destination: File,
        mimeType: String,
        checkpoint: () -> Unit = {},
    ): Boolean {
        FileInputStream(source).channel.use { input ->
            val fileSize = input.size()
            if (fileSize <= 0L || fileSize > Int.MAX_VALUE.toLong()) return corrupt()
            val mapped = ByteBuffer.allocate(fileSize.toInt())
            while (mapped.hasRemaining()) {
                if (input.read(mapped) < 0) return corrupt()
                checkpoint()
            }
            mapped.flip()
            FileOutputStream(destination).channel.use { output ->
                return when (mimeType) {
                    MIME_JPEG -> stripJpeg(mapped, fileSize.toInt(), output, checkpoint)
                    MIME_PNG -> stripPng(mapped, fileSize.toInt(), output, checkpoint)
                    MIME_WEBP -> stripWebp(mapped, fileSize.toInt(), output, checkpoint)
                    else -> throw FileException(FileError.UnsupportedFormat)
                }
            }
        }
    }

    // --- JPEG ---

    /** Marcadores con longitud que se conservan; cualquier otro se retira. */
    private val JPEG_KEPT_MARKERS: Set<Int> = setOf(
        0xE0, // APP0 (JFIF)
        0xE2, // APP2 (perfil ICC)
        0xEE, // APP14 (Adobe)
        0xDB, // DQT
        0xC4, // DHT
        0xCC, // DAC
        0xDD, // DRI
        // SOFn (sin C4/C8/CC, que no son SOF)
        0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7,
        0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF,
    )

    private fun stripJpeg(bytes: ByteArray): ByteArray {
        if (bytes.size < 4 || bytes[0] != MARKER_PREFIX || bytes[1] != JPEG_SOI) {
            return corrupt()
        }
        val output = ByteArrayOutputStream(bytes.size)
        output.write(bytes, 0, 2)
        var index = 2
        var finished = false
        while (!finished) {
            if (index >= bytes.size || bytes[index] != MARKER_PREFIX) return corrupt()
            val markerStart = index
            while (index < bytes.size && bytes[index] == MARKER_PREFIX) index++
            if (index >= bytes.size) return corrupt()
            val marker = bytes[index].toInt() and 0xFF
            index++
            when {
                // Relleno o byte "stuffed" fuera de un scan: estructura inválida.
                marker == 0x00 -> return corrupt()
                marker == JPEG_EOI -> {
                    output.write(0xFF)
                    output.write(JPEG_EOI)
                    finished = true
                }
                // Marcadores sin longitud (TEM/RSTn fuera de scan): se conservan.
                marker == 0x01 || marker in 0xD0..0xD7 -> {
                    output.write(0xFF)
                    output.write(marker)
                }
                else -> {
                    val segmentEnd = segmentEnd(bytes, index)
                    if (marker == JPEG_SOS) {
                        output.write(bytes, markerStart, segmentEnd - markerStart)
                        index = copyScanData(bytes, segmentEnd, output)
                    } else {
                        if (marker in JPEG_KEPT_MARKERS) {
                            output.write(bytes, markerStart, segmentEnd - markerStart)
                        }
                        index = segmentEnd
                    }
                }
            }
        }
        // Lo que siga al EOI se descarta: también puede transportar datos ajenos.
        return output.toByteArray()
    }

    private fun stripJpeg(
        bytes: ByteBuffer,
        size: Int,
        output: FileChannel,
        checkpoint: () -> Unit,
    ): Boolean {
        if (size < 4 || bytes[0] != MARKER_PREFIX || bytes[1] != JPEG_SOI) return corrupt()
        copyRange(bytes, 0, 2, output, checkpoint)
        var index = 2
        var changed = false
        var finished = false
        while (!finished) {
            checkpoint()
            if (index >= size || bytes[index] != MARKER_PREFIX) return corrupt()
            val markerStart = index
            while (index < size && bytes[index] == MARKER_PREFIX) index++
            if (index >= size) return corrupt()
            val marker = bytes[index].toInt() and 0xFF
            index++
            when {
                marker == 0x00 -> return corrupt()
                marker == JPEG_EOI -> {
                    copyRange(bytes, markerStart, index, output, checkpoint)
                    finished = true
                }
                marker == 0x01 || marker in 0xD0..0xD7 -> {
                    copyRange(bytes, markerStart, index, output, checkpoint)
                }
                else -> {
                    val segmentEnd = segmentEnd(bytes, size, index)
                    if (marker == JPEG_SOS) {
                        copyRange(bytes, markerStart, segmentEnd, output, checkpoint)
                        val scanEnd = findScanEnd(bytes, segmentEnd, size, checkpoint)
                        copyRange(bytes, segmentEnd, scanEnd, output, checkpoint)
                        index = scanEnd
                    } else {
                        if (marker in JPEG_KEPT_MARKERS) {
                            copyRange(bytes, markerStart, segmentEnd, output, checkpoint)
                        } else {
                            changed = true
                        }
                        index = segmentEnd
                    }
                }
            }
        }
        return changed || index != size
    }

    /** Fin de un segmento con longitud ([lengthIndex] apunta a los 2 bytes de longitud). */
    private fun segmentEnd(bytes: ByteArray, lengthIndex: Int): Int {
        if (lengthIndex + 2 > bytes.size) return corrupt()
        val length = ((bytes[lengthIndex].toInt() and 0xFF) shl 8) or
            (bytes[lengthIndex + 1].toInt() and 0xFF)
        if (length < 2 || lengthIndex + length > bytes.size) return corrupt()
        return lengthIndex + length
    }

    private fun segmentEnd(bytes: ByteBuffer, size: Int, lengthIndex: Int): Int {
        if (lengthIndex + 2 > size) return corrupt()
        val length = ((bytes[lengthIndex].toInt() and 0xFF) shl 8) or
            (bytes[lengthIndex + 1].toInt() and 0xFF)
        if (length < 2 || lengthIndex.toLong() + length > size.toLong()) return corrupt()
        return lengthIndex + length
    }

    /**
     * Copia los datos de un scan (entrada entrópica) hasta el próximo marcador real y
     * devuelve el índice del 0xFF que lo inicia. Los bytes 0xFF00 "stuffed" y los RSTn son
     * datos válidos del scan y se copian.
     */
    private fun copyScanData(bytes: ByteArray, startIndex: Int, output: ByteArrayOutputStream): Int {
        var index = startIndex
        while (true) {
            if (index + 1 >= bytes.size) return corrupt()
            val current = bytes[index]
            if (current != MARKER_PREFIX) {
                output.write(current.toInt())
                index++
                continue
            }
            val next = bytes[index + 1].toInt() and 0xFF
            when {
                next == 0x00 -> {
                    output.write(0xFF)
                    output.write(0x00)
                    index += 2
                }
                next == 0xFF -> {
                    output.write(0xFF)
                    index++
                }
                next in 0xD0..0xD7 -> {
                    output.write(0xFF)
                    output.write(next)
                    index += 2
                }
                else -> return index
            }
        }
    }

    private fun findScanEnd(
        bytes: ByteBuffer,
        startIndex: Int,
        size: Int,
        checkpoint: () -> Unit,
    ): Int {
        var index = startIndex
        var nextCheckpoint = startIndex
        while (true) {
            if (index >= nextCheckpoint) {
                checkpoint()
                nextCheckpoint = index + COPY_CHUNK_BYTES
            }
            if (index + 1 >= size) return corrupt()
            if (bytes[index] != MARKER_PREFIX) {
                index++
                continue
            }
            val next = bytes[index + 1].toInt() and 0xFF
            when {
                next == 0x00 || next in 0xD0..0xD7 -> index += 2
                next == 0xFF -> index++
                else -> return index
            }
        }
    }

    // --- PNG ---

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    /** Chunks auxiliares que pueden transportar metadatos y se retiran. */
    private val PNG_DROPPED_CHUNKS: Set<String> = setOf("eXIf", "tEXt", "zTXt", "iTXt", "tIME")

    private fun stripPng(bytes: ByteArray): ByteArray {
        if (bytes.size < PNG_SIGNATURE.size ||
            !PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }
        ) {
            return corrupt()
        }
        val output = ByteArrayOutputStream(bytes.size)
        output.write(bytes, 0, PNG_SIGNATURE.size)
        var index = PNG_SIGNATURE.size
        var sawIhdr = false
        var sawIend = false
        while (!sawIend) {
            if (index + PNG_CHUNK_HEADER_BYTES > bytes.size) return corrupt()
            val length = readBigEndianInt(bytes, index)
            val type = String(bytes, index + 4, 4, Charsets.US_ASCII)
            val chunkEnd = index + PNG_CHUNK_HEADER_BYTES + length + PNG_CHUNK_CRC_BYTES
            if (length < 0 || chunkEnd > bytes.size || chunkEnd < index) return corrupt()
            if (!sawIhdr) {
                if (type != "IHDR") return corrupt()
                sawIhdr = true
            }
            if (type !in PNG_DROPPED_CHUNKS) {
                output.write(bytes, index, chunkEnd - index)
            }
            if (type == "IEND") sawIend = true
            index = chunkEnd
        }
        // Lo que siga a IEND no es estándar y se descarta.
        return output.toByteArray()
    }

    private fun stripPng(
        bytes: ByteBuffer,
        size: Int,
        output: FileChannel,
        checkpoint: () -> Unit,
    ): Boolean {
        if (size < PNG_SIGNATURE.size ||
            !PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }
        ) {
            return corrupt()
        }
        copyRange(bytes, 0, PNG_SIGNATURE.size, output, checkpoint)
        var index = PNG_SIGNATURE.size
        var sawIhdr = false
        var sawIend = false
        var changed = false
        while (!sawIend) {
            checkpoint()
            if (index.toLong() + PNG_CHUNK_HEADER_BYTES > size.toLong()) return corrupt()
            val length = readBigEndianInt(bytes, index)
            val typeOffset = index + 4
            val chunkEnd = index.toLong() + PNG_CHUNK_HEADER_BYTES + length + PNG_CHUNK_CRC_BYTES
            if (length < 0 || chunkEnd > size || chunkEnd < index) return corrupt()
            val isIhdr = asciiAt(bytes, typeOffset, "IHDR")
            if (!sawIhdr) {
                if (!isIhdr) return corrupt()
                sawIhdr = true
            }
            val dropped = PNG_DROPPED_CHUNKS.any { type -> asciiAt(bytes, typeOffset, type) }
            if (dropped) {
                changed = true
            } else {
                copyRange(bytes, index, chunkEnd.toInt(), output, checkpoint)
            }
            sawIend = asciiAt(bytes, typeOffset, "IEND")
            index = chunkEnd.toInt()
        }
        return changed || index != size
    }

    // --- WebP ---

    private fun stripWebp(bytes: ByteArray): ByteArray {
        if (bytes.size < RIFF_HEADER_BYTES ||
            !asciiAt(bytes, 0, "RIFF") ||
            !asciiAt(bytes, 8, "WEBP")
        ) {
            return corrupt()
        }
        val riffEnd = 8 + readLittleEndianInt(bytes, 4)
        if (riffEnd > bytes.size || riffEnd < RIFF_HEADER_BYTES) return corrupt()
        val output = ByteArrayOutputStream(bytes.size)
        output.write(bytes, 0, RIFF_HEADER_BYTES)
        var index = RIFF_HEADER_BYTES
        while (index < riffEnd) {
            if (index + RIFF_CHUNK_HEADER_BYTES > riffEnd) return corrupt()
            val fourcc = String(bytes, index, 4, Charsets.US_ASCII)
            val payloadSize = readLittleEndianInt(bytes, index + 4)
            if (payloadSize < 0) return corrupt()
            val payloadStart = index + RIFF_CHUNK_HEADER_BYTES
            val paddedEnd = payloadStart + payloadSize + (payloadSize and 1)
            if (paddedEnd > riffEnd || paddedEnd < index) return corrupt()
            when (fourcc) {
                "EXIF", "XMP " -> Unit // metadatos retirados
                "VP8X" -> {
                    // Payload fijo de 10 bytes (par, sin relleno): se reescribe el byte de
                    // banderas limpiando EXIF (0x08) y XMP (0x04) al retirar esos chunks.
                    if (payloadSize != VP8X_PAYLOAD_BYTES) return corrupt()
                    output.write(bytes, index, RIFF_CHUNK_HEADER_BYTES)
                    val flags = bytes[payloadStart].toInt() and 0xFF
                    output.write(flags and VP8X_METADATA_FLAGS.inv())
                    output.write(bytes, payloadStart + 1, payloadSize - 1)
                }
                else -> output.write(bytes, index, paddedEnd - index)
            }
            index = paddedEnd
        }
        val stripped = output.toByteArray()
        writeLittleEndianInt(stripped, 4, stripped.size - 8)
        return stripped
    }

    private fun stripWebp(
        bytes: ByteBuffer,
        size: Int,
        output: FileChannel,
        checkpoint: () -> Unit,
    ): Boolean {
        if (size < RIFF_HEADER_BYTES ||
            !asciiAt(bytes, 0, "RIFF") ||
            !asciiAt(bytes, 8, "WEBP")
        ) {
            return corrupt()
        }
        val declaredSize = readLittleEndianInt(bytes, 4)
        val riffEnd = 8L + declaredSize
        if (declaredSize < 0 || riffEnd > size || riffEnd < RIFF_HEADER_BYTES) return corrupt()

        copyRange(bytes, 0, RIFF_HEADER_BYTES, output, checkpoint)
        var index = RIFF_HEADER_BYTES
        var changed = false
        while (index < riffEnd) {
            checkpoint()
            if (index.toLong() + RIFF_CHUNK_HEADER_BYTES > riffEnd) return corrupt()
            val payloadSize = readLittleEndianInt(bytes, index + 4)
            if (payloadSize < 0) return corrupt()
            val payloadStart = index + RIFF_CHUNK_HEADER_BYTES
            val paddedEnd = payloadStart.toLong() + payloadSize + (payloadSize and 1)
            if (paddedEnd > riffEnd || paddedEnd < index) return corrupt()
            when {
                asciiAt(bytes, index, "EXIF") || asciiAt(bytes, index, "XMP ") -> {
                    changed = true
                }
                asciiAt(bytes, index, "VP8X") -> {
                    if (payloadSize != VP8X_PAYLOAD_BYTES) return corrupt()
                    copyRange(bytes, index, payloadStart, output, checkpoint)
                    val flags = bytes[payloadStart].toInt() and 0xFF
                    val cleanFlags = flags and VP8X_METADATA_FLAGS.inv()
                    writeByte(output, cleanFlags)
                    copyRange(bytes, payloadStart + 1, paddedEnd.toInt(), output, checkpoint)
                    if (cleanFlags != flags) changed = true
                }
                else -> copyRange(bytes, index, paddedEnd.toInt(), output, checkpoint)
            }
            index = paddedEnd.toInt()
        }

        val strippedSize = output.position()
        output.position(4L)
        writeLittleEndianInt(output, strippedSize - 8L)
        output.position(strippedSize)
        return changed || riffEnd != size.toLong() || strippedSize != riffEnd
    }

    // --- Utilidades binarias ---

    private fun asciiAt(bytes: ByteArray, offset: Int, expected: String): Boolean =
        expected.indices.all { offset + it < bytes.size && bytes[offset + it] == expected[it].code.toByte() }

    private fun asciiAt(bytes: ByteBuffer, offset: Int, expected: String): Boolean =
        expected.indices.all { offset + it < bytes.limit() && bytes[offset + it] == expected[it].code.toByte() }

    private fun readBigEndianInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun readBigEndianInt(bytes: ByteBuffer, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun readLittleEndianInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun readLittleEndianInt(bytes: ByteBuffer, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun writeLittleEndianInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun writeLittleEndianInt(output: FileChannel, value: Long) {
        if (value !in 0L..Int.MAX_VALUE.toLong()) return corrupt()
        val intValue = value.toInt()
        val encoded = ByteBuffer.wrap(
            byteArrayOf(
                (intValue and 0xFF).toByte(),
                ((intValue ushr 8) and 0xFF).toByte(),
                ((intValue ushr 16) and 0xFF).toByte(),
                ((intValue ushr 24) and 0xFF).toByte(),
            ),
        )
        writeFully(output, encoded)
    }

    private fun writeByte(output: FileChannel, value: Int) {
        writeFully(output, ByteBuffer.wrap(byteArrayOf(value.toByte())))
    }

    private fun copyRange(
        source: ByteBuffer,
        start: Int,
        end: Int,
        output: FileChannel,
        checkpoint: () -> Unit,
    ) {
        if (start < 0 || end < start || end > source.limit()) return corrupt()
        val view = source.duplicate()
        var position = start
        while (position < end) {
            checkpoint()
            val chunkEnd = min(end, position + COPY_CHUNK_BYTES)
            view.position(position)
            view.limit(chunkEnd)
            writeFully(output, view)
            position = chunkEnd
        }
    }

    private fun writeFully(output: FileChannel, buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            if (output.write(buffer) < 0) return corrupt()
        }
    }

    private fun corrupt(): Nothing = throw FileException(FileError.Corrupt)

    private const val MARKER_PREFIX = 0xFF.toByte()
    private const val JPEG_SOI = 0xD8.toByte()
    private const val JPEG_EOI = 0xD9
    private const val JPEG_SOS = 0xDA
    private const val MIME_JPEG = "image/jpeg"
    private const val MIME_PNG = "image/png"
    private const val MIME_WEBP = "image/webp"
    private const val PNG_CHUNK_HEADER_BYTES = 8
    private const val PNG_CHUNK_CRC_BYTES = 4
    private const val RIFF_HEADER_BYTES = 12
    private const val RIFF_CHUNK_HEADER_BYTES = 8
    private const val VP8X_PAYLOAD_BYTES = 10
    private const val VP8X_METADATA_FLAGS = 0x08 or 0x04
    private const val COPY_CHUNK_BYTES = 64 * 1024
}
