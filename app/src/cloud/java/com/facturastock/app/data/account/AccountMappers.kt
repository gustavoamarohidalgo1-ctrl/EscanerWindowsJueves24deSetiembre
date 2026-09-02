package com.facturastock.app.data.account

import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.AccountException
import com.facturastock.app.domain.model.AccountDeletionSummary
import com.facturastock.app.domain.model.BusinessInvitation
import com.facturastock.app.domain.model.BusinessRole
import com.facturastock.app.domain.model.CloudMember
import com.facturastock.app.domain.model.CloudMembership
import com.facturastock.app.domain.model.id.BusinessId

/**
 * Traduce los payloads (`Map<*, *>` del JSON del callable) a modelos del dominio. Cualquier
 * forma inesperada — campo ausente, tipo distinto, UUID no canónico, rol desconocido — se
 * convierte en [AccountError.Unexpected]; el contenido del payload nunca aparece en el error.
 *
 * Objeto puro: no toca Firebase ni Android y se prueba en JVM.
 */
object AccountMappers {

    /** Respuesta de `listMyMemberships`: `{memberships: [...]}`. */
    fun membershipList(data: Map<*, *>): List<CloudMembership> =
        mapList(data, MEMBERSHIPS_KEY) { entry -> membership(entry) }

    /**
     * Membresía suelta (`createBusiness`, `acceptInvitation` devuelven `{businessId, role}`).
     * `displayNameFallback` cubre el nombre visible cuando el payload no lo repite.
     */
    fun membership(data: Map<*, *>, displayNameFallback: String? = null): CloudMembership =
        CloudMembership(
            businessId = businessId(data[BUSINESS_ID]),
            businessDisplayName = (data[DISPLAY_NAME] as? String) ?: displayNameFallback,
            role = role(data[ROLE]),
        )

    /** Respuesta histórica de `listMembers`: `{members: [...]}`. */
    fun memberList(data: Map<*, *>): List<CloudMember> =
        mapList(data, MEMBERS_KEY) { entry -> member(entry) }

    /**
     * Página de `listMembers`. El cursor es opaco para Android; solo se comprueba la forma
     * cerrada del contrato y [drainAccountPages] se ocupa de detectar repeticiones/ciclos.
     */
    internal fun memberPage(data: Map<*, *>): AccountListPage<CloudMember> {
        val page = accountListPage(data, MEMBERS_KEY) { entry -> member(entry) }
        if (page.hasMore && page.values.lastOrNull()?.uid != page.nextCursor) malformed()
        return page
    }

    fun member(data: Map<*, *>): CloudMember = CloudMember(
        uid = (data[UID] as? String)?.takeIf { it.isNotBlank() } ?: malformed(),
        email = (data[EMAIL] as? String)?.takeIf { it.isNotBlank() },
        role = role(data[ROLE]),
    )

    /**
     * Respuesta de `listBusinessInvitations` o `listMyInvitations`. El servidor omite el
     * `businessId` en la primera (es el del parámetro) y el `email` en la segunda (es el de la
     * cuenta actual); los fallbacks completan esos campos.
     */
    fun invitationList(
        data: Map<*, *>,
        businessId: BusinessId? = null,
        email: String? = null,
    ): List<BusinessInvitation> =
        mapList(data, INVITATIONS_KEY) { entry -> invitation(entry, businessId, email) }

    /** Página de `listBusinessInvitations`, manteniendo exactamente el orden del servidor. */
    internal fun invitationPage(
        data: Map<*, *>,
        businessId: BusinessId,
    ): AccountListPage<BusinessInvitation> =
        accountListPage(data, INVITATIONS_KEY) { entry -> invitation(entry, businessId) }

