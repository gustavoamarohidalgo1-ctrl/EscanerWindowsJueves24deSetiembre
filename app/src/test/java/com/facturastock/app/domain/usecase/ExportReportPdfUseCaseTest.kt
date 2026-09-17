package com.facturastock.app.domain.usecase

import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.PreparedReportPdf
import com.facturastock.app.domain.model.ReportPdfKind
import com.facturastock.app.domain.model.ReportPdfPreparation
import com.facturastock.app.domain.model.ReportPdfSnapshot
import com.facturastock.app.domain.model.ReportPdfWriteStatus
import com.facturastock.app.domain.model.SalesReportRange
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.ReportPdfRepository
import com.facturastock.app.domain.repository.ReportPdfWriter
import com.facturastock.app.testing.FakeAppConfigurationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class ExportReportPdfUseCaseTest {
    private var context = AppConfiguration.defaults().copy(businessId = BUSINESS)
    private var now = Instant.parse("2026-09-12T22:30:00Z")
    private var reads = 0
    private var writes = 0
    private var capturedKind: ReportPdfKind? = null
    private var written: PreparedReportPdf? = null
    private var onRead: (ReportPdfSnapshot) -> ReportPdfSnapshot? = { it }
    private var onWrite: () -> ReportPdfWriteStatus = { ReportPdfWriteStatus.WRITTEN }
    private var onConfigurationRead: () -> AppConfiguration = { context }
    private val configuration =
        object : AppConfigurationRepository by FakeAppConfigurationRepository() {
            override suspend fun current(): AppConfiguration = onConfigurationRead()
        }
    private val repository =
        object : ReportPdfRepository {
            override suspend fun readSnapshot(
                businessId: BusinessId,
                range: SalesReportRange,
                generatedAt: Instant,
                primaryCurrency: CurrencyCode,
                kind: ReportPdfKind,
            ): ReportPdfSnapshot? {
                reads++
                capturedKind = kind
                return onRead(
                    ReportPdfSnapshot(
                        businessId,
                        "Tienda",
                        generatedAt,
                        range,
                        primaryCurrency,
                        emptyList(),
                        emptyList(),
                        emptyMap(),
                    ),
                )
            }
        }
    private val writer =
        object : ReportPdfWriter {
            override suspend fun write(
                documentUri: String,
                prepared: PreparedReportPdf,
            ): ReportPdfWriteStatus {
                assertEquals(URI, documentUri)
                writes++
                written = prepared
                return onWrite()
            }
        }
    private val useCase = ExportReportPdfUseCase(configuration, repository, writer, AppClock { now })

    @Test
    fun `prepare captures current calendar day including daylight saving and kind`() =
        runTest {
            context = context.copy(zoneId = ZoneId.of("America/New_York"))
            now = Instant.parse("2026-03-08T17:00:00Z")
            val prepared = ready(ReportPdfKind.DAILY_SALES_WITH_DEBTORS)

            assertEquals(now, prepared.snapshot.generatedAt)
            assertEquals(BUSINESS, prepared.snapshot.businessId)
            assertEquals(Instant.parse("2026-03-08T05:00:00Z"), prepared.snapshot.range.startInclusive)
            assertEquals(Instant.parse("2026-03-09T04:00:00Z"), prepared.snapshot.range.endExclusive)
            assertEquals(
                Duration.ofHours(23),
                Duration.between(
                    prepared.snapshot.range.startInclusive,
                    prepared.snapshot.range.endExclusive,
                ),
            )
            assertEquals("ventas-del-dia-2026-03-08.pdf", prepared.suggestedFileName)
            assertEquals(ReportPdfKind.DAILY_SALES_WITH_DEBTORS, capturedKind)
            assertEquals(0, writes)
        }

    @Test
    fun `debtors use their own filename and active demo business`() =
        runTest {
            context = context.copy(demoBusinessId = OTHER_BUSINESS)
            val prepared = ready(ReportPdfKind.DEBTORS)
            assertEquals(OTHER_BUSINESS, prepared.snapshot.businessId)
            assertEquals("deudores-pendientes-2026-09-12.pdf", prepared.suggestedFileName)
            assertEquals(ReportPdfKind.DEBTORS, capturedKind)
        }

    @Test
    fun `missing active business avoids repository and absent stored business returns no business`() =
        runTest {
            context = context.copy(businessId = null)
            assertEquals(ReportPdfPreparation.NoActiveBusiness, useCase.prepare(ReportPdfKind.DEBTORS))
            assertEquals(0, reads)
            context = context.copy(businessId = BUSINESS)
            onRead = { null }
            assertEquals(ReportPdfPreparation.NoActiveBusiness, useCase.prepare(ReportPdfKind.DEBTORS))
        }

    @Test
    fun `prepare rejects business zone or currency change during snapshot read`() =
        runTest {
            val initial = context
            listOf(
                initial.copy(businessId = OTHER_BUSINESS),
                initial.copy(zoneId = ZoneId.of("UTC")),
                initial.copy(currency = CurrencyCode.of("USD")),
            ).forEach { changed ->
                context = initial
                onRead = { snapshot ->
                    context = changed
                    snapshot
                }
                assertEquals(ReportPdfPreparation.ContextChanged, useCase.prepare(ReportPdfKind.DEBTORS))
            }
            assertEquals(0, writes)
        }

    @Test
    fun `prepare rejects a repository snapshot from another business or interval`() =
        runTest {
            onRead = { it.copy(businessId = OTHER_BUSINESS) }
            assertEquals(ReportPdfPreparation.Failed, useCase.prepare(ReportPdfKind.DEBTORS))
            onRead = { it.copy(generatedAt = it.generatedAt.plusSeconds(1)) }
            assertEquals(ReportPdfPreparation.Failed, useCase.prepare(ReportPdfKind.DEBTORS))
            onRead = { it.copy(range = it.range.copy(endExclusive = it.range.endExclusive.plusSeconds(1))) }
            assertEquals(ReportPdfPreparation.Failed, useCase.prepare(ReportPdfKind.DEBTORS))
        }

    @Test
    fun `prepare reports ordinary failure but propagates cancellation`() =
        runTest {
            onRead = { throw IOException("read failed") }
            assertEquals(ReportPdfPreparation.Failed, useCase.prepare(ReportPdfKind.DEBTORS))
            val cancellation = CancellationException("cancelled read")
            onRead = { throw cancellation }
            assertSame(cancellation, cancellationFrom { useCase.prepare(ReportPdfKind.DEBTORS) })
            onConfigurationRead = { throw cancellation }
            assertSame(cancellation, cancellationFrom { useCase.prepare(ReportPdfKind.DEBTORS) })
        }

    @Test
    fun `write reuses prepared snapshot even when destination selector crosses midnight`() =
        runTest {
            val prepared = ready()
            now = now.plusSeconds(86_400)
            assertEquals(ReportPdfWriteStatus.WRITTEN, useCase.write(URI, prepared))
            assertSame(prepared, written)
            assertEquals(1, reads)
            assertEquals(1, writes)
            assertEquals("ventas-del-dia-2026-09-12.pdf", written?.suggestedFileName)
        }

    @Test
    fun `write rejects changed context before opening destination`() =
        runTest {
            val prepared = ready()
            val initial = context
            listOf(
                initial.copy(businessId = OTHER_BUSINESS),
                initial.copy(businessId = null),
                initial.copy(zoneId = ZoneId.of("UTC")),
                initial.copy(currency = CurrencyCode.of("USD")),
            ).forEach { changed ->
                context = changed
                assertEquals(ReportPdfWriteStatus.CONTEXT_CHANGED, useCase.write(URI, prepared))
            }
            assertEquals(0, writes)
        }

    @Test
    fun `write keeps destination status and handles failures conservatively`() =
        runTest {
            val prepared = ready()
            ReportPdfWriteStatus.entries.forEach { result ->
                onWrite = { result }
                assertEquals(result, useCase.write(URI, prepared))
            }
            onWrite = { throw IOException("after opening destination") }
            assertEquals(ReportPdfWriteStatus.FAILED_DESTINATION_MAY_CONTAIN_PARTIAL_DATA, useCase.write(URI, prepared))
            val writeCount = writes
            assertEquals(ReportPdfWriteStatus.FAILED_DESTINATION_CLEAN, useCase.write(" ", prepared))
            onConfigurationRead = { throw IOException("preferences unavailable") }
            assertEquals(ReportPdfWriteStatus.FAILED_DESTINATION_CLEAN, useCase.write(URI, prepared))
            assertEquals(writeCount, writes)
        }

    @Test
    fun `write cancellation from configuration and destination propagates`() =
        runTest {
            val prepared = ready()
            val cancellation = CancellationException("cancelled write")
            onWrite = { throw cancellation }
            assertSame(cancellation, cancellationFrom { useCase.write(URI, prepared) })
            onConfigurationRead = { throw cancellation }
            assertSame(cancellation, cancellationFrom { useCase.write(URI, prepared) })
            assertEquals(1, writes)
        }

    private suspend fun ready(kind: ReportPdfKind = ReportPdfKind.DAILY_SALES_WITH_DEBTORS): PreparedReportPdf {
        val result = useCase.prepare(kind)
        assertTrue(result is ReportPdfPreparation.Ready)
        return (result as ReportPdfPreparation.Ready).prepared
    }

    private suspend fun cancellationFrom(action: suspend () -> Any): CancellationException =
        try {
            action()
            throw AssertionError("Expected cancellation")
        } catch (cancelled: CancellationException) {
            cancelled
        }

    private companion object {
        val BUSINESS = BusinessId.from(UUID(0L, 1L))
        val OTHER_BUSINESS = BusinessId.from(UUID(0L, 2L))
        const val URI = "content://test/report.pdf"
    }
}
