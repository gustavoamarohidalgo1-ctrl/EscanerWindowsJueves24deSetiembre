package com.facturastock.app.feature.preview

import org.jetbrains.compose.resources.StringResource

import com.facturastock.app.resources.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import com.facturastock.app.core.platform.LocalAppDirectories
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.facturastock.app.feature.common.sensitiveImageRequest
import com.facturastock.app.domain.model.ImageCrop
import com.facturastock.app.domain.model.ImageQualityWarning
import com.facturastock.app.ui.components.FacturaStockDialog
import com.facturastock.app.ui.components.FacturaStockPrimaryButton
import com.facturastock.app.ui.components.FacturaStockSecondaryButton
import com.facturastock.app.ui.components.RecoverableError
import com.facturastock.app.ui.components.StatusCard
import com.facturastock.app.ui.components.StatusTone
import com.facturastock.app.ui.theme.FacturaStockDesign
import java.io.File
import kotlin.math.roundToInt

/**
 * Vista previa de las páginas del documento ANTES del OCR. Cada página se muestra desde su
 * archivo local aplicando la rotación registrada; la pinza amplía (1×–5×) en modo revisión.
 * En modo recorte, las cuatro esquinas arrastrables ajustan el rectángulo normalizado —
 * clampado en el ViewModel y validado en dominio, así que nunca sale del archivo.
 */
