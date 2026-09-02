package com.facturastock.app.data.account

import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.model.BusinessInvitation
import com.facturastock.app.domain.model.BusinessRole
import com.facturastock.app.domain.model.CloudBusinessLink
import com.facturastock.app.domain.model.CloudBusinessLinkCasResult
import com.facturastock.app.domain.model.CloudBusinessLinkReceipt
import com.facturastock.app.domain.model.CloudMember
import com.facturastock.app.domain.model.CloudMembership
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.BusinessMembershipRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Binding productivo del flavor local: sin nube no hay negocios remotos, membresías ni
 * invitaciones. Todo responde [AccountError.Unavailable] y nunca hay enlace activo.
 */
@Singleton
class UnavailableMembershipRepository @Inject constructor() : BusinessMembershipRepository {
    override fun currentLocalBusinessIdentityEpoch(): Long = 0L

    override suspend fun listMyMemberships(
        expectedUid: String,
    ): DomainResult<List<CloudMembership>> = unavailable()

    override suspend fun createBusiness(
        expectedUid: String,
        displayName: String,
    ): DomainResult<CloudMembership> = unavailable()

    override suspend fun listMembers(
        expectedUid: String,
        businessId: BusinessId,
    ): DomainResult<List<CloudMember>> = unavailable()

    override suspend fun listBusinessInvitations(
        expectedUid: String,
        businessId: BusinessId,
    ): DomainResult<List<BusinessInvitation>> = unavailable()

    override suspend fun inviteMember(
        expectedUid: String,
        businessId: BusinessId,
        email: String,
        role: BusinessRole,
    ): DomainResult<Unit> = unavailable()

    override suspend fun listMyInvitations(
        expectedUid: String,
    ): DomainResult<List<BusinessInvitation>> = unavailable()

    override suspend fun acceptInvitation(
        expectedUid: String,
        businessId: BusinessId,
    ): DomainResult<CloudMembership> = unavailable()

    override suspend fun declineInvitation(
        expectedUid: String,
        businessId: BusinessId,
    ): DomainResult<Unit> = unavailable()

    override suspend fun changeMemberRole(
        expectedUid: String,
        businessId: BusinessId,
        memberUid: String,
        role: BusinessRole,
    ): DomainResult<Unit> = unavailable()

    override suspend fun removeMember(
        expectedUid: String,
        businessId: BusinessId,
        memberUid: String,
    ): DomainResult<Unit> = unavailable()

    override suspend fun setActiveCloudBusiness(
        expectedUid: String,
        expectedLocalIdentityEpoch: Long,
        link: CloudBusinessLink,
    ): DomainResult<CloudBusinessLinkReceipt> =
        unavailable()

    override suspend fun compareAndSetActiveCloudBusiness(
        expectedUid: String,
        expectedLocalIdentityEpoch: Long,
        expectedReceipt: CloudBusinessLinkReceipt,
        newLink: CloudBusinessLink?,
    ): DomainResult<CloudBusinessLinkCasResult> = unavailable()

    override suspend fun clearActiveCloudBusinessIfOwned(
        expectedUid: String,
        expectedReceipt: CloudBusinessLinkReceipt,
    ): DomainResult<Boolean> = unavailable()

    override fun observeActiveLink(): Flow<CloudBusinessLink?> = flowOf(null)

    private fun <T> unavailable(): DomainResult<T> =
        DomainResult.Failure(AccountError.Unavailable)
}
