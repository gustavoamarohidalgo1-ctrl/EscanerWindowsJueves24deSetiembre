package com.facturastock.app.data.repository

import androidx.room.withTransaction
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.codec.InvoiceLinesEditCodec
import com.facturastock.app.data.local.dao.InvoiceDraftDao
import com.facturastock.app.data.local.dao.InvoiceLineDao
import com.facturastock.app.data.local.dao.InvoiceLinesEditDao
import com.facturastock.app.data.local.dao.ProductDao
import com.facturastock.app.data.local.dao.UnitDao
import com.facturastock.app.data.local.entity.InvoiceLineEntity
import com.facturastock.app.data.local.entity.InvoiceLinesEditEntity
import com.facturastock.app.data.local.mapper.toEntity
import com.facturastock.app.data.local.storageCatching
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InvoiceLinesEdit
import com.facturastock.app.domain.model.PurchaseProductProvenance
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.repository.InvoiceLinesEditPublication
import com.facturastock.app.domain.repository.InvoiceLinesReviewRepository
import com.facturastock.app.domain.repository.SaveInvoiceLinesEditResult
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Autosave agregado de líneas. Snapshot, soft-delete/restore y proyección tipada avanzan en la
 * misma transacción; el audit/snapshot conserva OCR mientras `invoice_lines` sirve al downstream.
 */