@Composable
fun PreviewScreen(
    state: PreviewContract.State,
    onAction: (PreviewContract.Action) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .testTag(PreviewTestTags.SCREEN),
    ) {
        val maximumControlsHeight = maxHeight * MAX_CONTROLS_HEIGHT_FRACTION
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                val page = state.currentPage
                if (page != null) {
                    PageCanvas(
                        page = page,
                        state = state,
                        onAction = onAction,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (state.isBusy) {
                    Text(
                        text = stringResource(Res.string.preview_working),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }

            when (state.mode) {
                PreviewContract.Mode.VIEWING -> ViewingControls(
                    state = state,
                    onAction = onAction,
                    modifier = Modifier.heightIn(max = maximumControlsHeight),
                )
                PreviewContract.Mode.CROPPING -> CroppingControls(
                    state = state,
                    enabled = !state.isBusy,
                    onAction = onAction,
                    modifier = Modifier.heightIn(max = maximumControlsHeight),
                )
            }
        }
    }

    if (state.showDeleteConfirm) {
        FacturaStockDialog(
            title = stringResource(Res.string.preview_delete_title),
            message = stringResource(
                Res.string.preview_delete_message,
                state.currentIndex + 1,
            ),
            confirmLabel = stringResource(Res.string.preview_delete_confirm),
            dismissLabel = stringResource(Res.string.action_keep_editing),
            onConfirm = { onAction(PreviewContract.Action.DeleteConfirmed) },
            onDismiss = { onAction(PreviewContract.Action.DeleteDismissed) },
            modifier = Modifier.testTag(PreviewTestTags.DELETE_DIALOG),
        )
    }

    if (state.qualityWarnings.isNotEmpty()) {
        FacturaStockDialog(
            title = stringResource(Res.string.preview_quality_warning_title),
            message = qualityWarningMessage(state),
            confirmLabel = stringResource(Res.string.preview_quality_continue),
            dismissLabel = stringResource(Res.string.preview_quality_retake),
            onConfirm = {
                onAction(PreviewContract.Action.ContinueWithWarningsClicked)
            },
            onDismiss = {
                onAction(PreviewContract.Action.RetakeWarnedPageClicked)
            },
            // Repetir reemplaza una página y debe ser siempre una decisión explícita. Back o
            // un toque exterior mantienen el aviso abierto, sin navegar ni mutar el borrador.
            onRequestDismiss = {},
        )
    }
}

/** Convierte cada heurística en una explicación medible y evita lenguaje de certeza. */
@Composable
private fun qualityWarningMessage(state: PreviewContract.State): String {
    val intro = stringResource(Res.string.preview_quality_warning_intro)
    val lines = state.qualityWarnings.flatMap { report ->
        val pageNumber = state.pages.firstOrNull { it.imageId == report.imageId }
            ?.pageIndex
            ?.plus(1)
            ?: 1
        report.warnings.map { warning ->
            stringResource(
                Res.string.preview_quality_warning_page,
                pageNumber,
                qualityWarningDetail(warning),
            )
        }
    }
    return buildString {
        append(intro)
        lines.forEach { line ->
            append("\n\n• ")
            append(line)
        }
    }
}

@Composable
private fun qualityWarningDetail(
    warning: ImageQualityWarning,
): String = when (warning) {
    is ImageQualityWarning.LowResolution -> stringResource(
        Res.string.preview_quality_low_resolution,
        warning.widthPx,
        warning.heightPx,
        warning.minimumShortSidePx,
        warning.minimumLongSidePx,
    )
    is ImageQualityWarning.PossibleBlur -> stringResource(
        Res.string.preview_quality_blur,
        warning.sharpnessScore,
        warning.recommendedMinimum,
    )
    is ImageQualityWarning.PossibleUnderexposure -> stringResource(
        Res.string.preview_quality_underexposed,
        warning.meanLuminance,
        formatPermille(warning.darkPixelsPermille),
    )
    is ImageQualityWarning.PossibleOverexposure -> stringResource(
        Res.string.preview_quality_overexposed,
        warning.meanLuminance,
        formatPermille(warning.brightPixelsPermille),
    )
    is ImageQualityWarning.PossibleSkew -> stringResource(
        Res.string.preview_quality_skew,
        formatTenths(warning.estimatedDegreesTenths, suffix = "°"),
        formatPermille(warning.confidencePermille),
    )
    is ImageQualityWarning.PossibleIncompleteCrop -> stringResource(
        Res.string.preview_quality_incomplete_crop,
        formatPermille(warning.borderContentPermille),
        formatPermille(warning.warningThresholdPermille),
    )
}

private fun formatPermille(value: Int): String = formatTenths(value.coerceIn(0, 1_000), "%")

private fun formatTenths(value: Int, suffix: String): String {
    val absolute = kotlin.math.abs(value)
    val sign = if (value < 0) "−" else ""
    return "$sign${absolute / 10}.${absolute % 10}$suffix"
}

/** Zona de imagen: archivo local con rotación aplicada, zoom por pinza y capa de recorte. */
@Composable
private fun PageCanvas(
    page: PreviewContract.Page,
    state: PreviewContract.State,
    onAction: (PreviewContract.Action) -> Unit,
    modifier: Modifier = Modifier,
) {
    val directories = LocalAppDirectories.current
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val containerWidthPx = with(density) { maxWidth.toPx() }
        val containerHeightPx = with(density) { maxHeight.toPx() }
        val rotated = page.rotationDegrees == 90 || page.rotationDegrees == 270
        val frameWidthPx = if (rotated) page.heightPx else page.widthPx
        val frameHeightPx = if (rotated) page.widthPx else page.heightPx
        val fitScale = minOf(
            containerWidthPx / frameWidthPx,
            containerHeightPx / frameHeightPx,
        )
        val displayWidthPx = frameWidthPx * fitScale
        val displayHeightPx = frameHeightPx * fitScale

        // Zoom solo en modo revisión; cambiar de página o de modo lo reinicia.
        var zoom by remember(page.imageId, state.mode) { mutableFloatStateOf(1f) }
        var pan by remember(page.imageId, state.mode) { mutableStateOf(Offset.Zero) }
        val maxPanX = (zoom - 1f) * displayWidthPx / 2f
        val maxPanY = (zoom - 1f) * displayHeightPx / 2f

        val imageModifier = if (state.mode == PreviewContract.Mode.VIEWING) {
            Modifier.pointerInput(page.imageId) {
                detectTransformGestures { _, panChange, zoomChange, _ ->
                    zoom = (zoom * zoomChange).coerceIn(1f, 5f)
                    val nextMaxX = (zoom - 1f) * displayWidthPx / 2f
                    val nextMaxY = (zoom - 1f) * displayHeightPx / 2f
                    pan = Offset(
                        (pan.x + panChange.x).coerceIn(-nextMaxX, nextMaxX),
                        (pan.y + panChange.y).coerceIn(-nextMaxY, nextMaxY),
                    )
                }
            }
        } else {
            Modifier
        }

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(
                    with(density) { displayWidthPx.toDp() },
                    with(density) { displayHeightPx.toDp() },
                )
                .graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = pan.x.coerceIn(-maxPanX, maxPanX)
                    translationY = pan.y.coerceIn(-maxPanY, maxPanY)
                }
                .then(imageModifier),
        ) {
            AsyncImage(
                model = sensitiveImageRequest(File(directories.filesDir, page.filePath)),
                contentDescription = stringResource(
                    Res.string.preview_page_image_description,
                    page.pageIndex + 1,
                ),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(
                        with(density) { (page.widthPx * fitScale).toDp() },
                        with(density) { (page.heightPx * fitScale).toDp() },
                    )
                    .graphicsLayer { rotationZ = page.rotationDegrees.toFloat() }
                    .testTag(PreviewTestTags.PAGE_IMAGE),
            )
            if (state.mode == PreviewContract.Mode.CROPPING && state.cropDraft != null) {
                CropOverlay(
                    crop = state.cropDraft,
                    widthPx = displayWidthPx,
                    heightPx = displayHeightPx,
                    onCornerDragged = { corner, xFraction, yFraction ->
                        onAction(
                            PreviewContract.Action.CropCornerDragged(
                                corner = corner,
                                xFraction = xFraction,
                                yFraction = yFraction,
                            ),
                        )
                    },
                )
            }
        }
    }
}

