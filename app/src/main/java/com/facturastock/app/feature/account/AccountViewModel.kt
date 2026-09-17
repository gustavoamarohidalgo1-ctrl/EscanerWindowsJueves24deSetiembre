package com.facturastock.app.feature.account

import androidx.lifecycle.SavedStateHandle
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.AccountException
import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.model.AccountSession
import com.facturastock.app.domain.model.CloudBusinessLink
import com.facturastock.app.domain.model.CloudBusinessLinkReceipt
import com.facturastock.app.domain.model.CloudMembership
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
class AccountViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val accountRepository: AccountRepository,
    private val membershipRepository: BusinessMembershipRepository,
    private val observeAppConfigurationUseCase: ObserveAppConfigurationUseCase,
    private val purchaseBackupScheduler: PurchaseBackupScheduler,
    private val bindCloudBusinessLink: BindCloudBusinessLinkUseCase,
    dispatcherProvider: DispatcherProvider,
) : UdfViewModel<AccountContract.State, AccountContract.Action, AccountContract.Effect>(
    initialState = AccountContract.State(
        accountDeletionAvailable = accountRepository.accountDeletionAvailable,
        email = savedStateHandle.get<String>(EMAIL_KEY).orEmpty()
            .take(AccountContract.EMAIL_MAX_LENGTH),
    ),
    dispatcherProvider = dispatcherProvider,
) {
    private var sessionJob: Job? = null
    private var membershipsJob: Job? = null
    private var membershipReconciliationJob: Job? = null
    private var linkChangeJob: Job? = null
    private var createBusinessJob: Job? = null
    private var accountDeletionRequestJob: Job? = null
    private var deletedAccountCleanupJob: Job? = null
    @Volatile
    private var membershipsIdentityUid: String? = null
    private var membershipsLoadedForUid: String? = null
    private var observedSessionUid: String? = null
    @Volatile
    private var activeBusinessIdentity: AccountBusinessIdentity? = null
    @Volatile
    private var businessIdentityGeneration: Long = 0L
    @Volatile
    private var membershipIdentityGeneration: Long = 0L
    private var pendingDeletionUid: String? = null
    private var deletionOutcomeInThisProcess: AccountContract.Feedback? = null
    @Volatile
    private var activeConfigurationIsDemo: Boolean = false

    init {
        observeSession()
    }

    override fun onAction(action: AccountContract.Action) {
        if (isDeletionCleanupExclusive() && action != AccountContract.Action.RetryDeletedAccountCleanup) {
            return
        }
        when (action) {
            is AccountContract.Action.EmailChanged -> executeMain {
                val value = action.value.take(AccountContract.EMAIL_MAX_LENGTH)
                savedStateHandle[EMAIL_KEY] = value
                updateState { copy(email = value, feedback = null) }
            }

            is AccountContract.Action.PasswordChanged -> executeMain {
                // La contraseña vive solo en memoria: nunca se persiste en SavedStateHandle.
                updateState { copy(password = action.value, feedback = null) }
            }

            AccountContract.Action.ToggleAuthMode -> executeMain {
                updateState {
                    copy(
                        isRegisterMode = !isRegisterMode,
                        failure = null,
                        feedback = null,
                    )
                }
            }

            AccountContract.Action.SubmitCredentials -> submitCredentials()

            AccountContract.Action.SendPasswordReset -> sendPasswordReset()

            AccountContract.Action.ResendVerification -> resendVerification()

            AccountContract.Action.RefreshVerification -> refreshVerification()

            is AccountContract.Action.MembershipSelected -> selectMembership(action.cloudBusinessId)

            is AccountContract.Action.BusinessDisplayNameChanged -> executeMain {
                updateState {
                    copy(
                        businessDisplayName = action.value.take(
                            AccountContract.BUSINESS_DISPLAY_NAME_MAX_LENGTH,
                        ),
                        feedback = null,
                    )
                }
            }

            AccountContract.Action.CreateBusiness -> createBusiness()

            AccountContract.Action.OpenMembers -> executeMain {
                emitEffect(AccountContract.Effect.OpenMembers)
            }

            AccountContract.Action.OpenInvitations -> executeMain {
                emitEffect(AccountContract.Effect.OpenInvitations)
            }

            AccountContract.Action.SignOutRequested -> executeMain {
                updateState { copy(showSignOutDialog = true) }
            }

            AccountContract.Action.SignOutConfirmed -> signOut()

            AccountContract.Action.DeleteAccountRequested -> executeMain {
                if (
                    uiState.value.accountDeletionAvailable &&
                    uiState.value.session.canRequestAccountDeletion() && !uiState.value.isWorking
                ) {
                    updateState {
                        copy(
                            showAccountDeletionDialog = true,
                            accountDeletionPassword = "",
                        )
                    }
                }
            }

            is AccountContract.Action.AccountDeletionPasswordChanged -> executeMain {
                updateState {
                    copy(
                        accountDeletionPassword = action.value,
                        failure = null,
                    )
                }
            }

            // Queue confirmation on the same dispatcher as password edits so a final text-field
            // event cannot be overtaken by the confirm button event.
            AccountContract.Action.DeleteAccountConfirmed -> executeMain { deleteAccount() }

            AccountContract.Action.RetryDeletedAccountCleanup -> finishDeletedAccountCleanup()

            AccountContract.Action.DismissDialogs -> executeMain {
                updateState {
                    copy(
                        showSignOutDialog = false,
                        showAccountDeletionDialog = false,
                        accountDeletionPassword = "",
                    )
                }
            }

            AccountContract.Action.Reconnect -> reconnect()

            AccountContract.Action.BackSelected -> executeMain {
                if (
                    accountDeletionRequestJob?.isActive != true &&
                    deletedAccountCleanupJob?.isActive != true
                ) {
                    emitEffect(AccountContract.Effect.Back)
                }
            }

            AccountContract.Action.RetryMemberships -> loadMemberships()

            AccountContract.Action.RetryInitialLoad -> observeSession()
        }
    }

    private fun observeSession() {
        sessionJob?.cancel()
        sessionJob = executeMain {
            try {
                combine(
                    accountRepository.observeSession(),
                    observeAppConfigurationUseCase(),
                    accountRepository.observePendingAccountDeletionUid(),
                ) { session, config, persistedDeletionUid ->
                    Triple(session, config, persistedDeletionUid)
                }.collectLatest { (session, configuration, persistedDeletionUid) ->
                        val activeBusinessId = configuration.activeBusinessId
                        activeConfigurationIsDemo = configuration.isDemoMode
                        if (persistedDeletionUid != null) {
                            pendingDeletionUid = persistedDeletionUid
                        } else if (
                            accountDeletionRequestJob?.isActive != true &&
                            deletedAccountCleanupJob?.isActive != true
                        ) {
                            pendingDeletionUid = null
                        }
                        val sessionUid = session.authenticatedUidOrNull()
                        if (sessionUid != observedSessionUid) {
                            val cancelledDeletionRequest =
                                accountDeletionRequestJob?.isActive == true
                            accountDeletionRequestJob?.cancel()
                            observedSessionUid = sessionUid
                            if (cancelledDeletionRequest) {
                                updateState {
                                    copy(
                                        isWorking = false,
                                        isDeletingAccount = false,
                                        accountDeletionFailed = false,
                                        showAccountDeletionDialog = false,
                                        accountDeletionPassword = "",
                                    )
                                }
                            }
                        }

                        val activeUid = (session as? AccountSession.Active)?.uid
                        if (activeUid != membershipsIdentityUid) {
                            membershipIdentityGeneration++
                            membershipsJob?.cancel()
                            createBusinessJob?.cancel()
                            membershipsIdentityUid = activeUid
                            membershipsLoadedForUid = null
                            updateState {
                                copy(
                                    memberships = emptyList(),
                                    isLoadingMemberships = false,
                                    isCreatingBusiness = false,
                                    changingLinkTo = null,
                                )
                            }
                        }

                        val nextBusinessIdentity = if (
                            session is AccountSession.Active && activeBusinessId != null
                        ) {
                            AccountBusinessIdentity(session.uid, activeBusinessId)
                        } else {
                            null
                        }
                        val businessIdentityChanged =
                            nextBusinessIdentity != activeBusinessIdentity
                        if (businessIdentityChanged) {
                            businessIdentityGeneration++
                            activeBusinessIdentity = nextBusinessIdentity
                            membershipReconciliationJob?.cancel()
                            linkChangeJob?.cancel()
                            updateState { copy(changingLinkTo = null) }
                        }

                        updateState {
                            copy(
                                session = session,
                                activeBusinessId = activeBusinessId,
                                isDemoMode = configuration.isDemoMode,
                                deletionPendingSignOut = pendingDeletionUid != null,
                                initialLoadFailure = null,
                                showExpiredSignInForm =
                                    showExpiredSignInForm && session is AccountSession.Expired,
                            )
                        }
                        if (
                            session is AccountSession.Active &&
                            membershipsLoadedForUid != session.uid &&
                            membershipsJob?.isActive != true
                        ) {
                            loadMemberships()
                        } else if (
                            (
                                businessIdentityChanged ||
                                    (
                                        session is AccountSession.Active &&
                                            session.link != null &&
                                            session.link.localBusinessId != activeBusinessId
                                        )
                            ) &&
                            session is AccountSession.Active &&
                            membershipsLoadedForUid == session.uid &&
                            !configuration.isDemoMode
                        ) {
                            reconcilePersistedLink(uiState.value.memberships, session.uid)
                        }

                        if (
                            pendingDeletionUid != null &&
                            accountDeletionRequestJob?.isActive != true &&
                            deletedAccountCleanupJob?.isActive != true
                        ) {
                            finishDeletedAccountCleanup()
                        }
                    }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                updateState { copy(initialLoadFailure = AccountError.Unexpected) }
            }
        }
    }

    private fun loadMemberships() {
        val expectedUid = (uiState.value.session as? AccountSession.Active)?.uid ?: return
        val identityToken = currentMembershipIdentityToken(expectedUid) ?: return
        membershipsJob?.cancel()
        membershipsJob = executeIo(
            before = { updateState { copy(isLoadingMemberships = true, failure = null) } },
            operation = { listMembershipsWithTokenRecovery(expectedUid) },
            onSuccess = { result ->
                if (!isMembershipIdentityCurrent(identityToken)) {
                    return@executeIo
                }
                when (result) {
                    is DomainResult.Success -> {
                        membershipsLoadedForUid = expectedUid
                        updateState {
                            copy(memberships = result.value, isLoadingMemberships = false)
                        }
                        if (!activeConfigurationIsDemo) {
                            reconcilePersistedLink(result.value, expectedUid)
                        }
                    }

                    is DomainResult.Failure -> updateState {
                        copy(
                            isLoadingMemberships = false,
                            failure = result.error.toAccountError(),
                        )
                    }
                }
            },
            onFailure = { error ->
                if (!isMembershipIdentityCurrent(identityToken)) {
                    return@executeIo
                }
                updateState {
                    copy(isLoadingMemberships = false, failure = error.toAccountError())
                }
            },
        )
    }

    /**
     * Repara una única vez el caso en que Auth ya conoce el correo verificado, pero Firestore
     * todavía recibió el JWT anterior. Se limita a esta lectura idempotente: ninguna escritura
     * de membresías se reenvía automáticamente ante un rechazo ambiguo.
     */
    private suspend fun listMembershipsWithTokenRecovery(
        expectedUid: String,
    ): DomainResult<List<CloudMembership>> {
        val firstAttempt = membershipRepository.listMyMemberships(expectedUid)
        if (
            firstAttempt !is DomainResult.Failure ||
            firstAttempt.error != AccountError.PermissionDenied
        ) {
            return firstAttempt
        }

        return when (val recovery = accountRepository.recoverSession()) {
            is DomainResult.Failure -> recovery
            is DomainResult.Success -> {
                val active = recovery.value as? AccountSession.Active
                if (active?.uid != expectedUid) {
                    DomainResult.Failure(AccountError.SessionExpired)
                } else {
                    membershipRepository.listMyMemberships(expectedUid)
                }
            }
        }
    }

    /**
     * Un resultado remoto exitoso reemplaza la caché de autorización. Sin conectividad no se
     * ejecuta este método y el último rol confirmado sigue disponible para el flujo offline.
     */
    private fun reconcilePersistedLink(
        memberships: List<CloudMembership>,
        expectedUid: String,
    ) {
        if (activeConfigurationIsDemo) return
        val session = uiState.value.session as? AccountSession.Active ?: return
        if (session.uid != expectedUid) return
        val currentLink = session.link ?: return
        val currentReceipt = CloudBusinessLinkReceipt(currentLink, session.linkRevision)
        val identityToken = currentBusinessIdentityToken(expectedUid) ?: return
        val membershipConfirmedLink = if (
            currentLink.localBusinessId == identityToken.identity.localBusinessId
        ) {
            memberships.singleOrNull { it.businessId == currentLink.cloudBusinessId }
                ?.let { membership -> currentLink.copy(role = membership.role) }
        } else {
            null
        }
        membershipReconciliationJob?.cancel()
        membershipReconciliationJob = executeIo(
            operation = {
                // Un binding Room ya fijado es la autoridad incluso si una revisión vieja de
                // DataStore anunciaba otro cloud ID. Se repara hacia X si aún hay membresía;
                // de lo contrario se retira el link engañoso sin tocar el binding.
                val fixedTarget = bindCloudBusinessLink.fixedTargetFor(
                    identityToken.identity.localBusinessId,
                )
                val confirmedLink = if (fixedTarget != null) {
                    memberships.singleOrNull { it.businessId == fixedTarget }
                        ?.let { membership ->
                            CloudBusinessLink(
                                localBusinessId = identityToken.identity.localBusinessId,
                                cloudBusinessId = fixedTarget,
                                role = membership.role,
                            )
                        }
                } else {
                    membershipConfirmedLink
                }
                val outcome = reconcileLinkForIdentity(
                    token = identityToken,
                    expectedReceipt = currentReceipt,
                    link = confirmedLink,
                )
                if (
                    confirmedLink != null && outcome is LinkMutationOutcome.Finished &&
                    outcome.result is DomainResult.Success
                ) {
                    wakeBackupChannelsAfterDurableIdentityChange(confirmedLink)
                }
                outcome
            },
            onSuccess = { outcome ->
                when (outcome) {
                    LinkMutationOutcome.Stale -> Unit
                    is LinkMutationOutcome.Finished -> {
                        val failure = outcome.result as? DomainResult.Failure
                        if (failure != null) {
                            updateState { copy(failure = failure.error.toAccountError()) }
                        }
                    }
                }
            },
            onFailure = { error ->
                if (!isBusinessIdentityCurrent(identityToken)) {
                    return@executeIo
                }
                updateState { copy(failure = error.toAccountError()) }
            },
        )
    }

    private fun currentBusinessIdentityToken(expectedUid: String): AccountBusinessIdentityToken? {
        val identity = activeBusinessIdentity ?: return null
        if (identity.uid != expectedUid) return null
        return AccountBusinessIdentityToken(
            identity = identity,
            generation = businessIdentityGeneration,
            localIdentityEpoch = membershipRepository.currentLocalBusinessIdentityEpoch(),
        )
    }

    private fun isBusinessIdentityCurrent(token: AccountBusinessIdentityToken): Boolean =
        isBusinessIdentityVisible(token) &&
            membershipRepository.currentLocalBusinessIdentityEpoch() == token.localIdentityEpoch

    private fun isBusinessIdentityVisible(token: AccountBusinessIdentityToken): Boolean =
        activeBusinessIdentity == token.identity &&
            businessIdentityGeneration == token.generation &&
            (uiState.value.session as? AccountSession.Active)?.uid == token.identity.uid &&
            uiState.value.activeBusinessId == token.identity.localBusinessId

    private fun currentMembershipIdentityToken(expectedUid: String): AccountIdentityToken? {
        if (membershipsIdentityUid != expectedUid) return null
        return AccountIdentityToken(expectedUid, membershipIdentityGeneration)
    }

    private fun isMembershipIdentityCurrent(token: AccountIdentityToken): Boolean =
        membershipsIdentityUid == token.uid &&
            membershipIdentityGeneration == token.generation &&
            (uiState.value.session as? AccountSession.Active)?.uid == token.uid

    private suspend fun setLinkForIdentity(
        token: AccountBusinessIdentityToken,
        link: CloudBusinessLink,
    ): LinkMutationOutcome = mutateLinkForIdentity(token, link, expectedReceipt = null, isCas = false)

    private suspend fun reconcileLinkForIdentity(
        token: AccountBusinessIdentityToken,
        expectedReceipt: CloudBusinessLinkReceipt,
        link: CloudBusinessLink?,
    ): LinkMutationOutcome = mutateLinkForIdentity(token, link, expectedReceipt, isCas = true)

    /**
     * Toda escritura incluye UID y el adaptador cloud la serializa globalmente. La sección no
     * cancelable garantiza que una mutación de A que terminó tras cambiar a B sea retirada con
     * CAS; si B ya escribió (incluso el mismo valor), su propietario impide borrarla.
     */
    private suspend fun mutateLinkForIdentity(
        token: AccountBusinessIdentityToken,
        link: CloudBusinessLink?,
        expectedReceipt: CloudBusinessLinkReceipt?,
        isCas: Boolean,
    ): LinkMutationOutcome {
        if (!isBusinessIdentityCurrent(token)) return LinkMutationOutcome.Stale
        return withContext(NonCancellable) {
            val mutation = if (isCas) {
                when (
                    val compared = membershipRepository.compareAndSetActiveCloudBusiness(
                        expectedUid = token.identity.uid,
                        expectedLocalIdentityEpoch = token.localIdentityEpoch,
                        expectedReceipt = checkNotNull(expectedReceipt),
                        newLink = link,
                    )
                ) {
                    is DomainResult.Success -> if (compared.value.applied) {
                        LinkWriteResult(
                            result = DomainResult.Success(Unit),
                            receipt = compared.value.receipt,
                        )
                    } else {
                        return@withContext LinkMutationOutcome.Stale
                    }

                    is DomainResult.Failure -> LinkWriteResult(
                        result = DomainResult.Failure(compared.error),
                        receipt = null,
                    )
                }
            } else {
                val selected = checkNotNull(link)
                when (
                    val written = membershipRepository.setActiveCloudBusiness(
                        expectedUid = token.identity.uid,
                        expectedLocalIdentityEpoch = token.localIdentityEpoch,
                        link = selected,
                    )
                ) {
                    is DomainResult.Success -> LinkWriteResult(
                        result = DomainResult.Success(Unit),
                        receipt = written.value,
                    )
                    is DomainResult.Failure -> LinkWriteResult(
                        result = DomainResult.Failure(written.error),
                        receipt = null,
                    )
                }
            }
            if (!isBusinessIdentityCurrent(token)) {
                if (mutation.result is DomainResult.Success && mutation.receipt != null) {
                    membershipRepository.clearActiveCloudBusinessIfOwned(
                        expectedUid = token.identity.uid,
                        expectedReceipt = mutation.receipt,
                    )
                }
                LinkMutationOutcome.Stale
            } else {
                LinkMutationOutcome.Finished(mutation.result)
            }
        }
    }

    private fun isDeletionCleanupExclusive(): Boolean =
        pendingDeletionUid != null || uiState.value.deletionPendingSignOut

    private fun submitCredentials() {
        val snapshot = uiState.value
        if (!snapshot.canSubmitCredentials) return
        val email = snapshot.email.trim()
        val password = snapshot.password
        executeIo(
            before = { updateState { copy(isWorking = true, failure = null, feedback = null) } },
            operation = {
                val result = if (snapshot.isRegisterMode) {
                    accountRepository.register(email, password)
                } else {
                    accountRepository.signIn(email, password)
                }
                if (result is DomainResult.Success) {
                    wakeBackupChannelsAfterDurableIdentityChange(result.value)
                }
                result
            },
            onSuccess = { result ->
                when (result) {
                    is DomainResult.Success -> {
                        updateState { copy(isWorking = false, password = "") }
                    }

                    is DomainResult.Failure -> updateState {
                        copy(isWorking = false, failure = result.error.toAccountError())
                    }
                }
            },
            onFailure = { error ->
                updateState { copy(isWorking = false, failure = error.toAccountError()) }
            },
        )
    }

    private fun sendPasswordReset() {
        val snapshot = uiState.value
        if (!snapshot.canSendPasswordReset) return
        executeIo(
            before = { updateState { copy(isWorking = true, failure = null, feedback = null) } },
            operation = { accountRepository.sendPasswordReset(snapshot.email.trim()) },
            onSuccess = { result ->
                when (result) {
                    is DomainResult.Success -> updateState {
                        copy(
                            isWorking = false,
                            feedback = AccountContract.Feedback.PASSWORD_RESET_SENT,
                        )
                    }

                    is DomainResult.Failure -> updateState {
                        copy(isWorking = false, failure = result.error.toAccountError())
                    }
                }
            },
            onFailure = { error ->
                updateState { copy(isWorking = false, failure = error.toAccountError()) }
            },
        )
    }

    private fun resendVerification() {
        if (uiState.value.isWorking) return
        executeIo(
            before = { updateState { copy(isWorking = true, failure = null, feedback = null) } },
            operation = { accountRepository.resendVerificationEmail() },
            onSuccess = { result ->
                when (result) {
                    is DomainResult.Success -> updateState {
                        copy(
                            isWorking = false,
                            feedback = AccountContract.Feedback.VERIFICATION_RESENT,
                        )
                    }

                    is DomainResult.Failure -> updateState {
                        copy(isWorking = false, failure = result.error.toAccountError())
                    }
                }
            },
            onFailure = { error ->
                updateState { copy(isWorking = false, failure = error.toAccountError()) }
            },
        )
    }

    private fun refreshVerification() {
        if (uiState.value.isWorking) return
        executeIo(
            before = { updateState { copy(isWorking = true, failure = null, feedback = null) } },
            operation = {
                val result = accountRepository.refreshVerification()
                if (result is DomainResult.Success) {
                    wakeBackupChannelsAfterDurableIdentityChange(result.value)
                }
                result
            },
            onSuccess = { result ->
                when (result) {
                    is DomainResult.Success -> {
                        updateState { copy(isWorking = false) }
                    }

                    is DomainResult.Failure -> updateState {
                        copy(isWorking = false, failure = result.error.toAccountError())
                    }
                }
            },
            onFailure = { error ->
                updateState { copy(isWorking = false, failure = error.toAccountError()) }
            },
        )
    }

    private fun selectMembership(cloudBusinessId: BusinessId) {
        val snapshot = uiState.value
        if (snapshot.isDemoMode || activeConfigurationIsDemo) return
        val session = snapshot.session as? AccountSession.Active ?: return
        val localBusinessId = snapshot.activeBusinessId ?: return
        val membership = snapshot.memberships.singleOrNull { it.businessId == cloudBusinessId }
            ?: return
        val currentLink = session.link
        if (
            currentLink?.cloudBusinessId == cloudBusinessId &&
            currentLink.localBusinessId == localBusinessId &&
            currentLink.role == membership.role
        ) return
        if (snapshot.changingLinkTo != null) return
        membershipReconciliationJob?.cancel()
        businessIdentityGeneration++
        val identityToken = currentBusinessIdentityToken(session.uid) ?: return
        val desiredLink = CloudBusinessLink(
            localBusinessId = localBusinessId,
            cloudBusinessId = cloudBusinessId,
            role = membership.role,
        )
        linkChangeJob = executeIo(
            before = {
                updateState { copy(changingLinkTo = cloudBusinessId, failure = null, feedback = null) }
            },
            operation = {
                val outcome = if (!isBusinessIdentityCurrent(identityToken)) {
                    LinkMutationOutcome.Stale
                } else {
                    when (val binding = bindCloudBusinessLink(localBusinessId, cloudBusinessId)) {
                        is DomainResult.Failure -> LinkMutationOutcome.Finished(binding)
                        is DomainResult.Success -> setLinkForIdentity(identityToken, desiredLink)
                    }
                }
                if (
                    outcome is LinkMutationOutcome.Finished &&
                    outcome.result is DomainResult.Success
                ) {
                    wakeBackupChannelsAfterDurableIdentityChange(desiredLink)
                }
                outcome
            },
            onSuccess = { outcome ->
                when (outcome) {
                    LinkMutationOutcome.Stale -> {
                        if (isBusinessIdentityVisible(identityToken)) {
                            updateState { copy(changingLinkTo = null) }
                        }
                    }
                    is LinkMutationOutcome.Finished -> when (val result = outcome.result) {
                        is DomainResult.Success -> {
                            updateState {
                                copy(
                                    changingLinkTo = null,
                                    feedback = AccountContract.Feedback.LINK_UPDATED,
                                )
                            }
                        }

                        is DomainResult.Failure -> updateState {
                            copy(changingLinkTo = null, failure = result.error.toAccountError())
                        }
                    }
                }
            },
            onFailure = { error ->
                if (!isBusinessIdentityVisible(identityToken)) {
                    return@executeIo
                }
                updateState {
                    copy(
                        changingLinkTo = null,
                        failure = if (isBusinessIdentityCurrent(identityToken)) {
                            error.toAccountError()
                        } else {
                            null
                        },
                    )
                }
            },
        )
    }

    private fun createBusiness() {
        val snapshot = uiState.value
        if (!snapshot.canCreateBusiness || createBusinessJob?.isActive == true) return
        val expectedUid = (snapshot.session as? AccountSession.Active)?.uid ?: return
        val identityToken = currentMembershipIdentityToken(expectedUid) ?: return
        createBusinessJob = executeIo(
            before = {
                updateState { copy(isCreatingBusiness = true, failure = null, feedback = null) }
            },
            operation = {
                membershipRepository.createBusiness(
                    expectedUid = expectedUid,
                    displayName = snapshot.businessDisplayName.trim(),
                )
            },
            onSuccess = { result ->
                if (!isMembershipIdentityCurrent(identityToken)) {
                    return@executeIo
                }
                when (result) {
                    is DomainResult.Success -> {
                        updateState {
                            copy(
                                isCreatingBusiness = false,
                                businessDisplayName = "",
                                feedback = AccountContract.Feedback.BUSINESS_CREATED,
                            )
                        }
                        loadMemberships()
                    }

                    is DomainResult.Failure -> updateState {
                        copy(isCreatingBusiness = false, failure = result.error.toAccountError())
                    }
                }
            },
            onFailure = { error ->
                if (!isMembershipIdentityCurrent(identityToken)) {
                    return@executeIo
                }
                updateState { copy(isCreatingBusiness = false, failure = error.toAccountError()) }
            },
        )
    }

    private fun signOut() {
        if (uiState.value.isWorking) return
        executeIo(
            before = {
                updateState {
                    copy(showSignOutDialog = false, isWorking = true, failure = null, feedback = null)
                }
            },
            operation = { accountRepository.signOut() },
            onSuccess = { result ->
                when (result) {
                    is DomainResult.Success -> updateState {
                        copy(
                            isWorking = false,
                            password = "",
                            memberships = emptyList(),
                            showExpiredSignInForm = false,
                        )
                    }

                    is DomainResult.Failure -> updateState {
                        copy(isWorking = false, failure = result.error.toAccountError())
                    }
                }
            },
            onFailure = { error ->
                updateState { copy(isWorking = false, failure = error.toAccountError()) }
            },
        )
    }

    /**
     * Solicita el borrado remoto una sola vez. El repositorio escribe un checkpoint durable
     * antes del primer await remoto; éxito, cancelación o respuesta ambigua continúan únicamente
     * con la limpieza local y nunca reenvían un comando cuyo resultado se desconoce.
     */
    private fun deleteAccount() {
        val snapshot = uiState.value
        if (
            !snapshot.accountDeletionAvailable ||
            !snapshot.session.canRequestAccountDeletion() ||
            !snapshot.canConfirmAccountDeletion ||
            snapshot.isWorking ||
            snapshot.deletionPendingSignOut ||
            accountDeletionRequestJob?.isActive == true
        ) {
            return
        }
        val expectedUid = snapshot.session.authenticatedUidOrNull() ?: return
        val reauthenticationPassword = snapshot.accountDeletionPassword
        deletionOutcomeInThisProcess = null
        accountDeletionRequestJob = executeIo(
            before = {
                updateState {
                    copy(
                        showAccountDeletionDialog = false,
                        accountDeletionPassword = "",
                        isWorking = true,
                        isDeletingAccount = true,
                        accountDeletionFailed = false,
                        failure = null,
                        feedback = null,
                    )
                }
            },
            operation = {
                val result = accountRepository.requestAccountDeletion(
                    expectedUid = expectedUid,
                    password = reauthenticationPassword,
                )
                val durablePendingUid = try {
                    accountRepository.pendingAccountDeletionUid()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Si la lectura del checkpoint falla después del request, limpiar es la
                    // decisión segura: el servidor pudo haber completado el borrado.
                    expectedUid
                }
                AccountDeletionAttempt(result, durablePendingUid)
            },
            onSuccess = { attempt ->
                when (val result = attempt.result) {
                    is DomainResult.Success -> {
                        deletionOutcomeInThisProcess = if (result.value.isPending) {
                            AccountContract.Feedback.ACCOUNT_DELETION_PENDING
                        } else {
                            AccountContract.Feedback.ACCOUNT_DELETED
                        }
                        pendingDeletionUid = expectedUid
                        updateState {
                            copy(
                                isWorking = false,
                                deletionPendingSignOut = true,
                            )
                        }
                        finishDeletedAccountCleanup()
                    }

                    is DomainResult.Failure -> {
                        val cleanupIsRequired = attempt.durablePendingUid == expectedUid
                        pendingDeletionUid = if (cleanupIsRequired) expectedUid else null
                        val sameAccountStillVisible =
                            uiState.value.session.authenticatedUidOrNull() == expectedUid
                        updateState {
                            copy(
                                isWorking = false,
                                isDeletingAccount = cleanupIsRequired,
                                accountDeletionFailed = true,
                                deletionPendingSignOut = cleanupIsRequired,
                                failure = if (sameAccountStillVisible) {
                                    result.error.toAccountError()
                                } else {
                                    null
                                },
                                showAccountDeletionDialog =
                                    sameAccountStillVisible &&
                                    result.error.toAccountError() ==
                                    AccountError.InvalidCredentials,
                            )
                        }
                        if (cleanupIsRequired) finishDeletedAccountCleanup()
                    }
                }
            },
            onFailure = { error ->
                pendingDeletionUid = expectedUid
                updateState {
                    copy(
                        isWorking = false,
                        isDeletingAccount = true,
                        accountDeletionFailed = true,
                        deletionPendingSignOut = true,
                        failure = error.toAccountError(),
                    )
                }
                finishDeletedAccountCleanup()
            },
        )
    }

    /** Reintento idempotente de la limpieza local: nunca vuelve a llamar al backend. */
    private fun finishDeletedAccountCleanup() {
        val snapshot = uiState.value
        val expectedUid = pendingDeletionUid ?: return
        if (
            !snapshot.deletionPendingSignOut ||
            snapshot.isWorking ||
            deletedAccountCleanupJob?.isActive == true
        ) {
            return
        }
        deletedAccountCleanupJob = executeIo(
            before = {
                updateState {
                    copy(
                        isWorking = true,
                        isDeletingAccount = true,
                        accountDeletionFailed = false,
                        failure = null,
                    )
                }
            },
            operation = { accountRepository.signOutIfCurrent(expectedUid) },
            onSuccess = { result ->
                if (pendingDeletionUid != expectedUid) return@executeIo
                when (result) {
                    is DomainResult.Success -> {
                        if (result.value) {
                            clearPendingDeletion(showFeedback = true)
                        } else {
                            // Otra cuenta ya tomó la sesión. El borrado remoto anterior terminó,
                            // pero nunca se debe cerrar ni alterar la cuenta nueva.
                            clearPendingDeletion(showFeedback = false)
                        }
                    }

                    is DomainResult.Failure -> updateState {
                        copy(
                            isWorking = false,
                            isDeletingAccount = false,
                            accountDeletionFailed = true,
                            failure = result.error.toAccountError(),
                        )
                    }
                }
            },
            onFailure = { error ->
                if (pendingDeletionUid != expectedUid) {
                    return@executeIo
                }
                updateState {
                    copy(
                        isWorking = false,
                        isDeletingAccount = false,
                        accountDeletionFailed = true,
                        failure = error.toAccountError(),
                    )
                }
            },
        )
    }

    private fun clearPendingDeletion(showFeedback: Boolean) {
        val deletionFeedback = if (showFeedback) {
            deletionOutcomeInThisProcess ?: AccountContract.Feedback.ACCOUNT_DELETION_UNCONFIRMED
        } else {
            null
        }
        val showConfirmedDeletionFeedback = deletionFeedback == AccountContract.Feedback.ACCOUNT_DELETED
        pendingDeletionUid = null
        deletionOutcomeInThisProcess = null
        updateState {
            copy(
                isWorking = false,
                isDeletingAccount = false,
                accountDeletionFailed = false,
                deletionPendingSignOut = false,
                password = if (showConfirmedDeletionFeedback) "" else password,
                memberships = if (showConfirmedDeletionFeedback) emptyList() else memberships,
                feedback = deletionFeedback,
                failure = null,
            )
        }
    }

    private fun reconnect() {
        if (uiState.value.isWorking) return
        val expiredEmail = (uiState.value.session as? AccountSession.Expired)?.email
        executeIo(
            before = { updateState { copy(isWorking = true, failure = null, feedback = null) } },
            operation = {
                val result = accountRepository.recoverSession()
                if (result is DomainResult.Success) {
                    wakeBackupChannelsAfterDurableIdentityChange(result.value)
                }
                result
            },
            onSuccess = { result ->
                when (result) {
                    is DomainResult.Success -> {
                        updateState {
                            copy(
                                isWorking = false,
                                showExpiredSignInForm = result.value !is AccountSession.Active,
                                email = if (email.isBlank()) expiredEmail.orEmpty() else email,
                            )
                        }
                    }

                    is DomainResult.Failure -> updateState {
                        copy(
                            isWorking = false,
                            showExpiredSignInForm = true,
                            email = if (email.isBlank()) expiredEmail.orEmpty() else email,
                            failure = result.error.toAccountError(),
                        )
                    }
                }
            },
            onFailure = { error ->
                updateState {
                    copy(
                        isWorking = false,
                        showExpiredSignInForm = true,
                        email = if (email.isBlank()) expiredEmail.orEmpty() else email,
                        failure = error.toAccountError(),
                    )
                }
            },
        )
    }

    /**
     * Tras autenticar o recuperar una sesión activa, despierta siempre privacidad. El canal
     * comercial requiere enlace, pero una purga pendiente se autoriza con las membresías remotas
     * y no puede quedar dormida solo porque la cuenta todavía no eligió un negocio activo.
     */
    private suspend fun wakeBackupChannelsAfterDurableIdentityChange(session: AccountSession) {
        val active = session as? AccountSession.Active ?: return
        wakeBackupChannelsAfterDurableIdentityChange(hasDurableLink = active.link != null)
    }

    /**
     * Se ejecuta junto al commit de sesión/link y no en el callback de UI. Una cancelación
     * inmediata del ViewModel no puede saltarse el segundo canal, y ambos intentos son
     * independientes para que un fallo de la cadena comercial no duerma una purga.
     */
    private suspend fun wakeBackupChannelsAfterDurableIdentityChange(link: CloudBusinessLink) {
        @Suppress("UNUSED_VARIABLE")
        val durableLink = link // Hace explícito que el wake corresponde al receipt ya persistido.
        wakeBackupChannelsAfterDurableIdentityChange(hasDurableLink = true)
    }

    private suspend fun wakeBackupChannelsAfterDurableIdentityChange(hasDurableLink: Boolean) {
        withContext(NonCancellable) {
            if (hasDurableLink) {
                try {
                    purchaseBackupScheduler.enqueueBestEffort()
                } catch (_: CancellationException) {
                    // Un scheduler defectuoso no debe impedir el intento de privacidad.
                }
            }
            try {
                purchaseBackupScheduler.enqueuePrivacyPurgeBestEffort()
            } catch (_: CancellationException) {
                // Startup vuelve a despertar la cola si WorkManager tampoco pudo persistirlo.
            }
        }
    }

    private companion object {
        const val EMAIL_KEY = "account.email"
    }
}

