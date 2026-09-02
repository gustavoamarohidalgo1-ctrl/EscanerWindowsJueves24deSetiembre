package com.facturastock.app.feature.capture

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.facturastock.app.R
import com.facturastock.app.ui.components.FacturaStockPrimaryButton
import com.facturastock.app.ui.components.FacturaStockSecondaryButton
import com.facturastock.app.ui.components.LoadingState
import com.facturastock.app.ui.components.StatusCard
import com.facturastock.app.ui.components.StatusTone
import com.facturastock.app.ui.theme.FacturaStockDesign

/**
 * Pantalla de captura con cámara, stateless: el estado llega por parámetros y toda interacción
 * se notifica por callbacks. El visor de cámara entra por el slot [cameraPreview], de modo que
 * la pantalla se prueba y previsualiza sin cámara real pasando un composable sustituto.
 *
 * Sobre el visor se dibuja un marco rectangular vertical (proporción tipo A4) con scrim
 * translúcido alrededor para guiar el encuadre de la factura, la instrucción de encuadre, el
 * indicador de enfoque táctil (un círculo que aparece donde el usuario tocó y se desvanece) y
 * los controles: obturador grande —deshabilitado hasta que CameraX esté listo y durante una
 * captura en curso— y el
 * botón de flash, oculto cuando la cámara no tiene flash ([hasFlashUnit] lo informa la ruta).
 *
 * Los estados de permiso denegado y de error de cámara sustituyen al visor a pantalla
 * completa. [allowImagePicker] conserva la salida a galería para entradas legacy; el recorrido
 * directo de dos pasos la oculta.
 */
@Composable
fun CaptureScreen(
    modifier: Modifier = Modifier,
    state: CaptureContract.State = CaptureContract.State(),
    hasFlashUnit: Boolean = true,
    focusIndicatorOffset: Offset? = null,
    onCaptureClicked: () -> Unit = {},
    onFlashClicked: () -> Unit = {},
    onGrantPermission: () -> Unit = {},
    onRetryCamera: () -> Unit = {},
    onPickImage: () -> Unit = {},
    allowImagePicker: Boolean = true,
    onBack: () -> Unit = {},
    cameraPreview: @Composable () -> Unit = {},
) {
    Box(modifier = modifier.fillMaxSize()) {
        when {
            state.cameraPermissionDenied -> CapturePermissionDeniedState(
                permanentlyDenied = state.cameraPermissionPermanentlyDenied,
                onGrantPermission = onGrantPermission,
                onPickImage = onPickImage,
                allowImagePicker = allowImagePicker,
            )

            state.cameraError != null -> CaptureErrorState(
                cameraError = state.cameraError,
                onRetryCamera = onRetryCamera,
                onPickImage = onPickImage,
                allowImagePicker = allowImagePicker,
            )

            else -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(CaptureTestTags.PREVIEW),
                ) {
                    cameraPreview()
                }
                CaptureFrameOverlay()
                CaptureFocusIndicator(offset = focusIndicatorOffset)
                CaptureControls(
                    state = state,
                    hasFlashUnit = hasFlashUnit,
                    onCaptureClicked = onCaptureClicked,
                    onFlashClicked = onFlashClicked,
                    onBack = onBack,
                )
                if (state.isCapturing) {
                    CaptureInProgressOverlay()
                }
            }
        }
    }
}

/** Marco guía con scrim translúcido fuera de él y la instrucción de encuadre encima. */
@Composable
private fun CaptureFrameOverlay(modifier: Modifier = Modifier) {
    val spacing = FacturaStockDesign.spacing
    val scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = FRAME_SCRIM_ALPHA)
    val frameColor = MaterialTheme.colorScheme.primary
    val frameBorderWidth = spacing.borderThin * 2
    val frameCorner = spacing.md

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .testTag(CaptureTestTags.FRAME),
    ) {
        // Proporción vertical tipo A4: la factura estándar es más alta que ancha.
        val frameWidth = size.width * FRAME_WIDTH_FRACTION
        val frameHeight = frameWidth * FRAME_HEIGHT_PER_WIDTH
        val frameLeft = (size.width - frameWidth) / 2f
        val frameTop = (size.height - frameHeight) / 2f
        val frameRect = Rect(
            offset = Offset(frameLeft, frameTop),
            size = Size(frameWidth, frameHeight),
        )
        val corner = CornerRadius(frameCorner.toPx())

        clipPath(
            path = Path().apply { addRoundRect(RoundRect(frameRect, corner)) },
            clipOp = ClipOp.Difference,
        ) {
            drawRect(color = scrimColor)
        }
        drawRoundRect(
            color = frameColor,
            topLeft = Offset(frameRect.left, frameRect.top),
            size = Size(frameRect.width, frameRect.height),
            cornerRadius = corner,
            style = Stroke(width = frameBorderWidth.toPx()),
        )
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Text(
            text = stringResource(R.string.capture_frame_instruction),
            modifier = Modifier
                .padding(top = spacing.xxl)
                .background(
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.medium,
                )
                .padding(horizontal = spacing.md, vertical = spacing.xs),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
    }
}

