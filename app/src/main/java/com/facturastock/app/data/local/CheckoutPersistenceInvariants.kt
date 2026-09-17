package com.facturastock.app.data.local

import androidx.sqlite.db.SupportSQLiteDatabase

/** Respalda el bloqueo del repositorio frente a escritores SQL accidentales. */
internal fun installCheckoutPersistenceInvariants(db: SupportSQLiteDatabase) {
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS pending_checkout_validate_insert " +
            "BEFORE INSERT ON pending_sale_checkouts WHEN NOT EXISTS (SELECT 1 FROM sales s " +
            "WHERE s.saleId = NEW.saleId AND s.businessId = NEW.businessId AND s.status = 'DRAFT' " +
            "AND s.version = NEW.expectedVersion AND s.contentHash = NEW.contentHash) " +
            "BEGIN SELECT RAISE(ABORT, 'invalid pending checkout'); END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS pending_checkout_block_update BEFORE UPDATE ON pending_sale_checkouts " +
            "BEGIN SELECT RAISE(ABORT, 'pending checkout is immutable'); END",
    )
    for ((operation, row) in listOf("INSERT" to "NEW", "UPDATE" to "OLD", "DELETE" to "OLD")) {
        val parentCondition =
            if (operation == "UPDATE") {
                "p.saleId = OLD.saleId OR p.saleId = NEW.saleId"
            } else {
                "p.saleId = $row.saleId"
            }
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS pending_checkout_block_line_${operation.lowercase()} " +
                "BEFORE $operation ON sale_lines WHEN EXISTS (SELECT 1 FROM pending_sale_checkouts p " +
                "WHERE $parentCondition) " +
                "BEGIN SELECT RAISE(ABORT, 'checkout awaiting confirmation'); END",
        )
    }
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS pending_checkout_block_draft_change " +
            "BEFORE UPDATE ON sales WHEN NEW.status = 'DRAFT' AND EXISTS " +
            "(SELECT 1 FROM pending_sale_checkouts p WHERE p.saleId = OLD.saleId) " +
            "AND (NEW.version != OLD.version OR NEW.contentHash != OLD.contentHash " +
            "OR NEW.totalMinorUnits != OLD.totalMinorUnits OR NEW.currencyCode != OLD.currencyCode " +
            "OR NEW.updatedAt != OLD.updatedAt) " +
            "BEGIN SELECT RAISE(ABORT, 'checkout awaiting confirmation'); END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS pending_checkout_finish_posted AFTER UPDATE OF status ON sales " +
            "WHEN NEW.status = 'POSTED' BEGIN DELETE FROM pending_sale_checkouts " +
            "WHERE saleId = NEW.saleId; END",
    )
}
