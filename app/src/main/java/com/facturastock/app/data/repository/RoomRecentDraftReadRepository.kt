package com.facturastock.app.data.repository

import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.data.local.dao.InvoiceDraftDao
import com.facturastock.app.data.local.mapper.toDomain
import com.facturastock.app.domain.model.RecentDraft
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.RecentDraftReadRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** Proyección Room única y reactiva para el feed acotado de Home. */
class RoomRecentDraftReadRepository @Inject constructor(
    private val invoiceDraftDao: InvoiceDraftDao,
    private val dispatchers: DispatcherProvider,
) : RecentDraftReadRepository {
    override fun observeRecent(
        businessId: BusinessId,
        limit: Int,
    ): Flow<List<RecentDraft>> {
        require(limit in 1..MAX_RECENT_DRAFT_LIMIT) {
            "limit debe estar entre 1 y $MAX_RECENT_DRAFT_LIMIT"
        }
        return invoiceDraftDao.observeRecentForBusiness(businessId.value, limit)
            .map { rows ->
                rows.map { row ->
                    RecentDraft(
                        draft = row.draft.toDomain(),
                        supplierName = row.supplierName,
                    )
                }
            }
            .flowOn(dispatchers.io)
    }

    private companion object {
        const val MAX_RECENT_DRAFT_LIMIT = 50
    }
}
