package com.facturastock.app.testing

import com.facturastock.app.di.AppConfigurationModule
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.config.CostPolicy
import com.facturastock.app.domain.config.TaxRate
import com.facturastock.app.domain.model.ImageRetentionPolicy
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.AppConfigurationRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Reemplazo de [AppConfigurationModule] para tests instrumentados: la compuerta de primer
 * inicio se controla fijando [TestAppConfigurationState.current] ANTES de lanzar la
 * actividad. El fake delega en ese MutableStateFlow compartido, así funciona aunque el
 * singleton de Hilt sobreviva a varias actividades dentro del mismo proceso de tests.
 */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [AppConfigurationModule::class],
)
abstract class TestAppConfigurationModule {
    @Binds
    @Singleton
    abstract fun bindAppConfigurationRepository(
        implementation: TestAppConfigurationRepository,
    ): AppConfigurationRepository
}

object TestAppConfigurationState {
    val current = MutableStateFlow(AppConfiguration.defaults())
}

/** Configuración con onboarding completado para que la compuerta deje pasar al grafo principal. */
fun completedGateConfiguration(): AppConfiguration = AppConfiguration.defaults().copy(
    onboardingCompleted = true,
    businessId = BusinessId.from(
        UUID.fromString("323e4567-e89b-42d3-a456-426614174000"),
    ),
)

@Singleton
class TestAppConfigurationRepository @Inject constructor() : AppConfigurationRepository {
    override fun observe(): Flow<AppConfiguration> = TestAppConfigurationState.current

    override suspend fun current(): AppConfiguration = TestAppConfigurationState.current.value

    override suspend fun completeOnboarding(
        businessId: BusinessId,
        taxRate: TaxRate,
        costPolicy: CostPolicy,
    ) {
        TestAppConfigurationState.current.value = TestAppConfigurationState.current.value.copy(
            onboardingCompleted = true,
            businessId = businessId,
            taxRate = taxRate,
            costPolicy = costPolicy,
        )
    }

    override suspend fun updateTaxRate(rate: TaxRate) {
        TestAppConfigurationState.current.value =
            TestAppConfigurationState.current.value.copy(taxRate = rate)
    }

    override suspend fun updateCostPolicy(policy: CostPolicy) {
        TestAppConfigurationState.current.value =
            TestAppConfigurationState.current.value.copy(costPolicy = policy)
    }

    override suspend fun updateImageRetentionPolicy(policy: ImageRetentionPolicy) {
        TestAppConfigurationState.current.value =
            TestAppConfigurationState.current.value.copy(imageRetentionPolicy = policy)
    }

    override suspend fun updateBackupEnabled(enabled: Boolean) {
        TestAppConfigurationState.current.value =
            TestAppConfigurationState.current.value.copy(backupEnabled = enabled)
    }

    override suspend fun updateDocumentBackupEnabled(enabled: Boolean) {
        TestAppConfigurationState.current.value =
            TestAppConfigurationState.current.value.copy(documentBackupEnabled = enabled)
    }

    override suspend fun updateDiagnosticsEnabled(enabled: Boolean) {
        TestAppConfigurationState.current.value =
            TestAppConfigurationState.current.value.copy(diagnosticsEnabled = enabled)
    }

    override suspend fun updateBiometricLockEnabled(enabled: Boolean) {
        TestAppConfigurationState.current.value =
            TestAppConfigurationState.current.value.copy(biometricLockEnabled = enabled)
    }

    override suspend fun enterDemoMode(demoBusinessId: BusinessId) {
        TestAppConfigurationState.current.value =
            TestAppConfigurationState.current.value.copy(demoBusinessId = demoBusinessId)
    }

    override suspend fun exitDemoMode() {
        TestAppConfigurationState.current.value =
            TestAppConfigurationState.current.value.copy(demoBusinessId = null)
    }
}
