package com.facturastock.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.entity.BusinessEntity
import com.facturastock.app.data.local.entity.InventoryBalanceEntity
import com.facturastock.app.data.local.entity.InventoryLocationEntity
import com.facturastock.app.data.local.entity.ProductEntity
import com.facturastock.app.data.local.entity.UnitEntity
import com.facturastock.app.data.local.postingPersistenceCallback
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.DebtPaymentMethod
import com.facturastock.app.domain.model.DebtPaymentReportItem
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.PurchaseOverrideActor
import com.facturastock.app.domain.model.PurchaseOverrideRole
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.ReportPdfKind
import com.facturastock.app.domain.model.SalesReportPeriod
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DebtId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.SaleId
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.CheckoutSaleCommand
import com.facturastock.app.domain.repository.CheckoutSaleResult
import com.facturastock.app.domain.repository.PurchaseOverrideAuthorizationRepository
import com.facturastock.app.domain.repository.RecordDebtPaymentCommand
import com.facturastock.app.domain.repository.RecordDebtPaymentResult
import com.facturastock.app.domain.repository.SaleCartMutationResult
import com.facturastock.app.domain.repository.SaleVoidPreviewResult
import com.facturastock.app.domain.repository.SaleVoidResult
import com.facturastock.app.domain.repository.SaveSaleCartLineCommand
import com.facturastock.app.domain.usecase.currentSalesReportRange
import com.facturastock.app.testing.TestAppConfigurationRepository
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Checkout y pagos reales sobre Room en memoria con invariantes SQL activas. */
@RunWith(AndroidJUnit4::class)
class RoomDebtPaymentReportTest {
    private lateinit var database: FacturaStockDatabase
    private lateinit var sales: RoomSaleRepository
    private lateinit var debts: RoomDebtRepository
    private lateinit var pdfs: RoomReportPdfRepository
    private var now = RANGE.startInclusive.minusSeconds(172_800)
    private val ids = AtomicLong(100L)
    private val clock = AppClock { now }
    private val configuration =
        object : AppConfigurationRepository by TestAppConfigurationRepository() {
            override suspend fun current() = AppConfiguration.defaults().copy(businessId = BUSINESS)
        }

    @Before
    fun setUp() =
        runBlocking {
            database =
                Room
                    .inMemoryDatabaseBuilder(
                        ApplicationProvider.getApplicationContext<Context>(),
                        FacturaStockDatabase::class.java,
                    ).addCallback(postingPersistenceCallback)
                    .build()
            sales = RoomSaleRepository(database, clock, UuidGenerator { UUID(10L, ids.incrementAndGet()) }, testDispatchers)
            debts = RoomDebtRepository(database, testDispatchers)
            pdfs = RoomReportPdfRepository(database, testDispatchers)
            seed(BUSINESS)
            seed(OTHER_BUSINESS)
        }

    @After
    fun tearDown() = database.close()

