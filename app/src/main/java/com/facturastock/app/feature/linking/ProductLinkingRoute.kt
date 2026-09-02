package com.facturastock.app.feature.linking

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
import com.facturastock.app.feature.linking.ProductLinkingContract.Action
import com.facturastock.app.feature.linking.ProductLinkingContract.Effect
import com.facturastock.app.feature.linking.ProductLinkingContract.Failure
import com.facturastock.app.ui.components.LoadingState
import com.facturastock.app.ui.components.RecoverableError
import com.facturastock.app.ui.theme.FacturaStockDesign

/**
 * Destino con Hilt de la vinculación. Una ruta con IDs inválidos se cierra de inmediato; un
 * borrador que ya no es editable muestra el error recuperable común.
 */
@Composable
fun ProductLinkingRoute(
    onOpenSummary: (DraftId) -> Unit,
    onBack: () -> Unit,
    onCloseInvalidRoute: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProductLinkingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.onAction(Action.Start)
    }
    CollectUiEffects(viewModel.effects) { effect ->
        when (effect) {
            is Effect.OpenSummary -> onOpenSummary(effect.draftId)
            Effect.Back -> onBack()
            Effect.CloseInvalidRoute -> onCloseInvalidRoute()
        }
    }

    when {
        state.failure == Failure.DRAFT_NOT_EDITABLE -> Box(
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
            message = stringResource(R.string.linking_loading),
            modifier = modifier.fillMaxSize(),
        )

        else -> ProductLinkingScreen(
            state = state,
            onAction = viewModel::onAction,
            modifier = modifier,
        )
    }
}
