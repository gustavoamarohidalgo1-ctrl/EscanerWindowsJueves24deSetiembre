package com.facturastock.app.data.export

import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.DebtPayment
import com.facturastock.app.domain.model.DebtPaymentMethod
import com.facturastock.app.domain.model.DebtPaymentReportItem
import com.facturastock.app.domain.model.DebtStatus
import com.facturastock.app.domain.model.DebtSummary
import com.facturastock.app.domain.model.ExactMonetaryAmount
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.PreparedReportPdf
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.RealizedSaleLineProfit
import com.facturastock.app.domain.model.RealizedSaleProfit
import com.facturastock.app.domain.model.ReportPdfKind
import com.facturastock.app.domain.model.ReportPdfSnapshot
import com.facturastock.app.domain.model.SalesReportPeriod
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DebtId
import com.facturastock.app.domain.model.id.DebtPaymentId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.SaleId
import com.facturastock.app.domain.model.id.SaleLineId
import com.facturastock.app.domain.usecase.currentSalesReportRange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * Artefactos sintéticos del renderer real (PDFBox). Se rasteriza cada página con PDFBox para
 * comprobar tamaño A4 y contenido; con `-Dreport.pdf.qa.dir=<carpeta>` se conservan los PDF para
 * revisarlos a mano.
 */
class ReportPdfRendererTest {
    private val keepDirectory: File? = System.getProperty("report.pdf.qa.dir")?.takeIf(String::isNotBlank)?.let(::File)
    private val directory: File =
        (keepDirectory ?: Files.createTempDirectory("report-pdf-qa").toFile()).apply { mkdirs() }

    @After
    fun tearDown() {
        if (keepDirectory == null) directory.deleteRecursively()
    }

    @Test
    fun combinedReportContainsRenderableA4PagesForLongSalesAndDebtors() =
        runBlocking {
            val prepared = sample(ReportPdfKind.DAILY_SALES_WITH_DEBTORS)
            val file = File(directory, "ventas-dia-y-deudores-demo.pdf")
            file.outputStream().use { ReportPdfRenderer().write(prepared, it) }
            assertTrue(
                file.inputStream().use { input ->
                    val header = ByteArray(5)
                    input.read(header) == 5 && String(header, Charsets.US_ASCII) == "%PDF-"
                },
            )
            assertTrue(checkEveryPage(file) >= 3)
            File(directory, "expected-sale-ids.txt").writeText(prepared.snapshot.sales.joinToString("\n") { it.saleId.value })
            File(directory, "expected-debt-sale-ids.txt").writeText(prepared.snapshot.debts.joinToString("\n") { it.saleId.value })
        }

    @Test
    fun debtorsOnlyReportKeepsMultipleCurrenciesAndContinuesTables() =
        runBlocking {
            val file = File(directory, "deudores-demo.pdf")
            file.outputStream().use { ReportPdfRenderer().write(sample(ReportPdfKind.DEBTORS, debtCount = 38), it) }
            assertTrue(checkEveryPage(file) >= 3)
        }

    @Test
    fun emptyDayStillProducesAnExplicitReadableReport() =
        runBlocking {
            val file = File(directory, "reporte-vacio-demo.pdf")
            file.outputStream().use { ReportPdfRenderer().write(sample(ReportPdfKind.DAILY_SALES_WITH_DEBTORS, empty = true), it) }
            assertEquals(1, checkEveryPage(file))
        }

    @Test
    fun collectedDebtPaymentsRenderEveryReferenceAndSeparateCurrenciesAcrossPages() =
        runBlocking {
            val base = sample(ReportPdfKind.DAILY_SALES_WITH_DEBTORS, empty = true)
            val payments =
                (1..16).map { index ->
                    val currency = if (index % 3 == 0) USD else PEN
                    DebtPaymentReportItem(
                        BUSINESS,
                        SaleId.from(UUID(41L, index.toLong())),
                        if (index % 4 == 0) "José Ñahui y María del Carmen Quispe Apaza" else "Cliente de pago directo $index",
                        DebtPayment(
                            DebtPaymentId.from(UUID(42L, index.toLong())),
                            DebtId.from(UUID(43L, index.toLong())),
                            Money.ofMinor(1234L + index, currency),
                            DebtPaymentMethod.OTHER,
                            null,
                            null,
                            1L,
                            Money.ofMinor(0L, currency),
                            NOW.minusSeconds(index * 60L),
                            NOW.minusSeconds(index * 60L),
                        ),
                    )
                }
            val prepared = base.copy(snapshot = base.snapshot.copy(debtPayments = payments))
            val file = File(directory, "cobros-deudas-dia-demo.pdf")
            file.outputStream().use { ReportPdfRenderer().write(prepared, it) }
            assertTrue(checkEveryPage(file) >= 2)
            File(directory, "expected-payment-ids.txt").writeText(payments.joinToString("\n") { it.payment.paymentId.value })
            File(directory, "expected-payment-totals.txt").writeText(
                payments.groupBy { it.payment.amount.currency }.entries.joinToString("\n") { (currency, items) ->
                    val total = items.fold(BigDecimal.ZERO) { sum, item -> sum.add(item.payment.amount.toMajor()) }
                    "${currency.value}|${total.toPlainString()}"
                },
            )
        }

