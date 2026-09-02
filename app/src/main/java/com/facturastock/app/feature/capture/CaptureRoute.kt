package com.facturastock.app.feature.capture

import android.Manifest
import android.content.pm.PackageManager
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.facturastock.app.domain.model.CaptureImagePolicy
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.feature.capture.camera.CameraPreviewSection
import com.facturastock.app.feature.common.CollectUiEffects
import com.facturastock.app.feature.common.isCameraPermissionPermanentlyDenied
import com.facturastock.app.feature.common.openAppPermissionSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor

/**
 * Ruta del paso de captura con cámara real (CameraX). Cablea el contrato UDF con el hardware:
 *
 * - Al entrar comprueba el permiso de cámara. Como "Abrir cámara" ya es una acción explícita
 *   del recorrido de dos pasos, la primera entrada solicita el permiso inmediatamente. Si el
 *   usuario ya lo denegó, la pantalla conserva ese estado y ofrece reintentarlo (efecto
 *   [CaptureContract.Effect.RequestCameraPermission]).
 * - El efecto one-shot [CaptureContract.Effect.CaptureNow] ejecuta `takePicture` sobre el
 *   `ImageCapture` entregado por el adaptador de cámara: antes se fija `targetRotation` desde
 *   el display actual para que CameraX reporte la rotación correcta en `imageInfo`.
 * - El JPEG llega en memoria: el plano se copia en `Dispatchers.Default`, no en Main; se cierra
 *   el proxy SIEMPRE (un proxy sin cerrar bloquea la cámara) y se notifica en Main
 *   [CaptureContract.Action.CaptureSucceeded]; cualquier error es
 *   [CaptureContract.Action.CaptureFailed].
 * - El modo de flash del estado se aplica al `ImageCapture` cada vez que cambia.
 *
 * La persistencia no ocurre aquí: los bytes viajan al ViewModel, que los pasa a
 * `ImportDraftImageUseCase` por el mismo pipeline de validación que la galería.
 */
@Composable
fun CaptureRoute(
    onOpenProcessing: (DraftId) -> Unit,
    onBack: () -> Unit,
    onCloseInvalidRoute: () -> Unit,
    allowImagePicker: Boolean = false,
    modifier: Modifier = Modifier,
    viewModel: CaptureViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val jpegCopyExecutor = remember { Dispatchers.Default.asExecutor() }

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var hasFlashUnit by remember { mutableStateOf(false) }
    var focusOffset by remember { mutableStateOf<Offset?>(null) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.onAction(
            CaptureContract.Action.CameraPermissionResult(
                granted = granted,
                permanentlyDenied = !granted && context.isCameraPermissionPermanentlyDenied(),
            ),
        )
    }

    LaunchedEffect(viewModel) {
        viewModel.onAction(CaptureContract.Action.Start)
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            viewModel.onAction(CaptureContract.Action.CameraPermissionSnapshot(granted = true))
        } else if (
            !state.cameraPermissionDenied &&
            !state.cameraPermissionPermanentlyDenied
        ) {
            // Abrir la cámara es la acción explícita del paso 1, por lo que el diálogo del
            // sistema puede mostrarse de inmediato sin añadir otra pantalla al recorrido.
            viewModel.onAction(CaptureContract.Action.RequestPermissionSelected)
        } else {
            viewModel.onAction(CaptureContract.Action.CameraPermissionSnapshot(granted = false))
        }
    }

    // Si el usuario concedió el permiso desde Ajustes, al volver la cámara se recupera sin
    // dejarlo atrapado en el estado de denegación permanente.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(
        lifecycleOwner,
        context,
        viewModel,
        state.cameraPermissionPermanentlyDenied,
    ) {
        if (!state.cameraPermissionPermanentlyDenied) {
            onDispose { }
        } else {
            var leftActivity = false
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE) {
                    leftActivity = true
                } else if (event == Lifecycle.Event.ON_RESUME && leftActivity) {
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.CAMERA,
                    ) == PackageManager.PERMISSION_GRANTED
                    viewModel.onAction(
                        CaptureContract.Action.CameraPermissionResult(
                            granted = granted,
                            permanentlyDenied = !granted,
                        ),
                    )
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }

    // Aplica el modo de flash elegido al caso de uso de captura ya ligado.
    LaunchedEffect(state.flashMode, imageCapture) {
        imageCapture?.flashMode = when (state.flashMode) {
            CaptureFlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
            CaptureFlashMode.ON -> ImageCapture.FLASH_MODE_ON
            CaptureFlashMode.OFF -> ImageCapture.FLASH_MODE_OFF
        }
    }

    CollectUiEffects(viewModel.effects) { effect ->
        when (effect) {
            CaptureContract.Effect.CaptureNow -> {
                val capture = imageCapture
                if (capture == null) {
                    // El obturador se tocó antes de que la cámara estuviera ligada.
                    viewModel.onAction(CaptureContract.Action.CaptureFailed)
                } else {
                    capture.targetRotation = view.display?.rotation ?: Surface.ROTATION_0
                    capture.takePicture(
                        jpegCopyExecutor,
                        object : ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(imageProxy: ImageProxy) {
                                try {
                                    val jpegBytes = imageProxy.readJpegBytes()
                                    val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                                    mainExecutor.execute {
                                        viewModel.onAction(
                                            CaptureContract.Action.CaptureSucceeded(
                                                jpegBytes = jpegBytes,
                                                rotationDegrees = rotationDegrees,
                                            ),
                                        )
                                    }
                                } catch (_: RuntimeException) {
                                    mainExecutor.execute {
                                        viewModel.onAction(CaptureContract.Action.CaptureFailed)
                                    }
                                } catch (_: OutOfMemoryError) {
                                    mainExecutor.execute {
                                        viewModel.onAction(CaptureContract.Action.CaptureFailed)
                                    }
                                } finally {
                                    // Un proxy sin cerrar deja la cámara colgada.
                                    imageProxy.close()
                                }
                            }

                            override fun onError(exception: ImageCaptureException) {
                                mainExecutor.execute {
                                    viewModel.onAction(CaptureContract.Action.CaptureFailed)
                                }
                            }
                        },
                    )
                }
            }

            CaptureContract.Effect.RequestCameraPermission ->
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)

            CaptureContract.Effect.OpenAppSettings -> context.openAppPermissionSettings()

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
        hasFlashUnit = hasFlashUnit,
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
                onCameraReady = { readyCapture, readyHasFlash ->
                    imageCapture = readyCapture
                    hasFlashUnit = readyHasFlash
                    viewModel.onAction(CaptureContract.Action.CameraReady)
                },
                onCameraError = {
                    imageCapture = null
                    viewModel.onAction(CaptureContract.Action.CameraBindFailed)
                },
                onFocusChanged = { tapped -> focusOffset = tapped },
            )
        },
    )
}

/**
 * Bytes JPEG del proxy: con el formato por defecto de `ImageCapture` (JPEG) todo el archivo
 * codificado vive en el primer y único plano.
 */
/** Compartido con el benchmark del build aislado para medir exactamente la copia productiva. */
internal fun ImageProxy.readJpegBytes(): ByteArray {
    val buffer = planes[0].buffer
    val byteCount = buffer.remaining()
    check(CaptureImagePolicy.isSizeAllowed(byteCount.toLong())) {
        "CameraX produjo un JPEG fuera de la política de captura"
    }
    val bytes = ByteArray(byteCount)
    buffer.get(bytes)
    return bytes
}
