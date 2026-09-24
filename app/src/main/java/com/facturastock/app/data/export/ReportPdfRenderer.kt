package com.facturastock.app.data.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.PreparedReportPdf
import com.facturastock.app.domain.model.ReportPdfKind
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.OutputStream
import java.math.BigDecimal
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.CoroutineContext
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** PDF A4 con texto seleccionable, importes exactos y tablas que continúan entre páginas. */
internal class ReportPdfRenderer {
    suspend fun write(
        prepared: PreparedReportPdf,
        output: OutputStream,
    ) {
        val context = currentCoroutineContext()
        context.ensureActive()
        val document = PdfDocument()
        try {
            val page = ReportPages(document, prepared, context)
            try {
                page.introduction()
                // Orden del PDF diario: primero las ventas del día y después todo lo de deudores
                // (cobros del día y saldos pendientes).
                if (prepared.kind == ReportPdfKind.DAILY_SALES_WITH_DEBTORS) {
                    page.sales()
                    page.debtPayments()
                }
                page.debtors()
                page.finish()
                context.ensureActive()
                document.writeTo(output)
                context.ensureActive()
            } finally {
                // PdfDocument exige terminar la página abierta incluso si se cancela el trabajo.
                page.finish()
            }
        } finally {
            document.close()
        }
    }
}

