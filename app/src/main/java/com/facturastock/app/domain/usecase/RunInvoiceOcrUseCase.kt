package com.facturastock.app.domain.usecase

import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.error.FileException
import com.facturastock.app.domain.error.OcrError
import com.facturastock.app.domain.error.OcrException
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InvoiceImage
import com.facturastock.app.domain.model.InvoiceOcrSnapshot
import com.facturastock.app.domain.model.InvoiceTextDocument
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.model.id.OcrRunId
import com.facturastock.app.domain.observability.DisabledProductionObservability
import com.facturastock.app.domain.observability.InternalIdentifiers
import com.facturastock.app.domain.observability.OperationalAction
import com.facturastock.app.domain.observability.OperationalAuditEvent
import com.facturastock.app.domain.observability.OperationalOutcome
import com.facturastock.app.domain.observability.ProductionObservability
import com.facturastock.app.domain.repository.InvoiceDraftRepository
import com.facturastock.app.domain.repository.InvoiceImagePreprocessor
import com.facturastock.app.domain.repository.InvoiceOcrSnapshotRepository
import com.facturastock.app.domain.repository.InvoiceTextRecognizer
import com.facturastock.app.domain.repository.OcrImageFile
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Etapas observables que corresponden a trabajo real del pipeline OCR local. */
enum class InvoiceOcrStage {
    PREPARING,
    READING,
    MERGING_PAGES,
}

/** Identidad y metadatos no sensibles de una ejecución OCR publicada completamente. */
data class InvoiceOcrRunResult(
    val draftId: DraftId,
    val runId: OcrRunId,
    val pageCount: Int,
    val completedAt: Instant,
) {
    init {
        require(pageCount > 0) { "Una ejecución OCR completada debe contener páginas" }
        require(!completedAt.isBefore(Instant.EPOCH)) {
            "completedAt no puede ser anterior al epoch"
        }
    }
}

/**
 * Orquesta un OCR completo sobre un único snapshot correlativo de las páginas del borrador.
 *
 * Room es la fuente de verdad de la exclusión: el CAS se reclama antes de preprocesar y tanto
 * cancelación como fallo solo pueden cerrar el mismo [OcrRunId]. El snapshot y OCR_READY se
 * publican juntos por [InvoiceOcrSnapshotRepository], por lo que nunca queda visible un resultado
 * parcial. Una invocación repetida después del commit devuelve los metadatos del snapshot ya
 * publicado sin volver a ejecutar el motor.
 */
