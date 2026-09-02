package com.facturastock.app.domain.repository

import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.model.BusinessInvitation
import com.facturastock.app.domain.model.BusinessRole
import com.facturastock.app.domain.model.CloudBusinessLink
import com.facturastock.app.domain.model.CloudBusinessLinkCasResult
import com.facturastock.app.domain.model.CloudBusinessLinkReceipt
import com.facturastock.app.domain.model.CloudMember
import com.facturastock.app.domain.model.CloudMembership
import com.facturastock.app.domain.model.id.BusinessId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Puerto de negocios y membresías en la nube. Toda la autorización real la hace el servidor
 * (la Function deriva el UID del token, exige que coincida con `expectedUid` y comprueba
 * membresía y rol); estas operaciones solo son su proyección remota. Ninguna acepta un
 * `businessId` sin validación del lado servidor.
 */
interface BusinessMembershipRepository {
    /** Generación process-local de la identidad de negocio; su captura es síncrona. */
    fun currentLocalBusinessIdentityEpoch(): Long

    /** Negocios a los que pertenece [expectedUid]. */
    suspend fun listMyMemberships(expectedUid: String): DomainResult<List<CloudMembership>>

    /** Crea un negocio en la nube; [expectedUid] queda como OWNER. */
    suspend fun createBusiness(
        expectedUid: String,
        displayName: String,
    ): DomainResult<CloudMembership>

    /** Miembros completos de un negocio propio; el adaptador cloud drena su paginación. */
    suspend fun listMembers(
        expectedUid: String,
        businessId: BusinessId,
    ): DomainResult<List<CloudMember>>

    /** Invitaciones PENDING completas del negocio; exige OWNER o ADMIN y drena su paginación. */
    suspend fun listBusinessInvitations(
        expectedUid: String,
        businessId: BusinessId,
    ): DomainResult<List<BusinessInvitation>>

    /** Invita por email con un rol; exige OWNER o ADMIN. */
    suspend fun inviteMember(
        expectedUid: String,
        businessId: BusinessId,
        email: String,
        role: BusinessRole,
    ): DomainResult<Unit>

    /** Invitaciones pendientes dirigidas al email de [expectedUid]. */
    suspend fun listMyInvitations(expectedUid: String): DomainResult<List<BusinessInvitation>>

    /** Acepta una invitación: crea la membresía con el rol invitado. */
    suspend fun acceptInvitation(
        expectedUid: String,
        businessId: BusinessId,
    ): DomainResult<CloudMembership>

    suspend fun declineInvitation(
        expectedUid: String,
        businessId: BusinessId,
    ): DomainResult<Unit>

    /** Cambia el rol de un miembro; exige OWNER o ADMIN. OPERATOR y READER no pueden. */
    suspend fun changeMemberRole(
        expectedUid: String,
        businessId: BusinessId,
        memberUid: String,
        role: BusinessRole,
    ): DomainResult<Unit>

    /** Elimina a un miembro (o a uno mismo para abandonar); protege al último OWNER. */
    suspend fun removeMember(
        expectedUid: String,
        businessId: BusinessId,
        memberUid: String,
    ): DomainResult<Unit>

    /**
     * Fija el negocio de la nube al que respalda esta instalación únicamente para la cuenta
     * [expectedUid]. El UID queda asociado al enlace durable para que una respuesta tardía de
     * otra sesión nunca se proyecte sobre la cuenta actual. Devuelve el recibo durable exacto
     * que debe usarse si luego se necesita compensar esta escritura.
     */
    suspend fun setActiveCloudBusiness(
        expectedUid: String,
        expectedLocalIdentityEpoch: Long,
        link: CloudBusinessLink,
    ): DomainResult<CloudBusinessLinkReceipt>

    /**
     * Sustituye el enlace solo si [expectedReceipt] conserva propietario, valor y revisión.
     * [expectedLocalIdentityEpoch] invalida también ABA del negocio local en escrituras no nulas.
     */
    suspend fun compareAndSetActiveCloudBusiness(
        expectedUid: String,
        expectedLocalIdentityEpoch: Long,
        expectedReceipt: CloudBusinessLinkReceipt,
        newLink: CloudBusinessLink?,
    ): DomainResult<CloudBusinessLinkCasResult>

    /** Limpieza compensatoria: borra el enlace exacto de un UID obsoleto, aunque ya haya B. */
    suspend fun clearActiveCloudBusinessIfOwned(
        expectedUid: String,
        expectedReceipt: CloudBusinessLinkReceipt,
    ): DomainResult<Boolean>

    /** Enlace vigente local ↔ nube; lo consulta el transporte en cada envío. */
    fun observeActiveLink(): Flow<CloudBusinessLink?>
}

/** Doble neutro compartido por pruebas de componentes que solo necesitan una operación. */
object DisabledBusinessMembershipRepository : BusinessMembershipRepository {
    override fun currentLocalBusinessIdentityEpoch(): Long = 0L
    override suspend fun listMyMemberships(expectedUid: String) = unavailable<List<CloudMembership>>()
    override suspend fun createBusiness(expectedUid: String, displayName: String) =
        unavailable<CloudMembership>()
    override suspend fun listMembers(expectedUid: String, businessId: BusinessId) =
        unavailable<List<CloudMember>>()
    override suspend fun listBusinessInvitations(expectedUid: String, businessId: BusinessId) =
        unavailable<List<BusinessInvitation>>()
    override suspend fun inviteMember(
        expectedUid: String,
        businessId: BusinessId,
        email: String,
        role: BusinessRole,
    ) = unavailable<Unit>()
    override suspend fun listMyInvitations(expectedUid: String) =
        unavailable<List<BusinessInvitation>>()
    override suspend fun acceptInvitation(expectedUid: String, businessId: BusinessId) =
        unavailable<CloudMembership>()
    override suspend fun declineInvitation(expectedUid: String, businessId: BusinessId) =
        unavailable<Unit>()
    override suspend fun changeMemberRole(
        expectedUid: String,
        businessId: BusinessId,
        memberUid: String,
        role: BusinessRole,
    ) = unavailable<Unit>()
    override suspend fun removeMember(
        expectedUid: String,
        businessId: BusinessId,
        memberUid: String,
    ) = unavailable<Unit>()
    override suspend fun setActiveCloudBusiness(
        expectedUid: String,
        expectedLocalIdentityEpoch: Long,
        link: CloudBusinessLink,
    ) = unavailable<CloudBusinessLinkReceipt>()
    override suspend fun compareAndSetActiveCloudBusiness(
        expectedUid: String,
        expectedLocalIdentityEpoch: Long,
        expectedReceipt: CloudBusinessLinkReceipt,
        newLink: CloudBusinessLink?,
    ) = unavailable<CloudBusinessLinkCasResult>()
    override suspend fun clearActiveCloudBusinessIfOwned(
        expectedUid: String,
        expectedReceipt: CloudBusinessLinkReceipt,
    ) = unavailable<Boolean>()
    override fun observeActiveLink(): Flow<CloudBusinessLink?> = flowOf(null)

    private fun <T> unavailable(): DomainResult<T> = DomainResult.Failure(AccountError.Unavailable)
}
