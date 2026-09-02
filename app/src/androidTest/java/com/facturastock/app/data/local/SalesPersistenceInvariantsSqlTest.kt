package com.facturastock.app.data.local

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SalesPersistenceInvariantsSqlTest {
    private lateinit var database: FacturaStockDatabase
    private lateinit var sql: SupportSQLiteDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, FacturaStockDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(postingPersistenceCallback)
            .build()
        sql = database.openHelper.writableDatabase
        seedCatalog()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun directSqlAcceptsBoundaryShapesAndRejectsMalformedSaleData() {
        assertThrows(SQLiteException::class.java) { insertDraft(contentHash = "abc") }
        assertThrows(SQLiteException::class.java) {
            insertDraft(contentHash = VALID_HASH, draftSlot = null)
        }
        insertDraft(contentHash = VALID_HASH)

        assertThrows(SQLiteException::class.java) {
            sql.execSQL(
                "UPDATE `sales` SET `contentHash`='abc',`version`=1,`updatedAt`=2 " +
                    "WHERE `saleId`='$SALE_ID'",
            )
        }
        assertThrows(SQLiteException::class.java) {
            sql.execSQL(
                "UPDATE `sales` SET `subtotalMinorUnits`=-1,`totalMinorUnits`=-1," +
                    "`version`=1,`updatedAt`=2 WHERE `saleId`='$SALE_ID'",
            )
        }

        listOf(
            "abc",
            "0",
            "1.${"0".repeat(18)}1",
            "1".repeat(39),
        ).forEach { invalidQuantity ->
            assertThrows(SQLiteException::class.java) {
                insertLine(quantity = invalidQuantity)
            }
        }
        assertThrows(SQLiteException::class.java) {
            insertLine(quantity = "1", productNameSnapshot = " Producto ")
        }
        assertThrows(SQLiteException::class.java) {
            insertLine(quantity = "1", productNameSnapshot = "P".repeat(201))
        }
        assertThrows(SQLiteException::class.java) {
            insertLine(quantity = "1", lineTotalMinorUnits = 1L)
        }
        assertThrows(SQLiteException::class.java) {
            insertLine(
                quantity = "1",
                unitPriceMinorUnits = 100L,
                taxMinorUnits = 101L,
                lineTotalMinorUnits = 100L,
            )
        }

        insertLine(
            quantity = MAX_PRECISION_AND_SCALE_QUANTITY,
            productNameSnapshot = "P".repeat(200),
            unitCodeSnapshot = "U".repeat(16),
            locationNameSnapshot = "L".repeat(100),
            barcodeSnapshot = "B".repeat(128),
        )
        assertSingleLong("SELECT COUNT(*) FROM `sale_lines`", 1L)
        assertThrows(SQLiteException::class.java) {
            sql.execSQL(
                "UPDATE `sale_lines` SET `quantity`='abc' WHERE `saleLineId`='$SALE_LINE_ID'",
            )
        }
        assertThrows(SQLiteException::class.java) {
            sql.execSQL(
                "UPDATE `sale_lines` SET `productNameSnapshot`=' Producto ' " +
                    "WHERE `saleLineId`='$SALE_LINE_ID'",
            )
        }
        sql.execSQL("DELETE FROM `sale_lines` WHERE `saleLineId`='$SALE_LINE_ID'")

        insertLine(
            quantity = "1.000000000000000000",
            unitPriceMinorUnits = 100L,
            lineTotalMinorUnits = 100L,
        )
        assertThrows(SQLiteException::class.java) {
            sql.execSQL(
                "UPDATE `sales` SET `status`='POSTED',`draftSlot`=NULL," +
                    "`checkoutIdempotencyKey`='sale-checkout:v1:sql-test'," +
                    "`subtotalMinorUnits`=100,`totalMinorUnits`=100,`version`=1," +
                    "`updatedAt`=2,`postedAt`=2 WHERE `saleId`='$SALE_ID'",
            )
        }
        sql.query(
            "SELECT `status`,`version`,`subtotalMinorUnits`,`totalMinorUnits` FROM `sales` " +
                "WHERE `saleId`='$SALE_ID'",
        ).use { cursor ->
            check(cursor.moveToFirst())
            assertEquals("DRAFT", cursor.getString(0))
            assertEquals(0L, cursor.getLong(1))
            assertEquals(0L, cursor.getLong(2))
            assertEquals(0L, cursor.getLong(3))
        }
    }

    private fun insertDraft(
        contentHash: String,
        draftSlot: String? = "$BUSINESS_ID:PEN",
    ) {
        sql.execSQL(
            "INSERT INTO `sales` (`saleId`,`businessId`,`status`,`currencyCode`," +
                "`subtotalMinorUnits`,`discountMinorUnits`,`taxMinorUnits`,`totalMinorUnits`," +
                "`contentHash`,`draftSlot`,`checkoutIdempotencyKey`,`version`,`createdAt`," +
                "`updatedAt`,`postedAt`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(
                SALE_ID,
                BUSINESS_ID,
                "DRAFT",
                "PEN",
                0L,
                0L,
                0L,
                0L,
                contentHash,
                draftSlot,
                null,
                0L,
                1L,
                1L,
                null,
            ),
        )
    }

    private fun insertLine(
        quantity: String,
        productNameSnapshot: String = "Producto",
        unitCodeSnapshot: String = "NIU",
        locationNameSnapshot: String = "Principal",
        barcodeSnapshot: String? = "001234",
        unitPriceMinorUnits: Long? = null,
        discountMinorUnits: Long = 0L,
        taxMinorUnits: Long = 0L,
        lineTotalMinorUnits: Long? = null,
    ) {
        sql.execSQL(
            "INSERT INTO `sale_lines` (`saleLineId`,`saleId`,`productId`,`unitId`,`locationId`," +
                "`position`,`productNameSnapshot`,`unitCodeSnapshot`,`locationNameSnapshot`," +
                "`barcodeSnapshot`,`quantity`,`unitPriceMinorUnits`,`discountMinorUnits`," +
                "`taxMinorUnits`,`lineTotalMinorUnits`,`currencyCode`) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(
                SALE_LINE_ID,
                SALE_ID,
                PRODUCT_ID,
                UNIT_ID,
                LOCATION_ID,
                0,
                productNameSnapshot,
                unitCodeSnapshot,
                locationNameSnapshot,
                barcodeSnapshot,
                quantity,
                unitPriceMinorUnits,
                discountMinorUnits,
                taxMinorUnits,
                lineTotalMinorUnits,
                "PEN",
            ),
        )
    }

    private fun seedCatalog() {
        sql.execSQL(
            "INSERT INTO `businesses` (`businessId`,`legalName`,`createdAt`,`updatedAt`,`ruc`," +
                "`tradeName`,`status`) VALUES ('$BUSINESS_ID','Negocio',1,1,NULL,NULL,'ACTIVE')",
        )
        sql.execSQL(
            "INSERT INTO `units` (`unitId`,`businessId`,`code`,`name`,`createdAt`,`updatedAt`," +
                "`symbol`,`status`) VALUES ('$UNIT_ID','$BUSINESS_ID','NIU','Unidad',1,1,NULL,'ACTIVE')",
        )
        sql.execSQL(
            "INSERT INTO `inventory_locations` (`locationId`,`businessId`,`name`,`createdAt`," +
                "`updatedAt`,`status`) VALUES ('$LOCATION_ID','$BUSINESS_ID','Principal',1,1,'ACTIVE')",
        )
        sql.execSQL(
            "INSERT INTO `products` (`productId`,`businessId`,`unitId`,`name`,`createdAt`," +
                "`updatedAt`,`locationId`,`sku`,`barcode`,`normalizedName`,`purchaseUnitId`," +
                "`purchaseFactor`,`status`,`version`) VALUES ('$PRODUCT_ID','$BUSINESS_ID'," +
                "'$UNIT_ID','Producto',1,1,'$LOCATION_ID',NULL,NULL,'producto',NULL,NULL,'ACTIVE',1)",
        )
    }

    private fun assertSingleLong(query: String, expected: Long) {
        sql.query(query).use { cursor ->
            check(cursor.moveToFirst())
            assertEquals(expected, cursor.getLong(0))
        }
    }

    private companion object {
        const val BUSINESS_ID = "11111111-1111-4111-8111-111111111111"
        const val UNIT_ID = "22222222-2222-4222-8222-222222222222"
        const val PRODUCT_ID = "33333333-3333-4333-8333-333333333333"
        const val LOCATION_ID = "44444444-4444-4444-8444-444444444444"
        const val SALE_ID = "55555555-5555-4555-8555-555555555555"
        const val SALE_LINE_ID = "66666666-6666-4666-8666-666666666666"
        const val VALID_HASH =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val MAX_PRECISION_AND_SCALE_QUANTITY =
            "12345678901234567890.123456789012345678"
    }
}
