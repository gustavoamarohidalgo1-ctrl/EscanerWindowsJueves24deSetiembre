package com.facturastock.app.data.local

import com.facturastock.app.data.local.sqlite.SQLiteException
import com.facturastock.app.data.local.sqlite.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Regresión focal de índices, grafos tenant y atomicidad de la migración v22 -> v23. */
class DatabaseHardeningMigrationTest {
    @get:Rule
    val helper = FacturaStockMigrationTestHelper()

    @Test
    fun migrate22To23PreservesRowsAndUsesCoveringIndexes() {
        helper.createDatabase(PRESERVATION_DB, 22).apply {
            seedConsistentV22Graph()
            close()
        }

        val db = helper.runMigrationsAndValidate(
            PRESERVATION_DB,
            23,
            true,
            FacturaStockDatabase.MIGRATION_22_23,
        )
        db.execSQL("PRAGMA foreign_keys=ON")

        db.query(
            "SELECT `unitId`,`locationId`,`salePriceMinorUnits`,`salePriceCurrencyCode`,`version` " +
                "FROM `products` WHERE `productId`='$PRODUCT_A'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(UNIT_A, cursor.getString(0))
            assertEquals(LOCATION_A, cursor.getString(1))
            assertEquals(1_234L, cursor.getLong(2))
            assertEquals("PEN", cursor.getString(3))
            assertEquals(7L, cursor.getLong(4))
            assertFalse(cursor.moveToNext())
        }
        listOf(
            "supplier_product_aliases",
            "invoice_images",
            "invoice_lines",
            "purchases",
            "sales",
            "inventory_balances",
            "stock_movements",
            "audit_events",
            "outbox_operations",
            "remote_catalog_changes",
        ).forEach { table -> assertSingleLong(db, "SELECT COUNT(*) FROM `$table`", 1L) }

        val plans = mapOf(
            "index_invoice_drafts_businessId_updatedAt_draftId" to
                "SELECT * FROM invoice_drafts WHERE businessId='$BUSINESS_A' " +
                "ORDER BY updatedAt DESC, draftId DESC",
            "index_invoice_drafts_businessId_status_updatedAt_draftId" to
                "SELECT * FROM invoice_drafts WHERE businessId='$BUSINESS_A' " +
                "AND status='CAPTURED' ORDER BY updatedAt DESC, draftId DESC",
            "index_purchases_businessId_createdAt_purchaseId" to
                "SELECT * FROM purchases WHERE businessId='$BUSINESS_A' " +
                "ORDER BY createdAt DESC, purchaseId DESC LIMIT 50",
            "index_purchases_businessId_status_createdAt_purchaseId" to
                "SELECT * FROM purchases WHERE businessId='$BUSINESS_A' AND status='DRAFT' " +
                "ORDER BY createdAt DESC, purchaseId DESC LIMIT 50",
            "index_sales_businessId_status_postedAt_saleId" to
                "SELECT * FROM sales WHERE businessId='$BUSINESS_A' AND status='DRAFT' " +
                "ORDER BY postedAt DESC, saleId DESC LIMIT 50",
            "index_audit_events_businessId_occurredAt_auditEventId" to
                "SELECT * FROM audit_events WHERE businessId='$BUSINESS_A' " +
                "ORDER BY occurredAt, auditEventId",
            "index_audit_events_purchaseId_occurredAt_auditEventId" to
                "SELECT * FROM audit_events WHERE purchaseId='$PURCHASE_A' " +
                "ORDER BY occurredAt, auditEventId",
            "index_audit_events_businessId_entityType_entityId_occurredAt_auditEventId" to
                "SELECT * FROM audit_events WHERE businessId='$BUSINESS_A' " +
                "AND entityType='PRODUCT' AND entityId='$PRODUCT_A' " +
                "ORDER BY occurredAt, auditEventId",
            "index_outbox_operations_purchaseId_createdAt_operationId" to
                "SELECT * FROM outbox_operations WHERE purchaseId='$PURCHASE_A' " +
                "ORDER BY createdAt DESC, operationId DESC LIMIT 1",
            "index_remote_catalog_changes_cloudBusinessId_applicationStatus_seq" to
                "SELECT * FROM remote_catalog_changes WHERE cloudBusinessId='$CLOUD_BUSINESS' " +
                "AND applicationStatus='PENDING' ORDER BY seq",
            "index_remote_catalog_changes_cloudBusinessId_entityType_remoteEntityId_" +
                "applicationStatus_seq" to
                "SELECT 1 FROM remote_catalog_changes WHERE cloudBusinessId='$CLOUD_BUSINESS' " +
                "AND entityType='PRODUCT' AND remoteEntityId='$REMOTE_PRODUCT' " +
                "AND applicationStatus='CONFLICT' AND seq < 99",
            "index_stock_movements_businessId_productId_occurredAt_createdAt_movementId" to
                "SELECT * FROM stock_movements WHERE businessId='$BUSINESS_A' " +
                "AND productId='$PRODUCT_A' ORDER BY occurredAt,createdAt,movementId",
            "index_stock_movements_purchaseId_occurredAt_createdAt_movementId" to
                "SELECT * FROM stock_movements WHERE purchaseId='$PURCHASE_A' " +
                "ORDER BY occurredAt,createdAt,movementId",
            "index_stock_movements_saleId_occurredAt_createdAt_movementId" to
                "SELECT * FROM stock_movements WHERE saleId='$SALE_A' " +
                "ORDER BY occurredAt,createdAt,movementId",
            "index_inventory_balances_businessId_locationId_productId" to
                "SELECT * FROM inventory_balances WHERE businessId='$BUSINESS_A' " +
                "AND locationId='$LOCATION_A' ORDER BY productId",
        )
        plans.forEach { (index, query) -> assertPlanUsesIndexWithoutTemporarySort(db, query, index) }
        assertSingleLong(
            db,
            "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND " +
                "name='index_remote_movement_summaries_cloudBusinessId_seq'",
            0L,
        )
        assertSingleLong(
            db,
            "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name IN (" +
                "'catalog_products_validate_graph_insert'," +
                "'catalog_products_validate_graph_update'," +
                "'catalog_aliases_validate_graph_insert'," +
                "'catalog_aliases_validate_graph_update'," +
                "'invoice_drafts_validate_catalog_graph_insert'," +
                "'invoice_drafts_validate_catalog_graph_update'," +
                "'invoice_images_validate_graph_insert'," +
                "'invoice_images_validate_graph_update'," +
                "'invoice_lines_validate_graph_insert'," +
                "'invoice_lines_validate_graph_update')",
            10L,
        )
        assertDatabaseIsConsistent(db)
        helper.closeWhenFinished(db)
    }

