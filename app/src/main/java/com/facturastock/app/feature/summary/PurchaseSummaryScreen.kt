package com.facturastock.app.feature.summary

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.facturastock.app.R
import com.facturastock.app.domain.model.InvoiceHeaderEditField
import com.facturastock.app.domain.model.InvoiceLineEditField
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.PrepareBlockerCode
import com.facturastock.app.domain.model.PreparedPurchase
import com.facturastock.app.domain.model.PreparedPurchaseLine
import com.facturastock.app.domain.model.PurchaseDocumentType
import com.facturastock.app.domain.model.PurchaseReconciliationAdjustment
import com.facturastock.app.domain.usecase.WARNING_LINES_TOTAL_DIFFERENCE
import com.facturastock.app.feature.summary.PurchaseSummaryContract.Action
import com.facturastock.app.feature.summary.PurchaseSummaryContract.BlockerItem
import com.facturastock.app.feature.summary.PurchaseSummaryContract.Failure
import com.facturastock.app.feature.summary.PurchaseSummaryContract.FinanceSummary
import com.facturastock.app.feature.summary.PurchaseSummaryContract.Mode
import com.facturastock.app.feature.summary.PurchaseSummaryContract.State
import com.facturastock.app.ui.components.FacturaStockPrimaryButton
import com.facturastock.app.ui.components.FacturaStockSecondaryButton
import com.facturastock.app.ui.components.RecoverableError
import com.facturastock.app.ui.format.formatForDisplay
import com.facturastock.app.ui.format.formatSignedForDisplay
import com.facturastock.app.ui.theme.FacturaStockDesign

/**
 * Resumen stateless: en modo edición enumera los bloqueos, los totales y la aceptación del
 * redondeo; en modo preparado muestra la instantánea congelada con sus advertencias aceptadas.
 */
@Composable
fun PurchaseSummaryScreen(
    state: State,
    onAction: (Action) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FacturaStockDesign.spacing

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .testTag(PurchaseSummaryTestTags.SCREEN),
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag(PurchaseSummaryTestTags.LIST),
            contentPadding = PaddingValues(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            item(key = "summary_header", contentType = "header") {
                SummaryHeader(mode = state.mode)
            }
            if (state.failure == Failure.ACTION_FAILED || state.failure == Failure.STORAGE_FULL) {
                item(key = "summary_failure", contentType = "failure") {
                    RecoverableError(
                        title = stringResource(R.string.summary_action_failed_title),
                        message = stringResource(
                            if (state.failure == Failure.STORAGE_FULL) {
                                R.string.storage_full_recoverable_message
                            } else {
                                R.string.summary_action_failed_message
                            },
                        ),
                        actionLabel = stringResource(R.string.action_retry),
                        onAction = { onAction(Action.Retry) },
                        modifier = Modifier.testTag(PurchaseSummaryTestTags.FAILURE_BANNER),
                    )
                }
            }
            when (state.mode) {
                Mode.EDITING -> editingContent(state = state, onAction = onAction)
                Mode.PREPARED -> preparedContent(state = state)
            }
        }

        BottomBar(state = state, onAction = onAction)
    }
}

