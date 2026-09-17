package com.facturastock.app.feature.catalogs

import android.content.res.Configuration
import android.util.DisplayMetrics
import android.view.ContextThemeWrapper
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
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
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.InventoryCostAmount
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.ProductCatalogDetail
import com.facturastock.app.domain.model.ProductInventoryPosition
import com.facturastock.app.domain.model.ProductInventorySummary
import com.facturastock.app.domain.model.SupplierProductAlias
import com.facturastock.app.domain.model.UnitOfMeasure
import com.facturastock.app.domain.model.id.AliasId
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.SupplierId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.repository.ProductEditingPosition
import com.facturastock.app.domain.repository.ProductEditingSnapshot
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
    fun specialProductFormHasKilosCostPriceAndWarehouseWithoutBarcodeInput() {
        val actions = mutableListOf<Action>()
        val warehouseId = LocationId.from(UUID.fromString("10000000-0000-4000-8000-000000000080"))
        composeRule.setContent {
            FacturaStockTheme {
                CatalogsScreen(
                    state = State(form = Form.ProductForm(isSpecialRegistration = true,
                        title = "Comida", quantity = "0,5", purchasePrice = "0", salePrice = "10", locationId = warehouseId),
                        locationOptions = listOf(CatalogsContract.LocationOption(warehouseId, "Mostrador", CatalogStatus.ACTIVE))),
                    onAction = actions::add,
                )
            }
        }
        composeRule.onNodeWithText(context.getString(R.string.catalog_special_product_title)).assertIsDisplayed()
        composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_BARCODE).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.catalog_special_product_quantity)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_PURCHASE_PRICE).performScrollTo().assertTextContains("0")
        composeRule.onNodeWithText("Mostrador").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(CatalogsTestTags.SAVE_FORM).performScrollTo().assertIsEnabled().performClick()
        assertTrue(actions.contains(Action.SaveForm))
    }

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

        CatalogsContract.visibleSections.forEach { section ->
            composeRule.onNodeWithTag(CatalogsTestTags.tab(section)).assertIsDisplayed()
        }
        composeRule.onNodeWithTag(CatalogsTestTags.tab(Section.LOCATIONS)).assertDoesNotExist()
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
    fun productsHideStatusFiltersBadgesAndEvenLegacyPendingStatusDialogs() {
        val original = product().copy(status = CatalogStatus.ARCHIVED)
        var state by mutableStateOf(State(
            rows = listOf(Row.ProductRow(original)),
            statusFilter = CatalogsContract.StatusFilter.ARCHIVED,
        ))
        composeRule.setContent {
            FacturaStockTheme { CatalogsScreen(state = state, onAction = {}) }
        }
        composeRule.onNodeWithText(context.getString(R.string.catalog_filter_active)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.catalog_filter_archived)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.catalog_filter_all)).assertDoesNotExist()
        composeRule.runOnIdle {
            state = state.copy(
                detail = Detail.ProductDetail(Row.ProductRow(original), isLoading = false),
                pendingStatusChange = CatalogsContract.PendingStatusChange(Row.ProductRow(original), CatalogStatus.ACTIVE),
            )
        }
        composeRule.onNodeWithTag(CatalogsTestTags.DETAIL).assertIsDisplayed()
        composeRule.onNodeWithTag(CatalogsTestTags.STATUS).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.catalog_action_archive)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.catalog_action_restore)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.catalog_action_edit)).assertIsDisplayed()
    }

    @Test
    fun unitCatalogRetainsStatusFiltersAndArchiveAction() {
        var state by mutableStateOf(State(section = Section.UNITS))
        composeRule.setContent {
            FacturaStockTheme { CatalogsScreen(state = state, onAction = {}) }
        }
        composeRule.onNodeWithText(context.getString(R.string.catalog_filter_active)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.catalog_filter_archived)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.catalog_filter_all)).assertIsDisplayed()
        composeRule.runOnIdle { state = state.copy(detail = Detail.UnitDetail(Row.UnitRow(unit()))) }
        composeRule.onNodeWithTag(CatalogsTestTags.STATUS).assertIsDisplayed().assertHasClickAction()
        composeRule.onNodeWithText(context.getString(R.string.catalog_action_archive)).assertIsDisplayed()
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
    fun supplierUnitAndLocationFieldsStayDisabledUntilSavingFinishes() {
        var state by mutableStateOf(State(isSaving = true))
        val actions = mutableListOf<Action>()
        composeRule.setContent {
            FacturaStockTheme {
                CatalogsScreen(state = state, onAction = actions::add)
            }
        }
        val forms = listOf(
            Form.SupplierForm(title = "Proveedor local", tradeName = "Mi proveedor", ruc = "20131312955") to listOf(
                R.string.catalog_legal_name_label,
                R.string.catalog_trade_name_label,
                R.string.catalog_ruc_label,
            ),
            Form.UnitForm(title = "Unidad", code = "NIU", symbol = "u") to listOf(
                R.string.catalog_name_label,
                R.string.catalog_unit_code_label,
                R.string.catalog_unit_symbol_label,
            ),
            Form.LocationForm(title = "Almacén") to listOf(R.string.catalog_name_label),
        )
        forms.forEach { (form, labels) ->
            composeRule.runOnIdle { state = state.copy(form = form, isSaving = true) }
            labels.forEach { label ->
                composeRule.onNodeWithText(context.getString(label)).performScrollTo().assertIsNotEnabled()
            }
            composeRule.onNodeWithTag(CatalogsTestTags.SAVE_FORM).assertIsNotEnabled()
            composeRule.runOnIdle { state = state.copy(isSaving = false) }
            labels.forEach { label ->
                composeRule.onNodeWithText(context.getString(label)).performScrollTo().assertIsEnabled()
            }
        }
        assertTrue(actions.isEmpty())
        composeRule.onNodeWithText(context.getString(R.string.catalog_name_label)).performTextReplacement("Sucursal")
        assertTrue(actions == listOf(Action.LocationNameChanged("Sucursal")))
    }

    @Test
    fun inventorySalePriceExplainsZeroAndOverflowWithoutChangingEnteredValues() {
        val initialState = inventoryEditorState()
        var state by mutableStateOf(
            initialState.copy(form = (initialState.form as Form.ProductForm).copy(salePrice = "0")),
        )
        composeRule.setContent {
            FacturaStockTheme {
                CatalogsScreen(
                    state = state,
                    onAction = { action ->
                        if (action is Action.ProductPriceChanged) {
                            state = state.copy(form = (state.form as Form.ProductForm).copy(salePrice = action.value))
                        }
                    },
                )
            }
        }
        val price = composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_SALE_PRICE)
        val error = context.getString(R.string.catalog_polish_sale_price_error, "90071992547409.91", "PEN", 2)
        listOf("0", "90071992547410.00").forEach { value ->
            price.performScrollTo().performTextReplacement(value)
            price.assertTextContains(value).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
            composeRule.onNodeWithText(error).performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag(CatalogsTestTags.SAVE_FORM).assertIsNotEnabled()
        }
        price.performScrollTo().performTextReplacement("6,25")
        price.assertTextContains("6,25").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
        composeRule.onNodeWithText(error).assertDoesNotExist()
        composeRule.onNodeWithTag(CatalogsTestTags.SAVE_FORM).assertIsEnabled()
        price.performTextReplacement("")
        price.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
        composeRule.onNodeWithTag(CatalogsTestTags.SAVE_FORM).assertIsEnabled()
    }

    @Test
    fun clearingKnownPurchasePriceExplainsRequiredValueWithoutTreatingUnknownCostAsAnError() {
        val initialState = inventoryEditorState()
        var state by mutableStateOf(initialState)
        composeRule.setContent {
            FacturaStockTheme {
                CatalogsScreen(
                    state = state,
                    onAction = { action ->
                        if (action is Action.ProductPurchasePriceChanged) {
                            val form = state.form as Form.ProductForm
                            state = state.copy(
                                form = form.copy(
                                    purchasePrice = action.value,
                                    inventoryBalanceDrafts = form.inventoryBalanceDrafts.map { draft ->
                                        if (draft.locationId == form.locationId) draft.copy(purchasePrice = action.value) else draft
                                    },
                                ),
                            )
                        }
                    },
                )
            }
        }
        val purchase = composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_PURCHASE_PRICE)
        val requiredError = context.getString(R.string.catalog_polish_purchase_price_required)
        val unknownHelp = context.getString(R.string.inventory_stock_editor_missing_cost_help)
        purchase.performScrollTo().performTextReplacement("")
        purchase.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        composeRule.onNodeWithText(requiredError).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(unknownHelp).assertDoesNotExist()
        composeRule.onNodeWithTag(CatalogsTestTags.SAVE_FORM).assertIsNotEnabled()

        purchase.performScrollTo().performTextReplacement("0")
        purchase.assertTextContains("0").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
        composeRule.onNodeWithText(requiredError).assertDoesNotExist()
        composeRule.onNodeWithTag(CatalogsTestTags.SAVE_FORM).assertIsEnabled()

        composeRule.runOnIdle {
            val form = initialState.form as Form.ProductForm
            val snapshot = requireNotNull(form.inventorySnapshot)
            state = initialState.copy(
                form = form.copy(
                    purchasePrice = "",
                    inventorySnapshot = snapshot.copy(positions = snapshot.positions.map { it.copy(averageUnitCost = null) }),
                    inventoryBalanceDrafts = form.inventoryBalanceDrafts.map { it.copy(purchasePrice = "") },
                ),
                inventoryBalances = initialState.inventoryBalances.map { it.copy(unitCost = "") },
            )
        }
        purchase.performScrollTo().assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
        composeRule.onNodeWithText(requiredError).assertDoesNotExist()
        composeRule.onNodeWithText(unknownHelp).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(CatalogsTestTags.SAVE_FORM).assertIsEnabled()
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
    fun inventoryEditorPrefillsThreeExplicitValuesAndKeepsSaveVisibleWhileScrolling() {
        val actions = mutableListOf<Action>()
        composeRule.setContent {
            SimulatedWindow(width = 840.dp) {
                FacturaStockTheme {
                    CatalogsScreen(state = inventoryEditorState(), onAction = actions::add)
                }
            }
        }
        composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_QUANTITY)
            .performScrollTo().assertIsDisplayed().assertTextContains("10").performTextReplacement("7")
        composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_PURCHASE_PRICE)
            .performScrollTo().assertIsDisplayed().assertTextContains("2").assertTextContains("PEN")
            .performTextReplacement("3,25")
        composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_SALE_PRICE)
            .performScrollTo().assertIsDisplayed().assertTextContains("5.00").assertTextContains("PEN")
            .performTextReplacement("6,50")
        composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_BARCODE).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(CatalogsTestTags.SAVE_FORM).assertIsDisplayed().assertIsEnabled().performClick()
        composeRule.onNodeWithText(context.getString(R.string.action_cancel)).assertIsDisplayed()
        assertTrue(
            actions.containsAll(
                listOf(
                    Action.ProductQuantityChanged("7"),
                    Action.ProductPurchasePriceChanged("3,25"),
                    Action.ProductPriceChanged("6,50"),
                    Action.SaveForm,
                ),
            ),
        )
    }

    @Test
    fun multipleLocationsShowTheTotalAndRequireSelectionBeforeEditingTheirOwnBalance() {
        var state by mutableStateOf(inventoryEditorState(multipleLocations = true))
        val selectedLocations = mutableListOf<LocationId?>()
        composeRule.setContent {
            FacturaStockTheme {
                CatalogsScreen(
                    state = state,
                    onAction = { action ->
                        if (action is Action.ProductLocationSelected) {
                            selectedLocations += action.locationId
                            val form = state.form as Form.ProductForm
                            val balance = state.inventoryBalances.single { it.locationId == action.locationId }
                            state = state.copy(
                                form = form.copy(
                                    locationId = balance.locationId,
                                    quantity = balance.quantity,
                                    purchasePrice = balance.unitCost,
                                    inventoryCurrency = balance.currency,
                                ),
                            )
                        }
                    },
                )
            }
        }
        composeRule.onNodeWithTag(InventoryProductStockEditorTags.TOTAL).assertTextContains("Total en todas las ubicaciones: 15")
        composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_QUANTITY).assertIsNotEnabled()
        composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_PURCHASE_PRICE).assertIsNotEnabled()
        composeRule.onNodeWithTag(InventoryProductStockEditorTags.LOCATION).performClick()
        composeRule.onNodeWithText("Cantidad: 10 · Compra por unidad: PEN 2").assertIsDisplayed()
        composeRule.onNodeWithText("Cantidad: 5 · Compra por unidad: USD 4").assertIsDisplayed()
        composeRule.onNodeWithText("Sucursal").performClick()
        composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_QUANTITY).performScrollTo().assertIsEnabled().assertTextContains("5")
        composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_PURCHASE_PRICE).performScrollTo()
            .assertIsEnabled().assertTextContains("4").assertTextContains("USD")
        assertTrue(selectedLocations == listOf(LocationId.from(uuid(40))))
    }

    private fun inventoryEditorState(multipleLocations: Boolean = false): State {
        val pen = CurrencyCode.of("PEN")
        val original = product().copy(salePrice = Money.ofMinor(500L, pen))
        val positions = buildList {
            add(ProductEditingPosition(LOCATION_ID, "Almacén", CatalogStatus.ACTIVE, BigDecimal.TEN, BigDecimal("2"), pen, 1L))
            if (multipleLocations) {
                add(
                    ProductEditingPosition(
                        LocationId.from(uuid(40)), "Sucursal", CatalogStatus.ACTIVE,
                        BigDecimal("5"), BigDecimal("4"), CurrencyCode.of("USD"), 1L,
                    ),
                )
            }
        }
        return State(
            currency = pen,
            form = Form.ProductForm(
                productId = original.productId,
                title = original.name,
                unitId = UNIT_ID,
                locationId = if (multipleLocations) null else LOCATION_ID,
                quantity = if (multipleLocations) "" else "10",
                purchasePrice = if (multipleLocations) "" else "2",
                salePrice = "5.00",
                saleCurrency = pen,
                inventoryCurrency = if (multipleLocations) null else pen,
                isInventoryOrigin = true,
                original = original,
                inventorySnapshot = ProductEditingSnapshot(original, positions, true, pen),
                inventoryBalanceDrafts = positions.map {
                    CatalogsContract.InventoryBalanceDraft(it.locationId, it.quantityOnHand.toPlainString(), it.averageUnitCost!!.toPlainString())
                },
            ),
            inventoryBalances = positions.map {
                CatalogsContract.InventoryBalanceOption(
                    it.locationId, it.locationName, it.quantityOnHand.toPlainString(), it.averageUnitCost!!.toPlainString(), it.currency,
                )
            },
            unitOptions = listOf(CatalogsContract.UnitOption(UNIT_ID, "NIU", "Unidad", CatalogStatus.ACTIVE)),
        )
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
                        averageUnitCost = InventoryCostAmount(BigDecimal("4.50"), CurrencyCode.of("PEN")),
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

    @Test
    fun negativeStockKeepsPositionVisibleAndExplainsUnavailableAverageCost() {
        val product = product()
        composeRule.setContent {
            FacturaStockTheme {
                CatalogsScreen(
                    state = State(
                        detail = Detail.ProductDetail(
                            row = Row.ProductRow(product),
                            catalogDetail = ProductCatalogDetail(
                                product = product,
                                unit = unit(),
                                aliases = emptyList(),
                                inventory = ProductInventorySummary(
                                    listOf(
                                        ProductInventoryPosition(
                                            LOCATION_ID,
                                            BigDecimal("-2"),
                                            InventoryCostAmount(BigDecimal("4.50"), CurrencyCode.of("PEN")),
                                        ),
                                    ),
                                ),
                            ),
                            positions = listOf(CatalogsContract.ProductPosition("Principal", "-2", "4.50 PEN")),
                            isLoading = false,
                        ),
                    ),
                    onAction = {},
                )
            }
        }
        composeRule.onNodeWithText(context.getString(R.string.catalog_product_total_stock, "-2", "NIU"))
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.catalog_polish_average_cost_unavailable, "PEN"))
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.catalog_product_position_single, "-2", "4.50 PEN"))
            .performScrollTo().assertIsDisplayed()
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
