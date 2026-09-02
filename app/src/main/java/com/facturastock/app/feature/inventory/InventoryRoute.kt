package com.facturastock.app.feature.inventory

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.facturastock.app.R
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.feature.common.CollectUiEffects
import com.facturastock.app.feature.common.FeatureLoadContent
import com.facturastock.app.feature.common.PhysicalScannerRegistration
import com.facturastock.app.ui.components.RecoverableError

@Composable
fun InventoryRoute(
    onOpenProduct: (ProductId) -> Unit,
    onOpenPurchase: (PurchaseId) -> Unit,
    onBack: () -> Unit,
    onCloseInvalidRoute: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: InventoryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    CollectUiEffects(viewModel.effects) { effect ->
        when (effect) {
            is InventoryContract.Effect.OpenProduct -> onOpenProduct(effect.productId)
            is InventoryContract.Effect.OpenPurchase -> onOpenPurchase(effect.purchaseId)
            InventoryContract.Effect.Back -> onBack()
            InventoryContract.Effect.CloseInvalidRoute -> onCloseInvalidRoute()
        }
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

    PhysicalScannerRegistration(
        enabled = state.canRouteScannerInput,
        onAvailabilityChanged = { active ->
            viewModel.onAction(InventoryContract.Action.ScannerAvailabilityChanged(active))
        },
        onScan = { value ->
            viewModel.onAction(InventoryContract.Action.BarcodeScanned(value))
        },
    )

    val loadFailure = state.failure == InventoryContract.Failure.LOAD_FAILED
    FeatureLoadContent(
        isLoading = state.isLoading,
        hasContent = if (state.productId == null) state.allItems.isNotEmpty() else state.detail != null,
        hasFailure = loadFailure,
        onRetry = { viewModel.onAction(InventoryContract.Action.Retry) },
        modifier = modifier,
    ) {
        if (state.productId == null) {
            InventoryListScreen(
                items = state.items,
                query = state.query,
                diagnosticReport = state.diagnosticReport,
                isLoading = state.isLoading,
                isDiagnosing = state.isDiagnosing,
                diagnosticFailed = state.failure == InventoryContract.Failure.DIAGNOSTIC_FAILED,
                showDiagnostic = inventoryDiagnosticIsRelevant(
                    items = state.allItems,
                    report = state.diagnosticReport,
                    isRunning = state.isDiagnosing,
                    failed = state.failure == InventoryContract.Failure.DIAGNOSTIC_FAILED,
                ),
                onQueryChange = { viewModel.onAction(InventoryContract.Action.SearchChanged(it)) },
                inputMode = state.inputMode,
                scannerActive = state.scannerActive,
                isBarcodeLookupRunning = state.isBarcodeLookupRunning,
                scannerFailure = state.scannerFailure,
                onInputModeChange = {
                    viewModel.onAction(InventoryContract.Action.InputModeChanged(it))
                },
                onProductClick = {
                    viewModel.onAction(InventoryContract.Action.ProductSelected(it))
                },
                onRunDiagnostic = {
                    viewModel.onAction(InventoryContract.Action.RunDiagnostic)
                },
                section = state.section,
                profits = state.profits,
                isProfitLoading = state.isProfitLoading,
                profitFailure = state.profitFailure,
                salePriceEditor = state.salePriceEditor,
                isSavingSalePrice = state.isSavingSalePrice,
                onSectionChange = {
                    viewModel.onAction(InventoryContract.Action.SectionChanged(it))
                },
                onEditSalePrice = {
                    viewModel.onAction(InventoryContract.Action.EditSalePrice(it))
                },
                onSalePriceChange = {
                    viewModel.onAction(InventoryContract.Action.SalePriceChanged(it))
                },
                onSaveSalePrice = {
                    viewModel.onAction(InventoryContract.Action.SaveSalePrice)
                },
                onDismissSalePrice = {
                    viewModel.onAction(InventoryContract.Action.DismissSalePrice)
                },
                onRetryProfit = { viewModel.onAction(InventoryContract.Action.Retry) },
            )
        } else {
            InventoryProductDetailScreen(
                detail = requireNotNull(state.detail),
                onOpenPurchase = {
                    viewModel.onAction(InventoryContract.Action.OriginPurchaseSelected(it))
                },
                onBack = { viewModel.onAction(InventoryContract.Action.BackSelected) },
            )
        }
    }
}
