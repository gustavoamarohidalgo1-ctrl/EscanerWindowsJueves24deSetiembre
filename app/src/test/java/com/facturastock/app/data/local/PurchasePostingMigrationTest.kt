package com.facturastock.app.data.local

import com.facturastock.app.data.local.sqlite.SQLiteConstraintException
import com.facturastock.app.data.local.sqlite.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Contract tests for the durable purchase ledger introduced in schema v11. */
class PurchasePostingMigrationTest {
    @get:Rule
    val helper = FacturaStockMigrationTestHelper()

    @Test
    fun migrate10To11PreservesDraftsAndReviewsAndInvalidatesLegacyPreparation() {
        val db = migrate(TEST_DB_PRESERVATION)

        db.query(
            "SELECT `businessId`, `status`, `supplierId`, `documentType`, " +
                "`documentNumberNormalized`, `currencyCode`, `totalMinorUnits`, `updatedAt` " +
                "FROM `invoice_drafts` WHERE `draftId` = '$READY_DRAFT_ID'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(BUSINESS_ID, cursor.getString(0))
            assertEquals("NEEDS_REVIEW", cursor.getString(1))
            assertEquals(SUPPLIER_ID, cursor.getString(2))
            assertEquals("INVOICE", cursor.getString(3))
            assertEquals("F001-00000042", cursor.getString(4))
            assertEquals("PEN", cursor.getString(5))
            assertEquals(1_180L, cursor.getLong(6))
            assertEquals(2_000L, cursor.getLong(7))
        }
        assertSingleLong(db, "SELECT COUNT(*) FROM `invoice_drafts`", 4L)
        assertSingleLong(db, "SELECT COUNT(*) FROM `prepared_purchases`", 0L)
        assertSingleLong(
            db,
            "SELECT `revision` FROM `invoice_header_edits` WHERE `draftId` = '$READY_DRAFT_ID'",
            7L,
        )
        assertSingleLong(
            db,
            "SELECT `revision` FROM `invoice_line_edits` WHERE `draftId` = '$READY_DRAFT_ID'",
            11L,
        )
        // v10 no tenía tabla purchases ni FK: el enlace provisional se sanea, sin borrar draft.
        db.query(
            "SELECT `confirmedPurchaseId` FROM `invoice_drafts` " +
                "WHERE `draftId` = '$FOURTH_DRAFT_ID'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
        }
        NEW_TABLES.forEach { table ->
            assertSingleLong(db, "SELECT COUNT(*) FROM `$table`", 0L)
        }
        assertNoForeignKeyViolations(db)

        helper.closeWhenFinished(db)
    }

    @Test
    fun migrate10To11EnforcesPurchaseAndOutboxIdempotency() {
        val db = migrate(TEST_DB_UNIQUENESS)
        reprepareDraft(db, READY_DRAFT_ID)
        insertPurchase(db, purchaseId = PURCHASE_ID, sourceDraftId = READY_DRAFT_ID)

        // Un encabezado aislado nunca puede saltar a POSTED por el DAO/SQL de bajo nivel.
        assertConstraint {
            db.execSQL(
                "UPDATE `purchases` SET `status` = 'POSTED', `postedAt` = 3100, " +
                    "`updatedAt` = 3100 WHERE `purchaseId` = '$PURCHASE_ID'",
            )
        }
        assertSingleText(
            db,
            "SELECT `status` FROM `purchases` WHERE `purchaseId` = '$PURCHASE_ID'",
            "DRAFT",
        )

        // At most one durable purchase may originate from a draft.
        assertConstraint {
            insertPurchase(
                db,
                purchaseId = "purchase-same-draft",
                sourceDraftId = READY_DRAFT_ID,
                idempotencyKey = "purchase-key-same-draft",
                documentSeries = "F010",
                documentNumber = "10",
            )
        }
        // A retry with the same idempotency key may not create another purchase.
        assertConstraint {
            insertPurchase(
                db,
                purchaseId = "purchase-same-idempotency",
                sourceDraftId = SECOND_DRAFT_ID,
                idempotencyKey = PURCHASE_IDEMPOTENCY_KEY,
                documentSeries = "F011",
                documentNumber = "11",
            )
        }
        // The supplier document identity is unique inside one business.
        assertConstraint {
            insertPurchase(
                db,
                purchaseId = "purchase-same-document",
                sourceDraftId = SECOND_DRAFT_ID,
                idempotencyKey = "purchase-key-same-document",
            )
        }

        // The complete document key matters: another series remains a distinct purchase.
        insertPurchase(
            db,
            purchaseId = SECOND_PURCHASE_ID,
            sourceDraftId = SECOND_DRAFT_ID,
            idempotencyKey = SECOND_PURCHASE_IDEMPOTENCY_KEY,
            documentSeries = "F002",
        )
        assertSingleLong(db, "SELECT COUNT(*) FROM `purchases`", 2L)

        insertPurchaseLine(db, purchaseId = PURCHASE_ID)
        assertConstraint {
            insertPurchaseLine(
                db,
                purchaseLineId = "line-same-position",
                purchaseId = PURCHASE_ID,
                position = 0,
            )
        }
        insertBalance(db)
        assertConstraint { insertBalance(db) }

        insertOutbox(db, operationId = OUTBOX_ID, purchaseId = PURCHASE_ID)
        assertConstraint {
            insertOutbox(
                db,
                operationId = "outbox-same-idempotency",
                purchaseId = SECOND_PURCHASE_ID,
                idempotencyKey = OUTBOX_IDEMPOTENCY_KEY,
            )
        }
        assertSingleLong(db, "SELECT COUNT(*) FROM `outbox_operations`", 1L)
        assertNoForeignKeyViolations(db)

        helper.closeWhenFinished(db)
    }

