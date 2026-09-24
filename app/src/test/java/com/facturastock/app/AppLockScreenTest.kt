package com.facturastock.app

import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import com.facturastock.app.resources.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import com.facturastock.app.ui.theme.FacturaStockTheme
import org.junit.Rule
import org.junit.Test

class AppLockScreenTest {
    @get:Rule
    val composeRule = createComposeRule()


    @Test
    fun lockContentReflowsAtTwoHundredPercentAndKeepsUnlockReachable() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                FacturaStockTheme {
                    AppLockScreen(onUnlock = {})
                }
            }
        }

        composeRule.onNodeWithText(str(Res.string.app_lock_title))
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        composeRule.onNodeWithText(str(Res.string.app_lock_action))
            .performScrollTo()
            .assertIsDisplayed()
    }
}

private fun str(resource: StringResource, vararg args: Any): String =
    runBlocking { getString(resource, *args) }
