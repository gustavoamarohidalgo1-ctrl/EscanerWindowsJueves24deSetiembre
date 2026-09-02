package com.facturastock.app.feature.sync

import androidx.compose.runtime.Composable
import com.facturastock.app.domain.model.AccountSession
import com.facturastock.app.domain.model.BusinessRole
import com.facturastock.app.domain.model.BalanceDifference
import com.facturastock.app.domain.model.CloudBusinessLink
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.OutboxOperationStatus
import com.facturastock.app.domain.model.PurchaseDocumentType
import com.facturastock.app.domain.model.PurchaseReadSummary
import com.facturastock.app.domain.model.PurchaseStatus
import com.facturastock.app.domain.model.PurchaseSyncState
import com.facturastock.app.domain.model.ReconciliationReport
import com.facturastock.app.domain.model.RemotePurchaseChange
import com.facturastock.app.domain.model.RemotePurchaseDescription
import com.facturastock.app.domain.model.SyncCursor
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.repository.OutboxOperationView
import com.facturastock.app.ui.preview.LargeFontPreview
import com.facturastock.app.ui.preview.ThemePreviews
import com.facturastock.app.ui.theme.FacturaStockTheme
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@ThemePreviews
@Composable
private fun SyncScreenActivePreview() {
    FacturaStockTheme {
        SyncScreen(
            state = previewActiveState(),
            onAction = {},
        )
    }
}

@LargeFontPreview
@Composable
private fun SyncScreenUnavailablePreview() {
    FacturaStockTheme {
        SyncScreen(
            state = SyncContract.State(
                session = AccountSession.Unavailable,
                activeBusinessId = previewLocalBusinessId,
                remoteLedgerAvailable = false,
            ),
            onAction = {},
        )
    }
}

private val previewLocalBusinessId: BusinessId = BusinessId.from(
    UUID.fromString("123e4567-e89b-42d3-a456-426614174000"),
)

private val previewCloudBusinessId: BusinessId = BusinessId.from(
    UUID.fromString("123e4567-e89b-42d3-a456-426614174001"),
)

private val previewPurchaseId: PurchaseId = PurchaseId.from(
    UUID.fromString("123e4567-e89b-42d3-a456-426614174002"),
)

private val previewNow: Instant = Instant.parse("2026-08-16T12:00:00Z")

private val previewFailedOperation = OutboxOperationView(
    operationId = "op-preview-failed",
    operationType = "SYNC_PURCHASE",
    purchaseId = previewPurchaseId,
    status = OutboxOperationStatus.FAILED,
    attemptCount = 3,
    lastError = "NETWORK_UNAVAILABLE",
    nextAttemptAt = previewNow,
    updatedAt = previewNow,
    conflictRemotePurchaseId = null,
    conflictReceiptId = null,
)

private val previewConflictOperation = OutboxOperationView(
    operationId = "op-preview-conflict",
    operationType = "SYNC_PURCHASE",
    purchaseId = previewPurchaseId,
    status = OutboxOperationStatus.CONFLICT,
    attemptCount = 1,
    lastError = "DUPLICATE_DOCUMENT",
    nextAttemptAt = null,
    updatedAt = previewNow,
    conflictRemotePurchaseId = "223e4567-e89b-42d3-a456-426614174003",
    conflictReceiptId = "receipt-preview",
)

private val previewLocalPurchase = PurchaseReadSummary(
    purchaseId = previewPurchaseId,
    businessId = previewLocalBusinessId,
    sourceDraftId = DraftId.from(UUID.fromString("123e4567-e89b-42d3-a456-426614174004")),
    supplierRuc = "20123456786",
    supplierLegalName = "Distribuidora Andina SAC",
    documentType = PurchaseDocumentType.INVOICE,
    documentSeries = "F001",
    documentNumber = "12345",
    issueDate = LocalDate.parse("2026-08-10"),
    currency = CurrencyCode.of("PEN"),
    total = Money.ofMinor(11800, CurrencyCode.of("PEN")),
    status = PurchaseStatus.POSTED,
    syncState = PurchaseSyncState.CONFLICT,
    lineCount = 2,
    productCount = 2,
    postedAt = previewNow,
)

private val previewRemoteDescription = RemotePurchaseDescription(
    purchaseId = "223e4567-e89b-42d3-a456-426614174003",
    status = PurchaseStatus.POSTED,
    documentType = PurchaseDocumentType.INVOICE.name,
    documentSeries = "F001",
    documentNumber = "12345",
    issueDate = "2026-08-10",
    currency = "PEN",
    supplierRuc = "20123456786",
    supplierLegalName = "Distribuidora Andina SAC",
    totalMinorUnits = 11800,
    receiptId = "receipt-preview",
    syncedAtMillis = previewNow.toEpochMilli(),
    syncedBy = "uid-preview",
)

private val previewRemoteOnlyChange = RemotePurchaseChange(
    seq = 41,
    purchaseId = "323e4567-e89b-42d3-a456-426614174005",
    status = PurchaseStatus.POSTED,
    documentType = PurchaseDocumentType.INVOICE.name,
    documentSeries = "F002",
    documentNumber = "777",
    issueDate = "2026-08-12",
    currency = "PEN",
    supplierRuc = "20445566778",
    supplierLegalName = "Mayorista Central EIRL",
    totalMinorUnits = 5900,
    movementSummary = emptyList(),
    receiptId = "receipt-preview-2",
    syncedAtMillis = previewNow.toEpochMilli(),
    syncedBy = "uid-preview",
)

private fun previewActiveState(): SyncContract.State = SyncContract.State(
    session = AccountSession.Active(
        uid = "uid-preview",
        email = "duena@bodega.pe",
        link = CloudBusinessLink(
            localBusinessId = previewLocalBusinessId,
            cloudBusinessId = previewCloudBusinessId,
            role = BusinessRole.OWNER,
        ),
    ),
    activeBusinessId = previewLocalBusinessId,
    remoteLedgerAvailable = true,
    outbox = listOf(previewFailedOperation, previewConflictOperation),
    purchases = listOf(previewLocalPurchase),
    cursor = SyncCursor(seq = 40, lastPullAt = previewNow),
    remoteDescriptions = mapOf(
        previewConflictOperation.operationId to
            SyncContract.RemoteDescriptionState.Loaded(previewRemoteDescription),
    ),
    report = ReconciliationReport(
        businessId = previewCloudBusinessId,
        latestSeq = 41,
        matched = emptyList(),
        ambiguous = emptyList(),
        remoteOnly = listOf(previewRemoteOnlyChange),
        balanceDifferences = listOf(
            BalanceDifference(
                productId = ProductId.from(
                    UUID.fromString("123e4567-e89b-42d3-a456-426614174006"),
                ),
                productName = "Arroz extra 50 kg",
                localOnHand = BigDecimal("10"),
                remoteNet = BigDecimal("14"),
            ),
        ),
        unlinkedRemoteProducts = emptyList(),
        comparedProductCount = 3,
        generatedAt = previewNow,
    ),
)