@Composable
private fun SummaryHeader(mode: Mode) {
    val spacing = FacturaStockDesign.spacing
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.xxs),
    ) {
        Text(
            text = stringResource(R.string.summary_title),
            modifier = Modifier.semantics { heading() },
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = stringResource(
                if (mode == Mode.PREPARED) {
                    R.string.summary_prepared_message
                } else {
                    R.string.summary_edit_message
                },
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun LazyListScope.editingContent(
    state: State,
    onAction: (Action) -> Unit,
) {
    val visibleBlockers = state.visibleBlockers
    if (visibleBlockers.isEmpty()) {
        item(key = "summary_all_clear", contentType = "status") {
            Text(
                text = stringResource(R.string.summary_all_clear),
                color = FacturaStockDesign.semanticColors.success,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    } else {
        item(key = "summary_blockers_title", contentType = "header") {
            Text(
                text = stringResource(R.string.summary_blockers_title),
                modifier = Modifier.semantics { heading() },
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        itemsIndexed(
            items = visibleBlockers,
            key = { index, blocker -> "blocker_${blocker.code}_$index" },
            contentType = { _, _ -> "blocker" },
        ) { index, blocker ->
            BlockerRow(
                blocker = blocker,
                modifier = Modifier.testTag(PurchaseSummaryTestTags.blocker(index)),
            )
        }
    }
    state.finance?.let { finance ->
        item(key = "summary_finance", contentType = "finance") {
            FinanceCard(finance = finance)
        }
    }
    val roundingDifference = state.roundingDifference
    if (roundingDifference != null) {
        item(key = "summary_rounding", contentType = "rounding") {
            RoundingAcceptanceRow(
                accepted = state.roundingAccepted,
                difference = roundingDifference,
                reason = state.adjustmentReason,
                enabled = !state.isBusy,
                onAction = onAction,
            )
        }
    }
}

@Composable
private fun BlockerRow(
    blocker: BlockerItem,
    modifier: Modifier = Modifier,
) {
    val spacing = FacturaStockDesign.spacing
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(
            containerColor = FacturaStockDesign.semanticColors.warningContainer,
        ),
    ) {
        Text(
            text = blockerText(blocker),
            modifier = Modifier.padding(spacing.sm),
            color = FacturaStockDesign.semanticColors.onWarningContainer,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun FinanceCard(finance: FinanceSummary) {
    val spacing = FacturaStockDesign.spacing
    val pending = stringResource(R.string.line_review_summary_pending)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(PurchaseSummaryTestTags.FINANCE),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Text(
                text = stringResource(R.string.summary_finance_title),
                modifier = Modifier.semantics { heading() },
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
            )
            FinanceRow(
                labelRes = R.string.line_review_summary_sum_label,
                value = finance.lineSum.ifBlank { pending },
            )
            FinanceRow(
                labelRes = R.string.line_review_summary_invoice_label,
                value = finance.invoiceTotal.ifBlank { pending },
            )
            FinanceRow(
                labelRes = R.string.line_review_summary_difference_label,
                value = finance.difference.ifBlank { pending },
                emphasize = finance.hasDifference,
            )
        }
    }
}

@Composable
private fun FinanceRow(
    @StringRes labelRes: Int,
    value: String,
    emphasize: Boolean = false,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(labelRes),
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = value,
            color = if (emphasize) {
                FacturaStockDesign.semanticColors.warning
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun RoundingAcceptanceRow(
    accepted: Boolean,
    difference: String,
    reason: String,
    enabled: Boolean,
    onAction: (Action) -> Unit,
) {
    val spacing = FacturaStockDesign.spacing
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(PurchaseSummaryTestTags.ROUNDING)
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = spacing.minimumTouchTarget)
                    .toggleable(
                        value = accepted,
                        enabled = enabled,
                        role = Role.Checkbox,
                        onValueChange = { checked ->
                            onAction(Action.RoundingAcceptanceChanged(checked))
                        },
                    )
                    .testTag(PurchaseSummaryTestTags.ADJUSTMENT_CONFIRMATION)
                    .padding(vertical = spacing.xs),
                verticalAlignment = Alignment.Top,
            ) {
                Checkbox(
                    checked = accepted,
                    onCheckedChange = null,
                    enabled = enabled,
                )
                Spacer(modifier = Modifier.width(spacing.xs))
                Text(
                    text = stringResource(R.string.summary_rounding_accept, difference),
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            if (accepted) {
                val reasonIsValid = PurchaseReconciliationAdjustment.isValidReason(reason)
                OutlinedTextField(
                    value = reason,
                    onValueChange = { value -> onAction(Action.AdjustmentReasonChanged(value)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(PurchaseSummaryTestTags.ADJUSTMENT_REASON),
                    enabled = enabled,
                    label = { Text(stringResource(R.string.summary_adjustment_reason_label)) },
                    supportingText = {
                        Text(
                            if (reasonIsValid) {
                                stringResource(
                                    R.string.summary_adjustment_reason_count,
                                    reason.length,
                                    PurchaseReconciliationAdjustment.MAX_REASON_LENGTH,
                                )
                            } else {
                                stringResource(
                                    R.string.summary_adjustment_reason_requirement,
                                    PurchaseReconciliationAdjustment.MIN_REASON_LENGTH,
                                    PurchaseReconciliationAdjustment.MAX_REASON_LENGTH,
                                )
                            },
                        )
                    },
                    isError = !reasonIsValid,
                    minLines = 2,
                    maxLines = 4,
                )
            }
        }
    }
}

private fun LazyListScope.preparedContent(state: State) {
    val purchase = state.prepared ?: return
    item(key = "summary_prepared_card", contentType = "snapshot") {
        SnapshotCard(purchase = purchase)
    }
    item(key = "summary_lines_title", contentType = "header") {
        Text(
            text = stringResource(R.string.summary_lines_title),
            modifier = Modifier.semantics { heading() },
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
        )
    }
    items(
        items = purchase.lines,
        key = { line -> line.lineId.value },
        contentType = { "prepared_line" },
    ) { line ->
        PreparedLineRow(line = line)
    }
    item(key = "summary_totals", contentType = "totals") {
        PreparedTotalsCard(purchase = purchase)
    }
    if (purchase.acceptedWarnings.isNotEmpty()) {
        item(key = "summary_warnings_title", contentType = "header") {
            Text(
                text = stringResource(R.string.summary_warnings_title),
                modifier = Modifier.semantics { heading() },
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        itemsIndexed(
            items = purchase.acceptedWarnings,
            key = { index, warning -> "warning_${warning}_$index" },
            contentType = { _, _ -> "warning" },
        ) { _, warning ->
            WarningRow(warning = warning)
        }
    }
}

@Composable
private fun SnapshotCard(purchase: PreparedPurchase) {
    val spacing = FacturaStockDesign.spacing
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(PurchaseSummaryTestTags.PREPARED_CARD),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            SnapshotRow(
                labelRes = R.string.summary_supplier_label,
                value = purchase.supplierLegalName ?: purchase.supplierRuc,
            )
            SnapshotRow(
                labelRes = R.string.header_review_ruc_label,
                value = purchase.supplierRuc,
            )
            SnapshotRow(
                labelRes = R.string.summary_document_label,
                value = listOfNotNull(
                    purchase.documentType?.let { type -> stringResource(documentTypeLabelRes(type)) },
                    purchase.documentNumber,
                ).joinToString(separator = " · "),
            )
            SnapshotRow(
                labelRes = R.string.header_review_issue_date_label,
                value = purchase.issueDate.formatForDisplay(),
            )
            SnapshotRow(
                labelRes = R.string.header_review_currency_label,
                value = purchase.currency.value,
            )
            SnapshotRow(
                labelRes = R.string.summary_hash_label,
                value = purchase.logicalHash.take(HASH_VISIBLE_CHARS),
                valueTestTag = PurchaseSummaryTestTags.HASH,
            )
        }
    }
}

@Composable
private fun SnapshotRow(
    @StringRes labelRes: Int,
    value: String,
    valueTestTag: String? = null,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(labelRes),
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = value,
            modifier = if (valueTestTag != null) Modifier.testTag(valueTestTag) else Modifier,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PreparedLineRow(line: PreparedPurchaseLine) {
    val spacing = FacturaStockDesign.spacing
    val pending = stringResource(R.string.line_review_summary_pending)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(PurchaseSummaryTestTags.preparedLine(line.position)),
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(spacing.borderThin, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(spacing.sm)) {
            Text(
                text = line.description,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(
                        R.string.summary_line_quantity,
                        line.quantity.value.toPlainString(),
                    ),
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = line.lineTotal?.formatForDisplay() ?: pending,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Composable
private fun PreparedTotalsCard(purchase: PreparedPurchase) {
    val spacing = FacturaStockDesign.spacing
    val pending = stringResource(R.string.line_review_summary_pending)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(PurchaseSummaryTestTags.PREPARED_TOTALS),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Text(
                text = stringResource(R.string.summary_finance_title),
                modifier = Modifier.semantics { heading() },
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
            )
            FinanceRow(
                labelRes = R.string.header_review_subtotal_label,
                value = purchase.subtotal.displayOr(pending),
            )
            FinanceRow(
                labelRes = R.string.header_review_igv_label,
                value = purchase.tax.displayOr(pending),
            )
            FinanceRow(
                labelRes = R.string.header_review_other_charges_label,
                value = purchase.otherCharges.displayOr(pending),
            )
            purchase.reconciliationAdjustment?.let { adjustment ->
                FinanceRow(
                    labelRes = R.string.purchase_detail_adjustment,
                    value = adjustment.amount.formatSignedForDisplay(),
                    emphasize = true,
                )
                FinanceRow(
                    labelRes = R.string.purchase_detail_adjustment_reason,
                    value = adjustment.reason,
                )
            }
            FinanceRow(
                labelRes = R.string.header_review_total_label,
                value = purchase.total.formatForDisplay(),
                emphasize = true,
            )
        }
    }
}

@Composable
private fun WarningRow(warning: String) {
    val spacing = FacturaStockDesign.spacing
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(PurchaseSummaryTestTags.WARNINGS),
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(
            containerColor = FacturaStockDesign.semanticColors.warningContainer,
        ),
    ) {
        Text(
            text = when (warning) {
                WARNING_TOTAL_DIFFERENCE ->
                    stringResource(R.string.summary_warning_total_difference)

                WARNING_LINES_TOTAL_DIFFERENCE ->
                    stringResource(R.string.summary_warning_lines_total_difference)

                else -> stringResource(R.string.summary_warning_generic, warning)
            },
            modifier = Modifier.padding(spacing.sm),
            color = FacturaStockDesign.semanticColors.onWarningContainer,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun BottomBar(
    state: State,
    onAction: (Action) -> Unit,
) {
    val spacing = FacturaStockDesign.spacing
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = spacing.xs,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg, vertical = spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            when (state.mode) {
                Mode.EDITING -> FacturaStockPrimaryButton(
                    text = stringResource(R.string.summary_action_prepare),
                    onClick = { onAction(Action.PrepareSelected) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(PurchaseSummaryTestTags.PREPARE),
                    enabled = state.canPrepare,
                )

                Mode.PREPARED -> {
                    FacturaStockPrimaryButton(
                        text = stringResource(R.string.summary_action_register),
                        onClick = { onAction(Action.RegisterSelected) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(PurchaseSummaryTestTags.REGISTER),
                        enabled = !state.isBusy,
                    )
                    FacturaStockSecondaryButton(
                        text = stringResource(R.string.summary_action_reopen),
                        onClick = { onAction(Action.ReopenSelected) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(PurchaseSummaryTestTags.REOPEN),
                        enabled = !state.isBusy,
                    )
                }
            }
        }
    }
}

@Composable
private fun blockerText(blocker: BlockerItem): String = when (blocker.code) {
    PrepareBlockerCode.HEADER_MISSING ->
        stringResource(R.string.summary_blocker_header_missing)

    PrepareBlockerCode.HEADER_INVALID ->
        stringResource(R.string.summary_blocker_header_invalid, headerFieldLabel(blocker.detail))

    PrepareBlockerCode.CREDIT_NOTE_UNSUPPORTED ->
        stringResource(R.string.summary_blocker_credit_note_unsupported)

    PrepareBlockerCode.NO_LINES ->
        stringResource(R.string.summary_blocker_no_lines)

    PrepareBlockerCode.LINE_DESCRIPTION_MISSING ->
        lineBlockerText(R.string.summary_blocker_line_description_missing, blocker.linePosition)

    PrepareBlockerCode.LINE_QUANTITY_INVALID ->
        lineBlockerText(R.string.summary_blocker_line_quantity_invalid, blocker.linePosition)

    PrepareBlockerCode.LINE_AMOUNT_INVALID ->
        if (blocker.linePosition != null) {
            stringResource(
                R.string.summary_blocker_line_amount_invalid,
                blocker.linePosition,
                lineFieldLabel(blocker.detail),
            )
        } else {
            stringResource(R.string.summary_blocker_generic)
        }

    PrepareBlockerCode.LINE_REVIEW_PENDING ->
        lineBlockerText(R.string.summary_blocker_line_review_pending, blocker.linePosition)

    PrepareBlockerCode.LINE_TAX_DECISION_REQUIRED ->
        lineBlockerText(R.string.summary_blocker_line_tax_required, blocker.linePosition)

    PrepareBlockerCode.LINE_PRODUCT_MISSING ->
        lineBlockerText(R.string.summary_blocker_line_product_missing, blocker.linePosition)

    PrepareBlockerCode.LINE_PRODUCT_INVALID ->
        lineBlockerText(R.string.summary_blocker_line_product_invalid, blocker.linePosition)

    PrepareBlockerCode.LINE_PRODUCT_PROVENANCE_REQUIRED ->
        lineBlockerText(R.string.summary_blocker_line_product_provenance, blocker.linePosition)

    PrepareBlockerCode.LINE_SALE_PRICE_REQUIRED ->
        lineBlockerText(R.string.summary_blocker_line_sale_price_required, blocker.linePosition)

    PrepareBlockerCode.ROUNDING_ACCEPTANCE_REQUIRED ->
        stringResource(R.string.summary_blocker_rounding)

    PrepareBlockerCode.ADJUSTMENT_REASON_REQUIRED ->
        stringResource(R.string.summary_blocker_adjustment_reason)
}

@Composable
private fun lineBlockerText(@StringRes res: Int, linePosition: Int?): String =
    if (linePosition != null) {
        stringResource(res, linePosition)
    } else {
        stringResource(R.string.summary_blocker_generic)
    }

@Composable
private fun headerFieldLabel(detail: String?): String {
    val field = detail?.let { name ->
        runCatching { InvoiceHeaderEditField.valueOf(name) }.getOrNull()
    }
    val labelRes = when (field) {
        InvoiceHeaderEditField.SUPPLIER_RUC -> R.string.header_review_ruc_label
        InvoiceHeaderEditField.SUPPLIER_LEGAL_NAME -> R.string.header_review_supplier_label
        InvoiceHeaderEditField.DOCUMENT_TYPE -> R.string.header_review_document_type_label
        InvoiceHeaderEditField.DOCUMENT_SERIES -> R.string.header_review_series_label
        InvoiceHeaderEditField.DOCUMENT_NUMBER -> R.string.header_review_number_label
        InvoiceHeaderEditField.ISSUE_DATE -> R.string.header_review_issue_date_label
        InvoiceHeaderEditField.CURRENCY -> R.string.header_review_currency_label
        InvoiceHeaderEditField.SUBTOTAL -> R.string.header_review_subtotal_label
        InvoiceHeaderEditField.IGV -> R.string.header_review_igv_label
        InvoiceHeaderEditField.OTHER_CHARGES -> R.string.header_review_other_charges_label
        InvoiceHeaderEditField.TOTAL -> R.string.header_review_total_label
        null -> null
    }
    return labelRes?.let { stringResource(it) }
        ?: stringResource(R.string.summary_field_unknown)
}

@Composable
private fun lineFieldLabel(detail: String?): String {
    val field = detail?.let { name ->
        runCatching { InvoiceLineEditField.valueOf(name) }.getOrNull()
    }
    val labelRes = when (field) {
        InvoiceLineEditField.DESCRIPTION -> R.string.line_review_description_label
        InvoiceLineEditField.CODE -> R.string.line_review_code_label
        InvoiceLineEditField.QUANTITY -> R.string.line_review_quantity_label
        InvoiceLineEditField.UNIT -> R.string.line_review_unit_label
        InvoiceLineEditField.UNIT_COST -> R.string.line_review_unit_cost_label
        InvoiceLineEditField.DISCOUNT -> R.string.line_review_discount_label
        InvoiceLineEditField.IGV -> R.string.line_review_igv_label
        InvoiceLineEditField.TOTAL -> R.string.line_review_total_label
        null -> null
    }
    return labelRes?.let { stringResource(it) }
        ?: stringResource(R.string.summary_field_unknown)
}

@StringRes
private fun documentTypeLabelRes(type: PurchaseDocumentType): Int = when (type) {
    PurchaseDocumentType.INVOICE -> R.string.header_review_document_type_invoice
    PurchaseDocumentType.SALES_RECEIPT -> R.string.header_review_document_type_sales_receipt
    PurchaseDocumentType.CREDIT_NOTE -> R.string.header_review_document_type_credit_note
    PurchaseDocumentType.DEBIT_NOTE -> R.string.header_review_document_type_debit_note
}

private fun Money?.displayOr(pending: String): String =
    this?.formatForDisplay() ?: pending

private const val HASH_VISIBLE_CHARS = 8

/** Código de advertencia de cabecera registrado en la instantánea. */
private const val WARNING_TOTAL_DIFFERENCE = "TOTAL_DIFFERENCE"
