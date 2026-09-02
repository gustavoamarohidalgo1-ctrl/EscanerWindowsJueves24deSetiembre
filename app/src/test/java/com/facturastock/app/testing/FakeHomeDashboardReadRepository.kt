package com.facturastock.app.testing

import com.facturastock.app.domain.model.HomeDashboardRead
import com.facturastock.app.domain.model.HomeDraftOverview
import com.facturastock.app.domain.model.HomeInventoryOverview
import com.facturastock.app.domain.model.HomePurchaseOverview
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.HomeDashboardReadRepository
import com.facturastock.app.domain.repository.RecentDraftReadRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

/**
 * Fake reactivo del read-model de Inicio. Cada negocio conserva un [MutableStateFlow]
 * independiente, como las consultas Room que sustituye.
 *
 * Cuando se proporciona [recentDraftReadRepository], la lista reciente se deriva de ese puerto y
 * reacciona a sus cambios. El `openCount` configurado se conserva como mínimo explícito, lo que
 * permite representar más borradores que los incluidos en la ventana reciente; con cero se deriva
 * completamente del repositorio de borradores.
 */
class FakeHomeDashboardReadRepository(
    private val recentDraftReadRepository: RecentDraftReadRepository? = null,
) : HomeDashboardReadRepository {
    private val dashboardFlows =
        mutableMapOf<BusinessId, MutableStateFlow<HomeDashboardRead>>()
    private val mutableObservedBusinessIds = mutableListOf<BusinessId>()

    val observedBusinessIds: List<BusinessId>
        get() = mutableObservedBusinessIds.toList()

    override fun observe(businessId: BusinessId): Flow<HomeDashboardRead> {
        mutableObservedBusinessIds += businessId
        val dashboard = dashboardFor(businessId)
        val recentDrafts = recentDraftReadRepository ?: return dashboard
        return combine(
            dashboard,
            recentDrafts.observeRecent(businessId, RECENT_DRAFT_QUERY_LIMIT),
        ) { current, observedDrafts ->
            current.copy(
                drafts = HomeDraftOverview(
                    openCount = maxOf(current.drafts.openCount, observedDrafts.size),
                    recent = observedDrafts.take(RECENT_DRAFT_DISPLAY_LIMIT),
                ),
            )
        }
    }

    /** Publica una instantánea completa para un solo negocio. */
    fun replaceDashboard(businessId: BusinessId, dashboard: HomeDashboardRead) {
        require(dashboard.drafts.recent.all { it.draft.businessId == businessId }) {
            "Los borradores del dashboard deben pertenecer al negocio observado"
        }
        dashboardFor(businessId).value = dashboard
    }

    private fun dashboardFor(
        businessId: BusinessId,
    ): MutableStateFlow<HomeDashboardRead> = dashboardFlows.getOrPut(businessId) {
        MutableStateFlow(EMPTY_DASHBOARD)
    }

    private companion object {
        const val RECENT_DRAFT_QUERY_LIMIT = 50
        const val RECENT_DRAFT_DISPLAY_LIMIT = 10

        val EMPTY_DASHBOARD = HomeDashboardRead(
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
