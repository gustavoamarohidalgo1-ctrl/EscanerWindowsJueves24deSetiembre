package com.facturastock.app.domain.usecase

import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.InvoiceLine
import com.facturastock.app.domain.model.InvoiceLineEdit
import com.facturastock.app.domain.model.InvoiceLineEditField
import com.facturastock.app.domain.model.InvoiceLineEditOrigin
import com.facturastock.app.domain.model.InvoiceLineEditValue
import com.facturastock.app.domain.model.InvoiceLinesEdit
import com.facturastock.app.domain.model.InvoiceLineValueSource
import com.facturastock.app.domain.model.InventoryTaxTreatment
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.UnitCost
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.observability.DisabledProductionObservability
import com.facturastock.app.domain.observability.InternalIdentifiers
import com.facturastock.app.domain.observability.OperationalAction
import com.facturastock.app.domain.observability.OperationalAuditEvent
import com.facturastock.app.domain.observability.OperationalErrorCode
import com.facturastock.app.domain.observability.OperationalOutcome
import com.facturastock.app.domain.observability.ProductionObservability
import com.facturastock.app.domain.normalization.ParsedInvoiceAudit
import com.facturastock.app.domain.normalization.ParsedInvoiceConfidence
import com.facturastock.app.domain.normalization.ParsedInvoiceFieldKind
import com.facturastock.app.domain.normalization.ParsedInvoiceFieldTrace
import com.facturastock.app.domain.repository.InvoiceDraftRepository
import com.facturastock.app.domain.repository.InvoiceLinesEditPublication
import com.facturastock.app.domain.repository.InvoiceLinesReviewRepository
import com.facturastock.app.domain.repository.ParsedInvoiceRepository
import com.facturastock.app.domain.repository.SaveInvoiceLinesEditResult
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

/** Fotografia durable que alimenta el editor sin volver a interpretar el OCR. */
data class InvoiceLinesReviewSnapshot(
    val draft: InvoiceDraft,
    val edit: InvoiceLinesEdit,
    val persistedEditRevision: Long,
    val audit: ParsedInvoiceAudit?,
)

enum class InvoiceLineValidationErrorCode {
    REQUIRED,
    INVALID_DECIMAL,
    INVALID_AMOUNT,
    NEGATIVE_QUANTITY,
    NEGATIVE_AMOUNT,
    TOO_LONG,
}

data class InvoiceLineValidationError(
    val field: InvoiceLineEditField,
    val code: InvoiceLineValidationErrorCode,
)

data class InvoiceLineValidation(
    val errors: List<InvoiceLineValidationError>,
) {
    val isValid: Boolean get() = errors.isEmpty()

    fun errorFor(field: InvoiceLineEditField): InvoiceLineValidationError? =
        errors.firstOrNull { error -> error.field == field }
}

/** Resultado exacto; null nunca significa cero, sino que la suma no puede resolverse. */
data class InvoiceLinesFinancialSummary(
    val lineSum: Money?,
    val invoiceTotal: Money?,
    /** Convencion visible: suma de lineas - total del documento. */
    val difference: Money?,
    val unresolvedLineIds: Set<LineId>,
    val arithmeticOverflow: Boolean,
)

