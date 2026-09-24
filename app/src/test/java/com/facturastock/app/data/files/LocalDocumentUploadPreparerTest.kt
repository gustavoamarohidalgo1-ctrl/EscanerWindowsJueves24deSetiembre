package com.facturastock.app.data.files

import com.facturastock.app.core.coroutines.DefaultDispatcherProvider
import com.facturastock.app.core.platform.AppDirectories
import com.facturastock.app.data.files.FilesTestSupport.createSymbolicLinkOrSkip
import com.facturastock.app.domain.model.PrivateImageDeletionResult
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.repository.DocumentUploadSource
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalDocumentUploadPreparerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var directories: AppDirectories
    private lateinit var filesDir: File
    private lateinit var cipher: RetainedImageCipher
    private lateinit var directory: File

    @Before
    fun setUp() {
        // Raíz privada temporal: también aísla la clave de cifrado en `no_backup/keys`.
        directories = AppDirectories(tempFolder.newFolder("app").canonicalFile)
        filesDir = directories.filesDir
        directory = File(filesDir, "document_uploads")
        check(directory.mkdirs())
        cipher = RetainedImageCipher(directories)
    }

    @Test
    fun orphanSweepKeepsOnlyArtifactsBackedByAnOpenRoomIdentity() {
        runBlocking {
            val retainedId = imageId()
            val orphanId = imageId()
            val retained = writeArtifact(retainedId)
            val orphan = writeArtifact(orphanId)

            val report = preparer().sweepOrphans(setOf(retainedId))

            assertEquals(1, report.attempted)
            assertEquals(1, report.deleted)
            assertEquals(0, report.failed)
            assertTrue(retained.isFile)
            assertFalse(orphan.exists())
        }
    }

    @Test
    fun failedOfflineSweepRemainsVisibleAndARecreatedPreparerRetriesIt() {
        runBlocking {
            val orphanId = imageId()
            val blockedArtifact = File(directory, "${orphanId.value}.fse")
            check(blockedArtifact.mkdir())

            val failed = preparer().sweepOrphans(emptySet())

            assertEquals(1, failed.attempted)
            assertEquals(1, failed.failed)
            assertTrue(blockedArtifact.isDirectory)

            assertTrue(blockedArtifact.delete())
            writeArtifact(orphanId)
            val retriedAfterRestart = preparer().sweepOrphans(emptySet())

            assertEquals(1, retriedAfterRestart.attempted)
            assertEquals(1, retriedAfterRestart.deleted)
            assertEquals(0, retriedAfterRestart.failed)
            assertFalse(blockedArtifact.exists())
        }
    }

    @Test
    fun discardVerifiesDeletionAndAbsenceIdempotently() {
        runBlocking {
            val imageId = imageId()
            writeArtifact(imageId)
            val preparer = preparer()

            assertEquals(PrivateImageDeletionResult.DELETED, preparer.discard(imageId.value))
            assertEquals(
                PrivateImageDeletionResult.ALREADY_ABSENT,
                preparer.discard(imageId.value),
            )
        }
    }

    @Test
    fun retryAfterArtifactFileSyncFailureUsesAuthenticatedArtifactAndReplaysDurability() {
        assertPreparedArtifactRetry(PublicationFailurePoint.FILE)
    }

    @Test
    fun retryAfterArtifactParentSyncFailureUsesAuthenticatedArtifactAndReplaysDurability() {
        assertPreparedArtifactRetry(PublicationFailurePoint.PARENT)
    }

    @Test
    fun newlyCreatedArtifactDirectoryMustBecomeDurableBeforePreparingBytes() = runBlocking {
        NoFollowFileTree.delete(directory)
        val imageId = imageId()
        val (source, _) = writeJpegSource(imageId)
        val rootDurability = FailOnceRootDirectoryDurability()
        val guardedPreparer = preparer(directoryDurability = rootDurability)

        assertNull(guardedPreparer.prepare(source))
        assertTrue(directory.isDirectory)
        assertFalse(File(directory, "${imageId.value}.fse").exists())
        assertEquals(1, rootDurability.attempts)

        val retried = guardedPreparer.prepare(source)

        assertTrue(retried != null)
        assertTrue(File(directory, "${imageId.value}.fse").isFile)
        assertEquals(2, rootDurability.attempts)
    }

    @Test
    fun documentArtifactRootSymlinkIsNeverTraversedOrDeleted() = runBlocking {
        NoFollowFileTree.delete(directory)
        val sentinel = File(filesDir, "document-upload-sentinel/keep.fse").apply {
            check(parentFile?.mkdirs() == true || parentFile?.isDirectory == true)
            writeBytes(byteArrayOf(9, 8, 7))
        }
        createSymbolicLinkOrSkip(directory.toPath(), sentinel.parentFile!!.toPath())
        val preparer = preparer()

        val failure = runCatching { preparer.sweepOrphans(emptySet()) }.exceptionOrNull()

        assertTrue(failure is java.io.IOException)
        assertEquals(
            PrivateImageDeletionResult.REJECTED_UNSAFE,
            preparer.discard(imageId().value),
        )
        assertTrue(Files.isSymbolicLink(directory.toPath()))
        assertEquals(listOf<Byte>(9, 8, 7), sentinel.readBytes().toList())
    }

    private fun preparer(
        artifactCipher: RetainedImageCipher = cipher,
        directoryDurability: PrivatePublicationDurability = PrivatePublicationDurability(),
    ): LocalDocumentUploadPreparer = LocalDocumentUploadPreparer(
        directories = directories,
        retainedImages = LocalRetainedImageStore(
            directories,
            artifactCipher,
            DefaultDispatcherProvider(),
        ),
        cipher = artifactCipher,
        dispatchers = DefaultDispatcherProvider(),
        directoryPublicationDurability = directoryDurability,
    )

    private fun writeArtifact(imageId: ImageId): File =
        File(directory, "${imageId.value}.fse").also { artifact ->
            check(cipher.encryptBytesToFile(byteArrayOf(1, 2, 3), artifact))
            check(cipher.isEncrypted(artifact))
        }

    private fun imageId(): ImageId = requireNotNull(ImageId.parse(UUID.randomUUID().toString()))

    private fun assertPreparedArtifactRetry(failurePoint: PublicationFailurePoint) = runBlocking {
        val imageId = imageId()
        val (source, sourceFile) = writeJpegSource(imageId)
        val durability = FailOncePublicationDurability(failurePoint)
        val retryingCipher = RetainedImageCipher(directories, durability)
        val retryingPreparer = preparer(retryingCipher)
        val artifact = File(directory, "${imageId.value}.fse")

        assertNull(retryingPreparer.prepare(source))
        assertTrue(artifact.isFile)
        assertEquals(
            RetainedImageCipher.EnvelopeState.ENCRYPTED,
            retryingCipher.inspect(artifact),
        )
        val publishedEnvelope = artifact.readBytes()
        assertTrue(sourceFile.delete())

        val prepared = retryingPreparer.prepare(source)

        assertTrue(prepared != null)
        assertArrayEquals(publishedEnvelope, artifact.readBytes())
        assertEquals(2, durability.fileSyncAttempts)
        assertEquals(
            if (failurePoint == PublicationFailurePoint.FILE) 1 else 2,
            durability.parentSyncAttempts,
        )
    }

    private fun writeJpegSource(imageId: ImageId): Pair<DocumentUploadSource, File> {
        val draftDirectory = File(
            filesDir,
            "draft_images/${UUID.randomUUID()}",
        ).apply {
            check(mkdirs())
        }
        // `Bitmap.createBitmap` nace transparente; el JPEG lo aplana a negro como Skia.
        val bytes = FilesTestSupport.jpegBytes(
            FilesTestSupport.solidImage(32, 24, 0x00000000),
            quality = 90,
        )
        val sourceFile = File(draftDirectory, "${imageId.value}.jpg").apply {
            writeBytes(bytes)
        }
        val source = DocumentUploadSource(
            imageId = imageId.value,
            sourceSha256 = sha256(bytes),
            relativeFilePath = sourceFile.relativeTo(filesDir).invariantSeparatorsPath,
            mimeType = "image/jpeg",
            rotationDegrees = 0,
        )
        bytes.fill(0)
        return source to sourceFile
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private enum class PublicationFailurePoint { FILE, PARENT }

    private class FailOncePublicationDurability(
        private val failurePoint: PublicationFailurePoint,
    ) : PrivatePublicationDurability() {
        var fileSyncAttempts: Int = 0
            private set
        var parentSyncAttempts: Int = 0
            private set

        override fun syncFile(file: File) {
            fileSyncAttempts += 1
            if (failurePoint == PublicationFailurePoint.FILE && fileSyncAttempts == 1) {
                throw IOException("injected file sync failure")
            }
            super.syncFile(file)
        }

        override fun syncParentAfterRename(destination: File) {
            parentSyncAttempts += 1
            if (failurePoint == PublicationFailurePoint.PARENT && parentSyncAttempts == 1) {
                throw IOException("injected parent sync failure")
            }
            super.syncParentAfterRename(destination)
        }
    }

    private class FailOnceRootDirectoryDurability : PrivatePublicationDurability() {
        var attempts: Int = 0
            private set

        override fun syncDirectoryAfterMutation(directory: File) {
            attempts += 1
            if (attempts == 1) throw IOException("injected root directory sync failure")
            super.syncDirectoryAfterMutation(directory)
        }
    }
}
