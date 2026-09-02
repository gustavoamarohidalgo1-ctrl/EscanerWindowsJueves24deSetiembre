package com.facturastock.app.testing

import com.facturastock.app.di.OcrModule
import com.facturastock.app.domain.model.DemoPurchaseScenario
import com.facturastock.app.domain.model.InvoiceTextDocument
import com.facturastock.app.domain.model.InvoiceTextBlock
import com.facturastock.app.domain.model.InvoiceTextBoundingBox
import com.facturastock.app.domain.model.InvoiceTextGeometry
import com.facturastock.app.domain.model.InvoiceTextLine
import com.facturastock.app.domain.model.InvoiceTextPage
import com.facturastock.app.domain.repository.InvoiceTextRecognizer
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/** Mantiene herméticos los tests Hilt; el smoke test de ML Kit instancia el adaptador real. */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [OcrModule::class],
)
object TestOcrModule {
    @Provides
    @Singleton
    fun provideInvoiceTextRecognizer(): InvoiceTextRecognizer = InvoiceTextRecognizer { pages ->
        InvoiceTextDocument(
            pages.mapIndexed { index, page ->
                val cells = completeInvoiceCells()
                val blocks = cells.mapIndexed { position, cell ->
                    val geometry = InvoiceTextGeometry(
                        boundingBox = cell.scaledBox(page.widthPx, page.heightPx),
                        cornerPoints = emptyList(),
                    )
                    InvoiceTextBlock(
                        position = position,
                        text = cell.text,
                        languageTag = "es-PE",
                        geometry = geometry,
                        lines = listOf(
                            InvoiceTextLine(
                                position = 0,
                                text = cell.text,
                                languageTag = "es-PE",
                                geometry = geometry,
                                confidencePermille = 950,
                                clockwiseAngleTenths = 0,
                                elements = emptyList(),
                            ),
                        ),
                    )
                }
                InvoiceTextPage(
                    sourceImageId = page.sourceImageId,
                    pageIndex = index,
                    widthPx = page.widthPx,
                    heightPx = page.heightPx,
                    text = cells.joinToString("\n", transform = TestCell::text),
                    blocks = blocks,
                )
            },
        )
    }

    private fun completeInvoiceCells(): List<TestCell> = listOf(
        // Los prefijos explícitos mantienen el fake alineado con el contrato del parser. Un
        // nombre desnudo podía hacer que el RUC contiguo se interpretara como dato del comprador.
        TestCell(
            "PROVEEDOR: ${DemoPurchaseScenario.PRIMARY_SUPPLIER_LEGAL_NAME}",
            40,
            35,
            590,
            70,
        ),
        TestCell("RUC: ${DemoPurchaseScenario.PRIMARY_SUPPLIER_RUC}", 40, 80, 370, 115),
        TestCell("FACTURA ELECTRÓNICA", 650, 35, 1_160, 70),
        TestCell("NRO: F001-12345", 760, 85, 1_050, 120),
        TestCell("Fecha de emisión: 17/08/2026", 650, 140, 1_160, 175),
        TestCell("Moneda: PEN", 650, 190, 1_000, 225),
        TestCell("DESCRIPCIÓN", 40, 300, 430, 335),
        TestCell("CANTIDAD", 450, 300, 530, 335),
        TestCell("U.M.", 540, 300, 620, 335),
        TestCell("P. UNITARIO", 640, 300, 780, 335),
        TestCell("IGV", 800, 300, 900, 335),
        TestCell("IMPORTE", 930, 300, 1_160, 335),
        TestCell("ARROZ EXTRA 5 KG", 40, 365, 430, 400),
        TestCell("2", 450, 365, 530, 400),
        TestCell("NIU", 540, 365, 620, 400),
        TestCell("S/ 50.00", 640, 365, 780, 400),
        TestCell("S/ 18.00", 800, 365, 900, 400),
        TestCell("S/ 118.00", 930, 365, 1_160, 400),
        TestCell("OP. GRAVADA", 650, 900, 930, 935),
        TestCell("S/ 100.00", 960, 900, 1_160, 935),
        TestCell("IGV 18%", 650, 960, 930, 995),
        TestCell("S/ 18.00", 960, 960, 1_160, 995),
        TestCell("TOTAL A PAGAR", 650, 1_020, 930, 1_055),
        TestCell("S/ 118.00", 960, 1_020, 1_160, 1_055),
    )

    private data class TestCell(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        fun scaledBox(widthPx: Int, heightPx: Int): InvoiceTextBoundingBox {
            val scaledLeft = left * widthPx / BASE_WIDTH
            val scaledTop = top * heightPx / BASE_HEIGHT
            val scaledRight = (right * widthPx / BASE_WIDTH).coerceAtLeast(scaledLeft + 1)
            val scaledBottom = (bottom * heightPx / BASE_HEIGHT).coerceAtLeast(scaledTop + 1)
            return InvoiceTextBoundingBox(
                leftPx = scaledLeft,
                topPx = scaledTop,
                rightPx = scaledRight.coerceAtMost(widthPx),
                bottomPx = scaledBottom.coerceAtMost(heightPx),
            )
        }
    }

    private const val BASE_WIDTH = 1_200
    private const val BASE_HEIGHT = 1_600
}
