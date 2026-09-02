package com.facturastock.app.domain.usecase

import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.CatalogStatus
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.InvoiceHeaderEdit
import com.facturastock.app.domain.model.InvoiceLineEditField
import com.facturastock.app.domain.model.InvoiceLinesEdit
import com.facturastock.app.domain.model.InventoryTaxEvidence
import com.facturastock.app.domain.model.InventoryTaxTreatment
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.PrepareBlocker
import com.facturastock.app.domain.model.PrepareBlockerCode
import com.facturastock.app.domain.model.PreparedPurchase
import com.facturastock.app.domain.model.PreparedPurchaseLine
import com.facturastock.app.domain.model.PurchaseReconciliationAdjustment
import com.facturastock.app.domain.model.PurchaseDocumentType
import com.facturastock.app.domain.model.PurchaseProductProvenance
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.UnitCost
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.observability.DisabledProductionObservability
import com.facturastock.app.domain.observability.InternalIdentifiers
import com.facturastock.app.domain.observability.OperationalAction
import com.facturastock.app.domain.observability.OperationalAuditEvent
import com.facturastock.app.domain.observability.OperationalErrorCode
import com.facturastock.app.domain.observability.OperationalOutcome
import com.facturastock.app.domain.observability.ProductionObservability
import com.facturastock.app.domain.repository.InvoiceDraftRepository
import com.facturastock.app.domain.repository.InvoiceHeaderReviewRepository
import com.facturastock.app.domain.repository.InvoiceLinesReviewRepository
import com.facturastock.app.domain.repository.PreparedPurchaseRepository
import com.facturastock.app.domain.repository.ProductRepository
import com.facturastock.app.domain.repository.PublishPreparedPurchaseResult
import com.facturastock.app.domain.repository.UnitRepository
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale
import java.util.concurrent.CancellationException

/** Código de advertencia registrado cuando la suma de líneas difiere del total del documento. */
const val WARNING_LINES_TOTAL_DIFFERENCE: String = "LINES_TOTAL_DIFFERENCE"

/**
 * Validación agregada de preparación: reúne TODOS los bloqueos (cabecera, cada línea y la
 * aceptación del redondeo) en una sola pasada, sin detenerse en el primero.
 */
