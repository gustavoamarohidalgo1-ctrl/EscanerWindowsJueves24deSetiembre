package com.facturastock.app.feature.purchases

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.facturastock.app.R
import com.facturastock.app.domain.model.AuditEventType
import com.facturastock.app.domain.model.PurchaseOverrideRole
import com.facturastock.app.domain.model.PurchaseReadAuditEvent
import com.facturastock.app.domain.model.PurchaseReadDetail
import com.facturastock.app.domain.model.PurchaseReadDuplicateOverride
import com.facturastock.app.domain.model.PurchaseReadLine
import com.facturastock.app.domain.model.PurchaseReadMovement
import com.facturastock.app.domain.model.PurchaseRetainedImage
import com.facturastock.app.domain.model.PurchaseStatus
import com.facturastock.app.domain.model.StockMovementType
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.ui.components.FacturaStockPrimaryButton
import com.facturastock.app.ui.components.FacturaStockSecondaryButton
import com.facturastock.app.ui.components.StatusCard
import com.facturastock.app.ui.components.StatusTone
import com.facturastock.app.ui.format.formatForDisplay
import com.facturastock.app.ui.format.formatSignedForDisplay
import com.facturastock.app.ui.theme.FacturaStockDesign
import coil3.compose.AsyncImage
import com.facturastock.app.feature.common.sensitiveImageRequest
import java.math.BigDecimal

