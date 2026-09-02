package com.facturastock.app.domain.normalization

import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.OcrRunId

/** Categoría explicable derivada de la confianza OCR, nunca una probabilidad calculada. */
enum class ParsedInvoiceConfidence(val reliabilityRank: Int) {
    UNKNOWN(0),
    LOW(1),
    MEDIUM(2),
    HIGH(3),
    ;

    companion object {
        /** Umbrales estables del parser v1. Un valor ausente permanece UNKNOWN. */
        fun fromPermille(value: Int?): ParsedInvoiceConfidence {
            require(value == null || value in 0..1_000) { "Confianza fuera de 0..1000" }
            return when (value) {
                null -> UNKNOWN
                in 900..1_000 -> HIGH
                in 700..899 -> MEDIUM
                else -> LOW
            }
        }
    }
}

/** Campo estable dentro del borrador trazable; [stableOrder] fija el orden del payload y la UI. */
enum class ParsedInvoiceFieldKind(val stableOrder: Int) {
    DOCUMENT_TYPE(10),
    ISSUER_RUC(20),
    ISSUER_LEGAL_NAME(30),
    DOCUMENT_NUMBER(40),
    ISSUE_DATE(50),
    CURRENCY(60),
    LINE_CODE(100),
    LINE_BARCODE(110),
    LINE_DESCRIPTION(120),
    LINE_QUANTITY(130),
    LINE_UNIT(140),
    LINE_UNIT_COST(150),
    LINE_DISCOUNT(160),
    LINE_TAX(170),
    LINE_TOTAL(180),
    TAXABLE_OPERATIONS(200),
    EXEMPT_OPERATIONS(210),
    UNAFFECTED_OPERATIONS(220),
    DISCOUNTS(230),
    SUBTOTAL(240),
    IGV(250),
    PRINTED_IGV_RATE(260),
    OTHER_CHARGE(270),
    ROUNDING(280),
    DOCUMENT_TOTAL(290),
    CALCULATED_SUBTOTAL(300),
    CALCULATED_IGV(310),
    CALCULATED_TOTAL_BEFORE_ROUNDING(320),
    CALCULATED_TOTAL(330),
}

enum class ParsedInvoiceValueOrigin {
    OCR,
    CALCULATED,
}

/**
 * Candidato durable. [canonicalValue] es una representación canónica para auditoría; el valor
 * tipado sigue disponible en los tres resultados puros de [ParsedInvoice].
 */
data class ParsedInvoiceCandidateTrace(
    val canonicalValue: String?,
    val rawText: String?,
    val origin: ParsedInvoiceValueOrigin,
    val confidencePermille: Int?,
    val confidence: ParsedInvoiceConfidence,
    val requiresReview: Boolean,
    val warnings: List<String>,
    /** Razones positivas o diagnósticas que explican la elección y la confianza. */
    val reasons: List<String>,
    val evidence: List<CandidateEvidence>,
) {
    init {
        require(confidencePermille == null || confidencePermille in 0..1_000) {
            "Confianza de candidato trazable fuera de 0..1000"
        }
        require(confidence == ParsedInvoiceConfidence.fromPermille(confidencePermille) || requiresReview) {
            "Una categoría limitada por política debe explicar que requiere revisión"
        }
        require(origin != ParsedInvoiceValueOrigin.OCR || !rawText.isNullOrEmpty()) {
            "Un candidato OCR debe conservar el texto original"
        }
        require(origin != ParsedInvoiceValueOrigin.OCR || evidence.isNotEmpty()) {
            "Un candidato OCR debe conservar evidencia"
        }
        require(warnings == warnings.distinct().sorted()) {
            "Las advertencias del candidato deben ser únicas y deterministas"
        }
        require(reasons == reasons.distinct().sorted()) {
            "Las razones del candidato deben ser únicas y deterministas"
        }
    }
}

