package com.facturastock.app.domain.normalization

import com.facturastock.app.domain.config.TaxRate
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.InvoiceDocumentNumber
import com.facturastock.app.domain.model.InvoiceOcrSnapshot
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.PurchaseDocumentType
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.UnitCost
import java.math.RoundingMode
import java.time.LocalDate

/** Contexto inmutable y fingerprinted que afecta la interpretación del snapshot. */
data class InvoiceParseContext(
    val parserVersion: Int,
    val contextFingerprint: String,
    val buyerRuc: String?,
    val fallbackCurrency: CurrencyCode,
    val referenceIgvRate: TaxRate,
    val taxRoundingMode: RoundingMode? = null,
) {
    init {
        require(parserVersion > 0) { "parserVersion debe ser positivo" }
    }
}

/** Orquestador puro de cabecera, líneas y totales sobre un único snapshot OCR. */
class InvoiceParser(
    private val headerParser: InvoiceHeaderParser = InvoiceHeaderParser(),
    private val lineItemsParser: InvoiceLineItemsParser = InvoiceLineItemsParser(),
    private val totalsParser: InvoiceTotalsParser = InvoiceTotalsParser(),
) {
    fun parse(
        snapshot: InvoiceOcrSnapshot,
        context: InvoiceParseContext,
        checkpoint: () -> Unit = {},
    ): ParsedInvoice {
        checkpoint()
        val header = headerParser.parse(snapshot, context.buyerRuc)
        checkpoint()
        val explicitCurrency = header.currency.selected
            ?.takeUnless { candidate -> candidate.requiresReview }
            ?.value
        val currencyContext = explicitCurrency ?: context.fallbackCurrency
        // La cabecera ya fue interpretada arriba. Reutilizarla evita recorrer y normalizar todo
        // el documento una segunda vez solo para resolver la moneda de las líneas.
        val lineItems = lineItemsParser.parse(snapshot, currencyContext, header)
        checkpoint()
        val totals = totalsParser.parse(
            snapshot = snapshot,
            context = InvoiceTotalsParseContext(
                draftId = snapshot.draftId,
                runId = snapshot.runId,
                referenceIgvRate = context.referenceIgvRate,
                currency = currencyContext,
                taxRoundingMode = context.taxRoundingMode,
            ),
        )
        checkpoint()
        val audit = ParsedInvoiceAuditFactory.create(
            header = header,
            lineItems = lineItems,
            totals = totals,
            parserVersion = context.parserVersion,
            contextFingerprint = context.contextFingerprint,
            checkpoint = checkpoint,
        )
        checkpoint()
        return ParsedInvoice(header = header, lineItems = lineItems, totals = totals, audit = audit)
    }
}

