package com.facturastock.app.feature.reports

import androidx.lifecycle.viewModelScope
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.ReportPdfKind
import com.facturastock.app.domain.model.ReportPdfWriteStatus
import com.facturastock.app.domain.model.SalesReportPeriod
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.SaleId
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.SaleVoidPreview
import com.facturastock.app.domain.repository.SaleVoidPreviewResult
import com.facturastock.app.domain.repository.SaleVoidRepository
import com.facturastock.app.domain.repository.SaleVoidResult
import com.facturastock.app.domain.usecase.ExportReportPdfUseCase
import com.facturastock.app.domain.usecase.ObserveSalesReportUseCase
import com.facturastock.app.domain.usecase.VoidSaleUseCase
import com.facturastock.app.testing.FakeAppConfigurationRepository
import com.facturastock.app.testing.FakeDebtPaymentReportRepository
import com.facturastock.app.testing.FakeSaleRepository
import com.facturastock.app.testing.MainDispatcherRule
import com.facturastock.app.testing.TestDispatcherProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class ReportsPdfViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private var now = Instant.parse("2026-09-12T20:00:00Z")
    private val clock = AppClock { now }
    private val config = MutableStateFlow(AppConfiguration.defaults().copy(onboardingCompleted = true, businessId = BUSINESS_ID))
    private val configuration =
        object : AppConfigurationRepository by FakeAppConfigurationRepository() {
            override fun observe() = config

            override suspend fun current() = config.value
        }
    private val pdfs = FakeReportsPdfPorts()
    private val voids =
        object : SaleVoidRepository {
            override suspend fun preview(
                businessId: BusinessId,
                saleId: SaleId,
            ) = SaleVoidPreviewResult.NotFound

            override suspend fun confirm(preview: SaleVoidPreview) = SaleVoidResult.NotFound
        }

    @Test
    fun `no active business cannot launch exports or debtors`() =
        runTest(mainDispatcherRule.dispatcher) {
            config.value = AppConfiguration.defaults()
            withViewModel { vm, effects ->
                assertFalse(vm.uiState.value.canExportPdf)
                vm.onAction(ReportsContract.Action.ExportPdfRequested(ReportPdfKind.DEBTORS))
                vm.onAction(ReportsContract.Action.OpenDebtors)
                runCurrent()
                assertTrue(pdfs.reads.isEmpty())
                assertTrue(pdfs.writes.isEmpty())
                assertTrue(effects.isEmpty())
            }
        }

    @Test
    fun `rapid taps reserve one preparation and launch only after its snapshot is ready`() =
        runTest(mainDispatcherRule.dispatcher) {
            pdfs.preparationGate = CompletableDeferred()
            withViewModel { vm, effects ->
                repeat(2) { vm.onAction(ReportsContract.Action.ExportPdfRequested(ReportPdfKind.DAILY_SALES_WITH_DEBTORS)) }
                runCurrent()
                assertEquals(1, pdfs.reads.size)
                assertEquals(ReportsContract.PdfStage.PREPARING, vm.uiState.value.pdfStage)
                assertFalse(vm.uiState.value.canExportPdf)
                assertTrue(effects.isEmpty())
                pdfs.preparationGate!!.complete(Unit)
                runCurrent()
                assertEquals(ReportsContract.PdfStage.CHOOSING_DESTINATION, vm.uiState.value.pdfStage)
                val request = effects.single() as ReportsContract.Effect.CreatePdfDocument
                assertEquals("ventas-del-dia-2026-09-12.pdf", request.suggestedFileName)
                assertTrue(vm.claimPdfDestination(request.requestId))
                assertFalse(vm.claimPdfDestination(request.requestId))
                assertTrue(pdfs.writes.isEmpty())
            }
        }

    @Test
    fun `week and month selectors still prepare today and preserve the original snapshot across midnight`() =
        runTest(mainDispatcherRule.dispatcher) {
            withViewModel { vm, effects ->
                for (period in listOf(SalesReportPeriod.WEEK, SalesReportPeriod.MONTH)) {
                    vm.onAction(ReportsContract.Action.PeriodSelected(period))
                    runCurrent()
                    val capturedAt = now
                    val request = requestPdf(vm, effects, ReportPdfKind.DAILY_SALES_WITH_DEBTORS)
                    val snapshot = pdfs.reads.last().second
                    assertEquals(period, vm.uiState.value.selectedPeriod)
                    assertEquals(SalesReportPeriod.DAY, snapshot.range.period)
                    assertEquals(capturedAt, snapshot.generatedAt)
                    assertEquals(
                        capturedAt.atZone(config.value.zoneId).toLocalDate(),
                        snapshot.range.startInclusive
                            .atZone(snapshot.range.zoneId)
                            .toLocalDate(),
                    )
                    now = now.plusSeconds(86_400)
                    pdfs.businessName = "Negocio después del selector"
                    vm.onAction(ReportsContract.Action.Resumed)
                    runCurrent()
                    assertTrue(vm.claimPdfDestination(request.requestId))
                    vm.onAction(ReportsContract.Action.PdfDestinationSelected(request.requestId, URI))
                    runCurrent()
                    assertTrue(vm.uiState.value.pdfSaved)
                    assertSame(
                        snapshot,
                        pdfs.writes
                            .last()
                            .second.snapshot,
                    )
                }
                assertEquals(2, pdfs.reads.size)
                assertEquals(2, pdfs.writes.size)
            }
        }

    @Test
    fun `cancelling the destination silently releases the snapshot and allows a new request`() =
        runTest(mainDispatcherRule.dispatcher) {
            withViewModel { vm, effects ->
                val request = requestPdf(vm, effects)
                assertTrue(vm.claimPdfDestination(request.requestId))
                vm.onAction(ReportsContract.Action.PdfDestinationSelected(request.requestId, null))
                runCurrent()
                assertEquals(ReportsContract.PdfStage.IDLE, vm.uiState.value.pdfStage)
                assertNull(vm.uiState.value.pdfFailure)
                assertFalse(vm.uiState.value.pdfSaved)
                assertTrue(vm.uiState.value.canExportPdf)
                assertTrue(pdfs.writes.isEmpty())
                val retry = requestPdf(vm, effects)
                assertTrue(retry.requestId != request.requestId)
                assertEquals(2, pdfs.reads.size)
            }
        }

    @Test
    fun `write is committed once and opening the saved file requires a separate action`() =
        runTest(mainDispatcherRule.dispatcher) {
            pdfs.writeGate = CompletableDeferred()
            withViewModel { vm, effects ->
                val request = requestPdf(vm, effects)
                assertTrue(vm.claimPdfDestination(request.requestId))
                repeat(2) { vm.onAction(ReportsContract.Action.PdfDestinationSelected(request.requestId, URI)) }
                runCurrent()
                vm.onAction(ReportsContract.Action.ExportPdfRequested(ReportPdfKind.DEBTORS))
                vm.onAction(ReportsContract.Action.OpenSavedPdf)
                runCurrent()
                assertEquals(1, pdfs.reads.size)
                assertEquals(1, pdfs.writes.size)
                assertEquals(ReportsContract.PdfStage.WRITING, vm.uiState.value.pdfStage)
                assertFalse(vm.uiState.value.pdfSaved)
                assertEquals(1, effects.size)
                pdfs.writeGate!!.complete(Unit)
                runCurrent()
                assertTrue(vm.uiState.value.pdfSaved)
                assertEquals(1, effects.size)
                vm.onAction(ReportsContract.Action.OpenSavedPdf)
                runCurrent()
                assertEquals(ReportsContract.Effect.OpenPdf(URI), effects.last())
                vm.onAction(ReportsContract.Action.PdfViewerUnavailable)
                runCurrent()
                assertTrue(vm.uiState.value.pdfSaved)
                assertTrue(vm.uiState.value.pdfViewerUnavailable)
                assertNull(vm.uiState.value.pdfFailure)
                vm.onAction(ReportsContract.Action.PdfNoticeDismissed)
                runCurrent()
                assertFalse(vm.uiState.value.pdfSaved)
                assertFalse(vm.uiState.value.pdfViewerUnavailable)
            }
        }

    @Test
    fun `a rotated collector does not launch again and the retained snapshot handles its result`() =
        runTest(mainDispatcherRule.dispatcher) {
            val vm = viewModel()
            try {
                runCurrent()
                val beforeRotation = mutableListOf<ReportsContract.Effect>()
                val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { beforeRotation += it } }
                val request = requestPdf(vm, beforeRotation)
                assertTrue(vm.claimPdfDestination(request.requestId))
                collector.cancel()
                val afterRotation = mutableListOf<ReportsContract.Effect>()
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { afterRotation += it } }
                vm.onAction(ReportsContract.Action.Resumed)
                runCurrent()
                assertTrue(afterRotation.isEmpty())
                assertFalse(vm.claimPdfDestination(request.requestId))
                vm.onAction(ReportsContract.Action.PdfDestinationSelected(request.requestId, URI))
                runCurrent()
                assertTrue(vm.uiState.value.pdfSaved)
                assertEquals(1, pdfs.writes.size)
            } finally {
                vm.viewModelScope.cancel()
            }
        }

    @Test
    fun `a restored destination after process death never prepares replacement data`() =
        runTest(mainDispatcherRule.dispatcher) {
            withViewModel { vm, _ ->
                vm.onAction(ReportsContract.Action.PdfDestinationSelected("prior-process-request", URI))
                runCurrent()
                assertEquals(ReportsContract.PdfFailure.INTERRUPTED, vm.uiState.value.pdfFailure)
                assertTrue(vm.uiState.value.canExportPdf)
                assertTrue(pdfs.reads.isEmpty())
                assertTrue(pdfs.writes.isEmpty())
            }
        }

    @Test
    fun `business currency or zone changes invalidate an outstanding selector and ignore its late result`() =
        runTest(mainDispatcherRule.dispatcher) {
            withViewModel { vm, effects ->
                val changes: List<(AppConfiguration) -> AppConfiguration> =
                    listOf(
                        { it.copy(businessId = BusinessId.from(UUID(9L, 2L))) },
                        { it.copy(currency = CurrencyCode.of("USD")) },
                        { it.copy(zoneId = ZoneId.of("UTC")) },
                    )
                changes.forEach { change ->
                    val request = requestPdf(vm, effects)
                    assertTrue(vm.claimPdfDestination(request.requestId))
                    config.value = change(config.value)
                    runCurrent()
                    assertEquals(ReportsContract.PdfFailure.CONTEXT_CHANGED, vm.uiState.value.pdfFailure)
                    assertFalse(vm.uiState.value.canExportPdf)
                    val count = pdfs.reads.size
                    vm.onAction(ReportsContract.Action.ExportPdfRequested(ReportPdfKind.DEBTORS))
                    runCurrent()
                    assertEquals(count, pdfs.reads.size)
                    vm.onAction(ReportsContract.Action.PdfDestinationSelected(request.requestId, URI))
                    runCurrent()
                    assertEquals(ReportsContract.PdfStage.IDLE, vm.uiState.value.pdfStage)
                    assertEquals(ReportsContract.PdfFailure.CONTEXT_CHANGED, vm.uiState.value.pdfFailure)
                    assertTrue(pdfs.writes.isEmpty())
                }
            }
        }

    @Test
    fun `business changes during preparation cancel the old snapshot without launching it`() =
        runTest(mainDispatcherRule.dispatcher) {
            pdfs.preparationGate = CompletableDeferred()
            withViewModel { vm, effects ->
                vm.onAction(ReportsContract.Action.ExportPdfRequested(ReportPdfKind.DEBTORS))
                runCurrent()
                config.value = config.value.copy(businessId = BusinessId.from(UUID(9L, 2L)))
                runCurrent()
                pdfs.preparationGate!!.complete(Unit)
                runCurrent()
                assertEquals(ReportsContract.PdfFailure.CONTEXT_CHANGED, vm.uiState.value.pdfFailure)
                assertEquals(ReportsContract.PdfStage.IDLE, vm.uiState.value.pdfStage)
                assertTrue(effects.isEmpty())
                assertTrue(pdfs.writes.isEmpty())
                requestPdf(vm, effects)
                assertEquals(2, pdfs.reads.size)
            }
        }

    @Test
    fun `a buffered effect from a previous business cannot claim the document launcher`() =
        runTest(mainDispatcherRule.dispatcher) {
            withViewModel { vm, effects ->
                val request = requestPdf(vm, effects)
                config.value = AppConfiguration.defaults()
                runCurrent()
                assertFalse(vm.claimPdfDestination(request.requestId))
                assertEquals(ReportsContract.PdfFailure.CONTEXT_CHANGED, vm.uiState.value.pdfFailure)
                assertTrue(pdfs.writes.isEmpty())
            }
        }

    @Test
    fun `preparation and launcher failures release the request for an explicit retry`() =
        runTest(mainDispatcherRule.dispatcher) {
            withViewModel { vm, effects ->
                pdfs.preparationFailure = IOException("read failed")
                vm.onAction(ReportsContract.Action.ExportPdfRequested(ReportPdfKind.DEBTORS))
                runCurrent()
                assertEquals(ReportsContract.PdfFailure.PREPARATION_FAILED, vm.uiState.value.pdfFailure)
                assertTrue(vm.uiState.value.canExportPdf)
                assertTrue(effects.isEmpty())
                pdfs.preparationFailure = null
                pdfs.missingBusiness = true
                vm.onAction(ReportsContract.Action.ExportPdfRequested(ReportPdfKind.DEBTORS))
                runCurrent()
                assertEquals(ReportsContract.PdfFailure.NO_ACTIVE_BUSINESS, vm.uiState.value.pdfFailure)
                pdfs.missingBusiness = false
                val request = requestPdf(vm, effects)
                assertTrue(vm.claimPdfDestination(request.requestId))
                vm.onAction(ReportsContract.Action.PdfDestinationLaunchFailed(request.requestId))
                runCurrent()
                assertEquals(ReportsContract.PdfFailure.DESTINATION_CLEAN, vm.uiState.value.pdfFailure)
                assertTrue(vm.uiState.value.canExportPdf)
                vm.onAction(ReportsContract.Action.PdfDestinationSelected(request.requestId, URI))
                runCurrent()
                assertTrue(pdfs.writes.isEmpty())
                requestPdf(vm, effects)
            }
        }

    @Test
    fun `writer failures distinguish clean partial and changed destinations without reporting success`() =
        runTest(mainDispatcherRule.dispatcher) {
            withViewModel { vm, effects ->
                val statuses =
                    listOf(
                        ReportPdfWriteStatus.FAILED_DESTINATION_CLEAN to ReportsContract.PdfFailure.DESTINATION_CLEAN,
                        ReportPdfWriteStatus.FAILED_DESTINATION_MAY_CONTAIN_PARTIAL_DATA to ReportsContract.PdfFailure.DESTINATION_MAY_CONTAIN_PARTIAL_DATA,
                        ReportPdfWriteStatus.CONTEXT_CHANGED to ReportsContract.PdfFailure.CONTEXT_CHANGED,
                    )
                statuses.forEach { (status, expected) ->
                    pdfs.writeStatus = status
                    val request = requestPdf(vm, effects)
                    assertTrue(vm.claimPdfDestination(request.requestId))
                    vm.onAction(ReportsContract.Action.PdfDestinationSelected(request.requestId, URI))
                    runCurrent()
                    assertEquals(expected, vm.uiState.value.pdfFailure)
                    assertFalse(vm.uiState.value.pdfSaved)
                    assertTrue(vm.uiState.value.canExportPdf)
                }
            }
        }

    @Test
    fun `preparation cancellation is not converted into an error or a stuck progress indicator`() =
        runTest(mainDispatcherRule.dispatcher) {
            pdfs.preparationFailure = CancellationException("cancelled")
            withViewModel { vm, effects ->
                vm.onAction(ReportsContract.Action.ExportPdfRequested(ReportPdfKind.DEBTORS))
                runCurrent()
                assertEquals(ReportsContract.PdfStage.IDLE, vm.uiState.value.pdfStage)
                assertNull(vm.uiState.value.pdfFailure)
                assertTrue(vm.uiState.value.canExportPdf)
                assertTrue(effects.isEmpty())
                pdfs.preparationFailure = null
                requestPdf(vm, effects)
            }
        }

    @Test
    fun `debtors navigation is a user requested one time effect`() =
        runTest(mainDispatcherRule.dispatcher) {
            withViewModel { vm, effects ->
                assertTrue(effects.isEmpty())
                vm.onAction(ReportsContract.Action.OpenDebtors)
                runCurrent()
                assertEquals(listOf(ReportsContract.Effect.OpenDebtors), effects)
                assertTrue(pdfs.reads.isEmpty())
            }
        }

    @Test
    fun `changing business after success removes the old saved file action`() =
        runTest(mainDispatcherRule.dispatcher) {
            withViewModel { vm, effects ->
                val request = requestPdf(vm, effects)
                assertTrue(vm.claimPdfDestination(request.requestId))
                vm.onAction(ReportsContract.Action.PdfDestinationSelected(request.requestId, URI))
                runCurrent()
                assertTrue(vm.uiState.value.pdfSaved)
                config.value = config.value.copy(businessId = BusinessId.from(UUID(9L, 2L)))
                runCurrent()
                assertFalse(vm.uiState.value.pdfSaved)
                assertFalse(vm.uiState.value.pdfViewerUnavailable)
                val count = effects.size
                vm.onAction(ReportsContract.Action.OpenSavedPdf)
                runCurrent()
                assertEquals(count, effects.size)
            }
        }

    @Test
    fun `changing business during writing lets cleanup finish but never offers the old file in the new business`() =
        runTest(mainDispatcherRule.dispatcher) {
            pdfs.writeGate = CompletableDeferred()
            withViewModel { vm, effects ->
                val request = requestPdf(vm, effects)
                assertTrue(vm.claimPdfDestination(request.requestId))
                vm.onAction(ReportsContract.Action.PdfDestinationSelected(request.requestId, URI))
                runCurrent()
                assertEquals(1, pdfs.writes.size)
                config.value = config.value.copy(businessId = BusinessId.from(UUID(9L, 2L)))
                runCurrent()
                assertEquals(ReportsContract.PdfStage.WRITING, vm.uiState.value.pdfStage)
                pdfs.writeGate!!.complete(Unit)
                runCurrent()
                assertEquals(ReportsContract.PdfStage.IDLE, vm.uiState.value.pdfStage)
                assertEquals(ReportsContract.PdfFailure.CONTEXT_CHANGED, vm.uiState.value.pdfFailure)
                assertFalse(vm.uiState.value.pdfSaved)
                val count = effects.size
                vm.onAction(ReportsContract.Action.OpenSavedPdf)
                runCurrent()
                assertEquals(count, effects.size)
                assertEquals(1, pdfs.writes.size)
            }
        }

    private suspend fun TestScope.withViewModel(block: suspend TestScope.(ReportsViewModel, MutableList<ReportsContract.Effect>) -> Unit) {
        val vm = viewModel()
        val effects = mutableListOf<ReportsContract.Effect>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        try {
            runCurrent()
            block(vm, effects)
        } finally {
            collector.cancel()
            vm.viewModelScope.cancel()
        }
    }

    private fun TestScope.requestPdf(
        vm: ReportsViewModel,
        effects: List<ReportsContract.Effect>,
        kind: ReportPdfKind = ReportPdfKind.DEBTORS,
    ): ReportsContract.Effect.CreatePdfDocument {
        val count = effects.size
        vm.onAction(ReportsContract.Action.ExportPdfRequested(kind))
        runCurrent()
        assertEquals(count + 1, effects.size)
        return effects.last() as ReportsContract.Effect.CreatePdfDocument
    }

    private fun viewModel() =
        ReportsViewModel(
            observeSalesReport = ObserveSalesReportUseCase(configuration, FakeSaleRepository(), clock, FakeDebtPaymentReportRepository()),
            clock = clock,
            dispatcherProvider = TestDispatcherProvider(main = mainDispatcherRule.dispatcher),
            voidSale = VoidSaleUseCase(configuration, voids),
            exportReportPdf = ExportReportPdfUseCase(configuration, pdfs, pdfs, clock),
        )

    private companion object {
        val BUSINESS_ID = BusinessId.from(UUID(9L, 1L))
        const val URI = "content://documents/report.pdf"
    }
}
