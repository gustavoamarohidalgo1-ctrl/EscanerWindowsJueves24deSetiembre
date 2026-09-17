package com.facturastock.app.data.local

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * La anulación conserva venta, deuda, cobros y salida originales. El recibo se inserta al final
 * de la transacción, cuando todas las entradas inversas y su auditoría ya existen.
 * Los decimales se comparan como texto exacto; nunca se redondean mediante REAL de SQLite.
 */
internal fun installSaleVoidPersistenceInvariants(db: SupportSQLiteDatabase) {
    val exists = db.query("PRAGMA table_info(`sale_voids`)").use { it.count > 0 }
    if (!exists) return
    if (db.inTransaction()) {
        installSaleVoidPersistenceInvariantsInTransaction(db)
        return
    }
    db.beginTransaction()
    try {
        installSaleVoidPersistenceInvariantsInTransaction(db)
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
}

private fun installSaleVoidPersistenceInvariantsInTransaction(db: SupportSQLiteDatabase) {
    listOf("replace", "update", "delete", "graph_insert").forEach {
        db.execSQL("DROP TRIGGER IF EXISTS `sale_voids_block_$it`")
    }
    db.execSQL(
        "CREATE TRIGGER `sale_voids_block_replace` BEFORE INSERT ON `sale_voids` " +
            "WHEN EXISTS (SELECT 1 FROM `sale_voids` WHERE `saleId` = NEW.`saleId`) " +
            "BEGIN SELECT RAISE(ABORT, 'sale_voids is append-only'); END",
    )
    for (operation in listOf("UPDATE", "DELETE")) {
        db.execSQL(
            "CREATE TRIGGER `sale_voids_block_${operation.lowercase()}` " +
                "BEFORE $operation ON `sale_voids` " +
                "BEGIN SELECT RAISE(ABORT, 'sale_voids is append-only'); END",
        )
    }
    db.execSQL(
        """
        CREATE TRIGGER `sale_voids_block_graph_insert` BEFORE INSERT ON `sale_voids`
        WHEN NOT (
            typeof(NEW.`impactHash`) = 'text' AND length(NEW.`impactHash`) = 64 AND
            NEW.`impactHash` NOT GLOB '*[^0-9a-f]*' AND
            typeof(NEW.`actorId`) = 'text' AND length(NEW.`actorId`) BETWEEN 1 AND 128 AND
            NEW.`actorId` = trim(NEW.`actorId`) AND NEW.`actorRole` IN ('OWNER','MANAGER') AND
            typeof(NEW.`voidedAt`) = 'integer' AND NEW.`voidedAt` >= 0 AND
            typeof(NEW.`refundedAmountMinorUnits`) = 'integer' AND NEW.`refundedAmountMinorUnits` >= 0 AND
            typeof(NEW.`cancelledDebtBalanceMinorUnits`) = 'integer' AND NEW.`cancelledDebtBalanceMinorUnits` >= 0 AND
            EXISTS (SELECT 1 FROM `sales` s WHERE s.`saleId` = NEW.`saleId` AND
                s.`businessId` = NEW.`businessId` AND s.`status` = 'POSTED' AND
                s.`postedAt` IS NOT NULL AND NEW.`voidedAt` >= s.`postedAt` AND
                s.`currencyCode` = NEW.`currencyCode` AND
                NEW.`cancelledDebtBalanceMinorUnits` <= s.`totalMinorUnits` AND
                NEW.`refundedAmountMinorUnits` = s.`totalMinorUnits` - NEW.`cancelledDebtBalanceMinorUnits`) AND
            (SELECT COUNT(*) FROM `debts` d WHERE d.`saleId` = NEW.`saleId`) <= 1 AND
            NOT EXISTS (SELECT 1 FROM `debts` d WHERE d.`saleId` = NEW.`saleId` AND
                (d.`businessId` != NEW.`businessId` OR d.`currencyCode` != NEW.`currencyCode`)) AND
            NEW.`cancelledDebtBalanceMinorUnits` = COALESCE(
                (SELECT d.`balanceMinorUnits` FROM `debts` d WHERE d.`saleId` = NEW.`saleId`), 0) AND
            EXISTS (SELECT 1 FROM `sale_lines` sl WHERE sl.`saleId` = NEW.`saleId`) AND
            NOT EXISTS (SELECT 1 FROM `sale_lines` sl WHERE sl.`saleId` = NEW.`saleId` AND (
                (SELECT COUNT(*) FROM `stock_movements` original WHERE
                    original.`saleId` = NEW.`saleId` AND original.`saleLineId` = sl.`saleLineId` AND
                    original.`type` = 'SALE') != 1 OR
                (SELECT COUNT(*) FROM `stock_movements` returned WHERE
                    returned.`saleId` = NEW.`saleId` AND returned.`saleLineId` = sl.`saleLineId` AND
                    returned.`type` = 'SALE_VOID') != 1 OR
                NOT EXISTS (SELECT 1 FROM `stock_movements` original JOIN `stock_movements` returned
                    ON returned.`saleId` = original.`saleId` AND returned.`saleLineId` = original.`saleLineId`
                    WHERE original.`saleId` = NEW.`saleId` AND original.`saleLineId` = sl.`saleLineId` AND
                    original.`type` = 'SALE' AND returned.`type` = 'SALE_VOID' AND
                    original.`businessId` = NEW.`businessId` AND returned.`businessId` = NEW.`businessId` AND
                    original.`productId` = sl.`productId` AND returned.`productId` = sl.`productId` AND
                    original.`locationId` = sl.`locationId` AND returned.`locationId` = sl.`locationId` AND
                    original.`purchaseId` IS NULL AND original.`purchaseLineId` IS NULL AND
                    returned.`purchaseId` IS NULL AND returned.`purchaseLineId` IS NULL AND
                    original.`quantityDelta` = '-' || sl.`quantity` AND
                    original.`occurredAt` = (SELECT s.`postedAt` FROM `sales` s WHERE s.`saleId` = NEW.`saleId`) AND
                    original.`createdAt` = original.`occurredAt` AND
                    returned.`quantityDelta` = substr(original.`quantityDelta`, 2) AND
                    original.`unitCost` IS NOT NULL AND returned.`unitCost` = original.`unitCost` AND
                    original.`currencyCode` = NEW.`currencyCode` AND returned.`currencyCode` = NEW.`currencyCode` AND
                    returned.`idempotencyKey` = 'sale-void-stock:v1:' || NEW.`saleId` || ':' || sl.`saleLineId` AND
                    returned.`occurredAt` = NEW.`voidedAt` AND returned.`createdAt` = NEW.`voidedAt`) OR
                NOT EXISTS (SELECT 1 FROM `inventory_balances` b WHERE
                    b.`businessId` = NEW.`businessId` AND b.`productId` = sl.`productId` AND
                    b.`locationId` = sl.`locationId` AND b.`currencyCode` = NEW.`currencyCode` AND
                    b.`updatedAt` = NEW.`voidedAt`)
            )) AND
            (SELECT COUNT(*) FROM `stock_movements` m WHERE m.`saleId` = NEW.`saleId`) =
                2 * (SELECT COUNT(*) FROM `sale_lines` sl WHERE sl.`saleId` = NEW.`saleId`) AND
            (SELECT COUNT(*) FROM `audit_events` a WHERE a.`entityType` = 'SALE' AND
                a.`entityId` = NEW.`saleId` AND a.`eventType` = 'SALE_POSTED') = 1 AND
            EXISTS (SELECT 1 FROM `audit_events` a JOIN `sales` s ON s.`saleId` = a.`entityId` WHERE
                a.`entityType` = 'SALE' AND a.`entityId` = NEW.`saleId` AND a.`eventType` = 'SALE_POSTED' AND
                a.`businessId` = NEW.`businessId` AND a.`purchaseId` IS NULL AND a.`occurredAt` = s.`postedAt`) AND
            (SELECT COUNT(*) FROM `audit_events` a WHERE a.`entityType` = 'SALE' AND
                a.`entityId` = NEW.`saleId` AND a.`eventType` = 'SALE_VOIDED') = 1 AND
            EXISTS (SELECT 1 FROM `audit_events` a WHERE a.`entityType` = 'SALE' AND
                a.`entityId` = NEW.`saleId` AND a.`eventType` = 'SALE_VOIDED' AND
                a.`businessId` = NEW.`businessId` AND a.`purchaseId` IS NULL AND
                a.`occurredAt` = NEW.`voidedAt`)
        )
        BEGIN SELECT RAISE(ABORT, 'incomplete sale void graph'); END
        """.trimIndent(),
    )
}

/** Only used when schema 29's receipt table exists, including during historical upgrades. */
internal fun saleVoidMovementOriginSql(): String =
    """
    OR (NEW.`type` = 'SALE_VOID' AND NEW.`purchaseId` IS NULL AND NEW.`purchaseLineId` IS NULL AND
        NEW.`saleId` IS NOT NULL AND NEW.`saleLineId` IS NOT NULL AND NEW.`unitCost` IS NOT NULL AND
        NEW.`idempotencyKey` = 'sale-void-stock:v1:' || NEW.`saleId` || ':' || NEW.`saleLineId` AND
        NOT EXISTS (SELECT 1 FROM `sale_voids` v WHERE v.`saleId` = NEW.`saleId`) AND
        NOT EXISTS (SELECT 1 FROM `stock_movements` prior WHERE prior.`saleId` = NEW.`saleId` AND
            prior.`saleLineId` = NEW.`saleLineId` AND prior.`type` = 'SALE_VOID') AND
        EXISTS (SELECT 1 FROM `sales` s JOIN `sale_lines` sl ON sl.`saleId` = s.`saleId`
            JOIN `stock_movements` original ON original.`saleId` = s.`saleId` AND
                original.`saleLineId` = sl.`saleLineId` AND original.`type` = 'SALE'
            WHERE s.`saleId` = NEW.`saleId` AND sl.`saleLineId` = NEW.`saleLineId` AND
            s.`businessId` = NEW.`businessId` AND s.`status` = 'POSTED' AND s.`postedAt` IS NOT NULL AND
            NEW.`occurredAt` >= s.`postedAt` AND NEW.`createdAt` = NEW.`occurredAt` AND
            NEW.`occurredAt` >= original.`occurredAt` AND NEW.`occurredAt` >= original.`createdAt` AND
            sl.`productId` = NEW.`productId` AND sl.`locationId` = NEW.`locationId` AND
            original.`businessId` = NEW.`businessId` AND original.`productId` = NEW.`productId` AND
            original.`locationId` = NEW.`locationId` AND original.`purchaseId` IS NULL AND
            original.`purchaseLineId` IS NULL AND original.`quantityDelta` = '-' || sl.`quantity` AND
            NEW.`quantityDelta` = substr(original.`quantityDelta`, 2) AND
            NEW.`unitCost` = original.`unitCost` AND NEW.`currencyCode` = original.`currencyCode` AND
            NEW.`currencyCode` = s.`currencyCode`))
    """.trimIndent()

internal fun saleVoidAuditOriginSql(): String =
    """
    (NEW.`purchaseId` IS NULL AND NEW.`eventType` = 'SALE_VOIDED' AND NEW.`entityType` = 'SALE' AND
        EXISTS (SELECT 1 FROM `sales` s WHERE s.`saleId` = NEW.`entityId` AND
            s.`businessId` = NEW.`businessId` AND s.`status` = 'POSTED' AND
            s.`postedAt` IS NOT NULL AND NEW.`occurredAt` >= s.`postedAt`) AND
        NOT EXISTS (SELECT 1 FROM `sale_voids` v WHERE v.`saleId` = NEW.`entityId`) AND
        NOT EXISTS (SELECT 1 FROM `audit_events` a WHERE a.`entityType` = 'SALE' AND
            a.`entityId` = NEW.`entityId` AND a.`eventType` = 'SALE_VOIDED')) OR
    """.trimIndent()
