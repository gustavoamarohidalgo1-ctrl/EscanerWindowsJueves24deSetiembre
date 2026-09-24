package com.facturastock.app.navigation

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
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
import com.facturastock.app.domain.repository.CheckoutSaleCommand
import com.facturastock.app.domain.repository.CheckoutSaleResult
import com.facturastock.app.domain.repository.RecordDebtPaymentCommand
import com.facturastock.app.domain.repository.RecordDebtPaymentResult
import com.facturastock.app.domain.repository.SaleCartMutationResult
import com.facturastock.app.domain.repository.SaveSaleCartLineCommand
import com.facturastock.app.feature.debtors.DebtorsTestTags
import com.facturastock.app.feature.reports.ReportsTestTags
import com.facturastock.app.feature.sales.SalesTestTags
import com.facturastock.app.resources.Res
import com.facturastock.app.resources.debt_delete_cancel
import com.facturastock.app.resources.debt_delete_confirm
import com.facturastock.app.resources.navigation_reports
import com.facturastock.app.testing.DesktopAppHarness
import com.facturastock.app.testing.TestAppConfigurationState
import com.facturastock.app.testing.performClickOnUiThread
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Eliminación desde la ventana real con crédito y abono reales sobre una base temporal. */
class DebtDeletionJourneyTest {
    @get:Rule(order = 0)
    val harness = DesktopAppHarness()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    private val database get() = harness.component.database()
    private val businesses get() = harness.component.businesses()
    private val units get() = harness.component.units()
    private val locations get() = harness.component.locations()
    private val registration get() = harness.component.productRegistration()
    private val sales get() = harness.component.sales()
    private val debts get() = harness.component.debts()

    private var seededSaleId: SaleId? = null
    private var seededDebtId: DebtId? = null
    private val saleId get() = requireNotNull(seededSaleId)
    private val debtId get() = requireNotNull(seededDebtId)
    private val currency = CurrencyCode.of("PEN")
    private val productId = ProductId.from(UUID.randomUUID())
    private val locationId = LocationId.from(UUID.randomUUID())
    private val businessId get() = requireNotNull(TestAppConfigurationState.current.value.businessId)

    @Before
    fun setUp() {
        runBlocking { seedCreditWithPayment() }
        startApp()
    }

    private fun startApp() {
        harness.setAppContent(composeRule)
        waitForSalesWithActiveDraft()
    }

    private fun waitForSalesWithActiveDraft() {
        waitForTag(SalesTestTags.SCREEN)
        // El inicio de Vender abre su siguiente borrador. Se estabiliza antes de comparar tablas.
        composeRule.waitUntil(15_000L) {
            runBlocking { database.saleDao().findActiveDraft(businessId.value, currency.value) != null }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun cancelDebtDeletionKeepsEveryDatabaseTableAndReturnsToTheSameDebt() {
        openDebt()
        val before = snapshotTables()

        openDeletionPreview()
        assertEquals(before, snapshotTables())
        dialogButton(Res.string.debt_delete_cancel).assertIsEnabled().performClickOnUiThread(composeRule)
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
        dialogButton(Res.string.debt_delete_confirm).assertIsEnabled().performClickOnUiThread(composeRule)
        waitForTag(DebtorsTestTags.LIST_SCREEN)
        waitForTag(DebtorsTestTags.EMPTY)
        composeRule.onNodeWithTag(DebtorsTestTags.debt(debtId.value)).assertDoesNotExist()
        composeRule.onNodeWithTag(DebtorsTestTags.DETAIL_SCREEN).assertDoesNotExist()

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

        // Cerrar y reabrir la ventana: lo guardado en Room sigue sin la deuda eliminada.
        harness.restartApp(composeRule)
        waitForSalesWithActiveDraft()
        openDebtorsList()
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
        openDebtorsList()
        val tag = DebtorsTestTags.debt(debtId.value)
        composeRule.onNodeWithTag(DebtorsTestTags.LIST_SCREEN).performScrollToNode(hasTestTag(tag))
        clickTag(tag)
        waitForTag(DebtorsTestTags.DETAIL_SCREEN)
    }

    private fun openDebtorsList() {
        composeRule
            .onNode(
                hasText(str(Res.string.navigation_reports)) and
                    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab),
            ).performClickOnUiThread(composeRule)
        waitForTag(ReportsTestTags.SCREEN)
        composeRule.onNodeWithTag(ReportsTestTags.SCREEN).performScrollToNode(hasTestTag(ReportsTestTags.OPEN_DEBTORS))
        clickTag(ReportsTestTags.OPEN_DEBTORS)
        waitForTag(DebtorsTestTags.LIST_SCREEN)
    }

    private fun openDeletionPreview() {
        composeRule.onNodeWithTag(DebtorsTestTags.DETAIL_SCREEN).performScrollToNode(hasTestTag(DebtorsTestTags.DELETE))
        clickTag(DebtorsTestTags.DELETE)
        waitForTag(DebtorsTestTags.DELETE_DIALOG)
        waitForTag(DebtorsTestTags.DELETE_IMPACT)
        composeRule.waitUntil(15_000L) {
            runCatching { dialogButton(Res.string.debt_delete_confirm).assertIsDisplayed().assertIsEnabled() }.isSuccess
        }
    }

    private fun dialogButton(label: StringResource) =
        composeRule.onNode(
            hasText(str(label)) and hasAnyAncestor(hasTestTag(DebtorsTestTags.DELETE_DIALOG)),
        )

    private fun clickTag(tag: String) {
        // La pestaña Deudores de Reportes se habilita cuando el reporte identifica el negocio.
        composeRule.waitUntil(15_000L) {
            runCatching { composeRule.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled() }.isSuccess
        }
        composeRule.onNodeWithTag(tag).performClickOnUiThread(composeRule)
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

    private fun snapshotTables(): Map<String, List<List<String?>>> = database.snapshotAllTables()

    private fun str(resource: StringResource): String = runBlocking { getString(resource) }
}
