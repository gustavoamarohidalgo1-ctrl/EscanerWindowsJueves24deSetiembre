package com.facturastock.app.feature.inventory

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import com.facturastock.app.R
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.InventoryDataAlert
import com.facturastock.app.domain.model.InventoryDiagnosticIssue
import com.facturastock.app.domain.model.InventoryDiagnosticPosition
import com.facturastock.app.domain.model.InventoryDiagnosticReport
import com.facturastock.app.domain.model.InventoryProductDetail
import com.facturastock.app.domain.model.InventoryReadItem
import com.facturastock.app.domain.model.InventoryReadMovement
import com.facturastock.app.domain.model.InventoryReadPosition
import com.facturastock.app.domain.model.ProductProfit
import com.facturastock.app.domain.model.ProductProfitStatus
import com.facturastock.app.domain.model.StockMovementType
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.ui.components.EmptyState
import com.facturastock.app.ui.components.FacturaStockPrimaryButton
import com.facturastock.app.ui.components.FacturaStockDialog
import com.facturastock.app.ui.components.FacturaStockSecondaryButton
import com.facturastock.app.ui.components.LoadingState
import com.facturastock.app.ui.components.StatusCard
import com.facturastock.app.ui.components.StatusTone
import com.facturastock.app.ui.format.formatForDisplay
import com.facturastock.app.ui.theme.FacturaStockDesign
import java.math.BigDecimal

