package com.facturastock.app.feature.capture.camera

import android.content.Context
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Adaptador de vista aislado para CameraX: toda la dependencia del framework de cámara vive en
 * este composable de presentación, sin lógica de negocio. La validación y la persistencia de la
 * imagen capturada se hacen siempre por casos de uso (`ImportDraftImageUseCase`); aquí solo se
 * liga el visor, se entrega el `ImageCapture` a la ruta y se gestiona el enfoque táctil.
 *
 * Ciclo de vida: el `ProcessCameraProvider` se obtiene de forma suspendida sobre su
 * `ListenableFuture` (sin Guava en el classpath de la app) y `Preview` + `ImageCapture` se
 * ligan al `LifecycleOwner` local con la cámara trasera. Al salir de la composición se hace
 * `unbind()` solo de esos dos casos de uso, de modo que entrar y salir repetidamente de la
 * pantalla no filtra la cámara ni interfiere con otro consumidor del provider; al recrearse el
 * adaptador (por ejemplo tras limpiar un error) el enlace se rehace desde cero.
 *
 * Resolución objetivo de ~1440×2560: suficiente para leer caracteres pequeños de una factura en
 * el OCR posterior. Si el dispositivo no la ofrece se prefiere la alternativa inferior más
 * cercana, evitando que una cámara salte primero a 4K/12 MP, dispare el JPEG hacia el límite de
 * 15 MiB y aumente el coste de decodificación.
 * `CAPTURE_MODE_MAXIMIZE_QUALITY` porque la legibilidad del documento importa más que la
 * latencia: el usuario encuadra una factura quieta, no una escena en movimiento.
 */
@Composable
fun CameraPreviewSection(
    onCameraReady: (imageCapture: ImageCapture, hasFlashUnit: Boolean) -> Unit,
    onCameraError: () -> Unit,
    onFocusChanged: (offset: Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnCameraReady by rememberUpdatedState(onCameraReady)
    val currentOnCameraError by rememberUpdatedState(onCameraError)
    val currentOnFocusChanged by rememberUpdatedState(onFocusChanged)

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val binding = remember { CameraBinding() }

    LaunchedEffect(lifecycleOwner) {
        binding.release()
        val provider = try {
            context.awaitCameraProvider()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            currentOnCameraError()
            return@LaunchedEffect
        }

        val imageCapture = buildInvoiceImageCapture()
        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }

        val camera = try {
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Sin cámara trasera disponible o enlace rechazado por el dispositivo.
            null
        }
        if (camera == null) {
            provider.unbind(preview, imageCapture)
            currentOnCameraError()
        } else {
            binding.attach(provider, preview, imageCapture, camera)
            currentOnCameraReady(imageCapture, camera.cameraInfo.hasFlashUnit())
        }
    }

    // Liberar la cámara al abandonar la pantalla: sin esto el dispositivo queda retenido.
    DisposableEffect(lifecycleOwner) {
        onDispose {
            binding.release()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier.pointerInput(previewView) {
            detectTapGestures { tapOffset ->
                val camera = binding.camera ?: return@detectTapGestures
                val meteringPoint = previewView.meteringPointFactory
                    .createPoint(tapOffset.x, tapOffset.y)
                val action = FocusMeteringAction.Builder(
                    meteringPoint,
                    FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
                )
                    .setAutoCancelDuration(FOCUS_AUTO_CANCEL_SECONDS, TimeUnit.SECONDS)
                    .build()
                camera.cameraControl.startFocusAndMetering(action)
                currentOnFocusChanged(tapOffset)
            }
        },
    )
}

/** Referencias CameraX no observables: actualizarlas no debe recomponer el visor. */
private class CameraBinding {
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    var camera: Camera? = null
        private set

    fun attach(
        provider: ProcessCameraProvider,
        preview: Preview,
        imageCapture: ImageCapture,
        camera: Camera,
    ) {
        release()
        this.provider = provider
        this.preview = preview
        this.imageCapture = imageCapture
        this.camera = camera
    }

    fun release() {
        val currentProvider = provider
        val currentPreview = preview
        val currentImageCapture = imageCapture
        if (currentProvider != null && currentPreview != null && currentImageCapture != null) {
            currentProvider.unbind(currentPreview, currentImageCapture)
        }
        provider = null
        preview = null
        imageCapture = null
        camera = null
    }
}

/**
 * Única receta de captura para producción y Macrobenchmark. Mantenerla aquí evita que una prueba
 * mida una resolución/modo distintos de los que recibe el flujo real.
 */
internal fun buildInvoiceImageCapture(): ImageCapture = ImageCapture.Builder()
    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
    .setResolutionSelector(
        ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(TARGET_WIDTH_PX, TARGET_HEIGHT_PX),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                ),
            )
            .build(),
    )
    .build()

/** Espera el `ProcessCameraProvider` sin Guava: puente directo del `ListenableFuture`. */
private suspend fun Context.awaitCameraProvider(): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess(continuation::resume)
                    .onFailure(continuation::resumeWithException)
            },
            ContextCompat.getMainExecutor(this),
        )
    }

private const val TARGET_WIDTH_PX = 1_440
private const val TARGET_HEIGHT_PX = 2_560
private const val FOCUS_AUTO_CANCEL_SECONDS = 3L
