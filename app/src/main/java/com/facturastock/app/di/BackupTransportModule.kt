package com.facturastock.app.di

import com.facturastock.app.data.sync.UnavailablePurchaseBackupTransport
import com.facturastock.app.data.sync.UnavailableRemoteCatalogRepository
import com.facturastock.app.data.sync.UnavailableRemoteDocumentArchive
import com.facturastock.app.data.sync.UnavailableRemoteDebtSyncRepository
import com.facturastock.app.data.sync.UnavailableRemoteSaleSyncRepository
import com.facturastock.app.domain.repository.PurchaseBackupTransport
import com.facturastock.app.domain.repository.RemoteCatalogRepository
import com.facturastock.app.domain.repository.RemoteDocumentArchive
import com.facturastock.app.domain.repository.RemoteDebtSyncRepository
import com.facturastock.app.domain.repository.RemoteSaleSyncRepository
import dagger.Binds
import dagger.Module
import javax.inject.Singleton

/**
 * Flavor local: la outbox no tiene destino remoto. La cola queda en PENDING_SYNC y el
 * programador no encola trabajo; nada sale del dispositivo.
 */
@Module
abstract class BackupTransportModule {
    @Binds
    @Singleton
    abstract fun bindPurchaseBackupTransport(
        implementation: UnavailablePurchaseBackupTransport,
    ): PurchaseBackupTransport

    @Binds
    @Singleton
    abstract fun bindRemoteCatalogRepository(
        implementation: UnavailableRemoteCatalogRepository,
    ): RemoteCatalogRepository

    @Binds
    @Singleton
    abstract fun bindRemoteDocumentArchive(
        implementation: UnavailableRemoteDocumentArchive,
    ): RemoteDocumentArchive

    @Binds
    @Singleton
    abstract fun bindRemoteSaleSyncRepository(
        implementation: UnavailableRemoteSaleSyncRepository,
    ): RemoteSaleSyncRepository

    @Binds
    @Singleton
    abstract fun bindRemoteDebtSyncRepository(
        implementation: UnavailableRemoteDebtSyncRepository,
    ): RemoteDebtSyncRepository
}