    @Test
    fun migrate10To11RejectsDuplicateStockMovement() {
        val db = migrate(TEST_DB_MOVEMENT)
        seedPostedPurchaseGraph(db)

        assertConstraint {
            insertMovement(
                db,
                movementId = "movement-same-idempotency",
                idempotencyKey = MOVEMENT_IDEMPOTENCY_KEY,
                quantityDelta = "99",
            )
        }

        assertSingleLong(db, "SELECT COUNT(*) FROM `stock_movements`", 1L)
        db.query(
            "SELECT `movementId`, `quantityDelta` FROM `stock_movements` " +
                "WHERE `idempotencyKey` = '$MOVEMENT_IDEMPOTENCY_KEY'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(MOVEMENT_ID, cursor.getString(0))
            assertEquals("2", cursor.getString(1))
        }
        assertNoForeignKeyViolations(db)

        helper.closeWhenFinished(db)
    }

    @Test
    fun migrate10To11RejectsOrphansAndDeclaresEveryForeignKey() {
        val db = migrate(TEST_DB_FOREIGN_KEYS)
        seedPostedPurchaseGraph(db)

        assertConstraint {
            insertPurchase(
                db,
                purchaseId = "purchase-orphan-business",
                sourceDraftId = SECOND_DRAFT_ID,
                businessId = MISSING_ID,
                idempotencyKey = "purchase-key-orphan-business",
                documentNumber = "101",
            )
        }
        assertConstraint {
            insertPurchase(
                db,
                purchaseId = "purchase-orphan-draft",
                sourceDraftId = MISSING_ID,
                idempotencyKey = "purchase-key-orphan-draft",
                documentNumber = "102",
            )
        }
        assertConstraint {
            insertPurchase(
                db,
                purchaseId = "purchase-orphan-supplier",
                sourceDraftId = SECOND_DRAFT_ID,
                supplierId = MISSING_ID,
                idempotencyKey = "purchase-key-orphan-supplier",
                documentNumber = "103",
            )
        }

        assertConstraint {
            insertPurchaseLine(
                db,
                purchaseLineId = "line-orphan-purchase",
                purchaseId = MISSING_ID,
                position = 10,
            )
        }
        assertConstraint {
            insertPurchaseLine(
                db,
                purchaseLineId = "line-orphan-product",
                productId = MISSING_ID,
                position = 11,
            )
        }
        assertConstraint {
            insertPurchaseLine(
                db,
                purchaseLineId = "line-orphan-unit",
                unitId = MISSING_ID,
                position = 12,
            )
        }

        assertConstraint {
            insertBalance(db, businessId = MISSING_ID)
        }
        assertConstraint {
            insertBalance(db, productId = MISSING_ID)
        }
        assertConstraint {
            insertBalance(db, locationId = MISSING_ID)
        }

        assertConstraint {
            insertMovement(
                db,
                movementId = "movement-orphan-business",
                businessId = MISSING_ID,
                idempotencyKey = "movement-key-orphan-business",
            )
        }
        assertConstraint {
            insertMovement(
                db,
                movementId = "movement-orphan-purchase",
                purchaseId = MISSING_ID,
                purchaseLineId = null,
                idempotencyKey = "movement-key-orphan-purchase",
            )
        }
        assertConstraint {
            insertMovement(
                db,
                movementId = "movement-orphan-line",
                purchaseLineId = MISSING_ID,
                idempotencyKey = "movement-key-orphan-line",
            )
        }
        assertConstraint {
            insertMovement(
                db,
                movementId = "movement-orphan-product",
                productId = MISSING_ID,
                idempotencyKey = "movement-key-orphan-product",
            )
        }
        assertConstraint {
            insertMovement(
                db,
                movementId = "movement-orphan-location",
                locationId = MISSING_ID,
                idempotencyKey = "movement-key-orphan-location",
            )
        }

        assertConstraint {
            insertAudit(
                db,
                auditEventId = "audit-orphan-business",
                businessId = MISSING_ID,
            )
        }
        assertConstraint {
            insertAudit(
                db,
                auditEventId = "audit-orphan-purchase",
                purchaseId = MISSING_ID,
            )
        }
        assertConstraint {
            insertOutbox(
                db,
                operationId = "outbox-orphan-business",
                businessId = MISSING_ID,
                idempotencyKey = "outbox-key-orphan-business",
            )
        }
        assertConstraint {
            insertOutbox(
                db,
                operationId = "outbox-orphan-purchase",
                purchaseId = MISSING_ID,
                idempotencyKey = "outbox-key-orphan-purchase",
            )
        }

        assertForeignKey(db, "purchases", "businessId", "businesses", "businessId", "CASCADE")
        assertForeignKey(db, "purchases", "sourceDraftId", "invoice_drafts", "draftId", "RESTRICT")
        assertForeignKey(db, "purchases", "supplierId", "suppliers", "supplierId", "RESTRICT")
        assertForeignKey(db, "purchase_lines", "purchaseId", "purchases", "purchaseId", "CASCADE")
        assertForeignKey(db, "purchase_lines", "productId", "products", "productId", "RESTRICT")
        assertForeignKey(db, "purchase_lines", "unitId", "units", "unitId", "RESTRICT")
        assertForeignKey(db, "inventory_balances", "businessId", "businesses", "businessId", "CASCADE")
        assertForeignKey(db, "inventory_balances", "productId", "products", "productId", "RESTRICT")
        assertForeignKey(
            db,
            "inventory_balances",
            "locationId",
            "inventory_locations",
            "locationId",
            "RESTRICT",
        )
        assertForeignKey(db, "stock_movements", "businessId", "businesses", "businessId", "CASCADE")
        assertForeignKey(db, "stock_movements", "purchaseId", "purchases", "purchaseId", "RESTRICT")
        assertForeignKey(
            db,
            "stock_movements",
            "purchaseLineId",
            "purchase_lines",
            "purchaseLineId",
            "RESTRICT",
        )
        assertForeignKey(db, "stock_movements", "productId", "products", "productId", "RESTRICT")
        assertForeignKey(
            db,
            "stock_movements",
            "locationId",
            "inventory_locations",
            "locationId",
            "RESTRICT",
        )
        assertForeignKey(db, "audit_events", "businessId", "businesses", "businessId", "CASCADE")
        assertForeignKey(db, "audit_events", "purchaseId", "purchases", "purchaseId", "RESTRICT")
        assertForeignKey(db, "outbox_operations", "businessId", "businesses", "businessId", "CASCADE")
        assertForeignKey(db, "outbox_operations", "purchaseId", "purchases", "purchaseId", "RESTRICT")
        assertNoForeignKeyViolations(db)

        helper.closeWhenFinished(db)
    }

