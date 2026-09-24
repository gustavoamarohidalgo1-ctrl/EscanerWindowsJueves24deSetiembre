package com.facturastock.app.feature.sales

import androidx.annotation.StringRes
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick as semanticsOnClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import com.facturastock.app.R
import com.facturastock.app.feature.sales.SalesContract.BarcodeReplacement
import com.facturastock.app.feature.sales.SalesContract.BarcodeSuggestion
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
import com.facturastock.app.ui.format.formatCurrencyAmountForDisplay
import com.facturastock.app.ui.theme.FacturaStockDesign

@Composable
fun SalesScreen(
    state: SalesContract.State,
    onAction: (SalesContract.Action) -> Unit,
    modifier: Modifier = Modifier,
    allowEntryKindSelection: Boolean = true,
    showScannerStatus: Boolean = true,
    showStepBack: Boolean = true,
) {
    val spacing = FacturaStockDesign.spacing
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()
    val scannedBarcode = state.pendingAssociationBarcode
    val hasBarcodeSuggestions = scannedBarcode != null && state.barcodeSuggestions.isNotEmpty()
    var manualAssociationExpanded by remember(scannedBarcode) { mutableStateOf(false) }
    val hasSearchPrefix = state.query.trim().length >= 2
    val showProductSearch = (state.unifiedInput || state.mode == EntryMode.MANUAL || state.isAssociating) &&
        (!hasBarcodeSuggestions || manualAssociationExpanded || (state.unifiedInput && hasSearchPrefix))
    val barcodeSuggestionEnabled = !state.isCheckoutPending && !state.isMutating &&
        !state.isSavingLineEdits && !state.hasPendingEdits &&
        state.pendingReplacement == null && !state.discardEditsReview &&
        !state.catalogLoadFailed && !state.cartLoadFailed
    LaunchedEffect(state.entryStep, state.mode) {
        listState.scrollToItem(0)
    }
    LaunchedEffect(scannedBarcode) {
        if (scannedBarcode != null) listState.scrollToItem(0)
    }
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .testTag(SalesTestTags.SCREEN),
        contentPadding = PaddingValues(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        if (state.entryStep == SalesContract.EntryStep.SELECT_KIND && allowEntryKindSelection) {
            item(key = "entry_kind", contentType = "entry_kind_selector") {
                Column(
                    modifier = Modifier.testTag(SalesTestTags.ENTRY_KIND_SCREEN),
                    verticalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    Text(
                        text = stringResource(R.string.sales_entry_kind_title),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = stringResource(R.string.sales_entry_kind_help),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SalesChoiceCard(
                        title = stringResource(R.string.sales_entry_cash),
                        description = stringResource(R.string.sales_entry_cash_help),
                        iconRes = R.drawable.ic_sale,
                        enabled = !state.isMutating,
                        onClick = { onAction(SalesContract.Action.EntryKindChanged(EntryKind.CASH)) },
                        modifier = Modifier.testTag(SalesTestTags.CASH_ENTRY),
                    )
                    SalesChoiceCard(
                        title = stringResource(R.string.sales_entry_credit),
                        description = stringResource(R.string.sales_entry_credit_help),
                        iconRes = R.drawable.ic_sale,
                        enabled = !state.isMutating,
                        onClick = { onAction(SalesContract.Action.EntryKindChanged(EntryKind.CREDIT)) },
                        modifier = Modifier.testTag(SalesTestTags.CREDIT_ENTRY),
                    )
                }
            }
        }

        if (showStepBack && state.entryStep != SalesContract.EntryStep.SELECT_KIND) {
            item(key = "step_back", contentType = "step_navigation") {
                TextButton(
                    onClick = {
                        focusManager.clearFocus(force = true)
                        onAction(SalesContract.Action.StepBackSelected)
                    },
                    enabled = !state.isCheckoutPending && !state.isMutating,
                    modifier = Modifier.testTag(SalesTestTags.STEP_BACK),
                ) {
                    Icon(painterResource(R.drawable.ic_back), contentDescription = null)
                    Text(
                        text = stringResource(R.string.sales_step_back),
                        modifier = Modifier.padding(start = spacing.xs),
                    )
                }
            }
        }

        if (state.entryStep == SalesContract.EntryStep.SELECT_MODE && !state.unifiedInput) {
            item(key = "mode", contentType = "mode_selector") {
                Column(
                    modifier = Modifier.testTag(SalesTestTags.ENTRY_MODE_SCREEN),
                    verticalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    Text(
                        text = stringResource(
                            if (state.entryKind == EntryKind.CASH) R.string.sales_entry_cash
                            else R.string.sales_entry_credit,
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.sales_entry_mode_title),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() },
                    )
                    SalesChoiceCard(
                        title = stringResource(R.string.sales_mode_scanner),
                        description = stringResource(R.string.sales_entry_scanner_help),
                        iconRes = R.drawable.ic_barcode_scanner,
                        enabled = !state.isMutating,
                        onClick = {
                            focusManager.clearFocus(force = true)
                            onAction(SalesContract.Action.ModeChanged(EntryMode.SCANNER))
                        },
                        modifier = Modifier.testTag(SalesTestTags.SCANNER_MODE),
                    )
                    SalesChoiceCard(
                        title = stringResource(R.string.sales_mode_manual),
                        description = stringResource(R.string.sales_entry_manual_help),
                        iconRes = R.drawable.ic_products,
                        enabled = !state.isMutating,
                        onClick = {
                            focusManager.clearFocus(force = true)
                            onAction(SalesContract.Action.ModeChanged(EntryMode.MANUAL))
                        },
                        modifier = Modifier.testTag(SalesTestTags.MANUAL_MODE),
                    )
                }
            }
        }

        if (state.entryStep == SalesContract.EntryStep.SELL) {
            item(key = "sale_mode_title", contentType = "section_header") {
                Text(
                    text = if (state.unifiedInput) stringResource(
                        if (state.entryKind == EntryKind.CASH) R.string.sales_entry_cash else R.string.sales_entry_credit,
                    ) else stringResource(
                        R.string.sales_current_entry,
                        stringResource(
                            if (state.entryKind == EntryKind.CASH) R.string.sales_entry_cash
                            else R.string.sales_entry_credit,
                        ),
                        stringResource(
                            if (state.mode == EntryMode.SCANNER) R.string.sales_mode_scanner
                            else R.string.sales_mode_manual,
                        ),
                    ),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
            }
            if (state.entryKind == EntryKind.CREDIT) {
                item(key = "debtor", contentType = "debtor") {
                    DebtorNameEntry(state = state, onAction = onAction)
                }
            }

            if (state.isMutating && !state.isProcessingBarcode) {
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

            state.scannerFailure?.let { failure ->
                item(key = "scanner_read_failure", contentType = "failure") {
                    StatusCard(
                        statusLabel = stringResource(R.string.sales_attention_label),
                        title = stringResource(R.string.sales_error_title),
                        message = stringResource(failure.messageRes()),
                        tone = StatusTone.WARNING,
                        iconRes = R.drawable.ic_barcode_scanner,
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

            if (state.productRegisteredWithoutCartAdd) {
                item(key = "registered_product_notice", contentType = "status") {
                    StatusCard(
                        statusLabel = stringResource(R.string.sales_attention_label),
                        title = stringResource(R.string.sales_registered_product_title),
                        message = stringResource(R.string.sales_registered_product_retry),
                        tone = StatusTone.WARNING,
                        iconRes = R.drawable.ic_barcode_scanner,
                        modifier = Modifier.testTag(SalesTestTags.REGISTERED_PRODUCT_NOTICE),
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
                    if (state.isAssociating && !hasBarcodeSuggestions) {
                        item(key = "register_unknown_product", contentType = "product_registration") {
                            RegisterUnknownProduct(state, onAction)
                        }
                    }
                    if (state.mode == EntryMode.SCANNER && showScannerStatus && !hasBarcodeSuggestions && !state.unifiedInput) {
                        item(key = "scanner", contentType = "scanner") {
                            ScannerEntry(state = state)
                        }
                    }

                    if (hasBarcodeSuggestions) {
                        item(key = "barcode_suggestions_header", contentType = "section_header") {
                            BarcodeSuggestionsHeader(
                                scannedBarcode = requireNotNull(scannedBarcode),
                                selectionReason = state.barcodeSelectionReason,
                                enabled = barcodeSuggestionEnabled,
                                onDismiss = {
                                    // Conservar el foco del receptor Android cuando ya está activo.
                                    // Solo un campo de edición pausado necesita liberar su foco.
                                    if (state.isTextInputFocused) focusManager.clearFocus(force = true)
                                    onAction(SalesContract.Action.AssociationDismissed)
                                },
                            )
                        }
                        items(
                            items = state.barcodeSuggestions,
                            key = { "barcode_suggestion:${it.product.productId.value}:${it.product.locationId.value}" },
                            contentType = { "barcode_suggestion" },
                        ) { suggestion ->
                            BarcodeSuggestionCard(
                                suggestion = suggestion,
                                enabled = barcodeSuggestionEnabled,
                                onClick = {
                                    if (state.isTextInputFocused) focusManager.clearFocus(force = true)
                                    onAction(
                                        SalesContract.Action.BarcodeSuggestionSelected(
                                            productId = suggestion.product.productId,
                                            locationId = suggestion.product.locationId,
                                            scannedBarcode = requireNotNull(scannedBarcode),
                                        ),
                                    )
                                },
                            )
                        }
                        item(key = "barcode_suggestions_fallback", contentType = "search_navigation") {
                            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                                HorizontalDivider()
                                RegisterUnknownProduct(state, onAction)
                                Text(
                                    text = stringResource(R.string.sales_barcode_suggestions_fallback),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(
                                    onClick = {
                                        if (state.isTextInputFocused) focusManager.clearFocus(force = true)
                                        onAction(SalesContract.Action.AssociationDismissed)
                                    },
                                    enabled = barcodeSuggestionEnabled,
                                    modifier = Modifier.testTag(SalesTestTags.ASSOCIATION_CANCEL),
                                ) {
                                    Text(stringResource(R.string.action_cancel))
                                }
                                if (!manualAssociationExpanded && !(state.unifiedInput && hasSearchPrefix)) {
                                    FacturaStockSecondaryButton(
                                        text = stringResource(R.string.sales_barcode_suggestions_manual_action),
                                        onClick = { manualAssociationExpanded = true },
                                        enabled = barcodeSuggestionEnabled,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag(SalesTestTags.BARCODE_SUGGESTIONS_MANUAL),
                                    )
                                }
                            }
                        }
                    }

                    if (showProductSearch && (!state.unifiedInput || state.isAssociating)) {
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
                                enabled = !state.isCheckoutPending && !state.isMutating && !state.isSavingLineEdits &&
                                    !state.catalogLoadFailed && !state.cartLoadFailed,
                                onAction = onAction,
                            )
                        }
                    }

                    if (showProductSearch) {
                        if (state.unifiedInput && !hasSearchPrefix) {
                            item(key = "unified_search_help", contentType = "supporting_text") {
                                Text(
                                    text = stringResource(R.string.sales_unified_search_help),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        } else if (state.isNameSearchRunning) {
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
                        } else {
                            val displayOptions = if (state.query.isBlank()) {
                                state.availableProducts
                            } else {
                                state.productOptions
                            }
                            if (state.query.isBlank()) {
                                if (displayOptions.isEmpty()) {
                                    item(key = "available_empty", contentType = "empty") {
                                        EmptyState(
                                            title = stringResource(R.string.sales_available_products_title),
                                            message = stringResource(R.string.sales_available_products_empty),
                                            iconRes = R.drawable.ic_products,
                                        )
                                    }
                                } else {
                                    item(key = "available_header", contentType = "section_header") {
                                        Text(
                                            text = stringResource(R.string.sales_available_products_title),
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                    items(
                                        items = displayOptions,
                                        key = { "${it.productId.value}:${it.locationId.value}" },
                                        contentType = { "sale_product" },
                                    ) { option ->
                                        ProductOptionCard(
                                            option = option,
                                            associationMode = state.isAssociating,
                                            enabled = !state.isCheckoutPending && !state.isMutating && !state.isSavingLineEdits &&
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
                            } else if (displayOptions.isEmpty()) {
                                item(key = "manual_empty", contentType = "empty") {
                                    EmptyState(
                                        title = stringResource(R.string.sales_products_empty_title),
                                        message = stringResource(R.string.sales_products_empty_message),
                                        iconRes = R.drawable.ic_products,
                                    )
                                }
                            } else {
                                items(
                                    items = displayOptions,
                                    key = { "${it.productId.value}:${it.locationId.value}" },
                                    contentType = { "sale_product" },
                                ) { option ->
                                    ProductOptionCard(
                                        option = option,
                                        associationMode = state.isAssociating,
                                        enabled = !state.isCheckoutPending && !state.isMutating && !state.isSavingLineEdits &&
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
                                text = stringResource(
                                    if (state.unifiedInput) R.string.sales_unified_cart_empty
                                    else if (state.mode == EntryMode.SCANNER) R.string.sales_scanner_cart_empty_message
                                    else R.string.sales_cart_empty_message,
                                ),
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
                                editingEnabled = !state.isCheckoutPending && !state.isMutating && !state.catalogLoadFailed &&
                                    !state.cartLoadFailed,
                                removeEnabled = !state.isCheckoutPending && !state.isMutating && !state.isSavingLineEdits &&
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
                                if (state.isCheckoutPending) {
                                    Text(stringResource(R.string.sales_pending_checkout_message),
                                        style = MaterialTheme.typography.bodyMedium)
                                }
                                FacturaStockPrimaryButton(
                                    text = stringResource(
                                        if (state.isCheckoutPending) {
                                            R.string.sales_verify_pending_checkout
                                        } else if (state.entryKind == EntryKind.CREDIT) {
                                            R.string.debt_entry_confirm_action
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
    }

    state.weightSaleEditor?.let { editor ->
        WeightSaleDialog(
            editor = editor,
            isMutating = state.isMutating || state.isSavingLineEdits,
            onAction = onAction,
        )
    }
    state.pendingReplacement?.let { replacement ->
        BarcodeReplacementDialog(
            replacement,
            state.isMutating || state.isSavingLineEdits,
            onAction,
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
        enabled = !state.isCheckoutPending && !state.isMutating,
        isError = isInvalid,
        modifier = Modifier
            .fillMaxWidth()
            .then(textInputFocusModifier("debtor", onAction))
            .testTag(SalesTestTags.DEBTOR_NAME),
    )
}

/** Los campos liberan su registro incluso si una fila sale de la composición. */
@Composable
private fun textInputFocusModifier(
    fieldId: String,
    onAction: (SalesContract.Action) -> Unit,
): Modifier {
    var focused by remember(fieldId) { mutableStateOf(false) }
    val currentOnAction by rememberUpdatedState(onAction)
    DisposableEffect(fieldId) {
        onDispose {
            if (focused) {
                currentOnAction(SalesContract.Action.TextInputFocusChanged(fieldId, false))
            }
        }
    }
    return Modifier.onFocusChanged { state ->
        if (focused != state.isFocused) {
            focused = state.isFocused
            currentOnAction(SalesContract.Action.TextInputFocusChanged(fieldId, focused))
        }
    }
}

@StringRes
private fun SalesContract.ScannerFailure.messageRes(): Int = when (this) {
    SalesContract.ScannerFailure.INCOMPLETE -> R.string.sales_scanner_read_incomplete
    SalesContract.ScannerFailure.TOO_LONG -> R.string.sales_scanner_read_too_long
    SalesContract.ScannerFailure.INVALID_CHARACTER -> R.string.sales_scanner_read_invalid_character
    SalesContract.ScannerFailure.QUEUE_FULL -> R.string.sales_scanner_queue_full
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
private fun SalesChoiceCard(
    title: String,
    description: String,
    @DrawableRes iconRes: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FacturaStockDesign.spacing
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = spacing.minimumTouchTarget),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        border = BorderStroke(
            spacing.borderThin,
            MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(spacing.iconLarge),
            )
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(
                    description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
internal fun SalesScannerFeedback(
    state: SalesContract.State,
    modifier: Modifier = Modifier,
) {
    val spacing = FacturaStockDesign.spacing
    val semanticColors = FacturaStockDesign.semanticColors
    val focusManager = LocalFocusManager.current
    val lastAdded = state.lastScanAdded
    val hasFailure = state.scannerFailure != null || state.failure != null ||
        state.catalogLoadFailed || state.cartLoadFailed
    val isSaving = state.isSavingLineEdits || (state.isMutating && !state.isProcessingBarcode)
    val isPaused = state.isTextInputFocused || isSaving || state.hasPendingEdits
    val needsSelection = state.productRegistration != null || state.isAssociating || state.pendingLocations.isNotEmpty() ||
        state.pendingReplacement != null
    val needsReview = state.isCheckoutPending || state.discardEditsReview
    val hasPendingReads = state.pendingBarcodeCount > 0 || state.isProcessingBarcode
    val pendingRecoveredProduct = state.pendingLocations.firstOrNull()
        ?.takeIf { state.pendingRecoveredBarcode != null }
    val showPendingRecovery = pendingRecoveredProduct != null && !hasFailure && !isPaused && state.productRegistration == null
    val showSuccess = lastAdded != null && !hasFailure && !isPaused && !needsSelection &&
        !needsReview && !hasPendingReads
    val title = when {
        hasFailure -> stringResource(R.string.sales_scanner_feedback_attention)
        state.productRegistration != null -> stringResource(R.string.sales_scanner_feedback_registration)
        isSaving -> stringResource(R.string.sales_scanner_feedback_saving)
        state.isTextInputFocused -> stringResource(R.string.sales_scanner_feedback_paused)
        state.hasPendingEdits -> stringResource(R.string.sales_scanner_feedback_review_edits)
        pendingRecoveredProduct != null -> stringResource(
            R.string.sales_scanner_feedback_recovered,
            pendingRecoveredProduct.productName,
        )
        state.pendingLocations.isNotEmpty() -> stringResource(R.string.sales_scanner_feedback_location)
        state.isAssociating -> stringResource(
            when (state.barcodeSelectionReason) {
                SalesContract.BarcodeSelectionReason.AMBIGUOUS -> R.string.sales_scanner_feedback_ambiguous
                SalesContract.BarcodeSelectionReason.CATALOG_CHANGED -> R.string.sales_scanner_feedback_catalog_changed
                null -> if (state.barcodeSuggestions.isNotEmpty()) {
                    R.string.sales_scanner_feedback_choose_product
                } else {
                    R.string.sales_scanner_feedback_unknown
                }
            },
        )
        state.pendingReplacement != null -> stringResource(R.string.sales_scanner_feedback_replacement)
        needsReview -> stringResource(R.string.sales_scanner_feedback_checkout)
        hasPendingReads -> stringResource(
            R.string.sales_scanner_feedback_processing,
            state.pendingBarcodeCount.coerceAtLeast(1),
        )
        lastAdded != null -> stringResource(
            when {
                lastAdded.recoveredFromBarcode != null && lastAdded.alreadyInCart -> R.string.sales_scanner_feedback_recovered_present
                lastAdded.recoveredFromBarcode != null -> R.string.sales_scanner_feedback_recovered
                lastAdded.alreadyInCart -> R.string.sales_scanner_feedback_already_present
                else -> R.string.sales_scanner_feedback_added
            },
            lastAdded.productName,
        )
        state.scannerActive -> stringResource(R.string.sales_scanner_feedback_ready)
        else -> stringResource(R.string.sales_scanner_inactive_title)
    }
    val detail = when {
        state.scannerFailure != null -> stringResource(
            when (state.scannerFailure) {
                SalesContract.ScannerFailure.INCOMPLETE -> R.string.sales_scanner_feedback_incomplete
                SalesContract.ScannerFailure.TOO_LONG -> R.string.sales_scanner_feedback_too_long
                SalesContract.ScannerFailure.INVALID_CHARACTER -> R.string.sales_scanner_feedback_invalid
                SalesContract.ScannerFailure.QUEUE_FULL -> R.string.sales_scanner_feedback_queue_full
            },
        )
        hasFailure -> stringResource(R.string.sales_scanner_feedback_error_help)
        state.productRegistration != null -> stringResource(R.string.sales_scanner_feedback_registration_help)
        isSaving -> stringResource(R.string.sales_scanner_feedback_saving_help)
        state.isTextInputFocused -> stringResource(R.string.sales_scanner_feedback_paused_help)
        state.hasPendingEdits -> stringResource(R.string.sales_scanner_feedback_review_edits_help)
        pendingRecoveredProduct != null -> stringResource(R.string.sales_scanner_feedback_recovered_location_help)
        state.isAssociating && state.barcodeSelectionReason == SalesContract.BarcodeSelectionReason.AMBIGUOUS ->
            stringResource(R.string.sales_scanner_feedback_ambiguous_help)
        state.isAssociating && state.barcodeSelectionReason == SalesContract.BarcodeSelectionReason.CATALOG_CHANGED ->
            stringResource(R.string.sales_scanner_feedback_catalog_changed_help)
        needsSelection -> stringResource(R.string.sales_scanner_feedback_selection_help)
        needsReview -> stringResource(R.string.sales_scanner_feedback_checkout_help)
        hasPendingReads -> stringResource(R.string.sales_scanner_feedback_processing_help)
        lastAdded != null -> stringResource(
            R.string.sales_scanner_feedback_quantity,
            lastAdded.quantity.stripTrailingZeros().toPlainString(),
            lastAdded.unitCode,
            lastAdded.locationName,
        )
        state.scannerActive -> stringResource(R.string.sales_scanner_feedback_ready_help)
        else -> stringResource(R.string.sales_scanner_feedback_inactive_help)
    }
    val recoveryBarcode = when {
        showPendingRecovery -> state.pendingRecoveredBarcode
        showSuccess -> lastAdded?.recoveredFromBarcode
        else -> null
    }
    val recoveryDescription = recoveryBarcode?.let { barcode ->
        stringResource(R.string.sales_scanner_feedback_recovered_code, barcode)
    }
    val containerColor = when {
        hasFailure || needsSelection -> semanticColors.warningContainer
        showSuccess -> semanticColors.successContainer
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    val contentColor = when {
        hasFailure || needsSelection -> semanticColors.onWarningContainer
        showSuccess -> semanticColors.onSuccessContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                recoveryDescription?.let { stateDescription = it }
            }
            .testTag(SalesTestTags.SCANNER_FEEDBACK),
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                // La franja fija conserva espacio para el carrito con letra grande. El texto
                // completo sigue disponible en semántica aunque un nombre largo no quepa.
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (state.isTextInputFocused && !state.isAssociating && !isSaving) {
                TextButton(
                    onClick = { focusManager.clearFocus(force = true) },
                    modifier = Modifier.testTag(SalesTestTags.SCANNER_RESUME),
                ) {
                    Text(stringResource(R.string.sales_scanner_feedback_resume))
                }
            }
        }
    }
}

@Composable
private fun ScannerEntry(
    state: SalesContract.State,
) {
    val spacing = FacturaStockDesign.spacing
    val focusManager = LocalFocusManager.current
    val semanticColors = FacturaStockDesign.semanticColors
    val message = stringResource(
        when {
            state.isTextInputFocused -> R.string.sales_scanner_paused_for_edit
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
        Column(
            modifier = Modifier.padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_barcode_scanner),
                contentDescription = null,
                modifier = Modifier.size(spacing.iconLarge),
            )
            Text(
                text = stringResource(R.string.sales_scanner_ready_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = if (state.pendingBarcodeCount > 0) {
                    "$message\n${stringResource(R.string.sales_scanner_processing_queue, state.pendingBarcodeCount)}"
                } else {
                    message
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (state.isTextInputFocused && !state.isAssociating) {
                TextButton(
                    onClick = { focusManager.clearFocus(force = true) },
                    modifier = Modifier.testTag(SalesTestTags.SCANNER_RESUME),
                ) {
                    Text(stringResource(R.string.sales_scanner_resume))
                }
            }
        }
    }
}

@Composable
private fun RegisterUnknownProduct(
    state: SalesContract.State,
    onAction: (SalesContract.Action) -> Unit,
) {
    val barcode = state.pendingAssociationBarcode ?: return
    val focusManager = LocalFocusManager.current
    Column(verticalArrangement = Arrangement.spacedBy(FacturaStockDesign.spacing.sm)) {
        Text(
            text = stringResource(
                if (state.barcodeSuggestions.isEmpty()) R.string.sales_register_product_help
                else R.string.sales_register_product_after_suggestions,
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        FacturaStockPrimaryButton(
            text = stringResource(R.string.sales_register_product_action),
            enabled = state.canRegisterProduct,
            modifier = Modifier.fillMaxWidth().testTag(SalesTestTags.REGISTER_PRODUCT),
            onClick = {
                focusManager.clearFocus(force = true)
                onAction(SalesContract.Action.RegisterProductRequested(barcode))
            },
        )
    }
}

@Composable
private fun ProductSearchHeader(
    state: SalesContract.State,
    onAction: (SalesContract.Action) -> Unit,
) {
    val spacing = FacturaStockDesign.spacing
    val hasBarcodeSuggestions = state.isAssociating && state.barcodeSuggestions.isNotEmpty()
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
            if (!hasBarcodeSuggestions && state.barcodeSelectionReason != null) {
                Text(
                    text = barcodeSelectionHelp(state.barcodeSelectionReason),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                text = stringResource(
                    if (hasBarcodeSuggestions) R.string.sales_barcode_suggestions_manual_notice
                    else R.string.sales_association_privacy_notice,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!hasBarcodeSuggestions) {
                FacturaStockSecondaryButton(
                    text = stringResource(R.string.sales_association_cancel),
                    onClick = { onAction(SalesContract.Action.AssociationDismissed) },
                    enabled = !state.isMutating,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(SalesTestTags.ASSOCIATION_CANCEL),
                )
            }
        }
        if (!state.unifiedInput) {
            OutlinedTextField(
                value = state.query,
                onValueChange = { onAction(SalesContract.Action.SearchChanged(it)) },
                label = { Text(stringResource(R.string.sales_search_label)) },
                singleLine = true,
                enabled = !state.isMutating,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(textInputFocusModifier("search", onAction))
                    .testTag(SalesTestTags.SEARCH),
            )
        }
    }
}

@Composable
private fun BarcodeSuggestionsHeader(
    scannedBarcode: String,
    selectionReason: SalesContract.BarcodeSelectionReason?,
    enabled: Boolean,
    onDismiss: () -> Unit,
) {
    val spacing = FacturaStockDesign.spacing
    Column(
        modifier = Modifier.testTag(SalesTestTags.BARCODE_SUGGESTIONS),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            text = stringResource(R.string.sales_barcode_suggestions_title),
            modifier = Modifier.semantics {
                heading()
                liveRegion = LiveRegionMode.Polite
            },
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = stringResource(R.string.sales_association_code, scannedBarcode),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = barcodeSelectionHelp(selectionReason),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FacturaStockSecondaryButton(
            text = stringResource(R.string.sales_barcode_suggestions_rescan),
            onClick = onDismiss,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SalesTestTags.BARCODE_SUGGESTIONS_RESCAN),
        )
    }
}

@Composable
private fun barcodeSelectionHelp(reason: SalesContract.BarcodeSelectionReason?): String =
    stringResource(
        when (reason) {
            SalesContract.BarcodeSelectionReason.AMBIGUOUS -> R.string.sales_barcode_suggestions_ambiguous_help
            SalesContract.BarcodeSelectionReason.CATALOG_CHANGED -> R.string.sales_barcode_suggestions_catalog_changed_help
            null -> R.string.sales_barcode_suggestions_help
        },
    )

@Composable
private fun BarcodeSuggestionCard(
    suggestion: BarcodeSuggestion,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val spacing = FacturaStockDesign.spacing
    val option = suggestion.product
    val clickLabel = stringResource(
        R.string.sales_product_add_accessibility,
        option.productName,
        option.locationName,
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SalesTestTags.barcodeSuggestion(option.productId.value, option.locationId.value)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(spacing.borderThin, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(option.productName, style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(
                    R.string.sales_barcode_suggestions_saved_code,
                    option.barcode.orEmpty(),
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = if (suggestion.missingDigits == 0) {
                    stringResource(R.string.sales_barcode_suggestions_exact_code)
                } else {
                    pluralStringResource(
                        R.plurals.sales_barcode_suggestions_missing_digits,
                        suggestion.missingDigits,
                        suggestion.missingDigits,
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            option.suggestedSalePrice?.let { price ->
                Text(
                    text = stringResource(R.string.sales_product_price, price.formatForDisplay()),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            FacturaStockPrimaryButton(
                text = stringResource(R.string.sales_scanner_add_product),
                onClick = onClick,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SalesTestTags.barcodeSuggestionAdd(option.productId.value, option.locationId.value))
                    .semantics { semanticsOnClick(label = clickLabel, action = null) },
            )
        }
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
            option.suggestedSalePrice?.let { price ->
                Text(
                    text = stringResource(R.string.sales_product_price, price.formatForDisplay()),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            if (!associationMode) {
                Text(
                    text = stringResource(R.string.sales_add_to_sale),
                    color = MaterialTheme.colorScheme.secondary,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
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
        options.firstOrNull()?.let { product ->
            Text(
                text = product.productName,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            product.barcode?.let { barcode ->
                Text(
                    text = stringResource(R.string.sales_barcode_suggestions_saved_code, barcode),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
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
                text = if (line.isWeightProduct) {
                    stringResource(
                        R.string.weight_sale_stock,
                        formatWeightForDisplay(line.availableQuantity),
                        line.locationName,
                    )
                } else {
                    stringResource(
                        R.string.sales_line_location_stock,
                        line.locationName,
                        line.availableQuantity.toPlainString(),
                        line.unitCode,
                    )
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (line.isWeightProduct) {
                val quantity = remember(line.quantityInput) {
                    line.quantityInput.replace(',', '.').toBigDecimalOrNull()
                        ?.let(::formatWeightForDisplay)
                }
                val price = remember(currencyCode, line.unitPriceInput) {
                    formatCurrencyAmountForDisplay(currencyCode, line.unitPriceInput)
                }
                Text(
                    text = stringResource(
                        R.string.weight_sale_cart_summary,
                        quantity ?: stringResource(R.string.weight_sale_pending_value),
                        price ?: stringResource(R.string.sales_total_pending),
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag(WeightSaleTestTags.summary(line.lineId)),
                )
                FacturaStockSecondaryButton(
                    text = stringResource(R.string.weight_sale_edit),
                    onClick = { onAction(SalesContract.Action.EditWeightSale(line.lineId)) },
                    enabled = editingEnabled,
                    modifier = Modifier.fillMaxWidth().testTag(WeightSaleTestTags.edit(line.lineId)),
                )
            } else {
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
                            .then(textInputFocusModifier("quantity:${line.lineId}", onAction))
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
                        .then(textInputFocusModifier("price:${line.lineId}", onAction))
                        .testTag(SalesTestTags.price(line.lineId)),
                )
            }
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
