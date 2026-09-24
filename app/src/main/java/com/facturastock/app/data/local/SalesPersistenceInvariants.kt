package com.facturastock.app.data.local

import com.facturastock.app.data.local.sqlite.SupportSQLiteDatabase

private val SALES_TRIGGER_NAMES = listOf(
    "sales_block_replace",
    "sales_require_draft_insert",
    "sales_validate_graph_insert",
    "sales_validate_graph_update",
    "sales_validate_update",
    "sales_require_complete_posting",
    "sales_block_posted_update",
    "sales_block_posted_delete",
    "sale_lines_require_draft_insert",
    "sale_lines_validate_graph_insert",
    "sale_lines_validate_graph_update",
    "sale_lines_block_posted_update",
    "sale_lines_block_posted_delete",
)

/**
 * Guards v21 para que ninguna escritura SQL pueda persistir formas inválidas ni publicar un
 * grafo parcial.
 *
 * SQLite comprueba aquí gramática, límites, relaciones estructurales y sumas de enteros. No intenta
 * sustituir la aritmética decimal exacta de Kotlin: [RoomSaleRepository] sigue siendo la autoridad
 * semántica para `BigDecimal`, redondeo, hash del contenido, stock e idempotencia de checkout.
 */
internal fun installSalesPersistenceInvariants(db: SupportSQLiteDatabase) {
    if (!db.hasColumn("sales", "saleId")) return
    if (db.inTransaction()) {
        installSalesPersistenceInvariantsInTransaction(db)
        return
    }
    db.beginTransaction()
    try {
        installSalesPersistenceInvariantsInTransaction(db)
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
}

private fun installSalesPersistenceInvariantsInTransaction(db: SupportSQLiteDatabase) {
    SALES_TRIGGER_NAMES.forEach { db.execSQL("DROP TRIGGER IF EXISTS `$it`") }
    val validSaleShape =
        "typeof(NEW.`status`) = 'text' AND NEW.`status` IN ('DRAFT', 'POSTED') AND " +
            "typeof(NEW.`currencyCode`) = 'text' AND length(NEW.`currencyCode`) = 3 AND " +
            "NEW.`currencyCode` NOT GLOB '*[^A-Z]*' AND " +
            "typeof(NEW.`contentHash`) = 'text' AND length(NEW.`contentHash`) = 64 AND " +
            "NEW.`contentHash` NOT GLOB '*[^0-9a-f]*' AND " +
            "typeof(NEW.`subtotalMinorUnits`) = 'integer' AND NEW.`subtotalMinorUnits` >= 0 AND " +
            "typeof(NEW.`discountMinorUnits`) = 'integer' AND NEW.`discountMinorUnits` >= 0 AND " +
            "NEW.`discountMinorUnits` <= NEW.`subtotalMinorUnits` AND " +
            "typeof(NEW.`taxMinorUnits`) = 'integer' AND NEW.`taxMinorUnits` >= 0 AND " +
            "typeof(NEW.`totalMinorUnits`) = 'integer' AND NEW.`totalMinorUnits` >= 0 AND " +
            "NEW.`totalMinorUnits` = NEW.`subtotalMinorUnits` - NEW.`discountMinorUnits` + " +
            "NEW.`taxMinorUnits` AND typeof(NEW.`version`) = 'integer' AND NEW.`version` >= 0 AND " +
            "typeof(NEW.`createdAt`) = 'integer' AND NEW.`createdAt` >= 0 AND " +
            "typeof(NEW.`updatedAt`) = 'integer' AND NEW.`updatedAt` >= NEW.`createdAt` AND " +
            "(NEW.`postedAt` IS NULL OR (typeof(NEW.`postedAt`) = 'integer' AND " +
            "NEW.`postedAt` BETWEEN NEW.`createdAt` AND NEW.`updatedAt`)) AND " +
            "(NEW.`draftSlot` IS NULL OR (typeof(NEW.`draftSlot`) = 'text' AND " +
            "length(NEW.`draftSlot`) BETWEEN 1 AND 80 AND " +
            "NEW.`draftSlot` = trim(NEW.`draftSlot`))) AND " +
            "(NEW.`checkoutIdempotencyKey` IS NULL OR (" +
            "typeof(NEW.`checkoutIdempotencyKey`) = 'text' AND " +
            "length(NEW.`checkoutIdempotencyKey`) BETWEEN 1 AND 256 AND " +
            "NEW.`checkoutIdempotencyKey` = trim(NEW.`checkoutIdempotencyKey`)))"
    db.execSQL(
        "CREATE TRIGGER `sales_block_replace` BEFORE INSERT ON `sales` WHEN EXISTS (" +
            "SELECT 1 FROM `sales` s WHERE s.`saleId` = NEW.`saleId` OR " +
            "(NEW.`draftSlot` IS NOT NULL AND s.`draftSlot` = NEW.`draftSlot`) OR " +
            "(NEW.`checkoutIdempotencyKey` IS NOT NULL AND " +
            "s.`checkoutIdempotencyKey` = NEW.`checkoutIdempotencyKey`)) " +
            "BEGIN SELECT RAISE(ABORT, 'sales cannot be replaced'); END",
    )
    db.execSQL(
        "CREATE TRIGGER `sales_require_draft_insert` BEFORE INSERT ON `sales` WHEN NOT (" +
            "NEW.`status` = 'DRAFT' AND NEW.`postedAt` IS NULL AND " +
            "NEW.`checkoutIdempotencyKey` IS NULL AND " +
            "NEW.`draftSlot` IS NOT NULL AND " +
            "(NEW.`draftSlot` = NEW.`businessId` || ':' || NEW.`currencyCode` OR " +
            "NEW.`draftSlot` = 'remote:' || NEW.`saleId`) AND " +
            "NEW.`version` = 0 AND NEW.`subtotalMinorUnits` = 0 AND " +
            "NEW.`discountMinorUnits` = 0 AND NEW.`taxMinorUnits` = 0 AND " +
            "NEW.`totalMinorUnits` = 0 AND NEW.`updatedAt` >= NEW.`createdAt`) " +
            "BEGIN SELECT RAISE(ABORT, 'sales must start as an empty draft'); END",
    )
    db.execSQL(
        "CREATE TRIGGER `sales_validate_graph_insert` BEFORE INSERT ON `sales` WHEN NOT (" +
            "($validSaleShape) AND EXISTS (" +
            "SELECT 1 FROM `businesses` b WHERE b.`businessId` = NEW.`businessId`) " +
            ") " +
            "BEGIN SELECT RAISE(ABORT, 'invalid sale ownership'); END",
    )
    db.execSQL(
        "CREATE TRIGGER `sales_validate_graph_update` BEFORE UPDATE ON `sales` WHEN NOT (" +
            "($validSaleShape) AND EXISTS (" +
            "SELECT 1 FROM `businesses` b WHERE b.`businessId` = NEW.`businessId`) " +
            ") " +
            "BEGIN SELECT RAISE(ABORT, 'invalid sale ownership'); END",
    )
    db.execSQL(
        "CREATE TRIGGER `sales_validate_update` BEFORE UPDATE ON `sales` WHEN NOT (" +
            "NEW.`saleId` IS OLD.`saleId` AND NEW.`businessId` IS OLD.`businessId` AND " +
            "NEW.`currencyCode` IS OLD.`currencyCode` AND NEW.`createdAt` IS OLD.`createdAt` AND (" +
            "(OLD.`status` = 'DRAFT' AND NEW.`status` = 'DRAFT' AND " +
            "NEW.`postedAt` IS NULL AND NEW.`checkoutIdempotencyKey` IS NULL AND " +
            "NEW.`draftSlot` IS OLD.`draftSlot` AND NEW.`version` = OLD.`version` + 1 AND " +
            "NEW.`updatedAt` >= OLD.`updatedAt`) OR " +
            "(OLD.`status` = 'DRAFT' AND NEW.`status` = 'POSTED' AND " +
            "NEW.`postedAt` IS NOT NULL AND NEW.`checkoutIdempotencyKey` IS NOT NULL AND " +
            "NEW.`draftSlot` IS NULL AND NEW.`version` = OLD.`version` + 1 AND " +
            "NEW.`updatedAt` = NEW.`postedAt` AND NEW.`postedAt` >= OLD.`updatedAt`))) " +
            "BEGIN SELECT RAISE(ABORT, 'invalid sale transition'); END",
    )
    db.execSQL(
        "CREATE TRIGGER `sales_require_complete_posting` BEFORE UPDATE OF `status` ON `sales` " +
            "WHEN OLD.`status` = 'DRAFT' AND NEW.`status` = 'POSTED' AND (" +
            "NOT EXISTS (SELECT 1 FROM `sale_lines` sl WHERE sl.`saleId` = OLD.`saleId`) OR " +
            "EXISTS (SELECT 1 FROM `sale_lines` sl WHERE sl.`saleId` = OLD.`saleId` AND (" +
            "sl.`unitPriceMinorUnits` IS NULL OR sl.`unitPriceMinorUnits` <= 0 OR " +
            "sl.`lineTotalMinorUnits` IS NULL OR sl.`lineTotalMinorUnits` < 0 OR " +
            "sl.`discountMinorUnits` < 0 OR sl.`taxMinorUnits` < 0 OR " +
            "sl.`currencyCode` != OLD.`currencyCode` OR " +
            "(SELECT COUNT(*) FROM `stock_movements` sm WHERE " +
            "sm.`saleId` = OLD.`saleId` AND sm.`saleLineId` = sl.`saleLineId` AND " +
            "sm.`type` = 'SALE' AND sm.`businessId` = OLD.`businessId` AND " +
            "sm.`productId` = sl.`productId` AND sm.`locationId` = sl.`locationId` AND " +
            "sm.`quantityDelta` = '-' || sl.`quantity` AND sm.`unitCost` IS NOT NULL AND " +
            "sm.`occurredAt` = NEW.`postedAt` AND sm.`createdAt` = NEW.`postedAt`) != 1)) OR " +
            "EXISTS (SELECT 1 FROM `stock_movements` sm WHERE sm.`saleId` = OLD.`saleId` AND (" +
            "sm.`type` != 'SALE' OR sm.`purchaseId` IS NOT NULL OR " +
            "sm.`purchaseLineId` IS NOT NULL OR NOT EXISTS (SELECT 1 FROM `sale_lines` sl " +
            "WHERE sl.`saleId` = OLD.`saleId` AND sl.`saleLineId` = sm.`saleLineId`))) OR " +
            "(SELECT COUNT(*) FROM `stock_movements` sm WHERE sm.`saleId` = OLD.`saleId`) != " +
            "(SELECT COUNT(*) FROM `sale_lines` sl WHERE sl.`saleId` = OLD.`saleId`) OR " +
            "NEW.`subtotalMinorUnits` != (SELECT COALESCE(SUM(" +
            "sl.`lineTotalMinorUnits` + sl.`discountMinorUnits` - sl.`taxMinorUnits`), 0) " +
            "FROM `sale_lines` sl WHERE sl.`saleId` = OLD.`saleId`) OR " +
            "NEW.`discountMinorUnits` != (SELECT COALESCE(SUM(sl.`discountMinorUnits`), 0) " +
            "FROM `sale_lines` sl WHERE sl.`saleId` = OLD.`saleId`) OR " +
            "NEW.`taxMinorUnits` != (SELECT COALESCE(SUM(sl.`taxMinorUnits`), 0) " +
            "FROM `sale_lines` sl WHERE sl.`saleId` = OLD.`saleId`) OR " +
            "NEW.`totalMinorUnits` != (SELECT COALESCE(SUM(sl.`lineTotalMinorUnits`), 0) " +
            "FROM `sale_lines` sl WHERE sl.`saleId` = OLD.`saleId`) OR " +
            "(SELECT MIN(sl.`position`) FROM `sale_lines` sl WHERE sl.`saleId` = OLD.`saleId`) != 0 OR " +
            "(SELECT MAX(sl.`position`) FROM `sale_lines` sl WHERE sl.`saleId` = OLD.`saleId`) != " +
            "(SELECT COUNT(*) - 1 FROM `sale_lines` sl WHERE sl.`saleId` = OLD.`saleId`) OR " +
            "(SELECT COUNT(*) FROM `audit_events` a WHERE a.`businessId` = OLD.`businessId` " +
            "AND a.`purchaseId` IS NULL AND a.`eventType` = 'SALE_POSTED' AND " +
            "a.`entityType` = 'SALE' AND a.`entityId` = OLD.`saleId` AND " +
            "a.`occurredAt` = NEW.`postedAt`) != 1) " +
            "BEGIN SELECT RAISE(ABORT, 'posted sale graph is incomplete'); END",
    )
    db.execSQL(
        "CREATE TRIGGER `sales_block_posted_update` BEFORE UPDATE ON `sales` " +
            "WHEN OLD.`status` = 'POSTED' " +
            "BEGIN SELECT RAISE(ABORT, 'posted sales are immutable'); END",
    )
    db.execSQL(
        "CREATE TRIGGER `sales_block_posted_delete` BEFORE DELETE ON `sales` " +
            "WHEN OLD.`status` = 'POSTED' " +
            "BEGIN SELECT RAISE(ABORT, 'posted sales cannot be deleted'); END",
    )
    db.execSQL(
        "CREATE TRIGGER `sale_lines_require_draft_insert` BEFORE INSERT ON `sale_lines` " +
            "WHEN (SELECT s.`status` FROM `sales` s WHERE s.`saleId` = NEW.`saleId`) != 'DRAFT' " +
            "BEGIN SELECT RAISE(ABORT, 'lines can only be added to draft sales'); END",
    )
    val quantityDigits = "replace(NEW.`quantity`, '.', '')"
    val validQuantityShape =
        "typeof(NEW.`quantity`) = 'text' AND length(NEW.`quantity`) BETWEEN 1 AND 128 AND " +
            "NEW.`quantity` NOT GLOB '*[^0-9.]*' AND " +
            "substr(NEW.`quantity`, 1, 1) GLOB '[0-9]' AND " +
            "substr(NEW.`quantity`, -1, 1) GLOB '[0-9]' AND " +
            "length(NEW.`quantity`) - length(replace(NEW.`quantity`, '.', '')) <= 1 AND " +
            "(instr(NEW.`quantity`, '.') = 0 OR " +
            "length(NEW.`quantity`) - instr(NEW.`quantity`, '.') BETWEEN 1 AND 18) AND " +
            "length(ltrim($quantityDigits, '0')) BETWEEN 1 AND 38"
    val validLineShape =
        "typeof(NEW.`position`) = 'integer' AND NEW.`position` >= 0 AND " +
            validTrimmedText("productNameSnapshot", 200) + " AND " +
            validTrimmedText("unitCodeSnapshot", 16) + " AND " +
            validTrimmedText("locationNameSnapshot", 100) + " AND " +
            "(NEW.`barcodeSnapshot` IS NULL OR (" +
            validTrimmedText("barcodeSnapshot", 128) + ")) AND " +
            "($validQuantityShape) AND " +
            "typeof(NEW.`discountMinorUnits`) = 'integer' AND NEW.`discountMinorUnits` >= 0 AND " +
            "typeof(NEW.`taxMinorUnits`) = 'integer' AND NEW.`taxMinorUnits` >= 0 AND " +
            "((NEW.`unitPriceMinorUnits` IS NULL AND NEW.`lineTotalMinorUnits` IS NULL AND " +
            "NEW.`discountMinorUnits` = 0 AND NEW.`taxMinorUnits` = 0) OR " +
            "(typeof(NEW.`unitPriceMinorUnits`) = 'integer' AND " +
            "NEW.`unitPriceMinorUnits` > 0 AND " +
            "typeof(NEW.`lineTotalMinorUnits`) = 'integer' AND " +
            "NEW.`lineTotalMinorUnits` >= NEW.`taxMinorUnits`))"
    // El producto cantidad × precio y su redondeo no se reproducen con NUMERIC/REAL de SQLite.
    // El trigger limita la representación y coherencia nullable; el repositorio recalcula el total.
    val validLineGraph =
        "EXISTS (SELECT 1 FROM `sales` s JOIN `products` p ON p.`productId` = NEW.`productId` " +
            "JOIN `units` u ON u.`unitId` = NEW.`unitId` JOIN `inventory_locations` l ON " +
            "l.`locationId` = NEW.`locationId` WHERE s.`saleId` = NEW.`saleId` AND " +
            "p.`businessId` = s.`businessId` AND u.`businessId` = s.`businessId` AND " +
            "l.`businessId` = s.`businessId` AND p.`unitId` = u.`unitId` AND " +
            "NEW.`currencyCode` = s.`currencyCode`) AND ($validLineShape)"
    db.execSQL(
        "CREATE TRIGGER `sale_lines_validate_graph_insert` BEFORE INSERT ON `sale_lines` " +
            "WHEN NOT ($validLineGraph) " +
            "BEGIN SELECT RAISE(ABORT, 'invalid sale line graph'); END",
    )
    db.execSQL(
        "CREATE TRIGGER `sale_lines_validate_graph_update` BEFORE UPDATE ON `sale_lines` " +
            "WHEN NOT ($validLineGraph) " +
            "BEGIN SELECT RAISE(ABORT, 'invalid sale line graph'); END",
    )
    db.execSQL(
        "CREATE TRIGGER `sale_lines_block_posted_update` BEFORE UPDATE ON `sale_lines` WHEN " +
            "NEW.`saleId` IS NOT OLD.`saleId` OR (SELECT s.`status` FROM `sales` s WHERE " +
            "s.`saleId` = OLD.`saleId`) != 'DRAFT' " +
            "BEGIN SELECT RAISE(ABORT, 'posted sale lines are immutable'); END",
    )
    db.execSQL(
        "CREATE TRIGGER `sale_lines_block_posted_delete` BEFORE DELETE ON `sale_lines` WHEN " +
            "(SELECT s.`status` FROM `sales` s WHERE s.`saleId` = OLD.`saleId`) != 'DRAFT' " +
            "BEGIN SELECT RAISE(ABORT, 'posted sale lines are immutable'); END",
    )
}

private fun validTrimmedText(column: String, maxLength: Int): String =
    "typeof(NEW.`$column`) = 'text' AND length(NEW.`$column`) BETWEEN 1 AND $maxLength AND " +
        "NEW.`$column` = trim(NEW.`$column`)"

private fun SupportSQLiteDatabase.hasColumn(table: String, column: String): Boolean =
    query("PRAGMA table_info(`$table`)").use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        var found = false
        while (cursor.moveToNext() && !found) found = cursor.getString(nameIndex) == column
        found
    }
