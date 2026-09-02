package com.facturastock.app.data.privacy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyMaintenanceRetryPolicyTest {
    @Test
    fun `solo los dos primeros fallos solicitan retry`() {
        assertTrue(PrivacyMaintenanceRetryPolicy.shouldRetry(runAttemptCount = 0))
        assertTrue(PrivacyMaintenanceRetryPolicy.shouldRetry(runAttemptCount = 1))
        assertFalse(PrivacyMaintenanceRetryPolicy.shouldRetry(runAttemptCount = 2))
        assertFalse(PrivacyMaintenanceRetryPolicy.shouldRetry(runAttemptCount = 99))
    }

    @Test
    fun `checkpoint forzado vivo reintenta incluso despues del limite ordinario`() {
        assertTrue(
            PrivacyMaintenanceRetryPolicy.shouldRetry(
                forceDeletionStillPending = true,
                hasRetryableLocalWork = true,
                runAttemptCount = 99,
            ),
        )
    }

    @Test
    fun `trabajo ordinario terminaliza al llegar al limite`() {
        assertFalse(
            PrivacyMaintenanceRetryPolicy.shouldRetry(
                forceDeletionStillPending = false,
                hasRetryableLocalWork = true,
                runAttemptCount = 2,
            ),
        )
        assertFalse(
            PrivacyMaintenanceRetryPolicy.shouldRetry(
                forceDeletionStillPending = false,
                hasRetryableLocalWork = false,
                runAttemptCount = 0,
            ),
        )
    }
}
