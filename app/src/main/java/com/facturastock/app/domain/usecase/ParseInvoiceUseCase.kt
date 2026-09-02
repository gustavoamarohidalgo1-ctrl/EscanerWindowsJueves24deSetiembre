package com.facturastock.app.domain.usecase

import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.error.ParsingError
import com.facturastock.app.domain.error.ParsingException
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.DraftStatus
import com.facturastock.app.domain.model.InvoiceDraft
import com.facturastock.app.domain.model.InvoiceLine
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.RucValidator
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.normalization.Candidate
import com.facturastock.app.domain.normalization.InvoiceHeaderCandidate
import com.facturastock.app.domain.normalization.InvoiceLineAdjustment
import com.facturastock.app.domain.normalization.InvoiceParseContext
import com.facturastock.app.domain.normalization.InvoiceParser
import com.facturastock.app.domain.normalization.InvoiceTotalField
import com.facturastock.app.domain.normalization.ParsedInvoice
import com.facturastock.app.domain.normalization.ParsedInvoiceLineItem
import com.facturastock.app.domain.repository.AppConfigurationRepository
import com.facturastock.app.domain.repository.BusinessRepository
import com.facturastock.app.domain.repository.InvoiceDraftRepository
import com.facturastock.app.domain.repository.InvoiceOcrSnapshotRepository
import com.facturastock.app.domain.repository.ParsedInvoicePublication
import com.facturastock.app.domain.repository.ParsedInvoiceRepository
import com.facturastock.app.domain.repository.PublishParsedInvoiceResult
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Produce y publica el borrador estructurado de un snapshot OCR. El parseo es puro; la publicación
 * del audit trail, cabecera editable, líneas y transición a NEEDS_REVIEW es una sola transacción.
 */