/** Vista durable y de solo lectura de una compra publicada. */
@Composable
internal fun PurchaseDetailScreen(
    detail: PurchaseReadDetail,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    retainedImages: Map<ImageId, PurchasesContract.RetainedImageContent> = emptyMap(),
    onVoidPurchase: () -> Unit = {},
    isRetryingBackup: Boolean = false,
    backupRetryFailed: Boolean = false,
    onRetryBackup: () -> Unit = {},
    technicalDetailsVisible: Boolean = false,
    onTechnicalDetailsToggle: () -> Unit = {},
) {
    val spacing = FacturaStockDesign.spacing
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(PurchaseDetailTestTags.SCREEN),
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag(PurchaseDetailTestTags.LIST),
            contentPadding = PaddingValues(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            item(key = "document", contentType = "document") {
                PurchaseDocumentCard(
                    detail = detail,
                    modifier = Modifier.testTag(PurchaseDetailTestTags.DOCUMENT),
                )
            }
            item(key = "persistence", contentType = "status") {
                PurchasePersistenceStatus(
                    summary = detail.summary,
                    isRetrying = isRetryingBackup,
                    retryFailed = backupRetryFailed,
                    retryTestTag = PurchaseDetailTestTags.RETRY_BACKUP,
                    onRetryBackup = onRetryBackup,
                    modifier = Modifier.testTag(PurchaseDetailTestTags.PERSISTENCE),
                )
            }
            item(key = "totals", contentType = "totals") {
                PurchaseTotalsCard(
                    detail = detail,
                    modifier = Modifier.testTag(PurchaseDetailTestTags.TOTALS),
                )
            }
            item(key = "lines_header", contentType = "section_header") {
                DetailSectionTitle(
                    text = stringResource(
                        R.string.purchase_detail_lines_section,
                        detail.lines.size,
                    ),
                    modifier = Modifier.testTag(PurchaseDetailTestTags.LINES),
                )
            }
            items(
                items = detail.lines,
                key = PurchaseReadLine::purchaseLineId,
                contentType = { "line" },
            ) { line ->
                PurchaseLineCard(
                    line = line,
                    modifier = Modifier.testTag(PurchaseDetailTestTags.line(line.position)),
                )
            }
            item(key = "technical_toggle", contentType = "action") {
                FacturaStockSecondaryButton(
                    text = stringResource(
                        if (technicalDetailsVisible) {
                            R.string.purchase_detail_technical_hide
                        } else {
                            R.string.purchase_detail_technical_show
                        },
                    ),
                    onClick = onTechnicalDetailsToggle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(PurchaseDetailTestTags.TECHNICAL_TOGGLE),
                )
            }
            if (technicalDetailsVisible) {
                detail.duplicateOverride?.let { duplicateOverride ->
                    item(key = "duplicate_override", contentType = "audit_metadata") {
                        DuplicateOverrideCard(
                            duplicateOverride = duplicateOverride,
                            modifier = Modifier.testTag(
                                PurchaseDetailTestTags.DUPLICATE_OVERRIDE,
                            ),
                        )
                    }
                }
                if (detail.movements.isNotEmpty()) {
                    item(key = "movements_header", contentType = "section_header") {
                        DetailSectionTitle(
                            text = stringResource(
                                R.string.purchase_detail_movements_section,
                                detail.movements.size,
                            ),
                            modifier = Modifier.testTag(PurchaseDetailTestTags.MOVEMENTS),
                        )
                    }
                    items(
                        items = detail.movements,
                        key = PurchaseReadMovement::movementId,
                        contentType = { "movement" },
                    ) { movement ->
                        PurchaseMovementCard(
                            movement = movement,
                            modifier = Modifier.testTag(
                                PurchaseDetailTestTags.movement(movement.movementId),
                            ),
                        )
                    }
                }
                if (detail.images.isNotEmpty()) {
                    item(key = "images_header", contentType = "section_header") {
                        DetailSectionTitle(
                            text = stringResource(
                                R.string.purchase_detail_images_section,
                                detail.images.size,
                            ),
                            modifier = Modifier.testTag(PurchaseDetailTestTags.IMAGES),
                        )
                    }
                    items(
                        items = detail.images,
                        key = { image -> image.imageId.value },
                        contentType = { "image" },
                    ) { image ->
                        RetainedImageCard(
                            image = image,
                            content = retainedImages[image.imageId]
                                ?: PurchasesContract.RetainedImageContent.Unavailable,
                            modifier = Modifier.testTag(
                                PurchaseDetailTestTags.image(image.pageIndex),
                            ),
                        )
                    }
                }
                if (detail.auditEvents.isNotEmpty()) {
                    item(key = "audit_header", contentType = "section_header") {
                        DetailSectionTitle(
                            text = stringResource(
                                R.string.purchase_detail_audit_section,
                                detail.auditEvents.size,
                            ),
                            modifier = Modifier.testTag(PurchaseDetailTestTags.AUDIT),
                        )
                    }
                    items(
                        items = detail.auditEvents,
                        key = PurchaseReadAuditEvent::auditEventId,
                        contentType = { "audit" },
                    ) { event ->
                        AuditEventCard(
                            event = event,
                            modifier = Modifier.testTag(
                                PurchaseDetailTestTags.audit(event.auditEventId),
                            ),
                        )
                    }
                }
                item(key = "integrity", contentType = "integrity") {
                    IntegrityCard(
                        detail = detail,
                        modifier = Modifier.testTag(PurchaseDetailTestTags.INTEGRITY),
                    )
                }
            }
            when (detail.summary.status) {
                PurchaseStatus.POSTED -> item(key = "void_action", contentType = "action") {
                    VoidPurchaseActionCard(onVoidPurchase = onVoidPurchase)
                }
                PurchaseStatus.VOIDED -> item(key = "voided_notice", contentType = "status") {
                    VoidedPurchaseNotice()
                }
                PurchaseStatus.DRAFT -> Unit
            }
        }
        FacturaStockPrimaryButton(
            text = stringResource(R.string.action_back),
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.md, vertical = spacing.sm)
                .testTag(PurchaseDetailTestTags.BACK),
            leadingIconRes = R.drawable.ic_back,
        )
    }
}

