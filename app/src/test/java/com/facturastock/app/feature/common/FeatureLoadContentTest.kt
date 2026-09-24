package com.facturastock.app.feature.common

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.facturastock.app.resources.Res
import com.facturastock.app.resources.action_retry
import com.facturastock.app.resources.feature_load_error_title
import com.facturastock.app.resources.feature_stale_error_title
import com.facturastock.app.ui.theme.FacturaStockTheme
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FeatureLoadContentTest {
    @get:Rule
    val composeRule = createComposeRule()

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
        composeRule.onNodeWithText(str(Res.string.feature_stale_error_title))
            .assertIsDisplayed()
        composeRule.onNodeWithText(str(Res.string.action_retry))
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
        composeRule.onNodeWithText(str(Res.string.feature_load_error_title))
            .assertIsDisplayed()
    }

    private fun str(resource: StringResource): String = runBlocking { getString(resource) }
}
