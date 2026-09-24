package com.facturastock.app.navigation

import android.os.Build
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.facturastock.app.MainActivity
import com.facturastock.app.R
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.domain.model.Business
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.InventoryLocation
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.UnitOfMeasure
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.repository.BusinessRepository
import com.facturastock.app.domain.repository.InventoryLocationRepository
import com.facturastock.app.domain.repository.ProductInventoryRepository
import com.facturastock.app.domain.repository.ProductRepository
import com.facturastock.app.domain.repository.UnitRepository
import com.facturastock.app.feature.catalogs.CatalogsContract
import com.facturastock.app.feature.catalogs.CatalogsTestTags
import com.facturastock.app.feature.common.ScannerCodeInputTestTags
import com.facturastock.app.feature.inventory.InventoryTestTags
import com.facturastock.app.feature.sales.SalesTestTags
import com.facturastock.app.testing.TestAppConfigurationState
import com.facturastock.app.testing.completedGateConfiguration
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

/** Navegación y persistencia reales; el guard impide borrar datos en dispositivos físicos. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class InventoryProductActionsJourneyTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject lateinit var database: FacturaStockDatabase

    @Inject lateinit var businesses: BusinessRepository

    @Inject lateinit var units: UnitRepository

    @Inject lateinit var locations: InventoryLocationRepository

    @Inject lateinit var products: ProductRepository

    @Inject lateinit var productInventory: ProductInventoryRepository

    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var product: Product
    private lateinit var balancesBefore: List<List<String?>>
    private lateinit var movementsBefore: List<List<String?>>
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val businessId get() = requireNotNull(TestAppConfigurationState.current.value.businessId)

    @Before
    fun setUp() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Sólo ejecutar en emulador dedicado" }
        TestAppConfigurationState.current.value = completedGateConfiguration()
        context.deleteDatabase(FacturaStockDatabase.NAME)
        hiltRule.inject()
        runBlocking {
            businesses.create(Business(businessId, "Negocio prueba", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH))
            val unitId = UnitId.from(UUID.randomUUID())
            val locationId = LocationId.from(UUID.randomUUID())
            units.create(UnitOfMeasure(unitId, businessId, "NIU", "Unidad", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH))
            locations.create(InventoryLocation(locationId, businessId, "Almacén", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH))
            product =
                products.create(
                    Product(
                        productId = ProductId.from(UUID.randomUUID()),
                        businessId = businessId,
                        unitId = unitId,
                        locationId = locationId,
                        name = "Arroz sin código",
                        salePrice = Money.ofMinor(500L, CurrencyCode.of("PEN")),
                        createdAt = Instant.EPOCH,
                        updatedAt = Instant.EPOCH,
                    ),
                )
            productInventory.addStock(
                businessId,
                product.productId,
                locationId,
                "10".toBigDecimal(),
                CurrencyCode.of("PEN"),
                unitCost = "2".toBigDecimal(),
            )
        }
        balancesBefore = snapshot("inventory_balances")
        movementsBefore = snapshot("stock_movements")
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForTag(SalesTestTags.SCREEN)
        val tab =
            hasText(context.getString(R.string.navigation_inventory)) and
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        composeRule.onNode(tab).performClick()
        waitForTag(InventoryTestTags.LIST_SCREEN)
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) scenario.close()
        if (::database.isInitialized) database.close()
    }

    @Test
    fun manualRegistrationRestoresTheStandaloneFormAndSavesPricesAndStockOnceWithoutBarcode() {
        // Kilogramo se ordena antes que Unidad: un producto manual normal debe seguir usando NIU.
        runBlocking {
            units.create(
                UnitOfMeasure(
                    UnitId.from(UUID.randomUUID()),
                    businessId,
                    "KGM",
                    "Kilogramo",
                    symbol = "kg",
                    createdAt = Instant.EPOCH,
                    updatedAt = Instant.EPOCH,
                ),
            )
        }
        val before = registrationTables.associateWith(::snapshot)
        clickTopBarAction(InventoryTestTags.REGISTER_MANUAL)
        waitForManualForm()
        assertStandaloneManualForm()
        fillManualRegistration("Galletas manuales", "4", "3,125", "6,50")

        scenario.recreate()
        waitForManualForm()
        assertStandaloneManualForm()
        composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_NAME).performScrollTo().assertTextContains("Galletas manuales")
        assertDecimalValue(CatalogsTestTags.PRODUCT_QUANTITY, "4")
        assertDecimalValue(CatalogsTestTags.PRODUCT_PURCHASE_PRICE, "3.125")
        assertDecimalValue(CatalogsTestTags.PRODUCT_SALE_PRICE, "6.50")
        before.forEach { (table, rows) -> assertEquals("El formulario alteró $table antes de guardar", rows, snapshot(table)) }

        val save =
            requireNotNull(
                composeRule
                    .onNodeWithTag(CatalogsTestTags.SAVE_FORM)
                    .performScrollTo()
                    .assertIsEnabled()
                    .fetchSemanticsNode()
                    .config[SemanticsActions.OnClick]
                    .action,
            )
        composeRule.runOnIdle {
            assertTrue(save())
            assertTrue(save())
        }
        waitForTag(InventoryTestTags.LIST_SCREEN)
        composeRule.onNodeWithTag(CatalogsTestTags.FORM).assertDoesNotExist()
        runBlocking {
            val registered = database.productDao().listForBusiness(businessId.value)
            assertEquals(2, registered.size)
            val saved = registered.single { it.productId != product.productId.value }
            assertEquals("Galletas manuales", saved.name)
            assertNull(saved.barcode)
            assertNull(saved.sku)
            assertEquals(650L, saved.salePriceMinorUnits)
            assertEquals("PEN", saved.salePriceCurrencyCode)
            assertEquals("NIU", database.unitDao().findById(saved.unitId)?.code)
            assertEquals(product.locationId?.value, saved.locationId)
            val balance = requireNotNull(database.inventoryDao().findBalance(businessId.value, saved.productId, requireNotNull(saved.locationId)))
            assertEquals(0, "4".toBigDecimal().compareTo(balance.quantityOnHand.toBigDecimal()))
            assertEquals(0, "3.125".toBigDecimal().compareTo(balance.averageUnitCost.toBigDecimal()))
            val movement = requireNotNull(database.inventoryDao().findMovementByIdempotencyKey("product-registration:v1:${saved.productId}"))
            assertEquals(0, "4".toBigDecimal().compareTo(movement.quantityDelta.toBigDecimal()))
            assertEquals(0, "3.125".toBigDecimal().compareTo(requireNotNull(movement.unitCost).toBigDecimal()))
            assertEquals(product, products.findById(product.productId))
        }
        assertEquals(before.getValue("stock_movements").size + 1, snapshot("stock_movements").size)
        assertEquals(before.getValue("outbox_operations").size + 1, snapshot("outbox_operations").size)
        assertTrue(snapshot("inventory_balances").containsAll(balancesBefore))
        assertTrue(snapshot("stock_movements").containsAll(movementsBefore))
    }

    @Test
    fun cancellingManualRegistrationReturnsToInventoryWithoutPersistingAnything() {
        val before = registrationTables.associateWith(::snapshot)
        clickTopBarAction(InventoryTestTags.REGISTER_MANUAL)
        waitForManualForm()
        assertStandaloneManualForm()
        fillManualRegistration("Producto cancelado", "7", "2", "4")

        composeRule.onNodeWithText(context.getString(R.string.action_cancel)).performScrollTo().performClick()

        waitForTag(InventoryTestTags.LIST_SCREEN)
        composeRule.onNodeWithTag(CatalogsTestTags.FORM).assertDoesNotExist()
        before.forEach { (table, rows) -> assertEquals("Cancelar alteró $table", rows, snapshot(table)) }
        runBlocking { assertEquals(product, products.findById(product.productId)) }
    }

    @Test
    fun nameSearchKeepsKeyboardFocusFiltersProductsAndClearsWithoutChangingStock() {
        composeRule.onNodeWithTag(InventoryTestTags.SEARCH).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.inventory_unified_input_label)).assertIsDisplayed()
        replaceInventoryInput("zz")
        waitForTag(InventoryTestTags.EMPTY)
        assertInventoryInput("zz")

        replaceInventoryInput("AR")
        scenario.onActivity { activity ->
            val field = requireNotNull(activity.window.decorView.findViewWithTag<EditText>(ScannerCodeInputTestTags.FIELD))
            assertTrue(requireNotNull(field.onCreateInputConnection(EditorInfo())).performEditorAction(EditorInfo.IME_ACTION_SEARCH))
        }
        assertInventoryInput("AR")
        composeRule
            .onNodeWithTag(InventoryTestTags.LIST_SCREEN)
            .performScrollToNode(hasTestTag(InventoryTestTags.product(product.productId)))
        composeRule.onNodeWithTag(InventoryTestTags.product(product.productId)).assertIsDisplayed()
        composeRule.onNodeWithTag(CatalogsTestTags.FORM).assertDoesNotExist()

        replaceInventoryInput("zz")
        waitForTag(InventoryTestTags.EMPTY)
        // Inventario ya no muestra «Reiniciar lector»: borrar el campo restablece la lista.
        replaceInventoryInput("")
        assertInventoryInput("")
        composeRule.onNodeWithTag(ScannerCodeInputTestTags.RESET).assertDoesNotExist()
        composeRule.onNodeWithTag(ScannerCodeInputTestTags.SUBMIT).assertDoesNotExist()
        composeRule
            .onNodeWithTag(InventoryTestTags.LIST_SCREEN)
            .performScrollToNode(hasTestTag(InventoryTestTags.product(product.productId)))
        composeRule.onNodeWithTag(InventoryTestTags.product(product.productId)).assertIsDisplayed()
        assertStockPreserved()

        val barcode = "0001234567895"
        runBlocking { assertTrue(products.update(product.copy(barcode = barcode))) }
        replaceInventoryInput(barcode)
        // Sin botón «Abrir por código», el Enter final del lector confirma la lectura.
        scenario.onActivity { activity ->
            val field = requireNotNull(activity.window.decorView.findViewWithTag<EditText>(ScannerCodeInputTestTags.FIELD))
            field.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            field.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
        waitForTag(CatalogsTestTags.PRODUCT_NAME)
        composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_NAME).performScrollTo().assertTextContains(product.name)
        runBlocking { assertEquals(product.productId, products.findByBarcode(businessId, barcode)?.productId) }
        assertStockPreserved()
    }

    @Test
    fun editingPreservesTheSameProductAndStockHistory() {
        clickListAction(InventoryTestTags.editProduct(product.productId))
        waitForTag(CatalogsTestTags.PRODUCT_NAME)
        assertDecimalValue(CatalogsTestTags.PRODUCT_QUANTITY, "10")
        assertDecimalValue(CatalogsTestTags.PRODUCT_PURCHASE_PRICE, "2")
        composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_NAME).performScrollTo()
        composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_NAME).performTextReplacement("Arroz editado")
        composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_SALE_PRICE).performScrollTo().performTextReplacement("6.25")
        composeRule.onNodeWithTag(CatalogsTestTags.SAVE_FORM).assertIsDisplayed().performClick()
        waitForTag(InventoryTestTags.LIST_SCREEN)
        runBlocking {
            val saved = requireNotNull(products.findById(product.productId))
            assertEquals("Arroz editado", saved.name)
            assertEquals(625L, saved.salePrice?.minorUnits)
            assertNull(saved.barcode)
        }
        assertStockPreserved()

        listOf("Activos", "Archivados", "Restaurar").forEach { label ->
            composeRule.onNodeWithText(label).assertDoesNotExist()
        }
        clickListAction(InventoryTestTags.product(product.productId))
        waitForTag(InventoryTestTags.DETAIL_SCREEN)
        composeRule.onNodeWithTag(InventoryTestTags.editProduct(product.productId)).assertIsDisplayed()
        runBlocking { assertEquals(1, database.productDao().listForBusiness(businessId.value).size) }
        assertStockPreserved()
    }

    @Test
    fun quantityPurchaseCostAndSalePriceSaveTogetherAsAnAdjustmentWithHistoryIntact() {
        clickListAction(InventoryTestTags.editProduct(product.productId))
        waitForTag(CatalogsTestTags.PRODUCT_NAME)
        assertDecimalValue(CatalogsTestTags.PRODUCT_QUANTITY, "10")
        composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_QUANTITY).performTextReplacement("7")
        assertDecimalValue(CatalogsTestTags.PRODUCT_PURCHASE_PRICE, "2")
        composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_PURCHASE_PRICE).performTextReplacement("3,25")
        composeRule
            .onNodeWithTag(CatalogsTestTags.PRODUCT_SALE_PRICE)
            .performScrollTo()
            .performTextReplacement("6,50")
        composeRule
            .onNodeWithTag(CatalogsTestTags.SAVE_FORM)
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        waitForTag(InventoryTestTags.LIST_SCREEN)

        runBlocking {
            val saved = requireNotNull(products.findById(product.productId))
            assertEquals(product.productId, saved.productId)
            assertEquals(650L, saved.salePrice?.minorUnits)
            val balance =
                requireNotNull(
                    database.inventoryDao().findBalance(businessId.value, product.productId.value, requireNotNull(product.locationId).value),
                )
            assertEquals(0, "7".toBigDecimal().compareTo(balance.quantityOnHand.toBigDecimal()))
            assertEquals(0, "3.25".toBigDecimal().compareTo(balance.averageUnitCost.toBigDecimal()))
            assertEquals("PEN", balance.currencyCode)
            assertEquals(1, database.productDao().listForBusiness(businessId.value).size)
        }
        val after = snapshot("stock_movements")
        assertTrue(after.containsAll(movementsBefore))
        assertTrue(after.size > movementsBefore.size)

        clickListAction(InventoryTestTags.editProduct(product.productId))
        waitForTag(CatalogsTestTags.PRODUCT_NAME)
        assertDecimalValue(CatalogsTestTags.PRODUCT_QUANTITY, "7")
        assertDecimalValue(CatalogsTestTags.PRODUCT_PURCHASE_PRICE, "3.25")
        assertDecimalValue(CatalogsTestTags.PRODUCT_SALE_PRICE, "6.50")
        composeRule.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
    }

    @Test
    fun exhaustedProductRemainsInInventoryWithZeroAndCanBeEditedWithoutDeletingHistory() {
        clickListAction(InventoryTestTags.editProduct(product.productId))
        waitForTag(CatalogsTestTags.FORM)
        composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_QUANTITY).performScrollTo().performTextReplacement("0")
        composeRule
            .onNodeWithTag(CatalogsTestTags.SAVE_FORM)
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        waitForTag(InventoryTestTags.LIST_SCREEN)
        composeRule.onNodeWithTag(InventoryTestTags.LIST_SCREEN).performScrollToNode(hasText("1 producto"))
        composeRule.onNodeWithText("1 producto").assertIsDisplayed()
        composeRule
            .onNodeWithTag(InventoryTestTags.LIST_SCREEN)
            .performScrollToNode(hasText("Existencia total: 0 NIU"))
        composeRule.onNodeWithText("Existencia total: 0 NIU").assertIsDisplayed()
        composeRule
            .onNodeWithTag(InventoryTestTags.LIST_SCREEN)
            .performScrollToNode(hasTestTag(InventoryTestTags.editProduct(product.productId)))
        composeRule.onNodeWithTag(InventoryTestTags.product(product.productId)).assertIsDisplayed()
        composeRule.onNodeWithTag(InventoryTestTags.editProduct(product.productId)).assertIsEnabled()
        listOf("Activos", "Archivados", "Restaurar").forEach { label ->
            composeRule.onNodeWithText(label).assertDoesNotExist()
        }
        runBlocking {
            val saved = requireNotNull(products.findById(product.productId))
            assertEquals(product.productId, saved.productId)
            assertEquals(product.name, saved.name)
            assertEquals(CatalogStatus.ACTIVE, saved.status)
            assertEquals(1, database.productDao().listForBusiness(businessId.value).size)
            val balance =
                requireNotNull(
                    database.inventoryDao().findBalance(businessId.value, product.productId.value, requireNotNull(product.locationId).value),
                )
            assertEquals(0, balance.quantityOnHand.toBigDecimal().signum())
        }
        val depletedBalances = snapshot("inventory_balances")
        val depletedMovements = snapshot("stock_movements")
        assertTrue(depletedMovements.containsAll(movementsBefore))
        assertTrue(depletedMovements.size > movementsBefore.size)
        clickListAction(InventoryTestTags.deleteProduct(product.productId))
        waitForTag(InventoryTestTags.PRODUCT_DELETE_DIALOG)
        clickDialogText(R.string.inventory_product_delete_confirm)
        composeRule.waitUntil(15_000L) {
            runBlocking { products.findById(product.productId)?.status == CatalogStatus.ARCHIVED }
        }
        waitForTag(InventoryTestTags.EMPTY)
        composeRule.onNodeWithTag(InventoryTestTags.product(product.productId)).assertDoesNotExist()
        assertEquals(depletedBalances, snapshot("inventory_balances"))
        assertEquals(depletedMovements, snapshot("stock_movements"))
    }

    @Test
    fun productWithHistoryIsRemovedWithOneConfirmationAndStaysHiddenAfterRecreation() {
        clickListAction(InventoryTestTags.deleteProduct(product.productId))
        waitForTag(InventoryTestTags.PRODUCT_DELETE_DIALOG)
        clickDialogText(R.string.action_cancel)
        runBlocking { assertEquals(product, products.findById(product.productId)) }
        assertStockPreserved()

        clickListAction(InventoryTestTags.deleteProduct(product.productId))
        waitForTag(InventoryTestTags.PRODUCT_DELETE_DIALOG)
        clickDialogText(R.string.inventory_product_delete_confirm)
        composeRule.waitUntil(15_000L) {
            runBlocking { products.findById(product.productId)?.status == CatalogStatus.ARCHIVED }
        }
        waitForTag(InventoryTestTags.EMPTY)
        composeRule.onNodeWithTag(InventoryTestTags.product(product.productId)).assertDoesNotExist()
        assertStockPreserved()
        runBlocking { assertEquals(product.version + 1, products.findById(product.productId)?.version) }

        scenario.recreate()
        waitForTag(InventoryTestTags.LIST_SCREEN)
        waitForTag(InventoryTestTags.EMPTY)
        composeRule.onNodeWithTag(InventoryTestTags.product(product.productId)).assertDoesNotExist()
        listOf("Restaurar producto", "Ver retirados", "Quitar del catálogo").forEach { label ->
            composeRule.onNodeWithText(label).assertDoesNotExist()
        }
        replaceInventoryInput("Arroz")
        waitForTag(InventoryTestTags.EMPTY)
        composeRule.onNodeWithTag(InventoryTestTags.product(product.productId)).assertDoesNotExist()
        runBlocking {
            assertEquals(CatalogStatus.ARCHIVED, products.findById(product.productId)?.status)
            assertEquals(1, database.productDao().listForBusiness(businessId.value).size)
        }
        assertStockPreserved()
    }

    @Test
    fun permanentDeletionOfUnusedProductRequiresConfirmationAndLeavesExistingHistoryUntouched() {
        val unused =
            runBlocking {
                products.create(
                    product.copy(
                        productId = ProductId.from(UUID.randomUUID()),
                        name = "Producto nuevo sin movimientos",
                        barcode = null,
                        sku = null,
                    ),
                )
            }
        composeRule.waitUntil(15_000L) {
            runCatching {
                composeRule
                    .onNodeWithTag(InventoryTestTags.LIST_SCREEN)
                    .performScrollToNode(hasTestTag(InventoryTestTags.deleteProduct(unused.productId)))
                composeRule.onNodeWithTag(InventoryTestTags.deleteProduct(unused.productId)).assertIsDisplayed()
            }.isSuccess
        }
        clickListAction(InventoryTestTags.deleteProduct(unused.productId))
        waitForTag(InventoryTestTags.PRODUCT_DELETE_DIALOG)
        clickDialogText(R.string.action_cancel)
        runBlocking { assertEquals(unused, products.findById(unused.productId)) }
        assertStockPreserved()

        clickListAction(InventoryTestTags.deleteProduct(unused.productId))
        waitForTag(InventoryTestTags.PRODUCT_DELETE_DIALOG)
        composeRule
            .onNodeWithText(context.getString(R.string.inventory_product_delete_message, unused.name))
            .performScrollTo()
            .assertIsDisplayed()
        clickDialogText(R.string.inventory_product_delete_confirm)
        composeRule.waitUntil(15_000L) { runBlocking { products.findById(unused.productId) == null } }
        composeRule.waitUntil(15_000L) {
            runCatching { composeRule.onNodeWithTag(InventoryTestTags.PRODUCT_DELETE_DIALOG).assertDoesNotExist() }.isSuccess
        }
        composeRule.onNodeWithTag(InventoryTestTags.product(unused.productId)).assertDoesNotExist()
        runBlocking {
            assertEquals(product, products.findById(product.productId))
            assertEquals(1, database.productDao().listForBusiness(businessId.value).size)
        }
        assertStockPreserved()
    }

    @Test
    fun cancelledEditAfterRecreationLeavesTheOriginalProductUntouched() {
        clickListAction(InventoryTestTags.editProduct(product.productId))
        waitForTag(CatalogsTestTags.PRODUCT_NAME)
        composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_NAME).performTextReplacement("Cambio pendiente")
        composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_BARCODE).performScrollTo().performTextReplacement("00998877")
        composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_SKU).performScrollTo().performTextReplacement("ARROZ-01")
        scenario.recreate()
        waitForTag(CatalogsTestTags.FORM)
        // El desplazamiento hasta SKU también se restaura; un campo fuera de vista no
        // significa que se haya perdido el formulario ni su contenido.
        android.util.Log.i(
            "InventoryEditorTest",
            "Nombre visible antes de desplazar: " +
                runCatching { composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_NAME).assertIsDisplayed() }.isSuccess,
        )
        composeRule
            .onNodeWithTag(CatalogsTestTags.PRODUCT_NAME)
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextContains("Cambio pendiente")
        composeRule
            .onNodeWithTag(CatalogsTestTags.PRODUCT_BARCODE)
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextContains("00998877")
        composeRule
            .onNodeWithTag(CatalogsTestTags.PRODUCT_SKU)
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextContains("ARROZ-01")
        composeRule.onNodeWithText(context.getString(R.string.action_cancel)).assertIsDisplayed().performClick()
        waitForTag(InventoryTestTags.LIST_SCREEN)
        runBlocking { assertEquals(product, products.findById(product.productId)) }
        assertStockPreserved()
    }

    private val registrationTables = listOf("products", "inventory_balances", "stock_movements", "outbox_operations")

    private fun replaceInventoryInput(value: String) {
        composeRule.waitUntil(15_000L) {
            var ready = false
            scenario.onActivity { activity ->
                val field = activity.window.decorView.findViewWithTag<EditText>(ScannerCodeInputTestTags.FIELD)
                ready = field?.isEnabled == true && field.hasFocus()
            }
            ready
        }
        scenario.onActivity { activity ->
            val field = requireNotNull(activity.window.decorView.findViewWithTag<EditText>(ScannerCodeInputTestTags.FIELD))
            field.selectAll()
            assertTrue(requireNotNull(field.onCreateInputConnection(EditorInfo())).commitText(value, 1))
        }
        composeRule.waitForIdle()
    }

    private fun assertInventoryInput(value: String) {
        composeRule.runOnIdle {
            scenario.onActivity { activity ->
                val field = requireNotNull(activity.window.decorView.findViewWithTag<EditText>(ScannerCodeInputTestTags.FIELD))
                assertEquals(value, field.text.toString())
                assertTrue(field.hasFocus())
            }
        }
    }

    private fun waitForManualForm() {
        waitForTag(CatalogsTestTags.FORM)
        composeRule.waitUntil(15_000L) {
            runCatching {
                composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_NAME).performScrollTo().assertIsDisplayed()
            }.isSuccess
        }
    }

    private fun assertStandaloneManualForm() {
        composeRule.onNode(isDialog()).assertDoesNotExist()
        listOf(
            CatalogsTestTags.LIST,
            CatalogsTestTags.SEARCH,
            CatalogsTestTags.ADD,
            CatalogsTestTags.PRODUCT_BARCODE,
            CatalogsTestTags.PRODUCT_SKU,
        ).forEach { tag -> composeRule.onNodeWithTag(tag).assertDoesNotExist() }
        CatalogsContract.Section.entries.forEach { section ->
            composeRule.onNodeWithTag(CatalogsTestTags.tab(section)).assertDoesNotExist()
        }
    }

    private fun fillManualRegistration(
        name: String,
        quantity: String,
        purchasePrice: String,
        salePrice: String,
    ) {
        listOf(
            CatalogsTestTags.PRODUCT_NAME to name,
            CatalogsTestTags.PRODUCT_QUANTITY to quantity,
            CatalogsTestTags.PRODUCT_PURCHASE_PRICE to purchasePrice,
            CatalogsTestTags.PRODUCT_SALE_PRICE to salePrice,
        ).forEach { (tag, value) ->
            composeRule.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)
        }
    }

    private fun assertDecimalValue(
        tag: String,
        expected: String,
    ) {
        val node =
            composeRule
                .onNodeWithTag(tag)
                .performScrollTo()
                .assertIsDisplayed()
                .fetchSemanticsNode()
        val actual =
            node.config[SemanticsProperties.EditableText]
                .text
                .replace(',', '.')
                .toBigDecimal()
        assertEquals(0, expected.toBigDecimal().compareTo(actual))
    }

    private fun assertStockPreserved() {
        assertEquals(balancesBefore, snapshot("inventory_balances"))
        assertEquals(movementsBefore, snapshot("stock_movements"))
    }

    private fun snapshot(table: String): List<List<String?>> =
        database.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY 1").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add((0 until cursor.columnCount).map { index -> cursor.getString(index) })
            }
        }

    /** Los registros de productos son iconos fijos de la barra superior, fuera de la lista. */
    private fun clickTopBarAction(tag: String) {
        composeRule.waitUntil(15_000L) {
            runCatching { composeRule.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled() }.isSuccess
        }
        composeRule.onNodeWithTag(tag).performClick()
    }

    private fun clickListAction(tag: String) {
        composeRule.onNodeWithTag(InventoryTestTags.LIST_SCREEN).performScrollToNode(hasTestTag(tag))
        composeRule.onNodeWithTag(tag).performClick()
    }

    private fun clickDialogText(label: Int) {
        val button = hasText(context.getString(label)) and hasAnyAncestor(isDialog())
        composeRule.waitUntil(15_000L) {
            runCatching { composeRule.onNode(button).assertIsDisplayed().assertIsEnabled() }.isSuccess
        }
        composeRule.onNode(button).performClick()
    }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(15_000L) {
            runCatching { composeRule.onNodeWithTag(tag).assertIsDisplayed() }.isSuccess
        }
    }
}
