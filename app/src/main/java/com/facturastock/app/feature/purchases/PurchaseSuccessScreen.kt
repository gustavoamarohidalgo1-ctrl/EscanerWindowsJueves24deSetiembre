package com.facturastock.app.feature.purchases

import com.facturastock.app.resources.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.facturastock.app.domain.model.PurchaseReadDetail
import com.facturastock.app.ui.components.FacturaStockPrimaryButton
import com.facturastock.app.ui.components.FacturaStockSecondaryButton
import com.facturastock.app.ui.components.StatusCard
import com.facturastock.app.ui.components.StatusTone
import com.facturastock.app.ui.format.formatForDisplay
import com.facturastock.app.ui.format.formatSignedForDisplay
import com.facturastock.app.ui.theme.FacturaStockDesign

/** Confirmación terminal: no conserva acciones editables ni ofrece repetir la publicación. */
@Composable
fun PurchaseSuccessScreen(
    detail: PurchaseReadDetail,
    onViewDetail: () -> Unit,
    onViewInventory: () -> Unit,
    modifier: Modifier = Modifier,
    isRetryingBackup: Boolean = false,
    backupRetryFailed: Boolean = false,
    onRetryBackup: () -> Unit = {},
) {
    val spacing = FacturaStockDesign.spacing
    val summary = detail.summary
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag(PurchaseSuccessTestTags.SCREEN),
        contentPadding = PaddingValues(spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        item(key = "title", contentType = "header") {
            Text(
                text = stringResource(Res.string.purchase_success_title),
                modifier = Modifier.semantics { heading() },
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.headlineLarge,
            )
        }
        item(key = "summary", contentType = "status") {
            StatusCard(
                statusLabel = stringResource(Res.string.purchase_success_status),
                title = summary.canonicalDocumentNumber,
                message = stringResource(Res.string.purchase_success_message),
                tone = StatusTone.SUCCESS,
                iconRes = Res.drawable.ic_check_circle,
                modifier = Modifier
                    .testTag(PurchaseSuccessTestTags.SUMMARY)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                supportingContent = {
                    Text(
                        text = summary.supplierLegalName,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        stringResource(
                            Res.string.purchase_duplicate_date,
                            summary.issueDate.formatForDisplay(),
                        ),
                    )
                    Text(
                        purchaseSuccessCountsText(
                            lineCount = summary.lineCount,
                            createdProductCount = summary.createdProductCount,
                            existingProductCount = summary.existingProductCount,
                            unknownProductCount = summary.unknownProductCount,
                        ),
                    )
                    Text(
                        stringResource(
                            Res.string.purchases_card_total,
                            summary.total.formatForDisplay(),
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    detail.adjustment?.let { adjustment ->
                        Text(
                            text = stringResource(
                                Res.string.purchase_success_adjustment,
                                adjustment.formatSignedForDisplay(),
                            ),
                            modifier = Modifier.testTag(PurchaseSuccessTestTags.ADJUSTMENT),
                        )
                        detail.adjustmentReason?.let { reason ->
                            Text(stringResource(Res.string.purchase_success_adjustment_reason, reason))
                        }
                    }
                },
            )
        }
        item(key = "persistence", contentType = "status") {
            PurchasePersistenceStatus(
                summary = summary,
                isRetrying = isRetryingBackup,
                retryFailed = backupRetryFailed,
                retryTestTag = PurchaseSuccessTestTags.RETRY_BACKUP,
                onRetryBackup = onRetryBackup,
                modifier = Modifier.testTag(PurchaseSuccessTestTags.PERSISTENCE),
            )
        }
        item(key = "inventory_message", contentType = "message") {
            Text(
                text = stringResource(Res.string.purchase_success_inventory_message),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        item(key = "view_detail", contentType = "action") {
            FacturaStockPrimaryButton(
                text = stringResource(Res.string.action_view_purchase_detail),
                onClick = onViewDetail,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(PurchaseSuccessTestTags.VIEW_DETAIL),
                leadingIconRes = Res.drawable.ic_receipt,
            )
        }
        item(key = "view_inventory", contentType = "action") {
            FacturaStockSecondaryButton(
                text = stringResource(Res.string.action_view_updated_inventory),
                onClick = onViewInventory,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(PurchaseSuccessTestTags.VIEW_INVENTORY),
                leadingIconRes = Res.drawable.ic_inventory,
            )
        }
    }
}
