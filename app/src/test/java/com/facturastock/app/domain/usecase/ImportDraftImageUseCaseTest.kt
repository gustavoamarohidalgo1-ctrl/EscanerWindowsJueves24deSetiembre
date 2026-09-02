package com.facturastock.app.domain.usecase

import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.config.AppConfiguration
import com.facturastock.app.domain.error.FileError
import com.facturastock.app.domain.error.FileException
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.InvoiceImage
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.repository.CapturedPageWrite
import com.facturastock.app.domain.repository.CapturedPageIntent
import com.facturastock.app.domain.repository.DraftImageImporter
import com.facturastock.app.domain.repository.ImportedImageFile
import com.facturastock.app.domain.repository.InvoiceDraftRepository
import com.facturastock.app.domain.repository.PublishedCapturedPage
import com.facturastock.app.testing.FakeAppConfigurationRepository
import com.facturastock.app.testing.FakeDraftFileStore
import com.facturastock.app.testing.FakeDraftImageImporter
import com.facturastock.app.testing.FakeInvoiceDraftRepository
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ImportDraftImageUseCaseTest {
    private var now: Instant = Instant.parse("2026-08-01T12:00:00Z")
    private val clock = AppClock { now }
    private val appConfig = FakeAppConfigurationRepository()
    private val drafts = FakeInvoiceDraftRepository(clock)
    private val importer = FakeDraftImageImporter()
    private val fileStore = FakeDraftFileStore()
    private val useCase = ImportDraftImageUseCase(
        appConfigurationRepository = appConfig,
        invoiceDraftRepository = drafts,
        draftImageImporter = importer,
        draftFileStore = fileStore,
        uuidGenerator = UuidGenerator { GENERATED_UUID },
    )

    @Test
    fun `importing into a materialized draft persists page zero with the imported metadata`() =
        runTest {
            activateBusiness()
            val importedPath = "draft_images/${DRAFT_ID.value}/${GENERATED_UUID}.jpg"
            importer.nextResult = imported(path = importedPath)

            val image = useCase(DRAFT_ID, SOURCE_URI)

            // El importador recibió el draftId de ruta, el imageId generado y la URI elegida.
            assertEquals(
                listOf(
                    FakeDraftImageImporter.ImportCall(
                        draftId = DRAFT_ID,
                        imageId = image.imageId,
                        sourceUri = SOURCE_URI,
                    ),
                ),
                importer.calls,
            )
            assertEquals(GENERATED_UUID.toString(), image.imageId.value)
            assertEquals(DRAFT_ID, image.draftId)
            assertEquals(BUSINESS_ID, image.businessId)
            assertEquals(0, image.pageIndex)
            assertEquals(importedPath, image.filePath)
            assertEquals("c".repeat(64), image.sha256)
            assertEquals("image/jpeg", image.mimeType)
            assertEquals(2_048, image.widthPx)
            assertEquals(1_536, image.heightPx)
            assertEquals(512_000L, image.fileSizeBytes)
            assertEquals(0, image.rotationDegrees)
            assertNull(image.crop)

            // El borrador preexistente avanzó de CREATED a CAPTURED.
            val draft = drafts.findDraft(DRAFT_ID)
            assertEquals(DraftStatus.CAPTURED, draft?.status)
            assertEquals(BUSINESS_ID, draft?.businessId)
            assertEquals(
                listOf(image.imageId),
                drafts.observeImages(DRAFT_ID).first().map { it.imageId },
            )
        }

    @Test
    fun `reimporting without a replace target appends a new page keeping both`() = runTest {
        activateBusiness()
        val first = useCase(DRAFT_ID, SOURCE_URI)
        val secondPath = "draft_images/${DRAFT_ID.value}/${SECOND_UUID}.jpg"
        importer.nextResult = imported(path = secondPath)

        // Un UUID distinto por importación, como haría el generador real.
        val regeneratedUseCase = ImportDraftImageUseCase(
            appConfigurationRepository = appConfig,
            invoiceDraftRepository = drafts,
            draftImageImporter = importer,
            draftFileStore = fileStore,
            uuidGenerator = UuidGenerator { SECOND_UUID },
        )
        val second = regeneratedUseCase(DRAFT_ID, SOURCE_URI)

        assertEquals(GENERATED_UUID.toString(), first.imageId.value)
        assertEquals(SECOND_UUID.toString(), second.imageId.value)
        val images = drafts.observeImages(DRAFT_ID).first()
        assertEquals(2, images.size)
        assertEquals(first.imageId, images[0].imageId)
        assertEquals(0, images[0].pageIndex)
        assertEquals(second.imageId, images[1].imageId)
        assertEquals(1, images[1].pageIndex)
        assertEquals(secondPath, images[1].filePath)
        assertEquals(DraftStatus.CAPTURED, drafts.findDraft(DRAFT_ID)?.status)
        // Añadir una página no borra ningún archivo.
        assertTrue(fileStore.deletions.isEmpty())
    }

    @Test
    fun `a preferred image already committed returns idempotently without invoking importer`() =
        runTest {
            activateBusiness(materializeDraft = false)
            drafts.createDraft(draft(status = DraftStatus.CREATED))
            val preferredImageId = ImageId.from(SECOND_UUID)
            val committed = drafts.publishCapturedPage(
                page = CapturedPageWrite(
                    imageId = preferredImageId,
                    draftId = DRAFT_ID,
                    businessId = BUSINESS_ID,
                    filePath = "draft_images/${DRAFT_ID.value}/${preferredImageId.value}.jpg",
                    sha256 = "d".repeat(64),
                    mimeType = "image/jpeg",
                    widthPx = 1_200,
                    heightPx = 1_600,
                    fileSizeBytes = 320_000L,
                    rotationDegrees = 90,
                ),
                intent = CapturedPageIntent.Append,
            ).image
            // Si el retorno temprano no existe, esta excepción hace fallar la prueba.
            importer.nextException = FileException(FileError.Corrupt)

            val restoredFromGallery = useCase(
                draftId = DRAFT_ID,
                sourceUri = SOURCE_URI,
                preferredImageId = preferredImageId,
            )
            val restoredFromCamera = useCase(
                draftId = DRAFT_ID,
                jpegBytes = JPEG_BYTES,
                rotationDegrees = 180,
                preferredImageId = preferredImageId,
            )

            assertEquals(committed, restoredFromGallery)
            assertEquals(committed, restoredFromCamera)
            assertTrue(importer.calls.isEmpty())
            assertTrue(importer.bytesCalls.isEmpty())
            assertEquals(listOf(committed), drafts.observeImages(DRAFT_ID).first())
            assertTrue(fileStore.deletions.isEmpty())
        }

    @Test
    fun `a preferred image replay with a different intent is rejected before file IO`() = runTest {
        activateBusiness(materializeDraft = false)
        drafts.createDraft(draft(status = DraftStatus.CREATED))
        val preferredImageId = ImageId.from(SECOND_UUID)
        drafts.publishCapturedPage(
            page = CapturedPageWrite(
                imageId = preferredImageId,
                draftId = DRAFT_ID,
                businessId = BUSINESS_ID,
                filePath = "draft_images/${DRAFT_ID.value}/${preferredImageId.value}.jpg",
                sha256 = "d".repeat(64),
                mimeType = "image/jpeg",
                widthPx = 1_200,
                heightPx = 1_600,
                fileSizeBytes = 320_000L,
            ),
            intent = CapturedPageIntent.Append,
        )

        val failure = runCatching {
            useCase(
                draftId = DRAFT_ID,
                sourceUri = SOURCE_URI,
                replaceImageId = IMAGE_ID_ABSENT,
                preferredImageId = preferredImageId,
            )
        }.exceptionOrNull()

        assertTrue(failure is StorageException)
        assertTrue((failure as StorageException).error is StorageError.ConstraintConflict)
        assertTrue(importer.calls.isEmpty())
        assertTrue(fileStore.deletions.isEmpty())
        assertEquals(listOf(preferredImageId), drafts.observeImages(DRAFT_ID).first().map { it.imageId })
    }

    @Test
    fun `an exact replace replay succeeds after its target was atomically removed`() = runTest {
        activateBusiness(materializeDraft = false)
        drafts.createDraft(draft(status = DraftStatus.CAPTURED))
        val targetImageId = ImageId.from(THIRD_UUID)
        val targetPath = "draft_images/${DRAFT_ID.value}/${targetImageId.value}.jpg"
        drafts.seedImage(
            page(
                targetImageId,
                0,
                targetPath,
            ),
        )
        val replacementImageId = ImageId.from(SECOND_UUID)
        val replacementRequest = CapturedPageWrite(
            imageId = replacementImageId,
            draftId = DRAFT_ID,
            businessId = BUSINESS_ID,
            filePath = "draft_images/${DRAFT_ID.value}/${replacementImageId.value}.jpg",
            sha256 = "d".repeat(64),
            mimeType = "image/jpeg",
            widthPx = 1_200,
            heightPx = 1_600,
            fileSizeBytes = 320_000L,
        )
        val committed = drafts.publishCapturedPage(
            replacementRequest,
            CapturedPageIntent.Replace(targetImageId),
        ).image
        importer.nextException = FileException(FileError.Corrupt)

        val replay = useCase(
            draftId = DRAFT_ID,
            sourceUri = SOURCE_URI,
            replaceImageId = targetImageId,
            preferredImageId = replacementImageId,
        )

        assertEquals(committed, replay)
        assertTrue(importer.calls.isEmpty())
        assertNull(drafts.findImage(targetImageId))
        assertEquals(replacementImageId, drafts.observeImages(DRAFT_ID).first().single().imageId)
        assertEquals(listOf(listOf(targetPath)), fileStore.deletions)
    }

    @Test
    fun `concurrent imports with the same preferred id invoke importer once and share commit`() =
        runTest {
            activateBusiness()
            val blockingImporter = BlockingDraftImageImporter()
            val concurrentUseCase = useCaseWith(blockingImporter)
            val preferredImageId = ImageId.from(SECOND_UUID)

            val first = async {
                concurrentUseCase(DRAFT_ID, SOURCE_URI, preferredImageId = preferredImageId)
            }
            runCurrent()
            blockingImporter.firstCallStarted.await()
            val second = async {
                concurrentUseCase(DRAFT_ID, SOURCE_URI, preferredImageId = preferredImageId)
            }
            runCurrent()

            // La segunda llamada no atraviesa el preflight mientras la primera está en IO.
            assertEquals(1, blockingImporter.callCount)
            blockingImporter.releaseFirstCall.complete(Unit)

            val firstResult = first.await()
            val secondResult = second.await()
            assertEquals(firstResult, secondResult)
            assertEquals(1, blockingImporter.callCount)
            assertEquals(
                listOf(preferredImageId),
                drafts.observeImages(DRAFT_ID).first().map(InvoiceImage::imageId),
            )
            assertTrue(fileStore.deletions.isEmpty())
        }

    @Test
    fun `concurrent imports with different ids append distinct ordered pages`() = runTest {
        activateBusiness()
        val blockingImporter = BlockingDraftImageImporter()
        val concurrentUseCase = useCaseWith(blockingImporter)
        val firstImageId = ImageId.from(GENERATED_UUID)
        val secondImageId = ImageId.from(SECOND_UUID)

        val first = async {
            concurrentUseCase(DRAFT_ID, SOURCE_URI, preferredImageId = firstImageId)
        }
        runCurrent()
        blockingImporter.firstCallStarted.await()
        val second = async {
            concurrentUseCase(DRAFT_ID, SOURCE_URI, preferredImageId = secondImageId)
        }
        runCurrent()

        assertEquals(1, blockingImporter.callCount)
        blockingImporter.releaseFirstCall.complete(Unit)
        first.await()
        second.await()

        val pages = drafts.observeImages(DRAFT_ID).first()
        assertEquals(listOf(firstImageId, secondImageId), pages.map(InvoiceImage::imageId))
        assertEquals(listOf(0, 1), pages.map(InvoiceImage::pageIndex))
        assertEquals(2, blockingImporter.callCount)
        assertTrue(fileStore.deletions.isEmpty())
    }

    @Test
    fun `a draft from another active business fails before invoking importer`() = runTest {
        activateBusiness(materializeDraft = false)
        drafts.createDraft(
            draft(status = DraftStatus.CREATED).copy(businessId = OTHER_BUSINESS_ID),
        )

        try {
            useCase(DRAFT_ID, SOURCE_URI, preferredImageId = ImageId.from(SECOND_UUID))
            fail("se esperaba StorageException")
        } catch (expected: StorageException) {
            assertTrue(expected.error is StorageError.ConstraintConflict)
        }

        assertTrue(importer.calls.isEmpty())
        assertTrue(importer.bytesCalls.isEmpty())
        assertTrue(drafts.observeImages(DRAFT_ID).first().isEmpty())
        assertTrue(fileStore.deletions.isEmpty())
    }

    @Test
    fun `reimporting with a replace target keeps the page index and deletes only its file`() =
        runTest {
            activateBusiness()
            val firstPath = "draft_images/${DRAFT_ID.value}/${GENERATED_UUID}.jpg"
            importer.nextResult = imported(path = firstPath)
            val first = useCase(DRAFT_ID, SOURCE_URI)

            // La primera página no reemplaza nada: ningún borrado.
            assertTrue(fileStore.deletions.isEmpty())
            assertTrue(fileStore.ocrVersionDeletions.isEmpty())

            val secondPath = "draft_images/${DRAFT_ID.value}/${SECOND_UUID}.jpg"
            importer.nextResult = imported(path = secondPath)
            val second = useCase(
                draftId = DRAFT_ID,
                sourceUri = SOURCE_URI,
                replaceImageId = first.imageId,
                // ID reservado por el ViewModel antes de abrir el picker: identifica el
                // commit tras una eventual muerte de proceso, también al reemplazar.
                preferredImageId = ImageId.from(SECOND_UUID),
            )

            // La página reemplazada conserva su índice (con un nuevo imageId) y solo su
            // archivo se borra, una vez persistida la nueva.
            assertEquals(SECOND_UUID.toString(), second.imageId.value)
            assertEquals(0, second.pageIndex)
            assertEquals(
                listOf(listOf(firstPath)),
                fileStore.deletions,
            )
            assertTrue(fileStore.ocrVersionDeletions.isEmpty())
            assertEquals(
                secondPath,
                drafts.observeImages(DRAFT_ID).first().single().filePath,
            )
        }

    @Test
    fun `replace intent follows its image id across a concurrent reorder`() = runTest {
        activateBusiness(materializeDraft = false)
        drafts.createDraft(draft(status = DraftStatus.CAPTURED))
        val firstId = ImageId.from(SECOND_UUID)
        val secondId = IMAGE_ID_ABSENT
        val replacementId = ImageId.from(THIRD_UUID)
        val firstPath = "draft_images/${DRAFT_ID.value}/${firstId.value}.jpg"
        val secondPath = "draft_images/${DRAFT_ID.value}/${secondId.value}.jpg"
        drafts.seedImage(page(firstId, 0, firstPath))
        drafts.seedImage(page(secondId, 1, secondPath))
        importer.nextResult = imported(
            path = "draft_images/${DRAFT_ID.value}/${replacementId.value}.jpg",
        )
        val publicationReached = CompletableDeferred<Unit>()
        val releasePublication = CompletableDeferred<Unit>()
        val intercepted = object : InvoiceDraftRepository by drafts {
            override suspend fun publishCapturedPage(
                page: CapturedPageWrite,
                intent: CapturedPageIntent,
            ): PublishedCapturedPage {
                publicationReached.complete(Unit)
                releasePublication.await()
                return drafts.publishCapturedPage(page, intent)
            }
        }

        val operation = async {
            runCatching {
                useCaseWith(importer, intercepted)(
                    draftId = DRAFT_ID,
                    sourceUri = SOURCE_URI,
                    replaceImageId = firstId,
                    preferredImageId = replacementId,
                )
            }
        }
        publicationReached.await()
        drafts.moveImageOneStep(DRAFT_ID, firstId, moveUp = false)
        releasePublication.complete(Unit)

        val replacement = operation.await().getOrThrow()
        assertEquals(1, replacement.pageIndex)
        assertEquals(
            listOf(secondId, replacementId),
            drafts.observeImages(DRAFT_ID).first().map(InvoiceImage::imageId),
        )
        assertEquals(listOf(listOf(firstPath)), fileStore.deletions)
        assertTrue(fileStore.deletions.flatten().none { it == secondPath })
    }

    @Test
    fun `replace target deleted concurrently compensates only the new file`() = runTest {
        activateBusiness(materializeDraft = false)
        drafts.createDraft(draft(status = DraftStatus.CAPTURED))
        val firstId = ImageId.from(SECOND_UUID)
        val secondId = IMAGE_ID_ABSENT
        val replacementId = ImageId.from(THIRD_UUID)
        val firstPath = "draft_images/${DRAFT_ID.value}/${firstId.value}.jpg"
        val secondPath = "draft_images/${DRAFT_ID.value}/${secondId.value}.jpg"
        val replacementPath = "draft_images/${DRAFT_ID.value}/${replacementId.value}.jpg"
        drafts.seedImage(page(firstId, 0, firstPath))
        drafts.seedImage(page(secondId, 1, secondPath))
        importer.nextResult = imported(path = replacementPath)
        val publicationReached = CompletableDeferred<Unit>()
        val releasePublication = CompletableDeferred<Unit>()
        val intercepted = object : InvoiceDraftRepository by drafts {
            override suspend fun publishCapturedPage(
                page: CapturedPageWrite,
                intent: CapturedPageIntent,
            ): PublishedCapturedPage {
                publicationReached.complete(Unit)
                releasePublication.await()
                return drafts.publishCapturedPage(page, intent)
            }
        }

        val operation = async {
            runCatching {
                useCaseWith(importer, intercepted)(
                    draftId = DRAFT_ID,
                    sourceUri = SOURCE_URI,
                    replaceImageId = firstId,
                    preferredImageId = replacementId,
                )
            }
        }
        publicationReached.await()
        requireNotNull(drafts.deleteImage(firstId))
        releasePublication.complete(Unit)

        val failure = operation.await().exceptionOrNull()
        assertTrue(failure is StorageException)
        assertEquals(StorageError.Unavailable, (failure as StorageException).error)
        val remaining = drafts.observeImages(DRAFT_ID).first()
        assertEquals(listOf(secondId), remaining.map(InvoiceImage::imageId))
        assertEquals(listOf(0), remaining.map(InvoiceImage::pageIndex))
        assertEquals(listOf(listOf(replacementPath)), fileStore.deletions)
        assertTrue(fileStore.deletions.flatten().none { it == secondPath })
    }

    @Test
    fun `a replace target that does not exist fails controlled and touches nothing`() = runTest {
        activateBusiness()
        useCase(DRAFT_ID, SOURCE_URI)
        val pagesBefore = drafts.observeImages(DRAFT_ID).first().size

        try {
            useCase(
                DRAFT_ID,
                SOURCE_URI,
                replaceImageId = IMAGE_ID_ABSENT,
                preferredImageId = ImageId.from(SECOND_UUID),
            )
            fail("se esperaba StorageException")
        } catch (expected: StorageException) {
            assertEquals(StorageError.Unavailable, expected.error)
        }

        assertEquals(pagesBefore, drafts.observeImages(DRAFT_ID).first().size)
    }

    @Test
    fun `a storage failure after the copy deletes the new file and propagates`() = runTest {
        activateBusiness(materializeDraft = false)
        drafts.createDraft(draft(status = DraftStatus.CAPTURED))
        val importedPath = "draft_images/${DRAFT_ID.value}/${GENERATED_UUID}.jpg"
        importer.nextResult = imported(path = importedPath)
        val failingRepository = object : InvoiceDraftRepository by drafts {
            override suspend fun publishCapturedPage(
                page: CapturedPageWrite,
                intent: CapturedPageIntent,
            ): PublishedCapturedPage {
                throw StorageException(StorageError.Unavailable)
            }
        }
        val failingUseCase = useCaseWith(importer, failingRepository)

        try {
            failingUseCase(DRAFT_ID, SOURCE_URI)
            fail("se esperaba StorageException")
        } catch (expected: StorageException) {
            assertEquals(StorageError.Unavailable, expected.error)
        }

        // El archivo nuevo ya estaba en disco: se pidió borrar para no dejar huérfanos.
        assertEquals(
            listOf(listOf(importedPath)),
            fileStore.deletions,
        )
        assertTrue(drafts.observeImages(DRAFT_ID).first().isEmpty())
    }

    @Test
    fun `compensation preserves a path referenced by a different legacy row`() = runTest {
        activateBusiness(materializeDraft = false)
        drafts.createDraft(draft(status = DraftStatus.CAPTURED))
        val importedPath = "draft_images/${DRAFT_ID.value}/${GENERATED_UUID}.jpg"
        val legacyAliasId = ImageId.from(SECOND_UUID)
        drafts.seedImage(page(legacyAliasId, 0, importedPath))
        importer.nextResult = imported(path = importedPath)
        val failingRepository = object : InvoiceDraftRepository by drafts {
            override suspend fun publishCapturedPage(
                page: CapturedPageWrite,
                intent: CapturedPageIntent,
            ): PublishedCapturedPage = throw StorageException(StorageError.Unavailable)
        }

        try {
            useCaseWith(importer, failingRepository)(DRAFT_ID, SOURCE_URI)
            fail("se esperaba StorageException")
        } catch (_: StorageException) {
            // fallo sintético posterior a la copia
        }

        assertTrue(fileStore.deletions.isEmpty())
        assertEquals(importedPath, drafts.findImage(legacyAliasId)?.filePath)
    }

    @Test
    fun `replace commit and replay preserve an old path still referenced by a legacy alias`() =
        runTest {
            activateBusiness(materializeDraft = false)
            drafts.createDraft(draft(status = DraftStatus.CAPTURED))
            val targetId = ImageId.from(SECOND_UUID)
            val aliasId = IMAGE_ID_ABSENT
            val replacementId = ImageId.from(THIRD_UUID)
            val sharedOldPath = "draft_images/${DRAFT_ID.value}/${targetId.value}.jpg"
            drafts.seedImage(page(targetId, 0, sharedOldPath))
            drafts.seedImage(page(aliasId, 1, sharedOldPath))
            importer.nextResult = imported(
                "draft_images/${DRAFT_ID.value}/${replacementId.value}.jpg",
            )

            val committed = useCase(
                draftId = DRAFT_ID,
                sourceUri = SOURCE_URI,
                replaceImageId = targetId,
                preferredImageId = replacementId,
            )
            val replay = useCase(
                draftId = DRAFT_ID,
                sourceUri = SOURCE_URI,
                replaceImageId = targetId,
                preferredImageId = replacementId,
            )

            assertEquals(committed, replay)
            assertEquals(1, importer.calls.size)
            assertEquals(sharedOldPath, drafts.findImage(aliasId)?.filePath)
            assertTrue(fileStore.deletions.isEmpty())
        }

    @Test
    fun `publication rejects invalid paths and cross business pages before persistence`() =
        runTest {
            activateBusiness(materializeDraft = false)
            drafts.createDraft(draft(status = DraftStatus.CREATED))
            val invalidPages = listOf(
                CapturedPageWrite(
                    imageId = ImageId.from(SECOND_UUID),
                    draftId = DRAFT_ID,
                    businessId = BUSINESS_ID,
                    filePath = "draft_images/${DRAFT_ID.value}/wrong.jpg",
                    sha256 = "a".repeat(64),
                    mimeType = "image/jpeg",
                    widthPx = 100,
                    heightPx = 100,
                    fileSizeBytes = 100,
                ),
                CapturedPageWrite(
                    imageId = ImageId.from(THIRD_UUID),
                    draftId = DRAFT_ID,
                    businessId = BUSINESS_ID,
                    filePath = "draft_images/${DRAFT_ID.value}/${THIRD_UUID}.png",
                    sha256 = "b".repeat(64),
                    mimeType = "image/jpeg",
                    widthPx = 100,
                    heightPx = 100,
                    fileSizeBytes = 100,
                ),
                CapturedPageWrite(
                    imageId = IMAGE_ID_ABSENT,
                    draftId = DRAFT_ID,
                    businessId = OTHER_BUSINESS_ID,
                    filePath =
                        "draft_images/${DRAFT_ID.value}/${IMAGE_ID_ABSENT.value}.jpg",
                    sha256 = "e".repeat(64),
                    mimeType = "image/jpeg",
                    widthPx = 100,
                    heightPx = 100,
                    fileSizeBytes = 100,
                ),
            )

            invalidPages.forEach { invalid ->
                val failure = runCatching {
                    drafts.publishCapturedPage(invalid, CapturedPageIntent.Append)
                }.exceptionOrNull()
                assertTrue(failure is StorageException)
                assertTrue((failure as StorageException).error is StorageError.ConstraintConflict)
                assertNull(drafts.findImage(invalid.imageId))
            }
            assertTrue(drafts.observeImages(DRAFT_ID).first().isEmpty())
        }

    @Test
    fun `an atomic page publication failure deletes the unreferenced file and keeps CREATED`() =
        runTest {
            activateBusiness(materializeDraft = false)
            drafts.createDraft(draft(status = DraftStatus.CREATED))
            val importedPath = "draft_images/${DRAFT_ID.value}/${GENERATED_UUID}.jpg"
            importer.nextResult = imported(path = importedPath)
            val failingPublication = object : InvoiceDraftRepository by drafts {
                override suspend fun publishCapturedPage(
                    page: CapturedPageWrite,
                    intent: CapturedPageIntent,
                ): PublishedCapturedPage =
                    throw StorageException(StorageError.Unavailable)
            }
            val useCaseWithFailingPublication = ImportDraftImageUseCase(
                appConfigurationRepository = appConfig,
                invoiceDraftRepository = failingPublication,
                draftImageImporter = importer,
                draftFileStore = fileStore,
                uuidGenerator = UuidGenerator { GENERATED_UUID },
            )

            try {
                useCaseWithFailingPublication(DRAFT_ID, SOURCE_URI)
                fail("se esperaba StorageException")
            } catch (expected: StorageException) {
                assertEquals(StorageError.Unavailable, expected.error)
            }

            // La frontera atómica no publicó nada: el final nuevo se limpia, la imagen no
            // aparece y el borrador recuperable conserva CREATED.
            assertEquals(
                listOf(listOf(importedPath)),
                fileStore.deletions,
            )
            assertTrue(drafts.observeImages(DRAFT_ID).first().isEmpty())
            assertEquals(DraftStatus.CREATED, drafts.findDraft(DRAFT_ID)?.status)
        }

    @Test
    fun `a publication that commits then throws preserves its referenced file`() = runTest {
        activateBusiness(materializeDraft = false)
        drafts.createDraft(draft(status = DraftStatus.CREATED))
        val preferredImageId = ImageId.from(SECOND_UUID)
        val importedPath = "draft_images/${DRAFT_ID.value}/${preferredImageId.value}.jpg"
        importer.nextResult = imported(path = importedPath)
        val commitThenThrow = object : InvoiceDraftRepository by drafts {
            override suspend fun publishCapturedPage(
                page: CapturedPageWrite,
                intent: CapturedPageIntent,
            ): PublishedCapturedPage {
                drafts.publishCapturedPage(page, intent)
                throw StorageException(StorageError.Unavailable)
            }
        }
        val failingUseCase = useCaseWith(importer, commitThenThrow)

        try {
            failingUseCase(DRAFT_ID, SOURCE_URI, preferredImageId = preferredImageId)
            fail("se esperaba StorageException")
        } catch (_: StorageException) {
            // fallo sintético posterior al commit
        }

        assertEquals(importedPath, drafts.findImage(preferredImageId)?.filePath)
        assertTrue(fileStore.deletions.isEmpty())
    }

    @Test
    fun `cancellation before publication cleans the confirmed unreferenced file`() = runTest {
        activateBusiness(materializeDraft = false)
        drafts.createDraft(draft(status = DraftStatus.CREATED))
        val preferredImageId = ImageId.from(SECOND_UUID)
        val importedPath = "draft_images/${DRAFT_ID.value}/${preferredImageId.value}.jpg"
        importer.nextResult = imported(path = importedPath)
        val publicationStarted = CompletableDeferred<Unit>()
        val cancelBeforeCommit = object : InvoiceDraftRepository by drafts {
            override suspend fun publishCapturedPage(
                page: CapturedPageWrite,
                intent: CapturedPageIntent,
            ): PublishedCapturedPage {
                publicationStarted.complete(Unit)
                awaitCancellation()
            }
        }
        val cancellingUseCase = useCaseWith(importer, cancelBeforeCommit)

        val operation = launch {
            cancellingUseCase(DRAFT_ID, SOURCE_URI, preferredImageId = preferredImageId)
        }
        runCurrent()
        publicationStarted.await()
        operation.cancelAndJoin()

        assertNull(drafts.findImage(preferredImageId))
        assertEquals(listOf(listOf(importedPath)), fileStore.deletions)
    }

    @Test
    fun `cancellation after publication preserves the committed file`() = runTest {
        activateBusiness(materializeDraft = false)
        drafts.createDraft(draft(status = DraftStatus.CREATED))
        val preferredImageId = ImageId.from(SECOND_UUID)
        val importedPath = "draft_images/${DRAFT_ID.value}/${preferredImageId.value}.jpg"
        importer.nextResult = imported(path = importedPath)
        val commitFinished = CompletableDeferred<Unit>()
        val commitThenWait = object : InvoiceDraftRepository by drafts {
            override suspend fun publishCapturedPage(
                page: CapturedPageWrite,
                intent: CapturedPageIntent,
            ): PublishedCapturedPage {
                drafts.publishCapturedPage(page, intent)
                commitFinished.complete(Unit)
                awaitCancellation()
            }
        }
        val cancellingUseCase = useCaseWith(importer, commitThenWait)

        val operation = launch {
            cancellingUseCase(DRAFT_ID, SOURCE_URI, preferredImageId = preferredImageId)
        }
        runCurrent()
        commitFinished.await()
        operation.cancelAndJoin()

        assertEquals(importedPath, drafts.findImage(preferredImageId)?.filePath)
        assertTrue(fileStore.deletions.isEmpty())
    }

    @Test
    fun `cleanup preserves imported file when the reference query fails`() = runTest {
        activateBusiness(materializeDraft = false)
        drafts.createDraft(draft(status = DraftStatus.CREATED))
        val importedPath = "draft_images/${DRAFT_ID.value}/${GENERATED_UUID}.jpg"
        importer.nextResult = imported(path = importedPath)
        val uncertainRepository = object : InvoiceDraftRepository by drafts {
            override suspend fun publishCapturedPage(
                page: CapturedPageWrite,
                intent: CapturedPageIntent,
            ): PublishedCapturedPage {
                throw StorageException(StorageError.Unavailable)
            }

            override suspend fun isImagePathReferenced(filePath: String): Boolean {
                throw StorageException(StorageError.Unavailable)
            }
        }
        val failingUseCase = useCaseWith(importer, uncertainRepository)

        try {
            // Sin preferredImageId: la única consulta de referencia corresponde al cleanup.
            failingUseCase(DRAFT_ID, SOURCE_URI)
            fail("se esperaba StorageException")
        } catch (_: StorageException) {
            // fallo sintético esperado
        }

        assertTrue(fileStore.deletions.isEmpty())
    }

    @Test
    fun `importing from gallery persists the rotation rescued from the EXIF metadata`() = runTest {
        activateBusiness()
        importer.nextResult = imported(
            path = "draft_images/${DRAFT_ID.value}/${GENERATED_UUID}.jpg",
        )
            .copy(rotationDegrees = 270)

        val image = useCase(DRAFT_ID, SOURCE_URI)

        assertEquals(270, image.rotationDegrees)
        assertEquals(270, drafts.observeImages(DRAFT_ID).first().single().rotationDegrees)
    }

    @Test
    fun `adding a page to an advanced draft is rejected before file IO`() = runTest {
        activateBusiness(materializeDraft = false)
        drafts.createDraft(draft(status = DraftStatus.OCR_READY))

        try {
            useCase(DRAFT_ID, SOURCE_URI)
            fail("se esperaba StorageException")
        } catch (expected: StorageException) {
            assertTrue(expected.error is StorageError.ConstraintConflict)
        }

        assertEquals(DraftStatus.OCR_READY, drafts.findDraft(DRAFT_ID)?.status)
        assertTrue(drafts.observeImages(DRAFT_ID).first().isEmpty())
        assertTrue(importer.calls.isEmpty())
    }

    @Test
    fun `a file failure keeps a recoverable empty draft and persists no image`() = runTest {
        activateBusiness()
        importer.nextException = FileException(FileError.TooLarge)

        try {
            useCase(DRAFT_ID, SOURCE_URI)
            fail("se esperaba FileException")
        } catch (expected: FileException) {
            assertEquals(FileError.TooLarge, expected.error)
        }

        // La reserva Room evita que un sweep concurrente borre una importación en curso.
        assertEquals(DraftStatus.CREATED, drafts.findDraft(DRAFT_ID)?.status)
        assertTrue(drafts.observeImages(DRAFT_ID).first().isEmpty())
    }

    @Test
    fun `stale gallery and camera callbacks after discard fail before file IO and do not recreate`() =
        runTest {
            activateBusiness()
            assertTrue(drafts.deleteDraft(DRAFT_ID))

            val galleryFailure = runCatching {
                useCase(
                    draftId = DRAFT_ID,
                    sourceUri = SOURCE_URI,
                    preferredImageId = ImageId.from(SECOND_UUID),
                )
            }.exceptionOrNull()
            val cameraFailure = runCatching {
                useCase(
                    draftId = DRAFT_ID,
                    jpegBytes = JPEG_BYTES,
                    rotationDegrees = ROTATION_DEGREES,
                    preferredImageId = ImageId.from(THIRD_UUID),
                )
            }.exceptionOrNull()

            listOf(galleryFailure, cameraFailure).forEach { failure ->
                assertTrue(failure is StorageException)
                assertTrue(
                    (failure as StorageException).error is StorageError.ConstraintConflict,
                )
            }
            assertTrue(importer.calls.isEmpty())
            assertTrue(importer.bytesCalls.isEmpty())
            assertNull(drafts.findDraft(DRAFT_ID))
            assertTrue(drafts.observeImages(DRAFT_ID).first().isEmpty())
            assertTrue(fileStore.deletions.isEmpty())
        }

    @Test
    fun `without an active business it fails controlled and touches nothing`() = runTest {
        try {
            useCase(DRAFT_ID, SOURCE_URI)
            fail("se esperaba StorageException")
        } catch (expected: StorageException) {
            // fallo controlado esperado
        }

        assertTrue(importer.calls.isEmpty())
        assertNull(drafts.findDraft(DRAFT_ID))
        assertTrue(drafts.observeImages(DRAFT_ID).first().isEmpty())
    }

    @Test
    fun `importing camera bytes persists the rotation with the imported metadata`() = runTest {
        activateBusiness()
        importer.nextResult = imported(
            path = "draft_images/${DRAFT_ID.value}/${GENERATED_UUID}.jpg",
        )

        val image = useCase(DRAFT_ID, JPEG_BYTES, ROTATION_DEGREES)

        // El importador recibió el draftId, el imageId generado, los bytes y la rotación.
        val call = importer.bytesCalls.single()
        assertEquals(DRAFT_ID, call.draftId)
        assertEquals(image.imageId, call.imageId)
        assertTrue(JPEG_BYTES.contentEquals(call.jpegBytes))
        assertEquals(ROTATION_DEGREES, call.rotationDegrees)
        assertTrue(importer.calls.isEmpty())

        // La rotación de la captura queda registrada en los metadatos persistidos.
        assertEquals(GENERATED_UUID.toString(), image.imageId.value)
        assertEquals(ROTATION_DEGREES, image.rotationDegrees)
        assertEquals(0, image.pageIndex)
        assertEquals(DraftStatus.CAPTURED, drafts.findDraft(DRAFT_ID)?.status)
        assertEquals(
            listOf(image.imageId),
            drafts.observeImages(DRAFT_ID).first().map { it.imageId },
        )
    }

    @Test
    fun `recapturing with bytes and a replace target keeps the page and its rotation`() = runTest {
        activateBusiness()
        val first = useCase(DRAFT_ID, JPEG_BYTES, ROTATION_DEGREES)
        val recapturePath = "draft_images/${DRAFT_ID.value}/${SECOND_UUID}.jpg"
        importer.nextResult = imported(path = recapturePath)

        val second = useCase(
            DRAFT_ID,
            JPEG_BYTES,
            rotationDegrees = 180,
            replaceImageId = first.imageId,
            preferredImageId = ImageId.from(SECOND_UUID),
        )

        assertEquals(GENERATED_UUID.toString(), first.imageId.value)
        assertEquals(SECOND_UUID.toString(), second.imageId.value)
        val images = drafts.observeImages(DRAFT_ID).first()
        assertEquals(1, images.size)
        assertEquals(second.imageId, images.single().imageId)
        assertEquals(0, images.single().pageIndex)
        assertEquals(recapturePath, images.single().filePath)
        assertEquals(180, images.single().rotationDegrees)
        assertEquals(DraftStatus.CAPTURED, drafts.findDraft(DRAFT_ID)?.status)
        // El archivo de la captura reemplazada se pidió borrar tras persistir la nueva.
        val firstPath = "draft_images/${DRAFT_ID.value}/${GENERATED_UUID}.jpg"
        assertEquals(listOf(listOf(firstPath)), fileStore.deletions)
    }

    @Test
    fun `a file failure from bytes keeps a recoverable empty draft`() = runTest {
        activateBusiness()
        importer.nextException = FileException(FileError.UnsupportedFormat)

        try {
            useCase(DRAFT_ID, JPEG_BYTES, ROTATION_DEGREES)
            fail("se esperaba FileException")
        } catch (expected: FileException) {
            assertEquals(FileError.UnsupportedFormat, expected.error)
        }

        assertEquals(DraftStatus.CREATED, drafts.findDraft(DRAFT_ID)?.status)
        assertTrue(drafts.observeImages(DRAFT_ID).first().isEmpty())
    }

    @Test
    fun `product flow keeps the precreated draft when the first image runs out of space`() =
        runTest {
            activateBusiness(materializeDraft = false)
            StartInvoiceDraftUseCase(appConfig, drafts, clock)(DRAFT_ID)
            importer.nextException = FileException(FileError.InsufficientSpace)

            try {
                useCase(DRAFT_ID, JPEG_BYTES, ROTATION_DEGREES)
                fail("se esperaba FileException")
            } catch (expected: FileException) {
                assertEquals(FileError.InsufficientSpace, expected.error)
            }

            assertEquals(DraftStatus.CREATED, drafts.findDraft(DRAFT_ID)?.status)
            assertTrue(drafts.observeImages(DRAFT_ID).first().isEmpty())
            assertTrue(fileStore.deletions.isEmpty())
        }

    @Test
    fun `insufficient space during recapture keeps the previous page and draft`() = runTest {
        activateBusiness()
        importer.nextResult = imported(
            path = "draft_images/${DRAFT_ID.value}/${GENERATED_UUID}.jpg",
        )
        val stable = useCase(DRAFT_ID, JPEG_BYTES, ROTATION_DEGREES)
        importer.nextException = FileException(FileError.InsufficientSpace)

        try {
            useCase(
                draftId = DRAFT_ID,
                jpegBytes = JPEG_BYTES,
                rotationDegrees = 180,
                replaceImageId = stable.imageId,
            )
            fail("se esperaba FileException")
        } catch (expected: FileException) {
            assertEquals(FileError.InsufficientSpace, expected.error)
        }

        assertEquals(DraftStatus.CAPTURED, drafts.findDraft(DRAFT_ID)?.status)
        assertEquals(listOf(stable), drafts.observeImages(DRAFT_ID).first())
        assertTrue(fileStore.deletions.isEmpty())
    }

    @Test
    fun `the bytes overload also requires an active business`() = runTest {
        try {
            useCase(DRAFT_ID, JPEG_BYTES, ROTATION_DEGREES)
            fail("se esperaba StorageException")
        } catch (expected: StorageException) {
            // fallo controlado esperado
        }

        assertTrue(importer.bytesCalls.isEmpty())
        assertNull(drafts.findDraft(DRAFT_ID))
    }

    private suspend fun activateBusiness(materializeDraft: Boolean = true) {
        appConfig.completeOnboarding(
            BUSINESS_ID,
            AppConfiguration.DEFAULT_TAX_RATE,
            AppConfiguration.DEFAULT_COST_POLICY,
        )
        if (materializeDraft) {
            drafts.createDraft(draft(status = DraftStatus.CREATED))
        }
    }

    private fun draft(status: DraftStatus) = InvoiceDraft(
        draftId = DRAFT_ID,
        businessId = BUSINESS_ID,
        status = status,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private fun imported(path: String) = ImportedImageFile(
        relativePath = path,
        sha256 = "c".repeat(64),
        mimeType = when (path.substringAfterLast('.')) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> error("extensión de fixture no admitida")
        },
        widthPx = 2_048,
        heightPx = 1_536,
        fileSizeBytes = 512_000L,
    )

    private fun page(imageId: ImageId, pageIndex: Int, path: String) = InvoiceImage(
        imageId = imageId,
        draftId = DRAFT_ID,
        businessId = BUSINESS_ID,
        pageIndex = pageIndex,
        filePath = path,
        sha256 = imageId.value.replace("-", "").padEnd(64, '0'),
        mimeType = "image/jpeg",
        widthPx = 1_200,
        heightPx = 1_600,
        fileSizeBytes = 320_000L,
        createdAt = Instant.EPOCH,
    )

    private fun useCaseWith(
        imageImporter: DraftImageImporter,
        repository: InvoiceDraftRepository = drafts,
    ) = ImportDraftImageUseCase(
        appConfigurationRepository = appConfig,
        invoiceDraftRepository = repository,
        draftImageImporter = imageImporter,
        draftFileStore = fileStore,
        uuidGenerator = UuidGenerator { GENERATED_UUID },
    )

    private class BlockingDraftImageImporter : DraftImageImporter {
        val firstCallStarted = CompletableDeferred<Unit>()
        val releaseFirstCall = CompletableDeferred<Unit>()
        var callCount: Int = 0
            private set

        override suspend fun import(
            draftId: DraftId,
            imageId: ImageId,
            sourceUri: String,
        ): ImportedImageFile = resultFor(draftId, imageId)

        override suspend fun importBytes(
            draftId: DraftId,
            imageId: ImageId,
            jpegBytes: ByteArray,
            rotationDegrees: Int,
        ): ImportedImageFile = resultFor(draftId, imageId)

        private suspend fun resultFor(
            draftId: DraftId,
            imageId: ImageId,
        ): ImportedImageFile {
            callCount += 1
            if (callCount == 1) {
                firstCallStarted.complete(Unit)
                releaseFirstCall.await()
            }
            return ImportedImageFile(
                relativePath = "draft_images/${draftId.value}/${imageId.value}.jpg",
                sha256 = imageId.value.replace("-", "").padEnd(64, '0'),
                mimeType = "image/jpeg",
                widthPx = 1_200,
                heightPx = 1_600,
                fileSizeBytes = 320_000L,
            )
        }
    }

    private companion object {
        val GENERATED_UUID: UUID = UUID.fromString("aaaaaaaa-0000-4000-8000-0000000000aa")
        val SECOND_UUID: UUID = UUID.fromString("bbbbbbbb-0000-4000-8000-0000000000bb")
        val THIRD_UUID: UUID = UUID.fromString("cccccccc-0000-4000-8000-0000000000cc")
        val BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("00000000-0000-0000-0000-0000000000b1"),
        )
        val OTHER_BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("00000000-0000-0000-0000-0000000000b2"),
        )
        val DRAFT_ID: DraftId = DraftId.from(
            UUID.fromString("00000000-0000-4000-8000-0000000000d1"),
        )
        val IMAGE_ID_ABSENT: ImageId = ImageId.from(
            UUID.fromString("00000000-0000-4000-8000-0000000000ff"),
        )
        const val SOURCE_URI = "content://media/external/images/42"
        const val ROTATION_DEGREES = 90
        val JPEG_BYTES = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x42)
    }
}
