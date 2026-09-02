package com.facturastock.app.domain.usecase

import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.model.HomeDashboardRead
import com.facturastock.app.domain.model.HomeDashboardSnapshot
import com.facturastock.app.domain.model.HomeDraftOverview
import com.facturastock.app.domain.model.HomeInventoryOverview
import com.facturastock.app.domain.model.HomePurchaseOverview
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.BusinessRepository
import com.facturastock.app.domain.repository.HomeDashboardReadRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Observa en una sola frontera de tenant los datos que necesita Inicio. Capturar el contexto
 * antes de abrir los flows evita instantáneas transitorias que mezclen el negocio real y
 * el de demostración.
 */
class ObserveHomeDashboardUseCase(
    private val configurationRepository: AppConfigurationRepository,
    private val businessRepository: BusinessRepository,
    private val dashboardReadRepository: HomeDashboardReadRepository,
    private val clock: AppClock,
) {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    operator fun invoke(): Flow<HomeDashboardSnapshot> = configurationRepository.observe()
        .map { configuration ->
            HomeDashboardContext(
                businessId = configuration.activeBusinessId,
                isDemoMode = configuration.isDemoMode,
                zoneId = configuration.zoneId,
            )
        }
        .distinctUntilChanged()
        .flatMapLatest { context ->
            val businessId = context.businessId
                ?: return@flatMapLatest flowOf(
                    HomeDashboardSnapshot(
                        business = null,
                        isDemoMode = context.isDemoMode,
                        zoneId = context.zoneId,
                        overview = EmptyDashboardRead,
                        observedAt = clock.now(),
                    ),
                )

            combine(
                businessRepository.observeById(businessId),
                dashboardReadRepository.observe(businessId),
            ) { business, overview ->
                HomeDashboardSnapshot(
                    business = business,
                    isDemoMode = context.isDemoMode,
                    zoneId = context.zoneId,
                    overview = overview,
                    observedAt = clock.now(),
                )
            }
        }

    private data class HomeDashboardContext(
        val businessId: com.facturastock.app.domain.model.id.BusinessId?,
        val isDemoMode: Boolean,
        val zoneId: java.time.ZoneId,
    )

    private companion object {
        val EmptyDashboardRead = HomeDashboardRead(
            drafts = HomeDraftOverview(openCount = 0, recent = emptyList()),
            inventory = HomeInventoryOverview(
                productCount = 0,
                availableProductCount = 0,
                attentionProductCount = 0,
                withoutStockCount = 0,
                withoutSalePriceCount = 0,
                negativeStockCount = 0,
            ),
            purchases = HomePurchaseOverview(
                postedCount = 0,
                syncProblemCount = 0,
            ),
        )
    }
}
