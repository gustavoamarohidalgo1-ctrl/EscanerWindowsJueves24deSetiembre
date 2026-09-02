package com.facturastock.app.feature.home

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.facturastock.app.domain.model.Business
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.HomeDashboardRead
import com.facturastock.app.domain.model.HomeDashboardSnapshot
import com.facturastock.app.domain.model.HomeDraftOverview
import com.facturastock.app.domain.model.HomeInventoryOverview
import com.facturastock.app.domain.model.HomePurchaseOverview
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.RecentDraft
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.ui.preview.LargeFontPreview
import com.facturastock.app.ui.preview.ThemePreviews
import com.facturastock.app.ui.theme.FacturaStockTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@ThemePreviews
@Composable
private fun HomeScreenThemePreview() {
    FacturaStockTheme {
        HomeScreen(
            dashboard = previewDashboard(),
            drafts = previewDraftItems(),
        )
    }
}

@LargeFontPreview
@Composable
private fun HomeScreenLargeFontPreview() {
    FacturaStockTheme {
        HomeScreen(
            dashboard = previewDashboard(),
            drafts = previewDraftItems(),
        )
    }
}

@Preview(
    name = "Centro de operaciones · tablet",
    group = "Tablet",
    widthDp = 900,
    heightDp = 600,
    showBackground = true,
    uiMode = UI_MODE_NIGHT_YES,
)
@Composable
private fun HomeScreenTabletPreview() {
    FacturaStockTheme {
        HomeScreen(
            dashboard = previewDashboard(),
            drafts = previewDraftItems(),
        )
    }
}

private fun previewDashboard(): HomeDashboardSnapshot {
    val drafts = previewDraftItems()
    return HomeDashboardSnapshot(
        business = Business(
            businessId = PREVIEW_BUSINESS_ID,
            legalName = "Bodega Mayda E.I.R.L.",
            tradeName = "Bodega Mayda",
            createdAt = PREVIEW_TIME.minusSeconds(86_400),
            updatedAt = PREVIEW_TIME,
        ),
        isDemoMode = false,
        zoneId = ZoneId.of("America/Lima"),
        overview = HomeDashboardRead(
            drafts = HomeDraftOverview(
                openCount = drafts.size,
                recent = drafts.map { RecentDraft(it.draft, it.supplierName) },
            ),
            inventory = HomeInventoryOverview(
                productCount = 38,
                availableProductCount = 31,
                attentionProductCount = 7,
                withoutStockCount = 4,
                withoutSalePriceCount = 3,
                negativeStockCount = 0,
            ),
            purchases = HomePurchaseOverview(
                postedCount = 14,
                syncProblemCount = 0,
            ),
        ),
        observedAt = PREVIEW_TIME,
    )
}

private fun previewDraftItems(): List<HomeContract.HomeDraftItem> {
    return listOf(
        HomeContract.HomeDraftItem(
            draft = InvoiceDraft(
                draftId = DraftId.from(
                    UUID.fromString("00000000-0000-4000-8000-0000000000a1"),
                ),
                businessId = PREVIEW_BUSINESS_ID,
                status = DraftStatus.NEEDS_REVIEW,
                documentNumberNormalized = "F001-001234",
                issueDate = LocalDate.of(2026, 8, 1),
                currency = CurrencyCode.of("PEN"),
                total = Money.ofMinor(24850, CurrencyCode.of("PEN")),
                createdAt = Instant.parse("2026-08-01T10:15:00Z"),
                updatedAt = Instant.parse("2026-08-03T18:40:00Z"),
            ),
            supplierName = "Distribuidora Andina S.A.C.",
        ),
        HomeContract.HomeDraftItem(
            draft = InvoiceDraft(
                draftId = DraftId.from(
                    UUID.fromString("00000000-0000-4000-8000-0000000000a2"),
                ),
                businessId = PREVIEW_BUSINESS_ID,
                status = DraftStatus.OCR_PROCESSING,
                supplierRucNormalized = "20512345678",
                createdAt = Instant.parse("2026-08-02T09:00:00Z"),
                updatedAt = Instant.parse("2026-08-02T09:05:00Z"),
            ),
            supplierName = null,
        ),
        HomeContract.HomeDraftItem(
            draft = InvoiceDraft(
                draftId = DraftId.from(
                    UUID.fromString("00000000-0000-4000-8000-0000000000a3"),
                ),
                businessId = PREVIEW_BUSINESS_ID,
                status = DraftStatus.CREATED,
                createdAt = Instant.parse("2026-08-01T08:00:00Z"),
                updatedAt = Instant.parse("2026-08-01T08:00:00Z"),
            ),
            supplierName = null,
        ),
    )
}

private val PREVIEW_BUSINESS_ID = BusinessId.from(
    UUID.fromString("323e4567-e89b-42d3-a456-426614174000"),
)
private val PREVIEW_TIME: Instant = Instant.parse("2026-08-28T15:30:00Z")
