package com.facturastock.app.testing

import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.model.AccountDeletionSummary
import com.facturastock.app.domain.model.AccountSession
import com.facturastock.app.domain.model.RemotePurchaseDescription
import com.facturastock.app.domain.model.CatalogSyncPullPage
import com.facturastock.app.domain.model.RemoteCatalogApplication
import com.facturastock.app.domain.model.RemotePurchaseChange
import com.facturastock.app.domain.model.SyncCursor
import com.facturastock.app.domain.model.SyncPullPage
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.repository.AccountRepository
import com.facturastock.app.domain.repository.BackupEnvelope
import com.facturastock.app.domain.repository.BackupTransportResult
import com.facturastock.app.domain.repository.PurchaseBackupTransport
import com.facturastock.app.domain.repository.RemoteLedgerRepository
import com.facturastock.app.domain.repository.RemotePurchaseCacheCompletion
import com.facturastock.app.domain.repository.RemoteSyncCacheRepository
import com.facturastock.app.domain.repository.SyncCursorRepository
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Dobles compartidos de androidTest para el worker push-then-pull: viven en el source set
 * común para que la prueba compile igual en ambos flavors (los bindings `Unavailable*` de
 * producción son propios del flavor local y no existen en cloud).
 */

/** Sin nube: toda lectura del libro remoto declara `Unavailable`. */
class UnavailableRemoteLedgerDouble : RemoteLedgerRepository {
    override val available: Boolean = false

    override suspend fun pullChanges(
        businessId: BusinessId,
        sinceSeq: Long,
        limit: Int,
    ): DomainResult<SyncPullPage> = DomainResult.Failure(AccountError.Unavailable)

    override suspend fun describeRemotePurchase(
        businessId: BusinessId,
        remotePurchaseId: String,
    ): DomainResult<RemotePurchaseDescription?> = DomainResult.Failure(AccountError.Unavailable)
}

/** Sin backend: el transporte declara `Unavailable`, como el binding productivo del flavor local. */
class UnavailablePurchaseBackupTransportDouble : PurchaseBackupTransport {
    override val configured: Boolean = false

    override suspend fun send(envelope: BackupEnvelope): BackupTransportResult =
        BackupTransportResult.Unavailable
}

/** Sin libro remoto no hay cursor: seq siempre 0 y guardar es un éxito vacío. */
class NoOpSyncCursorDouble : SyncCursorRepository, RemoteSyncCacheRepository {
    override suspend fun lastCatalogPulledSeq(businessId: BusinessId): Long = 0L

    override suspend fun lastPulledSeq(businessId: BusinessId): Long = 0L

    override suspend fun beginPurchasePull(
        businessId: BusinessId,
        expectedPreviousSeq: Long,
    ): DomainResult<Unit> = DomainResult.Success(Unit)

    override suspend fun saveCursor(
        businessId: BusinessId,
        seq: Long,
        pulledAt: Instant,
    ): DomainResult<Unit> = DomainResult.Success(Unit)

    override fun observeCursor(businessId: BusinessId): Flow<SyncCursor?> = flowOf(null)

    override suspend fun persistPurchasePage(
        businessId: BusinessId,
        expectedPreviousSeq: Long,
        page: SyncPullPage,
        pulledAt: Instant,
    ): DomainResult<Unit> = DomainResult.Success(Unit)

    override suspend fun persistCatalogPage(
        businessId: BusinessId,
        expectedPreviousSeq: Long,
        page: CatalogSyncPullPage,
        pulledAt: Instant,
    ): DomainResult<Unit> = DomainResult.Success(Unit)

    override suspend fun purchaseCacheCompletion(
        businessId: BusinessId,
    ): RemotePurchaseCacheCompletion? = null

    override suspend fun listPurchaseChanges(businessId: BusinessId): List<RemotePurchaseChange> =
        emptyList()

    override fun observePurchaseDescriptions(
        businessId: BusinessId,
    ): Flow<Map<String, RemotePurchaseDescription>> = flowOf(emptyMap())

    override fun observeCatalogApplications(
        businessId: BusinessId,
    ): Flow<List<RemoteCatalogApplication>> = flowOf(emptyList())
}

/** Sesión nunca activa: el pull del worker no arranca y el push queda intacto. */
class SignedOutAccountRepositoryDouble : AccountRepository {
    override val available: Boolean = false

    override fun observeSession(): Flow<AccountSession> = flowOf(AccountSession.SignedOut)

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