/** Valida texto parcial sin normalizarlo ni cambiarle el signo. */
class InvoiceLineReviewValidator {
    fun validate(line: InvoiceLineEdit, currency: CurrencyCode?): InvoiceLineValidation {
        val errors = mutableListOf<InvoiceLineValidationError>()
        val description = line.description.selectedValue.orEmpty()
        when {
            description.isBlank() -> errors += InvoiceLineValidationError(
                InvoiceLineEditField.DESCRIPTION,
                InvoiceLineValidationErrorCode.REQUIRED,
            )
            description.length > MAX_DESCRIPTION_LENGTH -> errors += InvoiceLineValidationError(
                InvoiceLineEditField.DESCRIPTION,
                InvoiceLineValidationErrorCode.TOO_LONG,
            )
        }
        if (line.code.selectedValue.orEmpty().length > MAX_CODE_LENGTH) {
            errors += InvoiceLineValidationError(
                InvoiceLineEditField.CODE,
                InvoiceLineValidationErrorCode.TOO_LONG,
            )
        }
        if (line.unit.selectedValue.orEmpty().length > MAX_UNIT_LENGTH) {
            errors += InvoiceLineValidationError(
                InvoiceLineEditField.UNIT,
                InvoiceLineValidationErrorCode.TOO_LONG,
            )
        }

        val quantityText = line.quantity.selectedValue.orEmpty().trim()
        when {
            quantityText.isEmpty() -> errors += InvoiceLineValidationError(
                InvoiceLineEditField.QUANTITY,
                InvoiceLineValidationErrorCode.REQUIRED,
            )
            parseDecimal(quantityText) == null -> errors += InvoiceLineValidationError(
                InvoiceLineEditField.QUANTITY,
                InvoiceLineValidationErrorCode.INVALID_DECIMAL,
            )
            requireNotNull(parseDecimal(quantityText)).signum() <= 0 -> errors +=
                InvoiceLineValidationError(
                    InvoiceLineEditField.QUANTITY,
                    InvoiceLineValidationErrorCode.NEGATIVE_QUANTITY,
                )
            parseQuantity(quantityText) == null -> errors += InvoiceLineValidationError(
                InvoiceLineEditField.QUANTITY,
                InvoiceLineValidationErrorCode.INVALID_DECIMAL,
            )
        }

        validateNonNegativeAmount(
            line.unitCost.selectedValue,
            InvoiceLineEditField.UNIT_COST,
            currency,
            errors,
        )
        validateNonNegativeAmount(
            line.discount.selectedValue,
            InvoiceLineEditField.DISCOUNT,
            currency,
            errors,
        )
        validateNonNegativeAmount(
            line.igv.selectedValue,
            InvoiceLineEditField.IGV,
            currency,
            errors,
        )
        validateSignedAmount(
            line.total.selectedValue,
            InvoiceLineEditField.TOTAL,
            currency,
            errors,
        )
        return InvoiceLineValidation(errors.sortedBy { error -> error.field.stableOrder })
    }

    private fun validateNonNegativeAmount(
        value: String?,
        field: InvoiceLineEditField,
        currency: CurrencyCode?,
        errors: MutableList<InvoiceLineValidationError>,
    ) {
        val text = value.orEmpty().trim()
        if (text.isEmpty()) return
        val decimal = parseDecimal(text)
        when {
            decimal == null -> errors += InvoiceLineValidationError(
                field,
                InvoiceLineValidationErrorCode.INVALID_AMOUNT,
            )
            decimal.signum() < 0 -> errors += InvoiceLineValidationError(
                field,
                InvoiceLineValidationErrorCode.NEGATIVE_AMOUNT,
            )
            currency == null || parseMoney(text, currency) == null -> errors +=
                InvoiceLineValidationError(field, InvoiceLineValidationErrorCode.INVALID_AMOUNT)
        }
    }

    private fun validateSignedAmount(
        value: String?,
        field: InvoiceLineEditField,
        currency: CurrencyCode?,
        errors: MutableList<InvoiceLineValidationError>,
    ) {
        val text = value.orEmpty().trim()
        if (text.isEmpty()) return
        if (currency == null || parseMoney(text, currency) == null) {
            errors += InvoiceLineValidationError(field, InvoiceLineValidationErrorCode.INVALID_AMOUNT)
        }
    }

    companion object {
        const val MAX_DESCRIPTION_LENGTH: Int = 64_000
        const val MAX_CODE_LENGTH: Int = 512
        const val MAX_UNIT_LENGTH: Int = 128
    }
}