class PurchaseReadinessValidator(
    private val headerValidator: InvoiceHeaderReviewValidator = InvoiceHeaderReviewValidator(),
    private val lineValidator: InvoiceLineReviewValidator = InvoiceLineReviewValidator(),
    private val lineCalculator: InvoiceLineReviewCalculator = InvoiceLineReviewCalculator(),
) {
    fun assess(
        draft: InvoiceDraft,
        headerEdit: InvoiceHeaderEdit?,
        linesEdit: InvoiceLinesEdit?,
        roundingAccepted: Boolean,
        adjustmentReason: String? = null,
    ): List<PrepareBlocker> {
        val blockers = mutableListOf<PrepareBlocker>()
        if (headerEdit == null) {
            blockers += PrepareBlocker(PrepareBlockerCode.HEADER_MISSING)
        } else {
            headerValidator.validate(headerEdit).errors.forEach { error ->
                blockers += PrepareBlocker(PrepareBlockerCode.HEADER_INVALID, detail = error.field.name)
            }
        }
        val documentType = draft.documentType ?: headerEdit
            ?.documentType
            ?.trim()
            ?.uppercase(Locale.ROOT)
            ?.let { value -> runCatching { PurchaseDocumentType.valueOf(value) }.getOrNull() }
        if (documentType == PurchaseDocumentType.CREDIT_NOTE) {
            blockers += PrepareBlocker(PrepareBlockerCode.CREDIT_NOTE_UNSUPPORTED)
        }
        val activeLines = linesEdit?.activeLines.orEmpty()
        if (activeLines.isEmpty()) {
            blockers += PrepareBlocker(PrepareBlockerCode.NO_LINES)
        }
        activeLines.forEach { line ->
            val validation = lineValidator.validate(line, draft.currency)
            validation.errors.forEach { error ->
                blockers += when (error.field) {
                    InvoiceLineEditField.DESCRIPTION ->
                        PrepareBlocker(PrepareBlockerCode.LINE_DESCRIPTION_MISSING, line.lineId)
                    InvoiceLineEditField.QUANTITY ->
                        PrepareBlocker(PrepareBlockerCode.LINE_QUANTITY_INVALID, line.lineId)
                    else ->
                        PrepareBlocker(PrepareBlockerCode.LINE_AMOUNT_INVALID, line.lineId, error.field.name)
                }
            }
            // Una línea con campos inválidos ya reportó su causa; REVIEW_PENDING señala la
            // confirmación humana pendiente sobre campos formalmente válidos.
            if (
                line.isPending && validation.isValid &&
                line.taxTreatment != InventoryTaxTreatment.UNKNOWN &&
                line.productProvenance != PurchaseProductProvenance.UNKNOWN_LEGACY
            ) {
                blockers += PrepareBlocker(PrepareBlockerCode.LINE_REVIEW_PENDING, line.lineId)
            }
            if (line.productProvenance == PurchaseProductProvenance.UNKNOWN_LEGACY) {
                blockers += PrepareBlocker(
                    PrepareBlockerCode.LINE_PRODUCT_PROVENANCE_REQUIRED,
                    line.lineId,
                    PurchaseProductProvenance.UNKNOWN_LEGACY.name,
                )
            }
            when {
                line.taxTreatment == InventoryTaxTreatment.UNKNOWN -> blockers += PrepareBlocker(
                    PrepareBlockerCode.LINE_TAX_DECISION_REQUIRED,
                    line.lineId,
                    InventoryTaxTreatment.UNKNOWN.name,
                )
                line.taxTreatment in setOf(
                    InventoryTaxTreatment.INCLUDED,
                    InventoryTaxTreatment.EXCLUDED,
                ) && line.igv.selectedValue.isNullOrBlank() -> blockers += PrepareBlocker(
                    PrepareBlockerCode.LINE_TAX_DECISION_REQUIRED,
                    line.lineId,
                    "TAX_EVIDENCE_REQUIRED",
                )
                line.taxTreatment == InventoryTaxTreatment.EXEMPT &&
                    line.igv.selectedValue?.trim()?.takeIf(String::isNotEmpty)?.let { amount ->
                        draft.currency?.let { currency ->
                            runCatching { Money.fromMajor(amount, currency).minorUnits > 0L }
                                .getOrDefault(false)
                        }
                    } == true -> blockers += PrepareBlocker(
                    PrepareBlockerCode.LINE_TAX_DECISION_REQUIRED,
                    line.lineId,
                    "EXEMPT_WITH_POSITIVE_TAX",
                )
            }
            if (line.linkedProductId == null || line.linkedUnitId == null) {
                blockers += PrepareBlocker(PrepareBlockerCode.LINE_PRODUCT_MISSING, line.lineId)
            }
            val hasValidUnitCost = line.unitCost.selectedValue?.trim()?.takeIf(String::isNotEmpty)
                ?.let { cost ->
                    draft.currency != null && runCatching {
                        UnitCost.of(cost, draft.currency)
                    }.isSuccess
                } == true
            if (!hasValidUnitCost && validation.errors.none { error ->
                    error.field == InvoiceLineEditField.UNIT_COST
                }
            ) {
                blockers += PrepareBlocker(
                    PrepareBlockerCode.LINE_AMOUNT_INVALID,
                    line.lineId,
                    InvoiceLineEditField.UNIT_COST.name,
                )
            }
            val hasValidLineAmount = line.total.selectedValue?.trim()?.takeIf(String::isNotEmpty)
                ?.let { total ->
                    draft.currency != null && runCatching {
                        Money.fromMajor(total, draft.currency)
                    }.isSuccess
                } == true
            if (!hasValidLineAmount && validation.errors.none { error ->
                    error.field in setOf(
                        InvoiceLineEditField.UNIT_COST,
                        InvoiceLineEditField.DISCOUNT,
                        InvoiceLineEditField.IGV,
                        InvoiceLineEditField.TOTAL,
                    )
                }
            ) {
                blockers += PrepareBlocker(
                    PrepareBlockerCode.LINE_AMOUNT_INVALID,
                    line.lineId,
                    InvoiceLineEditField.TOTAL.name,
                )
            }
        }
        if (roundingDifference(draft, headerEdit, linesEdit) != null) {
            when {
                !roundingAccepted ->
                    blockers += PrepareBlocker(PrepareBlockerCode.ROUNDING_ACCEPTANCE_REQUIRED)
                !PurchaseReconciliationAdjustment.isValidReason(adjustmentReason) ->
                    blockers += PrepareBlocker(PrepareBlockerCode.ADJUSTMENT_REASON_REQUIRED)
            }
        }
        return blockers
    }

    /** Diferencia exacta que exige aceptación explícita; null si la compra cuadra al céntimo. */
    fun roundingDifference(
        draft: InvoiceDraft,
        headerEdit: InvoiceHeaderEdit?,
        linesEdit: InvoiceLinesEdit?,
    ): Money? =
        linesAdjustment(draft, linesEdit) ?: headerEdit
            ?.let { edit ->
                headerValidator.validate(edit).warnings
                    .firstOrNull { warning ->
                        warning.code == InvoiceHeaderValidationWarningCode.TOTAL_DIFFERENCE
                    }
                    ?.difference
            }

    /** Advertencias presentes al preparar; solo se registran tras la aceptación explícita. */
    fun acceptedWarnings(
        draft: InvoiceDraft,
        headerEdit: InvoiceHeaderEdit?,
        linesEdit: InvoiceLinesEdit?,
    ): List<String> = buildList {
        if (
            headerEdit != null &&
            headerValidator.validate(headerEdit).warnings.any { warning ->
                warning.code == InvoiceHeaderValidationWarningCode.TOTAL_DIFFERENCE
            }
        ) {
            add(InvoiceHeaderValidationWarningCode.TOTAL_DIFFERENCE.name)
        }
        if (linesAdjustment(draft, linesEdit) != null) {
            add(WARNING_LINES_TOTAL_DIFFERENCE)
        }
    }

    /** Convención durable y visible: ajuste = total objetivo - suma calculada de líneas. */
    private fun linesAdjustment(draft: InvoiceDraft, linesEdit: InvoiceLinesEdit?): Money? =
        linesEdit
            ?.let { edit -> lineCalculator.summarize(edit, draft.total, draft.currency).difference }
            ?.takeIf { difference -> difference.minorUnits != 0L }
            ?.let { difference ->
                runCatching { Money.zero(difference.currency) - difference }.getOrNull()
            }
}

