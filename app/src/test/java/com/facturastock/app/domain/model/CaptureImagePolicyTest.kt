package com.facturastock.app.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureImagePolicyTest {

    @Test
    fun `every documented image mime type is allowed`() {
        listOf("image/jpeg", "image/png", "image/webp")
            .forEach { mimeType ->
                assertTrue(mimeType, CaptureImagePolicy.isMimeTypeAllowed(mimeType))
            }
    }

    @Test
    fun `heic and heif are rejected because their metadata cannot be scrubbed`() {
        listOf("image/heic", "image/heif", "image/heic-sequence", "image/heif-sequence")
            .forEach { mimeType ->
                assertFalse(mimeType, CaptureImagePolicy.isMimeTypeAllowed(mimeType))
            }
    }

    @Test
    fun `mime types are normalized before checking`() {
        assertTrue(CaptureImagePolicy.isMimeTypeAllowed("IMAGE/JPEG"))
        assertTrue(CaptureImagePolicy.isMimeTypeAllowed(" image/png "))
    }

    @Test
    fun `unknown missing or non image mime types are rejected`() {
        listOf(null, "", "   ", "image/gif", "image/bmp", "image/svg+xml", "application/pdf")
            .forEach { mimeType ->
                assertFalse("$mimeType", CaptureImagePolicy.isMimeTypeAllowed(mimeType))
            }
    }

    @Test
    fun `sizes up to the maximum are allowed including the boundary`() {
        assertTrue(CaptureImagePolicy.isSizeAllowed(0L))
        assertTrue(CaptureImagePolicy.isSizeAllowed(1L))
        assertTrue(CaptureImagePolicy.isSizeAllowed(CaptureImagePolicy.MAX_FILE_SIZE_BYTES))
    }

    @Test
    fun `sizes beyond the maximum or negative are rejected`() {
        assertFalse(
            CaptureImagePolicy.isSizeAllowed(CaptureImagePolicy.MAX_FILE_SIZE_BYTES + 1L),
        )
        assertFalse(CaptureImagePolicy.isSizeAllowed(-1L))
    }

    @Test
    fun `dimensions up to the maximum are allowed including the boundary`() {
        assertTrue(CaptureImagePolicy.areDimensionsAllowed(1, 1))
        assertTrue(
            CaptureImagePolicy.areDimensionsAllowed(
                CaptureImagePolicy.MAX_DIMENSION_PX,
                CaptureImagePolicy.MAX_DIMENSION_PX,
            ),
        )
        assertTrue(CaptureImagePolicy.areDimensionsAllowed(8_000, 1))
        assertTrue(CaptureImagePolicy.areDimensionsAllowed(1, 8_000))
    }

    @Test
    fun `dimensions beyond the maximum or not positive are rejected`() {
        assertFalse(
            CaptureImagePolicy.areDimensionsAllowed(
                CaptureImagePolicy.MAX_DIMENSION_PX + 1,
                1,
            ),
        )
        assertFalse(
            CaptureImagePolicy.areDimensionsAllowed(
                1,
                CaptureImagePolicy.MAX_DIMENSION_PX + 1,
            ),
        )
        assertFalse(CaptureImagePolicy.areDimensionsAllowed(0, 100))
        assertFalse(CaptureImagePolicy.areDimensionsAllowed(100, 0))
        assertFalse(CaptureImagePolicy.areDimensionsAllowed(-1, -1))
    }
}
