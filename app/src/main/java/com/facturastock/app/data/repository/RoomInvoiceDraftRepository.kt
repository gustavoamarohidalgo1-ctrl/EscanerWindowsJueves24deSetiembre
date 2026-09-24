package com.facturastock.app.data.repository

import com.facturastock.app.data.local.withTransaction
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.entity.CapturedPagePublicationEntity
import com.facturastock.app.data.local.dao.InvoiceDraftDao
import com.facturastock.app.data.local.dao.InvoiceImageDao
import com.facturastock.app.data.local.dao.InvoiceLineDao
import com.facturastock.app.data.local.mapper.toDomain
import com.facturastock.app.data.local.mapper.toEntity
import com.facturastock.app.data.local.storageCatching
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.ImageCrop
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.InvoiceImage
import com.facturastock.app.domain.model.InvoiceLine
import com.facturastock.app.domain.model.acceptsImageMutations
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.model.id.OcrRunId
import com.facturastock.app.domain.repository.CapturedPageWrite
import com.facturastock.app.domain.repository.CapturedPageIntent
import com.facturastock.app.domain.repository.DeletedDraftImage
import com.facturastock.app.domain.repository.InvoiceDraftRepository
import com.facturastock.app.domain.repository.PublishedCapturedPage
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Implementación Room del agregado de borrador. Las mutaciones de imágenes y líneas tocan el
 * `updatedAt` del borrador padre dentro de la misma transacción (`withTransaction`), de modo
 * que un fallo revierte todo el lote.
 */
