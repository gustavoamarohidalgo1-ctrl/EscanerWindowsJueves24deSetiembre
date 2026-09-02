package com.facturastock.app.data.repository

import androidx.room.withTransaction
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.codec.ParsedInvoiceAuditCodec
import com.facturastock.app.data.local.dao.InvoiceDraftDao
import com.facturastock.app.data.local.dao.InvoiceLineDao
import com.facturastock.app.data.local.dao.InvoiceOcrSnapshotDao
import com.facturastock.app.data.local.dao.ParsedInvoiceDao
import com.facturastock.app.data.local.entity.InvoiceDraftEntity
import com.facturastock.app.data.local.entity.InvoiceLineEntity
import com.facturastock.app.data.local.entity.ParsedInvoiceResultEntity
import com.facturastock.app.data.local.mapper.toEntity
import com.facturastock.app.data.local.storageCatching
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.normalization.ParsedInvoiceAudit
import com.facturastock.app.domain.repository.ParsedInvoicePublication
import com.facturastock.app.domain.repository.ParsedInvoiceRepository
import com.facturastock.app.domain.repository.PersistedParsedInvoice
import com.facturastock.app.domain.repository.PublishParsedInvoiceResult
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.withContext

/** Publica el audit trail y su proyección editable como una única mutación Room. */
class RoomParsedInvoiceRepository @Inject constructor(
    private val database: FacturaStockDatabase,
    private val parsedInvoiceDao: ParsedInvoiceDao,
    private val invoiceDraftDao: InvoiceDraftDao,
    private val invoiceLineDao: InvoiceLineDao,
    private val invoiceOcrSnapshotDao: InvoiceOcrSnapshotDao,
    private val dispatchers: DispatcherProvider,
) : ParsedInvoiceRepository {
    override suspend fun publish(
        publication: ParsedInvoicePublication,
    ): PublishParsedInvoiceResult {
        // El BLOB y sus proyecciones se preparan antes de abrir la transacción de escritura.
        val prepared = withContext(dispatchers.default) { publication.prepare() }

        return withContext(dispatchers.io) {
            storageCatching {
                database.withTransaction {
                    val existing = parsedInvoiceDao.findByDraftId(prepared.result.draftId)
                    if (existing != null) {
                        val persistedAudit = existing.decodeAndValidate()
                        if (
                            persistedAudit == publication.parsedInvoice.audit &&
                            existing.payloadCodecVersion == ParsedInvoiceAuditCodec.VERSION &&
                            existing.payloadSha256 == prepared.result.payloadSha256 &&
                            existing.payload.contentEquals(prepared.result.payload)
                        ) {
                            return@withTransaction PublishParsedInvoiceResult.ALREADY_PUBLISHED
                        }
                        return@withTransaction PublishParsedInvoiceResult.CONFLICT
                    }

                    val currentDraft = invoiceDraftDao.findById(prepared.result.draftId)
                        ?: return@withTransaction PublishParsedInvoiceResult.DRAFT_NOT_READY
                    val snapshot = invoiceOcrSnapshotDao.findHeader(prepared.result.draftId)
                    if (snapshot == null || snapshot.runId != prepared.result.runId) {
                        return@withTransaction PublishParsedInvoiceResult.STALE_SNAPSHOT
                    }
                    if (
                        currentDraft.status != DraftStatus.OCR_READY.name ||
                        currentDraft.activeOcrRunId != null ||
                        currentDraft.confirmedPurchaseId != null ||
                        currentDraft.businessId != prepared.projectedDraft.businessId
                    ) {
                        return@withTransaction PublishParsedInvoiceResult.DRAFT_NOT_READY
                    }

                    parsedInvoiceDao.insert(prepared.result)
                    invoiceLineDao.deleteForDraft(prepared.result.draftId)
                    invoiceLineDao.insertAll(prepared.lines)

                    val projection = prepared.projectedDraft
                    val claimed = parsedInvoiceDao.publishProjectionIfReady(
                        draftId = prepared.result.draftId,
                        runId = prepared.result.runId,
                        businessId = projection.businessId,
                        ocrReadyStatus = DraftStatus.OCR_READY.name,
                        needsReviewStatus = DraftStatus.NEEDS_REVIEW.name,
                        supplierId = projection.supplierId,
                        supplierRucRaw = projection.supplierRucRaw,
                        supplierRucNormalized = projection.supplierRucNormalized,
                        supplierLegalNameRaw = projection.supplierLegalNameRaw,
                        supplierLegalNameNormalized = projection.supplierLegalNameNormalized,
                        documentType = projection.documentType,
                        documentNumberRaw = projection.documentNumberRaw,
                        documentNumberNormalized = projection.documentNumberNormalized,
                        issueDateRaw = projection.issueDateRaw,
                        issueDateNormalized = projection.issueDateNormalized,
                        currencyCode = projection.currencyCode,
                        subtotalMinorUnits = projection.subtotalMinorUnits,
                        taxMinorUnits = projection.taxMinorUnits,
                        otherChargesMinorUnits = projection.otherChargesMinorUnits,
                        totalMinorUnits = projection.totalMinorUnits,
                        headerConfidence = projection.headerConfidence,
                        updatedAt = prepared.result.parsedAt,
                    )
                    if (claimed != 1) {
                        throw IOException("El borrador dejó de pertenecer al snapshot parseado")
                    }
                    PublishParsedInvoiceResult.PUBLISHED
                }
            }
        }
    }

    override suspend fun find(draftId: DraftId): PersistedParsedInvoice? {
        val stored = withContext(dispatchers.io) {
            storageCatching { parsedInvoiceDao.findByDraftId(draftId.value) }
        } ?: return null

        return withContext(dispatchers.default) {
            storageCatching {
                PersistedParsedInvoice(
                    audit = stored.decodeAndValidate(),
                    parsedAt = Instant.ofEpochMilli(stored.parsedAt),
                    payloadSha256 = stored.payloadSha256,
                )
            }
        }
    }
}

