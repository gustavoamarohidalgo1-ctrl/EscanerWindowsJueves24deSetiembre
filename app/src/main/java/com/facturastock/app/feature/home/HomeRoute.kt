package com.facturastock.app.feature.home

import com.facturastock.app.resources.*
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import com.facturastock.app.di.appViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.feature.common.CollectUiEffects
import com.facturastock.app.feature.common.FeatureLoadContent
import kotlinx.coroutines.launch

@Composable
fun HomeRoute(
    onOpenDraftCamera: (DraftId) -> Unit,
    onOpenDraftSource: (DraftId) -> Unit,
    onOpenPreview: (DraftId, ImageId) -> Unit,
    onOpenProcessing: (DraftId) -> Unit,
    onOpenHeader: (DraftId) -> Unit,
    onOpenLines: (DraftId) -> Unit,
    onOpenSummary: (DraftId) -> Unit,
    onOpenPurchaseDetail: (PurchaseId) -> Unit,
    onOpenSales: () -> Unit,
    onOpenProducts: () -> Unit,
    onOpenPurchases: () -> Unit,
    onOpenDebtors: () -> Unit,
    onOpenInventory: () -> Unit,
    modifier: Modifier = Modifier,
    onContentReady: () -> Unit = {},
    viewModel: HomeViewModel = appViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val draftDeletedMessage = stringResource(Res.string.home_draft_deleted)
    val deleteErrorMessage = stringResource(Res.string.home_draft_delete_error)
    val createDraftErrorMessage = stringResource(Res.string.home_draft_create_error)
    val ocrRecoveryErrorMessage = stringResource(Res.string.home_ocr_recovery_error)
    val retryLabel = stringResource(Res.string.action_retry)
    val currentOnContentReady by rememberUpdatedState(onContentReady)
    val contentSettled = shouldSignalHomeContentReady(
        hasDashboard = state.dashboard != null,
        hasFailure = state.failure != null,
    )

    LaunchedEffect(contentSettled) {
        if (!contentSettled) return@LaunchedEffect
        // El contenido operativo o su error recuperable deben haber sido presentados antes de
        // despertar limpieza, recuperación de outbox o WorkManager.
        withFrameNanos { }
        withFrameNanos { }
        currentOnContentReady()
    }

    CollectUiEffects(viewModel.effects) { effect ->
        when (effect) {
            is HomeContract.Effect.OpenDraftCamera -> onOpenDraftCamera(effect.draftId)
            is HomeContract.Effect.OpenDraftSource -> onOpenDraftSource(effect.draftId)
            is HomeContract.Effect.OpenPreview -> onOpenPreview(effect.draftId, effect.imageId)
            is HomeContract.Effect.OpenProcessing -> onOpenProcessing(effect.draftId)
            is HomeContract.Effect.OpenHeader -> onOpenHeader(effect.draftId)
            is HomeContract.Effect.OpenLines -> onOpenLines(effect.draftId)
            is HomeContract.Effect.OpenSummary -> onOpenSummary(effect.draftId)
            is HomeContract.Effect.OpenPurchaseDetail ->
                onOpenPurchaseDetail(effect.purchaseId)

            HomeContract.Effect.OpenProducts -> onOpenProducts()
            HomeContract.Effect.OpenPurchases -> onOpenPurchases()
            HomeContract.Effect.OpenInventory -> onOpenInventory()
            HomeContract.Effect.ShowDeleteSuccess -> coroutineScope.launch {
                snackbarHostState.showSnackbar(draftDeletedMessage)
            }

            HomeContract.Effect.ShowDraftCreationFailure -> coroutineScope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = createDraftErrorMessage,
                    actionLabel = retryLabel,
                    duration = SnackbarDuration.Long,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    viewModel.onAction(HomeContract.Action.ScanInvoiceSelected)
                }
            }

            HomeContract.Effect.ShowOcrRecoveryFailure -> coroutineScope.launch {
                snackbarHostState.showSnackbar(ocrRecoveryErrorMessage)
            }

            is HomeContract.Effect.ShowDeleteFailure -> coroutineScope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = deleteErrorMessage,
                    actionLabel = retryLabel,
                    duration = SnackbarDuration.Long,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    // Reintenta el borrado del mismo borrador confirmando de inmediato.
                    viewModel.onAction(HomeContract.Action.DeleteRequested(effect.draftId))
                    viewModel.onAction(HomeContract.Action.DeleteConfirmed)
                }
            }
        }
    }

    FeatureLoadContent(
        isLoading = state.isLoading,
        hasContent = state.dashboard != null,
        hasFailure = state.failure != null,
        onRetry = { viewModel.onAction(HomeContract.Action.Retry) },
        modifier = modifier,
    ) {
        HomeScreen(
            dashboard = state.dashboard,
            drafts = state.drafts.orEmpty(),
            snackbarHostState = snackbarHostState,
            draftIdPendingDeletion = state.draftIdPendingDeletion,
            draftIdPendingOcrChoice = state.draftIdPendingOcrChoice,
            isRecoveringOcr = state.isRecoveringOcr,
            isCreatingDraft = state.isCreatingDraft,
            onScanInvoice = {
                viewModel.onAction(HomeContract.Action.ScanInvoiceSelected)
            },
            onOpenSales = onOpenSales,
            onOpenProducts = {
                viewModel.onAction(HomeContract.Action.ProductsSelected)
            },
            onOpenPurchases = {
                viewModel.onAction(HomeContract.Action.PurchasesSelected)
            },
            onOpenDebtors = onOpenDebtors,
            onOpenInventory = {
                viewModel.onAction(HomeContract.Action.InventorySelected)
            },
            onDraftSelected = { draftId ->
                viewModel.onAction(HomeContract.Action.DraftSelected(draftId))
            },
            onDeleteRequested = { draftId ->
                viewModel.onAction(HomeContract.Action.DeleteRequested(draftId))
            },
            onDeleteConfirmed = {
                viewModel.onAction(HomeContract.Action.DeleteConfirmed)
            },
            onDeleteDismissed = {
                viewModel.onAction(HomeContract.Action.DeleteDismissed)
            },
            onOcrResumeSelected = {
                viewModel.onAction(HomeContract.Action.OcrResumeSelected)
            },
            onOcrRetrySelected = {
                viewModel.onAction(HomeContract.Action.OcrRetrySelected)
            },
            onOcrChoiceDismissed = {
                viewModel.onAction(HomeContract.Action.OcrChoiceDismissed)
            },
        )
    }
}

internal fun shouldSignalHomeContentReady(
    hasDashboard: Boolean,
    hasFailure: Boolean,
): Boolean = hasDashboard || hasFailure