    @Test
    fun cancelledWorkDoesNotEmitAnIncompleteDocument() =
        runBlocking {
            val output = ByteArrayOutputStream()
            val job = Job().apply { cancel() }
            var cancelled = false
            try {
                withContext(job) { ReportPdfRenderer().write(sample(ReportPdfKind.DEBTORS), output) }
            } catch (_: CancellationException) {
                cancelled = true
            }
            assertTrue(cancelled)
            assertEquals(0, output.size())
        }

    @Test
    fun spanishTextSurvivesAndUnsupportedCharactersAreReplacedSafely() =
        runBlocking {
            val base = sample(ReportPdfKind.DEBTORS, debtCount = 3)
            val prepared =
                base.copy(
                    snapshot = base.snapshot.copy(businessName = "Bodega Ñandú ¿Qué tal? ¡Sí! 😀 漢字\u200B fin"),
                )
            val file = File(directory, "texto-especial.pdf")
            file.outputStream().use { ReportPdfRenderer(fontsDirectory = null).write(prepared, it) }
            // Las celdas estrechas parten líneas: se comparan palabras, no saltos de línea.
            val text = extractText(file).replace(Regex("\\s+"), " ")
            assertTrue(text.contains("Bodega Ñandú ¿Qué tal? ¡Sí!"))
            assertTrue(text.contains("José Ñahui y María del Carmen Quispe Apaza"))
            assertTrue(text.contains("Página 1"))
            assertTrue(text.contains("Ventas del día") || text.contains("Deudores pendientes"))
            assertFalse(text.contains("😀"))
            assertTrue(text.contains("? ?? fin"))
        }

    @Test
    fun everySaleDebtAndPaymentReferenceIsSelectableText() =
        runBlocking {
            val prepared = sample(ReportPdfKind.DAILY_SALES_WITH_DEBTORS)
            val file = File(directory, "referencias.pdf")
            file.outputStream().use { ReportPdfRenderer(fontsDirectory = null).write(prepared, it) }
            val text = extractText(file).replace(Regex("\\s+"), "")
            prepared.snapshot.sales.forEach { assertTrue(text.contains(it.saleId.value)) }
            prepared.snapshot.debts.forEach { assertTrue(text.contains(it.saleId.value)) }
            assertTrue(text.contains("Saldopendiente"))
        }

    @Test
    fun systemTrueTypeFontsAreEmbeddedWhenAvailable() =
        runBlocking {
            val fonts = listOf(
                File("C:\\Windows\\Fonts"),
                File("/System/Library/Fonts/Supplemental"),
                File("/Library/Fonts"),
            )
            val source = fonts.firstNotNullOfOrNull { dir ->
                val regular = listOf("arial.ttf", "Arial.ttf").map { File(dir, it) }.firstOrNull(File::isFile)
                val bold = listOf("arialbd.ttf", "Arial Bold.ttf").map { File(dir, it) }.firstOrNull(File::isFile)
                if (regular != null && bold != null) regular to bold else null
            }
            assumeTrue("Arial no está instalada en este equipo", source != null)
            val fontsDirectory = File(directory, "fonts").apply { mkdirs() }
            source!!.first.copyTo(File(fontsDirectory, "arial.ttf"), overwrite = true)
            source.second.copyTo(File(fontsDirectory, "arialbd.ttf"), overwrite = true)
            val file = File(directory, "arial.pdf")
            file.outputStream().use { ReportPdfRenderer(fontsDirectory).write(sample(ReportPdfKind.DEBTORS), it) }
            Loader.loadPDF(file).use { document ->
                val page = document.getPage(0)
                val embedded = page.resources.fontNames.map { page.resources.getFont(it) }
                assertTrue(embedded.isNotEmpty() && embedded.all { it is PDType0Font && it.isEmbedded })
            }
            assertTrue(extractText(file).contains("María Fernández Núñez"))
            assertTrue(checkEveryPage(file) >= 1)
        }

