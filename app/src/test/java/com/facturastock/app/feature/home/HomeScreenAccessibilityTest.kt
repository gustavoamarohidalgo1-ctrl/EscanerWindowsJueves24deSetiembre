package com.facturastock.app.feature.home

import com.facturastock.app.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import kotlinx.coroutines.runBlocking
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.navigation.AppRoutes
import com.facturastock.app.navigation.FacturaStockApp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.facturastock.app.ui.components.FacturaStockNavigationTestTags
import com.facturastock.app.testing.performClickOnUiThread
import com.facturastock.app.ui.theme.FacturaStockTheme
import java.util.UUID
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HomeScreenAccessibilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun str(res: StringResource, vararg args: Any): String = runBlocking { getString(res, *args) }

    @Test
    fun mainControlsHaveNamesRolesAndMinimumTouchTargets() {
        // Inicio ya no es un destino de la app (su ruta es un alias que abre Vender), así que sus
        // acciones se verifican sobre la pantalla directamente.
        composeRule.setContent {
            FacturaStockTheme {
                HomeScreen()
            }
        }

        listOf(
            Res.string.action_scan_invoice,
            Res.string.action_new_sale,
            Res.string.home_shortcut_products,
            Res.string.action_view_purchases,
        ).forEach { labelRes ->
            val actionMatcher = hasText(str(labelRes)) and
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
            composeRule.onNodeWithTag(HomeTestTags.DRAFTS_LIST)
                .performScrollToNode(actionMatcher)
            composeRule
                .onNode(actionMatcher)
                .assertIsDisplayed()
                .assertHasClickAction()
                .assert(
                    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button),
                )
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
                .assertTouchTargetAtLeast48Dp()
        }
    }

    @Test
    fun appChromeHasHeadingPaneTitleAndAccessibleTabs() {
        composeRule.setContent {
            FacturaStockTheme {
                FacturaStockApp(useInjectedViewModels = false)
            }
        }

        // El destino inicial vigente es Vender; en destinos principales el título es su pestaña.
        val startTitle = str(Res.string.navigation_sales)
        composeRule
            .onNode(
                hasText(startTitle) and
                    SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
            )
            .assertIsDisplayed()
        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.PaneTitle,
                startTitle,
            ),
        ).assertIsDisplayed()

        listOf(
            Res.string.navigation_sales,
            Res.string.navigation_inventory,
            Res.string.navigation_reports,
        ).forEach { labelRes ->
            composeRule
                .onNode(
                    hasText(str(labelRes)) and
                        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab),
                )
                .assertHasClickAction()
                .assert(
                    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab),
                )
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
                .assertTouchTargetAtLeast48Dp()
        }

        composeRule
            .onNode(
                hasText(str(Res.string.navigation_sales)) and
                    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab),
            )
            .assertIsSelected()
    }

    @Test
    fun essentialActionsRemainReachableAtTwoHundredPercentFontScale() {
        composeRule.setContent {
            val currentDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = currentDensity.density,
                    fontScale = 2f,
                ),
            ) {
                FacturaStockTheme {
                    HomeScreen()
                }
            }
        }

        assertHomeActionsReachable(checkRole = true)
    }

    @Test
    fun navigationTabsRemainReachableAtTwoHundredPercentFontScale() {
        composeRule.setContent {
            val currentDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = currentDensity.density,
                    fontScale = 2f,
                ),
            ) {
                FacturaStockTheme {
                    FacturaStockApp(useInjectedViewModels = false)
                }
            }
        }

        composeRule
            .onNode(
                hasText(str(Res.string.navigation_inventory)) and
                    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab),
            )
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertTouchTargetAtLeast48Dp()
    }

    @Test
    fun essentialActionsRemainReachableUnderCompactLandscapeConstraints() {
        composeRule.setContent {
            val currentDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = currentDensity.density,
                    fontScale = 2f,
                ),
            ) {
                FacturaStockTheme {
                    Box(
                        modifier = Modifier
                            .width(360.dp)
                            .height(240.dp),
                    ) {
                        HomeScreen()
                    }
                }
            }
        }

        assertHomeActionsReachable(checkRole = false)
    }

    @Test
    fun compactLandscapeChromeLeavesRoomForContent() {
        composeRule.setContent {
            val currentDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = currentDensity.density,
                    fontScale = 2f,
                ),
            ) {
                FacturaStockTheme {
                    Box(
                        modifier = Modifier
                            .width(360.dp)
                            .height(240.dp),
                    ) {
                        FacturaStockApp(useInjectedViewModels = false)
                    }
                }
            }
        }

        composeRule
            .onNodeWithContentDescription(str(Res.string.navigation_open_settings))
            .assertIsDisplayed()
            .assertHasClickAction()
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button),
            )
            .assertTouchTargetAtLeast48Dp()

        val topBarHeight = composeRule
            .onNodeWithTag(FacturaStockNavigationTestTags.TOP)
            .fetchSemanticsNode()
            .boundsInRoot
            .height
        val bottomBarHeight = composeRule
            .onNodeWithTag(FacturaStockNavigationTestTags.BOTTOM)
            .fetchSemanticsNode()
            .boundsInRoot
            .height
        val density = composeRule.density.density
        assertTrue(
            "Compact chrome must leave at least one 48dp touch target visible",
            topBarHeight + bottomBarHeight <= (240f - 48f) * density,
        )
    }

    @Test
    fun expandedTabletUsesARail() {
        composeRule.setContent {
            FacturaStockTheme {
                Box(
                    modifier = Modifier
                        .width(900.dp)
                        .height(600.dp),
                ) {
                    FacturaStockApp(useInjectedViewModels = false)
                }
            }
        }

        composeRule.onNodeWithTag(FacturaStockNavigationTestTags.RAIL).assertIsDisplayed()
        composeRule.onNodeWithTag(FacturaStockNavigationTestTags.BOTTOM).assertDoesNotExist()
    }

    @Test
    fun expandedTabletFallsBackToBottomNavigationForLargeText() {
        composeRule.setContent {
            val currentDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = currentDensity.density,
                    fontScale = 2f,
                ),
            ) {
                FacturaStockTheme {
                    Box(
                        modifier = Modifier
                            .width(900.dp)
                            .height(600.dp),
                    ) {
                        FacturaStockApp(useInjectedViewModels = false)
                    }
                }
            }
        }

        composeRule.onNodeWithTag(FacturaStockNavigationTestTags.BOTTOM).assertIsDisplayed()
        composeRule.onNodeWithTag(FacturaStockNavigationTestTags.RAIL).assertDoesNotExist()
    }

    @Test
    fun shortWideWindowKeepsTheBottomNavigationReachable() {
        composeRule.setContent {
            FacturaStockTheme {
                Box(
                    modifier = Modifier
                        .width(900.dp)
                        .height(320.dp),
                ) {
                    FacturaStockApp(useInjectedViewModels = false)
                }
            }
        }

        composeRule.onNodeWithTag(FacturaStockNavigationTestTags.BOTTOM).assertIsDisplayed()
        composeRule.onNodeWithTag(FacturaStockNavigationTestTags.RAIL).assertDoesNotExist()
    }

    @Test
    fun discardDialogExposesTitleAndExplicitActions() {
        val draftUuid = UUID.fromString("60000000-0000-4000-8000-000000000006")
        val dialogTitle = str(Res.string.discard_dialog_title)

        lateinit var navController: NavHostController
        composeRule.setContent {
            val currentDensity = LocalDensity.current
            navController = rememberNavController()
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = currentDensity.density,
                    fontScale = 2f,
                ),
            ) {
                FacturaStockTheme {
                    FacturaStockApp(
                        navController = navController,
                        uuidGenerator = UuidGenerator { draftUuid },
                        useInjectedViewModels = false,
                    )
                }
            }
        }

        // Inicio ya no existe como destino: la compra se abre por su ruta y se inicia con su
        // acción explícita, que crea el borrador y entra al flujo protegido.
        composeRule.runOnIdle { navController.navigate(AppRoutes.NEW_PURCHASE) }
        val startPurchase = str(Res.string.action_start_purchase)
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodesWithText(startPurchase).fetchSemanticsNodes().isNotEmpty()
        }
        // Clic en el hilo de UI: navegar desde el hilo del test deja la entrada nueva sin ciclo
        // de vida (ver performClickOnUiThread).
        composeRule
            .onNodeWithText(startPurchase)
            .performScrollTo()
            .performClickOnUiThread(composeRule)
        composeRule
            .onNodeWithContentDescription(
                str(Res.string.action_close_purchase_flow),
            )
            .performClickOnUiThread(composeRule)

        composeRule.waitUntil(timeoutMillis = 5_000L) {
            runCatching {
                composeRule.onNodeWithText(dialogTitle).assertIsDisplayed()
            }.isSuccess
        }
        composeRule
            .onNodeWithText(dialogTitle)
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        composeRule
            .onNode(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.PaneTitle,
                    dialogTitle,
                ),
            )
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.IsTraversalGroup,
                    true,
                ),
            )
        listOf(
            Res.string.action_discard_draft,
            Res.string.action_keep_editing,
        ).forEach { labelRes ->
            composeRule
                .onNodeWithText(str(labelRes))
                .assertIsDisplayed()
                .assertHasClickAction()
                .assert(
                    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button),
                )
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
                .assertTouchTargetAtLeast48Dp()
        }
    }

    private fun assertHomeActionsReachable(checkRole: Boolean) {
        listOf(
            HomeTestTags.SCAN_CTA,
            HomeTestTags.SALES_CTA,
            HomeTestTags.SHORTCUT_PRODUCTS,
            HomeTestTags.SHORTCUT_PURCHASES,
        ).forEach { actionTag ->
            composeRule.onNodeWithTag(HomeTestTags.DRAFTS_LIST)
                .performScrollToNode(hasTestTag(actionTag))
            val node = composeRule
                .onNodeWithTag(actionTag)
                .assertIsDisplayed()
                .assertHasClickAction()
            if (checkRole) {
                node.assert(
                    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button),
                )
            }
            node.assertTouchTargetAtLeast48Dp()
        }
    }

    private fun SemanticsNodeInteraction.assertTouchTargetAtLeast48Dp(): SemanticsNodeInteraction {
        val minimumPixels = 48f * composeRule.density.density
        val touchBounds = fetchSemanticsNode().touchBoundsInRoot
        assertTrue(touchBounds.width >= minimumPixels)
        assertTrue(touchBounds.height >= minimumPixels)
        return this
    }
}
