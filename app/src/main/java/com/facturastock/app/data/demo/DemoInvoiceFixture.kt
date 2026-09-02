package com.facturastock.app.data.demo

import com.facturastock.app.domain.model.DemoPurchaseScenario
import com.facturastock.app.domain.model.InvoiceTextBlock
import com.facturastock.app.domain.model.InvoiceTextBoundingBox
import com.facturastock.app.domain.model.InvoiceTextDocument
import com.facturastock.app.domain.model.InvoiceTextGeometry
import com.facturastock.app.domain.model.InvoiceTextLine
import com.facturastock.app.domain.model.InvoiceTextPage
import com.facturastock.app.domain.repository.InvoiceTextRecognizer
import com.facturastock.app.domain.repository.OcrImageFile
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Tipo de resolución de producto que la factura sintética ejercita durante la demo. */
enum class DemoProductCase {
    EXISTING,
    AMBIGUOUS,
    NEW,
}

/** Una fila canónica de la factura demo; los importes se expresan siempre en céntimos PEN. */
data class DemoInvoiceLine(
    val position: Int,
    val supplierCode: String,
    val description: String,
    val quantityText: String,
    val unitCode: String,
    val unitPriceMinorUnits: Long,
    /** IGV incluido asignado explícitamente a la fila; la suma coincide con la cabecera. */
    val taxMinorUnits: Long,
    val lineTotalMinorUnits: Long,
    val productCase: DemoProductCase,
) {
    init {
        require(position >= 0) { "La posición demo no puede ser negativa" }
        require(supplierCode.isNotBlank()) { "La línea demo necesita código de proveedor" }
        require(description.startsWith(DemoInvoiceFixture.DEMO_PREFIX)) {
            "Toda descripción sintética debe llevar el prefijo [DEMO]"
        }
        require(quantityText == "1") { "La factura canónica usa cantidad unitaria" }
        require(unitCode.isNotBlank()) { "La línea demo necesita unidad" }
        require(unitPriceMinorUnits > 0L) { "El precio demo debe ser positivo" }
        require(taxMinorUnits in 0..lineTotalMinorUnits) {
            "El IGV demo debe caber dentro del importe bruto de la fila"
        }
        require(lineTotalMinorUnits == unitPriceMinorUnits) {
            "Con cantidad unitaria, precio y total deben coincidir"
        }
    }
}

/**
 * Fuente canónica y 100 % sintética del escenario visible de demostración.
 *
 * Implementa [InvoiceTextRecognizer] para poder sustituir el OCR real sin cambiar el contrato del
 * pipeline. La jerarquía fake conserva geometría de celdas, no solo texto plano, por lo que pasa
 * por los parsers de cabecera, tabla y totales exactamente igual que un resultado de ML Kit. La
 * página JPEG de [DemoInvoiceImageGenerator] se dibuja a partir de estas mismas celdas.
 */
object DemoInvoiceFixture : InvoiceTextRecognizer {
    const val DEMO_PREFIX: String = "[DEMO]"
    const val SUPPLIER_LEGAL_NAME: String = DemoPurchaseScenario.PRIMARY_SUPPLIER_LEGAL_NAME
    const val SUPPLIER_RUC: String = DemoPurchaseScenario.PRIMARY_SUPPLIER_RUC
    const val DOCUMENT_NUMBER: String = DemoPurchaseScenario.DOCUMENT_NUMBER
    const val CURRENCY_CODE: String = "PEN"
    const val EXPECTED_LINE_COUNT: Int = DemoPurchaseScenario.TOTAL_LINE_COUNT

    const val SUBTOTAL_MINOR_UNITS: Long = 8_288L
    const val IGV_MINOR_UNITS: Long = 1_492L
    const val CALCULATED_TOTAL_MINOR_UNITS: Long = 9_780L
    const val TARGET_TOTAL_MINOR_UNITS: Long = 9_783L
    const val REQUIRED_ADJUSTMENT_MINOR_UNITS: Long = 3L
    const val REQUIRED_ADJUSTMENT_REASON: String =
        "[DEMO] Diferencia controlada entre suma calculada y total del comprobante"