/** Capa de recorte: velo fuera del rectángulo, borde y cuatro esquinas arrastrables. */
@Composable
private fun CropOverlay(
    crop: ImageCrop,
    widthPx: Float,
    heightPx: Float,
    onCornerDragged: (PreviewContract.CropCorner, Int, Int) -> Unit,
) {
    val density = LocalDensity.current
    val currentCrop by rememberUpdatedState(crop)
    val handleTouch = FacturaStockDesign.spacing.minimumTouchTarget
    val scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)
    val borderColor = MaterialTheme.colorScheme.primary
    val handleInnerColor = MaterialTheme.colorScheme.onPrimary

    fun fractionToPx(fraction: Int, dimension: Float): Float =
        fraction.toFloat() / ImageCrop.FRACTION_MAX * dimension

    fun cornerPosition(corner: PreviewContract.CropCorner): Offset {
        val rect = currentCrop
        return when (corner) {
            PreviewContract.CropCorner.TOP_LEFT ->
                Offset(fractionToPx(rect.left, widthPx), fractionToPx(rect.top, heightPx))
            PreviewContract.CropCorner.TOP_RIGHT ->
                Offset(fractionToPx(rect.right, widthPx), fractionToPx(rect.top, heightPx))
            PreviewContract.CropCorner.BOTTOM_LEFT ->
                Offset(fractionToPx(rect.left, widthPx), fractionToPx(rect.bottom, heightPx))
            PreviewContract.CropCorner.BOTTOM_RIGHT ->
                Offset(fractionToPx(rect.right, widthPx), fractionToPx(rect.bottom, heightPx))
        }
    }

    val rect = with(currentCrop) {
        Rect(
            left = fractionToPx(left, widthPx),
            top = fractionToPx(top, heightPx),
            right = fractionToPx(right, widthPx),
            bottom = fractionToPx(bottom, heightPx),
        )
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .testTag(PreviewTestTags.CROP_OVERLAY),
    ) {
        drawRect(scrimColor, topLeft = Offset.Zero, size = size.copy(height = rect.top))
        drawRect(
            scrimColor,
            topLeft = Offset(0f, rect.bottom),
            size = size.copy(height = size.height - rect.bottom),
        )
        drawRect(
            scrimColor,
            topLeft = Offset(0f, rect.top),
            size = androidx.compose.ui.geometry.Size(rect.left, rect.height),
        )
        drawRect(
            scrimColor,
            topLeft = Offset(rect.right, rect.top),
            size = androidx.compose.ui.geometry.Size(size.width - rect.right, rect.height),
        )
        drawRect(
            color = borderColor,
            topLeft = rect.topLeft,
            size = rect.size,
            style = Stroke(width = 3.dp.toPx()),
        )
    }

    val moveLeft = stringResource(Res.string.preview_crop_move_left)
    val moveRight = stringResource(Res.string.preview_crop_move_right)
    val moveUp = stringResource(Res.string.preview_crop_move_up)
    val moveDown = stringResource(Res.string.preview_crop_move_down)
    PreviewContract.CropCorner.entries.forEach { corner ->
        val position = cornerPosition(corner)
        val handleSizePx = with(density) { handleTouch.toPx() }
        val xFraction = when (corner) {
            PreviewContract.CropCorner.TOP_LEFT,
            PreviewContract.CropCorner.BOTTOM_LEFT,
            -> currentCrop.left
            PreviewContract.CropCorner.TOP_RIGHT,
            PreviewContract.CropCorner.BOTTOM_RIGHT,
            -> currentCrop.right
        }
        val yFraction = when (corner) {
            PreviewContract.CropCorner.TOP_LEFT,
            PreviewContract.CropCorner.TOP_RIGHT,
            -> currentCrop.top
            PreviewContract.CropCorner.BOTTOM_LEFT,
            PreviewContract.CropCorner.BOTTOM_RIGHT,
            -> currentCrop.bottom
        }
        val handleDescription = stringResource(cropCornerDescription(corner))
        val positionDescription = stringResource(
            Res.string.preview_crop_handle_position,
            xFraction / FRACTION_PER_PERCENT,
            yFraction / FRACTION_PER_PERCENT,
        )
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (position.x - handleSizePx / 2f).roundToInt(),
                        (position.y - handleSizePx / 2f).roundToInt(),
                    )
                }
                .size(handleTouch)
                .testTag(PreviewTestTags.cropHandle(corner))
                .semantics {
                    contentDescription = handleDescription
                    stateDescription = positionDescription
                    customActions = listOf(
                        CustomAccessibilityAction(moveLeft) {
                            onCornerDragged(
                                corner,
                                xFraction - ACCESSIBILITY_CROP_STEP,
                                yFraction,
                            )
                            true
                        },
                        CustomAccessibilityAction(moveRight) {
                            onCornerDragged(
                                corner,
                                xFraction + ACCESSIBILITY_CROP_STEP,
                                yFraction,
                            )
                            true
                        },
                        CustomAccessibilityAction(moveUp) {
                            onCornerDragged(
                                corner,
                                xFraction,
                                yFraction - ACCESSIBILITY_CROP_STEP,
                            )
                            true
                        },
                        CustomAccessibilityAction(moveDown) {
                            onCornerDragged(
                                corner,
                                xFraction,
                                yFraction + ACCESSIBILITY_CROP_STEP,
                            )
                            true
                        },
                    )
                }
                .pointerInput(corner) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val base = cornerPosition(corner)
                        onCornerDragged(
                            corner,
                            ((base.x + dragAmount.x) / widthPx * ImageCrop.FRACTION_MAX)
                                .roundToInt(),
                            ((base.y + dragAmount.y) / heightPx * ImageCrop.FRACTION_MAX)
                                .roundToInt(),
                        )
                    }
                },
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(24.dp)
                    .graphicsLayer { },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(borderColor)
                    drawCircle(handleInnerColor, radius = size.minDimension / 4f)
                }
            }
        }
    }
}

