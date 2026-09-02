package com.facturastock.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class FacturaStockSpacing(
    val borderThin: Dp = 1.dp,
    val xxs: Dp = 4.dp,
    val xs: Dp = 8.dp,
    val sm: Dp = 12.dp,
    val md: Dp = 16.dp,
    val lg: Dp = 24.dp,
    val xl: Dp = 32.dp,
    val xxl: Dp = 48.dp,
    val minimumTouchTarget: Dp = 48.dp,
    val comfortableTouchTarget: Dp = 56.dp,
    val iconSmall: Dp = 18.dp,
    val icon: Dp = 24.dp,
    val iconEmphasis: Dp = 32.dp,
    val iconLarge: Dp = 48.dp,
    val iconContainer: Dp = 56.dp,
    val contentMaxWidth: Dp = 720.dp,
    val contentWideMaxWidth: Dp = 960.dp,
    val dialogMaxWidth: Dp = 560.dp,
    val navigationRailWidth: Dp = 112.dp,
    val topBarMinHeight: Dp = 64.dp,
    val expandedNavigationBreakpoint: Dp = 840.dp,
    val expandedNavigationMinHeight: Dp = 480.dp,
)

internal val LocalFacturaStockSpacing = staticCompositionLocalOf { FacturaStockSpacing() }
internal val LocalFacturaStockSemanticColors = staticCompositionLocalOf {
    LightSemanticColors
}

object FacturaStockDesign {
    val spacing: FacturaStockSpacing
        @androidx.compose.runtime.Composable
        get() = LocalFacturaStockSpacing.current

    val semanticColors: FacturaStockSemanticColors
        @androidx.compose.runtime.Composable
        get() = LocalFacturaStockSemanticColors.current
}
