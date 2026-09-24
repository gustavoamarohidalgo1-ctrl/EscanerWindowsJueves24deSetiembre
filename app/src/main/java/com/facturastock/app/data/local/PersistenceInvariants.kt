package com.facturastock.app.data.local

import androidx.room.RoomDatabase
import com.facturastock.app.data.local.sqlite.SupportSQLiteDatabase
import androidx.sqlite.SQLiteConnection

private val PERSISTENCE_TRIGGER_NAMES = listOf(
    "catalog_suppliers_block_referenced_delete",
    "catalog_products_block_referenced_delete",
    "catalog_products_validate_barcode_insert",
    "catalog_products_validate_barcode_update",
    "catalog_products_validate_sale_price_insert",
    "catalog_products_validate_sale_price_update",
    "catalog_products_validate_graph_insert",
    "catalog_products_validate_graph_update",
    "catalog_aliases_validate_graph_insert",
    "catalog_aliases_validate_graph_update",
    "invoice_drafts_validate_catalog_graph_insert",
    "invoice_drafts_validate_catalog_graph_update",
    "invoice_images_validate_graph_insert",
    "invoice_images_validate_graph_update",
    "captured_page_publications_validate_graph_insert",
    "captured_page_publications_validate_graph_update",
    "invoice_lines_validate_graph_insert",
    "invoice_lines_validate_graph_update",
    "catalog_units_block_referenced_delete",
    "catalog_locations_block_referenced_delete",
    "catalog_products_block_stock_unit_update",
    "catalog_units_block_referenced_code_update",
    "stock_movements_block_replace",
    "stock_movements_block_update",
    "stock_movements_block_delete",
    "stock_movements_validate_graph_insert",
    "audit_events_block_replace",
    "audit_events_block_update",
    "audit_events_block_delete",
    "audit_events_validate_graph_insert",
    "purchases_block_replace",
    "purchases_validate_document_identity_insert",
    "purchases_validate_document_identity_update",
    "purchases_require_draft_insert",
    "purchases_validate_graph_insert",
    "purchases_validate_graph_update",
    "purchases_validate_status_transition",
    "purchases_require_complete_posting",
    "purchases_require_complete_void",
    "purchases_block_posted_identity_update",
    "purchases_block_linked_delete",
    "purchases_block_posted_delete",
    "purchase_lines_require_draft_insert",
    "purchase_lines_require_cloud_snapshot_insert",
    "purchase_lines_validate_graph_insert",
    "purchase_lines_validate_graph_update",
    "purchase_lines_block_posted_update",
    "purchase_lines_block_posted_delete",
    "inventory_balances_validate_graph_insert",
    "inventory_balances_validate_graph_update",
    "inventory_balances_require_next_version",
    "outbox_operations_validate_graph_insert",
    "outbox_operations_validate_graph_update",
    "prepared_purchases_block_confirmed_replace",
    "prepared_purchases_block_confirmed_update",
    "prepared_purchases_block_confirmed_delete",
    "invoice_drafts_validate_purchase_insert",
    "invoice_drafts_validate_purchase_link",
    "invoice_drafts_block_purchase_relink",
    "invoice_drafts_block_committed_insert",
    "invoice_drafts_validate_committed_transition",
    "invoice_drafts_block_committed_update",
)

/**
 * Invariantes que SQLite no puede expresar mediante las anotaciones de Room. Se instalan tanto
 * al crear una base nueva como al migrar para que un acceso SQL accidental no pueda reescribir
 * el libro de movimientos ni el historial de auditoría.
 */
