package com.facturastock.app.domain.usecase

import java.text.Normalizer
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Referencia congelada del scorer anterior a la optimización del 12/09/2026.
 * Conserva también sus filas reutilizables y recorte de extremos para que la comparación de
 * resultados y el diagnóstico JVM usen el trabajo real previo, sin una referencia artificialmente lenta.
 */
internal object ProductNameSimilarityBaseline {
    fun similarityPermille(
        query: String,
        candidate: String,
    ): Int = scorer(query)?.similarityPermille(candidate) ?: 0

    /**
     * Prepara una búsqueda completa una sola vez. El objeto se usa secuencialmente dentro de una
     * invocación de matching y reutiliza dos filas de Levenshtein acotadas a 200 caracteres.
     */
    fun scorer(
        query: String,
        minimumPrefixLength: Int = MIN_PREFIX_LENGTH,
    ): Scorer? {
        require(minimumPrefixLength in 2..ProductMatchingUseCase.MAX_MATCH_TEXT_LENGTH)
        if (query.length > ProductMatchingUseCase.MAX_MATCH_TEXT_LENGTH) return null
        // NFD puede expandir caracteres acentuados; el corte mantiene también acotados los
        // arreglos de Levenshtein. Esta ruta solo sugiere y nunca auto-vincula.
        val foldedQuery = fold(query).take(ProductMatchingUseCase.MAX_MATCH_TEXT_LENGTH)
        if (foldedQuery.isEmpty()) return null
        return Scorer(foldedQuery, tokens(foldedQuery), minimumPrefixLength)
    }

    internal class Scorer internal constructor(
        private val foldedQuery: String,
        private val queryTokens: Set<String>,
        private val minimumPrefixLength: Int,
    ) {
        private val previousScratch = IntArray(ProductMatchingUseCase.MAX_MATCH_TEXT_LENGTH + 1)
        private val currentScratch = IntArray(ProductMatchingUseCase.MAX_MATCH_TEXT_LENGTH + 1)

        fun similarityPermille(candidate: String): Int {
            if (candidate.length > ProductMatchingUseCase.MAX_MATCH_TEXT_LENGTH) return 0
            val foldedCandidate = fold(candidate).take(ProductMatchingUseCase.MAX_MATCH_TEXT_LENGTH)
            if (foldedCandidate.isEmpty()) return 0
            val candidateTokens = tokens(foldedCandidate)
            val smallerTokens: Set<String>
            val largerTokens: Set<String>
            if (queryTokens.size <= candidateTokens.size) {
                smallerTokens = queryTokens
                largerTokens = candidateTokens
            } else {
                smallerTokens = candidateTokens
                largerTokens = queryTokens
            }
            val overlap =
                if (smallerTokens.isEmpty()) {
                    0.0
                } else {
                    smallerTokens.count(largerTokens::contains).toDouble() / smallerTokens.size
                }
            val levenshteinRatio =
                1.0 -
                    levenshtein(foldedQuery, foldedCandidate).toDouble() /
                    maxOf(foldedQuery.length, foldedCandidate.length)
            val baseScore = ((overlap + levenshteinRatio) * 500.0).roundToInt()
            // La puntuación pública conserva su contrato simétrico. En la búsqueda name-only el
            // primer lado suele ser el fragmento; aceptar también la dirección inversa mantiene
            // la misma puntuación si un llamador intercambia los argumentos.
            val allTokensMatchByPrefix =
                tokensMatchByPrefix(queryTokens, candidateTokens, minimumPrefixLength) ||
                    tokensMatchByPrefix(candidateTokens, queryTokens, minimumPrefixLength)
            val prefixScore = if (allTokensMatchByPrefix) PREFIX_MATCH_SCORE else 0
            return maxOf(baseScore, prefixScore).coerceIn(0, 1_000)
        }

        private fun levenshtein(
            a: String,
            b: String,
        ): Int {
            if (a == b) return 0
            if (a.isEmpty()) return b.length
            if (b.isEmpty()) return a.length
            var sharedStart = 0
            val shortestLength = minOf(a.length, b.length)
            while (sharedStart < shortestLength && a[sharedStart] == b[sharedStart]) {
                sharedStart++
            }
            var aEnd = a.length
            var bEnd = b.length
            while (
                aEnd > sharedStart &&
                bEnd > sharedStart &&
                a[aEnd - 1] == b[bEnd - 1]
            ) {
                aEnd--
                bEnd--
            }
            val aLength = aEnd - sharedStart
            val bLength = bEnd - sharedStart
            if (aLength == 0) return bLength
            if (bLength == 0) return aLength
            var previous = previousScratch
            var current = currentScratch
            for (index in 0..bLength) previous[index] = index
            for (i in 1..aLength) {
                current[0] = i
                val aCharacter = a[sharedStart + i - 1]
                for (j in 1..bLength) {
                    val substitution =
                        previous[j - 1] +
                            if (aCharacter == b[sharedStart + j - 1]) 0 else 1
                    current[j] = minOf(previous[j] + 1, current[j - 1] + 1, substitution)
                }
                val swap = previous
                previous = current
                current = swap
            }
            return previous[bLength]
        }
    }

    private fun tokensMatchByPrefix(
        fragments: Set<String>,
        candidates: Set<String>,
        minimumPrefixLength: Int,
    ): Boolean =
        fragments.isNotEmpty() &&
            fragments.all { fragment ->
                fragment.length >= minimumPrefixLength &&
                    candidates.any { candidate ->
                        candidate.startsWith(fragment)
                    }
            }

    private fun fold(raw: String): String {
        val withoutMarks =
            Normalizer
                .normalize(raw, Normalizer.Form.NFD)
                .replace(COMBINING_MARKS, "")
        return withoutMarks
            .lowercase(Locale.ROOT)
            .replace(WHITESPACE, " ")
            .trim()
    }

    private fun tokens(folded: String): Set<String> = folded.split(' ').filter { it.length >= 2 }.toSet()

    private val COMBINING_MARKS = Regex("\\p{M}+")
    private val WHITESPACE = Regex("(?U)\\s+")
    private const val MIN_PREFIX_LENGTH = 3
    private const val PREFIX_MATCH_SCORE = 850
}
