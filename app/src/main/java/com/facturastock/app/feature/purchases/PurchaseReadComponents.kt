package com.facturastock.app.feature.purchases

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.facturastock.app.R
import com.facturastock.app.domain.model.PurchaseDocumentType
import com.facturastock.app.domain.model.PurchaseReadSummary
import com.facturastock.app.domain.model.PurchaseStatus
import com.facturastock.app.domain.model.PurchaseSyncState
import com.facturastock.app.domain.model.UnitCost
import com.facturastock.app.ui.components.FacturaStockSecondaryButton
import com.facturastock.app.ui.format.formatForDisplay
import com.facturastock.app.ui.theme.FacturaStockDesign
import java.math.BigDecimal

@Composable
internal fun PurchaseStatusBadge(
    status: PurchaseStatus,
    modifier: Modifier = Modifier,
) {
    val material = MaterialTheme.colorScheme
    val semantic = FacturaStockDesign.semanticColors
    val colors = when (status) {
        PurchaseStatus.DRAFT -> BadgeColors(
            container = semantic.warningContainer,
            content = semantic.onWarningContainer,
            border = semantic.warning,
        )
        PurchaseStatus.POSTED -> BadgeColors(
            container = semantic.successContainer,
            content = semantic.onSuccessContainer,
            border = semantic.success,
        )
        PurchaseStatus.VOIDED -> BadgeColors(
            container = material.errorContainer,
            content = material.onErrorContainer,
            border = material.error,
        )
    }
    PurchaseBadge(
        label = androidx.compose.ui.res.stringResource(status.labelRes()),
        colors = colors,
        modifier = modifier,
    )
}

@Composable
internal fun PurchaseSyncBadge(
    syncState: PurchaseSyncState,
    modifier: Modifier = Modifier,
) {
    val material = MaterialTheme.colorScheme
    val semantic = FacturaStockDesign.semanticColors
    val colors = when (syncState) {
        PurchaseSyncState.DRAFT -> BadgeColors(
            container = material.surfaceVariant,
            content = material.onSurfaceVariant,
            border = material.outlineVariant,
        )
        PurchaseSyncState.PENDING_SYNC -> BadgeColors(
            container = semantic.warningContainer,
            content = semantic.onWarningContainer,
            border = semantic.warning,
        )
        PurchaseSyncState.SYNCING -> BadgeColors(
            container = semantic.infoContainer,
            content = semantic.onInfoContainer,
            border = semantic.info,
        )
        PurchaseSyncState.SYNCED -> BadgeColors(
            container = semantic.successContainer,
            content = semantic.onSuccessContainer,
            border = semantic.success,
        )
        PurchaseSyncState.ERROR -> BadgeColors(
            container = material.errorContainer,
            content = material.onErrorContainer,
            border = material.error,
        )
        PurchaseSyncState.CONFLICT -> BadgeColors(
            container = semantic.warningContainer,
            content = semantic.onWarningContainer,
            border = semantic.warning,
        )
        PurchaseSyncState.RESOLVED -> BadgeColors(
            container = semantic.infoContainer,
            content = semantic.onInfoContainer,
            border = semantic.info,
        )
    }
    PurchaseBadge(
        label = androidx.compose.ui.res.stringResource(syncState.labelRes()),
        colors = colors,
        modifier = modifier,
    )
}

