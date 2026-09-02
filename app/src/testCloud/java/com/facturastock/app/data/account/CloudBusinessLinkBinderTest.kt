package com.facturastock.app.data.account

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.data.settings.AppIdentityMutationCoordinator
import com.facturastock.app.data.settings.DataStoreAppConfigurationRepository
import com.facturastock.app.domain.config.CostPolicy
import com.facturastock.app.domain.config.TaxRate
import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.model.BusinessRole
import com.facturastock.app.domain.model.CloudBusinessLink
import com.facturastock.app.domain.model.CloudBusinessLinkCasResult
import com.facturastock.app.domain.model.CloudBusinessLinkReceipt
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.CloudBusinessBindingRepository
import com.facturastock.app.domain.repository.CloudBusinessBindingResult
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
class CloudBusinessLinkBinderTest {
    private lateinit var tempDir: File
    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var appDataStore: DataStore<Preferences>
    private lateinit var store: CloudAccountSettingsStore
    private lateinit var bindings: InMemoryBindings
    private lateinit var coordinator: CloudAccountMutationCoordinator
    private lateinit var identityCoordinator: AppIdentityMutationCoordinator
    private lateinit var appConfiguration: DataStoreAppConfigurationRepository
    private lateinit var binder: CloudBusinessLinkBinder

    private val testDispatchers = object : DispatcherProvider {
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = Dispatchers.Default
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
    }

    @Before
    fun setUp() {
        tempDir = createTempDirectory("cloud-link-binder-test").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            File(tempDir, "account.preferences_pb")
        }
        appDataStore = PreferenceDataStoreFactory.create(scope = scope) {
            File(tempDir, "app.preferences_pb")
        }
        store = CloudAccountSettingsStore(dataStore)
        bindings = InMemoryBindings()
        coordinator = CloudAccountMutationCoordinator()
        identityCoordinator = AppIdentityMutationCoordinator()
        appConfiguration = DataStoreAppConfigurationRepository(
            dataStore = appDataStore,
            dispatchers = testDispatchers,
            identityCoordinator = identityCoordinator,
            applicationScope = scope,
        )
        runBlocking {
            appConfiguration.completeOnboarding(
                businessId = LOCAL,
                taxRate = TaxRate(BigDecimal("18")),
                costPolicy = CostPolicy.NET,
            )
        }
        binder = CloudBusinessLinkBinder(
            store = store,
            bindings = bindings,
            appClock = { NOW },
            coordinator = coordinator,
            appConfiguration = appConfiguration,
            identityCoordinator = identityCoordinator,
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        tempDir.deleteRecursively()
    }

    @Test
    fun `upgrade conserva X y rechaza seleccionar Y antes del primer worker`() = runTest {
        val rememberedX = link(CLOUD_X)
        store.setLink(UID_A, rememberedX)

        assertEquals(
            DomainResult.Failure(AccountError.CloudBusinessAlreadyBound),
            setActive(UID_A, link(CLOUD_Y)),
        )
        assertEquals(CLOUD_X, bindings.targetFor(LOCAL))
        assertEquals(rememberedX, store.observeLink().first())
        assertEquals(listOf(LOCAL to CLOUD_X), bindings.bindCalls)
    }

    @Test
    fun `primer enlace fija Room antes de publicar DataStore`() = runTest {
        val selected = link(CLOUD_X)

        val receipt = receipt(setActive(UID_A, selected))

        assertEquals(CLOUD_X, bindings.targetFor(LOCAL))
        assertEquals(selected, store.observeLink().first())
        assertEquals(store.storedLink()?.revision, receipt.revision)
    }

    @Test
    fun `cerrar enlace no borra binding ni permite repin tras revocacion`() = runTest {
        val receipt = receipt(setActive(UID_A, link(CLOUD_X)))

        assertEquals(
            DomainResult.Success(CloudBusinessLinkCasResult(true, null)),
            compareAndSet(UID_A, receipt, null),
        )
        assertNull(store.observeLink().first())
        assertEquals(CLOUD_X, bindings.targetFor(LOCAL))

        assertEquals(
            DomainResult.Failure(AccountError.CloudBusinessAlreadyBound),
            setActive(UID_A, link(CLOUD_Y)),
        )
        assertEquals(CLOUD_X, bindings.targetFor(LOCAL))
        assertNull(store.observeLink().first())
    }

