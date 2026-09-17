package com.facturastock.app.data.local.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.facturastock.app.data.local.entity.InventoryBalanceEntity
import com.facturastock.app.data.local.entity.StockMovementEntity
import kotlinx.coroutines.flow.Flow

data class PurchaseMovementReadRow(
    val movementId: String,
    val purchaseId: String,
    val purchaseLineId: String,
    val productId: String,
    val productName: String,
    val locationId: String,
    val locationName: String,
    val type: String,
    val quantityDelta: String,
    val unitCost: String?,
    val currencyCode: String,
    val occurredAt: Long,
)

/** Fila plana, tenant-safe, para lista y posiciones de inventario. */
data class InventoryPositionReadRow(
    val businessId: String,
    val productId: String,
    val productName: String,
    val sku: String?,
    val productStatus: String,
    val unitCode: String,
    val unitSymbol: String?,
    val locationId: String,
    val locationName: String,
    val locationStatus: String,
    val quantityOnHand: String,
    val averageUnitCost: String,
    val currencyCode: String,
    val version: Long,
    val updatedAt: Long,
)

data class InventoryProductHeaderReadRow(
    val businessId: String,
    val productId: String,
    val productName: String,
    val sku: String?,
    val productStatus: String,
    val unitCode: String,
    val unitSymbol: String?,
)

/** Conserva el producto aunque todavía no tenga ninguna posición de inventario. */
data class InventoryListReadRow(
    @Embedded val product: InventoryProductHeaderReadRow,
    @Embedded val position: InventoryReadBalanceRow?,
)

/** Room deja todo este bloque en null cuando el LEFT JOIN no encuentra un saldo real. */
data class InventoryReadBalanceRow(
    val locationId: String,
    val locationName: String,
    val locationStatus: String,
    val quantityOnHand: String,
    val averageUnitCost: String,
    val currencyCode: String,
    val version: Long,
    val updatedAt: Long,
)

/** Agregado pequeño para Inicio; no materializa posiciones ni mezcla cantidades entre productos. */
data class InventoryHomeOverviewRow(
    val productCount: Int,
    val availableProductCount: Int,
    val attentionProductCount: Int,
    val withoutStockCount: Int,
    val withoutSalePriceCount: Int,
    val negativeStockCount: Int,
)

/** Solo dispara invalidacion; el agregado se relee despues en una transaccion. */
data class InventoryProductRevisionRow(
    val productId: String,
    val productUpdatedAt: Long,
    val unitUpdatedAt: Long,
    val balanceCount: Int,
    val movementCount: Int,
    val locationUpdatedAtHead: Long?,
)

data class InventoryMovementReadRow(
    val movementId: String,
    val productId: String,
    val locationId: String,
    val locationName: String,
    val type: String,
    val quantityDelta: String,
    val unitCost: String?,
    val currencyCode: String,
    val purchaseId: String?,
    val saleId: String?,
    val purchaseDocumentNumber: String?,
    val occurredAt: Long,
    val createdAt: Long,
)

/** Datos crudos usados dentro de una unica instantanea diagnostica. */
data class InventoryDiagnosticBalanceRow(
    val productId: String,
    val productName: String,
    val locationId: String,
    val locationName: String,
    val quantityOnHand: String,
    val averageUnitCost: String,
    val currencyCode: String,
    val version: Long,
    val updatedAt: Long,
)

data class InventoryDiagnosticMovementRow(
    val movementId: String,
    val productId: String,
    val productName: String,
    val locationId: String,
    val locationName: String,
    val type: String,
    val quantityDelta: String,
    val unitCost: String?,
    val currencyCode: String,
    val purchaseId: String?,
    val purchaseLineId: String?,
    val saleId: String?,
    val saleLineId: String?,
    val appliedCostTotal: String?,
    val purchaseQuantity: String?,
    val purchaseUnitFactor: String?,
    val inventoryQuantity: String?,
    val appliedUnitCost: String?,
    val roundingScale: Int?,
    val roundingMode: String?,
    val occurredAt: Long,
    val createdAt: Long,
)