    const val BASE_PAGE_WIDTH_PX: Int = 1_600
    const val BASE_PAGE_HEIGHT_PX: Int = 2_600

    val issueDate: LocalDate = LocalDate.parse(DemoPurchaseScenario.DOCUMENT_DATE_ISO)
    val issueDateText: String = issueDate.format(DateTimeFormatter.ofPattern("dd/MM/uuuu"))

    val lines: List<DemoInvoiceLine> = buildLines().also { fixtureLines ->
        require(fixtureLines.size == EXPECTED_LINE_COUNT)
        require(fixtureLines.map(DemoInvoiceLine::position) == fixtureLines.indices.toList())
        require(fixtureLines.sumOf(DemoInvoiceLine::lineTotalMinorUnits) == CALCULATED_TOTAL_MINOR_UNITS)
        require(fixtureLines.sumOf(DemoInvoiceLine::taxMinorUnits) == IGV_MINOR_UNITS)
        require(
            fixtureLines.sumOf { it.lineTotalMinorUnits - it.taxMinorUnits } ==
                SUBTOTAL_MINOR_UNITS,
        )
        require(SUBTOTAL_MINOR_UNITS + IGV_MINOR_UNITS == CALCULATED_TOTAL_MINOR_UNITS)
        require(TARGET_TOTAL_MINOR_UNITS - CALCULATED_TOTAL_MINOR_UNITS == REQUIRED_ADJUSTMENT_MINOR_UNITS)
        require(fixtureLines.count { it.productCase == DemoProductCase.AMBIGUOUS } == 1)
        require(fixtureLines.count { it.productCase == DemoProductCase.NEW } == 1)
    }

    override suspend fun recognize(pages: List<OcrImageFile>): InvoiceTextDocument =
        documentFor(pages)

    /** La demostración es deliberadamente una sola página; no inventa ni descarta páginas. */
    fun documentFor(pages: List<OcrImageFile>): InvoiceTextDocument {
        require(pages.size == 1) { "La factura demo debe procesarse como una sola página" }
        return InvoiceTextDocument(listOf(pageFor(pages.single())))
    }

    fun pageFor(source: OcrImageFile): InvoiceTextPage {
        require(source.widthPx >= MIN_RENDERED_WIDTH_PX && source.heightPx >= MIN_RENDERED_HEIGHT_PX) {
            "La página demo preparada es demasiado pequeña para conservar su tabla"
        }
        val cells = canonicalCells()
        val blocks = cells.mapIndexed { position, cell ->
            val geometry = InvoiceTextGeometry(
                boundingBox = cell.box.scaledTo(source.widthPx, source.heightPx),
                cornerPoints = emptyList(),
            )
            InvoiceTextBlock(
                position = position,
                text = cell.text,
                languageTag = LANGUAGE_TAG,
                geometry = geometry,
                lines = listOf(
                    InvoiceTextLine(
                        position = 0,
                        text = cell.text,
                        languageTag = LANGUAGE_TAG,
                        geometry = geometry,
                        confidencePermille = FAKE_CONFIDENCE_PERMILLE,
                        clockwiseAngleTenths = 0,
                        elements = emptyList(),
                    ),
                ),
            )
        }
        return InvoiceTextPage(
            sourceImageId = source.sourceImageId,
            pageIndex = 0,
            widthPx = source.widthPx,
            heightPx = source.heightPx,
            text = cells.joinToString(separator = "\n", transform = DemoInvoiceCell::text),
            blocks = blocks,
        )
    }

