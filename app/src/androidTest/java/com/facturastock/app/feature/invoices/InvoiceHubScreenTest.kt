package com.facturastock.app.feature.invoices

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.facturastock.app.ui.theme.FacturaStockTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InvoiceHubScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsTheTwoStepActionAndCatalogOnlyEffect() {
        var registrations = 0
        composeRule.setContent {
            FacturaStockTheme {
                InvoiceHubScreen(
                    isCreating = false,
                    creationFailed = false,
                    onRegister = { registrations++ },
                )
            }
        }

        composeRule.onNodeWithTag(InvoiceHubTestTags.REGISTER_ACTION)
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        assertEquals(1, registrations)

        composeRule.onNodeWithTag(InvoiceHubTestTags.TWO_STEP_GUIDE)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("1. Abre la cámara.").assertIsDisplayed()
        composeRule.onNodeWithText("2. Toma una foto completa de la factura.")
            .assertIsDisplayed()

        composeRule.onNodeWithTag(InvoiceHubTestTags.CATALOG_ONLY_NOTICE)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(
            "Solo guardaremos en Productos los artículos detectados en la factura. " +
                "No registraremos una compra ni cambiaremos las existencias.",
        ).assertIsDisplayed()
    }

    @Test
    fun creatingStateShowsProgressAndPreventsDuplicateRegistration() {
        var registrations = 0
        composeRule.setContent {
            FacturaStockTheme {
                InvoiceHubScreen(
                    isCreating = true,
                    creationFailed = false,
                    onRegister = { registrations++ },
                )
            }
        }

        composeRule.onNodeWithTag(InvoiceHubTestTags.CREATION_PROGRESS)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(InvoiceHubTestTags.REGISTER_ACTION)
            .assertIsNotEnabled()
            .assertTextContains("Abriendo cámara…")
        assertEquals(0, registrations)
    }

    @Test
    fun failedCreationExplainsNoInventoryChangeAndOffersRetry() {
        var failed by mutableStateOf(true)
        var registrations = 0
        composeRule.setContent {
            FacturaStockTheme {
                InvoiceHubScreen(
                    isCreating = false,
                    creationFailed = failed,
                    onRegister = {
                        registrations++
                        failed = false
                    },
                )
            }
        }

        composeRule.onNodeWithTag(InvoiceHubTestTags.CREATION_ERROR)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(
            "No se pudo abrir la cámara. No se guardó ningún producto.",
        ).assertIsDisplayed()
        composeRule.onNodeWithTag(InvoiceHubTestTags.REGISTER_ACTION)
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertTextContains("Reintentar")
            .performClick()
        composeRule.runOnIdle { assertEquals(1, registrations) }
    }
}
