package com.facturastock.app.domain.model

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bordes exactos del decisor de retención con reloj fijo: el borrado por antigüedad ocurre
 * precisamente cuando `postedAt + retentionDays <= now`.
 */
class ImageRetentionDeciderTest {
    private val postedAt: Instant = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `shouldDeleteAfterOcr solo es cierta con AFTER_OCR`() {
        ImageRetentionPolicy.entries.forEach { policy ->
            assertEquals(
                policy == ImageRetentionPolicy.AFTER_OCR,
                ImageRetentionDecider.shouldDeleteAfterOcr(policy),
            )
        }
    }

    @Test
    fun `shouldDeleteAfterConfirm solo es cierta con AFTER_CONFIRM`() {
        ImageRetentionPolicy.entries.forEach { policy ->
            assertEquals(
                policy == ImageRetentionPolicy.AFTER_CONFIRM,
                ImageRetentionDecider.shouldDeleteAfterConfirm(policy),
            )
        }
    }

    @Test
    fun `DAYS_30 borra exactamente al cumplirse 30 dias`() {
        // 29 días: aún no.
        assertFalse(deleteAfterDays(ImageRetentionPolicy.DAYS_30, 29))
        // 30 días exactos: el instante límite ya pertenece al borrado.
        assertTrue(deleteAfterDays(ImageRetentionPolicy.DAYS_30, 30))
        // 31 días: vencida con holgura.
        assertTrue(deleteAfterDays(ImageRetentionPolicy.DAYS_30, 31))
    }

    @Test
    fun `DAYS_90 borra exactamente al cumplirse 90 dias`() {
        assertFalse(deleteAfterDays(ImageRetentionPolicy.DAYS_90, 89))
        assertTrue(deleteAfterDays(ImageRetentionPolicy.DAYS_90, 90))
        assertTrue(deleteAfterDays(ImageRetentionPolicy.DAYS_90, 91))
    }

    @Test
    fun `DAYS_30 un segundo antes del limite aun conserva`() {
        val now = postedAt.plus(Duration.ofDays(30)).minusSeconds(1)
        assertFalse(
            ImageRetentionDecider.shouldDeleteRetainedImage(
                ImageRetentionPolicy.DAYS_30,
                postedAt,
                now,
            ),
        )
    }

    @Test
    fun `KEEP nunca borra por antiguedad`() {
        assertFalse(deleteAfterDays(ImageRetentionPolicy.KEEP, 0))
        assertFalse(deleteAfterDays(ImageRetentionPolicy.KEEP, 3650))
    }

    @Test
    fun `AFTER_OCR y AFTER_CONFIRM borran lo retenido sea cual sea la antiguedad`() {
        // La compra ya pasó su hito de borrado: un archivo superviviente se elimina.
        assertTrue(deleteAfterDays(ImageRetentionPolicy.AFTER_OCR, 1))
        assertTrue(deleteAfterDays(ImageRetentionPolicy.AFTER_CONFIRM, 1))
    }

    @Test
    fun `las politicas con ventana declaran sus dias y las demas no`() {
        assertEquals(30L, ImageRetentionPolicy.DAYS_30.retentionDays)
        assertEquals(90L, ImageRetentionPolicy.DAYS_90.retentionDays)
        assertEquals(null, ImageRetentionPolicy.AFTER_OCR.retentionDays)
        assertEquals(null, ImageRetentionPolicy.AFTER_CONFIRM.retentionDays)
        assertEquals(null, ImageRetentionPolicy.KEEP.retentionDays)
    }

    private fun deleteAfterDays(policy: ImageRetentionPolicy, days: Long): Boolean =
        ImageRetentionDecider.shouldDeleteRetainedImage(
            policy,
            postedAt,
            postedAt.plus(Duration.ofDays(days)),
        )
}
