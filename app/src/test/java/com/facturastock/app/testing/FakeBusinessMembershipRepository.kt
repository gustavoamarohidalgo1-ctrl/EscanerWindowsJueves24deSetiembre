package com.facturastock.app.testing

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
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Fake de membresías e invitaciones con guion: las listas son datos programables, cada
 * operación registra sus argumentos exactos y los resultados `next*` se consumen una vez
 * (null = éxito por defecto). Los éxitos mantienen el estado en memoria como haría el
 * servidor: aceptar o rechazar retira la invitación, el alta agrega la membresía y fijar el
 * negocio activo reemite [observeActiveLink].
 */
class FakeBusinessMembershipRepository : BusinessMembershipRepository {

    var localBusinessIdentityEpoch: Long = 0L

    // --- Datos programables ---
    var memberships: List<CloudMembership> = emptyList()
    var members: List<CloudMember> = emptyList()
    var businessInvitations: List<BusinessInvitation> = emptyList()
    var myInvitations: List<BusinessInvitation> = emptyList()

    /** Membresía devuelta al aceptar una invitación; null = OPERATOR en el negocio aceptado. */
    var acceptedMembership: CloudMembership? = null

    // --- Guion: resultado de la próxima llamada; null = éxito por defecto ---
    var nextListMyMembershipsResult: DomainResult<List<CloudMembership>>? = null
    var nextListMembersResult: DomainResult<List<CloudMember>>? = null
    var nextListBusinessInvitationsResult: DomainResult<List<BusinessInvitation>>? = null
    var nextListMyInvitationsResult: DomainResult<List<BusinessInvitation>>? = null
    var nextCreateBusinessResult: DomainResult<CloudMembership>? = null
    var nextInviteMemberResult: DomainResult<Unit>? = null
    var nextAcceptInvitationResult: DomainResult<CloudMembership>? = null
    var nextDeclineInvitationResult: DomainResult<Unit>? = null
    var nextChangeMemberRoleResult: DomainResult<Unit>? = null
    var nextRemoveMemberResult: DomainResult<Unit>? = null
    var nextSetActiveCloudBusinessResult: DomainResult<Unit>? = null

    /** Hooks suspendibles para probar carreras/cancelación; si existen prevalecen sobre el guion. */
    var listMyMembershipsHandler:
        (suspend () -> DomainResult<List<CloudMembership>>)? = null
    var listMembersHandler:
        (suspend (BusinessId) -> DomainResult<List<CloudMember>>)? = null
    var listBusinessInvitationsHandler:
        (suspend (BusinessId) -> DomainResult<List<BusinessInvitation>>)? = null
    var setActiveCloudBusinessHandler:
        (suspend (CloudBusinessLink?) -> DomainResult<Unit>)? = null
    var compareAndSetActiveCloudBusinessHandler:
        (suspend (String, CloudBusinessLink?, CloudBusinessLink?) -> DomainResult<Boolean>)? = null

    // --- Registro de llamadas ---
    var listMyMembershipsCalls: Int = 0
        private set

    val listMyMembershipsUidCalls = mutableListOf<String>()

    var listMyInvitationsCalls: Int = 0
        private set

    val listMyInvitationsUidCalls = mutableListOf<String>()
    val listMembersCalls = mutableListOf<BusinessId>()
    val listMembersUidCalls = mutableListOf<String>()
    val listBusinessInvitationsCalls = mutableListOf<BusinessId>()
    val listBusinessInvitationsUidCalls = mutableListOf<String>()
    val createBusinessUidCalls = mutableListOf<String>()
    val createBusinessCalls = mutableListOf<String>()
    val inviteMemberUidCalls = mutableListOf<String>()
    val inviteMemberCalls = mutableListOf<InviteMemberCall>()
    val acceptInvitationUidCalls = mutableListOf<String>()
    val acceptInvitationCalls = mutableListOf<BusinessId>()
    val declineInvitationUidCalls = mutableListOf<String>()
    val declineInvitationCalls = mutableListOf<BusinessId>()
    val changeMemberRoleUidCalls = mutableListOf<String>()
    val changeMemberRoleCalls = mutableListOf<ChangeMemberRoleCall>()
    val removeMemberUidCalls = mutableListOf<String>()
    val removeMemberCalls = mutableListOf<Pair<BusinessId, String>>()
    val setActiveCloudBusinessCalls = mutableListOf<CloudBusinessLink?>()
    val setActiveCloudBusinessUidCalls = mutableListOf<String>()
    val setActiveCloudBusinessEpochCalls = mutableListOf<Long>()
    val compareAndSetActiveCloudBusinessEpochCalls = mutableListOf<Long>()
    val compareAndSetActiveCloudBusinessCalls =
        mutableListOf<CompareAndSetActiveCloudBusinessCall>()

    private val activeLinkFlow = MutableStateFlow<CloudBusinessLink?>(null)
    private var activeLinkOwnerUid: String? = null
    private var activeLinkRevision: Long? = null
    private var linkRevisionCounter: Long = 0L

