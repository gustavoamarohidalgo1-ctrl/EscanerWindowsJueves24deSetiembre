package com.facturastock.app.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.facturastock.app.core.platform.AppDirectories
import okio.Path.Companion.toOkioPath
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.dao.AuditEventDao
import com.facturastock.app.data.local.dao.BusinessDao
import com.facturastock.app.data.local.dao.CatalogSyncLinkDao
import com.facturastock.app.data.local.dao.CloudBusinessBindingDao
import com.facturastock.app.data.local.dao.DebtDao
import com.facturastock.app.data.local.dao.InventoryDao
import com.facturastock.app.data.local.dao.InventoryLocationDao
import com.facturastock.app.data.local.dao.InvoiceDraftDao
import com.facturastock.app.data.local.dao.InvoiceHeaderEditDao
import com.facturastock.app.data.local.dao.InvoiceImageDao
import com.facturastock.app.data.local.dao.InvoiceLineDao
import com.facturastock.app.data.local.dao.InvoiceLinesEditDao
import com.facturastock.app.data.local.dao.InvoiceOcrSnapshotDao
import com.facturastock.app.data.local.dao.OutboxOperationDao
import com.facturastock.app.data.local.dao.ParsedInvoiceDao
import com.facturastock.app.data.local.dao.PreparedPurchaseDao
import com.facturastock.app.data.local.dao.PurchaseDao
import com.facturastock.app.data.local.dao.PurchaseLineDao
import com.facturastock.app.data.local.dao.PurchasePostingDao
import com.facturastock.app.data.local.dao.SaleDao
import com.facturastock.app.data.local.dao.ProductDao
import com.facturastock.app.data.local.dao.RemoteSyncDao
import com.facturastock.app.data.local.dao.SupplierDao
import com.facturastock.app.data.local.dao.SupplierProductAliasDao
import com.facturastock.app.data.local.dao.UnitDao
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

@Module
object PersistenceModule {
    @Provides
    @Singleton
    fun provideDatabase(
        directories: AppDirectories,
    ): FacturaStockDatabase = FacturaStockDatabase.build(
        directories.databaseFile(FacturaStockDatabase.NAME),
    )

    @Provides
    fun provideBusinessDao(database: FacturaStockDatabase): BusinessDao = database.businessDao()

    @Provides
    fun provideSupplierDao(database: FacturaStockDatabase): SupplierDao = database.supplierDao()

    @Provides
    fun provideUnitDao(database: FacturaStockDatabase): UnitDao = database.unitDao()

    @Provides
    fun provideInventoryLocationDao(
        database: FacturaStockDatabase,
    ): InventoryLocationDao = database.inventoryLocationDao()

    @Provides
    fun provideProductDao(database: FacturaStockDatabase): ProductDao = database.productDao()

    @Provides
    fun provideSupplierProductAliasDao(
        database: FacturaStockDatabase,
    ): SupplierProductAliasDao = database.supplierProductAliasDao()

    @Provides
    fun provideInvoiceDraftDao(database: FacturaStockDatabase): InvoiceDraftDao =
        database.invoiceDraftDao()

    @Provides
    fun provideInvoiceImageDao(database: FacturaStockDatabase): InvoiceImageDao =
        database.invoiceImageDao()

    @Provides
    fun provideInvoiceLineDao(database: FacturaStockDatabase): InvoiceLineDao =
        database.invoiceLineDao()

    @Provides
    fun provideInvoiceOcrSnapshotDao(database: FacturaStockDatabase): InvoiceOcrSnapshotDao =
        database.invoiceOcrSnapshotDao()

    @Provides
    fun provideParsedInvoiceDao(database: FacturaStockDatabase): ParsedInvoiceDao =
        database.parsedInvoiceDao()

    @Provides
    fun provideInvoiceHeaderEditDao(database: FacturaStockDatabase): InvoiceHeaderEditDao =
        database.invoiceHeaderEditDao()

    @Provides
    fun provideInvoiceLinesEditDao(database: FacturaStockDatabase): InvoiceLinesEditDao =
        database.invoiceLinesEditDao()

    @Provides
    fun providePreparedPurchaseDao(database: FacturaStockDatabase): PreparedPurchaseDao =
        database.preparedPurchaseDao()

    @Provides
    fun providePurchaseDao(database: FacturaStockDatabase): PurchaseDao = database.purchaseDao()

    @Provides
    fun providePurchaseLineDao(database: FacturaStockDatabase): PurchaseLineDao =
        database.purchaseLineDao()

    @Provides
    fun providePurchasePostingDao(database: FacturaStockDatabase): PurchasePostingDao =
        database.purchasePostingDao()

    @Provides
    fun provideSaleDao(database: FacturaStockDatabase): SaleDao = database.saleDao()

    @Provides
    fun provideDebtDao(database: FacturaStockDatabase): DebtDao = database.debtDao()

    @Provides
    fun provideInventoryDao(database: FacturaStockDatabase): InventoryDao = database.inventoryDao()

    @Provides
    fun provideAuditEventDao(database: FacturaStockDatabase): AuditEventDao =
        database.auditEventDao()

    @Provides
    fun provideOutboxOperationDao(database: FacturaStockDatabase): OutboxOperationDao =
        database.outboxOperationDao()

    @Provides
    fun provideRemoteSyncDao(database: FacturaStockDatabase): RemoteSyncDao =
        database.remoteSyncDao()

    @Provides
    fun provideCatalogSyncLinkDao(database: FacturaStockDatabase): CatalogSyncLinkDao =
        database.catalogSyncLinkDao()

    @Provides
    fun provideCloudBusinessBindingDao(database: FacturaStockDatabase): CloudBusinessBindingDao =
        database.cloudBusinessBindingDao()

    @Provides
    @Singleton
    fun provideAppSettingsDataStore(directories: AppDirectories): DataStore<Preferences> =
        PreferenceDataStoreFactory.createWithPath(
            produceFile = { directories.preferencesDataStoreFile("app_settings").toOkioPath() },
        )
}
