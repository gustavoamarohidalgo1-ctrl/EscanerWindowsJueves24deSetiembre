package com.facturastock.app.data.repository

import com.facturastock.app.domain.repository.RemoteSaleSyncRepository
import com.facturastock.app.domain.repository.DisabledRemoteSaleSyncRepository
import com.facturastock.app.domain.repository.RemoteSalePostResult
import com.facturastock.app.domain.repository.AuthorizedLocalBalance
import com.facturastock.app.domain.model.SharedSaleDocument
import java.io.IOException
import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.dao.updateCas
import com.facturastock.app.data.local.entity.AuditEventEntity
import com.facturastock.app.data.local.entity.BusinessEntity
import com.facturastock.app.data.local.entity.InventoryBalanceEntity
import com.facturastock.app.data.local.entity.InventoryLocationEntity
import com.facturastock.app.data.local.entity.ProductEntity
import com.facturastock.app.data.local.entity.SaleLineEntity
import com.facturastock.app.data.local.entity.UnitEntity
import com.facturastock.app.data.local.postingPersistenceCallback
import com.facturastock.app.domain.model.AuditEventType
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.DebtPaymentMethod
import com.facturastock.app.domain.model.DebtStatus
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.SaleStatus
import com.facturastock.app.domain.model.StockMovementType
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DebtId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.SaleLineId
import com.facturastock.app.domain.repository.AuditPayloadPolicy
import com.facturastock.app.domain.repository.CheckoutSaleCommand
import com.facturastock.app.domain.repository.CheckoutSaleResult
import com.facturastock.app.domain.repository.RecordDebtPaymentCommand
import com.facturastock.app.domain.repository.RecordDebtPaymentResult
import com.facturastock.app.domain.repository.SaleCartMutationResult
import com.facturastock.app.domain.repository.SaleBarcodeRecoveryExpectation
import com.facturastock.app.domain.repository.SaveSaleCartLineCommand
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomSaleRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: FacturaStockDatabase
    private lateinit var repository: RoomSaleRepository
    private val ids = AtomicLong(100L)

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(RESTART_DATABASE_NAME)
        database = Room.inMemoryDatabaseBuilder(context, FacturaStockDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(postingPersistenceCallback)
            .build()
        database.openHelper.writableDatabase
        repository = saleRepository(database)
        seedCatalogAndStock()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(RESTART_DATABASE_NAME)
    }

    @Test
    fun lostCheckoutAckSurvivesRestartFreezesCartAndReplaysExactlyOnce() = runBlocking {
        database.close()
        database = openRestartDatabase()
        seedCatalogAndStock()
        var posted: SharedSaleDocument? = null
        var calls = 0
        val remote = object : RemoteSaleSyncRepository by DisabledRemoteSaleSyncRepository {
            override suspend fun postSale(
                localBusinessId: BusinessId,
                document: SharedSaleDocument,
            ): RemoteSalePostResult {
                calls += 1
                assertTrue(database.saleDao().findPendingCheckout(document.saleId.value) != null)
                if (posted == null) {
                    posted = document
                    throw IOException("Synthetic lost response after server commit")
                }
                assertEquals(posted, document)
                return RemoteSalePostResult.Authorized(
                    saleId = document.saleId, receiptId = "receipt", seq = 1L,
                    postedAt = Instant.ofEpochMilli(NOW),
                    balances = listOf(AuthorizedLocalBalance(PRODUCT, LOCATION, "3.000", "2.5000", "PEN", 8L,
                        Instant.ofEpochMilli(NOW))),
                )
            }
        }
        fun buildRepository() = RoomSaleRepository(database, AppClock { Instant.ofEpochMilli(NOW) },
            UuidGenerator { uuid(ids.incrementAndGet()) }, TEST_DISPATCHERS, remote)
        repository = buildRepository()
        val opened = repository.createOrResume(BUSINESS, PEN).cart
        val saved = repository.saveLine(BUSINESS, SaveSaleCartLineCommand(opened.saleId, opened.version,
            productId = PRODUCT, locationId = LOCATION, quantity = Quantity.of("2.000"),
            unitPrice = Money.ofMinor(500L, PEN))) as SaleCartMutationResult.Saved
        val command = CheckoutSaleCommand(saved.cart.saleId, saved.cart.version, saved.cart.contentHash,
            debtorName = "Cliente prueba", debtDueAt = Instant.ofEpochMilli(NOW + 1000L))
        assertEquals(CheckoutSaleResult.OnlineRequired, repository.checkout(BUSINESS, command))
        database.close()
        database = openRestartDatabase()
        repository = buildRepository()
        val restored = repository.createOrResume(BUSINESS, PEN).cart
        assertEquals("Cliente prueba", restored.pendingCheckout?.debtorName)
        assertEquals(SaleCartMutationResult.CheckoutPending, repository.saveLine(BUSINESS,
            SaveSaleCartLineCommand(restored.saleId, restored.version, restored.lines.single().saleLineId,
                PRODUCT, LOCATION, Quantity.of("3"), Money.ofMinor(500L, PEN))))
        assertEquals(SaleCartMutationResult.CheckoutPending, repository.removeLine(BUSINESS,
            restored.saleId, restored.lines.single().saleLineId, restored.version))
        assertEquals(CheckoutSaleResult.CartChanged, repository.checkout(BUSINESS,
            command.copy(debtorName = "Otra persona")))
        assertEquals(1, calls)
        val line = requireNotNull(database.saleDao().findLine(restored.lines.single().saleLineId.value))
        assertTrue(runCatching { database.saleDao().updateLineEntity(line.copy(quantity = "3.000")) }.isFailure)
        assertEquals(CheckoutSaleResult.Posted(restored.saleId), repository.checkout(BUSINESS, command))
        assertNull(database.saleDao().findPendingCheckout(restored.saleId.value))
        assertEquals(CheckoutSaleResult.AlreadyPosted(restored.saleId), repository.checkout(BUSINESS, command))
        assertEquals(2, calls)
        assertEquals(1, database.inventoryDao().listMovementsForSale(BUSINESS.value, restored.saleId.value).size)
        assertEquals("3.000", database.inventoryDao().findBalance(BUSINESS.value, PRODUCT.value, LOCATION.value)?.quantityOnHand)
        assertDatabaseIntegrity()
    }

    @Test
    fun lostCheckoutAckCanCompleteAfterCatalogIsArchivedWithoutChangingHistoricalLines() = runBlocking {
        database.close()
        database = openRestartDatabase()
        seedCatalogAndStock()
        var accepted: SharedSaleDocument? = null
        var calls = 0
        val remote = object : RemoteSaleSyncRepository by DisabledRemoteSaleSyncRepository {
            override suspend fun postSale(
                localBusinessId: BusinessId,
                document: SharedSaleDocument,
            ): RemoteSalePostResult {
                calls += 1
                if (accepted == null) {
                    accepted = document
                    throw IOException("Synthetic lost ACK before catalog archive")
                }
                assertEquals(accepted, document)
                return RemoteSalePostResult.Authorized(
                    saleId = document.saleId,
                    receiptId = "receipt-archived",
                    seq = 1L,
                    postedAt = Instant.ofEpochMilli(NOW),
                    balances = listOf(AuthorizedLocalBalance(
                        PRODUCT, LOCATION, "3.000", "2.5000", "PEN", 8L,
                        Instant.ofEpochMilli(NOW),
                    )),
                )
            }
        }
        fun buildRepository() = RoomSaleRepository(
            database, AppClock { Instant.ofEpochMilli(NOW) },
            UuidGenerator { uuid(ids.incrementAndGet()) }, TEST_DISPATCHERS, remote,
        )
        repository = buildRepository()
        val cart = repository.createOrResume(BUSINESS, PEN).cart
        val saved = repository.saveLine(BUSINESS, SaveSaleCartLineCommand(
            cart.saleId, cart.version, productId = PRODUCT, locationId = LOCATION,
            quantity = Quantity.of("2.000"), unitPrice = Money.ofMinor(500L, PEN),
        )) as SaleCartMutationResult.Saved
        val command = CheckoutSaleCommand(saved.cart.saleId, saved.cart.version, saved.cart.contentHash)
        val originalLines = requireNotNull(database.saleDao().findWithLines(cart.saleId.value)).lines
        assertEquals(CheckoutSaleResult.OnlineRequired, repository.checkout(BUSINESS, command))

        val product = requireNotNull(database.productDao().findById(PRODUCT.value))
        assertEquals(1, database.productDao().setStatus(PRODUCT.value, CatalogStatus.ARCHIVED.name, product.version, NOW + 1L))
        assertEquals(1, database.unitDao().setStatus(UNIT_ID, CatalogStatus.ARCHIVED.name, NOW + 1L))
        assertEquals(1, database.inventoryLocationDao().setStatus(LOCATION.value, CatalogStatus.ARCHIVED.name, NOW + 1L))
        database.close()
        database = openRestartDatabase()
        repository = buildRepository()

        assertEquals(CheckoutSaleResult.Posted(cart.saleId), repository.checkout(BUSINESS, command))
        assertEquals(CheckoutSaleResult.AlreadyPosted(cart.saleId), repository.checkout(BUSINESS, command))
        assertEquals(2, calls)
        assertEquals(originalLines, requireNotNull(database.saleDao().findWithLines(cart.saleId.value)).lines)
        assertNull(database.saleDao().findPendingCheckout(cart.saleId.value))
        assertEquals(1, database.inventoryDao().listMovementsForSale(BUSINESS.value, cart.saleId.value).size)
        assertEquals("3.000", database.inventoryDao().findBalance(BUSINESS.value, PRODUCT.value, LOCATION.value)?.quantityOnHand)
        assertEquals(CatalogStatus.ARCHIVED.name, database.productDao().findById(PRODUCT.value)?.status)
        assertEquals(CatalogStatus.ARCHIVED.name, database.unitDao().findById(UNIT_ID)?.status)
        assertEquals(CatalogStatus.ARCHIVED.name, database.inventoryLocationDao().findById(LOCATION.value)?.status)
        assertDatabaseIntegrity()
    }

    @Test
    fun localCheckoutCannotUsePendingIntentToSellArchivedCatalog() = runBlocking {
        val remote = object : RemoteSaleSyncRepository by DisabledRemoteSaleSyncRepository {
            override suspend fun postSale(localBusinessId: BusinessId, document: SharedSaleDocument): RemoteSalePostResult {
                val product = requireNotNull(database.productDao().findById(PRODUCT.value))
                database.productDao().setStatus(PRODUCT.value, CatalogStatus.ARCHIVED.name, product.version, NOW + 1L)
                return RemoteSalePostResult.NotRequired
            }
        }
        repository = RoomSaleRepository(database, AppClock { Instant.ofEpochMilli(NOW) },
            UuidGenerator { uuid(ids.incrementAndGet()) }, TEST_DISPATCHERS, remote)
        val cart = repository.createOrResume(BUSINESS, PEN).cart
        val saved = repository.saveLine(BUSINESS, SaveSaleCartLineCommand(
            cart.saleId, cart.version, productId = PRODUCT, locationId = LOCATION,
            quantity = Quantity.of("2"), unitPrice = Money.ofMinor(500L, PEN),
        )) as SaleCartMutationResult.Saved
        assertEquals(CheckoutSaleResult.ProductUnavailable, repository.checkout(BUSINESS,
            CheckoutSaleCommand(saved.cart.saleId, saved.cart.version, saved.cart.contentHash)))
        assertNull(database.saleDao().findPendingCheckout(cart.saleId.value))
        assertTrue(database.inventoryDao().listMovementsForSale(BUSINESS.value, cart.saleId.value).isEmpty())
        assertBalanceUnchanged()
    }

    @Test
    fun repeatedAndConcurrentOpenResumeSingleDraftSlot() = runBlocking {
        val opened = listOf(
            async(Dispatchers.Default) { repository.createOrResume(BUSINESS, PEN) },
            async(Dispatchers.Default) { repository.createOrResume(BUSINESS, PEN) },
        ).awaitAll()

        assertEquals(1, opened.count { it.created })
        assertEquals(1, opened.map { it.cart.saleId }.distinct().size)
        val resumed = repository.createOrResume(BUSINESS, PEN)
        assertFalse(resumed.created)
        assertEquals(opened.first().cart.saleId, resumed.cart.saleId)
        database.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM sales WHERE status = 'DRAFT'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
    }

    @Test
    fun archivedBusinessCannotResumeItsExistingDraft() = runBlocking {
        val opened = repository.createOrResume(BUSINESS, PEN)
        val business = requireNotNull(database.businessDao().findById(BUSINESS.value))
        assertEquals(
            1,
            database.businessDao().update(
                business.copy(status = CatalogStatus.ARCHIVED.name, updatedAt = 2L),
            ),
        )

        val failure = runCatching { repository.createOrResume(BUSINESS, PEN) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(
            opened.cart.saleId.value,
            database.saleDao().findActiveDraft(BUSINESS.value, PEN.value)?.sale?.saleId,
        )
    }

    @Test
    fun concurrentLineMutationReturnsStaleWithoutPartialWriteOrCrash() = runBlocking {
        val cart = repository.createOrResume(BUSINESS, PEN).cart
        val command = SaveSaleCartLineCommand(
            saleId = cart.saleId,
            expectedVersion = cart.version,
            productId = PRODUCT,
            locationId = LOCATION,
            quantity = Quantity.of("1"),
            unitPrice = Money.ofMinor(100L, PEN),
        )

        val results = listOf(
            async(Dispatchers.Default) { repository.saveLine(BUSINESS, command) },
            async(Dispatchers.Default) { repository.saveLine(BUSINESS, command) },
        ).awaitAll()

        assertEquals(1, results.count { it is SaleCartMutationResult.Saved })
        assertEquals(1, results.count { it == SaleCartMutationResult.Stale })
        val stored = requireNotNull(database.saleDao().findWithLines(cart.saleId.value))
        assertEquals(1L, stored.sale.version)
        assertEquals(1, stored.lines.size)
    }

    @Test
    fun recoveredBarcodeIsValidatedInsideTheWriteAndKeepsTheRegisteredCode() = runBlocking {
        val command = recoveryCommand()
        val productBefore = database.productDao().findById(PRODUCT.value)

        val saved = repository.saveLine(BUSINESS, command) as SaleCartMutationResult.Saved

        assertEquals(PRODUCT, saved.cart.lines.single().productId)
        assertEquals(RECOVERY_STORED, saved.cart.lines.single().barcode)
        assertEquals(command.expectedVersion + 1L, saved.cart.version)
        assertEquals(productBefore, database.productDao().findById(PRODUCT.value))
        assertBalanceUnchanged()
    }

    @Test
    fun newCompetingBarcodeWithoutStockInvalidatesAnEarlierRecovery() = runBlocking {
        val command = recoveryCommand()
        val competitor = insertRecoveryCompetitor()
        assertNull(database.inventoryDao().findBalance(BUSINESS.value, competitor.productId, LOCATION.value))

        assertRecoveryRejectedWithoutWrite(command)
    }

    @Test
    fun newArchivedCompetingBarcodeAlsoInvalidatesAnEarlierRecovery() = runBlocking {
        val command = recoveryCommand()
        insertRecoveryCompetitor(status = CatalogStatus.ARCHIVED)

        assertRecoveryRejectedWithoutWrite(command)
    }

    @Test
    fun changedCandidateBarcodeInvalidatesAnEarlierRecovery() = runBlocking {
        val command = recoveryCommand()
        val original = requireNotNull(database.productDao().findById(PRODUCT.value))
        assertEquals(1, database.productDao().updateCas(original.copy(barcode = RECOVERY_COMPETITOR, updatedAt = 3L)))

        assertRecoveryRejectedWithoutWrite(command)
    }

    @Test
    fun changedCandidateVersionInvalidatesRecoveryEvenWhenItsBarcodeIsUnchanged() = runBlocking {
        val command = recoveryCommand()
        val original = requireNotNull(database.productDao().findById(PRODUCT.value))
        assertEquals(1, database.productDao().updateCas(original.copy(name = "Producto actualizado", updatedAt = 3L)))
        assertEquals(RECOVERY_STORED, database.productDao().findById(PRODUCT.value)?.barcode)

        assertRecoveryRejectedWithoutWrite(command)
    }

    @Test
    fun newExactBarcodeInvalidatesAnEarlierRecovery() = runBlocking {
        val command = recoveryCommand()
        insertRecoveryCompetitor(barcode = RECOVERY_SCANNED)

        assertRecoveryRejectedWithoutWrite(command)
    }

    @Test
    fun newExactSkuInvalidatesAnEarlierRecovery() = runBlocking {
        val command = recoveryCommand()
        insertRecoveryCompetitor(barcode = null, sku = RECOVERY_SCANNED)

        assertRecoveryRejectedWithoutWrite(command)
    }

    @Test
    fun competingBarcodeInAnotherBusinessDoesNotBlockRecovery() = runBlocking {
        val command = recoveryCommand()
        val otherBusiness = BusinessId.from(uuid(ids.incrementAndGet()))
        val otherUnit = uuid(ids.incrementAndGet()).toString()
        database.businessDao().insert(BusinessEntity(otherBusiness.value, "Otro negocio", 1L, 1L))
        database.unitDao().insert(UnitEntity(otherUnit, otherBusiness.value, "NIU", "Unidad", 1L, 1L))
        database.productDao().insert(ProductEntity(
            productId = uuid(ids.incrementAndGet()).toString(),
            businessId = otherBusiness.value,
            unitId = otherUnit,
            name = "Competidor de otro negocio",
            barcode = RECOVERY_COMPETITOR,
            createdAt = 1L,
            updatedAt = 1L,
        ))

        val result = repository.saveLine(BUSINESS, command)

        assertTrue(result is SaleCartMutationResult.Saved)
        assertEquals(PRODUCT, (result as SaleCartMutationResult.Saved).cart.lines.single().productId)
    }

    @Test
    fun ordinaryLineSaveDoesNotAcquireBarcodeRecoveryRestrictions() = runBlocking {
        val command = recoveryCommand().copy(barcodeRecovery = null)
        insertRecoveryCompetitor()

        val result = repository.saveLine(BUSINESS, command)

        assertTrue(result is SaleCartMutationResult.Saved)
        assertEquals(PRODUCT, (result as SaleCartMutationResult.Saved).cart.lines.single().productId)
    }

    @Test
    fun invalidatedRecoveryCannotReplaceAnExistingLineOrAdvanceItsCartVersion() = runBlocking {
        val initial = recoveryCommand()
        val saved = repository.saveLine(BUSINESS, initial) as SaleCartMutationResult.Saved
        val replacement = initial.copy(
            expectedVersion = saved.cart.version,
            saleLineId = saved.cart.lines.single().saleLineId,
            quantity = Quantity.of("2"),
        )
        insertRecoveryCompetitor()

        assertRecoveryRejectedWithoutWrite(replacement)
    }

    @Test
    fun incompletePriceBlocksBeforeAnyStockOrLedgerWrite() = runBlocking {
        val cart = repository.createOrResume(BUSINESS, PEN).cart
        val saved = repository.saveLine(
            BUSINESS,
            SaveSaleCartLineCommand(
                saleId = cart.saleId,
                expectedVersion = cart.version,
                productId = PRODUCT,
                locationId = LOCATION,
                quantity = Quantity.of("2"),
            ),
        ) as SaleCartMutationResult.Saved

        val result = repository.checkout(
            BUSINESS,
            CheckoutSaleCommand(saved.cart.saleId, saved.cart.version, saved.cart.contentHash),
        )

        assertTrue(result is CheckoutSaleResult.IncompleteLine)
        val balance = requireNotNull(
            database.inventoryDao().findBalance(BUSINESS.value, PRODUCT.value, LOCATION.value),
        )
        assertEquals("5.000", balance.quantityOnHand)
        assertEquals(7L, balance.version)
        assertTrue(database.inventoryDao().listMovementsForSale(BUSINESS.value, cart.saleId.value).isEmpty())
        assertTrue(
            database.auditEventDao().listForEntity(BUSINESS.value, "SALE", cart.saleId.value).isEmpty(),
        )
        assertEquals(SaleStatus.DRAFT.name, database.saleDao().findSale(cart.saleId.value)?.status)
    }

    @Test
    fun checkoutIsAtomicIdempotentAndPreservesAverageCost() = runBlocking {
        val cart = repository.createOrResume(BUSINESS, PEN).cart
        val saved = repository.saveLine(
            BUSINESS,
            SaveSaleCartLineCommand(
                saleId = cart.saleId,
                expectedVersion = cart.version,
                productId = PRODUCT,
                locationId = LOCATION,
                quantity = Quantity.of("2.000"),
                unitPrice = Money.ofMinor(550L, PEN),
            ),
        ) as SaleCartMutationResult.Saved
        val command = CheckoutSaleCommand(
            saved.cart.saleId,
            saved.cart.version,
            saved.cart.contentHash,
        )

        assertEquals(CheckoutSaleResult.Posted(cart.saleId), repository.checkout(BUSINESS, command))
        assertEquals(
            CheckoutSaleResult.AlreadyPosted(cart.saleId),
            repository.checkout(BUSINESS, command),
        )

        val balance = requireNotNull(
            database.inventoryDao().findBalance(BUSINESS.value, PRODUCT.value, LOCATION.value),
        )
        assertEquals("3.000", balance.quantityOnHand)
        assertEquals("2.5000", balance.averageUnitCost)
        assertEquals(8L, balance.version)
        val movement = database.inventoryDao()
            .listMovementsForSale(BUSINESS.value, cart.saleId.value).single()
        assertEquals(StockMovementType.SALE.name, movement.type)
        assertEquals("-2.000", movement.quantityDelta)
        assertEquals("2.5000", movement.unitCost)
        assertEquals(cart.saleId.value, movement.saleId)
        val audit = database.auditEventDao()
            .listForEntity(BUSINESS.value, "SALE", cart.saleId.value).single()
        assertEquals(AuditEventType.SALE_POSTED.name, audit.eventType)
        assertNull(audit.purchaseId)
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM outbox_operations").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
        val recent = repository.observeRecentPosted(BUSINESS, 50).first().single()
        assertEquals(cart.saleId, recent.saleId)
        assertEquals(1, recent.lineCount)
        assertEquals(1_100L, recent.total.minorUnits)

        val next = repository.createOrResume(BUSINESS, PEN)
        assertTrue(next.created)
        assertNotEquals(cart.saleId, next.cart.saleId)
    }

    @Test
    fun creditCheckoutCreatesDebtAndPaymentsAreVersionedAndIdempotent() = runBlocking {
        val cart = repository.createOrResume(BUSINESS, PEN).cart
        val saved = repository.saveLine(
            BUSINESS,
            SaveSaleCartLineCommand(
                saleId = cart.saleId,
                expectedVersion = cart.version,
                productId = PRODUCT,
                locationId = LOCATION,
                quantity = Quantity.of("2.000"),
                unitPrice = Money.ofMinor(550L, PEN),
            ),
        ) as SaleCartMutationResult.Saved
        val dueAt = Instant.ofEpochMilli(NOW + 86_400_000L)
        val command = CheckoutSaleCommand(
            saleId = saved.cart.saleId,
            expectedVersion = saved.cart.version,
            expectedContentHash = saved.cart.contentHash,
            debtorName = "  Mar\u00eda   P\u00e9rez  ",
            debtDueAt = dueAt,
        )

        assertEquals(CheckoutSaleResult.Posted(cart.saleId), repository.checkout(BUSINESS, command))
        assertEquals(
            CheckoutSaleResult.AlreadyPosted(cart.saleId),
            repository.checkout(BUSINESS, command),
        )

        val debts = RoomDebtRepository(database, TEST_DISPATCHERS)
        val debt = debts.observeOpen(BUSINESS).first().single()
        assertEquals(DebtId.from(SaleContentIdentity.uuid("sale-debt", cart.saleId.value)), debt.debtId)
        assertEquals("Mar\u00eda P\u00e9rez", debt.debtorName)
        assertEquals(1_100L, debt.originalAmount.minorUnits)
        assertEquals(1_100L, debt.balance.minorUnits)
        assertEquals(1, debt.lineCount)
        assertEquals(dueAt, debt.dueAt)
        assertEquals(1L, debt.version)

        val firstPayment = RecordDebtPaymentCommand(
            debtId = debt.debtId,
            expectedVersion = debt.version,
            amount = Money.ofMinor(400L, PEN),
            method = DebtPaymentMethod.YAPE,
            note = "  Primer abono  ",
            reference = " OP-1 ",
            occurredAt = Instant.ofEpochMilli(NOW + 100L),
        )
        val recorded = debts.recordPayment(BUSINESS, firstPayment)
        assertTrue(recorded is RecordDebtPaymentResult.Recorded)
        recorded as RecordDebtPaymentResult.Recorded
        assertEquals(700L, recorded.debt.balance.minorUnits)
        assertEquals(2L, recorded.debt.version)
        assertEquals("Primer abono", recorded.payment.note)
        assertEquals("OP-1", recorded.payment.reference)

        val retriedAfterRestart = firstPayment.copy(occurredAt = Instant.ofEpochMilli(NOW + 999L))
        val duplicate = debts.recordPayment(BUSINESS, retriedAfterRestart)
        assertTrue(duplicate is RecordDebtPaymentResult.AlreadyRecorded)
        duplicate as RecordDebtPaymentResult.AlreadyRecorded
        assertEquals(recorded.payment.paymentId, duplicate.payment.paymentId)
        assertEquals(1, database.debtDao().countPayments(debt.debtId.value))

        val paid = debts.recordPayment(
            BUSINESS,
            RecordDebtPaymentCommand(
                debtId = debt.debtId,
                expectedVersion = 2L,
                amount = Money.ofMinor(700L, PEN),
                method = DebtPaymentMethod.CASH,
                occurredAt = Instant.ofEpochMilli(NOW + 200L),
            ),
        )
        assertTrue(paid is RecordDebtPaymentResult.Recorded)
        paid as RecordDebtPaymentResult.Recorded
        assertEquals(DebtStatus.PAID, paid.debt.status)
        assertEquals(0L, paid.debt.balance.minorUnits)
        assertEquals(3L, paid.debt.version)
        assertTrue(debts.observeOpen(BUSINESS).first().isEmpty())
        assertEquals(DebtStatus.PAID, debts.observeAll(BUSINESS).first().single().status)
        val detail = requireNotNull(debts.observeDetail(BUSINESS, debt.debtId).first())
        assertEquals(1, detail.lines.size)
        assertEquals("Producto", detail.lines.single().productName)
        assertEquals(2, detail.payments.size)
    }

    @Test
    fun postedProfitUsesFrozenMovementCostAndSemiOpenInterval() = runBlocking {
        val cart = repository.createOrResume(BUSINESS, PEN).cart
        val saved = repository.saveLine(
            BUSINESS,
            SaveSaleCartLineCommand(
                saleId = cart.saleId,
                expectedVersion = cart.version,
                productId = PRODUCT,
                locationId = LOCATION,
                quantity = Quantity.of("2.000"),
                unitPrice = Money.ofMinor(550L, PEN),
                discount = Money.ofMinor(100L, PEN),
                tax = Money.ofMinor(180L, PEN),
            ),
        ) as SaleCartMutationResult.Saved
        assertEquals(
            CheckoutSaleResult.Posted(cart.saleId),
            repository.checkout(
                BUSINESS,
                CheckoutSaleCommand(saved.cart.saleId, saved.cart.version, saved.cart.contentHash),
            ),
        )

        val included = repository.observePostedProfits(
            BUSINESS,
            Instant.ofEpochMilli(NOW),
            Instant.ofEpochMilli(NOW + 1L),
        ).first().single()

        assertEquals(1_180L, included.totalCharged.minorUnits)
        assertEquals(1_000L, included.netRevenue.minorUnits)
        assertEquals(BigDecimal("5.0000000"), included.historicalCost?.amount)
        assertEquals(BigDecimal("5.0000000"), included.grossProfit?.amount)
        assertTrue(included.issues.isEmpty())
        assertEquals(Instant.ofEpochMilli(NOW), included.postedAt)
        val line = included.lines.single()
        assertEquals(PRODUCT, line.productId)
        assertEquals(0, line.position)
        assertEquals("Producto", line.productName)
        assertEquals("NIU", line.unitCode)
        assertEquals("Principal", line.locationName)
        assertEquals(Quantity.of("2.000"), line.quantity)
        assertEquals(1_180L, line.totalCharged.minorUnits)
        assertEquals(1_000L, line.netRevenue.minorUnits)
        assertEquals(BigDecimal("5.0000000"), line.historicalCost?.amount)
        assertEquals(BigDecimal("5.0000000"), line.grossProfit?.amount)
        assertTrue(line.issues.isEmpty())
        assertTrue(
            repository.observePostedProfits(
                BUSINESS,
                Instant.EPOCH,
                Instant.ofEpochMilli(NOW),
            ).first().isEmpty(),
        )
        assertTrue(
            repository.observePostedProfits(
                BUSINESS,
                Instant.ofEpochMilli(NOW + 1L),
                Instant.ofEpochMilli(NOW + 2L),
            ).first().isEmpty(),
        )
    }

    @Test
    fun postedSaleAndIdempotentRetrySurviveWalCheckpointAndDatabaseReopen() = runBlocking {
        database.close()
        database = openRestartDatabase()
        repository = saleRepository(database)
        seedCatalogAndStock()

        val cart = repository.createOrResume(BUSINESS, PEN).cart
        val saved = repository.saveLine(
            BUSINESS,
            SaveSaleCartLineCommand(
                saleId = cart.saleId,
                expectedVersion = cart.version,
                productId = PRODUCT,
                locationId = LOCATION,
                quantity = Quantity.of("2.000"),
                unitPrice = Money.ofMinor(550L, PEN),
            ),
        ) as SaleCartMutationResult.Saved
        val command = CheckoutSaleCommand(
            saleId = saved.cart.saleId,
            expectedVersion = saved.cart.version,
            expectedContentHash = saved.cart.contentHash,
        )

        assertEquals(CheckoutSaleResult.Posted(cart.saleId), repository.checkout(BUSINESS, command))
        assertWalCheckpointCompletes()
        assertDatabaseIntegrity()

        database.close()
        database = openRestartDatabase()
        repository = saleRepository(database)

        assertEquals(
            CheckoutSaleResult.AlreadyPosted(cart.saleId),
            repository.checkout(BUSINESS, command),
        )
        val balance = requireNotNull(
            database.inventoryDao().findBalance(BUSINESS.value, PRODUCT.value, LOCATION.value),
        )
        assertEquals("3.000", balance.quantityOnHand)
        assertEquals(8L, balance.version)
        assertEquals(
            1,
            database.inventoryDao().listMovementsForSale(BUSINESS.value, cart.saleId.value).size,
        )
        assertEquals(
            1,
            database.auditEventDao().listForEntity(BUSINESS.value, "SALE", cart.saleId.value).size,
        )
        database.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM sale_lines WHERE saleId = ?",
            arrayOf(cart.saleId.value),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        assertDatabaseIntegrity()
    }

    @Test
    fun checkoutAcrossMultipleWarehousesUpdatesEveryBalanceAndMovementExactly() = runBlocking {
        seedLocationAndStock(
            locationId = LOCATION_2,
            name = "Secundario",
            quantity = "4.500",
            averageUnitCost = "3.7500",
            version = 3L,
        )
        val opened = repository.createOrResume(BUSINESS, PEN).cart
        val first = repository.saveLine(
            BUSINESS,
            SaveSaleCartLineCommand(
                saleId = opened.saleId,
                expectedVersion = opened.version,
                productId = PRODUCT,
                locationId = LOCATION,
                quantity = Quantity.of("2.000"),
                unitPrice = Money.ofMinor(550L, PEN),
            ),
        ) as SaleCartMutationResult.Saved
        val second = repository.saveLine(
            BUSINESS,
            SaveSaleCartLineCommand(
                saleId = first.cart.saleId,
                expectedVersion = first.cart.version,
                productId = PRODUCT,
                locationId = LOCATION_2,
                quantity = Quantity.of("1.500"),
                unitPrice = Money.ofMinor(200L, PEN),
            ),
        ) as SaleCartMutationResult.Saved

        assertEquals(
            CheckoutSaleResult.Posted(opened.saleId),
            repository.checkout(
                BUSINESS,
                CheckoutSaleCommand(second.cart.saleId, second.cart.version, second.cart.contentHash),
            ),
        )

        val primary = requireNotNull(
            database.inventoryDao().findBalance(BUSINESS.value, PRODUCT.value, LOCATION.value),
        )
        val secondary = requireNotNull(
            database.inventoryDao().findBalance(BUSINESS.value, PRODUCT.value, LOCATION_2.value),
        )
        assertEquals("3.000", primary.quantityOnHand)
        assertEquals("2.5000", primary.averageUnitCost)
        assertEquals(8L, primary.version)
        assertEquals("3.000", secondary.quantityOnHand)
        assertEquals("3.7500", secondary.averageUnitCost)
        assertEquals(4L, secondary.version)
        val movements = database.inventoryDao()
            .listMovementsForSale(BUSINESS.value, opened.saleId.value)
            .associateBy { it.locationId }
        assertEquals(setOf(LOCATION.value, LOCATION_2.value), movements.keys)
        assertEquals("-2.000", movements.getValue(LOCATION.value).quantityDelta)
        assertEquals("-1.500", movements.getValue(LOCATION_2.value).quantityDelta)
        assertEquals(1_400L, database.saleDao().findSale(opened.saleId.value)?.totalMinorUnits)
    }

    @Test
    fun mismatchedCheckoutHashDoesNotTouchStockOrLedger() = runBlocking {
        val cart = repository.createOrResume(BUSINESS, PEN).cart
        val saved = repository.saveLine(
            BUSINESS,
            SaveSaleCartLineCommand(
                saleId = cart.saleId,
                expectedVersion = cart.version,
                productId = PRODUCT,
                locationId = LOCATION,
                quantity = Quantity.of("1"),
                unitPrice = Money.ofMinor(100L, PEN),
            ),
        ) as SaleCartMutationResult.Saved

        val result = repository.checkout(
            BUSINESS,
            CheckoutSaleCommand(saved.cart.saleId, saved.cart.version, "0".repeat(64)),
        )

        assertEquals(CheckoutSaleResult.CartChanged, result)
        assertBalanceUnchanged()
        assertTrue(database.inventoryDao().listMovementsForSale(BUSINESS.value, cart.saleId.value).isEmpty())
        assertTrue(database.auditEventDao().listForEntity(BUSINESS.value, "SALE", cart.saleId.value).isEmpty())
        assertEquals(SaleStatus.DRAFT.name, database.saleDao().findSale(cart.saleId.value)?.status)
    }

    @Test
    fun lateAuditConflictRollsBackBalancesMovementsAndPosting() = runBlocking {
        val cart = repository.createOrResume(BUSINESS, PEN).cart
        val saved = repository.saveLine(
            BUSINESS,
            SaveSaleCartLineCommand(
                saleId = cart.saleId,
                expectedVersion = cart.version,
                productId = PRODUCT,
                locationId = LOCATION,
                quantity = Quantity.of("2.000"),
                unitPrice = Money.ofMinor(550L, PEN),
            ),
        ) as SaleCartMutationResult.Saved
        database.auditEventDao().insert(
            AuditEventEntity(
                auditEventId = SaleContentIdentity.uuid("sale-posted-audit", cart.saleId.value).toString(),
                businessId = BUSINESS.value,
                purchaseId = null,
                eventType = AuditEventType.SALE_POSTED.name,
                entityType = "SALE",
                entityId = cart.saleId.value,
                payload = AuditPayloadPolicy.encode(AuditEventType.SALE_POSTED, mapOf("version" to "1")),
                occurredAt = NOW,
            ),
        )

        val result = repository.checkout(
            BUSINESS,
            CheckoutSaleCommand(saved.cart.saleId, saved.cart.version, saved.cart.contentHash),
        )

        assertEquals(CheckoutSaleResult.RetryableConflict, result)
        assertBalanceUnchanged()
        assertTrue(database.inventoryDao().listMovementsForSale(BUSINESS.value, cart.saleId.value).isEmpty())
        assertEquals(SaleStatus.DRAFT.name, database.saleDao().findSale(cart.saleId.value)?.status)
        assertEquals(
            1,
            database.auditEventDao().listForEntity(BUSINESS.value, "SALE", cart.saleId.value).size,
        )
    }

    @Test
    fun aggregateOverflowReturnsTypedFailureAndRollsBackInsertedLine() = runBlocking {
        seedLocationAndStock(LOCATION_2, "Secundario", "5.000", "2.5000", 1L)
        val cart = repository.createOrResume(BUSINESS, PEN).cart
        val first = repository.saveLine(
            BUSINESS,
            SaveSaleCartLineCommand(
                saleId = cart.saleId,
                expectedVersion = cart.version,
                productId = PRODUCT,
                locationId = LOCATION,
                quantity = Quantity.of("1"),
                unitPrice = Money.ofMinor(Long.MAX_VALUE, PEN),
            ),
        ) as SaleCartMutationResult.Saved

        val result = repository.saveLine(
            BUSINESS,
            SaveSaleCartLineCommand(
                saleId = first.cart.saleId,
                expectedVersion = first.cart.version,
                productId = PRODUCT,
                locationId = LOCATION_2,
                quantity = Quantity.of("1"),
                unitPrice = Money.ofMinor(1L, PEN),
            ),
        )

        assertEquals(SaleCartMutationResult.InvalidTotals, result)
        val stored = requireNotNull(database.saleDao().findWithLines(cart.saleId.value))
        assertEquals(1L, stored.sale.version)
        assertEquals(Long.MAX_VALUE, stored.sale.totalMinorUnits)
        assertEquals(listOf(LOCATION.value), stored.lines.map { it.locationId })
    }

    @Test
    fun checkoutOverflowInPersistedAggregateReturnsTypedFailureWithoutWrites() = runBlocking {
        seedLocationAndStock(LOCATION_2, "Secundario", "5.000", "2.5000", 1L)
        val cart = repository.createOrResume(BUSINESS, PEN).cart
        val first = repository.saveLine(
            BUSINESS,
            SaveSaleCartLineCommand(
                saleId = cart.saleId,
                expectedVersion = cart.version,
                productId = PRODUCT,
                locationId = LOCATION,
                quantity = Quantity.of("1"),
                unitPrice = Money.ofMinor(Long.MAX_VALUE, PEN),
            ),
        ) as SaleCartMutationResult.Saved
        insertPricedLine(
            saleId = cart.saleId.value,
            saleLineId = SALE_LINE_2,
            locationId = LOCATION_2,
            position = 1,
            unitPriceMinorUnits = 1L,
        )

        val result = repository.checkout(
            BUSINESS,
            CheckoutSaleCommand(first.cart.saleId, first.cart.version, first.cart.contentHash),
        )

        assertEquals(CheckoutSaleResult.InvalidTotals, result)
        assertBalanceUnchanged()
        assertTrue(database.inventoryDao().listMovementsForSale(BUSINESS.value, cart.saleId.value).isEmpty())
        assertEquals(SaleStatus.DRAFT.name, database.saleDao().findSale(cart.saleId.value)?.status)
    }

    @Test
    fun removeLineOverflowReturnsTypedFailureAndRestoresDeletedGraph() = runBlocking {
        seedLocationAndStock(LOCATION_2, "Secundario", "5.000", "2.5000", 1L)
        seedLocationAndStock(LOCATION_3, "Tercero", "5.000", "2.5000", 1L)
        val cart = repository.createOrResume(BUSINESS, PEN).cart
        val first = repository.saveLine(
            BUSINESS,
            SaveSaleCartLineCommand(
                saleId = cart.saleId,
                expectedVersion = cart.version,
                productId = PRODUCT,
                locationId = LOCATION,
                quantity = Quantity.of("1"),
                unitPrice = Money.ofMinor(Long.MAX_VALUE, PEN),
            ),
        ) as SaleCartMutationResult.Saved
        insertPricedLine(cart.saleId.value, SALE_LINE_2, LOCATION_2, 1, 1L)
        insertPricedLine(cart.saleId.value, SALE_LINE_3, LOCATION_3, 2, 1L)

        val result = repository.removeLine(
            businessId = BUSINESS,
            saleId = cart.saleId,
            saleLineId = SALE_LINE_3,
            expectedVersion = first.cart.version,
        )

        assertEquals(SaleCartMutationResult.InvalidTotals, result)
        val stored = requireNotNull(database.saleDao().findWithLines(cart.saleId.value))
        assertEquals(1L, stored.sale.version)
        assertEquals(
            listOf(SALE_LINE_2.value, SALE_LINE_3.value),
            stored.lines.sortedBy { it.position }.drop(1).map { it.saleLineId },
        )
    }

    @Test
    fun insufficientStockLeavesEntireGraphUntouched() = runBlocking {
        val cart = repository.createOrResume(BUSINESS, PEN).cart
        val saved = repository.saveLine(
            BUSINESS,
            SaveSaleCartLineCommand(
                saleId = cart.saleId,
                expectedVersion = cart.version,
                productId = PRODUCT,
                locationId = LOCATION,
                quantity = Quantity.of("5.001"),
                unitPrice = Money.ofMinor(100L, PEN),
            ),
        ) as SaleCartMutationResult.Saved

        val result = repository.checkout(
            BUSINESS,
            CheckoutSaleCommand(saved.cart.saleId, saved.cart.version, saved.cart.contentHash),
        )

        assertTrue(result is CheckoutSaleResult.InsufficientStock)
        val balance = requireNotNull(
            database.inventoryDao().findBalance(BUSINESS.value, PRODUCT.value, LOCATION.value),
        )
        assertEquals("5.000", balance.quantityOnHand)
        assertEquals(7L, balance.version)
        assertTrue(database.inventoryDao().listMovementsForSale(BUSINESS.value, cart.saleId.value).isEmpty())
        assertEquals(SaleStatus.DRAFT.name, database.saleDao().findSale(cart.saleId.value)?.status)
    }

    private suspend fun recoveryCommand(): SaveSaleCartLineCommand {
        val product = requireNotNull(database.productDao().findById(PRODUCT.value))
        assertEquals(1, database.productDao().updateCas(product.copy(barcode = RECOVERY_STORED, updatedAt = 2L)))
        val current = requireNotNull(database.productDao().findById(PRODUCT.value))
        val cart = repository.createOrResume(BUSINESS, PEN).cart
        return SaveSaleCartLineCommand(
            saleId = cart.saleId,
            expectedVersion = cart.version,
            productId = PRODUCT,
            locationId = LOCATION,
            quantity = Quantity.of("1"),
            unitPrice = Money.ofMinor(500L, PEN),
            barcodeRecovery = SaleBarcodeRecoveryExpectation(
                scannedBarcode = RECOVERY_SCANNED,
                expectedStoredBarcode = requireNotNull(current.barcode),
                expectedProductVersion = current.version,
            ),
        )
    }

    private suspend fun insertRecoveryCompetitor(
        barcode: String? = RECOVERY_COMPETITOR,
        sku: String? = null,
        status: CatalogStatus = CatalogStatus.ACTIVE,
    ): ProductEntity {
        val competitor = requireNotNull(database.productDao().findById(PRODUCT.value)).copy(
            productId = uuid(ids.incrementAndGet()).toString(),
            name = "Competidor",
            barcode = barcode,
            sku = sku,
            status = status.name,
        )
        database.productDao().insert(competitor)
        return competitor
    }

    private suspend fun assertRecoveryRejectedWithoutWrite(command: SaveSaleCartLineCommand) {
        val before = requireNotNull(database.saleDao().findWithLines(command.saleId.value))

        assertEquals(SaleCartMutationResult.BarcodeRecoveryChanged, repository.saveLine(BUSINESS, command))

        assertEquals(before, database.saleDao().findWithLines(command.saleId.value))
        assertBalanceUnchanged()
        assertTrue(database.inventoryDao().listMovementsForSale(BUSINESS.value, command.saleId.value).isEmpty())
        assertTrue(database.auditEventDao().listForEntity(BUSINESS.value, "SALE", command.saleId.value).isEmpty())
    }

    private suspend fun seedCatalogAndStock() {
        database.businessDao().insert(
            BusinessEntity(BUSINESS.value, "Negocio", 1L, 1L),
        )
        database.unitDao().insert(
            UnitEntity(UNIT_ID, BUSINESS.value, "NIU", "Unidad", 1L, 1L),
        )
        database.inventoryLocationDao().insert(
            InventoryLocationEntity(LOCATION.value, BUSINESS.value, "Principal", 1L, 1L),
        )
        database.productDao().insert(
            ProductEntity(
                productId = PRODUCT.value,
                businessId = BUSINESS.value,
                unitId = UNIT_ID,
                name = "Producto",
                createdAt = 1L,
                updatedAt = 1L,
                locationId = LOCATION.value,
            ),
        )
        database.inventoryDao().insertBalanceIfAbsent(
            InventoryBalanceEntity(
                businessId = BUSINESS.value,
                productId = PRODUCT.value,
                locationId = LOCATION.value,
                quantityOnHand = "5.000",
                averageUnitCost = "2.5000",
                currencyCode = PEN.value,
                version = 7L,
                updatedAt = 1L,
            ),
        )
    }

    private fun openRestartDatabase(): FacturaStockDatabase =
        Room.databaseBuilder(context, FacturaStockDatabase::class.java, RESTART_DATABASE_NAME)
            .allowMainThreadQueries()
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addCallback(postingPersistenceCallback)
            .build()
            .also { it.openHelper.writableDatabase }

    private fun saleRepository(database: FacturaStockDatabase): RoomSaleRepository =
        RoomSaleRepository(
            database = database,
            appClock = AppClock { Instant.ofEpochMilli(NOW) },
            uuidGenerator = UuidGenerator { uuid(ids.incrementAndGet()) },
            dispatchers = TEST_DISPATCHERS,
        )

    private fun assertWalCheckpointCompletes() {
        val sqlite = database.openHelper.writableDatabase
        sqlite.query("PRAGMA journal_mode").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("wal", cursor.getString(0).lowercase())
        }
        sqlite.query("PRAGMA wal_checkpoint(FULL)").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
            assertEquals(cursor.getInt(1), cursor.getInt(2))
        }
    }

    private fun assertDatabaseIntegrity() {
        val sqlite = database.openHelper.readableDatabase
        sqlite.query("PRAGMA integrity_check").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("ok", cursor.getString(0))
            assertFalse(cursor.moveToNext())
        }
        sqlite.query("PRAGMA foreign_key_check").use { cursor ->
            assertEquals(0, cursor.count)
        }
    }

    private suspend fun seedLocationAndStock(
        locationId: LocationId,
        name: String,
        quantity: String,
        averageUnitCost: String,
        version: Long,
    ) {
        database.inventoryLocationDao().insert(
            InventoryLocationEntity(locationId.value, BUSINESS.value, name, 1L, 1L),
        )
        database.inventoryDao().insertBalanceIfAbsent(
            InventoryBalanceEntity(
                businessId = BUSINESS.value,
                productId = PRODUCT.value,
                locationId = locationId.value,
                quantityOnHand = quantity,
                averageUnitCost = averageUnitCost,
                currencyCode = PEN.value,
                version = version,
                updatedAt = 1L,
            ),
        )
    }

    private suspend fun insertPricedLine(
        saleId: String,
        saleLineId: SaleLineId,
        locationId: LocationId,
        position: Int,
        unitPriceMinorUnits: Long,
    ) {
        database.saleDao().insertLine(
            SaleLineEntity(
                saleLineId = saleLineId.value,
                saleId = saleId,
                productId = PRODUCT.value,
                unitId = UNIT_ID,
                locationId = locationId.value,
                position = position,
                productNameSnapshot = "Producto",
                unitCodeSnapshot = "NIU",
                locationNameSnapshot = "Secundario",
                quantity = "1",
                unitPriceMinorUnits = unitPriceMinorUnits,
                discountMinorUnits = 0L,
                taxMinorUnits = 0L,
                lineTotalMinorUnits = unitPriceMinorUnits,
                currencyCode = PEN.value,
            ),
        )
    }

    private suspend fun assertBalanceUnchanged() {
        val balance = requireNotNull(
            database.inventoryDao().findBalance(BUSINESS.value, PRODUCT.value, LOCATION.value),
        )
        assertEquals("5.000", balance.quantityOnHand)
        assertEquals("2.5000", balance.averageUnitCost)
        assertEquals(7L, balance.version)
    }

    private companion object {
        const val RECOVERY_SCANNED = "77512345000"
        const val RECOVERY_STORED = "7751234500004"
        const val RECOVERY_COMPETITOR = "7751234500011"
        const val NOW = 10_000L
        const val RESTART_DATABASE_NAME = "room-sale-restart.db"
        const val UNIT_ID = "22222222-2222-4222-8222-222222222222"
        val PEN: CurrencyCode = CurrencyCode.of("PEN")
        val BUSINESS: BusinessId = requireNotNull(
            BusinessId.parse("11111111-1111-4111-8111-111111111111"),
        )
        val PRODUCT: ProductId = requireNotNull(
            ProductId.parse("33333333-3333-4333-8333-333333333333"),
        )
        val LOCATION: LocationId = requireNotNull(
            LocationId.parse("44444444-4444-4444-8444-444444444444"),
        )
        val LOCATION_2: LocationId = requireNotNull(
            LocationId.parse("55555555-5555-4555-8555-555555555555"),
        )
        val LOCATION_3: LocationId = requireNotNull(
            LocationId.parse("77777777-7777-4777-8777-777777777777"),
        )
        val SALE_LINE_2: SaleLineId = requireNotNull(
            SaleLineId.parse("66666666-6666-4666-8666-666666666666"),
        )
        val SALE_LINE_3: SaleLineId = requireNotNull(
            SaleLineId.parse("88888888-8888-4888-8888-888888888888"),
        )
        val TEST_DISPATCHERS = object : DispatcherProvider {
            override val io: CoroutineDispatcher = Dispatchers.Unconfined
            override val default: CoroutineDispatcher = Dispatchers.Unconfined
            override val main: CoroutineDispatcher = Dispatchers.Unconfined
        }

        fun uuid(seed: Long): UUID = UUID(seed, seed)
    }
}