class RoomInvoiceLinesReviewRepository @Inject constructor(
    private val database: FacturaStockDatabase,
    private val linesEditDao: InvoiceLinesEditDao,
    private val invoiceDraftDao: InvoiceDraftDao,
    private val invoiceLineDao: InvoiceLineDao,
    private val productDao: ProductDao,
    private val unitDao: UnitDao,
    private val dispatchers: DispatcherProvider,
) : InvoiceLinesReviewRepository {
    override suspend fun find(draftId: DraftId): InvoiceLinesEdit? {
        val entity = withContext(dispatchers.io) {
            storageCatching { linesEditDao.findByDraftId(draftId.value) }
        } ?: return null
        return withContext(dispatchers.default) {
            storageCatching { entity.decodeAndValidate() }
        }
    }

    override fun observe(draftId: DraftId): Flow<InvoiceLinesEdit?> =
        linesEditDao.observeByDraftId(draftId.value)
            .map { entity ->
                entity?.let { storageCatching { it.decodeAndValidate() } }
            }
            .flowOn(dispatchers.default)

    override suspend fun saveIfNewer(
        publication: InvoiceLinesEditPublication,
    ): SaveInvoiceLinesEditResult {
        val canonicalPublication = publication.atStoragePrecision()
        val prepared = withContext(dispatchers.default) { canonicalPublication.prepareProjection() }
        val draftId = canonicalPublication.edit.draftId.value
        return withContext(dispatchers.io) {
            storageCatching {
                database.withTransaction {
                    val currentDraft = invoiceDraftDao.findById(draftId)
                        ?: return@withTransaction SaveInvoiceLinesEditResult.DRAFT_NOT_EDITABLE
                    if (
                        currentDraft.status != DraftStatus.NEEDS_REVIEW.name ||
                        currentDraft.activeOcrRunId != null ||
                        currentDraft.confirmedPurchaseId != null ||
                        prepared.projectedLines.any {
                            it.businessId != currentDraft.businessId ||
                                (
                                    it.unitCostCurrency != null &&
                                        it.unitCostCurrency != currentDraft.currencyCode
                                    )
                        }
                    ) {
                        return@withTransaction SaveInvoiceLinesEditResult.DRAFT_NOT_EDITABLE
                    }

                    val existing = linesEditDao.findByDraftId(draftId)
                    val existingEdit = existing?.decodeAndValidate()
                    if (existing != null) {
                        when {
                            existing.revision > canonicalPublication.edit.revision ->
                                return@withTransaction SaveInvoiceLinesEditResult.STALE_REVISION
                            existing.revision < canonicalPublication.edit.revision &&
                                canonicalPublication.expectedRevision != existing.revision ->
                                return@withTransaction SaveInvoiceLinesEditResult.STALE_REVISION
                        }
                    } else if (canonicalPublication.expectedRevision != null) {
                        return@withTransaction SaveInvoiceLinesEditResult.STALE_REVISION
                    }

                    val currentById = invoiceLineDao.listForDraft(draftId)
                        .associateBy(InvoiceLineEntity::lineId)
                    val catalogLinkLineIds = canonicalPublication.catalogLinkLineIds
                        .mapTo(mutableSetOf()) { it.value }
                    if (catalogLinkLineIds.isNotEmpty()) {
                        val editedById = canonicalPublication.edit.activeLines
                            .associateBy { line -> line.lineId.value }
                        val projectedById = prepared.projectedLines
                            .associateBy(InvoiceLineEntity::lineId)
                        val requestedProductIds = linkedSetOf<String>()
                        val requestedUnitIds = linkedSetOf<String>()
                        for (lineId in catalogLinkLineIds) {
                            val edited = editedById[lineId]
                                ?: return@withTransaction SaveInvoiceLinesEditResult.CONFLICT
                            if (projectedById[lineId] == null || currentById[lineId] == null) {
                                return@withTransaction SaveInvoiceLinesEditResult.CONFLICT
                            }
                            val productId = edited.linkedProductId?.value
                                ?: return@withTransaction SaveInvoiceLinesEditResult.CONFLICT
                            val unitId = edited.linkedUnitId?.value
                                ?: return@withTransaction SaveInvoiceLinesEditResult.CONFLICT
                            requestedProductIds += productId
                            requestedUnitIds += unitId
                        }
                        val productsById = productDao.findByIds(requestedProductIds.toList())
                            .associateBy { product -> product.productId }
                        val unitsById = unitDao.findByIds(requestedUnitIds.toList())
                            .associateBy { unit -> unit.unitId }
                        for (lineId in catalogLinkLineIds) {
                            val edited = requireNotNull(editedById[lineId])
                            val projected = requireNotNull(projectedById[lineId])
                            val current = requireNotNull(currentById[lineId])
                            val productId = requireNotNull(edited.linkedProductId).value
                            val unitId = requireNotNull(edited.linkedUnitId).value
                            val unit = unitsById[unitId]
                                ?: return@withTransaction SaveInvoiceLinesEditResult.CONFLICT
                            val staged = edited.stagedProduct
                            val stagedWasCreatedInDraft =
                                edited.productProvenance == PurchaseProductProvenance.CREATED_IN_DRAFT
                            val stagedValid = staged != null &&
                                stagedWasCreatedInDraft &&
                                staged.productId.value == productId &&
                                staged.businessId.value == currentDraft.businessId &&
                                (
                                    unitId == staged.unitId.value ||
                                        unitId == staged.purchaseUnitId?.value
                                    ) &&
                                productsById[productId] == null
                            val product = productsById[productId].takeIf { staged == null }
                            val existingValid = staged == null &&
                                product != null &&
                                product.businessId == currentDraft.businessId &&
                                product.status == CatalogStatus.ACTIVE.name &&
                                (unitId == product.unitId || unitId == product.purchaseUnitId)
                            if (
                                current.draftId != draftId ||
                                current.businessId != currentDraft.businessId ||
                                projected.draftId != draftId ||
                                projected.businessId != currentDraft.businessId ||
                                projected.productId != productId.takeIf { staged == null } ||
                                projected.unitId != unitId ||
                                projected.linkConfidence != edited.linkConfidence ||
                                (!stagedValid && !existingValid) ||
                                unit.businessId != currentDraft.businessId ||
                                unit.status != CatalogStatus.ACTIVE.name
                            ) {
                                return@withTransaction SaveInvoiceLinesEditResult.CONFLICT
                            }
                        }
                    }
                    // La lectura exacta ocurre dentro de la misma transacción que el payload.
                    // `null` en una fila existente es un unlink autoritativo, no un fallback a
                    // la copia atrasada que preparó el ViewModel. Si un tombstone ya no tiene
                    // proyección física, su snapshot persistido manda sobre un retry stale.
                    val hydratedEdit = canonicalPublication.edit.withExactLinks(
                        currentById = currentById,
                        existingEdit = existingEdit,
                        catalogLinkLineIds = catalogLinkLineIds,
                    )
                    val hydratedById = hydratedEdit.lines.associateBy { it.lineId.value }
                    val mergedProjection = prepared.projectedLines.mapIndexed { index, incoming ->
                        val current = currentById[incoming.lineId]
                        val hydrated = hydratedById[incoming.lineId]
                            ?: throw IOException(
                                "La proyección no pertenece al snapshot hidratado",
                            )
                        incoming.copy(
                            position = index,
                            createdAt = current?.createdAt ?: incoming.createdAt,
                            updatedAt = hydratedEdit.updatedAt.toEpochMilli(),
                            // Vinculación de catálogo pertenece a otro flujo. Un autosave del
                            // texto no puede deshacerla por una copia atrasada del ViewModel.
                            unitId = if (incoming.lineId in catalogLinkLineIds) {
                                hydrated.linkedUnitId?.value
                            } else if (current != null) {
                                current.unitId
                            } else {
                                hydrated.linkedUnitId?.value
                            },
                            productId = if (incoming.lineId in catalogLinkLineIds) {
                                hydrated.linkedProductId?.value
                                    ?.takeIf { hydrated.stagedProduct == null }
                            } else if (current != null) {
                                current.productId
                            } else {
                                hydrated.linkedProductId?.value
                            },
                            linkConfidence = if (incoming.lineId in catalogLinkLineIds) {
                                hydrated.linkConfidence
                            } else if (current != null) {
                                current.linkConfidence
                            } else {
                                hydrated.linkConfidence
                            },
                        )
                    }
                    // Un tombstone puede sobrevivir a la eliminación de catálogo. Restore
                    // conserva enlaces existentes del mismo negocio y degrada los ya borrados a
                    // null, igual que SET_NULL, sin provocar un rollback por una FK obsoleta.
                    val desiredProductIds = (
                        mergedProjection.mapNotNull(InvoiceLineEntity::productId) +
                            hydratedEdit.lines.mapNotNull { line ->
                                line.linkedProductId?.value.takeIf { line.stagedProduct == null }
                            }
                        )
                        .distinct()
                    val desiredUnitIds = (
                        mergedProjection.mapNotNull(InvoiceLineEntity::unitId) +
                            hydratedEdit.lines.mapNotNull { it.linkedUnitId?.value }
                        )
                        .distinct()
                    val validProductIds = if (desiredProductIds.isEmpty()) {
                        emptySet()
                    } else {
                        productDao.existingIdsForBusiness(currentDraft.businessId, desiredProductIds)
                            .toSet()
                    }
                    val validUnitIds = if (desiredUnitIds.isEmpty()) {
                        emptySet()
                    } else {
                        unitDao.existingIdsForBusiness(currentDraft.businessId, desiredUnitIds).toSet()
                    }
                    val canonicalEdit = hydratedEdit.withValidCatalogLinks(
                        validProductIds = validProductIds,
                        validUnitIds = validUnitIds,
                    )
                    val editEntity = canonicalEdit.toEntity()
                    val safeProjection = mergedProjection.map { line ->
                        val hydrated = hydratedById[line.lineId]
                        val productId = line.productId
                            ?.takeIf { hydrated?.stagedProduct == null && it in validProductIds }
                        line.copy(
                            productId = productId,
                            unitId = line.unitId?.takeIf { it in validUnitIds },
                            linkConfidence = line.linkConfidence.takeIf { productId != null },
                        )
                    }

                    if (existing != null && existing.revision == editEntity.revision) {
                        return@withTransaction if (requireNotNull(existingEdit) == canonicalEdit) {
                            SaveInvoiceLinesEditResult.ALREADY_SAVED
                        } else {
                            SaveInvoiceLinesEditResult.CONFLICT
                        }
                    }

                    if (existing == null) {
                        linesEditDao.insert(editEntity)
                    } else if (linesEditDao.update(editEntity) != 1) {
                        throw IOException("No se pudo avanzar la revisión de líneas")
                    }
                    // Delete + insert evita colisiones en (draftId, position), elimina la
                    // proyección de tombstones y restaura el mismo lineId cuando reaparece.
                    invoiceLineDao.deleteForDraft(draftId)
                    invoiceLineDao.insertAll(safeProjection)
                    if (
                        invoiceDraftDao.touchAtLeast(
                            draftId,
                            editEntity.updatedAt,
                        ) != 1
                    ) {
                        throw IOException("El borrador desapareció durante el autosave de líneas")
                    }
                    SaveInvoiceLinesEditResult.SAVED
                }
            }
        }
    }
}

