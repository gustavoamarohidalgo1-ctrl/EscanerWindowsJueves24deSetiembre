package com.facturastock.app.feature.home

import androidx.lifecycle.SavedStateHandle
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.domain.model.DraftResumeTarget
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.OcrRunId
import com.facturastock.app.domain.model.resumeTarget
import com.facturastock.app.domain.usecase.DeleteDraftUseCase
import com.facturastock.app.domain.usecase.FindDraftFirstImageUseCase
import com.facturastock.app.domain.usecase.ObserveHomeDashboardUseCase
import com.facturastock.app.domain.usecase.RetryDraftOcrUseCase
import com.facturastock.app.domain.usecase.StartInvoiceDraftUseCase
import com.facturastock.app.feature.common.UdfViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val observeHomeDashboardUseCase: ObserveHomeDashboardUseCase,
    private val deleteDraftUseCase: DeleteDraftUseCase,
    private val retryDraftOcrUseCase: RetryDraftOcrUseCase,
    private val findDraftFirstImageUseCase: FindDraftFirstImageUseCase,
    private val startInvoiceDraftUseCase: StartInvoiceDraftUseCase,
    private val uuidGenerator: UuidGenerator,
    dispatcherProvider: DispatcherProvider,
) : UdfViewModel<HomeContract.State, HomeContract.Action, HomeContract.Effect>(
    initialState = restoredHomeState(savedStateHandle),
    dispatcherProvider = dispatcherProvider,
) {
    private var dashboardJob: Job? = null
    private var ocrRecoveryJob: Job? = null
    private var draftCreationJob: Job? = null

    init {
        observeDashboard()
    }

    override fun onAction(action: HomeContract.Action) {
        when (action) {
            HomeContract.Action.Retry -> observeDashboard()

            HomeContract.Action.ScanInvoiceSelected -> startDraft()

            HomeContract.Action.ProductsSelected -> executeMain {
                emitEffect(HomeContract.Effect.OpenProducts)
            }

            HomeContract.Action.PurchasesSelected -> executeMain {
                emitEffect(HomeContract.Effect.OpenPurchases)
            }

            HomeContract.Action.InventorySelected -> executeMain {
                emitEffect(HomeContract.Effect.OpenInventory)
            }

            is HomeContract.Action.DraftSelected -> onDraftSelected(action.draftId)

            is HomeContract.Action.DeleteRequested -> executeMain {
                savedStateHandle[PENDING_DELETION_KEY] = action.draftId.value
                updateState { copy(draftIdPendingDeletion = action.draftId) }
            }

            HomeContract.Action.DeleteConfirmed -> confirmDeletion()

            HomeContract.Action.DeleteDismissed -> executeMain {
                savedStateHandle.remove<String>(PENDING_DELETION_KEY)
                updateState { copy(draftIdPendingDeletion = null) }
            }

            HomeContract.Action.OcrResumeSelected -> recoverInterruptedOcr()

            HomeContract.Action.OcrRetrySelected -> retryOcr()

            HomeContract.Action.OcrChoiceDismissed -> executeMain {
                if (ocrRecoveryJob?.isActive == true) return@executeMain
                savedStateHandle.remove<String>(PENDING_OCR_CHOICE_KEY)
                savedStateHandle.remove<String>(PENDING_OCR_RUN_KEY)
                updateState {
                    copy(draftIdPendingOcrChoice = null, ocrRunIdPendingChoice = null)
                }
            }
        }
    }

    private fun startDraft() {
        if (draftCreationJob?.isActive == true) return
        val draftId = DraftId.from(uuidGenerator.newUuid())
        draftCreationJob = executeIo(
            before = { updateState { copy(isCreatingDraft = true) } },
            operation = { startInvoiceDraftUseCase(draftId) },
            onSuccess = { draft ->
                draftCreationJob = null
                updateState { copy(isCreatingDraft = false) }
                emitEffect(HomeContract.Effect.OpenDraftCamera(draft.draftId))
            },
            onFailure = {
                draftCreationJob = null
                updateState { copy(isCreatingDraft = false) }
                emitEffect(HomeContract.Effect.ShowDraftCreationFailure)
            },
        )
    }

    /**
     * Colección continua de una instantánea coherente del negocio activo. Un fallo fija
     * [HomeContract.Failure.LOAD_FAILED] conservando el contenido previo; [HomeContract.Action.Retry]
     * cancela la colección y la reinicia.
     */
    private fun observeDashboard() {
        dashboardJob?.cancel()
        dashboardJob = executeMain {
            updateState { copy(isLoading = true, failure = null) }
            try {
                observeHomeDashboardUseCase().collect { dashboard ->
                    updateState {
                        copy(
                            isLoading = false,
                            dashboard = dashboard,
                            drafts = dashboard.overview.drafts.recent.map { recent ->
                                HomeContract.HomeDraftItem(
                                    draft = recent.draft,
                                    supplierName = recent.supplierName,
                                )
                            },
                            failure = null,
                        )
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                updateState {
                    copy(isLoading = false, failure = HomeContract.Failure.LOAD_FAILED)
                }
            }
        }
    }

    private fun onDraftSelected(draftId: DraftId) {
        val item = uiState.value.drafts
            ?.firstOrNull { it.draft.draftId == draftId }
            ?: return
        executeMain {
            when (val target = item.draft.resumeTarget()) {
                is DraftResumeTarget.Source ->
                    emitEffect(HomeContract.Effect.OpenDraftCamera(target.draftId))

                is DraftResumeTarget.Preview -> {
                    // La vista previa necesita una página concreta; CAPTURED garantiza al
                    // menos una (eliminar la última revierte el borrador a CREATED). Si aun
                    // así faltara, se reanuda en el origen.
                    val firstImageId = findDraftFirstImageUseCase(target.draftId)
                    if (firstImageId != null) {
                        emitEffect(HomeContract.Effect.OpenPreview(target.draftId, firstImageId))
                    } else {
                        emitEffect(HomeContract.Effect.OpenDraftSource(target.draftId))
                    }
                }

                is DraftResumeTarget.Processing ->
                    emitEffect(HomeContract.Effect.OpenProcessing(target.draftId))

                is DraftResumeTarget.Header ->
                    emitEffect(HomeContract.Effect.OpenHeader(target.draftId))

                is DraftResumeTarget.Lines ->
                    emitEffect(HomeContract.Effect.OpenLines(target.draftId))

                is DraftResumeTarget.Summary ->
                    emitEffect(HomeContract.Effect.OpenSummary(target.draftId))

                is DraftResumeTarget.PurchaseDetail ->
                    emitEffect(HomeContract.Effect.OpenPurchaseDetail(target.purchaseId))

                // OCR interrumpido: no se navega; se abre el diálogo de reanudar o repetir.
                DraftResumeTarget.InterruptedOcr -> {
                    savedStateHandle[PENDING_OCR_CHOICE_KEY] = draftId.value
                    item.draft.activeOcrRunId?.let { runId ->
                        savedStateHandle[PENDING_OCR_RUN_KEY] = runId.value
                    } ?: savedStateHandle.remove<String>(PENDING_OCR_RUN_KEY)
                    updateState {
                        copy(
                            draftIdPendingOcrChoice = draftId,
                            ocrRunIdPendingChoice = item.draft.activeOcrRunId,
                        )
                    }
                }
            }
        }
    }

    private fun confirmDeletion() {
        val draftId = uiState.value.draftIdPendingDeletion ?: return
        executeIo(
            before = {
                savedStateHandle.remove<String>(PENDING_DELETION_KEY)
                updateState { copy(draftIdPendingDeletion = null) }
            },
            operation = { deleteDraftUseCase(draftId) },
            onSuccess = { removed ->
                emitEffect(
                    if (removed) {
                        HomeContract.Effect.ShowDeleteSuccess
                    } else {
                        HomeContract.Effect.ShowDeleteFailure(draftId)
                    },
                )
            },
            onFailure = {
                emitEffect(HomeContract.Effect.ShowDeleteFailure(draftId))
            },
        )
    }

    private fun retryOcr() = recoverInterruptedOcr()

    private fun recoverInterruptedOcr() {
        if (ocrRecoveryJob?.isActive == true) return
        val draftId = uiState.value.draftIdPendingOcrChoice ?: return
        val expectedRunId = uiState.value.ocrRunIdPendingChoice
        ocrRecoveryJob = executeIo(
            before = {
                updateState { copy(isRecoveringOcr = true) }
            },
            operation = { retryDraftOcrUseCase(draftId, expectedRunId) },
            onSuccess = { reset ->
                ocrRecoveryJob = null
                if (reset) {
                    savedStateHandle.remove<String>(PENDING_OCR_CHOICE_KEY)
                    savedStateHandle.remove<String>(PENDING_OCR_RUN_KEY)
                    updateState {
                        copy(
                            draftIdPendingOcrChoice = null,
                            ocrRunIdPendingChoice = null,
                            isRecoveringOcr = false,
                        )
                    }
                    emitEffect(HomeContract.Effect.OpenProcessing(draftId))
                } else {
                    updateState { copy(isRecoveringOcr = false) }
                    emitEffect(HomeContract.Effect.ShowOcrRecoveryFailure)
                }
            },
            onFailure = {
                ocrRecoveryJob = null
                updateState { copy(isRecoveringOcr = false) }
                emitEffect(HomeContract.Effect.ShowOcrRecoveryFailure)
            },
        )
    }

    private companion object {
        const val PENDING_DELETION_KEY = "home.pendingDeletionDraftId"
        const val PENDING_OCR_CHOICE_KEY = "home.pendingOcrChoiceDraftId"
        const val PENDING_OCR_RUN_KEY = "home.pendingOcrChoiceRunId"
    }
}

private fun restoredHomeState(savedStateHandle: SavedStateHandle): HomeContract.State =
    HomeContract.State(
        draftIdPendingDeletion = DraftId.parse(
            savedStateHandle.get<String>("home.pendingDeletionDraftId"),
        ),
        draftIdPendingOcrChoice = DraftId.parse(
            savedStateHandle.get<String>("home.pendingOcrChoiceDraftId"),
        ),
        ocrRunIdPendingChoice = OcrRunId.parse(
            savedStateHandle.get<String>("home.pendingOcrChoiceRunId"),
        ),
    )