/**
 * Proyección mutable de saldos y libro mayor append-only de movimientos.
 *
 * El coordinador calcula cantidades/costo exactos y ejecuta estas primitivas dentro de la misma
 * transacción que la compra, auditoría y outbox. La versión del saldo evita reemplazos perdidos.
 * Deliberadamente no existe ninguna operación de update/delete para `stock_movements`: una
 * anulación se registra insertando movimientos compensatorios con deltas opuestos.
 */
@Dao
interface InventoryDao {
    @Query(
        "WITH balance_flags AS (" +
            "SELECT b.businessId, b.productId, " +
            "MAX(CASE WHEN l.status = 'ACTIVE' AND b.quantityOnHand NOT LIKE '-%' " +
            "AND b.quantityOnHand GLOB '*[1-9]*' THEN 1 ELSE 0 END) AS hasPositive, " +
            "MAX(CASE WHEN b.quantityOnHand LIKE '-%' " +
            "AND b.quantityOnHand GLOB '*[1-9]*' THEN 1 ELSE 0 END) AS hasNegative " +
            "FROM inventory_balances b INNER JOIN inventory_locations l " +
            "ON l.businessId = b.businessId AND l.locationId = b.locationId " +
            "WHERE b.businessId = :businessId GROUP BY b.businessId, b.productId) " +
            "SELECT COUNT(p.productId) AS productCount, " +
            "COALESCE(SUM(CASE WHEN COALESCE(bf.hasPositive, 0) = 1 THEN 1 ELSE 0 END), 0) " +
            "AS availableProductCount, " +
            "COALESCE(SUM(CASE WHEN p.salePriceMinorUnits IS NULL " +
            "OR COALESCE(bf.hasPositive, 0) = 0 OR COALESCE(bf.hasNegative, 0) = 1 " +
            "THEN 1 ELSE 0 END), 0) AS attentionProductCount, " +
            "COALESCE(SUM(CASE WHEN COALESCE(bf.hasPositive, 0) = 0 " +
            "THEN 1 ELSE 0 END), 0) AS withoutStockCount, " +
            "COALESCE(SUM(CASE WHEN p.salePriceMinorUnits IS NULL THEN 1 ELSE 0 END), 0) " +
            "AS withoutSalePriceCount, " +
            "COALESCE(SUM(CASE WHEN COALESCE(bf.hasNegative, 0) = 1 THEN 1 ELSE 0 END), 0) " +
            "AS negativeStockCount " +
            "FROM products p LEFT JOIN balance_flags bf ON bf.businessId = p.businessId " +
            "AND bf.productId = p.productId " +
            "WHERE p.businessId = :businessId AND p.status = 'ACTIVE'",
    )
    fun observeHomeOverview(businessId: String): Flow<InventoryHomeOverviewRow>

    @Query(
        "SELECT p.businessId, p.productId, p.name AS productName, p.sku, " +
            "p.status AS productStatus, u.code AS unitCode, u.symbol AS unitSymbol, " +
            "b.locationId, l.name AS locationName, l.status AS locationStatus, " +
            "b.quantityOnHand, b.averageUnitCost, b.currencyCode, b.version, b.updatedAt " +
            "FROM products p " +
            "INNER JOIN units u ON u.unitId = p.unitId AND u.businessId = p.businessId " +
            "LEFT JOIN inventory_balances b ON b.productId = p.productId " +
            "AND b.businessId = p.businessId " +
            "LEFT JOIN inventory_locations l ON l.locationId = b.locationId " +
            "AND l.businessId = b.businessId " +
            "WHERE p.businessId = :businessId " +
            "ORDER BY p.normalizedName ASC, p.productId ASC, LOWER(l.name) ASC, l.locationId ASC",
    )
    fun observeReadPositions(businessId: String): Flow<List<InventoryListReadRow>>

