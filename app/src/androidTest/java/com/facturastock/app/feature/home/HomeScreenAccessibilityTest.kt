package com.facturastock.app.feature.home

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
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.facturastock.app.R
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.navigation.FacturaStockApp
import com.facturastock.app.ui.components.FacturaStockNavigationTestTags
import com.facturastock.app.ui.theme.FacturaStockTheme
import java.util.UUID
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeScreenAccessibilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun mainControlsHaveNamesRolesAndMinimumTouchTargets() {
        composeRule.setContent {
            FacturaStockTheme {
                FacturaStockApp(useInjectedViewModels = false)
            }
        }

        val homeTitle = context.getString(R.string.navigation_home)
        composeRule
            .onNode(
                hasText(homeTitle) and
                    SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
            )
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.PaneTitle,
                homeTitle,
            ),
        ).assertIsDisplayed()
        listOf(
            R.string.action_scan_invoice,
            R.string.action_new_sale,
            R.string.home_shortcut_products,
            R.string.action_view_purchases,
        ).forEach { labelRes ->
            val actionMatcher = hasText(context.getString(labelRes)) and
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

        listOf(
            R.string.navigation_home,
            R.string.navigation_sales,
            R.string.navigation_invoices,
            R.string.navigation_inventory,
            R.string.navigation_reports,
        ).forEach { labelRes ->
            composeRule
                .onNode(
                    hasText(context.getString(labelRes)) and
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
                hasText(homeTitle) and
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
                    FacturaStockApp(useInjectedViewModels = false)
                }
            }
        }

        listOf(
            HomeTestTags.SCAN_CTA,
            HomeTestTags.SALES_CTA,
            HomeTestTags.SHORTCUT_PRODUCTS,
            HomeTestTags.SHORTCUT_PURCHASES,
        ).forEach { actionTag ->
            composeRule.onNodeWithTag(HomeTestTags.DRAFTS_LIST)
                .performScrollToNode(hasTestTag(actionTag))
            composeRule
                .onNodeWithTag(actionTag)
                .assertIsDisplayed()
                .assertHasClickAction()
                .assert(
                    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button),
                )
                .assertTouchTargetAtLeast48Dp()
        }
        composeRule
            .onNodeWithText(context.getString(R.string.navigation_invoices))
            .assertIsDisplayed()
            .assertHasClickAction()
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab),
            )
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
                        FacturaStockApp(useInjectedViewModels = false)
                    }
                }
            }
        }

        listOf(
            HomeTestTags.SCAN_CTA,
            HomeTestTags.SALES_CTA,
            HomeTestTags.SHORTCUT_PRODUCTS,
            HomeTestTags.SHORTCUT_PURCHASES,
        ).forEach { actionTag ->
            composeRule.onNodeWithTag(HomeTestTags.DRAFTS_LIST)
                .performScrollToNode(hasTestTag(actionTag))
            composeRule
                .onNodeWithTag(actionTag)
                .assertIsDisplayed()
                .assertHasClickAction()
                .assertTouchTargetAtLeast48Dp()
        }
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
        val density = context.resources.displayMetrics.density
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
        val dialogTitle = context.getString(R.string.discard_dialog_title)

        composeRule.setContent {
            val currentDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = currentDensity.density,
                    fontScale = 2f,
                ),
            ) {
                FacturaStockTheme {
                    FacturaStockApp(
                        uuidGenerator = UuidGenerator { draftUuid },
                        useInjectedViewModels = false,
                    )
                }
            }
        }

        composeRule
            .onNodeWithText(context.getString(R.string.action_scan_invoice))
            .performScrollTo()
            .performClick()
        composeRule
            .onNodeWithContentDescription(
                context.getString(R.string.action_close_purchase_flow),
            )
            .performClick()

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
            R.string.action_discard_draft,
            R.string.action_keep_editing,
        ).forEach { labelRes ->
            composeRule
                .onNodeWithText(context.getString(labelRes))
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

    private fun SemanticsNodeInteraction.assertTouchTargetAtLeast48Dp(): SemanticsNodeInteraction {
        val minimumPixels = 48f * context.resources.displayMetrics.density
        val touchBounds = fetchSemanticsNode().touchBoundsInRoot
        assertTrue(touchBounds.width >= minimumPixels)
        assertTrue(touchBounds.height >= minimumPixels)
        return this
    }
}
