package com.facturastock.app.di

import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.AuditTrailRepository
import com.facturastock.app.domain.repository.BusinessRepository
import com.facturastock.app.domain.repository.DraftFileStore
import com.facturastock.app.domain.repository.DraftImageImporter
import com.facturastock.app.domain.repository.DraftWorkflowRepository
import com.facturastock.app.domain.repository.DemoInvoiceSource
import com.facturastock.app.domain.repository.DocumentBackupLifecycleRepository
import com.facturastock.app.domain.repository.DocumentUploadPreparer
import com.facturastock.app.domain.repository.DebtRepository
import com.facturastock.app.domain.repository.InventoryLocationRepository
import com.facturastock.app.domain.repository.InventoryReadRepository
import com.facturastock.app.domain.repository.HomeDashboardReadRepository
import com.facturastock.app.domain.repository.ImageQualityAnalyzer
import com.facturastock.app.domain.repository.InvoiceDraftRepository
import com.facturastock.app.domain.repository.InvoiceHeaderReviewRepository
import com.facturastock.app.domain.repository.InvoiceLinesReviewRepository
import com.facturastock.app.domain.repository.ManualInvoiceReviewRepository
import com.facturastock.app.domain.repository.OnboardingProvisioningRepository
import com.facturastock.app.domain.repository.InvoiceImagePreprocessor
import com.facturastock.app.domain.repository.InvoiceOcrSnapshotRepository
import com.facturastock.app.domain.repository.InvoiceTextRecognizer
import com.facturastock.app.domain.repository.ParsedInvoiceRepository
import com.facturastock.app.domain.repository.PreparedPurchaseRepository
import com.facturastock.app.domain.repository.PurchaseOverrideAuthorizationRepository
import com.facturastock.app.domain.repository.PurchaseBackupOutboxRepository
import com.facturastock.app.domain.repository.CloudBusinessBindingRepository
import com.facturastock.app.domain.repository.PurchaseBackupRepository
import com.facturastock.app.domain.repository.PurchaseBackupScheduler
import com.facturastock.app.domain.repository.PurchaseBackupTransport
import com.facturastock.app.domain.repository.PurchaseRepository
import com.facturastock.app.domain.repository.PurchaseReadRepository
import com.facturastock.app.domain.repository.RecentDraftReadRepository
import com.facturastock.app.domain.repository.PrivacyMaintenanceScheduler
import com.facturastock.app.domain.repository.PurchasePostingRepository
import com.facturastock.app.domain.repository.PurchaseVoidRepository
import com.facturastock.app.domain.repository.SaleRepository
import com.facturastock.app.domain.repository.RemoteLedgerRepository
import com.facturastock.app.domain.repository.RemoteSaleSyncRepository
import com.facturastock.app.domain.repository.SharedInventoryApplicationRepository
import com.facturastock.app.domain.repository.RemoteCatalogApplicationRepository
import com.facturastock.app.domain.repository.RemoteCatalogRepository
import com.facturastock.app.domain.repository.RemoteDocumentArchive
import com.facturastock.app.domain.repository.RemoteSyncCacheRepository
import com.facturastock.app.domain.repository.ProductRepository
import com.facturastock.app.domain.repository.ProductProfitRepository
import com.facturastock.app.domain.repository.ProductInventoryRepository
import com.facturastock.app.domain.repository.RetainedImageStore
import com.facturastock.app.domain.repository.RetentionFileSweep
import com.facturastock.app.domain.repository.SupplierProductAliasRepository
import com.facturastock.app.domain.repository.SupplierRepository
import com.facturastock.app.domain.repository.SyncCursorRepository
import com.facturastock.app.domain.repository.SyncReconciliationRepository
import com.facturastock.app.domain.repository.UnitRepository
import com.facturastock.app.domain.observability.ProductionObservability
import com.facturastock.app.domain.usecase.AnalyzeDraftImagesUseCase
import com.facturastock.app.domain.usecase.ApplyImageRetentionAfterConfirmUseCase
import com.facturastock.app.domain.usecase.ApplyImageRetentionAfterOcrUseCase
import com.facturastock.app.domain.usecase.BindCloudBusinessLinkUseCase
import com.facturastock.app.domain.usecase.CompleteOnboardingUseCase
import com.facturastock.app.domain.usecase.CreateLinkedProductUseCase
import com.facturastock.app.domain.usecase.CropDraftImageUseCase
import com.facturastock.app.domain.usecase.DeleteDraftImageUseCase
import com.facturastock.app.domain.usecase.DeleteDraftUseCase
import com.facturastock.app.domain.usecase.EnterDemoModeUseCase
import com.facturastock.app.domain.usecase.EnterManualInvoiceReviewUseCase
import com.facturastock.app.domain.usecase.ExitDemoModeUseCase
import com.facturastock.app.domain.usecase.ExportUserDataUseCase
import com.facturastock.app.domain.usecase.FindDraftFirstImageUseCase
import com.facturastock.app.domain.usecase.ImportDraftImageUseCase
import com.facturastock.app.domain.usecase.ImportScannedInvoiceProductsUseCase
import com.facturastock.app.domain.usecase.LoadPreparedOcrImagesUseCase
import com.facturastock.app.domain.usecase.LoadRemotePurchaseDocumentUseCase
import com.facturastock.app.domain.usecase.LoadInvoiceHeaderReviewUseCase
import com.facturastock.app.domain.usecase.ObserveAppConfigurationUseCase
import com.facturastock.app.domain.usecase.ObserveInventoryProductUseCase
import com.facturastock.app.domain.usecase.ObserveInventoryUseCase
import com.facturastock.app.domain.usecase.ObserveHomeDashboardUseCase
import com.facturastock.app.domain.usecase.ObserveProductProfitsUseCase
import com.facturastock.app.domain.usecase.DiagnoseInventoryUseCase
import com.facturastock.app.domain.usecase.ObserveDraftImagesUseCase
import com.facturastock.app.domain.usecase.ObserveDebtDetailUseCase
import com.facturastock.app.domain.usecase.ObserveDebtsUseCase
import com.facturastock.app.domain.usecase.ObserveInvoiceDraftUseCase
import com.facturastock.app.domain.usecase.ObserveRecentDraftsUseCase
import com.facturastock.app.domain.usecase.OcrRunActivityRegistry
import com.facturastock.app.domain.usecase.ParseInvoiceUseCase
import com.facturastock.app.domain.usecase.PreprocessDraftImagesUseCase
import com.facturastock.app.domain.usecase.PreparePurchaseUseCase
import com.facturastock.app.domain.usecase.AuthorizePurchaseDuplicateOverrideUseCase
import com.facturastock.app.domain.usecase.CheckPurchaseDuplicateUseCase
import com.facturastock.app.domain.usecase.ConfirmPurchaseUseCase
import com.facturastock.app.domain.usecase.CheckoutSaleUseCase
import com.facturastock.app.domain.usecase.CreateSaleCartUseCase
import com.facturastock.app.domain.usecase.PreviewPurchaseVoidUseCase
import com.facturastock.app.domain.usecase.VoidPurchaseUseCase
import com.facturastock.app.domain.usecase.FindPurchaseUseCase
import com.facturastock.app.domain.usecase.ObservePurchaseDetailUseCase
import com.facturastock.app.domain.usecase.ObservePurchaseHistoryUseCase
import com.facturastock.app.domain.usecase.ObservePurchasesUseCase
import com.facturastock.app.domain.usecase.ObserveSalesReportUseCase
import com.facturastock.app.domain.usecase.ObserveSaleCartUseCase
import com.facturastock.app.domain.usecase.ObservePurchaseBackupUseCase
import com.facturastock.app.domain.usecase.ObservePreparedPurchaseUseCase
import com.facturastock.app.domain.usecase.ProcessPurchaseBackupOutboxUseCase
import com.facturastock.app.domain.usecase.PullRemoteChangesUseCase
import com.facturastock.app.domain.usecase.ReconcileRemoteLedgerUseCase
import com.facturastock.app.domain.usecase.ReportOutboxHealthUseCase
import com.facturastock.app.domain.usecase.RecordReconciliationReviewUseCase
import com.facturastock.app.domain.usecase.RecordDebtPaymentUseCase
import com.facturastock.app.domain.usecase.ResolveSyncConflictUseCase
import com.facturastock.app.domain.usecase.RecoverInterruptedPurchaseBackupsUseCase
import com.facturastock.app.domain.usecase.ReadRetainedImageUseCase
import com.facturastock.app.domain.usecase.RetryPurchaseBackupUseCase
import com.facturastock.app.domain.usecase.RetrySyncOperationUseCase
import com.facturastock.app.domain.usecase.ProductMatchingUseCase
import com.facturastock.app.domain.usecase.PurchaseReadinessValidator
import com.facturastock.app.domain.usecase.ReorderDraftImagesUseCase
import com.facturastock.app.domain.usecase.RetryDraftOcrUseCase
import com.facturastock.app.domain.usecase.RotateDraftImageUseCase
import com.facturastock.app.domain.usecase.RunInvoiceOcrUseCase
import com.facturastock.app.domain.usecase.RunDraftStageUseCase
import com.facturastock.app.domain.usecase.RunPrivacyMaintenanceUseCase
import com.facturastock.app.domain.usecase.SaveInvoiceHeaderEditUseCase
import com.facturastock.app.domain.usecase.SaveSupplierAliasUseCase
import com.facturastock.app.domain.usecase.RemoveSaleCartLineUseCase
import com.facturastock.app.domain.usecase.SaveSaleCartLineUseCase
import com.facturastock.app.domain.usecase.InvoiceHeaderReviewValidator
import com.facturastock.app.domain.usecase.InvoiceLineReviewCalculator
import com.facturastock.app.domain.usecase.InvoiceLineReviewValidator
import com.facturastock.app.domain.usecase.LoadInvoiceLinesReviewUseCase
import com.facturastock.app.domain.usecase.SaveInvoiceLinesEditUseCase
import com.facturastock.app.domain.usecase.UpdateBusinessProfileUseCase
import com.facturastock.app.domain.usecase.UpdateProductSalePriceUseCase
import com.facturastock.app.domain.usecase.UpdateBackupEnabledUseCase
import com.facturastock.app.domain.usecase.UpdateBiometricLockEnabledUseCase
import com.facturastock.app.domain.usecase.UpdateDocumentBackupEnabledUseCase
import com.facturastock.app.domain.usecase.UpdateImageRetentionPolicyUseCase
import com.facturastock.app.domain.usecase.UpdateDiagnosticsConsentUseCase
import com.facturastock.app.domain.usecase.UpdateTaxConfigurationUseCase
import com.facturastock.app.domain.usecase.WriteUserDataExportUseCase
import com.facturastock.app.domain.repository.UserDataExportWriter
import com.facturastock.app.domain.usecase.LoadProductCatalogDetailUseCase
import com.facturastock.app.domain.usecase.ArchiveInventoryLocationCatalogUseCase
import com.facturastock.app.domain.usecase.ArchiveProductCatalogUseCase
import com.facturastock.app.domain.usecase.ArchiveSupplierCatalogUseCase
import com.facturastock.app.domain.usecase.ArchiveUnitCatalogUseCase
import com.facturastock.app.domain.usecase.RestoreInventoryLocationCatalogUseCase
import com.facturastock.app.domain.usecase.RestoreProductCatalogUseCase
import com.facturastock.app.domain.usecase.RestoreSupplierCatalogUseCase
import com.facturastock.app.domain.usecase.RestoreUnitCatalogUseCase
import com.facturastock.app.domain.usecase.SaveInventoryLocationCatalogUseCase
import com.facturastock.app.domain.usecase.SaveProductCatalogUseCase
import com.facturastock.app.domain.usecase.SaveSupplierCatalogUseCase
import com.facturastock.app.domain.usecase.SaveUnitCatalogUseCase
import com.facturastock.app.domain.usecase.StartDemoInvoiceScenarioUseCase
import com.facturastock.app.domain.usecase.StartInvoiceDraftUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object UseCaseModule {
    @Provides
    fun provideRunDraftStageUseCase(
        repository: DraftWorkflowRepository,
    ): RunDraftStageUseCase = RunDraftStageUseCase(repository)

    @Provides
    fun provideCompleteOnboardingUseCase(
        onboardingProvisioningRepository: OnboardingProvisioningRepository,
        appConfigurationRepository: AppConfigurationRepository,
        uuidGenerator: UuidGenerator,
        appClock: AppClock,
    ): CompleteOnboardingUseCase = CompleteOnboardingUseCase(
        onboardingProvisioningRepository = onboardingProvisioningRepository,
        appConfigurationRepository = appConfigurationRepository,
        uuidGenerator = uuidGenerator,
        appClock = appClock,
    )

    @Provides
    fun provideUpdateBusinessProfileUseCase(
        businessRepository: BusinessRepository,
    ): UpdateBusinessProfileUseCase = UpdateBusinessProfileUseCase(businessRepository)

    @Provides
    fun provideUpdateTaxConfigurationUseCase(
        appConfigurationRepository: AppConfigurationRepository,
    ): UpdateTaxConfigurationUseCase = UpdateTaxConfigurationUseCase(appConfigurationRepository)

    @Provides
    fun provideEnterDemoModeUseCase(
        businessRepository: BusinessRepository,
        supplierRepository: SupplierRepository,
        unitRepository: UnitRepository,
        inventoryLocationRepository: InventoryLocationRepository,
        productRepository: ProductRepository,
        appConfigurationRepository: AppConfigurationRepository,
        uuidGenerator: UuidGenerator,
        appClock: AppClock,
    ): EnterDemoModeUseCase = EnterDemoModeUseCase(
        businessRepository = businessRepository,
        supplierRepository = supplierRepository,
        unitRepository = unitRepository,
        inventoryLocationRepository = inventoryLocationRepository,
        productRepository = productRepository,
        appConfigurationRepository = appConfigurationRepository,
        uuidGenerator = uuidGenerator,
        appClock = appClock,
    )

    @Provides
    fun provideExitDemoModeUseCase(
        appConfigurationRepository: AppConfigurationRepository,
        businessRepository: BusinessRepository,
        purchaseBackupScheduler: PurchaseBackupScheduler,
    ): ExitDemoModeUseCase = ExitDemoModeUseCase(
        appConfigurationRepository = appConfigurationRepository,
        businessRepository = businessRepository,
        purchaseBackupScheduler = purchaseBackupScheduler,
    )

    @Provides
    @Singleton
    fun provideStartDemoInvoiceScenarioUseCase(
        appConfigurationRepository: AppConfigurationRepository,
        invoiceDraftRepository: InvoiceDraftRepository,
        demoInvoiceSource: DemoInvoiceSource,
        importDraftImageUseCase: ImportDraftImageUseCase,
        appClock: AppClock,
    ): StartDemoInvoiceScenarioUseCase = StartDemoInvoiceScenarioUseCase(
        appConfigurationRepository = appConfigurationRepository,
        invoiceDraftRepository = invoiceDraftRepository,
        demoInvoiceSource = demoInvoiceSource,
        importDraftImageUseCase = importDraftImageUseCase,
        appClock = appClock,
    )

    @Provides
    fun provideObserveAppConfigurationUseCase(
        appConfigurationRepository: AppConfigurationRepository,
    ): ObserveAppConfigurationUseCase = ObserveAppConfigurationUseCase(appConfigurationRepository)

    @Provides
    fun provideBindCloudBusinessLinkUseCase(
        cloudBusinessBindingRepository: CloudBusinessBindingRepository,
        appClock: AppClock,
    ): BindCloudBusinessLinkUseCase =
        BindCloudBusinessLinkUseCase(cloudBusinessBindingRepository, appClock)

    @Provides
    fun provideObserveRecentDraftsUseCase(
        appConfigurationRepository: AppConfigurationRepository,
        recentDraftReadRepository: RecentDraftReadRepository,
    ): ObserveRecentDraftsUseCase = ObserveRecentDraftsUseCase(
        appConfigurationRepository = appConfigurationRepository,
        recentDraftReadRepository = recentDraftReadRepository,
    )

    @Provides
    fun provideObserveHomeDashboardUseCase(
        appConfigurationRepository: AppConfigurationRepository,
        businessRepository: BusinessRepository,
        homeDashboardReadRepository: HomeDashboardReadRepository,
        appClock: AppClock,
    ): ObserveHomeDashboardUseCase = ObserveHomeDashboardUseCase(
        configurationRepository = appConfigurationRepository,
        businessRepository = businessRepository,
        dashboardReadRepository = homeDashboardReadRepository,
        clock = appClock,
    )

    @Provides
    fun provideStartInvoiceDraftUseCase(
        appConfigurationRepository: AppConfigurationRepository,
        invoiceDraftRepository: InvoiceDraftRepository,
        appClock: AppClock,
    ): StartInvoiceDraftUseCase = StartInvoiceDraftUseCase(
        appConfigurationRepository = appConfigurationRepository,
        invoiceDraftRepository = invoiceDraftRepository,
        appClock = appClock,
    )

    @Provides
    fun provideDeleteDraftUseCase(
        invoiceDraftRepository: InvoiceDraftRepository,
        draftFileStore: DraftFileStore,
    ): DeleteDraftUseCase = DeleteDraftUseCase(
        invoiceDraftRepository = invoiceDraftRepository,
        draftFileStore = draftFileStore,
    )

    @Provides
    fun provideImportScannedInvoiceProductsUseCase(
        appConfigurationRepository: AppConfigurationRepository,
        businessRepository: BusinessRepository,
        invoiceDraftRepository: InvoiceDraftRepository,
        productRepository: ProductRepository,
        unitRepository: UnitRepository,
        uuidGenerator: UuidGenerator,
        appClock: AppClock,
        deleteDraftUseCase: DeleteDraftUseCase,
    ): ImportScannedInvoiceProductsUseCase = ImportScannedInvoiceProductsUseCase(
        appConfigurationRepository = appConfigurationRepository,
        businessRepository = businessRepository,
        invoiceDraftRepository = invoiceDraftRepository,
        productRepository = productRepository,
        unitRepository = unitRepository,
        uuidGenerator = uuidGenerator,
        appClock = appClock,
        deleteDraftUseCase = deleteDraftUseCase,
    )

    @Provides
    fun provideObserveDraftImagesUseCase(
        invoiceDraftRepository: InvoiceDraftRepository,
    ): ObserveDraftImagesUseCase = ObserveDraftImagesUseCase(invoiceDraftRepository)

    @Provides
    fun provideObserveInvoiceDraftUseCase(
        invoiceDraftRepository: InvoiceDraftRepository,
    ): ObserveInvoiceDraftUseCase = ObserveInvoiceDraftUseCase(invoiceDraftRepository)

    @Provides
    fun provideFindDraftFirstImageUseCase(
        invoiceDraftRepository: InvoiceDraftRepository,
    ): FindDraftFirstImageUseCase = FindDraftFirstImageUseCase(invoiceDraftRepository)

    @Provides
    fun provideRetryDraftOcrUseCase(
        invoiceDraftRepository: InvoiceDraftRepository,
    ): RetryDraftOcrUseCase = RetryDraftOcrUseCase(invoiceDraftRepository)

    @Provides
    fun provideEnterManualInvoiceReviewUseCase(
        repository: ManualInvoiceReviewRepository,
    ): EnterManualInvoiceReviewUseCase = EnterManualInvoiceReviewUseCase(repository)

    @Provides
    @Singleton
    fun provideImportDraftImageUseCase(
        appConfigurationRepository: AppConfigurationRepository,
        invoiceDraftRepository: InvoiceDraftRepository,
        draftImageImporter: DraftImageImporter,
        draftFileStore: DraftFileStore,
        uuidGenerator: UuidGenerator,
        observability: ProductionObservability,
    ): ImportDraftImageUseCase = ImportDraftImageUseCase(
        appConfigurationRepository = appConfigurationRepository,
        invoiceDraftRepository = invoiceDraftRepository,
        draftImageImporter = draftImageImporter,
        draftFileStore = draftFileStore,
        uuidGenerator = uuidGenerator,
        observability = observability,
    )

    @Provides
    fun provideRotateDraftImageUseCase(
        invoiceDraftRepository: InvoiceDraftRepository,
    ): RotateDraftImageUseCase = RotateDraftImageUseCase(invoiceDraftRepository)

    @Provides
    fun provideCropDraftImageUseCase(
        invoiceDraftRepository: InvoiceDraftRepository,
    ): CropDraftImageUseCase = CropDraftImageUseCase(invoiceDraftRepository)

    @Provides
    fun provideReorderDraftImagesUseCase(
        invoiceDraftRepository: InvoiceDraftRepository,
    ): ReorderDraftImagesUseCase = ReorderDraftImagesUseCase(invoiceDraftRepository)

    @Provides
    fun provideDeleteDraftImageUseCase(
        invoiceDraftRepository: InvoiceDraftRepository,
        draftFileStore: DraftFileStore,
    ): DeleteDraftImageUseCase = DeleteDraftImageUseCase(
        invoiceDraftRepository = invoiceDraftRepository,
        draftFileStore = draftFileStore,
    )

    @Provides
    fun provideAnalyzeDraftImagesUseCase(
        invoiceDraftRepository: InvoiceDraftRepository,
        imageQualityAnalyzer: ImageQualityAnalyzer,
    ): AnalyzeDraftImagesUseCase = AnalyzeDraftImagesUseCase(
        invoiceDraftRepository = invoiceDraftRepository,
        imageQualityAnalyzer = imageQualityAnalyzer,
    )

    @Provides
    fun providePreprocessDraftImagesUseCase(
        invoiceDraftRepository: InvoiceDraftRepository,
        invoiceImagePreprocessor: InvoiceImagePreprocessor,
    ): PreprocessDraftImagesUseCase = PreprocessDraftImagesUseCase(
        invoiceDraftRepository = invoiceDraftRepository,
        imagePreprocessor = invoiceImagePreprocessor,
    )

    @Provides
    fun provideLoadPreparedOcrImagesUseCase(
        invoiceImagePreprocessor: InvoiceImagePreprocessor,
    ): LoadPreparedOcrImagesUseCase = LoadPreparedOcrImagesUseCase(invoiceImagePreprocessor)

    @Provides
    @Singleton
    fun provideOcrRunActivityRegistry(): OcrRunActivityRegistry = OcrRunActivityRegistry()

    @Provides
    fun provideRunInvoiceOcrUseCase(
        invoiceDraftRepository: InvoiceDraftRepository,
        invoiceImagePreprocessor: InvoiceImagePreprocessor,
        invoiceTextRecognizer: InvoiceTextRecognizer,
        invoiceOcrSnapshotRepository: InvoiceOcrSnapshotRepository,
        uuidGenerator: UuidGenerator,
        appClock: AppClock,
        applyImageRetentionAfterOcr: ApplyImageRetentionAfterOcrUseCase,
        ocrRunActivityRegistry: OcrRunActivityRegistry,
        productionObservability: ProductionObservability,
    ): RunInvoiceOcrUseCase = RunInvoiceOcrUseCase(
        invoiceDraftRepository = invoiceDraftRepository,
        invoiceImagePreprocessor = invoiceImagePreprocessor,
        invoiceTextRecognizer = invoiceTextRecognizer,
        invoiceOcrSnapshotRepository = invoiceOcrSnapshotRepository,
        uuidGenerator = uuidGenerator,
        appClock = appClock,
        applyImageRetentionAfterOcr = applyImageRetentionAfterOcr,
        activityRegistry = ocrRunActivityRegistry,
        observability = productionObservability,
    )

    @Provides
    fun provideUpdateImageRetentionPolicyUseCase(
        appConfigurationRepository: AppConfigurationRepository,
    ): UpdateImageRetentionPolicyUseCase =
        UpdateImageRetentionPolicyUseCase(appConfigurationRepository)

    @Provides
    fun provideUpdateBackupEnabledUseCase(
        appConfigurationRepository: AppConfigurationRepository,
        purchaseBackupScheduler: PurchaseBackupScheduler,
    ): UpdateBackupEnabledUseCase = UpdateBackupEnabledUseCase(
        appConfigurationRepository,
        purchaseBackupScheduler,
    )

    @Provides
    fun provideUpdateDocumentBackupEnabledUseCase(
        appConfigurationRepository: AppConfigurationRepository,
        documentBackupLifecycleRepository: DocumentBackupLifecycleRepository,
        purchaseBackupScheduler: PurchaseBackupScheduler,
        privacyMaintenanceScheduler: PrivacyMaintenanceScheduler,
        appClock: AppClock,
    ): UpdateDocumentBackupEnabledUseCase =
        UpdateDocumentBackupEnabledUseCase(
            appConfigurationRepository,
            documentBackupLifecycleRepository,
            purchaseBackupScheduler,
            appClock,
        )

    @Provides
    fun provideUpdateBiometricLockEnabledUseCase(
        appConfigurationRepository: AppConfigurationRepository,
    ): UpdateBiometricLockEnabledUseCase =
        UpdateBiometricLockEnabledUseCase(appConfigurationRepository)

    @Provides
    fun provideUpdateDiagnosticsConsentUseCase(
        appConfigurationRepository: AppConfigurationRepository,
        observability: ProductionObservability,
    ): UpdateDiagnosticsConsentUseCase = UpdateDiagnosticsConsentUseCase(
        appConfigurationRepository = appConfigurationRepository,
        observability = observability,
    )

    @Provides
    fun provideApplyImageRetentionAfterOcrUseCase(
        appConfigurationRepository: AppConfigurationRepository,
        draftFileStore: DraftFileStore,
        invoiceDraftRepository: InvoiceDraftRepository,
    ): ApplyImageRetentionAfterOcrUseCase = ApplyImageRetentionAfterOcrUseCase(
        appConfigurationRepository = appConfigurationRepository,
        draftFileStore = draftFileStore,
        invoiceDraftRepository = invoiceDraftRepository,
    )

    @Provides
    fun provideApplyImageRetentionAfterConfirmUseCase(
        appConfigurationRepository: AppConfigurationRepository,
        draftFileStore: DraftFileStore,
        runPrivacyMaintenanceUseCase: RunPrivacyMaintenanceUseCase,
        purchaseReadRepository: PurchaseReadRepository,
        retainedImageStore: RetainedImageStore,
        privacyMaintenanceScheduler: PrivacyMaintenanceScheduler,
    ): ApplyImageRetentionAfterConfirmUseCase = ApplyImageRetentionAfterConfirmUseCase(
        appConfigurationRepository = appConfigurationRepository,
        draftFileStore = draftFileStore,
        runPrivacyMaintenanceUseCase = runPrivacyMaintenanceUseCase,
        purchaseReadRepository = purchaseReadRepository,
        retainedImageStore = retainedImageStore,
        privacyMaintenanceScheduler = privacyMaintenanceScheduler,
    )

    @Provides
    fun provideReadRetainedImageUseCase(
        retainedImageStore: RetainedImageStore,
    ): ReadRetainedImageUseCase = ReadRetainedImageUseCase(retainedImageStore)

    @Provides
    fun provideLoadRemotePurchaseDocumentUseCase(
        appConfigurationRepository: AppConfigurationRepository,
        documentBackupLifecycleRepository: DocumentBackupLifecycleRepository,
        remoteDocumentArchive: RemoteDocumentArchive,
    ): LoadRemotePurchaseDocumentUseCase = LoadRemotePurchaseDocumentUseCase(
        appConfigurationRepository = appConfigurationRepository,
        documentLifecycle = documentBackupLifecycleRepository,
        remoteDocumentArchive = remoteDocumentArchive,
    )

    @Provides
    fun provideRunPrivacyMaintenanceUseCase(
        appConfigurationRepository: AppConfigurationRepository,
        invoiceDraftRepository: InvoiceDraftRepository,
        purchaseReadRepository: PurchaseReadRepository,
        retentionFileSweep: RetentionFileSweep,
        draftFileStore: DraftFileStore,
        retainedImageStore: RetainedImageStore,
        documentBackupLifecycleRepository: DocumentBackupLifecycleRepository,
        purchaseBackupScheduler: PurchaseBackupScheduler,
        privacyMaintenanceScheduler: PrivacyMaintenanceScheduler,
        ocrRunActivityRegistry: OcrRunActivityRegistry,
        appClock: AppClock,
    ): RunPrivacyMaintenanceUseCase = RunPrivacyMaintenanceUseCase(
        appConfigurationRepository = appConfigurationRepository,
        invoiceDraftRepository = invoiceDraftRepository,
        purchaseReadRepository = purchaseReadRepository,
        retentionFileSweep = retentionFileSweep,
        draftFileStore = draftFileStore,
        retainedImageStore = retainedImageStore,
        documentLifecycle = documentBackupLifecycleRepository,
        purchaseBackupScheduler = purchaseBackupScheduler,
        privacyMaintenanceScheduler = privacyMaintenanceScheduler,
        ocrRunActivityRegistry = ocrRunActivityRegistry,
        appClock = appClock,
    )

    @Provides
    fun provideExportUserDataUseCase(
        appConfigurationRepository: AppConfigurationRepository,
        businessRepository: BusinessRepository,
        productRepository: ProductRepository,
        supplierRepository: SupplierRepository,
        unitRepository: UnitRepository,
        inventoryLocationRepository: InventoryLocationRepository,
        supplierProductAliasRepository: SupplierProductAliasRepository,
        inventoryReadRepository: InventoryReadRepository,
        purchaseReadRepository: PurchaseReadRepository,
        appClock: AppClock,
    ): ExportUserDataUseCase = ExportUserDataUseCase(
        appConfigurationRepository = appConfigurationRepository,
        businessRepository = businessRepository,
        productRepository = productRepository,
        supplierRepository = supplierRepository,
        unitRepository = unitRepository,
        inventoryLocationRepository = inventoryLocationRepository,
        supplierProductAliasRepository = supplierProductAliasRepository,
        inventoryReadRepository = inventoryReadRepository,
        purchaseReadRepository = purchaseReadRepository,
        appClock = appClock,
    )

    @Provides
    fun provideWriteUserDataExportUseCase(
        exportUserDataUseCase: ExportUserDataUseCase,
        writer: UserDataExportWriter,
    ): WriteUserDataExportUseCase = WriteUserDataExportUseCase(
        exportUserData = exportUserDataUseCase,
        writer = writer,
    )

    @Provides
    fun provideParseInvoiceUseCase(
        invoiceDraftRepository: InvoiceDraftRepository,
        invoiceOcrSnapshotRepository: InvoiceOcrSnapshotRepository,
        parsedInvoiceRepository: ParsedInvoiceRepository,
        appConfigurationRepository: AppConfigurationRepository,
        businessRepository: BusinessRepository,
        appClock: AppClock,
        dispatcherProvider: DispatcherProvider,
    ): ParseInvoiceUseCase = ParseInvoiceUseCase(
        invoiceDraftRepository = invoiceDraftRepository,
        invoiceOcrSnapshotRepository = invoiceOcrSnapshotRepository,
        parsedInvoiceRepository = parsedInvoiceRepository,
        appConfigurationRepository = appConfigurationRepository,
        businessRepository = businessRepository,
        appClock = appClock,
        dispatcherProvider = dispatcherProvider,
    )

    @Provides
    fun provideInvoiceHeaderReviewValidator(): InvoiceHeaderReviewValidator =
        InvoiceHeaderReviewValidator()

    @Provides
    fun provideLoadInvoiceHeaderReviewUseCase(
        invoiceDraftRepository: InvoiceDraftRepository,
        invoiceHeaderReviewRepository: InvoiceHeaderReviewRepository,
        parsedInvoiceRepository: ParsedInvoiceRepository,
    ): LoadInvoiceHeaderReviewUseCase = LoadInvoiceHeaderReviewUseCase(
        invoiceDraftRepository = invoiceDraftRepository,
        invoiceHeaderReviewRepository = invoiceHeaderReviewRepository,
        parsedInvoiceRepository = parsedInvoiceRepository,
    )

    @Provides
    fun provideSaveInvoiceHeaderEditUseCase(
        invoiceDraftRepository: InvoiceDraftRepository,
        invoiceHeaderReviewRepository: InvoiceHeaderReviewRepository,
        validator: InvoiceHeaderReviewValidator,
        observability: ProductionObservability,
    ): SaveInvoiceHeaderEditUseCase = SaveInvoiceHeaderEditUseCase(
        invoiceDraftRepository = invoiceDraftRepository,
        invoiceHeaderReviewRepository = invoiceHeaderReviewRepository,
        validator = validator,
        observability = observability,
    )

    @Provides
    fun provideInvoiceLineReviewValidator(): InvoiceLineReviewValidator =
        InvoiceLineReviewValidator()

    @Provides
    fun provideInvoiceLineReviewCalculator(): InvoiceLineReviewCalculator =
        InvoiceLineReviewCalculator()

    @Provides
    fun provideLoadInvoiceLinesReviewUseCase(
        invoiceDraftRepository: InvoiceDraftRepository,
        invoiceLinesReviewRepository: InvoiceLinesReviewRepository,
        parsedInvoiceRepository: ParsedInvoiceRepository,
        appClock: AppClock,
    ): LoadInvoiceLinesReviewUseCase = LoadInvoiceLinesReviewUseCase(
        invoiceDraftRepository = invoiceDraftRepository,
        invoiceLinesReviewRepository = invoiceLinesReviewRepository,
        parsedInvoiceRepository = parsedInvoiceRepository,
        appClock = appClock,
    )

    @Provides
    fun provideSaveInvoiceLinesEditUseCase(
        invoiceDraftRepository: InvoiceDraftRepository,
        invoiceLinesReviewRepository: InvoiceLinesReviewRepository,
        calculator: InvoiceLineReviewCalculator,
        validator: InvoiceLineReviewValidator,
        observability: ProductionObservability,
    ): SaveInvoiceLinesEditUseCase = SaveInvoiceLinesEditUseCase(
        invoiceDraftRepository = invoiceDraftRepository,
        invoiceLinesReviewRepository = invoiceLinesReviewRepository,
        calculator = calculator,
        validator = validator,
        observability = observability,
    )

    @Provides
    fun provideProductMatchingUseCase(
        productRepository: ProductRepository,
        supplierProductAliasRepository: SupplierProductAliasRepository,
    ): ProductMatchingUseCase = ProductMatchingUseCase(
        productRepository = productRepository,
        supplierProductAliasRepository = supplierProductAliasRepository,
    )

    @Provides
    fun provideLoadProductCatalogDetailUseCase(
        productRepository: ProductRepository,
        unitRepository: UnitRepository,
        supplierProductAliasRepository: SupplierProductAliasRepository,
        productInventoryRepository: ProductInventoryRepository,
    ): LoadProductCatalogDetailUseCase = LoadProductCatalogDetailUseCase(
        productRepository = productRepository,
        unitRepository = unitRepository,
        supplierProductAliasRepository = supplierProductAliasRepository,
        productInventoryRepository = productInventoryRepository,
    )

    @Provides
    fun provideSaveSupplierCatalogUseCase(repository: SupplierRepository) =
        SaveSupplierCatalogUseCase(repository)

    @Provides
    fun provideSaveProductCatalogUseCase(
        productRepository: ProductRepository,
        unitRepository: UnitRepository,
        inventoryLocationRepository: InventoryLocationRepository,
    ) = SaveProductCatalogUseCase(
        productRepository,
        unitRepository,
        inventoryLocationRepository,
    )

    @Provides
    fun provideSaveUnitCatalogUseCase(repository: UnitRepository) =
        SaveUnitCatalogUseCase(repository)

    @Provides
    fun provideSaveInventoryLocationCatalogUseCase(repository: InventoryLocationRepository) =
        SaveInventoryLocationCatalogUseCase(repository)

    @Provides
    fun provideArchiveSupplierCatalogUseCase(repository: SupplierRepository) =
        ArchiveSupplierCatalogUseCase(repository)

    @Provides
    fun provideRestoreSupplierCatalogUseCase(repository: SupplierRepository) =
        RestoreSupplierCatalogUseCase(repository)

    @Provides
    fun provideArchiveProductCatalogUseCase(repository: ProductRepository) =
        ArchiveProductCatalogUseCase(repository)

    @Provides
    fun provideRestoreProductCatalogUseCase(repository: ProductRepository) =
        RestoreProductCatalogUseCase(repository)

    @Provides
    fun provideArchiveUnitCatalogUseCase(repository: UnitRepository) =
        ArchiveUnitCatalogUseCase(repository)

    @Provides
    fun provideRestoreUnitCatalogUseCase(repository: UnitRepository) =
        RestoreUnitCatalogUseCase(repository)

    @Provides
    fun provideArchiveInventoryLocationCatalogUseCase(repository: InventoryLocationRepository) =
        ArchiveInventoryLocationCatalogUseCase(repository)

    @Provides
    fun provideRestoreInventoryLocationCatalogUseCase(repository: InventoryLocationRepository) =
        RestoreInventoryLocationCatalogUseCase(repository)

    @Provides
    fun provideSaveSupplierAliasUseCase(
        supplierProductAliasRepository: SupplierProductAliasRepository,
        uuidGenerator: UuidGenerator,
        appClock: AppClock,
    ): SaveSupplierAliasUseCase = SaveSupplierAliasUseCase(
        supplierProductAliasRepository = supplierProductAliasRepository,
        uuidGenerator = uuidGenerator,
        appClock = appClock,
    )

    @Provides
    fun provideCreateLinkedProductUseCase(
        appConfigurationRepository: AppConfigurationRepository,
        productRepository: ProductRepository,
        uuidGenerator: UuidGenerator,
    ): CreateLinkedProductUseCase = CreateLinkedProductUseCase(
        appConfigurationRepository = appConfigurationRepository,
        productRepository = productRepository,
        uuidGenerator = uuidGenerator,
    )

    @Provides
    fun providePurchaseReadinessValidator(): PurchaseReadinessValidator =
        PurchaseReadinessValidator()

    @Provides
    fun provideCheckPurchaseDuplicateUseCase(
        preparedPurchaseRepository: PreparedPurchaseRepository,
        invoiceDraftRepository: InvoiceDraftRepository,
        purchaseRepository: PurchaseRepository,
    ): CheckPurchaseDuplicateUseCase = CheckPurchaseDuplicateUseCase(
        preparedPurchaseRepository = preparedPurchaseRepository,
        invoiceDraftRepository = invoiceDraftRepository,
        purchaseRepository = purchaseRepository,
    )

    @Provides
    fun provideConfirmPurchaseUseCase(
        appConfigurationRepository: AppConfigurationRepository,
        purchasePostingRepository: PurchasePostingRepository,
        purchaseBackupScheduler: PurchaseBackupScheduler,
        applyImageRetentionAfterConfirm: ApplyImageRetentionAfterConfirmUseCase,
        observability: ProductionObservability,
    ): ConfirmPurchaseUseCase = ConfirmPurchaseUseCase(
        appConfigurationRepository = appConfigurationRepository,
        purchasePostingRepository = purchasePostingRepository,
        purchaseBackupScheduler = purchaseBackupScheduler,
        applyImageRetentionAfterConfirm = applyImageRetentionAfterConfirm,
        observability = observability,
    )

    @Provides
    fun providePreviewPurchaseVoidUseCase(
        appConfigurationRepository: AppConfigurationRepository,
        authorizationRepository: PurchaseOverrideAuthorizationRepository,
        purchaseVoidRepository: PurchaseVoidRepository,
    ): PreviewPurchaseVoidUseCase = PreviewPurchaseVoidUseCase(
        appConfigurationRepository = appConfigurationRepository,
        authorizationRepository = authorizationRepository,
        purchaseVoidRepository = purchaseVoidRepository,
    )

    @Provides
    fun provideVoidPurchaseUseCase(
        appConfigurationRepository: AppConfigurationRepository,
        authorizationRepository: PurchaseOverrideAuthorizationRepository,
        purchaseVoidRepository: PurchaseVoidRepository,
        purchaseBackupScheduler: PurchaseBackupScheduler,
        observability: ProductionObservability,
    ): VoidPurchaseUseCase = VoidPurchaseUseCase(
        appConfigurationRepository = appConfigurationRepository,
        authorizationRepository = authorizationRepository,
        purchaseVoidRepository = purchaseVoidRepository,
        purchaseBackupScheduler = purchaseBackupScheduler,
        observability = observability,
    )

    @Provides
    fun provideAuthorizePurchaseDuplicateOverrideUseCase(
        checkPurchaseDuplicateUseCase: CheckPurchaseDuplicateUseCase,
        authorizationRepository: PurchaseOverrideAuthorizationRepository,
    ): AuthorizePurchaseDuplicateOverrideUseCase = AuthorizePurchaseDuplicateOverrideUseCase(
        checkDuplicate = checkPurchaseDuplicateUseCase,
        authorizationRepository = authorizationRepository,
    )

    @Provides
    fun provideFindPurchaseUseCase(
        purchaseRepository: PurchaseRepository,
    ): FindPurchaseUseCase = FindPurchaseUseCase(purchaseRepository)

    @Provides
    fun provideObservePurchasesUseCase(
        appConfigurationRepository: AppConfigurationRepository,
        purchaseReadRepository: PurchaseReadRepository,
    ): ObservePurchasesUseCase = ObservePurchasesUseCase(
        appConfigurationRepository = appConfigurationRepository,
        purchaseReadRepository = purchaseReadRepository,
    )

    @Provides
    fun provideObservePurchaseHistoryUseCase(
        appConfigurationRepository: AppConfigurationRepository,
        purchaseReadRepository: PurchaseReadRepository,
    ): ObservePurchaseHistoryUseCase = ObservePurchaseHistoryUseCase(
        appConfigurationRepository = appConfigurationRepository,
        purchaseReadRepository = purchaseReadRepository,
    )

    @Provides
    fun provideObservePurchaseDetailUseCase(
        appConfigurationRepository: AppConfigurationRepository,
        purchaseReadRepository: PurchaseReadRepository,
    ): ObservePurchaseDetailUseCase = ObservePurchaseDetailUseCase(
        appConfigurationRepository = appConfigurationRepository,
        purchaseReadRepository = purchaseReadRepository,
    )

    @Provides
    fun provideRetryPurchaseBackupUseCase(
        appConfigurationRepository: AppConfigurationRepository,
        purchaseBackupRepository: PurchaseBackupRepository,
        purchaseBackupScheduler: PurchaseBackupScheduler,
    ): RetryPurchaseBackupUseCase = RetryPurchaseBackupUseCase(
        configuration = appConfigurationRepository,
        backups = purchaseBackupRepository,
        purchaseBackupScheduler = purchaseBackupScheduler,
    )

    @Provides
    fun provideRetrySyncOperationUseCase(
        purchaseBackupOutboxRepository: PurchaseBackupOutboxRepository,
        purchaseBackupScheduler: PurchaseBackupScheduler,
        appClock: AppClock,
    ): RetrySyncOperationUseCase = RetrySyncOperationUseCase(
        outbox = purchaseBackupOutboxRepository,
        scheduler = purchaseBackupScheduler,
        clock = appClock,
    )

    @Provides
    fun provideObservePurchaseBackupUseCase(
        appConfigurationRepository: AppConfigurationRepository,
        purchaseBackupRepository: PurchaseBackupRepository,
    ): ObservePurchaseBackupUseCase = ObservePurchaseBackupUseCase(
        configuration = appConfigurationRepository,
        backups = purchaseBackupRepository,
    )

    @Provides
    fun provideObservePreparedPurchaseUseCase(
        preparedPurchaseRepository: PreparedPurchaseRepository,
    ): ObservePreparedPurchaseUseCase =
        ObservePreparedPurchaseUseCase(preparedPurchaseRepository)

    @Provides
    fun provideRecoverInterruptedPurchaseBackupsUseCase(
        purchaseBackupRepository: PurchaseBackupRepository,
    ): RecoverInterruptedPurchaseBackupsUseCase =
        RecoverInterruptedPurchaseBackupsUseCase(purchaseBackupRepository)

    @Provides
    fun provideProcessPurchaseBackupOutboxUseCase(
        purchaseBackupOutboxRepository: PurchaseBackupOutboxRepository,
        purchaseBackupTransport: PurchaseBackupTransport,
        appClock: AppClock,
        uuidGenerator: UuidGenerator,
        observability: ProductionObservability,
        documentUploadPreparer: DocumentUploadPreparer,
    ): ProcessPurchaseBackupOutboxUseCase = ProcessPurchaseBackupOutboxUseCase(
        outbox = purchaseBackupOutboxRepository,
        transport = purchaseBackupTransport,
        clock = appClock,
        uuidGenerator = uuidGenerator,
        observability = observability,
        documentUploadPreparer = documentUploadPreparer,
    )

    @Provides
    fun provideReportOutboxHealthUseCase(
        purchaseBackupOutboxRepository: PurchaseBackupOutboxRepository,
        cloudBusinessBindingRepository: CloudBusinessBindingRepository,
        appClock: AppClock,
        observability: ProductionObservability,
    ): ReportOutboxHealthUseCase = ReportOutboxHealthUseCase(
        outbox = purchaseBackupOutboxRepository,
        cloudBusinessBindings = cloudBusinessBindingRepository,
        clock = appClock,
        observability = observability,
    )

    @Provides
    fun provideObserveInventoryUseCase(
        appConfigurationRepository: AppConfigurationRepository,
        inventoryReadRepository: InventoryReadRepository,
    ): ObserveInventoryUseCase = ObserveInventoryUseCase(
        appConfigurationRepository = appConfigurationRepository,
        inventoryReadRepository = inventoryReadRepository,
    )

    @Provides
    fun provideObserveInventoryProductUseCase(
        appConfigurationRepository: AppConfigurationRepository,
        inventoryReadRepository: InventoryReadRepository,
    ): ObserveInventoryProductUseCase = ObserveInventoryProductUseCase(
        appConfigurationRepository = appConfigurationRepository,
        inventoryReadRepository = inventoryReadRepository,
    )

    @Provides
    fun provideObserveProductProfitsUseCase(
        appConfigurationRepository: AppConfigurationRepository,
        productProfitRepository: ProductProfitRepository,
    ): ObserveProductProfitsUseCase = ObserveProductProfitsUseCase(
        appConfigurationRepository = appConfigurationRepository,
        productProfitRepository = productProfitRepository,
    )

    @Provides
    fun provideUpdateProductSalePriceUseCase(
        appConfigurationRepository: AppConfigurationRepository,
        productRepository: ProductRepository,
    ): UpdateProductSalePriceUseCase = UpdateProductSalePriceUseCase(
        appConfigurationRepository = appConfigurationRepository,
        productRepository = productRepository,
    )

    @Provides
    fun provideDiagnoseInventoryUseCase(
        appConfigurationRepository: AppConfigurationRepository,
        inventoryReadRepository: InventoryReadRepository,
    ): DiagnoseInventoryUseCase = DiagnoseInventoryUseCase(
        appConfigurationRepository = appConfigurationRepository,
        inventoryReadRepository = inventoryReadRepository,
    )

    @Provides
    fun providePreparePurchaseUseCase(
        invoiceDraftRepository: InvoiceDraftRepository,
        invoiceHeaderReviewRepository: InvoiceHeaderReviewRepository,
        invoiceLinesReviewRepository: InvoiceLinesReviewRepository,
        preparedPurchaseRepository: PreparedPurchaseRepository,
        productRepository: ProductRepository,
        unitRepository: UnitRepository,
        appClock: AppClock,
        readinessValidator: PurchaseReadinessValidator,
        observability: ProductionObservability,
    ): PreparePurchaseUseCase = PreparePurchaseUseCase(
        invoiceDraftRepository = invoiceDraftRepository,
        invoiceHeaderReviewRepository = invoiceHeaderReviewRepository,
        invoiceLinesReviewRepository = invoiceLinesReviewRepository,
        preparedPurchaseRepository = preparedPurchaseRepository,
        productRepository = productRepository,
        unitRepository = unitRepository,
        appClock = appClock,
        readinessValidator = readinessValidator,
        observability = observability,
    )

    @Provides
    fun providePullRemoteChangesUseCase(
        remoteLedgerRepository: RemoteLedgerRepository,
        remoteCatalogRepository: RemoteCatalogRepository,
        syncCursorRepository: SyncCursorRepository,
        remoteSyncCacheRepository: RemoteSyncCacheRepository,
        remoteCatalogApplicationRepository: RemoteCatalogApplicationRepository,
        cloudBusinessBindingRepository: CloudBusinessBindingRepository,
        remoteSaleSyncRepository: RemoteSaleSyncRepository,
        sharedInventoryApplicationRepository: SharedInventoryApplicationRepository,
        appClock: AppClock,
    ): PullRemoteChangesUseCase = PullRemoteChangesUseCase(
        remoteLedger = remoteLedgerRepository,
        cursors = syncCursorRepository,
        cache = remoteSyncCacheRepository,
        clock = appClock,
        remoteCatalog = remoteCatalogRepository,
        catalogApplications = remoteCatalogApplicationRepository,
        cloudBusinessBindings = cloudBusinessBindingRepository,
        remoteSales = remoteSaleSyncRepository,
        inventoryApplications = sharedInventoryApplicationRepository,
    )

    @Provides
    fun provideReconcileRemoteLedgerUseCase(
        remoteSyncCacheRepository: RemoteSyncCacheRepository,
        syncReconciliationRepository: SyncReconciliationRepository,
        cloudBusinessBindingRepository: CloudBusinessBindingRepository,
        appClock: AppClock,
    ): ReconcileRemoteLedgerUseCase = ReconcileRemoteLedgerUseCase(
        cache = remoteSyncCacheRepository,
        reconciliation = syncReconciliationRepository,
        clock = appClock,
        cloudBusinessBindings = cloudBusinessBindingRepository,
    )

    @Provides
    fun provideResolveSyncConflictUseCase(
        purchaseBackupOutboxRepository: PurchaseBackupOutboxRepository,
        retryPurchaseBackupUseCase: RetryPurchaseBackupUseCase,
        remoteCatalogApplicationRepository: RemoteCatalogApplicationRepository,
        purchaseBackupScheduler: PurchaseBackupScheduler,
        appClock: AppClock,
    ): ResolveSyncConflictUseCase = ResolveSyncConflictUseCase(
        outbox = purchaseBackupOutboxRepository,
        retryBackup = retryPurchaseBackupUseCase,
        clock = appClock,
        catalogConflicts = remoteCatalogApplicationRepository,
        scheduler = purchaseBackupScheduler,
    )

    @Provides
    fun provideRecordReconciliationReviewUseCase(
        auditTrailRepository: AuditTrailRepository,
        uuidGenerator: UuidGenerator,
        appClock: AppClock,
    ): RecordReconciliationReviewUseCase = RecordReconciliationReviewUseCase(
        auditTrail = auditTrailRepository,
        uuids = uuidGenerator,
        clock = appClock,
    )

    @Provides
    fun provideCreateSaleCartUseCase(
        configuration: AppConfigurationRepository,
        repository: SaleRepository,
    ): CreateSaleCartUseCase = CreateSaleCartUseCase(configuration, repository)

    @Provides
    fun provideObserveSaleCartUseCase(repository: SaleRepository): ObserveSaleCartUseCase =
        ObserveSaleCartUseCase(repository)

    @Provides
    fun provideObserveSalesReportUseCase(
        configuration: AppConfigurationRepository,
        repository: SaleRepository,
        appClock: AppClock,
    ): ObserveSalesReportUseCase = ObserveSalesReportUseCase(configuration, repository, appClock)

    @Provides
    fun provideObserveDebtsUseCase(
        configuration: AppConfigurationRepository,
        repository: DebtRepository,
    ): ObserveDebtsUseCase = ObserveDebtsUseCase(configuration, repository)

    @Provides
    fun provideObserveDebtDetailUseCase(
        configuration: AppConfigurationRepository,
        repository: DebtRepository,
    ): ObserveDebtDetailUseCase = ObserveDebtDetailUseCase(configuration, repository)

    @Provides
    fun provideRecordDebtPaymentUseCase(
        configuration: AppConfigurationRepository,
        repository: DebtRepository,
    ): RecordDebtPaymentUseCase = RecordDebtPaymentUseCase(configuration, repository)

    @Provides
    fun provideSaveSaleCartLineUseCase(
        configuration: AppConfigurationRepository,
        repository: SaleRepository,
    ): SaveSaleCartLineUseCase = SaveSaleCartLineUseCase(configuration, repository)

    @Provides
    fun provideRemoveSaleCartLineUseCase(
        configuration: AppConfigurationRepository,
        repository: SaleRepository,
    ): RemoveSaleCartLineUseCase = RemoveSaleCartLineUseCase(configuration, repository)

    @Provides
    fun provideCheckoutSaleUseCase(
        configuration: AppConfigurationRepository,
        repository: SaleRepository,
        purchaseBackupScheduler: PurchaseBackupScheduler,
    ): CheckoutSaleUseCase = CheckoutSaleUseCase(
        configuration = configuration,
        repository = repository,
        scheduler = purchaseBackupScheduler,
    )

}
