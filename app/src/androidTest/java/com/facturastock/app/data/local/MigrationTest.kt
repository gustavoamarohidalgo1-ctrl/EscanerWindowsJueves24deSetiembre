package com.facturastock.app.data.local

import android.database.sqlite.SQLiteException
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Pruebas de migración con validación estricta contra los esquemas Room exportados. */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        FacturaStockDatabase::class.java,
    )

    @Test
    fun migrate1To2PreservesRowsAndAllowsNullRuc() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                "INSERT INTO `businesses` " +
                    "(`businessId`, `legalName`, `ruc`, `createdAt`, `updatedAt`, `tradeName`, `status`) " +
                    "VALUES ('11111111-1111-1111-1111-111111111111', 'Negocio V1 SAC', " +
                    "'20123456789', 1000, 1500, NULL, 'ACTIVE')",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            2,
            true,
            FacturaStockDatabase.MIGRATION_1_2,
        )
        db.execSQL("PRAGMA foreign_keys=ON")

        // La fila de v1 sobrevive intacta a la reconstrucción de la tabla.
        db.query(
            "SELECT `legalName`, `ruc`, `createdAt`, `updatedAt`, `status` FROM `businesses` " +
                "WHERE `businessId` = '11111111-1111-1111-1111-111111111111'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Negocio V1 SAC", cursor.getString(0))
            assertEquals("20123456789", cursor.getString(1))
            assertEquals(1_000L, cursor.getLong(2))
            assertEquals(1_500L, cursor.getLong(3))
            assertEquals("ACTIVE", cursor.getString(4))
        }

        // En v2 el RUC es opcional y varios NULL no colisionan en el índice único.
        db.execSQL(
            "INSERT INTO `businesses` " +
                "(`businessId`, `legalName`, `ruc`, `createdAt`, `updatedAt`, `tradeName`, `status`) " +
                "VALUES ('22222222-2222-2222-2222-222222222222', 'Sin RUC Uno', NULL, 1000, 1000, NULL, 'ACTIVE')",
        )
        db.execSQL(
            "INSERT INTO `businesses` " +
                "(`businessId`, `legalName`, `ruc`, `createdAt`, `updatedAt`, `tradeName`, `status`) " +
                "VALUES ('33333333-3333-3333-3333-333333333333', 'Sin RUC Dos', NULL, 1000, 1000, NULL, 'ACTIVE')",
        )
        db.query("SELECT COUNT(*) FROM `businesses` WHERE `ruc` IS NULL").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(0))
        }
        // El índice único sigue rechazando RUC duplicados no nulos.
        db.query("SELECT COUNT(*) FROM `businesses` WHERE `ruc` = '20123456789'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }

        helper.closeWhenFinished(db)
    }

    @Test
    fun migrate2To3ConvertsPixelCropsToNormalizedFractions() {
        helper.createDatabase(TEST_DB, 2).apply {
            execSQL(
                "INSERT INTO `businesses` " +
                    "(`businessId`, `legalName`, `ruc`, `createdAt`, `updatedAt`, `tradeName`, `status`) " +
                    "VALUES ('11111111-1111-1111-1111-111111111111', 'Negocio Migración SAC', " +
                    "NULL, 1000, 1000, NULL, 'ACTIVE')",
            )
            execSQL(
                "INSERT INTO `invoice_drafts` " +
                    "(`draftId`, `businessId`, `createdAt`, `updatedAt`, `status`) " +
                    "VALUES ('22222222-2222-2222-2222-222222222222', " +
                    "'11111111-1111-1111-1111-111111111111', 1000, 1000, 'CAPTURED')",
            )
            // Página sin rotar (3000×4000) con recorte en píxeles: (300, 1000, 2700, 3600).
            execSQL(
                "INSERT INTO `invoice_images` (" +
                    "`imageId`, `draftId`, `businessId`, `pageIndex`, `filePath`, `sha256`, " +
                    "`mimeType`, `widthPx`, `heightPx`, `fileSizeBytes`, `createdAt`, " +
                    "`rotationDegrees`, `cropLeftPx`, `cropTopPx`, `cropRightPx`, `cropBottomPx`) " +
                    "VALUES ('33333333-3333-3333-3333-333333333333', " +
                    "'22222222-2222-2222-2222-222222222222', '11111111-1111-1111-1111-111111111111', " +
                    "0, 'captures/x/p0.jpg', '" + "a".repeat(64) + "', 'image/jpeg', 3000, 4000, " +
                    "2000000, 1000, 0, 300, 1000, 2700, 3600)",
            )
            // Página rotada 90° (3000×4000 → 4000×3000 visible) con recorte en píxeles.
            execSQL(
                "INSERT INTO `invoice_images` (" +
                    "`imageId`, `draftId`, `businessId`, `pageIndex`, `filePath`, `sha256`, " +
                    "`mimeType`, `widthPx`, `heightPx`, `fileSizeBytes`, `createdAt`, " +
                    "`rotationDegrees`, `cropLeftPx`, `cropTopPx`, `cropRightPx`, `cropBottomPx`) " +
                    "VALUES ('44444444-4444-4444-4444-444444444444', " +
                    "'22222222-2222-2222-2222-222222222222', '11111111-1111-1111-1111-111111111111', " +
                    "1, 'captures/x/p1.jpg', '" + "b".repeat(64) + "', 'image/jpeg', 3000, 4000, " +
                    "2000000, 1000, 90, 400, 300, 3600, 2700)",
            )
            // Página sin recorte: los NULL se conservan.
            execSQL(
                "INSERT INTO `invoice_images` (" +
                    "`imageId`, `draftId`, `businessId`, `pageIndex`, `filePath`, `sha256`, " +
                    "`mimeType`, `widthPx`, `heightPx`, `fileSizeBytes`, `createdAt`, " +
                    "`rotationDegrees`, `cropLeftPx`, `cropTopPx`, `cropRightPx`, `cropBottomPx`) " +
                    "VALUES ('55555555-5555-5555-5555-555555555555', " +
                    "'22222222-2222-2222-2222-222222222222', '11111111-1111-1111-1111-111111111111', " +
                    "2, 'captures/x/p2.jpg', '" + "c".repeat(64) + "', 'image/jpeg', 3000, 4000, " +
                    "2000000, 1000, 0, NULL, NULL, NULL, NULL)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            3,
            true,
            FacturaStockDatabase.MIGRATION_1_2,
            FacturaStockDatabase.MIGRATION_2_3,
        )
        db.execSQL("PRAGMA foreign_keys=ON")

        // Sin rotación: fracciones sobre 3000×4000 — (300/3000, 1000/4000, 2700/3000, 3600/4000).
        db.query(
            "SELECT `cropLeftFraction`, `cropTopFraction`, `cropRightFraction`, `cropBottomFraction` " +
                "FROM `invoice_images` WHERE `imageId` = '33333333-3333-3333-3333-333333333333'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1_000, cursor.getInt(0))
            assertEquals(2_500, cursor.getInt(1))
            assertEquals(9_000, cursor.getInt(2))
            assertEquals(9_000, cursor.getInt(3))
        }

        // Con 90° las dimensiones se intercambian: 400/4000, 300/3000, 3600/4000, 2700/3000.
        db.query(
            "SELECT `cropLeftFraction`, `cropTopFraction`, `cropRightFraction`, `cropBottomFraction` " +
                "FROM `invoice_images` WHERE `imageId` = '44444444-4444-4444-4444-444444444444'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1_000, cursor.getInt(0))
            assertEquals(1_000, cursor.getInt(1))
            assertEquals(9_000, cursor.getInt(2))
            assertEquals(9_000, cursor.getInt(3))
        }

        // La página sin recorte conserva los NULL y el resto de columnas intactas.
        db.query(
            "SELECT `cropLeftFraction`, `filePath`, `rotationDegrees`, `widthPx` " +
                "FROM `invoice_images` WHERE `imageId` = '55555555-5555-5555-5555-555555555555'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertEquals("captures/x/p2.jpg", cursor.getString(1))
            assertEquals(0, cursor.getInt(2))
            assertEquals(3_000, cursor.getInt(3))
        }

        // El índice único (draftId, pageIndex) quedó reconstruido: las tres páginas conviven.
        db.query("SELECT COUNT(*) FROM `invoice_images`").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(3, cursor.getInt(0))
        }

        helper.closeWhenFinished(db)
    }

    @Test
    fun migrate3To4AddsNullableOcrRunTokenWithoutChangingDrafts() {
        helper.createDatabase(TEST_DB, 3).apply {
            execSQL(
                "INSERT INTO `businesses` " +
                    "(`businessId`, `legalName`, `ruc`, `createdAt`, `updatedAt`, `tradeName`, `status`) " +
                    "VALUES ('11111111-1111-1111-1111-111111111111', 'Negocio OCR SAC', " +
                    "NULL, 1000, 1000, NULL, 'ACTIVE')",
            )
            execSQL(
                "INSERT INTO `invoice_drafts` " +
                    "(`draftId`, `businessId`, `createdAt`, `updatedAt`, `status`) " +
                    "VALUES ('22222222-2222-2222-2222-222222222222', " +
                    "'11111111-1111-1111-1111-111111111111', 1000, 1000, 'CAPTURED')",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            4,
            true,
            FacturaStockDatabase.MIGRATION_3_4,
        )
        db.execSQL("PRAGMA foreign_keys=ON")

        db.query(
            "SELECT `status`, `activeOcrRunId` FROM `invoice_drafts` " +
                "WHERE `draftId` = '22222222-2222-2222-2222-222222222222'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("CAPTURED", cursor.getString(0))
            assertTrue(cursor.isNull(1))
        }
        db.execSQL(
            "UPDATE `invoice_drafts` SET `activeOcrRunId` = " +
                "'33333333-3333-3333-3333-333333333333'",
        )
        db.query("SELECT `activeOcrRunId` FROM `invoice_drafts`").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("33333333-3333-3333-3333-333333333333", cursor.getString(0))
        }

        helper.closeWhenFinished(db)
    }

    @Test
    fun migrate4To5CreatesSnapshotTablesAndDowngradesUnrecoverableOcrReadyDrafts() {
        helper.createDatabase(TEST_DB, 4).apply {
            execSQL(
                "INSERT INTO `businesses` " +
                    "(`businessId`, `legalName`, `ruc`, `createdAt`, `updatedAt`, `tradeName`, `status`) " +
                    "VALUES ('11111111-1111-1111-1111-111111111111', 'Negocio Snapshot SAC', " +
                    "NULL, 1000, 1000, NULL, 'ACTIVE')",
            )
            execSQL(
                "INSERT INTO `invoice_drafts` " +
                    "(`draftId`, `businessId`, `createdAt`, `updatedAt`, `status`, " +
                    "`activeOcrRunId`, `lastError`) " +
                    "VALUES ('22222222-2222-2222-2222-222222222222', " +
                    "'11111111-1111-1111-1111-111111111111', 1000, 2000, 'OCR_READY', " +
                    "'33333333-3333-3333-3333-333333333333', 'resultado v4 sin documento')",
            )
            execSQL(
                "INSERT INTO `invoice_drafts` " +
                    "(`draftId`, `businessId`, `createdAt`, `updatedAt`, `status`, `activeOcrRunId`) " +
                    "VALUES ('44444444-4444-4444-4444-444444444444', " +
                    "'11111111-1111-1111-1111-111111111111', 1000, 2000, 'OCR_PROCESSING', " +
                    "'55555555-5555-5555-5555-555555555555')",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            5,
            true,
            FacturaStockDatabase.MIGRATION_4_5,
        )
        db.execSQL("PRAGMA foreign_keys=ON")

        db.query(
            "SELECT `status`, `activeOcrRunId`, `lastError`, `updatedAt` " +
                "FROM `invoice_drafts` " +
                "WHERE `draftId` = '22222222-2222-2222-2222-222222222222'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("CAPTURED", cursor.getString(0))
            assertTrue(cursor.isNull(1))
            assertTrue(cursor.isNull(2))
            assertEquals(2_000L, cursor.getLong(3))
        }
        // Otros estados no se reinterpretan durante la migración.
        db.query(
            "SELECT `status`, `activeOcrRunId` FROM `invoice_drafts` " +
                "WHERE `draftId` = '44444444-4444-4444-4444-444444444444'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("OCR_PROCESSING", cursor.getString(0))
            assertEquals("55555555-5555-5555-5555-555555555555", cursor.getString(1))
        }
        db.query(
            "SELECT COUNT(*) FROM `sqlite_master` WHERE `type` = 'table' " +
                "AND `name` IN ('invoice_ocr_snapshots', 'invoice_ocr_snapshot_pages')",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM `invoice_ocr_snapshots`").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM `invoice_ocr_snapshot_pages`").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }

        helper.closeWhenFinished(db)
    }

    @Test
    fun migrate5To6CreatesParsedAuditTableLinkedToTheExactSnapshot() {
        helper.createDatabase(TEST_DB, 5).apply {
            execSQL(
                "INSERT INTO `businesses` " +
                    "(`businessId`, `legalName`, `ruc`, `createdAt`, `updatedAt`, `tradeName`, `status`) " +
                    "VALUES ('11111111-1111-1111-1111-111111111111', 'Negocio Parser SAC', " +
                    "NULL, 1000, 1000, NULL, 'ACTIVE')",
            )
            execSQL(
                "INSERT INTO `invoice_drafts` " +
                    "(`draftId`, `businessId`, `createdAt`, `updatedAt`, `status`) " +
                    "VALUES ('22222222-2222-2222-2222-222222222222', " +
                    "'11111111-1111-1111-1111-111111111111', 1000, 2000, 'OCR_READY')",
            )
            execSQL(
                "INSERT INTO `invoice_ocr_snapshots` " +
                    "(`draftId`, `runId`, `completedAt`, `codecVersion`, `pageCount`) " +
                    "VALUES ('22222222-2222-2222-2222-222222222222', " +
                    "'33333333-3333-3333-3333-333333333333', 2000, 1, 1)",
            )
            execSQL(
                "INSERT INTO `invoice_ocr_snapshot_pages` " +
                    "(`draftId`, `pageIndex`, `sourceImageId`, `widthPx`, `heightPx`, " +
                    "`payloadSha256`, `payload`) VALUES (" +
                    "'22222222-2222-2222-2222-222222222222', 0, " +
                    "'44444444-4444-4444-4444-444444444444', 100, 200, " +
                    "'${"a".repeat(64)}', X'01')",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            6,
            true,
            FacturaStockDatabase.MIGRATION_5_6,
        )
        db.execSQL("PRAGMA foreign_keys=ON")

        db.execSQL(
            "INSERT INTO `invoice_parsed_results` (" +
                "`draftId`, `runId`, `parserVersion`, `contextFingerprint`, " +
                "`payloadCodecVersion`, `payloadSha256`, `payload`, `parsedAt`) VALUES (" +
                "'22222222-2222-2222-2222-222222222222', " +
                "'33333333-3333-3333-3333-333333333333', 1, '${"b".repeat(64)}', " +
                "1, '${"c".repeat(64)}', X'0102', 3000)",
        )
        db.query(
            "SELECT `runId`, `parserVersion`, `parsedAt` FROM `invoice_parsed_results` " +
                "WHERE `draftId` = '22222222-2222-2222-2222-222222222222'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("33333333-3333-3333-3333-333333333333", cursor.getString(0))
            assertEquals(1, cursor.getInt(1))
            assertEquals(3_000L, cursor.getLong(2))
        }

        // La FK compuesta impide que el audit sobreviva sin el snapshot exacto.
        db.execSQL(
            "DELETE FROM `invoice_ocr_snapshots` " +
                "WHERE `draftId` = '22222222-2222-2222-2222-222222222222'",
        )
        db.query("SELECT COUNT(*) FROM `invoice_parsed_results`").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }

        helper.closeWhenFinished(db)
    }

    @Test
    fun migrate6To7AddsHeaderProjectionAndDurableEditWithoutChangingDraft() {
        helper.createDatabase(TEST_DB, 6).apply {
            execSQL(
                "INSERT INTO `businesses` " +
                    "(`businessId`, `legalName`, `ruc`, `createdAt`, `updatedAt`, `tradeName`, `status`) " +
                    "VALUES ('11111111-1111-1111-1111-111111111111', 'Negocio Revisión SAC', " +
                    "NULL, 1000, 1000, NULL, 'ACTIVE')",
            )
            execSQL(
                "INSERT INTO `invoice_drafts` (" +
                    "`draftId`, `businessId`, `createdAt`, `updatedAt`, `status`, " +
                    "`supplierRucRaw`, `supplierRucNormalized`, `documentNumberRaw`, " +
                    "`documentNumberNormalized`, `currencyCode`, `subtotalMinorUnits`, " +
                    "`taxMinorUnits`, `totalMinorUnits`) VALUES (" +
                    "'22222222-2222-2222-2222-222222222222', " +
                    "'11111111-1111-1111-1111-111111111111', 1000, 2000, 'NEEDS_REVIEW', " +
                    "'RUC 20123456789', '20123456789', 'F001-42', 'F001-42', " +
                    "'PEN', 1000, 180, 1180)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            7,
            true,
            FacturaStockDatabase.MIGRATION_6_7,
        )
        db.execSQL("PRAGMA foreign_keys=ON")

        db.query(
            "SELECT `status`, `supplierRucNormalized`, `totalMinorUnits`, " +
                "`supplierLegalNameRaw`, `supplierLegalNameNormalized`, `documentType`, " +
                "`otherChargesMinorUnits` FROM `invoice_drafts` " +
                "WHERE `draftId` = '22222222-2222-2222-2222-222222222222'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("NEEDS_REVIEW", cursor.getString(0))
            assertEquals("20123456789", cursor.getString(1))
            assertEquals(1_180L, cursor.getLong(2))
            assertTrue(cursor.isNull(3))
            assertTrue(cursor.isNull(4))
            assertTrue(cursor.isNull(5))
            assertTrue(cursor.isNull(6))
        }

        db.execSQL(
            "INSERT INTO `invoice_header_edits` (" +
                "`draftId`, `revision`, `payloadCodecVersion`, `payloadSha256`, `payload`, `updatedAt`) " +
                "VALUES ('22222222-2222-2222-2222-222222222222', 3, 1, " +
                "'${"a".repeat(64)}', X'0102', 3000)",
        )
        db.query(
            "SELECT `revision`, `updatedAt` FROM `invoice_header_edits` " +
                "WHERE `draftId` = '22222222-2222-2222-2222-222222222222'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(3L, cursor.getLong(0))
            assertEquals(3_000L, cursor.getLong(1))
        }

        db.execSQL(
            "DELETE FROM `invoice_drafts` " +
                "WHERE `draftId` = '22222222-2222-2222-2222-222222222222'",
        )
        db.query("SELECT COUNT(*) FROM `invoice_header_edits`").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }

        helper.closeWhenFinished(db)
    }

    @Test
    fun migrate7To8AddsTypedLineFieldsAndDurableEditorWithCascade() {
        helper.createDatabase(TEST_DB, 7).apply {
            execSQL(
                "INSERT INTO `businesses` " +
                    "(`businessId`, `legalName`, `ruc`, `createdAt`, `updatedAt`, `tradeName`, `status`) " +
                    "VALUES ('11111111-1111-1111-1111-111111111111', 'Negocio Líneas SAC', " +
                    "NULL, 1000, 1000, NULL, 'ACTIVE')",
            )
            execSQL(
                "INSERT INTO `invoice_drafts` " +
                    "(`draftId`, `businessId`, `createdAt`, `updatedAt`, `status`) " +
                    "VALUES ('22222222-2222-2222-2222-222222222222', " +
                    "'11111111-1111-1111-1111-111111111111', 1000, 2000, 'NEEDS_REVIEW')",
            )
            execSQL(
                "INSERT INTO `invoice_lines` (" +
                    "`lineId`, `draftId`, `businessId`, `position`, `descriptionRaw`, " +
                    "`createdAt`, `updatedAt`, `quantity`, `unitCost`, `unitCostCurrency`, " +
                    "`lineTotalMinorUnits`) VALUES (" +
                    "'33333333-3333-3333-3333-333333333333', " +
                    "'22222222-2222-2222-2222-222222222222', " +
                    "'11111111-1111-1111-1111-111111111111', 0, 'Producto v7', " +
                    "1000, 2000, '2.00', '5.00', 'PEN', 1000)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            8,
            true,
            FacturaStockDatabase.MIGRATION_7_8,
        )
        db.execSQL("PRAGMA foreign_keys=ON")

        db.query(
            "SELECT `descriptionRaw`, `quantity`, `lineTotalMinorUnits`, `codeRaw`, " +
                "`unitCodeNormalized`, `discountMinorUnits`, `taxMinorUnits` " +
                "FROM `invoice_lines` WHERE `lineId` = " +
                "'33333333-3333-3333-3333-333333333333'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Producto v7", cursor.getString(0))
            assertEquals("2.00", cursor.getString(1))
            assertEquals(1_000L, cursor.getLong(2))
            assertTrue(cursor.isNull(3))
            assertTrue(cursor.isNull(4))
            assertTrue(cursor.isNull(5))
            assertTrue(cursor.isNull(6))
        }

        db.execSQL(
            "INSERT INTO `invoice_line_edits` (" +
                "`draftId`, `revision`, `payloadCodecVersion`, `payloadSha256`, `payload`, `updatedAt`) " +
                "VALUES ('22222222-2222-2222-2222-222222222222', 4, 1, " +
                "'${"b".repeat(64)}', X'0102', 4000)",
        )
        db.query("SELECT `revision` FROM `invoice_line_edits`").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(4L, cursor.getLong(0))
        }

        db.execSQL(
            "DELETE FROM `invoice_drafts` " +
                "WHERE `draftId` = '22222222-2222-2222-2222-222222222222'",
        )
        db.query("SELECT COUNT(*) FROM `invoice_line_edits`").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }

        helper.closeWhenFinished(db)
    }

    @Test
    fun migrate8To9AddsPurchaseColumnsToProductsWithoutTouchingExistingData() {
        helper.createDatabase(TEST_DB, 8).apply {
            execSQL(
                "INSERT INTO `businesses` " +
                    "(`businessId`, `legalName`, `ruc`, `createdAt`, `updatedAt`, `tradeName`, `status`) " +
                    "VALUES ('11111111-1111-1111-1111-111111111111', 'Negocio Compra SAC', " +
                    "NULL, 1000, 1000, NULL, 'ACTIVE')",
            )
            execSQL(
                "INSERT INTO `units` " +
                    "(`unitId`, `businessId`, `code`, `name`, `createdAt`, `updatedAt`, `symbol`, `status`) " +
                    "VALUES ('44444444-4444-4444-4444-444444444444', " +
                    "'11111111-1111-1111-1111-111111111111', 'NIU', 'Unidad', 1000, 1000, NULL, 'ACTIVE')",
            )
            execSQL(
                "INSERT INTO `products` (" +
                    "`productId`, `businessId`, `unitId`, `name`, `createdAt`, `updatedAt`, " +
                    "`locationId`, `sku`, `barcode`, `normalizedName`, `status`) VALUES (" +
                    "'55555555-5555-5555-5555-555555555555', " +
                    "'11111111-1111-1111-1111-111111111111', " +
                    "'44444444-4444-4444-4444-444444444444', 'Arroz Extra', 1000, 2000, " +
                    "NULL, 'SKU-ARR-01', '7750001000011', 'arroz extra', 'ACTIVE')",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            9,
            true,
            FacturaStockDatabase.MIGRATION_8_9,
        )
        db.execSQL("PRAGMA foreign_keys=ON")

        // El producto de v8 conserva sus datos y las columnas nuevas nacen NULL.
        db.query(
            "SELECT `name`, `sku`, `barcode`, `normalizedName`, `purchaseUnitId`, " +
                "`purchaseFactor`, `status` FROM `products` " +
                "WHERE `productId` = '55555555-5555-5555-5555-555555555555'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Arroz Extra", cursor.getString(0))
            assertEquals("SKU-ARR-01", cursor.getString(1))
            assertEquals("7750001000011", cursor.getString(2))
            assertEquals("arroz extra", cursor.getString(3))
            assertTrue(cursor.isNull(4))
            assertTrue(cursor.isNull(5))
            assertEquals("ACTIVE", cursor.getString(6))
        }

        // El índice de la unidad de compra quedó reconstruido.
        db.query(
            "SELECT COUNT(*) FROM `sqlite_master` WHERE `type` = 'index' " +
                "AND `name` = 'index_products_purchaseUnitId'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }

        // La FK SET_NULL: al borrar la unidad de compra solo la referencia se anula.
        db.execSQL(
            "INSERT INTO `units` " +
                "(`unitId`, `businessId`, `code`, `name`, `createdAt`, `updatedAt`, `symbol`, `status`) " +
                "VALUES ('66666666-6666-6666-6666-666666666666', " +
                "'11111111-1111-1111-1111-111111111111', 'CJA', 'Caja', 1000, 1000, NULL, 'ACTIVE')",
        )
        db.execSQL(
            "UPDATE `products` SET `purchaseUnitId` = '66666666-6666-6666-6666-666666666666', " +
                "`purchaseFactor` = '12' " +
                "WHERE `productId` = '55555555-5555-5555-5555-555555555555'",
        )
        db.execSQL(
            "DELETE FROM `units` WHERE `unitId` = '66666666-6666-6666-6666-666666666666'",
        )
        db.query(
            "SELECT `purchaseUnitId`, `purchaseFactor` FROM `products` " +
                "WHERE `productId` = '55555555-5555-5555-5555-555555555555'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertEquals("12", cursor.getString(1))
        }

        helper.closeWhenFinished(db)
    }

    @Test
    fun migrate11To12CanonicalizesCatalogKeysAndPreservesRows() {
        helper.createDatabase(CANONICAL_DB, 11).apply {
            insertCatalogBusinessAndUnit()
            execSQL(
                "INSERT INTO `suppliers` " +
                    "(`supplierId`,`businessId`,`legalName`,`createdAt`,`updatedAt`,`ruc`,`tradeName`,`status`) " +
                    "VALUES ('33333333-3333-3333-3333-333333333333'," +
                    "'11111111-1111-1111-1111-111111111111','Proveedor legado',1000,2000," +
                    "' 20987654321 ',' Legado ','ACTIVE')",
            )
            execSQL(
                "INSERT INTO `products` " +
                    "(`productId`,`businessId`,`unitId`,`name`,`createdAt`,`updatedAt`,`locationId`," +
                    "`sku`,`barcode`,`normalizedName`,`purchaseUnitId`,`purchaseFactor`,`status`) " +
                    "VALUES ('44444444-4444-4444-4444-444444444444'," +
                    "'11111111-1111-1111-1111-111111111111'," +
                    "'22222222-2222-2222-2222-222222222222','Producto legado',1000,2000,NULL," +
                    "' sku-mixta-01 ',' 0012345 ','producto legado',NULL,NULL,'ACTIVE')",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            CANONICAL_DB,
            12,
            true,
            FacturaStockDatabase.MIGRATION_11_12,
        )
        db.query(
            "SELECT `ruc`,`legalName`,`createdAt`,`updatedAt` FROM `suppliers` " +
                "WHERE `supplierId`='33333333-3333-3333-3333-333333333333'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("20987654321", cursor.getString(0))
            assertEquals("Proveedor legado", cursor.getString(1))
            assertEquals(1_000L, cursor.getLong(2))
            assertEquals(2_000L, cursor.getLong(3))
        }
        db.query(
            "SELECT `sku`,`barcode`,`name`,`unitId` FROM `products` " +
                "WHERE `productId`='44444444-4444-4444-4444-444444444444'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("SKU-MIXTA-01", cursor.getString(0))
            assertEquals("0012345", cursor.getString(1))
            assertEquals("Producto legado", cursor.getString(2))
            assertEquals("22222222-2222-2222-2222-222222222222", cursor.getString(3))
        }
        db.query("PRAGMA foreign_key_check").use { cursor -> assertEquals(0, cursor.count) }
        db.query(
            "SELECT COUNT(*) FROM `sqlite_master` WHERE `type`='trigger' " +
                "AND `name` LIKE 'catalog_%_block_referenced_delete'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(4, cursor.getInt(0))
        }
        helper.closeWhenFinished(db)
    }

    @Test
    fun migrate11To21PreservesLegacyUnicodeBarcodeAndKeepsStrictNewWriteGuard() {
        helper.createDatabase(LEGACY_UNICODE_BARCODE_DB, 11).apply {
            insertCatalogBusinessAndUnit()
            execSQL(
                "INSERT INTO `products` " +
                    "(`productId`,`businessId`,`unitId`,`name`,`createdAt`,`updatedAt`,`locationId`," +
                    "`sku`,`barcode`,`normalizedName`,`purchaseUnitId`,`purchaseFactor`,`status`) " +
                    "VALUES ('44444444-4444-4444-8444-444444444444'," +
                    "'11111111-1111-1111-1111-111111111111'," +
                    "'22222222-2222-2222-2222-222222222222','Producto legado',1000,2000,NULL," +
                    "NULL,' CAFÉ-01 ','producto legado',NULL,NULL,'ACTIVE')",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            LEGACY_UNICODE_BARCODE_DB,
            21,
            true,
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
        )
        db.query(
            "SELECT `barcode` FROM `products` " +
                "WHERE `productId`='44444444-4444-4444-8444-444444444444'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("CAFÉ-01", cursor.getString(0))
        }

        // El trigger de v21 deja editar otros campos si el valor heredado permanece idéntico.
        db.execSQL(
            "UPDATE `products` SET `name`='Producto editado', `barcode`='CAFÉ-01' " +
                "WHERE `productId`='44444444-4444-4444-8444-444444444444'",
        )
        // Pero no convierte esa excepción de lectura en permiso para una escritura Unicode nueva.
        assertThrows(SQLiteException::class.java) {
            db.execSQL(
                "UPDATE `products` SET `barcode`='NUEVO-Ñ' " +
                    "WHERE `productId`='44444444-4444-4444-8444-444444444444'",
            )
        }
        db.query("PRAGMA foreign_key_check").use { cursor -> assertEquals(0, cursor.count) }
        helper.closeWhenFinished(db)
    }

    @Test
    fun migrate11To12PreservesRowsAndClearsCanonicalLosersWhenKeysCollide() {
        helper.createDatabase(COLLISION_DB, 11).apply {
            insertCatalogBusinessAndUnit()
            execSQL(
                "INSERT INTO `suppliers` " +
                    "(`supplierId`,`businessId`,`legalName`,`createdAt`,`updatedAt`,`ruc`,`tradeName`,`status`) VALUES " +
                    "('33333333-3333-3333-3333-333333333333'," +
                    "'11111111-1111-1111-1111-111111111111','Proveedor uno',1000,1000," +
                    "' 20987654321 ',NULL,'ACTIVE')," +
                    "('66666666-6666-6666-6666-666666666666'," +
                    "'11111111-1111-1111-1111-111111111111','Proveedor dos',1000,1000," +
                    "'20987654321',NULL,'ACTIVE')",
            )
            execSQL(
                "INSERT INTO `products` " +
                    "(`productId`,`businessId`,`unitId`,`name`,`createdAt`,`updatedAt`,`locationId`," +
                    "`sku`,`barcode`,`normalizedName`,`purchaseUnitId`,`purchaseFactor`,`status`) VALUES " +
                    "('44444444-4444-4444-4444-444444444444'," +
                    "'11111111-1111-1111-1111-111111111111'," +
                    "'22222222-2222-2222-2222-222222222222','Uno',1000,1000,NULL," +
                    "' sku-1 ',' 0012345 ','uno',NULL,NULL,'ACTIVE')," +
                    "('55555555-5555-5555-5555-555555555555'," +
                    "'11111111-1111-1111-1111-111111111111'," +
                    "'22222222-2222-2222-2222-222222222222','Dos',1000,1000,NULL," +
                    "'SKU-1','0012345','dos',NULL,NULL,'ACTIVE')",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            COLLISION_DB,
            12,
            true,
            FacturaStockDatabase.MIGRATION_11_12,
        )
        db.query("SELECT `productId`,`sku`,`barcode` FROM `products` ORDER BY `productId`").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("44444444-4444-4444-4444-444444444444", cursor.getString(0))
            assertEquals("SKU-1", cursor.getString(1))
            assertEquals("0012345", cursor.getString(2))
            assertTrue(cursor.moveToNext())
            assertEquals("55555555-5555-5555-5555-555555555555", cursor.getString(0))
            assertTrue(cursor.isNull(1))
            assertTrue(cursor.isNull(2))
        }
        db.query("SELECT COUNT(*) FROM `products`").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(0))
        }
        db.query("SELECT `supplierId`,`ruc` FROM `suppliers` ORDER BY `supplierId`").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("33333333-3333-3333-3333-333333333333", cursor.getString(0))
            assertEquals("20987654321", cursor.getString(1))
            assertTrue(cursor.moveToNext())
            assertEquals("66666666-6666-6666-6666-666666666666", cursor.getString(0))
            assertTrue(cursor.isNull(1))
        }
        db.query("PRAGMA foreign_key_check").use { cursor -> assertEquals(0, cursor.count) }
        helper.closeWhenFinished(db)
    }

    @Test
    fun migrate12To13PreservesReadCostAndLeavesUnknownCostingDecisionsNull() {
        helper.createDatabase(COSTING_DB, 12).apply {
            insertCatalogBusinessAndUnit()
            execSQL(
                "INSERT INTO `suppliers` " +
                    "(`supplierId`,`businessId`,`legalName`,`createdAt`,`updatedAt`,`ruc`,`tradeName`,`status`) " +
                    "VALUES ('33333333-3333-3333-3333-333333333333'," +
                    "'11111111-1111-1111-1111-111111111111','Proveedor',1000,1000," +
                    "'20987654321',NULL,'ACTIVE')",
            )
            execSQL(
                "INSERT INTO `products` " +
                    "(`productId`,`businessId`,`unitId`,`name`,`createdAt`,`updatedAt`,`locationId`," +
                    "`sku`,`barcode`,`normalizedName`,`purchaseUnitId`,`purchaseFactor`,`status`) " +
                    "VALUES ('44444444-4444-4444-4444-444444444444'," +
                    "'11111111-1111-1111-1111-111111111111'," +
                    "'22222222-2222-2222-2222-222222222222','Producto',1000,1000,NULL," +
                    "'SKU-1',NULL,'producto',NULL,NULL,'ACTIVE')",
            )
            execSQL(
                "INSERT INTO `invoice_drafts` " +
                    "(`draftId`,`businessId`,`createdAt`,`updatedAt`,`status`) VALUES " +
                    "('55555555-5555-5555-5555-555555555555'," +
                    "'11111111-1111-1111-1111-111111111111',1000,1000,'NEEDS_REVIEW')",
            )
            execSQL(
                "INSERT INTO `purchases` " +
                    "(`purchaseId`,`businessId`,`sourceDraftId`,`supplierId`,`documentType`," +
                    "`documentSeries`,`documentNumber`,`issueDate`,`currencyCode`," +
                    "`subtotalMinorUnits`,`taxMinorUnits`,`otherChargesMinorUnits`," +
                    "`totalMinorUnits`,`status`,`idempotencyKey`,`createdAt`,`updatedAt`," +
                    "`postedAt`,`voidedAt`) VALUES " +
                    "('66666666-6666-6666-6666-666666666666'," +
                    "'11111111-1111-1111-1111-111111111111'," +
                    "'55555555-5555-5555-5555-555555555555'," +
                    "'33333333-3333-3333-3333-333333333333','INVOICE','F001','42'," +
                    "'2026-08-13','PEN',1000,180,0,1180,'DRAFT','legacy-cost',1000,1000,NULL,NULL)",
            )
            execSQL(
                "INSERT INTO `purchase_lines` " +
                    "(`purchaseLineId`,`purchaseId`,`productId`,`unitId`,`position`,`rawText`," +
                    "`description`,`quantity`,`unitCost`,`currencyCode`,`taxMinorUnits`," +
                    "`totalMinorUnits`,`confidence`) VALUES " +
                    "('77777777-7777-7777-7777-777777777777'," +
                    "'66666666-6666-6666-6666-666666666666'," +
                    "'44444444-4444-4444-4444-444444444444'," +
                    "'22222222-2222-2222-2222-222222222222',0,'2 PRODUCTO 10.125'," +
                    "'Producto','2','10.125','PEN',180,1180,950)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            COSTING_DB,
            13,
            true,
            FacturaStockDatabase.MIGRATION_12_13,
        )
        db.query(
            "SELECT `unitCost`,`appliedUnitCost`,`purchaseUnitFactor`,`inventoryQuantity`," +
                "`discount`,`taxTreatment`,`costPolicy`,`appliedCostTotal`," +
                "`taxEvidenceType`,`taxEvidenceValue`,`roundingScale`,`roundingMode`," +
                "`costingWarnings` FROM `purchase_lines` WHERE " +
                "`purchaseLineId`='77777777-7777-7777-7777-777777777777'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("10.125", cursor.getString(0))
            for (column in 1..12) assertTrue(cursor.isNull(column))
        }
        db.query("PRAGMA foreign_key_check").use { cursor -> assertEquals(0, cursor.count) }
        helper.closeWhenFinished(db)
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.insertCatalogBusinessAndUnit() {
        execSQL(
            "INSERT INTO `businesses` " +
                "(`businessId`,`legalName`,`ruc`,`createdAt`,`updatedAt`,`tradeName`,`status`) " +
                "VALUES ('11111111-1111-1111-1111-111111111111','Negocio',NULL,1000,1000,NULL,'ACTIVE')",
        )
        execSQL(
            "INSERT INTO `units` " +
                "(`unitId`,`businessId`,`code`,`name`,`createdAt`,`updatedAt`,`symbol`,`status`) " +
                "VALUES ('22222222-2222-2222-2222-222222222222'," +
                "'11111111-1111-1111-1111-111111111111','NIU','Unidad',1000,1000,NULL,'ACTIVE')",
        )
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
        const val CANONICAL_DB = "migration-canonical-test.db"
        const val LEGACY_UNICODE_BARCODE_DB = "migration-legacy-unicode-barcode-test.db"
        const val COLLISION_DB = "migration-collision-test.db"
        const val COSTING_DB = "migration-costing-test.db"
        const val OUTBOX_DB = "migration-outbox-test.db"
        const val SYNC_DB = "migration-sync-test.db"
        const val SALE_PRICE_DB = "migration-sale-price-test.db"
    }

    @Test
    fun migrate14To15AddsClaimProtocolColumnsPreservingRows() {
        helper.createDatabase(OUTBOX_DB, 14).apply {
            insertCatalogBusinessAndUnit()
            execSQL(
                "INSERT INTO `outbox_operations` " +
                    "(`operationId`,`businessId`,`purchaseId`,`idempotencyKey`,`operationType`," +
                    "`payload`,`status`,`attemptCount`,`createdAt`,`updatedAt`,`nextAttemptAt`," +
                    "`completedAt`,`lastError`) VALUES " +
                    "('55555555-5555-5555-5555-555555555555'," +
                    "'11111111-1111-1111-1111-111111111111',NULL,'sync-purchase:v1:abc'," +
                    "'SYNC_PURCHASE','{\"version\":2}','PROCESSING',2,1000,1500,NULL,NULL," +
                    "'NETWORK_UNAVAILABLE')",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            OUTBOX_DB,
            15,
            true,
            FacturaStockDatabase.MIGRATION_14_15,
        )

        // La fila v14 conserva todo su estado; las columnas nuevas nacen con sus defaults y un
        // claim legado queda con lease NULL (la recuperación lo trata como vencido).
        db.query(
            "SELECT `status`,`attemptCount`,`updatedAt`,`lastError`,`payloadVersion`," +
                "`claimToken`,`claimLeaseUntil` FROM `outbox_operations` " +
                "WHERE `operationId` = '55555555-5555-5555-5555-555555555555'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("PROCESSING", cursor.getString(0))
            assertEquals(2, cursor.getInt(1))
            assertEquals(1_500L, cursor.getLong(2))
            assertEquals("NETWORK_UNAVAILABLE", cursor.getString(3))
            assertEquals(1, cursor.getInt(4))
            assertTrue(cursor.isNull(5))
            assertTrue(cursor.isNull(6))
        }

        // El esquema v15 admite el protocolo completo de claim con token y lease.
        db.execSQL(
            "INSERT INTO `outbox_operations` " +
                "(`operationId`,`businessId`,`purchaseId`,`idempotencyKey`,`operationType`," +
                "`payload`,`status`,`attemptCount`,`createdAt`,`updatedAt`,`nextAttemptAt`," +
                "`completedAt`,`lastError`,`payloadVersion`,`claimToken`,`claimLeaseUntil`) " +
                "VALUES ('66666666-6666-6666-6666-666666666666'," +
                "'11111111-1111-1111-1111-111111111111',NULL,'sync-purchase:v1:def'," +
                "'SYNC_PURCHASE','{\"version\":2}','PROCESSING',1,2000,2000,NULL,NULL,NULL,2," +
                "'77777777-7777-7777-7777-777777777777',2300000)",
        )
        db.query(
            "SELECT COUNT(*) FROM `outbox_operations` WHERE `claimToken` IS NOT NULL",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        db.query("PRAGMA foreign_key_check").use { cursor -> assertEquals(0, cursor.count) }
        helper.closeWhenFinished(db)
    }

    @Test
    fun migrate15To16AddsCatalogVersionsAndConflictIdentityPreservingRows() {
        helper.createDatabase(SYNC_DB, 15).apply {
            insertCatalogBusinessAndUnit()
            execSQL(
                "INSERT INTO `suppliers` " +
                    "(`supplierId`,`businessId`,`legalName`,`createdAt`,`updatedAt`,`ruc`," +
                    "`tradeName`,`status`) VALUES " +
                    "('33333333-3333-3333-3333-333333333333'," +
                    "'11111111-1111-1111-1111-111111111111','Proveedor',1000,1000," +
                    "'20987654321',NULL,'ACTIVE')",
            )
            execSQL(
                "INSERT INTO `products` " +
                    "(`productId`,`businessId`,`unitId`,`name`,`createdAt`,`updatedAt`,`locationId`," +
                    "`sku`,`barcode`,`normalizedName`,`purchaseUnitId`,`purchaseFactor`,`status`) " +
                    "VALUES ('44444444-4444-4444-4444-444444444444'," +
                    "'11111111-1111-1111-1111-111111111111'," +
                    "'22222222-2222-2222-2222-222222222222','Producto',1000,1000,NULL," +
                    "'SKU-1',NULL,'producto',NULL,NULL,'ACTIVE')",
            )
            execSQL(
                "INSERT INTO `outbox_operations` " +
                    "(`operationId`,`businessId`,`purchaseId`,`idempotencyKey`,`operationType`," +
                    "`payload`,`status`,`attemptCount`,`createdAt`,`updatedAt`,`nextAttemptAt`," +
                    "`completedAt`,`lastError`,`payloadVersion`,`claimToken`,`claimLeaseUntil`) " +
                    "VALUES ('55555555-5555-5555-5555-555555555555'," +
                    "'11111111-1111-1111-1111-111111111111',NULL,'sync-purchase:v1:ghi'," +
                    "'SYNC_PURCHASE','{\"version\":2}','CONFLICT',3,1000,1500,NULL,NULL," +
                    "'ALREADY_EXISTS',2,NULL,NULL)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            SYNC_DB,
            16,
            true,
            FacturaStockDatabase.MIGRATION_15_16,
        )

        // Las filas v15 sobreviven intactas: los catálogos nacen en versión 1 y la operación en
        // conflicto conserva su estado sin identidad remota (el backend aún no la informaba).
        db.query(
            "SELECT `version` FROM `suppliers` " +
                "WHERE `supplierId` = '33333333-3333-3333-3333-333333333333'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(0))
        }
        db.query(
            "SELECT `version` FROM `products` " +
                "WHERE `productId` = '44444444-4444-4444-4444-444444444444'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(0))
        }
        db.query(
            "SELECT `status`,`attemptCount`,`lastError`,`conflictRemotePurchaseId`," +
                "`conflictReceiptId` FROM `outbox_operations` " +
                "WHERE `operationId` = '55555555-5555-5555-5555-555555555555'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("CONFLICT", cursor.getString(0))
            assertEquals(3, cursor.getInt(1))
            assertEquals("ALREADY_EXISTS", cursor.getString(2))
            assertTrue(cursor.isNull(3))
            assertTrue(cursor.isNull(4))
        }

        // El esquema v16 admite la identidad del registro remoto en conflicto.
        db.execSQL(
            "INSERT INTO `outbox_operations` " +
                "(`operationId`,`businessId`,`purchaseId`,`idempotencyKey`,`operationType`," +
                "`payload`,`status`,`attemptCount`,`createdAt`,`updatedAt`,`nextAttemptAt`," +
                "`completedAt`,`lastError`,`payloadVersion`,`claimToken`,`claimLeaseUntil`," +
                "`conflictRemotePurchaseId`,`conflictReceiptId`) VALUES " +
                "('66666666-6666-6666-6666-666666666666'," +
                "'11111111-1111-1111-1111-111111111111',NULL,'sync-purchase:v1:jkl'," +
                "'SYNC_PURCHASE','{\"version\":2}','CONFLICT',1,2000,2000,NULL,NULL," +
                "'FAILED_PRECONDITION',2,NULL,NULL," +
                "'77777777-7777-7777-7777-777777777777','receipt-remoto-9')",
        )
        db.query(
            "SELECT `conflictRemotePurchaseId`,`conflictReceiptId` FROM `outbox_operations` " +
                "WHERE `operationId` = '66666666-6666-6666-6666-666666666666'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("77777777-7777-7777-7777-777777777777", cursor.getString(0))
            assertEquals("receipt-remoto-9", cursor.getString(1))
        }
        db.query("PRAGMA foreign_key_check").use { cursor -> assertEquals(0, cursor.count) }
        helper.closeWhenFinished(db)
    }

    @Test
    fun migrate18To19AddsTypedCausalOutboxAndDurableRemoteMirror() {
        helper.createDatabase(SYNC_DB, 18).apply {
            insertCatalogBusinessAndUnit()
            execSQL(
                "INSERT INTO `outbox_operations` " +
                    "(`operationId`,`businessId`,`purchaseId`,`idempotencyKey`,`operationType`," +
                    "`payload`,`status`,`attemptCount`,`createdAt`,`updatedAt`,`nextAttemptAt`," +
                    "`completedAt`,`lastError`,`payloadVersion`,`claimToken`,`claimLeaseUntil`," +
                    "`conflictRemotePurchaseId`,`conflictReceiptId`) VALUES " +
                    "('55555555-5555-5555-5555-555555555555'," +
                    "'11111111-1111-1111-1111-111111111111',NULL,'legacy-post'," +
                    "'SYNC_PURCHASE','{\"version\":2}','PENDING',0,1000,1000,NULL,NULL," +
                    "NULL,2,NULL,NULL,NULL,NULL)," +
                    "('66666666-6666-6666-6666-666666666666'," +
                    "'11111111-1111-1111-1111-111111111111',NULL,'legacy-void'," +
                    "'SYNC_PURCHASE_VOID','{\"version\":1}','PENDING',0,1001,1001,NULL,NULL," +
                    "NULL,1,NULL,NULL,NULL,NULL)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            SYNC_DB,
            19,
            true,
            FacturaStockDatabase.MIGRATION_18_19,
        )

        db.query(
            "SELECT `operationId`,`entityType`,`entityId`,`entityVersion`,`remoteEntityId`," +
                "`conflictCloudBusinessId` " +
                "FROM `outbox_operations` ORDER BY `createdAt`",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("PURCHASE", cursor.getString(1))
            assertEquals(cursor.getString(0), cursor.getString(2))
            assertEquals(1L, cursor.getLong(3))
            assertTrue(cursor.isNull(4))
            assertTrue(cursor.isNull(5))
            assertTrue(cursor.moveToNext())
            assertEquals("PURCHASE", cursor.getString(1))
            assertEquals(cursor.getString(0), cursor.getString(2))
            assertEquals(2L, cursor.getLong(3))
            assertTrue(cursor.isNull(4))
            assertTrue(cursor.isNull(5))
        }
        listOf(
            "remote_sync_states",
            "remote_purchase_changes",
            "remote_movement_summaries",
            "remote_catalog_changes",
            "catalog_sync_links",
        ).forEach { table ->
            db.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0L, cursor.getLong(0))
            }
        }
        db.query("PRAGMA foreign_key_check").use { cursor -> assertEquals(0, cursor.count) }
        helper.closeWhenFinished(db)
    }

    @Test
    fun migrate19To20KeepsLegacyTargetsUnknownAndCreatesImmutableBindingSchema() {
        helper.createDatabase(SYNC_DB, 19).apply {
            insertCatalogBusinessAndUnit()
            execSQL(
                "INSERT INTO `outbox_operations` (`operationId`,`businessId`,`purchaseId`," +
                    "`idempotencyKey`,`operationType`,`payload`,`status`,`attemptCount`," +
                    "`createdAt`,`updatedAt`,`nextAttemptAt`,`completedAt`,`lastError`," +
                    "`payloadVersion`,`claimToken`,`claimLeaseUntil`,`entityType`,`entityId`," +
                    "`entityVersion`,`remoteEntityId`) VALUES " +
                    "('55555555-5555-4555-8555-555555555555'," +
                    "'11111111-1111-1111-1111-111111111111',NULL,'pending-v19'," +
                    "'SYNC_PRODUCT','{\"version\":1}','PENDING',0,1000,1000,NULL,NULL,NULL," +
                    "1,NULL,NULL,'PRODUCT','55555555-5555-4555-8555-555555555555',1,NULL)," +
                    "('66666666-6666-4666-8666-666666666666'," +
                    "'11111111-1111-1111-1111-111111111111',NULL,'completed-v19'," +
                    "'SYNC_SUPPLIER','{\"version\":1}','COMPLETED',1,1001,1002,NULL,1002,NULL," +
                    "1,NULL,NULL,'SUPPLIER','66666666-6666-4666-8666-666666666666',1,NULL)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            SYNC_DB,
            20,
            true,
            FacturaStockDatabase.MIGRATION_19_20,
        )

        db.query(
            "SELECT `status`,`targetCloudBusinessId` FROM `outbox_operations` " +
                "ORDER BY `createdAt`",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("PENDING", cursor.getString(0))
            assertTrue(cursor.isNull(1))
            assertTrue(cursor.moveToNext())
            assertEquals("COMPLETED", cursor.getString(0))
            assertTrue(cursor.isNull(1))
        }
        db.query("SELECT COUNT(*) FROM `cloud_business_bindings`").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        db.query("PRAGMA foreign_key_check").use { cursor -> assertEquals(0, cursor.count) }
        helper.closeWhenFinished(db)
    }

    @Test
    fun migrate20To21PreservesLedgerAndAddsEmptySalesGraph() {
        helper.createDatabase(TEST_DB, 20).apply {
            execSQL(
                "INSERT INTO `businesses` (`businessId`,`legalName`,`createdAt`,`updatedAt`," +
                    "`ruc`,`tradeName`,`status`) VALUES " +
                    "('11111111-1111-1111-1111-111111111111','Negocio',1000,1000,NULL,NULL,'ACTIVE')",
            )
            execSQL(
                "INSERT INTO `units` (`unitId`,`businessId`,`code`,`name`,`createdAt`," +
                    "`updatedAt`,`symbol`,`status`) VALUES " +
                    "('22222222-2222-2222-2222-222222222222'," +
                    "'11111111-1111-1111-1111-111111111111','NIU','Unidad',1000,1000,NULL,'ACTIVE')",
            )
            execSQL(
                "INSERT INTO `inventory_locations` (`locationId`,`businessId`,`name`,`createdAt`," +
                    "`updatedAt`,`status`) VALUES ('33333333-3333-3333-3333-333333333333'," +
                    "'11111111-1111-1111-1111-111111111111','Principal',1000,1000,'ACTIVE')",
            )
            execSQL(
                "INSERT INTO `products` (`productId`,`businessId`,`unitId`,`name`,`createdAt`," +
                    "`updatedAt`,`locationId`,`sku`,`barcode`,`normalizedName`,`purchaseUnitId`," +
                    "`purchaseFactor`,`status`,`version`) VALUES " +
                    "('44444444-4444-4444-8444-444444444444'," +
                    "'11111111-1111-1111-1111-111111111111'," +
                    "'22222222-2222-2222-2222-222222222222','Producto',1000,1000," +
                    "'33333333-3333-3333-3333-333333333333',NULL,'café-旧'," +
                    "'producto',NULL,NULL,'ACTIVE',1)",
            )
            execSQL(
                "INSERT INTO `inventory_balances` (`businessId`,`productId`,`locationId`," +
                    "`quantityOnHand`,`averageUnitCost`,`currencyCode`,`version`,`updatedAt`) " +
                    "VALUES ('11111111-1111-1111-1111-111111111111'," +
                    "'44444444-4444-4444-8444-444444444444'," +
                    "'33333333-3333-3333-3333-333333333333','7.500','2.2500','PEN',4,1000)",
            )
            execSQL(
                "INSERT INTO `stock_movements` (`movementId`,`businessId`,`purchaseId`," +
                    "`purchaseLineId`,`productId`,`locationId`,`type`,`quantityDelta`,`unitCost`," +
                    "`currencyCode`,`idempotencyKey`,`occurredAt`,`createdAt`) VALUES " +
                    "('55555555-5555-4555-8555-555555555555'," +
                    "'11111111-1111-1111-1111-111111111111',NULL,NULL," +
                    "'44444444-4444-4444-8444-444444444444'," +
                    "'33333333-3333-3333-3333-333333333333','ADJUSTMENT','7.500','2.2500'," +
                    "'PEN','adjustment:v20',1000,1000)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            21,
            true,
            FacturaStockDatabase.MIGRATION_20_21,
        )
        db.query(
            "SELECT `quantityDelta`,`unitCost`,`idempotencyKey`,`saleId`,`saleLineId` " +
                "FROM `stock_movements` WHERE `movementId` = " +
                "'55555555-5555-4555-8555-555555555555'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("7.500", cursor.getString(0))
            assertEquals("2.2500", cursor.getString(1))
            assertEquals("adjustment:v20", cursor.getString(2))
            assertTrue(cursor.isNull(3))
            assertTrue(cursor.isNull(4))
        }
        db.query("SELECT COUNT(*) FROM `sales`").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        db.query(
            "SELECT `barcode` FROM `products` WHERE `productId` = " +
                "'44444444-4444-4444-8444-444444444444'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("café-旧", cursor.getString(0))
        }
        // Conservar exactamente el valor heredado no bloquea una edición de otro campo.
        db.execSQL(
            "UPDATE `products` SET `name` = 'Producto editado', `barcode` = 'café-旧' " +
                "WHERE `productId` = '44444444-4444-4444-8444-444444444444'",
        )
        assertThrows(SQLiteException::class.java) {
            db.execSQL(
                "INSERT INTO `products` (`productId`,`businessId`,`unitId`,`name`,`createdAt`," +
                    "`updatedAt`,`locationId`,`sku`,`barcode`,`normalizedName`,`purchaseUnitId`," +
                    "`purchaseFactor`,`status`,`version`) VALUES " +
                    "('66666666-6666-4666-8666-666666666666'," +
                    "'11111111-1111-1111-1111-111111111111'," +
                    "'22222222-2222-2222-2222-222222222222','Nuevo inseguro',1000,1000," +
                    "NULL,NULL,'nuevo-ñ','nuevo inseguro',NULL,NULL,'ACTIVE',1)",
            )
        }
        db.query("PRAGMA foreign_key_check").use { cursor -> assertEquals(0, cursor.count) }
        helper.closeWhenFinished(db)
    }

    @Test
    fun migrate21To22PreservesOperationalGraphAndGuardsSalePricePair() {
        helper.createDatabase(SALE_PRICE_DB, 21).apply {
            execSQL(
                "INSERT INTO `businesses` (`businessId`,`legalName`,`createdAt`,`updatedAt`," +
                    "`ruc`,`tradeName`,`status`) VALUES " +
                    "('11111111-1111-4111-8111-111111111111','Negocio',1000,1000," +
                    "'20123456789',NULL,'ACTIVE')",
            )
            execSQL(
                "INSERT INTO `suppliers` (`supplierId`,`businessId`,`legalName`,`createdAt`," +
                    "`updatedAt`,`ruc`,`tradeName`,`status`,`version`) VALUES " +
                    "('22222222-2222-4222-8222-222222222222'," +
                    "'11111111-1111-4111-8111-111111111111','Proveedor',1000,1000," +
                    "'20987654321',NULL,'ACTIVE',3)",
            )
            execSQL(
                "INSERT INTO `units` (`unitId`,`businessId`,`code`,`name`,`createdAt`," +
                    "`updatedAt`,`symbol`,`status`) VALUES " +
                    "('33333333-3333-4333-8333-333333333333'," +
                    "'11111111-1111-4111-8111-111111111111','NIU','Unidad',1000,1000," +
                    "'u','ACTIVE')",
            )
            execSQL(
                "INSERT INTO `inventory_locations` (`locationId`,`businessId`,`name`," +
                    "`createdAt`,`updatedAt`,`status`) VALUES " +
                    "('44444444-4444-4444-8444-444444444444'," +
                    "'11111111-1111-4111-8111-111111111111','Principal',1000,1000,'ACTIVE')",
            )
            execSQL(
                "INSERT INTO `products` (`productId`,`businessId`,`unitId`,`name`,`createdAt`," +
                    "`updatedAt`,`locationId`,`sku`,`barcode`,`normalizedName`,`purchaseUnitId`," +
                    "`purchaseFactor`,`status`,`version`) VALUES " +
                    "('55555555-5555-4555-8555-555555555555'," +
                    "'11111111-1111-4111-8111-111111111111'," +
                    "'33333333-3333-4333-8333-333333333333','Café',1000,1500," +
                    "'44444444-4444-4444-8444-444444444444','CAFE-1','café-旧'," +
                    "'café',NULL,NULL,'ACTIVE',7)",
            )
            execSQL(
                "INSERT INTO `invoice_drafts` (`draftId`,`businessId`,`createdAt`,`updatedAt`," +
                    "`status`,`supplierId`,`supplierRucRaw`,`supplierRucNormalized`," +
                    "`documentType`,`documentNumberRaw`,`documentNumberNormalized`," +
                    "`issueDateRaw`,`issueDateNormalized`,`currencyCode`,`subtotalMinorUnits`," +
                    "`taxMinorUnits`,`otherChargesMinorUnits`,`totalMinorUnits`," +
                    "`confirmedPurchaseId`) VALUES " +
                    "('66666666-6666-4666-8666-666666666666'," +
                    "'11111111-1111-4111-8111-111111111111',1000,2000,'COMMITTED'," +
                    "'22222222-2222-4222-8222-222222222222','20987654321','20987654321'," +
                    "'INVOICE','F001-42','F001-42','2026-08-24','2026-08-24','PEN'," +
                    "10005,1801,99,11905,'77777777-7777-4777-8777-777777777777')",
            )
            execSQL(
                "INSERT INTO `purchases` (`purchaseId`,`businessId`,`sourceDraftId`,`supplierId`," +
                    "`documentType`,`documentSeries`,`documentNumber`,`issueDate`,`currencyCode`," +
                    "`subtotalMinorUnits`,`taxMinorUnits`,`otherChargesMinorUnits`," +
                    "`totalMinorUnits`,`status`,`idempotencyKey`,`createdAt`,`updatedAt`," +
                    "`postedAt`,`voidedAt`,`documentIdentitySlot`) VALUES " +
                    "('77777777-7777-4777-8777-777777777777'," +
                    "'11111111-1111-4111-8111-111111111111'," +
                    "'66666666-6666-4666-8666-666666666666'," +
                    "'22222222-2222-4222-8222-222222222222','INVOICE','F001','42'," +
                    "'2026-08-24','PEN',10005,1801,99,11905,'POSTED'," +
                    "'purchase:v1:migration-21',2000,2000,2000,NULL,'PRIMARY')",
            )
            execSQL(
                "INSERT INTO `purchase_lines` (`purchaseLineId`,`purchaseId`,`productId`," +
                    "`unitId`,`productNameSnapshot`,`unitCodeSnapshot`,`position`,`rawText`," +
                    "`description`,`quantity`,`unitCost`,`currencyCode`,`taxMinorUnits`," +
                    "`totalMinorUnits`,`confidence`,`appliedUnitCost`,`purchaseUnitFactor`," +
                    "`inventoryQuantity`,`discount`,`taxTreatment`,`costPolicy`," +
                    "`appliedCostTotal`,`taxEvidenceType`,`taxEvidenceValue`,`roundingScale`," +
                    "`roundingMode`,`costingWarnings`,`productProvenance`) VALUES " +
                    "('88888888-8888-4888-8888-888888888888'," +
                    "'77777777-7777-4777-8777-777777777777'," +
                    "'55555555-5555-4555-8555-555555555555'," +
                    "'33333333-3333-4333-8333-333333333333','Café','NIU',0," +
                    "'12.5000 CAFE','Café','12.5000','8.0040','PEN',1801,11905,950," +
                    "'0.667000','12.0000','150.0000','0.0000','INCLUDED','NET'," +
                    "'100.050000','EXPLICIT_AMOUNT','18.0100',6,'HALF_EVEN',''," +
                    "'EXISTING')",
            )
            execSQL(
                "INSERT INTO `inventory_balances` (`businessId`,`productId`,`locationId`," +
                    "`quantityOnHand`,`averageUnitCost`,`currencyCode`,`version`,`updatedAt`) " +
                    "VALUES ('11111111-1111-4111-8111-111111111111'," +
                    "'55555555-5555-4555-8555-555555555555'," +
                    "'44444444-4444-4444-8444-444444444444','150.0000','0.667000'," +
                    "'PEN',9,2000)",
            )
            execSQL(
                "INSERT INTO `sales` (`saleId`,`businessId`,`status`,`currencyCode`," +
                    "`subtotalMinorUnits`,`discountMinorUnits`,`taxMinorUnits`,`totalMinorUnits`," +
                    "`contentHash`,`draftSlot`,`checkoutIdempotencyKey`,`version`,`createdAt`," +
                    "`updatedAt`,`postedAt`) VALUES " +
                    "('99999999-9999-4999-8999-999999999999'," +
                    "'11111111-1111-4111-8111-111111111111','POSTED','PEN',900,0,0,900," +
                    "'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'," +
                    "NULL,'sale:v1:migration-21',4,2100,2200,2200)",
            )
            execSQL(
                "INSERT INTO `sale_lines` (`saleLineId`,`saleId`,`productId`,`unitId`," +
                    "`locationId`,`position`,`productNameSnapshot`,`unitCodeSnapshot`," +
                    "`locationNameSnapshot`,`barcodeSnapshot`,`quantity`,`unitPriceMinorUnits`," +
                    "`discountMinorUnits`,`taxMinorUnits`,`lineTotalMinorUnits`,`currencyCode`) " +
                    "VALUES ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'," +
                    "'99999999-9999-4999-8999-999999999999'," +
                    "'55555555-5555-4555-8555-555555555555'," +
                    "'33333333-3333-4333-8333-333333333333'," +
                    "'44444444-4444-4444-8444-444444444444',0,'Café','NIU','Principal'," +
                    "'café-旧','2.0000',450,0,0,900,'PEN')",
            )
            execSQL(
                "INSERT INTO `outbox_operations` (`operationId`,`businessId`,`purchaseId`," +
                    "`idempotencyKey`,`operationType`,`payload`,`status`,`attemptCount`," +
                    "`createdAt`,`updatedAt`,`nextAttemptAt`,`completedAt`,`lastError`," +
                    "`payloadVersion`,`entityType`,`entityId`,`entityVersion`) VALUES " +
                    "('bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'," +
                    "'11111111-1111-4111-8111-111111111111'," +
                    "'77777777-7777-4777-8777-777777777777','sync-purchase:v2:migration-21'," +
                    "'SYNC_PURCHASE','{\"version\":2}','PENDING',2,2200,2300,2400,NULL,NULL,2," +
                    "'PURCHASE','77777777-7777-4777-8777-777777777777',1)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            SALE_PRICE_DB,
            22,
            true,
            FacturaStockDatabase.MIGRATION_21_22,
        )
        db.execSQL("PRAGMA foreign_keys=ON")

        db.query(
            "SELECT `barcode`,`version`,`salePriceMinorUnits`,`salePriceCurrencyCode` " +
                "FROM `products` WHERE `productId`='55555555-5555-4555-8555-555555555555'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("café-旧", cursor.getString(0))
            assertEquals(7L, cursor.getLong(1))
            assertTrue(cursor.isNull(2))
            assertTrue(cursor.isNull(3))
        }
        db.query(
            "SELECT `status`,`subtotalMinorUnits`,`taxMinorUnits`,`otherChargesMinorUnits`," +
                "`totalMinorUnits` FROM `invoice_drafts` WHERE " +
                "`draftId`='66666666-6666-4666-8666-666666666666'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("COMMITTED", cursor.getString(0))
            assertEquals(10_005L, cursor.getLong(1))
            assertEquals(1_801L, cursor.getLong(2))
            assertEquals(99L, cursor.getLong(3))
            assertEquals(11_905L, cursor.getLong(4))
        }
        db.query(
            "SELECT `status`,`totalMinorUnits`,`idempotencyKey` FROM `purchases` WHERE " +
                "`purchaseId`='77777777-7777-4777-8777-777777777777'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("POSTED", cursor.getString(0))
            assertEquals(11_905L, cursor.getLong(1))
            assertEquals("purchase:v1:migration-21", cursor.getString(2))
        }
        db.query(
            "SELECT `quantity`,`unitCost`,`appliedUnitCost`,`inventoryQuantity`," +
                "`appliedCostTotal` FROM `purchase_lines` WHERE " +
                "`purchaseLineId`='88888888-8888-4888-8888-888888888888'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("12.5000", cursor.getString(0))
            assertEquals("8.0040", cursor.getString(1))
            assertEquals("0.667000", cursor.getString(2))
            assertEquals("150.0000", cursor.getString(3))
            assertEquals("100.050000", cursor.getString(4))
        }
        db.query(
            "SELECT `quantityOnHand`,`averageUnitCost`,`currencyCode`,`version` " +
                "FROM `inventory_balances` WHERE " +
                "`productId`='55555555-5555-4555-8555-555555555555'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("150.0000", cursor.getString(0))
            assertEquals("0.667000", cursor.getString(1))
            assertEquals("PEN", cursor.getString(2))
            assertEquals(9L, cursor.getLong(3))
        }
        db.query(
            "SELECT s.`status`,s.`totalMinorUnits`,l.`quantity`,l.`unitPriceMinorUnits`," +
                "l.`lineTotalMinorUnits` FROM `sales` s JOIN `sale_lines` l " +
                "ON l.`saleId`=s.`saleId` WHERE " +
                "s.`saleId`='99999999-9999-4999-8999-999999999999'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("POSTED", cursor.getString(0))
            assertEquals(900L, cursor.getLong(1))
            assertEquals("2.0000", cursor.getString(2))
            assertEquals(450L, cursor.getLong(3))
            assertEquals(900L, cursor.getLong(4))
        }
        db.query(
            "SELECT `status`,`attemptCount`,`nextAttemptAt`,`payloadVersion` " +
                "FROM `outbox_operations` WHERE " +
                "`operationId`='bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("PENDING", cursor.getString(0))
            assertEquals(2, cursor.getInt(1))
            assertEquals(2_400L, cursor.getLong(2))
            assertEquals(2, cursor.getInt(3))
        }

        assertThrows(SQLiteException::class.java) {
            db.execSQL(
                "UPDATE `products` SET `salePriceMinorUnits`=600," +
                    "`salePriceCurrencyCode`=NULL WHERE " +
                    "`productId`='55555555-5555-4555-8555-555555555555'",
            )
        }
        listOf(
            "`salePriceMinorUnits`=0,`salePriceCurrencyCode`='PEN'",
            "`salePriceMinorUnits`=9007199254740992,`salePriceCurrencyCode`='PEN'",
            "`salePriceMinorUnits`=600,`salePriceCurrencyCode`='pen'",
        ).forEach { invalidAssignment ->
            assertThrows(SQLiteException::class.java) {
                db.execSQL(
                    "UPDATE `products` SET $invalidAssignment WHERE " +
                        "`productId`='55555555-5555-4555-8555-555555555555'",
                )
            }
        }
        db.execSQL(
            "UPDATE `products` SET `salePriceMinorUnits`=600," +
                "`salePriceCurrencyCode`='PEN' WHERE " +
                "`productId`='55555555-5555-4555-8555-555555555555'",
        )
        db.query(
            "SELECT `salePriceMinorUnits`,`salePriceCurrencyCode` FROM `products` WHERE " +
                "`productId`='55555555-5555-4555-8555-555555555555'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(600L, cursor.getLong(0))
            assertEquals("PEN", cursor.getString(1))
        }
        db.query("PRAGMA foreign_key_check").use { cursor -> assertEquals(0, cursor.count) }
        helper.closeWhenFinished(db)
    }
}
