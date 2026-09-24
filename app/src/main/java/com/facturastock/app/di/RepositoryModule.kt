package com.facturastock.app.di

import com.facturastock.app.data.files.LocalDraftFileStore
import com.facturastock.app.data.files.LocalDraftImageImporter
import com.facturastock.app.data.files.LocalDocumentUploadPreparer
import com.facturastock.app.data.files.LocalImageQualityAnalyzer
import com.facturastock.app.data.files.LocalInvoiceImagePreprocessor
import com.facturastock.app.data.files.LocalRetainedImageStore
import com.facturastock.app.data.files.LocalRetentionFileSweep
import com.facturastock.app.data.export.DesktopReportPdfWriter
import com.facturastock.app.data.repository.RoomReportPdfRepository
import com.facturastock.app.domain.repository.ReportPdfRepository
import com.facturastock.app.domain.repository.ReportPdfWriter
import com.facturastock.app.data.export.DesktopUserDataExportWriter
import com.facturastock.app.data.privacy.DesktopPrivacyMaintenanceScheduler
import com.facturastock.app.data.demo.LocalDemoInvoiceSource
import com.facturastock.app.data.repository.RoomDraftWorkflowRepository
import com.facturastock.app.data.repository.RoomDocumentBackupLifecycleRepository
import com.facturastock.app.data.repository.RoomDebtRepository
import com.facturastock.app.data.repository.RoomPurchaseBackupOutboxRepository
import com.facturastock.app.data.repository.RoomCloudBusinessBindingRepository
import com.facturastock.app.data.repository.RoomPurchaseBackupRepository
import com.facturastock.app.data.repository.RoomCatalogSyncBootstrapRepository
import com.facturastock.app.data.repository.RoomBusinessRepository
import com.facturastock.app.data.repository.RoomInventoryLocationRepository
import com.facturastock.app.data.repository.RoomInventoryReadRepository
import com.facturastock.app.data.repository.RoomHomeDashboardReadRepository
import com.facturastock.app.data.repository.RoomInvoiceDraftRepository
import com.facturastock.app.data.repository.RoomInvoiceHeaderReviewRepository
import com.facturastock.app.data.repository.RoomInvoiceLinesReviewRepository
import com.facturastock.app.data.repository.RoomInvoiceOcrSnapshotRepository
import com.facturastock.app.data.repository.RoomManualInvoiceReviewRepository
import com.facturastock.app.data.repository.RoomOnboardingProvisioningRepository
import com.facturastock.app.data.repository.RoomParsedInvoiceRepository
import com.facturastock.app.data.repository.RoomPreparedPurchaseRepository
import com.facturastock.app.data.repository.RoomPurchaseRepository
import com.facturastock.app.data.repository.RoomPurchaseReadRepository
import com.facturastock.app.data.repository.RoomRecentDraftReadRepository
import com.facturastock.app.data.repository.RoomPurchasePostingRepository
import com.facturastock.app.data.repository.RoomPurchaseVoidRepository
import com.facturastock.app.data.repository.RoomSaleRepository
import com.facturastock.app.data.repository.RoomSaleVoidRepository
import com.facturastock.app.data.repository.RoomRemoteSyncCacheRepository
import com.facturastock.app.data.repository.RoomRemoteCatalogApplicationRepository
import com.facturastock.app.data.repository.RoomSharedInventoryApplicationRepository
import com.facturastock.app.data.repository.RoomAuditTrailRepository
import com.facturastock.app.data.repository.RoomProductRepository
import com.facturastock.app.data.repository.RoomProductProfitRepository
import com.facturastock.app.data.repository.RoomProductInventoryRepository
import com.facturastock.app.data.repository.RoomInvoiceMatchingCommitRepository
import com.facturastock.app.domain.repository.InvoiceMatchingCommitRepository
import com.facturastock.app.data.repository.RoomProductRegistrationRepository
import com.facturastock.app.data.repository.RoomProductEditingRepository
import com.facturastock.app.domain.repository.ProductEditingRepository
import com.facturastock.app.data.repository.RoomSupplierProductAliasRepository
import com.facturastock.app.data.repository.RoomSupplierRepository
import com.facturastock.app.data.repository.RoomSyncReconciliationRepository
import com.facturastock.app.data.repository.RoomUnitRepository
import com.facturastock.app.data.sync.NoOpPurchaseBackupScheduler
import com.facturastock.app.domain.repository.BusinessRepository
import com.facturastock.app.domain.repository.CatalogSyncBootstrapRepository
import com.facturastock.app.domain.repository.DraftFileStore
import com.facturastock.app.domain.repository.DraftImageImporter
import com.facturastock.app.domain.repository.DraftWorkflowRepository
import com.facturastock.app.domain.repository.DocumentUploadPreparer
import com.facturastock.app.domain.repository.DocumentBackupLifecycleRepository
import com.facturastock.app.domain.repository.DemoInvoiceSource
import com.facturastock.app.domain.repository.DebtRepository
import com.facturastock.app.domain.repository.InventoryLocationRepository
import com.facturastock.app.domain.repository.InventoryReadRepository
import com.facturastock.app.domain.repository.HomeDashboardReadRepository
import com.facturastock.app.domain.repository.ImageQualityAnalyzer
import com.facturastock.app.domain.repository.InvoiceDraftRepository
import com.facturastock.app.domain.repository.InvoiceHeaderReviewRepository
import com.facturastock.app.domain.repository.InvoiceLinesReviewRepository
import com.facturastock.app.domain.repository.InvoiceImagePreprocessor
import com.facturastock.app.domain.repository.InvoiceOcrSnapshotRepository
import com.facturastock.app.domain.repository.ManualInvoiceReviewRepository
import com.facturastock.app.domain.repository.OnboardingProvisioningRepository
import com.facturastock.app.domain.repository.ParsedInvoiceRepository
import com.facturastock.app.domain.repository.PreparedPurchaseRepository
import com.facturastock.app.domain.repository.PurchaseBackupOutboxRepository
import com.facturastock.app.domain.repository.CloudBusinessBindingRepository
import com.facturastock.app.domain.repository.PurchaseBackupRepository
import com.facturastock.app.domain.repository.PurchaseBackupScheduler
import com.facturastock.app.domain.repository.PrivacyMaintenanceScheduler
import com.facturastock.app.domain.repository.PurchaseRepository
import com.facturastock.app.domain.repository.PurchaseReadRepository
import com.facturastock.app.domain.repository.RecentDraftReadRepository
import com.facturastock.app.domain.repository.PurchasePostingRepository
import com.facturastock.app.domain.repository.PurchaseVoidRepository
import com.facturastock.app.domain.repository.SaleRepository
import com.facturastock.app.domain.repository.SaleVoidRepository
import com.facturastock.app.domain.repository.AuditTrailRepository
import com.facturastock.app.domain.repository.ProductRepository
import com.facturastock.app.domain.repository.ProductProfitRepository
import com.facturastock.app.domain.repository.ProductInventoryRepository
import com.facturastock.app.domain.repository.ProductRegistrationRepository
import com.facturastock.app.domain.repository.RetainedImageStore
import com.facturastock.app.domain.repository.RemoteSyncCacheRepository
import com.facturastock.app.domain.repository.RemoteCatalogApplicationRepository
import com.facturastock.app.domain.repository.SharedInventoryApplicationRepository
import com.facturastock.app.domain.repository.RetentionFileSweep
import com.facturastock.app.domain.repository.SupplierProductAliasRepository
import com.facturastock.app.domain.repository.SupplierRepository
import com.facturastock.app.domain.repository.SyncReconciliationRepository
import com.facturastock.app.domain.repository.SyncCursorRepository
import com.facturastock.app.domain.repository.UnitRepository
import com.facturastock.app.domain.repository.UserDataExportWriter
import dagger.Binds
import dagger.Module
import javax.inject.Singleton