/** Recalcula comparables y resumen sin seleccionar ni inventar una tasa de impuesto. */
class InvoiceLineReviewCalculator {
    fun recalculate(line: InvoiceLineEdit, currency: CurrencyCode?): InvoiceLineEdit {
        val calculated = calculateLineTotal(line, currency)?.toMajor()?.toPlainString()
        val currentTotal = line.total
        val contributorsWereWritten = listOf(line.quantity, line.unitCost, line.discount, line.igv)
            .any { value -> value.selectedSource == InvoiceLineValueSource.WRITTEN }
        val selectedSource = when {
            currentTotal.written != null -> InvoiceLineValueSource.WRITTEN
            contributorsWereWritten && calculated != null -> InvoiceLineValueSource.CALCULATED
            currentTotal.selectedSource == InvoiceLineValueSource.OCR && currentTotal.ocr != null ->
                InvoiceLineValueSource.OCR
            currentTotal.selectedSource == InvoiceLineValueSource.CALCULATED && calculated != null ->
                InvoiceLineValueSource.CALCULATED
            currentTotal.ocr != null -> InvoiceLineValueSource.OCR
            calculated != null -> InvoiceLineValueSource.CALCULATED
            else -> null
        }
        return line.copy(
            total = currentTotal.copy(
                calculated = calculated,
                selectedSource = selectedSource,
            ),
        )
    }

    fun recalculate(edit: InvoiceLinesEdit, currency: CurrencyCode?): InvoiceLinesEdit = edit.copy(
        lines = edit.lines.map { line -> if (line.isDeleted) line else recalculate(line, currency) },
    )

    fun summarize(
        edit: InvoiceLinesEdit,
        invoiceTotal: Money?,
        currency: CurrencyCode?,
    ): InvoiceLinesFinancialSummary {
        if (currency == null) {
            return InvoiceLinesFinancialSummary(
                lineSum = null,
                invoiceTotal = invoiceTotal,
                difference = null,
                unresolvedLineIds = edit.activeLines.mapTo(linkedSetOf()) { it.lineId },
                arithmeticOverflow = false,
            )
        }
        val unresolved = linkedSetOf<LineId>()
        var sum = Money.zero(currency)
        var overflow = false
        edit.activeLines.forEach { line ->
            val amount = parseMoney(line.total.selectedValue, currency)
            if (amount == null) {
                unresolved += line.lineId
            } else if (!overflow) {
                try {
                    sum += amount
                } catch (_: RuntimeException) {
                    overflow = true
                }
            }
        }
        val resolvedSum = sum.takeIf { unresolved.isEmpty() && !overflow }
        val comparableInvoiceTotal = invoiceTotal?.takeIf { it.currency == currency }
        val difference = if (resolvedSum != null && comparableInvoiceTotal != null) {
            try {
                resolvedSum - comparableInvoiceTotal
            } catch (_: RuntimeException) {
                null
            }
        } else {
            null
        }
        return InvoiceLinesFinancialSummary(
            lineSum = resolvedSum,
            invoiceTotal = comparableInvoiceTotal,
            difference = difference,
            unresolvedLineIds = unresolved,
            arithmeticOverflow = overflow || (resolvedSum != null && comparableInvoiceTotal != null && difference == null),
        )
    }

    private fun calculateLineTotal(line: InvoiceLineEdit, currency: CurrencyCode?): Money? {
        currency ?: return null
        if (line.taxTreatment == InventoryTaxTreatment.UNKNOWN) return null
        val quantity = parseQuantity(line.quantity.selectedValue) ?: return null
        val unitCost = parseUnitCost(line.unitCost.selectedValue, currency) ?: return null
        val discount = line.discount.selectedValue.toOptionalMoney(currency) ?: return null
        val igv = line.igv.selectedValue.toOptionalMoney(currency) ?: return null
        return try {
            val discounted = unitCost.totalFor(quantity, RoundingMode.UNNECESSARY) - discount
            when (line.taxTreatment) {
                InventoryTaxTreatment.INCLUDED,
                InventoryTaxTreatment.EXEMPT,
                -> discounted
                InventoryTaxTreatment.EXCLUDED -> discounted + igv
                InventoryTaxTreatment.UNKNOWN -> null
            }
        } catch (_: RuntimeException) {
            null
        }
    }
}

