package com.facturastock.app.feature.matching

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.facturastock.app.feature.common.CollectUiEffects
import com.facturastock.app.feature.matching.InvoiceMatchingContract.Effect

@Composable
fun InvoiceMatchingRoute(
    onMatchingConfirmed: (Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: InvoiceMatchingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    CollectUiEffects(viewModel.effects) { effect ->
        when (effect) {
            is Effect.MatchingConfirmed -> onMatchingConfirmed(effect.updatedCount)
            Effect.Back -> onBack()
        }
    }

    InvoiceMatchingScreen(
        state = state,
        onAction = viewModel::onAction,
        modifier = modifier,
    )
}
