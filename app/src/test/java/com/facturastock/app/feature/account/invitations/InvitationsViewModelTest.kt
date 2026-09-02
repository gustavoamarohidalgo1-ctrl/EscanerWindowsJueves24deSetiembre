package com.facturastock.app.feature.account.invitations

import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.config.CostPolicy
import com.facturastock.app.domain.config.TaxRate
import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.model.AccountSession
import com.facturastock.app.domain.model.BusinessInvitation
import com.facturastock.app.domain.model.BusinessRole
import com.facturastock.app.domain.model.CloudBusinessLink
import com.facturastock.app.domain.model.CloudBusinessLinkReceipt
import com.facturastock.app.domain.model.CloudMembership
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.BusinessMembershipRepository
import com.facturastock.app.domain.usecase.ObserveAppConfigurationUseCase
import com.facturastock.app.domain.usecase.BindCloudBusinessLinkUseCase
import com.facturastock.app.testing.FakeAccountRepository
import com.facturastock.app.testing.FakeAppConfigurationRepository
import com.facturastock.app.testing.FakeBusinessMembershipRepository
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
class InvitationsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var membershipRepository: FakeBusinessMembershipRepository
    private lateinit var accountRepository: FakeAccountRepository
    private lateinit var appConfig: FakeAppConfigurationRepository
    private lateinit var scheduler: FakePurchaseBackupScheduler
    private lateinit var cloudBindings: FakeCloudBusinessBindingRepository
    private lateinit var dispatchers: TestDispatcherProvider

    @Before
    fun setUp() = runTest {
        membershipRepository = FakeBusinessMembershipRepository()
        accountRepository = FakeAccountRepository(initialSession = activeSession(UID_A, EMAIL_A))
        appConfig = FakeAppConfigurationRepository()
        scheduler = FakePurchaseBackupScheduler()
        cloudBindings = FakeCloudBusinessBindingRepository()
        dispatchers = TestDispatcherProvider(mainDispatcherRule.dispatcher)
        appConfig.completeOnboarding(LOCAL_BUSINESS_ID, TaxRate(BigDecimal("18")), CostPolicy.NET)
    }

    @Test
    fun `lista las invitaciones pendientes del correo de la cuenta`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            membershipRepository.myInvitations = listOf(INVITATION, OTHER_INVITATION)
            val viewModel = createViewModel()
            runCurrent()

            val state = viewModel.uiState.value
            assertEquals(listOf(INVITATION, OTHER_INVITATION), state.invitations)
            assertEquals(1, membershipRepository.listMyInvitationsCalls)
            assertEquals(listOf(UID_A), membershipRepository.listMyInvitationsUidCalls)
            assertEquals(LOCAL_BUSINESS_ID, state.activeBusinessId)
            assertFalse(state.isBusy)
        }

    @Test
    fun `aceptar recarga la lista y ofrece activar el negocio`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            membershipRepository.myInvitations = listOf(INVITATION)
            val accepted = CloudMembership(
                businessId = INVITATION.businessId,
                businessDisplayName = INVITATION.businessDisplayName,
                role = INVITATION.role,
            )
            membershipRepository.acceptedMembership = accepted
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(InvitationsContract.Action.Accept(INVITATION))
            runCurrent()

            val state = viewModel.uiState.value
            assertEquals(listOf(UID_A), membershipRepository.acceptInvitationUidCalls)
            assertEquals(listOf(INVITATION.businessId), membershipRepository.acceptInvitationCalls)
            // La membresía aceptada queda visible para ofrecer el enlace.
            assertEquals(accepted, state.acceptedMembership)
            assertNull(state.processingBusinessId)
            // La recarga ya no trae la invitación aceptada.
            assertEquals(2, membershipRepository.listMyInvitationsCalls)
            assertEquals(emptyList<BusinessInvitation>(), state.invitations)
            assertEquals(0, scheduler.enqueueCount)
            assertEquals(1, scheduler.privacyEnqueueCount)

            viewModel.onAction(InvitationsContract.Action.DismissAccepted)
            runCurrent()

            assertNull(viewModel.uiState.value.acceptedMembership)
            assertEquals(0, scheduler.enqueueCount)
            assertEquals(1, scheduler.privacyEnqueueCount)
        }

    @Test
    fun `activar el negocio aceptado enlaza con el negocio local activo y encola`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            membershipRepository.myInvitations = listOf(INVITATION)
            membershipRepository.acceptedMembership = CloudMembership(
                businessId = INVITATION.businessId,
                businessDisplayName = INVITATION.businessDisplayName,
                role = INVITATION.role,
            )
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(InvitationsContract.Action.Accept(INVITATION))
            runCurrent()
            viewModel.onAction(InvitationsContract.Action.SetActiveAfterAccept)
            runCurrent()

            assertEquals(
                listOf(
                    CloudBusinessLink(
                        localBusinessId = LOCAL_BUSINESS_ID,
                        cloudBusinessId = INVITATION.businessId,
                        role = INVITATION.role,
                    ),
                ),
                membershipRepository.setActiveCloudBusinessCalls,
            )
            assertEquals(1, scheduler.enqueueCount)
            // Aceptar despierta privacidad y enlazar confirma ambos canales nuevamente.
            assertEquals(2, scheduler.privacyEnqueueCount)
            val state = viewModel.uiState.value
            assertEquals(InvitationsContract.Feedback.LINK_UPDATED, state.feedback)
            assertNull(state.acceptedMembership)
            assertFalse(state.isSettingActive)
        }

    @Test
    fun `modo demo acepta invitacion pero nunca enlaza el tenant al negocio efimero`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            appConfig.enterDemoMode(OTHER_LOCAL_BUSINESS_ID)
            membershipRepository.myInvitations = listOf(INVITATION)
            membershipRepository.acceptedMembership = CloudMembership(
                businessId = INVITATION.businessId,
                businessDisplayName = INVITATION.businessDisplayName,
                role = INVITATION.role,
            )
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(InvitationsContract.Action.Accept(INVITATION))
            runCurrent()
            viewModel.onAction(InvitationsContract.Action.SetActiveAfterAccept)
            runCurrent()

            assertTrue(viewModel.uiState.value.isDemoMode)
            assertEquals(OTHER_LOCAL_BUSINESS_ID, viewModel.uiState.value.activeBusinessId)
            assertEquals(0, cloudBindings.bindCalls)
            assertTrue(membershipRepository.setActiveCloudBusinessCalls.isEmpty())
            assertEquals(0, scheduler.enqueueCount)
            assertEquals(1, scheduler.privacyEnqueueCount)
        }

    @Test
    fun `fallo al programar no convierte la activacion persistida en error`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            membershipRepository.myInvitations = listOf(INVITATION)
            membershipRepository.acceptedMembership = CloudMembership(
                businessId = INVITATION.businessId,
                businessDisplayName = INVITATION.businessDisplayName,
                role = INVITATION.role,
            )
            scheduler.enqueueFailure = IllegalStateException("WorkManager sin espacio")
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(InvitationsContract.Action.Accept(INVITATION))
            runCurrent()
            viewModel.onAction(InvitationsContract.Action.SetActiveAfterAccept)
            runCurrent()

            assertEquals(1, membershipRepository.setActiveCloudBusinessCalls.size)
            val state = viewModel.uiState.value
            assertEquals(InvitationsContract.Feedback.LINK_UPDATED, state.feedback)
            assertNull(state.failure)
            assertNull(state.acceptedMembership)
            assertFalse(state.isSettingActive)
            assertEquals(0, scheduler.enqueueCount)
        }

    @Test
    fun `rechazar delega en el puerto y recarga la lista`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            membershipRepository.myInvitations = listOf(INVITATION, OTHER_INVITATION)
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(InvitationsContract.Action.Decline(INVITATION))
            runCurrent()

            val state = viewModel.uiState.value
            assertEquals(listOf(UID_A), membershipRepository.declineInvitationUidCalls)
            assertEquals(listOf(INVITATION.businessId), membershipRepository.declineInvitationCalls)
            assertEquals(InvitationsContract.Feedback.DECLINED, state.feedback)
            assertNull(state.processingBusinessId)
            assertEquals(2, membershipRepository.listMyInvitationsCalls)
            assertEquals(listOf(OTHER_INVITATION), state.invitations)
        }

    @Test
    fun `aceptar una invitacion expirada muestra el error cerrado y recarga`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            membershipRepository.myInvitations = listOf(INVITATION)
            membershipRepository.nextAcceptInvitationResult =
                DomainResult.Failure(AccountError.InvitationExpired)
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(InvitationsContract.Action.Accept(INVITATION))
            runCurrent()

            val state = viewModel.uiState.value
            assertEquals(AccountError.InvitationExpired, state.failure)
            assertNull(state.acceptedMembership)
            assertNull(state.processingBusinessId)
            // La lista se refresca: la invitación pudo ser gestionada por otra vía.
            assertEquals(2, membershipRepository.listMyInvitationsCalls)
            assertEquals(0, scheduler.enqueueCount)
        }

    @Test
    fun `rechazar con fallo de red muestra el error cerrado`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            membershipRepository.myInvitations = listOf(INVITATION)
            membershipRepository.nextDeclineInvitationResult =
                DomainResult.Failure(AccountError.NetworkUnavailable)
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(InvitationsContract.Action.Decline(INVITATION))
            runCurrent()

            val state = viewModel.uiState.value
            assertEquals(AccountError.NetworkUnavailable, state.failure)
            assertNull(state.processingBusinessId)
        }

    @Test
    fun `cerrar sesion deja una lista terminal vacia y Retry no carga sin uid activo`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            membershipRepository.myInvitations = listOf(INVITATION)
            val viewModel = createViewModel()
            runCurrent()
            assertEquals(listOf(INVITATION), viewModel.uiState.value.invitations)

            accountRepository.emitSession(AccountSession.SignedOut)
            runCurrent()

            val signedOut = viewModel.uiState.value
            assertEquals(emptyList<BusinessInvitation>(), signedOut.invitations)
            assertNull(signedOut.acceptedMembership)
            assertFalse(signedOut.isBusy)
            assertNull(signedOut.feedback)
            assertNull(signedOut.failure)

            viewModel.onAction(InvitationsContract.Action.Retry)
            runCurrent()

            assertEquals(1, membershipRepository.listMyInvitationsCalls)
            assertEquals(emptyList<BusinessInvitation>(), viewModel.uiState.value.invitations)
        }

    @Test
    fun `un load no cooperativo de la cuenta A no reemplaza la lista de la cuenta B`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val oldLoadStarted = CompletableDeferred<Unit>()
            val releaseOldLoad = CompletableDeferred<Unit>()
            var loadIndex = 0
            val repository = ControlledMembershipRepository(membershipRepository).apply {
                listMyInvitationsHandler = {
                    loadIndex += 1
                    if (loadIndex == 1) {
                        oldLoadStarted.complete(Unit)
                        withContext(NonCancellable) { releaseOldLoad.await() }
                        DomainResult.Success(listOf(INVITATION))
                    } else {
                        DomainResult.Success(listOf(OTHER_INVITATION))
                    }
                }
            }
            val viewModel = createViewModel(repository)
            runCurrent()
            assertTrue(oldLoadStarted.isCompleted)

            switchToAccountB()
            runCurrent()

            assertEquals(listOf(OTHER_INVITATION), viewModel.uiState.value.invitations)
            assertNull(viewModel.uiState.value.acceptedMembership)
            assertFalse(viewModel.uiState.value.isBusy)

            releaseOldLoad.complete(Unit)
            runCurrent()

            assertEquals(2, repository.listMyInvitationsCalls)
            assertEquals(listOf(UID_A, UID_B), repository.listMyInvitationsUidCalls)
            assertEquals(listOf(OTHER_INVITATION), viewModel.uiState.value.invitations)
            assertNull(viewModel.uiState.value.feedback)
        }

    @Test
    fun `un accept no cooperativo de la cuenta A no publica membresia en la cuenta B`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            membershipRepository.myInvitations = listOf(INVITATION)
            val acceptStarted = CompletableDeferred<Unit>()
            val releaseAccept = CompletableDeferred<Unit>()
            val repository = ControlledMembershipRepository(membershipRepository).apply {
                acceptInvitationHandler = { _, businessId ->
                    acceptStarted.complete(Unit)
                    withContext(NonCancellable) { releaseAccept.await() }
                    DomainResult.Success(
                        CloudMembership(
                            businessId = businessId,
                            businessDisplayName = INVITATION.businessDisplayName,
                            role = INVITATION.role,
                        ),
                    )
                }
            }
            val viewModel = createViewModel(repository)
            runCurrent()

            viewModel.onAction(InvitationsContract.Action.Accept(INVITATION))
            runCurrent()
            assertTrue(acceptStarted.isCompleted)
            assertEquals(INVITATION.businessId, viewModel.uiState.value.processingBusinessId)

            membershipRepository.myInvitations = listOf(OTHER_INVITATION)
            switchToAccountB()
            runCurrent()

            val switched = viewModel.uiState.value
            assertEquals(listOf(OTHER_INVITATION), switched.invitations)
            assertNull(switched.acceptedMembership)
            assertNull(switched.processingBusinessId)
            assertNull(switched.feedback)

            releaseAccept.complete(Unit)
            runCurrent()

            assertEquals(listOf(UID_A), repository.acceptInvitationUidCalls)
            assertEquals(listOf(INVITATION.businessId), repository.acceptInvitationCalls)
            assertEquals(listOf(OTHER_INVITATION), viewModel.uiState.value.invitations)
            assertNull(viewModel.uiState.value.acceptedMembership)
            assertFalse(viewModel.uiState.value.isBusy)
        }

    @Test
    fun `setActive no cooperativo conserva el negocio capturado y no confirma tras cambiar a B`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            membershipRepository.myInvitations = listOf(INVITATION)
            membershipRepository.acceptedMembership = CloudMembership(
                businessId = INVITATION.businessId,
                businessDisplayName = INVITATION.businessDisplayName,
                role = INVITATION.role,
            )
            val setActiveStarted = CompletableDeferred<Unit>()
            val releaseSetActive = CompletableDeferred<Unit>()
            val repository = ControlledMembershipRepository(membershipRepository).apply {
                setActiveCloudBusinessHandler = {
                    setActiveStarted.complete(Unit)
                    withContext(NonCancellable) { releaseSetActive.await() }
                    DomainResult.Success(Unit)
                }
            }
            val viewModel = createViewModel(repository)
            runCurrent()
            viewModel.onAction(InvitationsContract.Action.Accept(INVITATION))
            runCurrent()
            assertEquals(INVITATION.businessId, viewModel.uiState.value.acceptedMembership?.businessId)

            viewModel.onAction(InvitationsContract.Action.SetActiveAfterAccept)
            runCurrent()
            assertTrue(setActiveStarted.isCompleted)
            assertTrue(viewModel.uiState.value.isSettingActive)

            appConfig.enterDemoMode(OTHER_LOCAL_BUSINESS_ID)
            runCurrent()

            assertEquals(
                listOf(
                    CloudBusinessLink(
                        localBusinessId = LOCAL_BUSINESS_ID,
                        cloudBusinessId = INVITATION.businessId,
                        role = INVITATION.role,
                    ),
                ),
                repository.setActiveCloudBusinessCalls,
            )
            val switched = viewModel.uiState.value
            assertEquals(OTHER_LOCAL_BUSINESS_ID, switched.activeBusinessId)
            assertNull(switched.acceptedMembership)
            assertFalse(switched.isSettingActive)
            assertNull(switched.feedback)

            releaseSetActive.complete(Unit)
            runCurrent()

            assertEquals(0, scheduler.enqueueCount)
            assertNull(viewModel.uiState.value.feedback)
            assertNull(viewModel.uiState.value.acceptedMembership)
            assertFalse(viewModel.uiState.value.isBusy)
        }

    @Test
    fun `setActive de invitacion rechaza ABA local L1 L2 L1 mediante epoch`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            membershipRepository.myInvitations = listOf(INVITATION)
            membershipRepository.acceptedMembership = CloudMembership(
                businessId = INVITATION.businessId,
                businessDisplayName = INVITATION.businessDisplayName,
                role = INVITATION.role,
            )
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val repository = ControlledMembershipRepository(membershipRepository).apply {
                setActiveCloudBusinessHandler = {
                    entered.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                    DomainResult.Success(Unit)
                }
            }
            val viewModel = createViewModel(repository)
            runCurrent()
            viewModel.onAction(InvitationsContract.Action.Accept(INVITATION))
            runCurrent()
            viewModel.onAction(InvitationsContract.Action.SetActiveAfterAccept)
            runCurrent()
            assertTrue(entered.isCompleted)

            membershipRepository.localBusinessIdentityEpoch += 1L
            appConfig.enterDemoMode(OTHER_LOCAL_BUSINESS_ID)
            runCurrent()
            membershipRepository.localBusinessIdentityEpoch += 1L
            appConfig.exitDemoMode()
            runCurrent()
            release.complete(Unit)
            runCurrent()

            assertEquals(listOf(0L), membershipRepository.setActiveCloudBusinessEpochCalls)
            assertEquals(listOf(2L), membershipRepository.compareAndSetActiveCloudBusinessEpochCalls)
            assertEquals(LOCAL_BUSINESS_ID, viewModel.uiState.value.activeBusinessId)
            assertNull(viewModel.uiState.value.feedback)
            assertFalse(viewModel.uiState.value.isBusy)
            assertEquals(0, scheduler.enqueueCount)
        }

    private fun invitation(businessId: BusinessId, name: String) = BusinessInvitation(
        businessId = businessId,
        businessDisplayName = name,
        email = "ana@example.com",
        role = BusinessRole.OPERATOR,
        expiresAtEpochMilli = 1_900_000_000_000L,
    )

    private fun createViewModel(
        repository: BusinessMembershipRepository = membershipRepository,
    ): InvitationsViewModel = InvitationsViewModel(
        accountRepository = accountRepository,
        membershipRepository = repository,
        observeAppConfigurationUseCase = ObserveAppConfigurationUseCase(appConfig),
        purchaseBackupScheduler = scheduler,
        bindCloudBusinessLink = BindCloudBusinessLinkUseCase(
            cloudBindings,
            AppClock { Instant.EPOCH },
        ),
        dispatcherProvider = dispatchers,
    )

    private fun switchToAccountB() {
        accountRepository.emitSession(activeSession(UID_B, EMAIL_B))
    }

    private class ControlledMembershipRepository(
        private val delegate: BusinessMembershipRepository,
    ) : BusinessMembershipRepository by delegate {
        var listMyInvitationsHandler:
            (suspend () -> DomainResult<List<BusinessInvitation>>)? = null
        var acceptInvitationHandler:
            (suspend (String, BusinessId) -> DomainResult<CloudMembership>)? = null
        var setActiveCloudBusinessHandler:
            (suspend (CloudBusinessLink?) -> DomainResult<Unit>)? = null

        var listMyInvitationsCalls: Int = 0
            private set
        val listMyInvitationsUidCalls = mutableListOf<String>()
        val acceptInvitationUidCalls = mutableListOf<String>()
        val acceptInvitationCalls = mutableListOf<BusinessId>()
        val setActiveCloudBusinessCalls = mutableListOf<CloudBusinessLink?>()

        override suspend fun listMyInvitations(
            expectedUid: String,
        ): DomainResult<List<BusinessInvitation>> {
            listMyInvitationsUidCalls += expectedUid
            listMyInvitationsCalls += 1
            return listMyInvitationsHandler?.invoke() ?: delegate.listMyInvitations(expectedUid)
        }

        override suspend fun acceptInvitation(
            expectedUid: String,
            businessId: BusinessId,
        ): DomainResult<CloudMembership> {
            acceptInvitationUidCalls += expectedUid
            acceptInvitationCalls += businessId
            return acceptInvitationHandler?.invoke(expectedUid, businessId)
                ?: delegate.acceptInvitation(expectedUid, businessId)
        }

        override suspend fun setActiveCloudBusiness(
            expectedUid: String,
            expectedLocalIdentityEpoch: Long,
            link: CloudBusinessLink,
        ): DomainResult<CloudBusinessLinkReceipt> {
            setActiveCloudBusinessCalls += link
            return when (val scripted = setActiveCloudBusinessHandler?.invoke(link)) {
                is DomainResult.Failure -> scripted
                else -> delegate.setActiveCloudBusiness(
                    expectedUid,
                    expectedLocalIdentityEpoch,
                    link,
                )
            }
        }
    }

    private companion object {
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
        const val UID_A = "uid-invitations-a"
        const val EMAIL_A = "invitations-a@example.com"
        const val UID_B = "uid-invitations-b"
        const val EMAIL_B = "invitations-b@example.com"

        fun activeSession(uid: String, email: String): AccountSession.Active =
            AccountSession.Active(uid = uid, email = email, link = null)
    }

    private val INVITATION = invitation(CLOUD_BUSINESS_ID, "Bodega Nube")
    private val OTHER_INVITATION = invitation(OTHER_CLOUD_BUSINESS_ID, "Otro negocio")
}