    @Test
    fun migrate10To11MakesLedgersAppendOnlyButKeepsLifecycleAndOutboxMutable() {
        val db = migrate(TEST_DB_APPEND_ONLY)
        seedPostedPurchaseGraph(db)

        // A purchase can change only lifecycle state/timestamps; its accounting identity is immutable.
        assertConstraint {
            db.execSQL(
                "UPDATE `purchases` SET `totalMinorUnits` = 9999 " +
                    "WHERE `purchaseId` = '$PURCHASE_ID'",
            )
        }
        assertConstraint {
            db.execSQL("DELETE FROM `purchases` WHERE `purchaseId` = '$PURCHASE_ID'")
        }

        assertConstraint {
            insertPurchaseLine(
                db,
                purchaseLineId = "line-added-after-posting",
                purchaseId = PURCHASE_ID,
                position = 1,
            )
        }
        assertConstraint {
            db.execSQL(
                "UPDATE `purchase_lines` SET `quantity` = '100' " +
                    "WHERE `purchaseLineId` = '$PURCHASE_LINE_ID'",
            )
        }
        assertConstraint {
            db.execSQL(
                "DELETE FROM `purchase_lines` WHERE `purchaseLineId` = '$PURCHASE_LINE_ID'",
            )
        }

        assertConstraint {
            db.execSQL(
                "UPDATE `stock_movements` SET `quantityDelta` = '100' " +
                    "WHERE `movementId` = '$MOVEMENT_ID'",
            )
        }
        assertConstraint {
            db.execSQL("DELETE FROM `stock_movements` WHERE `movementId` = '$MOVEMENT_ID'")
        }

        assertConstraint {
            db.execSQL(
                "UPDATE `audit_events` SET `eventType` = 'TAMPERED' " +
                    "WHERE `auditEventId` = '$AUDIT_ID'",
            )
        }
        assertConstraint {
            db.execSQL("DELETE FROM `audit_events` WHERE `auditEventId` = '$AUDIT_ID'")
        }

        assertConstraint {
            db.execSQL(
                "UPDATE `prepared_purchases` SET `logicalHash` = '${"f".repeat(64)}' " +
                    "WHERE `draftId` = '$READY_DRAFT_ID'",
            )
        }
        assertSingleText(
            db,
            "SELECT `logicalHash` FROM `prepared_purchases` WHERE `draftId` = '$READY_DRAFT_ID'",
            LOGICAL_HASH,
        )

        assertConstraint { voidPurchase(db) }
        seedVoidGraph(db)
        voidPurchase(db)
        db.execSQL(
            "UPDATE `outbox_operations` SET `status` = 'COMPLETED', `attemptCount` = 1, " +
                "`updatedAt` = 4100, `completedAt` = 4100 " +
                "WHERE `operationId` = '$OUTBOX_ID'",
        )

        db.query(
            "SELECT `status`, `totalMinorUnits`, `voidedAt` FROM `purchases` " +
                "WHERE `purchaseId` = '$PURCHASE_ID'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("VOIDED", cursor.getString(0))
            assertEquals(1_180L, cursor.getLong(1))
            assertEquals(4_000L, cursor.getLong(2))
        }
        assertSingleText(
            db,
            "SELECT `quantity` FROM `purchase_lines` WHERE `purchaseLineId` = '$PURCHASE_LINE_ID'",
            "2",
        )
        assertSingleText(
            db,
            "SELECT `quantityDelta` FROM `stock_movements` WHERE `movementId` = '$MOVEMENT_ID'",
            "2",
        )
        assertSingleText(
            db,
            "SELECT `eventType` FROM `audit_events` WHERE `auditEventId` = '$AUDIT_ID'",
            "PURCHASE_POSTED",
        )
        assertSingleText(
            db,
            "SELECT `status` FROM `outbox_operations` WHERE `operationId` = '$OUTBOX_ID'",
            "COMPLETED",
        )
        assertNoForeignKeyViolations(db)

        helper.closeWhenFinished(db)
    }