@Composable
private fun DuplicateOverrideCard(
    duplicateOverride: PurchaseReadDuplicateOverride,
    modifier: Modifier = Modifier,
) {
    ReadCard(modifier = modifier) {
        DetailSectionTitle(stringResource(R.string.purchase_detail_duplicate_override_section))
        Text(
            text = stringResource(R.string.purchase_detail_duplicate_override_message),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        OverrideMetadata(
            labelRes = R.string.purchase_detail_duplicate_override_target,
            value = duplicateOverride.existingPurchaseId.value,
            monospace = true,
        )
        OverrideMetadata(
            labelRes = R.string.purchase_detail_duplicate_override_reason,
            value = duplicateOverride.reason,
        )
        OverrideMetadata(
            labelRes = R.string.purchase_detail_duplicate_override_actor,
            value = duplicateOverride.actorId,
            monospace = true,
        )
        OverrideMetadata(
            labelRes = R.string.purchase_detail_duplicate_override_role,
            value = stringResource(duplicateOverride.actorRole.labelRes()),
        )
    }
}

@Composable
private fun OverrideMetadata(
    @StringRes labelRes: Int,
    value: String,
    monospace: Boolean = false,
) {
    Text(
        text = stringResource(labelRes),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge,
    )
    Text(
        text = value,
        color = MaterialTheme.colorScheme.onSurface,
        fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun VoidPurchaseActionCard(onVoidPurchase: () -> Unit) {
    val spacing = FacturaStockDesign.spacing
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Text(
                text = stringResource(R.string.purchase_void_detail_action),
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.purchase_void_detail_action_message),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
            FacturaStockSecondaryButton(
                text = stringResource(R.string.purchase_void_detail_action),
                onClick = onVoidPurchase,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(PurchaseDetailTestTags.VOID_ACTION),
            )
        }
    }
}

@Composable
private fun VoidedPurchaseNotice() {
    StatusCard(
        statusLabel = stringResource(R.string.purchase_status_voided),
        title = stringResource(R.string.purchase_void_history_title),
        message = stringResource(R.string.purchase_void_history_message),
        tone = StatusTone.ERROR,
        iconRes = R.drawable.ic_warning,
        modifier = Modifier.testTag(PurchaseDetailTestTags.VOIDED_NOTICE),
    )
}

@Composable
private fun PurchaseDocumentCard(
    detail: PurchaseReadDetail,
    modifier: Modifier = Modifier,
) {
    val summary = detail.summary
    StatusCard(
        statusLabel = stringResource(summary.status.labelRes()),
        title = summary.canonicalDocumentNumber,
        message = summary.supplierLegalName,
        tone = summary.status.statusTone(),
        iconRes = R.drawable.ic_receipt,
        modifier = modifier,
        supportingContent = {
            summary.supplierRuc?.let { ruc ->
                Text(stringResource(R.string.purchases_card_ruc, ruc))
            }
            Text(stringResource(summary.documentType.labelRes()))
            Text(
                stringResource(
                    R.string.purchase_duplicate_date,
                    summary.issueDate.formatForDisplay(),
                ),
            )
            summary.postedAt?.let { postedAt ->
                Text(
                    stringResource(
                        R.string.purchases_card_posted_at,
                        postedAt.formatForDisplay(),
                    ),
                )
            }
            PurchaseSyncBadge(syncState = summary.syncState)
        },
    )
}

@Composable
private fun PurchaseTotalsCard(
    detail: PurchaseReadDetail,
    modifier: Modifier = Modifier,
) {
    val spacing = FacturaStockDesign.spacing
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = spacing.xs),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        DetailSectionTitle(stringResource(R.string.purchase_detail_totals_section))
        MoneyRow(
            labelRes = R.string.purchase_detail_subtotal,
            value = detail.subtotal.formatForDisplay(),
        )
        MoneyRow(
            labelRes = R.string.purchase_detail_tax,
            value = detail.tax.formatForDisplay(),
        )
        MoneyRow(
            labelRes = R.string.purchase_detail_other_charges,
            value = detail.otherCharges.formatForDisplay(),
        )
        detail.adjustment?.let { adjustment ->
            MoneyRow(
                labelRes = R.string.purchase_detail_adjustment,
                value = adjustment.formatSignedForDisplay(),
            )
            detail.adjustmentReason?.let { reason ->
                MoneyRow(
                    labelRes = R.string.purchase_detail_adjustment_reason,
                    value = reason,
                )
            }
        }
        MoneyRow(
            labelRes = R.string.purchase_detail_total,
            value = detail.summary.total.formatForDisplay(),
            emphasized = true,
        )
    }
}

@Composable
private fun MoneyRow(
    @StringRes labelRes: Int,
    value: String,
    emphasized: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(FacturaStockDesign.spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = stringResource(labelRes),
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = if (emphasized) {
                MaterialTheme.typography.titleMedium
            } else {
                MaterialTheme.typography.bodyMedium
            },
        )
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Normal,
            style = if (emphasized) {
                MaterialTheme.typography.titleMedium
            } else {
                MaterialTheme.typography.bodyMedium
            },
        )
    }
}

