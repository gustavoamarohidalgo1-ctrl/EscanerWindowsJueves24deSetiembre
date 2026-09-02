package com.facturastock.app.feature.settings

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.facturastock.app.R
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.model.Business
import com.facturastock.app.domain.model.DemoPurchaseScenario
import com.facturastock.app.domain.model.PrivacyMaintenanceStep
import com.facturastock.app.domain.model.RetentionSweepReport
import com.facturastock.app.domain.model.ImageRetentionPolicy
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.ui.theme.FacturaStockTheme
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun demoScenarioActionIsVisibleInDemoAndDispatchesStart() {
        val actions = mutableListOf<SettingsContract.Action>()
        composeRule.setContent {
            FacturaStockTheme {
                SettingsScreen(
                    state = state(inDemo = true),
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithTag(SettingsTestTags.DEMO_SCENARIO_ACTION)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(SettingsContract.Action.StartDemoScenario), actions)
        }
    }

    @Test
    fun demoScenarioActionIsAbsentOutsideDemo() {
        composeRule.setContent {
            FacturaStockTheme {
                SettingsScreen(
                    state = state(inDemo = false),
                    onAction = {},
                )
            }
        }
        composeRule.onNodeWithTag(SettingsTestTags.DEMO_SCENARIO_ACTION).assertDoesNotExist()
    }

    @Test
    fun busyAndConflictRemainVisibleAndBlockAnotherTap() {
        composeRule.setContent {
            FacturaStockTheme {
                SettingsScreen(
                    state = state(
                        inDemo = true,
                        isStarting = true,
                        failure = SettingsContract.DemoScenarioFailure.CONFLICT,
                    ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithTag(SettingsTestTags.DEMO_SCENARIO_ACTION)
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsNotEnabled()
        composeRule.onNodeWithText(context.getString(R.string.demo_scenario_conflict_error))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun diagnosticsConsentDefaultsOffAndDispatchesExplicitOptInThenOptOut() {
        val actions = mutableListOf<SettingsContract.Action>()
        composeRule.setContent {
            val uiState = remember {
                mutableStateOf(state(inDemo = false, diagnosticsEnabled = false))
            }
            FacturaStockTheme {
                SettingsScreen(
                    state = uiState.value,
                    onAction = { action ->
                        actions += action
                        if (action is SettingsContract.Action.DiagnosticsConsentChanged) {
                            uiState.value = uiState.value.copy(
                                config = requireNotNull(uiState.value.config).copy(
                                    diagnosticsEnabled = action.enabled,
                                ),
                            )
                        }
                    },
                )
            }
        }

        val consent = composeRule.onNodeWithTag(SettingsTestTags.DIAGNOSTICS_CONSENT)
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsOff()
        consent.performClick().assertIsOn()
        consent.performClick().assertIsOff()

        composeRule.runOnIdle {
            assertEquals(
                listOf(
                    SettingsContract.Action.DiagnosticsConsentChanged(true),
                    SettingsContract.Action.DiagnosticsConsentChanged(false),
                ),
                actions,
            )
        }
    }

    @Test
    fun configuredPrivacyPolicyIsVisibleAndDispatchesOpen() {
        val actions = mutableListOf<SettingsContract.Action>()
        composeRule.setContent {
            FacturaStockTheme {
                SettingsScreen(
                    state = state(inDemo = false),
                    onAction = actions::add,
                    privacyPolicyAvailable = true,
                )
            }
        }

        composeRule.onNodeWithTag(SettingsTestTags.PRIVACY_POLICY_ACTION)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(SettingsContract.Action.OpenPrivacyPolicy), actions)
        }
    }

    @Test
    fun backupAndDocumentBackupAreSeparateOptIns() {
        val actions = mutableListOf<SettingsContract.Action>()
        composeRule.setContent {
            val uiState = remember { mutableStateOf(state(inDemo = false)) }
            FacturaStockTheme {
                SettingsScreen(
                    state = uiState.value,
                    onAction = { action ->
                        actions += action
                        when (action) {
                            is SettingsContract.Action.BackupEnabledChanged ->
                                uiState.value = uiState.value.copy(
                                    config = requireNotNull(uiState.value.config).copy(
                                        backupEnabled = action.enabled,
                                    ),
                                )

                            is SettingsContract.Action.DocumentBackupEnabledChanged ->
                                uiState.value = uiState.value.copy(
                                    config = requireNotNull(uiState.value.config).copy(
                                        documentBackupEnabled = action.enabled,
                                    ),
                                )

                            else -> Unit
                        }
                    },
                )
            }
        }

        val document = composeRule.onNodeWithTag(SettingsTestTags.DOCUMENT_BACKUP_CONSENT)
            .performScrollTo()
            .assertIsOff()
            .assertIsNotEnabled()
        composeRule.onNodeWithTag(SettingsTestTags.BACKUP_CONSENT)
            .performScrollTo()
            .assertIsOff()
            .performClick()
            .assertIsOn()
        document.assertIsEnabled().performClick().assertIsOn()

        composeRule.runOnIdle {
            assertEquals(
                listOf(
                    SettingsContract.Action.BackupEnabledChanged(true),
                    SettingsContract.Action.DocumentBackupEnabledChanged(true),
                ),
                actions,
            )
        }
    }

    @Test
    fun privacyActionsDispatchExportCleanupAndConfirmedLocalImageDeletion() {
        val actions = mutableListOf<SettingsContract.Action>()
        composeRule.setContent {
            val uiState = remember { mutableStateOf(state(inDemo = false)) }
            FacturaStockTheme {
                SettingsScreen(
                    state = uiState.value,
                    onAction = { action ->
                        actions += action
                        if (action == SettingsContract.Action.DeleteImages) {
                            uiState.value = uiState.value.copy(showDeleteImagesDialog = true)
                        }
                    },
                )
            }
        }

        composeRule.onNodeWithTag(SettingsTestTags.EXPORT_DATA)
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(SettingsTestTags.CLEAN_PRIVATE_FILES)
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag(SettingsTestTags.DELETE_IMAGES)
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithText(context.getString(R.string.settings_delete_images_confirm))
            .assertIsDisplayed()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(
                listOf(
                    SettingsContract.Action.ExportData,
                    SettingsContract.Action.CleanPrivateFiles,
                    SettingsContract.Action.DeleteImages,
                    SettingsContract.Action.ConfirmDeleteImages,
                ),
                actions,
            )
        }
    }

    @Test
    fun completedPrivacyOperationIsAnnouncedPolitely() {
        composeRule.setContent {
            FacturaStockTheme {
                SettingsScreen(
                    state = state(inDemo = false).copy(
                        privacyResult = SettingsContract.PrivacyResult.BackupUpdated(
                            enabled = true,
                            schedulerUpdated = true,
                        ),
                    ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithTag(SettingsTestTags.PRIVACY_RESULT)
            .performScrollTo()
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            )
    }

    @Test
    fun failedCleanupStageIsReportedAsPartialInsteadOfSuccess() {
        composeRule.setContent {
            FacturaStockTheme {
                SettingsScreen(
                    state = state(inDemo = false).copy(
                        privacyResult = SettingsContract.PrivacyResult.FilesCleaned(
                            RetentionSweepReport(
                                staleImports = 0,
                                staleCacheEntries = 2,
                                stalePrivateTemps = 0,
                                orphanDraftDirs = 0,
                                committedOcrRuns = 0,
                                afterOcrDeleteAttempted = 0,
                                afterOcrDeleted = 0,
                                afterOcrAlreadyAbsent = 0,
                                afterOcrDeleteFailed = 0,
                                retentionDeleteAttempted = 0,
                                purgeIntentAttempted = 0,
                                purgeIntentDurable = 0,
                                purgeIntentNotRequired = 0,
                                purgeIntentFailed = 0,
                                retentionDeleted = 0,
                                retentionAlreadyAbsent = 0,
                                retentionDeleteFailed = 0,
                                encryptedMigrated = 0,
                                encryptionAttempted = 0,
                                encryptionAlreadySatisfied = 0,
                                encryptionFailed = 0,
                                failedSteps = setOf(PrivacyMaintenanceStep.STALE_IMPORTS),
                            ),
                        ),
                    ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.settings_cleanup_partial_title))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.settings_cleanup_success_title))
            .assertDoesNotExist()
    }

    @Test
    fun legacyUnknownDocumentDestinationRequiresVisibleManualReview() {
        composeRule.setContent {
            FacturaStockTheme {
                SettingsScreen(
                    state = state(inDemo = false).copy(
                        privacyResult = SettingsContract.PrivacyResult.ImagesDeleted(
                            RetentionSweepReport(
                                staleImports = 0,
                                staleCacheEntries = 0,
                                stalePrivateTemps = 0,
                                orphanDraftDirs = 0,
                                committedOcrRuns = 0,
                                afterOcrDeleteAttempted = 0,
                                afterOcrDeleted = 0,
                                afterOcrAlreadyAbsent = 0,
                                afterOcrDeleteFailed = 0,
                                retentionDeleteAttempted = 1,
                                purgeIntentAttempted = 1,
                                purgeIntentDurable = 0,
                                purgeIntentNotRequired = 0,
                                purgeIntentFailed = 0,
                                purgeIntentLegacyDestinationUnknown = 1,
                                retentionDeleted = 0,
                                retentionAlreadyAbsent = 0,
                                retentionDeleteFailed = 1,
                                encryptedMigrated = 1,
                                encryptionAttempted = 1,
                                encryptionAlreadySatisfied = 0,
                                encryptionFailed = 0,
                                failedSteps = emptySet(),
                            ),
                        ),
                    ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithText("destino histórico desconocido", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("requieren revisión manual", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun uncleanExportDestinationShowsManualDeletionWarning() {
        composeRule.setContent {
            FacturaStockTheme {
                SettingsScreen(
                    state = state(inDemo = false).copy(
                        privacyResult =
                            SettingsContract.PrivacyResult.ExportDestinationCleanupUnconfirmed,
                    ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.settings_export_partial_title))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.settings_export_partial_message))
            .assertIsDisplayed()
    }

    @Test
    fun destructiveRetentionPolicyRequiresExplicitConfirmation() {
        val actions = mutableListOf<SettingsContract.Action>()
        composeRule.setContent {
            FacturaStockTheme {
                SettingsScreen(
                    state = state(inDemo = false).copy(
                        pendingImageRetentionPolicy = ImageRetentionPolicy.DAYS_30,
                    ),
                    onAction = actions::add,
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.settings_retention_dialog_title))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.settings_retention_confirm))
            .performClick()
        composeRule.runOnIdle {
            assertEquals(
                listOf(SettingsContract.Action.ConfirmImageRetentionPolicy),
                actions,
            )
        }
    }

    private fun state(
        inDemo: Boolean,
        isStarting: Boolean = false,
        failure: SettingsContract.DemoScenarioFailure? = null,
        diagnosticsEnabled: Boolean = false,
    ): SettingsContract.State {
        val activeId = if (inDemo) DEMO_BUSINESS_ID else REAL_BUSINESS_ID
        return SettingsContract.State(
            config = AppConfiguration.defaults().copy(
                businessId = REAL_BUSINESS_ID,
                demoBusinessId = DEMO_BUSINESS_ID.takeIf { inDemo },
                diagnosticsEnabled = diagnosticsEnabled,
            ),
            business = Business(
                businessId = activeId,
                legalName = if (inDemo) {
                    DemoPurchaseScenario.BUSINESS_LEGAL_NAME
                } else {
                    "Bodega de prueba"
                },
                createdAt = NOW,
                updatedAt = NOW,
            ),
            legalName = if (inDemo) {
                DemoPurchaseScenario.BUSINESS_LEGAL_NAME
            } else {
                "Bodega de prueba"
            },
            isStartingDemoScenario = isStarting,
            demoScenarioFailure = failure,
        )
    }

    private companion object {
        val REAL_BUSINESS_ID = BusinessId.from(
            UUID.fromString("10000000-0000-4000-8000-000000000001"),
        )
        val DEMO_BUSINESS_ID = BusinessId.from(
            UUID.fromString("20000000-0000-4000-8000-000000000002"),
        )
        val NOW: Instant = Instant.parse("2026-08-14T12:00:00Z")
    }
}
