package com.facturastock.app.navigation

import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.facturastock.app.MainActivity
import com.facturastock.app.R
import com.facturastock.app.core.input.KeyboardWedgeRouter
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.domain.model.Business
import com.facturastock.app.domain.model.CatalogSearch
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
import com.facturastock.app.feature.catalogs.CatalogsTestTags
import com.facturastock.app.feature.common.ScannerCodeInputTestTags
import com.facturastock.app.feature.debtors.DebtorsTestTags
import com.facturastock.app.feature.inventory.INVENTORY_IDLE_READ_RESET_MILLIS
import com.facturastock.app.feature.inventory.InventoryTestTags
import com.facturastock.app.feature.reports.ReportsTestTags
import com.facturastock.app.feature.sales.SalesTestTags
import com.facturastock.app.testing.TestAppConfigurationState
import com.facturastock.app.testing.completedGateConfiguration
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

/** Activity y SQLite reales: sólo en emulador o paquete QA separado de la aplicación del usuario. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ScannerSaleJourneyTest {
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
    private lateinit var keyboard: InputDevice
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val businessId get() = requireNotNull(TestAppConfigurationState.current.value.businessId)

    @Before
    fun setUp() {
        check(context.packageName == "com.facturastock.scannerqa" || Build.HARDWARE in setOf("ranchu", "goldfish")) {
            "Este recorrido requiere un emulador o el paquete QA aislado; nunca la base real de la tablet."
        }
        val keyboards =
            InputDevice.getDeviceIds().map(InputDevice::getDevice).filterNotNull().filter {
                !it.isVirtual && it.supportsSource(InputDevice.SOURCE_KEYBOARD)
            }
        keyboard =
            requireNotNull(
                keyboards.firstOrNull { it.name.contains("SCANNER", ignoreCase = true) }
                    ?: keyboards.firstOrNull { it.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC }
                    ?: keyboards.firstOrNull(),
            ) { "Se requiere un dispositivo de teclado Android para probar el adaptador HID" }
        KeyboardWedgeRouter.deactivate()
        TestAppConfigurationState.current.value = completedGateConfiguration()
        context.deleteDatabase(FacturaStockDatabase.NAME)
        hiltRule.inject()
        runBlocking {
            businesses.create(Business(businessId, "Negocio lector", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH))
            units.create(
                UnitOfMeasure(
                    unitId = UnitId.from(UUID.randomUUID()),
                    businessId = businessId,
                    code = "NIU",
                    name = "Unidad",
                    createdAt = Instant.EPOCH,
                    updatedAt = Instant.EPOCH,
                ),
            )
            locations.create(
                InventoryLocation(
                    locationId = LocationId.from(UUID.randomUUID()),
                    businessId = businessId,
                    name = "Almacén Principal",
                    createdAt = Instant.EPOCH,
                    updatedAt = Instant.EPOCH,
                ),
            )
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) scenario.close()
        KeyboardWedgeRouter.deactivate()
        if (::database.isInitialized) database.close()
    }

    @Test
    fun debtorsFromReportsAndReturnPreserveTheExistingCartAndInventory() {
        val code = "0099512300775"
        createStockedProduct("Producto acceso deudores", barcode = code, unitCost = "2")
        expectDirectCashSale()
        waitForSalesReader()
        scanFromInputConnection(code, KeyEvent.KEYCODE_ENTER)
        waitForCartQuantity(code, "1")
        val tables = listOf("sales", "sale_lines", "stock_movements", "inventory_balances", "debts", "audit_events")
        val before = tables.associateWith(::snapshot)

        // Vender no tiene selector previo: se sale directamente desde la venta abierta.
        navigate(R.string.navigation_reports)
        waitForTag(ReportsTestTags.SCREEN)
        composeRule.onNodeWithTag(ReportsTestTags.SCREEN).performScrollToNode(hasTestTag(ReportsTestTags.OPEN_DEBTORS))
        clickTag(ReportsTestTags.OPEN_DEBTORS)
        waitForTag(ReportsTestTags.DEBTORS_CONTENT)
        waitForTag(DebtorsTestTags.LIST_SCREEN)
        tables.forEach { table -> assertEquals("Abrir deudores alteró $table", before.getValue(table), snapshot(table)) }

        // Deudores es una pestaña de Reportes: no hay flecha de regreso, se vuelve con el periodo.
        composeRule.onNodeWithContentDescription(context.getString(R.string.action_back)).assertDoesNotExist()
        clickTag(ReportsTestTags.PERIOD_DAY)
        waitForTag(ReportsTestTags.SCREEN)
        navigate(R.string.navigation_sales)
        expectDirectCashSale()
        waitForSalesReader()
        waitForCartQuantity(code, "1")
        tables.forEach { table -> assertEquals("Volver de deudores alteró $table", before.getValue(table), snapshot(table)) }
    }

    @Test
    fun leavingSalesForReportsRequiresConfirmationForInvalidEditsAndCancelPreservesInput() {
        val code = "0099512300881"
        createStockedProduct("Producto resguardo deudores", barcode = code, unitCost = "2")
        expectDirectCashSale()
        waitForSalesReader()
        scanFromInputConnection(code, KeyEvent.KEYCODE_ENTER)
        waitForCartQuantity(code, "1")
        val lineId =
            runBlocking {
                requireNotNull(database.saleDao().findActiveDraft(businessId.value, "PEN")).lines.single().saleLineId
            }
        val tables = listOf("sales", "sale_lines", "stock_movements", "inventory_balances", "debts", "audit_events")
        val before = tables.associateWith(::snapshot)
        val quantityTag = SalesTestTags.quantity(lineId)
        scrollToSalesControl(quantityTag)
        composeRule.onNodeWithTag(quantityTag).performClick().performTextReplacement("")

        // Sin selector ni "Volver" en Vender, la salida se pide directamente desde la venta.
        navigate(R.string.navigation_reports)
        waitForTag(SalesTestTags.DISCARD_EDITS_DIALOG)
        composeRule.onNodeWithTag(ReportsTestTags.SCREEN).assertDoesNotExist()
        composeRule
            .onNode(
                hasText(context.getString(R.string.action_cancel)) and hasAnyAncestor(hasTestTag(SalesTestTags.DISCARD_EDITS_DIALOG)),
            ).performClick()
        waitForTagGone(SalesTestTags.DISCARD_EDITS_DIALOG)
        composeRule.onNodeWithTag(ReportsTestTags.SCREEN).assertDoesNotExist()
        expectDirectCashSale()
        scrollToSalesControl(quantityTag)
        composeRule.onNodeWithTag(quantityTag).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
        )
        tables.forEach { table -> assertEquals("Cancelar salida alteró $table", before.getValue(table), snapshot(table)) }
    }

    @Test
    fun directCheckoutIgnoresRapidRepeatedTapsAndDeductsStockOnlyOnce() {
        val code = "0099512300447"
        val product = createStockedProduct("Producto cierre directo", barcode = code, priceMinorUnits = 850L, unitCost = "2")
        expectDirectCashSale()
        waitForSalesReader()
        scan(code, KeyEvent.KEYCODE_ENTER)
        waitForCartQuantity(code, "1")
        scrollToSalesControl(SalesTestTags.CHECKOUT)
        val checkout =
            requireNotNull(
                composeRule
                    .onNodeWithTag(SalesTestTags.CHECKOUT)
                    .assertIsEnabled()
                    .fetchSemanticsNode()
                    .config[SemanticsActions.OnClick]
                    .action,
            )
        // Entregar ambos toques en la misma vuelta de UI evita que un waitForIdle esconda la carrera.
        composeRule.runOnIdle {
            assertTrue(checkout())
            assertTrue(checkout())
        }
        composeRule.onNodeWithTag(SalesTestTags.CHECKOUT_DIALOG).assertDoesNotExist()
        composeRule.waitUntil(15_000L) {
            runBlocking {
                val posted = database.saleDao().observeRecentPosted(businessId.value, 10).first()
                val next = database.saleDao().findActiveDraft(businessId.value, "PEN")
                posted.size == 1 && next != null && next.sale.saleId != posted.single().saleId && next.lines.isEmpty()
            }
        }
        runBlocking {
            val sale =
                database
                    .saleDao()
                    .observeRecentPosted(businessId.value, 10)
                    .first()
                    .single()
            assertEquals(850L, sale.totalMinorUnits)
            assertEquals(1, sale.lineCount)
            val lines = requireNotNull(database.saleDao().findWithLines(sale.saleId)).lines
            assertEquals(product.productId.value, lines.single().productId)
            assertEquals(0, "1".toBigDecimal().compareTo(lines.single().quantity.toBigDecimal()))
            assertEquals(1, database.inventoryDao().listMovementsForSale(businessId.value, sale.saleId).count { it.type == "SALE" })
            val balance = database.inventoryDao().listDiagnosticBalances(businessId.value).single()
            assertEquals(0, "9".toBigDecimal().compareTo(balance.quantityOnHand.toBigDecimal()))
            assertNull(database.debtDao().findDebtForSale(sale.saleId))
        }
        composeRule.onNodeWithTag(SalesTestTags.CHECKOUT_DIALOG).assertDoesNotExist()
    }

    @Test
    fun reportVoidCancelsOnlyAfterConfirmationReturnsStockAndSurvivesRecreation() {
        val code = "0099512300447"
        val product = createStockedProduct("Producto para anular venta", barcode = code, unitCost = "2")
        expectDirectCashSale()
        waitForSalesReader()
        composeRule.onNodeWithTag(SalesTestTags.ENTRY_MODE_SCREEN).assertDoesNotExist()
        waitForSalesReader()
        scan(code, KeyEvent.KEYCODE_ENTER)
        waitForCartQuantity(code, "1")
        setCartQuantityManually(code, "2")
        scrollToSalesControl(SalesTestTags.CHECKOUT)
        clickTag(SalesTestTags.CHECKOUT)
        composeRule.onNodeWithTag(SalesTestTags.CHECKOUT_DIALOG).assertDoesNotExist()
        composeRule.waitUntil(15_000L) {
            runBlocking {
                database
                    .saleDao()
                    .observeRecentPosted(businessId.value, 10)
                    .first()
                    .size == 1
            }
        }
        val sale =
            runBlocking {
                database
                    .saleDao()
                    .observeRecentPosted(businessId.value, 10)
                    .first()
                    .single()
            }
        // Cobrar al contado crea el siguiente carrito en una segunda transacción. El snapshot
        // de historial debe incluir ese borrador estable antes de probar Cancelar/Anular.
        composeRule.waitUntil(15_000L) {
            runBlocking {
                val nextCart = database.saleDao().findActiveDraft(businessId.value, "PEN")
                nextCart != null && nextCart.sale.saleId != sale.saleId && nextCart.lines.isEmpty()
            }
        }
        val historyTables = listOf("sales", "sale_lines", "stock_movements", "inventory_balances", "audit_events", "sale_voids")
        val before = historyTables.associateWith(::snapshot)
        val productBefore = runBlocking { products.findById(product.productId) }
        navigate(R.string.navigation_reports)
        waitForTag(ReportsTestTags.SCREEN)
        val actionTag = ReportsTestTags.saleVoid(sale.saleId)

        fun openVoid() {
            composeRule.waitUntil(15_000L) {
                runCatching {
                    composeRule.onNodeWithTag(ReportsTestTags.SCREEN).performScrollToNode(hasTestTag(actionTag))
                    composeRule.onNodeWithTag(actionTag).assertIsEnabled().performClick()
                }.isSuccess
            }
            waitForTag(ReportsTestTags.VOID_DIALOG)
            composeRule.waitUntil(15_000L) {
                runCatching {
                    composeRule
                        .onNode(
                            hasText(context.getString(R.string.reports_void_action)) and
                                hasAnyAncestor(hasTestTag(ReportsTestTags.VOID_DIALOG)),
                        ).assertIsEnabled()
                }.isSuccess
            }
        }
        openVoid()
        composeRule
            .onNode(
                hasText(context.getString(R.string.reports_void_cancel)) and
                    hasAnyAncestor(hasTestTag(ReportsTestTags.VOID_DIALOG)),
            ).performClick()
        composeRule.waitForIdle()
        historyTables.forEach { table -> assertEquals(before.getValue(table), snapshot(table)) }

        openVoid()
        composeRule
            .onNode(
                hasText(context.getString(R.string.reports_void_action)) and
                    hasAnyAncestor(hasTestTag(ReportsTestTags.VOID_DIALOG)),
            ).performClick()
        composeRule.waitUntil(15_000L) {
            runBlocking { database.saleVoidDao().findBySaleId(businessId.value, sale.saleId) != null }
        }
        waitForTag(ReportsTestTags.VOID_SUCCESS)
        composeRule.onNodeWithTag(ReportsTestTags.sale(sale.saleId)).assertDoesNotExist()
        runBlocking {
            val balance =
                database
                    .inventoryDao()
                    .listDiagnosticBalances(businessId.value)
                    .single { it.productId == product.productId.value }
            assertEquals(0, "10".toBigDecimal().compareTo(balance.quantityOnHand.toBigDecimal()))
            assertEquals(0, "2".toBigDecimal().compareTo(balance.averageUnitCost.toBigDecimal()))
            assertEquals(productBefore, products.findById(product.productId))
            assertTrue(
                database
                    .saleDao()
                    .observeRecentPosted(businessId.value, 10)
                    .first()
                    .isEmpty(),
            )
            val receipt = requireNotNull(database.saleVoidDao().findBySaleId(businessId.value, sale.saleId))
            assertEquals(1000L, receipt.refundedAmountMinorUnits)
            assertEquals(0L, receipt.cancelledDebtBalanceMinorUnits)
            val movements = database.inventoryDao().listMovementsForSale(businessId.value, sale.saleId)
            assertEquals(1, movements.count { it.type == "SALE" })
            assertEquals(1, movements.count { it.type == "SALE_VOID" })
        }
        assertEquals(before.getValue("sales"), snapshot("sales"))
        assertEquals(before.getValue("sale_lines"), snapshot("sale_lines"))
        val finalHistory = historyTables.associateWith(::snapshot)
        scenario.recreate()
        waitForTag(ReportsTestTags.SCREEN)
        waitForTag(ReportsTestTags.EMPTY)
        composeRule.onNodeWithTag(actionTag).assertDoesNotExist()
        historyTables.forEach { table -> assertEquals(finalHistory.getValue(table), snapshot(table)) }
    }

    @Test
    fun twoRegistrationsReturnToReaderAndRepeatedScanKeepsQuantityUntilManuallyChangedAndSold() {
        waitForTag(SalesTestTags.SCREEN)
        navigate(R.string.navigation_inventory)
        waitForTag(InventoryTestTags.LIST_SCREEN)
        clickTag(InventoryTestTags.REGISTER_PRODUCTS)
        waitForReader()

        registerProduct("00112233", "Arroz lector", "8.50", KeyEvent.KEYCODE_ENTER)
        registerProduct("00445566", "Azúcar lector", "5.00", KeyEvent.KEYCODE_TAB)
        // Volver a leer un código guardado también debe abrir el formulario, sin crear otro.
        scan("00112233", KeyEvent.KEYCODE_ENTER)
        waitForTag(CatalogsTestTags.PRODUCT_NAME)
        composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_NAME).assertTextContains("Arroz lector")
        composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_BARCODE).performScrollTo().assertTextContains("00112233")
        assertEditorDecimal(CatalogsTestTags.PRODUCT_QUANTITY, "10")
        assertEditorDecimal(CatalogsTestTags.PRODUCT_PURCHASE_PRICE, "2")
        assertEditorDecimal(CatalogsTestTags.PRODUCT_SALE_PRICE, "8.50")
        composeRule.onNodeWithText(context.getString(R.string.action_cancel)).assertIsDisplayed().performClick()
        waitForReader()
        runBlocking {
            assertEquals(2, database.productDao().listForBusiness(businessId.value).size)
            assertTrue(
                database.inventoryDao().listDiagnosticBalances(businessId.value).all {
                    it.quantityOnHand.toBigDecimal().compareTo("10".toBigDecimal()) == 0
                },
            )
        }

        composeRule.onNodeWithContentDescription(context.getString(R.string.action_back)).performClick()
        waitForTag(InventoryTestTags.LIST_SCREEN)
        navigate(R.string.navigation_sales)
        expectDirectCashSale()
        waitForSalesReader()
        composeRule.onNodeWithTag(SalesTestTags.ENTRY_MODE_SCREEN).assertDoesNotExist()
        waitForTag(SalesTestTags.SCANNER_FEEDBACK)

        scan("00112233", KeyEvent.KEYCODE_ENTER)
        waitForCartQuantity("00112233", "1")
        scan("00112233", KeyEvent.KEYCODE_TAB)
        waitForCartQuantity("00112233", "1")
        waitForScanFeedback("Arroz lector", "1")
        setCartQuantityManually("00112233", "2")
        scan("00445566", KeyEvent.KEYCODE_NUMPAD_ENTER)
        waitForCartQuantity("00445566", "1")
        composeRule.onNodeWithTag(SalesTestTags.ENTRY_MODE_SCREEN).assertDoesNotExist()
        composeRule.onNodeWithTag(InventoryTestTags.LIST_SCREEN).assertDoesNotExist()

        composeRule.onNodeWithTag(SalesTestTags.SCREEN).performScrollToNode(hasTestTag(SalesTestTags.CHECKOUT))
        composeRule
            .onNodeWithTag(SalesTestTags.CHECKOUT)
            .assertIsEnabled()
            .performClick()
        composeRule.onNodeWithTag(SalesTestTags.CHECKOUT_DIALOG).assertDoesNotExist()
        composeRule.waitUntil(15_000L) {
            runBlocking {
                database
                    .saleDao()
                    .observeRecentPosted(businessId.value, 10)
                    .first()
                    .size == 1
            }
        }
        runBlocking {
            val sale =
                database
                    .saleDao()
                    .observeRecentPosted(businessId.value, 10)
                    .first()
                    .single()
            assertEquals(2_200L, sale.totalMinorUnits)
            assertEquals(2, sale.lineCount)
            val products = database.productDao().listForBusiness(businessId.value).associateBy { it.barcode }
            val stock = database.inventoryDao().listDiagnosticBalances(businessId.value).associateBy { it.productId }
            assertEquals(
                0,
                stock
                    .getValue(products.getValue("00112233").productId)
                    .quantityOnHand
                    .toBigDecimal()
                    .compareTo("8".toBigDecimal()),
            )
            assertEquals(
                0,
                stock
                    .getValue(products.getValue("00445566").productId)
                    .quantityOnHand
                    .toBigDecimal()
                    .compareTo("9".toBigDecimal()),
            )
        }
    }

    @Test
    fun unifiedCashEntrySearchesFromTwoLettersThenAcceptsLeadingZeroHidAndPostsOnce() {
        unifiedNameAndScannerJourney(credit = false)
    }

    @Test
    fun unifiedCreditEntryResumesAfterDebtorEditingAndPostsNameAndHidProductsOnce() {
        unifiedNameAndScannerJourney(credit = true)
    }

    @Test
    fun cashSaleAcceptsImeFramesAndUnterminatedUsbThenPersistsTheCorrectStockAndTotal() {
        waitForTag(SalesTestTags.SCREEN)
        navigate(R.string.navigation_inventory)
        waitForTag(InventoryTestTags.LIST_SCREEN)
        clickTag(InventoryTestTags.REGISTER_PRODUCTS)
        waitForReader()
        registerProduct("00112233", "Arroz lector", "8.50", KeyEvent.KEYCODE_ENTER)
        registerProduct("00445566", "Azúcar lector", "5.00", KeyEvent.KEYCODE_TAB)
        composeRule.onNodeWithContentDescription(context.getString(R.string.action_back)).performClick()
        waitForTag(InventoryTestTags.LIST_SCREEN)
        navigate(R.string.navigation_sales)
        expectDirectCashSale()
        waitForSalesReader()
        composeRule.onNodeWithTag(SalesTestTags.ENTRY_MODE_SCREEN).assertDoesNotExist()
        waitForTag(ScannerCodeInputTestTags.FIELD)

        // IME commitText y Enter/Tab virtuales pasan por el campo de la Activity real.
        scanFromInputConnection("00112233", KeyEvent.KEYCODE_ENTER)
        waitForCartQuantity("00112233", "1")
        val lineId =
            runBlocking {
                requireNotNull(database.saleDao().findActiveDraft(businessId.value, "PEN")).lines.single().saleLineId
            }
        lateinit var oldConnection: InputConnection
        scenario.onActivity { activity ->
            val field = requireNotNull(activity.window.decorView.findViewWithTag<EditText>(ScannerCodeInputTestTags.FIELD))
            oldConnection = requireNotNull(field.onCreateInputConnection(EditorInfo()))
        }
        val quantityTag = SalesTestTags.quantity(lineId)
        composeRule.onNodeWithTag(SalesTestTags.SCREEN).performScrollToNode(hasTestTag(quantityTag))
        composeRule.onNodeWithTag(quantityTag).performClick()
        composeRule.waitForIdle()
        scenario.onActivity { activity ->
            val field = requireNotNull(activity.window.decorView.findViewWithTag<EditText>(ScannerCodeInputTestTags.FIELD))
            assertFalse("Editar cantidad debe pausar la captura del lector por foco", field.hasFocus())
            assertTrue(oldConnection.commitText("00112233\r", 1))
        }
        waitForCartQuantity("00112233", "1")

        // Al desplazar la lista, una fila fuera de composición puede liberar el foco por sí sola.
        // Se busca el estado estable del lector, no un botón que puede desaparecer por esa razón.
        composeRule.onNodeWithTag(SalesTestTags.SCANNER_FEEDBACK).assertIsDisplayed()
        composeRule.waitForIdle()
        if (runCatching { composeRule.onNodeWithTag(SalesTestTags.SCANNER_RESUME).assertIsDisplayed() }.isSuccess) {
            composeRule.onNodeWithTag(SalesTestTags.SCANNER_RESUME).performClick()
        }
        composeRule.waitUntil(15_000L) {
            var resumed = false
            scenario.onActivity { activity ->
                val field = activity.window.decorView.findViewWithTag<EditText>(ScannerCodeInputTestTags.FIELD)
                resumed = field?.isEnabled == true && field.hasFocus()
            }
            resumed
        }
        scenario.onActivity { activity ->
            val field = requireNotNull(activity.window.decorView.findViewWithTag<EditText>(ScannerCodeInputTestTags.FIELD))
            assertTrue(oldConnection.commitText("00112233\r", 1))
            assertEquals("", field.text.toString())
        }
        waitForCartQuantity("00112233", "1")
        scanFromInputConnection("00112233\r\n")
        waitForCartQuantity("00112233", "1")
        waitForScanFeedback("Arroz lector", "1")
        scanFromInputConnection("00445566", KeyEvent.KEYCODE_TAB)
        waitForCartQuantity("00445566", "1")
        composeRule.onNodeWithTag(SalesTestTags.ENTRY_MODE_SCREEN).assertDoesNotExist()
        composeRule.onNodeWithTag(InventoryTestTags.LIST_SCREEN).assertDoesNotExist()

        // El lector USB sin sufijo debe mostrar el código, sin añadirlo hasta la confirmación.
        scan("00112233", terminator = null)
        scenario.onActivity { activity ->
            val field = requireNotNull(activity.window.decorView.findViewWithTag<EditText>(ScannerCodeInputTestTags.FIELD))
            assertEquals("00112233", field.text.toString())
        }
        waitForCartQuantity("00112233", "1")
        composeRule.onNodeWithTag(ScannerCodeInputTestTags.SUBMIT).assertIsEnabled().performClick()
        waitForCartQuantity("00112233", "1")
        waitForScanFeedback("Arroz lector", "1")
        scenario.onActivity { activity ->
            val field = requireNotNull(activity.window.decorView.findViewWithTag<EditText>(ScannerCodeInputTestTags.FIELD))
            assertEquals("", field.text.toString())
        }
        setCartQuantityManually("00112233", "3")

        composeRule.onNodeWithTag(SalesTestTags.SCREEN).performScrollToNode(hasTestTag(SalesTestTags.CHECKOUT))
        composeRule.onNodeWithTag(SalesTestTags.CHECKOUT).assertIsEnabled().performClick()
        composeRule.onNodeWithTag(SalesTestTags.CHECKOUT_DIALOG).assertDoesNotExist()
        composeRule.waitUntil(15_000L) {
            runBlocking {
                database
                    .saleDao()
                    .observeRecentPosted(businessId.value, 10)
                    .first()
                    .size == 1
            }
        }
        runBlocking {
            val sale =
                database
                    .saleDao()
                    .observeRecentPosted(businessId.value, 10)
                    .first()
                    .single()
            assertEquals(3_050L, sale.totalMinorUnits)
            assertEquals(2, sale.lineCount)
            val products = database.productDao().listForBusiness(businessId.value).associateBy { it.barcode }
            val stock = database.inventoryDao().listDiagnosticBalances(businessId.value).associateBy { it.productId }
            mapOf("00112233" to "7", "00445566" to "9").forEach { (barcode, expected) ->
                assertEquals(
                    "Existencias de $barcode después de cobrar",
                    0,
                    stock
                        .getValue(products.getValue(barcode).productId)
                        .quantityOnHand
                        .toBigDecimal()
                        .compareTo(expected.toBigDecimal()),
                )
            }
        }
    }

    @Test
    fun suspiciousSaleCodeRegistersInventoryReturnsToTheSameCartAndCancelCreatesNothing() {
        waitForTag(SalesTestTags.SCREEN)
        // Una lectura sin exacto ni recuperación segura se ignora. El registro desde Ventas sigue
        // disponible cuando la lectura exige elegir: coincide con un SKU y truncaría otro GTIN.
        val barcode = "775619830524"
        val cancelledBarcode = "009988776654"
        val unknownBarcode = "5901234123457"
        val fixtures = createAmbiguousReadingFixtures(barcode) + createAmbiguousReadingFixtures(cancelledBarcode)
        val name = "Galleta registrada desde venta"
        expectDirectCashSale()
        waitForSalesReader()
        composeRule.onNodeWithTag(SalesTestTags.ENTRY_MODE_SCREEN).assertDoesNotExist()
        waitForSalesReader()
        val originalSaleId =
            runBlocking {
                requireNotNull(database.saleDao().findActiveDraft(businessId.value, "PEN")).sale.saleId
            }

        scan(barcode, KeyEvent.KEYCODE_ENTER)
        scrollToSalesControl(SalesTestTags.REGISTER_PRODUCT)
        clickTag(SalesTestTags.REGISTER_PRODUCT)
        waitForTag(CatalogsTestTags.PRODUCT_BARCODE)
        composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_BARCODE).assertTextContains(barcode)
        listOf(
            CatalogsTestTags.PRODUCT_NAME to name,
            CatalogsTestTags.PRODUCT_QUANTITY to "12",
            CatalogsTestTags.PRODUCT_PURCHASE_PRICE to "1.80",
            CatalogsTestTags.PRODUCT_SALE_PRICE to "3.60",
        ).forEach { (tag, value) ->
            composeRule.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)
        }
        composeRule
            .onNodeWithTag(CatalogsTestTags.SAVE_FORM)
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        waitForSalesReader()
        waitForCartQuantity(barcode, "1")
        waitForScanFeedback(name, "1")
        composeRule.onNodeWithTag(CatalogsTestTags.FORM).assertDoesNotExist()
        composeRule.onNodeWithTag(SalesTestTags.ENTRY_MODE_SCREEN).assertDoesNotExist()
        val registered = runBlocking { requireNotNull(products.findByBarcode(businessId, barcode)) }
        val firstLine =
            runBlocking {
                val draft = requireNotNull(database.saleDao().findActiveDraft(businessId.value, "PEN"))
                assertEquals(originalSaleId, draft.sale.saleId)
                assertEquals(1, draft.lines.size)
                draft.lines.single()
            }
        assertEquals(registered.productId.value, firstLine.productId)
        assertEquals(360L, firstLine.unitPriceMinorUnits)
        assertEquals(360L, firstLine.lineTotalMinorUnits)
        val stockAfterRegistration = snapshot("stock_movements")

        // El código registrado sigue siendo un truncado sospechoso: vuelve a pedir una elección
        // explícita y nunca suma otra unidad ni reescribe el producto.
        scan(barcode, KeyEvent.KEYCODE_TAB)
        waitForTag(SalesTestTags.BARCODE_SUGGESTIONS)
        waitForCartQuantity(barcode, "1")
        runBlocking {
            val draft = requireNotNull(database.saleDao().findActiveDraft(businessId.value, "PEN"))
            assertEquals(originalSaleId, draft.sale.saleId)
            assertEquals(firstLine, draft.lines.single())
            assertEquals(fixtures.size + 1, database.productDao().listForBusiness(businessId.value).size)
            val stored = requireNotNull(products.findById(registered.productId))
            assertEquals(registered.version, stored.version)
            assertEquals(barcode, stored.barcode)
            assertEquals(360L, stored.salePrice?.minorUnits)
            val balance =
                database.inventoryDao().listDiagnosticBalances(businessId.value).single {
                    it.productId == registered.productId.value
                }
            assertEquals(0, "12".toBigDecimal().compareTo(balance.quantityOnHand.toBigDecimal()))
            assertEquals(0, "1.80".toBigDecimal().compareTo(balance.averageUnitCost.toBigDecimal()))
        }
        assertEquals(stockAfterRegistration, snapshot("stock_movements"))

        scan(cancelledBarcode, KeyEvent.KEYCODE_ENTER)
        scrollToSalesControl(SalesTestTags.REGISTER_PRODUCT)
        clickTag(SalesTestTags.REGISTER_PRODUCT)
        waitForTag(CatalogsTestTags.PRODUCT_BARCODE)
        composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_BARCODE).assertTextContains(cancelledBarcode)
        composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_NAME).performScrollTo().performTextReplacement("No guardar")
        composeRule.onNodeWithText(context.getString(R.string.action_cancel)).performScrollTo().performClick()
        waitForSalesReader()
        composeRule.onNodeWithTag(CatalogsTestTags.FORM).assertDoesNotExist()
        runBlocking {
            assertNull(products.findByBarcode(businessId, cancelledBarcode))
            assertEquals(fixtures.size + 1, database.productDao().listForBusiness(businessId.value).size)
            val draft = requireNotNull(database.saleDao().findActiveDraft(businessId.value, "PEN"))
            assertEquals(originalSaleId, draft.sale.saleId)
            assertEquals(firstLine, draft.lines.single())
        }
        assertEquals(stockAfterRegistration, snapshot("stock_movements"))

        // Un código realmente desconocido descarta la elección pendiente y no ofrece registrarlo.
        scan(unknownBarcode, KeyEvent.KEYCODE_ENTER)
        composeRule.waitUntil(15_000L) {
            runCatching {
                composeRule.onNodeWithTag(SalesTestTags.REGISTER_PRODUCT).assertDoesNotExist()
                composeRule.onNodeWithTag(SalesTestTags.ASSOCIATION_CANCEL).assertDoesNotExist()
                composeRule.onNodeWithTag(SalesTestTags.BARCODE_SUGGESTIONS).assertDoesNotExist()
            }.isSuccess
        }
        waitForSalesReader()
        runBlocking {
            assertNull(products.findByBarcode(businessId, unknownBarcode))
            assertEquals(fixtures.size + 1, database.productDao().listForBusiness(businessId.value).size)
            val draft = requireNotNull(database.saleDao().findActiveDraft(businessId.value, "PEN"))
            assertEquals(originalSaleId, draft.sale.saleId)
            assertEquals(firstLine, draft.lines.single())
        }
        assertEquals(stockAfterRegistration, snapshot("stock_movements"))
    }

    @Test
    fun importedProductSkuScansTwiceWithoutAssociationOrBarcodeMutationAndBarcodeProductStillWorks() {
        waitForTag(SalesTestTags.SCREEN)
        val sku = "00776655"
        val barcode = "00998877"
        val imported = createStockedProduct("Arroz importado por SKU", sku = sku, priceMinorUnits = 850L)
        createStockedProduct("Azúcar con código de barras", barcode = barcode)
        expectDirectCashSale()
        waitForSalesReader()
        composeRule.onNodeWithTag(SalesTestTags.ENTRY_MODE_SCREEN).assertDoesNotExist()
        waitForTag(ScannerCodeInputTestTags.FIELD)

        var firstLineId: String? = null
        repeat(2) { index ->
            if (index == 0) {
                scanFromInputConnection(sku, KeyEvent.KEYCODE_ENTER)
            } else {
                scan(sku, KeyEvent.KEYCODE_TAB)
            }
            composeRule.waitUntil(15_000L) {
                runBlocking {
                    database
                        .saleDao()
                        .findActiveDraft(businessId.value, "PEN")
                        ?.lines
                        ?.singleOrNull { it.productId == imported.productId.value }
                        ?.quantity
                        ?.toBigDecimal()
                        ?.compareTo("1".toBigDecimal()) == 0
                }
            }
            composeRule.waitForIdle()
            waitForScanFeedback(imported.name, "1")
            composeRule.onNodeWithTag(SalesTestTags.ASSOCIATION_CANCEL).assertDoesNotExist()
            composeRule.onNodeWithTag(SalesTestTags.SEARCH).assertDoesNotExist()
            runBlocking {
                val line = requireNotNull(database.saleDao().findActiveDraft(businessId.value, "PEN")).lines.single()
                if (firstLineId == null) firstLineId = line.saleLineId
                assertEquals(firstLineId, line.saleLineId)
                val stored = requireNotNull(products.findById(imported.productId))
                assertEquals(sku, stored.sku)
                assertNull(stored.barcode)
                assertEquals(imported.version, stored.version)
            }
        }

        scan(barcode, KeyEvent.KEYCODE_ENTER)
        waitForCartQuantity(barcode, "1")
        composeRule.onNodeWithTag(SalesTestTags.ASSOCIATION_CANCEL).assertDoesNotExist()
        runBlocking {
            val lines = requireNotNull(database.saleDao().findActiveDraft(businessId.value, "PEN")).lines
            assertEquals(2, lines.size)
            val importedLine = lines.single { it.productId == imported.productId.value }
            assertEquals(firstLineId, importedLine.saleLineId)
            assertEquals(0, importedLine.quantity.toBigDecimal().compareTo("1".toBigDecimal()))
            assertEquals(2, database.productDao().listForBusiness(businessId.value).size)
            assertNull(products.findById(imported.productId)?.barcode)
            assertTrue(
                database.inventoryDao().listDiagnosticBalances(businessId.value).all {
                    it.quantityOnHand.toBigDecimal().compareTo("10".toBigDecimal()) == 0
                },
            )
        }
    }

    @Test
    fun uniqueValidBarcodeRecoversTwoMissingDigitsShowsItsOriginAndChecksOutWithoutDuplicates() {
        waitForTag(SalesTestTags.SCREEN)
        val barcode = "7753176004930"
        val incomplete = barcode.removeRange(4, 6)
        val product = createStockedProduct("Arroz de recuperación automática", barcode = barcode, priceMinorUnits = 850L, unitCost = "2")
        val recoveryNotice = context.getString(R.string.sales_scanner_feedback_recovered_code, incomplete)
        val movementsBefore = snapshot("stock_movements")
        expectDirectCashSale()
        waitForSalesReader()

        // Una única lectura física omite dos dígitos centrales, conservando el orden restante.
        scan(incomplete, KeyEvent.KEYCODE_ENTER)
        waitForCartQuantity(barcode, "1")
        waitForScanFeedback(product.name, "1")
        composeRule
            .onNodeWithTag(SalesTestTags.SCANNER_FEEDBACK)
            .assertTextContains(context.getString(R.string.sales_scanner_feedback_recovered, product.name))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, recoveryNotice))
        composeRule.onNodeWithTag(SalesTestTags.BARCODE_SUGGESTIONS).assertDoesNotExist()
        composeRule.onNodeWithTag(SalesTestTags.REPLACEMENT_DIALOG).assertDoesNotExist()
        val recoveredCartVersion =
            runBlocking {
                val draft = requireNotNull(database.saleDao().findActiveDraft(businessId.value, "PEN"))
                assertEquals(1, draft.lines.size)
                assertEquals(product.productId.value, draft.lines.single().productId)
                assertEquals(850L, draft.lines.single().lineTotalMinorUnits)
                assertEquals(product, products.findById(product.productId))
                assertNull(products.findByBarcode(businessId, incomplete))
                draft.sale.version
            }

        // El código exacto vuelve a identificar la misma línea sin sumar ni reescribir el producto.
        scanFromInputConnection(barcode, KeyEvent.KEYCODE_TAB)
        composeRule.waitUntil(15_000L) {
            runCatching {
                composeRule.onNodeWithTag(SalesTestTags.SCANNER_FEEDBACK).assertTextContains(
                    context.getString(R.string.sales_scanner_feedback_already_present, product.name),
                )
            }.isSuccess
        }
        composeRule
            .onNodeWithTag(SalesTestTags.SCANNER_FEEDBACK)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        runBlocking {
            val draft = requireNotNull(database.saleDao().findActiveDraft(businessId.value, "PEN"))
            assertEquals(recoveredCartVersion, draft.sale.version)
            assertEquals(1, draft.lines.size)
            assertEquals(
                0,
                "1".toBigDecimal().compareTo(
                    draft.lines
                        .single()
                        .quantity
                        .toBigDecimal(),
                ),
            )
            assertEquals(product, products.findById(product.productId))
        }
        assertEquals(movementsBefore, snapshot("stock_movements"))

        scrollToSalesControl(SalesTestTags.CHECKOUT)
        clickTag(SalesTestTags.CHECKOUT)
        composeRule.onNodeWithTag(SalesTestTags.CHECKOUT_DIALOG).assertDoesNotExist()
        composeRule.waitUntil(15_000L) {
            runBlocking {
                database
                    .saleDao()
                    .observeRecentPosted(businessId.value, 10)
                    .first()
                    .size == 1
            }
        }
        runBlocking {
            val sale =
                database
                    .saleDao()
                    .observeRecentPosted(businessId.value, 10)
                    .first()
                    .single()
            assertEquals(850L, sale.totalMinorUnits)
            assertEquals(1, sale.lineCount)
            val balance = database.inventoryDao().listDiagnosticBalances(businessId.value).single()
            assertEquals(0, "9".toBigDecimal().compareTo(balance.quantityOnHand.toBigDecimal()))
            assertEquals(0, "2".toBigDecimal().compareTo(balance.averageUnitCost.toBigDecimal()))
            assertEquals(1, database.inventoryDao().listMovementsForSale(businessId.value, sale.saleId).count { it.type == "SALE" })
            assertEquals(product, products.findById(product.productId))
            assertNull(products.findByBarcode(businessId, incomplete))
        }
    }

    @Test
    fun suspiciousExactScansRequireChoosingASuggestionAndCheckoutPreservesCodesWithCorrectStock() {
        waitForTag(SalesTestTags.SCREEN)
        val firstCode = "7753176004931"
        val secondCode = "7753176004916"
        val first = createStockedProduct("Arroz con código parecido", barcode = firstCode, priceMinorUnits = 850L, unitCost = "2")
        val second = createStockedProduct("Azúcar con código parecido", barcode = secondCode, priceMinorUnits = 500L, unitCost = "1")
        val oneDigitMissing = firstCode.dropLast(1)
        val threeDigitsMissing = firstCode.dropLast(3)
        // Sin exacto ni recuperación segura una lectura se ignora; estas lecturas coinciden con un
        // SKU y podrían truncar otro GTIN, por eso el cajero debe elegir entre las sugerencias.
        val ambiguityFixtures =
            createAmbiguousReadingFixtures(oneDigitMissing) + createAmbiguousReadingFixtures(threeDigitsMissing)
        val firstSuggestion = SalesTestTags.barcodeSuggestionAdd(first.productId.value, requireNotNull(first.locationId).value)
        val secondSuggestion = SalesTestTags.barcodeSuggestionAdd(second.productId.value, requireNotNull(second.locationId).value)

        fun assertProductCodesUnchanged() =
            runBlocking {
                listOf(first, second).forEach { original ->
                    val stored = requireNotNull(products.findById(original.productId))
                    assertEquals(original.barcode, stored.barcode)
                    assertEquals(original.sku, stored.sku)
                    assertEquals(original.version, stored.version)
                }
                assertEquals(2 + ambiguityFixtures.size, database.productDao().listForBusiness(businessId.value).size)
                assertNull(products.findByBarcode(businessId, oneDigitMissing))
                assertNull(products.findByBarcode(businessId, threeDigitsMissing))
            }

        fun scrollToSuggestion(tag: String) {
            composeRule.waitUntil(15_000L) {
                runCatching {
                    composeRule.onNodeWithTag(SalesTestTags.SCREEN).performScrollToNode(hasTestTag(tag))
                    composeRule
                        .onNodeWithTag(tag)
                        .performScrollTo()
                        .assertIsDisplayed()
                        .assertIsEnabled()
                }.isSuccess
            }
        }

        expectDirectCashSale()
        waitForSalesReader()
        composeRule.onNodeWithTag(SalesTestTags.ENTRY_MODE_SCREEN).assertDoesNotExist()
        waitForTag(ScannerCodeInputTestTags.FIELD)

        // La lectura exacta es sospechosa: la omisión frente al código guardado requiere confirmación.
        scan(oneDigitMissing, KeyEvent.KEYCODE_ENTER)
        scrollToSuggestion(firstSuggestion)
        composeRule
            .onNodeWithText(context.resources.getQuantityString(R.plurals.sales_barcode_suggestions_missing_digits, 1, 1))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(R.string.sales_barcode_suggestions_saved_code, firstCode))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(SalesTestTags.SEARCH).assertDoesNotExist()
        composeRule.onNodeWithTag(SalesTestTags.REPLACEMENT_DIALOG).assertDoesNotExist()
        runBlocking {
            // Nada se agrega hasta que el cajero elige una sugerencia.
            assertTrue(requireNotNull(database.saleDao().findActiveDraft(businessId.value, "PEN")).lines.isEmpty())
        }
        assertProductCodesUnchanged()
        scrollToSuggestion(firstSuggestion)
        clickTag(firstSuggestion)
        waitForCartQuantity(firstCode, "1")
        composeRule.onNodeWithTag(SalesTestTags.BARCODE_SUGGESTIONS).assertDoesNotExist()
        assertProductCodesUnchanged()

        // Con tres dígitos omitidos, ambos códigos son candidatos; el cajero elige el segundo.
        scanFromInputConnection(threeDigitsMissing, KeyEvent.KEYCODE_TAB)
        scrollToSuggestion(firstSuggestion)
        scrollToSuggestion(secondSuggestion)
        composeRule
            .onNodeWithText(context.getString(R.string.sales_barcode_suggestions_saved_code, secondCode))
            .performScrollTo()
            .assertIsDisplayed()
        runBlocking {
            val lines = requireNotNull(database.saleDao().findActiveDraft(businessId.value, "PEN")).lines
            assertEquals(1, lines.size)
            assertEquals(first.productId.value, lines.single().productId)
            assertEquals(0, "1".toBigDecimal().compareTo(lines.single().quantity.toBigDecimal()))
        }
        assertProductCodesUnchanged()
        scrollToSuggestion(secondSuggestion)
        clickTag(secondSuggestion)
        waitForCartQuantity(secondCode, "1")
        composeRule.onNodeWithTag(SalesTestTags.REPLACEMENT_DIALOG).assertDoesNotExist()
        assertProductCodesUnchanged()

        // El código completo reconoce la línea elegida mediante sugerencia sin aumentar su cantidad.
        scan(firstCode, KeyEvent.KEYCODE_NUMPAD_ENTER)
        waitForCartQuantity(firstCode, "1")
        waitForScanFeedback(first.name, "1")
        waitForCartQuantity(secondCode, "1")
        composeRule.onNodeWithTag(SalesTestTags.ASSOCIATION_CANCEL).assertDoesNotExist()
        composeRule.onNodeWithTag(SalesTestTags.BARCODE_SUGGESTIONS).assertDoesNotExist()
        assertProductCodesUnchanged()

        composeRule.onNodeWithTag(SalesTestTags.SCREEN).performScrollToNode(hasTestTag(SalesTestTags.CHECKOUT))
        composeRule.onNodeWithTag(SalesTestTags.CHECKOUT).assertIsEnabled().performClick()
        composeRule.onNodeWithTag(SalesTestTags.CHECKOUT_DIALOG).assertDoesNotExist()
        composeRule.waitUntil(15_000L) {
            runBlocking {
                database
                    .saleDao()
                    .observeRecentPosted(businessId.value, 10)
                    .first()
                    .size == 1
            }
        }
        runBlocking {
            val sale =
                database
                    .saleDao()
                    .observeRecentPosted(businessId.value, 10)
                    .first()
                    .single()
            assertEquals(1_350L, sale.totalMinorUnits)
            assertEquals(2, sale.lineCount)
            val stock = database.inventoryDao().listDiagnosticBalances(businessId.value).associateBy { it.productId }
            mapOf(first.productId.value to "9", second.productId.value to "9").forEach { (productId, quantity) ->
                assertEquals(
                    "Existencias después de elegir sugerencias y cobrar",
                    0,
                    quantity.toBigDecimal().compareTo(stock.getValue(productId).quantityOnHand.toBigDecimal()),
                )
            }
        }
        assertProductCodesUnchanged()
    }

    @Test
    fun suggestionRescanAndCancelKeepUnifiedImeReadyDuringNameSearch() {
        waitForTag(SalesTestTags.SCREEN)
        val barcode = "7753176004930"
        val partialCode = barcode.dropLast(3)
        val product = createStockedProduct("Arroz para reescanear", barcode = barcode, unitCost = "2")
        createAmbiguousReadingFixtures(partialCode)

        fun scrollToSaleControl(tag: String) {
            composeRule.waitUntil(15_000L) {
                runCatching {
                    composeRule.onNodeWithTag(SalesTestTags.SCREEN).performScrollToNode(hasTestTag(tag))
                    composeRule
                        .onNodeWithTag(tag)
                        .performScrollTo()
                        .assertIsDisplayed()
                        .assertIsEnabled()
                }.isSuccess
            }
        }

        expectDirectCashSale()
        waitForSalesReader()
        composeRule.onNodeWithTag(SalesTestTags.ENTRY_MODE_SCREEN).assertDoesNotExist()
        waitForTag(ScannerCodeInputTestTags.FIELD)

        // La lectura parcial coincide con un SKU y es sospechosa: exige elegir manualmente y
        // reescanear/cancelar deben mantener el IME.
        listOf(SalesTestTags.BARCODE_SUGGESTIONS_RESCAN, SalesTestTags.ASSOCIATION_CANCEL)
            .forEach { dismissalTag ->
                scanFromInputConnection(partialCode, KeyEvent.KEYCODE_ENTER)
                scrollToSaleControl(dismissalTag)
                clickTag(dismissalTag)
                composeRule.onNodeWithTag(SalesTestTags.BARCODE_SUGGESTIONS).assertDoesNotExist()
                scanFromInputConnection(barcode, KeyEvent.KEYCODE_ENTER)
                waitForCartQuantity(barcode, "1")
                waitForScanFeedback(product.name, "1")
            }

        // La misma entrada busca por nombre sin desactivar el lector ni abrir un segundo campo.
        scanFromInputConnection(partialCode, KeyEvent.KEYCODE_TAB)
        scrollToSaleControl(SalesTestTags.ASSOCIATION_CANCEL)
        waitForSalesReader()
        scanFromInputConnection("Ar")
        val optionTag = SalesTestTags.option(product.productId.value, requireNotNull(product.locationId).value)
        scrollToSaleControl(optionTag)
        scenario.onActivity { activity ->
            val field = requireNotNull(activity.window.decorView.findViewWithTag<EditText>(ScannerCodeInputTestTags.FIELD))
            assertTrue("Buscar por nombre debe conservar la entrada unificada", field.isEnabled)
            assertTrue(field.hasFocus())
            assertEquals("Ar", field.text.toString())
        }
        composeRule.onNodeWithTag(SalesTestTags.SEARCH).assertDoesNotExist()
        scrollToSaleControl(SalesTestTags.ASSOCIATION_CANCEL)
        clickTag(SalesTestTags.ASSOCIATION_CANCEL)
        waitForSalesReader()
        scenario.onActivity { activity ->
            val field = requireNotNull(activity.window.decorView.findViewWithTag<EditText>(ScannerCodeInputTestTags.FIELD))
            assertEquals("", field.text.toString())
        }
        scanFromInputConnection(barcode, KeyEvent.KEYCODE_ENTER)
        waitForCartQuantity(barcode, "1")
        waitForScanFeedback(product.name, "1")

        runBlocking {
            val lines = requireNotNull(database.saleDao().findActiveDraft(businessId.value, "PEN")).lines
            assertEquals(1, lines.size)
            assertEquals(product.productId.value, lines.single().productId)
            assertEquals(0, "1".toBigDecimal().compareTo(lines.single().quantity.toBigDecimal()))
            val stored = requireNotNull(products.findById(product.productId))
            assertEquals(barcode, stored.barcode)
            assertEquals(product.version, stored.version)
            assertNull(products.findByBarcode(businessId, partialCode))
        }
    }

    @Test
    fun fullLeading77BarcodesAddThreeProductsAndRepeatedImeScanPreservesTheSameLine() {
        waitForTag(SalesTestTags.SCREEN)
        val codes = listOf("77529305", "7753176004930", "7753176004916")
        val fixtures =
            codes.mapIndexed { index, code ->
                createStockedProduct("Producto ${index + 1}", barcode = code)
            }
        expectDirectCashSale()
        waitForSalesReader()
        composeRule.onNodeWithTag(SalesTestTags.ENTRY_MODE_SCREEN).assertDoesNotExist()
        waitForTag(ScannerCodeInputTestTags.FIELD)

        codes.forEach { code ->
            scanFromInputConnection(code, KeyEvent.KEYCODE_ENTER)
            waitForCartQuantity(code, "1")
            composeRule.onNodeWithTag(SalesTestTags.ASSOCIATION_CANCEL).assertDoesNotExist()
            composeRule.onNodeWithTag(SalesTestTags.SEARCH).assertDoesNotExist()
        }
        val repeatedProduct = fixtures[1]
        val firstLineId =
            runBlocking {
                requireNotNull(database.saleDao().findActiveDraft(businessId.value, "PEN"))
                    .lines
                    .single { it.productId == repeatedProduct.productId.value }
                    .saleLineId
            }
        scanFromInputConnection(codes[1], KeyEvent.KEYCODE_ENTER)
        waitForCartQuantity(codes[1], "1")
        waitForScanFeedback(repeatedProduct.name, "1")
        composeRule.onNodeWithTag(SalesTestTags.ASSOCIATION_CANCEL).assertDoesNotExist()
        composeRule.onNodeWithTag(SalesTestTags.SEARCH).assertDoesNotExist()
        runBlocking {
            val lines = requireNotNull(database.saleDao().findActiveDraft(businessId.value, "PEN")).lines
            assertEquals(3, lines.size)
            fixtures.forEach { product ->
                val line = lines.single { it.productId == product.productId.value }
                assertEquals(0, line.quantity.toBigDecimal().compareTo("1".toBigDecimal()))
                val stored = requireNotNull(products.findById(product.productId))
                assertEquals(product.barcode, stored.barcode)
                assertNull(stored.sku)
                assertEquals(product.version, stored.version)
            }
            assertEquals(firstLineId, lines.single { it.productId == repeatedProduct.productId.value }.saleLineId)
        }
    }

    @Test
    fun rapidPhysicalFramesContinueAfterUnknownCodeAndKeepAllKnownQuantities() {
        waitForTag(SalesTestTags.SCREEN)
        val codes = listOf("77529305", "7753176004930", "7753176004916")
        val fixtures =
            codes.mapIndexed { index, code ->
                createStockedProduct("Producto de ráfaga ${index + 1}", barcode = code)
            }
        val unknownCode = "0099001100"
        val frames =
            listOf(
                codes[0],
                codes[1],
                codes[0],
                codes[2],
                unknownCode,
                codes[1],
                codes[2],
                codes[0],
                codes[1],
                codes[2],
                codes[0],
                codes[2],
            )
        expectDirectCashSale()
        waitForSalesReader()
        composeRule.onNodeWithTag(SalesTestTags.ENTRY_MODE_SCREEN).assertDoesNotExist()
        waitForTag(ScannerCodeInputTestTags.FIELD)
        composeRule.waitUntil(15_000L) {
            var ready = false
            scenario.onActivity { activity ->
                val field = activity.window.decorView.findViewWithTag<EditText>(ScannerCodeInputTestTags.FIELD)
                ready = field?.isEnabled == true && field.hasFocus()
            }
            ready
        }

        // Toda la ráfaga atraviesa dispatchKeyEvent antes de ceder el hilo a Compose o al carrito.
        scenario.onActivity { activity ->
            var eventTime = SystemClock.uptimeMillis()
            frames.forEach { frame ->
                val keys = frame.map { KeyEvent.KEYCODE_0 + it.digitToInt() } + KeyEvent.KEYCODE_ENTER
                keys.forEach { key ->
                    val downTime = eventTime
                    listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP).forEach { action ->
                        assertTrue(
                            "La ráfaga debe consumir $frame / tecla $key / acción $action",
                            activity.dispatchKeyEvent(
                                KeyEvent(
                                    downTime,
                                    eventTime++,
                                    action,
                                    key,
                                    0,
                                    0,
                                    keyboard.id,
                                    0,
                                    0,
                                    InputDevice.SOURCE_KEYBOARD,
                                ),
                            ),
                        )
                    }
                }
            }
        }

        val expectedQuantities = codes.associateWith { "1" }
        expectedQuantities.forEach { (barcode, quantity) -> waitForCartQuantity(barcode, quantity) }
        waitForScanFeedback(fixtures.last().name, "1")
        composeRule.onNodeWithTag(SalesTestTags.ASSOCIATION_CANCEL).assertDoesNotExist()
        composeRule.onNodeWithTag(SalesTestTags.SEARCH).assertDoesNotExist()
        composeRule.waitUntil(15_000L) {
            var ready = false
            scenario.onActivity { activity ->
                val field = activity.window.decorView.findViewWithTag<EditText>(ScannerCodeInputTestTags.FIELD)
                ready = field?.isEnabled == true && field.hasFocus() && field.text.isNullOrEmpty()
            }
            ready
        }
        runBlocking {
            val lines = requireNotNull(database.saleDao().findActiveDraft(businessId.value, "PEN")).lines
            assertEquals(3, lines.size)
            fixtures.forEach { product ->
                val line = lines.single { it.productId == product.productId.value }
                val expected = expectedQuantities.getValue(requireNotNull(product.barcode))
                assertEquals(0, line.quantity.toBigDecimal().compareTo(expected.toBigDecimal()))
                val stored = requireNotNull(products.findById(product.productId))
                assertEquals(product.barcode, stored.barcode)
                assertEquals(product.version, stored.version)
            }
            assertEquals(3, database.productDao().listForBusiness(businessId.value).size)
            assertNull(products.findByBarcode(businessId, unknownCode))
            assertTrue(
                database.inventoryDao().listDiagnosticBalances(businessId.value).all {
                    it.quantityOnHand.toBigDecimal().compareTo("10".toBigDecimal()) == 0
                },
            )
        }
    }

    @Test
    fun existingScansUseInventoryEditorSaveAllThreeValuesAndCancelWithoutDuplicateStock() {
        val code = "00778899"
        val product = createStockedProduct("Arroz existente", barcode = code, unitCost = "2")
        val historyBefore = snapshot("stock_movements")
        waitForTag(SalesTestTags.SCREEN)
        navigate(R.string.navigation_inventory)
        waitForTag(ScannerCodeInputTestTags.FIELD)
        scan(code, KeyEvent.KEYCODE_ENTER)
        waitForTag(CatalogsTestTags.PRODUCT_NAME)
        assertEditorDecimal(CatalogsTestTags.PRODUCT_QUANTITY, "10")
        assertEditorDecimal(CatalogsTestTags.PRODUCT_PURCHASE_PRICE, "2")
        assertEditorDecimal(CatalogsTestTags.PRODUCT_SALE_PRICE, "5")
        listOf(
            CatalogsTestTags.PRODUCT_QUANTITY to "7",
            CatalogsTestTags.PRODUCT_PURCHASE_PRICE to "3.25",
            CatalogsTestTags.PRODUCT_SALE_PRICE to "6.50",
        ).forEach { (tag, value) ->
            composeRule.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)
        }
        composeRule.onNodeWithTag(CatalogsTestTags.SAVE_FORM).assertIsEnabled().performClick()
        waitForTag(InventoryTestTags.LIST_SCREEN)
        runBlocking {
            assertEquals(1, database.productDao().listForBusiness(businessId.value).size)
            val saved = requireNotNull(products.findById(product.productId))
            assertEquals(code, saved.barcode)
            assertEquals(650L, saved.salePrice?.minorUnits)
            val balance =
                requireNotNull(
                    database.inventoryDao().findBalance(
                        businessId.value,
                        product.productId.value,
                        requireNotNull(product.locationId).value,
                    ),
                )
            assertEquals(0, "7".toBigDecimal().compareTo(balance.quantityOnHand.toBigDecimal()))
            assertEquals(0, "3.25".toBigDecimal().compareTo(balance.averageUnitCost.toBigDecimal()))
        }
        val historyAfter = snapshot("stock_movements")
        assertTrue(historyAfter.containsAll(historyBefore))
        assertTrue(historyAfter.size > historyBefore.size)

        clickTag(InventoryTestTags.REGISTER_PRODUCTS)
        waitForReader()
        scan(code, KeyEvent.KEYCODE_TAB)
        waitForTag(CatalogsTestTags.PRODUCT_NAME)
        assertEditorDecimal(CatalogsTestTags.PRODUCT_QUANTITY, "7")
        assertEditorDecimal(CatalogsTestTags.PRODUCT_PURCHASE_PRICE, "3.25")
        assertEditorDecimal(CatalogsTestTags.PRODUCT_SALE_PRICE, "6.50")
        composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_QUANTITY).performScrollTo().performTextReplacement("99")
        composeRule.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
        waitForReader()
        assertEquals(historyAfter, snapshot("stock_movements"))
        runBlocking { assertEquals(1, database.productDao().listForBusiness(businessId.value).size) }
    }

    @Test
    fun inventoryListKeepsUsbWithoutEnterUnconfirmedAndNextTerminatedReadIsClean() {
        waitForTag(SalesTestTags.SCREEN)
        navigate(R.string.navigation_inventory)
        waitForTag(InventoryTestTags.LIST_SCREEN)
        waitForTag(ScannerCodeInputTestTags.FIELD)
        composeRule.waitForIdle()

        scan("00990011", terminator = null)
        scenario.onActivity { activity ->
            val field = requireNotNull(activity.window.decorView.findViewWithTag<EditText>(ScannerCodeInputTestTags.FIELD))
            assertEquals("00990011", field.text.toString())
        }
        composeRule.onNodeWithTag(CatalogsTestTags.FORM).assertDoesNotExist()
        // Inventario sólo muestra el campo: sin Enter/Tab del lector la lectura no se confirma.
        composeRule.onNodeWithTag(ScannerCodeInputTestTags.SUBMIT).assertDoesNotExist()
        composeRule.onNodeWithTag(ScannerCodeInputTestTags.RESET).assertDoesNotExist()

        // Tras la pausa, la trama sin sufijo se descarta y no se mezcla con la lectura siguiente.
        waitForInventoryIdleReset()
        scenario.onActivity { activity ->
            val field = requireNotNull(activity.window.decorView.findViewWithTag<EditText>(ScannerCodeInputTestTags.FIELD))
            assertEquals("", field.text.toString())
        }
        composeRule.onNodeWithTag(CatalogsTestTags.FORM).assertDoesNotExist()
        scan("00880022", KeyEvent.KEYCODE_TAB)
        waitForTag(CatalogsTestTags.PRODUCT_BARCODE)
        composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_BARCODE).assertTextContains("00880022")
        runBlocking { assertTrue(database.productDao().listForBusiness(businessId.value).isEmpty()) }
    }

    @Test
    fun rejectedUsbFrameWithoutEnterShowsErrorAndReaderCanBeRestarted() {
        waitForTag(SalesTestTags.SCREEN)
        navigate(R.string.navigation_inventory)
        waitForTag(ScannerCodeInputTestTags.FIELD)
        composeRule.waitForIdle()
        scan("1".repeat(129), terminator = null)
        composeRule.onNodeWithText(context.getString(R.string.inventory_scanner_too_long)).assertIsDisplayed()
        // Inventario ya no muestra «Reiniciar lector»: tras una pausa el lector se limpia solo.
        composeRule.onNodeWithTag(ScannerCodeInputTestTags.RESET).assertDoesNotExist()
        waitForInventoryIdleReset()
        scan("00334455", KeyEvent.KEYCODE_ENTER)
        waitForTag(CatalogsTestTags.PRODUCT_BARCODE)
        composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_BARCODE).assertTextContains("00334455")
        runBlocking { assertTrue(database.productDao().listForBusiness(businessId.value).isEmpty()) }
    }

    @Test
    fun thirtyTwoRapidScansKeepOneUnitAndConfirmationVisibleWhileCartScrolls() {
        waitForTag(SalesTestTags.SCREEN)
        val barcode = "7753176004930"
        val product = createStockedProduct("Producto de ráfaga", barcode = barcode, unitCost = "2")
        runBlocking {
            productInventory.setStock(
                businessId = businessId,
                productId = product.productId,
                locationId = requireNotNull(product.locationId),
                quantity = "100".toBigDecimal(),
                currency = CurrencyCode.of("PEN"),
            )
        }
        expectDirectCashSale()
        waitForSalesReader()
        composeRule.onNodeWithTag(SalesTestTags.ENTRY_MODE_SCREEN).assertDoesNotExist()
        waitForTag(ScannerCodeInputTestTags.FIELD)
        waitForSalesReader()
        val originalCartVersion =
            runBlocking {
                requireNotNull(database.saleDao().findActiveDraft(businessId.value, "PEN")).sale.version
            }
        val startedAt = SystemClock.elapsedRealtime()
        scenario.onActivity { activity ->
            var eventTime = SystemClock.uptimeMillis()
            repeat(32) {
                (barcode.map { KeyEvent.KEYCODE_0 + it.digitToInt() } + KeyEvent.KEYCODE_ENTER).forEach { key ->
                    val down = eventTime
                    listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP).forEach { action ->
                        assertTrue(
                            activity.dispatchKeyEvent(
                                KeyEvent(down, eventTime++, action, key, 0, 0, keyboard.id, 0, 0, InputDevice.SOURCE_KEYBOARD),
                            ),
                        )
                    }
                }
            }
        }
        waitForCartQuantity(barcode, "1")
        waitForScanFeedback(product.name, "1")
        val elapsedMillis = SystemClock.elapsedRealtime() - startedAt
        composeRule
            .onNodeWithTag(SalesTestTags.SCANNER_FEEDBACK)
            .assertIsDisplayed()
            .assertTextContains(product.name, substring = true)
            .assertTextContains("1 NIU", substring = true)
        composeRule.onNodeWithTag(SalesTestTags.SCREEN).performScrollToNode(hasTestTag(SalesTestTags.CHECKOUT))
        composeRule.onNodeWithTag(SalesTestTags.SCANNER_FEEDBACK).assertIsDisplayed()
        composeRule.onNodeWithTag(SalesTestTags.CHECKOUT).assertIsEnabled()
        runBlocking {
            val draft = requireNotNull(database.saleDao().findActiveDraft(businessId.value, "PEN"))
            assertEquals(originalCartVersion + 1L, draft.sale.version)
            val lines = draft.lines
            assertEquals(1, lines.size)
            assertEquals(product.productId.value, lines.single().productId)
            assertEquals(0, "1".toBigDecimal().compareTo(lines.single().quantity.toBigDecimal()))
            assertEquals(500L, lines.single().lineTotalMinorUnits)
            assertEquals(product.version, products.findById(product.productId)?.version)
            assertEquals(
                0,
                "100".toBigDecimal().compareTo(
                    database
                        .inventoryDao()
                        .listDiagnosticBalances(businessId.value)
                        .single()
                        .quantityOnHand
                        .toBigDecimal(),
                ),
            )
        }
        File(requireNotNull(context.getExternalFilesDir(null)), "scanner-burst-metrics.json").writeText(
            JSONObject()
                .put("frames", 32)
                .put("savedQuantity", 1)
                .put("elapsedMillis", elapsedMillis)
                .put("keyboardDeviceId", keyboard.id)
                .put("keyboardDeviceName", keyboard.name)
                .toString() + "\n",
        )
    }

    private fun unifiedNameAndScannerJourney(credit: Boolean) {
        val named = createStockedProduct("Arroz por nombre", barcode = "00123450", unitCost = "2")
        val scanned = createStockedProduct("Azúcar por lector", barcode = "00005678", unitCost = "1")
        val namedCode = requireNotNull(named.barcode)
        val scannedCode = requireNotNull(scanned.barcode)
        val optionTag = SalesTestTags.option(named.productId.value, requireNotNull(named.locationId).value)
        val movementsBefore = snapshot("stock_movements")
        expectDirectCashSale()
        if (credit) openCreditSale()
        waitForSalesReader()
        composeRule.onNodeWithTag(SalesTestTags.ENTRY_MODE_SCREEN).assertDoesNotExist()
        composeRule.onNodeWithTag(SalesTestTags.SCANNER_MODE).assertDoesNotExist()
        composeRule.onNodeWithTag(SalesTestTags.MANUAL_MODE).assertDoesNotExist()
        composeRule.onNodeWithTag(SalesTestTags.SEARCH).assertDoesNotExist()

        scanFromInputConnection("A")
        composeRule.onNodeWithTag(optionTag).assertDoesNotExist()
        scanFromInputConnection("r")
        scrollToSalesControl(optionTag)
        scenario.onActivity { activity ->
            val field = requireNotNull(activity.window.decorView.findViewWithTag<EditText>(ScannerCodeInputTestTags.FIELD))
            assertEquals("Ar", field.text.toString())
            assertTrue(field.isEnabled)
            assertTrue(field.hasFocus())
        }
        runBlocking {
            assertTrue(requireNotNull(database.saleDao().findActiveDraft(businessId.value, "PEN")).lines.isEmpty())
        }
        clickTag(optionTag)
        waitForCartQuantity(namedCode, "1")
        waitForSalesReader()
        lateinit var previousConnection: InputConnection
        scenario.onActivity { activity ->
            val field = requireNotNull(activity.window.decorView.findViewWithTag<EditText>(ScannerCodeInputTestTags.FIELD))
            assertEquals("", field.text.toString())
            previousConnection = requireNotNull(field.onCreateInputConnection(EditorInfo()))
        }

        if (credit) {
            scrollToSalesControl(SalesTestTags.DEBTOR_NAME)
            composeRule.onNodeWithTag(SalesTestTags.DEBTOR_NAME).performClick().performTextReplacement("Ana Pérez")
            composeRule.waitForIdle()
            scenario.onActivity { activity ->
                val field = requireNotNull(activity.window.decorView.findViewWithTag<EditText>(ScannerCodeInputTestTags.FIELD))
                assertTrue("La entrada debe seguir disponible para volver desde el nombre del cliente", field.isEnabled)
                assertFalse("Editar el cliente no debe perder el foco por una recomposición", field.hasFocus())
                assertTrue(previousConnection.commitText("$scannedCode\r", 1))
            }
            runBlocking {
                val draft = requireNotNull(database.saleDao().findActiveDraft(businessId.value, "PEN"))
                assertEquals(1, draft.lines.size)
                assertEquals(named.productId.value, draft.lines.single().productId)
            }
            clickTag(SalesTestTags.SCANNER_RESUME)
            waitForSalesReader()
            scenario.onActivity { activity ->
                val field = requireNotNull(activity.window.decorView.findViewWithTag<EditText>(ScannerCodeInputTestTags.FIELD))
                assertTrue(previousConnection.commitText("$scannedCode\r", 1))
                assertEquals("", field.text.toString())
            }
            composeRule.onNodeWithTag(SalesTestTags.DEBTOR_NAME).assertTextContains("Ana Pérez")
        }

        // El helper conserva ceros iniciales, Down/Up físicos y el doble sufijo original.
        scan(scannedCode, KeyEvent.KEYCODE_TAB)
        waitForCartQuantity(scannedCode, "1")
        waitForSalesReader()
        scan(scannedCode, KeyEvent.KEYCODE_ENTER)
        waitForCartQuantity(scannedCode, "1")
        waitForScanFeedback(scanned.name, "1")
        scenario.onActivity { activity ->
            val field = requireNotNull(activity.window.decorView.findViewWithTag<EditText>(ScannerCodeInputTestTags.FIELD))
            assertEquals("", field.text.toString())
        }
        runBlocking {
            val draft = requireNotNull(database.saleDao().findActiveDraft(businessId.value, "PEN"))
            assertEquals(2, draft.lines.size)
            assertEquals(setOf(named.productId.value, scanned.productId.value), draft.lines.map { it.productId }.toSet())
            assertEquals(1_000L, draft.sale.totalMinorUnits)
            listOf(named, scanned).forEach { product ->
                assertEquals(product, products.findById(product.productId))
            }
        }
        assertEquals(movementsBefore, snapshot("stock_movements"))
        scrollToSalesControl(SalesTestTags.CHECKOUT)
        clickTag(SalesTestTags.CHECKOUT)
        composeRule.onNodeWithTag(SalesTestTags.CHECKOUT_DIALOG).assertDoesNotExist()
        composeRule.waitUntil(15_000L) {
            runBlocking {
                database
                    .saleDao()
                    .observeRecentPosted(businessId.value, 10)
                    .first()
                    .size == 1
            }
        }
        runBlocking {
            val sale =
                database
                    .saleDao()
                    .observeRecentPosted(businessId.value, 10)
                    .first()
                    .single()
            assertEquals(1_000L, sale.totalMinorUnits)
            assertEquals(2, sale.lineCount)
            val debt = database.debtDao().findDebtForSale(sale.saleId)
            if (credit) {
                assertEquals("Ana Pérez", requireNotNull(debt).debtorName)
                assertEquals(1_000L, debt.balanceMinorUnits)
            } else {
                assertNull(debt)
            }
            assertEquals(2, database.inventoryDao().listMovementsForSale(businessId.value, sale.saleId).count { it.type == "SALE" })
            database.inventoryDao().listDiagnosticBalances(businessId.value).forEach { balance ->
                assertEquals(0, "9".toBigDecimal().compareTo(balance.quantityOnHand.toBigDecimal()))
            }
        }
        if (credit) {
            // Abierta desde Vender, la venta a crédito registrada termina en la lista de deudores.
            val debtId =
                runBlocking {
                    val sale = database.saleDao().observeRecentPosted(businessId.value, 10).first().single()
                    requireNotNull(database.debtDao().findDebtForSale(sale.saleId)).debtId
                }
            waitForTag(DebtorsTestTags.LIST_SCREEN)
            waitForTagGone(SalesTestTags.DEBTOR_NAME)
            composeRule.onNodeWithTag(ReportsTestTags.DEBTORS_CONTENT).assertDoesNotExist()
            composeRule.onNodeWithTag(DebtorsTestTags.LIST_SCREEN).performScrollToNode(hasTestTag(DebtorsTestTags.debt(debtId)))
            composeRule.onNodeWithTag(DebtorsTestTags.debt(debtId)).assertIsDisplayed()
        } else {
            expectDirectCashSale()
        }
    }

    /** Vender abre directamente la venta al contado, sin selector de tipo ni "Volver". */
    private fun expectDirectCashSale() {
        waitForTag(SalesTestTags.SCREEN)
        waitForTag(SalesTestTags.OPEN_CREDIT_SALE)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SalesTestTags.ENTRY_KIND_SCREEN).assertDoesNotExist()
        composeRule.onNodeWithTag(SalesTestTags.STEP_BACK).assertDoesNotExist()
        composeRule.onNodeWithTag(SalesTestTags.DEBTOR_NAME).assertDoesNotExist()
    }

    /** La venta a crédito se abre desde el icono de la barra superior de Vender. */
    private fun openCreditSale() {
        clickTag(SalesTestTags.OPEN_CREDIT_SALE)
        waitForTag(SalesTestTags.DEBTOR_NAME)
        waitForTagGone(SalesTestTags.OPEN_CREDIT_SALE)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SalesTestTags.ENTRY_KIND_SCREEN).assertDoesNotExist()
    }

    private fun waitForTagGone(tag: String) {
        composeRule.waitUntil(15_000L) {
            runCatching { composeRule.onNodeWithTag(tag).assertDoesNotExist() }.isSuccess
        }
    }

    private fun scrollToSalesControl(tag: String) {
        composeRule.waitUntil(15_000L) {
            runCatching {
                composeRule.onNodeWithTag(SalesTestTags.SCREEN).performScrollToNode(hasTestTag(tag))
                composeRule
                    .onNodeWithTag(tag)
                    .performScrollTo()
                    .assertIsDisplayed()
                    .assertIsEnabled()
            }.isSuccess
        }
    }

    private fun waitForSalesReader() {
        waitForTag(ScannerCodeInputTestTags.FIELD)
        composeRule.waitUntil(15_000L) {
            var ready = false
            scenario.onActivity { activity ->
                val field = activity.window.decorView.findViewWithTag<EditText>(ScannerCodeInputTestTags.FIELD)
                ready = field?.isEnabled == true && field.hasFocus()
            }
            ready
        }
    }

    private fun waitForScanFeedback(
        productName: String,
        quantity: String,
    ) {
        composeRule.waitUntil(15_000L) {
            runCatching {
                composeRule
                    .onNodeWithTag(SalesTestTags.SCANNER_FEEDBACK)
                    .assertIsDisplayed()
                    .assertTextContains(productName, substring = true)
                    .assertTextContains("$quantity NIU", substring = true)
            }.isSuccess
        }
    }

    private fun setCartQuantityManually(
        barcode: String,
        quantity: String,
    ) {
        val lineId =
            runBlocking {
                val product = database.productDao().listForBusiness(businessId.value).single { it.barcode == barcode }
                requireNotNull(database.saleDao().findActiveDraft(businessId.value, "PEN"))
                    .lines
                    .single { it.productId == product.productId }
                    .saleLineId
            }
        val quantityTag = SalesTestTags.quantity(lineId)
        scrollToSalesControl(quantityTag)
        composeRule.onNodeWithTag(quantityTag).performClick().performTextReplacement(quantity)
        composeRule.waitForIdle()
        if (runCatching { composeRule.onNodeWithTag(SalesTestTags.SCANNER_RESUME).assertIsDisplayed() }.isSuccess) {
            composeRule.onNodeWithTag(SalesTestTags.SCANNER_RESUME).performClick()
        }
        waitForCartQuantity(barcode, quantity)
        waitForSalesReader()
    }

    private fun createStockedProduct(
        name: String,
        sku: String? = null,
        barcode: String? = null,
        priceMinorUnits: Long = 500L,
        unitCost: String? = null,
        stocked: Boolean = true,
    ): Product =
        runBlocking {
            val unitId =
                units
                    .search(businessId, CatalogSearch(limit = 1))
                    .items
                    .single()
                    .unitId
            val locationId =
                locations
                    .search(businessId, CatalogSearch(limit = 1))
                    .items
                    .single()
                    .locationId
            val currency = CurrencyCode.of("PEN")
            val product =
                products.create(
                    Product(
                        productId = ProductId.from(UUID.randomUUID()),
                        businessId = businessId,
                        unitId = unitId,
                        locationId = locationId,
                        name = name,
                        sku = sku,
                        barcode = barcode,
                        salePrice = Money.ofMinor(priceMinorUnits, currency),
                        createdAt = Instant.EPOCH,
                        updatedAt = Instant.EPOCH,
                    ),
                )
            if (!stocked) return@runBlocking product
            if (unitCost == null) {
                productInventory.setStock(
                    businessId = businessId,
                    productId = product.productId,
                    locationId = locationId,
                    quantity = "10".toBigDecimal(),
                    currency = currency,
                )
            } else {
                productInventory.addStock(
                    businessId = businessId,
                    productId = product.productId,
                    locationId = locationId,
                    quantityToAdd = "10".toBigDecimal(),
                    currency = currency,
                    unitCost = unitCost.toBigDecimal(),
                )
            }
            product
        }

    /**
     * Ventas ignora una lectura sin coincidencia exacta ni recuperación automática segura. Las
     * elecciones manuales, incluido el registro desde Ventas, solo aparecen si la lectura coincide
     * exacto (aquí con un SKU) y otro producto guarda un GTIN válido del que podría ser un truncado.
     * Ninguno tiene stock, de modo que no aparecen como sugerencias vendibles.
     */
    private fun createAmbiguousReadingFixtures(reading: String): List<Product> =
        listOf(
            createStockedProduct("Código interno $reading", sku = reading, stocked = false),
            createStockedProduct("GTIN completo de $reading", barcode = validGtinContaining(reading), stocked = false),
        )

    /** GTIN válido que contiene la lectura con una o dos cifras iniciales omitidas. */
    private fun validGtinContaining(reading: String): String {
        val length = listOf(8, 12, 13, 14).first { it - reading.length in 1..2 }
        val padding = "1".repeat(length - reading.length - 1)
        return (0..9).map { "$it$padding$reading" }.first { candidate ->
            candidate.reversed().withIndex().sumOf { (index, digit) ->
                (digit - '0') * if (index % 2 == 0) 1 else 3
            } % 10 == 0
        }
    }

    private fun assertEditorDecimal(
        tag: String,
        expected: String,
    ) {
        val field = composeRule.onNodeWithTag(tag).performScrollTo().assertIsEnabled()
        val value = field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertEquals(0, expected.toBigDecimal().compareTo(value.replace(',', '.').toBigDecimal()))
    }

    private fun snapshot(table: String): List<List<String?>> =
        database.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY 1").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add((0 until cursor.columnCount).map { index -> cursor.getString(index) })
            }
        }

    private fun registerProduct(
        barcode: String,
        name: String,
        salePrice: String,
        terminator: Int,
    ) {
        scan(barcode, terminator)
        waitForTag(CatalogsTestTags.PRODUCT_BARCODE)
        composeRule.onNodeWithTag(CatalogsTestTags.PRODUCT_BARCODE).assertTextContains(barcode)
        listOf(
            CatalogsTestTags.PRODUCT_NAME to name,
            CatalogsTestTags.PRODUCT_QUANTITY to "10",
            CatalogsTestTags.PRODUCT_PURCHASE_PRICE to "2.00",
            CatalogsTestTags.PRODUCT_SALE_PRICE to salePrice,
        ).forEach { (tag, value) ->
            composeRule.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)
        }
        composeRule.onNodeWithTag(CatalogsTestTags.SAVE_FORM).performScrollTo().performClick()
        waitForReader()
        composeRule.onNodeWithTag(CatalogsTestTags.FORM).assertDoesNotExist()
    }

    private fun waitForReader() {
        waitForTag(InventoryTestTags.REGISTRATION_SCREEN)
        composeRule.waitUntil(15_000L) {
            runCatching {
                composeRule.onNodeWithText(context.getString(R.string.inventory_registration_ready)).assertIsDisplayed()
            }.isSuccess
        }
    }

    private fun waitForCartQuantity(
        barcode: String,
        quantity: String,
    ) {
        composeRule.waitUntil(15_000L) {
            runBlocking {
                val product = database.productDao().listForBusiness(businessId.value).single { it.barcode == barcode }
                database
                    .saleDao()
                    .findActiveDraft(businessId.value, "PEN")
                    ?.lines
                    ?.singleOrNull {
                        it.productId == product.productId
                    }?.quantity
                    ?.toBigDecimal()
                    ?.compareTo(quantity.toBigDecimal()) == 0
            }
        }
        composeRule.waitForIdle()
    }

    private fun scanFromInputConnection(
        value: String,
        terminator: Int? = null,
    ) {
        waitForTag(ScannerCodeInputTestTags.FIELD)
        composeRule.waitForIdle()
        scenario.onActivity { activity ->
            val field = requireNotNull(activity.window.decorView.findViewWithTag<EditText>(ScannerCodeInputTestTags.FIELD))
            assertTrue("El campo de ventas debe conservar el foco para recibir al lector Android", field.hasFocus())
            val connection = requireNotNull(field.onCreateInputConnection(EditorInfo()))
            assertTrue(connection.commitText(value, 1))
            if (terminator != null) {
                val time = SystemClock.uptimeMillis()
                val down =
                    KeyEvent(
                        time,
                        time,
                        KeyEvent.ACTION_DOWN,
                        terminator,
                        0,
                        0,
                        KeyCharacterMap.VIRTUAL_KEYBOARD,
                        0,
                        0,
                        InputDevice.SOURCE_KEYBOARD,
                    )
                assertTrue(activity.dispatchKeyEvent(down))
                assertTrue(activity.dispatchKeyEvent(KeyEvent.changeAction(down, KeyEvent.ACTION_UP)))
            }
        }
        composeRule.waitForIdle()
    }

    /** Down/Up físicos, incluido sufijo doble CR/LF: pasan por MainActivity y el adaptador. */
    /** Espera más que la pausa tras la cual Inventario descarta una lectura pendiente o rechazada. */
    private fun waitForInventoryIdleReset() {
        Thread.sleep(INVENTORY_IDLE_READ_RESET_MILLIS + 500L)
        composeRule.mainClock.advanceTimeBy(INVENTORY_IDLE_READ_RESET_MILLIS + 500L)
        composeRule.waitForIdle()
    }

    private fun scan(
        value: String,
        terminator: Int?,
    ) {
        scenario.onActivity { activity ->
            var time = SystemClock.uptimeMillis()
            val keys =
                value.map { KeyEvent.KEYCODE_0 + it.digitToInt() } +
                    (terminator?.let { listOf(it, it) } ?: emptyList())
            keys.forEach { keyCode ->
                val down = time
                listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP).forEach { action ->
                    assertTrue(
                        "El lector debe consumir $value / tecla $keyCode / acción $action",
                        activity.dispatchKeyEvent(
                            KeyEvent(down, time++, action, keyCode, 0, 0, keyboard.id, 0, 0, InputDevice.SOURCE_KEYBOARD),
                        ),
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun navigate(label: Int) {
        val tab = hasText(context.getString(label)) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        composeRule.onNode(tab).performClick()
    }

    private fun clickTag(tag: String) {
        composeRule.waitUntil(15_000L) {
            runCatching { composeRule.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled() }.isSuccess
        }
        composeRule.onNodeWithTag(tag).performClick()
    }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(15_000L) {
            runCatching { composeRule.onNodeWithTag(tag).assertIsDisplayed() }.isSuccess
        }
    }
}
