package com.facturastock.app.feature.purchases

import org.jetbrains.compose.resources.StringResource

import com.facturastock.app.resources.*
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
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
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
        label = org.jetbrains.compose.resources.stringResource(status.labelRes()),
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
        label = org.jetbrains.compose.resources.stringResource(syncState.labelRes()),
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

internal fun PurchaseStatus.labelRes(): StringResource = when (this) {
    PurchaseStatus.DRAFT -> Res.string.purchase_status_draft
    PurchaseStatus.POSTED -> Res.string.purchase_status_posted
    PurchaseStatus.VOIDED -> Res.string.purchase_status_voided
}

internal fun PurchaseDocumentType.labelRes(): StringResource = when (this) {
    PurchaseDocumentType.INVOICE -> Res.string.header_review_document_type_invoice
    PurchaseDocumentType.SALES_RECEIPT -> Res.string.header_review_document_type_sales_receipt
    PurchaseDocumentType.CREDIT_NOTE -> Res.string.header_review_document_type_credit_note
    PurchaseDocumentType.DEBIT_NOTE -> Res.string.header_review_document_type_debit_note
}

internal fun PurchaseSyncState.labelRes(): StringResource = when (this) {
    PurchaseSyncState.DRAFT -> Res.string.purchases_sync_draft
    PurchaseSyncState.PENDING_SYNC -> Res.string.purchases_sync_pending
    PurchaseSyncState.SYNCING -> Res.string.purchases_sync_syncing
    PurchaseSyncState.SYNCED -> Res.string.purchases_sync_synced
    PurchaseSyncState.ERROR -> Res.string.purchases_sync_error
    PurchaseSyncState.CONFLICT -> Res.string.purchases_sync_conflict
    PurchaseSyncState.RESOLVED -> Res.string.purchases_sync_resolved
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
                text = stringResource(Res.string.purchase_local_saved_title),
                color = FacturaStockDesign.semanticColors.success,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(Res.string.purchase_local_saved_message),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(Res.string.purchase_backup_title),
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
                        Res.string.purchase_backup_last_attempt,
                        attemptedAt.formatForDisplay(),
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (retryFailed) {
                Text(
                    text = stringResource(Res.string.purchase_backup_retry_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (summary.syncState.isRetryable() && onRetryBackup != null) {
                FacturaStockSecondaryButton(
                    text = stringResource(
                        if (isRetrying) {
                            Res.string.action_retrying_backup
                        } else {
                            Res.string.action_retry_backup
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

private fun PurchaseSyncState.messageRes(): StringResource = when (this) {
    PurchaseSyncState.DRAFT -> Res.string.purchase_backup_draft_message
    PurchaseSyncState.PENDING_SYNC -> Res.string.purchase_backup_pending_message
    PurchaseSyncState.SYNCING -> Res.string.purchase_backup_syncing_message
    PurchaseSyncState.SYNCED -> Res.string.purchase_backup_synced_message
    PurchaseSyncState.ERROR -> Res.string.purchase_backup_error_message
    PurchaseSyncState.CONFLICT -> Res.string.purchase_backup_conflict_message
    PurchaseSyncState.RESOLVED -> Res.string.purchase_backup_resolved_message
}

internal fun PurchaseSyncState.isRetryable(): Boolean =
    this == PurchaseSyncState.ERROR || this == PurchaseSyncState.CONFLICT

internal fun BigDecimal.formatPurchaseQuantity(): String =
    stripTrailingZeros().toPlainString()

internal fun UnitCost.formatPurchaseUnitCost(): String =
    "${currency.value} ${amount.stripTrailingZeros().toPlainString()}"

@Composable
internal fun purchaseCountsText(lineCount: Int, productCount: Int): String = stringResource(
    Res.string.purchases_card_counts,
    pluralStringResource(Res.plurals.purchases_line_count, lineCount, lineCount),
    pluralStringResource(Res.plurals.purchases_product_count, productCount, productCount),
)

@Composable
internal fun purchaseSuccessCountsText(
    lineCount: Int,
    createdProductCount: Int,
    existingProductCount: Int,
    unknownProductCount: Int,
): String {
    val linesAndCreated = stringResource(
        Res.string.purchases_card_counts,
        pluralStringResource(Res.plurals.purchases_line_count, lineCount, lineCount),
        pluralStringResource(
            Res.plurals.purchase_success_created_product_count,
            createdProductCount,
            createdProductCount,
        ),
    )
    val knownCounts = stringResource(
        Res.string.purchases_card_counts,
        linesAndCreated,
        pluralStringResource(
            Res.plurals.purchase_success_existing_product_count,
            existingProductCount,
            existingProductCount,
        ),
    )
    return if (unknownProductCount == 0) {
        knownCounts
    } else {
        stringResource(
            Res.string.purchases_card_counts,
            knownCounts,
            pluralStringResource(
                Res.plurals.purchase_success_unknown_product_count,
                unknownProductCount,
                unknownProductCount,
            ),
        )
    }
}
