package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.observability.OperationalAgeBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OperationalAgeBucketTest {
    @Test
    fun `outbox age is reduced to closed privacy buckets at exact boundaries`() {
        assertEquals(OperationalAgeBucket.UNDER_15_MINUTES, operationalAgeBucket(0L))
        assertEquals(
            OperationalAgeBucket.UNDER_15_MINUTES,
            operationalAgeBucket(15L * MINUTE - 1L),
        )
        assertEquals(
            OperationalAgeBucket.FROM_15_MINUTES_TO_1_HOUR,
            operationalAgeBucket(15L * MINUTE),
        )
        assertEquals(
            OperationalAgeBucket.FROM_1_TO_6_HOURS,
            operationalAgeBucket(60L * MINUTE),
        )
        assertEquals(
            OperationalAgeBucket.FROM_6_TO_24_HOURS,
            operationalAgeBucket(6L * HOUR),
        )
        assertEquals(
            OperationalAgeBucket.FROM_1_TO_7_DAYS,
            operationalAgeBucket(24L * HOUR),
        )
        assertEquals(
            OperationalAgeBucket.OVER_7_DAYS,
            operationalAgeBucket(7L * DAY),
        )
        assertThrows(IllegalArgumentException::class.java) { operationalAgeBucket(-1L) }
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60L * MINUTE
        const val DAY = 24L * HOUR
    }
}
