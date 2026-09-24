package com.facturastock.app.feature.preparation

import org.jetbrains.compose.resources.StringResource

import com.facturastock.app.resources.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import com.facturastock.app.domain.model.PurchaseDuplicateKind
import com.facturastock.app.domain.model.PurchaseDuplicateMatch
import com.facturastock.app.domain.model.PurchaseDuplicateOverride
import com.facturastock.app.domain.model.PurchaseDuplicateProbe
import com.facturastock.app.domain.model.PurchaseDuplicateReason
import com.facturastock.app.ui.components.FacturaStockPrimaryButton
import com.facturastock.app.ui.components.FacturaStockSecondaryButton
import com.facturastock.app.ui.components.StatusCard
import com.facturastock.app.ui.components.StatusTone
import com.facturastock.app.ui.format.formatForDisplay
import com.facturastock.app.ui.format.formatSignedForDisplay
import com.facturastock.app.ui.theme.FacturaStockDesign

@Composable
internal fun PreparationScreen(
    state: PreparationContract.State,
    onAction: (PreparationContract.Action) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FacturaStockDesign.spacing
    val match = state.duplicateAssessment?.match
    val retryableFailure = state.failure?.allowsRetry == true

    LazyColumn(
        modifier = modifier.testTag(PreparationTestTags.LIST),
        contentPadding = PaddingValues(spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        item {
            Text(
                text = stringResource(Res.string.purchase_confirmation_title),
                modifier = Modifier.semantics { heading() },
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.headlineLarge,
            )
        }

        state.duplicateAssessment?.probe?.let { probe ->
            item { PreparedSnapshotCard(probe) }
        }

        state.failure?.let { failure ->
            item {
                StatusCard(
                    statusLabel = stringResource(Res.string.purchase_duplicate_status),
                    title = failure.title(),
                    message = failure.message(),
                    tone = StatusTone.ERROR,
                    iconRes = Res.drawable.ic_warning,
                )
            }
        }

        if (match == null && state.failure == null) {
            item {
                StatusCard(
                    statusLabel = stringResource(Res.string.purchase_duplicate_status_clear),
                    title = stringResource(Res.string.purchase_confirmation_title),
                    message = stringResource(Res.string.purchase_confirmation_message),
                    tone = StatusTone.SUCCESS,
                    iconRes = Res.drawable.ic_receipt,
                )
            }
            item {
                FacturaStockPrimaryButton(
                    text = stringResource(Res.string.action_confirm_purchase),
                    onClick = { onAction(PreparationContract.Action.Confirm) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (match != null) {
            item { DuplicateMatchCard(match) }
            item {
                FacturaStockPrimaryButton(
                    text = stringResource(Res.string.purchase_duplicate_open_existing),
                    onClick = { onAction(PreparationContract.Action.OpenExistingSelected) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (!retryableFailure && match.kind == PurchaseDuplicateKind.EXACT) {
                item {
                    FacturaStockSecondaryButton(
                        text = stringResource(Res.string.purchase_duplicate_override_action),
                        onClick = { onAction(PreparationContract.Action.RequestOverride) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(PreparationTestTags.OVERRIDE_ACTION),
                    )
                }
            } else if (!retryableFailure) {
                item {
                    FacturaStockSecondaryButton(
                        text = stringResource(Res.string.action_confirm_purchase),
                        onClick = { onAction(PreparationContract.Action.Confirm) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        if (retryableFailure) {
            item {
                FacturaStockPrimaryButton(
                    text = stringResource(Res.string.action_retry),
                    onClick = { onAction(PreparationContract.Action.Retry) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(PreparationTestTags.RETRY),
                )
            }
        }

        item {
            FacturaStockSecondaryButton(
                text = stringResource(Res.string.action_previous_step),
                onClick = { onAction(PreparationContract.Action.BackSelected) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (state.showOverrideDialog && match != null) {
        DuplicateOverrideDialog(state = state, onAction = onAction)
    }
}

@Composable
private fun PreparedSnapshotCard(probe: PurchaseDuplicateProbe) {
    StatusCard(
        statusLabel = stringResource(Res.string.purchase_confirmation_snapshot_status),
        title = stringResource(Res.string.purchase_confirmation_snapshot_title),
        message = stringResource(Res.string.purchase_confirmation_snapshot_message),
        tone = StatusTone.INFO,
        iconRes = Res.drawable.ic_receipt,
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(FacturaStockDesign.spacing.xxs)) {
                Text(
                    stringResource(
                        Res.string.purchase_confirmation_snapshot_total,
                        probe.total.formatForDisplay(),
                    ),
                )
                probe.reconciliationAdjustment?.let { adjustment ->
                    Text(
                        stringResource(
                            Res.string.purchase_confirmation_snapshot_adjustment,
                            adjustment.amount.formatSignedForDisplay(),
                        ),
                    )
                    Text(
                        stringResource(
                            Res.string.purchase_confirmation_snapshot_reason,
                            adjustment.reason,
                        ),
                    )
                }
            }
        },
    )
}

@Composable
private fun DuplicateMatchCard(match: PurchaseDuplicateMatch) {
    val purchase = match.purchase
    val exact = match.kind == PurchaseDuplicateKind.EXACT
    val signalLabels = match.reasons
        .map { reason -> stringResource(reason.labelRes()) }
        .joinToString()
    StatusCard(
        statusLabel = stringResource(
            if (exact) Res.string.purchase_duplicate_exact_label
            else Res.string.purchase_duplicate_probable_label,
        ),
        title = stringResource(
            if (exact) Res.string.purchase_duplicate_exact_title
            else Res.string.purchase_duplicate_probable_title,
        ),
        message = stringResource(
            if (exact) Res.string.purchase_duplicate_exact_message
            else Res.string.purchase_duplicate_probable_message,
        ),
        tone = if (exact) StatusTone.ERROR else StatusTone.WARNING,
        iconRes = Res.drawable.ic_warning,
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(FacturaStockDesign.spacing.xxs)) {
                Text(stringResource(Res.string.purchase_duplicate_supplier, purchase.supplierLegalName))
                Text(stringResource(Res.string.purchase_duplicate_document, purchase.canonicalDocumentNumber))
                Text(
                    stringResource(
                        Res.string.purchase_duplicate_date,
                        purchase.issueDate.formatForDisplay(),
                    ),
                )
                Text(
                    stringResource(
                        Res.string.purchase_duplicate_total,
                        purchase.total.formatForDisplay(),
                    ),
                )
                Text(
                    stringResource(
                        Res.string.purchase_duplicate_signals,
                        signalLabels,
                    ),
                )
            }
        },
    )
}

@Composable
private fun DuplicateOverrideDialog(
    state: PreparationContract.State,
    onAction: (PreparationContract.Action) -> Unit,
) {
    val trimmedLength = state.overrideReason.trim().length
    val reasonInvalid = state.failure == PreparationContract.Failure.OVERRIDE_REASON_REQUIRED
    AlertDialog(
        onDismissRequest = { onAction(PreparationContract.Action.DismissOverride) },
        modifier = Modifier.testTag(PreparationTestTags.OVERRIDE_DIALOG),
        title = { Text(stringResource(Res.string.purchase_duplicate_override_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(FacturaStockDesign.spacing.sm)) {
                Text(stringResource(Res.string.purchase_duplicate_override_message))
                OutlinedTextField(
                    value = state.overrideReason,
                    onValueChange = {
                        onAction(PreparationContract.Action.OverrideReasonChanged(it))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(PreparationTestTags.OVERRIDE_REASON),
                    label = { Text(stringResource(Res.string.purchase_duplicate_override_reason)) },
                    supportingText = {
                        Text(
                            if (reasonInvalid) {
                                stringResource(Res.string.purchase_duplicate_override_reason_error)
                            } else {
                                stringResource(
                                    Res.string.purchase_duplicate_override_counter,
                                    state.overrideReason.length,
                                    PurchaseDuplicateOverride.MAX_REASON_LENGTH,
                                )
                            },
                        )
                    },
                    isError = reasonInvalid,
                    minLines = 3,
                    maxLines = 6,
                )
            }
        },
        confirmButton = {
            FacturaStockPrimaryButton(
                text = stringResource(Res.string.purchase_duplicate_override_confirm),
                onClick = { onAction(PreparationContract.Action.ConfirmOverride) },
                modifier = Modifier.testTag(PreparationTestTags.OVERRIDE_CONFIRM),
                enabled = trimmedLength >= PurchaseDuplicateOverride.MIN_REASON_LENGTH,
            )
        },
        dismissButton = {
            FacturaStockSecondaryButton(
                text = stringResource(Res.string.action_cancel),
                onClick = { onAction(PreparationContract.Action.DismissOverride) },
            )
        },
    )
}

@Composable
private fun PreparationContract.Failure.title(): String = stringResource(
    when (this) {
        PreparationContract.Failure.INVALID_DRAFT_ID -> Res.string.ocr_invalid_route_title
        PreparationContract.Failure.DUPLICATE_CHECK_FAILED,
        PreparationContract.Failure.OVERRIDE_REASON_REQUIRED,
        PreparationContract.Failure.OVERRIDE_NOT_AUTHORIZED,
        PreparationContract.Failure.OVERRIDE_FAILED,
        PreparationContract.Failure.DUPLICATE_CHANGED,
        -> Res.string.purchase_duplicate_error_title
        PreparationContract.Failure.PREPARATION_FAILED,
        PreparationContract.Failure.STORAGE_FULL,
        PreparationContract.Failure.CONFIRMATION_BLOCKED,
        PreparationContract.Failure.PREPARED_PURCHASE_CHANGED,
        PreparationContract.Failure.RETRYABLE_CONFLICT,
        -> Res.string.purchase_confirmation_error_title
    },
)

@Composable
private fun PreparationContract.Failure.message(): String = stringResource(
    when (this) {
        PreparationContract.Failure.INVALID_DRAFT_ID -> Res.string.ocr_invalid_route_message
        PreparationContract.Failure.DUPLICATE_CHECK_FAILED -> Res.string.purchase_duplicate_check_error
        PreparationContract.Failure.OVERRIDE_REASON_REQUIRED -> Res.string.purchase_duplicate_override_reason_error
        PreparationContract.Failure.OVERRIDE_NOT_AUTHORIZED -> Res.string.purchase_duplicate_override_unauthorized
        PreparationContract.Failure.OVERRIDE_FAILED -> Res.string.purchase_duplicate_publish_error
        PreparationContract.Failure.DUPLICATE_CHANGED -> Res.string.purchase_duplicate_changed
        PreparationContract.Failure.PREPARATION_FAILED -> Res.string.purchase_confirmation_publish_error
        PreparationContract.Failure.STORAGE_FULL -> Res.string.storage_full_recoverable_message
        PreparationContract.Failure.CONFIRMATION_BLOCKED -> Res.string.purchase_confirmation_blocked
        PreparationContract.Failure.PREPARED_PURCHASE_CHANGED ->
            Res.string.purchase_confirmation_prepared_changed
        PreparationContract.Failure.RETRYABLE_CONFLICT ->
            Res.string.purchase_confirmation_retryable_conflict
    },
)

private val PreparationContract.Failure.allowsRetry: Boolean
    get() = when (this) {
        PreparationContract.Failure.DUPLICATE_CHECK_FAILED,
        PreparationContract.Failure.DUPLICATE_CHANGED,
        PreparationContract.Failure.PREPARATION_FAILED,
        PreparationContract.Failure.STORAGE_FULL,
        PreparationContract.Failure.RETRYABLE_CONFLICT,
        -> true
        PreparationContract.Failure.INVALID_DRAFT_ID,
        PreparationContract.Failure.OVERRIDE_REASON_REQUIRED,
        PreparationContract.Failure.OVERRIDE_NOT_AUTHORIZED,
        PreparationContract.Failure.OVERRIDE_FAILED,
        PreparationContract.Failure.CONFIRMATION_BLOCKED,
        PreparationContract.Failure.PREPARED_PURCHASE_CHANGED,
        -> false
    }

private fun PurchaseDuplicateReason.labelRes(): StringResource = when (this) {
        PurchaseDuplicateReason.SAME_BUSINESS -> Res.string.purchase_duplicate_signal_business
        PurchaseDuplicateReason.SAME_SUPPLIER -> Res.string.purchase_duplicate_signal_supplier
        PurchaseDuplicateReason.SAME_DOCUMENT_TYPE -> Res.string.purchase_duplicate_signal_type
        PurchaseDuplicateReason.SAME_DOCUMENT_NUMBER -> Res.string.purchase_duplicate_signal_number
        PurchaseDuplicateReason.CORRELATIVE_PADDING_VARIANT -> Res.string.purchase_duplicate_signal_padding
        PurchaseDuplicateReason.SAME_SERIES -> Res.string.purchase_duplicate_signal_series
        PurchaseDuplicateReason.SAME_ISSUE_DATE -> Res.string.purchase_duplicate_signal_date
        PurchaseDuplicateReason.SAME_TOTAL -> Res.string.purchase_duplicate_signal_total
        PurchaseDuplicateReason.SAME_IMAGE_HASH -> Res.string.purchase_duplicate_signal_image
    }
