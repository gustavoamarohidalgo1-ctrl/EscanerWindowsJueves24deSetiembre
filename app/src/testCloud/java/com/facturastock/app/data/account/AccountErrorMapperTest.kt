package com.facturastock.app.data.account

import com.facturastock.app.domain.error.AccountError
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Las excepciones del SDK Firebase no son instanciables en JVM (sus constructores y el
 * inicializador del enum `Code` dependen de `android.text.TextUtils` y `android.util.SparseArray`),
 * así que las tablas se ejercitan por los puntos de entrada públicos por código, como hace
 * `FunctionsErrorMapperTest` con `fromCode`. `fromException` se cubre con tipos JVM puros.
 */
class AccountErrorMapperTest {

    @Test
    fun `errores de auth se mapean por codigo cerrado`() {
        assertEquals(
            AccountError.InvalidEmail,
            AccountErrorMapper.fromAuthErrorCode("ERROR_INVALID_EMAIL"),
        )
        listOf(
            "ERROR_WRONG_PASSWORD",
            "ERROR_INVALID_CREDENTIAL",
            "ERROR_USER_NOT_FOUND",
            "ERROR_USER_DISABLED",
        ).forEach { code ->
            assertEquals(AccountError.InvalidCredentials, AccountErrorMapper.fromAuthErrorCode(code))
        }
        assertEquals(
            AccountError.EmailInUse,
            AccountErrorMapper.fromAuthErrorCode("ERROR_EMAIL_ALREADY_IN_USE"),
        )
        assertEquals(
            AccountError.WeakPassword,
            AccountErrorMapper.fromAuthErrorCode("ERROR_WEAK_PASSWORD"),
        )
        listOf("ERROR_USER_TOKEN_EXPIRED", "ERROR_INVALID_USER_TOKEN").forEach { code ->
            assertEquals(AccountError.SessionExpired, AccountErrorMapper.fromAuthErrorCode(code))
        }
    }

    @Test
    fun `invalidacion de usuario autenticado reconoce solo codigos definitivos`() {
        listOf(
            "ERROR_USER_NOT_FOUND",
            "ERROR_USER_DISABLED",
            "ERROR_USER_TOKEN_EXPIRED",
            "ERROR_INVALID_USER_TOKEN",
        ).forEach { code -> assertTrue(AccountErrorMapper.isExpiredSessionAuthErrorCode(code)) }
        listOf(
            "ERROR_INVALID_CREDENTIAL",
            "ERROR_TOO_MANY_REQUESTS",
            "ERROR_NETWORK_REQUEST_FAILED",
        ).forEach { code -> assertFalse(AccountErrorMapper.isExpiredSessionAuthErrorCode(code)) }
        assertTrue(
            AccountErrorMapper.isExpiredSessionGenericMessage(
                "An internal error has occurred. [ INVALID_REFRESH_TOKEN ]",
            ),
        )
        listOf(
            null,
            "INVALID_REFRESH_TOKEN",
            "An internal error has occurred. [ INVALID_REFRESH_TOKEN ] extra",
            "free text INVALID_REFRESH_TOKEN",
        ).forEach { message ->
            assertFalse(AccountErrorMapper.isExpiredSessionGenericMessage(message))
        }
    }

    @Test
    fun `error de auth desconocido es inesperado`() {
        assertEquals(
            AccountError.Unexpected,
            AccountErrorMapper.fromAuthErrorCode("ERROR_TOO_MANY_REQUESTS"),
        )
    }

    @Test
    fun `permission denied reconoce solo el codigo cerrado de verificacion`() {
        assertEquals(
            AccountError.EmailNotVerified,
            AccountErrorMapper.fromFunctionsCode("PERMISSION_DENIED", "EMAIL_NOT_VERIFIED"),
        )
        listOf("NOT_A_MEMBER", "ROLE_FORBIDDEN", "texto libre del backend", null).forEach { message ->
            assertEquals(
                AccountError.PermissionDenied,
                AccountErrorMapper.fromFunctionsCode("PERMISSION_DENIED", message),
            )
        }
    }

    @Test
    fun `not found y already exists tienen codigo propio`() {
        assertEquals(
            AccountError.NotFound,
            AccountErrorMapper.fromFunctionsCode("NOT_FOUND", "BUSINESS_NOT_FOUND"),
        )
        assertEquals(
            AccountError.Conflict,
            AccountErrorMapper.fromFunctionsCode("ALREADY_EXISTS", "BUSINESS_EXISTS"),
        )
    }

