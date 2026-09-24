package com.facturastock.app.data.demo

import com.facturastock.app.data.files.DesktopImageCodec
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream

/**
 * Renderiza la página visible de la demo desde el mismo layout que alimenta al OCR fake.
 *
 * El resultado no contiene EXIF ni datos externos y puede entrar por la sobrecarga de captura
 * (`ImportDraftImageUseCase` con bytes JPEG). Después del preprocesado también es una entrada
 * legible para el OCR; el recorrido determinista puede usar directamente [DemoInvoiceFixture].
 *
 * En escritorio se dibuja con Java2D sobre un [BufferedImage] RGB con la misma geometría,
 * colores, tamaños de texto y calidad JPEG que la versión Android. La tipografía es la sans
 * serif lógica del sistema (Arial/Segoe en Windows) en lugar de Roboto, por lo que los píxeles
 * del texto no son idénticos, pero sí su posición, alineación y centrado vertical.
 */
object DemoInvoiceImageGenerator {
    fun jpegBytes(quality: Int = DEFAULT_JPEG_QUALITY): ByteArray {
        require(quality in MIN_JPEG_QUALITY..MAX_JPEG_QUALITY) {
            "La calidad JPEG demo debe estar entre $MIN_JPEG_QUALITY y $MAX_JPEG_QUALITY"
        }
        val bitmap = BufferedImage(
            DemoInvoiceFixture.BASE_PAGE_WIDTH_PX,
            DemoInvoiceFixture.BASE_PAGE_HEIGHT_PX,
            BufferedImage.TYPE_INT_RGB,
        )
        return try {
            val canvas = bitmap.createGraphics()
            try {
                drawInvoice(canvas)
            } finally {
                canvas.dispose()
            }
            ByteArrayOutputStream().use { output ->
                check(DesktopImageCodec.compressJpeg(bitmap, quality, output)) {
                    "No se pudo comprimir la factura demo"
                }
                output.toByteArray()
            }
        } finally {
            bitmap.flush()
        }
    }

    private fun drawInvoice(canvas: Graphics2D) {
        // Geometría exacta: sin normalización de trazos, como el Canvas de Android.
        canvas.setRenderingHint(
            RenderingHints.KEY_STROKE_CONTROL,
            RenderingHints.VALUE_STROKE_PURE,
        )
        canvas.setRenderingHint(
            RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
        )
        canvas.setRenderingHint(
            RenderingHints.KEY_FRACTIONALMETRICS,
            RenderingHints.VALUE_FRACTIONALMETRICS_ON,
        )
        canvas.color = Color.WHITE
        canvas.fillRect(
            0,
            0,
            DemoInvoiceFixture.BASE_PAGE_WIDTH_PX,
            DemoInvoiceFixture.BASE_PAGE_HEIGHT_PX,
        )
        val cells = DemoInvoiceFixture.canonicalCells()
        cells.forEach { cell ->
            drawCellBackground(canvas, cell)
            drawCellText(canvas, cell)
        }
        drawTableGrid(canvas, cells)
        drawPageFrame(canvas)
    }

    private fun drawCellBackground(canvas: Graphics2D, cell: DemoInvoiceCell) {
        val background = when (cell.style) {
            DemoInvoiceCellStyle.TABLE_HEADER -> HEADER_BACKGROUND
            DemoInvoiceCellStyle.SUMMARY_MONEY -> TOTAL_BACKGROUND
            DemoInvoiceCellStyle.DISCLAIMER -> DISCLAIMER_BACKGROUND
            else -> Color.WHITE
        }
        if (background == Color.WHITE) return
        // Relleno sin antialias, como el `Paint()` sin ANTI_ALIAS_FLAG de Android.
        canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
        canvas.color = background
        canvas.fill(cell.box.toRectangle())
    }

    private fun drawCellText(canvas: Graphics2D, cell: DemoInvoiceCell) {
        val font = textFont(cell.style)
        canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        canvas.font = font
        canvas.color = textColor(cell.style)
        val baseline = centeredBaseline(canvas, cell.box, font, cell.text)
        val x = when (cell.style) {
            DemoInvoiceCellStyle.TABLE_MONEY,
            DemoInvoiceCellStyle.SUMMARY_MONEY,
            -> cell.box.rightPx - CELL_HORIZONTAL_PADDING_PX

            else -> cell.box.leftPx + CELL_HORIZONTAL_PADDING_PX
        }
        // Paint.Align.RIGHT: el ancho de avance del texto termina exactamente en `x`.
        val drawX = if (isRightAligned(cell.style)) {
            x - font.getStringBounds(cell.text, canvas.fontRenderContext).width.toFloat()
        } else {
            x.toFloat()
        }
        canvas.drawString(cell.text, drawX, baseline)
    }

