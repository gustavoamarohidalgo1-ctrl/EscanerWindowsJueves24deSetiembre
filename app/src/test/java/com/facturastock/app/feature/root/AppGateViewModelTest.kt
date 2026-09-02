package com.facturastock.app.feature.root

import app.cash.turbine.test
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.usecase.ObserveAppConfigurationUseCase
import com.facturastock.app.testing.FakeAppConfigurationRepository
import com.facturastock.app.testing.MainDispatcherRule
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppGateViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `onboarding marcado completo sin negocio mantiene cerrado el grafo`() {
        val corruptConfiguration = AppConfiguration.defaults().copy(onboardingCompleted = true)

        assertEquals(GateState.Unavailable, corruptConfiguration.toGateState())
    }

    @Test
    fun `onboarding completo con negocio abre el grafo principal`() {
        val configuration = AppConfiguration.defaults().copy(
            onboardingCompleted = true,
            businessId = BusinessId.from(UUID.fromString(BUSINESS_UUID)),
        )

        assertEquals(GateState.Complete, configuration.toGateState())
    }

    @Test
    fun `un fallo queda recuperable y retry vuelve a observar la configuracion`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val repository = FakeAppConfigurationRepository().apply {
                observeFailure = IOException("fallo de lectura simulado")
            }
            val viewModel = AppGateViewModel(ObserveAppConfigurationUseCase(repository))

            viewModel.uiState.test {
                assertEquals(GateState.Loading, awaitItem())
                assertEquals(GateState.Unavailable, awaitItem())

                repository.observeFailure = null
                viewModel.retry()

                assertEquals(GateState.Loading, awaitItem())
                assertEquals(GateState.Incomplete, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `una pausa corta conserva Complete sin volver a Loading`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val defaults = AppConfiguration.defaults()
            val repository = FakeAppConfigurationRepository().apply {
                completeOnboarding(
                    businessId = BusinessId.from(UUID.fromString(BUSINESS_UUID)),
                    taxRate = defaults.taxRate,
                    costPolicy = defaults.costPolicy,
                )
            }
            val viewModel = AppGateViewModel(ObserveAppConfigurationUseCase(repository))

            viewModel.uiState.test {
                assertEquals(GateState.Loading, awaitItem())
                assertEquals(GateState.Complete, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            advanceTimeBy(4_999L)

            viewModel.uiState.test {
                assertEquals(GateState.Complete, awaitItem())
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    private companion object {
        const val BUSINESS_UUID = "11111111-1111-1111-1111-111111111111"
    }
}
