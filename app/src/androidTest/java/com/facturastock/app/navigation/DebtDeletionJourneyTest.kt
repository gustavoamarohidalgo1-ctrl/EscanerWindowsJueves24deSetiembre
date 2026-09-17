package com.facturastock.app.navigation

import android.database.Cursor
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.facturastock.app.MainActivity
import com.facturastock.app.R
import com.facturastock.app.core.input.KeyboardWedgeRouter
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.domain.model.Business
import com.facturastock.app.domain.model.CatalogMutationResult
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.DebtPaymentMethod
import com.facturastock.app.domain.model.InventoryLocation
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.UnitCost
import com.facturastock.app.domain.model.UnitOfMeasure
import com.facturastock.app.domain.model.id.DebtId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.SaleId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.repository.BusinessRepository
import com.facturastock.app.domain.repository.CheckoutSaleCommand
import com.facturastock.app.domain.repository.CheckoutSaleResult
import com.facturastock.app.domain.repository.DebtRepository
import com.facturastock.app.domain.repository.InventoryLocationRepository
import com.facturastock.app.domain.repository.ProductRegistrationRepository
import com.facturastock.app.domain.repository.RecordDebtPaymentCommand
import com.facturastock.app.domain.repository.RecordDebtPaymentResult
import com.facturastock.app.domain.repository.SaleCartMutationResult
import com.facturastock.app.domain.repository.SaleRepository
import com.facturastock.app.domain.repository.SaveSaleCartLineCommand
import com.facturastock.app.domain.repository.UnitRepository
import com.facturastock.app.feature.debtors.DebtorsTestTags
import com.facturastock.app.feature.sales.SalesTestTags
import com.facturastock.app.testing.TestAppConfigurationState
import com.facturastock.app.testing.completedGateConfiguration
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

