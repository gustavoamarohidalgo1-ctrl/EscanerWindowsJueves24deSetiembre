package com.facturastock.app.data.account

import com.facturastock.app.domain.error.AccountError
import com.google.firebase.FirebaseException
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.functions.FirebaseFunctionsException
import java.io.IOException

/**
 * Traduce los fallos del SDK Firebase a los códigos cerrados de [AccountError]. El mensaje del
 * backend solo se consulta para reconocer los códigos cerrados conocidos (el servidor fija el
 * `message` del HttpsError a un código como `EMAIL_NOT_VERIFIED`); jamás se propaga al
 * resultado. Objeto puro, probado en JVM.
 */
object AccountErrorMapper {

    fun fromException(error: Throwable): AccountError = when (error) {
        is FirebaseAuthException -> fromAuthErrorCode(error.errorCode)
        is FirebaseFunctionsException -> fromFunctionsCode(error.code.name, error.message)
        is FirebaseNetworkException -> AccountError.NetworkUnavailable
        is IOException -> AccountError.NetworkUnavailable
        else -> AccountError.Unexpected
    }

    fun fromAuthErrorCode(errorCode: String): AccountError = when (errorCode) {
        ERROR_INVALID_EMAIL -> AccountError.InvalidEmail
        ERROR_USER_TOKEN_EXPIRED, ERROR_INVALID_USER_TOKEN -> AccountError.SessionExpired
        ERROR_WRONG_PASSWORD, ERROR_INVALID_CREDENTIAL,
        ERROR_USER_NOT_FOUND, ERROR_USER_DISABLED,
        -> AccountError.InvalidCredentials

        ERROR_EMAIL_ALREADY_IN_USE -> AccountError.EmailInUse
        ERROR_WEAK_PASSWORD -> AccountError.WeakPassword
        else -> AccountError.Unexpected
    }

