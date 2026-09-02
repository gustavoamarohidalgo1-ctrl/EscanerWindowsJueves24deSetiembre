package com.facturastock.app

import kotlinx.coroutines.CancellationException

/**
 * Ejecuta las redes de seguridad de arranque en orden, pero con fronteras de fallo separadas.
 * Ninguna de estas acciones es la fuente de verdad: Room y WorkManager conservan el estado para
 * el próximo intento. Una cancelación estructurada sí debe detener inmediatamente la secuencia.
 */
internal suspend fun runIndependentStartupRecovery(
    cleanOrphanedImports: suspend () -> Unit,
    recoverOutboxClaims: suspend () -> Unit,
    enqueueRegularBackup: suspend () -> Unit,
    enqueuePrivacyPurge: suspend () -> Unit,
) {
    runStartupStep(cleanOrphanedImports)
    runStartupStep(recoverOutboxClaims)
    runStartupStep(enqueueRegularBackup)
    runStartupStep(enqueuePrivacyPurge)
}

private suspend fun runStartupStep(step: suspend () -> Unit) {
    try {
        step()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Mejor esfuerzo: la siguiente red de seguridad sigue siendo independiente y Room
        // conserva cualquier estado durable que este intento no haya podido procesar.
    }
}