    fun seedActiveLink(ownerUid: String, link: CloudBusinessLink?, revision: Long = 0L) {
        require(revision >= 0L)
        activeLinkOwnerUid = if (link == null) null else ownerUid
        activeLinkRevision = if (link == null) null else revision
        linkRevisionCounter = maxOf(linkRevisionCounter, revision)
        activeLinkFlow.value = link
    }

    override fun currentLocalBusinessIdentityEpoch(): Long = localBusinessIdentityEpoch

    data class InviteMemberCall(
        val businessId: BusinessId,
        val email: String,
        val role: BusinessRole,
    )

    data class ChangeMemberRoleCall(
        val businessId: BusinessId,
        val memberUid: String,
        val role: BusinessRole,
    )

    data class CompareAndSetActiveCloudBusinessCall(
        val expectedUid: String,
        val expectedLink: CloudBusinessLink?,
        val newLink: CloudBusinessLink?,
    )

    override suspend fun listMyMemberships(
        expectedUid: String,
    ): DomainResult<List<CloudMembership>> {
        listMyMembershipsUidCalls += expectedUid
        listMyMembershipsCalls++
        listMyMembershipsHandler?.let { return it() }
        return consume(nextListMyMembershipsResult) { nextListMyMembershipsResult = null }
            ?: DomainResult.Success(memberships)
    }

    override suspend fun createBusiness(
        expectedUid: String,
        displayName: String,
    ): DomainResult<CloudMembership> {
        createBusinessUidCalls += expectedUid
        createBusinessCalls += displayName
        val result = consume(nextCreateBusinessResult) { nextCreateBusinessResult = null }
            ?: DomainResult.Success(
                CloudMembership(
                    // Id determinista: el mismo nombre siempre crea el mismo negocio fake.
                    businessId = BusinessId.from(UUID.nameUUIDFromBytes(displayName.toByteArray())),
                    businessDisplayName = displayName,
                    role = BusinessRole.OWNER,
                ),
            )
        if (result is DomainResult.Success) memberships = memberships + result.value
        return result
    }

    override suspend fun listMembers(
        expectedUid: String,
        businessId: BusinessId,
    ): DomainResult<List<CloudMember>> {
        listMembersUidCalls += expectedUid
        listMembersCalls += businessId
        listMembersHandler?.let { return it(businessId) }
        return consume(nextListMembersResult) { nextListMembersResult = null }
            ?: DomainResult.Success(members)
    }

    override suspend fun listBusinessInvitations(
        expectedUid: String,
        businessId: BusinessId,
    ): DomainResult<List<BusinessInvitation>> {
        listBusinessInvitationsUidCalls += expectedUid
        listBusinessInvitationsCalls += businessId
        listBusinessInvitationsHandler?.let { return it(businessId) }
        return consume(nextListBusinessInvitationsResult) { nextListBusinessInvitationsResult = null }
            ?: DomainResult.Success(businessInvitations)
    }

    override suspend fun inviteMember(
        expectedUid: String,
        businessId: BusinessId,
        email: String,
        role: BusinessRole,
    ): DomainResult<Unit> {
        inviteMemberUidCalls += expectedUid
        inviteMemberCalls += InviteMemberCall(businessId, email, role)
        return consume(nextInviteMemberResult) { nextInviteMemberResult = null }
            ?: DomainResult.Success(Unit)
    }

    override suspend fun listMyInvitations(
        expectedUid: String,
    ): DomainResult<List<BusinessInvitation>> {
        listMyInvitationsUidCalls += expectedUid
        listMyInvitationsCalls++
        return consume(nextListMyInvitationsResult) { nextListMyInvitationsResult = null }
            ?: DomainResult.Success(myInvitations)
    }

    override suspend fun acceptInvitation(
        expectedUid: String,
        businessId: BusinessId,
    ): DomainResult<CloudMembership> {
        acceptInvitationUidCalls += expectedUid
        acceptInvitationCalls += businessId
        val result = consume(nextAcceptInvitationResult) { nextAcceptInvitationResult = null }
            ?: DomainResult.Success(
                acceptedMembership ?: CloudMembership(
                    businessId = businessId,
                    businessDisplayName = null,
                    role = BusinessRole.OPERATOR,
                ),
            )
        if (result is DomainResult.Success) {
            myInvitations = myInvitations.filterNot { it.businessId == businessId }
            memberships = memberships + result.value
        }
        return result
    }

    override suspend fun declineInvitation(
        expectedUid: String,
        businessId: BusinessId,
    ): DomainResult<Unit> {
        declineInvitationUidCalls += expectedUid
        declineInvitationCalls += businessId
        val result = consume(nextDeclineInvitationResult) { nextDeclineInvitationResult = null }
            ?: DomainResult.Success(Unit)
        if (result is DomainResult.Success) {
            myInvitations = myInvitations.filterNot { it.businessId == businessId }
        }
        return result
    }

