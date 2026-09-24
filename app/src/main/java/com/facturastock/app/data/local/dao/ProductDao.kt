package com.facturastock.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.facturastock.app.data.local.entity.ProductEntity
import com.facturastock.app.data.local.entity.InvoiceLinesEditEntity
import com.facturastock.app.data.local.entity.PreparedPurchaseEntity
import kotlinx.coroutines.flow.Flow

/** Fila cruda: SQLite no suma ni convierte los decimales de inventario. */
data class ProductProfitReadRow(
    val businessId: String,
    val productId: String,
    val productName: String,
    val sku: String?,
    val productStatus: String,
    val productVersion: Long,
    val unitCode: String,
    val salePriceMinorUnits: Long?,
    val salePriceCurrencyCode: String?,
    val locationId: String?,
    val quantityOnHand: String?,
    val averageUnitCost: String?,
    val inventoryCurrencyCode: String?,
)

@Dao
interface ProductDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(product: ProductEntity)

    /** Inserción por lote para importaciones offline y fixtures grandes sin 5 000 round-trips. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(products: List<ProductEntity>)

    /**
     * CAS por versión optimista: escribe solo si la fila sigue en la versión que el llamador
     * leyó y la incrementa en la misma sentencia. Cero filas significa que otra edición ganó
     * la carrera; el repositorio lo traduce a "obsoleto", nunca a error. `businessId` y
     * `createdAt` son inmutables y no forman parte del SET.
     */
    @Query(
        "UPDATE products SET unitId = :unitId, name = :name, normalizedName = :normalizedName, " +
        "locationId = :locationId, sku = :sku, barcode = :barcode, " +
            "purchaseUnitId = :purchaseUnitId, purchaseFactor = :purchaseFactor, " +
            "salePriceMinorUnits = :salePriceMinorUnits, " +
            "salePriceCurrencyCode = :salePriceCurrencyCode, " +
            "status = :status, version = version + 1, updatedAt = :updatedAt " +
            "WHERE productId = :productId AND version = :expectedVersion",
    )
    suspend fun update(
        productId: String,
        unitId: String,
        name: String,
        normalizedName: String,
        locationId: String?,
        sku: String?,
        barcode: String?,
        purchaseUnitId: String?,
        purchaseFactor: String?,
        salePriceMinorUnits: Long?,
        salePriceCurrencyCode: String?,
        status: String,
        expectedVersion: Long,
        updatedAt: Long,
    ): Int

    /** Aplicación de una versión cloud ya conciliada; nunca genera otra operación de outbox. */
    @Query(
        "UPDATE products SET unitId = :unitId, name = :name, normalizedName = :normalizedName, " +
        "locationId = :locationId, sku = :sku, barcode = :barcode, " +
            "purchaseUnitId = :purchaseUnitId, purchaseFactor = :purchaseFactor, " +
            "salePriceMinorUnits = :salePriceMinorUnits, " +
            "salePriceCurrencyCode = :salePriceCurrencyCode, " +
            "status = :status, version = :remoteVersion, createdAt = :createdAt, " +
            "updatedAt = :updatedAt " +
            "WHERE productId = :productId AND businessId = :businessId " +
            "AND version = :expectedLocalVersion",
    )
    suspend fun applyRemoteVersion(
        productId: String,
        businessId: String,
        unitId: String,
        name: String,
        normalizedName: String,
        locationId: String?,
        sku: String?,
        barcode: String?,
        purchaseUnitId: String?,
        purchaseFactor: String?,
        salePriceMinorUnits: Long?,
        salePriceCurrencyCode: String?,
        status: String,
        expectedLocalVersion: Long,
        remoteVersion: Long,
        createdAt: Long,
        updatedAt: Long,
    ): Int

    /** KEEP_LOCAL rebasa la versión causal sin cambiar el contenido elegido por la persona. */
    @Query(
        "UPDATE products SET version = :rebasedVersion, updatedAt = :updatedAt " +
            "WHERE productId = :productId AND businessId = :businessId " +
            "AND version = :expectedLocalVersion",
    )
    suspend fun rebaseVersion(
        productId: String,
        businessId: String,
        expectedLocalVersion: Long,
        rebasedVersion: Long,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE products SET salePriceMinorUnits = :salePriceMinorUnits, " +
            "salePriceCurrencyCode = :salePriceCurrencyCode, version = version + 1, " +
            "updatedAt = :updatedAt WHERE productId = :productId AND businessId = :businessId " +
            "AND status = 'ACTIVE' AND version = :expectedVersion",
    )
    suspend fun updateSalePrice(
        productId: String,
        businessId: String,
        expectedVersion: Long,
        salePriceMinorUnits: Long,
        salePriceCurrencyCode: String,
        updatedAt: Long,
    ): Int

    @Query("SELECT * FROM products WHERE productId = :productId")
    suspend fun findById(productId: String): ProductEntity?

    /** Incluye borradores y alias: no se permite perder enlaces mediante SET_NULL/CASCADE. */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM invoice_lines WHERE productId = :productId) OR " +
            "EXISTS(SELECT 1 FROM purchase_lines WHERE productId = :productId) OR " +
            "EXISTS(SELECT 1 FROM sale_lines WHERE productId = :productId) OR " +
            "EXISTS(SELECT 1 FROM stock_movements WHERE productId = :productId) OR " +
            "EXISTS(SELECT 1 FROM supplier_product_aliases WHERE productId = :productId)",
    )
    suspend fun hasDeletionReferences(productId: String): Boolean

    @Query(
        "SELECT EXISTS(SELECT 1 FROM catalog_sync_links " +
            "WHERE localBusinessId = :businessId) OR " +
            "EXISTS(SELECT 1 FROM remote_catalog_changes WHERE entityType = 'PRODUCT' " +
            "AND (localEntityId = :productId OR remoteEntityId = :productId)) OR " +
            "EXISTS(SELECT 1 FROM remote_movement_summaries WHERE productId = :productId)",
    )
    suspend fun hasRemoteDeletionReferences(businessId: String, productId: String): Boolean

    /** Los codecs guardan UUID como UTF-8; el repositorio confirma la referencia decodificada. */
    @Query(
        "SELECT e.* FROM invoice_line_edits e INNER JOIN invoice_drafts d " +
            "ON d.draftId = e.draftId WHERE d.businessId = :businessId " +
            "AND INSTR(e.payload, CAST(:productId AS BLOB)) > 0",
    )
    suspend fun listDeletionEditCandidates(businessId: String, productId: String): List<InvoiceLinesEditEntity>

    @Query(
        "SELECT p.* FROM prepared_purchases p INNER JOIN invoice_drafts d " +
            "ON d.draftId = p.draftId WHERE d.businessId = :businessId " +
            "AND INSTR(p.payload, CAST(:productId AS BLOB)) > 0",
    )
    suspend fun listDeletionPreparedCandidates(businessId: String, productId: String): List<PreparedPurchaseEntity>

    /** Sólo después de verificar exactamente todos los decimales cero en la misma transacción. */
    @Query("DELETE FROM inventory_balances WHERE businessId = :businessId AND productId = :productId")
    suspend fun deleteUnusedBalances(businessId: String, productId: String): Int

    @Query(
        "DELETE FROM products WHERE businessId = :businessId AND productId = :productId " +
            "AND version = :expectedVersion",
    )
    suspend fun deletePermanently(businessId: String, productId: String, expectedVersion: Long): Int

    /** Lectura acotada por las 100 líneas activas máximas de una revisión. */
    @Query("SELECT * FROM products WHERE productId IN (:productIds)")
    suspend fun findByIds(productIds: List<String>): List<ProductEntity>

    @Query(
        "SELECT CASE WHEN " +
            "EXISTS(SELECT 1 FROM units WHERE unitId = :unitId AND businessId = :businessId) " +
            "AND (:purchaseUnitId IS NULL OR EXISTS(SELECT 1 FROM units " +
            "WHERE unitId = :purchaseUnitId AND businessId = :businessId)) " +
            "AND (:locationId IS NULL OR EXISTS(SELECT 1 FROM inventory_locations " +
            "WHERE locationId = :locationId AND businessId = :businessId)) " +
            "THEN 1 ELSE 0 END",
    )
    suspend fun referencesBelongToBusiness(
        businessId: String,
        unitId: String,
        purchaseUnitId: String?,
        locationId: String?,
    ): Boolean

    @Query(
        "SELECT CASE WHEN " +
            "EXISTS(SELECT 1 FROM units WHERE unitId = :unitId AND businessId = :businessId " +
            "AND status = 'ACTIVE') " +
            "AND (:purchaseUnitId IS NULL OR EXISTS(SELECT 1 FROM units " +
            "WHERE unitId = :purchaseUnitId AND businessId = :businessId AND status = 'ACTIVE')) " +
            "AND (:locationId IS NULL OR EXISTS(SELECT 1 FROM inventory_locations " +
            "WHERE locationId = :locationId AND businessId = :businessId AND status = 'ACTIVE')) " +
            "THEN 1 ELSE 0 END",
    )
    suspend fun referencesAreActiveForCreate(
        businessId: String,
        unitId: String,
        purchaseUnitId: String?,
        locationId: String?,
    ): Boolean

    @Query("SELECT * FROM products WHERE businessId = :businessId AND barcode = :barcode")
    suspend fun findByBarcode(businessId: String, barcode: String): ProductEntity?

    @Query("SELECT * FROM products WHERE businessId = :businessId AND sku = :sku")
    suspend fun findBySku(businessId: String, sku: String): ProductEntity?

    @Query(
        "SELECT * FROM products " +
            "WHERE businessId = :businessId AND normalizedName = :normalizedName " +
            "ORDER BY name, productId",
    )
    suspend fun findByNormalizedName(businessId: String, normalizedName: String): List<ProductEntity>

    @Query(
        "SELECT * FROM products WHERE businessId = :businessId AND status = 'ACTIVE' " +
            "AND normalizedName LIKE :pattern ESCAPE '\\' " +
            "ORDER BY normalizedName, productId LIMIT :limit",
    )
    suspend fun searchActiveByName(
        businessId: String,
        pattern: String,
        limit: Int,
    ): List<ProductEntity>

    @Query(
        "SELECT * FROM products " +
            "WHERE businessId = :businessId AND (:status IS NULL OR status = :status) AND (" +
            "normalizedName LIKE :pattern ESCAPE '\\' OR " +
            "LOWER(sku) LIKE :pattern ESCAPE '\\' OR " +
            "barcode LIKE :pattern ESCAPE '\\') " +
            "ORDER BY normalizedName, productId LIMIT :limit OFFSET :offset",
    )
    suspend fun searchPage(
        businessId: String,
        pattern: String,
        status: String?,
        limit: Int,
        offset: Int,
    ): List<ProductEntity>

    @Query(
        "SELECT * FROM products " +
            "WHERE businessId = :businessId AND (:status IS NULL OR status = :status) AND (" +
            "normalizedName LIKE :pattern ESCAPE '\\' OR " +
            "LOWER(sku) LIKE :pattern ESCAPE '\\' OR " +
            "barcode LIKE :pattern ESCAPE '\\') " +
            "ORDER BY normalizedName, productId LIMIT :limit OFFSET :offset",
    )
    fun observeSearchPage(
        businessId: String,
        pattern: String,
        status: String?,
        limit: Int,
        offset: Int,
    ): Flow<List<ProductEntity>>

    @Query(
        "SELECT COUNT(*) FROM products " +
            "WHERE businessId = :businessId AND (:status IS NULL OR status = :status) AND (" +
            "normalizedName LIKE :pattern ESCAPE '\\' OR " +
            "LOWER(sku) LIKE :pattern ESCAPE '\\' OR " +
            "barcode LIKE :pattern ESCAPE '\\')",
    )
    suspend fun countSearch(businessId: String, pattern: String, status: String?): Int

    @Query(
        "SELECT COUNT(*) FROM products " +
            "WHERE businessId = :businessId AND (:status IS NULL OR status = :status) AND (" +
            "normalizedName LIKE :pattern ESCAPE '\\' OR " +
            "LOWER(sku) LIKE :pattern ESCAPE '\\' OR " +
            "barcode LIKE :pattern ESCAPE '\\')",
    )
    fun observeCountSearch(
        businessId: String,
        pattern: String,
        status: String?,
    ): Flow<Int>

    @Query("SELECT * FROM products WHERE businessId = :businessId ORDER BY name")
    fun observeForBusiness(businessId: String): Flow<List<ProductEntity>>

    @Query(
        "SELECT p.businessId, p.productId, p.name AS productName, p.sku, " +
            "p.status AS productStatus, p.version AS productVersion, u.code AS unitCode, " +
            "p.salePriceMinorUnits, p.salePriceCurrencyCode, b.locationId, " +
            "b.quantityOnHand, b.averageUnitCost, " +
            "b.currencyCode AS inventoryCurrencyCode FROM products p " +
            "INNER JOIN units u ON u.unitId = p.unitId AND u.businessId = p.businessId " +
            "LEFT JOIN inventory_balances b ON b.productId = p.productId " +
            "AND b.businessId = p.businessId WHERE p.businessId = :businessId " +
            "AND p.status = 'ACTIVE' " +
            "ORDER BY p.normalizedName ASC, p.productId ASC, b.locationId ASC",
    )
    fun observeProfitRows(businessId: String): Flow<List<ProductProfitReadRow>>

    @Query("SELECT * FROM products WHERE businessId = :businessId ORDER BY productId")
    suspend fun listForBusiness(businessId: String): List<ProductEntity>

    /**
     * Productos que pueden explicar una lectura del escáner: sin código ni SKU nunca coinciden.
     * SQLite filtra sin materializar el resto del catálogo; el orden no afecta a los consumidores.
     */
    @Query(
        "SELECT * FROM products WHERE businessId = :businessId " +
            "AND (barcode IS NOT NULL OR sku IS NOT NULL)",
    )
    suspend fun listScannerIdentityCandidates(businessId: String): List<ProductEntity>

    /** Códigos guardados de longitud acotada; un exacto truncado sólo compite con 1–2 dígitos más. */
    @Query(
        "SELECT * FROM products WHERE businessId = :businessId AND barcode IS NOT NULL " +
            "AND length(barcode) BETWEEN :minLength AND :maxLength ORDER BY productId",
    )
    suspend fun listByBarcodeLength(
        businessId: String,
        minLength: Int,
        maxLength: Int,
    ): List<ProductEntity>

    /**
     * [listByBarcodeLength] restringido a códigos que contienen la lectura como subsecuencia
     * (`%7%7%5%…%`). Un exacto sospechoso sólo compite con esos códigos; antes se leían y
     * convertían todos los GTIN del largo pedido en cada lectura del escáner.
     */
    @Query(
        "SELECT * FROM products WHERE businessId = :businessId AND barcode IS NOT NULL " +
            "AND length(barcode) BETWEEN :minLength AND :maxLength " +
            "AND barcode LIKE :subsequencePattern ORDER BY productId",
    )
    suspend fun listBarcodeSupersequences(
        businessId: String,
        minLength: Int,
        maxLength: Int,
        subsequencePattern: String,
    ): List<ProductEntity>

    @Query("SELECT COUNT(*) FROM products WHERE businessId = :businessId")
    suspend fun countForBusiness(businessId: String): Int

    @Query("SELECT productId FROM products WHERE businessId = :businessId AND productId IN (:ids)")
    suspend fun existingIdsForBusiness(businessId: String, ids: List<String>): List<String>

    /** Archivo/restauración con el mismo CAS y avance de versión que una edición normal. */
    @Query(
        "UPDATE products SET status = :status, version = version + 1, updatedAt = :updatedAt " +
            "WHERE productId = :productId AND status != :status AND version = :expectedVersion",
    )
    suspend fun setStatus(
        productId: String,
        status: String,
        expectedVersion: Long,
        updatedAt: Long,
    ): Int

    @Query("DELETE FROM products WHERE productId = :productId")
    suspend fun deleteById(productId: String): Int

    /**
     * Identidades del catálogo activo para enlazar productos remotos por nombre exacto en la
     * reconciliación diagnóstica. La normalización de enlace es la del dominio
     * (`normalizeProductName`), no la forma persistida de búsqueda.
     */
    @Query(
        "SELECT productId, name FROM products " +
            "WHERE businessId = :businessId AND status = 'ACTIVE' ORDER BY productId ASC",
    )
    suspend fun listActiveIdentities(businessId: String): List<ProductIdentityRow>
}

/** Proyección mínima de catálogo para el enlace exacto de la reconciliación. */
data class ProductIdentityRow(
    val productId: String,
    val name: String,
)

/**
 * Conveniencia entidad → CAS: la `version` que trae la entidad es la versión esperada (la que
 * el llamador leyó); el incremento ocurre en SQL. Room no enlaza campos de entidad en `@Query`,
 * así que la forma explícita vive en el DAO y esta extensión conserva la llamada natural.
 */
suspend fun ProductDao.updateCas(product: ProductEntity): Int = update(
    productId = product.productId,
    unitId = product.unitId,
    name = product.name,
    normalizedName = product.normalizedName,
    locationId = product.locationId,
    sku = product.sku,
    barcode = product.barcode,
    purchaseUnitId = product.purchaseUnitId,
    purchaseFactor = product.purchaseFactor,
    salePriceMinorUnits = product.salePriceMinorUnits,
    salePriceCurrencyCode = product.salePriceCurrencyCode,
    status = product.status,
    expectedVersion = product.version,
    updatedAt = product.updatedAt,
)