internal fun installPostingPersistenceInvariants(db: SupportSQLiteDatabase) {
    if (db.inTransaction()) {
        installPostingPersistenceInvariantsInTransaction(db)
        return
    }
    db.beginTransaction()
    try {
        installPostingPersistenceInvariantsInTransaction(db)
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
}

private val CLOUD_BINDING_TRIGGER_NAMES = listOf(
    "cloud_business_bindings_block_update",
    "cloud_business_bindings_block_direct_delete",
    "outbox_operations_validate_cloud_target_insert",
    "outbox_operations_fill_cloud_target_insert",
    "outbox_operations_validate_cloud_target_update",
)

/** Guards v20: el vínculo es inmutable y toda operación nueva hereda ese destino en SQLite. */
internal fun installCloudBusinessBindingInvariants(db: SupportSQLiteDatabase) {
    if (!db.tableHasColumn("outbox_operations", "targetCloudBusinessId")) return
    if (db.inTransaction()) {
        installCloudBusinessBindingInvariantsInTransaction(db)
        return
    }
    db.beginTransaction()
    try {
        installCloudBusinessBindingInvariantsInTransaction(db)
        db.setTransactionSuccessful()
    } finally {
        db.endTransaction()
    }
}

private fun installCloudBusinessBindingInvariantsInTransaction(db: SupportSQLiteDatabase) {
    CLOUD_BINDING_TRIGGER_NAMES.forEach { db.execSQL("DROP TRIGGER IF EXISTS `$it`") }
    db.execSQL(
        "CREATE TRIGGER `cloud_business_bindings_block_update` BEFORE UPDATE ON " +
            "`cloud_business_bindings` BEGIN SELECT RAISE(ABORT, " +
            "'cloud business binding is immutable'); END",
    )
    // Permite únicamente la cascada posterior a eliminar el negocio local completo.
    db.execSQL(
        "CREATE TRIGGER `cloud_business_bindings_block_direct_delete` BEFORE DELETE ON " +
            "`cloud_business_bindings` WHEN EXISTS (SELECT 1 FROM `businesses` b WHERE " +
            "b.`businessId` = OLD.`localBusinessId`) BEGIN SELECT RAISE(ABORT, " +
            "'cloud business binding is immutable'); END",
    )
    db.execSQL(
        "CREATE TRIGGER `outbox_operations_validate_cloud_target_insert` BEFORE INSERT ON " +
            "`outbox_operations` WHEN NEW.`targetCloudBusinessId` IS NOT NULL AND NOT EXISTS (" +
            "SELECT 1 FROM `cloud_business_bindings` cb WHERE " +
            "cb.`localBusinessId` = NEW.`businessId` AND " +
            "cb.`cloudBusinessId` = NEW.`targetCloudBusinessId`) BEGIN SELECT RAISE(ABORT, " +
            "'outbox cloud target does not match immutable binding'); END",
    )
    db.execSQL(
        "CREATE TRIGGER `outbox_operations_fill_cloud_target_insert` AFTER INSERT ON " +
            "`outbox_operations` WHEN NEW.`targetCloudBusinessId` IS NULL AND EXISTS (" +
            "SELECT 1 FROM `cloud_business_bindings` cb WHERE " +
            "cb.`localBusinessId` = NEW.`businessId`) BEGIN UPDATE `outbox_operations` SET " +
            "`targetCloudBusinessId` = (SELECT cb.`cloudBusinessId` FROM " +
            "`cloud_business_bindings` cb WHERE cb.`localBusinessId` = NEW.`businessId`) " +
            "WHERE `operationId` = NEW.`operationId`; END",
    )
    db.execSQL(
        "CREATE TRIGGER `outbox_operations_validate_cloud_target_update` BEFORE UPDATE OF " +
            "`targetCloudBusinessId` ON `outbox_operations` WHEN " +
            "(OLD.`targetCloudBusinessId` IS NOT NULL AND " +
            "NEW.`targetCloudBusinessId` IS NOT OLD.`targetCloudBusinessId`) OR " +
            "(NEW.`targetCloudBusinessId` IS NOT NULL AND NOT EXISTS (SELECT 1 FROM " +
            "`cloud_business_bindings` cb WHERE cb.`localBusinessId` = NEW.`businessId` AND " +
            "cb.`cloudBusinessId` = NEW.`targetCloudBusinessId`)) BEGIN SELECT RAISE(ABORT, " +
            "'outbox cloud target is immutable'); END",
    )
}

private fun installPostingPersistenceInvariantsInTransaction(db: SupportSQLiteDatabase) {
    // Los cuerpos pueden endurecerse sin subir el esquema de tablas. Recrearlos hace que onOpen
    // repare también una base de desarrollo v11 que hubiese instalado una revisión anterior.
    PERSISTENCE_TRIGGER_NAMES.forEach { trigger ->
        db.execSQL("DROP TRIGGER IF EXISTS `$trigger`")
    }
    // MIGRATION_10_11 instala estos mismos guards antes de que v13 añada auditoría de costo.
    // La rama antigua permite completar el salto incremental; MIGRATION_12_13 los reinstala
    // inmediatamente con la condición estricta.
    val costingPostingGuard = if (db.tableHasColumn("purchase_lines", "appliedUnitCost")) {
        "pl.`appliedUnitCost` IS NULL OR pl.`purchaseUnitFactor` IS NULL OR " +
            "pl.`inventoryQuantity` IS NULL OR pl.`discount` IS NULL OR " +
            "pl.`taxTreatment` IS NULL OR " +
            "pl.`taxTreatment` NOT IN ('INCLUDED','EXCLUDED','EXEMPT') OR " +
            "pl.`costPolicy` IS NULL OR pl.`costPolicy` NOT IN ('NET','GROSS') OR " +
            "pl.`appliedCostTotal` IS NULL OR " +
            "pl.`taxEvidenceType` IS NULL OR pl.`roundingScale` IS NULL OR " +
            "pl.`roundingScale` < 0 OR pl.`roundingScale` > 18 OR " +
            "pl.`roundingMode` IS NULL OR " +
            "pl.`roundingMode` NOT IN ('UP','DOWN','CEILING','FLOOR','HALF_UP'," +
            "'HALF_DOWN','HALF_EVEN','UNNECESSARY') OR " +
            "pl.`costingWarnings` IS NULL OR " +
            "pl.`taxEvidenceType` NOT IN ('NONE','EXPLICIT_AMOUNT','EXPLICIT_RATE') OR " +
            "(pl.`taxEvidenceType` = 'NONE' AND pl.`taxEvidenceValue` IS NOT NULL) OR " +
            "(pl.`taxEvidenceType` != 'NONE' AND pl.`taxEvidenceValue` IS NULL) OR "
    } else {
        ""
    }
    val productProvenancePostingGuard = if (
        db.tableHasColumn("purchase_lines", "productProvenance")
    ) {
        "pl.`productProvenance` NOT IN " +
            "('EXISTING','CREATED_IN_DRAFT') OR "
    } else {
        ""
    }
    val hasDocumentIdentitySlot = db.tableHasColumn("purchases", "documentIdentitySlot")
    val hasSales =
        db.tableHasColumn("sales", "saleId") &&
            db.tableHasColumn("stock_movements", "saleId")
    val hasSaleVoids = hasSales && db.tableHasColumn("sale_voids", "saleId")
    val saleProductDeleteGuard = if (hasSales) {
        "EXISTS (SELECT 1 FROM `sale_lines` sl WHERE " +
            "sl.`productId` = OLD.`productId`) OR "
    } else {
        ""
    }
    val saleUnitDeleteGuard = if (hasSales) {
        "EXISTS (SELECT 1 FROM `sale_lines` sl WHERE sl.`unitId` = OLD.`unitId`) OR "
    } else {
        ""
    }
    val saleLocationDeleteGuard = if (hasSales) {
        "EXISTS (SELECT 1 FROM `sale_lines` sl WHERE " +
            "sl.`locationId` = OLD.`locationId`) OR "
    } else {
        ""
    }
    val salePostedAuditGraph = if (hasSales) {
        "(NEW.`purchaseId` IS NULL AND NEW.`eventType` = 'SALE_POSTED' AND " +
            "NEW.`entityType` = 'SALE' AND EXISTS (SELECT 1 FROM `sales` sale WHERE " +
            "sale.`saleId` = NEW.`entityId` AND sale.`businessId` = NEW.`businessId` AND " +
            "sale.`status` = 'DRAFT')) OR "
    } else {
        ""
    }
    val saleVoidedAuditGraph = if (hasSaleVoids) saleVoidAuditOriginSql() else ""
    val documentIdentitySlotMatch = if (hasDocumentIdentitySlot) {
        " AND `documentIdentitySlot` = NEW.`documentIdentitySlot`"
    } else {
        ""
    }
    val purchaseDuplicateOverrideAuditGuard = if (hasDocumentIdentitySlot) {
        "(NEW.`eventType` = 'PURCHASE_DUPLICATE_OVERRIDE' AND " +
            "p.`status` = 'DRAFT' AND p.`duplicateOverrideOfPurchaseId` IS NOT NULL) OR "
    } else {
        ""
    }
    val duplicateOverridePostingGuard = if (hasDocumentIdentitySlot) {
        "(OLD.`duplicateOverrideOfPurchaseId` IS NOT NULL AND NOT EXISTS (" +
            "SELECT 1 FROM `audit_events` a WHERE " +
            "a.`purchaseId` = OLD.`purchaseId` AND a.`businessId` = OLD.`businessId` AND " +
            "a.`eventType` = 'PURCHASE_DUPLICATE_OVERRIDE' AND " +
            "a.`entityType` = 'PURCHASE' AND a.`entityId` = OLD.`purchaseId` AND " +
            "a.`occurredAt` = NEW.`postedAt`)) OR "
    } else {
        ""
    }
    // Los IDs son UUID globales y sus FKs simples aseguran existencia, pero no que todas las
    // referencias de un agregado pertenezcan al mismo negocio. Estos guards equivalen a FKs
    // compuestas sin reconstruir tablas históricas ni duplicar claves UNIQUE de los padres.
    val validProductGraph =
        "EXISTS (SELECT 1 FROM `units` u WHERE u.`unitId` = NEW.`unitId` AND " +
            "u.`businessId` = NEW.`businessId`) AND " +
            "(NEW.`purchaseUnitId` IS NULL OR EXISTS (SELECT 1 FROM `units` pu WHERE " +
            "pu.`unitId` = NEW.`purchaseUnitId` AND pu.`businessId` = NEW.`businessId`)) AND " +
            "(NEW.`locationId` IS NULL OR EXISTS (SELECT 1 FROM `inventory_locations` l WHERE " +
            "l.`locationId` = NEW.`locationId` AND l.`businessId` = NEW.`businessId`))"
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `catalog_products_validate_graph_insert` " +
            "BEFORE INSERT ON `products` WHEN NOT ($validProductGraph) " +
            "BEGIN SELECT RAISE(ABORT, 'invalid product tenant graph'); END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `catalog_products_validate_graph_update` " +
            "BEFORE UPDATE ON `products` WHEN (EXISTS (SELECT 1 FROM `businesses` b WHERE " +
            "b.`businessId` IN (OLD.`businessId`, NEW.`businessId`))) " +
            "AND NOT ($validProductGraph) " +
            "BEGIN SELECT RAISE(ABORT, 'invalid product tenant graph'); END",
    )
    val validAliasGraph =
        "EXISTS (SELECT 1 FROM `suppliers` s WHERE s.`supplierId` = NEW.`supplierId` AND " +
            "s.`businessId` = NEW.`businessId`) AND EXISTS (SELECT 1 FROM `products` p WHERE " +
            "p.`productId` = NEW.`productId` AND p.`businessId` = NEW.`businessId`)"
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `catalog_aliases_validate_graph_insert` " +
            "BEFORE INSERT ON `supplier_product_aliases` WHEN NOT ($validAliasGraph) " +
            "BEGIN SELECT RAISE(ABORT, 'invalid alias tenant graph'); END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `catalog_aliases_validate_graph_update` " +
            "BEFORE UPDATE ON `supplier_product_aliases` WHEN (EXISTS (SELECT 1 FROM " +
            "`businesses` b WHERE b.`businessId` IN (OLD.`businessId`, NEW.`businessId`))) " +
            "AND NOT ($validAliasGraph) " +
            "BEGIN SELECT RAISE(ABORT, 'invalid alias tenant graph'); END",
    )
    val validDraftCatalogGraph =
        "NEW.`supplierId` IS NULL OR EXISTS (SELECT 1 FROM `suppliers` s WHERE " +
            "s.`supplierId` = NEW.`supplierId` AND s.`businessId` = NEW.`businessId`)"
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `invoice_drafts_validate_catalog_graph_insert` " +
            "BEFORE INSERT ON `invoice_drafts` WHEN NOT ($validDraftCatalogGraph) " +
            "BEGIN SELECT RAISE(ABORT, 'invalid draft tenant graph'); END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `invoice_drafts_validate_catalog_graph_update` " +
            "BEFORE UPDATE ON `invoice_drafts` WHEN (EXISTS (SELECT 1 FROM `businesses` b " +
            "WHERE b.`businessId` IN (OLD.`businessId`, NEW.`businessId`))) " +
            "AND NOT ($validDraftCatalogGraph) " +
            "BEGIN SELECT RAISE(ABORT, 'invalid draft tenant graph'); END",
    )
    val validImageGraph =
        "EXISTS (SELECT 1 FROM `invoice_drafts` d WHERE d.`draftId` = NEW.`draftId` AND " +
            "d.`businessId` = NEW.`businessId`)"
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `invoice_images_validate_graph_insert` " +
            "BEFORE INSERT ON `invoice_images` WHEN NOT ($validImageGraph) " +
            "BEGIN SELECT RAISE(ABORT, 'invalid invoice image tenant graph'); END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `invoice_images_validate_graph_update` " +
            "BEFORE UPDATE ON `invoice_images` WHEN (EXISTS (SELECT 1 FROM `businesses` b " +
            "WHERE b.`businessId` IN (OLD.`businessId`, NEW.`businessId`))) " +
            "AND NOT ($validImageGraph) " +
            "BEGIN SELECT RAISE(ABORT, 'invalid invoice image tenant graph'); END",
    )
    if (db.tableHasColumn("captured_page_publications", "businessId")) {
        val validCapturedPagePublicationGraph =
            "EXISTS (SELECT 1 FROM `invoice_drafts` d WHERE d.`draftId` = NEW.`draftId` AND " +
                "d.`businessId` = NEW.`businessId`)"
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS `captured_page_publications_validate_graph_insert` " +
                "BEFORE INSERT ON `captured_page_publications` " +
                "WHEN NOT ($validCapturedPagePublicationGraph) " +
                "BEGIN SELECT RAISE(ABORT, " +
                "'invalid captured page publication tenant graph'); END",
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS `captured_page_publications_validate_graph_update` " +
                "BEFORE UPDATE ON `captured_page_publications` WHEN " +
                "(EXISTS (SELECT 1 FROM `businesses` b WHERE " +
                "b.`businessId` IN (OLD.`businessId`, NEW.`businessId`))) AND " +
                "NOT ($validCapturedPagePublicationGraph) " +
                "BEGIN SELECT RAISE(ABORT, " +
                "'invalid captured page publication tenant graph'); END",
        )
    }
    val validInvoiceLineGraph =
        "EXISTS (SELECT 1 FROM `invoice_drafts` d WHERE d.`draftId` = NEW.`draftId` AND " +
            "d.`businessId` = NEW.`businessId`) AND " +
            "(NEW.`productId` IS NULL OR EXISTS (SELECT 1 FROM `products` p WHERE " +
            "p.`productId` = NEW.`productId` AND p.`businessId` = NEW.`businessId`)) AND " +
            "(NEW.`unitId` IS NULL OR EXISTS (SELECT 1 FROM `units` u WHERE " +
            "u.`unitId` = NEW.`unitId` AND u.`businessId` = NEW.`businessId`))"
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `invoice_lines_validate_graph_insert` " +
            "BEFORE INSERT ON `invoice_lines` WHEN NOT ($validInvoiceLineGraph) " +
            "BEGIN SELECT RAISE(ABORT, 'invalid invoice line tenant graph'); END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `invoice_lines_validate_graph_update` " +
            "BEFORE UPDATE ON `invoice_lines` WHEN (EXISTS (SELECT 1 FROM `businesses` b " +
            "WHERE b.`businessId` IN (OLD.`businessId`, NEW.`businessId`))) " +
            "AND NOT ($validInvoiceLineGraph) " +
            "BEGIN SELECT RAISE(ABORT, 'invalid invoice line tenant graph'); END",
    )
    // Los cuatro catálogos productivos se retiran mediante status=ARCHIVED. Estos guards evitan
    // que SQL de mantenimiento borre una fila referenciada y active los SET_NULL/CASCADE legados.
    // La existencia del negocio preserva la cascada intencional al eliminar un negocio completo:
    // SQLite borra primero la fila padre y después ejecuta sus acciones de FK.
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `catalog_suppliers_block_referenced_delete` " +
            "BEFORE DELETE ON `suppliers` WHEN " +
            "EXISTS (SELECT 1 FROM `businesses` b WHERE b.`businessId` = OLD.`businessId`) AND (" +
            "EXISTS (SELECT 1 FROM `invoice_drafts` d WHERE d.`supplierId` = OLD.`supplierId`) OR " +
            "EXISTS (SELECT 1 FROM `supplier_product_aliases` a WHERE " +
            "a.`supplierId` = OLD.`supplierId`) OR " +
            "EXISTS (SELECT 1 FROM `purchases` p WHERE p.`supplierId` = OLD.`supplierId`)) " +
            "BEGIN SELECT RAISE(ABORT, 'referenced supplier must be archived'); END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `catalog_products_block_referenced_delete` " +
            "BEFORE DELETE ON `products` WHEN " +
            "EXISTS (SELECT 1 FROM `businesses` b WHERE b.`businessId` = OLD.`businessId`) AND (" +
            "EXISTS (SELECT 1 FROM `invoice_lines` l WHERE l.`productId` = OLD.`productId`) OR " +
            "EXISTS (SELECT 1 FROM `supplier_product_aliases` a WHERE " +
            "a.`productId` = OLD.`productId`) OR " +
            "EXISTS (SELECT 1 FROM `purchase_lines` pl WHERE pl.`productId` = OLD.`productId`) OR " +
            saleProductDeleteGuard +
            "EXISTS (SELECT 1 FROM `inventory_balances` ib WHERE " +
            "ib.`productId` = OLD.`productId`) OR " +
            "EXISTS (SELECT 1 FROM `stock_movements` sm WHERE sm.`productId` = OLD.`productId`)) " +
            "BEGIN SELECT RAISE(ABORT, 'referenced product must be archived'); END",
    )
    // Las filas Unicode históricas permanecen legibles. Un UPDATE que conserva exactamente el
    // valor antiguo también puede editar otro campo; cualquier INSERT o sustitución de barcode
    // debe cumplir el contrato nuevo: ASCII imprimible, 1..128 y sin espacios ASCII de borde.
    val invalidNewBarcode =
        "NEW.`barcode` IS NOT NULL AND (length(NEW.`barcode`) NOT BETWEEN 1 AND 128 OR " +
            "NEW.`barcode` != trim(NEW.`barcode`, ' ') OR " +
            "NEW.`barcode` GLOB '*[^ -~]*')"
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `catalog_products_validate_barcode_insert` " +
            "BEFORE INSERT ON `products` WHEN $invalidNewBarcode " +
            "BEGIN SELECT RAISE(ABORT, 'product barcode must be printable ASCII'); END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `catalog_products_validate_barcode_update` " +
            "BEFORE UPDATE OF `barcode` ON `products` " +
            "WHEN NEW.`barcode` IS NOT OLD.`barcode` AND $invalidNewBarcode " +
            "BEGIN SELECT RAISE(ABORT, 'product barcode must be printable ASCII'); END",
    )
    if (
        db.tableHasColumn("products", "salePriceMinorUnits") &&
        db.tableHasColumn("products", "salePriceCurrencyCode")
    ) {
        val invalidSalePrice =
            "NOT ((NEW.`salePriceMinorUnits` IS NULL AND " +
                "NEW.`salePriceCurrencyCode` IS NULL) OR (" +
                "typeof(NEW.`salePriceMinorUnits`) = 'integer' AND " +
                "NEW.`salePriceMinorUnits` BETWEEN 1 AND " +
                "${com.facturastock.app.domain.model.ProductSalePricePolicy.MAX_MINOR_UNITS} AND " +
                "typeof(NEW.`salePriceCurrencyCode`) = 'text' AND " +
                "length(NEW.`salePriceCurrencyCode`) = 3 AND " +
                "NEW.`salePriceCurrencyCode` GLOB '[A-Z][A-Z][A-Z]'))"
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS `catalog_products_validate_sale_price_insert` " +
                "BEFORE INSERT ON `products` WHEN $invalidSalePrice " +
                "BEGIN SELECT RAISE(ABORT, 'invalid product sale price'); END",
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS `catalog_products_validate_sale_price_update` " +
                "BEFORE UPDATE OF `salePriceMinorUnits`, `salePriceCurrencyCode` ON `products` " +
                "WHEN $invalidSalePrice " +
                "BEGIN SELECT RAISE(ABORT, 'invalid product sale price'); END",
        )
    }
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `catalog_units_block_referenced_delete` " +
            "BEFORE DELETE ON `units` WHEN " +
            "EXISTS (SELECT 1 FROM `businesses` b WHERE b.`businessId` = OLD.`businessId`) AND (" +
            "EXISTS (SELECT 1 FROM `products` p WHERE p.`unitId` = OLD.`unitId` OR " +
            "p.`purchaseUnitId` = OLD.`unitId`) OR " +
            "EXISTS (SELECT 1 FROM `invoice_lines` l WHERE l.`unitId` = OLD.`unitId`) OR " +
            saleUnitDeleteGuard +
            "EXISTS (SELECT 1 FROM `purchase_lines` pl WHERE pl.`unitId` = OLD.`unitId`)) " +
            "BEGIN SELECT RAISE(ABORT, 'referenced unit must be archived'); END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `catalog_locations_block_referenced_delete` " +
            "BEFORE DELETE ON `inventory_locations` WHEN " +
            "EXISTS (SELECT 1 FROM `businesses` b WHERE b.`businessId` = OLD.`businessId`) AND (" +
            "EXISTS (SELECT 1 FROM `products` p WHERE p.`locationId` = OLD.`locationId`) OR " +
            saleLocationDeleteGuard +
            "EXISTS (SELECT 1 FROM `inventory_balances` ib WHERE " +
            "ib.`locationId` = OLD.`locationId`) OR " +
            "EXISTS (SELECT 1 FROM `stock_movements` sm WHERE sm.`locationId` = OLD.`locationId`)) " +
            "BEGIN SELECT RAISE(ABORT, 'referenced location must be archived'); END",
    )
    // Cantidades historicas no congelan la unidad en cada fila. Una vez que existe saldo o
    // movimiento, cambiar product.unitId reinterpretaria todo el libro sin una conversion.
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `catalog_products_block_stock_unit_update` " +
            "BEFORE UPDATE OF `unitId` ON `products` " +
            "WHEN NEW.`unitId` IS NOT OLD.`unitId` AND (" +
            "EXISTS (SELECT 1 FROM `inventory_balances` b WHERE " +
            "b.`businessId` = OLD.`businessId` AND b.`productId` = OLD.`productId`) OR " +
            "EXISTS (SELECT 1 FROM `stock_movements` m WHERE " +
            "m.`businessId` = OLD.`businessId` AND m.`productId` = OLD.`productId`)) " +
            "BEGIN SELECT RAISE(ABORT, 'inventory unit is immutable after stock history'); END",
    )
    // El codigo es la identidad semantica que se muestra para lineas y movimientos historicos.
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `catalog_units_block_referenced_code_update` " +
            "BEFORE UPDATE OF `code` ON `units` " +
            "WHEN NEW.`code` IS NOT OLD.`code` AND (" +
            "EXISTS (SELECT 1 FROM `purchase_lines` pl WHERE pl.`unitId` = OLD.`unitId`) OR " +
            "EXISTS (SELECT 1 FROM `products` p JOIN `inventory_balances` b " +
            "ON b.`businessId` = p.`businessId` AND b.`productId` = p.`productId` " +
            "WHERE p.`unitId` = OLD.`unitId`) OR " +
            "EXISTS (SELECT 1 FROM `products` p JOIN `stock_movements` m " +
            "ON m.`businessId` = p.`businessId` AND m.`productId` = p.`productId` " +
            "WHERE p.`unitId` = OLD.`unitId`)) " +
            "BEGIN SELECT RAISE(ABORT, 'referenced unit code is immutable'); END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `stock_movements_block_replace` " +
            "BEFORE INSERT ON `stock_movements` WHEN EXISTS (" +
            "SELECT 1 FROM `stock_movements` WHERE " +
            "`movementId` = NEW.`movementId` OR `idempotencyKey` = NEW.`idempotencyKey`) " +
            "BEGIN SELECT RAISE(ABORT, 'stock_movements is append-only'); END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `stock_movements_block_update` " +
            "BEFORE UPDATE ON `stock_movements` BEGIN " +
            "SELECT RAISE(ABORT, 'stock_movements is append-only'); END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `stock_movements_block_delete` " +
            "BEFORE DELETE ON `stock_movements` BEGIN " +
            "SELECT RAISE(ABORT, 'stock_movements is append-only'); END",
    )
    val adjustmentOrigin =
        "(NEW.`type` = 'ADJUSTMENT' AND NEW.`purchaseId` IS NULL AND " +
            "NEW.`purchaseLineId` IS NULL" +
            if (hasSales) " AND NEW.`saleId` IS NULL AND NEW.`saleLineId` IS NULL)" else ")"
    val purchaseOrigin =
        "(NEW.`type` IN ('PURCHASE', 'VOID') AND NEW.`purchaseId` IS NOT NULL AND " +
            "NEW.`purchaseLineId` IS NOT NULL AND NEW.`unitCost` IS NOT NULL " +
            (if (hasSales) "AND NEW.`saleId` IS NULL AND NEW.`saleLineId` IS NULL " else "") +
            "AND EXISTS (SELECT 1 FROM `purchases` p JOIN `purchase_lines` pl " +
            "ON pl.`purchaseId` = p.`purchaseId` WHERE p.`purchaseId` = NEW.`purchaseId` AND " +
            "pl.`purchaseLineId` = NEW.`purchaseLineId` AND p.`businessId` = NEW.`businessId` AND " +
            "pl.`productId` = NEW.`productId` AND p.`currencyCode` = NEW.`currencyCode` AND " +
            "((NEW.`type` = 'PURCHASE' AND p.`status` = 'DRAFT' AND EXISTS (SELECT 1 FROM " +
            "`invoice_drafts` d WHERE d.`draftId` = p.`sourceDraftId` AND " +
            "d.`confirmedPurchaseId` = p.`purchaseId`)) OR " +
            "(NEW.`type` = 'VOID' AND p.`status` = 'POSTED'))))"
    val saleOrigin = if (hasSales) {
        " OR (NEW.`type` = 'SALE' AND NEW.`purchaseId` IS NULL AND " +
            "NEW.`purchaseLineId` IS NULL AND NEW.`saleId` IS NOT NULL AND " +
            "NEW.`saleLineId` IS NOT NULL AND NEW.`unitCost` IS NOT NULL AND " +
            "SUBSTR(NEW.`quantityDelta`, 1, 1) = '-' AND EXISTS (SELECT 1 FROM `sales` s " +
            "JOIN `sale_lines` sl ON sl.`saleId` = s.`saleId` WHERE " +
            "s.`saleId` = NEW.`saleId` AND sl.`saleLineId` = NEW.`saleLineId` AND " +
            "s.`businessId` = NEW.`businessId` AND s.`status` = 'DRAFT' AND " +
            "sl.`productId` = NEW.`productId` AND sl.`locationId` = NEW.`locationId`) AND " +
            "EXISTS (SELECT 1 FROM `inventory_balances` b WHERE " +
            "b.`businessId` = NEW.`businessId` AND b.`productId` = NEW.`productId` AND " +
            "b.`locationId` = NEW.`locationId` AND b.`currencyCode` = NEW.`currencyCode` AND " +
            "b.`updatedAt` = NEW.`occurredAt`))"
    } else {
        ""
    }
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `stock_movements_validate_graph_insert` " +
            "BEFORE INSERT ON `stock_movements` WHEN NOT (" +
            "EXISTS (SELECT 1 FROM `products` p JOIN `inventory_locations` l " +
            "ON l.`locationId` = NEW.`locationId` WHERE " +
            "p.`productId` = NEW.`productId` AND p.`businessId` = NEW.`businessId` AND " +
            "l.`businessId` = NEW.`businessId`) AND (" +
            adjustmentOrigin + " OR " + purchaseOrigin + saleOrigin +
            (if (hasSaleVoids) saleVoidMovementOriginSql() else "") + ")) " +
            "BEGIN SELECT RAISE(ABORT, 'invalid stock movement graph'); END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `audit_events_block_replace` " +
            "BEFORE INSERT ON `audit_events` WHEN EXISTS (" +
            "SELECT 1 FROM `audit_events` WHERE `auditEventId` = NEW.`auditEventId`) " +
            "BEGIN SELECT RAISE(ABORT, 'audit_events is append-only'); END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `audit_events_block_update` " +
            "BEFORE UPDATE ON `audit_events` BEGIN " +
            "SELECT RAISE(ABORT, 'audit_events is append-only'); END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `audit_events_block_delete` " +
            "BEFORE DELETE ON `audit_events` BEGIN " +
            "SELECT RAISE(ABORT, 'audit_events is append-only'); END",
    )
    // Los eventos de sincronización documentan decisiones humanas, no escrituras del libro: la
    // resolución de un CONFLICT apunta a una compra ya terminal; la resolución de catálogo
    // apunta a un producto/proveedor real del mismo negocio; y la revisión de reconciliación
    // apunta al negocio completo (entityId = businessId, sin compra).
    db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS `audit_events_validate_graph_insert` " +
            "BEFORE INSERT ON `audit_events` WHEN NOT (" +
            salePostedAuditGraph + saleVoidedAuditGraph +
            "(NEW.`purchaseId` IS NULL AND NEW.`eventType` = 'STOCK_ADJUSTED') OR " +
            "(NEW.`purchaseId` IS NULL AND NEW.`eventType` = 'SYNC_RECONCILED' AND " +
            "NEW.`entityType` = 'business' AND NEW.`entityId` = NEW.`businessId`) OR " +
            "(NEW.`purchaseId` IS NULL AND " +
            "NEW.`eventType` = 'CATALOG_SYNC_CONFLICT_RESOLVED' AND ((" +
            "NEW.`entityType` = 'PRODUCT' AND EXISTS (SELECT 1 FROM `products` product " +
            "WHERE product.`productId` = NEW.`entityId` AND " +
            "product.`businessId` = NEW.`businessId`)) OR (" +
            "NEW.`entityType` = 'SUPPLIER' AND EXISTS (SELECT 1 FROM `suppliers` supplier " +
            "WHERE supplier.`supplierId` = NEW.`entityId` AND " +
            "supplier.`businessId` = NEW.`businessId`)))) OR " +
            "(NEW.`purchaseId` IS NOT NULL AND EXISTS (" +
            "SELECT 1 FROM `purchases` p WHERE p.`purchaseId` = NEW.`purchaseId` AND " +
            "p.`businessId` = NEW.`businessId` AND NEW.`entityId` = p.`purchaseId` AND (" +
            "(NEW.`entityType` = 'PURCHASE' AND (" +
            "(NEW.`eventType` = 'PURCHASE_POSTED' AND p.`status` = 'DRAFT') OR " +
            purchaseDuplicateOverrideAuditGuard +
            "(NEW.`eventType` = 'PURCHASE_VOIDED' AND p.`status` = 'POSTED'))) OR " +
            "(NEW.`entityType` = 'purchase' AND " +
            "NEW.`eventType` = 'SYNC_CONFLICT_RESOLVED' AND " +
            "p.`status` IN ('POSTED','VOIDED')))))) " +
            "BEGIN SELECT RAISE(ABORT, 'invalid audit event graph'); END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `purchases_block_replace` " +
            "BEFORE INSERT ON `purchases` WHEN EXISTS (" +
            "SELECT 1 FROM `purchases` WHERE `purchaseId` = NEW.`purchaseId` OR " +
            "`sourceDraftId` = NEW.`sourceDraftId` OR " +
            "`idempotencyKey` = NEW.`idempotencyKey` OR (" +
            "`businessId` = NEW.`businessId` AND `supplierId` = NEW.`supplierId` AND " +
            "`documentType` = NEW.`documentType` AND " +
            "`documentSeries` = NEW.`documentSeries` AND " +
            "`documentNumber` = NEW.`documentNumber`" + documentIdentitySlotMatch + ")) " +
            "BEGIN SELECT RAISE(ABORT, 'purchases cannot be replaced'); END",
    )
    if (hasDocumentIdentitySlot) {
        val validDocumentIdentity =
            "((NEW.`documentIdentitySlot` = 'PRIMARY' AND " +
                "NEW.`duplicateOverrideOfPurchaseId` IS NULL AND " +
                "NEW.`duplicateOverrideReason` IS NULL AND " +
                "NEW.`duplicateOverrideActorId` IS NULL AND " +
                "NEW.`duplicateOverrideRole` IS NULL) OR (" +
                "NEW.`documentIdentitySlot` != 'PRIMARY' AND " +
                "NEW.`documentIdentitySlot` = NEW.`sourceDraftId` AND " +
                "NEW.`duplicateOverrideOfPurchaseId` IS NOT NULL AND " +
                "NEW.`duplicateOverrideOfPurchaseId` != NEW.`purchaseId` AND " +
                "NEW.`duplicateOverrideReason` IS NOT NULL AND " +
                "NEW.`duplicateOverrideReason` = TRIM(NEW.`duplicateOverrideReason`) AND " +
                "LENGTH(NEW.`duplicateOverrideReason`) BETWEEN 10 AND 500 AND " +
                "NEW.`duplicateOverrideActorId` IS NOT NULL AND " +
                "LENGTH(NEW.`duplicateOverrideActorId`) BETWEEN 1 AND 128 AND " +
                "LENGTH(TRIM(NEW.`duplicateOverrideActorId`)) >= 1 AND " +
                "NEW.`duplicateOverrideRole` IS NOT NULL AND " +
                "NEW.`duplicateOverrideRole` IN ('OWNER','MANAGER') AND " +
                "EXISTS (SELECT 1 FROM `purchases` target WHERE " +
                "target.`purchaseId` = NEW.`duplicateOverrideOfPurchaseId` AND " +
                "target.`status` IN ('POSTED','VOIDED') AND " +
                "target.`businessId` = NEW.`businessId` AND " +
                "(target.`supplierId` = NEW.`supplierId` OR EXISTS (" +
                "SELECT 1 FROM `invoice_drafts` sourceDraft " +
                "JOIN `invoice_drafts` targetDraft ON " +
                "targetDraft.`draftId` = target.`sourceDraftId` WHERE " +
                "sourceDraft.`draftId` = NEW.`sourceDraftId` AND " +
                "sourceDraft.`supplierRucNormalized` IS NOT NULL AND " +
                "sourceDraft.`supplierRucNormalized` = " +
                "targetDraft.`supplierRucNormalized`)) AND " +
                "target.`documentType` = NEW.`documentType` AND " +
                "target.`documentSeries` = NEW.`documentSeries` AND " +
                "target.`documentNumber` = NEW.`documentNumber`)))"
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS `purchases_validate_document_identity_insert` " +
                "BEFORE INSERT ON `purchases` WHEN NOT $validDocumentIdentity " +
                "BEGIN SELECT RAISE(ABORT, 'invalid purchase document identity'); END",
        )
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS `purchases_validate_document_identity_update` " +
                "BEFORE UPDATE ON `purchases` WHEN NOT $validDocumentIdentity " +
                "BEGIN SELECT RAISE(ABORT, 'invalid purchase document identity'); END",
        )
    }
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `purchases_require_draft_insert` " +
            "BEFORE INSERT ON `purchases` WHEN " +
            "NEW.`status` != 'DRAFT' OR NEW.`postedAt` IS NOT NULL OR " +
            "NEW.`voidedAt` IS NOT NULL OR NEW.`updatedAt` < NEW.`createdAt` " +
            "BEGIN SELECT RAISE(ABORT, 'purchases must be inserted as DRAFT'); END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `purchases_validate_graph_insert` " +
            "BEFORE INSERT ON `purchases` WHEN NOT (" +
            "EXISTS (SELECT 1 FROM `invoice_drafts` d WHERE " +
            "d.`draftId` = NEW.`sourceDraftId` AND d.`businessId` = NEW.`businessId`) AND " +
            "EXISTS (SELECT 1 FROM `suppliers` s WHERE " +
            "s.`supplierId` = NEW.`supplierId` AND s.`businessId` = NEW.`businessId`)) " +
            "BEGIN SELECT RAISE(ABORT, 'invalid purchase ownership'); END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `purchases_validate_graph_update` " +
            "BEFORE UPDATE ON `purchases` WHEN NOT (" +
            "EXISTS (SELECT 1 FROM `invoice_drafts` d WHERE " +
            "d.`draftId` = NEW.`sourceDraftId` AND d.`businessId` = NEW.`businessId`) AND " +
            "EXISTS (SELECT 1 FROM `suppliers` s WHERE " +
            "s.`supplierId` = NEW.`supplierId` AND s.`businessId` = NEW.`businessId`)) " +
            "BEGIN SELECT RAISE(ABORT, 'invalid purchase ownership'); END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `purchases_validate_status_transition` " +
            "BEFORE UPDATE ON `purchases` " +
            "WHEN NOT (" +
            "(OLD.`status` = 'DRAFT' AND NEW.`status` = 'DRAFT' AND " +
            "NEW.`postedAt` IS NULL AND NEW.`voidedAt` IS NULL AND " +
            "NEW.`updatedAt` >= OLD.`updatedAt`) OR " +
            "(OLD.`status` = 'DRAFT' AND NEW.`status` = 'POSTED' AND " +
            "NEW.`postedAt` IS NOT NULL AND NEW.`voidedAt` IS NULL AND " +
            "NEW.`postedAt` >= NEW.`createdAt` AND NEW.`updatedAt` >= NEW.`postedAt` AND " +
            "NEW.`updatedAt` >= OLD.`updatedAt`) OR " +
            "(OLD.`status` = 'POSTED' AND NEW.`status` = 'POSTED' AND " +
            "NEW.`postedAt` IS OLD.`postedAt` AND NEW.`voidedAt` IS NULL AND " +
            "NEW.`updatedAt` >= OLD.`updatedAt`) OR " +
            "(OLD.`status` = 'POSTED' AND NEW.`status` = 'VOIDED' AND " +
            "NEW.`postedAt` IS OLD.`postedAt` AND NEW.`voidedAt` IS NOT NULL AND " +
            "NEW.`voidedAt` >= NEW.`postedAt` AND NEW.`updatedAt` >= NEW.`voidedAt` AND " +
            "NEW.`updatedAt` >= OLD.`updatedAt`) OR " +
            "(OLD.`status` = 'VOIDED' AND NEW.`status` = 'VOIDED' AND " +
            "NEW.`postedAt` IS OLD.`postedAt` AND NEW.`voidedAt` IS OLD.`voidedAt` AND " +
            "NEW.`updatedAt` >= OLD.`updatedAt`)) " +
            "BEGIN SELECT RAISE(ABORT, 'invalid purchase status transition'); END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `purchases_require_complete_posting` " +
            "BEFORE UPDATE OF `status` ON `purchases` " +
            "WHEN OLD.`status` = 'DRAFT' AND NEW.`status` = 'POSTED' AND (" +
            "NOT EXISTS (SELECT 1 FROM `invoice_drafts` d " +
            "JOIN `prepared_purchases` pp ON pp.`draftId` = d.`draftId` WHERE " +
            "d.`draftId` = OLD.`sourceDraftId` AND d.`businessId` = OLD.`businessId` AND " +
            "d.`status` = 'READY_TO_POST' AND " +
            "d.`confirmedPurchaseId` = OLD.`purchaseId`) OR " +
            "NOT EXISTS (SELECT 1 FROM `purchase_lines` pl WHERE " +
            "pl.`purchaseId` = OLD.`purchaseId`) OR " +
            "EXISTS (SELECT 1 FROM `purchase_lines` pl WHERE " +
            "pl.`purchaseId` = OLD.`purchaseId` AND (" +
            "pl.`currencyCode` != OLD.`currencyCode` OR " +
            costingPostingGuard + productProvenancePostingGuard +
            "NOT EXISTS (" +
            "SELECT 1 FROM `stock_movements` sm WHERE " +
            "sm.`purchaseId` = OLD.`purchaseId` AND " +
            "sm.`purchaseLineId` = pl.`purchaseLineId` AND " +
            "sm.`productId` = pl.`productId` AND sm.`type` = 'PURCHASE'))) OR " +
            "EXISTS (SELECT 1 FROM `stock_movements` sm WHERE " +
            "sm.`purchaseId` = OLD.`purchaseId` AND (" +
            "sm.`businessId` != OLD.`businessId` OR sm.`type` != 'PURCHASE' OR " +
            "sm.`currencyCode` != OLD.`currencyCode` OR " +
            "sm.`occurredAt` != NEW.`postedAt` OR sm.`createdAt` != NEW.`postedAt` OR " +
            "NOT EXISTS (SELECT 1 FROM `purchase_lines` pl WHERE " +
            "pl.`purchaseId` = OLD.`purchaseId` AND " +
            "pl.`purchaseLineId` = sm.`purchaseLineId` AND " +
            "pl.`productId` = sm.`productId`))) OR " +
            "EXISTS (SELECT 1 FROM `stock_movements` sm WHERE " +
            "sm.`purchaseId` = OLD.`purchaseId` AND sm.`type` = 'PURCHASE' AND " +
            "NOT EXISTS (SELECT 1 FROM `inventory_balances` b WHERE " +
            "b.`businessId` = OLD.`businessId` AND b.`productId` = sm.`productId` AND " +
            "b.`locationId` = sm.`locationId` AND " +
            "b.`currencyCode` = OLD.`currencyCode` AND b.`updatedAt` = NEW.`postedAt`)) OR " +
            "NOT EXISTS (SELECT 1 FROM `audit_events` a WHERE " +
            "a.`purchaseId` = OLD.`purchaseId` AND a.`businessId` = OLD.`businessId` AND " +
            "a.`eventType` = 'PURCHASE_POSTED' AND a.`entityType` = 'PURCHASE' AND " +
            "a.`entityId` = OLD.`purchaseId` AND a.`occurredAt` = NEW.`postedAt`) OR " +
            duplicateOverridePostingGuard +
            "EXISTS (SELECT 1 FROM `audit_events` a WHERE " +
            "a.`purchaseId` = OLD.`purchaseId` AND (" +
            "a.`businessId` != OLD.`businessId` OR " +
            "a.`eventType` NOT IN ('PURCHASE_POSTED','PURCHASE_DUPLICATE_OVERRIDE') OR " +
            "a.`entityType` != 'PURCHASE' OR a.`entityId` != OLD.`purchaseId` OR " +
            "a.`occurredAt` != NEW.`postedAt`)) OR " +
            "NOT EXISTS (SELECT 1 FROM `outbox_operations` o WHERE " +
            "o.`purchaseId` = OLD.`purchaseId` AND o.`businessId` = OLD.`businessId` AND " +
            "o.`status` = 'PENDING' AND o.`operationType` = 'SYNC_PURCHASE' AND " +
            "o.`createdAt` = NEW.`postedAt` AND o.`updatedAt` = NEW.`postedAt`) OR " +
            "EXISTS (SELECT 1 FROM `outbox_operations` o WHERE " +
            "o.`purchaseId` = OLD.`purchaseId` AND (" +
            "o.`businessId` != OLD.`businessId` OR o.`status` != 'PENDING' OR " +
            "o.`operationType` NOT IN ('SYNC_PURCHASE','SYNC_DOCUMENT_UPLOAD') OR " +
            "o.`createdAt` != NEW.`postedAt` OR o.`updatedAt` != NEW.`postedAt`))) " +
            "BEGIN SELECT RAISE(ABORT, 'posted purchase graph is incomplete'); END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `purchases_require_complete_void` " +
            "BEFORE UPDATE OF `status` ON `purchases` " +
            "WHEN OLD.`status` = 'POSTED' AND NEW.`status` = 'VOIDED' AND (" +
            "NOT EXISTS (SELECT 1 FROM `stock_movements` WHERE " +
            "`purchaseId` = OLD.`purchaseId` AND `type` = 'VOID') OR " +
            // Cardinalidad 1:1 por línea y opuesto textual exacto. No usar CAST/SUM NUMERIC:
            // SQLite aproxima decimales grandes y podría aceptar una reversa distinta.
            "EXISTS (SELECT 1 FROM `purchase_lines` pl WHERE " +
            "pl.`purchaseId` = OLD.`purchaseId` AND (" +
            "(SELECT COUNT(*) FROM `stock_movements` p WHERE " +
            "p.`purchaseId` = OLD.`purchaseId` AND " +
            "p.`purchaseLineId` = pl.`purchaseLineId` AND p.`type` = 'PURCHASE') != 1 OR " +
            "(SELECT COUNT(*) FROM `stock_movements` v WHERE " +
            "v.`purchaseId` = OLD.`purchaseId` AND " +
            "v.`purchaseLineId` = pl.`purchaseLineId` AND v.`type` = 'VOID') != 1 OR " +
            "NOT EXISTS (SELECT 1 FROM `stock_movements` p " +
            "JOIN `stock_movements` v ON " +
            "v.`purchaseId` = p.`purchaseId` AND " +
            "v.`purchaseLineId` = p.`purchaseLineId` AND " +
            "v.`productId` = p.`productId` AND v.`locationId` = p.`locationId` AND " +
            "v.`unitCost` = p.`unitCost` AND v.`currencyCode` = p.`currencyCode` AND " +
            "v.`quantityDelta` = CASE " +
            "WHEN SUBSTR(p.`quantityDelta`, 1, 1) = '-' " +
            "THEN SUBSTR(p.`quantityDelta`, 2) ELSE '-' || p.`quantityDelta` END " +
            "WHERE p.`purchaseId` = OLD.`purchaseId` AND " +
            "p.`purchaseLineId` = pl.`purchaseLineId` AND p.`type` = 'PURCHASE' AND " +
            "v.`type` = 'VOID'))) OR " +
            "EXISTS (SELECT 1 FROM `stock_movements` sm WHERE " +
            "sm.`purchaseId` = OLD.`purchaseId` AND sm.`type` = 'VOID' AND " +
            "NOT EXISTS (SELECT 1 FROM `purchase_lines` pl WHERE " +
            "pl.`purchaseId` = OLD.`purchaseId` AND " +
            "pl.`purchaseLineId` = sm.`purchaseLineId`)) OR " +
            "EXISTS (SELECT 1 FROM `stock_movements` sm WHERE " +
            "sm.`purchaseId` = OLD.`purchaseId` AND sm.`type` = 'VOID' AND (" +
            "sm.`businessId` != OLD.`businessId` OR " +
            "sm.`currencyCode` != OLD.`currencyCode` OR " +
            "sm.`occurredAt` != NEW.`voidedAt` OR sm.`createdAt` != NEW.`voidedAt`)) OR " +
            "EXISTS (SELECT 1 FROM `stock_movements` sm WHERE " +
            "sm.`purchaseId` = OLD.`purchaseId` AND sm.`type` = 'VOID' AND " +
            "NOT EXISTS (SELECT 1 FROM `inventory_balances` b WHERE " +
            "b.`businessId` = OLD.`businessId` AND b.`productId` = sm.`productId` AND " +
            "b.`locationId` = sm.`locationId` AND " +
            "b.`currencyCode` = OLD.`currencyCode` AND b.`updatedAt` = NEW.`voidedAt`)) OR " +
            "NOT EXISTS (SELECT 1 FROM `audit_events` a WHERE " +
            "a.`purchaseId` = OLD.`purchaseId` AND a.`businessId` = OLD.`businessId` AND " +
            "a.`eventType` = 'PURCHASE_VOIDED' AND a.`entityType` = 'PURCHASE' AND " +
            "a.`entityId` = OLD.`purchaseId` AND a.`occurredAt` = NEW.`voidedAt`) OR " +
            "NOT EXISTS (SELECT 1 FROM `outbox_operations` o WHERE " +
            "o.`purchaseId` = OLD.`purchaseId` AND o.`businessId` = OLD.`businessId` AND " +
            "o.`status` = 'PENDING' AND o.`operationType` = 'SYNC_PURCHASE_VOID' AND " +
            "o.`createdAt` = NEW.`voidedAt` AND o.`updatedAt` = NEW.`voidedAt`)) " +
            "BEGIN SELECT RAISE(ABORT, 'voided purchase graph is incomplete'); END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `purchases_block_posted_identity_update` " +
            "BEFORE UPDATE ON `purchases` " +
            "WHEN (OLD.`status` != 'DRAFT' OR NEW.`status` != 'DRAFT') AND (" +
            "NEW.`purchaseId` IS NOT OLD.`purchaseId` OR " +
            "NEW.`businessId` IS NOT OLD.`businessId` OR " +
            "NEW.`sourceDraftId` IS NOT OLD.`sourceDraftId` OR " +
            "NEW.`supplierId` IS NOT OLD.`supplierId` OR " +
            "NEW.`documentType` IS NOT OLD.`documentType` OR " +
            "NEW.`documentSeries` IS NOT OLD.`documentSeries` OR " +
            "NEW.`documentNumber` IS NOT OLD.`documentNumber` OR " +
            (if (hasDocumentIdentitySlot) {
                "NEW.`documentIdentitySlot` IS NOT OLD.`documentIdentitySlot` OR " +
                    "NEW.`duplicateOverrideOfPurchaseId` IS NOT " +
                    "OLD.`duplicateOverrideOfPurchaseId` OR " +
                    "NEW.`duplicateOverrideReason` IS NOT OLD.`duplicateOverrideReason` OR " +
                    "NEW.`duplicateOverrideActorId` IS NOT OLD.`duplicateOverrideActorId` OR " +
                    "NEW.`duplicateOverrideRole` IS NOT OLD.`duplicateOverrideRole` OR "
            } else {
                ""
            }) +
            "NEW.`issueDate` IS NOT OLD.`issueDate` OR " +
            "NEW.`currencyCode` IS NOT OLD.`currencyCode` OR " +
            "NEW.`subtotalMinorUnits` IS NOT OLD.`subtotalMinorUnits` OR " +
            "NEW.`taxMinorUnits` IS NOT OLD.`taxMinorUnits` OR " +
            "NEW.`otherChargesMinorUnits` IS NOT OLD.`otherChargesMinorUnits` OR " +
            "NEW.`totalMinorUnits` IS NOT OLD.`totalMinorUnits` OR " +
            "NEW.`idempotencyKey` IS NOT OLD.`idempotencyKey` OR " +
            "NEW.`createdAt` IS NOT OLD.`createdAt`) " +
            "BEGIN SELECT RAISE(ABORT, 'posted purchase identity is immutable'); END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `purchases_block_linked_delete` " +
            "BEFORE DELETE ON `purchases` WHEN EXISTS (" +
            "SELECT 1 FROM `invoice_drafts` WHERE `confirmedPurchaseId` = OLD.`purchaseId`) " +
            "BEGIN SELECT RAISE(ABORT, 'linked purchases cannot be deleted'); END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `purchases_block_posted_delete` " +
            "BEFORE DELETE ON `purchases` WHEN OLD.`status` != 'DRAFT' " +
            "BEGIN SELECT RAISE(ABORT, 'posted purchases cannot be deleted'); END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `purchase_lines_require_draft_insert` " +
            "BEFORE INSERT ON `purchase_lines` " +
            "WHEN (SELECT `status` FROM `purchases` " +
            "WHERE `purchaseId` = NEW.`purchaseId`) != 'DRAFT' " +
            "BEGIN SELECT RAISE(ABORT, 'lines can only be added to draft purchases'); END",
    )
    if (db.tableHasColumn("purchase_lines", "productNameSnapshot")) {
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS `purchase_lines_require_cloud_snapshot_insert` " +
                "BEFORE INSERT ON `purchase_lines` WHEN " +
                "NEW.`productNameSnapshot` IS NULL OR " +
                "length(trim(NEW.`productNameSnapshot`)) = 0 OR " +
                "length(NEW.`productNameSnapshot`) > 200 OR " +
                "NEW.`unitCodeSnapshot` IS NULL OR " +
                "length(trim(NEW.`unitCodeSnapshot`)) = 0 OR " +
                "length(NEW.`unitCodeSnapshot`) > 16 " +
                "BEGIN SELECT RAISE(ABORT, 'purchase line cloud snapshot is required'); END",
        )
    }
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `purchase_lines_validate_graph_insert` " +
            "BEFORE INSERT ON `purchase_lines` WHEN NOT EXISTS (" +
            "SELECT 1 FROM `purchases` p JOIN `products` pr ON " +
            "pr.`productId` = NEW.`productId` JOIN `units` u ON " +
            "u.`unitId` = NEW.`unitId` WHERE p.`purchaseId` = NEW.`purchaseId` AND " +
            "pr.`businessId` = p.`businessId` AND u.`businessId` = p.`businessId` AND " +
            "(pr.`unitId` = u.`unitId` OR pr.`purchaseUnitId` = u.`unitId`)) " +
            "BEGIN SELECT RAISE(ABORT, 'invalid purchase line graph'); END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `purchase_lines_validate_graph_update` " +
            "BEFORE UPDATE ON `purchase_lines` WHEN NOT EXISTS (" +
            "SELECT 1 FROM `purchases` p JOIN `products` pr ON " +
            "pr.`productId` = NEW.`productId` JOIN `units` u ON " +
            "u.`unitId` = NEW.`unitId` WHERE p.`purchaseId` = NEW.`purchaseId` AND " +
            "pr.`businessId` = p.`businessId` AND u.`businessId` = p.`businessId` AND " +
            "(pr.`unitId` = u.`unitId` OR pr.`purchaseUnitId` = u.`unitId`)) " +
            "BEGIN SELECT RAISE(ABORT, 'invalid purchase line graph'); END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `purchase_lines_block_posted_update` " +
            "BEFORE UPDATE ON `purchase_lines` " +
            "WHEN NEW.`purchaseId` IS NOT OLD.`purchaseId` OR " +
            "(SELECT `status` FROM `purchases` " +
            "WHERE `purchaseId` = OLD.`purchaseId`) != 'DRAFT' " +
            "BEGIN SELECT RAISE(ABORT, 'posted purchase lines are immutable'); END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `purchase_lines_block_posted_delete` " +
            "BEFORE DELETE ON `purchase_lines` " +
            "WHEN (SELECT `status` FROM `purchases` " +
            "WHERE `purchaseId` = OLD.`purchaseId`) != 'DRAFT' " +
            "BEGIN SELECT RAISE(ABORT, 'posted purchase lines are immutable'); END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `inventory_balances_validate_graph_insert` " +
            "BEFORE INSERT ON `inventory_balances` WHEN NOT EXISTS (" +
            "SELECT 1 FROM `products` p JOIN `inventory_locations` l ON " +
            "l.`locationId` = NEW.`locationId` WHERE " +
            "p.`productId` = NEW.`productId` AND p.`businessId` = NEW.`businessId` AND " +
            "l.`businessId` = NEW.`businessId`) " +
            "BEGIN SELECT RAISE(ABORT, 'invalid inventory balance graph'); END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `inventory_balances_validate_graph_update` " +
            "BEFORE UPDATE ON `inventory_balances` WHEN NOT EXISTS (" +
            "SELECT 1 FROM `products` p JOIN `inventory_locations` l ON " +
            "l.`locationId` = NEW.`locationId` WHERE " +
            "p.`productId` = NEW.`productId` AND p.`businessId` = NEW.`businessId` AND " +
            "l.`businessId` = NEW.`businessId`) " +
            "BEGIN SELECT RAISE(ABORT, 'invalid inventory balance graph'); END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `inventory_balances_require_next_version` " +
            "BEFORE UPDATE ON `inventory_balances` " +
            "WHEN NEW.`version` != OLD.`version` + 1 " +
            "BEGIN SELECT RAISE(ABORT, 'inventory balance version must advance by one'); END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `outbox_operations_validate_graph_insert` " +
            "BEFORE INSERT ON `outbox_operations` WHEN " +
            "NEW.`purchaseId` IS NOT NULL AND NOT EXISTS (" +
            "SELECT 1 FROM `purchases` p WHERE p.`purchaseId` = NEW.`purchaseId` AND " +
            "p.`businessId` = NEW.`businessId`) " +
            "BEGIN SELECT RAISE(ABORT, 'invalid outbox operation graph'); END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `outbox_operations_validate_graph_update` " +
            "BEFORE UPDATE ON `outbox_operations` WHEN " +
            "NEW.`purchaseId` IS NOT NULL AND NOT EXISTS (" +
            "SELECT 1 FROM `purchases` p WHERE p.`purchaseId` = NEW.`purchaseId` AND " +
            "p.`businessId` = NEW.`businessId`) " +
            "BEGIN SELECT RAISE(ABORT, 'invalid outbox operation graph'); END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `prepared_purchases_block_confirmed_replace` " +
            "BEFORE INSERT ON `prepared_purchases` WHEN EXISTS (" +
            "SELECT 1 FROM `invoice_drafts` WHERE `draftId` = NEW.`draftId` AND " +
            "`confirmedPurchaseId` IS NOT NULL) " +
            "BEGIN SELECT RAISE(ABORT, 'confirmed purchase snapshot is immutable'); END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `prepared_purchases_block_confirmed_delete` " +
            "BEFORE DELETE ON `prepared_purchases` WHEN EXISTS (" +
            "SELECT 1 FROM `invoice_drafts` WHERE `draftId` = OLD.`draftId` AND " +
            "`confirmedPurchaseId` IS NOT NULL) " +
            "BEGIN SELECT RAISE(ABORT, 'confirmed purchase snapshot is immutable'); END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `prepared_purchases_block_confirmed_update` " +
            "BEFORE UPDATE ON `prepared_purchases` WHEN EXISTS (" +
            "SELECT 1 FROM `invoice_drafts` d WHERE " +
            "(d.`draftId` = OLD.`draftId` OR d.`draftId` = NEW.`draftId`) AND " +
            "d.`confirmedPurchaseId` IS NOT NULL) " +
            "BEGIN SELECT RAISE(ABORT, 'confirmed purchase snapshot is immutable'); END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `invoice_drafts_validate_purchase_insert` " +
            "BEFORE INSERT ON `invoice_drafts` " +
            "WHEN NEW.`confirmedPurchaseId` IS NOT NULL AND NOT EXISTS (" +
            "SELECT 1 FROM `purchases` WHERE " +
            "`purchaseId` = NEW.`confirmedPurchaseId` AND " +
            "`sourceDraftId` = NEW.`draftId` AND `businessId` = NEW.`businessId`) " +
            "BEGIN SELECT RAISE(ABORT, 'confirmed purchase does not belong to draft'); END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `invoice_drafts_validate_purchase_link` " +
            "BEFORE UPDATE OF `confirmedPurchaseId` ON `invoice_drafts` " +
            "WHEN NEW.`confirmedPurchaseId` IS NOT NULL AND NOT EXISTS (" +
            "SELECT 1 FROM `purchases` WHERE " +
            "`purchaseId` = NEW.`confirmedPurchaseId` AND " +
            "`sourceDraftId` = NEW.`draftId` AND `businessId` = NEW.`businessId`) " +
            "BEGIN SELECT RAISE(ABORT, 'confirmed purchase does not belong to draft'); END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `invoice_drafts_block_purchase_relink` " +
            "BEFORE UPDATE OF `confirmedPurchaseId` ON `invoice_drafts` " +
            "WHEN OLD.`confirmedPurchaseId` IS NOT NULL AND " +
            "NEW.`confirmedPurchaseId` IS NOT OLD.`confirmedPurchaseId` " +
            "BEGIN SELECT RAISE(ABORT, 'confirmed purchase link is immutable'); END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `invoice_drafts_block_committed_insert` " +
            "BEFORE INSERT ON `invoice_drafts` WHEN NEW.`status` = 'COMMITTED' " +
            "BEGIN SELECT RAISE(ABORT, 'draft cannot start committed'); END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `invoice_drafts_validate_committed_transition` " +
            "BEFORE UPDATE OF `status` ON `invoice_drafts` " +
            "WHEN NEW.`status` = 'COMMITTED' AND NOT (" +
            "OLD.`status` = 'READY_TO_POST' AND NEW.`confirmedPurchaseId` IS NOT NULL AND " +
            "EXISTS (SELECT 1 FROM `prepared_purchases` pp WHERE " +
            "pp.`draftId` = NEW.`draftId`) AND EXISTS (" +
            "SELECT 1 FROM `purchases` p WHERE " +
            "p.`purchaseId` = NEW.`confirmedPurchaseId` AND " +
            "p.`sourceDraftId` = NEW.`draftId` AND p.`businessId` = NEW.`businessId` AND " +
            "p.`status` IN ('POSTED','VOIDED'))) " +
            "BEGIN SELECT RAISE(ABORT, 'invalid committed draft graph'); END",
    )
    db.execSQL(
        "CREATE TRIGGER IF NOT EXISTS `invoice_drafts_block_committed_update` " +
            "BEFORE UPDATE ON `invoice_drafts` WHEN OLD.`status` = 'COMMITTED' " +
            "BEGIN SELECT RAISE(ABORT, 'committed draft is immutable'); END",
    )
}

