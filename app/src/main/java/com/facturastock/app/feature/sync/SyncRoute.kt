package com.facturastock.app.feature.sync

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.facturastock.app.R
import com.facturastock.app.domain.model.AccountSession
import com.facturastock.app.domain.model.AmbiguousRemotePurchase
import com.facturastock.app.domain.model.AmbiguousRemoteProduct
import com.facturastock.app.domain.model.BalanceDifference
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.OutboxOperationStatus
import com.facturastock.app.domain.model.PurchaseReadSummary
import com.facturastock.app.domain.model.ReconciliationReport
import com.facturastock.app.domain.model.RemotePurchaseChange
import com.facturastock.app.domain.model.RemotePurchaseDescription
import com.facturastock.app.domain.repository.OutboxOperationView
import com.facturastock.app.domain.repository.CatalogConflictSide
import com.facturastock.app.feature.common.CollectUiEffects
import com.facturastock.app.ui.components.FacturaStockDialog
import com.facturastock.app.ui.components.FacturaStockPrimaryButton
import com.facturastock.app.ui.components.FacturaStockSecondaryButton
import com.facturastock.app.ui.components.LoadingState
import com.facturastock.app.ui.components.RecoverableError
import com.facturastock.app.ui.components.StatusCard
import com.facturastock.app.ui.components.StatusTone
import com.facturastock.app.ui.format.formatForDisplay
import com.facturastock.app.ui.theme.FacturaStockDesign
import java.time.Instant
import java.time.LocalDate

@Composable
fun SyncRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SyncViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val initialLoadFailure = state.initialLoadFailure

    CollectUiEffects(viewModel.effects) { effect ->
        when (effect) {
            SyncContract.Effect.Back -> onBack()
        }
    }

    if (state.session == null && initialLoadFailure != null) {
        RecoverableError(
            title = stringResource(R.string.feature_load_error_title),
            message = stringResource(syncErrorMessageRes(initialLoadFailure)),
            actionLabel = stringResource(R.string.action_retry),
            onAction = { viewModel.onAction(SyncContract.Action.RetryInitialLoad) },
            modifier = modifier.fillMaxSize(),
        )
    } else if (state.session == null) {
        LoadingState(
            message = stringResource(R.string.feature_loading_message),
            modifier = modifier.fillMaxSize(),
        )
    } else {
        SyncScreen(
            state = state,
            onAction = viewModel::onAction,
            modifier = modifier,
        )
    }
}

@Composable
fun SyncScreen(
    state: SyncContract.State,
    onAction: (SyncContract.Action) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FacturaStockDesign.spacing

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        state.feedback?.let { feedback ->
            StatusCard(
                statusLabel = stringResource(R.string.sync_status_label),
                title = stringResource(R.string.sync_feedback_title),
                message = stringResource(
                    when (feedback) {
                        SyncContract.Feedback.OPERATION_REQUEUED ->
                            R.string.sync_feedback_requeued

                        SyncContract.Feedback.CONFLICT_RESOLVED ->
                            R.string.sync_feedback_conflict_resolved

                        SyncContract.Feedback.REVIEW_RECORDED ->
                            R.string.sync_review_recorded
                    },
                ),
                tone = StatusTone.SUCCESS,
                iconRes = R.drawable.ic_check_circle,
                announcementMode = LiveRegionMode.Polite,
            )
        }
        state.failure?.let { failure ->
            StatusCard(
                statusLabel = stringResource(R.string.sync_status_label),
                title = stringResource(R.string.sync_error_title),
                message = stringResource(syncErrorMessageRes(failure)),
                tone = StatusTone.ERROR,
                iconRes = R.drawable.ic_warning,
            )
        }

        StatusSection(state = state)

        if (state.cloudLinked) {
            OutboxSection(state = state, onAction = onAction)
            DocumentOperationsSection(state = state, onAction = onAction)
            ConflictsSection(state = state, onAction = onAction)
            SyncSection(state = state, onAction = onAction)
        }

        Spacer(modifier = Modifier.height(spacing.sm))
        FacturaStockSecondaryButton(
            text = stringResource(R.string.sync_back_action),
            onClick = { onAction(SyncContract.Action.BackSelected) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(spacing.md))
    }

    state.keepRemoteCandidate?.let { candidate ->
        val isCatalog = candidate.entityType != "PURCHASE"
        FacturaStockDialog(
            title = stringResource(
                if (isCatalog) {
                    R.string.sync_apply_remote_catalog_dialog_title
                } else {
                    R.string.sync_keep_remote_dialog_title
                },
            ),
            message = stringResource(
                if (isCatalog) {
                    R.string.sync_apply_remote_catalog_dialog_message
                } else {
                    R.string.sync_keep_remote_dialog_message
                },
            ),
            confirmLabel = stringResource(
                if (isCatalog) {
                    R.string.sync_apply_remote_catalog_action
                } else {
                    R.string.sync_keep_remote_action
                },
            ),
            dismissLabel = stringResource(R.string.action_cancel),
            onConfirm = { onAction(SyncContract.Action.KeepRemoteConfirmed) },
            onDismiss = { onAction(SyncContract.Action.DismissDialogs) },
        )
    }
}

