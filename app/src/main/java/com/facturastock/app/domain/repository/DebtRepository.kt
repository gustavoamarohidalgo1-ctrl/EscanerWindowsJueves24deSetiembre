package com.facturastock.app.domain.repository

import com.facturastock.app.domain.model.DebtDetail
import com.facturastock.app.domain.model.DebtPayment
import com.facturastock.app.domain.model.DebtPaymentMethod
import com.facturastock.app.domain.model.DebtSummary
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DebtId
import java.time.Instant
import kotlinx.coroutines.flow.Flow

data class RecordDebtPaymentCommand(
    val debtId: DebtId,
    val expectedVersion: Long,
    val amount: Money,
    val method: DebtPaymentMethod,
    val note: String? = null,
    val reference: String? = null,
    /** Debe conservarse al reintentar el mismo gesto; el paymentId se deriva de deuda+versión. */
    val occurredAt: Instant,
) {
    init {
        require(expectedVersion >= 1L)
        require(amount.minorUnits > 0L)
        require(occurredAt.toEpochMilli() >= 0L)
    }
}

sealed interface RecordDebtPaymentResult {
    data class Recorded(
        val debt: DebtSummary,
        val payment: DebtPayment,
    ) : RecordDebtPaymentResult

    data class AlreadyRecorded(
        val debt: DebtSummary,
        val payment: DebtPayment,
    ) : RecordDebtPaymentResult

    data object NoActiveBusiness : RecordDebtPaymentResult
    data object NotFound : RecordDebtPaymentResult
    data object AlreadyPaid : RecordDebtPaymentResult
    data object Stale : RecordDebtPaymentResult
    data object CurrencyMismatch : RecordDebtPaymentResult
    data object AmountExceedsBalance : RecordDebtPaymentResult
    data object InvalidPayment : RecordDebtPaymentResult
    data object OnlineRequired : RecordDebtPaymentResult
    data object RemoteRejected : RecordDebtPaymentResult
    data object RetryableConflict : RecordDebtPaymentResult
}

interface DebtRepository {
    fun observeOpen(businessId: BusinessId): Flow<List<DebtSummary>>

    fun observeAll(businessId: BusinessId): Flow<List<DebtSummary>>

    fun observeDetail(businessId: BusinessId, debtId: DebtId): Flow<DebtDetail?>

    suspend fun recordPayment(
        businessId: BusinessId,
        command: RecordDebtPaymentCommand,
    ): RecordDebtPaymentResult
}
