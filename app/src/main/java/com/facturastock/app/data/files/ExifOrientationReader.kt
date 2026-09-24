package com.facturastock.app.data.files

import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream

/**
 * Lector mínimo de la etiqueta EXIF `Orientation` (0x0112), en Kotlin puro y sin
 * dependencias, que reemplaza a `androidx.exifinterface` en escritorio.
 *
 * Recorre el contenedor solo hasta encontrar el bloque EXIF y lee únicamente el IFD0 del
 * TIFF embebido; nunca carga píxeles. Formatos cubiertos (los mismos que admite
 * [com.facturastock.app.domain.model.CaptureImagePolicy]):
 *
 * - **JPEG**: segmento APP1 con cabecera `Exif\0\0`, antes del primer SOS.
 * - **PNG**: chunk `eXIf`, antes de `IDAT`.
 * - **WebP**: chunk `EXIF` del contenedor RIFF (con o sin cabecera `Exif\0\0`).
 *
 * Igual que `ExifInterface`, es tolerante con el contenido: una estructura EXIF ausente,
 * truncada o incoherente devuelve [ORIENTATION_NORMAL]. Solo un fallo real de lectura del
 * archivo (no existe, sin permiso, error del medio) se propaga como [IOException].
 */
internal object ExifOrientationReader {
    const val ORIENTATION_UNDEFINED = 0
    const val ORIENTATION_NORMAL = 1
    const val ORIENTATION_FLIP_HORIZONTAL = 2
    const val ORIENTATION_ROTATE_180 = 3
    const val ORIENTATION_FLIP_VERTICAL = 4
    const val ORIENTATION_TRANSPOSE = 5
    const val ORIENTATION_ROTATE_90 = 6
    const val ORIENTATION_TRANSVERSE = 7
    const val ORIENTATION_ROTATE_270 = 8

    /** Orientación EXIF de [file] (1 a 8); [ORIENTATION_NORMAL] si no la declara. */
    @Throws(IOException::class)
    fun readOrientation(file: File): Int =
        file.inputStream().buffered(STREAM_BUFFER_BYTES).use(::readOrientation)

    /** Variante sobre bytes en memoria; nunca lanza por contenido malformado. */
    fun readOrientation(bytes: ByteArray): Int = try {
        readOrientation(bytes.inputStream())
    } catch (_: IOException) {
        ORIENTATION_NORMAL
    }

    @Throws(IOException::class)
    private fun readOrientation(stream: InputStream): Int {
        val input = DataInputStream(stream)
        val tiff = try {
            val signature = ByteArray(SIGNATURE_BYTES)
            val read = input.readUpTo(signature)
            when {
                read >= 2 && signature[0] == 0xFF.toByte() && signature[1] == 0xD8.toByte() ->
                    findJpegExif(input, signature, read)
                read >= PNG_SIGNATURE.size &&
                    signature.copyOf(PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE) ->
                    findPngExif(input, signature, read)
                read >= SIGNATURE_BYTES && signature.ascii(0, 4) == "RIFF" &&
                    signature.ascii(8, 4) == "WEBP" -> findWebpExif(input)
                else -> null
            }
        } catch (_: EOFException) {
            // Contenedor truncado: sin orientación confiable, igual que ExifInterface.
            null
        } ?: return ORIENTATION_NORMAL
        return orientationFromTiff(tiff)
    }

    // --- JPEG ---

    private fun findJpegExif(input: DataInputStream, signature: ByteArray, read: Int): ByteArray? {
        // Los bytes ya consumidos por la firma se reinyectan antes del resto del flujo.
        val stream = DataInputStream(
            SequenceInputStream(signature.inputStream(2, read - 2), input),
        )
        while (true) {
            var marker = stream.readUnsignedByte()
            if (marker != 0xFF) return null
            while (marker == 0xFF) marker = stream.readUnsignedByte()
            when {
                marker == JPEG_SOS || marker == JPEG_EOI -> return null
                marker == 0x01 || marker in 0xD0..0xD7 -> continue
            }
            val length = stream.readUnsignedShort()
            if (length < 2) return null
            val payloadLength = length - 2
            if (marker == JPEG_APP1 && payloadLength >= EXIF_HEADER.size) {
                val payload = ByteArray(payloadLength)
                stream.readFully(payload)
                if (payload.copyOf(EXIF_HEADER.size).contentEquals(EXIF_HEADER)) {
                    return payload.copyOfRange(EXIF_HEADER.size, payload.size)
                }
            } else {
                stream.skipFully(payloadLength.toLong())
            }
        }
    }

    // --- PNG ---

    private fun findPngExif(input: DataInputStream, signature: ByteArray, read: Int): ByteArray? {
        val stream = DataInputStream(
            SequenceInputStream(
                signature.inputStream(PNG_SIGNATURE.size, read - PNG_SIGNATURE.size),
                input,
            ),
        )
        while (true) {
            val length = stream.readInt()
            // PNG limita la longitud a 2^31-1; un valor negativo solo puede ser corrupción.
            if (length < 0) return null
            val typeBytes = ByteArray(4)
            stream.readFully(typeBytes)
            val type = String(typeBytes, Charsets.US_ASCII)
            when (type) {
                "eXIf" -> {
                    if (length > MAX_EXIF_CHUNK_BYTES) return null
                    val payload = ByteArray(length)
                    stream.readFully(payload)
                    return payload
                }
                "IDAT", "IEND" -> return null
                else -> stream.skipFully(length.toLong() + PNG_CRC_BYTES)
            }
        }
    }

