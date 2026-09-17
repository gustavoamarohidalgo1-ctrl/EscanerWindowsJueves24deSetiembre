package com.facturastock.app.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.Normalizer
import java.util.Locale
import kotlin.math.roundToInt

/** Diagnóstico JVM reproducible; verifica equivalencia, nunca impone un umbral de tiempo. */
class ProductMatchingPerformanceDiagnosticTest {
    @Test
    fun `diagnostico alternado del scorer previo y optimizado sobre paginas de doscientos`() {
        val fixtures =
            listOf(
                Fixture("prefijo_dos_letras", "ar", 2, List(200) { "Arroz extra nacional bolsa ${it + 1} kg" }),
                Fixture("prefijos_no_contiguos", "leche ent", 3, List(200) { "Leche evaporada entera lata ${it + 1} g" }),
                Fixture("acentos_y_prefijos", "har inte", 3, List(200) { "Harina de avena integral orgánica bolsa ${it + 1} kg" }),
                Fixture("error_ocr", "detergnte liquid", 3, List(200) { "Detergente líquido concentrado botella ${it + 1} ml" }),
                Fixture(
                    "catalogo_mixto",
                    "cafe mol",
                    3,
                    List(200) {
                        when (it % 4) {
                            0 -> "Café tostado molido premium ${it + 1} g"
                            1 -> "Cafe molido ${it + 1} g"
                            2 -> "Arroz extra bolsa ${it + 1} kg"
                            else -> "Azúcar rubia nacional ${it + 1} kg"
                        }
                    },
                ),
            )
        println("matching_jvm,scenario,candidates,baseline_median_ns,optimized_median_ns,prefix_shortcuts,baseline_dp_cells_avoided,ascii_candidates,checksum")
        for (fixture in fixtures) {
            val expected = baselineRound(fixture)
            assertEquals(expected, optimizedRound(fixture))
            repeat(5) {
                assertEquals(expected, baselineRound(fixture))
                assertEquals(expected, optimizedRound(fixture))
            }
            val baselineSamples = LongArray(9)
            val optimizedSamples = LongArray(9)
            for (sample in baselineSamples.indices) {
                // Alternar quién corre primero reduce el sesgo por calentamiento, GC y carga
                // del host. Cada muestra promedia cuatro páginas con un scorer por página.
                if (sample % 2 == 0) {
                    baselineSamples[sample] = measure(expected) { baselineRound(fixture) }
                    optimizedSamples[sample] = measure(expected) { optimizedRound(fixture) }
                } else {
                    optimizedSamples[sample] = measure(expected) { optimizedRound(fixture) }
                    baselineSamples[sample] = measure(expected) { baselineRound(fixture) }
                }
            }
            val avoidedCells = fixture.candidates.map { candidate -> shortcutAvoidedCells(fixture, candidate) }
            println(
                "matching_jvm,${fixture.label},${fixture.candidates.size}," +
                    "${baselineSamples.sorted()[4]},${optimizedSamples.sorted()[4]}," +
                    "${avoidedCells.count { it != null }},${avoidedCells.filterNotNull().sum()}," +
                    "${fixture.candidates.count { name -> name.all { it < '\u0080' } }},$expected",
            )
        }
    }

    private fun baselineRound(fixture: Fixture): Int {
        val scorer = requireNotNull(ProductNameSimilarityBaseline.scorer(fixture.query, fixture.minimumPrefixLength))
        var checksum = 0
        for (candidate in fixture.candidates) checksum += scorer.similarityPermille(candidate)
        return checksum
    }

    private fun optimizedRound(fixture: Fixture): Int {
        val scorer = requireNotNull(ProductNameSimilarity.scorer(fixture.query, fixture.minimumPrefixLength))
        var checksum = 0
        for (candidate in fixture.candidates) checksum += scorer.similarityPermille(candidate)
        return checksum
    }

    private inline fun measure(
        expected: Int,
        operation: () -> Int,
    ): Long {
        var checksum = 0
        val started = System.nanoTime()
        repeat(4) { checksum += operation() }
        val elapsed = System.nanoTime() - started
        assertEquals(expected * 4, checksum)
        return elapsed / 4
    }

    /**
     * Conteo fuera de la medición: cuando la cota fija 850, calcula las celdas que la referencia
     * habría recorrido después de recortar extremos comunes. Cero significa que su propio
     * atajo ya evitaba la matriz; no se atribuye a la optimización trabajo que no existía.
     */
    private fun shortcutAvoidedCells(
        fixture: Fixture,
        candidate: String,
    ): Int? {
        fun fold(text: String): String =
            Normalizer
                .normalize(text, Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .lowercase(Locale.ROOT)
                .replace(Regex("\\s+"), " ")
                .trim()
        val query = fold(fixture.query)
        val name = fold(candidate)
        val queryTokens = query.split(' ').filter { it.length >= 2 }.toSet()
        val nameTokens = name.split(' ').filter { it.length >= 2 }.toSet()

        fun prefixes(
            fragments: Set<String>,
            tokens: Set<String>,
        ): Boolean =
            fragments.isNotEmpty() &&
                fragments.all { fragment ->
                    fragment.length >= fixture.minimumPrefixLength && tokens.any { it.startsWith(fragment) }
                }
        if (!prefixes(queryTokens, nameTokens) && !prefixes(nameTokens, queryTokens)) return null
        val smaller = if (queryTokens.size <= nameTokens.size) queryTokens else nameTokens
        val larger = if (queryTokens.size <= nameTokens.size) nameTokens else queryTokens
        val overlap = smaller.count(larger::contains).toDouble() / smaller.size
        val maximumLength = maxOf(query.length, name.length)
        val minimumDistance = maximumLength - minOf(query.length, name.length)
        val maximumRatio = 1.0 - minimumDistance.toDouble() / maximumLength
        if (((overlap + maximumRatio) * 500.0).roundToInt() > 850) return null
        var start = 0
        while (start < minOf(query.length, name.length) && query[start] == name[start]) start++
        var queryEnd = query.length
        var nameEnd = name.length
        while (queryEnd > start && nameEnd > start && query[queryEnd - 1] == name[nameEnd - 1]) {
            queryEnd--
            nameEnd--
        }
        return (queryEnd - start) * (nameEnd - start)
    }

    private data class Fixture(
        val label: String,
        val query: String,
        val minimumPrefixLength: Int,
        val candidates: List<String>,
    )
}
