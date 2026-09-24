package com.facturastock.app.feature.purchases

import org.jetbrains.compose.resources.StringResource

import com.facturastock.app.resources.*
import com.facturastock.app.ui.navigation.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.facturastock.app.domain.model.PurchaseOverrideRole
import com.facturastock.app.domain.model.PurchaseVoidImpact
import com.facturastock.app.domain.model.PurchaseVoidPreview
import com.facturastock.app.domain.repository.PurchaseVoidRequest
import com.facturastock.app.ui.components.FacturaStockPrimaryButton
import com.facturastock.app.ui.components.FacturaStockSecondaryButton
import com.facturastock.app.ui.components.LoadingState
import com.facturastock.app.ui.components.StatusCard
import com.facturastock.app.ui.components.StatusTone
import com.facturastock.app.ui.theme.FacturaStockDesign
import java.math.BigDecimal

@Composable
internal fun PurchaseVoidScreen(
    state: PurchaseVoidContract.State,
    onAction: (PurchaseVoidContract.Action) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(enabled = state.isSubmitting) { /* El commit atómico ya está en curso. */ }
    val spacing = FacturaStockDesign.spacing
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(PurchaseVoidTestTags.SCREEN),
    ) {
        when {
            state.isLoading && state.preview == null -> LoadingState(
                message = stringResource(Res.string.purchase_void_loading),
                modifier = Modifier.weight(1f),
            )
            state.preview == null -> PurchaseVoidBlockingContent(
                failure = state.failure ?: PurchaseVoidContract.Failure.LOAD_FAILED,
                onRetry = { onAction(PurchaseVoidContract.Action.Retry) },
                modifier = Modifier.weight(1f),
            )
            else -> PurchaseVoidPreviewContent(
                state = state,
                onAction = onAction,
                modifier = Modifier.weight(1f),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.md, vertical = spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            if (state.preview != null) {
                FacturaStockPrimaryButton(
                    text = stringResource(
                        if (state.isSubmitting) Res.string.purchase_void_submitting
                        else Res.string.purchase_void_submit,
                    ),
                    onClick = { onAction(PurchaseVoidContract.Action.Submit) },
                    enabled = state.canSubmit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(PurchaseVoidTestTags.SUBMIT),
                )
            }
            FacturaStockSecondaryButton(
                text = stringResource(Res.string.purchase_void_back),
                onClick = { onAction(PurchaseVoidContract.Action.BackSelected) },
                enabled = !state.isSubmitting,
                leadingIconRes = Res.drawable.ic_back,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(PurchaseVoidTestTags.BACK),
            )
        }
    }
}

@Composable
private fun PurchaseVoidBlockingContent(
    failure: PurchaseVoidContract.Failure,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FacturaStockDesign.spacing
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .testTag(PurchaseVoidTestTags.CONTENT),
        contentPadding = PaddingValues(spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        item {
            PurchaseVoidHeader()
        }
        item {
            PurchaseVoidFailureCard(failure)
        }
        if (failure.canRetry()) {
            item {
                FacturaStockSecondaryButton(
                    text = stringResource(Res.string.purchase_void_retry),
                    onClick = onRetry,
                    leadingIconRes = Res.drawable.ic_refresh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(PurchaseVoidTestTags.RETRY),
                )
            }
        }
    }
}

@Composable
private fun PurchaseVoidPreviewContent(
    state: PurchaseVoidContract.State,
    onAction: (PurchaseVoidContract.Action) -> Unit,
    modifier: Modifier = Modifier,
) {
    val preview = requireNotNull(state.preview)
    val spacing = FacturaStockDesign.spacing
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .testTag(PurchaseVoidTestTags.CONTENT),
        contentPadding = PaddingValues(spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        item(key = "header", contentType = "header") {
            PurchaseVoidHeader()
        }
        item(key = "document", contentType = "document") {
            PurchaseVoidDocumentCard(preview)
        }
        if (preview.hasNegativeImpact) {
            item(key = "negative_warning", contentType = "warning") {
                NegativeStockWarning()
            }
        }
        item(key = "impacts_header", contentType = "section_header") {
            SectionTitle(
                stringResource(Res.string.purchase_void_impact_title, preview.impacts.size),
            )
        }
        items(
            items = preview.impacts,
            key = { impact -> "${impact.productId.value}:${impact.locationId.value}" },
            contentType = { "impact" },
        ) { impact ->
            PurchaseVoidImpactCard(
                impact = impact,
                modifier = Modifier.testTag(
                    PurchaseVoidTestTags.impact(
                        impact.productId.value,
                        impact.locationId.value,
                    ),
                ),
            )
        }
        item(key = "reason", contentType = "input") {
            PurchaseVoidReasonField(
                reason = state.reason,
                isError = state.failure == PurchaseVoidContract.Failure.INVALID_REASON,
                enabled = !state.isSubmitting && !state.isLoading,
                onReasonChange = {
                    onAction(PurchaseVoidContract.Action.ReasonChanged(it))
                },
            )
        }
        state.failure?.let { failure ->
            item(key = "failure", contentType = "warning") {
                PurchaseVoidFailureCard(failure)
            }
        }
        item(key = "confirmation", contentType = "input") {
            PurchaseVoidConfirmation(
                checked = state.confirmed,
                enabled = !state.isSubmitting && !state.isLoading,
                onCheckedChange = {
                    onAction(PurchaseVoidContract.Action.ConfirmationChanged(it))
                },
            )
        }
    }
}