    internal fun canonicalCells(): List<DemoInvoiceCell> = buildList {
        add(
            cell(
                text = "PROVEEDOR: $SUPPLIER_LEGAL_NAME",
                left = PAGE_MARGIN_PX,
                top = 50,
                right = 980,
                bottom = 100,
                style = DemoInvoiceCellStyle.ISSUER,
            ),
        )
        add(
            cell(
                text = "RUC: $SUPPLIER_RUC",
                left = PAGE_MARGIN_PX,
                top = 115,
                right = 760,
                bottom = 155,
                style = DemoInvoiceCellStyle.HEADER,
            ),
        )
        add(
            cell(
                text = "FACTURA ELECTRÓNICA",
                left = 1_060,
                top = 45,
                right = 1_550,
                bottom = 90,
                style = DemoInvoiceCellStyle.DOCUMENT_TITLE,
            ),
        )
        add(
            cell(
                text = "NRO: $DOCUMENT_NUMBER",
                left = 1_060,
                top = 105,
                right = 1_550,
                bottom = 150,
                style = DemoInvoiceCellStyle.DOCUMENT_TITLE,
            ),
        )
        add(
            cell(
                text = "FECHA DE EMISIÓN: $issueDateText",
                left = 1_060,
                top = 170,
                right = 1_550,
                bottom = 210,
                style = DemoInvoiceCellStyle.HEADER,
            ),
        )
        add(
            cell(
                text = "MONEDA: $CURRENCY_CODE",
                left = 1_060,
                top = 225,
                right = 1_550,
                bottom = 265,
                style = DemoInvoiceCellStyle.HEADER,
            ),
        )
        add(
            cell(
                text = "$DEMO_PREFIX FACTURA FICTICIA - SIN VALOR TRIBUTARIO",
                left = PAGE_MARGIN_PX,
                top = 235,
                right = 980,
                bottom = 275,
                style = DemoInvoiceCellStyle.DISCLAIMER,
            ),
        )

        add(cell("CÓDIGO", 40, TABLE_HEADER_TOP_PX, 260, TABLE_HEADER_BOTTOM_PX, DemoInvoiceCellStyle.TABLE_HEADER))
        add(cell("DESCRIPCIÓN", 280, TABLE_HEADER_TOP_PX, 920, TABLE_HEADER_BOTTOM_PX, DemoInvoiceCellStyle.TABLE_HEADER))
        add(cell("CANT.", 940, TABLE_HEADER_TOP_PX, 1_040, TABLE_HEADER_BOTTOM_PX, DemoInvoiceCellStyle.TABLE_HEADER))
        add(cell("U.M.", 1_060, TABLE_HEADER_TOP_PX, 1_140, TABLE_HEADER_BOTTOM_PX, DemoInvoiceCellStyle.TABLE_HEADER))
        add(cell("P. UNITARIO", 1_160, TABLE_HEADER_TOP_PX, 1_340, TABLE_HEADER_BOTTOM_PX, DemoInvoiceCellStyle.TABLE_HEADER))
        add(cell("IMPORTE", 1_360, TABLE_HEADER_TOP_PX, 1_560, TABLE_HEADER_BOTTOM_PX, DemoInvoiceCellStyle.TABLE_HEADER))

        lines.forEach { line ->
            val top = FIRST_LINE_TOP_PX + line.position * LINE_HEIGHT_PX
            val bottom = top + LINE_BOX_HEIGHT_PX
            add(cell(line.supplierCode, 40, top, 260, bottom, DemoInvoiceCellStyle.TABLE_LINE))
            add(cell(line.description, 280, top, 920, bottom, DemoInvoiceCellStyle.TABLE_LINE))
            add(cell(line.quantityText, 940, top, 1_040, bottom, DemoInvoiceCellStyle.TABLE_LINE))
            add(cell(line.unitCode, 1_060, top, 1_140, bottom, DemoInvoiceCellStyle.TABLE_LINE))
            add(
                cell(
                    formatMinorUnits(line.unitPriceMinorUnits),
                    1_160,
                    top,
                    1_340,
                    bottom,
                    DemoInvoiceCellStyle.TABLE_MONEY,
                ),
            )
            add(
                cell(
                    formatMinorUnits(line.lineTotalMinorUnits),
                    1_360,
                    top,
                    1_560,
                    bottom,
                    DemoInvoiceCellStyle.TABLE_MONEY,
                ),
            )
        }

        addSummaryRow("OP. GRAVADA", SUBTOTAL_MINOR_UNITS, SUMMARY_TOP_PX)
        addSummaryRow("OP. EXONERADA", 0L, SUMMARY_TOP_PX + SUMMARY_ROW_HEIGHT_PX)
        addSummaryRow("OP. INAFECTA", 0L, SUMMARY_TOP_PX + SUMMARY_ROW_HEIGHT_PX * 2)
        addSummaryRow("SUBTOTAL", SUBTOTAL_MINOR_UNITS, SUMMARY_TOP_PX + SUMMARY_ROW_HEIGHT_PX * 3)
        addSummaryRow("IGV 18%", IGV_MINOR_UNITS, SUMMARY_TOP_PX + SUMMARY_ROW_HEIGHT_PX * 4)
        addSummaryRow("TOTAL A PAGAR", TARGET_TOTAL_MINOR_UNITS, SUMMARY_TOP_PX + SUMMARY_ROW_HEIGHT_PX * 5)

        add(
            cell(
                text = "$DEMO_PREFIX 38 líneas | suma calculada S/ 97.80 | total objetivo S/ 97.83",
                left = PAGE_MARGIN_PX,
                top = 2_470,
                right = 1_560,
                bottom = 2_520,
                style = DemoInvoiceCellStyle.DISCLAIMER,
            ),
        )
    }

