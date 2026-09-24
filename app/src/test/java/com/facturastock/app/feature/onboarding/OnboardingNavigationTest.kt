package com.facturastock.app.feature.onboarding

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.feature.inventory.InventoryTestTags
import com.facturastock.app.feature.sales.SalesTestTags
import com.facturastock.app.resources.Res
import com.facturastock.app.resources.action_back
import com.facturastock.app.resources.navigation_inventory
import com.facturastock.app.resources.navigation_sales
import com.facturastock.app.resources.onboarding_title
import com.facturastock.app.testing.DesktopAppHarness
import com.facturastock.app.testing.TestAppConfigurationState
import com.facturastock.app.testing.performClickOnUiThread
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Recorrido de la compuerta de primer inicio: con la configuración incompleta la app arranca
 * en onboarding y solo se llega a Vender tras completarla. El fake de configuración
 * ([com.facturastock.app.testing.TestAppConfigurationModule]) arranca incompleto y su
 * `completeOnboarding` reacciona como el DataStore real.
 *
 * Persistencia: el resto de la app usa los repositorios Room REALES sobre una base temporal
 * del [DesktopAppHarness]; el RUC se deja vacío (es opcional).
 */
class OnboardingNavigationTest {
    @get:Rule(order = 0)
    val harness = DesktopAppHarness(initialConfiguration = AppConfiguration.defaults())

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    @Test
    fun onboardingReachesInventoryAndCompletionSurvivesWindowRestart() {
        harness.setAppContent(composeRule)

        // La compuerta incompleta muestra onboarding y no el grafo principal.
        waitUntilDisplayed(Res.string.onboarding_title)
        composeRule
            .onNodeWithContentDescription(str(Res.string.action_back))
            .assertDoesNotExist()
        composeRule
            .onNodeWithText(str(Res.string.navigation_sales))
            .assertDoesNotExist()

        // Formulario mínimo: nombre comercial (RUC opcional vacío, IGV 18 %).
        val businessName = "Bodega Nav Test ${System.currentTimeMillis()}"
        composeRule.onNodeWithTag(OnboardingTestTags.BUSINESS_NAME)
            .performScrollTo()
            .performTextInput(businessName)
        composeRule.waitForIdle()

        composeRule
            .onNodeWithTag(OnboardingTestTags.SUBMIT)
            .performScrollTo()
            .performClickOnUiThread(composeRule)

        // Tras completar, la compuerta reacciona y se llega a Vender; onboarding queda fuera.
        waitUntilTag(SalesTestTags.SCREEN)
        composeRule
            .onAllNodesWithText(str(Res.string.onboarding_title))
            .assertCountEquals(0)
        assertTrue(TestAppConfigurationState.current.value.onboardingCompleted)

        openInventory()

        // Cerrar y reabrir la ventana: la compuerta completada no vuelve a mostrar onboarding.
        harness.restartApp(composeRule)
        waitUntilTag(SalesTestTags.SCREEN)
        composeRule
            .onAllNodesWithText(str(Res.string.onboarding_title))
            .assertCountEquals(0)
        openInventory()
    }

    private fun openInventory() {
        composeRule
            .onNode(
                hasText(str(Res.string.navigation_inventory)) and
                    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab),
            )
            .performClickOnUiThread(composeRule)
        waitUntilTag(InventoryTestTags.LIST_SCREEN)
    }

    private fun waitUntilDisplayed(resource: StringResource) {
        val label = str(resource)
        composeRule.waitUntil(timeoutMillis = 15_000L) {
            runCatching {
                composeRule.onNodeWithText(label).assertIsDisplayed()
            }.isSuccess
        }
    }

    private fun waitUntilTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 15_000L) {
            runCatching {
                composeRule.onNodeWithTag(tag).assertIsDisplayed()
            }.isSuccess
        }
    }

    private fun str(resource: StringResource): String = runBlocking { getString(resource) }
}
