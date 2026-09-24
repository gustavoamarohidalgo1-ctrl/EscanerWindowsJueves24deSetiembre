package com.facturastock.app.feature.reports

import com.facturastock.app.testing.FakeDebtPaymentReportRepository
import androidx.lifecycle.viewModelScope
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.model.DebtPaymentReportItem
import com.facturastock.app.domain.model.SalesReportPeriod
import com.facturastock.app.domain.model.ExactMonetaryAmount
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.RealizedSaleLineProfit
import com.facturastock.app.domain.model.RealizedSaleProfit
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.SaleId
import com.facturastock.app.domain.model.id.SaleLineId
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.DebtRepository
import com.facturastock.app.domain.repository.SaleRepository
import com.facturastock.app.domain.repository.SaleVoidLine
import com.facturastock.app.domain.repository.SaleVoidPreview
import com.facturastock.app.domain.repository.SaleVoidPreviewResult
import com.facturastock.app.domain.repository.SaleVoidRepository
import com.facturastock.app.domain.repository.SaleVoidResult
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.usecase.ExportReportPdfUseCase
import com.facturastock.app.domain.usecase.ObserveSalesReportUseCase
import com.facturastock.app.domain.usecase.VoidSaleUseCase
import com.facturastock.app.testing.FakeAppConfigurationRepository
import com.facturastock.app.testing.FakeSaleRepository
import com.facturastock.app.testing.MainDispatcherRule
import com.facturastock.app.testing.TestDispatcherProvider
import java.time.Instant
import java.time.Duration
import java.time.ZoneId
import java.math.BigDecimal
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReportsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private var now = Instant.parse("2026-08-29T04:59:59.900Z")
    private val clock = AppClock { now }
    private val configuration = FakeAppConfigurationRepository()
    private val sales = FakeSaleRepository()
    private val voids = FakeVoidRepository()
    private val pdfs = FakeReportsPdfPorts()
    private val dispatchers = TestDispatcherProvider(main = mainDispatcherRule.dispatcher)

    @Test
    fun `an open daily report moves to the next business day at midnight`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            configuration.completeOnboarding(
                BUSINESS_ID,
                AppConfiguration.DEFAULT_TAX_RATE,
                AppConfiguration.DEFAULT_COST_POLICY,
            )
            val viewModel = ReportsViewModel(
                observeSalesReport = ObserveSalesReportUseCase(configuration, sales, clock, FakeDebtPaymentReportRepository()),
                clock = clock,
                dispatcherProvider = dispatchers,
                voidSale = VoidSaleUseCase(configuration, voids),
                exportReportPdf = ExportReportPdfUseCase(configuration, pdfs, pdfs, clock),
            )
            try {
                runCurrent()

                val firstRequest = sales.observedProfitRequests.single()
                assertEquals(Instant.parse("2026-08-28T05:00:00Z"), firstRequest.second)
                assertEquals(Instant.parse("2026-08-29T05:00:00Z"), firstRequest.third)

                now = Instant.parse("2026-08-29T05:00:00.100Z")
                advanceTimeBy(201L)
                runCurrent()

                val refreshedRequest = sales.observedProfitRequests.last()
                assertEquals(2, sales.observedProfitRequests.size)
                assertEquals(Instant.parse("2026-08-29T05:00:00Z"), refreshedRequest.second)
                assertEquals(Instant.parse("2026-08-30T05:00:00Z"), refreshedRequest.third)
                assertEquals(SalesReportPeriod.DAY, viewModel.uiState.value.report?.range?.period)
                assertEquals(
                    Instant.parse("2026-08-29T05:00:00Z"),
                    viewModel.uiState.value.report?.range?.startInclusive,
                )
                assertFalse(viewModel.uiState.value.isLoading)
                assertFalse(viewModel.uiState.value.isRefreshing)
            } finally {
                // El ViewModel real vive mientras la pantalla exista. En esta prueba aislada hay
                // que cerrar su scope para que runTest no siga adelantando el temporizador diario.
                viewModel.viewModelScope.cancel()
            }
        }

    @Test
    fun `repeated resumes reuse fresh content without state changes while retry reopens observation`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            completeOnboarding()
            val viewModel = viewModel()
            val states = mutableListOf<ReportsContract.State>()
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.uiState.collect { states += it }
            }
            try {
                runCurrent()
                assertEquals(1, sales.observedProfitRequests.size)
                val fresh = viewModel.uiState.value
                val stateCount = states.size
                repeat(3) {
                    viewModel.onAction(ReportsContract.Action.Resumed)
                    runCurrent()
                    assertEquals(1, sales.observedProfitRequests.size)
                    assertEquals(fresh, viewModel.uiState.value)
                    assertEquals(stateCount, states.size)
                }
                assertFalse(states.any { it.isRefreshing })

                viewModel.onAction(ReportsContract.Action.Retry)
                runCurrent()
                assertEquals(2, sales.observedProfitRequests.size)
                assertTrue(states.any { it.isRefreshing })
                assertFalse(viewModel.uiState.value.isRefreshing)
            } finally {
                collector.cancel()
                viewModel.viewModelScope.cancel()
            }
        }

    @Test
    fun `stopping pauses the live report and resuming reopens it keeping visible content`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            completeOnboarding()
            val viewModel = viewModel()
            val states = mutableListOf<ReportsContract.State>()
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.uiState.collect { states += it }
            }
            try {
                runCurrent()
                assertEquals(1, sales.observedProfitRequests.size)
                val shown = viewModel.uiState.value.report
                assertTrue(shown != null)

                viewModel.onAction(ReportsContract.Action.Stopped)
                runCurrent()
                // Pausar no borra el contenido ni emite estados de carga.
                assertEquals(shown, viewModel.uiState.value.report)
                assertFalse(viewModel.uiState.value.isLoading)

                states.clear()
                viewModel.onAction(ReportsContract.Action.Resumed)
                runCurrent()
                assertEquals(2, sales.observedProfitRequests.size)
                assertTrue(states.none { it.report == null || it.isLoading })
                assertFalse(viewModel.uiState.value.isRefreshing)
                assertEquals(shown!!.range, viewModel.uiState.value.report?.range)
            } finally {
                collector.cancel()
                viewModel.viewModelScope.cancel()
            }
        }

    @Test
    fun `a later resume after the displayed range expired recalculates calendar boundaries`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            completeOnboarding()
            val viewModel = viewModel()
            try {
                runCurrent()
                viewModel.onAction(ReportsContract.Action.Resumed)
                runCurrent()
                assertEquals(1, sales.observedProfitRequests.size)
                // El reloj cambia sin ejecutar el timer, como al regresar de una suspensión.
                now = Instant.parse("2026-08-29T05:00:01Z")
                viewModel.onAction(ReportsContract.Action.Resumed)
                runCurrent()
                assertEquals(2, sales.observedProfitRequests.size)
                assertEquals(Instant.parse("2026-08-29T05:00:00Z"), sales.observedProfitRequests.last().second)
                assertEquals(Instant.parse("2026-08-30T05:00:00Z"), viewModel.uiState.value.report!!.range.endExclusive)
                assertFalse(viewModel.uiState.value.isRefreshing)
            } finally {
                viewModel.viewModelScope.cancel()
            }
        }

    @Test
    fun `first resume with a pending initial load crossing midnight does not reuse its old query`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            completeOnboarding()
            val gate = CompletableDeferred<Unit>()
            val requests = mutableListOf<Triple<BusinessId, Instant, Instant>>()
            val delayedSales = object : SaleRepository by sales {
                override fun observePostedProfits(
                    businessId: BusinessId,
                    startInclusive: Instant,
                    endExclusive: Instant,
                ): Flow<List<RealizedSaleProfit>> {
                    requests += Triple(businessId, startInclusive, endExclusive)
                    return flow {
                        gate.await()
                        emitAll(sales.observePostedProfits(businessId, startInclusive, endExclusive))
                    }
                }
            }
            val viewModel = viewModel(repository = delayedSales)
            try {
                runCurrent()
                assertEquals(1, requests.size)
                assertNull(viewModel.uiState.value.report)
                now = Instant.parse("2026-08-29T05:00:01Z")
                viewModel.onAction(ReportsContract.Action.Resumed)
                runCurrent()
                assertEquals(2, requests.size)
                assertEquals(Instant.parse("2026-08-29T05:00:00Z"), requests.last().second)
                gate.complete(Unit)
                runCurrent()
                assertEquals(requests.last(), sales.observedProfitRequests.single())
                assertEquals(Instant.parse("2026-08-29T05:00:00Z"), viewModel.uiState.value.report!!.range.startInclusive)
                assertFalse(viewModel.uiState.value.isLoading)
            } finally {
                viewModel.viewModelScope.cancel()
            }
        }

    @Test
    fun `resumes recover failed initial and stale observations while explicit retry can reopen them`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            completeOnboarding()
            configuration.observeFailure = IOException("initial failure")
            val viewModel = viewModel()
            try {
                runCurrent()
                assertEquals(ReportsContract.Failure.LOAD_FAILED, viewModel.uiState.value.failure)
                assertTrue(sales.observedProfitRequests.isEmpty())
                configuration.observeFailure = null
                viewModel.onAction(ReportsContract.Action.Resumed)
                runCurrent()
                assertEquals(1, sales.observedProfitRequests.size)
                assertNull(viewModel.uiState.value.failure)
                val lastReport = viewModel.uiState.value.report

                configuration.observeFailure = IOException("retry failure")
                viewModel.onAction(ReportsContract.Action.Retry)
                runCurrent()
                assertEquals(ReportsContract.Failure.LOAD_FAILED, viewModel.uiState.value.failure)
                assertEquals(lastReport, viewModel.uiState.value.report)
                configuration.observeFailure = null
                viewModel.onAction(ReportsContract.Action.Resumed)
                runCurrent()
                assertEquals(2, sales.observedProfitRequests.size)
                assertNull(viewModel.uiState.value.failure)
                assertFalse(viewModel.uiState.value.isRefreshing)
            } finally {
                viewModel.viewModelScope.cancel()
            }
        }

    @Test
    fun `retained observation still emits sales and switches timezone and active business across resumes`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val configs = MutableStateFlow(AppConfiguration.defaults().copy(
                onboardingCompleted = true,
                businessId = BUSINESS_ID,
            ))
            val configRepository = object : AppConfigurationRepository by configuration {
                override fun observe(): Flow<AppConfiguration> = configs
            }
            val viewModel = viewModel(configurationRepository = configRepository)
            val firstSale = profit(1L)
            val otherSale = profit(2L)
            val otherBusiness = BusinessId.from(UUID(0L, 902L))
            try {
                runCurrent()
                repeat(3) {
                    viewModel.onAction(ReportsContract.Action.Resumed)
                    runCurrent()
                }
                assertEquals(1, sales.observedProfitRequests.size)
                sales.replacePostedProfits(BUSINESS_ID, listOf(firstSale))
                runCurrent()
                assertEquals(listOf(firstSale), viewModel.uiState.value.report!!.sales)
                assertEquals(1, sales.observedProfitRequests.size)

                configs.value = configs.value.copy(zoneId = ZoneId.of("UTC"))
                runCurrent()
                assertEquals(2, sales.observedProfitRequests.size)
                assertEquals(Instant.parse("2026-08-29T00:00:00Z"), sales.observedProfitRequests.last().second)
                assertEquals(ZoneId.of("UTC"), viewModel.uiState.value.report!!.range.zoneId)
                sales.replacePostedProfits(otherBusiness, listOf(otherSale))
                configs.value = configs.value.copy(businessId = otherBusiness)
                runCurrent()
                assertEquals(3, sales.observedProfitRequests.size)
                assertEquals(otherBusiness, sales.observedProfitRequests.last().first)
                assertEquals(listOf(otherSale), viewModel.uiState.value.report!!.sales)

                repeat(2) {
                    viewModel.onAction(ReportsContract.Action.Resumed)
                    runCurrent()
                    assertEquals(3, sales.observedProfitRequests.size)
                    assertEquals(listOf(otherSale), viewModel.uiState.value.report!!.sales)
                }

                sales.replacePostedProfits(BUSINESS_ID, emptyList())
                runCurrent()
                assertEquals(listOf(otherSale), viewModel.uiState.value.report!!.sales)
                assertEquals(3, sales.observedProfitRequests.size)
                assertFalse(viewModel.uiState.value.isRefreshing)
            } finally {
                viewModel.viewModelScope.cancel()
            }
        }

    @Test
    fun `a completed observation is reopened on resume even when its last report is still current`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            completeOnboarding()
            val currentConfiguration = configuration.current()
            val finiteConfiguration = object : AppConfigurationRepository by configuration {
                override fun observe(): Flow<AppConfiguration> = flowOf(currentConfiguration)
            }
            val finiteSales = object : SaleRepository by sales {
                override fun observePostedProfits(
                    businessId: BusinessId,
                    startInclusive: Instant,
                    endExclusive: Instant,
                ): Flow<List<RealizedSaleProfit>> =
                    sales.observePostedProfits(businessId, startInclusive, endExclusive).take(1)
            }
            val payments = FakeDebtPaymentReportRepository()
            val finitePayments = object : DebtRepository by payments {
                override fun observePaymentsInRange(
                    businessId: BusinessId,
                    startInclusive: Instant,
                    endExclusive: Instant,
                ): Flow<List<DebtPaymentReportItem>> =
                    payments.observePaymentsInRange(businessId, startInclusive, endExclusive).take(1)
            }
            val viewModel = viewModel(finiteSales, finiteConfiguration, finitePayments)
            try {
                runCurrent()
                val fresh = viewModel.uiState.value.report
                assertTrue(fresh != null)
                assertEquals(1, sales.observedProfitRequests.size)
                assertEquals(1, payments.requests.size)
                viewModel.onAction(ReportsContract.Action.Resumed)
                runCurrent()
                assertEquals(2, sales.observedProfitRequests.size)
                assertEquals(2, payments.requests.size)
                assertEquals(fresh, viewModel.uiState.value.report)
                assertFalse(viewModel.uiState.value.isRefreshing)
            } finally {
                viewModel.viewModelScope.cancel()
            }
        }

    @Test
    fun `resume reschedules midnight after the clock advances within the current day without reopening data`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            now = Instant.parse("2026-08-28T17:00:00Z")
            completeOnboarding()
            val viewModel = viewModel()
            try {
                runCurrent()
                val fresh = viewModel.uiState.value
                val midnight = requireNotNull(fresh.report).range.endExclusive
                assertEquals(Instant.parse("2026-08-29T05:00:00Z"), midnight)
                assertEquals(1, sales.observedProfitRequests.size)

                // El reloj civil avanza dos horas mientras el scheduler permanece en cero.
                now = now.plusSeconds(2L * 60L * 60L)
                val remainingMillis = Duration.between(now, midnight).toMillis()
                viewModel.onAction(ReportsContract.Action.Resumed)
                runCurrent()
                assertEquals(1, sales.observedProfitRequests.size)
                assertEquals(fresh, viewModel.uiState.value)

                now = midnight.plusMillis(100L)
                advanceTimeBy(remainingMillis + 101L)
                runCurrent()
                assertEquals(2, sales.observedProfitRequests.size)
                assertEquals(midnight, sales.observedProfitRequests.last().second)
                assertEquals(midnight, viewModel.uiState.value.report!!.range.startInclusive)
                assertFalse(viewModel.uiState.value.isRefreshing)
            } finally {
                viewModel.viewModelScope.cancel()
            }
        }

    @Test
    fun `preview and cancellation never mutate and dismiss immediately rejects a queued confirm`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            completeOnboarding()
            val sale = profit(1L)
            sales.replacePostedProfits(BUSINESS_ID, listOf(sale))
            voids.previewResult = SaleVoidPreviewResult.Ready(voidPreview(sale))
            val viewModel = viewModel()
            try {
                runCurrent()
                viewModel.onAction(ReportsContract.Action.VoidRequested(sale.saleId))
                viewModel.onAction(ReportsContract.Action.VoidRequested(sale.saleId))
                runCurrent()
                assertEquals(1, voids.previewCalls.size)
                assertEquals(voidPreview(sale), viewModel.uiState.value.voidPreview)
                assertTrue(voids.confirmations.isEmpty())
                assertEquals(listOf(sale), viewModel.uiState.value.report!!.sales)

                viewModel.onAction(ReportsContract.Action.VoidDismissed)
                viewModel.onAction(ReportsContract.Action.VoidConfirmed)
                runCurrent()
                assertNull(viewModel.uiState.value.voidSaleId)
                assertNull(viewModel.uiState.value.voidPreview)
                assertTrue(voids.confirmations.isEmpty())
                assertEquals(listOf(sale), viewModel.uiState.value.report!!.sales)
            } finally {
                viewModel.viewModelScope.cancel()
            }
        }

    @Test
    fun `confirm reserves once immediately and waits for commit and report emission`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            completeOnboarding()
            val sale = profit(1L)
            sales.replacePostedProfits(BUSINESS_ID, listOf(sale))
            voids.previewResult = SaleVoidPreviewResult.Ready(voidPreview(sale))
            val commit = CompletableDeferred<Unit>()
            voids.confirmGate = commit
            val viewModel = viewModel()
            try {
                runCurrent()
                viewModel.onAction(ReportsContract.Action.VoidRequested(sale.saleId))
                runCurrent()
                repeat(3) { viewModel.onAction(ReportsContract.Action.VoidConfirmed) }
                viewModel.onAction(ReportsContract.Action.VoidDismissed)
                viewModel.onAction(ReportsContract.Action.PeriodSelected(SalesReportPeriod.MONTH))
                runCurrent()
                assertEquals(1, voids.confirmations.size)
                assertTrue(viewModel.uiState.value.isVoiding)
                assertEquals(SalesReportPeriod.DAY, viewModel.uiState.value.selectedPeriod)
                assertEquals(listOf(sale), viewModel.uiState.value.report!!.sales)
                assertFalse(viewModel.uiState.value.voidSucceeded)

                commit.complete(Unit)
                runCurrent()
                assertFalse(viewModel.uiState.value.isVoiding)
                assertNull(viewModel.uiState.value.voidPreview)
                assertTrue(viewModel.uiState.value.voidSucceeded)
                // El resultado de escritura no altera un reporte de lectura anticipadamente.
                assertEquals(listOf(sale), viewModel.uiState.value.report!!.sales)
                sales.replacePostedProfits(BUSINESS_ID, emptyList())
                runCurrent()
                assertTrue(viewModel.uiState.value.report!!.sales.isEmpty())
                viewModel.onAction(ReportsContract.Action.Resumed)
                viewModel.onAction(ReportsContract.Action.VoidConfirmed)
                runCurrent()
                assertEquals(1, voids.confirmations.size)
                assertTrue(viewModel.uiState.value.voidSucceeded)
            } finally {
                viewModel.viewModelScope.cancel()
            }
        }

    @Test
    fun `stale confirmation reloads the impact and requires a fresh explicit confirmation`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            completeOnboarding()
            val sale = profit(1L)
            sales.replacePostedProfits(BUSINESS_ID, listOf(sale))
            val original = voidPreview(sale)
            voids.previewResult = SaleVoidPreviewResult.Ready(original)
            voids.confirmResult = SaleVoidResult.Stale
            val viewModel = viewModel()
            try {
                runCurrent()
                viewModel.onAction(ReportsContract.Action.VoidRequested(sale.saleId))
                runCurrent()
                val refreshed = original.copy(
                    refundAmount = Money.ofMinor(200L, sale.totalCharged.currency),
                    debtBalanceToCancel = Money.ofMinor(300L, sale.totalCharged.currency),
                    impactHash = "b".repeat(64),
                )
                voids.previewResult = SaleVoidPreviewResult.Ready(refreshed)
                viewModel.onAction(ReportsContract.Action.VoidConfirmed)
                runCurrent()
                assertEquals(1, voids.confirmations.size)
                assertEquals(2, voids.previewCalls.size)
                assertEquals(refreshed, viewModel.uiState.value.voidPreview)
                assertEquals(ReportsContract.VoidFailure.STALE, viewModel.uiState.value.voidFailure)
                assertFalse(viewModel.uiState.value.isVoiding)
                assertFalse(viewModel.uiState.value.voidSucceeded)
                voids.confirmResult = SaleVoidResult.Voided
                viewModel.onAction(ReportsContract.Action.VoidConfirmed)
                runCurrent()
                assertEquals(listOf(original, refreshed), voids.confirmations)
                assertTrue(viewModel.uiState.value.voidSucceeded)
            } finally {
                viewModel.viewModelScope.cancel()
            }
        }

    @Test
    fun `changing period cancels a pending preview and cannot confirm the old sale`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            completeOnboarding()
            val sale = profit(1L)
            sales.replacePostedProfits(BUSINESS_ID, listOf(sale))
            voids.previewResult = SaleVoidPreviewResult.Ready(voidPreview(sale))
            val previewGate = CompletableDeferred<Unit>()
            voids.previewGate = previewGate
            val viewModel = viewModel()
            try {
                runCurrent()
                viewModel.onAction(ReportsContract.Action.VoidRequested(sale.saleId))
                runCurrent()
                assertTrue(viewModel.uiState.value.isLoadingVoidPreview)
                viewModel.onAction(ReportsContract.Action.PeriodSelected(SalesReportPeriod.WEEK))
                viewModel.onAction(ReportsContract.Action.VoidConfirmed)
                runCurrent()
                previewGate.complete(Unit)
                runCurrent()
                assertEquals(SalesReportPeriod.WEEK, viewModel.uiState.value.selectedPeriod)
                assertNull(viewModel.uiState.value.voidPreview)
                assertNull(viewModel.uiState.value.voidSaleId)
                assertFalse(viewModel.uiState.value.isLoadingVoidPreview)
                assertTrue(voids.confirmations.isEmpty())
            } finally {
                viewModel.viewModelScope.cancel()
            }
        }

    @Test
    fun `business change clears the confirmation and never reuses the old preview`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            completeOnboarding()
            val sale = profit(1L)
            sales.replacePostedProfits(BUSINESS_ID, listOf(sale))
            voids.previewResult = SaleVoidPreviewResult.Ready(voidPreview(sale))
            val viewModel = viewModel()
            try {
                runCurrent()
                viewModel.onAction(ReportsContract.Action.VoidRequested(sale.saleId))
                runCurrent()
                val otherBusiness = BusinessId.from(UUID(0L, 902L))
                configuration.completeOnboarding(
                    otherBusiness, AppConfiguration.DEFAULT_TAX_RATE, AppConfiguration.DEFAULT_COST_POLICY,
                )
                runCurrent()
                assertEquals(otherBusiness, viewModel.uiState.value.report!!.businessId)
                assertNull(viewModel.uiState.value.voidPreview)
                assertNull(viewModel.uiState.value.voidSaleId)
                viewModel.onAction(ReportsContract.Action.VoidConfirmed)
                runCurrent()
                assertTrue(voids.confirmations.isEmpty())
            } finally {
                viewModel.viewModelScope.cancel()
            }
        }

    @Test
    fun `shared business rejection stays visible and retry only reloads a preview`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            completeOnboarding()
            val sale = profit(1L)
            sales.replacePostedProfits(BUSINESS_ID, listOf(sale))
            voids.previewResult = SaleVoidPreviewResult.SharedBusinessUnsupported
            val viewModel = viewModel()
            try {
                runCurrent()
                viewModel.onAction(ReportsContract.Action.VoidRequested(sale.saleId))
                runCurrent()
                assertEquals(
                    ReportsContract.VoidFailure.SHARED_BUSINESS_UNSUPPORTED,
                    viewModel.uiState.value.voidFailure,
                )
                assertNull(viewModel.uiState.value.voidPreview)
                viewModel.onAction(ReportsContract.Action.VoidConfirmed)
                runCurrent()
                assertTrue(voids.confirmations.isEmpty())
                voids.previewResult = SaleVoidPreviewResult.Ready(voidPreview(sale))
                viewModel.onAction(ReportsContract.Action.VoidPreviewRetry)
                runCurrent()
                assertEquals(2, voids.previewCalls.size)
                assertNull(viewModel.uiState.value.voidFailure)
                assertEquals(voidPreview(sale), viewModel.uiState.value.voidPreview)
                assertTrue(voids.confirmations.isEmpty())
            } finally {
                viewModel.viewModelScope.cancel()
            }
        }

    @Test
    fun `ambiguous commit failure requires review and an already voided retry is idempotent`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            completeOnboarding()
            val sale = profit(1L)
            sales.replacePostedProfits(BUSINESS_ID, listOf(sale))
            voids.previewResult = SaleVoidPreviewResult.Ready(voidPreview(sale))
            voids.confirmFailure = IOException("response lost")
            val viewModel = viewModel()
            try {
                runCurrent()
                viewModel.onAction(ReportsContract.Action.VoidRequested(sale.saleId))
                runCurrent()
                viewModel.onAction(ReportsContract.Action.VoidConfirmed)
                runCurrent()
                assertFalse(viewModel.uiState.value.isVoiding)
                assertNull(viewModel.uiState.value.voidPreview)
                assertEquals(ReportsContract.VoidFailure.OPERATION_FAILED, viewModel.uiState.value.voidFailure)
                assertEquals(listOf(sale), viewModel.uiState.value.report!!.sales)
                viewModel.onAction(ReportsContract.Action.VoidConfirmed)
                runCurrent()
                assertEquals(1, voids.confirmations.size)
                voids.previewResult = SaleVoidPreviewResult.AlreadyVoided
                viewModel.onAction(ReportsContract.Action.VoidPreviewRetry)
                runCurrent()
                assertTrue(viewModel.uiState.value.voidSucceeded)
                assertNull(viewModel.uiState.value.voidSaleId)
                assertEquals(1, voids.confirmations.size)
            } finally {
                viewModel.viewModelScope.cancel()
            }
        }

    private suspend fun completeOnboarding() {
        configuration.completeOnboarding(
            BUSINESS_ID, AppConfiguration.DEFAULT_TAX_RATE, AppConfiguration.DEFAULT_COST_POLICY,
        )
    }

    private fun viewModel(
        repository: SaleRepository = sales,
        configurationRepository: AppConfigurationRepository = configuration,
        debtRepository: DebtRepository = FakeDebtPaymentReportRepository(),
    ) = ReportsViewModel(
        ObserveSalesReportUseCase(configurationRepository, repository, clock, debtRepository), clock, dispatchers,
        VoidSaleUseCase(configurationRepository, voids),
        ExportReportPdfUseCase(configurationRepository, pdfs, pdfs, clock),
    )

    private fun voidPreview(sale: RealizedSaleProfit) = SaleVoidPreview(
        businessId = BUSINESS_ID,
        saleId = sale.saleId,
        postedAt = sale.postedAt,
        total = sale.totalCharged,
        refundAmount = sale.totalCharged,
        debtBalanceToCancel = null,
        lines = sale.lines.map {
            SaleVoidLine(it.productName, it.locationName, it.unitCode, requireNotNull(it.quantity))
        },
        impactHash = "a".repeat(64),
    )

    private class FakeVoidRepository : SaleVoidRepository {
        var previewResult: SaleVoidPreviewResult = SaleVoidPreviewResult.InvalidHistory
        var confirmResult: SaleVoidResult = SaleVoidResult.Voided
        var previewGate: CompletableDeferred<Unit>? = null
        var confirmGate: CompletableDeferred<Unit>? = null
        var confirmFailure: Throwable? = null
        val previewCalls = mutableListOf<Pair<BusinessId, SaleId>>()
        val confirmations = mutableListOf<SaleVoidPreview>()

        override suspend fun preview(businessId: BusinessId, saleId: SaleId): SaleVoidPreviewResult {
            previewCalls += businessId to saleId
            previewGate?.await()
            return previewResult
        }

        override suspend fun confirm(preview: SaleVoidPreview): SaleVoidResult {
            confirmations += preview
            confirmGate?.await()
            confirmFailure?.let { throw it }
            return confirmResult
        }
    }

    private fun profit(id: Long): RealizedSaleProfit {
        val currency = AppConfiguration.DEFAULT_CURRENCY
        val revenue = Money.ofMinor(500L, currency)
        val cost = ExactMonetaryAmount(BigDecimal("2"), currency)
        val gross = ExactMonetaryAmount(BigDecimal("3"), currency)
        return RealizedSaleProfit(
            saleId = SaleId.from(UUID(0L, id)), totalCharged = revenue, netRevenue = revenue,
            historicalCost = cost, grossProfit = gross, postedAt = now.minusMillis(1L),
            lines = listOf(RealizedSaleLineProfit(
                saleLineId = SaleLineId.from(UUID(1L, id)), productId = ProductId.from(UUID(2L, id)),
                position = 0, productName = "Producto", unitCode = "NIU", locationName = "Principal",
                quantity = Quantity.of("1"), totalCharged = revenue, netRevenue = revenue,
                historicalCost = cost, grossProfit = gross,
            )),
        )
    }

    private companion object {
        val BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("00000000-0000-4000-8000-000000000901"),
        )
    }
}
