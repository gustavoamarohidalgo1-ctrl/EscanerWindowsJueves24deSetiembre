package com.facturastock.app.feature.capture

import com.facturastock.app.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import com.facturastock.app.ui.theme.FacturaStockTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Pruebas de la pantalla de captura con un slot de visor falso: la cámara real no participa,
 * así la pantalla se verifica sin dispositivo con cámara funcional.
 */
class CaptureScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun str(res: StringResource, vararg args: Any): String = runBlocking { getString(res, *args) }

    @Test
    fun frameInstructionPreviewAndControlsAreVisible() {
        composeRule.setContent {
            FacturaStockTheme {
                CaptureScreen(
                    state = CaptureContract.State(isCameraReady = true),
                    cameraPreview = {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .testTag(FAKE_CAMERA_PREVIEW),
                        )
                    },
                )
            }
        }

        composeRule.onNodeWithTag(FAKE_CAMERA_PREVIEW).assertIsDisplayed()
        composeRule
            .onNodeWithTag(CaptureTestTags.PREVIEW)
            .assertIsDisplayed()
        composeRule
            .onNodeWithTag(CaptureTestTags.FRAME)
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(str(Res.string.capture_frame_instruction))
            .assertIsDisplayed()
        composeRule
            .onNodeWithTag(CaptureTestTags.SHUTTER)
            .assertIsDisplayed()
            .assertIsEnabled()
        composeRule
            .onNodeWithContentDescription(str(Res.string.action_take_photo))
            .assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription(str(Res.string.capture_back_to_source))
            .assertIsDisplayed()
        composeRule
            .onNodeWithTag(CaptureTestTags.FLASH)
            .assertIsDisplayed()
    }

    @Test
    fun shutterClickFiresTheCaptureCallback() {
        var captures = 0
        composeRule.setContent {
            FacturaStockTheme {
                CaptureScreen(
                    state = CaptureContract.State(isCameraReady = true),
                    onCaptureClicked = { captures += 1 },
                )
            }
        }

        composeRule
            .onNodeWithTag(CaptureTestTags.SHUTTER)
            .performClick()
        assertEquals(1, captures)
    }

    @Test
    fun shutterStaysDisabledUntilCameraXIsReady() {
        composeRule.setContent {
            FacturaStockTheme {
                CaptureScreen(state = CaptureContract.State(isCameraReady = false))
            }
        }

        composeRule.onNodeWithTag(CaptureTestTags.SHUTTER).assertIsNotEnabled()
    }

    @Test
    fun capturingDisablesTheShutterAndShowsTheIndicator() {
        composeRule.setContent {
            FacturaStockTheme {
                CaptureScreen(
                    state = CaptureContract.State(
                        isCameraReady = true,
                        isCapturing = true,
                    ),
                )
            }
        }

        composeRule
            .onNodeWithTag(CaptureTestTags.SHUTTER)
            .assertIsNotEnabled()
        composeRule
            .onNodeWithTag(CaptureTestTags.IMPORTING)
            .assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription(str(Res.string.capture_in_progress))
            .assertIsDisplayed()
    }

    @Test
    fun flashButtonCyclesAndAnnouncesTheCurrentMode() {
        var toggles = 0
        var flashMode by mutableStateOf(CaptureFlashMode.AUTO)
        composeRule.setContent {
            FacturaStockTheme {
                CaptureScreen(
                    state = CaptureContract.State(flashMode = flashMode),
                    onFlashClicked = {
                        toggles += 1
                        flashMode = CaptureFlashMode.ON
                    },
                )
            }
        }

        composeRule
            .onNodeWithContentDescription(str(Res.string.capture_flash_auto))
            .assertIsDisplayed()
        composeRule
            .onNodeWithTag(CaptureTestTags.FLASH)
            .performClick()
        assertEquals(1, toggles)

        composeRule
            .onNodeWithContentDescription(str(Res.string.capture_flash_on))
            .assertIsDisplayed()

        composeRule.runOnIdle { flashMode = CaptureFlashMode.OFF }
        composeRule
            .onNodeWithContentDescription(str(Res.string.capture_flash_off))
            .assertIsDisplayed()
    }

    @Test
    fun flashButtonIsHiddenWhenTheCameraHasNoFlashUnit() {
        composeRule.setContent {
            FacturaStockTheme {
                CaptureScreen(
                    state = CaptureContract.State(isCameraReady = true),
                    hasFlashUnit = false,
                )
            }
        }

        composeRule
            .onNodeWithTag(CaptureTestTags.FLASH)
            .assertDoesNotExist()
        // El obturador sigue disponible aunque no haya flash.
        composeRule
            .onNodeWithTag(CaptureTestTags.SHUTTER)
            .assertIsDisplayed()
    }

    @Test
    fun permissionDeniedStateOffersGrantingThePermissionAndPickingAnImage() {
        val fired = mutableListOf<String>()
        composeRule.setContent {
            FacturaStockTheme {
                CaptureScreen(
                    state = CaptureContract.State(cameraPermissionDenied = true),
                    onGrantPermission = { fired += "grant" },
                    onPickImage = { fired += "pick" },
                )
            }
        }

        composeRule
            .onNodeWithTag(CaptureTestTags.PERMISSION_DENIED)
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Assertive,
                ),
            )
        composeRule
            .onNodeWithText(str(Res.string.source_camera_denied_title))
            .assertIsDisplayed()
        composeRule
            .onNodeWithTag(CaptureTestTags.GRANT_PERMISSION)
            .assertIsDisplayed()
            .performClick()
        composeRule
            .onNodeWithTag(CaptureTestTags.PICK_IMAGE)
            .assertIsDisplayed()
            .performClick()
        assertEquals(listOf("grant", "pick"), fired)

        // El visor de cámara no se compone en el estado de denegado.
        composeRule
            .onNodeWithTag(CaptureTestTags.PREVIEW)
            .assertDoesNotExist()
    }

    @Test
    fun permanentlyDeniedPermissionOffersAppSettings() {
        var openedSettings = false
        composeRule.setContent {
            FacturaStockTheme {
                CaptureScreen(
                    state = CaptureContract.State(
                        cameraPermissionDenied = true,
                        cameraPermissionPermanentlyDenied = true,
                    ),
                    onGrantPermission = { openedSettings = true },
                )
            }
        }

        composeRule.onNodeWithText(str(Res.string.action_open_app_settings))
            .assertIsDisplayed()
            .performClick()
        assertTrue(openedSettings)
    }

    @Test
    fun cameraErrorStateOffersRetryAndPickingAnImage() {
        val fired = mutableListOf<String>()
        composeRule.setContent {
            FacturaStockTheme {
                CaptureScreen(
                    state = CaptureContract.State(
                        cameraError = CaptureContract.CameraErrorKind.BIND_FAILED,
                    ),
                    onRetryCamera = { fired += "retry" },
                    onPickImage = { fired += "pick" },
                )
            }
        }

        composeRule
            .onNodeWithTag(CaptureTestTags.CAMERA_ERROR)
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(str(Res.string.capture_camera_error_title))
            .assertIsDisplayed()
        composeRule
            .onNodeWithTag(CaptureTestTags.RETRY)
            .assertIsDisplayed()
            .performClick()
        composeRule
            .onNodeWithTag(CaptureTestTags.PICK_IMAGE)
            .assertIsDisplayed()
            .performClick()
        assertEquals(listOf("retry", "pick"), fired)
    }

    @Test
    fun directTwoStepFlowHidesTheGalleryFallback() {
        composeRule.setContent {
            FacturaStockTheme {
                CaptureScreen(
                    state = CaptureContract.State(
                        cameraError = CaptureContract.CameraErrorKind.BIND_FAILED,
                    ),
                    allowImagePicker = false,
                )
            }
        }

        composeRule.onNodeWithTag(CaptureTestTags.RETRY).assertIsDisplayed()
        composeRule.onNodeWithTag(CaptureTestTags.PICK_IMAGE).assertDoesNotExist()
    }

    @Test
    fun importFailureShowsItsOwnMessage() {
        composeRule.setContent {
            FacturaStockTheme {
                CaptureScreen(
                    state = CaptureContract.State(
                        cameraError = CaptureContract.CameraErrorKind.IMPORT_FAILED,
                    ),
                )
            }
        }

        composeRule
            .onNodeWithTag(CaptureTestTags.CAMERA_ERROR)
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(str(Res.string.capture_import_error_title))
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(str(Res.string.capture_import_error_message))
            .assertIsDisplayed()
        composeRule
            .onNodeWithTag(CaptureTestTags.RETRY)
            .assertIsDisplayed()
    }

    @Test
    fun cameraErrorActionsRemainReachableAtTwoHundredPercent() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                FacturaStockTheme {
                    CaptureScreen(
                        state = CaptureContract.State(
                            cameraError = CaptureContract.CameraErrorKind.BIND_FAILED,
                        ),
                    )
                }
            }
        }

        composeRule.onNodeWithTag(CaptureTestTags.PICK_IMAGE)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(CaptureTestTags.RETRY)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun focusIndicatorAppearsAtTheTappedPoint() {
        // Con el reloj detenido la animación de desvanecido no avanza y el aro persiste.
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            FacturaStockTheme {
                CaptureScreen(focusIndicatorOffset = Offset(x = 120f, y = 240f))
            }
        }

        composeRule
            .onNodeWithTag(CaptureTestTags.FOCUS_INDICATOR)
            .assertIsDisplayed()
    }

    private companion object {
        const val FAKE_CAMERA_PREVIEW = "fake_camera_preview"
    }
}
