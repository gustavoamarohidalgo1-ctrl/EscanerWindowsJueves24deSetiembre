package com.facturastock.app.domain.repository

import kotlinx.coroutines.CancellationException

/** Programa el mantenimiento local periódico de privacidad. */
interface PrivacyMaintenanceScheduler {
    /** Mantiene la pasada diaria única. */
    suspend fun enqueue()

    /** Despierta una pasada inmediata; el default conserva compatibilidad de implementaciones. */
    suspend fun enqueueImmediate() = enqueue()
}

/**
 * El mantenimiento es una red de seguridad; una base WorkManager sin espacio nunca debe
 * impedir abrir la app. Los hooks de OCR/confirmación siguen aplicando la retención en línea.
 */
suspend fun PrivacyMaintenanceScheduler.enqueueBestEffort(): Boolean = try {
    enqueue()
    true
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    false
}

/** La confirmación/arranque no deben fallar aunque WorkManager no pueda persistir la solicitud. */
suspend fun PrivacyMaintenanceScheduler.enqueueImmediateBestEffort(): Boolean = try {
    enqueueImmediate()
    true
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    false
}
