package com.facturastock.app.navigation

import androidx.lifecycle.SavedStateHandle
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.usecase.DeleteDraftUseCase
import com.facturastock.app.domain.usecase.ObserveInvoiceDraftUseCase
import com.facturastock.app.domain.usecase.StartInvoiceDraftUseCase
import com.facturastock.app.feature.common.UdfViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first

/**
 * Coordina las mutaciones que protegen los límites de navegación del borrador.
 *
 * El ID pendiente se guarda antes de tocar Room. Tras recreación o muerte de proceso se consulta
 * primero el registro durable: si el insert ya concluyó se reutiliza y, si no, se reanuda con el
 * mismo ID. Los pendientes solo se limpian después de que la UI haya cambiado de destino.
 */
@HiltViewModel
class DraftFlowViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val startInvoiceDraftUseCase: StartInvoiceDraftUseCase,
    private val deleteDraftUseCase: DeleteDraftUseCase,
    private val observeInvoiceDraftUseCase: ObserveInvoiceDraftUseCase,
    private val uuidGenerator: UuidGenerator,
    dispatcherProvider: DispatcherProvider,
) : UdfViewModel<
    DraftFlowContract.State,
    DraftFlowContract.Action,
    DraftFlowContract.Effect,
    >(
    initialState = restoredDraftFlowState(savedStateHandle),
    dispatcherProvider = dispatcherProvider,
) {
    private var creationJob: Job? = null
    private var discardJob: Job? = null

    init {
        pendingCreationId()?.let(::materializeDraft)
        pendingDiscardId()?.let(::deleteDraft)
    }

    override fun onAction(action: DraftFlowContract.Action) {
        when (action) {
            DraftFlowContract.Action.StartDraft -> startDraft()
            DraftFlowContract.Action.StartNavigationHandled -> executeMain {
                savedStateHandle.remove<String>(PENDING_CREATION_KEY)
                updateState { copy(isCreatingDraft = false, draftCreationFailed = false) }
            }

            is DraftFlowContract.Action.DiscardDraft -> requestDiscard(action.draftId)
            DraftFlowContract.Action.DiscardNavigationHandled -> executeMain {
                savedStateHandle.remove<String>(PENDING_DISCARD_KEY)
                updateState { copy(discardingDraftId = null, discardFailedDraftId = null) }
            }
        }
    }

    private fun startDraft() {
        if (creationJob?.isActive == true || uiState.value.isCreatingDraft) return
        val draftId = pendingCreationId() ?: DraftId.from(uuidGenerator.newUuid()).also { id ->
            savedStateHandle[PENDING_CREATION_KEY] = id.value
        }
        materializeDraft(draftId)
    }

    private fun materializeDraft(draftId: DraftId) {
        if (creationJob?.isActive == true) return
        creationJob = executeIo(
            before = {
                updateState { copy(isCreatingDraft = true, draftCreationFailed = false) }
            },
            operation = {
                observeInvoiceDraftUseCase(draftId).first()
                    ?: startInvoiceDraftUseCase(draftId)
            },
            onSuccess = { durableDraft ->
                creationJob = null
                emitEffect(DraftFlowContract.Effect.OpenDraftCamera(durableDraft.draftId))
            },
            onFailure = {
                creationJob = null
                updateState { copy(isCreatingDraft = false, draftCreationFailed = true) }
            },
        )
    }

    private fun requestDiscard(draftId: DraftId) {
        if (discardJob?.isActive == true || uiState.value.discardingDraftId != null) return
        savedStateHandle[PENDING_DISCARD_KEY] = draftId.value
        deleteDraft(draftId)
    }

    private fun deleteDraft(draftId: DraftId) {
        if (discardJob?.isActive == true) return
        discardJob = executeIo(
            before = {
                updateState {
                    copy(discardingDraftId = draftId, discardFailedDraftId = null)
                }
            },
            operation = { deleteDraftUseCase(draftId) },
            onSuccess = {
                // `false` también es éxito idempotente: el borrador ya no existe.
                discardJob = null
                emitEffect(DraftFlowContract.Effect.DraftDiscarded(draftId))
            },
            onFailure = {
                discardJob = null
                updateState {
                    copy(discardingDraftId = null, discardFailedDraftId = draftId)
                }
            },
        )
    }

    private fun pendingCreationId(): DraftId? =
        DraftId.parse(savedStateHandle.get<String>(PENDING_CREATION_KEY))

    private fun pendingDiscardId(): DraftId? =
        DraftId.parse(savedStateHandle.get<String>(PENDING_DISCARD_KEY))

    private companion object {
        const val PENDING_CREATION_KEY = "draftFlow.pendingCreationId"
        const val PENDING_DISCARD_KEY = "draftFlow.pendingDiscardId"
    }
}

private fun restoredDraftFlowState(savedStateHandle: SavedStateHandle): DraftFlowContract.State =
    DraftFlowContract.State(
        isCreatingDraft = DraftId.parse(
            savedStateHandle.get<String>("draftFlow.pendingCreationId"),
        ) != null,
        discardingDraftId = DraftId.parse(
            savedStateHandle.get<String>("draftFlow.pendingDiscardId"),
        ),
    )
