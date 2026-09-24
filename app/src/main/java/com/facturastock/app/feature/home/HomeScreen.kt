package com.facturastock.app.feature.home

import org.jetbrains.compose.resources.StringResource

import com.facturastock.app.resources.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.HomeDashboardSnapshot
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.ui.components.FacturaStockPrimaryButton
import com.facturastock.app.ui.components.FacturaStockSecondaryButton
import com.facturastock.app.ui.components.LoadingState
import com.facturastock.app.ui.components.StatusTone
import com.facturastock.app.ui.format.formatForDisplay
import com.facturastock.app.ui.theme.FacturaStockDesign

/**
 * Inicio: dashboard operativo para registrar movimientos, consultar la gestión y retomar
 * borradores. Es stateless — el estado de diálogos llega por parámetros y toda interacción se
 * notifica por callbacks —, por lo que sirve igual a la rama con ViewModel ([HomeRoute]) que a
 * la de tests de navegación.
 */
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    dashboard: HomeDashboardSnapshot? = null,
    drafts: List<HomeContract.HomeDraftItem> = emptyList(),
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    draftIdPendingDeletion: DraftId? = null,
    draftIdPendingOcrChoice: DraftId? = null,
    isRecoveringOcr: Boolean = false,
    isCreatingDraft: Boolean = false,
    onScanInvoice: () -> Unit = {},
    onOpenSales: () -> Unit = {},
    onOpenProducts: () -> Unit = {},
    onOpenPurchases: () -> Unit = {},
    onOpenDebtors: () -> Unit = {},
    onOpenInventory: () -> Unit = {},
    onDraftSelected: (DraftId) -> Unit = {},
    onDeleteRequested: (DraftId) -> Unit = {},
    onDeleteConfirmed: () -> Unit = {},
    onDeleteDismissed: () -> Unit = {},
    onOcrResumeSelected: () -> Unit = {},
    onOcrRetrySelected: () -> Unit = {},
    onOcrChoiceDismissed: () -> Unit = {},
) {
    val spacing = FacturaStockDesign.spacing
    val showDraftSection = drafts.isNotEmpty()

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val dashboardSingleColumn = homeDashboardUsesSingleColumn(maxWidth)
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag(HomeTestTags.DRAFTS_LIST),
            contentPadding = PaddingValues(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            homeDashboardItems(
                dashboard = dashboard,
                singleColumn = dashboardSingleColumn,
                isCreatingDraft = isCreatingDraft,
                onScanInvoice = onScanInvoice,
                onNewSale = onOpenSales,
                onOpenProducts = onOpenProducts,
                onOpenPurchases = onOpenPurchases,
                onOpenDebtors = onOpenDebtors,
                onOpenInventory = onOpenInventory,
            )
            if (showDraftSection) {
                item(key = "home_drafts_header", contentType = "drafts_header") {
                    HomeSectionHeader(text = stringResource(Res.string.home_drafts_title))
                }
                items(
                    items = drafts,
                    key = { item -> item.draft.draftId.value },
                    contentType = { "home_draft" },
                ) { item ->
                    HomeDraftCard(
                        item = item,
                        onClick = { onDraftSelected(item.draft.draftId) },
                        onDeleteClick = { onDeleteRequested(item.draft.draftId) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .testTag(HomeTestTags.SNACKBAR),
        )
    }

    if (draftIdPendingDeletion != null) {
        HomeDeleteDialog(
            onConfirm = onDeleteConfirmed,
            onDismiss = onDeleteDismissed,
        )
    }
    if (draftIdPendingOcrChoice != null) {
        HomeOcrChoiceDialog(
            isRecovering = isRecoveringOcr,
            onResume = onOcrResumeSelected,
            onRetry = onOcrRetrySelected,
            onDismiss = onOcrChoiceDismissed,
        )
    }
}

@Composable
private fun HomeSectionHeader(
    text: String,
) {
    Text(
        text = text,
        modifier = Modifier.semantics { heading() },
        color = MaterialTheme.colorScheme.onBackground,
        style = MaterialTheme.typography.titleMedium,
    )
}

@Composable
private fun HomeDraftCard(
    item: HomeContract.HomeDraftItem,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FacturaStockDesign.spacing
    val draft = item.draft
    val supplierLabel = homeDraftSupplierLabel(item)
    val documentLabel = homeDraftDocumentLabel(draft)

    Card(
        onClick = onClick,
        modifier = modifier
            .semantics { role = Role.Button }
            .testTag(HomeTestTags.draftCard(draft.draftId)),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(spacing.xxs),
                ) {
                    Text(
                        text = supplierLabel,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = documentLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(modifier = Modifier.width(spacing.sm))
                IconButton(
                    onClick = onDeleteClick,
                    modifier = Modifier
                        .size(spacing.minimumTouchTarget)
                        .testTag(HomeTestTags.draftDelete(draft.draftId)),
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_delete),
                        contentDescription = stringResource(
                            Res.string.home_delete_draft_description_context,
                            supplierLabel,
                            documentLabel,
                        ),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(spacing.xxs),
            ) {
                HomeDraftStatusChip(status = draft.status)
                Text(
                    text = homeDraftTotalLabel(draft),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            Text(
                text = stringResource(
                    Res.string.home_draft_continue,
                    stringResource(draftNextActionLabelRes(draft.status)),
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun HomeDraftStatusChip(
    status: DraftStatus,
    modifier: Modifier = Modifier,
) {
    val spacing = FacturaStockDesign.spacing
    val material = MaterialTheme.colorScheme
    val semantic = FacturaStockDesign.semanticColors
    val (container, content) = when (draftStatusTone(status)) {
        StatusTone.NEUTRAL -> material.surfaceVariant to material.onSurfaceVariant
        StatusTone.INFO -> semantic.infoContainer to semantic.onInfoContainer
        StatusTone.SUCCESS -> semantic.successContainer to semantic.onSuccessContainer
        StatusTone.WARNING -> semantic.warningContainer to semantic.onWarningContainer
        StatusTone.ERROR -> material.errorContainer to material.onErrorContainer
    }

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraSmall,
        color = container,
        contentColor = content,
    ) {
        Text(
            text = stringResource(draftStatusLabelRes(status)),
            modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xs),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

private fun draftStatusLabelRes(status: DraftStatus): StringResource = when (status) {
    DraftStatus.CREATED -> Res.string.home_status_created
    DraftStatus.CAPTURED -> Res.string.home_status_captured
    DraftStatus.OCR_PROCESSING -> Res.string.home_status_ocr_interrupted
    DraftStatus.OCR_READY -> Res.string.home_status_ocr_ready
    DraftStatus.NEEDS_REVIEW -> Res.string.home_status_needs_review
    DraftStatus.READY_TO_POST -> Res.string.home_status_ready_to_post
    DraftStatus.COMMITTED -> Res.string.home_status_committed
    DraftStatus.ERROR -> Res.string.home_status_error
}

private fun draftNextActionLabelRes(status: DraftStatus): StringResource = when (status) {
    DraftStatus.CREATED -> Res.string.home_next_capture
    DraftStatus.CAPTURED -> Res.string.home_next_start_ocr
    DraftStatus.OCR_PROCESSING -> Res.string.home_next_resume_ocr
    DraftStatus.OCR_READY -> Res.string.home_next_review_header
    DraftStatus.NEEDS_REVIEW -> Res.string.home_next_review_products
    DraftStatus.READY_TO_POST -> Res.string.home_next_confirm_purchase
    DraftStatus.COMMITTED -> Res.string.home_next_open_purchase
    DraftStatus.ERROR -> Res.string.home_next_review_error
}

private fun draftStatusTone(status: DraftStatus): StatusTone = when (status) {
    DraftStatus.CREATED -> StatusTone.NEUTRAL
    DraftStatus.CAPTURED -> StatusTone.INFO
    DraftStatus.OCR_PROCESSING -> StatusTone.WARNING
    DraftStatus.OCR_READY -> StatusTone.INFO
    DraftStatus.NEEDS_REVIEW -> StatusTone.WARNING
    DraftStatus.READY_TO_POST,
    DraftStatus.COMMITTED,
    -> StatusTone.SUCCESS
    DraftStatus.ERROR -> StatusTone.ERROR
}

/** Misma estructura visual que `FacturaStockDialog`, con test tags en el marco y botones. */
@Composable
private fun HomeDialogFrame(
    title: String,
    message: String,
    testTag: String,
    onDismiss: () -> Unit,
    buttons: @Composable ColumnScope.() -> Unit,
) {
    val spacing = FacturaStockDesign.spacing

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing)
                .padding(spacing.lg),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = spacing.dialogMaxWidth)
                    .fillMaxWidth()
                    .semantics {
                        paneTitle = title
                        isTraversalGroup = true
                    }
                    .testTag(testTag),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = spacing.xs,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(spacing.lg),
                ) {
                    Text(
                        text = title,
                        modifier = Modifier.semantics { heading() },
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Spacer(modifier = Modifier.height(spacing.md))
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(modifier = Modifier.height(spacing.lg))
                    buttons()
                }
            }
        }
    }
}

@Composable
private fun HomeDeleteDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = FacturaStockDesign.spacing

    HomeDialogFrame(
        title = stringResource(Res.string.home_delete_dialog_title),
        message = stringResource(Res.string.home_delete_dialog_message),
        testTag = HomeTestTags.DELETE_DIALOG,
        onDismiss = onDismiss,
    ) {
        FacturaStockPrimaryButton(
            text = stringResource(Res.string.action_delete_draft),
            onClick = onConfirm,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(HomeTestTags.DELETE_DIALOG_CONFIRM),
        )
        Spacer(modifier = Modifier.height(spacing.xs))
        FacturaStockSecondaryButton(
            text = stringResource(Res.string.action_cancel),
            onClick = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(HomeTestTags.DELETE_DIALOG_CANCEL),
        )
    }
}

@Composable
private fun HomeOcrChoiceDialog(
    isRecovering: Boolean,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = FacturaStockDesign.spacing

    HomeDialogFrame(
        title = stringResource(Res.string.home_ocr_dialog_title),
        message = stringResource(Res.string.home_ocr_dialog_message),
        testTag = HomeTestTags.OCR_DIALOG,
        onDismiss = { if (!isRecovering) onDismiss() },
    ) {
        if (isRecovering) {
            LoadingState(message = stringResource(Res.string.ocr_recovering))
            Spacer(modifier = Modifier.height(spacing.xs))
        }
        FacturaStockPrimaryButton(
            text = stringResource(Res.string.action_resume_ocr),
            onClick = onResume,
            enabled = !isRecovering,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(HomeTestTags.OCR_DIALOG_RESUME),
        )
        Spacer(modifier = Modifier.height(spacing.xs))
        FacturaStockSecondaryButton(
            text = stringResource(Res.string.action_retry_ocr),
            onClick = onRetry,
            enabled = !isRecovering,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(HomeTestTags.OCR_DIALOG_RETRY),
        )
        Spacer(modifier = Modifier.height(spacing.xs))
        FacturaStockSecondaryButton(
            text = stringResource(Res.string.action_cancel),
            onClick = onDismiss,
            enabled = !isRecovering,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(HomeTestTags.OCR_DIALOG_CANCEL),
        )
    }
}

@Composable
private fun homeDraftSupplierLabel(item: HomeContract.HomeDraftItem): String =
    item.supplierName
        ?: item.draft.supplierRucNormalized
        ?: item.draft.supplierRucRaw
        ?: stringResource(Res.string.home_supplier_pending)

@Composable
private fun homeDraftDocumentLabel(draft: InvoiceDraft): String =
    draft.documentNumberNormalized
        ?: draft.documentNumberRaw
        ?: stringResource(Res.string.home_document_pending)

@Composable
private fun homeDraftTotalLabel(draft: InvoiceDraft): String =
    draft.total?.let { total ->
        stringResource(Res.string.home_total, total.formatForDisplay())
    } ?: stringResource(Res.string.home_total_pending)