/** Carga el snapshot; la primera apertura materializa el baseline OCR de forma idempotente. */
class LoadInvoiceLinesReviewUseCase(
    private val invoiceDraftRepository: InvoiceDraftRepository,
    private val invoiceLinesReviewRepository: InvoiceLinesReviewRepository,
    private val parsedInvoiceRepository: ParsedInvoiceRepository,
    private val appClock: AppClock,
) {
    /**
     * Materializa una sola vez el baseline OCR y después observa exclusivamente sus filas Room.
     * Un reinicio crea otro colector sobre el mismo snapshot durable, sin volver a interpretar
     * texto ni depender de conectividad.
     */
    fun observe(draftId: DraftId): Flow<InvoiceLinesReviewSnapshot?> = flow {
        if (invoke(draftId) == null) {
            emit(null)
            return@flow
        }
        emitAll(
            combine(
                invoiceDraftRepository.observeDraft(draftId),
                invoiceLinesReviewRepository.observe(draftId),
            ) { draft, persisted ->
                if (draft == null || persisted == null || !draft.isEditableForLineReview()) {
                    null
                } else {
                    InvoiceLinesReviewSnapshot(
                        draft = draft,
                        edit = persisted,
                        persistedEditRevision = persisted.revision,
                        audit = parsedInvoiceRepository.find(draftId)?.audit,
                    )
                }
            },
        )
    }

    suspend operator fun invoke(draftId: DraftId): InvoiceLinesReviewSnapshot? {
        val draft = invoiceDraftRepository.findDraft(draftId) ?: return null
        if (!draft.isEditableForLineReview()) return null
        val audit = parsedInvoiceRepository.find(draftId)?.audit
        invoiceLinesReviewRepository.find(draftId)?.let { persisted ->
            return InvoiceLinesReviewSnapshot(draft, persisted, persisted.revision, audit)
        }
        val projectedLines = invoiceDraftRepository.observeLines(draftId).first()
        val now = canonicalInstant(maxOf(appClock.now(), draft.updatedAt))
        val initial = InvoiceLineReviewCalculator().recalculate(InvoiceLinesEdit(
            draftId = draftId,
            lines = projectedLines.map { line -> line.toInitialEdit(audit, draft.currency, now) },
            revision = 0L,
            updatedAt = now,
        ), draft.currency)
        val initialValidator = InvoiceLineReviewValidator()
        val initialById = projectedLines.associateBy(InvoiceLine::lineId)
        val initialProjection = initial.activeLines.mapNotNull { line ->
            line.toProjectionOrNull(
                draft = draft,
                current = initialById[line.lineId],
                validator = initialValidator,
            )
        }.mapIndexed { index, line -> line.copy(position = index) }
        return when (
            invoiceLinesReviewRepository.saveIfNewer(
                InvoiceLinesEditPublication(initial, initialProjection, expectedRevision = null),
            )
        ) {
            SaveInvoiceLinesEditResult.SAVED,
            SaveInvoiceLinesEditResult.ALREADY_SAVED,
            -> InvoiceLinesReviewSnapshot(draft, initial, initial.revision, audit)
            SaveInvoiceLinesEditResult.STALE_REVISION,
            SaveInvoiceLinesEditResult.CONFLICT,
            -> invoiceLinesReviewRepository.find(draftId)?.let { raced ->
                InvoiceLinesReviewSnapshot(draft, raced, raced.revision, audit)
            }
            SaveInvoiceLinesEditResult.DRAFT_NOT_EDITABLE -> null
        }
    }
}

