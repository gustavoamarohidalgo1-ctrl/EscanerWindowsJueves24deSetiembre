package com.facturastock.app.data.files

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.facturastock.app.core.coroutines.DefaultDispatcherProvider
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.model.OcrVersionSweepReport
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.repository.OcrVersionSweepDecision
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Barridos del ciclo de vida sobre directorios ficticios en el almacenamiento privado real:
 * huérfanos fuera del conjunto de IDs, versiones OCR de borradores confirmados y temporales
 * de importación por antigüedad. Los directorios vivos jamás se tocan.
 */
@RunWith(AndroidJUnit4::class)
class LocalRetentionFileSweepTest {
    private lateinit var context: Context
    private lateinit var sweep: LocalRetentionFileSweep
    private lateinit var mutationCoordinator: PrivateImageMutationCoordinator

    private val draftsRoot: File
        get() = File(context.filesDir, LocalDraftImageImporter.IMAGE_DIRECTORY)
    private val testCacheRoot: File
        get() = File(context.cacheDir, "privacy-sweep-test")
    private val sentinelRoot: File
        get() = File(context.filesDir, "privacy-sweep-sentinel")
    private val cacheRootLink: File
        get() = File(context.filesDir, "privacy-sweep-cache-link")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        mutationCoordinator = PrivateImageMutationCoordinator()
        sweep = LocalRetentionFileSweep(
            context = context,
            staleImportCleanup = StaleImportCleanup(context, AppClock { NOW }),
            dispatchers = DefaultDispatcherProvider(),
            mutationCoordinator = mutationCoordinator,
        )
        NoFollowFileTree.delete(draftsRoot)
        NoFollowFileTree.delete(testCacheRoot)
        NoFollowFileTree.delete(sentinelRoot)
        NoFollowFileTree.delete(cacheRootLink)
    }

    @After
    fun tearDown() {
        NoFollowFileTree.delete(draftsRoot)
        NoFollowFileTree.delete(testCacheRoot)
        NoFollowFileTree.delete(sentinelRoot)
        NoFollowFileTree.delete(cacheRootLink)
        File(context.filesDir, "import-leftover.tmp").delete()
        File(context.filesDir, "import-fresh.tmp").delete()
    }

    @Test
    fun orphanSweepDeletesOnlyDirectoriesOutsideTheExistingSet() = runBlocking {
        val alive = DRAFT_ID
        val orphan = OTHER_DRAFT_ID
        createDraftDir(alive, withOcr = true)
        createDraftDir(orphan, withOcr = true)
        // Un archivo suelto bajo draft_images no es un directorio de borrador: no se toca.
        File(draftsRoot, "nota.txt").writeText("no es un directorio")
        File(draftsRoot, alive.value).setLastModified(NOW.toEpochMilli() - 3_600_001L)
        File(draftsRoot, orphan.value).setLastModified(NOW.toEpochMilli() - 3_600_001L)
        val revalidated = mutableSetOf<DraftId>()

        val removed = sweep.sweepOrphanDraftImageDirs(
            now = NOW,
            // Simula un snapshot tomado justo antes de crearse `alive`.
            existingDraftIds = emptySet(),
        ) { draftId ->
            revalidated += draftId
            draftId == alive
        }

        assertEquals(1, removed.deleted)
        assertEquals(setOf(alive, orphan), revalidated)
        assertTrue(File(draftsRoot, alive.value).isDirectory)
        assertFalse(File(draftsRoot, orphan.value).exists())
        assertTrue(File(draftsRoot, "nota.txt").isFile)
    }

    @Test
    fun orphanSweepWaitsForDraftMutationAndRevalidatesInsideTheLock() = runBlocking {
        createDraftDir(DRAFT_ID, withOcr = false)
        val directory = File(draftsRoot, DRAFT_ID.value)
        directory.setLastModified(NOW.toEpochMilli() - 3_600_001L)
        val lockEntered = CompletableDeferred<Unit>()
        val releaseLock = CompletableDeferred<Unit>()
        val callbackEntered = CompletableDeferred<Unit>()
        val holder = async(Dispatchers.Default) {
            mutationCoordinator.withDraftLock(DRAFT_ID) {
                lockEntered.complete(Unit)
                releaseLock.await()
            }
        }
        lockEntered.await()
        val pendingSweep = async(Dispatchers.Default) {
            sweep.sweepOrphanDraftImageDirs(NOW, emptySet()) {
                callbackEntered.complete(Unit)
                true
            }
        }

        assertNull(withTimeoutOrNull(100) { callbackEntered.await() })
        assertTrue(directory.isDirectory)
        releaseLock.complete(Unit)

        assertEquals(0, pendingSweep.await().deleted)
        holder.await()
        assertTrue(callbackEntered.isCompleted)
        assertTrue(directory.isDirectory)
    }

    @Test
    fun orphanSweepRevalidatesEvenWhenTheDraftWasPresentInTheInitialSnapshot() = runBlocking {
        createDraftDir(DRAFT_ID, withOcr = false)
        val directory = File(draftsRoot, DRAFT_ID.value).apply {
            setLastModified(NOW.toEpochMilli() - 3_600_001L)
        }
        var revalidated = false

        val report = sweep.sweepOrphanDraftImageDirs(
            now = NOW,
            existingDraftIds = setOf(DRAFT_ID),
        ) {
            revalidated = true
            false
        }

        assertTrue(revalidated)
        assertEquals(1, report.deleted)
        assertFalse(directory.exists())
    }

    @Test
    fun orphanSweepKeepsCanonicalTreeWithLeafReferencedByAnotherLegacyRow() = runBlocking {
        createDraftDir(DRAFT_ID, withOcr = false)
        val directory = File(draftsRoot, DRAFT_ID.value).apply {
            setLastModified(NOW.toEpochMilli() - 3_600_001L)
        }
        val sharedPath =
            "${LocalDraftImageImporter.IMAGE_DIRECTORY}/${DRAFT_ID.value}/page-0.jpg"

        val report = sweep.sweepOrphanDraftImageDirs(
            now = NOW,
            existingDraftIds = emptySet(),
            pathIsReferencedAnywhere = { path -> path == sharedPath },
        ) { false }

        assertEquals(0, report.attempted)
        assertTrue(directory.isDirectory)
    }

    @Test
    fun orphanSweepKeepsInvalidUuidTreeWithGloballyReferencedLeaf() = runBlocking {
        val directory = File(draftsRoot, "legacy-invalid-id").apply {
            check(mkdirs() || isDirectory)
        }
        File(directory, "legacy.jpg").writeBytes(byteArrayOf(9))
        directory.setLastModified(NOW.toEpochMilli() - 3_600_001L)
        val sharedPath =
            "${LocalDraftImageImporter.IMAGE_DIRECTORY}/${directory.name}/legacy.jpg"

        val report = sweep.sweepOrphanDraftImageDirs(
            now = NOW,
            existingDraftIds = emptySet(),
            pathIsReferencedAnywhere = { path -> path == sharedPath },
        ) { false }

        assertEquals(0, report.attempted)
        assertTrue(directory.isDirectory)
    }

    @Test
    fun forceSweepKeepsUnknownAgeOrphanDirectoryAndReportsFailure() = runBlocking {
        createDraftDir(DRAFT_ID, withOcr = false)
        val directory = File(draftsRoot, DRAFT_ID.value)
        Files.setLastModifiedTime(directory.toPath(), FileTime.fromMillis(0L))

        val report = sweep.sweepOrphanDraftImageDirs(
            now = NOW,
            existingDraftIds = emptySet(),
            forceDeletionCutoff = NOW,
        ) { false }

        assertEquals(1, report.attempted)
        assertEquals(0, report.deleted)
        assertEquals(1, report.failed)
        assertTrue(directory.isDirectory)
    }

    @Test
    fun stalePrivateTempWaitsForTheSharedDraftLock() = runBlocking {
        createDraftDir(DRAFT_ID, withOcr = false)
        val stale = File(draftsRoot, "${DRAFT_ID.value}/encrypt-pending.tmp").apply {
            writeBytes(byteArrayOf(1))
            setLastModified(NOW.toEpochMilli() - 3_600_001L)
        }
        val lockEntered = CompletableDeferred<Unit>()
        val releaseLock = CompletableDeferred<Unit>()
        val holder = async(Dispatchers.Default) {
            mutationCoordinator.withDraftLock(DRAFT_ID) {
                lockEntered.complete(Unit)
                releaseLock.await()
            }
        }
        lockEntered.await()
        val pendingSweep = async(Dispatchers.Default) { sweep.sweepStalePrivateTemps(NOW) }

        assertNull(withTimeoutOrNull(100) { pendingSweep.await() })
        assertTrue(stale.isFile)
        releaseLock.complete(Unit)

        assertEquals(1, pendingSweep.await().deleted)
        holder.await()
        assertFalse(stale.exists())
    }

    @Test
    fun committedOcrSweepDeletesOnlyTheOcrSubtreeOfCommittedDrafts() = runBlocking {
        createDraftDir(DRAFT_ID, withOcr = true)
        createDraftDir(OTHER_DRAFT_ID, withOcr = true)

        val removed = sweep.sweepCommittedOcrVersions(setOf(DRAFT_ID)) { draftId ->
            draftId == DRAFT_ID
        }

        assertEquals(1, removed.deleted)
        // Los originales del borrador confirmado sobreviven: solo cae el subárbol ocr/.
        assertTrue(File(draftsRoot, "${DRAFT_ID.value}/page-0.jpg").isFile)
        assertFalse(File(draftsRoot, "${DRAFT_ID.value}/ocr").exists())
        // El borrador no confirmado conserva su subárbol OCR intacto.
        assertTrue(File(draftsRoot, "${OTHER_DRAFT_ID.value}/ocr").isDirectory)

        // Segunda pasada: idempotente, nada nuevo que borrar.
        assertEquals(
            0,
            sweep.sweepCommittedOcrVersions(setOf(DRAFT_ID)) { draftId ->
                draftId == DRAFT_ID
            }.deleted,
        )
    }

    @Test
    fun forcedOcrSweepDeletesOpenAndCommittedTreesWithClosedCounters() = runBlocking {
        createDraftDir(DRAFT_ID, withOcr = true)
        createDraftDir(OTHER_DRAFT_ID, withOcr = true)

        val report = sweep.sweepAllDraftOcrVersions(
            existingDraftIds = setOf(DRAFT_ID, OTHER_DRAFT_ID),
        ) { OcrVersionSweepDecision.DELETE }

        assertEquals(
            OcrVersionSweepReport(attempted = 2, deleted = 2),
            report,
        )
        assertTrue(File(draftsRoot, "${DRAFT_ID.value}/page-0.jpg").isFile)
        assertTrue(File(draftsRoot, "${OTHER_DRAFT_ID.value}/page-0.jpg").isFile)
        assertFalse(File(draftsRoot, "${DRAFT_ID.value}/ocr").exists())
        assertFalse(File(draftsRoot, "${OTHER_DRAFT_ID.value}/ocr").exists())

        assertEquals(
            OcrVersionSweepReport(attempted = 2, alreadyAbsent = 2),
            sweep.sweepAllDraftOcrVersions(setOf(DRAFT_ID, OTHER_DRAFT_ID)) {
                OcrVersionSweepDecision.DELETE
            },
        )
    }

    @Test
    fun forcedOcrSweepDefersAnActiveReaderAndContinuesOtherDrafts() = runBlocking {
        createDraftDir(DRAFT_ID, withOcr = true)
        createDraftDir(OTHER_DRAFT_ID, withOcr = true)

        val report = sweep.sweepAllDraftOcrVersions(
            existingDraftIds = setOf(DRAFT_ID, OTHER_DRAFT_ID),
        ) { draftId ->
            if (draftId == DRAFT_ID) {
                OcrVersionSweepDecision.RETRY_LATER
            } else {
                OcrVersionSweepDecision.DELETE
            }
        }

        assertEquals(
            OcrVersionSweepReport(
                attempted = 2,
                deleted = 1,
                failed = 1,
                retryableFailed = 1,
            ),
            report,
        )
        assertTrue(File(draftsRoot, "${DRAFT_ID.value}/ocr").isDirectory)
        assertFalse(File(draftsRoot, "${OTHER_DRAFT_ID.value}/ocr").exists())
    }

    @Test
    fun forcedOcrSweepNeverFollowsNestedOrRootSymlinks() = runBlocking {
        val sentinel = File(sentinelRoot, "keep.txt").apply {
            check(parentFile?.mkdirs() == true || parentFile?.isDirectory == true)
            writeText("no borrar")
        }
        createDraftDir(DRAFT_ID, withOcr = true)
        val nestedLink = File(draftsRoot, "${DRAFT_ID.value}/ocr/external-link")
        Files.createSymbolicLink(nestedLink.toPath(), sentinelRoot.toPath())

        val nestedReport = sweep.sweepAllDraftOcrVersions(setOf(DRAFT_ID)) {
            OcrVersionSweepDecision.DELETE
        }

        assertEquals(1, nestedReport.deleted)
        assertTrue(sentinel.isFile)

        val otherDraft = File(draftsRoot, OTHER_DRAFT_ID.value).apply {
            check(mkdirs() || isDirectory)
        }
        Files.createSymbolicLink(
            File(otherDraft, "ocr").toPath(),
            sentinelRoot.toPath(),
        )
        val rootLinkReport = sweep.sweepAllDraftOcrVersions(setOf(OTHER_DRAFT_ID)) {
            OcrVersionSweepDecision.DELETE
        }

        assertEquals(1, rootLinkReport.deleted)
        assertTrue(sentinel.isFile)
    }

    @Test
    fun draftRootSymlinkIsReportedAndNeverTraversed() = runBlocking {
        val sentinel = File(sentinelRoot, "old-image.jpg").apply {
            check(parentFile?.mkdirs() == true || parentFile?.isDirectory == true)
            writeBytes(byteArrayOf(7, 8, 9))
            setLastModified(NOW.toEpochMilli() - 3_600_001L)
        }
        NoFollowFileTree.delete(draftsRoot)
        Files.createSymbolicLink(draftsRoot.toPath(), sentinelRoot.toPath())

        val orphanReport = sweep.sweepOrphanDraftImageDirs(NOW, emptySet()) { false }
        val ocrReport = sweep.sweepAllDraftOcrVersions(emptySet()) {
            OcrVersionSweepDecision.DELETE
        }

        assertEquals(1, orphanReport.failed)
        assertEquals(1, ocrReport.failed)
        assertTrue(Files.isSymbolicLink(draftsRoot.toPath()))
        assertTrue(sentinel.isFile)
        assertEquals(listOf<Byte>(7, 8, 9), sentinel.readBytes().toList())
    }

    @Test
    fun staleImportSweepRespectsTheOneHourThreshold() = runBlocking {
        val stale = File(context.filesDir, "import-leftover.tmp").apply {
            writeText("temporal huérfano")
            setLastModified(NOW.toEpochMilli() - 3_600_000L - 1_000L)
        }
        val fresh = File(context.filesDir, "import-fresh.tmp").apply {
            writeText("importación en curso")
            setLastModified(NOW.toEpochMilli())
        }

        val removed = sweep.sweepStaleImports(NOW)

        assertEquals(1, removed.deleted)
        assertFalse(stale.exists())
        assertTrue(fresh.isFile)
    }

    @Test
    fun staleCacheSweepKeepsFreshSiblingInTheSameSubdirectory() = runBlocking {
        check(testCacheRoot.mkdirs() || testCacheRoot.isDirectory)
        val sharedSubdirectory = File(testCacheRoot, "same-batch").apply {
            check(mkdirs() || isDirectory)
        }
        val stale = File(sharedSubdirectory, "thumbnail-old.tmp").apply {
            writeBytes(byteArrayOf(1))
            setLastModified(NOW.toEpochMilli() - 3_600_001L)
        }
        val fresh = File(sharedSubdirectory, "thumbnail-active.tmp").apply {
            writeBytes(byteArrayOf(2))
            setLastModified(NOW.toEpochMilli())
        }

        val removed = sweep.sweepStaleCache(NOW)

        assertEquals(1, removed.deleted)
        assertFalse(stale.exists())
        assertTrue(testCacheRoot.isDirectory)
        assertTrue(sharedSubdirectory.isDirectory)
        assertTrue(fresh.isFile)
        assertEquals(listOf<Byte>(2), fresh.readBytes().toList())
    }

    @Test
    fun staleCacheSweepRejectsASymlinkAtItsRootWithoutTouchingTheTarget() = runBlocking {
        val sentinel = File(sentinelRoot, "keep-old.txt").apply {
            check(parentFile?.mkdirs() == true || parentFile?.isDirectory == true)
            writeText("no borrar")
            setLastModified(NOW.toEpochMilli() - 3_600_001L)
        }
        Files.createSymbolicLink(cacheRootLink.toPath(), sentinelRoot.toPath())
        val contextWithLinkedCache = object : ContextWrapper(context) {
            override fun getCacheDir(): File = cacheRootLink
        }
        val linkedSweep = LocalRetentionFileSweep(
            context = contextWithLinkedCache,
            staleImportCleanup = StaleImportCleanup(context, AppClock { NOW }),
            dispatchers = DefaultDispatcherProvider(),
            mutationCoordinator = mutationCoordinator,
        )

        val report = linkedSweep.sweepStaleCache(NOW)

        assertEquals(1, report.attempted)
        assertEquals(1, report.failed)
        assertTrue(Files.isSymbolicLink(cacheRootLink.toPath()))
        assertTrue(sentinel.isFile)
        assertEquals("no borrar", sentinel.readText())
    }

    @Test
    fun stalePrivateTempSweepRejectsSymlinkRootWithoutTouchingTheTarget() = runBlocking {
        val sentinel = File(sentinelRoot, "ocr-page-old.tmp").apply {
            check(parentFile?.mkdirs() == true || parentFile?.isDirectory == true)
            writeText("no borrar")
            setLastModified(NOW.toEpochMilli() - 3_600_001L)
        }
        Files.createSymbolicLink(cacheRootLink.toPath(), sentinelRoot.toPath())
        val contextWithLinkedFiles = object : ContextWrapper(context) {
            override fun getFilesDir(): File = cacheRootLink
        }
        val linkedSweep = LocalRetentionFileSweep(
            context = contextWithLinkedFiles,
            staleImportCleanup = StaleImportCleanup(context, AppClock { NOW }),
            dispatchers = DefaultDispatcherProvider(),
            mutationCoordinator = mutationCoordinator,
        )

        val report = linkedSweep.sweepStalePrivateTemps(NOW)

        assertEquals(1, report.attempted)
        assertEquals(1, report.failed)
        assertTrue(Files.isSymbolicLink(cacheRootLink.toPath()))
        assertEquals("no borrar", sentinel.readText())
    }

    @Test
    fun stalePrivateTempSweepDeletesOnlyKnownExpiredArtifacts() = runBlocking {
        val draftDir = File(draftsRoot, DRAFT_ID.value).apply {
            check(mkdirs() || isDirectory)
        }
        val staleCipher = File(draftDir, "encrypt-crashed.tmp").apply {
            writeBytes(byteArrayOf(1))
            setLastModified(NOW.toEpochMilli() - 3_600_001L)
        }
        val freshOcr = File(draftDir, "ocr-page-active.tmp").apply {
            writeBytes(byteArrayOf(2))
            setLastModified(NOW.toEpochMilli())
        }
        val unrelated = File(draftDir, "user-note.tmp").apply {
            writeText("no pertenece a un temporal conocido")
            setLastModified(NOW.toEpochMilli() - 3_600_001L)
        }

        val removed = sweep.sweepStalePrivateTemps(NOW)

        assertEquals(1, removed.deleted)
        assertFalse(staleCipher.exists())
        assertTrue(freshOcr.isFile)
        assertTrue(unrelated.isFile)
    }

    @Test
    fun forceSweepKeepsUnknownAgePrivateTempAndReportsRetryableFailure() = runBlocking {
        val draftDir = File(draftsRoot, DRAFT_ID.value).apply {
            check(mkdirs() || isDirectory)
        }
        val unknownAge = File(draftDir, "ocr-page-unknown.tmp").apply {
            writeBytes(byteArrayOf(1))
        }
        Files.setLastModifiedTime(unknownAge.toPath(), FileTime.fromMillis(0L))

        val report = sweep.sweepStalePrivateTemps(
            now = NOW,
            forceDeletionCutoff = NOW,
        )

        assertEquals(1, report.attempted)
        assertEquals(0, report.deleted)
        assertEquals(1, report.failed)
        assertTrue(unknownAge.isFile)
    }

    @Test
    fun forceSweepKeepsUnknownAgeUnreferencedFinalAndReportsFailure() = runBlocking {
        val draftDir = File(draftsRoot, DRAFT_ID.value).apply {
            check(mkdirs() || isDirectory)
        }
        val unknownAge = File(draftDir, "unknown-age.jpg").apply {
            writeBytes(byteArrayOf(1))
        }
        Files.setLastModifiedTime(unknownAge.toPath(), FileTime.fromMillis(0L))

        val report = sweep.sweepUnreferencedDraftImages(
            now = NOW,
            existingDraftIds = setOf(DRAFT_ID),
            forceDeletionCutoff = NOW,
            pathIsReferencedAnywhere = { false },
        ) { emptySet() }

        assertEquals(1, report.attempted)
        assertEquals(0, report.deleted)
        assertEquals(1, report.failed)
        assertTrue(unknownAge.isFile)
    }

    @Test
    fun unreferencedFinalSweepDeletesOnlyOldDirectImagesAbsentFromRoom() = runBlocking {
        createDraftDir(DRAFT_ID, withOcr = true)
        val referenced = File(draftsRoot, "${DRAFT_ID.value}/page-0.jpg")
        val crashedFinal = File(draftsRoot, "${DRAFT_ID.value}/crash-final.jpg").apply {
            writeBytes(byteArrayOf(4))
            setLastModified(NOW.toEpochMilli() - 3_600_001L)
        }
        val referencedPath =
            "${LocalDraftImageImporter.IMAGE_DIRECTORY}/${DRAFT_ID.value}/${referenced.name}"

        val report = sweep.sweepUnreferencedDraftImages(
            now = NOW,
            existingDraftIds = setOf(DRAFT_ID),
            pathIsReferencedAnywhere = { false },
        ) { setOf(referencedPath) }

        assertEquals(1, report.deleted)
        assertFalse(crashedFinal.exists())
        assertTrue(referenced.isFile)
        assertTrue(File(draftsRoot, "${DRAFT_ID.value}/ocr").isDirectory)
    }

    @Test
    fun unreferencedFinalSweepKeepsAPathReferencedByAnyOtherLegacyRow() = runBlocking {
        createDraftDir(DRAFT_ID, withOcr = false)
        val shared = File(draftsRoot, "${DRAFT_ID.value}/legacy-shared.jpg").apply {
            writeBytes(byteArrayOf(4, 2))
            setLastModified(NOW.toEpochMilli() - 3_600_001L)
        }
        val sharedPath =
            "${LocalDraftImageImporter.IMAGE_DIRECTORY}/${DRAFT_ID.value}/${shared.name}"

        val report = sweep.sweepUnreferencedDraftImages(
            now = NOW,
            existingDraftIds = setOf(DRAFT_ID),
            pathIsReferencedAnywhere = { candidate -> candidate == sharedPath },
        ) { emptySet() }

        assertEquals(0, report.attempted)
        assertTrue(shared.isFile)
        assertEquals(listOf<Byte>(4, 2), shared.readBytes().toList())
    }

    @Test
    fun forcedUnreferencedSweepDefersFreshPreCutoffFilesAndIgnoresFutureFiles() = runBlocking {
        createDraftDir(DRAFT_ID, withOcr = false)
        val freshPreCutoff = File(draftsRoot, "${DRAFT_ID.value}/fresh.jpg").apply {
            writeBytes(byteArrayOf(5))
            setLastModified(NOW.minusSeconds(1).toEpochMilli())
        }
        val future = File(draftsRoot, "${DRAFT_ID.value}/future.jpg").apply {
            writeBytes(byteArrayOf(6))
            setLastModified(NOW.plusSeconds(1).toEpochMilli())
        }
        val referenced =
            "${LocalDraftImageImporter.IMAGE_DIRECTORY}/${DRAFT_ID.value}/page-0.jpg"

        val report = sweep.sweepUnreferencedDraftImages(
            now = NOW,
            existingDraftIds = setOf(DRAFT_ID),
            forceDeletionCutoff = NOW,
            pathIsReferencedAnywhere = { false },
        ) { setOf(referenced) }

        assertEquals(1, report.attempted)
        assertEquals(1, report.failed)
        assertEquals(1, report.retryableFailed)
        assertTrue(freshPreCutoff.isFile)
        assertTrue(future.isFile)
    }

    private fun createDraftDir(draftId: DraftId, withOcr: Boolean) {
        val dir = File(draftsRoot, draftId.value)
        check(dir.mkdirs() || dir.isDirectory)
        File(dir, "page-0.jpg").writeBytes(byteArrayOf(1, 2, 3))
        if (withOcr) {
            val ocrDir = File(dir, LocalInvoiceImagePreprocessor.OCR_DIRECTORY)
            check(ocrDir.mkdirs() || ocrDir.isDirectory)
            File(ocrDir, "current-v1.manifest").writeText("recipe=v1")
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-17T12:00:00Z")
        val DRAFT_ID: DraftId = DraftId.from(UUID(0L, 1L))
        val OTHER_DRAFT_ID: DraftId = DraftId.from(UUID(0L, 2L))
    }
}
