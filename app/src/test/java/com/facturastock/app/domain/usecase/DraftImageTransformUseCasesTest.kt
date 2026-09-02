package com.facturastock.app.domain.usecase

import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.ImageCrop
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.InvoiceImage
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.repository.OcrImageFile
import com.facturastock.app.testing.FakeDraftFileStore
import com.facturastock.app.testing.FakeInvoiceImagePreprocessor
import com.facturastock.app.testing.FakeInvoiceDraftRepository
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Cobertura de las transformaciones de páginas: giro (×4 = identidad), recorte, reordenado,
 * eliminación con reindexado y preparación de copias OCR.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DraftImageTransformUseCasesTest {
    private var now: Instant = Instant.parse("2026-08-01T12:00:00Z")
    private val clock = AppClock { now }
    private val fileStore = FakeDraftFileStore()
    private val drafts = FakeInvoiceDraftRepository(clock)
    private val imagePreprocessor = FakeInvoiceImagePreprocessor()
    private val rotate = RotateDraftImageUseCase(drafts)
    private val crop = CropDraftImageUseCase(drafts)
    private val reorder = ReorderDraftImagesUseCase(drafts)
    private val deleteImage = DeleteDraftImageUseCase(drafts, fileStore)
    private val preprocess = PreprocessDraftImagesUseCase(drafts, imagePreprocessor)

    @Test
    fun `rotating four times restores the original rotation and crop`() = runTest {
        seedDraftWithPages(1)
        val original = drafts.findImage(PAGE_IDS[0])!!
            .copy(rotationDegrees = 90, crop = ImageCrop(1_000, 2_000, 8_000, 9_000))
        drafts.replaceSeedImage(original)

        val rotated = List(4) { rotate(PAGE_IDS[0]) }.last()

        assertEquals(original.rotationDegrees, rotated.rotationDegrees)
        assertEquals(original.crop, rotated.crop)
        // Y quedó persistido igual en el repositorio.
        assertEquals(original.crop, drafts.findImage(PAGE_IDS[0])?.crop)
        assertEquals(90, drafts.findImage(PAGE_IDS[0])?.rotationDegrees)
    }

    @Test
    fun `rotating once transforms crop in the same atomic intent`() =
        runTest {
            seedDraftWithPages(1)
            rotate(PAGE_IDS[0])

            val updated = drafts.findImage(PAGE_IDS[0])!!
            assertEquals(90, updated.rotationDegrees)
            assertEquals(ImageCrop(1_500, 1_000, 8_000, 8_000), updated.crop)
        }

    @Test
    fun `two concurrent rotations compose to 180 without lost update`() = runTest {
        seedDraftWithPages(1)
        val start = CompletableDeferred<Unit>()

        val rotations = List(2) {
            async {
                start.await()
                rotate(PAGE_IDS[0])
            }
        }
        runCurrent()
        start.complete(Unit)
        rotations.awaitAll()

        val persisted = drafts.findImage(PAGE_IDS[0])!!
        assertEquals(180, persisted.rotationDegrees)
        assertEquals(
            ImageCrop(1_000, 2_000, 8_000, 8_500).rotated90Cw().rotated90Cw(),
            persisted.crop,
        )
    }

    @Test
    fun `concurrent rotation and crop preserve both edits`() = runTest {
        seedDraftWithPages(1)
        // El rectángulo asimétrico hace visibles los dos órdenes serializables válidos. La UI
        // serializa gestos de usuario; este puerto además no debe perder ninguno ante callers
        // concurrentes.
        val rectangle = ImageCrop(500, 1_500, 8_000, 9_000)
        val start = CompletableDeferred<Unit>()

        val rotation = async {
            start.await()
            rotate(PAGE_IDS[0])
        }
        val cropping = async {
            start.await()
            crop(PAGE_IDS[0], rectangle)
        }
        runCurrent()
        start.complete(Unit)
        awaitAll(rotation, cropping)

        val persisted = drafts.findImage(PAGE_IDS[0])!!
        assertEquals(90, persisted.rotationDegrees)
        assertTrue(persisted.crop in setOf(rectangle, rectangle.rotated90Cw()))
    }

    @Test
    fun `rotating a missing page fails controlled`() = runTest {
        try {
            rotate(PAGE_IDS[0])
            fail("se esperaba StorageException")
        } catch (expected: StorageException) {
            assertEquals(StorageError.Unavailable, expected.error)
        }
    }

    @Test
    fun `cropping a missing page fails controlled`() = runTest {
        try {
            crop(PAGE_IDS[0], ImageCrop(100, 200, 9_900, 9_800))
            fail("se esperaba StorageException")
        } catch (expected: StorageException) {
            assertEquals(StorageError.Unavailable, expected.error)
        }
    }

    @Test
    fun `cropping persists the rectangle and a full image clears it`() = runTest {
        seedDraftWithPages(1)
        val rectangle = ImageCrop(100, 200, 9_900, 9_800)

        crop(PAGE_IDS[0], rectangle)
        assertEquals(rectangle, drafts.findImage(PAGE_IDS[0])?.crop)

        crop(PAGE_IDS[0], ImageCrop(0, 0, ImageCrop.FRACTION_MAX, ImageCrop.FRACTION_MAX))
        assertNull(drafts.findImage(PAGE_IDS[0])?.crop)

        // El archivo y el resto de metadatos no cambian: el original se preserva.
        val image = drafts.findImage(PAGE_IDS[0])!!
        assertEquals("draft_images/${DRAFT_ID.value}/p0.jpg", image.filePath)
        assertEquals(64, image.widthPx)
        assertEquals(48, image.heightPx)
    }

    @Test
    fun `reordering moves a page and persists the whole order`() = runTest {
        seedDraftWithPages(3)

        val moved = reorder(DRAFT_ID, PAGE_IDS[2], moveUp = true)
        assertEquals(
            listOf(PAGE_IDS[0], PAGE_IDS[2], PAGE_IDS[1]),
            moved.map(InvoiceImage::imageId),
        )
        assertEquals(
            listOf(0, 1, 2),
            drafts.observeImages(DRAFT_ID).first().map(InvoiceImage::pageIndex),
        )

        // Extremos: no-op controlado.
        assertEquals(
            listOf(PAGE_IDS[0], PAGE_IDS[2], PAGE_IDS[1]),
            reorder(DRAFT_ID, PAGE_IDS[0], moveUp = true).map(InvoiceImage::imageId),
        )
        assertEquals(
            listOf(PAGE_IDS[0], PAGE_IDS[2], PAGE_IDS[1]),
            reorder(DRAFT_ID, PAGE_IDS[1], moveUp = false).map(InvoiceImage::imageId),
        )
    }

    @Test
    fun `two concurrent one-step moves compose instead of overwriting a stale order`() = runTest {
        seedDraftWithPages(3)

        listOf(
            async { reorder(DRAFT_ID, PAGE_IDS[2], moveUp = true) },
            async { reorder(DRAFT_ID, PAGE_IDS[2], moveUp = true) },
        ).awaitAll()

        val final = drafts.observeImages(DRAFT_ID).first()
        assertEquals(listOf(PAGE_IDS[2], PAGE_IDS[0], PAGE_IDS[1]), final.map { it.imageId })
        assertEquals(listOf(0, 1, 2), final.map(InvoiceImage::pageIndex))
    }

    @Test
    fun `reordering a page from another draft fails controlled`() = runTest {
        seedDraftWithPages(2)
        try {
            reorder(DRAFT_ID, PAGE_IDS[2], moveUp = true)
            fail("se esperaba StorageException")
        } catch (expected: StorageException) {
            assertEquals(StorageError.Unavailable, expected.error)
        }
    }

    @Test
    fun `deleting the middle page reindexes the rest and removes only its file`() = runTest {
        seedDraftWithPages(3)

        val firstDeletion = deleteImage(PAGE_IDS[1])
        assertTrue(firstDeletion.removed)
        assertFalse(firstDeletion.draftIsEmpty)

        val images = drafts.observeImages(DRAFT_ID).first()
        assertEquals(listOf(PAGE_IDS[0], PAGE_IDS[2]), images.map(InvoiceImage::imageId))
        assertEquals(listOf(0, 1), images.map(InvoiceImage::pageIndex))
        assertEquals(
            listOf(listOf("draft_images/${DRAFT_ID.value}/p1.jpg")),
            fileStore.deletions,
        )
        // Los artefactos OCR se eliminan en el mantenimiento coordinado. Borrarlos aquí
        // podría competir con un reconocimiento que ya publicó su lease activo.
        assertTrue(fileStore.ocrVersionDeletions.isEmpty())
        assertEquals(DraftStatus.CAPTURED, drafts.findDraft(DRAFT_ID)?.status)
    }

    @Test
    fun `deleting the last page returns the draft to created`() = runTest {
        seedDraftWithPages(1)

        val lastDeletion = deleteImage(PAGE_IDS[0])
        assertTrue(lastDeletion.removed)
        assertTrue(lastDeletion.draftIsEmpty)

        assertTrue(drafts.observeImages(DRAFT_ID).first().isEmpty())
        assertEquals(DraftStatus.CREATED, drafts.findDraft(DRAFT_ID)?.status)
    }

    @Test
    fun `deleting a page preserves a file still referenced by a legacy alias`() = runTest {
        seedDraftWithPages(1)
        val sharedPath = "draft_images/${DRAFT_ID.value}/p0.jpg"
        drafts.createDraft(
            InvoiceDraft(
                draftId = OTHER_DRAFT_ID,
                businessId = BUSINESS_ID,
                status = DraftStatus.CAPTURED,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            ),
        )
        drafts.seedImage(
            drafts.findImage(PAGE_IDS[0])!!.copy(
                imageId = PAGE_IDS[1],
                draftId = OTHER_DRAFT_ID,
                pageIndex = 0,
            ),
        )

        val deletion = deleteImage(PAGE_IDS[0])

        assertTrue(deletion.removed)
        assertEquals(sharedPath, drafts.findImage(PAGE_IDS[1])?.filePath)
        assertTrue(fileStore.deletions.isEmpty())
    }

    @Test
    fun `deleting a missing page returns false and touches nothing`() = runTest {
        seedDraftWithPages(1)

        val missingDeletion = deleteImage(PAGE_IDS[1])
        assertFalse(missingDeletion.removed)
        assertFalse(missingDeletion.draftIsEmpty)

        assertTrue(fileStore.deletions.isEmpty())
        assertTrue(fileStore.ocrVersionDeletions.isEmpty())
        assertEquals(1, drafts.observeImages(DRAFT_ID).first().size)
    }

    @Test
    fun `preprocessing publishes every OCR page in order without touching originals`() = runTest {
        seedDraftWithPages(3)

        val prepared = preprocess(DRAFT_ID)

        assertTrue(imagePreprocessor.clears.isEmpty())
        assertEquals(
            listOf(PAGE_IDS[0], PAGE_IDS[1], PAGE_IDS[2]),
            imagePreprocessor.preprocessCalls.map(FakeInvoiceImagePreprocessor.PreprocessCall::imageId),
        )
        assertEquals(
            PAGE_IDS.map {
                "draft_images/${DRAFT_ID.value}/ocr/runs/fake/${it.value}.jpg"
            },
            prepared.map { it.relativePath },
        )
        assertEquals(prepared, imagePreprocessor.findPrepared(DRAFT_ID))
    }

    @Test
    fun `cancelling preprocessing stops the batch and removes partial OCR versions`() = runTest {
        seedDraftWithPages(3)
        val previousPublished = OcrImageFile(
            sourceImageId = PAGE_IDS[2],
            relativePath = "draft_images/${DRAFT_ID.value}/ocr/runs/previous/page.jpg",
            mimeType = "image/jpeg",
            widthPx = 1_200,
            heightPx = 1_600,
            fileSizeBytes = 1_000L,
        )
        imagePreprocessor.seedPrepared(DRAFT_ID, listOf(previousPublished))
        imagePreprocessor.beforePreprocess = { image ->
            if (image.imageId == PAGE_IDS[1]) awaitCancellation()
        }

        val job = launch { preprocess(DRAFT_ID) }
        runCurrent()
        job.cancelAndJoin()

        assertEquals(
            listOf(PAGE_IDS[0]),
            imagePreprocessor.preprocessCalls.map(FakeInvoiceImagePreprocessor.PreprocessCall::imageId),
        )
        assertEquals(
            listOf(previousPublished),
            imagePreprocessor.findPrepared(DRAFT_ID),
        )
        assertTrue(imagePreprocessor.clears.isEmpty())
    }

    private suspend fun seedDraftWithPages(pageCount: Int) {
        drafts.createDraft(
            InvoiceDraft(
                draftId = DRAFT_ID,
                businessId = BUSINESS_ID,
                status = DraftStatus.CAPTURED,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            ),
        )
        repeat(pageCount) { index ->
            drafts.seedImage(
                InvoiceImage(
                    imageId = PAGE_IDS[index],
                    draftId = DRAFT_ID,
                    businessId = BUSINESS_ID,
                    pageIndex = index,
                    filePath = "draft_images/${DRAFT_ID.value}/p$index.jpg",
                    sha256 = "%064x".format(index + 1),
                    mimeType = "image/jpeg",
                    widthPx = 64,
                    heightPx = 48,
                    fileSizeBytes = 1_000L,
                    rotationDegrees = 0,
                    crop = ImageCrop(1_000, 2_000, 8_000, 8_500),
                    createdAt = Instant.EPOCH,
                ),
            )
        }
    }

    private companion object {
        val BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("00000000-0000-0000-0000-0000000000b1"),
        )
        val DRAFT_ID: DraftId = DraftId.from(
            UUID.fromString("00000000-0000-4000-8000-0000000000d1"),
        )
        val OTHER_DRAFT_ID: DraftId = DraftId.from(
            UUID.fromString("00000000-0000-4000-8000-0000000000d2"),
        )
        val PAGE_IDS: List<ImageId> = listOf(
            ImageId.from(UUID.fromString("00000000-0000-4000-8000-0000000000a1")),
            ImageId.from(UUID.fromString("00000000-0000-4000-8000-0000000000a2")),
            ImageId.from(UUID.fromString("00000000-0000-4000-8000-0000000000a3")),
        )
    }
}
