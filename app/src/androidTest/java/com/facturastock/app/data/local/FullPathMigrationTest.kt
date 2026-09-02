package com.facturastock.app.data.local

import androidx.room.migration.Migration
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal

/**
 * Recorre la cadena completa desde cada esquema histórico soportado hasta el esquema actual.
 *
 * Cada origen recibe el grafo máximo que puede representar. Los esquemas v1..v10 conservan
 * catálogo, alias, borrador y línea OCR; desde v11 también conservan compra publicada, línea de
 * compra, saldo, movimiento, auditoría y una operación outbox pendiente. Desde v13 el fixture
 * incluye además todas las decisiones decimales de costeo.
 */
@RunWith(AndroidJUnit4::class)
class FullPathMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        FacturaStockDatabase::class.java,
    )

    @Test
    fun migrate1To23PreservesMaximumRepresentableGraph() = verifyFullPathFrom(1)

    @Test
    fun migrate2To23PreservesMaximumRepresentableGraph() = verifyFullPathFrom(2)

    @Test
    fun migrate3To23PreservesMaximumRepresentableGraph() = verifyFullPathFrom(3)

    @Test
    fun migrate4To23PreservesMaximumRepresentableGraph() = verifyFullPathFrom(4)

    @Test
    fun migrate5To23PreservesMaximumRepresentableGraph() = verifyFullPathFrom(5)

    @Test
    fun migrate6To23PreservesMaximumRepresentableGraph() = verifyFullPathFrom(6)

    @Test
    fun migrate7To23PreservesMaximumRepresentableGraph() = verifyFullPathFrom(7)

    @Test
    fun migrate8To23PreservesMaximumRepresentableGraph() = verifyFullPathFrom(8)

    @Test
    fun migrate9To23PreservesMaximumRepresentableGraph() = verifyFullPathFrom(9)

    @Test
    fun migrate10To23PreservesMaximumRepresentableGraph() = verifyFullPathFrom(10)

    @Test
    fun migrate11To23PreservesMaximumRepresentableGraph() = verifyFullPathFrom(11)

    @Test
    fun migrate12To23PreservesMaximumRepresentableGraph() = verifyFullPathFrom(12)

    @Test
    fun migrate13To23PreservesMaximumRepresentableGraph() = verifyFullPathFrom(13)

    @Test
    fun migrate14To23PreservesMaximumRepresentableGraph() = verifyFullPathFrom(14)

    @Test
    fun migrate15To23PreservesMaximumRepresentableGraph() = verifyFullPathFrom(15)

    @Test
    fun migrate16To23PreservesMaximumRepresentableGraph() = verifyFullPathFrom(16)

    @Test
    fun migrate17To23PreservesMaximumRepresentableGraph() = verifyFullPathFrom(17)

    @Test
    fun migrate18To23PreservesMaximumRepresentableGraph() = verifyFullPathFrom(18)

    @Test
    fun migrate19To23PreservesMaximumRepresentableGraph() = verifyFullPathFrom(19)

    @Test
    fun migrate20To23PreservesMaximumRepresentableGraph() = verifyFullPathFrom(20)

    @Test
    fun migrate21To23PreservesMaximumRepresentableGraph() = verifyFullPathFrom(21)

    @Test
    fun migrate22To23PreservesMaximumRepresentableGraph() = verifyFullPathFrom(22)

    private fun verifyFullPathFrom(sourceVersion: Int) {
        val databaseName = "full-path-migration-v$sourceVersion.db"
        helper.createDatabase(databaseName, sourceVersion).apply {
            seedMaximumRepresentableGraph(sourceVersion)
            close()
        }

        val db = helper.runMigrationsAndValidate(
            databaseName,
            CURRENT_SCHEMA_VERSION,
            true,
            *ALL_MIGRATIONS,
        )
        db.execSQL("PRAGMA foreign_keys=ON")

        assertSingleLong(db, "PRAGMA user_version", CURRENT_SCHEMA_VERSION.toLong())
        assertCommonGraph(db, sourceVersion)
        if (sourceVersion >= LEDGER_SCHEMA_VERSION) {
            assertRichLedgerGraph(db, sourceVersion)
        } else {
            assertLedgerTablesAreEmpty(db)
        }
        assertCloudTenantBindingSchema(db)
        assertNoForeignKeyViolations(db)
        assertIntegrityCheckPasses(db)

        helper.closeWhenFinished(db)
    }

    private fun SupportSQLiteDatabase.seedMaximumRepresentableGraph(sourceVersion: Int) {
        execSQL("PRAGMA foreign_keys=ON")
        val needsCatalogCanonicalization = sourceVersion <= CATALOG_CANONICALIZATION_SOURCE
        val supplierRuc = if (needsCatalogCanonicalization) " 20987654321 " else SUPPLIER_RUC
        val productSku = if (needsCatalogCanonicalization) " sku-rich-01 " else PRODUCT_SKU
        val productBarcode = if (needsCatalogCanonicalization) " 001234500067 " else PRODUCT_BARCODE
        val hasOtherCharges = sourceVersion >= HEADER_REVIEW_SCHEMA_VERSION
        val hasCatalogVersion = sourceVersion >= CATALOG_VERSION_SCHEMA_VERSION
        val catalogVersionColumn = if (hasCatalogVersion) ",`version`" else ""
        val catalogVersionValue = if (hasCatalogVersion) ",1" else ""
        val totalMinorUnits = if (hasOtherCharges) TOTAL_MINOR_UNITS else LEGACY_TOTAL_MINOR_UNITS
        val draftStatus = when {
            sourceVersion < PREPARED_PURCHASE_SCHEMA_VERSION -> "CAPTURED"
            sourceVersion < DUPLICATE_OVERRIDE_SCHEMA_VERSION -> "READY_TO_POST"
            else -> "COMMITTED"
        }
        val draftUpdatedAt =
            if (sourceVersion >= DUPLICATE_OVERRIDE_SCHEMA_VERSION) POSTED_AT else DRAFT_UPDATED_AT

        execSQL(
            "INSERT INTO `businesses` " +
                "(`businessId`,`legalName`,`ruc`,`createdAt`,`updatedAt`,`tradeName`,`status`) " +
                "VALUES ('$BUSINESS_ID','Negocio histórico SAC','$BUSINESS_RUC'," +
                "$CREATED_AT,$DRAFT_UPDATED_AT,'Negocio histórico','ACTIVE')",
        )
        execSQL(
            "INSERT INTO `suppliers` " +
                "(`supplierId`,`businessId`,`legalName`,`createdAt`,`updatedAt`,`ruc`," +
                "`tradeName`,`status`$catalogVersionColumn) VALUES ('$SUPPLIER_ID','$BUSINESS_ID'," +
                "'Proveedor histórico',$CREATED_AT,$DRAFT_UPDATED_AT,'$supplierRuc'," +
                "'Proveedor','$ACTIVE_STATUS'$catalogVersionValue)",
        )
        execSQL(
            "INSERT INTO `units` " +
                "(`unitId`,`businessId`,`code`,`name`,`createdAt`,`updatedAt`,`symbol`,`status`) VALUES " +
                "('$UNIT_ID','$BUSINESS_ID','NIU','Unidad',$CREATED_AT,$DRAFT_UPDATED_AT," +
                "'u','$ACTIVE_STATUS')," +
                "('$PURCHASE_UNIT_ID','$BUSINESS_ID','CJA','Caja',$CREATED_AT,$DRAFT_UPDATED_AT," +
                "'caja','$ACTIVE_STATUS')",
        )
        execSQL(
            "INSERT INTO `inventory_locations` " +
                "(`locationId`,`businessId`,`name`,`createdAt`,`updatedAt`,`status`) " +
                "VALUES ('$LOCATION_ID','$BUSINESS_ID','Almacén principal'," +
                "$CREATED_AT,$DRAFT_UPDATED_AT,'$ACTIVE_STATUS')",
        )

        if (sourceVersion >= PURCHASE_UNIT_SCHEMA_VERSION) {
            execSQL(
                "INSERT INTO `products` " +
                    "(`productId`,`businessId`,`unitId`,`name`,`createdAt`,`updatedAt`," +
                    "`locationId`,`sku`,`barcode`,`normalizedName`,`purchaseUnitId`," +
                    "`purchaseFactor`,`status`$catalogVersionColumn) VALUES ('$PRODUCT_ID','$BUSINESS_ID'," +
                    "'$UNIT_ID','Producto histórico',$CREATED_AT,$DRAFT_UPDATED_AT," +
                    "'$LOCATION_ID','$productSku','$productBarcode','producto historico'," +
                    "'$PURCHASE_UNIT_ID','$PURCHASE_FACTOR','$ACTIVE_STATUS'$catalogVersionValue)",
            )
        } else {
            execSQL(
                "INSERT INTO `products` " +
                    "(`productId`,`businessId`,`unitId`,`name`,`createdAt`,`updatedAt`," +
                    "`locationId`,`sku`,`barcode`,`normalizedName`,`status`$catalogVersionColumn) VALUES (" +
                    "'$PRODUCT_ID','$BUSINESS_ID','$UNIT_ID','Producto histórico'," +
                    "$CREATED_AT,$DRAFT_UPDATED_AT,'$LOCATION_ID','$productSku'," +
                    "'$productBarcode','producto historico','$ACTIVE_STATUS'$catalogVersionValue)",
            )
        }
        execSQL(
            "INSERT INTO `supplier_product_aliases` " +
                "(`aliasId`,`businessId`,`supplierId`,`productId`,`alias`,`createdAt`," +
                "`updatedAt`,`aliasNormalized`) VALUES ('$ALIAS_ID','$BUSINESS_ID'," +
                "'$SUPPLIER_ID','$PRODUCT_ID','Caja Histórica',$CREATED_AT,$DRAFT_UPDATED_AT," +
                "'caja historica')",
        )
        execSQL(
            "INSERT INTO `invoice_drafts` " +
                "(`draftId`,`businessId`,`createdAt`,`updatedAt`,`status`,`supplierId`," +
                "`supplierRucRaw`,`supplierRucNormalized`,`documentNumberRaw`," +
                "`documentNumberNormalized`,`issueDateRaw`,`issueDateNormalized`," +
                "`currencyCode`,`subtotalMinorUnits`,`taxMinorUnits`,`totalMinorUnits`," +
                "`headerConfidence`,`confirmedPurchaseId`,`lastError`) VALUES (" +
                "'$DRAFT_ID','$BUSINESS_ID',$CREATED_AT,$draftUpdatedAt,'$draftStatus'," +
                "'$SUPPLIER_ID','$SUPPLIER_RUC','$SUPPLIER_RUC','F001-42','F001-42'," +
                "'2026-08-13','2026-08-13','PEN',$SUBTOTAL_MINOR_UNITS,$TAX_MINOR_UNITS," +
                "$totalMinorUnits,975,NULL,NULL)",
        )
        if (hasOtherCharges) {
            execSQL(
                "UPDATE `invoice_drafts` SET `supplierLegalNameRaw`='Proveedor histórico'," +
                    "`supplierLegalNameNormalized`='proveedor historico'," +
                    "`documentType`='INVOICE',`otherChargesMinorUnits`=$OTHER_CHARGES_MINOR_UNITS " +
                    "WHERE `draftId`='$DRAFT_ID'",
            )
        }
        execSQL(
            "INSERT INTO `invoice_lines` " +
                "(`lineId`,`draftId`,`businessId`,`position`,`descriptionRaw`,`createdAt`," +
                "`updatedAt`,`descriptionNormalized`,`quantity`,`unitCost`,`unitCostCurrency`," +
                "`unitId`,`productId`,`lineTotalMinorUnits`,`ocrConfidence`,`linkConfidence`) " +
                "VALUES ('$INVOICE_LINE_ID','$DRAFT_ID','$BUSINESS_ID',0,'Caja Histórica'," +
                "$CREATED_AT,$DRAFT_UPDATED_AT,'caja historica','$PURCHASE_QUANTITY'," +
                "'$READ_UNIT_COST','PEN','$PURCHASE_UNIT_ID','$PRODUCT_ID',$totalMinorUnits," +
                "960,940)",
        )
        if (sourceVersion >= TYPED_LINE_SCHEMA_VERSION) {
            execSQL(
                "UPDATE `invoice_lines` SET `codeRaw`=' sku-rich-01 '," +
                    "`codeNormalized`='$PRODUCT_SKU',`unitRaw`='Caja'," +
                    "`unitCodeNormalized`='CJA',`discountMinorUnits`=0," +
                    "`taxMinorUnits`=$TAX_MINOR_UNITS WHERE `lineId`='$INVOICE_LINE_ID'",
            )
        }

        if (sourceVersion >= PREPARED_PURCHASE_SCHEMA_VERSION) {
            insertPreparedPurchase()
        }
        if (sourceVersion >= LEDGER_SCHEMA_VERSION) {
            insertRichLedger(sourceVersion)
        }
    }

    private fun SupportSQLiteDatabase.insertPreparedPurchase() {
        execSQL(
            "INSERT INTO `prepared_purchases` " +
                "(`draftId`,`logicalHash`,`payloadCodecVersion`,`payloadSha256`,`payload`," +
                "`preparedAt`) VALUES ('$DRAFT_ID','${"a".repeat(64)}',2," +
                "'${"b".repeat(64)}',X'01020304',$PREPARED_AT)",
        )
    }

    private fun SupportSQLiteDatabase.insertRichLedger(sourceVersion: Int) {
        val purchaseSnapshotColumns = if (sourceVersion >= PURCHASE_SNAPSHOT_SCHEMA_VERSION) {
            ",`productNameSnapshot`,`unitCodeSnapshot`"
        } else {
            ""
        }
        val purchaseSnapshotValues = if (sourceVersion >= PURCHASE_SNAPSHOT_SCHEMA_VERSION) {
            ",'Producto histórico','CJA'"
        } else {
            ""
        }
        if (sourceVersion >= DUPLICATE_OVERRIDE_SCHEMA_VERSION) {
            execSQL(
                "INSERT INTO `purchases` " +
                    "(`purchaseId`,`businessId`,`sourceDraftId`,`supplierId`,`documentType`," +
                    "`documentSeries`,`documentNumber`,`issueDate`,`currencyCode`," +
                    "`subtotalMinorUnits`,`taxMinorUnits`,`otherChargesMinorUnits`," +
                    "`totalMinorUnits`,`status`,`idempotencyKey`,`createdAt`,`updatedAt`," +
                    "`postedAt`,`voidedAt`,`documentIdentitySlot`,`duplicateOverrideOfPurchaseId`," +
                    "`duplicateOverrideReason`,`duplicateOverrideActorId`,`duplicateOverrideRole`) " +
                    "VALUES ('$PURCHASE_ID','$BUSINESS_ID','$DRAFT_ID','$SUPPLIER_ID','INVOICE'," +
                    "'F001','42','2026-08-13','PEN',$SUBTOTAL_MINOR_UNITS,$TAX_MINOR_UNITS," +
                    "$OTHER_CHARGES_MINOR_UNITS,$TOTAL_MINOR_UNITS,'POSTED'," +
                    "'$PURCHASE_IDEMPOTENCY_KEY',$PURCHASE_CREATED_AT,$POSTED_AT,$POSTED_AT,NULL," +
                    "'PRIMARY',NULL,NULL,NULL,NULL)",
            )
        } else {
            execSQL(
                "INSERT INTO `purchases` " +
                    "(`purchaseId`,`businessId`,`sourceDraftId`,`supplierId`,`documentType`," +
                    "`documentSeries`,`documentNumber`,`issueDate`,`currencyCode`," +
                    "`subtotalMinorUnits`,`taxMinorUnits`,`otherChargesMinorUnits`," +
                    "`totalMinorUnits`,`status`,`idempotencyKey`,`createdAt`,`updatedAt`," +
                    "`postedAt`,`voidedAt`) VALUES ('$PURCHASE_ID','$BUSINESS_ID','$DRAFT_ID'," +
                    "'$SUPPLIER_ID','INVOICE','F001','42','2026-08-13','PEN'," +
                    "$SUBTOTAL_MINOR_UNITS,$TAX_MINOR_UNITS,$OTHER_CHARGES_MINOR_UNITS," +
                    "$TOTAL_MINOR_UNITS,'POSTED','$PURCHASE_IDEMPOTENCY_KEY'," +
                    "$PURCHASE_CREATED_AT,$POSTED_AT,$POSTED_AT,NULL)",
            )
        }

        if (sourceVersion >= COSTING_AUDIT_SCHEMA_VERSION) {
            execSQL(
                "INSERT INTO `purchase_lines` " +
                    "(`purchaseLineId`,`purchaseId`,`productId`,`unitId`$purchaseSnapshotColumns," +
                    "`position`,`rawText`," +
                    "`description`,`quantity`,`unitCost`,`currencyCode`,`taxMinorUnits`," +
                    "`totalMinorUnits`,`confidence`,`appliedUnitCost`,`purchaseUnitFactor`," +
                    "`inventoryQuantity`,`discount`,`taxTreatment`,`costPolicy`," +
                    "`appliedCostTotal`,`taxEvidenceType`,`taxEvidenceValue`,`roundingScale`," +
                    "`roundingMode`,`costingWarnings`) VALUES ('$PURCHASE_LINE_ID','$PURCHASE_ID'," +
                    "'$PRODUCT_ID','$PURCHASE_UNIT_ID'$purchaseSnapshotValues," +
                    "0,'12.5000 CAJA HISTÓRICA'," +
                    "'Caja Histórica','$PURCHASE_QUANTITY','$READ_UNIT_COST','PEN'," +
                    "$TAX_MINOR_UNITS,$TOTAL_MINOR_UNITS,950,'$APPLIED_UNIT_COST'," +
                    "'$PURCHASE_FACTOR','$INVENTORY_QUANTITY','$DISCOUNT','EXCLUDED','NET'," +
                    "'$APPLIED_COST_TOTAL','EXPLICIT_AMOUNT','$TAX_EVIDENCE_VALUE'," +
                    "$ROUNDING_SCALE,'HALF_EVEN','[]')",
            )
        } else {
            execSQL(
                "INSERT INTO `purchase_lines` " +
                    "(`purchaseLineId`,`purchaseId`,`productId`,`unitId`$purchaseSnapshotColumns," +
                    "`position`,`rawText`," +
                    "`description`,`quantity`,`unitCost`,`currencyCode`,`taxMinorUnits`," +
                    "`totalMinorUnits`,`confidence`) VALUES ('$PURCHASE_LINE_ID','$PURCHASE_ID'," +
                    "'$PRODUCT_ID','$PURCHASE_UNIT_ID'$purchaseSnapshotValues," +
                    "0,'12.5000 CAJA HISTÓRICA'," +
                    "'Caja Histórica','$PURCHASE_QUANTITY','$READ_UNIT_COST','PEN'," +
                    "$TAX_MINOR_UNITS,$TOTAL_MINOR_UNITS,950)",
            )
        }
        execSQL(
            "INSERT INTO `inventory_balances` " +
                "(`businessId`,`productId`,`locationId`,`quantityOnHand`,`averageUnitCost`," +
                "`currencyCode`,`version`,`updatedAt`) VALUES ('$BUSINESS_ID','$PRODUCT_ID'," +
                "'$LOCATION_ID','$INVENTORY_QUANTITY','$APPLIED_UNIT_COST','PEN'," +
                "$BALANCE_VERSION,$POSTED_AT)",
        )
        execSQL(
            "INSERT INTO `stock_movements` " +
                "(`movementId`,`businessId`,`purchaseId`,`purchaseLineId`,`productId`," +
                "`locationId`,`type`,`quantityDelta`,`unitCost`,`currencyCode`,`idempotencyKey`," +
                "`occurredAt`,`createdAt`) VALUES ('$MOVEMENT_ID','$BUSINESS_ID','$PURCHASE_ID'," +
                "'$PURCHASE_LINE_ID','$PRODUCT_ID','$LOCATION_ID','PURCHASE'," +
                "'$INVENTORY_QUANTITY','$APPLIED_UNIT_COST','PEN','$MOVEMENT_IDEMPOTENCY_KEY'," +
                "$POSTED_AT,$POSTED_AT)",
        )
        execSQL(
            "INSERT INTO `audit_events` " +
                "(`auditEventId`,`businessId`,`purchaseId`,`eventType`,`entityType`,`entityId`," +
                "`payload`,`occurredAt`) VALUES ('$AUDIT_EVENT_ID','$BUSINESS_ID','$PURCHASE_ID'," +
                "'PURCHASE_POSTED','PURCHASE','$PURCHASE_ID','{}',$POSTED_AT)",
        )
        if (sourceVersion >= CLAIM_PROTOCOL_SCHEMA_VERSION) {
            execSQL(
                "INSERT INTO `outbox_operations` " +
                    "(`operationId`,`businessId`,`purchaseId`,`idempotencyKey`,`operationType`," +
                    "`payload`,`status`,`attemptCount`,`createdAt`,`updatedAt`,`nextAttemptAt`," +
                    "`completedAt`,`lastError`,`payloadVersion`,`claimToken`,`claimLeaseUntil`) " +
                    "VALUES ('$OUTBOX_ID','$BUSINESS_ID','$PURCHASE_ID'," +
                    "'$OUTBOX_IDEMPOTENCY_KEY','SYNC_PURCHASE','{\"version\":2}'," +
                    "'PENDING',$OUTBOX_ATTEMPTS,$OUTBOX_CREATED_AT,$OUTBOX_UPDATED_AT," +
                    "$OUTBOX_NEXT_ATTEMPT_AT,NULL,'NETWORK_UNAVAILABLE',2,NULL,NULL)",
            )
        } else {
            execSQL(
                "INSERT INTO `outbox_operations` " +
                    "(`operationId`,`businessId`,`purchaseId`,`idempotencyKey`,`operationType`," +
                    "`payload`,`status`,`attemptCount`,`createdAt`,`updatedAt`,`nextAttemptAt`," +
                    "`completedAt`,`lastError`) VALUES ('$OUTBOX_ID','$BUSINESS_ID'," +
                    "'$PURCHASE_ID','$OUTBOX_IDEMPOTENCY_KEY','SYNC_PURCHASE'," +
                    "'{\"version\":2}','PENDING',$OUTBOX_ATTEMPTS,$OUTBOX_CREATED_AT," +
                    "$OUTBOX_UPDATED_AT,$OUTBOX_NEXT_ATTEMPT_AT,NULL,'NETWORK_UNAVAILABLE')",
            )
        }
        execSQL(
            "UPDATE `invoice_drafts` SET `confirmedPurchaseId`='$PURCHASE_ID' " +
                "WHERE `draftId`='$DRAFT_ID'",
        )
    }

    private fun assertCommonGraph(db: SupportSQLiteDatabase, sourceVersion: Int) {
        db.query(
            "SELECT `supplierId`,`businessId`,`ruc`,`status`,`version` FROM `suppliers` " +
                "WHERE `supplierId`='$SUPPLIER_ID'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(SUPPLIER_ID, cursor.getString(0))
            assertEquals(BUSINESS_ID, cursor.getString(1))
            assertEquals(SUPPLIER_RUC, cursor.getString(2))
            assertEquals(ACTIVE_STATUS, cursor.getString(3))
            assertEquals(1L, cursor.getLong(4))
            assertFalse(cursor.moveToNext())
        }
        db.query(
            "SELECT `productId`,`businessId`,`unitId`,`locationId`,`sku`,`barcode`," +
                "`purchaseUnitId`,`purchaseFactor`,`status`,`version`," +
                "`salePriceMinorUnits`,`salePriceCurrencyCode` FROM `products` " +
                "WHERE `productId`='$PRODUCT_ID'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(PRODUCT_ID, cursor.getString(0))
            assertEquals(BUSINESS_ID, cursor.getString(1))
            assertEquals(UNIT_ID, cursor.getString(2))
            assertEquals(LOCATION_ID, cursor.getString(3))
            assertEquals(PRODUCT_SKU, cursor.getString(4))
            assertEquals(PRODUCT_BARCODE, cursor.getString(5))
            if (sourceVersion >= PURCHASE_UNIT_SCHEMA_VERSION) {
                assertEquals(PURCHASE_UNIT_ID, cursor.getString(6))
                assertEquals(PURCHASE_FACTOR, cursor.getString(7))
            } else {
                assertTrue(cursor.isNull(6))
                assertTrue(cursor.isNull(7))
            }
            assertEquals(ACTIVE_STATUS, cursor.getString(8))
            assertEquals(1L, cursor.getLong(9))
            assertTrue(cursor.isNull(10))
            assertTrue(cursor.isNull(11))
            assertFalse(cursor.moveToNext())
        }
        db.query(
            "SELECT `aliasId`,`businessId`,`supplierId`,`productId`,`alias`,`aliasNormalized` " +
                "FROM `supplier_product_aliases` WHERE `aliasId`='$ALIAS_ID'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(ALIAS_ID, cursor.getString(0))
            assertEquals(BUSINESS_ID, cursor.getString(1))
            assertEquals(SUPPLIER_ID, cursor.getString(2))
            assertEquals(PRODUCT_ID, cursor.getString(3))
            assertEquals("Caja Histórica", cursor.getString(4))
            assertEquals("caja historica", cursor.getString(5))
            assertFalse(cursor.moveToNext())
        }

        val expectedDraftStatus = when {
            sourceVersion < PREPARED_PURCHASE_SCHEMA_VERSION -> "CAPTURED"
            sourceVersion < LEDGER_SCHEMA_VERSION -> "NEEDS_REVIEW"
            else -> "COMMITTED"
        }
        val expectedOtherCharges =
            if (sourceVersion >= HEADER_REVIEW_SCHEMA_VERSION) OTHER_CHARGES_MINOR_UNITS else null
        val expectedTotal =
            if (sourceVersion >= HEADER_REVIEW_SCHEMA_VERSION) TOTAL_MINOR_UNITS
            else LEGACY_TOTAL_MINOR_UNITS
        db.query(
            "SELECT `draftId`,`businessId`,`status`,`confirmedPurchaseId`," +
                "`subtotalMinorUnits`,`taxMinorUnits`,`otherChargesMinorUnits`," +
                "`totalMinorUnits`,`updatedAt` FROM `invoice_drafts` WHERE `draftId`='$DRAFT_ID'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(DRAFT_ID, cursor.getString(0))
            assertEquals(BUSINESS_ID, cursor.getString(1))
            assertEquals(expectedDraftStatus, cursor.getString(2))
            if (sourceVersion >= LEDGER_SCHEMA_VERSION) {
                assertEquals(PURCHASE_ID, cursor.getString(3))
            } else {
                assertTrue(cursor.isNull(3))
            }
            assertEquals(SUBTOTAL_MINOR_UNITS, cursor.getLong(4))
            assertEquals(TAX_MINOR_UNITS, cursor.getLong(5))
            if (expectedOtherCharges == null) {
                assertTrue(cursor.isNull(6))
            } else {
                assertEquals(expectedOtherCharges, cursor.getLong(6))
            }
            assertEquals(expectedTotal, cursor.getLong(7))
            val expectedUpdatedAt =
                if (sourceVersion >= LEDGER_SCHEMA_VERSION) POSTED_AT else DRAFT_UPDATED_AT
            assertEquals(expectedUpdatedAt, cursor.getLong(8))
            assertFalse(cursor.moveToNext())
        }
        db.query(
            "SELECT `lineId`,`draftId`,`businessId`,`quantity`,`unitCost`," +
                "`lineTotalMinorUnits`,`codeNormalized`,`unitCodeNormalized`," +
                "`discountMinorUnits`,`taxMinorUnits` FROM `invoice_lines` " +
                "WHERE `lineId`='$INVOICE_LINE_ID'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(INVOICE_LINE_ID, cursor.getString(0))
            assertEquals(DRAFT_ID, cursor.getString(1))
            assertEquals(BUSINESS_ID, cursor.getString(2))
            assertEquals(PURCHASE_QUANTITY, cursor.getString(3))
            assertEquals(READ_UNIT_COST, cursor.getString(4))
            assertEquals(expectedTotal, cursor.getLong(5))
            if (sourceVersion >= TYPED_LINE_SCHEMA_VERSION) {
                assertEquals(PRODUCT_SKU, cursor.getString(6))
                assertEquals("CJA", cursor.getString(7))
                assertEquals(0L, cursor.getLong(8))
                assertEquals(TAX_MINOR_UNITS, cursor.getLong(9))
            } else {
                for (column in 6..9) assertTrue(cursor.isNull(column))
            }
            assertFalse(cursor.moveToNext())
        }

        if (sourceVersion >= LEDGER_SCHEMA_VERSION) {
            assertSingleLong(db, "SELECT COUNT(*) FROM `prepared_purchases`", 1L)
        } else {
            // v10 snapshots are deliberately invalidated by MIGRATION_10_11.
            assertSingleLong(db, "SELECT COUNT(*) FROM `prepared_purchases`", 0L)
        }
    }

    private fun assertRichLedgerGraph(db: SupportSQLiteDatabase, sourceVersion: Int) {
        db.query(
            "SELECT `purchaseId`,`businessId`,`sourceDraftId`,`supplierId`," +
                "`subtotalMinorUnits`,`taxMinorUnits`,`otherChargesMinorUnits`,`totalMinorUnits`," +
                "`status`,`postedAt`,`documentIdentitySlot`,`duplicateOverrideOfPurchaseId` " +
                "FROM `purchases` WHERE `purchaseId`='$PURCHASE_ID'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(PURCHASE_ID, cursor.getString(0))
            assertEquals(BUSINESS_ID, cursor.getString(1))
            assertEquals(DRAFT_ID, cursor.getString(2))
            assertEquals(SUPPLIER_ID, cursor.getString(3))
            assertEquals(SUBTOTAL_MINOR_UNITS, cursor.getLong(4))
            assertEquals(TAX_MINOR_UNITS, cursor.getLong(5))
            assertEquals(OTHER_CHARGES_MINOR_UNITS, cursor.getLong(6))
            assertEquals(TOTAL_MINOR_UNITS, cursor.getLong(7))
            assertEquals("POSTED", cursor.getString(8))
            assertEquals(POSTED_AT, cursor.getLong(9))
            assertEquals("PRIMARY", cursor.getString(10))
            assertTrue(cursor.isNull(11))
            assertFalse(cursor.moveToNext())
        }
        db.query(
            "SELECT `purchaseLineId`,`purchaseId`,`productId`,`unitId`,`quantity`,`unitCost`," +
                "`taxMinorUnits`,`totalMinorUnits`,`appliedUnitCost`,`purchaseUnitFactor`," +
                "`inventoryQuantity`,`discount`,`taxTreatment`,`costPolicy`,`appliedCostTotal`," +
                "`taxEvidenceType`,`taxEvidenceValue`,`roundingScale`,`roundingMode`," +
                "`costingWarnings`,`productNameSnapshot`,`unitCodeSnapshot`,`productProvenance` " +
                "FROM `purchase_lines` " +
                "WHERE `purchaseLineId`='$PURCHASE_LINE_ID'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(PURCHASE_LINE_ID, cursor.getString(0))
            assertEquals(PURCHASE_ID, cursor.getString(1))
            assertEquals(PRODUCT_ID, cursor.getString(2))
            assertEquals(PURCHASE_UNIT_ID, cursor.getString(3))
            assertEquals(PURCHASE_QUANTITY, cursor.getString(4))
            assertEquals(READ_UNIT_COST, cursor.getString(5))
            assertEquals(TAX_MINOR_UNITS, cursor.getLong(6))
            assertEquals(TOTAL_MINOR_UNITS, cursor.getLong(7))
            if (sourceVersion >= COSTING_AUDIT_SCHEMA_VERSION) {
                assertEquals(APPLIED_UNIT_COST, cursor.getString(8))
                assertEquals(PURCHASE_FACTOR, cursor.getString(9))
                assertEquals(INVENTORY_QUANTITY, cursor.getString(10))
                assertEquals(DISCOUNT, cursor.getString(11))
                assertEquals("EXCLUDED", cursor.getString(12))
                assertEquals("NET", cursor.getString(13))
                assertEquals(APPLIED_COST_TOTAL, cursor.getString(14))
                assertEquals("EXPLICIT_AMOUNT", cursor.getString(15))
                assertEquals(TAX_EVIDENCE_VALUE, cursor.getString(16))
                assertEquals(ROUNDING_SCALE, cursor.getInt(17))
                assertEquals("HALF_EVEN", cursor.getString(18))
                assertEquals("[]", cursor.getString(19))
            } else {
                for (column in 8..19) assertTrue(cursor.isNull(column))
            }
            assertEquals("Producto histórico", cursor.getString(20))
            assertEquals("CJA", cursor.getString(21))
            assertEquals("UNKNOWN_LEGACY", cursor.getString(22))
            assertFalse(cursor.moveToNext())
        }
        db.query(
            "SELECT `businessId`,`productId`,`locationId`,`quantityOnHand`," +
                "`averageUnitCost`,`currencyCode`,`version`,`updatedAt` " +
                "FROM `inventory_balances` WHERE `businessId`='$BUSINESS_ID' " +
                "AND `productId`='$PRODUCT_ID' AND `locationId`='$LOCATION_ID'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(BUSINESS_ID, cursor.getString(0))
            assertEquals(PRODUCT_ID, cursor.getString(1))
            assertEquals(LOCATION_ID, cursor.getString(2))
            assertEquals(INVENTORY_QUANTITY, cursor.getString(3))
            assertEquals(APPLIED_UNIT_COST, cursor.getString(4))
            assertEquals("PEN", cursor.getString(5))
            assertEquals(BALANCE_VERSION, cursor.getLong(6))
            assertEquals(POSTED_AT, cursor.getLong(7))
            assertFalse(cursor.moveToNext())
        }
        db.query(
            "SELECT `movementId`,`purchaseId`,`purchaseLineId`,`productId`,`locationId`," +
                "`type`,`quantityDelta`,`unitCost`,`currencyCode`,`idempotencyKey` " +
                "FROM `stock_movements` WHERE `movementId`='$MOVEMENT_ID'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(MOVEMENT_ID, cursor.getString(0))
            assertEquals(PURCHASE_ID, cursor.getString(1))
            assertEquals(PURCHASE_LINE_ID, cursor.getString(2))
            assertEquals(PRODUCT_ID, cursor.getString(3))
            assertEquals(LOCATION_ID, cursor.getString(4))
            assertEquals("PURCHASE", cursor.getString(5))
            assertEquals(INVENTORY_QUANTITY, cursor.getString(6))
            assertEquals(APPLIED_UNIT_COST, cursor.getString(7))
            assertEquals("PEN", cursor.getString(8))
            assertEquals(MOVEMENT_IDEMPOTENCY_KEY, cursor.getString(9))
            assertFalse(cursor.moveToNext())
        }
        assertBalanceMatchesMovementLedger(db)
        db.query(
            "SELECT `auditEventId`,`businessId`,`purchaseId`,`eventType`,`entityType`,`entityId` " +
                "FROM `audit_events` WHERE `auditEventId`='$AUDIT_EVENT_ID'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(AUDIT_EVENT_ID, cursor.getString(0))
            assertEquals(BUSINESS_ID, cursor.getString(1))
            assertEquals(PURCHASE_ID, cursor.getString(2))
            assertEquals("PURCHASE_POSTED", cursor.getString(3))
            assertEquals("PURCHASE", cursor.getString(4))
            assertEquals(PURCHASE_ID, cursor.getString(5))
            assertFalse(cursor.moveToNext())
        }
        db.query(
            "SELECT `operationId`,`businessId`,`purchaseId`,`status`,`attemptCount`," +
                "`nextAttemptAt`,`completedAt`,`lastError`,`payloadVersion`,`claimToken`," +
                "`claimLeaseUntil`,`conflictRemotePurchaseId`,`conflictReceiptId`," +
                "`targetCloudBusinessId` " +
                "FROM `outbox_operations` WHERE `operationId`='$OUTBOX_ID'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(OUTBOX_ID, cursor.getString(0))
            assertEquals(BUSINESS_ID, cursor.getString(1))
            assertEquals(PURCHASE_ID, cursor.getString(2))
            assertEquals("PENDING", cursor.getString(3))
            assertEquals(OUTBOX_ATTEMPTS, cursor.getInt(4))
            assertEquals(OUTBOX_NEXT_ATTEMPT_AT, cursor.getLong(5))
            assertTrue(cursor.isNull(6))
            assertEquals("NETWORK_UNAVAILABLE", cursor.getString(7))
            val expectedPayloadVersion = if (sourceVersion >= CLAIM_PROTOCOL_SCHEMA_VERSION) 2 else 1
            assertEquals(expectedPayloadVersion, cursor.getInt(8))
            for (column in 9..13) assertTrue(cursor.isNull(column))
            assertFalse(cursor.moveToNext())
        }
    }

    private fun assertBalanceMatchesMovementLedger(db: SupportSQLiteDatabase) {
        val persistedBalance = db.query(
            "SELECT `quantityOnHand` FROM `inventory_balances` " +
                "WHERE `businessId`='$BUSINESS_ID' AND `productId`='$PRODUCT_ID' " +
                "AND `locationId`='$LOCATION_ID'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            val quantityOnHand = cursor.getString(0)
            assertFalse(cursor.moveToNext())
            BigDecimal(quantityOnHand)
        }
        var movementCount = 0
        var movementTotal = BigDecimal.ZERO
        db.query(
            "SELECT `quantityDelta` FROM `stock_movements` " +
                "WHERE `businessId`='$BUSINESS_ID' AND `productId`='$PRODUCT_ID' " +
                "AND `locationId`='$LOCATION_ID' ORDER BY `occurredAt`,`movementId`",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                movementTotal = movementTotal.add(BigDecimal(cursor.getString(0)))
                movementCount += 1
            }
        }

        assertTrue("El fixture rico debe conservar al menos un movimiento", movementCount > 0)
        assertEquals(0, persistedBalance.compareTo(movementTotal))
        assertEquals(persistedBalance.scale(), movementTotal.scale())
        assertEquals(BigDecimal(INVENTORY_QUANTITY).scale(), persistedBalance.scale())
    }

    private fun assertLedgerTablesAreEmpty(db: SupportSQLiteDatabase) {
        listOf(
            "purchases",
            "purchase_lines",
            "inventory_balances",
            "stock_movements",
            "audit_events",
            "outbox_operations",
        ).forEach { table ->
            assertSingleLong(db, "SELECT COUNT(*) FROM `$table`", 0L)
        }
    }

    private fun assertCloudTenantBindingSchema(db: SupportSQLiteDatabase) {
        db.query("PRAGMA table_info(`outbox_operations`)").use { cursor ->
            var foundTarget = false
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "targetCloudBusinessId") {
                    foundTarget = true
                    assertFalse(cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) == 1)
                }
            }
            assertTrue("v20 debe fijar una columna de tenant nullable para historia legacy", foundTarget)
        }
        assertSingleLong(db, "SELECT COUNT(*) FROM `cloud_business_bindings`", 0L)
        assertSingleLong(
            db,
            "SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name IN (" +
                "'cloud_business_bindings_block_update'," +
                "'cloud_business_bindings_block_direct_delete'," +
                "'outbox_operations_validate_cloud_target_insert'," +
                "'outbox_operations_fill_cloud_target_insert'," +
                "'outbox_operations_validate_cloud_target_update')",
            5L,
        )
    }

    private fun assertNoForeignKeyViolations(db: SupportSQLiteDatabase) {
        db.query("PRAGMA foreign_key_check").use { cursor ->
            assertEquals(0, cursor.count)
        }
    }

    private fun assertIntegrityCheckPasses(db: SupportSQLiteDatabase) {
        db.query("PRAGMA integrity_check").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("ok", cursor.getString(0))
            assertFalse(cursor.moveToNext())
        }
    }

    private fun assertSingleLong(db: SupportSQLiteDatabase, sql: String, expected: Long) {
        db.query(sql).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(expected, cursor.getLong(0))
            assertFalse(cursor.moveToNext())
        }
    }

    private companion object {
        const val CURRENT_SCHEMA_VERSION = 27
        const val HEADER_REVIEW_SCHEMA_VERSION = 7
        const val TYPED_LINE_SCHEMA_VERSION = 8
        const val PURCHASE_UNIT_SCHEMA_VERSION = 9
        const val PREPARED_PURCHASE_SCHEMA_VERSION = 10
        const val LEDGER_SCHEMA_VERSION = 11
        const val CATALOG_CANONICALIZATION_SOURCE = 11
        const val COSTING_AUDIT_SCHEMA_VERSION = 13
        const val DUPLICATE_OVERRIDE_SCHEMA_VERSION = 14
        const val CLAIM_PROTOCOL_SCHEMA_VERSION = 15
        const val CATALOG_VERSION_SCHEMA_VERSION = 16
        const val PURCHASE_SNAPSHOT_SCHEMA_VERSION = 17

        const val ACTIVE_STATUS = "ACTIVE"
        const val CREATED_AT = 1_000L
        const val DRAFT_UPDATED_AT = 3_000L
        const val PREPARED_AT = 3_500L
        const val PURCHASE_CREATED_AT = 4_000L
        const val POSTED_AT = 8_000L
        const val OUTBOX_CREATED_AT = 8_200L
        const val OUTBOX_UPDATED_AT = 8_400L
        const val OUTBOX_NEXT_ATTEMPT_AT = 9_000L
        const val OUTBOX_ATTEMPTS = 2
        const val BALANCE_VERSION = 7L

        const val SUBTOTAL_MINOR_UNITS = 10_005L
        const val TAX_MINOR_UNITS = 1_801L
        const val OTHER_CHARGES_MINOR_UNITS = 99L
        const val LEGACY_TOTAL_MINOR_UNITS = 11_806L
        const val TOTAL_MINOR_UNITS = 11_905L
        const val PURCHASE_QUANTITY = "12.5000"
        const val READ_UNIT_COST = "8.0040"
        const val PURCHASE_FACTOR = "12.0000"
        const val INVENTORY_QUANTITY = "150.0000"
        const val APPLIED_UNIT_COST = "0.667000"
        const val APPLIED_COST_TOTAL = "100.050000"
        const val DISCOUNT = "0.0000"
        const val TAX_EVIDENCE_VALUE = "18.0100"
        const val ROUNDING_SCALE = 6

        const val BUSINESS_RUC = "20123456789"
        const val SUPPLIER_RUC = "20987654321"
        const val PRODUCT_SKU = "SKU-RICH-01"
        const val PRODUCT_BARCODE = "001234500067"

        const val BUSINESS_ID = "10000000-0000-0000-0000-000000000001"
        const val SUPPLIER_ID = "10000000-0000-0000-0000-000000000002"
        const val UNIT_ID = "10000000-0000-0000-0000-000000000003"
        const val PURCHASE_UNIT_ID = "10000000-0000-0000-0000-000000000004"
        const val LOCATION_ID = "10000000-0000-0000-0000-000000000005"
        const val PRODUCT_ID = "10000000-0000-0000-0000-000000000006"
        const val ALIAS_ID = "10000000-0000-0000-0000-000000000007"
        const val DRAFT_ID = "10000000-0000-0000-0000-000000000008"
        const val INVOICE_LINE_ID = "10000000-0000-0000-0000-000000000009"
        const val PURCHASE_ID = "20000000-0000-0000-0000-000000000001"
        const val PURCHASE_LINE_ID = "20000000-0000-0000-0000-000000000002"
        const val MOVEMENT_ID = "20000000-0000-0000-0000-000000000003"
        const val AUDIT_EVENT_ID = "20000000-0000-0000-0000-000000000004"
        const val OUTBOX_ID = "20000000-0000-0000-0000-000000000005"

        const val PURCHASE_IDEMPOTENCY_KEY = "purchase:v1:rich-history"
        const val MOVEMENT_IDEMPOTENCY_KEY = "movement:v1:rich-history"
        const val OUTBOX_IDEMPOTENCY_KEY = "sync-purchase:v1:rich-history"

        val ALL_MIGRATIONS: Array<Migration> = arrayOf(
            FacturaStockDatabase.MIGRATION_1_2,
            FacturaStockDatabase.MIGRATION_2_3,
            FacturaStockDatabase.MIGRATION_3_4,
            FacturaStockDatabase.MIGRATION_4_5,
            FacturaStockDatabase.MIGRATION_5_6,
            FacturaStockDatabase.MIGRATION_6_7,
            FacturaStockDatabase.MIGRATION_7_8,
            FacturaStockDatabase.MIGRATION_8_9,
            FacturaStockDatabase.MIGRATION_9_10,
            FacturaStockDatabase.MIGRATION_10_11,
            FacturaStockDatabase.MIGRATION_11_12,
            FacturaStockDatabase.MIGRATION_12_13,
            FacturaStockDatabase.MIGRATION_13_14,
            FacturaStockDatabase.MIGRATION_14_15,
            FacturaStockDatabase.MIGRATION_15_16,
            FacturaStockDatabase.MIGRATION_16_17,
            FacturaStockDatabase.MIGRATION_17_18,
            FacturaStockDatabase.MIGRATION_18_19,
            FacturaStockDatabase.MIGRATION_19_20,
            FacturaStockDatabase.MIGRATION_20_21,
            FacturaStockDatabase.MIGRATION_21_22,
            FacturaStockDatabase.MIGRATION_22_23,
            FacturaStockDatabase.MIGRATION_23_24,
            FacturaStockDatabase.MIGRATION_24_25,
            FacturaStockDatabase.MIGRATION_25_26,
            FacturaStockDatabase.MIGRATION_26_27,
        )
    }
}
