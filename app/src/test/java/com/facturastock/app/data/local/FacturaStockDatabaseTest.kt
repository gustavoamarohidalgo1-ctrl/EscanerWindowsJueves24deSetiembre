package com.facturastock.app.data.local

import org.junit.rules.TemporaryFolder
import org.junit.Rule
import com.facturastock.app.data.local.sqlite.SQLiteConstraintException
import com.facturastock.app.data.local.entity.BusinessEntity
import com.facturastock.app.data.local.entity.InventoryLocationEntity
import com.facturastock.app.data.local.entity.InvoiceDraftEntity
import com.facturastock.app.data.local.entity.InvoiceImageEntity
import com.facturastock.app.data.local.entity.InvoiceLineEntity
import com.facturastock.app.data.local.entity.ProductEntity
import com.facturastock.app.data.local.entity.SupplierEntity
import com.facturastock.app.data.local.entity.SupplierProductAliasEntity
import com.facturastock.app.data.local.entity.UnitEntity
import com.facturastock.app.domain.model.DraftStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FacturaStockDatabaseTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var database: FacturaStockDatabase

    @Before
    fun createDatabase() {
        database = tempFolder.newFacturaStockDatabase()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun decimalValuesSurviveTheRoundTripWithoutLosingPrecision() = runBlocking {
        seedBusinessGraph(businessId = uuid(1), unitId = uuid(2), draftId = uuid(3))
        val precise = InvoiceLineEntity(
            lineId = uuid(4),
            draftId = uuid(3),
            businessId = uuid(1),
            position = 0,
            descriptionRaw = "PRODUCTO DE PRUEBA",
            createdAt = 10L,
            updatedAt = 10L,
            quantity = "99999999999999999999.999999999999999999",
            unitCost = "0.000000000000000001",
            unitCostCurrency = "PEN",
            lineTotalMinorUnits = Long.MAX_VALUE,
            ocrConfidence = 1_000,
        )
        database.invoiceLineDao().insert(precise)

        val restored = database.invoiceLineDao().findById(uuid(4))

        assertNotNull(restored)
        assertEquals("99999999999999999999.999999999999999999", restored!!.quantity)
        assertEquals("0.000000000000000001", restored.unitCost)
        assertEquals(Long.MAX_VALUE, restored.lineTotalMinorUnits)
        assertEquals(1_000, restored.ocrConfidence)
    }

    @Test
    fun deletingBusinessCascadesOnlyToItsOwnChildren() = runBlocking {
        seedFullGraph(idOffset = 0, ruc = "20111111111", sku = "SKU-A", barcode = "77500001")
        seedFullGraph(idOffset = 100, ruc = "20222222222", sku = "SKU-B", barcode = "77500002")

        database.businessDao().deleteById(uuid(1))

        assertEquals(0, database.supplierDao().countForBusiness(uuid(1)))
        assertEquals(0, database.productDao().countForBusiness(uuid(1)))
        assertEquals(0, database.unitDao().countForBusiness(uuid(1)))
        assertEquals(0, database.inventoryLocationDao().countForBusiness(uuid(1)))
        assertEquals(0, database.supplierProductAliasDao().countForBusiness(uuid(1)))
        assertEquals(0, database.invoiceDraftDao().countForBusiness(uuid(1)))
        assertEquals(0, database.invoiceImageDao().countForDraft(uuid(8)))
        assertEquals(0, database.invoiceLineDao().countForDraft(uuid(8)))

        assertEquals(1, database.supplierDao().countForBusiness(uuid(101)))
        assertEquals(1, database.productDao().countForBusiness(uuid(101)))
        assertEquals(1, database.unitDao().countForBusiness(uuid(101)))
        assertEquals(1, database.inventoryLocationDao().countForBusiness(uuid(101)))
        assertEquals(1, database.supplierProductAliasDao().countForBusiness(uuid(101)))
        assertEquals(1, database.invoiceDraftDao().countForBusiness(uuid(101)))
        assertEquals(1, database.invoiceImageDao().countForDraft(uuid(108)))
        assertEquals(1, database.invoiceLineDao().countForDraft(uuid(108)))
    }

    @Test
    fun deletingDraftCascadesOnlyImagesAndLines() = runBlocking {
        seedFullGraph(idOffset = 0, ruc = "20111111111", sku = "SKU-A", barcode = "77500001")

        database.invoiceDraftDao().deleteById(uuid(8))

        assertEquals(0, database.invoiceImageDao().countForDraft(uuid(8)))
        assertEquals(0, database.invoiceLineDao().countForDraft(uuid(8)))
        assertEquals(1, database.supplierDao().countForBusiness(uuid(1)))
        assertEquals(1, database.productDao().countForBusiness(uuid(1)))
        assertEquals(1, database.unitDao().countForBusiness(uuid(1)))
    }

    @Test
    fun deletingReferencedProductIsRejectedAndHistoryStaysLinked() = runBlocking {
        seedFullGraph(idOffset = 0, ruc = "20111111111", sku = "SKU-A", barcode = "77500001")

        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { database.productDao().deleteById(uuid(6)) }
        }

        assertEquals(1, database.supplierProductAliasDao().countForProduct(uuid(6)))
        val line = database.invoiceLineDao().findById(uuid(10))
        assertNotNull(line)
        assertEquals(uuid(6), line!!.productId)
    }

    @Test
    fun deletingReferencedSupplierIsRejectedAndDraftStaysLinked() = runBlocking {
        seedFullGraph(idOffset = 0, ruc = "20111111111", sku = "SKU-A", barcode = "77500001")

        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { database.supplierDao().deleteById(uuid(5)) }
        }

        assertEquals(1, database.supplierProductAliasDao().countForBusiness(uuid(1)))
        val draft = database.invoiceDraftDao().findById(uuid(8))
        assertNotNull(draft)
        assertEquals(uuid(5), draft!!.supplierId)
    }

    @Test
    fun deletingUnitInUseByProductIsRejected() = runBlocking {
        seedFullGraph(idOffset = 0, ruc = "20111111111", sku = "SKU-A", barcode = "77500001")

        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { database.unitDao().deleteById(uuid(3)) }
        }

        val line = database.invoiceLineDao().findById(uuid(10))
        assertNotNull(line)
        assertEquals(uuid(3), line!!.unitId)
    }

    @Test
    fun deletingReferencedLocationIsRejected() = runBlocking {
        seedFullGraph(idOffset = 0, ruc = "20111111111", sku = "SKU-A", barcode = "77500001")

        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { database.inventoryLocationDao().deleteById(uuid(4)) }
        }
        assertEquals(uuid(4), database.productDao().findById(uuid(6))!!.locationId)
    }

    @Test
    fun uniquenessIsEnforcedPerBusiness() = runBlocking {
        seedFullGraph(idOffset = 0, ruc = "20111111111", sku = "SKU-A", barcode = "77500001")
        seedBusinessGraph(businessId = uuid(101), unitId = uuid(103), draftId = uuid(108))

        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { database.businessDao().insert(business(uuid(50), "20111111111")) }
        }
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking {
                database.supplierDao().insert(supplier(uuid(51), uuid(1), "20333333333"))
            }
        }
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking {
                database.productDao().insert(
                    product(uuid(52), uuid(1), uuid(3), sku = "SKU-A", barcode = null),
                )
            }
        }
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking {
                database.productDao().insert(
                    product(uuid(53), uuid(1), uuid(3), sku = null, barcode = "77500001"),
                )
            }
        }
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking {
                database.invoiceImageDao().insert(
                    image(uuid(54), uuid(8), uuid(1), pageIndex = 0, sha256 = "f".repeat(64)),
                )
            }
        }
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking {
                database.invoiceLineDao().insert(
                    line(uuid(55), uuid(8), uuid(1), position = 0),
                )
            }
        }
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking {
                database.supplierProductAliasDao().insert(
                    alias(uuid(56), uuid(1), uuid(5), uuid(6), alias = "arroz extra costeño"),
                )
            }
        }

        // El mismo RUC, SKU, código de barras y alias sí pueden existir en otro negocio.
        database.supplierDao().insert(supplier(uuid(57), uuid(101), "20333333333"))
        database.productDao().insert(
            product(uuid(58), uuid(101), uuid(103), sku = "SKU-A", barcode = "77500001"),
        )
        database.supplierProductAliasDao().insert(
            alias(uuid(59), uuid(101), uuid(57), uuid(58), alias = "ARROZ EXTRA COSTEÑO"),
        )
        assertEquals(1, database.supplierDao().countForBusiness(uuid(101)))
    }

    @Test
    fun requiredIndicesExist() {
        val expected = mapOf(
            "businesses" to listOf("index_businesses_ruc"),
            "suppliers" to listOf("index_suppliers_businessId_ruc"),
            "units" to listOf("index_units_businessId_code"),
            "products" to listOf(
                "index_products_businessId_sku",
                "index_products_businessId_barcode",
                "index_products_businessId_normalizedName",
            ),
            "supplier_product_aliases" to listOf(
                "index_supplier_product_aliases_businessId_supplierId_aliasNormalized",
                "index_supplier_product_aliases_businessId_aliasNormalized",
            ),
            "invoice_drafts" to listOf(
                "index_invoice_drafts_businessId_status_updatedAt_draftId",
            ),
            "invoice_images" to listOf(
                "index_invoice_images_draftId_pageIndex",
                "index_invoice_images_businessId_sha256",
            ),
            "invoice_lines" to listOf("index_invoice_lines_draftId_position"),
            "purchases" to listOf(
                "index_purchases_businessId_supplierId_documentType_documentSeries_" +
                    "documentNumber_documentIdentitySlot",
                "index_purchases_duplicateOverrideOfPurchaseId",
            ),
        )

        expected.forEach { (table, indexNames) ->
            val actual = indexNames(table)
            indexNames.forEach { indexName ->
                assertTrue("Falta $indexName en $table", actual.contains(indexName))
            }
        }
    }

    private fun indexNames(table: String): Set<String> {
        val names = mutableSetOf<String>()
        database.readableSql
            .query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = ?", arrayOf(table))
            .use { cursor ->
                while (cursor.moveToNext()) {
                    names += cursor.getString(0)!!
                }
            }
        return names
    }

    /**
     * Crea negocio, unidad y borrador vacío. Los IDs se derivan de [businessId], [unitId] y
     * [draftId] para que las pruebas controlen el grafo completo.
     */
    private fun seedBusinessGraph(businessId: String, unitId: String, draftId: String) = runBlocking {
        database.businessDao().insert(business(businessId, rucFor(businessId)))
        database.unitDao().insert(unit(unitId, businessId))
        database.invoiceDraftDao().insert(draft(draftId, businessId))
    }

    /**
     * Grafo completo con IDs fijos por desplazamiento: negocio +1, unidad +3, ubicación +4,
     * proveedor +5, producto +6, alias +7, borrador +8, imagen +9 y línea +10.
     */
    private fun seedFullGraph(idOffset: Int, ruc: String, sku: String, barcode: String) = runBlocking {
        database.businessDao().insert(business(uuid(1 + idOffset), ruc))
        database.unitDao().insert(unit(uuid(3 + idOffset), uuid(1 + idOffset)))
        database.inventoryLocationDao().insert(location(uuid(4 + idOffset), uuid(1 + idOffset)))
        database.supplierDao().insert(supplier(uuid(5 + idOffset), uuid(1 + idOffset), "20333333333"))
        database.productDao().insert(
            product(
                uuid(6 + idOffset),
                uuid(1 + idOffset),
                uuid(3 + idOffset),
                sku = sku,
                barcode = barcode,
                locationId = uuid(4 + idOffset),
            ),
        )
        database.supplierProductAliasDao().insert(
            alias(uuid(7 + idOffset), uuid(1 + idOffset), uuid(5 + idOffset), uuid(6 + idOffset), "Arroz Extra Costeño"),
        )
        database.invoiceDraftDao().insert(
            draft(uuid(8 + idOffset), uuid(1 + idOffset), supplierId = uuid(5 + idOffset)),
        )
        database.invoiceImageDao().insert(
            image(uuid(9 + idOffset), uuid(8 + idOffset), uuid(1 + idOffset), pageIndex = 0, sha256 = "e".repeat(64)),
        )
        database.invoiceLineDao().insert(
            line(
                uuid(10 + idOffset),
                uuid(8 + idOffset),
                uuid(1 + idOffset),
                position = 0,
                productId = uuid(6 + idOffset),
                unitId = uuid(3 + idOffset),
            ),
        )
    }

    private fun uuid(seed: Int): String = "00000000-0000-0000-0000-%012d".format(seed)

    private fun rucFor(businessId: String): String = "20" + businessId.takeLast(9)

    private fun business(id: String, ruc: String) = BusinessEntity(
        businessId = id,
        legalName = "Negocio $ruc",
        ruc = ruc,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun supplier(id: String, businessId: String, ruc: String?) = SupplierEntity(
        supplierId = id,
        businessId = businessId,
        legalName = "Proveedor $id",
        createdAt = 1L,
        updatedAt = 1L,
        ruc = ruc,
    )

    private fun unit(id: String, businessId: String) = UnitEntity(
        unitId = id,
        businessId = businessId,
        code = "NIU",
        name = "Unidad",
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun location(id: String, businessId: String) = InventoryLocationEntity(
        locationId = id,
        businessId = businessId,
        name = "Almacén",
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun product(
        id: String,
        businessId: String,
        unitId: String,
        sku: String?,
        barcode: String?,
        locationId: String? = null,
    ) = ProductEntity(
        productId = id,
        businessId = businessId,
        unitId = unitId,
        name = "Arroz Extra Costeño",
        createdAt = 1L,
        updatedAt = 1L,
        locationId = locationId,
        sku = sku,
        barcode = barcode,
    )

    private fun alias(id: String, businessId: String, supplierId: String, productId: String, alias: String) =
        SupplierProductAliasEntity(
            aliasId = id,
            businessId = businessId,
            supplierId = supplierId,
            productId = productId,
            alias = alias,
            createdAt = 1L,
            updatedAt = 1L,
        )

    private fun draft(id: String, businessId: String, supplierId: String? = null) = InvoiceDraftEntity(
        draftId = id,
        businessId = businessId,
        createdAt = 1L,
        updatedAt = 1L,
        status = DraftStatus.CAPTURED.name,
        supplierId = supplierId,
    )

    private fun image(id: String, draftId: String, businessId: String, pageIndex: Int, sha256: String) =
        InvoiceImageEntity(
            imageId = id,
            draftId = draftId,
            businessId = businessId,
            pageIndex = pageIndex,
            filePath = "captures/$draftId/page-$pageIndex.jpg",
            sha256 = sha256,
            mimeType = "image/jpeg",
            widthPx = 3_000,
            heightPx = 4_000,
            fileSizeBytes = 1_000L,
            createdAt = 1L,
            rotationDegrees = 90,
        )

    private fun line(
        id: String,
        draftId: String,
        businessId: String,
        position: Int,
        productId: String? = null,
        unitId: String? = null,
    ) = InvoiceLineEntity(
        lineId = id,
        draftId = draftId,
        businessId = businessId,
        position = position,
        descriptionRaw = "ARROZ EXTRA COSTEÑO X 50 KG",
        createdAt = 1L,
        updatedAt = 1L,
        quantity = "1.500",
        unitCost = "12.3400",
        unitCostCurrency = "PEN",
        unitId = unitId,
        productId = productId,
        lineTotalMinorUnits = 1_851L,
        ocrConfidence = 998,
    )
}
