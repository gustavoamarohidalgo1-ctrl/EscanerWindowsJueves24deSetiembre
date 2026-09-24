package com.facturastock.app.data.export

import com.facturastock.app.core.coroutines.DefaultDispatcherProvider
import com.facturastock.app.core.platform.AppDirectories
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.PreparedReportPdf
import com.facturastock.app.domain.model.ReportPdfKind
import com.facturastock.app.domain.model.ReportPdfSnapshot
import com.facturastock.app.domain.model.ReportPdfWriteStatus
import com.facturastock.app.domain.model.SalesReportPeriod
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.usecase.currentSalesReportRange
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Escritor de PDF de escritorio: genera en la caché privada y copia al archivo elegido con
 * "Guardar como". Sustituye a las pruebas del proveedor SAF de Android: el destino es un
 * `file:` real y los fallos se provocan con permisos del sistema de archivos.
 */
class DesktopReportPdfWriterTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var directories: AppDirectories
    private lateinit var staging: File
    private lateinit var destinationDirectory: File
    private lateinit var destination: File
    private lateinit var writer: DesktopReportPdfWriter
    private val lockedFiles = mutableListOf<File>()
    private val now = Instant.parse("2026-09-12T16:00:00Z")
    private val prepared =
        PreparedReportPdf(
            kind = ReportPdfKind.DEBTORS,
            snapshot =
                ReportPdfSnapshot(
                    businessId = BusinessId.from(UUID(0L, 1L)),
                    businessName = "Negocio PDF QA",
                    generatedAt = now,
                    range = currentSalesReportRange(SalesReportPeriod.DAY, now, ZoneId.of("America/Lima")),
                    primaryCurrency = CurrencyCode.of("PEN"),
                    sales = emptyList(),
                    debts = emptyList(),
                    saleDebtorNames = emptyMap(),
                ),
            suggestedFileName = "deudores.pdf",
        )

    @Before
    fun setUp() {
        directories = AppDirectories(tempFolder.newFolder("app").canonicalFile)
        staging = directories.cacheDir
        destinationDirectory = tempFolder.newFolder("destination").canonicalFile
        destination = File(destinationDirectory, "deudores.pdf")
        writer = DesktopReportPdfWriter(directories, DefaultDispatcherProvider())
    }

    @After
    fun tearDown() {
        // Restituye permisos para que TemporaryFolder pueda borrar todo.
        lockedFiles.forEach { it.setWritable(true, false) }
    }

    @Test
    fun invalidUriNeverTouchesADestinationOrLeavesStaging() =
        runBlocking {
            val missingParent = File(destinationDirectory, "no-existe/report.pdf").toURI().toString()
            val directoryUri = destinationDirectory.toURI().toString()
            for (
                invalid in listOf(
                    "https://example.invalid/report.pdf",
                    "content://com.facturastock.app/report.pdf",
                    "report.pdf",
                    "",
                    missingParent,
                    directoryUri,
                )
            ) {
                assertEquals(invalid, ReportPdfWriteStatus.FAILED_DESTINATION_CLEAN, writer.write(invalid, prepared))
            }
            assertEquals(emptyList<String>(), destinationDirectory.list().orEmpty().toList())
            assertNoStaging()
        }

    @Test
    fun successfulWriteProducesReadablePdfAndRemovesPrivateStaging() =
        runBlocking {
            assertEquals(ReportPdfWriteStatus.WRITTEN, writer.write(destination.toURI().toString(), prepared))
            assertEquals(
                "%PDF-",
                destination.inputStream().use { input ->
                    val header = ByteArray(5)
                    assertEquals(header.size, input.read(header))
                    String(header, Charsets.US_ASCII)
                },
            )
            Loader.loadPDF(destination).use { pdf ->
                assertTrue(pdf.numberOfPages >= 1)
                assertEquals(595, pdf.getPage(0).mediaBox.width.toInt())
                assertTrue(PDFTextStripper().getText(pdf).contains("Negocio PDF QA"))
            }
            assertNoStaging()
        }

    @Test
    fun successfulWriteReplacesAPreviousDestinationCompletely() =
        runBlocking {
            destination.writeText("contenido anterior mucho más largo que la cabecera del PDF nuevo ".repeat(4_000))

            assertEquals(ReportPdfWriteStatus.WRITTEN, writer.write(destination.toURI().toString(), prepared))

            Loader.loadPDF(destination).use { pdf -> assertTrue(pdf.numberOfPages >= 1) }
            assertNoStaging()
        }

    /**
     * Equivalente del borrado rechazado por el proveedor: la carpeta de destino no admite
     * cambios de entradas, así que la copia falla y tampoco puede borrarse el archivo; el
     * truncado verificado deja el destino vacío y limpio.
     */
    @Test
    fun rejectedDeleteFallsBackToVerifiedTruncation() =
        runBlocking {
            destination.writeText(PARTIAL_CONTENT)
            lockDirectoryEntries(destinationDirectory)

            assertEquals(
                ReportPdfWriteStatus.FAILED_DESTINATION_CLEAN,
                writer.write(destination.toURI().toString(), prepared),
            )

            assertTrue(destination.exists())
            assertEquals(0L, destination.length())
            assertNoStaging()
        }

    @Test
    fun failedDeleteAndTruncationPreserveExplicitPartialStatus() =
        runBlocking {
            destination.writeText(PARTIAL_CONTENT)
            lockFile(destination)
            lockDirectoryEntries(destinationDirectory)

            assertEquals(
                ReportPdfWriteStatus.FAILED_DESTINATION_MAY_CONTAIN_PARTIAL_DATA,
                writer.write(destination.toURI().toString(), prepared),
            )

            assertEquals(PARTIAL_CONTENT, destination.readText())
            assertNoStaging()
        }

    /** La cancelación antes de copiar se propaga sin tocar el destino y sin dejar staging. */
    @Test
    fun callerCancellationRethrowsWithoutTouchingTheDestinationOrStaging() =
        runBlocking {
            destination.writeText(PARTIAL_CONTENT)
            var failure: Throwable? = null
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel(CancellationException("Se cerró la pantalla"))
                failure = runCatching { writer.write(destination.toURI().toString(), prepared) }
                    .exceptionOrNull()
            }
            job.join()

            assertTrue(job.isCancelled)
            assertTrue(failure is CancellationException)
            assertEquals(PARTIAL_CONTENT, destination.readText())
            assertNoStaging()
        }

    @Test
    fun cleanupDeletesAPartialDocumentAndTreatsAnAbsentOneAsClean() {
        val partial = File(destinationDirectory, "parcial.pdf").apply { writeText(PARTIAL_CONTENT) }

        assertTrue(cleanupPartialDocument(partial))
        assertFalse(partial.exists())
        assertTrue(cleanupPartialDocument(partial))
    }

    private fun lockFile(file: File) {
        lockedFiles += file
        file.setWritable(false, false)
        assumeTrue("El sistema no permite proteger el archivo contra escritura", !file.canWrite())
    }

    /** Sin permiso de escritura en la carpeta no se pueden crear, borrar ni renombrar entradas. */
    private fun lockDirectoryEntries(directory: File) {
        lockedFiles += directory
        directory.setWritable(false, false)
        val probe = File(directory, "probe-${UUID.randomUUID()}")
        val canStillCreate = runCatching { probe.createNewFile() }.getOrDefault(false)
        if (canStillCreate) probe.delete()
        assumeTrue("El sistema (o root) ignora la protección de la carpeta", !canStillCreate)
    }

    private fun assertNoStaging() {
        assertTrue("No deben quedar archivos privados de staging", checkNotNull(staging.listFiles()).isEmpty())
    }

    private companion object {
        const val PARTIAL_CONTENT = "%PDF-parcial"
    }
}
