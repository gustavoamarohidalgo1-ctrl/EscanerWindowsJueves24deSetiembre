package com.facturastock.app.data.account

import com.facturastock.app.data.sync.FirebaseBackupRuntime
import com.facturastock.app.domain.error.AccountError
import com.facturastock.app.domain.error.AccountException
import com.facturastock.app.domain.error.DomainResult
import com.facturastock.app.domain.model.AccountDeletionSummary
import com.facturastock.app.domain.model.AccountSession
import com.facturastock.app.domain.repository.AccountRepository
import com.facturastock.app.domain.repository.PurchaseBackupScheduler
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.EmailAuthProvider
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await

/**
 * `AccountRepository` del flavor cloud sobre Firebase Auth (email/contraseña con verificación).
 *
 * Los tokens jamás salen de esta clase: el resultado de `getIdToken` se descarta en el acto y
 * ningún error transporta mensajes del SDK — solo códigos cerrados vía [AccountErrorMapper].
 * Sin configuración Firebase todo responde `Unavailable` y el flujo local no cambia.
 */
@Singleton
class FirebaseAccountRepository @Inject constructor(
    private val runtime: FirebaseBackupRuntime,
    private val store: CloudAccountSettingsStore,
    private val coordinator: CloudAccountMutationCoordinator,
    // Perezoso: el scheduler depende del transporte, que a su vez consulta esta cuenta.
    private val scheduler: Lazy<PurchaseBackupScheduler>,
) : AccountRepository {

    override val available: Boolean
        get() = runtime.config != null

    /**
     * Marca de sesión expirada fijada por [recoverSession]: aunque el SDK siga viendo un
     * usuario local, para la app hay que reingresar. Se limpia con el próximo acceso válido.
     */
    private val expiredOverride = MutableStateFlow<ExpiredSessionOverride?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeSession(): Flow<AccountSession> {
        val auth = runtime.auth() ?: return flowOf(AccountSession.Unavailable)
        val users = callbackFlow {
            // El estado Active también depende de que el JWT haya incorporado la verificación.
            // IdTokenListener cubre sign-in/sign-out y, a diferencia de AuthStateListener,
            // vuelve a emitir cuando `getIdToken(true)` renueva el token del mismo usuario.
            val listener = FirebaseAuth.IdTokenListener { firebaseAuth ->
                trySend(firebaseAuth.currentUser)
            }
            auth.addIdTokenListener(listener)
            awaitClose { auth.removeIdTokenListener(listener) }
        }
        return combine(users, store.observeStoredLink(), expiredOverride) { user, storedLink, expired ->
            sessionFor(user, storedLink, expired)
        }
    }

    override fun observePendingAccountDeletionUid(): Flow<String?> =
        store.observePendingAccountDeletionUid()

    override suspend fun pendingAccountDeletionUid(): String? =
        store.pendingAccountDeletionUid()

    override suspend fun signIn(email: String, password: String): DomainResult<AccountSession> =
        coordinator.withLock {
            guarding {
                val auth = requireAuth()
                auth.signInWithEmailAndPassword(email, password).await()
                expiredOverride.value = null
                DomainResult.Success(currentSession(auth))
            }
        }

    override suspend fun register(email: String, password: String): DomainResult<AccountSession> =
        coordinator.withLock {
            guarding {
                val auth = requireAuth()
                auth.createUserWithEmailAndPassword(email, password).await()
                expiredOverride.value = null
                val user = auth.currentUser ?: throw AccountException(AccountError.Unexpected)
                val createdSession = currentSession(auth)
                try {
                    user.sendEmailVerification().await()
                    checkCurrentUid(auth, user.uid)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: AccountException) {
                    throw failure
                } catch (failure: Exception) {
                    if (AccountErrorMapper.isExpiredSessionFailure(failure)) {
                        throw AccountException(AccountError.SessionExpired, failure)
                    }
                    // El alta ya fue confirmada por Auth. Un fallo al enviar el primer correo no
                    // puede convertirla en un fallo reintentable que luego choque con EmailInUse;
                    // la sesión AwaitingVerification ofrece el reenvío explícito.
                }
                DomainResult.Success(createdSession)
            }
        }

    override suspend fun resendVerificationEmail(): DomainResult<Unit> = coordinator.withLock {
        val auth = runtime.auth()
            ?: return@withLock DomainResult.Failure(AccountError.Unavailable)
        val user = auth.currentUser
            ?: return@withLock DomainResult.Failure(AccountError.NotAuthenticated)
        guardingExistingUser(auth, user) {
            user.sendEmailVerification().await()
            checkCurrentUid(auth, user.uid)
            expiredOverride.value = null
            DomainResult.Success(Unit)
        }
    }

    override suspend fun refreshVerification(): DomainResult<AccountSession> =
        coordinator.withLock {
            val auth = runtime.auth()
                ?: return@withLock DomainResult.Failure(AccountError.Unavailable)
            val user = auth.currentUser
                ?: return@withLock DomainResult.Failure(AccountError.NotAuthenticated)
            guardingExistingUser(auth, user) {
                user.reload().await()
                checkCurrentUid(auth, user.uid)
                val refreshedUser = auth.currentUser
                    ?: throw AccountException(AccountError.SessionExpired)
                if (refreshedUser.isEmailVerified) {
                    // `reload()` actualiza el perfil (`isEmailVerified`), pero Firestore y
                    // Functions autorizan con el JWT cacheado. No publicar Active hasta haber
                    // renovado el token que contiene `email_verified=true`.
                    refreshedUser.getIdToken(true).await()
                    checkCurrentUid(auth, user.uid)
                }
                expiredOverride.value = null
                DomainResult.Success(currentSession(auth))
            }
        }

    override suspend fun sendPasswordReset(email: String): DomainResult<Unit> = guarding {
        requireAuth().sendPasswordResetEmail(email).await()
        DomainResult.Success(Unit)
    }

    override suspend fun recoverSession(): DomainResult<AccountSession> = coordinator.withLock {
        val auth = runtime.auth()
            ?: return@withLock DomainResult.Failure(AccountError.Unavailable)
        val user = auth.currentUser
            ?: return@withLock DomainResult.Failure(AccountError.NotAuthenticated)
        guardingExistingUser(auth, user) {
            // Fuerza la renovación del token; el resultado (que lo contiene) se descarta.
            user.getIdToken(true).await()
            checkCurrentUid(auth, user.uid)
            expiredOverride.value = null
            DomainResult.Success(currentSession(auth))
        }
    }

    override suspend fun signOut(): DomainResult<Unit> = coordinator.withLock {
        val auth = runtime.auth()
            ?: return@withLock DomainResult.Failure(AccountError.Unavailable)
        try {
            // Cancela el drenado pendiente y olvida el enlace; compras y borradores locales
            // jamás se tocan.
            scheduler.get().cancelAll()
            store.clear()
            expiredOverride.value = null
            auth.signOut()
            DomainResult.Success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            DomainResult.Failure(AccountErrorMapper.fromException(failure))
        }
    }

    override suspend fun signOutIfCurrent(expectedUid: String): DomainResult<Boolean> =
        coordinator.withLock {
            val auth = runtime.auth()
                ?: return@withLock DomainResult.Failure(AccountError.Unavailable)
            try {
                val currentUser = auth.currentUser
                if (currentUser != null && currentUser.uid != expectedUid) {
                    store.clearPendingAccountDeletion(expectedUid)
                    return@withLock DomainResult.Success(false)
                }
                // El sign-out del SDK es síncrono: se ejecuta inmediatamente después del guard
                // para no cerrar una cuenta distinta tras una suspensión de limpieza.
                auth.signOut()
                scheduler.get().cancelAll()
                store.clear()
                expiredOverride.value = null
                DomainResult.Success(true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                DomainResult.Failure(AccountErrorMapper.fromException(failure))
            }
        }

    /**
     * Reautentica primero y persiste el UID inmediatamente antes del callable de borrado. Todo
     * el protocolo comparte la exclusión de auth/enlace; si el proceso muere una vez escrito el
     * checkpoint, el próximo ViewModel limpia localmente sin repetir un resultado ambiguo.
     */
    override suspend fun requestAccountDeletion(
        expectedUid: String,
        password: String,
    ): DomainResult<AccountDeletionSummary> = coordinator.withLock {
        if (password.length < MIN_REAUTH_PASSWORD_LENGTH) {
            return@withLock DomainResult.Failure(AccountError.InvalidCredentials)
        }
        var checkpointWritten = false
        try {
            val auth = requireAuth()
            val functions = runtime.functions()
                ?: throw AccountException(AccountError.Unavailable)
            val user = auth.currentUser
                ?.takeIf { it.uid == expectedUid }
                ?: throw AccountException(AccountError.NotAuthenticated)
            val email = user.email?.takeIf(String::isNotBlank)
                ?: throw AccountException(AccountError.NotAuthenticated)

            user.reauthenticate(EmailAuthProvider.getCredential(email, password)).await()
            checkCurrentUid(auth, expectedUid)
            // Fuerza el token cuyo auth_time acaba de renovar la reautenticación; se descarta.
            user.getIdToken(true).await()
            checkCurrentUid(auth, expectedUid)
            // Write-ahead inmediato al único paso ambiguo: antes de aquí ningún borrado remoto
            // pudo comenzar; después de aquí el callable nunca se reenvía si se pierde el resultado.
            store.markAccountDeletionPending(expectedUid)
            checkpointWritten = true
            val result = functions
                .getHttpsCallable(DELETE_MY_ACCOUNT_CALLABLE)
                // Segunda frontera de identidad: aunque Auth cambiase fuera de este
                // repositorio, Functions rechaza un JWT que no corresponda al usuario que
                // reautenticó y autorizó esta eliminación.
                .call(mapOf("expectedUid" to expectedUid))
                .await()
            val data = result.data as? Map<*, *>
                ?: throw AccountException(AccountError.Unexpected)
            DomainResult.Success(AccountMappers.accountDeletionSummary(data))
        } catch (cancelled: CancellationException) {
            // El checkpoint se conserva: el servidor pudo haber consumido el request.
            throw cancelled
        } catch (failure: AccountException) {
            if (checkpointWritten && failure.error.isDefinitiveDeletionRejection()) {
                clearDeletionCheckpointBestEffort(expectedUid)
            }
            DomainResult.Failure(failure.error)
        } catch (failure: Exception) {
            val error = AccountErrorMapper.fromException(failure)
            if (checkpointWritten && error.isDefinitiveDeletionRejection()) {
                clearDeletionCheckpointBestEffort(expectedUid)
            }
            DomainResult.Failure(error)
        }
    }

    private fun checkCurrentUid(auth: FirebaseAuth, expectedUid: String) {
        if (auth.currentUser?.uid != expectedUid) {
            throw AccountException(AccountError.SessionExpired)
        }
    }

    private suspend fun clearDeletionCheckpointBestEffort(expectedUid: String) {
        try {
            store.clearPendingAccountDeletion(expectedUid)
        } catch (_: Exception) {
            // Mantener el checkpoint es el fallo seguro: obliga a una limpieza local posterior.
        }
    }

    private fun requireAuth(): FirebaseAuth =
        runtime.auth() ?: throw AccountException(AccountError.Unavailable)

    private suspend fun currentSession(auth: FirebaseAuth): AccountSession =
        sessionFor(auth.currentUser, store.observeStoredLink().first(), expiredOverride.value)

    private fun sessionFor(
        user: FirebaseUser?,
        storedLink: StoredCloudBusinessLink?,
        expired: ExpiredSessionOverride?,
    ): AccountSession {
        // Auth puede retirar inmediatamente el usuario local al detectar que fue borrado. La
        // marca sigue siendo válida mientras no aparezca una identidad distinta.
        if (expired != null && (user == null || expired.uid == user.uid)) return expired.session
        if (user == null) return AccountSession.SignedOut
        // Con email/contraseña el correo siempre existe; si faltara, la sesión no es
        // representable en los estados cerrados y se trata como ausente.
        val email = user.email?.takeIf { it.isNotBlank() }
            ?: return AccountSession.SignedOut
        val ownedLink = storedLink
            ?.takeIf { it.ownerUid == null || it.ownerUid == user.uid }
        return if (user.isEmailVerified) {
            AccountSession.Active(
                uid = user.uid,
                email = email,
                link = ownedLink?.link,
                linkRevision = ownedLink?.revision ?: 0L,
            )
        } else {
            AccountSession.AwaitingVerification(uid = user.uid, email = email)
        }
    }

    private suspend fun <T> guarding(block: suspend () -> DomainResult<T>): DomainResult<T> =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AccountException) {
            DomainResult.Failure(failure.error)
        } catch (failure: Exception) {
            DomainResult.Failure(AccountErrorMapper.fromException(failure))
        }

    /**
     * Variante contextual para operaciones sobre un [FirebaseUser] ya autenticado. Mantiene la
     * anti-enumeración de sign-in, pero una invalidación remota del usuario actual sí publica el
     * estado cerrado [AccountSession.Expired].
     */
    private suspend fun <T> guardingExistingUser(
        auth: FirebaseAuth,
        user: FirebaseUser,
        block: suspend () -> DomainResult<T>,
    ): DomainResult<T> = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: AccountException) {
        DomainResult.Failure(failure.error)
    } catch (failure: Exception) {
        if (AccountErrorMapper.isExpiredSessionFailure(failure)) {
            // El mutex impide que esta marca de A se publique después de un sign-in de B.
            val currentUid = auth.currentUser?.uid
            if (currentUid == null || currentUid == user.uid) {
                expiredOverride.value = ExpiredSessionOverride(
                    uid = user.uid,
                    session = AccountSession.Expired(user.email),
                )
            }
            DomainResult.Failure(AccountError.SessionExpired)
        } else {
            DomainResult.Failure(AccountErrorMapper.fromException(failure))
        }
    }

    private companion object {
        const val DELETE_MY_ACCOUNT_CALLABLE = "deleteMyAccount"
        const val MIN_REAUTH_PASSWORD_LENGTH = 6
    }
}

private data class ExpiredSessionOverride(
    val uid: String,
    val session: AccountSession.Expired,
)

internal fun AccountError.isDefinitiveDeletionRejection(): Boolean = when (this) {
    AccountError.InvalidCredentials,
    AccountError.InvalidEmail,
    AccountError.EmailNotVerified,
    AccountError.PermissionDenied,
    AccountError.NotFound,
    AccountError.LastOwnerRequired,
    AccountError.DeletionScopeTooLarge,
    // En este flujo solo puede nacer antes del checkpoint (cambio local de UID) o de la
    // autenticación/expectedUid del callable, antes de entrar al handler destructivo.
    AccountError.SessionExpired,
    -> true

    // Red, INTERNAL o conflicto pueden llegar después de que el servidor haya consumido el
    // request. El checkpoint se conserva y se elige la limpieza segura.
    else -> false
}
