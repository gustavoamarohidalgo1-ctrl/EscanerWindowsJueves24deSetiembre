package com.facturastock.app.feature.headerreview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.facturastock.app.R
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.feature.common.CollectUiEffects
import com.facturastock.app.feature.headerreview.InvoiceHeaderReviewContract.Action
import com.facturastock.app.feature.headerreview.InvoiceHeaderReviewContract.Effect
import com.facturastock.app.feature.headerreview.InvoiceHeaderReviewContract.Failure
import com.facturastock.app.feature.headerreview.InvoiceHeaderReviewContract.FieldId
import com.facturastock.app.ui.components.LoadingState
import com.facturastock.app.ui.components.RecoverableError
import com.facturastock.app.ui.theme.FacturaStockDesign

@Composable
fun InvoiceHeaderReviewRoute(
    onOpenProductsReview: (DraftId) -> Unit,
    onBack: () -> Unit,
    onCloseInvalidRoute: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: InvoiceHeaderReviewViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var requestedFocusField by remember { mutableStateOf<FieldId?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.onAction(Action.Start)
    }

    CollectUiEffects(viewModel.effects) { effect ->
        when (effect) {
            is Effect.OpenProductsReview -> onOpenProductsReview(effect.draftId)
            is Effect.FocusField -> requestedFocusField = effect.field
            Effect.Back -> onBack()
            Effect.CloseInvalidRoute -> onCloseInvalidRoute()
        }
    }

    when {
        state.failure == Failure.LOAD_FAILED -> {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .padding(FacturaStockDesign.spacing.lg),
            ) {
                RecoverableError(
                    title = stringResource(R.string.feature_load_error_title),
                    message = stringResource(R.string.feature_load_error_message),
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = { viewModel.onAction(Action.RetryLoad) },
                )
            }
        }

        state.isLoading || state.failure == Failure.INVALID_ROUTE -> {
            LoadingState(
                message = stringResource(R.string.header_review_loading),
                modifier = modifier.fillMaxSize(),
            )
        }

        else -> {
            InvoiceHeaderReviewScreen(
                state = state,
                onAction = viewModel::onAction,
                modifier = modifier,
                requestedFocusField = requestedFocusField,
                onRequestedFocusHandled = { requestedFocusField = null },
            )
        }
    }
}
