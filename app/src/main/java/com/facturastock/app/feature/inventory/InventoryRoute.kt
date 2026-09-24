package com.facturastock.app.feature.inventory

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.facturastock.app.R
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.feature.common.CollectUiEffects
import com.facturastock.app.feature.common.FeatureLoadContent
import com.facturastock.app.feature.common.ScannerCodeInput
import com.facturastock.app.ui.components.FacturaStockSecondaryButton
import com.facturastock.app.ui.components.FacturaStockTopBarAction
import com.facturastock.app.ui.components.RecoverableError
import com.facturastock.app.ui.theme.FacturaStockDesign

@Composable
fun InventoryRoute(
    onOpenProduct: (ProductId) -> Unit,
    onOpenPurchase: (PurchaseId) -> Unit,
    onRegisterProduct: (String?) -> Unit,
    onBack: () -> Unit,
    onCloseInvalidRoute: () -> Unit,
    modifier: Modifier = Modifier,
    onRegisterProducts: () -> Unit = {},
    onRegisterSpecialProduct: () -> Unit = {},
    onEditProduct: (ProductId) -> Unit = {},
    onTopBarActionsAvailable: (List<FacturaStockTopBarAction>) -> Unit = {},
    viewModel: InventoryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val diagnosticFailed = state.failure == InventoryContract.Failure.DIAGNOSTIC_FAILED
    val showDiagnostic = remember(state.allItems, state.diagnosticReport, state.isDiagnosing, diagnosticFailed) {
        inventoryDiagnosticIsRelevant(
            items = state.allItems,
            report = state.diagnosticReport,
            isRunning = state.isDiagnosing,
            failed = diagnosticFailed,
        )
    }

    val scanner = if (state.productId == null) rememberInventoryScannerInput(state, viewModel) else null
    InventoryTopBarActions(
        state = state,
        onRegisterProducts = onRegisterProducts,
        onRegisterManual = { viewModel.onAction(InventoryContract.Action.RegisterProductManual) },
        onRegisterSpecialProduct = onRegisterSpecialProduct,
        onTopBarActionsAvailable = onTopBarActionsAvailable,
    )

    CollectUiEffects(viewModel.effects) { effect ->
        when (effect) {
            is InventoryContract.Effect.OpenProduct -> {
                onOpenProduct(effect.productId)
            }

            is InventoryContract.Effect.EditProduct -> {
                onEditProduct(effect.productId)
            }

            is InventoryContract.Effect.OpenPurchase -> {
                onOpenPurchase(effect.purchaseId)
            }

            is InventoryContract.Effect.OpenProductCreation -> {
                onRegisterProduct(effect.barcode)
            }

            InventoryContract.Effect.Back -> {
                onBack()
            }

            InventoryContract.Effect.CloseInvalidRoute -> {
                onCloseInvalidRoute()
            }
        }
    }

    state.pendingDeletion?.let { pending ->
        InventoryProductDeletionDialog(
            pending = pending,
            isBusy = state.isChangingProduct,
            failure = state.productActionFailure,
            onConfirm = { viewModel.onAction(InventoryContract.Action.ConfirmProductDeletion) },
            onDismiss = { viewModel.onAction(InventoryContract.Action.DismissProductDeletion) },
        )
    }

    if (state.failure == InventoryContract.Failure.PRODUCT_NOT_FOUND && state.detail == null) {
        RecoverableError(
            title = stringResource(R.string.inventory_product_not_found_title),
            message = stringResource(R.string.inventory_product_not_found_message),
            actionLabel = stringResource(R.string.action_return_inventory),
            onAction = onCloseInvalidRoute,
            modifier = modifier,
        )
        return
    }

    val loadFailure = state.failure == InventoryContract.Failure.LOAD_FAILED
    Column(modifier.fillMaxSize()) {
            state.productActionFailure?.takeIf { state.pendingDeletion == null }?.let { failure ->
                Column(Modifier.padding(FacturaStockDesign.spacing.md)) {
                    InventoryProductActionError(failure)
                    FacturaStockSecondaryButton(
                        text = stringResource(R.string.inventory_product_dismiss_error),
                        onClick = { viewModel.onAction(InventoryContract.Action.DismissProductActionFailure) },
                    )
                }
            }
            FeatureLoadContent(
                isLoading = state.isLoading,
                hasContent = if (state.productId == null) state.allItems.isNotEmpty() else state.detail != null,
                hasFailure = loadFailure,
                onRetry = { viewModel.onAction(InventoryContract.Action.Retry) },
                modifier = Modifier.weight(1f),
            ) {
                if (state.productId == null) {
                    Column(Modifier.fillMaxSize()) {
                        if (scanner != null) {
                            ScannerCodeInput(
                                enabled = state.isRegisteringProducts && state.canRouteScannerInput,
                                physicalInput = scanner.physicalInput,
                                onClearPhysicalInput = scanner.clear,
                                onCode = scanner.submit,
                                searchQuery = state.query,
                                onSearchQueryChange = { viewModel.onAction(InventoryContract.Action.SearchChanged(it)) },
                                labelRes = R.string.inventory_unified_input_label,
                                hintRes = R.string.inventory_unified_input_hint,
                                supportingTextRes = R.string.inventory_unified_input_help,
                                submitLabelRes = R.string.inventory_unified_open_code,
                                modifier = Modifier.padding(FacturaStockDesign.spacing.md),
                            )
                            Text(
                                text = stringResource(inventoryScannerMessage(state)),
                                color =
                                    if (state.scannerFailure != null) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                modifier = Modifier.padding(horizontal = FacturaStockDesign.spacing.md),
                            )
                            if (state.scannerFailure == InventoryContract.ScannerFailure.LOOKUP_FAILED) {
                                FacturaStockSecondaryButton(
                                    text = stringResource(R.string.action_retry),
                                    onClick = { viewModel.onAction(InventoryContract.Action.Retry) },
                                    enabled = !state.isBarcodeLookupRunning,
                                    modifier = Modifier.padding(horizontal = FacturaStockDesign.spacing.md),
                                )
                            }
                        }
                        InventoryListScreen(
                            items = state.items,
                            query = state.query,
                            productActionsEnabled = state.canStartProductAction,
                            onEditProduct = { viewModel.onAction(InventoryContract.Action.EditProduct(it)) },
                            onDeleteProduct = { viewModel.onAction(InventoryContract.Action.DeleteProduct(it)) },
                            diagnosticReport = state.diagnosticReport,
                            isLoading = state.isLoading,
                            isDiagnosing = state.isDiagnosing,
                            diagnosticFailed = diagnosticFailed,
                            showDiagnostic = showDiagnostic,
                            showNameSearch = scanner == null,
                            onQueryChange = { viewModel.onAction(InventoryContract.Action.SearchChanged(it)) },
                            onSearchFocusChange = { viewModel.onAction(InventoryContract.Action.SearchFocusChanged(it)) },
                            onProductClick = {
                                viewModel.onAction(InventoryContract.Action.ProductSelected(it))
                            },
                            onRunDiagnostic = {
                                viewModel.onAction(InventoryContract.Action.RunDiagnostic)
                            },
                            onRegisterManual = {
                                viewModel.onAction(InventoryContract.Action.RegisterProductManual)
                            },
                            onRegisterProducts = onRegisterProducts,
                            onRegisterSpecialProduct = onRegisterSpecialProduct,
                            // Los tres registros viven como iconos en la barra superior.
                            showRegisterActions = false,
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    InventoryProductDetailScreen(
                        detail = requireNotNull(state.detail),
                        onOpenPurchase = {
                            viewModel.onAction(InventoryContract.Action.OriginPurchaseSelected(it))
                        },
                        onBack = { viewModel.onAction(InventoryContract.Action.BackSelected) },
                        productActionsEnabled = state.canStartProductAction,
                        onEditProduct = { viewModel.onAction(InventoryContract.Action.EditProduct(it)) },
                        onDeleteProduct = { viewModel.onAction(InventoryContract.Action.DeleteProduct(it)) },
                    )
                }
            }
    }
}

/**
 * Publica los registros de productos (escáner, manual y especial por peso) como iconos de la
 * barra superior mientras se muestra la lista de Inventario.
 */
@Composable
private fun InventoryTopBarActions(
    state: InventoryContract.State,
    onRegisterProducts: () -> Unit,
    onRegisterManual: () -> Unit,
    onRegisterSpecialProduct: () -> Unit,
    onTopBarActionsAvailable: (List<FacturaStockTopBarAction>) -> Unit,
) {
    val registerProductsLabel = stringResource(R.string.inventory_register_products)
    val registerManualLabel = stringResource(R.string.inventory_register_manual)
    val registerSpecialLabel = stringResource(R.string.inventory_register_special_product)
    val currentOnRegisterProducts by rememberUpdatedState(onRegisterProducts)
    val currentOnRegisterManual by rememberUpdatedState(onRegisterManual)
    val currentOnRegisterSpecialProduct by rememberUpdatedState(onRegisterSpecialProduct)
    val currentOnTopBarActionsAvailable by rememberUpdatedState(onTopBarActionsAvailable)
    val showActions = state.productId == null
    val enabled = state.canStartProductAction && !state.isLoading
    val actions = remember(showActions, enabled, registerProductsLabel, registerManualLabel, registerSpecialLabel) {
        if (!showActions) {
            emptyList()
        } else {
            listOf(
                FacturaStockTopBarAction(
                    iconRes = R.drawable.ic_scanner_gun,
                    contentDescription = registerProductsLabel,
                    onClick = { currentOnRegisterProducts() },
                    enabled = enabled,
                    testTag = InventoryTestTags.REGISTER_PRODUCTS,
                ),
                FacturaStockTopBarAction(
                    iconRes = R.drawable.ic_hand_writing,
                    contentDescription = registerManualLabel,
                    onClick = { currentOnRegisterManual() },
                    enabled = enabled,
                    testTag = InventoryTestTags.REGISTER_MANUAL,
                ),
                FacturaStockTopBarAction(
                    iconRes = R.drawable.ic_rice,
                    contentDescription = registerSpecialLabel,
                    onClick = { currentOnRegisterSpecialProduct() },
                    enabled = enabled,
                    testTag = InventoryTestTags.REGISTER_SPECIAL_PRODUCT,
                ),
            )
        }
    }
    LaunchedEffect(actions) { currentOnTopBarActionsAvailable(actions) }
    DisposableEffect(Unit) {
        onDispose { currentOnTopBarActionsAvailable(emptyList()) }
    }
}
