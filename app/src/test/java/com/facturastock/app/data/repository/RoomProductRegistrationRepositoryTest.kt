package com.facturastock.app.data.repository

import com.facturastock.app.data.local.newFacturaStockDatabase
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import com.facturastock.app.data.local.readableSql
import com.facturastock.app.data.local.writableSql
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.entity.BusinessEntity
import com.facturastock.app.data.local.entity.CloudBusinessBindingEntity
import com.facturastock.app.data.local.entity.InventoryLocationEntity
import com.facturastock.app.data.local.entity.UnitEntity
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.CatalogDuplicateField
import com.facturastock.app.domain.model.CatalogInvalidField
import com.facturastock.app.domain.model.CatalogMutationResult
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.UnitCost
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.UnitId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class RoomProductRegistrationRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var database: FacturaStockDatabase
    private lateinit var products: RoomProductRepository
    private lateinit var registration: RoomProductRegistrationRepository
    private val clock = TestClock(Instant.parse("2026-09-04T12:00:00Z"))

    @Before
    fun setUp() =
        runBlocking {
            database =
                tempFolder.newFacturaStockDatabase()
            products = RoomProductRepository(database, database.productDao(), testDispatchers, clock)
            registration =
                RoomProductRegistrationRepository(
                    database = database,
                    products = products,
                    units = RoomUnitRepository(database.unitDao(), testDispatchers, clock),
                    locations = RoomInventoryLocationRepository(database, testDispatchers, clock),
                    inventory =
                        RoomProductInventoryRepository(
                            database,
                            database.inventoryDao(),
                            testDispatchers,
                            clock,
                            UuidGenerator { UUID.randomUUID() },
                        ),
                    dispatchers = testDispatchers,
                )
            val now = clock.now().toEpochMilli()
            database.businessDao().insert(BusinessEntity(BUSINESS.value, "Almacén local", now, now))
            database.unitDao().insert(UnitEntity(UNIT.value, BUSINESS.value, "NIU", "Unidad", now, now))
            database.inventoryLocationDao().insert(
                InventoryLocationEntity(LOCATION.value, BUSINESS.value, "Principal", now, now),
            )
        }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun savesScannedBarcodeSalePriceQuantityAndExactUnitCostTogether() =
        runBlocking {
            val result = registration.register(product(), BigDecimal("4"), UnitCost.of("2.125", PEN))

            assertTrue(result is CatalogMutationResult.Saved)
            val stored = requireNotNull(products.findByBarcode(BUSINESS, "000123450001"))
            assertEquals(PRODUCT, stored.productId)
            assertEquals("000123450001", stored.barcode)
            assertEquals(Money.ofMinor(350L, PEN), stored.salePrice)
            assertEquals(LOCATION, stored.locationId)
            val balance = requireNotNull(database.inventoryDao().findBalance(BUSINESS.value, PRODUCT.value, LOCATION.value))
            assertEquals(0, BigDecimal("4").compareTo(BigDecimal(balance.quantityOnHand)))
            assertEquals(0, BigDecimal("2.125").compareTo(BigDecimal(balance.averageUnitCost)))
            val movement =
                requireNotNull(
                    database.inventoryDao().findMovementByIdempotencyKey("product-registration:v1:${PRODUCT.value}"),
                )
            assertEquals("4", movement.quantityDelta)
            assertEquals("2.125", movement.unitCost)
            assertEquals(1L, rowCount("outbox_operations"))
        }

    @Test
    fun manualProductWithoutBarcodeStoresBothPricesAndRetryDoesNotDuplicateOpeningStock() =
        runBlocking {
            val candidate = product().copy(barcode = null)
            val saved = registration.register(candidate, BigDecimal("4"), UnitCost.of("2.125", PEN))

            assertTrue(saved is CatalogMutationResult.Saved)
            val stored = requireNotNull(products.findById(PRODUCT))
            assertNull(stored.barcode)
            assertEquals(Money.ofMinor(350L, PEN), stored.salePrice)
            assertEquals(CatalogMutationResult.Stale, registration.register(candidate, BigDecimal("9"), UnitCost.of("7", PEN)))
            val balance = requireNotNull(database.inventoryDao().findBalance(BUSINESS.value, PRODUCT.value, LOCATION.value))
            assertEquals(0, BigDecimal("4").compareTo(BigDecimal(balance.quantityOnHand)))
            assertEquals(0, BigDecimal("2.125").compareTo(BigDecimal(balance.averageUnitCost)))
            val movement = requireNotNull(database.inventoryDao().findMovementByIdempotencyKey("product-registration:v1:${PRODUCT.value}"))
            assertEquals("4", movement.quantityDelta)
            assertEquals("2.125", movement.unitCost)
            assertEquals(1L, rowCount("products"))
            assertEquals(1L, rowCount("outbox_operations"))
            assertEquals(1L, rowCount("stock_movements"))
        }

    @Test
    fun stockFailureRollsBackProductOutboxBalanceAndMovement() =
        runBlocking {
            database.writableSql.execSQL(
                "CREATE TRIGGER fail_registration_stock BEFORE INSERT ON stock_movements " +
                    "BEGIN SELECT RAISE(ABORT, 'registration stock test failure'); END",
            )

            val failure =
                runCatching {
                    registration.register(product(), BigDecimal("4"), UnitCost.of("2.125", PEN))
                }.exceptionOrNull()

            assertTrue(failure is StorageException)
            assertNull(products.findById(PRODUCT))
            assertNull(products.findByBarcode(BUSINESS, "000123450001"))
            assertEquals(0L, rowCount("outbox_operations"))
            assertEquals(0L, rowCount("inventory_balances"))
            assertEquals(0L, rowCount("stock_movements"))
        }

    @Test
    fun retryAndDuplicateBarcodeNeverAddStockAgain() =
        runBlocking {
            val candidate = product()
            assertTrue(registration.register(candidate, BigDecimal("4"), UnitCost.of("2.125", PEN)) is CatalogMutationResult.Saved)

            val replay = registration.register(candidate, BigDecimal("9"), UnitCost.of("7", PEN))
            val duplicateBarcode =
                registration.register(
                    candidate.copy(productId = ProductId.from(UUID.randomUUID()), name = "Otro producto"),
                    BigDecimal("8"),
                    UnitCost.of("3", PEN),
                )

            assertEquals(CatalogMutationResult.Stale, replay)
            assertEquals(CatalogMutationResult.Duplicate(CatalogDuplicateField.BARCODE), duplicateBarcode)
            val balance = requireNotNull(database.inventoryDao().findBalance(BUSINESS.value, PRODUCT.value, LOCATION.value))
            assertEquals(0, BigDecimal("4").compareTo(BigDecimal(balance.quantityOnHand)))
            assertEquals(0, BigDecimal("2.125").compareTo(BigDecimal(balance.averageUnitCost)))
            assertEquals(1L, rowCount("products"))
            assertEquals(1L, rowCount("outbox_operations"))
            assertEquals(1L, rowCount("stock_movements"))
        }

    @Test
    fun cloudBoundBusinessRejectsLocalInitialStockWithoutCreatingProduct() =
        runBlocking {
            database.cloudBusinessBindingDao().insert(
                CloudBusinessBindingEntity(BUSINESS.value, UUID.randomUUID().toString(), clock.now().toEpochMilli(), 0),
            )

            val result = registration.register(product(), BigDecimal("4"), UnitCost.of("2.125", PEN))

            assertEquals(CatalogMutationResult.Invalid(CatalogInvalidField.OWNERSHIP), result)
            assertNull(products.findById(PRODUCT))
            assertEquals(0L, rowCount("outbox_operations"))
            assertEquals(0L, rowCount("inventory_balances"))
            assertEquals(0L, rowCount("stock_movements"))
        }

    private fun product() =
        Product(
            productId = PRODUCT,
            businessId = BUSINESS,
            unitId = UNIT,
            name = "Galletas",
            locationId = LOCATION,
            barcode = "000123450001",
            salePrice = Money.ofMinor(350L, PEN),
            createdAt = clock.now(),
            updatedAt = clock.now(),
        )

    private fun rowCount(table: String): Long {
        require(table in setOf("products", "outbox_operations", "inventory_balances", "stock_movements"))
        return database.readableSql.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }
    }

    private companion object {
        val BUSINESS = BusinessId.from(UUID.fromString("00000000-0000-0000-0000-000000000101"))
        val UNIT = UnitId.from(UUID.fromString("00000000-0000-0000-0000-000000000102"))
        val LOCATION = LocationId.from(UUID.fromString("00000000-0000-0000-0000-000000000103"))
        val PRODUCT = ProductId.from(UUID.fromString("00000000-0000-0000-0000-000000000104"))
        val PEN = CurrencyCode.of("PEN")
    }
}
