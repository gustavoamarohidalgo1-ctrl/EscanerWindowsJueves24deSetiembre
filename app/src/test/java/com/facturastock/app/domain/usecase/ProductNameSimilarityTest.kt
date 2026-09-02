package com.facturastock.app.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contrato del ordenamiento difuso que sostiene «hasta cinco candidatos y por qué coinciden»:
 * la puntuación es determinista, simétrica, vive en permil y solo cruza
 * [ProductMatchingUseCase.MIN_FUZZY_SCORE_PERMILLE] cuando los textos comparten contenido real.
 * Una puntuación alta nunca decide sola: la cascada la entrega como sugerencia.
 */
class ProductNameSimilarityTest {
    @Test
    fun `un nombre identico salvo tildes mayusculas y espacios puntua el maximo`() {
        assertEquals(1_000, ProductNameSimilarity.similarityPermille("Café  Molido", "cafe molido"))
        assertEquals(1_000, ProductNameSimilarity.similarityPermille("AZÚCAR RUBIA", " azucar rubia "))
    }

    @Test
    fun `un texto vacio o en blanco nunca puntua`() {
        assertEquals(0, ProductNameSimilarity.similarityPermille("", "cafe molido"))
        assertEquals(0, ProductNameSimilarity.similarityPermille("   ", "cafe molido"))
        assertEquals(0, ProductNameSimilarity.similarityPermille("cafe molido", ""))
    }

    @Test
    fun `descripciones ajenas quedan bajo el umbral difuso`() {
        val score = ProductNameSimilarity.similarityPermille("ARROZ EXTRA", "DETERGENTE LIQUIDO 3L")

        assertTrue(
            "una descripción ajena no puede sugerirse: $score",
            score < ProductMatchingUseCase.MIN_FUZZY_SCORE_PERMILLE,
        )
    }

    @Test
    fun `un nombre de catalogo que extiende la descripcion de la factura cruza el umbral`() {
        val score = ProductNameSimilarity.similarityPermille("CAFE MOLIDO", "Café Molido Premium 500 g")

        assertTrue(
            "el catálogo extiende la descripción y debe sugerirse: $score",
            score >= ProductMatchingUseCase.MIN_FUZZY_SCORE_PERMILLE,
        )
    }

    @Test
    fun `prefijos informativos de uno o varios tokens producen sugerencia`() {
        assertTrue(
            ProductNameSimilarity.similarityPermille("azuc", "Azúcar rubia") >=
                ProductMatchingUseCase.MIN_FUZZY_SCORE_PERMILLE,
        )
        assertTrue(
            ProductNameSimilarity.similarityPermille("leche evap", "Leche evaporada") >=
                ProductMatchingUseCase.MIN_FUZZY_SCORE_PERMILLE,
        )
    }

    @Test
    fun `la similitud es simetrica`() {
        val pairs = listOf(
            "CAFE MOLIDO" to "Café Molido Premium 500 g",
            "ARROZ EXTRA" to "DETERGENTE LIQUIDO 3L",
            "azucar" to "AZÚCAR RUBIA BOLSA",
            "leche evap" to "Leche evaporada",
        )

        pairs.forEach { (left, right) ->
            assertEquals(
                "similitud asimétrica entre «$left» y «$right»",
                ProductNameSimilarity.similarityPermille(left, right),
                ProductNameSimilarity.similarityPermille(right, left),
            )
        }
    }

    @Test
    fun `una letra sola no cuenta como token informativo`() {
        val score = ProductNameSimilarity.similarityPermille("A B C", "A B D")

        assertTrue(
            "letras aisladas no pueden fabricar solape de tokens: $score",
            score < ProductMatchingUseCase.MIN_FUZZY_SCORE_PERMILLE,
        )
    }

    @Test
    fun `la puntuacion es estable y vive dentro del rango permil`() {
        val texts = listOf(
            "CAFE MOLIDO",
            "Café Molido Premium 500 g",
            "ARROZ EXTRA",
            "DETERGENTE LIQUIDO 3L",
            "",
            "   ",
            "azucar",
        )

        texts.forEach { left ->
            texts.forEach { right ->
                val score = ProductNameSimilarity.similarityPermille(left, right)
                assertTrue("puntuación fuera de 0..1000: $score", score in 0..1_000)
                assertEquals(
                    "la puntuación debe ser determinista",
                    score,
                    ProductNameSimilarity.similarityPermille(left, right),
                )
            }
        }
    }

    @Test
    fun `el scorer reutilizable no conserva residuos entre candidatos`() {
        val query = "leche evap"
        val candidates = listOf(
            "Leche evaporada",
            "x",
            "Leche evaporada entera lata 400 g",
            "",
            "Detergente líquido",
            "Leche evap",
        )
        val expected = candidates.map { candidate ->
            ProductNameSimilarity.similarityPermille(query, candidate)
        }
        val scorer = requireNotNull(ProductNameSimilarity.scorer(query))

        assertEquals(expected, candidates.map(scorer::similarityPermille))
        assertEquals(expected, candidates.map(scorer::similarityPermille))
    }
}
