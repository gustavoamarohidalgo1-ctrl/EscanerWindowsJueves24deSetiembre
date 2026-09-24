package com.facturastock.app.di

import com.facturastock.app.core.platform.AppDirectories
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.startup.DesktopStartup
import dagger.BindsInstance
import dagger.Component
import javax.inject.Singleton

/** Grafo único de la aplicación de escritorio; reemplaza al `SingletonComponent` de Hilt. */
@Singleton
@Component(
    modules = [
        AccountModule::class,
        AppConfigurationModule::class,
        BackupTransportModule::class,
        CoreModule::class,
        ObservabilityModule::class,
        ObservabilitySinkModule::class,
        OcrModule::class,
        PersistenceModule::class,
        RepositoryModule::class,
        UseCaseModule::class,
        ViewModelSubcomponentModule::class,
    ],
)
interface AppComponent {
    fun viewModelComponentFactory(): ViewModelComponent.Factory

    fun appConfigurationRepository(): AppConfigurationRepository

    fun database(): FacturaStockDatabase

    fun startup(): DesktopStartup

    @Component.Factory
    interface Factory {
        fun create(@BindsInstance directories: AppDirectories): AppComponent
    }
}
