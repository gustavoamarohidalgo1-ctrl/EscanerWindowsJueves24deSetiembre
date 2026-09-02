package com.facturastock.app.feature.source

import androidx.compose.runtime.Composable
import com.facturastock.app.ui.preview.LargeFontPreview
import com.facturastock.app.ui.preview.ThemePreviews
import com.facturastock.app.ui.theme.FacturaStockTheme

@ThemePreviews
@Composable
private fun SourceScreenThemePreview() {
    FacturaStockTheme {
        SourceScreen()
    }
}

@LargeFontPreview
@Composable
private fun SourceScreenLargeFontPreview() {
    FacturaStockTheme {
        SourceScreen(
            state = SourceContract.State(
                cameraAccessDenied = true,
                invalidImage = SourceContract.InvalidImageReason.UNSUPPORTED_FORMAT,
            ),
        )
    }
}
