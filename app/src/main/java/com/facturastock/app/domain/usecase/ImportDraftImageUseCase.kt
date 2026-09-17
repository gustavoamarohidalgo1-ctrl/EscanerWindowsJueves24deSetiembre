package com.facturastock.app.domain.usecase

import com.facturastock.app.core.coroutines.SuspendMutex
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InvoiceImage
import com.facturastock.app.domain.model.acceptsImageMutations
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.observability.DisabledProductionObservability
import com.facturastock.app.domain.observability.InternalIdentifiers
import com.facturastock.app.domain.observability.OperationalAction
import com.facturastock.app.domain.observability.OperationalAuditEvent
import com.facturastock.app.domain.observability.OperationalOutcome
import com.facturastock.app.domain.observability.ProductionObservability
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.CapturedPageWrite
import com.facturastock.app.domain.repository.CapturedPageIntent
import com.facturastock.app.domain.repository.DraftFileStore
import com.facturastock.app.domain.repository.DraftImageImporter
import com.facturastock.app.domain.repository.ImportedImageFile
import com.facturastock.app.domain.repository.InvoiceDraftRepository
import java.util.concurrent.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Importa una imagen de factura como página de un borrador, ya sea elegida desde galería
 * (URI) o capturada con la cámara (bytes JPEG en memoria con su rotación de sensor).
 *
 * Destino de la página:
 * - Sin [replaceImageId]: la página se AÑADE al final (`pageIndex` = número de páginas
 *   actuales; un borrador nuevo empieza en 0). Así "Añadir página" mantiene el orden.
 * - Con [replaceImageId]: la página objetivo se REEMPLAZA conservando su `pageIndex`
 *   ("Repetir" una página concreta). Un objetivo inexistente falla de forma controlada
 *   (`StorageException`), nunca se sustituye silenciosamente.
 *
 * Secuencia todo o nada, compartida por ambas entradas:
 * 1. Exige un negocio activo y un borrador ya materializado. Antes de cualquier E/S de imagen
 *    comprueba que el borrador pertenezca a ese negocio y siga aceptando páginas. Un callback
 *    restaurado después de descartar el borrador falla de forma controlada y no lo resucita.
 * 2. Usa el `ImageId` estable reservado por la UI (o genera uno cuando no se proporcionó).
 *    Si ese ID reservado ya tiene un recibo durable para la misma intención APPEND/REPLACE,
 *    devuelve esa fila de forma idempotente sin volver a invocar el importador. El mismo ID con
 *    otra intención se rechaza aunque la página se haya reordenado o eliminado. En otro caso
 *    delega la validación, la limpieza de metadatos (sin geolocalización ni EXIF) y la copia del
 *    archivo: si lanza
 *    `FileException` no muta la página objetivo. La reserva `CREATED` fue materializada por el
 *    flujo que abrió cámara/galería y se conserva si la importación falla.
 * 3. Publica la intención APPEND o REPLACE por ID mediante `publishCapturedPage`. La posición,
 *    la página sustituida, la invalidación OCR/preparación y el estado se resuelven en una sola
 *    transacción Room; una edición concurrente nunca puede redirigir el reemplazo. La rotación
 *    registrada es la del sensor o del EXIF y el flujo vuelve a `CAPTURED`.
 * 4. Solo AHORA, con la nueva página ya validada y persistida, se borra el archivo de la
 *    página reemplazada — nunca otro — en mejor esfuerzo.
 *
 * Si la persistencia falla DESPUÉS de haberse copiado el archivo nuevo, ese archivo se
 * borra en mejor esfuerzo antes de relanzar el fallo — salvo que la base de datos ya lo
 * hubiera registrado (entonces se conserva, porque la base lo referencia).
 *
 * Devuelve el `InvoiceImage` persistido.
 *
 * La instancia productiva es singleton y serializa importaciones locales; la corrección frente
 * a cualquier otra mutación proviene de resolver la intención dentro de Room.
 */