    @Test
    fun payingOldCreditAppearsOnPaymentDayWithoutAnotherSaleOrStockChangeAndVoidRemovesIt() =
        runBlocking {
            val saleId = postCredit()
            val debt = requireNotNull(database.debtDao().findDebtForSale(saleId.value))
            val debtId = requireNotNull(DebtId.parse(debt.debtId))
            val saleBefore = database.saleDao().findWithLines(saleId.value)
            val stockBefore = balance(BUSINESS, PEN)
            val movementsBefore = database.inventoryDao().listMovementsForSale(BUSINESS.value, saleId.value)
            assertTrue(record(debtId, 1L, 400L, RANGE.startInclusive.minusSeconds(60)) is RecordDebtPaymentResult.Recorded)
            val emissions = Channel<List<DebtPaymentReportItem>>(Channel.UNLIMITED)
            val collector =
                launch {
                    debts.observePaymentsInRange(BUSINESS, RANGE.startInclusive, RANGE.endExclusive).collect(emissions::send)
                }
            try {
                assertTrue(withTimeout(5_000L) { emissions.receive() }.isEmpty())
                val command = payment(debtId, 2L, 600L, NOW)
                val paid = debts.recordPayment(BUSINESS, command) as RecordDebtPaymentResult.Recorded
                val rows = withTimeout(5_000L) { emissions.receive() }
                assertEquals(1, rows.size)
                assertEquals(paid.payment, rows.single().payment)
                assertEquals(saleId, rows.single().saleId)
                assertEquals("Ana Torres", rows.single().debtorName)
                assertEquals(
                    600L,
                    rows
                        .single()
                        .payment.amount.minorUnits,
                )
                assertEquals(
                    0L,
                    rows
                        .single()
                        .payment.balanceAfter.minorUnits,
                )
                assertEquals(NOW, rows.single().payment.occurredAt)
                assertTrue(debts.recordPayment(BUSINESS, command) is RecordDebtPaymentResult.AlreadyRecorded)
                assertEquals(stockBefore, balance(BUSINESS, PEN))
                assertEquals(saleBefore, database.saleDao().findWithLines(saleId.value))
                assertEquals(movementsBefore, database.inventoryDao().listMovementsForSale(BUSINESS.value, saleId.value))
                assertEquals(2, database.debtDao().countPayments(debt.debtId))

                val daily = requireNotNull(pdfs.readSnapshot(BUSINESS, RANGE, NOW, PEN, ReportPdfKind.DAILY_SALES_WITH_DEBTORS))
                assertTrue(daily.sales.isEmpty())
                assertTrue(daily.debts.isEmpty())
                assertEquals(rows, daily.debtPayments)
                val pending = requireNotNull(pdfs.readSnapshot(BUSINESS, RANGE, NOW, PEN, ReportPdfKind.DEBTORS))
                assertTrue(pending.debtPayments.isEmpty())

                now = NOW.plusSeconds(1)
                val voids =
                    RoomSaleVoidRepository(
                        database,
                        clock,
                        testDispatchers,
                        configuration,
                        object : PurchaseOverrideAuthorizationRepository {
                            override suspend fun currentActor(businessId: BusinessId) = PurchaseOverrideActor("owner", PurchaseOverrideRole.OWNER)
                        },
                    )
                val preview = (voids.preview(BUSINESS, saleId) as SaleVoidPreviewResult.Ready).preview
                assertEquals(1_000L, preview.refundAmount.minorUnits)
                assertEquals(SaleVoidResult.Voided, voids.confirm(preview))
                assertTrue(withTimeout(5_000L) { emissions.receive() }.isEmpty())
                assertEquals(2, database.debtDao().countPayments(debt.debtId))
                assertTrue(requireNotNull(pdfs.readSnapshot(BUSINESS, RANGE, now, PEN, ReportPdfKind.DAILY_SALES_WITH_DEBTORS)).debtPayments.isEmpty())
            } finally {
                collector.cancelAndJoin()
                emissions.close()
            }
        }

    @Test
    fun receiptReadUsesHalfOpenPaymentRangeStableOrderAndTenantCurrencyIsolation() =
        runBlocking {
            val first = requireNotNull(database.debtDao().findDebtForSale(postCredit().value))
            val usd = requireNotNull(database.debtDao().findDebtForSale(postCredit(currency = USD).value))
            val tied = requireNotNull(database.debtDao().findDebtForSale(postCredit().value))
            val other = requireNotNull(database.debtDao().findDebtForSale(postCredit(OTHER_BUSINESS).value))
            val firstId = requireNotNull(DebtId.parse(first.debtId))
            val before = balance(BUSINESS, PEN)
            record(firstId, 1L, 100L, RANGE.startInclusive.minusMillis(1))
            val atStart = record(firstId, 2L, 200L, RANGE.startInclusive) as RecordDebtPaymentResult.Recorded
            val beforeEnd = record(firstId, 3L, 300L, RANGE.endExclusive.minusMillis(1)) as RecordDebtPaymentResult.Recorded
            record(firstId, 4L, 400L, RANGE.endExclusive)
            val inUsd = record(requireNotNull(DebtId.parse(usd.debtId)), 1L, 1_000L, NOW, currency = USD) as RecordDebtPaymentResult.Recorded
            val sameTime = record(requireNotNull(DebtId.parse(tied.debtId)), 1L, 1_000L, NOW) as RecordDebtPaymentResult.Recorded
            record(requireNotNull(DebtId.parse(other.debtId)), 1L, 1_000L, NOW, businessId = OTHER_BUSINESS)
            val rows = debts.observePaymentsInRange(BUSINESS, RANGE.startInclusive, RANGE.endExclusive).first()
            val expected =
                listOf(atStart.payment, beforeEnd.payment, inUsd.payment, sameTime.payment).sortedWith(
                    compareByDescending<com.facturastock.app.domain.model.DebtPayment> { it.occurredAt }.thenByDescending { it.paymentId.value },
                )
            assertEquals(expected, rows.map { it.payment })
            assertTrue(rows.all { it.businessId == BUSINESS })
            assertEquals(setOf(PEN, USD), rows.map { it.payment.amount.currency }.toSet())
            assertEquals(before, balance(BUSINESS, PEN))
            assertEquals(
                rows,
                database
                    .debtDao()
                    .listPaymentsInRange(
                        BUSINESS.value,
                        RANGE.startInclusive.toEpochMilli(),
                        RANGE.endExclusive.toEpochMilli(),
                    ).map { it.toDomain() },
            )
            assertEquals(1, debts.observePaymentsInRange(OTHER_BUSINESS, RANGE.startInclusive, RANGE.endExclusive).first().size)
            assertEquals(4, requireNotNull(pdfs.readSnapshot(BUSINESS, RANGE, NOW, PEN, ReportPdfKind.DAILY_SALES_WITH_DEBTORS)).debtPayments.size)
        }

