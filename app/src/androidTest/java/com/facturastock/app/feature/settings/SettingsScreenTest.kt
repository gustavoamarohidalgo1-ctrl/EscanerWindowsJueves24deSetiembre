package com.facturastock.app.feature.settings

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
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
    fun showsOnlyBusinessProfileTaxAndDataExport() {
        composeRule.setContent {
            FacturaStockTheme {
                SettingsScreen(state = state(), onAction = {})
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.settings_section_business))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.settings_section_tax))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.settings_section_data))
            .performScrollTo()
            .assertIsDisplayed()
        // Sin nube, demo ni mantenimiento de fotos: esas secciones ya no existen.
        listOf("Cuenta y respaldo", "Privacidad y diagnóstico", "Modo demostración").forEach { removed ->
            composeRule.onNodeWithText(removed).assertDoesNotExist()
        }
    }

    @Test
    fun exportButtonDispatchesExport() {
        val actions = mutableListOf<SettingsContract.Action>()
        composeRule.setContent {
            FacturaStockTheme {
                SettingsScreen(state = state(), onAction = actions::add)
            }
        }

        composeRule.onNodeWithTag(SettingsTestTags.EXPORT_DATA)
            .performScrollTo()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(listOf<SettingsContract.Action>(SettingsContract.Action.ExportData), actions)
        }
    }

    @Test
    fun exportIsDisabledWhileAnotherExportRuns() {
        composeRule.setContent {
            FacturaStockTheme {
                SettingsScreen(state = state().copy(isExporting = true), onAction = {})
            }
        }

        composeRule.onNodeWithTag(SettingsTestTags.EXPORT_DATA)
            .performScrollTo()
            .assertIsNotEnabled()
    }

    @Test
    fun completedExportIsAnnouncedPolitely() {
        composeRule.setContent {
            FacturaStockTheme {
                SettingsScreen(
                    state = state().copy(
                        exportResult = SettingsContract.ExportResult.Exported(
                            products = 3,
                            suppliers = 1,
                            units = 2,
                            inventoryLocations = 1,
                            supplierProductAliases = 0,
                            purchases = 4,
                            purchaseLines = 9,
                            inventoryBalances = 3,
                            stockMovements = 12,
                            auditEvents = 5,
                            retainedImageMetadata = 0,
                        ),
                    ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithTag(SettingsTestTags.EXPORT_RESULT)
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
    fun uncleanExportDestinationShowsManualDeletionWarning() {
        composeRule.setContent {
            FacturaStockTheme {
                SettingsScreen(
                    state = state().copy(
                        exportResult = SettingsContract.ExportResult.DestinationCleanupUnconfirmed,
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

    private fun state(): SettingsContract.State =
        SettingsContract.State(
            config = AppConfiguration.defaults().copy(businessId = BUSINESS_ID),
            business = Business(
                businessId = BUSINESS_ID,
                legalName = "Bodega de prueba",
                createdAt = NOW,
                updatedAt = NOW,
            ),
            legalName = "Bodega de prueba",
        )

    private companion object {
        val BUSINESS_ID = BusinessId.from(
            UUID.fromString("10000000-0000-4000-8000-000000000001"),
        )
        val NOW: Instant = Instant.parse("2026-08-14T12:00:00Z")
    }
}