class RoomInvoiceDraftRepository @Inject constructor(
    private val database: FacturaStockDatabase,
    private val invoiceDraftDao: InvoiceDraftDao,
    private val invoiceImageDao: InvoiceImageDao,
    private val invoiceLineDao: InvoiceLineDao,
    private val dispatchers: DispatcherProvider,
    private val clock: AppClock,
) : InvoiceDraftRepository {
    private val preparedPurchaseDao = database.preparedPurchaseDao()
    private val capturedPagePublicationDao = database.capturedPagePublicationDao()
    private val ocrSnapshotDao = database.invoiceOcrSnapshotDao()
    private val headerEditDao = database.invoiceHeaderEditDao()
    private val linesEditDao = database.invoiceLinesEditDao()

    override suspend fun createDraft(draft: InvoiceDraft): InvoiceDraft =
        withContext(dispatchers.io) {
            storageCatching {
                val now = clock.now()
                val stamped = draft.copy(createdAt = now, updatedAt = now)
                invoiceDraftDao.insert(stamped.toEntity())
                stamped
            }
        }

    override suspend fun updateDraft(draft: InvoiceDraft): Boolean = withContext(dispatchers.io) {
        storageCatching {
            val now = clock.now()
            val stamped = draft.copy(updatedAt = now)
            database.withTransaction {
                val current = invoiceDraftDao.findById(stamped.draftId.value)
                if (current == null) {
                    false
                } else {
                    invalidatePreparedPurchaseForEdit(current.draftId, now.toEpochMilli())
                    // Un update genérico de un borrador ya preparado es una edición: no puede
                    // conservar READY_TO_POST después de borrar su instantánea congelada.
                    val editable = if (current.status == DraftStatus.READY_TO_POST.name) {
                        stamped.copy(status = DraftStatus.NEEDS_REVIEW)
                    } else {
                        stamped
                    }
                    invoiceDraftDao.update(editable.toEntity())
                    true
                }
            }
        }
    }

    override suspend fun beginOcrRun(draftId: DraftId, runId: OcrRunId): Boolean =
        withContext(dispatchers.io) {
            storageCatching {
                invoiceDraftDao.beginOcrRun(
                    draftId = draftId.value,
                    runId = runId.value,
                    capturedStatus = DraftStatus.CAPTURED.name,
                    errorStatus = DraftStatus.ERROR.name,
                    processingStatus = DraftStatus.OCR_PROCESSING.name,
                    updatedAt = clock.now().toEpochMilli(),
                ) > 0
            }
        }

    override suspend fun finishOcrRun(
        draftId: DraftId,
        runId: OcrRunId,
        newStatus: DraftStatus,
        lastError: String?,
    ): Boolean = withContext(dispatchers.io) {
        require(
            newStatus == DraftStatus.CAPTURED ||
                newStatus == DraftStatus.OCR_READY ||
                newStatus == DraftStatus.ERROR,
        ) { "Estado final OCR inválido: $newStatus" }
        storageCatching {
            invoiceDraftDao.finishOcrRun(
                draftId = draftId.value,
                runId = runId.value,
                processingStatus = DraftStatus.OCR_PROCESSING.name,
                newStatus = newStatus.name,
                lastError = lastError,
                updatedAt = clock.now().toEpochMilli(),
            ) > 0
        }
    }

    override suspend fun resetInterruptedOcr(
        draftId: DraftId,
        expectedRunId: OcrRunId?,
    ): Boolean = withContext(dispatchers.io) {
        storageCatching {
            invoiceDraftDao.resetInterruptedOcr(
                draftId = draftId.value,
                expectedRunId = expectedRunId?.value,
                processingStatus = DraftStatus.OCR_PROCESSING.name,
                capturedStatus = DraftStatus.CAPTURED.name,
                updatedAt = clock.now().toEpochMilli(),
            ) > 0
        }
    }

    override suspend fun findDraft(draftId: DraftId): InvoiceDraft? = withContext(dispatchers.io) {
        storageCatching { invoiceDraftDao.findById(draftId.value)?.toDomain() }
    }

    override fun observeDraft(draftId: DraftId): Flow<InvoiceDraft?> =
        invoiceDraftDao.observeById(draftId.value)
            .map { it?.toDomain() }
            .flowOn(dispatchers.io)

    override fun observeDrafts(
        businessId: BusinessId,
        status: DraftStatus?,
    ): Flow<List<InvoiceDraft>> {
        val source = if (status == null) {
            invoiceDraftDao.observeForBusiness(businessId.value)
        } else {
            invoiceDraftDao.observeForBusinessByStatus(businessId.value, status.name)
        }
        return source
            .map { list -> list.map { it.toDomain() } }
            .flowOn(dispatchers.io)
    }

    override suspend fun deleteDraft(draftId: DraftId): Boolean = withContext(dispatchers.io) {
        storageCatching { invoiceDraftDao.deleteById(draftId.value) > 0 }
    }

    override suspend fun publishCapturedPage(
        page: CapturedPageWrite,
        intent: CapturedPageIntent,
    ): PublishedCapturedPage =
        withContext(dispatchers.io) {
            storageCatching {
                page.validationFailure()?.let(::publicationConflict)
                if (intent is CapturedPageIntent.Replace && intent.targetImageId == page.imageId) {
                    publicationConflict("Una captura no puede reemplazarse a sí misma")
                }
                val now = clock.now()
                database.withTransaction {
                    capturedPagePublicationDao.findByImageId(page.imageId.value)?.let { receipt ->
                        if (!receipt.matches(page, intent)) {
                            publicationConflict("imageId ya fue consumido por otra captura o intención")
                        }
                        val alreadyPublished = invoiceImageDao.findById(page.imageId.value)
                            ?: publicationConflict("La publicación idempotente ya no está activa")
                        checkPublishedIdentity(alreadyPublished.toDomain(), page)
                        return@withTransaction PublishedCapturedPage(
                            image = alreadyPublished.toDomain(),
                            replacedFilePath = receipt.replacedFilePath,
                        )
                    }

                    val alreadyPublished = invoiceImageDao.findById(page.imageId.value)
                    if (alreadyPublished != null) {
                        publicationConflict("imageId legacy ocupado sin recibo de publicación")
                    }

                    val current = invoiceImageDao.listForDraft(page.draftId.value)
                    val replaced = when (intent) {
                        CapturedPageIntent.Append -> null
                        is CapturedPageIntent.Replace -> {
                            current.firstOrNull { it.imageId == intent.targetImageId.value }
                                ?: throw StorageException(StorageError.Unavailable)
                        }
                        CapturedPageIntent.ReplaceSoleInvoiceScan -> {
                            current.singleOrNull()
                                ?: publicationConflict(
                                    "El reintento del escaneo exige exactamente una página",
                                )
                        }
                    }
                    if (intent == CapturedPageIntent.ReplaceSoleInvoiceScan) {
                        val claimed = invoiceDraftDao.claimSoleInvoiceScanRetake(
                            draftId = page.draftId.value,
                            capturedStatus = DraftStatus.CAPTURED.name,
                            errorStatus = DraftStatus.ERROR.name,
                            ocrReadyStatus = DraftStatus.OCR_READY.name,
                            reviewStatus = DraftStatus.NEEDS_REVIEW.name,
                        )
                        if (claimed != 1) {
                            publicationConflict(
                                "El borrador dejó de admitir el reintento del escaneo",
                            )
                        }
                    }
                    val targetPageIndex = replaced?.pageIndex ?: current.size
                    val stamped = InvoiceImage(
                        imageId = page.imageId,
                        draftId = page.draftId,
                        businessId = page.businessId,
                        pageIndex = targetPageIndex,
                        filePath = page.filePath,
                        sha256 = page.sha256,
                        mimeType = page.mimeType,
                        widthPx = page.widthPx,
                        heightPx = page.heightPx,
                        fileSizeBytes = page.fileSizeBytes,
                        rotationDegrees = page.rotationDegrees,
                        createdAt = now,
                    )
                    invalidateImageDerivedState(
                        draftId = page.draftId.value,
                        expectedBusinessId = page.businessId.value,
                    )
                    replaced?.let { invoiceImageDao.deleteById(it.imageId) }
                    invoiceImageDao.insert(stamped.toEntity())
                    capturedPagePublicationDao.insert(
                        page.toPublicationEntity(
                            intent = intent,
                            replaceTargetImageId = replaced?.imageId,
                            replacedFilePath = replaced?.filePath,
                            publishedPageIndex = targetPageIndex,
                            publishedAt = now.toEpochMilli(),
                        ),
                    )
                    // Deliberadamente al final: si el reset falla, Room restaura la página y
                    // todas las evidencias OCR/preparación invalidadas por esta edición.
                    resetDraftAfterImageMutation(page.draftId.value, now.toEpochMilli())
                    PublishedCapturedPage(
                        image = stamped,
                        replacedFilePath = replaced?.filePath,
                    )
                }
            }
        }

    override suspend fun findPublishedCapturedPage(
        imageId: ImageId,
        draftId: DraftId,
        businessId: BusinessId,
        intent: CapturedPageIntent,
    ): PublishedCapturedPage? = withContext(dispatchers.io) {
        storageCatching {
            database.withTransaction {
                val receipt = capturedPagePublicationDao.findByImageId(imageId.value)
                if (receipt == null) {
                    if (invoiceImageDao.findById(imageId.value) != null) {
                        publicationConflict("imageId legacy ocupado sin recibo de publicación")
                    }
                    return@withTransaction null
                }
                if (!receipt.matches(imageId, draftId, businessId, intent)) {
                    publicationConflict("imageId ya fue consumido por otra intención")
                }
                val image = invoiceImageDao.findById(imageId.value)
                    ?: publicationConflict("La publicación idempotente ya no está activa")
                val domain = image.toDomain()
                if (domain.draftId != draftId || domain.businessId != businessId) {
                    publicationConflict("La página publicada no pertenece al agregado esperado")
                }
                PublishedCapturedPage(
                    image = domain,
                    replacedFilePath = receipt.replacedFilePath,
                )
            }
        }
    }

    override suspend fun rotateImage90(imageId: ImageId): InvoiceImage? =
        withContext(dispatchers.io) {
            storageCatching {
                val now = clock.now()
                database.withTransaction {
                    val current = invoiceImageDao.findById(imageId.value)
                        ?: return@withTransaction null
                    invalidateImageDerivedState(current.draftId)
                    check(invoiceImageDao.rotate90WithCrop(imageId.value) == 1) {
                        "La imagen dejó de existir durante la rotación"
                    }
                    resetDraftAfterImageMutation(current.draftId, now.toEpochMilli())
                    checkNotNull(invoiceImageDao.findById(imageId.value)).toDomain()
                }
            }
        }

    override suspend fun setImageCrop(imageId: ImageId, crop: ImageCrop?): InvoiceImage? =
        withContext(dispatchers.io) {
            storageCatching {
                val now = clock.now()
                database.withTransaction {
                    val current = invoiceImageDao.findById(imageId.value)
                        ?: return@withTransaction null
                    invalidateImageDerivedState(current.draftId)
                    check(
                        invoiceImageDao.setCrop(
                            imageId = imageId.value,
                            left = crop?.left,
                            top = crop?.top,
                            right = crop?.right,
                            bottom = crop?.bottom,
                        ) == 1,
                    ) { "La imagen dejó de existir durante el recorte" }
                    resetDraftAfterImageMutation(current.draftId, now.toEpochMilli())
                    checkNotNull(invoiceImageDao.findById(imageId.value)).toDomain()
                }
            }
        }

    override fun observeImages(draftId: DraftId): Flow<List<InvoiceImage>> =
        invoiceImageDao.observeForDraft(draftId.value)
            .map { list -> list.map { it.toDomain() } }
            .flowOn(dispatchers.io)

    override suspend fun findImage(imageId: ImageId): InvoiceImage? = withContext(dispatchers.io) {
        storageCatching { invoiceImageDao.findById(imageId.value)?.toDomain() }
    }

    override suspend fun isImagePathReferenced(filePath: String): Boolean =
        withContext(dispatchers.io) {
            storageCatching { invoiceImageDao.isPathReferenced(filePath) }
        }

    override suspend fun countImagePathReferences(filePath: String): Int =
        withContext(dispatchers.io) {
            storageCatching { invoiceImageDao.countPathReferences(filePath) }
        }

    override suspend fun listImageIdsReferencingPath(filePath: String): Set<ImageId> =
        withContext(dispatchers.io) {
            storageCatching {
                invoiceImageDao.listImageIdsReferencingPath(filePath)
                    .mapTo(linkedSetOf()) { raw -> ImageId.from(UUID.fromString(raw)) }
            }
        }

    override suspend fun moveImageOneStep(
        draftId: DraftId,
        imageId: ImageId,
        moveUp: Boolean,
    ): List<InvoiceImage> = withContext(dispatchers.io) {
        storageCatching {
            val now = clock.now()
            database.withTransaction {
                val current = invoiceImageDao.listForDraft(draftId.value)
                val fromIndex = current.indexOfFirst { it.imageId == imageId.value }
                if (fromIndex < 0) throw StorageException(StorageError.Unavailable)
                val toIndex = if (moveUp) fromIndex - 1 else fromIndex + 1
                if (toIndex !in current.indices) {
                    return@withTransaction current.map { it.toDomain() }
                }

                invalidateImageDerivedState(draftId.value)
                val moving = current[fromIndex]
                val neighbor = current[toIndex]
                check(invoiceImageDao.deleteById(moving.imageId) == 1)
                check(invoiceImageDao.deleteById(neighbor.imageId) == 1)
                invoiceImageDao.insertAll(
                    listOf(
                        moving.copy(pageIndex = neighbor.pageIndex),
                        neighbor.copy(pageIndex = moving.pageIndex),
                    ),
                )
                resetDraftAfterImageMutation(draftId.value, now.toEpochMilli())
                invoiceImageDao.listForDraft(draftId.value).map { it.toDomain() }
            }
        }
    }

    override suspend fun deleteImage(imageId: ImageId): DeletedDraftImage? =
        withContext(dispatchers.io) {
            storageCatching {
                val now = clock.now()
                database.withTransaction {
                    val current = invoiceImageDao.findById(imageId.value)
                        ?: return@withTransaction null
                    invalidateImageDerivedState(current.draftId)
                    invoiceImageDao.deleteById(imageId.value)
                    // Reindexado ascendente: cada página posterior baja una posición sin chocar
                    // con el índice único (la posición de destino siempre quedó libre).
                    val remaining = invoiceImageDao.listForDraft(current.draftId)
                    remaining
                        .filter { it.pageIndex > current.pageIndex }
                        .sortedBy { it.pageIndex }
                        .forEach { entity ->
                            invoiceImageDao.updatePageIndex(entity.imageId, entity.pageIndex - 1)
                        }
                    resetDraftAfterImageMutation(current.draftId, now.toEpochMilli())
                    DeletedDraftImage(
                        image = current.toDomain(),
                        draftIsEmpty = remaining.isEmpty(),
                    )
                }
            }
        }

    override suspend fun addLine(line: InvoiceLine): InvoiceLine = withContext(dispatchers.io) {
        storageCatching {
            val now = clock.now()
            val stamped = line.copy(createdAt = now, updatedAt = now)
            database.withTransaction {
                invalidatePreparedPurchaseForEdit(stamped.draftId.value, now.toEpochMilli())
                invoiceLineDao.insert(stamped.toEntity())
                invoiceDraftDao.touch(stamped.draftId.value, now.toEpochMilli())
            }
            stamped
        }
    }

    override suspend fun updateLine(line: InvoiceLine): Boolean = withContext(dispatchers.io) {
        storageCatching {
            val now = clock.now()
            val stamped = line.copy(updatedAt = now)
            database.withTransaction {
                if (invoiceLineDao.findById(stamped.lineId.value) == null) {
                    false
                } else {
                    invalidatePreparedPurchaseForEdit(stamped.draftId.value, now.toEpochMilli())
                    invoiceLineDao.update(stamped.toEntity())
                    invoiceDraftDao.touch(stamped.draftId.value, now.toEpochMilli())
                    true
                }
            }
        }
    }

    override suspend fun replaceLines(draftId: DraftId, lines: List<InvoiceLine>) {
        withContext(dispatchers.io) {
            storageCatching {
                val now = clock.now()
                database.withTransaction {
                    invalidatePreparedPurchaseForEdit(draftId.value, now.toEpochMilli())
                    // Delete + insert evita colisiones con el índice único (draftId, position).
                    invoiceLineDao.deleteForDraft(draftId.value)
                    val entities = lines.mapIndexed { index, line ->
                        line.copy(draftId = draftId, position = index, updatedAt = now).toEntity()
                    }
                    invoiceLineDao.insertAll(entities)
                    invoiceDraftDao.touch(draftId.value, now.toEpochMilli())
                }
            }
        }
    }

    override suspend fun reorderLines(draftId: DraftId, orderedLineIds: List<LineId>) {
        withContext(dispatchers.io) {
            storageCatching {
                val now = clock.now()
                database.withTransaction {
                    val current = invoiceLineDao.listForDraft(draftId.value)
                    val byId = current.associateBy { it.lineId }
                    require(orderedLineIds.size == byId.size && orderedLineIds.all { it.value in byId }) {
                        "orderedLineIds debe contener exactamente las líneas actuales del borrador"
                    }
                    invalidatePreparedPurchaseForEdit(draftId.value, now.toEpochMilli())
                    invoiceLineDao.deleteForDraft(draftId.value)
                    val reordered = orderedLineIds.mapIndexed { index, lineId ->
                        byId.getValue(lineId.value)
                            .copy(position = index, updatedAt = now.toEpochMilli())
                    }
                    invoiceLineDao.insertAll(reordered)
                    invoiceDraftDao.touch(draftId.value, now.toEpochMilli())
                }
            }
        }
    }

    override fun observeLines(draftId: DraftId): Flow<List<InvoiceLine>> =
        invoiceLineDao.observeForDraft(draftId.value)
            .map { list -> list.map { it.toDomain() } }
            .flowOn(dispatchers.io)

    override suspend fun deleteLine(lineId: LineId): Boolean = withContext(dispatchers.io) {
        storageCatching {
            val now = clock.now()
            database.withTransaction {
                val current = invoiceLineDao.findById(lineId.value)
                    ?: return@withTransaction false
                invalidatePreparedPurchaseForEdit(current.draftId, now.toEpochMilli())
                invoiceLineDao.deleteById(lineId.value)
                invoiceDraftDao.touch(current.draftId, now.toEpochMilli())
                true
            }
        }
    }

    override suspend fun listAllDraftIds(): Set<DraftId> = withContext(dispatchers.io) {
        storageCatching {
            invoiceDraftDao.listAllDraftIds().mapTo(mutableSetOf(), ::parseDraftId)
        }
    }

    override suspend fun listOpenDraftImages(): List<InvoiceImage> =
        withContext(dispatchers.io) {
            storageCatching {
                invoiceDraftDao.listOpenDraftImages().map { it.toDomain() }
            }
        }

    override suspend fun listCommittedDraftIds(): Set<DraftId> = withContext(dispatchers.io) {
        storageCatching {
            invoiceDraftDao.listCommittedDraftIds().mapTo(mutableSetOf(), ::parseDraftId)
        }
    }

    override suspend fun listImagesWithPublishedOcr(): List<InvoiceImage> =
        withContext(dispatchers.io) {
            storageCatching {
                invoiceDraftDao.listImagesForDraftStatuses(
                    statuses = listOf(
                        DraftStatus.OCR_READY.name,
                        DraftStatus.NEEDS_REVIEW.name,
                        DraftStatus.READY_TO_POST.name,
                    ),
                ).map { it.toDomain() }
            }
        }

    /**
     * Un ID no canónico en la base rompe la pasada de mantenimiento en seco (fail-fast): un
     * conjunto incompleto convertiría directorios válidos en "huérfanos" durante el barrido.
     */
    private fun parseDraftId(raw: String): DraftId = DraftId.from(UUID.fromString(raw))

    /**
     * Mantiene la preparación ligada al contenido del agregado. Debe invocarse dentro de la
     * misma transacción Room que la mutación: si cualquier escritura posterior falla, tanto el
     * estado READY_TO_POST como la instantánea se restauran por rollback.
     *
     * El delete es deliberadamente incondicional para reparar también una instantánea huérfana
     * que pudiera haber quedado junto a un estado distinto de READY_TO_POST.
     */
    private suspend fun invalidatePreparedPurchaseForEdit(draftId: String, updatedAt: Long) {
        preparedPurchaseDao.reopenIfReady(
            draftId = draftId,
            readyStatus = DraftStatus.READY_TO_POST.name,
            reviewStatus = DraftStatus.NEEDS_REVIEW.name,
            updatedAt = updatedAt,
        )
        preparedPurchaseDao.deleteForDraft(draftId)
    }

    /**
     * Invalida dentro de la misma transacción todo dato cuyo significado depende de las páginas.
     * Leer primero el padre evita mutaciones transitorias sobre un borrador terminal; el UPDATE
     * final conserva el cierre ante una confirmación concurrente.
     */
    private suspend fun invalidateImageDerivedState(
        draftId: String,
        expectedBusinessId: String? = null,
    ) {
        val current = invoiceDraftDao.findById(draftId)
            ?: throw StorageException(StorageError.Unavailable)
        if (expectedBusinessId != null && current.businessId != expectedBusinessId) {
            throw StorageException(
                StorageError.ConstraintConflict(
                    "La captura no pertenece al negocio del borrador",
                ),
            )
        }
        val status = DraftStatus.valueOf(current.status)
        if (current.confirmedPurchaseId != null || !status.acceptsImageMutations) {
            throw StorageException(
                StorageError.ConstraintConflict(
                    "El estado $status no admite mutaciones de imágenes",
                ),
            )
        }

        preparedPurchaseDao.deleteForDraft(draftId)
        headerEditDao.deleteForDraft(draftId)
        linesEditDao.deleteForDraft(draftId)
        invoiceLineDao.deleteForDraft(draftId)
        // La FK elimina también páginas OCR y el audit trail del parser.
        ocrSnapshotDao.deleteByDraftId(draftId)
    }

    private suspend fun resetDraftAfterImageMutation(draftId: String, updatedAt: Long) {
        check(
            invoiceDraftDao.resetAfterImageMutation(
                draftId = draftId,
                createdStatus = DraftStatus.CREATED.name,
                capturedStatus = DraftStatus.CAPTURED.name,
                errorStatus = DraftStatus.ERROR.name,
                updatedAt = updatedAt,
            ) == 1,
        ) { "El borrador dejó de admitir la mutación de imágenes" }
    }

}

