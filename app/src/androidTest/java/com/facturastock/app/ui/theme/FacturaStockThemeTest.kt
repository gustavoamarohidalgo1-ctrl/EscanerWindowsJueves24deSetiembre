package com.facturastock.app.ui.theme

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.view.ContextThemeWrapper
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.facturastock.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FacturaStockThemeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

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
            nightMode = Configuration.UI_MODE_NIGHT_NO,
            expectedMaterial = FacturaStockLightColors,
            expectedSemantic = LightSemanticColors,
        )
    }

    @Test
    fun defaultThemeFollowsSystemNightConfiguration() {
        assertDefaultThemeFor(
            nightMode = Configuration.UI_MODE_NIGHT_YES,
            expectedMaterial = FacturaStockDarkColors,
            expectedSemantic = DarkSemanticColors,
        )
    }

    @Test
    fun platformThemeUsesQualifiedLaunchBackgroundAndStatusBarAppearance() {
        val day = platformThemeSnapshot(Configuration.UI_MODE_NIGHT_NO)
        val night = platformThemeSnapshot(Configuration.UI_MODE_NIGHT_YES)

        assertEquals(context.getColor(R.color.window_background_light), day.windowBackground)
        assertEquals(context.getColor(R.color.window_background_dark), night.windowBackground)
        assertEquals(Color.TRANSPARENT, day.statusBarColor)
        assertEquals(Color.TRANSPARENT, night.statusBarColor)
        assertEquals(Color.TRANSPARENT, day.navigationBarColor)
        assertEquals(Color.TRANSPARENT, night.navigationBarColor)
        assertTrue(day.lightStatusBar)
        assertFalse(night.lightStatusBar)
        assertTrue(day.lightNavigationBar)
        assertFalse(night.lightNavigationBar)
    }

    private fun assertDefaultThemeFor(
        nightMode: Int,
        expectedMaterial: ColorScheme,
        expectedSemantic: FacturaStockSemanticColors,
    ) {
        val configuration = configurationFor(nightMode)
        assertPublishedTheme(
            content = { themedContent ->
                CompositionLocalProvider(LocalConfiguration provides configuration) {
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

    @Suppress("DEPRECATION")
    private fun platformThemeSnapshot(nightMode: Int): PlatformThemeSnapshot {
        val configurationContext = context.createConfigurationContext(configurationFor(nightMode))
        val themedContext = ContextThemeWrapper(configurationContext, R.style.Theme_FacturaStock)
        val attributes = themedContext.obtainStyledAttributes(
            intArrayOf(
                android.R.attr.windowBackground,
                android.R.attr.statusBarColor,
                android.R.attr.navigationBarColor,
                android.R.attr.windowLightStatusBar,
                android.R.attr.windowLightNavigationBar,
            ),
        )
        return try {
            PlatformThemeSnapshot(
                windowBackground = attributes.getColor(0, Color.MAGENTA),
                statusBarColor = attributes.getColor(1, Color.MAGENTA),
                navigationBarColor = attributes.getColor(2, Color.MAGENTA),
                lightStatusBar = attributes.getBoolean(3, false),
                lightNavigationBar = attributes.getBoolean(4, false),
            )
        } finally {
            attributes.recycle()
        }
    }

    private fun configurationFor(nightMode: Int): Configuration =
        Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
        }
}

private data class ThemeSnapshot(
    val material: ColorScheme,
    val semantic: FacturaStockSemanticColors,
)

private data class PlatformThemeSnapshot(
    val windowBackground: Int,
    val statusBarColor: Int,
    val navigationBarColor: Int,
    val lightStatusBar: Boolean,
    val lightNavigationBar: Boolean,
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
