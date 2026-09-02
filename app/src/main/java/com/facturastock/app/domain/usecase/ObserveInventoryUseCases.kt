package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.model.InventoryDiagnosticReport
import com.facturastock.app.domain.model.InventoryProductDetail
import com.facturastock.app.domain.model.InventoryReadItem
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.InventoryReadRepository
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

class ObserveInventoryUseCase(
    private val appConfigurationRepository: AppConfigurationRepository,
    private val inventoryReadRepository: InventoryReadRepository,
) {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<List<InventoryReadItem>> = appConfigurationRepository.observe()
        .map { it.activeBusinessId }
        .distinctUntilChanged()
        .flatMapLatest { businessId ->
            if (businessId == null) flowOf(emptyList())
            else inventoryReadRepository.observeInventory(businessId)
        }
}

class ObserveInventoryProductUseCase(
    private val appConfigurationRepository: AppConfigurationRepository,
    private val inventoryReadRepository: InventoryReadRepository,
) {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    operator fun invoke(productId: ProductId): Flow<InventoryProductDetail?> =
        appConfigurationRepository.observe()
            .map { it.activeBusinessId }
            .distinctUntilChanged()
            .flatMapLatest { businessId ->
                if (businessId == null) flowOf(null)
                else inventoryReadRepository.observeProduct(businessId, productId)
            }
}

class DiagnoseInventoryUseCase(
    private val appConfigurationRepository: AppConfigurationRepository,
    private val inventoryReadRepository: InventoryReadRepository,
) {
    suspend operator fun invoke(): InventoryDiagnosticReport {
        val businessId = appConfigurationRepository.current().activeBusinessId
            ?: return InventoryDiagnosticReport(Instant.EPOCH, emptyList())
        return inventoryReadRepository.diagnose(businessId)
    }
}
