package com.facturastock.app.feature.summary

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.facturastock.app.R
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.feature.common.CollectUiEffects
import com.facturastock.app.feature.summary.PurchaseSummaryContract.Action
import com.facturastock.app.feature.summary.PurchaseSummaryContract.Effect
import com.facturastock.app.feature.summary.PurchaseSummaryContract.Failure
import com.facturastock.app.ui.components.LoadingState
import com.facturastock.app.ui.components.RecoverableError
import com.facturastock.app.ui.theme.FacturaStockDesign

/**
 * Destino con Hilt del resumen de compra. Una ruta inválida o un borrador que ya no es editable
 * se cierra de inmediato; un fallo de carga muestra el error recuperable común.
 */
@Composable
fun PurchaseSummaryRoute(
    onPreparedStateEntered: (DraftId) -> Unit,
    onOpenLineReview: (DraftId) -> Unit,
    onOpenConfirmation: (DraftId, String) -> Unit,
    onBack: () -> Unit,
    onCloseInvalidRoute: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PurchaseSummaryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.onAction(Action.Start)
    }
    CollectUiEffects(viewModel.effects) { effect ->
        when (effect) {
            is Effect.PreparedStateEntered -> onPreparedStateEntered(effect.draftId)
            is Effect.OpenLineReview -> onOpenLineReview(effect.draftId)
            is Effect.OpenConfirmation -> onOpenConfirmation(
                effect.draftId,
                effect.expectedPreparedLogicalHash,
            )
            Effect.Back -> onBack()
            Effect.CloseInvalidRoute -> onCloseInvalidRoute()
        }
    }

    when {
        state.failure == Failure.LOAD_FAILED -> Box(
            modifier = modifier
                .fillMaxSize()
                .padding(FacturaStockDesign.spacing.lg),
        ) {
            RecoverableError(
                title = stringResource(R.string.feature_load_error_title),
                message = stringResource(R.string.feature_load_error_message),
                actionLabel = stringResource(R.string.action_retry),
                onAction = { viewModel.onAction(Action.Retry) },
            )
        }

        state.isLoading || state.failure == Failure.INVALID_ROUTE -> LoadingState(
            message = stringResource(R.string.summary_loading),
            modifier = modifier.fillMaxSize(),
        )

        else -> PurchaseSummaryScreen(
            state = state,
            onAction = viewModel::onAction,
            modifier = modifier,
        )
    }
}