    @Test
    fun `CAS de A nunca borra el mismo enlace publicado despues por B`() = runTest {
        val selected = link(CLOUD_X)
        val receiptA = receipt(setActive(UID_A, selected))
        receipt(setActive(UID_B, selected))

        val staleCleanup = compareAndSet(UID_A, receiptA, null)

        assertEquals(
            DomainResult.Success(CloudBusinessLinkCasResult(false, null)),
            staleCleanup,
        )
        assertEquals(UID_B, store.storedLink()?.ownerUid)
        assertEquals(selected, store.observeLink().first())
    }

    @Test
    fun `CAS exacto actualiza una sola vez`() = runTest {
        val selected = link(CLOUD_X)
        val receipt = receipt(setActive(UID_A, selected))

        assertEquals(
            DomainResult.Success(CloudBusinessLinkCasResult(true, null)),
            compareAndSet(UID_A, receipt, null),
        )
        assertNull(store.observeLink().first())
        assertEquals(
            DomainResult.Success(CloudBusinessLinkCasResult(false, null)),
            compareAndSet(UID_A, receipt, null),
        )
    }

    @Test
    fun `validacion tardia de A rechaza antes de pinnear o pisar el enlace de B`() = runTest {
        val selectedByB = link(CLOUD_X)
        receipt(setActive(UID_B, selectedByB))

        val staleResult = binder.setActive(UID_A, epoch(), link(CLOUD_Y)) {
            false
        }

        assertEquals(DomainResult.Failure(AccountError.SessionExpired), staleResult)
        assertEquals(listOf(LOCAL to CLOUD_X), bindings.bindCalls)
        assertEquals(UID_B, store.storedLink()?.ownerUid)
        assertEquals(selectedByB, store.observeLink().first())
    }

    @Test
    fun `validacion UID ocurre al adquirir la misma exclusion que la escritura`() = runTest {
        var currentUid = UID_A
        val lockEntered = CompletableDeferred<Unit>()
        val releaseLock = CompletableDeferred<Unit>()
        val blocker = async {
            coordinator.withLock {
                lockEntered.complete(Unit)
                releaseLock.await()
            }
        }
        lockEntered.await()
        val staleWrite = async {
            binder.setActive(UID_A, epoch(), link(CLOUD_X)) { currentUid == UID_A }
        }
        runCurrent()

        currentUid = UID_B
        releaseLock.complete(Unit)
        blocker.await()

        assertEquals(DomainResult.Failure(AccountError.SessionExpired), staleWrite.await())
        assertTrue(bindings.bindCalls.isEmpty())
        assertNull(store.observeLink().first())
    }

    @Test
    fun `cambio local adelantado rechaza el pin obsoleto dentro de la exclusion`() = runTest {
        val lockEntered = CompletableDeferred<Unit>()
        val releaseAccountLock = CompletableDeferred<Unit>()
        val blocker = async {
            coordinator.withLock {
                lockEntered.complete(Unit)
                releaseAccountLock.await()
            }
        }
        lockEntered.await()
        val capturedEpoch = epoch()
        val staleWrite = async { binder.setActive(UID_A, capturedEpoch, link(CLOUD_X)) }
        runCurrent()

        appConfiguration.enterDemoMode(LOCAL_B)
        releaseAccountLock.complete(Unit)
        blocker.await()

        assertEquals(DomainResult.Failure(AccountError.Conflict), staleWrite.await())
        assertTrue(bindings.bindCalls.isEmpty())
        assertNull(store.observeLink().first())
        assertEquals(LOCAL_B, appConfiguration.current().activeBusinessId)
    }

    @Test
    fun `cambio local iniciado durante el pin espera su linearizacion completa`() = runTest {
        val bindEntered = CompletableDeferred<Unit>()
        val releaseBind = CompletableDeferred<Unit>()
        bindings.beforeBind = {
            bindEntered.complete(Unit)
            releaseBind.await()
        }

        val binding = async { setActive(UID_A, link(CLOUD_X)) }
        bindEntered.await()
        val switch = async { appConfiguration.enterDemoMode(LOCAL_B) }
        runCurrent()

        assertFalse(switch.isCompleted)
        releaseBind.complete(Unit)
        receipt(binding.await())
        switch.await()

        assertEquals(CLOUD_X, bindings.targetFor(LOCAL))
        assertEquals(link(CLOUD_X), store.observeLink().first())
        assertEquals(LOCAL_B, appConfiguration.current().activeBusinessId)
    }