/** Persiste snapshot y proyeccion tipada en el mismo commit agregado. */
class SaveInvoiceLinesEditUseCase(
    private val invoiceDraftRepository: InvoiceDraftRepository,
    private val invoiceLinesReviewRepository: InvoiceLinesReviewRepository,
    private val calculator: InvoiceLineReviewCalculator = InvoiceLineReviewCalculator(),
    private val validator: InvoiceLineReviewValidator = InvoiceLineReviewValidator(),
    private val observability: ProductionObservability = DisabledProductionObservability,
) {
    suspend operator fun invoke(
        edit: InvoiceLinesEdit,
        expectedRevision: Long?,
        catalogLinkLineIds: Set<LineId> = emptySet(),
    ): SaveInvoiceLinesEditResult {
        return try {
            saveAndObserve(edit, expectedRevision, catalogLinkLineIds)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            observability.record(
                OperationalAuditEvent(
                    action = OperationalAction.INVOICE_LINES_EDIT,
                    outcome = OperationalOutcome.FAILED,
                    identifiers = InternalIdentifiers(draftId = edit.draftId),
                ),
                failure,
            )
            throw failure
        }
    }

    private suspend fun saveAndObserve(
        edit: InvoiceLinesEdit,
        expectedRevision: Long?,
        catalogLinkLineIds: Set<LineId>,
    ): SaveInvoiceLinesEditResult {
        val draft = invoiceDraftRepository.findDraft(edit.draftId)
        if (draft == null || !draft.isEditableForLineReview()) {
            return SaveInvoiceLinesEditResult.DRAFT_NOT_EDITABLE.also { result ->
                recordLinesEdit(edit.draftId, result)
            }
        }
        val recalculated = calculator.recalculate(edit, draft.currency)
        val currentProjection = invoiceDraftRepository.observeLines(edit.draftId).first()
            .associateBy(InvoiceLine::lineId)
        val persistedEdit = invoiceLinesReviewRepository.find(edit.draftId)
            ?.lines
            .orEmpty()
            .associateBy(InvoiceLineEdit::lineId)
        // La vinculación puede ocurrir en otro flujo después de crear el snapshot. Al eliminar,
        // captura el estado tipado todavía existente antes de que Room convierta la fila en
        // tombstone físico; guardados posteriores lo recuperan del snapshot durable.
        val hydrated = recalculated.copy(
            lines = recalculated.lines.map { line ->
                val projected = currentProjection[line.lineId]
                val persisted = persistedEdit[line.lineId]
                when {
                    line.isDeleted && persisted?.stagedProduct != null ->
                        line.withLinksFrom(persisted)
                    line.isDeleted && projected != null -> line.withLinksFrom(projected)
                    line.isDeleted && persisted != null -> line.withLinksFrom(persisted)
                    // Restore: la fila física ya no existe, pero el tombstone durable sí
                    // conserva los enlaces capturados en el commit de eliminación.
                    !line.isDeleted && projected == null && persisted?.isDeleted == true ->
                        line.withLinksFrom(persisted)
                    else -> line
                }
            },
        )
        val editById = hydrated.activeLines.associateBy(InvoiceLineEdit::lineId)
        val projected = hydrated.activeLines.mapNotNull { line ->
            line.toProjectionOrNull(
                draft = draft,
                current = currentProjection[line.lineId],
                validator = validator,
            )
        }.mapIndexed { index, line ->
            val linked = editById.getValue(line.lineId)
            if (line.lineId in catalogLinkLineIds) {
                line.copy(
                    position = index,
                    // Un producto staged aun no puede cruzar la FK de invoice_lines.
                    productId = linked.linkedProductId.takeIf { linked.stagedProduct == null },
                    unitId = linked.linkedUnitId,
                    linkConfidence = linked.linkConfidence,
                )
            } else {
                line.copy(position = index)
            }
        }
        return invoiceLinesReviewRepository.saveIfNewer(
            InvoiceLinesEditPublication(
                edit = hydrated,
                projectedLines = projected,
                expectedRevision = expectedRevision,
                catalogLinkLineIds = catalogLinkLineIds,
            ),
        ).also { result -> recordLinesEdit(edit.draftId, result) }
    }

    private suspend fun recordLinesEdit(
        draftId: DraftId,
        result: SaveInvoiceLinesEditResult,
    ) {
        observability.record(
            OperationalAuditEvent(
                action = OperationalAction.INVOICE_LINES_EDIT,
                outcome = when (result) {
                    SaveInvoiceLinesEditResult.SAVED -> OperationalOutcome.SUCCEEDED
                    SaveInvoiceLinesEditResult.ALREADY_SAVED -> OperationalOutcome.ALREADY_APPLIED
                    SaveInvoiceLinesEditResult.STALE_REVISION,
                    SaveInvoiceLinesEditResult.CONFLICT,
                    -> OperationalOutcome.CONFLICT
                    SaveInvoiceLinesEditResult.DRAFT_NOT_EDITABLE -> OperationalOutcome.BLOCKED
                },
                identifiers = InternalIdentifiers(draftId = draftId),
                errorCode = when (result) {
                    SaveInvoiceLinesEditResult.STALE_REVISION ->
                        OperationalErrorCode.STALE_REVISION
                    SaveInvoiceLinesEditResult.CONFLICT ->
                        OperationalErrorCode.INTEGRITY_CONFLICT
                    SaveInvoiceLinesEditResult.DRAFT_NOT_EDITABLE ->
                        OperationalErrorCode.NOT_EDITABLE
                    else -> null
                },
            ),
        )
    }
}

