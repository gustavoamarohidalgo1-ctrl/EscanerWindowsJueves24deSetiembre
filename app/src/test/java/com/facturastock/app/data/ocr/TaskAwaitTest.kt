package com.facturastock.app.data.ocr

import com.google.android.gms.tasks.TaskCompletionSource
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TaskAwaitTest {
    @Test
    fun `successful Task resumes the suspended caller once`() = runTest {
        val source = TaskCompletionSource<String>()
        var value: String? = null
        val job = launch { value = source.task.awaitCancellable() }

        source.setResult("local-result")
        runCurrent()

        assertTrue(job.isCompleted)
        assertEquals("local-result", value)
    }

    @Test
    fun `late Task success cannot resume a cancelled caller`() = runTest {
        val source = TaskCompletionSource<String>()
        var successCallbackRan = false
        val job = launch {
            source.task.awaitCancellable()
            successCallbackRan = true
        }
        runCurrent()

        job.cancelAndJoin()
        source.setResult("late OCR text")
        runCurrent()

        assertFalse(successCallbackRan)
    }

    @Test
    fun `Task failure is propagated without replacing its cause`() = runTest {
        val source = TaskCompletionSource<String>()
        val expected = IllegalStateException("controlled failure without invoice text")
        var received: Throwable? = null
        val job = launch {
            received = runCatching { source.task.awaitCancellable() }.exceptionOrNull()
        }

        source.setException(expected)
        runCurrent()

        assertTrue(job.isCompleted)
        assertEquals(expected::class, received?.let { it::class })
        assertEquals(expected.message, received?.message)
    }
}
