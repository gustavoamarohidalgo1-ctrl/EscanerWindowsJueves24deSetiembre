package com.facturastock.app.data.sync

import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.model.RemotePurchaseDescription
import com.facturastock.app.domain.model.SyncPullPage
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.RemoteLedgerRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Binding productivo del flavor local: sin nube no hay libro remoto que leer. El puerto declara
 * no estar disponible y todo responde [AccountError.Unavailable]; el perfil local sigue
 * funcionando sin sincronización.
 */
@Singleton
class UnavailableRemoteLedgerRepository @Inject constructor() : RemoteLedgerRepository {
    override val available: Boolean = false

    override suspend fun pullChanges(
        businessId: BusinessId,
        sinceSeq: Long,
        limit: Int,
    ): DomainResult<SyncPullPage> = unavailable()

    override suspend fun describeRemotePurchase(
        businessId: BusinessId,
        remotePurchaseId: String,
    ): DomainResult<RemotePurchaseDescription?> = unavailable()

    private fun <T> unavailable(): DomainResult<T> =
        DomainResult.Failure(AccountError.Unavailable)
}
