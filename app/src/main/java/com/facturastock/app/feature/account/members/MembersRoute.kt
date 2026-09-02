package com.facturastock.app.feature.account.members

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.facturastock.app.R
import com.facturastock.app.domain.model.BusinessRole
import com.facturastock.app.domain.model.CloudMember
import com.facturastock.app.feature.account.accountErrorMessageRes
import com.facturastock.app.feature.account.businessRoleLabelRes
import com.facturastock.app.feature.common.CollectUiEffects
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
fun MembersRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MembersViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val initialLoadFailure = state.initialLoadFailure

    CollectUiEffects(viewModel.effects) { effect ->
        when (effect) {
            MembersContract.Effect.Back -> onBack()
        }
    }

    if (state.session == null && initialLoadFailure != null) {
        RecoverableError(
            title = stringResource(R.string.feature_load_error_title),
            message = stringResource(accountErrorMessageRes(initialLoadFailure)),
            actionLabel = stringResource(R.string.action_retry),
            onAction = { viewModel.onAction(MembersContract.Action.Retry) },
            modifier = modifier.fillMaxSize(),
        )
    } else if (state.session == null) {
        LoadingState(
            message = stringResource(R.string.feature_loading_message),
            modifier = modifier.fillMaxSize(),
        )
    } else {
        MembersScreen(
            state = state,
            onAction = viewModel::onAction,
            modifier = modifier,
        )
    }
}

@Composable
fun MembersScreen(
    state: MembersContract.State,
    onAction: (MembersContract.Action) -> Unit,
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
        // Un fallo posterior del observador no invalida el último snapshot confirmado: se
        // conserva visible y se ofrece recuperar la observación sin convertirlo en pantalla
        // vacía ni ocultar los datos cacheados.
        state.initialLoadFailure?.let { failure ->
            RecoverableError(
                title = stringResource(R.string.feature_load_error_title),
                message = stringResource(accountErrorMessageRes(failure)),
                actionLabel = stringResource(R.string.action_retry),
                onAction = { onAction(MembersContract.Action.Retry) },
            )
        }

        when {
            state.link == null -> StatusCard(
                statusLabel = stringResource(R.string.navigation_account_members),
                title = stringResource(R.string.members_no_link_title),
                message = stringResource(R.string.members_no_link_message),
                tone = StatusTone.INFO,
                iconRes = R.drawable.ic_info,
            )

            state.members == null && state.failure != null -> RecoverableError(
                title = stringResource(R.string.feature_load_error_title),
                message = stringResource(accountErrorMessageRes(state.failure)),
                actionLabel = stringResource(R.string.action_retry),
                onAction = { onAction(MembersContract.Action.Retry) },
            )

            state.members == null -> LoadingState(
                message = stringResource(R.string.feature_loading_message),
            )

            else -> MembersContent(state = state, onAction = onAction)
        }

        Spacer(modifier = Modifier.height(spacing.sm))
        FacturaStockSecondaryButton(
            text = stringResource(R.string.members_back_action),
            onClick = { onAction(MembersContract.Action.BackSelected) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(spacing.md))
    }

    val removeCandidate = state.removeCandidate
    if (removeCandidate != null) {
        FacturaStockDialog(
            title = stringResource(R.string.members_remove_dialog_title),
            message = stringResource(
                R.string.members_remove_dialog_message,
                removeCandidate.email ?: stringResource(R.string.members_email_unknown),
            ),
            confirmLabel = stringResource(R.string.members_remove_action),
            dismissLabel = stringResource(R.string.action_cancel),
            onConfirm = { onAction(MembersContract.Action.RemoveConfirmed) },
            onDismiss = { onAction(MembersContract.Action.RemoveDismissed) },
        )
    }
}

