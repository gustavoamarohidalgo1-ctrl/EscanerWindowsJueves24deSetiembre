package com.facturastock.app.data.local.codec

import com.facturastock.app.domain.model.InvoiceTextBlock
import com.facturastock.app.domain.model.InvoiceTextBoundingBox
import com.facturastock.app.domain.model.InvoiceTextElement
import com.facturastock.app.domain.model.InvoiceTextGeometry
import com.facturastock.app.domain.model.InvoiceTextLine
import com.facturastock.app.domain.model.InvoiceTextPage
import com.facturastock.app.domain.model.InvoiceTextPoint
import com.facturastock.app.domain.model.id.ImageId
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class InvoiceOcrPageCodecTest {
    @Test
    fun `round trip preserves unicode hierarchy geometry and nullable values`() {
        val encoded = InvoiceOcrPageCodec.encode(PAGE)

        val decoded = InvoiceOcrPageCodec.decode(encoded)

        assertEquals(PAGE, decoded)
        assertEquals(64, InvoiceOcrPageCodec.sha256(encoded).length)
        assertArrayEquals(encoded, InvoiceOcrPageCodec.encode(PAGE))
    }

    @Test
    fun `unknown version and trailing bytes are rejected`() {
        val unknownVersion = InvoiceOcrPageCodec.encode(PAGE).copyOf().also { payload ->
            ByteBuffer.wrap(payload).putInt(Int.SIZE_BYTES, InvoiceOcrPageCodec.VERSION + 1)
        }
        val trailingBytes = InvoiceOcrPageCodec.encode(PAGE).let { payload ->
            payload.copyOf(payload.size + 1)
        }

        assertThrows(IOException::class.java) { InvoiceOcrPageCodec.decode(unknownVersion) }
        assertThrows(IOException::class.java) { InvoiceOcrPageCodec.decode(trailingBytes) }
    }

    private companion object {
        val GEOMETRY = InvoiceTextGeometry(
            boundingBox = InvoiceTextBoundingBox(10, 20, 400, 120),
            cornerPoints = listOf(
                InvoiceTextPoint(10, 20),
                InvoiceTextPoint(399, 20),
                InvoiceTextPoint(399, 119),
                InvoiceTextPoint(10, 119),
            ),
        )
        val PAGE = InvoiceTextPage(
            sourceImageId = ImageId.from(UUID.fromString("11111111-1111-1111-1111-111111111111")),
            pageIndex = 0,
            widthPx = 1_200,
            heightPx = 1_600,
            text = "FACTURA Nº F001-123\nTOTAL S/ 248.50 — café",
            blocks = listOf(
                InvoiceTextBlock(
                    position = 0,
                    text = "TOTAL S/ 248.50 — café",
                    languageTag = null,
                    geometry = GEOMETRY,
                    lines = listOf(
                        InvoiceTextLine(
                            position = 0,
                            text = "TOTAL S/ 248.50 — café",
                            languageTag = "es-PE",
                            geometry = GEOMETRY,
                            confidencePermille = 876,
                            clockwiseAngleTenths = -13,
                            elements = listOf(
                                InvoiceTextElement(
                                    position = 0,
                                    text = "248.50",
                                    languageTag = "und",
                                    geometry = InvoiceTextGeometry(null, emptyList()),
                                    confidencePermille = null,
                                    clockwiseAngleTenths = 0,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
    }
}
