package com.facturastock.app.feature.catalogs

import android.content.res.Configuration
import android.util.DisplayMetrics
import android.view.ContextThemeWrapper
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.facturastock.app.R
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.ProductCatalogDetail
import com.facturastock.app.domain.model.ProductInventoryPosition
import com.facturastock.app.domain.model.ProductInventorySummary
import com.facturastock.app.domain.model.SupplierProductAlias
import com.facturastock.app.domain.model.UnitCost
import com.facturastock.app.domain.model.UnitOfMeasure
import com.facturastock.app.domain.model.id.AliasId
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.SupplierId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.feature.catalogs.CatalogsContract.Action
import com.facturastock.app.feature.catalogs.CatalogsContract.Detail
import com.facturastock.app.feature.catalogs.CatalogsContract.Form
import com.facturastock.app.feature.catalogs.CatalogsContract.Row
import com.facturastock.app.feature.catalogs.CatalogsContract.Section
import com.facturastock.app.feature.catalogs.CatalogsContract.State
import com.facturastock.app.ui.theme.FacturaStockTheme
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.math.roundToInt
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun tabsSearchAndAddAreDiscoverableAndEmitActions() {
        val actions = mutableListOf<Action>()
        composeRule.setContent {
            FacturaStockTheme {
                CatalogsScreen(
                    state = State(rows = listOf(Row.ProductRow(product()))),
                    onAction = actions::add,
                )
            }
        }

        Section.entries.forEach { section ->
            composeRule.onNodeWithTag(CatalogsTestTags.tab(section)).assertIsDisplayed()
        }
        composeRule.onNodeWithTag(CatalogsTestTags.SEARCH).performTextInput("arroz")
        composeRule.onNodeWithTag(CatalogsTestTags.tab(Section.SUPPLIERS)).performClick()
        composeRule.onNodeWithTag(CatalogsTestTags.ADD).performClick()

        assertTrue(actions.any { it == Action.QueryChanged("arroz") })
        assertTrue(actions.any { it == Action.SectionSelected(Section.SUPPLIERS) })
        assertTrue(actions.any { it == Action.AddSelected })
        composeRule.onNodeWithText(context.getString(R.string.home_delete_dialog_title))
            .assertDoesNotExist()
    }

    @Test
    fun supplierFormExplainsThatRucValidationIsLocalAndOffersNoDelete() {
        composeRule.setContent {
            FacturaStockTheme {
                CatalogsScreen(
                    state = State(
                        section = Section.SUPPLIERS,
                        form = Form.SupplierForm(title = "Proveedor local"),
                    ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithTag(CatalogsTestTags.FORM).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.catalog_ruc_local_notice))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.action_save)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.catalog_action_archive))
            .assertDoesNotExist()
    }

    @Test
    fun supplierFormExposesPaneTitleAndCapsWidthInWideWindow() {
        val paneTitle = context.getString(R.string.catalog_create_supplier)
        composeRule.setContent {
            SimulatedWindow(width = 840.dp) {
                FacturaStockTheme {
                    CatalogsScreen(
                        state = State(
                            section = Section.SUPPLIERS,
                            form = Form.SupplierForm(title = "Proveedor local"),
                        ),
                        onAction = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag(CatalogsTestTags.FORM)
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, paneTitle),
            )
            .assertWidthIsEqualTo(560.dp)
    }

    @Test
    fun productFormActionsRemainReachableInCompactWindowAtTwoHundredPercentFontScale() {
        val actions = mutableListOf<Action>()
        composeRule.setContent {
            SimulatedWindow(width = 360.dp, fontScale = 2f) {
                FacturaStockTheme {
                    CatalogsScreen(
                        state = State(
                            form = Form.ProductForm(
                                title = "Arroz extra",
                                unitId = UNIT_ID,
                            ),
                        ),
                        onAction = actions::add,
                    )
                }
            }
        }

        val form = composeRule.onNodeWithTag(CatalogsTestTags.FORM).assertIsDisplayed()
        val formNode = form.fetchSemanticsNode()
        val formWidth = with(formNode.layoutInfo.density) { formNode.size.width.toDp() }
        assertTrue("Expected a compact form, but its width was $formWidth", formWidth <= 360.dp)
        assertTrue(
            "Expected 200% font scale, but it was ${formNode.layoutInfo.density.fontScale}",
            formNode.layoutInfo.density.fontScale == 2f,
        )
        composeRule.onNodeWithText(context.getString(R.string.action_save))
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        composeRule.onNodeWithText(context.getString(R.string.action_cancel))
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()

        assertTrue(actions.containsAll(listOf(Action.SaveForm, Action.CloseForm)))
    }

    @Test
    fun productDetailShowsUnitStockAverageCostAndSupplierAlias() {
        val product = product()
        val unit = unit()
        val detail = ProductCatalogDetail(
            product = product,
            unit = unit,
            aliases = listOf(
                SupplierProductAlias(
                    aliasId = AliasId.from(uuid(8)),
                    businessId = BUSINESS_ID,
                    supplierId = SupplierId.from(uuid(7)),
                    productId = product.productId,
                    alias = "ARROZ PROV 5KG",
                    createdAt = NOW,
                    updatedAt = NOW,
                ),
            ),
            inventory = ProductInventorySummary(
                listOf(
                    ProductInventoryPosition(
                        locationId = LOCATION_ID,
                        quantityOnHand = BigDecimal("25"),
                        averageUnitCost = UnitCost.of("4.50", CurrencyCode.of("PEN")),
                    ),
                ),
            ),
        )
        composeRule.setContent {
            FacturaStockTheme {
                CatalogsScreen(
                    state = State(
                        detail = Detail.ProductDetail(
                            row = Row.ProductRow(product),
                            catalogDetail = detail,
                            positions = listOf(
                                CatalogsContract.ProductPosition("Principal", "25", "4.50 PEN"),
                            ),
                            isLoading = false,
                        ),
                    ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithTag(CatalogsTestTags.DETAIL).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.catalog_product_unit_value, "NIU"),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.catalog_product_total_stock, "25", "NIU"),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.catalog_product_average_cost, "4.50 PEN"),
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("ARROZ PROV 5KG").performScrollTo().assertIsDisplayed()
    }

    private fun product() = Product(
        productId = PRODUCT_ID,
        businessId = BUSINESS_ID,
        unitId = UNIT_ID,
        name = "Arroz extra",
        locationId = LOCATION_ID,
        sku = "ARR-5",
        createdAt = NOW,
        updatedAt = NOW,
    )

    private fun unit() = UnitOfMeasure(
        unitId = UNIT_ID,
        businessId = BUSINESS_ID,
        code = "NIU",
        name = "Unidad",
        createdAt = NOW,
        updatedAt = NOW,
    )

    @Composable
    private fun SimulatedWindow(
        width: Dp,
        fontScale: Float = 1f,
        content: @Composable () -> Unit,
    ) {
        val hostView = LocalView.current
        val displayWidthPixels = hostView.resources.displayMetrics.widthPixels
        val simulatedDensity = displayWidthPixels / width.value
        val configuration = remember(hostView, width, fontScale) {
            Configuration(hostView.resources.configuration).apply {
                densityDpi = (simulatedDensity * DisplayMetrics.DENSITY_DEFAULT).roundToInt()
                this.fontScale = fontScale
            }
        }
        val dialogContext = remember(hostView, configuration) {
            ContextThemeWrapper(hostView.context, 0).apply {
                applyOverrideConfiguration(configuration)
            }
        }
        val dialogAnchor = remember(hostView, dialogContext) {
            View(dialogContext).apply {
                setViewTreeLifecycleOwner(hostView.findViewTreeLifecycleOwner())
                setViewTreeViewModelStoreOwner(hostView.findViewTreeViewModelStoreOwner())
                setViewTreeSavedStateRegistryOwner(hostView.findViewTreeSavedStateRegistryOwner())
            }
        }
        CompositionLocalProvider(
            LocalView provides dialogAnchor,
            LocalDensity provides Density(
                density = configuration.densityDpi.toFloat() / DisplayMetrics.DENSITY_DEFAULT,
                fontScale = fontScale,
            ),
            content = content,
        )
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-13T01:00:00Z")
        val BUSINESS_ID: BusinessId = BusinessId.from(uuid(1))
        val PRODUCT_ID: ProductId = ProductId.from(uuid(2))
        val UNIT_ID: UnitId = UnitId.from(uuid(3))
        val LOCATION_ID: LocationId = LocationId.from(uuid(4))

        fun uuid(value: Long): UUID = UUID(0L, value)
    }
}