class ParseInvoiceUseCase(
    private val invoiceDraftRepository: InvoiceDraftRepository,
    private val invoiceOcrSnapshotRepository: InvoiceOcrSnapshotRepository,
    private val parsedInvoiceRepository: ParsedInvoiceRepository,
    private val appConfigurationRepository: AppConfigurationRepository,
    private val businessRepository: BusinessRepository,
    private val appClock: AppClock,
    private val dispatcherProvider: DispatcherProvider,
    private val invoiceParser: InvoiceParser = InvoiceParser(),
    private val parserVersion: Int = CURRENT_PARSER_VERSION,
) {
    init {
        require(parserVersion > 0) { "parserVersion debe ser positivo" }
    }

    suspend operator fun invoke(draftId: DraftId): ParsedInvoice {
        val draft = invoiceDraftRepository.findDraft(draftId)
            ?: throw ParsingException(ParsingError.DraftNotFound)
        if (
            draft.status !in PARSEABLE_STATUSES ||
            draft.activeOcrRunId != null ||
            draft.confirmedPurchaseId != null
        ) {
            throw ParsingException(ParsingError.DraftNotReady)
        }
        val snapshot = invoiceOcrSnapshotRepository.find(draftId)
            ?: throw ParsingException(ParsingError.SnapshotMissing)
        if (snapshot.draftId != draftId) {
            throw ParsingException(ParsingError.StaleSnapshot)
        }

        val configuration = appConfigurationRepository.current()
        val owner = businessRepository.findById(draft.businessId)
        val buyerRuc = owner?.ruc?.takeIf(RucValidator::isWellFormed)
        // Regex, normalización, auditoría y hashes deterministas son CPU puro. Se aíslan de IO y
        // reciben checkpoints del Job por fase y por línea para que cancelar OCR impida publicar
        // un parseo que ya no interesa.
        val computation = withContext(dispatcherProvider.default) {
            val parseContext = currentCoroutineContext()
            val checkpoint: () -> Unit = { parseContext.ensureActive() }
            checkpoint()
            val contextFingerprint = contextFingerprint(
                draft = draft,
                runId = snapshot.runId.value,
                completedAtMillis = snapshot.completedAt.toEpochMilli(),
                buyerRuc = buyerRuc,
                fallbackCurrency = configuration.currency,
                referenceIgvRate = configuration.taxRate.percent.stripTrailingZeros().toPlainString(),
                parserVersion = parserVersion,
            )
            val parsed = invoiceParser.parse(
                snapshot = snapshot,
                context = InvoiceParseContext(
                    parserVersion = parserVersion,
                    contextFingerprint = contextFingerprint,
                    buyerRuc = buyerRuc,
                    fallbackCurrency = configuration.currency,
                    referenceIgvRate = configuration.taxRate,
                    taxRoundingMode = null,
                ),
                checkpoint = checkpoint,
            )
            checkpoint()
            val parsedAt = appClock.now()
            val publication = ParsedInvoicePublication(
                parsedInvoice = parsed,
                draft = draft.toProjection(parsed, parsedAt),
                lines = parsed.lineItems.items.map { item ->
                    checkpoint()
                    item.toProjection(draft, parsed, parsedAt)
                },
                parsedAt = parsedAt,
            )
            ParseComputation(parsed, publication)
        }
        return when (parsedInvoiceRepository.publish(computation.publication)) {
            PublishParsedInvoiceResult.PUBLISHED,
            PublishParsedInvoiceResult.ALREADY_PUBLISHED,
            -> computation.parsed
            PublishParsedInvoiceResult.STALE_SNAPSHOT ->
                throw ParsingException(ParsingError.StaleSnapshot)
            PublishParsedInvoiceResult.DRAFT_NOT_READY ->
                throw ParsingException(ParsingError.DraftNotReady)
            PublishParsedInvoiceResult.CONFLICT ->
                throw ParsingException(ParsingError.PublicationConflict)
        }
    }

    private fun InvoiceDraft.toProjection(parsed: ParsedInvoice, parsedAt: java.time.Instant): InvoiceDraft {
        val selectedRuc = parsed.header.issuerRuc.selected
        val selectedLegalName = parsed.header.issuerLegalName.selected
        val selectedDocumentType = parsed.header.documentType.selected
        val selectedNumber = parsed.header.documentNumber.selected
        val selectedDate = parsed.header.issueDate.selected
        val selectedCurrency = parsed.header.currency.selected?.value
        val readSubtotal = parsed.totals.read.subtotal.resolvedValue()
        val readTax = parsed.totals.read.igv.resolvedValue()
        val readOtherCharges = parsed.totals.read.otherCharges
            .map { charge -> charge.amount.candidate.value }
            .sumMoneyOrNull()
        val readTotal = parsed.totals.read.total.resolvedValue()
        val projectedCurrencies = listOfNotNull(
            selectedCurrency,
            readSubtotal?.currency,
            readTax?.currency,
            readOtherCharges?.currency,
            readTotal?.currency,
        ).distinct()
        // Un conflicto queda íntegro en el audit trail; la proyección de una sola moneda no
        // elige silenciosamente un bando ni fuerza importes incompatibles.
        val preferredCurrency = projectedCurrencies.singleOrNull()
        val selectedHeaderCandidates = listOfNotNull<InvoiceHeaderCandidate<*>>(
            parsed.header.documentType.selected,
            parsed.header.issuerRuc.selected,
            parsed.header.issuerLegalName.selected,
            parsed.header.documentNumber.selected,
            parsed.header.issueDate.selected,
            parsed.header.currency.selected,
        )
        return copy(
            status = DraftStatus.NEEDS_REVIEW,
            supplierRucRaw = selectedRuc?.rawText,
            supplierRucNormalized = selectedRuc?.value,
            supplierLegalNameRaw = selectedLegalName?.rawText,
            supplierLegalNameNormalized = selectedLegalName?.value,
            documentType = selectedDocumentType?.value,
            documentNumberRaw = selectedNumber?.rawText,
            documentNumberNormalized = selectedNumber?.value?.normalized,
            issueDateRaw = selectedDate?.rawText,
            issueDate = selectedDate?.value,
            currency = preferredCurrency,
            subtotal = readSubtotal.sameCurrencyOrNull(preferredCurrency),
            tax = readTax.sameCurrencyOrNull(preferredCurrency),
            otherCharges = readOtherCharges.sameCurrencyOrNull(preferredCurrency),
            total = readTotal.sameCurrencyOrNull(preferredCurrency),
            headerConfidence = selectedHeaderCandidates.minimumConfidenceOrNull(),
            activeOcrRunId = null,
            lastError = null,
            updatedAt = parsedAt,
        )
    }

    private fun ParsedInvoiceLineItem.toProjection(
        draft: InvoiceDraft,
        parsed: ParsedInvoice,
        parsedAt: java.time.Instant,
    ): InvoiceLine {
        val selectedDescription = description?.value
        val selectedUnitCost = unitCost?.value
        val readDiscount = (discount?.value as? InvoiceLineAdjustment.Amount)?.value
        val readTax = (tax?.value as? InvoiceLineAdjustment.Amount)?.value
        val selectedCurrency = selectedUnitCost?.currency ?: total?.value?.currency
            ?: readDiscount?.currency ?: readTax?.currency
        val selectedLineTotal = total?.value.sameCurrencyOrNull(selectedCurrency)
        val selectedCandidates = listOfNotNull(
            code?.takeIf { it.value != null },
            barcode?.takeIf { it.value != null },
            description?.takeIf { it.value != null },
            quantity?.takeIf { it.value != null },
            unit?.takeIf { it.value != null },
            unitCost?.takeIf { it.value != null },
            discount?.takeIf { it.value != null },
            tax?.takeIf { it.value != null },
            total?.takeIf { it.value != null },
        )
        return InvoiceLine(
            lineId = deterministicLineId(parsed, position),
            draftId = draft.draftId,
            businessId = draft.businessId,
            position = position,
            descriptionRaw = description?.evidence?.rawText?.takeIf(String::isNotBlank) ?: rawText,
            descriptionNormalized = selectedDescription,
            codeRaw = code?.evidence?.rawText,
            codeNormalized = code?.value,
            quantity = quantity?.value,
            unitRaw = unit?.evidence?.rawText,
            unitCodeNormalized = unit?.value?.name,
            unitCost = selectedUnitCost,
            discount = readDiscount.sameCurrencyOrNull(selectedCurrency),
            tax = readTax.sameCurrencyOrNull(selectedCurrency),
            unitId = null,
            productId = null,
            lineTotal = selectedLineTotal,
            ocrConfidence = selectedCandidates.minimumCandidateConfidenceOrNull(),
            linkConfidence = null,
            createdAt = parsedAt,
            updatedAt = parsedAt,
        )
    }

    private fun deterministicLineId(parsed: ParsedInvoice, position: Int): LineId {
        val source = listOf(
            parsed.draftId.value,
            parsed.runId.value,
            parsed.parserVersion.toString(),
            position.toString(),
        ).joinToString("\u001f")
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(StandardCharsets.UTF_8))
            .copyOf(16)
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x50).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
        val buffer = ByteBuffer.wrap(bytes)
        return LineId.from(UUID(buffer.long, buffer.long))
    }

    companion object {
        const val CURRENT_PARSER_VERSION: Int = 1

        private val PARSEABLE_STATUSES = setOf(DraftStatus.OCR_READY, DraftStatus.NEEDS_REVIEW)
    }
}

