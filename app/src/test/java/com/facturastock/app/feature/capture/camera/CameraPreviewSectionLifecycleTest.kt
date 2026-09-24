package com.facturastock.app.feature.capture.camera

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.facturastock.app.feature.capture.CaptureContract
import com.facturastock.app.feature.capture.CaptureScreen
import com.facturastock.app.feature.capture.CaptureTestTags
import com.facturastock.app.resources.Res
import com.facturastock.app.resources.capture_camera_error_title
import com.facturastock.app.ui.theme.FacturaStockTheme
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * En Windows no hay CameraX: [CameraPreviewSection] es un sustituto que informa de inmediato que
 * la cámara no está disponible. Las pruebas de ciclo de vida de CameraX (`unbindAll()` al salir,
 * `ProcessCameraProvider.isBound`) se retiraron junto con la cámara; aquí se verifica el contrato
 * del sustituto: cada entrada al visor reporta el error una sola vez y nunca una cámara lista, y
 * la pantalla de captura muestra entonces el estado de cámara no disponible.
 */
class CameraPreviewSectionLifecycleTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun everyVisitReportsTheCameraAsUnavailableExactlyOnce() {
        val readyCalls = AtomicInteger(0)
        val errorCalls = AtomicInteger(0)
        var visorVisible by mutableStateOf(true)

        composeRule.setContent {
            if (visorVisible) {
                CameraPreviewSection(
                    onCameraReady = { _, _ -> readyCalls.incrementAndGet() },
                    onCameraError = { errorCalls.incrementAndGet() },
                    onFocusChanged = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        repeat(VISITS) { visit ->
            if (visit > 0) {
                composeRule.runOnIdle { visorVisible = true }
            }
            composeRule.waitUntil(timeoutMillis = 5_000) { errorCalls.get() > visit }
            composeRule.runOnIdle {
                assertEquals("Visita ${visit + 1}", visit + 1, errorCalls.get())
            }
            composeRule.runOnIdle { visorVisible = false }
            composeRule.waitForIdle()
        }

        assertEquals(VISITS, errorCalls.get())
        assertEquals(0, readyCalls.get())
    }

    @Test
    fun captureScreenWithTheDesktopStubShowsCameraUnavailableAndOffersRetry() {
        var state by mutableStateOf(CaptureContract.State())
        var retries = 0

        composeRule.setContent {
            FacturaStockTheme {
                CaptureScreen(
                    state = state,
                    onRetryCamera = {
                        retries += 1
                        state = state.copy(isCameraReady = false, cameraError = null)
                    },
                    allowImagePicker = false,
                    cameraPreview = {
                        CameraPreviewSection(
                            onCameraReady = { _, _ -> state = state.copy(isCameraReady = true) },
                            onCameraError = {
                                state = state.copy(
                                    cameraError = CaptureContract.CameraErrorKind.BIND_FAILED,
                                )
                            },
                            onFocusChanged = {},
                        )
                    },
                )
            }
        }

        val errorTitle = runBlocking { getString(Res.string.capture_camera_error_title) }
        composeRule.onNodeWithTag(CaptureTestTags.CAMERA_ERROR).assertIsDisplayed()
        composeRule.onNodeWithText(errorTitle).assertIsDisplayed()
        composeRule.onNodeWithTag(CaptureTestTags.PREVIEW).assertDoesNotExist()

        // Reintentar vuelve a componer el visor; el sustituto vuelve a reportar el error.
        composeRule.onNodeWithTag(CaptureTestTags.RETRY).performClick()
        composeRule.waitForIdle()
        assertEquals(1, retries)
        composeRule.onNodeWithTag(CaptureTestTags.CAMERA_ERROR).assertIsDisplayed()
        composeRule.onNodeWithTag(CaptureTestTags.SHUTTER).assertDoesNotExist()
    }

    private companion object {
        const val VISITS = 3
    }
}