    override suspend fun changeMemberRole(
        expectedUid: String,
        businessId: BusinessId,
        memberUid: String,
        role: BusinessRole,
    ): DomainResult<Unit> {
        changeMemberRoleUidCalls += expectedUid
        changeMemberRoleCalls += ChangeMemberRoleCall(businessId, memberUid, role)
        val result = consume(nextChangeMemberRoleResult) { nextChangeMemberRoleResult = null }
            ?: DomainResult.Success(Unit)
        if (result is DomainResult.Success) {
            members = members.map { if (it.uid == memberUid) it.copy(role = role) else it }
        }
        return result
    }

    override suspend fun removeMember(
        expectedUid: String,
        businessId: BusinessId,
        memberUid: String,
    ): DomainResult<Unit> {
        removeMemberUidCalls += expectedUid
        removeMemberCalls += businessId to memberUid
        val result = consume(nextRemoveMemberResult) { nextRemoveMemberResult = null }
            ?: DomainResult.Success(Unit)
        if (result is DomainResult.Success) {
            members = members.filterNot { it.uid == memberUid }
        }
        return result
    }

    override suspend fun setActiveCloudBusiness(
        expectedUid: String,
        expectedLocalIdentityEpoch: Long,
        link: CloudBusinessLink,
    ): DomainResult<CloudBusinessLinkReceipt> {
        setActiveCloudBusinessUidCalls += expectedUid
        setActiveCloudBusinessEpochCalls += expectedLocalIdentityEpoch
        setActiveCloudBusinessCalls += link
        val programmed = setActiveCloudBusinessHandler?.invoke(link)
            ?: consume(nextSetActiveCloudBusinessResult) {
                nextSetActiveCloudBusinessResult = null
            }
            ?: DomainResult.Success(Unit)
        if (programmed is DomainResult.Failure) return programmed
        linkRevisionCounter += 1L
        val receipt = CloudBusinessLinkReceipt(link, linkRevisionCounter)
        activeLinkOwnerUid = expectedUid
        activeLinkRevision = receipt.revision
        activeLinkFlow.value = link
        return DomainResult.Success(receipt)
    }

    override suspend fun compareAndSetActiveCloudBusiness(
        expectedUid: String,
        expectedLocalIdentityEpoch: Long,
        expectedReceipt: CloudBusinessLinkReceipt,
        newLink: CloudBusinessLink?,
    ): DomainResult<CloudBusinessLinkCasResult> {
        compareAndSetActiveCloudBusinessEpochCalls += expectedLocalIdentityEpoch
        compareAndSetActiveCloudBusinessCalls += CompareAndSetActiveCloudBusinessCall(
            expectedUid = expectedUid,
            expectedLink = expectedReceipt.link,
            newLink = newLink,
        )
        val scripted = compareAndSetActiveCloudBusinessHandler?.invoke(
            expectedUid,
            expectedReceipt.link,
            newLink,
        )
        if (scripted is DomainResult.Failure) return scripted
        if (scripted is DomainResult.Success && !scripted.value) {
            return DomainResult.Success(CloudBusinessLinkCasResult(false, null))
        }
        val ownerMatches = activeLinkOwnerUid == null || activeLinkOwnerUid == expectedUid
        if (
            scripted == null && (
                !ownerMatches || activeLinkFlow.value != expectedReceipt.link ||
                    activeLinkRevision != expectedReceipt.revision
                )
        ) {
            return DomainResult.Success(CloudBusinessLinkCasResult(false, null))
        }
        val receipt = if (newLink == null) {
            activeLinkOwnerUid = null
            activeLinkRevision = null
            null
        } else {
            linkRevisionCounter += 1L
            CloudBusinessLinkReceipt(newLink, linkRevisionCounter).also {
                activeLinkOwnerUid = expectedUid
                activeLinkRevision = it.revision
            }
        }
        activeLinkFlow.value = newLink
        return DomainResult.Success(CloudBusinessLinkCasResult(true, receipt))
    }

    override suspend fun clearActiveCloudBusinessIfOwned(
        expectedUid: String,
        expectedReceipt: CloudBusinessLinkReceipt,
    ): DomainResult<Boolean> = when (
        val result = compareAndSetActiveCloudBusiness(
            expectedUid = expectedUid,
            expectedLocalIdentityEpoch = currentLocalBusinessIdentityEpoch(),
            expectedReceipt = expectedReceipt,
            newLink = null,
        )
    ) {
        is DomainResult.Success -> DomainResult.Success(result.value.applied)
        is DomainResult.Failure -> result
    }

    override fun observeActiveLink(): Flow<CloudBusinessLink?> = activeLinkFlow

    /** Consume el resultado programado y lo limpia; null si no había guion. */
    private fun <T> consume(programmed: DomainResult<T>?, clear: () -> Unit): DomainResult<T>? {
        val result = programmed
        clear()
        return result
    }
}