    private fun drawTableGrid(canvas: Graphics2D, cells: List<DemoInvoiceCell>) {
        canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        canvas.color = GRID_COLOR
        canvas.stroke = strokeOf(GRID_STROKE_WIDTH_PX)
        cells.asSequence()
            .filter { cell ->
                cell.style == DemoInvoiceCellStyle.TABLE_HEADER ||
                    cell.style == DemoInvoiceCellStyle.TABLE_LINE ||
                    cell.style == DemoInvoiceCellStyle.TABLE_MONEY ||
                    cell.style == DemoInvoiceCellStyle.SUMMARY_LABEL ||
                    cell.style == DemoInvoiceCellStyle.SUMMARY_MONEY
            }
            .forEach { cell -> canvas.draw(cell.box.toRectangle()) }
    }

    private fun drawPageFrame(canvas: Graphics2D) {
        canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        canvas.color = FRAME_COLOR
        canvas.stroke = strokeOf(FRAME_STROKE_WIDTH_PX)
        canvas.draw(
            Rectangle2D.Float(
                PAGE_FRAME_INSET_PX,
                PAGE_FRAME_INSET_PX,
                DemoInvoiceFixture.BASE_PAGE_WIDTH_PX - PAGE_FRAME_INSET_PX * 2,
                DemoInvoiceFixture.BASE_PAGE_HEIGHT_PX - PAGE_FRAME_INSET_PX * 2,
            ),
        )
    }

    /** Trazo centrado en el borde, extremo plano y unión en inglete (defaults de `Paint`). */
    private fun strokeOf(width: Float): BasicStroke =
        BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, ANDROID_MITER_LIMIT)

    private fun DemoInvoiceBox.toRectangle(): Rectangle2D.Float = Rectangle2D.Float(
        leftPx.toFloat(),
        topPx.toFloat(),
        (rightPx - leftPx).toFloat(),
        (bottomPx - topPx).toFloat(),
    )

    private fun textColor(style: DemoInvoiceCellStyle): Color = when (style) {
        DemoInvoiceCellStyle.DISCLAIMER -> DISCLAIMER_TEXT_COLOR
        else -> Color.BLACK
    }

    private fun textFont(style: DemoInvoiceCellStyle): Font {
        val size = when (style) {
            DemoInvoiceCellStyle.ISSUER -> 30f
            DemoInvoiceCellStyle.DOCUMENT_TITLE -> 28f
            DemoInvoiceCellStyle.HEADER -> 23f
            DemoInvoiceCellStyle.DISCLAIMER -> 22f
            DemoInvoiceCellStyle.TABLE_HEADER -> 24f
            DemoInvoiceCellStyle.TABLE_LINE,
            DemoInvoiceCellStyle.TABLE_MONEY,
            -> 24f

            DemoInvoiceCellStyle.SUMMARY_LABEL,
            DemoInvoiceCellStyle.SUMMARY_MONEY,
            -> 25f
        }
        val weight = when (style) {
            DemoInvoiceCellStyle.ISSUER,
            DemoInvoiceCellStyle.DOCUMENT_TITLE,
            DemoInvoiceCellStyle.TABLE_HEADER,
            DemoInvoiceCellStyle.SUMMARY_MONEY,
            -> Font.BOLD

            else -> Font.PLAIN
        }
        // Sobre un BufferedImage sin transformación, 1 pt de Java2D equivale a 1 px, igual que
        // `Paint.textSize`.
        return Font(Font.SANS_SERIF, weight, 1).deriveFont(size)
    }

    private fun isRightAligned(style: DemoInvoiceCellStyle): Boolean = when (style) {
        DemoInvoiceCellStyle.TABLE_MONEY,
        DemoInvoiceCellStyle.SUMMARY_MONEY,
        -> true

        else -> false
    }

    /**
     * Misma fórmula que Android: `centerY - (ascent + descent) / 2`, donde el ascent de Android
     * es negativo. Java2D lo expresa positivo, de ahí `centerY + (ascent - descent) / 2`.
     */
    private fun centeredBaseline(
        canvas: Graphics2D,
        box: DemoInvoiceBox,
        font: Font,
        text: String,
    ): Float {
        val metrics = font.getLineMetrics(text, canvas.fontRenderContext)
        val centerY = box.topPx + (box.bottomPx - box.topPx) / 2f
        return centerY + (metrics.ascent - metrics.descent) / 2f
    }

    private const val DEFAULT_JPEG_QUALITY = 94
    private const val MIN_JPEG_QUALITY = 70
    private const val MAX_JPEG_QUALITY = 100
    private const val CELL_HORIZONTAL_PADDING_PX = 7
    private const val GRID_STROKE_WIDTH_PX = 1f
    private const val FRAME_STROKE_WIDTH_PX = 3f
    private const val PAGE_FRAME_INSET_PX = 18f
    private const val ANDROID_MITER_LIMIT = 4f

    private val HEADER_BACKGROUND = Color(232, 238, 244)
    private val TOTAL_BACKGROUND = Color(228, 235, 248)
    private val DISCLAIMER_BACKGROUND = Color(255, 246, 210)
    private val DISCLAIMER_TEXT_COLOR = Color(116, 76, 0)
    private val GRID_COLOR = Color(150, 150, 150)
    private val FRAME_COLOR = Color(65, 65, 65)
}
