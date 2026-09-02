package com.facturastock.app.feature.account.invitations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.facturastock.app.R
import com.facturastock.app.domain.model.BusinessInvitation
import com.facturastock.app.feature.account.accountErrorMessageRes
import com.facturastock.app.feature.account.businessRoleLabelRes
import com.facturastock.app.feature.common.CollectUiEffects
import com.facturastock.app.ui.components.EmptyState
import com.facturastock.app.ui.components.FacturaStockDialog
import com.facturastock.app.ui.components.FacturaStockPrimaryButton
import com.facturastock.app.ui.components.FacturaStockSecondaryButton
import com.facturastock.app.ui.components.LoadingState
import com.facturastock.app.ui.components.RecoverableError
import com.facturastock.app.ui.components.StatusCard
import com.facturastock.app.ui.components.StatusTone
import com.facturastock.app.ui.format.formatForDisplay
import com.facturastock.app.ui.theme.FacturaStockDesign
import java.time.Instant

@Composable
fun InvitationsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: InvitationsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    CollectUiEffects(viewModel.effects) { effect ->
        when (effect) {
            InvitationsContract.Effect.Back -> onBack()
        }
    }

    InvitationsScreen(
        state = state,
        onAction = viewModel::onAction,
        modifier = modifier,
    )
}

@Composable
fun InvitationsScreen(
    state: InvitationsContract.State,
    onAction: (InvitationsContract.Action) -> Unit,
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
        if (state.invitations != null) {
            state.feedback?.let { feedback ->
                StatusCard(
                    statusLabel = stringResource(R.string.navigation_account_invitations),
                    title = stringResource(R.string.account_feedback_title),
                    message = stringResource(
                        when (feedback) {
                            InvitationsContract.Feedback.DECLINED ->
                                R.string.invitation_declined_feedback

                            InvitationsContract.Feedback.LINK_UPDATED ->
                                R.string.invitation_link_updated
                        },
                    ),
                    tone = StatusTone.SUCCESS,
                    iconRes = R.drawable.ic_check_circle,
                    announcementMode = LiveRegionMode.Polite,
                )
            }
            state.failure?.let { failure ->
                StatusCard(
                    statusLabel = stringResource(R.string.navigation_account_invitations),
                    title = stringResource(R.string.account_error_title),
                    message = stringResource(accountErrorMessageRes(failure)),
                    tone = StatusTone.ERROR,
                    iconRes = R.drawable.ic_warning,
                )
            }
        }

        when {
            state.invitations == null && state.failure != null -> RecoverableError(
                title = stringResource(R.string.feature_load_error_title),
                message = stringResource(accountErrorMessageRes(state.failure)),
                actionLabel = stringResource(R.string.action_retry),
                onAction = { onAction(InvitationsContract.Action.Retry) },
            )

            state.invitations == null -> LoadingState(
                message = stringResource(R.string.feature_loading_message),
            )

            state.invitations.isEmpty() -> EmptyState(
                title = stringResource(R.string.invitation_empty_title),
                message = stringResource(R.string.invitation_empty_message),
            )

            else -> state.invitations.orEmpty().forEach { invitation ->
                InvitationCard(
                    invitation = invitation,
                    enabled = !state.isBusy,
                    onAccept = { onAction(InvitationsContract.Action.Accept(invitation)) },
                    onDecline = { onAction(InvitationsContract.Action.Decline(invitation)) },
                )
            }
        }

        Spacer(modifier = Modifier.height(spacing.sm))
        FacturaStockSecondaryButton(
            text = stringResource(R.string.invitation_back_action),
            onClick = { onAction(InvitationsContract.Action.BackSelected) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(spacing.md))
    }

    val accepted = state.acceptedMembership
    if (accepted != null) {
        FacturaStockDialog(
            title = stringResource(R.string.invitation_accepted_title),
            message = stringResource(
                R.string.invitation_accepted_message,
                accepted.businessDisplayName
                    ?: stringResource(R.string.invitation_business_unknown),
            ),
            confirmLabel = stringResource(R.string.invitation_set_active_action),
            dismissLabel = stringResource(R.string.invitation_keep_current_action),
            onConfirm = { onAction(InvitationsContract.Action.SetActiveAfterAccept) },
            onDismiss = { onAction(InvitationsContract.Action.DismissAccepted) },
            confirmEnabled = !state.isDemoMode,
        )
    }
}

@Composable
private fun InvitationCard(
    invitation: BusinessInvitation,
    enabled: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    val spacing = FacturaStockDesign.spacing

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(spacing.md)) {
            Text(
                text = invitation.businessDisplayName
                    ?: stringResource(R.string.invitation_business_unknown),
                modifier = Modifier.semantics { heading() },
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(spacing.xxs))
            Text(
                text = stringResource(
                    R.string.invitation_role_line,
                    stringResource(businessRoleLabelRes(invitation.role)),
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(
                    R.string.invitation_expires_line,
                    Instant.ofEpochMilli(invitation.expiresAtEpochMilli).formatForDisplay(),
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(spacing.xxs))
            Text(
                text = stringResource(R.string.invitation_access_disclosure),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(spacing.sm))
            FacturaStockPrimaryButton(
                text = stringResource(R.string.invitation_accept_action),
                onClick = onAccept,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(InvitationsTestTags.acceptTag(invitation.businessId.value)),
            )
            Spacer(modifier = Modifier.height(spacing.xs))
            FacturaStockSecondaryButton(
                text = stringResource(R.string.invitation_decline_action),
                onClick = onDecline,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
