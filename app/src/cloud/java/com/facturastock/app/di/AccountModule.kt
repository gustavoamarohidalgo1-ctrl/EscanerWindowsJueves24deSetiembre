package com.facturastock.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.facturastock.app.data.account.CloudAccountSettingsStore
import com.facturastock.app.data.account.CloudPurchaseOverrideAuthorizationRepository
import com.facturastock.app.data.account.FirebaseAccountRepository
import com.facturastock.app.data.spark.ModeAwareBusinessMembershipRepository
import com.facturastock.app.data.sync.FirebaseRemoteLedgerRepository
import com.facturastock.app.domain.repository.AccountRepository
import com.facturastock.app.domain.repository.BusinessMembershipRepository
import com.facturastock.app.domain.repository.RemoteLedgerRepository
import com.facturastock.app.domain.repository.PurchaseOverrideAuthorizationRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

/** Marca el DataStore de ajustes de cuenta (`account_settings`), distinto del de `app_settings`. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CloudAccountSettings

/**
 * Flavor cloud: la cuenta usa Firebase Auth con correo verificado. Membresías selecciona los
 * callables endurecidos o el OWNER directo de Spark según la variante. El enlace local ↔ nube vive en su propio DataStore
 * (`account_settings`); sin configuración Firebase los puertos declaran `Unavailable`.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AccountModule {
    @Binds
    @Singleton
    abstract fun bindAccountRepository(
        implementation: FirebaseAccountRepository,
    ): AccountRepository

    @Binds
    @Singleton
    abstract fun bindBusinessMembershipRepository(
        implementation: ModeAwareBusinessMembershipRepository,
    ): BusinessMembershipRepository

    @Binds
    @Singleton
    abstract fun bindRemoteLedgerRepository(
        implementation: FirebaseRemoteLedgerRepository,
    ): RemoteLedgerRepository

    @Binds
    @Singleton
    abstract fun bindPurchaseOverrideAuthorizationRepository(
        implementation: CloudPurchaseOverrideAuthorizationRepository,
    ): PurchaseOverrideAuthorizationRepository

    companion object {
        @Provides
        @Singleton
        @CloudAccountSettings
        fun provideCloudAccountSettingsDataStore(
            @ApplicationContext context: Context,
        ): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                produceFile = { context.preferencesDataStoreFile("account_settings") },
            )

        @Provides
        @Singleton
        fun provideCloudAccountSettingsStore(
            @CloudAccountSettings dataStore: DataStore<Preferences>,
        ): CloudAccountSettingsStore = CloudAccountSettingsStore(dataStore)
    }
}
