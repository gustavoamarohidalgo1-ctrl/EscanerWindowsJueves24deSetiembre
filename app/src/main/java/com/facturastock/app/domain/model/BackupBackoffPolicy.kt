package com.facturastock.app.domain.model

/**
 * Política de reintento de la outbox de respaldo. Es deliberadamente determinista (sin jitter)
 * para que el `nextAttemptAt` persistido en Room sea auditable y reproducible en pruebas.
 * WorkManager aplica su propio backoff a nivel trabajo; esta política gobierna el reintento
 * por operación, que es la única fuente de verdad durable sobre cuándo reintentar.
 */
object BackupBackoffPolicy {
    /** Intentos de envío antes de declarar el fallo permanente y exigir acción manual. */
    const val MAX_ATTEMPTS = 5

    /** Primer reintento tras un fallo transitorio. */
    const val BASE_BACKOFF_MILLIS = 30_000L

    /** Techo de cualquier espera entre reintentos automáticos. */
    const val MAX_BACKOFF_MILLIS = 2 * 60 * 60 * 1_000L

    /** Ventana durante la cual el dueño de un claim PROCESSING puede completar o fallar. */
    const val CLAIM_LEASE_MILLIS = 5 * 60 * 1_000L

    /**
     * Espera antes del siguiente intento automático. [attemptNumber] es 1-based: el valor de
     * `attemptCount` que la operación ya consumió con el claim actual. Secuencia: 30 s, 60 s,
     * 120 s, 240 s, 480 s y luego el tope.
     */
    fun backoffMillis(attemptNumber: Int): Long {
        require(attemptNumber >= 1) { "attemptNumber es 1-based: $attemptNumber" }
        var backoff = BASE_BACKOFF_MILLIS
        repeat(attemptNumber - 1) {
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MILLIS)
        }
        return backoff.coerceAtMost(MAX_BACKOFF_MILLIS)
    }
}