/** Resultado de intentar preparar la compra. */
sealed interface PreparePurchaseResult {
    data class Prepared(val purchase: PreparedPurchase) : PreparePurchaseResult

    /** Misma lógica ya congelada: el hash coincide y nada se reescribe. */
    data class AlreadyPrepared(val purchase: PreparedPurchase) : PreparePurchaseResult

    /** La compra no es preparable; [blockers] los enumera todos. */
    data class Blocked(val blockers: List<PrepareBlocker>) : PreparePurchaseResult

    /** El borrador no está en un estado editable de revisión. */
    data object DraftNotReady : PreparePurchaseResult
}

enum class ReopenPreparedPurchaseResult {
    REOPENED,
    NOT_PREPARED,
}

/** Vista de solo lectura para la pantalla de resumen; no persiste nada. */
data class PrepareInspection(
    val draft: InvoiceDraft,
    val prepared: PreparedPurchase?,
    val blockers: List<PrepareBlocker>,
    val roundingDifference: Money?,
    val summary: InvoiceLinesFinancialSummary?,
)

/**
 * Valida la compra revisada y congela su instantánea en READY_TO_POST. Nunca mueve inventario:
 * la publicación de stock es una etapa posterior. Preparar dos veces el mismo contenido produce
 * el mismo hash lógico y `AlreadyPrepared` sin reescritura; editar exige [reopen], que borra la
 * instantánea y devuelve el borrador a NEEDS_REVIEW.
 */
