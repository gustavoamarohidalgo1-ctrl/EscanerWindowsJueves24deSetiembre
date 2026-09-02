package com.facturastock.app.feature.review

import androidx.compose.runtime.Immutable
import com.facturastock.app.domain.model.WorkflowSnapshot
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.feature.common.UiAction
import com.facturastock.app.feature.common.UiEffect
import com.facturastock.app.feature.common.UiState

object ReviewContract {
    @Immutable
    data class State(
        val draftId: DraftId? = null,
        val lineId: LineId? = null,
        val isSubmitting: Boolean = false,
        val snapshot: WorkflowSnapshot? = null,
        val failure: Failure? = null,
    ) : UiState

    enum class Failure {
        INVALID_DRAFT_ID,
        INVALID_LINE_ID,
        REVIEW_FAILED,
    }

    sealed interface Action : UiAction {
        data object Submit : Action
        data object Retry : Action
        data object BackSelected : Action
    }

    sealed interface Effect : UiEffect {
        data class OpenPreparation(val draftId: DraftId) : Effect
        data object Back : Effect
        data object CloseInvalidRoute : Effect
    }
}
