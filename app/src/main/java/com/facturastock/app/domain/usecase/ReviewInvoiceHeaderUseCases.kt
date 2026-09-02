package com.facturastock.app.domain.usecase

import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InvoiceDocumentNumber
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.InvoiceHeaderEdit
import com.facturastock.app.domain.model.InvoiceHeaderEditField
import com.facturastock.app.domain.model.InvoiceImage
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.PurchaseDocumentType
import com.facturastock.app.domain.model.RucValidator
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.observability.DisabledProductionObservability
import com.facturastock.app.domain.observability.InternalIdentifiers
import com.facturastock.app.domain.observability.OperationalAction
import com.facturastock.app.domain.observability.OperationalAuditEvent
import com.facturastock.app.domain.observability.OperationalErrorCode
import com.facturastock.app.domain.observability.OperationalOutcome
import com.facturastock.app.domain.observability.ProductionObservability
import com.facturastock.app.domain.normalization.ParsedInvoiceAudit
import com.facturastock.app.domain.repository.InvoiceDraftRepository
import com.facturastock.app.domain.repository.InvoiceHeaderEditPublication
import com.facturastock.app.domain.repository.InvoiceHeaderReviewRepository
import com.facturastock.app.domain.repository.ParsedInvoiceRepository
import com.facturastock.app.domain.repository.SaveInvoiceHeaderEditResult
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Locale
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/** Datos durables que necesita la revisión, incluidos el audit trail OCR y las páginas. */
data class InvoiceHeaderReviewSnapshot(
    val draft: InvoiceDraft,
    val edit: InvoiceHeaderEdit,
    /** null cuando el formulario todavía es una proyección inicial y no tiene fila durable. */
    val persistedEditRevision: Long?,
    val pages: List<InvoiceImage>,
    val audit: ParsedInvoiceAudit?,
)

enum class InvoiceHeaderValidationErrorCode {
    REQUIRED,
    INVALID_RUC,
    INVALID_DOCUMENT_TYPE,
    INVALID_SERIES,
    INVALID_NUMBER,
    INVALID_DATE,
    INVALID_CURRENCY,
    INVALID_AMOUNT,
}

data class InvoiceHeaderValidationError(
    val field: InvoiceHeaderEditField,
    val code: InvoiceHeaderValidationErrorCode,
)

enum class InvoiceHeaderValidationWarningCode {
    RUC_CHECKSUM_MISMATCH,
    TOTAL_DIFFERENCE,
}

data class InvoiceHeaderValidationWarning(
    val code: InvoiceHeaderValidationWarningCode,
    val difference: Money? = null,
)

data class InvoiceHeaderValidation(
    val errors: List<InvoiceHeaderValidationError>,
    val warnings: List<InvoiceHeaderValidationWarning>,
) {
    val isValid: Boolean get() = errors.isEmpty()

    fun errorFor(field: InvoiceHeaderEditField): InvoiceHeaderValidationError? =
        errors.firstOrNull { error -> error.field == field }
}

/**
 * Validación pura de la cabecera editable. RUC, tipo, número, fecha, moneda y total son
 * imprescindibles. Los demás campos pueden quedar vacíos, pero un importe escrito debe poder
 * persistirse exactamente. Checksum RUC y descuadre financiero son solo advertencias.
 */
