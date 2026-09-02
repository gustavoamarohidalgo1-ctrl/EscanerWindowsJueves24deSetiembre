package com.facturastock.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.facturastock.app.data.local.entity.PurchaseLineEntity
import kotlinx.coroutines.flow.Flow

data class PurchaseLineReadRow(
    val purchaseLineId: String,
    val purchaseId: String,
    val productId: String,
    val productName: String,
    val unitId: String,
    val unitCode: String,
    val unitSymbol: String?,
    val position: Int,
    val rawText: String,
    val description: String,
    val quantity: String,
    val readUnitCost: String,
    val currencyCode: String,
    val taxMinorUnits: Long,
    val totalMinorUnits: Long,
    val appliedUnitCost: String?,
    val appliedCostTotal: String?,
    val inventoryQuantity: String?,
    val discount: String?,
    val productProvenance: String,
    val taxTreatment: String?,
    val taxEvidenceType: String?,
    val taxEvidenceValue: String?,
)

/** Líneas congeladas de una compra; después de insertarlas no se pueden editar ni borrar. */
@Dao
interface PurchaseLineDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(lines: List<PurchaseLineEntity>)

    @Query("SELECT * FROM purchase_lines WHERE purchaseLineId = :purchaseLineId")
    suspend fun findById(purchaseLineId: String): PurchaseLineEntity?

    @Query(
        "SELECT * FROM purchase_lines WHERE purchaseId = :purchaseId " +
            "ORDER BY position ASC, purchaseLineId ASC",
    )
    suspend fun listForPurchase(purchaseId: String): List<PurchaseLineEntity>

    @Query(
        "SELECT pl.purchaseLineId, pl.purchaseId, pl.productId, " +
            "COALESCE(pl.productNameSnapshot, pr.name) AS productName, " +
            "pl.unitId, COALESCE(pl.unitCodeSnapshot, u.code) AS unitCode, " +
            "u.symbol AS unitSymbol, pl.position, " +
            "pl.rawText, pl.description, pl.quantity, pl.unitCost AS readUnitCost, " +
            "pl.currencyCode, pl.taxMinorUnits, pl.totalMinorUnits, pl.appliedUnitCost, " +
            "pl.appliedCostTotal, pl.inventoryQuantity, pl.discount, " +
            "pl.productProvenance, pl.taxTreatment, " +
            "pl.taxEvidenceType, pl.taxEvidenceValue " +
            "FROM purchase_lines pl " +
            "INNER JOIN products pr ON pr.productId = pl.productId " +
            "INNER JOIN units u ON u.unitId = pl.unitId " +
            "WHERE pl.purchaseId = :purchaseId " +
            "ORDER BY pl.position ASC, pl.purchaseLineId ASC",
    )
    fun observeReadLines(purchaseId: String): Flow<List<PurchaseLineReadRow>>
}