class ImportDraftImageUseCase(
    private val appConfigurationRepository: AppConfigurationRepository,
    private val invoiceDraftRepository: InvoiceDraftRepository,
    private val draftImageImporter: DraftImageImporter,
    private val draftFileStore: DraftFileStore,
    private val uuidGenerator: UuidGenerator,
    private val observability: ProductionObservability = DisabledProductionObservability,
) {
    private val importMutex = SuspendMutex()

    /**
     * Entrada de galería: la imagen se lee desde [sourceUri] y toma la rotación de su EXIF.
     * [preferredImageId] permite reservar la identidad antes de abrir el picker y reconocer
     * exactamente el commit al restaurar la ruta, incluso cuando [replaceImageId] no es null.
     * [replaceSoleInvoiceScan] queda reservado al reintento explícito del flujo rápido de cámara.
     */
    suspend operator fun invoke(
        draftId: DraftId,
        sourceUri: String,
        replaceImageId: ImageId? = null,
        preferredImageId: ImageId? = null,
        replaceSoleInvoiceScan: Boolean = false,
    ): InvoiceImage =
        importAndPersist(
            draftId = draftId,
            sensorRotationDegrees = null,
            replaceImageId = replaceImageId,
            preferredImageId = preferredImageId,
            replaceSoleInvoiceScan = replaceSoleInvoiceScan,
        ) { imageId ->
            draftImageImporter.import(draftId, imageId, sourceUri)
        }

    /**
     * Entrada de cámara: la captura llega como JPEG en memoria y [rotationDegrees] queda
     * registrado en los metadatos para que la vista rote la imagen al mostrarla.
     * [preferredImageId] cumple la misma función durable que en la entrada de galería.
     */
    suspend operator fun invoke(
        draftId: DraftId,
        jpegBytes: ByteArray,
        rotationDegrees: Int,
        replaceImageId: ImageId? = null,
        preferredImageId: ImageId? = null,
        replaceSoleInvoiceScan: Boolean = false,
    ): InvoiceImage =
        importAndPersist(
            draftId = draftId,
            sensorRotationDegrees = rotationDegrees,
            replaceImageId = replaceImageId,
            preferredImageId = preferredImageId,
            replaceSoleInvoiceScan = replaceSoleInvoiceScan,
        ) { imageId ->
            draftImageImporter.importBytes(draftId, imageId, jpegBytes, rotationDegrees)
        }

    private suspend fun importAndPersist(
        draftId: DraftId,
        sensorRotationDegrees: Int?,
        replaceImageId: ImageId?,
        preferredImageId: ImageId? = null,
        replaceSoleInvoiceScan: Boolean,
        importImage: suspend (ImageId) -> ImportedImageFile,
    ): InvoiceImage =
        importMutex.withLock {
            importAndPersistLocked(
                draftId = draftId,
                sensorRotationDegrees = sensorRotationDegrees,
                replaceImageId = replaceImageId,
                preferredImageId = preferredImageId,
                replaceSoleInvoiceScan = replaceSoleInvoiceScan,
                importImage = importImage,
            )
        }

    private suspend fun importAndPersistLocked(
        draftId: DraftId,
        sensorRotationDegrees: Int?,
        replaceImageId: ImageId?,
        preferredImageId: ImageId?,
        replaceSoleInvoiceScan: Boolean,
        importImage: suspend (ImageId) -> ImportedImageFile,
    ): InvoiceImage {
        val imageId = preferredImageId ?: ImageId.from(uuidGenerator.newUuid())
        if (replaceSoleInvoiceScan && replaceImageId != null) {
            throw StorageException(
                StorageError.ConstraintConflict(
                    "El reintento del escaneo no acepta un objetivo multipágina",
                ),
            )
        }
        val publicationIntent = when {
            replaceSoleInvoiceScan -> CapturedPageIntent.ReplaceSoleInvoiceScan
            replaceImageId != null -> CapturedPageIntent.Replace(replaceImageId)
            else -> CapturedPageIntent.Append
        }
        val businessId = try {
            appConfigurationRepository.current().activeBusinessId
                ?: throw StorageException(StorageError.Unavailable)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            recordCapture(draftId, imageId, null, OperationalOutcome.FAILED, failure)
            throw failure
        }

        // El ID reservado por la UI es también la clave idempotente del commit. El preflight
        // consulta el recibo, no solo la fila mutable: así reconoce el intento exacto y rechaza
        // un SavedState con otra intención incluso tras reorder/delete o reinicio.
        if (preferredImageId != null) {
            try {
                invoiceDraftRepository.findPublishedCapturedPage(
                    imageId = imageId,
                    draftId = draftId,
                    businessId = businessId,
                    intent = publicationIntent,
                )?.let { publication ->
                    deleteUnreferencedBestEffort(
                        listOfNotNull(publication.replacedFilePath)
                            .filter { it != publication.image.filePath },
                    )
                    if (publicationIntent == CapturedPageIntent.ReplaceSoleInvoiceScan) {
                        deleteOcrVersionsBestEffort(draftId)
                    }
                    recordCapture(
                        draftId,
                        imageId,
                        businessId,
                        OperationalOutcome.SUCCEEDED,
                    )
                    return publication.image
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                recordCapture(draftId, imageId, businessId, OperationalOutcome.FAILED, failure)
                throw failure
            }
        }

        // El borrador se valida contra el negocio activo antes de abrir/copiar la imagen.
        // Así una ruta restaurada de otro tenant no alcanza ninguna mutación de filesystem.
        val draft = try {
            val existing = invoiceDraftRepository.findDraft(draftId)
                ?: throw StorageException(
                    StorageError.ConstraintConflict(
                        "El borrador ya no existe; la captura fue descartada",
                    ),
                )
            existing.also { draft ->
                if (draft.businessId != businessId) {
                    throw StorageException(
                        StorageError.ConstraintConflict(
                            "draftId pertenece a otro negocio activo",
                        ),
                    )
                }
                val acceptsInvoiceScanRetake =
                    publicationIntent == CapturedPageIntent.ReplaceSoleInvoiceScan &&
                        draft.status in INVOICE_SCAN_RETAKE_STATUSES
                if (!draft.status.acceptsImageMutations && !acceptsInvoiceScanRetake) {
                    throw StorageException(
                        StorageError.ConstraintConflict(
                            "El borrador ya no admite cambios de imágenes",
                        ),
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            recordCapture(draftId, imageId, businessId, OperationalOutcome.FAILED, failure)
            throw failure
        }

        // Todo fallo del importador conserva la reserva CREATED preexistente, pero no publica
        // imagen alguna; la página objetivo previa también sobrevive intacta.
        val imported = try {
            importImage(imageId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            recordCapture(draftId, imageId, businessId, OperationalOutcome.FAILED, failure)
            throw failure
        }
        val importedPath = imported.relativePath

        try {
            val publication = invoiceDraftRepository.publishCapturedPage(
                page = CapturedPageWrite(
                    imageId = imageId,
                    draftId = draft.draftId,
                    businessId = draft.businessId,
                    filePath = importedPath,
                    sha256 = imported.sha256,
                    mimeType = imported.mimeType,
                    widthPx = imported.widthPx,
                    heightPx = imported.heightPx,
                    fileSizeBytes = imported.fileSizeBytes,
                    rotationDegrees = sensorRotationDegrees ?: imported.rotationDegrees,
                ),
                intent = publicationIntent,
            )

            deleteUnreferencedBestEffort(
                listOfNotNull(publication.replacedFilePath)
                    .filter { it != importedPath },
            )
            if (publicationIntent == CapturedPageIntent.ReplaceSoleInvoiceScan) {
                deleteOcrVersionsBestEffort(draftId)
            }
            recordCapture(draftId, imageId, businessId, OperationalOutcome.SUCCEEDED)
            return publication.image
        } catch (cancelled: CancellationException) {
            cleanupImportedFileIfUnreferenced(importedPath)
            throw cancelled
        } catch (failure: Exception) {
            // Incluso si el puerto lanzó después del commit, Room es la fuente de verdad:
            // solo se limpia el archivo cuando una relectura global confirma que ninguna fila
            // referencia esa ruta (incluidos aliases legacy). Ante duda se conserva.
            cleanupImportedFileIfUnreferenced(importedPath)
            recordCapture(draftId, imageId, businessId, OperationalOutcome.FAILED, failure)
            throw failure
        }
    }

    private suspend fun cleanupImportedFileIfUnreferenced(importedPath: String) {
        withContext(NonCancellable) {
            val referenced = try {
                invoiceDraftRepository.isImagePathReferenced(importedPath)
            } catch (_: Exception) {
                // Ante una lectura incierta no se arriesga un archivo potencialmente publicado.
                return@withContext
            }
            if (!referenced) {
                try {
                    draftFileStore.deleteFiles(listOf(importedPath))
                } catch (_: Exception) {
                    // Mejor esfuerzo: nunca se enmascara el fallo o la cancelación originales.
                }
            }
        }
    }

    /**
     * Una ruta sustituida puede seguir viva en una fila legacy alias. Solo entrega al almacén
     * aquellas cuya ausencia de referencias pudo comprobarse; una lectura incierta conserva.
     */
    private suspend fun deleteUnreferencedBestEffort(relativePaths: List<String>) {
        val safeToDelete = mutableListOf<String>()
        relativePaths.distinct().forEach { relativePath ->
            val referenced = try {
                invoiceDraftRepository.isImagePathReferenced(relativePath)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                true
            }
            if (!referenced) safeToDelete += relativePath
        }
        deleteBestEffort(safeToDelete)
    }

    /** El snapshot Room ya fue invalidado; los derivados de archivo son regenerables. */
    private suspend fun deleteOcrVersionsBestEffort(draftId: DraftId) {
        withContext(NonCancellable) {
            try {
                draftFileStore.deleteOcrVersions(draftId)
            } catch (_: Exception) {
                // Mejor esfuerzo: el nuevo original durable nunca se revierte por un huérfano.
            }
        }
    }

    private suspend fun recordCapture(
        draftId: DraftId,
        imageId: ImageId,
        businessId: BusinessId?,
        outcome: OperationalOutcome,
        failure: Throwable? = null,
    ) {
        observability.record(
            OperationalAuditEvent(
                action = OperationalAction.INVOICE_CAPTURE,
                outcome = outcome,
                identifiers = InternalIdentifiers(
                    businessId = businessId,
                    draftId = draftId,
                    imageId = imageId,
                ),
            ),
            failure,
        )
    }

    /** Borrado en mejor esfuerzo: la cancelación se relanza; cualquier otro fallo se tolera. */
    private suspend fun deleteBestEffort(relativePaths: List<String>) {
        if (relativePaths.isEmpty()) return
        try {
            draftFileStore.deleteFiles(relativePaths)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Mejor esfuerzo: los huérfanos se toleran y nunca enmascaran el resultado.
        }
    }

    private companion object {
        val INVOICE_SCAN_RETAKE_STATUSES = setOf(
            DraftStatus.CAPTURED,
            DraftStatus.ERROR,
            DraftStatus.OCR_READY,
            DraftStatus.NEEDS_REVIEW,
        )
    }
}