private fun cropCornerDescription(corner: PreviewContract.CropCorner): StringResource = when (corner) {
    PreviewContract.CropCorner.TOP_LEFT -> Res.string.preview_crop_handle_top_left
    PreviewContract.CropCorner.TOP_RIGHT -> Res.string.preview_crop_handle_top_right
    PreviewContract.CropCorner.BOTTOM_LEFT -> Res.string.preview_crop_handle_bottom_left
    PreviewContract.CropCorner.BOTTOM_RIGHT -> Res.string.preview_crop_handle_bottom_right
}

@Composable
private fun PreviewErrorCards(
    state: PreviewContract.State,
    onAction: (PreviewContract.Action) -> Unit,
) {
    if (state.processFailed && state.mode == PreviewContract.Mode.VIEWING) {
        RecoverableError(
            title = stringResource(Res.string.preview_process_error_title),
            message = stringResource(Res.string.preview_process_error_message),
            actionLabel = stringResource(Res.string.action_retry),
            onAction = { onAction(PreviewContract.Action.ProcessClicked) },
        )
    }
    if (state.failure == PreviewContract.Failure.MUTATION_FAILED) {
        StatusCard(
            statusLabel = stringResource(Res.string.preview_edit_error_status),
            title = stringResource(Res.string.preview_edit_error_title),
            message = stringResource(Res.string.preview_edit_error_message),
            tone = StatusTone.ERROR,
            iconRes = Res.drawable.ic_warning,
        )
    }
}

