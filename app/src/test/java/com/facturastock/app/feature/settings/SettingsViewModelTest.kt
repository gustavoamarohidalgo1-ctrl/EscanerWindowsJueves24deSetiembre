package com.facturastock.app.feature.settings

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.config.CostPolicy
import com.facturastock.app.domain.config.TaxRate
import com.facturastock.app.domain.model.Business
import com.facturastock.app.domain.model.UserDataExportWriteStatus
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.usecase.ExportUserDataUseCase
import com.facturastock.app.domain.usecase.ObserveAppConfigurationUseCase
import com.facturastock.app.domain.usecase.UpdateBusinessProfileUseCase
import com.facturastock.app.domain.usecase.UpdateTaxConfigurationUseCase
import com.facturastock.app.domain.usecase.WriteUserDataExportUseCase
import com.facturastock.app.testing.FakeAppConfigurationRepository
import com.facturastock.app.testing.FakeBusinessRepository
import com.facturastock.app.testing.FakeInventoryLocationRepository
import com.facturastock.app.testing.FakeInventoryReadRepository
import com.facturastock.app.testing.FakeProductRepository
import com.facturastock.app.testing.FakePurchaseReadRepository
import com.facturastock.app.testing.FakeSupplierRepository
import com.facturastock.app.testing.FakeSupplierProductAliasRepository
import com.facturastock.app.testing.FakeUnitRepository
import com.facturastock.app.testing.FakeUserDataExportWriter
import com.facturastock.app.testing.MainDispatcherRule
import com.facturastock.app.testing.TestDispatcherProvider
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val realBusinessId = BusinessId.from(
        UUID.fromString("123e4567-e89b-42d3-a456-426614174000"),
    )
    private val clock = AppClock { Instant.parse("2026-08-08T12:00:00Z") }

    private lateinit var businesses: FakeBusinessRepository
    private lateinit var appConfig: FakeAppConfigurationRepository
    private lateinit var dispatchers: TestDispatcherProvider
    private lateinit var exportWriter: FakeUserDataExportWriter

    @Before
    fun setUp() = runTest {
        businesses = FakeBusinessRepository(clock)
        appConfig = FakeAppConfigurationRepository()
        dispatchers = TestDispatcherProvider(mainDispatcherRule.dispatcher)
        businesses.create(
            Business(
                businessId = realBusinessId,
                legalName = "Bodega Real SAC",
                ruc = "20123456786",
                createdAt = clock.now(),
                updatedAt = clock.now(),
            ),
        )
        appConfig.completeOnboarding(realBusinessId, TaxRate(BigDecimal("18")), CostPolicy.NET)
    }

    @Test
    fun `al iniciar carga la configuracion y el negocio activo`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()
            runCurrent()

            val state = viewModel.uiState.value
            assertEquals(realBusinessId, state.config?.businessId)
            assertEquals("Bodega Real SAC", state.business?.legalName)
            assertEquals("Bodega Real SAC", state.legalName)
            assertEquals("20123456786", state.ruc)
            assertEquals("18", state.taxRatePercent)
            assertEquals(CostPolicy.NET, state.costPolicy)
        }

    @Test
    fun `fallo inicial de configuracion se recupera con Retry`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            appConfig.observeFailure = IllegalStateException("settings unavailable")
            val viewModel = createViewModel()
            runCurrent()

            assertNull(viewModel.uiState.value.config)
            assertEquals(
                SettingsContract.Failure.LOAD_FAILED,
                viewModel.uiState.value.failure,
            )

            appConfig.observeFailure = null
            viewModel.onAction(SettingsContract.Action.Retry)
            runCurrent()

            assertEquals(realBusinessId, viewModel.uiState.value.config?.businessId)
            assertNull(viewModel.uiState.value.failure)
        }

    @Test
    fun `borrador local sobrevive recreacion del ViewModel y primera emision`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val handle = SavedStateHandle()
            val first = createViewModel(savedStateHandle = handle)
            runCurrent()

            first.onAction(SettingsContract.Action.LegalNameChanged("Nombre aún no guardado"))
            first.onAction(SettingsContract.Action.TaxRateChanged("12.5"))
            first.onAction(SettingsContract.Action.CostPolicySelected(CostPolicy.GROSS))
            runCurrent()

            val recreated = createViewModel(savedStateHandle = handle)
            runCurrent()

            assertEquals("Nombre aún no guardado", recreated.uiState.value.legalName)
            assertEquals("12.5", recreated.uiState.value.taxRatePercent)
            assertEquals(CostPolicy.GROSS, recreated.uiState.value.costPolicy)
            assertEquals("Bodega Real SAC", recreated.uiState.value.business?.legalName)
        }

    @Test
    fun `nombres de negocio se limitan antes de entrar al estado y SavedState`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val handle = SavedStateHandle()
            val viewModel = createViewModel(savedStateHandle = handle)
            runCurrent()

            viewModel.onAction(SettingsContract.Action.LegalNameChanged("L".repeat(300)))
            viewModel.onAction(SettingsContract.Action.TradeNameChanged("T".repeat(300)))
            runCurrent()

            assertEquals(SettingsContract.BUSINESS_NAME_MAX_LENGTH, viewModel.uiState.value.legalName.length)
            assertEquals(SettingsContract.BUSINESS_NAME_MAX_LENGTH, viewModel.uiState.value.tradeName.length)
            assertEquals(
                SettingsContract.BUSINESS_NAME_MAX_LENGTH,
                handle.get<String>("settings.legalName")?.length,
            )
        }

    @Test
    fun `exporta solo despues de elegir content uri y muestra conteos reales`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()
            runCurrent()

            viewModel.effects.test {
                viewModel.onAction(SettingsContract.Action.ExportData)
                runCurrent()

                assertEquals(SettingsContract.Effect.CreateExportDocument, awaitItem())
                assertTrue(exportWriter.writes.isEmpty())

                viewModel.onAction(
                    SettingsContract.Action.ExportDestinationSelected(
                        "content://exports/facturastock.json",
                    ),
                )
                runCurrent()

                assertEquals(1, exportWriter.writes.size)
                assertEquals(
                    SettingsContract.ExportResult.Exported(
                        products = 0,
                        suppliers = 0,
                        units = 0,
                        inventoryLocations = 0,
                        supplierProductAliases = 0,
                        purchases = 0,
                        purchaseLines = 0,
                        inventoryBalances = 0,
                        stockMovements = 0,
                        auditEvents = 0,
                        retainedImageMetadata = 0,
                    ),
                    viewModel.uiState.value.exportResult,
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `fallo SAF sin limpieza confirmada exige borrar el destino manualmente`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()
            exportWriter.result =
                UserDataExportWriteStatus.FAILED_DESTINATION_MAY_CONTAIN_PARTIAL_DATA
            runCurrent()

            viewModel.onAction(
                SettingsContract.Action.ExportDestinationSelected(
                    "content://exports/partial.json",
                ),
            )
            runCurrent()

            assertEquals(
                SettingsContract.ExportResult.DestinationCleanupUnconfirmed,
                viewModel.uiState.value.exportResult,
            )
        }

    @Test
    fun `el perfil visible sigue Room Flow sin pisar una edicion local`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()
            runCurrent()
            val initial = requireNotNull(businesses.findById(realBusinessId))

            businesses.update(initial.copy(legalName = "Bodega actualizada en Room"))
            runCurrent()
            assertEquals("Bodega actualizada en Room", viewModel.uiState.value.legalName)

            viewModel.onAction(SettingsContract.Action.LegalNameChanged("Edición local pendiente"))
            runCurrent()
            businesses.update(
                requireNotNull(businesses.findById(realBusinessId)).copy(
                    tradeName = "Nombre externo",
                ),
            )
            runCurrent()

            assertEquals("Edición local pendiente", viewModel.uiState.value.legalName)
            assertEquals("Nombre externo", viewModel.uiState.value.business?.tradeName)
        }

    @Test
    fun `guardar impuestos no persiste hasta confirmar la advertencia`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(SettingsContract.Action.TaxRateChanged("4"))
            runCurrent()
            viewModel.onAction(SettingsContract.Action.SaveTaxConfiguration)
            runCurrent()

            // La advertencia se muestra y nada se ha escrito todavía.
            assertTrue(viewModel.uiState.value.showTaxWarningDialog)
            assertEquals(TaxRate(BigDecimal("18")), appConfig.current().taxRate)

            viewModel.onAction(SettingsContract.Action.ConfirmTaxConfiguration)
            runCurrent()

            val config = appConfig.current()
            assertEquals(TaxRate(BigDecimal("4")), config.taxRate)
            // El resto de claves queda intacto: la tasa aplica solo a cálculos futuros.
            assertEquals(CostPolicy.NET, config.costPolicy)
            assertEquals(realBusinessId, config.businessId)
            assertTrue(config.onboardingCompleted)
            assertTrue(viewModel.uiState.value.savedFeedback)
        }

    @Test
    fun `guardar perfil con checksum invalido advierte una vez y persiste al reenviar`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(SettingsContract.Action.RucChanged("12345678901"))
            runCurrent()
            viewModel.onAction(SettingsContract.Action.SaveBusinessProfile)
            runCurrent()

            assertTrue(viewModel.uiState.value.rucChecksumWarning)
            assertEquals("20123456786", businesses.findById(realBusinessId)?.ruc)

            viewModel.onAction(SettingsContract.Action.SaveBusinessProfile)
            runCurrent()

            val saved = businesses.findById(realBusinessId)
            assertEquals("12345678901", saved?.ruc)
            assertTrue(viewModel.uiState.value.savedFeedback)
        }

    @Test
    fun `guardar perfil con RUC mal formado no persiste`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(SettingsContract.Action.RucChanged("123"))
            runCurrent()
            viewModel.onAction(SettingsContract.Action.SaveBusinessProfile)
            runCurrent()

            assertEquals("20123456786", businesses.findById(realBusinessId)?.ruc)
            assertFalse(viewModel.uiState.value.savedFeedback)
        }

    @Test
    fun `dos destinos de exportacion en el mismo frame escriben una sola vez`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(
                SettingsContract.Action.ExportDestinationSelected("content://exports/primero.json"),
            )
            viewModel.onAction(
                SettingsContract.Action.ExportDestinationSelected("content://exports/segundo.json"),
            )
            runCurrent()

            assertEquals(listOf("content://exports/primero.json"), exportWriter.writes.map { it.first })
            assertFalse(viewModel.uiState.value.isExporting)

            // El claim se libera al terminar: una exportación posterior vuelve a escribir.
            viewModel.onAction(
                SettingsContract.Action.ExportDestinationSelected("content://exports/tercero.json"),
            )
            runCurrent()
            assertEquals(2, exportWriter.writes.size)
        }

    private fun createViewModel(
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ): SettingsViewModel {
        exportWriter = FakeUserDataExportWriter()
        return SettingsViewModel(
            savedStateHandle = savedStateHandle,
            observeAppConfigurationUseCase = ObserveAppConfigurationUseCase(appConfig),
            businessRepository = businesses,
            updateBusinessProfileUseCase = UpdateBusinessProfileUseCase(businesses),
            updateTaxConfigurationUseCase = UpdateTaxConfigurationUseCase(appConfig),
            writeUserDataExportUseCase = WriteUserDataExportUseCase(
                exportUserData = ExportUserDataUseCase(
                    appConfigurationRepository = appConfig,
                    businessRepository = businesses,
                    productRepository = FakeProductRepository(clock),
                    supplierRepository = FakeSupplierRepository(clock),
                    unitRepository = FakeUnitRepository(clock),
                    inventoryLocationRepository = FakeInventoryLocationRepository(clock),
                    supplierProductAliasRepository = FakeSupplierProductAliasRepository(clock),
                    inventoryReadRepository = FakeInventoryReadRepository(),
                    purchaseReadRepository = FakePurchaseReadRepository(),
                    appClock = clock,
                ),
                writer = exportWriter,
            ),
            dispatcherProvider = dispatchers,
        )
    }
}
