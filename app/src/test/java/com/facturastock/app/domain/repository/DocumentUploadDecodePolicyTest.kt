package com.facturastock.app.domain.repository

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentUploadDecodePolicyTest {
    @Test
    fun `extreme accepted dimensions are sampled before any full bitmap allocation`() {
        val sample = DocumentUploadDecodePolicy.sampleSize(
            width = DocumentUploadDecodePolicy.MAX_SOURCE_DIMENSION,
            height = DocumentUploadDecodePolicy.MAX_SOURCE_DIMENSION,
        )

        assertEquals(4, sample)
        assertTrue(
            DocumentUploadDecodePolicy.sampledPixelCount(
                width = DocumentUploadDecodePolicy.MAX_SOURCE_DIMENSION,
                height = DocumentUploadDecodePolicy.MAX_SOURCE_DIMENSION,
                sampleSize = sample,
            ) <= DocumentUploadDecodePolicy.MAX_DECODED_PIXELS,
        )
        assertFalse(DocumentUploadDecodePolicy.acceptsSourceDimensions(250_000, 250_000))
    }

    @Test
    fun `oom is translated to a permanent closed error without retaining its cause`() = runTest {
        val failure = runCatching {
            guardDocumentUploadPreparation<Unit> {
                throw OutOfMemoryError("hostile dimensions and private path")
            }
        }.exceptionOrNull()

        assertTrue(failure is DocumentUploadPreparationException)
        failure as DocumentUploadPreparationException
        assertEquals(
            DocumentUploadPreparationError.RESOURCE_LIMIT_EXCEEDED,
            failure.error,
        )
        assertEquals("DOCUMENT_PREPARATION_RESOURCE_LIMIT", failure.message)
        assertNull(failure.cause)
    }
}
