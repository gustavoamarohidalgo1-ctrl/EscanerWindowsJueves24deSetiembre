package com.facturastock.app.domain.normalization

import com.facturastock.app.domain.config.TaxRate
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.InvoiceTextBoundingBox
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.UnitCost
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.OcrRunId

/** Magnitud no negativa; la columna determina si se resta como descuento o se suma como IGV. */
sealed interface InvoiceLineAdjustment {
    data class Rate(val value: TaxRate) : InvoiceLineAdjustment

    data class Amount(val value: Money) : InvoiceLineAdjustment {
        init {
            require(value.minorUnits >= 0L) { "Un ajuste de línea no puede ser negativo" }
        }
    }
}

/** Advertencias de una fila extraída. Las informativas no bloquean una edición posterior. */
enum class InvoiceLineItemWarning(val requiresReview: Boolean) {
    INCOMPLETE_ROW(true),
    MISSING_DESCRIPTION(true),
    UNRESOLVED_FIELD(true),
    ARITHMETIC_MISMATCH(true),
    CURRENCY_CONFLICT(true),
    AMBIGUOUS_COLUMN_ASSIGNMENT(true),
    WEAK_GEOMETRY_MATCH(true),
    OCR_CORRECTION_APPLIED(true),
    MULTILINE_DESCRIPTION_JOINED(true),
    CURRENCY_FROM_DOCUMENT(false),
}

/** Advertencias que afectan el conjunto extraído y no una sola fila. */
enum class InvoiceLineItemsWarning {
    CURRENCY_CONFLICT,
}

/**
 * Fila editable propuesta por el OCR. Todos los campos son opcionales: una celda ausente nunca se
 * convierte en cero. [evidence] conserva cada fragmento original, incluso cuando la descripción
 * ocupa varias líneas.
 */
data class ParsedInvoiceLineItem(
    val position: Int,
    val pageIndex: Int,
    val rawText: String,
    val evidence: List<CandidateEvidence>,
    val boundingBox: InvoiceTextBoundingBox?,
    val code: Candidate<String>? = null,
    val barcode: Candidate<String>? = null,
    val description: Candidate<String>? = null,
    val quantity: Candidate<Quantity>? = null,
    val unit: Candidate<InvoiceUnitCode>? = null,
    val unitCost: Candidate<UnitCost>? = null,
    val discount: Candidate<InvoiceLineAdjustment>? = null,
    val tax: Candidate<InvoiceLineAdjustment>? = null,
    val total: Candidate<Money>? = null,
    val warnings: List<InvoiceLineItemWarning> = emptyList(),
) {
    init {
        require(position >= 0) { "Posición de fila OCR negativa" }
        require(pageIndex >= 0) { "Página de fila OCR negativa" }
        require(rawText.isNotBlank()) { "Una fila OCR debe conservar texto original" }
        require(evidence.isNotEmpty()) { "Una fila OCR necesita evidencia" }
        require(evidence.all { item -> item.pageIndex == null || item.pageIndex == pageIndex }) {
            "La evidencia de una fila OCR no puede mezclar páginas"
        }
        require(
            listOfNotNull<Candidate<*>>(
                code,
                barcode,
                description,
                quantity,
                unit,
                unitCost,
                discount,
                tax,
                total,
            ).all { candidate ->
                candidate.evidence.pageIndex == null || candidate.evidence.pageIndex == pageIndex
            },
        ) {
            "La evidencia de una celda OCR no puede pertenecer a otra página"
        }
        require(warnings.distinct().size == warnings.size) {
            "Advertencias de fila OCR duplicadas"
        }
    }

    val requiresReview: Boolean
        get() = warnings.any(InvoiceLineItemWarning::requiresReview) ||
            listOfNotNull<Candidate<*>>(
                code,
                barcode,
                description,
                quantity,
                unit,
                unitCost,
                discount,
                tax,
                total,
            ).any(Candidate<*>::requiresReview)
}

/** Resultado puro y estable ligado al snapshot que originó las líneas. */
data class InvoiceLineItemsParseResult(
    val draftId: DraftId,
    val runId: OcrRunId,
    val currency: CurrencyCode?,
    val items: List<ParsedInvoiceLineItem>,
    val warnings: List<InvoiceLineItemsWarning> = emptyList(),
) {
    init {
        require(items.map(ParsedInvoiceLineItem::position) == items.indices.toList()) {
            "Las filas OCR deben conservar un orden correlativo"
        }
        require(warnings.distinct().size == warnings.size) {
            "Advertencias del conjunto de filas duplicadas"
        }
    }
}
