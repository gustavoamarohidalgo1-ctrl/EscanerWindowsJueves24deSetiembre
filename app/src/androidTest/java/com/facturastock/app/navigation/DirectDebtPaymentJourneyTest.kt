package com.facturastock.app.navigation

import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.WindowManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
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
import com.facturastock.app.core.coroutines.DefaultDispatcherProvider
import com.facturastock.app.core.input.KeyboardWedgeRouter
import com.facturastock.app.data.local.FacturaStockDatabase
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
import com.facturastock.app.domain.usecase.currentSalesReportRange
import com.facturastock.app.feature.debtors.DebtorsTestTags
import com.facturastock.app.feature.reports.ReportsTestTags
import com.facturastock.app.feature.sales.SalesTestTags
import com.facturastock.app.testing.TestAppConfigurationState
import com.facturastock.app.testing.completedGateConfiguration
import com.facturastock.app.ui.format.formatForDisplay
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

/** Pago directo con Activity y repositorios reales, siempre con datos sintéticos de emulador. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DirectDebtPaymentJourneyTest {
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
    private val configuration get() = TestAppConfigurationState.current.value
    private val businessId get() = requireNotNull(configuration.businessId)

    @Before
    fun setUp() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) {
            "Este recorrido cobra sólo deudas sintéticas de emulador; nunca se ejecuta en la tablet."
        }
        KeyboardWedgeRouter.deactivate()
        TestAppConfigurationState.current.value = completedGateConfiguration()
        context.deleteDatabase(FacturaStockDatabase.NAME)
        hiltRule.inject()
        runBlocking { seedCreditWithPriorPayment() }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        // Evidencia del fixture sintético tras el guard de emulador; producción conserva FLAG_SECURE.
        scenario.onActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        waitForTag(SalesTestTags.SCREEN)
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
    fun oneTapPaysOnlyRemainingBalanceAndOpensTodayWithPaymentInReportAndPdf() {
        val reportTab =
            hasText(context.getString(R.string.navigation_reports)) and
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        composeRule.onNode(reportTab).performClick()
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
        captureOptionalReport()
        val afterDebt = snapshot("debts")
        val afterPayments = snapshot("debt_payments")

        scenario.recreate()
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
        composeRule.onNodeWithTag(tag).performClick()
    }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(15_000L) { runCatching { composeRule.onNodeWithTag(tag).assertIsDisplayed() }.isSuccess }
    }

    private fun assertStock(expected: String) =
        runBlocking {
            val balance = requireNotNull(database.inventoryDao().findBalance(businessId.value, productId.value, locationId.value))
            assertEquals(0, BigDecimal(expected).compareTo(BigDecimal(balance.quantityOnHand)))
        }

    private fun snapshot(table: String): List<List<String?>> =
        database.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY rowid").use { cursor ->
            buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { cursor.getString(it) }) }
        }

    private fun captureOptionalReport() {
        composeRule.waitForIdle()
        runCatching {
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            if (bitmap == null) {
                Log.w("DirectDebtPaymentJourney", "Captura opcional del reporte no disponible")
                return@runCatching
            }
            try {
                val folder = File(context.filesDir, "direct-debt-payment-qa").apply { check(isDirectory || mkdirs()) }
                File(folder, "reportes-hoy.png").outputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                }
            } finally {
                bitmap.recycle()
            }
        }.onFailure { Log.w("DirectDebtPaymentJourney", "No se pudo guardar evidencia opcional", it) }
    }

    private companion object {
        const val DEBTOR_NAME = "Cliente pago directo QA"
    }
}
