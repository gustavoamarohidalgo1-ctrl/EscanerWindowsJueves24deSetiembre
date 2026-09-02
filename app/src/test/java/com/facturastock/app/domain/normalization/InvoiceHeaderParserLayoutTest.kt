package com.facturastock.app.domain.normalization

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
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InvoiceHeaderParserLayoutTest {
    @Test
    fun `layout A selects issuer at the left and splits the document number in the right box`() {
        val page = page(
            widthPx = 1_000,
            heightPx = 1_400,
            blocks = listOf(
                block(
                    line("COMERCIAL ANDINA S.A.C.", rect(40, 35, 560, 70)),
                    line("RUC: 20131312955", rect(40, 80, 370, 112)),
                ),
                block(
                    line("FACTURA ELECTRÓNICA", rect(620, 35, 970, 72)),
                    line(
                        text = "F001 - 12345",
                        bounds = rect(630, 88, 895, 124),
                        elements = listOf(
                            element("F001", rect(630, 88, 735, 124)),
                            element("-", rect(742, 88, 755, 124)),
                            element("12345", rect(765, 88, 895, 124)),
                        ),
                    ),
                    line("Fecha de emisión: 17/08/2026", rect(620, 140, 970, 175)),
                    line("Moneda: PEN", rect(620, 188, 850, 222)),
                ),
                block(
                    line("DATOS DEL CLIENTE", rect(40, 300, 430, 334)),
                    line("MERCADOS DEL SUR S.R.L.", rect(40, 344, 520, 378)),
                    line("RUC: 20512345671", rect(40, 388, 370, 422)),
                ),
            ),
        )

        val result = InvoiceHeaderParser().parse(
            snapshot(page),
            buyerRuc = "20512345671",
        )

        assertEquals(DRAFT_ID, result.draftId)
        assertEquals(RUN_ID, result.runId)
        assertEquals("INVOICE", result.documentType.selected?.value?.name)
        assertEquals("20131312955", result.issuerRuc.selected?.value)
        assertEquals("COMERCIAL ANDINA S.A.C.", result.issuerLegalName.selected?.value)
        assertEquals("F001", result.documentNumber.selected?.value?.series)
        assertEquals("12345", result.documentNumber.selected?.value?.correlative)
        assertEquals("F001-12345", result.documentNumber.selected?.value?.normalized)
        assertEquals(LocalDate.of(2026, 8, 17), result.issueDate.selected?.value)
        assertEquals("PEN", result.currency.selected?.value?.value)

        assertTrue(
            result.issuerRuc.alternatives.any { candidate ->
                candidate.value == "20512345671"
            },
        )
        assertEquals(
            setOf("20131312955", "20512345671"),
            result.issuerRuc.all.map { candidate -> candidate.value }.toSet(),
        )
        val issuerRuc = requireNotNull(result.issuerRuc.selected)
        assertTrue(issuerRuc.rawText.contains("20131312955"))
        assertTrue(
            issuerRuc.evidence.any { evidence ->
                evidence.rawText.contains("20131312955") &&
                    evidence.pageIndex == 0 &&
                    evidence.sourceImageId == page.sourceImageId
            },
        )
        assertEquals(rect(40, 80, 370, 112).toBoundingBox(), issuerRuc.boundingBox)
    }

    @Test
    fun `layout B keeps noisy OCR evidence and proposes corrected typed header values`() {
        val page = page(
            widthPx = 600,
            heightPx = 1_600,
            blocks = listOf(
                block(
                    line("FACTURA ELECTR0NICA", rect(125, 25, 475, 62)),
                    line("F001-12345", rect(205, 74, 395, 108)),
                    line("EMISOR", rect(235, 130, 365, 160)),
                    line("DISTRIBUIDORA L0S ANDES S.A.C.", rect(60, 170, 540, 207)),
                    line("RUC: 20I3I3I2955", rect(145, 220, 455, 255)),
                    line("Fecha de emisión: I7/O8/2O26", rect(92, 272, 508, 308)),
                    line("M0NEDA: S0LES", rect(165, 325, 435, 360)),
                ),
            ),
        )

        val result = InvoiceHeaderParser().parse(snapshot(page))

        assertEquals("INVOICE", result.documentType.selected?.value?.name)
        assertEquals("20131312955", result.issuerRuc.selected?.value)
        assertEquals("DISTRIBUIDORA L0S ANDES S.A.C.", result.issuerLegalName.selected?.value)
        assertEquals("F001-12345", result.documentNumber.selected?.value?.normalized)
        assertEquals(LocalDate.of(2026, 8, 17), result.issueDate.selected?.value)
        assertEquals("PEN", result.currency.selected?.value?.value)

        val noisyType = requireNotNull(result.documentType.selected)
        val noisyRuc = requireNotNull(result.issuerRuc.selected)
        val noisyDate = requireNotNull(result.issueDate.selected)
        val noisyCurrency = requireNotNull(result.currency.selected)
        assertTrue(noisyType.rawText.contains("ELECTR0NICA"))
        assertTrue(noisyRuc.rawText.contains("20I3I3I2955"))
        assertTrue(noisyDate.rawText.contains("I7/O8/2O26"))
        assertTrue(noisyCurrency.rawText.contains("S0LES"))
        assertTrue(noisyType.warnings.isNotEmpty())
        assertTrue(noisyRuc.warnings.isNotEmpty())
        assertTrue(noisyDate.warnings.isNotEmpty())
        assertTrue(noisyCurrency.warnings.isNotEmpty())
        assertTrue(
            noisyRuc.evidence.any { evidence -> evidence.rawText.contains("20I3I3I2955") },
        )
        assertTrue(
            noisyDate.evidence.any { evidence -> evidence.rawText.contains("I7/O8/2O26") },
        )
    }

    @Test
    fun `layout C uses geometry and roles when semantic blocks arrive shuffled`() {
        val clientBlock = block(
            line("CLIENTE", rect(40, 360, 220, 394)),
            line("TIENDAS LIMA S.A.", rect(40, 404, 410, 438)),
            line("RUC: 20123456786", rect(40, 448, 350, 482)),
        )
        val documentBlock = block(
            line("BOLETA DE VENTA ELECTRÓNICA", rect(35, 35, 455, 72)),
            line("B001-000078", rect(105, 88, 330, 124)),
            line("Fecha de emisión: 19/08/2026", rect(35, 142, 455, 177)),
            line("Moneda: USD", rect(35, 192, 285, 226)),
        )
        val issuerBlock = block(
            line("EMISOR: SERVICIOS PACÍFICO E.I.R.L.", rect(520, 35, 970, 72)),
            line("RUC DEL EMISOR: 20512345671", rect(560, 88, 970, 124)),
        )
        val page = page(
            widthPx = 1_000,
            heightPx = 1_400,
            // El orden semántico está deliberadamente invertido: cliente, documento, emisor.
            blocks = listOf(clientBlock, documentBlock, issuerBlock),
        )

        val result = InvoiceHeaderParser().parse(
            snapshot(page),
            buyerRuc = "20123456786",
        )

        assertEquals("SALES_RECEIPT", result.documentType.selected?.value?.name)
        assertEquals("20512345671", result.issuerRuc.selected?.value)
        assertEquals("SERVICIOS PACÍFICO E.I.R.L.", result.issuerLegalName.selected?.value)
        assertEquals("B001", result.documentNumber.selected?.value?.series)
        assertEquals("000078", result.documentNumber.selected?.value?.correlative)
        assertEquals("B001-000078", result.documentNumber.selected?.value?.normalized)
        assertEquals(LocalDate.of(2026, 8, 19), result.issueDate.selected?.value)
        assertEquals("USD", result.currency.selected?.value?.value)
        assertTrue(
            result.issuerRuc.alternatives.any { candidate ->
                candidate.value == "20123456786"
            },
        )
        assertEquals(2, result.issuerRuc.all.map { candidate -> candidate.value }.distinct().size)
        assertTrue(
            requireNotNull(result.issuerRuc.selected).evidence.any { evidence ->
                evidence.rawText.contains("RUC DEL EMISOR")
            },
        )
    }

    @Test
    fun `layout D pairs separate columns using corner points and retains local RUC warning`() {
        val page = page(
            widthPx = 1_000,
            heightPx = 1_400,
            blocks = listOf(
                block(line("RAZÓN SOCIAL", rect(40, 35, 250, 70), cornerOnly = true)),
                block(
                    line(
                        "IMPORTADORA DEL NORTE S.R.L.",
                        rect(300, 35, 780, 70),
                        cornerOnly = true,
                    ),
                ),
                block(line("RUC", rect(40, 90, 150, 124), cornerOnly = true)),
                block(line("20131312954", rect(300, 90, 555, 124), cornerOnly = true)),
                block(line("TIPO", rect(40, 145, 150, 179), cornerOnly = true)),
                block(line("FACTURA", rect(300, 145, 500, 179), cornerOnly = true)),
                block(line("SERIE", rect(40, 205, 155, 239), cornerOnly = true)),
                block(line("F001", rect(210, 205, 315, 239), cornerOnly = true)),
                block(line("CORRELATIVO", rect(430, 205, 620, 239), cornerOnly = true)),
                block(line("12345", rect(700, 205, 835, 239), cornerOnly = true)),
                block(
                    line("FECHA DE EMISIÓN", rect(40, 265, 280, 299), cornerOnly = true),
                ),
                block(line("17/08/2026", rect(300, 265, 535, 299), cornerOnly = true)),
                block(line("MONEDA", rect(40, 325, 190, 359), cornerOnly = true)),
                block(line("PEN", rect(300, 325, 395, 359), cornerOnly = true)),
            ),
        )

        assertTrue(
            page.blocks.flatMap { it.lines }.all { line ->
                line.geometry.boundingBox == null && line.geometry.cornerPoints.size == 4
            },
        )

        val result = InvoiceHeaderParser().parse(snapshot(page))

        assertEquals("INVOICE", result.documentType.selected?.value?.name)
        assertEquals("20131312954", result.issuerRuc.selected?.value)
        assertEquals("IMPORTADORA DEL NORTE S.R.L.", result.issuerLegalName.selected?.value)
        assertEquals("F001", result.documentNumber.selected?.value?.series)
        assertEquals("12345", result.documentNumber.selected?.value?.correlative)
        assertEquals("F001-12345", result.documentNumber.selected?.value?.normalized)
        assertEquals(LocalDate.of(2026, 8, 17), result.issueDate.selected?.value)
        assertEquals("PEN", result.currency.selected?.value?.value)

        val retainedRuc = requireNotNull(result.issuerRuc.selected)
        assertTrue(retainedRuc.warnings.isNotEmpty())
        assertTrue(retainedRuc.rawText.contains("20131312954"))
        assertTrue(
            retainedRuc.evidence.any { evidence ->
                evidence.rawText == "20131312954" && evidence.pageIndex == 0
            },
        )
    }

    @Test
    fun `layout E joins wrapped issuer name and leaves genuinely absent fields empty`() {
        val firstPage = page(
            pageIndex = 0,
            widthPx = 800,
            heightPx = 1_200,
            blocks = listOf(
                block(
                    line("RAZÓN SOCIAL", rect(40, 35, 300, 69)),
                    line("IMPORTACIONES", rect(40, 80, 320, 114)),
                    line("DEL PACÍFICO E.I.R.L.", rect(40, 120, 430, 154)),
                    line("RUC", rect(40, 180, 125, 214)),
                    line("20600055519", rect(40, 224, 300, 258)),
                ),
                block(
                    // Sin geometría: solo se permite el fallback textual dentro del bloque.
                    line("FACTURA ELECTRÓNICA F001-12345", bounds = null),
                ),
            ),
        )
        val secondPage = page(
            pageIndex = 1,
            widthPx = 800,
            heightPx = 1_200,
            blocks = listOf(
                block(
                    line("DATOS DEL CLIENTE", rect(40, 40, 360, 74)),
                    line("RUC: 20123456786", rect(40, 86, 340, 120)),
                    line("TOTAL 10.00", rect(500, 1_080, 750, 1_115)),
                ),
            ),
        )

        val result = InvoiceHeaderParser().parse(
            snapshot(firstPage, secondPage),
            buyerRuc = "20123456786",
        )

        assertEquals("INVOICE", result.documentType.selected?.value?.name)
        assertEquals("20600055519", result.issuerRuc.selected?.value)
        assertEquals(
            "IMPORTACIONES DEL PACÍFICO E.I.R.L.",
            result.issuerLegalName.selected?.value,
        )
        assertEquals("F001", result.documentNumber.selected?.value?.series)
        assertEquals("12345", result.documentNumber.selected?.value?.correlative)
        assertEquals("F001-12345", result.documentNumber.selected?.value?.normalized)
        assertNull(result.issueDate.selected)
        assertTrue(result.issueDate.alternatives.isEmpty())
        assertTrue(result.issueDate.all.isEmpty())
        assertNull(result.currency.selected)
        assertTrue(result.currency.alternatives.isEmpty())
        assertTrue(result.currency.all.isEmpty())

        val name = requireNotNull(result.issuerLegalName.selected)
        assertTrue(name.evidence.any { evidence -> evidence.rawText == "IMPORTACIONES" })
        assertTrue(
            name.evidence.any { evidence -> evidence.rawText == "DEL PACÍFICO E.I.R.L." },
        )
        assertFalse(name.rawText.contains('\n'))
    }

    @Test
    fun `two RUC without party role stay unresolved with two explained alternatives`() {
        val page = page(
            widthPx = 800,
            heightPx = 1_200,
            blocks = listOf(
                block(line("RUC: 20131312955", rect(40, 45, 340, 80))),
                block(line("RUC: 20512345671", rect(40, 105, 340, 140))),
            ),
        )

        val field = InvoiceHeaderParser().parse(snapshot(page)).issuerRuc

        assertNull(field.selected)
        assertEquals(
            setOf("20131312955", "20512345671"),
            field.alternatives.map { candidate -> candidate.value }.toSet(),
        )
        assertEquals(2, field.all.size)
        field.alternatives.forEach { candidate ->
            assertTrue(InvoiceHeaderWarning.AMBIGUOUS_VALUE in candidate.warnings)
            assertTrue(InvoiceHeaderReason.EXPLICIT_FIELD_LABEL in candidate.reasons)
            assertTrue(InvoiceHeaderReason.TOP_OF_FIRST_PAGE in candidate.reasons)
            assertTrue(candidate.evidence.any { evidence -> evidence.pageIndex == 0 })
        }
    }

    @Test
    fun `a sole recipient RUC is never selected by label or active buyer match`() {
        val explicitlyRecipient = InvoiceHeaderParser().parse(
            snapshot(
                page(
                    widthPx = 800,
                    heightPx = 1_200,
                    blocks = listOf(
                        block(
                            line(
                                "CLIENTE RUC: 20131312955",
                                rect(40, 45, 500, 80),
                            ),
                        ),
                    ),
                ),
            ),
        ).issuerRuc
        val matchingBuyer = InvoiceHeaderParser().parse(
            snapshot(
                page(
                    widthPx = 800,
                    heightPx = 1_200,
                    blocks = listOf(
                        block(line("RUC: 20131312955", rect(40, 45, 340, 80))),
                    ),
                ),
            ),
            buyerRuc = "20131312955",
        ).issuerRuc

        assertNull(explicitlyRecipient.selected)
        assertEquals("20131312955", explicitlyRecipient.alternatives.single().value)
        assertTrue(
            InvoiceHeaderWarning.RECIPIENT_CONTEXT in
                explicitlyRecipient.alternatives.single().warnings,
        )
        assertTrue(
            InvoiceHeaderReason.EXPLICIT_RECIPIENT_CONTEXT in
                explicitlyRecipient.alternatives.single().reasons,
        )

        assertNull(matchingBuyer.selected)
        assertEquals("20131312955", matchingBuyer.alternatives.single().value)
        assertTrue(
            InvoiceHeaderWarning.RECIPIENT_CONTEXT in matchingBuyer.alternatives.single().warnings,
        )
        assertTrue(
            InvoiceHeaderReason.MATCHES_ACTIVE_BUYER in matchingBuyer.alternatives.single().reasons,
        )
    }

    @Test
    fun `issue date outranks due date and an impossible calendar date is rejected`() {
        val page = page(
            widthPx = 800,
            heightPx = 1_200,
            blocks = listOf(
                block(line("Fecha de vencimiento: 31/08/2026", rect(40, 45, 500, 80))),
                block(line("Fecha de emisión: 17/08/2026", rect(40, 105, 500, 140))),
                block(line("Fecha de emisión: 31/04/2026", rect(40, 165, 500, 200))),
            ),
        )

        val field = InvoiceHeaderParser().parse(snapshot(page)).issueDate

        assertEquals(LocalDate.of(2026, 8, 17), field.selected?.value)
        assertTrue(
            InvoiceHeaderReason.ISSUE_DATE_LABEL in requireNotNull(field.selected).reasons,
        )
        assertEquals(
            listOf(LocalDate.of(2026, 8, 31)),
            field.alternatives.map { candidate -> candidate.value },
        )
        assertTrue(
            InvoiceHeaderReason.OTHER_DATE_LABEL in field.alternatives.single().reasons,
        )
        assertFalse(field.all.any { candidate -> candidate.rawText.contains("31/04/2026") })
    }

    @Test
    fun `explicit USD currency outranks a PEN total symbol while preserving the fallback`() {
        val page = page(
            widthPx = 800,
            heightPx = 1_200,
            blocks = listOf(
                block(line("TOTAL S/ 100.00", rect(470, 900, 760, 940))),
                block(line("Moneda: USD", rect(40, 45, 300, 80))),
            ),
        )

        val field = InvoiceHeaderParser().parse(snapshot(page)).currency

        assertEquals("USD", field.selected?.value?.value)
        assertTrue(
            InvoiceHeaderReason.EXPLICIT_CURRENCY_LABEL in requireNotNull(field.selected).reasons,
        )
        val penFallback = field.alternatives.single { candidate -> candidate.value.value == "PEN" }
        assertTrue(InvoiceHeaderWarning.CURRENCY_FROM_AMOUNT_SYMBOL in penFallback.warnings)
        assertTrue(InvoiceHeaderReason.AMOUNT_CURRENCY_SYMBOL in penFallback.reasons)
        assertTrue(penFallback.rawText.contains("TOTAL S/"))
    }

    @Test
    fun `an empty textual page produces six empty header fields`() {
        val emptyPage = page(
            widthPx = 800,
            heightPx = 1_200,
            blocks = emptyList(),
        )

        val result = InvoiceHeaderParser().parse(snapshot(emptyPage))

        assertNull(result.documentType.selected)
        assertTrue(result.documentType.all.isEmpty())
        assertNull(result.issuerRuc.selected)
        assertTrue(result.issuerRuc.all.isEmpty())
        assertNull(result.issuerLegalName.selected)
        assertTrue(result.issuerLegalName.all.isEmpty())
        assertNull(result.documentNumber.selected)
        assertTrue(result.documentNumber.all.isEmpty())
        assertNull(result.issueDate.selected)
        assertTrue(result.issueDate.all.isEmpty())
        assertNull(result.currency.selected)
        assertTrue(result.currency.all.isEmpty())
    }

    @Test
    fun `a structurally invalid labeled RUC is retained without inventing a repair`() {
        val malformed = InvoiceHeaderParser().parse(
            snapshot(
                page(
                    widthPx = 800,
                    heightPx = 1_200,
                    blocks = listOf(
                        block(
                            line("EMISOR", rect(40, 35, 200, 69)),
                            line("RUC: 2010007097S", rect(40, 80, 360, 114)),
                        ),
                    ),
                ),
            ),
        ).issuerRuc

        val candidate = requireNotNull(malformed.selected)
        assertEquals("2010007097S", candidate.value)
        assertTrue(InvoiceHeaderWarning.RUC_STRUCTURE_MISMATCH_LOCAL in candidate.warnings)
        assertFalse(candidate.evidence.flatMap { it.corrections }.any { correction ->
            correction.originalFragment.contains('S')
        })
        assertTrue(candidate.rawText.contains("2010007097S"))
    }

    @Test
    fun `compatibility digits remain evidenced and other numeral systems are not RUC tokens`() {
        val compatibility = InvoiceHeaderParser().parse(
            snapshot(
                page(
                    widthPx = 800,
                    heightPx = 1_200,
                    blocks = listOf(
                        block(
                            line("EMISOR", rect(40, 35, 200, 69)),
                            line("RUC: ２０１３１３１２９５５", rect(40, 80, 430, 115)),
                        ),
                    ),
                ),
            ),
        ).issuerRuc
        val otherNumerals = InvoiceHeaderParser().parse(
            snapshot(
                page(
                    widthPx = 800,
                    heightPx = 1_200,
                    blocks = listOf(
                        block(line("RUC: ٢٠١٣١٣١٢٩٥٥", rect(40, 35, 430, 70))),
                    ),
                ),
            ),
        ).issuerRuc

        assertEquals("20131312955", compatibility.selected?.value)
        assertTrue(
            InvoiceHeaderWarning.UNICODE_COMPATIBILITY_NORMALIZED in
                requireNotNull(compatibility.selected).warnings,
        )
        assertTrue(
            requireNotNull(compatibility.selected).evidence.any { evidence ->
                evidence.rawText.contains("２０１３１３１２９５５")
            },
        )
        assertTrue(otherNumerals.all.isEmpty())
    }

    @Test
    fun `RUC context canonicalizes buyer spaces and accepts dotted labels with safe OCR noise`() {
        val buyer = InvoiceHeaderParser().parse(
            snapshot(
                page(
                    widthPx = 800,
                    heightPx = 1_200,
                    blocks = listOf(
                        block(line("EMISOR RUC: 20512345671", rect(40, 35, 500, 70))),
                    ),
                ),
            ),
            buyerRuc = "  20512345671  ",
        ).issuerRuc
        val dotted = InvoiceHeaderParser().parse(
            snapshot(
                page(
                    widthPx = 800,
                    heightPx = 1_200,
                    blocks = listOf(
                        block(
                            line("EMISOR", rect(40, 35, 200, 69)),
                            line("R.U.C.: 20I3I3I2955", rect(40, 80, 430, 115)),
                        ),
                    ),
                ),
            ),
        ).issuerRuc

        assertNull(buyer.selected)
        assertEquals("20512345671", buyer.alternatives.single().value)
        assertTrue(InvoiceHeaderReason.MATCHES_ACTIVE_BUYER in buyer.alternatives.single().reasons)
        assertEquals("20131312955", dotted.selected?.value)
        assertTrue(InvoiceHeaderWarning.OCR_CORRECTION_APPLIED in requireNotNull(dotted.selected).warnings)
    }

    @Test
    fun `client word inside issuer legal name is not treated as a recipient section`() {
        val field = InvoiceHeaderParser().parse(
            snapshot(
                page(
                    widthPx = 800,
                    heightPx = 1_200,
                    blocks = listOf(
                        block(
                            line("SERVICIOS AL CLIENTE S.A.C.", rect(40, 35, 500, 70)),
                            line("RUC: 20131312955", rect(40, 80, 340, 115)),
                        ),
                    ),
                ),
            ),
        ).issuerRuc

        assertEquals("20131312955", field.selected?.value)
        assertFalse(InvoiceHeaderWarning.RECIPIENT_CONTEXT in requireNotNull(field.selected).warnings)
        assertTrue(InvoiceHeaderReason.ISSUER_LEGAL_NAME_CONTEXT in field.selected.reasons)
    }

    @Test
    fun `recipient legal-name label cannot populate issuer fields`() {
        val result = InvoiceHeaderParser().parse(
            snapshot(
                page(
                    widthPx = 800,
                    heightPx = 1_200,
                    blocks = listOf(
                        block(
                            line(
                                "RAZÓN SOCIAL DEL CLIENTE: COMERCIAL ANDINA S.A.C.",
                                rect(40, 35, 700, 70),
                            ),
                            line("RUC: 20512345671", rect(40, 80, 340, 115)),
                        ),
                    ),
                ),
            ),
        )

        assertNull(result.issuerRuc.selected)
        assertEquals("20512345671", result.issuerRuc.alternatives.single().value)
        assertTrue(
            InvoiceHeaderWarning.RECIPIENT_CONTEXT in result.issuerRuc.alternatives.single().warnings,
        )
        assertNull(result.issuerLegalName.selected)
        assertTrue(result.issuerLegalName.all.all { candidate ->
            InvoiceHeaderWarning.RECIPIENT_CONTEXT in candidate.warnings
        })
    }

    @Test
    fun `a lone top RUC without positive issuer context remains an alternative`() {
        val field = InvoiceHeaderParser().parse(
            snapshot(
                page(
                    widthPx = 800,
                    heightPx = 1_200,
                    blocks = listOf(
                        block(line("RUC: 20512345671", rect(40, 35, 340, 70))),
                    ),
                ),
            ),
        ).issuerRuc

        assertNull(field.selected)
        assertEquals("20512345671", field.alternatives.single().value)
        assertTrue(InvoiceHeaderWarning.AMBIGUOUS_PARTY_ROLE in field.alternatives.single().warnings)
    }

    @Test
    fun `date tokens bind to the nearest label including two labels on one OCR line`() {
        val separateColumns = InvoiceHeaderParser().parse(
            snapshot(
                page(
                    widthPx = 1_000,
                    heightPx = 1_400,
                    blocks = listOf(
                        block(line("FECHA DE VENCIMIENTO", rect(20, 40, 220, 75))),
                        block(line("FECHA DE EMISIÓN", rect(250, 40, 450, 75))),
                        block(line("17/08/2026", rect(500, 40, 700, 75))),
                    ),
                ),
            ),
        ).issueDate
        val sameLine = InvoiceHeaderParser().parse(
            snapshot(
                page(
                    widthPx = 1_000,
                    heightPx = 1_400,
                    blocks = listOf(
                        block(
                            line(
                                "FECHA DE EMISIÓN: 17/08/2026 FECHA DE VENCIMIENTO: 18/08/2026",
                                rect(20, 40, 950, 80),
                            ),
                        ),
                    ),
                ),
            ),
        ).issueDate

        assertEquals(LocalDate.of(2026, 8, 17), separateColumns.selected?.value)
        assertEquals(LocalDate.of(2026, 8, 17), sameLine.selected?.value)
        assertEquals(
            listOf(LocalDate.of(2026, 8, 18)),
            sameLine.alternatives.map { candidate -> candidate.value },
        )
    }

    @Test
    fun `currency words in a company name are ignored and a nearby total stays fallback`() {
        val companyOnly = InvoiceHeaderParser().parse(
            snapshot(
                page(
                    widthPx = 800,
                    heightPx = 1_200,
                    blocks = listOf(
                        block(line("IMPORTACIONES SOLES S.A.C.", rect(40, 35, 500, 70))),
                    ),
                ),
            ),
        ).currency
        val explicitAndTotal = InvoiceHeaderParser().parse(
            snapshot(
                page(
                    widthPx = 800,
                    heightPx = 1_200,
                    blocks = listOf(
                        block(line("MONEDA: USD", rect(40, 35, 300, 70))),
                        block(line("TOTAL S/ 10.00", rect(40, 90, 350, 125))),
                    ),
                ),
            ),
        ).currency

        assertTrue(companyOnly.all.isEmpty())
        assertEquals("USD", explicitAndTotal.selected?.value?.value)
        val pen = explicitAndTotal.alternatives.single { candidate -> candidate.value.value == "PEN" }
        assertTrue(InvoiceHeaderWarning.CURRENCY_FROM_AMOUNT_SYMBOL in pen.warnings)
    }

    @Test
    fun `inherited block geometry falls back to sequence and page text keeps party sections apart`() {
        val basePage = page(
            widthPx = 800,
            heightPx = 1_200,
            blocks = listOf(
                block(
                    line("SERIE", bounds = null),
                    line("F001", bounds = null),
                    line("CORRELATIVO", bounds = null),
                    line("000123", bounds = null),
                ),
            ),
        )
        val inheritedPage = basePage.copy(
            blocks = listOf(
                basePage.blocks.single().copy(
                    geometry = InvoiceTextGeometry(
                        InvoiceTextBoundingBox(20, 20, 500, 180),
                        emptyList(),
                    ),
                ),
            ),
        )
        val number = InvoiceHeaderParser().parse(snapshot(inheritedPage)).documentNumber

        val fallbackPage = page(
            widthPx = 800,
            heightPx = 1_200,
            blocks = emptyList(),
        ).copy(
            text = "EMISOR\nRUC: 20131312955\nCLIENTE\nRUC: 20512345671",
        )
        val ruc = InvoiceHeaderParser().parse(snapshot(fallbackPage)).issuerRuc

        assertEquals("F001-000123", number.selected?.value?.normalized)
        assertTrue(InvoiceHeaderWarning.WEAK_GEOMETRY_MATCH in requireNotNull(number.selected).warnings)
        assertEquals("20131312955", ruc.selected?.value)
        assertEquals("20512345671", ruc.alternatives.single().value)
        assertTrue(InvoiceHeaderWarning.RECIPIENT_CONTEXT in ruc.alternatives.single().warnings)
    }

    private fun snapshot(vararg pages: InvoiceTextPage): InvoiceOcrSnapshot = InvoiceOcrSnapshot(
        draftId = DRAFT_ID,
        runId = RUN_ID,
        completedAt = COMPLETED_AT,
        document = InvoiceTextDocument(pages.toList()),
    )

    private fun page(
        widthPx: Int,
        heightPx: Int,
        blocks: List<BlockSpec>,
        pageIndex: Int = 0,
    ): InvoiceTextPage {
        val mappedBlocks = blocks.mapIndexed { blockIndex, spec ->
            val blockBounds = spec.lines.mapNotNull(LineSpec::bounds).union()
            InvoiceTextBlock(
                position = blockIndex,
                text = spec.lines.joinToString("\n", transform = LineSpec::text),
                languageTag = "es-PE",
                geometry = blockBounds?.toGeometry(
                    cornerOnly = spec.lines.all(LineSpec::cornerOnly),
                ) ?: InvoiceTextGeometry(null, emptyList()),
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
            sourceImageId = ImageId.from(UUID(0L, 100L + pageIndex)),
            pageIndex = pageIndex,
            widthPx = widthPx,
            heightPx = heightPx,
            text = mappedBlocks.joinToString("\n", transform = InvoiceTextBlock::text),
            blocks = mappedBlocks,
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

    private fun List<RectSpec>.union(): RectSpec? = takeIf(List<RectSpec>::isNotEmpty)?.let {
        RectSpec(
            left = minOf { bounds -> bounds.left },
            top = minOf { bounds -> bounds.top },
            right = maxOf { bounds -> bounds.right },
            bottom = maxOf { bounds -> bounds.bottom },
        )
    }

    private fun RectSpec.toGeometry(cornerOnly: Boolean): InvoiceTextGeometry =
        InvoiceTextGeometry(
            boundingBox = toBoundingBox().takeUnless { cornerOnly },
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

    private fun RectSpec.toBoundingBox(): InvoiceTextBoundingBox = InvoiceTextBoundingBox(
        leftPx = left,
        topPx = top,
        rightPx = right,
        bottomPx = bottom,
    )

    private fun block(vararg lines: LineSpec): BlockSpec = BlockSpec(lines.toList())

    private fun line(
        text: String,
        bounds: RectSpec?,
        elements: List<ElementSpec> = emptyList(),
        cornerOnly: Boolean = false,
        confidencePermille: Int = 930,
    ): LineSpec = LineSpec(
        text = text,
        bounds = bounds,
        elements = elements,
        cornerOnly = cornerOnly,
        confidencePermille = confidencePermille,
    )

    private fun element(text: String, bounds: RectSpec): ElementSpec = ElementSpec(text, bounds)

    private fun rect(left: Int, top: Int, right: Int, bottom: Int): RectSpec =
        RectSpec(left, top, right, bottom)

    private data class BlockSpec(
        val lines: List<LineSpec>,
    )

    private data class LineSpec(
        val text: String,
        val bounds: RectSpec?,
        val elements: List<ElementSpec>,
        val cornerOnly: Boolean,
        val confidencePermille: Int,
    )

    private data class ElementSpec(
        val text: String,
        val bounds: RectSpec,
    )

    private data class RectSpec(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    private companion object {
        val DRAFT_ID: DraftId = DraftId.from(UUID(0L, 1L))
        val RUN_ID: OcrRunId = OcrRunId.from(UUID(0L, 2L))
        val COMPLETED_AT: Instant = Instant.parse("2026-08-09T15:00:00Z")
    }
}