    @Test
    fun migratedTenantGraphTriggersRejectCrossBusinessWrites() {
        helper.createDatabase(TENANT_GUARDS_DB, 22).apply {
            seedConsistentV22Graph()
            close()
        }
        val db = helper.runMigrationsAndValidate(
            TENANT_GUARDS_DB,
            23,
            true,
            FacturaStockDatabase.MIGRATION_22_23,
        )
        db.execSQL("PRAGMA foreign_keys=ON")

        assertThrows(SQLiteException::class.java) {
            db.execSQL(
                "INSERT INTO `products` (`productId`,`businessId`,`unitId`,`name`,`createdAt`," +
                    "`updatedAt`,`normalizedName`,`status`,`version`) VALUES " +
                    "('$INVALID_PRODUCT','$BUSINESS_A','$UNIT_B','Cruce',3000,3000," +
                    "'cruce','ACTIVE',1)",
            )
        }
        db.execSQL(
            "INSERT INTO `products` (`productId`,`businessId`,`unitId`,`name`,`createdAt`," +
                "`updatedAt`,`normalizedName`,`status`,`version`) VALUES " +
                "('$SECOND_PRODUCT_A','$BUSINESS_A','$UNIT_A','Editable',3000,3000," +
                "'editable','ACTIVE',1)",
        )
        assertThrows(SQLiteException::class.java) {
            db.execSQL(
                "UPDATE `products` SET `unitId`='$UNIT_B' WHERE `productId`='$SECOND_PRODUCT_A'",
            )
        }
        assertThrows(SQLiteException::class.java) {
            db.execSQL(
                "INSERT INTO `supplier_product_aliases` (`aliasId`,`businessId`,`supplierId`," +
                    "`productId`,`alias`,`createdAt`,`updatedAt`,`aliasNormalized`) VALUES " +
                    "('$INVALID_ALIAS','$BUSINESS_A','$SUPPLIER_B','$PRODUCT_A'," +
                    "'Cruce',3000,3000,'cruce')",
            )
        }
        assertThrows(SQLiteException::class.java) {
            db.execSQL(
                "INSERT INTO `invoice_drafts` (`draftId`,`businessId`,`createdAt`,`updatedAt`," +
                    "`status`,`supplierId`) VALUES " +
                    "('$INVALID_DRAFT','$BUSINESS_A',3000,3000,'CREATED','$SUPPLIER_B')",
            )
        }
        assertThrows(SQLiteException::class.java) {
            db.execSQL(
                "INSERT INTO `invoice_images` (`imageId`,`draftId`,`businessId`,`pageIndex`," +
                    "`filePath`,`sha256`,`mimeType`,`widthPx`,`heightPx`,`fileSizeBytes`," +
                    "`createdAt`,`rotationDegrees`) VALUES " +
                    "('$INVALID_IMAGE','$DRAFT_A','$BUSINESS_B',1,'invalid.jpg','${"c".repeat(64)}'," +
                    "'image/jpeg',100,100,1000,3000,0)",
            )
        }
        assertThrows(SQLiteException::class.java) {
            db.execSQL(
                "INSERT INTO `invoice_lines` (`lineId`,`draftId`,`businessId`,`position`," +
                    "`descriptionRaw`,`createdAt`,`updatedAt`,`unitId`) VALUES " +
                    "('$INVALID_LINE','$DRAFT_A','$BUSINESS_B',1,'Cruce',3000,3000,'$UNIT_B')",
            )
        }

        assertSingleLong(db, "SELECT COUNT(*) FROM products WHERE productId='$INVALID_PRODUCT'", 0L)
        assertSingleLong(db, "SELECT COUNT(*) FROM supplier_product_aliases", 1L)
        assertSingleLong(db, "SELECT COUNT(*) FROM invoice_images", 1L)
        assertSingleLong(db, "SELECT COUNT(*) FROM invoice_lines", 1L)
        // Los guards de UPDATE no interfieren con la cascada explícita del negocio completo.
        db.execSQL("DELETE FROM `businesses` WHERE `businessId`='$BUSINESS_B'")
        assertSingleLong(db, "SELECT COUNT(*) FROM businesses WHERE businessId='$BUSINESS_B'", 0L)
        assertSingleLong(db, "SELECT COUNT(*) FROM products WHERE productId='$PRODUCT_B'", 0L)
        assertDatabaseIsConsistent(db)
        helper.closeWhenFinished(db)
    }

