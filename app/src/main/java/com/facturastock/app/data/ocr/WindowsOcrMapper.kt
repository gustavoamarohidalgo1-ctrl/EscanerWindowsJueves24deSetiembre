package com.facturastock.app.data.ocr

import com.facturastock.app.domain.model.InvoiceTextBlock
import com.facturastock.app.domain.model.InvoiceTextBoundingBox
import com.facturastock.app.domain.model.InvoiceTextDocument
import com.facturastock.app.domain.model.InvoiceTextElement
import com.facturastock.app.domain.model.InvoiceTextGeometry
import com.facturastock.app.domain.model.InvoiceTextLine
import com.facturastock.app.domain.model.InvoiceTextPage
import com.facturastock.app.domain.repository.OcrImageFile
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Salida cruda de [WindowsOcrScript]; ver el contrato JSON descrito en el propio script. */
@Serializable
internal data class WindowsOcrOutput(
    val pages: List<WindowsOcrPage> = emptyList(),
)

@Serializable
internal data class WindowsOcrPage(
    val index: Int,
    /** Tamaño en píxeles del bitmap entregado al motor (tras EXIF y posible reducción). */
    val width: Int,
    val height: Int,
    /** `OcrResult.TextAngle`: grados en sentido horario; null si Windows no lo detectó. */
    val angle: Double? = null,
    val lines: List<WindowsOcrLine> = emptyList(),
)

@Serializable
internal data class WindowsOcrLine(
    val text: String = "",
    val words: List<WindowsOcrWord> = emptyList(),
)

@Serializable
internal data class WindowsOcrWord(
    val text: String = "",
    val x: Double? = null,
    val y: Double? = null,
    val w: Double? = null,
    val h: Double? = null,
)

/**
 * Traduce la salida de Windows OCR a los modelos internos con la misma semántica que tenía el
 * mapper de ML Kit: texto sin normalizar, cajas recortadas a la página preparada, posiciones
 * correlativas, ángulo en décimas de grado y chequeos cooperativos de cancelación.
 *
 * Diferencias inevitables con ML Kit, documentadas:
 * - Windows OCR no agrupa en bloques: [groupIntoBlocks] junta líneas consecutivas por cercanía
 *   vertical y solape horizontal, parecido a los párrafos/columnas de ML Kit.
 * - Windows OCR no informa confianza: se usa [CONFIDENCE_PERMILLE] (null = desconocida), que el
 *   parser trata como "requiere confirmación" en vez de inventar una certeza que no existe.
 * - Windows OCR no detecta idioma por región (usa el del reconocedor): `languageTag` es null,
 *   igual que cuando ML Kit devolvía `und`.
 * - Solo hay rectángulos alineados a los ejes: `cornerPoints` queda vacío (el modelo lo admite).
 * - El ángulo es único por página (`TextAngle`) y se aplica a todas sus líneas y palabras.
 */
internal object WindowsOcrMapper {
    /** Confianza fija para todo lo reconocido por Windows OCR: desconocida. */
    val CONFIDENCE_PERMILLE: Int? = null

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /** @throws kotlinx.serialization.SerializationException si el JSON no respeta el contrato. */
    fun parse(text: String): WindowsOcrOutput = json.decodeFromString(WindowsOcrOutput.serializer(), text)

    /**
     * Empareja cada página de [output] con su imagen de entrada; el script debe devolver
     * exactamente una página por imagen y en el mismo orden.
     */
    fun mapDocument(
        output: WindowsOcrOutput,
        sources: List<OcrImageFile>,
        checkpoint: () -> Unit = {},
    ): InvoiceTextDocument {
        check(output.pages.size == sources.size) {
            "Windows OCR devolvió ${output.pages.size} páginas para ${sources.size} imágenes"
        }
        return InvoiceTextDocument(
            sources.mapIndexed { pageIndex, source ->
                checkpoint()
                val page = output.pages[pageIndex]
                check(page.index == pageIndex) { "Windows OCR devolvió páginas fuera de orden" }
                mapPage(pageIndex, source, page, checkpoint)
            },
        )
    }