    @Test
    fun `CAS obsoleto no repinnea pero su limpieza exacta sigue disponible`() = runTest {
        val original = link(CLOUD_X)
        val updated = original.copy(role = BusinessRole.READER)
        val originalEpoch = epoch()
        val originalReceipt = receipt(setActive(UID_A, original, originalEpoch))
        appConfiguration.enterDemoMode(LOCAL_B)

        assertEquals(
            DomainResult.Success(CloudBusinessLinkCasResult(false, null)),
            compareAndSet(UID_A, originalReceipt, updated, originalEpoch),
        )
        assertEquals(original, store.observeLink().first())
        assertEquals(
            DomainResult.Success(CloudBusinessLinkCasResult(true, null)),
            compareAndSet(UID_A, originalReceipt, null),
        )
        assertNull(store.observeLink().first())
        assertEquals(CLOUD_X, bindings.targetFor(LOCAL))
    }

    @Test
    fun `CAS de reconciliacion obsoleto se rechaza antes de mutar`() = runTest {
        val original = link(CLOUD_X)
        val downgraded = original.copy(role = BusinessRole.READER)
        val originalReceipt = receipt(setActive(UID_A, original))

        val result = binder.compareAndSet(
            UID_A,
            epoch(),
            originalReceipt,
            downgraded,
        ) { false }

        assertEquals(DomainResult.Success(CloudBusinessLinkCasResult(false, null)), result)
        assertEquals(original, store.observeLink().first())
        assertEquals(listOf(LOCAL to CLOUD_X), bindings.bindCalls)
    }

    @Test
    fun `ABA local L1 L2 L1 rechaza el epoch capturado antes de tocar Room`() = runTest {
        val lockEntered = CompletableDeferred<Unit>()
        val releaseAccountLock = CompletableDeferred<Unit>()
        val blocker = async {
            coordinator.withLock {
                lockEntered.complete(Unit)
                releaseAccountLock.await()
            }
        }
        lockEntered.await()
        val capturedEpoch = epoch()
        val staleWrite = async {
            binder.setActive(UID_A, capturedEpoch, link(CLOUD_X))
        }
        runCurrent()

        appConfiguration.enterDemoMode(LOCAL_B)
        appConfiguration.exitDemoMode()
        assertEquals(LOCAL, appConfiguration.current().activeBusinessId)
        releaseAccountLock.complete(Unit)
        blocker.await()

        assertEquals(DomainResult.Failure(AccountError.Conflict), staleWrite.await())
        assertTrue(bindings.bindCalls.isEmpty())
        assertNull(store.observeLink().first())
    }

    @Test
    fun `clear y set identicos no permiten cleanup ni reconciliacion con recibo viejo`() = runTest {
        val selected = link(CLOUD_X)
        val firstReceipt = receipt(setActive(UID_A, selected))
        assertEquals(
            DomainResult.Success(CloudBusinessLinkCasResult(true, null)),
            compareAndSet(UID_A, firstReceipt, null),
        )
        val secondReceipt = receipt(setActive(UID_A, selected))
        assertTrue(secondReceipt.revision > firstReceipt.revision)

        assertEquals(
            DomainResult.Success(CloudBusinessLinkCasResult(false, null)),
            compareAndSet(UID_A, firstReceipt, null),
        )
        assertEquals(
            DomainResult.Success(CloudBusinessLinkCasResult(false, null)),
            compareAndSet(
                UID_A,
                firstReceipt,
                selected.copy(role = BusinessRole.READER),
            ),
        )
        assertEquals(secondReceipt.revision, store.storedLink()?.revision)
        assertEquals(selected, store.observeLink().first())
    }

