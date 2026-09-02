package com.facturastock.app.data.sync

import com.facturastock.app.domain.model.AccountSession
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.AccountRepository
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.CloudBusinessBindingRepository
import com.facturastock.app.domain.repository.RemoteDebtPaymentDocument
import com.facturastock.app.domain.repository.RemoteDebtPaymentResult
import com.facturastock.app.domain.repository.RemoteDebtSyncRepository
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.functions.FirebaseFunctionsException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await

/** Autoridad remota para abonos de negocios enlazados; nunca confirma un pago solo en local. */
@Singleton
class FirebaseRemoteDebtSyncRepository @Inject constructor(
    private val runtime: FirebaseBackupRuntime,
    private val accountRepository: AccountRepository,
    private val appConfiguration: AppConfigurationRepository,
    private val cloudBusinessBindings: CloudBusinessBindingRepository,
) : RemoteDebtSyncRepository {
    override val available: Boolean
        get() = runtime.config != null

    override suspend fun postPayment(
        localBusinessId: BusinessId,
        document: RemoteDebtPaymentDocument,
    ): RemoteDebtPaymentResult {
        if (document.businessId != localBusinessId) return RemoteDebtPaymentResult.Rejected
        val cloudBusinessId = cloudBusinessBindings.targetFor(localBusinessId)
            ?: return RemoteDebtPaymentResult.NotRequired
        if (!available || !appConfiguration.current().backupEnabled) {
            return RemoteDebtPaymentResult.OnlineRequired
        }
        val session = accountRepository.observeSession().first()
        if (session !is AccountSession.Active) return RemoteDebtPaymentResult.OnlineRequired
        val link = session.link
        if (
            link == null || link.localBusinessId != localBusinessId ||
            link.cloudBusinessId != cloudBusinessId ||
            !cloudBusinessBindings.matches(localBusinessId, cloudBusinessId)
        ) {
            return RemoteDebtPaymentResult.Rejected
        }
        val functions = runtime.functions() ?: return RemoteDebtPaymentResult.OnlineRequired
        val outbound = linkedMapOf<String, Any?>(
            "version" to 1,
            "paymentId" to document.paymentId.value,
            "debtId" to document.debtId.value,
            "businessId" to cloudBusinessId.value,
            "currency" to document.amount.currency.value,
            "amountMinorUnits" to document.amount.minorUnits,
            "method" to document.method.name,
            "note" to document.note,
            "reference" to document.reference,
            "expectedDebtVersion" to document.expectedDebtVersion,
            "occurredAt" to document.occurredAt.toEpochMilli(),
            "createdAt" to document.createdAt.toEpochMilli(),
        )
        if (document.idempotencyKey != "debt-payment:v1:${document.debtId.value}:${document.paymentId.value}") {
            return RemoteDebtPaymentResult.Rejected
        }
        return try {
            runtime.prepareBusiness(functions, cloudBusinessId.value)
            val result = functions.getHttpsCallable(RECORD_DEBT_PAYMENT_CALLABLE)
                .call(
                    mapOf(
                        "businessId" to cloudBusinessId.value,
                        "expectedUid" to session.uid,
                        "idempotencyKey" to document.idempotencyKey,
                        "operationType" to OPERATION_TYPE,
                        "payloadVersion" to 1,
                        "document" to outbound,
                    ),
                )
                .await()
            val raw = result.data as? Map<*, *> ?: return RemoteDebtPaymentResult.Rejected
            authorize(
                cloudBusinessId = cloudBusinessId,
                requested = document,
                ack = FirebaseSaleWireMapper.debtPaymentAck(raw),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            mapFailure(failure)
        }
    }

    private fun authorize(
        cloudBusinessId: BusinessId,
        requested: RemoteDebtPaymentDocument,
        ack: FirebaseDebtPaymentAck,
    ): RemoteDebtPaymentResult {
        val debt = ack.debt
        val payment = ack.payment
        if (
            ack.idempotencyKey != requested.idempotencyKey ||
            debt.businessId != cloudBusinessId || payment.businessId != cloudBusinessId ||
            debt.debtId != requested.debtId || payment.debtId != requested.debtId ||
            payment.paymentId != requested.paymentId ||
            payment.amount != requested.amount || payment.method != requested.method ||
            payment.note != requested.note || payment.reference != requested.reference ||
            payment.expectedDebtVersion != requested.expectedDebtVersion ||
            payment.idempotencyKey != requested.idempotencyKey ||
            payment.occurredAt != requested.occurredAt ||
            payment.createdAt < requested.createdAt ||
            debt.currency != requested.amount.currency ||
            debt.version != requested.expectedDebtVersion + 1L ||
            debt.balance != payment.balanceAfter || debt.updatedAt != payment.createdAt
        ) {
            return RemoteDebtPaymentResult.Rejected
        }
        return RemoteDebtPaymentResult.Authorized(
            paymentId = payment.paymentId,
            debtId = debt.debtId,
            balanceAfter = debt.balance,
            debtVersion = debt.version,
            occurredAt = payment.occurredAt,
            createdAt = payment.createdAt,
            receiptId = ack.receiptId,
            seq = ack.seq,
        )
    }

    private fun mapFailure(failure: Exception): RemoteDebtPaymentResult {
        if (failure is FirebaseFunctionsException) {
            val message = failure.message.orEmpty()
            if (
                failure.code == FirebaseFunctionsException.Code.ABORTED ||
                (failure.code == FirebaseFunctionsException.Code.FAILED_PRECONDITION &&
                    STALE_CODES.any(message::contains))
            ) {
                return RemoteDebtPaymentResult.Stale
            }
            if (
                failure.code == FirebaseFunctionsException.Code.UNAVAILABLE ||
                failure.code == FirebaseFunctionsException.Code.DEADLINE_EXCEEDED ||
                failure.code == FirebaseFunctionsException.Code.INTERNAL ||
                failure.code == FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED ||
                failure.code == FirebaseFunctionsException.Code.UNAUTHENTICATED
            ) {
                return RemoteDebtPaymentResult.OnlineRequired
            }
            return RemoteDebtPaymentResult.Rejected
        }
        if (failure is FirebaseNetworkException || failure is IOException) {
            return RemoteDebtPaymentResult.OnlineRequired
        }
        return RemoteDebtPaymentResult.Rejected
    }

    private companion object {
        const val RECORD_DEBT_PAYMENT_CALLABLE = "recordDebtPayment"
        const val OPERATION_TYPE = "SYNC_DEBT_PAYMENT"
        val STALE_CODES = setOf(
            "DEBT_VERSION_CONFLICT",
            "DEBT_ALREADY_PAID",
            // Otro dispositivo pudo reducir el saldo sin que este cliente haya consumido aún
            // el feed. El importe era válido contra la versión local, por lo que se recarga.
            "DEBT_PAYMENT_EXCEEDS_BALANCE",
        )
    }
}