private data class AccountDeletionAttempt(
    val result: DomainResult<com.facturastock.app.domain.model.AccountDeletionSummary>,
    val durablePendingUid: String?,
)

private data class AccountBusinessIdentity(
    val uid: String,
    val localBusinessId: BusinessId,
)

private data class AccountBusinessIdentityToken(
    val identity: AccountBusinessIdentity,
    val generation: Long,
    val localIdentityEpoch: Long,
)

private data class AccountIdentityToken(
    val uid: String,
    val generation: Long,
)

private sealed interface LinkMutationOutcome {
    data object Stale : LinkMutationOutcome
    data class Finished(val result: DomainResult<Unit>) : LinkMutationOutcome
}

private data class LinkWriteResult(
    val result: DomainResult<Unit>,
    val receipt: CloudBusinessLinkReceipt?,
)

private fun com.facturastock.app.domain.error.FacturaStockError.toAccountError(): AccountError =
    this as? AccountError ?: AccountError.Unexpected

private fun Throwable.toAccountError(): AccountError =
    (this as? AccountException)?.error ?: AccountError.Unexpected

private fun AccountSession?.canRequestAccountDeletion(): Boolean =
    this is AccountSession.Active || this is AccountSession.AwaitingVerification

private fun AccountSession?.authenticatedUidOrNull(): String? = when (this) {
    is AccountSession.Active -> uid
    is AccountSession.AwaitingVerification -> uid
    else -> null
}
