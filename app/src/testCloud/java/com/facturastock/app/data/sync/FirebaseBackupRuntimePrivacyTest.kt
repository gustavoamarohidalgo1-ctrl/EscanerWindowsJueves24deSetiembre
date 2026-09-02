package com.facturastock.app.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FirebaseBackupRuntimePrivacyTest {
    @Test
    fun `same sdk throwable on both denial attempts cannot bypass quarantine`() {
        val singletonFailure = IllegalStateException("singleton-sdk-failure")

        val recovered = retryFirebasePrivacyDenialOnce(singletonFailure) {
            throw singletonFailure
        }

        assertFalse(recovered)
        assertTrue(singletonFailure.suppressed.isEmpty())
    }

    @Test
    fun `distinct retry failure is retained on the original denial failure`() {
        val firstFailure = IllegalStateException("first-sdk-failure")
        val retryFailure = IllegalArgumentException("retry-sdk-failure")

        val recovered = retryFirebasePrivacyDenialOnce(firstFailure) {
            throw retryFailure
        }

        assertFalse(recovered)
        assertEquals(1, firstFailure.suppressed.size)
        assertSame(retryFailure, firstFailure.suppressed.single())
    }

    @Test
    fun `successful immediate denial retry keeps runtime recoverable`() {
        val recovered = retryFirebasePrivacyDenialOnce(IllegalStateException("first")) {
            // Segundo intento confirmado.
        }

        assertTrue(recovered)
    }
}