class RunInvoiceOcrUseCase(
    private val invoiceDraftRepository: InvoiceDraftRepository,
    private val invoiceImagePreprocessor: InvoiceImagePreprocessor,
    private val invoiceTextRecognizer: InvoiceTextRecognizer,
    private val invoiceOcrSnapshotRepository: InvoiceOcrSnapshotRepository,
    private val uuidGenerator: UuidGenerator,
    private val appClock: AppClock,
    private val applyImageRetentionAfterOcr: ApplyImageRetentionAfterOcrUseCase,
    private val activityRegistry: OcrRunActivityRegistry = OcrRunActivityRegistry(),
    private val observability: ProductionObservability = DisabledProductionObservability,
) {
    suspend operator fun invoke(
        draftId: DraftId,
        onStage: suspend (InvoiceOcrStage) -> Unit = {},
    ): InvoiceOcrRunResult {
        val draft = invoiceDraftRepository.findDraft(draftId)
            ?: throw OcrException(OcrError.DraftNotOpen)

        if (draft.status in PUBLISHED_OCR_STATUSES && draft.confirmedPurchaseId == null) {
            val restored = invoiceOcrSnapshotRepository.find(draftId)
                ?.toRunResult()
                ?: throw OcrException(OcrError.RecognitionFailed)
            observability.record(
                OperationalAuditEvent(
                    action = OperationalAction.INVOICE_OCR,
                    outcome = OperationalOutcome.ALREADY_APPLIED,
                    identifiers = InternalIdentifiers(
                        businessId = draft.businessId,
                        draftId = draftId,
                    ),
                ),
            )
            return restored
        }
        if (
            draft.status !in OPEN_OCR_STATUSES ||
            draft.activeOcrRunId != null ||
            draft.confirmedPurchaseId != null
        ) {
            throw OcrException(OcrError.DraftNotOpen)
        }

        // La lectura es la validación barata previa al CAS; ningún píxel se decodifica todavía.
        val sourceImages = invoiceDraftRepository.observeImages(draftId).first()
        validateSourceImages(draftId, draft.businessId, sourceImages)

        val runId = OcrRunId.from(uuidGenerator.newUuid())
        var claimed = false
        try {
            currentCoroutineContext().ensureActive()
            claimed = withContext(NonCancellable) {
                invoiceDraftRepository.beginOcrRun(draftId, runId)
            }
            if (!claimed) throw OcrException(OcrError.DraftNotOpen)
            activityRegistry.markActive(draftId, runId)
            validateUnchangedSourceImages(
                expected = sourceImages,
                current = invoiceDraftRepository.observeImages(draftId).first(),
            )

            notifyStage(InvoiceOcrStage.PREPARING, onStage)
            val preparedPages = invoiceImagePreprocessor.preprocess(draftId, sourceImages)
            validatePreparedBatch(sourceImages, preparedPages)

            notifyStage(InvoiceOcrStage.READING, onStage)
            val document = invoiceTextRecognizer.recognize(preparedPages)
            validateRecognizedDocument(preparedPages, document)
            if (document.pages.all { page -> page.text.isBlank() }) {
                throw OcrException(OcrError.NoTextDetected)
            }
            validateUnchangedSourceImages(
                expected = sourceImages,
                current = invoiceDraftRepository.observeImages(draftId).first(),
            )

            notifyStage(InvoiceOcrStage.MERGING_PAGES, onStage)
            val completedAt = appClock.now()
            currentCoroutineContext().ensureActive()
            val published = invoiceOcrSnapshotRepository.publish(
                InvoiceOcrSnapshot(
                    draftId = draftId,
                    runId = runId,
                    completedAt = completedAt,
                    document = document,
                ),
            )
            if (!published) throw OcrException(OcrError.DraftNotOpen)

            // Ciclo de vida de imágenes: con AFTER_OCR los originales se borran al publicarse
            // el run (mejor esfuerzo); las versiones OCR de trabajo sobreviven hasta la
            // confirmación de la compra, que es quien las purga.
            applyImageRetentionAfterOcr(sourceImages)

            val result = InvoiceOcrRunResult(
                draftId = draftId,
                runId = runId,
                pageCount = document.pages.size,
                completedAt = completedAt,
            )
            observability.record(
                OperationalAuditEvent(
                    action = OperationalAction.INVOICE_OCR,
                    outcome = OperationalOutcome.SUCCEEDED,
                    identifiers = InternalIdentifiers(
                        businessId = draft.businessId,
                        draftId = draftId,
                    ),
                ),
            )
            return result
        } catch (cancellation: CancellationException) {
            if (claimed) {
                finishPreservingFailure(
                    draftId = draftId,
                    runId = runId,
                    status = DraftStatus.CAPTURED,
                    primaryFailure = cancellation,
                )
            }
            throw cancellation
        } catch (failure: OutOfMemoryError) {
            // El límite de bitmap reduce el riesgo, pero ML Kit y las estructuras de texto son
            // nativas/externas. Normalizar aquí garantiza que el CAS persistido también se cierre
            // si una asignación falla en cualquier punto del pipeline anterior a la publicación.
            val normalized = OcrException(OcrError.RecognitionFailed, failure)
            if (claimed) {
                finishPreservingFailure(
                    draftId = draftId,
                    runId = runId,
                    status = DraftStatus.ERROR,
                    lastError = normalized.safeOcrRunCode(),
                    primaryFailure = normalized,
                )
            }
            observability.record(
                OperationalAuditEvent(
                    action = OperationalAction.INVOICE_OCR,
                    outcome = OperationalOutcome.FAILED,
                    identifiers = InternalIdentifiers(
                        businessId = draft.businessId,
                        draftId = draftId,
                    ),
                ),
                normalized,
            )
            throw normalized
        } catch (failure: Exception) {
            if (claimed) {
                finishPreservingFailure(
                    draftId = draftId,
                    runId = runId,
                    status = DraftStatus.ERROR,
                    lastError = failure.safeOcrRunCode(),
                    primaryFailure = failure,
                )
            }
            observability.record(
                OperationalAuditEvent(
                    action = OperationalAction.INVOICE_OCR,
                    outcome = OperationalOutcome.FAILED,
                    identifiers = InternalIdentifiers(
                        businessId = draft.businessId,
                        draftId = draftId,
                    ),
                ),
                failure,
            )
            throw failure
        } finally {
            if (claimed) activityRegistry.markFinished(draftId, runId)
        }
    }

    private suspend fun notifyStage(
        stage: InvoiceOcrStage,
        observer: suspend (InvoiceOcrStage) -> Unit,
    ) {
        currentCoroutineContext().ensureActive()
        observer(stage)
        currentCoroutineContext().ensureActive()
    }

    /** Un fallo de cleanup queda suprimido y nunca reemplaza la cancelación o fallo principal. */
    private suspend fun finishPreservingFailure(
        draftId: DraftId,
        runId: OcrRunId,
        status: DraftStatus,
        lastError: String? = null,
        primaryFailure: Throwable,
    ) {
        try {
            withContext(NonCancellable) {
                invoiceDraftRepository.finishOcrRun(
                    draftId = draftId,
                    runId = runId,
                    newStatus = status,
                    lastError = lastError,
                )
            }
        } catch (cleanupFailure: Exception) {
            primaryFailure.addSuppressed(cleanupFailure)
        }
    }

    private companion object {
        val PUBLISHED_OCR_STATUSES = setOf(DraftStatus.OCR_READY, DraftStatus.NEEDS_REVIEW)
        val OPEN_OCR_STATUSES = setOf(DraftStatus.CAPTURED, DraftStatus.ERROR)
    }
}

