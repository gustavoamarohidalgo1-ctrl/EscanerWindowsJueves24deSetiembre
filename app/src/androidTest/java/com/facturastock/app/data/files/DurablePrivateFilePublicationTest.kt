package com.facturastock.app.data.files

import android.content.Context
import android.os.Build
import android.system.OsConstants
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Regresiones de la barrera file-fsync -> rename -> directory-fsync. */
@RunWith(AndroidJUnit4::class)
class DurablePrivateFilePublicationTest {
    private lateinit var context: Context
    private lateinit var root: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        root = File(context.cacheDir, "durable-publication-${UUID.randomUUID()}")
        check(root.mkdirs())
    }

    @After
    fun tearDown() {
        NoFollowFileTree.delete(root)
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

    /** Regresión ejecutable en la matriz minSdk: API 26 no expone `OsConstants.O_CLOEXEC`. */
    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.O, maxSdkVersion = Build.VERSION_CODES.O)
    fun api26CanOpenAndSynchronizeAPrivateFileWithoutCloseOnExec() {
        val file = File(root, "api-26.bin").apply { writeBytes(SOURCE_BYTES) }

        DurablePrivateFilePublication.syncFile(file)

        assertArrayEquals(SOURCE_BYTES, file.readBytes())
    }

    @Test
    fun fileSyncRejectsASymlinkWithoutOpeningItsTarget() {
        val target = File(root, "sentinel.txt").apply { writeText("intacto") }
        val link = File(root, "published.bin")
        Files.createSymbolicLink(link.toPath(), target.toPath())

        val failure = runCatching {
            DurablePrivateFilePublication.syncFile(link)
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertEquals("intacto", target.readText())
    }

    @Test
    fun onlyExplicitFilesystemIncompatibilityIsBestEffort() {
        assertTrue(isUnsupportedDirectorySyncErrno(OsConstants.EINVAL))
        assertTrue(isUnsupportedDirectorySyncErrno(OsConstants.ENOTSUP))
        assertTrue(isUnsupportedDirectorySyncErrno(OsConstants.EOPNOTSUPP))

        assertFalse(isUnsupportedDirectorySyncErrno(OsConstants.EIO))
        assertFalse(isUnsupportedDirectorySyncErrno(OsConstants.ENOSPC))
        assertFalse(isUnsupportedDirectorySyncErrno(OsConstants.EROFS))
    }

    private companion object {
        val SOURCE_BYTES = ByteArray(4 * 1_024) { index -> (index % 251).toByte() }
    }
}
