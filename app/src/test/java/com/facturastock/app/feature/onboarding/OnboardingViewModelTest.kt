package com.facturastock.app.feature.onboarding

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.config.CostPolicy
import com.facturastock.app.domain.usecase.CompleteOnboardingUseCase
import com.facturastock.app.testing.FakeAppConfigurationRepository
import com.facturastock.app.testing.FakeBusinessRepository
import com.facturastock.app.testing.FakeInventoryLocationRepository
import com.facturastock.app.testing.FakeOnboardingProvisioningRepository
import com.facturastock.app.testing.FakeUnitRepository
import com.facturastock.app.testing.MainDispatcherRule
import com.facturastock.app.testing.TestDispatcherProvider
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private var uuidCounter = 0
    private val uuidGenerator = UuidGenerator {
        UUID.fromString("00000000-0000-0000-0000-%012d".format(++uuidCounter))
    }
    private val clock = AppClock { Instant.parse("2026-08-08T12:00:00Z") }

    private lateinit var businesses: FakeBusinessRepository
    private lateinit var locations: FakeInventoryLocationRepository
    private lateinit var units: FakeUnitRepository
    private lateinit var appConfig: FakeAppConfigurationRepository
    private lateinit var dispatchers: TestDispatcherProvider

    private fun createViewModel(savedStateHandle: SavedStateHandle = SavedStateHandle()): OnboardingViewModel {
        businesses = FakeBusinessRepository(clock)
        locations = FakeInventoryLocationRepository(clock)
        units = FakeUnitRepository(clock)
        appConfig = FakeAppConfigurationRepository()
        dispatchers = TestDispatcherProvider(mainDispatcherRule.dispatcher)
        return OnboardingViewModel(
            savedStateHandle = savedStateHandle,
            completeOnboardingUseCase = CompleteOnboardingUseCase(
                onboardingProvisioningRepository = FakeOnboardingProvisioningRepository(
                    businesses = businesses,
                    locations = locations,
                    units = units,
                ),
                appConfigurationRepository = appConfig,
                uuidGenerator = uuidGenerator,
                appClock = clock,
            ),
            dispatcherProvider = dispatchers,
        )
    }

    @Test
    fun `estado inicial con defaults y guardado bloqueado`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()

            assertEquals(OnboardingContract.State(), viewModel.uiState.value)
            assertFalse(viewModel.uiState.value.canSave)
        }

    @Test
    fun `canSave exige nombre y almacen con RUC e IGV validos`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()

            viewModel.onAction(OnboardingContract.Action.BusinessNameChanged("Bodega Lola"))
            runCurrent()
            assertFalse(viewModel.uiState.value.canSave)

            viewModel.onAction(OnboardingContract.Action.WarehouseNameChanged("Almacén central"))
            runCurrent()
            assertTrue(viewModel.uiState.value.canSave)

            viewModel.onAction(OnboardingContract.Action.RucChanged("123"))
            runCurrent()
            assertFalse(viewModel.uiState.value.canSave)

            viewModel.onAction(OnboardingContract.Action.RucChanged("12345678903"))
            runCurrent()
            assertTrue(viewModel.uiState.value.canSave)

            viewModel.onAction(OnboardingContract.Action.TaxRateChanged("101"))
            runCurrent()
            assertFalse(viewModel.uiState.value.canSave)
        }

    @Test
    fun `nombres se limitan antes de entrar al estado y SavedState`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val handle = SavedStateHandle()
            val viewModel = createViewModel(handle)

            viewModel.onAction(OnboardingContract.Action.BusinessNameChanged("N".repeat(300)))
            viewModel.onAction(OnboardingContract.Action.WarehouseNameChanged("A".repeat(200)))
            runCurrent()

            assertEquals(
                OnboardingContract.BUSINESS_NAME_MAX_LENGTH,
                viewModel.uiState.value.businessName.length,
            )
            assertEquals(
                OnboardingContract.WAREHOUSE_NAME_MAX_LENGTH,
                viewModel.uiState.value.warehouseName.length,
            )
            assertEquals(
                OnboardingContract.WAREHOUSE_NAME_MAX_LENGTH,
                handle.get<String>("onboarding.warehouseName")?.length,
            )
        }

    @Test
    fun `RUC mal formado bloquea el guardado sin tocar los repositorios`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()
            fillValidForm(viewModel, ruc = "123")

            viewModel.onAction(OnboardingContract.Action.Save)
            runCurrent()

            assertTrue(businesses.observeBusinesses().first().isEmpty())
            assertFalse(appConfig.current().onboardingCompleted)
        }

    @Test
    fun `checksum invalido advierte una vez y un segundo guardado conserva el RUC tal cual`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()
            fillValidForm(viewModel, ruc = "12345678901")

            viewModel.effects.test {
                viewModel.onAction(OnboardingContract.Action.Save)
                runCurrent()

                // Primer intento: solo advierte, no persiste ni emite.
                assertTrue(viewModel.uiState.value.rucChecksumWarning)
                assertTrue(businesses.observeBusinesses().first().isEmpty())
                assertFalse(appConfig.current().onboardingCompleted)
                expectNoEvents()

                // Segundo intento con el mismo RUC: confirma explícito y guarda sin corregir.
                viewModel.onAction(OnboardingContract.Action.Save)
                runCurrent()

                assertEquals(
                    OnboardingContract.Effect.OnboardingCompleted,
                    awaitItem(),
                )
                val saved = businesses.findByRuc("12345678901")
                assertNotNull(saved)
                assertEquals("12345678901", saved!!.ruc)
                assertTrue(appConfig.current().onboardingCompleted)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `guardado exitoso crea negocio almacen y configuracion con el reloj inyectado`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()
            fillValidForm(viewModel, ruc = "12345678903")

            viewModel.effects.test {
                viewModel.onAction(OnboardingContract.Action.Save)
                runCurrent()

                assertEquals(
                    OnboardingContract.Effect.OnboardingCompleted,
                    awaitItem(),
                )
                cancelAndIgnoreRemainingEvents()
            }

            val business = businesses.findByRuc("12345678903")
            assertNotNull(business)
            assertEquals("Bodega Lola", business!!.legalName)
            assertEquals(clock.now(), business.createdAt)

            val warehouses = locations.observeForBusiness(business.businessId).first()
            assertEquals(listOf("Almacén central"), warehouses.map { it.name })

            val config = appConfig.current()
            assertTrue(config.onboardingCompleted)
            assertEquals(business.businessId, config.businessId)
            assertEquals(BigDecimal("18"), config.taxRate.percent)
            assertEquals(CostPolicy.NET, config.costPolicy)
        }

    @Test
    fun `dos Save rapidos producen un solo commit y un solo efecto terminal`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()
            fillValidForm(viewModel, ruc = "12345678903")

            viewModel.effects.test {
                viewModel.onAction(OnboardingContract.Action.Save)
                viewModel.onAction(OnboardingContract.Action.Save)
                runCurrent()

                assertEquals(OnboardingContract.Effect.OnboardingCompleted, awaitItem())
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }

            assertEquals(1, businesses.observeBusinesses().first().size)
            val businessId = requireNotNull(appConfig.current().businessId)
            assertEquals(1, locations.observeForBusiness(businessId).first().size)
            assertEquals(1, units.observeForBusiness(businessId).first().size)
        }

    @Test
    fun `los campos sobreviven a la recreacion del ViewModel via SavedStateHandle`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val handle = SavedStateHandle()
            val first = createViewModel(handle)
            fillValidForm(first, ruc = "12345678903")
            runCurrent()

            val restored = createViewModel(handle)

            assertEquals("Bodega Lola", restored.uiState.value.businessName)
            assertEquals("12345678903", restored.uiState.value.ruc)
            assertEquals("Almacén central", restored.uiState.value.warehouseName)
            assertTrue(restored.uiState.value.canSave)
        }

    private fun TestScope.fillValidForm(viewModel: OnboardingViewModel, ruc: String) {
        viewModel.onAction(OnboardingContract.Action.BusinessNameChanged("Bodega Lola"))
        viewModel.onAction(OnboardingContract.Action.RucChanged(ruc))
        viewModel.onAction(OnboardingContract.Action.WarehouseNameChanged("Almacén central"))
        runCurrent()
    }
}
