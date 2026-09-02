package com.facturastock.app.domain.repository

import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.model.CatalogSyncPullPage
import com.facturastock.app.domain.model.id.BusinessId

/** Transporte de lectura incremental del catálogo cloud; no escribe Room ni toca la UI. */
interface RemoteCatalogRepository {
    val available: Boolean

    suspend fun pullCatalogChanges(
        businessId: BusinessId,
        sinceSeq: Long,
        limit: Int,
    ): DomainResult<CatalogSyncPullPage>
}
