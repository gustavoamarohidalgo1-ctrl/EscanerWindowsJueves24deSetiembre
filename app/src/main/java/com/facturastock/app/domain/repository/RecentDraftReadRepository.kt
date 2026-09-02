package com.facturastock.app.domain.repository

import com.facturastock.app.domain.model.RecentDraft
import com.facturastock.app.domain.model.id.BusinessId
import kotlinx.coroutines.flow.Flow

/** Read-model acotado para el feed de borradores de Home. */
interface RecentDraftReadRepository {
    fun observeRecent(
        businessId: BusinessId,
        limit: Int,
    ): Flow<List<RecentDraft>>
}
