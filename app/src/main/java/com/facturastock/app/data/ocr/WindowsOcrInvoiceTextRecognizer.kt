package com.facturastock.app.data.ocr

import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.platform.AppDirectories
import com.facturastock.app.data.files.PrivateFileResolver
import com.facturastock.app.domain.error.OcrError
import com.facturastock.app.domain.error.OcrException
import com.facturastock.app.domain.model.InvoiceTextDocument
import com.facturastock.app.domain.repository.InvoiceTextRecognizer
import com.facturastock.app.domain.repository.OcrImageFile
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * OCR local con el motor integrado de Windows 10/11 (`Windows.Media.Ocr`), sin descargas ni red.
 * Todo el lote se reconoce en un único proceso de PowerShell, en el orden recibido; la carpeta
 * de trabajo temporal se borra siempre, incluso ante fallo o cancelación.
 *
 * Errores, igual que con ML Kit: [OcrError.ModelUnavailable] si el equipo no es Windows, falta
 * PowerShell/WinRT o no hay ningún idioma OCR instalado; [OcrError.RecognitionFailed] para el
 * resto. La cancelación se propaga tal cual y mata el proceso.
 */
class WindowsOcrInvoiceTextRecognizer internal constructor(
    private val directories: AppDirectories,
    private val dispatcherProvider: DispatcherProvider,
    private val engine: WindowsOcrEngine,
) : InvoiceTextRecognizer {
    @Inject
    constructor(
        directories: AppDirectories,
        dispatcherProvider: DispatcherProvider,
    ) : this(directories, dispatcherProvider, PowerShellWindowsOcrEngine())

    override suspend fun recognize(pages: List<OcrImageFile>): InvoiceTextDocument = try {
        recognizeBatch(pages)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: OcrException) {
        throw failure
    } catch (failure: OutOfMemoryError) {
        // Mantiene el protocolo del run: el caso de uso recibe un fallo OCR normalizable y puede
        // cerrar su token persistido, en vez de dejar el borrador en OCR_PROCESSING.
        throw OcrException(OcrError.RecognitionFailed, failure)
    } catch (failure: Exception) {
        throw OcrException(OcrError.RecognitionFailed, failure)
    }

    private suspend fun recognizeBatch(pages: List<OcrImageFile>): InvoiceTextDocument {
        if (pages.isEmpty()) return InvoiceTextDocument(emptyList())
        engine.ensureAvailable()
        val json = withContext(dispatcherProvider.io) {
            val images = pages.map { page ->
                currentCoroutineContext().ensureActive()
                resolveImage(page)
            }
            val workDirectory = File(directories.cacheDir, "$WORK_DIRECTORY_PREFIX${UUID.randomUUID()}")
            if (!workDirectory.mkdirs()) throw IOException("No se pudo crear la carpeta temporal de OCR")
            try {
                engine.recognize(images, workDirectory)
            } finally {
                workDirectory.deleteRecursively()
            }
        }
        currentCoroutineContext().ensureActive()
        return withContext(dispatcherProvider.default) {
            val mappingContext = currentCoroutineContext()
            WindowsOcrMapper.mapDocument(WindowsOcrMapper.parse(json), pages) {
                mappingContext.ensureActive()
            }
        }
    }

    private fun resolveImage(page: OcrImageFile): File {
        val file = try {
            PrivateFileResolver.resolveInside(directories.filesDir, page.relativePath)
        } catch (failure: IOException) {
            throw OcrException(OcrError.RecognitionFailed, failure)
        }?.takeIf { candidate ->
            candidate.isFile && candidate.length() == page.fileSizeBytes
        } ?: throw OcrException(OcrError.RecognitionFailed)
        return file
    }

    private companion object {
        const val WORK_DIRECTORY_PREFIX = "windows-ocr-"
    }
}
