package com.facturastock.app.domain.repository

import java.time.Instant
import kotlinx.coroutines.CancellationException

/**
 * Puerto del programador de drenado de la outbox. La implementación productiva usa WorkManager;
 * en ausencia de transporte configurado todos los métodos son un no-op deliberado. Las operaciones
 * son suspendidas para confirmar la escritura/cancelación durable de WorkManager antes de volver.
 */
interface PurchaseBackupScheduler {
    /**
     * Encola un drenado inmediato en una cadena única. Cada wake se preserva detrás de una
     * pasada activa para cerrar la ventana entre su último snapshot y `Result.success`.
     */
    suspend fun enqueue()

    /** Programa el follow-up del próximo intento diferido o vencimiento de lease. */
    suspend fun enqueueAt(attemptAt: Instant)

    /**
     * Variante usada solo por un follow-up que despertó antes de su propio deadline y necesita
     * un sucesor para el mismo instante. [runningWorkId] evita colisionar con el request RUNNING;
     * el default mantiene adaptadores simples compatibles.
     */
    suspend fun enqueueSuccessorAt(attemptAt: Instant, runningWorkId: String) = enqueueAt(attemptAt)

    /** Tombstones explícitos de privacidad deben avanzar aunque el master de sync esté off. */
    suspend fun enqueuePrivacyPurge() = enqueue()

    suspend fun enqueuePrivacyPurgeAt(attemptAt: Instant) = enqueueAt(attemptAt)

    suspend fun enqueuePrivacyPurgeSuccessorAt(
        attemptAt: Instant,
        runningWorkId: String,
    ) = enqueuePrivacyPurgeAt(attemptAt)

    /**
     * Cancela únicamente el respaldo comercial. Desactivar el master usa esta variante antes de
     * despertar la purga, evitando cancelar y volver a encolar con KEEP el mismo canal.
     */
    suspend fun cancelRegular() = cancelAll()

    /**
     * Cancela todo el trabajo de respaldo encolado o en curso (p. ej. al cerrar sesión).
     * Las operaciones de la outbox no se borran: vuelven a drenarse con el próximo enqueue.
     */
    suspend fun cancelAll()
}

/** No-op explícito para adaptadores locales aislados en pruebas que no inicializan WorkManager. */
object DisabledPurchaseBackupScheduler : PurchaseBackupScheduler {
    override suspend fun enqueue() = Unit
    override suspend fun enqueueAt(attemptAt: Instant) = Unit
    override suspend fun enqueueSuccessorAt(attemptAt: Instant, runningWorkId: String) = Unit
    override suspend fun enqueuePrivacyPurge() = Unit
    override suspend fun enqueuePrivacyPurgeAt(attemptAt: Instant) = Unit
    override suspend fun enqueuePrivacyPurgeSuccessorAt(
        attemptAt: Instant,
        runningWorkId: String,
    ) = Unit
    override suspend fun cancelRegular() = Unit
    override suspend fun cancelAll() = Unit
}

/**
 * Programa el drenado como efecto posterior a un commit durable.
 *
 * La outbox en Room es la fuente de verdad. Si la base interna de WorkManager no puede escribir
 * temporalmente (por ejemplo, almacenamiento lleno), propagar esa excepción haría que la UI
 * mostrara que falló una compra que en realidad ya quedó publicada. El próximo arranque u otra
 * mutación volverá a encolar la misma cola persistente.
 */
suspend fun PurchaseBackupScheduler.enqueueBestEffort(): Boolean = try {
    enqueue()
    true
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    false
}

/** Variante segura para el despertar diferido posterior a un fallo transitorio durable. */
suspend fun PurchaseBackupScheduler.enqueueAtBestEffort(attemptAt: Instant): Boolean = try {
    enqueueAt(attemptAt)
    true
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    false
}

suspend fun PurchaseBackupScheduler.enqueuePrivacyPurgeBestEffort(): Boolean = try {
    enqueuePrivacyPurge()
    true
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    false
}

suspend fun PurchaseBackupScheduler.enqueuePrivacyPurgeAtBestEffort(attemptAt: Instant): Boolean = try {
    enqueuePrivacyPurgeAt(attemptAt)
    true
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    false
}

suspend fun PurchaseBackupScheduler.enqueueSuccessorAtBestEffort(
    attemptAt: Instant,
    runningWorkId: String,
): Boolean = try {
    enqueueSuccessorAt(attemptAt, runningWorkId)
    true
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    false
}

suspend fun PurchaseBackupScheduler.enqueuePrivacyPurgeSuccessorAtBestEffort(
    attemptAt: Instant,
    runningWorkId: String,
): Boolean = try {
    enqueuePrivacyPurgeSuccessorAt(attemptAt, runningWorkId)
    true
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    false
}