private fun InvoiceLineEdit.withLinksFrom(line: InvoiceLine): InvoiceLineEdit = copy(
    linkedProductId = line.productId,
    linkedUnitId = line.unitId,
    linkConfidence = line.linkConfidence,
    productProvenance = if (line.productId == linkedProductId) {
        productProvenance
    } else {
        com.facturastock.app.domain.model.PurchaseProductProvenance.UNKNOWN_LEGACY
    },
    stagedProduct = null,
)

private fun InvoiceLineEdit.withLinksFrom(line: InvoiceLineEdit): InvoiceLineEdit = copy(
    linkedProductId = line.linkedProductId,
    linkedUnitId = line.linkedUnitId,
    linkConfidence = line.linkConfidence,
    productProvenance = line.productProvenance,
    stagedProduct = line.stagedProduct,
)

private fun InvoiceDraft.isEditableForLineReview(): Boolean =
    status == DraftStatus.NEEDS_REVIEW && activeOcrRunId == null && confirmedPurchaseId == null

private fun InvoiceLine.toInitialEdit(
    audit: ParsedInvoiceAudit?,
    currency: CurrencyCode?,
    snapshotTime: Instant,
): InvoiceLineEdit {
    val traces = audit?.fields.orEmpty().filter { trace -> trace.position == position }
    fun trace(kind: ParsedInvoiceFieldKind): ParsedInvoiceFieldTrace? =
        traces.firstOrNull { item -> item.kind == kind }
    fun ocr(kind: ParsedInvoiceFieldKind): String? = trace(kind)?.editableValue(currency)
    fun value(typed: String?, kind: ParsedInvoiceFieldKind): InvoiceLineEditValue {
        val read = typed ?: ocr(kind)
        val selected = trace(kind)?.selectedCandidate()
        val raw = selected?.rawText
            ?: selected?.evidence.orEmpty().map { evidence -> evidence.rawText }
                .filter(String::isNotBlank).distinct().joinToString(" ").ifBlank { null }
        return InvoiceLineEditValue(
            ocr = read,
            ocrRaw = raw ?: read,
            selectedSource = read?.let { InvoiceLineValueSource.OCR },
        )
    }
    val fieldKinds = mapOf(
        InvoiceLineEditField.DESCRIPTION to ParsedInvoiceFieldKind.LINE_DESCRIPTION,
        InvoiceLineEditField.CODE to ParsedInvoiceFieldKind.LINE_CODE,
        InvoiceLineEditField.QUANTITY to ParsedInvoiceFieldKind.LINE_QUANTITY,
        InvoiceLineEditField.UNIT to ParsedInvoiceFieldKind.LINE_UNIT,
        InvoiceLineEditField.UNIT_COST to ParsedInvoiceFieldKind.LINE_UNIT_COST,
        InvoiceLineEditField.DISCOUNT to ParsedInvoiceFieldKind.LINE_DISCOUNT,
        InvoiceLineEditField.IGV to ParsedInvoiceFieldKind.LINE_TAX,
        InvoiceLineEditField.TOTAL to ParsedInvoiceFieldKind.LINE_TOTAL,
    )
    val confidenceByField = fieldKinds.mapNotNull { (field, kind) ->
        trace(kind)?.selectedCandidate()?.confidencePermille?.let { field to it }
    }.toMap()
    val reviewFields = fieldKinds.mapNotNullTo(linkedSetOf()) { (field, kind) ->
        trace(kind)?.takeIf { item ->
            item.selectedCandidate() != null &&
                (
                    item.requiresReview || item.confidence in setOf(
                        ParsedInvoiceConfidence.LOW,
                        ParsedInvoiceConfidence.UNKNOWN,
                    )
                    )
        }?.let { field }
    }
    val rawRow = traces.flatMap { item ->
        item.selectedCandidate()?.evidence.orEmpty().map { evidence -> evidence.rawText }
    }.filter(String::isNotBlank).distinct().joinToString(" ").ifBlank { descriptionRaw }
    val created = canonicalInstant(createdAt)
    val updated = canonicalInstant(maxOf(updatedAt, created, snapshotTime))
    return InvoiceLineEdit(
        lineId = lineId,
        position = position,
        origin = InvoiceLineEditOrigin.OCR,
        sourcePosition = position,
        ocrRawText = rawRow,
        linkedProductId = productId,
        linkedUnitId = unitId,
        linkConfidence = linkConfidence,
        description = value(
            descriptionNormalized ?: descriptionRaw.takeIf { audit == null },
            ParsedInvoiceFieldKind.LINE_DESCRIPTION,
        ),
        code = value(codeNormalized ?: codeRaw, ParsedInvoiceFieldKind.LINE_CODE),
        quantity = value(quantity?.value?.toPlainString(), ParsedInvoiceFieldKind.LINE_QUANTITY),
        unit = value(unitCodeNormalized ?: unitRaw, ParsedInvoiceFieldKind.LINE_UNIT),
        unitCost = value(unitCost?.amount?.toPlainString(), ParsedInvoiceFieldKind.LINE_UNIT_COST),
        discount = value(discount?.toMajor()?.toPlainString(), ParsedInvoiceFieldKind.LINE_DISCOUNT),
        igv = value(tax?.toMajor()?.toPlainString(), ParsedInvoiceFieldKind.LINE_TAX),
        total = value(lineTotal?.toMajor()?.toPlainString(), ParsedInvoiceFieldKind.LINE_TOTAL),
        confidencePermille = ocrConfidence,
        fieldConfidencePermille = confidenceByField,
        requiresReview = audit?.blockers.orEmpty().any { blocker -> blocker.linePosition == position } ||
            audit?.warnings.orEmpty().any { warning -> warning.position == position && warning.requiresReview },
        reviewConfirmedByUser = false,
        reviewRequiredFields = reviewFields,
        touchedFields = emptySet(),
        createdAt = created,
        updatedAt = updated,
    )
}