    @Test
    fun `failed precondition distingue los codigos cerrados conocidos`() {
        assertEquals(
            AccountError.InvalidCredentials,
            AccountErrorMapper.fromFunctionsCode("FAILED_PRECONDITION", "RECENT_AUTH_REQUIRED"),
        )
        assertEquals(
            AccountError.InvitationExpired,
            AccountErrorMapper.fromFunctionsCode("FAILED_PRECONDITION", "INVITATION_EXPIRED"),
        )
        assertEquals(
            AccountError.LastOwnerRequired,
            AccountErrorMapper.fromFunctionsCode("FAILED_PRECONDITION", "LAST_OWNER_REQUIRED"),
        )
        assertEquals(
            AccountError.LastOwnerRequired,
            AccountErrorMapper.fromFunctionsCode(
                "FAILED_PRECONDITION",
                "OWNED_BUSINESS_HAS_MEMBERS",
            ),
        )
        assertEquals(
            AccountError.DeletionScopeTooLarge,
            AccountErrorMapper.fromFunctionsCode(
                "FAILED_PRECONDITION",
                "ACCOUNT_DELETION_SCOPE_TOO_LARGE",
            ),
        )
        listOf("INVITATION_NOT_PENDING", "INVITATION_EMAIL_MISMATCH", "detalle interno", null)
            .forEach { message ->
                assertEquals(
                    AccountError.Conflict,
                    AccountErrorMapper.fromFunctionsCode("FAILED_PRECONDITION", message),
                )
            }
    }

    @Test
    fun `unauthenticated es sesion expirada`() {
        assertEquals(
            AccountError.SessionExpired,
            AccountErrorMapper.fromFunctionsCode("UNAUTHENTICATED", RAW_DETAIL),
        )
        assertTrue(AccountError.SessionExpired.isDefinitiveDeletionRejection())
    }

    @Test
    fun `fallos de infraestructura son red no disponible`() {
        listOf("UNAVAILABLE", "DEADLINE_EXCEEDED", "INTERNAL").forEach { code ->
            assertEquals(
                AccountError.NetworkUnavailable,
                AccountErrorMapper.fromFunctionsCode(code, RAW_DETAIL),
            )
        }
        assertEquals(
            AccountError.NetworkUnavailable,
            AccountErrorMapper.fromException(IOException(RAW_DETAIL)),
        )
    }

    @Test
    fun `solo rechazos previos al handler limpian checkpoint de borrado`() {
        listOf(
            AccountError.InvalidCredentials,
            AccountError.InvalidEmail,
            AccountError.EmailNotVerified,
            AccountError.PermissionDenied,
            AccountError.NotFound,
            AccountError.LastOwnerRequired,
            AccountError.DeletionScopeTooLarge,
            AccountError.SessionExpired,
        ).forEach { error -> assertTrue(error.isDefinitiveDeletionRejection()) }
        listOf(
            AccountError.NetworkUnavailable,
            AccountError.Conflict,
            AccountError.Unexpected,
        ).forEach { error -> assertFalse(error.isDefinitiveDeletionRejection()) }
    }

    @Test
    fun `cualquier otro fallo es inesperado`() {
        assertEquals(
            AccountError.Unexpected,
            AccountErrorMapper.fromFunctionsCode("INVALID_ARGUMENT", RAW_DETAIL),
        )
        assertEquals(
            AccountError.Unexpected,
            AccountErrorMapper.fromFunctionsCode("RESOURCE_EXHAUSTED", RAW_DETAIL),
        )
        assertEquals(
            AccountError.Unexpected,
            AccountErrorMapper.fromException(IllegalStateException(RAW_DETAIL)),
        )
    }

    @Test
    fun `el resultado nunca contiene el mensaje original del backend`() {
        val mapped = listOf(
            AccountErrorMapper.fromAuthErrorCode("ERROR_WRONG_PASSWORD"),
            AccountErrorMapper.fromFunctionsCode("PERMISSION_DENIED", RAW_DETAIL),
            AccountErrorMapper.fromFunctionsCode("FAILED_PRECONDITION", RAW_DETAIL),
            AccountErrorMapper.fromFunctionsCode("UNAVAILABLE", RAW_DETAIL),
            AccountErrorMapper.fromFunctionsCode("NOT_FOUND", RAW_DETAIL),
            AccountErrorMapper.fromException(IOException(RAW_DETAIL)),
            AccountErrorMapper.fromException(IllegalStateException(RAW_DETAIL)),
        )
        mapped.forEach { result ->
            assertFalse(result.toString().contains(RAW_DETAIL))
            assertFalse(result.toString().contains(SECRET_MARKER))
        }
    }

    private companion object {
        const val SECRET_MARKER = "correo.privado@example.com token-abc-123"
        const val RAW_DETAIL = "detalle crudo: $SECRET_MARKER"
    }
}
