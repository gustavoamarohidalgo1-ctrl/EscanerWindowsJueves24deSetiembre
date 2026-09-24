package com.facturastock.app.data.repository

import com.facturastock.app.data.local.newFacturaStockDatabase
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import com.facturastock.app.data.local.readableSql
import com.facturastock.app.data.local.writableSql
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.entity.BusinessEntity
import com.facturastock.app.data.local.entity.InventoryLocationEntity
import com.facturastock.app.data.local.entity.InvoiceDraftEntity
import com.facturastock.app.data.local.entity.InvoiceLineEntity
import com.facturastock.app.data.local.entity.UnitEntity
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InvoiceMatchingIssue
import com.facturastock.app.domain.model.MatchStatus
import com.facturastock.app.domain.model.PrivateImageDeletionResult
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.ScannedItemMatch
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.repository.DraftFileStore
import com.facturastock.app.domain.usecase.ConfirmInvoiceMatchingResult
import com.facturastock.app.domain.usecase.SaveSupplierAliasUseCase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class RoomInvoiceMatchingCommitRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: FacturaStockDatabase
    private lateinit var products: RoomProductRepository
    private lateinit var inventory: RoomProductInventoryRepository
    private lateinit var repository: RoomInvoiceMatchingCommitRepository
    private val clock = TestClock(Instant.parse("2026-09-04T12:00:00Z"))
    private val ids = UuidGenerator { UUID.randomUUID() }

    @Before fun setup() =
        runBlocking {
            db =
                tempFolder.newFacturaStockDatabase()
            products = RoomProductRepository(db, db.productDao(), testDispatchers, clock)
            inventory = RoomProductInventoryRepository(db, db.inventoryDao(), testDispatchers, clock, ids)
            val drafts = RoomInvoiceDraftRepository(db, db.invoiceDraftDao(), db.invoiceImageDao(), db.invoiceLineDao(), testDispatchers, clock)
            repository =
                RoomInvoiceMatchingCommitRepository(
                    db,
                    products,
                    inventory,
                    drafts,
                    NoFiles,
                    SaveSupplierAliasUseCase(RoomSupplierProductAliasRepository(db.supplierProductAliasDao(), testDispatchers, clock), ids, clock),
                    clock,
                    testDispatchers,
                )
            val now = clock.now().toEpochMilli()
            db.businessDao().insert(BusinessEntity(BUSINESS.value, "Almacén", now, now))
            db.unitDao().insert(UnitEntity(UNIT.value, BUSINESS.value, "NIU", "Unidad", now, now))
            db.unitDao().insert(UnitEntity(BOX.value, BUSINESS.value, "CJA", "Caja", now, now))
            db.inventoryLocationDao().insert(InventoryLocationEntity(LOCATION.value, BUSINESS.value, "Principal", now, now))
            db.invoiceDraftDao().insert(InvoiceDraftEntity(DRAFT.value, BUSINESS.value, now, now, DraftStatus.NEEDS_REVIEW.name))
        }

    @After fun close() = db.close()

    @Test fun mixedBatchWithMissingQuantityDoesNotWriteOrDeleteAnything() =
        runBlocking {
            val rows = listOf(row(0), row(1).copy(quantity = null))
            seedSource(rows)
            val result = repository.confirm(BUSINESS, PEN, DRAFT, rows)
            assertTrue(result is ConfirmInvoiceMatchingResult.ReviewRequired)
            assertEquals(setOf(InvoiceMatchingIssue.QUANTITY_REQUIRED), (result as ConfirmInvoiceMatchingResult.ReviewRequired).issues[1])
            assertEquals(0L, count("products"))
            assertEquals(0L, count("stock_movements"))
            assertEquals(0L, count("invoice_inventory_receipts"))
            assertNotNull(db.invoiceDraftDao().findById(DRAFT.value))
        }

    @Test fun sourceBoxesConvertQuantityAndCostAtomically() =
        runBlocking {
            val item = row(0).copy(sourceUnitCode = "CJA", matchedProduct = product(0).copy(purchaseUnitId = BOX, purchaseFactor = BigDecimal("12")))
            seedSource(listOf(item))
            assertEquals(ConfirmInvoiceMatchingResult.Applied(1), repository.confirm(BUSINESS, PEN, DRAFT, listOf(item)))
            assertBalance(item.matchedProduct!!.productId, "24", "10")
            assertNull(db.invoiceDraftDao().findById(DRAFT.value))
            assertNotNull(db.invoiceInventoryReceiptDao().find(DRAFT.value))
            assertEquals(1L, count("outbox_operations"))
        }

    @Test fun failureAtDraftClosureRollsBackProductsOutboxStockAndReceipt() =
        runBlocking {
            val item = row(0)
            seedSource(listOf(item))
            db.writableSql.execSQL("CREATE TRIGGER fail_matching_close BEFORE DELETE ON invoice_drafts BEGIN SELECT RAISE(ABORT, 'injected close failure'); END")
            val failure = runCatching { repository.confirm(BUSINESS, PEN, DRAFT, listOf(item)) }.exceptionOrNull()
            assertTrue(failure is StorageException)
            listOf("products", "outbox_operations", "inventory_balances", "stock_movements", "invoice_inventory_receipts").forEach { assertEquals(it, 0L, count(it)) }
            assertNotNull(db.invoiceDraftDao().findById(DRAFT.value))
            db.writableSql.execSQL("DROP TRIGGER fail_matching_close")
            assertEquals(ConfirmInvoiceMatchingResult.Applied(1), repository.confirm(BUSINESS, PEN, DRAFT, listOf(item)))
            assertBalance(item.matchedProduct!!.productId, "2", "120")
        }

    @Test fun receiptRecognizesLostAckAndRejectsDivergentRetry() =
        runBlocking {
            val item = row(0)
            seedSource(listOf(item))
            assertEquals(ConfirmInvoiceMatchingResult.Applied(1), repository.confirm(BUSINESS, PEN, DRAFT, listOf(item)))
            assertEquals(ConfirmInvoiceMatchingResult.AlreadyApplied(1), repository.confirm(BUSINESS, PEN, DRAFT, listOf(item)))
            assertEquals(1, repository.findAppliedCount(BUSINESS, DRAFT))
            assertNull(repository.findAppliedCount(BusinessId.from(UUID(0, 999)), DRAFT))
            assertEquals(ConfirmInvoiceMatchingResult.DraftChanged, repository.confirm(BUSINESS, PEN, DRAFT, listOf(item.copy(quantity = BigDecimal("9")))))
            assertEquals(ConfirmInvoiceMatchingResult.DraftChanged, repository.confirm(BUSINESS, PEN, DRAFT, listOf(item.copy(matchedProduct = product(1)))))
            assertEquals(1L, count("stock_movements"))
            assertBalance(item.matchedProduct!!.productId, "2", "120")
        }

    @Test fun compatibleLegacyPartialCommitClosesWithoutAddingStockAgain() =
        runBlocking {
            val item = row(0).copy(status = MatchStatus.MANUAL_LINKED)
            products.create(item.matchedProduct!!)
            seedSource(listOf(item))
            inventory.addStock(BUSINESS, item.matchedProduct!!.productId, LOCATION, BigDecimal("2"), PEN, BigDecimal("120"), legacyKey(item))
            assertEquals(ConfirmInvoiceMatchingResult.Applied(1), repository.confirm(BUSINESS, PEN, DRAFT, listOf(item)))
            assertEquals(1L, count("stock_movements"))
            assertBalance(item.matchedProduct!!.productId, "2", "120")
        }

    @Test fun changedProductAfterLegacyPartialCommitRequiresReconciliation() =
        runBlocking {
            val original = row(0).copy(status = MatchStatus.MANUAL_LINKED)
            products.create(original.matchedProduct!!)
            val replacement = products.create(product(1))
            seedSource(listOf(original))
            inventory.addStock(BUSINESS, original.matchedProduct!!.productId, LOCATION, BigDecimal("2"), PEN, BigDecimal("120"), legacyKey(original))
            assertEquals(ConfirmInvoiceMatchingResult.LegacyConflict, repository.confirm(BUSINESS, PEN, DRAFT, listOf(original.copy(matchedProduct = replacement))))
            assertEquals(1L, count("stock_movements"))
            assertNull(db.inventoryDao().findBalance(BUSINESS.value, replacement.productId.value, LOCATION.value))
            assertNotNull(db.invoiceDraftDao().findById(DRAFT.value))
        }

    @Test fun originalForeignCurrencyAndUnknownUnitsBlockBeforeAnyWrite() =
        runBlocking {
            val item = row(0).copy(sourceCurrency = CurrencyCode.of("USD"), sourceUnitCode = null)
            seedSource(listOf(item))
            val result = repository.confirm(BUSINESS, PEN, DRAFT, listOf(item)) as ConfirmInvoiceMatchingResult.ReviewRequired
            assertTrue(result.issues.getValue(0).containsAll(setOf(InvoiceMatchingIssue.CURRENCY_MISMATCH, InvoiceMatchingIssue.UNIT_REQUIRED)))
            assertEquals(0L, count("products"))
            assertNotNull(db.invoiceDraftDao().findById(DRAFT.value))
        }

    @Test fun omittedSourceLineCannotDeleteUnreviewedEvidence() =
        runBlocking {
            val rows = listOf(row(0), row(1))
            seedSource(rows)
            assertEquals(ConfirmInvoiceMatchingResult.DraftChanged, repository.confirm(BUSINESS, PEN, DRAFT, rows.take(1)))
            assertEquals(0L, count("products"))
            assertEquals(2L, count("invoice_lines"))
        }

    @Test fun explicitlyAddedManualLineHasStableIdentityAndCanCompleteAnEmptyOcrDraft() =
        runBlocking {
            val item = row(0).copy(sourceLineId = null, manualLineId = LineId.from(UUID(0, 500)))
            assertEquals(ConfirmInvoiceMatchingResult.Applied(1), repository.confirm(BUSINESS, PEN, DRAFT, listOf(item)))
            assertEquals(ConfirmInvoiceMatchingResult.AlreadyApplied(1), repository.confirm(BUSINESS, PEN, DRAFT, listOf(item)))
            assertBalance(item.matchedProduct!!.productId, "2", "120")
            assertEquals(1L, count("stock_movements"))
        }

    @Test fun manualRowsCannotReplaceAnUnreviewedOriginalLine() =
        runBlocking {
            val source = row(0)
            seedSource(listOf(source))
            val manual = row(1).copy(sourceLineId = null, manualLineId = LineId.from(UUID(0, 500)))
            assertEquals(ConfirmInvoiceMatchingResult.DraftChanged, repository.confirm(BUSINESS, PEN, DRAFT, listOf(manual)))
            assertEquals(0L, count("products"))
            assertEquals(1L, count("invoice_lines"))
        }

    @Test fun inventoryIdempotencyRejectsChangedAmountUnderExistingKey() =
        runBlocking {
            val product = products.create(product(0))
            inventory.addStock(BUSINESS, product.productId, LOCATION, BigDecimal("2"), PEN, BigDecimal("120"), "test:stable-key")
            val failure = runCatching { inventory.addStock(BUSINESS, product.productId, LOCATION, BigDecimal("9"), PEN, BigDecimal("120"), "test:stable-key") }.exceptionOrNull()
            assertTrue(failure is StorageException)
            assertBalance(product.productId, "2", "120")
        }

    private suspend fun seedSource(items: List<ScannedItemMatch>) {
        val now = clock.now().toEpochMilli()
        db.invoiceLineDao().insertAll(items.map { InvoiceLineEntity(it.sourceLineId!!.value, DRAFT.value, BUSINESS.value, it.lineIndex, it.rawDescription, now, now, quantity = it.quantity?.toPlainString()) })
    }

    private fun product(index: Int) = Product(ProductId.from(UUID(0, 100L + index)), BUSINESS, UNIT, "Producto $index", locationId = LOCATION, createdAt = clock.now(), updatedAt = clock.now())

    private fun row(index: Int) = ScannedItemMatch(index, "Producto $index", BigDecimal("2"), BigDecimal("120"), matchedProduct = product(index), status = MatchStatus.CREATED_NEW, sourceLineId = LineId.from(UUID(0, 200L + index)), sourceCurrency = PEN, sourceUnitCode = "NIU")

    private fun legacyKey(item: ScannedItemMatch) = "invoice-match:v1:${DRAFT.value}:${item.lineIndex}:${item.matchedProduct!!.productId.value}"

    private suspend fun assertBalance(
        product: ProductId,
        quantity: String,
        cost: String,
    ) {
        val balance = requireNotNull(db.inventoryDao().findBalance(BUSINESS.value, product.value, LOCATION.value))
        assertEquals(0, BigDecimal(quantity).compareTo(balance.quantityOnHand.toBigDecimal()))
        assertEquals(0, BigDecimal(cost).compareTo(balance.averageUnitCost.toBigDecimal()))
    }

    private fun count(table: String): Long =
        db.readableSql.query("SELECT COUNT(*) FROM `$table`").use {
            it.moveToFirst()
            it.getLong(0)
        }

    private object NoFiles : DraftFileStore {
        override suspend fun deleteFiles(relativePaths: List<String>) = relativePaths.map { PrivateImageDeletionResult.DELETED }

        override suspend fun deleteDraftTree(draftId: DraftId) = Unit

        override suspend fun deleteDraftTreeIf(
            draftId: DraftId,
            pathIsReferencedAnywhere: suspend (String) -> Boolean,
            shouldDelete: suspend () -> Boolean,
        ) = shouldDelete()

        override suspend fun deleteOcrVersions(draftId: DraftId) = Unit
    }

    companion object {
        val BUSINESS = BusinessId.from(UUID(0, 1))
        val UNIT = UnitId.from(UUID(0, 2))
        val BOX = UnitId.from(UUID(0, 3))
        val LOCATION = LocationId.from(UUID(0, 4))
        val DRAFT = DraftId.from(UUID(0, 5))
        val PEN = CurrencyCode.of("PEN")
    }
}
