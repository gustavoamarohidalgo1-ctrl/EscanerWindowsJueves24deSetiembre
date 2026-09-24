package com.facturastock.app.data.files

import com.facturastock.app.data.files.FilesTestSupport.createSymbolicLinkOrSkip
import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Regresiones de la barrera file-fsync -> rename -> directory-fsync. */
class DurablePrivateFilePublicationTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var root: File

    @Before
    fun setUp() {
        root = tempFolder.newFolder("durable-publication").canonicalFile
    }

    @Test
    fun syncedRenameAndParentChainPreserveThePublishedBytes() {
        val directory = File(root, "created/leaf").apply { check(mkdirs()) }
        val source = File(directory, "content.tmp").apply { writeBytes(SOURCE_BYTES) }
        val destination = File(directory, "content.bin").apply { writeText("anterior") }

        DurablePrivateFilePublication.syncFile(source)
        DurablePrivateFilePublication.replaceByRename(source, destination)
        DurablePrivateFilePublication.syncFile(destination)
        DurablePrivateFilePublication.syncParentChainAfterRename(destination, root)

        assertFalse(source.exists())
        assertArrayEquals(SOURCE_BYTES, destination.readBytes())
    }

    @Test
    fun renameAcrossDirectoriesIsRejectedWithoutMovingTheSource() {
        val source = File(root, "a/content.tmp").apply {
            check(parentFile.mkdirs())
            writeBytes(SOURCE_BYTES)
        }
        val destination = File(root, "b/content.bin").apply { check(parentFile.mkdirs()) }

        val failure = runCatching {
            DurablePrivateFilePublication.replaceByRename(source, destination)
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertArrayEquals(SOURCE_BYTES, source.readBytes())
        assertFalse(destination.exists())
    }

    @Test
    fun parentChainOutsideTheDurabilityRootIsRejected() {
        val outside = tempFolder.newFolder("outside").canonicalFile
        val destination = File(outside, "content.bin").apply { writeBytes(SOURCE_BYTES) }

        val failure = runCatching {
            DurablePrivateFilePublication.syncParentChainAfterRename(destination, root)
        }.exceptionOrNull()

        assertTrue(failure is IOException)
    }

    @Test
    fun fileSyncRejectsASymlinkWithoutOpeningItsTarget() {
        val target = File(root, "sentinel.txt").apply { writeText("intacto") }
        val link = File(root, "published.bin")
        createSymbolicLinkOrSkip(link.toPath(), target.toPath())

        val failure = runCatching {
            DurablePrivateFilePublication.syncFile(link)
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertEquals("intacto", target.readText())
    }

    /**
     * En escritorio no hay `OsConstants`: se fijan los valores numéricos de Linux (EINVAL=22,
     * ENOTSUP/EOPNOTSUPP=95, EIO=5, ENOSPC=28, EROFS=30) y los de macOS (ENOTSUP=45,
     * EOPNOTSUPP=102).
     */
    @Test
    fun onlyExplicitFilesystemIncompatibilityIsBestEffort() {
        assertTrue(isUnsupportedDirectorySyncErrno(22))
        assertTrue(isUnsupportedDirectorySyncErrno(95))
        assertTrue(isUnsupportedDirectorySyncErrno(45))
        assertTrue(isUnsupportedDirectorySyncErrno(102))

        assertFalse(isUnsupportedDirectorySyncErrno(5))
        assertFalse(isUnsupportedDirectorySyncErrno(28))
        assertFalse(isUnsupportedDirectorySyncErrno(30))
    }

    /** El JDK no expone `errno`: la política equivalente se decide por el texto de `strerror`. */
    @Test
    fun onlyUnsupportedFailureMessagesAreBestEffortAndAccessDeniedNeverIs() {
        assertTrue(isUnsupportedDirectorySyncFailure(IOException("Invalid argument")))
        assertTrue(isUnsupportedDirectorySyncFailure(IOException("Operation not supported")))

        assertFalse(isUnsupportedDirectorySyncFailure(IOException("Input/output error")))
        assertFalse(isUnsupportedDirectorySyncFailure(IOException("No space left on device")))
        assertFalse(isUnsupportedDirectorySyncFailure(IOException("Read-only file system")))
        assertFalse(
            isUnsupportedDirectorySyncFailure(AccessDeniedException("dir", null, "Invalid argument")),
        )
    }

    private companion object {
        val SOURCE_BYTES = ByteArray(4 * 1_024) { index -> (index % 251).toByte() }
    }
}
