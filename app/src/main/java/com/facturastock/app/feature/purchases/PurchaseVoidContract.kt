package com.facturastock.app.feature.purchases

import androidx.compose.runtime.Immutable
import com.facturastock.app.domain.model.PurchaseVoidPreview
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.repository.PurchaseVoidRequest
import com.facturastock.app.feature.common.UiAction
import com.facturastock.app.feature.common.UiEffect
import com.facturastock.app.feature.common.UiState

object PurchaseVoidContract {
    @Immutable
    data class State(
        val purchaseId: PurchaseId? = null,
        val isLoading: Boolean = false,
        val isSubmitting: Boolean = false,
        val preview: PurchaseVoidPreview? = null,
        val reason: String = "",
        val confirmed: Boolean = false,
        val failure: Failure? = null,
    ) : UiState {
        val reasonLength: Int
            get() = reason.trim().length

        val reasonIsValid: Boolean
            get() = reasonLength in PurchaseVoidRequest.MIN_REASON_LENGTH..
                PurchaseVoidRequest.MAX_REASON_LENGTH

        val canSubmit: Boolean
            get() = preview != null && reasonIsValid && confirmed &&
                !isLoading && !isSubmitting
    }

    enum class Failure {
        INVALID_PURCHASE_ID,
        LOAD_FAILED,
        SUBMIT_FAILED,
        STORAGE_FULL,
        NO_ACTIVE_BUSINESS,
        NOT_FOUND,
        NOT_POSTED,
        UNAUTHORIZED,
        CONFIRMATION_REQUIRED,
        INVALID_REASON,
        IMPACT_CHANGED,
        RETRYABLE_CONFLICT,
    }

    sealed interface Action : UiAction {
        data object Load : Action
        data object Retry : Action
        data class ReasonChanged(val reason: String) : Action
        data class ConfirmationChanged(val confirmed: Boolean) : Action
        data object Submit : Action
        data object BackSelected : Action
    }

    sealed interface Effect : UiEffect {
        data class Completed(val purchaseId: PurchaseId) : Effect
        data object Back : Effect
        data object CloseInvalidRoute : Effect
    }
}
