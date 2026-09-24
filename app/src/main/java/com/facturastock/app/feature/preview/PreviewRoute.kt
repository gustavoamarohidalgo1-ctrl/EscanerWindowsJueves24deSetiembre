package com.facturastock.app.feature.preview

import com.facturastock.app.resources.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import com.facturastock.app.di.appViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.feature.common.CollectUiEffects
import com.facturastock.app.ui.components.RecoverableError

/**
 * Ruta de la vista previa de páginas. Toda la navegación se traduce aquí: "Repetir" y
 * "Añadir página" vuelven al origen (con o sin objetivo de reemplazo) y "Procesar" avanza
 * al OCR solo después de analizar calidad y crear las versiones preparadas.
 */
@Composable
fun PreviewRoute(
    onOpenSource: (DraftId, ImageId?) -> Unit,
    onOpenProcessing: (DraftId) -> Unit,
    onBack: () -> Unit,
    onCloseInvalidRoute: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PreviewViewModel = appViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.onAction(PreviewContract.Action.Start)
    }

    CollectUiEffects(viewModel.effects) { effect ->
        when (effect) {
            is PreviewContract.Effect.OpenSource ->
                onOpenSource(effect.draftId, effect.replaceImageId)
            is PreviewContract.Effect.OpenProcessing -> onOpenProcessing(effect.draftId)
            PreviewContract.Effect.Back -> onBack()
            PreviewContract.Effect.CloseInvalidRoute -> onCloseInvalidRoute()
        }
    }

    if (state.failure == PreviewContract.Failure.LOAD_FAILED) {
        RecoverableError(
            title = stringResource(Res.string.feature_load_error_title),
            message = stringResource(Res.string.feature_load_error_message),
            actionLabel = stringResource(Res.string.action_retry),
            onAction = { viewModel.onAction(PreviewContract.Action.RetryLoad) },
            modifier = modifier.fillMaxSize(),
        )
    } else {
        PreviewScreen(
            state = state,
            onAction = viewModel::onAction,
            modifier = modifier,
        )
    }
}
