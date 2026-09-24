package com.facturastock.app.data.files

import com.facturastock.app.core.platform.AppDirectories
import com.facturastock.app.data.files.FilesTestSupport.createSymbolicLinkOrSkip
import com.facturastock.app.domain.model.PrivateImageDeletionResult
import com.facturastock.app.domain.model.id.DraftId
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalDraftFileStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var directories: AppDirectories
    private lateinit var filesDir: File
    private lateinit var store: LocalDraftFileStore

    @Before
    fun setUp() {
        directories = AppDirectories(tempFolder.newFolder("app").canonicalFile)
        filesDir = directories.filesDir
        store = LocalDraftFileStore(directories)
    }

    @Test
    fun deleteFilesDistinguishesDeletedMissingAndOutsideSandboxPaths() = runTest {
        val directory = "draft_images/${UUID.randomUUID()}"
        val relativePath = "$directory/page.jpg"
        File(filesDir, relativePath).apply {
            check(parentFile?.mkdirs() == true || parentFile?.isDirectory == true)
            writeBytes(byteArrayOf(1, 2, 3))
        }

        assertEquals(
            listOf(
                PrivateImageDeletionResult.DELETED,
                PrivateImageDeletionResult.ALREADY_ABSENT,
                PrivateImageDeletionResult.REJECTED_UNSAFE,
            ),
            store.deleteFiles(listOf(relativePath, relativePath, "../outside.jpg")),
        )
    }

    @Test
    fun deleteFilesDeletesOnlyTheLeafSymlinkAndRejectsAnIntermediateSymlink() = runTest {
        val token = UUID.randomUUID()
        val draftDirectory = File(filesDir, "draft_images/$token").apply {
            check(mkdirs() || isDirectory)
        }
        val sentinelDirectory = File(filesDir, "sentinel-$token").apply {
            check(mkdirs() || isDirectory)
        }
        val sentinel = File(sentinelDirectory, "keep.jpg").apply { writeBytes(byteArrayOf(7)) }
        val leafLink = File(draftDirectory, "leaf.jpg")
        val directoryLink = File(draftDirectory, "linked-dir")
        createSymbolicLinkOrSkip(leafLink.toPath(), sentinel.toPath())
        createSymbolicLinkOrSkip(directoryLink.toPath(), sentinelDirectory.toPath())

        assertEquals(
            listOf(
                PrivateImageDeletionResult.DELETED,
                PrivateImageDeletionResult.REJECTED_UNSAFE,
            ),
            store.deleteFiles(
                listOf(
                    "draft_images/$token/leaf.jpg",
                    "draft_images/$token/linked-dir/keep.jpg",
                ),
            ),
        )
        assertTrue(sentinel.isFile)
        assertFalse(Files.exists(leafLink.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.isSymbolicLink(directoryLink.toPath()))
    }

    @Test
    fun treeDeletionNeverFollowsNestedOrIntermediateSymlinks() = runTest {
        val draftId = DraftId.from(UUID.randomUUID())
        val sentinelDirectory = File(filesDir, "tree-sentinel-${draftId.value}").apply {
            check(mkdirs() || isDirectory)
        }
        val sentinel = File(sentinelDirectory, "keep.jpg").apply { writeBytes(byteArrayOf(9)) }
        val draftDirectory = File(filesDir, "draft_images/${draftId.value}").apply {
            check(mkdirs() || isDirectory)
        }
        val ocr = File(draftDirectory, "ocr").apply { check(mkdirs() || isDirectory) }
        createSymbolicLinkOrSkip(File(ocr, "external").toPath(), sentinelDirectory.toPath())

        store.deleteOcrVersions(draftId)
        assertFalse(Files.exists(ocr.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertTrue(sentinel.isFile)

        NoFollowFileTree.delete(draftDirectory)
        createSymbolicLinkOrSkip(draftDirectory.toPath(), sentinelDirectory.toPath())
        store.deleteOcrVersions(draftId)
        assertTrue(sentinel.isFile)
        assertTrue(Files.isSymbolicLink(draftDirectory.toPath()))

        store.deleteDraftTree(draftId)
        assertFalse(Files.exists(draftDirectory.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertTrue(sentinel.isFile)
    }

    @Test
    fun treeFsyncFailureAfterDeletionIsRetryableWhenTreeIsAlreadyAbsent() = runTest {
        val draftId = DraftId.from(UUID.randomUUID())
        val draftDirectory = File(filesDir, "draft_images/${draftId.value}")
        File(draftDirectory, "ocr/v1/page-0.jpg").apply {
            check(parentFile?.mkdirs() == true || parentFile?.isDirectory == true)
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val durability = FailFirstTreeDeletionSync()
        val failingStore = LocalDraftFileStore(
            directories = directories,
            mutationCoordinator = PrivateImageMutationCoordinator(),
            deletionDurability = durability,
        )

        assertFalse(failingStore.deleteDraftTreeIf(draftId, shouldDelete = { true }))
        assertTrue(Files.notExists(draftDirectory.toPath(), LinkOption.NOFOLLOW_LINKS))

        assertTrue(failingStore.deleteDraftTreeIf(draftId, shouldDelete = { true }))
        assertEquals(2, durability.syncAttempts)
        assertEquals(1, durability.successfulSyncs)
    }

    private class FailFirstTreeDeletionSync : PrivateDeletionDurability() {
        var syncAttempts: Int = 0
            private set
        var successfulSyncs: Int = 0
            private set

        override fun syncAfterDeletion(target: File, durabilityRoot: File) {
            syncAttempts++
            if (syncAttempts == 1) throw IOException("fallo fsync posterior al borrado del árbol")
            super.syncAfterDeletion(target, durabilityRoot)
            successfulSyncs++
        }
    }
}
