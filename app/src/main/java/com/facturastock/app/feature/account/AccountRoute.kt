package com.facturastock.app.feature.account

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.facturastock.app.R
import com.facturastock.app.domain.model.AccountSession
import com.facturastock.app.feature.common.CollectUiEffects
import com.facturastock.app.ui.components.FacturaStockDialog
import com.facturastock.app.ui.components.FacturaStockPrimaryButton
import com.facturastock.app.ui.components.FacturaStockSecondaryButton
import com.facturastock.app.ui.components.LoadingState
import com.facturastock.app.ui.components.RecoverableError
import com.facturastock.app.ui.components.StatusCard
import com.facturastock.app.ui.components.StatusTone
import com.facturastock.app.ui.theme.FacturaStockDesign

@Composable
fun AccountRoute(
    onOpenMembers: () -> Unit,
    onOpenInvitations: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AccountViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val initialLoadFailure = state.initialLoadFailure

    CollectUiEffects(viewModel.effects) { effect ->
        when (effect) {
            AccountContract.Effect.OpenMembers -> onOpenMembers()
            AccountContract.Effect.OpenInvitations -> onOpenInvitations()
            AccountContract.Effect.Back -> onBack()
        }
    }

    if (state.session == null && initialLoadFailure != null) {
        RecoverableError(
            title = stringResource(R.string.feature_load_error_title),
            message = stringResource(accountErrorMessageRes(initialLoadFailure)),
            actionLabel = stringResource(R.string.action_retry),
            onAction = { viewModel.onAction(AccountContract.Action.RetryInitialLoad) },
            modifier = modifier.fillMaxSize(),
        )
    } else if (state.session == null) {
        LoadingState(
            message = stringResource(R.string.feature_loading_message),
            modifier = modifier.fillMaxSize(),
        )
    } else {
        AccountScreen(
            state = state,
            onAction = viewModel::onAction,
            modifier = modifier,
        )
    }
}

