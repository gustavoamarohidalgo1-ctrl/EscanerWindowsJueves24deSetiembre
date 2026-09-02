package com.facturastock.app.domain.normalization

import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.InvoiceOcrSnapshot
import com.facturastock.app.domain.model.InvoiceTextBlock
import com.facturastock.app.domain.model.InvoiceTextBoundingBox
import com.facturastock.app.domain.model.InvoiceTextDocument
import com.facturastock.app.domain.model.InvoiceTextElement
import com.facturastock.app.domain.model.InvoiceTextGeometry
import com.facturastock.app.domain.model.InvoiceTextLine
import com.facturastock.app.domain.model.InvoiceTextPage
import com.facturastock.app.domain.model.InvoiceTextPoint
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.model.id.OcrRunId
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InvoiceLineItemsParserLayoutTest {
    @Test
    fun `format 1 extracts a complete six-column table from independent OCR blocks`() {
        val result = parse(
            page(
                blocks = fullHeader() + listOf(
                    cell("A001", 40, 285, 150, 320),
                    cell("ARROZ EXTRA 5 KG", 170, 285, 590, 320),
                    cell("2", 600, 285, 690, 320),
                    cell("NIU", 700, 285, 790, 320),
                    cell("12.50", 810, 285, 950, 320),
                    cell("25.00", 980, 285, 1_160, 320),
                    cell("B020", 40, 350, 150, 385),
                    cell("ACEITE VEGETAL 1 L", 170, 350, 590, 385),
                    cell("3", 600, 350, 690, 385),
                    cell("NIU", 700, 350, 790, 385),
                    cell("8.20", 810, 350, 950, 385),
                    cell("24.60", 980, 350, 1_160, 385),
                ),
            ),
        )

        assertEquals(DRAFT_ID, result.draftId)
        assertEquals(RUN_ID, result.runId)
        assertEquals(PEN, result.currency)
        assertEquals(listOf(0, 1), result.items.map(ParsedInvoiceLineItem::position))

        val first = result.items[0]
        assertEquals("A001", first.code?.value)
        assertEquals("ARROZ EXTRA 5 KG", first.description?.value)
        assertDecimal("2", first.quantity?.value?.value)
        assertEquals(InvoiceUnitCode.NIU, first.unit?.value)
        assertDecimal("12.50", first.unitCost?.value?.amount)
        assertMoney(2_500L, first.total?.value)
        assertTrue(InvoiceLineItemWarning.CURRENCY_FROM_DOCUMENT in first.warnings)
        assertEquals("A001 ARROZ EXTRA 5 KG 2 NIU 12.50 25.00", first.rawText)
        assertEquals(
            listOf("A001", "ARROZ EXTRA 5 KG", "2", "NIU", "12.50", "25.00"),
            first.evidence.map(CandidateEvidence::rawText),
        )

        val second = result.items[1]
        assertEquals("B020", second.code?.value)
        assertEquals("ACEITE VEGETAL 1 L", second.description?.value)
        assertDecimal("3", second.quantity?.value?.value)
        assertEquals(InvoiceUnitCode.NIU, second.unit?.value)
        assertDecimal("8.20", second.unitCost?.value?.amount)
        assertMoney(2_460L, second.total?.value)
        assertFalse(second.requiresReview)
    }

    @Test
    fun `format 2 uses element geometry when each visual row is one wide OCR line`() {
        val result = parse(
            page(
                blocks = listOf(
                    wideLine(
                        element("DESCRIPCIÓN", rect(40, 220, 560, 255)),
                        element("CANT", rect(580, 220, 680, 255)),
                        element("U.M.", rect(700, 220, 790, 255)),
                        element("P.UNIT", rect(810, 220, 950, 255)),
                        element("TOTAL", rect(980, 220, 1_160, 255)),
                    ),
                    wideLine(
                        element("QUESO FRESCO", rect(40, 285, 560, 320)),
                        element("1.25", rect(580, 285, 680, 320)),
                        element("KGM", rect(700, 285, 790, 320)),
                        element("20.00", rect(810, 285, 950, 320)),
                        element("25.00", rect(980, 285, 1_160, 320)),
                    ),
                    wideLine(
                        element("LECHE EVAPORADA", rect(40, 350, 560, 385)),
                        element("6", rect(580, 350, 680, 385)),
                        element("NIU", rect(700, 350, 790, 385)),
                        element("4.50", rect(810, 350, 950, 385)),
                        element("27.00", rect(980, 350, 1_160, 385)),
                    ),
                ),
            ),
        )

        assertEquals(2, result.items.size)
        val cheese = result.items[0]
        assertNull(cheese.code)
        assertEquals("QUESO FRESCO", cheese.description?.value)
        assertDecimal("1.25", cheese.quantity?.value?.value)
        assertEquals(InvoiceUnitCode.KGM, cheese.unit?.value)
        assertDecimal("20.00", cheese.unitCost?.value?.amount)
        assertMoney(2_500L, cheese.total?.value)
        assertTrue(cheese.evidence.all { evidence -> evidence.elementPosition != null })

        val milk = result.items[1]
        assertEquals("LECHE EVAPORADA", milk.description?.value)
        assertDecimal("6", milk.quantity?.value?.value)
        assertMoney(2_700L, milk.total?.value)
    }

    @Test
    fun `format 3 leaves columns absent from a three-column schema empty without incomplete warning`() {
        val result = parse(
            page(
                blocks = listOf(
                    cell("DESCRIPCIÓN", 60, 220, 650, 255),
                    cell("CANTIDAD", 680, 220, 800, 255),
                    cell("IMPORTE", 900, 220, 1_150, 255),
                    cell("PAN INTEGRAL", 60, 285, 650, 320),
                    cell("2", 680, 285, 800, 320),
                    cell("15.00", 900, 285, 1_150, 320),
                    cell("CAFÉ MOLIDO", 60, 350, 650, 385),
                    cell("1", 680, 350, 800, 385),
                    cell("22.90", 900, 350, 1_150, 385),
                ),
            ),
        )

        assertEquals(2, result.items.size)
        result.items.forEach { item ->
            assertNull(item.code)
            assertNull(item.barcode)
            assertNull(item.unit)
            assertNull(item.unitCost)
            assertNull(item.discount)
            assertNull(item.tax)
            assertFalse(InvoiceLineItemWarning.INCOMPLETE_ROW in item.warnings)
            assertTrue(InvoiceLineItemWarning.CURRENCY_FROM_DOCUMENT in item.warnings)
        }
        assertEquals("PAN INTEGRAL", result.items[0].description?.value)
        assertDecimal("2", result.items[0].quantity?.value?.value)
        assertMoney(1_500L, result.items[0].total?.value)
        assertEquals("CAFÉ MOLIDO", result.items[1].description?.value)
        assertMoney(2_290L, result.items[1].total?.value)
    }

    @Test
    fun `format 4 groups ragged baselines by geometry despite shuffled semantic blocks`() {
        val header = compactHeader()
        val firstRow = listOf(
            cell("HARINA SIN PREPARAR", 170, 280, 590, 320),
            cell("4", 600, 292, 690, 326),
            cell("KGM", 700, 276, 790, 310),
            cell("3.50", 810, 288, 950, 322),
            cell("14.00", 980, 284, 1_160, 318),
        )
        val secondRow = listOf(
            cell("YOGUR NATURAL", 170, 360, 590, 400),
            cell("2", 600, 352, 690, 386),
            cell("NIU", 700, 368, 790, 402),
            cell("7.25", 810, 356, 950, 390),
            cell("14.50", 980, 366, 1_160, 400),
        )
        val result = parse(
            page(blocks = (header + firstRow + secondRow).reversed()),
        )

        assertEquals(2, result.items.size)
        assertEquals("HARINA SIN PREPARAR", result.items[0].description?.value)
        assertDecimal("4", result.items[0].quantity?.value?.value)
        assertEquals(InvoiceUnitCode.KGM, result.items[0].unit?.value)
        assertDecimal("3.50", result.items[0].unitCost?.value?.amount)
        assertMoney(1_400L, result.items[0].total?.value)
        assertEquals(
            InvoiceTextBoundingBox(170, 276, 1_160, 326),
            result.items[0].boundingBox,
        )
        assertEquals(
            listOf("KGM", "HARINA SIN PREPARAR", "14.00", "3.50", "4"),
            result.items[0].evidence.map(CandidateEvidence::rawText),
        )

        assertEquals("YOGUR NATURAL", result.items[1].description?.value)
        assertDecimal("2", result.items[1].quantity?.value?.value)
        assertEquals(InvoiceUnitCode.NIU, result.items[1].unit?.value)
        assertMoney(1_450L, result.items[1].total?.value)
        assertEquals(
            InvoiceTextBoundingBox(170, 352, 1_160, 402),
            result.items[1].boundingBox,
        )
        assertEquals(
            listOf("2", "7.25", "YOGUR NATURAL", "14.50", "NIU"),
            result.items[1].evidence.map(CandidateEvidence::rawText),
        )
    }

    @Test
    fun `format 5 joins description continuations without creating a phantom product row`() {
        val result = parse(
            page(
                blocks = compactHeader() + listOf(
                    cell("DETERGENTE EN POLVO", 170, 280, 590, 314),
                    cell("2", 600, 280, 690, 314),
                    cell("NIU", 700, 280, 790, 314),
                    cell("30.00", 810, 280, 950, 314),
                    cell("60.00", 980, 280, 1_160, 314),
                    cell("BOLSA DE 4.5 KG", 170, 321, 590, 351),
                    cell("JABÓN DE TOCADOR", 170, 390, 590, 424),
                    cell("3", 600, 390, 690, 424),
                    cell("NIU", 700, 390, 790, 424),
                    cell("4.00", 810, 390, 950, 424),
                    cell("12.00", 980, 390, 1_160, 424),
                ),
            ),
        )

        assertEquals(2, result.items.size)
        val detergent = result.items[0]
        assertEquals("DETERGENTE EN POLVO BOLSA DE 4.5 KG", detergent.description?.value)
        assertDecimal("2", detergent.quantity?.value?.value)
        assertMoney(6_000L, detergent.total?.value)
        assertTrue(InvoiceLineItemWarning.MULTILINE_DESCRIPTION_JOINED in detergent.warnings)
        assertTrue(detergent.rawText.contains("DETERGENTE EN POLVO"))
        assertTrue(detergent.rawText.contains("BOLSA DE 4.5 KG"))
        assertTrue(
            detergent.evidence.any { evidence -> evidence.rawText == "DETERGENTE EN POLVO" },
        )
        assertTrue(
            detergent.evidence.any { evidence -> evidence.rawText == "BOLSA DE 4.5 KG" },
        )
        assertEquals("JABÓN DE TOCADOR", result.items[1].description?.value)
    }

    @Test
    fun `format 6 preserves incomplete rows and never borrows a neighboring cell`() {
        val result = parse(
            page(
                blocks = compactHeader() + listOf(
                    cell("CAJA PLÁSTICA", 170, 280, 590, 314),
                    cell("NIU", 700, 280, 790, 314),
                    cell("18.00", 810, 280, 950, 314),
                    cell("18.00", 980, 280, 1_160, 314),
                    cell("GUANTES DE NITRILO", 170, 355, 590, 389),
                    cell("2", 600, 355, 690, 389),
                    cell("NIU", 700, 355, 790, 389),
                    cell("5.00", 810, 355, 950, 389),
                ),
            ),
        )

        assertEquals(2, result.items.size)
        val box = result.items[0]
        assertEquals("CAJA PLÁSTICA", box.description?.value)
        assertNull(box.quantity)
        assertDecimal("18.00", box.unitCost?.value?.amount)
        assertMoney(1_800L, box.total?.value)
        assertTrue(InvoiceLineItemWarning.INCOMPLETE_ROW in box.warnings)
        assertTrue(box.requiresReview)

        val gloves = result.items[1]
        assertEquals("GUANTES DE NITRILO", gloves.description?.value)
        assertDecimal("2", gloves.quantity?.value?.value)
        assertDecimal("5.00", gloves.unitCost?.value?.amount)
        assertNull(gloves.total)
        assertTrue(InvoiceLineItemWarning.INCOMPLETE_ROW in gloves.warnings)
        assertTrue(gloves.requiresReview)
    }

    @Test
    fun `format 7 keeps SKU and barcode strings out of numeric item fields`() {
        val result = parse(
            page(
                blocks = listOf(
                    cell("SKU", 20, 220, 130, 255),
                    cell("CÓDIGO DE BARRAS", 140, 220, 330, 255),
                    cell("DESCRIPCIÓN", 340, 220, 710, 255),
                    cell("CANT", 720, 220, 800, 255),
                    cell("P.UNIT", 820, 220, 950, 255),
                    cell("TOTAL", 980, 220, 1_160, 255),
                    cell("SKU-77", 20, 285, 130, 320),
                    cell("7751234567890", 140, 285, 330, 320),
                    cell("GALLETAS DE AVENA", 340, 285, 710, 320),
                    cell("4", 720, 285, 800, 320),
                    cell("2.50", 820, 285, 950, 320),
                    cell("10.00", 980, 285, 1_160, 320),
                    cell("000123", 20, 350, 130, 385),
                    cell("7759876543210", 140, 350, 330, 385),
                    cell("CEREAL TOTAL 500G", 340, 350, 710, 385),
                    cell("2", 720, 350, 800, 385),
                    cell("6.00", 820, 350, 950, 385),
                    cell("12.00", 980, 350, 1_160, 385),
                    cell("A94A8FE5CCB19BA61C4C0873D391E987", 20, 415, 130, 450),
                    cell("7751111111111", 140, 415, 330, 450),
                    cell("REPUESTO", 340, 415, 710, 450),
                    cell("1", 720, 415, 800, 450),
                    cell("3.00", 820, 415, 950, 450),
                    cell("3.00", 980, 415, 1_160, 450),
                ),
            ),
        )

        assertEquals(3, result.items.size)
        val cookies = result.items[0]
        assertEquals("SKU-77", cookies.code?.value)
        assertEquals("7751234567890", cookies.barcode?.value)
        assertEquals("GALLETAS DE AVENA", cookies.description?.value)
        assertDecimal("4", cookies.quantity?.value?.value)
        assertMoney(1_000L, cookies.total?.value)

        val cereal = result.items[1]
        assertEquals("000123", cereal.code?.value)
        assertEquals("7759876543210", cereal.barcode?.value)
        assertEquals("CEREAL TOTAL 500G", cereal.description?.value)
        assertDecimal("2", cereal.quantity?.value?.value)
        assertMoney(1_200L, cereal.total?.value)

        val replacement = result.items[2]
        assertEquals("A94A8FE5CCB19BA61C4C0873D391E987", replacement.code?.value)
        assertEquals("REPUESTO", replacement.description?.value)
        assertMoney(300L, replacement.total?.value)
    }

    @Test
    fun `format 8 assigns discount and tax columns without contaminating cost or total`() {
        val result = parse(
            page(
                blocks = listOf(
                    cell("DESCRIPCIÓN", 20, 220, 370, 255),
                    cell("CANT", 380, 220, 450, 255),
                    cell("P.UNIT", 460, 220, 590, 255),
                    cell("DSCTO", 600, 220, 730, 255),
                    cell("IGV", 740, 220, 870, 255),
                    cell("TOTAL", 890, 220, 1_080, 255),
                    cell("SERVICIO TÉCNICO", 20, 285, 370, 320),
                    cell("2", 380, 285, 450, 320),
                    cell("50.00", 460, 285, 590, 320),
                    cell("10%", 600, 285, 730, 320),
                    cell("16.20", 740, 285, 870, 320),
                    cell("106.20", 890, 285, 1_080, 320),
                    cell("REPUESTO", 20, 350, 370, 385),
                    cell("1", 380, 350, 450, 385),
                    cell("100.00", 460, 350, 590, 385),
                    cell("5.00", 600, 350, 730, 385),
                    cell("18%", 740, 350, 870, 385),
                    cell("112.10", 890, 350, 1_080, 385),
                ),
            ),
        )

        assertEquals(2, result.items.size)
        val service = result.items[0]
        assertDecimal("50.00", service.unitCost?.value?.amount)
        assertRate("10", service.discount)
        assertAdjustmentMoney(1_620L, service.tax)
        assertMoney(10_620L, service.total?.value)

        val replacement = result.items[1]
        assertDecimal("100.00", replacement.unitCost?.value?.amount)
        assertAdjustmentMoney(500L, replacement.discount)
        assertRate("18", replacement.tax)
        assertMoney(11_210L, replacement.total?.value)
        result.items.forEach { item ->
            assertTrue(InvoiceLineItemWarning.CURRENCY_FROM_DOCUMENT in item.warnings)
        }
    }

    @Test
    fun `format 9 excludes summaries QR payload and footer from item rows`() {
        val result = parse(
            page(
                blocks = compactHeader() + listOf(
                    cell("CAFÉ INSTANTÁNEO", 170, 285, 590, 320),
                    cell("1", 600, 285, 690, 320),
                    cell("NIU", 700, 285, 790, 320),
                    cell("15.00", 810, 285, 950, 320),
                    cell("15.00", 980, 285, 1_160, 320),
                    cell("AZÚCAR RUBIA", 170, 350, 590, 385),
                    cell("2", 600, 350, 690, 385),
                    cell("KGM", 700, 350, 790, 385),
                    cell("11.00", 810, 350, 950, 385),
                    cell("22.00", 980, 350, 1_160, 385),
                    cell("OP. GRAVADA", 650, 600, 950, 635),
                    cell("37.00", 980, 600, 1_160, 635),
                    cell("SUBTOTAL", 650, 650, 950, 685),
                    cell("37.00", 980, 650, 1_160, 685),
                    cell("IGV 18%", 650, 700, 950, 735),
                    cell("6.66", 980, 700, 1_160, 735),
                    cell("TOTAL", 650, 750, 950, 790),
                    cell("43.66", 980, 750, 1_160, 790),
                    cell(
                        "20131312955|F001|12345|37.00|6.66|43.66",
                        190,
                        850,
                        1_010,
                        990,
                    ),
                    cell("https://consulta.example/qr/12345", 190, 1_010, 900, 1_045),
                    cell("Representación impresa del comprobante", 220, 1_110, 980, 1_145),
                    cell("Página 1 de 1", 500, 1_180, 750, 1_215),
                ),
            ),
        )

        assertEquals(2, result.items.size)
        assertEquals(
            listOf("CAFÉ INSTANTÁNEO", "AZÚCAR RUBIA"),
            result.items.map { item -> item.description?.value },
        )
        val itemEvidence = result.items.flatMap(ParsedInvoiceLineItem::evidence)
            .joinToString("\n", transform = CandidateEvidence::rawText)
        assertFalse(
            result.items.flatMap(ParsedInvoiceLineItem::evidence)
                .any { evidence -> evidence.rawText == "TOTAL" },
        )
        listOf(
            "OP. GRAVADA",
            "SUBTOTAL",
            "IGV 18%",
            "20131312955|F001|12345",
            "https://consulta.example",
            "Representación impresa",
            "Página 1 de 1",
        ).forEach { excluded -> assertFalse(excluded, itemEvidence.contains(excluded)) }
    }

    @Test
    fun `format 10 handles repeated multipage headers corner geometry and numeric OCR noise`() {
        val firstPage = page(
            pageIndex = 0,
            blocks = compactHeader() + listOf(
                cell("AGUA MINERAL", 170, 285, 590, 320),
                cell("2", 600, 285, 690, 320),
                cell("NIU", 700, 285, 790, 320),
                cell("3.00", 810, 285, 950, 320),
                cell("6.00", 980, 285, 1_160, 320),
                cell("CONTINÚA", 500, 1_480, 700, 1_515),
            ),
        )
        val secondPage = page(
            pageIndex = 1,
            blocks = compactHeader(cornerOnly = true) + listOf(
                cell("JABÓN LÍQUIDO", 170, 285, 590, 320, cornerOnly = true),
                cell("I", 600, 285, 690, 320, cornerOnly = true),
                cell("NIU", 700, 285, 790, 320, cornerOnly = true),
                cell("2O.00", 810, 285, 950, 320, cornerOnly = true),
                cell("20.00", 980, 285, 1_160, 320, cornerOnly = true),
                cell("TOTAL", 650, 600, 950, 635, cornerOnly = true),
                cell("26.00", 980, 600, 1_160, 635, cornerOnly = true),
            ),
        )

        val result = parse(firstPage, secondPage)

        assertEquals(2, result.items.size)
        assertEquals(listOf(0, 1), result.items.map(ParsedInvoiceLineItem::position))
        assertEquals(listOf(0, 1), result.items.map(ParsedInvoiceLineItem::pageIndex))
        assertEquals("AGUA MINERAL", result.items[0].description?.value)

        val soap = result.items[1]
        assertEquals("JABÓN LÍQUIDO", soap.description?.value)
        assertNull(soap.quantity?.value)
        assertDecimal("1", soap.quantity?.alternatives?.singleOrNull()?.value)
        assertNull(soap.unitCost?.value)
        assertDecimal("20.00", soap.unitCost?.alternatives?.singleOrNull()?.amount)
        assertMoney(2_000L, soap.total?.value)
        assertTrue(InvoiceLineItemWarning.OCR_CORRECTION_APPLIED in soap.warnings)
        assertTrue(soap.boundingBox != null)
        assertTrue(
            soap.evidence.any { evidence ->
                evidence.pageIndex == 1 && evidence.cornerPoints.size == 4
            },
        )
        assertTrue(
            soap.evidence.any { evidence ->
                evidence.rawText == "I" &&
                    CandidateWarning.OCR_CORRECTION_APPLIED in soap.quantity!!.warnings
            },
        )
    }

    @Test
    fun `aligned numeric text without a table header never becomes invoice items`() {
        val result = parse(
            page(
                blocks = listOf(
                    cell("ARROZ EXTRA", 170, 285, 590, 320),
                    cell("2", 600, 285, 690, 320),
                    cell("NIU", 700, 285, 790, 320),
                    cell("5.00", 810, 285, 950, 320),
                    cell("10.00", 980, 285, 1_160, 320),
                    cell("RUC: 20131312955", 170, 350, 590, 385),
                    cell("TOTAL", 810, 350, 950, 385),
                    cell("10.00", 980, 350, 1_160, 385),
                ),
            ),
        )

        assertTrue(result.items.isEmpty())
    }

    @Test
    fun `an explicit document currency conflicting with context stays unresolved globally`() {
        val snapshot = snapshot(
            page(
                blocks = listOf(cell("MONEDA: USD", 40, 80, 300, 115)) + compactHeader() + listOf(
                    cell("SERVICIO", 170, 285, 590, 320),
                    cell("1", 600, 285, 690, 320),
                    cell("NIU", 700, 285, 790, 320),
                    cell("10.00", 810, 285, 950, 320),
                    cell("10.00", 980, 285, 1_160, 320),
                ),
            ),
        )

        val result = InvoiceLineItemsParser().parse(snapshot, currencyContext = PEN)

        assertNull(result.currency)
        assertTrue(InvoiceLineItemsWarning.CURRENCY_CONFLICT in result.warnings)
        assertNull(result.items.single().unitCost)
        assertNull(result.items.single().total)
        assertTrue(InvoiceLineItemWarning.INCOMPLETE_ROW in result.items.single().warnings)
    }

    @Test
    fun `reusing a parsed header preserves the complete line result`() {
        val snapshot = snapshot(
            page(
                blocks = listOf(cell("MONEDA: USD", 40, 80, 300, 115)) + compactHeader() + listOf(
                    cell("SERVICIO", 170, 285, 590, 320),
                    cell("1", 600, 285, 690, 320),
                    cell("NIU", 700, 285, 790, 320),
                    cell("10.00", 810, 285, 950, 320),
                    cell("10.00", 980, 285, 1_160, 320),
                ),
            ),
        )
        val parser = InvoiceLineItemsParser()
        val expected = parser.parse(snapshot, currencyContext = PEN)
        val parsedHeader = InvoiceHeaderParser().parse(snapshot)

        val actual = parser.parse(snapshot, currencyContext = PEN, parsedHeader = parsedHeader)

        assertEquals(expected, actual)
    }

    @Test
    fun `US dollar marker never inherits PEN and exposes an intra-row currency conflict`() {
        val result = parse(
            page(
                blocks = compactHeader() + listOf(
                    cell("REPUESTO IMPORTADO", 170, 285, 590, 320),
                    cell("1", 600, 285, 690, 320),
                    cell("NIU", 700, 285, 790, 320),
                    cell("US\$ 10.00", 810, 285, 950, 320),
                    cell("US\$ 10.00", 980, 285, 1_160, 320),
                ),
            ),
        )

        val item = result.items.single()
        assertEquals("USD", item.unitCost?.value?.currency?.value)
        assertEquals("USD", item.total?.value?.currency?.value)
        assertTrue(InvoiceLineItemWarning.CURRENCY_CONFLICT in item.warnings)
        assertTrue(item.requiresReview)
    }

    @Test
    fun `a product beginning with TOTAL does not terminate the table`() {
        val result = parse(
            page(
                blocks = compactHeader() + listOf(
                    cell("TOTAL CLEAN LIMPIADOR", 170, 285, 590, 320),
                    cell("1", 600, 285, 690, 320),
                    cell("NIU", 700, 285, 790, 320),
                    cell("5.00", 810, 285, 950, 320),
                    cell("5.00", 980, 285, 1_160, 320),
                    cell("ARROZ EXTRA", 170, 350, 590, 385),
                    cell("2", 600, 350, 690, 385),
                    cell("NIU", 700, 350, 790, 385),
                    cell("4.00", 810, 350, 950, 385),
                    cell("8.00", 980, 350, 1_160, 385),
                    cell("LOTE NAVIDEÑO", 170, 415, 590, 450),
                    cell("1", 600, 415, 690, 450),
                    cell("NIU", 700, 415, 790, 450),
                    cell("100.00", 810, 415, 950, 450),
                    cell("100.00", 980, 415, 1_160, 450),
                    cell("HASH BROWN CONGELADO", 170, 480, 590, 515),
                    cell("1", 600, 480, 690, 515),
                    cell("NIU", 700, 480, 790, 515),
                    cell("6.00", 810, 480, 950, 515),
                    cell("6.00", 980, 480, 1_160, 515),
                    cell("FIRMA DIGITAL CERTIFICADA", 170, 545, 590, 580),
                    cell("1", 600, 545, 690, 580),
                    cell("NIU", 700, 545, 790, 580),
                    cell("50.00", 810, 545, 950, 580),
                    cell("50.00", 980, 545, 1_160, 580),
                ),
            ),
        )

        assertEquals(
            listOf(
                "TOTAL CLEAN LIMPIADOR",
                "ARROZ EXTRA",
                "LOTE NAVIDEÑO",
                "HASH BROWN CONGELADO",
                "FIRMA DIGITAL CERTIFICADA",
            ),
            result.items.map { item -> item.description?.value },
        )
    }

    @Test
    fun `arithmetic mismatch keeps OCR values and requires review`() {
        val result = parse(
            page(
                blocks = compactHeader() + listOf(
                    cell("PRODUCTO", 170, 285, 590, 320),
                    cell("2", 600, 285, 690, 320),
                    cell("NIU", 700, 285, 790, 320),
                    cell("10.00", 810, 285, 950, 320),
                    cell("99.00", 980, 285, 1_160, 320),
                ),
            ),
        )

        val item = result.items.single()
        assertDecimal("2", item.quantity?.value?.value)
        assertDecimal("10.00", item.unitCost?.value?.amount)
        assertMoney(9_900L, item.total?.value)
        assertTrue(InvoiceLineItemWarning.ARITHMETIC_MISMATCH in item.warnings)
        assertTrue(item.requiresReview)
    }

    @Test
    fun `RUC DNI bare QR and quantity-only barcode never become products or continuations`() {
        val result = parse(
            page(
                blocks = compactHeader() + listOf(
                    cell("ARROZ", 170, 285, 590, 320),
                    cell("1", 600, 285, 690, 320),
                    cell("NIU", 700, 285, 790, 320),
                    cell("5.00", 810, 285, 950, 320),
                    cell("5.00", 980, 285, 1_160, 320),
                    cell("RUC: 20131312955", 170, 328, 590, 360),
                    cell("DNI 12345678", 170, 370, 590, 402),
                    cell("20131312955|F001", 170, 412, 590, 444),
                    cell("|123|10.00", 600, 412, 690, 444),
                    cell("A94A8FE5CCB19BA61C4C0873D391E987", 170, 454, 590, 486),
                    cell("7751234567890", 600, 496, 690, 528),
                    cell("GALLETAS", 170, 570, 590, 602),
                    cell("7759876543210", 600, 570, 690, 602),
                    cell("10.00", 980, 570, 1_160, 602),
                    cell("ACEITE", 170, 640, 590, 672),
                    cell("2", 600, 640, 690, 672),
                    cell("NIU", 700, 640, 790, 672),
                    cell("8.00", 810, 640, 950, 672),
                    cell("16.00", 980, 640, 1_160, 672),
                    cell("ACME SAC", 170, 710, 590, 742),
                    cell("20131312955", 600, 710, 690, 742),
                    cell("10.00", 980, 710, 1_160, 742),
                ),
            ),
        )

        assertEquals(
            listOf("ARROZ", "GALLETAS", "ACEITE", "ACME SAC"),
            result.items.map { it.description?.value },
        )
        val barcodeAsQuantity = result.items[1].quantity
        assertNull(barcodeAsQuantity?.value)
        assertDecimal("7759876543210", barcodeAsQuantity?.alternatives?.singleOrNull()?.value)
        assertTrue(result.items[1].requiresReview)
        val rucAsQuantity = result.items[3].quantity
        assertNull(rucAsQuantity?.value)
        assertDecimal("20131312955", rucAsQuantity?.alternatives?.singleOrNull()?.value)
        assertTrue(result.items[3].requiresReview)
        val evidence = result.items.flatMap(ParsedInvoiceLineItem::evidence).map(CandidateEvidence::rawText)
        assertFalse(evidence.any { raw -> raw.contains("RUC") || raw.contains("DNI") || '|' in raw })
        assertFalse("7751234567890" in evidence)
        assertFalse("A94A8FE5CCB19BA61C4C0873D391E987" in evidence)
    }

    @Test
    fun `compatibility digits and percent are normalized only after preserving raw evidence`() {
        val result = parse(
            page(
                blocks = listOf(
                    cell("DESCRIPCIÓN", 20, 220, 370, 255),
                    cell("CANT", 380, 220, 470, 255),
                    cell("P.UNIT", 480, 220, 650, 255),
                    cell("DSCTO", 660, 220, 820, 255),
                    cell("TOTAL", 840, 220, 1_080, 255),
                    cell("SERVICIO", 20, 285, 370, 320),
                    cell("２", 380, 285, 470, 320),
                    cell("５,００", 480, 285, 650, 320),
                    cell("１０％", 660, 285, 820, 320),
                    cell("１０,００", 840, 285, 1_080, 320),
                ),
            ),
        )

        val item = result.items.single()
        assertDecimal("2", item.quantity?.value?.value)
        assertDecimal("5.00", item.unitCost?.value?.amount)
        assertRate("10", item.discount)
        assertMoney(1_000L, item.total?.value)
        assertTrue(item.evidence.any { evidence -> evidence.rawText == "１０％" })
    }

    @Test
    fun `a tall header box does not swallow the first product row`() {
        val result = parse(
            page(
                blocks = listOf(
                    cell("DESCRIPCIÓN", 170, 220, 360, 400),
                    cell("DEL", 370, 220, 430, 400),
                    cell("PRODUCTO", 440, 220, 590, 400),
                    cell("CANT", 600, 220, 690, 255),
                    cell("UNIDAD", 700, 220, 790, 255),
                    cell("P.UNIT", 810, 220, 950, 255),
                    cell("TOTAL", 980, 220, 1_160, 255),
                    cell("LECHE", 170, 285, 590, 320),
                    cell("2", 600, 285, 690, 320),
                    cell("NIU", 700, 285, 790, 320),
                    cell("4.00", 810, 285, 950, 320),
                    cell("8.00", 980, 285, 1_160, 320),
                ),
            ),
        )

        assertEquals("LECHE", result.items.single().description?.value)
        assertMoney(800L, result.items.single().total?.value)
    }

    @Test
    fun `a connector between tall header blocks never contaminates the product code`() {
        val result = parse(
            page(
                blocks = listOf(
                    cell("CÓDIGO", 20, 220, 100, 400),
                    cell("DE", 105, 220, 125, 400),
                    cell("BARRAS", 130, 220, 300, 400),
                    cell("DESCRIPCIÓN", 340, 220, 700, 255),
                    cell("CANT", 720, 220, 810, 255),
                    cell("TOTAL", 900, 220, 1_160, 255),
                    cell("SKU-1", 20, 285, 100, 320),
                    cell("7751234567890", 130, 285, 300, 320),
                    cell("REPUESTO", 340, 285, 700, 320),
                    cell("1", 720, 285, 810, 320),
                    cell("5.00", 900, 285, 1_160, 320),
                ),
            ),
        )

        val item = result.items.single()
        assertEquals("SKU-1", item.code?.value)
        assertEquals("7751234567890", item.barcode?.value)
        assertEquals("REPUESTO", item.description?.value)
        assertFalse(item.evidence.any { evidence -> evidence.rawText == "DE" })
    }

    @Test
    fun `a distant description-only row remains editable instead of being dropped`() {
        val result = parse(
            page(
                blocks = compactHeader() + listOf(
                    cell("PRODUCTO SIN NÚMEROS", 170, 285, 590, 320),
                    cell("PRODUCTO COMPLETO", 170, 500, 590, 535),
                    cell("1", 600, 500, 690, 535),
                    cell("NIU", 700, 500, 790, 535),
                    cell("7.00", 810, 500, 950, 535),
                    cell("7.00", 980, 500, 1_160, 535),
                ),
            ),
        )

        assertEquals(2, result.items.size)
        assertEquals("PRODUCTO SIN NÚMEROS", result.items[0].description?.value)
        assertTrue(InvoiceLineItemWarning.INCOMPLETE_ROW in result.items[0].warnings)
        assertEquals("PRODUCTO COMPLETO", result.items[1].description?.value)
    }

    @Test
    fun `metadata-like product names survive a valid two-column table`() {
        val result = parse(
            page(
                blocks = listOf(
                    cell("DESCRIPCIÓN", 80, 220, 700, 255),
                    cell("IMPORTE", 800, 220, 1_140, 255),
                    cell("LOTE NAVIDEÑO", 80, 285, 700, 320),
                    cell("100.00", 800, 285, 1_140, 320),
                    cell("ORDEN DE SERVICIO", 80, 350, 700, 385),
                    cell("50.00", 800, 350, 1_140, 385),
                    cell("HASH BROWN CONGELADO", 80, 415, 700, 450),
                    cell("5.00", 800, 415, 1_140, 450),
                    cell("FIRMA DIGITAL", 80, 480, 700, 515),
                    cell("20.00", 800, 480, 1_140, 515),
                    cell("RUC", 80, 545, 700, 580),
                    cell("20131312955", 800, 545, 1_140, 580),
                    cell("FIRMA", 80, 610, 700, 645),
                    cell("2026", 800, 610, 1_140, 645),
                    cell("NO DEBE APARECER", 80, 675, 700, 710),
                    cell("30.00", 800, 675, 1_140, 710),
                ),
            ),
        )

        assertEquals(
            listOf(
                "LOTE NAVIDEÑO",
                "ORDEN DE SERVICIO",
                "HASH BROWN CONGELADO",
                "FIRMA DIGITAL",
            ),
            result.items.map { item -> item.description?.value },
        )
        assertMoney(10_000L, result.items[0].total?.value)
        assertMoney(5_000L, result.items[1].total?.value)
        assertMoney(500L, result.items[2].total?.value)
        assertMoney(2_000L, result.items[3].total?.value)
    }

    @Test
    fun `percentage adjustments use a closed exact grammar and keep ambiguity visible`() {
        val rows = listOf(
            Triple("CERO", "0%", 285),
            Triple("CIEN", "100%", 340),
            Triple("AMBIGUO", "1,234%", 395),
            Triple("OCR", "I8%", 450),
            Triple("ROTO", "18%%", 505),
            Triple("MIXTO", "10% S/ 5.00", 560),
            Triple("TASA POR CABECERA", "18", 615),
        )
        val body = rows.flatMap { (description, discount, top) ->
            listOf(
                cell(description, 20, top, 430, top + 34),
                cell("1", 440, top, 530, top + 34),
                cell(discount, 550, top, 790, top + 34),
                cell("1.00", 820, top, 1_080, top + 34),
            )
        }
        val result = parse(
            page(
                blocks = listOf(
                    cell("DESCRIPCIÓN", 20, 220, 430, 255),
                    cell("CANT", 440, 220, 530, 255),
                    cell("DSCTO %", 550, 220, 790, 255),
                    cell("TOTAL", 820, 220, 1_080, 255),
                ) + body,
            ),
        )

        assertRate("0", result.items[0].discount)
        assertRate("100", result.items[1].discount)
        val ambiguous = requireNotNull(result.items[2].discount)
        assertNull(ambiguous.value)
        assertEquals(
            BigDecimal("1.234"),
            (ambiguous.alternatives.single() as InvoiceLineAdjustment.Rate).value.percent,
        )
        assertTrue(CandidateWarning.AMBIGUOUS_NUMBER_SEPARATOR in ambiguous.warnings)
        val corrected = requireNotNull(result.items[3].discount)
        assertNull(corrected.value)
        assertEquals(
            BigDecimal("18"),
            (corrected.alternatives.single() as InvoiceLineAdjustment.Rate).value.percent,
        )
        assertTrue(CandidateWarning.OCR_CORRECTION_APPLIED in corrected.warnings)
        assertNull(result.items[4].discount)
        assertNull(result.items[5].discount)
        assertRate("18", result.items[6].discount)
        assertTrue(InvoiceLineItemWarning.INCOMPLETE_ROW in result.items[4].warnings)
        assertTrue(InvoiceLineItemWarning.INCOMPLETE_ROW in result.items[5].warnings)
    }

    private fun parse(vararg pages: InvoiceTextPage): InvoiceLineItemsParseResult =
        InvoiceLineItemsParser().parse(
            snapshot = snapshot(*pages),
            currencyContext = PEN,
        )

    private fun snapshot(vararg pages: InvoiceTextPage): InvoiceOcrSnapshot = InvoiceOcrSnapshot(
        draftId = DRAFT_ID,
        runId = RUN_ID,
        completedAt = COMPLETED_AT,
        document = InvoiceTextDocument(pages.toList()),
    )

    private fun fullHeader(
        top: Int = 220,
        cornerOnly: Boolean = false,
    ): List<BlockSpec> = listOf(
        cell("CÓDIGO", 40, top, 150, top + 35, cornerOnly),
        cell("DESCRIPCIÓN", 170, top, 590, top + 35, cornerOnly),
        cell("CANT", 600, top, 690, top + 35, cornerOnly),
        cell("UNIDAD", 700, top, 790, top + 35, cornerOnly),
        cell("P.UNIT", 810, top, 950, top + 35, cornerOnly),
        cell("IMPORTE", 980, top, 1_160, top + 35, cornerOnly),
    )

    private fun compactHeader(
        top: Int = 220,
        cornerOnly: Boolean = false,
    ): List<BlockSpec> = listOf(
        cell("DESCRIPCIÓN", 170, top, 590, top + 35, cornerOnly),
        cell("CANT", 600, top, 690, top + 35, cornerOnly),
        cell("UNIDAD", 700, top, 790, top + 35, cornerOnly),
        cell("P.UNIT", 810, top, 950, top + 35, cornerOnly),
        cell("TOTAL", 980, top, 1_160, top + 35, cornerOnly),
    )

    private fun page(
        blocks: List<BlockSpec>,
        pageIndex: Int = 0,
        widthPx: Int = 1_200,
        heightPx: Int = 1_600,
    ): InvoiceTextPage {
        val mapped = blocks.mapIndexed { blockIndex, spec ->
            val blockBounds = spec.lines.mapNotNull(LineSpec::bounds).union()
            InvoiceTextBlock(
                position = blockIndex,
                text = spec.lines.joinToString("\n", transform = LineSpec::text),
                languageTag = "es-PE",
                geometry = blockBounds?.toGeometry(spec.lines.all(LineSpec::cornerOnly))
                    ?: InvoiceTextGeometry(null, emptyList()),
                lines = spec.lines.mapIndexed { lineIndex, line ->
                    InvoiceTextLine(
                        position = lineIndex,
                        text = line.text,
                        languageTag = "es-PE",
                        geometry = line.bounds?.toGeometry(line.cornerOnly)
                            ?: InvoiceTextGeometry(null, emptyList()),
                        confidencePermille = line.confidencePermille,
                        clockwiseAngleTenths = 0,
                        elements = line.toElements(),
                    )
                },
            )
        }
        return InvoiceTextPage(
            sourceImageId = ImageId.from(UUID(0L, 500L + pageIndex)),
            pageIndex = pageIndex,
            widthPx = widthPx,
            heightPx = heightPx,
            text = mapped.joinToString("\n", transform = InvoiceTextBlock::text),
            blocks = mapped,
        )
    }

    private fun LineSpec.toElements(): List<InvoiceTextElement> {
        val elementSpecs = when {
            elements.isNotEmpty() -> elements
            bounds != null -> listOf(ElementSpec(text, bounds))
            else -> emptyList()
        }
        return elementSpecs.mapIndexed { elementIndex, element ->
            InvoiceTextElement(
                position = elementIndex,
                text = element.text,
                languageTag = "es-PE",
                geometry = element.bounds.toGeometry(cornerOnly),
                confidencePermille = confidencePermille,
                clockwiseAngleTenths = 0,
            )
        }
    }

    private fun cell(
        text: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        cornerOnly: Boolean = false,
    ): BlockSpec = block(
        line(
            text = text,
            bounds = rect(left, top, right, bottom),
            cornerOnly = cornerOnly,
        ),
    )

    private fun wideLine(vararg elements: ElementSpec): BlockSpec {
        val bounds = elements.map(ElementSpec::bounds).union()
        return block(
            line(
                text = elements.joinToString(" ", transform = ElementSpec::text),
                bounds = requireNotNull(bounds),
                elements = elements.toList(),
            ),
        )
    }

    private fun block(vararg lines: LineSpec): BlockSpec = BlockSpec(lines.toList())

    private fun line(
        text: String,
        bounds: RectSpec?,
        elements: List<ElementSpec> = emptyList(),
        cornerOnly: Boolean = false,
        confidencePermille: Int = 930,
    ): LineSpec = LineSpec(text, bounds, elements, cornerOnly, confidencePermille)

    private fun element(text: String, bounds: RectSpec): ElementSpec = ElementSpec(text, bounds)

    private fun rect(left: Int, top: Int, right: Int, bottom: Int): RectSpec =
        RectSpec(left, top, right, bottom)

    private fun List<RectSpec>.union(): RectSpec? = if (isEmpty()) {
        null
    } else {
        RectSpec(
            left = minOf(RectSpec::left),
            top = minOf(RectSpec::top),
            right = maxOf(RectSpec::right),
            bottom = maxOf(RectSpec::bottom),
        )
    }

    private fun RectSpec.toGeometry(cornerOnly: Boolean): InvoiceTextGeometry =
        InvoiceTextGeometry(
            boundingBox = if (cornerOnly) null else InvoiceTextBoundingBox(left, top, right, bottom),
            cornerPoints = if (cornerOnly) {
                listOf(
                    InvoiceTextPoint(left, top),
                    InvoiceTextPoint(right - 1, top),
                    InvoiceTextPoint(right - 1, bottom - 1),
                    InvoiceTextPoint(left, bottom - 1),
                )
            } else {
                emptyList()
            },
        )

    private fun assertDecimal(expected: String, actual: BigDecimal?) {
        assertEquals(BigDecimal(expected), actual)
    }

    private fun assertMoney(expectedMinorUnits: Long, actual: com.facturastock.app.domain.model.Money?) {
        assertEquals(expectedMinorUnits, actual?.minorUnits)
        assertEquals(PEN, actual?.currency)
    }

    private fun assertRate(
        expected: String,
        candidate: Candidate<InvoiceLineAdjustment>?,
    ) {
        val adjustment = candidate?.value
        assertTrue(adjustment is InvoiceLineAdjustment.Rate)
        assertEquals(
            BigDecimal(expected),
            (adjustment as InvoiceLineAdjustment.Rate).value.percent,
        )
    }

    private fun assertAdjustmentMoney(
        expectedMinorUnits: Long,
        candidate: Candidate<InvoiceLineAdjustment>?,
    ) {
        val adjustment = candidate?.value
        assertTrue(adjustment is InvoiceLineAdjustment.Amount)
        assertMoney(
            expectedMinorUnits,
            (adjustment as InvoiceLineAdjustment.Amount).value,
        )
    }

    private data class BlockSpec(val lines: List<LineSpec>)

    private data class LineSpec(
        val text: String,
        val bounds: RectSpec?,
        val elements: List<ElementSpec>,
        val cornerOnly: Boolean,
        val confidencePermille: Int,
    )

    private data class ElementSpec(val text: String, val bounds: RectSpec)

    private data class RectSpec(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    private companion object {
        val DRAFT_ID: DraftId = DraftId.from(UUID(0L, 401L))
        val RUN_ID: OcrRunId = OcrRunId.from(UUID(0L, 402L))
        val COMPLETED_AT: Instant = Instant.parse("2026-08-09T18:00:00Z")
        val PEN: CurrencyCode = CurrencyCode.of("PEN")
    }
}
