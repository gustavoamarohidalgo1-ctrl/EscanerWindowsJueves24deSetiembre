package com.facturastock.app.feature.preparation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.feature.common.CollectUiEffects
import com.facturastock.app.ui.components.LoadingState
import com.facturastock.app.R
import androidx.compose.ui.res.stringResource

@Composable
fun PreparationRoute(
    onOpenPurchase: (PurchaseId) -> Unit,
    onOpenExistingPurchase: (PurchaseId) -> Unit,
    onBack: () -> Unit,
    onCloseInvalidRoute: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PreparationViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.onAction(PreparationContract.Action.Start)
    }

    CollectUiEffects(viewModel.effects) { effect ->
        when (effect) {
            is PreparationContract.Effect.OpenPurchase -> onOpenPurchase(effect.purchaseId)
            is PreparationContract.Effect.OpenExistingPurchase -> {
                onOpenExistingPurchase(effect.purchaseId)
            }
            PreparationContract.Effect.Back -> onBack()
            PreparationContract.Effect.CloseInvalidRoute -> onCloseInvalidRoute()
        }
    }

    if (state.isCheckingDuplicates || state.isConfirming) {
        LoadingState(
            message = stringResource(
                if (state.isCheckingDuplicates) {
                    R.string.purchase_duplicate_checking
                } else {
                    R.string.feature_loading_message
                },
            ),
            modifier = modifier,
        )
    } else {
        PreparationScreen(
            state = state,
            onAction = viewModel::onAction,
            modifier = modifier,
        )
    }
}
