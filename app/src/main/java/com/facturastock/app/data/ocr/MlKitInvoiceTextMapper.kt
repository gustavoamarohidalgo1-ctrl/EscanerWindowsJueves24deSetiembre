package com.facturastock.app.data.ocr

import android.graphics.Point
import android.graphics.Rect
import com.facturastock.app.domain.model.InvoiceTextBlock
import com.facturastock.app.domain.model.InvoiceTextBoundingBox
import com.facturastock.app.domain.model.InvoiceTextElement
import com.facturastock.app.domain.model.InvoiceTextGeometry
import com.facturastock.app.domain.model.InvoiceTextLine
import com.facturastock.app.domain.model.InvoiceTextPage
import com.facturastock.app.domain.model.InvoiceTextPoint
import com.facturastock.app.domain.repository.OcrImageFile
import com.google.mlkit.vision.text.Text
import kotlin.math.roundToInt

/** Traduce inmediatamente la jerarquía ML Kit a snapshots internos sin filtrar sus clases. */
internal object MlKitInvoiceTextMapper {
    /**
     * Camino productivo: copia la jerarquía de ML Kit directamente a los modelos internos.
     * Construir primero un snapshot espejo duplicaba bloques, líneas, elementos, geometría y
     * listas durante cada página; el resultado de ML Kit no escapa de esta llamada igualmente.
     */
    fun mapPage(
        pageIndex: Int,
        source: OcrImageFile,
        recognized: Text,
        checkpoint: () -> Unit = {},
    ): InvoiceTextPage =
        InvoiceTextPage(
            sourceImageId = source.sourceImageId,
            pageIndex = pageIndex,
            widthPx = source.widthPx,
            heightPx = source.heightPx,
            text = recognized.text,
            blocks = recognized.textBlocks.mapIndexed { blockIndex, block ->
                checkpoint()
                InvoiceTextBlock(
                    position = blockIndex,
                    text = block.text,
                    languageTag = block.recognizedLanguage.normalizedLanguageTag(),
                    geometry = geometry(block.boundingBox, block.cornerPoints, source),
                    lines = block.lines.mapIndexed { lineIndex, line ->
                        checkpoint()
                        InvoiceTextLine(
                            position = lineIndex,
                            text = line.text,
                            languageTag = line.recognizedLanguage.normalizedLanguageTag(),
                            geometry = geometry(line.boundingBox, line.cornerPoints, source),
                            confidencePermille = line.confidence.toPermille(),
                            clockwiseAngleTenths = line.angle.toAngleTenths(),
                            elements = line.elements.mapIndexed { elementIndex, element ->
                                checkpoint()
                                InvoiceTextElement(
                                    position = elementIndex,
                                    text = element.text,
                                    languageTag = element.recognizedLanguage.normalizedLanguageTag(),
                                    geometry = geometry(
                                        element.boundingBox,
                                        element.cornerPoints,
                                        source,
                                    ),
                                    confidencePermille = element.confidence.toPermille(),
                                    clockwiseAngleTenths = element.angle.toAngleTenths(),
                                )
                            },
                        )
                    },
                )
            },
        )

    fun mapPage(
        pageIndex: Int,
        source: OcrImageFile,
        recognized: MlKitPageSnapshot,
        checkpoint: () -> Unit = {},
    ): InvoiceTextPage = InvoiceTextPage(
        sourceImageId = source.sourceImageId,
        pageIndex = pageIndex,
        widthPx = source.widthPx,
        heightPx = source.heightPx,
        text = recognized.text,
        blocks = recognized.blocks.mapIndexed { blockIndex, block ->
            checkpoint()
            InvoiceTextBlock(
                position = blockIndex,
                text = block.region.text,
                languageTag = block.region.languageTag.normalizedLanguageTag(),
                geometry = block.region.toGeometry(source),
                lines = block.lines.mapIndexed { lineIndex, line ->
                    checkpoint()
                    InvoiceTextLine(
                        position = lineIndex,
                        text = line.region.text,
                        languageTag = line.region.languageTag.normalizedLanguageTag(),
                        geometry = line.region.toGeometry(source),
                        confidencePermille = line.region.confidence.toPermille(),
                        clockwiseAngleTenths = line.region.angleDegrees.toAngleTenths(),
                        elements = line.elements.mapIndexed { elementIndex, element ->
                            checkpoint()
                            InvoiceTextElement(
                                position = elementIndex,
                                text = element.text,
                                languageTag = element.languageTag.normalizedLanguageTag(),
                                geometry = element.toGeometry(source),
                                confidencePermille = element.confidence.toPermille(),
                                clockwiseAngleTenths = element.angleDegrees.toAngleTenths(),
                            )
                        },
                    )
                },
            )
        },
    )

