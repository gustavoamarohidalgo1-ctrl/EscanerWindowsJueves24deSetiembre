package com.facturastock.app.domain.usecase

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** La clase Unicode de \\s en Android difiere de la JVM usada por las pruebas unitarias. */
@RunWith(AndroidJUnit4::class)
class ProductNameSimilarityAndroidTest {
    @Test
    fun unicodeWhitespaceKeepsAndroidNormalizationAndScores() {
        for (separator in listOf('\u00A0', '\u202F', '\u2003')) {
            val name = "Arroz${separator}extra"
            assertEquals("Arroz extra", name.replace(Regex("\\s+"), " "))
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
