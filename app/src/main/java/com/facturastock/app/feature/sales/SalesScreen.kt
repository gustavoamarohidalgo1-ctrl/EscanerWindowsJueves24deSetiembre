package com.facturastock.app.feature.sales

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick as semanticsOnClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import com.facturastock.app.R
import com.facturastock.app.feature.sales.SalesContract.BarcodeReplacement
import com.facturastock.app.feature.sales.SalesContract.CartLine
import com.facturastock.app.feature.sales.SalesContract.EntryKind
import com.facturastock.app.feature.sales.SalesContract.EntryMode
import com.facturastock.app.feature.sales.SalesContract.ProductOption
import com.facturastock.app.ui.components.EmptyState
import com.facturastock.app.ui.components.FacturaStockDialog
import com.facturastock.app.ui.components.FacturaStockPrimaryButton
import com.facturastock.app.ui.components.FacturaStockSecondaryButton
import com.facturastock.app.ui.components.LoadingState
import com.facturastock.app.ui.components.StatusCard
import com.facturastock.app.ui.components.StatusTone
import com.facturastock.app.ui.format.formatForDisplay
import com.facturastock.app.ui.format.currencyLabelForDisplay
import com.facturastock.app.ui.theme.FacturaStockDesign

@Composable
fun SalesScreen(
    state: SalesContract.State,
    onAction: (SalesContract.Action) -> Unit,
    modifier: Modifier = Modifier,
    allowEntryKindSelection: Boolean = true,
) {
    val spacing = FacturaStockDesign.spacing
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag(SalesTestTags.SCREEN),
        contentPadding = PaddingValues(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        if (allowEntryKindSelection) {
            item(key = "entry_kind", contentType = "entry_kind_selector") {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    Text(
                        text = stringResource(R.string.sales_entry_kind_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Row(
                        modifier = Modifier.selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        SalesModeOption(
                            text = stringResource(R.string.sales_entry_cash),
                            selected = state.entryKind == EntryKind.CASH,
                            enabled = !state.isMutating && state.checkoutReview == null,
                            onClick = {
                                onAction(SalesContract.Action.EntryKindChanged(EntryKind.CASH))
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag(SalesTestTags.CASH_ENTRY),
                        )
                        SalesModeOption(
                            text = stringResource(R.string.sales_entry_credit),
                            selected = state.entryKind == EntryKind.CREDIT,
                            enabled = !state.isMutating && state.checkoutReview == null,
                            onClick = {
                                onAction(SalesContract.Action.EntryKindChanged(EntryKind.CREDIT))
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag(SalesTestTags.CREDIT_ENTRY),
                        )
                    }
                }
            }
        }

        if (state.entryKind == EntryKind.CREDIT) {
            item(key = "debtor", contentType = "debtor") {
                DebtorNameEntry(state = state, onAction = onAction)
            }
        }

        item(key = "mode", contentType = "mode_selector") {
            Row(
                modifier = Modifier.selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                SalesModeOption(
                    text = stringResource(R.string.sales_mode_scanner),
                    selected = state.mode == EntryMode.SCANNER,
                    enabled = !state.isMutating && state.checkoutReview == null,
                    onClick = {
                        onAction(SalesContract.Action.ModeChanged(EntryMode.SCANNER))
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag(SalesTestTags.SCANNER_MODE),
                )
                SalesModeOption(
                    text = stringResource(R.string.sales_mode_manual),
                    selected = state.mode == EntryMode.MANUAL,
                    enabled = !state.isMutating && state.checkoutReview == null,
                    onClick = {
                        onAction(SalesContract.Action.ModeChanged(EntryMode.MANUAL))
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag(SalesTestTags.MANUAL_MODE),
                )
            }
        }

        if (state.isMutating) {
            item(key = "progress", contentType = "loading") {
                LoadingState(message = stringResource(R.string.sales_processing))
            }
        }

        state.failure?.takeUnless {
            it == SalesContract.Failure.NO_ACTIVE_BUSINESS ||
                it == SalesContract.Failure.INVALID_QUANTITY ||
                it == SalesContract.Failure.INVALID_PRICE
        }?.let { failure ->
            item(key = "failure", contentType = "failure") {
                StatusCard(
                    statusLabel = stringResource(R.string.sales_error_status),
                    title = stringResource(R.string.sales_error_title),
                    message = stringResource(failure.messageRes()),
                    tone = StatusTone.ERROR,
                    iconRes = R.drawable.ic_warning,
                    supportingContent = if (failure.isRecoverable()) {
                        {
                            FacturaStockSecondaryButton(
                                text = stringResource(R.string.action_retry),
                                onClick = { onAction(SalesContract.Action.Retry) },
                                enabled = !state.isMutating,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }

        if (state.catalogLoadFailed) {
            item(key = "catalog_failure", contentType = "failure") {
                StatusCard(
                    statusLabel = stringResource(R.string.sales_attention_label),
                    title = stringResource(R.string.sales_catalog_load_error_title),
                    message = stringResource(R.string.sales_catalog_load_error_message),
                    tone = StatusTone.ERROR,
                    iconRes = R.drawable.ic_warning,
                    modifier = Modifier.testTag(SalesTestTags.CATALOG_FAILURE),
                    supportingContent = {
                        FacturaStockSecondaryButton(
                            text = stringResource(R.string.action_retry),
                            onClick = { onAction(SalesContract.Action.RetryCatalog) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                )
            }
        }

        if (state.cartLoadFailed) {
            item(key = "cart_failure", contentType = "failure") {
                StatusCard(
                    statusLabel = stringResource(R.string.sales_attention_label),
                    title = stringResource(R.string.sales_cart_load_error_title),
                    message = stringResource(R.string.sales_cart_load_error_message),
                    tone = StatusTone.ERROR,
                    iconRes = R.drawable.ic_warning,
                    modifier = Modifier.testTag(SalesTestTags.CART_FAILURE),
                    supportingContent = {
                        FacturaStockSecondaryButton(
                            text = stringResource(R.string.action_retry),
                            onClick = { onAction(SalesContract.Action.RetryCart) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                )
            }
        }

        if (state.barcodeAssociatedWithoutCartAdd) {
            item(key = "association_saved_notice", contentType = "status") {
                StatusCard(
                    statusLabel = stringResource(R.string.sales_association_saved_status),
                    title = stringResource(R.string.sales_association_saved_title),
                    message = stringResource(R.string.sales_association_saved_message),
                    tone = StatusTone.WARNING,
                    iconRes = R.drawable.ic_barcode_scanner,
                    modifier = Modifier.testTag(SalesTestTags.ASSOCIATION_SAVED_NOTICE),
                    announcementMode = LiveRegionMode.Polite,
                )
            }
        }

        when {
            state.isLoading -> item(key = "loading", contentType = "loading") {
                LoadingState(message = stringResource(R.string.feature_loading_message))
            }

            state.failure == SalesContract.Failure.NO_ACTIVE_BUSINESS -> item(
                key = "no_business",
                contentType = "empty",
            ) {
                EmptyState(
                    title = stringResource(R.string.sales_no_business_title),
                    message = stringResource(R.string.sales_no_business_message),
                    iconRes = R.drawable.ic_info,
                )
            }

            else -> {
                if (state.mode == EntryMode.SCANNER) {
                    item(key = "scanner", contentType = "scanner") {
                        ScannerEntry(state = state)
                    }
                }

                if (state.mode == EntryMode.MANUAL || state.isAssociating) {
                    item(key = "manual_header", contentType = "search") {
                        ProductSearchHeader(
                            state = state,
                            onAction = onAction,
                        )
                    }
                }

                if (state.pendingLocations.isNotEmpty()) {
                    item(key = "locations", contentType = "location_selector") {
                        PendingLocationSelection(
                            options = state.pendingLocations,
                            enabled = !state.isMutating && !state.isSavingLineEdits &&
                                state.checkoutReview == null &&
                                !state.catalogLoadFailed && !state.cartLoadFailed,
                            onAction = onAction,
                        )
                    }
                }

                if (state.mode == EntryMode.MANUAL || state.isAssociating) {
                    if (state.isNameSearchRunning) {
                        item(key = "name_search_progress", contentType = "loading") {
                            LoadingState(
                                message = stringResource(R.string.sales_name_search_running),
                                modifier = Modifier.testTag(SalesTestTags.NAME_SEARCH_PROGRESS),
                            )
                        }
                    } else if (state.searchFailed) {
                        item(key = "name_search_failure", contentType = "failure") {
                            StatusCard(
                                statusLabel = stringResource(R.string.sales_attention_label),
                                title = stringResource(R.string.sales_search_error_title),
                                message = stringResource(R.string.sales_error_search),
                                tone = StatusTone.ERROR,
                                iconRes = R.drawable.ic_warning,
                                modifier = Modifier.testTag(SalesTestTags.SEARCH_FAILURE),
                                supportingContent = {
                                    FacturaStockSecondaryButton(
                                        text = stringResource(R.string.action_retry),
                                        onClick = { onAction(SalesContract.Action.RetrySearch) },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                },
                            )
                        }
                    } else if (state.productOptions.isEmpty() && state.query.isNotBlank()) {
                        item(key = "manual_empty", contentType = "empty") {
                            EmptyState(
                                title = stringResource(R.string.sales_products_empty_title),
                                message = stringResource(R.string.sales_products_empty_message),
                                iconRes = R.drawable.ic_products,
                            )
                        }
                    } else if (state.productOptions.isNotEmpty()) {
                        items(
                            items = state.productOptions,
                            key = { "${it.productId.value}:${it.locationId.value}" },
                            contentType = { "sale_product" },
                        ) { option ->
                            ProductOptionCard(
                                option = option,
                                associationMode = state.isAssociating,
                                enabled = !state.isMutating && !state.isSavingLineEdits &&
                                    state.checkoutReview == null &&
                                    !state.catalogLoadFailed && !state.cartLoadFailed,
                                onClick = {
                                    onAction(
                                        SalesContract.Action.ProductSelected(
                                            option.productId,
                                            option.locationId,
                                        ),
                                    )
                                },
                            )
                        }
                    }
                }

                item(key = "cart_header", contentType = "section_header") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(
                                if (state.entryKind == EntryKind.CREDIT) {
                                    R.string.debt_entry_products_title
                                } else {
                                    R.string.sales_cart_title
                                },
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .semantics { heading() }
                                .testTag(SalesTestTags.CART),
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleLarge,
                        )
                        if (state.isSavingLineEdits) {
                            Row(
                                modifier = Modifier
                                    .semantics(mergeDescendants = true) {
                                        liveRegion = LiveRegionMode.Polite
                                    }
                                    .testTag(SalesTestTags.LINE_SAVE_PROGRESS),
                                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(spacing.iconSmall),
                                    strokeWidth = spacing.borderThin * 2,
                                )
                                Text(
                                    text = stringResource(R.string.action_saving),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                if (state.cartLines.isEmpty()) {
                    item(key = "cart_empty", contentType = "empty") {
                        Text(
                            text = stringResource(R.string.sales_cart_empty_message),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                } else {
                    items(
                        items = state.cartLines,
                        key = CartLine::lineId,
                        contentType = { "sale_line" },
                    ) { line ->
                        SaleLineCard(
                            line = line,
                            currencyCode = state.currencyCode,
                            editingEnabled = !state.isMutating && !state.catalogLoadFailed &&
                                !state.cartLoadFailed,
                            removeEnabled = !state.isMutating && !state.isSavingLineEdits &&
                                !state.catalogLoadFailed && !state.cartLoadFailed,
                            onAction = onAction,
                        )
                    }
                    item(key = "total", contentType = "total") {
                        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                            HorizontalDivider()
                            Text(
                                text = stringResource(
                                    R.string.sales_total,
                                    state.total?.formatForDisplay()
                                        ?: stringResource(R.string.sales_total_pending),
                                ),
                                modifier = Modifier.semantics { heading() },
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.titleLarge,
                            )
                            FacturaStockPrimaryButton(
                                text = stringResource(
                                    if (state.entryKind == EntryKind.CREDIT) {
                                        R.string.debt_entry_review
                                    } else {
                                        R.string.sales_checkout
                                    },
                                ),
                                onClick = {
                                    onAction(SalesContract.Action.CheckoutRequested)
                                },
                                enabled = state.canCheckout,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag(SalesTestTags.CHECKOUT),
                            )
                        }
                    }
                }
            }
        }
    }

    state.pendingReplacement?.let { replacement ->
        BarcodeReplacementDialog(
            replacement,
            state.isMutating || state.isSavingLineEdits,
            onAction,
        )
    }
    if (state.showCheckoutConfirmation) {
        val review = requireNotNull(state.checkoutReview)
        FacturaStockDialog(
            title = stringResource(
                if (state.entryKind == EntryKind.CREDIT) {
                    R.string.debt_entry_confirm_title
                } else {
                    R.string.sales_confirm_title
                },
            ),
            message = if (state.entryKind == EntryKind.CREDIT) {
                stringResource(
                    R.string.debt_entry_confirm_message,
                    review.debtorName.orEmpty(),
                    review.total.formatForDisplay(),
                )
            } else {
                stringResource(
                    R.string.sales_confirm_message,
                    review.total.formatForDisplay(),
                )
            },
            confirmLabel = stringResource(
                if (state.entryKind == EntryKind.CREDIT) {
                    R.string.debt_entry_confirm_action
                } else {
                    R.string.sales_confirm_action
                },
            ),
            dismissLabel = stringResource(R.string.action_cancel),
            onConfirm = { onAction(SalesContract.Action.CheckoutConfirmed) },
            onDismiss = {
                if (!state.isMutating) onAction(SalesContract.Action.CheckoutDismissed)
            },
            confirmEnabled = !state.isMutating,
            dismissEnabled = !state.isMutating,
            modifier = Modifier.testTag(SalesTestTags.CHECKOUT_DIALOG),
        )
    }
    if (state.discardEditsReview) {
        FacturaStockDialog(
            title = stringResource(R.string.sales_discard_edits_title),
            message = stringResource(R.string.sales_discard_edits_message),
            confirmLabel = stringResource(R.string.sales_discard_edits_action),
            dismissLabel = stringResource(R.string.action_cancel),
            onConfirm = { onAction(SalesContract.Action.DiscardEditsConfirmed) },
            onDismiss = {
                if (!state.isMutating) onAction(SalesContract.Action.DiscardEditsDismissed)
            },
            confirmEnabled = !state.isMutating,
            dismissEnabled = !state.isMutating,
            modifier = Modifier.testTag(SalesTestTags.DISCARD_EDITS_DIALOG),
        )
    }
}

@Composable
private fun DebtorNameEntry(
    state: SalesContract.State,
    onAction: (SalesContract.Action) -> Unit,
) {
    val isInvalid = state.debtorNameInput.isNotBlank() && !state.isDebtorNameValid
    OutlinedTextField(
        value = state.debtorNameInput,
        onValueChange = { onAction(SalesContract.Action.DebtorNameChanged(it)) },
        label = { Text(stringResource(R.string.debt_entry_debtor_name_label)) },
        supportingText = {
            Text(
                stringResource(
                    if (isInvalid) {
                        R.string.debt_entry_debtor_name_error
                    } else {
                        R.string.debt_entry_debtor_name_help
                    },
                ),
            )
        },
        singleLine = true,
        enabled = !state.isMutating && state.checkoutReview == null,
        isError = isInvalid,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SalesTestTags.DEBTOR_NAME),
    )
}

@StringRes
private fun SalesContract.Failure.messageRes(): Int = when (this) {
    SalesContract.Failure.NO_ACTIVE_BUSINESS -> R.string.sales_no_business_message
    SalesContract.Failure.LOAD_FAILED -> R.string.sales_error_load
    SalesContract.Failure.INVALID_BARCODE -> R.string.sales_error_invalid_barcode
    SalesContract.Failure.BARCODE_NOT_FOUND -> R.string.sales_error_barcode_not_found
    SalesContract.Failure.BARCODE_CONFLICT -> R.string.sales_error_barcode_conflict
    SalesContract.Failure.PRODUCT_UNAVAILABLE -> R.string.sales_error_product_unavailable
    SalesContract.Failure.INVALID_QUANTITY -> R.string.sales_error_invalid_quantity
    SalesContract.Failure.INVALID_PRICE -> R.string.sales_error_invalid_price
    SalesContract.Failure.INVALID_CREDIT_TERMS -> R.string.debt_entry_error_invalid_terms
    SalesContract.Failure.INCOMPLETE_LINE -> R.string.sales_error_incomplete_line
    SalesContract.Failure.STALE_CART -> R.string.sales_error_stale
    SalesContract.Failure.INSUFFICIENT_STOCK -> R.string.sales_error_stock
    SalesContract.Failure.ONLINE_REQUIRED -> R.string.sales_error_online_required
    SalesContract.Failure.INVENTORY_MIGRATION_REQUIRED ->
        R.string.sales_error_inventory_migration_required
    SalesContract.Failure.CHECKOUT_FAILED -> R.string.sales_error_checkout
    SalesContract.Failure.SAVE_FAILED -> R.string.sales_error_save
}

private fun SalesContract.Failure.isRecoverable(): Boolean = when (this) {
    SalesContract.Failure.LOAD_FAILED,
    SalesContract.Failure.STALE_CART,
    SalesContract.Failure.CHECKOUT_FAILED,
    SalesContract.Failure.ONLINE_REQUIRED,
    SalesContract.Failure.INVENTORY_MIGRATION_REQUIRED,
    SalesContract.Failure.SAVE_FAILED,
    -> true
    else -> false
}

@Composable
private fun SalesModeOption(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FacturaStockDesign.spacing
    Surface(
        modifier = modifier
            .heightIn(min = spacing.minimumTouchTarget)
            .selectable(
                selected = selected,
                enabled = enabled,
                onClick = onClick,
                role = Role.RadioButton,
            ),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        border = BorderStroke(
            spacing.borderThin,
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = spacing.lg, vertical = spacing.md),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun ScannerEntry(
    state: SalesContract.State,
) {
    val spacing = FacturaStockDesign.spacing
    val semanticColors = FacturaStockDesign.semanticColors
    val message = stringResource(
        when {
            state.isAssociating -> R.string.sales_scanner_association_message
            state.scannerActive -> R.string.sales_scanner_help
            else -> R.string.sales_scanner_inactive_title
        },
    )
    val containerColor = if (state.isAssociating) {
        semanticColors.warningContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val contentColor = if (state.isAssociating) {
        semanticColors.onWarningContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .testTag(SalesTestTags.SCANNER_STATUS),
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_barcode_scanner),
                contentDescription = null,
                modifier = Modifier.size(spacing.iconSmall),
            )
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ProductSearchHeader(
    state: SalesContract.State,
    onAction: (SalesContract.Action) -> Unit,
) {
    val spacing = FacturaStockDesign.spacing
    Column(
        modifier = Modifier.testTag(SalesTestTags.OPTIONS),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        if (state.isAssociating) {
            Text(
                text = stringResource(R.string.sales_association_title),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(
                    R.string.sales_association_code,
                    state.pendingAssociationBarcode.orEmpty(),
                ),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.sales_association_privacy_notice),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            FacturaStockSecondaryButton(
                text = stringResource(R.string.sales_association_cancel),
                onClick = { onAction(SalesContract.Action.AssociationDismissed) },
                enabled = !state.isMutating,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SalesTestTags.ASSOCIATION_CANCEL),
            )
        }
        OutlinedTextField(
            value = state.query,
            onValueChange = { onAction(SalesContract.Action.SearchChanged(it)) },
            label = { Text(stringResource(R.string.sales_search_label)) },
            singleLine = true,
            enabled = !state.isMutating,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SalesTestTags.SEARCH),
        )
    }
}

@Composable
private fun ProductOptionCard(
    option: ProductOption,
    associationMode: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val spacing = FacturaStockDesign.spacing
    val clickLabel = stringResource(
        if (associationMode) R.string.sales_product_associate_accessibility
        else R.string.sales_product_add_accessibility,
        option.productName,
        option.locationName,
    )
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SalesTestTags.option(option.productId.value, option.locationId.value))
            .semantics(mergeDescendants = true) {
                semanticsOnClick(label = clickLabel, action = null)
            },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(spacing.borderThin, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Text(option.productName, style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(
                    R.string.sales_product_stock,
                    option.availableQuantity.toPlainString(),
                    option.unitCode,
                    option.locationName,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            option.sku?.let {
                Text(
                    text = stringResource(R.string.sales_product_sku, it),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            option.nameMatchKind?.let { matchKind ->
                Text(
                    text = stringResource(
                        if (matchKind == SalesContract.NameMatchKind.EXACT) {
                            R.string.sales_name_match_exact
                        } else {
                            R.string.sales_name_match_similar
                        },
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            if (associationMode && option.barcode != null) {
                Text(
                    text = stringResource(R.string.sales_product_has_barcode),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun PendingLocationSelection(
    options: List<ProductOption>,
    enabled: Boolean,
    onAction: (SalesContract.Action) -> Unit,
) {
    val spacing = FacturaStockDesign.spacing
    StatusCard(
        statusLabel = stringResource(R.string.sales_attention_label),
        title = stringResource(R.string.sales_location_title),
        message = stringResource(R.string.sales_location_message),
        tone = StatusTone.WARNING,
        iconRes = R.drawable.ic_warning,
        modifier = Modifier.testTag(SalesTestTags.LOCATION_SELECTION),
        announcementMode = LiveRegionMode.Polite,
    )
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        options.forEach { option ->
            FacturaStockSecondaryButton(
                text = stringResource(
                    R.string.sales_location_option,
                    option.locationName,
                    option.availableQuantity.toPlainString(),
                    option.unitCode,
                ),
                onClick = {
                    onAction(
                        SalesContract.Action.LocationSelected(
                            option.productId,
                            option.locationId,
                        ),
                    )
                },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        TextButton(
            onClick = { onAction(SalesContract.Action.LocationSelectionDismissed) },
            enabled = enabled,
        ) {
            Text(stringResource(R.string.action_cancel))
        }
    }
}

@Composable
private fun SaleLineCard(
    line: CartLine,
    currencyCode: String,
    editingEnabled: Boolean,
    removeEnabled: Boolean,
    onAction: (SalesContract.Action) -> Unit,
) {
    val spacing = FacturaStockDesign.spacing
    val currencyLabel = remember(currencyCode) { currencyLabelForDisplay(currencyCode) }
    val formattedLineTotal = remember(line.lineTotal) { line.lineTotal?.formatForDisplay() }
    val decreaseClickLabel = stringResource(
        R.string.sales_quantity_decrease_accessibility,
        line.productName,
    )
    val increaseClickLabel = stringResource(
        R.string.sales_quantity_increase_accessibility,
        line.productName,
    )
    val removeClickLabel = stringResource(
        R.string.sales_remove_line_accessibility,
        line.productName,
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SalesTestTags.line(line.lineId)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(spacing.borderThin, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(line.productName, style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(
                    R.string.sales_line_location_stock,
                    line.locationName,
                    line.availableQuantity.toPlainString(),
                    line.unitCode,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FacturaStockSecondaryButton(
                    text = stringResource(R.string.sales_quantity_decrease),
                    onClick = {
                        onAction(SalesContract.Action.QuantityDecremented(line.lineId))
                    },
                    enabled = editingEnabled,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(SalesTestTags.quantityDecrease(line.lineId))
                        .semantics(mergeDescendants = true) {
                            semanticsOnClick(label = decreaseClickLabel, action = null)
                        },
                )
                OutlinedTextField(
                    value = line.quantityInput,
                    onValueChange = {
                        onAction(SalesContract.Action.QuantityChanged(line.lineId, it))
                    },
                    label = { Text(stringResource(R.string.sales_quantity_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    enabled = editingEnabled,
                    isError = !line.quantityValid,
                    modifier = Modifier
                        .weight(2f)
                        .testTag(SalesTestTags.quantity(line.lineId)),
                )
                FacturaStockSecondaryButton(
                    text = stringResource(R.string.sales_quantity_increase),
                    onClick = {
                        onAction(SalesContract.Action.QuantityIncremented(line.lineId))
                    },
                    enabled = editingEnabled,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(SalesTestTags.quantityIncrease(line.lineId))
                        .semantics(mergeDescendants = true) {
                            semanticsOnClick(label = increaseClickLabel, action = null)
                        },
                )
            }
            if (!line.quantityValid) {
                Text(
                    text = stringResource(R.string.sales_error_invalid_quantity),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlinedTextField(
                value = line.unitPriceInput,
                onValueChange = {
                    onAction(SalesContract.Action.UnitPriceChanged(line.lineId, it))
                },
                label = {
                    Text(
                        stringResource(
                            R.string.sales_unit_price_label,
                            currencyLabel,
                        ),
                    )
                },
                supportingText = if (!line.priceValid) {
                    { Text(stringResource(R.string.sales_error_invalid_price)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                enabled = editingEnabled,
                isError = !line.priceValid,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SalesTestTags.price(line.lineId)),
            )
            Text(
                text = stringResource(
                    R.string.sales_line_total,
                    formattedLineTotal
                        ?: stringResource(R.string.sales_total_pending),
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            TextButton(
                onClick = { onAction(SalesContract.Action.LineRemoved(line.lineId)) },
                enabled = removeEnabled,
                modifier = Modifier
                    .testTag(SalesTestTags.remove(line.lineId))
                    .semantics(mergeDescendants = true) {
                        semanticsOnClick(label = removeClickLabel, action = null)
                    },
            ) {
                Text(stringResource(R.string.sales_remove_line))
            }
        }
    }
}

@Composable
private fun BarcodeReplacementDialog(
    replacement: BarcodeReplacement,
    isMutating: Boolean,
    onAction: (SalesContract.Action) -> Unit,
) {
    val replacing = replacement.existingBarcode != null
    FacturaStockDialog(
        title = stringResource(
            if (replacing) R.string.sales_replace_barcode_title
            else R.string.sales_associate_barcode_title,
        ),
        message = if (replacing) {
            stringResource(
                R.string.sales_replace_barcode_message,
                replacement.product.productName,
                replacement.existingBarcode.orEmpty(),
                replacement.newBarcode,
            )
        } else {
            stringResource(
                R.string.sales_associate_barcode_message,
                replacement.newBarcode,
                replacement.product.productName,
            )
        },
        confirmLabel = stringResource(
            if (replacing) R.string.sales_replace_barcode_action
            else R.string.sales_associate_barcode_action,
        ),
        dismissLabel = stringResource(R.string.action_cancel),
        onConfirm = { onAction(SalesContract.Action.BarcodeReplacementConfirmed) },
        onDismiss = {
            if (!isMutating) onAction(SalesContract.Action.BarcodeReplacementDismissed)
        },
        confirmEnabled = !isMutating,
        dismissEnabled = !isMutating,
        modifier = Modifier.testTag(SalesTestTags.REPLACEMENT_DIALOG),
    )
}
