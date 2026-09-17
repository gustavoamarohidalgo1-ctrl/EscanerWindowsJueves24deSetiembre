package com.facturastock.app.data.repository

import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import androidx.room.withTransaction
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.dao.DebtPaymentReportRow
import com.facturastock.app.data.local.dao.DebtSummaryRow
import com.facturastock.app.data.local.dao.DebtWithGraph
import com.facturastock.app.data.local.entity.DebtEntity
import com.facturastock.app.data.local.entity.DebtPaymentEntity
import com.facturastock.app.data.local.entity.SaleLineEntity
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.DebtDetail
import com.facturastock.app.domain.model.DebtLine
import com.facturastock.app.domain.model.DebtPayment
import com.facturastock.app.domain.model.DebtPaymentReportItem
import com.facturastock.app.domain.model.DebtPaymentMethod
import com.facturastock.app.domain.model.DebtStatus
import com.facturastock.app.domain.model.DebtSummary
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.normalizeOptionalDebtText
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DebtId
import com.facturastock.app.domain.model.id.DebtPaymentId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.SaleId
import com.facturastock.app.domain.model.id.SaleLineId
import com.facturastock.app.domain.repository.DebtRepository
import com.facturastock.app.domain.repository.DisabledRemoteDebtSyncRepository
import com.facturastock.app.domain.repository.RecordDebtPaymentCommand
import com.facturastock.app.domain.repository.RecordDebtPaymentResult
import com.facturastock.app.domain.repository.RemoteDebtPaymentDocument
import com.facturastock.app.domain.repository.RemoteDebtPaymentResult
import com.facturastock.app.domain.repository.RemoteDebtSyncRepository
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class RoomDebtRepository @Inject constructor(
    private val database: FacturaStockDatabase,
    private val dispatchers: DispatcherProvider,
    private val remote: RemoteDebtSyncRepository = DisabledRemoteDebtSyncRepository,
) : DebtRepository {
    override fun observeOpen(businessId: BusinessId): Flow<List<DebtSummary>> =
        observeSummaries(businessId, onlyOpen = true)

    override fun observeAll(businessId: BusinessId): Flow<List<DebtSummary>> =
        observeSummaries(businessId, onlyOpen = false)

    private fun observeSummaries(
        businessId: BusinessId,
        onlyOpen: Boolean,
    ): Flow<List<DebtSummary>> = database.debtDao()
        .observeSummaries(businessId.value, onlyOpen)
        .map { rows -> rows.map { row -> row.toDomain() } }
        .flowOn(dispatchers.io)

    override fun observeDetail(
        businessId: BusinessId,
        debtId: DebtId,
    ): Flow<DebtDetail?> = database.debtDao()
        .observeDetail(businessId.value, debtId.value)
        .map { graph -> graph?.toDomain() }
        .flowOn(dispatchers.io)

    override fun observePaymentsInRange(
        businessId: BusinessId,
        startInclusive: Instant,
        endExclusive: Instant,
    ): Flow<List<DebtPaymentReportItem>> = database.debtDao()
        .observePaymentsInRange(businessId.value, startInclusive.toEpochMilli(), endExclusive.toEpochMilli())
        .distinctUntilChanged()
        .map { rows -> rows.map { it.toDomain() } }
        .flowOn(dispatchers.io)

    override suspend fun recordPayment(
        businessId: BusinessId,
        command: RecordDebtPaymentCommand,
    ): RecordDebtPaymentResult = withContext(dispatchers.io) {
        val prepared = try {
            preparePayment(businessId, command)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalArgumentException) {
            return@withContext RecordDebtPaymentResult.InvalidPayment
        } catch (failure: SQLiteFullException) {
            throw StorageException(StorageError.InsufficientSpace, failure)
        } catch (failure: SQLiteException) {
            throw StorageException(StorageError.Unavailable, failure)
        }
        if (prepared is PreparedDebtPayment.Rejected) return@withContext prepared.result
        val ready = prepared as PreparedDebtPayment.Ready

        val remoteResult = try {
            remote.postPayment(businessId, ready.document)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            RemoteDebtPaymentResult.OnlineRequired
        }
        val authorization = when (remoteResult) {
            RemoteDebtPaymentResult.NotRequired -> PaymentAuthorization(
                balanceAfter = ready.localBalanceAfter,
                debtVersion = command.expectedVersion + 1L,
                occurredAt = ready.document.occurredAt,
                createdAt = ready.document.createdAt,
            )
            is RemoteDebtPaymentResult.Authorized -> {
                if (
                    remoteResult.paymentId != ready.document.paymentId ||
                    remoteResult.debtId != command.debtId ||
                    remoteResult.balanceAfter.currency != command.amount.currency ||
                    remoteResult.balanceAfter.minorUnits != ready.localBalanceAfter.minorUnits ||
                    remoteResult.debtVersion != command.expectedVersion + 1L ||
                    remoteResult.createdAt < remoteResult.occurredAt
                ) {
                    return@withContext RecordDebtPaymentResult.RemoteRejected
                }
                PaymentAuthorization(
                    balanceAfter = remoteResult.balanceAfter,
                    debtVersion = remoteResult.debtVersion,
                    occurredAt = remoteResult.occurredAt,
                    createdAt = remoteResult.createdAt,
                )
            }
            RemoteDebtPaymentResult.OnlineRequired ->
                return@withContext RecordDebtPaymentResult.OnlineRequired
            RemoteDebtPaymentResult.Stale -> return@withContext RecordDebtPaymentResult.Stale
            RemoteDebtPaymentResult.Rejected ->
                return@withContext RecordDebtPaymentResult.RemoteRejected
        }
        commitPayment(businessId, command, ready, authorization)
    }

    private suspend fun preparePayment(
        businessId: BusinessId,
        command: RecordDebtPaymentCommand,
    ): PreparedDebtPayment {
        val debt = database.debtDao().findDebt(command.debtId.value)
            ?: return PreparedDebtPayment.Rejected(RecordDebtPaymentResult.NotFound)
        if (debt.businessId != businessId.value) {
            return PreparedDebtPayment.Rejected(RecordDebtPaymentResult.NotFound)
        }
        if (database.saleVoidDao().findBySaleId(businessId.value, debt.saleId) != null) {
            return PreparedDebtPayment.Rejected(RecordDebtPaymentResult.NotFound)
        }
        val paymentId = deterministicPaymentId(command.debtId, command.expectedVersion)
        val idempotencyKey = paymentId.idempotencyKey(command.debtId)
        val eventAt = maxOf(command.occurredAt.toEpochMilli(), debt.updatedAt)
        database.debtDao().findPaymentByIdempotencyKey(idempotencyKey)?.let { existing ->
            return if (existing.matches(command, businessId, paymentId)) {
                val summary = database.debtDao().findSummary(businessId.value, command.debtId.value)
                    ?.toDomain() ?: return PreparedDebtPayment.Rejected(RecordDebtPaymentResult.NotFound)
                PreparedDebtPayment.Rejected(
                    RecordDebtPaymentResult.AlreadyRecorded(summary, existing.toDomain()),
                )
            } else {
                PreparedDebtPayment.Rejected(RecordDebtPaymentResult.RetryableConflict)
            }
        }
        if (debt.status == DebtStatus.PAID.name) {
            return PreparedDebtPayment.Rejected(RecordDebtPaymentResult.AlreadyPaid)
        }
        if (debt.version != command.expectedVersion) {
            return PreparedDebtPayment.Rejected(RecordDebtPaymentResult.Stale)
        }
        if (debt.currencyCode != command.amount.currency.value) {
            return PreparedDebtPayment.Rejected(RecordDebtPaymentResult.CurrencyMismatch)
        }
        if (command.amount.minorUnits > debt.balanceMinorUnits) {
            return PreparedDebtPayment.Rejected(RecordDebtPaymentResult.AmountExceedsBalance)
        }
        val note = normalizeOptionalDebtText(command.note, 500)
        val reference = normalizeOptionalDebtText(command.reference, 120)
        val balanceAfter = Money.ofMinor(
            debt.balanceMinorUnits - command.amount.minorUnits,
            command.amount.currency,
        )
        return PreparedDebtPayment.Ready(
            document = RemoteDebtPaymentDocument(
                paymentId = paymentId,
                debtId = command.debtId,
                businessId = businessId,
                amount = command.amount,
                method = command.method,
                note = note,
                reference = reference,
                expectedDebtVersion = command.expectedVersion,
                occurredAt = Instant.ofEpochMilli(eventAt),
                createdAt = Instant.ofEpochMilli(eventAt),
                idempotencyKey = idempotencyKey,
            ),
            localBalanceAfter = balanceAfter,
        )
    }

    private suspend fun commitPayment(
        businessId: BusinessId,
        command: RecordDebtPaymentCommand,
        ready: PreparedDebtPayment.Ready,
        authorization: PaymentAuthorization,
    ): RecordDebtPaymentResult {
        try {
            return database.withTransaction {
                val dao = database.debtDao()
                val currentDebt = dao.findDebt(command.debtId.value)
                    ?: return@withTransaction RecordDebtPaymentResult.NotFound
                if (currentDebt.businessId != businessId.value ||
                    database.saleVoidDao().findBySaleId(businessId.value, currentDebt.saleId) != null
                ) return@withTransaction RecordDebtPaymentResult.NotFound
                dao.findPaymentByIdempotencyKey(ready.document.idempotencyKey)?.let { existing ->
                    if (existing.matches(
                            command,
                            businessId,
                            ready.document.paymentId,
                        )
                    ) {
                        val summary = checkNotNull(
                            dao.findSummary(businessId.value, command.debtId.value),
                        ).toDomain()
                        return@withTransaction RecordDebtPaymentResult.AlreadyRecorded(
                            summary,
                            existing.toDomain(),
                        )
                    }
                    return@withTransaction RecordDebtPaymentResult.RetryableConflict
                }
                val debt = dao.findDebt(command.debtId.value)
                    ?: return@withTransaction RecordDebtPaymentResult.NotFound
                if (debt.businessId != businessId.value) {
                    return@withTransaction RecordDebtPaymentResult.NotFound
                }
                if (debt.status == DebtStatus.PAID.name) {
                    return@withTransaction RecordDebtPaymentResult.AlreadyPaid
                }
                if (debt.version != command.expectedVersion) {
                    return@withTransaction RecordDebtPaymentResult.Stale
                }
                if (debt.currencyCode != command.amount.currency.value) {
                    return@withTransaction RecordDebtPaymentResult.CurrencyMismatch
                }
                val expectedBalance = debt.balanceMinorUnits - command.amount.minorUnits
                if (expectedBalance < 0L) {
                    return@withTransaction RecordDebtPaymentResult.AmountExceedsBalance
                }
                if (
                    authorization.balanceAfter.minorUnits != expectedBalance ||
                    authorization.debtVersion != debt.version + 1L
                ) {
                    return@withTransaction RecordDebtPaymentResult.RemoteRejected
                }
                val createdAt = authorization.createdAt.toEpochMilli()
                val occurredAt = authorization.occurredAt.toEpochMilli()
                if (createdAt < debt.updatedAt || createdAt < occurredAt) {
                    return@withTransaction RecordDebtPaymentResult.RemoteRejected
                }
                val payment = DebtPaymentEntity(
                    paymentId = ready.document.paymentId.value,
                    debtId = debt.debtId,
                    businessId = debt.businessId,
                    currencyCode = debt.currencyCode,
                    amountMinorUnits = command.amount.minorUnits,
                    method = command.method.name,
                    note = ready.document.note,
                    reference = ready.document.reference,
                    expectedDebtVersion = debt.version,
                    balanceAfterMinorUnits = expectedBalance,
                    idempotencyKey = ready.document.idempotencyKey,
                    occurredAt = occurredAt,
                    createdAt = createdAt,
                )
                dao.insertPayment(payment)
                val paid = expectedBalance == 0L
                if (
                    dao.applyPaymentIfVersion(
                        debtId = debt.debtId,
                        businessId = debt.businessId,
                        expectedVersion = debt.version,
                        currencyCode = debt.currencyCode,
                        balanceAfterMinorUnits = expectedBalance,
                        status = if (paid) DebtStatus.PAID.name else DebtStatus.OPEN.name,
                        updatedAt = createdAt,
                        paidAt = createdAt.takeIf { paid },
                    ) != 1
                ) {
                    throw DebtPaymentRace()
                }
                val summary = checkNotNull(dao.findSummary(businessId.value, debt.debtId)).toDomain()
                RecordDebtPaymentResult.Recorded(summary, payment.toDomain())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: DebtPaymentRace) {
            return RecordDebtPaymentResult.RetryableConflict
        } catch (_: SQLiteConstraintException) {
            return classifyConstraintConflict(businessId, command, ready)
        } catch (failure: SQLiteFullException) {
            throw StorageException(StorageError.InsufficientSpace, failure)
        } catch (failure: SQLiteException) {
            throw StorageException(StorageError.Unavailable, failure)
        }
    }

    private suspend fun classifyConstraintConflict(
        businessId: BusinessId,
        command: RecordDebtPaymentCommand,
        ready: PreparedDebtPayment.Ready,
    ): RecordDebtPaymentResult {
        val currentDebt = database.debtDao().findDebt(command.debtId.value)
        if (currentDebt != null && currentDebt.businessId == businessId.value &&
            database.saleVoidDao().findBySaleId(businessId.value, currentDebt.saleId) != null
        ) return RecordDebtPaymentResult.NotFound
        database.debtDao().findPaymentByIdempotencyKey(ready.document.idempotencyKey)
            ?.let { existing ->
                if (existing.matches(
                        command,
                        businessId,
                        ready.document.paymentId,
                    )
                ) {
                    val summary = database.debtDao()
                        .findSummary(businessId.value, command.debtId.value)
                        ?.toDomain()
                        ?: return RecordDebtPaymentResult.NotFound
                    return RecordDebtPaymentResult.AlreadyRecorded(summary, existing.toDomain())
                }
            }
        val debt = database.debtDao().findDebt(command.debtId.value)
            ?: return RecordDebtPaymentResult.NotFound
        return if (debt.businessId != businessId.value) {
            RecordDebtPaymentResult.NotFound
        } else if (debt.status == DebtStatus.PAID.name) {
            RecordDebtPaymentResult.AlreadyPaid
        } else if (debt.version != command.expectedVersion) {
            RecordDebtPaymentResult.Stale
        } else {
            RecordDebtPaymentResult.RetryableConflict
        }
    }

    private fun DebtWithGraph.toDomain(): DebtDetail {
        val currency = CurrencyCode.of(debt.currencyCode)
        val orderedLines = lines.sortedBy(SaleLineEntity::position)
        val summary = debt.toDomainSummary(orderedLines.size)
        return DebtDetail(
            debt = summary,
            lines = orderedLines.map { line ->
                DebtLine(
                    saleLineId = checkNotNull(SaleLineId.parse(line.saleLineId)),
                    productId = checkNotNull(ProductId.parse(line.productId)),
                    productName = line.productNameSnapshot,
                    barcode = line.barcodeSnapshot,
                    quantity = Quantity.of(line.quantity),
                    unitPrice = Money.ofMinor(requireNotNull(line.unitPriceMinorUnits), currency),
                    lineTotal = Money.ofMinor(requireNotNull(line.lineTotalMinorUnits), currency),
                )
            },
            payments = payments.sortedWith(
                compareByDescending<DebtPaymentEntity> { it.occurredAt }
                    .thenByDescending { it.paymentId },
            ).map { payment -> payment.toDomain() },
        )
    }

    private fun DebtPaymentEntity.matches(
        command: RecordDebtPaymentCommand,
        businessId: BusinessId,
        expectedPaymentId: DebtPaymentId,
    ): Boolean = paymentId == expectedPaymentId.value && debtId == command.debtId.value &&
        this.businessId == businessId.value && currencyCode == command.amount.currency.value &&
        amountMinorUnits == command.amount.minorUnits && method == command.method.name &&
        note == normalizeOptionalDebtText(command.note, 500) &&
        reference == normalizeOptionalDebtText(command.reference, 120) &&
        expectedDebtVersion == command.expectedVersion

    private fun deterministicPaymentId(debtId: DebtId, expectedVersion: Long): DebtPaymentId =
        DebtPaymentId.from(
            SaleContentIdentity.uuid("debt-payment", debtId.value, expectedVersion.toString()),
        )

    private fun DebtPaymentId.idempotencyKey(debtId: DebtId): String =
        "debt-payment:v1:${debtId.value}:$value"
}

private fun DebtPaymentEntity.toDomain(): DebtPayment {
    val currency = CurrencyCode.of(currencyCode)
    return DebtPayment(
        paymentId = checkNotNull(DebtPaymentId.parse(paymentId)),
        debtId = checkNotNull(DebtId.parse(debtId)),
        amount = Money.ofMinor(amountMinorUnits, currency),
        method = DebtPaymentMethod.valueOf(method),
        note = note,
        reference = reference,
        expectedDebtVersion = expectedDebtVersion,
        balanceAfter = Money.ofMinor(balanceAfterMinorUnits, currency),
        occurredAt = Instant.ofEpochMilli(occurredAt),
        createdAt = Instant.ofEpochMilli(createdAt),
    )
}


internal fun DebtSummaryRow.toDomain(): DebtSummary {
    require(lineCount > 0) { "Una deuda persistida debe conservar líneas de venta" }
    return debt.toDomainSummary(lineCount)
}

private fun DebtEntity.toDomainSummary(lineCount: Int): DebtSummary {
    val currency = CurrencyCode.of(currencyCode)
    return DebtSummary(
        debtId = checkNotNull(DebtId.parse(debtId)),
        businessId = checkNotNull(BusinessId.parse(businessId)),
        saleId = checkNotNull(SaleId.parse(saleId)),
        debtorName = debtorName,
        originalAmount = Money.ofMinor(originalAmountMinorUnits, currency),
        balance = Money.ofMinor(balanceMinorUnits, currency),
        status = DebtStatus.valueOf(status),
        lineCount = lineCount,
        dueAt = dueAt?.let(Instant::ofEpochMilli),
        version = version,
        createdAt = Instant.ofEpochMilli(createdAt),
        updatedAt = Instant.ofEpochMilli(updatedAt),
        paidAt = paidAt?.let(Instant::ofEpochMilli),
    )
}

private sealed interface PreparedDebtPayment {
    data class Ready(
        val document: RemoteDebtPaymentDocument,
        val localBalanceAfter: Money,
    ) : PreparedDebtPayment

    data class Rejected(val result: RecordDebtPaymentResult) : PreparedDebtPayment
}

private data class PaymentAuthorization(
    val balanceAfter: Money,
    val debtVersion: Long,
    val occurredAt: Instant,
    val createdAt: Instant,
)

private class DebtPaymentRace : RuntimeException()

internal fun DebtPaymentReportRow.toDomain(): DebtPaymentReportItem = DebtPaymentReportItem(
    businessId = checkNotNull(BusinessId.parse(payment.businessId)),
    saleId = checkNotNull(SaleId.parse(saleId)),
    debtorName = debtorName,
    payment = payment.toDomain(),
)
