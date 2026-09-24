package com.facturastock.app.data.account

import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.model.AccountDeletionSummary
import com.facturastock.app.domain.model.AccountSession
import com.facturastock.app.domain.repository.AccountRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Binding productivo del flavor local: no hay nube. La sesión declara no estar disponible y
 * cada operación responde con el código cerrado [AccountError.Unavailable]; el flujo local
 * (captura, OCR, compras, inventario) no depende de esta cuenta para nada.
 */
@Singleton
class UnavailableAccountRepository @Inject constructor() : AccountRepository {
    override val available: Boolean = false

    override fun observeSession(): Flow<AccountSession> = flowOf(AccountSession.Unavailable)

    override fun observePendingAccountDeletionUid(): Flow<String?> = flowOf(null)

    override suspend fun pendingAccountDeletionUid(): String? = null

    override suspend fun signIn(email: String, password: String): DomainResult<AccountSession> =
        unavailable()

    override suspend fun register(email: String, password: String): DomainResult<AccountSession> =
        unavailable()

    override suspend fun resendVerificationEmail(): DomainResult<Unit> = unavailable()

    override suspend fun refreshVerification(): DomainResult<AccountSession> = unavailable()

    override suspend fun sendPasswordReset(email: String): DomainResult<Unit> = unavailable()

    override suspend fun recoverSession(): DomainResult<AccountSession> = unavailable()

    override suspend fun signOut(): DomainResult<Unit> = unavailable()

    override suspend fun signOutIfCurrent(expectedUid: String): DomainResult<Boolean> = unavailable()

    override suspend fun requestAccountDeletion(
        expectedUid: String,
        password: String,
    ): DomainResult<AccountDeletionSummary> =
        unavailable()

    private fun <T> unavailable(): DomainResult<T> =
        DomainResult.Failure(AccountError.Unavailable)
}