@Composable
fun InventoryListScreen(
    items: List<InventoryReadItem>,
    query: String,
    diagnosticReport: InventoryDiagnosticReport?,
    isLoading: Boolean,
    isDiagnosing: Boolean,
    diagnosticFailed: Boolean,
    modifier: Modifier = Modifier,
    showDiagnostic: Boolean = inventoryDiagnosticIsRelevant(
        items = items,
        report = diagnosticReport,
        isRunning = isDiagnosing,
        failed = diagnosticFailed,
    ),
    onQueryChange: (String) -> Unit,
    onProductClick: (ProductId) -> Unit,
    onRunDiagnostic: () -> Unit,
    section: InventoryContract.ListSection = InventoryContract.ListSection.STOCK,
    profits: List<ProductProfit> = emptyList(),
    isProfitLoading: Boolean = false,
    profitFailure: InventoryContract.ProfitFailure? = null,
    salePriceEditor: InventoryContract.SalePriceEditor? = null,
    isSavingSalePrice: Boolean = false,
    onEditSalePrice: (ProductId) -> Unit = {},
    onSalePriceChange: (String) -> Unit = {},
    onSaveSalePrice: () -> Unit = {},
    onDismissSalePrice: () -> Unit = {},
    onRetryProfit: () -> Unit = {},
    onRegisterManual: () -> Unit = {},
    onRegisterSpecialProduct: () -> Unit = {},
    onRegisterProducts: () -> Unit = {},
    productActionsEnabled: Boolean = true,
    onEditProduct: (ProductId) -> Unit = {},
    onDeleteProduct: (ProductId) -> Unit = {},
    onSearchFocusChange: (Boolean) -> Unit = {},
) {
    val spacing = FacturaStockDesign.spacing
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag(InventoryTestTags.LIST_SCREEN),
        contentPadding = PaddingValues(spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        if (section == InventoryContract.ListSection.STOCK) {
            item(key = "name_search", contentType = "search") {
                InventorySearchField(
                    query = query,
                    enabled = productActionsEnabled,
                    onQueryChange = onQueryChange,
                    onFocusChange = onSearchFocusChange,
                )
            }
        }
        item(key = "register_products", contentType = "register_products") {
            FacturaStockPrimaryButton(
                text = stringResource(R.string.inventory_register_products),
                onClick = onRegisterProducts,
                enabled = productActionsEnabled,
                leadingIconRes = R.drawable.ic_barcode_scanner,
                modifier = Modifier.fillMaxWidth().testTag(InventoryTestTags.REGISTER_PRODUCTS),
            )
        }
        if (section == InventoryContract.ListSection.STOCK) {
            item(key = "register_product", contentType = "register_product") {
                InventoryRegisterActions(
                    onManualClicked = onRegisterManual,
                    onSpecialClicked = onRegisterSpecialProduct,
                    enabled = productActionsEnabled,
                )
            }
            if (showDiagnostic) {
                item(key = "diagnostic", contentType = "diagnostic") {
                    InventoryDiagnosticCard(
                        report = diagnosticReport,
                        isRunning = isDiagnosing,
                        failed = diagnosticFailed,
                        onRun = onRunDiagnostic,
                        modifier = Modifier.testTag(InventoryTestTags.DIAGNOSTIC),
                    )
                }
            }
            if (!isLoading && items.isNotEmpty()) {
                item(key = "count", contentType = "count") {
                    Text(
                        text = pluralStringResource(
                            R.plurals.inventory_product_count,
                            items.size,
                            items.size,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            when {
                isLoading -> item(key = "loading", contentType = "loading") {
                    LoadingState(message = stringResource(R.string.feature_loading_message))
                }
                items.isEmpty() -> item(key = "empty", contentType = "empty") {
                    val filtered = query.trim().length >= 2
                    EmptyState(
                        title = stringResource(
                            when {
                                filtered -> R.string.inventory_empty_filtered_title
                                else -> R.string.inventory_empty_title
                            },
                        ),
                        message = stringResource(
                            when {
                                filtered -> R.string.inventory_empty_filtered_message
                                else -> R.string.inventory_empty_message
                            },
                        ),
                        iconRes = R.drawable.ic_inventory,
                        actionLabel = if (filtered) {
                            stringResource(R.string.action_clear_search)
                        } else {
                            null
                        },
                        onAction = if (filtered) {
                            { onQueryChange("") }
                        } else {
                            null
                        },
                        modifier = Modifier.testTag(InventoryTestTags.EMPTY),
                    )
                }
                else -> items(
                    items = items,
                    key = { it.productId.value },
                    contentType = { "inventory_product" },
                ) { item ->
                    InventoryProductCard(
                        item = item,
                        onClick = { onProductClick(item.productId) },
                        actionsEnabled = productActionsEnabled,
                        onEdit = { onEditProduct(item.productId) },
                        onDelete = { onDeleteProduct(item.productId) },
                        modifier = Modifier.testTag(InventoryTestTags.product(item.productId)),
                    )
                }
            }
        } else {
            if (!isProfitLoading && profits.isNotEmpty()) {
                item(key = "profit_scope", contentType = "supporting_text") {
                    ProfitScopeNote()
                }
                item(key = "profit_count", contentType = "count") {
                    Text(
                        text = pluralStringResource(
                            R.plurals.inventory_profit_result_count,
                            profits.size,
                            profits.size,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            when {
                isProfitLoading -> item(key = "profit_loading", contentType = "loading") {
                    LoadingState(message = stringResource(R.string.inventory_profit_loading))
                }
                profitFailure == InventoryContract.ProfitFailure.LOAD_FAILED -> {
                    item(key = "profit_failure", contentType = "failure") {
                        com.facturastock.app.ui.components.RecoverableError(
                            title = stringResource(R.string.inventory_profit_load_error_title),
                            message = stringResource(R.string.inventory_profit_load_error_message),
                            actionLabel = stringResource(R.string.action_retry),
                            onAction = onRetryProfit,
                        )
                    }
                }
                profits.isEmpty() -> item(key = "profit_empty", contentType = "empty") {
                    EmptyState(
                        title = stringResource(R.string.inventory_profit_empty_title),
                        message = stringResource(
                            if (query.isBlank()) R.string.inventory_profit_empty_message
                            else R.string.inventory_empty_filtered_message,
                        ),
                        iconRes = R.drawable.ic_sale,
                        actionLabel = if (query.isNotBlank()) {
                            stringResource(R.string.action_clear_search)
                        } else {
                            null
                        },
                        onAction = if (query.isNotBlank()) {
                            { onQueryChange("") }
                        } else {
                            null
                        },
                        modifier = Modifier.testTag(InventoryTestTags.PROFIT_EMPTY),
                    )
                }
                else -> items(
                    items = profits,
                    key = { profit -> profit.productId.value },
                    contentType = { "product_profit" },
                ) { profit ->
                    ProductProfitCard(
                        profit = profit,
                        onEditPrice = { onEditSalePrice(profit.productId) },
                        modifier = Modifier.testTag(InventoryTestTags.profit(profit.productId)),
                    )
                }
            }
        }
    }

    salePriceEditor?.let { editor ->
        SalePriceDialog(
            editor = editor,
            isSaving = isSavingSalePrice,
            failure = profitFailure,
            onValueChange = onSalePriceChange,
            onSave = onSaveSalePrice,
            onDismiss = onDismissSalePrice,
        )
    }
}

@Composable
private fun InventorySearchField(
    query: String,
    enabled: Boolean,
    onQueryChange: (String) -> Unit,
    onFocusChange: (Boolean) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    val currentOnFocusChange by rememberUpdatedState(onFocusChange)
    DisposableEffect(Unit) {
        onDispose {
            if (focused) currentOnFocusChange(false)
        }
    }
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        label = { Text(stringResource(R.string.inventory_search_label)) },
        supportingText = { Text(stringResource(R.string.inventory_search_hint)) },
        leadingIcon = {
            Icon(painterResource(R.drawable.ic_search), contentDescription = null)
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(
                    onClick = { onQueryChange("") },
                    enabled = enabled,
                    modifier = Modifier.testTag(InventoryTestTags.SEARCH_CLEAR),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.action_clear_search),
                    )
                }
            }
        },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged {
                if (focused != it.isFocused) {
                    focused = it.isFocused
                    currentOnFocusChange(focused)
                }
            }
            .testTag(InventoryTestTags.SEARCH),
    )
}

@Composable
private fun ProfitScopeNote() {
    Text(
        text = stringResource(R.string.inventory_profit_message),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(InventoryTestTags.PROFIT_LIVE_REGION)
            .semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
}

internal fun inventoryDiagnosticIsRelevant(
    items: List<InventoryReadItem>,
    report: InventoryDiagnosticReport?,
    isRunning: Boolean,
    failed: Boolean,
): Boolean = isRunning ||
    failed ||
    report?.isConsistent == false ||
    items.any { item -> item.allAlerts.any(InventoryDataAlert::requiresDiagnostic) }

private fun InventoryDataAlert.requiresDiagnostic(): Boolean = when (this) {
    InventoryDataAlert.ARCHIVED_PRODUCT,
    InventoryDataAlert.ARCHIVED_LOCATION,
    -> false

    InventoryDataAlert.NEGATIVE_STOCK,
    InventoryDataAlert.MIXED_CURRENCIES,
    InventoryDataAlert.MISSING_MOVEMENT_COST,
    InventoryDataAlert.PROJECTION_DIVERGENCE,
    InventoryDataAlert.UNDEFINED_AGGREGATE_AVERAGE,
    -> true
}

@Composable
private fun ProductProfitCard(
    profit: ProductProfit,
    onEditPrice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FacturaStockDesign.spacing
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(spacing.borderThin, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Text(
                text = profit.productName,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium,
            )
            profit.sku?.let { sku ->
                Text(
                    text = stringResource(R.string.inventory_sku, sku),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            profit.salePrice?.let { price ->
                Text(
                    text = stringResource(
                        R.string.inventory_profit_sale_price,
                        price.formatForDisplay(),
                        profit.unitCode,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            if (profit.status == ProductProfitStatus.AVAILABLE) {
                val average = requireNotNull(profit.averageUnitCost)
                val unitProfit = requireNotNull(profit.unitProfit)
                val potential = requireNotNull(profit.potentialProfit)
                Text(
                    text = stringResource(
                        R.string.inventory_profit_average_cost,
                        average.amount.formatInventoryMoney(average.currency),
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(
                        R.string.inventory_profit_unit_profit,
                        unitProfit.amount.formatInventoryMoney(unitProfit.currency),
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(
                        R.string.inventory_profit_margin,
                        requireNotNull(profit.marginPercent).formatInventoryNumber(),
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(
                        R.string.inventory_profit_stock,
                        requireNotNull(profit.totalStockQuantity).formatInventoryNumber(),
                        profit.unitCode,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(
                        R.string.inventory_profit_potential,
                        potential.amount.formatInventoryMoney(potential.currency),
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Column(
                        modifier = Modifier.padding(spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(spacing.xxs),
                    ) {
                        Text(
                            text = stringResource(profit.status.titleRes()),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            text = stringResource(profit.status.messageRes()),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            if (profit.productStatus == CatalogStatus.ACTIVE) {
                val editDescription = stringResource(
                    R.string.inventory_profit_edit_price_accessibility,
                    profit.productName,
                )
                FacturaStockSecondaryButton(
                    text = stringResource(R.string.inventory_profit_edit_price),
                    onClick = onEditPrice,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(InventoryTestTags.editPrice(profit.productId))
                        .semantics { contentDescription = editDescription },
                )
            } else {
                Text(
                    text = stringResource(R.string.inventory_profit_archived),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun SalePriceDialog(
    editor: InventoryContract.SalePriceEditor,
    isSaving: Boolean,
    failure: InventoryContract.ProfitFailure?,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    FacturaStockDialog(
        title = stringResource(R.string.inventory_profit_price_dialog_title),
        message = stringResource(
            R.string.inventory_profit_price_dialog_message,
            editor.productName,
            editor.currency.value,
        ),
        confirmLabel = if (isSaving) {
            stringResource(R.string.inventory_profit_price_saving)
        } else {
            stringResource(R.string.inventory_profit_price_save)
        },
        dismissLabel = stringResource(R.string.action_cancel),
        onConfirm = onSave,
        onDismiss = onDismiss,
        confirmEnabled = !isSaving,
        dismissEnabled = !isSaving,
        modifier = Modifier.testTag(InventoryTestTags.PRICE_DIALOG),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(FacturaStockDesign.spacing.sm)) {
            OutlinedTextField(
                value = editor.value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(InventoryTestTags.PRICE_FIELD),
                label = {
                    Text(
                        stringResource(
                            R.string.inventory_profit_price_field_label,
                            editor.currency.value,
                        ),
                    )
                },
                supportingText = {
                    Text(
                        stringResource(
                            if (editor.submitAttempted && !editor.isValid) {
                                R.string.inventory_profit_price_invalid
                            } else {
                                R.string.inventory_profit_price_help
                            },
                        ),
                    )
                },
                isError = editor.submitAttempted && !editor.isValid,
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                ),
            )
            failure?.takeIf { it != InventoryContract.ProfitFailure.LOAD_FAILED }?.let {
                Text(
                    text = stringResource(it.messageRes()),
                    modifier = Modifier
                        .testTag(InventoryTestTags.PRICE_ERROR)
                        .semantics { liveRegion = LiveRegionMode.Assertive },
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            }
        },
    )
}

@Composable
private fun InventoryProductActions(
    item: InventoryReadItem,
    enabled: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (InventoryDataAlert.ARCHIVED_PRODUCT in item.allAlerts) return
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FacturaStockDesign.spacing.sm)) {
        FacturaStockSecondaryButton(
            text = stringResource(R.string.inventory_product_edit),
            onClick = onEdit,
            enabled = enabled && InventoryDataAlert.ARCHIVED_PRODUCT !in item.allAlerts,
            modifier = Modifier.weight(1f).testTag(InventoryTestTags.editProduct(item.productId)),
        )
        FacturaStockSecondaryButton(
            text = stringResource(R.string.inventory_product_delete),
            onClick = onDelete,
            enabled = enabled,
            modifier = Modifier.weight(1f).testTag(InventoryTestTags.deleteProduct(item.productId)),
        )
    }
}

@Composable
internal fun InventoryProductDeletionDialog(
    pending: InventoryContract.PendingProductDeletion,
    isBusy: Boolean,
    failure: InventoryContract.ProductActionFailure?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val retryable = failure == InventoryContract.ProductActionFailure.LOAD_FAILED ||
        failure == InventoryContract.ProductActionFailure.SAVE_FAILED
    val blocked = failure != null && !retryable
    if (blocked) {
        AlertDialog(
            onDismissRequest = { if (!isBusy) onDismiss() },
            title = { Text(stringResource(R.string.inventory_product_delete_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(FacturaStockDesign.spacing.sm)) {
                    Text(pending.productName)
                    InventoryProductActionError(requireNotNull(failure), deleting = true)
                }
            },
            confirmButton = {
                FacturaStockSecondaryButton(
                    text = stringResource(R.string.inventory_product_delete_close),
                    onClick = onDismiss,
                    enabled = !isBusy,
                )
            },
            modifier = Modifier.testTag(InventoryTestTags.PRODUCT_DELETE_DIALOG),
        )
        return
    }
    FacturaStockDialog(
        title = stringResource(R.string.inventory_product_delete_title),
        message = stringResource(R.string.inventory_product_delete_message, pending.productName),
        confirmLabel = stringResource(if (retryable) R.string.action_retry else R.string.inventory_product_delete_confirm),
        dismissLabel = stringResource(R.string.action_cancel),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        confirmEnabled = !isBusy && (retryable || pending.expectedVersion != null),
        dismissEnabled = !isBusy,
        modifier = Modifier.testTag(InventoryTestTags.PRODUCT_DELETE_DIALOG),
        content = {
            if (isBusy || (pending.expectedVersion == null && failure == null)) {
                LoadingState(
                    message = stringResource(
                        if (pending.expectedVersion == null) R.string.inventory_product_delete_checking
                        else R.string.inventory_product_delete_saving,
                    ),
                )
            }
            failure?.let {
                Text(
                    stringResource(R.string.inventory_product_delete_retry),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                )
            }
        },
    )
}

@Composable
internal fun InventoryProductActionError(
    failure: InventoryContract.ProductActionFailure,
    deleting: Boolean = false,
) {
    Text(
        text = stringResource(
            if (deleting && failure == InventoryContract.ProductActionFailure.STALE_PRODUCT) R.string.inventory_product_delete_stale
            else failure.messageRes(),
        ),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .testTag(InventoryTestTags.PRODUCT_ACTION_ERROR)
            .semantics { liveRegion = LiveRegionMode.Assertive },
    )
}

@StringRes
private fun InventoryContract.ProductActionFailure.messageRes(): Int = when (this) {
    InventoryContract.ProductActionFailure.LOAD_FAILED -> R.string.inventory_product_load_failed
    InventoryContract.ProductActionFailure.SAVE_FAILED -> R.string.inventory_product_save_failed
    InventoryContract.ProductActionFailure.BUSINESS_CHANGED -> R.string.inventory_product_business_changed
    InventoryContract.ProductActionFailure.PRODUCT_UNAVAILABLE -> R.string.inventory_product_unavailable
    InventoryContract.ProductActionFailure.STALE_PRODUCT -> R.string.inventory_product_stale
    InventoryContract.ProductActionFailure.HAS_HISTORY -> R.string.inventory_product_delete_has_history
    InventoryContract.ProductActionFailure.HAS_STOCK -> R.string.inventory_product_delete_has_stock
    InventoryContract.ProductActionFailure.SHARED_BUSINESS -> R.string.inventory_product_delete_shared
}

@Composable
private fun InventoryProductCard(
    item: InventoryReadItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    actionsEnabled: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val spacing = FacturaStockDesign.spacing
    Card(
        onClick = onClick,
        enabled = actionsEnabled,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(spacing.borderThin, MaterialTheme.colorScheme.outlineVariant),
    ) {
        InventoryProductContent(item = item, showPositions = false)
        InventoryProductActions(
            item = item,
            enabled = actionsEnabled,
            onEdit = onEdit,
            onDelete = onDelete,
            modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.sm),
        )
    }
}

@Composable
private fun InventoryProductSnapshotCard(item: InventoryReadItem) {
    val spacing = FacturaStockDesign.spacing
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(spacing.borderThin, MaterialTheme.colorScheme.outlineVariant),
    ) {
        InventoryProductContent(item = item, showPositions = true)
    }
}

@Composable
private fun InventoryProductContent(
    item: InventoryReadItem,
    showPositions: Boolean,
) {
    val spacing = FacturaStockDesign.spacing
    Column(
        modifier = Modifier.padding(spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        Text(
            text = item.productName,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleMedium,
        )
        item.sku?.let { sku ->
            Text(
                text = stringResource(R.string.inventory_sku, sku),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            text = stringResource(
                R.string.inventory_total_quantity,
                item.totalQuantityOnHand.formatInventoryNumber(),
                item.displayUnit(),
            ),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
        )
        item.estimatedValuesByCurrency.toSortedMap(compareBy(CurrencyCode::value))
            .forEach { (currency, value) ->
                val average = item.averageUnitCostsByCurrency[currency]
                Text(
                    text = stringResource(
                        R.string.inventory_total_average,
                        average?.amount?.formatInventoryMoney(currency)
                            ?: stringResource(R.string.inventory_value_unavailable),
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(
                        R.string.inventory_total_value,
                        value.formatInventoryMoney(currency),
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        Text(
            text = pluralStringResource(
                R.plurals.inventory_warehouse_count,
                item.positions.size,
                item.positions.size,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
        )
        if (showPositions) {
            item.positions.forEachIndexed { index, position ->
                if (index > 0) HorizontalDivider()
                InventoryPositionContent(
                    position = position,
                    unit = item.displayUnit(),
                    showLocationName = item.positions.size > 1,
                )
            }
        }
        if (item.allAlerts.isNotEmpty()) {
            InventoryAlerts(item.allAlerts)
        }
    }
}

@Composable
private fun InventoryPositionContent(
    position: InventoryReadPosition,
    unit: String,
    showLocationName: Boolean = false,
) {
    val spacing = FacturaStockDesign.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
        if (showLocationName) {
            Text(
                text = position.locationName,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.titleSmall,
            )
        }
        Text(
            text = stringResource(
                R.string.inventory_position_quantity,
                position.quantityOnHand.formatInventoryNumber(),
                unit,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(
                R.string.inventory_position_average,
                position.averageUnitCost.amount.formatInventoryMoney(
                    position.averageUnitCost.currency,
                ),
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(
                R.string.inventory_position_value,
                position.estimatedValue.formatInventoryMoney(
                    position.averageUnitCost.currency,
                ),
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun InventoryAlerts(alerts: Set<InventoryDataAlert>) {
    val spacing = FacturaStockDesign.spacing
    val orderedAlerts = remember(alerts) { alerts.sortedBy(Enum<*>::name) }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = FacturaStockDesign.semanticColors.warningContainer,
        contentColor = FacturaStockDesign.semanticColors.onWarningContainer,
    ) {
        Column(
            modifier = Modifier.padding(spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.xxs),
        ) {
            Text(
                text = stringResource(R.string.inventory_alert_title),
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelLarge,
            )
            orderedAlerts.forEach { alert ->
                Text(
                    text = stringResource(alert.labelRes()),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun InventoryDiagnosticCard(
    report: InventoryDiagnosticReport?,
    isRunning: Boolean,
    failed: Boolean,
    onRun: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = when {
        isRunning -> DiagnosticPresentation(
            R.string.inventory_diagnostic_running_status,
            R.string.inventory_diagnostic_running_message,
            StatusTone.INFO,
            R.drawable.ic_refresh,
        )
        failed -> DiagnosticPresentation(
            R.string.inventory_diagnostic_failed_status,
            R.string.inventory_diagnostic_failed_message,
            StatusTone.ERROR,
            R.drawable.ic_warning,
        )
        report == null -> DiagnosticPresentation(
            R.string.inventory_diagnostic_idle_status,
            R.string.inventory_diagnostic_idle_message,
            StatusTone.NEUTRAL,
            R.drawable.ic_inventory,
        )
        report.isConsistent -> DiagnosticPresentation(
            R.string.inventory_diagnostic_ok_status,
            R.string.inventory_diagnostic_ok_message,
            StatusTone.SUCCESS,
            R.drawable.ic_check_circle,
        )
        else -> DiagnosticPresentation(
            R.string.inventory_diagnostic_warning_status,
            R.string.inventory_diagnostic_warning_message,
            StatusTone.WARNING,
            R.drawable.ic_warning,
        )
    }
    val message = when {
        report != null && report.isConsistent -> stringResource(
            status.messageRes,
            report.movementCount,
        )
        report != null && !report.isConsistent -> stringResource(
            status.messageRes,
            report.divergenceCount,
            report.positions.size,
        )
        else -> stringResource(status.messageRes)
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(FacturaStockDesign.spacing.sm),
    ) {
        StatusCard(
            statusLabel = stringResource(status.statusRes),
            title = stringResource(R.string.inventory_diagnostic_title),
            message = message,
            tone = status.tone,
            iconRes = status.iconRes,
            supportingContent = {
                report?.positions?.filterNot { it.matches }?.take(MAX_DIAGNOSTIC_ROWS)
                    ?.forEach { DiagnosticIssueContent(it) }
            },
        )
        FacturaStockSecondaryButton(
            text = stringResource(R.string.inventory_diagnostic_action),
            onClick = onRun,
            enabled = !isRunning,
            leadingIconRes = R.drawable.ic_refresh,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(InventoryTestTags.RUN_DIAGNOSTIC),
        )
    }
}

@Composable
private fun DiagnosticIssueContent(position: InventoryDiagnosticPosition) {
    val spacing = FacturaStockDesign.spacing
    val orderedIssues = remember(position.issues) {
        position.issues.sortedBy(Enum<*>::name)
    }
    Column(
        modifier = Modifier.padding(top = spacing.xs),
        verticalArrangement = Arrangement.spacedBy(spacing.xxs),
    ) {
        Text(
            text = position.productName,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelLarge,
        )
        Text(text = position.locationName, style = MaterialTheme.typography.bodySmall)
        Text(
            text = stringResource(
                R.string.inventory_diagnostic_issue_quantity,
                position.cachedQuantity?.formatInventoryNumber()
                    ?: stringResource(R.string.inventory_value_unavailable),
                position.ledgerQuantity?.formatInventoryNumber()
                    ?: stringResource(R.string.inventory_value_unavailable),
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        if (
            position.cachedAverageUnitCost != null ||
            position.ledgerAverageUnitCost != null
        ) {
            Text(
                text = stringResource(
                    R.string.inventory_diagnostic_issue_cost,
                    position.cachedAverageUnitCost?.formatInventoryNumber()
                        ?: stringResource(R.string.inventory_value_unavailable),
                    position.ledgerAverageUnitCost?.formatInventoryNumber()
                        ?: stringResource(R.string.inventory_value_unavailable),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        orderedIssues.forEach { issue ->
            Text(text = stringResource(issue.labelRes()), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun InventoryProductDetailScreen(
    detail: InventoryProductDetail,
    onOpenPurchase: (PurchaseId) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    productActionsEnabled: Boolean = true,
    onEditProduct: (ProductId) -> Unit = {},
    onDeleteProduct: (ProductId) -> Unit = {},
) {
    val spacing = FacturaStockDesign.spacing
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(InventoryTestTags.DETAIL_SCREEN),
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag(InventoryTestTags.DETAIL_LIST),
            contentPadding = PaddingValues(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            item(key = "header", contentType = "header") {
                Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    Text(
                        text = stringResource(R.string.inventory_detail_title),
                        modifier = Modifier.semantics { heading() },
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.headlineLarge,
                    )
                    Text(
                        text = detail.item.productName,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = stringResource(R.string.inventory_detail_message),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            item(key = "product_actions", contentType = "product_actions") {
                InventoryProductActions(
                    item = detail.item,
                    enabled = productActionsEnabled,
                    onEdit = { onEditProduct(detail.item.productId) },
                    onDelete = { onDeleteProduct(detail.item.productId) },
                )
            }
            item(key = "positions_header", contentType = "section_header") {
                SectionTitle(stringResource(R.string.inventory_positions_section))
            }
            item(key = "positions", contentType = "positions") {
                InventoryProductSnapshotCard(item = detail.item)
            }
            item(key = "movements_header", contentType = "section_header") {
                SectionTitle(
                    stringResource(R.string.inventory_movements_section, detail.movements.size),
                )
            }
            if (detail.movements.isEmpty()) {
                item(key = "movements_empty", contentType = "empty") {
                    EmptyState(
                        title = stringResource(R.string.inventory_no_movements),
                        message = stringResource(R.string.inventory_detail_message),
                    )
                }
            } else {
                items(
                    items = detail.movements,
                    key = InventoryReadMovement::movementId,
                    contentType = { "movement" },
                ) { movement ->
                    InventoryMovementCard(
                        movement = movement,
                        unit = detail.item.displayUnit(),
                        onOpenPurchase = onOpenPurchase,
                        modifier = Modifier.testTag(
                            InventoryTestTags.movement(movement.movementId),
                        ),
                    )
                }
            }
        }
        FacturaStockPrimaryButton(
            text = stringResource(R.string.action_back),
            onClick = onBack,
            enabled = productActionsEnabled,
            leadingIconRes = R.drawable.ic_back,
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.lg)
                .testTag(InventoryTestTags.BACK),
        )
    }
}

@Composable
private fun InventoryMovementCard(
    movement: InventoryReadMovement,
    unit: String,
    onOpenPurchase: (PurchaseId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FacturaStockDesign.spacing
    Card(
        modifier = modifier.fillMaxWidth(),
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
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(movement.type.labelRes()),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = movement.locationName,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Text(
                text = stringResource(
                    R.string.inventory_movement_delta,
                    movement.quantityDelta.formatSignedInventoryNumber(),
                    unit,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = movement.unitCost?.let { cost ->
                    stringResource(
                        R.string.inventory_movement_cost,
                        cost.amount.formatInventoryMoney(cost.currency),
                    )
                } ?: stringResource(R.string.inventory_movement_no_cost),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(
                    R.string.inventory_movement_date,
                    movement.occurredAt.formatForDisplay(),
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.inventory_movement_immutable),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
            val purchaseId = movement.purchaseId
            val document = movement.purchaseDocumentNumber
            if (purchaseId != null && document != null) {
                FacturaStockSecondaryButton(
                    text = stringResource(R.string.inventory_movement_open_purchase, document),
                    onClick = { onOpenPurchase(purchaseId) },
                    leadingIconRes = R.drawable.ic_receipt,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(InventoryTestTags.openPurchase(movement.movementId)),
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.semantics { heading() },
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.titleLarge,
    )
}

private data class DiagnosticPresentation(
    @param:StringRes val statusRes: Int,
    @param:StringRes val messageRes: Int,
    val tone: StatusTone,
    val iconRes: Int,
)

@StringRes
private fun InventoryDataAlert.labelRes(): Int = when (this) {
    InventoryDataAlert.NEGATIVE_STOCK -> R.string.inventory_alert_negative
    InventoryDataAlert.ARCHIVED_PRODUCT -> R.string.inventory_alert_archived_product
    InventoryDataAlert.ARCHIVED_LOCATION -> R.string.inventory_alert_archived_location
    InventoryDataAlert.MIXED_CURRENCIES -> R.string.inventory_alert_mixed_currencies
    InventoryDataAlert.MISSING_MOVEMENT_COST -> R.string.inventory_alert_missing_cost
    InventoryDataAlert.PROJECTION_DIVERGENCE -> R.string.inventory_alert_projection_divergence
    InventoryDataAlert.UNDEFINED_AGGREGATE_AVERAGE ->
        R.string.inventory_alert_undefined_average
}

@StringRes
private fun InventoryDiagnosticIssue.labelRes(): Int = when (this) {
    InventoryDiagnosticIssue.MISSING_CACHED_BALANCE ->
        R.string.inventory_diagnostic_issue_missing_balance
    InventoryDiagnosticIssue.QUANTITY_DIVERGENCE ->
        R.string.inventory_diagnostic_issue_quantity_divergence
    InventoryDiagnosticIssue.AVERAGE_COST_DIVERGENCE ->
        R.string.inventory_diagnostic_issue_cost_divergence
    InventoryDiagnosticIssue.CURRENCY_DIVERGENCE ->
        R.string.inventory_diagnostic_issue_currency
    InventoryDiagnosticIssue.MISSING_MOVEMENT_COST,
    InventoryDiagnosticIssue.COST_UNVERIFIABLE,
    -> R.string.inventory_diagnostic_issue_unverifiable
    InventoryDiagnosticIssue.ORDER_AMBIGUOUS -> R.string.inventory_diagnostic_issue_order
    InventoryDiagnosticIssue.UNEXPLAINED_OPENING_BALANCE ->
        R.string.inventory_diagnostic_issue_opening
    InventoryDiagnosticIssue.INVALID_LEDGER_DATA -> R.string.inventory_diagnostic_issue_invalid
}

@StringRes
private fun StockMovementType.labelRes(): Int = when (this) {
    StockMovementType.PURCHASE -> R.string.inventory_movement_purchase
    StockMovementType.SALE -> R.string.inventory_movement_sale
    StockMovementType.SALE_VOID -> R.string.inventory_movement_sale_void
    StockMovementType.VOID -> R.string.inventory_movement_void
    StockMovementType.ADJUSTMENT -> R.string.inventory_movement_adjustment
}

@StringRes
private fun ProductProfitStatus.titleRes(): Int = when (this) {
    ProductProfitStatus.AVAILABLE -> R.string.inventory_profit_status_available
    ProductProfitStatus.MISSING_SALE_PRICE -> R.string.inventory_profit_status_missing_price
    ProductProfitStatus.NO_STOCK -> R.string.inventory_profit_status_no_stock
    ProductProfitStatus.NON_COMPARABLE_CURRENCY ->
        R.string.inventory_profit_status_non_comparable
    ProductProfitStatus.INVALID_INVENTORY -> R.string.inventory_profit_status_invalid
}

@StringRes
private fun ProductProfitStatus.messageRes(): Int = when (this) {
    ProductProfitStatus.AVAILABLE -> R.string.inventory_profit_status_available_message
    ProductProfitStatus.MISSING_SALE_PRICE -> R.string.inventory_profit_status_missing_price_message
    ProductProfitStatus.NO_STOCK -> R.string.inventory_profit_status_no_stock_message
    ProductProfitStatus.NON_COMPARABLE_CURRENCY ->
        R.string.inventory_profit_status_non_comparable_message
    ProductProfitStatus.INVALID_INVENTORY -> R.string.inventory_profit_status_invalid_message
}

@StringRes
private fun InventoryContract.ProfitFailure.messageRes(): Int = when (this) {
    InventoryContract.ProfitFailure.LOAD_FAILED -> R.string.inventory_profit_load_error_message
    InventoryContract.ProfitFailure.INVALID_PRICE -> R.string.inventory_profit_price_invalid
    InventoryContract.ProfitFailure.STALE_PRICE -> R.string.inventory_profit_price_stale
    InventoryContract.ProfitFailure.CURRENCY_MISMATCH -> R.string.inventory_profit_price_currency
    InventoryContract.ProfitFailure.PRODUCT_UNAVAILABLE ->
        R.string.inventory_profit_price_unavailable
    InventoryContract.ProfitFailure.SAVE_FAILED -> R.string.inventory_profit_price_save_failed
}

private fun InventoryReadItem.displayUnit(): String = unitSymbol?.takeIf(String::isNotBlank)
    ?: unitCode

private fun BigDecimal.formatInventoryNumber(): String = stripTrailingZeros().toPlainString()

private fun BigDecimal.formatSignedInventoryNumber(): String {
    val value = formatInventoryNumber()
    return if (signum() > 0) "+$value" else value
}

private fun BigDecimal.formatInventoryMoney(currency: CurrencyCode): String =
    "${currency.value} ${formatInventoryNumber()}"

private const val MAX_DIAGNOSTIC_ROWS = 5

/** Alta manual de productos desde Inventario; el lector físico registra desde su modo propio. */
@Composable
private fun InventoryRegisterActions(
    onManualClicked: () -> Unit,
    onSpecialClicked: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(FacturaStockDesign.spacing.sm)) {
        FacturaStockPrimaryButton(
            text = stringResource(R.string.inventory_register_manual),
            onClick = onManualClicked,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(InventoryTestTags.REGISTER_MANUAL),
        )
        FacturaStockSecondaryButton(
            text = stringResource(R.string.inventory_register_special_product),
            onClick = onSpecialClicked,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag(InventoryTestTags.REGISTER_SPECIAL_PRODUCT),
        )
    }
}

object InventoryTestTags {
    const val LIST_SCREEN = "inventory_screen"
    const val REGISTER_PRODUCTS = "inventory_register_products"
    const val REGISTRATION_SCREEN = "inventory_registration_screen"
    const val REGISTRATION_STATUS = "inventory_registration_status"
    const val DETAIL_SCREEN = "inventory_detail_screen"
    const val DETAIL_LIST = "inventory_detail_list"
    const val SEARCH = "inventory_search"
    const val SEARCH_CLEAR = "inventory_search_clear"
    const val SEARCH_MODE = "inventory_search_mode"
    const val SCANNER_MODE = "inventory_scanner_mode"
    const val SCANNER_STATUS = "inventory_scanner_status"
    const val DIAGNOSTIC = "inventory_diagnostic"
    const val RUN_DIAGNOSTIC = "inventory_run_diagnostic"
    const val EMPTY = "inventory_empty"
    const val BACK = "inventory_back"
    const val SECTION_STOCK = "inventory_section_stock"
    const val SECTION_PROFIT = "inventory_section_profit"
    const val PROFIT_LIVE_REGION = "inventory_profit_live_region"
    const val PROFIT_EMPTY = "inventory_profit_empty"
    const val PRICE_DIALOG = "inventory_profit_price_dialog"
    const val PRICE_FIELD = "inventory_profit_price_field"
    const val PRICE_ERROR = "inventory_profit_price_error"
    const val REGISTER_ACTIONS = "inventory_register_actions"
    const val REGISTER_MANUAL = "inventory_register_manual"
    const val REGISTER_SPECIAL_PRODUCT = "inventory_register_special_product"
    const val REGISTER_UNMATCHED = "inventory_register_unmatched"
    const val PRODUCT_ACTION_ERROR = "inventory_product_action_error"
    const val PRODUCT_DELETE_DIALOG = "inventory_product_delete_dialog"

    fun editProduct(productId: ProductId): String = "inventory_edit_${productId.value}"
    fun deleteProduct(productId: ProductId): String = "inventory_delete_${productId.value}"

    fun product(productId: ProductId): String = "inventory_product_${productId.value}"
    fun profit(productId: ProductId): String = "inventory_profit_${productId.value}"
    fun editPrice(productId: ProductId): String = "inventory_profit_edit_${productId.value}"
    fun movement(movementId: String): String = "inventory_movement_$movementId"
    fun openPurchase(movementId: String): String = "inventory_open_purchase_$movementId"
}
