package com.facturastock.app.domain.repository

import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.model.AccountDeletionSummary
import com.facturastock.app.domain.model.AccountSession
import kotlinx.coroutines.flow.Flow

/**
 * Puerto de la sesión de cuenta para el respaldo en la nube. El perfil local no depende de
 * él: sin sesión el flujo local sigue intacto y la cola de respaldo permanece PENDING_SYNC.
 *
 * Los tokens de autenticación jamás cruzan este puerto: viven y mueren en la capa de datos.
 */
interface AccountRepository {
    /** `false` en el flavor local o sin Firebase configurado: la nube no existe aquí. */
    val available: Boolean

    /** Capacidad real del backend activo; Spark no dispone del servicio de borrado remoto. */
    val accountDeletionAvailable: Boolean
        get() = available

    /** Estado actual de la sesión; emite en cada cambio de autenticación o de enlace. */
    fun observeSession(): Flow<AccountSession>

    /** UID cuyo callable de borrado pudo empezar y aún requiere cerrar/limpiar la sesión local. */
    fun observePendingAccountDeletionUid(): Flow<String?>

    /** Lectura puntual del mismo checkpoint durable; null si no existe o no es válido. */
    suspend fun pendingAccountDeletionUid(): String?

    /** Acceso con email y contraseña. Tras el éxito la sesión pasa a Active o AwaitingVerification. */
    suspend fun signIn(email: String, password: String): DomainResult<AccountSession>

    /** Alta con email y contraseña; envía el correo de verificación. */
    suspend fun register(email: String, password: String): DomainResult<AccountSession>

    suspend fun resendVerificationEmail(): DomainResult<Unit>

    /** Recarga el usuario remoto para comprobar si el correo ya fue verificado. */
    suspend fun refreshVerification(): DomainResult<AccountSession>

    suspend fun sendPasswordReset(email: String): DomainResult<Unit>

    /**
     * Intenta renovar el token (sesión expirada o `UNAUTHENTICATED` del backend). Si la
     * renovación falla, la sesión pasa a [AccountSession.Expired] y hay que reingresar.
     */
    suspend fun recoverSession(): DomainResult<AccountSession>

    /**
     * Cierra la sesión: limpia tokens, cancela los trabajos de respaldo del usuario y
     * olvida el enlace de negocio. Nunca borra compras, ventas, deudas, abonos ni borradores locales.
     */
    suspend fun signOut(): DomainResult<Unit>

    /**
     * Limpia la sesión únicamente si todavía pertenece a [expectedUid]. Devuelve `false`
     * sin cerrar ni limpiar la sesión nueva cuando otra cuenta ya tomó el proceso; en ese caso
     * solo descarta el checkpoint del UID anterior. Una sesión ya ausente cuenta como limpieza
     * completada y devuelve `true`.
     */
    suspend fun signOutIfCurrent(expectedUid: String): DomainResult<Boolean>

    /**
     * Elimina la cuenta en la nube: borra los negocios donde el usuario es OWNER y único
     * miembro (árbol completo), sus membresías en negocios ajenos y todas las invitaciones
     * dirigidas a su correo verificado. En negocios que sobreviven, anonimiza las referencias
     * históricas al UID sin borrar compras comerciales. Después elimina el usuario de Auth. Si
     * es OWNER de un negocio con más miembros, el servidor rechaza sin borrar nada (hay que
     * transferir la propiedad primero).
     *
     * No cierra la sesión local: tras el éxito la UI persiste que el borrado remoto terminó y
     * ejecuta/reintenta el sign-out por separado, sin repetir este comando. Las compras locales
     * nunca se tocan.
     */
    suspend fun requestAccountDeletion(
        expectedUid: String,
        password: String,
    ): DomainResult<AccountDeletionSummary>
}
