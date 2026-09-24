package com.facturastock.app.feature.debtors

import org.jetbrains.compose.resources.StringResource

import com.facturastock.app.resources.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import com.facturastock.app.domain.model.DebtDetail
import com.facturastock.app.domain.model.DebtLine
import com.facturastock.app.domain.model.DebtPayment
import com.facturastock.app.domain.model.DebtPaymentMethod
import com.facturastock.app.domain.model.DebtStatus
import com.facturastock.app.domain.model.DebtSummary
import com.facturastock.app.ui.components.EmptyState
import com.facturastock.app.ui.components.FacturaStockDialog
import com.facturastock.app.ui.components.FacturaStockPrimaryButton
import com.facturastock.app.ui.components.FacturaStockSecondaryButton
import com.facturastock.app.ui.components.StatusCard
import com.facturastock.app.ui.components.StatusTone
import com.facturastock.app.ui.format.formatForDisplay
import com.facturastock.app.ui.theme.FacturaStockDesign

@Composable
fun DebtorsListScreen(
    state: DebtorsContract.State,
    onAction: (DebtorsContract.Action) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FacturaStockDesign.spacing
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag(DebtorsTestTags.LIST_SCREEN),
        contentPadding = PaddingValues(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        item(key = "new_debt", contentType = "primary_action") {
            FacturaStockPrimaryButton(
                text = stringResource(Res.string.action_new_debt),
                onClick = { onAction(DebtorsContract.Action.NewDebtSelected) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(DebtorsTestTags.NEW_DEBT),
                leadingIconRes = Res.drawable.ic_debtors,
            )
        }

        state.totalOpenBalance?.let { total ->
            item(key = "total_open_balance", contentType = "summary") {
                StatusCard(
                    statusLabel = stringResource(Res.string.debtors_balance_status),
                    title = total.formatForDisplay(),
                    message = stringResource(Res.string.debtors_balance_message),
                    tone = if (total.minorUnits > 0L) StatusTone.WARNING else StatusTone.SUCCESS,
                    iconRes = Res.drawable.ic_debtors,
                    modifier = Modifier.testTag(DebtorsTestTags.TOTAL_BALANCE),
                )
            }
        }

        item(key = "search", contentType = "search") {
            OutlinedTextField(
                value = state.query,
                onValueChange = { onAction(DebtorsContract.Action.SearchChanged(it)) },
                label = { Text(stringResource(Res.string.debtors_search_label)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(DebtorsTestTags.SEARCH),
            )
        }

        item(key = "filters", contentType = "filters") {
            DebtStatusFilters(
                selected = state.statusFilter,
                onSelected = { onAction(DebtorsContract.Action.StatusFilterChanged(it)) },
            )
        }

        if (state.debts.isEmpty()) {
            item(key = "empty", contentType = "empty") {
                val hasFilters = state.query.isNotBlank() ||
                    state.statusFilter != DebtorsContract.StatusFilter.OPEN
                EmptyState(
                    title = stringResource(
                        if (hasFilters) {
                            Res.string.debtors_empty_filtered_title
                        } else {
                            Res.string.debtors_empty_title
                        },
                    ),
                    message = stringResource(
                        if (hasFilters) {
                            Res.string.debtors_empty_filtered_message
                        } else {
                            Res.string.debtors_empty_message
                        },
                    ),
                    iconRes = Res.drawable.ic_debtors,
                    modifier = Modifier.testTag(DebtorsTestTags.EMPTY),
                )
            }
        } else {
            items(
                items = state.debts,
                key = { it.debtId.value },
                contentType = { "debt" },
            ) { debt ->
                DebtSummaryCard(
                    debt = debt,
                    onClick = { onAction(DebtorsContract.Action.DebtSelected(debt.debtId)) },
                )
            }
        }
    }
}

@Composable
private fun DebtStatusFilters(
    selected: DebtorsContract.StatusFilter,
    onSelected: (DebtorsContract.StatusFilter) -> Unit,
) {
    val spacing = FacturaStockDesign.spacing
    LazyRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
        items(DebtorsContract.StatusFilter.entries) { filter ->
            FilterChip(
                selected = selected == filter,
                onClick = { onSelected(filter) },
                label = { Text(stringResource(filter.labelRes())) },
                modifier = Modifier.testTag(
                    when (filter) {
                        DebtorsContract.StatusFilter.OPEN -> DebtorsTestTags.FILTER_OPEN
                        DebtorsContract.StatusFilter.PAID -> DebtorsTestTags.FILTER_PAID
                        DebtorsContract.StatusFilter.ALL -> DebtorsTestTags.FILTER_ALL
                    },
                ),
            )
        }
    }
}

@Composable
private fun DebtSummaryCard(
    debt: DebtSummary,
    onClick: () -> Unit,
) {
    val spacing = FacturaStockDesign.spacing
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { role = Role.Button }
            .testTag(DebtorsTestTags.debt(debt.debtId.value)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(spacing.borderThin, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = debt.debtorName,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                DebtStatusBadge(debt.status)
            }
            Text(
                text = stringResource(
                    Res.string.debtors_card_balance,
                    debt.balance.formatForDisplay(),
                ),
                color = if (debt.status == DebtStatus.OPEN) {
                    FacturaStockDesign.semanticColors.warning
                } else {
                    FacturaStockDesign.semanticColors.success
                },
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(
                    Res.string.debtors_card_original,
                    debt.originalAmount.formatForDisplay(),
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = pluralStringResource(
                    Res.plurals.debtors_card_products,
                    debt.lineCount,
                    debt.lineCount,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(
                    Res.string.debtors_card_updated,
                    debt.updatedAt.formatForDisplay(),
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
fun DebtDetailScreen(
    state: DebtorsContract.State,
    onAction: (DebtorsContract.Action) -> Unit,
    modifier: Modifier = Modifier,
) {
    val detail = requireNotNull(state.detail)
    val debt = detail.debt
    val spacing = FacturaStockDesign.spacing
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag(DebtorsTestTags.DETAIL_SCREEN),
        contentPadding = PaddingValues(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        item(key = "summary", contentType = "summary") {
            DebtDetailSummary(debt)
        }

        if (debt.status == DebtStatus.OPEN) {
            item(key = "payment_action", contentType = "primary_action") {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    FacturaStockPrimaryButton(
                        text = stringResource(if (state.isSavingPayment) Res.string.debt_payment_saving else Res.string.debt_payment_action),
                        onClick = { onAction(DebtorsContract.Action.PaymentRequested) },
                        enabled = !state.isSavingPayment && state.paymentEditor == null && state.deleteTarget == null && !state.isDeletingDebt,
                        modifier = Modifier.fillMaxWidth().testTag(DebtorsTestTags.PAYMENT),
                    )
                    Text(
                        stringResource(Res.string.pago_directo_saldo_completo, debt.balance.formatForDisplay()),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag(DebtorsTestTags.PAYMENT_FULL_BALANCE),
                    )
                    TextButton(
                        onClick = { onAction(DebtorsContract.Action.PartialPaymentRequested) },
                        enabled = !state.isSavingPayment && state.deleteTarget == null && !state.isDeletingDebt,
                        modifier = Modifier.testTag(DebtorsTestTags.PARTIAL_PAYMENT),
                    ) {
                        Text(stringResource(Res.string.pago_directo_registrar_abono))
                    }
                }
            }
        }

        if (state.isSavingPayment && state.paymentEditor == null) {
            item(key = "payment_progress", contentType = "progress") {
                Column(Modifier.testTag(DebtorsTestTags.PAYMENT_PROGRESS).semantics { liveRegion = LiveRegionMode.Polite }) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(Res.string.debt_payment_saving))
                }
            }
        }
        if (state.paymentEditor == null) {
            fullPaymentFailureMessage(state.failure)?.let { message ->
                item(key = "payment_error", contentType = "error") {
                    Text(stringResource(message), color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag(DebtorsTestTags.PAYMENT_ERROR).semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
        }

        item(key = "delete_action", contentType = "secondary_action") {
            FacturaStockSecondaryButton(
                text = stringResource(Res.string.debt_delete_action),
                onClick = { onAction(DebtorsContract.Action.DeleteRequested) },
                enabled = !state.isSavingPayment && state.paymentEditor == null && state.deleteTarget == null && !state.isDeletingDebt,
                modifier = Modifier.fillMaxWidth().testTag(DebtorsTestTags.DELETE),
            )
        }

        item(key = "products_header", contentType = "section_header") {
            SectionTitle(stringResource(Res.string.debt_detail_products_title))
        }
        items(
            items = detail.lines,
            key = { it.saleLineId.value },
            contentType = { "debt_line" },
        ) { line ->
            DebtLineCard(line)
        }

        item(key = "payments_header", contentType = "section_header") {
            SectionTitle(stringResource(Res.string.debt_detail_payments_title))
        }
        if (detail.payments.isEmpty()) {
            item(key = "payments_empty", contentType = "empty") {
                Text(
                    text = stringResource(Res.string.debt_detail_payments_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            items(
                items = detail.payments,
                key = { it.paymentId.value },
                contentType = { "debt_payment" },
            ) { payment ->
                DebtPaymentCard(payment)
            }
        }
    }

    if (state.deleteTarget != null) {
        DebtDeleteDialog(state, onAction)
    }
    state.paymentEditor?.let { editor ->
        PaymentDialog(
            editor = editor,
            isSaving = state.isSavingPayment || state.isDeletingDebt,
            failure = state.failure,
            onAction = onAction,
        )
    }
}

@Composable
private fun DebtDeleteDialog(state: DebtorsContract.State, onAction: (DebtorsContract.Action) -> Unit) {
    val target = requireNotNull(state.deleteTarget)
    val preview = state.deletePreview
    val busy = state.isDeletingDebt || state.isLoadingDeletePreview
    val spacing = FacturaStockDesign.spacing
    FacturaStockDialog(
        title = stringResource(Res.string.debt_delete_title),
        message = stringResource(Res.string.debt_delete_message),
        confirmLabel = stringResource(when {
            state.isDeletingDebt -> Res.string.debt_delete_saving
            state.isLoadingDeletePreview -> Res.string.debt_delete_loading
            preview != null -> Res.string.debt_delete_confirm
            else -> Res.string.debt_delete_review
        }),
        dismissLabel = stringResource(Res.string.debt_delete_cancel),
        onConfirm = {
            onAction(if (preview != null) DebtorsContract.Action.DeleteConfirmed else DebtorsContract.Action.DeletePreviewRetry)
        },
        onDismiss = { onAction(DebtorsContract.Action.DeleteDismissed) },
        confirmEnabled = !busy && !state.isSavingPayment,
        dismissEnabled = !state.isDeletingDebt,
        modifier = Modifier.testTag(DebtorsTestTags.DELETE_DIALOG),
    ) {
        Column(
            modifier = Modifier.testTag(DebtorsTestTags.DELETE_IMPACT),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(stringResource(Res.string.debt_delete_debtor, target.debtorName), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(Res.string.debt_delete_identity, target.debtId.value), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(Res.string.debt_delete_date, target.createdAt.formatForDisplay()))
            Text(stringResource(Res.string.debt_delete_original, target.originalAmount.formatForDisplay()))
            Text(stringResource(Res.string.debt_delete_balance, target.balance.formatForDisplay()))
            Text(stringResource(Res.string.debt_delete_payment_alternative))
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(if (state.isDeletingDebt) Res.string.debt_delete_saving else Res.string.debt_delete_loading),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (preview != null) {
                HorizontalDivider()
                Text(stringResource(Res.string.debt_delete_stock), style = MaterialTheme.typography.titleSmall)
                preview.lines.forEach { line ->
                    Text(stringResource(Res.string.debt_delete_line, line.productName,
                        line.quantity.value.stripTrailingZeros().toPlainString(), line.unitCode, line.locationName))
                }
                HorizontalDivider()
                Text(stringResource(Res.string.debt_delete_refund, preview.refundAmount.formatForDisplay()))
                Text(stringResource(Res.string.debt_delete_history))
            }
            state.deleteFailure?.let { failure ->
                Text(stringResource(failure.messageRes()), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(DebtorsTestTags.DELETE_ERROR).semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
    }
}

private fun DebtorsContract.DeleteFailure.messageRes(): StringResource = when (this) {
    DebtorsContract.DeleteFailure.LOAD_FAILED -> Res.string.debt_delete_error_load
    DebtorsContract.DeleteFailure.OPERATION_FAILED -> Res.string.debt_delete_error_operation
    DebtorsContract.DeleteFailure.STALE -> Res.string.debt_delete_error_stale
    DebtorsContract.DeleteFailure.CONTEXT_CHANGED -> Res.string.debt_delete_error_context
    DebtorsContract.DeleteFailure.NO_ACTIVE_BUSINESS -> Res.string.debt_delete_error_business
    DebtorsContract.DeleteFailure.NOT_FOUND -> Res.string.debt_delete_error_not_found
    DebtorsContract.DeleteFailure.UNAUTHORIZED -> Res.string.debt_delete_error_unauthorized
    DebtorsContract.DeleteFailure.SHARED_BUSINESS_UNSUPPORTED -> Res.string.debt_delete_error_shared
    DebtorsContract.DeleteFailure.INVALID_HISTORY -> Res.string.debt_delete_error_history
}

@Composable
private fun DebtDetailSummary(debt: DebtSummary) {
    val spacing = FacturaStockDesign.spacing
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = debt.debtorName,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall,
                )
                DebtStatusBadge(debt.status)
            }
            Text(
                text = stringResource(
                    Res.string.debt_detail_balance,
                    debt.balance.formatForDisplay(),
                ),
                color = if (debt.status == DebtStatus.OPEN) {
                    FacturaStockDesign.semanticColors.warning
                } else {
                    FacturaStockDesign.semanticColors.success
                },
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(
                    Res.string.debt_detail_original,
                    debt.originalAmount.formatForDisplay(),
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(
                    Res.string.debt_detail_created,
                    debt.createdAt.formatForDisplay(),
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DebtLineCard(line: DebtLine) {
    val spacing = FacturaStockDesign.spacing
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(spacing.borderThin, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xxs),
        ) {
            Text(line.productName, style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(
                    Res.string.debt_detail_line_quantity_price,
                    line.quantity.value.stripTrailingZeros().toPlainString(),
                    line.unitPrice.formatForDisplay(),
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(
                    Res.string.debt_detail_line_total,
                    line.lineTotal.formatForDisplay(),
                ),
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
}

@Composable
private fun DebtPaymentCard(payment: DebtPayment) {
    val spacing = FacturaStockDesign.spacing
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = FacturaStockDesign.semanticColors.successContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xxs),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = payment.amount.formatForDisplay(),
                    color = FacturaStockDesign.semanticColors.onSuccessContainer,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(payment.method.labelRes()),
                    color = FacturaStockDesign.semanticColors.onSuccessContainer,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Text(
                text = payment.occurredAt.formatForDisplay(),
                color = FacturaStockDesign.semanticColors.onSuccessContainer,
                style = MaterialTheme.typography.bodySmall,
            )
            payment.reference?.let { reference ->
                Text(
                    text = stringResource(Res.string.debt_payment_reference_value, reference),
                    color = FacturaStockDesign.semanticColors.onSuccessContainer,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            payment.note?.let { note ->
                Text(
                    text = note,
                    color = FacturaStockDesign.semanticColors.onSuccessContainer,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            HorizontalDivider(
                color = FacturaStockDesign.semanticColors.onSuccessContainer.copy(alpha = 0.3f),
            )
            Text(
                text = stringResource(
                    Res.string.debt_payment_balance_after,
                    payment.balanceAfter.formatForDisplay(),
                ),
                color = FacturaStockDesign.semanticColors.onSuccessContainer,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun PaymentDialog(
    editor: DebtorsContract.PaymentEditor,
    isSaving: Boolean,
    failure: DebtorsContract.Failure?,
    onAction: (DebtorsContract.Action) -> Unit,
) {
    val spacing = FacturaStockDesign.spacing
    FacturaStockDialog(
        title = stringResource(Res.string.debt_payment_dialog_title),
        message = stringResource(
            Res.string.debt_payment_dialog_message,
            editor.balance.formatForDisplay(),
        ),
        confirmLabel = stringResource(
            if (isSaving) Res.string.debt_payment_saving else Res.string.debt_payment_confirm,
        ),
        dismissLabel = stringResource(Res.string.action_cancel),
        onConfirm = { onAction(DebtorsContract.Action.PaymentConfirmed) },
        onDismiss = { onAction(DebtorsContract.Action.PaymentDismissed) },
        confirmEnabled = !isSaving && editor.isValid,
        dismissEnabled = !isSaving,
        modifier = Modifier.testTag(DebtorsTestTags.PAYMENT_DIALOG),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            OutlinedTextField(
                value = editor.amountInput,
                onValueChange = { onAction(DebtorsContract.Action.PaymentAmountChanged(it)) },
                label = { Text(stringResource(Res.string.debt_payment_amount_label)) },
                supportingText = if (editor.submitAttempted && editor.amount == null) {
                    { Text(stringResource(Res.string.debt_payment_amount_error)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                enabled = !isSaving,
                isError = editor.submitAttempted && editor.amount == null,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(DebtorsTestTags.PAYMENT_AMOUNT),
            )
            Text(
                text = stringResource(Res.string.debt_payment_method_label),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleSmall,
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                items(DebtPaymentMethod.entries) { method ->
                    FilterChip(
                        selected = editor.method == method,
                        onClick = {
                            onAction(DebtorsContract.Action.PaymentMethodChanged(method))
                        },
                        enabled = !isSaving,
                        label = { Text(stringResource(method.labelRes())) },
                        modifier = Modifier.testTag(
                            DebtorsTestTags.paymentMethod(method.name.lowercase()),
                        ),
                    )
                }
            }
            OutlinedTextField(
                value = editor.referenceInput,
                onValueChange = { onAction(DebtorsContract.Action.PaymentReferenceChanged(it)) },
                label = { Text(stringResource(Res.string.debt_payment_reference_label)) },
                singleLine = true,
                enabled = !isSaving,
                isError = editor.referenceInput.length > DebtorsContract.MAX_REFERENCE_LENGTH,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(DebtorsTestTags.PAYMENT_REFERENCE),
            )
            OutlinedTextField(
                value = editor.noteInput,
                onValueChange = { onAction(DebtorsContract.Action.PaymentNoteChanged(it)) },
                label = { Text(stringResource(Res.string.debt_payment_note_label)) },
                enabled = !isSaving,
                isError = editor.noteInput.length > DebtorsContract.MAX_NOTE_LENGTH,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(DebtorsTestTags.PAYMENT_NOTE),
            )
            paymentFailureMessage(failure)?.let { messageRes ->
                Text(
                    text = stringResource(messageRes),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun DebtStatusBadge(status: DebtStatus) {
    val colors = if (status == DebtStatus.OPEN) {
        FacturaStockDesign.semanticColors.warningContainer to
            FacturaStockDesign.semanticColors.onWarningContainer
    } else {
        FacturaStockDesign.semanticColors.successContainer to
            FacturaStockDesign.semanticColors.onSuccessContainer
    }
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = colors.first,
        contentColor = colors.second,
    ) {
        Text(
            text = stringResource(status.labelRes()),
            modifier = Modifier.padding(
                horizontal = FacturaStockDesign.spacing.sm,
                vertical = FacturaStockDesign.spacing.xs,
            ),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.titleLarge,
    )
}

private fun DebtorsContract.StatusFilter.labelRes(): StringResource = when (this) {
    DebtorsContract.StatusFilter.OPEN -> Res.string.debtors_filter_open
    DebtorsContract.StatusFilter.PAID -> Res.string.debtors_filter_paid
    DebtorsContract.StatusFilter.ALL -> Res.string.debtors_filter_all
}

private fun DebtStatus.labelRes(): StringResource = when (this) {
    DebtStatus.OPEN -> Res.string.debt_status_open
    DebtStatus.PAID -> Res.string.debt_status_paid
}

private fun DebtPaymentMethod.labelRes(): StringResource = when (this) {
    DebtPaymentMethod.CASH -> Res.string.debt_payment_method_cash
    DebtPaymentMethod.YAPE -> Res.string.debt_payment_method_yape
    DebtPaymentMethod.PLIN -> Res.string.debt_payment_method_plin
    DebtPaymentMethod.BANK_TRANSFER -> Res.string.debt_payment_method_bank_transfer
    DebtPaymentMethod.OTHER -> Res.string.debt_payment_method_other
}

private fun paymentFailureMessage(failure: DebtorsContract.Failure?): StringResource? = when (failure) {
    DebtorsContract.Failure.INVALID_PAYMENT -> Res.string.debt_payment_error_invalid
    DebtorsContract.Failure.STALE_DEBT -> Res.string.debt_payment_error_stale
    DebtorsContract.Failure.PAYMENT_EXCEEDS_BALANCE -> Res.string.debt_payment_error_exceeds
    DebtorsContract.Failure.ONLINE_REQUIRED -> Res.string.debt_payment_error_online_required
    DebtorsContract.Failure.REMOTE_REJECTED -> Res.string.debt_payment_error_remote_rejected
    DebtorsContract.Failure.SAVE_PAYMENT_FAILED -> Res.string.debt_payment_error_save
    else -> null
}

private fun fullPaymentFailureMessage(failure: DebtorsContract.Failure?): StringResource? = when (failure) {
    DebtorsContract.Failure.STALE_DEBT -> Res.string.pago_directo_error_actualizado
    DebtorsContract.Failure.SAVE_PAYMENT_FAILED -> Res.string.pago_directo_error_guardar
    DebtorsContract.Failure.DEBT_NOT_FOUND -> Res.string.debt_not_found_message
    else -> paymentFailureMessage(failure)
}
