package com.facturastock.app.navigation

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import com.facturastock.app.core.coroutines.DefaultDispatcherProvider
import com.facturastock.app.data.repository.RoomReportPdfRepository
import com.facturastock.app.domain.model.Business
import com.facturastock.app.domain.model.CatalogMutationResult
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.DebtPaymentMethod
import com.facturastock.app.domain.model.InventoryLocation
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.ReportPdfKind
import com.facturastock.app.domain.model.SalesReportPeriod
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
import com.facturastock.app.domain.usecase.currentSalesReportRange
import com.facturastock.app.feature.debtors.DebtorsTestTags
import com.facturastock.app.feature.reports.ReportsTestTags
import com.facturastock.app.feature.sales.SalesTestTags
import com.facturastock.app.resources.Res
import com.facturastock.app.resources.navigation_reports
import com.facturastock.app.testing.DesktopAppHarness
import com.facturastock.app.testing.TestAppConfigurationState
import com.facturastock.app.ui.format.formatForDisplay
import com.facturastock.app.testing.performClickOnUiThread
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.jetbrains.compose.resources.getString
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Pago directo con la ventana y los repositorios reales sobre una base temporal. */
class DirectDebtPaymentJourneyTest {
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
    private val configuration get() = TestAppConfigurationState.current.value
    private val businessId get() = requireNotNull(configuration.businessId)
    private val reportTab
        get() = hasText(runBlocking { getString(Res.string.navigation_reports) }) and
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    @Before
    fun setUp() {
        runBlocking { seedCreditWithPriorPayment() }
        harness.setAppContent(composeRule)
        waitForSalesWithActiveDraft()
    }

