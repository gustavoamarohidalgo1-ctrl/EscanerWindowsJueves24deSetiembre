package com.facturastock.app.navigation

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
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
import com.facturastock.app.feature.catalogs.CatalogsContract
import com.facturastock.app.feature.catalogs.CatalogsTestTags
import com.facturastock.app.feature.common.ScannerCodeInputTestTags
import com.facturastock.app.feature.inventory.InventoryTestTags
import com.facturastock.app.feature.sales.SalesTestTags
import com.facturastock.app.resources.Res
import com.facturastock.app.resources.action_cancel
import com.facturastock.app.resources.inventory_product_delete_confirm
import com.facturastock.app.resources.inventory_product_delete_message
import com.facturastock.app.resources.inventory_unified_input_label
import com.facturastock.app.resources.navigation_inventory
import com.facturastock.app.testing.DesktopAppHarness
import com.facturastock.app.testing.TestAppConfigurationState
import com.facturastock.app.testing.performClickOnUiThread
import com.facturastock.app.testing.snapshotTable
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Navegación y persistencia reales sobre el grafo de pruebas y una base SQLite temporal. */
class InventoryProductActionsJourneyTest {
    @get:Rule(order = 0)
    val harness = DesktopAppHarness()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private lateinit var product: Product
    private lateinit var balancesBefore: List<List<String?>>
    private lateinit var movementsBefore: List<List<String?>>
    private val database get() = harness.component.database()
    private val products get() = harness.component.products()
    private val keyboard by lazy { harness.journeyKeyboard(composeRule) }
    private val businessId get() = requireNotNull(TestAppConfigurationState.current.value.businessId)

