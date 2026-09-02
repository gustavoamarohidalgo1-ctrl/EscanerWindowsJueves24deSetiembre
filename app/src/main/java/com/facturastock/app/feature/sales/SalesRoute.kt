package com.facturastock.app.feature.sales

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.facturastock.app.R
import com.facturastock.app.feature.common.CollectUiEffects
import com.facturastock.app.feature.common.PhysicalScannerRegistration
import kotlinx.coroutines.launch

@Composable
fun SalesRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    entryKind: SalesContract.EntryKind = SalesContract.EntryKind.CASH,
    allowEntryKindSelection: Boolean = true,
    onCreditSalePosted: () -> Unit = {},
    onExitCancelled: () -> Unit = {},
    onExitRequestAvailable: ((() -> Unit)?) -> Unit = {},
    viewModel: SalesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val associatedMessage = stringResource(R.string.sales_message_barcode_associated)
    val postedMessage = stringResource(R.string.sales_message_posted)
    val currentExitRegistration by rememberUpdatedState(onExitRequestAvailable)

    LaunchedEffect(viewModel, entryKind) {
        viewModel.onAction(SalesContract.Action.EntryKindChanged(entryKind))
    }

    DisposableEffect(viewModel) {
        currentExitRegistration {
            viewModel.onAction(SalesContract.Action.BackSelected)
        }
        onDispose { currentExitRegistration(null) }
    }

    BackHandler {
        when {
            state.isMutating -> viewModel.onAction(SalesContract.Action.BackSelected)
            state.discardEditsReview -> {
                onExitCancelled()
                viewModel.onAction(SalesContract.Action.DiscardEditsDismissed)
            }
            state.checkoutReview != null ->
                viewModel.onAction(SalesContract.Action.CheckoutDismissed)
            state.pendingReplacement != null ->
                viewModel.onAction(SalesContract.Action.BarcodeReplacementDismissed)
            state.pendingLocations.isNotEmpty() ->
                viewModel.onAction(SalesContract.Action.LocationSelectionDismissed)
            state.isAssociating -> viewModel.onAction(SalesContract.Action.AssociationDismissed)
            else -> viewModel.onAction(SalesContract.Action.BackSelected)
        }
    }

    CollectUiEffects(viewModel.effects) { effect ->
        when (effect) {
            SalesContract.Effect.Back -> onBack()
            SalesContract.Effect.CreditSalePosted -> onCreditSalePosted()
            is SalesContract.Effect.ShowMessage -> scope.launch {
                snackbarHostState.showSnackbar(
                    when (effect.message) {
                        SalesContract.Message.BARCODE_ASSOCIATED -> associatedMessage
                        SalesContract.Message.SALE_POSTED -> postedMessage
                    },
                )
            }
        }
    }

    PhysicalScannerRegistration(
        enabled = state.canRouteScannerInput,
        onAvailabilityChanged = { active ->
            viewModel.onAction(SalesContract.Action.ScannerAvailabilityChanged(active))
        },
        onScan = { value ->
            viewModel.onAction(SalesContract.Action.BarcodeScanned(value))
        },
    )

    Box(modifier = modifier.fillMaxSize()) {
        SalesScreen(
            state = state,
            onAction = { action ->
                if (action == SalesContract.Action.DiscardEditsDismissed) {
                    onExitCancelled()
                }
                viewModel.onAction(action)
            },
            modifier = Modifier.fillMaxSize(),
            allowEntryKindSelection = allowEntryKindSelection,
        )
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
