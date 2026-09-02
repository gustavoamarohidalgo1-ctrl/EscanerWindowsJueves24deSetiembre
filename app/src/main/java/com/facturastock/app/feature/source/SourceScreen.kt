package com.facturastock.app.feature.source

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.facturastock.app.R
import com.facturastock.app.feature.purchase.PURCHASE_EDITABLE_STEP_COUNT
import com.facturastock.app.ui.components.FacturaStockPrimaryButton
import com.facturastock.app.ui.components.FacturaStockSecondaryButton
import com.facturastock.app.ui.components.LoadingState
import com.facturastock.app.ui.components.StatusCard
import com.facturastock.app.ui.components.StatusTone
import com.facturastock.app.ui.theme.FacturaStockDesign

/**
 * Origen de la factura: cámara o galería, con el aviso de privacidad siempre visible. Es
 * stateless — el estado llega por parámetros y toda interacción se notifica por callbacks —,
 * por lo que sirve igual a la rama con ViewModel ([SourceRoute]) que a las pruebas de
 * pantalla.
 */
@Composable
fun SourceScreen(
    modifier: Modifier = Modifier,
    state: SourceContract.State = SourceContract.State(),
    onTakePhoto: () -> Unit = {},
    onPickImage: () -> Unit = {},
    onRetryCameraPermission: () -> Unit = {},
    onOpenCameraSettings: () -> Unit = {},
    onDismissInvalidImage: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    val spacing = FacturaStockDesign.spacing

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        item {
            Text(
                text = stringResource(
                    R.string.purchase_flow_step,
                    1,
                    PURCHASE_EDITABLE_STEP_COUNT,
                ),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        item {
            Text(
                text = stringResource(R.string.purchase_source_title),
                modifier = Modifier.semantics { heading() },
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.headlineLarge,
            )
        }
        item {
            StatusCard(
                statusLabel = stringResource(R.string.source_privacy_label),
                title = stringResource(R.string.source_privacy_title),
                message = stringResource(R.string.source_privacy_message),
                tone = StatusTone.INFO,
                iconRes = R.drawable.ic_info,
                modifier = Modifier.testTag(SourceTestTags.PRIVACY_CARD),
            )
        }
        item {
            FacturaStockPrimaryButton(
                text = stringResource(R.string.action_take_photo),
                onClick = onTakePhoto,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SourceTestTags.TAKE_PHOTO),
                enabled = !state.isImporting,
                leadingIconRes = R.drawable.ic_camera,
            )
        }
        item {
            FacturaStockSecondaryButton(
                text = stringResource(R.string.action_pick_image),
                onClick = onPickImage,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SourceTestTags.PICK_IMAGE),
                enabled = !state.isImporting,
                leadingIconRes = R.drawable.ic_photo_library,
            )
        }
        if (state.isImporting) {
            item {
                LoadingState(
                    message = stringResource(R.string.source_importing),
                    modifier = Modifier.testTag(SourceTestTags.IMPORTING),
                )
            }
        }
        if (state.cameraAccessDenied) {
            item {
                SourceActionCard(
                    card = {
                        StatusCard(
                            statusLabel = stringResource(R.string.source_camera_denied_label),
                            title = stringResource(R.string.source_camera_denied_title),
                            message = stringResource(R.string.source_camera_denied_message),
                            tone = StatusTone.WARNING,
                            iconRes = R.drawable.ic_camera,
                            modifier = Modifier.testTag(SourceTestTags.CAMERA_DENIED),
                        )
                    },
                    actionLabel = stringResource(
                        if (state.cameraPermissionPermanentlyDenied) {
                            R.string.action_open_app_settings
                        } else {
                            R.string.action_retry
                        },
                    ),
                    onAction = if (state.cameraPermissionPermanentlyDenied) {
                        onOpenCameraSettings
                    } else {
                        onRetryCameraPermission
                    },
                    actionTestTag = SourceTestTags.CAMERA_DENIED_RETRY,
                )
            }
        }
        if (state.invalidImage != null) {
            item {
                SourceActionCard(
                    card = {
                        StatusCard(
                            statusLabel = stringResource(R.string.source_invalid_image_label),
                            title = stringResource(R.string.source_invalid_image_title),
                            message = stringResource(
                                invalidImageMessageRes(state.invalidImage),
                            ),
                            tone = StatusTone.ERROR,
                            iconRes = R.drawable.ic_warning,
                            modifier = Modifier.testTag(SourceTestTags.INVALID_IMAGE),
                        )
                    },
                    actionLabel = stringResource(R.string.action_dismiss),
                    onAction = onDismissInvalidImage,
                    actionTestTag = SourceTestTags.INVALID_IMAGE_DISMISS,
                )
            }
        }
        item {
            FacturaStockSecondaryButton(
                text = stringResource(R.string.action_previous_step),
                onClick = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SourceTestTags.BACK),
            )
        }
    }
}

/** Tarjeta de estado con su acción pegada debajo, leída como una sola unidad visual. */
@Composable
private fun SourceActionCard(
    card: @Composable () -> Unit,
    actionLabel: String,
    onAction: () -> Unit,
    actionTestTag: String,
) {
    val spacing = FacturaStockDesign.spacing

    Column {
        card()
        Spacer(modifier = Modifier.height(spacing.xs))
        FacturaStockSecondaryButton(
            text = actionLabel,
            onClick = onAction,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(actionTestTag),
        )
    }
}

private fun invalidImageMessageRes(reason: SourceContract.InvalidImageReason): Int =
    when (reason) {
        SourceContract.InvalidImageReason.UNSUPPORTED_FORMAT ->
            R.string.source_invalid_image_unsupported

        SourceContract.InvalidImageReason.TOO_LARGE ->
            R.string.source_invalid_image_too_large

        SourceContract.InvalidImageReason.CORRUPT ->
            R.string.source_invalid_image_corrupt

        SourceContract.InvalidImageReason.NOT_FOUND ->
            R.string.source_invalid_image_not_found

        SourceContract.InvalidImageReason.STORAGE_FULL ->
            R.string.source_invalid_image_storage_full

        SourceContract.InvalidImageReason.UNAVAILABLE ->
            R.string.source_invalid_image_unavailable
    }
