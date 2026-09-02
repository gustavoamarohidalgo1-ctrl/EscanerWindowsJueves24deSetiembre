package com.facturastock.app.data.sync

import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.model.SharedInventoryPullPage
import com.facturastock.app.domain.model.SharedSaleDocument
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.RemoteSalePostResult
import com.facturastock.app.domain.repository.RemoteSaleSyncRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UnavailableRemoteSaleSyncRepository @Inject constructor() : RemoteSaleSyncRepository {
    override val available: Boolean = false

    override suspend fun postSale(
        localBusinessId: BusinessId,
        document: SharedSaleDocument,
    ): RemoteSalePostResult = RemoteSalePostResult.NotRequired

    override suspend fun pullInventoryChanges(
        cloudBusinessId: BusinessId,
        sinceSeq: Long,
        limit: Int,
    ): DomainResult<SharedInventoryPullPage> = DomainResult.Failure(AccountError.Unavailable)
}