private fun SupportSQLiteDatabase.tableHasColumn(table: String, column: String): Boolean =
    query("PRAGMA table_info(`$table`)").use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        var found = false
        while (cursor.moveToNext() && !found) {
            found = cursor.getString(nameIndex) == column
        }
        found
    }

/** Callback de producción para bases nuevas y reparación idempotente al abrir. */
internal val postingPersistenceCallback: RoomDatabase.Callback =
    object : RoomDatabase.Callback() {
        override fun onCreate(connection: SQLiteConnection) {
            val db = SupportSQLiteDatabase(connection)
            installPostingPersistenceInvariants(db)
            installSalesPersistenceInvariants(db)
            installSaleVoidPersistenceInvariants(db)
            installDebtPersistenceInvariants(db)
            installCloudBusinessBindingInvariants(db)
            installCheckoutPersistenceInvariants(db)
        }

        override fun onOpen(connection: SQLiteConnection) {
            val db = SupportSQLiteDatabase(connection)
            enforceWriteDurability(db)
            installPostingPersistenceInvariants(db)
            installSalesPersistenceInvariants(db)
            installSaleVoidPersistenceInvariants(db)
            installDebtPersistenceInvariants(db)
            installCloudBusinessBindingInvariants(db)
            installCheckoutPersistenceInvariants(db)
        }
    }

