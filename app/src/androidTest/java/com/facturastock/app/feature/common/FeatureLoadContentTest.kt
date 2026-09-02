package com.facturastock.app.feature.common

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.facturastock.app.R
import com.facturastock.app.ui.theme.FacturaStockTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FeatureLoadContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun cachedContentRemainsVisibleWithAnExplicitRetryableStaleWarning() {
        var retryCount = 0
        val cachedContent = "Inventario guardado"
        composeRule.setContent {
            FacturaStockTheme {
                FeatureLoadContent(
                    isLoading = false,
                    hasContent = true,
                    hasFailure = true,
                    onRetry = { retryCount += 1 },
                ) {
                    Text(cachedContent)
                }
            }
        }

        composeRule.onNodeWithText(cachedContent).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.feature_stale_error_title))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.action_retry))
            .assertIsDisplayed()
            .performClick()

        assertEquals(1, retryCount)
    }

    @Test
    fun failureWithoutCachedContentDoesNotShowAnEmptyOrMisleadingSnapshot() {
        val content = "No debe mostrarse"
        composeRule.setContent {
            FacturaStockTheme {
                FeatureLoadContent(
                    isLoading = false,
                    hasContent = false,
                    hasFailure = true,
                    onRetry = {},
                ) {
                    Text(content)
                }
            }
        }

        composeRule.onNodeWithText(content).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.feature_load_error_title))
            .assertIsDisplayed()
    }
}
