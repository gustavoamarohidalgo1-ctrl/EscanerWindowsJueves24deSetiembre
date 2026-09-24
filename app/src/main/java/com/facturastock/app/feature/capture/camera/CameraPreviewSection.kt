package com.facturastock.app.feature.capture.camera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset

/** Captura de cámara en escritorio: marcador que nunca llega a estar lista. */
class ImageCapture internal constructor()

/**
 * Sin CameraX en Windows, la vista previa informa de inmediato que la cámara no pudo ligarse;
 * la pantalla de captura muestra entonces su estado de error con la opción de elegir una imagen.
 */
@Composable
fun CameraPreviewSection(
    onCameraReady: (imageCapture: ImageCapture, hasFlashUnit: Boolean) -> Unit,
    onCameraError: () -> Unit,
    onFocusChanged: (offset: Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnCameraError by rememberUpdatedState(onCameraError)
    LaunchedEffect(Unit) { currentOnCameraError() }
}