private object ParsedInvoiceAuditFactory {
    fun create(
        header: InvoiceHeaderParseResult,
        lineItems: InvoiceLineItemsParseResult,
        totals: InvoiceTotalsParseResult,
        parserVersion: Int,
        contextFingerprint: String,
        checkpoint: () -> Unit,
    ): ParsedInvoiceAudit {
        val fields = buildList {
            checkpoint()
            add(header.documentType.trace(ParsedInvoiceFieldKind.DOCUMENT_TYPE, ::canonical))
            add(header.issuerRuc.trace(ParsedInvoiceFieldKind.ISSUER_RUC, ::canonical))
            add(header.issuerLegalName.trace(ParsedInvoiceFieldKind.ISSUER_LEGAL_NAME, ::canonical))
            add(header.documentNumber.trace(ParsedInvoiceFieldKind.DOCUMENT_NUMBER, ::canonical))
            add(header.issueDate.trace(ParsedInvoiceFieldKind.ISSUE_DATE, ::canonical))
            add(header.currency.trace(ParsedInvoiceFieldKind.CURRENCY, ::canonical))
            lineItems.items.forEach { item ->
                checkpoint()
                add(item.code.trace(ParsedInvoiceFieldKind.LINE_CODE, item.position, ::canonical))
                add(item.barcode.trace(ParsedInvoiceFieldKind.LINE_BARCODE, item.position, ::canonical))
                add(item.description.trace(ParsedInvoiceFieldKind.LINE_DESCRIPTION, item.position, ::canonical))
                add(item.quantity.trace(ParsedInvoiceFieldKind.LINE_QUANTITY, item.position, ::canonical))
                add(item.unit.trace(ParsedInvoiceFieldKind.LINE_UNIT, item.position, ::canonical))
                add(item.unitCost.trace(ParsedInvoiceFieldKind.LINE_UNIT_COST, item.position, ::canonical))
                add(item.discount.trace(ParsedInvoiceFieldKind.LINE_DISCOUNT, item.position, ::canonical))
                add(item.tax.trace(ParsedInvoiceFieldKind.LINE_TAX, item.position, ::canonical))
                add(item.total.trace(ParsedInvoiceFieldKind.LINE_TOTAL, item.position, ::canonical))
            }
            add(totals.read.taxableOperations.trace(ParsedInvoiceFieldKind.TAXABLE_OPERATIONS, ::canonical))
            add(totals.read.exemptOperations.trace(ParsedInvoiceFieldKind.EXEMPT_OPERATIONS, ::canonical))
            add(totals.read.unaffectedOperations.trace(ParsedInvoiceFieldKind.UNAFFECTED_OPERATIONS, ::canonical))
            add(totals.read.discounts.trace(ParsedInvoiceFieldKind.DISCOUNTS, ::canonical))
            add(totals.read.subtotal.trace(ParsedInvoiceFieldKind.SUBTOTAL, ::canonical))
            add(totals.read.igv.trace(ParsedInvoiceFieldKind.IGV, ::canonical))
            add(totals.read.printedIgvRate.trace(ParsedInvoiceFieldKind.PRINTED_IGV_RATE, ::canonical))
            totals.read.otherCharges.forEach { charge ->
                checkpoint()
                val trace = InvoiceTotalField(selected = charge.amount)
                    .trace(ParsedInvoiceFieldKind.OTHER_CHARGE, ::canonical, charge.position)
                add(
                    trace.copy(
                        candidates = trace.candidates.map { candidate ->
                            candidate.copy(
                                reasons = (candidate.reasons + "CHARGE.${charge.kind.name}")
                                    .distinct().sorted(),
                            )
                        },
                    ),
                )
            }
            add(totals.read.rounding.trace(ParsedInvoiceFieldKind.ROUNDING, ::canonical))
            add(totals.read.total.trace(ParsedInvoiceFieldKind.DOCUMENT_TOTAL, ::canonical))

            val readEvidence = totals.read.allSelectedEvidence()
            totals.calculated.subtotal?.let { amount ->
                add(amount.calculatedTrace(ParsedInvoiceFieldKind.CALCULATED_SUBTOTAL, readEvidence))
            }
            totals.calculated.igv?.let { amount ->
                add(amount.calculatedTrace(ParsedInvoiceFieldKind.CALCULATED_IGV, readEvidence))
            }
            totals.calculated.totalBeforeRounding?.let { amount ->
                add(
                    amount.calculatedTrace(
                        ParsedInvoiceFieldKind.CALCULATED_TOTAL_BEFORE_ROUNDING,
                        readEvidence,
                    ),
                )
            }
            totals.calculated.total?.let { amount ->
                add(amount.calculatedTrace(ParsedInvoiceFieldKind.CALCULATED_TOTAL, readEvidence))
            }
        }.sortedWith(ParsedInvoiceAudit.FIELD_ORDER)

        checkpoint()
        val fieldByKey = fields.associateBy { it.kind to it.position }
        val warnings = buildWarnings(header, lineItems, totals, fieldByKey, checkpoint)
        val blockers = buildBlockers(lineItems, checkpoint)
        val required = buildList {
            add(requireNotNull(fieldByKey[ParsedInvoiceFieldKind.ISSUER_RUC to null]))
            add(requireNotNull(fieldByKey[ParsedInvoiceFieldKind.DOCUMENT_NUMBER to null]))
            add(requireNotNull(fieldByKey[ParsedInvoiceFieldKind.ISSUE_DATE to null]))
            add(requireNotNull(fieldByKey[ParsedInvoiceFieldKind.DOCUMENT_TOTAL to null]))
            lineItems.items.forEach { item ->
                checkpoint()
                add(requireNotNull(fieldByKey[ParsedInvoiceFieldKind.LINE_DESCRIPTION to item.position]))
                add(requireNotNull(fieldByKey[ParsedInvoiceFieldKind.LINE_QUANTITY to item.position]))
            }
        }
        val overallConfidence = if (lineItems.items.isEmpty()) {
            ParsedInvoiceConfidence.UNKNOWN
        } else {
            required.minBy { field -> field.confidenceRank() }.confidence
        }
        return ParsedInvoiceAudit(
            draftId = header.draftId,
            runId = header.runId,
            parserVersion = parserVersion,
            contextFingerprint = contextFingerprint,
            confidence = overallConfidence,
            fields = fields,
            warnings = warnings,
            blockers = blockers,
        )
    }

