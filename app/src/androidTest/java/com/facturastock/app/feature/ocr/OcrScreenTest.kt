package com.facturastock.app.feature.ocr

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.facturastock.app.R
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.usecase.InvoiceOcrStage
import com.facturastock.app.ui.theme.FacturaStockTheme
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OcrScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun realStagesAreAnnouncedAsIndeterminateProgressWithoutPercentages() {
        var state by mutableStateOf(runningState(InvoiceOcrStage.PREPARING))
        composeRule.setContent {
            FacturaStockTheme {
                OcrScreen(state = state, onAction = {})
            }
        }

        listOf(
            InvoiceOcrStage.PREPARING to R.string.ocr_stage_preparing,
            InvoiceOcrStage.READING to R.string.ocr_stage_reading,
            InvoiceOcrStage.MERGING_PAGES to R.string.ocr_stage_merging_pages,
        ).forEach { (stage, messageRes) ->
            composeRule.runOnIdle { state = runningState(stage) }
            val message = context.getString(messageRes)
            composeRule
                .onNodeWithContentDescription(message)
                .performScrollTo()
                .assertIsDisplayed()
                .assert(indeterminateProgress())
            composeRule.onAllNodes(hasText("%", substring = true)).assertCountEquals(0)
        }
    }

    @Test
    fun runningOffersCancelAndFailureOffersRetry() {
        var action: OcrContract.Action? = null
        var state by mutableStateOf(runningState(InvoiceOcrStage.READING))
        composeRule.setContent {
            FacturaStockTheme {
                OcrScreen(state = state, onAction = { action = it })
            }
        }

        composeRule.onNodeWithTag(OcrTestTags.CANCEL).assertIsEnabled().performClick()
        assertEquals(OcrContract.Action.Cancel, action)
        composeRule.onNodeWithTag(OcrTestTags.RETRY).assertDoesNotExist()

        composeRule.runOnIdle {
            action = null
            state = OcrContract.State(
                draftId = DRAFT_ID,
                stage = InvoiceOcrStage.READING,
                failure = OcrContract.Failure.RECOGNITION_FAILED,
            )
        }
        composeRule.onNodeWithTag(OcrTestTags.CANCEL).assertDoesNotExist()
        composeRule.onNodeWithTag(OcrTestTags.RETRY).assertIsEnabled().performClick()
        assertEquals(OcrContract.Action.Retry, action)
        composeRule.onNodeWithTag(OcrTestTags.ENTER_MANUALLY).assertDoesNotExist()
    }

    @Test
    fun recognitionAndParsingFailuresKeepTheTwoStepFlow() {
        var action: OcrContract.Action? = null
        var state by mutableStateOf(
            OcrContract.State(
                draftId = DRAFT_ID,
                failure = OcrContract.Failure.RECOGNITION_FAILED,
            ),
        )
        composeRule.setContent {
            FacturaStockTheme {
                OcrScreen(state = state, onAction = { action = it })
            }
        }

        composeRule.onNodeWithTag(OcrTestTags.ENTER_MANUALLY).assertDoesNotExist()
        composeRule.onNodeWithTag(OcrTestTags.BACK).performScrollTo().performClick()
        assertEquals(OcrContract.Action.BackSelected, action)

        composeRule.runOnIdle {
            action = null
            state = state.copy(failure = OcrContract.Failure.PARSING_FAILED)
        }
        composeRule.onNodeWithTag(OcrTestTags.ENTER_MANUALLY).assertDoesNotExist()
        composeRule.onNodeWithTag(OcrTestTags.RETRY).performScrollTo().performClick()
        assertEquals(OcrContract.Action.Retry, action)
    }

    @Test
    fun productImportFailuresExplainTheOutcomeAndOfferRetry() {
        var state by mutableStateOf(
            OcrContract.State(
                draftId = DRAFT_ID,
                failure = OcrContract.Failure.NO_PRODUCTS_FOUND,
            ),
        )
        composeRule.setContent {
            FacturaStockTheme {
                OcrScreen(state = state, onAction = {})
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.ocr_no_products_title))
            .assertIsDisplayed()
        composeRule.onNodeWithTag(OcrTestTags.RETRY).assertIsEnabled()

        composeRule.runOnIdle {
            state = state.copy(failure = OcrContract.Failure.PRODUCT_SAVE_FAILED)
        }
        composeRule.onNodeWithText(context.getString(R.string.ocr_product_save_error_title))
            .assertIsDisplayed()
        composeRule.onNodeWithTag(OcrTestTags.RETRY).assertIsEnabled()
    }

    @Test
    fun savingProductsKeepsProgressVisibleWithoutNavigationActions() {
        composeRule.setContent {
            FacturaStockTheme {
                OcrScreen(
                    state = OcrContract.State(
                        draftId = DRAFT_ID,
                        isRunning = true,
                        isSavingProducts = true,
                    ),
                    onAction = {},
                )
            }
        }

        val saving = context.getString(R.string.ocr_saving_products)
        composeRule.onNodeWithText(saving).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(saving)
            .assertIsDisplayed()
            .assert(indeterminateProgress())
        composeRule.onNodeWithTag(OcrTestTags.BACK).assertDoesNotExist()
    }

    @Test
    fun interruptedRunExplainsLocalPersistenceAndOffersSingleRecoveryAction() {
        var action: OcrContract.Action? = null
        var state by mutableStateOf(
            OcrContract.State(
                draftId = DRAFT_ID,
                failure = OcrContract.Failure.INTERRUPTED,
            ),
        )
        composeRule.setContent {
            FacturaStockTheme {
                OcrScreen(state = state, onAction = { action = it })
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.ocr_interrupted_status))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.ocr_interrupted_message))
            .assertIsDisplayed()
        composeRule.onNodeWithTag(OcrTestTags.ENTER_MANUALLY).assertDoesNotExist()
        composeRule.onNodeWithTag(OcrTestTags.RETRY).assertIsEnabled().performClick()
        assertEquals(OcrContract.Action.Retry, action)

        composeRule.runOnIdle {
            state = state.copy(isRecoveringInterruptedOcr = true)
        }
        composeRule.onNodeWithTag(OcrTestTags.RETRY).assertIsNotEnabled()
        composeRule.onNodeWithText(context.getString(R.string.ocr_recovering)).assertIsDisplayed()
    }

    @Test
    fun cancellingKeepsIndeterminateFeedbackAndDisablesTheAction() {
        composeRule.setContent {
            FacturaStockTheme {
                OcrScreen(
                    state = OcrContract.State(
                        draftId = DRAFT_ID,
                        stage = InvoiceOcrStage.READING,
                        isCancelling = true,
                    ),
                    onAction = {},
                )
            }
        }

        val cancelling = context.getString(R.string.ocr_cancelling)
        composeRule.onNodeWithText(cancelling).assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription(cancelling)
            .assertIsDisplayed()
            .assert(indeterminateProgress())
        composeRule.onNodeWithTag(OcrTestTags.CANCEL).assertIsNotEnabled()
        composeRule.onNodeWithTag(OcrTestTags.RETRY).assertDoesNotExist()
    }

    private fun runningState(stage: InvoiceOcrStage) = OcrContract.State(
        draftId = DRAFT_ID,
        stage = stage,
        isRunning = true,
    )

    private fun indeterminateProgress(): SemanticsMatcher = SemanticsMatcher.expectValue(
        SemanticsProperties.ProgressBarRangeInfo,
        ProgressBarRangeInfo.Indeterminate,
    )

    private companion object {
        val DRAFT_ID: DraftId = DraftId.from(UUID(0L, 10L))
    }
}
