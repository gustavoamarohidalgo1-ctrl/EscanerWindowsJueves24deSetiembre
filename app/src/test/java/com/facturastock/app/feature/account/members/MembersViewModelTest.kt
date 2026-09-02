package com.facturastock.app.feature.account.members

import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.model.AccountSession
import com.facturastock.app.domain.model.BusinessInvitation
import com.facturastock.app.domain.model.BusinessRole
import com.facturastock.app.domain.model.CloudBusinessLink
import com.facturastock.app.domain.model.CloudMember
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.testing.FakeAccountRepository
import com.facturastock.app.testing.FakeBusinessMembershipRepository
import com.facturastock.app.testing.MainDispatcherRule
import com.facturastock.app.testing.TestDispatcherProvider
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
class MembersViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var accountRepository: FakeAccountRepository
    private lateinit var membershipRepository: FakeBusinessMembershipRepository
    private lateinit var dispatchers: TestDispatcherProvider

    @Before
    fun setUp() {
        accountRepository = FakeAccountRepository()
        membershipRepository = FakeBusinessMembershipRepository()
        dispatchers = TestDispatcherProvider(mainDispatcherRule.dispatcher)
    }

    @Test
    fun `email de invitacion se limita antes de entrar al estado`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            signInAs(OWNER_MEMBER)
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(MembersContract.Action.InviteEmailChanged("e".repeat(400)))
            runCurrent()

            assertEquals(
                MembersContract.INVITE_EMAIL_MAX_LENGTH,
                viewModel.uiState.value.inviteEmail.length,
            )
        }

    @Test
    fun `carga los miembros del negocio enlazado y sus invitaciones pendientes`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            signInAs(OWNER_MEMBER)
            membershipRepository.businessInvitations = listOf(invitation("pendiente@example.com"))
            val viewModel = createViewModel()
            runCurrent()

            val state = viewModel.uiState.value
            assertEquals(listOf(OWNER_MEMBER, OPERATOR_MEMBER), state.members)
            assertEquals(listOf(CLOUD_BUSINESS_ID), membershipRepository.listMembersCalls)
            assertEquals(listOf(OWNER_MEMBER.uid), membershipRepository.listMembersUidCalls)
            // Soy OWNER: también se cargan las invitaciones del negocio.
            assertEquals(listOf(CLOUD_BUSINESS_ID), membershipRepository.listBusinessInvitationsCalls)
            assertEquals(
                listOf(OWNER_MEMBER.uid),
                membershipRepository.listBusinessInvitationsUidCalls,
            )
            assertEquals(1, state.pendingInvitations.size)
            assertTrue(state.canManage)
            assertFalse(state.isLoading)
        }

    @Test
    fun `fallo inicial de sesion permite reintentar el Flow real`() =
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
            viewModel.onAction(MembersContract.Action.Retry)
            runCurrent()

            assertEquals(AccountSession.SignedOut, viewModel.uiState.value.session)
            assertNull(viewModel.uiState.value.initialLoadFailure)
        }

    @Test
    fun `fallo del Flow tras una sesion conserva cache y retry recupera el observador`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            signInAs(OWNER_MEMBER)
            membershipRepository.businessInvitations = listOf(invitation("cached@example.com"))
            accountRepository.observeSessionFailureAfterCurrentEmission =
                IllegalStateException("session observer stopped")
            val viewModel = createViewModel()
            runCurrent()

            val degraded = viewModel.uiState.value
            assertEquals(listOf(OWNER_MEMBER, OPERATOR_MEMBER), degraded.members)
            assertEquals(listOf("cached@example.com"), degraded.pendingInvitations.map { it.email })
            assertEquals(AccountError.Unexpected, degraded.initialLoadFailure)
            assertFalse(degraded.isLoading)

            accountRepository.observeSessionFailureAfterCurrentEmission = null
            viewModel.onAction(MembersContract.Action.Retry)
            runCurrent()

            val recovered = viewModel.uiState.value
            assertEquals(listOf(OWNER_MEMBER, OPERATOR_MEMBER), recovered.members)
            assertEquals(listOf("cached@example.com"), recovered.pendingInvitations.map { it.email })
            assertNull(recovered.initialLoadFailure)
            assertEquals(2, membershipRepository.listMembersCalls.size)
        }

    @Test
    fun `un OPERATOR no gestiona ni carga invitaciones del negocio`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            signInAs(OPERATOR_MEMBER)
            val viewModel = createViewModel()
            runCurrent()

            val state = viewModel.uiState.value
            assertEquals(listOf(OWNER_MEMBER, OPERATOR_MEMBER), state.members)
            // La carga de invitaciones exige OWNER o ADMIN: aquí ni siquiera se intenta.
            assertTrue(membershipRepository.listBusinessInvitationsCalls.isEmpty())
            assertTrue(state.pendingInvitations.isEmpty())

            // El estado derivado no expone ninguna acción de gestión.
            assertFalse(state.canManage)
            assertFalse(state.canInvite)
            assertTrue(state.grantableRoles.isEmpty())
            assertFalse(state.canActOn(OWNER_MEMBER))
            assertFalse(state.canActOn(OPERATOR_MEMBER))
        }

    @Test
    fun `invitar llama al puerto con negocio email y rol exactos y recarga`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            signInAs(OWNER_MEMBER)
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(MembersContract.Action.InviteEmailChanged("  nueva@example.com "))
            runCurrent()
            viewModel.onAction(MembersContract.Action.InviteRoleSelected(BusinessRole.READER))
            runCurrent()
            viewModel.onAction(MembersContract.Action.SendInvitation)
            runCurrent()

            assertEquals(listOf(OWNER_MEMBER.uid), membershipRepository.inviteMemberUidCalls)
            assertEquals(
                listOf(
                    FakeBusinessMembershipRepository.InviteMemberCall(
                        businessId = CLOUD_BUSINESS_ID,
                        email = "nueva@example.com",
                        role = BusinessRole.READER,
                    ),
                ),
                membershipRepository.inviteMemberCalls,
            )
            val state = viewModel.uiState.value
            assertEquals(MembersContract.Feedback.INVITED, state.feedback)
            assertEquals("", state.inviteEmail)
            assertFalse(state.isInviting)
            // La invitación exitosa recarga miembros e invitaciones.
            assertEquals(2, membershipRepository.listMembersCalls.size)
        }

    @Test
    fun `cambiar rol confirma con los argumentos exactos y recarga`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            signInAs(OWNER_MEMBER)
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(MembersContract.Action.ChangeRoleRequested(OPERATOR_MEMBER))
            runCurrent()
            assertEquals(OPERATOR_MEMBER.uid, viewModel.uiState.value.roleEditUid)
            assertEquals(OPERATOR_MEMBER.role, viewModel.uiState.value.roleEditSelection)

            viewModel.onAction(MembersContract.Action.ChangeRoleSelected(BusinessRole.ADMIN))
            runCurrent()
            viewModel.onAction(MembersContract.Action.ChangeRoleConfirmed)
            runCurrent()

            assertEquals(listOf(OWNER_MEMBER.uid), membershipRepository.changeMemberRoleUidCalls)
            assertEquals(
                listOf(
                    FakeBusinessMembershipRepository.ChangeMemberRoleCall(
                        businessId = CLOUD_BUSINESS_ID,
                        memberUid = OPERATOR_MEMBER.uid,
                        role = BusinessRole.ADMIN,
                    ),
                ),
                membershipRepository.changeMemberRoleCalls,
            )
            val state = viewModel.uiState.value
            assertEquals(MembersContract.Feedback.ROLE_UPDATED, state.feedback)
            assertNull(state.roleEditUid)
            assertNull(state.roleEditSelection)
            assertFalse(state.isChangingRole)
            assertEquals(2, membershipRepository.listMembersCalls.size)
        }

    @Test
    fun `eliminar miembro confirma con los argumentos exactos y recarga`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            signInAs(OWNER_MEMBER)
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(MembersContract.Action.RemoveRequested(OPERATOR_MEMBER))
            runCurrent()
            assertEquals(OPERATOR_MEMBER, viewModel.uiState.value.removeCandidate)

            viewModel.onAction(MembersContract.Action.RemoveConfirmed)
            runCurrent()

            assertEquals(listOf(OWNER_MEMBER.uid), membershipRepository.removeMemberUidCalls)
            assertEquals(
                listOf(CLOUD_BUSINESS_ID to OPERATOR_MEMBER.uid),
                membershipRepository.removeMemberCalls,
            )
            val state = viewModel.uiState.value
            assertEquals(MembersContract.Feedback.MEMBER_REMOVED, state.feedback)
            assertNull(state.removeCandidate)
            assertFalse(state.isRemoving)
            assertEquals(2, membershipRepository.listMembersCalls.size)
        }

    @Test
    fun `invitacion rechazada por el servidor muestra el error cerrado`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            signInAs(OWNER_MEMBER)
            membershipRepository.nextInviteMemberResult =
                DomainResult.Failure(AccountError.NetworkUnavailable)
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(MembersContract.Action.InviteEmailChanged("nueva@example.com"))
            runCurrent()
            viewModel.onAction(MembersContract.Action.SendInvitation)
            runCurrent()

            val state = viewModel.uiState.value
            assertEquals(AccountError.NetworkUnavailable, state.failure)
            assertFalse(state.isInviting)
            // Sin recarga: no cambió nada en el servidor.
            assertEquals(1, membershipRepository.listMembersCalls.size)
        }

    @Test
    fun `fallo al listar invitaciones no se presenta como lista vacia confirmada`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            signInAs(OWNER_MEMBER)
            membershipRepository.nextListBusinessInvitationsResult =
                DomainResult.Failure(AccountError.NetworkUnavailable)

            val viewModel = createViewModel()
            runCurrent()

            val state = viewModel.uiState.value
            assertNull(state.members)
            assertTrue(state.pendingInvitations.isEmpty())
            assertEquals(AccountError.NetworkUnavailable, state.failure)
            assertFalse(state.isLoading)
        }

    @Test
    fun `cambio de rol sin permiso muestra el error cerrado`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            signInAs(OWNER_MEMBER)
            membershipRepository.nextChangeMemberRoleResult =
                DomainResult.Failure(AccountError.PermissionDenied)
            val viewModel = createViewModel()
            runCurrent()

            viewModel.onAction(MembersContract.Action.ChangeRoleRequested(OPERATOR_MEMBER))
            runCurrent()
            viewModel.onAction(MembersContract.Action.ChangeRoleConfirmed)
            runCurrent()

            val state = viewModel.uiState.value
            assertEquals(AccountError.PermissionDenied, state.failure)
            assertFalse(state.isChangingRole)
        }

    @Test
    fun `sin enlace de negocio no hay miembros que cargar`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            accountRepository.emitSession(
                AccountSession.Active(uid = MY_UID, email = MY_EMAIL, link = null),
            )
            val viewModel = createViewModel()
            runCurrent()

            assertNull(viewModel.uiState.value.members)
            assertTrue(membershipRepository.listMembersCalls.isEmpty())
        }

    @Test
    fun `logout limpia estado transitorio aun si la cuenta activa no tenia enlace`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            accountRepository.emitSession(
                AccountSession.Active(uid = MY_UID, email = MY_EMAIL, link = null),
            )
            val viewModel = createViewModel()
            runCurrent()
            viewModel.onAction(MembersContract.Action.InviteEmailChanged("draft@example.com"))
            runCurrent()
            assertEquals("draft@example.com", viewModel.uiState.value.inviteEmail)

            accountRepository.emitSession(AccountSession.SignedOut)
            runCurrent()

            assertEquals(AccountSession.SignedOut, viewModel.uiState.value.session)
            assertEquals("", viewModel.uiState.value.inviteEmail)
            assertNull(viewModel.uiState.value.members)
            assertTrue(viewModel.uiState.value.pendingInvitations.isEmpty())
        }

    @Test
    fun `cambio de negocio limpia en caliente y una carga tardia no repuebla otro tenant`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            val firstStarted = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val secondStarted = CompletableDeferred<Unit>()
            val releaseSecond = CompletableDeferred<Unit>()
            membershipRepository.listMembersHandler = { businessId ->
                when (businessId) {
                    CLOUD_BUSINESS_ID -> {
                        firstStarted.complete(Unit)
                        withContext(NonCancellable) { releaseFirst.await() }
                        DomainResult.Success(listOf(OWNER_MEMBER))
                    }

                    SECOND_CLOUD_BUSINESS_ID -> {
                        secondStarted.complete(Unit)
                        releaseSecond.await()
                        DomainResult.Success(listOf(SECOND_OWNER_MEMBER))
                    }

                    else -> error("negocio inesperado")
                }
            }
            accountRepository.emitSession(activeSession(OWNER_MEMBER, CLOUD_BUSINESS_ID))
            val viewModel = createViewModel()
            runCurrent()
            assertTrue(firstStarted.isCompleted)

            accountRepository.emitSession(
                activeSession(SECOND_OWNER_MEMBER, SECOND_CLOUD_BUSINESS_ID),
            )
            runCurrent()

            assertTrue(secondStarted.isCompleted)
            assertNull(viewModel.uiState.value.members)
            assertTrue(viewModel.uiState.value.pendingInvitations.isEmpty())
            assertTrue(viewModel.uiState.value.isLoading)

            releaseSecond.complete(Unit)
            runCurrent()
            assertEquals(listOf(SECOND_OWNER_MEMBER), viewModel.uiState.value.members)
            assertEquals(SECOND_OWNER_MEMBER.uid, viewModel.uiState.value.myUid)

            // Simula un transporte que termina tarde aun después de recibir cancelación.
            releaseFirst.complete(Unit)
            runCurrent()
            assertEquals(listOf(SECOND_OWNER_MEMBER), viewModel.uiState.value.members)
            assertEquals(
                listOf(CLOUD_BUSINESS_ID, SECOND_CLOUD_BUSINESS_ID),
                membershipRepository.listMembersCalls,
            )
        }

    @Test
    fun `logout cancela carga pendiente y borra listas ediciones y flags del tenant`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            signInAs(OWNER_MEMBER)
            membershipRepository.businessInvitations = listOf(invitation("old@example.com"))
            val viewModel = createViewModel()
            runCurrent()
            assertEquals(listOf(OWNER_MEMBER, OPERATOR_MEMBER), viewModel.uiState.value.members)

            viewModel.onAction(MembersContract.Action.InviteEmailChanged("draft@example.com"))
            viewModel.onAction(MembersContract.Action.ChangeRoleRequested(OPERATOR_MEMBER))
            viewModel.onAction(MembersContract.Action.RemoveRequested(OPERATOR_MEMBER))
            runCurrent()

            val invitationStarted = CompletableDeferred<Unit>()
            val releaseInvitation = CompletableDeferred<Unit>()
            membershipRepository.listBusinessInvitationsHandler = {
                invitationStarted.complete(Unit)
                withContext(NonCancellable) { releaseInvitation.await() }
                DomainResult.Success(listOf(invitation("late@example.com")))
            }
            viewModel.onAction(MembersContract.Action.Retry)
            runCurrent()
            assertTrue(invitationStarted.isCompleted)
            assertTrue(viewModel.uiState.value.isLoading)

            accountRepository.emitSession(AccountSession.SignedOut)
            runCurrent()

            val signedOut = viewModel.uiState.value
            assertEquals(AccountSession.SignedOut, signedOut.session)
            assertNull(signedOut.members)
            assertTrue(signedOut.pendingInvitations.isEmpty())
            assertEquals("", signedOut.inviteEmail)
            assertNull(signedOut.roleEditUid)
            assertNull(signedOut.removeCandidate)
            assertFalse(signedOut.isLoading)
            assertFalse(signedOut.isInviting)
            assertFalse(signedOut.isChangingRole)
            assertFalse(signedOut.isRemoving)

            releaseInvitation.complete(Unit)
            runCurrent()
            assertEquals(signedOut, viewModel.uiState.value)
        }

    @Test
    fun `cambio de uid en el mismo negocio invalida la carga de la cuenta anterior`() =
        runTest(context = mainDispatcherRule.dispatcher) {
            signInAs(OWNER_MEMBER)
            val viewModel = createViewModel()
            runCurrent()

            val oldAccountStarted = CompletableDeferred<Unit>()
            val releaseOldAccount = CompletableDeferred<Unit>()
            val newAccountStarted = CompletableDeferred<Unit>()
            val releaseNewAccount = CompletableDeferred<Unit>()
            var racedCalls = 0
            membershipRepository.listMembersHandler = {
                racedCalls++
                if (racedCalls == 1) {
                    oldAccountStarted.complete(Unit)
                    withContext(NonCancellable) { releaseOldAccount.await() }
                    DomainResult.Success(listOf(OWNER_MEMBER, OPERATOR_MEMBER))
                } else {
                    newAccountStarted.complete(Unit)
                    releaseNewAccount.await()
                    DomainResult.Success(listOf(SECOND_OWNER_MEMBER))
                }
            }

            viewModel.onAction(MembersContract.Action.Retry)
            runCurrent()
            assertTrue(oldAccountStarted.isCompleted)

            accountRepository.emitSession(activeSession(SECOND_OWNER_MEMBER, CLOUD_BUSINESS_ID))
            runCurrent()
            assertTrue(newAccountStarted.isCompleted)
            assertNull(viewModel.uiState.value.members)
            assertEquals(SECOND_OWNER_MEMBER.uid, viewModel.uiState.value.myUid)

            releaseNewAccount.complete(Unit)
            runCurrent()
            assertEquals(listOf(SECOND_OWNER_MEMBER), viewModel.uiState.value.members)

            releaseOldAccount.complete(Unit)
            runCurrent()
            assertEquals(listOf(SECOND_OWNER_MEMBER), viewModel.uiState.value.members)
            assertEquals(3, membershipRepository.listMembersCalls.size)
        }

    /** Sesión activa enlazada cuyo rol en el negocio es el de [myMember]. */
    private fun signInAs(myMember: CloudMember) {
        accountRepository.emitSession(
            AccountSession.Active(
                uid = myMember.uid,
                email = myMember.email ?: MY_EMAIL,
                link = CloudBusinessLink(
                    localBusinessId = LOCAL_BUSINESS_ID,
                    cloudBusinessId = CLOUD_BUSINESS_ID,
                    role = myMember.role,
                ),
            ),
        )
        membershipRepository.members = listOf(OWNER_MEMBER, OPERATOR_MEMBER)
    }

    private fun invitation(email: String) = BusinessInvitation(
        businessId = CLOUD_BUSINESS_ID,
        businessDisplayName = "Bodega Nube",
        email = email,
        role = BusinessRole.READER,
        expiresAtEpochMilli = 1_900_000_000_000L,
    )

    private fun activeSession(member: CloudMember, cloudBusinessId: BusinessId) =
        AccountSession.Active(
            uid = member.uid,
            email = member.email ?: MY_EMAIL,
            link = CloudBusinessLink(
                localBusinessId = LOCAL_BUSINESS_ID,
                cloudBusinessId = cloudBusinessId,
                role = member.role,
            ),
        )

    private fun createViewModel(): MembersViewModel = MembersViewModel(
        accountRepository = accountRepository,
        membershipRepository = membershipRepository,
        dispatcherProvider = dispatchers,
    )

    private companion object {
        const val MY_UID = "uid-owner"
        const val MY_EMAIL = "owner@example.com"
        val LOCAL_BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("10000000-0000-4000-8000-000000000001"),
        )
        val CLOUD_BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("20000000-0000-4000-8000-000000000002"),
        )
        val SECOND_CLOUD_BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("30000000-0000-4000-8000-000000000003"),
        )
        val OWNER_MEMBER = CloudMember(
            uid = MY_UID,
            email = MY_EMAIL,
            role = BusinessRole.OWNER,
        )
        val OPERATOR_MEMBER = CloudMember(
            uid = "uid-operador",
            email = "operador@example.com",
            role = BusinessRole.OPERATOR,
        )
        val SECOND_OWNER_MEMBER = CloudMember(
            uid = "uid-second-owner",
            email = "second-owner@example.com",
            role = BusinessRole.OWNER,
        )
    }
}
