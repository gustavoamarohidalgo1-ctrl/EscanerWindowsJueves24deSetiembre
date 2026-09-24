package com.facturastock.app.data.repository

import com.facturastock.app.data.local.withTransaction
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.entity.InvoiceInventoryReceiptEntity
import com.facturastock.app.data.local.entity.StockMovementEntity
import com.facturastock.app.data.local.mapper.toDomain
import com.facturastock.app.data.local.storageCatching
import com.facturastock.app.domain.model.CatalogCanonicalizer
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InvoiceMatchingIssue
import com.facturastock.app.domain.model.MatchStatus
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.ScannedItemMatch
import com.facturastock.app.domain.model.StockMovementType
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.SupplierId
import com.facturastock.app.domain.repository.DraftFileStore
import com.facturastock.app.domain.repository.InventoryStockAddition
import com.facturastock.app.domain.repository.InvoiceDraftRepository
import com.facturastock.app.domain.repository.InvoiceMatchingCommitRepository
import com.facturastock.app.domain.repository.ProductInventoryRepository
import com.facturastock.app.domain.repository.ProductRepository
import com.facturastock.app.domain.usecase.ConfirmInvoiceMatchingResult
import com.facturastock.app.domain.usecase.InvoiceMatchingStockResolver
import com.facturastock.app.domain.usecase.SaveSupplierAliasUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.inject.Inject

