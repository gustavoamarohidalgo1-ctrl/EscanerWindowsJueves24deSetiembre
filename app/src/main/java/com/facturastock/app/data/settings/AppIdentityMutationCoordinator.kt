package com.facturastock.app.data.settings

import com.facturastock.app.core.coroutines.SuspendMutex
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Frontera de linearización para toda mutación que cambia el negocio local activo.
 *
 * El binder cloud comparte esta exclusión con [DataStoreAppConfigurationRepository]: así puede
 * comprobar el negocio activo y fijar Room/DataStore como una sola operación lógica. Las capas
 * que además necesiten la exclusión de cuenta deben adquirir primero su lock de cuenta y después
 * este; este coordinador nunca llama a la capa cloud, por lo que el orden no forma ciclos.
 */
@Singleton
class AppIdentityMutationCoordinator @Inject constructor() {
    private val mutex = SuspendMutex()
    private val epoch = AtomicLong(0L)

    /** Captura síncrona y process-local de la generación de identidad activa. */
    fun currentEpoch(): Long = epoch.get()

    /** `Long.MAX_VALUE` queda reservado como estado agotado y nunca autoriza una escritura. */
    fun isCurrentEpoch(expected: Long): Boolean =
        expected != Long.MAX_VALUE && epoch.get() == expected

    suspend fun <T> withLock(action: suspend () -> T): T = mutex.withLock(action)

    /**
     * Invalida primero todos los comandos capturados y después intenta la mutación. El avance no
     * se revierte si [action] falla: aceptar un token previo tras un fallo sería reabrir un ABA.
     */
    suspend fun <T> mutate(action: suspend () -> T): T = mutex.withLock {
        val current = epoch.get()
        check(current != Long.MAX_VALUE) { "epoch de identidad agotado" }
        val next = current + 1L
        epoch.set(next)
        check(next != Long.MAX_VALUE) { "epoch de identidad agotado" }
        action()
    }
}