    @Before
    fun setUp() {
        val component = harness.component
        runBlocking {
            component.businesses().create(Business(businessId, "Negocio prueba", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH))
            val unitId = UnitId.from(UUID.randomUUID())
            val locationId = LocationId.from(UUID.randomUUID())
            component.units().create(UnitOfMeasure(unitId, businessId, "NIU", "Unidad", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH))
            component.locations().create(
                InventoryLocation(locationId, businessId, "Almacén", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH),
            )
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
            component.productInventory().addStock(
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
        harness.setAppContent(composeRule)
        openInventory()
    }

    @Test
    fun manualRegistrationShowsTheStandaloneFormAndSavesPricesAndStockOnceWithoutBarcode() {
        // Kilogramo se ordena antes que Unidad: un producto manual normal debe seguir usando NIU.
        runBlocking {
            harness.component.units().create(
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

        // En escritorio no hay recreación de Activity: se comprueba el formulario ya completado.
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

        composeRule.onNodeWithText(str(Res.string.action_cancel)).performScrollTo().performClickOnUiThread(composeRule)

        waitForTag(InventoryTestTags.LIST_SCREEN)
        composeRule.onNodeWithTag(CatalogsTestTags.FORM).assertDoesNotExist()
        before.forEach { (table, rows) -> assertEquals("Cancelar alteró $table", rows, snapshot(table)) }
        runBlocking { assertEquals(product, products.findById(product.productId)) }
    }

    @Test
    fun nameSearchKeepsKeyboardFocusFiltersProductsAndClearsWithoutChangingStock() {
        composeRule.onNodeWithTag(InventoryTestTags.SEARCH).assertDoesNotExist()
        composeRule.onNodeWithText(str(Res.string.inventory_unified_input_label)).assertIsDisplayed()
        replaceInventoryInput("zz")
        waitForTag(InventoryTestTags.EMPTY)
        assertInventoryInput("zz")

        replaceInventoryInput("AR")
        // Acción «Buscar» del campo (la del teclado): conserva el texto y el foco.
        composeRule.onNodeWithTag(ScannerCodeInputTestTags.FIELD).performImeAction()
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
        // Sin botón «Abrir por código», el Enter final del lector confirma la lectura.
        composeRule.waitForFocusedScannerField()
        keyboard.burst(barcode, listOf(ScanTerminator.ENTER))
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
        composeRule.onNodeWithTag(CatalogsTestTags.SAVE_FORM).assertIsDisplayed().performClickOnUiThread(composeRule)
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
            .performClickOnUiThread(composeRule)
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
        composeRule.onNodeWithText(str(Res.string.action_cancel)).performClickOnUiThread(composeRule)
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
            .performClickOnUiThread(composeRule)
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
        clickDialogText(Res.string.inventory_product_delete_confirm)
        composeRule.waitUntil(15_000L) {
            runBlocking { products.findById(product.productId)?.status == CatalogStatus.ARCHIVED }
        }
        waitForTag(InventoryTestTags.EMPTY)
        composeRule.onNodeWithTag(InventoryTestTags.product(product.productId)).assertDoesNotExist()
        assertEquals(depletedBalances, snapshot("inventory_balances"))
        assertEquals(depletedMovements, snapshot("stock_movements"))
    }

    @Test
    fun productWithHistoryIsRemovedWithOneConfirmationAndStaysHiddenAfterRestart() {
        clickListAction(InventoryTestTags.deleteProduct(product.productId))
        waitForTag(InventoryTestTags.PRODUCT_DELETE_DIALOG)
        clickDialogText(Res.string.action_cancel)
        runBlocking { assertEquals(product, products.findById(product.productId)) }
        assertStockPreserved()

        clickListAction(InventoryTestTags.deleteProduct(product.productId))
        waitForTag(InventoryTestTags.PRODUCT_DELETE_DIALOG)
        clickDialogText(Res.string.inventory_product_delete_confirm)
        composeRule.waitUntil(15_000L) {
            runBlocking { products.findById(product.productId)?.status == CatalogStatus.ARCHIVED }
        }
        waitForTag(InventoryTestTags.EMPTY)
        composeRule.onNodeWithTag(InventoryTestTags.product(product.productId)).assertDoesNotExist()
        assertStockPreserved()
        runBlocking { assertEquals(product.version + 1, products.findById(product.productId)?.version) }

        // Cerrar y reabrir la ventana: lo retirado sigue oculto porque está guardado en SQLite.
        harness.restartApp(composeRule)
        openInventory()
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
        clickDialogText(Res.string.action_cancel)
        runBlocking { assertEquals(unused, products.findById(unused.productId)) }
        assertStockPreserved()

        clickListAction(InventoryTestTags.deleteProduct(unused.productId))
        waitForTag(InventoryTestTags.PRODUCT_DELETE_DIALOG)
        composeRule
            .onNodeWithText(str(Res.string.inventory_product_delete_message, unused.name))
            .performScrollTo()
            .assertIsDisplayed()
        clickDialogText(Res.string.inventory_product_delete_confirm)
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
    fun cancelledEditLeavesTheOriginalProductUntouched() {
        clickListAction(InventoryTestTags.editProduct(product.productId))
        waitForTag(CatalogsTestTags.PRODUCT_NAME)
        composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_NAME).performTextReplacement("Cambio pendiente")
        composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_BARCODE).performScrollTo().performTextReplacement("00998877")
        composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_SKU).performScrollTo().performTextReplacement("ARROZ-01")
        // Sin recreación de Activity en escritorio: los cambios pendientes siguen en el formulario.
        waitForTag(CatalogsTestTags.FORM)
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
        composeRule.onNodeWithText(str(Res.string.action_cancel)).assertIsDisplayed().performClickOnUiThread(composeRule)
        waitForTag(InventoryTestTags.LIST_SCREEN)
        runBlocking { assertEquals(product, products.findById(product.productId)) }
        assertStockPreserved()
    }

    private val registrationTables = listOf("products", "inventory_balances", "stock_movements", "outbox_operations")

    private fun openInventory() {
        waitForTag(SalesTestTags.SCREEN)
        val tab =
            hasText(str(Res.string.navigation_inventory)) and
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        composeRule.onNode(tab).performClickOnUiThread(composeRule)
        waitForTag(InventoryTestTags.LIST_SCREEN)
    }

    /** Seleccionar todo el texto de la entrada unificada y sustituirlo, con el foco en el campo. */
    private fun replaceInventoryInput(value: String) {
        composeRule.waitForFocusedScannerField()
        composeRule.onNodeWithTag(ScannerCodeInputTestTags.FIELD).performTextReplacement(value)
        composeRule.waitForIdle()
    }

    private fun assertInventoryInput(value: String) {
        composeRule.waitForIdle()
        assertEquals(value, composeRule.scannerFieldText())
        composeRule.onNodeWithTag(ScannerCodeInputTestTags.FIELD).assertIsFocused()
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

    private fun snapshot(table: String): List<List<String?>> = database.snapshotTable(table)

    /** Los registros de productos son iconos fijos de la barra superior, fuera de la lista. */
    private fun clickTopBarAction(tag: String) {
        composeRule.waitUntil(15_000L) {
            runCatching { composeRule.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled() }.isSuccess
        }
        composeRule.onNodeWithTag(tag).performClickOnUiThread(composeRule)
    }

    private fun clickListAction(tag: String) {
        composeRule.onNodeWithTag(InventoryTestTags.LIST_SCREEN).performScrollToNode(hasTestTag(tag))
        composeRule.onNodeWithTag(tag).performClickOnUiThread(composeRule)
    }

    private fun clickDialogText(label: StringResource) {
        val button = hasText(str(label)) and hasAnyAncestor(isDialog())
        composeRule.waitUntil(15_000L) {
            runCatching { composeRule.onNode(button).assertIsDisplayed().assertIsEnabled() }.isSuccess
        }
        composeRule.onNode(button).performClickOnUiThread(composeRule)
    }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(15_000L) {
            runCatching { composeRule.onNodeWithTag(tag).assertIsDisplayed() }.isSuccess
        }
    }

    private fun str(res: StringResource, vararg args: Any): String = runBlocking { getString(res, *args) }
}
