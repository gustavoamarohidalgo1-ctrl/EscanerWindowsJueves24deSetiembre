package com.facturastock.app.domain.repository

import com.facturastock.app.domain.model.DebtPaymentMethod
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DebtId
import com.facturastock.app.domain.model.id.DebtPaymentId
import java.time.Instant

/** Documento estable que el backend autoriza antes de que Room disminuya el saldo local. */
data class RemoteDebtPaymentDocument(
    val paymentId: DebtPaymentId,
    val debtId: DebtId,
    val businessId: BusinessId,
    val amount: Money,
    val method: DebtPaymentMethod,
    val note: String?,
    val reference: String?,
    val expectedDebtVersion: Long,
    val occurredAt: Instant,
    val createdAt: Instant,
    val idempotencyKey: String,
)

sealed interface RemoteDebtPaymentResult {
    /** No existe enlace cloud: Room conserva el comportamiento local offline. */
    data object NotRequired : RemoteDebtPaymentResult

    data class Authorized(
        val paymentId: DebtPaymentId,
        val debtId: DebtId,
        val balanceAfter: Money,
        val debtVersion: Long,
        val occurredAt: Instant,
        val createdAt: Instant,
        val receiptId: String,
        val seq: Long,
    ) : RemoteDebtPaymentResult

    data object OnlineRequired : RemoteDebtPaymentResult
    data object Stale : RemoteDebtPaymentResult
    data object Rejected : RemoteDebtPaymentResult
}

interface RemoteDebtSyncRepository {
    val available: Boolean

    suspend fun postPayment(
        localBusinessId: BusinessId,
        document: RemoteDebtPaymentDocument,
    ): RemoteDebtPaymentResult
}

object DisabledRemoteDebtSyncRepository : RemoteDebtSyncRepository {
    override val available: Boolean = false

    override suspend fun postPayment(
        localBusinessId: BusinessId,
        document: RemoteDebtPaymentDocument,
    ): RemoteDebtPaymentResult = RemoteDebtPaymentResult.NotRequired
}
