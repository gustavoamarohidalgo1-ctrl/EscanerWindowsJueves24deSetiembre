package com.facturastock.app.domain.usecase

import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.Product
import com.facturastock.app.domain.model.UnitOfMeasure
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.normalization.Candidate
import com.facturastock.app.domain.normalization.InvoiceUnitCode
import com.facturastock.app.domain.normalization.ParsedInvoice
import com.facturastock.app.domain.normalization.ParsedInvoiceConfidence
import com.facturastock.app.domain.normalization.ParsedInvoiceLineItem
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.BusinessRepository
import com.facturastock.app.domain.repository.InvoiceDraftRepository
import com.facturastock.app.domain.repository.ProductRepository
import com.facturastock.app.domain.repository.UnitRepository
import java.util.Locale
import java.util.concurrent.CancellationException

data class ScannedInvoiceProductImportCounts(
    val scannedLineCount: Int,
    val eligibleProductCount: Int,
    val importedCount: Int,
    val alreadyExistingCount: Int,
    val duplicateLineCount: Int,
    val skippedLineCount: Int,
)

enum class ScannedInvoiceProductImportError {
    PARSED_DRAFT_MISMATCH,
    NO_ACTIVE_BUSINESS,
    DRAFT_NOT_FOUND,
    DRAFT_NOT_READY,
    BUSINESS_MISMATCH,
    UNIT_UNAVAILABLE,
    NO_ELIGIBLE_PRODUCTS,
    CATALOG_CONFLICT,
    INSUFFICIENT_STORAGE,
    STORAGE_UNAVAILABLE,
    CLEANUP_FAILED,
}

sealed interface ImportScannedInvoiceProductsResult {
    data class Success(
        val counts: ScannedInvoiceProductImportCounts,
    ) : ImportScannedInvoiceProductsResult

    data class Failure(
        val error: ScannedInvoiceProductImportError,
        val counts: ScannedInvoiceProductImportCounts,
    ) : ImportScannedInvoiceProductsResult
}

/**
 * Importa únicamente nombres de productos fiables desde una factura ya parseada. No registra una
 * compra, existencias, costos, proveedor, moneda ni almacén: esos datos requieren revisión y una
 * publicación contable distinta.
 *
 * Una fila es elegible solo cuando su descripción está resuelta con confianza alta y al menos otro
 * campo estructural fiable señala que procede de una fila de producto, no de una cabecera. Los
 * nombres repetidos en la factura y los ya existentes en el negocio no se vuelven a insertar.
 * Room sobrescribe [ProductRepository.createBatch] para publicar todo el lote y su outbox en una
 * sola transacción.
 */
