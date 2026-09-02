package com.facturastock.app.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FirebaseRemoteDocumentArchiveTest {
    @Test
    fun `protected metadata yields the server digest without inventing a local hash`() {
        assertEquals(
            SHA,
            validatedRemoteDocumentSha(
                contentType = "image/jpeg",
                sizeBytes = 1_024L,
                sha256 = SHA,
                metadataPurchaseId = PURCHASE_ID,
                metadataImageId = IMAGE_ID,
                expectedPurchaseId = PURCHASE_ID,
                expectedImageId = IMAGE_ID,
            ),
        )
    }

    @Test
    fun `wrong identity digest mime or bounded size rejects metadata`() {
        val valid = Arguments()
        val invalid = listOf(
            valid.copy(contentType = "image/png"),
            valid.copy(sizeBytes = 0L),
            valid.copy(sizeBytes = 2L * 1024 * 1024 + 1L),
            valid.copy(sha256 = "A".repeat(64)),
            valid.copy(metadataPurchaseId = OTHER_ID),
            valid.copy(metadataImageId = OTHER_ID),
        )

        invalid.forEach { arguments ->
            assertNull(
                validatedRemoteDocumentSha(
                    contentType = arguments.contentType,
                    sizeBytes = arguments.sizeBytes,
                    sha256 = arguments.sha256,
                    metadataPurchaseId = arguments.metadataPurchaseId,
                    metadataImageId = arguments.metadataImageId,
                    expectedPurchaseId = PURCHASE_ID,
                    expectedImageId = IMAGE_ID,
                ),
            )
        }
    }

    private data class Arguments(
        val contentType: String = "image/jpeg",
        val sizeBytes: Long = 1_024L,
        val sha256: String = SHA,
        val metadataPurchaseId: String = PURCHASE_ID,
        val metadataImageId: String = IMAGE_ID,
    )

    private companion object {
        const val PURCHASE_ID = "00000000-0000-0000-0000-000000000001"
        const val IMAGE_ID = "00000000-0000-0000-0000-000000000002"
        const val OTHER_ID = "00000000-0000-0000-0000-000000000003"
        const val SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