    /**
     * Reconoce la invalidación definitiva de un usuario que ya estaba autenticado. Algunas
     * versiones del SDK entregan la excepción de Auth directamente y otras pueden envolverla;
     * el recorrido es corto, acotado y no consulta mensajes libres.
     */
    fun isExpiredSessionFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        repeat(MAX_CAUSE_DEPTH) {
            val authError = current as? FirebaseAuthException
            if (
                authError is FirebaseAuthInvalidUserException ||
                authError?.errorCode?.let(::isExpiredSessionAuthErrorCode) == true ||
                (current is FirebaseException &&
                    isExpiredSessionGenericMessage(current.message))
            ) {
                return true
            }
            val cause = current?.cause ?: return false
            if (cause === current) return false
            current = cause
        }
        return false
    }

    internal fun isExpiredSessionAuthErrorCode(errorCode: String): Boolean =
        errorCode == ERROR_USER_NOT_FOUND ||
            errorCode == ERROR_USER_DISABLED ||
            errorCode == ERROR_USER_TOKEN_EXPIRED ||
            errorCode == ERROR_INVALID_USER_TOKEN

    /** Workaround cerrado para la forma que devuelve Auth Emulator al borrar el usuario. */
    internal fun isExpiredSessionGenericMessage(message: String?): Boolean =
        message?.trim() == GENERIC_INVALID_REFRESH_TOKEN_MESSAGE

    /**
     * Mapeo por nombre de código (`FirebaseFunctionsException.Code.name`), como
     * `FunctionsErrorMapper.fromCode`: permite probar la tabla en JVM sin cargar el enum,
     * cuyo inicializador depende de `android.util.SparseArray`.
     */
    fun fromFunctionsCode(code: String, message: String?): AccountError = when (code) {
        CODE_UNAUTHENTICATED ->
            AccountError.SessionExpired

        CODE_PERMISSION_DENIED -> when (backendCode(message)) {
            BackendCode.EMAIL_NOT_VERIFIED -> AccountError.EmailNotVerified
            else -> AccountError.PermissionDenied
        }

        CODE_NOT_FOUND ->
            AccountError.NotFound

        CODE_ALREADY_EXISTS ->
            AccountError.Conflict

        CODE_FAILED_PRECONDITION -> when (backendCode(message)) {
            // El servidor valida `auth_time` antes de adquirir el lock o mutar datos. Tratarlo
            // como credenciales rechazadas permite limpiar el checkpoint write-ahead y evita
            // ejecutar una limpieza local por una eliminación que nunca empezó.
            BackendCode.RECENT_AUTH_REQUIRED -> AccountError.InvalidCredentials
            BackendCode.INVITATION_EXPIRED -> AccountError.InvitationExpired
            BackendCode.ACCOUNT_DELETION_SCOPE_TOO_LARGE -> AccountError.DeletionScopeTooLarge
            BackendCode.LAST_OWNER_REQUIRED,
            BackendCode.OWNED_BUSINESS_HAS_MEMBERS,
            -> AccountError.LastOwnerRequired
            else -> AccountError.Conflict
        }

        CODE_UNAVAILABLE, CODE_DEADLINE_EXCEEDED, CODE_INTERNAL ->
            AccountError.NetworkUnavailable

        else -> AccountError.Unexpected
    }

    /**
     * Reconoce el código cerrado que el backend fija como `message` del HttpsError. Devuelve
     * `null` para cualquier texto libre: ese texto nunca sale de esta capa.
     */
    private fun backendCode(message: String?): BackendCode? =
        BackendCode.entries.firstOrNull { it.wireValue == message?.trim() }

    private enum class BackendCode(val wireValue: String) {
        EMAIL_NOT_VERIFIED("EMAIL_NOT_VERIFIED"),
        RECENT_AUTH_REQUIRED("RECENT_AUTH_REQUIRED"),
        ACCOUNT_DELETION_SCOPE_TOO_LARGE("ACCOUNT_DELETION_SCOPE_TOO_LARGE"),
        INVITATION_EXPIRED("INVITATION_EXPIRED"),
        LAST_OWNER_REQUIRED("LAST_OWNER_REQUIRED"),
        OWNED_BUSINESS_HAS_MEMBERS("OWNED_BUSINESS_HAS_MEMBERS"),
    }

    private const val CODE_UNAUTHENTICATED = "UNAUTHENTICATED"
    private const val CODE_PERMISSION_DENIED = "PERMISSION_DENIED"
    private const val CODE_NOT_FOUND = "NOT_FOUND"
    private const val CODE_ALREADY_EXISTS = "ALREADY_EXISTS"
    private const val CODE_FAILED_PRECONDITION = "FAILED_PRECONDITION"
    private const val CODE_UNAVAILABLE = "UNAVAILABLE"
    private const val CODE_DEADLINE_EXCEEDED = "DEADLINE_EXCEEDED"
    private const val CODE_INTERNAL = "INTERNAL"

    private const val ERROR_INVALID_EMAIL = "ERROR_INVALID_EMAIL"
    private const val ERROR_WRONG_PASSWORD = "ERROR_WRONG_PASSWORD"
    private const val ERROR_INVALID_CREDENTIAL = "ERROR_INVALID_CREDENTIAL"
    private const val ERROR_USER_NOT_FOUND = "ERROR_USER_NOT_FOUND"
    private const val ERROR_USER_DISABLED = "ERROR_USER_DISABLED"
    private const val ERROR_USER_TOKEN_EXPIRED = "ERROR_USER_TOKEN_EXPIRED"
    private const val ERROR_INVALID_USER_TOKEN = "ERROR_INVALID_USER_TOKEN"
    private const val ERROR_EMAIL_ALREADY_IN_USE = "ERROR_EMAIL_ALREADY_IN_USE"
    private const val ERROR_WEAK_PASSWORD = "ERROR_WEAK_PASSWORD"
    private const val GENERIC_INVALID_REFRESH_TOKEN_MESSAGE =
        "An internal error has occurred. [ INVALID_REFRESH_TOKEN ]"
    private const val MAX_CAUSE_DEPTH = 8
}
