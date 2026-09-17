package com.facturastock.app.data.local.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import com.facturastock.app.data.local.entity.SaleEntity
import com.facturastock.app.data.local.entity.PendingSaleCheckoutEntity
import com.facturastock.app.data.local.entity.SaleLineEntity
import kotlinx.coroutines.flow.Flow

data class SaleWithLines(
    @Embedded val sale: SaleEntity,
    @Relation(parentColumn = "saleId", entityColumn = "saleId")
    val lines: List<SaleLineEntity>,
    @Relation(parentColumn = "saleId", entityColumn = "saleId")
    val pendingCheckout: PendingSaleCheckoutEntity? = null,
)

data class PostedSaleSummaryRow(
    val saleId: String,
    val totalMinorUnits: Long,
    val currencyCode: String,
    val lineCount: Int,
    val postedAt: Long,
)

/**
 * Fila contable por línea y movimiento de salida. El `LEFT JOIN` es intencional: una ausencia
 * histórica debe llegar al dominio como costo no disponible, nunca convertirse en cero.
 */
data class PostedSaleProfitRow(
    val saleId: String,
    val totalMinorUnits: Long,
    val saleCurrencyCode: String,
    val postedAt: Long,
    val saleLineId: String,
    val productId: String,
    val linePosition: Int,
    val productNameSnapshot: String,
    val unitCodeSnapshot: String,
    val locationNameSnapshot: String,
    val lineCurrencyCode: String,
    val lineQuantity: String,
    val lineTotalMinorUnits: Long?,
    val lineTaxMinorUnits: Long,
    val movementId: String?,
    val movementQuantityDelta: String?,
    val movementUnitCost: String?,
    val movementCurrencyCode: String?,
)

/**
 * Cursor estable para recorrer ventas confirmadas sin `OFFSET`. Mantener solo la clave de la
 * cabecera evita materializar todas las líneas del periodo antes de empezar a calcularlo.
 */
data class PostedSaleProfitPageKey(
    val saleId: String,
    val postedAt: Long,
)

