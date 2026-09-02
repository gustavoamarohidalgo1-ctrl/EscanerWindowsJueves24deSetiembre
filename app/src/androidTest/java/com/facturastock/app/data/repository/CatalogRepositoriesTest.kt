package com.facturastock.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.entity.CloudBusinessBindingEntity
import com.facturastock.app.data.local.entity.ProductEntity
import com.facturastock.app.data.local.entity.InventoryBalanceEntity
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.Business
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.CatalogSearch
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.InventoryLocation
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.Supplier
import com.facturastock.app.domain.model.SupplierProductAlias
import com.facturastock.app.domain.model.UnitOfMeasure
import com.facturastock.app.domain.model.id.AliasId
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.SupplierId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.repository.ProductSalePriceMutationResult
import com.facturastock.app.domain.usecase.LoadProductCatalogDetailUseCase
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogRepositoriesTest {
    private lateinit var database: FacturaStockDatabase
    private lateinit var clock: TestClock
    private lateinit var businesses: RoomBusinessRepository
    private lateinit var suppliers: RoomSupplierRepository
    private lateinit var units: RoomUnitRepository
    private lateinit var locations: RoomInventoryLocationRepository
    private lateinit var products: RoomProductRepository
    private lateinit var aliases: RoomSupplierProductAliasRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, FacturaStockDatabase::class.java).build()
        clock = TestClock(Instant.parse("2026-08-08T12:00:00Z"))
        businesses = RoomBusinessRepository(database.businessDao(), testDispatchers, clock)
        suppliers = RoomSupplierRepository(database, database.supplierDao(), testDispatchers, clock)
        units = RoomUnitRepository(database.unitDao(), testDispatchers, clock)
        locations = RoomInventoryLocationRepository(
            database,
            testDispatchers,
            clock,
        )
        products = RoomProductRepository(database, database.productDao(), testDispatchers, clock)
        aliases = RoomSupplierProductAliasRepository(database.supplierProductAliasDao(), testDispatchers, clock)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun businessCrudFlowAndSafeDelete() = runBlocking {
        val beta = businesses.create(business(businessId(1), ruc = "20123456789", legalName = "Beta SAC"))
        assertEquals(clock.now(), beta.createdAt)
        assertEquals(beta.createdAt, beta.updatedAt)
        assertEquals(beta, businesses.findById(businessId(1)))
        assertEquals(beta, businesses.findByRuc("20123456789"))

        businesses.create(business(businessId(2), ruc = "20999999999", legalName = "Alfa EIRL"))
        assertEquals(
            listOf("Alfa EIRL", "Beta SAC"),
            businesses.observeBusinesses().awaitMatching { it.size == 2 }.map { it.legalName },
        )

        clock.advanceSeconds(60)
        assertTrue(businesses.update(beta.copy(legalName = "Beta Renombrada SAC")))
        val updated = businesses.findById(businessId(1))!!
        assertEquals("Beta Renombrada SAC", updated.legalName)
        assertEquals(beta.createdAt, updated.createdAt)
        assertTrue(updated.updatedAt > beta.updatedAt)
        businesses.observeBusinesses().awaitMatching { list ->
            list.any { it.legalName == "Beta Renombrada SAC" }
        }

        assertTrue(businesses.deleteById(businessId(1)))
        businesses.observeBusinesses().awaitMatching { list -> list.none { it.businessId == businessId(1) } }
        assertFalse(businesses.deleteById(businessId(1)))
        assertFalse(businesses.update(business(businessId(50), ruc = "20500000000", legalName = "Ausente")))
    }

    @Test
    fun supplierCrudSearchAndFlowEmissions() = runBlocking {
        businesses.create(business(businessId(1), ruc = "20123456789", legalName = "Negocio"))
        suppliers.create(supplier(supplierId(2), legalName = "Beta Distribuciones", tradeName = "Betita"))
        suppliers.create(supplier(supplierId(1), legalName = "Alfa Mayorista", ruc = "20111222333"))

        // Búsqueda contains insensible a caso sobre legalName/tradeName y por RUC.
        assertEquals(
            listOf("Alfa Mayorista"),
            suppliers.search(businessId(1), "  ALFA ").map { it.legalName },
        )
        assertEquals(
            listOf("Beta Distribuciones"),
            suppliers.search(businessId(1), "betita").map { it.legalName },
        )
        assertEquals(
            listOf("Beta Distribuciones"),
            suppliers.search(businessId(1), "987654321").map { it.legalName },
        )
        assertEquals(emptyList<Supplier>(), suppliers.search(businessId(2), "alfa"))
        assertEquals(
            "Alfa Mayorista",
            suppliers.findByRuc(businessId(1), "20111222333")?.legalName,
        )

        // El Flow conserva el registro al archivarlo y restaurarlo.
        suppliers.observeForBusiness(businessId(1)).awaitMatching { it.size == 2 }
            .also { list ->
                assertEquals(listOf("Alfa Mayorista", "Beta Distribuciones"), list.map { it.legalName })
            }
        clock.advanceSeconds(60)
        assertTrue(suppliers.update(supplier(supplierId(2), legalName = "Zeta Distribuciones")))
        suppliers.observeForBusiness(businessId(1))
            .awaitMatching { list -> list.any { it.legalName == "Zeta Distribuciones" } }
        assertTrue(suppliers.archive(supplierId(2)))
        assertEquals(CatalogStatus.ARCHIVED, suppliers.findById(supplierId(2))?.status)
        assertEquals(2, suppliers.observeForBusiness(businessId(1)).awaitMatching { it.size == 2 }.size)
        assertTrue(suppliers.restore(supplierId(2)))
        assertEquals(CatalogStatus.ACTIVE, suppliers.findById(supplierId(2))?.status)
    }

    @Test
    fun duplicateSupplierRucTranslatesToConstraintConflict() = runBlocking {
        businesses.create(business(businessId(1), ruc = "20123456789", legalName = "Negocio A"))
        businesses.create(business(businessId(2), ruc = "20999999999", legalName = "Negocio B"))
        val original = suppliers.create(
            supplier(supplierId(1), legalName = "Original", ruc = " 20987654321 "),
        )
        assertEquals("20987654321", original.ruc)

        val exception = assertThrows(StorageException::class.java) {
            runBlocking {
                suppliers.create(supplier(supplierId(2), legalName = "Duplicado", ruc = " 20987654321 "))
            }
        }
        assertTrue(exception.error is StorageError.ConstraintConflict)

        // El mismo RUC sí es válido en otro negocio.
        suppliers.create(
            supplier(supplierId(3), businessId = businessId(2), legalName = "Otro", ruc = "20987654321"),
        )
        assertEquals(1, suppliers.search(businessId(2), "987654321").size)
    }

    @Test
    fun concurrentCanonicalSupplierRucRaceHasOneDurableWinner() = runBlocking {
        businesses.create(business(businessId(1), ruc = "20123456789", legalName = "Negocio"))
        val results = listOf(
            async(Dispatchers.Default) {
                runCatching {
                    suppliers.create(supplier(supplierId(1), legalName = "Uno", ruc = " 20987654321 "))
                }
            },
            async(Dispatchers.Default) {
                runCatching {
                    suppliers.create(supplier(supplierId(2), legalName = "Dos", ruc = "20987654321"))
                }
            },
        ).awaitAll()

        assertEquals(1, results.count { it.isSuccess })
        val failure = results.single { it.isFailure }.exceptionOrNull()
        assertTrue(failure is StorageException)
        assertTrue((failure as StorageException).error is StorageError.ConstraintConflict)
        assertEquals(1, suppliers.search(businessId(1), "20987654321").size)
    }

    @Test
    fun unitCrudAndArchivePreservesReferences() = runBlocking {
        businesses.create(business(businessId(1), ruc = "20123456789", legalName = "Negocio"))
        units.create(unit(unitId(1), code = "NIU", name = "Unidad"))
        units.create(unit(unitId(2), code = "KGM", name = "Kilogramo"))

        // findByCode normaliza el código consultado a mayúsculas.
        assertEquals("NIU", units.findByCode(businessId(1), " niu ")?.code)
        assertEquals(
            listOf("KGM", "NIU"),
            units.observeForBusiness(businessId(1)).awaitMatching { it.size == 2 }.map { it.code },
        )
        assertEquals(
            listOf("KGM"),
            units.search(businessId(1), CatalogSearch(query = "kilogramo", limit = 1))
                .items.map { it.code },
        )

        clock.advanceSeconds(60)
        assertTrue(units.update(unit(unitId(1), code = "NIU", name = "Unidad de medida")))
        assertEquals("Unidad de medida", units.findById(unitId(1))?.name)

        // Archivar una unidad referenciada conserva tanto la unidad como el producto histórico.
        products.create(product(productId(1), unitId = unitId(1), name = "Arroz Extra Costeño"))
        assertTrue(units.archive(unitId(1)))
        assertEquals(CatalogStatus.ARCHIVED, units.findById(unitId(1))?.status)
        assertEquals(unitId(1), products.findById(productId(1))?.unitId)
        assertTrue(units.restore(unitId(1)))
        assertEquals(CatalogStatus.ACTIVE, units.findById(unitId(1))?.status)
    }

    @Test
    fun locationCrudAndFlow() = runBlocking {
        businesses.create(business(businessId(1), ruc = "20123456789", legalName = "Negocio"))
        locations.create(location(locationId(1), name = "Mostrador"))
        locations.create(location(locationId(2), name = "Almacén central"))

        assertEquals("Mostrador", locations.findByName(businessId(1), "Mostrador")?.name)
        assertEquals(
            listOf("Almacén central", "Mostrador"),
            locations.observeForBusiness(businessId(1)).awaitMatching { it.size == 2 }.map { it.name },
        )
        assertEquals(
            listOf("Almacén central"),
            locations.search(businessId(1), CatalogSearch(query = "central", limit = 1))
                .items.map { it.name },
        )

        assertTrue(locations.update(location(locationId(1), name = "Vitrina")))
        locations.observeForBusiness(businessId(1))
            .awaitMatching { list -> list.any { it.name == "Vitrina" } }

        assertTrue(locations.archive(locationId(2)))
        assertEquals(CatalogStatus.ARCHIVED, locations.findById(locationId(2))?.status)
        assertEquals(2, locations.observeForBusiness(businessId(1)).awaitMatching { it.size == 2 }.size)
        assertTrue(locations.restore(locationId(2)))
    }

    @Test
    fun linkedInventoryLocationCannotBeRenamed() = runBlocking {
        businesses.create(business(businessId(1), ruc = "20123456789", legalName = "Negocio"))
        val stored = locations.create(location(locationId(1), name = "Almacén principal"))
        database.cloudBusinessBindingDao().insert(
            CloudBusinessBindingEntity(
                localBusinessId = businessId(1).value,
                cloudBusinessId = businessId(99).value,
                createdAt = clock.now().toEpochMilli(),
                boundLegacyOperationCount = 0,
            ),
        )

        val failure = assertThrows(StorageException::class.java) {
            runBlocking { locations.update(stored.copy(name = "Almacén renombrado")) }
        }

        assertTrue(failure.error is StorageError.ConstraintConflict)
        assertEquals("Almacén principal", locations.findById(locationId(1))?.name)
        assertTrue(locations.archive(locationId(1)))
    }

    @Test
    fun canonicalDuplicateInventoryLocationIsRejected() = runBlocking {
        businesses.create(business(businessId(1), ruc = "20123456789", legalName = "Negocio"))
        locations.create(location(locationId(1), name = "Depósito norte"))

        val failure = assertThrows(StorageException::class.java) {
            runBlocking {
                locations.create(location(locationId(2), name = "  DEPÓSITO   norte  "))
            }
        }

        assertTrue(failure.error is StorageError.ConstraintConflict)
        assertNull(locations.findById(locationId(2)))
    }

    @Test
    fun productCrudSearchAndFlow() = runBlocking {
        businesses.create(business(businessId(1), ruc = "20123456789", legalName = "Negocio"))
        units.create(unit(unitId(1), code = "NIU", name = "Unidad"))
        locations.create(location(locationId(1), name = "Almacén"))
        products.create(
            product(productId(1), name = "Arroz Extra Costeño", sku = "SKU-ARR-01", barcode = "7750001000011"),
        )
        products.create(product(productId(2), name = "Azúcar Rubia", sku = "SKU-AZU-01"))

        // La búsqueda es contains insensible a caso sobre el nombre normalizado.
        assertEquals(listOf("Arroz Extra Costeño"), products.search(businessId(1), "ARROZ").map { it.name })
        assertEquals(listOf("Azúcar Rubia"), products.search(businessId(1), "  azúcar ").map { it.name })
        assertEquals(listOf("Arroz Extra Costeño"), products.search(businessId(1), "sku-arr-01").map { it.name })
        assertEquals(listOf("Arroz Extra Costeño"), products.search(businessId(1), "1000011").map { it.name })
        assertEquals("Arroz Extra Costeño", products.findBySku(businessId(1), "SKU-ARR-01")?.name)
        assertEquals("Arroz Extra Costeño", products.findByBarcode(businessId(1), "7750001000011")?.name)
        assertEquals(
            listOf("Arroz Extra Costeño", "Azúcar Rubia"),
            products.observeForBusiness(businessId(1)).awaitMatching { it.size == 2 }.map { it.name },
        )

        clock.advanceSeconds(60)
        val created = products.findById(productId(2))!!
        assertTrue(products.update(created.copy(name = "Azúcar Rubia Premium")))
        val updated = products.findById(productId(2))!!
        assertEquals(created.createdAt, updated.createdAt)
        assertTrue(updated.updatedAt > created.updatedAt)
        products.observeForBusiness(businessId(1))
            .awaitMatching { list -> list.any { it.name == "Azúcar Rubia Premium" } }

        assertTrue(products.archive(productId(1)))
        assertEquals(CatalogStatus.ARCHIVED, products.findById(productId(1))?.status)
        assertEquals(2, products.observeForBusiness(businessId(1)).awaitMatching { it.size == 2 }.size)
        assertTrue(products.restore(productId(1)))
    }

    @Test
    fun productAndSupplierMutationsPersistOrderedCanonicalOutbox() = runBlocking {
        businesses.create(business(businessId(1), ruc = "20123456789", legalName = "Negocio"))
        units.create(unit(unitId(1), code = "NIU", name = "Unidad"))
        units.create(unit(unitId(2), code = "CJA", name = "Caja"))
        locations.create(location(locationId(1), name = "Almacén central"))

        var storedProduct = products.create(
            product(
                productId(1),
                name = "Arroz",
                sku = " arroz-01 ",
                purchaseUnitId = unitId(2),
                purchaseFactor = BigDecimal("12"),
                locationId = locationId(1),
            ),
        )
        clock.advanceSeconds(1)
        assertTrue(products.update(storedProduct.copy(name = "Arroz superior")))
        storedProduct = requireNotNull(products.findById(productId(1)))
        clock.advanceSeconds(1)
        assertTrue(products.archive(productId(1)))
        // Repetir la misma intención es idempotente y no inventa otra versión/outbox.
        assertTrue(products.archive(productId(1)))
        clock.advanceSeconds(1)
        assertTrue(products.restore(productId(1)))

        var storedSupplier = suppliers.create(
            supplier(supplierId(1), legalName = "Distribuidora Norte", tradeName = "Norte"),
        )
        clock.advanceSeconds(1)
        assertTrue(suppliers.update(storedSupplier.copy(tradeName = "Norte Perú")))
        storedSupplier = requireNotNull(suppliers.findById(supplierId(1)))
        clock.advanceSeconds(1)
        assertTrue(suppliers.archive(supplierId(1)))
        clock.advanceSeconds(1)
        assertTrue(suppliers.restore(supplierId(1)))

        val operations = database.outboxOperationDao().observeForBusiness(businessId(1).value)
            .first()
        val productOperations = operations.filter { it.entityType == PRODUCT_ENTITY_TYPE }
            .sortedBy { it.entityVersion }
        val supplierOperations = operations.filter { it.entityType == SUPPLIER_ENTITY_TYPE }
            .sortedBy { it.entityVersion }

        assertEquals(listOf(1L, 2L, 3L, 4L), productOperations.map { it.entityVersion })
        assertEquals(listOf(1L, 2L, 3L, 4L), supplierOperations.map { it.entityVersion })
        assertEquals(4L, products.findById(productId(1))?.version)
        assertEquals(4L, suppliers.findById(supplierId(1))?.version)
        productOperations.forEachIndexed { index, operation ->
            assertEquals(SYNC_PRODUCT, operation.operationType)
            assertTrue(operation.payload.contains("\"expectedVersion\":$index"))
            assertTrue(operation.payload.contains("\"targetVersion\":${index + 1}"))
            // El envelope debe llevar la identidad wire del producto. Estos fixtures usan el
            // mismo UUID crudo para IDs tipados distintos, por lo que buscar en TODO el payload
            // confundía ese entityId legítimo con una fuga de FK local. La minimización se
            // verifica exclusivamente sobre el snapshot que cruza dispositivos.
            assertTrue(operation.payload.contains("\"entityId\":\"${productId(1).value}\""))
            val snapshot = requireNotNull(operation.catalogSnapshotPayload())
            assertFalse(snapshot.contains(unitId(1).value))
            assertFalse(snapshot.contains(locationId(1).value))
            assertFalse(snapshot.contains(businessId(1).value))
        }
        supplierOperations.forEach { operation ->
            assertEquals(SYNC_SUPPLIER, operation.operationType)
            assertTrue(operation.payload.contains("\"entityId\":\"${supplierId(1).value}\""))
            val snapshot = requireNotNull(operation.catalogSnapshotPayload())
            assertFalse(snapshot.contains(businessId(1).value))
        }
    }

    @Test
    fun salePriceCasUpdatesProductAndV2OutboxAtomicallyAndRejectsStaleTenantAndInactive() = runBlocking {
        businesses.create(business(businessId(1), ruc = "20123456789", legalName = "Negocio"))
        units.create(unit(unitId(1), code = "NIU", name = "Unidad"))
        val created = products.create(product(productId(1), name = "Producto"))

        val updated = products.updateSalePrice(
            businessId = businessId(1),
            productId = created.productId,
            expectedVersion = created.version,
            salePrice = Money.ofMinor(600L, CurrencyCode.of("PEN")),
        )

        assertTrue(updated is ProductSalePriceMutationResult.Updated)
        val stored = requireNotNull(products.findById(created.productId))
        assertEquals(2L, stored.version)
        assertEquals(Money.ofMinor(600L, CurrencyCode.of("PEN")), stored.salePrice)
        val productOperations = database.outboxOperationDao()
            .observeForBusiness(businessId(1).value)
            .first()
            .filter { it.entityType == PRODUCT_ENTITY_TYPE }
            .sortedBy { it.entityVersion }
        assertEquals(listOf(1L, 2L), productOperations.map { it.entityVersion })
        val priceOperation = productOperations.last()
        assertEquals(2, priceOperation.payloadVersion)
        assertEquals("sync-product:v2:${productId(1).value}:2", priceOperation.idempotencyKey)
        assertTrue(priceOperation.payload.startsWith("{\"version\":2"))
        assertTrue(priceOperation.payload.contains("\"salePriceMinorUnits\":600"))
        assertTrue(priceOperation.payload.contains("\"salePriceCurrencyCode\":\"PEN\""))

        assertEquals(
            ProductSalePriceMutationResult.Stale,
            products.updateSalePrice(
                businessId = businessId(1),
                productId = created.productId,
                expectedVersion = created.version,
                salePrice = Money.ofMinor(700L, CurrencyCode.of("PEN")),
            ),
        )
        assertEquals(
            ProductSalePriceMutationResult.NotFound,
            products.updateSalePrice(
                businessId = businessId(2),
                productId = created.productId,
                expectedVersion = stored.version,
                salePrice = Money.ofMinor(700L, CurrencyCode.of("PEN")),
            ),
        )
        assertEquals(2, database.outboxOperationDao().observeForBusiness(businessId(1).value)
            .first().count { it.entityType == PRODUCT_ENTITY_TYPE })
        assertEquals(Money.ofMinor(600L, CurrencyCode.of("PEN")), products.findById(created.productId)?.salePrice)

        assertTrue(products.archive(created.productId))
        val archived = requireNotNull(products.findById(created.productId))
        val operationCountBeforeInactive = database.outboxOperationDao()
            .observeForBusiness(businessId(1).value).first().size
        assertEquals(
            ProductSalePriceMutationResult.Inactive,
            products.updateSalePrice(
                businessId = businessId(1),
                productId = created.productId,
                expectedVersion = archived.version,
                salePrice = Money.ofMinor(800L, CurrencyCode.of("PEN")),
            ),
        )
        assertEquals(
            operationCountBeforeInactive,
            database.outboxOperationDao().observeForBusiness(businessId(1).value).first().size,
        )
    }

    @Test
    fun salePriceOutboxConflictRollsBackTheProductCas() = runBlocking {
        businesses.create(business(businessId(1), ruc = "20123456789", legalName = "Negocio"))
        units.create(unit(unitId(1), code = "NIU", name = "Unidad"))
        val created = products.create(product(productId(1), name = "Producto"))
        val currentEntity = requireNotNull(database.productDao().findById(created.productId.value))
        val nextEntity = currentEntity.copy(
            salePriceMinorUnits = 600L,
            salePriceCurrencyCode = "PEN",
            version = 2L,
        )
        val collidingOutbox = CatalogSyncOutbox.product(
            entity = nextEntity,
            expectedVersion = 1L,
            inventoryUnit = requireNotNull(database.unitDao().findById(unitId(1).value)),
            purchaseUnit = null,
            location = null,
        ).copy(operationId = uuid(999).toString())
        database.outboxOperationDao().insert(collidingOutbox)

        val failure = assertThrows(StorageException::class.java) {
            runBlocking {
                products.updateSalePrice(
                    businessId = businessId(1),
                    productId = created.productId,
                    expectedVersion = created.version,
                    salePrice = Money.ofMinor(600L, CurrencyCode.of("PEN")),
                )
            }
        }

        assertTrue(failure.error is StorageError.ConstraintConflict)
        val after = requireNotNull(products.findById(created.productId))
        assertEquals(1L, after.version)
        assertNull(after.salePrice)
        assertEquals(
            2,
            database.outboxOperationDao().observeForBusiness(businessId(1).value).first()
                .count { it.entityType == PRODUCT_ENTITY_TYPE },
        )
    }

    @Test
    fun outboxConstraintFailureRollsBackProductCreation() = runBlocking {
        businesses.create(business(businessId(1), ruc = "20123456789", legalName = "Negocio"))
        units.create(unit(unitId(1), code = "NIU", name = "Unidad"))
        val timestamp = clock.now().toEpochMilli()
        val candidate = ProductEntity(
            productId = productId(1).value,
            businessId = businessId(1).value,
            unitId = unitId(1).value,
            name = "Producto atómico",
            createdAt = timestamp,
            updatedAt = timestamp,
        )
        val colliding = CatalogSyncOutbox.product(
            entity = candidate,
            expectedVersion = 0L,
            inventoryUnit = requireNotNull(database.unitDao().findById(unitId(1).value)),
            purchaseUnit = null,
            location = null,
        ).copy(operationId = uuid(999).toString())
        database.outboxOperationDao().insert(colliding)

        val failure = assertThrows(StorageException::class.java) {
            runBlocking { products.create(product(productId(1), name = "Producto atómico")) }
        }

        assertTrue(failure.error is StorageError.ConstraintConflict)
        assertNull(products.findById(productId(1)))
    }

    @Test
    fun aliasCrudAndNormalizedLookup() = runBlocking {
        businesses.create(business(businessId(1), ruc = "20123456789", legalName = "Negocio"))
        units.create(unit(unitId(1), code = "NIU", name = "Unidad"))
        suppliers.create(supplier(supplierId(1), legalName = "Proveedor"))
        products.create(product(productId(1), name = "Arroz Extra Costeño"))
        aliases.create(alias(aliasId(2), alias = "ARROZ X 50KG"))
        aliases.create(alias(aliasId(1), alias = "Arroz costeño 50 kg"))

        // La búsqueda puntual normaliza el alias (trim + minúsculas) antes de comparar.
        assertEquals(
            listOf(aliasId(2)),
            aliases.findByNormalizedAlias(businessId(1), "  arroz x 50kg ").map { it.aliasId },
        )
        assertEquals(
            listOf("ARROZ X 50KG", "Arroz costeño 50 kg"),
            aliases.observeForProduct(productId(1)).awaitMatching { it.size == 2 }.map { it.alias },
        )

        assertTrue(aliases.update(alias(aliasId(2), alias = "ARROZ X 25KG")))
        assertEquals(
            listOf(aliasId(2)),
            aliases.findByNormalizedAlias(businessId(1), "arroz x 25kg").map { it.aliasId },
        )
        assertTrue(aliases.deleteById(aliasId(1)))
        aliases.observeForProduct(productId(1)).awaitMatching { it.size == 1 }
        assertFalse(aliases.deleteById(aliasId(1)))
    }

    @Test
    fun productPurchaseFieldsRoundTripAndNormalizedNameLookup() = runBlocking {
        businesses.create(business(businessId(1), ruc = "20123456789", legalName = "Negocio"))
        units.create(unit(unitId(1), code = "NIU", name = "Unidad"))
        units.create(unit(unitId(2), code = "CJA", name = "Caja"))
        products.create(
            product(
                productId(1),
                name = "Arroz Extra Costeño",
                purchaseUnitId = unitId(2),
                purchaseFactor = BigDecimal("12"),
            ),
        )

        // La pareja unidad de compra/factor sobrevive al viaje Room completo.
        val stored = products.findById(productId(1))!!
        assertEquals(unitId(2), stored.purchaseUnitId)
        assertEquals(BigDecimal("12"), stored.purchaseFactor)

        clock.advanceSeconds(60)
        assertTrue(products.update(stored.copy(purchaseUnitId = null, purchaseFactor = null)))
        val cleared = products.findById(productId(1))!!
        assertNull(cleared.purchaseUnitId)
        assertNull(cleared.purchaseFactor)

        // El nombre no es único: la búsqueda puntual normaliza y devuelve lista.
        assertEquals(
            listOf("Arroz Extra Costeño"),
            products.findByNormalizedName(businessId(1), "  arroz EXTRA costeño ").map { it.name },
        )
        assertEquals(
            emptyList<Product>(),
            products.findByNormalizedName(businessId(2), "arroz extra costeño"),
        )
    }

    @Test
    fun productCanonicalSkuAndBarcodeDuplicatesBecomeConstraintConflicts() = runBlocking {
        businesses.create(business(businessId(1), ruc = "20123456789", legalName = "Negocio"))
        units.create(unit(unitId(1), code = "NIU", name = "Unidad"))
        val stored = products.create(
            product(
                productId(1),
                name = "Original",
                sku = " sku-mixta-01 ",
                barcode = " 0012345 ",
            ),
        )
        assertEquals("SKU-MIXTA-01", stored.sku)
        assertEquals("0012345", stored.barcode)

        val skuConflict = assertThrows(StorageException::class.java) {
            runBlocking {
                products.create(product(productId(2), name = "Duplicado SKU", sku = "SKU-MIXTA-01"))
            }
        }
        assertTrue(skuConflict.error is StorageError.ConstraintConflict)
        val barcodeConflict = assertThrows(StorageException::class.java) {
            runBlocking {
                products.create(product(productId(3), name = "Duplicado código", barcode = "0012345"))
            }
        }
        assertTrue(barcodeConflict.error is StorageError.ConstraintConflict)
    }

    @Test
    fun productiveRepositoriesRejectCrossBusinessProductAndAliasGraphs() = runBlocking {
        businesses.create(business(businessId(1), ruc = "20123456789", legalName = "Negocio A"))
        businesses.create(business(businessId(2), ruc = "20999999999", legalName = "Negocio B"))
        units.create(unit(unitId(1), code = "NIU", name = "Unidad A"))
        locations.create(location(locationId(1), name = "Almacén A"))

        val productConflict = assertThrows(StorageException::class.java) {
            runBlocking {
                products.create(
                    product(productId(1), name = "Cruce", locationId = locationId(1))
                        .copy(businessId = businessId(2)),
                )
            }
        }
        assertTrue(productConflict.error is StorageError.ConstraintConflict)

        suppliers.create(supplier(supplierId(1), legalName = "Proveedor A"))
        products.create(product(productId(1), name = "Producto A"))
        val aliasConflict = assertThrows(StorageException::class.java) {
            runBlocking {
                aliases.create(alias(aliasId(1), alias = "Alias cruzado").copy(businessId = businessId(2)))
            }
        }
        assertTrue(aliasConflict.error is StorageError.ConstraintConflict)
    }

    @Test
    fun creatingProductWithArchivedUnitIsRejectedByRoomAdapter() = runBlocking {
        businesses.create(business(businessId(1), ruc = "20123456789", legalName = "Negocio"))
        units.create(unit(unitId(1), code = "NIU", name = "Unidad"))
        units.archive(unitId(1))

        val conflict = assertThrows(StorageException::class.java) {
            runBlocking { products.create(product(productId(1), name = "Producto")) }
        }
        assertTrue(conflict.error is StorageError.ConstraintConflict)
    }

    @Test
    fun archivingAllCatalogsKeepsAliasesAndForeignKeysIntact() = runBlocking {
        businesses.create(business(businessId(1), ruc = "20123456789", legalName = "Negocio"))
        suppliers.create(supplier(supplierId(1), legalName = "Proveedor"))
        units.create(unit(unitId(1), code = "NIU", name = "Unidad"))
        locations.create(location(locationId(1), name = "Almacén"))
        products.create(
            product(productId(1), name = "Arroz", locationId = locationId(1)),
        )
        aliases.create(alias(aliasId(1), alias = "ARROZ X 50KG"))

        assertTrue(suppliers.archive(supplierId(1)))
        assertTrue(products.archive(productId(1)))
        assertTrue(units.archive(unitId(1)))
        assertTrue(locations.archive(locationId(1)))

        assertEquals(aliasId(1), aliases.listForProduct(productId(1)).single().aliasId)
        assertEquals(unitId(1), products.findById(productId(1))?.unitId)
        assertEquals(locationId(1), products.findById(productId(1))?.locationId)
        assertEquals(CatalogStatus.ARCHIVED, suppliers.findById(supplierId(1))?.status)
        database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { cursor ->
            assertEquals(0, cursor.count)
        }
    }

    @Test
    fun productDetailCombinesUnitAliasesAndExactInventoryProjection() = runBlocking {
        businesses.create(business(businessId(1), ruc = "20123456789", legalName = "Negocio"))
        suppliers.create(supplier(supplierId(1), legalName = "Proveedor"))
        units.create(unit(unitId(1), code = "NIU", name = "Unidad"))
        locations.create(location(locationId(1), name = "Almacén uno"))
        locations.create(location(locationId(2), name = "Almacén dos"))
        products.create(product(productId(1), name = "Arroz"))
        aliases.create(alias(aliasId(1), alias = "ARROZ X 50KG"))
        database.inventoryDao().insertBalanceIfAbsent(
            InventoryBalanceEntity(
                businessId = businessId(1).value,
                productId = productId(1).value,
                locationId = locationId(1).value,
                quantityOnHand = "2",
                averageUnitCost = "5",
                currencyCode = "PEN",
                version = 0,
                updatedAt = 1_000,
            ),
        )
        database.inventoryDao().insertBalanceIfAbsent(
            InventoryBalanceEntity(
                businessId = businessId(1).value,
                productId = productId(1).value,
                locationId = locationId(2).value,
                quantityOnHand = "3",
                averageUnitCost = "7",
                currencyCode = "PEN",
                version = 0,
                updatedAt = 1_000,
            ),
        )
        val detail = LoadProductCatalogDetailUseCase(
            products,
            units,
            aliases,
            RoomProductInventoryRepository(database.inventoryDao(), testDispatchers),
        )(productId(1))!!

        assertEquals("NIU", detail.unit.code)
        assertEquals(listOf("ARROZ X 50KG"), detail.aliases.map { it.alias })
        assertEquals(0, detail.inventory.totalQuantityOnHand.compareTo(BigDecimal("5")))
        assertEquals(
            0,
            detail.inventory.averageUnitCost!!.amount.compareTo(BigDecimal("6.2")),
        )
        assertEquals(2, detail.inventory.positions.size)
    }

    @Test
    fun profitProjectionWeightsActiveProductWarehousesAndOmitsArchivedCatalog() = runBlocking {
        businesses.create(business(businessId(1), ruc = "20123456789", legalName = "Negocio"))
        units.create(unit(unitId(1), code = "NIU", name = "Unidad"))
        locations.create(location(locationId(1), name = "Almacén uno"))
        locations.create(location(locationId(2), name = "Almacén dos"))
        val product = products.create(product(productId(1), name = "Arroz"))
        assertTrue(
            products.updateSalePrice(
                businessId = businessId(1),
                productId = product.productId,
                expectedVersion = product.version,
                salePrice = Money.ofMinor(600L, CurrencyCode.of("PEN")),
            ) is ProductSalePriceMutationResult.Updated,
        )
        listOf(
            Triple(locationId(1), "2.000", "3.005"),
            Triple(locationId(2), "1", "5.005"),
        ).forEach { (locationId, quantity, cost) ->
            database.inventoryDao().insertBalanceIfAbsent(
                InventoryBalanceEntity(
                    businessId = businessId(1).value,
                    productId = product.productId.value,
                    locationId = locationId.value,
                    quantityOnHand = quantity,
                    averageUnitCost = cost,
                    currencyCode = "PEN",
                    version = 0L,
                    updatedAt = 1_000L,
                ),
            )
        }
        val repository = RoomProductProfitRepository(database.productDao(), testDispatchers)

        val projection = repository.observeForBusiness(businessId(1)).first().single()

        assertEquals("3.671666666666666667", projection.averageUnitCost?.amount?.toPlainString())
        assertEquals("6.985000", projection.potentialProfit?.amount?.toPlainString())
        assertTrue(products.archive(product.productId))
        assertTrue(repository.observeForBusiness(businessId(1)).first().isEmpty())
    }

    @Test
    fun productSearchIsBoundedAndResponsiveWithFiveThousandOfflineRows() = runBlocking {
        businesses.create(business(businessId(1), ruc = "20123456789", legalName = "Negocio"))
        units.create(unit(unitId(1), code = "NIU", name = "Unidad"))
        val rows = (0 until 5_000).map { index ->
            ProductEntity(
                productId = uuid(10_000 + index).toString(),
                businessId = businessId(1).value,
                unitId = unitId(1).value,
                name = "Producto catálogo %04d".format(index),
                sku = "SKU-%05d".format(index),
                barcode = (7_700_000_000_000L + index).toString(),
                createdAt = 1_000L,
                updatedAt = 1_000L,
            )
        }
        database.productDao().insertAll(rows)

        lateinit var byName: com.facturastock.app.domain.model.CatalogPage<Product>
        lateinit var bySku: com.facturastock.app.domain.model.CatalogPage<Product>
        lateinit var byBarcode: com.facturastock.app.domain.model.CatalogPage<Product>
        val queryMillis = measureTimeMillis {
            byName = products.search(
                businessId(1),
                CatalogSearch(query = "producto catálogo", offset = 0, limit = 37),
            )
            bySku = products.search(
                businessId(1),
                CatalogSearch(query = "sku-04999", limit = 10),
            )
            byBarcode = products.search(
                businessId(1),
                CatalogSearch(query = (7_700_000_000_000L + 4_321).toString(), limit = 10),
            )
        }

        assertEquals(5_000, byName.total)
        assertEquals(37, byName.items.size)
        assertTrue(byName.hasMore)
        assertEquals(listOf("SKU-04999"), bySku.items.mapNotNull(Product::sku))
        assertEquals(1, byBarcode.items.size)
        // Presupuesto holgado: detecta regresiones a cargas/ordenaciones completas sin ser un microbenchmark.
        assertTrue("Las tres consultas tardaron ${queryMillis}ms", queryMillis < 10_000L)
    }

    private fun uuid(seed: Int): UUID =
        UUID.fromString("00000000-0000-0000-0000-%012d".format(seed))

    private fun businessId(seed: Int) = BusinessId.from(uuid(seed))
    private fun supplierId(seed: Int) = SupplierId.from(uuid(seed))
    private fun unitId(seed: Int) = UnitId.from(uuid(seed))
    private fun locationId(seed: Int) = LocationId.from(uuid(seed))
    private fun productId(seed: Int) = ProductId.from(uuid(seed))
    private fun aliasId(seed: Int) = AliasId.from(uuid(seed))

    private fun business(id: BusinessId, ruc: String, legalName: String) = Business(
        businessId = id,
        legalName = legalName,
        ruc = ruc,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private fun supplier(
        id: SupplierId,
        businessId: BusinessId = businessId(1),
        legalName: String,
        ruc: String? = "20987654321",
        tradeName: String? = null,
    ) = Supplier(
        supplierId = id,
        businessId = businessId,
        legalName = legalName,
        ruc = ruc,
        tradeName = tradeName,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private fun unit(id: UnitId, code: String, name: String) = UnitOfMeasure(
        unitId = id,
        businessId = businessId(1),
        code = code,
        name = name,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private fun location(id: LocationId, name: String) = InventoryLocation(
        locationId = id,
        businessId = businessId(1),
        name = name,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private fun product(
        id: ProductId,
        unitId: UnitId = unitId(1),
        name: String,
        sku: String? = null,
        barcode: String? = null,
        purchaseUnitId: UnitId? = null,
        purchaseFactor: BigDecimal? = null,
        locationId: LocationId? = null,
    ) = Product(
        productId = id,
        businessId = businessId(1),
        unitId = unitId,
        name = name,
        sku = sku,
        barcode = barcode,
        locationId = locationId,
        purchaseUnitId = purchaseUnitId,
        purchaseFactor = purchaseFactor,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private fun alias(id: AliasId, alias: String) = SupplierProductAlias(
        aliasId = id,
        businessId = businessId(1),
        supplierId = supplierId(1),
        productId = productId(1),
        alias = alias,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )
}
