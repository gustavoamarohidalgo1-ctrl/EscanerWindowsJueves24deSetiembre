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

data class SaleDebtorNameRow(
    val saleId: String,
    val debtorName: String,
)

data class DebtPaymentReportRow(
    @Embedded val payment: DebtPaymentEntity,
    val saleId: String,
    val debtorName: String,
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

    @Query("SELECT * FROM debt_payments WHERE debtId = :debtId " +
        "ORDER BY expectedDebtVersion ASC, paymentId ASC")
    suspend fun findPaymentsForDebt(debtId: String): List<DebtPaymentEntity>

    @Query("SELECT * FROM debt_payments WHERE paymentId = :paymentId")
    suspend fun findPayment(paymentId: String): DebtPaymentEntity?

    @Query("SELECT * FROM debt_payments WHERE idempotencyKey = :idempotencyKey LIMIT 1")
    suspend fun findPaymentByIdempotencyKey(idempotencyKey: String): DebtPaymentEntity?

    @Query(
        "SELECT d.*, (SELECT COUNT(*) FROM sale_lines sl WHERE sl.saleId = d.saleId) " +
            "AS lineCount FROM debts d WHERE d.debtId = :debtId " +
            "AND d.businessId = :businessId " +
            "AND NOT EXISTS (SELECT 1 FROM sale_voids v WHERE v.saleId = d.saleId " +
            "AND v.businessId = d.businessId) LIMIT 1",
    )
    suspend fun findSummary(businessId: String, debtId: String): DebtSummaryRow?

    @Transaction
    @Query("SELECT d.* FROM debts d WHERE d.debtId = :debtId AND d.businessId = :businessId " +
        "AND NOT EXISTS (SELECT 1 FROM sale_voids v WHERE v.saleId = d.saleId AND v.businessId = d.businessId)")
    fun observeDetail(businessId: String, debtId: String): Flow<DebtWithGraph?>

    @Query(
        "SELECT d.*, (SELECT COUNT(*) FROM sale_lines sl WHERE sl.saleId = d.saleId) " +
            "AS lineCount FROM debts d WHERE d.businessId = :businessId " +
            "AND NOT EXISTS (SELECT 1 FROM sale_voids v WHERE v.saleId = d.saleId " +
            "AND v.businessId = d.businessId) " +
            "AND (:onlyOpen = 0 OR d.status = 'OPEN') " +
            "ORDER BY CASE d.status WHEN 'OPEN' THEN 0 ELSE 1 END, " +
            "d.updatedAt DESC, d.debtId DESC",
    )
    fun observeSummaries(
        businessId: String,
        onlyOpen: Boolean,
    ): Flow<List<DebtSummaryRow>>

    @Query(
        "SELECT d.*, (SELECT COUNT(*) FROM sale_lines sl WHERE sl.saleId = d.saleId) " +
            "AS lineCount FROM debts d WHERE d.businessId = :businessId " +
            "AND NOT EXISTS (SELECT 1 FROM sale_voids v WHERE v.saleId = d.saleId " +
            "AND v.businessId = d.businessId) " +
            "AND (:onlyOpen = 0 OR d.status = 'OPEN') " +
            "ORDER BY CASE d.status WHEN 'OPEN' THEN 0 ELSE 1 END, " +
            "d.updatedAt DESC, d.debtId DESC",
    )
    suspend fun listSummaries(businessId: String, onlyOpen: Boolean): List<DebtSummaryRow>

    /** Consulta acotada a las ventas del PDF; conserva los créditos que ya fueron pagados. */
    @Query(
        "SELECT d.saleId, d.debtorName FROM debts d " +
            "WHERE d.businessId = :businessId AND d.saleId IN (:saleIds) " +
            "AND NOT EXISTS (SELECT 1 FROM sale_voids v WHERE v.saleId = d.saleId " +
            "AND v.businessId = d.businessId)",
    )
    suspend fun listDebtorNamesForSales(businessId: String, saleIds: List<String>): List<SaleDebtorNameRow>

    @Query(
        "SELECT p.*, d.saleId, d.debtorName FROM debt_payments p " +
            "JOIN debts d ON d.debtId = p.debtId AND d.businessId = p.businessId " +
            "WHERE p.businessId = :businessId AND p.occurredAt >= :startInclusive " +
            "AND p.occurredAt < :endExclusive " +
            "AND NOT EXISTS (SELECT 1 FROM sale_voids v WHERE v.saleId = d.saleId " +
            "AND v.businessId = d.businessId) " +
            "ORDER BY p.occurredAt DESC, p.paymentId DESC",
    )
    fun observePaymentsInRange(businessId: String, startInclusive: Long, endExclusive: Long): Flow<List<DebtPaymentReportRow>>

    @Query(
        "SELECT p.*, d.saleId, d.debtorName FROM debt_payments p " +
            "JOIN debts d ON d.debtId = p.debtId AND d.businessId = p.businessId " +
            "WHERE p.businessId = :businessId AND p.occurredAt >= :startInclusive " +
            "AND p.occurredAt < :endExclusive " +
            "AND NOT EXISTS (SELECT 1 FROM sale_voids v WHERE v.saleId = d.saleId " +
            "AND v.businessId = d.businessId) " +
            "ORDER BY p.occurredAt DESC, p.paymentId DESC",
    )
    suspend fun listPaymentsInRange(businessId: String, startInclusive: Long, endExclusive: Long): List<DebtPaymentReportRow>

    @Query(
        "UPDATE debts SET balanceMinorUnits = :balanceAfterMinorUnits, status = :status, " +
            "version = version + 1, updatedAt = :updatedAt, paidAt = :paidAt " +
            "WHERE debtId = :debtId AND businessId = :businessId AND status = 'OPEN' " +
            "AND version = :expectedVersion AND currencyCode = :currencyCode " +
            "AND NOT EXISTS (SELECT 1 FROM sale_voids v WHERE v.saleId = debts.saleId " +
            "AND v.businessId = debts.businessId)",
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