@Composable
private fun PurchaseBadge(
    label: String,
    colors: BadgeColors,
    modifier: Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraSmall,
        color = colors.container,
        contentColor = colors.content,
        border = BorderStroke(FacturaStockDesign.spacing.borderThin, colors.border),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(
                horizontal = FacturaStockDesign.spacing.sm,
                vertical = FacturaStockDesign.spacing.xs,
            ),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

private data class BadgeColors(
    val container: Color,
    val content: Color,
    val border: Color,
)

@StringRes
internal fun PurchaseStatus.labelRes(): Int = when (this) {
    PurchaseStatus.DRAFT -> R.string.purchase_status_draft
    PurchaseStatus.POSTED -> R.string.purchase_status_posted
    PurchaseStatus.VOIDED -> R.string.purchase_status_voided
}

@StringRes
internal fun PurchaseDocumentType.labelRes(): Int = when (this) {
    PurchaseDocumentType.INVOICE -> R.string.header_review_document_type_invoice
    PurchaseDocumentType.SALES_RECEIPT -> R.string.header_review_document_type_sales_receipt
    PurchaseDocumentType.CREDIT_NOTE -> R.string.header_review_document_type_credit_note
    PurchaseDocumentType.DEBIT_NOTE -> R.string.header_review_document_type_debit_note
}

@StringRes
internal fun PurchaseSyncState.labelRes(): Int = when (this) {
    PurchaseSyncState.DRAFT -> R.string.purchases_sync_draft
    PurchaseSyncState.PENDING_SYNC -> R.string.purchases_sync_pending
    PurchaseSyncState.SYNCING -> R.string.purchases_sync_syncing
    PurchaseSyncState.SYNCED -> R.string.purchases_sync_synced
    PurchaseSyncState.ERROR -> R.string.purchases_sync_error
    PurchaseSyncState.CONFLICT -> R.string.purchases_sync_conflict
    PurchaseSyncState.RESOLVED -> R.string.purchases_sync_resolved
}

/**
 * Separa deliberadamente durabilidad local de respaldo: cualquier estado de outbox mantiene la
 * primera garantía. El botón solo reencola Room; esta UI nunca espera ni renderiza una respuesta
 * de red.
 */
@Composable
internal fun PurchasePersistenceStatus(
    summary: PurchaseReadSummary,
    modifier: Modifier = Modifier,
    isRetrying: Boolean = false,
    retryFailed: Boolean = false,
    retryTestTag: String? = null,
    onRetryBackup: (() -> Unit)? = null,
) {
    val spacing = FacturaStockDesign.spacing
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Text(
                text = stringResource(R.string.purchase_local_saved_title),
                color = FacturaStockDesign.semanticColors.success,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.purchase_local_saved_message),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.purchase_backup_title),
                modifier = Modifier.padding(top = spacing.xs),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
            )
            PurchaseSyncBadge(summary.syncState)
            Text(
                text = stringResource(summary.syncState.messageRes()),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            summary.lastSyncAttemptAt?.let { attemptedAt ->
                Text(
                    text = stringResource(
                        R.string.purchase_backup_last_attempt,
                        attemptedAt.formatForDisplay(),
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (retryFailed) {
                Text(
                    text = stringResource(R.string.purchase_backup_retry_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (summary.syncState.isRetryable() && onRetryBackup != null) {
                FacturaStockSecondaryButton(
                    text = stringResource(
                        if (isRetrying) {
                            R.string.action_retrying_backup
                        } else {
                            R.string.action_retry_backup
                        },
                    ),
                    onClick = onRetryBackup,
                    enabled = !isRetrying,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (retryTestTag == null) Modifier else Modifier.testTag(retryTestTag),
                        ),
                )
            }
        }
    }
}

@StringRes
private fun PurchaseSyncState.messageRes(): Int = when (this) {
    PurchaseSyncState.DRAFT -> R.string.purchase_backup_draft_message
    PurchaseSyncState.PENDING_SYNC -> R.string.purchase_backup_pending_message
    PurchaseSyncState.SYNCING -> R.string.purchase_backup_syncing_message
    PurchaseSyncState.SYNCED -> R.string.purchase_backup_synced_message
    PurchaseSyncState.ERROR -> R.string.purchase_backup_error_message
    PurchaseSyncState.CONFLICT -> R.string.purchase_backup_conflict_message
    PurchaseSyncState.RESOLVED -> R.string.purchase_backup_resolved_message
}

internal fun PurchaseSyncState.isRetryable(): Boolean =
    this == PurchaseSyncState.ERROR || this == PurchaseSyncState.CONFLICT

internal fun BigDecimal.formatPurchaseQuantity(): String =
    stripTrailingZeros().toPlainString()

internal fun UnitCost.formatPurchaseUnitCost(): String =
    "${currency.value} ${amount.stripTrailingZeros().toPlainString()}"

@Composable
internal fun purchaseCountsText(lineCount: Int, productCount: Int): String = stringResource(
    R.string.purchases_card_counts,
    pluralStringResource(R.plurals.purchases_line_count, lineCount, lineCount),
    pluralStringResource(R.plurals.purchases_product_count, productCount, productCount),
)

@Composable
internal fun purchaseSuccessCountsText(
    lineCount: Int,
    createdProductCount: Int,
    existingProductCount: Int,
    unknownProductCount: Int,
): String {
    val linesAndCreated = stringResource(
        R.string.purchases_card_counts,
        pluralStringResource(R.plurals.purchases_line_count, lineCount, lineCount),
        pluralStringResource(
            R.plurals.purchase_success_created_product_count,
            createdProductCount,
            createdProductCount,
        ),
    )
    val knownCounts = stringResource(
        R.string.purchases_card_counts,
        linesAndCreated,
        pluralStringResource(
            R.plurals.purchase_success_existing_product_count,
            existingProductCount,
            existingProductCount,
        ),
    )
    return if (unknownProductCount == 0) {
        knownCounts
    } else {
        stringResource(
            R.string.purchases_card_counts,
            knownCounts,
            pluralStringResource(
                R.plurals.purchase_success_unknown_product_count,
                unknownProductCount,
                unknownProductCount,
            ),
        )
    }
}