private data class ParseComputation(
    val parsed: ParsedInvoice,
    val publication: ParsedInvoicePublication,
)

private fun <T : Any> InvoiceTotalField<T>.resolvedValue(): T? =
    selected?.candidate?.value

private fun Money?.sameCurrencyOrNull(currency: CurrencyCode?): Money? =
    this?.takeIf { amount -> currency != null && amount.currency == currency }

/** Los cargos se proyectan solo si todos fueron leídos, son no negativos y comparten moneda. */
private fun List<Money?>.sumMoneyOrNull(): Money? {
    val amounts = map { amount -> amount ?: return null }
    if (amounts.isEmpty() || amounts.any { amount -> amount.minorUnits < 0L }) return null
    val currency = amounts.map(Money::currency).distinct().singleOrNull() ?: return null
    val minorUnits = try {
        amounts.fold(0L) { accumulated, amount ->
            Math.addExact(accumulated, amount.minorUnits)
        }
    } catch (_: ArithmeticException) {
        return null
    }
    return Money.ofMinor(minorUnits, currency)
}

private fun List<InvoiceHeaderCandidate<*>>.minimumConfidenceOrNull(): Int? = when {
    isEmpty() -> null
    any { candidate -> candidate.confidencePermille == null } -> null
    else -> minOf { candidate -> requireNotNull(candidate.confidencePermille) }
}

private fun List<Candidate<*>>.minimumCandidateConfidenceOrNull(): Int? = when {
    isEmpty() -> null
    any { candidate -> candidate.confidencePermille == null } -> null
    else -> minOf { candidate -> requireNotNull(candidate.confidencePermille) }
}

private fun contextFingerprint(
    draft: InvoiceDraft,
    runId: String,
    completedAtMillis: Long,
    buyerRuc: String?,
    fallbackCurrency: CurrencyCode,
    referenceIgvRate: String,
    parserVersion: Int,
): String {
    val canonical = listOf(
        "parserVersion=$parserVersion",
        "draftId=${draft.draftId.value}",
        "businessId=${draft.businessId.value}",
        "runId=$runId",
        "completedAt=$completedAtMillis",
        "buyerRuc=${buyerRuc ?: "<null>"}",
        "fallbackCurrency=${fallbackCurrency.value}",
        "referenceIgvRate=$referenceIgvRate",
        "taxRoundingMode=<null>",
    ).joinToString("\n")
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
}