/**
 * Durabilidad de escritura: WAL en Android arranca con `synchronous=NORMAL`, que resiste el
 * cierre de la app pero puede revertir la transacción más reciente ante un apagado abrupto o
 * pérdida de energía. `FULL` fuerza el fsync del WAL en cada commit. Se aplica en onOpen, que
 * corre sobre cada conexión que abre el open helper —incluida la que escribe— sin depender de
 * un PRAGMA aplicado a una sola conexión del pool.
 */
internal fun enforceWriteDurability(db: SupportSQLiteDatabase) {
    // rawQuery es perezoso: el PRAGMA solo se ejecuta al avanzar el cursor. La asignación puede
    // volver con o sin filas según el nivel de API, así que se avanza sin exigirlas; la lectura
    // posterior sí siempre devuelve el valor vigente y confirma que quedó fijado.
    db.query("PRAGMA synchronous=FULL").use { cursor -> cursor.moveToFirst() }
    db.query("PRAGMA synchronous").use { cursor ->
        check(cursor.moveToFirst() && cursor.getInt(0) == SYNCHRONOUS_FULL) {
            "synchronous=FULL no quedó activo en la conexión"
        }
    }
}

/** Valor de `PRAGMA synchronous` que equivale a FULL en SQLite. */
private const val SYNCHRONOUS_FULL: Int = 2
