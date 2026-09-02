package com.facturastock.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.entity.InvoiceHeaderEditEntity
import com.facturastock.app.data.local.entity.InvoiceLinesEditEntity
import com.facturastock.app.data.local.entity.InvoiceOcrSnapshotEntity
import com.facturastock.app.data.local.entity.InvoiceOcrSnapshotPageEntity
import com.facturastock.app.data.local.entity.ParsedInvoiceResultEntity
import com.facturastock.app.data.local.entity.PreparedPurchaseEntity
import com.facturastock.app.data.local.entity.PurchaseEntity
import com.facturastock.app.data.local.entity.SupplierEntity
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.Business
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.ImageCrop
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.InvoiceImage
import com.facturastock.app.domain.model.InvoiceLine
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.PurchaseDocumentType
import com.facturastock.app.domain.model.PurchaseStatus
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.model.id.OcrRunId
import com.facturastock.app.domain.model.id.SupplierId
import com.facturastock.app.domain.repository.CapturedPageIntent
import com.facturastock.app.domain.repository.CapturedPageWrite
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InvoiceDraftRepositoryTest {
    private lateinit var database: FacturaStockDatabase
    private lateinit var clock: TestClock
    private lateinit var businesses: RoomBusinessRepository
    private lateinit var drafts: RoomInvoiceDraftRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, FacturaStockDatabase::class.java).build()
        clock = TestClock(Instant.parse("2026-08-08T12:00:00Z"))
        businesses = RoomBusinessRepository(database.businessDao(), testDispatchers, clock)
        drafts = RoomInvoiceDraftRepository(
            database = database,
            invoiceDraftDao = database.invoiceDraftDao(),
            invoiceImageDao = database.invoiceImageDao(),
            invoiceLineDao = database.invoiceLineDao(),
            dispatchers = testDispatchers,
            clock = clock,
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun draftCrudAndObserve() = runBlocking {
        seedBusiness()
        val created = drafts.createDraft(draft(draftId(1)))
        assertEquals(clock.now(), created.createdAt)
        assertEquals(created.createdAt, created.updatedAt)
        assertEquals(created, drafts.findDraft(draftId(1)))
        drafts.observeDraft(draftId(1)).awaitMatching { it == created }

        clock.advanceSeconds(60)
        assertTrue(
            drafts.updateDraft(
                created.copy(
                    status = DraftStatus.NEEDS_REVIEW,
                    total = Money.ofMinor(100L, CurrencyCode.of("PEN")),
                ),
            ),
        )
        val updated = drafts.findDraft(draftId(1))!!
        assertEquals(DraftStatus.NEEDS_REVIEW, updated.status)
        assertEquals(Money.ofMinor(100L, CurrencyCode.of("PEN")), updated.total)
        assertEquals(created.createdAt, updated.createdAt)
        assertTrue(updated.updatedAt > created.updatedAt)

        clock.advanceSeconds(60)
        drafts.createDraft(draft(draftId(2)))
        // Orden por updatedAt descendente: el borrador 2 es el más reciente.
        drafts.observeDrafts(businessId(1), null).awaitMatching { it.size == 2 }
            .also { list ->
                assertEquals(listOf(draftId(2), draftId(1)), list.map { it.draftId })
            }
        drafts.observeDrafts(businessId(1), DraftStatus.NEEDS_REVIEW)
            .awaitMatching { it.isNotEmpty() }
            .also { list -> assertEquals(listOf(draftId(1)), list.map { it.draftId }) }
        drafts.observeDrafts(businessId(1), DraftStatus.READY_TO_POST).awaitMatching { it.isEmpty() }

        assertFalse(drafts.updateDraft(draft(draftId(50))))
        assertTrue(drafts.deleteDraft(draftId(2)))
        drafts.observeDrafts(businessId(1), null).awaitMatching { it.size == 1 }
        assertFalse(drafts.deleteDraft(draftId(2)))
    }

    @Test
    fun ocrRunCompareAndSetRejectsClosedDraftsAndStaleCallbacks() = runBlocking {
        seedBusiness()
        val created = drafts.createDraft(draft(draftId(1)).copy(status = DraftStatus.CAPTURED))
        val firstRun = OcrRunId.from(uuid(80))
        val secondRun = OcrRunId.from(uuid(81))

        assertTrue(drafts.beginOcrRun(created.draftId, firstRun))
        assertFalse(drafts.beginOcrRun(created.draftId, secondRun))
        assertFalse(
            drafts.finishOcrRun(created.draftId, secondRun, DraftStatus.OCR_READY),
        )
        assertFalse(drafts.resetInterruptedOcr(created.draftId, secondRun))
        assertTrue(drafts.resetInterruptedOcr(created.draftId, firstRun))
        assertTrue(drafts.beginOcrRun(created.draftId, firstRun))
        assertTrue(drafts.finishOcrRun(created.draftId, firstRun, DraftStatus.CAPTURED))
        assertTrue(drafts.beginOcrRun(created.draftId, secondRun))
        assertFalse(drafts.finishOcrRun(created.draftId, firstRun, DraftStatus.ERROR))
        assertFalse(drafts.resetInterruptedOcr(created.draftId, firstRun))

        val processing = requireNotNull(drafts.findDraft(created.draftId))
        drafts.updateDraft(
            processing.copy(
                status = DraftStatus.READY_TO_POST,
                activeOcrRunId = null,
            ),
        )
        assertFalse(drafts.finishOcrRun(created.draftId, secondRun, DraftStatus.OCR_READY))
        assertFalse(drafts.resetInterruptedOcr(created.draftId, secondRun))
        assertEquals(DraftStatus.READY_TO_POST, drafts.findDraft(created.draftId)?.status)
    }

    @Test
    fun publishedImageLifecycle() = runBlocking {
        seedBusiness()
        val created = drafts.createDraft(draft(draftId(1)))
        clock.advanceSeconds(60)
        val firstPage = drafts.publishCapturedPage(
            page = captured(image(imageId(1), draftId(1), pageIndex = 0, shaSeed = "a")),
            intent = CapturedPageIntent.Append,
        ).image
        assertEquals(clock.now(), firstPage.createdAt)
        assertTrue(drafts.findDraft(draftId(1))!!.updatedAt > created.updatedAt)

        drafts.publishCapturedPage(
            page = captured(image(imageId(3), draftId(1), pageIndex = 1, shaSeed = "c")),
            intent = CapturedPageIntent.Append,
        )
        drafts.observeImages(draftId(1)).awaitMatching { it.size == 2 }
            .also { list -> assertEquals(listOf(0, 1), list.map { it.pageIndex }) }

        clock.advanceSeconds(60)
        val before = drafts.findDraft(draftId(1))!!.updatedAt
        val replacement = drafts.publishCapturedPage(
            page = captured(image(imageId(4), draftId(1), pageIndex = 0, shaSeed = "d")),
            intent = CapturedPageIntent.Replace(imageId(1)),
        )
        assertEquals(firstPage.filePath, replacement.replacedFilePath)
        drafts.observeImages(draftId(1))
            .awaitMatching { list -> list.any { it.imageId == imageId(4) } }
            .also { list ->
                assertEquals(2, list.size)
                assertEquals(listOf(imageId(4), imageId(3)), list.map { it.imageId })
            }
        assertTrue(drafts.findDraft(draftId(1))!!.updatedAt > before)

        val deletion = requireNotNull(drafts.deleteImage(imageId(4)))
        assertEquals(imageId(4), deletion.image.imageId)
        assertFalse(deletion.draftIsEmpty)
        drafts.observeImages(draftId(1)).awaitMatching { it.size == 1 }
        assertNull(drafts.deleteImage(imageId(4)))
    }

    @Test
    fun imagePathReferenceCountIncludesExactLegacyAliases() = runBlocking {
        seedBusiness()
        drafts.createDraft(draft(draftId(1)))
        val sharedPath = "draft_images/legacy/shared.jpg"
        seedImage(
            image(imageId(1), draftId(1), pageIndex = 0, shaSeed = "a")
                .copy(filePath = sharedPath),
        )
        seedImage(
            image(imageId(2), draftId(1), pageIndex = 1, shaSeed = "b")
                .copy(filePath = sharedPath),
        )

        assertTrue(drafts.isImagePathReferenced(sharedPath))
        assertEquals(2, drafts.countImagePathReferences(sharedPath))
        assertEquals(0, drafts.countImagePathReferences("draft_images/legacy/missing.jpg"))
    }

    @Test
    fun publishCapturedPageTransitionsCreatedAndRejectsAdvancedState() =
        runBlocking {
            seedBusiness()
            val created = drafts.createDraft(draft(draftId(1)))
            clock.advanceSeconds(60)

            val first = drafts.publishCapturedPage(
                page = captured(image(imageId(1), draftId(1), pageIndex = 0, shaSeed = "a")),
                intent = CapturedPageIntent.Append,
            ).image

            assertEquals(DraftStatus.CAPTURED, drafts.findDraft(draftId(1))?.status)
            assertEquals(first, drafts.findImage(imageId(1)))
            assertTrue(requireNotNull(drafts.findDraft(draftId(1))).updatedAt > created.updatedAt)

            // Tras OCR las fuentes pueden haber sido eliminadas por AFTER_OCR: el agregado se
            // cierra a cambios de páginas y el rechazo ocurre sin tocar la fila existente.
            val capturedDraft = requireNotNull(drafts.findDraft(draftId(1)))
            assertTrue(drafts.updateDraft(capturedDraft.copy(status = DraftStatus.OCR_READY)))
            clock.advanceSeconds(60)
            assertThrows(StorageException::class.java) {
                runBlocking {
                    drafts.publishCapturedPage(
                        page = captured(
                            image(imageId(2), draftId(1), pageIndex = 1, shaSeed = "b"),
                        ),
                        intent = CapturedPageIntent.Append,
                    )
                }
            }
            assertEquals(DraftStatus.OCR_READY, drafts.findDraft(draftId(1))?.status)
            assertNull(drafts.findImage(imageId(2)))

            // Una preparación terminal editable tampoco puede cambiar sus fuentes.
            markReadyWithPreparedPurchase(draftId(1))
            assertThrows(StorageException::class.java) {
                runBlocking {
                    drafts.publishCapturedPage(
                        page = captured(
                            image(imageId(3), draftId(1), pageIndex = 2, shaSeed = "c"),
                        ),
                        intent = CapturedPageIntent.Append,
                    )
                }
            }
            assertEquals(DraftStatus.READY_TO_POST, drafts.findDraft(draftId(1))?.status)
            assertTrue(database.preparedPurchaseDao().find(draftId(1).value) != null)
        }

    @Test
    fun captureReceiptAcceptsOnlyTheExactOriginalRequest() = runBlocking {
        seedBusiness()
        drafts.createDraft(draft(draftId(1)))
        val request = captured(image(imageId(1), draftId(1), pageIndex = 0, shaSeed = "a"))
        val first = drafts.publishCapturedPage(request, CapturedPageIntent.Append).image

        assertEquals(
            first,
            drafts.publishCapturedPage(request, CapturedPageIntent.Append).image,
        )
        val changedIntent = assertThrows(StorageException::class.java) {
            runBlocking {
                drafts.publishCapturedPage(
                    request,
                    CapturedPageIntent.Replace(imageId(9)),
                )
            }
        }
        assertTrue(changedIntent.error is StorageError.ConstraintConflict)
        val changedPayload = assertThrows(StorageException::class.java) {
            runBlocking {
                drafts.publishCapturedPage(
                    request.copy(sha256 = "b".repeat(64)),
                    CapturedPageIntent.Append,
                )
            }
        }
        assertTrue(changedPayload.error is StorageError.ConstraintConflict)
        assertEquals(first, drafts.findImage(imageId(1)))
    }

    @Test
    fun capturePublicationRejectsPathOutsideItsExactImageNamespace() = runBlocking {
        seedBusiness()
        drafts.createDraft(draft(draftId(1)))
        val invalid = captured(image(imageId(1), draftId(1), pageIndex = 0, shaSeed = "a"))
            .copy(filePath = "draft_images/${draftId(1).value}/${imageId(2).value}.jpg")

        val failure = assertThrows(StorageException::class.java) {
            runBlocking { drafts.publishCapturedPage(invalid, CapturedPageIntent.Append) }
        }

        assertTrue(failure.error is StorageError.ConstraintConflict)
        assertNull(drafts.findImage(imageId(1)))
        assertNull(database.capturedPagePublicationDao().findByImageId(imageId(1).value))
    }

    @Test
    fun publishRotateCropMoveAndDeleteDiscardDerivedStateAndPreserveUnrelatedRows() = runBlocking {
        seedBusiness()
        val unrelated = seedUnrelatedCatalogAndPurchase()

        val publish = seedStaleImageDerivedState(
            draftSeed = 10,
            imageSeeds = listOf(101),
        )
        drafts.publishCapturedPage(
            page = captured(
                image(imageId(102), publish.draftId, pageIndex = 1, shaSeed = "b"),
            ),
            intent = CapturedPageIntent.Append,
        )
        assertImageDerivedStateReset(
            label = "publish",
            fixture = publish,
            expectedStatus = DraftStatus.CAPTURED,
            expectedImageCount = 2,
        )

        val rotate = seedStaleImageDerivedState(
            draftSeed = 20,
            imageSeeds = listOf(201),
        )
        assertTrue(drafts.rotateImage90(rotate.imageIds.single()) != null)
        assertImageDerivedStateReset(
            label = "rotate",
            fixture = rotate,
            expectedStatus = DraftStatus.CAPTURED,
            expectedImageCount = 1,
        )

        val crop = seedStaleImageDerivedState(
            draftSeed = 30,
            imageSeeds = listOf(301),
        )
        assertTrue(
            drafts.setImageCrop(
                crop.imageIds.single(),
                ImageCrop(left = 500, top = 1_000, right = 9_000, bottom = 9_500),
            ) != null,
        )
        assertImageDerivedStateReset(
            label = "crop",
            fixture = crop,
            expectedStatus = DraftStatus.CAPTURED,
            expectedImageCount = 1,
        )

        val move = seedStaleImageDerivedState(
            draftSeed = 40,
            imageSeeds = listOf(401, 402),
        )
        val moved = drafts.moveImageOneStep(
            draftId = move.draftId,
            imageId = move.imageIds.first(),
            moveUp = false,
        )
        assertEquals(listOf(move.imageIds[1], move.imageIds[0]), moved.map { it.imageId })
        assertImageDerivedStateReset(
            label = "move",
            fixture = move,
            expectedStatus = DraftStatus.CAPTURED,
            expectedImageCount = 2,
        )

        val delete = seedStaleImageDerivedState(
            draftSeed = 50,
            imageSeeds = listOf(501),
        )
        assertTrue(requireNotNull(drafts.deleteImage(delete.imageIds.single())).draftIsEmpty)
        assertImageDerivedStateReset(
            label = "delete-last",
            fixture = delete,
            expectedStatus = DraftStatus.CREATED,
            expectedImageCount = 0,
        )

        assertEquals(unrelated.supplier, database.supplierDao().findById(unrelated.supplier.supplierId))
        assertEquals(unrelated.purchase, database.purchaseDao().findById(unrelated.purchase.purchaseId))
    }

    @Test
    fun publishCapturedPageRollsBackReplacementAndPreparationWhenFinalTouchFails() = runBlocking {
        seedBusiness()
        drafts.createDraft(draft(draftId(1)))
        val original = seedImage(
            image(imageId(1), draftId(1), pageIndex = 0, shaSeed = "a"),
        )
        insertPreparedPurchase(draftId(1))
        val draftBefore = requireNotNull(drafts.findDraft(draftId(1)))
        assertTrue(database.preparedPurchaseDao().find(draftId(1).value) != null)
        clock.advanceSeconds(60)
        val sqlite = database.openHelper.writableDatabase
        sqlite.execSQL(
            "CREATE TRIGGER `test_fail_captured_page_final_step` " +
                "BEFORE UPDATE OF `status` ON `invoice_drafts` " +
                "WHEN OLD.`draftId` = '${draftId(1).value}' " +
                // La nueva imagen solo existe al llegar al touch final: la reapertura e
                // invalidación previas sí se ejecutan antes de este fallo inducido.
                "AND EXISTS (SELECT 1 FROM `invoice_images` " +
                "WHERE `imageId` = '${imageId(2).value}') " +
                "BEGIN SELECT RAISE(ABORT, 'forced final capture failure'); END",
        )

        val failure = try {
            assertThrows(StorageException::class.java) {
                runBlocking {
                    drafts.publishCapturedPage(
                        page = captured(
                            image(imageId(2), draftId(1), pageIndex = 0, shaSeed = "b"),
                        ),
                        intent = CapturedPageIntent.Replace(imageId(1)),
                    )
                }
            }
        } finally {
            sqlite.execSQL("DROP TRIGGER IF EXISTS `test_fail_captured_page_final_step`")
        }

        assertTrue(failure.error is StorageError.ConstraintConflict)
        // El delete/insert y la invalidación ya habían ocurrido dentro de la transacción,
        // pero el fallo del último UPDATE restaura página, estado, timestamp y preparación.
        assertEquals(original, drafts.findImage(imageId(1)))
        assertNull(drafts.findImage(imageId(2)))
        assertNull(database.capturedPagePublicationDao().findByImageId(imageId(2).value))
        assertEquals(draftBefore, drafts.findDraft(draftId(1)))
        assertTrue(database.preparedPurchaseDao().find(draftId(1).value) != null)
    }

    @Test
    fun imageTransformIntentsEditOnlyTheirOwnMetadata() = runBlocking {
        seedBusiness()
        val created = drafts.createDraft(draft(draftId(1)))
        val page = seedImage(image(imageId(1), draftId(1), pageIndex = 0, shaSeed = "a"))

        clock.advanceSeconds(60)
        val before = drafts.findDraft(draftId(1))!!.updatedAt
        val originalCrop = ImageCrop(left = 1_000, top = 2_000, right = 8_000, bottom = 8_500)
        assertTrue(drafts.setImageCrop(imageId(1), originalCrop) != null)
        val rotatedOnce = requireNotNull(drafts.rotateImage90(imageId(1)))
        assertEquals(originalCrop.rotated90Cw(), rotatedOnce.crop)
        repeat(2) { assertTrue(drafts.rotateImage90(imageId(1)) != null) }
        val rectangle = ImageCrop(left = 250, top = 500, right = 9_000, bottom = 9_500)
        assertTrue(drafts.setImageCrop(imageId(1), rectangle) != null)

        val persisted = drafts.findImage(imageId(1))!!
        assertEquals(270, persisted.rotationDegrees)
        assertEquals(rectangle, persisted.crop)
        // El resto del registro se conserva intacto.
        assertEquals(page.filePath, persisted.filePath)
        assertEquals(page.sha256, persisted.sha256)
        assertEquals(page.pageIndex, persisted.pageIndex)
        assertEquals(page.widthPx, persisted.widthPx)
        assertEquals(page.createdAt, persisted.createdAt)
        // Cada intent toca el updatedAt del borrador padre.
        assertTrue(drafts.findDraft(draftId(1))!!.updatedAt > before)
        assertEquals(created.createdAt, drafts.findDraft(draftId(1))!!.createdAt)

        // Limpiar el recorte (null) también se persiste.
        assertTrue(drafts.setImageCrop(imageId(1), null) != null)
        assertNull(drafts.findImage(imageId(1))!!.crop)

        // El reloj se leyó antes de una posible espera por la transacción: nunca puede hacer
        // retroceder un high-water mark más reciente del mismo agregado.
        val futureUpdatedAt = clock.now().plusSeconds(3_600)
        database.invoiceDraftDao().touchAtLeast(
            draftId = draftId(1).value,
            updatedAt = futureUpdatedAt.toEpochMilli(),
        )
        assertTrue(drafts.rotateImage90(imageId(1)) != null)
        assertEquals(futureUpdatedAt, drafts.findDraft(draftId(1))?.updatedAt)

        assertNull(drafts.rotateImage90(imageId(50)))
        assertNull(drafts.setImageCrop(imageId(50), rectangle))
    }

    @Test
    fun imageTransformIntentsComposeUnderConcurrencyWithoutLostUpdates() = runBlocking {
        seedBusiness()
        drafts.createDraft(draft(draftId(1)))
        seedImage(image(imageId(1), draftId(1), pageIndex = 0, shaSeed = "a"))
        seedImage(image(imageId(2), draftId(1), pageIndex = 1, shaSeed = "b"))

        val rotationStart = CompletableDeferred<Unit>()
        val rotations = List(2) {
            async {
                rotationStart.await()
                drafts.rotateImage90(imageId(1))
            }
        }
        rotationStart.complete(Unit)
        rotations.awaitAll()
        assertEquals(180, drafts.findImage(imageId(1))?.rotationDegrees)

        val rectangle = ImageCrop(left = 500, top = 1_500, right = 8_000, bottom = 9_000)
        val mixedStart = CompletableDeferred<Unit>()
        val rotation = async {
            mixedStart.await()
            drafts.rotateImage90(imageId(2))
        }
        val cropping = async {
            mixedStart.await()
            drafts.setImageCrop(imageId(2), rectangle)
        }
        mixedStart.complete(Unit)
        awaitAll(rotation, cropping)
        val concurrentlyEdited = requireNotNull(drafts.findImage(imageId(2)))
        assertEquals(90, concurrentlyEdited.rotationDegrees)
        assertTrue(
            concurrentlyEdited.crop in setOf(rectangle, rectangle.rotated90Cw()),
        )
    }

    @Test
    fun imageTransformRollsBackMetadataAndDerivedInvalidationWhenFinalResetFails() = runBlocking {
        seedBusiness()
        drafts.createDraft(draft(draftId(1)))
        val original = seedImage(
            image(imageId(1), draftId(1), pageIndex = 0, shaSeed = "a"),
        )
        drafts.addLine(line(lineId(1), draftId(1), position = 0))
        val draftBefore = requireNotNull(drafts.findDraft(draftId(1)))
        val sqlite = database.openHelper.writableDatabase
        sqlite.execSQL(
            "CREATE TRIGGER `test_fail_transform_final_step` " +
                "BEFORE UPDATE OF `status` ON `invoice_drafts` " +
                "WHEN OLD.`draftId` = '${draftId(1).value}' " +
                "BEGIN SELECT RAISE(ABORT, 'forced transform final failure'); END",
        )

        val failure = try {
            assertThrows(StorageException::class.java) {
                runBlocking { drafts.rotateImage90(imageId(1)) }
            }
        } finally {
            sqlite.execSQL("DROP TRIGGER IF EXISTS `test_fail_transform_final_step`")
        }

        assertTrue(failure.error is StorageError.ConstraintConflict)
        assertEquals(original, drafts.findImage(imageId(1)))
        assertEquals(draftBefore, drafts.findDraft(draftId(1)))
        assertEquals(1, database.invoiceLineDao().countForDraft(draftId(1).value))
    }

    @Test
    fun activeOcrClaimRejectsTransformWithoutChangingMetadata() = runBlocking {
        seedBusiness()
        drafts.createDraft(draft(draftId(1)))
        val original = seedImage(
            image(imageId(1), draftId(1), pageIndex = 0, shaSeed = "a"),
        )
        val runId = OcrRunId.from(uuid(80))
        assertTrue(drafts.beginOcrRun(draftId(1), runId))

        val failure = assertThrows(StorageException::class.java) {
            runBlocking { drafts.rotateImage90(imageId(1)) }
        }

        assertTrue(failure.error is StorageError.ConstraintConflict)
        assertEquals(original, drafts.findImage(imageId(1)))
        assertEquals(DraftStatus.OCR_PROCESSING, drafts.findDraft(draftId(1))?.status)
        assertEquals(runId, drafts.findDraft(draftId(1))?.activeOcrRunId)
    }

    @Test
    fun concurrentOneStepMovesComposeInsideRoomTransactions() = runBlocking {
        seedBusiness()
        drafts.createDraft(draft(draftId(1)))
        seedImage(image(imageId(1), draftId(1), pageIndex = 0, shaSeed = "a"))
        seedImage(image(imageId(2), draftId(1), pageIndex = 1, shaSeed = "b"))
        seedImage(image(imageId(3), draftId(1), pageIndex = 2, shaSeed = "c"))

        listOf(
            async { drafts.moveImageOneStep(draftId(1), imageId(3), moveUp = true) },
            async { drafts.moveImageOneStep(draftId(1), imageId(3), moveUp = true) },
        ).awaitAll()

        val final = drafts.observeImages(draftId(1)).awaitMatching { it.size == 3 }
        assertEquals(listOf(imageId(3), imageId(1), imageId(2)), final.map { it.imageId })
        assertEquals(listOf(0, 1, 2), final.map { it.pageIndex })
        assertEquals("captures/${draftId(1).value}/page-2.jpg", final.first().filePath)
    }

    @Test
    fun deleteImageReindexesRemainingPages() = runBlocking {
        seedBusiness()
        drafts.createDraft(draft(draftId(1)))
        seedImage(image(imageId(1), draftId(1), pageIndex = 0, shaSeed = "a"))
        seedImage(image(imageId(2), draftId(1), pageIndex = 1, shaSeed = "b"))
        seedImage(image(imageId(3), draftId(1), pageIndex = 2, shaSeed = "c"))

        // Eliminar la página central: la última baja a la posición 1 sin huecos.
        val middleDeletion = requireNotNull(drafts.deleteImage(imageId(2)))
        assertEquals(imageId(2), middleDeletion.image.imageId)
        assertFalse(middleDeletion.draftIsEmpty)
        drafts.observeImages(draftId(1)).awaitMatching { it.size == 2 }
            .also { list ->
                assertEquals(listOf(imageId(1), imageId(3)), list.map { it.imageId })
                assertEquals(listOf(0, 1), list.map { it.pageIndex })
            }

        // Tras el reindexado la página 1 es única: se puede insertar una nueva al final.
        seedImage(image(imageId(4), draftId(1), pageIndex = 2, shaSeed = "d"))
        drafts.observeImages(draftId(1)).awaitMatching { it.size == 3 }
            .also { list ->
                assertEquals(listOf(imageId(1), imageId(3), imageId(4)), list.map { it.imageId })
                assertEquals(listOf(0, 1, 2), list.map { it.pageIndex })
            }

        // Eliminar la primera reindexa todo de nuevo.
        val firstDeletion = requireNotNull(drafts.deleteImage(imageId(1)))
        assertEquals(imageId(1), firstDeletion.image.imageId)
        assertFalse(firstDeletion.draftIsEmpty)
        drafts.observeImages(draftId(1)).awaitMatching { it.size == 2 }
            .also { list ->
                assertEquals(listOf(imageId(3), imageId(4)), list.map { it.imageId })
                assertEquals(listOf(0, 1), list.map { it.pageIndex })
            }
        Unit
    }

    @Test
    fun deleteLastImageReturnsRemovedRecordAndResetsCapturedDraftAtomically() = runBlocking {
        seedBusiness()
        drafts.createDraft(draft(draftId(1)).copy(status = DraftStatus.CAPTURED))
        val persisted = seedImage(
            image(imageId(1), draftId(1), pageIndex = 0, shaSeed = "a"),
        )
        clock.advanceSeconds(60)

        val deletion = requireNotNull(drafts.deleteImage(imageId(1)))

        assertEquals(persisted, deletion.image)
        assertTrue(deletion.draftIsEmpty)
        assertTrue(drafts.observeImages(draftId(1)).awaitMatching { it.isEmpty() }.isEmpty())
        val updatedDraft = requireNotNull(drafts.findDraft(draftId(1)))
        assertEquals(DraftStatus.CREATED, updatedDraft.status)
        assertEquals(clock.now(), updatedDraft.updatedAt)
    }

    @Test
    fun deleteImageRollsBackDeleteAndReindexWhenFinalTouchFails() = runBlocking {
        seedBusiness()
        drafts.createDraft(draft(draftId(1)).copy(status = DraftStatus.CAPTURED))
        seedImage(image(imageId(1), draftId(1), pageIndex = 0, shaSeed = "a"))
        seedImage(image(imageId(2), draftId(1), pageIndex = 1, shaSeed = "b"))
        seedImage(image(imageId(3), draftId(1), pageIndex = 2, shaSeed = "c"))
        val pagesBefore = drafts.observeImages(draftId(1)).awaitMatching { it.size == 3 }
        val draftBefore = requireNotNull(drafts.findDraft(draftId(1)))
        clock.advanceSeconds(60)
        val sqlite = database.openHelper.writableDatabase
        sqlite.execSQL(
            "CREATE TRIGGER `test_fail_image_delete_final_touch` " +
                "BEFORE UPDATE OF `status` ON `invoice_drafts` " +
                "WHEN OLD.`draftId` = '${draftId(1).value}' " +
                "AND NOT EXISTS (SELECT 1 FROM `invoice_images` " +
                "WHERE `imageId` = '${imageId(2).value}') " +
                "BEGIN SELECT RAISE(ABORT, 'forced final delete failure'); END",
        )

        val failure = try {
            assertThrows(StorageException::class.java) {
                runBlocking { drafts.deleteImage(imageId(2)) }
            }
        } finally {
            sqlite.execSQL("DROP TRIGGER IF EXISTS `test_fail_image_delete_final_touch`")
        }

        assertTrue(failure.error is StorageError.ConstraintConflict)
        assertEquals(pagesBefore, drafts.observeImages(draftId(1)).awaitMatching { it.size == 3 })
        assertEquals(draftBefore, drafts.findDraft(draftId(1)))
    }

    @Test
    fun lineLifecycle() = runBlocking {
        seedBusiness()
        drafts.createDraft(draft(draftId(1)))
        drafts.addLine(line(lineId(1), draftId(1), position = 0))
        drafts.addLine(line(lineId(2), draftId(1), position = 1))
        val current = drafts.observeLines(draftId(1)).awaitMatching { it.size == 2 }
        assertEquals(listOf(lineId(1), lineId(2)), current.map { it.lineId })

        // La posición (draftId, position) es única: insertar otra línea en ella falla.
        val conflict = assertThrows(StorageException::class.java) {
            runBlocking { drafts.addLine(line(lineId(3), draftId(1), position = 1)) }
        }
        assertTrue(conflict.error is StorageError.ConstraintConflict)

        clock.advanceSeconds(60)
        val before = drafts.findDraft(draftId(1))!!.updatedAt
        assertTrue(drafts.updateLine(current.first().copy(descriptionRaw = "AZÚCAR RUBIA X 50 KG")))
        drafts.observeLines(draftId(1))
            .awaitMatching { list -> list.any { it.descriptionRaw == "AZÚCAR RUBIA X 50 KG" } }
        // updateLine toca el updatedAt del borrador padre.
        assertTrue(drafts.findDraft(draftId(1))!!.updatedAt > before)

        assertFalse(drafts.updateLine(line(lineId(50), draftId(1), position = 5)))
        assertTrue(drafts.deleteLine(lineId(2)))
        drafts.observeLines(draftId(1)).awaitMatching { it.size == 1 }
        assertFalse(drafts.deleteLine(lineId(2)))
    }

    @Test
    fun replaceLinesIsAtomicAndRecovers() = runBlocking {
        seedBusiness()
        drafts.createDraft(draft(draftId(1)))
        drafts.replaceLines(
            draftId(1),
            listOf(
                line(lineId(1), draftId(1), position = 0),
                line(lineId(2), draftId(1), position = 1),
            ),
        )
        drafts.observeLines(draftId(1)).awaitMatching { it.size == 2 }

        // Dos líneas con el mismo lineId: el insertAll falla a mitad y la transacción revierte
        // incluso el deleteForDraft previo.
        val broken = listOf(
            line(lineId(3), draftId(1), position = 0),
            line(lineId(3), draftId(1), position = 1),
        )
        val exception = assertThrows(StorageException::class.java) {
            runBlocking { drafts.replaceLines(draftId(1), broken) }
        }
        assertTrue(exception.error is StorageError.ConstraintConflict)

        // Las líneas originales quedan intactas tras el rollback.
        drafts.observeLines(draftId(1)).awaitMatching { it.isNotEmpty() }
            .also { list ->
                assertEquals(listOf(lineId(1), lineId(2)), list.map { it.lineId })
                assertEquals(listOf(0, 1), list.map { it.position })
            }

        // Recuperación: un replaceLines válido funciona y reasigna posiciones 0..n-1.
        drafts.replaceLines(
            draftId(1),
            listOf(
                line(lineId(4), draftId(1), position = 0),
                line(lineId(5), draftId(1), position = 0),
                line(lineId(6), draftId(1), position = 0),
            ),
        )
        drafts.observeLines(draftId(1)).awaitMatching { it.size == 3 }
            .also { list ->
                assertEquals(listOf(lineId(4), lineId(5), lineId(6)), list.map { it.lineId })
                assertEquals(listOf(0, 1, 2), list.map { it.position })
            }
        Unit
    }

    @Test
    fun reorderLinesReassignsPositionsAndValidatesSet() = runBlocking {
        seedBusiness()
        drafts.createDraft(draft(draftId(1)))
        drafts.replaceLines(
            draftId(1),
            listOf(
                line(lineId(1), draftId(1), position = 0),
                line(lineId(2), draftId(1), position = 1),
                line(lineId(3), draftId(1), position = 2),
            ),
        )
        drafts.observeLines(draftId(1)).awaitMatching { it.size == 3 }

        drafts.reorderLines(draftId(1), listOf(lineId(3), lineId(1), lineId(2)))
        drafts.observeLines(draftId(1)).awaitMatching { list -> list.first().lineId == lineId(3) }
            .also { list ->
                assertEquals(listOf(lineId(3), lineId(1), lineId(2)), list.map { it.lineId })
                assertEquals(listOf(0, 1, 2), list.map { it.position })
            }

        // Un conjunto incompleto o con IDs ajenos se rechaza sin tocar el orden.
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { drafts.reorderLines(draftId(1), listOf(lineId(1), lineId(2))) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                drafts.reorderLines(draftId(1), listOf(lineId(1), lineId(2), lineId(9)))
            }
        }
        drafts.observeLines(draftId(1)).awaitMatching { it.isNotEmpty() }
            .also { list ->
                assertEquals(listOf(lineId(3), lineId(1), lineId(2)), list.map { it.lineId })
            }
        Unit
    }

    @Test
    fun everyImageMutationRejectsReadyDraftWithoutInvalidatingPreparation() = runBlocking {
        seedBusiness()
        drafts.createDraft(draft(draftId(1)))
        seedImage(image(imageId(1), draftId(1), pageIndex = 0, shaSeed = "a"))
        seedImage(image(imageId(2), draftId(1), pageIndex = 1, shaSeed = "b"))

        markReadyWithPreparedPurchase(draftId(1))
        assertThrows(StorageException::class.java) {
            runBlocking {
                drafts.publishCapturedPage(
                    captured(image(imageId(3), draftId(1), pageIndex = 2, shaSeed = "c")),
                    CapturedPageIntent.Append,
                )
            }
        }
        assertThrows(StorageException::class.java) {
            runBlocking {
                drafts.publishCapturedPage(
                    captured(image(imageId(4), draftId(1), pageIndex = 0, shaSeed = "d")),
                    CapturedPageIntent.Replace(imageId(1)),
                )
            }
        }
        assertThrows(StorageException::class.java) {
            runBlocking { drafts.rotateImage90(imageId(1)) }
        }
        assertThrows(StorageException::class.java) {
            runBlocking {
                drafts.setImageCrop(
                    imageId(1),
                    ImageCrop(left = 100, top = 100, right = 9_900, bottom = 9_900),
                )
            }
        }
        assertThrows(StorageException::class.java) {
            runBlocking { drafts.moveImageOneStep(draftId(1), imageId(1), moveUp = false) }
        }
        assertThrows(StorageException::class.java) {
            runBlocking { drafts.deleteImage(imageId(1)) }
        }

        assertEquals(DraftStatus.READY_TO_POST, drafts.findDraft(draftId(1))?.status)
        assertTrue(database.preparedPurchaseDao().find(draftId(1).value) != null)
        assertEquals(
            listOf(imageId(1), imageId(2)),
            drafts.observeImages(draftId(1)).awaitMatching { it.size == 2 }.map { it.imageId },
        )
    }

    @Test
    fun draftAndLineMutationsInvalidatePreparedPurchase() = runBlocking {
        seedBusiness()
        drafts.createDraft(draft(draftId(1)))
        drafts.addLine(line(lineId(1), draftId(1), position = 0))
        drafts.addLine(line(lineId(2), draftId(1), position = 1))

        markReadyWithPreparedPurchase(draftId(1))
        val ready = requireNotNull(drafts.findDraft(draftId(1)))
        assertTrue(
            drafts.updateDraft(
                ready.copy(total = Money.ofMinor(1_000L, CurrencyCode.of("PEN"))),
            ),
        )
        assertPreparationInvalidated(draftId(1))

        markReadyWithPreparedPurchase(draftId(1))
        drafts.addLine(line(lineId(3), draftId(1), position = 2))
        assertPreparationInvalidated(draftId(1))

        markReadyWithPreparedPurchase(draftId(1))
        val first = drafts.observeLines(draftId(1)).awaitMatching { it.size == 3 }.first()
        assertTrue(drafts.updateLine(first.copy(descriptionRaw = "ARROZ EDITADO")))
        assertPreparationInvalidated(draftId(1))

        markReadyWithPreparedPurchase(draftId(1))
        drafts.replaceLines(
            draftId(1),
            listOf(
                line(lineId(4), draftId(1), position = 0),
                line(lineId(5), draftId(1), position = 1),
            ),
        )
        assertPreparationInvalidated(draftId(1))

        markReadyWithPreparedPurchase(draftId(1))
        drafts.reorderLines(draftId(1), listOf(lineId(5), lineId(4)))
        assertPreparationInvalidated(draftId(1))

        markReadyWithPreparedPurchase(draftId(1))
        assertTrue(drafts.deleteLine(lineId(5)))
        assertPreparationInvalidated(draftId(1))
    }

    @Test
    fun failedAggregateMutationRollsBackPreparedPurchaseInvalidation() = runBlocking {
        seedBusiness()
        drafts.createDraft(draft(draftId(1)))
        drafts.replaceLines(
            draftId(1),
            listOf(
                line(lineId(1), draftId(1), position = 0),
                line(lineId(2), draftId(1), position = 1),
            ),
        )
        markReadyWithPreparedPurchase(draftId(1))

        val duplicatedPrimaryKey = listOf(
            line(lineId(3), draftId(1), position = 0),
            line(lineId(3), draftId(1), position = 1),
        )
        val failure = assertThrows(StorageException::class.java) {
            runBlocking { drafts.replaceLines(draftId(1), duplicatedPrimaryKey) }
        }
        assertTrue(failure.error is StorageError.ConstraintConflict)

        assertEquals(DraftStatus.READY_TO_POST, drafts.findDraft(draftId(1))?.status)
        assertTrue(database.preparedPurchaseDao().find(draftId(1).value) != null)
        drafts.observeLines(draftId(1)).awaitMatching { it.size == 2 }
            .also { lines -> assertEquals(listOf(lineId(1), lineId(2)), lines.map { it.lineId }) }
        Unit
    }

    @Test
    fun deleteDraftCascadesImagesAndLines() = runBlocking {
        seedBusiness()
        drafts.createDraft(draft(draftId(1)))
        seedImage(image(imageId(1), draftId(1), pageIndex = 0, shaSeed = "a"))
        drafts.replaceLines(draftId(1), listOf(line(lineId(1), draftId(1), position = 0)))
        assertEquals(1, database.invoiceImageDao().countForDraft(draftId(1).value))
        assertEquals(1, database.invoiceLineDao().countForDraft(draftId(1).value))

        assertTrue(drafts.deleteDraft(draftId(1)))

        assertEquals(0, database.invoiceImageDao().countForDraft(draftId(1).value))
        assertEquals(0, database.invoiceLineDao().countForDraft(draftId(1).value))
        drafts.observeImages(draftId(1)).awaitMatching { it.isEmpty() }
        drafts.observeLines(draftId(1)).awaitMatching { it.isEmpty() }
        assertNull(drafts.findDraft(draftId(1)))
    }

    @Test
    fun updatedAtPolicyUsesInjectedClock() = runBlocking {
        seedBusiness()
        val t0 = clock.now()
        val created = drafts.createDraft(draft(draftId(1)))
        assertEquals(t0, created.createdAt)
        assertEquals(t0, created.updatedAt)

        clock.advanceSeconds(60)
        val t1 = clock.now()
        drafts.updateDraft(created.copy(status = DraftStatus.CAPTURED))
        assertEquals(t1, drafts.findDraft(draftId(1))!!.updatedAt)

        clock.advanceSeconds(60)
        val t2 = clock.now()
        drafts.replaceLines(draftId(1), listOf(line(lineId(1), draftId(1), position = 0)))
        assertEquals(t2, drafts.findDraft(draftId(1))!!.updatedAt)
        assertEquals(
            t2,
            drafts.observeLines(draftId(1)).awaitMatching { it.isNotEmpty() }.first().updatedAt,
        )

        clock.advanceSeconds(60)
        val t3 = clock.now()
        seedImage(image(imageId(1), draftId(1), pageIndex = 0, shaSeed = "a"))
        val after = drafts.findDraft(draftId(1))!!
        assertEquals(t3, after.updatedAt)
        assertEquals(t0, after.createdAt)
    }

    private suspend fun seedBusiness() {
        businesses.create(
            Business(
                businessId = businessId(1),
                legalName = "Negocio de Prueba SAC",
                ruc = "20123456789",
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            ),
        )
    }

    private suspend fun seedImage(image: InvoiceImage): InvoiceImage =
        database.seedImageFixture(image, clock.now())

    private suspend fun seedUnrelatedCatalogAndPurchase(): UnrelatedPersistentRows {
        val now = clock.now().toEpochMilli()
        val supplier = SupplierEntity(
            supplierId = supplierId(70).value,
            businessId = businessId(1).value,
            legalName = "Proveedor ajeno persistente SAC",
            createdAt = now,
            updatedAt = now,
            ruc = "20987654321",
            tradeName = "Catálogo persistente",
        )
        database.supplierDao().insert(supplier)

        val sourceDraftId = draftId(90)
        drafts.createDraft(draft(sourceDraftId))
        val purchase = PurchaseEntity(
            purchaseId = uuid(900).toString(),
            businessId = businessId(1).value,
            sourceDraftId = sourceDraftId.value,
            supplierId = supplier.supplierId,
            documentType = PurchaseDocumentType.INVOICE.name,
            documentSeries = "F090",
            documentNumber = "1",
            issueDate = "2026-08-08",
            currencyCode = "PEN",
            subtotalMinorUnits = 100L,
            taxMinorUnits = 18L,
            otherChargesMinorUnits = 0L,
            totalMinorUnits = 118L,
            status = PurchaseStatus.DRAFT.name,
            idempotencyKey = "unrelated-image-invalidation-purchase",
            createdAt = now,
            updatedAt = now,
        )
        database.purchaseDao().insert(purchase)
        return UnrelatedPersistentRows(supplier = supplier, purchase = purchase)
    }

    private suspend fun seedStaleImageDerivedState(
        draftSeed: Int,
        imageSeeds: List<Int>,
    ): ImageDerivedStateFixture {
        require(imageSeeds.isNotEmpty())
        val targetDraftId = draftId(draftSeed)
        val created = drafts.createDraft(draft(targetDraftId))
        val targetImageIds = imageSeeds.map(::imageId)
        targetImageIds.forEachIndexed { pageIndex, targetImageId ->
            seedImage(
                image(
                    id = targetImageId,
                    draftId = targetDraftId,
                    pageIndex = pageIndex,
                    shaSeed = if (pageIndex % 2 == 0) "a" else "b",
                ),
            )
        }
        drafts.addLine(line(lineId(20_000 + draftSeed), targetDraftId, position = 0))

        val runId = OcrRunId.from(uuid(30_000 + draftSeed))
        database.invoiceOcrSnapshotDao().insertHeader(
            InvoiceOcrSnapshotEntity(
                draftId = targetDraftId.value,
                runId = runId.value,
                completedAt = clock.now().toEpochMilli(),
                codecVersion = 1,
                pageCount = targetImageIds.size,
            ),
        )
        database.invoiceOcrSnapshotDao().insertPages(
            targetImageIds.mapIndexed { pageIndex, targetImageId ->
                InvoiceOcrSnapshotPageEntity(
                    draftId = targetDraftId.value,
                    pageIndex = pageIndex,
                    sourceImageId = targetImageId.value,
                    widthPx = 100,
                    heightPx = 100,
                    payloadSha256 = "c".repeat(64),
                    payload = byteArrayOf((pageIndex + 1).toByte()),
                )
            },
        )
        database.parsedInvoiceDao().insert(
            ParsedInvoiceResultEntity(
                draftId = targetDraftId.value,
                runId = runId.value,
                parserVersion = 1,
                contextFingerprint = "d".repeat(64),
                payloadCodecVersion = 1,
                payloadSha256 = "e".repeat(64),
                payload = byteArrayOf(2),
                parsedAt = clock.now().toEpochMilli(),
            ),
        )
        database.invoiceHeaderEditDao().insert(
            InvoiceHeaderEditEntity(
                draftId = targetDraftId.value,
                revision = 2,
                payloadCodecVersion = 1,
                payloadSha256 = "f".repeat(64),
                payload = byteArrayOf(3),
                updatedAt = clock.now().toEpochMilli(),
            ),
        )
        database.invoiceLinesEditDao().insert(
            InvoiceLinesEditEntity(
                draftId = targetDraftId.value,
                revision = 3,
                payloadCodecVersion = 1,
                payloadSha256 = "1".repeat(64),
                payload = byteArrayOf(4),
                updatedAt = clock.now().toEpochMilli(),
            ),
        )

        val currency = CurrencyCode.of("PEN")
        assertTrue(
            drafts.updateDraft(
                requireNotNull(drafts.findDraft(targetDraftId)).copy(
                    // Simula una recuperación legada/inconsistente con todo lo derivado poblado.
                    status = DraftStatus.ERROR,
                    supplierId = supplierId(70),
                    supplierRucRaw = "RUC leído: 20987654321",
                    supplierRucNormalized = "20987654321",
                    supplierLegalNameRaw = "PROVEEDOR AJENO PERSISTENTE S.A.C.",
                    supplierLegalNameNormalized = "Proveedor ajeno persistente SAC",
                    documentType = PurchaseDocumentType.INVOICE,
                    documentNumberRaw = "F001 / 00000123",
                    documentNumberNormalized = "F001-00000123",
                    issueDateRaw = "08/08/2026",
                    issueDate = LocalDate.parse("2026-08-08"),
                    currency = currency,
                    subtotal = Money.ofMinor(1_000L, currency),
                    tax = Money.ofMinor(180L, currency),
                    otherCharges = Money.ofMinor(20L, currency),
                    total = Money.ofMinor(1_200L, currency),
                    headerConfidence = 900,
                    activeOcrRunId = runId,
                    lastError = "Error OCR anterior",
                ),
            ),
        )
        insertPreparedPurchase(targetDraftId)

        // El reloj de la mutación se lee antes de entrar en Room. Esta marca futura demuestra
        // que resetAfterImageMutation usa un high-water y no puede retroceder el agregado.
        val highWater = clock.now().plusSeconds(86_400L + draftSeed)
        assertEquals(
            1,
            database.invoiceDraftDao().touchAtLeast(
                draftId = targetDraftId.value,
                updatedAt = highWater.toEpochMilli(),
            ),
        )
        return ImageDerivedStateFixture(
            draftId = targetDraftId,
            imageIds = targetImageIds,
            createdAt = created.createdAt,
            highWater = highWater,
        )
    }

    private suspend fun assertImageDerivedStateReset(
        label: String,
        fixture: ImageDerivedStateFixture,
        expectedStatus: DraftStatus,
        expectedImageCount: Int,
    ) {
        val reset = requireNotNull(drafts.findDraft(fixture.draftId))
        assertEquals("$label draftId", fixture.draftId, reset.draftId)
        assertEquals("$label businessId", businessId(1), reset.businessId)
        assertEquals("$label status", expectedStatus, reset.status)
        assertNull("$label supplierId", reset.supplierId)
        assertNull("$label supplierRucRaw", reset.supplierRucRaw)
        assertNull("$label supplierRucNormalized", reset.supplierRucNormalized)
        assertNull("$label supplierLegalNameRaw", reset.supplierLegalNameRaw)
        assertNull("$label supplierLegalNameNormalized", reset.supplierLegalNameNormalized)
        assertNull("$label documentType", reset.documentType)
        assertNull("$label documentNumberRaw", reset.documentNumberRaw)
        assertNull("$label documentNumberNormalized", reset.documentNumberNormalized)
        assertNull("$label issueDateRaw", reset.issueDateRaw)
        assertNull("$label issueDate", reset.issueDate)
        assertNull("$label currency", reset.currency)
        assertNull("$label subtotal", reset.subtotal)
        assertNull("$label tax", reset.tax)
        assertNull("$label otherCharges", reset.otherCharges)
        assertNull("$label total", reset.total)
        assertNull("$label headerConfidence", reset.headerConfidence)
        assertNull("$label activeOcrRunId", reset.activeOcrRunId)
        assertNull("$label confirmedPurchaseId", reset.confirmedPurchaseId)
        assertNull("$label lastError", reset.lastError)
        assertEquals("$label createdAt", fixture.createdAt, reset.createdAt)
        assertEquals("$label updatedAt high-water", fixture.highWater, reset.updatedAt)

        val rawDraftId = fixture.draftId.value
        assertNull("$label OCR header", database.invoiceOcrSnapshotDao().findHeader(rawDraftId))
        assertTrue(
            "$label OCR pages",
            database.invoiceOcrSnapshotDao().findPages(rawDraftId).isEmpty(),
        )
        assertNull("$label parsed snapshot", database.parsedInvoiceDao().findByDraftId(rawDraftId))
        assertNull("$label header edit", database.invoiceHeaderEditDao().findByDraftId(rawDraftId))
        assertNull("$label lines edit", database.invoiceLinesEditDao().findByDraftId(rawDraftId))
        assertEquals("$label projected lines", 0, database.invoiceLineDao().countForDraft(rawDraftId))
        assertNull("$label prepared purchase", database.preparedPurchaseDao().find(rawDraftId))
        assertEquals(
            "$label image count",
            expectedImageCount,
            database.invoiceImageDao().countForDraft(rawDraftId),
        )
    }

    private suspend fun markReadyWithPreparedPurchase(draftId: DraftId) {
        val current = requireNotNull(database.invoiceDraftDao().findById(draftId.value))
        database.invoiceDraftDao().update(current.copy(status = DraftStatus.READY_TO_POST.name))
        insertPreparedPurchase(draftId)
        assertEquals(DraftStatus.READY_TO_POST, drafts.findDraft(draftId)?.status)
        assertTrue(database.preparedPurchaseDao().find(draftId.value) != null)
    }

    private suspend fun insertPreparedPurchase(draftId: DraftId) {
        database.preparedPurchaseDao().upsert(
            PreparedPurchaseEntity(
                draftId = draftId.value,
                logicalHash = "a".repeat(64),
                payloadCodecVersion = 1,
                payloadSha256 = "b".repeat(64),
                payload = byteArrayOf(1),
                preparedAt = clock.now().toEpochMilli(),
            ),
        )
    }

    private suspend fun assertPreparationInvalidated(draftId: DraftId) {
        assertEquals(DraftStatus.NEEDS_REVIEW, drafts.findDraft(draftId)?.status)
        assertNull(database.preparedPurchaseDao().find(draftId.value))
    }

    private data class ImageDerivedStateFixture(
        val draftId: DraftId,
        val imageIds: List<ImageId>,
        val createdAt: Instant,
        val highWater: Instant,
    )

    private data class UnrelatedPersistentRows(
        val supplier: SupplierEntity,
        val purchase: PurchaseEntity,
    )

    private fun uuid(seed: Int): UUID =
        UUID.fromString("00000000-0000-0000-0000-%012d".format(seed))

    private fun businessId(seed: Int) = BusinessId.from(uuid(seed))
    private fun draftId(seed: Int) = DraftId.from(uuid(seed))
    private fun imageId(seed: Int) = ImageId.from(uuid(seed))
    private fun lineId(seed: Int) = LineId.from(uuid(seed))
    private fun supplierId(seed: Int) = SupplierId.from(uuid(seed))

    private fun draft(id: DraftId) = InvoiceDraft(
        draftId = id,
        businessId = businessId(1),
        currency = CurrencyCode.of("PEN"),
        total = Money.ofMinor(944L, CurrencyCode.of("PEN")),
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private fun image(id: ImageId, draftId: DraftId, pageIndex: Int, shaSeed: String) = InvoiceImage(
        imageId = id,
        draftId = draftId,
        businessId = businessId(1),
        pageIndex = pageIndex,
        filePath = "captures/${draftId.value}/page-$pageIndex.jpg",
        sha256 = shaSeed.repeat(64),
        mimeType = "image/jpeg",
        widthPx = 3_000,
        heightPx = 4_000,
        fileSizeBytes = 1_000L,
        createdAt = Instant.EPOCH,
    )

    private fun captured(image: InvoiceImage) = CapturedPageWrite(
        imageId = image.imageId,
        draftId = image.draftId,
        businessId = image.businessId,
        filePath = "draft_images/${image.draftId.value}/${image.imageId.value}.jpg",
        sha256 = image.sha256,
        mimeType = image.mimeType,
        widthPx = image.widthPx,
        heightPx = image.heightPx,
        fileSizeBytes = image.fileSizeBytes,
        rotationDegrees = image.rotationDegrees,
    )

    private fun line(id: LineId, draftId: DraftId, position: Int) = InvoiceLine(
        lineId = id,
        draftId = draftId,
        businessId = businessId(1),
        position = position,
        descriptionRaw = "ARROZ EXTRA COSTEÑO X 50 KG",
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )
}
