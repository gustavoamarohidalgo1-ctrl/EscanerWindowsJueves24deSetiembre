package com.facturastock.app.feature.account

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.config.CostPolicy
import com.facturastock.app.domain.config.TaxRate
import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.model.AccountSession
import com.facturastock.app.domain.model.BusinessRole
import com.facturastock.app.domain.model.CloudBusinessLink
import com.facturastock.app.domain.model.CloudMembership
import com.facturastock.app.domain.model.AccountDeletionSummary
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.usecase.ObserveAppConfigurationUseCase
import com.facturastock.app.domain.usecase.BindCloudBusinessLinkUseCase
import com.facturastock.app.testing.FakeAccountRepository
import com.facturastock.app.testing.FakeAppConfigurationRepository
import com.facturastock.app.testing.FakeBusinessMembershipRepository
import com.facturastock.app.testing.FakeFirebaseConnectivity
import com.facturastock.app.testing.FakeCloudBusinessBindingRepository
import com.facturastock.app.testing.FakePurchaseBackupScheduler
import com.facturastock.app.testing.MainDispatcherRule
import com.facturastock.app.testing.TestDispatcherProvider
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var accountRepository: FakeAccountRepository
    private lateinit var firebaseConnectivity: FakeFirebaseConnectivity
    private lateinit var membershipRepository: FakeBusinessMembershipRepository
    private lateinit var appConfig: FakeAppConfigurationRepository
    private lateinit var scheduler: FakePurchaseBackupScheduler
    private lateinit var cloudBindings: FakeCloudBusinessBindingRepository
    private lateinit var dispatchers: TestDispatcherProvider

    @Before
    fun setUp() = runTest {
        firebaseConnectivity = FakeFirebaseConnectivity()
        accountRepository = FakeAccountRepository(connectivity = firebaseConnectivity)
        membershipRepository = FakeBusinessMembershipRepository()
        appConfig = FakeAppConfigurationRepository()
        scheduler = FakePurchaseBackupScheduler()
        cloudBindings = FakeCloudBusinessBindingRepository()
        dispatchers = TestDispatcherProvider(mainDispatcherRule.dispatcher)
        appConfig.completeOnboarding(LOCAL_BUSINESS_ID, TaxRate(BigDecimal("18")), CostPolicy.NET)
    }

    @Test
    fun `sesion firmada fuera es el estado visible tras iniciar`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()

            // Antes de colectar, la sesión aún no se conoce.
            assertEquals(AccountContract.State(email = ""), viewModel.uiState.value)

            runCurrent()
            assertEquals(AccountSession.SignedOut, viewModel.uiState.value.session)
            assertEquals(LOCAL_BUSINESS_ID, viewModel.uiState.value.activeBusinessId)
        }

    @Test
    fun `campos enviados al backend se limitan antes de entrar al estado`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val handle = SavedStateHandle()
            val viewModel = createViewModel(savedStateHandle = handle)

            viewModel.onAction(AccountContract.Action.EmailChanged("e".repeat(400)))
            viewModel.onAction(
                AccountContract.Action.BusinessDisplayNameChanged("N".repeat(300)),
            )
            runCurrent()

            assertEquals(AccountContract.EMAIL_MAX_LENGTH, viewModel.uiState.value.email.length)
            assertEquals(
                AccountContract.BUSINESS_DISPLAY_NAME_MAX_LENGTH,
                viewModel.uiState.value.businessDisplayName.length,
            )
            assertEquals(
                AccountContract.EMAIL_MAX_LENGTH,
                handle.get<String>("account.email")?.length,
            )
        }

    @Test
    fun `fallo inicial de sesion se recupera al reintentar el observador`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            accountRepository.observeSessionFailure = IllegalStateException("session unavailable")
            val viewModel = createViewModel()
            runCurrent()

            assertNull(viewModel.uiState.value.session)
            assertEquals(
                AccountError.Unexpected,
                viewModel.uiState.value.initialLoadFailure,
            )

            accountRepository.observeSessionFailure = null
            viewModel.onAction(AccountContract.Action.RetryInitialLoad)
            runCurrent()

            assertEquals(AccountSession.SignedOut, viewModel.uiState.value.session)
            assertNull(viewModel.uiState.value.initialLoadFailure)
        }

    @Test
    fun `Firebase no configurado permanece Unavailable y rechaza credenciales con error cerrado`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            accountRepository = FakeAccountRepository(
                available = false,
                connectivity = firebaseConnectivity,
            )
            val viewModel = createViewModel()
            runCurrent()

            assertEquals(AccountSession.Unavailable, viewModel.uiState.value.session)

            viewModel.onAction(AccountContract.Action.EmailChanged(EMAIL))
            viewModel.onAction(AccountContract.Action.PasswordChanged(PASSWORD))
            runCurrent()
            viewModel.onAction(AccountContract.Action.SubmitCredentials)
            runCurrent()

            assertEquals(AccountError.Unavailable, viewModel.uiState.value.failure)
            assertEquals(listOf(EMAIL to PASSWORD), accountRepository.signInCalls)
            assertEquals(0, scheduler.enqueueCount)
        }

    @Test
    fun `entrar con exito activa la sesion y drena la outbox cuando hay enlace`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val link = CloudBusinessLink(
                localBusinessId = LOCAL_BUSINESS_ID,
                cloudBusinessId = CLOUD_BUSINESS_ID,
                role = BusinessRole.OWNER,
            )
            val active = AccountSession.Active(uid = UID, email = EMAIL, link = link)
            accountRepository.nextSignInResult = DomainResult.Success(active)
            membershipRepository.memberships = listOf(
                CloudMembership(CLOUD_BUSINESS_ID, "Bodega Nube", BusinessRole.OWNER),
            )
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(AccountContract.Action.EmailChanged(EMAIL))
            runCurrent()
            viewModel.onAction(AccountContract.Action.PasswordChanged(PASSWORD))
            runCurrent()
            viewModel.onAction(AccountContract.Action.SubmitCredentials)
            runCurrent()

            val state = viewModel.uiState.value
            assertEquals(listOf(EMAIL to PASSWORD), accountRepository.signInCalls)
            assertEquals(active, state.session)
            assertEquals("", state.password)
            assertFalse(state.isWorking)
            // El éxito con enlace encoló el drenado y cargó las membresías.
            assertEquals(1, scheduler.enqueueCount)
            assertEquals(1, scheduler.privacyEnqueueCount)
            assertEquals(1, membershipRepository.listMyMembershipsCalls)
            assertEquals(listOf(UID), membershipRepository.listMyMembershipsUidCalls)
            assertEquals("Bodega Nube", state.memberships.single().businessDisplayName)
        }

    @Test
    fun `entrar sin enlace despierta privacidad pero no el respaldo comercial`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val active = AccountSession.Active(uid = UID, email = EMAIL, link = null)
            accountRepository.nextSignInResult = DomainResult.Success(active)
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(AccountContract.Action.EmailChanged(EMAIL))
            viewModel.onAction(AccountContract.Action.PasswordChanged(PASSWORD))
            runCurrent()
            viewModel.onAction(AccountContract.Action.SubmitCredentials)
            runCurrent()

            assertEquals(active, viewModel.uiState.value.session)
            assertEquals(0, scheduler.enqueueCount)
            assertEquals(1, scheduler.privacyEnqueueCount)
        }

    @Test
    fun `verificar correo sin enlace despierta privacidad pero no respaldo comercial`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            accountRepository.emitSession(AccountSession.AwaitingVerification(UID, EMAIL))
            val active = AccountSession.Active(uid = UID, email = EMAIL, link = null)
            accountRepository.nextRefreshVerificationResult = DomainResult.Success(active)
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(AccountContract.Action.RefreshVerification)
            runCurrent()

            assertEquals(1, accountRepository.refreshVerificationCalls)
            assertEquals(active, viewModel.uiState.value.session)
            assertEquals(0, scheduler.enqueueCount)
            assertEquals(1, scheduler.privacyEnqueueCount)
        }

    @Test
    fun `permiso stale al listar membresias renueva token y reintenta una sola lectura`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val active = AccountSession.Active(uid = UID, email = EMAIL, link = null)
            val membership = CloudMembership(
                CLOUD_BUSINESS_ID,
                "Bodega Nube",
                BusinessRole.OWNER,
            )
            accountRepository.emitSession(active)
            accountRepository.nextRecoverSessionResult = DomainResult.Success(active)
            var attempt = 0
            membershipRepository.listMyMembershipsHandler = {
                attempt++
                if (attempt == 1) {
                    DomainResult.Failure(AccountError.PermissionDenied)
                } else {
                    DomainResult.Success(listOf(membership))
                }
            }

            val viewModel = createViewModel()
            runCurrent()

            assertEquals(1, accountRepository.recoverSessionCalls)
            assertEquals(2, membershipRepository.listMyMembershipsCalls)
            assertEquals(listOf(UID, UID), membershipRepository.listMyMembershipsUidCalls)
            assertEquals(listOf(membership), viewModel.uiState.value.memberships)
            assertNull(viewModel.uiState.value.failure)
        }

    @Test
    fun `permiso persistente no causa bucle ni reintenta escrituras`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val active = AccountSession.Active(uid = UID, email = EMAIL, link = null)
            accountRepository.emitSession(active)
            accountRepository.nextRecoverSessionResult = DomainResult.Success(active)
            membershipRepository.listMyMembershipsHandler = {
                DomainResult.Failure(AccountError.PermissionDenied)
            }

            val viewModel = createViewModel()
            runCurrent()

            assertEquals(1, accountRepository.recoverSessionCalls)
            assertEquals(2, membershipRepository.listMyMembershipsCalls)
            assertTrue(membershipRepository.createBusinessCalls.isEmpty())
            assertEquals(AccountError.PermissionDenied, viewModel.uiState.value.failure)

            membershipRepository.listMyMembershipsHandler = {
                DomainResult.Success(emptyList())
            }
            viewModel.onAction(AccountContract.Action.RetryMemberships)
            runCurrent()

            assertEquals(1, accountRepository.recoverSessionCalls)
            assertEquals(3, membershipRepository.listMyMembershipsCalls)
            assertNull(viewModel.uiState.value.failure)
        }

    @Test
    fun `una carga tardia de membresias no repuebla la sesion de otro uid`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val oldStarted = CompletableDeferred<Unit>()
            val releaseOld = CompletableDeferred<Unit>()
            val oldMembership = CloudMembership(
                CLOUD_BUSINESS_ID,
                "Negocio anterior",
                BusinessRole.OWNER,
            )
            val newMembership = CloudMembership(
                OTHER_CLOUD_BUSINESS_ID,
                "Negocio actual",
                BusinessRole.READER,
            )
            var request = 0
            membershipRepository.listMyMembershipsHandler = {
                request++
                if (request == 1) {
                    oldStarted.complete(Unit)
                    withContext(NonCancellable) { releaseOld.await() }
                    DomainResult.Success(listOf(oldMembership))
                } else {
                    DomainResult.Success(listOf(newMembership))
                }
            }
            val viewModel = createViewModel()
            runCurrent()

            accountRepository.emitSession(
                AccountSession.Active(uid = "uid-old", email = "old@example.com", link = null),
            )
            runCurrent()
            assertTrue(oldStarted.isCompleted)

            accountRepository.emitSession(
                AccountSession.Active(uid = "uid-new", email = "new@example.com", link = null),
            )
            runCurrent()
            assertEquals(listOf(newMembership), viewModel.uiState.value.memberships)

            releaseOld.complete(Unit)
            runCurrent()
            assertEquals("uid-new", (viewModel.uiState.value.session as AccountSession.Active).uid)
            assertEquals(listOf(newMembership), viewModel.uiState.value.memberships)
        }

    @Test
    fun `una carga A1 no pisa A2 despues de cambiar A a B y volver a A`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val oldStarted = CompletableDeferred<Unit>()
            val releaseOld = CompletableDeferred<Unit>()
            val stale = CloudMembership(CLOUD_BUSINESS_ID, "A anterior", BusinessRole.OWNER)
            val current = CloudMembership(
                OTHER_CLOUD_BUSINESS_ID,
                "A actual",
                BusinessRole.ADMIN,
            )
            var request = 0
            membershipRepository.listMyMembershipsHandler = {
                request++
                when (request) {
                    1 -> {
                        oldStarted.complete(Unit)
                        withContext(NonCancellable) { releaseOld.await() }
                        DomainResult.Success(listOf(stale))
                    }

                    2 -> DomainResult.Success(emptyList())
                    else -> DomainResult.Success(listOf(current))
                }
            }
            val viewModel = createViewModel()
            runCurrent()
            accountRepository.emitSession(
                AccountSession.Active(uid = UID, email = EMAIL, link = null),
            )
            runCurrent()
            assertTrue(oldStarted.isCompleted)

            accountRepository.emitSession(
                AccountSession.Active(uid = OTHER_UID, email = "bea@example.com", link = null),
            )
            runCurrent()
            accountRepository.emitSession(
                AccountSession.Active(uid = UID, email = EMAIL, link = null),
            )
            runCurrent()
            assertEquals(listOf(current), viewModel.uiState.value.memberships)

            releaseOld.complete(Unit)
            runCurrent()

            assertEquals(listOf(current), viewModel.uiState.value.memberships)
        }

    @Test
    fun `doble toque de crear negocio solo envia una solicitud`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            accountRepository.emitSession(
                AccountSession.Active(uid = UID, email = EMAIL, link = null),
            )
            val viewModel = createViewModel()
            runCurrent()
            viewModel.onAction(
                AccountContract.Action.BusinessDisplayNameChanged("Bodega Central"),
            )
            runCurrent()

            viewModel.onAction(AccountContract.Action.CreateBusiness)
            viewModel.onAction(AccountContract.Action.CreateBusiness)
            runCurrent()

            assertEquals(listOf(UID), membershipRepository.createBusinessUidCalls)
            assertEquals(listOf("Bodega Central"), membershipRepository.createBusinessCalls)
        }

    @Test
    fun `entrar sin enlace no encola respaldo`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            accountRepository.nextSignInResult = DomainResult.Success(
                AccountSession.Active(uid = UID, email = EMAIL, link = null),
            )
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(AccountContract.Action.EmailChanged(EMAIL))
            runCurrent()
            viewModel.onAction(AccountContract.Action.PasswordChanged(PASSWORD))
            runCurrent()
            viewModel.onAction(AccountContract.Action.SubmitCredentials)
            runCurrent()

            assertEquals(0, scheduler.enqueueCount)
        }

    @Test
    fun `credenciales invalidas muestran el error cerrado sin exponer detalle`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            accountRepository.nextSignInResult = DomainResult.Failure(AccountError.InvalidCredentials)
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(AccountContract.Action.EmailChanged(EMAIL))
            runCurrent()
            viewModel.onAction(AccountContract.Action.PasswordChanged(PASSWORD))
            runCurrent()
            viewModel.onAction(AccountContract.Action.SubmitCredentials)
            runCurrent()

            val state = viewModel.uiState.value
            assertEquals(AccountError.InvalidCredentials, state.failure)
            assertFalse(state.isWorking)
            assertEquals(AccountSession.SignedOut, state.session)
            assertEquals(0, scheduler.enqueueCount)
        }

    @Test
    fun `cambiar de negocio enlaza el negocio local activo con el elegido`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            accountRepository.emitSession(
                AccountSession.Active(uid = UID, email = EMAIL, link = null),
            )
            membershipRepository.memberships = listOf(
                CloudMembership(CLOUD_BUSINESS_ID, "Bodega Nube", BusinessRole.OWNER),
                CloudMembership(OTHER_CLOUD_BUSINESS_ID, "Otro negocio", BusinessRole.ADMIN),
            )
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(
                AccountContract.Action.MembershipSelected(OTHER_CLOUD_BUSINESS_ID),
            )
            runCurrent()

            assertEquals(
                listOf(
                    CloudBusinessLink(
                        localBusinessId = LOCAL_BUSINESS_ID,
                        cloudBusinessId = OTHER_CLOUD_BUSINESS_ID,
                        role = BusinessRole.ADMIN,
                    ),
                ),
                membershipRepository.setActiveCloudBusinessCalls,
            )
            assertEquals(1, scheduler.enqueueCount)
            assertEquals(1, scheduler.privacyEnqueueCount)
            assertEquals(AccountContract.Feedback.LINK_UPDATED, viewModel.uiState.value.feedback)
            assertNull(viewModel.uiState.value.changingLinkTo)
        }

    @Test
    fun `cambio local invalida una escritura de enlace no cooperativa y la revierte`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            accountRepository.emitSession(
                AccountSession.Active(uid = UID, email = EMAIL, link = null),
            )
            membershipRepository.memberships = listOf(
                CloudMembership(CLOUD_BUSINESS_ID, "Bodega Nube", BusinessRole.OWNER),
            )
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            membershipRepository.setActiveCloudBusinessHandler = { link ->
                if (link?.localBusinessId == LOCAL_BUSINESS_ID) {
                    withContext(NonCancellable) {
                        entered.complete(Unit)
                        release.await()
                    }
                }
                DomainResult.Success(Unit)
            }
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(AccountContract.Action.MembershipSelected(CLOUD_BUSINESS_ID))
            runCurrent()
            assertTrue(entered.isCompleted)

            appConfig.completeOnboarding(
                OTHER_LOCAL_BUSINESS_ID,
                TaxRate(BigDecimal("18")),
                CostPolicy.NET,
            )
            runCurrent()
            release.complete(Unit)
            runCurrent()

            val staleLink = CloudBusinessLink(
                localBusinessId = LOCAL_BUSINESS_ID,
                cloudBusinessId = CLOUD_BUSINESS_ID,
                role = BusinessRole.OWNER,
            )
            assertEquals(
                listOf(staleLink),
                membershipRepository.setActiveCloudBusinessCalls,
            )
            assertEquals(
                listOf(
                    FakeBusinessMembershipRepository.CompareAndSetActiveCloudBusinessCall(
                        expectedUid = UID,
                        expectedLink = staleLink,
                        newLink = null,
                    ),
                ),
                membershipRepository.compareAndSetActiveCloudBusinessCalls,
            )
            assertEquals(OTHER_LOCAL_BUSINESS_ID, viewModel.uiState.value.activeBusinessId)
            assertNull(viewModel.uiState.value.changingLinkTo)
            assertNull(viewModel.uiState.value.feedback)
            assertEquals(0, scheduler.enqueueCount)
        }

    @Test
    fun `ABA local L1 L2 L1 invalida el epoch y compensa la escritura tardia`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            accountRepository.emitSession(
                AccountSession.Active(uid = UID, email = EMAIL, link = null),
            )
            membershipRepository.memberships = listOf(
                CloudMembership(CLOUD_BUSINESS_ID, "Bodega Nube", BusinessRole.OWNER),
            )
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            membershipRepository.setActiveCloudBusinessHandler = {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                }
                DomainResult.Success(Unit)
            }
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(AccountContract.Action.MembershipSelected(CLOUD_BUSINESS_ID))
            runCurrent()
            assertTrue(entered.isCompleted)

            membershipRepository.localBusinessIdentityEpoch += 1L
            appConfig.completeOnboarding(
                OTHER_LOCAL_BUSINESS_ID,
                TaxRate(BigDecimal("18")),
                CostPolicy.NET,
            )
            runCurrent()
            membershipRepository.localBusinessIdentityEpoch += 1L
            appConfig.completeOnboarding(
                LOCAL_BUSINESS_ID,
                TaxRate(BigDecimal("18")),
                CostPolicy.NET,
            )
            runCurrent()
            release.complete(Unit)
            runCurrent()

            assertEquals(listOf(0L), membershipRepository.setActiveCloudBusinessEpochCalls)
            assertEquals(listOf(2L), membershipRepository.compareAndSetActiveCloudBusinessEpochCalls)
            assertEquals(LOCAL_BUSINESS_ID, viewModel.uiState.value.activeBusinessId)
            assertNull(viewModel.uiState.value.feedback)
            assertNull(viewModel.uiState.value.changingLinkTo)
            assertEquals(0, scheduler.enqueueCount)
        }

    @Test
    fun `intento local sin cambio invalida comando pero libera UI y permite reintento`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            accountRepository.emitSession(
                AccountSession.Active(uid = UID, email = EMAIL, link = null),
            )
            membershipRepository.memberships = listOf(
                CloudMembership(CLOUD_BUSINESS_ID, "Bodega Nube", BusinessRole.OWNER),
            )
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            membershipRepository.setActiveCloudBusinessHandler = {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                }
                DomainResult.Success(Unit)
            }
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(AccountContract.Action.MembershipSelected(CLOUD_BUSINESS_ID))
            runCurrent()
            assertTrue(entered.isCompleted)

            membershipRepository.localBusinessIdentityEpoch += 1L
            appConfig.completeOnboarding(
                LOCAL_BUSINESS_ID,
                TaxRate(BigDecimal("18")),
                CostPolicy.NET,
            )
            runCurrent()
            release.complete(Unit)
            runCurrent()

            assertNull(viewModel.uiState.value.changingLinkTo)
            assertNull(viewModel.uiState.value.feedback)
            membershipRepository.setActiveCloudBusinessHandler = null

            viewModel.onAction(AccountContract.Action.MembershipSelected(CLOUD_BUSINESS_ID))
            runCurrent()

            assertEquals(listOf(0L, 1L), membershipRepository.setActiveCloudBusinessEpochCalls)
            assertNull(viewModel.uiState.value.changingLinkTo)
            assertEquals(AccountContract.Feedback.LINK_UPDATED, viewModel.uiState.value.feedback)
            assertEquals(1, scheduler.enqueueCount)
        }

    @Test
    fun `fallo al programar tras cambiar negocio no convierte el enlace persistido en error`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            accountRepository.emitSession(
                AccountSession.Active(uid = UID, email = EMAIL, link = null),
            )
            membershipRepository.memberships = listOf(
                CloudMembership(CLOUD_BUSINESS_ID, "Bodega Nube", BusinessRole.OWNER),
            )
            scheduler.enqueueFailure = IllegalStateException("WorkManager sin espacio")
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(AccountContract.Action.MembershipSelected(CLOUD_BUSINESS_ID))
            runCurrent()

            assertEquals(1, membershipRepository.setActiveCloudBusinessCalls.size)
            assertEquals(AccountContract.Feedback.LINK_UPDATED, viewModel.uiState.value.feedback)
            assertNull(viewModel.uiState.value.failure)
            assertNull(viewModel.uiState.value.changingLinkTo)
            assertEquals(0, scheduler.enqueueCount)
        }

    @Test
    fun `cambiar de negocio sin negocio local activo no llama al puerto`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val sinNegocio = FakeAppConfigurationRepository()
            accountRepository.emitSession(
                AccountSession.Active(uid = UID, email = EMAIL, link = null),
            )
            val viewModel = createViewModel(appConfig = sinNegocio)
            runCurrent()

            viewModel.onAction(AccountContract.Action.MembershipSelected(CLOUD_BUSINESS_ID))
            runCurrent()

            assertTrue(membershipRepository.setActiveCloudBusinessCalls.isEmpty())
            assertEquals(0, scheduler.enqueueCount)
        }

    @Test
    fun `binding Room fijo rechaza retarget antes de escribir el enlace remoto`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            accountRepository.emitSession(
                AccountSession.Active(uid = UID, email = EMAIL, link = null),
            )
            membershipRepository.memberships = listOf(
                CloudMembership(CLOUD_BUSINESS_ID, "Bodega fija", BusinessRole.OWNER),
                CloudMembership(OTHER_CLOUD_BUSINESS_ID, "Destino rechazado", BusinessRole.ADMIN),
            )
            cloudBindings.seed(LOCAL_BUSINESS_ID, CLOUD_BUSINESS_ID)
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(
                AccountContract.Action.MembershipSelected(OTHER_CLOUD_BUSINESS_ID),
            )
            runCurrent()

            assertEquals(1, cloudBindings.bindCalls)
            assertTrue(membershipRepository.setActiveCloudBusinessCalls.isEmpty())
            assertEquals(AccountError.CloudBusinessAlreadyBound, viewModel.uiState.value.failure)
            assertEquals(0, scheduler.enqueueCount)
            assertEquals(0, scheduler.privacyEnqueueCount)
        }

    @Test
    fun `modo demo nunca vincula su negocio efimero con una membresia cloud`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            accountRepository.emitSession(
                AccountSession.Active(uid = UID, email = EMAIL, link = null),
            )
            membershipRepository.memberships = listOf(
                CloudMembership(CLOUD_BUSINESS_ID, "Bodega Nube", BusinessRole.OWNER),
            )
            appConfig.enterDemoMode(OTHER_LOCAL_BUSINESS_ID)
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(AccountContract.Action.MembershipSelected(CLOUD_BUSINESS_ID))
            runCurrent()

            assertTrue(viewModel.uiState.value.isDemoMode)
            assertEquals(OTHER_LOCAL_BUSINESS_ID, viewModel.uiState.value.activeBusinessId)
            assertEquals(0, cloudBindings.bindCalls)
            assertTrue(membershipRepository.setActiveCloudBusinessCalls.isEmpty())
            assertEquals(0, scheduler.enqueueCount)
            assertEquals(0, scheduler.privacyEnqueueCount)
        }

    @Test
    fun `retarget bloqueado conserva enlace y muestra error cerrado sin programar sync`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            accountRepository.emitSession(
                AccountSession.Active(
                    uid = UID,
                    email = EMAIL,
                    link = CloudBusinessLink(
                        localBusinessId = LOCAL_BUSINESS_ID,
                        cloudBusinessId = CLOUD_BUSINESS_ID,
                        role = BusinessRole.OWNER,
                    ),
                ),
            )
            membershipRepository.memberships = listOf(
                CloudMembership(CLOUD_BUSINESS_ID, "Bodega Nube", BusinessRole.OWNER),
                CloudMembership(OTHER_CLOUD_BUSINESS_ID, "Otro negocio", BusinessRole.ADMIN),
            )
            membershipRepository.nextSetActiveCloudBusinessResult =
                DomainResult.Failure(AccountError.CloudBusinessAlreadyBound)
            val viewModel = createViewModel()
            runCurrent()
            // El refresco valida primero el vínculo actual; el fallo programado se reserva para
            // el intento explícito de cambio.
            membershipRepository.nextSetActiveCloudBusinessResult =
                DomainResult.Failure(AccountError.CloudBusinessAlreadyBound)

            viewModel.onAction(
                AccountContract.Action.MembershipSelected(OTHER_CLOUD_BUSINESS_ID),
            )
            runCurrent()

            assertEquals(AccountError.CloudBusinessAlreadyBound, viewModel.uiState.value.failure)
            assertEquals(0, scheduler.enqueueCount)
            assertNull(viewModel.uiState.value.feedback)
        }

    @Test
    fun `refresco exitoso degrada el rol persistido al rol confirmado por servidor`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            accountRepository.emitSession(
                AccountSession.Active(
                    uid = UID,
                    email = EMAIL,
                    link = CloudBusinessLink(
                        localBusinessId = LOCAL_BUSINESS_ID,
                        cloudBusinessId = CLOUD_BUSINESS_ID,
                        role = BusinessRole.OWNER,
                    ),
                ),
            )
            membershipRepository.memberships = listOf(
                CloudMembership(CLOUD_BUSINESS_ID, "Bodega Nube", BusinessRole.OPERATOR),
            )
            val persistedLink = (accountRepository.session as AccountSession.Active).link
            membershipRepository.seedActiveLink(UID, persistedLink)

            createViewModel()
            runCurrent()

            assertEquals(
                listOf(
                    FakeBusinessMembershipRepository.CompareAndSetActiveCloudBusinessCall(
                        expectedUid = UID,
                        expectedLink = persistedLink,
                        newLink = CloudBusinessLink(
                            localBusinessId = LOCAL_BUSINESS_ID,
                            cloudBusinessId = CLOUD_BUSINESS_ID,
                            role = BusinessRole.OPERATOR,
                        ),
                    ),
                ),
                membershipRepository.compareAndSetActiveCloudBusinessCalls,
            )
        }

    @Test
    fun `binding Room repara un enlace DataStore que apuntaba a otro tenant`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val staleLink = CloudBusinessLink(
                localBusinessId = LOCAL_BUSINESS_ID,
                cloudBusinessId = OTHER_CLOUD_BUSINESS_ID,
                role = BusinessRole.ADMIN,
            )
            accountRepository.emitSession(
                AccountSession.Active(uid = UID, email = EMAIL, link = staleLink),
            )
            membershipRepository.memberships = listOf(
                CloudMembership(CLOUD_BUSINESS_ID, "Bodega fija", BusinessRole.OPERATOR),
                CloudMembership(OTHER_CLOUD_BUSINESS_ID, "Enlace viejo", BusinessRole.ADMIN),
            )
            membershipRepository.seedActiveLink(UID, staleLink)
            cloudBindings.seed(LOCAL_BUSINESS_ID, CLOUD_BUSINESS_ID)

            createViewModel()
            runCurrent()

            assertEquals(
                listOf(
                    FakeBusinessMembershipRepository.CompareAndSetActiveCloudBusinessCall(
                        expectedUid = UID,
                        expectedLink = staleLink,
                        newLink = CloudBusinessLink(
                            localBusinessId = LOCAL_BUSINESS_ID,
                            cloudBusinessId = CLOUD_BUSINESS_ID,
                            role = BusinessRole.OPERATOR,
                        ),
                    ),
                ),
                membershipRepository.compareAndSetActiveCloudBusinessCalls,
            )
            assertEquals(1, scheduler.enqueueCount)
            assertEquals(1, scheduler.privacyEnqueueCount)
        }

    @Test
    fun `refresco exitoso revoca enlace cuando la membresia ya no existe`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            accountRepository.emitSession(
                AccountSession.Active(
                    uid = UID,
                    email = EMAIL,
                    link = CloudBusinessLink(
                        localBusinessId = LOCAL_BUSINESS_ID,
                        cloudBusinessId = CLOUD_BUSINESS_ID,
                        role = BusinessRole.OWNER,
                    ),
                ),
            )
            membershipRepository.memberships = emptyList()
            val persistedLink = (accountRepository.session as AccountSession.Active).link
            membershipRepository.seedActiveLink(UID, persistedLink)

            createViewModel()
            runCurrent()

            assertEquals(
                listOf(
                    FakeBusinessMembershipRepository.CompareAndSetActiveCloudBusinessCall(
                        expectedUid = UID,
                        expectedLink = persistedLink,
                        newLink = null,
                    ),
                ),
                membershipRepository.compareAndSetActiveCloudBusinessCalls,
            )
        }

    @Test
    fun `refresco fallido conserva el ultimo rol confirmado para uso offline`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            accountRepository.emitSession(
                AccountSession.Active(
                    uid = UID,
                    email = EMAIL,
                    link = CloudBusinessLink(
                        localBusinessId = LOCAL_BUSINESS_ID,
                        cloudBusinessId = CLOUD_BUSINESS_ID,
                        role = BusinessRole.OWNER,
                    ),
                ),
            )
            membershipRepository.nextListMyMembershipsResult =
                DomainResult.Failure(AccountError.NetworkUnavailable)

            val viewModel = createViewModel()
            runCurrent()

            assertTrue(membershipRepository.setActiveCloudBusinessCalls.isEmpty())
            assertEquals(AccountError.NetworkUnavailable, viewModel.uiState.value.failure)
        }

    @Test
    fun `cerrar sesion confirmado delega en el puerto y vuelve a SignedOut`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            accountRepository.emitSession(
                AccountSession.Active(uid = UID, email = EMAIL, link = null),
            )
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(AccountContract.Action.SignOutRequested)
            runCurrent()
            assertTrue(viewModel.uiState.value.showSignOutDialog)

            viewModel.onAction(AccountContract.Action.SignOutConfirmed)
            runCurrent()

            // El puerto real cancela los trabajos del usuario; el fake registra la llamada.
            assertEquals(1, accountRepository.signOutCalls)
            val state = viewModel.uiState.value
            assertEquals(AccountSession.SignedOut, state.session)
            assertFalse(state.showSignOutDialog)
            assertFalse(state.isWorking)
            assertTrue(state.memberships.isEmpty())
        }

    @Test
    fun `backend sin capacidad de borrado no ofrece ni envia la accion`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            accountRepository = FakeAccountRepository(
                initialSession = AccountSession.Active(uid = UID, email = EMAIL, link = null),
                accountDeletionAvailable = false,
            )
            val viewModel = createViewModel()
            runCurrent()
            viewModel.onAction(AccountContract.Action.DeleteAccountRequested)
            viewModel.onAction(AccountContract.Action.AccountDeletionPasswordChanged(DELETE_PASSWORD))
            viewModel.onAction(AccountContract.Action.DeleteAccountConfirmed)
            runCurrent()

            assertFalse(viewModel.uiState.value.accountDeletionAvailable)
            assertFalse(viewModel.uiState.value.showAccountDeletionDialog)
            assertEquals(0, accountRepository.accountDeletionCalls)
        }

    @Test
    fun `eliminacion requiere confirmacion y doble confirmacion solo llama una vez`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            accountRepository.emitSession(
                AccountSession.Active(uid = UID, email = EMAIL, link = null),
            )
            accountRepository.nextAccountDeletionResult =
                DomainResult.Success(AccountDeletionSummary(emptyList(), 0))
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(AccountContract.Action.DeleteAccountRequested)
            runCurrent()
            assertTrue(viewModel.uiState.value.showAccountDeletionDialog)
            assertEquals(0, accountRepository.accountDeletionCalls)

            viewModel.onAction(AccountContract.Action.DeleteAccountConfirmed)
            runCurrent()
            assertEquals(0, accountRepository.accountDeletionCalls)
            viewModel.onAction(
                AccountContract.Action.AccountDeletionPasswordChanged(DELETE_PASSWORD),
            )
            viewModel.onAction(AccountContract.Action.DeleteAccountConfirmed)
            viewModel.onAction(AccountContract.Action.DeleteAccountConfirmed)
            runCurrent()

            assertEquals(1, accountRepository.accountDeletionCalls)
            assertEquals(listOf(DELETE_PASSWORD), accountRepository.accountDeletionPasswords)
            assertEquals("", viewModel.uiState.value.accountDeletionPassword)
            assertEquals(1, accountRepository.signOutCalls)
            assertEquals(AccountSession.SignedOut, viewModel.uiState.value.session)
            assertEquals(AccountContract.Feedback.ACCOUNT_DELETED, viewModel.uiState.value.feedback)
            assertFalse(viewModel.uiState.value.deletionPendingSignOut)
        }

    @Test
    fun `fallo remoto ambiguo limpia la sesion sin repetir la eliminacion`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            accountRepository.emitSession(AccountSession.Active(uid = UID, email = EMAIL, link = null))
            accountRepository.nextAccountDeletionResult =
                DomainResult.Failure(AccountError.NetworkUnavailable)
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(AccountContract.Action.DeleteAccountRequested)
            runCurrent()
            viewModel.onAction(
                AccountContract.Action.AccountDeletionPasswordChanged(DELETE_PASSWORD),
            )
            viewModel.onAction(AccountContract.Action.DeleteAccountConfirmed)
            runCurrent()

            assertEquals(1, accountRepository.accountDeletionCalls)
            assertEquals(1, accountRepository.signOutCalls)
            assertEquals(AccountSession.SignedOut, viewModel.uiState.value.session)
            assertNull(accountRepository.pendingAccountDeletionUid())
            assertFalse(viewModel.uiState.value.deletionPendingSignOut)
            assertEquals(
                AccountContract.Feedback.ACCOUNT_DELETION_UNCONFIRMED,
                viewModel.uiState.value.feedback,
            )
        }

    @Test
    fun `aceptacion pendiente informa el trabajo durable sin afirmar borrado completo`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            accountRepository.emitSession(AccountSession.Active(uid = UID, email = EMAIL, link = null))
            accountRepository.nextAccountDeletionResult =
                DomainResult.Success(AccountDeletionSummary(emptyList(), 0, isPending = true))
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(AccountContract.Action.DeleteAccountRequested)
            viewModel.onAction(AccountContract.Action.AccountDeletionPasswordChanged(DELETE_PASSWORD))
            viewModel.onAction(AccountContract.Action.DeleteAccountConfirmed)
            runCurrent()

            assertEquals(1, accountRepository.accountDeletionCalls)
            assertEquals(AccountSession.SignedOut, viewModel.uiState.value.session)
            assertEquals(
                AccountContract.Feedback.ACCOUNT_DELETION_PENDING,
                viewModel.uiState.value.feedback,
            )
        }

    @Test
    fun `rechazo definitivo retira checkpoint y permite corregir la clave`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val active = AccountSession.Active(uid = UID, email = EMAIL, link = null)
            accountRepository.emitSession(active)
            accountRepository.nextAccountDeletionResult =
                DomainResult.Failure(AccountError.InvalidCredentials)
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(AccountContract.Action.DeleteAccountRequested)
            viewModel.onAction(
                AccountContract.Action.AccountDeletionPasswordChanged(DELETE_PASSWORD),
            )
            viewModel.onAction(AccountContract.Action.DeleteAccountConfirmed)
            runCurrent()

            assertEquals(1, accountRepository.accountDeletionCalls)
            assertTrue(accountRepository.signOutIfCurrentCalls.isEmpty())
            assertNull(accountRepository.pendingAccountDeletionUid())
            assertEquals(active, viewModel.uiState.value.session)
            assertTrue(viewModel.uiState.value.showAccountDeletionDialog)
            assertEquals(AccountError.InvalidCredentials, viewModel.uiState.value.failure)
        }

    @Test
    fun `cuenta aun no verificada tambien puede eliminarse`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            accountRepository.emitSession(
                AccountSession.AwaitingVerification(uid = UID, email = EMAIL),
            )
            accountRepository.nextAccountDeletionResult =
                DomainResult.Success(AccountDeletionSummary(emptyList(), 0))
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(AccountContract.Action.DeleteAccountRequested)
            runCurrent()
            assertTrue(viewModel.uiState.value.showAccountDeletionDialog)
            viewModel.onAction(
                AccountContract.Action.AccountDeletionPasswordChanged(DELETE_PASSWORD),
            )
            viewModel.onAction(AccountContract.Action.DeleteAccountConfirmed)
            runCurrent()

            assertEquals(1, accountRepository.accountDeletionCalls)
            assertEquals(1, accountRepository.signOutCalls)
            assertEquals(AccountSession.SignedOut, viewModel.uiState.value.session)
        }

    @Test
    fun `fallo de limpieza reintenta sign out sin repetir borrado remoto`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            accountRepository.emitSession(
                AccountSession.Active(uid = UID, email = EMAIL, link = null),
            )
            accountRepository.nextAccountDeletionResult =
                DomainResult.Success(AccountDeletionSummary(emptyList(), 0))
            accountRepository.nextSignOutResult =
                DomainResult.Failure(AccountError.NetworkUnavailable)
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(AccountContract.Action.DeleteAccountRequested)
            runCurrent()
            viewModel.onAction(
                AccountContract.Action.AccountDeletionPasswordChanged(DELETE_PASSWORD),
            )
            viewModel.onAction(AccountContract.Action.DeleteAccountConfirmed)
            runCurrent()

            assertEquals(1, accountRepository.accountDeletionCalls)
            assertEquals(1, accountRepository.signOutCalls)
            assertTrue(viewModel.uiState.value.deletionPendingSignOut)
            assertEquals(AccountError.NetworkUnavailable, viewModel.uiState.value.failure)

            viewModel.effects.test {
                viewModel.onAction(AccountContract.Action.BackSelected)
                viewModel.onAction(AccountContract.Action.OpenMembers)
                viewModel.onAction(AccountContract.Action.OpenInvitations)
                viewModel.onAction(AccountContract.Action.SignOutRequested)
                runCurrent()
                expectNoEvents()
            }
            assertFalse(viewModel.uiState.value.showSignOutDialog)

            viewModel.onAction(AccountContract.Action.RetryDeletedAccountCleanup)
            viewModel.onAction(AccountContract.Action.RetryDeletedAccountCleanup)
            runCurrent()

            assertEquals(1, accountRepository.accountDeletionCalls)
            assertEquals(2, accountRepository.signOutCalls)
            assertFalse(viewModel.uiState.value.deletionPendingSignOut)
            assertEquals(AccountSession.SignedOut, viewModel.uiState.value.session)
        }

    @Test
    fun `marca restaurada completa sign out sin repetir borrado remoto`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            accountRepository.emitSession(
                AccountSession.Active(uid = UID, email = EMAIL, link = null),
            )
            accountRepository.seedPendingAccountDeletion(UID)
            val viewModel = createViewModel()

            runCurrent()

            assertEquals(0, accountRepository.accountDeletionCalls)
            assertEquals(1, accountRepository.signOutCalls)
            assertEquals(AccountSession.SignedOut, viewModel.uiState.value.session)
            assertFalse(viewModel.uiState.value.deletionPendingSignOut)
            assertEquals(
                AccountContract.Feedback.ACCOUNT_DELETION_UNCONFIRMED,
                viewModel.uiState.value.feedback,
            )
        }

    @Test
    fun `marca restaurada de otra cuenta nunca cierra la sesion actual`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            accountRepository.emitSession(
                AccountSession.Active(uid = OTHER_UID, email = "bea@example.com", link = null),
            )
            accountRepository.seedPendingAccountDeletion(UID)
            val viewModel = createViewModel()

            runCurrent()

            assertEquals(listOf(UID), accountRepository.signOutIfCurrentCalls)
            assertEquals(OTHER_UID, (viewModel.uiState.value.session as AccountSession.Active).uid)
            assertFalse(viewModel.uiState.value.deletionPendingSignOut)
            assertNull(accountRepository.pendingAccountDeletionUid())
            assertNull(viewModel.uiState.value.feedback)
        }

    @Test
    fun `resultado tardio de borrado de A no inicia limpieza sobre B`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            accountRepository.emitSession(
                AccountSession.Active(uid = UID, email = EMAIL, link = null),
            )
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            accountRepository.accountDeletionHandler = {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                }
                DomainResult.Success(AccountDeletionSummary(emptyList(), 0))
            }
            val viewModel = createViewModel()
            runCurrent()
            viewModel.onAction(AccountContract.Action.DeleteAccountRequested)
            runCurrent()
            viewModel.onAction(
                AccountContract.Action.AccountDeletionPasswordChanged(DELETE_PASSWORD),
            )
            viewModel.onAction(AccountContract.Action.DeleteAccountConfirmed)
            runCurrent()
            assertTrue(entered.isCompleted)

            accountRepository.emitSession(
                AccountSession.Active(uid = OTHER_UID, email = "bea@example.com", link = null),
            )
            runCurrent()
            release.complete(Unit)
            runCurrent()

            assertEquals(1, accountRepository.accountDeletionCalls)
            assertEquals(listOf(UID), accountRepository.signOutIfCurrentCalls)
            assertEquals(OTHER_UID, (viewModel.uiState.value.session as AccountSession.Active).uid)
            assertFalse(viewModel.uiState.value.deletionPendingSignOut)
            assertNull(accountRepository.pendingAccountDeletionUid())
            assertFalse(viewModel.uiState.value.isDeletingAccount)
            assertNull(viewModel.uiState.value.feedback)
        }

    @Test
    fun `checkpoint durable se limpia aunque la sesion ya este ausente`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            accountRepository.emitSession(AccountSession.SignedOut)
            accountRepository.seedPendingAccountDeletion(UID)

            val viewModel = createViewModel()
            runCurrent()

            assertEquals(listOf(UID), accountRepository.signOutIfCurrentCalls)
            assertNull(accountRepository.pendingAccountDeletionUid())
            assertFalse(viewModel.uiState.value.deletionPendingSignOut)
        }

    @Test
    fun `back queda bloqueado mientras el borrado remoto sigue en vuelo`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            accountRepository.emitSession(
                AccountSession.Active(uid = UID, email = EMAIL, link = null),
            )
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            accountRepository.accountDeletionHandler = {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                }
                DomainResult.Success(AccountDeletionSummary(emptyList(), 0))
            }
            val viewModel = createViewModel()
            runCurrent()
            viewModel.onAction(AccountContract.Action.DeleteAccountRequested)
            viewModel.onAction(
                AccountContract.Action.AccountDeletionPasswordChanged(DELETE_PASSWORD),
            )
            viewModel.onAction(AccountContract.Action.DeleteAccountConfirmed)
            runCurrent()
            assertTrue(entered.isCompleted)
            assertEquals(UID, accountRepository.pendingAccountDeletionUid())

            viewModel.effects.test {
                viewModel.onAction(AccountContract.Action.BackSelected)
                runCurrent()
                expectNoEvents()
                release.complete(Unit)
                runCurrent()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `sesion expirada intenta recuperar la sesion al reconectar`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            accountRepository.emitSession(AccountSession.Expired(email = EMAIL))
            val link = CloudBusinessLink(
                localBusinessId = LOCAL_BUSINESS_ID,
                cloudBusinessId = CLOUD_BUSINESS_ID,
                role = BusinessRole.OWNER,
            )
            accountRepository.nextRecoverSessionResult = DomainResult.Success(
                AccountSession.Active(uid = UID, email = EMAIL, link = link),
            )
            val viewModel = createViewModel()
            runCurrent()
            assertTrue(viewModel.uiState.value.session is AccountSession.Expired)

            viewModel.onAction(AccountContract.Action.Reconnect)
            runCurrent()

            assertEquals(1, accountRepository.recoverSessionCalls)
            assertTrue(viewModel.uiState.value.session is AccountSession.Active)
            assertFalse(viewModel.uiState.value.showExpiredSignInForm)
            assertEquals(1, scheduler.enqueueCount)
            assertEquals(1, scheduler.privacyEnqueueCount)
        }

    @Test
    fun `recuperar sesion sin enlace despierta privacidad pero no respaldo comercial`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            accountRepository.emitSession(AccountSession.Expired(email = EMAIL))
            val active = AccountSession.Active(uid = UID, email = EMAIL, link = null)
            accountRepository.nextRecoverSessionResult = DomainResult.Success(active)
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(AccountContract.Action.Reconnect)
            runCurrent()

            assertEquals(active, viewModel.uiState.value.session)
            assertEquals(0, scheduler.enqueueCount)
            assertEquals(1, scheduler.privacyEnqueueCount)
        }

    @Test
    fun `fallo al programar tras recuperar sesion conserva la sesion recuperada`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            accountRepository.emitSession(AccountSession.Expired(email = EMAIL))
            val active = AccountSession.Active(
                uid = UID,
                email = EMAIL,
                link = CloudBusinessLink(
                    localBusinessId = LOCAL_BUSINESS_ID,
                    cloudBusinessId = CLOUD_BUSINESS_ID,
                    role = BusinessRole.OWNER,
                ),
            )
            accountRepository.nextRecoverSessionResult = DomainResult.Success(active)
            scheduler.enqueueFailure = IllegalStateException("WorkManager sin espacio")
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(AccountContract.Action.Reconnect)
            runCurrent()

            assertEquals(active, viewModel.uiState.value.session)
            assertNull(viewModel.uiState.value.failure)
            assertFalse(viewModel.uiState.value.isWorking)
            assertEquals(0, scheduler.enqueueCount)
        }

    @Test
    fun `recuperacion fallida pide reingresar con el error cerrado`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            accountRepository.emitSession(AccountSession.Expired(email = EMAIL))
            accountRepository.nextRecoverSessionResult =
                DomainResult.Failure(AccountError.SessionExpired)
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(AccountContract.Action.Reconnect)
            runCurrent()

            val state = viewModel.uiState.value
            assertEquals(1, accountRepository.recoverSessionCalls)
            assertEquals(AccountError.SessionExpired, state.failure)
            assertTrue(state.showExpiredSignInForm)
            assertEquals(0, scheduler.enqueueCount)
        }

    @Test
    fun `modo avion conserva sesion vencida y reconexion recupera Firebase y la outbox`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val link = CloudBusinessLink(
                localBusinessId = LOCAL_BUSINESS_ID,
                cloudBusinessId = CLOUD_BUSINESS_ID,
                role = BusinessRole.OWNER,
            )
            val active = AccountSession.Active(uid = UID, email = EMAIL, link = link)
            accountRepository.emitSession(AccountSession.Expired(email = EMAIL))
            accountRepository.nextRecoverSessionResult = DomainResult.Success(active)
            firebaseConnectivity.disconnect()
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(AccountContract.Action.Reconnect)
            runCurrent()

            val offline = viewModel.uiState.value
            assertEquals(AccountSession.Expired(email = EMAIL), offline.session)
            assertEquals(AccountError.NetworkUnavailable, offline.failure)
            assertTrue(offline.showExpiredSignInForm)
            assertEquals(EMAIL, offline.email)
            assertEquals(1, accountRepository.recoverSessionCalls)
            assertEquals(0, scheduler.enqueueCount)

            firebaseConnectivity.reconnect()
            viewModel.onAction(AccountContract.Action.Reconnect)
            runCurrent()

            val reconnected = viewModel.uiState.value
            assertEquals(active, reconnected.session)
            assertNull(reconnected.failure)
            assertFalse(reconnected.showExpiredSignInForm)
            assertFalse(reconnected.isWorking)
            assertEquals(2, accountRepository.recoverSessionCalls)
            assertEquals(1, scheduler.enqueueCount)
            assertEquals(1, scheduler.privacyEnqueueCount)
        }

    @Test
    fun `las acciones de navegacion emiten sus efectos`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val viewModel = createViewModel()
            runCurrent()

            viewModel.effects.test {
                viewModel.onAction(AccountContract.Action.OpenMembers)
                viewModel.onAction(AccountContract.Action.OpenInvitations)
                viewModel.onAction(AccountContract.Action.BackSelected)
                runCurrent()

                assertEquals(AccountContract.Effect.OpenMembers, awaitItem())
                assertEquals(AccountContract.Effect.OpenInvitations, awaitItem())
                assertEquals(AccountContract.Effect.Back, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    private fun createViewModel(
        appConfig: FakeAppConfigurationRepository = this.appConfig,
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ): AccountViewModel = AccountViewModel(
        savedStateHandle = savedStateHandle,
        accountRepository = accountRepository,
        membershipRepository = membershipRepository,
        observeAppConfigurationUseCase = ObserveAppConfigurationUseCase(appConfig),
        purchaseBackupScheduler = scheduler,
        bindCloudBusinessLink = BindCloudBusinessLinkUseCase(
            cloudBindings,
            AppClock { Instant.EPOCH },
        ),
        dispatcherProvider = dispatchers,
    )

    private companion object {
        const val UID = "uid-ana"
        const val OTHER_UID = "uid-bea"
        const val EMAIL = "ana@example.com"
        const val PASSWORD = "secreto1"
        const val DELETE_PASSWORD = "correct-secret"
        val LOCAL_BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("10000000-0000-4000-8000-000000000001"),
        )
        val CLOUD_BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("20000000-0000-4000-8000-000000000002"),
        )
        val OTHER_CLOUD_BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("30000000-0000-4000-8000-000000000003"),
        )
        val OTHER_LOCAL_BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("40000000-0000-4000-8000-000000000004"),
        )
    }
}
