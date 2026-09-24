package com.facturastock.app.testing

import com.facturastock.app.core.platform.AppDirectories
import com.facturastock.app.di.AccountModule
import com.facturastock.app.di.AppComponent
import com.facturastock.app.di.BackupTransportModule
import com.facturastock.app.di.CoreModule
import com.facturastock.app.di.ObservabilityModule
import com.facturastock.app.di.ObservabilitySinkModule
import com.facturastock.app.di.PersistenceModule
import com.facturastock.app.di.RepositoryModule
import com.facturastock.app.di.UseCaseModule
import com.facturastock.app.di.ViewModelSubcomponentModule
import com.facturastock.app.domain.repository.BusinessRepository
import com.facturastock.app.domain.repository.DebtRepository
import com.facturastock.app.domain.repository.InventoryLocationRepository
import com.facturastock.app.domain.repository.InvoiceDraftRepository
import com.facturastock.app.domain.repository.ProductInventoryRepository
import com.facturastock.app.domain.repository.ProductRegistrationRepository
import com.facturastock.app.domain.repository.ProductRepository
import com.facturastock.app.domain.repository.SaleRepository
import com.facturastock.app.domain.repository.UnitRepository
import dagger.BindsInstance
import dagger.Component
import javax.inject.Singleton

/**
 * Grafo real de la aplicación con los mismos reemplazos que usaban los tests instrumentados con
 * `@TestInstallIn`: [TestAppConfigurationModule] en lugar de `AppConfigurationModule` y
 * [TestOcrModule] en lugar de `OcrModule`. El resto (Room, repositorios, casos de uso y
 * ViewModels) es el de producción sobre un directorio temporal.
 */
@Singleton
@Component(
    modules = [
        AccountModule::class,
        TestAppConfigurationModule::class,
        BackupTransportModule::class,
        CoreModule::class,
        ObservabilityModule::class,
        ObservabilitySinkModule::class,
        TestOcrModule::class,
        PersistenceModule::class,
        RepositoryModule::class,
        UseCaseModule::class,
        ViewModelSubcomponentModule::class,
    ],
)
interface TestAppComponent : AppComponent {
    fun businesses(): BusinessRepository

    fun units(): UnitRepository

    fun locations(): InventoryLocationRepository

    fun products(): ProductRepository

    fun productInventory(): ProductInventoryRepository

    fun productRegistration(): ProductRegistrationRepository

    fun sales(): SaleRepository

    fun debts(): DebtRepository

    fun invoiceDrafts(): InvoiceDraftRepository

    @Component.Factory
    interface Factory {
        fun create(@BindsInstance directories: AppDirectories): TestAppComponent
    }
}
