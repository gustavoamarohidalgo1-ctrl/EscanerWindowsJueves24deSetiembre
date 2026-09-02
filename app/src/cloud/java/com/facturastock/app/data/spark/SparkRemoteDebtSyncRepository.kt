package com.facturastock.app.data.spark

import com.facturastock.app.core.time.AppClock
import com.facturastock.app.data.sync.FirebaseBackupRuntime
import com.facturastock.app.domain.model.AccountSession
import com.facturastock.app.domain.model.BusinessRole
import com.facturastock.app.domain.model.SharedDebtPayment
import com.facturastock.app.domain.model.SharedDebtSnapshot
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.AccountRepository
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.CloudBusinessBindingRepository
import com.facturastock.app.domain.repository.RemoteDebtPaymentDocument
import com.facturastock.app.domain.repository.RemoteDebtPaymentResult
import com.facturastock.app.domain.repository.RemoteDebtSyncRepository
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Transaction
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await

/** Pago CAS directo en Firestore para Spark. Nunca modifica inventario ni llama Functions. */
@Singleton
class SparkRemoteDebtSyncRepository @Inject constructor(
    private val runtime: FirebaseBackupRuntime,
    private val accountRepository: AccountRepository,
    private val appConfiguration: AppConfigurationRepository,
    private val cloudBusinessBindings: CloudBusinessBindingRepository,
    private val clock: AppClock,
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
            link.cloudBusinessId != cloudBusinessId || link.role != BusinessRole.OWNER ||
            !cloudBusinessBindings.matches(localBusinessId, cloudBusinessId)
        ) {
            return RemoteDebtPaymentResult.Rejected
        }
        if (
            document.idempotencyKey != SparkFirestoreSchema.debtPaymentOperationId(
                document.debtId.value,
                document.paymentId.value,
            )
        ) {
            return RemoteDebtPaymentResult.Rejected
        }
        val firestore = runtime.firestore() ?: return RemoteDebtPaymentResult.OnlineRequired
        val operationId = document.idempotencyKey
        val operationDocumentId = SparkFirestoreSchema.operationDocumentId(operationId)
        val receiptId = SparkFirestoreSchema.debtPaymentReceiptId(
            cloudBusinessId.value,
            operationId,
        )
        val payloadHash = debtPaymentPayloadHash(cloudBusinessId, document)
        return try {
            val businessRef = firestore.collection(SparkFirestoreSchema.BUSINESSES)
                .document(cloudBusinessId.value)
            val debtRef = businessRef.collection(SparkFirestoreSchema.DEBTS)
                .document(document.debtId.value)
            val paymentRef = debtRef.collection(SparkFirestoreSchema.DEBT_PAYMENTS)
                .document(document.paymentId.value)
            val keyRef = businessRef.collection(SparkFirestoreSchema.DEBT_PAYMENT_KEYS)
                .document(operationDocumentId)
            val metadataRef = businessRef.collection(SparkFirestoreSchema.SYNC)
                .document(SparkFirestoreSchema.INVENTORY_METADATA)
            val changeRef = businessRef.collection(SparkFirestoreSchema.INVENTORY_CHANGES)
                .document(receiptId)
            val ack = firestore.runTransaction { transaction ->
                payInTransaction(
                    transaction = transaction,
                    session = session,
                    cloudBusinessId = cloudBusinessId,
                    document = document,
                    operationId = operationId,
                    receiptId = receiptId,
                    payloadHash = payloadHash,
                    businessRef = businessRef,
                    debtRef = debtRef,
                    paymentRef = paymentRef,
                    keyRef = keyRef,
                    metadataRef = metadataRef,
                    changeRef = changeRef,
                )
            }.await()
            RemoteDebtPaymentResult.Authorized(
                paymentId = ack.payment.paymentId,
                debtId = ack.debt.debtId,
                balanceAfter = ack.debt.balance,
                debtVersion = ack.debt.version,
                occurredAt = ack.payment.occurredAt,
                createdAt = ack.payment.createdAt,
                receiptId = receiptId,
                seq = ack.seq,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SparkMutationFailure.Stale) {
            RemoteDebtPaymentResult.Stale
        } catch (_: SparkMutationFailure.InventoryMigrationRequired) {
            RemoteDebtPaymentResult.Rejected
        } catch (_: SparkMutationFailure.InsufficientStock) {
            RemoteDebtPaymentResult.Rejected
        } catch (_: SparkMutationFailure.Rejected) {
            RemoteDebtPaymentResult.Rejected
        } catch (_: SparkMutationFailure.CorruptRemoteData) {
            RemoteDebtPaymentResult.Rejected
        } catch (failure: Exception) {
            mapDebtFailure(failure)
        }
    }

    private fun payInTransaction(
        transaction: Transaction,
        session: AccountSession.Active,
        cloudBusinessId: BusinessId,
        document: RemoteDebtPaymentDocument,
        operationId: String,
        receiptId: String,
        payloadHash: String,
        businessRef: DocumentReference,
        debtRef: DocumentReference,
        paymentRef: DocumentReference,
        keyRef: DocumentReference,
        metadataRef: DocumentReference,
        changeRef: DocumentReference,
    ): SparkDebtPaymentAck {
        val business = transaction.get(businessRef)
        val debt = transaction.get(debtRef)
        val payment = transaction.get(paymentRef)
        val key = transaction.get(keyRef)
        val metadata = transaction.get(metadataRef)
        val change = transaction.get(changeRef)

        requireOwnedDebtBusiness(business, session.uid)
        if (payment.exists()) {
            return replayPayment(
                payment = payment,
                key = key,
                change = change,
                cloudBusinessId = cloudBusinessId,
                document = document,
                operationId = operationId,
                receiptId = receiptId,
                payloadHash = payloadHash,
            )
        }
        if (!debt.exists()) throw SparkMutationFailure.Stale
        if (key.exists() || change.exists()) throw SparkMutationFailure.Rejected
        val metadataSeq = metadata.data?.safeDebtLong("seq") ?: 0L
        val plan = SparkDebtPaymentMutationPlanner.plan(
            cloudBusinessId = cloudBusinessId,
            document = document,
            rawDebt = debt.data ?: throw SparkMutationFailure.Stale,
            metadataSeq = metadataSeq,
            now = clock.now(),
        )
        val serverTimestamp = FieldValue.serverTimestamp()
        val debtSnapshot = plan.debt.toWireMap()
        val paymentSnapshot = plan.payment.toWireMap()
        val existingDebt = debt.data ?: throw SparkMutationFailure.Stale
        val openingOperationId = existingDebt["openingOperationId"]
            ?: existingDebt["operationId"]
            ?: throw SparkMutationFailure.CorruptRemoteData

        transaction.set(
            debtRef,
            LinkedHashMap(debtSnapshot).apply {
                this["schemaVersion"] = SparkFirestoreSchema.SCHEMA_VERSION
                this["operationId"] = openingOperationId
                this["openingOperationId"] = openingOperationId
                this["lastOperationId"] = operationId
                this["expectedDebtVersion"] = document.expectedDebtVersion
                this["lastPaymentId"] = document.paymentId.value
                this["lastPaymentReceiptId"] = receiptId
                this["inventorySeq"] = plan.seq
                this["actorUid"] = session.uid
                this["authorizedRole"] = BusinessRole.OWNER.name
                this["syncedAt"] = serverTimestamp
            },
            SetOptions.merge(),
        )
        transaction.set(
            paymentRef,
            LinkedHashMap(paymentSnapshot).apply {
                this["schemaVersion"] = SparkFirestoreSchema.SCHEMA_VERSION
                this["operationId"] = operationId
                this["syncPayloadHash"] = payloadHash
                this["actorUid"] = session.uid
                this["authorizedRole"] = BusinessRole.OWNER.name
                this["receiptId"] = receiptId
                this["inventorySeq"] = plan.seq
                this["resultingDebt"] = debtSnapshot
                this["syncedAt"] = serverTimestamp
            },
        )
        transaction.set(
            changeRef,
            linkedMapOf(
                "schemaVersion" to SparkFirestoreSchema.SCHEMA_VERSION,
                "businessId" to cloudBusinessId.value,
                "operationId" to operationId,
                "kind" to "DEBT_PAYMENT",
                "seq" to plan.seq,
                "receiptId" to receiptId,
                "sale" to null,
                "balances" to emptyList<Map<String, Any?>>(),
                "debt" to debtSnapshot,
                "payment" to paymentSnapshot,
                "syncedAt" to serverTimestamp,
            ),
        )
        transaction.set(
            keyRef,
            linkedMapOf(
                "schemaVersion" to SparkFirestoreSchema.SCHEMA_VERSION,
                "businessId" to cloudBusinessId.value,
                "operationId" to operationId,
                "debtId" to document.debtId.value,
                "paymentId" to document.paymentId.value,
                "receiptId" to receiptId,
                "seq" to plan.seq,
                "requestHash" to payloadHash,
                "expectedDebtVersion" to document.expectedDebtVersion,
                "resultingDebtVersion" to plan.debt.version,
                "createdAt" to serverTimestamp,
            ),
        )
        transaction.set(
            metadataRef,
            linkedMapOf(
                "schemaVersion" to SparkFirestoreSchema.SCHEMA_VERSION,
                "businessId" to cloudBusinessId.value,
                "seq" to plan.seq,
                "lastKind" to "DEBT_PAYMENT",
                "lastReceiptId" to receiptId,
                "lastOperationId" to operationId,
                "updatedAt" to serverTimestamp,
            ),
            SetOptions.merge(),
        )
        return SparkDebtPaymentAck(plan.seq, plan.debt, plan.payment)
    }
}

