package com.facturastock.app.data.local.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import com.facturastock.app.data.local.entity.DebtEntity
import com.facturastock.app.data.local.entity.DebtPaymentEntity
import com.facturastock.app.data.local.entity.SaleLineEntity
import kotlinx.coroutines.flow.Flow

data class DebtSummaryRow(
    @Embedded val debt: DebtEntity,
    val lineCount: Int,
)

data class DebtWithGraph(
    @Embedded val debt: DebtEntity,
    @Relation(parentColumn = "saleId", entityColumn = "saleId")
    val lines: List<SaleLineEntity>,
    @Relation(parentColumn = "debtId", entityColumn = "debtId")
    val payments: List<DebtPaymentEntity>,
)

@Dao
interface DebtDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDebt(debt: DebtEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPayment(payment: DebtPaymentEntity)

    @Query("SELECT * FROM debts WHERE debtId = :debtId")
    suspend fun findDebt(debtId: String): DebtEntity?

    @Query("SELECT * FROM debts WHERE saleId = :saleId LIMIT 1")
    suspend fun findDebtForSale(saleId: String): DebtEntity?

    @Query("SELECT * FROM debt_payments WHERE paymentId = :paymentId")
    suspend fun findPayment(paymentId: String): DebtPaymentEntity?

    @Query("SELECT * FROM debt_payments WHERE idempotencyKey = :idempotencyKey LIMIT 1")
    suspend fun findPaymentByIdempotencyKey(idempotencyKey: String): DebtPaymentEntity?

    @Query(
        "SELECT d.*, (SELECT COUNT(*) FROM sale_lines sl WHERE sl.saleId = d.saleId) " +
            "AS lineCount FROM debts d WHERE d.debtId = :debtId " +
            "AND d.businessId = :businessId LIMIT 1",
    )
    suspend fun findSummary(businessId: String, debtId: String): DebtSummaryRow?

    @Transaction
    @Query("SELECT * FROM debts WHERE debtId = :debtId AND businessId = :businessId")
    fun observeDetail(businessId: String, debtId: String): Flow<DebtWithGraph?>

    @Query(
        "SELECT d.*, (SELECT COUNT(*) FROM sale_lines sl WHERE sl.saleId = d.saleId) " +
            "AS lineCount FROM debts d WHERE d.businessId = :businessId " +
            "AND (:onlyOpen = 0 OR d.status = 'OPEN') " +
            "ORDER BY CASE d.status WHEN 'OPEN' THEN 0 ELSE 1 END, " +
            "d.updatedAt DESC, d.debtId DESC",
    )
    fun observeSummaries(
        businessId: String,
        onlyOpen: Boolean,
    ): Flow<List<DebtSummaryRow>>

    @Query(
        "UPDATE debts SET balanceMinorUnits = :balanceAfterMinorUnits, status = :status, " +
            "version = version + 1, updatedAt = :updatedAt, paidAt = :paidAt " +
            "WHERE debtId = :debtId AND businessId = :businessId AND status = 'OPEN' " +
            "AND version = :expectedVersion AND currencyCode = :currencyCode",
    )
    suspend fun applyPaymentIfVersion(
        debtId: String,
        businessId: String,
        expectedVersion: Long,
        currencyCode: String,
        balanceAfterMinorUnits: Long,
        status: String,
        updatedAt: Long,
        paidAt: Long?,
    ): Int

    @Query("SELECT COUNT(*) FROM debts WHERE businessId = :businessId")
    suspend fun countForBusiness(businessId: String): Int

    @Query("SELECT COUNT(*) FROM debt_payments WHERE debtId = :debtId")
    suspend fun countPayments(debtId: String): Int
}