@Module
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindHomeDashboardReadRepository(
        implementation: RoomHomeDashboardReadRepository,
    ): HomeDashboardReadRepository

    @Binds
    @Singleton
    abstract fun bindPrivacyMaintenanceScheduler(
        implementation: DesktopPrivacyMaintenanceScheduler,
    ): PrivacyMaintenanceScheduler

    @Binds
    @Singleton
    abstract fun bindDemoInvoiceSource(
        implementation: LocalDemoInvoiceSource,
    ): DemoInvoiceSource

    @Binds
    @Singleton
    abstract fun bindDraftWorkflowRepository(
        implementation: RoomDraftWorkflowRepository,
    ): DraftWorkflowRepository

    @Binds
    @Singleton
    abstract fun bindPurchaseBackupRepository(
        implementation: RoomPurchaseBackupRepository,
    ): PurchaseBackupRepository

    @Binds
    @Singleton
    abstract fun bindPurchaseBackupOutboxRepository(
        implementation: RoomPurchaseBackupOutboxRepository,
    ): PurchaseBackupOutboxRepository

    @Binds
    @Singleton
    abstract fun bindCloudBusinessBindingRepository(
        implementation: RoomCloudBusinessBindingRepository,
    ): CloudBusinessBindingRepository

    @Binds
    @Singleton
    abstract fun bindPurchaseBackupScheduler(
        implementation: NoOpPurchaseBackupScheduler,
    ): PurchaseBackupScheduler

    @Binds
    @Singleton
    abstract fun bindSyncReconciliationRepository(
        implementation: RoomSyncReconciliationRepository,
    ): SyncReconciliationRepository

    @Binds
    @Singleton
    abstract fun bindRemoteSyncCacheRepository(
        implementation: RoomRemoteSyncCacheRepository,
    ): RemoteSyncCacheRepository

    @Binds
    @Singleton
    abstract fun bindRemoteCatalogApplicationRepository(
        implementation: RoomRemoteCatalogApplicationRepository,
    ): RemoteCatalogApplicationRepository

    @Binds
    @Singleton
    abstract fun bindSharedInventoryApplicationRepository(
        implementation: RoomSharedInventoryApplicationRepository,
    ): SharedInventoryApplicationRepository

    @Binds
    @Singleton
    abstract fun bindCatalogSyncBootstrapRepository(
        implementation: RoomCatalogSyncBootstrapRepository,
    ): CatalogSyncBootstrapRepository

    @Binds
    @Singleton
    abstract fun bindSyncCursorRepository(
        implementation: RoomRemoteSyncCacheRepository,
    ): SyncCursorRepository

    @Binds
    @Singleton
    abstract fun bindAuditTrailRepository(
        implementation: RoomAuditTrailRepository,
    ): AuditTrailRepository

    @Binds
    @Singleton
    abstract fun bindPurchaseRepository(
        implementation: RoomPurchaseRepository,
    ): PurchaseRepository

    @Binds
    @Singleton
    abstract fun bindPurchaseReadRepository(
        implementation: RoomPurchaseReadRepository,
    ): PurchaseReadRepository

    @Binds
    @Singleton
    abstract fun bindRecentDraftReadRepository(
        implementation: RoomRecentDraftReadRepository,
    ): RecentDraftReadRepository

    @Binds
    @Singleton
    abstract fun bindPurchasePostingRepository(
        implementation: RoomPurchasePostingRepository,
    ): PurchasePostingRepository

    @Binds
    @Singleton
    abstract fun bindPurchaseVoidRepository(
        implementation: RoomPurchaseVoidRepository,
    ): PurchaseVoidRepository

    @Binds
    @Singleton
    abstract fun bindSaleRepository(implementation: RoomSaleRepository): SaleRepository

    @Binds
    @Singleton
    abstract fun bindSaleVoidRepository(implementation: RoomSaleVoidRepository): SaleVoidRepository

    @Binds
    @Singleton
    abstract fun bindDebtRepository(implementation: RoomDebtRepository): DebtRepository

    @Binds
    @Singleton
    abstract fun bindBusinessRepository(
        implementation: RoomBusinessRepository,
    ): BusinessRepository

    @Binds
    @Singleton
    abstract fun bindOnboardingProvisioningRepository(
        implementation: RoomOnboardingProvisioningRepository,
    ): OnboardingProvisioningRepository

    @Binds
    @Singleton
    abstract fun bindSupplierRepository(
        implementation: RoomSupplierRepository,
    ): SupplierRepository

    @Binds
    @Singleton
    abstract fun bindUnitRepository(
        implementation: RoomUnitRepository,
    ): UnitRepository

    @Binds
    @Singleton
    abstract fun bindInventoryLocationRepository(
        implementation: RoomInventoryLocationRepository,
    ): InventoryLocationRepository

    @Binds
    @Singleton
    abstract fun bindInventoryReadRepository(
        implementation: RoomInventoryReadRepository,
    ): InventoryReadRepository

    @Binds
    @Singleton
    abstract fun bindProductRepository(
        implementation: RoomProductRepository,
    ): ProductRepository

    @Binds
    @Singleton
    abstract fun bindProductProfitRepository(
        implementation: RoomProductProfitRepository,
    ): ProductProfitRepository

    @Binds
    @Singleton
    abstract fun bindProductInventoryRepository(
        implementation: RoomProductInventoryRepository,
    ): ProductInventoryRepository

    @Binds
    @Singleton
    abstract fun bindInvoiceMatchingCommitRepository(
        implementation: RoomInvoiceMatchingCommitRepository,
    ): InvoiceMatchingCommitRepository

    @Binds
    @Singleton
    abstract fun bindProductRegistrationRepository(
        implementation: RoomProductRegistrationRepository,
    ): ProductRegistrationRepository

    @Binds
    @Singleton
    abstract fun bindProductEditingRepository(
        implementation: RoomProductEditingRepository,
    ): ProductEditingRepository

    @Binds
    @Singleton
    abstract fun bindSupplierProductAliasRepository(
        implementation: RoomSupplierProductAliasRepository,
    ): SupplierProductAliasRepository

    @Binds
    @Singleton
    abstract fun bindInvoiceDraftRepository(
        implementation: RoomInvoiceDraftRepository,
    ): InvoiceDraftRepository

    @Binds
    @Singleton
    abstract fun bindManualInvoiceReviewRepository(
        implementation: RoomManualInvoiceReviewRepository,
    ): ManualInvoiceReviewRepository

    @Binds
    @Singleton
    abstract fun bindInvoiceOcrSnapshotRepository(
        implementation: RoomInvoiceOcrSnapshotRepository,
    ): InvoiceOcrSnapshotRepository

    @Binds
    @Singleton
    abstract fun bindParsedInvoiceRepository(
        implementation: RoomParsedInvoiceRepository,
    ): ParsedInvoiceRepository

    @Binds
    @Singleton
    abstract fun bindInvoiceHeaderReviewRepository(
        implementation: RoomInvoiceHeaderReviewRepository,
    ): InvoiceHeaderReviewRepository

    @Binds
    @Singleton
    abstract fun bindInvoiceLinesReviewRepository(
        implementation: RoomInvoiceLinesReviewRepository,
    ): InvoiceLinesReviewRepository

    @Binds
    @Singleton
    abstract fun bindPreparedPurchaseRepository(
        implementation: RoomPreparedPurchaseRepository,
    ): PreparedPurchaseRepository

    @Binds
    @Singleton
    abstract fun bindDraftFileStore(
        implementation: LocalDraftFileStore,
    ): DraftFileStore

    @Binds
    @Singleton
    abstract fun bindDraftImageImporter(
        implementation: LocalDraftImageImporter,
    ): DraftImageImporter

    @Binds
    @Singleton
    abstract fun bindImageQualityAnalyzer(
        implementation: LocalImageQualityAnalyzer,
    ): ImageQualityAnalyzer

    @Binds
    @Singleton
    abstract fun bindInvoiceImagePreprocessor(
        implementation: LocalInvoiceImagePreprocessor,
    ): InvoiceImagePreprocessor

    @Binds
    @Singleton
    abstract fun bindRetainedImageStore(
        implementation: LocalRetainedImageStore,
    ): RetainedImageStore

    @Binds
    @Singleton
    abstract fun bindDocumentUploadPreparer(
        implementation: LocalDocumentUploadPreparer,
    ): DocumentUploadPreparer

    @Binds
    @Singleton
    abstract fun bindRetentionFileSweep(
        implementation: LocalRetentionFileSweep,
    ): RetentionFileSweep

    @Binds
    @Singleton
    abstract fun bindReportPdfRepository(implementation: RoomReportPdfRepository): ReportPdfRepository

    @Binds
    @Singleton
    abstract fun bindReportPdfWriter(implementation: DesktopReportPdfWriter): ReportPdfWriter

    @Binds
    @Singleton
    abstract fun bindUserDataExportWriter(
        implementation: DesktopUserDataExportWriter,
    ): UserDataExportWriter

    @Binds
    @Singleton
    abstract fun bindDocumentBackupLifecycleRepository(
        implementation: RoomDocumentBackupLifecycleRepository,
    ): DocumentBackupLifecycleRepository
}
