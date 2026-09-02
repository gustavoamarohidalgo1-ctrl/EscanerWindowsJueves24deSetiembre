package com.facturastock.app.domain.model

import com.facturastock.app.domain.model.id.BusinessId

/**
 * Rol de cuenta en un negocio de la nube. Es la fuente de verdad del lado cliente; el
 * servidor (Cloud Function) vuelve a comprobarlo siempre contra la membresía almacenada.
 * Distinto de [PurchaseOverrideRole], que es una autorización operativa local.
 */
enum class BusinessRole {
    OWNER,
    ADMIN,
    OPERATOR,
    READER,
    ;

    /** Respaldar compras o anulaciones exige al menos OPERATOR. */
    val canSyncPurchases: Boolean
        get() = this != READER

    /** Anular en la nube exige OWNER o ADMIN. */
    val canVoidSyncedPurchases: Boolean
        get() = this == OWNER || this == ADMIN

    /** Invitar, cambiar roles y eliminar miembros exige OWNER o ADMIN. */
    val canManageMembers: Boolean
        get() = this == OWNER || this == ADMIN

    companion object {
        fun parse(input: String?): BusinessRole? =
            input?.trim()?.uppercase()?.let { code -> entries.firstOrNull { it.name == code } }
    }
}

/** Miembro de un negocio en la nube, tal como lo devuelve el servidor. */
data class CloudMember(
    val uid: String,
    val email: String?,
    val role: BusinessRole,
) {
    init {
        require(uid.isNotBlank()) { "uid requerido" }
    }
}

/** Membresía propia: un negocio de la nube al que la cuenta actual pertenece. */
data class CloudMembership(
    val businessId: BusinessId,
    val businessDisplayName: String?,
    val role: BusinessRole,
)

/** Invitación pendiente (o gestionada) a un negocio de la nube. */
data class BusinessInvitation(
    val businessId: BusinessId,
    val businessDisplayName: String?,
    val email: String,
    val role: BusinessRole,
    val expiresAtEpochMilli: Long,
) {
    init {
        require(email.isNotBlank()) { "email requerido" }
        require(expiresAtEpochMilli > 0) { "expiresAtEpochMilli debe ser positivo" }
    }
}

/**
 * Enlace entre el negocio local (Room) y el negocio de la nube al que se respalda. El
 * `localBusinessId` queda fijado al enlazar: los envelopes de otra empresa local nunca se
 * redirigen silenciosamente a otro negocio de la nube. [role] es el último rol confirmado por
 * el servidor: se usa offline y se actualiza o revoca en el siguiente refresco exitoso de
 * membresías.
 */
data class CloudBusinessLink(
    val localBusinessId: BusinessId,
    val cloudBusinessId: BusinessId,
    val role: BusinessRole,
)

/**
 * Recibo de una publicación durable del enlace. [revision] distingue escrituras idénticas de la
 * misma cuenta y permite que una compensación tardía solo retire la versión que creó.
 */
data class CloudBusinessLinkReceipt(
    val link: CloudBusinessLink,
    val revision: Long,
) {
    init {
        require(revision >= 0L) { "revision debe ser no negativa" }
    }
}

/** Resultado de un CAS durable; una limpieza aplicada tiene [receipt] nulo. */
data class CloudBusinessLinkCasResult(
    val applied: Boolean,
    val receipt: CloudBusinessLinkReceipt?,
) {
    init {
        require(applied || receipt == null) { "un CAS no aplicado no puede emitir recibo" }
    }
}

/** Resolución del negocio de la nube para un envelope de respaldo. */
sealed interface CloudBusinessResolution {
    /** El enlace corresponde a la empresa local del envelope: respaldar aquí. */
    data class Linked(val cloudBusinessId: BusinessId) : CloudBusinessResolution

    /** No hay enlace configurado: la sincronización no está activada. */
    data object NotLinked : CloudBusinessResolution

    /** El enlace pertenece a otra empresa local: fallo visible, nunca reencaminar. */
    data object Mismatch : CloudBusinessResolution
}

/** Regla pura de enlace local ↔ nube aplicada por el transporte en cada envío. */
fun resolveCloudBusiness(
    link: CloudBusinessLink?,
    localBusinessId: BusinessId,
): CloudBusinessResolution = when {
    link == null -> CloudBusinessResolution.NotLinked
    link.localBusinessId == localBusinessId -> CloudBusinessResolution.Linked(link.cloudBusinessId)
    else -> CloudBusinessResolution.Mismatch
}

/**
 * Sesión de cuenta para el respaldo en la nube. La UI solo conoce estos estados; los tokens
 * jamás salen de la capa de datos.
 */
sealed interface AccountSession {
    /** Flavor local o Firebase sin configurar: la nube no existe para esta instalación. */
    data object Unavailable : AccountSession

    /** No hay cuenta autenticada. */
    data object SignedOut : AccountSession

    /** Cuenta creada o autenticada pero sin correo verificado: la sincronización no se activa. */
    data class AwaitingVerification(
        val uid: String,
        val email: String,
    ) : AccountSession {
        init {
            require(uid.isNotBlank()) { "uid requerido" }
            require(email.isNotBlank()) { "email requerido" }
        }
    }

    /** Sesión plena con correo verificado. */
    data class Active(
        val uid: String,
        val email: String,
        val link: CloudBusinessLink?,
        val linkRevision: Long = 0L,
    ) : AccountSession {
        init {
            require(uid.isNotBlank()) { "uid requerido" }
            require(email.isNotBlank()) { "email requerido" }
            require(linkRevision >= 0L) { "linkRevision debe ser no negativa" }
        }
    }

    /** El token dejó de ser válido (revocado o expirado): hay que reingresar. */
    data class Expired(val email: String?) : AccountSession
}
