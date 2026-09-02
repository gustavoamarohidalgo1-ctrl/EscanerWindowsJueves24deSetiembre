package com.facturastock.app.domain.model

import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.UnitId
import java.math.BigDecimal
import java.time.Instant

/** Campos editables de una línea; [stableOrder] fija el orden durable del payload. */
enum class InvoiceLineEditField(val stableOrder: Int) {
    DESCRIPTION(10),
    CODE(20),
    QUANTITY(30),
    UNIT(40),
    UNIT_COST(50),
    DISCOUNT(60),
    IGV(70),
    TOTAL(80),
}

/** Procedencia estable de la fila, independiente de la procedencia de cada valor. */
enum class InvoiceLineEditOrigin {
    OCR,
    USER,
}

/** Elección explícita. La igualdad textual nunca permite inferir la procedencia. */
enum class InvoiceLineValueSource {
    OCR,
    CALCULATED,
    WRITTEN,
}

/**
 * Los tres valores de una celda se conservan por separado. Son texto deliberadamente: el
 * autosave debe sobrevivir también a estados parciales (`12,`, `-` o un campo vaciado). El
 * parser/validador decide cuándo [selectedValue] puede proyectarse a un valor tipado.
 */
data class InvoiceLineEditValue(
    /** Valor canónico que el editor puede validar/calcular sin alterar su procedencia OCR. */
    val ocr: String? = null,
    /** Texto exacto impreso/reconocido; se muestra como referencia y nunca se normaliza. */
    val ocrRaw: String? = null,
    val calculated: String? = null,
    val written: String? = null,
    val selectedSource: InvoiceLineValueSource? = null,
) {
    init {
        validatePartialValue(ocr, "ocr")
        validatePartialValue(ocrRaw, "ocrRaw")
        validatePartialValue(calculated, "calculated")
        validatePartialValue(written, "written")
        require(
            when (selectedSource) {
                InvoiceLineValueSource.OCR -> ocr != null
                InvoiceLineValueSource.CALCULATED -> calculated != null
                InvoiceLineValueSource.WRITTEN -> written != null
                null -> true
            },
        ) { "selectedSource debe apuntar a un valor presente" }
    }

    val selectedValue: String?
        get() = when (selectedSource) {
            InvoiceLineValueSource.OCR -> ocr
            InvoiceLineValueSource.CALCULATED -> calculated
            InvoiceLineValueSource.WRITTEN -> written
            null -> null
        }
}

/**
 * Fila durable del editor móvil. [sourcePosition] conserva la posición del audit OCR aunque el
 * usuario reordene. Una eliminación es un tombstone: [position] retiene la posición anterior,
 * [deletedAt] permite restaurar el mismo [lineId] y toda su evidencia.
 *
 * [touchedFields] significa edición o confirmación humana explícita. Por ello un LOW/UNKNOWN en
 * [reviewRequiredFields] solo deja de estar pendiente cuando el campo correspondiente fue tocado;
 * ni el foco ni la mera presencia del OCR lo confirman automáticamente.
 */
