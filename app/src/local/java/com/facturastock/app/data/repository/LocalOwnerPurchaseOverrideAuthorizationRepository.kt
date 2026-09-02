package com.facturastock.app.data.repository

import com.facturastock.app.domain.model.PurchaseOverrideActor
import com.facturastock.app.domain.model.PurchaseOverrideRole
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.PurchaseOverrideAuthorizationRepository
import javax.inject.Inject

/** El flavor estrictamente local representa al propietario del negocio activo. */
class LocalOwnerPurchaseOverrideAuthorizationRepository @Inject constructor() :
    PurchaseOverrideAuthorizationRepository {
    override suspend fun currentActor(businessId: BusinessId): PurchaseOverrideActor =
        PurchaseOverrideActor(
            actorId = businessId.value,
            role = PurchaseOverrideRole.OWNER,
        )
}
