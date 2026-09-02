package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.model.DebtDetail
import com.facturastock.app.domain.model.DebtSummary
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DebtId
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.DebtRepository
import com.facturastock.app.domain.repository.RecordDebtPaymentCommand
import com.facturastock.app.domain.repository.RecordDebtPaymentResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

class ObserveDebtsUseCase(
    private val configuration: AppConfigurationRepository,
    private val repository: DebtRepository,
) {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    operator fun invoke(includePaid: Boolean = false): Flow<List<DebtSummary>> =
        configuration.observe()
            .map { it.activeBusinessId }
            .distinctUntilChanged()
            .flatMapLatest { businessId ->
                if (businessId == null) flowOf(emptyList())
                else if (includePaid) repository.observeAll(businessId)
                else repository.observeOpen(businessId)
            }

    fun forBusiness(
        businessId: BusinessId,
        includePaid: Boolean = false,
    ): Flow<List<DebtSummary>> = if (includePaid) {
        repository.observeAll(businessId)
    } else {
        repository.observeOpen(businessId)
    }
}

class ObserveDebtDetailUseCase(
    private val configuration: AppConfigurationRepository,
    private val repository: DebtRepository,
) {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    operator fun invoke(debtId: DebtId): Flow<DebtDetail?> = configuration.observe()
        .map { it.activeBusinessId }
        .distinctUntilChanged()
        .flatMapLatest { businessId ->
            if (businessId == null) flowOf(null)
            else repository.observeDetail(businessId, debtId)
        }
}

class RecordDebtPaymentUseCase(
    private val configuration: AppConfigurationRepository,
    private val repository: DebtRepository,
) {
    suspend operator fun invoke(command: RecordDebtPaymentCommand): RecordDebtPaymentResult {
        val businessId = configuration.current().activeBusinessId
            ?: return RecordDebtPaymentResult.NoActiveBusiness
        return repository.recordPayment(businessId, command)
    }

    suspend fun forBusiness(
        businessId: BusinessId,
        command: RecordDebtPaymentCommand,
    ): RecordDebtPaymentResult = repository.recordPayment(businessId, command)
}