    /** Una sola sentencia conserva la instantánea sin leer ni contar el libro de movimientos. */
    @Query(
        "SELECT p.businessId, p.productId, p.name AS productName, p.sku, " +
            "p.status AS productStatus, u.code AS unitCode, u.symbol AS unitSymbol, " +
            "b.locationId, l.name AS locationName, l.status AS locationStatus, " +
            "b.quantityOnHand, b.averageUnitCost, b.currencyCode, b.version, b.updatedAt " +
            "FROM products p " +
            "INNER JOIN units u ON u.unitId = p.unitId AND u.businessId = p.businessId " +
            "LEFT JOIN inventory_balances b ON b.productId = p.productId " +
            "AND b.businessId = p.businessId " +
            "LEFT JOIN inventory_locations l ON l.locationId = b.locationId " +
            "AND l.businessId = b.businessId " +
            "WHERE p.businessId = :businessId AND p.productId = :productId " +
            "ORDER BY LOWER(l.name) ASC, l.locationId ASC",
    )
    fun observeReadProductPositions(
        businessId: String,
        productId: String,
    ): Flow<List<InventoryListReadRow>>

    @Query(
        "SELECT p.businessId, p.productId, p.name AS productName, p.sku, " +
            "p.status AS productStatus, u.code AS unitCode, u.symbol AS unitSymbol " +
            "FROM products p INNER JOIN units u ON u.unitId = p.unitId " +
            "AND u.businessId = p.businessId " +
            "WHERE p.businessId = :businessId AND p.productId = :productId LIMIT 1",
    )
    suspend fun findReadProductHeader(
        businessId: String,
        productId: String,
    ): InventoryProductHeaderReadRow?

    @Query(
        "SELECT p.productId, p.updatedAt AS productUpdatedAt, u.updatedAt AS unitUpdatedAt, " +
            "(SELECT COUNT(*) FROM inventory_balances b WHERE " +
            "b.businessId = p.businessId AND b.productId = p.productId) AS balanceCount, " +
            "(SELECT COUNT(*) FROM stock_movements m WHERE " +
            "m.businessId = p.businessId AND m.productId = p.productId) AS movementCount, " +
            "(SELECT MAX(l.updatedAt) FROM inventory_balances b " +
            "INNER JOIN inventory_locations l ON l.locationId = b.locationId " +
            "AND l.businessId = b.businessId WHERE b.businessId = p.businessId " +
            "AND b.productId = p.productId) AS locationUpdatedAtHead " +
            "FROM products p INNER JOIN units u ON u.unitId = p.unitId " +
            "AND u.businessId = p.businessId " +
            "WHERE p.businessId = :businessId AND p.productId = :productId LIMIT 1",
    )
    fun observeProductRevision(
        businessId: String,
        productId: String,
    ): Flow<InventoryProductRevisionRow?>

    @Query(
        "SELECT b.businessId, b.productId, p.name AS productName, p.sku, " +
            "p.status AS productStatus, u.code AS unitCode, u.symbol AS unitSymbol, " +
            "b.locationId, l.name AS locationName, l.status AS locationStatus, " +
            "b.quantityOnHand, b.averageUnitCost, b.currencyCode, b.version, b.updatedAt " +
            "FROM inventory_balances b " +
            "INNER JOIN products p ON p.productId = b.productId " +
            "AND p.businessId = b.businessId " +
            "INNER JOIN units u ON u.unitId = p.unitId AND u.businessId = b.businessId " +
            "INNER JOIN inventory_locations l ON l.locationId = b.locationId " +
            "AND l.businessId = b.businessId " +
            "WHERE b.businessId = :businessId AND b.productId = :productId " +
            "ORDER BY LOWER(l.name) ASC, l.locationId ASC",
    )
    suspend fun listReadPositionsForProduct(
        businessId: String,
        productId: String,
    ): List<InventoryPositionReadRow>