@Composable
private fun StatusSection(state: SyncContract.State) {
    val session = state.session
    when {
        !state.remoteLedgerAvailable -> UnavailableCard(
            messageRes = R.string.sync_unavailable_flavor_message,
        )

        session !is AccountSession.Active -> UnavailableCard(
            messageRes = R.string.sync_unavailable_session_message,
        )

        session.link == null -> UnavailableCard(
            messageRes = R.string.sync_unavailable_link_message,
        )

        !state.backupEnabled -> UnavailableCard(
            messageRes = R.string.sync_backup_paused_message,
        )

        else -> StatusCard(
            statusLabel = stringResource(R.string.sync_status_label),
            title = stringResource(R.string.sync_status_ready_title),
            message = stringResource(R.string.sync_status_ready_message, session.email),
            tone = StatusTone.SUCCESS,
            iconRes = R.drawable.ic_check_circle,
        )
    }
}

@Composable
private fun UnavailableCard(@StringRes messageRes: Int) {
    StatusCard(
        statusLabel = stringResource(R.string.sync_status_label),
        title = stringResource(R.string.sync_unavailable_title),
        message = stringResource(messageRes),
        tone = StatusTone.INFO,
        iconRes = R.drawable.ic_info,
    )
}

@Composable
private fun OutboxSection(
    state: SyncContract.State,
    onAction: (SyncContract.Action) -> Unit,
) {
    SectionHeader(titleRes = R.string.sync_section_outbox)
    Text(
        text = stringResource(R.string.sync_outbox_image_notice),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyLarge,
    )
    if (state.pendingOperations.isEmpty()) {
        Text(
            text = stringResource(R.string.sync_outbox_empty),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
    } else {
        state.pendingOperations.forEach { operation ->
            OutboxOperationItem(
                operation = operation,
                working = state.workingOperationId != null,
                onAction = onAction,
            )
        }
    }
}

@Composable
private fun DocumentOperationsSection(
    state: SyncContract.State,
    onAction: (SyncContract.Action) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SyncTestTags.DOCUMENT_SECTION),
    ) {
        SectionHeader(titleRes = R.string.sync_section_documents)
        Text(
            text = stringResource(
                if (state.backupEnabled && state.documentBackupEnabled) {
                    R.string.sync_document_backup_active_notice
                } else {
                    R.string.sync_document_backup_paused_notice
                },
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
        if (state.documentOperations.isEmpty()) {
            Text(
                text = stringResource(R.string.sync_document_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            state.documentOperations.forEachIndexed { index, document ->
                DocumentOperationItem(
                    index = index,
                    document = document,
                    working = state.workingOperationId != null,
                    onAction = onAction,
                )
            }
        }
    }
}

@Composable
private fun DocumentOperationItem(
    index: Int,
    document: SyncContract.DocumentOperation,
    working: Boolean,
    onAction: (SyncContract.Action) -> Unit,
) {
    val operation = document.operation
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SyncTestTags.documentOperation(index)),
    ) {
        Text(
            text = stringResource(R.string.sync_document_item_title, index + 1),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(operationTypeLabelRes(operation.operationType)),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(
                R.string.sync_document_state_line,
                stringResource(documentSyncStateLabelRes(document.state)),
                operation.attemptCount,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(
                R.string.sync_outbox_last_attempt,
                operation.updatedAt.formatForDisplay(),
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
        operation.lastError?.let { lastError ->
            Text(
                text = stringResource(
                    R.string.sync_outbox_last_error,
                    stringResource(outboxErrorMessageRes(lastError)),
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        if (operation.status == OutboxOperationStatus.FAILED) {
            FacturaStockSecondaryButton(
                text = stringResource(R.string.sync_retry_send_action),
                onClick = { onAction(SyncContract.Action.RetryFailedOperation(operation)) },
                enabled = !working,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun OutboxOperationItem(
    operation: OutboxOperationView,
    working: Boolean,
    onAction: (SyncContract.Action) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(operationTypeLabelRes(operation.operationType)),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(
                R.string.sync_outbox_operation_line,
                stringResource(outboxStatusLabelRes(operation.status)),
                operation.attemptCount,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(
                R.string.sync_outbox_last_attempt,
                operation.updatedAt.formatForDisplay(),
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
        operation.nextAttemptAt?.let { nextAttemptAt ->
            Text(
                text = stringResource(
                    R.string.sync_outbox_next_attempt,
                    nextAttemptAt.formatForDisplay(),
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        operation.lastError?.let { lastError ->
            Text(
                text = stringResource(
                    R.string.sync_outbox_last_error,
                    stringResource(outboxErrorMessageRes(lastError)),
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        if (operation.status == OutboxOperationStatus.FAILED) {
            FacturaStockSecondaryButton(
                text = stringResource(R.string.sync_retry_send_action),
                onClick = { onAction(SyncContract.Action.RetryFailedOperation(operation)) },
                enabled = !working,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(SyncTestTags.RETRY_OPERATION_ACTION),
            )
        }
    }
}

@Composable
private fun ConflictsSection(
    state: SyncContract.State,
    onAction: (SyncContract.Action) -> Unit,
) {
    if (state.conflictOperations.isEmpty()) return
    SectionHeader(titleRes = R.string.sync_section_conflicts)
    state.conflictOperations.forEach { operation ->
        ConflictItem(
            operation = operation,
            localPurchase = state.purchases.firstOrNull { it.purchaseId == operation.purchaseId },
            remoteState = state.remoteDescriptions[operation.operationId],
            working = state.workingOperationId != null,
            onAction = onAction,
        )
    }
}

@Composable
private fun ConflictItem(
    operation: OutboxOperationView,
    localPurchase: PurchaseReadSummary?,
    remoteState: SyncContract.RemoteDescriptionState?,
    working: Boolean,
    onAction: (SyncContract.Action) -> Unit,
) {
    val spacing = FacturaStockDesign.spacing

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(operationTypeLabelRes(operation.operationType)),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(
                R.string.sync_outbox_last_attempt,
                operation.updatedAt.formatForDisplay(),
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
        operation.lastError?.let { errorCode ->
            Text(
                text = stringResource(
                    R.string.sync_outbox_last_error,
                    stringResource(outboxErrorMessageRes(errorCode)),
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        val catalogComparison = operation.catalogConflictComparison
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.sync_conflict_local_label),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                )
                if (catalogComparison != null) {
                    CatalogConflictLines(catalogComparison.local)
                } else if (localPurchase == null) {
                    Text(
                        text = stringResource(R.string.sync_conflict_local_missing),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                } else {
                    LocalPurchaseLines(localPurchase)
                }
            }
            Spacer(modifier = Modifier.width(spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.sync_conflict_remote_label),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                )
                if (catalogComparison != null) {
                    CatalogConflictLines(catalogComparison.remote)
                } else {
                    RemotePurchaseLines(
                        remoteState = remoteState,
                        onRetry = {
                            onAction(SyncContract.Action.RemoteDescriptionRetry(operation))
                        },
                    )
                }
            }
        }
        FacturaStockSecondaryButton(
            text = stringResource(
                if (catalogComparison != null) {
                    R.string.sync_apply_remote_catalog_action
                } else {
                    R.string.sync_keep_remote_action
                },
            ),
            onClick = { onAction(SyncContract.Action.KeepRemoteRequested(operation)) },
            enabled = !working,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SyncTestTags.KEEP_REMOTE_ACTION),
        )
        FacturaStockSecondaryButton(
            text = stringResource(
                if (catalogComparison != null) {
                    R.string.sync_keep_local_catalog_action
                } else {
                    R.string.sync_retry_send_action
                },
            ),
            onClick = { onAction(SyncContract.Action.RetryConflictOperation(operation)) },
            enabled = !working,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SyncTestTags.RETRY_CONFLICT_ACTION),
        )
    }
}

@Composable
private fun CatalogConflictLines(side: CatalogConflictSide?) {
    if (side == null) {
        Text(
            text = stringResource(R.string.sync_catalog_conflict_unavailable),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
        )
        return
    }
    Text(
        text = side.displayName,
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyLarge,
    )
    side.semanticIdentifier?.let { identifier ->
        Text(
            text = stringResource(R.string.sync_catalog_identifier, identifier),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
    Text(
        text = stringResource(
            R.string.sync_catalog_version_status,
            side.version,
            stringResource(
                if (side.status == "ACTIVE") {
                    R.string.catalog_status_active
                } else {
                    R.string.catalog_status_archived
                },
            ),
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyLarge,
    )
    side.changedAt?.let { changedAt ->
        Text(
            text = stringResource(
                R.string.sync_catalog_changed_at,
                changedAt.formatForDisplay(),
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
    Text(
        text = stringResource(
            R.string.sync_catalog_origin,
            stringResource(
                if (side.origin == "LOCAL") {
                    R.string.sync_catalog_origin_local
                } else {
                    R.string.sync_catalog_origin_cloud
                },
            ),
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun LocalPurchaseLines(purchase: PurchaseReadSummary) {
    Text(
        text = stringResource(
            R.string.sync_purchase_document_line,
            stringResource(remoteDocumentTypeLabelRes(purchase.documentType.name)),
            purchase.canonicalDocumentNumber,
        ),
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        text = stringResource(
            R.string.sync_purchase_date_line,
            purchase.issueDate.formatForDisplay(),
        ),
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        text = stringResource(R.string.sync_purchase_supplier_line, purchase.supplierLegalName),
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        text = stringResource(R.string.sync_purchase_total_line, purchase.total.formatForDisplay()),
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        text = stringResource(
            R.string.sync_purchase_status_line,
            stringResource(syncPurchaseStatusLabelRes(purchase.status)),
        ),
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun RemotePurchaseLines(
    remoteState: SyncContract.RemoteDescriptionState?,
    onRetry: () -> Unit,
) {
    when (remoteState) {
        null,
        SyncContract.RemoteDescriptionState.Loading,
        -> Text(
            text = stringResource(R.string.sync_conflict_remote_loading),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )

        SyncContract.RemoteDescriptionState.Unavailable -> {
            Text(
                text = stringResource(R.string.sync_conflict_remote_unavailable),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
            )
            FacturaStockSecondaryButton(
                text = stringResource(R.string.sync_conflict_retry_load_action),
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        is SyncContract.RemoteDescriptionState.Loaded -> {
            val description = remoteState.description
            if (description == null) {
                Text(
                    text = stringResource(R.string.sync_conflict_remote_missing),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else {
                RemotePurchaseDescriptionLines(description)
            }
        }
    }
}

@Composable
private fun RemotePurchaseDescriptionLines(description: RemotePurchaseDescription) {
    Text(
        text = stringResource(
            R.string.sync_purchase_document_line,
            stringResource(remoteDocumentTypeLabelRes(description.documentType)),
            "${description.documentSeries}-${description.documentNumber}",
        ),
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        text = stringResource(
            R.string.sync_purchase_date_line,
            formatRemoteIssueDate(description.issueDate),
        ),
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        text = stringResource(R.string.sync_purchase_supplier_line, description.supplierLegalName),
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        text = stringResource(
            R.string.sync_purchase_total_line,
            formatRemoteAmount(description.totalMinorUnits, description.currency),
        ),
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        text = stringResource(
            R.string.sync_purchase_status_line,
            stringResource(syncPurchaseStatusLabelRes(description.status)),
        ),
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyLarge,
    )
    description.syncedAtMillis?.let { syncedAtMillis ->
        Text(
            text = stringResource(
                R.string.sync_remote_synced_line,
                Instant.ofEpochMilli(syncedAtMillis).formatForDisplay(),
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun SyncSection(
    state: SyncContract.State,
    onAction: (SyncContract.Action) -> Unit,
) {
    SectionHeader(titleRes = R.string.sync_section_sync)
    Text(
        text = state.cursor?.let { cursor ->
            stringResource(
                R.string.sync_cursor_value,
                cursor.lastPullAt.formatForDisplay(),
                cursor.seq,
            )
        } ?: stringResource(R.string.sync_cursor_never),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyLarge,
    )
    FacturaStockPrimaryButton(
        text = stringResource(
            if (state.isPulling) R.string.sync_now_working else R.string.sync_now_action,
        ),
        onClick = { onAction(SyncContract.Action.SyncNow) },
        enabled = state.canPull,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SyncTestTags.SYNC_NOW_ACTION),
    )
    state.pullOutcome?.let { outcome ->
        Text(
            text = pluralStringResource(
                R.plurals.sync_pull_result,
                outcome.pulledCount,
                outcome.pulledCount,
                outcome.latestSeq,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
    FacturaStockSecondaryButton(
        text = stringResource(
            if (state.isComparing) R.string.sync_compare_working else R.string.sync_compare_action,
        ),
        onClick = { onAction(SyncContract.Action.CompareWithCloud) },
        enabled = state.canCompare,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SyncTestTags.COMPARE_ACTION),
    )
    state.report?.let { report ->
        ReconciliationReportSection(
            report = report,
            canRecordReview = state.canRecordReview,
            isRecordingReview = state.isRecordingReview,
            onAction = onAction,
        )
    }
    Text(
        text = stringResource(R.string.sync_reconcile_notice),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun ReconciliationReportSection(
    report: ReconciliationReport,
    canRecordReview: Boolean,
    isRecordingReview: Boolean,
    onAction: (SyncContract.Action) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.sync_report_matched, report.matched.size),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(R.string.sync_report_ambiguous, report.ambiguous.size),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(R.string.sync_report_remote_only, report.remoteOnly.size),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(
                R.string.sync_report_balance_differences,
                report.balanceDifferences.size,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(
                R.string.sync_report_unlinked,
                report.unlinkedRemoteProducts.size,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(
                R.string.sync_report_product_ambiguities,
                report.ambiguousRemoteProducts.size,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(R.string.sync_report_compared, report.comparedProductCount),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
        )
        if (report.ambiguous.isNotEmpty()) {
            Text(
                text = stringResource(R.string.sync_report_ambiguous_section),
                modifier = Modifier.semantics { heading() },
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
            )
            report.ambiguous.forEach { ambiguity ->
                AmbiguousRemoteLine(ambiguity)
            }
        }
        if (report.remoteOnly.isNotEmpty()) {
            Text(
                text = stringResource(R.string.sync_report_remote_only_section),
                modifier = Modifier.semantics { heading() },
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
            )
            report.remoteOnly.forEach { change ->
                RemoteOnlyLine(change)
            }
        }
        if (report.balanceDifferences.isNotEmpty()) {
            Text(
                text = stringResource(R.string.sync_report_balance_section),
                modifier = Modifier.semantics { heading() },
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
            )
            report.balanceDifferences.forEach { difference ->
                BalanceDifferenceLine(difference)
            }
        }
        if (report.ambiguousRemoteProducts.isNotEmpty()) {
            Text(
                text = stringResource(R.string.sync_report_product_ambiguities_section),
                modifier = Modifier.semantics { heading() },
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
            )
            report.ambiguousRemoteProducts.forEach { ambiguity ->
                AmbiguousRemoteProductLine(ambiguity)
            }
        }
        FacturaStockSecondaryButton(
            text = stringResource(
                if (isRecordingReview) {
                    R.string.sync_record_review_working
                } else {
                    R.string.sync_record_review_action
                },
            ),
            onClick = { onAction(SyncContract.Action.RecordReview) },
            enabled = canRecordReview,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SyncTestTags.RECORD_REVIEW_ACTION),
        )
    }
}

@Composable
private fun AmbiguousRemoteProductLine(ambiguity: AmbiguousRemoteProduct) {
    Text(
        text = stringResource(
            R.string.sync_report_product_ambiguity_line,
            ambiguity.productName ?: stringResource(R.string.sync_report_unnamed_product),
            ambiguity.remoteNet.toPlainString(),
            ambiguity.remoteProductIds.size,
            ambiguity.localProductIds.size,
        ),
        modifier = Modifier.testTag(
            SyncTestTags.productAmbiguity(ambiguity.remoteProductIds.first()),
        ),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun AmbiguousRemoteLine(ambiguity: AmbiguousRemotePurchase) {
    val change = ambiguity.remote
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SyncTestTags.ambiguity(change.purchaseId)),
    ) {
        Text(
            text = stringResource(
                R.string.sync_purchase_document_line,
                stringResource(remoteDocumentTypeLabelRes(change.documentType)),
                "${change.documentSeries}-${change.documentNumber}",
            ),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = pluralStringResource(
                R.plurals.sync_report_ambiguous_candidates,
                ambiguity.localPurchaseIds.size,
                ambiguity.localPurchaseIds.size,
            ),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun RemoteOnlyLine(change: RemotePurchaseChange) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(
                R.string.sync_purchase_document_line,
                stringResource(remoteDocumentTypeLabelRes(change.documentType)),
                "${change.documentSeries}-${change.documentNumber}",
            ),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(R.string.sync_purchase_supplier_line, change.supplierLegalName),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(
                R.string.sync_purchase_total_line,
                formatRemoteAmount(change.totalMinorUnits, change.currency),
            ),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(
                R.string.sync_purchase_status_line,
                stringResource(syncPurchaseStatusLabelRes(change.status)),
            ),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun BalanceDifferenceLine(difference: BalanceDifference) {
    val delta = difference.difference
    Text(
        text = stringResource(
            R.string.sync_report_balance_line,
            difference.productName ?: stringResource(R.string.sync_report_unnamed_product),
            difference.localOnHand.toPlainString(),
            difference.remoteNet.toPlainString(),
            if (delta.signum() > 0) "+${delta.toPlainString()}" else delta.toPlainString(),
        ),
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun SectionHeader(@StringRes titleRes: Int) {
    Text(
        text = stringResource(titleRes),
        modifier = Modifier.semantics { heading() },
        color = MaterialTheme.colorScheme.onBackground,
        style = MaterialTheme.typography.titleLarge,
    )
}

/** El total remoto viaja en minor units + código ISO; si el código no es sano no se inventa. */
@Composable
private fun formatRemoteAmount(totalMinorUnits: Long, currency: String): String =
    runCatching {
        Money.ofMinor(totalMinorUnits, CurrencyCode.of(currency)).formatForDisplay()
    }.getOrNull() ?: stringResource(R.string.sync_amount_unavailable)

/** La fecha remota viaja como ISO-8601; si no parsea se muestra tal cual llegó. */
private fun formatRemoteIssueDate(issueDate: String): String =
    runCatching { LocalDate.parse(issueDate).formatForDisplay() }.getOrDefault(issueDate)
