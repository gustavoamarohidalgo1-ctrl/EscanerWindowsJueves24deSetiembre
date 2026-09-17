package com.facturastock.app.testing

import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.model.AccountDeletionSummary
import com.facturastock.app.domain.model.AccountSession
import com.facturastock.app.domain.repository.AccountRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * Fake de sesión de cuenta con guion: la sesión es un [MutableStateFlow] manipulable con
 * [emitSession] y cada operación registra sus argumentos. Los resultados `next*` se consumen
 * una vez (como [StorageFailureHook]); sin resultado programado la operación tiene éxito con
 * la sesión vigente. En todo éxito que cambia la sesión el flujo se actualiza, igual que el
 * authState de Firebase, para que los observadores reaccionen.
 */
class FakeAccountRepository(
    initialSession: AccountSession = AccountSession.SignedOut,
    override val available: Boolean = true,
    private val connectivity: FakeFirebaseConnectivity = FakeFirebaseConnectivity(),
    override val accountDeletionAvailable: Boolean = available,
) : AccountRepository {
    private val sessionFlow = MutableStateFlow(
        if (available) initialSession else AccountSession.Unavailable,
    )
    private val pendingAccountDeletionUidFlow = MutableStateFlow<String?>(null)

    // --- Registro de llamadas ---
    val signInCalls = mutableListOf<Pair<String, String>>()
    val registerCalls = mutableListOf<Pair<String, String>>()
    val passwordResetCalls = mutableListOf<String>()

    var signOutCalls: Int = 0
        private set

    var recoverSessionCalls: Int = 0
        private set

    var resendVerificationCalls: Int = 0
        private set

    var refreshVerificationCalls: Int = 0
        private set

    var accountDeletionCalls: Int = 0
        private set

    // --- Guion: resultado de la próxima llamada; null = éxito por defecto ---
    var nextSignInResult: DomainResult<AccountSession>? = null
    var nextRegisterResult: DomainResult<AccountSession>? = null
    var nextRecoverSessionResult: DomainResult<AccountSession>? = null
    var nextRefreshVerificationResult: DomainResult<AccountSession>? = null
    var nextResendVerificationResult: DomainResult<Unit>? = null
    var nextPasswordResetResult: DomainResult<Unit>? = null
    var nextSignOutResult: DomainResult<Unit>? = null
    var nextAccountDeletionResult: DomainResult<AccountDeletionSummary>? = null
    var signOutIfCurrentHandler: (suspend (String) -> DomainResult<Boolean>)? = null
    var accountDeletionHandler:
        (suspend (String) -> DomainResult<AccountDeletionSummary>)? = null
    var observeSessionFailure: Throwable? = null
    var observeSessionFailureAfterCurrentEmission: Throwable? = null

    /** Sesión actual emitida por [observeSession]. */
    val session: AccountSession
        get() = sessionFlow.value

    /** Simula un cambio de autenticación o de enlace notificado por el backend. */
    fun emitSession(session: AccountSession) {
        sessionFlow.value = session
    }

    override fun observeSession(): Flow<AccountSession> = flow {
        observeSessionFailure?.let { throw it }
        val failureAfterEmission = observeSessionFailureAfterCurrentEmission
        if (failureAfterEmission != null) {
            emit(sessionFlow.value)
            throw failureAfterEmission
        }
        emitAll(sessionFlow)
    }

    override fun observePendingAccountDeletionUid(): Flow<String?> = pendingAccountDeletionUidFlow

    override suspend fun pendingAccountDeletionUid(): String? = pendingAccountDeletionUidFlow.value

    fun seedPendingAccountDeletion(uid: String?) {
        pendingAccountDeletionUidFlow.value = uid
    }

    override suspend fun signIn(email: String, password: String): DomainResult<AccountSession> {
        signInCalls += email to password
        cloudFailure<AccountSession>()?.let { return it }
        return sessionResult(nextSignInResult) { nextSignInResult = null }
    }

    override suspend fun register(email: String, password: String): DomainResult<AccountSession> {
        registerCalls += email to password
        cloudFailure<AccountSession>()?.let { return it }
        return sessionResult(nextRegisterResult) { nextRegisterResult = null }
    }

    override suspend fun resendVerificationEmail(): DomainResult<Unit> {
        resendVerificationCalls++
        cloudFailure<Unit>()?.let { return it }
        return unitResult(nextResendVerificationResult) { nextResendVerificationResult = null }
    }

    override suspend fun refreshVerification(): DomainResult<AccountSession> {
        refreshVerificationCalls++
        cloudFailure<AccountSession>()?.let { return it }
        return sessionResult(nextRefreshVerificationResult) { nextRefreshVerificationResult = null }
    }

    override suspend fun sendPasswordReset(email: String): DomainResult<Unit> {
        passwordResetCalls += email
        cloudFailure<Unit>()?.let { return it }
        return unitResult(nextPasswordResetResult) { nextPasswordResetResult = null }
    }

    override suspend fun recoverSession(): DomainResult<AccountSession> {
        recoverSessionCalls++
        cloudFailure<AccountSession>()?.let { return it }
        return sessionResult(nextRecoverSessionResult) { nextRecoverSessionResult = null }
    }

    override suspend fun signOut(): DomainResult<Unit> {
        signOutCalls++
        if (!available) return DomainResult.Failure(AccountError.Unavailable)
        val result = nextSignOutResult ?: DomainResult.Success(Unit)
        nextSignOutResult = null
        // El cierre de sesión real siempre notifica SignedOut; nunca toca las compras locales.
        if (result is DomainResult.Success) {
            pendingAccountDeletionUidFlow.value = null
            sessionFlow.value = AccountSession.SignedOut
        }
        return result
    }

    val signOutIfCurrentCalls = mutableListOf<String>()

    override suspend fun signOutIfCurrent(expectedUid: String): DomainResult<Boolean> {
        signOutIfCurrentCalls += expectedUid
        signOutCalls++
        signOutIfCurrentHandler?.let { return it(expectedUid) }
        if (!available) return DomainResult.Failure(AccountError.Unavailable)
        val currentUid = when (val current = sessionFlow.value) {
            is AccountSession.Active -> current.uid
            is AccountSession.AwaitingVerification -> current.uid
            else -> null
        }
        if (currentUid != null && currentUid != expectedUid) {
            if (pendingAccountDeletionUidFlow.value == expectedUid) {
                pendingAccountDeletionUidFlow.value = null
            }
            return DomainResult.Success(false)
        }
        val result = nextSignOutResult ?: DomainResult.Success(Unit)
        nextSignOutResult = null
        return when (result) {
            is DomainResult.Success -> {
                pendingAccountDeletionUidFlow.value = null
                sessionFlow.value = AccountSession.SignedOut
                DomainResult.Success(true)
            }

            is DomainResult.Failure -> result
        }
    }

    val accountDeletionPasswords = mutableListOf<String>()

    override suspend fun requestAccountDeletion(
        expectedUid: String,
        password: String,
    ): DomainResult<AccountDeletionSummary> {
        accountDeletionCalls++
        accountDeletionPasswords += password
        val currentUid = when (val current = sessionFlow.value) {
            is AccountSession.Active -> current.uid
            is AccountSession.AwaitingVerification -> current.uid
            else -> null
        }
        if (currentUid != expectedUid) {
            return DomainResult.Failure(AccountError.NotAuthenticated)
        }
        pendingAccountDeletionUidFlow.value = expectedUid
        val handled = accountDeletionHandler?.invoke(password)
        if (handled != null) return finishDeletionAttempt(expectedUid, handled)
        cloudFailure<AccountDeletionSummary>()?.let { return it }
        // El callable remoto no limpia por sí solo la sesión/cache del cliente. La UI debe
        // completar el cierre con signOut y puede reintentarlo sin repetir el borrado remoto.
        val result = nextAccountDeletionResult
            ?: DomainResult.Success(AccountDeletionSummary(emptyList(), 0))
        nextAccountDeletionResult = null
        return finishDeletionAttempt(expectedUid, result)
    }

    private fun finishDeletionAttempt(
        expectedUid: String,
        result: DomainResult<AccountDeletionSummary>,
    ): DomainResult<AccountDeletionSummary> {
        val error = (result as? DomainResult.Failure)?.error
        if (
            error != null &&
            error != AccountError.NetworkUnavailable &&
            error != AccountError.Unexpected &&
            error != AccountError.SessionExpired
        ) {
            if (pendingAccountDeletionUidFlow.value == expectedUid) {
                pendingAccountDeletionUidFlow.value = null
            }
        }
        return result
    }

    /** Resuelve el guion de una operación de sesión; el éxito reemite la sesión. */
    private fun sessionResult(
        programmed: DomainResult<AccountSession>?,
        consume: () -> Unit,
    ): DomainResult<AccountSession> {
        val result = programmed ?: DomainResult.Success(sessionFlow.value)
        consume()
        if (result is DomainResult.Success) sessionFlow.value = result.value
        return result
    }

    private fun unitResult(
        programmed: DomainResult<Unit>?,
        consume: () -> Unit,
    ): DomainResult<Unit> {
        val result = programmed ?: DomainResult.Success(Unit)
        consume()
        return result
    }

    /** La configuración ausente y el modo avión son fallos distintos, igual que en producción. */
    private fun <T> cloudFailure(): DomainResult<T>? = when {
        !available -> DomainResult.Failure(AccountError.Unavailable)
        !connectivity.isConnected -> DomainResult.Failure(AccountError.NetworkUnavailable)
        else -> null
    }
}
