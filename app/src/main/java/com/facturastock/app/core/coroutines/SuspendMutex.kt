package com.facturastock.app.core.coroutines

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Primitiva mínima de exclusión para casos de uso puros.
 *
 * La capa de dominio depende de este contrato de `core`, no de la implementación concreta de
 * coroutines. La sección crítica puede suspender sin bloquear un hilo.
 */
class SuspendMutex {
    private val delegate = Mutex()

    suspend fun <T> withLock(action: suspend () -> T): T = delegate.withLock { action() }
}
