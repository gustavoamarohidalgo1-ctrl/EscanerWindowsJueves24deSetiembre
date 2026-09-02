package com.facturastock.app.domain.normalization

import java.text.Normalizer
import java.util.Locale

/** Normalización Unicode/espacios sin aplicar correcciones OCR ni alterar el texto original. */
object PeruvianTextNormalizer {
    fun normalize(source: CandidateSource): CandidateEvidence {
        val unicode = Normalizer.normalize(source.rawText, Normalizer.Form.NFKC)
        val normalized = collapseUnicodeSpaces(unicode)
        return CandidateEvidence(
            rawText = source.rawText,
            unicodeText = unicode,
            normalizedText = normalized,
            comparisonText = normalized.uppercase(Locale.ROOT),
            sourceImageId = source.sourceImageId,
            pageIndex = source.pageIndex,
            boundingBox = source.boundingBox,
            cornerPoints = source.cornerPoints,
            blockPosition = source.blockPosition,
            linePosition = source.linePosition,
            elementPosition = source.elementPosition,
            clockwiseAngleTenths = source.clockwiseAngleTenths,
        )
    }

    fun normalize(rawText: String): CandidateEvidence = normalize(CandidateSource(rawText))
}

private fun collapseUnicodeSpaces(value: String): String {
    val result = StringBuilder(value.length)
    var index = 0
    var pendingSpace = false
    while (index < value.length) {
        val codePoint = Character.codePointAt(value, index)
        index += Character.charCount(codePoint)
        if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
            pendingSpace = result.isNotEmpty()
        } else {
            if (pendingSpace) result.append(' ')
            result.appendCodePoint(codePoint)
            pendingSpace = false
        }
    }
    return result.toString()
}