/** Un único commit para el ingreso OCR; el recibo sobrevive al borrador. */
class RoomInvoiceMatchingCommitRepository
    @Inject
    constructor(
        private val database: FacturaStockDatabase,
        private val products: ProductRepository,
        private val inventory: ProductInventoryRepository,
        private val drafts: InvoiceDraftRepository,
        private val files: DraftFileStore,
        private val saveAlias: SaveSupplierAliasUseCase,
        private val clock: AppClock,
        private val dispatchers: DispatcherProvider,
    ) : InvoiceMatchingCommitRepository {
        override suspend fun findAppliedCount(
            businessId: BusinessId,
            draftId: DraftId,
        ): Int? =
            withContext(dispatchers.io) {
                storageCatching {
                    database
                        .invoiceInventoryReceiptDao()
                        .find(draftId.value)
                        ?.takeIf { it.businessId == businessId.value }
                        ?.appliedLineCount
                }
            }

        override suspend fun confirm(
            businessId: BusinessId,
            currency: CurrencyCode,
            draftId: DraftId,
            items: List<ScannedItemMatch>,
        ): ConfirmInvoiceMatchingResult =
            withContext(dispatchers.io) {
                val hash = invoiceMatchingContentHash(businessId, currency, draftId, items)
                var supplierId: SupplierId? = null
                val result =
                    try {
                        storageCatching {
                            database.withTransaction {
                                val receipt = database.invoiceInventoryReceiptDao().find(draftId.value)
                                if (receipt != null) {
                                    return@withTransaction if (receipt.businessId == businessId.value && receipt.contentHash == hash) {
                                        ConfirmInvoiceMatchingResult.AlreadyApplied(receipt.appliedLineCount)
                                    } else {
                                        ConfirmInvoiceMatchingResult.DraftChanged
                                    }
                                }
                                val draft =
                                    database.invoiceDraftDao().findById(draftId.value)
                                        ?: return@withTransaction ConfirmInvoiceMatchingResult.DraftChanged
                                if (draft.businessId != businessId.value || draft.status != DraftStatus.NEEDS_REVIEW.name ||
                                    draft.activeOcrRunId != null || draft.confirmedPurchaseId != null
                                ) {
                                    return@withTransaction ConfirmInvoiceMatchingResult.DraftChanged
                                }
                                if (database.businessDao().findById(businessId.value)?.status != CatalogStatus.ACTIVE.name) {
                                    return@withTransaction ConfirmInvoiceMatchingResult.NoActiveBusiness
                                }
                                if (database.cloudBusinessBindingDao().findByLocal(businessId.value) != null) {
                                    return@withTransaction ConfirmInvoiceMatchingResult.CloudBound
                                }
                                if (items.isEmpty()) return@withTransaction ConfirmInvoiceMatchingResult.NothingToApply
                                val source = database.invoiceLineDao().listForDraft(draftId.value)
                                val originals = items.filter { it.sourceLineId != null }
                                val manual = items.filter { it.sourceLineId == null }
                                val identities = items.map { it.sourceLineId?.value ?: it.manualLineId?.value }
                                if (originals.size != source.size || originals.map { it.sourceLineId?.value }.toSet() != source.map { it.lineId }.toSet() ||
                                    items.map { it.lineIndex }.distinct().size != items.size ||
                                    identities.any { it == null } || identities.distinct().size != identities.size ||
                                    originals.any { item -> item.manualLineId != null || source.getOrNull(item.lineIndex)?.lineId != item.sourceLineId?.value } ||
                                    manual.any { it.manualLineId == null || it.lineIndex < source.size }
                                ) {
                                    return@withTransaction ConfirmInvoiceMatchingResult.DraftChanged
                                }

                                val issues = linkedMapOf<Int, Set<InvoiceMatchingIssue>>()
                                val pendingProducts = linkedMapOf<String, Product>()
                                val pendingSourceSnapshots = linkedMapOf<String, Product>()
                                val additions = linkedMapOf<Int, InventoryStockAddition>()
                                val reservedSkus = hashSetOf<String>()
                                val defaultLocations =
                                    database.inventoryLocationDao().searchPage(
                                        businessId.value,
                                        "%",
                                        CatalogStatus.ACTIVE.name,
                                        2,
                                        0,
                                    )
                                for (item in items.sortedBy { it.lineIndex }) {
                                    val selected = item.matchedProduct
                                    if (selected == null) {
                                        issues[item.lineIndex] = setOf(InvoiceMatchingIssue.PRODUCT_REQUIRED)
                                        continue
                                    }
                                    if (selected.businessId != businessId || selected.status != CatalogStatus.ACTIVE) {
                                        return@withTransaction ConfirmInvoiceMatchingResult.DraftChanged
                                    }
                                    val stored = database.productDao().findById(selected.productId.value)?.toDomain()
                                    val current =
                                        when {
                                            stored != null -> {
                                                if (stored.businessId != businessId || stored.status != CatalogStatus.ACTIVE ||
                                                    stored.version != selected.version || stored.unitId != selected.unitId ||
                                                    stored.purchaseUnitId != selected.purchaseUnitId ||
                                                    stored.purchaseFactor != selected.purchaseFactor || stored.locationId != selected.locationId
                                                ) {
                                                    return@withTransaction ConfirmInvoiceMatchingResult.DraftChanged
                                                }
                                                stored
                                            }

                                            item.status == MatchStatus.CREATED_NEW -> {
                                                val previous = pendingSourceSnapshots.putIfAbsent(selected.productId.value, selected)
                                                if (previous != null && previous != selected) return@withTransaction ConfirmInvoiceMatchingResult.DraftChanged
                                                var created = pendingProducts[selected.productId.value] ?: selected
                                                if (created.sku == null) {
                                                    val sku = CatalogCanonicalizer.sku(item.printedCode)
                                                    if (sku != null && sku !in reservedSkus && products.findBySku(businessId, sku) == null) {
                                                        created = created.copy(sku = sku)
                                                    }
                                                }
                                                created.sku?.let { reservedSkus += CatalogCanonicalizer.sku(it).orEmpty() }
                                                pendingProducts[selected.productId.value] = created
                                                created
                                            }

                                            else -> {
                                                return@withTransaction ConfirmInvoiceMatchingResult.DraftChanged
                                            }
                                        }
                                    val inventoryUnit =
                                        database
                                            .unitDao()
                                            .findById(current.unitId.value)
                                            ?.takeIf { it.businessId == businessId.value && it.status == CatalogStatus.ACTIVE.name }
                                    val purchaseUnit =
                                        current.purchaseUnitId
                                            ?.let { database.unitDao().findById(it.value) }
                                            ?.takeIf { it.businessId == businessId.value && it.status == CatalogStatus.ACTIVE.name }
                                    val checkedItem = item.copy(matchedProduct = current)
                                    val lineIssues = InvoiceMatchingStockResolver.issues(checkedItem, currency, inventoryUnit?.code, purchaseUnit?.code)
                                    if (lineIssues.isNotEmpty()) {
                                        issues[item.lineIndex] = lineIssues
                                        continue
                                    }
                                    val resolved = InvoiceMatchingStockResolver.resolve(checkedItem, inventoryUnit?.code, purchaseUnit?.code)
                                    if (resolved == null) {
                                        issues[item.lineIndex] = setOf(InvoiceMatchingIssue.QUANTITY_REQUIRED, InvoiceMatchingIssue.COST_REQUIRED)
                                        continue
                                    }
                                    val location =
                                        current.locationId?.let { database.inventoryLocationDao().findById(it.value) }
                                            ?: defaultLocations.singleOrNull()
                                    if (location == null || location.businessId != businessId.value || location.status != CatalogStatus.ACTIVE.name) {
                                        return@withTransaction ConfirmInvoiceMatchingResult.MissingLocation
                                    }
                                    additions[item.lineIndex] =
                                        InventoryStockAddition(
                                            productId = current.productId,
                                            locationId = requireNotNull(LocationId.parse(location.locationId)),
                                            quantityToAdd = resolved.quantity,
                                            unitCost = resolved.unitCost,
                                            appliedCostTotal = resolved.appliedCostTotal,
                                            idempotencyKey = "invoice-match:v2:${draftId.value}:${requireNotNull(item.sourceLineId ?: item.manualLineId).value}:$hash",
                                        )
                                }
                                if (issues.isNotEmpty()) return@withTransaction ConfirmInvoiceMatchingResult.ReviewRequired(issues)

                                // Una v1 pudo confirmar stock y caer antes de cerrar. Cada entrada anterior
                                // debe coincidir exactamente; cambiar producto/unidad/costo exige reconciliar.
                                val legacy = database.inventoryDao().listMovementsByIdempotencyPattern("invoice-match:v1:${draftId.value}:%")
                                val previouslyApplied = hashSetOf<Int>()
                                for (movement in legacy) {
                                    val item =
                                        items.singleOrNull {
                                            movement.idempotencyKey == "invoice-match:v1:${draftId.value}:${it.lineIndex}:${it.matchedProduct?.productId?.value}"
                                        } ?: return@withTransaction ConfirmInvoiceMatchingResult.LegacyConflict
                                    val addition = additions.getValue(item.lineIndex)
                                    if (!movement.matches(businessId, currency, addition) || !previouslyApplied.add(item.lineIndex)) {
                                        return@withTransaction ConfirmInvoiceMatchingResult.LegacyConflict
                                    }
                                }

                                // No hay retornos de rechazo después de esta primera escritura: todo error
                                // posterior lanza y revierte productos/outbox/stock/recibo/cascada del borrador.
                                products.createBatch(pendingProducts.values.toList())
                                val newAdditions = additions.filterKeys { it !in previouslyApplied }.values.toList()
                                val applied = inventory.addStockBatch(businessId, currency, newAdditions)
                                if (applied != newAdditions.size) throw MatchingCommitConflict()
                                database.invoiceInventoryReceiptDao().insert(
                                    InvoiceInventoryReceiptEntity(
                                        draftId.value,
                                        businessId.value,
                                        hash,
                                        items.size,
                                        maxOf(clock.now().toEpochMilli(), draft.updatedAt),
                                    ),
                                )
                                supplierId = draft.supplierId?.let(SupplierId::parse)
                                if (!drafts.deleteDraft(draftId)) throw MatchingCommitConflict()
                                ConfirmInvoiceMatchingResult.Applied(items.size)
                            }
                        }
                    } catch (_: MatchingCommitConflict) {
                        ConfirmInvoiceMatchingResult.DraftChanged
                    }
                if (result is ConfirmInvoiceMatchingResult.Applied || result is ConfirmInvoiceMatchingResult.AlreadyApplied) {
                    // Efectos derivados: nunca convierten una confirmación durable en un fallo visible.
                    try {
                        supplierId?.let { supplier ->
                            items
                                .filter { it.status in setOf(MatchStatus.MANUAL_LINKED, MatchStatus.CREATED_NEW) }
                                .forEach { item ->
                                    val product = item.matchedProduct ?: return@forEach
                                    listOfNotNull(item.rawDescription.takeIf(String::isNotBlank), item.printedCode?.takeIf(String::isNotBlank))
                                        .distinct()
                                        .forEach { saveAlias(product, supplier, it) }
                                }
                        }
                        files.deleteDraftTreeIf(draftId, drafts::isImagePathReferenced) { drafts.findDraft(draftId) == null }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // La limpieza de archivos se puede repetir; el recibo protege el siguiente intento.
                    }
                }
                result
            }
    }

