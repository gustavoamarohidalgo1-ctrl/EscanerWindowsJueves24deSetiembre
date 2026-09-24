package com.facturastock.app.data.sync

import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.RemoteDebtPaymentDocument
import com.facturastock.app.domain.repository.RemoteDebtPaymentResult
import com.facturastock.app.domain.repository.RemoteDebtSyncRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UnavailableRemoteDebtSyncRepository @Inject constructor() : RemoteDebtSyncRepository {
    override val available: Boolean = false

    override suspend fun postPayment(
        localBusinessId: BusinessId,
        document: RemoteDebtPaymentDocument,
    ): RemoteDebtPaymentResult = RemoteDebtPaymentResult.NotRequired
}
