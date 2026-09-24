package com.facturastock.app.data.repository

import com.facturastock.app.data.local.newFacturaStockDatabaseWithoutInvariants
import com.facturastock.app.data.local.RecordingSQLiteDriver
import java.io.File
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import androidx.room.RoomDatabase
import com.facturastock.app.data.local.withTransaction
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.entity.BusinessEntity
import com.facturastock.app.data.local.entity.DebtEntity
import com.facturastock.app.data.local.entity.InventoryLocationEntity
import com.facturastock.app.data.local.entity.ProductEntity
import com.facturastock.app.data.local.entity.SaleEntity
import com.facturastock.app.data.local.entity.SaleLineEntity
import com.facturastock.app.data.local.entity.SaleVoidEntity
import com.facturastock.app.data.local.entity.StockMovementEntity
import com.facturastock.app.data.local.entity.UnitEntity
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.DebtStatus
import com.facturastock.app.domain.model.RealizedProfitIssue
import com.facturastock.app.domain.model.ReportPdfKind
import com.facturastock.app.domain.model.SalesReportPeriod
import com.facturastock.app.domain.model.debtorNameSearchKey
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.SaleId
import com.facturastock.app.domain.usecase.currentSalesReportRange
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Datos sintéticos de lectura; no usa ni modifica la base instalada de la aplicación. */
class RoomReportPdfRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var database: FacturaStockDatabase
    private lateinit var repository: RoomReportPdfRepository
    private val queries = CopyOnWriteArrayList<RecordedQuery>()

    @Before
    fun setUp() =
        runBlocking {
            database =
                tempFolder.newFacturaStockDatabaseWithoutInvariants(
                    driver = RecordingSQLiteDriver { recorded ->
                        val sqlQuery = recorded.sql
                        val isReportRead =
                            sqlQuery.startsWith("SELECT") && (
                                sqlQuery.contains("FROM businesses") || sqlQuery.contains("FROM sales s") ||
                                    sqlQuery.contains("FROM debts d")
                            )
                        queries +=
                            RecordedQuery(
                                sqlQuery,
                                recorded.bindArgs,
                                if (isReportRead) recorded.inTransaction else false,
                            )
                    },
                )
            repository = RoomReportPdfRepository(database, testDispatchers)
            database.withTransaction {
                seedBusiness(BUSINESS, "Razón social", "Nombre comercial")
                seedBusiness(OTHER_BUSINESS, "Otro negocio")
            }
            queries.clear()
        }

    @After
    fun tearDown() = database.close()

    @Test
    fun dailyReportIncludesEveryPageAndHalfOpenDayWhileIsolatingBusinessAndVoids() =
        runBlocking {
            val start = RANGE.startInclusive.toEpochMilli()
            val end = RANGE.endExclusive.toEpochMilli()
            database.withTransaction {
                // 205 claves con el mismo timestamp obligan a desempatar por ID entre páginas.
                (1..205).forEach { seedSale(it, NOW.toEpochMilli()) }
                seedSale(501, start)
                seedSale(502, end - 1L, currency = "USD")
                seedSale(503, start - 1L)
                seedSale(504, end)
                seedSale(505, NOW.toEpochMilli(), businessId = OTHER_BUSINESS)
                seedSale(506, NOW.toEpochMilli(), draft = true)
                seedSale(507, NOW.toEpochMilli())
                seedVoid(507)
                seedKnownCost(1, "0.3125")
            }
            queries.clear()

            val snapshot = read()
            val expectedIds = listOf(502) + (205 downTo 1).toList() + 501
            assertEquals(expectedIds.map(::saleId), snapshot.sales.map { it.saleId })
            assertEquals(207, snapshot.sales.size)
            assertEquals("Nombre comercial", snapshot.businessName)
            assertEquals(setOf("PEN", "USD"), snapshot.sales.map { it.totalCharged.currency.value }.toSet())
            val known = snapshot.sales.single { it.saleId == saleId(1) }
            assertEquals(BigDecimal("0.3125"), known.historicalCost?.amount)
            assertEquals(BigDecimal("0.6875"), known.grossProfit?.amount)
            assertTrue(
                snapshot.sales
                    .single { it.saleId == saleId(2) }
                    .issues
                    .contains(RealizedProfitIssue.MISSING_HISTORICAL_COST),
            )
            assertEquals(3, queries.count { it.sql.startsWith("SELECT s.saleId, s.postedAt AS postedAt") })
            assertEquals(3, queries.count { it.sql.startsWith("SELECT s.saleId, s.totalMinorUnits") })
            val nameReads = queries.filter { it.sql.startsWith("SELECT d.saleId, d.debtorName") }
            assertEquals(3, nameReads.size)
            assertTrue(nameReads.all { it.arguments.size <= 101 })
            assertTrue(queries.filter { it.isReportRead }.all { it.inTransaction })
            assertFalse(queries.any { it.sql.startsWith("INSERT") || it.sql.startsWith("DELETE") || it.sql.startsWith("UPDATE sales") })
        }

    @Test
    fun pendingDebtorsSpanAllDatesAndPaidCreditsStillIdentifyDailySales() =
        runBlocking {
            val old = RANGE.startInclusive.minusSeconds(864_000).toEpochMilli()
            database.withTransaction {
                seedSale(1, NOW.toEpochMilli())
                seedSale(2, NOW.toEpochMilli())
                seedSale(3, old, currency = "USD")
                seedSale(4, old)
                seedSale(5, NOW.toEpochMilli())
                seedSale(6, NOW.toEpochMilli(), businessId = OTHER_BUSINESS)
                seedDebt(1, "Ana", balance = 40L)
                seedDebt(2, "Crédito pagado", paid = true)
                seedDebt(3, "Ana", currency = "USD", occurredAt = old, balance = 25L)
                seedDebt(4, "Antigua pagada", paid = true, occurredAt = old)
                seedDebt(5, "Anulada")
                seedVoid(5)
                seedDebt(6, "Otro negocio", businessId = OTHER_BUSINESS)
            }
            val snapshot = read()

            assertEquals(setOf(saleId(1), saleId(2)), snapshot.sales.map { it.saleId }.toSet())
            assertEquals(setOf(saleId(1), saleId(3)), snapshot.debts.map { it.saleId }.toSet())
            assertTrue(snapshot.debts.all { it.status == DebtStatus.OPEN && it.businessId == BUSINESS })
            assertEquals(listOf(40L, 25L), snapshot.debts.map { it.balance.minorUnits })
            assertEquals(listOf("PEN", "USD"), snapshot.debts.map { it.balance.currency.value })
            assertEquals(mapOf(saleId(1) to "Ana", saleId(2) to "Crédito pagado"), snapshot.saleDebtorNames)
            assertEquals(
                database
                    .debtDao()
                    .observeSummaries(BUSINESS.value, onlyOpen = true)
                    .first()
                    .map { it.toDomain() },
                snapshot.debts,
            )
        }

    @Test
    fun standaloneDebtorsSkipSaleHistoryAndPaidDebtHistoryButKeepOldBalances() =
        runBlocking {
            val old = RANGE.startInclusive.minusSeconds(864_000).toEpochMilli()
            database.withTransaction {
                (1..125).forEach { number ->
                    seedSale(number, old)
                    seedDebt(number, "Pendiente antiguo $number", occurredAt = old)
                }
                seedSale(126, NOW.toEpochMilli())
                seedDebt(126, "Pagado", paid = true)
            }
            queries.clear()

            val snapshot = read(ReportPdfKind.DEBTORS)
            assertTrue(snapshot.sales.isEmpty())
            assertTrue(snapshot.saleDebtorNames.isEmpty())
            assertEquals((125 downTo 1).map(::saleId), snapshot.debts.map { it.saleId })
            assertFalse(queries.any { it.sql.contains("FROM sales s") || it.sql.contains("stock_movements") })
            assertFalse(queries.any { it.sql.startsWith("SELECT d.saleId, d.debtorName") })
            val summaries = queries.filter { it.sql.contains("AS lineCount FROM debts") }
            assertEquals(1, summaries.size)
            assertEquals(2, summaries.single().arguments.size)
            assertTrue(
                summaries
                    .single()
                    .arguments
                    .drop(1)
                    .all { it.toString() == "1" },
            )
        }

    @Test
    fun absentOrArchivedBusinessReturnsNullAndEmptyActiveBusinessUsesLegalName() =
        runBlocking {
            assertNull(repository.readSnapshot(BusinessId.from(UUID(0L, 999L)), RANGE, NOW, PEN, ReportPdfKind.DEBTORS))
            val empty = requireNotNull(repository.readSnapshot(OTHER_BUSINESS, RANGE, NOW, PEN, ReportPdfKind.DEBTORS))
            assertEquals("Otro negocio", empty.businessName)
            assertTrue(empty.sales.isEmpty() && empty.debts.isEmpty())
            val business = requireNotNull(database.businessDao().findById(BUSINESS.value))
            database.businessDao().update(business.copy(status = "ARCHIVED"))
            assertNull(repository.readSnapshot(BUSINESS, RANGE, NOW, PEN, ReportPdfKind.DAILY_SALES_WITH_DEBTORS))
        }

    private suspend fun read(kind: ReportPdfKind = ReportPdfKind.DAILY_SALES_WITH_DEBTORS) = requireNotNull(repository.readSnapshot(BUSINESS, RANGE, NOW, PEN, kind))

    private suspend fun seedBusiness(
        businessId: BusinessId,
        name: String,
        tradeName: String? = null,
    ) {
        database.businessDao().insert(BusinessEntity(businessId.value, name, 1L, 1L, tradeName = tradeName))
        database.unitDao().insert(UnitEntity(unitId(businessId), businessId.value, "NIU", "Unidad", 1L, 1L))
        database.inventoryLocationDao().insert(InventoryLocationEntity(locationId(businessId), businessId.value, "Principal", 1L, 1L))
        database.productDao().insert(ProductEntity(productId(businessId), businessId.value, unitId(businessId), "Producto", 1L, 1L))
    }

    private suspend fun seedSale(
        number: Int,
        postedAt: Long,
        businessId: BusinessId = BUSINESS,
        currency: String = "PEN",
        draft: Boolean = false,
    ) {
        database.saleDao().insertSale(
            SaleEntity(
                saleId = saleId(number).value,
                businessId = businessId.value,
                status = if (draft) "DRAFT" else "POSTED",
                currencyCode = currency,
                subtotalMinorUnits = 100L,
                discountMinorUnits = 0L,
                taxMinorUnits = 0L,
                totalMinorUnits = 100L,
                contentHash = "a".repeat(64),
                draftSlot = if (draft) "${businessId.value}:$currency" else null,
                checkoutIdempotencyKey = if (draft) null else "pdf-fixture-sale-$number",
                version = 1L,
                createdAt = 1L,
                updatedAt = postedAt,
                postedAt = if (draft) null else postedAt,
            ),
        )
        database.saleDao().insertLine(
            SaleLineEntity(
                saleLineId = lineId(number),
                saleId = saleId(number).value,
                productId = productId(businessId),
                unitId = unitId(businessId),
                locationId = locationId(businessId),
                position = 0,
                productNameSnapshot = "Producto histórico",
                unitCodeSnapshot = "NIU",
                locationNameSnapshot = "Principal",
                quantity = "1",
                unitPriceMinorUnits = 100L,
                discountMinorUnits = 0L,
                taxMinorUnits = 0L,
                lineTotalMinorUnits = 100L,
                currencyCode = currency,
            ),
        )
    }

    private suspend fun seedDebt(
        saleNumber: Int,
        name: String,
        balance: Long = 100L,
        paid: Boolean = false,
        currency: String = "PEN",
        occurredAt: Long = NOW.toEpochMilli(),
        businessId: BusinessId = BUSINESS,
    ) {
        database.debtDao().insertDebt(
            DebtEntity(
                debtId = UUID(0L, 70_000L + saleNumber).toString(),
                businessId = businessId.value,
                saleId = saleId(saleNumber).value,
                debtorName = name,
                normalizedDebtorName = debtorNameSearchKey(name),
                currencyCode = currency,
                originalAmountMinorUnits = 100L,
                balanceMinorUnits = if (paid) 0L else balance,
                status = if (paid) "PAID" else "OPEN",
                version = 1L,
                createdAt = occurredAt,
                updatedAt = occurredAt,
                paidAt = if (paid) occurredAt else null,
            ),
        )
    }

    private suspend fun seedVoid(saleNumber: Int) {
        val debt = database.debtDao().findDebtForSale(saleId(saleNumber).value)
        database.saleVoidDao().insert(
            SaleVoidEntity(
                saleId(saleNumber).value,
                BUSINESS.value,
                "b".repeat(64),
                if (debt == null) 100L else 0L,
                debt?.balanceMinorUnits ?: 0L,
                "PEN",
                "fixture",
                "OWNER",
                NOW.toEpochMilli(),
            ),
        )
    }

    private suspend fun seedKnownCost(
        saleNumber: Int,
        cost: String,
    ) {
        database.inventoryDao().insertMovements(
            listOf(
                StockMovementEntity(
                    movementId = UUID(0L, 80_000L + saleNumber).toString(),
                    businessId = BUSINESS.value,
                    saleId = saleId(saleNumber).value,
                    saleLineId = lineId(saleNumber),
                    productId = productId(BUSINESS),
                    locationId = locationId(BUSINESS),
                    type = "SALE",
                    quantityDelta = "-1",
                    unitCost = cost,
                    currencyCode = "PEN",
                    idempotencyKey = "pdf-fixture-stock-$saleNumber",
                    occurredAt = NOW.toEpochMilli(),
                    createdAt = NOW.toEpochMilli(),
                ),
            ),
        )
    }

    private data class RecordedQuery(
        val sql: String,
        val arguments: List<Any?>,
        val inTransaction: Boolean,
    ) {
        val isReportRead: Boolean get() =
            sql.startsWith("SELECT") && (
                sql.contains("FROM businesses") || sql.contains("FROM sales s") || sql.contains("FROM debts d")
            )
    }

    private companion object {
        val BUSINESS = BusinessId.from(UUID(0L, 10_001L))
        val OTHER_BUSINESS = BusinessId.from(UUID(0L, 10_002L))
        val NOW: Instant = Instant.parse("2026-09-12T17:00:00Z")
        val RANGE = currentSalesReportRange(SalesReportPeriod.DAY, NOW, ZoneId.of("America/Lima"))
        val PEN = CurrencyCode.of("PEN")

        fun saleId(number: Int) = SaleId.from(UUID(0L, number.toLong()))

        fun lineId(number: Int) = UUID(0L, 60_000L + number).toString()

        fun unitId(businessId: BusinessId) = UUID(1L, businessId.value.takeLast(12).toLong(16)).toString()

        fun locationId(businessId: BusinessId) = UUID(2L, businessId.value.takeLast(12).toLong(16)).toString()

        fun productId(businessId: BusinessId) = UUID(3L, businessId.value.takeLast(12).toLong(16)).toString()
    }
}
