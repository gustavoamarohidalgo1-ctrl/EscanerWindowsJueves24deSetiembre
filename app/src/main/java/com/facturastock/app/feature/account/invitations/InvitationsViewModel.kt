package com.facturastock.app.feature.account.invitations

import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.AccountException
import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.error.FacturaStockError
import com.facturastock.app.domain.model.AccountSession
import com.facturastock.app.domain.model.BusinessInvitation
import com.facturastock.app.domain.model.CloudBusinessLink
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.AccountRepository
import com.facturastock.app.domain.repository.BusinessMembershipRepository
import com.facturastock.app.domain.repository.PurchaseBackupScheduler
import com.facturastock.app.domain.repository.enqueueBestEffort
import com.facturastock.app.domain.repository.enqueuePrivacyPurgeBestEffort
import com.facturastock.app.domain.usecase.BindCloudBusinessLinkUseCase
import com.facturastock.app.domain.usecase.ObserveAppConfigurationUseCase
import com.facturastock.app.feature.common.UdfViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

@HiltViewModel
class InvitationsViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val membershipRepository: BusinessMembershipRepository,
    private val observeAppConfigurationUseCase: ObserveAppConfigurationUseCase,
    private val purchaseBackupScheduler: PurchaseBackupScheduler,
    private val bindCloudBusinessLink: BindCloudBusinessLinkUseCase,
    dispatcherProvider: DispatcherProvider,
) : UdfViewModel<InvitationsContract.State, InvitationsContract.Action, InvitationsContract.Effect>(
    initialState = InvitationsContract.State(),
    dispatcherProvider = dispatcherProvider,
) {
    private var identityJob: Job? = null
    private var loadJob: Job? = null
    private var acceptJob: Job? = null
    private var declineJob: Job? = null
    private var setActiveJob: Job? = null
    private var identityGeneration: Long = 0
    @Volatile
    private var activeIdentityToken: InvitationsIdentityToken? = null

    init {
        observeIdentity()
    }

    override fun onAction(action: InvitationsContract.Action) {
        when (action) {
            is InvitationsContract.Action.Accept -> accept(action.invitation)

            is InvitationsContract.Action.Decline -> decline(action.invitation)

            InvitationsContract.Action.SetActiveAfterAccept -> setActiveAfterAccept()

            InvitationsContract.Action.DismissAccepted -> dismissAccepted()

            InvitationsContract.Action.Retry -> retry()

            InvitationsContract.Action.BackSelected -> executeMain {
                emitEffect(InvitationsContract.Effect.Back)
            }
        }
    }

    private fun observeIdentity() {
        identityJob?.cancel()
        identityJob = executeMain {
            try {
                combine(
                    accountRepository.observeSession(),
                    observeAppConfigurationUseCase(),
                ) { session, config ->
                    InvitationsIdentity(
                        activeUid = (session as? AccountSession.Active)?.uid,
                        activeBusinessId = config.activeBusinessId,
                        isDemoMode = config.isDemoMode,
                    )
                }.collectLatest(::applyIdentity)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                invalidateIdentity()
            }
        }
    }

    private fun applyIdentity(identity: InvitationsIdentity) {
        if (activeIdentityToken?.identity == identity) return

        identityGeneration += 1
        val token = InvitationsIdentityToken(identityGeneration, identity)
        // Invalidar primero impide que un callback no cooperativo publique datos de A en B.
        activeIdentityToken = token
        cancelIdentityScopedJobs()
        updateState {
            copy(
                activeBusinessId = identity.activeBusinessId,
                isDemoMode = identity.isDemoMode,
                invitations = if (identity.activeUid == null) emptyList() else null,
                processingBusinessId = null,
                acceptedMembership = null,
                isSettingActive = false,
                feedback = null,
                failure = null,
            )
        }
        if (identity.activeUid != null) load(token = token)
    }

    private fun invalidateIdentity() {
        activeIdentityToken = null
        identityGeneration += 1
        cancelIdentityScopedJobs()
        updateState {
            copy(
                activeBusinessId = null,
                invitations = null,
                processingBusinessId = null,
                acceptedMembership = null,
                isSettingActive = false,
                feedback = null,
                failure = AccountError.Unexpected,
            )
        }
    }

    private fun retry() {
        val token = activeIdentityToken
        if (token?.identity?.activeUid == null) {
            observeIdentity()
        } else {
            load(token = token)
        }
    }

    private fun load(
        token: InvitationsIdentityToken,
        clearFailure: Boolean = true,
    ) {
        val expectedUid = token.identity.activeUid ?: return
        if (!isCurrentIdentity(token)) return
        loadJob?.cancel()
        loadJob = executeIo(
            before = {
                if (clearFailure && isCurrentIdentity(token)) {
                    updateState { copy(failure = null) }
                }
            },
            operation = {
                ensureCurrentIdentity(token)
                membershipRepository.listMyInvitations(expectedUid)
            },
            onSuccess = success@ { result ->
                if (!isCurrentIdentity(token)) return@success
                when (result) {
                    is DomainResult.Success -> updateState {
                        copy(invitations = result.value)
                    }

                    is DomainResult.Failure -> updateState {
                        copy(failure = result.error.toAccountError())
                    }
                }
            },
            onFailure = failure@ { error ->
                if (!isCurrentIdentity(token)) return@failure
                updateState { copy(failure = error.toAccountError()) }
            },
        )
    }

    private fun accept(invitation: BusinessInvitation) {
        val token = activeIdentityToken ?: return
        val expectedUid = token.identity.activeUid ?: return
        val snapshot = uiState.value
        if (
            !isCurrentIdentity(token) || snapshot.isBusy ||
            invitation !in snapshot.invitations.orEmpty() || acceptJob?.isActive == true ||
            declineJob?.isActive == true || setActiveJob?.isActive == true
        ) {
            return
        }
        acceptJob = executeIo(
            before = {
                if (isCurrentIdentity(token)) updateState {
                    copy(
                        processingBusinessId = invitation.businessId,
                        failure = null,
                        feedback = null,
                    )
                }
            },
            operation = {
                ensureCurrentIdentity(token)
                val result = membershipRepository.acceptInvitation(
                    expectedUid = expectedUid,
                    businessId = invitation.businessId,
                )
                if (result is DomainResult.Success) {
                    // La membresía ya quedó aceptada de forma remota. Privacidad debe reintentar
                    // aunque la persona cierre el diálogo sin convertirla en el enlace activo.
                    withContext(NonCancellable) {
                        try {
                            purchaseBackupScheduler.enqueuePrivacyPurgeBestEffort()
                        } catch (_: CancellationException) {
                            // Startup vuelve a despertar la cola si WorkManager no pudo persistirla.
                        }
                    }
                }
                result
            },
            onSuccess = success@ { result ->
                if (!isCurrentIdentity(token)) return@success
                when (result) {
                    is DomainResult.Success -> {
                        updateState {
                            copy(
                                processingBusinessId = null,
                                acceptedMembership = result.value,
                            )
                        }
                        load(token = token)
                    }

                    is DomainResult.Failure -> {
                        updateState {
                            copy(
                                processingBusinessId = null,
                                failure = result.error.toAccountError(),
                            )
                        }
                        // La invitación pudo expirar o ser gestionada: refresca la lista sin
                        // perder el error que explica el fallo.
                        load(clearFailure = false, token = token)
                    }
                }
            },
            onFailure = failure@ { error ->
                if (!isCurrentIdentity(token)) return@failure
                updateState {
                    copy(processingBusinessId = null, failure = error.toAccountError())
                }
            },
        )
    }

    private fun decline(invitation: BusinessInvitation) {
        val token = activeIdentityToken ?: return
        val expectedUid = token.identity.activeUid ?: return
        val snapshot = uiState.value
        if (
            !isCurrentIdentity(token) || snapshot.isBusy ||
            invitation !in snapshot.invitations.orEmpty() || acceptJob?.isActive == true ||
            declineJob?.isActive == true || setActiveJob?.isActive == true
        ) {
            return
        }
        declineJob = executeIo(
            before = {
                if (isCurrentIdentity(token)) updateState {
                    copy(
                        processingBusinessId = invitation.businessId,
                        failure = null,
                        feedback = null,
                    )
                }
            },
            operation = {
                ensureCurrentIdentity(token)
                membershipRepository.declineInvitation(
                    expectedUid = expectedUid,
                    businessId = invitation.businessId,
                )
            },
            onSuccess = success@ { result ->
                if (!isCurrentIdentity(token)) return@success
                when (result) {
                    is DomainResult.Success -> {
                        updateState {
                            copy(
                                processingBusinessId = null,
                                feedback = InvitationsContract.Feedback.DECLINED,
                            )
                        }
                        load(token = token)
                    }

                    is DomainResult.Failure -> {
                        updateState {
                            copy(
                                processingBusinessId = null,
                                failure = result.error.toAccountError(),
                            )
                        }
                        // Misma recarga que al aceptar, conservando el error visible.
                        load(clearFailure = false, token = token)
                    }
                }
            },
            onFailure = failure@ { error ->
                if (!isCurrentIdentity(token)) return@failure
                updateState {
                    copy(processingBusinessId = null, failure = error.toAccountError())
                }
            },
        )
    }

    private fun setActiveAfterAccept() {
        val identityToken = activeIdentityToken ?: return
        val snapshot = uiState.value
        val accepted = snapshot.acceptedMembership ?: return
        if (snapshot.isDemoMode || identityToken.identity.isDemoMode) return
        val localBusinessId = snapshot.activeBusinessId ?: return
        val expectedUid = identityToken.identity.activeUid ?: return
        if (
            identityToken.identity.activeBusinessId != localBusinessId ||
            !isCurrentIdentity(identityToken) || snapshot.isBusy || setActiveJob?.isActive == true ||
            acceptJob?.isActive == true || declineJob?.isActive == true
        ) {
            return
        }
        val capturedLink = CloudBusinessLink(
            localBusinessId = localBusinessId,
            cloudBusinessId = accepted.businessId,
            role = accepted.role,
        )
        val token = InvitationsLinkIdentityToken(
            identityToken = identityToken,
            localIdentityEpoch = membershipRepository.currentLocalBusinessIdentityEpoch(),
        )
        setActiveJob = executeIo(
            before = {
                if (isCurrentLinkIdentity(token)) {
                    updateState { copy(isSettingActive = true, failure = null) }
                }
            },
            operation = {
                ensureCurrentIdentity(token.identityToken)
                withContext(NonCancellable) {
                    if (!isCurrentLinkIdentity(token)) {
                        throw CancellationException("Identidad de link reemplazada")
                    }
                    val result = when (
                        val binding = bindCloudBusinessLink(localBusinessId, accepted.businessId)
                    ) {
                        is DomainResult.Failure -> binding
                        is DomainResult.Success ->
                            membershipRepository.setActiveCloudBusiness(
                                expectedUid = expectedUid,
                                expectedLocalIdentityEpoch = token.localIdentityEpoch,
                                link = capturedLink,
                            )
                    }
                    if (!isCurrentLinkIdentity(token) && result is DomainResult.Success) {
                        membershipRepository.clearActiveCloudBusinessIfOwned(
                            expectedUid = expectedUid,
                            expectedReceipt = result.value,
                        )
                    } else if (result is DomainResult.Success) {
                        // El receipt ya es durable. Completar ambos wakes aquí evita que la
                        // cancelación prompt al volver a Main omita el canal de privacidad.
                        try {
                            purchaseBackupScheduler.enqueueBestEffort()
                        } catch (_: CancellationException) {
                            // El wake de privacidad sigue siendo independiente.
                        }
                        try {
                            purchaseBackupScheduler.enqueuePrivacyPurgeBestEffort()
                        } catch (_: CancellationException) {
                            // Startup cubre la ventana si WorkManager tampoco pudo escribir.
                        }
                    }
                    result
                }
            },
            onSuccess = success@ { result ->
                if (!isCurrentLinkIdentity(token)) {
                    if (isCurrentIdentity(token.identityToken)) {
                        updateState { copy(isSettingActive = false) }
                    }
                    return@success
                }
                when (result) {
                    is DomainResult.Success -> {
                        updateState {
                            copy(
                                isSettingActive = false,
                                acceptedMembership = null,
                                feedback = InvitationsContract.Feedback.LINK_UPDATED,
                            )
                        }
                    }

                    is DomainResult.Failure -> updateState {
                        copy(isSettingActive = false, failure = result.error.toAccountError())
                    }
                }
            },
            onFailure = failure@ { error ->
                if (!isCurrentLinkIdentity(token)) {
                    if (isCurrentIdentity(token.identityToken)) {
                        updateState { copy(isSettingActive = false) }
                    }
                    return@failure
                }
                updateState { copy(isSettingActive = false, failure = error.toAccountError()) }
            },
        )
    }

    private fun dismissAccepted() {
        val token = activeIdentityToken ?: return
        executeMain {
            if (isCurrentIdentity(token)) updateState { copy(acceptedMembership = null) }
        }
    }

    private fun cancelIdentityScopedJobs() {
        loadJob?.cancel()
        acceptJob?.cancel()
        declineJob?.cancel()
        setActiveJob?.cancel()
        loadJob = null
        acceptJob = null
        declineJob = null
        setActiveJob = null
    }

    private fun isCurrentIdentity(token: InvitationsIdentityToken): Boolean =
        activeIdentityToken == token

    private fun isCurrentLinkIdentity(token: InvitationsLinkIdentityToken): Boolean =
        isCurrentIdentity(token.identityToken) &&
            membershipRepository.currentLocalBusinessIdentityEpoch() == token.localIdentityEpoch

    private fun ensureCurrentIdentity(token: InvitationsIdentityToken) {
        if (!isCurrentIdentity(token)) {
            throw CancellationException("Identidad de invitaciones reemplazada")
        }
    }
}

private data class InvitationsIdentity(
    val activeUid: String?,
    val activeBusinessId: BusinessId?,
    val isDemoMode: Boolean,
)

private data class InvitationsIdentityToken(
    val generation: Long,
    val identity: InvitationsIdentity,
)

private data class InvitationsLinkIdentityToken(
    val identityToken: InvitationsIdentityToken,
    val localIdentityEpoch: Long,
)

private fun FacturaStockError.toAccountError(): AccountError =
    this as? AccountError ?: AccountError.Unexpected

private fun Throwable.toAccountError(): AccountError =
    (this as? AccountException)?.error ?: AccountError.Unexpected
