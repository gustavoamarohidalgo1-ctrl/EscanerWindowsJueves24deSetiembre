package com.facturastock.app.feature.account.members

import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.AccountException
import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.error.FacturaStockError
import com.facturastock.app.domain.model.AccountSession
import com.facturastock.app.domain.model.BusinessInvitation
import com.facturastock.app.domain.model.CloudMember
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.AccountRepository
import com.facturastock.app.domain.repository.BusinessMembershipRepository
import com.facturastock.app.feature.common.UdfViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest

@HiltViewModel
class MembersViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val membershipRepository: BusinessMembershipRepository,
    dispatcherProvider: DispatcherProvider,
) : UdfViewModel<MembersContract.State, MembersContract.Action, MembersContract.Effect>(
    initialState = MembersContract.State(),
    dispatcherProvider = dispatcherProvider,
) {
    private var sessionJob: Job? = null
    private var loadJob: Job? = null
    private var inviteJob: Job? = null
    private var roleJob: Job? = null
    private var removeJob: Job? = null
    private var activeIdentity: MembersIdentity? = null
    private var loadGeneration: Long = 0L

    init {
        observeSession()
    }

    override fun onAction(action: MembersContract.Action) {
        when (action) {
            is MembersContract.Action.InviteEmailChanged -> executeMain {
                updateState {
                    copy(
                        inviteEmail = action.value.take(MembersContract.INVITE_EMAIL_MAX_LENGTH),
                        feedback = null,
                    )
                }
            }

            is MembersContract.Action.InviteRoleSelected -> executeMain {
                updateState { copy(inviteRole = action.role) }
            }

            MembersContract.Action.SendInvitation -> sendInvitation()

            is MembersContract.Action.ChangeRoleRequested -> executeMain {
                updateState {
                    copy(
                        roleEditUid = action.member.uid,
                        roleEditSelection = action.member.role,
                        feedback = null,
                    )
                }
            }

            is MembersContract.Action.ChangeRoleSelected -> executeMain {
                updateState { copy(roleEditSelection = action.role) }
            }

            MembersContract.Action.ChangeRoleConfirmed -> changeRole()

            MembersContract.Action.ChangeRoleDismissed -> executeMain {
                updateState { copy(roleEditUid = null, roleEditSelection = null) }
            }

            is MembersContract.Action.RemoveRequested -> executeMain {
                updateState { copy(removeCandidate = action.member) }
            }

            MembersContract.Action.RemoveConfirmed -> removeMember()

            MembersContract.Action.RemoveDismissed -> executeMain {
                updateState { copy(removeCandidate = null) }
            }

            MembersContract.Action.Retry -> {
                if (uiState.value.session == null || uiState.value.initialLoadFailure != null) {
                    observeSession()
                } else {
                    refresh()
                }
            }

            MembersContract.Action.BackSelected -> executeMain {
                emitEffect(MembersContract.Effect.Back)
            }
        }
    }

    private fun observeSession() {
        sessionJob?.cancel()
        sessionJob = executeMain {
            try {
                accountRepository.observeSession().collectLatest { session ->
                    val identity = session.membersIdentity()
                    // Todo estado sin enlace (incluido logout) es una frontera terminal aunque
                    // el token anterior también fuese null.
                    if (identity == null || identity != activeIdentity) {
                        activeIdentity = identity
                        cancelIdentityWork()
                        // Ningún dato o estado de edición del tenant anterior sobrevive al
                        // cambio de UID, negocio cloud o cierre de sesión.
                        updateState { MembersContract.State(session = session) }
                    } else {
                        updateState { copy(session = session, initialLoadFailure = null) }
                    }
                    identity?.let(::loadAll)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                updateState { copy(initialLoadFailure = AccountError.Unexpected) }
            }
        }
    }

    private fun refresh() {
        activeIdentity?.let(::loadAll)
    }

    private fun loadAll(identity: MembersIdentity) {
        if (activeIdentity != identity) return
        loadJob?.cancel()
        val token = MembersLoadToken(identity = identity, generation = ++loadGeneration)
        loadJob = executeIo(
            before = {
                if (!isCurrent(token)) throw CancellationException("members load superseded")
                updateState { copy(isLoading = true, failure = null) }
            },
            operation = {
                currentCoroutineContext().ensureActive()
                when (
                    val membersResult = membershipRepository.listMembers(
                        expectedUid = identity.uid,
                        businessId = identity.cloudBusinessId,
                    )
                ) {
                    is DomainResult.Failure -> MembersSnapshot.Failure(membersResult.error)

                    is DomainResult.Success -> {
                        val myRole = membersResult.value
                            .firstOrNull { it.uid == identity.uid }
                            ?.role
                        val invitations = if (myRole?.canManageMembers == true) {
                            currentCoroutineContext().ensureActive()
                            when (
                                val pending = membershipRepository.listBusinessInvitations(
                                    expectedUid = identity.uid,
                                    businessId = identity.cloudBusinessId,
                                )
                            ) {
                                is DomainResult.Success -> pending.value
                                // No sustituir un fallo por "cero invitaciones": en un refresh
                                // se conserva el snapshot previo; en la primera carga se muestra
                                // el error recuperable hasta obtener ambas proyecciones.
                                is DomainResult.Failure ->
                                    return@executeIo MembersSnapshot.Failure(pending.error)
                            }
                        } else {
                            emptyList()
                        }
                        MembersSnapshot.Loaded(membersResult.value, invitations)
                    }
                }
            },
            onSuccess = success@{ snapshot ->
                if (!isCurrent(token)) return@success
                when (snapshot) {
                    is MembersSnapshot.Loaded -> updateState {
                        copy(
                            isLoading = false,
                            members = snapshot.members,
                            pendingInvitations = snapshot.pendingInvitations,
                        )
                    }

                    is MembersSnapshot.Failure -> updateState {
                        copy(isLoading = false, failure = snapshot.error.toAccountError())
                    }
                }
            },
            onFailure = failure@{ error ->
                if (!isCurrent(token)) return@failure
                updateState { copy(isLoading = false, failure = error.toAccountError()) }
            },
        )
    }

    private fun sendInvitation() {
        val snapshot = uiState.value
        val identity = activeIdentity ?: return
        val link = snapshot.link?.takeIf { it.cloudBusinessId == identity.cloudBusinessId }
            ?: return
        if (!snapshot.canInvite) return
        if (inviteJob?.isActive == true) return
        inviteJob = executeIo(
            before = {
                if (activeIdentity != identity) throw CancellationException("identity changed")
                updateState { copy(isInviting = true, failure = null, feedback = null) }
            },
            operation = {
                membershipRepository.inviteMember(
                    expectedUid = identity.uid,
                    businessId = link.cloudBusinessId,
                    email = snapshot.inviteEmail.trim(),
                    role = snapshot.inviteRole,
                )
            },
            onSuccess = success@{ result ->
                if (activeIdentity != identity) return@success
                when (result) {
                    is DomainResult.Success -> {
                        updateState {
                            copy(
                                isInviting = false,
                                inviteEmail = "",
                                feedback = MembersContract.Feedback.INVITED,
                            )
                        }
                        refresh()
                    }

                    is DomainResult.Failure -> updateState {
                        copy(isInviting = false, failure = result.error.toAccountError())
                    }
                }
            },
            onFailure = failure@{ error ->
                if (activeIdentity != identity) return@failure
                updateState { copy(isInviting = false, failure = error.toAccountError()) }
            },
        )
    }

    private fun changeRole() {
        val snapshot = uiState.value
        val identity = activeIdentity ?: return
        val link = snapshot.link?.takeIf { it.cloudBusinessId == identity.cloudBusinessId }
            ?: return
        val memberUid = snapshot.roleEditUid ?: return
        val role = snapshot.roleEditSelection ?: return
        if (snapshot.isChangingRole || roleJob?.isActive == true) return
        roleJob = executeIo(
            before = {
                if (activeIdentity != identity) throw CancellationException("identity changed")
                updateState {
                    copy(isChangingRole = true, failure = null, feedback = null)
                }
            },
            operation = {
                membershipRepository.changeMemberRole(
                    expectedUid = identity.uid,
                    businessId = link.cloudBusinessId,
                    memberUid = memberUid,
                    role = role,
                )
            },
            onSuccess = success@{ result ->
                if (activeIdentity != identity) return@success
                when (result) {
                    is DomainResult.Success -> {
                        updateState {
                            copy(
                                isChangingRole = false,
                                roleEditUid = null,
                                roleEditSelection = null,
                                feedback = MembersContract.Feedback.ROLE_UPDATED,
                            )
                        }
                        refresh()
                    }

                    is DomainResult.Failure -> updateState {
                        copy(isChangingRole = false, failure = result.error.toAccountError())
                    }
                }
            },
            onFailure = failure@{ error ->
                if (activeIdentity != identity) return@failure
                updateState { copy(isChangingRole = false, failure = error.toAccountError()) }
            },
        )
    }

    private fun removeMember() {
        val snapshot = uiState.value
        val identity = activeIdentity ?: return
        val link = snapshot.link?.takeIf { it.cloudBusinessId == identity.cloudBusinessId }
            ?: return
        val candidate = snapshot.removeCandidate ?: return
        if (snapshot.isRemoving || removeJob?.isActive == true) return
        removeJob = executeIo(
            before = {
                if (activeIdentity != identity) throw CancellationException("identity changed")
                updateState { copy(isRemoving = true, failure = null, feedback = null) }
            },
            operation = {
                membershipRepository.removeMember(
                    expectedUid = identity.uid,
                    businessId = link.cloudBusinessId,
                    memberUid = candidate.uid,
                )
            },
            onSuccess = success@{ result ->
                if (activeIdentity != identity) return@success
                when (result) {
                    is DomainResult.Success -> {
                        updateState {
                            copy(
                                isRemoving = false,
                                removeCandidate = null,
                                feedback = MembersContract.Feedback.MEMBER_REMOVED,
                            )
                        }
                        refresh()
                    }

                    is DomainResult.Failure -> updateState {
                        copy(isRemoving = false, failure = result.error.toAccountError())
                    }
                }
            },
            onFailure = failure@{ error ->
                if (activeIdentity != identity) return@failure
                updateState { copy(isRemoving = false, failure = error.toAccountError()) }
            },
        )
    }

    private fun cancelIdentityWork() {
        // Invalida incluso operaciones que ignoren temporalmente la cancelación cooperativa.
        loadGeneration++
        loadJob?.cancel()
        inviteJob?.cancel()
        roleJob?.cancel()
        removeJob?.cancel()
        loadJob = null
        inviteJob = null
        roleJob = null
        removeJob = null
    }

    private fun isCurrent(token: MembersLoadToken): Boolean =
        activeIdentity == token.identity && loadGeneration == token.generation
}

/** Resultado cerrado de la carga conjunta de miembros e invitaciones pendientes. */
private sealed interface MembersSnapshot {
    data class Loaded(
        val members: List<CloudMember>,
        val pendingInvitations: List<BusinessInvitation>,
    ) : MembersSnapshot

    data class Failure(val error: FacturaStockError) : MembersSnapshot
}

/** Frontera de aislamiento: ni el negocio por sí solo ni el UID por sí solo son suficientes. */
private data class MembersIdentity(
    val uid: String,
    val cloudBusinessId: BusinessId,
)

private data class MembersLoadToken(
    val identity: MembersIdentity,
    val generation: Long,
)

private fun AccountSession.membersIdentity(): MembersIdentity? {
    val active = this as? AccountSession.Active ?: return null
    val link = active.link ?: return null
    return MembersIdentity(uid = active.uid, cloudBusinessId = link.cloudBusinessId)
}

private fun FacturaStockError.toAccountError(): AccountError =
    this as? AccountError ?: AccountError.Unexpected

private fun Throwable.toAccountError(): AccountError =
    (this as? AccountException)?.error ?: AccountError.Unexpected
