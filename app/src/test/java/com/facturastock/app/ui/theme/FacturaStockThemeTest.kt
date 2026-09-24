package com.facturastock.app.ui.theme

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.LocalSystemTheme
import androidx.compose.ui.SystemTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * El tema publica paletas completas y, por defecto, sigue el tema del sistema operativo
 * (en escritorio, `LocalSystemTheme`, que la ventana alimenta desde Windows).
 */
class FacturaStockThemeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun explicitLightModePublishesCompleteLightPalettes() {
        assertPublishedTheme(
            content = { FacturaStockTheme(darkTheme = false, content = it) },
            expectedMaterial = FacturaStockLightColors,
            expectedSemantic = LightSemanticColors,
        )
    }

    @Test
    fun explicitDarkModePublishesCompleteDarkPalettes() {
        assertPublishedTheme(
            content = { FacturaStockTheme(darkTheme = true, content = it) },
            expectedMaterial = FacturaStockDarkColors,
            expectedSemantic = DarkSemanticColors,
        )
    }

    @Test
    fun defaultThemeFollowsSystemDayConfiguration() {
        assertDefaultThemeFor(
            systemTheme = SystemTheme.Light,
            expectedMaterial = FacturaStockLightColors,
            expectedSemantic = LightSemanticColors,
        )
    }

    @Test
    fun defaultThemeFollowsSystemNightConfiguration() {
        assertDefaultThemeFor(
            systemTheme = SystemTheme.Dark,
            expectedMaterial = FacturaStockDarkColors,
            expectedSemantic = DarkSemanticColors,
        )
    }

    @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
    private fun assertDefaultThemeFor(
        systemTheme: SystemTheme,
        expectedMaterial: ColorScheme,
        expectedSemantic: FacturaStockSemanticColors,
    ) {
        assertPublishedTheme(
            content = { themedContent ->
                CompositionLocalProvider(LocalSystemTheme provides systemTheme) {
                    FacturaStockTheme(content = themedContent)
                }
            },
            expectedMaterial = expectedMaterial,
            expectedSemantic = expectedSemantic,
        )
    }

    private fun assertPublishedTheme(
        content: @androidx.compose.runtime.Composable (
            @androidx.compose.runtime.Composable () -> Unit,
        ) -> Unit,
        expectedMaterial: ColorScheme,
        expectedSemantic: FacturaStockSemanticColors,
    ) {
        var actual: ThemeSnapshot? = null
        composeRule.setContent {
            content {
                val material = MaterialTheme.colorScheme
                val semantic = FacturaStockDesign.semanticColors
                SideEffect { actual = ThemeSnapshot(material, semantic) }
            }
        }

        composeRule.runOnIdle {
            val snapshot = requireNotNull(actual) { "Theme did not publish a snapshot" }
            assertColorSchemeEquals(expectedMaterial, snapshot.material)
            assertEquals(expectedSemantic, snapshot.semantic)
        }
    }
}

private data class ThemeSnapshot(
    val material: ColorScheme,
    val semantic: FacturaStockSemanticColors,
)

private fun assertColorSchemeEquals(expected: ColorScheme, actual: ColorScheme) {
    val roles = listOf(
        "primary" to (expected.primary to actual.primary),
        "onPrimary" to (expected.onPrimary to actual.onPrimary),
        "primaryContainer" to (expected.primaryContainer to actual.primaryContainer),
        "onPrimaryContainer" to (expected.onPrimaryContainer to actual.onPrimaryContainer),
        "secondary" to (expected.secondary to actual.secondary),
        "onSecondary" to (expected.onSecondary to actual.onSecondary),
        "secondaryContainer" to (expected.secondaryContainer to actual.secondaryContainer),
        "onSecondaryContainer" to
            (expected.onSecondaryContainer to actual.onSecondaryContainer),
        "tertiary" to (expected.tertiary to actual.tertiary),
        "onTertiary" to (expected.onTertiary to actual.onTertiary),
        "tertiaryContainer" to (expected.tertiaryContainer to actual.tertiaryContainer),
        "onTertiaryContainer" to (expected.onTertiaryContainer to actual.onTertiaryContainer),
        "error" to (expected.error to actual.error),
        "onError" to (expected.onError to actual.onError),
        "errorContainer" to (expected.errorContainer to actual.errorContainer),
        "onErrorContainer" to (expected.onErrorContainer to actual.onErrorContainer),
        "background" to (expected.background to actual.background),
        "onBackground" to (expected.onBackground to actual.onBackground),
        "surface" to (expected.surface to actual.surface),
        "onSurface" to (expected.onSurface to actual.onSurface),
        "surfaceDim" to (expected.surfaceDim to actual.surfaceDim),
        "surfaceBright" to (expected.surfaceBright to actual.surfaceBright),
        "surfaceContainerLowest" to
            (expected.surfaceContainerLowest to actual.surfaceContainerLowest),
        "surfaceContainerLow" to (expected.surfaceContainerLow to actual.surfaceContainerLow),
        "surfaceContainer" to (expected.surfaceContainer to actual.surfaceContainer),
        "surfaceContainerHigh" to
            (expected.surfaceContainerHigh to actual.surfaceContainerHigh),
        "surfaceContainerHighest" to
            (expected.surfaceContainerHighest to actual.surfaceContainerHighest),
        "surfaceVariant" to (expected.surfaceVariant to actual.surfaceVariant),
        "onSurfaceVariant" to (expected.onSurfaceVariant to actual.onSurfaceVariant),
        "outline" to (expected.outline to actual.outline),
        "outlineVariant" to (expected.outlineVariant to actual.outlineVariant),
        "inverseSurface" to (expected.inverseSurface to actual.inverseSurface),
        "inverseOnSurface" to (expected.inverseOnSurface to actual.inverseOnSurface),
        "inversePrimary" to (expected.inversePrimary to actual.inversePrimary),
        "surfaceTint" to (expected.surfaceTint to actual.surfaceTint),
        "scrim" to (expected.scrim to actual.scrim),
    )
    roles.forEach { (role, colors) ->
        assertEquals(role, colors.first, colors.second)
    }
}
