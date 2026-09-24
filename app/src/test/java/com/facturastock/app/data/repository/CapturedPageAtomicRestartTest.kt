package com.facturastock.app.data.repository

import org.junit.rules.TemporaryFolder
import org.junit.Rule
import androidx.room.Room
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.Business
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.InvoiceImage
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.repository.CapturedPageWrite
import com.facturastock.app.domain.repository.CapturedPageIntent
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Verifica que el commit atómico de captura sea una fuente durable tras recrear Room. */
class CapturedPageAtomicRestartTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val filesDir: File
        get() = File(tempFolder.root, "files")

    private lateinit var database: FacturaStockDatabase
    private lateinit var clock: TestClock

    @Before
    fun setUp() {
        captureDirectory().deleteRecursively()
        clock = TestClock(NOW)
        database = openDatabase()
    }

    @After
    fun tearDown() {
        database.close() // Room KMP: cerrar de nuevo una base ya cerrada es inocuo.
        captureDirectory().deleteRecursively()
    }

    @Test
    fun publishedPageAndCapturedStateSurviveDatabaseAndRepositoryRecreation() = runBlocking {
        RoomBusinessRepository(database.businessDao(), testDispatchers, clock).create(
            Business(
                businessId = BUSINESS_ID,
                legalName = "Negocio captura durable",
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            ),
        )
        val firstRepository = draftRepository()
        firstRepository.createDraft(
            InvoiceDraft(
                draftId = DRAFT_ID,
                businessId = BUSINESS_ID,
                status = DraftStatus.CREATED,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            ),
        )
        val capture = File(filesDir, RELATIVE_PATH).apply {
            check(parentFile?.mkdirs() == true || parentFile?.isDirectory == true)
            writeBytes(CAPTURE_BYTES)
        }
        val published = firstRepository.publishCapturedPage(
            page = CapturedPageWrite(
                imageId = IMAGE_ID,
                draftId = DRAFT_ID,
                businessId = BUSINESS_ID,
                filePath = RELATIVE_PATH,
                sha256 = "a".repeat(64),
                mimeType = "image/jpeg",
                widthPx = 1_200,
                heightPx = 1_600,
                fileSizeBytes = capture.length(),
            ),
            intent = CapturedPageIntent.Append,
        ).image
        assertEquals(DraftStatus.CAPTURED, firstRepository.findDraft(DRAFT_ID)?.status)

        // Equivale a cierre forzado/reinicio: no se conserva ningún objeto Room/repositorio.
        database.close()
        database = openDatabase()
        val reopenedRepository = draftRepository()

        assertEquals(DraftStatus.CAPTURED, reopenedRepository.findDraft(DRAFT_ID)?.status)
        assertEquals(published, reopenedRepository.findImage(IMAGE_ID))
        assertEquals(
            published,
            reopenedRepository.findPublishedCapturedPage(
                IMAGE_ID,
                DRAFT_ID,
                BUSINESS_ID,
                CapturedPageIntent.Append,
            )?.image,
        )
        val mismatchedIntent = try {
            reopenedRepository.findPublishedCapturedPage(
                IMAGE_ID,
                DRAFT_ID,
                BUSINESS_ID,
                CapturedPageIntent.Replace(OTHER_IMAGE_ID),
            )
            error("se esperaba StorageException")
        } catch (expected: StorageException) {
            expected
        }
        assertTrue(mismatchedIntent.error is StorageError.ConstraintConflict)

        // El recibo no depende de la fila activa: borrar la página no libera la clave para
        // que una restauración antigua vuelva a añadirla silenciosamente.
        assertTrue(reopenedRepository.deleteImage(IMAGE_ID) != null)
        database.close()
        database = openDatabase()
        val afterDeletionRestart = draftRepository()
        val consumedReplay = try {
            afterDeletionRestart.findPublishedCapturedPage(
                IMAGE_ID,
                DRAFT_ID,
                BUSINESS_ID,
                CapturedPageIntent.Append,
            )
            error("se esperaba StorageException")
        } catch (expected: StorageException) {
            expected
        }
        assertTrue(consumedReplay.error is StorageError.ConstraintConflict)
        assertTrue(capture.readBytes().contentEquals(CAPTURE_BYTES))
    }

    @Test
    fun replaceReplayAfterRestartRetainsTheExactFileCleanup() = runBlocking {
        RoomBusinessRepository(database.businessDao(), testDispatchers, clock).create(
            Business(
                businessId = BUSINESS_ID,
                legalName = "Negocio recaptura durable",
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            ),
        )
        val firstRepository = draftRepository()
        firstRepository.createDraft(
            InvoiceDraft(
                draftId = DRAFT_ID,
                businessId = BUSINESS_ID,
                status = DraftStatus.CREATED,
                createdAt = Instant.EPOCH,
                updatedAt = Instant.EPOCH,
            ),
        )
        val oldPath = "draft_images/${DRAFT_ID.value}/${OLD_IMAGE_ID.value}.jpg"
        database.seedImageFixture(
            InvoiceImage(
                imageId = OLD_IMAGE_ID,
                draftId = DRAFT_ID,
                businessId = BUSINESS_ID,
                pageIndex = 0,
                filePath = oldPath,
                sha256 = "b".repeat(64),
                mimeType = "image/jpeg",
                widthPx = 1_200,
                heightPx = 1_600,
                fileSizeBytes = 100,
                createdAt = Instant.EPOCH,
            ),
            capturedAt = clock.now(),
        )
        val replacementPath = "draft_images/${DRAFT_ID.value}/${IMAGE_ID.value}.jpg"
        firstRepository.publishCapturedPage(
            CapturedPageWrite(
                imageId = IMAGE_ID,
                draftId = DRAFT_ID,
                businessId = BUSINESS_ID,
                filePath = replacementPath,
                sha256 = "c".repeat(64),
                mimeType = "image/jpeg",
                widthPx = 1_200,
                heightPx = 1_600,
                fileSizeBytes = 100,
            ),
            CapturedPageIntent.Replace(OLD_IMAGE_ID),
        )

        database.close()
        database = openDatabase()
        val replay = requireNotNull(
            draftRepository().findPublishedCapturedPage(
                IMAGE_ID,
                DRAFT_ID,
                BUSINESS_ID,
                CapturedPageIntent.Replace(OLD_IMAGE_ID),
            ),
        )

        assertEquals(replacementPath, replay.image.filePath)
        assertEquals(oldPath, replay.replacedFilePath)
    }

    private fun openDatabase(): FacturaStockDatabase =
        FacturaStockDatabase.buildAt(File(tempFolder.root, DATABASE_NAME))

    private fun draftRepository(): RoomInvoiceDraftRepository = RoomInvoiceDraftRepository(
        database = database,
        invoiceDraftDao = database.invoiceDraftDao(),
        invoiceImageDao = database.invoiceImageDao(),
        invoiceLineDao = database.invoiceLineDao(),
        dispatchers = testDispatchers,
        clock = clock,
    )

    private fun captureDirectory(): File = File(filesDir, "draft_images/${DRAFT_ID.value}")

    private companion object {
        const val DATABASE_NAME = "captured-page-atomic-restart-test.db"
        val NOW: Instant = Instant.parse("2026-08-14T20:00:00Z")
        val BUSINESS_ID: BusinessId = BusinessId.from(
            UUID.fromString("00000000-0000-4000-8000-000000003691"),
        )
        val DRAFT_ID: DraftId = DraftId.from(
            UUID.fromString("00000000-0000-4000-8000-000000003692"),
        )
        val IMAGE_ID: ImageId = ImageId.from(
            UUID.fromString("00000000-0000-4000-8000-000000003693"),
        )
        val OTHER_IMAGE_ID: ImageId = ImageId.from(
            UUID.fromString("00000000-0000-4000-8000-000000003694"),
        )
        val OLD_IMAGE_ID: ImageId = ImageId.from(
            UUID.fromString("00000000-0000-4000-8000-000000003695"),
        )
        val CAPTURE_BYTES: ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val RELATIVE_PATH = "draft_images/${DRAFT_ID.value}/${IMAGE_ID.value}.jpg"
    }
}