/** Elección y candidatos de un campo, con posición cuando pertenece a una línea/cargo. */
data class ParsedInvoiceFieldTrace(
    val kind: ParsedInvoiceFieldKind,
    val position: Int? = null,
    val selectedCandidateIndex: Int? = null,
    val candidates: List<ParsedInvoiceCandidateTrace> = emptyList(),
    val confidence: ParsedInvoiceConfidence,
    val requiresReview: Boolean,
) {
    init {
        require(position == null || position >= 0) { "Posición de campo trazable negativa" }
        require(selectedCandidateIndex == null || selectedCandidateIndex in candidates.indices) {
            "Índice de candidato seleccionado fuera de rango"
        }
        require(selectedCandidateIndex != null || confidence == ParsedInvoiceConfidence.UNKNOWN) {
            "Un campo sin elección debe tener confianza UNKNOWN"
        }
        require(selectedCandidateIndex != null || requiresReview) {
            "Un campo sin elección debe requerir revisión"
        }
    }
}

enum class ParsedInvoiceWarningCode(val stableOrder: Int) {
    MISSING_REQUIRED_FIELD(10),
    UNRESOLVED_REQUIRED_FIELD(20),
    LOW_CONFIDENCE_REQUIRES_CONFIRMATION(30),
    UNKNOWN_CONFIDENCE_REQUIRES_CONFIRMATION(40),
    FIELD_REQUIRES_REVIEW(50),
    PARSER_WARNING(60),
    FINANCIAL_DIFFERENCE(70),
    NO_LINE_ITEMS(80),
}

/** Advertencia accionable. Las informativas de bajo nivel permanecen además en cada candidato. */
data class ParsedInvoiceWarning(
    val code: ParsedInvoiceWarningCode,
    val field: ParsedInvoiceFieldKind? = null,
    val position: Int? = null,
    val detail: String? = null,
    val requiresReview: Boolean = true,
    val evidence: List<CandidateEvidence> = emptyList(),
) {
    init {
        require(position == null || position >= 0) { "Posición de advertencia negativa" }
        require(detail == null || detail.isNotBlank()) { "Detalle de advertencia vacío" }
    }
}

enum class ParsedInvoiceBlockerCode(val stableOrder: Int) {
    MISSING_OR_UNRESOLVED_DESCRIPTION(10),
    MISSING_OR_UNRESOLVED_QUANTITY(20),
}

/** Un bloqueo invalida únicamente la línea indicada; nunca elimina la fila ni su evidencia. */
data class ParsedInvoiceBlocker(
    val code: ParsedInvoiceBlockerCode,
    val linePosition: Int,
    val evidence: List<CandidateEvidence>,
) {
    init {
        require(linePosition >= 0) { "Posición de bloqueo negativa" }
        require(evidence.isNotEmpty()) { "Un bloqueo de línea debe conservar evidencia de la fila" }
    }
}