private data class SparkDebtPaymentAck(
    val seq: Long,
    val debt: SharedDebtSnapshot,
    val payment: SharedDebtPayment,
)

internal fun debtPaymentPayloadHash(
    businessId: BusinessId,
    document: RemoteDebtPaymentDocument,
): String {
    val wireDocument = linkedMapOf<String, Any?>(
        "version" to 1L,
        "paymentId" to document.paymentId.value,
        "debtId" to document.debtId.value,
        "businessId" to businessId.value,
        "currency" to document.amount.currency.value,
        "amountMinorUnits" to document.amount.minorUnits,
        "method" to document.method.name,
        "note" to document.note,
        "reference" to document.reference,
        "expectedDebtVersion" to document.expectedDebtVersion,
        "occurredAt" to document.occurredAt.toEpochMilli(),
        "createdAt" to document.createdAt.toEpochMilli(),
    )
    return SparkFirestoreSchema.sha256(
        SparkFirestoreSchema.jsonStringify(
            linkedMapOf(
                "businessId" to businessId.value,
                "idempotencyKey" to document.idempotencyKey,
                "operationType" to "SYNC_DEBT_PAYMENT",
                "payloadVersion" to 1L,
                "document" to wireDocument,
            ),
        ),
    )
}

private fun requireOwnedDebtBusiness(snapshot: DocumentSnapshot, uid: String) {
    val data = snapshot.data ?: throw SparkMutationFailure.Rejected
    if (
        data["ownerUid"] != uid || data["accountDeletionLocked"] == true ||
        data["sparkDirectWritesEnabled"] == false
    ) {
        throw SparkMutationFailure.Rejected
    }
}