/** Círculo de enfoque: aparece en el punto tocado y se desvanece en aproximadamente 1 s. */
@Composable
private fun CaptureFocusIndicator(offset: Offset?, modifier: Modifier = Modifier) {
    if (offset == null) return
    val ringColor = MaterialTheme.colorScheme.primary
    val alpha = remember { Animatable(1f) }

    LaunchedEffect(offset) {
        alpha.snapTo(1f)
        alpha.animateTo(0f, animationSpec = tween(durationMillis = FOCUS_FADE_MS))
    }
    if (alpha.value <= 0f) return

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .testTag(CaptureTestTags.FOCUS_INDICATOR),
    ) {
        drawCircle(
            color = ringColor,
            radius = FOCUS_RING_RADIUS.toPx(),
            center = offset,
            alpha = alpha.value,
            style = Stroke(width = FOCUS_RING_STROKE.toPx()),
        )
    }
}

/** Controles inferiores: volver, obturador grande y flash (o hueco si la cámara no tiene). */
@Composable
private fun CaptureControls(
    state: CaptureContract.State,
    hasFlashUnit: Boolean,
    onCaptureClicked: () -> Unit,
    onFlashClicked: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FacturaStockDesign.spacing

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.lg, vertical = spacing.xl),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag(CaptureTestTags.BACK),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_back),
                        contentDescription = stringResource(R.string.capture_back_to_source),
                    )
                }
            }

            Surface(
                onClick = onCaptureClicked,
                enabled = state.isCameraReady && !state.isCapturing,
                modifier = Modifier
                    .size(SHUTTER_SIZE)
                    .testTag(CaptureTestTags.SHUTTER),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                border = BorderStroke(spacing.xxs, MaterialTheme.colorScheme.onPrimary),
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_camera),
                        contentDescription = stringResource(R.string.action_take_photo),
                        modifier = Modifier.size(spacing.iconLarge),
                    )
                }
            }

            if (hasFlashUnit) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    IconButton(
                        onClick = onFlashClicked,
                        modifier = Modifier.testTag(CaptureTestTags.FLASH),
                    ) {
                        Icon(
                            painter = painterResource(flashIconRes(state.flashMode)),
                            contentDescription = stringResource(
                                flashDescriptionRes(state.flashMode),
                            ),
                        )
                    }
                }
            } else {
                // Hueco del mismo ancho para que el obturador quede centrado.
                Spacer(modifier = Modifier.size(spacing.minimumTouchTarget))
            }
        }
    }
}

/** Velo sobre el visor mientras la captura/importación está en curso. */
@Composable
private fun CaptureInProgressOverlay(modifier: Modifier = Modifier) {
    val spacing = FacturaStockDesign.spacing

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                MaterialTheme.colorScheme.scrim.copy(alpha = FRAME_SCRIM_ALPHA),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            LoadingState(
                message = stringResource(R.string.capture_in_progress),
                modifier = Modifier
                    .padding(horizontal = spacing.xl)
                    .testTag(CaptureTestTags.IMPORTING),
            )
        }
    }
}

