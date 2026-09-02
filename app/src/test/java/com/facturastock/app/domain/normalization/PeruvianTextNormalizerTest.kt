package com.facturastock.app.domain.normalization

import com.facturastock.app.domain.model.InvoiceTextBoundingBox
import com.facturastock.app.domain.model.InvoiceTextElement
import com.facturastock.app.domain.model.InvoiceTextGeometry
import com.facturastock.app.domain.model.InvoiceTextPage
import com.facturastock.app.domain.model.id.ImageId
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PeruvianTextNormalizerTest {
    @Test
    fun `normalizes unicode and spaces while preserving raw and display case`() {
        val raw = "  ＰｅＮ\u00A0\t  Café\n  S／  10,50  "

        val evidence = PeruvianTextNormalizer.normalize(raw)

        assertEquals(raw, evidence.rawText)
        assertEquals("  PeN \t  Café\n  S/  10,50  ", evidence.unicodeText)
        assertEquals("PeN Café S/ 10,50", evidence.normalizedText)
        assertEquals("PEN CAFÉ S/ 10,50", evidence.comparisonText)
        assertNull(evidence.sourceImageId)
        assertNull(evidence.pageIndex)
    }

    @Test
    fun `source factory carries page geometry and confidence without rewriting element text`() {
        val raw = "  kg  "
        val box = InvoiceTextBoundingBox(10, 20, 50, 60)
        val page = InvoiceTextPage(
            sourceImageId = IMAGE_ID,
            pageIndex = 0,
            widthPx = 100,
            heightPx = 100,
            text = raw,
            blocks = emptyList(),
        )
        val element = InvoiceTextElement(
            position = 0,
            text = raw,
            languageTag = "es-PE",
            geometry = InvoiceTextGeometry(box, emptyList()),
            confidencePermille = 873,
            clockwiseAngleTenths = 0,
        )

        val source = CandidateSource.from(page, element)
        val candidate = requireNotNull(PeruvianValueParser.parseUnit(source))

        assertEquals(raw, source.rawText)
        assertEquals(raw, candidate.evidence.rawText)
        assertEquals(InvoiceUnitCode.KGM, candidate.value)
        assertEquals(box, candidate.boundingBox)
        assertEquals(873, candidate.confidencePermille)
        assertEquals(IMAGE_ID, candidate.evidence.sourceImageId)
        assertEquals(0, candidate.evidence.pageIndex)
    }

    private companion object {
        val IMAGE_ID: ImageId = ImageId.from(UUID(0L, 1L))
    }
}
