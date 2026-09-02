package com.facturastock.app.feature.review

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.facturastock.app.R
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.feature.common.CollectUiEffects
import com.facturastock.app.feature.purchase.PurchaseFlowScreen
import com.facturastock.app.ui.components.LoadingState

@Composable
fun ReviewRoute(
    onOpenPreparation: (DraftId) -> Unit,
    onBack: () -> Unit,
    onCloseInvalidRoute: () -> Unit,
    modifier: Modifier = Modifier,
    @StringRes titleRes: Int = R.string.purchase_lines_title,
    @StringRes messageRes: Int = R.string.purchase_lines_message,
    @StringRes primaryActionRes: Int = R.string.action_link_products,
    step: Int = 6,
    viewModel: ReviewViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    CollectUiEffects(viewModel.effects) { effect ->
        when (effect) {
            is ReviewContract.Effect.OpenPreparation -> {
                onOpenPreparation(effect.draftId)
            }
            ReviewContract.Effect.Back -> onBack()
            ReviewContract.Effect.CloseInvalidRoute -> onCloseInvalidRoute()
        }
    }

    if (state.isSubmitting) {
        LoadingState(
            message = stringResource(R.string.feature_loading_message),
            modifier = modifier,
        )
    } else {
        PurchaseFlowScreen(
            titleRes = titleRes,
            messageRes = messageRes,
            primaryActionRes = if (state.failure == ReviewContract.Failure.REVIEW_FAILED) {
                R.string.action_retry
            } else {
                primaryActionRes
            },
            onPrimaryAction = {
                viewModel.onAction(
                    if (state.failure == ReviewContract.Failure.REVIEW_FAILED) {
                        ReviewContract.Action.Retry
                    } else {
                        ReviewContract.Action.Submit
                    },
                )
            },
            modifier = modifier,
            step = step,
            showPreviousAction = true,
            onPreviousAction = {
                viewModel.onAction(ReviewContract.Action.BackSelected)
            },
        )
    }
}
