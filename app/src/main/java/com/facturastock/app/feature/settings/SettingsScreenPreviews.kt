package com.facturastock.app.feature.settings

import androidx.compose.runtime.Composable
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.model.Business
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.ui.preview.LargeFontPreview
import com.facturastock.app.ui.preview.ThemePreviews
import com.facturastock.app.ui.theme.FacturaStockTheme
import java.time.Instant
import java.util.UUID

@ThemePreviews
@Composable
private fun SettingsScreenThemePreview() {
    FacturaStockTheme {
        SettingsScreen(
            state = previewState(),
            onAction = {},
        )
    }
}

@LargeFontPreview
@Composable
private fun SettingsScreenLargeFontPreview() {
    FacturaStockTheme {
        SettingsScreen(
            state = previewState(),
            onAction = {},
        )
    }
}

private fun previewState(): SettingsContract.State {
    val businessId = BusinessId.from(
        UUID.fromString("123e4567-e89b-42d3-a456-426614174000"),
    )
    return SettingsContract.State(
        config = AppConfiguration.defaults().copy(businessId = businessId),
        business = Business(
            businessId = businessId,
            legalName = "Bodega Vista Alegre EIRL",
            tradeName = "Bodega Vista Alegre",
            ruc = "20123456789",
            createdAt = Instant.parse("2026-08-08T12:00:00Z"),
            updatedAt = Instant.parse("2026-08-08T12:00:00Z"),
        ),
        legalName = "Bodega Vista Alegre EIRL",
        tradeName = "Bodega Vista Alegre",
        ruc = "20123456789",
    )
}
