package com.facturastock.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.entity.BusinessEntity
import com.facturastock.app.data.local.entity.CatalogSyncLinkEntity
import com.facturastock.app.data.local.entity.CloudBusinessBindingEntity
import com.facturastock.app.data.local.entity.DebtPaymentEntity
import com.facturastock.app.data.local.entity.InventoryBalanceEntity
import com.facturastock.app.data.local.entity.InventoryLocationEntity
import com.facturastock.app.data.local.entity.ProductEntity
import com.facturastock.app.data.local.entity.SaleEntity
import com.facturastock.app.data.local.entity.SaleLineEntity
import com.facturastock.app.data.local.entity.UnitEntity
import com.facturastock.app.data.local.postingPersistenceCallback
import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.DebtPaymentMethod
import com.facturastock.app.domain.model.DebtStatus
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.SaleStatus
import com.facturastock.app.domain.model.SharedInventoryBalance
import com.facturastock.app.domain.model.SharedInventoryChange
import com.facturastock.app.domain.model.SharedInventoryChangeKind
import com.facturastock.app.domain.model.SharedInventoryPullPage
import com.facturastock.app.domain.model.SharedDebtPayment
import com.facturastock.app.domain.model.SharedDebtSnapshot
import com.facturastock.app.domain.model.SharedSaleCredit
import com.facturastock.app.domain.model.SharedSaleDocument
import com.facturastock.app.domain.model.SharedSaleLine
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DebtId
import com.facturastock.app.domain.model.id.DebtPaymentId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.SaleId
import com.facturastock.app.domain.model.id.SaleLineId
import com.facturastock.app.domain.model.id.UnitId
import java.math.BigDecimal
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomSharedInventoryApplicationRepositoryTest {
    private lateinit var database: FacturaStockDatabase
    private lateinit var repository: RoomSharedInventoryApplicationRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, FacturaStockDatabase::class.java)
            .addCallback(postingPersistenceCallback)
            .build()
        database.openHelper.writableDatabase
        repository = RoomSharedInventoryApplicationRepository(database, testDispatchers)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun exactNormalDraftIsRecoveredAfterRemoteAck() = runBlocking {
        val page = seedNormalDraftAndRemotePage()

        val result = repository.applyPage(
            localBusinessId = BUSINESS,
            cloudBusinessId = CLOUD_BUSINESS,
            expectedPreviousSeq = 0L,
            page = page,
            appliedAt = Instant.ofEpochMilli(400L),
        )

        assertEquals(DomainResult.Success(1), result)
        val sale = requireNotNull(database.saleDao().findSale(SALE.value))
        assertEquals(SaleStatus.POSTED.name, sale.status)
        assertNull(sale.draftSlot)
        assertEquals(2L, sale.version)
        assertEquals(
            "sale-checkout:v1:${SALE.value}:1:${sale.contentHash}",
            sale.checkoutIdempotencyKey,
        )
        assertEquals(300L, sale.postedAt)
        assertEquals(
            "Principal  1",
            database.saleDao().findLine(SALE_LINE.value)?.locationNameSnapshot,
        )
        assertEquals(1, database.inventoryDao().listMovementsForSale(BUSINESS.value, SALE.value).size)
        assertEquals(1, database.auditEventDao().listForEntity(BUSINESS.value, "SALE", SALE.value).size)
        val balance = requireNotNull(
            database.inventoryDao().findBalance(BUSINESS.value, PRODUCT.value, LOCATION.value),
        )
        assertEquals("4", balance.quantityOnHand)
        assertEquals(1L, database.remoteSyncDao().findState(CLOUD_BUSINESS.value)?.inventorySeq)
    }

    @Test
    fun mutatedNormalDraftFailsClosedAndDoesNotAdvanceCursor() = runBlocking {
        val page = seedNormalDraftAndRemotePage()
        val stored = requireNotNull(database.saleDao().findLine(SALE_LINE.value))
        assertEquals(
            1,
            database.saleDao().updateLine(
                saleLineId = stored.saleLineId,
                saleId = stored.saleId,
                productId = stored.productId,
                unitId = stored.unitId,
                locationId = stored.locationId,
                productNameSnapshot = stored.productNameSnapshot,
                unitCodeSnapshot = stored.unitCodeSnapshot,
                locationNameSnapshot = stored.locationNameSnapshot,
                barcodeSnapshot = stored.barcodeSnapshot,
                quantity = stored.quantity,
                unitPriceMinorUnits = 200L,
                discountMinorUnits = 0L,
                taxMinorUnits = 0L,
                lineTotalMinorUnits = 200L,
                currencyCode = stored.currencyCode,
            ),
        )
        val mutated = stored.copy(unitPriceMinorUnits = 200L, lineTotalMinorUnits = 200L)
        val mutatedTotals = SaleCartTotals.of(PEN.value, listOf(mutated))
        assertEquals(
            1,
            database.saleDao().updateDraftSummaryIfVersion(
                saleId = SALE.value,
                businessId = BUSINESS.value,
                expectedVersion = 1L,
                subtotalMinorUnits = mutatedTotals.subtotal,
                discountMinorUnits = mutatedTotals.discount,
                taxMinorUnits = mutatedTotals.tax,
                totalMinorUnits = mutatedTotals.total,
                contentHash = mutatedTotals.contentHash,
                updatedAt = 250L,
            ),
        )

        val result = repository.applyPage(
            localBusinessId = BUSINESS,
            cloudBusinessId = CLOUD_BUSINESS,
            expectedPreviousSeq = 0L,
            page = page,
            appliedAt = Instant.ofEpochMilli(400L),
        )

        assertTrue(result is DomainResult.Failure && result.error == AccountError.Conflict)
        assertEquals(SaleStatus.DRAFT.name, database.saleDao().findSale(SALE.value)?.status)
        assertEquals(2L, database.saleDao().findSale(SALE.value)?.version)
        assertEquals(0L, database.remoteSyncDao().findState(CLOUD_BUSINESS.value)?.inventorySeq ?: 0L)
        val balance = requireNotNull(
            database.inventoryDao().findBalance(BUSINESS.value, PRODUCT.value, LOCATION.value),
        )
        assertEquals("5", balance.quantityOnHand)
        assertEquals(7L, balance.version)
    }

    @Test
    fun remoteBalanceCreatesMissingLocationForSecondDevice() = runBlocking {
        database.businessDao().insert(BusinessEntity(BUSINESS.value, "Negocio", 1L, 1L))
        database.cloudBusinessBindingDao().insert(
            CloudBusinessBindingEntity(BUSINESS.value, CLOUD_BUSINESS.value, 1L, 0),
        )
        database.unitDao().insert(
            UnitEntity(UNIT.value, BUSINESS.value, "NIU", "Unidad", 1L, 1L),
        )
        database.productDao().insert(
            ProductEntity(
                productId = PRODUCT.value,
                businessId = BUSINESS.value,
                unitId = UNIT.value,
                name = "Producto",
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        database.catalogSyncLinkDao().insert(
            CatalogSyncLinkEntity(
                localBusinessId = BUSINESS.value,
                cloudBusinessId = CLOUD_BUSINESS.value,
                entityType = "PRODUCT",
                localEntityId = PRODUCT.value,
                remoteEntityId = REMOTE_PRODUCT.value,
                remoteVersion = 1L,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        val page = SharedInventoryPullPage(
            changes = listOf(
                SharedInventoryChange(
                    seq = 1L,
                    kind = SharedInventoryChangeKind.PURCHASE,
                    balances = listOf(
                        SharedInventoryBalance(
                            productId = REMOTE_PRODUCT,
                            locationName = "  Depósito   norte  ",
                            quantityOnHand = BigDecimal("3"),
                            averageUnitCost = BigDecimal("2.5"),
                            currency = PEN,
                            version = 1L,
                            updatedAt = Instant.ofEpochMilli(300L),
                        ),
                    ),
                    sale = null,
                ),
            ),
            nextCursor = 1L,
            hasMore = false,
        )

        val result = repository.applyPage(
            localBusinessId = BUSINESS,
            cloudBusinessId = CLOUD_BUSINESS,
            expectedPreviousSeq = 0L,
            page = page,
            appliedAt = Instant.ofEpochMilli(400L),
        )

        assertEquals(DomainResult.Success(0), result)
        val location = database.inventoryLocationDao()
            .findByCanonicalName(BUSINESS.value, "Depósito norte")
            .single()
        assertEquals("Depósito norte", location.name)
        assertEquals(
            "3",
            database.inventoryDao().findBalance(
                BUSINESS.value,
                PRODUCT.value,
                location.locationId,
            )?.quantityOnHand,
        )
    }

    @Test
    fun laterFeedSequenceWinsWhenLocalAndRemoteTimestampsAreEqual() = runBlocking {
        seedNormalDraftAndRemotePage()
        val page = SharedInventoryPullPage(
            changes = listOf(
                SharedInventoryChange(
                    seq = 1L,
                    kind = SharedInventoryChangeKind.PURCHASE,
                    balances = listOf(
                        SharedInventoryBalance(
                            productId = REMOTE_PRODUCT,
                            locationName = "Principal 1",
                            quantityOnHand = BigDecimal("9"),
                            averageUnitCost = BigDecimal("2.5"),
                            currency = PEN,
                            version = 1L,
                            updatedAt = Instant.ofEpochMilli(1L),
                        ),
                    ),
                    sale = null,
                ),
            ),
            nextCursor = 1L,
            hasMore = false,
        )

        val result = repository.applyPage(
            localBusinessId = BUSINESS,
            cloudBusinessId = CLOUD_BUSINESS,
            expectedPreviousSeq = 0L,
            page = page,
            appliedAt = Instant.ofEpochMilli(400L),
        )

        assertEquals(DomainResult.Success(0), result)
        val balance = requireNotNull(
            database.inventoryDao().findBalance(BUSINESS.value, PRODUCT.value, LOCATION.value),
        )
        assertEquals("9", balance.quantityOnHand)
        assertEquals(8L, balance.version)
        assertEquals(1L, database.remoteSyncDao().findState(CLOUD_BUSINESS.value)?.inventorySeq)
    }

    @Test
    fun laterFeedSequenceWinsEvenWhenLocalClockIsAhead() = runBlocking {
        seedNormalDraftAndRemotePage()
        val existing = requireNotNull(
            database.inventoryDao().findBalance(BUSINESS.value, PRODUCT.value, LOCATION.value),
        )
        assertEquals(
            1,
            database.inventoryDao().updateAuthoritativeBalanceIfVersion(
                businessId = existing.businessId,
                productId = existing.productId,
                locationId = existing.locationId,
                expectedVersion = existing.version,
                quantityOnHand = existing.quantityOnHand,
                averageUnitCost = existing.averageUnitCost,
                currencyCode = existing.currencyCode,
                updatedAt = 500L,
            ),
        )
        val page = SharedInventoryPullPage(
            changes = listOf(
                SharedInventoryChange(
                    seq = 1L,
                    kind = SharedInventoryChangeKind.PURCHASE,
                    balances = listOf(
                        SharedInventoryBalance(
                            productId = REMOTE_PRODUCT,
                            locationName = "Principal 1",
                            quantityOnHand = BigDecimal("9"),
                            averageUnitCost = BigDecimal("2.5"),
                            currency = PEN,
                            version = 1L,
                            updatedAt = Instant.ofEpochMilli(400L),
                        ),
                    ),
                    sale = null,
                ),
            ),
            nextCursor = 1L,
            hasMore = false,
        )

        val result = repository.applyPage(
            localBusinessId = BUSINESS,
            cloudBusinessId = CLOUD_BUSINESS,
            expectedPreviousSeq = 0L,
            page = page,
            appliedAt = Instant.ofEpochMilli(600L),
        )

        assertEquals(DomainResult.Success(0), result)
        val balance = requireNotNull(
            database.inventoryDao().findBalance(BUSINESS.value, PRODUCT.value, LOCATION.value),
        )
        assertEquals("9", balance.quantityOnHand)
        assertEquals(400L, balance.updatedAt)
        assertEquals(1L, database.remoteSyncDao().findState(CLOUD_BUSINESS.value)?.inventorySeq)
    }

    @Test
    fun creditSaleAndTwoLocalPaymentsAheadOfCursorReplayFromZeroInOrder() = runBlocking {
        val fixture = seedCreditSaleWithTwoLocalPayments()
        val state = requireNotNull(database.remoteSyncDao().findState(CLOUD_BUSINESS.value))
        database.remoteSyncDao().upsertState(
            state.copy(inventorySeq = 0L, inventoryPulledAt = null),
        )
        val balanceBeforeReplay = requireNotNull(
            database.inventoryDao().findBalance(BUSINESS.value, PRODUCT.value, LOCATION.value),
        )
        assertEquals(
            1,
            database.inventoryDao().updateAuthoritativeBalanceIfVersion(
                businessId = balanceBeforeReplay.businessId,
                productId = balanceBeforeReplay.productId,
                locationId = balanceBeforeReplay.locationId,
                expectedVersion = balanceBeforeReplay.version,
                quantityOnHand = "3",
                averageUnitCost = balanceBeforeReplay.averageUnitCost,
                currencyCode = balanceBeforeReplay.currencyCode,
                updatedAt = 350L,
            ),
        )
        val replayPage = SharedInventoryPullPage(
            changes = listOf(
                fixture.saleChange,
                fixture.firstPaymentChange,
                fixture.secondPaymentChange,
            ),
            nextCursor = 3L,
            hasMore = false,
        )

        val result = repository.applyPage(
            localBusinessId = BUSINESS,
            cloudBusinessId = CLOUD_BUSINESS,
            expectedPreviousSeq = 0L,
            page = replayPage,
            appliedAt = Instant.ofEpochMilli(500L),
        )

        assertEquals(DomainResult.Success(0), result)
        val debt = requireNotNull(database.debtDao().findDebt(fixture.debtId.value))
        assertEquals(3L, debt.version)
        assertEquals(50L, debt.balanceMinorUnits)
        assertEquals(2, database.debtDao().countPayments(fixture.debtId.value))
        assertEquals(
            "3",
            database.inventoryDao()
                .findBalance(BUSINESS.value, PRODUCT.value, LOCATION.value)
                ?.quantityOnHand,
        )
        assertEquals(3L, database.remoteSyncDao().findState(CLOUD_BUSINESS.value)?.inventorySeq)
    }

    @Test
    fun advancedDebtRejectsHistoricalFeedWhenItsExactPaymentIsMissing() = runBlocking {
        val fixture = seedCreditSaleWithTwoLocalPayments()
        val missingPaymentId = DebtPaymentId.from(
            SaleContentIdentity.uuid("missing-debt-payment", fixture.debtId.value, "1"),
        )
        val missingPaymentPage = SharedInventoryPullPage(
            changes = listOf(
                debtPaymentChange(
                    seq = 2L,
                    debtId = fixture.debtId,
                    paymentId = missingPaymentId,
                    expectedVersion = 1L,
                    amountMinorUnits = 30L,
                    balanceAfterMinorUnits = 70L,
                    method = DebtPaymentMethod.YAPE,
                    at = 310L,
                ),
            ),
            nextCursor = 2L,
            hasMore = true,
        )

        val result = repository.applyPage(
            localBusinessId = BUSINESS,
            cloudBusinessId = CLOUD_BUSINESS,
            expectedPreviousSeq = 1L,
            page = missingPaymentPage,
            appliedAt = Instant.ofEpochMilli(500L),
        )

        assertTrue(result is DomainResult.Failure && result.error == AccountError.Conflict)
        assertEquals(3L, database.debtDao().findDebt(fixture.debtId.value)?.version)
        assertEquals(2, database.debtDao().countPayments(fixture.debtId.value))
        assertEquals(1L, database.remoteSyncDao().findState(CLOUD_BUSINESS.value)?.inventorySeq)
    }

    private suspend fun seedCreditSaleWithTwoLocalPayments(): CreditReplayFixture {
        val salePage = seedNormalDraftAndRemotePage()
        val saleChange = salePage.changes.single()
        val sale = requireNotNull(saleChange.sale)
        val debtId = DebtId.from(SaleContentIdentity.uuid("sale-debt", SALE.value))
        val creditPage = salePage.copy(
            changes = listOf(
                saleChange.copy(
                    sale = sale.copy(
                        credit = SharedSaleCredit(
                            debtId = debtId,
                            debtorNameSnapshot = "María Pérez",
                            dueAt = null,
                        ),
                    ),
                ),
            ),
        )
        check(
            repository.applyPage(
                localBusinessId = BUSINESS,
                cloudBusinessId = CLOUD_BUSINESS,
                expectedPreviousSeq = 0L,
                page = creditPage,
                appliedAt = Instant.ofEpochMilli(400L),
            ) == DomainResult.Success(1),
        )

        val firstPaymentId = DebtPaymentId.from(
            SaleContentIdentity.uuid("debt-payment", debtId.value, "1"),
        )
        val secondPaymentId = DebtPaymentId.from(
            SaleContentIdentity.uuid("debt-payment", debtId.value, "2"),
        )
        recordLocalPayment(
            debtId = debtId,
            paymentId = firstPaymentId,
            expectedVersion = 1L,
            amountMinorUnits = 30L,
            balanceAfterMinorUnits = 70L,
            method = DebtPaymentMethod.YAPE,
            at = 310L,
        )
        recordLocalPayment(
            debtId = debtId,
            paymentId = secondPaymentId,
            expectedVersion = 2L,
            amountMinorUnits = 20L,
            balanceAfterMinorUnits = 50L,
            method = DebtPaymentMethod.CASH,
            at = 320L,
        )
        check(database.debtDao().findDebt(debtId.value)?.version == 3L)
        check(database.remoteSyncDao().findState(CLOUD_BUSINESS.value)?.inventorySeq == 1L)
        return CreditReplayFixture(
            debtId = debtId,
            saleChange = creditPage.changes.single(),
            firstPaymentChange = debtPaymentChange(
                seq = 2L,
                debtId = debtId,
                paymentId = firstPaymentId,
                expectedVersion = 1L,
                amountMinorUnits = 30L,
                balanceAfterMinorUnits = 70L,
                method = DebtPaymentMethod.YAPE,
                at = 310L,
            ),
            secondPaymentChange = debtPaymentChange(
                seq = 3L,
                debtId = debtId,
                paymentId = secondPaymentId,
                expectedVersion = 2L,
                amountMinorUnits = 20L,
                balanceAfterMinorUnits = 50L,
                method = DebtPaymentMethod.CASH,
                at = 320L,
            ),
        )
    }

    private data class CreditReplayFixture(
        val debtId: DebtId,
        val saleChange: SharedInventoryChange,
        val firstPaymentChange: SharedInventoryChange,
        val secondPaymentChange: SharedInventoryChange,
    )

    private suspend fun recordLocalPayment(
        debtId: DebtId,
        paymentId: DebtPaymentId,
        expectedVersion: Long,
        amountMinorUnits: Long,
        balanceAfterMinorUnits: Long,
        method: DebtPaymentMethod,
        at: Long,
    ) {
        val idempotencyKey = "debt-payment:v1:${debtId.value}:${paymentId.value}"
        database.debtDao().insertPayment(
            DebtPaymentEntity(
                paymentId = paymentId.value,
                debtId = debtId.value,
                businessId = BUSINESS.value,
                currencyCode = PEN.value,
                amountMinorUnits = amountMinorUnits,
                method = method.name,
                note = null,
                reference = null,
                expectedDebtVersion = expectedVersion,
                balanceAfterMinorUnits = balanceAfterMinorUnits,
                idempotencyKey = idempotencyKey,
                occurredAt = at,
                createdAt = at,
            ),
        )
        check(
            database.debtDao().applyPaymentIfVersion(
                debtId = debtId.value,
                businessId = BUSINESS.value,
                expectedVersion = expectedVersion,
                currencyCode = PEN.value,
                balanceAfterMinorUnits = balanceAfterMinorUnits,
                status = DebtStatus.OPEN.name,
                updatedAt = at,
                paidAt = null,
            ) == 1
        )
    }

    private fun debtPaymentChange(
        seq: Long,
        debtId: DebtId,
        paymentId: DebtPaymentId,
        expectedVersion: Long,
        amountMinorUnits: Long,
        balanceAfterMinorUnits: Long,
        method: DebtPaymentMethod,
        at: Long,
    ): SharedInventoryChange {
        val debt = SharedDebtSnapshot(
            debtId = debtId,
            businessId = CLOUD_BUSINESS,
            saleId = SALE,
            debtorNameSnapshot = "María Pérez",
            currency = PEN,
            originalAmount = Money.ofMinor(100L, PEN),
            balance = Money.ofMinor(balanceAfterMinorUnits, PEN),
            status = DebtStatus.OPEN,
            dueAt = null,
            version = expectedVersion + 1L,
            createdAt = Instant.ofEpochMilli(300L),
            updatedAt = Instant.ofEpochMilli(at),
            paidAt = null,
        )
        val payment = SharedDebtPayment(
            paymentId = paymentId,
            debtId = debtId,
            businessId = CLOUD_BUSINESS,
            amount = Money.ofMinor(amountMinorUnits, PEN),
            method = method,
            note = null,
            reference = null,
            expectedDebtVersion = expectedVersion,
            balanceAfter = Money.ofMinor(balanceAfterMinorUnits, PEN),
            idempotencyKey = "debt-payment:v1:${debtId.value}:${paymentId.value}",
            occurredAt = Instant.ofEpochMilli(at),
            createdAt = Instant.ofEpochMilli(at),
        )
        return SharedInventoryChange(
            seq = seq,
            kind = SharedInventoryChangeKind.DEBT_PAYMENT,
            balances = emptyList(),
            sale = null,
            debt = debt,
            payment = payment,
        )
    }

    private suspend fun seedNormalDraftAndRemotePage(): SharedInventoryPullPage {
        database.businessDao().insert(
            BusinessEntity(BUSINESS.value, "Negocio", 1L, 1L),
        )
        database.cloudBusinessBindingDao().insert(
            CloudBusinessBindingEntity(BUSINESS.value, CLOUD_BUSINESS.value, 1L, 0),
        )
        database.unitDao().insert(
            UnitEntity(UNIT.value, BUSINESS.value, "NIU", "Unidad", 1L, 1L),
        )
        database.inventoryLocationDao().insert(
            InventoryLocationEntity(LOCATION.value, BUSINESS.value, "Principal  1", 1L, 1L),
        )
        database.productDao().insert(
            ProductEntity(
                productId = PRODUCT.value,
                businessId = BUSINESS.value,
                unitId = UNIT.value,
                name = "Producto",
                createdAt = 1L,
                updatedAt = 1L,
                locationId = LOCATION.value,
            ),
        )
        database.catalogSyncLinkDao().insert(
            CatalogSyncLinkEntity(
                localBusinessId = BUSINESS.value,
                cloudBusinessId = CLOUD_BUSINESS.value,
                entityType = "PRODUCT",
                localEntityId = PRODUCT.value,
                remoteEntityId = REMOTE_PRODUCT.value,
                remoteVersion = 1L,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        database.inventoryDao().insertBalanceIfAbsent(
            InventoryBalanceEntity(
                businessId = BUSINESS.value,
                productId = PRODUCT.value,
                locationId = LOCATION.value,
                quantityOnHand = "5",
                averageUnitCost = "2.5",
                currencyCode = PEN.value,
                version = 7L,
                updatedAt = 1L,
            ),
        )
        database.saleDao().insertSale(
            SaleEntity(
                saleId = SALE.value,
                businessId = BUSINESS.value,
                status = SaleStatus.DRAFT.name,
                currencyCode = PEN.value,
                subtotalMinorUnits = 0L,
                discountMinorUnits = 0L,
                taxMinorUnits = 0L,
                totalMinorUnits = 0L,
                contentHash = SaleContentIdentity.hash(PEN.value, emptyList()),
                draftSlot = "${BUSINESS.value}:${PEN.value}",
                version = 0L,
                createdAt = 100L,
                updatedAt = 100L,
            ),
        )
        val localLine = SaleLineEntity(
            saleLineId = SALE_LINE.value,
            saleId = SALE.value,
            productId = PRODUCT.value,
            unitId = UNIT.value,
            locationId = LOCATION.value,
            position = 0,
            productNameSnapshot = "Producto",
            unitCodeSnapshot = "NIU",
            locationNameSnapshot = "Principal  1",
            quantity = "1",
            unitPriceMinorUnits = 100L,
            discountMinorUnits = 0L,
            taxMinorUnits = 0L,
            lineTotalMinorUnits = 100L,
            currencyCode = PEN.value,
        )
        database.saleDao().insertLine(localLine)
        val totals = SaleCartTotals.of(PEN.value, listOf(localLine))
        check(
            database.saleDao().updateDraftSummaryIfVersion(
                saleId = SALE.value,
                businessId = BUSINESS.value,
                expectedVersion = 0L,
                subtotalMinorUnits = totals.subtotal,
                discountMinorUnits = totals.discount,
                taxMinorUnits = totals.tax,
                totalMinorUnits = totals.total,
                contentHash = totals.contentHash,
                updatedAt = 200L,
            ) == 1
        )
        val remoteLine = SharedSaleLine(
            saleLineId = SALE_LINE,
            position = 0,
            productId = REMOTE_PRODUCT,
            unitId = UNIT,
            locationId = LOCATION,
            productName = "Producto",
            unitCode = "NIU",
            locationName = "Principal 1",
            barcode = null,
            quantity = Quantity.of("1"),
            unitPrice = Money.ofMinor(100L, PEN),
            discount = Money.zero(PEN),
            tax = Money.zero(PEN),
            lineTotal = Money.ofMinor(100L, PEN),
        )
        val remoteContentHash = SaleContentIdentity.hash(
            PEN.value,
            listOf(localLine.copy(productId = REMOTE_PRODUCT.value)),
        )
        val document = SharedSaleDocument(
            saleId = SALE,
            currency = PEN,
            subtotal = Money.ofMinor(100L, PEN),
            discount = Money.zero(PEN),
            tax = Money.zero(PEN),
            total = Money.ofMinor(100L, PEN),
            contentHash = remoteContentHash,
            checkoutIdempotencyKey = "sale-checkout:v1:${SALE.value}:1:$remoteContentHash",
            createdAt = Instant.ofEpochMilli(100L),
            updatedAt = Instant.ofEpochMilli(300L),
            postedAt = Instant.ofEpochMilli(300L),
            lines = listOf(remoteLine),
        )
        return SharedInventoryPullPage(
            changes = listOf(
                SharedInventoryChange(
                    seq = 1L,
                    kind = SharedInventoryChangeKind.SALE,
                    balances = listOf(
                        SharedInventoryBalance(
                            productId = REMOTE_PRODUCT,
                            locationName = "Principal 1",
                            quantityOnHand = BigDecimal("4"),
                            averageUnitCost = BigDecimal("2.5"),
                            currency = PEN,
                            version = 8L,
                            updatedAt = Instant.ofEpochMilli(300L),
                        ),
                    ),
                    sale = document,
                ),
            ),
            nextCursor = 1L,
            hasMore = false,
        )
    }

    private companion object {
        val BUSINESS = requireNotNull(BusinessId.parse("11111111-1111-4111-8111-111111111111"))
        val CLOUD_BUSINESS = requireNotNull(BusinessId.parse("22222222-2222-4222-8222-222222222222"))
        val UNIT = requireNotNull(UnitId.parse("33333333-3333-4333-8333-333333333333"))
        val LOCATION = requireNotNull(LocationId.parse("44444444-4444-4444-8444-444444444444"))
        val PRODUCT = requireNotNull(ProductId.parse("55555555-5555-4555-8555-555555555555"))
        val REMOTE_PRODUCT = requireNotNull(
            ProductId.parse("88888888-8888-4888-8888-888888888888"),
        )
        val SALE = requireNotNull(SaleId.parse("66666666-6666-4666-8666-666666666666"))
        val SALE_LINE = requireNotNull(SaleLineId.parse("77777777-7777-4777-8777-777777777777"))
        val PEN = CurrencyCode.of("PEN")
    }
}
