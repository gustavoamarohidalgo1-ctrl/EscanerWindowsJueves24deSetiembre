package com.facturastock.app.data.local.codec

import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.InventoryTaxEvidence
import com.facturastock.app.domain.model.InventoryTaxEvidenceType
import com.facturastock.app.domain.model.InventoryTaxTreatment
import com.facturastock.app.domain.model.PreparedPurchase
import com.facturastock.app.domain.model.PreparedPurchaseLine
import com.facturastock.app.domain.model.PurchaseDocumentType
import com.facturastock.app.domain.model.PurchaseReconciliationAdjustment
import com.facturastock.app.domain.model.PurchaseProductProvenance
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.UnitCost
import com.facturastock.app.domain.model.StagedPurchaseProduct
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.SupplierId
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
import java.time.LocalDate

/** Codec binario determinista de la instantánea de compra preparada. */
internal object PreparedPurchaseCodec {
    const val VERSION: Int = 6
    private const val MIN_SUPPORTED_VERSION: Int = 2
    private const val RECONCILIATION_VERSION: Int = 3
    private const val TAX_DECISION_VERSION: Int = 4
    private const val CANONICAL_HASH_VERSION: Int = 5
    private const val STAGED_SALE_PRICE_VERSION: Int = 6
    const val MAX_PAYLOAD_BYTES: Int = 8 * 1_024 * 1_024
    private const val MAX_LINES: Int = 500
    private const val MAX_WARNINGS: Int = 64

