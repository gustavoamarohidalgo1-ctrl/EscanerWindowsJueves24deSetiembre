package com.facturastock.app.domain.model

import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.SupplierId
import com.facturastock.app.domain.model.id.UnitId
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate

/** Motivo por el que un borrador no puede prepararse; se enumeran todos antes de publicar. */
enum class PrepareBlockerCode {
    HEADER_MISSING,
    HEADER_INVALID,
    CREDIT_NOTE_UNSUPPORTED,
    NO_LINES,
    LINE_DESCRIPTION_MISSING,
    LINE_QUANTITY_INVALID,
    LINE_AMOUNT_INVALID,
    LINE_REVIEW_PENDING,
    LINE_TAX_DECISION_REQUIRED,
    LINE_PRODUCT_MISSING,
    LINE_PRODUCT_INVALID,
    LINE_PRODUCT_PROVENANCE_REQUIRED,
    LINE_SALE_PRICE_REQUIRED,
    ROUNDING_ACCEPTANCE_REQUIRED,
    ADJUSTMENT_REASON_REQUIRED,
}

/** Bloqueo puntual; [lineId] identifica la línea cuando el bloqueo es de línea. */
data class PrepareBlocker(
    val code: PrepareBlockerCode,
    val lineId: LineId? = null,
    val detail: String? = null,
)

/**
 * Línea congelada de una compra preparada: cantidades y montos exactos con el producto y la
 * unidad ya resueltos. La descripción conserva el texto revisado por el usuario.
 */
data class PreparedPurchaseLine(
    val lineId: LineId,
    val position: Int,
    val productId: ProductId,
    val unitId: UnitId,
    val description: String,
    /** Texto de origen sin normalizar; para una línea manual coincide con su descripción. */
    val rawText: String = description,
    val quantity: Quantity,
    val unitCost: UnitCost? = null,
    val discount: Money? = null,
    val tax: Money? = null,
    val lineTotal: Money? = null,
    val linkConfidence: Int? = null,
    val taxTreatment: InventoryTaxTreatment = InventoryTaxTreatment.UNKNOWN,
    val taxEvidence: InventoryTaxEvidence = InventoryTaxEvidence.None,
    val productProvenance: PurchaseProductProvenance = PurchaseProductProvenance.UNKNOWN_LEGACY,
    val stagedProduct: StagedPurchaseProduct? = null,
) {
    init {
        require(position >= 0) { "position no puede ser negativa: $position" }
        require(description.isNotBlank()) { "La línea preparada necesita descripción" }
        require(rawText.isNotBlank() && rawText.length <= 64_000) {
            "La línea preparada necesita texto de origen válido"
        }
        require(linkConfidence == null || linkConfidence in 0..1_000) {
            "linkConfidence fuera de 0..1000: $linkConfidence"
        }
        require(
            stagedProduct == null ||
                (
                    productProvenance == PurchaseProductProvenance.CREATED_IN_DRAFT &&
                        stagedProduct.productId == productId &&
                        unitId in setOfNotNull(stagedProduct.unitId, stagedProduct.purchaseUnitId)
                    )
        ) { "El producto staged no coincide con la linea preparada" }
        require(
            productProvenance != PurchaseProductProvenance.CREATED_IN_DRAFT || stagedProduct != null
        ) { "CREATED_IN_DRAFT exige producto staged" }
    }
}

/**
 * Decisión humana que concilia la suma revisada de líneas con el total objetivo del documento.
 * [amount] usa la convención visible `suma de líneas + ajuste = total`; por ello el escenario
 * de demostración congela `+S/0,03`, no una diferencia negativa implícita.
 */