/** Permiso denegado: permite concederlo y, solo desde entradas legacy, usar la galería. */
@Composable
private fun CapturePermissionDeniedState(
    permanentlyDenied: Boolean,
    onGrantPermission: () -> Unit,
    onPickImage: () -> Unit,
    allowImagePicker: Boolean,
    modifier: Modifier = Modifier,
) {
    CaptureFullScreenState(
        card = {
            StatusCard(
                statusLabel = stringResource(R.string.source_camera_denied_label),
                title = stringResource(R.string.source_camera_denied_title),
                message = stringResource(R.string.source_camera_denied_message),
                tone = StatusTone.WARNING,
                iconRes = R.drawable.ic_camera,
                announcementMode = LiveRegionMode.Assertive,
                modifier = Modifier.testTag(CaptureTestTags.PERMISSION_DENIED),
            )
        },
        primaryLabel = stringResource(
            if (permanentlyDenied) {
                R.string.action_open_app_settings
            } else {
                R.string.action_grant_permission
            },
        ),
        onPrimary = onGrantPermission,
        primaryTestTag = CaptureTestTags.GRANT_PERMISSION,
        onPickImage = onPickImage,
        allowImagePicker = allowImagePicker,
        modifier = modifier,
    )
}

/** Error recuperable: ofrece reintentar y, solo desde entradas legacy, usar la galería. */
@Composable
private fun CaptureErrorState(
    cameraError: CaptureContract.CameraErrorKind,
    onRetryCamera: () -> Unit,
    onPickImage: () -> Unit,
    allowImagePicker: Boolean,
    modifier: Modifier = Modifier,
) {
    val (titleRes, messageRes) = when (cameraError) {
        CaptureContract.CameraErrorKind.STORAGE_FULL ->
            R.string.capture_storage_full_title to R.string.capture_storage_full_message

        CaptureContract.CameraErrorKind.IMPORT_FAILED ->
            R.string.capture_import_error_title to R.string.capture_import_error_message

        CaptureContract.CameraErrorKind.BIND_FAILED,
        CaptureContract.CameraErrorKind.CAPTURE_FAILED,
        ->
            R.string.capture_camera_error_title to R.string.capture_camera_error_message
    }

    CaptureFullScreenState(
        card = {
            StatusCard(
                statusLabel = stringResource(R.string.capture_camera_error_label),
                title = stringResource(titleRes),
                message = stringResource(messageRes),
                tone = StatusTone.ERROR,
                iconRes = R.drawable.ic_warning,
                modifier = Modifier.testTag(CaptureTestTags.CAMERA_ERROR),
            )
        },
        primaryLabel = stringResource(R.string.action_retry),
        onPrimary = onRetryCamera,
        primaryTestTag = CaptureTestTags.RETRY,
        onPickImage = onPickImage,
        allowImagePicker = allowImagePicker,
        modifier = modifier,
    )
}

/** Tarjeta de estado a pantalla completa con su acción principal y galería legacy opcional. */
@Composable
private fun CaptureFullScreenState(
    card: @Composable () -> Unit,
    primaryLabel: String,
    onPrimary: () -> Unit,
    primaryTestTag: String,
    modifier: Modifier = Modifier,
    onPickImage: () -> Unit = {},
    allowImagePicker: Boolean = true,
) {
    val spacing = FacturaStockDesign.spacing

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        card()
        Spacer(modifier = Modifier.height(spacing.sm))
        FacturaStockPrimaryButton(
            text = primaryLabel,
            onClick = onPrimary,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(primaryTestTag),
        )
        if (allowImagePicker) {
            FacturaStockSecondaryButton(
                text = stringResource(R.string.action_pick_image),
                onClick = onPickImage,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(CaptureTestTags.PICK_IMAGE),
                leadingIconRes = R.drawable.ic_photo_library,
            )
        }
    }
}

private fun flashIconRes(mode: CaptureFlashMode): Int = when (mode) {
    CaptureFlashMode.AUTO -> R.drawable.ic_flash_auto
    CaptureFlashMode.ON -> R.drawable.ic_flash_on
    CaptureFlashMode.OFF -> R.drawable.ic_flash_off
}

private fun flashDescriptionRes(mode: CaptureFlashMode): Int = when (mode) {
    CaptureFlashMode.AUTO -> R.string.capture_flash_auto
    CaptureFlashMode.ON -> R.string.capture_flash_on
    CaptureFlashMode.OFF -> R.string.capture_flash_off
}

/** Proporción vertical tipo A4 (alto = ancho × √2) para el marco guía de la factura. */
private const val FRAME_HEIGHT_PER_WIDTH = 1.4142f
private const val FRAME_WIDTH_FRACTION = 0.86f
private const val FRAME_SCRIM_ALPHA = 0.36f
private const val FOCUS_FADE_MS = 1_000
private val SHUTTER_SIZE = 72.dp
private val FOCUS_RING_RADIUS = 36.dp
private val FOCUS_RING_STROKE = 3.dp
