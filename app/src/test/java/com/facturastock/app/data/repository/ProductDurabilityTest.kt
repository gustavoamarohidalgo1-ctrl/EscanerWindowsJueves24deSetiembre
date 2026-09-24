package com.facturastock.app.data.repository

import com.facturastock.app.data.local.querySql
import java.io.File
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import com.facturastock.app.data.local.withTransaction
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.entity.InventoryLocationEntity
import com.facturastock.app.domain.model.Business
import com.facturastock.app.domain.model.CatalogMutationResult
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.SaleStatus
import com.facturastock.app.domain.model.UnitCost
import com.facturastock.app.domain.model.UnitOfMeasure
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.repository.CheckoutSaleCommand
import com.facturastock.app.domain.repository.CheckoutSaleResult
import com.facturastock.app.domain.repository.SaleCartMutationResult
import com.facturastock.app.domain.repository.SaveSaleCartLineCommand
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Durabilidad de productos sobre SQLite real (factory y builder de producción): synchronous=FULL
 * queda fijado en cada apertura y un producto confirmado sobrevive al cierre total y a la
 * reapertura de la base. Un "guardado" solo puede significar "legible desde disco".
 */
class ProductDurabilityTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val clock = TestClock(Instant.parse("2026-08-08T12:00:00Z"))
    private lateinit var databaseName: String
    private lateinit var database: FacturaStockDatabase

    @Before
    fun setUp() {
        databaseName = "durability-" + UUID.randomUUID() + ".db"
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) {
            database.close()
        }
    }

    @Test
    fun synchronousIsFullOnTheWriteConnection() =
        runBlocking {
            database = buildDatabase()
            // El pool interno de SQLiteDatabase en WAL atiende lecturas con conexiones secundarias
            // (synchronous=NORMAL, irrelevante: nunca confirman). La lectura dentro de una
            // transacción corre sobre la conexión primaria, la única que confirma commits.
            // synchronous=2 equivale a FULL.
            database.withTransaction {
                database.querySql("PRAGMA synchronous").use { cursor ->
                    cursor.moveToFirst()
                    assertEquals(2, cursor.getInt(0))
                }
            }
        }

    @Test
    fun savedProductSurvivesFullCloseAndReopen() =
        runBlocking {
            database = buildDatabase()
            val businesses = RoomBusinessRepository(database.businessDao(), testDispatchers, clock)
            val units = RoomUnitRepository(database.unitDao(), testDispatchers, clock)
            val products = RoomProductRepository(database, database.productDao(), testDispatchers, clock)

            businesses.create(business(BUSINESS_ID))
            units.create(unit(UNIT_ID))
            val created =
                products.create(
                    product(
                        PRODUCT_ID,
                        unitId = UNIT_ID,
                        name = "Azúcar rubia",
                        barcode = "7751234567890",
                    ),
                )

            // Cierre completo: el checkpoint de cierre descarga el WAL al archivo principal.
            database.close()

            val reopened =
                FacturaStockDatabase.build(File(tempFolder.root, databaseName))
            try {
                val stored =
                    requireNotNull(reopened.productDao().findById(PRODUCT_ID.value)) {
                        "El producto debe existir tras reabrir la base"
                    }
                assertEquals(created.productId.value, stored.productId)
                assertEquals(created.name, stored.name)
                assertEquals(created.barcode, stored.barcode)
                assertEquals(created.unitId.value, stored.unitId)
                assertEquals(1L, stored.version)
            } finally {
                reopened.close()
            }
        }

    @Test
    fun firstRegisteredBarcodeProductCanBeSoldAfterReopenAndCheckoutStaysDurable() =
        runBlocking {
            database = buildDatabase()
            val units = RoomUnitRepository(database.unitDao(), testDispatchers, clock)
            RoomBusinessRepository(database.businessDao(), testDispatchers, clock).create(business(BUSINESS_ID))
            units.create(unit(UNIT_ID))
            val now = clock.now().toEpochMilli()
            database.inventoryLocationDao().insert(
                InventoryLocationEntity(LOCATION_ID.value, BUSINESS_ID.value, "Principal", now, now),
            )
            val products = RoomProductRepository(database, database.productDao(), testDispatchers, clock)
            val registration =
                RoomProductRegistrationRepository(
                    database = database,
                    products = products,
                    units = units,
                    locations = RoomInventoryLocationRepository(database, testDispatchers, clock),
                    inventory =
                        RoomProductInventoryRepository(
                            database,
                            database.inventoryDao(),
                            testDispatchers,
                            clock,
                            uuidGenerator,
                        ),
                    dispatchers = testDispatchers,
                )
            // Ventas puede haber abierto el carrito cuando el negocio aún no tenía productos.
            val initialCart = saleRepository().createOrResume(BUSINESS_ID, PEN).cart
            assertEquals(0, database.productDao().countForBusiness(BUSINESS_ID.value))
            val candidate =
                product(PRODUCT_ID, UNIT_ID, "Galletas", "000123450001").copy(
                    locationId = LOCATION_ID,
                    salePrice = Money.ofMinor(350L, PEN),
                )
            assertTrue(
                registration.register(candidate, BigDecimal("4"), UnitCost.of("2.125", PEN))
                    is CatalogMutationResult.Saved,
            )
            assertEquals(PRODUCT_ID, products.findByBarcode(BUSINESS_ID, "000123450001")?.productId)

            database.close()
            database = buildDatabase()

            // Usar las lecturas reales de catálogo e inventario que alimentan el escáner de ventas.
            val scanned =
                requireNotNull(
                    RoomProductRepository(database, database.productDao(), testDispatchers, clock)
                        .findByBarcode(BUSINESS_ID, "000123450001"),
                )
            assertEquals(candidate.barcode, scanned.barcode)
            assertEquals(candidate.salePrice, scanned.salePrice)
            assertEquals(LOCATION_ID, scanned.locationId)
            val inventory =
                RoomInventoryReadRepository(database, clock, testDispatchers)
                    .observeInventory(BUSINESS_ID)
                    .first()
                    .single()
            assertEquals(scanned.productId, inventory.productId)
            val position = inventory.positions.single()
            assertEquals(LOCATION_ID, position.locationId)
            assertEquals(0, BigDecimal("4").compareTo(position.quantityOnHand))
            assertEquals(0, BigDecimal("2.125").compareTo(position.averageUnitCost.amount))
            assertTrue(inventory.allAlerts.isEmpty())
            val sales = saleRepository()
            val resumed = sales.createOrResume(BUSINESS_ID, PEN)
            assertEquals(initialCart.saleId, resumed.cart.saleId)
            val saved =
                sales.saveLine(
                    BUSINESS_ID,
                    SaveSaleCartLineCommand(
                        saleId = resumed.cart.saleId,
                        expectedVersion = resumed.cart.version,
                        productId = scanned.productId,
                        locationId = position.locationId,
                        quantity = Quantity.of("1"),
                        unitPrice = requireNotNull(scanned.salePrice),
                    ),
                ) as SaleCartMutationResult.Saved
            val checkout = CheckoutSaleCommand(saved.cart.saleId, saved.cart.version, saved.cart.contentHash)
            assertEquals(CheckoutSaleResult.Posted(saved.cart.saleId), sales.checkout(BUSINESS_ID, checkout))

            database.close()
            database = buildDatabase()

            val posted = requireNotNull(database.saleDao().findWithLines(saved.cart.saleId.value))
            assertEquals(SaleStatus.POSTED.name, posted.sale.status)
            assertEquals(350L, posted.sale.totalMinorUnits)
            assertEquals("000123450001", posted.lines.single().barcodeSnapshot)
            assertEquals(350L, requireNotNull(posted.lines.single().unitPriceMinorUnits))
            val balance =
                requireNotNull(
                    database.inventoryDao().findBalance(BUSINESS_ID.value, PRODUCT_ID.value, LOCATION_ID.value),
                )
            assertEquals(0, BigDecimal("3").compareTo(BigDecimal(balance.quantityOnHand)))
            assertEquals(0, BigDecimal("2.125").compareTo(BigDecimal(balance.averageUnitCost)))
            assertNotNull(
                database.inventoryDao().findMovementByIdempotencyKey("product-registration:v1:${PRODUCT_ID.value}"),
            )
            assertEquals(
                CheckoutSaleResult.AlreadyPosted(saved.cart.saleId),
                saleRepository().checkout(BUSINESS_ID, checkout),
            )
            assertEquals(
                1,
                database.inventoryDao().listMovementsForSale(BUSINESS_ID.value, saved.cart.saleId.value).size,
            )
            assertEquals(
                balance,
                database.inventoryDao().findBalance(BUSINESS_ID.value, PRODUCT_ID.value, LOCATION_ID.value),
            )
        }

    private fun saleRepository() = RoomSaleRepository(database, clock, uuidGenerator, testDispatchers)

    private fun buildDatabase(): FacturaStockDatabase =
        FacturaStockDatabase.build(File(tempFolder.root, databaseName))

    private fun business(id: BusinessId) =
        Business(
            businessId = id,
            legalName = "Mayda",
            ruc = null,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )

    private fun unit(id: UnitId) =
        UnitOfMeasure(
            unitId = id,
            businessId = BUSINESS_ID,
            code = "NIU",
            name = "Unidad",
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        )

    private fun product(
        id: ProductId,
        unitId: UnitId,
        name: String,
        barcode: String?,
    ) = Product(
        productId = id,
        businessId = BUSINESS_ID,
        unitId = unitId,
        name = name,
        sku = null,
        barcode = barcode,
        locationId = null,
        purchaseUnitId = null,
        purchaseFactor = null,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private companion object {
        val BUSINESS_ID: BusinessId =
            BusinessId.from(UUID.fromString("00000000-0000-0000-0000-000000000901"))
        val PRODUCT_ID: ProductId =
            ProductId.from(UUID.fromString("00000000-0000-0000-0000-000000000011"))
        val UNIT_ID: UnitId =
            UnitId.from(UUID.fromString("00000000-0000-0000-0000-000000000032"))
        val LOCATION_ID: LocationId =
            LocationId.from(UUID.fromString("00000000-0000-0000-0000-000000000033"))
        val PEN = CurrencyCode.of("PEN")
        val uuidGenerator = UuidGenerator { UUID.randomUUID() }
    }
}