    @Test
    fun tenantPreflightAbortsBeforeDdlAndPreservesLegacyV22() {
        val db = helper.createDatabase(PREFLIGHT_DB, 22)
        db.seedConsistentV22Graph()
        // V22 tiene FKs simples: la unidad existe, pero pertenece a otro negocio.
        db.execSQL("UPDATE `products` SET `unitId`='$UNIT_B' WHERE `productId`='$PRODUCT_A'")

        val failure = assertThrows(IllegalStateException::class.java) {
            FacturaStockDatabase.MIGRATION_22_23.migrate(db)
        }
        assertEquals("v23 tenant graph preflight failed", failure.message)
        assertSingleLong(db, "PRAGMA user_version", 22L)
        assertSingleText(
            db,
            "SELECT unitId FROM products WHERE productId='$PRODUCT_A'",
            UNIT_B,
        )
        assertSingleLong(
            db,
            "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND " +
                "name='index_invoice_drafts_businessId'",
            1L,
        )
        assertSingleLong(
            db,
            "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND " +
                "name='index_invoice_drafts_businessId_updatedAt_draftId'",
            0L,
        )
        helper.closeWhenFinished(db)
    }

    @Test
    fun migrationDdlRollsBackAsOneTransaction() {
        val db = helper.createDatabase(ROLLBACK_DB, 22)
        db.seedConsistentV22Graph()

        db.beginTransaction()
        try {
            FacturaStockDatabase.MIGRATION_22_23.migrate(db)
            assertSingleLong(
                db,
                "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND " +
                    "name='index_invoice_drafts_businessId_updatedAt_draftId'",
                1L,
            )
            // No setTransactionSuccessful(): simula una excepción tardía de apertura/validación.
        } finally {
            db.endTransaction()
        }

        assertSingleLong(db, "SELECT COUNT(*) FROM products WHERE productId='$PRODUCT_A'", 1L)
        assertSingleLong(
            db,
            "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND " +
                "name='index_invoice_drafts_businessId'",
            1L,
        )
        assertSingleLong(
            db,
            "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND " +
                "name='index_invoice_drafts_businessId_updatedAt_draftId'",
            0L,
        )
        helper.closeWhenFinished(db)
    }