class InvoiceHeaderReviewValidator {
    fun validate(edit: InvoiceHeaderEdit): InvoiceHeaderValidation {
        val errors = mutableListOf<InvoiceHeaderValidationError>()
        errors.required(
            InvoiceHeaderEditField.SUPPLIER_RUC,
            edit.supplierRuc,
            InvoiceHeaderValidationErrorCode.INVALID_RUC,
            RucValidator::isWellFormed,
        )
        errors.required(
            InvoiceHeaderEditField.DOCUMENT_TYPE,
            edit.documentType,
            InvoiceHeaderValidationErrorCode.INVALID_DOCUMENT_TYPE,
        ) { parseDocumentType(it) != null }
        errors.required(
            InvoiceHeaderEditField.DOCUMENT_SERIES,
            edit.documentSeries,
            InvoiceHeaderValidationErrorCode.INVALID_SERIES,
        ) { SERIES_PATTERN.matches(it.trim().uppercase(Locale.ROOT)) }
        errors.required(
            InvoiceHeaderEditField.DOCUMENT_NUMBER,
            edit.documentNumber,
            InvoiceHeaderValidationErrorCode.INVALID_NUMBER,
        ) { NUMBER_PATTERN.matches(it.trim()) }
        errors.required(
            InvoiceHeaderEditField.ISSUE_DATE,
            edit.issueDate,
            InvoiceHeaderValidationErrorCode.INVALID_DATE,
        ) { parseDate(it) != null }
        errors.required(
            InvoiceHeaderEditField.CURRENCY,
            edit.currency,
            InvoiceHeaderValidationErrorCode.INVALID_CURRENCY,
        ) { parseCurrency(it) != null }

        val currency = parseCurrency(edit.currency)
        errors.optionalAmount(InvoiceHeaderEditField.SUBTOTAL, edit.subtotal, currency)
        errors.optionalAmount(InvoiceHeaderEditField.IGV, edit.igv, currency)
        errors.optionalAmount(InvoiceHeaderEditField.OTHER_CHARGES, edit.otherCharges, currency)
        errors.requiredAmount(InvoiceHeaderEditField.TOTAL, edit.total, currency)

        val warnings = buildList {
            val ruc = edit.supplierRuc?.trim().orEmpty()
            if (RucValidator.isWellFormed(ruc) && !RucValidator.hasValidChecksum(ruc)) {
                add(
                    InvoiceHeaderValidationWarning(
                        InvoiceHeaderValidationWarningCode.RUC_CHECKSUM_MISMATCH,
                    ),
                )
            }
            financialDifference(edit)?.takeIf { it.minorUnits != 0L }?.let { difference ->
                add(
                    InvoiceHeaderValidationWarning(
                        code = InvoiceHeaderValidationWarningCode.TOTAL_DIFFERENCE,
                        difference = difference,
                    ),
                )
            }
        }
        return InvoiceHeaderValidation(
            errors = errors.sortedBy { error -> error.field.stableOrder },
            warnings = warnings,
        )
    }

    /**
     * Materializa el formulario completo. Un texto parcial/inválido queda en el edit durable y
     * conserva la última proyección tipada; vaciarlo explícitamente sí limpia esa proyección.
     */
    fun project(edit: InvoiceHeaderEdit, draft: InvoiceDraft): InvoiceDraft {
        require(edit.draftId == draft.draftId) { "El formulario pertenece a otro borrador" }
        val legalName = edit.supplierLegalName?.trim().orEmpty()
        val series = edit.documentSeries?.trim()?.uppercase(Locale.ROOT).orEmpty()
        val number = edit.documentNumber?.trim().orEmpty()
        val parsedNumber = runCatching { InvoiceDocumentNumber(series, number) }.getOrNull()
        val currency = resolveOrPreserve(
            text = edit.currency,
            current = draft.currency,
            parse = ::parseCurrency,
        )
        val normalizedRuc = resolveOrPreserve(
            text = edit.supplierRuc,
            current = draft.supplierRucNormalized,
        ) { value -> value?.trim()?.takeIf(RucValidator::isWellFormed) }
        val normalizedNumber = when {
            edit.documentSeries.isNullOrBlank() && edit.documentNumber.isNullOrBlank() -> null
            parsedNumber != null -> parsedNumber.normalized
            else -> draft.documentNumberNormalized
        }
        val resolvedDate = resolveOrPreserve(
            text = edit.issueDate,
            current = draft.issueDate,
            parse = ::parseDate,
        )
        val resolvedDocumentType = resolveOrPreserve(
            text = edit.documentType,
            current = draft.documentType,
            parse = ::parseDocumentType,
        )
        val supplierIdentityStable = if (draft.supplierRucNormalized != null) {
            when {
                edit.supplierRuc.isNullOrBlank() -> false
                RucValidator.isWellFormed(edit.supplierRuc) ->
                    normalizedRuc == draft.supplierRucNormalized
                else -> true // Entrada parcial: aún no afirma otra identidad.
            }
        } else {
            legalName == draft.supplierLegalNameNormalized.orEmpty()
        }
        return draft.copy(
            supplierId = draft.supplierId.takeIf { supplierIdentityStable },
            supplierRucRaw = edit.supplierRuc?.takeIf(String::isNotBlank),
            supplierRucNormalized = normalizedRuc,
            supplierLegalNameRaw = edit.supplierLegalName?.takeIf(String::isNotBlank),
            supplierLegalNameNormalized = legalName.ifEmpty { null },
            documentType = resolvedDocumentType,
            documentNumberRaw = listOf(series, number)
                .filter(String::isNotEmpty)
                .joinToString("-")
                .ifEmpty { null },
            documentNumberNormalized = normalizedNumber,
            issueDateRaw = edit.issueDate?.takeIf(String::isNotBlank),
            issueDate = resolvedDate,
            currency = currency,
            subtotal = resolveMoneyOrPreserve(edit.subtotal, currency, draft.subtotal),
            tax = resolveMoneyOrPreserve(edit.igv, currency, draft.tax),
            otherCharges = resolveMoneyOrPreserve(edit.otherCharges, currency, draft.otherCharges),
            total = resolveMoneyOrPreserve(edit.total, currency, draft.total),
            updatedAt = edit.updatedAt,
        )
    }

