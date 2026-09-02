package com.facturastock.app.data.repository

import java.time.DateTimeException
import java.time.Instant

/**
 * Codifica un recibo versionado sin alterar el esquema Room v26. Los millis legacy permanecen
 * legibles para la UI, pero nunca cuentan como prueba de completitud.
 *
 * El rango civil admitido llega holgadamente más allá de la vida útil de la app. Valores fuera
 * del rango se tratan como corrupción y fallan cerrados.
 */
internal object PurchaseCacheCompletionReceiptCodec {
    fun complete(completedAt: Instant): Long {
        val epochMillis = completedAt.toEpochMilli()
        require(epochMillis in 0L..MAX_PLAIN_EPOCH_MILLIS)
        return RECEIPT_V1_OFFSET + epochMillis
    }

    fun completionInstant(storedValue: Long?): Instant? {
        if (storedValue == null || storedValue !in RECEIPT_V1_RANGE) return null
        return instantOrNull(storedValue - RECEIPT_V1_OFFSET)
    }

    /** Fecha visible tanto para valores legacy como para recibos v1 válidos. */
    fun displayInstant(storedValue: Long?): Instant? {
        if (storedValue == null) return null
        completionInstant(storedValue)?.let { return it }
        if (storedValue !in 0L..MAX_PLAIN_EPOCH_MILLIS) return null
        return instantOrNull(storedValue)
    }

    /** Retira únicamente la marca de completitud, conservando la fecha visible anterior. */
    fun invalidate(storedValue: Long?): Long? =
        displayInstant(storedValue)?.toEpochMilli()

    private fun instantOrNull(epochMillis: Long): Instant? = try {
        Instant.ofEpochMilli(epochMillis)
    } catch (_: DateTimeException) {
        null
    }

    private const val RECEIPT_V1_OFFSET = 4_000_000_000_000_000_000L
    private const val MAX_PLAIN_EPOCH_MILLIS = 10_000_000_000_000L
    private val RECEIPT_V1_RANGE =
        RECEIPT_V1_OFFSET..(RECEIPT_V1_OFFSET + MAX_PLAIN_EPOCH_MILLIS)
}
