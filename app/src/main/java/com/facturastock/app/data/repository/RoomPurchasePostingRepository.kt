package com.facturastock.app.data.repository

import com.facturastock.app.data.local.sqlite.SQLiteConstraintException
import com.facturastock.app.data.local.sqlite.SQLiteException
import com.facturastock.app.data.local.sqlite.SQLiteFullException
import com.facturastock.app.data.local.withTransaction
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.codec.PreparedPurchaseCodec
import com.facturastock.app.data.local.dao.InventoryBalanceMutation
import com.facturastock.app.data.local.dao.PurchasePostingBatch
import com.facturastock.app.data.local.entity.AuditEventEntity
import com.facturastock.app.data.local.entity.InventoryBalanceEntity
import com.facturastock.app.data.local.entity.InventoryLocationEntity
import com.facturastock.app.data.local.entity.InvoiceImageEntity
import com.facturastock.app.data.local.entity.OutboxOperationEntity
import com.facturastock.app.data.local.entity.PreparedPurchaseEntity
import com.facturastock.app.data.local.entity.ProductEntity
import com.facturastock.app.data.local.entity.PurchaseEntity
import com.facturastock.app.data.local.entity.PurchaseLineEntity
import com.facturastock.app.data.local.entity.StockMovementEntity
import com.facturastock.app.data.local.entity.SupplierEntity
import com.facturastock.app.data.local.entity.SupplierProductAliasEntity
import com.facturastock.app.data.local.entity.UnitEntity
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.AuditEventType
import com.facturastock.app.domain.model.CatalogCanonicalizer
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InventoryAverageCostRequest
import com.facturastock.app.domain.model.InventoryAverageCostResult
import com.facturastock.app.domain.model.InventoryCostRoundingPolicy
import com.facturastock.app.domain.model.InventoryCostingCalculation
import com.facturastock.app.domain.model.InventoryCostingRequest
import com.facturastock.app.domain.model.InventoryCostingResult
import com.facturastock.app.domain.model.InventoryCostingWarning
import com.facturastock.app.domain.model.ImageRetentionPolicy
import com.facturastock.app.domain.model.InvoiceDocumentNumber
import com.facturastock.app.domain.model.OutboxOperationStatus
import com.facturastock.app.domain.model.PreparedPurchase
import com.facturastock.app.domain.model.PreparedPurchaseLine
import com.facturastock.app.domain.model.PurchaseDocumentType
import com.facturastock.app.domain.model.PurchaseDuplicateCanonicalizer
import com.facturastock.app.domain.model.PurchaseDuplicateKind
import com.facturastock.app.domain.model.PurchaseDuplicateOverride
import com.facturastock.app.domain.model.PurchaseDuplicateReason
import com.facturastock.app.domain.model.PurchaseStatus
import com.facturastock.app.domain.model.PurchaseProductProvenance
import com.facturastock.app.domain.model.StockMovementType
import com.facturastock.app.domain.model.StagedPurchaseProduct
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.model.id.SupplierId
import com.facturastock.app.domain.repository.ConfirmPurchaseCommand
import com.facturastock.app.domain.repository.ConfirmPurchaseResult
import com.facturastock.app.domain.repository.AuditPayloadKey
import com.facturastock.app.domain.repository.PurchaseConfirmationBlocker
import com.facturastock.app.domain.repository.PurchaseConfirmationContext
import com.facturastock.app.domain.repository.PurchasePostingRepository
import com.facturastock.app.domain.usecase.InventoryCostingService
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * Publica una compra preparada sin dejar estados intermedios observables.
 *
 * La lectura de la instantanea, la resolucion de catalogo, los aliases aprendidos, el calculo de
 * saldos y [PurchasePostingBatch] se ejecutan dentro del mismo `withTransaction` que invoca al
 * DAO de posting. Asi, un reintento nunca puede reutilizar una decision calculada sobre otro
 * saldo. El tratamiento y la evidencia tributaria proceden de la decision congelada en la
 * revision; el repositorio nunca los infiere a partir de importes.
 */
