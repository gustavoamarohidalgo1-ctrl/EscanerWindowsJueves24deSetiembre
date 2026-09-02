package com.facturastock.app.data.repository

import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.mapper.toDomain
import com.facturastock.app.domain.model.HomeDashboardRead
import com.facturastock.app.domain.model.HomeDraftOverview
import com.facturastock.app.domain.model.HomeInventoryOverview
import com.facturastock.app.domain.model.HomePurchaseOverview
import com.facturastock.app.domain.model.RecentDraft
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.HomeDashboardReadRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn

/** Consultas pequeñas y reactivas para Inicio; ningún historial completo sale de Room. */
class RoomHomeDashboardReadRepository @Inject constructor(
    private val database: FacturaStockDatabase,
    private val dispatchers: DispatcherProvider,
) : HomeDashboardReadRepository {
    override fun observe(businessId: BusinessId): Flow<HomeDashboardRead> = combine(
        database.invoiceDraftDao().observeOpenCountForBusiness(businessId.value),
        database.invoiceDraftDao().observeRecentForBusiness(businessId.value, RECENT_DRAFT_LIMIT),
        database.inventoryDao().observeHomeOverview(businessId.value),
        database.purchaseDao().observeHomeCounts(businessId.value),
    ) { openDraftCount, recentDraftRows, inventory, purchaseCounts ->
        val recentDrafts = recentDraftRows.map { row ->
            RecentDraft(
                draft = row.draft.toDomain(),
                supplierName = row.supplierName,
            )
        }
        HomeDashboardRead(
            drafts = HomeDraftOverview(
                // Room invalida ambos flows juntos, pero puede entregarlos en distinto orden.
                // Nunca expongas transitoriamente menos abiertos que tarjetas ya visibles.
                openCount = maxOf(openDraftCount, recentDrafts.size),
                recent = recentDrafts,
            ),
            inventory = HomeInventoryOverview(
                productCount = inventory.productCount,
                availableProductCount = inventory.availableProductCount,
                attentionProductCount = inventory.attentionProductCount,
                withoutStockCount = inventory.withoutStockCount,
                withoutSalePriceCount = inventory.withoutSalePriceCount,
                negativeStockCount = inventory.negativeStockCount,
            ),
            purchases = HomePurchaseOverview(
                postedCount = purchaseCounts.postedCount,
                syncProblemCount = purchaseCounts.syncProblemCount,
            ),
        )
    }.distinctUntilChanged()
        .flowOn(dispatchers.io)

    private companion object {
        const val RECENT_DRAFT_LIMIT = 10
    }
}
