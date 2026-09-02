package com.facturastock.app.data.export

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.provider.ProviderTestRule
import com.facturastock.app.core.coroutines.DefaultDispatcherProvider
import com.facturastock.app.domain.model.UserDataExport
import com.facturastock.app.domain.model.UserDataExportWriteStatus
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContentResolverUserDataExportWriterTest {
    @get:Rule
    val providerRule: ProviderTestRule = ProviderTestRule.Builder(
        ExportTestProvider::class.java,
        TEST_AUTHORITY,
    ).build()

    private lateinit var writer: ContentResolverUserDataExportWriter
    private val export = UserDataExport(
        exportedAt = Instant.parse("2026-08-21T12:00:00Z"),
        business = null,
        products = emptyList(),
        suppliers = emptyList(),
        units = emptyList(),
        inventoryLocations = emptyList(),
        supplierProductAliases = emptyList(),
        purchases = emptyList(),
        inventoryBalances = emptyList(),
        stockMovements = emptyList(),
        auditEvents = emptyList(),
    )

    @Before
    fun resetProvider() {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        ExportTestProvider.outputDirectory =
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        ExportTestProvider.lastOutputFile?.delete()
        ExportTestProvider.lastOutputFile = null
        ExportTestProvider.openCallCount = 0
        ExportTestProvider.lastOpenMode = null
        ExportTestProvider.deleteCallCount = 0
        ExportTestProvider.deleteSucceeds = false
        ExportTestProvider.failPrimaryWrite = false
        ExportTestProvider.failCleanupWrite = false
        ExportTestProvider.cancelPrimaryWrite = false
        ExportTestProvider.blockPrimaryWrite = false
        ExportTestProvider.primaryWriteEntered = CountDownLatch(1)
        ExportTestProvider.releasePrimaryWrite = CountDownLatch(1)
        writer = ContentResolverUserDataExportWriter(
            context = ProviderResolverContext(testContext, providerRule.resolver),
            dispatchers = DefaultDispatcherProvider(),
        )
    }

    @Test
    fun rejectsFileUriAndUnknownContentProviderWithoutClaimingSuccess() {
        runBlocking {
            assertEquals(
                UserDataExportWriteStatus.FAILED_DESTINATION_CLEAN,
                writer.write("file:///sdcard/export.json", export),
            )
            assertEquals(
                UserDataExportWriteStatus.FAILED_DESTINATION_MAY_CONTAIN_PARTIAL_DATA,
                writer.write("content://not-installed/export.json", export),
            )
        }
    }

    @Test
    fun writesAndClosesTheExactJsonThroughContentResolver() {
        runBlocking {
            ExportTestProvider.openCallCount = 0
            ExportTestProvider.lastOpenMode = null
            val written = writer.write(
                "content://$TEST_AUTHORITY/export.json",
                export,
            )

            assertEquals(
                "written=$written openCalls=${ExportTestProvider.openCallCount} " +
                    "mode=${ExportTestProvider.lastOpenMode}",
                UserDataExportWriteStatus.WRITTEN,
                written,
            )
            val file = requireNotNull(ExportTestProvider.lastOutputFile)
            assertEquals(UserDataExportJson.serialize(export), file.readText(Charsets.UTF_8))
            file.delete()
        }
    }

    @Test
    fun doubleFailureLeavesExplicitPartialWarningInsteadOfClaimingCleanup() {
        runBlocking {
            ExportTestProvider.failPrimaryWrite = true
            ExportTestProvider.failCleanupWrite = true

            val result = writer.write("content://$TEST_AUTHORITY/export.json", export)

            assertEquals(
                UserDataExportWriteStatus.FAILED_DESTINATION_MAY_CONTAIN_PARTIAL_DATA,
                result,
            )
            assertEquals(1, ExportTestProvider.deleteCallCount)
            val file = requireNotNull(ExportTestProvider.lastOutputFile)
            assertEquals("{\"partial\":", file.readText(Charsets.UTF_8))
            file.delete()
        }
    }

    @Test
    fun failedWriteReportsCleanOnlyAfterVerifiedTruncation() {
        runBlocking {
            ExportTestProvider.failPrimaryWrite = true

            val result = writer.write("content://$TEST_AUTHORITY/export.json", export)

            assertEquals(UserDataExportWriteStatus.FAILED_DESTINATION_CLEAN, result)
            assertEquals(1, ExportTestProvider.deleteCallCount)
            assertEquals(0L, requireNotNull(ExportTestProvider.lastOutputFile).length())
        }
    }

    @Test
    fun cancellationCannotHideAnUncleanPartialDestination() {
        runBlocking {
            ExportTestProvider.cancelPrimaryWrite = true
            ExportTestProvider.failCleanupWrite = true

            val result = writer.write("content://$TEST_AUTHORITY/export.json", export)

            assertEquals(
                UserDataExportWriteStatus.FAILED_DESTINATION_MAY_CONTAIN_PARTIAL_DATA,
                result,
            )
            assertEquals(1, ExportTestProvider.deleteCallCount)
            assertEquals(
                "{\"partial\":",
                requireNotNull(ExportTestProvider.lastOutputFile).readText(),
            )
        }
    }

    @Test
    fun promptParentCancellationAfterBlockingProviderStillCleansTheTouchedDestination() {
        runBlocking {
            ExportTestProvider.blockPrimaryWrite = true
            // Arranca hasta el primer punto de suspensión antes de bloquear este hilo con el
            // latch. Sin UNDISPATCHED, la coroutine hija queda encolada en el event loop de
            // runBlocking y el test puede expirar sin que el provider llegue a ejecutarse.
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                writer.write("content://$TEST_AUTHORITY/export.json", export)
            }
            assertEquals(
                true,
                ExportTestProvider.primaryWriteEntered.await(5, TimeUnit.SECONDS),
            )

            job.cancel(CancellationException("usuario cerro la pantalla"))
            ExportTestProvider.releasePrimaryWrite.countDown()
            job.join()

            assertEquals(true, job.isCancelled)
            assertEquals(1, ExportTestProvider.deleteCallCount)
            assertEquals(0L, requireNotNull(ExportTestProvider.lastOutputFile).length())
            // Apertura primaria + truncado de recuperación + reapertura de solo lectura para
            // verificar de forma explícita que el proveedor dejó longitud cero.
            assertEquals(3, ExportTestProvider.openCallCount)
        }
    }

    private companion object {
        const val TEST_AUTHORITY = "com.facturastock.app.export-test"
    }
}

private class ProviderResolverContext(
    base: Context,
    private val resolver: ContentResolver,
) : ContextWrapper(base) {
    override fun getContentResolver(): ContentResolver = resolver
}