    private fun MutableList<DemoInvoiceCell>.addSummaryRow(
        label: String,
        minorUnits: Long,
        top: Int,
    ) {
        val bottom = top + SUMMARY_BOX_HEIGHT_PX
        add(cell(label, 1_000, top, 1_340, bottom, DemoInvoiceCellStyle.SUMMARY_LABEL))
        add(
            cell(
                "S/ ${formatMinorUnits(minorUnits)}",
                1_360,
                top,
                1_560,
                bottom,
                DemoInvoiceCellStyle.SUMMARY_MONEY,
            ),
        )
    }

    private fun buildLines(): List<DemoInvoiceLine> {
        val existingLines = List(DemoPurchaseScenario.EXISTING_LINE_COUNT) { position ->
            val product = DemoPurchaseScenario.EXISTING_PRODUCTS[
                position % DemoPurchaseScenario.EXISTING_PRODUCTS.size
            ]
            DemoInvoiceLine(
                position = position,
                supplierCode = product.sku,
                description = product.name,
                quantityText = "1",
                unitCode = product.unitCode,
                unitPriceMinorUnits = STANDARD_LINE_MINOR_UNITS,
                taxMinorUnits = if (position < LOWER_TAX_LINE_COUNT) {
                    LOWER_STANDARD_LINE_TAX_MINOR_UNITS
                } else {
                    UPPER_STANDARD_LINE_TAX_MINOR_UNITS
                },
                lineTotalMinorUnits = STANDARD_LINE_MINOR_UNITS,
                productCase = DemoProductCase.EXISTING,
            )
        }
        return existingLines + listOf(
            DemoInvoiceLine(
                position = DemoPurchaseScenario.EXISTING_LINE_COUNT,
                supplierCode = DemoPurchaseScenario.AMBIGUOUS_SUPPLIER_CODE,
                description = DemoPurchaseScenario.AMBIGUOUS_PRODUCT_DESCRIPTION,
                quantityText = "1",
                unitCode = "NIU",
                unitPriceMinorUnits = STANDARD_LINE_MINOR_UNITS,
                taxMinorUnits = UPPER_STANDARD_LINE_TAX_MINOR_UNITS,
                lineTotalMinorUnits = STANDARD_LINE_MINOR_UNITS,
                productCase = DemoProductCase.AMBIGUOUS,
            ),
            DemoInvoiceLine(
                position = DemoPurchaseScenario.EXISTING_LINE_COUNT +
                    DemoPurchaseScenario.AMBIGUOUS_LINE_COUNT,
                supplierCode = DemoPurchaseScenario.NEW_SUPPLIER_CODE,
                description = DemoPurchaseScenario.NEW_PRODUCT_DESCRIPTION,
                quantityText = "1",
                unitCode = "NIU",
                unitPriceMinorUnits = FINAL_LINE_MINOR_UNITS,
                taxMinorUnits = FINAL_LINE_TAX_MINOR_UNITS,
                lineTotalMinorUnits = FINAL_LINE_MINOR_UNITS,
                productCase = DemoProductCase.NEW,
            ),
        )
    }

