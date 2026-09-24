package com.facturastock.app.data.repository

import com.facturastock.app.data.local.newFacturaStockDatabase
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import com.facturastock.app.data.local.readableSql
import com.facturastock.app.data.local.writableSql
import com.facturastock.app.data.local.TestCursor
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.entity.BusinessEntity
import com.facturastock.app.data.local.entity.InventoryLocationEntity
import com.facturastock.app.data.local.entity.UnitEntity
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.CatalogSearch
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.InventoryDataAlert
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.repository.CheckoutSaleCommand
import com.facturastock.app.domain.repository.CheckoutSaleResult
import com.facturastock.app.domain.repository.InventoryStockAddition
import com.facturastock.app.domain.repository.SaleCartMutationResult
import com.facturastock.app.domain.repository.SaveSaleCartLineCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Las operaciones del catálogo se prueban contra un libro real con una venta publicada. */
class RoomProductLifecycleRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var database: FacturaStockDatabase
    private lateinit var products: RoomProductRepository
    private lateinit var inventory: RoomInventoryReadRepository
    private val clock = TestClock(Instant.parse("2026-09-05T12:00:00Z"))
    private val ids = UuidGenerator { UUID.randomUUID() }

    @Before
    fun setUp() =
        runBlocking {
            database =
                tempFolder.newFacturaStockDatabase()
            products = RoomProductRepository(database, database.productDao(), testDispatchers, clock)
            inventory = RoomInventoryReadRepository(database, clock, testDispatchers)
            val now = clock.now().toEpochMilli()
            database.businessDao().insert(BusinessEntity(BUSINESS.value, "Negocio", now, now))
            database.unitDao().insert(UnitEntity(UNIT.value, BUSINESS.value, "NIU", "Unidad", now, now))
            database.unitDao().insert(UnitEntity(BOX.value, BUSINESS.value, "CJA", "Caja", now, now))
            database.inventoryLocationDao().insert(
                InventoryLocationEntity(LOCATION.value, BUSINESS.value, "Principal", now, now),
            )
            database.inventoryLocationDao().insert(
                InventoryLocationEntity(SECOND_LOCATION.value, BUSINESS.value, "Secundario", now, now),
            )
            products.create(
                Product(
                    productId = PRODUCT,
                    businessId = BUSINESS,
                    unitId = UNIT,
                    name = "Producto original",
                    sku = "ORIGINAL",
                    locationId = LOCATION,
                    salePrice = Money.ofMinor(500L, PEN),
                    createdAt = clock.now(),
                    updatedAt = clock.now(),
                ),
            )
            RoomProductInventoryRepository(database, database.inventoryDao(), testDispatchers, clock, ids)
                .addStockBatch(
                    BUSINESS,
                    PEN,
                    listOf(InventoryStockAddition(PRODUCT, LOCATION, BigDecimal("10"), BigDecimal("2"), "lifecycle-stock")),
                )
            val sales = RoomSaleRepository(database, clock, ids, testDispatchers)
            val cart = sales.createOrResume(BUSINESS, PEN).cart
            val saved =
                sales.saveLine(
                    BUSINESS,
                    SaveSaleCartLineCommand(
                        saleId = cart.saleId,
                        expectedVersion = cart.version,
                        productId = PRODUCT,
                        locationId = LOCATION,
                        quantity = Quantity.of("2"),
                        unitPrice = Money.ofMinor(500L, PEN),
                    ),
                ) as SaleCartMutationResult.Saved
            assertEquals(
                CheckoutSaleResult.Posted(cart.saleId),
                sales.checkout(
                    BUSINESS,
                    CheckoutSaleCommand(saved.cart.saleId, saved.cart.version, saved.cart.contentHash, debtorName = "Cliente"),
                ),
            )
        }

    @After
    fun tearDown() = database.close()

    @Test
    fun archiveAndRestoreKeepStockMovementsPostedSalesAndDebtExactly() =
        runBlocking {
            val before = storedProduct()
            val ledger = ledgerRows()
            assertEquals(1, persistedRows("debts").size)
            assertEquals(2, persistedRows("stock_movements").size)

            assertTrue(products.archive(BUSINESS, PRODUCT, before.version))
            val archived = storedProduct()
            assertEquals(before.copy(status = CatalogStatus.ARCHIVED, version = before.version + 1), archived)
            assertEquals(ledger, ledgerRows())
            assertTrue(products.search(BUSINESS, CatalogSearch(status = CatalogStatus.ACTIVE)).items.isEmpty())
            assertEquals(
                PRODUCT,
                products
                    .search(BUSINESS, CatalogSearch(status = CatalogStatus.ARCHIVED))
                    .items
                    .single()
                    .productId,
            )
            assertTrue(products.searchActiveByName(BUSINESS, "Producto", 10).isEmpty())
            // La proyección histórica conserva el saldo; la UI decide qué estados mostrar.
            val archivedInventory = inventory.observeInventory(BUSINESS).first().single()
            assertTrue(InventoryDataAlert.ARCHIVED_PRODUCT in archivedInventory.alerts)
            assertEquals(
                0,
                archivedInventory.positions
                    .single()
                    .quantityOnHand
                    .compareTo(BigDecimal("8")),
            )
            assertEquals(
                2,
                inventory
                    .observeProduct(BUSINESS, PRODUCT)
                    .first()!!
                    .movements.size,
            )

            val archivedOutbox = persistedRows("outbox_operations")
            assertTrue(products.archive(BUSINESS, PRODUCT, archived.version))
            assertEquals(archived, storedProduct())
            assertEquals(archivedOutbox, persistedRows("outbox_operations"))
            assertTrue(products.restore(BUSINESS, PRODUCT, archived.version))
            val restored = storedProduct()
            assertEquals(before.copy(version = before.version + 2), restored)
            assertEquals(ledger, ledgerRows())
            assertEquals(
                PRODUCT,
                products
                    .search(BUSINESS, CatalogSearch(status = CatalogStatus.ACTIVE))
                    .items
                    .single()
                    .productId,
            )
            assertFalse(
                InventoryDataAlert.ARCHIVED_PRODUCT in
                    inventory
                        .observeInventory(BUSINESS)
                        .first()
                        .single()
                        .alerts,
            )
            assertIntegrity()
        }

    @Test
    fun staleOrForeignStatusIntentCannotOverrideANewerEditOrRestoration() =
        runBlocking {
            val original = storedProduct()
            assertTrue(products.update(original.copy(name = "Nombre corregido")))
            val edited = storedProduct()
            val beforeRejected = persistedRows("outbox_operations")
            assertFalse(products.archive(BUSINESS, PRODUCT, original.version))
            assertFalse(products.archive(OTHER_BUSINESS, PRODUCT, edited.version))
            assertFalse(products.archive(BUSINESS, PRODUCT, 0L))
            assertEquals(edited, storedProduct())
            assertEquals(beforeRejected, persistedRows("outbox_operations"))

            assertTrue(products.archive(BUSINESS, PRODUCT, edited.version))
            val archived = storedProduct()
            assertTrue(products.update(archived.copy(name = "Nombre archivado corregido")))
            val updatedArchived = storedProduct()
            assertFalse(products.restore(BUSINESS, PRODUCT, archived.version))
            assertEquals(updatedArchived, storedProduct())
            assertTrue(products.restore(BUSINESS, PRODUCT, updatedArchived.version))
            val restored = storedProduct()
            // Un diálogo de eliminación anterior no vuelve a ocultar un producto ya restaurado.
            assertFalse(products.archive(BUSINESS, PRODUCT, edited.version))
            assertEquals(restored, storedProduct())
            assertEquals("Nombre archivado corregido", restored.name)
        }

    @Test
    fun concurrentEditAndArchiveFromOneVersionHaveExactlyOneWinner() =
        runBlocking {
            val original = storedProduct()
            val ledger = ledgerRows()
            val outcomes =
                listOf(
                    async(Dispatchers.Default) { products.update(original.copy(name = "Edición concurrente")) },
                    async(Dispatchers.Default) { products.archive(BUSINESS, PRODUCT, original.version) },
                ).awaitAll()
            assertEquals(1, outcomes.count { it })
            val stored = storedProduct()
            assertEquals(original.version + 1, stored.version)
            assertEquals(if (outcomes.first()) "Edición concurrente" else original.name, stored.name)
            assertEquals(if (outcomes.first()) CatalogStatus.ACTIVE else CatalogStatus.ARCHIVED, stored.status)
            assertEquals(2, persistedRows("outbox_operations").size)
            assertEquals(ledger, ledgerRows())
        }

    @Test
    fun editingMetadataPreservesLedgerAndCannotReinterpretTheInventoryUnit() =
        runBlocking {
            val ledger = ledgerRows()
            val original = storedProduct()
            assertTrue(
                products.update(
                    original.copy(name = "Producto corregido", sku = "CORREGIDO", locationId = SECOND_LOCATION, salePrice = Money.ofMinor(650L, PEN)),
                ),
            )
            val edited = storedProduct()
            assertEquals(ledger, ledgerRows())
            val detail = inventory.observeProduct(BUSINESS, PRODUCT).first()!!
            assertEquals("Producto corregido", detail.item.productName)
            assertEquals(
                LOCATION,
                detail.item.positions
                    .single()
                    .locationId,
            )
            val outboxBefore = persistedRows("outbox_operations")
            expectStorageFailure { products.update(edited.copy(unitId = BOX)) }
            assertEquals(edited, storedProduct())
            assertEquals(outboxBefore, persistedRows("outbox_operations"))
            assertEquals(ledger, ledgerRows())
            // La versión previa no puede recuperar los campos antiguos encima de la edición nueva.
            assertFalse(products.update(original.copy(name = "Edición obsoleta")))
            assertEquals(edited, storedProduct())
        }

    @Test
    fun lateOutboxFailureRollsBackArchiveAndRestoreWithoutTouchingHistory() =
        runBlocking {
            val ledger = ledgerRows()
            for (target in listOf(CatalogStatus.ARCHIVED, CatalogStatus.ACTIVE)) {
                val original = storedProduct()
                val outbox = persistedRows("outbox_operations")
                database.writableSql.execSQL(
                    "CREATE TRIGGER lifecycle_reject_outbox BEFORE INSERT ON outbox_operations " +
                        "BEGIN SELECT RAISE(ABORT, 'synthetic late outbox failure'); END",
                )
                expectStorageFailure {
                    if (target == CatalogStatus.ARCHIVED) {
                        products.archive(BUSINESS, PRODUCT, original.version)
                    } else {
                        products.restore(BUSINESS, PRODUCT, original.version)
                    }
                }
                assertEquals(original, storedProduct())
                assertEquals(outbox, persistedRows("outbox_operations"))
                assertEquals(ledger, ledgerRows())
                database.writableSql.execSQL("DROP TRIGGER lifecycle_reject_outbox")
                assertTrue(
                    if (target == CatalogStatus.ARCHIVED) {
                        products.archive(BUSINESS, PRODUCT, original.version)
                    } else {
                        products.restore(BUSINESS, PRODUCT, original.version)
                    },
                )
            }
            assertEquals(ledger, ledgerRows())
        }

    @Test
    fun statusReadbackMismatchCannotReportSuccessOrPublishOutbox() =
        runBlocking {
            val original = storedProduct()
            val outbox = persistedRows("outbox_operations")
            val ledger = ledgerRows()
            database.writableSql.execSQL(
                "CREATE TRIGGER lifecycle_alter_status_write AFTER UPDATE OF status ON products " +
                    "BEGIN UPDATE products SET updatedAt = NEW.updatedAt + 1 WHERE productId = NEW.productId; END",
            )
            val failure = expectStorageFailure { products.archive(BUSINESS, PRODUCT, original.version) }
            assertEquals(StorageError.Unavailable, failure.error)
            assertEquals(original, storedProduct())
            assertEquals(outbox, persistedRows("outbox_operations"))
            assertEquals(ledger, ledgerRows())
        }

    @Test
    fun newlySelectedArchivedReferencesAreRejectedInsideTheWriteTransaction() =
        runBlocking {
            val original = storedProduct()
            database.unitDao().setStatus(BOX.value, CatalogStatus.ARCHIVED.name, clock.now().toEpochMilli())
            database.inventoryLocationDao().setStatus(SECOND_LOCATION.value, CatalogStatus.ARCHIVED.name, clock.now().toEpochMilli())
            val outbox = persistedRows("outbox_operations")
            expectStorageFailure { products.update(original.copy(purchaseUnitId = BOX, purchaseFactor = BigDecimal("12"))) }
            expectStorageFailure { products.update(original.copy(locationId = SECOND_LOCATION)) }
            assertEquals(original, storedProduct())
            assertEquals(outbox, persistedRows("outbox_operations"))
            // Conservar una referencia histórica archivada sigue permitiendo corregir nombre/precio.
            database.inventoryLocationDao().setStatus(LOCATION.value, CatalogStatus.ARCHIVED.name, clock.now().toEpochMilli())
            assertTrue(products.update(original.copy(name = "Corrección con referencia histórica")))
            assertEquals(LOCATION, storedProduct().locationId)
        }

    private suspend fun storedProduct(): Product = requireNotNull(products.findById(PRODUCT))

    private fun ledgerRows(): Map<String, List<List<String?>>> =
        listOf("inventory_balances", "stock_movements", "sales", "sale_lines", "debts", "debt_payments", "audit_events")
            .associateWith(::persistedRows)

    private fun persistedRows(table: String): List<List<String?>> =
        database.readableSql.query("SELECT * FROM $table ORDER BY rowid").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        List(cursor.columnCount) { index ->
                            when (cursor.getType(index)) {
                                TestCursor.FIELD_TYPE_NULL -> null
                                TestCursor.FIELD_TYPE_INTEGER -> "integer:${cursor.getLong(index)}"
                                TestCursor.FIELD_TYPE_FLOAT -> "real:${cursor.getDouble(index)}"
                                TestCursor.FIELD_TYPE_BLOB -> "blob:${cursor.getBlob(index)!!.joinToString { it.toString() }}"
                                else -> "text:${cursor.getString(index)}"
                            }
                        },
                    )
                }
            }
        }

    private suspend fun expectStorageFailure(action: suspend () -> Any?): StorageException {
        try {
            action()
        } catch (failure: StorageException) {
            return failure
        }
        throw AssertionError("La operación debía fallar sin publicar cambios")
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
        val BOX = UnitId.from(UUID(0, 4))
        val LOCATION = LocationId.from(UUID(0, 5))
        val SECOND_LOCATION = LocationId.from(UUID(0, 6))
        val PRODUCT = ProductId.from(UUID(0, 7))
        val PEN = CurrencyCode.of("PEN")
    }
}