    private fun checkEveryPage(file: File): Int {
        Loader.loadPDF(file).use { document ->
            val renderer = PDFRenderer(document)
            for (index in 0 until document.numberOfPages) {
                val box = document.getPage(index).mediaBox
                assertEquals(595f, box.width, 0f)
                assertEquals(842f, box.height, 0f)
                // 72 DPI: un píxel por punto, igual que el Bitmap 595x842 de la versión Android.
                val image = renderer.renderImageWithDPI(index, 72f, ImageType.RGB)
                assertEquals(595, image.width)
                assertEquals(842, image.height)
                var ink = 0
                for (y in 66..780 step 2) {
                    for (x in 36..558 step 2) if (image.getRGB(x, y) and 0xFFFFFF != 0xFFFFFF) ink++
                }
                assertTrue("Page ${index + 1} must contain body content", ink > 30)
            }
            return document.numberOfPages
        }
    }

    private fun extractText(file: File): String = Loader.loadPDF(file).use { PDFTextStripper().getText(it) }

    private fun sample(
        kind: ReportPdfKind,
        debtCount: Int = 12,
        empty: Boolean = false,
    ): PreparedReportPdf {
        val sales = if (empty || kind == ReportPdfKind.DEBTORS) emptyList() else (1..4).map(::sale)
        val debts =
            if (empty) {
                emptyList()
            } else {
                (1..debtCount).map { index ->
                    val currency = if (index % 4 == 0) USD else PEN
                    DebtSummary(
                        debtId = DebtId.from(UUID(15L, index.toLong())),
                        businessId = BUSINESS,
                        saleId = SaleId.from(UUID(16L, index.toLong())),
                        debtorName = if (index % 3 == 0) "José Ñahui y María del Carmen Quispe Apaza" else "María Fernández Núñez $index",
                        originalAmount = Money.ofMinor(12_000L + index, currency),
                        balance = Money.ofMinor(4_125L + index, currency),
                        status = DebtStatus.OPEN,
                        lineCount = 2,
                        dueAt = NOW.plusSeconds(86_400L * index),
                        version = 2L,
                        createdAt = NOW.minusSeconds(86_400L * index),
                        updatedAt = NOW,
                        paidAt = null,
                    )
                }
            }
        return PreparedReportPdf(
            kind = kind,
            snapshot =
                ReportPdfSnapshot(
                    businessId = BUSINESS,
                    businessName = "Comercial Demo - Datos ficticios para revisar el PDF",
                    generatedAt = NOW,
                    range = currentSalesReportRange(SalesReportPeriod.DAY, NOW, ZoneId.of("America/Lima")),
                    primaryCurrency = PEN,
                    sales = sales,
                    debts = debts,
                    saleDebtorNames = sales.filterIndexed { index, _ -> index % 2 == 0 }.associate { it.saleId to "María Fernández Núñez" },
                ),
            suggestedFileName = "demo.pdf",
        )
    }

    private fun sale(index: Int): RealizedSaleProfit {
        val currency = if (index == 2) USD else PEN
        val count = if (index == 3) 32 else 3
        val lines =
            (0 until count).map { position ->
                val charged = Money.ofMinor(1234L + position, currency)
                RealizedSaleLineProfit(
                    saleLineId = SaleLineId.from(UUID(index.toLong(), position + 1L)),
                    productId = ProductId.from(UUID(13L, position + 1L)),
                    position = position,
                    productName =
                        if (position % 4 == 0) {
                            "Café orgánico de altura, tostado y molido, presentación familiar con nombre largo ${index}_${position + 1}"
                        } else {
                            "Arroz extra nacional ${index}_${position + 1}"
                        },
                    unitCode = if (position % 2 == 0) "KGM" else "NIU",
                    locationName = "Almacén principal",
                    quantity = Quantity.of(if (position % 2 == 0) "0.125" else "1"),
                    totalCharged = charged,
                    netRevenue = charged,
                    historicalCost = ExactMonetaryAmount(BigDecimal.ZERO, currency),
                    grossProfit = ExactMonetaryAmount(charged.toMajor(), currency),
                )
            }
        val total = Money.ofMinor(lines.sumOf { it.totalCharged.minorUnits }, currency)
        return RealizedSaleProfit(
            saleId = SaleId.from(UUID(12L, index.toLong())),
            totalCharged = total,
            netRevenue = total,
            historicalCost = ExactMonetaryAmount(BigDecimal.ZERO, currency),
            grossProfit = ExactMonetaryAmount(total.toMajor(), currency),
            lines = lines,
            postedAt = NOW.minusSeconds(index * 1200L),
        )
    }

    private companion object {
        val BUSINESS = BusinessId.from(UUID(10L, 1L))
        val PEN = CurrencyCode.of("PEN")
        val USD = CurrencyCode.of("USD")
        val NOW: Instant = Instant.parse("2026-09-12T17:35:00Z")
    }
}