    fun mapPage(
        pageIndex: Int,
        source: OcrImageFile,
        recognized: WindowsOcrPage,
        checkpoint: () -> Unit = {},
    ): InvoiceTextPage {
        check(recognized.width > 0 && recognized.height > 0) { "Dimensiones de Windows OCR inválidas" }
        // El motor puede haber recibido la imagen reducida: se lleva todo al espacio de la
        // página preparada, que es el que usa el resto de la app.
        val scaleX = source.widthPx.toDouble() / recognized.width
        val scaleY = source.heightPx.toDouble() / recognized.height
        val angleTenths = recognized.angle.toAngleTenths()

        val lines = recognized.lines.mapNotNull { line ->
            checkpoint()
            val elements = line.words
                .filter { word -> word.text.isNotBlank() }
                .mapIndexed { elementIndex, word ->
                    checkpoint()
                    InvoiceTextElement(
                        position = elementIndex,
                        text = word.text,
                        languageTag = null,
                        geometry = geometry(word.box(scaleX, scaleY, source)),
                        confidencePermille = CONFIDENCE_PERMILLE,
                        clockwiseAngleTenths = angleTenths,
                    )
                }
            val text = line.text.takeIf(String::isNotBlank)
                ?: elements.joinToString(" ", transform = InvoiceTextElement::text)
            if (text.isBlank()) return@mapNotNull null
            PendingLine(
                text = text,
                box = union(elements.mapNotNull { it.geometry.boundingBox }),
                elements = elements,
            )
        }

        val blocks = groupIntoBlocks(lines).mapIndexed { blockIndex, blockLines ->
            checkpoint()
            InvoiceTextBlock(
                position = blockIndex,
                text = blockLines.joinToString("\n", transform = PendingLine::text),
                languageTag = null,
                geometry = geometry(union(blockLines.mapNotNull(PendingLine::box))),
                lines = blockLines.mapIndexed { lineIndex, line ->
                    checkpoint()
                    InvoiceTextLine(
                        position = lineIndex,
                        text = line.text,
                        languageTag = null,
                        geometry = geometry(line.box),
                        confidencePermille = CONFIDENCE_PERMILLE,
                        clockwiseAngleTenths = angleTenths,
                        elements = line.elements,
                    )
                },
            )
        }
        return InvoiceTextPage(
            sourceImageId = source.sourceImageId,
            pageIndex = pageIndex,
            widthPx = source.widthPx,
            heightPx = source.heightPx,
            // Igual que `Text.getText()` de ML Kit: bloques separados por salto de línea.
            text = blocks.joinToString("\n", transform = InvoiceTextBlock::text),
            blocks = blocks,
        )
    }

    /**
     * Agrupa líneas consecutivas (en el orden de lectura de Windows) en bloques. Una línea abre
     * un bloque nuevo si queda muy separada verticalmente de la anterior, si sube (otra columna
     * o la misma fila más a la derecha), si no se solapa horizontalmente con el bloque o si su
     * altura es muy distinta (p. ej. un título). Las líneas sin caja siguen al bloque actual.
     */
    internal fun groupIntoBlocks(lines: List<PendingLine>): List<List<PendingLine>> {
        val blocks = mutableListOf<MutableList<PendingLine>>()
        var blockBox: InvoiceTextBoundingBox? = null
        var previousBox: InvoiceTextBoundingBox? = null
        for (line in lines) {
            val box = line.box
            val current = blocks.lastOrNull()
            val startsBlock = current == null ||
                (box != null && previousBox != null && blockBox != null && !continuesBlock(previousBox, blockBox, box))
            if (startsBlock) {
                blocks += mutableListOf(line)
                blockBox = box
            } else {
                current!! += line
                blockBox = if (box == null) blockBox else union(listOfNotNull(blockBox, box))
            }
            if (box != null) previousBox = box
        }
        return blocks
    }