    private fun financialDifference(edit: InvoiceHeaderEdit): Money? {
        val currency = parseCurrency(edit.currency) ?: return null
        val subtotal = parseMoney(edit.subtotal, currency) ?: return null
        val igv = parseMoney(edit.igv, currency) ?: return null
        val charges = if (edit.otherCharges.isNullOrBlank()) {
            Money.zero(currency)
        } else {
            parseMoney(edit.otherCharges, currency) ?: return null
        }
        val total = parseMoney(edit.total, currency) ?: return null
        return runCatching { total - (subtotal + igv + charges) }.getOrNull()
    }

    private fun MutableList<InvoiceHeaderValidationError>.required(
        field: InvoiceHeaderEditField,
        value: String?,
        invalidCode: InvoiceHeaderValidationErrorCode,
        isValid: (String) -> Boolean,
    ) {
        val text = value?.trim().orEmpty()
        when {
            text.isEmpty() -> add(
                InvoiceHeaderValidationError(field, InvoiceHeaderValidationErrorCode.REQUIRED),
            )
            !isValid(text) -> add(InvoiceHeaderValidationError(field, invalidCode))
        }
    }

    private fun MutableList<InvoiceHeaderValidationError>.optional(
        field: InvoiceHeaderEditField,
        value: String?,
        invalidCode: InvoiceHeaderValidationErrorCode,
        isValid: (String) -> Boolean,
    ) {
        val text = value?.trim().orEmpty()
        if (text.isNotEmpty() && !isValid(text)) {
            add(InvoiceHeaderValidationError(field, invalidCode))
        }
    }

    private fun MutableList<InvoiceHeaderValidationError>.requiredAmount(
        field: InvoiceHeaderEditField,
        value: String?,
        currency: CurrencyCode?,
    ) {
        val text = value?.trim().orEmpty()
        when {
            text.isEmpty() -> add(
                InvoiceHeaderValidationError(field, InvoiceHeaderValidationErrorCode.REQUIRED),
            )
            currency != null && parseMoney(text, currency) == null -> add(
                InvoiceHeaderValidationError(field, InvoiceHeaderValidationErrorCode.INVALID_AMOUNT),
            )
        }
    }

    private fun MutableList<InvoiceHeaderValidationError>.optionalAmount(
        field: InvoiceHeaderEditField,
        value: String?,
        currency: CurrencyCode?,
    ) {
        val text = value?.trim().orEmpty()
        if (text.isNotEmpty() && currency != null && parseMoney(text, currency) == null) {
            add(InvoiceHeaderValidationError(field, InvoiceHeaderValidationErrorCode.INVALID_AMOUNT))
        }
    }

    companion object {
        private val SERIES_PATTERN = Regex("^[A-Z0-9]{1,4}$")
        private val NUMBER_PATTERN = Regex("^\\d{1,12}$")
    }
}