    private fun buildWarnings(
        header: InvoiceHeaderParseResult,
        lineItems: InvoiceLineItemsParseResult,
        totals: InvoiceTotalsParseResult,
        fields: Map<Pair<ParsedInvoiceFieldKind, Int?>, ParsedInvoiceFieldTrace>,
        checkpoint: () -> Unit,
    ): List<ParsedInvoiceWarning> = buildList {
        REQUIRED_DOCUMENT_FIELDS.forEach { kind ->
            checkpoint()
            val field = requireNotNull(fields[kind to null])
            when {
                field.candidates.isEmpty() -> add(
                    ParsedInvoiceWarning(ParsedInvoiceWarningCode.MISSING_REQUIRED_FIELD, kind),
                )
                field.selectedCandidateIndex == null -> add(
                    ParsedInvoiceWarning(ParsedInvoiceWarningCode.UNRESOLVED_REQUIRED_FIELD, kind),
                )
                else -> addConfidenceWarning(field)?.let(::add)
            }
            if (field.candidates.isNotEmpty() && field.requiresReview) {
                add(field.reviewWarning())
            }
        }
        ALL_HEADER_FIELDS.forEach { kind ->
            checkpoint()
            fields[kind to null]?.takeIf { it.selectedCandidateIndex != null }
                ?.let(::addConfidenceWarning)?.let(::add)
            fields[kind to null]?.takeIf { it.candidates.isNotEmpty() && it.requiresReview }
                ?.let { add(it.reviewWarning()) }
        }

        lineItems.items.forEach { item ->
            checkpoint()
            ALL_LINE_FIELDS.forEach { kind ->
                val field = requireNotNull(fields[kind to item.position])
                if (field.selectedCandidateIndex != null) {
                    addConfidenceWarning(field)?.let(::add)
                }
                if (field.candidates.isNotEmpty() && field.requiresReview) {
                    add(field.reviewWarning())
                }
            }
            item.warnings.forEach { warning ->
                add(
                    ParsedInvoiceWarning(
                        code = ParsedInvoiceWarningCode.PARSER_WARNING,
                        position = item.position,
                        detail = "LINE.${warning.name}",
                        requiresReview = warning.requiresReview,
                        evidence = item.evidence,
                    ),
                )
            }
        }
        if (lineItems.items.isEmpty()) {
            add(ParsedInvoiceWarning(ParsedInvoiceWarningCode.NO_LINE_ITEMS))
        }
        lineItems.warnings.forEach { warning ->
            add(
                ParsedInvoiceWarning(
                    code = ParsedInvoiceWarningCode.PARSER_WARNING,
                    detail = "LINES.${warning.name}",
                ),
            )
        }

        totals.warnings.forEach { warning ->
            checkpoint()
            add(
                ParsedInvoiceWarning(
                    code = ParsedInvoiceWarningCode.PARSER_WARNING,
                    detail = "TOTALS.${warning.name}",
                    requiresReview = warning.requiresReview,
                    evidence = totals.read.total.selected?.evidence.orEmpty(),
                ),
            )
        }
        TOTAL_FIELDS.forEach { kind ->
            checkpoint()
            fields[kind to null]?.takeIf { it.selectedCandidateIndex != null }
                ?.let(::addConfidenceWarning)?.let(::add)
            fields[kind to null]?.takeIf { it.candidates.isNotEmpty() && it.requiresReview }
                ?.let { add(it.reviewWarning()) }
        }
        totals.reconciliation?.difference?.takeIf { it.minorUnits != 0L }?.let { difference ->
            add(
                ParsedInvoiceWarning(
                    code = ParsedInvoiceWarningCode.FINANCIAL_DIFFERENCE,
                    field = ParsedInvoiceFieldKind.DOCUMENT_TOTAL,
                    detail = canonical(difference),
                    evidence = totals.read.total.selected?.evidence.orEmpty(),
                ),
            )
        }
    }
        .distinct()
        .sortedWith(ParsedInvoiceAudit.WARNING_ORDER)

