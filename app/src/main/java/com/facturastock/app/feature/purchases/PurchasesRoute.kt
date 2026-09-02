package com.facturastock.app.feature.purchases

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.R
import com.facturastock.app.feature.common.CollectUiEffects
import com.facturastock.app.feature.common.FeatureLoadContent
import com.facturastock.app.ui.components.RecoverableError

@Composable
fun PurchasesRoute(
    onOpenPurchase: (PurchaseId) -> Unit,
    onBack: () -> Unit,
    onCloseInvalidRoute: () -> Unit,
    modifier: Modifier = Modifier,
    onNewPurchase: () -> Unit = {},
    onVoidPurchase: (PurchaseId) -> Unit = {},
    viewModel: PurchasesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    CollectUiEffects(viewModel.effects) { effect ->
        when (effect) {
            is PurchasesContract.Effect.OpenPurchase -> onOpenPurchase(effect.purchaseId)
            is PurchasesContract.Effect.OpenVoid -> onVoidPurchase(effect.purchaseId)
            PurchasesContract.Effect.Back -> onBack()
            PurchasesContract.Effect.CloseInvalidRoute -> onCloseInvalidRoute()
        }
    }

    if (state.failure == PurchasesContract.Failure.PURCHASE_NOT_FOUND && state.detail == null) {
        PurchaseNotFoundContent(onReturnToPurchases = onCloseInvalidRoute, modifier = modifier)
        return
    }

    FeatureLoadContent(
        isLoading = state.isLoading,
        hasContent = if (state.purchaseId == null) {
            state.purchases.isNotEmpty()
        } else {
            state.detail != null
        },
        hasFailure = state.failure != null,
        onRetry = { viewModel.onAction(PurchasesContract.Action.Retry) },
        modifier = modifier,
    ) {
        if (state.purchaseId == null) {
            PurchaseListScreen(
                purchases = state.purchases,
                isLoading = state.isFiltering,
                query = state.query,
                statusFilter = state.statusFilter,
                syncFilter = state.syncFilter,
                onQueryChange = { query ->
                    viewModel.onAction(PurchasesContract.Action.SearchChanged(query))
                },
                onStatusFilterChange = { status ->
                    viewModel.onAction(PurchasesContract.Action.StatusFilterChanged(status))
                },
                onSyncFilterChange = { syncState ->
                    viewModel.onAction(PurchasesContract.Action.SyncFilterChanged(syncState))
                },
                onPurchaseClick = { purchaseId ->
                    viewModel.onAction(PurchasesContract.Action.PurchaseSelected(purchaseId))
                },
                onNewPurchase = onNewPurchase,
                retryingBackupPurchaseId = state.retryingBackupPurchaseId,
                backupRetryFailedPurchaseId = state.backupRetryFailedPurchaseId,
                onRetryBackup = { purchaseId ->
                    viewModel.onAction(PurchasesContract.Action.RetryBackup(purchaseId))
                },
                hasMore = state.hasMore,
                isLoadingMore = state.isLoadingMore,
                onLoadMore = {
                    viewModel.onAction(PurchasesContract.Action.LoadMore)
                },
            )
        } else {
            PurchaseDetailScreen(
                detail = requireNotNull(state.detail),
                retainedImages = state.retainedImages,
                onBack = { viewModel.onAction(PurchasesContract.Action.BackSelected) },
                onVoidPurchase = {
                    viewModel.onAction(
                        PurchasesContract.Action.VoidSelected(
                            requireNotNull(state.purchaseId),
                        ),
                    )
                },
                isRetryingBackup = state.retryingBackupPurchaseId == state.purchaseId,
                backupRetryFailed = state.backupRetryFailedPurchaseId == state.purchaseId,
                onRetryBackup = {
                    viewModel.onAction(
                        PurchasesContract.Action.RetryBackup(requireNotNull(state.purchaseId)),
                    )
                },
                technicalDetailsVisible = state.technicalDetailsVisible,
                onTechnicalDetailsToggle = {
                    viewModel.onAction(PurchasesContract.Action.TechnicalDetailsToggled)
                },
            )
        }
    }
}

@Composable
fun PurchaseVoidRoute(
    onCompleted: (PurchaseId) -> Unit,
    onBack: () -> Unit,
    onCloseInvalidRoute: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PurchaseVoidViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    CollectUiEffects(viewModel.effects) { effect ->
        when (effect) {
            is PurchaseVoidContract.Effect.Completed -> onCompleted(effect.purchaseId)
            PurchaseVoidContract.Effect.Back -> onBack()
            PurchaseVoidContract.Effect.CloseInvalidRoute -> onCloseInvalidRoute()
        }
    }

    PurchaseVoidScreen(
        state = state,
        onAction = viewModel::onAction,
        modifier = modifier,
    )
}

/** Pantalla terminal observada desde Room; un retry nunca vuelve a ejecutar el posting. */
@Composable
fun PurchaseSuccessRoute(
    onViewDetail: (PurchaseId) -> Unit,
    onViewInventory: () -> Unit,
    onCloseInvalidRoute: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PurchasesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    CollectUiEffects(viewModel.effects) { effect ->
        if (effect == PurchasesContract.Effect.CloseInvalidRoute) onCloseInvalidRoute()
    }

    if (state.failure == PurchasesContract.Failure.PURCHASE_NOT_FOUND && state.detail == null) {
        PurchaseNotFoundContent(onReturnToPurchases = onCloseInvalidRoute, modifier = modifier)
        return
    }

    FeatureLoadContent(
        isLoading = state.isLoading,
        hasContent = state.detail != null,
        hasFailure = state.failure != null,
        onRetry = { viewModel.onAction(PurchasesContract.Action.Retry) },
        modifier = modifier,
    ) {
        val detail = requireNotNull(state.detail)
        PurchaseSuccessScreen(
            detail = detail,
            onViewDetail = { onViewDetail(detail.summary.purchaseId) },
            onViewInventory = onViewInventory,
            isRetryingBackup = state.retryingBackupPurchaseId == detail.summary.purchaseId,
            backupRetryFailed = state.backupRetryFailedPurchaseId == detail.summary.purchaseId,
            onRetryBackup = {
                viewModel.onAction(
                    PurchasesContract.Action.RetryBackup(detail.summary.purchaseId),
                )
            },
        )
    }
}

@Composable
private fun PurchaseNotFoundContent(
    onReturnToPurchases: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RecoverableError(
        title = stringResource(R.string.purchase_not_found_title),
        message = stringResource(R.string.purchase_not_found_message),
        actionLabel = stringResource(R.string.action_return_purchases),
        onAction = onReturnToPurchases,
        modifier = modifier,
    )
}