class RoomPurchasePostingRepository @Inject constructor(
    private val database: FacturaStockDatabase,
    private val appClock: AppClock,
    private val dispatchers: DispatcherProvider,
) : PurchasePostingRepository {
    private val costingService = InventoryCostingService()

    override suspend fun confirm(
        command: ConfirmPurchaseCommand,
        context: PurchaseConfirmationContext,
    ): ConfirmPurchaseResult = withContext(dispatchers.io) {
        try {
            database.withTransaction { confirmInTransaction(command, context) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SQLiteConstraintException) {
            classifyRolledBackConflict(command, context)
        } catch (failure: SQLiteFullException) {
            throw StorageException(StorageError.InsufficientSpace, failure)
        } catch (failure: SQLiteException) {
            throw StorageException(StorageError.Unavailable, failure)
        }
    }

    private suspend fun confirmInTransaction(
        command: ConfirmPurchaseCommand,
        context: PurchaseConfirmationContext,
    ): ConfirmPurchaseResult {
        val businessId = context.activeBusinessId.value
        val business = database.businessDao().findById(businessId)
        if (business == null || business.status != CatalogStatus.ACTIVE.name) {
            return blocked(PurchaseConfirmationBlocker.NoActiveBusiness)
        }

        val draftId = command.draftId.value
        val expectedPurchaseIdempotencyKey = PostingIdentity.purchaseKey(
            command.draftId,
            command.expectedPreparedLogicalHash,
        )
        val draft = database.invoiceDraftDao().findById(draftId)
            ?: return blocked(PurchaseConfirmationBlocker.DraftNotFound)
        database.purchaseDao().findBySourceDraftId(draftId)?.let { existing ->
            return existing.asIdempotentResult(
                expectedDraftId = draftId,
                expectedIdempotencyKey = expectedPurchaseIdempotencyKey,
                draftStatus = draft.status,
                confirmedPurchaseId = draft.confirmedPurchaseId,
                activeBusinessId = businessId,
            )
        }
        if (draft.status != DraftStatus.READY_TO_POST.name) {
            return blocked(PurchaseConfirmationBlocker.DraftNotReady)
        }

        val storedPrepared = database.preparedPurchaseDao().find(draftId)
            ?: return blocked(PurchaseConfirmationBlocker.PreparedPurchaseMissing)
        val prepared = storedPrepared.decodeValidOrNull() ?: return ConfirmPurchaseResult.PreparedChanged
        if (prepared.logicalHash != command.expectedPreparedLogicalHash) {
            return ConfirmPurchaseResult.PreparedChanged
        }
        if (prepared.businessId != context.activeBusinessId) {
            return blocked(
                PurchaseConfirmationBlocker.BusinessMismatch(
                    activeBusinessId = context.activeBusinessId,
                    preparedBusinessId = prepared.businessId,
                ),
            )
        }
        if (
            prepared.draftId != command.draftId ||
            draft.businessId != prepared.businessId.value ||
            draft.confirmedPurchaseId != null ||
            draft.activeOcrRunId != null
        ) {
            return ConfirmPurchaseResult.PreparedChanged
        }
        val documentType = prepared.documentType ?: return ConfirmPurchaseResult.PreparedChanged
        if (documentType == PurchaseDocumentType.CREDIT_NOTE) {
            return blocked(PurchaseConfirmationBlocker.CreditNoteUnsupported)
        }
        val document = InvoiceDocumentNumber.parseCanonical(prepared.documentNumber)
            ?: return ConfirmPurchaseResult.PreparedChanged

        val supplierResolution = resolveSupplier(prepared)
        val supplier = when (supplierResolution) {
            is SupplierResolution.Available -> supplierResolution
            is SupplierResolution.Blocked -> return blocked(supplierResolution.reason)
        }
        val purchaseIdempotencyKey = expectedPurchaseIdempotencyKey
        database.purchaseDao().findByIdempotencyKey(purchaseIdempotencyKey)?.let { existing ->
            return existing.asIdempotentResult(
                expectedDraftId = draftId,
                expectedIdempotencyKey = expectedPurchaseIdempotencyKey,
                draftStatus = draft.status,
                confirmedPurchaseId = draft.confirmedPurchaseId,
                activeBusinessId = businessId,
            )
        }

        val exactDuplicates = findExactDuplicates(
            prepared = prepared,
            supplierId = supplier.supplierId,
            documentType = documentType,
            document = document,
        )
        val duplicateOverride = command.duplicateOverride
        if (exactDuplicates.isEmpty()) {
            if (duplicateOverride != null) return ConfirmPurchaseResult.PreparedChanged
        } else if (
            duplicateOverride == null ||
            !duplicateOverride.authorizes(
                prepared = prepared,
                exactPurchaseIds = exactDuplicates,
            )
        ) {
            return ConfirmPurchaseResult.ExactDuplicate(exactDuplicates.first())
        }

        val blockers = linkedSetOf<PurchaseConfirmationBlocker>()
        val catalogTimestamp = maxOf(
            appClock.now().toEpochMilli(),
            business.updatedAt,
            draft.createdAt,
            draft.updatedAt,
            prepared.preparedAt.toEpochMilli(),
        )
        val stagedProducts = linkedMapOf<String, ProductEntity>()
        val stagedSnapshots = linkedMapOf<String, StagedPurchaseProduct>()
        val stagedBarcodes = mutableSetOf<String>()
        val stagedSkus = mutableSetOf<String>()
        val stagedNames = mutableSetOf<String>()
        for (line in prepared.lines.sortedBy(PreparedPurchaseLine::position)) {
            if (line.productProvenance == PurchaseProductProvenance.UNKNOWN_LEGACY) {
                return ConfirmPurchaseResult.PreparedChanged
            }
            if (line.productProvenance != PurchaseProductProvenance.CREATED_IN_DRAFT) {
                if (line.stagedProduct != null) return ConfirmPurchaseResult.PreparedChanged
                continue
            }
            val staged = line.stagedProduct ?: return ConfirmPurchaseResult.PreparedChanged
            if (
                staged.productId != line.productId ||
                staged.businessId != prepared.businessId ||
                line.unitId !in setOfNotNull(staged.unitId, staged.purchaseUnitId)
            ) {
                return ConfirmPurchaseResult.PreparedChanged
            }
            if (staged.salePrice == null) {
                blockers += PurchaseConfirmationBlocker.SalePriceRequired(
                    line.lineId,
                    line.productId,
                )
                continue
            }
            val previousSnapshot = stagedSnapshots[staged.productId.value]
            if (previousSnapshot != null) {
                if (previousSnapshot != staged) return ConfirmPurchaseResult.PreparedChanged
                continue
            }
            stagedSnapshots[staged.productId.value] = staged
            val inventoryUnit = database.unitDao().findById(staged.unitId.value)
            val purchaseUnit = staged.purchaseUnitId?.let { id ->
                database.unitDao().findById(id.value)
            }
            if (
                !inventoryUnit.isUsableFor(prepared.businessId.value) ||
                (staged.purchaseUnitId != null &&
                    !purchaseUnit.isUsableFor(prepared.businessId.value))
            ) {
                blockers += PurchaseConfirmationBlocker.ProductUnavailable(
                    line.lineId,
                    line.productId,
                )
                continue
            }
            val normalizedName = staged.name.trim().lowercase(Locale.ROOT)
            val collidesWithCatalog =
                database.productDao().findById(staged.productId.value) != null ||
                    staged.barcode?.let { barcode ->
                        database.productDao().findByBarcode(prepared.businessId.value, barcode) != null
                    } == true ||
                    staged.sku?.let { sku ->
                        database.productDao().findBySku(prepared.businessId.value, sku) != null
                    } == true ||
                    database.productDao().findByNormalizedName(
                        prepared.businessId.value,
                        normalizedName,
                    ).isNotEmpty()
            val collidesWithinDraft =
                staged.barcode?.let { !stagedBarcodes.add(it) } == true ||
                    staged.sku?.let { !stagedSkus.add(it) } == true ||
                    !stagedNames.add(normalizedName)
            if (collidesWithCatalog || collidesWithinDraft) {
                blockers += PurchaseConfirmationBlocker.ProductUnavailable(
                    line.lineId,
                    line.productId,
                )
                continue
            }
            val candidate = ProductEntity(
                productId = staged.productId.value,
                businessId = staged.businessId.value,
                unitId = staged.unitId.value,
                name = staged.name,
                createdAt = catalogTimestamp,
                updatedAt = catalogTimestamp,
                sku = staged.sku,
                barcode = staged.barcode,
                purchaseUnitId = staged.purchaseUnitId?.value,
                purchaseFactor = staged.purchaseFactor?.toPlainString(),
                salePriceMinorUnits = staged.salePrice.minorUnits,
                salePriceCurrencyCode = staged.salePrice.currency.value,
                status = CatalogStatus.ACTIVE.name,
            )
            val previous = stagedProducts.putIfAbsent(candidate.productId, candidate)
            if (previous != null && previous != candidate) {
                return ConfirmPurchaseResult.PreparedChanged
            }
        }
        if (blockers.isNotEmpty()) return ConfirmPurchaseResult.Blocked(blockers)

        val resolvedLines = mutableListOf<ResolvedPostingLine>()
        var uniqueLocationLoaded = false
        var uniqueLocation: InventoryLocationEntity? = null
        for (line in prepared.lines.sortedBy(PreparedPurchaseLine::position)) {
            val productCandidate = stagedProducts[line.productId.value]
                ?: database.productDao().findById(line.productId.value)
            val unitCandidate = database.unitDao().findById(line.unitId.value)
            if (
                !productCandidate.isUsableFor(prepared.businessId.value) ||
                !unitCandidate.isUsableFor(prepared.businessId.value)
            ) {
                blockers += PurchaseConfirmationBlocker.ProductUnavailable(line.lineId, line.productId)
                continue
            }
            val product = requireNotNull(productCandidate)
            val unit = requireNotNull(unitCandidate)
            val factor = when (line.unitId.value) {
                product.purchaseUnitId -> product.purchaseFactor?.toBigDecimalOrNull()
                product.unitId -> BigDecimal.ONE
                else -> null
            }
            if (factor == null || factor.signum() <= 0) {
                blockers += PurchaseConfirmationBlocker.ProductUnavailable(line.lineId, line.productId)
                continue
            }
            val location = if (product.locationId != null) {
                database.inventoryLocationDao().findById(product.locationId)
                    ?.takeIf { it.isUsableFor(prepared.businessId.value) }
            } else {
                if (!uniqueLocationLoaded) {
                    uniqueLocationLoaded = true
                    uniqueLocation = database.inventoryLocationDao().searchPage(
                        businessId = prepared.businessId.value,
                        pattern = "%",
                        status = CatalogStatus.ACTIVE.name,
                        limit = 2,
                        offset = 0,
                    ).singleOrNull()
                }
                uniqueLocation
            }
            if (location == null) {
                blockers += PurchaseConfirmationBlocker.LocationMissing(line.lineId, line.productId)
                continue
            }
            val unitCost = line.unitCost ?: return ConfirmPurchaseResult.PreparedChanged
            val calculation = try {
                costingService.calculate(
                    InventoryCostingRequest(
                        currency = prepared.currency,
                        purchaseQuantity = line.quantity.value,
                        purchaseUnitFactor = factor,
                        readPurchaseUnitCost = unitCost.amount,
                        lineDiscount = line.discount?.toMajor() ?: BigDecimal.ZERO,
                        taxTreatment = line.taxTreatment,
                        taxEvidence = line.taxEvidence,
                        costPolicy = context.costPolicy,
                        // El DAO recalcula cada linea con apertura cero y luego agrega por stock key.
                        previousQuantity = BigDecimal.ZERO,
                        previousAverageUnitCost = BigDecimal.ZERO,
                        roundingPolicy = POSTING_ROUNDING,
                    ),
                )
            } catch (_: IllegalArgumentException) {
                return ConfirmPurchaseResult.PreparedChanged
            }
            when (calculation) {
                is InventoryCostingResult.DecisionRequired -> {
                    blockers += PurchaseConfirmationBlocker.TaxDecisionRequired(
                        lineId = line.lineId,
                        reasons = calculation.reasons,
                    )
                }
                is InventoryCostingResult.Calculated -> resolvedLines += ResolvedPostingLine(
                    prepared = line,
                    product = product,
                    unit = unit,
                    location = location,
                    calculation = calculation.calculation,
                )
            }
        }
        if (blockers.isNotEmpty()) return ConfirmPurchaseResult.Blocked(blockers)

        val balancePlans = mutableListOf<InventoryBalanceMutation>()
        val warningsByLine = mutableMapOf<LineId, Set<InventoryCostingWarning>>()
        val openingBalances = mutableListOf<InventoryBalanceEntity>()
        for ((stockKey, lines) in resolvedLines.groupBy { it.stockKey }) {
            val opening = database.inventoryDao().findBalance(
                businessId = businessId,
                productId = stockKey.productId,
                locationId = stockKey.locationId,
            )
            if (opening != null && opening.currencyCode != prepared.currency.value) {
                return blocked(
                    PurchaseConfirmationBlocker.BalanceCurrencyMismatch(
                        lineId = lines.first().prepared.lineId,
                        expected = prepared.currency,
                        actual = CurrencyCode.of(opening.currencyCode),
                    ),
                )
            }
            if (opening != null) openingBalances += opening
            val incomingQuantity = lines.sumExact { it.calculation.inventoryQuantity }
            val incomingCost = lines.sumExact { it.calculation.appliedCostTotal }
            val averageResult = try {
                costingService.calculateAverage(
                    InventoryAverageCostRequest(
                        previousQuantity = opening?.quantityOnHand?.toBigDecimal() ?: BigDecimal.ZERO,
                        previousAverageUnitCost = opening?.averageUnitCost?.toBigDecimal() ?: BigDecimal.ZERO,
                        incomingInventoryQuantity = incomingQuantity,
                        incomingAppliedCostTotal = incomingCost,
                        roundingPolicy = POSTING_ROUNDING,
                    ),
                )
            } catch (_: IllegalArgumentException) {
                return ConfirmPurchaseResult.PreparedChanged
            }
            val average = when (averageResult) {
                is InventoryAverageCostResult.Calculated -> averageResult.calculation
                is InventoryAverageCostResult.DecisionRequired -> {
                    lines.forEach { line ->
                        blockers += PurchaseConfirmationBlocker.TaxDecisionRequired(
                            lineId = line.prepared.lineId,
                            reasons = averageResult.reasons,
                        )
                    }
                    continue
                }
            }
            val averageWarnings = average.warnings
            lines.forEach { line ->
                warningsByLine[line.prepared.lineId] =
                    line.calculation.warnings.filterNotTo(linkedSetOf()) {
                        it == InventoryCostingWarning.PREVIOUS_NON_POSITIVE_BALANCE_REBASED
                    } + averageWarnings
            }
            val nextVersion = opening?.version?.let { version ->
                if (version == Long.MAX_VALUE) return ConfirmPurchaseResult.RetryableConflict
                version + 1L
            } ?: 0L
            // El timestamp definitivo se aplica despues de resolver aliases y el maximo monotono.
            balancePlans += InventoryBalanceMutation(
                expectedVersion = opening?.version,
                balance = InventoryBalanceEntity(
                    businessId = businessId,
                    productId = stockKey.productId,
                    locationId = stockKey.locationId,
                    quantityOnHand = average.resultingQuantity.toPlainString(),
                    averageUnitCost = average.resultingAverageUnitCost.toPlainString(),
                    currencyCode = prepared.currency.value,
                    version = nextVersion,
                    updatedAt = 0L,
                ),
            )
        }
        if (blockers.isNotEmpty()) return ConfirmPurchaseResult.Blocked(blockers)

        val aliasPlan = planAliases(
            businessId = businessId,
            draftId = prepared.draftId.value,
            supplierId = supplier.supplierId,
            lines = resolvedLines,
        )
        val postedAt = maxOf(
            appClock.now().toEpochMilli(),
            business.updatedAt,
            draft.createdAt,
            draft.updatedAt,
            prepared.preparedAt.toEpochMilli(),
            supplier.existing?.createdAt ?: 0L,
            supplier.existing?.updatedAt ?: 0L,
            resolvedLines.maxOfOrNull { maxOf(
                it.product.createdAt,
                it.product.updatedAt,
                it.unit.createdAt,
                it.unit.updatedAt,
                it.location.createdAt,
                it.location.updatedAt,
            ) } ?: 0L,
            openingBalances.maxOfOrNull(InventoryBalanceEntity::updatedAt) ?: 0L,
            aliasPlan.latestExistingTimestamp,
        )

        supplier.newLegalName?.let { legalName ->
            val newSupplier = SupplierEntity(
                supplierId = supplier.supplierId,
                businessId = businessId,
                legalName = legalName,
                createdAt = postedAt,
                updatedAt = postedAt,
                ruc = supplier.ruc,
                status = CatalogStatus.ACTIVE.name,
            )
            database.supplierDao().insert(newSupplier)
            database.outboxOperationDao().insert(
                CatalogSyncOutbox.supplier(newSupplier, expectedVersion = 0L),
            )
        }
        stagedProducts.values.forEach { product ->
            val newProduct = product.copy(createdAt = postedAt, updatedAt = postedAt, version = 1L)
            database.productDao().insert(newProduct)
            database.outboxOperationDao().insert(
                CatalogSyncOutbox.product(
                    entity = newProduct,
                    expectedVersion = 0L,
                    inventoryUnit = requireNotNull(
                        database.unitDao().findById(newProduct.unitId),
                    ),
                    purchaseUnit = newProduct.purchaseUnitId?.let { unitId ->
                        requireNotNull(database.unitDao().findById(unitId))
                    },
                    location = newProduct.locationId?.let { locationId ->
                        requireNotNull(database.inventoryLocationDao().findById(locationId))
                    },
                ),
            )
        }
        aliasPlan.toInsert.forEach { planned ->
            database.supplierProductAliasDao().insert(
                SupplierProductAliasEntity(
                    aliasId = planned.aliasId,
                    businessId = businessId,
                    supplierId = supplier.supplierId,
                    productId = planned.productId,
                    alias = planned.alias,
                    aliasNormalized = planned.normalized,
                    createdAt = postedAt,
                    updatedAt = postedAt,
                ),
            )
        }

        val stampedBalances = balancePlans.map { mutation ->
            mutation.copy(balance = mutation.balance.copy(updatedAt = postedAt))
        }
        val documentImages = if (
            context.backupEnabled && context.documentBackupEnabled &&
            context.imageRetentionPolicy in RETAINED_DOCUMENT_POLICIES
        ) {
            database.invoiceImageDao().listForDraft(draftId)
        } else {
            emptyList()
        }
        val batch = PurchasePostingBatchFactory.create(
            prepared = prepared,
            supplierId = supplier.supplierId,
            document = document,
            costPolicyName = context.costPolicy.name,
            lines = resolvedLines,
            warningsByLine = warningsByLine,
            balanceMutations = stampedBalances,
            aliasesCreated = aliasPlan.toInsert.size,
            aliasesSkipped = aliasPlan.skipped,
            duplicateOverride = duplicateOverride,
            documentImages = documentImages,
            postedAt = postedAt,
        )
        database.purchasePostingDao().postAtomically(batch)
        return ConfirmPurchaseResult.Posted(
            requireNotNull(PurchaseId.parse(batch.purchase.purchaseId)),
        )
    }

    private suspend fun resolveSupplier(prepared: PreparedPurchase): SupplierResolution {
        val businessId = prepared.businessId.value
        val ruc = CatalogCanonicalizer.ruc(prepared.supplierRuc)
            ?.takeIf { RUC.matches(it) }
            ?: return SupplierResolution.Blocked(PurchaseConfirmationBlocker.SupplierMissing)
        prepared.supplierId?.let { preparedId ->
            val existing = database.supplierDao().findById(preparedId.value)
                ?: return SupplierResolution.Blocked(
                    PurchaseConfirmationBlocker.SupplierUnavailable(preparedId),
                )
            return if (
                existing.businessId == businessId &&
                existing.ruc == ruc &&
                existing.status == CatalogStatus.ACTIVE.name
            ) {
                SupplierResolution.Available(existing.supplierId, ruc, existing = existing)
            } else {
                SupplierResolution.Blocked(PurchaseConfirmationBlocker.SupplierUnavailable(preparedId))
            }
        }
        database.supplierDao().findByRuc(businessId, ruc)?.let { existing ->
            val supplierId = SupplierId.parse(existing.supplierId)
                ?: return SupplierResolution.Blocked(PurchaseConfirmationBlocker.SupplierMissing)
            return if (existing.status == CatalogStatus.ACTIVE.name) {
                SupplierResolution.Available(existing.supplierId, ruc, existing = existing)
            } else {
                SupplierResolution.Blocked(PurchaseConfirmationBlocker.SupplierUnavailable(supplierId))
            }
        }
        val legalName = prepared.supplierLegalName?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_SUPPLIER_NAME }
            ?: return SupplierResolution.Blocked(PurchaseConfirmationBlocker.SupplierMissing)
        return SupplierResolution.Available(
            // El RUC es la clave natural y su índice UNIQUE resuelve carreras. El UUID usa el
            // borrador para seguir siendo estable en reintentos sin quedar reservado para
            // siempre si ese proveedor cambia posteriormente de RUC.
            supplierId = PostingIdentity.uuid(
                "supplier",
                businessId,
                prepared.draftId.value,
                ruc,
            ).toString(),
            ruc = ruc,
            newLegalName = legalName,
        )
    }

    private suspend fun findExactDuplicates(
        prepared: PreparedPurchase,
        supplierId: String,
        documentType: PurchaseDocumentType,
        document: InvoiceDocumentNumber,
    ): List<PurchaseId> {
        val expectedSeries = PurchaseDuplicateCanonicalizer.series(document.series)
        val expectedNumber = PurchaseDuplicateCanonicalizer.correlative(document.correlative)
        return database.purchaseDao().listDuplicateCandidateRows(
            businessId = prepared.businessId.value,
            supplierId = supplierId,
            supplierRuc = PurchaseDuplicateCanonicalizer.ruc(prepared.supplierRuc).orEmpty(),
            documentType = documentType.name,
        ).groupBy { it.purchaseId }.values.mapNotNull { rows ->
            val candidate = rows.first()
            val exact = candidate.status in setOf(
                PurchaseStatus.POSTED.name,
                PurchaseStatus.VOIDED.name,
            ) && PurchaseDuplicateCanonicalizer.series(candidate.documentSeries) == expectedSeries &&
                PurchaseDuplicateCanonicalizer.correlative(candidate.documentNumber) == expectedNumber
            if (exact) PurchaseId.parse(candidate.purchaseId) else null
        }
    }

    private fun PurchaseDuplicateOverride.authorizes(
        prepared: PreparedPurchase,
        exactPurchaseIds: List<PurchaseId>,
    ): Boolean =
        draftId == prepared.draftId &&
            preparedLogicalHash == prepared.logicalHash &&
            businessId == prepared.businessId &&
            duplicateKind == PurchaseDuplicateKind.EXACT &&
            actor.canOverrideDuplicate &&
            existingPurchaseId in exactPurchaseIds &&
            reasons.containsAll(REQUIRED_EXACT_REASONS)

    private suspend fun planAliases(
        businessId: String,
        draftId: String,
        supplierId: String,
        lines: List<ResolvedPostingLine>,
    ): AliasPlan {
        val plannedByNormalized = linkedMapOf<String, PlannedAlias>()
        val skipped = mutableListOf<SkippedAlias>()
        var latestExisting = 0L
        for (line in lines.sortedBy { it.prepared.position }) {
            val alias = line.prepared.description.trim()
            val normalized = alias.lowercase(Locale.ROOT)
            if (normalized == line.product.name.trim().lowercase(Locale.ROOT)) continue
            if (alias.isEmpty() || alias.length > MAX_ALIAS_LENGTH) {
                skipped += SkippedAlias(line.prepared.lineId.value, line.product.productId, "INVALID_LENGTH")
                continue
            }
            val alreadyPlanned = plannedByNormalized[normalized]
            if (alreadyPlanned != null) {
                if (alreadyPlanned.productId != line.product.productId) {
                    skipped += SkippedAlias(
                        line.prepared.lineId.value,
                        alreadyPlanned.productId,
                        "PLANNED_FOR_OTHER_PRODUCT",
                    )
                }
                continue
            }
            val existing = database.supplierProductAliasDao()
                .findByNormalizedAlias(businessId, normalized)
                .firstOrNull { it.supplierId == supplierId }
            if (existing != null) {
                latestExisting = maxOf(latestExisting, existing.createdAt, existing.updatedAt)
                if (existing.productId != line.product.productId) {
                    skipped += SkippedAlias(
                        line.prepared.lineId.value,
                        existing.productId,
                        "EXISTING_OTHER_PRODUCT",
                    )
                }
                continue
            }
            plannedByNormalized[normalized] = PlannedAlias(
                // La unicidad natural decide carreras entre compras. Incluir el borrador evita
                // reutilizar un PK histórico cuando un alias se renombra y libera este texto.
                aliasId = PostingIdentity.uuid(
                    "supplier-alias",
                    businessId,
                    draftId,
                    supplierId,
                    normalized,
                ).toString(),
                productId = line.product.productId,
                alias = alias,
                normalized = normalized,
            )
        }
        return AliasPlan(plannedByNormalized.values.toList(), skipped, latestExisting)
    }

    private suspend fun classifyRolledBackConflict(
        command: ConfirmPurchaseCommand,
        context: PurchaseConfirmationContext,
    ): ConfirmPurchaseResult = try {
        database.withTransaction {
            val expectedPurchaseIdempotencyKey = PostingIdentity.purchaseKey(
                command.draftId,
                command.expectedPreparedLogicalHash,
            )
            val draft = database.invoiceDraftDao().findById(command.draftId.value)
                ?: return@withTransaction ConfirmPurchaseResult.PreparedChanged
            database.purchaseDao().findBySourceDraftId(command.draftId.value)?.let { existing ->
                return@withTransaction existing.asIdempotentResult(
                    expectedDraftId = command.draftId.value,
                    expectedIdempotencyKey = expectedPurchaseIdempotencyKey,
                    draftStatus = draft.status,
                    confirmedPurchaseId = draft.confirmedPurchaseId,
                    activeBusinessId = context.activeBusinessId.value,
                )
            }
            val stored = database.preparedPurchaseDao().find(command.draftId.value)
                ?: return@withTransaction ConfirmPurchaseResult.PreparedChanged
            val prepared = stored.decodeValidOrNull()
                ?: return@withTransaction ConfirmPurchaseResult.PreparedChanged
            if (prepared.logicalHash != command.expectedPreparedLogicalHash) {
                return@withTransaction ConfirmPurchaseResult.PreparedChanged
            }
            database.purchaseDao().findByIdempotencyKey(expectedPurchaseIdempotencyKey)
                ?.let { existing ->
                    return@withTransaction existing.asIdempotentResult(
                        expectedDraftId = command.draftId.value,
                        expectedIdempotencyKey = expectedPurchaseIdempotencyKey,
                        draftStatus = draft.status,
                        confirmedPurchaseId = draft.confirmedPurchaseId,
                        activeBusinessId = context.activeBusinessId.value,
                    )
                }
            val type = prepared.documentType
                ?: return@withTransaction ConfirmPurchaseResult.PreparedChanged
            val document = InvoiceDocumentNumber.parseCanonical(prepared.documentNumber)
                ?: return@withTransaction ConfirmPurchaseResult.PreparedChanged
            val supplierId = prepared.supplierId?.value
                ?: database.supplierDao().findByRuc(prepared.businessId.value, prepared.supplierRuc)?.supplierId
                ?: PostingIdentity.uuid(
                    "supplier",
                    prepared.businessId.value,
                    prepared.draftId.value,
                    prepared.supplierRuc,
                ).toString()
            val exactDuplicates = findExactDuplicates(prepared, supplierId, type, document)
            exactDuplicates.firstOrNull()?.let { duplicate ->
                return@withTransaction if (
                    command.duplicateOverride?.authorizes(prepared, exactDuplicates) == true
                ) {
                    ConfirmPurchaseResult.RetryableConflict
                } else {
                    ConfirmPurchaseResult.ExactDuplicate(duplicate)
                }
            }
            if (
                draft.status != DraftStatus.READY_TO_POST.name ||
                draft.businessId != prepared.businessId.value ||
                prepared.businessId != context.activeBusinessId
            ) {
                ConfirmPurchaseResult.PreparedChanged
            } else {
                ConfirmPurchaseResult.RetryableConflict
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: SQLiteFullException) {
        throw StorageException(StorageError.InsufficientSpace, failure)
    } catch (failure: SQLiteException) {
        throw StorageException(StorageError.Unavailable, failure)
    }

    private fun PurchaseEntity.asIdempotentResult(
        expectedDraftId: String,
        expectedIdempotencyKey: String,
        draftStatus: String,
        confirmedPurchaseId: String?,
        activeBusinessId: String,
    ): ConfirmPurchaseResult {
        if (idempotencyKey != expectedIdempotencyKey) {
            return ConfirmPurchaseResult.PreparedChanged
        }
        val id = PurchaseId.parse(purchaseId) ?: return ConfirmPurchaseResult.RetryableConflict
        return if (
            businessId == activeBusinessId &&
            sourceDraftId == expectedDraftId &&
            status in setOf(PurchaseStatus.POSTED.name, PurchaseStatus.VOIDED.name) &&
            draftStatus == DraftStatus.COMMITTED.name &&
            confirmedPurchaseId == purchaseId
        ) {
            ConfirmPurchaseResult.AlreadyPosted(id)
        } else {
            ConfirmPurchaseResult.RetryableConflict
        }
    }

    private fun PreparedPurchaseEntity.decodeValidOrNull(): PreparedPurchase? = try {
        if (
            !PreparedPurchaseCodec.supports(payloadCodecVersion) ||
            PreparedPurchaseCodec.sha256(payload) != payloadSha256
        ) return null
        PreparedPurchaseCodec.decode(payload).takeIf { decoded ->
            decoded.draftId.value == draftId &&
                decoded.logicalHash == logicalHash &&
                decoded.preparedAt.toEpochMilli() == preparedAt
        }
    } catch (_: Exception) {
        null
    }

    private fun blocked(reason: PurchaseConfirmationBlocker): ConfirmPurchaseResult =
        ConfirmPurchaseResult.Blocked(setOf(reason))

    private fun ProductEntity?.isUsableFor(businessId: String): Boolean =
        this != null && this.businessId == businessId && status == CatalogStatus.ACTIVE.name

    private fun UnitEntity?.isUsableFor(businessId: String): Boolean =
        this != null && this.businessId == businessId && status == CatalogStatus.ACTIVE.name

    private fun InventoryLocationEntity.isUsableFor(businessId: String): Boolean =
        this.businessId == businessId && status == CatalogStatus.ACTIVE.name

    private sealed interface SupplierResolution {
        data class Available(
            val supplierId: String,
            val ruc: String,
            val existing: SupplierEntity? = null,
            val newLegalName: String? = null,
        ) : SupplierResolution

        data class Blocked(val reason: PurchaseConfirmationBlocker) : SupplierResolution
    }

    private companion object {
        val RUC = Regex("^\\d{11}$")
        val POSTING_ROUNDING = InventoryCostRoundingPolicy(18, RoundingMode.HALF_EVEN)
        const val MAX_SUPPLIER_NAME = 200
        const val MAX_ALIAS_LENGTH = 200
        val REQUIRED_EXACT_REASONS = setOf(
            PurchaseDuplicateReason.SAME_BUSINESS,
            PurchaseDuplicateReason.SAME_SUPPLIER,
            PurchaseDuplicateReason.SAME_DOCUMENT_TYPE,
            PurchaseDuplicateReason.SAME_DOCUMENT_NUMBER,
        )
    }
}

private data class StockKey(val productId: String, val locationId: String)

private data class ResolvedPostingLine(
    val prepared: PreparedPurchaseLine,
    val product: ProductEntity,
    val unit: UnitEntity,
    val location: InventoryLocationEntity,
    val calculation: InventoryCostingCalculation,
) {
    val stockKey: StockKey = StockKey(product.productId, location.locationId)
}

private data class PlannedAlias(
    val aliasId: String,
    val productId: String,
    val alias: String,
    val normalized: String,
)

private data class SkippedAlias(
    val lineId: String,
    val existingProductId: String,
    val reason: String,
)

private data class AliasPlan(
    val toInsert: List<PlannedAlias>,
    val skipped: List<SkippedAlias>,
    val latestExistingTimestamp: Long,
)

/** Construccion pura del grafo que [com.facturastock.app.data.local.dao.PurchasePostingDao] valida. */
private val RETAINED_DOCUMENT_POLICIES = setOf(
    ImageRetentionPolicy.DAYS_30,
    ImageRetentionPolicy.DAYS_90,
    ImageRetentionPolicy.KEEP,
)

private object PurchasePostingBatchFactory {
    fun create(
        prepared: PreparedPurchase,
        supplierId: String,
        document: InvoiceDocumentNumber,
        costPolicyName: String,
        lines: List<ResolvedPostingLine>,
        warningsByLine: Map<LineId, Set<InventoryCostingWarning>>,
        balanceMutations: List<InventoryBalanceMutation>,
        aliasesCreated: Int,
        aliasesSkipped: List<SkippedAlias>,
        duplicateOverride: PurchaseDuplicateOverride?,
        documentImages: List<InvoiceImageEntity>,
        postedAt: Long,
    ): PurchasePostingBatch {
        val purchaseId = PostingIdentity.uuid(
            "purchase",
            prepared.draftId.value,
            prepared.logicalHash,
        ).toString()
        val purchase = PurchaseEntity(
            purchaseId = purchaseId,
            businessId = prepared.businessId.value,
            sourceDraftId = prepared.draftId.value,
            supplierId = supplierId,
            documentType = requireNotNull(prepared.documentType).name,
            documentSeries = document.series,
            documentNumber = document.correlative,
            issueDate = prepared.issueDate.toString(),
            currencyCode = prepared.currency.value,
            subtotalMinorUnits = prepared.subtotal?.minorUnits ?: 0L,
            taxMinorUnits = prepared.tax?.minorUnits ?: 0L,
            otherChargesMinorUnits = prepared.otherCharges?.minorUnits ?: 0L,
            totalMinorUnits = prepared.total.minorUnits,
            status = PurchaseStatus.POSTED.name,
            idempotencyKey = PostingIdentity.purchaseKey(prepared),
            createdAt = postedAt,
            updatedAt = postedAt,
            postedAt = postedAt,
            documentIdentitySlot = duplicateOverride?.draftId?.value ?: PRIMARY_IDENTITY_SLOT,
            duplicateOverrideOfPurchaseId = duplicateOverride?.existingPurchaseId?.value,
            duplicateOverrideReason = duplicateOverride?.reason,
            duplicateOverrideActorId = duplicateOverride?.actor?.actorId,
            duplicateOverrideRole = duplicateOverride?.actor?.role?.name,
        )
        val entitiesByPreparedLine = lines.associate { resolved ->
            val frozen = resolved.prepared
            val calculation = resolved.calculation
            val purchaseLineId = PostingIdentity.uuid(
                "purchase-line",
                purchaseId,
                frozen.lineId.value,
            ).toString()
            frozen.lineId to PurchaseLineEntity(
                purchaseLineId = purchaseLineId,
                purchaseId = purchaseId,
                productId = frozen.productId.value,
                unitId = frozen.unitId.value,
                productNameSnapshot = resolved.product.name,
                unitCodeSnapshot = resolved.unit.code,
                position = frozen.position,
                rawText = frozen.rawText,
                description = frozen.description,
                quantity = frozen.quantity.value.toPlainString(),
                readUnitCost = requireNotNull(frozen.unitCost).amount.toPlainString(),
                currencyCode = prepared.currency.value,
                taxMinorUnits = frozen.tax?.minorUnits ?: 0L,
                totalMinorUnits = frozen.lineTotal?.minorUnits ?: 0L,
                confidence = frozen.linkConfidence,
                productProvenance = frozen.productProvenance.name,
                appliedUnitCost = calculation.appliedInventoryUnitCost.toPlainString(),
                purchaseUnitFactor = calculation.purchaseUnitFactor.toPlainString(),
                inventoryQuantity = calculation.inventoryQuantity.toPlainString(),
                discount = calculation.lineDiscount.toPlainString(),
                taxTreatment = calculation.taxTreatment.name,
                costPolicy = costPolicyName,
                appliedCostTotal = calculation.appliedCostTotal.toPlainString(),
                taxEvidenceType = calculation.taxEvidence.type.name,
                taxEvidenceValue = calculation.taxEvidence.value?.toPlainString(),
                roundingScale = calculation.roundingPolicy.scale,
                roundingMode = calculation.roundingPolicy.mode.name,
                costingWarnings = warningsByLine.getValue(frozen.lineId)
                    .map(Enum<*>::name).sorted().joinToString(","),
            )
        }
        val lineEntities = lines.sortedBy { it.prepared.position }.map {
            entitiesByPreparedLine.getValue(it.prepared.lineId)
        }
        val movements = lines.sortedBy { it.prepared.position }.map { resolved ->
            val lineEntity = entitiesByPreparedLine.getValue(resolved.prepared.lineId)
            StockMovementEntity(
                movementId = PostingIdentity.uuid(
                    "stock-movement",
                    purchaseId,
                    resolved.prepared.lineId.value,
                ).toString(),
                businessId = prepared.businessId.value,
                purchaseId = purchaseId,
                purchaseLineId = lineEntity.purchaseLineId,
                productId = resolved.product.productId,
                locationId = resolved.location.locationId,
                type = StockMovementType.PURCHASE.name,
                quantityDelta = resolved.calculation.inventoryQuantity.toPlainString(),
                unitCost = resolved.calculation.appliedInventoryUnitCost.toPlainString(),
                currencyCode = prepared.currency.value,
                idempotencyKey = PostingIdentity.movementKey(purchaseId, resolved.prepared.lineId.value),
                occurredAt = postedAt,
                createdAt = postedAt,
            )
        }
        val outboxPayload = postingPayload(
            purchaseId = purchaseId,
            prepared = prepared,
            aliasesCreated = aliasesCreated,
            aliasesSkipped = aliasesSkipped,
        )
        return PurchasePostingBatch(
            purchase = purchase,
            expectedPreparedLogicalHash = prepared.logicalHash,
            lines = lineEntities,
            balanceMutations = balanceMutations,
            movements = movements,
            auditEvents = buildList {
                duplicateOverride?.let { override ->
                    add(
                        AuditEventEntity(
                            auditEventId = PostingIdentity.uuid(
                                "purchase-duplicate-override-audit",
                                purchaseId,
                            ).toString(),
                            businessId = prepared.businessId.value,
                            purchaseId = purchaseId,
                            eventType = AuditEventType.PURCHASE_DUPLICATE_OVERRIDE.name,
                            entityType = "PURCHASE",
                            entityId = purchaseId,
                            payload = duplicateOverrideAuditPayload(purchaseId, override),
                            occurredAt = postedAt,
                        ),
                    )
                }
                add(
                    AuditEventEntity(
                        auditEventId = PostingIdentity.uuid(
                            "purchase-posted-audit",
                            purchaseId,
                        ).toString(),
                        businessId = prepared.businessId.value,
                        purchaseId = purchaseId,
                        eventType = AuditEventType.PURCHASE_POSTED.name,
                        entityType = "PURCHASE",
                        entityId = purchaseId,
                        payload = purchasePostedAuditPayload(
                            purchaseId = purchaseId,
                            prepared = prepared,
                            aliasesCreated = aliasesCreated,
                            aliasesSkipped = aliasesSkipped,
                        ),
                        occurredAt = postedAt,
                    ),
                )
            },
            outboxOperations = buildList {
                add(
                    OutboxOperationEntity(
                        operationId = PostingIdentity.uuid(
                            "sync-purchase-outbox",
                            purchaseId,
                        ).toString(),
                        businessId = prepared.businessId.value,
                        purchaseId = purchaseId,
                        idempotencyKey = PostingIdentity.outboxKey(purchaseId),
                        operationType = "SYNC_PURCHASE",
                        payload = outboxPayload,
                        status = OutboxOperationStatus.PENDING.name,
                        attemptCount = 0,
                        createdAt = postedAt,
                        updatedAt = postedAt,
                        nextAttemptAt = postedAt,
                        payloadVersion = SYNC_PURCHASE_PAYLOAD_VERSION,
                    ),
                )
                documentImages.forEach { image ->
                    val uploadPayload = DocumentBackupPayloadCodec.encodeUpload(
                        purchaseId = purchaseId,
                        image = image,
                    )
                    add(
                        OutboxOperationEntity(
                            operationId = DocumentBackupPayloadCodec.candidateOperationId(
                                purchaseId,
                                image.imageId,
                            ),
                            businessId = prepared.businessId.value,
                            purchaseId = purchaseId,
                            idempotencyKey = DocumentBackupPayloadCodec.candidateIdempotencyKey(
                                purchaseId = purchaseId,
                                imageId = image.imageId,
                                sourceSha256 = image.sha256,
                            ),
                            operationType = "SYNC_DOCUMENT_UPLOAD",
                            payload = uploadPayload,
                            status = OutboxOperationStatus.PENDING.name,
                            createdAt = postedAt,
                            updatedAt = postedAt,
                            nextAttemptAt = postedAt,
                            payloadVersion = DocumentBackupPayloadCodec.PAYLOAD_VERSION,
                            entityType = "DOCUMENT",
                            entityId = image.imageId,
                            entityVersion = 1L,
                        ),
                    )
                }
            },
        )
    }

    private fun postingPayload(
        purchaseId: String,
        prepared: PreparedPurchase,
        aliasesCreated: Int,
        aliasesSkipped: List<SkippedAlias>,
    ): String = buildString {
        append("{\"version\":").append(SYNC_PURCHASE_PAYLOAD_VERSION)
        append(",\"purchaseId\":\"").append(purchaseId).append('"')
        append(",\"draftId\":\"").append(prepared.draftId.value).append('"')
        append(",\"preparedHash\":\"").append(prepared.logicalHash).append('"')
        append(",\"reconciliationAdjustment\":")
        prepared.reconciliationAdjustment?.let { adjustment ->
            append("{\"minorUnits\":").append(adjustment.amount.minorUnits)
            append(",\"currency\":\"").append(adjustment.amount.currency.value).append('"')
            append(",\"reason\":\"").append(adjustment.reason.jsonEscaped()).append("\"}")
        } ?: append("null")
        append(",\"aliasesCreated\":").append(aliasesCreated)
        append(",\"aliasesSkipped\":[")
        append(aliasesSkipped.joinToString(",") { skipped ->
            "{\"lineId\":\"${skipped.lineId}\",\"existingProductId\":" +
                "\"${skipped.existingProductId}\",\"reason\":\"${skipped.reason}\"}"
        })
        append("]}")
    }

    private fun purchasePostedAuditPayload(
        purchaseId: String,
        prepared: PreparedPurchase,
        aliasesCreated: Int,
        aliasesSkipped: List<SkippedAlias>,
    ): String = mapOf(
        AuditPayloadKey.VERSION to "1",
        AuditPayloadKey.PURCHASE_ID to purchaseId,
        AuditPayloadKey.DRAFT_ID to prepared.draftId.value,
        AuditPayloadKey.ALIASES_CREATED_COUNT to aliasesCreated.toString(),
        AuditPayloadKey.ALIASES_SKIPPED_COUNT to aliasesSkipped.size.toString(),
        AuditPayloadKey.ADJUSTMENT_APPLIED to
            (prepared.reconciliationAdjustment != null).toString(),
    ).toAuditPayloadJson(AuditEventType.PURCHASE_POSTED)

    private fun duplicateOverrideAuditPayload(
        purchaseId: String,
        override: PurchaseDuplicateOverride,
    ): String = mapOf(
        AuditPayloadKey.VERSION to "1",
        AuditPayloadKey.PURCHASE_ID to purchaseId,
        AuditPayloadKey.DRAFT_ID to override.draftId.value,
        AuditPayloadKey.EXISTING_PURCHASE_ID to override.existingPurchaseId.value,
        AuditPayloadKey.DUPLICATE_KIND to override.duplicateKind.name,
        AuditPayloadKey.DUPLICATE_REASON_CODES to
            override.reasons.sortedBy(Enum<*>::name).joinToString(",") { it.name },
        AuditPayloadKey.ACTOR_ROLE to override.actor.role.name,
    ).toAuditPayloadJson(AuditEventType.PURCHASE_DUPLICATE_OVERRIDE)

    private fun String.jsonEscaped(): String = buildString(length) {
        this@jsonEscaped.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u%04x".format(character.code))
                } else {
                    append(character)
                }
            }
        }
    }

    private const val PRIMARY_IDENTITY_SLOT = "PRIMARY"

    /** Versión del payload JSON de `SYNC_PURCHASE`; una sola fuente para cuerpo y columna. */
    // v4 reconstruye backup/document v3 con la ubicación semántica y el costo total exacto
    // aplicado por línea. Las filas v2/v3 ya encoladas conservan su versión para que un ACK
    // perdido pueda repetirse con el hash remoto legacy sin mutar el wire.
    private const val SYNC_PURCHASE_PAYLOAD_VERSION = 4
}

/** UUID estable basado en SHA-256; fija bits RFC-4122 y no usa azar durante un reintento. */
private object PostingIdentity {
    fun uuid(kind: String, vararg components: String): UUID {
        val canonicalName = buildString {
            append("facturastock:purchase-posting:v1:").append(kind)
            components.forEach { component -> append('\u001f').append(component) }
        }
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(canonicalName.toByteArray(StandardCharsets.UTF_8))
            .copyOf(16)
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x50).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        val buffer = ByteBuffer.wrap(bytes)
        return UUID(buffer.long, buffer.long)
    }

    fun purchaseKey(prepared: PreparedPurchase): String =
        purchaseKey(prepared.draftId, prepared.logicalHash)

    fun purchaseKey(draftId: DraftId, logicalHash: String): String =
        "confirm-purchase:v1:${draftId.value}:$logicalHash"

    fun movementKey(purchaseId: String, lineId: String): String =
        "purchase-movement:v1:$purchaseId:$lineId"

    fun outboxKey(purchaseId: String): String = "sync-purchase:v1:$purchaseId"
}

private inline fun <T> Iterable<T>.sumExact(transform: (T) -> BigDecimal): BigDecimal =
    fold(BigDecimal.ZERO) { total, item -> total.add(transform(item)) }
