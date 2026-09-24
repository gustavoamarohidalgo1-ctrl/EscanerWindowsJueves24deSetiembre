package com.facturastock.app.data.repository

import com.facturastock.app.data.local.withTransaction
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.codec.InvoiceHeaderEditCodec
import com.facturastock.app.data.local.codec.InvoiceLinesEditCodec
import com.facturastock.app.data.local.dao.InvoiceDraftDao
import com.facturastock.app.data.local.dao.InvoiceHeaderEditDao
import com.facturastock.app.data.local.dao.InvoiceLineDao
import com.facturastock.app.data.local.dao.InvoiceLinesEditDao
import com.facturastock.app.data.local.entity.InvoiceHeaderEditEntity
import com.facturastock.app.data.local.entity.InvoiceLinesEditEntity
import com.facturastock.app.data.local.storageCatching
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InvoiceHeaderEdit
import com.facturastock.app.domain.model.InvoiceLinesEdit
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.repository.EnterManualInvoiceReviewResult
import com.facturastock.app.domain.repository.ManualInvoiceReviewRepository
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.withContext

/** Fallback local y atómico para un reconocimiento o una interpretación OCR fallidos. */
class RoomManualInvoiceReviewRepository @Inject constructor(
    private val database: FacturaStockDatabase,
    private val invoiceDraftDao: InvoiceDraftDao,
    private val invoiceLineDao: InvoiceLineDao,
    private val headerEditDao: InvoiceHeaderEditDao,
    private val linesEditDao: InvoiceLinesEditDao,
    private val dispatchers: DispatcherProvider,
    private val clock: AppClock,
) : ManualInvoiceReviewRepository {
    override suspend fun enter(draftId: DraftId): EnterManualInvoiceReviewResult =
        withContext(dispatchers.io) {
            storageCatching {
                database.withTransaction {
                    val transitioned = invoiceDraftDao.enterManualReviewIfProcessingFailed(
                        draftId = draftId.value,
                        errorStatus = DraftStatus.ERROR.name,
                        processingStatus = DraftStatus.OCR_PROCESSING.name,
                        ocrReadyStatus = DraftStatus.OCR_READY.name,
                        reviewStatus = DraftStatus.NEEDS_REVIEW.name,
                        updatedAt = clock.now().toEpochMilli(),
                    )
                    if (transitioned != 1) {
                        return@withTransaction EnterManualInvoiceReviewResult.NOT_AVAILABLE
                    }

                    // La fila ya quedó reclamada y el token OCR fue invalidado. Si cualquier
                    // escritura siguiente falla, Room revierte también esta transición.
                    invoiceLineDao.deleteForDraft(draftId.value)
                    val persistedTimestamp = requireNotNull(
                        invoiceDraftDao.findById(draftId.value),
                    ).updatedAt
                    val updatedAt = Instant.ofEpochMilli(persistedTimestamp)
                    headerEditDao.insert(emptyHeader(draftId, updatedAt).toEntity())
                    linesEditDao.insert(emptyLines(draftId, updatedAt).toEntity())
                    EnterManualInvoiceReviewResult.ENTERED
                }
            }
        }
}

private fun emptyHeader(draftId: DraftId, updatedAt: Instant): InvoiceHeaderEdit =
    InvoiceHeaderEdit(
        draftId = draftId,
        revision = 0L,
        updatedAt = updatedAt,
    )

private fun emptyLines(draftId: DraftId, updatedAt: Instant): InvoiceLinesEdit =
    InvoiceLinesEdit(
        draftId = draftId,
        lines = emptyList(),
        revision = 0L,
        updatedAt = updatedAt,
    )

private fun InvoiceHeaderEdit.toEntity(): InvoiceHeaderEditEntity {
    val payload = InvoiceHeaderEditCodec.encode(this)
    return InvoiceHeaderEditEntity(
        draftId = draftId.value,
        revision = revision,
        payloadCodecVersion = InvoiceHeaderEditCodec.VERSION,
        payloadSha256 = InvoiceHeaderEditCodec.sha256(payload),
        payload = payload,
        updatedAt = updatedAt.toEpochMilli(),
    )
}

private fun InvoiceLinesEdit.toEntity(): InvoiceLinesEditEntity {
    val payload = InvoiceLinesEditCodec.encode(this)
    return InvoiceLinesEditEntity(
        draftId = draftId.value,
        revision = revision,
        payloadCodecVersion = InvoiceLinesEditCodec.VERSION,
        payloadSha256 = InvoiceLinesEditCodec.sha256(payload),
        payload = payload,
        updatedAt = updatedAt.toEpochMilli(),
    )
}
