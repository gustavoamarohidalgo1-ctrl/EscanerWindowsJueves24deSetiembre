package com.facturastock.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.facturastock.app.core.coroutines.ApplicationScope
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.data.local.storageCatching
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.config.CostPolicy
import com.facturastock.app.domain.config.TaxRate
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.ImageRetentionPolicy
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.OnboardingProvisionIds
import com.facturastock.app.domain.repository.OnboardingReservation
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * Implementación DataStore de [AppConfigurationRepository]. Cada escritura toca únicamente
 * sus claves (actualización parcial) y traduce los fallos de E/S a
 * `StorageException(StorageError.Unavailable)`; la cancelación se relanza intacta.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreAppConfigurationRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val dispatchers: DispatcherProvider,
    private val identityCoordinator: AppIdentityMutationCoordinator,
    @ApplicationScope applicationScope: CoroutineScope,
) : AppConfigurationRepository {
    private val observationGeneration = MutableStateFlow(0L)

    /**
     * MainActivity y la compuerta de navegacion observan la misma configuracion al arrancar. Un
     * upstream compartido evita repetir lectura/mapeo mientras ambas estan activas. Cada retry
     * incrementa una generación y reinicia DataStore incluso si un tercer observador mantiene
     * vivo el upstream; el replay de una generación anterior se descarta antes de llegar a la UI.
     *
     * Las lecturas imperativas [current] siguen consultando DataStore directamente: una escritura
     * que acaba de completar no debe depender de que el colector compartido ya haya procesado su
     * nueva emision.
     */
    private val observedConfiguration: SharedFlow<VersionedConfigurationRead> =
        observationGeneration
            .flatMapLatest { generation ->
                dataStore.data
                    .map<Preferences, VersionedConfigurationRead> { preferences ->
                        VersionedConfigurationRead(
                            generation = generation,
                            read = ConfigurationRead.Available(
                                AppSettingsDataStore.toAppConfiguration(preferences),
                            ),
                        )
                    }
                    .catch { failure ->
                        if (failure is CancellationException) throw failure
                        emit(
                            VersionedConfigurationRead(
                                generation = generation,
                                read = ConfigurationRead.Failed(failure.asConfigurationFailure()),
                            ),
                        )
                    }
            }
            .flowOn(dispatchers.io)
            .shareIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(
                    stopTimeoutMillis = 0L,
                    replayExpirationMillis = 0L,
                ),
                replay = 1,
            )

    override fun observe(): Flow<AppConfiguration> = observedConfiguration
        // Un retry puede ocurrir mientras el replay aún contiene el fallo anterior. La versión
        // evita entregarlo a los nuevos colectores aunque otro observador mantenga vivo shareIn.
        .filter { versioned -> versioned.generation == observationGeneration.value }
        .map { versioned ->
            when (val read = versioned.read) {
                is ConfigurationRead.Available -> read.configuration
                is ConfigurationRead.Failed -> throw read.failure
            }
        }

    override fun retryObservation() {
        observationGeneration.update { generation -> Math.incrementExact(generation) }
    }

    override suspend fun current(): AppConfiguration = withContext(dispatchers.io) {
        storageCatching { AppSettingsDataStore.toAppConfiguration(dataStore.data.first()) }
    }

    private sealed interface ConfigurationRead {
        data class Available(val configuration: AppConfiguration) : ConfigurationRead
        data class Failed(val failure: Throwable) : ConfigurationRead
    }

    private data class VersionedConfigurationRead(
        val generation: Long,
        val read: ConfigurationRead,
    )

    private fun Throwable.asConfigurationFailure(): Throwable = when (this) {
        is StorageException -> this
        is IOException -> StorageException(StorageError.Unavailable, this)
        else -> this
    }

    override suspend fun reserveOnboardingProvision(
        proposed: OnboardingProvisionIds,
    ): OnboardingReservation = withContext(dispatchers.io) {
        storageCatching {
            var result: OnboardingReservation? = null
            dataStore.edit { preferences ->
                if (preferences[AppSettingsDataStore.ONBOARDING_COMPLETED] == true) {
                    val completedBusinessId = preferences[AppSettingsDataStore.BUSINESS_ID]
                        ?.let(BusinessId::parse)
                        ?: corruptOnboardingCheckpoint(
                            "onboarding completado sin businessId canónico",
                        )
                    clearPendingOnboarding(preferences)
                    result = OnboardingReservation.AlreadyCompleted(completedBusinessId)
                    return@edit
                }

                val existing = readPendingOnboarding(preferences)
                val selected = existing ?: proposed.also { reservation ->
                    preferences[AppSettingsDataStore.PENDING_ONBOARDING_BUSINESS_ID] =
                        reservation.businessId.value
                    preferences[AppSettingsDataStore.PENDING_ONBOARDING_WAREHOUSE_ID] =
                        reservation.warehouseId.value
                    preferences[AppSettingsDataStore.PENDING_ONBOARDING_UNIT_ID] =
                        reservation.defaultUnitId.value
                }
                result = OnboardingReservation.Pending(selected)
            }
            checkNotNull(result) { "DataStore no devolvió la reserva de onboarding" }
        }
    }

    override suspend fun finalizeOnboardingProvision(
        reservation: OnboardingProvisionIds,
        provisionedBusinessId: BusinessId,
        taxRate: TaxRate,
        costPolicy: CostPolicy,
    ) {
        identityCoordinator.mutate {
            withContext(dispatchers.io) {
                storageCatching {
                    dataStore.edit { preferences ->
                        if (preferences[AppSettingsDataStore.ONBOARDING_COMPLETED] == true) {
                            val completedBusinessId = preferences[AppSettingsDataStore.BUSINESS_ID]
                                ?.let(BusinessId::parse)
                                ?: corruptOnboardingCheckpoint(
                                    "onboarding completado sin businessId canónico",
                                )
                            val completedReservation = readCompletedOnboarding(preferences)
                                ?: onboardingCheckpointConflict(
                                    "el onboarding completado no conserva una reserva verificable",
                                )
                            if (
                                completedBusinessId != provisionedBusinessId ||
                                completedReservation != reservation
                            ) {
                                onboardingCheckpointConflict(
                                    "otra invocación completó una reserva distinta",
                                )
                            }
                            clearPendingOnboarding(preferences)
                            return@edit
                        }

                        val pending = readPendingOnboarding(preferences)
                            ?: corruptOnboardingCheckpoint("falta la reserva pendiente")
                        if (pending != reservation) {
                            onboardingCheckpointConflict("la reserva pendiente cambió")
                        }
                        preferences[AppSettingsDataStore.ONBOARDING_COMPLETED] = true
                        preferences[AppSettingsDataStore.BUSINESS_ID] = provisionedBusinessId.value
                        preferences[AppSettingsDataStore.TAX_RATE_PERCENT] =
                            taxRate.percent.toPlainString()
                        preferences[AppSettingsDataStore.COST_POLICY] = costPolicy.name
                        preferences[AppSettingsDataStore.COMPLETED_ONBOARDING_BUSINESS_ID] =
                            reservation.businessId.value
                        preferences[AppSettingsDataStore.COMPLETED_ONBOARDING_WAREHOUSE_ID] =
                            reservation.warehouseId.value
                        preferences[AppSettingsDataStore.COMPLETED_ONBOARDING_UNIT_ID] =
                            reservation.defaultUnitId.value
                        clearPendingOnboarding(preferences)
                    }
                }
            }
        }
    }

    override suspend fun completeOnboarding(
        businessId: BusinessId,
        taxRate: TaxRate,
        costPolicy: CostPolicy,
    ) {
        editActiveBusiness { preferences ->
            preferences[AppSettingsDataStore.ONBOARDING_COMPLETED] = true
            preferences[AppSettingsDataStore.BUSINESS_ID] = businessId.value
            preferences[AppSettingsDataStore.TAX_RATE_PERCENT] = taxRate.percent.toPlainString()
            preferences[AppSettingsDataStore.COST_POLICY] = costPolicy.name
            clearPendingOnboarding(preferences)
            clearCompletedOnboarding(preferences)
        }
    }

    override suspend fun updateTaxRate(rate: TaxRate) {
        edit { preferences ->
            preferences[AppSettingsDataStore.TAX_RATE_PERCENT] = rate.percent.toPlainString()
        }
    }

    override suspend fun updateCostPolicy(policy: CostPolicy) {
        edit { preferences ->
            preferences[AppSettingsDataStore.COST_POLICY] = policy.name
        }
    }

    override suspend fun updateImageRetentionPolicy(policy: ImageRetentionPolicy) {
        edit { preferences ->
            preferences[AppSettingsDataStore.IMAGE_RETENTION_POLICY] = policy.name
        }
    }

    override suspend fun requestForceImageDeletion(requestedAt: Instant) {
        require(!requestedAt.isBefore(Instant.EPOCH))
        edit { preferences ->
            val requestedMillis = requestedAt.toEpochMilli()
            val currentMillis =
                preferences[AppSettingsDataStore.FORCE_IMAGE_DELETION_REQUESTED_AT]
            // Un reloj que retrocede o un segundo caller nunca debe reducir el snapshot ya
            // aceptado. Una solicitud posterior sí amplía explícitamente el cutoff.
            preferences[AppSettingsDataStore.FORCE_IMAGE_DELETION_REQUESTED_AT] =
                maxOf(currentMillis ?: requestedMillis, requestedMillis)
        }
    }

    override suspend fun forceImageDeletionRequestedAt(): Instant? =
        withContext(dispatchers.io) {
            storageCatching {
                dataStore.data.first()[AppSettingsDataStore.FORCE_IMAGE_DELETION_REQUESTED_AT]
                    ?.takeIf { it >= 0L }
                    ?.let(Instant::ofEpochMilli)
            }
        }

    override suspend fun clearForceImageDeletionRequest() {
        edit { preferences ->
            preferences.remove(AppSettingsDataStore.FORCE_IMAGE_DELETION_REQUESTED_AT)
        }
    }

    override suspend fun updateBackupEnabled(enabled: Boolean) {
        edit { preferences ->
            preferences[AppSettingsDataStore.BACKUP_ENABLED] = enabled
        }
    }

    override suspend fun updateDocumentBackupEnabled(enabled: Boolean) {
        edit { preferences ->
            preferences[AppSettingsDataStore.DOCUMENT_BACKUP_ENABLED] = enabled
        }
    }

    override suspend fun updateDiagnosticsEnabled(enabled: Boolean) {
        edit { preferences ->
            preferences[AppSettingsDataStore.DIAGNOSTICS_ENABLED] = enabled
        }
    }

    override suspend fun updateBiometricLockEnabled(enabled: Boolean) {
        edit { preferences ->
            preferences[AppSettingsDataStore.BIOMETRIC_LOCK_ENABLED] = enabled
        }
    }

    override suspend fun enterDemoMode(demoBusinessId: BusinessId) {
        editActiveBusiness { preferences ->
            preferences[AppSettingsDataStore.DEMO_BUSINESS_ID] = demoBusinessId.value
        }
    }

    override suspend fun exitDemoMode() {
        editActiveBusiness { preferences ->
            preferences.remove(AppSettingsDataStore.DEMO_BUSINESS_ID)
        }
    }

    private suspend fun editActiveBusiness(block: (MutablePreferences) -> Unit) {
        identityCoordinator.mutate { edit(block) }
    }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        withContext(dispatchers.io) {
            storageCatching { dataStore.edit { block(it) } }
        }
    }

    private fun readPendingOnboarding(
        preferences: Preferences,
    ): OnboardingProvisionIds? {
        val rawBusinessId = preferences[AppSettingsDataStore.PENDING_ONBOARDING_BUSINESS_ID]
        val rawWarehouseId = preferences[AppSettingsDataStore.PENDING_ONBOARDING_WAREHOUSE_ID]
        val rawUnitId = preferences[AppSettingsDataStore.PENDING_ONBOARDING_UNIT_ID]
        if (rawBusinessId == null && rawWarehouseId == null && rawUnitId == null) return null
        if (rawBusinessId == null || rawWarehouseId == null || rawUnitId == null) {
            corruptOnboardingCheckpoint("la reserva está incompleta")
        }
        return OnboardingProvisionIds(
            businessId = BusinessId.parse(rawBusinessId)
                ?: corruptOnboardingCheckpoint("businessId pendiente no canónico"),
            warehouseId = LocationId.parse(rawWarehouseId)
                ?: corruptOnboardingCheckpoint("warehouseId pendiente no canónico"),
            defaultUnitId = UnitId.parse(rawUnitId)
                ?: corruptOnboardingCheckpoint("unitId pendiente no canónico"),
        )
    }

    private fun clearPendingOnboarding(preferences: MutablePreferences) {
        preferences.remove(AppSettingsDataStore.PENDING_ONBOARDING_BUSINESS_ID)
        preferences.remove(AppSettingsDataStore.PENDING_ONBOARDING_WAREHOUSE_ID)
        preferences.remove(AppSettingsDataStore.PENDING_ONBOARDING_UNIT_ID)
    }

    private fun readCompletedOnboarding(preferences: Preferences): OnboardingProvisionIds? {
        val rawBusinessId = preferences[AppSettingsDataStore.COMPLETED_ONBOARDING_BUSINESS_ID]
        val rawWarehouseId = preferences[AppSettingsDataStore.COMPLETED_ONBOARDING_WAREHOUSE_ID]
        val rawUnitId = preferences[AppSettingsDataStore.COMPLETED_ONBOARDING_UNIT_ID]
        if (rawBusinessId == null && rawWarehouseId == null && rawUnitId == null) return null
        if (rawBusinessId == null || rawWarehouseId == null || rawUnitId == null) {
            corruptOnboardingCheckpoint("la reserva completada está incompleta")
        }
        return OnboardingProvisionIds(
            businessId = BusinessId.parse(rawBusinessId)
                ?: corruptOnboardingCheckpoint("businessId completado no canónico"),
            warehouseId = LocationId.parse(rawWarehouseId)
                ?: corruptOnboardingCheckpoint("warehouseId completado no canónico"),
            defaultUnitId = UnitId.parse(rawUnitId)
                ?: corruptOnboardingCheckpoint("unitId completado no canónico"),
        )
    }

    private fun clearCompletedOnboarding(preferences: MutablePreferences) {
        preferences.remove(AppSettingsDataStore.COMPLETED_ONBOARDING_BUSINESS_ID)
        preferences.remove(AppSettingsDataStore.COMPLETED_ONBOARDING_WAREHOUSE_ID)
        preferences.remove(AppSettingsDataStore.COMPLETED_ONBOARDING_UNIT_ID)
    }

    private fun corruptOnboardingCheckpoint(reason: String): Nothing = throw StorageException(
        StorageError.Unavailable,
        IllegalStateException(reason),
    )

    private fun onboardingCheckpointConflict(reason: String): Nothing = throw StorageException(
        StorageError.ConstraintConflict(reason),
    )
}
