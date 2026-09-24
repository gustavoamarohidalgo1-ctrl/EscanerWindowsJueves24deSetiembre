package com.facturastock.app.feature.capture

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.facturastock.app.di.appViewModel
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.feature.capture.camera.CameraPreviewSection
import com.facturastock.app.feature.common.CollectUiEffects
import com.facturastock.app.feature.common.isCameraPermissionPermanentlyDenied
import com.facturastock.app.feature.common.openAppPermissionSettings

/**
 * Ruta del paso de captura. En Windows no hay CameraX: la vista previa informa que la cámara no
 * está disponible y la pantalla ofrece elegir la foto de la factura como archivo, que sigue por
 * el mismo pipeline de validación que la galería (`ImportDraftImageUseCase`).
 */
@Composable
fun CaptureRoute(
    onOpenProcessing: (DraftId) -> Unit,
    onBack: () -> Unit,
    onCloseInvalidRoute: () -> Unit,
    allowImagePicker: Boolean = false,
    modifier: Modifier = Modifier,
    viewModel: CaptureViewModel = appViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var focusOffset by remember { mutableStateOf<Offset?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.onAction(CaptureContract.Action.Start)
        viewModel.onAction(CaptureContract.Action.CameraPermissionSnapshot(granted = true))
    }

    CollectUiEffects(viewModel.effects) { effect ->
        when (effect) {
            // El obturador no puede dispararse sin una cámara ligada.
            CaptureContract.Effect.CaptureNow -> viewModel.onAction(CaptureContract.Action.CaptureFailed)

            CaptureContract.Effect.RequestCameraPermission -> viewModel.onAction(
                CaptureContract.Action.CameraPermissionResult(
                    granted = false,
                    permanentlyDenied = isCameraPermissionPermanentlyDenied(),
                ),
            )

            CaptureContract.Effect.OpenAppSettings -> openAppPermissionSettings()

            is CaptureContract.Effect.OpenProcessing -> {
                onOpenProcessing(effect.draftId)
                viewModel.onAction(CaptureContract.Action.ProcessingNavigationHandled)
            }

            CaptureContract.Effect.Back -> onBack()
            CaptureContract.Effect.CloseInvalidRoute -> onCloseInvalidRoute()
        }
    }

    CaptureScreen(
        state = state,
        hasFlashUnit = false,
        focusIndicatorOffset = focusOffset,
        onCaptureClicked = { viewModel.onAction(CaptureContract.Action.CaptureClicked) },
        onFlashClicked = { viewModel.onAction(CaptureContract.Action.FlashToggled) },
        onGrantPermission = {
            viewModel.onAction(CaptureContract.Action.RequestPermissionSelected)
        },
        onRetryCamera = { viewModel.onAction(CaptureContract.Action.RetryCameraSelected) },
        onPickImage = { viewModel.onAction(CaptureContract.Action.ImportSelected) },
        allowImagePicker = allowImagePicker,
        onBack = { viewModel.onAction(CaptureContract.Action.BackSelected) },
        modifier = modifier,
        cameraPreview = {
            CameraPreviewSection(
                onCameraReady = { _, _ -> viewModel.onAction(CaptureContract.Action.CameraReady) },
                onCameraError = { viewModel.onAction(CaptureContract.Action.CameraBindFailed) },
                onFocusChanged = { tapped -> focusOffset = tapped },
            )
        },
    )
}
