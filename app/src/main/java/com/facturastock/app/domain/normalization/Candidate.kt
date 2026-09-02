package com.facturastock.app.domain.normalization

import com.facturastock.app.domain.model.InvoiceTextBlock
import com.facturastock.app.domain.model.InvoiceTextBoundingBox
import com.facturastock.app.domain.model.InvoiceTextElement
import com.facturastock.app.domain.model.InvoiceTextLine
import com.facturastock.app.domain.model.InvoiceTextPage
import com.facturastock.app.domain.model.InvoiceTextPoint
import com.facturastock.app.domain.model.id.ImageId

/** Fragmento OCR que se entrega a un normalizador puro sin perder su procedencia. */
data class CandidateSource(
    val rawText: String,
    val boundingBox: InvoiceTextBoundingBox? = null,
    val confidencePermille: Int? = null,
    val sourceImageId: ImageId? = null,
    val pageIndex: Int? = null,
    val cornerPoints: List<InvoiceTextPoint> = emptyList(),
    val blockPosition: Int? = null,
    val linePosition: Int? = null,
    val elementPosition: Int? = null,
    val clockwiseAngleTenths: Int? = null,
) {
    init {
        require(confidencePermille == null || confidencePermille in 0..1_000) {
            "Confianza de evidencia fuera de 0..1000"
        }
        require((sourceImageId == null) == (pageIndex == null)) {
            "sourceImageId y pageIndex deben estar presentes juntos"
        }
        require(pageIndex == null || pageIndex >= 0) { "pageIndex de evidencia negativo" }
        require(cornerPoints.isEmpty() || cornerPoints.size == 4) {
            "La evidencia debe tener cero o cuatro esquinas"
        }
        require(blockPosition == null || blockPosition >= 0) { "blockPosition de evidencia negativo" }
        require(linePosition == null || linePosition >= 0) { "linePosition de evidencia negativo" }
        require(elementPosition == null || elementPosition >= 0) {
            "elementPosition de evidencia negativo"
        }
        require(clockwiseAngleTenths == null || clockwiseAngleTenths in -1_800..1_800) {
            "Ángulo de evidencia fuera de -1800..1800"
        }
    }

    companion object {
        fun from(page: InvoiceTextPage, block: InvoiceTextBlock): CandidateSource =
            CandidateSource(
                rawText = block.text,
                boundingBox = block.geometry.boundingBox,
                sourceImageId = page.sourceImageId,
                pageIndex = page.pageIndex,
                cornerPoints = block.geometry.cornerPoints,
                blockPosition = block.position,
            )

        fun from(page: InvoiceTextPage, line: InvoiceTextLine): CandidateSource =
            CandidateSource(
                rawText = line.text,
                boundingBox = line.geometry.boundingBox,
                confidencePermille = line.confidencePermille,
                sourceImageId = page.sourceImageId,
                pageIndex = page.pageIndex,
                cornerPoints = line.geometry.cornerPoints,
                linePosition = line.position,
                clockwiseAngleTenths = line.clockwiseAngleTenths,
            )

        fun from(page: InvoiceTextPage, element: InvoiceTextElement): CandidateSource =
            CandidateSource(
                rawText = element.text,
                boundingBox = element.geometry.boundingBox,
                confidencePermille = element.confidencePermille,
                sourceImageId = page.sourceImageId,
                pageIndex = page.pageIndex,
                cornerPoints = element.geometry.cornerPoints,
                elementPosition = element.position,
                clockwiseAngleTenths = element.clockwiseAngleTenths,
            )

        fun from(
            page: InvoiceTextPage,
            block: InvoiceTextBlock,
            line: InvoiceTextLine,
            boundingBox: InvoiceTextBoundingBox? = line.geometry.boundingBox,
        ): CandidateSource = CandidateSource(
            rawText = line.text,
            boundingBox = boundingBox,
            confidencePermille = line.confidencePermille,
            sourceImageId = page.sourceImageId,
            pageIndex = page.pageIndex,
            cornerPoints = line.geometry.cornerPoints,
            blockPosition = block.position,
            linePosition = line.position,
            clockwiseAngleTenths = line.clockwiseAngleTenths,
        )

        fun from(
            page: InvoiceTextPage,
            block: InvoiceTextBlock,
            line: InvoiceTextLine,
            element: InvoiceTextElement,
            boundingBox: InvoiceTextBoundingBox? = element.geometry.boundingBox,
        ): CandidateSource = CandidateSource(
            rawText = element.text,
            boundingBox = boundingBox,
            confidencePermille = element.confidencePermille,
            sourceImageId = page.sourceImageId,
            pageIndex = page.pageIndex,
            cornerPoints = element.geometry.cornerPoints,
            blockPosition = block.position,
            linePosition = line.position,
            elementPosition = element.position,
            clockwiseAngleTenths = element.clockwiseAngleTenths,
        )
    }
}