    private fun waitForSalesWithActiveDraft() {
        waitForTag(SalesTestTags.SCREEN)
        composeRule.waitUntil(15_000L) {
            runBlocking { database.saleDao().findActiveDraft(businessId.value, currency.value) != null }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun oneTapPaysOnlyRemainingBalanceAndOpensTodayWithPaymentInReportAndPdf() {
        composeRule.onNode(reportTab).performClickOnUiThread(composeRule)
        waitForTag(ReportsTestTags.SCREEN)
        scrollReportTo(ReportsTestTags.PERIOD_WEEK)
        clickTag(ReportsTestTags.PERIOD_WEEK)
        composeRule.onNodeWithTag(ReportsTestTags.PERIOD_WEEK).assertIsSelected()
        scrollReportTo(ReportsTestTags.OPEN_DEBTORS)
        clickTag(ReportsTestTags.OPEN_DEBTORS)
        waitForTag(DebtorsTestTags.LIST_SCREEN)
        val debtTag = DebtorsTestTags.debt(debtId.value)
        composeRule.onNodeWithTag(DebtorsTestTags.LIST_SCREEN).performScrollToNode(hasTestTag(debtTag))
        clickTag(debtTag)
        waitForTag(DebtorsTestTags.DETAIL_SCREEN)

        val protectedTables = listOf("sales", "sale_lines", "stock_movements", "inventory_balances", "sale_voids")
        val before = protectedTables.associateWith(::snapshot)
        val previousPayment = runBlocking { database.debtDao().findPaymentsForDebt(debtId.value).single() }
        val debtBefore = runBlocking { requireNotNull(database.debtDao().findDebt(debtId.value)) }
        assertEquals(600L, debtBefore.balanceMinorUnits)
        assertStock("8")
        composeRule.onNodeWithTag(DebtorsTestTags.DETAIL_SCREEN).performScrollToNode(hasTestTag(DebtorsTestTags.PAYMENT))
        val requestedAt = Instant.now().toEpochMilli()
        clickTag(DebtorsTestTags.PAYMENT)

        // Un solo gesto debe completar el cobro y navegar; no se responde a ningún modal.
        waitForTag(ReportsTestTags.SCREEN)
        composeRule.onNodeWithTag(DebtorsTestTags.PAYMENT_DIALOG).assertDoesNotExist()
        val completedAt = Instant.now().toEpochMilli()
        val payments = runBlocking { database.debtDao().findPaymentsForDebt(debtId.value) }
        assertEquals(2, payments.size)
        assertEquals(previousPayment, payments.single { it.paymentId == previousPayment.paymentId })
        val payment = payments.single { it.paymentId != previousPayment.paymentId }
        assertEquals(600L, payment.amountMinorUnits)
        assertEquals(0L, payment.balanceAfterMinorUnits)
        assertEquals(DebtPaymentMethod.OTHER.name, payment.method)
        assertEquals(debtBefore.version, payment.expectedDebtVersion)
        assertTrue(payment.occurredAt in requestedAt..completedAt)
        runBlocking {
            val paid = requireNotNull(database.debtDao().findDebt(debtId.value))
            assertEquals("PAID", paid.status)
            assertEquals(0L, paid.balanceMinorUnits)
            assertEquals(debtBefore.version + 1L, paid.version)
            assertEquals(payment.occurredAt, paid.paidAt)
            assertTrue(debts.observeOpen(businessId).first().isEmpty())
            assertNull(database.saleVoidDao().findBySaleId(businessId.value, saleId.value))
        }
        protectedTables.forEach { table -> assertEquals("El cobro alteró $table", before.getValue(table), snapshot(table)) }
        assertStock("8")

        assertTodayAndVisiblePayment(payment.paymentId)
        val pdfs = RoomReportPdfRepository(database, DefaultDispatcherProvider())
        runBlocking {
            val generatedAt = Instant.now()
            val range = currentSalesReportRange(SalesReportPeriod.DAY, generatedAt, configuration.zoneId)
            val pdf = requireNotNull(pdfs.readSnapshot(businessId, range, generatedAt, currency, ReportPdfKind.DAILY_SALES_WITH_DEBTORS))
            assertEquals(SalesReportPeriod.DAY, pdf.range.period)
            assertEquals(setOf(previousPayment.paymentId, payment.paymentId), pdf.debtPayments.map { it.payment.paymentId.value }.toSet())
            assertEquals(2, pdf.debtPayments.size)
            assertEquals(1_000L, pdf.debtPayments.sumOf { it.payment.amount.minorUnits })
            val collected = pdf.debtPayments.single { it.payment.paymentId.value == payment.paymentId }
            assertEquals(600L, collected.payment.amount.minorUnits)
            assertEquals(payment.occurredAt, collected.payment.occurredAt.toEpochMilli())
            assertEquals(businessId, collected.businessId)
            assertEquals(saleId, collected.saleId)
            assertEquals(DEBTOR_NAME, collected.debtorName)
            assertEquals(1, pdf.sales.size)
            assertEquals(saleId, pdf.sales.single().saleId)
            assertEquals(
                1_000L,
                pdf.sales
                    .single()
                    .totalCharged.minorUnits,
            )
            assertEquals(DEBTOR_NAME, pdf.saleDebtorNames[saleId])
            assertTrue(pdf.debts.isEmpty())
        }
        val afterDebt = snapshot("debts")
        val afterPayments = snapshot("debt_payments")

        // Cerrar y reabrir la ventana: Reportes vuelve a abrir en Hoy con el cobro persistido.
        harness.restartApp(composeRule)
        waitForSalesWithActiveDraft()
        composeRule.onNode(reportTab).performClickOnUiThread(composeRule)
        waitForTag(ReportsTestTags.SCREEN)
        assertTodayAndVisiblePayment(payment.paymentId)
        assertEquals(afterDebt, snapshot("debts"))
        assertEquals(afterPayments, snapshot("debt_payments"))
        protectedTables.forEach { table -> assertEquals(before.getValue(table), snapshot(table)) }
        assertStock("8")
    }

    private suspend fun seedCreditWithPriorPayment() {
        businesses.create(Business(businessId, "Negocio pago directo QA", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH))
        val unitId = UnitId.from(UUID.randomUUID())
        units.create(UnitOfMeasure(unitId, businessId, "NIU", "Unidad", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH))
        locations.create(InventoryLocation(locationId, businessId, "Almacén QA", createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH))
        val product =
            Product(
                productId = productId,
                businessId = businessId,
                unitId = unitId,
                locationId = locationId,
                name = "Producto pago directo QA",
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
                    opened.saleId,
                    opened.version,
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
                CheckoutSaleCommand(saleId, saved.cart.version, saved.cart.contentHash, debtorName = DEBTOR_NAME),
            ),
        )
        val debt = requireNotNull(database.debtDao().findDebtForSale(saleId.value))
        seededDebtId = requireNotNull(DebtId.parse(debt.debtId))
        assertTrue(
            debts.recordPayment(
                businessId,
                RecordDebtPaymentCommand(
                    debtId,
                    debt.version,
                    Money.ofMinor(400L, currency),
                    DebtPaymentMethod.CASH,
                    occurredAt = Instant.now(),
                ),
            ) is RecordDebtPaymentResult.Recorded,
        )
    }

    private fun assertTodayAndVisiblePayment(paymentId: String) {
        scrollReportTo(ReportsTestTags.PERIOD_DAY)
        composeRule.onNodeWithTag(ReportsTestTags.PERIOD_DAY).assertIsSelected()
        val tag = ReportsTestTags.debtPayment(paymentId)
        scrollReportTo(tag)
        composeRule.onNodeWithTag(tag).assertIsDisplayed()
        composeRule.onNode(hasText(DEBTOR_NAME, substring = true) and hasAnyAncestor(hasTestTag(tag))).assertIsDisplayed()
        composeRule
            .onNode(
                hasText(Money.ofMinor(600L, currency).formatForDisplay(), substring = true) and
                    hasAnyAncestor(hasTestTag(tag)),
            ).assertIsDisplayed()
    }

    private fun scrollReportTo(tag: String) {
        composeRule.waitUntil(15_000L) {
            runCatching {
                composeRule.onNodeWithTag(ReportsTestTags.SCREEN).performScrollToNode(hasTestTag(tag))
                composeRule.onNodeWithTag(tag).assertIsDisplayed()
            }.isSuccess
        }
    }

    private fun clickTag(tag: String) {
        // La pestaña Deudores de Reportes se habilita cuando el reporte identifica el negocio.
        composeRule.waitUntil(15_000L) {
            runCatching { composeRule.onNodeWithTag(tag).assertIsDisplayed().assertIsEnabled() }.isSuccess
        }
        composeRule.onNodeWithTag(tag).performClickOnUiThread(composeRule)
    }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(15_000L) { runCatching { composeRule.onNodeWithTag(tag).assertIsDisplayed() }.isSuccess }
    }

    private fun assertStock(expected: String) =
        runBlocking {
            val balance = requireNotNull(database.inventoryDao().findBalance(businessId.value, productId.value, locationId.value))
            assertEquals(0, BigDecimal(expected).compareTo(BigDecimal(balance.quantityOnHand)))
        }

    private fun snapshot(table: String): List<List<String?>> = database.snapshotTable(table)

    private companion object {
        const val DEBTOR_NAME = "Cliente pago directo QA"
    }
}
