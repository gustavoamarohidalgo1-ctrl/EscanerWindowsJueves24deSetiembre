package com.facturastock.app.feature.preparation

import androidx.lifecycle.SavedStateHandle
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.PurchaseDuplicateKind
import com.facturastock.app.domain.model.PurchaseDuplicateOverride
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.repository.ConfirmPurchaseResult
import com.facturastock.app.domain.usecase.AuthorizeDuplicateOverrideResult
import com.facturastock.app.domain.usecase.AuthorizePurchaseDuplicateOverrideUseCase
import com.facturastock.app.domain.usecase.CheckPurchaseDuplicateUseCase
import com.facturastock.app.domain.usecase.ConfirmPurchaseUseCase
import com.facturastock.app.domain.usecase.ObserveInvoiceDraftUseCase
import com.facturastock.app.feature.common.RouteArgumentKeys
import com.facturastock.app.feature.common.UdfViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex

@HiltViewModel
class PreparationViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val confirmPurchaseUseCase: ConfirmPurchaseUseCase,
    private val checkPurchaseDuplicateUseCase: CheckPurchaseDuplicateUseCase,
    private val authorizeDuplicateOverrideUseCase: AuthorizePurchaseDuplicateOverrideUseCase,
    private val observeInvoiceDraftUseCase: ObserveInvoiceDraftUseCase,
    dispatcherProvider: DispatcherProvider,
) : UdfViewModel<PreparationContract.State, PreparationContract.Action, PreparationContract.Effect>(
    initialState = preparationInitialState(savedStateHandle),
    dispatcherProvider = dispatcherProvider,
) {
    private val confirmationMutex = Mutex()
    private var pendingDuplicateOverride: PurchaseDuplicateOverride? = null
    private var draftObservationJob: Job? = null
    private var initialDraftObserved = false
    private var observedDraftIsReady = false
    private var startRequested = false
    private var startConsumed = false
    private var duplicateCheckInFlight = false
    private var terminalEffectEmitted = false

    init {
        uiState.value.draftId?.let(::observeDraft)
    }

    override fun onAction(action: PreparationContract.Action) {
        when (action) {
            PreparationContract.Action.Start -> start()

            PreparationContract.Action.Retry -> retry()

            PreparationContract.Action.Confirm -> confirm()

            PreparationContract.Action.OpenExistingSelected -> executeMain {
                uiState.value.duplicateAssessment?.match?.purchase?.purchaseId?.let { purchaseId ->
                    emitEffect(PreparationContract.Effect.OpenExistingPurchase(purchaseId))
                }
            }

            PreparationContract.Action.RequestOverride -> executeMain {
                val state = uiState.value
                if (
                    !state.isConfirming &&
                    state.confirmedPurchaseId == null &&
                    state.duplicateAssessment?.match?.kind ==
                    PurchaseDuplicateKind.EXACT
                ) {
                    updateState {
                        copy(showOverrideDialog = true, failure = null)
                    }
                }
            }

            is PreparationContract.Action.OverrideReasonChanged -> executeMain {
                updateState {
                    copy(
                        overrideReason = action.value.take(MAX_OVERRIDE_REASON_LENGTH),
                        failure = if (failure == PreparationContract.Failure.OVERRIDE_REASON_REQUIRED) {
                            null
                        } else {
                            failure
                        },
                    )
                }
            }

            PreparationContract.Action.ConfirmOverride -> authorizeOverride()

            PreparationContract.Action.DismissOverride -> executeMain {
                pendingDuplicateOverride = null
                updateState { copy(showOverrideDialog = false, overrideReason = "", failure = null) }
            }

            PreparationContract.Action.BackSelected -> executeMain {
                if (!terminalEffectEmitted) emitEffect(PreparationContract.Effect.Back)
            }
        }
    }

    /**
     * Room resuelve primero el estado durable. Así, si el proceso murió después del commit y
     * antes de navegar, un `Start` restaurado nunca vuelve a ejecutar el preflight ni el commit.
     */
    private fun start() {
        if (terminalEffectEmitted || startConsumed) return
        if (uiState.value.draftId == null || uiState.value.expectedPreparedLogicalHash == null) {
            executeMain { closeInvalidRouteOnce() }
            return
        }
        startRequested = true
        resumeStartWhenDraftIsReady()
    }

    private fun observeDraft(draftId: DraftId) {
        if (draftObservationJob?.isActive == true || terminalEffectEmitted) return
        draftObservationJob = executeMain {
            try {
                observeInvoiceDraftUseCase(draftId).collect(::onDraftChanged)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                draftObservationJob = null
                if (!terminalEffectEmitted) {
                    initialDraftObserved = false
                    observedDraftIsReady = false
                    updateState {
                        copy(
                            isCheckingDuplicates = false,
                            isConfirming = false,
                            failure = PreparationContract.Failure.DUPLICATE_CHECK_FAILED,
                        )
                    }
                }
            }
        }
    }

    private suspend fun onDraftChanged(draft: InvoiceDraft?) {
        if (terminalEffectEmitted) return
        initialDraftObserved = true
        observedDraftIsReady = false
        when {
            // El vínculo durable tiene prioridad incluso al leer datos de una versión anterior.
            draft?.confirmedPurchaseId != null -> openPostedPurchase(draft.confirmedPurchaseId)

            draft == null ||
                draft.status != DraftStatus.READY_TO_POST ||
                draft.activeOcrRunId != null -> {
                pendingDuplicateOverride = null
                updateState {
                    copy(
                        isCheckingDuplicates = false,
                        isConfirming = false,
                        duplicateAssessment = null,
                        showOverrideDialog = false,
                        confirmationBlockers = emptySet(),
                        failure = PreparationContract.Failure.INVALID_DRAFT_ID,
                    )
                }
                closeInvalidRouteOnce()
            }

            else -> {
                observedDraftIsReady = true
                resumeStartWhenDraftIsReady()
            }
        }
    }

    private fun resumeStartWhenDraftIsReady() {
        if (
            terminalEffectEmitted ||
            !startRequested ||
            startConsumed ||
            !initialDraftObserved ||
            !observedDraftIsReady
        ) {
            return
        }
        startConsumed = true
        checkDuplicates()
    }

    private fun checkDuplicates() {
        if (
            terminalEffectEmitted ||
            !initialDraftObserved ||
            !observedDraftIsReady ||
            duplicateCheckInFlight
        ) {
            return
        }
        pendingDuplicateOverride = null
        val draftId = uiState.value.draftId
        val expectedHash = uiState.value.expectedPreparedLogicalHash
        if (draftId == null || expectedHash == null) {
            executeMain { closeInvalidRouteOnce() }
            return
        }
        duplicateCheckInFlight = true
        executeIo(
            before = {
                updateState {
                    copy(
                        isCheckingDuplicates = true,
                        duplicateAssessment = null,
                        confirmationBlockers = emptySet(),
                        failure = null,
                    )
                }
            },
            operation = { checkPurchaseDuplicateUseCase(draftId) },
            onSuccess = { assessment ->
                duplicateCheckInFlight = false
                if (terminalEffectEmitted || uiState.value.confirmedPurchaseId != null) {
                    return@executeIo
                }
                if (assessment.probe.preparedLogicalHash != expectedHash) {
                    updateState {
                        copy(
                            isCheckingDuplicates = false,
                            duplicateAssessment = null,
                            confirmationBlockers = emptySet(),
                            failure = PreparationContract.Failure.PREPARED_PURCHASE_CHANGED,
                        )
                    }
                } else {
                    updateState {
                        copy(
                            isCheckingDuplicates = false,
                            duplicateAssessment = assessment,
                            confirmationBlockers = emptySet(),
                            failure = null,
                        )
                    }
                }
            },
            onFailure = {
                duplicateCheckInFlight = false
                if (terminalEffectEmitted || uiState.value.confirmedPurchaseId != null) {
                    return@executeIo
                }
                updateState {
                    copy(
                        isCheckingDuplicates = false,
                        confirmationBlockers = emptySet(),
                        failure = PreparationContract.Failure.DUPLICATE_CHECK_FAILED,
                    )
                }
            },
        )
    }

    private fun confirm() {
        val state = uiState.value
        if (
            terminalEffectEmitted ||
            !observedDraftIsReady ||
            state.isConfirming ||
            state.confirmedPurchaseId != null
        ) return
        val draftId = state.draftId
        val expectedHash = state.expectedPreparedLogicalHash
        if (draftId == null || expectedHash == null) {
            executeMain {
                closeInvalidRouteOnce()
            }
            return
        }
        val assessment = state.duplicateAssessment
        if (assessment == null) {
            checkDuplicates()
            return
        }
        if (assessment.kind == PurchaseDuplicateKind.EXACT) {
            executeMain { updateState { copy(showOverrideDialog = true) } }
            return
        }

        runConfirmation(draftId, expectedHash)
    }

    private fun retry() {
        val state = uiState.value
        if (terminalEffectEmitted || state.isConfirming || state.confirmedPurchaseId != null) return
        if (!initialDraftObserved) {
            startRequested = true
            state.draftId?.let(::observeDraft)
            return
        }
        if (
            state.failure == PreparationContract.Failure.RETRYABLE_CONFLICT ||
            state.failure == PreparationContract.Failure.PREPARATION_FAILED ||
            state.failure == PreparationContract.Failure.STORAGE_FULL
        ) {
            val draftId = state.draftId
            val expectedHash = state.expectedPreparedLogicalHash
            if (draftId != null && expectedHash != null) {
                runConfirmation(draftId, expectedHash, pendingDuplicateOverride)
            }
        } else {
            checkDuplicates()
        }
    }

    private fun authorizeOverride() {
        val state = uiState.value
        if (
            terminalEffectEmitted ||
            !observedDraftIsReady ||
            state.isConfirming ||
            state.confirmedPurchaseId != null ||
            !confirmationMutex.tryLock()
        ) return
        val draftId = state.draftId ?: run {
            confirmationMutex.unlock()
            return
        }
        val expectedHash = state.expectedPreparedLogicalHash ?: run {
            confirmationMutex.unlock()
            return
        }
        val existingPurchaseId = state.duplicateAssessment?.match?.purchase?.purchaseId ?: run {
            confirmationMutex.unlock()
            return
        }
        pendingDuplicateOverride = null
        executeIo(
            before = {
                updateState {
                    copy(
                        isConfirming = true,
                        confirmationBlockers = emptySet(),
                        failure = null,
                    )
                }
            },
            operation = {
                try {
                    authorizeDuplicateOverrideUseCase(
                        draftId = draftId,
                        expectedPreparedLogicalHash = expectedHash,
                        expectedPurchaseId = existingPurchaseId,
                        reason = state.overrideReason,
                    )
                } finally {
                    confirmationMutex.unlock()
                }
            },
            onSuccess = { result ->
                if (terminalEffectEmitted || uiState.value.confirmedPurchaseId != null) {
                    return@executeIo
                }
                when (result) {
                    is AuthorizeDuplicateOverrideResult.Authorized -> {
                        pendingDuplicateOverride = result.override
                        updateState {
                            copy(
                                isConfirming = false,
                                showOverrideDialog = false,
                                overrideReason = "",
                                confirmationBlockers = emptySet(),
                                failure = null,
                            )
                        }
                        runConfirmation(draftId, expectedHash, result.override)
                    }
                    AuthorizeDuplicateOverrideResult.InvalidReason -> updateState {
                        copy(
                            isConfirming = false,
                            confirmationBlockers = emptySet(),
                            failure = PreparationContract.Failure.OVERRIDE_REASON_REQUIRED,
                        )
                    }
                    AuthorizeDuplicateOverrideResult.Unauthorized -> updateState {
                        copy(
                            isConfirming = false,
                            showOverrideDialog = false,
                            confirmationBlockers = emptySet(),
                            failure = PreparationContract.Failure.OVERRIDE_NOT_AUTHORIZED,
                        )
                    }
                    AuthorizeDuplicateOverrideResult.StaleMatch -> {
                        updateState {
                            copy(
                                isConfirming = false,
                                showOverrideDialog = false,
                                duplicateAssessment = null,
                                confirmationBlockers = emptySet(),
                                failure = PreparationContract.Failure.DUPLICATE_CHANGED,
                            )
                        }
                    }
                }
            },
            onFailure = {
                if (terminalEffectEmitted || uiState.value.confirmedPurchaseId != null) {
                    return@executeIo
                }
                updateState {
                    copy(
                        isConfirming = false,
                        confirmationBlockers = emptySet(),
                        failure = PreparationContract.Failure.OVERRIDE_FAILED,
                    )
                }
            },
        )
    }

    private fun runConfirmation(
        draftId: DraftId,
        expectedPreparedLogicalHash: String,
        duplicateOverride: PurchaseDuplicateOverride? = null,
    ) {
        if (
            terminalEffectEmitted ||
            !observedDraftIsReady ||
            uiState.value.confirmedPurchaseId != null ||
            uiState.value.isConfirming ||
            !confirmationMutex.tryLock()
        ) {
            return
        }
        executeIo(
            before = {
                updateState {
                    copy(
                        isConfirming = true,
                        confirmationBlockers = emptySet(),
                        failure = null,
                    )
                }
            },
            operation = {
                try {
                    confirmPurchaseUseCase(
                        draftId = draftId,
                        expectedPreparedLogicalHash = expectedPreparedLogicalHash,
                        duplicateOverride = duplicateOverride,
                    )
                } finally {
                    confirmationMutex.unlock()
                }
            },
            onSuccess = { result ->
                if (terminalEffectEmitted) return@executeIo
                when (result) {
                    is ConfirmPurchaseResult.Posted -> {
                        pendingDuplicateOverride = null
                        openPostedPurchase(result.purchaseId)
                    }
                    is ConfirmPurchaseResult.AlreadyPosted -> {
                        pendingDuplicateOverride = null
                        openPostedPurchase(result.purchaseId)
                    }
                    is ConfirmPurchaseResult.ExactDuplicate -> {
                        pendingDuplicateOverride = null
                        updateState {
                            copy(
                                isConfirming = false,
                                confirmationBlockers = emptySet(),
                                failure = null,
                            )
                        }
                        emitEffect(
                            PreparationContract.Effect.OpenExistingPurchase(
                                result.existingPurchaseId,
                            ),
                        )
                        // La consulta vuelve a poblar las señales si el usuario regresa a la ruta.
                        checkDuplicates()
                    }
                    is ConfirmPurchaseResult.Blocked -> {
                        pendingDuplicateOverride = null
                        updateState {
                            copy(
                                isConfirming = false,
                                confirmationBlockers = result.reasons,
                                failure = PreparationContract.Failure.CONFIRMATION_BLOCKED,
                            )
                        }
                    }
                    ConfirmPurchaseResult.PreparedChanged -> {
                        pendingDuplicateOverride = null
                        updateState {
                            copy(
                                isConfirming = false,
                                confirmationBlockers = emptySet(),
                                failure = PreparationContract.Failure.PREPARED_PURCHASE_CHANGED,
                            )
                        }
                    }
                    ConfirmPurchaseResult.RetryableConflict -> {
                        updateState {
                            copy(
                                isConfirming = false,
                                confirmationBlockers = emptySet(),
                                failure = PreparationContract.Failure.RETRYABLE_CONFLICT,
                            )
                        }
                    }
                }
            },
            onFailure = { failure ->
                if (terminalEffectEmitted) return@executeIo
                updateState {
                    copy(
                        isConfirming = false,
                        confirmationBlockers = emptySet(),
                        failure = if (failure.isInsufficientStorage()) {
                            PreparationContract.Failure.STORAGE_FULL
                        } else {
                            PreparationContract.Failure.PREPARATION_FAILED
                        },
                    )
                }
            },
        )
    }

    private suspend fun openPostedPurchase(purchaseId: PurchaseId) {
        if (terminalEffectEmitted) return
        terminalEffectEmitted = true
        observedDraftIsReady = false
        updateState {
            copy(
                isCheckingDuplicates = false,
                isConfirming = false,
                duplicateAssessment = null,
                showOverrideDialog = false,
                overrideReason = "",
                confirmationBlockers = emptySet(),
                confirmedPurchaseId = purchaseId,
                failure = null,
            )
        }
        emitEffect(PreparationContract.Effect.OpenPurchase(purchaseId))
    }

    private suspend fun closeInvalidRouteOnce() {
        if (terminalEffectEmitted) return
        terminalEffectEmitted = true
        emitEffect(PreparationContract.Effect.CloseInvalidRoute)
    }

    private companion object {
        const val MAX_OVERRIDE_REASON_LENGTH = 500
    }
}

private fun preparationInitialState(
    savedStateHandle: SavedStateHandle,
): PreparationContract.State {
    val draftId = DraftId.parse(
        savedStateHandle.get<String>(RouteArgumentKeys.DRAFT_ID),
    )
    val expectedHash = savedStateHandle
        .get<String>(RouteArgumentKeys.EXPECTED_PREPARED_HASH)
        ?.takeIf(PREPARED_HASH::matches)
    val routeIsValid = draftId != null && expectedHash != null
    return PreparationContract.State(
        draftId = draftId,
        expectedPreparedLogicalHash = expectedHash,
        // Evita mostrar fugazmente “sin coincidencias” antes del preflight inicial.
        isCheckingDuplicates = routeIsValid,
        failure = if (!routeIsValid) {
            PreparationContract.Failure.INVALID_DRAFT_ID
        } else {
            null
        },
    )
}

private val PREPARED_HASH = Regex("[0-9a-f]{64}")

private fun Throwable.isInsufficientStorage(): Boolean =
    this is StorageException && error == StorageError.InsufficientSpace