    @Query(
        "SELECT m.movementId, m.productId, m.locationId, l.name AS locationName, " +
            "m.type, m.quantityDelta, m.unitCost, m.currencyCode, m.purchaseId, m.saleId, " +
            "CASE WHEN pu.purchaseId IS NULL THEN NULL " +
            "ELSE pu.documentSeries || '-' || pu.documentNumber END AS purchaseDocumentNumber, " +
            "m.occurredAt, m.createdAt " +
            "FROM stock_movements m " +
            "INNER JOIN inventory_locations l ON l.locationId = m.locationId " +
            "AND l.businessId = m.businessId " +
            "LEFT JOIN purchases pu ON pu.purchaseId = m.purchaseId " +
            "AND pu.businessId = m.businessId " +
            "WHERE m.businessId = :businessId AND m.productId = :productId " +
            "ORDER BY m.occurredAt ASC, m.createdAt ASC, m.movementId ASC",
    )
    suspend fun listReadMovementsForProduct(
        businessId: String,
        productId: String,
    ): List<InventoryMovementReadRow>

    @Query(
        "SELECT b.productId, p.name AS productName, b.locationId, " +
            "l.name AS locationName, b.quantityOnHand, b.averageUnitCost, b.currencyCode, " +
            "b.version, b.updatedAt " +
            "FROM inventory_balances b " +
            "INNER JOIN products p ON p.productId = b.productId " +
            "AND p.businessId = b.businessId " +
            "INNER JOIN inventory_locations l ON l.locationId = b.locationId " +
            "AND l.businessId = b.businessId " +
            "WHERE b.businessId = :businessId " +
            "ORDER BY b.productId ASC, b.locationId ASC",
    )
    suspend fun listDiagnosticBalances(businessId: String): List<InventoryDiagnosticBalanceRow>

    @Query(
        "SELECT m.movementId, m.productId, p.name AS productName, m.locationId, " +
            "l.name AS locationName, m.type, m.quantityDelta, m.unitCost, m.currencyCode, " +
            "m.purchaseId, m.purchaseLineId, m.saleId, m.saleLineId, pl.appliedCostTotal, " +
            "pl.quantity AS purchaseQuantity, pl.purchaseUnitFactor, pl.inventoryQuantity, " +
            "pl.appliedUnitCost, pl.roundingScale, pl.roundingMode, " +
            "m.occurredAt, m.createdAt " +
            "FROM stock_movements m " +
            "INNER JOIN products p ON p.productId = m.productId " +
            "AND p.businessId = m.businessId " +
            "INNER JOIN inventory_locations l ON l.locationId = m.locationId " +
            "AND l.businessId = m.businessId " +
            "LEFT JOIN purchase_lines pl ON pl.purchaseLineId = m.purchaseLineId " +
            "AND pl.productId = m.productId " +
            "WHERE m.businessId = :businessId " +
            "ORDER BY m.occurredAt ASC, m.createdAt ASC, m.movementId ASC",
    )
    suspend fun listDiagnosticMovements(
        businessId: String,
    ): List<InventoryDiagnosticMovementRow>

    @Query(
        "SELECT * FROM inventory_balances WHERE businessId = :businessId " +
            "AND productId = :productId AND locationId = :locationId",
    )
    suspend fun findBalance(
        businessId: String,
        productId: String,
        locationId: String,
    ): InventoryBalanceEntity?

    @Query(
        "SELECT * FROM inventory_balances WHERE businessId = :businessId " +
            "ORDER BY productId ASC, locationId ASC",
    )
    suspend fun listBalancesForBusiness(businessId: String): List<InventoryBalanceEntity>

    @Query(
        "SELECT * FROM inventory_balances WHERE businessId = :businessId " +
            "AND locationId = :locationId ORDER BY productId ASC",
    )
    suspend fun listBalancesForLocation(
        businessId: String,
        locationId: String,
    ): List<InventoryBalanceEntity>

    @Query(
        "SELECT * FROM inventory_balances WHERE businessId = :businessId " +
            "AND productId = :productId ORDER BY locationId ASC",
    )
    suspend fun listBalancesForProduct(
        businessId: String,
        productId: String,
    ): List<InventoryBalanceEntity>

