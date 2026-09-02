package com.facturastock.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupBackoffPolicyTest {

    @Test
    fun `la secuencia es exponencial desde 30 segundos`() {
        assertEquals(30_000L, BackupBackoffPolicy.backoffMillis(1))
        assertEquals(60_000L, BackupBackoffPolicy.backoffMillis(2))
        assertEquals(120_000L, BackupBackoffPolicy.backoffMillis(3))
        assertEquals(240_000L, BackupBackoffPolicy.backoffMillis(4))
        assertEquals(480_000L, BackupBackoffPolicy.backoffMillis(5))
    }

    @Test
    fun `ninguna espera supera el tope de dos horas`() {
        assertEquals(7_200_000L, BackupBackoffPolicy.MAX_BACKOFF_MILLIS)
        assertEquals(7_200_000L, BackupBackoffPolicy.backoffMillis(9))
        assertEquals(7_200_000L, BackupBackoffPolicy.backoffMillis(40))
    }

    @Test
    fun `attempt cero o negativo es ilegal`() {
        assertThrows(IllegalArgumentException::class.java) { BackupBackoffPolicy.backoffMillis(0) }
        assertThrows(IllegalArgumentException::class.java) { BackupBackoffPolicy.backoffMillis(-1) }
    }

    @Test
    fun `la politica fija cinco intentos y un lease de cinco minutos`() {
        assertEquals(5, BackupBackoffPolicy.MAX_ATTEMPTS)
        assertEquals(300_000L, BackupBackoffPolicy.CLAIM_LEASE_MILLIS)
    }
}
