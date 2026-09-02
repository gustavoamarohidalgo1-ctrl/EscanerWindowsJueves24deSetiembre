package com.facturastock.app.corpus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoldenCorpusImageTest {
    @Test
    fun `blur e inclinacion cambian pixeles reales frente a la factura limpia`() {
        val fixtures = GoldenCorpus.loadCases().associateBy { fixture -> fixture.case.id }
        val clean = GoldenCorpusImage.rasterize(fixtures.getValue("factura-limpia-basica"))
        val blurred = GoldenCorpusImage.rasterize(
            fixtures.getValue("captura-borrosa-baja-confianza"),
        )
        val skewed = GoldenCorpusImage.rasterize(fixtures.getValue("captura-inclinada"))

        assertEquals(clean.width, blurred.width)
        assertEquals(clean.height, blurred.height)
        assertEquals(clean.width, skewed.width)
        assertEquals(clean.height, skewed.height)
        assertFalse(clean.pixels.contentEquals(blurred.pixels))
        assertFalse(clean.pixels.contentEquals(skewed.pixels))
        assertFalse(blurred.pixels.contentEquals(skewed.pixels))

        assertTrue(clean.pixels.all { pixel ->
            pixel == GoldenCorpusImage.WHITE || pixel == GoldenCorpusImage.BLACK
        })
        assertTrue(blurred.pixels.any { pixel ->
            pixel != GoldenCorpusImage.WHITE && pixel != GoldenCorpusImage.BLACK
        })
        assertTrue(skewed.pixels.any { pixel -> pixel == GoldenCorpusImage.BLACK })
    }
}