    private fun SupportSQLiteDatabase.seedConsistentV22Graph() {
        execSQL("PRAGMA foreign_keys=ON")
        listOf(BUSINESS_A to "Negocio A", BUSINESS_B to "Negocio B").forEach { (id, name) ->
            execSQL(
                "INSERT INTO businesses (businessId,legalName,createdAt,updatedAt,status) " +
                    "VALUES ('$id','$name',1000,1000,'ACTIVE')",
            )
        }
        listOf(
            Triple(SUPPLIER_A, BUSINESS_A, "Proveedor A"),
            Triple(SUPPLIER_B, BUSINESS_B, "Proveedor B"),
        ).forEach { (id, businessId, name) ->
            execSQL(
                "INSERT INTO suppliers (supplierId,businessId,legalName,createdAt,updatedAt," +
                    "status,version) VALUES ('$id','$businessId','$name',1000,1000,'ACTIVE',1)",
            )
        }
        listOf(Triple(UNIT_A, BUSINESS_A, "NIU"), Triple(UNIT_B, BUSINESS_B, "KGM"))
            .forEach { (id, businessId, code) ->
                execSQL(
                    "INSERT INTO units (unitId,businessId,code,name,createdAt,updatedAt,status) " +
                        "VALUES ('$id','$businessId','$code','$code',1000,1000,'ACTIVE')",
                )
            }
        listOf(
            Triple(LOCATION_A, BUSINESS_A, "Principal A"),
            Triple(LOCATION_B, BUSINESS_B, "Principal B"),
        ).forEach { (id, businessId, name) ->
            execSQL(
                "INSERT INTO inventory_locations (locationId,businessId,name,createdAt,updatedAt," +
                    "status) VALUES ('$id','$businessId','$name',1000,1000,'ACTIVE')",
            )
        }
        execSQL(
            "INSERT INTO products (productId,businessId,unitId,name,createdAt,updatedAt," +
                "locationId,sku,barcode,normalizedName,salePriceMinorUnits," +
                "salePriceCurrencyCode,status,version) VALUES " +
                "('$PRODUCT_A','$BUSINESS_A','$UNIT_A','Producto A',1000,2000,'$LOCATION_A'," +
                "'SKU-A','123456789012','producto a',1234,'PEN','ACTIVE',7)",
        )
        execSQL(
            "INSERT INTO products (productId,businessId,unitId,name,createdAt,updatedAt," +
                "locationId,normalizedName,status,version) VALUES " +
                "('$PRODUCT_B','$BUSINESS_B','$UNIT_B','Producto B',1000,2000,'$LOCATION_B'," +
                "'producto b','ACTIVE',1)",
        )
        execSQL(
            "INSERT INTO supplier_product_aliases (aliasId,businessId,supplierId,productId," +
                "alias,createdAt,updatedAt,aliasNormalized) VALUES " +
                "('$ALIAS_A','$BUSINESS_A','$SUPPLIER_A','$PRODUCT_A','Producto proveedor'," +
                "1000,2000,'producto proveedor')",
        )
        listOf(
            Triple(DRAFT_A, BUSINESS_A, SUPPLIER_A),
            Triple(DRAFT_B, BUSINESS_B, SUPPLIER_B),
        ).forEach { (id, businessId, supplierId) ->
            execSQL(
                "INSERT INTO invoice_drafts (draftId,businessId,createdAt,updatedAt,status," +
                    "supplierId) VALUES ('$id','$businessId',1000,2000,'CAPTURED','$supplierId')",
            )
        }
        execSQL(
            "INSERT INTO invoice_images (imageId,draftId,businessId,pageIndex,filePath,sha256," +
                "mimeType,widthPx,heightPx,fileSizeBytes,createdAt,rotationDegrees) VALUES " +
                "('$IMAGE_A','$DRAFT_A','$BUSINESS_A',0,'invoice.jpg','${"b".repeat(64)}'," +
                "'image/jpeg',1000,1400,50000,1000,0)",
        )
        execSQL(
            "INSERT INTO invoice_lines (lineId,draftId,businessId,position,descriptionRaw," +
                "createdAt,updatedAt,descriptionNormalized,quantity,unitId,productId) VALUES " +
                "('$LINE_A','$DRAFT_A','$BUSINESS_A',0,'Producto A',1000,2000," +
                "'producto a','2.0000','$UNIT_A','$PRODUCT_A')",
        )
        execSQL(
            "INSERT INTO purchases (purchaseId,businessId,sourceDraftId,supplierId,documentType," +
                "documentSeries,documentNumber,issueDate,currencyCode,subtotalMinorUnits," +
                "taxMinorUnits,otherChargesMinorUnits,totalMinorUnits,status,idempotencyKey," +
                "createdAt,updatedAt,documentIdentitySlot) VALUES " +
                "('$PURCHASE_A','$BUSINESS_A','$DRAFT_A','$SUPPLIER_A','INVOICE','F001','1'," +
                "'2026-08-24','PEN',1000,180,0,1180,'DRAFT','purchase:v1:v23',2000,2000,'PRIMARY')",
        )
        execSQL(
            "INSERT INTO sales (saleId,businessId,status,currencyCode,subtotalMinorUnits," +
                "discountMinorUnits,taxMinorUnits,totalMinorUnits,contentHash,draftSlot," +
                "version,createdAt,updatedAt) VALUES " +
                "('$SALE_A','$BUSINESS_A','DRAFT','PEN',0,0,0,0,'${"a".repeat(64)}'," +
                "'$BUSINESS_A:PEN',0,2000,2000)",
        )
        execSQL(
            "INSERT INTO inventory_balances (businessId,productId,locationId,quantityOnHand," +
                "averageUnitCost,currencyCode,version,updatedAt) VALUES " +
                "('$BUSINESS_A','$PRODUCT_A','$LOCATION_A','10.0000','5.500000','PEN',3,2000)",
        )
        execSQL(
            "INSERT INTO stock_movements (movementId,businessId,productId,locationId,type," +
                "quantityDelta,currencyCode,idempotencyKey,occurredAt,createdAt) VALUES " +
                "('$MOVEMENT_A','$BUSINESS_A','$PRODUCT_A','$LOCATION_A','ADJUSTMENT'," +
                "'10.0000','PEN','movement:v1:v23',2000,2000)",
        )
        execSQL(
            "INSERT INTO audit_events (auditEventId,businessId,eventType,entityType,entityId," +
                "payload,occurredAt) VALUES " +
                "('$AUDIT_A','$BUSINESS_A','STOCK_ADJUSTED','PRODUCT','$PRODUCT_A','{}',2000)",
        )
        execSQL(
            "INSERT INTO outbox_operations (operationId,businessId,purchaseId,idempotencyKey," +
                "operationType,payload,status,attemptCount,createdAt,updatedAt,payloadVersion," +
                "entityType,entityId,entityVersion) VALUES " +
                "('$OUTBOX_A','$BUSINESS_A','$PURCHASE_A','sync-purchase:v1:v23'," +
                "'SYNC_PURCHASE','{}','PENDING',0,2000,2000,1,'PURCHASE','$PURCHASE_A',1)",
        )
        execSQL(
            "INSERT INTO remote_catalog_changes (cloudBusinessId,seq,entityType,remoteEntityId," +
                "remoteVersion,mutation,snapshotPayload,snapshotSha256,receiptId,origin," +
                "receivedAt,applicationStatus) VALUES " +
                "('$CLOUD_BUSINESS',1,'PRODUCT','$REMOTE_PRODUCT',1,'UPSERT','{}'," +
                "'${"d".repeat(64)}','receipt-v23','CLOUD',2000,'PENDING')",
        )
    }

