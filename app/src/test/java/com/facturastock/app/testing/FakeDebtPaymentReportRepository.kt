package com.facturastock.app.testing

import com.facturastock.app.domain.model.DebtDetail
import com.facturastock.app.domain.model.DebtPaymentReportItem
import com.facturastock.app.domain.model.DebtSummary
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DebtId
import com.facturastock.app.domain.repository.DebtRepository
import com.facturastock.app.domain.repository.RecordDebtPaymentCommand
import com.facturastock.app.domain.repository.RecordDebtPaymentResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.Instant

class FakeDebtPaymentReportRepository : DebtRepository {
    val payments = MutableStateFlow<List<DebtPaymentReportItem>>(emptyList())
    val requests = mutableListOf<Triple<BusinessId, Instant, Instant>>()

    override fun observePaymentsInRange(
        businessId: BusinessId,
        startInclusive: Instant,
        endExclusive: Instant,
    ): Flow<List<DebtPaymentReportItem>> {
        requests += Triple(businessId, startInclusive, endExclusive)
        return payments.map { rows ->
            rows.filter {
                it.businessId == businessId && it.payment.occurredAt >= startInclusive && it.payment.occurredAt < endExclusive
            }
        }
    }

    override fun observeOpen(businessId: BusinessId): Flow<List<DebtSummary>> = flowOf(emptyList())

    override fun observeAll(businessId: BusinessId): Flow<List<DebtSummary>> = flowOf(emptyList())

    override fun observeDetail(
        businessId: BusinessId,
        debtId: DebtId,
    ): Flow<DebtDetail?> = flowOf(null)

    override suspend fun recordPayment(
        businessId: BusinessId,
        command: RecordDebtPaymentCommand,
    ): RecordDebtPaymentResult = error("This report fake does not record payments")
}
