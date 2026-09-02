package com.facturastock.app.feature.sync

import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.AccountException
import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.error.FacturaStockError
import com.facturastock.app.domain.model.AccountSession
import com.facturastock.app.domain.model.OutboxOperationStatus
import com.facturastock.app.domain.model.RemotePurchaseDescription
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.AccountRepository
import com.facturastock.app.domain.repository.OutboxOperationView
import com.facturastock.app.domain.repository.PurchaseBackupOutboxRepository
import com.facturastock.app.domain.repository.RemoteLedgerRepository
import com.facturastock.app.domain.repository.RemoteSyncCacheRepository
import com.facturastock.app.domain.repository.SyncCursorRepository
import com.facturastock.app.domain.usecase.ObserveAppConfigurationUseCase
import com.facturastock.app.domain.usecase.ObservePurchasesUseCase
import com.facturastock.app.domain.usecase.PullRemoteChangesUseCase
import com.facturastock.app.domain.usecase.ReconcileRemoteLedgerUseCase
import com.facturastock.app.domain.usecase.RecordReconciliationReviewUseCase
import com.facturastock.app.domain.usecase.ResolveSyncConflictResult
import com.facturastock.app.domain.usecase.ResolveSyncConflictUseCase
import com.facturastock.app.domain.usecase.RetrySyncOperationResult
import com.facturastock.app.domain.usecase.RetrySyncOperationUseCase
import com.facturastock.app.domain.usecase.SyncConflictResolution
import com.facturastock.app.feature.common.UdfViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine

@HiltViewModel
class SyncViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val observeAppConfigurationUseCase: ObserveAppConfigurationUseCase,
    private val outboxRepository: PurchaseBackupOutboxRepository,
    private val cursorRepository: SyncCursorRepository,
    private val remoteLedgerRepository: RemoteLedgerRepository,
    private val remoteSyncCacheRepository: RemoteSyncCacheRepository,
    private val observePurchasesUseCase: ObservePurchasesUseCase,
    private val pullRemoteChangesUseCase: PullRemoteChangesUseCase,
    private val reconcileRemoteLedgerUseCase: ReconcileRemoteLedgerUseCase,
    private val resolveSyncConflictUseCase: ResolveSyncConflictUseCase,
    private val recordReconciliationReviewUseCase: RecordReconciliationReviewUseCase,
    private val retrySyncOperationUseCase: RetrySyncOperationUseCase,
    dispatcherProvider: DispatcherProvider,
) : UdfViewModel<SyncContract.State, SyncContract.Action, SyncContract.Effect>(
    initialState = SyncContract.State(),
    dispatcherProvider = dispatcherProvider,
) {
    private var sessionJob: Job? = null
    private var outboxJob: Job? = null
    private var cursorJob: Job? = null
    private var remoteDescriptionsJob: Job? = null
    private var purchasesJob: Job? = null
    private var pullJob: Job? = null
    private var compareJob: Job? = null
    private var resolveJob: Job? = null
    private var retryJob: Job? = null
    private var reviewJob: Job? = null
    private var identityGeneration: Long = 0
    @Volatile
    private var activeIdentityToken: SyncIdentityToken? = null
    private var cachedRemoteDescriptions: Map<String, RemotePurchaseDescription> = emptyMap()

    init {
        observeSession()
    }

    override fun onAction(action: SyncContract.Action) {
        when (action) {
            is SyncContract.Action.RetryFailedOperation -> retryFailedOperation(action.operation)

            is SyncContract.Action.KeepRemoteRequested -> executeMain {
                val snapshot = uiState.value
                if (
                    snapshot.workingOperationId == null &&
                    snapshot.containsCurrentOperation(action.operation)
                ) {
                    updateState { copy(keepRemoteCandidate = action.operation) }
                }
            }

            SyncContract.Action.KeepRemoteConfirmed -> {
                val operation = uiState.value.keepRemoteCandidate ?: return
                resolveConflict(operation, SyncConflictResolution.KEEP_REMOTE)
            }

            is SyncContract.Action.RetryConflictOperation ->
                resolveConflict(action.operation, SyncConflictResolution.RETRY)

            is SyncContract.Action.RemoteDescriptionRetry ->
                syncNow(restartRemoteDescriptions = true)

            SyncContract.Action.SyncNow -> syncNow()

            SyncContract.Action.CompareWithCloud -> compareWithCloud()

            SyncContract.Action.RecordReview -> recordReview()

            SyncContract.Action.DismissDialogs -> executeMain {
                updateState { copy(keepRemoteCandidate = null) }
            }

            SyncContract.Action.RetryInitialLoad -> observeSession()

            SyncContract.Action.BackSelected -> executeMain {
                emitEffect(SyncContract.Effect.Back)
            }
        }
    }

    private fun observeSession() {
        sessionJob?.cancel()
        sessionJob = executeMain {
            try {
                combine(
                    accountRepository.observeSession(),
                    observeAppConfigurationUseCase(),
                ) { session, config -> session to config }
                    .collectLatest { (session, config) ->
                        applyIdentitySnapshot(session, config)
                    }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                invalidateIdentityAfterLoadFailure()
            }
        }
    }

    private fun applyIdentitySnapshot(
        session: AccountSession,
        config: AppConfiguration,
    ) {
        val activeBusinessId = config.activeBusinessId
        val identity = SyncIdentity(session, activeBusinessId)
        val identityChanged = activeIdentityToken?.identity != identity
        val token = if (identityChanged) {
            identityGeneration += 1
            SyncIdentityToken(identityGeneration, identity).also { next ->
                // Publicar primero el token invalida incluso callbacks no cooperativos.
                activeIdentityToken = next
                cancelIdentityScopedJobs()
                cachedRemoteDescriptions = emptyMap()
            }
        } else {
            checkNotNull(activeIdentityToken)
        }
        val link = (session as? AccountSession.Active)?.link
            ?.takeIf { it.localBusinessId == activeBusinessId }

        if (identityChanged) {
            updateState {
                copy(
                    session = session,
                    activeBusinessId = activeBusinessId,
                    backupEnabled = config.backupEnabled,
                    documentBackupEnabled = config.documentBackupEnabled,
                    remoteLedgerAvailable = remoteLedgerRepository.available,
                    outbox = emptyList(),
                    purchases = emptyList(),
                    cursor = null,
                    remoteDescriptions = emptyMap(),
                    workingOperationId = null,
                    keepRemoteCandidate = null,
                    isPulling = false,
                    pullOutcome = null,
                    isComparing = false,
                    report = null,
                    isRecordingReview = false,
                    feedback = null,
                    failure = null,
                    initialLoadFailure = null,
                )
            }
        } else {
            updateState {
                copy(
                    backupEnabled = config.backupEnabled,
                    documentBackupEnabled = config.documentBackupEnabled,
                    remoteLedgerAvailable = remoteLedgerRepository.available,
                    initialLoadFailure = null,
                )
            }
        }

        restartTerminatedObservers(
            token = token,
            localBusinessId = link?.localBusinessId,
            cloudBusinessId = link?.cloudBusinessId,
        )
    }

    /** Un retry del loader también revive cualquiera de sus observadores hijos ya terminados. */
    private fun restartTerminatedObservers(
        token: SyncIdentityToken,
        localBusinessId: BusinessId?,
        cloudBusinessId: BusinessId?,
    ) {
        if (localBusinessId == null || cloudBusinessId == null) return
        if (outboxJob?.isActive != true) observeOutbox(localBusinessId, token)
        if (cursorJob?.isActive != true) observeCursor(cloudBusinessId, token)
        restartRemoteDescriptionsIfTerminated(cloudBusinessId, token)
        if (purchasesJob?.isActive != true) observePurchases(localBusinessId, token)
    }

    private fun restartRemoteDescriptionsIfTerminated(
        cloudBusinessId: BusinessId,
        token: SyncIdentityToken,
    ) {
        if (isCurrentIdentity(token) && remoteDescriptionsJob?.isActive != true) {
            observeRemoteDescriptions(cloudBusinessId, token)
        }
    }

    /**
     * Un retry explícito sustituye también un observer todavía activo pero bloqueado. Además
     * arma un único reintento al completarse esa nueva suscripción, cerrando la carrera en la
     * que Room falla justo mientras el pull está publicando su caché.
     */
    private fun restartRemoteDescriptionsForRetry(
        cloudBusinessId: BusinessId,
        token: SyncIdentityToken,
    ) {
        if (!isCurrentIdentity(token)) return
        observeRemoteDescriptions(cloudBusinessId, token)
        val recoveryAttempt = remoteDescriptionsJob ?: return
        recoveryAttempt.invokeOnCompletion {
            executeMain {
                if (
                    isCurrentIdentity(token) &&
                    remoteDescriptionsJob === recoveryAttempt &&
                    recoveryAttempt.isCompleted
                ) {
                    observeRemoteDescriptions(cloudBusinessId, token)
                }
            }
        }
    }

    private fun invalidateIdentityAfterLoadFailure() {
        activeIdentityToken = null
        identityGeneration += 1
        cancelIdentityScopedJobs()
        cachedRemoteDescriptions = emptyMap()
        updateState {
            copy(
                session = null,
                activeBusinessId = null,
                backupEnabled = false,
                documentBackupEnabled = false,
                outbox = emptyList(),
                purchases = emptyList(),
                cursor = null,
                remoteDescriptions = emptyMap(),
                workingOperationId = null,
                keepRemoteCandidate = null,
                isPulling = false,
                pullOutcome = null,
                isComparing = false,
                report = null,
                isRecordingReview = false,
                feedback = null,
                failure = null,
                initialLoadFailure = AccountError.Unexpected,
            )
        }
    }

    private fun cancelIdentityScopedJobs() {
        outboxJob?.cancel()
        cursorJob?.cancel()
        remoteDescriptionsJob?.cancel()
        purchasesJob?.cancel()
        pullJob?.cancel()
        compareJob?.cancel()
        resolveJob?.cancel()
        retryJob?.cancel()
        reviewJob?.cancel()
        outboxJob = null
        cursorJob = null
        remoteDescriptionsJob = null
        purchasesJob = null
        pullJob = null
        compareJob = null
        resolveJob = null
        retryJob = null
        reviewJob = null
    }

    /** Cola durable del negocio local activo; sin negocio activo la lista queda vacía. */
    private fun observeOutbox(
        businessId: BusinessId,
        token: SyncIdentityToken,
    ) {
        outboxJob?.cancel()
        outboxJob = executeMain {
            try {
                outboxRepository.observeOutboxOperations(businessId).collectLatest { operations ->
                    if (!isCurrentIdentity(token)) return@collectLatest
                    val scopedOperations = operations.filter { it.businessId == businessId }
                    updateState {
                        copy(
                            outbox = scopedOperations,
                            remoteDescriptions = descriptionsFor(scopedOperations),
                        )
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                if (isCurrentIdentity(token)) {
                    updateState { copy(outbox = emptyList(), failure = AccountError.Unexpected) }
                }
            }
        }
    }

    private fun observeRemoteDescriptions(
        cloudBusinessId: BusinessId,
        token: SyncIdentityToken,
    ) {
        remoteDescriptionsJob?.cancel()
        cachedRemoteDescriptions = emptyMap()
        remoteDescriptionsJob = executeMain {
            try {
                remoteSyncCacheRepository.observePurchaseDescriptions(cloudBusinessId)
                    .collectLatest { descriptions ->
                        if (!isCurrentIdentity(token)) return@collectLatest
                        cachedRemoteDescriptions = descriptions
                        updateState { copy(remoteDescriptions = descriptionsFor(outbox)) }
                    }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                if (isCurrentIdentity(token)) {
                    cachedRemoteDescriptions = emptyMap()
                    updateState { copy(remoteDescriptions = descriptionsFor(outbox)) }
                }
            }
        }
    }

    private fun observeCursor(
        cloudBusinessId: BusinessId,
        token: SyncIdentityToken,
    ) {
        cursorJob?.cancel()
        cursorJob = executeMain {
            try {
                cursorRepository.observeCursor(cloudBusinessId).collectLatest { cursor ->
                    if (isCurrentIdentity(token)) updateState { copy(cursor = cursor) }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                if (isCurrentIdentity(token)) updateState { copy(cursor = null) }
            }
        }
    }

    /** Las compras del negocio activo alimentan la mitad local de cada comparación. */
    private fun observePurchases(
        businessId: BusinessId,
        token: SyncIdentityToken,
    ) {
        purchasesJob?.cancel()
        purchasesJob = executeMain {
            try {
                observePurchasesUseCase.forBusiness(businessId).collectLatest { purchases ->
                    if (isCurrentIdentity(token)) updateState { copy(purchases = purchases) }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                if (isCurrentIdentity(token)) updateState { copy(purchases = emptyList()) }
            }
        }
    }

    private fun descriptionsFor(
        operations: List<OutboxOperationView>,
    ): Map<String, SyncContract.RemoteDescriptionState> {
        if (uiState.value.activeCloudLink == null) return emptyMap()
        return operations
            .asSequence()
            .filter { it.status == OutboxOperationStatus.CONFLICT }
            .mapNotNull { operation ->
                val remoteId = operation.conflictRemotePurchaseId ?: return@mapNotNull null
                operation.operationId to (
                    cachedRemoteDescriptions[remoteId]
                        ?.let { SyncContract.RemoteDescriptionState.Loaded(it) }
                        ?: SyncContract.RemoteDescriptionState.Unavailable
                    )
            }
            .toMap()
    }

    private fun retryFailedOperation(operation: OutboxOperationView) {
        val snapshot = uiState.value
        val token = activeIdentityToken ?: return
        if (!snapshot.containsCurrentOperation(operation) || !isCurrentIdentity(token)) return
        if (snapshot.workingOperationId != null || retryJob?.isActive == true) return
        if (operation.status != OutboxOperationStatus.FAILED) return
        val businessId = snapshot.activeBusinessId ?: return
        retryJob = executeIo(
            before = {
                if (isCurrentIdentity(token)) {
                    updateState {
                        copy(
                            workingOperationId = operation.operationId,
                            failure = null,
                            feedback = null,
                        )
                    }
                }
            },
            operation = {
                ensureCurrentIdentity(token)
                retrySyncOperationUseCase(businessId, operation)
            },
            onSuccess = success@ { result ->
                if (!isCurrentIdentity(token)) return@success
                updateState {
                    copy(
                        workingOperationId = null,
                        feedback = when (result) {
                            RetrySyncOperationResult.Requeued ->
                                SyncContract.Feedback.OPERATION_REQUEUED

                            RetrySyncOperationResult.NotRetryable,
                            RetrySyncOperationResult.NotFound,
                            -> null
                        },
                        failure = when (result) {
                            RetrySyncOperationResult.NotFound -> AccountError.NotFound
                            RetrySyncOperationResult.NotRetryable -> AccountError.Conflict
                            RetrySyncOperationResult.Requeued -> null
                        },
                    )
                }
            },
            onFailure = failure@ { error ->
                if (!isCurrentIdentity(token)) return@failure
                updateState {
                    copy(workingOperationId = null, failure = error.toAccountError())
                }
            },
        )
    }

    private fun resolveConflict(
        operation: OutboxOperationView,
        resolution: SyncConflictResolution,
    ) {
        val snapshot = uiState.value
        val token = activeIdentityToken ?: return
        val actorId = snapshot.activeSession?.uid ?: return
        val businessId = snapshot.activeBusinessId ?: return
        if (!snapshot.containsCurrentOperation(operation) || !isCurrentIdentity(token)) return
        if (snapshot.workingOperationId != null || resolveJob?.isActive == true) return
        resolveJob = executeIo(
            before = {
                if (isCurrentIdentity(token)) {
                    updateState {
                        copy(
                            workingOperationId = operation.operationId,
                            keepRemoteCandidate = null,
                            failure = null,
                            feedback = null,
                        )
                    }
                }
            },
            operation = {
                ensureCurrentIdentity(token)
                resolveSyncConflictUseCase(businessId, operation, resolution, actorId)
            },
            onSuccess = success@ { result ->
                if (!isCurrentIdentity(token)) return@success
                updateState {
                    copy(
                        workingOperationId = null,
                        feedback = when (result) {
                            ResolveSyncConflictResult.Resolved ->
                                SyncContract.Feedback.CONFLICT_RESOLVED

                            ResolveSyncConflictResult.Requeued ->
                                SyncContract.Feedback.OPERATION_REQUEUED

                            ResolveSyncConflictResult.NotInConflict,
                            ResolveSyncConflictResult.NotFound,
                            -> null
                        },
                        failure = when (result) {
                            ResolveSyncConflictResult.NotInConflict -> AccountError.Conflict
                            ResolveSyncConflictResult.NotFound -> AccountError.NotFound
                            else -> null
                        },
                    )
                }
            },
            onFailure = failure@ { error ->
                if (!isCurrentIdentity(token)) return@failure
                updateState {
                    copy(workingOperationId = null, failure = error.toAccountError())
                }
            },
        )
    }

    private fun syncNow(restartRemoteDescriptions: Boolean = false) {
        val snapshot = uiState.value
        val token = activeIdentityToken ?: return
        val cloudBusinessId = snapshot.cloudBusinessId ?: return
        val localBusinessId = snapshot.activeBusinessId ?: return
        if (!isCurrentIdentity(token)) return
        if (restartRemoteDescriptions) {
            restartRemoteDescriptionsForRetry(cloudBusinessId, token)
        }
        if (!snapshot.canPull || pullJob?.isActive == true) return
        pullJob = executeIo(
            before = {
                if (isCurrentIdentity(token)) {
                    updateState {
                        copy(isPulling = true, pullOutcome = null, failure = null, feedback = null)
                    }
                }
            },
            operation = {
                ensureCurrentIdentity(token)
                pullRemoteChangesUseCase(
                    localBusinessId = localBusinessId,
                    cloudBusinessId = cloudBusinessId,
                )
            },
            onSuccess = success@ { result ->
                if (!isCurrentIdentity(token)) return@success
                when (result) {
                    is DomainResult.Success -> updateState {
                        copy(isPulling = false, pullOutcome = result.value)
                    }

                    is DomainResult.Failure -> updateState {
                        copy(isPulling = false, failure = result.error.toAccountError())
                    }
                }
                if (restartRemoteDescriptions) {
                    restartRemoteDescriptionsIfTerminated(cloudBusinessId, token)
                }
            },
            onFailure = failure@ { error ->
                if (!isCurrentIdentity(token)) return@failure
                updateState { copy(isPulling = false, failure = error.toAccountError()) }
                if (restartRemoteDescriptions) {
                    restartRemoteDescriptionsIfTerminated(cloudBusinessId, token)
                }
            },
        )
    }

    private fun compareWithCloud() {
        val snapshot = uiState.value
        val token = activeIdentityToken ?: return
        val link = snapshot.activeCloudLink ?: return
        if (!snapshot.canCompare || compareJob?.isActive == true || !isCurrentIdentity(token)) return
        compareJob = executeIo(
            before = {
                if (isCurrentIdentity(token)) {
                    updateState {
                        copy(isComparing = true, report = null, failure = null, feedback = null)
                    }
                }
            },
            operation = {
                ensureCurrentIdentity(token)
                when (
                    val pulled = pullRemoteChangesUseCase(
                        localBusinessId = link.localBusinessId,
                        cloudBusinessId = link.cloudBusinessId,
                    )
                ) {
                    is DomainResult.Failure -> DomainResult.Failure(pulled.error)
                    is DomainResult.Success -> {
                        ensureCurrentIdentity(token)
                        reconcileRemoteLedgerUseCase(
                            localBusinessId = link.localBusinessId,
                            cloudBusinessId = link.cloudBusinessId,
                        )
                    }
                }
            },
            onSuccess = success@ { result ->
                if (!isCurrentIdentity(token)) return@success
                when (result) {
                    is DomainResult.Success -> updateState {
                        copy(isComparing = false, report = result.value)
                    }

                    is DomainResult.Failure -> updateState {
                        copy(isComparing = false, failure = result.error.toAccountError())
                    }
                }
            },
            onFailure = failure@ { error ->
                if (!isCurrentIdentity(token)) return@failure
                updateState { copy(isComparing = false, failure = error.toAccountError()) }
            },
        )
    }

    private fun recordReview() {
        val snapshot = uiState.value
        val token = activeIdentityToken ?: return
        val link = snapshot.activeCloudLink ?: return
        val report = snapshot.report ?: return
        if (report.businessId != link.localBusinessId) return
        if (!snapshot.canRecordReview || reviewJob?.isActive == true || !isCurrentIdentity(token)) {
            return
        }
        reviewJob = executeIo(
            before = {
                if (isCurrentIdentity(token)) {
                    updateState { copy(isRecordingReview = true, failure = null, feedback = null) }
                }
            },
            operation = {
                ensureCurrentIdentity(token)
                recordReconciliationReviewUseCase(report)
            },
            onSuccess = success@ {
                if (!isCurrentIdentity(token)) return@success
                updateState {
                    copy(
                        isRecordingReview = false,
                        feedback = SyncContract.Feedback.REVIEW_RECORDED,
                    )
                }
            },
            onFailure = failure@ { error ->
                if (!isCurrentIdentity(token)) return@failure
                updateState {
                    copy(isRecordingReview = false, failure = error.toAccountError())
                }
            },
        )
    }

    private fun isCurrentIdentity(token: SyncIdentityToken): Boolean =
        activeIdentityToken == token

    private fun ensureCurrentIdentity(token: SyncIdentityToken) {
        if (!isCurrentIdentity(token)) throw CancellationException("Identidad de sync reemplazada")
    }
}

private data class SyncIdentity(
    val session: AccountSession,
    val localBusinessId: BusinessId?,
)

private data class SyncIdentityToken(
    val generation: Long,
    val identity: SyncIdentity,
)

private fun SyncContract.State.containsCurrentOperation(operation: OutboxOperationView): Boolean =
    activeSession != null &&
        operation.businessId == activeBusinessId &&
        outbox.any { current -> current == operation }

private fun FacturaStockError.toAccountError(): AccountError =
    this as? AccountError ?: AccountError.Unexpected

private fun Throwable.toAccountError(): AccountError =
    (this as? AccountException)?.error ?: AccountError.Unexpected
