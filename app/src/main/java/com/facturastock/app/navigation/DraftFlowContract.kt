package com.facturastock.app.navigation

import androidx.compose.runtime.Immutable
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.feature.common.UiAction
import com.facturastock.app.feature.common.UiEffect
import com.facturastock.app.feature.common.UiState

/** Operaciones durables que deben concluir antes de cambiar la ruta del flujo de compra. */
object DraftFlowContract {
    @Immutable
    data class State(
        val isCreatingDraft: Boolean = false,
        val draftCreationFailed: Boolean = false,
        val discardingDraftId: DraftId? = null,
        val discardFailedDraftId: DraftId? = null,
    ) : UiState

    sealed interface Action : UiAction {
        data object StartDraft : Action
        data object StartNavigationHandled : Action
        data class DiscardDraft(val draftId: DraftId) : Action
        data object DiscardNavigationHandled : Action
    }

    sealed interface Effect : UiEffect {
        /** Un borrador nuevo empieza directamente en la cámara. */
        data class OpenDraftCamera(val draftId: DraftId) : Effect
        data class DraftDiscarded(val draftId: DraftId) : Effect
    }
}
