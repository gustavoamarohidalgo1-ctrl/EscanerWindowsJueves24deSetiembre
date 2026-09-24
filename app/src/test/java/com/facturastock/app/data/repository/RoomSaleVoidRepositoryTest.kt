package com.facturastock.app.data.repository

import java.io.File
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import com.facturastock.app.data.local.openNow
import com.facturastock.app.data.local.writableSql
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.entity.BusinessEntity
import com.facturastock.app.data.local.entity.CloudBusinessBindingEntity
import com.facturastock.app.data.local.entity.InventoryBalanceEntity
import com.facturastock.app.data.local.entity.InventoryLocationEntity
import com.facturastock.app.data.local.entity.ProductEntity
import com.facturastock.app.data.local.entity.UnitEntity
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.DebtPaymentMethod
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
import com.facturastock.app.domain.repository.SaleVoidPreview
import com.facturastock.app.domain.repository.SaleVoidPreviewResult
import com.facturastock.app.domain.repository.SaleVoidResult
import com.facturastock.app.domain.repository.SaveSaleCartLineCommand
import com.facturastock.app.domain.usecase.VoidSaleUseCase
import com.facturastock.app.domain.usecase.currentSalesReportRange
import com.facturastock.app.testing.FakeAppConfigurationRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class RoomSaleVoidRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var database: FacturaStockDatabase
    private lateinit var sales: RoomSaleRepository
    private lateinit var repository: RoomSaleVoidRepository
    private var currentConfiguration = AppConfiguration.defaults().copy(businessId = BUSINESS)
    private var actor: PurchaseOverrideActor? = OWNER
    private var actorHook: (() -> Unit)? = null
    private val ids = AtomicLong(100L)
    private val configuration =
        object : AppConfigurationRepository by FakeAppConfigurationRepository() {
            override suspend fun current() = currentConfiguration
        }
    private val authorization =
        object : PurchaseOverrideAuthorizationRepository {
            override suspend fun currentActor(businessId: BusinessId): PurchaseOverrideActor? {
                actorHook?.invoke()
                return actor
            }
        }

    @Before
    fun setUp() =
        runBlocking {
            openDatabase()
            seed()
        }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun cashSaleReturnsOriginalStockOnceAndPreservesPostedHistoryAcrossRestart() =
        runBlocking {
            database.close()
            openDatabase(RESTART_DATABASE)
            seed()
            val saleId = postSale()
            val graph = database.saleDao().findWithLines(saleId.value)
            val original = database.inventoryDao().listMovementsForSale(BUSINESS.value, saleId.value).single()
            val preview = ready(saleId)
            assertEquals(1_000L, preview.refundAmount.minorUnits)
            assertNull(preview.debtBalanceToCancel)
            assertEquals("Producto", preview.lines.single().productName)
            assertEquals(SaleVoidResult.Voided, repository.confirm(preview))
            assertDecimal("5", balance().quantityOnHand)
            assertEquals(graph, database.saleDao().findWithLines(saleId.value))
            val movements = database.inventoryDao().listMovementsForSale(BUSINESS.value, saleId.value)
            assertEquals(original, movements.single { it.type == "SALE" })
            val returned = movements.single { it.type == "SALE_VOID" }
            assertEquals(original.unitCost, returned.unitCost)
            assertEquals(original.quantityDelta.removePrefix("-"), returned.quantityDelta)
            assertTrue(returned.occurredAt > original.occurredAt)
            assertEquals(1L, scalar("SELECT COUNT(*) FROM audit_events WHERE eventType = 'SALE_VOIDED'"))
            assertTrue(sales.observeRecentPosted(BUSINESS, 10).first().isEmpty())
            assertTrue(sales.observePostedProfits(BUSINESS, Instant.EPOCH, Instant.ofEpochMilli(NOW + 1000L)).first().isEmpty())
            database.close()
            openDatabase(RESTART_DATABASE)
            assertEquals(SaleVoidResult.AlreadyVoided, repository.confirm(preview))
            assertEquals(SaleVoidPreviewResult.AlreadyVoided, repository.preview(BUSINESS, saleId))
            assertDecimal("5", balance().quantityOnHand)
            assertEquals(2, database.inventoryDao().listMovementsForSale(BUSINESS.value, saleId.value).size)
            assertEquals(
                "ok",
                database.writableSql.query("PRAGMA integrity_check").use {
                    it.moveToFirst()
                    it.getString(0)
                },
            )
        }

    @Test
    fun concurrentConfirmationsFromSeparateRepositoriesCommitOnlyOneReceipt() =
        runBlocking {
            val saleId = postSale()
            val preview = ready(saleId)
            val results =
                listOf(repository, newRepository())
                    .map { repo ->
                        async(Dispatchers.Default) { repo.confirm(preview) }
                    }.awaitAll()
            assertEquals(1, results.count { it == SaleVoidResult.Voided })
            assertEquals(1, results.count { it == SaleVoidResult.AlreadyVoided })
            assertDecimal("5", balance().quantityOnHand)
            assertEquals(1L, scalar("SELECT COUNT(*) FROM sale_voids"))
            assertEquals(1L, scalar("SELECT COUNT(*) FROM stock_movements WHERE type = 'SALE_VOID'"))
        }

    @Test
    fun finalReceiptFailureRollsBackEveryLineBalanceMovementAndAuditThenAllowsRetry() =
        runBlocking {
            val saleId = postSale(secondLocation = true)
            val preview = ready(saleId)
            val firstBefore = balance()
            val secondBefore = balance(LOCATION_2)
            database.writableSql.execSQL(
                "CREATE TRIGGER fail_sale_void_test BEFORE INSERT ON sale_voids " +
                    "BEGIN SELECT RAISE(ABORT, 'synthetic receipt failure'); END",
            )
            assertEquals(SaleVoidResult.InvalidHistory, repository.confirm(preview))
            assertEquals(firstBefore, balance())
            assertEquals(secondBefore, balance(LOCATION_2))
            assertEquals(0L, scalar("SELECT COUNT(*) FROM sale_voids"))
            assertEquals(0L, scalar("SELECT COUNT(*) FROM stock_movements WHERE type = 'SALE_VOID'"))
            assertEquals(0L, scalar("SELECT COUNT(*) FROM audit_events WHERE eventType = 'SALE_VOIDED'"))
            database.writableSql.execSQL("DROP TRIGGER fail_sale_void_test")
            assertEquals(SaleVoidResult.Voided, repository.confirm(preview))
            assertDecimal("5", balance().quantityOnHand)
            assertDecimal("5", balance(LOCATION_2).quantityOnHand)
        }

    @Test
    fun laterStockCostMakesPreviewStaleAndReturnUsesHistoricalCostWeightedWithCurrentStock() =
        runBlocking {
            val saleId = postSale()
            val oldPreview = ready(saleId)
            val before = balance()
            assertEquals(
                1,
                database.inventoryDao().updateBalanceIfVersion(
                    BUSINESS.value,
                    PRODUCT.value,
                    LOCATION.value,
                    before.version,
                    "8",
                    "7.5",
                    "PEN",
                    NOW + 500L,
                ),
            )
            assertEquals(SaleVoidResult.Stale, repository.confirm(oldPreview))
            assertDecimal("8", balance().quantityOnHand)
            val fresh = ready(saleId)
            assertNotEquals(oldPreview.impactHash, fresh.impactHash)
            assertEquals(SaleVoidResult.Voided, repository.confirm(fresh))
            assertDecimal("10", balance().quantityOnHand)
            assertDecimal("6.5", balance().averageUnitCost)
            val returned =
                database
                    .inventoryDao()
                    .listMovementsForSale(BUSINESS.value, saleId.value)
                    .single { it.type == "SALE_VOID" }
            assertDecimal("2.5", requireNotNull(returned.unitCost))
            assertTrue(returned.occurredAt > NOW + 500L)
        }

    @Test
    fun creditPaymentInvalidatesPreviewRefundsOnlyCollectedMoneyAndPreservesRawDebtHistory() =
        runBlocking {
            val saleId = postSale(credit = true)
            val unpaidPreview = ready(saleId)
            assertEquals(0L, unpaidPreview.refundAmount.minorUnits)
            assertEquals(1_000L, unpaidPreview.debtBalanceToCancel?.minorUnits)
            val debt = requireNotNull(database.debtDao().findDebtForSale(saleId.value))
            val debts = RoomDebtRepository(database, DISPATCHERS)
            assertTrue(debts.recordPayment(BUSINESS, payment(debt.debtId, 1L, 400L)) is RecordDebtPaymentResult.Recorded)
            val originalDebt = database.debtDao().findDebt(debt.debtId)
            val originalPayments = database.debtDao().findPaymentsForDebt(debt.debtId)
            assertEquals(SaleVoidResult.Stale, repository.confirm(unpaidPreview))
            val preview = ready(saleId)
            assertEquals(400L, preview.refundAmount.minorUnits)
            assertEquals(600L, preview.debtBalanceToCancel?.minorUnits)
            assertEquals(SaleVoidResult.Voided, repository.confirm(preview))
            assertEquals(originalDebt, database.debtDao().findDebt(debt.debtId))
            assertEquals(originalPayments, database.debtDao().findPaymentsForDebt(debt.debtId))
            assertTrue(debts.observeAll(BUSINESS).first().isEmpty())
            assertTrue(debts.observeOpen(BUSINESS).first().isEmpty())
            assertNull(debts.observeDetail(BUSINESS, requireNotNull(DebtId.parse(debt.debtId))).first())
            assertEquals(RecordDebtPaymentResult.NotFound, debts.recordPayment(BUSINESS, payment(debt.debtId, 2L, 100L)))
            assertEquals(originalPayments, database.debtDao().findPaymentsForDebt(debt.debtId))
        }

    @Test
    fun deletingMistakenDebtThroughSaleVoidUpdatesBothPdfsAndPreservesOtherDebtAndPaidHistory() =
        runBlocking {
            // Construye ambos créditos mediante checkout real, con todos los triggers activos.
            val mistakenSaleId = postSale(credit = true)
            val otherSaleId = postSale(credit = true)
            val debtDao = database.debtDao()
            val mistakenDebt = requireNotNull(debtDao.findDebtForSale(mistakenSaleId.value))
            val otherDebt = requireNotNull(debtDao.findDebtForSale(otherSaleId.value))
            val debts = RoomDebtRepository(database, DISPATCHERS)
            assertTrue(
                debts.recordPayment(BUSINESS, payment(mistakenDebt.debtId, 1L, 400L)) is RecordDebtPaymentResult.Recorded,
            )
            val debtId = requireNotNull(DebtId.parse(mistakenDebt.debtId))
            val otherDebtId = requireNotNull(DebtId.parse(otherDebt.debtId))
            val paidDebtBefore = requireNotNull(debtDao.findDebt(mistakenDebt.debtId))
            val paymentsBefore = debtDao.findPaymentsForDebt(mistakenDebt.debtId)
            val mistakenSaleBefore = database.saleDao().findWithLines(mistakenSaleId.value)
            val originalMovement = database.inventoryDao().listMovementsForSale(BUSINESS.value, mistakenSaleId.value).single()
            val originalAudit = database.auditEventDao().listForEntity(BUSINESS.value, "SALE", mistakenSaleId.value)
            val otherSaleBefore = database.saleDao().findWithLines(otherSaleId.value)
            val otherMovementsBefore = database.inventoryDao().listMovementsForSale(BUSINESS.value, otherSaleId.value)
            val otherDetailBefore = requireNotNull(debts.observeDetail(BUSINESS, otherDebtId).first())
            assertDecimal("1", balance().quantityOnHand)

            val pdfs = RoomReportPdfRepository(database, DISPATCHERS)
            val generatedAt = Instant.ofEpochMilli(NOW + 1_000L)
            val range = currentSalesReportRange(SalesReportPeriod.DAY, generatedAt, currentConfiguration.zoneId)
            val beforePdf =
                requireNotNull(
                    pdfs.readSnapshot(BUSINESS, range, generatedAt, PEN, ReportPdfKind.DAILY_SALES_WITH_DEBTORS),
                )
            assertEquals(setOf(mistakenSaleId, otherSaleId), beforePdf.sales.map { it.saleId }.toSet())
            assertEquals(setOf(debtId, otherDebtId), beforePdf.debts.map { it.debtId }.toSet())

            // Es el mismo caso de uso empleado por «Eliminar deuda», sin borrar ni simular un pago.
            val voidSale = VoidSaleUseCase(configuration, repository)
            val preview = (voidSale.preview(BUSINESS, mistakenSaleId) as SaleVoidPreviewResult.Ready).preview
            assertEquals(1_000L, preview.total.minorUnits)
            assertEquals(400L, preview.refundAmount.minorUnits)
            assertEquals(600L, preview.debtBalanceToCancel?.minorUnits)
            assertEquals(SaleVoidResult.Voided, voidSale.confirm(preview))
            val balanceAfter = balance()
            assertDecimal("3", balanceAfter.quantityOnHand)
            val receipt = requireNotNull(database.saleVoidDao().findBySaleId(BUSINESS.value, mistakenSaleId.value))
            assertEquals(400L, receipt.refundedAmountMinorUnits)
            assertEquals(600L, receipt.cancelledDebtBalanceMinorUnits)
            assertEquals(SaleVoidResult.AlreadyVoided, voidSale.confirm(preview))
            assertEquals(balanceAfter, balance())
            assertEquals(receipt, database.saleVoidDao().findBySaleId(BUSINESS.value, mistakenSaleId.value))

            assertEquals(mistakenSaleBefore, database.saleDao().findWithLines(mistakenSaleId.value))
            assertEquals(paidDebtBefore, debtDao.findDebt(mistakenDebt.debtId))
            assertEquals(paymentsBefore, debtDao.findPaymentsForDebt(mistakenDebt.debtId))
            assertEquals(1, paymentsBefore.size)
            val movements = database.inventoryDao().listMovementsForSale(BUSINESS.value, mistakenSaleId.value)
            assertEquals(2, movements.size)
            assertEquals(originalMovement, movements.single { it.type == "SALE" })
            assertDecimal("2", movements.single { it.type == "SALE_VOID" }.quantityDelta)
            val auditAfter = database.auditEventDao().listForEntity(BUSINESS.value, "SALE", mistakenSaleId.value)
            assertEquals(originalAudit, auditAfter.filter { it.eventType != "SALE_VOIDED" })
            assertEquals(1, auditAfter.count { it.eventType == "SALE_VOIDED" })

            assertEquals(listOf(otherDebtId), debts.observeAll(BUSINESS).first().map { it.debtId })
            assertEquals(listOf(otherDebtId), debts.observeOpen(BUSINESS).first().map { it.debtId })
            assertNull(debts.observeDetail(BUSINESS, debtId).first())
            assertEquals(otherDetailBefore, debts.observeDetail(BUSINESS, otherDebtId).first())
            assertEquals(otherDebt, debtDao.findDebt(otherDebt.debtId))
            assertEquals(otherSaleBefore, database.saleDao().findWithLines(otherSaleId.value))
            assertEquals(otherMovementsBefore, database.inventoryDao().listMovementsForSale(BUSINESS.value, otherSaleId.value))
            assertNull(database.saleVoidDao().findBySaleId(BUSINESS.value, otherSaleId.value))

            val daily =
                requireNotNull(
                    pdfs.readSnapshot(BUSINESS, range, generatedAt, PEN, ReportPdfKind.DAILY_SALES_WITH_DEBTORS),
                )
            assertEquals(listOf(otherSaleId), daily.sales.map { it.saleId })
            assertEquals(listOf(otherDetailBefore.debt), daily.debts)
            assertEquals(mapOf(otherSaleId to otherDebt.debtorName), daily.saleDebtorNames)
            assertEquals(
                1_000L,
                daily.sales
                    .single()
                    .totalCharged.minorUnits,
            )
            assertEquals(
                1_000L,
                daily.debts
                    .single()
                    .balance.minorUnits,
            )
            val pending =
                requireNotNull(
                    pdfs.readSnapshot(BUSINESS, range, generatedAt, PEN, ReportPdfKind.DEBTORS),
                )
            assertEquals(daily.debts, pending.debts)
            assertTrue(pending.sales.isEmpty())
            assertTrue(pending.saleDebtorNames.isEmpty())
        }

    @Test
    fun fullyPaidCreditRefundsCollectedTotalWithoutReopeningHistoricalDebt() =
        runBlocking {
            val saleId = postSale(credit = true)
            val debt = requireNotNull(database.debtDao().findDebtForSale(saleId.value))
            val debts = RoomDebtRepository(database, DISPATCHERS)
            assertTrue(debts.recordPayment(BUSINESS, payment(debt.debtId, 1L, 1_000L)) is RecordDebtPaymentResult.Recorded)
            val paid = database.debtDao().findDebt(debt.debtId)
            val preview = ready(saleId)
            assertEquals(1_000L, preview.refundAmount.minorUnits)
            assertEquals(0L, preview.debtBalanceToCancel?.minorUnits)
            assertEquals(SaleVoidResult.Voided, repository.confirm(preview))
            assertEquals(paid, database.debtDao().findDebt(debt.debtId))
        }

    @Test
    fun changedBusinessOrRevokedActorCannotConfirmPreviouslyReviewedSale() =
        runBlocking {
            val saleId = postSale()
            val preview = ready(saleId)
            currentConfiguration = currentConfiguration.copy(businessId = OTHER_BUSINESS)
            assertEquals(SaleVoidResult.NoActiveBusiness, repository.confirm(preview))
            currentConfiguration = currentConfiguration.copy(businessId = BUSINESS)
            actor = PurchaseOverrideActor("operator", PurchaseOverrideRole.OPERATOR)
            assertEquals(SaleVoidPreviewResult.Unauthorized, repository.preview(BUSINESS, saleId))
            assertEquals(SaleVoidResult.Unauthorized, repository.confirm(preview))
            actor = PurchaseOverrideActor("manager", PurchaseOverrideRole.MANAGER)
            assertEquals(SaleVoidResult.Stale, repository.confirm(preview))
            assertEquals(SaleVoidResult.Voided, repository.confirm(ready(saleId)))
        }

    @Test
    fun businessChangedWhileAuthorizationSuspendsIsRejectedInsideTransaction() =
        runBlocking {
            val saleId = postSale()
            val preview = ready(saleId)
            actorHook = { currentConfiguration = currentConfiguration.copy(businessId = OTHER_BUSINESS) }
            assertEquals(SaleVoidResult.NoActiveBusiness, repository.confirm(preview))
            assertDecimal("3", balance().quantityOnHand)
            assertEquals(0L, scalar("SELECT COUNT(*) FROM sale_voids"))
        }

    @Test
    fun durableCloudBindingBlocksLocalVoidEvenWithLocalOwnerAuthorization() =
        runBlocking {
            val saleId = postSale()
            val preview = ready(saleId)
            database.cloudBusinessBindingDao().insert(
                CloudBusinessBindingEntity(BUSINESS.value, UUID(99L, 99L).toString(), NOW + 1L, 0),
            )
            assertEquals(SaleVoidPreviewResult.SharedBusinessUnsupported, repository.preview(BUSINESS, saleId))
            assertEquals(SaleVoidResult.SharedBusinessUnsupported, repository.confirm(preview))
            assertDecimal("3", balance().quantityOnHand)
        }

    @Test
    fun foreignSaleAndDraftAreNotVoidableAndAlteredPreviewCannotAuthorizeCommit() =
        runBlocking {
            val saleId = postSale()
            val preview = ready(saleId)
            database.businessDao().insert(BusinessEntity(OTHER_BUSINESS.value, "Otro", 1L, 1L))
            currentConfiguration = currentConfiguration.copy(businessId = OTHER_BUSINESS)
            assertEquals(SaleVoidPreviewResult.NotFound, repository.preview(OTHER_BUSINESS, saleId))
            currentConfiguration = currentConfiguration.copy(businessId = BUSINESS)
            val draft = sales.createOrResume(BUSINESS, PEN).cart
            assertEquals(SaleVoidPreviewResult.NotFound, repository.preview(BUSINESS, draft.saleId))
            assertEquals(
                SaleVoidResult.Stale,
                repository.confirm(
                    preview.copy(total = Money.ofMinor(1L, PEN), refundAmount = Money.ofMinor(1L, PEN)),
                ),
            )
            assertEquals(SaleVoidResult.Stale, repository.confirm(preview.copy(impactHash = "0".repeat(64))))
            assertDecimal("3", balance().quantityOnHand)
        }

    @Test
    fun fullyDiscountedCashSaleStillReturnsStockWithZeroRefund() =
        runBlocking {
            val saleId = postSale(fullDiscount = true)
            val preview = ready(saleId)
            assertEquals(0L, preview.total.minorUnits)
            assertEquals(0L, preview.refundAmount.minorUnits)
            assertEquals(SaleVoidResult.Voided, repository.confirm(preview))
            assertDecimal("5", balance().quantityOnHand)
        }

    @Test
    fun archivedCatalogStillReturnsStockToTheHistoricalProductAndLocation() =
        runBlocking {
            val saleId = postSale()
            assertEquals(1, database.productDao().setStatus(PRODUCT.value, "ARCHIVED", 1L, NOW + 10L))
            assertEquals(1, database.inventoryLocationDao().setStatus(LOCATION.value, "ARCHIVED", NOW + 10L))
            val preview = ready(saleId)
            assertEquals("Principal", preview.lines.single().locationName)
            assertEquals(SaleVoidResult.Voided, repository.confirm(preview))
            assertDecimal("5", balance().quantityOnHand)
            assertEquals("ARCHIVED", database.productDao().findById(PRODUCT.value)?.status)
        }

    @Test
    fun missingBalanceFailsClosedWithoutManufacturingInventoryHistory() =
        runBlocking {
            val saleId = postSale()
            val preview = ready(saleId)
            database.writableSql.execSQL(
                "DELETE FROM inventory_balances WHERE businessId = ? AND productId = ? AND locationId = ?",
                arrayOf(BUSINESS.value, PRODUCT.value, LOCATION.value),
            )
            assertEquals(SaleVoidPreviewResult.InvalidHistory, repository.preview(BUSINESS, saleId))
            assertEquals(SaleVoidResult.InvalidHistory, repository.confirm(preview))
            assertEquals(0L, scalar("SELECT COUNT(*) FROM sale_voids"))
            assertEquals(0L, scalar("SELECT COUNT(*) FROM stock_movements WHERE type = 'SALE_VOID'"))
        }

    private fun openDatabase(name: String? = null) {
        database =
            FacturaStockDatabase.buildAt(File(tempFolder.root, name ?: "sale-void-${UUID.randomUUID()}.db"))
        database.openNow()
        sales =
            RoomSaleRepository(
                database,
                AppClock { Instant.ofEpochMilli(NOW) },
                UuidGenerator { UUID(ids.incrementAndGet(), ids.get()) },
                DISPATCHERS,
            )
        repository = newRepository()
    }

    private fun newRepository() =
        RoomSaleVoidRepository(
            database,
            AppClock { Instant.ofEpochMilli(NOW) },
            DISPATCHERS,
            configuration,
            authorization,
        )

    private suspend fun seed() {
        database.businessDao().insert(BusinessEntity(BUSINESS.value, "Negocio", 1L, 1L))
        database.unitDao().insert(UnitEntity(UNIT, BUSINESS.value, "NIU", "Unidad", 1L, 1L))
        listOf(LOCATION, LOCATION_2).forEach { location ->
            database.inventoryLocationDao().insert(
                InventoryLocationEntity(
                    location.value,
                    BUSINESS.value,
                    if (location == LOCATION) "Principal" else "Reserva",
                    1L,
                    1L,
                ),
            )
        }
        database.productDao().insert(
            ProductEntity(
                PRODUCT.value,
                BUSINESS.value,
                UNIT,
                "Producto",
                1L,
                1L,
                locationId = LOCATION.value,
            ),
        )
        listOf(LOCATION, LOCATION_2).forEach { location ->
            database.inventoryDao().insertBalanceIfAbsent(
                InventoryBalanceEntity(
                    BUSINESS.value,
                    PRODUCT.value,
                    location.value,
                    "5.000",
                    "2.5000",
                    "PEN",
                    7L,
                    1L,
                ),
            )
        }
    }

    private suspend fun postSale(
        credit: Boolean = false,
        secondLocation: Boolean = false,
        fullDiscount: Boolean = false,
    ): SaleId {
        var cart = sales.createOrResume(BUSINESS, PEN).cart
        (if (secondLocation) listOf(LOCATION, LOCATION_2) else listOf(LOCATION)).forEach { location ->
            cart =
                (
                    sales.saveLine(
                        BUSINESS,
                        SaveSaleCartLineCommand(
                            cart.saleId,
                            cart.version,
                            productId = PRODUCT,
                            locationId = location,
                            quantity = Quantity.of("2.000"),
                            unitPrice = Money.ofMinor(500L, PEN),
                            discount = Money.ofMinor(if (fullDiscount) 1_000L else 0L, PEN),
                        ),
                    ) as SaleCartMutationResult.Saved
                ).cart
        }
        assertEquals(
            CheckoutSaleResult.Posted(cart.saleId),
            sales.checkout(
                BUSINESS,
                CheckoutSaleCommand(
                    cart.saleId,
                    cart.version,
                    cart.contentHash,
                    debtorName = "Cliente".takeIf { credit },
                ),
            ),
        )
        return cart.saleId
    }

    private suspend fun ready(saleId: SaleId): SaleVoidPreview = (repository.preview(BUSINESS, saleId) as SaleVoidPreviewResult.Ready).preview

    private suspend fun balance(location: LocationId = LOCATION) = requireNotNull(database.inventoryDao().findBalance(BUSINESS.value, PRODUCT.value, location.value))

    private fun payment(
        debtId: String,
        version: Long,
        amount: Long,
    ) = RecordDebtPaymentCommand(
        requireNotNull(DebtId.parse(debtId)),
        version,
        Money.ofMinor(amount, PEN),
        DebtPaymentMethod.CASH,
        occurredAt = Instant.ofEpochMilli(NOW + version * 100L),
    )

    private fun scalar(sql: String): Long =
        database.writableSql.query(sql).use {
            check(it.moveToFirst())
            it.getLong(0)
        }

    private fun assertDecimal(
        expected: String,
        actual: String,
    ) {
        assertEquals(0, BigDecimal(expected).compareTo(BigDecimal(actual)))
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val RESTART_DATABASE = "sale-void-restart-test.db"
        val BUSINESS = BusinessId.from(UUID(1L, 1L))
        val OTHER_BUSINESS = BusinessId.from(UUID(9L, 9L))
        val PRODUCT = ProductId.from(UUID(2L, 2L))
        val LOCATION = LocationId.from(UUID(3L, 3L))
        val LOCATION_2 = LocationId.from(UUID(4L, 4L))
        val UNIT = UUID(5L, 5L).toString()
        val PEN = CurrencyCode.of("PEN")
        val OWNER = PurchaseOverrideActor("owner", PurchaseOverrideRole.OWNER)
        val DISPATCHERS =
            object : DispatcherProvider {
                override val io: CoroutineDispatcher = Dispatchers.Unconfined
                override val default: CoroutineDispatcher = Dispatchers.Unconfined
                override val main: CoroutineDispatcher = Dispatchers.Unconfined
            }
    }
}
