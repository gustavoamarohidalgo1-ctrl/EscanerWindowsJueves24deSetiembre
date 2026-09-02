package com.facturastock.app.data.local

import androidx.sqlite.db.SupportSQLiteDatabase

private val DEBT_TRIGGER_NAMES = listOf(
    "debts_block_replace",
    "debts_require_posted_sale_insert",
    "debts_validate_payment_update",
    "debts_block_delete",
    "debt_payments_block_replace",
    "debt_payments_block_update",
    "debt_payments_block_delete",
    "debt_payments_validate_graph_insert",
)

/** SQL de defensa para la cuenta materializada y el libro append-only de pagos. */
internal fun installDebtPersistenceInvariants(db: SupportSQLiteDatabase) {
    if (!db.debtTableHasColumn("debts", "debtId")) return
    if (db.inTransaction()) {
        installDebtPersistenceInvariantsInTransaction(db)
        return
    }
    db.beginTransaction()
    try {
        installDebtPersistenceInvariantsInTransaction(db)
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
}

private fun installDebtPersistenceInvariantsInTransaction(db: SupportSQLiteDatabase) {
    DEBT_TRIGGER_NAMES.forEach { db.execSQL("DROP TRIGGER IF EXISTS `$it`") }
    val validName =
        "typeof(NEW.`debtorName`) = 'text' AND length(NEW.`debtorName`) BETWEEN 2 AND 120 AND " +
            "NEW.`debtorName` = trim(NEW.`debtorName`) AND " +
            "typeof(NEW.`normalizedDebtorName`) = 'text' AND " +
            "length(NEW.`normalizedDebtorName`) BETWEEN 2 AND 120 AND " +
            "NEW.`normalizedDebtorName` = trim(NEW.`normalizedDebtorName`)"
    val validDebtShape =
        "($validName) AND typeof(NEW.`currencyCode`) = 'text' AND " +
            "length(NEW.`currencyCode`) = 3 AND " +
            "NEW.`currencyCode` NOT GLOB '*[^A-Z]*' AND " +
            "typeof(NEW.`originalAmountMinorUnits`) = 'integer' AND " +
            "NEW.`originalAmountMinorUnits` > 0 AND " +
            "typeof(NEW.`balanceMinorUnits`) = 'integer' AND " +
            "NEW.`balanceMinorUnits` BETWEEN 0 AND NEW.`originalAmountMinorUnits` AND " +
            "typeof(NEW.`version`) = 'integer' AND NEW.`version` >= 1 AND " +
            "typeof(NEW.`createdAt`) = 'integer' AND NEW.`createdAt` >= 0 AND " +
            "typeof(NEW.`updatedAt`) = 'integer' AND NEW.`updatedAt` >= NEW.`createdAt` AND " +
            "(NEW.`dueAt` IS NULL OR (typeof(NEW.`dueAt`) = 'integer' AND NEW.`dueAt` >= 0)) AND " +
            "((NEW.`status` = 'OPEN' AND NEW.`balanceMinorUnits` > 0 AND " +
            "NEW.`paidAt` IS NULL) OR (NEW.`status` = 'PAID' AND " +
            "NEW.`balanceMinorUnits` = 0 AND typeof(NEW.`paidAt`) = 'integer' AND " +
            "NEW.`paidAt` BETWEEN NEW.`createdAt` AND NEW.`updatedAt`))"
    db.execSQL(
        "CREATE TRIGGER `debts_block_replace` BEFORE INSERT ON `debts` WHEN EXISTS (" +
            "SELECT 1 FROM `debts` d WHERE d.`debtId` = NEW.`debtId` OR " +
            "d.`saleId` = NEW.`saleId`) BEGIN SELECT RAISE(ABORT, " +
            "'debts cannot be replaced'); END",
    )
    db.execSQL(
        "CREATE TRIGGER `debts_require_posted_sale_insert` BEFORE INSERT ON `debts` WHEN NOT (" +
            "($validDebtShape) AND NEW.`status` = 'OPEN' AND NEW.`version` = 1 AND " +
            "NEW.`balanceMinorUnits` = NEW.`originalAmountMinorUnits` AND " +
            "NEW.`createdAt` = NEW.`updatedAt` AND EXISTS (SELECT 1 FROM `sales` s WHERE " +
            "s.`saleId` = NEW.`saleId` AND s.`businessId` = NEW.`businessId` AND " +
            "s.`status` = 'POSTED' AND s.`currencyCode` = NEW.`currencyCode` AND " +
            "s.`totalMinorUnits` = NEW.`originalAmountMinorUnits` AND " +
            "s.`postedAt` = NEW.`createdAt`)) BEGIN SELECT RAISE(ABORT, " +
            "'debt requires its exact posted sale'); END",
    )
    db.execSQL(
        "CREATE TRIGGER `debts_validate_payment_update` BEFORE UPDATE ON `debts` WHEN NOT (" +
            "($validDebtShape) AND OLD.`status` = 'OPEN' AND " +
            "NEW.`debtId` IS OLD.`debtId` AND NEW.`businessId` IS OLD.`businessId` AND " +
            "NEW.`saleId` IS OLD.`saleId` AND NEW.`debtorName` IS OLD.`debtorName` AND " +
            "NEW.`normalizedDebtorName` IS OLD.`normalizedDebtorName` AND " +
            "NEW.`currencyCode` IS OLD.`currencyCode` AND " +
            "NEW.`originalAmountMinorUnits` IS OLD.`originalAmountMinorUnits` AND " +
            "NEW.`dueAt` IS OLD.`dueAt` AND NEW.`createdAt` IS OLD.`createdAt` AND " +
            "NEW.`version` = OLD.`version` + 1 AND " +
            "NEW.`balanceMinorUnits` < OLD.`balanceMinorUnits` AND " +
            "NEW.`updatedAt` >= OLD.`updatedAt` AND EXISTS (SELECT 1 FROM `debt_payments` p " +
            "WHERE p.`debtId` = OLD.`debtId` AND p.`businessId` = OLD.`businessId` AND " +
            "p.`currencyCode` = OLD.`currencyCode` AND " +
            "p.`expectedDebtVersion` = OLD.`version` AND " +
            "p.`amountMinorUnits` = OLD.`balanceMinorUnits` - NEW.`balanceMinorUnits` AND " +
            "p.`balanceAfterMinorUnits` = NEW.`balanceMinorUnits` AND " +
            "p.`createdAt` = NEW.`updatedAt`)) BEGIN SELECT RAISE(ABORT, " +
            "'invalid debt payment transition'); END",
    )
    db.execSQL(
        "CREATE TRIGGER `debts_block_delete` BEFORE DELETE ON `debts` BEGIN " +
            "SELECT RAISE(ABORT, 'debts are historical'); END",
    )
    db.execSQL(
        "CREATE TRIGGER `debt_payments_block_replace` BEFORE INSERT ON `debt_payments` " +
            "WHEN EXISTS (SELECT 1 FROM `debt_payments` p WHERE " +
            "p.`paymentId` = NEW.`paymentId` OR p.`idempotencyKey` = NEW.`idempotencyKey` OR " +
            "(p.`debtId` = NEW.`debtId` AND " +
            "p.`expectedDebtVersion` = NEW.`expectedDebtVersion`)) BEGIN " +
            "SELECT RAISE(ABORT, 'debt_payments is append-only'); END",
    )
    db.execSQL(
        "CREATE TRIGGER `debt_payments_block_update` BEFORE UPDATE ON `debt_payments` BEGIN " +
            "SELECT RAISE(ABORT, 'debt_payments is append-only'); END",
    )
    db.execSQL(
        "CREATE TRIGGER `debt_payments_block_delete` BEFORE DELETE ON `debt_payments` BEGIN " +
            "SELECT RAISE(ABORT, 'debt_payments is append-only'); END",
    )
    val validOptionalNote =
        "(NEW.`note` IS NULL OR (typeof(NEW.`note`) = 'text' AND " +
            "length(NEW.`note`) BETWEEN 1 AND 500 AND NEW.`note` = trim(NEW.`note`)))"
    val validOptionalReference =
        "(NEW.`reference` IS NULL OR (typeof(NEW.`reference`) = 'text' AND " +
            "length(NEW.`reference`) BETWEEN 1 AND 120 AND " +
            "NEW.`reference` = trim(NEW.`reference`)))"
    db.execSQL(
        "CREATE TRIGGER `debt_payments_validate_graph_insert` BEFORE INSERT ON " +
            "`debt_payments` WHEN NOT (typeof(NEW.`amountMinorUnits`) = 'integer' AND " +
            "NEW.`amountMinorUnits` > 0 AND NEW.`method` IN " +
            "('CASH','YAPE','PLIN','BANK_TRANSFER','OTHER') AND $validOptionalNote AND " +
            "$validOptionalReference AND typeof(NEW.`expectedDebtVersion`) = 'integer' AND " +
            "NEW.`expectedDebtVersion` >= 1 AND " +
            "typeof(NEW.`balanceAfterMinorUnits`) = 'integer' AND " +
            "NEW.`balanceAfterMinorUnits` >= 0 AND " +
            "NEW.`idempotencyKey` = 'debt-payment:v1:' || NEW.`debtId` || ':' || " +
            "NEW.`paymentId` AND typeof(NEW.`occurredAt`) = 'integer' AND " +
            "NEW.`occurredAt` >= 0 AND typeof(NEW.`createdAt`) = 'integer' AND " +
            "NEW.`createdAt` >= NEW.`occurredAt` AND EXISTS (SELECT 1 FROM `debts` d WHERE " +
            "d.`debtId` = NEW.`debtId` AND d.`businessId` = NEW.`businessId` AND " +
            "d.`status` = 'OPEN' AND d.`currencyCode` = NEW.`currencyCode` AND " +
            "d.`version` = NEW.`expectedDebtVersion` AND " +
            "NEW.`amountMinorUnits` <= d.`balanceMinorUnits` AND " +
            "NEW.`balanceAfterMinorUnits` = d.`balanceMinorUnits` - " +
            "NEW.`amountMinorUnits`)) BEGIN SELECT RAISE(ABORT, " +
            "'invalid debt payment graph'); END",
    )
}

private fun SupportSQLiteDatabase.debtTableHasColumn(table: String, column: String): Boolean =
    query("PRAGMA table_info(`$table`)").use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        var found = false
        while (cursor.moveToNext() && !found) found = cursor.getString(nameIndex) == column
        found
    }
