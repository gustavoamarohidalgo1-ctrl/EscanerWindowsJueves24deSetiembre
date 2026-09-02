package com.facturastock.app.feature.preview

import android.accessibilityservice.AccessibilityService
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
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
import com.facturastock.app.domain.model.ImageCrop
import com.facturastock.app.domain.model.ImageQualityReport
import com.facturastock.app.domain.model.ImageQualityWarning
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.ui.theme.FacturaStockTheme
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pruebas de la pantalla de vista previa con estado sintético: el archivo de imagen no
 * existe en disco, así que solo se verifica la estructura (acciones, indicador, diálogos y
 * capa de recorte). Las transformaciones se cubren en dominio y casos de uso.
 */
@RunWith(AndroidJUnit4::class)
class PreviewScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun actionsIndicatorAndProcessButtonAreVisible() {
        composeRule.setContent {
            FacturaStockTheme {
                PreviewScreen(state = fakeState(), onAction = {})
            }
        }

        composeRule.onNodeWithTag(PreviewTestTags.PAGE_INDICATOR).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.preview_page_indicator, 1, 2),
        ).assertIsDisplayed()
        composeRule.onNodeWithTag(PreviewTestTags.ACTION_RETAKE).assertIsDisplayed()
        composeRule.onNodeWithTag(PreviewTestTags.ACTION_ROTATE).assertIsDisplayed()
        composeRule.onNodeWithTag(PreviewTestTags.ACTION_CROP).assertIsDisplayed()
        composeRule.onNodeWithTag(PreviewTestTags.ACTION_ADD_PAGE).assertIsDisplayed()
        composeRule.onNodeWithTag(PreviewTestTags.ACTION_DELETE).assertIsDisplayed()
        composeRule.onNodeWithTag(PreviewTestTags.ACTION_PROCESS).assertIsDisplayed()
        // En la primera página no se puede mover hacia el inicio.
        composeRule.onNodeWithTag(PreviewTestTags.ACTION_MOVE_UP).assertIsNotEnabled()
    }

    @Test
    fun failedPageEditExplainsThatTheOriginalPageWasPreserved() {
        composeRule.setContent {
            FacturaStockTheme {
                PreviewScreen(
                    state = fakeState(failure = PreviewContract.Failure.MUTATION_FAILED),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.preview_edit_error_title))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.preview_edit_error_message))
            .assertIsDisplayed()
        composeRule.onNodeWithTag(PreviewTestTags.PAGE_IMAGE).assertIsDisplayed()
    }

    @Test
    fun actionsReachTheHandler() {
        val received = mutableListOf<PreviewContract.Action>()
        composeRule.setContent {
            FacturaStockTheme {
                PreviewScreen(state = fakeState(), onAction = { received += it })
            }
        }

        composeRule.onNodeWithTag(PreviewTestTags.ACTION_ROTATE).performClick()
        composeRule.onNodeWithTag(PreviewTestTags.ACTION_PROCESS).performClick()
        composeRule.onNodeWithTag(PreviewTestTags.ACTION_ADD_PAGE).performClick()

        assertEquals(
            listOf(
                PreviewContract.Action.RotateClicked,
                PreviewContract.Action.ProcessClicked,
                PreviewContract.Action.AddPageClicked,
            ),
            received,
        )
    }

    @Test
    fun deleteAsksForConfirmationBeforeRemoving() {
        val received = mutableListOf<PreviewContract.Action>()
        composeRule.setContent {
            FacturaStockTheme {
                PreviewScreen(
                    state = fakeState(showDeleteConfirm = true),
                    onAction = { received += it },
                )
            }
        }

        composeRule.onNodeWithText(
            context.getString(R.string.preview_delete_title),
        ).assertIsDisplayed()
        composeRule.onNode(
            hasText(context.getString(R.string.preview_delete_confirm)) and
                hasAnyAncestor(hasTestTag(PreviewTestTags.DELETE_DIALOG)),
        ).performClick()

        assertEquals(listOf(PreviewContract.Action.DeleteConfirmed), received)
    }

    @Test
    fun croppingModeShowsOverlayAndApplyCancelControls() {
        val received = mutableListOf<PreviewContract.Action>()
        composeRule.setContent {
            FacturaStockTheme {
                PreviewScreen(
                    state = fakeState(
                        mode = PreviewContract.Mode.CROPPING,
                        cropDraft = ImageCrop(1_000, 1_000, 9_000, 9_000),
                    ),
                    onAction = { received += it },
                )
            }
        }

        composeRule.onNodeWithTag(PreviewTestTags.CROP_OVERLAY).assertIsDisplayed()
        PreviewContract.CropCorner.entries.forEach { corner ->
            composeRule.onNodeWithTag(PreviewTestTags.cropHandle(corner)).assertIsDisplayed()
        }
        composeRule.onNodeWithTag(PreviewTestTags.CROP_APPLY).performClick()

        assertEquals(listOf(PreviewContract.Action.CropConfirmed), received)
    }

    @Test
    fun cropActionsRemainReachableInACompactWindowAtTwoHundredPercent() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                FacturaStockTheme {
                    Box(Modifier.width(360.dp).height(240.dp)) {
                        PreviewScreen(
                            state = fakeState(mode = PreviewContract.Mode.CROPPING),
                            onAction = {},
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag(PreviewTestTags.CROP_CANCEL)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(PreviewTestTags.CROP_APPLY)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun viewingKeepsTheImageAndProcessActionReachableInACompactWindow() {
        composeRule.setContent {
            FacturaStockTheme {
                Box(Modifier.width(360.dp).height(240.dp)) {
                    PreviewScreen(state = fakeState(), onAction = {})
                }
            }
        }

        composeRule.onNodeWithTag(PreviewTestTags.PAGE_IMAGE).assertIsDisplayed()
        composeRule.onNodeWithTag(PreviewTestTags.ACTION_PROCESS)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun combinedErrorsDoNotCollapseTheImageOrHideRecoveryInACompactWindow() {
        composeRule.setContent {
            FacturaStockTheme {
                Box(Modifier.width(360.dp).height(240.dp)) {
                    PreviewScreen(
                        state = fakeState(
                            processFailed = true,
                            failure = PreviewContract.Failure.MUTATION_FAILED,
                        ),
                        onAction = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag(PreviewTestTags.PAGE_IMAGE).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.preview_process_error_title))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.preview_edit_error_title))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(PreviewTestTags.ACTION_PROCESS)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun cropHandlesExposeDistinctTalkBackLabelsPositionsAndActions() {
        val received = mutableListOf<PreviewContract.Action>()
        composeRule.setContent {
            FacturaStockTheme {
                PreviewScreen(
                    state = fakeState(
                        mode = PreviewContract.Mode.CROPPING,
                        cropDraft = ImageCrop(1_000, 1_000, 9_000, 9_000),
                    ),
                    onAction = { received += it },
                )
            }
        }

        val cornerDescriptions = mapOf(
            PreviewContract.CropCorner.TOP_LEFT to R.string.preview_crop_handle_top_left,
            PreviewContract.CropCorner.TOP_RIGHT to R.string.preview_crop_handle_top_right,
            PreviewContract.CropCorner.BOTTOM_LEFT to R.string.preview_crop_handle_bottom_left,
            PreviewContract.CropCorner.BOTTOM_RIGHT to R.string.preview_crop_handle_bottom_right,
        )
        assertEquals(4, cornerDescriptions.values.map(context::getString).toSet().size)
        cornerDescriptions.forEach { (corner, descriptionRes) ->
            composeRule.onNodeWithTag(PreviewTestTags.cropHandle(corner))
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.ContentDescription,
                        listOf(context.getString(descriptionRes)),
                    ),
                )
        }

        val topLeft = composeRule.onNodeWithTag(
            PreviewTestTags.cropHandle(PreviewContract.CropCorner.TOP_LEFT),
        )
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    context.getString(R.string.preview_crop_handle_position, 10, 10),
                ),
            )

        val actions = topLeft.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(
            listOf(
                R.string.preview_crop_move_left,
                R.string.preview_crop_move_right,
                R.string.preview_crop_move_up,
                R.string.preview_crop_move_down,
            ).map(context::getString),
            actions.map { it.label },
        )
        assertTrue(
            actions.single {
                it.label == context.getString(R.string.preview_crop_move_right)
            }.action(),
        )
        assertEquals(
            listOf(
                PreviewContract.Action.CropCornerDragged(
                    corner = PreviewContract.CropCorner.TOP_LEFT,
                    xFraction = 1_500,
                    yFraction = 1_000,
                ),
            ),
            received,
        )
    }

    @Test
    fun qualityWarningExplainsBlurAndExposureAndOffersContinueOrRetake() {
        val received = mutableListOf<PreviewContract.Action>()
        composeRule.setContent {
            FacturaStockTheme {
                PreviewScreen(
                    state = fakeState(
                        qualityWarnings = listOf(
                            ImageQualityReport(
                                imageId = IMAGE_ID_1,
                                effectiveWidthPx = 3_000,
                                effectiveHeightPx = 4_000,
                                meanLuminance = 42,
                                darkPixelsPermille = 680,
                                brightPixelsPermille = 0,
                                sharpnessScore = 500,
                                estimatedSkewTenths = 0,
                                borderContentPermille = 0,
                                warnings = listOf(
                                    ImageQualityWarning.PossibleBlur(
                                        sharpnessScore = 500,
                                        recommendedMinimum = 1_200,
                                    ),
                                    ImageQualityWarning.PossibleUnderexposure(42, 680),
                                ),
                            ),
                        ),
                    ),
                    onAction = { received += it },
                )
            }
        }

        composeRule.onNodeWithText(
            context.getString(R.string.preview_quality_warning_title),
        ).assertIsDisplayed()
        val detail = context.getString(
            R.string.preview_quality_underexposed,
            42,
            "68.0%",
        )
        composeRule.onNodeWithText(
            context.getString(R.string.preview_quality_warning_page, 1, detail),
            substring = true,
        ).assertIsDisplayed()
        val blurDetail = context.getString(
            R.string.preview_quality_blur,
            500,
            1_200,
        )
        composeRule.onNodeWithText(
            context.getString(R.string.preview_quality_warning_page, 1, blurDetail),
            substring = true,
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.preview_quality_continue),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.preview_quality_retake),
        ).assertIsDisplayed()

        // Cerrar incidentalmente el modal nunca equivale a reemplazar la página.
        assertTrue(
            InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
                AccessibilityService.GLOBAL_ACTION_BACK,
            ),
        )
        composeRule.waitForIdle()
        assertEquals(emptyList<PreviewContract.Action>(), received)
        composeRule.onNodeWithText(
            context.getString(R.string.preview_quality_warning_title),
        ).assertIsDisplayed()

        composeRule.onNodeWithText(
            context.getString(R.string.preview_quality_continue),
        ).performClick()
        composeRule.onNodeWithText(
            context.getString(R.string.preview_quality_retake),
        ).performClick()

        assertEquals(
            listOf(
                PreviewContract.Action.ContinueWithWarningsClicked,
                PreviewContract.Action.RetakeWarnedPageClicked,
            ),
            received,
        )
    }

    private fun fakeState(
        mode: PreviewContract.Mode = PreviewContract.Mode.VIEWING,
        cropDraft: ImageCrop? = null,
        showDeleteConfirm: Boolean = false,
        qualityWarnings: List<ImageQualityReport> = emptyList(),
        processFailed: Boolean = false,
        failure: PreviewContract.Failure? = null,
    ): PreviewContract.State = PreviewContract.State(
        draftId = DraftId.from(UUID.fromString("00000000-0000-4000-8000-0000000000d1")),
        pages = listOf(
            PreviewContract.Page(
                imageId = IMAGE_ID_1,
                filePath = "draft_images/test/p1.jpg",
                widthPx = 3_000,
                heightPx = 4_000,
                rotationDegrees = 0,
                crop = null,
                pageIndex = 0,
            ),
            PreviewContract.Page(
                imageId = ImageId.from(UUID.fromString("00000000-0000-4000-8000-0000000000a2")),
                filePath = "draft_images/test/p2.jpg",
                widthPx = 3_000,
                heightPx = 4_000,
                rotationDegrees = 90,
                crop = null,
                pageIndex = 1,
            ),
        ),
        currentIndex = 0,
        mode = mode,
        cropDraft = cropDraft,
        showDeleteConfirm = showDeleteConfirm,
        qualityWarnings = qualityWarnings,
        processFailed = processFailed,
        failure = failure,
    )

    private companion object {
        val IMAGE_ID_1: ImageId = ImageId.from(
            UUID.fromString("00000000-0000-4000-8000-0000000000a1"),
        )
    }
}
