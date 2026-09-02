package com.facturastock.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
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
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.facturastock.app.R
import com.facturastock.app.ui.theme.FacturaStockTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilityComponentsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun loadingStateAnnouncesProgressWithoutInventingAPercentage() {
        val message = context.getString(R.string.feature_loading_message)
        composeRule.setContent {
            FacturaStockTheme {
                LoadingState(message = message)
            }
        }

        composeRule.onNodeWithContentDescription(message)
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            )
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ProgressBarRangeInfo,
                    ProgressBarRangeInfo.Indeterminate,
                ),
            )
    }

    @Test
    fun errorStatusIsAssertiveAndKeepsItsVisibleContext() {
        val title = context.getString(R.string.preview_error_title)
        composeRule.setContent {
            FacturaStockTheme {
                StatusCard(
                    statusLabel = context.getString(R.string.ocr_error_status),
                    title = title,
                    message = context.getString(R.string.preview_error_message),
                    tone = StatusTone.ERROR,
                    iconRes = R.drawable.ic_warning,
                )
            }
        }

        composeRule.onNodeWithText(title)
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Assertive,
                ),
            )
    }

    @Test
    fun asynchronousSuccessCanRequestAPoliteAnnouncement() {
        val title = context.getString(R.string.saved_feedback_title)
        composeRule.setContent {
            FacturaStockTheme {
                StatusCard(
                    statusLabel = context.getString(R.string.navigation_settings),
                    title = title,
                    message = context.getString(R.string.saved_feedback_message),
                    tone = StatusTone.SUCCESS,
                    iconRes = R.drawable.ic_check_circle,
                    announcementMode = LiveRegionMode.Polite,
                )
            }
        }

        composeRule.onNodeWithText(title)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            )
    }

    @Test
    fun recoverableErrorAnnouncesAndExposesA48DpRetryButton() {
        val action = context.getString(R.string.action_retry)
        composeRule.setContent {
            FacturaStockTheme {
                RecoverableError(
                    title = context.getString(R.string.preview_error_title),
                    message = context.getString(R.string.preview_error_message),
                    actionLabel = action,
                    onAction = {},
                )
            }
        }

        composeRule.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.LiveRegion,
                LiveRegionMode.Assertive,
            ),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(action)
            .assertIsDisplayed()
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .assertTouchTargetAtLeast48Dp()
    }

    @Test
    fun dialogAtTwoHundredPercentHasPaneHeadingAndLogicalActionOrder() {
        val title = context.getString(R.string.preview_dialog_title)
        val confirm = context.getString(R.string.action_confirm_purchase)
        val dismiss = context.getString(R.string.action_cancel)
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                FacturaStockTheme {
                    FacturaStockDialogContent(
                        title = title,
                        message = context.getString(R.string.preview_dialog_message),
                        confirmLabel = confirm,
                        dismissLabel = dismiss,
                        onConfirm = {},
                        onDismiss = {},
                    )
                }
            }
        }

        composeRule.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, title),
        )
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.IsTraversalGroup,
                    true,
                ),
            )
        composeRule.onNode(
            hasText(title) and
                SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
        )
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))

        val buttonRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
        val confirmNode = composeRule.onNode(hasText(confirm) and buttonRole)
            .assertIsDisplayed()
            .assertHasClickAction()
            .assert(buttonRole)
            .assertHeightIsAtLeast(48.dp)
            .assertTouchTargetAtLeast48Dp()
        val dismissNode = composeRule.onNode(hasText(dismiss) and buttonRole)
            .assertIsDisplayed()
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHeightIsAtLeast(48.dp)
            .assertTouchTargetAtLeast48Dp()

        assertTrue(
            confirmNode.fetchSemanticsNode().boundsInRoot.top <
                dismissNode.fetchSemanticsNode().boundsInRoot.top,
        )
    }

    @Test
    fun compactLargeFontBottomNavigationKeepsTabOrderStateAndTouchTargets() {
        val labels = listOf(
            R.string.navigation_home,
            R.string.navigation_sales,
            R.string.navigation_invoices,
            R.string.navigation_inventory,
            R.string.navigation_reports,
        ).map(context::getString)
        val icons = listOf(
            R.drawable.ic_home,
            R.drawable.ic_sale,
            R.drawable.ic_receipt,
            R.drawable.ic_inventory,
            R.drawable.ic_reports,
        )
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                FacturaStockTheme {
                    Box(modifier = Modifier.width(320.dp)) {
                        FacturaStockBottomNavigation(
                            items = labels.zip(icons) { label, icon ->
                                FacturaStockBottomItem(label = label, iconRes = icon)
                            },
                            selectedIndex = 0,
                            onItemSelected = {},
                        )
                    }
                }
            }
        }

        val leftEdges = labels.mapIndexed { index, label ->
            composeRule.onNodeWithText(label)
                .assertIsDisplayed()
                .assertHasClickAction()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
                .assertTouchTargetAtLeast48Dp()
                .also { node ->
                    if (index == 0) node.assertIsSelected()
                }
                .fetchSemanticsNode()
                .boundsInRoot
                .left
        }
        assertTrue(leftEdges.zipWithNext().all { (left, right) -> left < right })
    }

    @Test
    fun shortExpandedRailKeepsBrandLabelsSelectionAndTouchTargets() {
        val labels = listOf(
            R.string.navigation_home,
            R.string.navigation_sales,
            R.string.navigation_invoices,
            R.string.navigation_inventory,
            R.string.navigation_reports,
        ).map(context::getString)
        val icons = listOf(
            R.drawable.ic_home,
            R.drawable.ic_sale,
            R.drawable.ic_receipt,
            R.drawable.ic_inventory,
            R.drawable.ic_reports,
        )
        var selectedIndex = -1

        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 1.4f),
            ) {
                FacturaStockTheme {
                    Box(
                        modifier = Modifier
                            .width(900.dp)
                            .height(480.dp),
                    ) {
                        FacturaStockNavigationRail(
                            items = labels.zip(icons) { label, icon ->
                                FacturaStockBottomItem(label = label, iconRes = icon)
                            },
                            selectedIndex = 2,
                            brandLabel = context.getString(R.string.app_name),
                            brandIconRes = R.drawable.ic_facturastock,
                            onItemSelected = { selectedIndex = it },
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag(FacturaStockNavigationTestTags.RAIL)
            .assertIsDisplayed()
            .assertWidthIsEqualTo(112.dp)
        composeRule.onNodeWithText(context.getString(R.string.app_name))
            .assertIsDisplayed()
        labels.forEachIndexed { index, label ->
            composeRule.onNodeWithText(label)
                .assertIsDisplayed()
                .assertHasClickAction()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
                .assertTouchTargetAtLeast48Dp()
                .also { node ->
                    if (index == 2) node.assertIsSelected()
                }
        }
        composeRule.onNodeWithText(labels.last()).performClick()
        composeRule.runOnIdle { assertEquals(4, selectedIndex) }
    }

    @Test
    fun scaffoldCanCenterAWiderTabletDashboardContent() {
        val contentTag = "wide_tablet_content"

        composeRule.setContent {
            FacturaStockTheme {
                Box(
                    modifier = Modifier
                        .width(1_000.dp)
                        .height(600.dp),
                ) {
                    FacturaStockScaffold(contentMaxWidth = 960.dp) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag(contentTag),
                        )
                    }
                }
            }
        }

        val contentBounds = composeRule.onNodeWithTag(contentTag)
            .assertIsDisplayed()
            .assertWidthIsEqualTo(960.dp)
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(contentBounds.left > 0f)
        assertEquals(contentBounds.left, 1_000.dp.toPx() - contentBounds.right, 1f)
    }

    @Test
    fun spanishCountsUseSingularAndPluralForms() {
        assertEquals(
            "1 resultado",
            context.resources.getQuantityString(R.plurals.catalog_result_count, 1, 1),
        )
        assertEquals(
            "2 resultados",
            context.resources.getQuantityString(R.plurals.catalog_result_count, 2, 2),
        )
        assertEquals(
            "1 almacén",
            context.resources.getQuantityString(R.plurals.inventory_warehouse_count, 1, 1),
        )
        assertEquals(
            "2 almacenes",
            context.resources.getQuantityString(R.plurals.inventory_warehouse_count, 2, 2),
        )
    }

    private fun SemanticsNodeInteraction.assertTouchTargetAtLeast48Dp(): SemanticsNodeInteraction {
        val minimumPixels = 48f * context.resources.displayMetrics.density
        val touchBounds = fetchSemanticsNode().touchBoundsInRoot
        assertTrue(touchBounds.width >= minimumPixels)
        assertTrue(touchBounds.height >= minimumPixels)
        return this
    }

    private fun androidx.compose.ui.unit.Dp.toPx(): Float =
        value * context.resources.displayMetrics.density
}