/** Transformación puntual aplicada solo después de que el parser conoce el tipo del campo. */
data class AppliedOcrCorrection(
    val originalFragment: String,
    val correctedFragment: String,
    val reason: OcrCorrectionReason,
) {
    init {
        require(originalFragment.isNotEmpty()) { "Una corrección OCR necesita origen" }
        require(correctedFragment.isNotEmpty()) { "Una corrección OCR necesita destino" }
        require(originalFragment != correctedFragment) { "Una corrección OCR debe cambiar el fragmento" }
    }
}

enum class OcrCorrectionReason {
    NUMERIC_CONFUSABLE_IN_NUMERIC_CONTEXT,
    UNIT_CONFUSABLE_IN_UNIT_CONTEXT,
    FIXED_VOCABULARY_CONFUSABLE,
}

/** Evidencia textual derivada; [rawText] es siempre la cadena OCR original, sin modificar. */
data class CandidateEvidence(
    val rawText: String,
    val unicodeText: String,
    val normalizedText: String,
    val comparisonText: String,
    val corrections: List<AppliedOcrCorrection> = emptyList(),
    val sourceImageId: ImageId? = null,
    val pageIndex: Int? = null,
    val boundingBox: InvoiceTextBoundingBox? = null,
    val cornerPoints: List<InvoiceTextPoint> = emptyList(),
    val blockPosition: Int? = null,
    val linePosition: Int? = null,
    val elementPosition: Int? = null,
    val clockwiseAngleTenths: Int? = null,
) {
    init {
        require((sourceImageId == null) == (pageIndex == null)) {
            "sourceImageId y pageIndex de evidencia deben estar presentes juntos"
        }
        require(pageIndex == null || pageIndex >= 0) { "pageIndex de evidencia negativo" }
        require(cornerPoints.isEmpty() || cornerPoints.size == 4) {
            "La evidencia debe tener cero o cuatro esquinas"
        }
        require(blockPosition == null || blockPosition >= 0) { "blockPosition de evidencia negativo" }
        require(linePosition == null || linePosition >= 0) { "linePosition de evidencia negativo" }
        require(elementPosition == null || elementPosition >= 0) {
            "elementPosition de evidencia negativo"
        }
        require(clockwiseAngleTenths == null || clockwiseAngleTenths in -1_800..1_800) {
            "Ángulo de evidencia fuera de -1800..1800"
        }
    }
}

/** Una advertencia puede ser informativa o exigir que la persona resuelva el valor. */
enum class CandidateWarning(val requiresReview: Boolean) {
    OCR_CORRECTION_APPLIED(true),
    AMBIGUOUS_NUMBER_SEPARATOR(true),
    AMBIGUOUS_DATE_ORDER(true),
    MULTIPLE_VALUES_FOUND(true),
    CURRENCY_FROM_CONTEXT(true),
    CURRENCY_CONFLICT(true),
    UNSUPPORTED_FRACTION_PRECISION(true),
    TWO_DIGIT_YEAR(true),
    NON_PERUVIAN_DATE_ORDER(true),
    UNSUPPORTED_NUMERIC_VALUE(true),
    MISSING_EXPLICIT_SIGN(true),
    SIGNED_TOTAL_REQUIRES_DOCUMENT_CONTEXT(true),
    UNIT_ALIAS_NORMALIZED(false),
}

/**
 * Valor propuesto junto a toda su evidencia. [value] queda nulo cuando hay más de una lectura
 * válida; [alternatives] hace explícitas esas lecturas y evita seleccionar una silenciosamente.
 */
data class Candidate<T : Any>(
    val value: T?,
    val evidence: CandidateEvidence,
    val boundingBox: InvoiceTextBoundingBox?,
    val confidencePermille: Int?,
    val warnings: List<CandidateWarning> = emptyList(),
    val alternatives: List<T> = emptyList(),
) {
    init {
        require(confidencePermille == null || confidencePermille in 0..1_000) {
            "Confianza de candidato fuera de 0..1000"
        }
        require(warnings.distinct().size == warnings.size) { "Advertencias de candidato duplicadas" }
        require(alternatives.distinct().size == alternatives.size) { "Alternativas duplicadas" }
        require(value == null || value !in alternatives) {
            "El valor elegido no debe repetirse como alternativa"
        }
        require(value == null || alternatives.isEmpty()) {
            "Un candidato resuelto no debe conservar alternativas"
        }
        require(value == null || warnings.none(CandidateWarning::requiresReview)) {
            "Un candidato con advertencias pendientes no puede quedar resuelto"
        }
        require(value != null || warnings.any(CandidateWarning::requiresReview)) {
            "Un candidato no resuelto debe explicar por qué requiere revisión"
        }
    }

    val requiresReview: Boolean
        get() = value == null || warnings.any(CandidateWarning::requiresReview)
}
