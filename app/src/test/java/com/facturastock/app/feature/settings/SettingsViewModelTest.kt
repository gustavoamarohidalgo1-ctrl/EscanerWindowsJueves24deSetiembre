package com.facturastock.app.feature.settings

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.config.CostPolicy
import com.facturastock.app.domain.config.TaxRate
import com.facturastock.app.domain.model.Business
import com.facturastock.app.domain.model.DemoPurchaseScenario
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.ImageRetentionPolicy
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.UserDataExportWriteStatus
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.repository.DemoInvoiceSource
import com.facturastock.app.domain.usecase.EnterDemoModeUseCase
import com.facturastock.app.domain.usecase.ExportUserDataUseCase
import com.facturastock.app.domain.usecase.ExitDemoModeUseCase
import com.facturastock.app.domain.usecase.ImportDraftImageUseCase
import com.facturastock.app.domain.usecase.ObserveAppConfigurationUseCase
import com.facturastock.app.domain.usecase.StartDemoInvoiceScenarioUseCase
import com.facturastock.app.domain.usecase.RunPrivacyMaintenanceUseCase
import com.facturastock.app.domain.usecase.UpdateBackupEnabledUseCase
import com.facturastock.app.domain.usecase.UpdateBiometricLockEnabledUseCase
import com.facturastock.app.domain.usecase.UpdateBusinessProfileUseCase
import com.facturastock.app.domain.usecase.UpdateDiagnosticsConsentUseCase
import com.facturastock.app.domain.usecase.UpdateDocumentBackupEnabledUseCase
import com.facturastock.app.domain.usecase.UpdateImageRetentionPolicyUseCase
import com.facturastock.app.domain.usecase.UpdateTaxConfigurationUseCase
import com.facturastock.app.domain.usecase.WriteUserDataExportUseCase
import com.facturastock.app.domain.observability.DisabledProductionObservability
import com.facturastock.app.testing.FakeAppConfigurationRepository
import com.facturastock.app.testing.FakeBusinessRepository
import com.facturastock.app.testing.FakeDraftFileStore
import com.facturastock.app.testing.FakeDraftImageImporter
import com.facturastock.app.testing.FakeInventoryLocationRepository
import com.facturastock.app.testing.FakeInventoryReadRepository
import com.facturastock.app.testing.FakeInvoiceDraftRepository
import com.facturastock.app.testing.FakeProductRepository
import com.facturastock.app.testing.FakePurchaseBackupScheduler
import com.facturastock.app.testing.FakePurchaseReadRepository
import com.facturastock.app.testing.FakeRetainedImageStore
import com.facturastock.app.testing.FakeRetentionFileSweep
import com.facturastock.app.testing.FakeSupplierRepository
import com.facturastock.app.testing.FakeSupplierProductAliasRepository
import com.facturastock.app.testing.FakeUnitRepository
import com.facturastock.app.testing.FakeUserDataExportWriter
import com.facturastock.app.testing.MainDispatcherRule
import com.facturastock.app.testing.TestDispatcherProvider
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
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

    private var uuidCounter = 100
    private val uuidGenerator = UuidGenerator {
        UUID.fromString("00000000-0000-0000-0000-%012d".format(++uuidCounter))
    }

    private lateinit var businesses: FakeBusinessRepository
    private lateinit var appConfig: FakeAppConfigurationRepository
    private lateinit var dispatchers: TestDispatcherProvider
    private lateinit var drafts: FakeInvoiceDraftRepository
    private lateinit var imageImporter: FakeDraftImageImporter
    private lateinit var backupScheduler: FakePurchaseBackupScheduler
    private lateinit var exportWriter: FakeUserDataExportWriter
    private lateinit var privacySweep: FakeRetentionFileSweep

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
            assertFalse(state.isDemoMode)
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
    fun `el consentimiento diagnostico persiste opt in y opt out entre ViewModels`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val first = createViewModel()
            runCurrent()
            assertFalse(first.uiState.value.diagnosticsEnabled)

            first.onAction(SettingsContract.Action.DiagnosticsConsentChanged(true))
            runCurrent()
            assertTrue(first.uiState.value.diagnosticsEnabled)
            assertTrue(appConfig.current().diagnosticsEnabled)

            val restoredOptIn = createViewModel()
            runCurrent()
            assertTrue(restoredOptIn.uiState.value.diagnosticsEnabled)

            restoredOptIn.onAction(SettingsContract.Action.DiagnosticsConsentChanged(false))
            runCurrent()
            assertFalse(restoredOptIn.uiState.value.diagnosticsEnabled)
            assertFalse(appConfig.current().diagnosticsEnabled)

            val restoredOptOut = createViewModel()
            runCurrent()
            assertFalse(restoredOptOut.uiState.value.diagnosticsEnabled)
        }

    @Test
    fun `respaldo y documentos requieren opt in y cancelan trabajo al apagar`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()
            runCurrent()
            assertFalse(viewModel.uiState.value.backupEnabled)
            assertFalse(viewModel.uiState.value.documentBackupEnabled)

            viewModel.onAction(SettingsContract.Action.BackupEnabledChanged(true))
            runCurrent()
            assertTrue(appConfig.current().backupEnabled)
            assertEquals(1, backupScheduler.enqueueCount)

            viewModel.onAction(SettingsContract.Action.DocumentBackupEnabledChanged(true))
            runCurrent()
            assertTrue(appConfig.current().documentBackupEnabled)

            viewModel.onAction(SettingsContract.Action.BackupEnabledChanged(false))
            runCurrent()
            assertFalse(appConfig.current().backupEnabled)
            assertEquals(1, backupScheduler.cancelAllCount)
            assertEquals(
                SettingsContract.PrivacyResult.BackupUpdated(
                    enabled = false,
                    schedulerUpdated = true,
                ),
                viewModel.uiState.value.privacyResult,
            )
        }

    @Test
    fun `fallo del scheduler no revierte preferencia y queda visible`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()
            runCurrent()
            backupScheduler.enqueueFailure = IllegalStateException("sin espacio")

            viewModel.onAction(SettingsContract.Action.BackupEnabledChanged(true))
            runCurrent()

            assertTrue(appConfig.current().backupEnabled)
            assertEquals(
                SettingsContract.PrivacyResult.BackupUpdated(
                    enabled = true,
                    schedulerUpdated = false,
                ),
                viewModel.uiState.value.privacyResult,
            )
        }

    @Test
    fun `dos toggles en el mismo frame conservan solo la primera preferencia reclamada`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()
            runCurrent()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            backupScheduler.beforeEnqueue = {
                started.complete(Unit)
                release.await()
            }

            viewModel.onAction(SettingsContract.Action.BackupEnabledChanged(true))
            viewModel.onAction(SettingsContract.Action.BackupEnabledChanged(false))
            runCurrent()
            started.await()

            assertTrue(viewModel.uiState.value.isSaving)
            assertEquals(0, backupScheduler.cancelAllCount)
            release.complete(Unit)
            runCurrent()

            assertTrue(appConfig.current().backupEnabled)
            assertEquals(1, backupScheduler.enqueueCount)
            assertEquals(0, backupScheduler.cancelAllCount)
            assertFalse(viewModel.uiState.value.isSaving)
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
                    SettingsContract.PrivacyResult.Exported(
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
                    viewModel.uiState.value.privacyResult,
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
                SettingsContract.PrivacyResult.ExportDestinationCleanupUnconfirmed,
                viewModel.uiState.value.privacyResult,
            )
        }

    @Test
    fun `politica destructiva espera confirmacion y ejecuta mantenimiento inmediato`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()
            runCurrent()
            assertEquals(
                ImageRetentionPolicy.KEEP,
                appConfig.current().imageRetentionPolicy,
            )

            viewModel.onAction(
                SettingsContract.Action.ImageRetentionPolicySelected(
                    ImageRetentionPolicy.DAYS_30,
                ),
            )
            runCurrent()

            assertEquals(
                ImageRetentionPolicy.DAYS_30,
                viewModel.uiState.value.pendingImageRetentionPolicy,
            )
            assertEquals(
                ImageRetentionPolicy.KEEP,
                appConfig.current().imageRetentionPolicy,
            )
            assertNull(privacySweep.lastStaleImportsNow)

            viewModel.onAction(SettingsContract.Action.ConfirmImageRetentionPolicy)
            runCurrent()

            assertEquals(
                ImageRetentionPolicy.DAYS_30,
                appConfig.current().imageRetentionPolicy,
            )
            assertEquals(clock.now(), privacySweep.lastStaleImportsNow)
            val result = viewModel.uiState.value.privacyResult
            assertTrue(result is SettingsContract.PrivacyResult.RetentionPolicyUpdated)
            result as SettingsContract.PrivacyResult.RetentionPolicyUpdated
            assertTrue(result.maintenanceAttempted)
            assertFalse(requireNotNull(result.report).hasUnconfirmedLocalWork)
        }

    @Test
    fun `doble confirmacion de borrado en el mismo frame reclama una sola operacion`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()
            runCurrent()
            viewModel.onAction(SettingsContract.Action.DeleteImages)
            runCurrent()

            viewModel.onAction(SettingsContract.Action.ConfirmDeleteImages)
            viewModel.onAction(SettingsContract.Action.ConfirmDeleteImages)
            runCurrent()

            assertEquals(1, appConfig.forceImageDeletionRequestCount)
            assertTrue(
                viewModel.uiState.value.privacyResult is SettingsContract.PrivacyResult.ImagesDeleted,
            )

            // Al terminar con éxito el claim queda disponible para una intención posterior.
            viewModel.onAction(SettingsContract.Action.ConfirmDeleteImages)
            runCurrent()
            assertEquals(2, appConfig.forceImageDeletionRequestCount)
        }

    @Test
    fun `fallo de borrado libera el claim y permite reintento`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()
            runCurrent()
            appConfig.nextForceImageDeletionFailure = IllegalStateException("fallo sintético")

            viewModel.onAction(SettingsContract.Action.ConfirmDeleteImages)
            runCurrent()
            assertEquals(1, appConfig.forceImageDeletionRequestCount)
            assertEquals(
                SettingsContract.PrivacyResult.Failed,
                viewModel.uiState.value.privacyResult,
            )

            viewModel.onAction(SettingsContract.Action.ConfirmDeleteImages)
            runCurrent()
            assertEquals(2, appConfig.forceImageDeletionRequestCount)
            assertTrue(
                viewModel.uiState.value.privacyResult is SettingsContract.PrivacyResult.ImagesDeleted,
            )
        }

    @Test
    fun `cancelacion de borrado limpia busy libera claim y permite reintento`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()
            runCurrent()
            appConfig.nextForceImageDeletionCancellation =
                CancellationException("cancelación sintética")

            viewModel.onAction(SettingsContract.Action.ConfirmDeleteImages)
            runCurrent()
            assertEquals(1, appConfig.forceImageDeletionRequestCount)
            assertNull(viewModel.uiState.value.privacyOperation)

            viewModel.onAction(SettingsContract.Action.ConfirmDeleteImages)
            runCurrent()
            assertEquals(2, appConfig.forceImageDeletionRequestCount)
            assertTrue(
                viewModel.uiState.value.privacyResult is SettingsContract.PrivacyResult.ImagesDeleted,
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
    fun `entrar y salir del modo demo recarga el negocio activo`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(SettingsContract.Action.EnterDemoMode)
            runCurrent()
            assertTrue(viewModel.uiState.value.showDemoEnterDialog)

            viewModel.onAction(SettingsContract.Action.ConfirmEnterDemoMode)
            runCurrent()

            val inDemo = viewModel.uiState.value
            assertTrue(inDemo.isDemoMode)
            assertEquals("[DEMO] Bodega de demostración", inDemo.business?.legalName)
            assertNull(inDemo.business?.ruc)
            assertTrue(appConfig.current().isDemoMode)

            viewModel.onAction(SettingsContract.Action.ExitDemoMode)
            runCurrent()
            assertTrue(viewModel.uiState.value.showDemoExitDialog)

            viewModel.onAction(SettingsContract.Action.ConfirmExitDemoMode)
            runCurrent()

            val outDemo = viewModel.uiState.value
            assertFalse(outDemo.isDemoMode)
            assertEquals("Bodega Real SAC", outDemo.business?.legalName)
            assertEquals(realBusinessId, appConfig.current().activeBusinessId)
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
    fun `doble toque inicia una sola importacion demo y navega una vez`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()
            runCurrent()
            viewModel.onAction(SettingsContract.Action.EnterDemoMode)
            viewModel.onAction(SettingsContract.Action.ConfirmEnterDemoMode)
            runCurrent()

            viewModel.effects.test {
                viewModel.onAction(SettingsContract.Action.StartDemoScenario)
                viewModel.onAction(SettingsContract.Action.StartDemoScenario)
                runCurrent()

                assertTrue(awaitItem() is SettingsContract.Effect.OpenDemoCapture)
                expectNoEvents()
                assertEquals(1, imageImporter.bytesCalls.size)
                val demoBusinessId = requireNotNull(appConfig.current().demoBusinessId)
                val demoDraft = drafts.findDraft(DemoPurchaseScenario.draftIdFor(demoBusinessId))
                assertEquals(demoBusinessId, demoDraft?.businessId)
                assertEquals(DraftStatus.CAPTURED, demoDraft?.status)
                assertFalse(viewModel.uiState.value.isStartingDemoScenario)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `si el modo demo desaparece durante el inicio no navega`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()
            runCurrent()
            viewModel.onAction(SettingsContract.Action.EnterDemoMode)
            viewModel.onAction(SettingsContract.Action.ConfirmEnterDemoMode)
            runCurrent()
            assertTrue(viewModel.uiState.value.isDemoMode)

            viewModel.effects.test {
                appConfig.exitDemoMode()
                viewModel.onAction(SettingsContract.Action.StartDemoScenario)
                runCurrent()

                expectNoEvents()
                assertEquals(
                    SettingsContract.DemoScenarioFailure.NOT_IN_DEMO_MODE,
                    viewModel.uiState.value.demoScenarioFailure,
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `conflicto de pertenencia del borrador demo no navega`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()
            runCurrent()
            viewModel.onAction(SettingsContract.Action.EnterDemoMode)
            viewModel.onAction(SettingsContract.Action.ConfirmEnterDemoMode)
            runCurrent()
            val demoBusinessId = requireNotNull(appConfig.current().demoBusinessId)
            drafts.createDraft(
                InvoiceDraft(
                    draftId = DemoPurchaseScenario.draftIdFor(demoBusinessId),
                    businessId = realBusinessId,
                    createdAt = clock.now(),
                    updatedAt = clock.now(),
                ),
            )

            viewModel.effects.test {
                viewModel.onAction(SettingsContract.Action.StartDemoScenario)
                runCurrent()

                expectNoEvents()
                assertEquals(
                    SettingsContract.DemoScenarioFailure.CONFLICT,
                    viewModel.uiState.value.demoScenarioFailure,
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `escenario ya publicado abre la compra en vez de recrear el borrador`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()
            runCurrent()
            viewModel.onAction(SettingsContract.Action.EnterDemoMode)
            viewModel.onAction(SettingsContract.Action.ConfirmEnterDemoMode)
            runCurrent()
            val demoBusinessId = requireNotNull(appConfig.current().demoBusinessId)
            val purchaseId = PurchaseId.from(
                UUID.fromString("90000000-0000-4000-8000-000000000009"),
            )
            drafts.createDraft(
                InvoiceDraft(
                    draftId = DemoPurchaseScenario.draftIdFor(demoBusinessId),
                    businessId = demoBusinessId,
                    confirmedPurchaseId = purchaseId,
                    createdAt = clock.now(),
                    updatedAt = clock.now(),
                ),
            )

            viewModel.effects.test {
                viewModel.onAction(SettingsContract.Action.StartDemoScenario)
                runCurrent()

                assertEquals(
                    SettingsContract.Effect.OpenDemoPurchase(purchaseId),
                    awaitItem(),
                )
                assertEquals(0, imageImporter.bytesCalls.size)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `abrir politica de privacidad emite un efecto unico`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()
            runCurrent()

            viewModel.effects.test {
                viewModel.onAction(SettingsContract.Action.OpenPrivacyPolicy)
                runCurrent()

                assertEquals(SettingsContract.Effect.OpenPrivacyPolicy, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    private fun createViewModel(
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ): SettingsViewModel {
        val suppliers = FakeSupplierRepository(clock)
        val units = FakeUnitRepository(clock)
        val locations = FakeInventoryLocationRepository(clock)
        val products = FakeProductRepository(clock)
        val aliases = FakeSupplierProductAliasRepository(clock)
        val inventoryReads = FakeInventoryReadRepository()
        val purchaseReads = FakePurchaseReadRepository()
        drafts = FakeInvoiceDraftRepository(clock)
        imageImporter = FakeDraftImageImporter()
        backupScheduler = FakePurchaseBackupScheduler()
        exportWriter = FakeUserDataExportWriter()
        privacySweep = FakeRetentionFileSweep()
        val importDraftImageUseCase = ImportDraftImageUseCase(
            appConfigurationRepository = appConfig,
            invoiceDraftRepository = drafts,
            draftImageImporter = imageImporter,
            draftFileStore = FakeDraftFileStore(),
            uuidGenerator = uuidGenerator,
        )
        return SettingsViewModel(
            savedStateHandle = savedStateHandle,
            observeAppConfigurationUseCase = ObserveAppConfigurationUseCase(appConfig),
            businessRepository = businesses,
            updateBusinessProfileUseCase = UpdateBusinessProfileUseCase(businesses),
            updateTaxConfigurationUseCase = UpdateTaxConfigurationUseCase(appConfig),
            enterDemoModeUseCase = EnterDemoModeUseCase(
                businessRepository = businesses,
                supplierRepository = suppliers,
                unitRepository = units,
                inventoryLocationRepository = locations,
                productRepository = products,
                appConfigurationRepository = appConfig,
                uuidGenerator = uuidGenerator,
                appClock = clock,
            ),
            exitDemoModeUseCase = ExitDemoModeUseCase(
                appConfigurationRepository = appConfig,
                businessRepository = businesses,
                purchaseBackupScheduler = backupScheduler,
            ),
            startDemoInvoiceScenarioUseCase = StartDemoInvoiceScenarioUseCase(
                appConfigurationRepository = appConfig,
                invoiceDraftRepository = drafts,
                demoInvoiceSource = DemoInvoiceSource { byteArrayOf(1, 2, 3) },
                importDraftImageUseCase = importDraftImageUseCase,
                appClock = clock,
            ),
            updateDiagnosticsConsentUseCase = UpdateDiagnosticsConsentUseCase(
                appConfigurationRepository = appConfig,
                observability = DisabledProductionObservability,
            ),
            updateImageRetentionPolicyUseCase = UpdateImageRetentionPolicyUseCase(appConfig),
            updateBackupEnabledUseCase = UpdateBackupEnabledUseCase(
                appConfigurationRepository = appConfig,
                purchaseBackupScheduler = backupScheduler,
            ),
            updateDocumentBackupEnabledUseCase =
                UpdateDocumentBackupEnabledUseCase(appConfig),
            updateBiometricLockEnabledUseCase = UpdateBiometricLockEnabledUseCase(appConfig),
            writeUserDataExportUseCase = WriteUserDataExportUseCase(
                exportUserData = ExportUserDataUseCase(
                    appConfigurationRepository = appConfig,
                    businessRepository = businesses,
                    productRepository = products,
                    supplierRepository = suppliers,
                    unitRepository = units,
                    inventoryLocationRepository = locations,
                    supplierProductAliasRepository = aliases,
                    inventoryReadRepository = inventoryReads,
                    purchaseReadRepository = purchaseReads,
                    appClock = clock,
                ),
                writer = exportWriter,
            ),
            runPrivacyMaintenanceUseCase = RunPrivacyMaintenanceUseCase(
                appConfigurationRepository = appConfig,
                invoiceDraftRepository = drafts,
                purchaseReadRepository = purchaseReads,
                retentionFileSweep = privacySweep,
                draftFileStore = FakeDraftFileStore(),
                retainedImageStore = FakeRetainedImageStore(),
                appClock = clock,
            ),
            dispatcherProvider = dispatchers,
        )
    }
}