    /** Devuelve -1 cuando el saldo ya existe; el coordinador debe leerlo y usar el CAS. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBalanceIfAbsent(balance: InventoryBalanceEntity): Long

    @Query(
        "UPDATE inventory_balances SET quantityOnHand = :quantityOnHand, " +
            "averageUnitCost = :averageUnitCost, currencyCode = :currencyCode, " +
            "version = version + 1, updatedAt = :updatedAt " +
            "WHERE businessId = :businessId AND productId = :productId " +
            "AND locationId = :locationId AND version = :expectedVersion " +
            "AND :updatedAt >= updatedAt",
    )
    suspend fun updateBalanceIfVersion(
        businessId: String,
        productId: String,
        locationId: String,
        expectedVersion: Long,
        quantityOnHand: String,
        averageUnitCost: String,
        currencyCode: String,
        updatedAt: Long,
    ): Int

    /**
     * Reemplaza la proyeccion con la instantanea autoritativa del stream cloud. La secuencia
     * remota se valida y avanza en la misma transaccion que este CAS, por lo que aqui no se usa
     * el reloj local como condicion: un dispositivo con hora adelantada no puede bloquear para
     * siempre un saldo valido del servidor. La version Room conserva su invariante local +1.
     */
    @Query(
        "UPDATE inventory_balances SET quantityOnHand = :quantityOnHand, " +
            "averageUnitCost = :averageUnitCost, currencyCode = :currencyCode, " +
            "version = version + 1, updatedAt = :updatedAt " +
            "WHERE businessId = :businessId AND productId = :productId " +
            "AND locationId = :locationId AND version = :expectedVersion",
    )
    suspend fun updateAuthoritativeBalanceIfVersion(
        businessId: String,
        productId: String,
        locationId: String,
        expectedVersion: Long,
        quantityOnHand: String,
        averageUnitCost: String,
        currencyCode: String,
        updatedAt: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMovements(movements: List<StockMovementEntity>)

    @Query("SELECT * FROM stock_movements WHERE movementId = :movementId")
    suspend fun findMovementById(movementId: String): StockMovementEntity?

    @Query("SELECT * FROM stock_movements WHERE idempotencyKey = :idempotencyKey")
    suspend fun findMovementByIdempotencyKey(idempotencyKey: String): StockMovementEntity?

    @Query("SELECT * FROM stock_movements WHERE idempotencyKey LIKE :pattern")
    suspend fun listMovementsByIdempotencyPattern(pattern: String): List<StockMovementEntity>

    @Query(
        "SELECT * FROM stock_movements WHERE businessId = :businessId " +
            "AND purchaseId = :purchaseId " +
            "ORDER BY occurredAt ASC, createdAt ASC, movementId ASC",
    )
    suspend fun listMovementsForPurchase(
        businessId: String,
        purchaseId: String,
    ): List<StockMovementEntity>

    @Query(
        "SELECT * FROM stock_movements WHERE businessId = :businessId AND saleId = :saleId " +
            "ORDER BY occurredAt ASC, createdAt ASC, movementId ASC",
    )
    suspend fun listMovementsForSale(
        businessId: String,
        saleId: String,
    ): List<StockMovementEntity>

    @Query(
        "SELECT MAX(MAX(occurredAt, createdAt)) FROM stock_movements " +
            "WHERE businessId = :businessId AND productId = :productId AND locationId = :locationId",
    )
    suspend fun latestMovementTimestamp(
        businessId: String,
        productId: String,
        locationId: String,
    ): Long?

    @Query(
        "SELECT m.movementId, m.purchaseId, m.purchaseLineId, m.productId, " +
            "p.name AS productName, m.locationId, l.name AS locationName, m.type, " +
            "m.quantityDelta, m.unitCost, m.currencyCode, m.occurredAt " +
            "FROM stock_movements m " +
            "INNER JOIN products p ON p.productId = m.productId " +
            "INNER JOIN inventory_locations l ON l.locationId = m.locationId " +
            "WHERE m.businessId = :businessId AND m.purchaseId = :purchaseId " +
            "AND m.purchaseLineId IS NOT NULL " +
            "ORDER BY m.occurredAt ASC, m.createdAt ASC, m.movementId ASC",
    )
    fun observeReadMovements(
        businessId: String,
        purchaseId: String,
    ): Flow<List<PurchaseMovementReadRow>>
}
