package com.facturastock.app.testing

import com.facturastock.app.domain.model.InvoiceTextBlock
import com.facturastock.app.domain.model.InvoiceTextBoundingBox
import com.facturastock.app.domain.model.InvoiceTextDocument
import com.facturastock.app.domain.model.InvoiceTextElement
import com.facturastock.app.domain.model.InvoiceTextGeometry
import com.facturastock.app.domain.model.InvoiceTextLine
import com.facturastock.app.domain.model.InvoiceTextPage
import com.facturastock.app.domain.model.InvoiceTextPoint
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.repository.InvoiceTextRecognizer
import com.facturastock.app.domain.repository.OcrImageFile

/**
 * Fake OCR determinista. Por defecto produce una jerarquía válida por página; los tests pueden
 * fijar texto por imagen, suspender una página o programar un único fallo.
 */
class FakeInvoiceTextRecognizer : InvoiceTextRecognizer {
    private val textByImage = mutableMapOf<ImageId, String>()
    private val recordedCalls = mutableListOf<List<OcrImageFile>>()

    var beforeRecognizePage: suspend (pageIndex: Int, page: OcrImageFile) -> Unit = { _, _ -> }
    var nextException: Exception? = null

    val calls: List<List<OcrImageFile>>
        get() = recordedCalls.map(List<OcrImageFile>::toList)

    fun seedText(imageId: ImageId, text: String) {
        textByImage[imageId] = text
    }

    override suspend fun recognize(pages: List<OcrImageFile>): InvoiceTextDocument {
        recordedCalls += pages.toList()
        val recognized = pages.mapIndexed { pageIndex, page ->
            beforeRecognizePage(pageIndex, page)
            nextException?.let { scheduled ->
                nextException = null
                throw scheduled
            }
            page.toDeterministicResult(
                pageIndex = pageIndex,
                text = textByImage[page.sourceImageId] ?: "FACTURA PAGINA ${pageIndex + 1}",
            )
        }
        return InvoiceTextDocument(recognized)
    }
}

private fun OcrImageFile.toDeterministicResult(pageIndex: Int, text: String): InvoiceTextPage {
    val geometry = deterministicGeometry(widthPx, heightPx)
    val elements = if (text.isBlank()) {
        emptyList()
    } else {
        listOf(
            InvoiceTextElement(
                position = 0,
                text = text,
                languageTag = "es",
                geometry = geometry,
                confidencePermille = 900,
                clockwiseAngleTenths = 0,
            ),
        )
    }
    val lines = if (elements.isEmpty()) {
        emptyList()
    } else {
        listOf(
            InvoiceTextLine(
                position = 0,
                text = text,
                languageTag = "es",
                geometry = geometry,
                confidencePermille = 900,
                clockwiseAngleTenths = 0,
                elements = elements,
            ),
        )
    }
    val blocks = if (lines.isEmpty()) {
        emptyList()
    } else {
        listOf(
            InvoiceTextBlock(
                position = 0,
                text = text,
                languageTag = "es",
                geometry = geometry,
                lines = lines,
            ),
        )
    }
    return InvoiceTextPage(
        sourceImageId = sourceImageId,
        pageIndex = pageIndex,
        widthPx = widthPx,
        heightPx = heightPx,
        text = text,
        blocks = blocks,
    )
}

private fun deterministicGeometry(widthPx: Int, heightPx: Int): InvoiceTextGeometry {
    if (widthPx < 2 || heightPx < 2) return InvoiceTextGeometry(null, emptyList())
    val right = minOf(widthPx, 120)
    val bottom = minOf(heightPx, 40)
    return InvoiceTextGeometry(
        boundingBox = InvoiceTextBoundingBox(0, 0, right, bottom),
        cornerPoints = listOf(
            InvoiceTextPoint(0, 0),
            InvoiceTextPoint(right - 1, 0),
            InvoiceTextPoint(right - 1, bottom - 1),
            InvoiceTextPoint(0, bottom - 1),
        ),
    )
}