@Dao
interface SaleDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPendingCheckout(pending: PendingSaleCheckoutEntity)

    @Query("SELECT * FROM pending_sale_checkouts WHERE saleId = :saleId")
    suspend fun findPendingCheckout(saleId: String): PendingSaleCheckoutEntity?

    @Query("DELETE FROM pending_sale_checkouts WHERE saleId = :saleId")
    suspend fun deletePendingCheckout(saleId: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSale(sale: SaleEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLine(line: SaleLineEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLines(lines: List<SaleLineEntity>)

    @Update
    suspend fun updateLineEntity(line: SaleLineEntity): Int

    @Transaction
    @Query("SELECT * FROM sales WHERE saleId = :saleId")
    fun observeWithLines(saleId: String): Flow<SaleWithLines?>

    /** El indice de ventas aplica orden y limite; solo se cuentan las lineas de ventas visibles. */
    @Query(
        "SELECT s.saleId, s.totalMinorUnits, s.currencyCode, " +
            "(SELECT COUNT(*) FROM sale_lines l WHERE l.saleId = s.saleId) AS lineCount, " +
            "s.postedAt AS postedAt FROM sales s " +
            "WHERE s.businessId = :businessId AND s.status = 'POSTED' AND s.postedAt IS NOT NULL " +
            "AND EXISTS (SELECT 1 FROM sale_lines l WHERE l.saleId = s.saleId) " +
            "AND NOT EXISTS (SELECT 1 FROM sale_voids v WHERE v.saleId = s.saleId) " +
            "ORDER BY s.postedAt DESC, s.saleId DESC LIMIT :limit",
    )
    fun observeRecentPosted(businessId: String, limit: Int): Flow<List<PostedSaleSummaryRow>>

    @Query(
        "SELECT s.saleId, s.postedAt AS postedAt FROM sales s " +
            "WHERE s.businessId = :businessId AND s.status = 'POSTED' " +
            "AND s.postedAt IS NOT NULL AND s.postedAt >= :startInclusive " +
            "AND s.postedAt < :endExclusive " +
            "AND EXISTS (SELECT 1 FROM sale_lines l WHERE l.saleId = s.saleId) " +
            "AND NOT EXISTS (SELECT 1 FROM sale_voids v WHERE v.saleId = s.saleId) " +
            "AND (:beforePostedAt IS NULL OR s.postedAt < :beforePostedAt OR " +
            "(s.postedAt = :beforePostedAt AND s.saleId < :beforeSaleId)) " +
            "ORDER BY s.postedAt DESC, s.saleId DESC LIMIT :limit",
    )
    fun observePostedProfitPageKeys(
        businessId: String,
        startInclusive: Long,
        endExclusive: Long,
        beforePostedAt: Long?,
        beforeSaleId: String?,
        limit: Int,
    ): Flow<List<PostedSaleProfitPageKey>>

    @Query(
        "SELECT s.saleId, s.postedAt AS postedAt FROM sales s " +
            "WHERE s.businessId = :businessId AND s.status = 'POSTED' " +
            "AND s.postedAt IS NOT NULL AND s.postedAt >= :startInclusive " +
            "AND s.postedAt < :endExclusive " +
            "AND EXISTS (SELECT 1 FROM sale_lines l WHERE l.saleId = s.saleId) " +
            "AND NOT EXISTS (SELECT 1 FROM sale_voids v WHERE v.saleId = s.saleId) " +
            "AND (:beforePostedAt IS NULL OR s.postedAt < :beforePostedAt OR " +
            "(s.postedAt = :beforePostedAt AND s.saleId < :beforeSaleId)) " +
            "ORDER BY s.postedAt DESC, s.saleId DESC LIMIT :limit",
    )
    suspend fun listPostedProfitPageKeys(
        businessId: String,
        startInclusive: Long,
        endExclusive: Long,
        beforePostedAt: Long?,
        beforeSaleId: String?,
        limit: Int,
    ): List<PostedSaleProfitPageKey>

    @Query(
        "SELECT s.saleId, s.totalMinorUnits, s.currencyCode AS saleCurrencyCode, " +
            "s.postedAt AS postedAt, l.saleLineId, l.productId, " +
            "l.position AS linePosition, l.productNameSnapshot, l.unitCodeSnapshot, " +
            "l.locationNameSnapshot, l.currencyCode AS lineCurrencyCode, " +
            "l.quantity AS lineQuantity, " +
            "l.lineTotalMinorUnits, l.taxMinorUnits AS lineTaxMinorUnits, " +
            "m.movementId, m.quantityDelta AS movementQuantityDelta, " +
            "m.unitCost AS movementUnitCost, m.currencyCode AS movementCurrencyCode " +
            "FROM sales s INNER JOIN sale_lines l ON l.saleId = s.saleId " +
            "LEFT JOIN stock_movements m ON m.saleId = s.saleId " +
            "AND m.saleLineId = l.saleLineId AND m.type = 'SALE' " +
            "WHERE s.businessId = :businessId AND s.status = 'POSTED' " +
            "AND s.postedAt IS NOT NULL AND s.saleId IN (:saleIds) " +
            "AND NOT EXISTS (SELECT 1 FROM sale_voids v WHERE v.saleId = s.saleId) " +
            "ORDER BY s.postedAt DESC, s.saleId DESC, l.position ASC, l.saleLineId ASC, " +
            "m.movementId ASC",
    )
    suspend fun listPostedProfitRowsForSales(
        businessId: String,
        saleIds: List<String>,
    ): List<PostedSaleProfitRow>

    @Transaction
    @Query("SELECT * FROM sales WHERE saleId = :saleId")
    suspend fun findWithLines(saleId: String): SaleWithLines?

    @Query("SELECT * FROM sales WHERE saleId = :saleId")
    suspend fun findSale(saleId: String): SaleEntity?

    @Transaction
    @Query(
        // `draftSlot` es unico e indexado; usarlo evita recorrer todas las ventas historicas del
        // negocio cada vez que se abre o reanuda el carrito.
        "SELECT * FROM sales WHERE draftSlot = (:businessId || ':' || :currencyCode) " +
            "AND businessId = :businessId AND currencyCode = :currencyCode " +
            "AND status = 'DRAFT' LIMIT 1",
    )
    suspend fun findActiveDraft(businessId: String, currencyCode: String): SaleWithLines?

    @Query("SELECT * FROM sales WHERE checkoutIdempotencyKey = :key")
    suspend fun findByCheckoutIdempotencyKey(key: String): SaleEntity?

    @Query("SELECT * FROM sale_lines WHERE saleLineId = :saleLineId")
    suspend fun findLine(saleLineId: String): SaleLineEntity?

    @Query(
        "SELECT * FROM sale_lines WHERE saleId = :saleId AND productId = :productId " +
            "AND locationId = :locationId LIMIT 1",
    )
    suspend fun findLineForProductLocation(
        saleId: String,
        productId: String,
        locationId: String,
    ): SaleLineEntity?

    @Query("SELECT COALESCE(MAX(position), -1) FROM sale_lines WHERE saleId = :saleId")
    suspend fun maxPosition(saleId: String): Int

    @Query(
        "UPDATE sale_lines SET productId = :productId, unitId = :unitId, " +
            "locationId = :locationId, productNameSnapshot = :productNameSnapshot, " +
            "unitCodeSnapshot = :unitCodeSnapshot, locationNameSnapshot = :locationNameSnapshot, " +
            "barcodeSnapshot = :barcodeSnapshot, quantity = :quantity, " +
            "unitPriceMinorUnits = :unitPriceMinorUnits, discountMinorUnits = :discountMinorUnits, " +
            "taxMinorUnits = :taxMinorUnits, lineTotalMinorUnits = :lineTotalMinorUnits, " +
            "currencyCode = :currencyCode WHERE saleLineId = :saleLineId AND saleId = :saleId",
    )
    suspend fun updateLine(
        saleLineId: String,
        saleId: String,
        productId: String,
        unitId: String,
        locationId: String,
        productNameSnapshot: String,
        unitCodeSnapshot: String,
        locationNameSnapshot: String,
        barcodeSnapshot: String?,
        quantity: String,
        unitPriceMinorUnits: Long?,
        discountMinorUnits: Long,
        taxMinorUnits: Long,
        lineTotalMinorUnits: Long?,
        currencyCode: String,
    ): Int

    @Query("DELETE FROM sale_lines WHERE saleLineId = :saleLineId AND saleId = :saleId")
    suspend fun deleteLine(saleId: String, saleLineId: String): Int

    @Query("DELETE FROM sale_lines WHERE saleId = :saleId")
    suspend fun deleteLines(saleId: String): Int

    @Query(
        "UPDATE sales SET subtotalMinorUnits = :subtotalMinorUnits, " +
            "discountMinorUnits = :discountMinorUnits, taxMinorUnits = :taxMinorUnits, " +
            "totalMinorUnits = :totalMinorUnits, contentHash = :contentHash, " +
            "version = version + 1, updatedAt = :updatedAt WHERE saleId = :saleId " +
            "AND businessId = :businessId AND status = 'DRAFT' AND version = :expectedVersion",
    )
    suspend fun updateDraftSummaryIfVersion(
        saleId: String,
        businessId: String,
        expectedVersion: Long,
        subtotalMinorUnits: Long,
        discountMinorUnits: Long,
        taxMinorUnits: Long,
        totalMinorUnits: Long,
        contentHash: String,
        updatedAt: Long,
    ): Int

    @Query(
        "UPDATE sales SET status = 'POSTED', draftSlot = NULL, " +
            "checkoutIdempotencyKey = :checkoutIdempotencyKey, " +
            "version = version + 1, postedAt = :postedAt, updatedAt = :postedAt " +
            "WHERE saleId = :saleId AND businessId = :businessId AND status = 'DRAFT' " +
            "AND version = :expectedVersion AND contentHash = :expectedContentHash",
    )
    suspend fun markPostedIfVersionAndHash(
        saleId: String,
        businessId: String,
        expectedVersion: Long,
        expectedContentHash: String,
        checkoutIdempotencyKey: String,
        postedAt: Long,
    ): Int
}