private fun CapturedPageWrite.toPublicationEntity(
    intent: CapturedPageIntent,
    replaceTargetImageId: String?,
    replacedFilePath: String?,
    publishedPageIndex: Int,
    publishedAt: Long,
): CapturedPagePublicationEntity = CapturedPagePublicationEntity(
    imageId = imageId.value,
    draftId = draftId.value,
    businessId = businessId.value,
    intentKind = when (intent) {
        CapturedPageIntent.Append -> CapturedPagePublicationEntity.APPEND
        is CapturedPageIntent.Replace -> CapturedPagePublicationEntity.REPLACE
        CapturedPageIntent.ReplaceSoleInvoiceScan ->
            CapturedPagePublicationEntity.REPLACE_SOLE_INVOICE_SCAN
    },
    // Para el reintento de página única el objetivo se decide dentro de la transacción.
    replaceTargetImageId = replaceTargetImageId,
    replacedFilePath = replacedFilePath,
    filePath = filePath,
    sha256 = sha256,
    mimeType = mimeType,
    widthPx = widthPx,
    heightPx = heightPx,
    fileSizeBytes = fileSizeBytes,
    rotationDegrees = rotationDegrees,
    publishedPageIndex = publishedPageIndex,
    publishedAt = publishedAt,
)

private fun CapturedPagePublicationEntity.matches(
    page: CapturedPageWrite,
    intent: CapturedPageIntent,
): Boolean =
    matches(page.imageId, page.draftId, page.businessId, intent) &&
        filePath == page.filePath &&
        sha256 == page.sha256 &&
        mimeType == page.mimeType &&
        widthPx == page.widthPx &&
        heightPx == page.heightPx &&
        fileSizeBytes == page.fileSizeBytes &&
        rotationDegrees == page.rotationDegrees