/** Recupera primero el edit durable; si aún no existe, deriva una vista inicial del draft. */
class LoadInvoiceHeaderReviewUseCase(
    private val invoiceDraftRepository: InvoiceDraftRepository,
    private val invoiceHeaderReviewRepository: InvoiceHeaderReviewRepository,
    private val parsedInvoiceRepository: ParsedInvoiceRepository,
) {
    /**
     * Mantiene la pantalla ligada a Room durante toda su vida. Cabecera, páginas y formulario
     * se recombinan ante cada invalidación; el audit es inmutable para este estadio y se vuelve
     * a leer dentro de la misma frontera local, nunca desde red.
     */
    fun observe(draftId: DraftId): Flow<InvoiceHeaderReviewSnapshot?> = combine(
        invoiceDraftRepository.observeDraft(draftId),
        invoiceHeaderReviewRepository.observe(draftId),
        invoiceDraftRepository.observeImages(draftId),
    ) { draft, persistedEdit, pages ->
        if (
            draft == null ||
            draft.status != DraftStatus.NEEDS_REVIEW ||
            draft.activeOcrRunId != null ||
            draft.confirmedPurchaseId != null
        ) {
            null
        } else {
            InvoiceHeaderReviewSnapshot(
                draft = draft,
                edit = persistedEdit ?: draft.initialHeaderEdit(),
                persistedEditRevision = persistedEdit?.revision,
                pages = pages,
                audit = parsedInvoiceRepository.find(draftId)?.audit,
            )
        }
    }

    suspend operator fun invoke(draftId: DraftId): InvoiceHeaderReviewSnapshot? {
        val draft = invoiceDraftRepository.findDraft(draftId) ?: return null
        if (
            draft.status != DraftStatus.NEEDS_REVIEW ||
            draft.activeOcrRunId != null ||
            draft.confirmedPurchaseId != null
        ) {
            return null
        }
        val persistedEdit = invoiceHeaderReviewRepository.find(draftId)
        return InvoiceHeaderReviewSnapshot(
            draft = draft,
            edit = persistedEdit ?: draft.initialHeaderEdit(),
            persistedEditRevision = persistedEdit?.revision,
            pages = invoiceDraftRepository.observeImages(draftId).first(),
            audit = parsedInvoiceRepository.find(draftId)?.audit,
        )
    }
}

/** Guarda texto parcial y proyección tipada en el mismo commit Room. */
class SaveInvoiceHeaderEditUseCase(
    private val invoiceDraftRepository: InvoiceDraftRepository,
    private val invoiceHeaderReviewRepository: InvoiceHeaderReviewRepository,
    private val validator: InvoiceHeaderReviewValidator = InvoiceHeaderReviewValidator(),
    private val observability: ProductionObservability = DisabledProductionObservability,
) {
    suspend operator fun invoke(
        edit: InvoiceHeaderEdit,
        expectedRevision: Long? = null,
    ): SaveInvoiceHeaderEditResult {
        return try {
            val draft = invoiceDraftRepository.findDraft(edit.draftId)
            val result = if (
                draft == null ||
                draft.status != DraftStatus.NEEDS_REVIEW ||
                draft.activeOcrRunId != null ||
                draft.confirmedPurchaseId != null
            ) {
                SaveInvoiceHeaderEditResult.DRAFT_NOT_EDITABLE
            } else {
                invoiceHeaderReviewRepository.saveIfNewer(
                    InvoiceHeaderEditPublication(
                        edit = edit,
                        projectedDraft = validator.project(edit, draft),
                        expectedRevision = expectedRevision,
                    ),
                )
            }
            recordHeaderEdit(edit.draftId, result)
            result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            observability.record(
                OperationalAuditEvent(
                    action = OperationalAction.INVOICE_HEADER_EDIT,
                    outcome = OperationalOutcome.FAILED,
                    identifiers = InternalIdentifiers(draftId = edit.draftId),
                ),
                failure,
            )
            throw failure
        }
    }

    private suspend fun recordHeaderEdit(
        draftId: DraftId,
        result: SaveInvoiceHeaderEditResult,
    ) {
        observability.record(
            OperationalAuditEvent(
                action = OperationalAction.INVOICE_HEADER_EDIT,
                outcome = when (result) {
                    SaveInvoiceHeaderEditResult.SAVED -> OperationalOutcome.SUCCEEDED
                    SaveInvoiceHeaderEditResult.ALREADY_SAVED -> OperationalOutcome.ALREADY_APPLIED
                    SaveInvoiceHeaderEditResult.STALE_REVISION,
                    SaveInvoiceHeaderEditResult.CONFLICT,
                    -> OperationalOutcome.CONFLICT
                    SaveInvoiceHeaderEditResult.DRAFT_NOT_EDITABLE -> OperationalOutcome.BLOCKED
                },
                identifiers = InternalIdentifiers(draftId = draftId),
                errorCode = when (result) {
                    SaveInvoiceHeaderEditResult.STALE_REVISION ->
                        OperationalErrorCode.STALE_REVISION
                    SaveInvoiceHeaderEditResult.CONFLICT ->
                        OperationalErrorCode.INTEGRITY_CONFLICT
                    SaveInvoiceHeaderEditResult.DRAFT_NOT_EDITABLE ->
                        OperationalErrorCode.NOT_EDITABLE
                    else -> null
                },
            ),
        )
    }
}

