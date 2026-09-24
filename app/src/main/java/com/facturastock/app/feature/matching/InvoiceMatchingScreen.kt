package com.facturastock.app.feature.matching

import org.jetbrains.compose.resources.StringResource

import com.facturastock.app.resources.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.facturastock.app.domain.model.InvoiceMatchUnitChoice
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.ScannedItemMatch
import com.facturastock.app.feature.matching.InvoiceMatchingContract.Action
import com.facturastock.app.feature.matching.InvoiceMatchingContract.Failure
import com.facturastock.app.feature.matching.InvoiceMatchingContract.State
import com.facturastock.app.ui.components.FacturaStockPrimaryButton
import com.facturastock.app.ui.components.FacturaStockSecondaryButton
import com.facturastock.app.ui.components.LoadingState
import com.facturastock.app.ui.components.RecoverableError
import com.facturastock.app.ui.theme.FacturaStockDesign

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvoiceMatchingScreen(
    state: State,
    onAction: (Action) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FacturaStockDesign.spacing

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(Res.string.matching_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { onAction(Action.BackSelected) }) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_back),
                            contentDescription = stringResource(Res.string.action_cancel),
                        )
                    }
                },
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(spacing.md),
                ) {
                    FacturaStockPrimaryButton(
                        text =
                            stringResource(
                                Res.string.matching_action_save_to_warehouse,
                                state.readyCount,
                            ),
                        onClick = { onAction(Action.SaveToWarehouse) },
                        enabled = state.canSaveToWarehouse && !state.isSaving,
                        modifier = Modifier.fillMaxWidth().testTag("matching_save_warehouse"),
                    )
                }
            }
        },
        modifier = modifier.imePadding(),
    ) { innerPadding ->
        when {
            state.isLoading -> {
                LoadingState(
                    message = stringResource(Res.string.linking_loading),
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                )
            }

            state.blocksList -> {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(spacing.lg),
                    contentAlignment = Alignment.Center,
                ) {
                    RecoverableError(
                        title = stringResource(Res.string.feature_load_error_title),
                        message = stringResource(matchingFailureMessage(state.failure)),
                        actionLabel = stringResource(Res.string.action_retry),
                        onAction = { onAction(Action.Start) },
                    )
                }
            }

            state.items.isEmpty() -> {
                Column(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(spacing.lg),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = stringResource(Res.string.matching_empty_lines_action),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = spacing.md),
                    )
                    FacturaStockPrimaryButton(
                        text = stringResource(Res.string.matching_add_product_cta),
                        onClick = { onAction(Action.OpenAddNewProductDialog) },
                    )
                }
            }

            else -> {
                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                    contentPadding = PaddingValues(spacing.md),
                    verticalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    item(key = "header_subtitle") {
                        Text(
                            text = stringResource(Res.string.matching_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = spacing.xs),
                        )
                    }
                    state.recoveryMessage?.let { message ->
                        item(key = "recovery_message") {
                            Text(message, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (state.saveFailure != null) {
                        item(key = "save_failure") {
                            RecoverableError(
                                title = stringResource(Res.string.feature_load_error_title),
                                message = stringResource(matchingFailureMessage(state.saveFailure)),
                                actionLabel = stringResource(Res.string.action_retry),
                                onAction = { onAction(Action.SaveToWarehouse) },
                            )
                        }
                    }

                    items(
                        items = state.items,
                        key = { item -> item.lineIndex },
                        contentType = { "scanned_item" },
                    ) { item ->
                        ScannedItemCard(
                            item = item,
                            ready = item.isReadyForInventory(state.businessCurrency),
                            onReview = { onAction(Action.OpenLineEditor(item.lineIndex)) },
                            onLink = { onAction(Action.OpenLinkingDialog(item.lineIndex)) },
                            onCreate = { onAction(Action.OpenCreateDialog(item.lineIndex)) },
                            onSuggestionClick = { product ->
                                onAction(Action.QuickSuggestionSelected(item.lineIndex, product))
                            },
                        )
                    }

                    item(key = "add_another_product") {
                        FacturaStockSecondaryButton(
                            text = stringResource(Res.string.matching_add_another_product_cta),
                            onClick = { onAction(Action.OpenAddNewProductDialog) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }

    // Modal de Vinculación con un producto existente
    state.editingItemIndex?.let { index ->
        state.items.firstOrNull { it.lineIndex == index }?.let { item ->
            LineReviewDialog(state, item, onAction)
        }
    }
    if (state.linkingItemIndex != null) {
        ProductSearchDialog(
            query = state.searchQuery,
            results = state.searchResults,
            isSearching = state.isSearching,
            searchFailed = state.searchFailed,
            onRetry = { onAction(Action.RetrySearch) },
            onQueryChanged = { onAction(Action.SearchQueryChanged(it)) },
            onProductSelected = { product ->
                onAction(Action.ProductSelected(state.linkingItemIndex, product))
            },
            onDismiss = { onAction(Action.CloseLinkingDialog) },
        )
    }

    // Modal de Creación de un producto nuevo
    if (state.creatingItemIndex != null) {
        ProductCreateDialog(
            name = state.createName,
            barcode = state.createBarcode,
            price = state.createPrice,
            quantity = state.createQuantity,
            isSaving = state.isSaving,
            createError = state.createError,
            onNameChanged = { onAction(Action.CreateNameChanged(it)) },
            onBarcodeChanged = { onAction(Action.CreateBarcodeChanged(it)) },
            onPriceChanged = { onAction(Action.CreatePriceChanged(it)) },
            onQuantityChanged = { onAction(Action.CreateQuantityChanged(it)) },
            onSubmit = { onAction(Action.SubmitCreateProduct) },
            onDismiss = { onAction(Action.CloseCreateDialog) },
        )
    }
}

@Composable
private fun ScannedItemCard(
    item: ScannedItemMatch,
    ready: Boolean,
    onReview: () -> Unit,
    onLink: () -> Unit,
    onCreate: () -> Unit,
    onSuggestionClick: (Product) -> Unit,
) {
    val spacing = FacturaStockDesign.spacing
    val isMatched = item.matchedProduct != null

    Card(
        shape = RoundedCornerShape(12.dp),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (ready) {
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    },
            ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            // Fila de descripción leída y cantidad
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.rawDescription,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.padding(start = spacing.sm),
                ) {
                    Text(
                        text =
                            item.quantity?.let { quantity ->
                                stringResource(
                                    Res.string.matching_line_quantity,
                                    quantity.stripTrailingZeros().toPlainString(),
                                )
                            } ?: stringResource(Res.string.matching_line_quantity_unknown),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xxs),
                    )
                }
            }

            // Datos secundarios leídos (costo unitario o código de barras)
            if (item.unitCost != null || !item.printedCode.isNullOrBlank() || !item.barcode.isNullOrBlank()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    item.unitCost?.let { cost ->
                        Text(
                            text =
                                stringResource(
                                    Res.string.matching_line_cost,
                                    cost.stripTrailingZeros().toPlainString(),
                                ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    item.printedCode?.let { code ->
                        Text(
                            text = stringResource(Res.string.matching_line_code, code),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    item.barcode?.let { code ->
                        Text(
                            text = stringResource(Res.string.catalog_barcode_label) + ": $code",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = spacing.xxs),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )

            // Estado de la vinculación
            if (isMatched) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            painter = painterResource(if (ready) Res.drawable.ic_check_circle else Res.drawable.ic_warning),
                            contentDescription = null,
                            tint = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text =
                                stringResource(
                                    if (ready) Res.string.matching_status_ready else Res.string.matching_review_linked,
                                    item.matchedProduct.name,
                                ),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    TextButton(onClick = onLink) {
                        Text(stringResource(Res.string.matching_action_change))
                    }
                }
            } else {
                // Sugerencias rápidas si se encontraron nombres parecidos
                if (item.suggestedProducts.isNotEmpty()) {
                    Text(
                        text = stringResource(Res.string.matching_suggestions_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
                    ) {
                        items(
                            items = item.suggestedProducts,
                            key = { suggestion -> suggestion.productId.value },
                            contentType = { "suggestion" },
                        ) { suggestion ->
                            SuggestionChip(
                                onClick = { onSuggestionClick(suggestion) },
                                label = { Text(suggestion.name) },
                            )
                        }
                    }
                }

                // Botones principales para resolver la línea
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = spacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    OutlinedButton(
                        onClick = onLink,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(Res.string.matching_action_link))
                    }
                    OutlinedButton(
                        onClick = onCreate,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(Res.string.matching_action_create))
                    }
                }
            }

            TextButton(onClick = onReview, modifier = Modifier.testTag("matching_review_${item.lineIndex}")) {
                Text(stringResource(if (ready) Res.string.matching_edit_numbers else Res.string.matching_review_required))
            }
        }
    }
}

@Composable
private fun LineReviewDialog(
    state: State,
    item: ScannedItemMatch,
    onAction: (Action) -> Unit,
) {
    Dialog(onDismissRequest = { onAction(Action.CloseLineEditor) }) {
        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(item.rawDescription, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(Res.string.matching_review_explanation), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = state.editQuantity,
                    onValueChange = { onAction(Action.EditQuantityChanged(it)) },
                    label = { Text(stringResource(Res.string.catalog_product_quantity_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("matching_edit_quantity"),
                )
                OutlinedTextField(
                    value = state.editCost,
                    onValueChange = { onAction(Action.EditCostChanged(it)) },
                    label = { Text(stringResource(Res.string.matching_review_cost)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("matching_edit_cost"),
                )
                OutlinedTextField(
                    value = state.editCurrency,
                    onValueChange = { onAction(Action.EditCurrencyChanged(it)) },
                    label = { Text(stringResource(Res.string.matching_review_currency, state.businessCurrency.value)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("matching_edit_currency"),
                )
                Text(stringResource(Res.string.matching_review_currency_help), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(Res.string.matching_review_unit, item.sourceUnitCode ?: "—"))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { onAction(Action.EditUnitChoiceChanged(InvoiceMatchUnitChoice.INVENTORY)) },
                ) {
                    RadioButton(
                        selected = state.editUnitChoice == InvoiceMatchUnitChoice.INVENTORY,
                        onClick = { onAction(Action.EditUnitChoiceChanged(InvoiceMatchUnitChoice.INVENTORY)) },
                    )
                    Text(stringResource(Res.string.matching_review_inventory_unit, item.inventoryUnitCode ?: "—"))
                }
                if (item.matchedProduct?.purchaseUnitId != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { onAction(Action.EditUnitChoiceChanged(InvoiceMatchUnitChoice.PURCHASE)) },
                    ) {
                        RadioButton(
                            selected = state.editUnitChoice == InvoiceMatchUnitChoice.PURCHASE,
                            onClick = { onAction(Action.EditUnitChoiceChanged(InvoiceMatchUnitChoice.PURCHASE)) },
                        )
                        Text(
                            stringResource(
                                Res.string.matching_review_purchase_unit,
                                item.purchaseUnitCode ?: "—",
                                item.matchedProduct.purchaseFactor
                                    ?.stripTrailingZeros()
                                    ?.toPlainString() ?: "—",
                            ),
                        )
                    }
                }
                state.editError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                state.recoveryMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FacturaStockSecondaryButton(
                        text = stringResource(Res.string.action_cancel),
                        onClick = { onAction(Action.CloseLineEditor) },
                        modifier = Modifier.weight(1f),
                    )
                    FacturaStockPrimaryButton(
                        text = stringResource(Res.string.action_save),
                        onClick = { onAction(Action.SubmitLineEdit) },
                        modifier = Modifier.weight(1f).testTag("matching_save_review"),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProductSearchDialog(
    query: String,
    results: List<Product>,
    isSearching: Boolean,
    searchFailed: Boolean,
    onRetry: () -> Unit,
    onQueryChanged: (String) -> Unit,
    onProductSelected: (Product) -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = FacturaStockDesign.spacing

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp),
        ) {
            Column(
                modifier = Modifier.padding(spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Text(
                    text = stringResource(Res.string.matching_dialog_link_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )

                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChanged,
                    placeholder = { Text(stringResource(Res.string.matching_search_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (isSearching) {
                    LoadingState(message = stringResource(Res.string.matching_search_loading))
                } else if (searchFailed) {
                    RecoverableError(
                        title = stringResource(Res.string.feature_load_error_title),
                        message = stringResource(Res.string.matching_search_error),
                        actionLabel = stringResource(Res.string.action_retry),
                        onAction = onRetry,
                    )
                } else if (results.isEmpty()) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                                .padding(vertical = spacing.xl),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(Res.string.matching_dialog_link_no_results),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(spacing.xs),
                    ) {
                        items(results, key = { it.productId.value }) { product ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { onProductSelected(product) },
                            ) {
                                Column(
                                    modifier = Modifier.padding(spacing.sm),
                                ) {
                                    Text(
                                        text = product.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    product.barcode?.let { code ->
                                        Text(
                                            text = stringResource(Res.string.catalog_barcode_label) + ": $code",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(Res.string.action_cancel))
                    }
                }
            }
        }
    }
}

@Composable
private fun ProductCreateDialog(
    name: String,
    barcode: String,
    price: String,
    quantity: String,
    isSaving: Boolean,
    createError: String? = null,
    onNameChanged: (String) -> Unit,
    onBarcodeChanged: (String) -> Unit,
    onPriceChanged: (String) -> Unit,
    onQuantityChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = FacturaStockDesign.spacing

    Dialog(onDismissRequest = { if (!isSaving) onDismiss() }) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(spacing.md),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                Text(
                    text = stringResource(Res.string.matching_dialog_create_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )

                if (!createError.isNullOrBlank()) {
                    Text(
                        text = createError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                OutlinedTextField(
                    value = barcode,
                    onValueChange = onBarcodeChanged,
                    enabled = !isSaving,
                    label = { Text(stringResource(Res.string.catalog_barcode_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChanged,
                    enabled = !isSaving,
                    label = { Text(stringResource(Res.string.catalog_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = price,
                    onValueChange = onPriceChanged,
                    enabled = !isSaving,
                    label = { Text(stringResource(Res.string.catalog_product_price_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = quantity,
                    onValueChange = onQuantityChanged,
                    enabled = !isSaving,
                    label = { Text(stringResource(Res.string.catalog_product_quantity_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    FacturaStockSecondaryButton(
                        text = stringResource(Res.string.action_cancel),
                        onClick = onDismiss,
                        enabled = !isSaving,
                        modifier = Modifier.weight(1f),
                    )
                    FacturaStockPrimaryButton(
                        text = stringResource(Res.string.action_save),
                        onClick = onSubmit,
                        enabled = name.isNotBlank() && !isSaving,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

private fun matchingFailureMessage(failure: Failure?): StringResource =
    when (failure) {
        Failure.NO_ACTIVE_BUSINESS -> Res.string.matching_error_no_business

        Failure.INVALID_DRAFT_ID -> Res.string.matching_error_invalid_draft

        Failure.CLOUD_BOUND -> Res.string.matching_error_cloud_bound

        Failure.UNRESOLVED_LINES -> Res.string.matching_error_unresolved

        Failure.NOTHING_TO_APPLY -> Res.string.matching_error_nothing_to_apply

        Failure.MISSING_LOCATION -> Res.string.matching_error_missing_location

        Failure.REVIEW_REQUIRED -> Res.string.matching_error_review_required

        Failure.DRAFT_CHANGED -> Res.string.matching_error_draft_changed

        Failure.LEGACY_CONFLICT -> Res.string.matching_error_legacy_conflict

        Failure.SAVE_FAILED,
        Failure.LOAD_FAILED,
        null,
        -> Res.string.feature_load_error_message
    }