    fun invitation(
        data: Map<*, *>,
        businessId: BusinessId? = null,
        email: String? = null,
    ): BusinessInvitation = BusinessInvitation(
        businessId = (data[BUSINESS_ID] as? String)?.let(BusinessId::parse)
            ?: businessId
            ?: malformed(),
        businessDisplayName = data[DISPLAY_NAME] as? String,
        email = (data[EMAIL] as? String)?.takeIf { it.isNotBlank() }
            ?: email?.takeIf { it.isNotBlank() }
            ?: malformed(),
        role = role(data[ROLE]),
        expiresAtEpochMilli = (data[EXPIRES_AT_MILLIS] as? Number)?.toLong()?.takeIf { it > 0 }
            ?: malformed(),
    )

    private fun businessId(raw: Any?): BusinessId =
        (raw as? String)?.let(BusinessId::parse) ?: malformed()

    /**
     * Respuesta de `deleteMyAccount`: `{businessesDeleted: [...], membershipsRemoved: N}`.
     * Ids como UUID canónico y contador entero no negativo; cualquier desviación es
     * [AccountError.Unexpected].
     */
    fun accountDeletionSummary(data: Map<*, *>): AccountDeletionSummary {
        val deleted = data[BUSINESSES_DELETED_KEY] as? List<*> ?: malformed()
        return AccountDeletionSummary(
            businessesDeleted = deleted.map { entry -> businessId(entry).value },
            membershipsRemoved = nonNegativeInt(data[MEMBERSHIPS_REMOVED_KEY]),
        )
    }

    /** Entero JSON estricto y no negativo: una fracción o un negativo es payload malformado. */
    private fun nonNegativeInt(raw: Any?): Int {
        val number = raw as? Number ?: malformed()
        val value = number.toLong()
        if (number.toDouble() != value.toDouble() || value < 0 || value > Int.MAX_VALUE) {
            malformed()
        }
        return value.toInt()
    }

    private fun role(raw: Any?): BusinessRole =
        BusinessRole.parse(raw as? String) ?: malformed()

    private fun <T> mapList(
        data: Map<*, *>,
        key: String,
        transform: (Map<*, *>) -> T,
    ): List<T> {
        val entries = data[key] as? List<*> ?: malformed()
        return entries.map { entry -> transform(entry as? Map<*, *> ?: malformed()) }
    }

    private fun <T> accountListPage(
        data: Map<*, *>,
        key: String,
        transform: (Map<*, *>) -> T,
    ): AccountListPage<T> {
        val entries = data[key] as? List<*> ?: malformed()
        if (entries.size > MAX_ACCOUNT_LIST_PAGE_SIZE) malformed()
        val values = entries.map { entry -> transform(entry as? Map<*, *> ?: malformed()) }
        val hasMore = data[HAS_MORE_KEY] as? Boolean ?: malformed()
        if (!data.containsKey(NEXT_CURSOR_KEY)) malformed()
        val rawCursor = data[NEXT_CURSOR_KEY]
        val nextCursor = when (rawCursor) {
            null -> null
            is String -> rawCursor.takeIf {
                it.isNotBlank() && it.length <= MAX_CURSOR_LENGTH && '/' !in it
            }
                ?: malformed()
            else -> malformed()
        }
        if (hasMore != (nextCursor != null)) malformed()
        return AccountListPage(values = values, nextCursor = nextCursor, hasMore = hasMore)
    }

    private fun malformed(): Nothing = throw AccountException(AccountError.Unexpected)

    private const val MEMBERSHIPS_KEY = "memberships"
    private const val MEMBERS_KEY = "members"
    private const val INVITATIONS_KEY = "invitations"
    private const val NEXT_CURSOR_KEY = "nextCursor"
    private const val HAS_MORE_KEY = "hasMore"
    private const val BUSINESSES_DELETED_KEY = "businessesDeleted"
    private const val MEMBERSHIPS_REMOVED_KEY = "membershipsRemoved"
    private const val BUSINESS_ID = "businessId"
    private const val DISPLAY_NAME = "displayName"
    private const val ROLE = "role"
    private const val UID = "uid"
    private const val EMAIL = "email"
    private const val EXPIRES_AT_MILLIS = "expiresAtMillis"
    private const val MAX_ACCOUNT_LIST_PAGE_SIZE = 100
    private const val MAX_CURSOR_LENGTH = 128
}