private val HEADER_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter
    .ofPattern("dd/MM/uuuu")
    .withResolverStyle(ResolverStyle.STRICT)

private fun InvoiceDraft.initialHeaderEdit(): InvoiceHeaderEdit {
    val parsedNumber = documentNumberNormalized
        ?.let(InvoiceDocumentNumber::parseCanonical)
        ?: documentNumberRaw?.uppercase(Locale.ROOT)?.let(InvoiceDocumentNumber::parseCanonical)
    return InvoiceHeaderEdit(
        draftId = draftId,
        supplierRuc = supplierRucNormalized ?: supplierRucRaw,
        supplierLegalName = supplierLegalNameNormalized ?: supplierLegalNameRaw,
        documentType = documentType?.name,
        documentSeries = parsedNumber?.series,
        documentNumber = parsedNumber?.correlative,
        issueDate = issueDate?.format(HEADER_DATE_FORMATTER),
        currency = currency?.value,
        subtotal = subtotal?.toMajor()?.toPlainString(),
        igv = tax?.toMajor()?.toPlainString(),
        otherCharges = otherCharges?.toMajor()?.toPlainString(),
        total = total?.toMajor()?.toPlainString(),
        revision = 0L,
        touchedFields = emptySet(),
        updatedAt = updatedAt,
    )
}

private fun parseDocumentType(value: String?): PurchaseDocumentType? {
    val normalized = value?.trim()?.uppercase(Locale.ROOT) ?: return null
    return PurchaseDocumentType.entries.firstOrNull { type -> type.name == normalized }
}

private fun parseDate(value: String?): LocalDate? {
    val text = value?.trim().orEmpty()
    if (text.isEmpty()) return null
    return try {
        LocalDate.parse(text, HEADER_DATE_FORMATTER)
    } catch (_: DateTimeParseException) {
        null
    }
}

private fun parseCurrency(value: String?): CurrencyCode? =
    value?.takeIf(String::isNotBlank)?.let { text ->
        runCatching { CurrencyCode.of(text) }.getOrNull()
    }

private fun parseMoney(value: String?, currency: CurrencyCode?): Money? {
    currency ?: return null
    val canonical = value?.trim()?.replace(',', '.')?.takeIf(String::isNotEmpty) ?: return null
    return runCatching { Money.fromMajor(canonical, currency) }.getOrNull()
}

private fun <T> resolveOrPreserve(
    text: String?,
    current: T?,
    parse: (String?) -> T?,
): T? = when {
    text.isNullOrBlank() -> null
    else -> parse(text) ?: current
}

private fun resolveMoneyOrPreserve(
    text: String?,
    currency: CurrencyCode?,
    current: Money?,
): Money? = when {
    text.isNullOrBlank() || currency == null -> null
    else -> parseMoney(text, currency)
        ?: current?.takeIf { amount -> amount.currency == currency }
}