data class PurchaseReconciliationAdjustment(
    val amount: Money,
    val reason: String,
) {
    init {
        require(amount.minorUnits != 0L) { "Un ajuste de conciliación no puede ser cero" }
        require(reason == reason.trim()) { "El motivo del ajuste debe estar recortado" }
        require(reason.length in MIN_REASON_LENGTH..MAX_REASON_LENGTH) {
            "El motivo del ajuste debe tener entre $MIN_REASON_LENGTH y $MAX_REASON_LENGTH caracteres"
        }
    }

    companion object {
        const val MIN_REASON_LENGTH: Int = 10
        const val MAX_REASON_LENGTH: Int = 500

        fun isValidReason(value: String?): Boolean {
            val trimmed = value?.trim() ?: return false
            return trimmed.length in MIN_REASON_LENGTH..MAX_REASON_LENGTH
        }
    }
}

/**
 * Instantánea inmutable de una compra validada, lista para publicar. [logicalHash] es SHA-256
 * del contenido canónico SIN marcas de tiempo: preparar dos veces el mismo contenido produce el
 * mismo hash (idempotencia). [acceptedWarnings] registra las advertencias (p. ej. el redondeo)
 * que el usuario aceptó explícitamente al preparar.
 *
 * La instantánea nunca mueve inventario: la publicación de stock es una etapa posterior.
 */
