package com.facturastock.app.feature.ocr

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.facturastock.app.R
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.usecase.InvoiceOcrStage
import com.facturastock.app.feature.common.CollectUiEffects
import com.facturastock.app.ui.components.FacturaStockPrimaryButton
import com.facturastock.app.ui.components.FacturaStockSecondaryButton
import com.facturastock.app.ui.components.LoadingState
import com.facturastock.app.ui.components.StatusCard
import com.facturastock.app.ui.components.StatusTone
import com.facturastock.app.ui.theme.FacturaStockDesign

@Composable
fun OcrRoute(
    onOpenReview: (DraftId) -> Unit,
    onOpenManualReview: (DraftId) -> Unit,
    onOpenMatching: (DraftId) -> Unit = {},
    onOpenProducts: (createdCount: Int, existingCount: Int, skippedCount: Int) -> Unit,
    onCancelled: () -> Unit,
    onBack: () -> Unit,
    onCloseInvalidRoute: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OcrViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    CollectUiEffects(viewModel.effects) { effect ->
        handleOcrEffect(
            effect = effect,
            onOpenReview = onOpenReview,
            onOpenManualReview = onOpenManualReview,
            onOpenMatching = onOpenMatching,
            onOpenProducts = onOpenProducts,
            onCancelled = onCancelled,
            onBack = onBack,
            onCloseInvalidRoute = onCloseInvalidRoute,
        )
    }

    OcrScreen(
        state = state,
        onAction = viewModel::onAction,
        modifier = modifier,
    )
}

internal fun handleOcrEffect(
    effect: OcrContract.Effect,
    onOpenReview: (DraftId) -> Unit,
    onOpenManualReview: (DraftId) -> Unit,
    onOpenMatching: (DraftId) -> Unit = {},
    onOpenProducts: (createdCount: Int, existingCount: Int, skippedCount: Int) -> Unit,
    onCancelled: () -> Unit,
    onBack: () -> Unit,
    onCloseInvalidRoute: () -> Unit,
) {
    when (effect) {
        is OcrContract.Effect.OpenReview -> onOpenReview(effect.draftId)
        is OcrContract.Effect.OpenManualReview -> onOpenManualReview(effect.draftId)
        is OcrContract.Effect.OpenMatching -> onOpenMatching(effect.draftId)
        is OcrContract.Effect.OpenProducts -> onOpenProducts(
            effect.createdCount,
            effect.existingCount,
            effect.skippedCount,
        )
        OcrContract.Effect.Cancelled -> onCancelled()
        OcrContract.Effect.Back -> onBack()
        OcrContract.Effect.CloseInvalidRoute -> onCloseInvalidRoute()
    }
}

