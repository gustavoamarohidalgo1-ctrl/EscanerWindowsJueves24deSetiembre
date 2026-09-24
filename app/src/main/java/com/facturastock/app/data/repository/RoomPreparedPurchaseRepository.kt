package com.facturastock.app.data.repository

import com.facturastock.app.data.local.withTransaction
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.codec.PreparedPurchaseCodec
import com.facturastock.app.data.local.dao.PreparedPurchaseDao
import com.facturastock.app.data.local.entity.PreparedPurchaseEntity
import com.facturastock.app.data.local.storageCatching
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.PreparedPurchase
import com.facturastock.app.domain.model.PurchaseProductProvenance
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.repository.PreparedPurchaseRepository
import com.facturastock.app.domain.repository.PublishPreparedPurchaseResult
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Instantánea de compra preparada en Room. Publicar y reabrir combinan en una sola transacción
 * el payload y el CAS de estado del borrador, de modo que nunca existe `READY_TO_POST` sin
 * instantánea ni instantánea sobre un borrador editable.
 */
class RoomPreparedPurchaseRepository @Inject constructor(
    private val database: FacturaStockDatabase,
    private val preparedPurchaseDao: PreparedPurchaseDao,
    private val dispatchers: DispatcherProvider,
    private val clock: AppClock,
) : PreparedPurchaseRepository {
    override suspend fun find(draftId: DraftId): PreparedPurchase? = withContext(dispatchers.io) {
        storageCatching { preparedPurchaseDao.find(draftId.value)?.let(::decodeAndValidate) }
    }

    override fun observe(draftId: DraftId): Flow<PreparedPurchase?> = preparedPurchaseDao
        .observe(draftId.value)
        .map { entity -> entity?.let(::decodeAndValidate) }
        .flowOn(dispatchers.io)

    override suspend fun publish(
        purchase: PreparedPurchase,
        expectedDraftUpdatedAt: Instant,
        expectedHeaderRevision: Long,
        expectedLinesRevision: Long,
    ): PublishPreparedPurchaseResult = withContext(dispatchers.io) {
        require(expectedHeaderRevision >= 0L) { "expectedHeaderRevision no puede ser negativa" }
        require(expectedLinesRevision >= 0L) { "expectedLinesRevision no puede ser negativa" }
        storageCatching {
            val payload = PreparedPurchaseCodec.encode(purchase)
            val entity = PreparedPurchaseEntity(
                draftId = purchase.draftId.value,
                logicalHash = purchase.logicalHash,
                payloadCodecVersion = PreparedPurchaseCodec.VERSION,
                payloadSha256 = PreparedPurchaseCodec.sha256(payload),
                payload = payload,
                preparedAt = purchase.preparedAt.toEpochMilli(),
            )
            database.withTransaction {
                val existing = preparedPurchaseDao.find(purchase.draftId.value)
                if (existing != null && existing.logicalHash == purchase.logicalHash) {
                    val draft = database.invoiceDraftDao().findById(purchase.draftId.value)
                    return@withTransaction if (
                        draft?.status == DraftStatus.READY_TO_POST.name &&
                        draft.activeOcrRunId == null &&
                        draft.confirmedPurchaseId == null
                    ) {
                        PublishPreparedPurchaseResult.ALREADY_PREPARED
                    } else {
                        PublishPreparedPurchaseResult.CONFLICT
                    }
                }
                val currentDraft = database.invoiceDraftDao().findById(purchase.draftId.value)
                if (
                    currentDraft == null ||
                    currentDraft.businessId != purchase.businessId.value ||
                    currentDraft.updatedAt != expectedDraftUpdatedAt.toEpochMilli()
                ) {
                    return@withTransaction PublishPreparedPurchaseResult.CONFLICT
                }
                val currentHeaderRevision = database.invoiceHeaderEditDao()
                    .findByDraftId(purchase.draftId.value)?.revision
                val currentLinesRevision = database.invoiceLinesEditDao()
                    .findByDraftId(purchase.draftId.value)?.revision
                if (
                    currentHeaderRevision != expectedHeaderRevision ||
                    currentLinesRevision != expectedLinesRevision
                ) {
                    return@withTransaction PublishPreparedPurchaseResult.CONFLICT
                }
                val catalogIsValid = purchase.lines.all { line ->
                    val product = database.productDao().findById(line.productId.value)
                    val unit = database.unitDao().findById(line.unitId.value)
                    val unitIsActive = unit != null &&
                        unit.businessId == purchase.businessId.value &&
                        unit.status == CatalogStatus.ACTIVE.name
                    when (line.productProvenance) {
                        PurchaseProductProvenance.CREATED_IN_DRAFT -> {
                            val staged = line.stagedProduct
                            val stagedPurchaseUnit = staged?.purchaseUnitId?.let { purchaseUnitId ->
                                database.unitDao().findById(purchaseUnitId.value)
                            }
                            staged != null &&
                                product == null &&
                                staged.businessId == purchase.businessId &&
                                unitIsActive &&
                                line.unitId in setOfNotNull(staged.unitId, staged.purchaseUnitId) &&
                                (
                                    staged.purchaseUnitId == null ||
                                        (
                                            stagedPurchaseUnit != null &&
                                            stagedPurchaseUnit.businessId == purchase.businessId.value &&
                                                stagedPurchaseUnit.status == CatalogStatus.ACTIVE.name
                                            )
                                    )
                        }
                        PurchaseProductProvenance.EXISTING,
                        PurchaseProductProvenance.UNKNOWN_LEGACY,
                        -> line.stagedProduct == null &&
                            product != null &&
                            product.businessId == purchase.businessId.value &&
                            product.status == CatalogStatus.ACTIVE.name &&
                            unitIsActive &&
                            line.unitId.value in setOfNotNull(
                                product.unitId,
                                product.purchaseUnitId,
                            )
                    }
                }
                if (!catalogIsValid) {
                    return@withTransaction PublishPreparedPurchaseResult.CONFLICT
                }
                // El CAS va primero: si el upsert falla, la transacción revierte el estado.
                val claimed = preparedPurchaseDao.markReadyToPostIfReviewing(
                    draftId = purchase.draftId.value,
                    reviewStatus = DraftStatus.NEEDS_REVIEW.name,
                    readyStatus = DraftStatus.READY_TO_POST.name,
                    updatedAt = clock.now().toEpochMilli(),
                )
                if (claimed != 1) {
                    return@withTransaction PublishPreparedPurchaseResult.CONFLICT
                }
                preparedPurchaseDao.upsert(entity)
                PublishPreparedPurchaseResult.PREPARED
            }
        }
    }

    override suspend fun reopenForEdit(draftId: DraftId): Boolean = withContext(dispatchers.io) {
        storageCatching {
            database.withTransaction {
                val reopened = preparedPurchaseDao.reopenIfReady(
                    draftId = draftId.value,
                    readyStatus = DraftStatus.READY_TO_POST.name,
                    reviewStatus = DraftStatus.NEEDS_REVIEW.name,
                    updatedAt = clock.now().toEpochMilli(),
                ) == 1
                if (reopened) {
                    preparedPurchaseDao.deleteForDraft(draftId.value)
                }
                reopened
            }
        }
    }

    @Throws(IOException::class)
    private fun decodeAndValidate(entity: PreparedPurchaseEntity): PreparedPurchase {
        if (!PreparedPurchaseCodec.supports(entity.payloadCodecVersion)) {
            throw IOException("Versión de compra preparada desconocida: ${entity.payloadCodecVersion}")
        }
        if (PreparedPurchaseCodec.sha256(entity.payload) != entity.payloadSha256) {
            throw IOException("Hash de compra preparada no coincide")
        }
        val decoded = PreparedPurchaseCodec.decode(entity.payload)
        if (
            decoded.draftId.value != entity.draftId ||
            decoded.logicalHash != entity.logicalHash ||
            decoded.preparedAt.toEpochMilli() != entity.preparedAt
        ) {
            throw IOException("Metadatos de compra preparada incoherentes")
        }
        return decoded
    }
}
