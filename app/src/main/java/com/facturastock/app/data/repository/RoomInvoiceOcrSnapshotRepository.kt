package com.facturastock.app.data.repository

import com.facturastock.app.data.local.withTransaction
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.codec.InvoiceOcrPageCodec
import com.facturastock.app.data.local.dao.InvoiceOcrSnapshotDao
import com.facturastock.app.data.local.entity.InvoiceOcrSnapshotEntity
import com.facturastock.app.data.local.entity.InvoiceOcrSnapshotPageEntity
import com.facturastock.app.data.local.storageCatching
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InvoiceOcrSnapshot
import com.facturastock.app.domain.model.InvoiceTextDocument
import com.facturastock.app.domain.model.InvoiceTextPage
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.OcrRunId
import com.facturastock.app.domain.repository.InvoiceOcrSnapshotRepository
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Room mantiene la publicación del snapshot y el cambio a OCR_READY en el mismo commit. */
class RoomInvoiceOcrSnapshotRepository @Inject constructor(
    private val database: FacturaStockDatabase,
    private val snapshotDao: InvoiceOcrSnapshotDao,
    private val dispatchers: DispatcherProvider,
) : InvoiceOcrSnapshotRepository {
    override suspend fun publish(snapshot: InvoiceOcrSnapshot): Boolean {
        // Serialización y hash son CPU puro y deliberadamente ocurren antes de abrir Room.
        val encodedPages = withContext(dispatchers.default) {
            snapshot.document.pages.map { page ->
                currentCoroutineContext().ensureActive()
                val payload = InvoiceOcrPageCodec.encode(page)
                EncodedPage(
                    page = page,
                    payload = payload,
                    sha256 = InvoiceOcrPageCodec.sha256(payload),
                )
            }
        }
        val header = InvoiceOcrSnapshotEntity(
            draftId = snapshot.draftId.value,
            runId = snapshot.runId.value,
            completedAt = snapshot.completedAt.toEpochMilli(),
            codecVersion = InvoiceOcrPageCodec.VERSION,
            pageCount = encodedPages.size,
        )
        val pageEntities = encodedPages.map { encoded ->
            InvoiceOcrSnapshotPageEntity(
                draftId = snapshot.draftId.value,
                pageIndex = encoded.page.pageIndex,
                sourceImageId = encoded.page.sourceImageId.value,
                widthPx = encoded.page.widthPx,
                heightPx = encoded.page.heightPx,
                payloadSha256 = encoded.sha256,
                payload = encoded.payload,
            )
        }

        return withContext(dispatchers.io) {
            storageCatching {
                database.withTransaction {
                    val claimed = snapshotDao.markReadyIfRunIsActive(
                        draftId = snapshot.draftId.value,
                        runId = snapshot.runId.value,
                        processingStatus = DraftStatus.OCR_PROCESSING.name,
                        readyStatus = DraftStatus.OCR_READY.name,
                        updatedAt = snapshot.completedAt.toEpochMilli(),
                    ) == 1
                    if (!claimed) return@withTransaction false

                    // El UPDATE anterior también se revierte si alguna inserción falla.
                    snapshotDao.deleteByDraftId(snapshot.draftId.value)
                    snapshotDao.insertHeader(header)
                    snapshotDao.insertPages(pageEntities)
                    true
                }
            }
        }
    }

    override suspend fun find(draftId: DraftId): InvoiceOcrSnapshot? {
        val stored = withContext(dispatchers.io) {
            storageCatching {
                database.withTransaction {
                    val header = snapshotDao.findHeader(draftId.value)
                        ?: return@withTransaction null
                    StoredSnapshot(header, snapshotDao.findPages(draftId.value))
                }
            }
        } ?: return null

        return withContext(dispatchers.default) {
            storageCatching { stored.decode(draftId) }
        }
    }

    @Throws(IOException::class)
    private suspend fun StoredSnapshot.decode(expectedDraftId: DraftId): InvoiceOcrSnapshot {
        if (header.draftId != expectedDraftId.value) throw IOException("Cabecera OCR inconsistente")
        if (header.codecVersion != InvoiceOcrPageCodec.VERSION) {
            throw IOException("Versión de snapshot OCR no soportada: ${header.codecVersion}")
        }
        if (pages.size != header.pageCount) throw IOException("Snapshot OCR incompleto")
        val runId = OcrRunId.parse(header.runId) ?: throw IOException("runId OCR inválido")
        val decodedPages = pages.mapIndexed { expectedIndex, storedPage ->
            currentCoroutineContext().ensureActive()
            if (
                storedPage.draftId != header.draftId ||
                storedPage.pageIndex != expectedIndex ||
                InvoiceOcrPageCodec.sha256(storedPage.payload) != storedPage.payloadSha256
            ) {
                throw IOException("Metadatos de página OCR inconsistentes")
            }
            val page = InvoiceOcrPageCodec.decode(storedPage.payload)
            if (
                page.pageIndex != storedPage.pageIndex ||
                page.sourceImageId.value != storedPage.sourceImageId ||
                page.widthPx != storedPage.widthPx ||
                page.heightPx != storedPage.heightPx
            ) {
                throw IOException("Contenido de página OCR inconsistente")
            }
            page
        }
        return InvoiceOcrSnapshot(
            draftId = expectedDraftId,
            runId = runId,
            completedAt = Instant.ofEpochMilli(header.completedAt),
            document = InvoiceTextDocument(decodedPages),
        )
    }
}

private data class EncodedPage(
    val page: InvoiceTextPage,
    val payload: ByteArray,
    val sha256: String,
)

private data class StoredSnapshot(
    val header: InvoiceOcrSnapshotEntity,
    val pages: List<InvoiceOcrSnapshotPageEntity>,
)