data class PreparedPurchase(
    val draftId: DraftId,
    val businessId: BusinessId,
    val supplierId: SupplierId?,
    val supplierRuc: String,
    val supplierLegalName: String?,
    val documentType: PurchaseDocumentType?,
    val documentNumber: String,
    val issueDate: LocalDate,
    val currency: CurrencyCode,
    val lines: List<PreparedPurchaseLine>,
    val subtotal: Money?,
    val tax: Money?,
    val otherCharges: Money?,
    val total: Money,
    val acceptedWarnings: List<String>,
    val logicalHash: String,
    val preparedAt: Instant,
    val reconciliationAdjustment: PurchaseReconciliationAdjustment? = null,
) {
    init {
        require(lines.isNotEmpty()) { "Una compra preparada necesita al menos una línea" }
        require(lines.map(PreparedPurchaseLine::lineId).distinct().size == lines.size) {
            "lineId duplicado en la compra preparada"
        }
        require(lines.sortedBy(PreparedPurchaseLine::position).map(PreparedPurchaseLine::position) == lines.indices.toList()) {
            "Las posiciones de la compra preparada deben ser correlativas desde cero"
        }
        require(
            lines.groupBy(PreparedPurchaseLine::productId).values.all { productLines ->
                productLines.map(PreparedPurchaseLine::productProvenance).distinct().size == 1
            },
        ) { "Un producto no puede mezclar procedencias en una compra preparada" }
        require(supplierRuc.isNotBlank()) { "La compra preparada necesita RUC de proveedor" }
        require(documentNumber.isNotBlank()) { "La compra preparada necesita número de documento" }
        require(HEX_64.matches(logicalHash)) { "logicalHash debe ser SHA-256 hex en minúsculas" }
        require(!preparedAt.isBefore(Instant.EPOCH)) { "preparedAt anterior al epoch" }
        require(total.currency == currency) { "El total no comparte la moneda del documento" }
        require(reconciliationAdjustment == null || reconciliationAdjustment.amount.currency == currency) {
            "El ajuste de conciliación no comparte la moneda del documento"
        }
        val monetaryValues = buildList {
            add(total)
            subtotal?.let(::add)
            tax?.let(::add)
            otherCharges?.let(::add)
            lines.forEach { line ->
                line.unitCost?.let { require(it.currency == currency) }
                line.discount?.let(::add)
                line.tax?.let(::add)
                line.lineTotal?.let(::add)
            }
        }
        require(monetaryValues.all { it.currency == currency }) {
            "Todos los importes deben compartir la moneda del documento"
        }
    }

    companion object {
        private val HEX_64 = Regex("[0-9a-f]{64}")

        /**
         * Hash lógico estable del contenido de la compra. Cada campo usa longitud explícita y
         * cada opcional un marcador de presencia, de modo que texto libre, listas y valores
         * nulos nunca puedan confundirse con separadores. Ninguna marca de tiempo participa.
         */
        fun logicalHash(
            draftId: DraftId,
            businessId: BusinessId,
            supplierId: SupplierId?,
            supplierRuc: String,
            supplierLegalName: String?,
            documentType: PurchaseDocumentType?,
            documentNumber: String,
            issueDate: LocalDate,
            currency: CurrencyCode,
            lines: List<PreparedPurchaseLine>,
            subtotal: Money?,
            tax: Money?,
            otherCharges: Money?,
            total: Money,
            acceptedWarnings: List<String>,
            reconciliationAdjustment: PurchaseReconciliationAdjustment? = null,
        ): String = canonicalLogicalHash(
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
            marker = "prepared-purchase-logical-hash-v3",
            includeStagedSalePrice = true,
        )

        /** Hash canónico v5, anterior a que el precio staged formara parte de la identidad. */
        internal fun legacyV5LogicalHash(
            draftId: DraftId,
            businessId: BusinessId,
            supplierId: SupplierId?,
            supplierRuc: String,
            supplierLegalName: String?,
            documentType: PurchaseDocumentType?,
            documentNumber: String,
            issueDate: LocalDate,
            currency: CurrencyCode,
            lines: List<PreparedPurchaseLine>,
            subtotal: Money?,
            tax: Money?,
            otherCharges: Money?,
            total: Money,
            acceptedWarnings: List<String>,
            reconciliationAdjustment: PurchaseReconciliationAdjustment? = null,
        ): String = canonicalLogicalHash(
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
            marker = "prepared-purchase-logical-hash-v2",
            includeStagedSalePrice = false,
        )

        private fun canonicalLogicalHash(
            draftId: DraftId,
            businessId: BusinessId,
            supplierId: SupplierId?,
            supplierRuc: String,
            supplierLegalName: String?,
            documentType: PurchaseDocumentType?,
            documentNumber: String,
            issueDate: LocalDate,
            currency: CurrencyCode,
            lines: List<PreparedPurchaseLine>,
            subtotal: Money?,
            tax: Money?,
            otherCharges: Money?,
            total: Money,
            acceptedWarnings: List<String>,
            reconciliationAdjustment: PurchaseReconciliationAdjustment?,
            marker: String,
            includeStagedSalePrice: Boolean,
        ): String {
            val canonical = ByteArrayOutputStream()
            DataOutputStream(canonical).use { output ->
                output.writeCanonicalString(marker)
                output.writeCanonicalString(draftId.value)
                output.writeCanonicalString(businessId.value)
                output.writeCanonicalNullableString(supplierId?.value)
                output.writeCanonicalString(supplierRuc)
                output.writeCanonicalNullableString(supplierLegalName)
                output.writeCanonicalNullableString(documentType?.name)
                output.writeCanonicalString(documentNumber)
                output.writeLong(issueDate.toEpochDay())
                output.writeCanonicalString(currency.value)
                output.writeInt(lines.size)
                lines.forEach { line ->
                    output.writeCanonicalString(line.lineId.value)
                    output.writeInt(line.position)
                    output.writeCanonicalString(line.productId.value)
                    output.writeCanonicalString(line.unitId.value)
                    output.writeCanonicalString(line.rawText)
                    output.writeCanonicalString(line.description)
                    output.writeCanonicalString(line.quantity.value.toPlainString())
                    output.writeCanonicalNullableDecimal(line.unitCost?.amount?.toPlainString())
                    output.writeCanonicalNullableString(line.unitCost?.currency?.value)
                    output.writeCanonicalNullableMoney(line.discount)
                    output.writeCanonicalNullableMoney(line.tax)
                    output.writeCanonicalNullableMoney(line.lineTotal)
                    output.writeCanonicalNullableInt(line.linkConfidence)
                    output.writeCanonicalString(line.taxTreatment.name)
                    output.writeCanonicalString(line.taxEvidence.type.name)
                    output.writeCanonicalNullableDecimal(line.taxEvidence.value?.toPlainString())
                    output.writeCanonicalString(line.productProvenance.name)
                    output.writeCanonicalNullableStagedProduct(
                        line.stagedProduct,
                        includeStagedSalePrice,
                    )
                }
                output.writeCanonicalNullableMoney(subtotal)
                output.writeCanonicalNullableMoney(tax)
                output.writeCanonicalNullableMoney(otherCharges)
                output.writeCanonicalMoney(total)
                output.writeInt(acceptedWarnings.size)
                acceptedWarnings.forEach { warning -> output.writeCanonicalString(warning) }
                output.writeBoolean(reconciliationAdjustment != null)
                reconciliationAdjustment?.let { adjustment ->
                    output.writeCanonicalMoney(adjustment.amount)
                    output.writeCanonicalString(adjustment.reason)
                }
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
            return digest.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }
        }

        /** Hash historico usado exclusivamente para validar payloads codec v2/v3. */
        internal fun legacyLogicalHash(
            draftId: DraftId,
            businessId: BusinessId,
            supplierId: SupplierId?,
            supplierRuc: String,
            supplierLegalName: String?,
            documentType: PurchaseDocumentType?,
            documentNumber: String,
            issueDate: LocalDate,
            currency: CurrencyCode,
            lines: List<PreparedPurchaseLine>,
            subtotal: Money?,
            tax: Money?,
            otherCharges: Money?,
            total: Money,
            acceptedWarnings: List<String>,
            reconciliationAdjustment: PurchaseReconciliationAdjustment? = null,
        ): String {
            val content = buildString {
                append(draftId.value).append('|').append(businessId.value).append('\n')
                append(supplierId?.value ?: "-").append('|').append(supplierRuc).append('|')
                    .append(supplierLegalName ?: "-").append('\n')
                append(documentType?.name ?: "-").append('|').append(documentNumber).append('|')
                    .append(issueDate).append('|').append(currency.value).append('\n')
                lines.forEach { line ->
                    append(line.lineId.value).append('|').append(line.position).append('|')
                        .append(line.productId.value).append('|').append(line.unitId.value).append('|')
                        .append(line.rawText).append('|').append(line.description).append('|')
                        .append(line.quantity.value.toPlainString()).append('|')
                        .append(line.unitCost?.let { "${it.amount.toPlainString()}:${it.currency.value}" } ?: "-").append('|')
                        .append(line.discount?.canonical() ?: "-").append('|')
                        .append(line.tax?.canonical() ?: "-").append('|')
                        .append(line.lineTotal?.canonical() ?: "-").append('|')
                        .append(line.linkConfidence?.toString() ?: "-").append('\n')
                }
                append(subtotal?.canonical() ?: "-").append('|')
                    .append(tax?.canonical() ?: "-").append('|')
                    .append(otherCharges?.canonical() ?: "-").append('|')
                    .append(total.canonical()).append('\n')
                append(acceptedWarnings.joinToString(separator = ",")).append('\n')
                reconciliationAdjustment?.let { adjustment ->
                    append("reconciliation-adjustment|")
                        .append(adjustment.amount.canonical()).append('|')
                        .append(adjustment.reason).append('\n')
                }
            }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(content.toByteArray(Charsets.UTF_8))
            return digest.joinToString(separator = "") { byte ->
                "%02x".format(byte.toInt() and 0xFF)
            }
        }

        /** Hash delimitado usado exclusivamente para validar payloads codec v4 ya persistidos. */
        internal fun legacyV4LogicalHash(
            draftId: DraftId,
            businessId: BusinessId,
            supplierId: SupplierId?,
            supplierRuc: String,
            supplierLegalName: String?,
            documentType: PurchaseDocumentType?,
            documentNumber: String,
            issueDate: LocalDate,
            currency: CurrencyCode,
            lines: List<PreparedPurchaseLine>,
            subtotal: Money?,
            tax: Money?,
            otherCharges: Money?,
            total: Money,
            acceptedWarnings: List<String>,
            reconciliationAdjustment: PurchaseReconciliationAdjustment? = null,
        ): String {
            val content = buildString {
                append(draftId.value).append('|').append(businessId.value).append('\n')
                append(supplierId?.value ?: "-").append('|').append(supplierRuc).append('|')
                    .append(supplierLegalName ?: "-").append('\n')
                append(documentType?.name ?: "-").append('|').append(documentNumber).append('|')
                    .append(issueDate).append('|').append(currency.value).append('\n')
                lines.forEach { line ->
                    append(line.lineId.value).append('|').append(line.position).append('|')
                        .append(line.productId.value).append('|').append(line.unitId.value).append('|')
                        .append(line.rawText).append('|').append(line.description).append('|')
                        .append(line.quantity.value.toPlainString()).append('|')
                        .append(
                            line.unitCost?.let {
                                "${it.amount.toPlainString()}:${it.currency.value}"
                            } ?: "-",
                        ).append('|')
                        .append(line.discount?.canonical() ?: "-").append('|')
                        .append(line.tax?.canonical() ?: "-").append('|')
                        .append(line.lineTotal?.canonical() ?: "-").append('|')
                        .append(line.linkConfidence?.toString() ?: "-").append('|')
                        .append(line.taxTreatment.name).append('|')
                        .append(line.taxEvidence.type.name).append(':')
                        .append(line.taxEvidence.value?.toPlainString() ?: "-").append('|')
                        .append(line.productProvenance.name).append('|')
                        .append(line.stagedProduct?.legacyV4Canonical() ?: "-").append('\n')
                }
                append(subtotal?.canonical() ?: "-").append('|')
                    .append(tax?.canonical() ?: "-").append('|')
                    .append(otherCharges?.canonical() ?: "-").append('|')
                    .append(total.canonical()).append('\n')
                append(acceptedWarnings.joinToString(separator = ",")).append('\n')
                reconciliationAdjustment?.let { adjustment ->
                    append("reconciliation-adjustment|")
                        .append(adjustment.amount.canonical()).append('|')
                        .append(adjustment.reason).append('\n')
                }
            }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(content.toByteArray(Charsets.UTF_8))
            return digest.joinToString(separator = "") { byte ->
                "%02x".format(byte.toInt() and 0xFF)
            }
        }

        private fun DataOutputStream.writeCanonicalString(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            writeInt(bytes.size)
            write(bytes)
        }

        private fun DataOutputStream.writeCanonicalNullableString(value: String?) {
            writeBoolean(value != null)
            value?.let { present -> writeCanonicalString(present) }
        }

        private fun DataOutputStream.writeCanonicalNullableDecimal(value: String?) =
            writeCanonicalNullableString(value)

        private fun DataOutputStream.writeCanonicalNullableInt(value: Int?) {
            writeBoolean(value != null)
            value?.let(::writeInt)
        }

        private fun DataOutputStream.writeCanonicalMoney(value: Money) {
            writeLong(value.minorUnits)
            writeCanonicalString(value.currency.value)
        }

        private fun DataOutputStream.writeCanonicalNullableMoney(value: Money?) {
            writeBoolean(value != null)
            value?.let { present -> writeCanonicalMoney(present) }
        }

        private fun DataOutputStream.writeCanonicalNullableStagedProduct(
            value: StagedPurchaseProduct?,
            includeSalePrice: Boolean,
        ) {
            writeBoolean(value != null)
            value?.let { staged ->
                writeCanonicalString(staged.productId.value)
                writeCanonicalString(staged.businessId.value)
                writeCanonicalString(staged.unitId.value)
                writeCanonicalString(staged.name)
                writeCanonicalNullableString(staged.sku)
                writeCanonicalNullableString(staged.barcode)
                writeCanonicalNullableString(staged.purchaseUnitId?.value)
                writeCanonicalNullableDecimal(staged.purchaseFactor?.toPlainString())
                if (includeSalePrice) writeCanonicalNullableMoney(staged.salePrice)
            }
        }

        private fun StagedPurchaseProduct.legacyV4Canonical(): String = buildString {
            append(productId.value).append(':').append(businessId.value).append(':')
                .append(unitId.value).append(':').append(name).append(':')
                .append(sku ?: "-").append(':').append(barcode ?: "-").append(':')
                .append(purchaseUnitId?.value ?: "-").append(':')
                .append(purchaseFactor?.toPlainString() ?: "-")
        }

        private fun Money.canonical(): String = "$minorUnits:${currency.value}"
    }
}