    // --- WebP ---

    private fun findWebpExif(input: DataInputStream): ByteArray? {
        while (true) {
            val typeBytes = ByteArray(4)
            input.readFully(typeBytes)
            val type = String(typeBytes, Charsets.US_ASCII)
            val size = Integer.reverseBytes(input.readInt()).toLong() and 0xFFFF_FFFFL
            val padded = size + (size and 1L)
            if (type == "EXIF") {
                if (size > MAX_EXIF_CHUNK_BYTES) return null
                val payload = ByteArray(size.toInt())
                input.readFully(payload)
                // Algunos codificadores anteponen `Exif\0\0`; la especificación no lo usa.
                return if (payload.size >= EXIF_HEADER.size &&
                    payload.copyOf(EXIF_HEADER.size).contentEquals(EXIF_HEADER)
                ) {
                    payload.copyOfRange(EXIF_HEADER.size, payload.size)
                } else {
                    payload
                }
            }
            input.skipFully(padded)
        }
    }

    // --- TIFF ---

    /** Lee `Orientation` del IFD0; cualquier incoherencia devuelve [ORIENTATION_NORMAL]. */
    private fun orientationFromTiff(tiff: ByteArray): Int {
        if (tiff.size < TIFF_HEADER_BYTES) return ORIENTATION_NORMAL
        val littleEndian = when {
            tiff[0] == 'I'.code.toByte() && tiff[1] == 'I'.code.toByte() -> true
            tiff[0] == 'M'.code.toByte() && tiff[1] == 'M'.code.toByte() -> false
            else -> return ORIENTATION_NORMAL
        }
        fun u16(offset: Int): Int {
            val b0 = tiff[offset].toInt() and 0xFF
            val b1 = tiff[offset + 1].toInt() and 0xFF
            return if (littleEndian) b0 or (b1 shl 8) else (b0 shl 8) or b1
        }
        fun u32(offset: Int): Long {
            val high = u16(if (littleEndian) offset + 2 else offset).toLong()
            val low = u16(if (littleEndian) offset else offset + 2).toLong()
            return (high shl 16) or low
        }
        if (u16(2) != TIFF_MAGIC) return ORIENTATION_NORMAL
        val ifdOffset = u32(4)
        if (ifdOffset < TIFF_HEADER_BYTES || ifdOffset + 2 > tiff.size) return ORIENTATION_NORMAL
        val entries = u16(ifdOffset.toInt())
        for (index in 0 until entries) {
            val entry = ifdOffset.toInt() + 2 + index * IFD_ENTRY_BYTES
            if (entry + IFD_ENTRY_BYTES > tiff.size) return ORIENTATION_NORMAL
            if (u16(entry) != TAG_ORIENTATION) continue
            val type = u16(entry + 2)
            val count = u32(entry + 4)
            if (count < 1L) return ORIENTATION_NORMAL
            val value = when (type) {
                TIFF_TYPE_SHORT -> u16(entry + 8)
                TIFF_TYPE_LONG -> u32(entry + 8).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                else -> return ORIENTATION_NORMAL
            }
            return if (value in ORIENTATION_NORMAL..ORIENTATION_ROTATE_270) {
                value
            } else {
                ORIENTATION_NORMAL
            }
        }
        return ORIENTATION_NORMAL
    }

    private fun DataInputStream.readUpTo(destination: ByteArray): Int {
        var total = 0
        while (total < destination.size) {
            val count = read(destination, total, destination.size - total)
            if (count < 0) break
            total += count
        }
        return total
    }

    private fun InputStream.skipFully(count: Long) {
        var remaining = count
        while (remaining > 0L) {
            val skipped = skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                if (read() < 0) throw EOFException()
                remaining--
            }
        }
    }

    private fun ByteArray.ascii(offset: Int, length: Int): String =
        String(this, offset, length, Charsets.US_ASCII)

    private const val STREAM_BUFFER_BYTES = 8 * 1024
    private const val SIGNATURE_BYTES = 12
    private const val JPEG_APP1 = 0xE1
    private const val JPEG_SOS = 0xDA
    private const val JPEG_EOI = 0xD9
    private const val PNG_CRC_BYTES = 4
    private const val MAX_EXIF_CHUNK_BYTES = 1024 * 1024
    private const val TIFF_HEADER_BYTES = 8
    private const val TIFF_MAGIC = 42
    private const val IFD_ENTRY_BYTES = 12
    private const val TAG_ORIENTATION = 0x0112
    private const val TIFF_TYPE_SHORT = 3
    private const val TIFF_TYPE_LONG = 4
    private val EXIF_HEADER = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00)
    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )
}