data class InvoiceLineEdit(
    val lineId: LineId,
    val position: Int,
    val origin: InvoiceLineEditOrigin,
    val sourcePosition: Int? = null,
    val ocrRawText: String? = null,
    /** Enlaces de catálogo retenidos también dentro de un tombstone para restore. */
    val linkedProductId: ProductId? = null,
    val linkedUnitId: UnitId? = null,
    val linkConfidence: Int? = null,
    /** Decision humana; UNKNOWN se conserva para payloads antiguos y bloquea el avance. */
    val taxTreatment: InventoryTaxTreatment = InventoryTaxTreatment.UNKNOWN,
    /** Origen durable del enlace. UNKNOWN_LEGACY nunca se usa para inventar metricas. */
    val productProvenance: PurchaseProductProvenance = PurchaseProductProvenance.UNKNOWN_LEGACY,
    /** Solo existe mientras un producto nuevo espera la transaccion final de confirmacion. */
    val stagedProduct: StagedPurchaseProduct? = null,
    val description: InvoiceLineEditValue = InvoiceLineEditValue(),
    val code: InvoiceLineEditValue = InvoiceLineEditValue(),
    val quantity: InvoiceLineEditValue = InvoiceLineEditValue(),
    val unit: InvoiceLineEditValue = InvoiceLineEditValue(),
    val unitCost: InvoiceLineEditValue = InvoiceLineEditValue(),
    val discount: InvoiceLineEditValue = InvoiceLineEditValue(),
    val igv: InvoiceLineEditValue = InvoiceLineEditValue(),
    val total: InvoiceLineEditValue = InvoiceLineEditValue(),
    val confidencePermille: Int? = null,
    val fieldConfidencePermille: Map<InvoiceLineEditField, Int> = emptyMap(),
    /** Advertencia global de fila (LOW/UNKNOWN, geometría o contradicción no ligada a celda). */
    val requiresReview: Boolean = false,
    /** Confirmación humana de la fila; nunca altera ni eleva [confidencePermille]. */
    val reviewConfirmedByUser: Boolean = false,
    val reviewRequiredFields: Set<InvoiceLineEditField> = emptySet(),
    val touchedFields: Set<InvoiceLineEditField> = emptySet(),
    val deletedAt: Instant? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    init {
        require(position >= 0) { "position no puede ser negativa: $position" }
        require((origin == InvoiceLineEditOrigin.OCR) == (sourcePosition != null)) {
            "Solo una línea OCR debe conservar sourcePosition"
        }
        require(sourcePosition == null || sourcePosition >= 0) {
            "sourcePosition no puede ser negativa: $sourcePosition"
        }
        require(origin != InvoiceLineEditOrigin.OCR || !ocrRawText.isNullOrBlank()) {
            "Una línea OCR debe conservar el texto original de la fila"
        }
        require(origin != InvoiceLineEditOrigin.USER || ocrRawText == null) {
            "Una línea creada por el usuario no puede inventar texto OCR"
        }
        validatePartialValue(ocrRawText, "ocrRawText")
        validateLineField(description, InvoiceLineEditField.DESCRIPTION, MAX_DESCRIPTION_LENGTH)
        validateLineField(code, InvoiceLineEditField.CODE, MAX_SHORT_VALUE_LENGTH)
        validateLineField(quantity, InvoiceLineEditField.QUANTITY, MAX_SHORT_VALUE_LENGTH)
        validateLineField(unit, InvoiceLineEditField.UNIT, MAX_SHORT_VALUE_LENGTH)
        validateLineField(unitCost, InvoiceLineEditField.UNIT_COST, MAX_SHORT_VALUE_LENGTH)
        validateLineField(discount, InvoiceLineEditField.DISCOUNT, MAX_SHORT_VALUE_LENGTH)
        validateLineField(igv, InvoiceLineEditField.IGV, MAX_SHORT_VALUE_LENGTH)
        validateLineField(total, InvoiceLineEditField.TOTAL, MAX_SHORT_VALUE_LENGTH)
        require(confidencePermille == null || confidencePermille in 0..1_000) {
            "confidencePermille fuera de 0..1000: $confidencePermille"
        }
        require(linkConfidence == null || linkConfidence in 0..1_000) {
            "linkConfidence fuera de 0..1000: $linkConfidence"
        }
        require(
            stagedProduct == null ||
                (
                    productProvenance == PurchaseProductProvenance.CREATED_IN_DRAFT &&
                        stagedProduct.productId == linkedProductId &&
                        stagedProduct.businessId.value.isNotBlank() &&
                        linkedUnitId in setOfNotNull(
                            stagedProduct.unitId,
                            stagedProduct.purchaseUnitId,
                        )
                    )
        ) { "El producto staged no coincide con el enlace de la linea" }
        require(
            productProvenance != PurchaseProductProvenance.CREATED_IN_DRAFT ||
                stagedProduct != null
        ) { "CREATED_IN_DRAFT exige una instantanea de producto staged" }
        require(
            productProvenance != PurchaseProductProvenance.EXISTING ||
                stagedProduct == null
        ) { "EXISTING no admite un producto staged" }
        require(fieldConfidencePermille.values.all { it in 0..1_000 }) {
            "Confianza de campo fuera de 0..1000"
        }
        require(!createdAt.isBefore(Instant.EPOCH)) { "createdAt anterior al epoch" }
        require(!updatedAt.isBefore(createdAt)) { "updatedAt anterior a createdAt" }
        require(deletedAt == null || !deletedAt.isBefore(createdAt)) {
            "deletedAt anterior a createdAt"
        }
        require(deletedAt == null || !deletedAt.isAfter(updatedAt)) {
            "deletedAt posterior a updatedAt"
        }
    }

    val isDeleted: Boolean
        get() = deletedAt != null

    val isPending: Boolean
        get() = !isDeleted && (
            description.selectedValue.isNullOrBlank() ||
                quantity.selectedValue.isNullOrBlank() ||
                taxTreatment == InventoryTaxTreatment.UNKNOWN ||
                productProvenance == PurchaseProductProvenance.UNKNOWN_LEGACY ||
                (
                    taxTreatment in setOf(
                        InventoryTaxTreatment.INCLUDED,
                        InventoryTaxTreatment.EXCLUDED,
                    ) && igv.selectedValue.isNullOrBlank()
                    ) ||
                (
                    taxTreatment == InventoryTaxTreatment.EXEMPT &&
                        igv.selectedValue.isStrictlyPositiveDecimal()
                    ) ||
                (
                    !reviewConfirmedByUser &&
                        (
                            requiresReview ||
                                reviewRequiredFields.any { it !in touchedFields }
                            )
                    )
            )

    fun value(field: InvoiceLineEditField): InvoiceLineEditValue = when (field) {
        InvoiceLineEditField.DESCRIPTION -> description
        InvoiceLineEditField.CODE -> code
        InvoiceLineEditField.QUANTITY -> quantity
        InvoiceLineEditField.UNIT -> unit
        InvoiceLineEditField.UNIT_COST -> unitCost
        InvoiceLineEditField.DISCOUNT -> discount
        InvoiceLineEditField.IGV -> igv
        InvoiceLineEditField.TOTAL -> total
    }
}

