package com.facturastock.app.domain.repository

import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PurchaseBackupSchedulerTest {
    @Test
    fun `privacy wake does not report success before durable enqueue completes`() = runTest {
        val release = CompletableDeferred<Unit>()
        val scheduler = RecordingScheduler(beforePrivacyEnqueue = { release.await() })

        val result = async { scheduler.enqueuePrivacyPurgeBestEffort() }
        runCurrent()

        assertFalse(result.isCompleted)
        release.complete(Unit)
        assertTrue(result.await())
        assertTrue(scheduler.privacyEnqueueCalled)
    }

    @Test
    fun `privacy wake reports a confirmed WorkManager write failure`() = runTest {
        val scheduler = RecordingScheduler(failure = IOException("WorkManager lleno"))

        assertFalse(scheduler.enqueuePrivacyPurgeBestEffort())
        assertTrue(scheduler.privacyEnqueueCalled)
    }

    @Test
    fun `privacy wake never swallows structural cancellation`() = runTest {
        val scheduler = RecordingScheduler(failure = CancellationException("cancelado"))

        try {
            scheduler.enqueuePrivacyPurgeBestEffort()
            fail("se esperaba CancellationException")
        } catch (_: CancellationException) {
            // La cancelación pertenece al scope llamador y debe propagarse.
        }
    }

    private class RecordingScheduler(
        private val failure: Exception? = null,
        private val beforePrivacyEnqueue: suspend () -> Unit = {},
    ) : PurchaseBackupScheduler {
        var privacyEnqueueCalled: Boolean = false
            private set

        override suspend fun enqueue() = Unit

        override suspend fun enqueueAt(attemptAt: Instant) = Unit

        override suspend fun enqueuePrivacyPurge() {
            privacyEnqueueCalled = true
            beforePrivacyEnqueue()
            failure?.let { throw it }
        }

        override suspend fun cancelAll() = Unit
    }
}
