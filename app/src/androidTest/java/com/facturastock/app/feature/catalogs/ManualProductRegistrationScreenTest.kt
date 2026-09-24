package com.facturastock.app.feature.catalogs

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.feature.catalogs.CatalogsContract.Action
import com.facturastock.app.feature.catalogs.CatalogsContract.Failure
import com.facturastock.app.feature.catalogs.CatalogsContract.Form
import com.facturastock.app.feature.catalogs.CatalogsContract.State
import com.facturastock.app.ui.theme.FacturaStockTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ManualProductRegistrationScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun manualEntryShowsOnlyFourFieldsAndSavesWithoutRequiringACode() {
        val state = mutableStateOf(manualState())
        val actions = mutableListOf<Action>()
        composeRule.setContent {
            FacturaStockTheme {
                CatalogsScreen(
                    state = state.value,
                    onAction = { action ->
                        actions += action
                        val form = state.value.form as Form.ProductForm
                        state.value =
                            when (action) {
                                is Action.ProductNameChanged -> state.value.copy(form = form.copy(title = action.value))
                                is Action.ProductQuantityChanged -> state.value.copy(form = form.copy(quantity = action.value))
                                is Action.ProductPurchasePriceChanged -> state.value.copy(form = form.copy(purchasePrice = action.value))
                                is Action.ProductPriceChanged -> state.value.copy(form = form.copy(salePrice = action.value))
                                Action.SaveForm -> state.value.copy(isSaving = true)
                                else -> state.value
                            }
                    },
                )
            }
        }

        assertOnlyManualScreen()
        composeRule.onNodeWithText("Registrar producto").assertIsDisplayed()
        FIELD_TAGS.forEach { tag ->
            composeRule
                .onNodeWithTag(tag)
                .performScrollTo()
                .assertIsDisplayed()
                .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText))
        }
        assertSaveDisabled()
        replaceField(CatalogsTestTags.PRODUCT_NAME, "Café tostado")
        assertSaveDisabled()
        replaceField(CatalogsTestTags.PRODUCT_QUANTITY, "2,5")
        assertSaveDisabled()
        replaceField(CatalogsTestTags.PRODUCT_PURCHASE_PRICE, "4,25")
        assertSaveDisabled()
        replaceField(CatalogsTestTags.PRODUCT_SALE_PRICE, "6,50")
        composeRule.onNodeWithTag(CatalogsTestTags.SAVE_FORM).performScrollTo().assertIsEnabled()
        replaceField(CatalogsTestTags.PRODUCT_QUANTITY, "0")
        assertSaveDisabled()
        replaceField(CatalogsTestTags.PRODUCT_QUANTITY, "2,5")
        composeRule
            .onNodeWithTag(CatalogsTestTags.SAVE_FORM)
            .performScrollTo()
            .assertIsEnabled()
            .performClick()

        composeRule.onNodeWithTag(CatalogsTestTags.SAVE_FORM).assertIsNotEnabled().performClick()
        composeRule
            .onNodeWithText("Cancelar")
            .performScrollTo()
            .assertIsNotEnabled()
            .performClick()
        FIELD_TAGS.forEach { tag ->
            composeRule.onNodeWithTag(tag).performScrollTo().assertIsNotEnabled()
        }
        assertOnlyManualScreen()
        composeRule.runOnIdle {
            val form = state.value.form as Form.ProductForm
            assertEquals("", form.barcode)
            assertEquals("", form.sku)
            assertEquals("2,5", form.quantity)
            assertEquals("4,25", form.purchasePrice)
            assertEquals("6,50", form.salePrice)
            assertEquals(1, actions.count { it == Action.SaveForm })
            assertFalse(actions.any { it == Action.CloseForm || it is Action.ProductBarcodeChanged || it is Action.ProductSkuChanged })
        }
    }

    @Test
    fun preparationAndRetryKeepTheManualScreenWithoutCatalogsOrDialogs() {
        val state = mutableStateOf(State())
        val actions = mutableListOf<Action>()
        composeRule.setContent {
            FacturaStockTheme {
                CatalogsScreen(state.value, actions::add, isManualRegistration = true)
            }
        }

        // La ruta ya conoce el origen manual antes de que llegue el primer estado del ViewModel.
        assertOnlyManualScreen()
        FIELD_TAGS.forEach { tag ->
            composeRule.onNodeWithTag(tag).performScrollTo().assertIsNotEnabled()
        }
        assertSaveDisabled()
        composeRule.onNodeWithContentDescription("Preparando el formulario…").performScrollTo().assertIsDisplayed()
        composeRule.runOnIdle {
            state.value = State(isManualEntryPending = true, manualEntryFailure = Failure.LOAD_FAILED)
        }
        assertOnlyManualScreen()
        composeRule
            .onNodeWithText("Intentar de nuevo")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeRule
            .onNodeWithText("Cancelar")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(Action.Retry, Action.CloseForm), actions)
        }
    }

    @Test
    fun manualSaveNeedsAResolvedLocationWithoutDependingOnCatalogLists() {
        val validForm =
            (manualState().form as Form.ProductForm).copy(
                title = "Café tostado",
                quantity = "3",
                purchasePrice = "0",
                salePrice = "1",
            )
        val state = mutableStateOf(manualState().copy(form = validForm.copy(locationId = null)))
        val actions = mutableListOf<Action>()
        composeRule.setContent {
            FacturaStockTheme { CatalogsScreen(state.value, actions::add) }
        }

        assertSaveDisabled()
        // El alta resuelve y revalida el almacén; no depende de cargar los catálogos auxiliares.
        composeRule.runOnIdle { state.value = state.value.copy(form = validForm) }
        composeRule.onNodeWithTag(CatalogsTestTags.SAVE_FORM).performScrollTo().assertIsEnabled()
        composeRule
            .onNodeWithText("Cancelar")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeRule.runOnIdle { assertEquals(listOf(Action.CloseForm), actions) }
    }

    private fun assertOnlyManualScreen() {
        composeRule.onNodeWithTag(CatalogsTestTags.FORM).assertIsDisplayed()
        composeRule.onAllNodes(isDialog()).assertCountEquals(0)
        CatalogsContract.visibleSections.forEach { section ->
            composeRule.onNodeWithTag(CatalogsTestTags.tab(section)).assertDoesNotExist()
        }
        listOf(
            CatalogsTestTags.SEARCH,
            CatalogsTestTags.LIST,
            CatalogsTestTags.ADD,
            CatalogsTestTags.PRODUCT_BARCODE,
            CatalogsTestTags.PRODUCT_SKU,
        ).forEach { tag -> composeRule.onNodeWithTag(tag).assertDoesNotExist() }
    }

    private fun replaceField(
        tag: String,
        value: String,
    ) {
        composeRule.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)
        composeRule.onNodeWithTag(tag).assertTextContains(value)
    }

    private fun assertSaveDisabled() {
        composeRule.onNodeWithTag(CatalogsTestTags.SAVE_FORM).performScrollTo().assertIsNotEnabled()
    }

    private fun manualState() =
        State(
            form = Form.ProductForm(isManualRegistration = true, locationId = LOCATION_ID),
        )

    private companion object {
        val LOCATION_ID = LocationId.from(UUID.fromString("00000000-0000-4000-8000-000000000001"))
        val FIELD_TAGS =
            listOf(
                CatalogsTestTags.PRODUCT_NAME,
                CatalogsTestTags.PRODUCT_QUANTITY,
                CatalogsTestTags.PRODUCT_PURCHASE_PRICE,
                CatalogsTestTags.PRODUCT_SALE_PRICE,
            )
    }
}