    private fun continuesBlock(
        previous: InvoiceTextBoundingBox,
        block: InvoiceTextBoundingBox,
        line: InvoiceTextBoundingBox,
    ): Boolean {
        val previousHeight = previous.bottomPx - previous.topPx
        val lineHeight = line.bottomPx - line.topPx
        val taller = max(previousHeight, lineHeight)
        val shorter = min(previousHeight, lineHeight)
        val gap = line.topPx - previous.bottomPx
        val horizontalOverlap = min(block.rightPx, line.rightPx) - max(block.leftPx, line.leftPx)
        return gap <= taller * MAX_GAP_TO_HEIGHT &&
            gap >= -shorter * MAX_OVERLAP_TO_HEIGHT &&
            horizontalOverlap > 0 &&
            taller <= shorter * MAX_HEIGHT_RATIO
    }

    private fun WindowsOcrWord.box(
        scaleX: Double,
        scaleY: Double,
        source: OcrImageFile,
    ): InvoiceTextBoundingBox? {
        val left = x ?: return null
        val top = y ?: return null
        val width = w ?: return null
        val height = h ?: return null
        if (!(left.isFinite() && top.isFinite() && width.isFinite() && height.isFinite())) return null
        return clipped(
            left = floor(left * scaleX),
            top = floor(top * scaleY),
            right = ceil((left + width) * scaleX),
            bottom = ceil((top + height) * scaleY),
            widthPx = source.widthPx,
            heightPx = source.heightPx,
        )
    }

    /** Mismo recorte que el mapper de ML Kit: origen dentro de la página y caja no vacía. */
    private fun clipped(
        left: Double,
        top: Double,
        right: Double,
        bottom: Double,
        widthPx: Int,
        heightPx: Int,
    ): InvoiceTextBoundingBox? {
        val clippedLeft = left.toIntSaturated().coerceIn(0, widthPx - 1)
        val clippedTop = top.toIntSaturated().coerceIn(0, heightPx - 1)
        val clippedRight = right.toIntSaturated().coerceIn(0, widthPx)
        val clippedBottom = bottom.toIntSaturated().coerceIn(0, heightPx)
        if (clippedRight <= clippedLeft || clippedBottom <= clippedTop) return null
        return InvoiceTextBoundingBox(
            leftPx = clippedLeft,
            topPx = clippedTop,
            rightPx = clippedRight,
            bottomPx = clippedBottom,
        )
    }

    private fun Double.toIntSaturated(): Int = coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble()).toInt()

    private fun union(boxes: List<InvoiceTextBoundingBox>): InvoiceTextBoundingBox? {
        if (boxes.isEmpty()) return null
        return InvoiceTextBoundingBox(
            leftPx = boxes.minOf(InvoiceTextBoundingBox::leftPx),
            topPx = boxes.minOf(InvoiceTextBoundingBox::topPx),
            rightPx = boxes.maxOf(InvoiceTextBoundingBox::rightPx),
            bottomPx = boxes.maxOf(InvoiceTextBoundingBox::bottomPx),
        )
    }

    private fun geometry(box: InvoiceTextBoundingBox?): InvoiceTextGeometry =
        InvoiceTextGeometry(boundingBox = box, cornerPoints = emptyList())

    private fun Double?.toAngleTenths(): Int? =
        this?.takeIf(Double::isFinite)?.times(10)?.roundToInt()?.coerceIn(-1_800, 1_800)

    /** Separación vertical máxima (en alturas de línea) para seguir en el mismo bloque. */
    private const val MAX_GAP_TO_HEIGHT = 0.8

    /** Cuánto puede "subir" una línea respecto de la anterior sin considerarse otra columna. */
    private const val MAX_OVERLAP_TO_HEIGHT = 0.5

    /** Relación máxima de alturas entre líneas vecinas del mismo bloque. */
    private const val MAX_HEIGHT_RATIO = 1.8

    internal class PendingLine(
        val text: String,
        val box: InvoiceTextBoundingBox?,
        val elements: List<InvoiceTextElement>,
    )
}