/** Pantalla OCR sin porcentajes: cada texto representa una fase real informada por el dominio. */
@Composable
fun OcrScreen(
    state: OcrContract.State,
    onAction: (OcrContract.Action) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FacturaStockDesign.spacing
    val stage = state.stage ?: InvoiceOcrStage.PREPARING
    val stageMessage = stringResource(stage.messageRes())

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag(OcrTestTags.SCREEN),
        contentPadding = PaddingValues(spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        item {
            Text(
                text = stringResource(R.string.purchase_processing_title),
                modifier = Modifier.semantics { heading() },
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.headlineLarge,
            )
        }

        when (state.failure) {
            OcrContract.Failure.INVALID_DRAFT_ID -> {
                item {
                    StatusCard(
                        statusLabel = stringResource(R.string.ocr_error_status),
                        title = stringResource(R.string.ocr_invalid_route_title),
                        message = stringResource(R.string.ocr_invalid_route_message),
                        tone = StatusTone.ERROR,
                        iconRes = R.drawable.ic_warning,
                        modifier = Modifier.testTag(OcrTestTags.ERROR),
                    )
                }
            }

            OcrContract.Failure.INTERRUPTED -> {
                item {
                    StatusCard(
                        statusLabel = stringResource(R.string.ocr_interrupted_status),
                        title = stringResource(R.string.ocr_interrupted_title),
                        message = stringResource(R.string.ocr_interrupted_message),
                        tone = StatusTone.WARNING,
                        iconRes = R.drawable.ic_warning,
                        modifier = Modifier.testTag(OcrTestTags.ERROR),
                    )
                }
                item {
                    FacturaStockPrimaryButton(
                        text = stringResource(
                            if (state.isRecoveringInterruptedOcr) {
                                R.string.ocr_recovering
                            } else {
                                R.string.action_retry
                            },
                        ),
                        onClick = { onAction(OcrContract.Action.Retry) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(OcrTestTags.RETRY),
                        enabled = !state.isRecoveringInterruptedOcr,
                    )
                }
            }

            OcrContract.Failure.RECOGNITION_FAILED -> {
                item {
                    StatusCard(
                        statusLabel = stringResource(R.string.ocr_error_status),
                        title = stringResource(R.string.ocr_recognition_error_title),
                        message = stringResource(
                            if (state.manualEntryFailed) {
                                R.string.ocr_manual_entry_error_message
                            } else {
                                R.string.ocr_recognition_error_message
                            },
                        ),
                        tone = StatusTone.ERROR,
                        iconRes = R.drawable.ic_warning,
                        modifier = Modifier.testTag(OcrTestTags.ERROR),
                    )
                }
                item {
                    FacturaStockPrimaryButton(
                        text = stringResource(R.string.action_retry),
                        onClick = { onAction(OcrContract.Action.Retry) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(OcrTestTags.RETRY),
                        enabled = !state.isEnteringManually,
                    )
                }
            }

            OcrContract.Failure.PARSING_FAILED -> {
                item {
                    StatusCard(
                        statusLabel = stringResource(R.string.ocr_parse_error_status),
                        title = stringResource(R.string.ocr_parse_error_title),
                        message = stringResource(
                            if (state.manualEntryFailed) {
                                R.string.ocr_manual_entry_error_message
                            } else {
                                R.string.ocr_parse_error_message
                            },
                        ),
                        tone = StatusTone.ERROR,
                        iconRes = R.drawable.ic_warning,
                        modifier = Modifier.testTag(OcrTestTags.ERROR),
                    )
                }
                item {
                    FacturaStockPrimaryButton(
                        text = stringResource(R.string.action_retry),
                        onClick = { onAction(OcrContract.Action.Retry) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(OcrTestTags.RETRY),
                        enabled = !state.isEnteringManually,
                    )
                }
            }

            OcrContract.Failure.NO_PRODUCTS_FOUND -> {
                item {
                    StatusCard(
                        statusLabel = stringResource(R.string.ocr_no_products_status),
                        title = stringResource(R.string.ocr_no_products_title),
                        message = stringResource(R.string.ocr_no_products_message),
                        tone = StatusTone.WARNING,
                        iconRes = R.drawable.ic_warning,
                        modifier = Modifier
                            .testTag(OcrTestTags.ERROR),
                    )
                }
                item {
                    FacturaStockPrimaryButton(
                        text = stringResource(R.string.action_retry),
                        onClick = { onAction(OcrContract.Action.Retry) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(OcrTestTags.RETRY),
                    )
                }
                item {
                    FacturaStockSecondaryButton(
                        text = stringResource(R.string.ocr_action_continue_manual),
                        onClick = { onAction(OcrContract.Action.ContinueToMatching) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            OcrContract.Failure.PRODUCT_SAVE_FAILED -> {
                item {
                    StatusCard(
                        statusLabel = stringResource(R.string.ocr_product_save_error_status),
                        title = stringResource(R.string.ocr_product_save_error_title),
                        message = stringResource(R.string.ocr_product_save_error_message),
                        tone = StatusTone.ERROR,
                        iconRes = R.drawable.ic_warning,
                        modifier = Modifier.testTag(OcrTestTags.ERROR),
                    )
                }
                item {
                    FacturaStockPrimaryButton(
                        text = stringResource(R.string.action_retry),
                        onClick = { onAction(OcrContract.Action.Retry) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(OcrTestTags.RETRY),
                    )
                }
            }

            null -> {
                if (!state.isRunning && !state.isSavingProducts && !state.isCancelling &&
                    !state.isEnteringManually && !state.isRecoveringInterruptedOcr &&
                    state.completedPageCount > 0
                ) {
                    item {
                        Text(text = stringResource(R.string.matching_ocr_complete))
                    }
                    item {
                        FacturaStockPrimaryButton(
                            text = stringResource(R.string.matching_resume_review),
                            onClick = { onAction(OcrContract.Action.ContinueToMatching) },
                            modifier = Modifier.fillMaxWidth().testTag(OcrTestTags.CONTINUE_REVIEW),
                        )
                    }
                } else {
                item {
                    Box(modifier = Modifier.testTag(OcrTestTags.PROGRESS)) {
                        LoadingState(
                            message = when {
                                state.isSavingProducts -> {
                                    stringResource(R.string.ocr_saving_products)
                                }

                                state.isCancelling -> stringResource(R.string.ocr_cancelling)
                                else -> stageMessage
                            },
                        )
                    }
                }
                item {
                    Text(
                        text = stringResource(R.string.purchase_processing_message),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if ((state.isRunning && !state.isSavingProducts) || state.isCancelling) {
                    item {
                        FacturaStockPrimaryButton(
                            text = if (state.isCancelling) {
                                stringResource(R.string.ocr_cancelling)
                            } else {
                                stringResource(R.string.action_cancel)
                            },
                            onClick = { onAction(OcrContract.Action.Cancel) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(OcrTestTags.CANCEL),
                            enabled = state.isRunning &&
                                !state.isSavingProducts &&
                                !state.isCancelling,
                        )
                    }
                }
                }
            }
        }

        if (
            !state.isRunning &&
            !state.isSavingProducts &&
            !state.isCancelling &&
            !state.isEnteringManually &&
            !state.isRecoveringInterruptedOcr
        ) {
            item {
                FacturaStockSecondaryButton(
                    text = stringResource(
                        if (state.failure == OcrContract.Failure.INVALID_DRAFT_ID) {
                            R.string.action_previous_step
                        } else {
                            R.string.action_retake_invoice_photo
                        },
                    ),
                    onClick = { onAction(OcrContract.Action.BackSelected) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(OcrTestTags.BACK),
                )
            }
        }
    }
}

object OcrTestTags {
    const val SCREEN = "ocr_screen"
    const val PROGRESS = "ocr_progress"
    const val CANCEL = "ocr_cancel"
    const val RETRY = "ocr_retry"
    const val ENTER_MANUALLY = "ocr_enter_manually"
    const val BACK = "ocr_back"
    const val ERROR = "ocr_error"
    const val CONTINUE_REVIEW = "ocr_continue_review"
}

@StringRes
private fun InvoiceOcrStage.messageRes(): Int = when (this) {
    InvoiceOcrStage.PREPARING -> R.string.ocr_stage_preparing
    InvoiceOcrStage.READING -> R.string.ocr_stage_reading
    InvoiceOcrStage.MERGING_PAGES -> R.string.ocr_stage_merging_pages
}
