package com.facturastock.app.data.local.codec

import com.facturastock.app.domain.model.InvoiceTextBlock
import com.facturastock.app.domain.model.InvoiceTextBoundingBox
import com.facturastock.app.domain.model.InvoiceTextElement
import com.facturastock.app.domain.model.InvoiceTextGeometry
import com.facturastock.app.domain.model.InvoiceTextLine
import com.facturastock.app.domain.model.InvoiceTextPage
import com.facturastock.app.domain.model.InvoiceTextPoint
import com.facturastock.app.domain.model.id.ImageId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Codec binario determinista. Cada BLOB contiene exactamente una página y su propia versión. */
internal object InvoiceOcrPageCodec {
    const val VERSION = 1

    fun encode(page: InvoiceTextPage): ByteArray {
        val buffer = BoundedByteArrayOutputStream(MAX_PAYLOAD_BYTES)
        DataOutputStream(buffer).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(VERSION)
            output.writeBoundedString(page.sourceImageId.value, MAX_IDENTIFIER_BYTES)
            output.writeInt(page.pageIndex)
            output.writeInt(page.widthPx)
            output.writeInt(page.heightPx)
            output.writeBoundedString(page.text, MAX_TEXT_BYTES)
            output.writeBoundedCount(page.blocks.size, "blocks")
            page.blocks.forEach { block -> output.writeOcrBlock(block) }
        }
        return buffer.toByteArray().also { payload ->
            require(payload.size <= MAX_PAYLOAD_BYTES) {
                "La página OCR excede $MAX_PAYLOAD_BYTES bytes serializados"
            }
        }
    }

    @Throws(IOException::class)
    fun decode(payload: ByteArray): InvoiceTextPage {
        if (payload.isEmpty() || payload.size > MAX_PAYLOAD_BYTES) {
            throw IOException("Tamaño de página OCR inválido: ${payload.size}")
        }
        try {
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                if (input.readInt() != MAGIC) throw IOException("Cabecera OCR inválida")
                val version = input.readInt()
                if (version != VERSION) throw IOException("Versión OCR no soportada: $version")
                val imageId = ImageId.parse(input.readBoundedString(MAX_IDENTIFIER_BYTES))
                    ?: throw IOException("sourceImageId OCR inválido")
                val page = InvoiceTextPage(
                    sourceImageId = imageId,
                    pageIndex = input.readInt(),
                    widthPx = input.readInt(),
                    heightPx = input.readInt(),
                    text = input.readBoundedString(MAX_TEXT_BYTES),
                    blocks = List(input.readBoundedCount("blocks")) { input.readOcrBlock() },
                )
                if (input.available() != 0) throw IOException("Datos sobrantes en la página OCR")
                return page
            }
        } catch (failure: IOException) {
            throw failure
        } catch (failure: RuntimeException) {
            throw IOException("Página OCR corrupta", failure)
        }
    }

    fun sha256(payload: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(payload)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }

    private fun DataOutputStream.writeOcrBlock(block: InvoiceTextBlock) {
        writeInt(block.position)
        writeBoundedString(block.text, MAX_TEXT_BYTES)
        writeNullableString(block.languageTag, MAX_LANGUAGE_TAG_BYTES)
        writeGeometry(block.geometry)
        writeBoundedCount(block.lines.size, "lines")
        block.lines.forEach { line -> writeOcrLine(line) }
    }

    private fun DataInputStream.readOcrBlock(): InvoiceTextBlock = InvoiceTextBlock(
        position = readInt(),
        text = readBoundedString(MAX_TEXT_BYTES),
        languageTag = readNullableString(MAX_LANGUAGE_TAG_BYTES),
        geometry = readGeometry(),
        lines = List(readBoundedCount("lines")) { readOcrLine() },
    )

    private fun DataOutputStream.writeOcrLine(line: InvoiceTextLine) {
        writeInt(line.position)
        writeBoundedString(line.text, MAX_TEXT_BYTES)
        writeNullableString(line.languageTag, MAX_LANGUAGE_TAG_BYTES)
        writeGeometry(line.geometry)
        writeNullableInt(line.confidencePermille)
        writeNullableInt(line.clockwiseAngleTenths)
        writeBoundedCount(line.elements.size, "elements")
        line.elements.forEach { element -> writeOcrElement(element) }
    }

    private fun DataInputStream.readOcrLine(): InvoiceTextLine = InvoiceTextLine(
        position = readInt(),
        text = readBoundedString(MAX_TEXT_BYTES),
        languageTag = readNullableString(MAX_LANGUAGE_TAG_BYTES),
        geometry = readGeometry(),
        confidencePermille = readNullableInt(),
        clockwiseAngleTenths = readNullableInt(),
        elements = List(readBoundedCount("elements")) { readOcrElement() },
    )

    private fun DataOutputStream.writeOcrElement(element: InvoiceTextElement) {
        writeInt(element.position)
        writeBoundedString(element.text, MAX_TEXT_BYTES)
        writeNullableString(element.languageTag, MAX_LANGUAGE_TAG_BYTES)
        writeGeometry(element.geometry)
        writeNullableInt(element.confidencePermille)
        writeNullableInt(element.clockwiseAngleTenths)
    }

    private fun DataInputStream.readOcrElement(): InvoiceTextElement = InvoiceTextElement(
        position = readInt(),
        text = readBoundedString(MAX_TEXT_BYTES),
        languageTag = readNullableString(MAX_LANGUAGE_TAG_BYTES),
        geometry = readGeometry(),
        confidencePermille = readNullableInt(),
        clockwiseAngleTenths = readNullableInt(),
    )

    private fun DataOutputStream.writeGeometry(geometry: InvoiceTextGeometry) {
        val box = geometry.boundingBox
        writeBoolean(box != null)
        if (box != null) {
            writeInt(box.leftPx)
            writeInt(box.topPx)
            writeInt(box.rightPx)
            writeInt(box.bottomPx)
        }
        writeInt(geometry.cornerPoints.size)
        geometry.cornerPoints.forEach { point ->
            writeInt(point.xPx)
            writeInt(point.yPx)
        }
    }

    private fun DataInputStream.readGeometry(): InvoiceTextGeometry {
        val box = if (readBoolean()) {
            InvoiceTextBoundingBox(
                leftPx = readInt(),
                topPx = readInt(),
                rightPx = readInt(),
                bottomPx = readInt(),
            )
        } else {
            null
        }
        val cornerCount = readInt()
        if (cornerCount != 0 && cornerCount != CORNER_COUNT) {
            throw IOException("Cantidad de esquinas OCR inválida: $cornerCount")
        }
        return InvoiceTextGeometry(
            boundingBox = box,
            cornerPoints = List(cornerCount) {
                InvoiceTextPoint(xPx = readInt(), yPx = readInt())
            },
        )
    }

    private fun DataOutputStream.writeBoundedString(value: String, maximumBytes: Int) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= maximumBytes) { "Texto OCR demasiado largo: ${bytes.size} bytes" }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readBoundedString(maximumBytes: Int): String {
        val size = readInt()
        if (size !in 0..maximumBytes) throw IOException("Longitud OCR inválida: $size")
        return ByteArray(size).also(::readFully).toString(StandardCharsets.UTF_8)
    }

    private fun DataOutputStream.writeNullableString(value: String?, maximumBytes: Int) {
        if (value == null) {
            writeInt(NULL_LENGTH)
        } else {
            writeBoundedString(value, maximumBytes)
        }
    }

    private fun DataInputStream.readNullableString(maximumBytes: Int): String? {
        val size = readInt()
        if (size == NULL_LENGTH) return null
        if (size !in 0..maximumBytes) throw IOException("Longitud OCR inválida: $size")
        return ByteArray(size).also(::readFully).toString(StandardCharsets.UTF_8)
    }

    private fun DataOutputStream.writeNullableInt(value: Int?) {
        writeBoolean(value != null)
        if (value != null) writeInt(value)
    }

    private fun DataInputStream.readNullableInt(): Int? = if (readBoolean()) readInt() else null

    private fun DataOutputStream.writeBoundedCount(value: Int, field: String) {
        require(value in 0..MAX_REGIONS_PER_COLLECTION) { "$field OCR fuera de límite: $value" }
        writeInt(value)
    }

    private fun DataInputStream.readBoundedCount(field: String): Int = readInt().also { value ->
        if (value !in 0..MAX_REGIONS_PER_COLLECTION) {
            throw IOException("$field OCR fuera de límite: $value")
        }
    }

    private const val MAGIC = 0x46534F50 // "FSOP"
    private const val NULL_LENGTH = -1
    private const val CORNER_COUNT = 4
    private const val MAX_IDENTIFIER_BYTES = 64
    private const val MAX_LANGUAGE_TAG_BYTES = 128
    private const val MAX_TEXT_BYTES = 1_000_000
    private const val MAX_REGIONS_PER_COLLECTION = 20_000
    private const val MAX_PAYLOAD_BYTES = 1_500_000
}

/** Impide que una jerarquía patológica consuma memoria sin límite antes del chequeo final. */
private class BoundedByteArrayOutputStream(
    private val maximumBytes: Int,
) : ByteArrayOutputStream() {
    override fun write(value: Int) {
        require(count < maximumBytes) { "La página OCR excede $maximumBytes bytes serializados" }
        super.write(value)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(length <= maximumBytes - count) {
            "La página OCR excede $maximumBytes bytes serializados"
        }
        super.write(bytes, offset, length)
    }
}
