package com.facturastock.app.feature.capture.camera

import android.Manifest
import android.content.pm.PackageManager
import androidx.camera.core.ImageCapture
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cubre el criterio del visor que el resto de las pruebas de captura no puede cubrir: entrar y
 * salir repetidamente de la cámara **no filtra el dispositivo**. `CaptureScreenTest` usa un slot
 * de visor falso justamente para no depender de una cámara real, así que el `unbindAll()` de
 * [CameraPreviewSection] solo queda verificado aquí, con CameraX de verdad.
 *
 * La señal de fuga es directa y no depende de tiempos: `ProcessCameraProvider.isBound` sobre el
 * `ImageCapture` que el propio composable entrega. Si al abandonar la composición quedara ligado,
 * la cámara seguiría retenida y la siguiente entrada competiría por ella.
 *
 * Se omite (no falla) en dispositivos sin cámara: ahí el composable llama a `onCameraError`, que
 * es el camino ya cubierto por `cameraErrorStateOffersRetryAndPickingAnImage`.
 */
@RunWith(AndroidJUnit4::class)
class CameraPreviewSectionLifecycleTest {
    @get:Rule(order = 0)
    val composeRule = createComposeRule()

    @get:Rule(order = 1)
    val grantPermissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.CAMERA,
    )

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun leavingTheCameraReleasesItOnEveryVisit() {
        assumeTrue(
            "El dispositivo no declara cámara: el visor real no se puede ejercitar aquí.",
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY),
        )
        // Fuera del hilo principal a propósito: `get()` en el principal bloquearía la
        // inicialización de CameraX, que se completa precisamente en ese hilo.
        val provider = ProcessCameraProvider.getInstance(context)
            .get(PROVIDER_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        val boundCaptures = Collections.synchronizedList(mutableListOf<ImageCapture>())
        val cameraErrors = AtomicInteger(0)
        var visorVisible by mutableStateOf(true)

        composeRule.setContent {
            if (visorVisible) {
                CameraPreviewSection(
                    onCameraReady = { imageCapture, _ -> boundCaptures.add(imageCapture) },
                    onCameraError = { cameraErrors.incrementAndGet() },
                    onFocusChanged = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        repeat(VISITS) { visit ->
            if (visit > 0) {
                composeRule.runOnIdle { visorVisible = true }
            }
            composeRule.waitUntil(timeoutMillis = BIND_TIMEOUT_MILLIS) {
                boundCaptures.size > visit || cameraErrors.get() > 0
            }
            assumeTrue(
                "El dispositivo rechazó ligar la cámara trasera en la visita ${visit + 1}.",
                cameraErrors.get() == 0,
            )
            val capture = boundCaptures[visit]
            // `isBound` se consulta siempre en el hilo principal, como exige CameraX.
            composeRule.runOnIdle {
                assertTrue(
                    "La visita ${visit + 1} no dejó ligada su propia captura.",
                    provider.isBound(capture),
                )
            }

            composeRule.runOnIdle { visorVisible = false }
            composeRule.waitForIdle()
            composeRule.runOnIdle {
                boundCaptures.forEachIndexed { index, previous ->
                    assertFalse(
                        "Al salir en la visita ${visit + 1} quedó ligada la captura " +
                            "de la visita ${index + 1}: la cámara se filtró.",
                        provider.isBound(previous),
                    )
                }
            }
        }

        // Cada entrada rehace el enlace desde cero en vez de reutilizar el anterior.
        assertEquals(VISITS, boundCaptures.size)
        assertEquals(VISITS, boundCaptures.distinct().size)
    }

    private companion object {
        const val VISITS = 3
        const val PROVIDER_TIMEOUT_SECONDS = 15L
        const val BIND_TIMEOUT_MILLIS = 15_000L
    }
}