private class ReportPages(
    private val document: PdfDocument,
    private val prepared: PreparedReportPdf,
    private val context: CoroutineContext,
) {
    private val snapshot = prepared.snapshot
    private val date = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT).withZone(snapshot.range.zoneId)
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT).withZone(snapshot.range.zoneId)
    private val stamp = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ROOT).withZone(snapshot.range.zoneId)
    private val fill = Paint()
    private val paints = mutableMapOf<Triple<Float, Boolean, Int>, TextPaint>()
    private var page: PdfDocument.Page? = null
    private var pageNumber = 0
    private var y = CONTENT_TOP

    fun introduction() {
        newPage()
        paragraph(snapshot.businessName, 17f, bold = true, after = 7f)
        paragraph(
            if (prepared.kind == ReportPdfKind.DAILY_SALES_WITH_DEBTORS) {
                "Ventas del día y deudores"
            } else {
                "Deudores pendientes"
            },
            15f,
            bold = true,
            color = ACCENT,
            after = 6f,
        )
        if (prepared.kind == ReportPdfKind.DAILY_SALES_WITH_DEBTORS) {
            paragraph("Día de ventas: ${date.format(snapshot.range.startInclusive)}", 10f, after = 4f)
        }
        paragraph("Generado: ${stamp.format(snapshot.generatedAt)} · Zona: ${snapshot.range.zoneId.id}", 8.5f, color = MUTED)
        paragraph("Deudores: saldos pendientes de todas las fechas al generar este documento.", 9f, color = MUTED, after = 12f)
    }

    fun sales() {
        section("Resumen de ventas del día")
        paragraph("${snapshot.sales.size} ventas confirmadas vigentes. Las ventas anuladas no se incluyen.", 9f)
        val totals = linkedMapOf<CurrencyCode, SaleAmounts>()
        totals[snapshot.primaryCurrency] = SaleAmounts()
        for (sale in snapshot.sales) {
            context.ensureActive()
            val amounts = totals.getOrPut(sale.totalCharged.currency) { SaleAmounts() }
            val amount = sale.totalCharged.toMajor()
            amounts.total = amounts.total.add(amount)
            if (sale.saleId in snapshot.saleDebtorNames) {
                amounts.credit = amounts.credit.add(amount)
            } else {
                amounts.cash = amounts.cash.add(amount)
            }
        }
        table(
            listOf("Moneda", "Total vendido", "Contado", "Crédito"),
            listOf(73, 150, 150, 150),
            totals.asSequence().map { (currency, total) ->
                listOf(currency.value, amount(total.total, currency), amount(total.cash, currency), amount(total.credit, currency))
            },
            rightAligned = setOf(1, 2, 3),
            continuation = "Resumen de ventas (continuación)",
        )
        paragraph("Los importes incluyen impuestos y descuentos. Una venta a crédito no equivale a dinero cobrado.", 8.5f, color = MUTED, after = 12f)
        if (snapshot.sales.isEmpty()) {
            paragraph("No se registraron ventas para este día.", 10f, after = 14f)
            return
        }
        section("Detalle de todas las ventas")
        snapshot.sales.forEachIndexed { index, sale ->
            context.ensureActive()
            ensureSpace(92f)
            val number = index + 1
            val debtor = snapshot.saleDebtorNames[sale.saleId]
            paragraph("Venta $number · ${time.format(sale.postedAt)} · ${if (debtor == null) "Contado" else "Crédito"}", 11f, bold = true, after = 4f)
            paragraph("Referencia: ${sale.saleId.value}", 8f, color = MUTED, after = 4f)
            if (debtor != null) paragraph("Cliente: $debtor", 9.5f, after = 5f)
            table(
                listOf("Producto", "Cantidad", "Importe"),
                listOf(294, 99, 130),
                sale.lines.asSequence().map { line ->
                    listOf(
                        line.productName,
                        "${line.quantity
                            ?.value
                            ?.stripTrailingZeros()
                            ?.toPlainString() ?: "No disponible"} ${line.unitCode}",
                        money(line.totalCharged),
                    )
                },
                rightAligned = setOf(1, 2),
                continuation = "Venta $number (continuación)",
                trailingSpace = 35f,
            )
            paragraph("Total venta $number: ${money(sale.totalCharged)}", 10f, bold = true, right = true, after = 14f)
        }
    }

    fun debtPayments() {
        if (snapshot.debtPayments.isEmpty()) return
        section("Cobros de deudas del día")
        paragraph("Pagos y abonos recibidos en la fecha del reporte, aunque la venta a crédito sea de otro día.", 9f)
        val totals = linkedMapOf(snapshot.primaryCurrency to BigDecimal.ZERO)
        for (item in snapshot.debtPayments) {
            context.ensureActive()
            val paid = item.payment.amount
            totals[paid.currency] = (totals[paid.currency] ?: BigDecimal.ZERO).add(paid.toMajor())
        }
        for ((currency, total) in totals) {
            paragraph("Cobrado de deudas ${currency.value}: ${amount(total, currency)}", 11f, bold = true, after = 5f)
        }
        table(
            listOf("Deudor / referencia", "Fecha y hora del pago", "Recibido"),
            listOf(303, 105, 115),
            snapshot.debtPayments.asSequence().map { item ->
                listOf(
                    "${item.debtorName}\nVenta: ${item.saleId.value}\nPago: ${item.payment.paymentId.value}",
                    stamp.format(item.payment.occurredAt),
                    money(item.payment.amount),
                )
            },
            rightAligned = setOf(2),
            continuation = "Cobros de deudas (continuación)",
        )
        paragraph(
            "Estos cobros no son nuevas ventas ni nuevos descuentos de inventario. No se suman al total vendido.",
            8.5f,
            color = MUTED,
            after = 12f,
        )
    }

    fun debtors() {
        section("Deudores pendientes · todas las fechas")
        paragraph("${snapshot.debts.size} cuentas por cobrar. Se muestra cada venta pendiente por separado.", 9f)
        paragraph("El saldo pendiente es actual; no se suma otra vez al total vendido del día.", 8.5f, color = MUTED, after = 8f)
        val totals = linkedMapOf<CurrencyCode, BigDecimal>()
        totals[snapshot.primaryCurrency] = BigDecimal.ZERO
        for (debt in snapshot.debts) {
            context.ensureActive()
            totals[debt.balance.currency] = (totals[debt.balance.currency] ?: BigDecimal.ZERO).add(debt.balance.toMajor())
        }
        for ((currency, total) in totals) paragraph("Saldo pendiente ${currency.value}: ${amount(total, currency)}", 11f, bold = true, after = 5f)
        if (snapshot.debts.isEmpty()) {
            paragraph("No hay deudores con saldo pendiente.", 10f, after = 12f)
            return
        }
        table(
            listOf("Deudor / referencia", "Fecha", "Original", "Abonado", "Pendiente"),
            listOf(190, 63, 90, 90, 90),
            snapshot.debts.asSequence().map { debt ->
                val due = debt.dueAt?.let { "\nVence: ${date.format(it)}" }.orEmpty()
                listOf(
                    "${debt.debtorName}\nVenta: ${debt.saleId.value}$due",
                    date.format(debt.createdAt),
                    money(debt.originalAmount),
                    amount(debt.originalAmount.toMajor().subtract(debt.balance.toMajor()), debt.balance.currency),
                    money(debt.balance),
                )
            },
            rightAligned = setOf(2, 3, 4),
            continuation = "Deudores pendientes (continuación)",
        )
        paragraph("Abonado = importe original menos saldo pendiente. No incluye cuentas ya pagadas ni ventas anuladas.", 8.5f, color = MUTED)
    }

    private fun section(title: String) {
        ensureSpace(85f)
        y += 8f
        paragraph(title, 12f, bold = true, color = ACCENT, after = 7f)
    }

    private fun paragraph(
        text: String,
        size: Float,
        bold: Boolean = false,
        color: Int = INK,
        right: Boolean = false,
        after: Float = 8f,
    ) {
        val layout = textLayout(text, WIDTH, size, bold, color, right)
        var line = 0
        while (line < layout.lineCount) {
            context.ensureActive()
            ensureSpace(lineHeight(layout, line))
            var end = line + 1
            while (end < layout.lineCount && layout.getLineBottom(end) - layout.getLineTop(line) <= BOTTOM - y) end++
            val height = layout.getLineBottom(end - 1) - layout.getLineTop(line)
            drawPart(layout, line, end, LEFT, y, WIDTH.toFloat())
            y += height
            line = end
        }
        y += after
    }

    private fun table(
        headers: List<String>,
        widths: List<Int>,
        rows: Sequence<List<String>>,
        rightAligned: Set<Int>,
        continuation: String,
        trailingSpace: Float = 0f,
    ) {
        require(widths.sum() == WIDTH && widths.size == headers.size)
        val headerLayouts = headers.mapIndexed { index, text -> textLayout(text, widths[index] - 2 * CELL_PAD, 8.5f, true, Color.WHITE, index in rightAligned) }
        val headerHeight = headerLayouts.maxOf { it.height } + 2f * CELL_PAD
        ensureSpace(headerHeight + 24f)

        fun header() {
            paintRow(headerLayouts, widths, 0, headerLayouts.maxOf { it.lineCount }, headerHeight, ACCENT)
        }

        fun continueTable() {
            newPage()
            paragraph(continuation, 10f, bold = true, color = ACCENT, after = 6f)
            header()
        }
        val continuationHeight = textLayout(continuation, WIDTH, 10f, true, ACCENT, false).height + 6f
        val freshRowSpace = BOTTOM - CONTENT_TOP - continuationHeight - headerHeight
        header()
        val indexedRows = rows.withIndex().iterator()
        while (indexedRows.hasNext()) {
            val (rowIndex, cells) = indexedRows.next()
            context.ensureActive()
            require(cells.size == widths.size)
            val layouts = cells.mapIndexed { index, cell -> textLayout(cell, widths[index] - 2 * CELL_PAD, 9f, false, INK, index in rightAligned) }
            val count = layouts.maxOf { it.lineCount }
            val reserve = if (indexedRows.hasNext()) 0f else trailingSpace
            val fullHeight = layouts.maxOf { it.height } + 2f * CELL_PAD
            // Keep ordinary rows together, reserving the sale total beside its final row.
            // Only a row taller than a fresh page needs to be divided into line fragments.
            if (fullHeight + reserve <= freshRowSpace && y + fullHeight + reserve > BOTTOM) continueTable()
            var first = 0
            while (first < count) {
                context.ensureActive()
                val tallestLine = layouts.filter { first < it.lineCount }.maxOf { lineHeight(it, first) }
                if (y + tallestLine + 2 * CELL_PAD > BOTTOM) {
                    continueTable()
                }
                val available = BOTTOM - y - 2 * CELL_PAD
                var end = min(count, first + max(1, floor(available / tallestLine).toInt()))

                fun partHeight(): Float =
                    layouts.maxOf { layout ->
                        if (first >= layout.lineCount) 0f else (layout.getLineBottom(min(end, layout.lineCount) - 1) - layout.getLineTop(first)).toFloat()
                    }
                while (end > first + 1 && partHeight() > available) end--
                val height = partHeight() + 2 * CELL_PAD
                paintRow(layouts, widths, first, end, height, if (rowIndex % 2 == 0) LIGHT else Color.WHITE)
                first = end
            }
        }
        y += 7f
    }

    private fun paintRow(
        layouts: List<StaticLayout>,
        widths: List<Int>,
        first: Int,
        end: Int,
        height: Float,
        background: Int,
    ) {
        val canvas = requireNotNull(page).canvas
        fill.color = background
        canvas.drawRect(LEFT, y, LEFT + WIDTH, y + height, fill)
        var x = LEFT
        layouts.forEachIndexed { index, layout ->
            if (first < layout.lineCount) drawPart(layout, first, min(end, layout.lineCount), x + CELL_PAD, y + CELL_PAD, (widths[index] - 2 * CELL_PAD).toFloat())
            x += widths[index]
        }
        y += height
    }

    private fun textLayout(
        text: String,
        width: Int,
        size: Float,
        bold: Boolean,
        color: Int,
        right: Boolean,
    ): StaticLayout {
        val paint =
            paints.getOrPut(Triple(size, bold, color)) {
                TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = size
                    this.color = color
                    typeface = if (bold) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
                }
            }
        return StaticLayout.Builder
            .obtain(text, 0, text.length, paint, width)
            .setAlignment(if (right) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setBreakStrategy(LineBreaker.BREAK_STRATEGY_HIGH_QUALITY)
            .build()
    }

    private fun drawPart(
        layout: StaticLayout,
        first: Int,
        end: Int,
        x: Float,
        top: Float,
        width: Float,
    ) {
        val canvas = requireNotNull(page).canvas
        val start = layout.getLineTop(first)
        val height = layout.getLineBottom(end - 1) - start
        canvas.save()
        canvas.clipRect(x, top, x + width, top + height)
        canvas.translate(x, top - start)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun lineHeight(
        layout: StaticLayout,
        line: Int,
    ): Float = (layout.getLineBottom(line) - layout.getLineTop(line)).toFloat()

    private fun ensureSpace(height: Float) {
        if (page == null || y + height > BOTTOM) newPage()
    }

    private fun newPage() {
        context.ensureActive()
        finish()
        pageNumber++
        val fresh = document.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
        page = fresh
        val canvas: Canvas = fresh.canvas
        fill.color = ACCENT
        canvas.drawRect(LEFT, 29f, LEFT + WIDTH, 32f, fill)
        val label =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 9f
                color = ACCENT
                typeface = Typeface.DEFAULT_BOLD
            }
        canvas.drawText("FACTURASTOCK", LEFT, 48f, label)
        label.typeface = Typeface.DEFAULT
        label.textSize = 8f
        label.color = MUTED
        val number = "Página $pageNumber"
        canvas.drawText(number, LEFT + WIDTH - label.measureText(number), 812f, label)
        canvas.drawText("Generado ${stamp.format(snapshot.generatedAt)}", LEFT, 812f, label)
        fill.color = RULE
        canvas.drawRect(LEFT, 797f, LEFT + WIDTH, 798f, fill)
        y = CONTENT_TOP
    }

    fun finish() {
        page?.let(document::finishPage)
        page = null
    }

    private fun money(money: Money): String = "${money.currency.value} ${amount(money.toMajor(), money.currency)}"

    private fun amount(
        value: BigDecimal,
        currency: CurrencyCode,
    ): String {
        val scale = Money.ofMinor(0, currency).toMajor().scale()
        return value.setScale(scale).toPlainString()
    }

    private class SaleAmounts {
        var total: BigDecimal = BigDecimal.ZERO
        var cash: BigDecimal = BigDecimal.ZERO
        var credit: BigDecimal = BigDecimal.ZERO
    }

    private companion object {
        const val WIDTH = 523
        const val LEFT = 36f
        const val CONTENT_TOP = 66f
        const val BOTTOM = 781f
        const val CELL_PAD = 5
        val INK: Int = Color.rgb(29, 44, 52)
        val ACCENT: Int = Color.rgb(18, 92, 93)
        val MUTED: Int = Color.rgb(83, 99, 110)
        val LIGHT: Int = Color.rgb(241, 246, 246)
        val RULE: Int = Color.rgb(211, 222, 225)
    }
}