private fun InvoiceLineEdit.toProjectionOrNull(
    draft: InvoiceDraft,
    current: InvoiceLine?,
    validator: InvoiceLineReviewValidator,
): InvoiceLine? {
    val currency = draft.currency
    val validation = validator.validate(this, currency)
    val descriptionText = description.selectedValue.orEmpty()
    val quantityText = quantity.selectedValue.orEmpty()
    val descriptionValue = descriptionText.trim().takeIf(String::isNotEmpty)
        ?: current?.descriptionRaw
        ?: return null
    val quantityValue = parseQuantity(quantityText) ?: current?.quantity ?: return null
    val unitCostValue = resolveOptionalUnitCost(unitCost.selectedValue, currency, current?.unitCost)
    val discountValue = resolveOptionalNonNegativeMoney(
        discount.selectedValue,
        currency,
        current?.discount,
    )
    val taxValue = resolveOptionalNonNegativeMoney(igv.selectedValue, currency, current?.tax)
    val selectedTotal = resolveOptionalMoney(total.selectedValue, currency, current?.lineTotal)
    val hasFatalNewLineError = current == null && validation.errors.any { error ->
        error.field == InvoiceLineEditField.DESCRIPTION || error.field == InvoiceLineEditField.QUANTITY
    }
    if (hasFatalNewLineError) return null
    return InvoiceLine(
        lineId = lineId,
        draftId = draft.draftId,
        businessId = draft.businessId,
        position = position,
        descriptionRaw = descriptionValue,
        descriptionNormalized = descriptionText.trim().takeIf(String::isNotEmpty)
            ?: current?.descriptionNormalized,
        codeRaw = code.selectedValue?.takeIf(String::isNotBlank),
        codeNormalized = code.selectedValue?.trim()?.uppercase(Locale.ROOT)
            ?.takeIf(String::isNotEmpty),
        quantity = quantityValue,
        unitRaw = unit.selectedValue?.takeIf(String::isNotBlank),
        unitCodeNormalized = unit.selectedValue?.trim()?.uppercase(Locale.ROOT)
            ?.takeIf(String::isNotEmpty),
        unitCost = unitCostValue,
        discount = discountValue,
        tax = taxValue,
        unitId = current?.unitId ?: linkedUnitId,
        productId = current?.productId ?: linkedProductId,
        lineTotal = selectedTotal,
        ocrConfidence = confidencePermille,
        linkConfidence = current?.linkConfidence ?: linkConfidence,
        createdAt = current?.createdAt ?: createdAt,
        updatedAt = updatedAt,
    )
}