private data class PreparedParsedInvoicePublication(
    val result: ParsedInvoiceResultEntity,
    val projectedDraft: InvoiceDraftEntity,
    val lines: List<InvoiceLineEntity>,
)

private fun ParsedInvoicePublication.prepare(): PreparedParsedInvoicePublication {
    val audit = parsedInvoice.audit
    val payload = ParsedInvoiceAuditCodec.encode(audit)
    val parsedAtMillis = parsedAt.toEpochMilli()
    val projectedDraft = draft.toEntity()
    val stampedLines = lines.mapIndexed { index, line ->
        line.copy(
            draftId = audit.draftId,
            businessId = draft.businessId,
            position = index,
            createdAt = parsedAt,
            updatedAt = parsedAt,
        ).toEntity()
    }
    return PreparedParsedInvoicePublication(
        result = ParsedInvoiceResultEntity(
            draftId = audit.draftId.value,
            runId = audit.runId.value,
            parserVersion = audit.parserVersion,
            contextFingerprint = audit.contextFingerprint,
            payloadCodecVersion = ParsedInvoiceAuditCodec.VERSION,
            payloadSha256 = ParsedInvoiceAuditCodec.sha256(payload),
            payload = payload,
            parsedAt = parsedAtMillis,
        ),
        // create/updatedAt del objeto recibido nunca sustituyen los timestamps persistidos del
        // borrador: el SQL conserva createdAt y usa parsedAt como updatedAt.
        projectedDraft = projectedDraft,
        lines = stampedLines,
    )
}

@Throws(IOException::class)
private fun ParsedInvoiceResultEntity.decodeAndValidate(): ParsedInvoiceAudit {
    if (payloadCodecVersion != ParsedInvoiceAuditCodec.VERSION) {
        throw IOException("Versión de audit trail no soportada: $payloadCodecVersion")
    }
    if (ParsedInvoiceAuditCodec.sha256(payload) != payloadSha256) {
        throw IOException("Hash del audit trail inconsistente")
    }
    val decoded = ParsedInvoiceAuditCodec.decode(payload)
    if (
        decoded.draftId.value != draftId ||
        decoded.runId.value != runId ||
        decoded.parserVersion != parserVersion ||
        decoded.contextFingerprint != contextFingerprint
    ) {
        throw IOException("Metadatos del audit trail inconsistentes")
    }
    return decoded
}
