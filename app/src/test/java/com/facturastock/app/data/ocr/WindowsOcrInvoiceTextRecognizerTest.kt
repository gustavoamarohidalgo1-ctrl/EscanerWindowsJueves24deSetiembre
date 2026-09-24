package com.facturastock.app.data.ocr

import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.platform.AppDirectories
import com.facturastock.app.domain.error.OcrError
import com.facturastock.app.domain.error.OcrException
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.repository.OcrImageFile
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WindowsOcrInvoiceTextRecognizerTest {
    private val root: File = Files.createTempDirectory("windows-ocr-test").toFile()
    private val directories = AppDirectories(root)

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `recognizes the batch in order through the engine and cleans the work directory`() = runBlocking {
        val first = storeImage("draft_images/d/one.jpg", 10)
        val second = storeImage("draft_images/d/two.jpg", 20)
        var received: List<File> = emptyList()
        var workDirectory: File? = null
        val recognizer = recognizer { images, work ->
            received = images
            workDirectory = work
            assertTrue(work.isDirectory)
            """{"pages":[
              {"index":0,"width":100,"height":100,"angle":0,"lines":[{"text":"uno","words":[{"text":"uno","x":1,"y":1,"w":10,"h":10}]}]},
              {"index":1,"width":100,"height":100,"angle":0,"lines":[{"text":"dos","words":[{"text":"dos","x":1,"y":1,"w":10,"h":10}]}]}
            ]}"""
        }

        val document = recognizer.recognize(listOf(first, second))

        assertEquals("uno\ndos", document.text)
        assertEquals(listOf(first.sourceImageId, second.sourceImageId), document.pages.map { it.sourceImageId })
        assertEquals(
            listOf(File(directories.filesDir, first.relativePath).canonicalFile, File(directories.filesDir, second.relativePath).canonicalFile),
            received,
        )
        assertFalse(workDirectory!!.exists())
    }

    @Test
    fun `empty batch never starts the engine`() = runBlocking {
        val recognizer = recognizer { _, _ -> fail("no debía ejecutarse"); "" }

        assertTrue(recognizer.recognize(emptyList()).pages.isEmpty())
    }

    @Test
    fun `size mismatch missing file or path traversal fail as recognition errors`() = runBlocking {
        val stored = storeImage("draft_images/d/page.jpg", 10)
        val recognizer = recognizer { _, _ -> fail("no debía ejecutarse"); "" }

        assertError(OcrError.RecognitionFailed) { recognizer.recognize(listOf(stored.copy(fileSizeBytes = 11))) }
        assertError(OcrError.RecognitionFailed) { recognizer.recognize(listOf(stored.copy(relativePath = "draft_images/d/missing.jpg"))) }
        assertError(OcrError.RecognitionFailed) { recognizer.recognize(listOf(stored.copy(relativePath = "../outside.jpg"))) }
    }

    @Test
    fun `non Windows hosts report the model as unavailable before touching files`() = runBlocking {
        val recognizer = WindowsOcrInvoiceTextRecognizer(
            directories,
            TestDispatchers,
            PowerShellWindowsOcrEngine(osName = "Mac OS X"),
        )

        assertError(OcrError.ModelUnavailable) { recognizer.recognize(listOf(page("draft_images/d/none.jpg", 1))) }
    }

    @Test
    fun `missing PowerShell executable reports the model as unavailable`() = runBlocking {
        val stored = storeImage("draft_images/d/page.jpg", 10)
        val recognizer = WindowsOcrInvoiceTextRecognizer(
            directories,
            TestDispatchers,
            PowerShellWindowsOcrEngine(osName = "Windows 11", powerShell = { File(root, "no-powershell.exe").absolutePath }),
        )

        assertError(OcrError.ModelUnavailable) { recognizer.recognize(listOf(stored)) }
        assertTrue(directories.cacheDir.listFiles().orEmpty().none { it.name.startsWith("windows-ocr-") })
    }

    @Test
    fun `engine failures and malformed output become recognition failures`() = runBlocking {
        val stored = storeImage("draft_images/d/page.jpg", 10)

        assertError(OcrError.RecognitionFailed) {
            recognizer { _, _ -> throw IllegalStateException("boom") }.recognize(listOf(stored))
        }
        assertError(OcrError.RecognitionFailed) {
            recognizer { _, _ -> "{not json" }.recognize(listOf(stored))
        }
        assertError(OcrError.RecognitionFailed) {
            recognizer { _, _ -> """{"pages":[]}""" }.recognize(listOf(stored))
        }
        assertError(OcrError.ModelUnavailable) {
            recognizer { _, _ -> throw OcrException(OcrError.ModelUnavailable) }.recognize(listOf(stored))
        }
    }

    @Test
    fun `cancellation propagates unchanged and still removes the work directory`() = runBlocking {
        val stored = storeImage("draft_images/d/page.jpg", 10)
        val started = CompletableDeferred<File>()
        val recognizer = recognizer { _, work ->
            started.complete(work)
            awaitCancellation()
        }

        val job = async { recognizer.recognize(listOf(stored)) }
        val work = started.await()
        job.cancel()
        try {
            job.await()
            fail("se esperaba cancelación")
        } catch (_: CancellationException) {
        }
        assertFalse(work.exists())
    }

    private fun recognizer(engine: WindowsOcrEngine) = WindowsOcrInvoiceTextRecognizer(directories, TestDispatchers, engine)

    private fun storeImage(relativePath: String, size: Int): OcrImageFile {
        val file = File(directories.filesDir, relativePath)
        file.parentFile.mkdirs()
        file.writeBytes(ByteArray(size))
        return page(relativePath, size.toLong())
    }

    private fun page(relativePath: String, size: Long) = OcrImageFile(
        sourceImageId = ImageId.from(UUID.randomUUID()),
        relativePath = relativePath,
        mimeType = "image/jpeg",
        widthPx = 100,
        heightPx = 100,
        fileSizeBytes = size,
    )

    private suspend fun assertError(expected: OcrError, block: suspend () -> Unit) {
        try {
            block()
            fail("se esperaba $expected")
        } catch (failure: OcrException) {
            assertSame(expected, failure.error)
        }
    }

    private object TestDispatchers : DispatcherProvider {
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = Dispatchers.Default
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
    }
}
