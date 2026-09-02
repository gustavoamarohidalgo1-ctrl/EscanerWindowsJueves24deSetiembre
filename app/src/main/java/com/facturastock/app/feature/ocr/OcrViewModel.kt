package com.facturastock.app.feature.ocr

import androidx.lifecycle.SavedStateHandle
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.OcrRunId
import com.facturastock.app.domain.repository.EnterManualInvoiceReviewResult
import com.facturastock.app.domain.usecase.EnterManualInvoiceReviewUseCase
import com.facturastock.app.domain.usecase.ImportScannedInvoiceProductsResult
import com.facturastock.app.domain.usecase.ImportScannedInvoiceProductsUseCase
import com.facturastock.app.domain.usecase.InvoiceOcrStage
import com.facturastock.app.domain.usecase.ObserveInvoiceDraftUseCase
import com.facturastock.app.domain.usecase.ParseInvoiceUseCase
import com.facturastock.app.domain.usecase.RetryDraftOcrUseCase
import com.facturastock.app.domain.usecase.RunInvoiceOcrUseCase
import com.facturastock.app.domain.usecase.ScannedInvoiceProductImportError
import com.facturastock.app.feature.common.RouteArgumentKeys
import com.facturastock.app.feature.common.UdfViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class OcrViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val runInvoiceOcrUseCase: RunInvoiceOcrUseCase,
    private val parseInvoiceUseCase: ParseInvoiceUseCase,
    private val importScannedInvoiceProductsUseCase: ImportScannedInvoiceProductsUseCase,
    private val enterManualInvoiceReviewUseCase: EnterManualInvoiceReviewUseCase,
    private val observeInvoiceDraftUseCase: ObserveInvoiceDraftUseCase,
    private val retryDraftOcrUseCase: RetryDraftOcrUseCase,
    private val appClock: AppClock,
    dispatcherProvider: DispatcherProvider,
) : UdfViewModel<OcrContract.State, OcrContract.Action, OcrContract.Effect>(
    initialState = ocrInitialState(savedStateHandle),
    dispatcherProvider = dispatcherProvider,
) {
    private var ocrJob: Job? = null
    private var cancellationJob: Job? = null
    private var manualEntryJob: Job? = null
    private var interruptedRecoveryJob: Job? = null
    private var firstDraftEmission = true
    private var resumeWhenCaptured = false
    private var interruptedRunId: OcrRunId? = null
    private var lastObservedDraft: InvoiceDraft? = null
    private var terminalEffectEmitted = false

    init {
        uiState.value.draftId?.let(::observeDraft)
    }

    override fun onAction(action: OcrContract.Action) {
        when (action) {
            OcrContract.Action.Start -> {
                if (terminalEffectEmitted) return
                when (uiState.value.failure) {
                    OcrContract.Failure.INTERRUPTED -> Unit
                    OcrContract.Failure.INVALID_DRAFT_ID -> executeMain {
                        closeInvalidRouteOnce()
                    }
                    else -> startOcr()
                }
            }
            OcrContract.Action.Retry -> {
                when (uiState.value.failure) {
                    OcrContract.Failure.INTERRUPTED -> recoverInterruptedOcr()
                    OcrContract.Failure.RECOGNITION_FAILED,
                    OcrContract.Failure.PARSING_FAILED,
                    OcrContract.Failure.NO_PRODUCTS_FOUND,
                    OcrContract.Failure.PRODUCT_SAVE_FAILED,
                    -> startOcr()
                    OcrContract.Failure.INVALID_DRAFT_ID,
                    null,
                    -> Unit
                }
            }

            OcrContract.Action.Cancel -> cancelOcr()
            OcrContract.Action.EnterManually -> enterManually()
            OcrContract.Action.BackSelected -> {
                if (
                    !uiState.value.isRunning &&
                    !uiState.value.isCancelling &&
                    !uiState.value.isEnteringManually &&
                    !uiState.value.isRecoveringInterruptedOcr
                ) {
                    executeMain { emitEffect(OcrContract.Effect.Back) }
                }
            }
        }
    }

    private fun startOcr() {
        // La UI no reemplaza una ejecución activa: su token debe terminar o limpiarse primero.
        if (
            terminalEffectEmitted ||
            ocrJob?.isActive == true ||
            cancellationJob?.isActive == true ||
            manualEntryJob?.isActive == true ||
            interruptedRecoveryJob?.isActive == true
        ) {
            return
        }
        val draftId = uiState.value.draftId
        if (draftId == null) {
            executeMain {
                closeInvalidRouteOnce()
            }
            return
        }

        ocrJob = executeIo(
            before = {
                updateState {
                    copy(
                        stage = InvoiceOcrStage.PREPARING,
                        isRunning = true,
                        isSavingProducts = false,
                        isCancelling = false,
                        isEnteringManually = false,
                        isRecoveringInterruptedOcr = false,
                        completedPageCount = 0,
                        startedAt = appClock.now(),
                        failure = null,
                        manualEntryFailed = false,
                    )
                }
            },
            operation = {
                val ocrResult = runInvoiceOcrUseCase(draftId) { stage ->
                    withContext(dispatcherProvider.main) {
                        updateState {
                            if (isRunning && !isCancelling) copy(stage = stage) else this
                        }
                    }
                }
                val parsedInvoice = try {
                    parseInvoiceUseCase(draftId)
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    throw InvoiceParsingStageException(failure)
                }
                withContext(dispatcherProvider.main) {
                    updateState {
                        if (isRunning && !isCancelling) {
                            copy(stage = null, isSavingProducts = true)
                        } else {
                            this
                        }
                    }
                }
                OcrProductImportOutcome(
                    pageCount = ocrResult.pageCount,
                    result = importScannedInvoiceProductsUseCase(draftId, parsedInvoice),
                )
            },
            onSuccess = { outcome ->
                ocrJob = null
                when (val result = outcome.result) {
                    is ImportScannedInvoiceProductsResult.Success -> {
                        updateState {
                            copy(
                                isRunning = false,
                                isSavingProducts = false,
                                isCancelling = false,
                                isEnteringManually = false,
                                isRecoveringInterruptedOcr = false,
                                completedPageCount = outcome.pageCount,
                                failure = null,
                                manualEntryFailed = false,
                            )
                        }
                        terminalEffectEmitted = true
                        emitEffect(
                            OcrContract.Effect.OpenProducts(
                                createdCount = result.counts.importedCount,
                                existingCount = result.counts.alreadyExistingCount,
                                skippedCount = result.counts.duplicateLineCount +
                                    result.counts.skippedLineCount,
                            ),
                        )
                    }

                    is ImportScannedInvoiceProductsResult.Failure -> {
                        updateState {
                            copy(
                                stage = null,
                                isRunning = false,
                                isSavingProducts = false,
                                isCancelling = false,
                                isEnteringManually = false,
                                isRecoveringInterruptedOcr = false,
                                completedPageCount = 0,
                                failure = result.error.toUiFailure(),
                                manualEntryFailed = false,
                            )
                        }
                    }
                }
            },
            onFailure = { failure ->
                ocrJob = null
                updateState {
                    copy(
                        stage = null,
                        isRunning = false,
                        isSavingProducts = false,
                        isCancelling = false,
                        isEnteringManually = false,
                        isRecoveringInterruptedOcr = false,
                        completedPageCount = 0,
                        failure = if (failure is InvoiceParsingStageException) {
                            OcrContract.Failure.PARSING_FAILED
                        } else {
                            OcrContract.Failure.RECOGNITION_FAILED
                        },
                        manualEntryFailed = false,
                    )
                }
            },
        )
    }

    /**
     * Room decide si la ruta puede empezar. Tras recrear el proceso, un token persistido no
     * pertenece a esta VM: se presenta como interrumpido y solo una acción explícita puede
     * limpiarlo por CAS. La emisión CAPTURED posterior dispara exactamente un nuevo intento.
     */
    private fun observeDraft(draftId: DraftId) {
        executeMain {
            observeInvoiceDraftUseCase(draftId).collect { draft ->
                lastObservedDraft = draft
                val isFirstEmission = firstDraftEmission
                firstDraftEmission = false

                if (draft == null) {
                    if (terminalEffectEmitted) return@collect
                    // El importador elimina el borrador, la foto y el OCR únicamente después de
                    // publicar el lote del catálogo. Esa emisión de Room es parte del éxito y la
                    // navegación final la decide el resultado del mismo job.
                    if (uiState.value.isSavingProducts) return@collect
                    ocrJob?.cancel()
                    updateState {
                        copy(
                            stage = null,
                            isRunning = false,
                            isSavingProducts = false,
                            failure = OcrContract.Failure.INVALID_DRAFT_ID,
                        )
                    }
                    closeInvalidRouteOnce()
                    return@collect
                }

                if (draft.status == DraftStatus.OCR_PROCESSING) {
                    if (
                        !resumeWhenCaptured &&
                        ocrJob?.isActive != true &&
                        cancellationJob?.isActive != true &&
                        interruptedRecoveryJob?.isActive != true
                    ) {
                        presentInterruptedRun(draft)
                    }
                    return@collect
                }

                if (resumeWhenCaptured && draft.isCapturedWithoutRun()) {
                    resumeRecoveredOcrIfReady()
                    return@collect
                }

                if (isFirstEmission) {
                    when (draft.status) {
                        DraftStatus.CAPTURED,
                        DraftStatus.ERROR,
                        DraftStatus.OCR_READY,
                        -> startOcr()

                        DraftStatus.NEEDS_REVIEW -> startOcr()

                        else -> {
                            updateState { copy(failure = OcrContract.Failure.INVALID_DRAFT_ID) }
                            closeInvalidRouteOnce()
                        }
                    }
                }
            }
        }
    }

    private suspend fun closeInvalidRouteOnce() {
        if (terminalEffectEmitted) return
        terminalEffectEmitted = true
        emitEffect(OcrContract.Effect.CloseInvalidRoute)
    }

    private fun presentInterruptedRun(draft: InvoiceDraft) {
        resumeWhenCaptured = true
        interruptedRunId = draft.activeOcrRunId
        updateState {
            copy(
                stage = null,
                isRunning = false,
                isSavingProducts = false,
                isCancelling = false,
                isRecoveringInterruptedOcr = false,
                completedPageCount = 0,
                failure = OcrContract.Failure.INTERRUPTED,
                manualEntryFailed = false,
            )
        }
    }

    private fun recoverInterruptedOcr() {
        if (interruptedRecoveryJob?.isActive == true || !resumeWhenCaptured) return
        val draftId = uiState.value.draftId ?: return
        val expectedRunId = interruptedRunId
        interruptedRecoveryJob = executeIo(
            before = {
                updateState { copy(isRecoveringInterruptedOcr = true) }
            },
            operation = { retryDraftOcrUseCase(draftId, expectedRunId) },
            onSuccess = {
                interruptedRecoveryJob = null
                updateState { copy(isRecoveringInterruptedOcr = false) }
                // La emisión Room puede llegar antes o después del retorno del CAS.
                resumeRecoveredOcrIfReady()
            },
            onFailure = {
                interruptedRecoveryJob = null
                updateState {
                    copy(
                        isRecoveringInterruptedOcr = false,
                        failure = OcrContract.Failure.INTERRUPTED,
                    )
                }
            },
        )
    }

    private fun resumeRecoveredOcrIfReady() {
        if (
            !resumeWhenCaptured ||
            interruptedRecoveryJob?.isActive == true ||
            !lastObservedDraft.isCapturedWithoutRun()
        ) {
            return
        }
        resumeWhenCaptured = false
        interruptedRunId = null
        startOcr()
    }

    private fun cancelOcr() {
        if (
            !uiState.value.isRunning ||
            uiState.value.isCancelling ||
            cancellationJob?.isActive == true
        ) {
            return
        }
        val runningJob = ocrJob
        // La señal se envía sin esperar al siguiente turno de Main: así un éxito ya listo no
        // puede publicar navegación mientras el job de cancelación espera el cleanup del token.
        runningJob?.cancel()
        cancellationJob = executeMain {
            updateState {
                copy(
                    isRunning = false,
                    isSavingProducts = false,
                    isCancelling = true,
                    failure = null,
                )
            }
            runningJob?.cancelAndJoin()
            if (ocrJob === runningJob) ocrJob = null
            updateState {
                copy(
                    stage = null,
                    isSavingProducts = false,
                    isCancelling = false,
                    completedPageCount = 0,
                    failure = null,
                )
            }
            emitEffect(OcrContract.Effect.Cancelled)
        }
    }

    private fun enterManually() {
        val state = uiState.value
        if (
            state.draftId == null ||
            state.failure !in setOf(
                OcrContract.Failure.RECOGNITION_FAILED,
                OcrContract.Failure.PARSING_FAILED,
            ) ||
            state.isCancelling ||
            state.isEnteringManually ||
            state.isRecoveringInterruptedOcr ||
            manualEntryJob?.isActive == true
        ) {
            return
        }
        val draftId = state.draftId
        val runningJob = ocrJob
        manualEntryJob = executeIo(
            before = {
                updateState {
                    copy(
                        isEnteringManually = true,
                        manualEntryFailed = false,
                    )
                }
            },
            operation = {
                val result = enterManualInvoiceReviewUseCase(draftId)
                if (result == EnterManualInvoiceReviewResult.ENTERED) {
                    // La transición Room ya invalidó el token. Cancelar después evita que el
                    // cleanup del job pueda degradar el nuevo NEEDS_REVIEW a CAPTURED.
                    runningJob?.cancelAndJoin()
                }
                result
            },
            onSuccess = { result ->
                updateState {
                    copy(
                        isEnteringManually = false,
                        manualEntryFailed = result != EnterManualInvoiceReviewResult.ENTERED,
                    )
                }
                if (result == EnterManualInvoiceReviewResult.ENTERED) {
                    terminalEffectEmitted = true
                    emitEffect(OcrContract.Effect.OpenManualReview(draftId))
                }
            },
            onFailure = {
                updateState {
                    copy(
                        isEnteringManually = false,
                        manualEntryFailed = true,
                    )
                }
            },
        )
    }
}

