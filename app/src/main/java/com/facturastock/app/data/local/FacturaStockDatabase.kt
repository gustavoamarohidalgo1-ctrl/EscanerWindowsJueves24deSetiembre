package com.facturastock.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.facturastock.app.data.local.dao.BusinessDao
import com.facturastock.app.data.local.dao.AuditEventDao
import com.facturastock.app.data.local.dao.CapturedPagePublicationDao
import com.facturastock.app.data.local.dao.CatalogSyncLinkDao
import com.facturastock.app.data.local.dao.CloudBusinessBindingDao
import com.facturastock.app.data.local.dao.DebtDao
import com.facturastock.app.data.local.dao.InventoryLocationDao
import com.facturastock.app.data.local.dao.InventoryDao
import com.facturastock.app.data.local.dao.InvoiceDraftDao
import com.facturastock.app.data.local.dao.InvoiceHeaderEditDao
import com.facturastock.app.data.local.dao.InvoiceImageDao
import com.facturastock.app.data.local.dao.InvoiceLineDao
import com.facturastock.app.data.local.dao.InvoiceLinesEditDao
import com.facturastock.app.data.local.dao.InvoiceOcrSnapshotDao
import com.facturastock.app.data.local.dao.ParsedInvoiceDao
import com.facturastock.app.data.local.dao.PreparedPurchaseDao
import com.facturastock.app.data.local.dao.PurchaseDao
import com.facturastock.app.data.local.dao.PurchaseLineDao
import com.facturastock.app.data.local.dao.PurchasePostingDao
import com.facturastock.app.data.local.dao.PurchaseVoidDao
import com.facturastock.app.data.local.dao.SaleDao
import com.facturastock.app.data.local.dao.RemoteSyncDao
import com.facturastock.app.data.local.dao.ProductDao
import com.facturastock.app.data.local.dao.SupplierDao
import com.facturastock.app.data.local.dao.SupplierProductAliasDao
import com.facturastock.app.data.local.dao.UnitDao
import com.facturastock.app.data.local.dao.OutboxOperationDao
import com.facturastock.app.data.local.entity.AuditEventEntity
import com.facturastock.app.data.local.entity.BusinessEntity
import com.facturastock.app.data.local.entity.CapturedPagePublicationEntity
import com.facturastock.app.data.local.entity.CatalogSyncLinkEntity
import com.facturastock.app.data.local.entity.CloudBusinessBindingEntity
import com.facturastock.app.data.local.entity.DebtEntity
import com.facturastock.app.data.local.entity.DebtPaymentEntity
import com.facturastock.app.data.local.entity.InventoryLocationEntity
import com.facturastock.app.data.local.entity.InventoryBalanceEntity
import com.facturastock.app.data.local.entity.InvoiceDraftEntity
import com.facturastock.app.data.local.entity.InvoiceHeaderEditEntity
import com.facturastock.app.data.local.entity.InvoiceImageEntity
import com.facturastock.app.data.local.entity.InvoiceLineEntity
import com.facturastock.app.data.local.entity.InvoiceLinesEditEntity
import com.facturastock.app.data.local.entity.InvoiceOcrSnapshotEntity
import com.facturastock.app.data.local.entity.InvoiceOcrSnapshotPageEntity
import com.facturastock.app.data.local.entity.ParsedInvoiceResultEntity
import com.facturastock.app.data.local.entity.PreparedPurchaseEntity
import com.facturastock.app.data.local.entity.PurchaseEntity
import com.facturastock.app.data.local.entity.PurchaseLineEntity
import com.facturastock.app.data.local.entity.SaleEntity
import com.facturastock.app.data.local.entity.SaleLineEntity
import com.facturastock.app.data.local.entity.ProductEntity
import com.facturastock.app.data.local.entity.SupplierEntity
import com.facturastock.app.data.local.entity.SupplierProductAliasEntity
import com.facturastock.app.data.local.entity.UnitEntity
import com.facturastock.app.data.local.entity.StockMovementEntity
import com.facturastock.app.data.local.entity.OutboxOperationEntity
import com.facturastock.app.data.local.entity.RemoteCatalogChangeEntity
import com.facturastock.app.data.local.entity.RemoteMovementSummaryEntity
import com.facturastock.app.data.local.entity.RemotePurchaseChangeEntity
import com.facturastock.app.data.local.entity.RemoteSyncStateEntity
import com.facturastock.app.domain.model.CatalogCanonicalizer

/**
 * Versión Room que también delimita el contrato de importación de snapshots.
 *
 * `verifyRoomSchemaPolicy` compara este valor con el literal de [Database.version]; mantener el
 * literal permite que el gate estático siga auditando el esquema exportado y esta constante evita
 * que consumidores como restore queden una versión atrás silenciosamente.
 */
const val FACTURA_STOCK_DATABASE_SCHEMA_VERSION: Int = 27

/** Hashes exportados por Room para las versiones aceptadas por el snapshot v1. */
const val FACTURA_STOCK_ROOM_IDENTITY_HASH_V24: String = "fd66144e0693b8448696bd6579bfb690"
const val FACTURA_STOCK_ROOM_IDENTITY_HASH_V25: String = "2f47e2315c70cd2582d81ccc87774d08"
const val FACTURA_STOCK_ROOM_IDENTITY_HASH_V26: String = "4bc1a91cad020ed2d95ec247cf8a92e7"
const val FACTURA_STOCK_ROOM_IDENTITY_HASH_V27: String = "248df7b1461797641fcfaa14bdff5d3d"

fun expectedFacturaStockRoomIdentityHash(schemaVersion: Int): String? = when (schemaVersion) {
    24 -> FACTURA_STOCK_ROOM_IDENTITY_HASH_V24
    25 -> FACTURA_STOCK_ROOM_IDENTITY_HASH_V25
    26 -> FACTURA_STOCK_ROOM_IDENTITY_HASH_V26
    27 -> FACTURA_STOCK_ROOM_IDENTITY_HASH_V27
    else -> null
}

/**
 * Base de datos local de FacturaStock.
 *
 * El esquema se exporta a `app/schemas` en cada compilación y se versiona junto al código.
 * Política de migración: **está prohibida la migración destructiva** — este builder nunca
 * llama a `fallbackToDestructiveMigration`; todo cambio de versión exige objetos `Migration`
 * explícitos y verificados.
 *
 * Antes de publicar, eliminar un negocio elimina su catálogo y borradores. Una vez que existe
 * historia POSTED, los triggers append-only hacen que esa eliminación sea rechazada: no se
 * sacrifica auditoría para satisfacer una cascada. Eliminar un borrador editable elimina sus
 * imágenes, líneas, snapshot OCR, audit trail parseado y autosaves. Las líneas y borradores sobreviven a la
 * eliminación física de producto, unidad, ubicación o proveedor: aunque algunas FKs antiguas
 * conservan `SET_NULL`, guards de catálogo impiden el `DELETE` si hay referencias y obligan a
 * archivar. La cascada del negocio sigue disponible para datos demo sin historia publicada.
 */
@Database(
    entities = [
        BusinessEntity::class,
        SupplierEntity::class,
        UnitEntity::class,
        InventoryLocationEntity::class,
        ProductEntity::class,
        SupplierProductAliasEntity::class,
        InvoiceDraftEntity::class,
        InvoiceImageEntity::class,
        CapturedPagePublicationEntity::class,
        InvoiceLineEntity::class,
        InvoiceOcrSnapshotEntity::class,
        InvoiceOcrSnapshotPageEntity::class,
        ParsedInvoiceResultEntity::class,
        InvoiceHeaderEditEntity::class,
        InvoiceLinesEditEntity::class,
        PreparedPurchaseEntity::class,
        PurchaseEntity::class,
        PurchaseLineEntity::class,
        SaleEntity::class,
        SaleLineEntity::class,
        InventoryBalanceEntity::class,
        StockMovementEntity::class,
        AuditEventEntity::class,
        OutboxOperationEntity::class,
        RemoteSyncStateEntity::class,
        RemotePurchaseChangeEntity::class,
        RemoteMovementSummaryEntity::class,
        RemoteCatalogChangeEntity::class,
        CatalogSyncLinkEntity::class,
        CloudBusinessBindingEntity::class,
        DebtEntity::class,
        DebtPaymentEntity::class,
    ],
    version = 27,
    exportSchema = true,
)
abstract class FacturaStockDatabase : RoomDatabase() {
    abstract fun businessDao(): BusinessDao
    abstract fun supplierDao(): SupplierDao
    abstract fun unitDao(): UnitDao
    abstract fun inventoryLocationDao(): InventoryLocationDao
    abstract fun productDao(): ProductDao
    abstract fun supplierProductAliasDao(): SupplierProductAliasDao
    abstract fun invoiceDraftDao(): InvoiceDraftDao
    abstract fun invoiceImageDao(): InvoiceImageDao
    abstract fun capturedPagePublicationDao(): CapturedPagePublicationDao
    abstract fun invoiceLineDao(): InvoiceLineDao
    abstract fun invoiceOcrSnapshotDao(): InvoiceOcrSnapshotDao
    abstract fun parsedInvoiceDao(): ParsedInvoiceDao
    abstract fun invoiceHeaderEditDao(): InvoiceHeaderEditDao
    abstract fun invoiceLinesEditDao(): InvoiceLinesEditDao
    abstract fun preparedPurchaseDao(): PreparedPurchaseDao
    abstract fun purchaseDao(): PurchaseDao
    abstract fun purchaseLineDao(): PurchaseLineDao
    abstract fun purchasePostingDao(): PurchasePostingDao
    abstract fun purchaseVoidDao(): PurchaseVoidDao
    abstract fun saleDao(): SaleDao
    abstract fun inventoryDao(): InventoryDao
    abstract fun auditEventDao(): AuditEventDao
    abstract fun outboxOperationDao(): OutboxOperationDao
    abstract fun remoteSyncDao(): RemoteSyncDao
    abstract fun catalogSyncLinkDao(): CatalogSyncLinkDao
    abstract fun cloudBusinessBindingDao(): CloudBusinessBindingDao
    abstract fun debtDao(): DebtDao

