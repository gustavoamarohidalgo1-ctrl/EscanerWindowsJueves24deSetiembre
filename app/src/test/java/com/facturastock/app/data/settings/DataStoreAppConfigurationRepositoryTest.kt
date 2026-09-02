package com.facturastock.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.config.CostPolicy
import com.facturastock.app.domain.config.TaxRate
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.ImageRetentionPolicy
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.repository.OnboardingProvisionIds
import com.facturastock.app.domain.repository.OnboardingReservation
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreAppConfigurationRepositoryTest {
    private lateinit var tempDir: File
    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: DataStoreAppConfigurationRepository
    private lateinit var identityCoordinator: AppIdentityMutationCoordinator

    private val testDispatchers = object : DispatcherProvider {
        override val io: CoroutineDispatcher = UnconfinedTestDispatcher()
        override val default: CoroutineDispatcher = UnconfinedTestDispatcher()
        override val main: CoroutineDispatcher = UnconfinedTestDispatcher()
    }

    private val businessId = BusinessId.from(
        UUID.fromString("123e4567-e89b-42d3-a456-426614174000"),
    )
    private val demoId = BusinessId.from(
        UUID.fromString("223e4567-e89b-42d3-a456-426614174000"),
    )

    @Before
    fun setUp() {
        tempDir = createTempDirectory("app-settings-test").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = newDataStore(scope)
        identityCoordinator = AppIdentityMutationCoordinator()
        repository = DataStoreAppConfigurationRepository(
            dataStore,
            testDispatchers,
            identityCoordinator,
            scope,
        )
    }

    @After
    fun tearDown() {
        // DataStore exige una sola instancia activa por archivo; el cierre es asíncrono
        // respecto a cancel(), pero borrar el directorio con la conexión abierta es válido.
        scope.cancel()
        tempDir.deleteRecursively()
    }

    @Test
    fun `sin nada escrito se emiten los defaults`() = runTest {
        assertEquals(AppConfiguration.defaults(), repository.current())
        assertEquals(AppConfiguration.defaults(), repository.observe().first())
    }

    @Test
    fun `observe traduce IOException sin degradar a configuracion por defecto`() = runTest {
        val ioFailure = IOException("lectura interrumpida")
        val failingDataStore = object : DataStore<Preferences> {
            override val data = flow<Preferences> { throw ioFailure }

            override suspend fun updateData(
                transform: suspend (t: Preferences) -> Preferences,
            ): Preferences = error("La prueba no debe escribir")
        }
        val failingRepository = DataStoreAppConfigurationRepository(
            failingDataStore,
            testDispatchers,
            identityCoordinator,
            scope,
        )

        val failure = runCatching { failingRepository.observe().first() }.exceptionOrNull()

        assertTrue(failure is StorageException)
        assertEquals(StorageError.Unavailable, (failure as StorageException).error)
        assertTrue(failure.cause === ioFailure)
    }

    @Test
    fun `observadores simultaneos comparten una sola lectura de configuracion`() = runTest {
        val subscriptions = AtomicInteger(0)
        val releaseRead = CompletableDeferred<Unit>()
        val countingDataStore = object : DataStore<Preferences> {
            override val data = flow {
                subscriptions.incrementAndGet()
                releaseRead.await()
                emit(emptyPreferences())
            }

            override suspend fun updateData(
                transform: suspend (t: Preferences) -> Preferences,
            ): Preferences = error("La prueba no debe escribir")
        }
        val sharedScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        val sharedRepository = DataStoreAppConfigurationRepository(
            countingDataStore,
            testDispatchers,
            identityCoordinator,
            sharedScope,
        )

        try {
            coroutineScope {
                val lockConfiguration = async { sharedRepository.observe().first() }
                val onboardingConfiguration = async { sharedRepository.observe().first() }
                runCurrent()

                assertEquals(1, subscriptions.get())
                releaseRead.complete(Unit)
                assertEquals(AppConfiguration.defaults(), lockConfiguration.await())
                assertEquals(AppConfiguration.defaults(), onboardingConfiguration.await())
            }
        } finally {
            sharedScope.cancel()
        }
    }

    @Test
    fun `retry descarta el fallo reproducido aunque otro observador mantenga viva la lectura`() =
        runTest {
            val subscriptions = AtomicInteger(0)
            val releaseFailure = CompletableDeferred<Unit>()
            val releaseDelayedObserver = CompletableDeferred<Unit>()
            val delayedObserverReady = CompletableDeferred<Unit>()
            val retryingDataStore = object : DataStore<Preferences> {
                override val data = flow {
                    when (subscriptions.incrementAndGet()) {
                        1 -> {
                            emit(emptyPreferences())
                            releaseFailure.await()
                            throw IOException("fallo transitorio")
                        }
                        else -> {
                            emit(emptyPreferences())
                            awaitCancellation()
                        }
                    }
                }

                override suspend fun updateData(
                    transform: suspend (t: Preferences) -> Preferences,
                ): Preferences = error("La prueba no debe escribir")
            }
            val sharedScope = CoroutineScope(
                SupervisorJob() + UnconfinedTestDispatcher(testScheduler),
            )
            val recoveringRepository = DataStoreAppConfigurationRepository(
                retryingDataStore,
                testDispatchers,
                identityCoordinator,
                sharedScope,
            )

            try {
                val delayedObserver = launch {
                    recoveringRepository.observe().collect {
                        delayedObserverReady.complete(Unit)
                        releaseDelayedObserver.await()
                    }
                }
                delayedObserverReady.await()
                val failedRead = async {
                    runCatching { recoveringRepository.observe().drop(1).first() }
                        .exceptionOrNull()
                }
                runCurrent()

                releaseFailure.complete(Unit)
                val failure = failedRead.await()
                assertTrue(failure is StorageException)
                assertEquals(StorageError.Unavailable, (failure as StorageException).error)

                recoveringRepository.retryObservation()
                val lockConfiguration = async { recoveringRepository.observe().first() }
                val onboardingConfiguration = async { recoveringRepository.observe().first() }
                runCurrent()

                // Libera al observador lento después de invalidar el replay: mantuvo shareIn vivo
                // durante el retry, que es justamente el caso que antes exigía un segundo toque.
                releaseDelayedObserver.complete(Unit)
                assertEquals(AppConfiguration.defaults(), lockConfiguration.await())
                assertEquals(AppConfiguration.defaults(), onboardingConfiguration.await())
                assertEquals(2, subscriptions.get())
                delayedObserver.cancelAndJoin()
            } finally {
                sharedScope.cancel()
            }
        }

    @Test
    fun `completeOnboarding persiste negocio tasa politica y marca`() = runTest {
        repository.completeOnboarding(businessId, TaxRate(BigDecimal("18")), CostPolicy.GROSS)

        val current = repository.current()
        assertTrue(current.onboardingCompleted)
        assertEquals(businessId, current.businessId)
        assertEquals(TaxRate(BigDecimal("18")), current.taxRate)
        assertEquals(CostPolicy.GROSS, current.costPolicy)
        assertEquals(businessId, repository.observe().first().businessId)
    }

    @Test
    fun `reabrir conserva lo persistido`() = runTest {
        repository.completeOnboarding(businessId, TaxRate(BigDecimal("10.5")), CostPolicy.GROSS)

        // DataStore exige una sola instancia activa por archivo. Esperar el Job propietario
        // completa el cierre del actor sin depender de reloj real ni esperas activas.
        requireNotNull(scope.coroutineContext[Job]).cancelAndJoin()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = newDataStore(scope)
        repository = DataStoreAppConfigurationRepository(
            dataStore,
            testDispatchers,
            identityCoordinator,
            scope,
        )

        val current = repository.current()
        assertTrue(current.onboardingCompleted)
        assertEquals(businessId, current.businessId)
        assertEquals(TaxRate(BigDecimal("10.5")), current.taxRate)
        assertEquals(CostPolicy.GROSS, current.costPolicy)
    }

    @Test
    fun `reserva de onboarding se reutiliza y sobrevive reapertura`() = runTest {
        val original = onboardingIds(10)
        val alternate = onboardingIds(20)

        assertEquals(
            OnboardingReservation.Pending(original),
            repository.reserveOnboardingProvision(original),
        )
        assertEquals(
            OnboardingReservation.Pending(original),
            repository.reserveOnboardingProvision(alternate),
        )

        requireNotNull(scope.coroutineContext[Job]).cancelAndJoin()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = newDataStore(scope)
        repository = DataStoreAppConfigurationRepository(
            dataStore,
            testDispatchers,
            identityCoordinator,
            scope,
        )

        assertEquals(
            OnboardingReservation.Pending(original),
            repository.reserveOnboardingProvision(alternate),
        )
        assertFalse(repository.current().onboardingCompleted)
    }

    @Test
    fun `finalizar consume la reserva y es idempotente para el mismo negocio`() = runTest {
        val reservation = onboardingIds(30)
        repository.reserveOnboardingProvision(reservation)

        repository.finalizeOnboardingProvision(
            reservation = reservation,
            provisionedBusinessId = reservation.businessId,
            taxRate = TaxRate(BigDecimal("18")),
            costPolicy = CostPolicy.NET,
        )
        // Simula la repetición de la fase final tras perder su respuesta.
        repository.finalizeOnboardingProvision(
            reservation = reservation,
            provisionedBusinessId = reservation.businessId,
            taxRate = TaxRate(BigDecimal("18")),
            costPolicy = CostPolicy.NET,
        )

        val current = repository.current()
        assertTrue(current.onboardingCompleted)
        assertEquals(reservation.businessId, current.businessId)
        assertEquals(
            OnboardingReservation.AlreadyCompleted(reservation.businessId),
            repository.reserveOnboardingProvision(onboardingIds(40)),
        )
        val preferences = dataStore.data.first()
        assertNull(preferences[AppSettingsDataStore.PENDING_ONBOARDING_BUSINESS_ID])
        assertNull(preferences[AppSettingsDataStore.PENDING_ONBOARDING_WAREHOUSE_ID])
        assertNull(preferences[AppSettingsDataStore.PENDING_ONBOARDING_UNIT_ID])
        assertEquals(
            reservation.warehouseId.value,
            preferences[AppSettingsDataStore.COMPLETED_ONBOARDING_WAREHOUSE_ID],
        )
    }

    @Test
    fun `finalizacion repetida con reserva distinta falla aunque el businessId coincida`() =
        runTest {
            val reservation = onboardingIds(31)
            repository.reserveOnboardingProvision(reservation)
            repository.finalizeOnboardingProvision(
                reservation = reservation,
                provisionedBusinessId = reservation.businessId,
                taxRate = TaxRate(BigDecimal("18")),
                costPolicy = CostPolicy.NET,
            )

            val alternate = OnboardingProvisionIds(
                businessId = reservation.businessId,
                warehouseId = onboardingIds(32).warehouseId,
                defaultUnitId = onboardingIds(32).defaultUnitId,
            )
            val failure = runCatching {
                repository.finalizeOnboardingProvision(
                    reservation = alternate,
                    provisionedBusinessId = reservation.businessId,
                    taxRate = TaxRate(BigDecimal("18")),
                    costPolicy = CostPolicy.NET,
                )
            }.exceptionOrNull()

            assertTrue(failure is StorageException)
            assertTrue((failure as StorageException).error is StorageError.ConstraintConflict)
        }

    @Test
    fun `finalizar con otra reserva falla cerrado y conserva checkpoint reintentable`() = runTest {
        val original = onboardingIds(50)
        repository.reserveOnboardingProvision(original)

        val failure = runCatching {
            repository.finalizeOnboardingProvision(
                reservation = onboardingIds(60),
                provisionedBusinessId = original.businessId,
                taxRate = TaxRate(BigDecimal("18")),
                costPolicy = CostPolicy.NET,
            )
        }.exceptionOrNull()

        assertTrue(failure is StorageException)
        assertTrue((failure as StorageException).error is StorageError.ConstraintConflict)
        assertFalse(repository.current().onboardingCompleted)
        assertEquals(
            OnboardingReservation.Pending(original),
            repository.reserveOnboardingProvision(onboardingIds(70)),
        )
    }

    @Test
    fun `updateTaxRate y updateCostPolicy solo cambian su clave`() = runTest {
        repository.completeOnboarding(businessId, TaxRate(BigDecimal("18")), CostPolicy.NET)

        repository.updateTaxRate(TaxRate(BigDecimal("4")))
        val afterRate = repository.current()
        assertEquals(TaxRate(BigDecimal("4")), afterRate.taxRate)
        // El resto de la configuración queda intacta (la tasa aplica a cálculos futuros).
        assertEquals(CostPolicy.NET, afterRate.costPolicy)
        assertEquals(businessId, afterRate.businessId)
        assertTrue(afterRate.onboardingCompleted)

        repository.updateCostPolicy(CostPolicy.GROSS)
        val afterPolicy = repository.current()
        assertEquals(CostPolicy.GROSS, afterPolicy.costPolicy)
        assertEquals(TaxRate(BigDecimal("4")), afterPolicy.taxRate)
        assertEquals(businessId, afterPolicy.businessId)
    }

    @Test
    fun `enterDemoMode y exitDemoMode mueven el negocio activo`() = runTest {
        repository.completeOnboarding(businessId, TaxRate(BigDecimal("18")), CostPolicy.NET)

        repository.enterDemoMode(demoId)
        val inDemo = repository.current()
        assertTrue(inDemo.isDemoMode)
        assertEquals(demoId, inDemo.activeBusinessId)
        // El negocio real nunca se sobrescribe.
        assertEquals(businessId, inDemo.businessId)

        repository.exitDemoMode()
        val outDemo = repository.current()
        assertFalse(outDemo.isDemoMode)
        assertNull(outDemo.demoBusinessId)
        assertEquals(businessId, outDemo.activeBusinessId)
    }

    @Test
    fun `epoch avanza antes de cada intento y no revierte ante fallo`() = runTest {
        val initial = identityCoordinator.currentEpoch()
        repository.completeOnboarding(businessId, TaxRate(BigDecimal("18")), CostPolicy.NET)
        assertEquals(initial + 1L, identityCoordinator.currentEpoch())

        val failure = runCatching {
            identityCoordinator.mutate<Unit> { error("fallo simulado") }
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(initial + 2L, identityCoordinator.currentEpoch())
    }

    @Test
    fun `valores corruptos vuelven al default de su clave`() = runTest {
        repository.completeOnboarding(businessId, TaxRate(BigDecimal("18")), CostPolicy.NET)
        dataStore.edit { preferences ->
            preferences[AppSettingsDataStore.TAX_RATE_PERCENT] = "no-es-decimal"
            preferences[AppSettingsDataStore.COST_POLICY] = "NO_EXISTE"
            preferences[AppSettingsDataStore.BUSINESS_ID] = "no-es-un-uuid"
        }

        val current = repository.current()
        assertEquals(AppConfiguration.DEFAULT_TAX_RATE, current.taxRate)
        assertEquals(AppConfiguration.DEFAULT_COST_POLICY, current.costPolicy)
        assertNull(current.businessId)
        // La marca de onboarding no se contamina por claves corruptas ajenas.
        assertTrue(current.onboardingCompleted)
    }

    @Test
    fun `preferencias de privacidad solo cambian su propia clave`() = runTest {
        repository.completeOnboarding(businessId, TaxRate(BigDecimal("18")), CostPolicy.NET)

        repository.updateImageRetentionPolicy(ImageRetentionPolicy.DAYS_30)
        var current = repository.current()
        assertEquals(ImageRetentionPolicy.DAYS_30, current.imageRetentionPolicy)
        // El resto de la configuración queda intacta.
        assertEquals(TaxRate(BigDecimal("18")), current.taxRate)
        assertEquals(businessId, current.businessId)
        assertFalse(current.backupEnabled)

        repository.updateBackupEnabled(false)
        current = repository.current()
        assertFalse(current.backupEnabled)
        assertEquals(ImageRetentionPolicy.DAYS_30, current.imageRetentionPolicy)
        assertEquals(businessId, current.businessId)

        repository.updateBackupEnabled(true)
        assertTrue(repository.current().backupEnabled)

        repository.updateDocumentBackupEnabled(true)
        current = repository.current()
        assertTrue(current.documentBackupEnabled)
        assertTrue(current.backupEnabled)

        repository.updateBiometricLockEnabled(true)
        current = repository.current()
        assertTrue(current.biometricLockEnabled)
        assertTrue(current.documentBackupEnabled)
    }

    @Test
    fun `politica corrupta vuelve a KEEP y opt ins ausentes quedan apagados`() = runTest {
        repository.completeOnboarding(businessId, TaxRate(BigDecimal("18")), CostPolicy.NET)
        dataStore.edit { preferences ->
            preferences[AppSettingsDataStore.IMAGE_RETENTION_POLICY] = "BORRA_TODO"
        }

        val current = repository.current()
        assertEquals(ImageRetentionPolicy.KEEP, current.imageRetentionPolicy)
        assertFalse(current.backupEnabled)
        assertFalse(current.documentBackupEnabled)
        assertFalse(current.diagnosticsEnabled)
        assertFalse(current.biometricLockEnabled)
        // Una clave corrupta no contamina al resto.
        assertEquals(businessId, current.businessId)
    }

    @Test
    fun `orden de borrar imagenes conserva cutoff durable tras reapertura y se puede consumir`() =
        runTest {
            val cutoff = Instant.parse("2026-08-25T10:15:30Z")
            repository.requestForceImageDeletion(cutoff)

            requireNotNull(scope.coroutineContext[Job]).cancelAndJoin()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            dataStore = newDataStore(scope)
            repository = DataStoreAppConfigurationRepository(
                dataStore,
                testDispatchers,
                identityCoordinator,
                scope,
            )

            assertEquals(cutoff, repository.forceImageDeletionRequestedAt())
            repository.clearForceImageDeletionRequest()
            assertNull(repository.forceImageDeletionRequestedAt())
            assertNull(
                dataStore.data.first()[AppSettingsDataStore.FORCE_IMAGE_DELETION_REQUESTED_AT],
            )
        }

    @Test
    fun `orden repetida solo avanza el cutoff y un reloj atrasado no lo reduce`() = runTest {
        val first = Instant.parse("2026-08-25T10:00:00Z")
        val newer = first.plusSeconds(60)

        repository.requestForceImageDeletion(first)
        repository.requestForceImageDeletion(first.minusSeconds(60))
        assertEquals(first, repository.forceImageDeletionRequestedAt())

        repository.requestForceImageDeletion(newer)
        assertEquals(newer, repository.forceImageDeletionRequestedAt())
    }

    private fun newDataStore(targetScope: CoroutineScope): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = targetScope,
            produceFile = { File(tempDir, "app_settings.preferences_pb") },
        )

    private fun onboardingIds(seed: Int) = OnboardingProvisionIds(
        businessId = BusinessId.from(uuid(seed)),
        warehouseId = LocationId.from(uuid(seed + 1)),
        defaultUnitId = UnitId.from(uuid(seed + 2)),
    )

    private fun uuid(seed: Int): UUID =
        UUID.fromString("00000000-0000-0000-0000-%012d".format(seed))
}
