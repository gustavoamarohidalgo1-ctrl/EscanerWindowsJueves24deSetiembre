package com.facturastock.app.data.sync

import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.model.CatalogSyncPullPage
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.RemoteCatalogRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UnavailableRemoteCatalogRepository @Inject constructor() : RemoteCatalogRepository {
    override val available: Boolean = false

    override suspend fun pullCatalogChanges(
        businessId: BusinessId,
        sinceSeq: Long,
        limit: Int,
    ): DomainResult<CatalogSyncPullPage> = DomainResult.Failure(AccountError.Unavailable)
}
