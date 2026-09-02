package com.facturastock.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
    @Test
    fun `light material roles meet text contrast`() {
        assertMaterialContrast("light", FacturaStockLightColors)
    }

    @Test
    fun `dark material roles meet text contrast`() {
        assertMaterialContrast("dark", FacturaStockDarkColors)
    }

    @Test
    fun `light semantic status roles meet text contrast`() {
        assertSemanticContrast("light", LightSemanticColors)
    }

    @Test
    fun `dark semantic status roles meet text contrast`() {
        assertSemanticContrast("dark", DarkSemanticColors)
    }

    @Test
    fun `light and dark palettes preserve their luminance polarity`() {
        assertTrue(
            "Light background must be brighter than dark background",
            FacturaStockLightColors.background.luminance() >
                FacturaStockDarkColors.background.luminance(),
        )
        assertTrue(
            "Light surface must be brighter than dark surface",
            FacturaStockLightColors.surface.luminance() >
                FacturaStockDarkColors.surface.luminance(),
        )
    }

    @Test
    fun `navigation selection indicators meet non text contrast`() {
        assertNonTextContrast(
            label = "light navigation selection",
            background = FacturaStockLightColors.surfaceContainer,
            indicator = FacturaStockLightColors.primary,
        )
        assertNonTextContrast(
            label = "dark navigation selection",
            background = FacturaStockDarkColors.surfaceContainer,
            indicator = FacturaStockDarkColors.primary,
        )
    }

    @Test
    fun `interactive card outlines meet non text contrast`() {
        assertNonTextContrast(
            label = "light interactive card outline",
            background = FacturaStockLightColors.surfaceContainerLow,
            indicator = FacturaStockLightColors.outline,
        )
        assertNonTextContrast(
            label = "dark interactive card outline",
            background = FacturaStockDarkColors.surfaceContainerLow,
            indicator = FacturaStockDarkColors.outline,
        )
    }

    @Test
    fun `brand identity remains blue instead of green`() {
        listOf(
            "light primary" to FacturaStockLightColors.primary,
            "light primary container" to FacturaStockLightColors.primaryContainer,
            "dark primary" to FacturaStockDarkColors.primary,
            "dark primary container" to FacturaStockDarkColors.primaryContainer,
        ).forEach { (role, color) ->
            assertTrue(
                "$role must keep blue as its strongest chromatic channel",
                color.blue > color.green && color.blue > color.red,
            )
        }
    }

    @Test
    fun `copper accent remains visually distinct from warning yellow`() {
        listOf(
            "light roles" to
                (FacturaStockLightColors.tertiary to LightSemanticColors.warning),
            "light containers" to
                (
                    FacturaStockLightColors.tertiaryContainer to
                        LightSemanticColors.warningContainer
                    ),
            "dark roles" to
                (FacturaStockDarkColors.tertiary to DarkSemanticColors.warning),
            "dark containers" to
                (
                    FacturaStockDarkColors.tertiaryContainer to
                        DarkSemanticColors.warningContainer
                    ),
        ).forEach { (role, colors) ->
            assertTrue(
                "$role must keep copper actions distinct from warning yellow",
                rgbDistance(colors.first, colors.second) >= 0.1f,
            )
        }
    }

    private fun assertMaterialContrast(palette: String, colors: ColorScheme) {
        val pairs = listOf(
            "primary/onPrimary" to (colors.primary to colors.onPrimary),
            "primaryContainer/onPrimaryContainer" to
                (colors.primaryContainer to colors.onPrimaryContainer),
            "secondary/onSecondary" to (colors.secondary to colors.onSecondary),
            "secondaryContainer/onSecondaryContainer" to
                (colors.secondaryContainer to colors.onSecondaryContainer),
            "tertiary/onTertiary" to (colors.tertiary to colors.onTertiary),
            "tertiaryContainer/onTertiaryContainer" to
                (colors.tertiaryContainer to colors.onTertiaryContainer),
            "error/onError" to (colors.error to colors.onError),
            "errorContainer/onErrorContainer" to
                (colors.errorContainer to colors.onErrorContainer),
            "background/onBackground" to (colors.background to colors.onBackground),
            "surface/onSurface" to (colors.surface to colors.onSurface),
            "surfaceDim/onSurface" to (colors.surfaceDim to colors.onSurface),
            "surfaceBright/onSurface" to (colors.surfaceBright to colors.onSurface),
            "surfaceContainerLowest/onSurface" to
                (colors.surfaceContainerLowest to colors.onSurface),
            "surfaceContainerLow/onSurface" to (colors.surfaceContainerLow to colors.onSurface),
            "surfaceContainer/onSurface" to (colors.surfaceContainer to colors.onSurface),
            "surfaceContainerHigh/onSurface" to
                (colors.surfaceContainerHigh to colors.onSurface),
            "surfaceContainerHighest/onSurface" to
                (colors.surfaceContainerHighest to colors.onSurface),
            "surfaceVariant/onSurfaceVariant" to
                (colors.surfaceVariant to colors.onSurfaceVariant),
            "inverseSurface/inverseOnSurface" to
                (colors.inverseSurface to colors.inverseOnSurface),
        )
        pairs.forEach { (role, colors) ->
            assertContrast(
                label = "$palette $role",
                background = colors.first,
                foreground = colors.second,
            )
        }
    }

    private fun assertSemanticContrast(palette: String, colors: FacturaStockSemanticColors) {
        val pairs = listOf(
            "success/onSuccess" to (colors.success to colors.onSuccess),
            "successContainer/onSuccessContainer" to
                (colors.successContainer to colors.onSuccessContainer),
            "warning/onWarning" to (colors.warning to colors.onWarning),
            "warningContainer/onWarningContainer" to
                (colors.warningContainer to colors.onWarningContainer),
            "info/onInfo" to (colors.info to colors.onInfo),
            "infoContainer/onInfoContainer" to (colors.infoContainer to colors.onInfoContainer),
        )
        pairs.forEach { (role, colors) ->
            assertContrast(
                label = "$palette $role",
                background = colors.first,
                foreground = colors.second,
            )
        }
    }

    private fun assertContrast(label: String, background: Color, foreground: Color) {
        val backgroundLuminance = background.luminance()
        val foregroundLuminance = foreground.luminance()
        val ratio = (max(backgroundLuminance, foregroundLuminance) + 0.05f) /
            (min(backgroundLuminance, foregroundLuminance) + 0.05f)

        assertTrue(
            "$label contrast ratio $ratio is below 4.5",
            ratio >= 4.5f,
        )
    }

    private fun assertNonTextContrast(label: String, background: Color, indicator: Color) {
        val backgroundLuminance = background.luminance()
        val indicatorLuminance = indicator.luminance()
        val ratio = (max(backgroundLuminance, indicatorLuminance) + 0.05f) /
            (min(backgroundLuminance, indicatorLuminance) + 0.05f)

        assertTrue(
            "$label contrast ratio $ratio is below 3.0",
            ratio >= 3f,
        )
    }

    private fun rgbDistance(first: Color, second: Color): Float {
        val red = first.red - second.red
        val green = first.green - second.green
        val blue = first.blue - second.blue
        return sqrt(red * red + green * green + blue * blue)
    }
}