@Composable
private fun PurchaseVoidHeader() {
    Column(verticalArrangement = Arrangement.spacedBy(FacturaStockDesign.spacing.xs)) {
        Text(
            text = stringResource(Res.string.purchase_void_title),
            modifier = Modifier.semantics { heading() },
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.headlineLarge,
        )
        Text(
            text = stringResource(Res.string.purchase_void_intro),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun PurchaseVoidDocumentCard(preview: PurchaseVoidPreview) {
    Column(modifier = Modifier.testTag(PurchaseVoidTestTags.DOCUMENT)) {
        StatusCard(
            statusLabel = stringResource(Res.string.purchase_void_authorization_title),
            title = stringResource(Res.string.purchase_void_document, preview.documentNumber),
            message = stringResource(
                Res.string.purchase_void_authorized_role,
                stringResource(preview.actor.role.labelRes()),
            ),
            tone = StatusTone.INFO,
            iconRes = Res.drawable.ic_info,
            modifier = Modifier.testTag(PurchaseVoidTestTags.AUTHORIZED_ROLE),
        )
    }
}

@Composable
private fun NegativeStockWarning() {
    StatusCard(
        statusLabel = stringResource(Res.string.purchase_void_negative_label),
        title = stringResource(Res.string.purchase_void_negative_title),
        message = stringResource(Res.string.purchase_void_negative_message),
        tone = StatusTone.WARNING,
        iconRes = Res.drawable.ic_warning,
        modifier = Modifier.testTag(PurchaseVoidTestTags.NEGATIVE_WARNING),
    )
}

@Composable
private fun PurchaseVoidImpactCard(
    impact: PurchaseVoidImpact,
    modifier: Modifier = Modifier,
) {
    val spacing = FacturaStockDesign.spacing
    val container = if (impact.becomesNegative) {
        FacturaStockDesign.semanticColors.warningContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    val content = if (impact.becomesNegative) {
        FacturaStockDesign.semanticColors.onWarningContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
        border = BorderStroke(spacing.borderThin, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = impact.productName,
                    modifier = Modifier.weight(1f),
                    color = content,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (impact.becomesNegative) {
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = FacturaStockDesign.semanticColors.warning,
                        contentColor = FacturaStockDesign.semanticColors.onWarning,
                    ) {
                        Text(
                            text = stringResource(Res.string.purchase_void_negative_label),
                            modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xxs),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
            Text(
                text = stringResource(
                    Res.string.purchase_void_impact_location,
                    impact.locationName,
                ),
                color = content,
            )
            Text(
                text = stringResource(
                    Res.string.purchase_void_impact_current,
                    impact.currentQuantity.formatQuantity(),
                    impact.unitCode,
                ),
                color = content,
            )
            Text(
                text = stringResource(
                    Res.string.purchase_void_impact_reversal,
                    impact.reversalQuantity.formatSignedQuantity(),
                    impact.unitCode,
                ),
                color = content,
            )
            Text(
                text = stringResource(
                    Res.string.purchase_void_impact_result,
                    impact.resultingQuantity.formatQuantity(),
                    impact.unitCode,
                ),
                color = content,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun PurchaseVoidReasonField(
    reason: String,
    isError: Boolean,
    enabled: Boolean,
    onReasonChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = reason,
        onValueChange = onReasonChange,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(PurchaseVoidTestTags.REASON),
        label = { Text(stringResource(Res.string.purchase_void_reason_label)) },
        supportingText = {
            if (isError) {
                Text(
                    text = stringResource(
                        Res.string.purchase_void_reason_error,
                        PurchaseVoidRequest.MIN_REASON_LENGTH,
                        PurchaseVoidRequest.MAX_REASON_LENGTH,
                    ),
                )
            } else {
                Column {
                    Text(
                        text = stringResource(
                            Res.string.purchase_void_reason_help,
                            PurchaseVoidRequest.MIN_REASON_LENGTH,
                            PurchaseVoidRequest.MAX_REASON_LENGTH,
                        ),
                    )
                    Text(
                        text = stringResource(
                            Res.string.purchase_void_reason_counter,
                            reason.trim().length,
                            PurchaseVoidRequest.MAX_REASON_LENGTH,
                        ),
                    )
                }
            }
        },
        isError = isError,
        enabled = enabled,
        minLines = 3,
        maxLines = 6,
    )
}

@Composable
private fun PurchaseVoidConfirmation(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val spacing = FacturaStockDesign.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = spacing.minimumTouchTarget)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = onCheckedChange,
            )
            .testTag(PurchaseVoidTestTags.CONFIRMATION)
            .padding(vertical = spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
        )
        Text(
            text = stringResource(Res.string.purchase_void_confirmation),
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun PurchaseVoidFailureCard(failure: PurchaseVoidContract.Failure) {
    StatusCard(
        statusLabel = stringResource(Res.string.purchase_void_failure_status),
        title = stringResource(Res.string.purchase_void_failure_title),
        message = stringResource(failure.messageRes()),
        tone = if (failure == PurchaseVoidContract.Failure.IMPACT_CHANGED) {
            StatusTone.WARNING
        } else {
            StatusTone.ERROR
        },
        iconRes = Res.drawable.ic_warning,
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .semantics { heading() }
            .testTag(PurchaseVoidTestTags.IMPACTS),
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.titleLarge,
    )
}

private fun PurchaseOverrideRole.labelRes(): StringResource = when (this) {
    PurchaseOverrideRole.OWNER -> Res.string.purchase_void_role_owner
    PurchaseOverrideRole.MANAGER -> Res.string.purchase_void_role_manager
    PurchaseOverrideRole.OPERATOR -> Res.string.purchase_void_role_operator
}

private fun PurchaseVoidContract.Failure.messageRes(): StringResource = when (this) {
    PurchaseVoidContract.Failure.INVALID_PURCHASE_ID,
    PurchaseVoidContract.Failure.NOT_FOUND,
    -> Res.string.purchase_void_failure_not_found
    PurchaseVoidContract.Failure.LOAD_FAILED -> Res.string.purchase_void_failure_load
    PurchaseVoidContract.Failure.SUBMIT_FAILED -> Res.string.purchase_void_failure_submit
    PurchaseVoidContract.Failure.STORAGE_FULL -> Res.string.storage_full_recoverable_message
    PurchaseVoidContract.Failure.NO_ACTIVE_BUSINESS ->
        Res.string.purchase_void_failure_no_business
    PurchaseVoidContract.Failure.NOT_POSTED -> Res.string.purchase_void_failure_not_posted
    PurchaseVoidContract.Failure.UNAUTHORIZED -> Res.string.purchase_void_failure_unauthorized
    PurchaseVoidContract.Failure.CONFIRMATION_REQUIRED ->
        Res.string.purchase_void_failure_confirmation
    PurchaseVoidContract.Failure.INVALID_REASON -> Res.string.purchase_void_failure_reason
    PurchaseVoidContract.Failure.IMPACT_CHANGED ->
        Res.string.purchase_void_failure_impact_changed
    PurchaseVoidContract.Failure.RETRYABLE_CONFLICT ->
        Res.string.purchase_void_failure_conflict
}

private fun PurchaseVoidContract.Failure.canRetry(): Boolean = when (this) {
    PurchaseVoidContract.Failure.LOAD_FAILED,
    PurchaseVoidContract.Failure.SUBMIT_FAILED,
    PurchaseVoidContract.Failure.STORAGE_FULL,
    PurchaseVoidContract.Failure.RETRYABLE_CONFLICT,
    -> true
    else -> false
}

private fun BigDecimal.formatQuantity(): String = stripTrailingZeros().toPlainString()

private fun BigDecimal.formatSignedQuantity(): String {
    val formatted = formatQuantity()
    return if (signum() > 0) "+$formatted" else formatted
}
