package com.facturastock.app.feature.linking

import androidx.compose.runtime.Composable
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.UnitOfMeasure
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.usecase.ProductMatchCandidate
import com.facturastock.app.domain.usecase.ProductMatchReason
import com.facturastock.app.feature.linking.ProductLinkingContract.LineLinking
import com.facturastock.app.feature.linking.ProductLinkingContract.LinkStatus
import com.facturastock.app.feature.linking.ProductLinkingContract.State
import com.facturastock.app.ui.preview.LargeFontPreview
import com.facturastock.app.ui.preview.ThemePreviews
import com.facturastock.app.ui.theme.FacturaStockTheme
import java.time.Instant
import java.util.UUID

@ThemePreviews
@Composable
private fun ProductLinkingScreenThemePreview() {
    FacturaStockTheme {
        ProductLinkingScreen(
            state = previewState(),
            onAction = {},
        )
    }
}

@LargeFontPreview
@Composable
private fun ProductLinkingScreenLargeFontPreview() {
    FacturaStockTheme {
        ProductLinkingScreen(
            state = previewState(),
            onAction = {},
        )
    }
}

private val previewNow: Instant = Instant.parse("2026-08-10T12:00:00Z")

private fun previewUuid(seed: Int): UUID =
    UUID.fromString("00000000-0000-0000-0000-%012d".format(seed))

private fun previewState(): State {
    val businessId = BusinessId.from(previewUuid(100))
    val unitId = UnitId.from(previewUuid(101))
    val lines = listOf(
        LineLinking(
            lineId = LineId.from(previewUuid(1)),
            position = 0,
            description = "ARROZ EXTRA COSTEÑO X 50KG",
            code = "ARR-50",
            status = LinkStatus.AUTO_LINKED,
            linkedProductName = "Arroz Extra Costeño",
            linkReason = ProductMatchReason.EXACT_NAME,
        ),
        LineLinking(
            lineId = LineId.from(previewUuid(2)),
            position = 1,
            description = "AZUCAR RUBIA X KG",
            code = null,
            status = LinkStatus.NEEDS_CHOICE,
            linkedProductName = null,
            linkReason = null,
        ),
        LineLinking(
            lineId = LineId.from(previewUuid(3)),
            position = 2,
            description = "GALLETA SORPRESA DISPLAY",
            code = null,
            status = LinkStatus.NO_MATCH,
            linkedProductName = null,
            linkReason = null,
        ),
    )
    val unit = UnitOfMeasure(
        unitId = unitId,
        businessId = businessId,
        code = "NIU",
        name = "Unidad",
        status = CatalogStatus.ACTIVE,
        createdAt = previewNow,
        updatedAt = previewNow,
    )
    val candidates = listOf(
        ProductMatchCandidate(
            product = Product(
                productId = ProductId.from(previewUuid(201)),
                businessId = businessId,
                unitId = unitId,
                name = "Azúcar Rubia",
                sku = "SKU-AZU-01",
                status = CatalogStatus.ACTIVE,
                createdAt = previewNow,
                updatedAt = previewNow,
            ),
            reason = ProductMatchReason.SIMILAR_NAME,
            confidencePermille = 853,
        ),
        ProductMatchCandidate(
            product = Product(
                productId = ProductId.from(previewUuid(202)),
                businessId = businessId,
                unitId = unitId,
                name = "Azúcar Blanca",
                status = CatalogStatus.ACTIVE,
                createdAt = previewNow,
                updatedAt = previewNow,
            ),
            reason = ProductMatchReason.SIMILAR_NAME,
            confidencePermille = 690,
        ),
    )
    return State(
        draftId = DraftId.from(previewUuid(300)),
        initialLineId = lines[1].lineId,
        isLoading = false,
        lines = lines,
        currentLineId = lines[1].lineId,
        candidates = candidates,
        searchQuery = lines[1].description,
        units = listOf(unit),
    )
}