private fun InvoiceLinesEditPublication.atStoragePrecision(): InvoiceLinesEditPublication {
    fun Instant.storagePrecision(): Instant = Instant.ofEpochMilli(toEpochMilli())
    val editTimestamp = edit.updatedAt.storagePrecision()
    return copy(
        edit = edit.copy(
            lines = edit.lines.map { line ->
                line.copy(
                    deletedAt = line.deletedAt?.storagePrecision(),
                    createdAt = line.createdAt.storagePrecision(),
                    updatedAt = line.updatedAt.storagePrecision(),
                )
            },
            updatedAt = editTimestamp,
        ),
        projectedLines = projectedLines.map { line ->
            line.copy(
                createdAt = line.createdAt.storagePrecision(),
                updatedAt = editTimestamp,
            )
        },
    )
}

private data class PreparedInvoiceLinesProjection(
    val projectedLines: List<InvoiceLineEntity>,
)

private fun InvoiceLinesEditPublication.prepareProjection(): PreparedInvoiceLinesProjection =
    PreparedInvoiceLinesProjection(
        projectedLines = projectedLines.mapIndexed { index, line ->
            line.copy(position = index, updatedAt = edit.updatedAt).toEntity()
        },
    )

@Throws(IOException::class)
private fun InvoiceLinesEdit.withExactLinks(
    currentById: Map<String, InvoiceLineEntity>,
    existingEdit: InvoiceLinesEdit?,
    catalogLinkLineIds: Set<String>,
): InvoiceLinesEdit {
    val persistedById = existingEdit?.lines.orEmpty().associateBy { it.lineId }
    return copy(lines = lines.map { line ->
        val current = currentById[line.lineId.value]
        val persisted = persistedById[line.lineId]
        when {
            line.lineId.value in catalogLinkLineIds -> line
            line.stagedProduct != null -> line
            persisted?.stagedProduct != null && current?.productId == null -> line.copy(
                linkedProductId = persisted.linkedProductId,
                linkedUnitId = persisted.linkedUnitId,
                linkConfidence = persisted.linkConfidence,
                productProvenance = persisted.productProvenance,
                stagedProduct = persisted.stagedProduct,
            )
            current != null -> line.copy(
                linkedProductId = current.productId.toProductId(),
                linkedUnitId = current.unitId.toUnitId(),
                linkConfidence = current.linkConfidence,
                productProvenance = when {
                    current.productId == line.linkedProductId?.value -> line.productProvenance
                    persisted != null &&
                        current.productId == persisted.linkedProductId?.value ->
                        persisted.productProvenance
                    else -> PurchaseProductProvenance.UNKNOWN_LEGACY
                },
                stagedProduct = null,
            )
            persisted != null -> line.copy(
                linkedProductId = persisted.linkedProductId,
                linkedUnitId = persisted.linkedUnitId,
                linkConfidence = persisted.linkConfidence,
                productProvenance = persisted.productProvenance,
                stagedProduct = persisted.stagedProduct,
            )
            else -> line
        }
    })
}