private data class OcrProductImportOutcome(
    val pageCount: Int,
    val result: ImportScannedInvoiceProductsResult,
)

/** Marca solo la etapa fallida sin exponer texto OCR ni detalles sensibles a la UI. */
private class InvoiceParsingStageException(cause: Throwable) : Exception(cause)

private fun ScannedInvoiceProductImportError.toUiFailure(): OcrContract.Failure = when (this) {
    ScannedInvoiceProductImportError.NO_ELIGIBLE_PRODUCTS ->
        OcrContract.Failure.NO_PRODUCTS_FOUND

    ScannedInvoiceProductImportError.PARSED_DRAFT_MISMATCH,
    ScannedInvoiceProductImportError.NO_ACTIVE_BUSINESS,
    ScannedInvoiceProductImportError.DRAFT_NOT_FOUND,
    ScannedInvoiceProductImportError.DRAFT_NOT_READY,
    ScannedInvoiceProductImportError.BUSINESS_MISMATCH,
    ScannedInvoiceProductImportError.UNIT_UNAVAILABLE,
    ScannedInvoiceProductImportError.CATALOG_CONFLICT,
    ScannedInvoiceProductImportError.INSUFFICIENT_STORAGE,
    ScannedInvoiceProductImportError.STORAGE_UNAVAILABLE,
    ScannedInvoiceProductImportError.CLEANUP_FAILED,
    -> OcrContract.Failure.PRODUCT_SAVE_FAILED
}

private fun InvoiceDraft?.isCapturedWithoutRun(): Boolean =
    this?.status == DraftStatus.CAPTURED &&
        activeOcrRunId == null &&
        confirmedPurchaseId == null

private fun ocrInitialState(savedStateHandle: SavedStateHandle): OcrContract.State {
    val draftId = DraftId.parse(
        savedStateHandle.get<String>(RouteArgumentKeys.DRAFT_ID),
    )
    return OcrContract.State(
        draftId = draftId,
        failure = if (draftId == null) {
            OcrContract.Failure.INVALID_DRAFT_ID
        } else {
            null
        },
    )
}
