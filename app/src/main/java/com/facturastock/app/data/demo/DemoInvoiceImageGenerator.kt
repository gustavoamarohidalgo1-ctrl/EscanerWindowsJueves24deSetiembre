package com.facturastock.app.data.demo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import java.io.ByteArrayOutputStream

/**
 * Renderiza la página visible de la demo desde el mismo layout que alimenta al OCR fake.
 *
 * El resultado no contiene EXIF ni datos externos y puede entrar por la sobrecarga de captura
 * (`ImportDraftImageUseCase` con bytes JPEG). Después del preprocesado también es una entrada
 * legible para ML Kit; el recorrido determinista puede usar directamente [DemoInvoiceFixture].
 */
object DemoInvoiceImageGenerator {
    fun jpegBytes(quality: Int = DEFAULT_JPEG_QUALITY): ByteArray {
        require(quality in MIN_JPEG_QUALITY..MAX_JPEG_QUALITY) {
            "La calidad JPEG demo debe estar entre $MIN_JPEG_QUALITY y $MAX_JPEG_QUALITY"
        }
        val bitmap = createBitmap(
            DemoInvoiceFixture.BASE_PAGE_WIDTH_PX,
            DemoInvoiceFixture.BASE_PAGE_HEIGHT_PX,
            Bitmap.Config.ARGB_8888,
        )
        return try {
            drawInvoice(Canvas(bitmap))
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) {
                    "No se pudo comprimir la factura demo"
                }
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun drawInvoice(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        val cells = DemoInvoiceFixture.canonicalCells()
        cells.forEach { cell ->
            drawCellBackground(canvas, cell)
            drawCellText(canvas, cell)
        }
        drawTableGrid(canvas, cells)
        drawPageFrame(canvas)
    }

    private fun drawCellBackground(canvas: Canvas, cell: DemoInvoiceCell) {
        val background = when (cell.style) {
            DemoInvoiceCellStyle.TABLE_HEADER -> HEADER_BACKGROUND
            DemoInvoiceCellStyle.SUMMARY_MONEY -> TOTAL_BACKGROUND
            DemoInvoiceCellStyle.DISCLAIMER -> DISCLAIMER_BACKGROUND
            else -> Color.WHITE
        }
        if (background == Color.WHITE) return
        canvas.drawRect(
            cell.box.leftPx.toFloat(),
            cell.box.topPx.toFloat(),
            cell.box.rightPx.toFloat(),
            cell.box.bottomPx.toFloat(),
            fillPaint(background),
        )
    }

    private fun drawCellText(canvas: Canvas, cell: DemoInvoiceCell) {
        val textPaint = textPaint(cell.style)
        val baseline = centeredBaseline(cell.box, textPaint)
        val x = when (cell.style) {
            DemoInvoiceCellStyle.TABLE_MONEY,
            DemoInvoiceCellStyle.SUMMARY_MONEY,
            -> cell.box.rightPx - CELL_HORIZONTAL_PADDING_PX

            else -> cell.box.leftPx + CELL_HORIZONTAL_PADDING_PX
        }
        canvas.drawText(cell.text, x.toFloat(), baseline, textPaint)
    }

    private fun drawTableGrid(canvas: Canvas, cells: List<DemoInvoiceCell>) {
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GRID_COLOR
            style = Paint.Style.STROKE
            strokeWidth = GRID_STROKE_WIDTH_PX
        }
        cells.asSequence()
            .filter { cell ->
                cell.style == DemoInvoiceCellStyle.TABLE_HEADER ||
                    cell.style == DemoInvoiceCellStyle.TABLE_LINE ||
                    cell.style == DemoInvoiceCellStyle.TABLE_MONEY ||
                    cell.style == DemoInvoiceCellStyle.SUMMARY_LABEL ||
                    cell.style == DemoInvoiceCellStyle.SUMMARY_MONEY
            }
            .forEach { cell ->
                canvas.drawRect(
                    cell.box.leftPx.toFloat(),
                    cell.box.topPx.toFloat(),
                    cell.box.rightPx.toFloat(),
                    cell.box.bottomPx.toFloat(),
                    gridPaint,
                )
            }
    }

    private fun drawPageFrame(canvas: Canvas) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = FRAME_COLOR
            style = Paint.Style.STROKE
            strokeWidth = FRAME_STROKE_WIDTH_PX
        }
        canvas.drawRect(
            PAGE_FRAME_INSET_PX,
            PAGE_FRAME_INSET_PX,
            DemoInvoiceFixture.BASE_PAGE_WIDTH_PX - PAGE_FRAME_INSET_PX,
            DemoInvoiceFixture.BASE_PAGE_HEIGHT_PX - PAGE_FRAME_INSET_PX,
            paint,
        )
    }

    private fun fillPaint(colorValue: Int): Paint = Paint().apply {
        color = colorValue
        style = Paint.Style.FILL
    }

    private fun textPaint(style: DemoInvoiceCellStyle): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = when (style) {
            DemoInvoiceCellStyle.DISCLAIMER -> DISCLAIMER_TEXT_COLOR
            else -> Color.BLACK
        }
        textSize = when (style) {
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
        typeface = when (style) {
            DemoInvoiceCellStyle.ISSUER,
            DemoInvoiceCellStyle.DOCUMENT_TITLE,
            DemoInvoiceCellStyle.TABLE_HEADER,
            DemoInvoiceCellStyle.SUMMARY_MONEY,
            -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

            else -> Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        textAlign = when (style) {
            DemoInvoiceCellStyle.TABLE_MONEY,
            DemoInvoiceCellStyle.SUMMARY_MONEY,
            -> Paint.Align.RIGHT

            else -> Paint.Align.LEFT
        }
    }

    private fun centeredBaseline(box: DemoInvoiceBox, paint: Paint): Float {
        val metrics = paint.fontMetrics
        val centerY = box.topPx + (box.bottomPx - box.topPx) / 2f
        return centerY - (metrics.ascent + metrics.descent) / 2f
    }

    private const val DEFAULT_JPEG_QUALITY = 94
    private const val MIN_JPEG_QUALITY = 70
    private const val MAX_JPEG_QUALITY = 100
    private const val CELL_HORIZONTAL_PADDING_PX = 7
    private const val GRID_STROKE_WIDTH_PX = 1f
    private const val FRAME_STROKE_WIDTH_PX = 3f
    private const val PAGE_FRAME_INSET_PX = 18f

    private val HEADER_BACKGROUND = Color.rgb(232, 238, 244)
    private val TOTAL_BACKGROUND = Color.rgb(228, 235, 248)
    private val DISCLAIMER_BACKGROUND = Color.rgb(255, 246, 210)
    private val DISCLAIMER_TEXT_COLOR = Color.rgb(116, 76, 0)
    private val GRID_COLOR = Color.rgb(150, 150, 150)
    private val FRAME_COLOR = Color.rgb(65, 65, 65)
}