private fun InvoiceLinesEdit.withValidCatalogLinks(
    validProductIds: Set<String>,
    validUnitIds: Set<String>,
): InvoiceLinesEdit = copy(
    lines = lines.map { line ->
        val unitId = line.linkedUnitId?.takeIf { it.value in validUnitIds }
        val staged = line.stagedProduct
        val productId = if (staged != null) {
            line.linkedProductId?.takeIf { id ->
                id == staged.productId && unitId in setOfNotNull(
                    staged.unitId,
                    staged.purchaseUnitId,
                )
            }
        } else {
            line.linkedProductId?.takeIf { it.value in validProductIds }
        }
        if (productId == null || unitId == null) {
            line.copy(
                linkedProductId = null,
                linkedUnitId = null,
                linkConfidence = null,
                productProvenance = PurchaseProductProvenance.UNKNOWN_LEGACY,
                stagedProduct = null,
            )
        } else {
            line.copy(linkedProductId = productId, linkedUnitId = unitId)
        }
    },
)

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

@Throws(IOException::class)
private fun String?.toProductId(): ProductId? = this?.let { value ->
    ProductId.parse(value) ?: throw IOException("productId actual inválido")
}

@Throws(IOException::class)
private fun String?.toUnitId(): UnitId? = this?.let { value ->
    UnitId.parse(value) ?: throw IOException("unitId actual inválido")
}

@Throws(IOException::class)
private fun InvoiceLinesEditEntity.decodeAndValidate(): InvoiceLinesEdit {
    if (!InvoiceLinesEditCodec.supports(payloadCodecVersion)) {
        throw IOException("Versión de editor no soportada: $payloadCodecVersion")
    }
    if (InvoiceLinesEditCodec.sha256(payload) != payloadSha256) {
        throw IOException("Hash del editor de líneas inconsistente")
    }
    val decoded = InvoiceLinesEditCodec.decode(payload)
    if (
        decoded.draftId.value != draftId ||
        decoded.revision != revision ||
        decoded.updatedAt.toEpochMilli() != updatedAt
    ) {
        throw IOException("Metadatos del editor de líneas inconsistentes")
    }
    return decoded
}