    companion object {
        const val NAME = "facturastock.db"

        /**
         * v1 → v2: `businesses.ruc` pasa de `NOT NULL` a nullable (RUC opcional en onboarding
         * y modo demo). SQLite no puede quitar un `NOT NULL` in situ, así que se recrea la
         * tabla conservando los datos y se reconstruye el índice único (los `NULL` no
         * colisionan). La migración destructiva sigue prohibida.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `businesses_new` (" +
                        "`businessId` TEXT NOT NULL, " +
                        "`legalName` TEXT NOT NULL, " +
                        "`ruc` TEXT, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "`tradeName` TEXT, " +
                        "`status` TEXT NOT NULL, " +
                        "PRIMARY KEY(`businessId`))",
                )
                db.execSQL(
                    "INSERT INTO `businesses_new` " +
                        "(`businessId`, `legalName`, `ruc`, `createdAt`, `updatedAt`, `tradeName`, `status`) " +
                        "SELECT `businessId`, `legalName`, `ruc`, `createdAt`, `updatedAt`, `tradeName`, `status` " +
                        "FROM `businesses`",
                )
                db.execSQL("DROP TABLE `businesses`")
                db.execSQL("ALTER TABLE `businesses_new` RENAME TO `businesses`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_businesses_ruc` ON `businesses` (`ruc`)",
                )
            }
        }

        /**
         * v2 → v3: el recorte de `invoice_images` pasa de píxeles (`crop*Px`) a coordenadas
         * normalizadas en diezmilésimas (`crop*Fraction`, 0..10000) sobre la imagen ya
         * rotada. La conversión divide cada arista entre la dimensión rotada
         * correspondiente (con 90°/270° ancho y alto se intercambian) y se acota a
         * 0..10000; los `NULL` (sin recorte) se conservan. Se recrea la tabla porque SQLite
         * no renombra columnas de forma portable en el rango de API soportado.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `invoice_images_new` (" +
                        "`imageId` TEXT NOT NULL, " +
                        "`draftId` TEXT NOT NULL, " +
                        "`businessId` TEXT NOT NULL, " +
                        "`pageIndex` INTEGER NOT NULL, " +
                        "`filePath` TEXT NOT NULL, " +
                        "`sha256` TEXT NOT NULL, " +
                        "`mimeType` TEXT NOT NULL, " +
                        "`widthPx` INTEGER NOT NULL, " +
                        "`heightPx` INTEGER NOT NULL, " +
                        "`fileSizeBytes` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`rotationDegrees` INTEGER NOT NULL, " +
                        "`cropLeftFraction` INTEGER, " +
                        "`cropTopFraction` INTEGER, " +
                        "`cropRightFraction` INTEGER, " +
                        "`cropBottomFraction` INTEGER, " +
                        "PRIMARY KEY(`imageId`), " +
                        "FOREIGN KEY(`draftId`) REFERENCES `invoice_drafts`(`draftId`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`businessId`) REFERENCES `businesses`(`businessId`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                // Ancho/alto YA rotados: con 90°/270° se intercambian las dimensiones.
                val rotatedWidth =
                    "(CASE WHEN `rotationDegrees` IN (90, 270) THEN `heightPx` ELSE `widthPx` END)"
                val rotatedHeight =
                    "(CASE WHEN `rotationDegrees` IN (90, 270) THEN `widthPx` ELSE `heightPx` END)"
                fun fraction(columnPx: String, dimension: String): String =
                    "CASE WHEN `$columnPx` IS NULL THEN NULL ELSE " +
                        "MIN(10000, MAX(0, CAST(ROUND(`$columnPx` * 10000.0 / $dimension) AS INTEGER))) " +
                        "END"
                db.execSQL(
                    "INSERT INTO `invoice_images_new` (" +
                        "`imageId`, `draftId`, `businessId`, `pageIndex`, `filePath`, `sha256`, " +
                        "`mimeType`, `widthPx`, `heightPx`, `fileSizeBytes`, `createdAt`, " +
                        "`rotationDegrees`, `cropLeftFraction`, `cropTopFraction`, " +
                        "`cropRightFraction`, `cropBottomFraction`) " +
                        "SELECT `imageId`, `draftId`, `businessId`, `pageIndex`, `filePath`, " +
                        "`sha256`, `mimeType`, `widthPx`, `heightPx`, `fileSizeBytes`, `createdAt`, " +
                        "`rotationDegrees`, " +
                        fraction("cropLeftPx", rotatedWidth) + ", " +
                        fraction("cropTopPx", rotatedHeight) + ", " +
                        fraction("cropRightPx", rotatedWidth) + ", " +
                        fraction("cropBottomPx", rotatedHeight) + " " +
                        "FROM `invoice_images`",
                )
                db.execSQL("DROP TABLE `invoice_images`")
                db.execSQL("ALTER TABLE `invoice_images_new` RENAME TO `invoice_images`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_invoice_images_draftId_pageIndex` " +
                        "ON `invoice_images` (`draftId`, `pageIndex`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_invoice_images_businessId` " +
                        "ON `invoice_images` (`businessId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_invoice_images_businessId_sha256` " +
                        "ON `invoice_images` (`businessId`, `sha256`)",
                )
            }
        }

        /** v3 → v4: token nullable que protege el commit/cancelación de cada ejecución OCR. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `invoice_drafts` ADD COLUMN `activeOcrRunId` TEXT")
            }
        }

        /** v4 → v5: snapshot OCR reemplazable, dividido en una BLOB versionada por página. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `invoice_ocr_snapshots` (" +
                        "`draftId` TEXT NOT NULL, " +
                        "`runId` TEXT NOT NULL, " +
                        "`completedAt` INTEGER NOT NULL, " +
                        "`codecVersion` INTEGER NOT NULL, " +
                        "`pageCount` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`draftId`), " +
                        "FOREIGN KEY(`draftId`) REFERENCES `invoice_drafts`(`draftId`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_invoice_ocr_snapshots_runId` " +
                        "ON `invoice_ocr_snapshots` (`runId`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `invoice_ocr_snapshot_pages` (" +
                        "`draftId` TEXT NOT NULL, " +
                        "`pageIndex` INTEGER NOT NULL, " +
                        "`sourceImageId` TEXT NOT NULL, " +
                        "`widthPx` INTEGER NOT NULL, " +
                        "`heightPx` INTEGER NOT NULL, " +
                        "`payloadSha256` TEXT NOT NULL, " +
                        "`payload` BLOB NOT NULL, " +
                        "PRIMARY KEY(`draftId`, `pageIndex`), " +
                        "FOREIGN KEY(`draftId`) REFERENCES `invoice_ocr_snapshots`(`draftId`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                // v4 nunca persistió el documento: no puede conservar honestamente OCR_READY.
                db.execSQL(
                    "UPDATE `invoice_drafts` SET `status` = 'CAPTURED', " +
                        "`activeOcrRunId` = NULL, `lastError` = NULL " +
                        "WHERE `status` = 'OCR_READY' AND `confirmedPurchaseId` IS NULL",
                )
            }
        }

        /** v5 → v6: audit trail versionado del parser, ligado 1:1 al snapshot OCR. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_invoice_ocr_snapshots_draftId_runId` " +
                        "ON `invoice_ocr_snapshots` (`draftId`, `runId`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `invoice_parsed_results` (" +
                        "`draftId` TEXT NOT NULL, " +
                        "`runId` TEXT NOT NULL, " +
                        "`parserVersion` INTEGER NOT NULL, " +
                        "`contextFingerprint` TEXT NOT NULL, " +
                        "`payloadCodecVersion` INTEGER NOT NULL, " +
                        "`payloadSha256` TEXT NOT NULL, " +
                        "`payload` BLOB NOT NULL, " +
                        "`parsedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`draftId`), " +
                        "FOREIGN KEY(`draftId`, `runId`) " +
                        "REFERENCES `invoice_ocr_snapshots`(`draftId`, `runId`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_invoice_parsed_results_draftId_runId` " +
                        "ON `invoice_parsed_results` (`draftId`, `runId`)",
                )
            }
        }

        /** v6 → v7: proyección ampliada y formulario parcial durable de revisión de cabecera. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `invoice_drafts` ADD COLUMN `supplierLegalNameRaw` TEXT")
                db.execSQL(
                    "ALTER TABLE `invoice_drafts` ADD COLUMN `supplierLegalNameNormalized` TEXT",
                )
                db.execSQL("ALTER TABLE `invoice_drafts` ADD COLUMN `documentType` TEXT")
                db.execSQL("ALTER TABLE `invoice_drafts` ADD COLUMN `otherChargesMinorUnits` INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `invoice_header_edits` (" +
                        "`draftId` TEXT NOT NULL, " +
                        "`revision` INTEGER NOT NULL, " +
                        "`payloadCodecVersion` INTEGER NOT NULL, " +
                        "`payloadSha256` TEXT NOT NULL, " +
                        "`payload` BLOB NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`draftId`), " +
                        "FOREIGN KEY(`draftId`) REFERENCES `invoice_drafts`(`draftId`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
            }
        }

        /** v7 → v8: snapshot CAS del editor de líneas y tombstones restaurables. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `invoice_lines` ADD COLUMN `codeRaw` TEXT")
                db.execSQL("ALTER TABLE `invoice_lines` ADD COLUMN `codeNormalized` TEXT")
                db.execSQL("ALTER TABLE `invoice_lines` ADD COLUMN `unitRaw` TEXT")
                db.execSQL("ALTER TABLE `invoice_lines` ADD COLUMN `unitCodeNormalized` TEXT")
                db.execSQL("ALTER TABLE `invoice_lines` ADD COLUMN `discountMinorUnits` INTEGER")
                db.execSQL("ALTER TABLE `invoice_lines` ADD COLUMN `taxMinorUnits` INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `invoice_line_edits` (" +
                        "`draftId` TEXT NOT NULL, " +
                        "`revision` INTEGER NOT NULL, " +
                        "`payloadCodecVersion` INTEGER NOT NULL, " +
                        "`payloadSha256` TEXT NOT NULL, " +
                        "`payload` BLOB NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`draftId`), " +
                        "FOREIGN KEY(`draftId`) REFERENCES `invoice_drafts`(`draftId`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
            }
        }

        /**
         * v8 → v9: `products` gana la unidad de compra (`purchaseUnitId`, FK a `units` con
         * `SET_NULL`) y su factor hacia la unidad de inventario (`purchaseFactor`, decimal en
         * texto plano). SQLite no puede añadir una `FOREIGN KEY` con `ALTER TABLE`, así que se
         * recrea la tabla conservando los datos (ambas columnas nacen `NULL`) y se reconstruyen
         * los índices. La migración destructiva sigue prohibida.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `products_new` (" +
                        "`productId` TEXT NOT NULL, " +
                        "`businessId` TEXT NOT NULL, " +
                        "`unitId` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "`locationId` TEXT, " +
                        "`sku` TEXT, " +
                        "`barcode` TEXT, " +
                        "`normalizedName` TEXT NOT NULL, " +
                        "`purchaseUnitId` TEXT, " +
                        "`purchaseFactor` TEXT, " +
                        "`status` TEXT NOT NULL, " +
                        "PRIMARY KEY(`productId`), " +
                        "FOREIGN KEY(`businessId`) REFERENCES `businesses`(`businessId`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`unitId`) REFERENCES `units`(`unitId`) " +
                        "ON UPDATE NO ACTION ON DELETE RESTRICT, " +
                        "FOREIGN KEY(`locationId`) REFERENCES `inventory_locations`(`locationId`) " +
                        "ON UPDATE NO ACTION ON DELETE SET NULL, " +
                        "FOREIGN KEY(`purchaseUnitId`) REFERENCES `units`(`unitId`) " +
                        "ON UPDATE NO ACTION ON DELETE SET NULL)",
                )
                db.execSQL(
                    "INSERT INTO `products_new` " +
                        "(`productId`, `businessId`, `unitId`, `name`, `createdAt`, `updatedAt`, " +
                        "`locationId`, `sku`, `barcode`, `normalizedName`, `status`) " +
                        "SELECT `productId`, `businessId`, `unitId`, `name`, `createdAt`, `updatedAt`, " +
                        "`locationId`, `sku`, `barcode`, `normalizedName`, `status` " +
                        "FROM `products`",
                )
                db.execSQL("DROP TABLE `products`")
                db.execSQL("ALTER TABLE `products_new` RENAME TO `products`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_products_businessId_sku` " +
                        "ON `products` (`businessId`, `sku`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_products_businessId_barcode` " +
                        "ON `products` (`businessId`, `barcode`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_products_businessId_normalizedName` " +
                        "ON `products` (`businessId`, `normalizedName`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_products_unitId` ON `products` (`unitId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_products_locationId` " +
                        "ON `products` (`locationId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_products_purchaseUnitId` " +
                        "ON `products` (`purchaseUnitId`)",
                )
            }
        }

        /**
         * v9 → v10: instantánea de compra preparada (`prepared_purchases`), una por borrador,
         * con hash lógico para idempotencia por contenido. El paso a/desde `READY_TO_POST` usa
         * los UPDATE condicionales de `PreparedPurchaseDao`; no hace falta tocar `invoice_drafts`
         * porque `READY_TO_POST` ya existe en el enum persistido por nombre.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `prepared_purchases` (" +
                        "`draftId` TEXT NOT NULL, " +
                        "`logicalHash` TEXT NOT NULL, " +
                        "`payloadCodecVersion` INTEGER NOT NULL, " +
                        "`payloadSha256` TEXT NOT NULL, " +
                        "`payload` BLOB NOT NULL, " +
                        "`preparedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`draftId`), " +
                        "FOREIGN KEY(`draftId`) REFERENCES `invoice_drafts`(`draftId`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
            }
        }

        /**
         * v10 → v11: libro definitivo de compras e inventario. Las tablas se añaden vacías y
         * se preservan borradores, OCR y revisiones. Las preparaciones v10 se invalidan de forma
         * conservadora porque su codec permitía tipo documental o costo nulos, campos obligatorios
         * para la identidad contable v11; el usuario vuelve a revisar sin perder sus ediciones. El
         * antiguo `confirmedPurchaseId` también se limpia porque v10 no tenía una tabla destino.
         * Unicidad, claves foráneas y triggers forman la barrera de idempotencia y auditoría.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // v10 no tenía tabla de compras ni FK para este campo. Un valor no nulo solo
                // podía provenir de un workflow provisional y no puede apuntar a una compra v11.
                db.execSQL(
                    "UPDATE `invoice_drafts` SET `confirmedPurchaseId` = NULL " +
                        "WHERE `confirmedPurchaseId` IS NOT NULL",
                )
                // SQL no puede decodificar el BLOB v10 para distinguir snapshots publicables.
                // Reabrirlos es seguro y conserva cabecera, líneas, OCR e imágenes revisadas.
                db.execSQL(
                    "UPDATE `invoice_drafts` SET `status` = 'NEEDS_REVIEW' " +
                        "WHERE `status` = 'READY_TO_POST'",
                )
                db.execSQL("DELETE FROM `prepared_purchases`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `purchases` (" +
                        "`purchaseId` TEXT NOT NULL, `businessId` TEXT NOT NULL, " +
                        "`sourceDraftId` TEXT NOT NULL, `supplierId` TEXT NOT NULL, " +
                        "`documentType` TEXT NOT NULL, `documentSeries` TEXT NOT NULL, " +
                        "`documentNumber` TEXT NOT NULL, `issueDate` TEXT NOT NULL, " +
                        "`currencyCode` TEXT NOT NULL, `subtotalMinorUnits` INTEGER NOT NULL, " +
                        "`taxMinorUnits` INTEGER NOT NULL, `otherChargesMinorUnits` INTEGER NOT NULL, " +
                        "`totalMinorUnits` INTEGER NOT NULL, `status` TEXT NOT NULL, " +
                        "`idempotencyKey` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, `postedAt` INTEGER, `voidedAt` INTEGER, " +
                        "PRIMARY KEY(`purchaseId`), " +
                        "FOREIGN KEY(`businessId`) REFERENCES `businesses`(`businessId`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`sourceDraftId`) REFERENCES `invoice_drafts`(`draftId`) " +
                        "ON UPDATE NO ACTION ON DELETE RESTRICT, " +
                        "FOREIGN KEY(`supplierId`) REFERENCES `suppliers`(`supplierId`) " +
                        "ON UPDATE NO ACTION ON DELETE RESTRICT)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_purchases_sourceDraftId` " +
                        "ON `purchases` (`sourceDraftId`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_purchases_idempotencyKey` " +
                        "ON `purchases` (`idempotencyKey`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_purchases_businessId_supplierId_documentType_documentSeries_documentNumber` " +
                        "ON `purchases` (`businessId`, `supplierId`, `documentType`, " +
                        "`documentSeries`, `documentNumber`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_purchases_businessId_status` " +
                        "ON `purchases` (`businessId`, `status`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_purchases_supplierId` " +
                        "ON `purchases` (`supplierId`)",
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `purchase_lines` (" +
                        "`purchaseLineId` TEXT NOT NULL, `purchaseId` TEXT NOT NULL, " +
                        "`productId` TEXT NOT NULL, `unitId` TEXT NOT NULL, " +
                        "`position` INTEGER NOT NULL, `rawText` TEXT NOT NULL, " +
                        "`description` TEXT NOT NULL, `quantity` TEXT NOT NULL, " +
                        "`unitCost` TEXT NOT NULL, `currencyCode` TEXT NOT NULL, " +
                        "`taxMinorUnits` INTEGER NOT NULL, `totalMinorUnits` INTEGER NOT NULL, " +
                        "`confidence` INTEGER, PRIMARY KEY(`purchaseLineId`), " +
                        "FOREIGN KEY(`purchaseId`) REFERENCES `purchases`(`purchaseId`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`productId`) REFERENCES `products`(`productId`) " +
                        "ON UPDATE NO ACTION ON DELETE RESTRICT, " +
                        "FOREIGN KEY(`unitId`) REFERENCES `units`(`unitId`) " +
                        "ON UPDATE NO ACTION ON DELETE RESTRICT)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_purchase_lines_purchaseId_position` " +
                        "ON `purchase_lines` (`purchaseId`, `position`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_purchase_lines_productId` " +
                        "ON `purchase_lines` (`productId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_purchase_lines_unitId` " +
                        "ON `purchase_lines` (`unitId`)",
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `inventory_balances` (" +
                        "`businessId` TEXT NOT NULL, `productId` TEXT NOT NULL, " +
                        "`locationId` TEXT NOT NULL, `quantityOnHand` TEXT NOT NULL, " +
                        "`averageUnitCost` TEXT NOT NULL, `currencyCode` TEXT NOT NULL, " +
                        "`version` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`businessId`, `productId`, `locationId`), " +
                        "FOREIGN KEY(`businessId`) REFERENCES `businesses`(`businessId`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`productId`) REFERENCES `products`(`productId`) " +
                        "ON UPDATE NO ACTION ON DELETE RESTRICT, " +
                        "FOREIGN KEY(`locationId`) REFERENCES `inventory_locations`(`locationId`) " +
                        "ON UPDATE NO ACTION ON DELETE RESTRICT)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_inventory_balances_productId` " +
                        "ON `inventory_balances` (`productId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_inventory_balances_locationId` " +
                        "ON `inventory_balances` (`locationId`)",
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `stock_movements` (" +
                        "`movementId` TEXT NOT NULL, `businessId` TEXT NOT NULL, " +
                        "`purchaseId` TEXT, `purchaseLineId` TEXT, `productId` TEXT NOT NULL, " +
                        "`locationId` TEXT NOT NULL, `type` TEXT NOT NULL, " +
                        "`quantityDelta` TEXT NOT NULL, `unitCost` TEXT, " +
                        "`currencyCode` TEXT NOT NULL, `idempotencyKey` TEXT NOT NULL, " +
                        "`occurredAt` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`movementId`), " +
                        "FOREIGN KEY(`businessId`) REFERENCES `businesses`(`businessId`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`purchaseId`) REFERENCES `purchases`(`purchaseId`) " +
                        "ON UPDATE NO ACTION ON DELETE RESTRICT, " +
                        "FOREIGN KEY(`purchaseLineId`) REFERENCES `purchase_lines`(`purchaseLineId`) " +
                        "ON UPDATE NO ACTION ON DELETE RESTRICT, " +
                        "FOREIGN KEY(`productId`) REFERENCES `products`(`productId`) " +
                        "ON UPDATE NO ACTION ON DELETE RESTRICT, " +
                        "FOREIGN KEY(`locationId`) REFERENCES `inventory_locations`(`locationId`) " +
                        "ON UPDATE NO ACTION ON DELETE RESTRICT)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_stock_movements_idempotencyKey` " +
                        "ON `stock_movements` (`idempotencyKey`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_stock_movements_businessId_productId_locationId_occurredAt` " +
                        "ON `stock_movements` (`businessId`, `productId`, `locationId`, `occurredAt`)",
                )
                listOf("purchaseId", "purchaseLineId", "productId", "locationId").forEach { column ->
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_stock_movements_$column` " +
                            "ON `stock_movements` (`$column`)",
                    )
                }

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `audit_events` (" +
                        "`auditEventId` TEXT NOT NULL, `businessId` TEXT NOT NULL, " +
                        "`purchaseId` TEXT, `eventType` TEXT NOT NULL, `entityType` TEXT NOT NULL, " +
                        "`entityId` TEXT NOT NULL, `payload` TEXT NOT NULL, " +
                        "`occurredAt` INTEGER NOT NULL, PRIMARY KEY(`auditEventId`), " +
                        "FOREIGN KEY(`businessId`) REFERENCES `businesses`(`businessId`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`purchaseId`) REFERENCES `purchases`(`purchaseId`) " +
                        "ON UPDATE NO ACTION ON DELETE RESTRICT)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_audit_events_businessId_occurredAt` " +
                        "ON `audit_events` (`businessId`, `occurredAt`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_audit_events_purchaseId` " +
                        "ON `audit_events` (`purchaseId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_audit_events_entityType_entityId` " +
                        "ON `audit_events` (`entityType`, `entityId`)",
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `outbox_operations` (" +
                        "`operationId` TEXT NOT NULL, `businessId` TEXT NOT NULL, " +
                        "`purchaseId` TEXT, `idempotencyKey` TEXT NOT NULL, " +
                        "`operationType` TEXT NOT NULL, `payload` TEXT NOT NULL, " +
                        "`status` TEXT NOT NULL, `attemptCount` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "`nextAttemptAt` INTEGER, `completedAt` INTEGER, `lastError` TEXT, " +
                        "PRIMARY KEY(`operationId`), " +
                        "FOREIGN KEY(`businessId`) REFERENCES `businesses`(`businessId`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`purchaseId`) REFERENCES `purchases`(`purchaseId`) " +
                        "ON UPDATE NO ACTION ON DELETE RESTRICT)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_outbox_operations_idempotencyKey` " +
                        "ON `outbox_operations` (`idempotencyKey`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_outbox_operations_businessId_status_nextAttemptAt` " +
                        "ON `outbox_operations` (`businessId`, `status`, `nextAttemptAt`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_outbox_operations_purchaseId` " +
                        "ON `outbox_operations` (`purchaseId`)",
                )

                installPostingPersistenceInvariants(db)
            }
        }

        /**
         * v11 → v12: normaliza las claves de unicidad operativa sin cambiar tablas ni índices.
         * Si dos valores antiguos colapsan, conserva la clave en el id lexicográficamente menor
         * y limpia a NULL los opcionales restantes, sin borrar ninguna fila ni referencia.
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val supplierRucs = readCatalogKeys(
                    db = db,
                    table = "suppliers",
                    idColumn = "supplierId",
                    valueColumn = "ruc",
                    canonicalize = CatalogCanonicalizer::ruc,
                )
                val productSkus = readCatalogKeys(
                    db = db,
                    table = "products",
                    idColumn = "productId",
                    valueColumn = "sku",
                    canonicalize = CatalogCanonicalizer::sku,
                )
                val productBarcodes = readCatalogKeys(
                    db = db,
                    table = "products",
                    idColumn = "productId",
                    valueColumn = "barcode",
                    // Esta migración debe conservar la semántica vigente cuando se escribió
                    // el esquema v11. El contrato actual es ASCII estricto, pero aplicarlo a
                    // historia ya persistida impediría abrir bases que contenían Unicode válido.
                    canonicalize = ::canonicalizeLegacyBarcode,
                )
                updateCanonicalKeys(
                    db,
                    "suppliers",
                    "supplierId",
                    "ruc",
                    resolveCanonicalCollisions(supplierRucs),
                )
                updateCanonicalKeys(
                    db,
                    "products",
                    "productId",
                    "sku",
                    resolveCanonicalCollisions(productSkus),
                )
                updateCanonicalKeys(
                    db,
                    "products",
                    "productId",
                    "barcode",
                    resolveCanonicalCollisions(productBarcodes),
                )
                installPostingPersistenceInvariants(db)
            }
        }

        /**
         * v12 → v13: congela la trazabilidad del cálculo de inventario por línea. La columna
         * histórica `unitCost` sigue conteniendo el costo leído y se mapea en Kotlin como
         * `readUnitCost`; las columnas nuevas quedan NULL para historia v12 porque no es seguro
         * inventar retroactivamente factor, tratamiento tributario ni política. Toda publicación
         * v13 exige el grupo completo antes de escribir stock.
         *
         * `quantityOnHand` ya era TEXT y no requiere cambio físico para admitir signo: el cambio
         * está en la validación de entidad y permite que un saldo importado/ajustado negativo se
         * recupere mediante la regla explícita del servicio de costos.
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `purchase_lines` ADD COLUMN `appliedUnitCost` TEXT")
                db.execSQL("ALTER TABLE `purchase_lines` ADD COLUMN `purchaseUnitFactor` TEXT")
                db.execSQL("ALTER TABLE `purchase_lines` ADD COLUMN `inventoryQuantity` TEXT")
                db.execSQL("ALTER TABLE `purchase_lines` ADD COLUMN `discount` TEXT")
                db.execSQL("ALTER TABLE `purchase_lines` ADD COLUMN `taxTreatment` TEXT")
                db.execSQL("ALTER TABLE `purchase_lines` ADD COLUMN `costPolicy` TEXT")
                db.execSQL("ALTER TABLE `purchase_lines` ADD COLUMN `appliedCostTotal` TEXT")
                db.execSQL("ALTER TABLE `purchase_lines` ADD COLUMN `taxEvidenceType` TEXT")
                db.execSQL("ALTER TABLE `purchase_lines` ADD COLUMN `taxEvidenceValue` TEXT")
                db.execSQL("ALTER TABLE `purchase_lines` ADD COLUMN `roundingScale` INTEGER")
                db.execSQL("ALTER TABLE `purchase_lines` ADD COLUMN `roundingMode` TEXT")
                db.execSQL("ALTER TABLE `purchase_lines` ADD COLUMN `costingWarnings` TEXT")
                installPostingPersistenceInvariants(db)
            }
        }

        /**
         * v13 → v14: mantiene una identidad fiscal `PRIMARY` única y abre slots excepcionales
         * por borrador únicamente cuando conservan autorización durable. Las filas históricas se
         * migran a `PRIMARY`; ningún dato previo se interpreta retroactivamente como excepción.
         *
         * No se añade una FK autorreferencial mediante recreación de tabla: los triggers verifican
         * el objetivo `POSTED/VOIDED`, y esos estados ya son indelebles por las invariantes del
         * libro. Así los cinco `ALTER TABLE` son aditivos y seguros para bases con historia.
         */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `purchases` ADD COLUMN " +
                        "`documentIdentitySlot` TEXT NOT NULL DEFAULT 'PRIMARY'",
                )
                db.execSQL(
                    "ALTER TABLE `purchases` ADD COLUMN `duplicateOverrideOfPurchaseId` TEXT",
                )
                db.execSQL(
                    "ALTER TABLE `purchases` ADD COLUMN `duplicateOverrideReason` TEXT",
                )
                db.execSQL(
                    "ALTER TABLE `purchases` ADD COLUMN `duplicateOverrideActorId` TEXT",
                )
                db.execSQL(
                    "ALTER TABLE `purchases` ADD COLUMN `duplicateOverrideRole` TEXT",
                )
                db.execSQL(
                    "DROP INDEX IF EXISTS " +
                        "`index_purchases_businessId_supplierId_documentType_documentSeries_documentNumber`",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_purchases_businessId_supplierId_documentType_documentSeries_" +
                        "documentNumber_documentIdentitySlot` ON `purchases` " +
                        "(`businessId`, `supplierId`, `documentType`, `documentSeries`, " +
                        "`documentNumber`, `documentIdentitySlot`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_purchases_duplicateOverrideOfPurchaseId` " +
                        "ON `purchases` (`duplicateOverrideOfPurchaseId`)",
                )
                // Hasta v13 una publicación dejaba el borrador enlazado en READY_TO_POST. La
                // compra ya era terminal y completa, por lo que v14 materializa explícitamente
                // ese mismo estado como COMMITTED antes de instalar sus guards de inmutabilidad.
                db.execSQL(
                    "UPDATE `invoice_drafts` SET `status` = 'COMMITTED', `updatedAt` = MAX(" +
                        "`updatedAt`, COALESCE((SELECT p.`updatedAt` FROM `purchases` p WHERE " +
                        "p.`purchaseId` = `invoice_drafts`.`confirmedPurchaseId`), `updatedAt`)) " +
                        "WHERE `status` = 'READY_TO_POST' AND `confirmedPurchaseId` IS NOT NULL " +
                        "AND EXISTS (SELECT 1 FROM `prepared_purchases` pp WHERE " +
                        "pp.`draftId` = `invoice_drafts`.`draftId`) AND EXISTS (" +
                        "SELECT 1 FROM `purchases` p WHERE " +
                        "p.`purchaseId` = `invoice_drafts`.`confirmedPurchaseId` AND " +
                        "p.`sourceDraftId` = `invoice_drafts`.`draftId` AND " +
                        "p.`businessId` = `invoice_drafts`.`businessId` AND " +
                        "p.`status` IN ('POSTED','VOIDED'))",
                )
                installPostingPersistenceInvariants(db)
            }
        }

        /**
         * v14 → v15: la outbox adopta el protocolo de transporte concurrente. `claimToken` y
         * `claimLeaseUntil` identifican al dueño de cada intento PROCESSING (solo ese token puede
         * completar/fallar y un lease vencido se recupera sin esperar reinicio) y
         * `payloadVersion` fija la versión del payload mínimo ya embebida en el JSON. Los claims
         * históricos quedan con lease NULL: la recuperación lease-aware los trata como vencidos.
         * Los tres `ALTER` son aditivos; ninguna fila cambia de estado ni de contenido.
         */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `outbox_operations` ADD COLUMN " +
                        "`payloadVersion` INTEGER NOT NULL DEFAULT 1",
                )
                db.execSQL("ALTER TABLE `outbox_operations` ADD COLUMN `claimToken` TEXT")
                db.execSQL("ALTER TABLE `outbox_operations` ADD COLUMN `claimLeaseUntil` INTEGER")
                installPostingPersistenceInvariants(db)
            }
        }

        /**
         * v15 → v16: concurrencia optimista de catálogos e identidad del conflicto remoto.
         * `products.version` y `suppliers.version` nacen en 1 y todo `UPDATE` de catálogo las
         * exige como CAS (una edición sobre una lectura obsoleta afecta cero filas en lugar de
         * pisar el cambio ajeno). `outbox_operations` registra qué registro de la nube chocó
         * con la operación local (solo IDs, nunca contenido) para la resolución explícita.
         * Los cuatro `ALTER` son aditivos; ninguna fila cambia de estado ni de contenido.
         */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `products` ADD COLUMN `version` INTEGER NOT NULL DEFAULT 1",
                )
                db.execSQL(
                    "ALTER TABLE `suppliers` ADD COLUMN `version` INTEGER NOT NULL DEFAULT 1",
                )
                db.execSQL(
                    "ALTER TABLE `outbox_operations` ADD COLUMN `conflictRemotePurchaseId` TEXT",
                )
                db.execSQL(
                    "ALTER TABLE `outbox_operations` ADD COLUMN `conflictReceiptId` TEXT",
                )
                installPostingPersistenceInvariants(db)
            }
        }

        /**
         * v16 → v17: congela nombre de producto y código de unidad en cada línea publicada.
         * El mapper cloud deja así de reconstruir un mismo hecho desde catálogos editables.
         * Las filas existentes se rellenan desde las FK vigentes en la propia migración.
         */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `purchase_lines` ADD COLUMN `productNameSnapshot` TEXT")
                db.execSQL("ALTER TABLE `purchase_lines` ADD COLUMN `unitCodeSnapshot` TEXT")
                // v11+ ya protege las líneas POSTED con este trigger. El backfill es parte de
                // la migración (no una edición de negocio), así que lo retiramos dentro de la
                // misma transacción y installPostingPersistenceInvariants lo reinstala abajo.
                db.execSQL("DROP TRIGGER IF EXISTS `purchase_lines_block_posted_update`")
                db.execSQL(
                    "UPDATE `purchase_lines` SET `productNameSnapshot` = " +
                        "(SELECT `name` FROM `products` " +
                        "WHERE `products`.`productId` = `purchase_lines`.`productId`), " +
                        "`unitCodeSnapshot` = (SELECT `code` FROM `units` " +
                        "WHERE `units`.`unitId` = `purchase_lines`.`unitId`)",
                )
                installPostingPersistenceInvariants(db)
            }
        }

        /**
         * v17 -> v18: conserva por linea si el producto ya existia o fue creado en la
         * confirmacion. La historia previa queda UNKNOWN_LEGACY: nunca se reconstruye a partir
         * de timestamps ni de coincidencias actuales del catalogo.
         */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `purchase_lines` ADD COLUMN `productProvenance` " +
                        "TEXT NOT NULL DEFAULT 'UNKNOWN_LEGACY'",
                )
                installPostingPersistenceInvariants(db)
            }
        }

        /**
         * v18 -> v19: generaliza la outbox para agregados de catálogo y añade el espejo Room
         * del pull. Las operaciones históricas conservan su payload y clave: solo reciben la
         * identidad PURCHASE y una versión causal derivada del tipo (alta=1, anulación=2).
         */
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `outbox_operations` ADD COLUMN " +
                        "`entityType` TEXT NOT NULL DEFAULT 'PURCHASE'",
                )
                db.execSQL(
                    "ALTER TABLE `outbox_operations` ADD COLUMN " +
                        "`entityId` TEXT NOT NULL DEFAULT ''",
                )
                db.execSQL(
                    "ALTER TABLE `outbox_operations` ADD COLUMN " +
                        "`entityVersion` INTEGER NOT NULL DEFAULT 1",
                )
                db.execSQL(
                    "ALTER TABLE `outbox_operations` ADD COLUMN `remoteEntityId` TEXT",
                )
                db.execSQL(
                    "ALTER TABLE `outbox_operations` ADD COLUMN `conflictRemoteEntityId` TEXT",
                )
                db.execSQL(
                    "ALTER TABLE `outbox_operations` ADD COLUMN `conflictRemoteVersion` INTEGER",
                )
                db.execSQL(
                    "ALTER TABLE `outbox_operations` ADD COLUMN " +
                        "`conflictRemoteSnapshotPayload` TEXT",
                )
                db.execSQL(
                    "ALTER TABLE `outbox_operations` ADD COLUMN `conflictRemoteSyncedAt` INTEGER",
                )
                db.execSQL(
                    "ALTER TABLE `outbox_operations` ADD COLUMN `conflictRemoteOrigin` TEXT",
                )
                db.execSQL(
                    "ALTER TABLE `outbox_operations` ADD COLUMN `conflictCloudBusinessId` TEXT",
                )
                db.execSQL(
                    "UPDATE `outbox_operations` SET `entityId` = " +
                        "COALESCE(`purchaseId`, `operationId`), `entityVersion` = CASE " +
                        "WHEN `operationType` = 'SYNC_PURCHASE_VOID' THEN 2 ELSE 1 END",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_outbox_operations_entityType_entityId_" +
                        "entityVersion` ON `outbox_operations` " +
                        "(`entityType`, `entityId`, `entityVersion`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `remote_sync_states` (" +
                        "`cloudBusinessId` TEXT NOT NULL, `purchaseSeq` INTEGER NOT NULL, " +
                        "`purchasePulledAt` INTEGER, `catalogSeq` INTEGER NOT NULL, " +
                        "`catalogPulledAt` INTEGER, PRIMARY KEY(`cloudBusinessId`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `remote_purchase_changes` (" +
                        "`cloudBusinessId` TEXT NOT NULL, `seq` INTEGER NOT NULL, " +
                        "`purchaseId` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                        "`documentType` TEXT NOT NULL, `documentSeries` TEXT NOT NULL, " +
                        "`documentNumber` TEXT NOT NULL, `issueDate` TEXT NOT NULL, " +
                        "`currency` TEXT NOT NULL, `supplierRuc` TEXT, " +
                        "`supplierLegalName` TEXT NOT NULL, `totalMinorUnits` INTEGER NOT NULL, " +
                        "`receiptId` TEXT NOT NULL, `syncedAtMillis` INTEGER, " +
                        "PRIMARY KEY(`cloudBusinessId`, `seq`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_remote_purchase_changes_cloudBusinessId_" +
                        "purchaseId` ON `remote_purchase_changes` (`cloudBusinessId`, `purchaseId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_remote_purchase_changes_cloudBusinessId_" +
                        "receiptId` ON `remote_purchase_changes` (`cloudBusinessId`, `receiptId`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `remote_movement_summaries` (" +
                        "`cloudBusinessId` TEXT NOT NULL, `seq` INTEGER NOT NULL, " +
                        "`position` INTEGER NOT NULL, `productId` TEXT NOT NULL, " +
                        "`productName` TEXT, `type` TEXT NOT NULL, `quantityDelta` TEXT NOT NULL, " +
                        "PRIMARY KEY(`cloudBusinessId`, `seq`, `position`), " +
                        "FOREIGN KEY(`cloudBusinessId`, `seq`) REFERENCES " +
                        "`remote_purchase_changes`(`cloudBusinessId`, `seq`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_remote_movement_summaries_" +
                        "cloudBusinessId_seq` ON `remote_movement_summaries` " +
                        "(`cloudBusinessId`, `seq`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `remote_catalog_changes` (" +
                        "`cloudBusinessId` TEXT NOT NULL, `seq` INTEGER NOT NULL, " +
                        "`entityType` TEXT NOT NULL, `remoteEntityId` TEXT NOT NULL, " +
                        "`remoteVersion` INTEGER NOT NULL, `mutation` TEXT NOT NULL, " +
                        "`snapshotPayload` TEXT NOT NULL, `snapshotSha256` TEXT NOT NULL, " +
                        "`receiptId` TEXT NOT NULL, `syncedAtMillis` INTEGER, " +
                        "`origin` TEXT NOT NULL, `receivedAt` INTEGER NOT NULL, " +
                        "`applicationStatus` TEXT NOT NULL, `localEntityId` TEXT, " +
                        "`localVersion` INTEGER, `localSnapshotPayload` TEXT, " +
                        "`conflictCode` TEXT, `conflictDetectedAt` INTEGER, " +
                        "`resolution` TEXT, `resolvedAt` INTEGER, " +
                        "PRIMARY KEY(`cloudBusinessId`, `seq`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_remote_catalog_changes_cloudBusinessId_" +
                        "entityType_remoteEntityId` ON `remote_catalog_changes` " +
                        "(`cloudBusinessId`, `entityType`, `remoteEntityId`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `catalog_sync_links` (" +
                        "`localBusinessId` TEXT NOT NULL, `cloudBusinessId` TEXT NOT NULL, " +
                        "`entityType` TEXT NOT NULL, `localEntityId` TEXT NOT NULL, " +
                        "`remoteEntityId` TEXT NOT NULL, `remoteVersion` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`localBusinessId`, `entityType`, `localEntityId`), " +
                        "FOREIGN KEY(`localBusinessId`) REFERENCES `businesses`(`businessId`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_catalog_sync_links_" +
                        "cloudBusinessId_entityType_remoteEntityId` ON `catalog_sync_links` " +
                        "(`cloudBusinessId`, `entityType`, `remoteEntityId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_catalog_sync_links_localBusinessId` " +
                        "ON `catalog_sync_links` (`localBusinessId`)",
                )
                installPostingPersistenceInvariants(db)
            }
        }

        /**
         * v19 -> v20: fija de forma durable el tenant cloud de cada negocio local. Las filas
         * históricas quedan deliberadamente sin destino: SQL no puede demostrar a qué enlace
         * de DataStore salieron. Solo la transacción explícita de primer enlace puede fijar
         * operaciones PENDING que nunca consumieron un intento.
         */
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `outbox_operations` ADD COLUMN `targetCloudBusinessId` TEXT",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_outbox_operations_" +
                        "targetCloudBusinessId_status_nextAttemptAt` ON `outbox_operations` " +
                        "(`targetCloudBusinessId`, `status`, `nextAttemptAt`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `cloud_business_bindings` (" +
                        "`localBusinessId` TEXT NOT NULL, `cloudBusinessId` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `boundLegacyOperationCount` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`localBusinessId`), FOREIGN KEY(`localBusinessId`) REFERENCES " +
                        "`businesses`(`businessId`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_cloud_business_bindings_" +
                        "cloudBusinessId` ON `cloud_business_bindings` (`cloudBusinessId`)",
                )
                installCloudBusinessBindingInvariants(db)
                installPostingPersistenceInvariants(db)
            }
        }

        /**
         * v20 -> v21: añade carritos/ventas locales y enlaza sus salidas al libro append-only.
         * Las filas históricas conservan identidad, decimales y claves sin reinterpretación.
         */
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sales` (" +
                        "`saleId` TEXT NOT NULL, `businessId` TEXT NOT NULL, " +
                        "`status` TEXT NOT NULL, `currencyCode` TEXT NOT NULL, " +
                        "`subtotalMinorUnits` INTEGER NOT NULL, " +
                        "`discountMinorUnits` INTEGER NOT NULL, `taxMinorUnits` INTEGER NOT NULL, " +
                        "`totalMinorUnits` INTEGER NOT NULL, `contentHash` TEXT NOT NULL, " +
                        "`draftSlot` TEXT, `checkoutIdempotencyKey` TEXT, " +
                        "`version` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, `postedAt` INTEGER, " +
                        "PRIMARY KEY(`saleId`), FOREIGN KEY(`businessId`) REFERENCES " +
                        "`businesses`(`businessId`) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sales_businessId_status` ON " +
                        "`sales` (`businessId`, `status`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_sales_draftSlot` ON " +
                        "`sales` (`draftSlot`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_sales_checkoutIdempotencyKey` ON " +
                        "`sales` (`checkoutIdempotencyKey`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sale_lines` (" +
                        "`saleLineId` TEXT NOT NULL, `saleId` TEXT NOT NULL, " +
                        "`productId` TEXT NOT NULL, `unitId` TEXT NOT NULL, " +
                        "`locationId` TEXT NOT NULL, `position` INTEGER NOT NULL, " +
                        "`productNameSnapshot` TEXT NOT NULL, `unitCodeSnapshot` TEXT NOT NULL, " +
                        "`locationNameSnapshot` TEXT NOT NULL, `barcodeSnapshot` TEXT, " +
                        "`quantity` TEXT NOT NULL, `unitPriceMinorUnits` INTEGER, " +
                        "`discountMinorUnits` INTEGER NOT NULL, `taxMinorUnits` INTEGER NOT NULL, " +
                        "`lineTotalMinorUnits` INTEGER, `currencyCode` TEXT NOT NULL, " +
                        "PRIMARY KEY(`saleLineId`), FOREIGN KEY(`saleId`) REFERENCES " +
                        "`sales`(`saleId`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`productId`) REFERENCES `products`(`productId`) " +
                        "ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`unitId`) " +
                        "REFERENCES `units`(`unitId`) ON UPDATE NO ACTION ON DELETE RESTRICT, " +
                        "FOREIGN KEY(`locationId`) REFERENCES " +
                        "`inventory_locations`(`locationId`) ON UPDATE NO ACTION ON DELETE RESTRICT)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_sale_lines_saleId_position` ON " +
                        "`sale_lines` (`saleId`, `position`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_sale_lines_saleId_productId_locationId` " +
                        "ON `sale_lines` (`saleId`, `productId`, `locationId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sale_lines_productId` ON " +
                        "`sale_lines` (`productId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sale_lines_unitId` ON " +
                        "`sale_lines` (`unitId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sale_lines_locationId` ON " +
                        "`sale_lines` (`locationId`)",
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `stock_movements_new` (" +
                        "`movementId` TEXT NOT NULL, `businessId` TEXT NOT NULL, " +
                        "`purchaseId` TEXT, `purchaseLineId` TEXT, `saleId` TEXT, " +
                        "`saleLineId` TEXT, `productId` TEXT NOT NULL, `locationId` TEXT NOT NULL, " +
                        "`type` TEXT NOT NULL, `quantityDelta` TEXT NOT NULL, `unitCost` TEXT, " +
                        "`currencyCode` TEXT NOT NULL, `idempotencyKey` TEXT NOT NULL, " +
                        "`occurredAt` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`movementId`), FOREIGN KEY(`businessId`) REFERENCES " +
                        "`businesses`(`businessId`) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`purchaseId`) REFERENCES `purchases`(`purchaseId`) " +
                        "ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`purchaseLineId`) " +
                        "REFERENCES `purchase_lines`(`purchaseLineId`) ON UPDATE NO ACTION " +
                        "ON DELETE RESTRICT, FOREIGN KEY(`saleId`) REFERENCES `sales`(`saleId`) " +
                        "ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`saleLineId`) " +
                        "REFERENCES `sale_lines`(`saleLineId`) ON UPDATE NO ACTION ON DELETE RESTRICT, " +
                        "FOREIGN KEY(`productId`) REFERENCES `products`(`productId`) " +
                        "ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`locationId`) " +
                        "REFERENCES `inventory_locations`(`locationId`) ON UPDATE NO ACTION " +
                        "ON DELETE RESTRICT)",
                )
                db.execSQL(
                    "INSERT INTO `stock_movements_new` (`movementId`, `businessId`, `purchaseId`, " +
                        "`purchaseLineId`, `saleId`, `saleLineId`, `productId`, `locationId`, " +
                        "`type`, `quantityDelta`, `unitCost`, `currencyCode`, `idempotencyKey`, " +
                        "`occurredAt`, `createdAt`) SELECT `movementId`, `businessId`, `purchaseId`, " +
                        "`purchaseLineId`, NULL, NULL, `productId`, `locationId`, `type`, " +
                        "`quantityDelta`, `unitCost`, `currencyCode`, `idempotencyKey`, " +
                        "`occurredAt`, `createdAt` FROM `stock_movements`",
                )
                db.execSQL("DROP TABLE `stock_movements`")
                db.execSQL("ALTER TABLE `stock_movements_new` RENAME TO `stock_movements`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_stock_movements_idempotencyKey` " +
                        "ON `stock_movements` (`idempotencyKey`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_stock_movements_businessId_productId_" +
                        "locationId_occurredAt` ON `stock_movements` " +
                        "(`businessId`, `productId`, `locationId`, `occurredAt`)",
                )
                listOf("purchaseId", "purchaseLineId", "saleId", "saleLineId", "productId", "locationId")
                    .forEach { column ->
                        db.execSQL(
                            "CREATE INDEX IF NOT EXISTS `index_stock_movements_$column` ON " +
                                "`stock_movements` (`$column`)",
                        )
                    }
                installPostingPersistenceInvariants(db)
                installSalesPersistenceInvariants(db)
            }
        }

        /**
         * v21 -> v22: añade un precio de venta monetario opcional al producto. Las filas
         * existentes conservan todos sus datos y reciben NULL/NULL hasta que una persona fija
         * el precio; no se infiere precio de venta desde el costo promedio.
         */
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `products` ADD COLUMN `salePriceMinorUnits` INTEGER")
                db.execSQL("ALTER TABLE `products` ADD COLUMN `salePriceCurrencyCode` TEXT")
                installPostingPersistenceInvariants(db)
                installSalesPersistenceInvariants(db)
                installCloudBusinessBindingInvariants(db)
            }
        }

        /**
         * v22 -> v23: alinea los índices con los filtros y órdenes de las lecturas que crecen
         * sin límite (borradores, compras, ventas, auditoría, movimientos y pull remoto). No
         * reescribe tablas ni filas. Los índices cortos reemplazados siguen cubiertos por el
         * prefijo de sus sucesores; el índice de movimientos remotos era redundante con la PK.
         */
        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                requireV23TenantGraphsAreConsistent(db)
                listOf(
                    "index_invoice_drafts_businessId",
                    "index_invoice_drafts_businessId_status",
                    "index_purchases_businessId_status",
                    "index_sales_businessId_status",
                    "index_audit_events_businessId_occurredAt",
                    "index_audit_events_purchaseId",
                    "index_audit_events_entityType_entityId",
                    "index_outbox_operations_purchaseId",
                    "index_remote_catalog_changes_cloudBusinessId_entityType_remoteEntityId",
                    "index_stock_movements_purchaseId",
                    "index_stock_movements_saleId",
                    "index_remote_movement_summaries_cloudBusinessId_seq",
                ).forEach { index -> db.execSQL("DROP INDEX IF EXISTS `$index`") }

                listOf(
                    "CREATE INDEX IF NOT EXISTS `index_invoice_drafts_businessId_updatedAt_draftId` " +
                        "ON `invoice_drafts` (`businessId`, `updatedAt`, `draftId`)",
                    "CREATE INDEX IF NOT EXISTS `index_invoice_drafts_businessId_status_" +
                        "updatedAt_draftId` ON `invoice_drafts` " +
                        "(`businessId`, `status`, `updatedAt`, `draftId`)",
                    "CREATE INDEX IF NOT EXISTS `index_purchases_businessId_createdAt_purchaseId` " +
                        "ON `purchases` (`businessId`, `createdAt`, `purchaseId`)",
                    "CREATE INDEX IF NOT EXISTS `index_purchases_businessId_status_" +
                        "createdAt_purchaseId` ON `purchases` " +
                        "(`businessId`, `status`, `createdAt`, `purchaseId`)",
                    "CREATE INDEX IF NOT EXISTS `index_sales_businessId_status_postedAt_saleId` " +
                        "ON `sales` (`businessId`, `status`, `postedAt`, `saleId`)",
                    "CREATE INDEX IF NOT EXISTS `index_audit_events_businessId_occurredAt_" +
                        "auditEventId` ON `audit_events` " +
                        "(`businessId`, `occurredAt`, `auditEventId`)",
                    "CREATE INDEX IF NOT EXISTS `index_audit_events_purchaseId_occurredAt_" +
                        "auditEventId` ON `audit_events` " +
                        "(`purchaseId`, `occurredAt`, `auditEventId`)",
                    "CREATE INDEX IF NOT EXISTS `index_audit_events_businessId_entityType_" +
                        "entityId_occurredAt_auditEventId` ON `audit_events` " +
                        "(`businessId`, `entityType`, `entityId`, `occurredAt`, `auditEventId`)",
                    "CREATE INDEX IF NOT EXISTS `index_outbox_operations_purchaseId_createdAt_" +
                        "operationId` ON `outbox_operations` " +
                        "(`purchaseId`, `createdAt`, `operationId`)",
                    "CREATE INDEX IF NOT EXISTS `index_remote_catalog_changes_cloudBusinessId_" +
                        "applicationStatus_seq` ON `remote_catalog_changes` " +
                        "(`cloudBusinessId`, `applicationStatus`, `seq`)",
                    "CREATE INDEX IF NOT EXISTS `index_remote_catalog_changes_cloudBusinessId_" +
                        "entityType_remoteEntityId_applicationStatus_seq` " +
                        "ON `remote_catalog_changes` (`cloudBusinessId`, `entityType`, " +
                        "`remoteEntityId`, `applicationStatus`, `seq`)",
                    "CREATE INDEX IF NOT EXISTS `index_stock_movements_businessId_productId_" +
                        "occurredAt_createdAt_movementId` ON `stock_movements` " +
                        "(`businessId`, `productId`, `occurredAt`, `createdAt`, `movementId`)",
                    "CREATE INDEX IF NOT EXISTS `index_stock_movements_purchaseId_occurredAt_" +
                        "createdAt_movementId` ON `stock_movements` " +
                        "(`purchaseId`, `occurredAt`, `createdAt`, `movementId`)",
                    "CREATE INDEX IF NOT EXISTS `index_stock_movements_saleId_occurredAt_" +
                        "createdAt_movementId` ON `stock_movements` " +
                        "(`saleId`, `occurredAt`, `createdAt`, `movementId`)",
                    "CREATE INDEX IF NOT EXISTS `index_inventory_balances_businessId_locationId_" +
                        "productId` ON `inventory_balances` " +
                        "(`businessId`, `locationId`, `productId`)",
                ).forEach { statement -> db.execSQL(statement) }

                installPostingPersistenceInvariants(db)
                installSalesPersistenceInvariants(db)
                installCloudBusinessBindingInvariants(db)
            }
        }

        /**
         * v23 -> v24: conserva un recibo de cada publicación capturada. Es una tabla aditiva y
         * vacía para filas legacy: no inventa intenciones APPEND/REPLACE que el esquema anterior
         * nunca registró. Los nuevos recibos se escriben atómicamente junto con `invoice_images`.
         */
        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `captured_page_publications` (" +
                        "`imageId` TEXT NOT NULL, " +
                        "`draftId` TEXT NOT NULL, " +
                        "`businessId` TEXT NOT NULL, " +
                        "`intentKind` TEXT NOT NULL, " +
                        "`replaceTargetImageId` TEXT, " +
                        "`replacedFilePath` TEXT, " +
                        "`filePath` TEXT NOT NULL, " +
                        "`sha256` TEXT NOT NULL, " +
                        "`mimeType` TEXT NOT NULL, " +
                        "`widthPx` INTEGER NOT NULL, " +
                        "`heightPx` INTEGER NOT NULL, " +
                        "`fileSizeBytes` INTEGER NOT NULL, " +
                        "`rotationDegrees` INTEGER NOT NULL, " +
                        "`publishedPageIndex` INTEGER NOT NULL, " +
                        "`publishedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`imageId`), " +
                        "FOREIGN KEY(`draftId`) REFERENCES `invoice_drafts`(`draftId`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`businessId`) REFERENCES `businesses`(`businessId`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_captured_page_publications_draftId` " +
                        "ON `captured_page_publications` (`draftId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_captured_page_publications_businessId` " +
                        "ON `captured_page_publications` (`businessId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_invoice_images_filePath` " +
                        "ON `invoice_images` (`filePath`)",
                )
                installPostingPersistenceInvariants(db)
                installSalesPersistenceInvariants(db)
                installCloudBusinessBindingInvariants(db)
            }
        }

        /**
         * v24 -> v25: cubre la muestra de salud de la outbox fijada a su tenant cloud. El orden
         * mantiene primero los predicados de igualdad y deja `createdAt` al final para resolver
         * MIN desde el índice sin materializar payloads ni recorrer la tabla.
         */
        val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_outbox_operations_targetCloudBusinessId_status_completedAt_createdAt` " +
                        "ON `outbox_operations` (`targetCloudBusinessId`, `status`, " +
                        "`completedAt`, `createdAt`)",
                )
            }
        }

        /** v25 -> v26: cursor independiente para el stream append-only de inventario/ventas. */
        val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `remote_sync_states` ADD COLUMN " +
                        "`inventorySeq` INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE `remote_sync_states` ADD COLUMN `inventoryPulledAt` INTEGER",
                )
                installSalesPersistenceInvariants(db)
            }
        }

        /** v26 -> v27: cuenta por cobrar por venta y pagos append-only con transición CAS. */
        val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `debts` (" +
                        "`debtId` TEXT NOT NULL, `businessId` TEXT NOT NULL, " +
                        "`saleId` TEXT NOT NULL, `debtorName` TEXT NOT NULL, " +
                        "`normalizedDebtorName` TEXT NOT NULL, `currencyCode` TEXT NOT NULL, " +
                        "`originalAmountMinorUnits` INTEGER NOT NULL, " +
                        "`balanceMinorUnits` INTEGER NOT NULL, `status` TEXT NOT NULL, " +
                        "`dueAt` INTEGER, `version` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "`paidAt` INTEGER, PRIMARY KEY(`debtId`), " +
                        "FOREIGN KEY(`businessId`) REFERENCES `businesses`(`businessId`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(`saleId`) REFERENCES `sales`(`saleId`) " +
                        "ON UPDATE NO ACTION ON DELETE RESTRICT)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_debts_saleId` ON `debts` (`saleId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_debts_businessId_status_updatedAt_debtId` " +
                        "ON `debts` (`businessId`, `status`, `updatedAt`, `debtId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_debts_businessId_normalizedDebtorName_" +
                        "status_updatedAt_debtId` ON `debts` (`businessId`, " +
                        "`normalizedDebtorName`, `status`, `updatedAt`, `debtId`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `debt_payments` (" +
                        "`paymentId` TEXT NOT NULL, `debtId` TEXT NOT NULL, " +
                        "`businessId` TEXT NOT NULL, `currencyCode` TEXT NOT NULL, " +
                        "`amountMinorUnits` INTEGER NOT NULL, `method` TEXT NOT NULL, " +
                        "`note` TEXT, `reference` TEXT, `expectedDebtVersion` INTEGER NOT NULL, " +
                        "`balanceAfterMinorUnits` INTEGER NOT NULL, " +
                        "`idempotencyKey` TEXT NOT NULL, `occurredAt` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`paymentId`), " +
                        "FOREIGN KEY(`debtId`) REFERENCES `debts`(`debtId`) " +
                        "ON UPDATE NO ACTION ON DELETE RESTRICT, " +
                        "FOREIGN KEY(`businessId`) REFERENCES `businesses`(`businessId`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_debt_payments_debtId_" +
                        "expectedDebtVersion` ON `debt_payments` " +
                        "(`debtId`, `expectedDebtVersion`)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_debt_payments_idempotencyKey` " +
                        "ON `debt_payments` (`idempotencyKey`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_debt_payments_businessId_occurredAt_" +
                        "paymentId` ON `debt_payments` " +
                        "(`businessId`, `occurredAt`, `paymentId`)",
                )
                installPostingPersistenceInvariants(db)
                installSalesPersistenceInvariants(db)
                installDebtPersistenceInvariants(db)
                installCloudBusinessBindingInvariants(db)
            }
        }

        /**
         * No repara ni borra una relación legacy dudosa. Si una v22 contiene referencias válidas
         * para sus FKs simples pero cruzadas entre negocios, se aborta antes del primer DDL; Room
         * conserva la base v22 y el mensaje no expone ningún identificador ni dato comercial.
         */
        private fun requireV23TenantGraphsAreConsistent(db: SupportSQLiteDatabase) {
            val hasForeignKeyViolation = db.query("PRAGMA foreign_key_check").use { cursor ->
                cursor.moveToFirst()
            }
            check(!hasForeignKeyViolation) { "v23 foreign key preflight failed" }
            val hasMismatch = db.query(
                "SELECT EXISTS(" +
                    "SELECT 1 FROM `products` p WHERE " +
                    "NOT EXISTS (SELECT 1 FROM `units` u WHERE u.`unitId` = p.`unitId` " +
                    "AND u.`businessId` = p.`businessId`) OR " +
                    "(p.`purchaseUnitId` IS NOT NULL AND NOT EXISTS (SELECT 1 FROM `units` pu " +
                    "WHERE pu.`unitId` = p.`purchaseUnitId` " +
                    "AND pu.`businessId` = p.`businessId`)) OR " +
                    "(p.`locationId` IS NOT NULL AND NOT EXISTS (SELECT 1 FROM " +
                    "`inventory_locations` l WHERE l.`locationId` = p.`locationId` " +
                    "AND l.`businessId` = p.`businessId`)) " +
                    "UNION ALL SELECT 1 FROM `supplier_product_aliases` a WHERE " +
                    "NOT EXISTS (SELECT 1 FROM `suppliers` s WHERE " +
                    "s.`supplierId` = a.`supplierId` AND s.`businessId` = a.`businessId`) OR " +
                    "NOT EXISTS (SELECT 1 FROM `products` p WHERE " +
                    "p.`productId` = a.`productId` AND p.`businessId` = a.`businessId`) " +
                    "UNION ALL SELECT 1 FROM `invoice_drafts` d WHERE d.`supplierId` IS NOT NULL " +
                    "AND NOT EXISTS (SELECT 1 FROM `suppliers` s WHERE " +
                    "s.`supplierId` = d.`supplierId` AND s.`businessId` = d.`businessId`) " +
                    "UNION ALL SELECT 1 FROM `invoice_images` i WHERE " +
                    "NOT EXISTS (SELECT 1 FROM `invoice_drafts` d WHERE " +
                    "d.`draftId` = i.`draftId` AND d.`businessId` = i.`businessId`) " +
                    "UNION ALL SELECT 1 FROM `invoice_lines` l WHERE " +
                    "NOT EXISTS (SELECT 1 FROM `invoice_drafts` d WHERE " +
                    "d.`draftId` = l.`draftId` AND d.`businessId` = l.`businessId`) OR " +
                    "(l.`productId` IS NOT NULL AND NOT EXISTS (SELECT 1 FROM `products` p " +
                    "WHERE p.`productId` = l.`productId` " +
                    "AND p.`businessId` = l.`businessId`)) OR " +
                    "(l.`unitId` IS NOT NULL AND NOT EXISTS (SELECT 1 FROM `units` u WHERE " +
                    "u.`unitId` = l.`unitId` AND u.`businessId` = l.`businessId`)) LIMIT 1)",
            ).use { cursor ->
                check(cursor.moveToFirst()) { "v23 tenant graph preflight failed" }
                cursor.getInt(0) != 0
            }
            check(!hasMismatch) { "v23 tenant graph preflight failed" }
        }

        fun build(
            context: Context,
            openHelperFactory: SupportSQLiteOpenHelper.Factory,
        ): FacturaStockDatabase = buildNamed(context, openHelperFactory, NAME)

        /** Builder aislado para validación/migración en un nombre que nunca sea [NAME]. */
        internal fun buildNamed(
            context: Context,
            openHelperFactory: SupportSQLiteOpenHelper.Factory,
            databaseName: String,
        ): FacturaStockDatabase {
            require(databaseName.isNotBlank() && '/' !in databaseName && '\\' !in databaseName)
            return Room.databaseBuilder(context, FacturaStockDatabase::class.java, databaseName)
                .openHelperFactory(openHelperFactory)
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_15,
                    MIGRATION_15_16,
                    MIGRATION_16_17,
                    MIGRATION_17_18,
                    MIGRATION_18_19,
                    MIGRATION_19_20,
                    MIGRATION_20_21,
                    MIGRATION_21_22,
                    MIGRATION_22_23,
                    MIGRATION_23_24,
                    MIGRATION_24_25,
                    MIGRATION_25_26,
                    MIGRATION_26_27,
                )
                .addCallback(postingPersistenceCallback)
                // Sin fallbackToDestructiveMigration: la migración destructiva está prohibida.
                .build()
        }

        private data class CatalogKeyRow(
            val id: String,
            val businessId: String,
            val raw: String?,
            val canonical: String?,
        )

        private fun readCatalogKeys(
            db: SupportSQLiteDatabase,
            table: String,
            idColumn: String,
            valueColumn: String,
            canonicalize: (String?) -> String?,
        ): List<CatalogKeyRow> = buildList {
            db.query(
                "SELECT `$idColumn`, `businessId`, `$valueColumn` FROM `$table`",
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val raw = if (cursor.isNull(2)) null else cursor.getString(2)
                    add(
                        CatalogKeyRow(
                            id = cursor.getString(0),
                            businessId = cursor.getString(1),
                            raw = raw,
                            canonical = canonicalize(raw),
                        ),
                    )
                }
            }
        }

        /** Forma histórica usada por v11: recorta bordes y conserva Unicode sin reinterpretarlo. */
        private fun canonicalizeLegacyBarcode(value: String?): String? =
            value?.trim()?.takeIf(String::isNotEmpty)

        private fun resolveCanonicalCollisions(rows: List<CatalogKeyRow>): List<CatalogKeyRow> {
            val winners = rows
                .filter { it.canonical != null }
                .groupBy { it.businessId to it.canonical }
                .mapValues { (_, candidates) -> candidates.minOf { it.id } }
            return rows.map { row ->
                val key = row.businessId to row.canonical
                if (row.canonical != null && winners[key] != row.id) row.copy(canonical = null)
                else row
            }
        }

        private fun updateCanonicalKeys(
            db: SupportSQLiteDatabase,
            table: String,
            idColumn: String,
            valueColumn: String,
            rows: List<CatalogKeyRow>,
        ) {
            // Primero libera las claves de los perdedores; solo después escribe formas canónicas.
            // Así el índice UNIQUE nunca observa transitoriamente ganador y perdedor con la
            // misma clave, independientemente del orden que devuelva SELECT.
            rows.filter { it.raw != it.canonical }
                .sortedBy { if (it.canonical == null) 0 else 1 }
                .forEach { row ->
                    db.execSQL(
                        "UPDATE `$table` SET `$valueColumn` = ? WHERE `$idColumn` = ?",
                        arrayOf(row.canonical, row.id),
                    )
                }
        }
    }
}
