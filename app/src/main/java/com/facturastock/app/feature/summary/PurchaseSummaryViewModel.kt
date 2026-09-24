package com.facturastock.app.feature.summary

import androidx.lifecycle.SavedStateHandle
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.PrepareBlocker
import com.facturastock.app.domain.model.PreparedPurchase
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.usecase.PrepareInspection
import com.facturastock.app.domain.usecase.PreparePurchaseResult
import com.facturastock.app.domain.usecase.PreparePurchaseUseCase
import com.facturastock.app.domain.usecase.ObserveInvoiceDraftUseCase
import com.facturastock.app.domain.usecase.ObservePreparedPurchaseUseCase
import com.facturastock.app.domain.usecase.ReopenPreparedPurchaseResult
import com.facturastock.app.feature.common.RouteArgumentKeys
import com.facturastock.app.feature.common.UdfViewModel
import com.facturastock.app.feature.summary.PurchaseSummaryContract.Action
import com.facturastock.app.feature.summary.PurchaseSummaryContract.BlockerItem
import com.facturastock.app.feature.summary.PurchaseSummaryContract.Effect
import com.facturastock.app.feature.summary.PurchaseSummaryContract.Failure
import com.facturastock.app.feature.summary.PurchaseSummaryContract.FinanceSummary
import com.facturastock.app.feature.summary.PurchaseSummaryContract.Mode
import com.facturastock.app.feature.summary.PurchaseSummaryContract.State
import com.facturastock.app.ui.format.formatForDisplay
import com.facturastock.app.ui.format.formatSignedForDisplay
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext

/**
 * Resumen y preparación de la compra. Lee la inspección agregada del dominio (nunca valida por
 * su cuenta), prepara la instantánea solo cuando no quedan bloqueos o el único pendiente es la
 * aceptación explícita del redondeo, y reabre la edición invalidando la instantánea.
 */
class PurchaseSummaryViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val preparePurchaseUseCase: PreparePurchaseUseCase,
    private val observeInvoiceDraft: ObserveInvoiceDraftUseCase,
    private val observePreparedPurchase: ObservePreparedPurchaseUseCase,
    dispatcherProvider: DispatcherProvider,
) : UdfViewModel<State, Action, Effect>(
    initialState = summaryInitialState(savedStateHandle),
    dispatcherProvider = dispatcherProvider,
) {
    private data class InspectionRequest(
        val debounce: Boolean,
        val showLoading: Boolean,
    )

    /**
     * Un único consumidor inspecciona el agregado. El canal conflado y collectLatest hacen que
     * una ráfaga de invalidaciones Room cancele una lectura vieja y termine renderizando solo la
     * versión durable más reciente.
     */
    private val inspectionRequests = Channel<InspectionRequest>(capacity = Channel.CONFLATED)
    private var inspectionWorkerJob: Job? = null
    private var draftObservationJob: Job? = null
    private var preparedObservationJob: Job? = null
    private var observationsStarted = false
    private var loadedOnce = false

    override fun onAction(action: Action) {
        when (action) {
            Action.Start -> start()
            Action.Retry -> retry()
            is Action.RoundingAcceptanceChanged -> updateRoundingAcceptance(action.accepted)
            is Action.AdjustmentReasonChanged -> updateAdjustmentReason(action.reason)
            Action.PrepareSelected -> prepare()
            Action.ReopenSelected -> reopen()
            Action.RegisterSelected -> register()
            Action.BackSelected -> executeMain { emitEffect(Effect.Back) }
        }
    }

    private fun start() {
        if (uiState.value.failure == Failure.INVALID_ROUTE) {
            executeMain { emitEffect(Effect.CloseInvalidRoute) }
            return
        }
        // El estado sobrevive a la recreación de la vista; Start no duplica colectores Room.
        if (observationsStarted) return
        observationsStarted = true
        startInspectionWorker()
        observePrepared()
        observeDraft()
    }

    private fun retry() {
        if (uiState.value.failure == Failure.INVALID_ROUTE) {
            executeMain { emitEffect(Effect.CloseInvalidRoute) }
            return
        }
        observationsStarted = true
        startInspectionWorker()
        observePrepared()
        observeDraft()
        requestInspection(debounce = false, showLoading = !loadedOnce)
    }

    /**
     * El borrador padre es el token de invalidación del agregado: los commits Room de cabecera,
     * líneas e imágenes tocan updatedAt dentro de la misma transacción. La primera emisión carga
     * sin demora; las siguientes se agrupan para no revalidar estados intermedios de una ráfaga.
     */
    private fun observeDraft() {
        val draftId = uiState.value.draftId ?: return
        if (draftObservationJob?.isActive == true) return
        draftObservationJob = executeMain {
            var firstEmission = true
            try {
                observeInvoiceDraft(draftId).collect {
                    requestInspection(
                        debounce = !firstEmission,
                        showLoading = firstEmission && !loadedOnce,
                    )
                    firstEmission = false
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                updateState { copy(isLoading = false, failure = Failure.LOAD_FAILED) }
            }
        }
    }

    private fun startInspectionWorker() {
        if (inspectionWorkerJob?.isActive == true) return
        inspectionWorkerJob = executeMain {
            inspectionRequests.receiveAsFlow().collectLatest { request ->
                if (request.debounce) delay(ROOM_REFRESH_DEBOUNCE_MILLIS)
                // Preparar/reabrir son escrituras atómicas. Esperar su resultado evita que una
                // invalidación propia libere isBusy antes de que finalice la acción.
                while (uiState.value.isBusy) delay(ACTION_COMPLETION_POLL_MILLIS)
                inspectAndRender(showLoading = request.showLoading)
            }
        }
    }

    private fun requestInspection(
        debounce: Boolean,
        showLoading: Boolean = false,
    ) {
        inspectionRequests.trySend(
            InspectionRequest(debounce = debounce, showLoading = showLoading),
        )
    }

    private suspend fun inspectAndRender(showLoading: Boolean) {
        val draftId = uiState.value.draftId ?: return
        try {
            if (showLoading) updateState { copy(isLoading = true, failure = null) }
            val inspection = withContext(dispatcherProvider.io) {
                preparePurchaseUseCase.inspect(draftId)
            }
            render(inspection)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // Una invalidación fallida no borra los totales/bloqueos locales ya visibles.
            updateState { copy(isLoading = false, failure = Failure.LOAD_FAILED) }
        }
    }

    private fun observePrepared() {
        val draftId = uiState.value.draftId ?: return
        if (preparedObservationJob?.isActive == true) return
        preparedObservationJob = executeMain {
            try {
                observePreparedPurchase(draftId).collect { purchase ->
                    when {
                        purchase != null && (
                            uiState.value.mode != Mode.PREPARED ||
                                uiState.value.prepared?.logicalHash != purchase.logicalHash
                            ) -> enterPreparedState(draftId, purchase)

                        purchase == null &&
                            uiState.value.mode == Mode.PREPARED &&
                            !uiState.value.isBusy -> requestInspection(debounce = false)
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // La inspección sigue siendo reintentable; no se borra el snapshot ya visible.
                if (uiState.value.prepared == null) {
                    updateState { copy(failure = Failure.LOAD_FAILED) }
                }
            }
        }
    }

    private suspend fun render(inspection: PrepareInspection?) {
        val draft = inspection?.draft
        val editable = draft != null &&
            draft.status == DraftStatus.NEEDS_REVIEW &&
            draft.activeOcrRunId == null &&
            draft.confirmedPurchaseId == null
        when {
            inspection == null || (inspection.prepared == null && !editable) -> {
                updateState { copy(isLoading = false, failure = Failure.INVALID_ROUTE) }
                emitEffect(Effect.CloseInvalidRoute)
            }

            inspection.prepared != null -> {
                val preparedDraft = requireNotNull(draft)
                loadedOnce = true
                enterPreparedState(
                    draftId = preparedDraft.draftId,
                    purchase = inspection.prepared,
                )
            }

            else -> {
                loadedOnce = true
                // roundingAccepted y adjustmentReason son entradas locales restaurables. Se
                // omiten del copy para que una revalidación Room no pise lo que se está tecleando.
                updateState {
                    copy(
                        isLoading = false,
                        mode = Mode.EDITING,
                        prepared = null,
                        blockers = inspection.blockers.toBlockerItems(),
                        roundingDifference = inspection.roundingDifference?.formatSignedForDisplay(),
                        finance = inspection.summary?.let { summary ->
                            FinanceSummary(
                                lineSum = summary.lineSum?.formatForDisplay().orEmpty(),
                                invoiceTotal = summary.invoiceTotal?.formatForDisplay().orEmpty(),
                                difference = summary.difference?.formatForDisplay().orEmpty(),
                                hasDifference = summary.difference?.let { difference ->
                                    difference.minorUnits != 0L
                                } == true,
                            )
                        },
                        failure = null,
                    )
                }
            }
        }
    }

    private fun updateRoundingAcceptance(accepted: Boolean) {
        if (uiState.value.isLoading || uiState.value.isBusy) return
        executeMain {
            savedStateHandle[ROUNDING_ACCEPTED_KEY] = accepted
            updateState { copy(roundingAccepted = accepted) }
        }
    }

    private fun updateAdjustmentReason(reason: String) {
        if (uiState.value.isLoading || uiState.value.isBusy) return
        val bounded = reason.take(ADJUSTMENT_REASON_MAX_INPUT)
        executeMain {
            savedStateHandle[ADJUSTMENT_REASON_KEY] = bounded
            updateState { copy(adjustmentReason = bounded) }
        }
    }

    private fun prepare() {
        val state = uiState.value
        val draftId = state.draftId ?: return
        if (!state.canPrepare) return
        executeIo(
            before = { updateState { copy(isBusy = true, failure = null) } },
            operation = {
                preparePurchaseUseCase(
                    draftId = draftId,
                    roundingAccepted = state.roundingAccepted,
                    adjustmentReason = state.adjustmentReason,
                )
            },
            onSuccess = { result ->
                when (result) {
                    is PreparePurchaseResult.Prepared ->
                        enterPreparedState(draftId = draftId, purchase = result.purchase)

                    is PreparePurchaseResult.AlreadyPrepared ->
                        enterPreparedState(draftId = draftId, purchase = result.purchase)

                    // Otro flujo pudo cambiar el borrador: se re-inspecciona y se muestra.
                    is PreparePurchaseResult.Blocked -> {
                        updateState { copy(isBusy = false) }
                        requestInspection(debounce = false)
                    }

                    PreparePurchaseResult.DraftNotReady -> updateState {
                        copy(isBusy = false, failure = Failure.ACTION_FAILED)
                    }
                }
            },
            onFailure = { error ->
                updateState { copy(isBusy = false, failure = error.toSummaryFailure()) }
            },
        )
    }

    /**
     * Publica primero el estado de solo lectura y después avisa a navegación. El efecto no se
     * emite hasta que el caso de uso haya confirmado READY_TO_POST y devuelto la instantánea.
     */
    private suspend fun enterPreparedState(
        draftId: DraftId,
        purchase: PreparedPurchase,
    ) {
        val shouldNotify = uiState.value.mode != Mode.PREPARED ||
            uiState.value.prepared?.logicalHash != purchase.logicalHash
        updateState {
            copy(
                isLoading = false,
                isBusy = false,
                mode = Mode.PREPARED,
                prepared = purchase,
                blockers = emptyList(),
                failure = null,
            )
        }
        if (shouldNotify) emitEffect(Effect.PreparedStateEntered(draftId))
    }

    private fun reopen() {
        val state = uiState.value
        val draftId = state.draftId ?: return
        if (state.mode != Mode.PREPARED || state.isBusy) return
        executeIo(
            before = { updateState { copy(isBusy = true, failure = null) } },
            operation = { preparePurchaseUseCase.reopen(draftId) },
            onSuccess = { result ->
                when (result) {
                    ReopenPreparedPurchaseResult.REOPENED -> {
                        updateState { copy(isBusy = false) }
                        emitEffect(Effect.OpenLineReview(draftId))
                    }

                    // La instantánea ya no existía: la vista se realinea con el dominio.
                    ReopenPreparedPurchaseResult.NOT_PREPARED -> {
                        updateState { copy(isBusy = false) }
                        requestInspection(debounce = false)
                    }
                }
            },
            onFailure = { error ->
                updateState { copy(isBusy = false, failure = error.toSummaryFailure()) }
            },
        )
    }

    private fun register() {
        val state = uiState.value
        val draftId = state.draftId ?: return
        val prepared = state.prepared ?: return
        if (state.mode != Mode.PREPARED || state.isBusy) return
        executeMain {
            emitEffect(
                Effect.OpenConfirmation(
                    draftId = draftId,
                    expectedPreparedLogicalHash = prepared.logicalHash,
                ),
            )
        }
    }

    private fun List<PrepareBlocker>.toBlockerItems(): List<BlockerItem> {
        val lineOrder = mapNotNull(PrepareBlocker::lineId).distinct()
        return map { blocker ->
            BlockerItem(
                code = blocker.code,
                linePosition = blocker.lineId?.let { lineOrder.indexOf(it) + 1 },
                detail = blocker.detail,
            )
        }
    }
}

private fun summaryInitialState(savedStateHandle: SavedStateHandle): State {
    val draftId = DraftId.parse(savedStateHandle.get<String>(RouteArgumentKeys.DRAFT_ID))
    return State(
        draftId = draftId,
        roundingAccepted = savedStateHandle.get<Boolean>(ROUNDING_ACCEPTED_KEY) ?: false,
        adjustmentReason = savedStateHandle.get<String>(ADJUSTMENT_REASON_KEY).orEmpty(),
        failure = if (draftId == null) {
            PurchaseSummaryContract.Failure.INVALID_ROUTE
        } else {
            null
        },
    )
}

private const val ROUNDING_ACCEPTED_KEY = "purchaseSummary.roundingAccepted"
private const val ADJUSTMENT_REASON_KEY = "purchaseSummary.adjustmentReason"
private const val ADJUSTMENT_REASON_MAX_INPUT = 500
private const val ROOM_REFRESH_DEBOUNCE_MILLIS = 100L
private const val ACTION_COMPLETION_POLL_MILLIS = 25L

private fun Throwable.toSummaryFailure(): Failure =
    if (this is StorageException && error == StorageError.InsufficientSpace) {
        Failure.STORAGE_FULL
    } else {
        Failure.ACTION_FAILED
    }
