package com.facturastock.app.feature.account.invitations

import androidx.compose.runtime.Immutable
import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.model.BusinessInvitation
import com.facturastock.app.domain.model.CloudMembership
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.feature.common.UiAction
import com.facturastock.app.feature.common.UiEffect
import com.facturastock.app.feature.common.UiState

object InvitationsContract {
    @Immutable
    data class State(
        val activeBusinessId: BusinessId? = null,
        val isDemoMode: Boolean = false,
        val invitations: List<BusinessInvitation>? = null,
        val processingBusinessId: BusinessId? = null,
        val acceptedMembership: CloudMembership? = null,
        val isSettingActive: Boolean = false,
        val feedback: Feedback? = null,
        val failure: AccountError? = null,
    ) : UiState {
        val isBusy: Boolean
            get() = processingBusinessId != null || isSettingActive
    }

    enum class Feedback {
        DECLINED,
        LINK_UPDATED,
    }

    sealed interface Action : UiAction {
        data class Accept(val invitation: BusinessInvitation) : Action
        data class Decline(val invitation: BusinessInvitation) : Action
        data object SetActiveAfterAccept : Action
        data object DismissAccepted : Action
        data object Retry : Action
        data object BackSelected : Action
    }

    sealed interface Effect : UiEffect {
        data object Back : Effect
    }
}