/** Eliminación desde la Activity real con crédito y abono reales; sólo admite emuladores. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DebtDeletionJourneyTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject lateinit var database: FacturaStockDatabase

    @Inject lateinit var businesses: BusinessRepository

    @Inject lateinit var units: UnitRepository

    @Inject lateinit var locations: InventoryLocationRepository

    @Inject lateinit var registration: ProductRegistrationRepository

    @Inject lateinit var sales: SaleRepository

    @Inject lateinit var debts: DebtRepository

    private lateinit var scenario: ActivityScenario<MainActivity>
    private var seededSaleId: SaleId? = null
    private var seededDebtId: DebtId? = null
    private val saleId get() = requireNotNull(seededSaleId)
    private val debtId get() = requireNotNull(seededDebtId)
    private val currency = CurrencyCode.of("PEN")
    private val productId = ProductId.from(UUID.randomUUID())
    private val locationId = LocationId.from(UUID.randomUUID())
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val businessId get() = requireNotNull(TestAppConfigurationState.current.value.businessId)

    @Before
    fun setUp() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) {
            "Este recorrido elimina sólo datos sintéticos de emulador; nunca se ejecuta en la tablet."
        }
        KeyboardWedgeRouter.deactivate()
        TestAppConfigurationState.current.value = completedGateConfiguration()
        context.deleteDatabase(FacturaStockDatabase.NAME)
        hiltRule.inject()
        runBlocking { seedCreditWithPayment() }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        // Sólo este fixture sintético protegido por el guard de emulador permite evidencia visual.
        // FLAG_SECURE permanece intacto en producción.
        scenario.onActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        waitForTag(SalesTestTags.ENTRY_KIND_SCREEN)
        // El inicio de Vender abre su siguiente borrador. Se estabiliza antes de comparar tablas.
        composeRule.waitUntil(15_000L) {
            runBlocking { database.saleDao().findActiveDraft(businessId.value, currency.value) != null }
        }
        composeRule.waitForIdle()
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) scenario.close()
        KeyboardWedgeRouter.deactivate()
        if (::database.isInitialized) database.close()
    }

    @Test
    fun cancelDebtDeletionKeepsEveryDatabaseTableAndReturnsToTheSameDebt() {
        openDebt()
        val before = snapshotTables()

        openDeletionPreview()
        assertEquals(before, snapshotTables())
        dialogButton(R.string.debt_delete_cancel).assertIsEnabled().performClick()
        waitForTag(DebtorsTestTags.DETAIL_SCREEN)
        composeRule.onNodeWithTag(DebtorsTestTags.DELETE_DIALOG).assertDoesNotExist()

        assertEquals(before, snapshotTables())
        runBlocking {
            assertNull(database.saleVoidDao().findBySaleId(businessId.value, saleId.value))
            assertEquals(debtId, requireNotNull(debts.observeDetail(businessId, debtId).first()).debt.debtId)
        }
        assertStock("8")
    }

    @Test
    fun confirmDebtDeletionReturnsToListRestoresStockOnceAndPreservesPaidHistory() {
        openDebt()
        val saleBefore = runBlocking { requireNotNull(database.saleDao().findWithLines(saleId.value)) }
        val debtBefore = runBlocking { requireNotNull(database.debtDao().findDebt(debtId.value)) }
        val paymentsBefore = runBlocking { database.debtDao().findPaymentsForDebt(debtId.value) }
        val movementBefore = runBlocking { database.inventoryDao().listMovementsForSale(businessId.value, saleId.value).single() }
        assertEquals(1, paymentsBefore.size)
        assertStock("8")

        openDeletionPreview()
        dialogButton(R.string.debt_delete_confirm).assertIsEnabled().performClick()
        waitForTag(DebtorsTestTags.LIST_SCREEN)
        waitForTag(DebtorsTestTags.EMPTY)
        composeRule.onNodeWithTag(DebtorsTestTags.debt(debtId.value)).assertDoesNotExist()
        composeRule.onNodeWithTag(DebtorsTestTags.DETAIL_SCREEN).assertDoesNotExist()
        captureOptionalEvidence("lista-despues.png")

        runBlocking {
            val receipt = requireNotNull(database.saleVoidDao().findBySaleId(businessId.value, saleId.value))
            assertEquals(400L, receipt.refundedAmountMinorUnits)
            assertEquals(600L, receipt.cancelledDebtBalanceMinorUnits)
            assertEquals(currency.value, receipt.currencyCode)
            assertEquals(saleBefore, database.saleDao().findWithLines(saleId.value))
            assertEquals(debtBefore, database.debtDao().findDebt(debtId.value))
            assertEquals(paymentsBefore, database.debtDao().findPaymentsForDebt(debtId.value))
            val movements = database.inventoryDao().listMovementsForSale(businessId.value, saleId.value)
            assertEquals(2, movements.size)
            assertEquals(movementBefore, movements.single { it.type == "SALE" })
            assertDecimal("2", movements.single { it.type == "SALE_VOID" }.quantityDelta)
            assertEquals(1, database.auditEventDao().listForEntity(businessId.value, "SALE", saleId.value).count { it.eventType == "SALE_VOIDED" })
            assertTrue(debts.observeOpen(businessId).first().isEmpty())
            assertTrue(debts.observeAll(businessId).first().isEmpty())
            assertNull(debts.observeDetail(businessId, debtId).first())
        }
        assertStock("10")
        val after = snapshotTables()

        scenario.recreate()
        waitForTag(DebtorsTestTags.LIST_SCREEN)
        waitForTag(DebtorsTestTags.EMPTY)
        composeRule.onNodeWithTag(DebtorsTestTags.debt(debtId.value)).assertDoesNotExist()
        assertEquals(after, snapshotTables())
        assertStock("10")
    }

    private suspend fun seedCreditWithPayment() {
        businesses.create(Business(businessId, "Negocio eliminar deuda QA", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH))
        val unitId = UnitId.from(UUID.randomUUID())
        units.create(UnitOfMeasure(unitId, businessId, "NIU", "Unidad", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH))
        locations.create(InventoryLocation(locationId, businessId, "Almacén QA", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH))
        val product =
            Product(
                productId = productId,
                businessId = businessId,
                unitId = unitId,
                locationId = locationId,
                name = "Producto crédito QA",
                salePrice = Money.ofMinor(500L, currency),
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            )
        assertTrue(registration.register(product, BigDecimal.TEN, UnitCost.of("2", currency)) is CatalogMutationResult.Saved)
        val opened = sales.createOrResume(businessId, currency).cart
        val saved =
            sales.saveLine(
                businessId,
                SaveSaleCartLineCommand(
                    saleId = opened.saleId,
                    expectedVersion = opened.version,
                    productId = productId,
                    locationId = locationId,
                    quantity = Quantity.of("2"),
                    unitPrice = Money.ofMinor(500L, currency),
                ),
            ) as SaleCartMutationResult.Saved
        seededSaleId = saved.cart.saleId
        assertEquals(
            CheckoutSaleResult.Posted(saleId),
            sales.checkout(
                businessId,
                CheckoutSaleCommand(saleId, saved.cart.version, saved.cart.contentHash, debtorName = "Cliente deuda por error QA"),
            ),
        )
        val debt = requireNotNull(database.debtDao().findDebtForSale(saleId.value))
        seededDebtId = requireNotNull(DebtId.parse(debt.debtId))
        val payment =
            debts.recordPayment(
                businessId,
                RecordDebtPaymentCommand(debtId, debt.version, Money.ofMinor(400L, currency), DebtPaymentMethod.CASH, occurredAt = Instant.now()),
            )
        assertTrue(payment is RecordDebtPaymentResult.Recorded)
        assertEquals(600L, (payment as RecordDebtPaymentResult.Recorded).debt.balance.minorUnits)
    }

    private fun openDebt() {
        clickTag(SalesTestTags.OPEN_DEBTORS)
        waitForTag(DebtorsTestTags.LIST_SCREEN)
        val tag = DebtorsTestTags.debt(debtId.value)
        composeRule.onNodeWithTag(DebtorsTestTags.LIST_SCREEN).performScrollToNode(hasTestTag(tag))
        clickTag(tag)
        waitForTag(DebtorsTestTags.DETAIL_SCREEN)
    }

    private fun openDeletionPreview() {
        composeRule.onNodeWithTag(DebtorsTestTags.DETAIL_SCREEN).performScrollToNode(hasTestTag(DebtorsTestTags.DELETE))
        clickTag(DebtorsTestTags.DELETE)
        waitForTag(DebtorsTestTags.DELETE_DIALOG)
        waitForTag(DebtorsTestTags.DELETE_IMPACT)
        composeRule.waitUntil(15_000L) {
            runCatching { dialogButton(R.string.debt_delete_confirm).assertIsDisplayed().assertIsEnabled() }.isSuccess
        }
        captureOptionalEvidence("confirmacion.png")
    }

    private fun dialogButton(label: Int) =
        composeRule.onNode(
            hasText(context.getString(label)) and hasAnyAncestor(hasTestTag(DebtorsTestTags.DELETE_DIALOG)),
        )

    private fun clickTag(tag: String) {
        waitForTag(tag)
        composeRule.onNodeWithTag(tag).assertIsEnabled().performClick()
    }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(15_000L) {
            runCatching { composeRule.onNodeWithTag(tag).assertIsDisplayed() }.isSuccess
        }
    }

    private fun assertStock(expected: String) =
        runBlocking {
            val balance = requireNotNull(database.inventoryDao().findBalance(businessId.value, productId.value, locationId.value))
            assertDecimal(expected, balance.quantityOnHand)
        }

    private fun assertDecimal(
        expected: String,
        actual: String,
    ) {
        assertEquals(0, BigDecimal(expected).compareTo(BigDecimal(actual)))
    }

    private fun captureOptionalEvidence(name: String) {
        composeRule.waitForIdle()
        runCatching {
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            if (screenshot == null) {
                Log.w("DebtDeletionJourney", "Captura opcional no disponible: $name")
                return@runCatching
            }
            try {
                val folder = File(context.filesDir, "debt-delete-qa").apply { check(isDirectory || mkdirs()) }
                File(folder, name).outputStream().use { output ->
                    check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output))
                }
            } finally {
                screenshot.recycle()
            }
        }.onFailure { Log.w("DebtDeletionJourney", "No se pudo guardar la captura opcional $name", it) }
    }

    private fun snapshotTables(): Map<String, List<List<String?>>> {
        val sqlite = database.openHelper.readableDatabase
        val names =
            sqlite.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name").use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
        return names.associateWith { table ->
            sqlite.query("SELECT * FROM \"${table.replace("\"", "\"\"")}\" ORDER BY rowid").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            (0 until cursor.columnCount).map { column ->
                                if (cursor.getType(column) == Cursor.FIELD_TYPE_BLOB) {
                                    cursor.getBlob(column).joinToString("") { byte -> "%02x".format(byte) }
                                } else {
                                    cursor.getString(column)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
