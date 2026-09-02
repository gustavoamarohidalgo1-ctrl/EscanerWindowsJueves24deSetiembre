package com.facturastock.app.domain.normalization

import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.InvoiceDocumentNumber
import com.facturastock.app.domain.model.InvoiceTextBoundingBox
import com.facturastock.app.domain.model.PurchaseDocumentType
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.OcrRunId
import java.time.LocalDate

/** Advertencias locales; ninguna representa una consulta o confirmación ante SUNAT. */
enum class InvoiceHeaderWarning {
    OCR_CORRECTION_APPLIED,
    UNICODE_COMPATIBILITY_NORMALIZED,
    RUC_STRUCTURE_MISMATCH_LOCAL,
    RUC_CHECK_DIGIT_MISMATCH_LOCAL,
    RECIPIENT_CONTEXT,
    AMBIGUOUS_PARTY_ROLE,
    AMBIGUOUS_VALUE,
    DATE_REQUIRES_REVIEW,
    CURRENCY_FROM_AMOUNT_SYMBOL,
    WEAK_GEOMETRY_MATCH,
}

/** Razones visibles y estables que explican por qué una ocurrencia compite o queda elegida. */
enum class InvoiceHeaderReason {
    EXPLICIT_FIELD_LABEL,
    SAME_LINE_AS_LABEL,
    SAME_ROW_AS_LABEL,
    DIRECTLY_BELOW_LABEL,
    EXPLICIT_ISSUER_CONTEXT,
    EXPLICIT_RECIPIENT_CONTEXT,
    MATCHES_ACTIVE_BUYER,
    ISSUER_LEGAL_NAME_CONTEXT,
    HEADER_BEFORE_RECIPIENT_SECTION,
    TOP_OF_FIRST_PAGE,
    FIXED_DOCUMENT_VOCABULARY,
    DOCUMENT_NUMBER_PATTERN,
    COMPANY_LEGAL_SUFFIX,
    ISSUE_DATE_LABEL,
    OTHER_DATE_LABEL,
    EXPLICIT_CURRENCY_LABEL,
    AMOUNT_CURRENCY_SYMBOL,
    RUC_CHECK_DIGIT_CONSISTENT_LOCAL,
    RUC_CHECK_DIGIT_MISMATCH_LOCAL,
}

/**
 * Propuesta de cabecera con evidencia potencialmente compuesta (etiqueta y valor pueden vivir en
 * cajas diferentes). [rawText] conserva los fragmentos OCR sin corregir; cuando el valor abarca
 * varias líneas, [evidence] mantiene cada original y sus límites por separado.
 */
data class InvoiceHeaderCandidate<T : Any>(
    val value: T,
    val rawText: String,
    val evidence: List<CandidateEvidence>,
    val boundingBox: InvoiceTextBoundingBox?,
    val confidencePermille: Int?,
    val warnings: List<InvoiceHeaderWarning> = emptyList(),
    val reasons: List<InvoiceHeaderReason> = emptyList(),
) {
    init {
        require(rawText.isNotEmpty()) { "Un candidato de cabecera debe conservar texto OCR" }
        require(evidence.isNotEmpty()) { "Un candidato de cabecera necesita evidencia" }
        require(confidencePermille == null || confidencePermille in 0..1_000) {
            "Confianza de cabecera fuera de 0..1000"
        }
        require(warnings.distinct().size == warnings.size) {
            "Advertencias de cabecera duplicadas"
        }
        require(reasons.distinct().size == reasons.size) { "Razones de cabecera duplicadas" }
    }

    val requiresReview: Boolean
        get() = warnings.isNotEmpty()
}

/** Una elección y las ocurrencias que la persona puede revisar o escoger en su lugar. */
data class HeaderField<T : Any>(
    val selected: InvoiceHeaderCandidate<T>? = null,
    val alternatives: List<InvoiceHeaderCandidate<T>> = emptyList(),
) {
    init {
        require(alternatives.distinct().size == alternatives.size) {
            "Alternativas de cabecera duplicadas"
        }
        require(selected == null || selected !in alternatives) {
            "La selección no debe repetirse como alternativa"
        }
    }

    val all: List<InvoiceHeaderCandidate<T>>
        get() = listOfNotNull(selected) + alternatives
}

/** Resultado puro ligado a la ejecución OCR que produjo la evidencia. */
data class InvoiceHeaderParseResult(
    val draftId: DraftId,
    val runId: OcrRunId,
    val documentType: HeaderField<PurchaseDocumentType>,
    val issuerRuc: HeaderField<String>,
    val issuerLegalName: HeaderField<String>,
    val documentNumber: HeaderField<InvoiceDocumentNumber>,
    val issueDate: HeaderField<LocalDate>,
    val currency: HeaderField<CurrencyCode>,
)
