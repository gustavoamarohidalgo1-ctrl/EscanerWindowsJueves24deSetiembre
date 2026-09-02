package com.facturastock.app.data.account

import com.facturastock.app.domain.model.AccountSession
import com.facturastock.app.domain.model.BusinessRole
import com.facturastock.app.domain.model.PurchaseOverrideActor
import com.facturastock.app.domain.model.PurchaseOverrideRole
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.AccountRepository
import com.facturastock.app.domain.repository.PurchaseOverrideAuthorizationRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * Autoriza con la identidad autenticada y el último rol de membresía confirmado y persistido,
 * nunca con datos de UI. La caché permite operar offline y se reconcilia al refrescar membresías.
 */
class CloudPurchaseOverrideAuthorizationRepository @Inject constructor(
    private val accountRepository: AccountRepository,
) : PurchaseOverrideAuthorizationRepository {
    override suspend fun currentActor(businessId: BusinessId): PurchaseOverrideActor? {
        val session = accountRepository.observeSession().first() as? AccountSession.Active
            ?: return null
        val link = session.link?.takeIf { it.localBusinessId == businessId } ?: return null
        return PurchaseOverrideActor(
            actorId = session.uid,
            role = link.role.toPurchaseOverrideRole(),
        )
    }
}

private fun BusinessRole.toPurchaseOverrideRole(): PurchaseOverrideRole = when (this) {
    BusinessRole.OWNER -> PurchaseOverrideRole.OWNER
    BusinessRole.ADMIN -> PurchaseOverrideRole.MANAGER
    BusinessRole.OPERATOR,
    BusinessRole.READER,
    -> PurchaseOverrideRole.OPERATOR
}
