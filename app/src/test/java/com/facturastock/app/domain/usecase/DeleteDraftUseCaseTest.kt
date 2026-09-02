package com.facturastock.app.domain.usecase

import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.InvoiceImage
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.testing.FakeDraftFileStore
import com.facturastock.app.testing.FakeInvoiceDraftRepository
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeleteDraftUseCaseTest {
    private val clock = AppClock { Instant.parse("2026-08-01T12:00:00Z") }
    private val drafts = FakeInvoiceDraftRepository(clock)
    private val fileStore = FakeDraftFileStore()
    private val useCase = DeleteDraftUseCase(drafts, fileStore)

    @Test
    fun `deletes the draft with its images and files, keeping other drafts intact`() = runTest {
        drafts.createDraft(draft(draftId(1)))
        drafts.createDraft(draft(draftId(2)))
        drafts.seedImage(image(imageId(1), draftId(1), pageIndex = 0))
        drafts.seedImage(image(imageId(2), draftId(1), pageIndex = 1))
        drafts.seedImage(image(imageId(3), draftId(2), pageIndex = 0))

        val removed = useCase(draftId(1))

        assertTrue(removed)
        assertNull(drafts.findDraft(draftId(1)))
        // Las imágenes del borrador borrado caen en cascada con el registro…
        assertEquals(emptyList<InvoiceImage>(), drafts.observeImages(draftId(1)).first())
        // El árbol cubre originales y derivados; no hay un segundo borrado por rutas que pueda
        // competir con una recreación del mismo draftId.
        assertTrue(fileStore.deletions.isEmpty())
        assertEquals(listOf(draftId(1)), fileStore.draftTreeDeletions)
        assertEquals(draftId(2), drafts.findDraft(draftId(2))?.draftId)
        assertEquals(
            listOf("captures/${draftId(2).value}/page-0.jpg"),
            drafts.observeImages(draftId(2)).first().map { it.filePath },
        )
    }

    @Test
    fun `returns false and touches nothing when the draft does not exist`() = runTest {
        assertFalse(useCase(draftId(9)))
        assertEquals(emptyList<List<String>>(), fileStore.deletions)
        assertTrue(fileStore.draftTreeDeletions.isEmpty())
    }

    @Test
    fun `a file store failure does not undo the record deletion`() = runTest {
        drafts.createDraft(draft(draftId(1)))
        drafts.seedImage(image(imageId(1), draftId(1), pageIndex = 0))
        fileStore.nextFailure = StorageError.Unavailable

        val removed = useCase(draftId(1))

        assertTrue(removed)
        assertNull(drafts.findDraft(draftId(1)))
    }

    @Test
    fun `a draft recreated before locked tree deletion keeps its new file reference`() = runTest {
        val id = draftId(1)
        val pageId = imageId(1)
        drafts.createDraft(draft(id))
        drafts.seedImage(image(pageId, id, pageIndex = 0))
        val conditionalDeleteReached = CompletableDeferred<Unit>()
        val releaseConditionalDelete = CompletableDeferred<Unit>()
        fileStore.beforeConditionalDraftTreeDelete = {
            conditionalDeleteReached.complete(Unit)
            releaseConditionalDelete.await()
        }

        val discard = async { useCase(id) }
        conditionalDeleteReached.await()
        assertNull(drafts.findDraft(id))

        drafts.createDraft(draft(id))
        val recreatedPage = drafts.seedImage(image(pageId, id, pageIndex = 0))
        releaseConditionalDelete.complete(Unit)

        assertTrue(discard.await())
        assertEquals(id, drafts.findDraft(id)?.draftId)
        assertEquals(recreatedPage, drafts.findImage(pageId))
        assertTrue(fileStore.draftTreeDeletions.isEmpty())
        assertTrue(fileStore.deletions.isEmpty())
    }

    @Test
    fun `discard keeps a tree leaf referenced by a legacy row from another draft`() = runTest {
        val discardedId = draftId(1)
        val survivorId = draftId(2)
        val sharedPath = "captures/${discardedId.value}/page-0.jpg"
        drafts.createDraft(draft(discardedId))
        drafts.createDraft(draft(survivorId))
        drafts.seedImage(image(imageId(1), discardedId, pageIndex = 0))
        drafts.seedImage(
            image(imageId(2), survivorId, pageIndex = 0).copy(filePath = sharedPath),
        )
        fileStore.conditionalDraftTreePaths = mapOf(discardedId to listOf(sharedPath))

        assertTrue(useCase(discardedId))

        assertEquals(sharedPath, drafts.findImage(imageId(2))?.filePath)
        assertTrue(fileStore.draftTreeDeletions.isEmpty())
    }

    private fun draft(id: DraftId) = InvoiceDraft(
        draftId = id,
        businessId = BUSINESS_ID,
        status = DraftStatus.CREATED,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private fun image(id: ImageId, draftId: DraftId, pageIndex: Int) = InvoiceImage(
        imageId = id,
        draftId = draftId,
        businessId = BUSINESS_ID,
        pageIndex = pageIndex,
        filePath = "captures/${draftId.value}/page-$pageIndex.jpg",
        sha256 = "a".repeat(64),
        mimeType = "image/jpeg",
        widthPx = 3_000,
        heightPx = 4_000,
        fileSizeBytes = 1_000L,
        createdAt = Instant.EPOCH,
    )

    private companion object {
        val BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("00000000-0000-0000-0000-0000000000b1"),
        )

        fun uuid(seed: Int): UUID =
            UUID.fromString("00000000-0000-0000-0000-%012d".format(seed))

        fun draftId(seed: Int): DraftId = DraftId.from(uuid(seed))

        fun imageId(seed: Int): ImageId = ImageId.from(uuid(seed + 100))
    }
}
