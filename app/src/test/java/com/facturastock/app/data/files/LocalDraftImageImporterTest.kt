package com.facturastock.app.data.files

import com.facturastock.app.core.platform.AppDirectories
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.data.files.FilesTestSupport.ascii
import com.facturastock.app.data.files.FilesTestSupport.containsSequence
import com.facturastock.app.data.files.FilesTestSupport.createSymbolicLinkOrSkip
import com.facturastock.app.domain.error.FileError
import com.facturastock.app.domain.error.FileException
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pruebas del importador: pipeline real sobre una raíz privada temporal (limpieza de
 * metadatos, hash del archivo final, movimiento a destino) y de la limpieza selectiva de
 * temporales huérfanos.
 */
class LocalDraftImageImporterTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var directories: AppDirectories
    private lateinit var filesDir: File
    private lateinit var importer: LocalDraftImageImporter
    private lateinit var cleanup: StaleImportCleanup

    @Before
    fun setUp() {
        directories = AppDirectories(tempFolder.newFolder("app").canonicalFile)
        filesDir = directories.filesDir
        cleanup = StaleImportCleanup(directories, AppClock { Instant.now() })
        importer = LocalDraftImageImporter(directories, cleanup)
    }

    @Test
    fun importBytesStoresScrubbedFileWhosePersistedMetadataMatchesItsContent() = runBlocking {
        val jpegBytes = jpegWithExifBytes()

        val imported = importer.importBytes(DRAFT_ID, IMAGE_ID, jpegBytes, rotationDegrees = 90)

        // El archivo quedó dentro del sandbox privado con nombre UUID no identificable.
        val stored = File(filesDir, imported.relativePath)
        assertTrue(stored.absolutePath.startsWith(filesDir.absolutePath))
        assertTrue(stored.name.startsWith(IMAGE_ID.value))

        // Hash y tamaño persistidos coinciden con el contenido final ya limpio.
        val storedBytes = stored.readBytes()
        assertEquals(sha256Hex(storedBytes), imported.sha256)
        assertEquals(storedBytes.size.toLong(), imported.fileSizeBytes)

        // La limpieza cambió el contenido respecto al origen etiquetado.
        assertNotEquals(sha256Hex(jpegBytes), imported.sha256)

        // Sin GPS, EXIF ni orientación en el archivo guardado, que sigue decodificable.
        assertFalse(storedBytes.containsSequence(ascii("Exif")))
        assertFalse(storedBytes.containsSequence(ascii(FilesTestSupport.PRIVATE_MAKE)))
        assertEquals(
            ExifOrientationReader.ORIENTATION_NORMAL,
            ExifOrientationReader.readOrientation(stored),
        )
        assertEquals(64, DesktopImageCodec.decodeBounds(stored).outWidth)
        assertEquals(64, imported.widthPx)
        assertEquals(48, imported.heightPx)

        // La orientación EXIF del origen se rescató y viaja en los metadatos devueltos.
        assertEquals(90, imported.rotationDegrees)

        // No quedan temporales de la importación.
        assertTrue(importTemps().isEmpty())
    }

    @Test
    fun importBytesRejectsCorruptContentWithoutLeavingTempsOrDestinations() = runBlocking {
        try {
            importer.importBytes(DRAFT_ID, IMAGE_ID, byteArrayOf(1, 2, 3, 4, 5), 0)
            fail("se esperaba FileException")
        } catch (expected: FileException) {
            assertEquals(FileError.Corrupt, expected.error)
        }

        assertTrue(importTemps().isEmpty())
        assertFalse(
            File(filesDir, "draft_images/${DRAFT_ID.value}/${IMAGE_ID.value}.jpg").exists(),
        )
    }

    @Test
    fun importBytesRejectsNonJpegContentAsUnsupported() = runBlocking {
        val pngBytes = FilesTestSupport.pngBytes(
            FilesTestSupport.solidImage(16, 16, 0xFF112233.toInt()),
        )

        try {
            importer.importBytes(DRAFT_ID, IMAGE_ID, pngBytes, 0)
            fail("se esperaba FileException")
        } catch (expected: FileException) {
            assertEquals(FileError.UnsupportedFormat, expected.error)
        }

        assertTrue(importTemps().isEmpty())
    }

    @Test
    fun importBytesReusesAnExistingDestinationOnlyWhenCleanContentMatches() = runBlocking {
        val source = jpegWithExifBytes()
        val first = importer.importBytes(DRAFT_ID, IMAGE_ID, source, rotationDegrees = 90)
        val destination = File(filesDir, first.relativePath)
        val bytesBeforeRetry = destination.readBytes()
        val modifiedBeforeRetry = destination.lastModified()

        val retried = importer.importBytes(DRAFT_ID, IMAGE_ID, source, rotationDegrees = 90)

        assertEquals(first, retried)
        assertArrayEquals(bytesBeforeRetry, destination.readBytes())
        assertEquals(modifiedBeforeRetry, destination.lastModified())
        assertTrue(importTemps().isEmpty())
    }

    @Test
    fun importBytesRejectsDifferentContentWithoutOverwritingExistingDestination() = runBlocking {
        val first = importer.importBytes(
            DRAFT_ID,
            IMAGE_ID,
            jpegWithExifBytes(color = 0xFF336699.toInt()),
            rotationDegrees = 90,
        )
        val destination = File(filesDir, first.relativePath)
        val bytesBeforeCollision = destination.readBytes()

        try {
            importer.importBytes(
                DRAFT_ID,
                IMAGE_ID,
                jpegWithExifBytes(color = 0xFF993366.toInt()),
                rotationDegrees = 90,
            )
            fail("se esperaba FileException")
        } catch (expected: FileException) {
            assertEquals(FileError.Corrupt, expected.error)
        }

        assertArrayEquals(bytesBeforeCollision, destination.readBytes())
        assertEquals(first.sha256, sha256Hex(destination.readBytes()))
        assertTrue(importTemps().isEmpty())
    }

    @Test
    fun importBytesRejectsDestinationSymlinkWithoutTouchingItsTarget() = runBlocking {
        val destination = File(
            filesDir,
            "draft_images/${DRAFT_ID.value}/${IMAGE_ID.value}.jpg",
        ).apply {
            check(parentFile?.mkdirs() == true || parentFile?.isDirectory == true)
        }
        val sentinel = File(filesDir, "import-sentinel.jpg").apply {
            writeBytes(byteArrayOf(9, 7, 5, 3))
        }
        createSymbolicLinkOrSkip(destination.toPath(), sentinel.toPath())

        val failure = runCatching {
            importer.importBytes(DRAFT_ID, IMAGE_ID, jpegWithExifBytes(), 0)
        }.exceptionOrNull()

        assertTrue(failure is FileException)
        assertEquals(FileError.Corrupt, (failure as FileException).error)
        assertTrue(Files.isSymbolicLink(destination.toPath()))
        assertArrayEquals(byteArrayOf(9, 7, 5, 3), sentinel.readBytes())
        assertTrue(importTemps().isEmpty())
    }

    @Test
    fun importBytesTriggersOrphanCleanupWithoutTouchingFreshTemps() = runBlocking {
        // Un temporal viejo (huérfano) y uno reciente (sesión en curso) antes de importar.
        val stale = File(filesDir, "import-test-stale.tmp").apply {
            writeBytes(byteArrayOf(9))
            setLastModified(Instant.now().toEpochMilli() - TimeUnit.HOURS.toMillis(2))
        }
        val fresh = File(filesDir, "import-test-fresh.tmp").apply {
            writeBytes(byteArrayOf(9))
        }

        importer.importBytes(OTHER_DRAFT_ID, OTHER_IMAGE_ID, jpegWithExifBytes(), 0)

        assertFalse(stale.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun cleanupOnlyDeletesAgedImportTempsAtThePrivateRoot() {
        val stale = File(filesDir, "import-test-old.tmp").apply {
            writeBytes(byteArrayOf(1))
            setLastModified(Instant.now().toEpochMilli() - TimeUnit.HOURS.toMillis(3))
        }
        val fresh = File(filesDir, "import-test-new.tmp").apply { writeBytes(byteArrayOf(1)) }
        val unrelated = File(filesDir, "import-test-notes.txt").apply {
            writeBytes(byteArrayOf(1))
            setLastModified(Instant.now().toEpochMilli() - TimeUnit.HOURS.toMillis(3))
        }
        val nestedDir = File(filesDir, "draft_images/${DRAFT_ID.value}").apply { mkdirs() }
        val nestedStale = File(nestedDir, "import-test-nested.tmp").apply {
            writeBytes(byteArrayOf(1))
            setLastModified(Instant.now().toEpochMilli() - TimeUnit.HOURS.toMillis(3))
        }

        val removed = cleanup.cleanOrphanedImportTemps()

        assertEquals(1, removed)
        assertFalse(stale.exists())
        // Sesiones activas (temporal reciente) y todo lo demás quedan intactos.
        assertTrue(fresh.exists())
        assertTrue(unrelated.exists())
        assertTrue(nestedStale.exists())
    }

    @Test
    fun forceSweepReportsImportTempWithUnknownTimestampAsRetryableFailure() {
        val unknownAge = File(filesDir, "import-test-unknown-age.tmp").apply {
            writeBytes(byteArrayOf(1))
        }
        Files.setLastModifiedTime(unknownAge.toPath(), FileTime.fromMillis(0L))

        val report = cleanup.sweepOrphanedImportTemps(
            now = Instant.now(),
            forceDeletionCutoff = Instant.now(),
        )

        assertEquals(1, report.attempted)
        assertEquals(0, report.deleted)
        assertEquals(1, report.failed)
        assertTrue(unknownAge.isFile)
    }

    @Test
    fun cleanupRejectsSymlinkRootWithoutTouchingItsTarget() {
        val sentinelRoot = File(filesDir, "import-test-sentinel-root").apply {
            check(mkdirs() || isDirectory)
        }
        val sentinel = File(sentinelRoot, "import-old.tmp").apply {
            writeText("no borrar")
            setLastModified(Instant.now().minusSeconds(7_200).toEpochMilli())
        }
        // En lugar del ContextWrapper de Android: una raíz cuya carpeta `files` es un enlace.
        val linkedAppRoot = tempFolder.newFolder("linked-app").canonicalFile
        val linkedRoot = File(linkedAppRoot, "files")
        createSymbolicLinkOrSkip(linkedRoot.toPath(), sentinelRoot.toPath())

        val report = StaleImportCleanup(
            AppDirectories(linkedAppRoot),
            AppClock { Instant.now() },
        ).sweepOrphanedImportTemps(Instant.now())

        assertEquals(1, report.attempted)
        assertEquals(1, report.failed)
        assertTrue(Files.isSymbolicLink(linkedRoot.toPath()))
        assertEquals("no borrar", sentinel.readText())
    }

    private fun importTemps(): List<File> =
        filesDir.listFiles { file ->
            file.isFile &&
                file.name.startsWith(LocalDraftImageImporter.TEMP_FILE_PREFIX) &&
                file.name.endsWith(LocalDraftImageImporter.TEMP_FILE_SUFFIX)
        }?.toList().orEmpty()

    private fun jpegWithExifBytes(color: Int = 0xFF336699.toInt()): ByteArray =
        FilesTestSupport.withExif(
            FilesTestSupport.jpegBytes(FilesTestSupport.solidImage(64, 48, color), quality = 92),
            orientation = ExifOrientationReader.ORIENTATION_ROTATE_90,
        )

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bytes)
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private companion object {
        val DRAFT_ID: DraftId = DraftId.from(
            UUID.fromString("00000000-0000-4000-8000-00000000da01")
        )
        val OTHER_DRAFT_ID: DraftId = DraftId.from(
            UUID.fromString("00000000-0000-4000-8000-00000000da02")
        )
        val IMAGE_ID: ImageId = ImageId.from(
            UUID.fromString("00000000-0000-4000-8000-000000001a01")
        )
        val OTHER_IMAGE_ID: ImageId = ImageId.from(
            UUID.fromString("00000000-0000-4000-8000-000000001a02")
        )
    }
}