private fun String?.isStrictlyPositiveDecimal(): Boolean {
    val normalized = this?.trim()?.replace(',', '.')?.takeIf(String::isNotEmpty) ?: return false
    return runCatching { BigDecimal(normalized).signum() > 0 }.getOrDefault(false)
}

/**
 * Snapshot completo del editor. Las líneas activas ocupan posiciones 0..n-1; los tombstones
 * conservan su antigua posición para que restore pueda reinsertarlos de forma acotada. La lista
 * se serializa completa y [revision] protege CRUD, reorder, delete y restore con un único CAS.
 */
data class InvoiceLinesEdit(
    val draftId: DraftId,
    val lines: List<InvoiceLineEdit>,
    val revision: Long,
    val updatedAt: Instant,
) {
    /** Se ordena una sola vez por snapshot; validación, edición y render comparten la misma vista. */
    val activeLines: List<InvoiceLineEdit> =
        lines.filterNot(InvoiceLineEdit::isDeleted).sortedBy(InvoiceLineEdit::position)

    init {
        require(revision >= 0L) { "revision no puede ser negativa: $revision" }
        require(!updatedAt.isBefore(Instant.EPOCH)) { "updatedAt anterior al epoch" }
        require(lines.size <= MAX_RETAINED_LINES) {
            "El editor excede $MAX_RETAINED_LINES líneas retenidas"
        }
        require(lines.map(InvoiceLineEdit::lineId).distinct().size == lines.size) {
            "lineId duplicado en el editor"
        }
        require(activeLines.size <= MAX_ACTIVE_LINES) {
            "El editor admite como máximo $MAX_ACTIVE_LINES líneas activas"
        }
        require(activeLines.map(InvoiceLineEdit::position) == activeLines.indices.toList()) {
            "Las posiciones activas deben ser correlativas desde cero"
        }
        val ocrPositions = lines
            .filter { it.origin == InvoiceLineEditOrigin.OCR }
            .map { requireNotNull(it.sourcePosition) }
        require(ocrPositions.distinct().size == ocrPositions.size) {
            "sourcePosition OCR duplicada"
        }
        require(lines.all { !it.updatedAt.isAfter(updatedAt) }) {
            "Una línea no puede ser posterior al snapshot"
        }
    }

    companion object {
        const val MAX_ACTIVE_LINES: Int = 100
        const val MAX_RETAINED_LINES: Int = 500
    }
}

private fun validatePartialValue(value: String?, name: String) {
    require(value == null || (value.length <= MAX_DESCRIPTION_LENGTH && '\u0000' !in value)) {
        "$name excede el límite o contiene NUL"
    }
}

private fun validateLineField(
    value: InvoiceLineEditValue,
    field: InvoiceLineEditField,
    maxLength: Int,
) {
    require(
        listOfNotNull(value.ocr, value.ocrRaw, value.calculated, value.written)
            .all { it.length <= maxLength },
    ) {
        "$field excede $maxLength caracteres"
    }
}

private const val MAX_DESCRIPTION_LENGTH: Int = 64_000
private const val MAX_SHORT_VALUE_LENGTH: Int = 512
