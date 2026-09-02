package com.facturastock.app.data.repository

import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.data.local.FacturaStockDatabase
import com.facturastock.app.data.local.codec.PreparedPurchaseCodec
import com.facturastock.app.data.local.dao.BusinessAuditReadRow
import com.facturastock.app.data.local.dao.PurchaseAuditReadRow
import com.facturastock.app.data.local.dao.PurchaseLineReadRow
import com.facturastock.app.data.local.dao.PurchaseMovementReadRow
import com.facturastock.app.data.local.dao.PurchaseReadHeaderRow
import com.facturastock.app.data.local.dao.PurchaseReadSummaryRow
import com.facturastock.app.data.local.dao.RetainedImageRow
import com.facturastock.app.data.local.entity.InvoiceImageEntity
import com.facturastock.app.data.local.entity.PreparedPurchaseEntity
import com.facturastock.app.data.local.storageCatching
import com.facturastock.app.domain.model.AuditEventType
import com.facturastock.app.domain.model.BusinessAuditEventRead
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.InvoiceDocumentNumber
import com.facturastock.app.domain.model.InventoryTaxEvidence
import com.facturastock.app.domain.model.InventoryTaxEvidenceType
import com.facturastock.app.domain.model.InventoryTaxTreatment
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.OutboxOperationStatus
import com.facturastock.app.domain.model.PreparedPurchase
import com.facturastock.app.domain.model.PurchaseDocumentType
import com.facturastock.app.domain.model.PurchaseOverrideRole
import com.facturastock.app.domain.model.PurchaseHistoryPage
import com.facturastock.app.domain.model.PurchaseHistoryRequest
import com.facturastock.app.domain.model.PurchaseProductProvenance
import com.facturastock.app.domain.model.PurchaseReadAuditEvent
import com.facturastock.app.domain.model.PurchaseReadDetail
import com.facturastock.app.domain.model.PurchaseReadDuplicateOverride
import com.facturastock.app.domain.model.PurchaseReadLine
import com.facturastock.app.domain.model.PurchaseReadMovement
import com.facturastock.app.domain.model.PurchaseReadSummary
import com.facturastock.app.domain.model.PurchaseRetainedImage
import com.facturastock.app.domain.model.PurchaseStatus
import com.facturastock.app.domain.model.PurchaseSyncState
import com.facturastock.app.domain.model.RetainedImageRef
import com.facturastock.app.domain.model.StockMovementType
import com.facturastock.app.domain.model.UnitCost
import com.facturastock.app.domain.model.id.BusinessId
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.ImageId
import com.facturastock.app.domain.model.id.LocationId
import com.facturastock.app.domain.model.id.ProductId
import com.facturastock.app.domain.model.id.PurchaseId
import com.facturastock.app.domain.model.id.SupplierId
import com.facturastock.app.domain.model.id.UnitId
import com.facturastock.app.domain.repository.PurchaseReadRepository
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext

/**
 * Reconstruye compras publicadas exclusivamente desde Room. La cabecera abre el agregado y fija
 * business/draft; las colecciones posteriores son históricas append-only, salvo el estado outbox.
 */
