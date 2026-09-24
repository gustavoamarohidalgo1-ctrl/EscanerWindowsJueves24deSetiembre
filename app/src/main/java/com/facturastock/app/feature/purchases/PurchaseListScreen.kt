package com.facturastock.app.feature.purchases

import com.facturastock.app.resources.*
import org.jetbrains.compose.resources.StringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.facturastock.app.domain.model.PurchaseReadSummary
import com.facturastock.app.domain.model.PurchaseStatus
import com.facturastock.app.domain.model.PurchaseSyncState
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.ui.components.EmptyState
import com.facturastock.app.ui.components.FacturaStockPrimaryButton
import com.facturastock.app.ui.components.FacturaStockSecondaryButton
import com.facturastock.app.ui.components.LoadingState
import com.facturastock.app.ui.format.formatForDisplay
import com.facturastock.app.ui.theme.FacturaStockDesign

/**
 * Historial de compras sin estado interno de negocio. Los filtros se elevan al ViewModel para
 * que puedan sobrevivir recreación y cambio de negocio sin duplicar una segunda fuente de verdad.
 */
@Composable
fun PurchaseListScreen(
    purchases: List<PurchaseReadSummary>,
    query: String,
    statusFilter: PurchaseStatus?,
    syncFilter: PurchaseSyncState?,
    onQueryChange: (String) -> Unit,
    onStatusFilterChange: (PurchaseStatus?) -> Unit,
    onSyncFilterChange: (PurchaseSyncState?) -> Unit,
    onPurchaseClick: (PurchaseId) -> Unit,
    onNewPurchase: () -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    retryingBackupPurchaseId: PurchaseId? = null,
    backupRetryFailedPurchaseId: PurchaseId? = null,
    onRetryBackup: (PurchaseId) -> Unit = {},
    hasMore: Boolean = false,
    isLoadingMore: Boolean = false,
    onLoadMore: () -> Unit = {},
) {
    val spacing = FacturaStockDesign.spacing
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag(PurchaseListTestTags.SCREEN),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag(PurchaseListTestTags.LIST),
            contentPadding = PaddingValues(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            item(key = "header", contentType = "header") {
                PurchaseListHeader(onNewPurchase = onNewPurchase)
            }
            item(key = "filters", contentType = "filters") {
                PurchaseFilters(
                    query = query,
                    statusFilter = statusFilter,
                    syncFilter = syncFilter,
                    onQueryChange = onQueryChange,
                    onStatusFilterChange = onStatusFilterChange,
                    onSyncFilterChange = onSyncFilterChange,
                )
            }
            item(key = "count", contentType = "count") {
                Text(
                    text = pluralStringResource(
                        Res.plurals.purchases_result_count,
                        purchases.size,
                        purchases.size,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            when {
                isLoading -> item(key = "loading", contentType = "loading") {
                    LoadingState(
                        message = stringResource(Res.string.feature_loading_message),
                        modifier = Modifier.testTag(PurchaseListTestTags.LOADING),
                    )
                }
                purchases.isEmpty() -> item(key = "empty", contentType = "empty") {
                    val filtered = query.isNotBlank() || statusFilter != null || syncFilter != null
                    EmptyState(
                        title = stringResource(
                            if (filtered) {
                                Res.string.purchases_empty_filtered_title
                            } else {
                                Res.string.purchases_empty_title
                            },
                        ),
                        message = stringResource(
                            if (filtered) {
                                Res.string.purchases_empty_filtered_message
                            } else {
                                Res.string.purchases_empty_message
                            },
                        ),
                        iconRes = Res.drawable.ic_receipt,
                        actionLabel = if (filtered) {
                            stringResource(Res.string.action_clear_filters)
                        } else {
                            null
                        },
                        onAction = if (filtered) {
                            {
                                onQueryChange("")
                                onStatusFilterChange(null)
                                onSyncFilterChange(null)
                            }
                        } else {
                            null
                        },
                        modifier = Modifier.testTag(PurchaseListTestTags.EMPTY),
                    )
                }
                else -> {
                    items(
                        items = purchases,
                        key = { purchase -> purchase.purchaseId.value },
                        contentType = { "purchase" },
                    ) { purchase ->
                        PurchaseSummaryCard(
                            purchase = purchase,
                            onClick = { onPurchaseClick(purchase.purchaseId) },
                            isRetryingBackup = retryingBackupPurchaseId == purchase.purchaseId,
                            backupRetryFailed = backupRetryFailedPurchaseId == purchase.purchaseId,
                            onRetryBackup = { onRetryBackup(purchase.purchaseId) },
                        )
                    }
                    if (hasMore) {
                        item(key = "load_more", contentType = "load_more") {
                            FacturaStockSecondaryButton(
                                text = stringResource(
                                    if (isLoadingMore) {
                                        Res.string.purchases_loading_more
                                    } else {
                                        Res.string.purchases_load_more
                                    },
                                ),
                                onClick = onLoadMore,
                                enabled = !isLoadingMore,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag(PurchaseListTestTags.LOAD_MORE),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PurchaseListHeader(onNewPurchase: () -> Unit) {
    FacturaStockPrimaryButton(
        text = stringResource(Res.string.action_register_purchase),
        onClick = onNewPurchase,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(PurchaseListTestTags.NEW_PURCHASE),
        leadingIconRes = Res.drawable.ic_add_document,
    )
}

@Composable
private fun PurchaseFilters(
    query: String,
    statusFilter: PurchaseStatus?,
    syncFilter: PurchaseSyncState?,
    onQueryChange: (String) -> Unit,
    onStatusFilterChange: (PurchaseStatus?) -> Unit,
    onSyncFilterChange: (PurchaseSyncState?) -> Unit,
) {
    val spacing = FacturaStockDesign.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text(stringResource(Res.string.purchases_search_label)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(PurchaseListTestTags.SEARCH),
        )
        FilterHeading(Res.string.purchases_status_filter_title)
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            items(
                items = STATUS_FILTERS,
                key = { status -> status?.name ?: "ALL" },
                contentType = { "status_filter" },
            ) { status ->
                FilterChip(
                    selected = statusFilter == status,
                    onClick = { onStatusFilterChange(status) },
                    label = {
                        Text(
                            stringResource(
                                when (status) {
                                    null -> Res.string.purchases_filter_all
                                    PurchaseStatus.POSTED -> Res.string.purchases_filter_posted
                                    PurchaseStatus.VOIDED -> Res.string.purchases_filter_voided
                                    PurchaseStatus.DRAFT -> Res.string.purchase_status_draft
                                },
                            ),
                        )
                    },
                    modifier = Modifier.testTag(PurchaseListTestTags.statusFilter(status)),
                )
            }
        }
        FilterHeading(Res.string.purchases_sync_filter_title)
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            items(
                items = SYNC_FILTERS,
                key = { state -> state?.name ?: "ALL" },
                contentType = { "sync_filter" },
            ) { state ->
                FilterChip(
                    selected = syncFilter == state,
                    onClick = { onSyncFilterChange(state) },
                    label = {
                        Text(
                            stringResource(
                                state?.filterLabelRes() ?: Res.string.purchases_sync_filter_all,
                            ),
                        )
                    },
                    modifier = Modifier.testTag(PurchaseListTestTags.syncFilter(state)),
                )
            }
        }
    }
}

@Composable
private fun FilterHeading(labelRes: StringResource) {
    Text(
        text = stringResource(labelRes),
        modifier = Modifier.semantics { heading() },
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
private fun PurchaseSummaryCard(
    purchase: PurchaseReadSummary,
    onClick: () -> Unit,
    isRetryingBackup: Boolean,
    backupRetryFailed: Boolean,
    onRetryBackup: () -> Unit,
) {
    val spacing = FacturaStockDesign.spacing
    val formattedIssueDate = remember(purchase.issueDate) {
        purchase.issueDate.formatForDisplay()
    }
    val formattedTotal = remember(purchase.total) { purchase.total.formatForDisplay() }
    Column(modifier = Modifier.fillMaxWidth()) {
        Surface(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(PurchaseListTestTags.row(purchase.purchaseId)),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier.padding(spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Text(
                    text = purchase.supplierLegalName,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(
                        Res.string.purchases_card_document,
                        stringResource(purchase.documentType.labelRes()),
                        purchase.canonicalDocumentNumber,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(
                        Res.string.purchase_duplicate_date,
                        formattedIssueDate,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(
                        Res.string.purchases_card_total,
                        formattedTotal,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                )
                PurchaseBadges(
                    status = purchase.status,
                    syncState = purchase.syncState,
                )
                if (backupRetryFailed) {
                    Text(
                        text = stringResource(Res.string.purchase_backup_retry_failed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (purchase.syncState.isRetryable()) {
                    FacturaStockSecondaryButton(
                        text = stringResource(
                            if (isRetryingBackup) {
                                Res.string.action_retrying_backup
                            } else {
                                Res.string.action_retry_backup
                            },
                        ),
                        onClick = onRetryBackup,
                        enabled = !isRetryingBackup,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(PurchaseListTestTags.retryBackup(purchase.purchaseId)),
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun PurchaseBadges(
    status: PurchaseStatus,
    syncState: PurchaseSyncState,
) {
    val spacing = FacturaStockDesign.spacing
    if (LocalDensity.current.fontScale >= 1.5f) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            PurchaseStatusBadge(status)
            PurchaseSyncBadge(syncState)
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            PurchaseStatusBadge(status)
            PurchaseSyncBadge(syncState)
        }
    }
}

private fun PurchaseSyncState.filterLabelRes(): StringResource = when (this) {
    PurchaseSyncState.DRAFT -> Res.string.purchases_sync_filter_draft
    PurchaseSyncState.PENDING_SYNC -> Res.string.purchases_sync_filter_pending
    PurchaseSyncState.SYNCING -> Res.string.purchases_sync_filter_syncing
    PurchaseSyncState.SYNCED -> Res.string.purchases_sync_filter_synced
    PurchaseSyncState.ERROR -> Res.string.purchases_sync_filter_error
    PurchaseSyncState.CONFLICT -> Res.string.purchases_sync_filter_conflict
    PurchaseSyncState.RESOLVED -> Res.string.purchases_sync_filter_resolved
}

private val STATUS_FILTERS: List<PurchaseStatus?> = listOf(
    null,
    PurchaseStatus.POSTED,
    PurchaseStatus.VOIDED,
)

private val SYNC_FILTERS: List<PurchaseSyncState?> =
    listOf(null) + PurchaseSyncState.entries
