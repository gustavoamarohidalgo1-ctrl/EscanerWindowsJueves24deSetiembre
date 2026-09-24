package com.facturastock.app.data.files

import com.facturastock.app.core.coroutines.DefaultDispatcherProvider
import com.facturastock.app.core.platform.AppDirectories
import com.facturastock.app.data.files.FilesTestSupport.createSymbolicLinkOrSkip
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.model.PrivateImageDeletionResult
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.repository.DocumentUploadDecodePolicy
import com.facturastock.app.domain.repository.RetainedImageReadResult
import com.facturastock.app.domain.usecase.ReadRetainedImageUseCase
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

/**
 * Almacén de imágenes retenidas sobre una raíz privada temporal: anti-traversal,
 * lectura transparente de cifrado y borrado honesto (cuenta solo lo que existía).
 */
class LocalRetainedImageStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var directories: AppDirectories
    private lateinit var filesDir: File
    private lateinit var store: LocalRetainedImageStore
    private lateinit var mutationCoordinator: PrivateImageMutationCoordinator
    private lateinit var cipher: RetainedImageCipher

    @Before
    fun setUp() {
        // La clave del cifrado vive en `no_backup/keys` de esta raíz temporal: no queda estado.
        directories = AppDirectories(tempFolder.newFolder("app").canonicalFile)
        filesDir = directories.filesDir
        cipher = RetainedImageCipher(directories)
        mutationCoordinator = PrivateImageMutationCoordinator()
        store = LocalRetainedImageStore(
            directories,
            cipher,
            DefaultDispatcherProvider(),
            mutationCoordinator,
        )
    }

    @Test
    fun readDecryptedServesPlainAndEncryptedFilesTransparently() = runBlocking {
        val plain = writeImage("page-0.jpg", CONTENT)
        val readRetainedImage = ReadRetainedImageUseCase(store)
        assertArrayEquals(CONTENT, readRetainedImage(plain))

        assertTrue(store.encryptInPlace(plain))
        assertTrue(store.isEncrypted(plain))
        assertArrayEquals(CONTENT, readRetainedImage(plain))
    }

    @Test
    fun encryptInPlaceIsIdempotentAndMissingFilesReturnFalse() = runBlocking {
        val path = writeImage("page-1.jpg", CONTENT)

        assertTrue(store.encryptInPlace(path))
        val ciphertext = File(filesDir, path).readBytes()
        assertTrue(store.encryptInPlace(path))
        assertArrayEquals(ciphertext, File(filesDir, path).readBytes())

        assertFalse(store.encryptInPlace("draft_images/${DRAFT_ID.value}/ausente.jpg"))
        assertFalse(store.isEncrypted("draft_images/${DRAFT_ID.value}/ausente.jpg"))
    }

    @Test
    fun concurrentEncryptAndDeleteCannotResurrectTheFile() = runBlocking {
        repeat(8) { index ->
            val path = writeImage(
                "encrypt-delete-$index.jpg",
                ByteArray(512 * 1024) { offset -> (offset % 127).toByte() },
            )

            awaitAll(
                async(Dispatchers.Default) { store.encryptInPlace(path) },
                async(Dispatchers.Default) { store.delete(path) },
            )

            assertEquals(RetainedImageReadResult.Absent, store.readForDisplay(path))
        }
    }

    @Test
    fun encryptAndDraftTreeDeletionShareTheSameCrossStoreLock() = runBlocking {
        val draftFiles = LocalDraftFileStore(directories, mutationCoordinator)
        repeat(8) { index ->
            val path = writeImage(
                "encrypt-tree-delete-$index.jpg",
                ByteArray(512 * 1024) { offset -> (offset % 127).toByte() },
            )

            awaitAll(
                async(Dispatchers.Default) { store.encryptInPlace(path) },
                async(Dispatchers.Default) { draftFiles.deleteDraftTree(DRAFT_ID) },
            )

            assertEquals(RetainedImageReadResult.Absent, store.readForDisplay(path))
        }
    }

    @Test
    fun deleteOnlyCountsFilesThatActuallyExisted() = runBlocking {
        val path = writeImage("page-2.jpg", CONTENT)

        assertEquals(PrivateImageDeletionResult.DELETED, store.delete(path))
        assertEquals(PrivateImageDeletionResult.ALREADY_ABSENT, store.delete(path))
        assertNull(store.readDecrypted(path))
    }

    @Test
    fun fsyncFailureAfterUnlinkIsFailedAndAbsentRetryCompletesDurability() = runBlocking {
        val durability = FailFirstDeletionSync()
        val failingStore = LocalRetainedImageStore(
            directories = directories,
            cipher = cipher,
            dispatchers = DefaultDispatcherProvider(),
            mutationCoordinator = mutationCoordinator,
            deletionDurability = durability,
        )
        val relativePath = writeImage("unlink-before-fsync-failure.jpg", CONTENT)
        val file = File(filesDir, relativePath)

        assertEquals(PrivateImageDeletionResult.FAILED, failingStore.delete(relativePath))
        assertTrue(Files.notExists(file.toPath(), LinkOption.NOFOLLOW_LINKS))

        assertEquals(
            PrivateImageDeletionResult.ALREADY_ABSENT,
            failingStore.delete(relativePath),
        )
        assertEquals(2, durability.syncAttempts)
        assertEquals(1, durability.successfulSyncs)
    }

    @Test
    fun displayReadDistinguishesAbsentFromCorrupt() = runBlocking {
        val missing = "draft_images/${DRAFT_ID.value}/missing.jpg"
        assertEquals(RetainedImageReadResult.Absent, store.readForDisplay(missing))

        val corrupt = writeImage("corrupt.jpg", byteArrayOf(0x46, 0x53, 0x45, 0x31, 0x00))
        assertEquals(
            RetainedImageReadResult.IntegrityRejected,
            store.readForDisplay(corrupt),
        )
    }

    @Test
    fun pathTraversalNeverLeavesThePrivateStorage() = runBlocking {
        assertNull(store.readDecrypted("../secrets.txt"))
        assertEquals(PrivateImageDeletionResult.REJECTED_UNSAFE, store.delete("../draft_images"))
        assertFalse(store.encryptInPlace("../../etc/hosts"))
    }

    @Test
    fun symbolicLinksAreRejectedForReadEncryptAndDeleteWithoutTouchingTheirTarget() = runBlocking {
        val draftDirectory = File(filesDir, "draft_images/${DRAFT_ID.value}").apply {
            check(mkdirs() || isDirectory)
        }
        val sentinel = File(filesDir, "retained-image-sentinel/keep.jpg").apply {
            check(parentFile?.mkdirs() == true || parentFile?.isDirectory == true)
            writeBytes(CONTENT)
        }
        val leafPath = "draft_images/${DRAFT_ID.value}/linked.jpg"
        createSymbolicLinkOrSkip(File(filesDir, leafPath).toPath(), sentinel.toPath())

        assertEquals(RetainedImageReadResult.Unavailable, store.readForDisplay(leafPath))
        assertFalse(store.encryptInPlace(leafPath))
        assertEquals(PrivateImageDeletionResult.REJECTED_UNSAFE, store.delete(leafPath))
        assertArrayEquals(CONTENT, sentinel.readBytes())

        NoFollowFileTree.delete(draftDirectory)
        createSymbolicLinkOrSkip(draftDirectory.toPath(), sentinel.parentFile!!.toPath())
        val nestedPath = "draft_images/${DRAFT_ID.value}/keep.jpg"
        assertEquals(RetainedImageReadResult.Unavailable, store.readForDisplay(nestedPath))
        assertFalse(store.encryptInPlace(nestedPath))
        assertEquals(PrivateImageDeletionResult.REJECTED_UNSAFE, store.delete(nestedPath))
        assertArrayEquals(CONTENT, sentinel.readBytes())
    }

    @Test
    fun boundedReadRejectsOversizedSourceFromFileLengthWithoutAllocatingItsContents() = runBlocking {
        val relativePath = "draft_images/${DRAFT_ID.value}/oversized.jpg"
        val file = File(filesDir, relativePath)
        check(file.parentFile?.mkdirs() == true || file.parentFile?.isDirectory == true)
        RandomAccessFile(file, "rw").use { randomAccess ->
            randomAccess.setLength(
                DocumentUploadDecodePolicy.MAX_SOURCE_BYTES.toLong() +
                    RetainedImageCipher.ENVELOPE_OVERHEAD_BYTES + 1L,
            )
        }

        assertNull(
            store.readDecrypted(
                relativePath,
                DocumentUploadDecodePolicy.MAX_SOURCE_BYTES,
            ),
        )
    }

    private fun writeImage(name: String, content: ByteArray): String {
        val relativePath = "draft_images/${DRAFT_ID.value}/$name"
        val file = File(filesDir, relativePath)
        check(file.parentFile?.mkdirs() == true || file.parentFile?.isDirectory == true)
        file.writeBytes(content)
        return relativePath
    }

    private class FailFirstDeletionSync : PrivateDeletionDurability() {
        var syncAttempts: Int = 0
            private set
        var successfulSyncs: Int = 0
            private set

        override fun syncAfterDeletion(target: File, durabilityRoot: File) {
            syncAttempts++
            if (syncAttempts == 1) throw IOException("fallo fsync posterior al unlink")
            super.syncAfterDeletion(target, durabilityRoot)
            successfulSyncs++
        }
    }

    private companion object {
        val DRAFT_ID: DraftId = DraftId.from(UUID(0L, 99L))
        val CONTENT = ByteArray(512) { index -> (index % 127).toByte() }
    }
}