    fun encode(purchase: PreparedPurchase): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { output ->
            output.writeInt(MAGIC)
            output.writeInt(VERSION)
            output.writeString(purchase.draftId.value, MAX_IDENTIFIER_BYTES)
            output.writeString(purchase.businessId.value, MAX_IDENTIFIER_BYTES)
            output.writeNullableString(purchase.supplierId?.value, MAX_IDENTIFIER_BYTES)
            output.writeString(purchase.supplierRuc, MAX_TEXT_BYTES)
            output.writeNullableString(purchase.supplierLegalName, MAX_TEXT_BYTES)
            output.writeNullableString(purchase.documentType?.name, MAX_ENUM_BYTES)
            output.writeString(purchase.documentNumber, MAX_TEXT_BYTES)
            output.writeLong(purchase.issueDate.toEpochDay())
            output.writeString(purchase.currency.value, MAX_ENUM_BYTES)
            output.writeInt(purchase.lines.size)
            purchase.lines.forEach { line -> output.writeLine(line) }
            output.writeNullableMoney(purchase.subtotal)
            output.writeNullableMoney(purchase.tax)
            output.writeNullableMoney(purchase.otherCharges)
            output.writeMoney(purchase.total)
            output.writeInt(purchase.acceptedWarnings.size)
            purchase.acceptedWarnings.forEach { output.writeString(it, MAX_ENUM_BYTES) }
            output.writeNullableReconciliationAdjustment(purchase.reconciliationAdjustment)
            output.writeString(purchase.logicalHash, MAX_HASH_BYTES)
            output.writeLong(purchase.preparedAt.toEpochMilli())
        }
        return buffer.toByteArray().also { payload ->
            require(payload.isNotEmpty() && payload.size <= MAX_PAYLOAD_BYTES) {
                "La compra preparada excede $MAX_PAYLOAD_BYTES bytes serializados"
            }
        }
    }

    @Throws(IOException::class)
    fun decode(payload: ByteArray): PreparedPurchase {
        if (payload.isEmpty() || payload.size > MAX_PAYLOAD_BYTES) {
            throw IOException("Tamaño de compra preparada inválido: ${payload.size}")
        }
        try {
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                if (input.readInt() != MAGIC) throw IOException("Cabecera de compra inválida")
                val version = input.readInt()
                if (!supports(version)) {
                    throw IOException("Versión de compra preparada no soportada: $version")
                }
                val draftId = DraftId.parse(input.readString(MAX_IDENTIFIER_BYTES))
                    ?: throw IOException("draftId inválido")
                val businessId = BusinessId.parse(input.readString(MAX_IDENTIFIER_BYTES))
                    ?: throw IOException("businessId inválido")
                val supplierId = input.readNullableString(MAX_IDENTIFIER_BYTES)?.let { value ->
                    SupplierId.parse(value) ?: throw IOException("supplierId inválido")
                }
                val supplierRuc = input.readString(MAX_TEXT_BYTES)
                val supplierLegalName = input.readNullableString(MAX_TEXT_BYTES)
                val documentType = input.readNullableString(MAX_ENUM_BYTES)?.let { name ->
                    PurchaseDocumentType.entries.firstOrNull { it.name == name }
                        ?: throw IOException("Tipo de documento desconocido: $name")
                }
                val documentNumber = input.readString(MAX_TEXT_BYTES)
                val issueDate = LocalDate.ofEpochDay(input.readLong())
                val currency = CurrencyCode.of(input.readString(MAX_ENUM_BYTES))
                val lineCount = input.readInt()
                if (lineCount !in 1..MAX_LINES) {
                    throw IOException("Cantidad de líneas inválida: $lineCount")
                }
                val lines = List(lineCount) { input.readLine(currency, version) }
                val subtotal = input.readNullableMoney()
                val tax = input.readNullableMoney()
                val otherCharges = input.readNullableMoney()
                val total = input.readMoney()
                val warningCount = input.readInt()
                if (warningCount !in 0..MAX_WARNINGS) {
                    throw IOException("Cantidad de advertencias inválida: $warningCount")
                }
                val acceptedWarnings = List(warningCount) { input.readString(MAX_ENUM_BYTES) }
                val reconciliationAdjustment = if (version >= RECONCILIATION_VERSION) {
                    input.readNullableReconciliationAdjustment()
                } else {
                    null
                }
                val logicalHash = input.readString(MAX_HASH_BYTES)
                val preparedAt = Instant.ofEpochMilli(input.readLong())
                if (input.available() != 0) throw IOException("Datos sobrantes en la compra")
                val purchase = PreparedPurchase(
                    draftId = draftId,
                    businessId = businessId,
                    supplierId = supplierId,
                    supplierRuc = supplierRuc,
                    supplierLegalName = supplierLegalName,
                    documentType = documentType,
                    documentNumber = documentNumber,
                    issueDate = issueDate,
                    currency = currency,
                    lines = lines,
                    subtotal = subtotal,
                    tax = tax,
                    otherCharges = otherCharges,
                    total = total,
                    acceptedWarnings = acceptedWarnings,
                    logicalHash = logicalHash,
                    preparedAt = preparedAt,
                    reconciliationAdjustment = reconciliationAdjustment,
                )
                val expectedLogicalHash = when {
                    version >= STAGED_SALE_PRICE_VERSION -> PreparedPurchase.logicalHash(
                        draftId = draftId,
                        businessId = businessId,
                        supplierId = supplierId,
                        supplierRuc = supplierRuc,
                        supplierLegalName = supplierLegalName,
                        documentType = documentType,
                        documentNumber = documentNumber,
                        issueDate = issueDate,
                        currency = currency,
                        lines = lines,
                        subtotal = subtotal,
                        tax = tax,
                        otherCharges = otherCharges,
                        total = total,
                        acceptedWarnings = acceptedWarnings,
                        reconciliationAdjustment = reconciliationAdjustment,
                    )
                    version >= CANONICAL_HASH_VERSION -> PreparedPurchase.legacyV5LogicalHash(
                        draftId = draftId,
                        businessId = businessId,
                        supplierId = supplierId,
                        supplierRuc = supplierRuc,
                        supplierLegalName = supplierLegalName,
                        documentType = documentType,
                        documentNumber = documentNumber,
                        issueDate = issueDate,
                        currency = currency,
                        lines = lines,
                        subtotal = subtotal,
                        tax = tax,
                        otherCharges = otherCharges,
                        total = total,
                        acceptedWarnings = acceptedWarnings,
                        reconciliationAdjustment = reconciliationAdjustment,
                    )
                    version >= TAX_DECISION_VERSION -> PreparedPurchase.legacyV4LogicalHash(
                        draftId = draftId,
                        businessId = businessId,
                        supplierId = supplierId,
                        supplierRuc = supplierRuc,
                        supplierLegalName = supplierLegalName,
                        documentType = documentType,
                        documentNumber = documentNumber,
                        issueDate = issueDate,
                        currency = currency,
                        lines = lines,
                        subtotal = subtotal,
                        tax = tax,
                        otherCharges = otherCharges,
                        total = total,
                        acceptedWarnings = acceptedWarnings,
                        reconciliationAdjustment = reconciliationAdjustment,
                    )
                    else -> PreparedPurchase.legacyLogicalHash(
                        draftId = draftId,
                        businessId = businessId,
                        supplierId = supplierId,
                        supplierRuc = supplierRuc,
                        supplierLegalName = supplierLegalName,
                        documentType = documentType,
                        documentNumber = documentNumber,
                        issueDate = issueDate,
                        currency = currency,
                        lines = lines,
                        subtotal = subtotal,
                        tax = tax,
                        otherCharges = otherCharges,
                        total = total,
                        acceptedWarnings = acceptedWarnings,
                        reconciliationAdjustment = reconciliationAdjustment,
                    )
                }
                if (logicalHash != expectedLogicalHash) {
                    throw IOException("Hash lógico de compra preparada inconsistente")
                }
                return purchase
            }
        } catch (failure: EOFException) {
            throw IOException("Compra preparada truncada", failure)
        } catch (failure: IOException) {
            throw failure
        } catch (failure: RuntimeException) {
            throw IOException("Compra preparada corrupta", failure)
        }
    }

    fun sha256(payload: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(payload)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }

    fun supports(version: Int): Boolean = version in MIN_SUPPORTED_VERSION..VERSION

    private fun DataOutputStream.writeLine(line: PreparedPurchaseLine) {
        writeString(line.lineId.value, MAX_IDENTIFIER_BYTES)
        writeInt(line.position)
        writeString(line.productId.value, MAX_IDENTIFIER_BYTES)
        writeString(line.unitId.value, MAX_IDENTIFIER_BYTES)
        writeString(line.description, MAX_DESCRIPTION_BYTES)
        writeString(line.rawText, MAX_DESCRIPTION_BYTES)
        writeString(line.quantity.value.toPlainString(), MAX_DECIMAL_BYTES)
        writeNullableString(line.unitCost?.amount?.toPlainString(), MAX_DECIMAL_BYTES)
        writeNullableMoney(line.discount)
        writeNullableMoney(line.tax)
        writeNullableMoney(line.lineTotal)
        writeNullableInt(line.linkConfidence)
        writeString(line.taxTreatment.name, MAX_ENUM_BYTES)
        writeString(line.taxEvidence.type.name, MAX_ENUM_BYTES)
        writeNullableString(line.taxEvidence.value?.toPlainString(), MAX_DECIMAL_BYTES)
        writeString(line.productProvenance.name, MAX_ENUM_BYTES)
        writeNullableStagedProduct(line.stagedProduct)
    }

    @Throws(IOException::class)
    private fun DataInputStream.readLine(
        currency: CurrencyCode,
        version: Int,
    ): PreparedPurchaseLine {
        val lineId = LineId.parse(readString(MAX_IDENTIFIER_BYTES))
            ?: throw IOException("lineId inválido")
        val position = readInt()
        val productId = ProductId.parse(readString(MAX_IDENTIFIER_BYTES))
            ?: throw IOException("productId inválido")
        val unitId = UnitId.parse(readString(MAX_IDENTIFIER_BYTES))
            ?: throw IOException("unitId inválido")
        val description = readString(MAX_DESCRIPTION_BYTES)
        val rawText = readString(MAX_DESCRIPTION_BYTES)
        val quantity = Quantity.of(readString(MAX_DECIMAL_BYTES))
        val unitCost = readNullableString(MAX_DECIMAL_BYTES)
            ?.let { UnitCost.of(BigDecimal(it), currency) }
        val discount = readNullableMoney()
        val tax = readNullableMoney()
        val lineTotal = readNullableMoney()
        val linkConfidence = readNullableInt()
        val taxTreatment: InventoryTaxTreatment
        val taxEvidence: InventoryTaxEvidence
        val productProvenance: PurchaseProductProvenance
        val stagedProduct: StagedPurchaseProduct?
        if (version >= TAX_DECISION_VERSION) {
            taxTreatment = readEnum()
            val evidenceType = readEnum<InventoryTaxEvidenceType>()
            val evidenceValue = readNullableString(MAX_DECIMAL_BYTES)?.let(::BigDecimal)
            taxEvidence = when (evidenceType) {
                InventoryTaxEvidenceType.NONE -> {
                    if (evidenceValue != null) throw IOException("Evidencia NONE con valor")
                    InventoryTaxEvidence.None
                }
                InventoryTaxEvidenceType.EXPLICIT_AMOUNT -> InventoryTaxEvidence.ExplicitAmount(
                    evidenceValue ?: throw IOException("Evidencia de importe sin valor"),
                )
                InventoryTaxEvidenceType.EXPLICIT_RATE -> InventoryTaxEvidence.ExplicitRate(
                    evidenceValue ?: throw IOException("Evidencia de tasa sin valor"),
                )
            }
            productProvenance = readEnum()
            stagedProduct = readNullableStagedProduct(version)
        } else {
            taxTreatment = InventoryTaxTreatment.UNKNOWN
            taxEvidence = InventoryTaxEvidence.None
            productProvenance = PurchaseProductProvenance.UNKNOWN_LEGACY
            stagedProduct = null
        }
        return PreparedPurchaseLine(
            lineId = lineId,
            position = position,
            productId = productId,
            unitId = unitId,
            description = description,
            rawText = rawText,
            quantity = quantity,
            unitCost = unitCost,
            discount = discount,
            tax = tax,
            lineTotal = lineTotal,
            linkConfidence = linkConfidence,
            taxTreatment = taxTreatment,
            taxEvidence = taxEvidence,
            productProvenance = productProvenance,
            stagedProduct = stagedProduct,
        )
    }

    private fun DataOutputStream.writeNullableStagedProduct(product: StagedPurchaseProduct?) {
        writeByte(if (product == null) 0 else 1)
        if (product == null) return
        writeString(product.productId.value, MAX_IDENTIFIER_BYTES)
        writeString(product.businessId.value, MAX_IDENTIFIER_BYTES)
        writeString(product.unitId.value, MAX_IDENTIFIER_BYTES)
        writeString(product.name, MAX_TEXT_BYTES)
        writeNullableString(product.sku, MAX_TEXT_BYTES)
        writeNullableString(product.barcode, MAX_TEXT_BYTES)
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
                name = readString(MAX_TEXT_BYTES),
                sku = readNullableString(MAX_TEXT_BYTES),
                barcode = readNullableString(MAX_TEXT_BYTES),
                purchaseUnitId = readNullableString(MAX_IDENTIFIER_BYTES)?.let { value ->
                    UnitId.parse(value) ?: throw IOException("purchaseUnitId staged invalido")
                },
                purchaseFactor = readNullableString(MAX_DECIMAL_BYTES)?.let(::BigDecimal),
                salePrice = if (version >= STAGED_SALE_PRICE_VERSION) {
                    readNullableMoney()
                } else {
                    null
                },
            )
            else -> throw IOException("Marcador de producto staged invalido")
        }

    @Throws(IOException::class)
    private inline fun <reified T : Enum<T>> DataInputStream.readEnum(): T {
        val name = readString(MAX_ENUM_BYTES)
        return enumValues<T>().firstOrNull { it.name == name }
            ?: throw IOException("${T::class.java.simpleName} desconocido: $name")
    }

    private fun DataOutputStream.writeMoney(money: Money) {
        writeLong(money.minorUnits)
        writeString(money.currency.value, MAX_ENUM_BYTES)
    }

    private fun DataOutputStream.writeNullableMoney(money: Money?) {
        writeByte(if (money == null) 0 else 1)
        if (money != null) writeMoney(money)
    }

    private fun DataOutputStream.writeNullableReconciliationAdjustment(
        adjustment: PurchaseReconciliationAdjustment?,
    ) {
        writeByte(if (adjustment == null) 0 else 1)
        if (adjustment != null) {
            writeMoney(adjustment.amount)
            writeString(adjustment.reason, MAX_REASON_BYTES)
        }
    }

    @Throws(IOException::class)
    private fun DataInputStream.readMoney(): Money {
        val minorUnits = readLong()
        val currency = CurrencyCode.of(readString(MAX_ENUM_BYTES))
        return Money.ofMinor(minorUnits, currency)
    }

    @Throws(IOException::class)
    private fun DataInputStream.readNullableMoney(): Money? = when (readUnsignedByte()) {
        0 -> null
        1 -> readMoney()
        else -> throw IOException("Marcador nullable inválido")
    }

    @Throws(IOException::class)
    private fun DataInputStream.readNullableReconciliationAdjustment(): PurchaseReconciliationAdjustment? =
        when (readUnsignedByte()) {
            0 -> null
            1 -> PurchaseReconciliationAdjustment(
                amount = readMoney(),
                reason = readString(MAX_REASON_BYTES),
            )
            else -> throw IOException("Marcador de ajuste inválido")
        }

    private fun DataOutputStream.writeNullableString(value: String?, maxBytes: Int) {
        writeByte(if (value == null) 0 else 1)
        if (value != null) writeString(value, maxBytes)
    }

    private fun DataOutputStream.writeString(value: String, maxBytes: Int) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= maxBytes) { "Texto demasiado largo para la compra preparada" }
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

    private const val MAGIC: Int = 0x50504348 // PPCH
    private const val MAX_IDENTIFIER_BYTES: Int = 64
    private const val MAX_ENUM_BYTES: Int = 64
    private const val MAX_TEXT_BYTES: Int = 1_024
    private const val MAX_DECIMAL_BYTES: Int = 256
    private const val MAX_DESCRIPTION_BYTES: Int = 256_000
    private const val MAX_HASH_BYTES: Int = 64
    private const val MAX_REASON_BYTES: Int = 2_048
}
