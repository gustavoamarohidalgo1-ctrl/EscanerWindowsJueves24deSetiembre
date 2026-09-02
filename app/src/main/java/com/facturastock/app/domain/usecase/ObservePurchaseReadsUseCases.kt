package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.model.PurchaseReadDetail
import com.facturastock.app.domain.model.PurchaseHistoryPage
import com.facturastock.app.domain.model.PurchaseHistoryRequest
import com.facturastock.app.domain.model.PurchaseReadSummary
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.PurchaseReadRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Cambia de tenant de forma reactiva; demo y negocio real nunca comparten resultados. */
class ObservePurchasesUseCase(
    private val appConfigurationRepository: AppConfigurationRepository,
    private val purchaseReadRepository: PurchaseReadRepository,
) {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<List<PurchaseReadSummary>> = appConfigurationRepository.observe()
        .map { configuration -> configuration.activeBusinessId }
        .distinctUntilChanged()
        .flatMapLatest { businessId ->
            if (businessId == null) flowOf(emptyList())
            else purchaseReadRepository.observePurchases(businessId)
        }

    /** Identidad ya capturada por un coordinador; no vuelve a resolver otro tenant por su cuenta. */
    fun forBusiness(businessId: BusinessId): Flow<List<PurchaseReadSummary>> =
        purchaseReadRepository.observePurchases(businessId)
}

/**
 * Único acceso paginado del historial visual. El flujo exhaustivo de [ObservePurchasesUseCase]
 * permanece disponible para sincronización, exportación y otras reglas que requieren el libro
 * completo.
 */
class ObservePurchaseHistoryUseCase(
    private val appConfigurationRepository: AppConfigurationRepository,
    private val purchaseReadRepository: PurchaseReadRepository,
) {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    operator fun invoke(request: PurchaseHistoryRequest): Flow<PurchaseHistoryPage> =
        appConfigurationRepository.observe()
            .map { configuration -> configuration.activeBusinessId }
            .distinctUntilChanged()
            .flatMapLatest { businessId ->
                if (businessId == null) {
                    flowOf(PurchaseHistoryPage(items = emptyList(), hasMore = false))
                } else {
                    purchaseReadRepository.observePurchaseHistory(businessId, request)
                }
            }
}

/** El purchaseId por sí solo nunca autoriza cruzar el límite del negocio activo. */
class ObservePurchaseDetailUseCase(
    private val appConfigurationRepository: AppConfigurationRepository,
    private val purchaseReadRepository: PurchaseReadRepository,
) {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    operator fun invoke(purchaseId: PurchaseId): Flow<PurchaseReadDetail?> =
        appConfigurationRepository.observe()
            .map { configuration -> configuration.activeBusinessId }
            .distinctUntilChanged()
            .flatMapLatest { businessId ->
                if (businessId == null) flowOf(null)
                else purchaseReadRepository.observePurchase(businessId, purchaseId)
            }
}