    private fun assertPlanUsesIndexWithoutTemporarySort(
        db: SupportSQLiteDatabase,
        query: String,
        index: String,
    ) {
        val details = buildList {
            db.query("EXPLAIN QUERY PLAN $query").use { cursor ->
                val detailColumn = cursor.getColumnIndexOrThrow("detail")
                while (cursor.moveToNext()) add(cursor.getString(detailColumn)!!)
            }
        }
        assertTrue("Plan no usa $index: $details", details.any { it.contains(index) })
        assertTrue(
            "Plan requiere orden temporal: $details",
            details.none { it.contains("USE TEMP B-TREE") },
        )
    }

    private fun assertDatabaseIsConsistent(db: SupportSQLiteDatabase) {
        db.query("PRAGMA foreign_key_check").use { cursor -> assertEquals(0, cursor.count) }
        assertSingleText(db, "PRAGMA integrity_check", "ok")
    }

    private fun assertSingleLong(db: SupportSQLiteDatabase, sql: String, expected: Long) {
        db.query(sql).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(expected, cursor.getLong(0))
            assertFalse(cursor.moveToNext())
        }
    }

    private fun assertSingleText(db: SupportSQLiteDatabase, sql: String, expected: String) {
        db.query(sql).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(expected, cursor.getString(0))
            assertFalse(cursor.moveToNext())
        }
    }

    private companion object {
        const val PRESERVATION_DB = "hardening-preservation.db"
        const val TENANT_GUARDS_DB = "hardening-tenant-guards.db"
        const val PREFLIGHT_DB = "hardening-preflight.db"
        const val ROLLBACK_DB = "hardening-rollback.db"

        const val BUSINESS_A = "10000000-0000-4000-8000-000000000001"
        const val BUSINESS_B = "10000000-0000-4000-8000-000000000002"
        const val SUPPLIER_A = "20000000-0000-4000-8000-000000000001"
        const val SUPPLIER_B = "20000000-0000-4000-8000-000000000002"
        const val UNIT_A = "30000000-0000-4000-8000-000000000001"
        const val UNIT_B = "30000000-0000-4000-8000-000000000002"
        const val LOCATION_A = "40000000-0000-4000-8000-000000000001"
        const val LOCATION_B = "40000000-0000-4000-8000-000000000002"
        const val PRODUCT_A = "50000000-0000-4000-8000-000000000001"
        const val PRODUCT_B = "50000000-0000-4000-8000-000000000003"
        const val SECOND_PRODUCT_A = "50000000-0000-4000-8000-000000000002"
        const val INVALID_PRODUCT = "50000000-0000-4000-8000-000000000099"
        const val ALIAS_A = "60000000-0000-4000-8000-000000000001"
        const val INVALID_ALIAS = "60000000-0000-4000-8000-000000000099"
        const val DRAFT_A = "70000000-0000-4000-8000-000000000001"
        const val DRAFT_B = "70000000-0000-4000-8000-000000000002"
        const val INVALID_DRAFT = "70000000-0000-4000-8000-000000000099"
        const val IMAGE_A = "80000000-0000-4000-8000-000000000001"
        const val INVALID_IMAGE = "80000000-0000-4000-8000-000000000099"
        const val LINE_A = "90000000-0000-4000-8000-000000000001"
        const val INVALID_LINE = "90000000-0000-4000-8000-000000000099"
        const val PURCHASE_A = "a0000000-0000-4000-8000-000000000001"
        const val SALE_A = "b0000000-0000-4000-8000-000000000001"
        const val MOVEMENT_A = "c0000000-0000-4000-8000-000000000001"
        const val AUDIT_A = "d0000000-0000-4000-8000-000000000001"
        const val OUTBOX_A = "e0000000-0000-4000-8000-000000000001"
        const val CLOUD_BUSINESS = "f0000000-0000-4000-8000-000000000001"
        const val REMOTE_PRODUCT = "f0000000-0000-4000-8000-000000000002"
    }
}
