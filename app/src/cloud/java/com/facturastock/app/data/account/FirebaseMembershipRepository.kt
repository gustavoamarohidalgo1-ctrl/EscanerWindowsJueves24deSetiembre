package com.facturastock.app.data.account

import com.facturastock.app.data.sync.FirebaseBackupRuntime
import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.AccountException
import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.model.AccountSession
import com.facturastock.app.domain.model.BusinessInvitation
import com.facturastock.app.domain.model.BusinessRole
import com.facturastock.app.domain.model.CloudBusinessLink
import com.facturastock.app.domain.model.CloudBusinessLinkCasResult
import com.facturastock.app.domain.model.CloudBusinessLinkReceipt
import com.facturastock.app.domain.model.CloudMember
import com.facturastock.app.domain.model.CloudMembership
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.AccountRepository
import com.facturastock.app.domain.repository.BusinessMembershipRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

/**
 * `BusinessMembershipRepository` del flavor cloud: proyecta los callables de Functions sobre
 * los puertos del dominio. Toda operación remota exige sesión [AccountSession.Active] (correo
 * verificado); la autorización real la hace siempre el servidor. Los payloads y errores se
 * traducen con [AccountMappers] y [AccountErrorMapper]: nada crudo del backend sale de aquí.
 */
@Singleton
class FirebaseMembershipRepository @Inject constructor(
    private val runtime: FirebaseBackupRuntime,
    private val accountRepository: AccountRepository,
    private val linkBinder: CloudBusinessLinkBinder,
) : BusinessMembershipRepository {

    override fun currentLocalBusinessIdentityEpoch(): Long =
        linkBinder.currentLocalBusinessIdentityEpoch()

    override suspend fun listMyMemberships(
        expectedUid: String,
    ): DomainResult<List<CloudMembership>> =
        callAs(expectedUid, "listMyMemberships", emptyMap()) { data ->
            AccountMappers.membershipList(data)
        }

    override suspend fun createBusiness(
        expectedUid: String,
        displayName: String,
    ): DomainResult<CloudMembership> {
        // El cliente propone el UUID del negocio; el servidor lo valida y lo crea con la
        // cuenta actual como OWNER. El enlace con el negocio local lo fija la UI después.
        val businessId = BusinessId.from(UUID.randomUUID())
        return callAs(
            expectedUid,
            "createBusiness",
            mapOf("businessId" to businessId.value, "displayName" to displayName),
        ) { data -> AccountMappers.membership(data, displayNameFallback = displayName) }
    }

    override suspend fun listMembers(
        expectedUid: String,
        businessId: BusinessId,
    ): DomainResult<List<CloudMember>> =
        callAllPages(expectedUid, "listMembers", businessId, AccountMappers::memberPage)

    override suspend fun listBusinessInvitations(
        expectedUid: String,
        businessId: BusinessId,
    ): DomainResult<List<BusinessInvitation>> =
        callAllPages(expectedUid, "listBusinessInvitations", businessId) { data ->
            AccountMappers.invitationPage(data, businessId = businessId)
        }

    override suspend fun inviteMember(
        expectedUid: String,
        businessId: BusinessId,
        email: String,
        role: BusinessRole,
    ): DomainResult<Unit> = callAs(
        expectedUid,
        "inviteMember",
        mapOf("businessId" to businessId.value, "email" to email, "role" to role.name),
    ) { }

    override suspend fun listMyInvitations(
        expectedUid: String,
    ): DomainResult<List<BusinessInvitation>> {
        // El payload no repite el correo: las invitaciones van dirigidas a la cuenta actual.
        val session = activeSession() ?: return DomainResult.Failure(AccountError.NotAuthenticated)
        if (session.uid != expectedUid) {
            return DomainResult.Failure(AccountError.SessionExpired)
        }
        return callAs(expectedUid, "listMyInvitations", emptyMap()) { data ->
            AccountMappers.invitationList(data, email = session.email)
        }
    }

    override suspend fun acceptInvitation(
        expectedUid: String,
        businessId: BusinessId,
    ): DomainResult<CloudMembership> =
        callAs(expectedUid, "acceptInvitation", mapOf("businessId" to businessId.value)) { data ->
            AccountMappers.membership(data)
        }

    override suspend fun declineInvitation(
        expectedUid: String,
        businessId: BusinessId,
    ): DomainResult<Unit> =
        callAs(expectedUid, "declineInvitation", mapOf("businessId" to businessId.value)) { }

    override suspend fun changeMemberRole(
        expectedUid: String,
        businessId: BusinessId,
        memberUid: String,
        role: BusinessRole,
    ): DomainResult<Unit> = callAs(
        expectedUid,
        "changeMemberRole",
        mapOf("businessId" to businessId.value, "uid" to memberUid, "role" to role.name),
    ) { }

    override suspend fun removeMember(
        expectedUid: String,
        businessId: BusinessId,
        memberUid: String,
    ): DomainResult<Unit> = callAs(
        expectedUid,
        "removeMember",
        mapOf("businessId" to businessId.value, "uid" to memberUid),
    ) { }

    override suspend fun setActiveCloudBusiness(
        expectedUid: String,
        expectedLocalIdentityEpoch: Long,
        link: CloudBusinessLink,
    ): DomainResult<CloudBusinessLinkReceipt> =
        try {
            linkBinder.setActive(expectedUid, expectedLocalIdentityEpoch, link) {
                activeSession()?.uid == expectedUid
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            DomainResult.Failure(AccountErrorMapper.fromException(failure))
        }

    override suspend fun compareAndSetActiveCloudBusiness(
        expectedUid: String,
        expectedLocalIdentityEpoch: Long,
        expectedReceipt: CloudBusinessLinkReceipt,
        newLink: CloudBusinessLink?,
    ): DomainResult<CloudBusinessLinkCasResult> = try {
        linkBinder.compareAndSet(
            expectedUid,
            expectedLocalIdentityEpoch,
            expectedReceipt,
            newLink,
        ) {
            activeSession()?.uid == expectedUid
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        DomainResult.Failure(AccountErrorMapper.fromException(failure))
    }

    override suspend fun clearActiveCloudBusinessIfOwned(
        expectedUid: String,
        expectedReceipt: CloudBusinessLinkReceipt,
    ): DomainResult<Boolean> = try {
        // Compensación de una operación ya linearizada para A: debe poder ejecutarse tras B,
        // pero el CAS propietario impide tocar cualquier escritura de B.
        when (
            val result = linkBinder.compareAndSet(
                expectedUid = expectedUid,
                expectedLocalIdentityEpoch = currentLocalBusinessIdentityEpoch(),
                expectedReceipt = expectedReceipt,
                newLink = null,
            )
        ) {
            is DomainResult.Success -> DomainResult.Success(result.value.applied)
            is DomainResult.Failure -> result
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        DomainResult.Failure(AccountErrorMapper.fromException(failure))
    }

    override fun observeActiveLink(): Flow<CloudBusinessLink?> =
        accountRepository.observeSession().map { session ->
            (session as? AccountSession.Active)?.link
        }

    private suspend fun activeSession(): AccountSession.Active? =
        accountRepository.observeSession().first() as? AccountSession.Active

    private suspend fun <T> callAllPages(
        expectedUid: String,
        name: String,
        businessId: BusinessId,
        decode: (Map<*, *>) -> AccountListPage<T>,
    ): DomainResult<List<T>> = drainAccountPages { cursor ->
        val payload = mutableMapOf<String, Any?>(
            "businessId" to businessId.value,
            "limit" to ACCOUNT_LIST_PAGE_SIZE,
        )
        cursor?.let { payload["cursor"] = it }
        callAs(expectedUid = expectedUid, name = name, payload = payload, decode = decode)
    }

    private suspend fun <T> call(
        name: String,
        payload: Map<String, Any?>,
        expectedUid: String,
        decode: (Map<*, *>) -> T,
    ): DomainResult<T> {
        val functions = runtime.functions()
            ?: return DomainResult.Failure(AccountError.Unavailable)
        val session = activeSession()
            ?: return DomainResult.Failure(AccountError.NotAuthenticated)
        if (session.uid != expectedUid) {
            return DomainResult.Failure(AccountError.SessionExpired)
        }
        return try {
            val result = functions.getHttpsCallable(name).call(payload).await()
            val data = result.data as? Map<*, *>
                ?: throw AccountException(AccountError.Unexpected)
            DomainResult.Success(decode(data))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AccountException) {
            DomainResult.Failure(failure.error)
        } catch (failure: Exception) {
            DomainResult.Failure(AccountErrorMapper.fromException(failure))
        }
    }

    /**
     * Liga una operación a la cuenta que originó la acción. La comprobación local evita trabajo
     * inútil si la sesión ya cambió y el payload permite que Functions cierre la carrera entre
     * esta comprobación y el envío efectivo del callable.
     */
    private suspend fun <T> callAs(
        expectedUid: String,
        name: String,
        payload: Map<String, Any?>,
        decode: (Map<*, *>) -> T,
    ): DomainResult<T> = call(
        name = name,
        payload = payload + ("expectedUid" to expectedUid),
        expectedUid = expectedUid,
        decode = decode,
    )

    private companion object {
        /** Debe coincidir con el máximo público de los callables de membresía. */
        const val ACCOUNT_LIST_PAGE_SIZE = 100
    }
}