    private fun MlKitRegionSnapshot.toGeometry(source: OcrImageFile): InvoiceTextGeometry =
        geometry(
            boundingBox = boundingBox,
            cornerPoints = cornerPoints,
            source = source,
        )

    private fun geometry(
        boundingBox: Rect?,
        cornerPoints: Array<Point>?,
        source: OcrImageFile,
    ): InvoiceTextGeometry = InvoiceTextGeometry(
        boundingBox = boundingBox?.clipped(source.widthPx, source.heightPx),
        cornerPoints = cornerPoints
            ?.takeIf { it.size == CORNER_COUNT }
            ?.map { point ->
                InvoiceTextPoint(
                    xPx = point.x.coerceIn(0, source.widthPx - 1),
                    yPx = point.y.coerceIn(0, source.heightPx - 1),
                )
            }
            .orEmpty(),
    )

    private fun geometry(
        boundingBox: MlKitBox?,
        cornerPoints: List<MlKitPoint>,
        source: OcrImageFile,
    ): InvoiceTextGeometry = InvoiceTextGeometry(
        boundingBox = boundingBox?.clipped(source.widthPx, source.heightPx),
        cornerPoints = cornerPoints
            .takeIf { it.size == CORNER_COUNT }
            ?.map { point ->
                InvoiceTextPoint(
                    xPx = point.x.coerceIn(0, source.widthPx - 1),
                    yPx = point.y.coerceIn(0, source.heightPx - 1),
                )
            }
            .orEmpty(),
    )

    private fun MlKitBox.clipped(widthPx: Int, heightPx: Int): InvoiceTextBoundingBox? {
        val clippedLeft = left.coerceIn(0, widthPx - 1)
        val clippedTop = top.coerceIn(0, heightPx - 1)
        val clippedRight = right.coerceIn(0, widthPx)
        val clippedBottom = bottom.coerceIn(0, heightPx)
        if (clippedRight <= clippedLeft || clippedBottom <= clippedTop) return null
        return InvoiceTextBoundingBox(
            leftPx = clippedLeft,
            topPx = clippedTop,
            rightPx = clippedRight,
            bottomPx = clippedBottom,
        )
    }

    private fun Rect.clipped(widthPx: Int, heightPx: Int): InvoiceTextBoundingBox? {
        val clippedLeft = left.coerceIn(0, widthPx - 1)
        val clippedTop = top.coerceIn(0, heightPx - 1)
        val clippedRight = right.coerceIn(0, widthPx)
        val clippedBottom = bottom.coerceIn(0, heightPx)
        if (clippedRight <= clippedLeft || clippedBottom <= clippedTop) return null
        return InvoiceTextBoundingBox(
            leftPx = clippedLeft,
            topPx = clippedTop,
            rightPx = clippedRight,
            bottomPx = clippedBottom,
        )
    }

    private fun String?.normalizedLanguageTag(): String? =
        this?.takeUnless { it.isBlank() || it == UNDETERMINED_LANGUAGE }

    private fun Float?.toPermille(): Int? =
        this?.takeIf(Float::isFinite)?.times(1_000)?.roundToInt()?.coerceIn(0, 1_000)

    private fun Float?.toAngleTenths(): Int? =
        this?.takeIf(Float::isFinite)?.times(10)?.roundToInt()?.coerceIn(-1_800, 1_800)

    private const val UNDETERMINED_LANGUAGE = "und"
    private const val CORNER_COUNT = 4
}

internal data class MlKitPageSnapshot(
    val text: String,
    val blocks: List<MlKitBlockSnapshot>,
)

internal data class MlKitBlockSnapshot(
    val region: MlKitRegionSnapshot,
    val lines: List<MlKitLineSnapshot>,
)

internal data class MlKitLineSnapshot(
    val region: MlKitRegionSnapshot,
    val elements: List<MlKitRegionSnapshot>,
)

internal data class MlKitRegionSnapshot(
    val text: String,
    val languageTag: String? = null,
    val boundingBox: MlKitBox? = null,
    val cornerPoints: List<MlKitPoint> = emptyList(),
    val confidence: Float? = null,
    val angleDegrees: Float? = null,
)

internal data class MlKitBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

internal data class MlKitPoint(val x: Int, val y: Int)