    private suspend fun seed(businessId: BusinessId) {
        database.businessDao().insert(BusinessEntity(businessId.value, "Negocio", 1L, 1L))
        database.unitDao().insert(UnitEntity(unitId(businessId), businessId.value, "NIU", "Unidad", 1L, 1L))
        database.inventoryLocationDao().insert(InventoryLocationEntity(locationId(businessId).value, businessId.value, "Principal", 1L, 1L))
        listOf(PEN, USD).forEach { currency ->
            database.productDao().insert(ProductEntity(productId(businessId, currency).value, businessId.value, unitId(businessId), "Producto ${currency.value}", 1L, 1L))
            database.inventoryDao().insertBalanceIfAbsent(
                InventoryBalanceEntity(
                    businessId.value,
                    productId(businessId, currency).value,
                    locationId(businessId).value,
                    "100",
                    "2.50",
                    currency.value,
                    1L,
                    1L,
                ),
            )
        }
    }

    private suspend fun postCredit(
        businessId: BusinessId = BUSINESS,
        currency: CurrencyCode = PEN,
    ): SaleId {
        val cart = sales.createOrResume(businessId, currency).cart
        val saved =
            (
                sales.saveLine(
                    businessId,
                    SaveSaleCartLineCommand(
                        cart.saleId,
                        cart.version,
                        productId = productId(businessId, currency),
                        locationId = locationId(businessId),
                        quantity = Quantity.of("1"),
                        unitPrice = Money.ofMinor(1_000L, currency),
                    ),
                ) as SaleCartMutationResult.Saved
            ).cart
        assertEquals(
            CheckoutSaleResult.Posted(saved.saleId),
            sales.checkout(
                businessId,
                CheckoutSaleCommand(saved.saleId, saved.version, saved.contentHash, debtorName = "Ana Torres"),
            ),
        )
        return saved.saleId
    }

    private suspend fun record(
        debtId: DebtId,
        version: Long,
        amount: Long,
        at: Instant,
        currency: CurrencyCode = PEN,
        businessId: BusinessId = BUSINESS,
    ) = debts.recordPayment(businessId, payment(debtId, version, amount, at, currency)).also {
        assertTrue(it is RecordDebtPaymentResult.Recorded)
    }

    private fun payment(
        debtId: DebtId,
        version: Long,
        amount: Long,
        at: Instant,
        currency: CurrencyCode = PEN,
    ) = RecordDebtPaymentCommand(debtId, version, Money.ofMinor(amount, currency), DebtPaymentMethod.OTHER, occurredAt = at)

    private suspend fun balance(
        businessId: BusinessId,
        currency: CurrencyCode,
    ) = requireNotNull(database.inventoryDao().findBalance(businessId.value, productId(businessId, currency).value, locationId(businessId).value))

    private companion object {
        val BUSINESS = BusinessId.from(UUID(1L, 1L))
        val OTHER_BUSINESS = BusinessId.from(UUID(1L, 2L))
        val PEN = CurrencyCode.of("PEN")
        val USD = CurrencyCode.of("USD")
        val NOW: Instant = Instant.parse("2026-09-12T17:00:00Z")
        val RANGE = currentSalesReportRange(SalesReportPeriod.DAY, NOW, AppConfiguration.DEFAULT_ZONE_ID)

        fun unitId(businessId: BusinessId) = UUID(2L, businessId.value.takeLast(12).toLong(16)).toString()

        fun locationId(businessId: BusinessId) = LocationId.from(UUID(3L, businessId.value.takeLast(12).toLong(16)))

        fun productId(
            businessId: BusinessId,
            currency: CurrencyCode,
        ) = ProductId.from(
            UUID(
                if (currency == PEN) 4L else 5L,
                businessId.value.takeLast(12).toLong(16),
            ),
        )
    }
}
