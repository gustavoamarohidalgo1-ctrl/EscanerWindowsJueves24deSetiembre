package com.facturastock.app.feature.reports

import androidx.lifecycle.viewModelScope
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.model.SalesReportPeriod
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.usecase.ObserveSalesReportUseCase
import com.facturastock.app.testing.FakeAppConfigurationRepository
import com.facturastock.app.testing.FakeSaleRepository
import com.facturastock.app.testing.MainDispatcherRule
import com.facturastock.app.testing.TestDispatcherProvider
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
                observeSalesReport = ObserveSalesReportUseCase(configuration, sales, clock),
                clock = clock,
                dispatcherProvider = dispatchers,
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

    private companion object {
        val BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("00000000-0000-4000-8000-000000000901"),
        )
    }
}
