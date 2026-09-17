package com.facturastock.app.feature.account

import androidx.compose.runtime.Immutable
import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.model.AccountSession
import com.facturastock.app.domain.model.CloudMembership
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.feature.common.UiAction
import com.facturastock.app.feature.common.UiEffect
import com.facturastock.app.feature.common.UiState

object AccountContract {
    @Immutable
    data class State(
        val session: AccountSession? = null,
        val activeBusinessId: BusinessId? = null,
        val isDemoMode: Boolean = false,
        val email: String = "",
        val password: String = "",
        val isRegisterMode: Boolean = false,
        val showExpiredSignInForm: Boolean = false,
        val memberships: List<CloudMembership> = emptyList(),
        val isLoadingMemberships: Boolean = false,
        val businessDisplayName: String = "",
        val isWorking: Boolean = false,
        val isCreatingBusiness: Boolean = false,
        val changingLinkTo: BusinessId? = null,
        val feedback: Feedback? = null,
        val failure: AccountError? = null,
        /** Fallo exclusivo de los Flows requeridos para construir la primera pantalla. */
        val initialLoadFailure: AccountError? = null,
        val showSignOutDialog: Boolean = false,
        val accountDeletionAvailable: Boolean = true,
        val showAccountDeletionDialog: Boolean = false,
        val accountDeletionPassword: String = "",
        val isDeletingAccount: Boolean = false,
        val accountDeletionFailed: Boolean = false,
        /** El callable pudo empezar o terminó; nunca se reenvía y falta limpiar la sesión local. */
        val deletionPendingSignOut: Boolean = false,
    ) : UiState {
        val isEmailValid: Boolean
            get() = email.trim().let {
                it.isNotEmpty() && it.length <= EMAIL_MAX_LENGTH && it.contains('@')
            }

        val isPasswordValid: Boolean
            get() = password.length >= MIN_PASSWORD_LENGTH

        val canSubmitCredentials: Boolean
            get() = !isWorking && isEmailValid && isPasswordValid

        val canSendPasswordReset: Boolean
            get() = !isWorking && isEmailValid

        val canCreateBusiness: Boolean
            get() = !isWorking &&
                !isCreatingBusiness &&
                businessDisplayName.isNotBlank() &&
                businessDisplayName.length <= BUSINESS_DISPLAY_NAME_MAX_LENGTH

        val canConfirmAccountDeletion: Boolean
            get() = !isWorking && accountDeletionPassword.length >= MIN_PASSWORD_LENGTH
    }

    enum class Feedback {
        PASSWORD_RESET_SENT,
        VERIFICATION_RESENT,
        BUSINESS_CREATED,
        LINK_UPDATED,
        ACCOUNT_DELETED,
        ACCOUNT_DELETION_PENDING,
        ACCOUNT_DELETION_UNCONFIRMED,
    }

    sealed interface Action : UiAction {
        data class EmailChanged(val value: String) : Action
        data class PasswordChanged(val value: String) : Action
        data object ToggleAuthMode : Action
        data object SubmitCredentials : Action
        data object SendPasswordReset : Action
        data object ResendVerification : Action
        data object RefreshVerification : Action
        data class MembershipSelected(val cloudBusinessId: BusinessId) : Action
        data class BusinessDisplayNameChanged(val value: String) : Action
        data object CreateBusiness : Action
        data object OpenMembers : Action
        data object OpenInvitations : Action
        data object SignOutRequested : Action
        data object SignOutConfirmed : Action
        data object DeleteAccountRequested : Action
        data class AccountDeletionPasswordChanged(val value: String) : Action
        data object DeleteAccountConfirmed : Action
        data object RetryDeletedAccountCleanup : Action
        data object DismissDialogs : Action
        data object Reconnect : Action
        data object BackSelected : Action
        data object RetryMemberships : Action
        data object RetryInitialLoad : Action
    }

    sealed interface Effect : UiEffect {
        data object OpenMembers : Effect
        data object OpenInvitations : Effect
        data object Back : Effect
    }

    const val MIN_PASSWORD_LENGTH: Int = 6
    const val EMAIL_MAX_LENGTH: Int = 254
    const val BUSINESS_DISPLAY_NAME_MAX_LENGTH: Int = 120
}
