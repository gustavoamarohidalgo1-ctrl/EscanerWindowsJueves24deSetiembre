package com.facturastock.app.data.export

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.provider.ProviderTestRule
import com.facturastock.app.core.coroutines.DefaultDispatcherProvider
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.PreparedReportPdf
import com.facturastock.app.domain.model.ReportPdfKind
import com.facturastock.app.domain.model.ReportPdfSnapshot
import com.facturastock.app.domain.model.ReportPdfWriteStatus
import com.facturastock.app.domain.model.SalesReportPeriod
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.usecase.currentSalesReportRange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AndroidReportPdfWriterTest {
    @get:Rule
    val providerRule: ProviderTestRule =
        ProviderTestRule
            .Builder(
                ReportPdfTestProvider::class.java,
                ReportPdfTestProvider.AUTHORITY,
            ).build()

    private lateinit var directory: File
    private lateinit var staging: File
    private lateinit var writer: AndroidReportPdfWriter
    private val uri = DocumentsContract.buildDocumentUri(ReportPdfTestProvider.AUTHORITY, ReportPdfTestProvider.DOCUMENT_ID)
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
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        directory = File(instrumentation.targetContext.cacheDir, "report-writer-test-${UUID.randomUUID()}")
        staging = File(directory, "staging").apply { check(mkdirs()) }
        val destination = File(directory, "destination").apply { check(mkdirs()) }
        ReportPdfTestProvider.reset(destination)
        val context = ReportPdfProviderContext(instrumentation.context, providerRule.resolver, staging)
        assertTrue("La URI debe ser reconocida como documento SAF", DocumentsContract.isDocumentUri(context, uri))
        writer = AndroidReportPdfWriter(context, DefaultDispatcherProvider())
    }

    @After
    fun tearDown() {
        ReportPdfTestProvider.releasePrimaryWrite.countDown()
        if (::directory.isInitialized) directory.deleteRecursively()
    }

    @Test
    fun invalidUriNeverOpensProviderOrLeavesStaging() =
        runBlocking {
            for (invalid in listOf("file:///sdcard/report.pdf", "https://example.invalid/report.pdf", "content:///report.pdf", "")) {
                assertEquals(ReportPdfWriteStatus.FAILED_DESTINATION_CLEAN, writer.write(invalid, prepared))
            }
            assertEquals(0, ReportPdfTestProvider.openCallCount)
            assertNoStaging()
        }

    @Test
    fun successfulWriteProducesReadablePdfAndRemovesPrivateStaging() =
        runBlocking {
            assertEquals(ReportPdfWriteStatus.WRITTEN, writer.write(uri.toString(), prepared))
            val file = checkNotNull(ReportPdfTestProvider.lastOutputFile)
            assertEquals(
                "%PDF-",
                file.inputStream().use { input ->
                    val header = ByteArray(5)
                    assertEquals(header.size, input.read(header))
                    String(header, Charsets.US_ASCII)
                },
            )
            PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { pdf ->
                assertTrue(pdf.pageCount >= 1)
                pdf.openPage(0).use { assertEquals(595, it.width) }
            }
            assertEquals(0, ReportPdfTestProvider.deleteDocumentCalls)
            assertNoStaging()
        }

    @Test
    fun partialWriteDeletesThroughDocumentsContractWithoutGenericDeleteOrReopen() =
        runBlocking {
            ReportPdfTestProvider.failPrimaryWrite = true
            ReportPdfTestProvider.failCleanupWrite = true

            assertEquals(ReportPdfWriteStatus.FAILED_DESTINATION_CLEAN, writer.write(uri.toString(), prepared))

            assertEquals(1, ReportPdfTestProvider.deleteDocumentCalls)
            assertEquals(0, ReportPdfTestProvider.genericDeleteCalls)
            assertEquals(1, ReportPdfTestProvider.openCallCount)
            assertFalse(checkNotNull(ReportPdfTestProvider.lastOutputFile).exists())
            assertNoStaging()
        }

    @Test
    fun rejectedDeleteFallsBackToVerifiedTruncation() =
        runBlocking {
            ReportPdfTestProvider.failPrimaryWrite = true
            ReportPdfTestProvider.deleteSucceeds = false

            assertEquals(ReportPdfWriteStatus.FAILED_DESTINATION_CLEAN, writer.write(uri.toString(), prepared))

            assertEquals(1, ReportPdfTestProvider.deleteDocumentCalls)
            assertEquals(0, ReportPdfTestProvider.genericDeleteCalls)
            assertEquals(3, ReportPdfTestProvider.openCallCount)
            assertEquals(0L, checkNotNull(ReportPdfTestProvider.lastOutputFile).length())
            assertNoStaging()
        }

    @Test
    fun nonDocumentContentUriUsesGenericCleanupAndVerifiedTruncation() =
        runBlocking {
            ReportPdfTestProvider.failPrimaryWrite = true
            val genericUri = "content://${ReportPdfTestProvider.AUTHORITY}/export.pdf"

            assertEquals(ReportPdfWriteStatus.FAILED_DESTINATION_CLEAN, writer.write(genericUri, prepared))

            assertEquals(0, ReportPdfTestProvider.deleteDocumentCalls)
            assertEquals(1, ReportPdfTestProvider.genericDeleteCalls)
            assertEquals(0L, checkNotNull(ReportPdfTestProvider.lastOutputFile).length())
            assertNoStaging()
        }

    @Test
    fun failedDeleteAndTruncationPreserveExplicitPartialStatus() =
        runBlocking {
            ReportPdfTestProvider.failPrimaryWrite = true
            ReportPdfTestProvider.deleteSucceeds = false
            ReportPdfTestProvider.failCleanupWrite = true

            assertEquals(ReportPdfWriteStatus.FAILED_DESTINATION_MAY_CONTAIN_PARTIAL_DATA, writer.write(uri.toString(), prepared))

            assertEquals(ReportPdfTestProvider.PARTIAL_CONTENT, checkNotNull(ReportPdfTestProvider.lastOutputFile).readText())
            assertEquals(1, ReportPdfTestProvider.deleteDocumentCalls)
            assertNoStaging()
        }

    @Test
    fun providerCancellationRethrowsOnlyAfterDocumentAndStagingAreClean() =
        runBlocking {
            ReportPdfTestProvider.cancelPrimaryWrite = true
            try {
                writer.write(uri.toString(), prepared)
                fail("La cancelación debe propagarse después de limpiar")
            } catch (_: CancellationException) {
                assertEquals(1, ReportPdfTestProvider.deleteDocumentCalls)
                assertFalse(checkNotNull(ReportPdfTestProvider.lastOutputFile).exists())
                assertNoStaging()
            }
        }

    @Test
    fun providerCancellationWithFailedCleanupReturnsPartialWarning() =
        runBlocking {
            ReportPdfTestProvider.cancelPrimaryWrite = true
            ReportPdfTestProvider.deleteSucceeds = false
            ReportPdfTestProvider.failCleanupWrite = true

            assertEquals(ReportPdfWriteStatus.FAILED_DESTINATION_MAY_CONTAIN_PARTIAL_DATA, writer.write(uri.toString(), prepared))

            assertEquals(ReportPdfTestProvider.PARTIAL_CONTENT, checkNotNull(ReportPdfTestProvider.lastOutputFile).readText())
            assertNoStaging()
        }

    @Test
    fun cancellingParentWhileProviderBlocksStillDeletesDocumentAndStaging() =
        runBlocking {
            ReportPdfTestProvider.blockPrimaryWrite = true
            val job = launch(start = CoroutineStart.UNDISPATCHED) { writer.write(uri.toString(), prepared) }
            try {
                assertTrue(ReportPdfTestProvider.primaryWriteEntered.await(10, TimeUnit.SECONDS))
                job.cancel(CancellationException("Se cerró la pantalla"))
            } finally {
                ReportPdfTestProvider.releasePrimaryWrite.countDown()
            }
            job.join()

            assertTrue(job.isCancelled)
            assertEquals(1, ReportPdfTestProvider.deleteDocumentCalls)
            assertEquals(0, ReportPdfTestProvider.genericDeleteCalls)
            assertFalse(checkNotNull(ReportPdfTestProvider.lastOutputFile).exists())
            assertNoStaging()
        }

    private fun assertNoStaging() {
        assertTrue("No deben quedar archivos privados de staging", checkNotNull(staging.listFiles()).isEmpty())
    }
}

private class ReportPdfProviderContext(
    base: Context,
    private val resolver: ContentResolver,
    private val stagingDirectory: File,
) : ContextWrapper(base) {
    override fun getContentResolver(): ContentResolver = resolver

    override fun getCacheDir(): File = stagingDirectory
}
