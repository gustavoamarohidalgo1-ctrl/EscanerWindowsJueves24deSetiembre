package com.facturastock.app.di

import com.facturastock.app.data.settings.DataStoreAppConfigurationRepository
import com.facturastock.app.domain.repository.AppConfigurationRepository
import dagger.Binds
import dagger.Module
import javax.inject.Singleton

/**
 * Binding de la configuración global en módulo propio: los tests instrumentados lo
 * reemplazan con `@TestInstallIn` sin tocar los demás bindings de repositorios.
 */
@Module
abstract class AppConfigurationModule {
    @Binds
    @Singleton
    abstract fun bindAppConfigurationRepository(
        implementation: DataStoreAppConfigurationRepository,
    ): AppConfigurationRepository
}