private fun validateSourceImages(
    draftId: DraftId,
    businessId: BusinessId,
    images: List<InvoiceImage>,
) {
    if (images.isEmpty()) throw OcrException(OcrError.RecognitionFailed)
    val seenImageIds = HashSet<ImageId>(images.size)
    val valid = images.indices.all { index ->
        val image = images[index]
        image.pageIndex == index &&
            seenImageIds.add(image.imageId) &&
            image.draftId == draftId &&
            image.businessId == businessId
    }
    if (!valid) throw OcrException(OcrError.RecognitionFailed)
}

/**
 * La igualdad de la lista compara orden y todos los metadatos de [InvoiceImage]: identidad,
 * archivo/hash, dimensiones, rotación, recorte, tamaño, negocio y marca de creación.
 */
private fun validateUnchangedSourceImages(
    expected: List<InvoiceImage>,
    current: List<InvoiceImage>,
) {
    if (current != expected) throw OcrException(OcrError.RecognitionFailed)
}

private fun validatePreparedBatch(
    sourceImages: List<InvoiceImage>,
    preparedPages: List<OcrImageFile>,
) {
    val valid = preparedPages.size == sourceImages.size &&
        preparedPages.indices.all { index ->
            val page = preparedPages[index]
            page.sourceImageId == sourceImages[index].imageId &&
                page.relativePath.isNotBlank() &&
                page.mimeType.isNotBlank() &&
                page.widthPx > 0 &&
                page.heightPx > 0 &&
                page.fileSizeBytes > 0L
        }
    if (!valid) throw OcrException(OcrError.RecognitionFailed)
}

private fun validateRecognizedDocument(
    preparedPages: List<OcrImageFile>,
    document: InvoiceTextDocument,
) {
    val valid = document.pages.size == preparedPages.size &&
        document.pages.indices.all { index ->
            val source = preparedPages[index]
            val recognized = document.pages[index]
            recognized.pageIndex == index &&
                recognized.sourceImageId == source.sourceImageId &&
                recognized.widthPx == source.widthPx &&
                recognized.heightPx == source.heightPx
        }
    if (!valid) throw OcrException(OcrError.RecognitionFailed)
}

private fun InvoiceOcrSnapshot.toRunResult(): InvoiceOcrRunResult = InvoiceOcrRunResult(
    draftId = draftId,
    runId = runId,
    pageCount = document.pages.size,
    completedAt = completedAt,
)

/** Solo se persisten códigos cerrados; nunca mensajes, texto reconocido, IDs ni rutas. */
private fun Exception.safeOcrRunCode(): String = when (this) {
    is OcrException -> when (error) {
        OcrError.ModelUnavailable -> "OCR_MODEL_UNAVAILABLE"
        OcrError.NoTextDetected -> "OCR_NO_TEXT"
        OcrError.DraftNotOpen -> "OCR_DRAFT_NOT_OPEN"
        OcrError.RecognitionFailed -> "OCR_RECOGNITION_FAILED"
    }
    is FileException -> "OCR_PREPARATION_FAILED"
    is StorageException -> "OCR_STORAGE_FAILED"
    else -> "OCR_PIPELINE_FAILED"
}