private fun ParsedInvoiceFieldTrace.selectedCandidate() =
    selectedCandidateIndex?.let(candidates::getOrNull)

private fun ParsedInvoiceFieldTrace.editableValue(currency: CurrencyCode?): String? {
    val candidate = selectedCandidate() ?: return null
    val canonical = candidate.canonicalValue ?: return candidate.rawText
    return when {
        kind in MONEY_LINE_FIELDS && canonical.startsWith("RATE:") -> candidate.rawText ?: canonical
        kind in MONEY_LINE_FIELDS && ':' in canonical -> {
            val code = canonical.substringBefore(':')
            val minor = canonical.substringAfter(':').toLongOrNull()
            val parsedCurrency = runCatching { CurrencyCode.of(code) }.getOrNull()
            if (minor != null && parsedCurrency != null && (currency == null || parsedCurrency == currency)) {
                Money.ofMinor(minor, parsedCurrency).toMajor().toPlainString()
            } else {
                candidate.rawText ?: canonical
            }
        }
        kind == ParsedInvoiceFieldKind.LINE_UNIT_COST && ':' in canonical -> canonical.substringAfter(':')
        else -> canonical
    }
}

private fun String?.toOptionalMoney(currency: CurrencyCode): Money? = when {
    isNullOrBlank() -> Money.zero(currency)
    else -> parseMoney(this, currency)
}

private fun resolveOptionalUnitCost(
    text: String?,
    currency: CurrencyCode?,
    current: UnitCost?,
): UnitCost? = when {
    text.isNullOrBlank() || currency == null -> null
    else -> parseUnitCost(text, currency) ?: current?.takeIf { it.currency == currency }
}

private fun resolveOptionalMoney(
    text: String?,
    currency: CurrencyCode?,
    current: Money?,
): Money? = when {
    text.isNullOrBlank() || currency == null -> null
    else -> parseMoney(text, currency) ?: current?.takeIf { it.currency == currency }
}

private fun resolveOptionalNonNegativeMoney(
    text: String?,
    currency: CurrencyCode?,
    current: Money?,
): Money? = resolveOptionalMoney(text, currency, current)
    ?.takeIf { amount -> amount.minorUnits >= 0L }

private fun parseDecimal(value: String?): BigDecimal? {
    val normalized = value?.trim()?.replace(',', '.')?.takeIf(String::isNotEmpty) ?: return null
    return runCatching { BigDecimal(normalized) }.getOrNull()
}

private fun parseQuantity(value: String?): Quantity? =
    parseDecimal(value)?.let { decimal -> runCatching { Quantity.of(decimal) }.getOrNull() }

private fun parseUnitCost(value: String?, currency: CurrencyCode): UnitCost? =
    parseDecimal(value)?.let { decimal -> runCatching { UnitCost.of(decimal, currency) }.getOrNull() }

private fun parseMoney(value: String?, currency: CurrencyCode): Money? {
    val normalized = value?.trim()?.replace(',', '.')?.takeIf(String::isNotEmpty) ?: return null
    return runCatching { Money.fromMajor(normalized, currency) }.getOrNull()
}

private fun canonicalInstant(value: Instant): Instant = Instant.ofEpochMilli(value.toEpochMilli())

private val MONEY_LINE_FIELDS = setOf(
    ParsedInvoiceFieldKind.LINE_DISCOUNT,
    ParsedInvoiceFieldKind.LINE_TAX,
    ParsedInvoiceFieldKind.LINE_TOTAL,
)