    private fun cell(
        text: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        style: DemoInvoiceCellStyle,
    ): DemoInvoiceCell = DemoInvoiceCell(
        text = text,
        box = DemoInvoiceBox(left, top, right, bottom),
        style = style,
    )

    internal fun formatMinorUnits(minorUnits: Long): String {
        require(minorUnits >= 0L) { "La factura demo no usa importes negativos" }
        val major = minorUnits / MINOR_UNITS_PER_SOL
        val cents = minorUnits % MINOR_UNITS_PER_SOL
        return "$major.${cents.toString().padStart(2, '0')}"
    }

    private const val LANGUAGE_TAG = "es-PE"
    private const val FAKE_CONFIDENCE_PERMILLE = 980
    private const val MIN_RENDERED_WIDTH_PX = 800
    private const val MIN_RENDERED_HEIGHT_PX = 1_300
    private const val PAGE_MARGIN_PX = 40
    private const val TABLE_HEADER_TOP_PX = 310
    private const val TABLE_HEADER_BOTTOM_PX = 350
    private const val FIRST_LINE_TOP_PX = 365
    private const val LINE_HEIGHT_PX = 42
    private const val LINE_BOX_HEIGHT_PX = 34
    private const val SUMMARY_TOP_PX = 2_080
    private const val SUMMARY_ROW_HEIGHT_PX = 52
    private const val SUMMARY_BOX_HEIGHT_PX = 40
    private const val STANDARD_LINE_MINOR_UNITS = 257L
    private const val LOWER_TAX_LINE_COUNT = 29
    private const val LOWER_STANDARD_LINE_TAX_MINOR_UNITS = 39L
    private const val UPPER_STANDARD_LINE_TAX_MINOR_UNITS = 40L
    private const val FINAL_LINE_MINOR_UNITS = 271L
    private const val FINAL_LINE_TAX_MINOR_UNITS = 41L
    private const val MINOR_UNITS_PER_SOL = 100L
}

internal data class DemoInvoiceCell(
    val text: String,
    val box: DemoInvoiceBox,
    val style: DemoInvoiceCellStyle,
)

internal data class DemoInvoiceBox(
    val leftPx: Int,
    val topPx: Int,
    val rightPx: Int,
    val bottomPx: Int,
) {
    init {
        require(leftPx >= 0 && topPx >= 0)
        require(rightPx > leftPx && rightPx <= DemoInvoiceFixture.BASE_PAGE_WIDTH_PX)
        require(bottomPx > topPx && bottomPx <= DemoInvoiceFixture.BASE_PAGE_HEIGHT_PX)
    }

    fun scaledTo(widthPx: Int, heightPx: Int): InvoiceTextBoundingBox {
        val scaledLeft = leftPx * widthPx / DemoInvoiceFixture.BASE_PAGE_WIDTH_PX
        val scaledTop = topPx * heightPx / DemoInvoiceFixture.BASE_PAGE_HEIGHT_PX
        val scaledRight = (rightPx * widthPx / DemoInvoiceFixture.BASE_PAGE_WIDTH_PX)
            .coerceAtLeast(scaledLeft + 1)
            .coerceAtMost(widthPx)
        val scaledBottom = (bottomPx * heightPx / DemoInvoiceFixture.BASE_PAGE_HEIGHT_PX)
            .coerceAtLeast(scaledTop + 1)
            .coerceAtMost(heightPx)
        return InvoiceTextBoundingBox(
            leftPx = scaledLeft,
            topPx = scaledTop,
            rightPx = scaledRight,
            bottomPx = scaledBottom,
        )
    }
}

internal enum class DemoInvoiceCellStyle {
    ISSUER,
    HEADER,
    DOCUMENT_TITLE,
    DISCLAIMER,
    TABLE_HEADER,
    TABLE_LINE,
    TABLE_MONEY,
    SUMMARY_LABEL,
    SUMMARY_MONEY,
}