    private fun buildBlockers(
        lineItems: InvoiceLineItemsParseResult,
        checkpoint: () -> Unit,
    ): List<ParsedInvoiceBlocker> =
        buildList {
            lineItems.items.forEach { item ->
                checkpoint()
                if (item.description?.value == null) {
                    add(
                        ParsedInvoiceBlocker(
                            ParsedInvoiceBlockerCode.MISSING_OR_UNRESOLVED_DESCRIPTION,
                            item.position,
                            item.evidence,
                        ),
                    )
                }
                if (item.quantity?.value == null) {
                    add(
                        ParsedInvoiceBlocker(
                            ParsedInvoiceBlockerCode.MISSING_OR_UNRESOLVED_QUANTITY,
                            item.position,
                            item.evidence,
                        ),
                    )
                }
            }
        }.distinct().sortedWith(ParsedInvoiceAudit.BLOCKER_ORDER)

    private fun addConfidenceWarning(field: ParsedInvoiceFieldTrace): ParsedInvoiceWarning? = when (
        field.confidence
    ) {
        ParsedInvoiceConfidence.LOW -> ParsedInvoiceWarning(
            code = ParsedInvoiceWarningCode.LOW_CONFIDENCE_REQUIRES_CONFIRMATION,
            field = field.kind,
            position = field.position,
            evidence = field.selectedEvidence(),
        )
        ParsedInvoiceConfidence.UNKNOWN -> ParsedInvoiceWarning(
            code = ParsedInvoiceWarningCode.UNKNOWN_CONFIDENCE_REQUIRES_CONFIRMATION,
            field = field.kind,
            position = field.position,
            evidence = field.selectedEvidence(),
        )
        ParsedInvoiceConfidence.MEDIUM,
        ParsedInvoiceConfidence.HIGH,
        -> null
    }

    private fun ParsedInvoiceFieldTrace.reviewWarning(): ParsedInvoiceWarning =
        ParsedInvoiceWarning(
            code = ParsedInvoiceWarningCode.FIELD_REQUIRES_REVIEW,
            field = kind,
            position = position,
            detail = candidates.flatMap(ParsedInvoiceCandidateTrace::warnings)
                .distinct().sorted().joinToString(",").ifEmpty { "REQUIRES_REVIEW" },
            evidence = selectedEvidence().ifEmpty { candidates.flatMap { it.evidence } },
        )

    private fun ParsedInvoiceFieldTrace.selectedEvidence(): List<CandidateEvidence> =
        selectedCandidateIndex?.let(candidates::get)?.evidence.orEmpty()

    private fun ParsedInvoiceFieldTrace.confidenceRank(): Int = confidence.reliabilityRank

    private fun <T : Any> HeaderField<T>.trace(
        kind: ParsedInvoiceFieldKind,
        canonical: (T) -> String,
    ): ParsedInvoiceFieldTrace {
        val candidateTraces = all.map { candidate ->
            val rawConfidence = ParsedInvoiceConfidence.fromPermille(candidate.confidencePermille)
            val confidence = rawConfidence.limitedByReview(candidate.requiresReview)
            ParsedInvoiceCandidateTrace(
                canonicalValue = canonical(candidate.value),
                rawText = candidate.rawText,
                origin = ParsedInvoiceValueOrigin.OCR,
                confidencePermille = candidate.confidencePermille,
                confidence = confidence,
                requiresReview = candidate.requiresReview || confidence.isUnconfirmed(),
                warnings = candidate.warnings.map { "HEADER.${it.name}" }.distinct().sorted(),
                reasons = candidate.reasons.map { "HEADER.${it.name}" }.distinct().sorted(),
                evidence = candidate.evidence,
            )
        }
        val selectedIndex = selected?.let { 0 }
        return ParsedInvoiceFieldTrace(
            kind = kind,
            selectedCandidateIndex = selectedIndex,
            candidates = candidateTraces,
            confidence = selectedIndex?.let { candidateTraces[it].confidence }
                ?: ParsedInvoiceConfidence.UNKNOWN,
            requiresReview = selectedIndex?.let { candidateTraces[it].requiresReview } ?: true,
        )
    }