class RoomPurchaseReadRepository @Inject constructor(
    private val database: FacturaStockDatabase,
    private val dispatchers: DispatcherProvider,
) : PurchaseReadRepository {
    override fun observePurchases(businessId: BusinessId): Flow<List<PurchaseReadSummary>> =
        database.purchaseDao()
            .observeReadSummaries(businessId.value)
            .map { rows -> rows.map(PurchaseReadSummaryRow::toDomain) }
            .flowOn(dispatchers.io)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observePurchaseHistory(
        businessId: BusinessId,
        request: PurchaseHistoryRequest,
    ): Flow<PurchaseHistoryPage> = database.purchaseDao()
        .observeReadSummaryInvalidations(businessId.value)
        .mapLatest {
            loadPurchaseHistoryWindow(
                businessId = businessId,
                request = request,
            )
        }
        .flowOn(dispatchers.io)

    private suspend fun loadPurchaseHistoryWindow(
        businessId: BusinessId,
        request: PurchaseHistoryRequest,
    ): PurchaseHistoryPage {
        val visibleLimit = request.visibleItemLimit
        val matches = ArrayList<PurchaseReadSummary>(minOf(visibleLimit, 1_023) + 1)
        var beforeCreatedAt: Long? = null
        var beforePurchaseId: String? = null
        val scanBatchSize = maxOf(HISTORY_SCAN_BATCH_SIZE, request.pageSize + 1)

        while (matches.size <= visibleLimit) {
            val rows = database.purchaseDao().listReadSummaryPage(
                businessId = businessId.value,
                status = request.status?.name,
                syncStatus = request.syncState.toOutboxStatusFilter(),
                beforeCreatedAt = beforeCreatedAt,
                beforePurchaseId = beforePurchaseId,
                limit = scanBatchSize,
            )
            if (rows.isEmpty()) break

            for (row in rows) {
                beforeCreatedAt = row.historyCreatedAt
                beforePurchaseId = row.purchaseId
                val purchase = row.toDomain()
                if (request.matches(purchase)) {
                    matches += purchase
                    if (matches.size > visibleLimit) {
                        return PurchaseHistoryPage(
                            items = matches.subList(0, visibleLimit).toList(),
                            hasMore = true,
                        )
                    }
                }
            }
            if (rows.size < scanBatchSize) break
        }

        return PurchaseHistoryPage(items = matches, hasMore = false)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observePurchase(
        businessId: BusinessId,
        purchaseId: PurchaseId,
    ): Flow<PurchaseReadDetail?> = database.purchaseDao()
        .observeReadHeader(businessId.value, purchaseId.value)
        .flatMapLatest { header ->
            if (header == null) {
                flowOf(null)
            } else {
                combine(
                    database.purchaseLineDao().observeReadLines(purchaseId.value),
                    database.inventoryDao().observeReadMovements(
                        businessId.value,
                        purchaseId.value,
                    ),
                    database.auditEventDao().observeForPurchase(
                        businessId.value,
                        purchaseId.value,
                    ),
                    database.preparedPurchaseDao().observe(header.sourceDraftId),
                    database.invoiceImageDao().observeForCommittedPurchase(
                        businessId.value,
                        header.sourceDraftId,
                    ),
                ) { lines, movements, audit, prepared, images ->
                    assembleDetail(
                        header = header,
                        lineRows = lines,
                        movementRows = movements,
                        auditRows = audit,
                        preparedEntity = prepared,
                        imageRows = images,
                    )
                }
            }
        }
        .flowOn(dispatchers.io)

    override suspend fun listRetainedImagesForRetention(
        businessId: BusinessId,
    ): List<RetainedImageRef> = withContext(dispatchers.io) {
        storageCatching {
            database.purchaseDao().listRetainedImagesForRetention(businessId.value).map { row ->
                RetainedImageRef(
                    businessId = businessId,
                    purchaseId = parsePurchaseId(row.purchaseId),
                    draftId = parseDraftId(row.sourceDraftId),
                    imageId = requireNotNull(ImageId.parse(row.imageId)) {
                        "imageId persistido inválido"
                    },
                    relativeFilePath = row.filePath,
                    postedAt = Instant.ofEpochMilli(row.postedAt),
                    sourceImageCreatedAt = Instant.ofEpochMilli(row.imageCreatedAt),
                )
            }
        }
    }

    override suspend fun listRetainedImagesForDraft(
        businessId: BusinessId,
        draftId: DraftId,
    ): List<RetainedImageRef> = withContext(dispatchers.io) {
        storageCatching {
            database.purchaseDao()
                .listRetainedImagesForDraft(businessId.value, draftId.value)
                .map { row -> row.toRetainedImageRef() }
        }
    }

    override suspend fun listAllRetainedImagesForRetention(): List<RetainedImageRef> =
        withContext(dispatchers.io) {
            storageCatching {
                database.purchaseDao().listAllRetainedImagesForRetention()
                    .map { row -> row.toRetainedImageRef() }
            }
        }

    override suspend fun listAuditEvents(
        businessId: BusinessId,
    ): List<BusinessAuditEventRead> = withContext(dispatchers.io) {
        storageCatching {
            database.auditEventDao().listReadEventsForBusiness(businessId.value)
                .map(BusinessAuditReadRow::toDomain)
        }
    }

    private fun RetainedImageRow.toRetainedImageRef(): RetainedImageRef = RetainedImageRef(
        businessId = parseBusinessId(businessId),
        purchaseId = parsePurchaseId(purchaseId),
        draftId = parseDraftId(sourceDraftId),
        imageId = requireNotNull(ImageId.parse(imageId)) { "imageId persistido inválido" },
        relativeFilePath = filePath,
        postedAt = Instant.ofEpochMilli(postedAt),
        sourceImageCreatedAt = Instant.ofEpochMilli(imageCreatedAt),
    )

    private fun assembleDetail(
        header: PurchaseReadHeaderRow,
        lineRows: List<PurchaseLineReadRow>,
        movementRows: List<PurchaseMovementReadRow>,
        auditRows: List<PurchaseAuditReadRow>,
        preparedEntity: PreparedPurchaseEntity?,
        imageRows: List<InvoiceImageEntity>,
    ): PurchaseReadDetail {
        val prepared = decodeAndValidate(
            preparedEntity ?: corrupt("La compra publicada no conserva su instantánea preparada"),
        )
        validateHeader(header, prepared)
        validateLines(lineRows, prepared)

        val summary = header.toSummary()
        val currency = summary.currency
        val lines = lineRows.map { it.toDomain(currency) }
        val movementLineIds = lines.mapTo(hashSetOf(), PurchaseReadLine::purchaseLineId)
        val movements = movementRows.map { row -> row.toDomain(currency, movementLineIds) }
        return PurchaseReadDetail(
            summary = summary,
            supplierId = parseSupplierId(header.supplierId),
            subtotal = Money.ofMinor(header.subtotalMinorUnits, currency),
            tax = Money.ofMinor(header.taxMinorUnits, currency),
            otherCharges = Money.ofMinor(header.otherChargesMinorUnits, currency),
            adjustment = prepared.readReconciliationAdjustment(),
            lines = lines,
            movements = movements,
            auditEvents = auditRows.map(PurchaseAuditReadRow::toDomain),
            images = imageRows.map(InvoiceImageEntity::toDomain),
            preparedLogicalHash = prepared.logicalHash,
            acceptedWarnings = prepared.acceptedWarnings,
            adjustmentReason = prepared.reconciliationAdjustment?.reason,
            duplicateOverride = header.toDuplicateOverride(),
        )
    }
}

private fun PurchaseReadSummaryRow.toDomain(): PurchaseReadSummary {
    val currency = CurrencyCode.of(currencyCode)
    return PurchaseReadSummary(
        purchaseId = parsePurchaseId(purchaseId),
        businessId = parseBusinessId(businessId),
        sourceDraftId = parseDraftId(sourceDraftId),
        supplierRuc = supplierRuc,
        supplierLegalName = supplierLegalName,
        documentType = parseEnum(documentType, "documentType"),
        documentSeries = documentSeries,
        documentNumber = documentNumber,
        issueDate = parseDate(issueDate),
        currency = currency,
        total = Money.ofMinor(totalMinorUnits, currency),
        status = parseEnum(status, "purchase status"),
        syncState = syncStatus.toSyncState(),
        lineCount = lineCount,
        productCount = productCount,
        postedAt = postedAt?.let(Instant::ofEpochMilli),
        createdProductCount = createdProductCount,
        existingProductCount = existingProductCount,
        unknownProductCount = unknownProductCount,
        lastSyncError = syncLastError,
        lastSyncAttemptAt = syncUpdatedAt?.let(Instant::ofEpochMilli),
    )
}

private fun PurchaseReadHeaderRow.toSummary(): PurchaseReadSummary {
    val currency = CurrencyCode.of(currencyCode)
    return PurchaseReadSummary(
        purchaseId = parsePurchaseId(purchaseId),
        businessId = parseBusinessId(businessId),
        sourceDraftId = parseDraftId(sourceDraftId),
        supplierRuc = supplierRuc,
        supplierLegalName = supplierLegalName,
        documentType = parseEnum(documentType, "documentType"),
        documentSeries = documentSeries,
        documentNumber = documentNumber,
        issueDate = parseDate(issueDate),
        currency = currency,
        total = Money.ofMinor(totalMinorUnits, currency),
        status = parseEnum(status, "purchase status"),
        syncState = syncStatus.toSyncState(),
        lineCount = lineCount,
        productCount = productCount,
        postedAt = postedAt?.let(Instant::ofEpochMilli),
        createdProductCount = createdProductCount,
        existingProductCount = existingProductCount,
        unknownProductCount = unknownProductCount,
        lastSyncError = syncLastError,
        lastSyncAttemptAt = syncUpdatedAt?.let(Instant::ofEpochMilli),
    )
}

private fun PurchaseLineReadRow.toDomain(currency: CurrencyCode): PurchaseReadLine {
    if (currencyCode != currency.value) corrupt("Moneda de línea inconsistente")
    return PurchaseReadLine(
        purchaseLineId = purchaseLineId,
        position = position,
        productId = parseProductId(productId),
        productName = productName,
        unitId = parseUnitId(unitId),
        unitCode = unitCode,
        unitSymbol = unitSymbol,
        rawText = rawText,
        description = description,
        quantity = decimal(quantity, "quantity"),
        readUnitCost = UnitCost.of(readUnitCost, currency),
        tax = Money.ofMinor(taxMinorUnits, currency),
        total = Money.ofMinor(totalMinorUnits, currency),
        appliedUnitCost = appliedUnitCost?.let { UnitCost.of(it, currency) },
        appliedCostTotal = appliedCostTotal?.let { decimal(it, "appliedCostTotal") },
        inventoryQuantity = inventoryQuantity?.let { decimal(it, "inventoryQuantity") },
        discount = discount?.let { decimal(it, "discount") },
        productProvenance = parseEnum<PurchaseProductProvenance>(
            productProvenance,
            "product provenance",
        ),
        taxTreatment = taxTreatment?.let {
            parseEnum<InventoryTaxTreatment>(it, "tax treatment")
        },
        taxEvidence = toTaxEvidence(),
    )
}

private fun PurchaseLineReadRow.toTaxEvidence(): InventoryTaxEvidence? {
    val typeText = taxEvidenceType
    if (typeText == null) {
        if (taxEvidenceValue != null) corrupt("Valor tributario sin tipo de evidencia")
        return null
    }
    return when (parseEnum<InventoryTaxEvidenceType>(typeText, "tax evidence type")) {
        InventoryTaxEvidenceType.NONE -> {
            if (taxEvidenceValue != null) corrupt("Evidencia NONE con valor")
            InventoryTaxEvidence.None
        }
        InventoryTaxEvidenceType.EXPLICIT_AMOUNT -> InventoryTaxEvidence.ExplicitAmount(
            decimal(taxEvidenceValue ?: corrupt("Importe tributario ausente"), "tax evidence"),
        )
        InventoryTaxEvidenceType.EXPLICIT_RATE -> InventoryTaxEvidence.ExplicitRate(
            decimal(taxEvidenceValue ?: corrupt("Tasa tributaria ausente"), "tax evidence"),
        )
    }
}

private fun PurchaseMovementReadRow.toDomain(
    expectedCurrency: CurrencyCode,
    knownLineIds: Set<String>,
): PurchaseReadMovement {
    if (currencyCode != expectedCurrency.value) corrupt("Moneda de movimiento inconsistente")
    if (purchaseLineId !in knownLineIds) corrupt("Movimiento ligado a una línea ajena")
    return PurchaseReadMovement(
        movementId = movementId,
        purchaseId = parsePurchaseId(purchaseId),
        purchaseLineId = purchaseLineId,
        productId = parseProductId(productId),
        productName = productName,
        locationId = LocationId.parse(locationId) ?: corrupt("locationId inválido"),
        locationName = locationName,
        type = parseEnum(type, "movement type"),
        quantityDelta = decimal(quantityDelta, "quantityDelta"),
        unitCost = unitCost?.let { UnitCost.of(it, expectedCurrency) },
        occurredAt = Instant.ofEpochMilli(occurredAt),
    )
}

private fun PurchaseAuditReadRow.toDomain(): PurchaseReadAuditEvent = PurchaseReadAuditEvent(
    auditEventId = auditEventId,
    eventType = parseEnum(eventType, "audit event type"),
    entityType = entityType,
    entityId = entityId,
    occurredAt = Instant.ofEpochMilli(occurredAt),
)

private fun BusinessAuditReadRow.toDomain(): BusinessAuditEventRead = BusinessAuditEventRead(
    auditEventId = auditEventId,
    purchaseId = purchaseId?.let(::parsePurchaseId),
    eventType = parseEnum(eventType, "audit event type"),
    entityType = entityType,
    entityId = entityId,
    occurredAt = Instant.ofEpochMilli(occurredAt),
)

private fun PurchaseReadHeaderRow.toDuplicateOverride(): PurchaseReadDuplicateOverride? {
    val fields = listOf(
        duplicateOverrideOfPurchaseId,
        duplicateOverrideReason,
        duplicateOverrideActorId,
        duplicateOverrideRole,
    )
    if (fields.all { it == null }) return null
    if (fields.any { it == null }) corrupt("Metadatos incompletos de excepción de duplicado")

    val reason = requireNotNull(duplicateOverrideReason)
    val actorId = requireNotNull(duplicateOverrideActorId)
    val role = parseEnum<PurchaseOverrideRole>(
        requireNotNull(duplicateOverrideRole),
        "duplicateOverrideRole",
    )
    if (reason != reason.trim() || reason.length !in 10..500) {
        corrupt("Motivo de excepción de duplicado inválido")
    }
    if (actorId.isBlank() || actorId.length > 128) {
        corrupt("Actor de excepción de duplicado inválido")
    }
    if (role != PurchaseOverrideRole.OWNER && role != PurchaseOverrideRole.MANAGER) {
        corrupt("Rol de excepción de duplicado no autorizado")
    }
    return PurchaseReadDuplicateOverride(
        existingPurchaseId = parsePurchaseId(requireNotNull(duplicateOverrideOfPurchaseId)),
        reason = reason,
        actorId = actorId,
        actorRole = role,
    )
}

private fun InvoiceImageEntity.toDomain(): PurchaseRetainedImage = PurchaseRetainedImage(
    imageId = ImageId.parse(imageId) ?: corrupt("imageId inválido"),
    pageIndex = pageIndex,
    relativeFilePath = filePath,
    mimeType = mimeType,
    widthPx = widthPx,
    heightPx = heightPx,
    rotationDegrees = rotationDegrees,
    cropLeftFraction = cropLeftFraction,
    cropTopFraction = cropTopFraction,
    cropRightFraction = cropRightFraction,
    cropBottomFraction = cropBottomFraction,
)

private fun String?.toSyncState(): PurchaseSyncState = when (this) {
    null -> PurchaseSyncState.DRAFT
    OutboxOperationStatus.PENDING.name -> PurchaseSyncState.PENDING_SYNC
    OutboxOperationStatus.PROCESSING.name -> PurchaseSyncState.SYNCING
    OutboxOperationStatus.COMPLETED.name -> PurchaseSyncState.SYNCED
    OutboxOperationStatus.FAILED.name -> PurchaseSyncState.ERROR
    OutboxOperationStatus.CONFLICT.name -> PurchaseSyncState.CONFLICT
    OutboxOperationStatus.RESOLVED.name -> PurchaseSyncState.RESOLVED
    else -> corrupt("Estado outbox desconocido")
}

private fun PurchaseSyncState?.toOutboxStatusFilter(): String? = when (this) {
    null -> null
    PurchaseSyncState.DRAFT -> ""
    PurchaseSyncState.PENDING_SYNC -> OutboxOperationStatus.PENDING.name
    PurchaseSyncState.SYNCING -> OutboxOperationStatus.PROCESSING.name
    PurchaseSyncState.SYNCED -> OutboxOperationStatus.COMPLETED.name
    PurchaseSyncState.ERROR -> OutboxOperationStatus.FAILED.name
    PurchaseSyncState.CONFLICT -> OutboxOperationStatus.CONFLICT.name
    PurchaseSyncState.RESOLVED -> OutboxOperationStatus.RESOLVED.name
}

private const val HISTORY_SCAN_BATCH_SIZE = 64

private fun decodeAndValidate(entity: PreparedPurchaseEntity): PreparedPurchase {
    if (!PreparedPurchaseCodec.supports(entity.payloadCodecVersion)) {
        corrupt("Versión de compra preparada desconocida")
    }
    if (PreparedPurchaseCodec.sha256(entity.payload) != entity.payloadSha256) {
        corrupt("Hash físico de compra preparada inconsistente")
    }
    val prepared = PreparedPurchaseCodec.decode(entity.payload)
    if (
        prepared.draftId.value != entity.draftId ||
        prepared.logicalHash != entity.logicalHash ||
        prepared.preparedAt.toEpochMilli() != entity.preparedAt
    ) {
        corrupt("Metadatos de compra preparada inconsistentes")
    }
    return prepared
}

private fun validateHeader(row: PurchaseReadHeaderRow, prepared: PreparedPurchase) {
    val document = InvoiceDocumentNumber.parseCanonical(prepared.documentNumber)
        ?: corrupt("Documento preparado no canónico")
    val expectedType = prepared.documentType ?: corrupt("Tipo de documento preparado ausente")
    val expected = listOf(
        row.businessId == prepared.businessId.value,
        row.sourceDraftId == prepared.draftId.value,
        row.documentType == expectedType.name,
        row.documentSeries == document.series,
        row.documentNumber == document.correlative,
        row.issueDate == prepared.issueDate.toString(),
        row.currencyCode == prepared.currency.value,
        row.subtotalMinorUnits == (prepared.subtotal?.minorUnits ?: 0L),
        row.taxMinorUnits == (prepared.tax?.minorUnits ?: 0L),
        row.otherChargesMinorUnits == (prepared.otherCharges?.minorUnits ?: 0L),
        row.totalMinorUnits == prepared.total.minorUnits,
        row.lineCount == prepared.lines.size,
    )
    if (expected.any { matches -> !matches }) {
        corrupt("La cabecera publicada diverge de PreparedPurchase")
    }
}

private fun validateLines(rows: List<PurchaseLineReadRow>, prepared: PreparedPurchase) {
    if (rows.size != prepared.lines.size) corrupt("Cantidad de líneas publicada inconsistente")
    rows.zip(prepared.lines).forEach { (row, frozen) ->
        val taxAuditMatches = if (
            row.taxTreatment == null &&
            row.taxEvidenceType == null &&
            row.taxEvidenceValue == null
        ) {
            frozen.taxTreatment == InventoryTaxTreatment.UNKNOWN &&
                frozen.taxEvidence == InventoryTaxEvidence.None
        } else {
            row.taxTreatment == frozen.taxTreatment.name &&
                row.taxEvidenceType == frozen.taxEvidence.type.name &&
                row.taxEvidenceValue.decimalEquals(frozen.taxEvidence.value)
        }
        val matches = row.position == frozen.position &&
            row.productId == frozen.productId.value &&
            row.unitId == frozen.unitId.value &&
            row.rawText == frozen.rawText &&
            row.description == frozen.description &&
            decimal(row.quantity, "quantity").compareTo(frozen.quantity.value) == 0 &&
            frozen.unitCost != null &&
            decimal(row.readUnitCost, "readUnitCost").compareTo(frozen.unitCost.amount) == 0 &&
            row.currencyCode == prepared.currency.value &&
            row.taxMinorUnits == (frozen.tax?.minorUnits ?: 0L) &&
            row.totalMinorUnits == (frozen.lineTotal?.minorUnits ?: 0L) &&
            row.productProvenance == frozen.productProvenance.name &&
            taxAuditMatches
        if (!matches) corrupt("La línea publicada ${row.position} diverge de PreparedPurchase")
    }
}

private fun String?.decimalEquals(expected: BigDecimal?): Boolean = when {
    this == null || expected == null -> this == null && expected == null
    else -> decimal(this, "taxEvidenceValue").compareTo(expected) == 0
}

/** Signo de conciliación: suma de líneas + ajuste = total del comprobante. */
private fun PreparedPurchase.readReconciliationAdjustment(): Money? {
    val derived = if ("LINES_TOTAL_DIFFERENCE" in acceptedWarnings) {
        val lineTotals = lines.map { line -> line.lineTotal ?: return@readReconciliationAdjustment null }
        val sum = lineTotals.fold(Money.zero(currency), Money::plus)
        (total - sum).takeUnless { it.minorUnits == 0L }
    } else if ("TOTAL_DIFFERENCE" in acceptedWarnings) {
        val frozenSubtotal = subtotal ?: return null
        val frozenTax = tax ?: return null
        val charges = otherCharges ?: Money.zero(currency)
        (total - (frozenSubtotal + frozenTax + charges))
            .takeUnless { it.minorUnits == 0L }
    } else {
        null
    }
    val frozen = reconciliationAdjustment?.amount
    if (frozen != null && frozen != derived) {
        corrupt("El ajuste congelado diverge de los importes preparados")
    }
    return frozen ?: derived
}

private fun parsePurchaseId(value: String): PurchaseId =
    PurchaseId.parse(value) ?: corrupt("purchaseId inválido")

private fun parseBusinessId(value: String): BusinessId =
    BusinessId.parse(value) ?: corrupt("businessId inválido")

private fun parseDraftId(value: String): DraftId =
    DraftId.parse(value) ?: corrupt("draftId inválido")

private fun parseSupplierId(value: String): SupplierId =
    SupplierId.parse(value) ?: corrupt("supplierId inválido")

private fun parseProductId(value: String): ProductId =
    ProductId.parse(value) ?: corrupt("productId inválido")

private fun parseUnitId(value: String): UnitId =
    UnitId.parse(value) ?: corrupt("unitId inválido")

private fun parseDate(value: String): LocalDate = try {
    LocalDate.parse(value)
} catch (failure: RuntimeException) {
    corrupt("Fecha de compra inválida", failure)
}

private fun decimal(value: String, field: String): BigDecimal = try {
    BigDecimal(value)
} catch (failure: NumberFormatException) {
    corrupt("Decimal inválido en $field", failure)
}

private inline fun <reified T : Enum<T>> parseEnum(value: String, field: String): T =
    enumValues<T>().firstOrNull { it.name == value } ?: corrupt("Enum inválido en $field")

private fun corrupt(message: String, cause: Throwable? = null): Nothing =
    throw IOException(message, cause)
