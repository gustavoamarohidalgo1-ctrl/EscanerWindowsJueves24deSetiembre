package com.facturastock.app.data.repository

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PurchaseCacheCompletionReceiptCodecTest {
    private val completedAt = Instant.parse("2026-08-30T12:34:56.789Z")

    @Test
    fun `legacy millis remain visible but never prove completion`() {
        val legacy = completedAt.toEpochMilli()

        assertEquals(completedAt, PurchaseCacheCompletionReceiptCodec.displayInstant(legacy))
        assertNull(PurchaseCacheCompletionReceiptCodec.completionInstant(legacy))
        assertEquals(legacy, PurchaseCacheCompletionReceiptCodec.invalidate(legacy))
    }

    @Test
    fun `terminal receipt round trips and invalidation strips only its tag`() {
        val receipt = PurchaseCacheCompletionReceiptCodec.complete(completedAt)

        assertTrue(receipt > completedAt.toEpochMilli())
        assertEquals(completedAt, PurchaseCacheCompletionReceiptCodec.completionInstant(receipt))
        assertEquals(completedAt, PurchaseCacheCompletionReceiptCodec.displayInstant(receipt))
        assertEquals(
            completedAt.toEpochMilli(),
            PurchaseCacheCompletionReceiptCodec.invalidate(receipt),
        )
        assertNull(
            PurchaseCacheCompletionReceiptCodec.completionInstant(
                PurchaseCacheCompletionReceiptCodec.invalidate(receipt),
            ),
        )
    }

    @Test
    fun `corrupt and out of policy values fail closed`() {
        listOf(-1L, Long.MAX_VALUE, 20_000_000_000_000L).forEach { corrupt ->
            assertNull(PurchaseCacheCompletionReceiptCodec.completionInstant(corrupt))
            assertNull(PurchaseCacheCompletionReceiptCodec.displayInstant(corrupt))
            assertNull(PurchaseCacheCompletionReceiptCodec.invalidate(corrupt))
        }
    }
}
