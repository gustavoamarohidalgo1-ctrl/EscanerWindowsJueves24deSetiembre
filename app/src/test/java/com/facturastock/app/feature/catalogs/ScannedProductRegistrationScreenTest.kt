package com.facturastock.app.feature.catalogs

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.feature.catalogs.CatalogsContract.Action
import com.facturastock.app.feature.catalogs.CatalogsContract.Form
import com.facturastock.app.feature.catalogs.CatalogsContract.State
import com.facturastock.app.ui.theme.FacturaStockTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Prueba el alta escaneada con estado local simulado, sin cámara, lector ni base de datos. */
class ScannedProductRegistrationScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun scannedBarcodeKeepsLeadingZerosAndIsReadOnlyWhileRequiredFieldsAreEditable() {
        composeRule.setContent {
            FacturaStockTheme {
                CatalogsScreen(
                    state = State(form = scannedForm()),
                    onAction = {},
                )
            }
        }

        composeRule
            .onNodeWithTag(CatalogsTestTags.PRODUCT_BARCODE)
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextContains(SCANNED_BARCODE)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetText))
        listOf(
            CatalogsTestTags.PRODUCT_NAME,
            CatalogsTestTags.PRODUCT_QUANTITY,
            CatalogsTestTags.PRODUCT_PURCHASE_PRICE,
            CatalogsTestTags.PRODUCT_SALE_PRICE,
        ).forEach { tag ->
            composeRule
                .onNodeWithTag(tag)
                .performScrollTo()
                .assertIsDisplayed()
                .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText))
        }
        composeRule
            .onNodeWithTag(CatalogsTestTags.SAVE_FORM)
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsNotEnabled()
    }

    @Test
    fun completingFourFieldsPreservesDecimalCommasAndEnablesSave() {
        val form = mutableStateOf(scannedForm())
        val actions = mutableListOf<Action>()
        composeRule.setContent {
            FacturaStockTheme {
                CatalogsScreen(
                    state = State(form = form.value),
                    onAction = { action ->
                        actions += action
                        form.value =
                            when (action) {
                                is Action.ProductNameChanged -> form.value.copy(title = action.value)
                                is Action.ProductQuantityChanged -> form.value.copy(quantity = action.value)
                                is Action.ProductPurchasePriceChanged -> form.value.copy(purchasePrice = action.value)
                                is Action.ProductPriceChanged -> form.value.copy(salePrice = action.value)
                                else -> form.value
                            }
                    },
                )
            }
        }

        assertSaveDisabled()
        replaceField(CatalogsTestTags.PRODUCT_NAME, "Arroz extra")
        assertSaveDisabled()
        replaceField(CatalogsTestTags.PRODUCT_QUANTITY, "2,5")
        assertSaveDisabled()
        replaceField(CatalogsTestTags.PRODUCT_PURCHASE_PRICE, "4,25")
        assertSaveDisabled()
        replaceField(CatalogsTestTags.PRODUCT_SALE_PRICE, "6,50")

        composeRule
            .onNodeWithTag(CatalogsTestTags.SAVE_FORM)
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()

        composeRule.runOnIdle {
            assertTrue(actions.contains(Action.ProductQuantityChanged("2,5")))
            assertTrue(actions.contains(Action.ProductPurchasePriceChanged("4,25")))
            assertTrue(actions.contains(Action.ProductPriceChanged("6,50")))
            assertFalse(actions.any { it is Action.ProductBarcodeChanged })
            assertEquals(SCANNED_BARCODE, form.value.barcode)
            assertEquals(1, actions.count { it == Action.SaveForm })
        }
    }

    private fun replaceField(
        tag: String,
        value: String,
    ) {
        composeRule
            .onNodeWithTag(tag)
            .performScrollTo()
            .performTextReplacement(value)
        composeRule.onNodeWithTag(tag).assertTextContains(value)
    }

    private fun assertSaveDisabled() {
        composeRule
            .onNodeWithTag(CatalogsTestTags.SAVE_FORM)
            .performScrollTo()
            .assertIsNotEnabled()
    }

    private fun scannedForm() =
        Form.ProductForm(
            barcode = SCANNED_BARCODE,
            unitId = UnitId.from(UUID.fromString("a73123a0-d432-4e9b-92cf-97cf4c325f80")),
            isScannedRegistration = true,
        )

    private companion object {
        const val SCANNED_BARCODE = "0001234567895"
    }
}