private fun CapturedPagePublicationEntity.matches(
    imageId: ImageId,
    draftId: DraftId,
    businessId: BusinessId,
    intent: CapturedPageIntent,
): Boolean =
    this.imageId == imageId.value &&
        this.draftId == draftId.value &&
        this.businessId == businessId.value &&
        when (intent) {
            CapturedPageIntent.Append ->
                intentKind == CapturedPagePublicationEntity.APPEND && replaceTargetImageId == null
            is CapturedPageIntent.Replace ->
                intentKind == CapturedPagePublicationEntity.REPLACE &&
                    replaceTargetImageId == intent.targetImageId.value
            CapturedPageIntent.ReplaceSoleInvoiceScan ->
                intentKind == CapturedPagePublicationEntity.REPLACE_SOLE_INVOICE_SCAN &&
                    replaceTargetImageId != null
        }

private fun checkPublishedIdentity(image: InvoiceImage, page: CapturedPageWrite) {
    if (
        image.imageId != page.imageId || image.draftId != page.draftId ||
        image.businessId != page.businessId || image.filePath != page.filePath
    ) {
        publicationConflict("La página publicada no coincide con su recibo")
    }
}

private fun publicationConflict(detail: String): Nothing =
    throw StorageException(StorageError.ConstraintConflict(detail))
