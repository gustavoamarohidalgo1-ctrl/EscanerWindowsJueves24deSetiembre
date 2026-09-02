package com.facturastock.app.di

import com.facturastock.app.data.sync.FirebasePurchaseBackupTransport
import com.facturastock.app.data.sync.FirebaseRemoteDocumentArchive
import com.facturastock.app.data.spark.ModeAwareRemoteCatalogRepository
import com.facturastock.app.data.spark.ModeAwareRemoteDebtSyncRepository
import com.facturastock.app.data.spark.ModeAwareRemoteSaleSyncRepository
import com.facturastock.app.domain.repository.PurchaseBackupTransport
import com.facturastock.app.domain.repository.RemoteCatalogRepository
import com.facturastock.app.domain.repository.RemoteDocumentArchive
import com.facturastock.app.domain.repository.RemoteDebtSyncRepository
import com.facturastock.app.domain.repository.RemoteSaleSyncRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Flavor cloud: el respaldo opcional usa Firebase (Emulator Suite en debug, `local.properties`
 * en release/Spark). Los delegados conservan Functions en CALLABLES y usan Firestore directo
 * solo en SPARK_DIRECT. Sin configuración nada cambia en el flujo local.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class BackupTransportModule {
    @Binds
    @Singleton
    abstract fun bindPurchaseBackupTransport(
        implementation: FirebasePurchaseBackupTransport,
    ): PurchaseBackupTransport

    @Binds
    @Singleton
    abstract fun bindRemoteCatalogRepository(
        implementation: ModeAwareRemoteCatalogRepository,
    ): RemoteCatalogRepository

    @Binds
    @Singleton
    abstract fun bindRemoteDocumentArchive(
        implementation: FirebaseRemoteDocumentArchive,
    ): RemoteDocumentArchive

    @Binds
    @Singleton
    abstract fun bindRemoteSaleSyncRepository(
        implementation: ModeAwareRemoteSaleSyncRepository,
    ): RemoteSaleSyncRepository

    @Binds
    @Singleton
    abstract fun bindRemoteDebtSyncRepository(
        implementation: ModeAwareRemoteDebtSyncRepository,
    ): RemoteDebtSyncRepository
}