class PreparePurchaseUseCase(
    private val invoiceDraftRepository: InvoiceDraftRepository,
    private val invoiceHeaderReviewRepository: InvoiceHeaderReviewRepository,
    private val invoiceLinesReviewRepository: InvoiceLinesReviewRepository,
    private val preparedPurchaseRepository: PreparedPurchaseRepository,
    private val productRepository: ProductRepository,
    private val unitRepository: UnitRepository,
    private val appClock: AppClock,
    private val readinessValidator: PurchaseReadinessValidator = PurchaseReadinessValidator(),
    private val observability: ProductionObservability = DisabledProductionObservability,
) {
    suspend operator fun invoke(
        draftId: DraftId,
        roundingAccepted: Boolean,
        adjustmentReason: String? = null,
    ): PreparePurchaseResult {
        val adjustmentAttempted = roundingAccepted || !adjustmentReason.isNullOrBlank()
        return try {
            prepare(draftId, roundingAccepted, adjustmentReason).also { result ->
                if (adjustmentAttempted || result.hasAdjustment()) {
                    recordAdjustment(draftId, result)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (adjustmentAttempted) {
                observability.record(
                    OperationalAuditEvent(
                        action = OperationalAction.PURCHASE_ADJUSTMENT,
                        outcome = OperationalOutcome.FAILED,
                        identifiers = InternalIdentifiers(draftId = draftId),
                    ),
                    failure,
                )
            }
            throw failure
        }
    }

    private suspend fun prepare(
        draftId: DraftId,
        roundingAccepted: Boolean,
        adjustmentReason: String?,
    ): PreparePurchaseResult {
        val draft = invoiceDraftRepository.findDraft(draftId) ?: return PreparePurchaseResult.DraftNotReady
        if (draft.status == DraftStatus.READY_TO_POST) {
            val existing = preparedPurchaseRepository.find(draftId)
                ?: return PreparePurchaseResult.DraftNotReady
            return PreparePurchaseResult.AlreadyPrepared(existing)
        }
        if (
            draft.status != DraftStatus.NEEDS_REVIEW ||
            draft.activeOcrRunId != null ||
            draft.confirmedPurchaseId != null
        ) {
            return PreparePurchaseResult.DraftNotReady
        }
        val headerEdit = invoiceHeaderReviewRepository.find(draftId)
        val linesEdit = invoiceLinesReviewRepository.find(draftId)
        val blockers = readinessValidator.assess(
            draft = draft,
            headerEdit = headerEdit,
            linesEdit = linesEdit,
            roundingAccepted = roundingAccepted,
            adjustmentReason = adjustmentReason,
        )
        val catalogBlockers = validateCatalogResolutions(draft, linesEdit)
        val allBlockers = blockers + catalogBlockers
        if (allBlockers.isNotEmpty()) {
            return PreparePurchaseResult.Blocked(allBlockers)
        }
        val purchase = assemble(draft, headerEdit, linesEdit, adjustmentReason)
            ?: return PreparePurchaseResult.Blocked(
                listOf(PrepareBlocker(PrepareBlockerCode.HEADER_INVALID, detail = "PROJECTION_MISSING")),
            )
        return when (
            preparedPurchaseRepository.publish(
                purchase = purchase,
                expectedDraftUpdatedAt = draft.updatedAt,
                expectedHeaderRevision = requireNotNull(headerEdit).revision,
                expectedLinesRevision = requireNotNull(linesEdit).revision,
            )
        ) {
            PublishPreparedPurchaseResult.PREPARED -> PreparePurchaseResult.Prepared(purchase)
            PublishPreparedPurchaseResult.ALREADY_PREPARED ->
                PreparePurchaseResult.AlreadyPrepared(
                    preparedPurchaseRepository.find(draftId) ?: purchase,
                )
            PublishPreparedPurchaseResult.CONFLICT -> PreparePurchaseResult.DraftNotReady
        }
    }

    private suspend fun recordAdjustment(draftId: DraftId, result: PreparePurchaseResult) {
        observability.record(
            OperationalAuditEvent(
                action = OperationalAction.PURCHASE_ADJUSTMENT,
                outcome = when (result) {
                    is PreparePurchaseResult.Prepared -> OperationalOutcome.SUCCEEDED
                    is PreparePurchaseResult.AlreadyPrepared -> OperationalOutcome.ALREADY_APPLIED
                    is PreparePurchaseResult.Blocked -> OperationalOutcome.BLOCKED
                    PreparePurchaseResult.DraftNotReady -> OperationalOutcome.CONFLICT
                },
                identifiers = InternalIdentifiers(
                    businessId = when (result) {
                        is PreparePurchaseResult.Prepared -> result.purchase.businessId
                        is PreparePurchaseResult.AlreadyPrepared -> result.purchase.businessId
                        else -> null
                    },
                    draftId = draftId,
                ),
                errorCode = when (result) {
                    is PreparePurchaseResult.Blocked -> if (
                        result.blockers.any { blocker ->
                            blocker.code == PrepareBlockerCode.ADJUSTMENT_REASON_REQUIRED
                        }
                    ) {
                        OperationalErrorCode.INVALID_REASON
                    } else {
                        OperationalErrorCode.VALIDATION_FAILED
                    }
                    PreparePurchaseResult.DraftNotReady -> OperationalErrorCode.INTEGRITY_CONFLICT
                    else -> null
                },
            ),
        )
    }

    private fun PreparePurchaseResult.hasAdjustment(): Boolean = when (this) {
        is PreparePurchaseResult.Prepared -> purchase.reconciliationAdjustment != null
        is PreparePurchaseResult.AlreadyPrepared -> purchase.reconciliationAdjustment != null
        is PreparePurchaseResult.Blocked,
        PreparePurchaseResult.DraftNotReady,
        -> false
    }

    suspend fun inspect(
        draftId: DraftId,
        roundingAccepted: Boolean = false,
        adjustmentReason: String? = null,
    ): PrepareInspection? {
        val draft = invoiceDraftRepository.findDraft(draftId) ?: return null
        val prepared = preparedPurchaseRepository.find(draftId)
        val headerEdit = invoiceHeaderReviewRepository.find(draftId)
        val linesEdit = invoiceLinesReviewRepository.find(draftId)
        val editable = draft.status == DraftStatus.NEEDS_REVIEW &&
            draft.activeOcrRunId == null &&
            draft.confirmedPurchaseId == null
        return PrepareInspection(
            draft = draft,
            prepared = prepared,
            blockers = if (editable) {
                readinessValidator.assess(
                    draft,
                    headerEdit,
                    linesEdit,
                    roundingAccepted,
                    adjustmentReason,
                ) +
                    validateCatalogResolutions(draft, linesEdit)
            } else {
                emptyList()
            },
            roundingDifference = readinessValidator.roundingDifference(draft, headerEdit, linesEdit),
            summary = linesEdit?.let { edit ->
                InvoiceLineReviewCalculator().summarize(edit, draft.total, draft.currency)
            },
        )
    }

    /** Invalida la preparación: borra la instantánea y reabre la edición en NEEDS_REVIEW. */
    suspend fun reopen(draftId: DraftId): ReopenPreparedPurchaseResult =
        if (preparedPurchaseRepository.reopenForEdit(draftId)) {
            ReopenPreparedPurchaseResult.REOPENED
        } else {
            ReopenPreparedPurchaseResult.NOT_PREPARED
        }

    /**
     * Un ID no nulo no basta para considerar una resolución válida: producto y unidad deben
     * seguir existiendo, estar activos, pertenecer al negocio y la unidad debe coincidir con la
     * unidad de inventario o de compra configurada en el producto.
     */
    private suspend fun validateCatalogResolutions(
        draft: InvoiceDraft,
        linesEdit: InvoiceLinesEdit?,
    ): List<PrepareBlocker> = buildList {
        linesEdit?.activeLines.orEmpty().forEach { line ->
            val productId = line.linkedProductId ?: return@forEach
            val unitId = line.linkedUnitId ?: return@forEach
            val unit = unitRepository.findById(unitId)
            val staged = line.stagedProduct
            if (
                line.productProvenance == PurchaseProductProvenance.CREATED_IN_DRAFT &&
                staged != null && staged.salePrice == null
            ) {
                add(
                    PrepareBlocker(
                        code = PrepareBlockerCode.LINE_SALE_PRICE_REQUIRED,
                        lineId = line.lineId,
                    ),
                )
            }
            val valid = if (
                line.productProvenance == PurchaseProductProvenance.CREATED_IN_DRAFT
            ) {
                val stagedInventoryUnit = staged?.let { unitRepository.findById(it.unitId) }
                val stagedPurchaseUnitId = staged?.purchaseUnitId
                val stagedPurchaseUnit = stagedPurchaseUnitId?.let { purchaseUnitId ->
                    unitRepository.findById(purchaseUnitId)
                }
                staged != null &&
                    staged.productId == productId &&
                    staged.businessId == draft.businessId &&
                    productRepository.findById(productId) == null &&
                    unit != null &&
                    unit.businessId == draft.businessId &&
                    unit.status == CatalogStatus.ACTIVE &&
                    unitId in setOfNotNull(staged.unitId, staged.purchaseUnitId) &&
                    stagedInventoryUnit != null &&
                    stagedInventoryUnit.businessId == draft.businessId &&
                    stagedInventoryUnit.status == CatalogStatus.ACTIVE &&
                    (
                        staged.purchaseUnitId == null ||
                            (
                                stagedPurchaseUnit != null &&
                                    stagedPurchaseUnit.businessId == draft.businessId &&
                                    stagedPurchaseUnit.status == CatalogStatus.ACTIVE
                                )
                        )
            } else {
                val product = productRepository.findById(productId)
                staged == null &&
                    product != null &&
                    product.businessId == draft.businessId &&
                    product.status == CatalogStatus.ACTIVE &&
                    unit != null &&
                    unit.businessId == draft.businessId &&
                    unit.status == CatalogStatus.ACTIVE &&
                    unitId in setOfNotNull(product.unitId, product.purchaseUnitId)
            }
            if (!valid) {
                add(
                    PrepareBlocker(
                        code = PrepareBlockerCode.LINE_PRODUCT_INVALID,
                        lineId = line.lineId,
                    ),
                )
            }
        }
    }

    /**
     * Construye la instantánea desde los textos revisados (ya validados) con la proyección del
     * borrador como respaldo. Devuelve null solo si una proyección imprescindible falta pese a
     * una validación limpia (inconsistencia interna).
     */
    private fun assemble(
        draft: InvoiceDraft,
        headerEdit: InvoiceHeaderEdit?,
        linesEdit: InvoiceLinesEdit?,
        adjustmentReason: String?,
    ): PreparedPurchase? {
        requireNotNull(headerEdit)
        requireNotNull(linesEdit)
        val currency = draft.currency ?: headerEdit.currency?.trim()
            ?.let { text -> runCatching { CurrencyCode.of(text.uppercase(Locale.ROOT)) }.getOrNull() }
            ?: return null
        val total = draft.total ?: headerEdit.total?.trim()
            ?.let { text -> runCatching { Money.fromMajor(text, currency) }.getOrNull() }
            ?: return null
        val issueDate = draft.issueDate ?: headerEdit.issueDate?.trim()
            ?.let(::parseIssueDate)
            ?: return null
        val documentType = draft.documentType ?: headerEdit.documentType?.trim()
            ?.uppercase(Locale.ROOT)
            ?.let { value ->
                runCatching { PurchaseDocumentType.valueOf(value) }.getOrNull()
            }
            ?: return null
        val series = headerEdit.documentSeries?.trim()?.uppercase(Locale.ROOT).orEmpty()
        val number = headerEdit.documentNumber?.trim().orEmpty()
        val documentNumber = draft.documentNumberNormalized
            ?: (if (series.isNotEmpty() && number.isNotEmpty()) "$series-$number" else null)
            ?: return null
        val supplierRuc = (draft.supplierRucNormalized ?: headerEdit.supplierRuc?.trim())
            ?.takeUnless(String::isEmpty)
            ?: return null
        val lines = linesEdit.activeLines.map { line ->
            val quantity = line.quantity.selectedValue?.trim()
                ?.let { text -> runCatching { Quantity.of(text) }.getOrNull() }
                ?: return null
            val description = line.description.selectedValue?.trim().orEmpty()
            PreparedPurchaseLine(
                lineId = line.lineId,
                position = line.position,
                productId = line.linkedProductId ?: return null,
                unitId = line.linkedUnitId ?: return null,
                description = description,
                rawText = line.ocrRawText ?: description,
                quantity = quantity,
                unitCost = line.unitCost.selectedValue?.trim()
                    ?.let { text -> runCatching { UnitCost.of(text, currency) }.getOrNull() },
                discount = line.discount.selectedValue?.trim()
                    ?.let { text -> runCatching { Money.fromMajor(text, currency) }.getOrNull() },
                tax = line.igv.selectedValue?.trim()
                    ?.let { text -> runCatching { Money.fromMajor(text, currency) }.getOrNull() },
                lineTotal = line.total.selectedValue?.trim()
                    ?.let { text -> runCatching { Money.fromMajor(text, currency) }.getOrNull() },
                linkConfidence = line.linkConfidence,
                taxTreatment = line.taxTreatment,
                taxEvidence = when (line.taxTreatment) {
                    InventoryTaxTreatment.INCLUDED,
                    InventoryTaxTreatment.EXCLUDED,
                    -> line.igv.selectedValue?.trim()?.takeIf(String::isNotEmpty)
                        ?.let { text ->
                            InventoryTaxEvidence.ExplicitAmount(
                                Money.fromMajor(text, currency).toMajor(),
                            )
                        }
                        ?: InventoryTaxEvidence.None
                    InventoryTaxTreatment.EXEMPT,
                    InventoryTaxTreatment.UNKNOWN,
                    -> InventoryTaxEvidence.None
                },
                productProvenance = line.productProvenance,
                stagedProduct = line.stagedProduct,
            )
        }
        val acceptedWarnings = readinessValidator.acceptedWarnings(draft, headerEdit, linesEdit)
        val reconciliationAdjustment = readinessValidator
            .roundingDifference(draft, headerEdit, linesEdit)
            ?.let { amount ->
                PurchaseReconciliationAdjustment(
                    amount = amount,
                    reason = requireNotNull(adjustmentReason).trim(),
                )
            }
        val hash = PreparedPurchase.logicalHash(
            draftId = draft.draftId,
            businessId = draft.businessId,
            supplierId = draft.supplierId,
            supplierRuc = supplierRuc,
            supplierLegalName = draft.supplierLegalNameNormalized
                ?: headerEdit.supplierLegalName?.trim()?.takeUnless(String::isEmpty),
            documentType = documentType,
            documentNumber = documentNumber,
            issueDate = issueDate,
            currency = currency,
            lines = lines,
            subtotal = draft.subtotal,
            tax = draft.tax,
            otherCharges = draft.otherCharges,
            total = total,
            acceptedWarnings = acceptedWarnings,
            reconciliationAdjustment = reconciliationAdjustment,
        )
        return PreparedPurchase(
            draftId = draft.draftId,
            businessId = draft.businessId,
            supplierId = draft.supplierId,
            supplierRuc = supplierRuc,
            supplierLegalName = draft.supplierLegalNameNormalized
                ?: headerEdit.supplierLegalName?.trim()?.takeUnless(String::isEmpty),
            documentType = documentType,
            documentNumber = documentNumber,
            issueDate = issueDate,
            currency = currency,
            lines = lines,
            subtotal = draft.subtotal,
            tax = draft.tax,
            otherCharges = draft.otherCharges,
            total = total,
            acceptedWarnings = acceptedWarnings,
            logicalHash = hash,
            preparedAt = appClock.now(),
            reconciliationAdjustment = reconciliationAdjustment,
        )
    }

    private fun parseIssueDate(text: String): LocalDate? = runCatching {
        LocalDate.parse(
            text,
            DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT),
        )
    }.getOrNull()
}
