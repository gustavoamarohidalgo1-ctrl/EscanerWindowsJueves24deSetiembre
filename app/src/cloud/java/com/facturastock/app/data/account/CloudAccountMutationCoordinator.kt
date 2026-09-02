package com.facturastock.app.data.account

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Única frontera de serialización para autenticación, enlace de negocio y limpieza de cuenta.
 * Evita que un comando validado para A escriba DataStore/Room después de que el cliente haya
 * iniciado sesión como B, o que una escritura resucite un enlace tras cerrar sesión.
 */
@Singleton
class CloudAccountMutationCoordinator @Inject constructor() {
    private val mutex = Mutex()

    suspend fun <T> withLock(action: suspend () -> T): T = mutex.withLock { action() }
}
