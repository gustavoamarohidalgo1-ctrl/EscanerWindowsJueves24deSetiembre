package com.facturastock.app.data.local.codec

import com.facturastock.app.domain.model.InvoiceLineEdit
import com.facturastock.app.domain.model.InvoiceLineEditField
import com.facturastock.app.domain.model.InvoiceLineEditOrigin
import com.facturastock.app.domain.model.InvoiceLineEditValue
import com.facturastock.app.domain.model.InvoiceLineValueSource
import com.facturastock.app.domain.model.InvoiceLinesEdit
import com.facturastock.app.domain.model.InventoryTaxTreatment
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.PurchaseProductProvenance
import com.facturastock.app.domain.model.StagedPurchaseProduct
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.UnitId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

/** Codec binario determinista del snapshot completo del editor de líneas. */
internal object InvoiceLinesEditCodec {
    const val VERSION: Int = 4
    private const val LEGACY_VERSION: Int = 2
    private const val STAGED_PRODUCT_VERSION: Int = 3
    private const val STAGED_SALE_PRICE_VERSION: Int = 4
    const val MAX_PAYLOAD_BYTES: Int = 8 * 1_024 * 1_024

    fun encode(edit: InvoiceLinesEdit): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(VERSION)
            output.writeString(edit.draftId.value, MAX_IDENTIFIER_BYTES)
            output.writeLong(edit.revision)
            output.writeInt(edit.lines.size)
            edit.lines.forEach { line -> output.writeEditLine(line) }
            output.writeLong(edit.updatedAt.toEpochMilli())
        }
        return buffer.toByteArray().also { payload ->
            require(payload.isNotEmpty() && payload.size <= MAX_PAYLOAD_BYTES) {
                "El editor de líneas excede $MAX_PAYLOAD_BYTES bytes serializados"
            }
        }
    }

    @Throws(IOException::class)
    fun decode(payload: ByteArray): InvoiceLinesEdit {
        if (payload.isEmpty() || payload.size > MAX_PAYLOAD_BYTES) {
            throw IOException("Tamaño de editor de líneas inválido: ${payload.size}")
        }
        try {
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                if (input.readInt() != MAGIC) throw IOException("Cabecera de editor inválida")
                val version = input.readInt()
                if (!supports(version)) {
                    throw IOException("Versión de editor no soportada: $version")
                }
                val draftId = DraftId.parse(input.readString(MAX_IDENTIFIER_BYTES))
                    ?: throw IOException("draftId de editor inválido")
                val revision = input.readLong()
                val lineCount = input.readInt()
                if (lineCount !in 0..InvoiceLinesEdit.MAX_RETAINED_LINES) {
                    throw IOException("Cantidad de líneas inválida: $lineCount")
                }
                val lines = List(lineCount) { input.readEditLine(version) }
                val updatedAt = input.readLong()
                if (input.available() != 0) throw IOException("Datos sobrantes en el editor")
                return InvoiceLinesEdit(
                    draftId = draftId,
                    lines = lines,
                    revision = revision,
                    updatedAt = Instant.ofEpochMilli(updatedAt),
                )
            }
        } catch (failure: EOFException) {
            throw IOException("Editor de líneas truncado", failure)
        } catch (failure: IOException) {
            throw failure
        } catch (failure: RuntimeException) {
            throw IOException("Editor de líneas corrupto", failure)
        }
    }

    fun sha256(payload: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(payload)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }

    fun supports(version: Int): Boolean = version in LEGACY_VERSION..VERSION

    private fun DataOutputStream.writeEditLine(line: InvoiceLineEdit) {
        writeString(line.lineId.value, MAX_IDENTIFIER_BYTES)
        writeInt(line.position)
        writeString(line.origin.name, MAX_ENUM_BYTES)
        writeNullableInt(line.sourcePosition)
        writeNullableString(line.ocrRawText, MAX_DESCRIPTION_BYTES)
        writeNullableString(line.linkedProductId?.value, MAX_IDENTIFIER_BYTES)
        writeNullableString(line.linkedUnitId?.value, MAX_IDENTIFIER_BYTES)
        writeNullableInt(line.linkConfidence)
        writeString(line.taxTreatment.name, MAX_ENUM_BYTES)
        writeString(line.productProvenance.name, MAX_ENUM_BYTES)
        writeNullableStagedProduct(line.stagedProduct)
        InvoiceLineEditField.entries.forEach { field ->
            val maxBytes = if (field == InvoiceLineEditField.DESCRIPTION) {
                MAX_DESCRIPTION_BYTES
            } else {
                MAX_SHORT_VALUE_BYTES
            }
            writeValue(line.value(field), maxBytes)
        }
        writeNullableInt(line.confidencePermille)
        val fieldConfidence = line.fieldConfidencePermille.entries
            .sortedBy { it.key.stableOrder }
        writeInt(fieldConfidence.size)
        fieldConfidence.forEach { (field, confidence) ->
            writeString(field.name, MAX_ENUM_BYTES)
            writeInt(confidence)
        }
        writeStrictBoolean(line.requiresReview)
        writeStrictBoolean(line.reviewConfirmedByUser)
        writeFieldSet(line.reviewRequiredFields)
        writeFieldSet(line.touchedFields)
        writeNullableLong(line.deletedAt?.toEpochMilli())
        writeLong(line.createdAt.toEpochMilli())
        writeLong(line.updatedAt.toEpochMilli())
    }

    @Throws(IOException::class)
    private fun DataInputStream.readEditLine(version: Int): InvoiceLineEdit {
        val lineId = LineId.parse(readString(MAX_IDENTIFIER_BYTES))
            ?: throw IOException("lineId de editor inválido")
        val position = readInt()
        val origin = readEnum<InvoiceLineEditOrigin>()
        val sourcePosition = readNullableInt()
        val ocrRawText = readNullableString(MAX_DESCRIPTION_BYTES)
        val linkedProductId = readNullableString(MAX_IDENTIFIER_BYTES)?.let { value ->
            ProductId.parse(value) ?: throw IOException("productId enlazado inválido")
        }
        val linkedUnitId = readNullableString(MAX_IDENTIFIER_BYTES)?.let { value ->
            UnitId.parse(value) ?: throw IOException("unitId enlazado inválido")
        }
        val linkConfidence = readNullableInt()
        val taxTreatment = if (version >= STAGED_PRODUCT_VERSION) {
            readEnum<InventoryTaxTreatment>()
        } else {
            InventoryTaxTreatment.UNKNOWN
        }
        val productProvenance = if (version >= STAGED_PRODUCT_VERSION) {
            readEnum<PurchaseProductProvenance>()
        } else {
            PurchaseProductProvenance.UNKNOWN_LEGACY
        }
        val stagedProduct = if (version >= STAGED_PRODUCT_VERSION) {
            readNullableStagedProduct(version)
        } else {
            null
        }
        val values = InvoiceLineEditField.entries.associateWith { field ->
            val maxBytes = if (field == InvoiceLineEditField.DESCRIPTION) {
                MAX_DESCRIPTION_BYTES
            } else {
                MAX_SHORT_VALUE_BYTES
            }
            readValue(maxBytes)
        }
        val confidencePermille = readNullableInt()
        val fieldConfidenceCount = readInt()
        if (fieldConfidenceCount !in 0..InvoiceLineEditField.entries.size) {
            throw IOException("Cantidad de confianzas inválida: $fieldConfidenceCount")
        }
        val fieldConfidence = buildMap {
            repeat(fieldConfidenceCount) {
                val field = readEnum<InvoiceLineEditField>()
                val confidence = readInt()
                if (put(field, confidence) != null) {
                    throw IOException("Confianza duplicada para $field")
                }
            }
        }
        val requiresReview = readStrictBoolean()
        val reviewConfirmedByUser = readStrictBoolean()
        val reviewRequiredFields = readFieldSet()
        val touchedFields = readFieldSet()
        val deletedAt = readNullableLong()?.let(Instant::ofEpochMilli)
        val createdAt = Instant.ofEpochMilli(readLong())
        val updatedAt = Instant.ofEpochMilli(readLong())
        return InvoiceLineEdit(
            lineId = lineId,
            position = position,
            origin = origin,
            sourcePosition = sourcePosition,
            ocrRawText = ocrRawText,
            linkedProductId = linkedProductId,
            linkedUnitId = linkedUnitId,
            linkConfidence = linkConfidence,
            taxTreatment = taxTreatment,
            productProvenance = productProvenance,
            stagedProduct = stagedProduct,
            description = values.getValue(InvoiceLineEditField.DESCRIPTION),
            code = values.getValue(InvoiceLineEditField.CODE),
            quantity = values.getValue(InvoiceLineEditField.QUANTITY),
            unit = values.getValue(InvoiceLineEditField.UNIT),
            unitCost = values.getValue(InvoiceLineEditField.UNIT_COST),
            discount = values.getValue(InvoiceLineEditField.DISCOUNT),
            igv = values.getValue(InvoiceLineEditField.IGV),
            total = values.getValue(InvoiceLineEditField.TOTAL),
            confidencePermille = confidencePermille,
            fieldConfidencePermille = fieldConfidence,
            requiresReview = requiresReview,
            reviewConfirmedByUser = reviewConfirmedByUser,
            reviewRequiredFields = reviewRequiredFields,
            touchedFields = touchedFields,
            deletedAt = deletedAt,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    private fun DataOutputStream.writeValue(value: InvoiceLineEditValue, maxBytes: Int) {
        writeNullableString(value.ocr, maxBytes)
        writeNullableString(value.ocrRaw, maxBytes)
        writeNullableString(value.calculated, maxBytes)
        writeNullableString(value.written, maxBytes)
        writeNullableString(value.selectedSource?.name, MAX_ENUM_BYTES)
    }

    private fun DataOutputStream.writeNullableStagedProduct(product: StagedPurchaseProduct?) {
        writeByte(if (product == null) 0 else 1)
        if (product == null) return
        writeString(product.productId.value, MAX_IDENTIFIER_BYTES)
        writeString(product.businessId.value, MAX_IDENTIFIER_BYTES)
        writeString(product.unitId.value, MAX_IDENTIFIER_BYTES)
        writeString(product.name, MAX_SHORT_VALUE_BYTES)
        writeNullableString(product.sku, MAX_SHORT_VALUE_BYTES)
        writeNullableString(product.barcode, MAX_SHORT_VALUE_BYTES)
        writeNullableString(product.purchaseUnitId?.value, MAX_IDENTIFIER_BYTES)
        writeNullableString(product.purchaseFactor?.toPlainString(), MAX_DECIMAL_BYTES)
        writeNullableMoney(product.salePrice)
    }

    @Throws(IOException::class)
    private fun DataInputStream.readNullableStagedProduct(version: Int): StagedPurchaseProduct? =
        when (readUnsignedByte()) {
            0 -> null
            1 -> StagedPurchaseProduct(
                productId = ProductId.parse(readString(MAX_IDENTIFIER_BYTES))
                    ?: throw IOException("productId staged invalido"),
                businessId = BusinessId.parse(readString(MAX_IDENTIFIER_BYTES))
                    ?: throw IOException("businessId staged invalido"),
                unitId = UnitId.parse(readString(MAX_IDENTIFIER_BYTES))
                    ?: throw IOException("unitId staged invalido"),
                name = readString(MAX_SHORT_VALUE_BYTES),
                sku = readNullableString(MAX_SHORT_VALUE_BYTES),
                barcode = readNullableString(MAX_SHORT_VALUE_BYTES),
                purchaseUnitId = readNullableString(MAX_IDENTIFIER_BYTES)?.let { value ->
                    UnitId.parse(value) ?: throw IOException("purchaseUnitId staged invalido")
                },
                purchaseFactor = readNullableString(MAX_DECIMAL_BYTES)?.let(::BigDecimal),
                salePrice = if (version >= STAGED_SALE_PRICE_VERSION) readNullableMoney() else null,
            )
            else -> throw IOException("Marcador de producto staged invalido")
        }

    private fun DataOutputStream.writeNullableMoney(value: Money?) {
        writeByte(if (value == null) 0 else 1)
        if (value != null) {
            writeLong(value.minorUnits)
            writeString(value.currency.value, MAX_ENUM_BYTES)
        }
    }

    @Throws(IOException::class)
    private fun DataInputStream.readNullableMoney(): Money? = when (readUnsignedByte()) {
        0 -> null
        1 -> Money.ofMinor(readLong(), CurrencyCode.of(readString(MAX_ENUM_BYTES)))
        else -> throw IOException("Marcador de precio de venta staged invalido")
    }

    @Throws(IOException::class)
    private fun DataInputStream.readValue(maxBytes: Int): InvoiceLineEditValue =
        InvoiceLineEditValue(
            ocr = readNullableString(maxBytes),
            ocrRaw = readNullableString(maxBytes),
            calculated = readNullableString(maxBytes),
            written = readNullableString(maxBytes),
            selectedSource = readNullableString(MAX_ENUM_BYTES)?.let { name ->
                InvoiceLineValueSource.entries.firstOrNull { it.name == name }
                    ?: throw IOException("Procedencia de valor desconocida: $name")
            },
        )

    private fun DataOutputStream.writeFieldSet(fields: Set<InvoiceLineEditField>) {
        val ordered = fields.sortedBy(InvoiceLineEditField::stableOrder)
        writeInt(ordered.size)
        ordered.forEach { writeString(it.name, MAX_ENUM_BYTES) }
    }

    @Throws(IOException::class)
    private fun DataInputStream.readFieldSet(): Set<InvoiceLineEditField> {
        val count = readInt()
        if (count !in 0..InvoiceLineEditField.entries.size) {
            throw IOException("Cantidad de campos inválida: $count")
        }
        return buildSet {
            repeat(count) {
                val field = readEnum<InvoiceLineEditField>()
                if (!add(field)) throw IOException("Campo duplicado: $field")
            }
        }
    }

    @Throws(IOException::class)
    private inline fun <reified T : Enum<T>> DataInputStream.readEnum(): T {
        val name = readString(MAX_ENUM_BYTES)
        return enumValues<T>().firstOrNull { it.name == name }
            ?: throw IOException("${T::class.java.simpleName} desconocido: $name")
    }

    private fun DataOutputStream.writeNullableString(value: String?, maxBytes: Int) {
        writeByte(if (value == null) 0 else 1)
        if (value != null) writeString(value, maxBytes)
    }

    private fun DataOutputStream.writeString(value: String, maxBytes: Int) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= maxBytes) { "Texto demasiado largo para el editor de líneas" }
        writeInt(bytes.size)
        write(bytes)
    }

    @Throws(IOException::class)
    private fun DataInputStream.readNullableString(maxBytes: Int): String? =
        when (readUnsignedByte()) {
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

    private fun DataOutputStream.writeNullableInt(value: Int?) {
        writeByte(if (value == null) 0 else 1)
        if (value != null) writeInt(value)
    }

    @Throws(IOException::class)
    private fun DataInputStream.readNullableInt(): Int? = when (readUnsignedByte()) {
        0 -> null
        1 -> readInt()
        else -> throw IOException("Marcador nullable inválido")
    }

    private fun DataOutputStream.writeNullableLong(value: Long?) {
        writeByte(if (value == null) 0 else 1)
        if (value != null) writeLong(value)
    }

    @Throws(IOException::class)
    private fun DataInputStream.readNullableLong(): Long? = when (readUnsignedByte()) {
        0 -> null
        1 -> readLong()
        else -> throw IOException("Marcador nullable inválido")
    }

    private fun DataOutputStream.writeStrictBoolean(value: Boolean) {
        writeByte(if (value) 1 else 0)
    }

    @Throws(IOException::class)
    private fun DataInputStream.readStrictBoolean(): Boolean = when (readUnsignedByte()) {
        0 -> false
        1 -> true
        else -> throw IOException("Booleano inválido")
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

    private const val MAGIC: Int = 0x494C4544 // ILED
    private const val MAX_IDENTIFIER_BYTES: Int = 64
    private const val MAX_ENUM_BYTES: Int = 64
    private const val MAX_SHORT_VALUE_BYTES: Int = 2_048
    private const val MAX_DECIMAL_BYTES: Int = 256
    private const val MAX_DESCRIPTION_BYTES: Int = 256_000
}
