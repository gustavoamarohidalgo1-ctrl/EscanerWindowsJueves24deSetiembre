package com.facturastock.app.feature.source

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.facturastock.app.domain.model.id.CaptureId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.feature.common.CollectUiEffects
import com.facturastock.app.feature.common.isCameraPermissionPermanentlyDenied
import com.facturastock.app.feature.common.openAppPermissionSettings

/**
 * Ruta del paso de origen. El permiso de cámara se pide solo al pulsar "Tomar foto" y nunca
 * al entrar: si ya está concedido se informa al ViewModel sin diálogo; si no, se lanza el
 * diálogo del sistema. La galería usa el Photo Picker (`PickVisualMedia`), que no requiere
 * ningún permiso de almacenamiento en ninguna API —cae a `ACTION_OPEN_DOCUMENT` donde el
 * picker moderno no existe— y la imagen elegida se copia de inmediato al almacenamiento
 * privado, por lo que no se retiene ningún permiso de URI.
 */
@Composable
fun SourceRoute(
    onOpenCamera: (DraftId, ImageId?) -> Unit,
    onOpenPreview: (DraftId, CaptureId) -> Unit,
    onBack: () -> Unit,
    onCloseInvalidRoute: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SourceViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.onAction(
            SourceContract.Action.CameraPermissionResult(
                granted = granted,
                permanentlyDenied = !granted && context.isCameraPermissionPermanentlyDenied(),
            ),
        )
    }
    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        viewModel.onAction(
            if (uri != null) {
                SourceContract.Action.ImagePicked(uri.toString())
            } else {
                SourceContract.Action.PickCancelled
            },
        )
    }

    LaunchedEffect(viewModel) {
        viewModel.onAction(SourceContract.Action.Start)
    }

    CollectUiEffects(viewModel.effects) { effect ->
        when (effect) {
            SourceContract.Effect.RequestCameraPermission -> {
                val alreadyGranted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA,
                ) == PackageManager.PERMISSION_GRANTED
                if (alreadyGranted) {
                    viewModel.onAction(
                        SourceContract.Action.CameraPermissionResult(granted = true),
                    )
                } else {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            }

            SourceContract.Effect.OpenAppSettings -> context.openAppPermissionSettings()

            SourceContract.Effect.LaunchImagePicker -> imagePickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )

            is SourceContract.Effect.OpenCamera -> onOpenCamera(effect.draftId, effect.replaceImageId)
            is SourceContract.Effect.OpenPreview -> {
                onOpenPreview(effect.draftId, effect.captureId)
                viewModel.onAction(SourceContract.Action.PreviewNavigationHandled)
            }
            SourceContract.Effect.Back -> onBack()
            SourceContract.Effect.CloseInvalidRoute -> onCloseInvalidRoute()
        }
    }

    SourceScreen(
        state = state,
        onTakePhoto = {
            viewModel.onAction(SourceContract.Action.CameraSelected)
        },
        onPickImage = {
            viewModel.onAction(SourceContract.Action.PickImageSelected)
        },
        onRetryCameraPermission = {
            viewModel.onAction(SourceContract.Action.CameraSelected)
        },
        onOpenCameraSettings = {
            viewModel.onAction(SourceContract.Action.OpenCameraSettingsSelected)
        },
        onDismissInvalidImage = {
            viewModel.onAction(SourceContract.Action.DismissInvalidImage)
        },
        onBack = {
            viewModel.onAction(SourceContract.Action.BackSelected)
        },
        modifier = modifier,
    )
}