private class MatchingCommitConflict : RuntimeException()

private fun StockMovementEntity.matches(
    business: BusinessId,
    currency: CurrencyCode,
    addition: InventoryStockAddition,
): Boolean =
    businessId == business.value && productId == addition.productId.value && locationId == addition.locationId.value &&
        type == StockMovementType.ADJUSTMENT.name && currencyCode == currency.value &&
        quantityDelta.toBigDecimal().compareTo(addition.quantityToAdd) == 0 && unitCost != null &&
        addition.unitCost != null && unitCost.toBigDecimal().compareTo(addition.unitCost) == 0

/** Orden y representación decimal canónicos; incluye todas las decisiones que cambian el ingreso. */
internal fun invoiceMatchingContentHash(
    businessId: BusinessId,
    currency: CurrencyCode,
    draftId: DraftId,
    items: List<ScannedItemMatch>,
): String {
    val parts = mutableListOf("invoice-inventory-v2", businessId.value, currency.value, draftId.value)
    items.sortedBy { it.lineIndex }.forEach { item ->
        val product = item.matchedProduct
        parts +=
            listOf(
                item.lineIndex.toString(),
                item.sourceLineId?.value.orEmpty(),
                item.manualLineId?.value.orEmpty(),
                item.rawDescription,
                item.quantity.canonical(),
                item.unitCost.canonical(),
                item.sourceCurrency?.value.orEmpty(),
                item.sourceUnitCode.orEmpty(),
                item.unitChoice?.name.orEmpty(),
                item.printedCode.orEmpty(),
                product?.productId?.value.orEmpty(),
                product?.businessId?.value.orEmpty(),
                product?.unitId?.value.orEmpty(),
                product?.purchaseUnitId?.value.orEmpty(),
                product?.purchaseFactor.canonical(),
                product?.locationId?.value.orEmpty(),
                product?.name.orEmpty(),
                product?.sku.orEmpty(),
                product?.barcode.orEmpty(),
                product?.version?.toString().orEmpty(),
                product
                    ?.salePrice
                    ?.minorUnits
                    ?.toString()
                    .orEmpty(),
                product
                    ?.salePrice
                    ?.currency
                    ?.value
                    .orEmpty(),
            )
    }
    val digest = MessageDigest.getInstance("SHA-256")
    parts.forEach { value ->
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun BigDecimal?.canonical(): String =
    this
        ?.stripTrailingZeros()
        ?.let {
            if (it.scale() < 0) it.setScale(0).toPlainString() else it.toPlainString()
        }.orEmpty()
