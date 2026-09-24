package com.facturastock.app.data.repository

import com.facturastock.app.data.local.withTransaction
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.codec.InvoiceHeaderEditCodec
import com.facturastock.app.data.local.dao.InvoiceDraftDao
import com.facturastock.app.data.local.dao.InvoiceHeaderEditDao
import com.facturastock.app.data.local.entity.InvoiceDraftEntity
import com.facturastock.app.data.local.entity.InvoiceHeaderEditEntity
import com.facturastock.app.data.local.mapper.toEntity
import com.facturastock.app.data.local.storageCatching
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InvoiceHeaderEdit
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.repository.InvoiceHeaderEditPublication
import com.facturastock.app.domain.repository.InvoiceHeaderReviewRepository
import com.facturastock.app.domain.repository.SaveInvoiceHeaderEditResult
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Autosave Room con revisión optimista y proyección tipada en la misma transacción. */
class RoomInvoiceHeaderReviewRepository @Inject constructor(
    private val database: FacturaStockDatabase,
    private val headerEditDao: InvoiceHeaderEditDao,
    private val invoiceDraftDao: InvoiceDraftDao,
    private val dispatchers: DispatcherProvider,
) : InvoiceHeaderReviewRepository {
    override suspend fun find(draftId: DraftId): InvoiceHeaderEdit? {
        val entity = withContext(dispatchers.io) {
            storageCatching { headerEditDao.findByDraftId(draftId.value) }
        } ?: return null
        return withContext(dispatchers.default) {
            storageCatching { entity.decodeAndValidate() }
        }
    }

    override fun observe(draftId: DraftId): Flow<InvoiceHeaderEdit?> =
        headerEditDao.observeByDraftId(draftId.value)
            .map { entity ->
                entity?.let { storageCatching { it.decodeAndValidate() } }
            }
            .flowOn(dispatchers.default)

    override suspend fun saveIfNewer(
        publication: InvoiceHeaderEditPublication,
    ): SaveInvoiceHeaderEditResult {
        // El codec y SQLite almacenan milisegundos. Canonicalizar antes de comparar conserva la
        // idempotencia aunque el reloj de la UI entregue un Instant con nanosegundos.
        val canonicalPublication = publication.atStoragePrecision()
        val prepared = withContext(dispatchers.default) { canonicalPublication.prepare() }
        return withContext(dispatchers.io) {
            storageCatching {
                database.withTransaction {
                    // El agregado manda incluso para un retry idéntico: otra instancia pudo
                    // confirmar o avanzar el borrador después del precheck del caso de uso.
                    val current = invoiceDraftDao.findById(prepared.editEntity.draftId)
                        ?: return@withTransaction SaveInvoiceHeaderEditResult.DRAFT_NOT_EDITABLE
                    if (
                        current.status != DraftStatus.NEEDS_REVIEW.name ||
                        current.activeOcrRunId != null ||
                        current.confirmedPurchaseId != null ||
                        current.businessId != prepared.projectedDraft.businessId
                    ) {
                        return@withTransaction SaveInvoiceHeaderEditResult.DRAFT_NOT_EDITABLE
                    }
                    val existing = headerEditDao.findByDraftId(prepared.editEntity.draftId)
                    if (existing != null) {
                        val existingEdit = existing.decodeAndValidate()
                        when {
                            existing.revision == prepared.editEntity.revision &&
                                existingEdit == canonicalPublication.edit ->
                                return@withTransaction SaveInvoiceHeaderEditResult.ALREADY_SAVED
                            existing.revision > prepared.editEntity.revision ->
                                return@withTransaction SaveInvoiceHeaderEditResult.STALE_REVISION
                            existing.revision == prepared.editEntity.revision ->
                                return@withTransaction SaveInvoiceHeaderEditResult.CONFLICT
                            canonicalPublication.expectedRevision != existing.revision ->
                                return@withTransaction SaveInvoiceHeaderEditResult.STALE_REVISION
                        }
                    } else if (canonicalPublication.expectedRevision != null) {
                        return@withTransaction SaveInvoiceHeaderEditResult.STALE_REVISION
                    }
                    if (existing == null) {
                        headerEditDao.insert(prepared.editEntity)
                    } else if (headerEditDao.update(prepared.editEntity) != 1) {
                        throw IOException("No se pudo avanzar la revisión de cabecera")
                    }
                    invoiceDraftDao.update(current.withHeaderProjection(prepared))
                    SaveInvoiceHeaderEditResult.SAVED
                }
            }
        }
    }
}

private fun InvoiceHeaderEditPublication.atStoragePrecision(): InvoiceHeaderEditPublication {
    val canonicalTimestamp = java.time.Instant.ofEpochMilli(edit.updatedAt.toEpochMilli())
    return copy(
        edit = edit.copy(updatedAt = canonicalTimestamp),
        projectedDraft = projectedDraft.copy(updatedAt = canonicalTimestamp),
    )
}

private data class PreparedHeaderEditPublication(
    val editEntity: InvoiceHeaderEditEntity,
    val projectedDraft: InvoiceDraftEntity,
)

private fun InvoiceHeaderEditPublication.prepare(): PreparedHeaderEditPublication {
    val payload = InvoiceHeaderEditCodec.encode(edit)
    return PreparedHeaderEditPublication(
        editEntity = InvoiceHeaderEditEntity(
            draftId = edit.draftId.value,
            revision = edit.revision,
            payloadCodecVersion = InvoiceHeaderEditCodec.VERSION,
            payloadSha256 = InvoiceHeaderEditCodec.sha256(payload),
            payload = payload,
            updatedAt = edit.updatedAt.toEpochMilli(),
        ),
        projectedDraft = projectedDraft.toEntity(),
    )
}

private fun InvoiceDraftEntity.withHeaderProjection(
    prepared: PreparedHeaderEditPublication,
): InvoiceDraftEntity {
    val projection = prepared.projectedDraft
    return copy(
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
        // `revision` ordena el formulario. El timestamp global del agregado también puede
        // avanzar por líneas o imágenes, así que nunca se usa como CAS ni se hace retroceder.
        updatedAt = maxOf(updatedAt, prepared.editEntity.updatedAt),
    )
}

@Throws(IOException::class)
private fun InvoiceHeaderEditEntity.decodeAndValidate(): InvoiceHeaderEdit {
    if (payloadCodecVersion != InvoiceHeaderEditCodec.VERSION) {
        throw IOException("Versión de formulario no soportada: $payloadCodecVersion")
    }
    if (InvoiceHeaderEditCodec.sha256(payload) != payloadSha256) {
        throw IOException("Hash del formulario de cabecera inconsistente")
    }
    val decoded = InvoiceHeaderEditCodec.decode(payload)
    if (
        decoded.draftId.value != draftId ||
        decoded.revision != revision ||
        decoded.updatedAt.toEpochMilli() != updatedAt
    ) {
        throw IOException("Metadatos del formulario de cabecera inconsistentes")
    }
    return decoded
}
