package com.facturastock.app.feature.review

import androidx.lifecycle.SavedStateHandle
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.domain.model.WorkflowRequest
import com.facturastock.app.domain.model.WorkflowStage
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.usecase.RunDraftStageUseCase
import com.facturastock.app.feature.common.RouteArgumentKeys
import com.facturastock.app.feature.common.UdfViewModel
import javax.inject.Inject
class ReviewViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val runDraftStageUseCase: RunDraftStageUseCase,
    dispatcherProvider: DispatcherProvider,
) : UdfViewModel<ReviewContract.State, ReviewContract.Action, ReviewContract.Effect>(
    initialState = reviewInitialState(savedStateHandle),
    dispatcherProvider = dispatcherProvider,
) {
    override fun onAction(action: ReviewContract.Action) {
        when (action) {
            ReviewContract.Action.Submit,
            ReviewContract.Action.Retry,
            -> submitReview()

            ReviewContract.Action.BackSelected -> executeMain {
                emitEffect(ReviewContract.Effect.Back)
            }
        }
    }

    private fun submitReview() {
        val draftId = uiState.value.draftId
        if (draftId == null || uiState.value.failure == ReviewContract.Failure.INVALID_LINE_ID) {
            executeMain {
                emitEffect(ReviewContract.Effect.CloseInvalidRoute)
            }
            return
        }

        executeIo(
            before = {
                updateState { copy(isSubmitting = true, failure = null) }
            },
            operation = {
                runDraftStageUseCase(
                    WorkflowRequest(
                        stage = if (uiState.value.lineId == null) {
                            WorkflowStage.LINES_REVIEWED
                        } else {
                            WorkflowStage.PRODUCTS_LINKED
                        },
                        draftId = draftId,
                        lineId = uiState.value.lineId,
                    ),
                )
            },
            onSuccess = { snapshot ->
                updateState {
                    copy(isSubmitting = false, snapshot = snapshot, failure = null)
                }
                emitEffect(ReviewContract.Effect.OpenPreparation(snapshot.draftId))
            },
            onFailure = {
                updateState {
                    copy(
                        isSubmitting = false,
                        failure = ReviewContract.Failure.REVIEW_FAILED,
                    )
                }
            },
        )
    }
}

private fun reviewInitialState(savedStateHandle: SavedStateHandle): ReviewContract.State {
    val rawDraftId = savedStateHandle.get<String>(RouteArgumentKeys.DRAFT_ID)
    val rawLineId = savedStateHandle.get<String>(RouteArgumentKeys.LINE_ID)
    val draftId = DraftId.parse(rawDraftId)
    val lineId = LineId.parse(rawLineId)
    val failure = when {
        draftId == null -> ReviewContract.Failure.INVALID_DRAFT_ID
        rawLineId != null && lineId == null -> ReviewContract.Failure.INVALID_LINE_ID
        else -> null
    }
    return ReviewContract.State(
        draftId = draftId,
        lineId = lineId,
        failure = failure,
    )
}