    @Test
    fun `fallo DataStore posterior al pin se recupera reintentando el mismo destino`() = runTest {
        val failingStore = CloudAccountSettingsStore(FailOnceDataStore(dataStore))
        binder = CloudBusinessLinkBinder(
            store = failingStore,
            bindings = bindings,
            appClock = { NOW },
            coordinator = coordinator,
            appConfiguration = appConfiguration,
            identityCoordinator = identityCoordinator,
        )

        val firstFailure = runCatching {
            setActive(UID_A, link(CLOUD_X))
        }.exceptionOrNull()

        assertTrue(firstFailure is IllegalStateException)
        assertEquals(CLOUD_X, bindings.targetFor(LOCAL))
        assertNull(failingStore.storedLink())

        val recovered = receipt(setActive(UID_A, link(CLOUD_X)))
        assertEquals(link(CLOUD_X), recovered.link)
        assertEquals(recovered.revision, failingStore.storedLink()?.revision)
        assertEquals(listOf(LOCAL to CLOUD_X, LOCAL to CLOUD_X), bindings.bindCalls)
    }

    private fun epoch(): Long = identityCoordinator.currentEpoch()

    private suspend fun setActive(
        uid: String,
        link: CloudBusinessLink,
        expectedEpoch: Long = epoch(),
    ): DomainResult<CloudBusinessLinkReceipt> =
        binder.setActive(uid, expectedEpoch, link)

    private suspend fun compareAndSet(
        uid: String,
        expectedReceipt: CloudBusinessLinkReceipt,
        newLink: CloudBusinessLink?,
        expectedEpoch: Long = epoch(),
    ): DomainResult<CloudBusinessLinkCasResult> = binder.compareAndSet(
        expectedUid = uid,
        expectedLocalIdentityEpoch = expectedEpoch,
        expectedReceipt = expectedReceipt,
        newLink = newLink,
    )

    private fun receipt(
        result: DomainResult<CloudBusinessLinkReceipt>,
    ): CloudBusinessLinkReceipt = (result as DomainResult.Success).value

    private fun link(cloudBusinessId: BusinessId) = CloudBusinessLink(
        localBusinessId = LOCAL,
        cloudBusinessId = cloudBusinessId,
        role = BusinessRole.OWNER,
    )

    private class InMemoryBindings : CloudBusinessBindingRepository {
        private val values = mutableMapOf<BusinessId, BusinessId>()
        val bindCalls = mutableListOf<Pair<BusinessId, BusinessId>>()
        var beforeBind: suspend () -> Unit = {}

        override suspend fun bindOnce(
            localBusinessId: BusinessId,
            cloudBusinessId: BusinessId,
            boundAt: Instant,
        ): CloudBusinessBindingResult {
            beforeBind()
            bindCalls += localBusinessId to cloudBusinessId
            val existing = values[localBusinessId]
            if (existing != null) {
                return if (existing == cloudBusinessId) {
                    CloudBusinessBindingResult.AlreadyBound
                } else {
                    CloudBusinessBindingResult.LocalBusinessAlreadyBound
                }
            }
            if (cloudBusinessId in values.values) {
                return CloudBusinessBindingResult.CloudBusinessAlreadyBound
            }
            values[localBusinessId] = cloudBusinessId
            return CloudBusinessBindingResult.Bound
        }

        override suspend fun targetFor(localBusinessId: BusinessId): BusinessId? =
            values[localBusinessId]

        override suspend fun matches(
            localBusinessId: BusinessId,
            cloudBusinessId: BusinessId,
        ): Boolean = values[localBusinessId] == cloudBusinessId
    }

    private class FailOnceDataStore<T>(
        private val delegate: DataStore<T>,
    ) : DataStore<T> {
        private var shouldFail = true

        override val data = delegate.data

        override suspend fun updateData(transform: suspend (t: T) -> T): T {
            if (shouldFail) {
                shouldFail = false
                throw IllegalStateException("fallo DataStore simulado")
            }
            return delegate.updateData(transform)
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-22T00:00:00Z")
        val LOCAL = requireNotNull(BusinessId.parse("11111111-1111-4111-8111-111111111111"))
        val LOCAL_B = requireNotNull(BusinessId.parse("44444444-4444-4444-8444-444444444444"))
        val CLOUD_X = requireNotNull(BusinessId.parse("22222222-2222-4222-8222-222222222222"))
        val CLOUD_Y = requireNotNull(BusinessId.parse("33333333-3333-4333-8333-333333333333"))
        const val UID_A = "uid-link-a"
        const val UID_B = "uid-link-b"
    }
}
