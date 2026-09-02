package com.facturastock.app.domain.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

class PrivacyMaintenanceSchedulerTest {
    @Test
    fun `best effort informa enqueue exitoso`() = runTest {
        val scheduler = RecordingScheduler()

        assertTrue(scheduler.enqueueBestEffort())
        assertTrue(scheduler.called)
    }

    @Test
    fun `best effort absorbe fallo de almacenamiento del scheduler`() = runTest {
        val scheduler = RecordingScheduler(failure = IllegalStateException("sin espacio"))

        assertFalse(scheduler.enqueueBestEffort())
        assertTrue(scheduler.called)
    }

    @Test
    fun `enqueue inmediato tambien es best effort e independiente`() = runTest {
        val scheduler = RecordingScheduler()

        assertTrue(scheduler.enqueueImmediateBestEffort())
        assertTrue(scheduler.immediateCalled)
    }

    @Test(expected = CancellationException::class)
    fun `best effort nunca absorbe cancelacion estructural`() = runTest {
        RecordingScheduler(failure = CancellationException("cancelado")).enqueueBestEffort()
    }

    @Test(expected = CancellationException::class)
    fun `enqueue inmediato nunca absorbe cancelacion estructural`() = runTest {
        RecordingScheduler(failure = CancellationException("cancelado"))
            .enqueueImmediateBestEffort()
    }

    private class RecordingScheduler(
        private val failure: Exception? = null,
    ) : PrivacyMaintenanceScheduler {
        var called: Boolean = false
        var immediateCalled: Boolean = false

        override suspend fun enqueue() {
            called = true
            failure?.let { throw it }
        }

        override suspend fun enqueueImmediate() {
            immediateCalled = true
            failure?.let { throw it }
        }
    }
}