@Composable
private fun MembersContent(
    state: MembersContract.State,
    onAction: (MembersContract.Action) -> Unit,
) {
    val myRole = state.myRole
    if (myRole != null) {
        Text(
            text = stringResource(
                R.string.members_my_role,
                stringResource(businessRoleLabelRes(myRole)),
            ),
            modifier = Modifier.semantics { heading() },
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.titleLarge,
        )
    }

    state.feedback?.let { feedback ->
        StatusCard(
            statusLabel = stringResource(R.string.navigation_account_members),
            title = stringResource(R.string.account_feedback_title),
            message = stringResource(
                when (feedback) {
                    MembersContract.Feedback.INVITED -> R.string.members_invited_feedback
                    MembersContract.Feedback.ROLE_UPDATED -> R.string.members_role_updated
                    MembersContract.Feedback.MEMBER_REMOVED -> R.string.members_removed_feedback
                },
            ),
            tone = StatusTone.SUCCESS,
            iconRes = R.drawable.ic_check_circle,
        )
    }
    state.failure?.let { failure ->
        StatusCard(
            statusLabel = stringResource(R.string.navigation_account_members),
            title = stringResource(R.string.account_error_title),
            message = stringResource(accountErrorMessageRes(failure)),
            tone = StatusTone.ERROR,
            iconRes = R.drawable.ic_warning,
        )
    }

    SectionHeader(titleRes = R.string.members_section_members)
    val members = state.members.orEmpty()
    if (members.isEmpty()) {
        Text(
            text = stringResource(R.string.members_empty),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
    members.forEach { member ->
        MemberCard(
            member = member,
            isSelf = member.uid == state.myUid,
            manageable = state.canActOn(member),
            actionsEnabled = !state.isChangingRole && !state.isRemoving && !state.isInviting,
            onChangeRole = { onAction(MembersContract.Action.ChangeRoleRequested(member)) },
            onRemove = { onAction(MembersContract.Action.RemoveRequested(member)) },
        )
    }

    val roleEditUid = state.roleEditUid
    if (roleEditUid != null) {
        val editedMember = members.firstOrNull { it.uid == roleEditUid }
        if (editedMember != null) {
            RoleEditSection(state = state, member = editedMember, onAction = onAction)
        }
    }

    if (state.canManage) {
        InviteSection(state = state, onAction = onAction)
        PendingInvitationsSection(state = state)
    }
}

@Composable
private fun MemberCard(
    member: CloudMember,
    isSelf: Boolean,
    manageable: Boolean,
    actionsEnabled: Boolean,
    onChangeRole: () -> Unit,
    onRemove: () -> Unit,
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
                text = member.email ?: stringResource(R.string.members_email_unknown),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(modifier = Modifier.height(spacing.xxs))
            Text(
                text = if (isSelf) {
                    stringResource(
                        R.string.members_role_self,
                        stringResource(businessRoleLabelRes(member.role)),
                    )
                } else {
                    stringResource(businessRoleLabelRes(member.role))
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
            )
            if (manageable) {
                Spacer(modifier = Modifier.height(spacing.sm))
                FacturaStockSecondaryButton(
                    text = stringResource(R.string.members_change_role_action),
                    onClick = onChangeRole,
                    enabled = actionsEnabled,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(spacing.xs))
                FacturaStockSecondaryButton(
                    text = stringResource(R.string.members_remove_action),
                    onClick = onRemove,
                    enabled = actionsEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(MembersTestTags.memberRemoveTag(member.uid)),
                )
            }
        }
    }
}

@Composable
private fun RoleEditSection(
    state: MembersContract.State,
    member: CloudMember,
    onAction: (MembersContract.Action) -> Unit,
) {
    SectionHeader(titleRes = R.string.members_change_role_section)
    Text(
        text = stringResource(
            R.string.members_change_role_title,
            member.email ?: stringResource(R.string.members_email_unknown),
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyLarge,
    )
    state.grantableRoles.forEach { role ->
        RoleOption(
            selected = state.roleEditSelection == role,
            labelRes = businessRoleLabelRes(role),
            onSelect = { onAction(MembersContract.Action.ChangeRoleSelected(role)) },
        )
    }
    FacturaStockPrimaryButton(
        text = stringResource(R.string.action_save),
        onClick = { onAction(MembersContract.Action.ChangeRoleConfirmed) },
        enabled = !state.isChangingRole &&
            state.roleEditSelection != null &&
            state.roleEditSelection != member.role,
        modifier = Modifier.fillMaxWidth(),
    )
    FacturaStockSecondaryButton(
        text = stringResource(R.string.action_cancel),
        onClick = { onAction(MembersContract.Action.ChangeRoleDismissed) },
        enabled = !state.isChangingRole,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun InviteSection(
    state: MembersContract.State,
    onAction: (MembersContract.Action) -> Unit,
) {
    SectionHeader(titleRes = R.string.members_invite_section)
    OutlinedTextField(
        value = state.inviteEmail,
        onValueChange = { onAction(MembersContract.Action.InviteEmailChanged(it)) },
        label = { Text(stringResource(R.string.members_invite_email_label)) },
        singleLine = true,
        isError = state.inviteEmail.isNotBlank() && !state.isInviteEmailValid,
        supportingText = if (state.inviteEmail.isNotBlank() && !state.isInviteEmailValid) {
            { Text(stringResource(R.string.account_email_error)) }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        text = stringResource(R.string.members_invite_role_label),
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.labelLarge,
    )
    state.grantableRoles.forEach { role ->
        RoleOption(
            selected = state.inviteRole == role,
            labelRes = businessRoleLabelRes(role),
            onSelect = { onAction(MembersContract.Action.InviteRoleSelected(role)) },
        )
    }
    FacturaStockPrimaryButton(
        text = stringResource(R.string.members_invite_action),
        onClick = { onAction(MembersContract.Action.SendInvitation) },
        enabled = state.canInvite,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(MembersTestTags.INVITE_ACTION),
    )
}

@Composable
private fun PendingInvitationsSection(state: MembersContract.State) {
    val spacing = FacturaStockDesign.spacing

    SectionHeader(titleRes = R.string.members_pending_section)
    if (state.pendingInvitations.isEmpty()) {
        Text(
            text = stringResource(R.string.members_pending_empty),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
    state.pendingInvitations.forEach { invitation ->
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(modifier = Modifier.padding(spacing.md)) {
                Text(
                    text = invitation.email,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(modifier = Modifier.height(spacing.xxs))
                Text(
                    text = stringResource(businessRoleLabelRes(invitation.role)),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = stringResource(
                        R.string.members_invitation_expires,
                        Instant.ofEpochMilli(invitation.expiresAtEpochMilli).formatForDisplay(),
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
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
private fun RoleOption(
    selected: Boolean,
    @StringRes labelRes: Int,
    onSelect: () -> Unit,
) {
    val spacing = FacturaStockDesign.spacing

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = spacing.minimumTouchTarget)
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(modifier = Modifier.width(spacing.sm))
        Text(
            text = stringResource(labelRes),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