/** Parte versionada y durable de un [ParsedInvoice]. */
data class ParsedInvoiceAudit(
    val draftId: DraftId,
    val runId: OcrRunId,
    val parserVersion: Int,
    val contextFingerprint: String,
    val confidence: ParsedInvoiceConfidence,
    val fields: List<ParsedInvoiceFieldTrace>,
    val warnings: List<ParsedInvoiceWarning>,
    val blockers: List<ParsedInvoiceBlocker>,
) {
    init {
        require(parserVersion > 0) { "parserVersion debe ser positivo" }
        require(SHA_256.matches(contextFingerprint)) { "contextFingerprint debe ser SHA-256" }
        require(fields.isOrderedWith(FIELD_ORDER)) {
            "Los campos trazables deben tener orden determinista"
        }
        require(fields.haveUniqueOrderedCoordinates()) {
            "No puede haber dos trazas para el mismo campo y posición"
        }
        require(warnings.isDistinctAndOrderedWith(WARNING_ORDER)) {
            "Las advertencias deben ser únicas y deterministas"
        }
        require(blockers.isDistinctAndOrderedWith(BLOCKER_ORDER)) {
            "Los bloqueos deben ser únicos y deterministas"
        }
    }

    /** LOW/UNKNOWN jamás habilitan una confirmación automática. */
    val eligibleForAutomaticConfirmation: Boolean
        get() = confidence == ParsedInvoiceConfidence.HIGH &&
            warnings.none(ParsedInvoiceWarning::requiresReview) && blockers.isEmpty()

    companion object {
        private val SHA_256 = Regex("^[0-9a-f]{64}$")

        val FIELD_ORDER: Comparator<ParsedInvoiceFieldTrace> =
            compareBy<ParsedInvoiceFieldTrace>({ it.position ?: -1 }, { it.kind.stableOrder })

        val WARNING_ORDER: Comparator<ParsedInvoiceWarning> =
            compareBy<ParsedInvoiceWarning>(
                { it.position ?: -1 },
                { it.field?.stableOrder ?: -1 },
                { it.code.stableOrder },
                { it.detail.orEmpty() },
                { if (it.requiresReview) 1 else 0 },
                { it.evidence.stableKey() },
            )

        val BLOCKER_ORDER: Comparator<ParsedInvoiceBlocker> =
            compareBy<ParsedInvoiceBlocker>(
                { it.linePosition },
                { it.code.stableOrder },
                { it.evidence.stableKey() },
            )
    }
}

private fun <T> List<T>.isOrderedWith(comparator: Comparator<in T>): Boolean {
    for (index in 1 until size) {
        if (comparator.compare(this[index - 1], this[index]) > 0) return false
    }
    return true
}

/** [ParsedInvoiceAudit.FIELD_ORDER] deja coordenadas repetidas necesariamente adyacentes. */
private fun List<ParsedInvoiceFieldTrace>.haveUniqueOrderedCoordinates(): Boolean {
    for (index in 1 until size) {
        val previous = this[index - 1]
        val current = this[index]
        if (previous.kind == current.kind && previous.position == current.position) return false
    }
    return true
}

private fun <T> List<T>.isDistinctAndOrderedWith(comparator: Comparator<in T>): Boolean {
    if (size < 2) return true
    val seen = HashSet<T>(size)
    for (index in indices) {
        val current = this[index]
        if (!seen.add(current)) return false
        if (index > 0 && comparator.compare(this[index - 1], current) > 0) return false
    }
    return true
}

private fun List<CandidateEvidence>.stableKey(): String = joinToString(separator = "\u001e") { item ->
    listOf(
        item.sourceImageId?.value.orEmpty(),
        item.pageIndex?.toString().orEmpty(),
        item.blockPosition?.toString().orEmpty(),
        item.linePosition?.toString().orEmpty(),
        item.elementPosition?.toString().orEmpty(),
        item.rawText,
    ).joinToString(separator = "\u001f")
}

/**
 * Resultado unificado: conserva los valores tipados completos y un audit trail genérico que se
 * puede persistir sin perder elección, candidatos, confianza ni evidencia OCR.
 */
data class ParsedInvoice(
    val header: InvoiceHeaderParseResult,
    val lineItems: InvoiceLineItemsParseResult,
    val totals: InvoiceTotalsParseResult,
    val audit: ParsedInvoiceAudit,
) {
    init {
        val identities = listOf(
            header.draftId to header.runId,
            lineItems.draftId to lineItems.runId,
            totals.draftId to totals.runId,
            audit.draftId to audit.runId,
        )
        require(identities.distinct().size == 1) {
            "Todos los componentes parseados deben pertenecer al mismo borrador y OCR"
        }
    }

    val draftId: DraftId get() = audit.draftId
    val runId: OcrRunId get() = audit.runId
    val parserVersion: Int get() = audit.parserVersion
    val confidence: ParsedInvoiceConfidence get() = audit.confidence
    val warnings: List<ParsedInvoiceWarning> get() = audit.warnings
    val blockers: List<ParsedInvoiceBlocker> get() = audit.blockers
}