class ImportScannedInvoiceProductsUseCase(
    private val appConfigurationRepository: AppConfigurationRepository,
    private val businessRepository: BusinessRepository,
    private val invoiceDraftRepository: InvoiceDraftRepository,
    private val productRepository: ProductRepository,
    private val unitRepository: UnitRepository,
    private val uuidGenerator: UuidGenerator,
    private val appClock: AppClock,
    private val deleteDraftUseCase: DeleteDraftUseCase,
) {
    suspend operator fun invoke(
        draftId: DraftId,
        parsedInvoice: ParsedInvoice,
    ): ImportScannedInvoiceProductsResult {
        val scannedLineCount = parsedInvoice.lineItems.items.size
        var counts = ScannedInvoiceProductImportCounts(
            scannedLineCount = scannedLineCount,
            eligibleProductCount = 0,
            importedCount = 0,
            alreadyExistingCount = 0,
            duplicateLineCount = 0,
            skippedLineCount = scannedLineCount,
        )
        if (parsedInvoice.draftId != draftId) {
            return failure(ScannedInvoiceProductImportError.PARSED_DRAFT_MISMATCH, counts)
        }

        return try {
            val activeBusinessId = appConfigurationRepository.current().activeBusinessId
                ?: return failure(ScannedInvoiceProductImportError.NO_ACTIVE_BUSINESS, counts)
            val activeBusiness = businessRepository.findById(activeBusinessId)
            if (activeBusiness?.status != CatalogStatus.ACTIVE) {
                return failure(ScannedInvoiceProductImportError.NO_ACTIVE_BUSINESS, counts)
            }
            val draft = invoiceDraftRepository.findDraft(draftId)
                ?: return failure(ScannedInvoiceProductImportError.DRAFT_NOT_FOUND, counts)
            if (
                draft.status != DraftStatus.NEEDS_REVIEW ||
                draft.activeOcrRunId != null ||
                draft.confirmedPurchaseId != null
            ) {
                return failure(ScannedInvoiceProductImportError.DRAFT_NOT_READY, counts)
            }
            if (draft.businessId != activeBusinessId) {
                return failure(ScannedInvoiceProductImportError.BUSINESS_MISMATCH, counts)
            }

            val eligibleRows = parsedInvoice.lineItems.items.mapNotNull { line ->
                line.eligibleName()?.let { name -> EligibleProductRow(line, name) }
            }
            val distinctRows = LinkedHashMap<String, EligibleProductRow>()
            eligibleRows.forEach { row -> distinctRows.putIfAbsent(normalizedName(row.name), row) }
            counts = counts.copy(
                eligibleProductCount = eligibleRows.size,
                duplicateLineCount = eligibleRows.size - distinctRows.size,
                skippedLineCount = scannedLineCount - eligibleRows.size,
            )
            if (eligibleRows.isEmpty()) {
                return failure(ScannedInvoiceProductImportError.NO_ELIGIBLE_PRODUCTS, counts)
            }

            val unitsByCode = mutableMapOf<String, UnitOfMeasure>()
            val productsToCreate = mutableListOf<Product>()
            val candidateCreatedAt = appClock.now()
            suspend fun findActiveUnit(code: String): UnitOfMeasure? {
                unitsByCode[code]?.let { return it }
                return unitRepository.findByCode(activeBusinessId, code)
                    ?.takeIf { candidate ->
                        candidate.businessId == activeBusinessId &&
                            candidate.status == CatalogStatus.ACTIVE
                    }
                    ?.also { activeUnit -> unitsByCode[code] = activeUnit }
            }
            for ((_, row) in distinctRows) {
                val unit = row.line.safeUnitCode()?.let { code -> findActiveUnit(code) }
                    ?: findActiveUnit(InvoiceUnitCode.NIU.name)
                    ?: return failure(ScannedInvoiceProductImportError.UNIT_UNAVAILABLE, counts)
                productsToCreate += Product(
                    productId = ProductId.from(uuidGenerator.newUuid()),
                    businessId = activeBusinessId,
                    unitId = unit.unitId,
                    name = row.name,
                    createdAt = candidateCreatedAt,
                    updatedAt = candidateCreatedAt,
                )
            }

            val creation = productRepository.createBatchSkippingExistingNames(
                businessId = activeBusinessId,
                products = productsToCreate,
            )
            if (creation.created.size + creation.alreadyExistingCount != productsToCreate.size) {
                return failure(ScannedInvoiceProductImportError.STORAGE_UNAVAILABLE, counts)
            }
            counts = counts.copy(
                importedCount = creation.created.size,
                alreadyExistingCount = creation.alreadyExistingCount,
            )
            try {
                deleteDraftUseCase(draftId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                return failure(ScannedInvoiceProductImportError.CLEANUP_FAILED, counts)
            }
            ImportScannedInvoiceProductsResult.Success(counts)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (storage: StorageException) {
            failure(storage.error.toImportError(), counts)
        } catch (_: Exception) {
            failure(ScannedInvoiceProductImportError.STORAGE_UNAVAILABLE, counts)
        }
    }
}

private data class EligibleProductRow(
    val line: ParsedInvoiceLineItem,
    val name: String,
)

private fun ParsedInvoiceLineItem.eligibleName(): String? {
    // Una advertencia bloqueante sobre cualquier parte de la fila (geometría ambigua,
    // corrección OCR, descuadre aritmético, etc.) invalida la importación automática completa.
    // El nombre por sí solo no basta para afirmar que la línea representa un producto seguro.
    if (requiresReview) return null
    if (!hasReliableRowSignal()) return null
    val candidate = description ?: return null
    if (!candidate.isHighConfidenceResolved()) return null
    return candidate.value?.trim()?.takeIf { name -> name.isNotEmpty() && name.length <= 200 }
}

private fun ParsedInvoiceLineItem.hasReliableRowSignal(): Boolean =
    listOf<Candidate<*>?>(quantity, unitCost, total, code, barcode)
        .any { candidate -> candidate.isHighConfidenceResolved() }

private fun ParsedInvoiceLineItem.safeUnitCode(): String? =
    unit?.takeIf { candidate -> candidate.isHighConfidenceResolved() }?.value?.name

private fun Candidate<*>?.isHighConfidenceResolved(): Boolean {
    val candidate = this ?: return false
    return candidate.value != null &&
        !candidate.requiresReview &&
        ParsedInvoiceConfidence.fromPermille(candidate.confidencePermille) ==
        ParsedInvoiceConfidence.HIGH
}

private fun normalizedName(name: String): String = name.trim().lowercase(Locale.ROOT)

private fun failure(
    error: ScannedInvoiceProductImportError,
    counts: ScannedInvoiceProductImportCounts,
): ImportScannedInvoiceProductsResult.Failure =
    ImportScannedInvoiceProductsResult.Failure(error = error, counts = counts)

private fun StorageError.toImportError(): ScannedInvoiceProductImportError = when (this) {
    is StorageError.ConstraintConflict -> ScannedInvoiceProductImportError.CATALOG_CONFLICT
    StorageError.InsufficientSpace -> ScannedInvoiceProductImportError.INSUFFICIENT_STORAGE
    StorageError.Unavailable -> ScannedInvoiceProductImportError.STORAGE_UNAVAILABLE
}
