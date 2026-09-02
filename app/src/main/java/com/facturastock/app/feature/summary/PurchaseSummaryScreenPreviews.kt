package com.facturastock.app.feature.summary

import androidx.compose.runtime.Composable
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.PrepareBlockerCode
import com.facturastock.app.domain.model.PreparedPurchase
import com.facturastock.app.domain.model.PreparedPurchaseLine
import com.facturastock.app.domain.model.PurchaseDocumentType
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.UnitCost
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.SupplierId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.usecase.WARNING_LINES_TOTAL_DIFFERENCE
import com.facturastock.app.feature.summary.PurchaseSummaryContract.BlockerItem
import com.facturastock.app.feature.summary.PurchaseSummaryContract.FinanceSummary
import com.facturastock.app.feature.summary.PurchaseSummaryContract.Mode
import com.facturastock.app.feature.summary.PurchaseSummaryContract.State
import com.facturastock.app.ui.preview.LargeFontPreview
import com.facturastock.app.ui.preview.ThemePreviews
import com.facturastock.app.ui.theme.FacturaStockTheme
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@ThemePreviews
@Composable
private fun PurchaseSummaryEditingPreview() {
    FacturaStockTheme {
        PurchaseSummaryScreen(
            state = editingPreviewState(),
            onAction = {},
        )
    }
}

@ThemePreviews
@Composable
private fun PurchaseSummaryPreparedPreview() {
    FacturaStockTheme {
        PurchaseSummaryScreen(
            state = preparedPreviewState(),
            onAction = {},
        )
    }
}

@LargeFontPreview
@Composable
private fun PurchaseSummaryPreparedLargeFontPreview() {
    FacturaStockTheme {
        PurchaseSummaryScreen(
            state = preparedPreviewState(),
            onAction = {},
        )
    }
}

private val previewNow: Instant = Instant.parse("2026-08-10T12:00:00Z")
private val previewPen: CurrencyCode = CurrencyCode.of("PEN")

private fun previewUuid(seed: Int): UUID =
    UUID.fromString("00000000-0000-0000-0000-%012d".format(seed))

private fun editingPreviewState(): State = State(
    draftId = DraftId.from(previewUuid(300)),
    isLoading = false,
    mode = Mode.EDITING,
    blockers = listOf(
        BlockerItem(code = PrepareBlockerCode.LINE_PRODUCT_MISSING, linePosition = 1),
        BlockerItem(
            code = PrepareBlockerCode.ROUNDING_ACCEPTANCE_REQUIRED,
        ),
    ),
    roundingDifference = "S/ 0.01",
    finance = FinanceSummary(
        lineSum = "S/ 117.99",
        invoiceTotal = "S/ 118.00",
        difference = "S/ 0.01",
        hasDifference = true,
    ),
)

private fun preparedPreviewState(): State {
    val draftId = DraftId.from(previewUuid(300))
    val businessId = BusinessId.from(previewUuid(100))
    val unitId = UnitId.from(previewUuid(101))
    val purchase = PreparedPurchase(
        draftId = draftId,
        businessId = businessId,
        supplierId = SupplierId.from(previewUuid(102)),
        supplierRuc = "20123456789",
        supplierLegalName = "DISTRIBUIDORA PACIFICO SAC",
        documentType = PurchaseDocumentType.INVOICE,
        documentNumber = "F001-00000042",
        issueDate = LocalDate.of(2026, 8, 8),
        currency = previewPen,
        lines = listOf(
            PreparedPurchaseLine(
                lineId = LineId.from(previewUuid(1)),
                position = 0,
                productId = ProductId.from(previewUuid(201)),
                unitId = unitId,
                description = "Arroz Extra Costeño",
                quantity = Quantity.of("2"),
                unitCost = UnitCost.of(Money.ofMinor(5_000, previewPen).toMajor(), previewPen),
                lineTotal = Money.ofMinor(10_000, previewPen),
                linkConfidence = 1_000,
            ),
            PreparedPurchaseLine(
                lineId = LineId.from(previewUuid(2)),
                position = 1,
                productId = ProductId.from(previewUuid(202)),
                unitId = unitId,
                description = "Azúcar Rubia",
                quantity = Quantity.of("1"),
                lineTotal = Money.ofMinor(800, previewPen),
                linkConfidence = 950,
            ),
        ),
        subtotal = Money.ofMinor(9_153, previewPen),
        tax = Money.ofMinor(1_647, previewPen),
        otherCharges = null,
        total = Money.ofMinor(10_800, previewPen),
        acceptedWarnings = listOf("TOTAL_DIFFERENCE", WARNING_LINES_TOTAL_DIFFERENCE),
        logicalHash = "9f2c4a6b8d1e3f5a7c9b0d2e4f6a8b1c3d5e7f9a0b2c4d6e8f0a1b3c5d7e9f0a",
        preparedAt = previewNow,
    )
    return State(
        draftId = draftId,
        isLoading = false,
        mode = Mode.PREPARED,
        prepared = purchase,
    )
}
