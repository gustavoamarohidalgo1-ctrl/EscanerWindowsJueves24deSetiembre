package com.facturastock.app.data.files

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class CappedCancellationByteArrayOutputStreamTest {
    @Test
    fun `returns exact bytes at the limit without retaining overflow`() {
        val output = CappedCancellationByteArrayOutputStream(maximumBytes = 4)
        try {
            output.write(byteArrayOf(1, 2, 3, 4))

            assertArrayEquals(byteArrayOf(1, 2, 3, 4), output.toByteArrayOrNull())
            assertEquals(4, output.size())

            output.write(5)

            assertNull(output.toByteArrayOrNull())
            assertEquals(4, output.size())
        } finally {
            output.close()
        }
    }

    @Test
    fun `cancelled compression cannot append another chunk`() {
        val job = Job()
        val output = CappedCancellationByteArrayOutputStream(
            maximumBytes = 16,
            cancellationJob = job,
        )
        try {
            output.write(byteArrayOf(1, 2, 3))
            job.cancel()

            try {
                output.write(byteArrayOf(4, 5, 6))
                fail("se esperaba cancelación cooperativa")
            } catch (_: CancellationException) {
                // esperado
            }

            assertArrayEquals(byteArrayOf(1, 2, 3), output.toByteArrayOrNull())
        } finally {
            output.close()
        }
    }
}