/** Controles del modo revisión: navegación de páginas y las siete acciones del documento. */
@Composable
private fun ViewingControls(
    state: PreviewContract.State,
    onAction: (PreviewContract.Action) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FacturaStockDesign.spacing
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.lg, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        PreviewErrorCards(state = state, onAction = onAction)
        Text(
            text = stringResource(
                Res.string.preview_page_indicator,
                state.currentIndex + 1,
                state.pages.size,
            ),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(PreviewTestTags.PAGE_INDICATOR)
                .semantics { liveRegion = LiveRegionMode.Polite },
            textAlign = TextAlign.Center,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FacturaStockSecondaryButton(
                text = stringResource(Res.string.preview_previous_page),
                onClick = { onAction(PreviewContract.Action.PreviousPageClicked) },
                enabled = !state.isBusy && state.currentIndex > 0,
                modifier = Modifier
                    .weight(1f)
                    .testTag(PreviewTestTags.PREVIOUS_PAGE),
            )
            FacturaStockSecondaryButton(
                text = stringResource(Res.string.preview_next_page),
                onClick = { onAction(PreviewContract.Action.NextPageClicked) },
                enabled = !state.isBusy && state.currentIndex < state.pages.lastIndex,
                modifier = Modifier
                    .weight(1f)
                    .testTag(PreviewTestTags.NEXT_PAGE),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            FacturaStockSecondaryButton(
                text = stringResource(Res.string.preview_action_retake),
                onClick = { onAction(PreviewContract.Action.RetakeClicked) },
                enabled = !state.isBusy,
                modifier = Modifier
                    .weight(1f)
                    .testTag(PreviewTestTags.ACTION_RETAKE),
            )
            FacturaStockSecondaryButton(
                text = stringResource(Res.string.preview_action_rotate),
                onClick = { onAction(PreviewContract.Action.RotateClicked) },
                enabled = !state.isBusy,
                modifier = Modifier
                    .weight(1f)
                    .testTag(PreviewTestTags.ACTION_ROTATE),
            )
        }
        FacturaStockSecondaryButton(
            text = stringResource(Res.string.preview_action_crop),
            onClick = { onAction(PreviewContract.Action.CropClicked) },
            enabled = !state.isBusy,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(PreviewTestTags.ACTION_CROP),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            FacturaStockSecondaryButton(
                text = stringResource(Res.string.preview_action_add_page),
                onClick = { onAction(PreviewContract.Action.AddPageClicked) },
                enabled = !state.isBusy,
                modifier = Modifier
                    .weight(1f)
                    .testTag(PreviewTestTags.ACTION_ADD_PAGE),
            )
            FacturaStockSecondaryButton(
                text = stringResource(Res.string.preview_action_move_up),
                onClick = { onAction(PreviewContract.Action.MoveUpClicked) },
                enabled = !state.isBusy && state.currentIndex > 0,
                modifier = Modifier
                    .weight(1f)
                    .testTag(PreviewTestTags.ACTION_MOVE_UP),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            FacturaStockSecondaryButton(
                text = stringResource(Res.string.preview_action_move_down),
                onClick = { onAction(PreviewContract.Action.MoveDownClicked) },
                enabled = !state.isBusy && state.currentIndex < state.pages.lastIndex,
                modifier = Modifier
                    .weight(1f)
                    .testTag(PreviewTestTags.ACTION_MOVE_DOWN),
            )
            FacturaStockSecondaryButton(
                text = stringResource(Res.string.preview_action_delete),
                onClick = { onAction(PreviewContract.Action.DeleteClicked) },
                enabled = !state.isBusy,
                modifier = Modifier
                    .weight(1f)
                    .testTag(PreviewTestTags.ACTION_DELETE),
            )
        }

        FacturaStockPrimaryButton(
            text = stringResource(Res.string.preview_action_process),
            onClick = { onAction(PreviewContract.Action.ProcessClicked) },
            enabled = !state.isBusy && state.pages.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(PreviewTestTags.ACTION_PROCESS),
        )
    }
}

/** Controles del modo recorte: confirmar persiste el rectángulo; cancelar lo descarta. */
@Composable
private fun CroppingControls(
    state: PreviewContract.State,
    enabled: Boolean,
    onAction: (PreviewContract.Action) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FacturaStockDesign.spacing
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.lg, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        PreviewErrorCards(state = state, onAction = onAction)
        Text(
            text = stringResource(Res.string.preview_crop_hint),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            FacturaStockSecondaryButton(
                text = stringResource(Res.string.preview_crop_cancel),
                onClick = { onAction(PreviewContract.Action.CropCancelled) },
                enabled = enabled,
                modifier = Modifier
                    .weight(1f)
                    .testTag(PreviewTestTags.CROP_CANCEL),
            )
            FacturaStockPrimaryButton(
                text = stringResource(Res.string.preview_crop_apply),
                onClick = { onAction(PreviewContract.Action.CropConfirmed) },
                enabled = enabled,
                modifier = Modifier
                    .weight(1f)
                    .testTag(PreviewTestTags.CROP_APPLY),
            )
        }
    }
}

private const val ACCESSIBILITY_CROP_STEP = 500
private const val FRACTION_PER_PERCENT = 100
private const val MAX_CONTROLS_HEIGHT_FRACTION = 0.6f
