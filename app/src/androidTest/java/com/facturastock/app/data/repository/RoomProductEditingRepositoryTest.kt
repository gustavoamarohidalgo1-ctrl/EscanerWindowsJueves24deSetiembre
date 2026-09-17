package com.facturastock.app.data.repository

import android.content.Context
import android.database.Cursor
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.entity.BusinessEntity
import com.facturastock.app.data.local.entity.CloudBusinessBindingEntity
import com.facturastock.app.data.local.entity.InventoryLocationEntity
import com.facturastock.app.data.local.entity.UnitEntity
import com.facturastock.app.data.local.postingPersistenceCallback
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.CatalogDuplicateField
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.InventoryDiagnosticIssue
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.repository.CheckoutSaleCommand
import com.facturastock.app.domain.repository.CheckoutSaleResult
import com.facturastock.app.domain.repository.ProductEditingInvalidField
import com.facturastock.app.domain.repository.ProductEditingResult
import com.facturastock.app.domain.repository.ProductEditingSnapshot
import com.facturastock.app.domain.repository.ProductStockEdit
import com.facturastock.app.domain.repository.SaleCartMutationResult
import com.facturastock.app.domain.repository.SaveSaleCartLineCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RoomProductEditingRepositoryTest {
    private lateinit var database: FacturaStockDatabase
    private lateinit var editor: RoomProductEditingRepository
    private lateinit var products: RoomProductRepository
    private lateinit var stock: RoomProductInventoryRepository
    private val clock = TestClock(Instant.parse("2026-09-05T20:00:00Z"))
    private val ids = UuidGenerator { UUID.randomUUID() }

    @Before
    fun setUp() =
        runBlocking {
            database =
                Room
                    .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), FacturaStockDatabase::class.java)
                    .allowMainThreadQueries()
                    .addCallback(postingPersistenceCallback)
                    .build()
            products = RoomProductRepository(database, database.productDao(), testDispatchers, clock)
            stock = RoomProductInventoryRepository(database, database.inventoryDao(), testDispatchers, clock, ids)
            editor = RoomProductEditingRepository(database, testDispatchers, clock, ids)
            val now = clock.now().toEpochMilli()
            database.businessDao().insert(BusinessEntity(BUSINESS.value, "Negocio", now, now))
            database.unitDao().insert(UnitEntity(UNIT.value, BUSINESS.value, "NIU", "Unidad", now, now))
            for ((location, name) in listOf(LOCATION to "Principal", USD_LOCATION to "Dólares", EMPTY_LOCATION to "Sin saldo")) {
                database.inventoryLocationDao().insert(InventoryLocationEntity(location.value, BUSINESS.value, name, now, now))
            }
            products.create(
                Product(
                    PRODUCT,
                    BUSINESS,
                    UNIT,
                    "Original",
                    locationId = LOCATION,
                    sku = "ORIGINAL",
                    barcode = "000123",
                    salePrice = Money.ofMinor(500, PEN),
                    createdAt = clock.now(),
                    updatedAt = clock.now(),
                ),
            )
            stock.addStock(BUSINESS, PRODUCT, LOCATION, BigDecimal.TEN, PEN, BigDecimal("2"), "editing-initial")
            stock.addStock(BUSINESS, PRODUCT, USD_LOCATION, BigDecimal("4"), USD, BigDecimal("1.25"), "editing-usd")
            postSale()
        }

    @After
    fun tearDown() = database.close()

    @Test
    fun loadPreloadsEachCurrentBalanceAndCostWithoutInventingAnAbsentBalance() =
        runBlocking {
            val loaded = snapshot()
            assertTrue(loaded.inventoryEditable)
            assertEquals(3, loaded.positions.size)
            assertEquals(PRODUCT, loaded.product.productId)
            val current = loaded.positions.single { it.locationId == LOCATION }
            assertDecimal("8", current.quantityOnHand)
            assertDecimal("2", requireNotNull(current.averageUnitCost))
            assertEquals(PEN, current.currency)
            assertEquals(1L, current.balanceVersion)
            val dollars = loaded.positions.single { it.locationId == USD_LOCATION }
            assertDecimal("4", dollars.quantityOnHand)
            assertDecimal("1.25", requireNotNull(dollars.averageUnitCost))
            assertEquals(USD, dollars.currency)
            val absent = loaded.positions.single { it.locationId == EMPTY_LOCATION }
            assertNull(absent.balanceVersion)
            assertNull(absent.averageUnitCost)
            assertDecimal("0", absent.quantityOnHand)
            assertNull(editor.load(BusinessId.from(UUID(0, 99)), PRODUCT, PEN))
            assertNull(editor.load(BUSINESS, ProductId.from(UUID(0, 99)), PEN))
        }

    @Test
    fun savesMetadataAndSeveralLocationsAtomicallyWithoutChangingHistoricalSalesOrMovements() =
        runBlocking {
            val original = snapshot()
            val historical = financialRows()
            val oldMovements = rows("stock_movements")
            val outbox = rows("outbox_operations").size
            val result =
                editor.save(
                    original,
                    original.product.copy(name = "Corregido", sku = "CORREGIDO", barcode = "000456", salePrice = Money.ofMinor(750, PEN)),
                    listOf(edit(LOCATION, "11", "3.123456789012345678"), edit(USD_LOCATION, "2", "1.50")),
                ) as ProductEditingResult.Saved
            assertTrue(result.changed)
            assertEquals(original.product.version + 1, result.snapshot.product.version)
            assertEquals(LOCATION, result.snapshot.product.locationId)
            assertEquals(UNIT, result.snapshot.product.unitId)
            assertEquals(outbox + 1, rows("outbox_operations").size)
            assertEquals(historical, financialRows())
            assertEquals(oldMovements, rows("stock_movements").take(oldMovements.size))
            assertBalance(LOCATION, "11", "3.123456789012345678", PEN)
            assertBalance(USD_LOCATION, "2", "1.50", USD)
            assertNull(database.inventoryDao().findBalance(BUSINESS.value, PRODUCT.value, EMPTY_LOCATION.value))
            val appended = database.inventoryDao().listReadMovementsForProduct(BUSINESS.value, PRODUCT.value).drop(oldMovements.size)
            assertEquals(4, appended.size)
            assertTrue(appended.all { it.type == "ADJUSTMENT" && it.purchaseId == null && it.saleId == null })
            assertTrue(appended.zipWithNext().all { (first, second) -> first.occurredAt < second.occurredAt })
            assertQuantityDiagnostic()
            assertIntegrity()
        }

    @Test
    fun costOnlyUsesOrderedAdjustmentsAndPreservesExactStoredPrecisionWithoutChangingProduct() =
        runBlocking {
            val original = snapshot()
            val productRows = rows("products")
            val outbox = rows("outbox_operations")
            val oldMovements = rows("stock_movements")
            val preciseCost = "123456.123456789012345678"
            val result = editor.save(original, original.product, listOf(edit(LOCATION, "8", preciseCost))) as ProductEditingResult.Saved
            assertTrue(result.changed)
            assertEquals(original.product, result.snapshot.product)
            assertEquals(productRows, rows("products"))
            assertEquals(outbox, rows("outbox_operations"))
            assertEquals(oldMovements, rows("stock_movements").take(oldMovements.size))
            val changes = database.inventoryDao().listReadMovementsForProduct(BUSINESS.value, PRODUCT.value).drop(oldMovements.size)
            assertEquals(listOf("-8", "8"), changes.map { it.quantityDelta })
            assertTrue(changes[0].occurredAt < changes[1].occurredAt)
            assertEquals(preciseCost, changes[1].unitCost)
            assertBalance(LOCATION, "8", preciseCost, PEN)
            val after = allRelevantRows()
            assertEquals(ProductEditingResult.Stale, editor.save(original, original.product, listOf(edit(LOCATION, "8", preciseCost))))
            assertEquals(after, allRelevantRows())
            assertQuantityDiagnostic()
        }

    @Test
    fun quantityZeroAndCostZeroRemainEditableIncludingCostOnlyWhenThereIsNoStock() =
        runBlocking {
            val original = snapshot()
            val result = editor.save(original, original.product, listOf(edit(LOCATION, "0", "0"))) as ProductEditingResult.Saved
            assertBalance(LOCATION, "0", "0", PEN)
            assertDecimal(
                "0",
                requireNotNull(
                    result.snapshot.positions
                        .single { it.locationId == LOCATION }
                        .averageUnitCost,
                ),
            )
            val beforeCost = rows("stock_movements").size
            val next = editor.save(result.snapshot, result.snapshot.product, listOf(edit(LOCATION, "0", "4.125"))) as ProductEditingResult.Saved
            assertBalance(LOCATION, "0", "4.125", PEN)
            val costPair = database.inventoryDao().listReadMovementsForProduct(BUSINESS.value, PRODUCT.value).drop(beforeCost)
            assertEquals(listOf("1", "-1"), costPair.map { it.quantityDelta })
            assertTrue(costPair[0].occurredAt < costPair[1].occurredAt)
            assertDecimal(
                "4.125",
                requireNotNull(
                    next.snapshot.positions
                        .single { it.locationId == LOCATION }
                        .averageUnitCost,
                ),
            )
            assertQuantityDiagnostic()
        }

    @Test
    fun unchangedAndMetadataOnlySavesDoNotWriteBalancesOrExtraOutbox() =
        runBlocking {
            val original = snapshot()
            val before = allRelevantRows()
            val result = editor.save(original, original.product, listOf(edit(LOCATION, "8.00", "2.00"))) as ProductEditingResult.Saved
            assertFalse(result.changed)
            assertEquals(before, allRelevantRows())
            val balances = rows("inventory_balances")
            val movements = rows("stock_movements")
            val edited = editor.save(original, original.product.copy(name = "Sólo nombre"), emptyList()) as ProductEditingResult.Saved
            assertTrue(edited.changed)
            assertEquals(balances, rows("inventory_balances"))
            assertEquals(movements, rows("stock_movements"))
            assertEquals(before.getValue("outbox_operations").size + 1, rows("outbox_operations").size)
            val after = allRelevantRows()
            assertFalse((editor.save(edited.snapshot, edited.snapshot.product, emptyList()) as ProductEditingResult.Saved).changed)
            assertEquals(after, allRelevantRows())
        }

    @Test
    fun quantityOnlyCanIncreaseReduceAndEmptyWithoutChangingTheAverageOrOtherLocation() =
        runBlocking {
            val other = database.inventoryDao().findBalance(BUSINESS.value, PRODUCT.value, USD_LOCATION.value)
            val product = snapshot().product
            val outbox = rows("outbox_operations")
            var expected = snapshot()
            for (quantity in listOf("10", "3.5", "0")) {
                val previousCount = rows("stock_movements").size
                val result = editor.save(expected, product, listOf(edit(LOCATION, quantity, null))) as ProductEditingResult.Saved
                expected = result.snapshot
                assertBalance(LOCATION, quantity, "2", PEN)
                assertEquals(previousCount + 1, rows("stock_movements").size)
                assertEquals(other, database.inventoryDao().findBalance(BUSINESS.value, PRODUCT.value, USD_LOCATION.value))
                assertEquals(product, result.snapshot.product)
                assertEquals(outbox, rows("outbox_operations"))
            }
            assertQuantityDiagnostic()
        }

    @Test
    fun unknownCostIsNotConvertedToZeroAndAnExplicitZeroIsASeparateReviewedDecision() =
        runBlocking {
            stock.addStock(BUSINESS, PRODUCT, EMPTY_LOCATION, BigDecimal("4"), PEN, null, "unknown-cost")
            val original = snapshot()
            assertNull(original.positions.single { it.locationId == EMPTY_LOCATION }.averageUnitCost)
            val balances = rows("inventory_balances")
            val saved = editor.save(original, original.product.copy(name = "Con costo desconocido"), emptyList()) as ProductEditingResult.Saved
            assertEquals(balances, rows("inventory_balances"))
            assertNull(
                saved.snapshot.positions
                    .single { it.locationId == EMPTY_LOCATION }
                    .averageUnitCost,
            )
            val before = allRelevantRows()
            assertEquals(
                ProductEditingResult.Invalid(ProductEditingInvalidField.COST),
                editor.save(saved.snapshot, saved.snapshot.product, listOf(edit(EMPTY_LOCATION, "5", null))),
            )
            assertEquals(before, allRelevantRows())
            val explicit = editor.save(saved.snapshot, saved.snapshot.product, listOf(edit(EMPTY_LOCATION, "4", "0"))) as ProductEditingResult.Saved
            assertDecimal(
                "0",
                requireNotNull(
                    explicit.snapshot.positions
                        .single { it.locationId == EMPTY_LOCATION }
                        .averageUnitCost,
                ),
            )
            assertBalance(EMPTY_LOCATION, "4", "0", PEN)
            assertQuantityDiagnostic()
        }

    @Test
    fun saleAfterOpeningTheEditorInvalidatesBalanceCasAndCannotPartiallySaveMetadata() =
        runBlocking {
            val original = snapshot()
            postSale()
            assertEquals(original.product, snapshot().product)
            val afterSale = allRelevantRows()
            assertEquals(
                ProductEditingResult.Stale,
                editor.save(original, original.product.copy(name = "No debe escribirse"), listOf(edit(LOCATION, "9", "2"))),
            )
            assertEquals(afterSale, allRelevantRows())
            assertBalance(LOCATION, "6", "2", PEN)
        }

    @Test
    fun absentBalanceCasRejectsAConcurrentFirstEntryAndArchiveInvalidatesProductCas() =
        runBlocking {
            val original = snapshot()
            stock.addStock(BUSINESS, PRODUCT, EMPTY_LOCATION, BigDecimal.ONE, PEN, BigDecimal.ONE, "concurrent-first")
            val afterEntry = allRelevantRows()
            assertEquals(
                ProductEditingResult.Stale,
                editor.save(original, original.product.copy(name = "Obsoleto"), listOf(edit(EMPTY_LOCATION, "5", "2"))),
            )
            assertEquals(afterEntry, allRelevantRows())
            assertTrue(products.archive(BUSINESS, PRODUCT, original.product.version))
            val afterArchive = allRelevantRows()
            assertEquals(ProductEditingResult.Stale, editor.save(original, original.product.copy(name = "No restaura"), emptyList()))
            assertEquals(afterArchive, allRelevantRows())
        }

    @Test
    fun concurrentStockSavesFromOneSnapshotHaveOnlyOneWinnerEvenWithoutMetadataVersionChange() =
        runBlocking {
            val original = snapshot()
            val count = rows("stock_movements").size
            val results =
                listOf(
                    async(Dispatchers.Default) { editor.save(original, original.product, listOf(edit(LOCATION, "9", null))) },
                    async(Dispatchers.Default) { editor.save(original, original.product, listOf(edit(LOCATION, "7", null))) },
                ).awaitAll()
            assertEquals(1, results.count { it is ProductEditingResult.Saved })
            assertEquals(1, results.count { it == ProductEditingResult.Stale })
            assertEquals(count + 1, rows("stock_movements").size)
            assertEquals(original.product, snapshot().product)
            assertQuantityDiagnostic()
        }

    @Test
    fun lateOutboxFailureRollsBackEveryLocationAndMetadataThenRetryAppliesOnce() =
        runBlocking {
            val original = snapshot()
            val before = allRelevantRows()
            database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER editing_fail_outbox BEFORE INSERT ON outbox_operations BEGIN SELECT RAISE(ABORT, 'injected outbox failure'); END",
            )
            val edits = listOf(edit(LOCATION, "10", "3"), edit(USD_LOCATION, "5", "2"))
            expectStorageFailure { editor.save(original, original.product.copy(name = "Atómico"), edits) }
            assertEquals(before, allRelevantRows())
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER editing_fail_outbox")
            assertTrue(editor.save(original, original.product.copy(name = "Atómico"), edits) is ProductEditingResult.Saved)
            val after = allRelevantRows()
            assertEquals(ProductEditingResult.Stale, editor.save(original, original.product.copy(name = "Atómico"), edits))
            assertEquals(after, allRelevantRows())
            assertQuantityDiagnostic()
        }

    @Test
    fun failureDuringTheSecondCostMovementRollsBackTheFirstMovementAndBalance() =
        runBlocking {
            val original = snapshot()
            val before = allRelevantRows()
            database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER editing_fail_second BEFORE INSERT ON stock_movements " +
                    "WHEN NEW.idempotencyKey LIKE 'product-edit:v1:%:1' BEGIN SELECT RAISE(ABORT, 'injected second movement failure'); END",
            )
            expectStorageFailure { editor.save(original, original.product.copy(name = "No parcial"), listOf(edit(LOCATION, "8", "3"))) }
            assertEquals(before, allRelevantRows())
        }

    @Test
    fun linkedBusinessAllowsMetadataButCannotMutateLocalAuthoritativeStock() =
        runBlocking {
            val original = snapshot()
            database.cloudBusinessBindingDao().insert(CloudBusinessBindingEntity(BUSINESS.value, UUID(0, 90).toString(), clock.now().toEpochMilli(), 0))
            assertFalse(snapshot().inventoryEditable)
            val before = allRelevantRows()
            assertEquals(
                ProductEditingResult.Invalid(ProductEditingInvalidField.SHARED_INVENTORY),
                editor.save(original, original.product.copy(name = "No parcial"), listOf(edit(LOCATION, "9", "2"))),
            )
            assertEquals(before, allRelevantRows())
            assertTrue(editor.save(original, original.product.copy(name = "Metadata permitida"), emptyList()) is ProductEditingResult.Saved)
            assertEquals(before.getValue("inventory_balances"), rows("inventory_balances"))
            assertEquals(before.getValue("stock_movements"), rows("stock_movements"))
        }

    @Test
    fun immutableFieldsDuplicateCodesAndInvalidNumbersCannotPartiallyApplyStock() =
        runBlocking {
            val original = snapshot()
            val before = allRelevantRows()
            for (candidate in listOf(
                original.product.copy(locationId = USD_LOCATION),
                original.product.copy(status = CatalogStatus.ARCHIVED),
                original.product.copy(version = original.product.version + 1),
                original.product.copy(createdAt = clock.now().plusSeconds(1)),
            )) {
                assertEquals(ProductEditingResult.Invalid(ProductEditingInvalidField.IMMUTABLE_FIELD), editor.save(original, candidate, listOf(edit(LOCATION, "10", "2"))))
            }
            assertEquals(ProductEditingResult.Invalid(ProductEditingInvalidField.QUANTITY), editor.save(original, original.product, listOf(edit(LOCATION, "-1", "2"))))
            assertEquals(ProductEditingResult.Invalid(ProductEditingInvalidField.COST), editor.save(original, original.product, listOf(edit(LOCATION, "10", "-1"))))
            assertEquals(ProductEditingResult.Invalid(ProductEditingInvalidField.LOCATION), editor.save(original, original.product, listOf(edit(LOCATION, "10", "2"), edit(LOCATION, "11", "3"))))
            assertEquals(before, allRelevantRows())
            products.create(original.product.copy(productId = ProductId.from(UUID(0, 89)), sku = "DUPLICATE", barcode = "00999"))
            val withDuplicate = allRelevantRows()
            assertEquals(ProductEditingResult.Duplicate(CatalogDuplicateField.SKU), editor.save(original, original.product.copy(sku = "duplicate"), listOf(edit(LOCATION, "10", "2"))))
            assertEquals(ProductEditingResult.Duplicate(CatalogDuplicateField.BARCODE), editor.save(original, original.product.copy(barcode = "00999"), listOf(edit(LOCATION, "10", "2"))))
            assertEquals(withDuplicate, allRelevantRows())
        }

    @Test
    fun absentPositionCanPersistAnExplicitZeroQuantityAndCostWithoutChangingOtherBalances() =
        runBlocking {
            val original = snapshot()
            val current = database.inventoryDao().findBalance(BUSINESS.value, PRODUCT.value, LOCATION.value)
            val result = editor.save(original, original.product, listOf(edit(EMPTY_LOCATION, "0", "0"))) as ProductEditingResult.Saved
            assertBalance(EMPTY_LOCATION, "0", "0", PEN)
            val position = result.snapshot.positions.single { it.locationId == EMPTY_LOCATION }
            assertEquals(0L, position.balanceVersion)
            assertDecimal("0", requireNotNull(position.averageUnitCost))
            assertEquals(current, database.inventoryDao().findBalance(BUSINESS.value, PRODUCT.value, LOCATION.value))
            assertQuantityDiagnostic()
        }

    @Test
    fun previouslyStoredDerivedPrecisionIsPreservedButANewCostMustUseTheInputPrecision() =
        runBlocking {
            val previous = requireNotNull(database.inventoryDao().findBalance(BUSINESS.value, PRODUCT.value, LOCATION.value))
            val storedCost = "123456.123456789012345678123456789012345678"
            // Simula un promedio derivado de compra histórica, ya persistido según política128/36.
            database.inventoryDao().updateBalanceIfVersion(
                previous.businessId,
                previous.productId,
                previous.locationId,
                previous.version,
                previous.quantityOnHand,
                storedCost,
                previous.currencyCode,
                previous.updatedAt,
            )
            val original = snapshot()
            assertEquals(
                storedCost,
                original.positions
                    .single { it.locationId == LOCATION }
                    .averageUnitCost
                    ?.toPlainString(),
            )
            val before = allRelevantRows()
            assertFalse((editor.save(original, original.product, emptyList()) as ProductEditingResult.Saved).changed)
            assertEquals(before, allRelevantRows())
            assertEquals(
                ProductEditingResult.Invalid(ProductEditingInvalidField.COST),
                editor.save(original, original.product.copy(name = "No parcial"), listOf(edit(LOCATION, "9", "1.1234567890123456789"))),
            )
            assertEquals(before, allRelevantRows())
            val result =
                editor.save(
                    original,
                    original.product.copy(name = "Conservar precisión"),
                    listOf(edit(LOCATION, "9", storedCost)),
                ) as ProductEditingResult.Saved
            assertEquals(
                storedCost,
                result.snapshot.positions
                    .single { it.locationId == LOCATION }
                    .averageUnitCost
                    ?.toPlainString(),
            )
            assertBalance(LOCATION, "9", storedCost, PEN)
            assertEquals(storedCost, database.inventoryDao().findBalance(BUSINESS.value, PRODUCT.value, LOCATION.value)?.averageUnitCost)
        }

    private suspend fun snapshot(): ProductEditingSnapshot = requireNotNull(editor.load(BUSINESS, PRODUCT, PEN))

    private fun edit(
        location: LocationId,
        quantity: String,
        cost: String?,
    ) = ProductStockEdit(location, quantity.toBigDecimal(), cost?.toBigDecimal())

    private suspend fun postSale() {
        val sales = RoomSaleRepository(database, clock, ids, testDispatchers)
        val cart = sales.createOrResume(BUSINESS, PEN).cart
        val saved =
            sales.saveLine(
                BUSINESS,
                SaveSaleCartLineCommand(
                    cart.saleId,
                    cart.version,
                    productId = PRODUCT,
                    locationId = LOCATION,
                    quantity = Quantity.of("2"),
                    unitPrice = Money.ofMinor(500, PEN),
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

    private suspend fun assertBalance(
        location: LocationId,
        quantity: String,
        cost: String,
        currency: CurrencyCode,
    ) {
        val balance = requireNotNull(database.inventoryDao().findBalance(BUSINESS.value, PRODUCT.value, location.value))
        assertDecimal(quantity, balance.quantityOnHand.toBigDecimal())
        assertDecimal(cost, balance.averageUnitCost.toBigDecimal())
        assertEquals(currency.value, balance.currencyCode)
    }

    private suspend fun assertQuantityDiagnostic() {
        val diagnostic = RoomInventoryReadRepository(database, clock, testDispatchers).diagnose(BUSINESS)
        assertTrue(diagnostic.positions.all { InventoryDiagnosticIssue.QUANTITY_DIVERGENCE !in it.issues })
        assertTrue(diagnostic.positions.all { it.cachedQuantity?.compareTo(it.ledgerQuantity) == 0 })
        // ADJUSTMENT conserva cantidades exactas; el diagnóstico existente no certifica su costo.
        assertTrue(diagnostic.positions.any { InventoryDiagnosticIssue.COST_UNVERIFIABLE in it.issues })
    }

    private fun assertDecimal(
        expected: String,
        actual: BigDecimal,
    ) = assertEquals(0, expected.toBigDecimal().compareTo(actual))

    private fun financialRows() = listOf("sales", "sale_lines", "debts", "debt_payments", "audit_events").associateWith(::rows)

    private fun allRelevantRows() = financialRows() + listOf("products", "inventory_balances", "stock_movements", "outbox_operations").associateWith(::rows)

    private fun rows(table: String): List<List<String?>> =
        database.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY rowid").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        List(cursor.columnCount) { index ->
                            when (cursor.getType(index)) {
                                Cursor.FIELD_TYPE_NULL -> null
                                Cursor.FIELD_TYPE_INTEGER -> "integer:${cursor.getLong(index)}"
                                Cursor.FIELD_TYPE_FLOAT -> "real:${cursor.getDouble(index)}"
                                Cursor.FIELD_TYPE_BLOB -> "blob:${cursor.getBlob(index).joinToString()}"
                                else -> "text:${cursor.getString(index)}"
                            }
                        },
                    )
                }
            }
        }

    private suspend fun expectStorageFailure(action: suspend () -> Any?) {
        try {
            action()
        } catch (_: StorageException) {
            return
        }
        throw AssertionError("Debió fallar y revertir todos los cambios")
    }

    private fun assertIntegrity() {
        database.openHelper.readableDatabase.query("PRAGMA integrity_check").use {
            assertTrue(it.moveToFirst())
            assertEquals("ok", it.getString(0))
        }
        database.openHelper.readableDatabase
            .query("PRAGMA foreign_key_check")
            .use { assertEquals(0, it.count) }
    }

    private companion object {
        val BUSINESS = BusinessId.from(UUID(0, 1))
        val UNIT = UnitId.from(UUID(0, 3))
        val LOCATION = LocationId.from(UUID(0, 5))
        val USD_LOCATION = LocationId.from(UUID(0, 6))
        val EMPTY_LOCATION = LocationId.from(UUID(0, 7))
        val PRODUCT = ProductId.from(UUID(0, 8))
        val PEN = CurrencyCode.of("PEN")
        val USD = CurrencyCode.of("USD")
    }
}
