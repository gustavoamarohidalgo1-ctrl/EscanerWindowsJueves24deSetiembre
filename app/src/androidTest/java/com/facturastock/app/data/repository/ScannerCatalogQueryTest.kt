package com.facturastock.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.FailClosedSQLiteOpenHelperFactory
import com.facturastock.app.domain.model.Business
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.UnitOfMeasure
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.UnitId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

/**
 * Las lecturas acotadas del escáner deben equivaler exactamente a filtrar la instantánea completa
 * sobre SQLite real: incluyen archivados, excluyen otros negocios y no dependen del orden.
 */
@RunWith(AndroidJUnit4::class)
class ScannerCatalogQueryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clock = TestClock(Instant.parse("2026-09-23T12:00:00Z"))
    private lateinit var databaseName: String
    private lateinit var database: FacturaStockDatabase
    private lateinit var products: RoomProductRepository

    @Before
    fun setUp() =
        runBlocking {
            databaseName = "scanner-catalog-" + UUID.randomUUID() + ".db"
            database = FacturaStockDatabase.buildNamed(context, FailClosedSQLiteOpenHelperFactory(), databaseName)
            val businesses = RoomBusinessRepository(database.businessDao(), testDispatchers, clock)
            val units = RoomUnitRepository(database.unitDao(), testDispatchers, clock)
            products = RoomProductRepository(database, database.productDao(), testDispatchers, clock)
            listOf(BUSINESS_ID to UNIT_ID, OTHER_BUSINESS_ID to OTHER_UNIT_ID).forEach { (business, unit) ->
                businesses.create(
                    Business(businessId = business, legalName = "Mayda", ruc = null, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH),
                )
                units.create(
                    UnitOfMeasure(
                        unitId = unit,
                        businessId = business,
                        code = "NIU",
                        name = "Unidad",
                        createdAt = Instant.EPOCH,
                        updatedAt = Instant.EPOCH,
                    ),
                )
            }
        }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun boundedScannerReadsMatchFilteringTheFullCatalog() =
        runBlocking {
            val seeded =
                listOf(
                    product(1, sku = null, barcode = null),
                    product(2, sku = "ARROZ-001", barcode = null),
                    product(3, sku = null, barcode = "753176004930"),
                    product(4, sku = null, barcode = "7753176004930"),
                    product(5, sku = "S-5", barcode = "77531760049301"),
                    product(6, sku = null, barcode = "12345"),
                    product(7, sku = null, barcode = "1234567"),
                ).map { products.create(it) }
            products.archive(seeded[3].productId)
            products.create(product(8, sku = null, barcode = "7753176004931", business = OTHER_BUSINESS_ID))

            val full = products.listForBusiness(BUSINESS_ID)
            assertEquals(7, full.size)
            assertTrue(full.any { it.productId == seeded[3].productId && it.status.name == "ARCHIVED" })

            assertEquals(
                full.filter { it.barcode != null || it.sku != null }.ids(),
                products.listScannerIdentityCandidates(BUSINESS_ID).ids(),
            )
            for (scannedLength in 5..13) {
                val min = scannedLength + 1
                val max = scannedLength + 2
                assertEquals(
                    "longitud $scannedLength",
                    full.filter { product -> product.barcode?.length?.let { it in min..max } == true }.ids(),
                    products.listByBarcodeLength(BUSINESS_ID, min, max).ids(),
                )
            }
        }

    private fun List<Product>.ids(): Set<ProductId> = map { it.productId }.toSortedSet(compareBy { it.value })

    private fun product(
        seed: Int,
        sku: String?,
        barcode: String?,
        business: BusinessId = BUSINESS_ID,
    ) = Product(
        productId = ProductId.from(UUID.fromString("00000000-0000-0000-0000-%012d".format(100 + seed))),
        businessId = business,
        unitId = if (business == BUSINESS_ID) UNIT_ID else OTHER_UNIT_ID,
        name = "Producto $seed",
        sku = sku,
        barcode = barcode,
        locationId = null,
        purchaseUnitId = null,
        purchaseFactor = null,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private companion object {
        val BUSINESS_ID: BusinessId = BusinessId.from(UUID.fromString("00000000-0000-0000-0000-000000000911"))
        val OTHER_BUSINESS_ID: BusinessId = BusinessId.from(UUID.fromString("00000000-0000-0000-0000-000000000912"))
        val UNIT_ID: UnitId = UnitId.from(UUID.fromString("00000000-0000-0000-0000-000000000921"))
        val OTHER_UNIT_ID: UnitId = UnitId.from(UUID.fromString("00000000-0000-0000-0000-000000000922"))
    }
}