    private fun <T : Any> Candidate<T>?.trace(
        kind: ParsedInvoiceFieldKind,
        position: Int,
        canonical: (T) -> String,
    ): ParsedInvoiceFieldTrace {
        if (this == null) {
            return ParsedInvoiceFieldTrace(
                kind = kind,
                position = position,
                confidence = ParsedInvoiceConfidence.UNKNOWN,
                requiresReview = true,
            )
        }
        val values: List<T?> = when {
            value != null -> listOf(value)
            alternatives.isNotEmpty() -> alternatives
            else -> listOf(null)
        }
        val rawConfidence = ParsedInvoiceConfidence.fromPermille(confidencePermille)
        val limitedConfidence = rawConfidence.limitedByReview(requiresReview)
        val candidateTraces = values.map { interpreted ->
            ParsedInvoiceCandidateTrace(
                canonicalValue = interpreted?.let(canonical),
                rawText = evidence.rawText,
                origin = ParsedInvoiceValueOrigin.OCR,
                confidencePermille = confidencePermille,
                confidence = limitedConfidence,
                requiresReview = requiresReview || limitedConfidence.isUnconfirmed(),
                warnings = warnings.map { "CANDIDATE.${it.name}" }.distinct().sorted(),
                reasons = emptyList(),
                evidence = listOf(evidence),
            )
        }
        val selectedIndex = value?.let { 0 }
        return ParsedInvoiceFieldTrace(
            kind = kind,
            position = position,
            selectedCandidateIndex = selectedIndex,
            candidates = candidateTraces,
            confidence = selectedIndex?.let { candidateTraces[it].confidence }
                ?: ParsedInvoiceConfidence.UNKNOWN,
            requiresReview = selectedIndex?.let { candidateTraces[it].requiresReview } ?: true,
        )
    }

    private fun <T : Any> InvoiceTotalField<T>.trace(
        kind: ParsedInvoiceFieldKind,
        canonical: (T) -> String,
        position: Int? = null,
    ): ParsedInvoiceFieldTrace {
        val candidateTraces = all.flatMap { occurrence ->
            val candidate = occurrence.candidate
            val values: List<T?> = when {
                candidate.value != null -> listOf(candidate.value)
                candidate.alternatives.isNotEmpty() -> candidate.alternatives
                else -> listOf(null)
            }
            val occurrenceRequiresReview = occurrence.requiresReview
            val rawConfidence = ParsedInvoiceConfidence.fromPermille(candidate.confidencePermille)
            val limitedConfidence = rawConfidence.limitedByReview(occurrenceRequiresReview)
            values.map { interpreted ->
                ParsedInvoiceCandidateTrace(
                    canonicalValue = interpreted?.let(canonical),
                    rawText = occurrence.rawText,
                    origin = ParsedInvoiceValueOrigin.OCR,
                    confidencePermille = candidate.confidencePermille,
                    confidence = limitedConfidence,
                    requiresReview = occurrenceRequiresReview || limitedConfidence.isUnconfirmed(),
                    warnings = (
                        candidate.warnings.map { "CANDIDATE.${it.name}" } +
                            occurrence.warnings.map { "TOTAL.${it.name}" }
                        ).distinct().sorted(),
                    reasons = occurrence.reasons.map { "TOTAL.${it.name}" }.distinct().sorted(),
                    evidence = occurrence.evidence,
                )
            }
        }
        val selectedIndex = selected?.candidate?.value?.let { 0 }
        return ParsedInvoiceFieldTrace(
            kind = kind,
            position = position,
            selectedCandidateIndex = selectedIndex,
            candidates = candidateTraces,
            confidence = selectedIndex?.let { candidateTraces[it].confidence }
                ?: ParsedInvoiceConfidence.UNKNOWN,
            requiresReview = selectedIndex?.let { candidateTraces[it].requiresReview } ?: true,
        )
    }

    private fun CalculatedInvoiceAmount.calculatedTrace(
        kind: ParsedInvoiceFieldKind,
        evidence: List<CandidateEvidence>,
    ): ParsedInvoiceFieldTrace {
        // CandidateEvidence intentionally owns location/text, while the source candidate owns
        // confidence. There is no honest numeric value to invent for a calculated amount.
        val confidencePermille: Int? = null
        val confidence = ParsedInvoiceConfidence.fromPermille(confidencePermille).let { source ->
            if (source.reliabilityRank > ParsedInvoiceConfidence.MEDIUM.reliabilityRank) {
                ParsedInvoiceConfidence.MEDIUM
            } else {
                source
            }
        }
        val trace = ParsedInvoiceCandidateTrace(
            canonicalValue = canonical(value),
            rawText = null,
            origin = ParsedInvoiceValueOrigin.CALCULATED,
            confidencePermille = confidencePermille,
            confidence = confidence,
            requiresReview = true,
            warnings = listOf("CALCULATED.VALUE"),
            reasons = listOf("CALCULATION.${reason.name}") +
                components.map { "COMPONENT.${it.name}" }.sorted(),
            evidence = evidence,
        )
        return ParsedInvoiceFieldTrace(
            kind = kind,
            selectedCandidateIndex = 0,
            candidates = listOf(trace),
            confidence = confidence,
            requiresReview = true,
        )
    }

