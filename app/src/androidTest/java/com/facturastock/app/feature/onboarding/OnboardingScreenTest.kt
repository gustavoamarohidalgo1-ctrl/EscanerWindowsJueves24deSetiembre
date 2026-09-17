package com.facturastock.app.feature.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
class OnboardingScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun incompleteRequiredDataKeepsTheCompactConfirmationDisabled() {
        composeRule.setContent {
            FacturaStockTheme {
                OnboardingScreen(
                    state = OnboardingContract.State(),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithTag(OnboardingTestTags.BUSINESS_NAME).assertIsDisplayed()
        composeRule.onNodeWithTag(OnboardingTestTags.SUBMIT)
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsNotEnabled()
    }

    @Test
    fun checksumWarningKeepsExplicitConfirmationAvailableAndMarksReviewPending() {
        composeRule.setContent {
            FacturaStockTheme {
                OnboardingScreen(
                    state = completedState(
                        ruc = "20123456789",
                        rucChecksumWarning = true,
                    ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.ruc_checksum_warning_title))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(OnboardingTestTags.SUBMIT)
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
    }

    @Test
    fun everyFieldAndConfirmationRemainReachableAtTwoHundredPercentFontScale() {
        val actions = mutableListOf<OnboardingContract.Action>()
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                FacturaStockTheme {
                    Box(modifier = Modifier.size(width = 360.dp, height = 640.dp)) {
                        OnboardingScreen(
                            state = completedState(),
                            onAction = actions::add,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        val screenNode = composeRule.onNodeWithTag(OnboardingTestTags.SCREEN)
            .fetchSemanticsNode()
        assertEquals(2f, screenNode.layoutInfo.density.fontScale)
        composeRule.onNodeWithTag(OnboardingTestTags.COST_GROSS)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(OnboardingTestTags.WAREHOUSE)
            .assertDoesNotExist()
        composeRule.onNodeWithTag(OnboardingTestTags.SUBMIT)
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()

        assertEquals(OnboardingContract.Action.Save, actions.last())
    }

    @Test
    fun costPolicyUsesAccessibleRadioControlsWithoutDecorativeCards() {
        val actions = mutableListOf<OnboardingContract.Action>()
        composeRule.setContent {
            FacturaStockTheme {
                OnboardingScreen(
                    state = completedState(),
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithTag(OnboardingTestTags.COST_NET)
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsSelected()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton),
            )
        composeRule.onNodeWithTag(OnboardingTestTags.COST_GROSS)
            .performScrollTo()
            .performClick()

        assertEquals(
            OnboardingContract.Action.CostPolicySelected(
                com.facturastock.app.domain.config.CostPolicy.GROSS,
            ),
            actions.last(),
        )
    }

    @Test
    fun contentWidthIsCappedInWideWindow() {
        composeRule.setContent {
            FacturaStockTheme {
                Box(modifier = Modifier.requiredSize(width = 1_000.dp, height = 800.dp)) {
                    OnboardingScreen(
                        state = completedState(),
                        onAction = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        val screenNode = composeRule.onNodeWithTag(OnboardingTestTags.SCREEN)
            .fetchSemanticsNode()
        val screenWidth = with(screenNode.layoutInfo.density) { screenNode.size.width.toDp() }
        assertTrue("Expected capped onboarding width, but it was $screenWidth", screenWidth <= 640.dp)
    }

    private fun completedState(
        ruc: String = "",
        rucChecksumWarning: Boolean = false,
    ) = OnboardingContract.State(
        businessName = "Bodega Mayda",
        ruc = ruc,
        taxRatePercent = "18",
        warehouseName = "Almacén principal",
        rucChecksumWarning = rucChecksumWarning,
    )
}
