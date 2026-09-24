package com.facturastock.app.navigation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import com.facturastock.app.feature.reports.ReportsTestTags
import com.facturastock.app.feature.sales.SalesTestTags
import com.facturastock.app.resources.Res
import com.facturastock.app.resources.navigation_open_settings
import com.facturastock.app.resources.navigation_reports
import com.facturastock.app.feature.settings.SettingsTestTags
import androidx.compose.ui.test.hasContentDescription
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import com.facturastock.app.testing.DesktopAppHarness
import com.facturastock.app.testing.performClickOnUiThread
import org.junit.Rule
import org.junit.Test

/** Esc en la ventana real: el mismo árbol que monta `Main.kt` sobre el grafo de pruebas. */
class DesktopWindowBackTest {
    @get:Rule(order = 0)
    val harness = DesktopAppHarness()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    @Test
    fun escapeOnTheStartDestinationKeepsSalesInsteadOfEmptyingTheBackStack() {
        harness.setAppContent(composeRule)
        composeRule.waitUntil(15_000L) {
            composeRule.onAllNodesWithTag(SalesTestTags.SCREEN).fetchSemanticsNodes().isNotEmpty()
        }
        harness.keyboard(composeRule).escape()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SalesTestTags.SCREEN).assertIsDisplayed()
    }

    @Test
    fun escapeAfterTopLevelNavigationReturnsToSales() {
        harness.setAppContent(composeRule)
        composeRule.waitUntil(15_000L) {
            composeRule.onAllNodesWithTag(SalesTestTags.SCREEN).fetchSemanticsNodes().isNotEmpty()
        }
        val reports = runBlocking { getString(Res.string.navigation_reports) }
        composeRule.onNode(hasText(reports) and hasClickAction()).performClickOnUiThread(composeRule)
        composeRule.waitUntil(15_000L) {
            composeRule.onAllNodesWithTag(ReportsTestTags.SCREEN).fetchSemanticsNodes().isNotEmpty()
        }
        harness.keyboard(composeRule).escape()
        composeRule.waitUntil(15_000L) {
            composeRule.onAllNodesWithTag(SalesTestTags.SCREEN).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(ReportsTestTags.SCREEN).assertDoesNotExist()
    }

    /** Un solo Esc retrocede un solo nivel: Ajustes -> Reportes, no hasta Vender. */
    @Test
    fun oneEscapeFromAPushedScreenGoesBackExactlyOneLevel() {
        harness.setAppContent(composeRule)
        waitForTag(SalesTestTags.SCREEN)
        val reports = runBlocking { getString(Res.string.navigation_reports) }
        composeRule.onNode(hasText(reports) and hasClickAction()).performClickOnUiThread(composeRule)
        waitForTag(ReportsTestTags.SCREEN)
        val settings = runBlocking { getString(Res.string.navigation_open_settings) }
        composeRule.onNode(hasContentDescription(settings) and hasClickAction()).performClickOnUiThread(composeRule)
        waitForTag(SettingsTestTags.SCREEN)

        harness.keyboard(composeRule).escape()

        waitForTag(ReportsTestTags.SCREEN)
        composeRule.onNodeWithTag(SettingsTestTags.SCREEN).assertDoesNotExist()
        composeRule.onNodeWithTag(SalesTestTags.SCREEN).assertDoesNotExist()
    }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(15_000L) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
