package com.facturastock.app.data.ocr

import android.content.Context
import android.net.Uri
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.data.files.PrivateFileResolver
import com.facturastock.app.domain.error.OcrError
import com.facturastock.app.domain.error.OcrException
import com.facturastock.app.domain.model.InvoiceTextDocument
import com.facturastock.app.domain.repository.InvoiceTextRecognizer
import com.facturastock.app.domain.repository.OcrImageFile
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * OCR latino integrado: el modelo `com.google.mlkit:text-recognition` está dentro del APK.
 * Se reutiliza un cliente para todo el lote, se procesan páginas secuencialmente y el cliente
 * se cierra exactamente una vez aun ante fallo o cancelación.
 */
class MlKitInvoiceTextRecognizer @Inject constructor(
    @ApplicationContext context: Context,
    private val dispatcherProvider: DispatcherProvider,
) : InvoiceTextRecognizer {
    private val rootDirectory: File = context.filesDir
    private val applicationContext: Context = context.applicationContext

    override suspend fun recognize(pages: List<OcrImageFile>): InvoiceTextDocument = try {
        recognizeBatch(pages)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: OcrException) {
        throw failure
    } catch (failure: MlKitException) {
        throw failure.toOcrException()
    } catch (failure: OutOfMemoryError) {
        // Mantiene el protocolo del run: el caso de uso recibe un fallo OCR normalizable y puede
        // cerrar su token persistido, en vez de dejar el borrador en OCR_PROCESSING.
        throw OcrException(OcrError.RecognitionFailed, failure)
    } catch (failure: Exception) {
        throw OcrException(OcrError.RecognitionFailed, failure)
    }

    private suspend fun recognizeBatch(pages: List<OcrImageFile>): InvoiceTextDocument {
        if (pages.isEmpty()) return InvoiceTextDocument(emptyList())
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val recognizedPages = pages.mapIndexed { pageIndex, page ->
                currentCoroutineContext().ensureActive()
                val input = loadInputImage(page)
                val result = recognizer.process(input).awaitCancellable()
                currentCoroutineContext().ensureActive()
                withContext(dispatcherProvider.default) {
                    val mappingContext = currentCoroutineContext()
                    MlKitInvoiceTextMapper.mapPage(pageIndex, page, result) {
                        mappingContext.ensureActive()
                    }
                }
            }
            return InvoiceTextDocument(recognizedPages)
        } finally {
            recognizer.close()
        }
    }

    private suspend fun loadInputImage(page: OcrImageFile): InputImage =
        withContext(dispatcherProvider.io) {
            currentCoroutineContext().ensureActive()
            val file = try {
                PrivateFileResolver.resolveInside(rootDirectory, page.relativePath)
            } catch (failure: IOException) {
                throw OcrException(OcrError.RecognitionFailed, failure)
            }?.takeIf { candidate ->
                candidate.isFile && candidate.length() == page.fileSizeBytes
            } ?: throw OcrException(OcrError.RecognitionFailed)

            try {
                InputImage.fromFilePath(applicationContext, Uri.fromFile(file))
            } catch (failure: IOException) {
                throw OcrException(OcrError.RecognitionFailed, failure)
            }
        }
}

private fun MlKitException.toOcrException(): OcrException {
    val mapped = when (errorCode) {
        MlKitException.UNAVAILABLE,
        MlKitException.UNSUPPORTED,
        -> OcrError.ModelUnavailable
        else -> OcrError.RecognitionFailed
    }
    return OcrException(mapped, this)
}