private fun replayPayment(
    payment: DocumentSnapshot,
    key: DocumentSnapshot,
    change: DocumentSnapshot,
    cloudBusinessId: BusinessId,
    document: RemoteDebtPaymentDocument,
    operationId: String,
    receiptId: String,
    payloadHash: String,
): SparkDebtPaymentAck {
    val saved = payment.data ?: throw SparkMutationFailure.CorruptRemoteData
    val keyData = key.data ?: throw SparkMutationFailure.CorruptRemoteData
    val changeData = change.data ?: throw SparkMutationFailure.CorruptRemoteData
    if (
        saved["operationId"] != operationId || saved["syncPayloadHash"] != payloadHash ||
        saved["receiptId"] != receiptId || keyData["operationId"] != operationId ||
        keyData["requestHash"] != payloadHash || changeData["operationId"] != operationId ||
        changeData["receiptId"] != receiptId
    ) {
        throw SparkMutationFailure.Rejected
    }
    val seq = saved.safeDebtLong("inventorySeq")
    if (keyData.safeDebtLong("seq") != seq || changeData.safeDebtLong("seq") != seq) {
        throw SparkMutationFailure.CorruptRemoteData
    }
    @Suppress("UNCHECKED_CAST")
    val rawDebt = saved["resultingDebt"] as? Map<String, Any?>
        ?: throw SparkMutationFailure.CorruptRemoteData
    val resultingDebt = SparkDebtPaymentMutationPlanner.parseDebt(
        raw = rawDebt,
        expectedBusinessId = cloudBusinessId,
        expectedDebtId = document.debtId,
        requireSchemaVersion = false,
    )
    val parsedPayment = parseDebtPayment(saved, resultingDebt)
    if (parsedPayment.paymentId != document.paymentId || parsedPayment.idempotencyKey != operationId) {
        throw SparkMutationFailure.Rejected
    }
    return SparkDebtPaymentAck(seq, resultingDebt, parsedPayment)
}

private fun Map<String, Any?>.safeDebtLong(key: String): Long = when (val value = this[key]) {
    is Byte -> value.toLong()
    is Short -> value.toLong()
    is Int -> value.toLong()
    is Long -> value
    is Float -> value.takeIf { it.isFinite() && it % 1f == 0f }?.toLong()
    is Double -> value.takeIf { it.isFinite() && it % 1.0 == 0.0 }?.toLong()
    else -> null
} ?: throw SparkMutationFailure.CorruptRemoteData

private fun mapDebtFailure(failure: Exception): RemoteDebtPaymentResult = when (failure) {
    is FirebaseNetworkException, is IOException -> RemoteDebtPaymentResult.OnlineRequired
    is FirebaseFirestoreException -> when (failure.code) {
        FirebaseFirestoreException.Code.ABORTED -> RemoteDebtPaymentResult.Stale
        FirebaseFirestoreException.Code.UNAVAILABLE,
        FirebaseFirestoreException.Code.DEADLINE_EXCEEDED,
        FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED,
        FirebaseFirestoreException.Code.UNAUTHENTICATED,
        FirebaseFirestoreException.Code.CANCELLED,
        -> RemoteDebtPaymentResult.OnlineRequired
        else -> RemoteDebtPaymentResult.Rejected
    }
    else -> RemoteDebtPaymentResult.Rejected
}
