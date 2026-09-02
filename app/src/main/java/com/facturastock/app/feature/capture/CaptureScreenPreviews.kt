package com.facturastock.app.feature.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.facturastock.app.ui.preview.LargeFontPreview
import com.facturastock.app.ui.preview.ThemePreviews
import com.facturastock.app.ui.theme.FacturaStockTheme

/** Visor falso: en previews y pruebas no hay cámara; un panel del tema ocupa su lugar. */
@Composable
private fun FakeCameraPreview() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.inverseSurface),
    )
}

@ThemePreviews
@Composable
private fun CaptureScreenReadyPreview() {
    FacturaStockTheme {
        CaptureScreen(cameraPreview = { FakeCameraPreview() })
    }
}

@ThemePreviews
@Composable
private fun CaptureScreenCapturingPreview() {
    FacturaStockTheme {
        CaptureScreen(
            state = CaptureContract.State(isCapturing = true),
            cameraPreview = { FakeCameraPreview() },
        )
    }
}

@ThemePreviews
@Composable
private fun CaptureScreenErrorPreview() {
    FacturaStockTheme {
        CaptureScreen(
            state = CaptureContract.State(
                cameraError = CaptureContract.CameraErrorKind.BIND_FAILED,
            ),
        )
    }
}

@LargeFontPreview
@Composable
private fun CaptureScreenPermissionDeniedPreview() {
    FacturaStockTheme {
        CaptureScreen(
            state = CaptureContract.State(cameraPermissionDenied = true),
        )
    }
}