@Composable
fun AccountScreen(
    state: AccountContract.State,
    onAction: (AccountContract.Action) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FacturaStockDesign.spacing

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        state.initialLoadFailure?.let { failure ->
            RecoverableError(
                title = stringResource(R.string.feature_load_error_title),
                message = stringResource(accountErrorMessageRes(failure)),
                actionLabel = stringResource(R.string.action_retry),
                onAction = { onAction(AccountContract.Action.RetryInitialLoad) },
            )
        }
        state.feedback?.let { feedback ->
            StatusCard(
                statusLabel = stringResource(R.string.account_status_label),
                title = stringResource(R.string.account_feedback_title),
                message = stringResource(
                    when (feedback) {
                        AccountContract.Feedback.PASSWORD_RESET_SENT ->
                            R.string.account_password_reset_sent

                        AccountContract.Feedback.VERIFICATION_RESENT ->
                            R.string.account_verification_resent

                        AccountContract.Feedback.BUSINESS_CREATED ->
                            R.string.account_business_created

                        AccountContract.Feedback.LINK_UPDATED -> R.string.account_link_updated

                        AccountContract.Feedback.ACCOUNT_DELETED ->
                            R.string.account_deleted_feedback
                    },
                ),
                tone = StatusTone.SUCCESS,
                iconRes = R.drawable.ic_check_circle,
                announcementMode = LiveRegionMode.Polite,
            )
        }
        state.failure?.let { failure ->
            StatusCard(
                statusLabel = stringResource(R.string.account_status_label),
                title = stringResource(R.string.account_error_title),
                message = stringResource(accountErrorMessageRes(failure)),
                tone = StatusTone.ERROR,
                iconRes = R.drawable.ic_warning,
            )
        }
        if (state.deletionPendingSignOut) {
            StatusCard(
                statusLabel = stringResource(R.string.account_status_label),
                title = stringResource(R.string.account_deletion_cleanup_title),
                message = stringResource(
                    if (state.isDeletingAccount) {
                        R.string.account_deletion_cleanup_working
                    } else {
                        R.string.account_deletion_cleanup_retry_message
                    },
                ),
                tone = StatusTone.WARNING,
                iconRes = R.drawable.ic_warning,
            )
            FacturaStockSecondaryButton(
                text = stringResource(R.string.account_deletion_cleanup_retry),
                onClick = {
                    onAction(AccountContract.Action.RetryDeletedAccountCleanup)
                },
                enabled = !state.isWorking,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .testTag(AccountTestTags.DELETE_ACCOUNT_CLEANUP_RETRY),
            )
        }

        if (!state.deletionPendingSignOut) {
            when (val session = state.session) {
                null -> Unit
                AccountSession.Unavailable -> UnavailableSection()
                AccountSession.SignedOut -> CredentialsSection(state = state, onAction = onAction)
                is AccountSession.AwaitingVerification ->
                    VerificationSection(session = session, state = state, onAction = onAction)

                is AccountSession.Active ->
                    ActiveSection(session = session, state = state, onAction = onAction)
                is AccountSession.Expired -> ExpiredSection(state = state, onAction = onAction)
            }

            Spacer(modifier = Modifier.height(spacing.sm))
            FacturaStockSecondaryButton(
                text = stringResource(R.string.account_back_action),
                onClick = { onAction(AccountContract.Action.BackSelected) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(modifier = Modifier.height(spacing.md))
    }

    if (state.showSignOutDialog) {
        FacturaStockDialog(
            title = stringResource(R.string.account_sign_out_dialog_title),
            message = stringResource(R.string.account_sign_out_dialog_message),
            confirmLabel = stringResource(R.string.account_sign_out_action),
            dismissLabel = stringResource(R.string.action_cancel),
            onConfirm = { onAction(AccountContract.Action.SignOutConfirmed) },
            onDismiss = { onAction(AccountContract.Action.DismissDialogs) },
            modifier = Modifier.testTag(AccountTestTags.SIGN_OUT_DIALOG),
        )
    }
    if (state.showAccountDeletionDialog) {
        FacturaStockDialog(
            title = stringResource(R.string.account_delete_dialog_title),
            message = stringResource(R.string.account_delete_dialog_message),
            confirmLabel = stringResource(R.string.account_delete_confirm),
            dismissLabel = stringResource(R.string.action_cancel),
            onConfirm = { onAction(AccountContract.Action.DeleteAccountConfirmed) },
            onDismiss = { onAction(AccountContract.Action.DismissDialogs) },
            confirmEnabled = state.canConfirmAccountDeletion,
            content = {
                OutlinedTextField(
                    value = state.accountDeletionPassword,
                    onValueChange = {
                        onAction(AccountContract.Action.AccountDeletionPasswordChanged(it))
                    },
                    label = { Text(stringResource(R.string.account_delete_password_label)) },
                    supportingText = {
                        Text(stringResource(R.string.account_delete_password_support))
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(AccountTestTags.DELETE_ACCOUNT_PASSWORD),
                )
            },
            modifier = Modifier.testTag(AccountTestTags.DELETE_ACCOUNT_DIALOG),
        )
    }
}

@Composable
private fun UnavailableSection() {
    StatusCard(
        statusLabel = stringResource(R.string.account_status_label),
        title = stringResource(R.string.account_unavailable_title),
        message = stringResource(R.string.account_unavailable_message),
        tone = StatusTone.INFO,
        iconRes = R.drawable.ic_info,
    )
}

@Composable
private fun CredentialsSection(
    state: AccountContract.State,
    onAction: (AccountContract.Action) -> Unit,
) {
    OutlinedTextField(
        value = state.email,
        onValueChange = { onAction(AccountContract.Action.EmailChanged(it)) },
        label = { Text(stringResource(R.string.account_email_label)) },
        singleLine = true,
        isError = state.email.isNotBlank() && !state.isEmailValid,
        supportingText = if (state.email.isNotBlank() && !state.isEmailValid) {
            { Text(stringResource(R.string.account_email_error)) }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.password,
        onValueChange = { onAction(AccountContract.Action.PasswordChanged(it)) },
        label = { Text(stringResource(R.string.account_password_label)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        isError = state.password.isNotEmpty() && !state.isPasswordValid,
        supportingText = if (state.password.isNotEmpty() && !state.isPasswordValid) {
            { Text(stringResource(R.string.account_password_error)) }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    FacturaStockPrimaryButton(
        text = stringResource(
            if (state.isRegisterMode) {
                R.string.account_register_action
            } else {
                R.string.account_sign_in_action
            },
        ),
        onClick = { onAction(AccountContract.Action.SubmitCredentials) },
        enabled = state.canSubmitCredentials,
        modifier = Modifier.fillMaxWidth(),
    )
    FacturaStockSecondaryButton(
        text = stringResource(
            if (state.isRegisterMode) {
                R.string.account_switch_to_sign_in
            } else {
                R.string.account_switch_to_register
            },
        ),
        onClick = { onAction(AccountContract.Action.ToggleAuthMode) },
        enabled = !state.isWorking,
        modifier = Modifier.fillMaxWidth(),
    )
    FacturaStockSecondaryButton(
        text = stringResource(R.string.account_password_reset_action),
        onClick = { onAction(AccountContract.Action.SendPasswordReset) },
        enabled = state.canSendPasswordReset,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun VerificationSection(
    session: AccountSession.AwaitingVerification,
    state: AccountContract.State,
    onAction: (AccountContract.Action) -> Unit,
) {
    StatusCard(
        statusLabel = stringResource(R.string.account_status_label),
        title = stringResource(R.string.account_verification_title),
        message = stringResource(R.string.account_verification_message, session.email),
        tone = StatusTone.INFO,
        iconRes = R.drawable.ic_info,
    )
    FacturaStockPrimaryButton(
        text = stringResource(R.string.account_verification_refresh),
        onClick = { onAction(AccountContract.Action.RefreshVerification) },
        enabled = !state.isWorking,
        modifier = Modifier.fillMaxWidth(),
    )
    FacturaStockSecondaryButton(
        text = stringResource(R.string.account_verification_resend),
        onClick = { onAction(AccountContract.Action.ResendVerification) },
        enabled = !state.isWorking,
        modifier = Modifier.fillMaxWidth(),
    )
    AccountDeletionSection(state = state, onAction = onAction)
}

@Composable
private fun ActiveSection(
    session: AccountSession.Active,
    state: AccountContract.State,
    onAction: (AccountContract.Action) -> Unit,
) {
    LabeledValue(
        labelRes = R.string.account_email_value_label,
        value = session.email,
    )
    val linkedName = session.link?.let { link ->
        state.memberships.firstOrNull { it.businessId == link.cloudBusinessId }?.businessDisplayName
            ?: link.cloudBusinessId.value
    }
    LabeledValue(
        labelRes = R.string.account_linked_business_label,
        value = linkedName ?: stringResource(R.string.account_no_linked_business),
    )

    SectionHeader(titleRes = R.string.account_memberships_section)
    Text(
        text = stringResource(R.string.account_membership_visibility_notice),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
    when {
        state.isLoadingMemberships -> LoadingState(
            message = stringResource(R.string.feature_loading_message),
        )

        state.memberships.isEmpty() -> Text(
            text = stringResource(R.string.account_memberships_empty),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )

        else -> state.memberships.forEach { membership ->
            MembershipOption(
                selected = session.link?.cloudBusinessId == membership.businessId,
                enabled = !state.isDemoMode && state.changingLinkTo == null && !state.isWorking,
                name = membership.businessDisplayName ?: membership.businessId.value,
                roleLabel = stringResource(businessRoleLabelRes(membership.role)),
                onSelect = {
                    onAction(AccountContract.Action.MembershipSelected(membership.businessId))
                },
                modifier = Modifier.testTag(
                    AccountTestTags.membership(membership.businessId.value),
                ),
            )
        }
    }

    SectionHeader(titleRes = R.string.account_create_business_section)
    OutlinedTextField(
        value = state.businessDisplayName,
        onValueChange = { onAction(AccountContract.Action.BusinessDisplayNameChanged(it)) },
        label = { Text(stringResource(R.string.account_business_name_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    FacturaStockPrimaryButton(
        text = stringResource(R.string.account_create_business_action),
        onClick = { onAction(AccountContract.Action.CreateBusiness) },
        enabled = state.canCreateBusiness,
        modifier = Modifier.fillMaxWidth(),
    )

    FacturaStockSecondaryButton(
        text = stringResource(R.string.account_open_members),
        onClick = { onAction(AccountContract.Action.OpenMembers) },
        enabled = session.link != null,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(AccountTestTags.OPEN_MEMBERS),
    )
    FacturaStockSecondaryButton(
        text = stringResource(R.string.account_open_invitations),
        onClick = { onAction(AccountContract.Action.OpenInvitations) },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(AccountTestTags.OPEN_INVITATIONS),
    )
    FacturaStockSecondaryButton(
        text = stringResource(R.string.account_sign_out_action),
        onClick = { onAction(AccountContract.Action.SignOutRequested) },
        enabled = !state.isWorking,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(AccountTestTags.SIGN_OUT_ACTION),
    )

    AccountDeletionSection(state = state, onAction = onAction)
}

@Composable
private fun AccountDeletionSection(
    state: AccountContract.State,
    onAction: (AccountContract.Action) -> Unit,
) {
    SectionHeader(titleRes = R.string.account_delete_section)
    Text(
        text = stringResource(R.string.account_delete_summary),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyLarge,
    )
    FacturaStockSecondaryButton(
        text = stringResource(
            if (state.accountDeletionFailed) {
                R.string.account_delete_retry
            } else {
                R.string.account_delete_action
            },
        ),
        onClick = { onAction(AccountContract.Action.DeleteAccountRequested) },
        enabled = !state.isWorking && !state.deletionPendingSignOut,
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(AccountTestTags.DELETE_ACCOUNT_ACTION),
    )
}

@Composable
private fun ExpiredSection(
    state: AccountContract.State,
    onAction: (AccountContract.Action) -> Unit,
) {
    StatusCard(
        statusLabel = stringResource(R.string.account_status_label),
        title = stringResource(R.string.account_expired_title),
        message = stringResource(R.string.account_expired_message),
        tone = StatusTone.WARNING,
        iconRes = R.drawable.ic_warning,
    )
    FacturaStockPrimaryButton(
        text = stringResource(R.string.account_reconnect_action),
        onClick = { onAction(AccountContract.Action.Reconnect) },
        enabled = !state.isWorking,
        modifier = Modifier.fillMaxWidth(),
    )
    if (state.showExpiredSignInForm) {
        CredentialsSection(state = state, onAction = onAction)
    }
}

@Composable
private fun SectionHeader(@StringRes titleRes: Int) {
    Text(
        text = stringResource(titleRes),
        modifier = Modifier.semantics { heading() },
        color = MaterialTheme.colorScheme.onBackground,
        style = MaterialTheme.typography.titleLarge,
    )
}

@Composable
private fun LabeledValue(
    @StringRes labelRes: Int,
    value: String,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(labelRes),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun MembershipOption(
    selected: Boolean,
    enabled: Boolean,
    name: String,
    roleLabel: String,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FacturaStockDesign.spacing

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = spacing.minimumTouchTarget)
            .selectable(
                selected = selected,
                enabled = enabled,
                onClick = onSelect,
                role = Role.RadioButton,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(modifier = Modifier.width(spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = roleLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