    private fun ReadInvoiceTotals.allSelectedEvidence(): List<CandidateEvidence> = buildList {
        addAll(taxableOperations.selected?.evidence.orEmpty())
        addAll(exemptOperations.selected?.evidence.orEmpty())
        addAll(unaffectedOperations.selected?.evidence.orEmpty())
        addAll(discounts.selected?.evidence.orEmpty())
        addAll(subtotal.selected?.evidence.orEmpty())
        addAll(igv.selected?.evidence.orEmpty())
        addAll(printedIgvRate.selected?.evidence.orEmpty())
        otherCharges.forEach { addAll(it.amount.evidence) }
        addAll(rounding.selected?.evidence.orEmpty())
        addAll(total.selected?.evidence.orEmpty())
    }.distinct()

    private fun ParsedInvoiceConfidence.limitedByReview(requiresReview: Boolean): ParsedInvoiceConfidence =
        if (requiresReview && reliabilityRank > ParsedInvoiceConfidence.LOW.reliabilityRank) {
            ParsedInvoiceConfidence.LOW
        } else {
            this
        }

    private fun ParsedInvoiceConfidence.isUnconfirmed(): Boolean =
        this == ParsedInvoiceConfidence.LOW || this == ParsedInvoiceConfidence.UNKNOWN

    private fun canonical(value: Any): String = when (value) {
        is String -> value
        is PurchaseDocumentType -> value.name
        is InvoiceDocumentNumber -> value.normalized
        is LocalDate -> value.toString()
        is CurrencyCode -> value.value
        is Money -> "${value.currency.value}:${value.minorUnits}"
        is Quantity -> value.value.toPlainString()
        is UnitCost -> "${value.currency.value}:${value.amount.toPlainString()}"
        is InvoiceUnitCode -> value.name
        is TaxRate -> value.percent.toPlainString()
        is InvoiceLineAdjustment.Rate -> "RATE:${value.value.percent.toPlainString()}"
        is InvoiceLineAdjustment.Amount -> canonical(value.value)
        is InvoiceRoundingAdjustment ->
            "${canonical(value.signedAmount)}:${value.direction.name}:${value.reason.name}"
        else -> error("Tipo canónico no soportado: ${value::class.java.name}")
    }

    private val REQUIRED_DOCUMENT_FIELDS = listOf(
        ParsedInvoiceFieldKind.ISSUER_RUC,
        ParsedInvoiceFieldKind.DOCUMENT_NUMBER,
        ParsedInvoiceFieldKind.ISSUE_DATE,
        ParsedInvoiceFieldKind.DOCUMENT_TOTAL,
    )
    private val ALL_HEADER_FIELDS = listOf(
        ParsedInvoiceFieldKind.ISSUER_RUC,
        ParsedInvoiceFieldKind.DOCUMENT_NUMBER,
        ParsedInvoiceFieldKind.ISSUE_DATE,
        ParsedInvoiceFieldKind.DOCUMENT_TYPE,
        ParsedInvoiceFieldKind.ISSUER_LEGAL_NAME,
        ParsedInvoiceFieldKind.CURRENCY,
    )
    private val ALL_LINE_FIELDS = listOf(
        ParsedInvoiceFieldKind.LINE_CODE,
        ParsedInvoiceFieldKind.LINE_BARCODE,
        ParsedInvoiceFieldKind.LINE_DESCRIPTION,
        ParsedInvoiceFieldKind.LINE_QUANTITY,
        ParsedInvoiceFieldKind.LINE_UNIT,
        ParsedInvoiceFieldKind.LINE_UNIT_COST,
        ParsedInvoiceFieldKind.LINE_DISCOUNT,
        ParsedInvoiceFieldKind.LINE_TAX,
        ParsedInvoiceFieldKind.LINE_TOTAL,
    )
    private val TOTAL_FIELDS = listOf(
        ParsedInvoiceFieldKind.TAXABLE_OPERATIONS,
        ParsedInvoiceFieldKind.EXEMPT_OPERATIONS,
        ParsedInvoiceFieldKind.UNAFFECTED_OPERATIONS,
        ParsedInvoiceFieldKind.DISCOUNTS,
        ParsedInvoiceFieldKind.SUBTOTAL,
        ParsedInvoiceFieldKind.IGV,
        ParsedInvoiceFieldKind.PRINTED_IGV_RATE,
        ParsedInvoiceFieldKind.ROUNDING,
        ParsedInvoiceFieldKind.DOCUMENT_TOTAL,
    )
}