    @Test
    fun completeVoidTriggerRejectsHugeDecimalsThatSQLiteNumericWouldRoundToZero() {
        val db = migrate(TEST_DB_VOID_EXACT_DECIMAL)
        val purchaseQuantity = "100000000000000000.1"
        val inexactVoidQuantity = "-100000000000000000.2"
        reprepareDraft(db, READY_DRAFT_ID)
        insertPurchase(db, purchaseId = PURCHASE_ID, sourceDraftId = READY_DRAFT_ID)
        insertPurchaseLine(db, purchaseId = PURCHASE_ID)
        insertBalance(db)
        linkPreparedDraft(db, draftId = READY_DRAFT_ID, purchaseId = PURCHASE_ID)
        insertMovement(
            db = db,
            movementId = MOVEMENT_ID,
            quantityDelta = purchaseQuantity,
        )
        insertAudit(db, auditEventId = AUDIT_ID)
        insertOutbox(db, operationId = OUTBOX_ID, purchaseId = PURCHASE_ID)
        postPurchase(db, purchaseId = PURCHASE_ID)
        seedVoidGraph(db, quantityDelta = inexactVoidQuantity)

        db.query(
            "SELECT SUM(CAST(`quantityDelta` AS NUMERIC)) FROM `stock_movements` " +
                "WHERE `purchaseId` = '$PURCHASE_ID'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            // Esta era la falsa igualdad del trigger anterior: SQLite pierde la décima distinta.
            assertEquals(0.0, cursor.getDouble(0), 0.0)
        }
        assertConstraint { voidPurchase(db) }
        assertSingleText(
            db,
            "SELECT `status` FROM `purchases` WHERE `purchaseId` = '$PURCHASE_ID'",
            "POSTED",
        )

        helper.closeWhenFinished(db)
    }

    private fun migrate(databaseName: String): SupportSQLiteDatabase {
        helper.createDatabase(databaseName, 10).apply {
            seedV10Graph(this)
            close()
        }
        return helper.runMigrationsAndValidate(
            databaseName,
            11,
            true,
            FacturaStockDatabase.MIGRATION_10_11,
        ).also { db -> db.execSQL("PRAGMA foreign_keys=ON") }
    }

