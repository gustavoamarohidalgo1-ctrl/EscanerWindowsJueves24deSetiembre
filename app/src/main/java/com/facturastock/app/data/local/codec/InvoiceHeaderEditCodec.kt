package com.facturastock.app.data.local.codec

import com.facturastock.app.domain.model.InvoiceHeaderEdit
import com.facturastock.app.domain.model.InvoiceHeaderEditField
import com.facturastock.app.domain.model.id.DraftId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

/** Codec binario determinista del formulario parcial de cabecera. */
internal object InvoiceHeaderEditCodec {
    const val VERSION: Int = 1
    const val MAX_PAYLOAD_BYTES: Int = 16_384

    fun encode(edit: InvoiceHeaderEdit): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(VERSION)
            output.writeString(edit.draftId.value, MAX_IDENTIFIER_BYTES)
            output.writeLong(edit.revision)
            output.writeNullableString(edit.supplierRuc, MAX_SHORT_FIELD_BYTES)
            output.writeNullableString(edit.supplierLegalName, MAX_NAME_BYTES)
            output.writeNullableString(edit.documentType, MAX_SHORT_FIELD_BYTES)
            output.writeNullableString(edit.documentSeries, MAX_SHORT_FIELD_BYTES)
            output.writeNullableString(edit.documentNumber, MAX_SHORT_FIELD_BYTES)
            output.writeNullableString(edit.issueDate, MAX_SHORT_FIELD_BYTES)
            output.writeNullableString(edit.currency, MAX_SHORT_FIELD_BYTES)
            output.writeNullableString(edit.subtotal, MAX_SHORT_FIELD_BYTES)
            output.writeNullableString(edit.igv, MAX_SHORT_FIELD_BYTES)
            output.writeNullableString(edit.otherCharges, MAX_SHORT_FIELD_BYTES)
            output.writeNullableString(edit.total, MAX_SHORT_FIELD_BYTES)
            val touched = edit.touchedFields.sortedBy(InvoiceHeaderEditField::stableOrder)
            output.writeInt(touched.size)
            touched.forEach { field -> output.writeString(field.name, MAX_ENUM_BYTES) }
            output.writeLong(edit.updatedAt.toEpochMilli())
        }
        return buffer.toByteArray().also { payload ->
            require(payload.isNotEmpty() && payload.size <= MAX_PAYLOAD_BYTES) {
                "El formulario excede $MAX_PAYLOAD_BYTES bytes serializados"
            }
        }
    }

    @Throws(IOException::class)
    fun decode(payload: ByteArray): InvoiceHeaderEdit {
        if (payload.isEmpty() || payload.size > MAX_PAYLOAD_BYTES) {
            throw IOException("Tamaño de formulario inválido: ${payload.size}")
        }
        try {
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                if (input.readInt() != MAGIC) throw IOException("Cabecera de formulario inválida")
                val version = input.readInt()
                if (version != VERSION) {
                    throw IOException("Versión de formulario no soportada: $version")
                }
                val draftId = DraftId.parse(input.readString(MAX_IDENTIFIER_BYTES))
                    ?: throw IOException("draftId de formulario inválido")
                val revision = input.readLong()
                val supplierRuc = input.readNullableString(MAX_SHORT_FIELD_BYTES)
                val supplierLegalName = input.readNullableString(MAX_NAME_BYTES)
                val documentType = input.readNullableString(MAX_SHORT_FIELD_BYTES)
                val documentSeries = input.readNullableString(MAX_SHORT_FIELD_BYTES)
                val documentNumber = input.readNullableString(MAX_SHORT_FIELD_BYTES)
                val issueDate = input.readNullableString(MAX_SHORT_FIELD_BYTES)
                val currency = input.readNullableString(MAX_SHORT_FIELD_BYTES)
                val subtotal = input.readNullableString(MAX_SHORT_FIELD_BYTES)
                val igv = input.readNullableString(MAX_SHORT_FIELD_BYTES)
                val otherCharges = input.readNullableString(MAX_SHORT_FIELD_BYTES)
                val total = input.readNullableString(MAX_SHORT_FIELD_BYTES)
                val touchedCount = input.readInt()
                if (touchedCount !in 0..InvoiceHeaderEditField.entries.size) {
                    throw IOException("Cantidad de campos tocados inválida: $touchedCount")
                }
                val touched = buildSet {
                    repeat(touchedCount) {
                        val name = input.readString(MAX_ENUM_BYTES)
                        val field = InvoiceHeaderEditField.entries.firstOrNull { it.name == name }
                            ?: throw IOException("Campo de formulario desconocido: $name")
                        if (!add(field)) throw IOException("Campo de formulario duplicado: $name")
                    }
                }
                val updatedAt = input.readLong()
                if (input.available() != 0) throw IOException("Datos sobrantes en el formulario")
                return InvoiceHeaderEdit(
                    draftId = draftId,
                    supplierRuc = supplierRuc,
                    supplierLegalName = supplierLegalName,
                    documentType = documentType,
                    documentSeries = documentSeries,
                    documentNumber = documentNumber,
                    issueDate = issueDate,
                    currency = currency,
                    subtotal = subtotal,
                    igv = igv,
                    otherCharges = otherCharges,
                    total = total,
                    revision = revision,
                    touchedFields = touched,
                    updatedAt = Instant.ofEpochMilli(updatedAt),
                )
            }
        } catch (failure: EOFException) {
            throw IOException("Formulario truncado", failure)
        } catch (failure: IOException) {
            throw failure
        } catch (failure: RuntimeException) {
            throw IOException("Formulario corrupto", failure)
        }
    }

    fun sha256(payload: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(payload)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }

    private fun DataOutputStream.writeNullableString(value: String?, maxBytes: Int) {
        writeByte(if (value == null) 0 else 1)
        if (value != null) writeString(value, maxBytes)
    }

    private fun DataOutputStream.writeString(value: String, maxBytes: Int) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= maxBytes) { "Texto demasiado largo para el formulario" }
        writeInt(bytes.size)
        write(bytes)
    }

    @Throws(IOException::class)
    private fun DataInputStream.readNullableString(maxBytes: Int): String? = when (readUnsignedByte()) {
        0 -> null
        1 -> readString(maxBytes)
        else -> throw IOException("Marcador nullable inválido")
    }

    @Throws(IOException::class)
    private fun DataInputStream.readString(maxBytes: Int): String {
        val size = readInt()
        if (size !in 0..maxBytes || size > available()) {
            throw IOException("Longitud de texto inválida: $size")
        }
        val bytes = ByteArray(size)
        readFully(bytes)
        return decodeUtf8(bytes)
    }

    @Throws(IOException::class)
    private fun decodeUtf8(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (failure: CharacterCodingException) {
        throw IOException("Texto UTF-8 inválido", failure)
    }

    private const val MAGIC: Int = 0x49484544 // IHED
    private const val MAX_IDENTIFIER_BYTES: Int = 64
    private const val MAX_ENUM_BYTES: Int = 64
    private const val MAX_SHORT_FIELD_BYTES: Int = 256
    private const val MAX_NAME_BYTES: Int = 2_048

}
