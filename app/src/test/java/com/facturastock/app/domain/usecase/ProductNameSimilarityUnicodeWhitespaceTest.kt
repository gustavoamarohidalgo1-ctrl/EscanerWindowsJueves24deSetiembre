package com.facturastock.app.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Test

/** Antes era una prueba instrumentada: Windows debe tratar los espacios Unicode como Android. */
class ProductNameSimilarityUnicodeWhitespaceTest {
    @Test
    fun unicodeWhitespaceKeepsAndroidNormalizationAndScores() {
        for (separator in listOf(' ', ' ', ' ')) {
            val name = "Arroz${separator}extra"
            assertEquals(1_000, ProductNameSimilarity.similarityPermille(name, "arroz extra"))
            assertEquals(1_000, ProductNameSimilarity.similarityPermille("arroz extra", name))
            val mixed = " \tCafé${separator}\t${separator}molido\n "
            assertEquals(1_000, ProductNameSimilarity.similarityPermille(mixed, "cafe molido"))

            val candidate = "Harina de avena integral"
            assertEquals(
                ProductNameSimilarity.similarityPermille("har inte", candidate),
                ProductNameSimilarity.similarityPermille("har${separator}inte", candidate),
            )
        }
    }
}
