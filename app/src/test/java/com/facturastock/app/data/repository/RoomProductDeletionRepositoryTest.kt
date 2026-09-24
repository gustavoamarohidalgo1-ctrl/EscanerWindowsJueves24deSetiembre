package com.facturastock.app.data.repository

import com.facturastock.app.data.local.newFacturaStockDatabase
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import com.facturastock.app.data.local.readableSql
import com.facturastock.app.data.local.writableSql
import com.facturastock.app.data.local.TestCursor
import androidx.room.Room
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.codec.InvoiceLinesEditCodec
import com.facturastock.app.data.local.codec.PreparedPurchaseCodec
import com.facturastock.app.data.local.entity.BusinessEntity
import com.facturastock.app.data.local.entity.CatalogSyncLinkEntity
import com.facturastock.app.data.local.entity.CloudBusinessBindingEntity
import com.facturastock.app.data.local.entity.InventoryBalanceEntity
import com.facturastock.app.data.local.entity.InventoryLocationEntity
import com.facturastock.app.data.local.entity.InvoiceDraftEntity
import com.facturastock.app.data.local.entity.InvoiceLineEntity
import com.facturastock.app.data.local.entity.InvoiceLinesEditEntity
import com.facturastock.app.data.local.entity.OutboxOperationEntity
import com.facturastock.app.data.local.entity.PreparedPurchaseEntity
import com.facturastock.app.data.local.entity.PurchaseEntity
import com.facturastock.app.data.local.entity.SupplierEntity
import com.facturastock.app.data.local.entity.UnitEntity
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.InvoiceLineEdit
import com.facturastock.app.domain.model.InvoiceLineEditOrigin
import com.facturastock.app.domain.model.InvoiceLinesEdit
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.repository.CheckoutSaleCommand
import com.facturastock.app.domain.repository.CheckoutSaleResult
import com.facturastock.app.domain.repository.InventoryStockAddition
import com.facturastock.app.domain.repository.ProductDeletionResult
import com.facturastock.app.domain.repository.SaleCartMutationResult
import com.facturastock.app.domain.repository.SaveSaleCartLineCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Borrado real, sin debilitar triggers ni modificar historia, sobre SQLite/Room. */
class RoomProductDeletionRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var database: FacturaStockDatabase
    private lateinit var products: RoomProductRepository
    private val clock = TestClock(Instant.parse("2026-09-05T12:00:00Z"))
    private val ids = UuidGenerator { UUID.randomUUID() }
    private val now get() = clock.now().toEpochMilli()

    @Before
    fun setUp() =
        runBlocking {
            database =
                tempFolder.newFacturaStockDatabase()
            products = RoomProductRepository(database, database.productDao(), testDispatchers, clock)
            seedBusiness(BUSINESS, UNIT, LOCATION)
            database.inventoryLocationDao().insert(
                InventoryLocationEntity(SECOND_LOCATION.value, BUSINESS.value, "Secundario", now, now),
            )
        }

    @After
    fun tearDown() = database.close()

    @Test
    fun deletesUnusedProductAndAllUnsentVersionsAllowingCodesOnANewIdentity() =
        runBlocking {
            val original = createProduct(sku = "REUSABLE", barcode = "7750001234567")
            assertTrue(products.update(original.copy(name = "Nombre corregido")))
            val current = requireNotNull(products.findById(original.productId))
            assertEquals(2, rows("outbox_operations").size)
            assertEquals(ProductDeletionResult.DELETED, delete(current))
            assertNull(products.findById(current.productId))
            assertTrue(rows("outbox_operations").isEmpty())
            assertEquals(ProductDeletionResult.NOT_FOUND, delete(current))
            val replacement = createProduct(sku = "REUSABLE", barcode = "7750001234567")
            assertNotEquals(current.productId, replacement.productId)
            assertEquals(replacement, products.findBySku(BUSINESS, "REUSABLE"))
            assertEquals(replacement, products.findByBarcode(BUSINESS, "7750001234567"))
            assertFalse(database.outboxOperationDao().hasProductOperations(BUSINESS.value, current.productId.value))
            assertTrue(database.outboxOperationDao().hasProductOperations(BUSINESS.value, replacement.productId.value))
            assertEquals(1, rows("outbox_operations").size)
            assertIntegrity()
        }

    @Test
    fun deletesOnlyZeroPositionsAndPreservesOtherProductsBalancesAndMessages() =
        runBlocking {
            val target = createProduct()
            val other = createProduct()
            val resolvedDocument = insertCancelledLocalDocument()
            insertBalance(target, "0.000", "987654321.123456789012345678901234567890123456")
            insertBalance(target, "0", "3", SECOND_LOCATION, "USD")
            insertBalance(other, "12", "4")
            val otherBalance = database.inventoryDao().listBalancesForProduct(BUSINESS.value, other.productId.value)
            val otherOutbox = database.outboxOperationDao().findLatestForEntity(BUSINESS.value, "PRODUCT", other.productId.value)
            assertEquals(ProductDeletionResult.DELETED, delete(target))
            assertTrue(database.inventoryDao().listBalancesForProduct(BUSINESS.value, target.productId.value).isEmpty())
            assertEquals(otherBalance, database.inventoryDao().listBalancesForProduct(BUSINESS.value, other.productId.value))
            assertEquals(otherOutbox, database.outboxOperationDao().findLatestForEntity(BUSINESS.value, "PRODUCT", other.productId.value))
            assertEquals(other, products.findById(other.productId))
            assertEquals(resolvedDocument, database.outboxOperationDao().findById(resolvedDocument.operationId))
            assertIntegrity()
        }

    @Test
    fun nonzeroBalancesNeverCancelAcrossLocationsAndConcurrentStockIsRechecked() =
        runBlocking {
            for (quantity in listOf("1", "-1", "0.000000000000000000000000000000000001")) {
                val target = createProduct()
                insertBalance(target, quantity, "2")
                insertBalance(target, BigDecimal(quantity).negate().toPlainString(), "2", SECOND_LOCATION)
                val before = relevantRows()
                assertEquals(ProductDeletionResult.HAS_STOCK, delete(target))
                assertEquals(before, relevantRows())
            }
            val shownInDialog = createProduct()
            addStock(shownInDialog, "1")
            // El ingreso no cambia product.version; debe releerse también la historia/saldo.
            assertEquals(shownInDialog.version, products.findById(shownInDialog.productId)!!.version)
            val before = relevantRows()
            assertEquals(ProductDeletionResult.HAS_HISTORY, delete(shownInDialog))
            assertEquals(before, relevantRows())
            assertIntegrity()
        }

    @Test
    fun rejectedDeletionCanArchiveAndRestoreExhaustedProductWithoutChangingSaleOrDebt() =
        runBlocking {
            val target = createProduct()
            addStock(target, "2")
            val sales = RoomSaleRepository(database, clock, ids, testDispatchers)
            val cart = sales.createOrResume(BUSINESS, PEN).cart
            val saved =
                sales.saveLine(
                    BUSINESS,
                    SaveSaleCartLineCommand(
                        saleId = cart.saleId,
                        expectedVersion = cart.version,
                        productId = target.productId,
                        locationId = LOCATION,
                        quantity = Quantity.of("2"),
                        unitPrice = Money.ofMinor(500, PEN),
                    ),
                ) as SaleCartMutationResult.Saved
            assertEquals(
                CheckoutSaleResult.Posted(cart.saleId),
                sales.checkout(BUSINESS, CheckoutSaleCommand(saved.cart.saleId, saved.cart.version, saved.cart.contentHash, debtorName = "Cliente")),
            )
            assertEquals(0, BigDecimal(database.inventoryDao().findBalance(BUSINESS.value, target.productId.value, LOCATION.value)!!.quantityOnHand).signum())
            assertEquals(2, rows("stock_movements").size)
            assertEquals(1, rows("debts").size)
            val before = relevantRows()
            assertEquals(ProductDeletionResult.HAS_HISTORY, delete(target))
            assertEquals(before, relevantRows())

            // El fallback usa la misma revisión: el borrado rechazado no debe consumir su
            // versión ni retirar datos que el archivo/restauración necesitan conservar.
            val history = before.filterKeys { it != "products" && it != "outbox_operations" }
            assertTrue(products.archive(BUSINESS, target.productId, target.version))
            val archived = requireNotNull(products.findById(target.productId))
            assertEquals(target.copy(status = CatalogStatus.ARCHIVED, version = target.version + 1), archived)
            assertEquals(history, relevantRows().filterKeys { it in history })
            assertRejectedUnchanged(archived, ProductDeletionResult.HAS_HISTORY)

            assertTrue(products.restore(BUSINESS, target.productId, archived.version))
            assertEquals(target.copy(version = target.version + 2), products.findById(target.productId))
            assertEquals(history, relevantRows().filterKeys { it in history })
            assertIntegrity()
        }

    @Test
    fun draftReferencesAndRestorableOrUnreadableSnapshotsRemainUntouched() =
        runBlocking {
            val linked = createProduct()
            val linkedDraft = createDraft()
            database.invoiceLineDao().insert(
                InvoiceLineEntity(UUID.randomUUID().toString(), linkedDraft.value, BUSINESS.value, 0, "Factura", now, now, productId = linked.productId.value),
            )
            val retired = createProduct()
            val retiredDraft = createDraft()
            val snapshot =
                InvoiceLinesEdit(
                    draftId = retiredDraft,
                    lines =
                        listOf(
                            InvoiceLineEdit(
                                lineId = LineId.from(UUID.randomUUID()),
                                position = 0,
                                origin = InvoiceLineEditOrigin.USER,
                                linkedProductId = retired.productId,
                                deletedAt = clock.now(),
                                createdAt = clock.now(),
                                updatedAt = clock.now(),
                            ),
                        ),
                    revision = 1L,
                    updatedAt = clock.now(),
                )
            val payload = InvoiceLinesEditCodec.encode(snapshot)
            database.invoiceLinesEditDao().insert(
                InvoiceLinesEditEntity(
                    retiredDraft.value,
                    snapshot.revision,
                    InvoiceLinesEditCodec.VERSION,
                    InvoiceLinesEditCodec.sha256(payload),
                    payload,
                    now,
                ),
            )
            val unreadable = createProduct()
            val unreadableDraft = createDraft()
            val corruptPayload = unreadable.productId.value.toByteArray(Charsets.UTF_8)
            database.preparedPurchaseDao().upsert(
                PreparedPurchaseEntity(
                    unreadableDraft.value,
                    "0".repeat(64),
                    PreparedPurchaseCodec.VERSION,
                    PreparedPurchaseCodec.sha256(corruptPayload),
                    corruptPayload,
                    now,
                ),
            )
            val before = relevantRows()
            for (target in listOf(linked, retired, unreadable)) {
                assertEquals(ProductDeletionResult.HAS_HISTORY, delete(target))
                assertEquals(before, relevantRows())
            }
            assertEquals(snapshot, InvoiceLinesEditCodec.decode(database.invoiceLinesEditDao().findByDraftId(retiredDraft.value)!!.payload))
            assertIntegrity()
        }

    @Test
    fun tenantAndVersionChecksProtectDataAndConcurrentEditHasExactlyOneWinner() =
        runBlocking {
            val original = createProduct()
            val before = relevantRows()
            assertEquals(ProductDeletionResult.NOT_FOUND, products.deletePermanently(OTHER_BUSINESS, original.productId, original.version))
            assertEquals(ProductDeletionResult.STALE, products.deletePermanently(BUSINESS, original.productId, original.version + 1))
            assertEquals(ProductDeletionResult.STALE, products.deletePermanently(BUSINESS, original.productId, 0))
            assertEquals(before, relevantRows())
            val edit = async(Dispatchers.Default) { products.update(original.copy(name = "Edición concurrente")) }
            val removal = async(Dispatchers.Default) { delete(original) }
            val edited = edit.await()
            val deleted = removal.await()
            assertEquals(1, listOf(edited, deleted == ProductDeletionResult.DELETED).count { it })
            if (edited) {
                assertEquals(ProductDeletionResult.STALE, deleted)
                assertEquals("Edición concurrente", products.findById(original.productId)!!.name)
            } else {
                assertNull(products.findById(original.productId))
                assertFalse(database.outboxOperationDao().hasProductOperations(BUSINESS.value, original.productId.value))
            }
            assertIntegrity()
        }

    @Test
    fun cloudBindingMappingAndAttemptedOutboxCannotBeDeletedOrCancelledLocally() =
        runBlocking {
            val bound = createProduct()
            database.cloudBusinessBindingDao().insert(CloudBusinessBindingEntity(BUSINESS.value, UUID.randomUUID().toString(), now, 0))
            assertRejectedUnchanged(bound, ProductDeletionResult.SHARED_BUSINESS)
            val mappedBusiness = BusinessId.from(UUID.randomUUID())
            val mappedUnit = UnitId.from(UUID.randomUUID())
            val mappedLocation = LocationId.from(UUID.randomUUID())
            seedBusiness(mappedBusiness, mappedUnit, mappedLocation)
            val mapped = createProduct(business = mappedBusiness, unit = mappedUnit, location = mappedLocation)
            database.catalogSyncLinkDao().insert(
                CatalogSyncLinkEntity(
                    mappedBusiness.value,
                    UUID.randomUUID().toString(),
                    "PRODUCT",
                    mapped.productId.value,
                    UUID.randomUUID().toString(),
                    1L,
                    now,
                    now,
                ),
            )
            assertRejectedUnchanged(mapped, ProductDeletionResult.SHARED_BUSINESS)
            val attemptedUnit = UnitId.from(UUID.randomUUID())
            val attemptedLocation = LocationId.from(UUID.randomUUID())
            seedBusiness(OTHER_BUSINESS, attemptedUnit, attemptedLocation)
            val attempted = createProduct(business = OTHER_BUSINESS, unit = attemptedUnit, location = attemptedLocation)
            // Reproduce una operación legada enviada antes de persistirse los bindings actuales.
            database.writableSql.execSQL(
                "UPDATE outbox_operations SET status = 'COMPLETED', attemptCount = 1, completedAt = updatedAt WHERE entityId = ?",
                arrayOf(attempted.productId.value),
            )
            assertRejectedUnchanged(attempted, ProductDeletionResult.SHARED_BUSINESS)
            assertIntegrity()
        }

    @Test
    fun failureAfterDeleteRollsBackProductZeroBalancesAndAllCancelledOperations() =
        runBlocking {
            val target = createProduct()
            insertBalance(target, "0", "7.123456789012345678901234567890123456")
            assertTrue(products.update(target.copy(name = "Versión siguiente")))
            val current = products.findById(target.productId)!!
            val before = relevantRows()
            database.writableSql.execSQL(
                "CREATE TRIGGER test_fail_product_delete AFTER DELETE ON products BEGIN SELECT RAISE(ABORT, 'controlled failure after cleanup'); END",
            )
            try {
                delete(current)
                throw AssertionError("El borrado debía fallar y revertir la limpieza")
            } catch (_: StorageException) {
                assertEquals(before, relevantRows())
            } finally {
                database.writableSql.execSQL("DROP TRIGGER test_fail_product_delete")
            }
            assertEquals(ProductDeletionResult.DELETED, delete(current))
            assertIntegrity()
        }

    private suspend fun seedBusiness(
        business: BusinessId,
        unit: UnitId,
        location: LocationId,
    ) {
        database.businessDao().insert(BusinessEntity(business.value, "Negocio", now, now))
        database.unitDao().insert(UnitEntity(unit.value, business.value, "NIU", "Unidad", now, now))
        database.inventoryLocationDao().insert(InventoryLocationEntity(location.value, business.value, "Principal", now, now))
    }

    private suspend fun createProduct(
        sku: String? = null,
        barcode: String? = null,
        business: BusinessId = BUSINESS,
        unit: UnitId = UNIT,
        location: LocationId = LOCATION,
    ): Product =
        products.create(
            Product(
                productId = ProductId.from(UUID.randomUUID()),
                businessId = business,
                unitId = unit,
                name = "Producto",
                sku = sku,
                barcode = barcode,
                locationId = location,
                salePrice = Money.ofMinor(500, PEN),
                createdAt = clock.now(),
                updatedAt = clock.now(),
            ),
        )

    private suspend fun insertBalance(
        product: Product,
        quantity: String,
        cost: String,
        location: LocationId = LOCATION,
        currency: String = "PEN",
    ) {
        database.inventoryDao().insertBalanceIfAbsent(
            InventoryBalanceEntity(
                product.businessId.value,
                product.productId.value,
                location.value,
                quantity,
                cost,
                currency,
                1L,
                now,
            ),
        )
    }

    private suspend fun addStock(
        product: Product,
        quantity: String,
    ) {
        RoomProductInventoryRepository(database, database.inventoryDao(), testDispatchers, clock, ids).addStockBatch(
            product.businessId,
            PEN,
            listOf(InventoryStockAddition(product.productId, LOCATION, BigDecimal(quantity), BigDecimal("2"), "delete-test:${product.productId.value}")),
        )
    }

    private suspend fun createDraft(): DraftId =
        DraftId.from(UUID.randomUUID()).also {
            database.invoiceDraftDao().insert(InvoiceDraftEntity(it.value, BUSINESS.value, now, now))
        }

    private suspend fun insertCancelledLocalDocument(): OutboxOperationEntity {
        val draft = createDraft()
        val supplierId = UUID.randomUUID().toString()
        val purchaseId = UUID.randomUUID().toString()
        database.supplierDao().insert(SupplierEntity(supplierId, BUSINESS.value, "Proveedor", now, now))
        database.purchaseDao().insert(
            PurchaseEntity(
                purchaseId = purchaseId,
                businessId = BUSINESS.value,
                sourceDraftId = draft.value,
                supplierId = supplierId,
                documentType = "INVOICE",
                documentSeries = "F001",
                documentNumber = "1",
                issueDate = "2026-09-05",
                currencyCode = "PEN",
                subtotalMinorUnits = 0L,
                taxMinorUnits = 0L,
                otherChargesMinorUnits = 0L,
                totalMinorUnits = 0L,
                idempotencyKey = "document-local:$purchaseId",
                createdAt = now,
                updatedAt = now,
            ),
        )
        val documentId = UUID.randomUUID().toString()
        return OutboxOperationEntity(
            operationId = UUID.randomUUID().toString(),
            businessId = BUSINESS.value,
            purchaseId = purchaseId,
            idempotencyKey = "document-upload:$documentId",
            operationType = "SYNC_DOCUMENT_UPLOAD",
            entityType = "DOCUMENT",
            entityId = documentId,
            payload = "{}",
            status = "RESOLVED",
            createdAt = now,
            updatedAt = now,
        ).also { database.outboxOperationDao().insert(it) }
    }

    private suspend fun delete(product: Product) = products.deletePermanently(product.businessId, product.productId, product.version)

    private suspend fun assertRejectedUnchanged(
        product: Product,
        result: ProductDeletionResult,
    ) {
        val before = relevantRows()
        assertEquals(result, delete(product))
        assertEquals(before, relevantRows())
    }

    private fun relevantRows(): Map<String, List<List<String?>>> =
        listOf(
            "products",
            "inventory_balances",
            "stock_movements",
            "sales",
            "sale_lines",
            "debts",
            "debt_payments",
            "purchases",
            "purchase_lines",
            "audit_events",
            "outbox_operations",
            "invoice_drafts",
            "invoice_lines",
            "invoice_line_edits",
            "prepared_purchases",
            "supplier_product_aliases",
            "catalog_sync_links",
            "cloud_business_bindings",
        ).associateWith(::rows)

    private fun rows(table: String): List<List<String?>> =
        database.readableSql.query("SELECT * FROM $table ORDER BY rowid").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        List(cursor.columnCount) { column ->
                            when (cursor.getType(column)) {
                                TestCursor.FIELD_TYPE_NULL -> null
                                TestCursor.FIELD_TYPE_INTEGER -> "integer:${cursor.getLong(column)}"
                                TestCursor.FIELD_TYPE_FLOAT -> "real:${cursor.getDouble(column)}"
                                TestCursor.FIELD_TYPE_BLOB -> "blob:${cursor.getBlob(column)!!.joinToString { it.toString() }}"
                                else -> "text:${cursor.getString(column)}"
                            }
                        },
                    )
                }
            }
        }

    private fun assertIntegrity() {
        database.readableSql.query("PRAGMA integrity_check").use {
            assertTrue(it.moveToFirst())
            assertEquals("ok", it.getString(0))
        }
        database.readableSql
            .query("PRAGMA foreign_key_check")
            .use { assertEquals(0, it.count) }
    }

    private companion object {
        val BUSINESS = BusinessId.from(UUID(0, 1))
        val OTHER_BUSINESS = BusinessId.from(UUID(0, 2))
        val UNIT = UnitId.from(UUID(0, 3))
        val LOCATION = LocationId.from(UUID(0, 4))
        val SECOND_LOCATION = LocationId.from(UUID(0, 5))
        val PEN = CurrencyCode.of("PEN")
    }
}
