package com.facturastock.app.feature.source

import com.facturastock.app.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.facturastock.app.ui.theme.FacturaStockTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SourceScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun str(res: StringResource, vararg args: Any): String = runBlocking { getString(res, *args) }

    @Test
    fun bothSourceActionsAndThePrivacyCardAreVisible() {
        composeRule.setContent {
            FacturaStockTheme {
                SourceScreen()
            }
        }

        composeRule
            .onNodeWithTag(SourceTestTags.PRIVACY_CARD)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(str(Res.string.source_privacy_title))
            .assertIsDisplayed()
        composeRule
            .onNodeWithTag(SourceTestTags.TAKE_PHOTO)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNodeWithTag(SourceTestTags.PICK_IMAGE)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(str(Res.string.action_take_photo))
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(str(Res.string.action_pick_image))
            .assertIsDisplayed()
    }

    @Test
    fun sourceActionButtonsFireTheirCallbacks() {
        val fired = mutableListOf<String>()
        composeRule.setContent {
            FacturaStockTheme {
                SourceScreen(
                    onTakePhoto = { fired += "camera" },
                    onPickImage = { fired += "gallery" },
                    onBack = { fired += "back" },
                )
            }
        }

        composeRule
            .onNodeWithTag(SourceTestTags.TAKE_PHOTO)
            .performScrollTo()
            .performClick()
        composeRule
            .onNodeWithTag(SourceTestTags.PICK_IMAGE)
            .performScrollTo()
            .performClick()
        composeRule
            .onNodeWithTag(SourceTestTags.BACK)
            .performScrollTo()
            .performClick()
        assertEquals(listOf("camera", "gallery", "back"), fired)
    }

    @Test
    fun cameraDeniedCardExplainsThePermissionAndRetries() {
        var retried = false
        composeRule.setContent {
            FacturaStockTheme {
                SourceScreen(
                    state = SourceContract.State(cameraAccessDenied = true),
                    onRetryCameraPermission = { retried = true },
                )
            }
        }

        composeRule
            .onNodeWithTag(SourceTestTags.CAMERA_DENIED)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(str(Res.string.source_camera_denied_title))
            .assertIsDisplayed()
        composeRule
            .onNodeWithTag(SourceTestTags.CAMERA_DENIED_RETRY)
            .performScrollTo()
            .performClick()
        assertTrue(retried)
    }

    @Test
    fun permanentlyDeniedCameraPermissionOffersAppSettings() {
        var openedSettings = false
        composeRule.setContent {
            FacturaStockTheme {
                SourceScreen(
                    state = SourceContract.State(
                        cameraAccessDenied = true,
                        cameraPermissionPermanentlyDenied = true,
                    ),
                    onOpenCameraSettings = { openedSettings = true },
                )
            }
        }

        composeRule.onNodeWithText(str(Res.string.action_open_app_settings))
            .performScrollTo()
            .performClick()
        assertTrue(openedSettings)
    }

    @Test
    fun invalidImageCardShowsItsReasonAndDismisses() {
        var dismissed = false
        composeRule.setContent {
            FacturaStockTheme {
                SourceScreen(
                    state = SourceContract.State(
                        invalidImage = SourceContract.InvalidImageReason.TOO_LARGE,
                    ),
                    onDismissInvalidImage = { dismissed = true },
                )
            }
        }

        composeRule
            .onNodeWithTag(SourceTestTags.INVALID_IMAGE)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(str(Res.string.source_invalid_image_too_large))
            .assertIsDisplayed()
        composeRule
            .onNodeWithTag(SourceTestTags.INVALID_IMAGE_DISMISS)
            .performScrollTo()
            .performClick()
        assertTrue(dismissed)
    }

    @Test
    fun storageFullExplainsThatTheDraftIsKeptAndCanBeRetried() {
        composeRule.setContent {
            FacturaStockTheme {
                SourceScreen(
                    state = SourceContract.State(
                        invalidImage = SourceContract.InvalidImageReason.STORAGE_FULL,
                    ),
                )
            }
        }

        composeRule.onNodeWithText("Libera espacio y reintenta", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("tu borrador no se borró", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun importingShowsTheIndicatorAndDisablesTheSourceActions() {
        composeRule.setContent {
            FacturaStockTheme {
                SourceScreen(state = SourceContract.State(isImporting = true))
            }
        }

        composeRule
            .onNodeWithTag(SourceTestTags.IMPORTING)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNodeWithTag(SourceTestTags.TAKE_PHOTO)
            .performScrollTo()
            .assertIsNotEnabled()
        composeRule
            .onNodeWithTag(SourceTestTags.PICK_IMAGE)
            .performScrollTo()
            .assertIsNotEnabled()
    }
}
