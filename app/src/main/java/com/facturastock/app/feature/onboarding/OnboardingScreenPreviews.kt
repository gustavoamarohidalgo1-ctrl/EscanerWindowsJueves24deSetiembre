package com.facturastock.app.feature.onboarding

import androidx.compose.runtime.Composable
import com.facturastock.app.ui.preview.LargeFontPreview
import com.facturastock.app.ui.preview.ThemePreviews
import com.facturastock.app.ui.theme.FacturaStockTheme

@ThemePreviews
@Composable
private fun OnboardingScreenThemePreview() {
    FacturaStockTheme {
        OnboardingScreen(
            state = OnboardingContract.State(),
            onAction = {},
        )
    }
}

@LargeFontPreview
@Composable
private fun OnboardingScreenLargeFontPreview() {
    FacturaStockTheme {
        OnboardingScreen(
            state = OnboardingContract.State(),
            onAction = {},
        )
    }
}
