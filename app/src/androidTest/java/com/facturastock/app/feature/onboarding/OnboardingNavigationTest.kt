package com.facturastock.app.feature.onboarding

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.facturastock.app.MainActivity
import com.facturastock.app.R
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.feature.inventory.InventoryTestTags
import com.facturastock.app.feature.sales.SalesTestTags
import com.facturastock.app.testing.TestAppConfigurationState
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Recorrido de la compuerta de primer inicio: con la configuración incompleta la app arranca
 * en onboarding y solo se llega a Vender tras completarla. El fake de configuración (que
 * reemplaza al módulo real con `@TestInstallIn`) arranca incompleto y su `completeOnboarding`
 * reacciona como el DataStore real.
 *
 * Persistencia: el resto de la app usa los repositorios Room REALES sobre la base de datos
 * del dispositivo de instrumentación (PersistenceModule no se reemplaza). El nombre del
 * negocio lleva un timestamp para no chocar con ejecuciones anteriores; el RUC se deja vacío
 * (es opcional) y los `NULL` no colisionan entre corridas.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class OnboardingNavigationTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<MainActivity>

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        TestAppConfigurationState.current.value = AppConfiguration.defaults()
        hiltRule.inject()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario.close()
    }

    @Test
    fun onboardingReachesInventoryAndInventorySurvivesActivityRecreation() {
        // La compuerta incompleta muestra onboarding y no el grafo principal.
        waitUntilDisplayed(R.string.onboarding_title)
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.action_back))
            .assertDoesNotExist()
        composeRule
            .onNodeWithText(context.getString(R.string.navigation_sales))
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
            .performClick()

        // Tras completar, la compuerta reacciona y se llega a Vender; onboarding queda fuera.
        waitUntilTag(SalesTestTags.SCREEN)
        composeRule
            .onAllNodesWithText(context.getString(R.string.onboarding_title))
            .assertCountEquals(0)

        composeRule
            .onNode(
                hasText(context.getString(R.string.navigation_inventory)) and
                    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab),
            )
            .performClick()
        waitUntilTag(InventoryTestTags.LIST_SCREEN)

        scenario.recreate()
        waitUntilTag(InventoryTestTags.LIST_SCREEN)
    }

    private fun waitUntilDisplayed(stringRes: Int) {
        val label = context.getString(stringRes)
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            runCatching {
                composeRule.onNodeWithText(label).assertIsDisplayed()
            }.isSuccess
        }
    }

    private fun waitUntilTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            runCatching {
                composeRule.onNodeWithTag(tag).assertIsDisplayed()
            }.isSuccess
        }
    }
}
