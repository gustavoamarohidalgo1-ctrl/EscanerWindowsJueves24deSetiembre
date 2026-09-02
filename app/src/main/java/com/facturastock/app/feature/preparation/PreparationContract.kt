package com.facturastock.app.feature.preparation

import androidx.compose.runtime.Immutable
import com.facturastock.app.domain.model.PurchaseDuplicateAssessment
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.repository.PurchaseConfirmationBlocker
import com.facturastock.app.feature.common.UiAction
import com.facturastock.app.feature.common.UiEffect
import com.facturastock.app.feature.common.UiState

object PreparationContract {
    @Immutable
    data class State(
        val draftId: DraftId? = null,
        val expectedPreparedLogicalHash: String? = null,
        val isCheckingDuplicates: Boolean = false,
        val isConfirming: Boolean = false,
        val duplicateAssessment: PurchaseDuplicateAssessment? = null,
        val showOverrideDialog: Boolean = false,
        val overrideReason: String = "",
        val confirmationBlockers: Set<PurchaseConfirmationBlocker> = emptySet(),
        val confirmedPurchaseId: PurchaseId? = null,
        val failure: Failure? = null,
    ) : UiState

    enum class Failure {
        INVALID_DRAFT_ID,
        PREPARATION_FAILED,
        STORAGE_FULL,
        DUPLICATE_CHECK_FAILED,
        OVERRIDE_REASON_REQUIRED,
        OVERRIDE_NOT_AUTHORIZED,
        OVERRIDE_FAILED,
        DUPLICATE_CHANGED,
        CONFIRMATION_BLOCKED,
        PREPARED_PURCHASE_CHANGED,
        RETRYABLE_CONFLICT,
    }

    sealed interface Action : UiAction {
        data object Start : Action
        data object Confirm : Action
        data object Retry : Action
        data object OpenExistingSelected : Action
        data object RequestOverride : Action
        data class OverrideReasonChanged(val value: String) : Action
        data object ConfirmOverride : Action
        data object DismissOverride : Action
        data object BackSelected : Action
    }

    sealed interface Effect : UiEffect {
        data class OpenPurchase(val purchaseId: PurchaseId) : Effect
        data class OpenExistingPurchase(val purchaseId: PurchaseId) : Effect
        data object Back : Effect
        data object CloseInvalidRoute : Effect
    }
}
