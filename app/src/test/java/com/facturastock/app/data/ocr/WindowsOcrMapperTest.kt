package com.facturastock.app.data.ocr

import com.facturastock.app.domain.model.InvoiceTextBoundingBox
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.repository.OcrImageFile
import java.util.UUID
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WindowsOcrMapperTest {
    @Test
    fun `parses the script JSON including escaped accents and explicit nulls`() {
        val output = WindowsOcrMapper.parse(
            """
            {"pages":[{"index":0,"width":1200,"height":1600,"angle":null,"lines":[
              {"text":"Descripción AÑO","words":[
                {"text":"Descripción","x":10.5,"y":20,"w":100,"h":1.5E1},
                {"text":"AÑO","x":null,"y":20,"w":30,"h":15}]}]}]}
            """.trimIndent(),
        )

        val line = output.pages.single().lines.single()
        assertEquals("Descripción AÑO", line.text)
        assertEquals(15.0, line.words[0].h!!, 0.0)
        assertNull(line.words[1].x)
        assertNull(output.pages.single().angle)
    }

    @Test
    fun `maps hierarchy text geometry angle and unknown confidence without engine leakage`() {
        val page = WindowsOcrPage(
            index = 0,
            width = 1_200,
            height = 1_600,
            angle = 1.26,
            lines = listOf(
                line("TOTAL 248.50", word("TOTAL", 100.0, 200.0, 80.0, 20.0), word("248.50", 190.4, 200.2, 70.2, 19.7)),
            ),
        )

        val mapped = WindowsOcrMapper.mapPage(0, SOURCE, page)
        val block = mapped.blocks.single()
        val line = block.lines.single()

        assertEquals("TOTAL 248.50", mapped.text)
        assertEquals("TOTAL 248.50", block.text)
        assertEquals(listOf("TOTAL", "248.50"), line.elements.map { it.text })
        assertEquals(listOf(0, 1), line.elements.map { it.position })
        assertEquals(InvoiceTextBoundingBox(100, 200, 180, 220), line.elements[0].geometry.boundingBox)
        // floor del origen y ceil del extremo: la caja nunca recorta tinta reconocida.
        assertEquals(InvoiceTextBoundingBox(190, 200, 261, 220), line.elements[1].geometry.boundingBox)
        assertEquals(InvoiceTextBoundingBox(100, 200, 261, 220), line.geometry.boundingBox)
        assertEquals(line.geometry.boundingBox, block.geometry.boundingBox)
        assertEquals(13, line.clockwiseAngleTenths)
        assertEquals(13, line.elements[1].clockwiseAngleTenths)
        assertNull(line.confidencePermille)
        assertNull(line.elements[0].confidencePermille)
        assertNull(block.languageTag)
        assertNull(line.languageTag)
        assertTrue(line.geometry.cornerPoints.isEmpty())
        assertEquals(SOURCE.sourceImageId, mapped.sourceImageId)
        assertEquals(1_200, mapped.widthPx)
    }

    @Test
    fun `coordinates of a downscaled bitmap are mapped back to the prepared page`() {
        val page = WindowsOcrPage(
            index = 0,
            width = 600,
            height = 800,
            lines = listOf(line("RUC", word("RUC", 10.0, 20.0, 30.0, 10.0))),
        )

        val box = WindowsOcrMapper.mapPage(0, SOURCE, page).blocks.single().lines.single().elements.single().geometry.boundingBox

        assertEquals(InvoiceTextBoundingBox(20, 40, 80, 60), box)
    }

    @Test
    fun `clips boxes to the page and drops invalid geometry angle and blank words`() {
        val page = WindowsOcrPage(
            index = 0,
            width = 1_200,
            height = 1_600,
            angle = Double.NaN,
            lines = listOf(
                line(
                    "X Y",
                    word("X", -10.0, 1_590.0, 1_300.0, 50.0),
                    word("   ", 0.0, 0.0, 10.0, 10.0),
                    word("Y", -40.0, -40.0, 20.0, 20.0),
                    WindowsOcrWord(text = "Z", x = null, y = 1.0, w = 1.0, h = 1.0),
                    WindowsOcrWord(text = "W", x = Double.POSITIVE_INFINITY, y = 1.0, w = 1.0, h = 1.0),
                ),
            ),
        )

        val line = WindowsOcrMapper.mapPage(0, SOURCE, page).blocks.single().lines.single()

        assertEquals(listOf("X", "Y", "Z", "W"), line.elements.map { it.text })
        assertEquals(InvoiceTextBoundingBox(0, 1_590, 1_200, 1_600), line.elements[0].geometry.boundingBox)
        assertNull(line.elements[1].geometry.boundingBox)
        assertNull(line.elements[2].geometry.boundingBox)
        assertNull(line.elements[3].geometry.boundingBox)
        assertEquals(InvoiceTextBoundingBox(0, 1_590, 1_200, 1_600), line.geometry.boundingBox)
        assertNull(line.clockwiseAngleTenths)
    }

    @Test
    fun `line text falls back to its words and empty lines are skipped`() {
        val page = WindowsOcrPage(
            index = 0,
            width = 1_200,
            height = 1_600,
            lines = listOf(
                line("", word("S/", 10.0, 10.0, 20.0, 20.0), word("10.00", 40.0, 10.0, 50.0, 20.0)),
                line("   "),
            ),
        )

        val mapped = WindowsOcrMapper.mapPage(0, SOURCE, page)

        assertEquals("S/ 10.00", mapped.text)
        assertEquals(1, mapped.blocks.single().lines.size)
    }

    @Test
    fun `page without text yields an empty page`() {
        val mapped = WindowsOcrMapper.mapPage(0, SOURCE, WindowsOcrPage(index = 0, width = 10, height = 10))

        assertEquals("", mapped.text)
        assertTrue(mapped.blocks.isEmpty())
    }

    @Test
    fun `groups lines into blocks by vertical proximity columns and height`() {
        val page = WindowsOcrPage(
            index = 0,
            width = 1_200,
            height = 1_600,
            lines = listOf(
                // Título grande.
                line("FACTURA", word("FACTURA", 100.0, 50.0, 400.0, 60.0)),
                // Párrafo de dos líneas pegadas.
                line("Proveedor SAC", word("Proveedor", 100.0, 200.0, 200.0, 20.0), word("SAC", 310.0, 200.0, 60.0, 20.0)),
                line("RUC 20123456789", word("RUC", 100.0, 225.0, 60.0, 20.0), word("20123456789", 170.0, 225.0, 200.0, 20.0)),
                // Misma fila que la anterior pero en otra columna: bloque nuevo.
                line("Fecha 01/02/2026", word("Fecha", 800.0, 225.0, 90.0, 20.0), word("01/02/2026", 900.0, 225.0, 180.0, 20.0)),
                // Continúa la columna derecha.
                line("Serie F001", word("Serie", 800.0, 250.0, 80.0, 20.0), word("F001", 890.0, 250.0, 70.0, 20.0)),
                // Separación grande: bloque nuevo aunque la columna coincida.
                line("TOTAL 248.50", word("TOTAL", 800.0, 600.0, 80.0, 20.0), word("248.50", 890.0, 600.0, 90.0, 20.0)),
            ),
        )

        val mapped = WindowsOcrMapper.mapPage(0, SOURCE, page)

        assertEquals(
            listOf(
                "FACTURA",
                "Proveedor SAC\nRUC 20123456789",
                "Fecha 01/02/2026\nSerie F001",
                "TOTAL 248.50",
            ),
            mapped.blocks.map { it.text },
        )
        assertEquals(listOf(0, 1, 2, 3), mapped.blocks.map { it.position })
        assertEquals(listOf(0, 1), mapped.blocks[1].lines.map { it.position })
        assertEquals(InvoiceTextBoundingBox(100, 200, 370, 245), mapped.blocks[1].geometry.boundingBox)
        assertEquals(
            "FACTURA\nProveedor SAC\nRUC 20123456789\nFecha 01/02/2026\nSerie F001\nTOTAL 248.50",
            mapped.text,
        )
    }

    @Test
    fun `document keeps input order and rejects a page count or order mismatch`() {
        val second = SOURCE.copy(sourceImageId = ImageId.from(UUID.fromString("22222222-2222-2222-2222-222222222222")))
        val output = WindowsOcrOutput(
            pages = listOf(
                WindowsOcrPage(index = 0, width = 1_200, height = 1_600, lines = listOf(line("uno", word("uno", 1.0, 1.0, 10.0, 10.0)))),
                WindowsOcrPage(index = 1, width = 1_200, height = 1_600, lines = listOf(line("dos", word("dos", 1.0, 1.0, 10.0, 10.0)))),
            ),
        )

        val document = WindowsOcrMapper.mapDocument(output, listOf(SOURCE, second))

        assertEquals(listOf(0, 1), document.pages.map { it.pageIndex })
        assertEquals(listOf(SOURCE.sourceImageId, second.sourceImageId), document.pages.map { it.sourceImageId })
        assertEquals("uno\ndos", document.text)
        expectIllegalState { WindowsOcrMapper.mapDocument(output, listOf(SOURCE)) }
        expectIllegalState {
            WindowsOcrMapper.mapDocument(output.copy(pages = output.pages.reversed()), listOf(SOURCE, second))
        }
        expectIllegalState {
            WindowsOcrMapper.mapPage(0, SOURCE, WindowsOcrPage(index = 0, width = 0, height = 10))
        }
    }

    @Test
    fun `mapping checks cancellation inside the recognized hierarchy`() {
        val page = WindowsOcrPage(
            index = 0,
            width = 1_200,
            height = 1_600,
            lines = listOf(line("mucho texto", *Array(20) { word("w$it", it * 10.0, 10.0, 8.0, 8.0) })),
        )
        var checkpoints = 0

        try {
            WindowsOcrMapper.mapPage(0, SOURCE, page) {
                checkpoints++
                if (checkpoints == 5) throw CancellationException("cancelled")
            }
            fail("se esperaba cancelación")
        } catch (_: CancellationException) {
            assertEquals(5, checkpoints)
        }
    }

    @Test
    fun `embedded PowerShell script is pure ASCII and declares the exit code contract`() {
        val script = WindowsOcrScript.CONTENT
        assertTrue("El .ps1 debe ser ASCII para PowerShell 5.1", script.all { it.code in 0x09..0x7E || it == '\r' || it == '\n' })
        assertTrue(script.contains("TryCreateFromUserProfileLanguages"))
        assertTrue(script.contains("IAsyncOperation`1"))
        assertTrue(script.contains("Stop-Ocr ${WindowsOcrScript.EXIT_NO_OCR_LANGUAGE} "))
        assertTrue(script.contains("Stop-Ocr ${WindowsOcrScript.EXIT_WINRT_UNAVAILABLE} "))
        assertTrue(script.contains("Stop-Ocr ${WindowsOcrScript.EXIT_RECOGNITION_FAILED} "))
        assertTrue(script.contains("Stop-Ocr ${WindowsOcrScript.EXIT_BAD_REQUEST} "))
        assertTrue(script.startsWith("#"))
    }

    private fun expectIllegalState(block: () -> Unit) {
        try {
            block()
            fail("se esperaba IllegalStateException")
        } catch (_: IllegalStateException) {
        }
    }

    private fun line(text: String, vararg words: WindowsOcrWord) = WindowsOcrLine(text = text, words = words.toList())

    private fun word(text: String, x: Double, y: Double, w: Double, h: Double) =
        WindowsOcrWord(text = text, x = x, y = y, w = w, h = h)

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