@Composable
private fun PurchaseLineCard(
    line: PurchaseReadLine,
    modifier: Modifier = Modifier,
) {
    val unit = line.unitSymbol ?: line.unitCode
    ReadCard(modifier = modifier) {
        Text(
            text = stringResource(R.string.purchase_detail_line_number, line.position + 1),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            text = line.productName,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
        )
        if (line.description != line.productName) {
            Text(
                text = line.description,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            text = stringResource(
                R.string.purchase_detail_line_quantity,
                line.quantity.formatPurchaseQuantity(),
                unit,
            ),
        )
        Text(
            text = stringResource(
                R.string.purchase_detail_line_unit_cost,
                line.readUnitCost.formatPurchaseUnitCost(),
            ),
        )
        line.appliedUnitCost?.let { appliedCost ->
            Text(
                text = stringResource(
                    R.string.purchase_detail_line_applied_cost,
                    appliedCost.formatPurchaseUnitCost(),
                ),
            )
        }
        line.inventoryQuantity?.let { quantity ->
            Text(
                text = stringResource(
                    R.string.purchase_detail_line_inventory_quantity,
                    quantity.formatPurchaseQuantity(),
                    unit,
                ),
            )
        }
        line.discount?.let { discount ->
            Text(
                text = stringResource(
                    R.string.purchase_detail_line_discount,
                    discount.formatCurrencyDecimal(line.total.currency.value),
                ),
            )
        }
        Text(
            text = stringResource(
                R.string.purchase_detail_line_tax,
                line.tax.formatForDisplay(),
            ),
        )
        Text(
            text = stringResource(
                R.string.purchase_detail_line_total,
                line.total.formatForDisplay(),
            ),
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun PurchaseMovementCard(
    movement: PurchaseReadMovement,
    modifier: Modifier = Modifier,
) {
    ReadCard(modifier = modifier) {
        Text(
            text = stringResource(movement.type.labelRes()),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            text = movement.productName,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(
                R.string.purchase_detail_movement_location,
                movement.locationName,
            ),
        )
        Text(
            text = stringResource(
                R.string.purchase_detail_movement_quantity,
                movement.quantityDelta.formatPurchaseQuantity(),
            ),
        )
        movement.unitCost?.let { cost ->
            Text(
                text = stringResource(
                    R.string.purchase_detail_movement_unit_cost,
                    cost.formatPurchaseUnitCost(),
                ),
            )
        }
        Text(
            text = stringResource(
                R.string.purchase_detail_movement_date,
                movement.occurredAt.formatForDisplay(),
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun RetainedImageCard(
    image: PurchaseRetainedImage,
    content: PurchasesContract.RetainedImageContent,
    modifier: Modifier = Modifier,
) {
    ReadCard(modifier = modifier) {
        Text(
            text = stringResource(R.string.purchase_detail_image_page, image.pageIndex + 1),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(
                R.string.purchase_detail_image_metadata,
                image.mimeType,
                image.widthPx,
                image.heightPx,
            ),
        )
        if (image.rotationDegrees != 0) {
            Text(
                text = stringResource(
                    R.string.purchase_detail_image_rotation,
                    image.rotationDegrees,
                ),
            )
        }
        if (image.cropLeftFraction != null) {
            Text(text = stringResource(R.string.purchase_detail_image_cropped))
        }
        when (content) {
            is PurchasesContract.RetainedImageContent.Available -> {
                AsyncImage(
                    model = sensitiveImageRequest(content.encodedBytes),
                    contentDescription = stringResource(
                        R.string.purchase_detail_image_description,
                        image.pageIndex + 1,
                    ),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = FacturaStockDesign.spacing.minimumTouchTarget * 4)
                        .graphicsLayer { rotationZ = image.rotationDegrees.toFloat() },
                )
                if (
                    content.source ==
                    PurchasesContract.RetainedImageContent.Available.Source.REMOTE
                ) {
                    Text(
                        text = stringResource(R.string.purchase_detail_image_remote_temporary),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            PurchasesContract.RetainedImageContent.Loading -> Text(
                text = stringResource(R.string.purchase_detail_image_loading),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )

            PurchasesContract.RetainedImageContent.Unavailable -> Text(
                text = stringResource(R.string.purchase_detail_image_not_retained),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )

            PurchasesContract.RetainedImageContent.RemoteUnavailable -> Text(
                text = stringResource(R.string.purchase_detail_image_remote_unavailable),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )

            PurchasesContract.RetainedImageContent.RemoteAccessDenied -> Text(
                text = stringResource(R.string.purchase_detail_image_remote_access_denied),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )

            PurchasesContract.RetainedImageContent.IntegrityRejected -> Text(
                text = stringResource(R.string.purchase_detail_image_integrity_rejected),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun AuditEventCard(
    event: PurchaseReadAuditEvent,
    modifier: Modifier = Modifier,
) {
    ReadCard(modifier = modifier) {
        Text(
            text = stringResource(event.eventType.labelRes()),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(
                R.string.purchase_detail_audit_date,
                event.occurredAt.formatForDisplay(),
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun IntegrityCard(
    detail: PurchaseReadDetail,
    modifier: Modifier = Modifier,
) {
    ReadCard(modifier = modifier) {
        DetailSectionTitle(stringResource(R.string.purchase_detail_integrity_section))
        Text(
            text = stringResource(R.string.purchase_detail_integrity_hash),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            text = detail.preparedLogicalHash,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = stringResource(R.string.purchase_detail_warnings),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
        )
        if (detail.acceptedWarnings.isEmpty()) {
            Text(text = stringResource(R.string.purchase_detail_no_warnings))
        } else {
            detail.acceptedWarnings.forEach { warning ->
                Text(text = warningLabel(warning))
            }
        }
    }
}

@Composable
private fun warningLabel(warning: String): String = when (warning) {
    WARNING_TOTAL_DIFFERENCE -> stringResource(R.string.summary_warning_total_difference)
    WARNING_LINES_TOTAL_DIFFERENCE ->
        stringResource(R.string.summary_warning_lines_total_difference)
    else -> stringResource(R.string.summary_warning_generic, warning)
}

@Composable
private fun ReadCard(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = FacturaStockDesign.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(FacturaStockDesign.spacing.xs),
        content = content,
    )
}

@Composable
private fun DetailSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier.semantics { heading() },
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

@StringRes
private fun StockMovementType.labelRes(): Int = when (this) {
    StockMovementType.PURCHASE -> R.string.purchase_detail_movement_purchase
    StockMovementType.SALE -> R.string.purchase_detail_movement_sale
    StockMovementType.SALE_VOID -> R.string.inventory_movement_sale_void
    StockMovementType.ADJUSTMENT -> R.string.purchase_detail_movement_adjustment
    StockMovementType.VOID -> R.string.purchase_detail_movement_void
}

@StringRes
private fun AuditEventType.labelRes(): Int = when (this) {
    AuditEventType.PURCHASE_POSTED -> R.string.purchase_detail_audit_posted
    // Los eventos SALE_POSTED no pertenecen a este timeline (purchaseId es NULL). La rama
    // mantiene exhaustividad ante una fila corrupta sin introducir una pantalla de ventas aquí.
    AuditEventType.SALE_POSTED -> R.string.purchase_detail_audit_posted
    AuditEventType.SALE_VOIDED -> R.string.sale_void_audit_label
    AuditEventType.PURCHASE_VOIDED -> R.string.purchase_detail_audit_voided
    AuditEventType.PURCHASE_DUPLICATE_OVERRIDE -> R.string.purchase_detail_audit_duplicate_override
    AuditEventType.STOCK_ADJUSTED -> R.string.purchase_detail_audit_stock_adjusted
    AuditEventType.SYNC_CONFLICT_RESOLVED -> R.string.purchase_detail_audit_sync_conflict_resolved
    AuditEventType.SYNC_RECONCILED -> R.string.purchase_detail_audit_sync_reconciled
    AuditEventType.CATALOG_SYNC_CONFLICT_RESOLVED ->
        R.string.purchase_detail_audit_sync_conflict_resolved
}

@StringRes
private fun PurchaseOverrideRole.labelRes(): Int = when (this) {
    PurchaseOverrideRole.OWNER -> R.string.purchase_void_role_owner
    PurchaseOverrideRole.MANAGER -> R.string.purchase_void_role_manager
    PurchaseOverrideRole.OPERATOR -> R.string.role_operator
}

private fun PurchaseStatus.statusTone(): StatusTone = when (this) {
    PurchaseStatus.DRAFT -> StatusTone.WARNING
    PurchaseStatus.POSTED -> StatusTone.SUCCESS
    PurchaseStatus.VOIDED -> StatusTone.ERROR
}

private fun BigDecimal.formatCurrencyDecimal(currencyCode: String): String =
    "$currencyCode ${formatPurchaseQuantity()}"

private const val WARNING_TOTAL_DIFFERENCE = "TOTAL_DIFFERENCE"
private const val WARNING_LINES_TOTAL_DIFFERENCE = "LINES_TOTAL_DIFFERENCE"
