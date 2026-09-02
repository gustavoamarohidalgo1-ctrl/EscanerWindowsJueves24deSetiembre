package com.facturastock.app.domain.model

import com.facturastock.app.domain.model.id.ImageId

/**
 * Documento OCR independiente del proveedor. El texto se conserva sin normalizar y las páginas
 * mantienen exactamente el orden de entrada del lote preparado.
 */
data class InvoiceTextDocument(
    val pages: List<InvoiceTextPage>,
) {
    init {
        require(pages.map(InvoiceTextPage::pageIndex) == pages.indices.toList()) {
            "Las páginas OCR deben ser correlativas y conservar el orden de entrada"
        }
    }

    val text: String
        get() = pages.joinToString(separator = "\n", transform = InvoiceTextPage::text)
}

/** Coordenadas de una página JPEG preparada, con origen en la esquina superior izquierda. */
data class InvoiceTextPage(
    val sourceImageId: ImageId,
    val pageIndex: Int,
    val widthPx: Int,
    val heightPx: Int,
    val text: String,
    val blocks: List<InvoiceTextBlock>,
) {
    init {
        require(pageIndex >= 0) { "pageIndex OCR negativo: $pageIndex" }
        require(widthPx > 0 && heightPx > 0) { "Dimensiones OCR inválidas: ${widthPx}x$heightPx" }
        require(blocks.map(InvoiceTextBlock::position) == blocks.indices.toList()) {
            "Los bloques OCR deben ser correlativos dentro de la página"
        }
        blocks.forEach { block -> block.requireInside(widthPx, heightPx) }
    }
}

data class InvoiceTextBlock(
    val position: Int,
    val text: String,
    val languageTag: String?,
    val geometry: InvoiceTextGeometry,
    val lines: List<InvoiceTextLine>,
) {
    init {
        require(position >= 0) { "Posición de bloque OCR negativa: $position" }
        require(lines.map(InvoiceTextLine::position) == lines.indices.toList()) {
            "Las líneas OCR deben ser correlativas dentro del bloque"
        }
    }

    internal fun requireInside(widthPx: Int, heightPx: Int) {
        geometry.requireInside(widthPx, heightPx)
        lines.forEach { line -> line.requireInside(widthPx, heightPx) }
    }
}

data class InvoiceTextLine(
    val position: Int,
    val text: String,
    val languageTag: String?,
    val geometry: InvoiceTextGeometry,
    val confidencePermille: Int?,
    val clockwiseAngleTenths: Int?,
    val elements: List<InvoiceTextElement>,
) {
    init {
        require(position >= 0) { "Posición de línea OCR negativa: $position" }
        requireConfidence(confidencePermille)
        requireAngle(clockwiseAngleTenths)
        require(elements.map(InvoiceTextElement::position) == elements.indices.toList()) {
            "Los elementos OCR deben ser correlativos dentro de la línea"
        }
    }

    internal fun requireInside(widthPx: Int, heightPx: Int) {
        geometry.requireInside(widthPx, heightPx)
        elements.forEach { element -> element.geometry.requireInside(widthPx, heightPx) }
    }
}

data class InvoiceTextElement(
    val position: Int,
    val text: String,
    val languageTag: String?,
    val geometry: InvoiceTextGeometry,
    val confidencePermille: Int?,
    val clockwiseAngleTenths: Int?,
) {
    init {
        require(position >= 0) { "Posición de elemento OCR negativa: $position" }
        requireConfidence(confidencePermille)
        requireAngle(clockwiseAngleTenths)
    }
}

/** La caja puede faltar; ML Kit puede aportar además un cuadrilátero con perspectiva. */
data class InvoiceTextGeometry(
    val boundingBox: InvoiceTextBoundingBox?,
    val cornerPoints: List<InvoiceTextPoint>,
) {
    init {
        require(cornerPoints.isEmpty() || cornerPoints.size == CORNER_COUNT) {
            "La geometría OCR debe tener cero o cuatro esquinas"
        }
    }

    internal fun requireInside(widthPx: Int, heightPx: Int) {
        boundingBox?.let { box ->
            require(box.rightPx <= widthPx && box.bottomPx <= heightPx) {
                "Caja OCR fuera de la página"
            }
        }
        require(cornerPoints.all { point -> point.xPx < widthPx && point.yPx < heightPx }) {
            "Esquina OCR fuera de la página"
        }
    }

    private companion object {
        const val CORNER_COUNT = 4
    }
}

data class InvoiceTextBoundingBox(
    val leftPx: Int,
    val topPx: Int,
    val rightPx: Int,
    val bottomPx: Int,
) {
    init {
        require(leftPx >= 0 && topPx >= 0) { "Caja OCR con origen negativo" }
        require(rightPx > leftPx && bottomPx > topPx) { "Caja OCR vacía o invertida" }
    }
}

data class InvoiceTextPoint(
    val xPx: Int,
    val yPx: Int,
) {
    init {
        require(xPx >= 0 && yPx >= 0) { "Punto OCR negativo" }
    }
}

private fun requireConfidence(value: Int?) {
    require(value == null || value in 0..1_000) { "Confianza OCR fuera de 0..1000: $value" }
}

private fun requireAngle(value: Int?) {
    require(value == null || value in -1_800..1_800) { "Ángulo OCR fuera de -1800..1800: $value" }
}
