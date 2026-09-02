package com.facturastock.app.data.ocr

import com.facturastock.app.domain.model.InvoiceTextBoundingBox
import com.facturastock.app.domain.model.InvoiceTextPoint
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.repository.OcrImageFile
import java.util.UUID
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class MlKitInvoiceTextMapperTest {
    @Test
    fun `maps hierarchy text geometry confidence angle and language without ML Kit leakage`() {
        val rawElement = region(
            text = "248.50",
            box = MlKitBox(-10, 20, 1_300, 1_700),
            points = listOf(
                MlKitPoint(-2, 20),
                MlKitPoint(1_300, 20),
                MlKitPoint(1_300, 1_700),
                MlKitPoint(-2, 1_700),
            ),
            confidence = 1.4f,
            angle = -12.34f,
        )
        val snapshot = MlKitPageSnapshot(
            text = "TOTAL 248.50",
            blocks = listOf(
                MlKitBlockSnapshot(
                    region = region("TOTAL 248.50", language = "es"),
                    lines = listOf(
                        MlKitLineSnapshot(
                            region = region(
                                text = "TOTAL 248.50",
                                language = "es-PE",
                                confidence = 0.8764f,
                                angle = 1.26f,
                            ),
                            elements = listOf(rawElement),
                        ),
                    ),
                ),
            ),
        )

        val page = MlKitInvoiceTextMapper.mapPage(0, SOURCE, snapshot)
        val block = page.blocks.single()
        val line = block.lines.single()
        val element = line.elements.single()

        assertEquals("TOTAL 248.50", page.text)
        assertEquals("es", block.languageTag)
        assertEquals("es-PE", line.languageTag)
        assertEquals(876, line.confidencePermille)
        assertEquals(13, line.clockwiseAngleTenths)
        assertEquals(1_000, element.confidencePermille)
        assertEquals(-123, element.clockwiseAngleTenths)
        assertEquals(InvoiceTextBoundingBox(0, 20, 1_200, 1_600), element.geometry.boundingBox)
        assertEquals(
            listOf(
                InvoiceTextPoint(0, 20),
                InvoiceTextPoint(1_199, 20),
                InvoiceTextPoint(1_199, 1_599),
                InvoiceTextPoint(0, 1_599),
            ),
            element.geometry.cornerPoints,
        )
    }

    @Test
    fun `drops unavailable language invalid confidence and boxes outside the page`() {
        val snapshot = MlKitPageSnapshot(
            text = "X",
            blocks = listOf(
                MlKitBlockSnapshot(
                    region = region("X", language = "und"),
                    lines = listOf(
                        MlKitLineSnapshot(
                            region = region(
                                text = "X",
                                box = MlKitBox(-20, -20, -1, -1),
                                confidence = Float.NaN,
                                angle = Float.POSITIVE_INFINITY,
                            ),
                            elements = emptyList(),
                        ),
                    ),
                ),
            ),
        )

        val block = MlKitInvoiceTextMapper.mapPage(0, SOURCE, snapshot).blocks.single()
        val line = block.lines.single()

        assertNull(block.languageTag)
        assertNull(line.confidencePermille)
        assertNull(line.clockwiseAngleTenths)
        assertNull(line.geometry.boundingBox)
    }

    @Test
    fun `drops a malformed corner list instead of leaking invalid geometry`() {
        val snapshot = MlKitPageSnapshot(
            text = "X",
            blocks = listOf(
                MlKitBlockSnapshot(
                    region = region(
                        text = "X",
                        points = listOf(MlKitPoint(-1, -1), MlKitPoint(10, 10)),
                    ),
                    lines = emptyList(),
                ),
            ),
        )

        val geometry = MlKitInvoiceTextMapper.mapPage(0, SOURCE, snapshot)
            .blocks.single().geometry

        assertEquals(emptyList<InvoiceTextPoint>(), geometry.cornerPoints)
    }

    @Test
    fun `mapping checks cancellation inside the recognized hierarchy`() {
        val snapshot = MlKitPageSnapshot(
            text = "mucho texto",
            blocks = listOf(
                MlKitBlockSnapshot(
                    region = region("bloque"),
                    lines = listOf(
                        MlKitLineSnapshot(
                            region = region("línea"),
                            elements = List(20) { index -> region("elemento-$index") },
                        ),
                    ),
                ),
            ),
        )
        var checkpoints = 0

        try {
            MlKitInvoiceTextMapper.mapPage(0, SOURCE, snapshot) {
                checkpoints++
                if (checkpoints == 5) throw CancellationException("cancelled")
            }
            fail("se esperaba cancelación")
        } catch (_: CancellationException) {
            assertEquals(5, checkpoints)
        }
    }

    private fun region(
        text: String,
        language: String? = null,
        box: MlKitBox? = null,
        points: List<MlKitPoint> = emptyList(),
        confidence: Float? = null,
        angle: Float? = null,
    ) = MlKitRegionSnapshot(
        text = text,
        languageTag = language,
        boundingBox = box,
        cornerPoints = points,
        confidence = confidence,
        angleDegrees = angle,
    )

    private companion object {
        val SOURCE = OcrImageFile(
            sourceImageId = ImageId.from(UUID.fromString("11111111-1111-1111-1111-111111111111")),
            relativePath = "draft_images/test/page.jpg",
            mimeType = "image/jpeg",
            widthPx = 1_200,
            heightPx = 1_600,
            fileSizeBytes = 1_000,
        )
    }
}
