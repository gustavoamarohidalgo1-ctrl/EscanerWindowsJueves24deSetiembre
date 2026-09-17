package com.facturastock.app.feature

import androidx.lifecycle.SavedStateHandle
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.model.InventoryDiagnosticReport
import com.facturastock.app.domain.model.InventoryProductDetail
import com.facturastock.app.domain.model.InventoryReadItem
import com.facturastock.app.domain.model.ProductInventorySummary
import com.facturastock.app.domain.model.ProductProfit
import com.facturastock.app.domain.repository.ProductInventoryRepository
import com.facturastock.app.domain.repository.DemoInvoiceSource
import com.facturastock.app.domain.repository.InventoryReadRepository
import com.facturastock.app.domain.repository.ProductProfitRepository
import com.facturastock.app.domain.repository.DisabledDocumentBackupLifecycleRepository
import com.facturastock.app.domain.repository.RemoteDocumentArchive
import com.facturastock.app.domain.repository.RemoteDocumentDownloadResult
import com.facturastock.app.domain.repository.RemoteDocumentReference
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.usecase.CompleteOnboardingUseCase
import com.facturastock.app.domain.usecase.BindCloudBusinessLinkUseCase
import com.facturastock.app.domain.usecase.DeleteDraftUseCase
import com.facturastock.app.domain.usecase.EnterDemoModeUseCase
import com.facturastock.app.domain.usecase.ExportUserDataUseCase
import com.facturastock.app.domain.usecase.EnterManualInvoiceReviewUseCase
import com.facturastock.app.domain.usecase.ExitDemoModeUseCase
import com.facturastock.app.domain.usecase.FindDraftFirstImageUseCase
import com.facturastock.app.domain.usecase.ImportDraftImageUseCase
import com.facturastock.app.domain.usecase.ImportScannedInvoiceProductsUseCase
import com.facturastock.app.domain.usecase.DiagnoseInventoryUseCase
import com.facturastock.app.domain.usecase.ObserveInventoryProductUseCase
import com.facturastock.app.domain.usecase.ObserveInventoryUseCase
import com.facturastock.app.domain.usecase.ObserveProductProfitsUseCase
import com.facturastock.app.domain.usecase.ObserveAppConfigurationUseCase
import com.facturastock.app.domain.usecase.ObserveDraftImagesUseCase
import com.facturastock.app.domain.usecase.ObserveInvoiceDraftUseCase
import com.facturastock.app.domain.usecase.ObservePurchaseDetailUseCase
import com.facturastock.app.domain.usecase.ObservePurchaseHistoryUseCase
import com.facturastock.app.domain.usecase.LoadRemotePurchaseDocumentUseCase
import com.facturastock.app.domain.usecase.ReadRetainedImageUseCase
import com.facturastock.app.domain.usecase.ObservePurchasesUseCase
import com.facturastock.app.domain.usecase.RetryPurchaseBackupUseCase
import com.facturastock.app.domain.usecase.RetrySyncOperationUseCase
import com.facturastock.app.domain.usecase.ObserveHomeDashboardUseCase
import com.facturastock.app.domain.usecase.ParseInvoiceUseCase
import com.facturastock.app.domain.usecase.PullRemoteChangesUseCase
import com.facturastock.app.domain.usecase.ReconcileRemoteLedgerUseCase
import com.facturastock.app.domain.usecase.RecordReconciliationReviewUseCase
import com.facturastock.app.domain.usecase.ResolveSyncConflictUseCase
import com.facturastock.app.domain.usecase.RetryDraftOcrUseCase
import com.facturastock.app.domain.usecase.RunInvoiceOcrUseCase
import com.facturastock.app.domain.usecase.RunPrivacyMaintenanceUseCase
import com.facturastock.app.domain.usecase.RunDraftStageUseCase
import com.facturastock.app.domain.usecase.AuthorizePurchaseDuplicateOverrideUseCase
import com.facturastock.app.domain.usecase.ApplyImageRetentionAfterConfirmUseCase
import com.facturastock.app.domain.usecase.ApplyImageRetentionAfterOcrUseCase
import com.facturastock.app.domain.usecase.CheckPurchaseDuplicateUseCase
import com.facturastock.app.domain.usecase.ConfirmPurchaseUseCase
import com.facturastock.app.domain.usecase.StartDemoInvoiceScenarioUseCase
import com.facturastock.app.domain.usecase.StartInvoiceDraftUseCase
import com.facturastock.app.domain.usecase.UpdateBusinessProfileUseCase
import com.facturastock.app.domain.usecase.UpdateBackupEnabledUseCase
import com.facturastock.app.domain.usecase.UpdateBiometricLockEnabledUseCase
import com.facturastock.app.domain.usecase.UpdateDiagnosticsConsentUseCase
import com.facturastock.app.domain.usecase.UpdateDocumentBackupEnabledUseCase
import com.facturastock.app.domain.usecase.UpdateImageRetentionPolicyUseCase
import com.facturastock.app.domain.usecase.UpdateTaxConfigurationUseCase
import com.facturastock.app.domain.usecase.UpdateProductSalePriceUseCase
import com.facturastock.app.domain.usecase.WriteUserDataExportUseCase
import com.facturastock.app.testing.FakeCloudBusinessBindingRepository
import com.facturastock.app.domain.observability.DisabledProductionObservability
import com.facturastock.app.feature.account.AccountContract
import com.facturastock.app.feature.account.AccountViewModel
import com.facturastock.app.feature.account.invitations.InvitationsContract
import com.facturastock.app.feature.account.invitations.InvitationsViewModel
import com.facturastock.app.feature.account.members.MembersContract
import com.facturastock.app.feature.account.members.MembersViewModel
import com.facturastock.app.feature.capture.CaptureContract
import com.facturastock.app.feature.capture.CaptureViewModel
import com.facturastock.app.feature.catalogs.CatalogsContract
import com.facturastock.app.feature.catalogs.CatalogsViewModel
import com.facturastock.app.feature.common.RouteArgumentKeys
import com.facturastock.app.feature.home.HomeContract
import com.facturastock.app.feature.home.HomeViewModel
import com.facturastock.app.feature.inventory.InventoryContract
import com.facturastock.app.feature.inventory.InventoryViewModel
import com.facturastock.app.feature.ocr.OcrContract
import com.facturastock.app.feature.ocr.OcrViewModel
import com.facturastock.app.feature.onboarding.OnboardingContract
import com.facturastock.app.feature.onboarding.OnboardingViewModel
import com.facturastock.app.feature.preparation.PreparationContract
import com.facturastock.app.feature.preparation.PreparationViewModel
import com.facturastock.app.feature.purchases.PurchasesContract
import com.facturastock.app.feature.purchases.PurchasesViewModel
import com.facturastock.app.feature.review.ReviewContract
import com.facturastock.app.feature.review.ReviewViewModel
import com.facturastock.app.feature.root.AppGateViewModel
import com.facturastock.app.feature.root.GateState
import com.facturastock.app.feature.settings.SettingsContract
import com.facturastock.app.feature.settings.SettingsViewModel
import com.facturastock.app.feature.sync.SyncContract
import com.facturastock.app.feature.sync.SyncViewModel
import com.facturastock.app.testing.ControlledDraftWorkflowRepository
import com.facturastock.app.testing.ControlledPurchasePostingRepository
import com.facturastock.app.testing.FakeAccountRepository
import com.facturastock.app.testing.FakeAppConfigurationRepository
import com.facturastock.app.testing.FakeAuditTrailRepository
import com.facturastock.app.testing.FakeBusinessMembershipRepository
import com.facturastock.app.testing.FakeBusinessRepository
import com.facturastock.app.testing.FakeHomeDashboardReadRepository
import com.facturastock.app.testing.FakeDraftFileStore
import com.facturastock.app.testing.FakeDraftImageImporter
import com.facturastock.app.testing.FakeInventoryLocationRepository
import com.facturastock.app.testing.FakeOnboardingProvisioningRepository
import com.facturastock.app.testing.FakeInventoryReadRepository
import com.facturastock.app.testing.FakeInvoiceDraftRepository
import com.facturastock.app.testing.FakeInvoiceImagePreprocessor
import com.facturastock.app.testing.FakeInvoiceOcrSnapshotRepository
import com.facturastock.app.testing.FakeInvoiceTextRecognizer
import com.facturastock.app.testing.FakeManualInvoiceReviewRepository
import com.facturastock.app.testing.FakeParsedInvoiceRepository
import com.facturastock.app.testing.FakeProductRepository
import com.facturastock.app.testing.FakePreparedPurchaseRepository
import com.facturastock.app.testing.FakePurchaseOverrideAuthorizationRepository
import com.facturastock.app.testing.FakePurchaseReadRepository
import com.facturastock.app.testing.FakePurchaseBackupOutboxRepository
import com.facturastock.app.testing.FakePurchaseBackupRepository
import com.facturastock.app.testing.FakePurchaseBackupScheduler
import com.facturastock.app.testing.FakePurchaseRepository
import com.facturastock.app.testing.FakeRemoteLedgerRepository
import com.facturastock.app.testing.FakeRetainedImageStore
import com.facturastock.app.testing.FakeRecentDraftReadRepository
import com.facturastock.app.testing.FakeRetentionFileSweep
import com.facturastock.app.testing.FakeSupplierRepository
import com.facturastock.app.testing.FakeSupplierProductAliasRepository
import com.facturastock.app.testing.FakeSyncCursorRepository
import com.facturastock.app.testing.FakeSyncReconciliationRepository
import com.facturastock.app.testing.FakeUnitRepository
import com.facturastock.app.testing.FakeUserDataExportWriter
import com.facturastock.app.testing.MainDispatcherRule
import com.facturastock.app.testing.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class ViewModelInitialStateTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `HomeViewModel exposes its initial state before loading`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val clock = AppClock { NOW }
            val dispatchers = TestDispatcherProvider(mainDispatcherRule.dispatcher)
            val appConfig = FakeAppConfigurationRepository()
            val drafts = FakeInvoiceDraftRepository(clock)
            val suppliers = FakeSupplierRepository(clock)
            val viewModel = HomeViewModel(
                SavedStateHandle(),
                ObserveHomeDashboardUseCase(
                    configurationRepository = appConfig,
                    businessRepository = FakeBusinessRepository(clock),
                    dashboardReadRepository = FakeHomeDashboardReadRepository(
                        FakeRecentDraftReadRepository(drafts, suppliers),
                    ),
                    clock = clock,
                ),
                DeleteDraftUseCase(drafts, FakeDraftFileStore()),
                RetryDraftOcrUseCase(drafts),
                FindDraftFirstImageUseCase(drafts),
                StartInvoiceDraftUseCase(appConfig, drafts, clock),
                UuidGenerator { DRAFT_UUID },
                dispatchers,
            )

            assertEquals(HomeContract.State(), viewModel.uiState.value)

            runCurrent()
            // Sin negocio activo el panel queda disponible, vacío y sin abrir datos de tenant.
            assertFalse(viewModel.uiState.value.isLoading)
            assertEquals(emptyList<HomeContract.HomeDraftItem>(), viewModel.uiState.value.drafts)
            assertNotNull(viewModel.uiState.value.dashboard)
            assertEquals(null, viewModel.uiState.value.dashboard?.business)
        }

    @Test
    fun `CatalogsViewModel exposes its initial state before loading`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val clock = AppClock { NOW }
            val dispatchers = TestDispatcherProvider(mainDispatcherRule.dispatcher)
            val appConfig = FakeAppConfigurationRepository()
            val viewModel = CatalogsViewModel(
                SavedStateHandle(),
                ObserveAppConfigurationUseCase(appConfig),
                FakeProductRepository(clock),
                FakeSupplierRepository(clock),
                FakeUnitRepository(clock),
                FakeInventoryLocationRepository(clock),
                FakeSupplierProductAliasRepository(clock),
                object : ProductInventoryRepository {
                    override suspend fun summaryForProduct(
                        businessId: com.facturastock.app.domain.model.id.BusinessId,
                        productId: com.facturastock.app.domain.model.id.ProductId,
                    ) = ProductInventorySummary(emptyList())
                },
                UuidGenerator { DRAFT_UUID },
                clock,
                dispatchers,
                object : com.facturastock.app.domain.repository.ProductRegistrationRepository {
                    override suspend fun register(
                        product: com.facturastock.app.domain.model.Product,
                        quantity: java.math.BigDecimal,
                        unitCost: com.facturastock.app.domain.model.UnitCost,
                    ): com.facturastock.app.domain.model.CatalogMutationResult<com.facturastock.app.domain.model.Product> =
                        error("No se registra un producto al cargar el estado inicial")
                },
                object : com.facturastock.app.domain.repository.ProductEditingRepository {
                    override suspend fun load(
                        businessId: com.facturastock.app.domain.model.id.BusinessId,
                        productId: com.facturastock.app.domain.model.id.ProductId,
                        defaultCurrency: com.facturastock.app.domain.model.CurrencyCode,
                    ): com.facturastock.app.domain.repository.ProductEditingSnapshot? =
                        error("No se abre un editor de producto al cargar el estado inicial")

                    override suspend fun save(
                        expected: com.facturastock.app.domain.repository.ProductEditingSnapshot,
                        candidate: com.facturastock.app.domain.model.Product,
                        stockEdits: List<com.facturastock.app.domain.repository.ProductStockEdit>,
                    ): com.facturastock.app.domain.repository.ProductEditingResult =
                        error("No se edita un producto al cargar el estado inicial")
                },
            )

            assertEquals(CatalogsContract.State(), viewModel.uiState.value)

            runCurrent()
        }

    @Test
    fun `CaptureViewModel restores its typed initial route state`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val clock = AppClock { NOW }
            val dispatchers = TestDispatcherProvider(mainDispatcherRule.dispatcher)
            val drafts = FakeInvoiceDraftRepository(clock)
            val viewModel = CaptureViewModel(
                savedStateHandle = SavedStateHandle(
                    mapOf(RouteArgumentKeys.DRAFT_ID to DRAFT_ID.value),
                ),
                importDraftImageUseCase = ImportDraftImageUseCase(
                    appConfigurationRepository = FakeAppConfigurationRepository(),
                    invoiceDraftRepository = drafts,
                    draftImageImporter = FakeDraftImageImporter(),
                    draftFileStore = FakeDraftFileStore(),
                    uuidGenerator = UuidGenerator { DRAFT_UUID },
                ),
                observeInvoiceDraftUseCase = ObserveInvoiceDraftUseCase(drafts),
                observeDraftImagesUseCase = ObserveDraftImagesUseCase(drafts),
                uuidGenerator = UuidGenerator { DRAFT_UUID },
                dispatcherProvider = dispatchers,
            )

            assertEquals(
                CaptureContract.State(draftId = DRAFT_ID),
                viewModel.uiState.value,
            )
        }

    @Test
    fun `OcrViewModel restores its typed initial route state`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val dispatchers = TestDispatcherProvider(mainDispatcherRule.dispatcher)
            val clock = AppClock { NOW }
            val drafts = FakeInvoiceDraftRepository(clock)
            val snapshots = FakeInvoiceOcrSnapshotRepository(drafts)
            val appConfiguration = FakeAppConfigurationRepository()
            val businesses = FakeBusinessRepository(clock)
            val products = FakeProductRepository(clock)
            val viewModel = OcrViewModel(
                savedStateHandle = SavedStateHandle(
                    mapOf(RouteArgumentKeys.DRAFT_ID to DRAFT_ID.value),
                ),
                runInvoiceOcrUseCase = RunInvoiceOcrUseCase(
                    invoiceDraftRepository = drafts,
                    invoiceImagePreprocessor = FakeInvoiceImagePreprocessor(),
                    invoiceTextRecognizer = FakeInvoiceTextRecognizer(),
                    invoiceOcrSnapshotRepository = snapshots,
                    uuidGenerator = UuidGenerator { DRAFT_UUID },
                    appClock = clock,
                    applyImageRetentionAfterOcr = ApplyImageRetentionAfterOcrUseCase(
                        FakeAppConfigurationRepository(),
                        FakeDraftFileStore(),
                        drafts,
                    ),
                ),
                parseInvoiceUseCase = ParseInvoiceUseCase(
                    invoiceDraftRepository = drafts,
                    invoiceOcrSnapshotRepository = snapshots,
                    parsedInvoiceRepository = FakeParsedInvoiceRepository(drafts),
                    appConfigurationRepository = appConfiguration,
                    businessRepository = businesses,
                    appClock = clock,
                    dispatcherProvider = dispatchers,
                ),
                importScannedInvoiceProductsUseCase = ImportScannedInvoiceProductsUseCase(
                    appConfigurationRepository = appConfiguration,
                    businessRepository = businesses,
                    invoiceDraftRepository = drafts,
                    productRepository = products,
                    unitRepository = FakeUnitRepository(clock, products),
                    uuidGenerator = UuidGenerator { DRAFT_UUID },
                    appClock = clock,
                    deleteDraftUseCase = DeleteDraftUseCase(drafts, FakeDraftFileStore()),
                ),
                enterManualInvoiceReviewUseCase = EnterManualInvoiceReviewUseCase(
                    FakeManualInvoiceReviewRepository(drafts),
                ),
                observeInvoiceDraftUseCase = ObserveInvoiceDraftUseCase(drafts),
                retryDraftOcrUseCase = RetryDraftOcrUseCase(drafts),
                appClock = clock,
                dispatcherProvider = dispatchers,
            )

            assertEquals(
                OcrContract.State(draftId = DRAFT_ID),
                viewModel.uiState.value,
            )
        }

    @Test
    fun `ReviewViewModel restores its typed initial route state`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val dispatchers = TestDispatcherProvider(mainDispatcherRule.dispatcher)
            val viewModel = ReviewViewModel(
                SavedStateHandle(
                    mapOf(
                        RouteArgumentKeys.DRAFT_ID to DRAFT_ID.value,
                        RouteArgumentKeys.LINE_ID to LINE_ID.value,
                    ),
                ),
                RunDraftStageUseCase(ControlledDraftWorkflowRepository()),
                dispatchers,
            )

            assertEquals(
                ReviewContract.State(
                    draftId = DRAFT_ID,
                    lineId = LINE_ID,
                ),
                viewModel.uiState.value,
            )
        }

    @Test
    fun `PreparationViewModel restores its typed initial route state`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val dispatchers = TestDispatcherProvider(mainDispatcherRule.dispatcher)
            val drafts = FakeInvoiceDraftRepository(AppClock { NOW })
            val prepared = FakePreparedPurchaseRepository(drafts, AppClock { NOW })
            val purchases = FakePurchaseRepository()
            val checkDuplicates = CheckPurchaseDuplicateUseCase(prepared, drafts, purchases)
            val viewModel = PreparationViewModel(
                SavedStateHandle(
                    mapOf(
                        RouteArgumentKeys.DRAFT_ID to DRAFT_ID.value,
                        RouteArgumentKeys.EXPECTED_PREPARED_HASH to PREPARED_HASH,
                    ),
                ),
                ConfirmPurchaseUseCase(
                    FakeAppConfigurationRepository(),
                    ControlledPurchasePostingRepository(),
                    FakePurchaseBackupScheduler(),
                    ApplyImageRetentionAfterConfirmUseCase(
                        FakeAppConfigurationRepository(),
                        FakeDraftFileStore(),
                    ),
                ),
                checkDuplicates,
                AuthorizePurchaseDuplicateOverrideUseCase(
                    checkDuplicates,
                    FakePurchaseOverrideAuthorizationRepository(),
                ),
                ObserveInvoiceDraftUseCase(drafts),
                dispatchers,
            )

            assertEquals(
                PreparationContract.State(
                    draftId = DRAFT_ID,
                    expectedPreparedLogicalHash = PREPARED_HASH,
                    isCheckingDuplicates = true,
                ),
                viewModel.uiState.value,
            )
        }

    @Test
    fun `PurchasesViewModel restores its initial state before loading`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val configuration = FakeAppConfigurationRepository()
            val purchases = FakePurchaseReadRepository()
            val dispatchers = TestDispatcherProvider(mainDispatcherRule.dispatcher)
            val viewModel = PurchasesViewModel(
                SavedStateHandle(
                    mapOf(RouteArgumentKeys.PURCHASE_ID to PURCHASE_ID.value),
                ),
                ObservePurchaseHistoryUseCase(configuration, purchases),
                ObservePurchaseDetailUseCase(configuration, purchases),
                ReadRetainedImageUseCase(FakeRetainedImageStore()),
                LoadRemotePurchaseDocumentUseCase(
                    configuration,
                    DisabledDocumentBackupLifecycleRepository,
                    UnavailableRemoteDocumentArchiveForTest,
                ),
                RetryPurchaseBackupUseCase(
                    configuration,
                    FakePurchaseBackupRepository(),
                    FakePurchaseBackupScheduler(),
                ),
                dispatchers,
            )

            assertEquals(
                PurchasesContract.State(purchaseId = PURCHASE_ID),
                viewModel.uiState.value,
            )

            runCurrent()
            // El detalle intenta cargar la compra tipada; el fake vacío responde no encontrado.
            runCurrent()
        }

    @Test
    fun `InventoryViewModel restores search before observing Room`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val configuration = FakeAppConfigurationRepository()
            val repository = object : InventoryReadRepository {
                override fun observeInventory(
                    businessId: BusinessId,
                ): Flow<List<InventoryReadItem>> = flowOf(emptyList())

                override fun observeProduct(
                    businessId: BusinessId,
                    productId: ProductId,
                ): Flow<InventoryProductDetail?> = flowOf(null)

                override suspend fun diagnose(
                    businessId: BusinessId,
                ): InventoryDiagnosticReport = InventoryDiagnosticReport(NOW, emptyList())
            }
            val dispatchers = TestDispatcherProvider(mainDispatcherRule.dispatcher)
            val products = FakeProductRepository(AppClock { NOW })
            val profits = object : ProductProfitRepository {
                override fun observeForBusiness(
                    businessId: BusinessId,
                ): Flow<List<ProductProfit>> = flowOf(emptyList())
            }
            val viewModel = InventoryViewModel(
                savedStateHandle = SavedStateHandle(mapOf("inventory.query" to "café")),
                observeInventory = ObserveInventoryUseCase(configuration, repository),
                observeProduct = ObserveInventoryProductUseCase(configuration, repository),
                diagnoseInventory = DiagnoseInventoryUseCase(configuration, repository),
                observeProductProfits = ObserveProductProfitsUseCase(configuration, profits),
                updateProductSalePrice = UpdateProductSalePriceUseCase(configuration, products),
                configuration = configuration,
                products = products,
                dispatcherProvider = dispatchers,
            )

            assertEquals(InventoryContract.State(query = "café"), viewModel.uiState.value)

            runCurrent()
        }

    @Test
    fun `OnboardingViewModel exposes its initial state`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val clock = AppClock { NOW }
            val dispatchers = TestDispatcherProvider(mainDispatcherRule.dispatcher)
            val businesses = FakeBusinessRepository(clock)
            val locations = FakeInventoryLocationRepository(clock)
            val units = FakeUnitRepository(clock)
            val viewModel = OnboardingViewModel(
                SavedStateHandle(),
                CompleteOnboardingUseCase(
                    onboardingProvisioningRepository = FakeOnboardingProvisioningRepository(
                        businesses = businesses,
                        locations = locations,
                        units = units,
                    ),
                    appConfigurationRepository = FakeAppConfigurationRepository(),
                    uuidGenerator = UuidGenerator { UUID.randomUUID() },
                    appClock = clock,
                ),
                dispatchers,
            )

            assertEquals(OnboardingContract.State(), viewModel.uiState.value)
        }

    @Test
    fun `SettingsViewModel exposes its initial state before loading`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val clock = AppClock { NOW }
            val dispatchers = TestDispatcherProvider(mainDispatcherRule.dispatcher)
            val businesses = FakeBusinessRepository(clock)
            val appConfig = FakeAppConfigurationRepository()
            val drafts = FakeInvoiceDraftRepository(clock)
            val suppliers = FakeSupplierRepository(clock)
            val products = FakeProductRepository(clock)
            val units = FakeUnitRepository(clock)
            val locations = FakeInventoryLocationRepository(clock)
            val aliases = FakeSupplierProductAliasRepository(clock)
            val inventoryReads = FakeInventoryReadRepository()
            val purchaseReads = FakePurchaseReadRepository()
            val uuidGenerator = UuidGenerator { UUID.randomUUID() }
            val viewModel = SettingsViewModel(
                SavedStateHandle(),
                ObserveAppConfigurationUseCase(appConfig),
                businesses,
                UpdateBusinessProfileUseCase(businesses),
                UpdateTaxConfigurationUseCase(appConfig),
                EnterDemoModeUseCase(
                    businessRepository = businesses,
                    supplierRepository = suppliers,
                    unitRepository = units,
                    inventoryLocationRepository = locations,
                    productRepository = products,
                    appConfigurationRepository = appConfig,
                    uuidGenerator = uuidGenerator,
                    appClock = clock,
                ),
                ExitDemoModeUseCase(appConfig, businesses),
                StartDemoInvoiceScenarioUseCase(
                    appConfigurationRepository = appConfig,
                    invoiceDraftRepository = drafts,
                    demoInvoiceSource = DemoInvoiceSource { byteArrayOf(1) },
                    importDraftImageUseCase = ImportDraftImageUseCase(
                        appConfigurationRepository = appConfig,
                        invoiceDraftRepository = drafts,
                        draftImageImporter = FakeDraftImageImporter(),
                        draftFileStore = FakeDraftFileStore(),
                        uuidGenerator = uuidGenerator,
                    ),
                    appClock = clock,
                ),
                UpdateDiagnosticsConsentUseCase(
                    appConfigurationRepository = appConfig,
                    observability = DisabledProductionObservability,
                ),
                UpdateImageRetentionPolicyUseCase(appConfig),
                UpdateBackupEnabledUseCase(appConfig, FakePurchaseBackupScheduler()),
                UpdateDocumentBackupEnabledUseCase(appConfig),
                UpdateBiometricLockEnabledUseCase(appConfig),
                WriteUserDataExportUseCase(
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
                    writer = FakeUserDataExportWriter(),
                ),
                RunPrivacyMaintenanceUseCase(
                    appConfigurationRepository = appConfig,
                    invoiceDraftRepository = drafts,
                    purchaseReadRepository = purchaseReads,
                    retentionFileSweep = FakeRetentionFileSweep(),
                    draftFileStore = FakeDraftFileStore(),
                    retainedImageStore = FakeRetainedImageStore(),
                    appClock = clock,
                ),
                dispatchers,
            )

            assertEquals(SettingsContract.State(), viewModel.uiState.value)
        }

    @Test
    fun `AppGateViewModel starts in Loading before the first emission`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            // Constructible a mano: no recibe DispatcherProvider; stateIn arranca en Loading
            // hasta que el flujo de configuración emita (Main lo provee MainDispatcherRule).
            val viewModel = AppGateViewModel(
                ObserveAppConfigurationUseCase(FakeAppConfigurationRepository()),
            )

            assertEquals(GateState.Loading, viewModel.uiState.value)
        }

    @Test
    fun `AccountViewModel exposes its initial state before observing the session`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val dispatchers = TestDispatcherProvider(mainDispatcherRule.dispatcher)
            val viewModel = AccountViewModel(
                SavedStateHandle(),
                FakeAccountRepository(),
                FakeBusinessMembershipRepository(),
                ObserveAppConfigurationUseCase(FakeAppConfigurationRepository()),
                FakePurchaseBackupScheduler(),
                BindCloudBusinessLinkUseCase(
                    FakeCloudBusinessBindingRepository(),
                    AppClock { NOW },
                ),
                dispatchers,
            )

            // La sesión aún no se conoce: el estado inicial no asume ninguna.
            assertEquals(AccountContract.State(), viewModel.uiState.value)
        }

    @Test
    fun `MembersViewModel exposes its initial state before observing the session`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val dispatchers = TestDispatcherProvider(mainDispatcherRule.dispatcher)
            val viewModel = MembersViewModel(
                FakeAccountRepository(),
                FakeBusinessMembershipRepository(),
                dispatchers,
            )

            assertEquals(MembersContract.State(), viewModel.uiState.value)
        }

    @Test
    fun `InvitationsViewModel exposes its initial state before loading`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val dispatchers = TestDispatcherProvider(mainDispatcherRule.dispatcher)
            val viewModel = InvitationsViewModel(
                FakeAccountRepository(),
                FakeBusinessMembershipRepository(),
                ObserveAppConfigurationUseCase(FakeAppConfigurationRepository()),
                FakePurchaseBackupScheduler(),
                BindCloudBusinessLinkUseCase(
                    FakeCloudBusinessBindingRepository(),
                    AppClock { NOW },
                ),
                dispatchers,
            )

            assertEquals(InvitationsContract.State(), viewModel.uiState.value)
        }

    @Test
    fun `SyncViewModel exposes its initial state before observing the session`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val dispatchers = TestDispatcherProvider(mainDispatcherRule.dispatcher)
            val clock = AppClock { NOW }
            val configuration = FakeAppConfigurationRepository()
            val outbox = FakePurchaseBackupOutboxRepository()
            // El libro remoto no está disponible en el estado inicial (flavor local).
            val cursors = FakeSyncCursorRepository()
            val remoteLedger = FakeRemoteLedgerRepository(available = false)
            val reconciliation = FakeSyncReconciliationRepository()
            val auditTrail = FakeAuditTrailRepository()
            val retryBackup = RetryPurchaseBackupUseCase(
                configuration,
                FakePurchaseBackupRepository(),
                FakePurchaseBackupScheduler(),
            )
            val retryOperation = RetrySyncOperationUseCase(
                outbox,
                FakePurchaseBackupScheduler(),
                clock,
            )
            val viewModel = SyncViewModel(
                FakeAccountRepository(),
                ObserveAppConfigurationUseCase(configuration),
                outbox,
                cursors,
                remoteLedger,
                cursors,
                ObservePurchasesUseCase(configuration, FakePurchaseReadRepository()),
                PullRemoteChangesUseCase(remoteLedger, cursors, cursors, clock),
                ReconcileRemoteLedgerUseCase(cursors, reconciliation, clock),
                ResolveSyncConflictUseCase(outbox, retryBackup, clock),
                RecordReconciliationReviewUseCase(auditTrail, UuidGenerator { DRAFT_UUID }, clock),
                retryOperation,
                dispatchers,
            )

            // La sesión aún no se conoce: el estado inicial no asume ninguna.
            assertEquals(SyncContract.State(), viewModel.uiState.value)
        }

    private companion object {
        val DRAFT_UUID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val LINE_UUID: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val PURCHASE_UUID: UUID = UUID.fromString("44444444-4444-4444-4444-444444444444")
        val DRAFT_ID: DraftId = DraftId.from(DRAFT_UUID)
        val LINE_ID: LineId = LineId.from(LINE_UUID)
        val PURCHASE_ID: PurchaseId = PurchaseId.from(PURCHASE_UUID)
        val NOW: Instant = Instant.parse("2026-08-07T12:00:00Z")
        const val PREPARED_HASH =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}

private object UnavailableRemoteDocumentArchiveForTest : RemoteDocumentArchive {
    override val configured: Boolean = false

    override suspend fun download(
        reference: RemoteDocumentReference,
    ): RemoteDocumentDownloadResult = RemoteDocumentDownloadResult.Unavailable
}
