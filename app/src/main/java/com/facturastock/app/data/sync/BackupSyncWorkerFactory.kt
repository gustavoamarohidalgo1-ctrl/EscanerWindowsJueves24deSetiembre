package com.facturastock.app.data.sync

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.observability.ProductionObservability
import com.facturastock.app.data.privacy.PrivacyMaintenanceWorker
import com.facturastock.app.domain.repository.AccountRepository
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.BusinessMembershipRepository
import com.facturastock.app.domain.repository.CatalogSyncBootstrapRepository
import com.facturastock.app.domain.repository.CloudBusinessBindingRepository
import com.facturastock.app.domain.repository.DocumentBackupLifecycleRepository
import com.facturastock.app.domain.repository.PurchaseBackupScheduler
import com.facturastock.app.domain.usecase.ProcessPurchaseBackupOutboxUseCase
import com.facturastock.app.domain.usecase.PullRemoteChangesUseCase
import com.facturastock.app.domain.usecase.RunPrivacyMaintenanceUseCase
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Factoría inyectada por Hilt: la aplicación la instala vía `Configuration.Provider` y el
 * worker recibe sus dependencias por constructor, sin `ServiceLocator` ni acceso global.
 * Devuelve `null` para cualquier worker desconocido y WorkManager usa su factoría por defecto.
 */
@Singleton
class BackupSyncWorkerFactory @Inject constructor(
    private val processor: Provider<ProcessPurchaseBackupOutboxUseCase>,
    private val scheduler: Provider<PurchaseBackupScheduler>,
    private val observability: Provider<ProductionObservability>,
    private val accountRepository: Provider<AccountRepository>,
    private val membershipRepository: Provider<BusinessMembershipRepository>,
    private val pullRemoteChanges: Provider<PullRemoteChangesUseCase>,
    private val appConfiguration: Provider<AppConfigurationRepository>,
    private val cloudBusinessBindings: Provider<CloudBusinessBindingRepository>,
    private val catalogBootstrap: Provider<CatalogSyncBootstrapRepository>,
    private val privacyMaintenance: Provider<RunPrivacyMaintenanceUseCase>,
    private val documentLifecycle: Provider<DocumentBackupLifecycleRepository>,
    private val appClock: Provider<AppClock>,
) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        PurchaseBackupSyncWorker::class.java.name -> PurchaseBackupSyncWorker(
            appContext = appContext,
            params = workerParameters,
            processor = processor.get(),
            scheduler = scheduler.get(),
            observability = observability.get(),
            accountRepository = accountRepository.get(),
            membershipRepository = membershipRepository.get(),
            pullRemoteChanges = pullRemoteChanges.get(),
            appConfiguration = appConfiguration.get(),
            cloudBusinessBindings = cloudBusinessBindings.get(),
            catalogBootstrap = catalogBootstrap.get(),
            documentLifecycle = documentLifecycle.get(),
            appClock = appClock.get(),
        )
        PrivacyMaintenanceWorker::class.java.name -> PrivacyMaintenanceWorker(
            appContext = appContext,
            params = workerParameters,
            runPrivacyMaintenance = privacyMaintenance.get(),
        )
        else -> null
    }
}