    private fun seedV10Graph(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO `businesses` " +
                "(`businessId`, `legalName`, `createdAt`, `updatedAt`, `ruc`, `tradeName`, `status`) " +
                "VALUES ('$BUSINESS_ID', 'Negocio Ledger SAC', 1000, 2000, '20123456789', " +
                "'Ledger', 'ACTIVE')",
        )
        db.execSQL(
            "INSERT INTO `suppliers` " +
                "(`supplierId`, `businessId`, `legalName`, `createdAt`, `updatedAt`, `ruc`, `tradeName`, `status`) " +
                "VALUES ('$SUPPLIER_ID', '$BUSINESS_ID', 'Proveedor Uno SAC', 1000, 2000, " +
                "'20987654321', NULL, 'ACTIVE')",
        )
        db.execSQL(
            "INSERT INTO `units` " +
                "(`unitId`, `businessId`, `code`, `name`, `createdAt`, `updatedAt`, `symbol`, `status`) " +
                "VALUES ('$UNIT_ID', '$BUSINESS_ID', 'NIU', 'Unidad', 1000, 2000, 'u', 'ACTIVE')",
        )
        db.execSQL(
            "INSERT INTO `inventory_locations` " +
                "(`locationId`, `businessId`, `name`, `createdAt`, `updatedAt`, `status`) " +
                "VALUES ('$LOCATION_ID', '$BUSINESS_ID', 'Almacén principal', 1000, 2000, 'ACTIVE')",
        )
        db.execSQL(
            "INSERT INTO `products` " +
                "(`productId`, `businessId`, `unitId`, `name`, `createdAt`, `updatedAt`, " +
                "`locationId`, `sku`, `barcode`, `normalizedName`, `purchaseUnitId`, " +
                "`purchaseFactor`, `status`) VALUES ('$PRODUCT_ID', '$BUSINESS_ID', '$UNIT_ID', " +
                "'Arroz extra', 1000, 2000, '$LOCATION_ID', 'SKU-1', '7750000000001', " +
                "'arroz extra', '$UNIT_ID', '1', 'ACTIVE')",
        )
        insertV10Draft(
            db = db,
            draftId = READY_DRAFT_ID,
            status = "READY_TO_POST",
            updatedAt = 2_000L,
            documentNumber = "F001-00000042",
        )
        insertV10Draft(
            db = db,
            draftId = SECOND_DRAFT_ID,
            status = "READY_TO_POST",
            updatedAt = 2_001L,
            documentNumber = "F001-00000043",
        )
        insertV10Draft(
            db = db,
            draftId = THIRD_DRAFT_ID,
            status = "CAPTURED",
            updatedAt = 2_002L,
            documentNumber = "F001-00000044",
        )
        insertV10Draft(
            db = db,
            draftId = FOURTH_DRAFT_ID,
            status = "CAPTURED",
            updatedAt = 2_003L,
            documentNumber = "F001-00000045",
            confirmedPurchaseId = "provisional-v10-purchase",
        )
        db.execSQL(
            "INSERT INTO `invoice_header_edits` " +
                "(`draftId`, `revision`, `payloadCodecVersion`, `payloadSha256`, `payload`, `updatedAt`) " +
                "VALUES ('$READY_DRAFT_ID', 7, 1, '${"a".repeat(64)}', X'0A0B', 2050)",
        )
        db.execSQL(
            "INSERT INTO `invoice_line_edits` " +
                "(`draftId`, `revision`, `payloadCodecVersion`, `payloadSha256`, `payload`, `updatedAt`) " +
                "VALUES ('$READY_DRAFT_ID', 11, 1, '${"b".repeat(64)}', X'0C0D', 2060)",
        )
        db.execSQL(
            "INSERT INTO `prepared_purchases` " +
                "(`draftId`, `logicalHash`, `payloadCodecVersion`, `payloadSha256`, `payload`, `preparedAt`) " +
                "VALUES ('$READY_DRAFT_ID', '$LOGICAL_HASH', 1, '$PREPARED_PAYLOAD_HASH', " +
                "X'01020304', 2100)",
        )
    }

    private fun insertV10Draft(
        db: SupportSQLiteDatabase,
        draftId: String,
        status: String,
        updatedAt: Long,
        documentNumber: String,
        confirmedPurchaseId: String? = null,
    ) {
        db.execSQL(
            "INSERT INTO `invoice_drafts` " +
                "(`draftId`, `businessId`, `createdAt`, `updatedAt`, `status`, `supplierId`, " +
                "`supplierRucRaw`, `supplierRucNormalized`, `supplierLegalNameRaw`, " +
                "`supplierLegalNameNormalized`, `documentType`, `documentNumberRaw`, " +
                "`documentNumberNormalized`, `issueDateRaw`, `issueDateNormalized`, " +
                "`currencyCode`, `subtotalMinorUnits`, `taxMinorUnits`, `otherChargesMinorUnits`, " +
                "`totalMinorUnits`, `headerConfidence`, `activeOcrRunId`, `confirmedPurchaseId`, " +
                "`lastError`) VALUES ('$draftId', '$BUSINESS_ID', 1000, $updatedAt, '$status', " +
                "'$SUPPLIER_ID', '20987654321', '20987654321', 'Proveedor Uno SAC', " +
                "'proveedor uno sac', 'INVOICE', '$documentNumber', '$documentNumber', " +
                "'12/08/2026', '2026-08-12', 'PEN', 1000, 180, 0, 1180, 950, NULL, " +
                "${sqlLiteral(confirmedPurchaseId)}, NULL)",
        )
    }

    private fun seedPostedPurchaseGraph(db: SupportSQLiteDatabase) {
        reprepareDraft(db, READY_DRAFT_ID)
        insertPurchase(db, purchaseId = PURCHASE_ID, sourceDraftId = READY_DRAFT_ID)
        insertPurchaseLine(db, purchaseId = PURCHASE_ID)
        insertBalance(db)
        linkPreparedDraft(db, draftId = READY_DRAFT_ID, purchaseId = PURCHASE_ID)
        insertMovement(db, movementId = MOVEMENT_ID)
        insertAudit(db, auditEventId = AUDIT_ID)
        insertOutbox(db, operationId = OUTBOX_ID, purchaseId = PURCHASE_ID)
        postPurchase(db, purchaseId = PURCHASE_ID)
    }

    private fun reprepareDraft(db: SupportSQLiteDatabase, draftId: String) {
        db.execSQL(
            "UPDATE `invoice_drafts` SET `status` = 'READY_TO_POST', " +
                "`confirmedPurchaseId` = NULL WHERE `draftId` = '$draftId'",
        )
        db.execSQL(
            "INSERT INTO `prepared_purchases` (`draftId`, `logicalHash`, " +
                "`payloadCodecVersion`, `payloadSha256`, `payload`, `preparedAt`) VALUES (" +
                "'$draftId', '$LOGICAL_HASH', 2, '$PREPARED_PAYLOAD_HASH', X'01020304', 2100)",
        )
    }

    private fun seedVoidGraph(
        db: SupportSQLiteDatabase,
        quantityDelta: String = "-2",
    ) {
        db.execSQL(
            "UPDATE `inventory_balances` SET `quantityOnHand` = '0', " +
                "`averageUnitCost` = '0', `version` = 2, `updatedAt` = 4000 " +
                "WHERE `businessId` = '$BUSINESS_ID' AND `productId` = '$PRODUCT_ID' " +
                "AND `locationId` = '$LOCATION_ID'",
        )
        db.execSQL(
            "INSERT INTO `stock_movements` (`movementId`, `businessId`, `purchaseId`, " +
                "`purchaseLineId`, `productId`, `locationId`, `type`, `quantityDelta`, " +
                "`unitCost`, `currencyCode`, `idempotencyKey`, `occurredAt`, `createdAt`) " +
                "VALUES ('$VOID_MOVEMENT_ID', '$BUSINESS_ID', '$PURCHASE_ID', " +
                "'$PURCHASE_LINE_ID', '$PRODUCT_ID', '$LOCATION_ID', 'VOID', '$quantityDelta', " +
                "'5.00', 'PEN', 'movement-void-key', 4000, 4000)",
        )
        db.execSQL(
            "INSERT INTO `audit_events` (`auditEventId`, `businessId`, `purchaseId`, " +
                "`eventType`, `entityType`, `entityId`, `payload`, `occurredAt`) VALUES (" +
                "'$VOID_AUDIT_ID', '$BUSINESS_ID', '$PURCHASE_ID', 'PURCHASE_VOIDED', " +
                "'PURCHASE', '$PURCHASE_ID', '{}', 4000)",
        )
        db.execSQL(
            "INSERT INTO `outbox_operations` (`operationId`, `businessId`, `purchaseId`, " +
                "`idempotencyKey`, `operationType`, `payload`, `status`, `attemptCount`, " +
                "`createdAt`, `updatedAt`, `nextAttemptAt`, `completedAt`, `lastError`) VALUES (" +
                "'$VOID_OUTBOX_ID', '$BUSINESS_ID', '$PURCHASE_ID', 'outbox-void-key', " +
                "'SYNC_PURCHASE_VOID', '{}', 'PENDING', 0, 4000, 4000, NULL, NULL, NULL)",
        )
    }

    private fun voidPurchase(db: SupportSQLiteDatabase) {
        db.execSQL(
            "UPDATE `purchases` SET `status` = 'VOIDED', `updatedAt` = 4000, " +
                "`voidedAt` = 4000 WHERE `purchaseId` = '$PURCHASE_ID'",
        )
    }

    private fun linkPreparedDraft(
        db: SupportSQLiteDatabase,
        draftId: String,
        purchaseId: String,
    ) {
        db.execSQL(
            "UPDATE `invoice_drafts` SET `confirmedPurchaseId` = '$purchaseId', " +
                "`updatedAt` = 3000 WHERE `draftId` = '$draftId'",
        )
        assertSingleText(
            db,
            "SELECT `confirmedPurchaseId` FROM `invoice_drafts` WHERE `draftId` = '$draftId'",
            purchaseId,
        )
    }

    private fun insertPurchase(
        db: SupportSQLiteDatabase,
        purchaseId: String,
        sourceDraftId: String,
        businessId: String = BUSINESS_ID,
        supplierId: String = SUPPLIER_ID,
        idempotencyKey: String = PURCHASE_IDEMPOTENCY_KEY,
        documentSeries: String = "F001",
        documentNumber: String = "42",
    ) {
        db.execSQL(
            "INSERT INTO `purchases` " +
                "(`purchaseId`, `businessId`, `sourceDraftId`, `supplierId`, `documentType`, " +
                "`documentSeries`, `documentNumber`, `issueDate`, `currencyCode`, " +
                "`subtotalMinorUnits`, `taxMinorUnits`, `otherChargesMinorUnits`, " +
                "`totalMinorUnits`, `status`, `idempotencyKey`, `createdAt`, `updatedAt`, " +
                "`postedAt`, `voidedAt`) VALUES ('$purchaseId', '$businessId', '$sourceDraftId', " +
                "'$supplierId', 'INVOICE', '$documentSeries', '$documentNumber', '2026-08-12', " +
                "'PEN', 1000, 180, 0, 1180, 'DRAFT', '$idempotencyKey', 3000, 3000, NULL, NULL)",
        )
    }

    private fun postPurchase(db: SupportSQLiteDatabase, purchaseId: String) {
        db.execSQL(
            "UPDATE `purchases` SET `status` = 'POSTED', `updatedAt` = 3100, `postedAt` = 3100 " +
                "WHERE `purchaseId` = '$purchaseId' AND `status` = 'DRAFT'",
        )
        assertSingleText(
            db,
            "SELECT `status` FROM `purchases` WHERE `purchaseId` = '$purchaseId'",
            "POSTED",
        )
    }

    private fun insertPurchaseLine(
        db: SupportSQLiteDatabase,
        purchaseLineId: String = PURCHASE_LINE_ID,
        purchaseId: String = PURCHASE_ID,
        productId: String = PRODUCT_ID,
        unitId: String = UNIT_ID,
        position: Int = 0,
    ) {
        db.execSQL(
            "INSERT INTO `purchase_lines` " +
                "(`purchaseLineId`, `purchaseId`, `productId`, `unitId`, `position`, `rawText`, " +
                "`description`, `quantity`, `unitCost`, `currencyCode`, `taxMinorUnits`, " +
                "`totalMinorUnits`, `confidence`) VALUES ('$purchaseLineId', '$purchaseId', " +
                "'$productId', '$unitId', $position, '2 ARROZ EXTRA 5.00', 'Arroz extra', " +
                "'2', '5.00', 'PEN', 180, 1180, 950)",
        )
    }

    private fun insertBalance(
        db: SupportSQLiteDatabase,
        businessId: String = BUSINESS_ID,
        productId: String = PRODUCT_ID,
        locationId: String = LOCATION_ID,
    ) {
        db.execSQL(
            "INSERT INTO `inventory_balances` " +
                "(`businessId`, `productId`, `locationId`, `quantityOnHand`, `averageUnitCost`, " +
                "`currencyCode`, `version`, `updatedAt`) VALUES ('$businessId', '$productId', " +
                "'$locationId', '2', '5.00', 'PEN', 1, 3100)",
        )
    }

    private fun insertMovement(
        db: SupportSQLiteDatabase,
        movementId: String,
        businessId: String = BUSINESS_ID,
        purchaseId: String? = PURCHASE_ID,
        purchaseLineId: String? = PURCHASE_LINE_ID,
        productId: String = PRODUCT_ID,
        locationId: String = LOCATION_ID,
        idempotencyKey: String = MOVEMENT_IDEMPOTENCY_KEY,
        quantityDelta: String = "2",
    ) {
        db.execSQL(
            "INSERT INTO `stock_movements` " +
                "(`movementId`, `businessId`, `purchaseId`, `purchaseLineId`, `productId`, " +
                "`locationId`, `type`, `quantityDelta`, `unitCost`, `currencyCode`, " +
                "`idempotencyKey`, `occurredAt`, `createdAt`) VALUES ('$movementId', '$businessId', " +
                "${sqlLiteral(purchaseId)}, ${sqlLiteral(purchaseLineId)}, '$productId', '$locationId', " +
                "'PURCHASE', '$quantityDelta', '5.00', 'PEN', '$idempotencyKey', 3100, 3100)",
        )
    }

    private fun insertAudit(
        db: SupportSQLiteDatabase,
        auditEventId: String,
        businessId: String = BUSINESS_ID,
        purchaseId: String? = PURCHASE_ID,
    ) {
        db.execSQL(
            "INSERT INTO `audit_events` " +
                "(`auditEventId`, `businessId`, `purchaseId`, `eventType`, `entityType`, " +
                "`entityId`, `payload`, `occurredAt`) VALUES ('$auditEventId', '$businessId', " +
                "${sqlLiteral(purchaseId)}, 'PURCHASE_POSTED', 'PURCHASE', '$PURCHASE_ID', " +
                "'{}', 3100)",
        )
    }

    private fun insertOutbox(
        db: SupportSQLiteDatabase,
        operationId: String,
        businessId: String = BUSINESS_ID,
        purchaseId: String? = PURCHASE_ID,
        idempotencyKey: String = OUTBOX_IDEMPOTENCY_KEY,
    ) {
        db.execSQL(
            "INSERT INTO `outbox_operations` " +
                "(`operationId`, `businessId`, `purchaseId`, `idempotencyKey`, `operationType`, " +
                "`payload`, `status`, `attemptCount`, `createdAt`, `updatedAt`, `nextAttemptAt`, " +
                "`completedAt`, `lastError`) VALUES ('$operationId', '$businessId', " +
                "${sqlLiteral(purchaseId)}, '$idempotencyKey', 'SYNC_PURCHASE', '{}', " +
                "'PENDING', 0, 3100, 3100, NULL, NULL, NULL)",
        )
    }

    private fun assertForeignKey(
        db: SupportSQLiteDatabase,
        table: String,
        from: String,
        referencedTable: String,
        referencedColumn: String,
        onDelete: String,
    ) {
        var found = false
        db.query("PRAGMA foreign_key_list(`$table`)").use { cursor ->
            val tableIndex = cursor.getColumnIndexOrThrow("table")
            val fromIndex = cursor.getColumnIndexOrThrow("from")
            val toIndex = cursor.getColumnIndexOrThrow("to")
            val deleteIndex = cursor.getColumnIndexOrThrow("on_delete")
            while (cursor.moveToNext()) {
                if (
                    cursor.getString(tableIndex) == referencedTable &&
                    cursor.getString(fromIndex) == from &&
                    cursor.getString(toIndex) == referencedColumn
                ) {
                    assertEquals(onDelete, cursor.getString(deleteIndex))
                    found = true
                }
            }
        }
        assertTrue(
            "Missing FK $table.$from -> $referencedTable.$referencedColumn",
            found,
        )
    }

    private fun assertNoForeignKeyViolations(db: SupportSQLiteDatabase) {
        db.query("PRAGMA foreign_key_check").use { cursor ->
            assertFalse("Foreign-key violations remain after migration", cursor.moveToFirst())
        }
    }

    private fun assertSingleLong(db: SupportSQLiteDatabase, query: String, expected: Long) {
        db.query(query).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(expected, cursor.getLong(0))
            assertFalse(cursor.moveToNext())
        }
    }

    private fun assertSingleText(db: SupportSQLiteDatabase, query: String, expected: String) {
        db.query(query).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(expected, cursor.getString(0))
            assertFalse(cursor.moveToNext())
        }
    }

    private fun assertConstraint(block: () -> Unit) {
        assertThrows(SQLiteConstraintException::class.java) { block() }
    }

    private fun sqlLiteral(value: String?): String = value?.let { "'$it'" } ?: "NULL"

    private companion object {
        const val TEST_DB_PRESERVATION = "purchase-posting-preservation.db"
        const val TEST_DB_UNIQUENESS = "purchase-posting-uniqueness.db"
        const val TEST_DB_MOVEMENT = "purchase-posting-movement.db"
        const val TEST_DB_FOREIGN_KEYS = "purchase-posting-foreign-keys.db"
        const val TEST_DB_APPEND_ONLY = "purchase-posting-append-only.db"
        const val TEST_DB_VOID_EXACT_DECIMAL = "purchase-posting-void-exact-decimal.db"

        const val BUSINESS_ID = "11111111-1111-1111-1111-111111111111"
        const val SUPPLIER_ID = "22222222-2222-2222-2222-222222222222"
        const val UNIT_ID = "33333333-3333-3333-3333-333333333333"
        const val LOCATION_ID = "44444444-4444-4444-4444-444444444444"
        const val PRODUCT_ID = "55555555-5555-5555-5555-555555555555"
        const val READY_DRAFT_ID = "66666666-6666-6666-6666-666666666666"
        const val SECOND_DRAFT_ID = "77777777-7777-7777-7777-777777777777"
        const val THIRD_DRAFT_ID = "88888888-8888-8888-8888-888888888888"
        const val FOURTH_DRAFT_ID = "99999999-9999-9999-9999-999999999999"
        const val PURCHASE_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
        const val SECOND_PURCHASE_ID = "abababab-abab-abab-abab-abababababab"
        const val PURCHASE_LINE_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
        const val MOVEMENT_ID = "cccccccc-cccc-cccc-cccc-cccccccccccc"
        const val AUDIT_ID = "dddddddd-dddd-dddd-dddd-dddddddddddd"
        const val OUTBOX_ID = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"
        const val VOID_MOVEMENT_ID = "cdcdcdcd-cdcd-cdcd-cdcd-cdcdcdcdcdcd"
        const val VOID_AUDIT_ID = "dededede-dede-dede-dede-dededededede"
        const val VOID_OUTBOX_ID = "efefefef-efef-efef-efef-efefefefefef"
        const val MISSING_ID = "ffffffff-ffff-ffff-ffff-ffffffffffff"

        const val PURCHASE_IDEMPOTENCY_KEY = "purchase:ready-draft:v1"
        const val SECOND_PURCHASE_IDEMPOTENCY_KEY = "purchase:second-draft:v1"
        const val MOVEMENT_IDEMPOTENCY_KEY = "movement:purchase-line:v1"
        const val OUTBOX_IDEMPOTENCY_KEY = "outbox:purchase:v1"
        val LOGICAL_HASH = "c".repeat(64)
        val PREPARED_PAYLOAD_HASH = "d".repeat(64)
        val NEW_TABLES = listOf(
            "purchases",
            "purchase_lines",
            "inventory_balances",
            "stock_movements",
            "audit_events",
            "outbox_operations",
        )
    }
}
