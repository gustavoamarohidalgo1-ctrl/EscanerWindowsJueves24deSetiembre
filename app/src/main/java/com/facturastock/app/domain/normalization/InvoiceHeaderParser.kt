package com.facturastock.app.domain.normalization

import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.InvoiceDocumentNumber
import com.facturastock.app.domain.model.InvoiceOcrSnapshot
import com.facturastock.app.domain.model.InvoiceTextBlock
import com.facturastock.app.domain.model.InvoiceTextBoundingBox
import com.facturastock.app.domain.model.InvoiceTextDocument
import com.facturastock.app.domain.model.InvoiceTextGeometry
import com.facturastock.app.domain.model.InvoiceTextLine
import com.facturastock.app.domain.model.InvoiceTextPage
import com.facturastock.app.domain.model.PurchaseDocumentType
import com.facturastock.app.domain.model.RucValidator
import java.text.Normalizer
import java.time.LocalDate
import java.util.Locale

/**
 * Extractor puro de cabecera. Trabaja solo con el snapshot local, no consulta servicios externos y
 * nunca interpreta la comprobación matemática del RUC como confirmación de identidad o estado.
 */
class InvoiceHeaderParser {
    fun parse(
        snapshot: InvoiceOcrSnapshot,
        buyerRuc: String? = null,
    ): InvoiceHeaderParseResult {
        val canonicalBuyerRuc = buyerRuc?.trim()
        require(canonicalBuyerRuc == null || RucValidator.isWellFormed(canonicalBuyerRuc)) {
            "El RUC comprador de contexto debe tener 11 dígitos ASCII"
        }
        val layout = HeaderLayout(snapshot.document.toHeaderFragments())
        return InvoiceHeaderParseResult(
            draftId = snapshot.draftId,
            runId = snapshot.runId,
            documentType = extractDocumentType(layout),
            issuerRuc = extractIssuerRuc(layout, canonicalBuyerRuc),
            issuerLegalName = extractIssuerLegalName(layout),
            documentNumber = extractDocumentNumber(layout),
            issueDate = extractIssueDate(layout),
            currency = extractCurrency(layout),
        )
    }
}

private data class HeaderFragment(
    val page: InvoiceTextPage,
    val blockPosition: Int?,
    val linePosition: Int?,
    val rawText: String,
    val evidence: CandidateEvidence,
    val box: InvoiceTextBoundingBox?,
    val confidencePermille: Int?,
    val usesInheritedBlockBox: Boolean,
) {
    val key: String = evidence.comparisonText.foldAccents()

    val stablePosition: Int
        get() = (blockPosition ?: FALLBACK_BLOCK_POSITION) * POSITION_SCALE + (linePosition ?: 0)
}

private data class RankedHeaderCandidate<T : Any>(
    val candidate: InvoiceHeaderCandidate<T>,
    val semanticRank: List<Int>,
    val selectable: Boolean = true,
)

private enum class LabelRelation(
    val strength: Int,
    val reason: InvoiceHeaderReason,
) {
    SAME_LINE(4, InvoiceHeaderReason.SAME_LINE_AS_LABEL),
    SAME_ROW(3, InvoiceHeaderReason.SAME_ROW_AS_LABEL),
    DIRECTLY_BELOW(2, InvoiceHeaderReason.DIRECTLY_BELOW_LABEL),
    SAME_BLOCK_SEQUENCE(1, InvoiceHeaderReason.DIRECTLY_BELOW_LABEL),
}

private data class RelatedLabel(
    val fragment: HeaderFragment,
    val relation: LabelRelation,
    val distance: Int,
)

private enum class PartyRole(val rank: Int) {
    EXPLICIT_ISSUER(4),
    LIKELY_ISSUER(3),
    UNKNOWN(2),
    RECIPIENT(0),
}

private data class PartyAssessment(
    val role: PartyRole,
    val reasons: List<InvoiceHeaderReason>,
    val warnings: List<InvoiceHeaderWarning>,
)

private class HeaderLayout(
    fragments: List<HeaderFragment>,
) {
    val fragments: List<HeaderFragment> = fragments.sortedWith(HEADER_VISUAL_ORDER)
    private val roleAnchors = this.fragments.filter { fragment ->
        ISSUER_ROLE.containsMatchIn(fragment.key) || RECIPIENT_ROLE.containsMatchIn(fragment.key)
    }

    fun labels(pattern: Regex): List<HeaderFragment> =
        fragments.filter { fragment -> pattern.containsMatchIn(fragment.key) }

    fun bestLabel(value: HeaderFragment, pattern: Regex): RelatedLabel? = labels(pattern)
        .mapNotNull { label ->
            relation(label, value)?.let { relation ->
                RelatedLabel(label, relation, label.distanceTo(value))
            }
        }
        .maxWithOrNull(
            compareBy<RelatedLabel> { related -> related.relation.strength }
                .thenBy { related -> -related.distance },
        )

    fun relatedValues(label: HeaderFragment): List<Pair<HeaderFragment, LabelRelation>> =
        fragments.asSequence()
            .filterNot { value -> value === label }
            .mapNotNull { value -> relation(label, value)?.let { value to it } }
            .sortedWith(
                compareByDescending<Pair<HeaderFragment, LabelRelation>> { (_, relation) ->
                    relation.strength
                }.thenBy { (value, _) -> label.distanceTo(value) },
            )
            .toList()

    fun assessParty(
        value: HeaderFragment,
        fieldLabel: HeaderFragment?,
        canonicalRuc: String? = null,
        buyerRuc: String? = null,
    ): PartyAssessment {
        val localKey = listOfNotNull(fieldLabel?.key, value.key).joinToString(" ")
        if (canonicalRuc != null && canonicalRuc == buyerRuc) {
            return PartyAssessment(
                role = PartyRole.RECIPIENT,
                reasons = listOf(InvoiceHeaderReason.MATCHES_ACTIVE_BUYER),
                warnings = listOf(InvoiceHeaderWarning.RECIPIENT_CONTEXT),
            )
        }
        if (RECIPIENT_ROLE.containsMatchIn(localKey)) {
            return PartyAssessment(
                role = PartyRole.RECIPIENT,
                reasons = listOf(InvoiceHeaderReason.EXPLICIT_RECIPIENT_CONTEXT),
                warnings = listOf(InvoiceHeaderWarning.RECIPIENT_CONTEXT),
            )
        }
        if (ISSUER_ROLE.containsMatchIn(localKey)) {
            return PartyAssessment(
                role = PartyRole.EXPLICIT_ISSUER,
                reasons = listOf(InvoiceHeaderReason.EXPLICIT_ISSUER_CONTEXT),
                warnings = emptyList(),
            )
        }

        val precedingRole = roleAnchors
            .filter { anchor -> anchor.page.pageIndex == value.page.pageIndex }
            .filter { anchor -> anchor.isVisuallyBefore(value) }
            .minByOrNull { anchor -> anchor.distanceTo(value) }
        if (precedingRole != null && RECIPIENT_ROLE.containsMatchIn(precedingRole.key)) {
            return PartyAssessment(
                role = PartyRole.RECIPIENT,
                reasons = listOf(InvoiceHeaderReason.EXPLICIT_RECIPIENT_CONTEXT),
                warnings = listOf(InvoiceHeaderWarning.RECIPIENT_CONTEXT),
            )
        }
        if (precedingRole != null && ISSUER_ROLE.containsMatchIn(precedingRole.key)) {
            return PartyAssessment(
                role = PartyRole.EXPLICIT_ISSUER,
                reasons = listOf(InvoiceHeaderReason.EXPLICIT_ISSUER_CONTEXT),
                warnings = emptyList(),
            )
        }

        val precedingLegalNameLabel = fragments
            .filter { anchor ->
                !RECIPIENT_ROLE.containsMatchIn(anchor.key) &&
                    (
                        LEGAL_NAME_LABEL.containsMatchIn(anchor.key) ||
                            (
                                COMPANY_SUFFIX.containsMatchIn(anchor.key) &&
                                    anchor.rawText.looksLikeLegalName()
                                )
                        )
            }
            .filter { anchor -> anchor.page.pageIndex == value.page.pageIndex }
            .filter { anchor -> anchor.isVisuallyBefore(value) }
            .minByOrNull { anchor -> anchor.distanceTo(value) }
        if (precedingLegalNameLabel != null && value.page.pageIndex == 0) {
            return PartyAssessment(
                role = PartyRole.LIKELY_ISSUER,
                reasons = listOf(InvoiceHeaderReason.ISSUER_LEGAL_NAME_CONTEXT),
                warnings = emptyList(),
            )
        }

        val firstRecipient = roleAnchors
            .filter { anchor ->
                anchor.page.pageIndex == value.page.pageIndex &&
                    RECIPIENT_ROLE.containsMatchIn(anchor.key)
            }
            .minWithOrNull(HEADER_VISUAL_ORDER)
        val beforeRecipient = firstRecipient != null && value.isVisuallyBefore(firstRecipient)
        val inFirstPageHeader = value.page.pageIndex == 0 &&
            (value.box?.topPx ?: value.stablePosition) < value.page.heightPx * HEADER_LIMIT_PERCENT / 100
        return when {
            beforeRecipient -> PartyAssessment(
                role = PartyRole.LIKELY_ISSUER,
                reasons = listOf(InvoiceHeaderReason.HEADER_BEFORE_RECIPIENT_SECTION),
                warnings = emptyList(),
            )

            inFirstPageHeader -> PartyAssessment(
                role = PartyRole.UNKNOWN,
                reasons = listOf(InvoiceHeaderReason.TOP_OF_FIRST_PAGE),
                warnings = listOf(InvoiceHeaderWarning.AMBIGUOUS_PARTY_ROLE),
            )

            else -> PartyAssessment(
                role = PartyRole.UNKNOWN,
                reasons = emptyList(),
                warnings = listOf(InvoiceHeaderWarning.AMBIGUOUS_PARTY_ROLE),
            )
        }
    }

    private fun relation(label: HeaderFragment, value: HeaderFragment): LabelRelation? {
        if (label === value) return LabelRelation.SAME_LINE
        if (label.page.pageIndex != value.page.pageIndex) return null
        val consecutiveInBlock = label.blockPosition == value.blockPosition &&
            label.linePosition != null && value.linePosition == label.linePosition + 1
        if (consecutiveInBlock && (label.usesInheritedBlockBox || value.usesInheritedBlockBox)) {
            return LabelRelation.SAME_BLOCK_SEQUENCE
        }
        val labelBox = label.box
        val valueBox = value.box
        if (labelBox != null && valueBox != null) {
            val verticalOverlap = minOf(labelBox.bottomPx, valueBox.bottomPx) -
                maxOf(labelBox.topPx, valueBox.topPx)
            val minimumHeight = minOf(labelBox.height, valueBox.height)
            val sameRow = verticalOverlap > 0 && verticalOverlap * 2 >= minimumHeight
            val horizontalGap = valueBox.leftPx - labelBox.rightPx
            if (
                sameRow && horizontalGap >= -minimumHeight &&
                horizontalGap <= label.page.widthPx * MAX_ROW_GAP_PERCENT / 100
            ) {
                return LabelRelation.SAME_ROW
            }

            val verticalGap = valueBox.topPx - labelBox.bottomPx
            val maximumGap = maxOf(labelBox.height * 3, label.page.heightPx * MAX_BELOW_GAP_PERCENT / 100)
            val horizontalOverlap = minOf(labelBox.rightPx, valueBox.rightPx) -
                maxOf(labelBox.leftPx, valueBox.leftPx)
            val alignedLeft = kotlin.math.abs(labelBox.leftPx - valueBox.leftPx) <=
                label.page.widthPx * MAX_ALIGNMENT_DRIFT_PERCENT / 100
            if (
                verticalGap in 0..maximumGap &&
                (horizontalOverlap > 0 || alignedLeft)
            ) {
                return LabelRelation.DIRECTLY_BELOW
            }
            return null
        }

        if (
            consecutiveInBlock
        ) {
            return LabelRelation.SAME_BLOCK_SEQUENCE
        }
        return null
    }
}

private fun extractDocumentType(layout: HeaderLayout): HeaderField<PurchaseDocumentType> {
    val candidates = layout.fragments.flatMap { fragment ->
        val label = layout.bestLabel(fragment, TYPE_LABEL)
        val correctedKey = fragment.key.replace('0', 'O')
        DOCUMENT_TYPE_DEFINITIONS.mapNotNull { definition ->
            val correctedMatch = definition.pattern.find(correctedKey) ?: return@mapNotNull null
            val exactMatch = definition.pattern.find(fragment.key)
            val electronicCorrection = ELECTRONIC_OCR_ERROR.find(fragment.key)
            val correction = when {
                exactMatch == null -> correctedMatch.range.toCorrection(fragment, correctedKey)
                electronicCorrection != null -> AppliedOcrCorrection(
                    originalFragment = electronicCorrection.value,
                    correctedFragment = electronicCorrection.value.replace('0', 'O'),
                    reason = OcrCorrectionReason.FIXED_VOCABULARY_CONFUSABLE,
                )

                else -> null
            }
            val evidence = fragment.evidence.withOptionalCorrection(correction)
            RankedHeaderCandidate(
                candidate = InvoiceHeaderCandidate(
                    value = definition.type,
                    rawText = fragment.rawText,
                    evidence = listOfNotNull(label?.fragment?.evidence, evidence).distinct(),
                    boundingBox = fragment.box,
                    confidencePermille = fragment.confidencePermille,
                    warnings = buildList {
                        if (correction != null) add(InvoiceHeaderWarning.OCR_CORRECTION_APPLIED)
                        if (label?.relation == LabelRelation.SAME_BLOCK_SEQUENCE) {
                            add(InvoiceHeaderWarning.WEAK_GEOMETRY_MATCH)
                        }
                    }.distinct(),
                    reasons = buildList {
                        add(InvoiceHeaderReason.FIXED_DOCUMENT_VOCABULARY)
                        if (label != null) {
                            add(InvoiceHeaderReason.EXPLICIT_FIELD_LABEL)
                            add(label.relation.reason)
                        }
                    }.distinct(),
                ),
                semanticRank = listOf(
                    if (label != null) 2 else 1,
                    if (fragment.page.pageIndex == 0) 2 else 1,
                    if (fragment.isInHeader()) 1 else 0,
                ),
            )
        }
    }
    return chooseField(candidates.mergeByValue())
}

private data class RucToken(
    val text: String,
    val fragment: HeaderFragment,
    val explicitLabel: RelatedLabel?,
)

private fun extractIssuerRuc(
    layout: HeaderLayout,
    buyerRuc: String?,
): HeaderField<String> {
    val tokens = layout.fragments.flatMap { fragment -> extractRucTokens(layout, fragment) }
    val candidates = tokens.map { token ->
        val canCorrect = token.explicitLabel != null
        val corrected = if (canCorrect) token.text.correctRucConfusables() else token.text
        val correction = corrected.takeIf { value -> value != token.text }?.let { value ->
            AppliedOcrCorrection(
                originalFragment = token.text,
                correctedFragment = value,
                reason = OcrCorrectionReason.NUMERIC_CONFUSABLE_IN_NUMERIC_CONTEXT,
            )
        }
        val wellFormed = RucValidator.isWellFormed(corrected)
        val checksumConsistent = wellFormed && RucValidator.hasValidChecksum(corrected)
        val role = layout.assessParty(
            value = token.fragment,
            fieldLabel = token.explicitLabel?.fragment,
            canonicalRuc = corrected.takeIf { wellFormed },
            buyerRuc = buyerRuc,
        )
        val relationReason = token.explicitLabel?.relation?.reason
        val unicodeChanged = Normalizer.normalize(token.fragment.rawText, Normalizer.Form.NFKC) !=
            token.fragment.rawText
        val warnings = buildList {
            addAll(role.warnings)
            if (correction != null) add(InvoiceHeaderWarning.OCR_CORRECTION_APPLIED)
            if (unicodeChanged) add(InvoiceHeaderWarning.UNICODE_COMPATIBILITY_NORMALIZED)
            if (!wellFormed) add(InvoiceHeaderWarning.RUC_STRUCTURE_MISMATCH_LOCAL)
            if (wellFormed && !checksumConsistent) {
                add(InvoiceHeaderWarning.RUC_CHECK_DIGIT_MISMATCH_LOCAL)
            }
            if (token.explicitLabel?.relation == LabelRelation.SAME_BLOCK_SEQUENCE) {
                add(InvoiceHeaderWarning.WEAK_GEOMETRY_MATCH)
            }
        }.distinct()
        val reasons = buildList {
            if (token.explicitLabel != null) add(InvoiceHeaderReason.EXPLICIT_FIELD_LABEL)
            if (relationReason != null) add(relationReason)
            addAll(role.reasons)
            if (checksumConsistent) {
                add(InvoiceHeaderReason.RUC_CHECK_DIGIT_CONSISTENT_LOCAL)
            } else if (wellFormed) {
                add(InvoiceHeaderReason.RUC_CHECK_DIGIT_MISMATCH_LOCAL)
            }
        }.distinct()
        val valueEvidence = token.fragment.evidence.withOptionalCorrection(correction)
        RankedHeaderCandidate(
            candidate = InvoiceHeaderCandidate(
                value = corrected,
                rawText = token.fragment.rawText,
                evidence = listOfNotNull(token.explicitLabel?.fragment?.evidence, valueEvidence).distinct(),
                boundingBox = token.fragment.box,
                confidencePermille = token.fragment.confidencePermille,
                warnings = warnings,
                reasons = reasons,
            ),
            semanticRank = listOf(role.role.rank),
            selectable = role.role == PartyRole.EXPLICIT_ISSUER || role.role == PartyRole.LIKELY_ISSUER,
        )
    }.mergeByValue()
    return chooseField(candidates)
}

private fun extractRucTokens(
    layout: HeaderLayout,
    fragment: HeaderFragment,
): List<RucToken> {
    val explicitMatches = RUC_WITH_VALUE.findAll(fragment.key).map { match ->
        RucToken(
            text = match.groupValues[1],
            fragment = fragment,
            explicitLabel = RelatedLabel(fragment, LabelRelation.SAME_LINE, 0),
        )
    }.toList()
    val nearbyLabel = layout.bestLabel(fragment, RUC_LABEL)
    val standaloneExplicit = if (
        nearbyLabel != null && RUC_STANDALONE_VALUE.matches(fragment.key.trim())
    ) {
        listOf(RucToken(fragment.key.trim(), fragment, nearbyLabel))
    } else {
        emptyList()
    }
    val strictMatches = STRICT_RUC.findAll(fragment.key).map { match ->
        RucToken(
            text = match.groupValues[1],
            fragment = fragment,
            explicitLabel = nearbyLabel,
        )
    }.toList()
    return (explicitMatches + standaloneExplicit + strictMatches)
        .distinctBy { token -> token.text to token.fragment.evidencePath }
}

private data class FieldValue(
    val text: String,
    val fragment: HeaderFragment,
    val label: HeaderFragment,
    val relation: LabelRelation,
)

private fun extractIssuerLegalName(layout: HeaderLayout): HeaderField<String> {
    val explicit = layout.labels(LEGAL_NAME_LABEL).flatMap { label ->
        valuesForLabel(layout, label, LEGAL_NAME_LABEL)
            .filter { value -> value.text.looksLikeLegalName() }
            .take(1)
            .map { value ->
                val continuation = layout.relatedValues(value.fragment)
                    .firstOrNull { (fragment, relation) ->
                        relation == LabelRelation.DIRECTLY_BELOW &&
                            fragment !== label && fragment.rawText.looksLikeLegalName()
                    }
                    ?.first
                val valueText = listOfNotNull(
                    value.text,
                    continuation?.evidence?.normalizedText,
                ).joinToString(" ").trim()
                legalNameCandidate(
                    layout = layout,
                    value = valueText,
                    rawText = listOfNotNull(value.fragment.rawText, continuation?.rawText)
                        .distinct()
                        .joinToString(" "),
                    valueFragment = value.fragment,
                    label = label,
                    relation = value.relation,
                    extraEvidence = listOfNotNull(continuation?.evidence),
                    explicit = true,
                )
            }
    }
    val fallback = layout.fragments
        .filter { fragment ->
            COMPANY_SUFFIX.containsMatchIn(fragment.key) && fragment.rawText.looksLikeLegalName()
        }
        .map { fragment ->
            legalNameCandidate(
                layout = layout,
                value = fragment.evidence.normalizedText,
                rawText = fragment.rawText,
                valueFragment = fragment,
                label = null,
                relation = null,
                extraEvidence = emptyList(),
                explicit = false,
            )
        }
    return chooseField((explicit + fallback).mergeByKey { candidate ->
        candidate.candidate.value.foldAccents().uppercase(Locale.ROOT)
    })
}

private fun legalNameCandidate(
    layout: HeaderLayout,
    value: String,
    rawText: String,
    valueFragment: HeaderFragment,
    label: HeaderFragment?,
    relation: LabelRelation?,
    extraEvidence: List<CandidateEvidence>,
    explicit: Boolean,
): RankedHeaderCandidate<String> {
    val role = layout.assessParty(valueFragment, label)
    return RankedHeaderCandidate(
        candidate = InvoiceHeaderCandidate(
            value = value,
            rawText = rawText,
            evidence = (listOfNotNull(label?.evidence, valueFragment.evidence) + extraEvidence)
                .distinct(),
            boundingBox = valueFragment.box,
            confidencePermille = valueFragment.confidencePermille,
            warnings = buildList {
                addAll(role.warnings)
                if (relation == LabelRelation.SAME_BLOCK_SEQUENCE) {
                    add(InvoiceHeaderWarning.WEAK_GEOMETRY_MATCH)
                }
            }.distinct(),
            reasons = buildList {
                if (explicit) add(InvoiceHeaderReason.EXPLICIT_FIELD_LABEL)
                if (relation != null) add(relation.reason)
                if (!explicit) add(InvoiceHeaderReason.COMPANY_LEGAL_SUFFIX)
                addAll(role.reasons)
            }.distinct(),
        ),
        semanticRank = listOf(role.role.rank, if (explicit) 2 else 1),
        selectable = role.role != PartyRole.RECIPIENT,
    )
}

private fun extractDocumentNumber(layout: HeaderLayout): HeaderField<InvoiceDocumentNumber> {
    val direct = layout.fragments.flatMap { fragment ->
        DOCUMENT_NUMBER.findAll(fragment.key).mapNotNull { match ->
            val explicitLabel = NUMBER_LABEL.containsMatchIn(fragment.key)
            val series = match.groupValues[1]
            if (!explicitLabel && series.none { character -> character in 'A'..'Z' }) {
                return@mapNotNull null
            }
            val rawCorrelative = match.groupValues[2]
            val correlative = rawCorrelative.correctRucConfusables()
            val correction = correlative.takeIf { it != rawCorrelative }?.let {
                AppliedOcrCorrection(
                    originalFragment = rawCorrelative,
                    correctedFragment = it,
                    reason = OcrCorrectionReason.NUMERIC_CONFUSABLE_IN_NUMERIC_CONTEXT,
                )
            }
            val number = runCatching { InvoiceDocumentNumber(series, correlative) }.getOrNull()
                ?: return@mapNotNull null
            RankedHeaderCandidate(
                candidate = InvoiceHeaderCandidate(
                    value = number,
                    rawText = fragment.rawText,
                    evidence = listOf(fragment.evidence.withOptionalCorrection(correction)),
                    boundingBox = fragment.box,
                    confidencePermille = fragment.confidencePermille,
                    warnings = if (correction == null) emptyList() else {
                        listOf(InvoiceHeaderWarning.OCR_CORRECTION_APPLIED)
                    },
                    reasons = buildList {
                        add(InvoiceHeaderReason.DOCUMENT_NUMBER_PATTERN)
                        if (explicitLabel) add(InvoiceHeaderReason.EXPLICIT_FIELD_LABEL)
                    },
                ),
                semanticRank = listOf(if (explicitLabel) 3 else 2, if (fragment.page.pageIndex == 0) 1 else 0),
            )
        }.toList()
    }

    val seriesValues = layout.labels(SERIES_LABEL).flatMap { label ->
        valuesForLabel(layout, label, SERIES_LABEL).mapNotNull { value ->
            SERIES_VALUE.matchEntire(value.text.foldAccents().uppercase(Locale.ROOT).trim())
                ?.value?.let { series ->
                value to series
            }
        }
    }
    val correlativeValues = layout.labels(CORRELATIVE_LABEL).flatMap { label ->
        valuesForLabel(layout, label, CORRELATIVE_LABEL).mapNotNull { value ->
            CORRELATIVE_VALUE.matchEntire(value.text.foldAccents().uppercase(Locale.ROOT).trim())
                ?.value?.let { token ->
                Triple(value, token, token.correctRucConfusables())
            }
        }
    }
    val separated = seriesValues.flatMap { (seriesValue, series) ->
        correlativeValues.mapNotNull { (correlativeValue, rawCorrelative, correlative) ->
            if (seriesValue.fragment.page.pageIndex != correlativeValue.fragment.page.pageIndex) {
                return@mapNotNull null
            }
            val number = runCatching { InvoiceDocumentNumber(series, correlative) }.getOrNull()
                ?: return@mapNotNull null
            val correction = correlative.takeIf { it != rawCorrelative }?.let {
                AppliedOcrCorrection(
                    originalFragment = rawCorrelative,
                    correctedFragment = it,
                    reason = OcrCorrectionReason.NUMERIC_CONFUSABLE_IN_NUMERIC_CONTEXT,
                )
            }
            RankedHeaderCandidate(
                candidate = InvoiceHeaderCandidate(
                    value = number,
                    rawText = listOf(seriesValue.fragment.rawText, correlativeValue.fragment.rawText)
                        .distinct().joinToString("\n"),
                    evidence = listOf(
                        seriesValue.label.evidence,
                        seriesValue.fragment.evidence,
                        correlativeValue.label.evidence,
                        correlativeValue.fragment.evidence.withOptionalCorrection(correction),
                    ).distinct(),
                    boundingBox = unionBoxes(
                        listOfNotNull(seriesValue.fragment.box, correlativeValue.fragment.box),
                    ),
                    confidencePermille = listOfNotNull(
                        seriesValue.fragment.confidencePermille,
                        correlativeValue.fragment.confidencePermille,
                    ).minOrNull(),
                    warnings = buildList {
                        if (correction != null) add(InvoiceHeaderWarning.OCR_CORRECTION_APPLIED)
                        if (
                            seriesValue.relation == LabelRelation.SAME_BLOCK_SEQUENCE ||
                            correlativeValue.relation == LabelRelation.SAME_BLOCK_SEQUENCE
                        ) {
                            add(InvoiceHeaderWarning.WEAK_GEOMETRY_MATCH)
                        }
                    }.distinct(),
                    reasons = listOf(
                        InvoiceHeaderReason.EXPLICIT_FIELD_LABEL,
                        seriesValue.relation.reason,
                        correlativeValue.relation.reason,
                    ).distinct(),
                ),
                semanticRank = listOf(4, 1),
            )
        }
    }
    return chooseField((direct + separated).mergeByValue())
}

private fun extractIssueDate(layout: HeaderLayout): HeaderField<LocalDate> {
    val candidates = layout.fragments.flatMap { fragment ->
        HEADER_DATE_TOKEN.findAll(fragment.key).flatMap { tokenMatch ->
            val parsed = PeruvianValueParser.parseDate(tokenMatch.value)
                ?: return@flatMap emptySequence()
            val context = dateContext(layout, fragment, tokenMatch.range.first)
            val values = (listOfNotNull(parsed.value) + parsed.alternatives).distinct()
            values.asSequence().map { date ->
                val correctedEvidence = fragment.evidence.copy(
                    corrections = (fragment.evidence.corrections + parsed.evidence.corrections).distinct(),
                )
                RankedHeaderCandidate(
                    candidate = InvoiceHeaderCandidate(
                        value = date,
                        rawText = fragment.rawText,
                        evidence = listOfNotNull(
                            context.externalLabel?.fragment?.evidence,
                            correctedEvidence,
                        ).distinct(),
                        boundingBox = fragment.box,
                        confidencePermille = fragment.confidencePermille,
                        warnings = buildList {
                            if (parsed.requiresReview) add(InvoiceHeaderWarning.DATE_REQUIRES_REVIEW)
                            if (CandidateWarning.OCR_CORRECTION_APPLIED in parsed.warnings) {
                                add(InvoiceHeaderWarning.OCR_CORRECTION_APPLIED)
                            }
                            if (context.relation == LabelRelation.SAME_BLOCK_SEQUENCE) {
                                add(InvoiceHeaderWarning.WEAK_GEOMETRY_MATCH)
                            }
                        }.distinct(),
                        reasons = buildList {
                            if (context.role != DateRole.NONE) {
                                add(InvoiceHeaderReason.EXPLICIT_FIELD_LABEL)
                            }
                            if (context.relation != null) add(context.relation.reason)
                            when (context.role) {
                                DateRole.ISSUE -> add(InvoiceHeaderReason.ISSUE_DATE_LABEL)
                                DateRole.OTHER -> add(InvoiceHeaderReason.OTHER_DATE_LABEL)
                                DateRole.GENERIC,
                                DateRole.NONE -> Unit
                            }
                        }.distinct(),
                    ),
                    semanticRank = listOf(
                        when (context.role) {
                            DateRole.ISSUE -> 4
                            DateRole.GENERIC -> 3
                            DateRole.NONE -> if (fragment.isInHeader()) 1 else 0
                            DateRole.OTHER -> 0
                        },
                        if (fragment.page.pageIndex == 0) 1 else 0,
                    ),
                    selectable = context.role != DateRole.OTHER,
                )
            }
        }.toList()
    }
    return chooseField(candidates.mergeByValue())
}

private enum class DateRole {
    ISSUE,
    OTHER,
    GENERIC,
    NONE,
}

private data class DateContext(
    val role: DateRole,
    val externalLabel: RelatedLabel?,
    val relation: LabelRelation?,
)

private fun dateContext(
    layout: HeaderLayout,
    fragment: HeaderFragment,
    tokenStart: Int,
): DateContext {
    val inlineLabel = DATE_ROLE_LABEL.findAll(fragment.key.substring(0, tokenStart)).lastOrNull()
    if (inlineLabel != null) {
        return DateContext(
            role = inlineLabel.toDateRole(),
            externalLabel = null,
            relation = LabelRelation.SAME_LINE,
        )
    }
    val external = layout.bestLabel(fragment, DATE_LABEL)
        ?.takeUnless { related -> related.fragment === fragment }
    return DateContext(
        role = external?.fragment?.key?.let { key ->
            DATE_ROLE_LABEL.findAll(key).lastOrNull()?.toDateRole()
        } ?: DateRole.NONE,
        externalLabel = external,
        relation = external?.relation,
    )
}

private fun MatchResult.toDateRole(): DateRole = when (groupValues[1]) {
    "EMISION" -> DateRole.ISSUE
    "VENCIMIENTO", "TRASLADO", "ENTREGA" -> DateRole.OTHER
    else -> DateRole.GENERIC
}

private fun extractCurrency(layout: HeaderLayout): HeaderField<CurrencyCode> {
    val candidates = layout.fragments.flatMap { fragment ->
        val relatedLabel = layout.bestLabel(fragment, CURRENCY_LABEL)
        val vocabularyCorrected = fragment.key.correctCurrencyVocabulary()
        val label = relatedLabel?.takeIf { related ->
            related.relation == LabelRelation.SAME_LINE ||
                CURRENCY_STANDALONE.matches(vocabularyCorrected.trim())
        }
        val correctedKey = if (label == null) fragment.key else fragment.key.correctCurrencyVocabulary()
        val correction = currencyCorrection(fragment.key, correctedKey)
        recognizeCurrencies(correctedKey)
            .filter { match ->
                match.fromSymbol || label != null || CURRENCY_STANDALONE.matches(correctedKey.trim())
            }
            .map { match ->
            val explicit = label != null
            val symbolFallback = !explicit && match.fromSymbol
            RankedHeaderCandidate(
                candidate = InvoiceHeaderCandidate(
                    value = match.currency,
                    rawText = fragment.rawText,
                    evidence = listOfNotNull(
                        label?.fragment?.evidence,
                        fragment.evidence.withOptionalCorrection(correction),
                    ).distinct(),
                    boundingBox = fragment.box,
                    confidencePermille = fragment.confidencePermille,
                    warnings = buildList {
                        if (symbolFallback) {
                            add(InvoiceHeaderWarning.CURRENCY_FROM_AMOUNT_SYMBOL)
                        }
                        if (correction != null) {
                            add(InvoiceHeaderWarning.OCR_CORRECTION_APPLIED)
                        }
                        if (label?.relation == LabelRelation.SAME_BLOCK_SEQUENCE) {
                            add(InvoiceHeaderWarning.WEAK_GEOMETRY_MATCH)
                        }
                    }.distinct(),
                    reasons = buildList {
                        if (explicit) {
                            add(InvoiceHeaderReason.EXPLICIT_FIELD_LABEL)
                            add(InvoiceHeaderReason.EXPLICIT_CURRENCY_LABEL)
                            add(label.relation.reason)
                        } else if (match.fromSymbol) {
                            add(InvoiceHeaderReason.AMOUNT_CURRENCY_SYMBOL)
                        }
                    }.distinct(),
                ),
                semanticRank = listOf(if (explicit) 3 else if (!match.fromSymbol) 2 else 1),
            )
        }
    }
    return chooseField(candidates.mergeByValue())
}

private data class CurrencyMatch(
    val currency: CurrencyCode,
    val fromSymbol: Boolean,
)

private fun recognizeCurrencies(key: String): List<CurrencyMatch> = buildList {
    if (PEN_CODE.containsMatchIn(key)) add(CurrencyMatch(PEN, fromSymbol = false))
    if (PEN_SYMBOL.containsMatchIn(key)) add(CurrencyMatch(PEN, fromSymbol = true))
    if (USD_CODE.containsMatchIn(key)) add(CurrencyMatch(USD, fromSymbol = false))
    if (USD_SYMBOL.containsMatchIn(key)) add(CurrencyMatch(USD, fromSymbol = true))
}.distinct()

private fun String.correctCurrencyVocabulary(): String =
    replace("M0NEDA", "MONEDA")
        .replace("S0LES", "SOLES")
        .replace("S0L", "SOL")
        .replace("D0LARES", "DOLARES")

private fun currencyCorrection(
    originalKey: String,
    correctedKey: String,
): AppliedOcrCorrection? {
    if (originalKey == correctedKey) return null
    val originalToken = listOf("M0NEDA", "S0LES", "S0L", "D0LARES")
        .firstOrNull(originalKey::contains) ?: return null
    return AppliedOcrCorrection(
        originalFragment = originalToken,
        correctedFragment = originalToken.replace('0', 'O'),
        reason = OcrCorrectionReason.FIXED_VOCABULARY_CONFUSABLE,
    )
}

private fun valuesForLabel(
    layout: HeaderLayout,
    label: HeaderFragment,
    labelPattern: Regex,
): List<FieldValue> {
    val inline = labelPattern.find(label.key)?.let { match ->
        label.evidence.normalizedText.substring(match.range.last + 1)
            .trim(*FIELD_SEPARATOR_CHARACTERS)
            .takeIf(String::isNotBlank)
            ?.let { text -> FieldValue(text, label, label, LabelRelation.SAME_LINE) }
    }
    val nearby = layout.relatedValues(label).map { (fragment, relation) ->
        FieldValue(fragment.evidence.normalizedText, fragment, label, relation)
    }
    return listOfNotNull(inline) + nearby
}

private fun String.looksLikeLegalName(): Boolean {
    val normalized = foldAccents().uppercase(Locale.ROOT).trim()
    if (normalized.length !in MIN_LEGAL_NAME_LENGTH..MAX_LEGAL_NAME_LENGTH) return false
    if (normalized.none { character -> character in 'A'..'Z' }) return false
    if (
        RUC_LABEL.containsMatchIn(normalized) || DATE_LABEL.containsMatchIn(normalized) ||
        CURRENCY_LABEL.containsMatchIn(normalized) || DOCUMENT_TYPE_WORDS.containsMatchIn(normalized) ||
        ADDRESS_WORDS.containsMatchIn(normalized) || HEADER_FIELD_WORDS.containsMatchIn(normalized) ||
        RECIPIENT_ROLE.containsMatchIn(normalized)
    ) {
        return false
    }
    return true
}

private fun HeaderFragment.toCandidateSource(): CandidateSource = CandidateSource(
    rawText = rawText,
    boundingBox = box,
    confidencePermille = confidencePermille,
    sourceImageId = page.sourceImageId,
    pageIndex = page.pageIndex,
    cornerPoints = evidence.cornerPoints,
    blockPosition = blockPosition,
    linePosition = linePosition,
    clockwiseAngleTenths = evidence.clockwiseAngleTenths,
)

private val HeaderFragment.evidencePath: String
    get() = "${page.pageIndex}:${blockPosition ?: -1}:${linePosition ?: -1}"

private fun InvoiceTextDocument.toHeaderFragments(): List<HeaderFragment> = pages.flatMap { page ->
    val pageFragments = page.blocks.flatMap { block ->
        if (block.lines.isEmpty()) {
            val box = block.geometry.effectiveBox()
            block.text.nonBlankTextLines().map { (linePosition, text) ->
                CandidateSource(
                    rawText = text,
                    boundingBox = box,
                    sourceImageId = page.sourceImageId,
                    pageIndex = page.pageIndex,
                    cornerPoints = block.geometry.cornerPoints,
                    blockPosition = block.position,
                    linePosition = linePosition,
                ).toHeaderFragment(
                    page = page,
                    blockPosition = block.position,
                    linePosition = linePosition,
                    box = box,
                    confidence = null,
                    usesInheritedBlockBox = box != null,
                )
            }
        } else {
            block.lines.mapNotNull { line ->
                line.text.takeIf(String::isNotBlank)?.let {
                    val effectiveBox = line.effectiveBox(block)
                    CandidateSource.from(page, block, line, effectiveBox.box)
                        .toHeaderFragment(
                            page = page,
                            blockPosition = block.position,
                            linePosition = line.position,
                            box = effectiveBox.box,
                            confidence = line.confidencePermille,
                            usesInheritedBlockBox = effectiveBox.inheritedFromBlock,
                        )
                }
            }
        }
    }
    if (pageFragments.isNotEmpty()) {
        pageFragments
    } else {
        page.text.nonBlankTextLines().map { (linePosition, text) ->
                CandidateSource(
                    rawText = text,
                    sourceImageId = page.sourceImageId,
                    pageIndex = page.pageIndex,
                    linePosition = linePosition,
                ).toHeaderFragment(
                    page = page,
                    blockPosition = null,
                    linePosition = linePosition,
                    box = null,
                    confidence = null,
                    usesInheritedBlockBox = false,
                )
        }
    }
}

private fun CandidateSource.toHeaderFragment(
    page: InvoiceTextPage,
    blockPosition: Int?,
    linePosition: Int?,
    box: InvoiceTextBoundingBox?,
    confidence: Int?,
    usesInheritedBlockBox: Boolean,
): HeaderFragment {
    val evidence = PeruvianTextNormalizer.normalize(this)
    val fragment = HeaderFragment(
        page = page,
        blockPosition = blockPosition,
        linePosition = linePosition,
        rawText = rawText,
        evidence = evidence,
        box = box,
        confidencePermille = confidence,
        usesInheritedBlockBox = usesInheritedBlockBox,
    )
    return fragment
}

private data class EffectiveLineBox(
    val box: InvoiceTextBoundingBox?,
    val inheritedFromBlock: Boolean,
)

private fun InvoiceTextLine.effectiveBox(block: InvoiceTextBlock): EffectiveLineBox {
    val ownBox = geometry.boundingBox
        ?: unionBoxes(elements.mapNotNull { element -> element.geometry.effectiveBox() })
        ?: geometry.cornerEnvelope()
    return if (ownBox != null) {
        EffectiveLineBox(ownBox, inheritedFromBlock = false)
    } else {
        EffectiveLineBox(block.geometry.effectiveBox(), inheritedFromBlock = true)
    }
}

private fun String.nonBlankTextLines(): List<Pair<Int, String>> =
    lineSequence().mapIndexedNotNull { index, line ->
        line.takeIf(String::isNotBlank)?.let { index to it }
    }.toList()

private fun InvoiceTextGeometry.effectiveBox(): InvoiceTextBoundingBox? =
    boundingBox ?: cornerEnvelope()

private fun InvoiceTextGeometry.cornerEnvelope(): InvoiceTextBoundingBox? {
    if (cornerPoints.isEmpty()) return null
    val left = cornerPoints.minOf { point -> point.xPx }
    val top = cornerPoints.minOf { point -> point.yPx }
    val right = cornerPoints.maxOf { point -> point.xPx }
    val bottom = cornerPoints.maxOf { point -> point.yPx }
    return if (right > left && bottom > top) {
        InvoiceTextBoundingBox(left, top, right, bottom)
    } else {
        null
    }
}

private fun unionBoxes(boxes: List<InvoiceTextBoundingBox>): InvoiceTextBoundingBox? {
    if (boxes.isEmpty()) return null
    return InvoiceTextBoundingBox(
        leftPx = boxes.minOf(InvoiceTextBoundingBox::leftPx),
        topPx = boxes.minOf(InvoiceTextBoundingBox::topPx),
        rightPx = boxes.maxOf(InvoiceTextBoundingBox::rightPx),
        bottomPx = boxes.maxOf(InvoiceTextBoundingBox::bottomPx),
    )
}

private val InvoiceTextBoundingBox.height: Int
    get() = bottomPx - topPx

private fun HeaderFragment.distanceTo(other: HeaderFragment): Int {
    val thisBox = box
    val otherBox = other.box
    return if (thisBox != null && otherBox != null) {
        kotlin.math.abs(thisBox.topPx - otherBox.topPx) +
            kotlin.math.abs(thisBox.leftPx - otherBox.leftPx)
    } else {
        kotlin.math.abs(stablePosition - other.stablePosition)
    }
}

private fun HeaderFragment.isVisuallyBefore(other: HeaderFragment): Boolean =
    HEADER_VISUAL_ORDER.compare(this, other) < 0

private fun HeaderFragment.isInHeader(): Boolean = page.pageIndex == 0 &&
    (box?.topPx ?: stablePosition) < page.heightPx * HEADER_LIMIT_PERCENT / 100

private fun String.foldAccents(): String {
    val decomposed = Normalizer.normalize(this, Normalizer.Form.NFD)
    return decomposed.filterNot { character -> Character.getType(character) == Character.NON_SPACING_MARK.toInt() }
}

private fun String.correctRucConfusables(): String = buildString(length) {
    this@correctRucConfusables.forEach { character ->
        append(
            when (character) {
                'O' -> '0'
                'I', 'L', '|' -> '1'
                else -> character
            },
        )
    }
}

private fun IntRange.toCorrection(
    fragment: HeaderFragment,
    correctedKey: String,
): AppliedOcrCorrection? {
    val original = fragment.evidence.normalizedText.substring(first, last + 1)
    val corrected = correctedKey.substring(first, last + 1)
    return corrected.takeIf { value -> value != original }?.let { value ->
        AppliedOcrCorrection(
            originalFragment = original,
            correctedFragment = value,
            reason = OcrCorrectionReason.FIXED_VOCABULARY_CONFUSABLE,
        )
    }
}

private fun CandidateEvidence.withOptionalCorrection(
    correction: AppliedOcrCorrection?,
): CandidateEvidence = if (correction == null) {
    this
} else {
    copy(corrections = (corrections + correction).distinct())
}

private fun <T : Any> List<RankedHeaderCandidate<T>>.mergeByValue(): List<RankedHeaderCandidate<T>> =
    mergeByKey { ranked -> ranked.candidate.value }

private fun <T : Any, K : Any> List<RankedHeaderCandidate<T>>.mergeByKey(
    key: (RankedHeaderCandidate<T>) -> K,
): List<RankedHeaderCandidate<T>> = groupBy(key).values.map { occurrences ->
    val best = occurrences.maxWithOrNull(RANKED_CANDIDATE_ORDER) ?: error("Grupo vacío")
    best.copy(
        candidate = best.candidate.copy(
            evidence = occurrences.flatMap { occurrence -> occurrence.candidate.evidence }.distinct(),
            warnings = occurrences.flatMap { occurrence -> occurrence.candidate.warnings }.distinct(),
            reasons = occurrences.flatMap { occurrence -> occurrence.candidate.reasons }.distinct(),
        ),
        selectable = occurrences.any(RankedHeaderCandidate<T>::selectable),
    )
}

private fun <T : Any> chooseField(
    candidates: List<RankedHeaderCandidate<T>>,
): HeaderField<T> {
    if (candidates.isEmpty()) return HeaderField()
    val sorted = candidates.sortedWith(RANKED_CANDIDATE_ORDER.reversed())
    val selectable = sorted.filter(RankedHeaderCandidate<T>::selectable)
    if (selectable.isEmpty()) {
        return HeaderField(
            alternatives = sorted.map { ranked ->
                if (sorted.size > 1) {
                    ranked.candidate.withWarning(InvoiceHeaderWarning.AMBIGUOUS_VALUE)
                } else {
                    ranked.candidate
                }
            },
        )
    }
    val best = selectable.first()
    val tied = selectable.drop(1).any { candidate -> candidate.semanticRank == best.semanticRank }
    return if (tied) {
        HeaderField(
            alternatives = sorted.map { ranked ->
                if (ranked.semanticRank == best.semanticRank) {
                    ranked.candidate.withWarning(InvoiceHeaderWarning.AMBIGUOUS_VALUE)
                } else {
                    ranked.candidate
                }
            },
        )
    } else {
        HeaderField(
            selected = best.candidate,
            alternatives = sorted
                .filterNot { ranked -> ranked === best }
                .map(RankedHeaderCandidate<T>::candidate),
        )
    }
}

private fun <T : Any> InvoiceHeaderCandidate<T>.withWarning(
    warning: InvoiceHeaderWarning,
): InvoiceHeaderCandidate<T> = copy(warnings = (warnings + warning).distinct())

private fun compareSemanticRanks(left: List<Int>, right: List<Int>): Int {
    val maximum = maxOf(left.size, right.size)
    repeat(maximum) { index ->
        val comparison = (left.getOrElse(index) { 0 }).compareTo(right.getOrElse(index) { 0 })
        if (comparison != 0) return comparison
    }
    return 0
}

private data class DocumentTypeDefinition(
    val pattern: Regex,
    val type: PurchaseDocumentType,
)

private val HEADER_VISUAL_ORDER = compareBy<HeaderFragment> { fragment -> fragment.page.pageIndex }
    .thenBy { fragment -> fragment.box?.topPx ?: fragment.page.heightPx + fragment.stablePosition }
    .thenBy { fragment -> fragment.box?.leftPx ?: fragment.stablePosition }
    .thenBy(HeaderFragment::stablePosition)

private val RANKED_CANDIDATE_ORDER = Comparator<RankedHeaderCandidate<*>> { left, right ->
    compareSemanticRanks(left.semanticRank, right.semanticRank)
}

private val DOCUMENT_TYPE_DEFINITIONS = listOf(
    DocumentTypeDefinition(Regex("\\bNOTA DE CREDITO(?: ELECTRONICA)?\\b"), PurchaseDocumentType.CREDIT_NOTE),
    DocumentTypeDefinition(Regex("\\bNOTA DE DEBITO(?: ELECTRONICA)?\\b"), PurchaseDocumentType.DEBIT_NOTE),
    DocumentTypeDefinition(Regex("\\bBOLETA(?: DE VENTA)?(?: ELECTRONICA)?\\b"), PurchaseDocumentType.SALES_RECEIPT),
    DocumentTypeDefinition(Regex("\\bFACTURA(?: ELECTRONICA)?\\b"), PurchaseDocumentType.INVOICE),
)
private val ELECTRONIC_OCR_ERROR = Regex("\\bELECTR0NICA\\b")
private val DOCUMENT_TYPE_WORDS = Regex("\\b(?:FACTURA|BOLETA|NOTA DE (?:CREDITO|DEBITO))\\b")
private val TYPE_LABEL = Regex("\\b(?:TIPO(?: DE COMPROBANTE)?|COMPROBANTE)\\b\\s*[:#-]?")
private val RUC_LABEL = Regex(
    "(?<![A-Z0-9])R\\s*\\.?\\s*U\\s*\\.?\\s*C\\s*\\.?(?![A-Z0-9])",
)
private val RUC_WITH_VALUE = Regex(
    "(?<![A-Z0-9])R\\s*\\.?\\s*U\\s*\\.?\\s*C\\s*\\.?(?![A-Z0-9])" +
        "\\s*[:#-]?\\s*([A-Z0-9|]{8,14})(?![A-Z0-9|])",
)
private val RUC_STANDALONE_VALUE = Regex("[A-Z0-9|]{8,14}")
private val STRICT_RUC = Regex("(?<![A-Z0-9])([0-9]{11})(?![A-Z0-9])")
private val ISSUER_ROLE = Regex(
    "(?:^(?:DATOS(?: DEL)? )?(?:EMISOR|PROVEEDOR|VENDEDOR)\\b" +
        "(?:\\s*[:#-]|\\s+RUC\\b|$)|\\bRUC(?: DEL)? (?:EMISOR|PROVEEDOR|VENDEDOR)\\b)",
)
private val RECIPIENT_ROLE = Regex(
    "(?:^(?:DATOS(?: DEL)? )?(?:CLIENTE|ADQUIRIENTE|RECEPTOR|DESTINATARIO|SENOR(?:ES)?)\\b" +
        "(?:\\s*[:#-]|\\s+RUC\\b|$)|\\bRUC(?: DEL)? " +
        "(?:CLIENTE|ADQUIRIENTE|RECEPTOR|DESTINATARIO)\\b|" +
        "\\b(?:RAZON SOCIAL|NOMBRE(?: O RAZON SOCIAL)?) DEL " +
        "(?:CLIENTE|ADQUIRIENTE|RECEPTOR|DESTINATARIO)\\b)",
)
private val LEGAL_NAME_LABEL = Regex(
    "\\b(?:NOMBRE(?: O)? RAZON SOCIAL|RAZON SOCIAL|EMISOR|PROVEEDOR)\\b\\s*[:#-]?",
)
private val COMPANY_SUFFIX = Regex(
    "\\b(?:S\\.?\\s*A\\.?\\s*C\\.?|E\\.?\\s*I\\.?\\s*R\\.?\\s*L\\.?|S\\.?\\s*R\\.?\\s*L\\.?|S\\.?\\s*A\\.?)\\b",
)
private val ADDRESS_WORDS = Regex("\\b(?:DIRECCION|DOMICILIO|AVENIDA|CALLE|JR\\.?|JIRON)\\b")
private val HEADER_FIELD_WORDS = Regex(
    "\\b(?:TIPO|SERIE|CORRELATIVO|NUMERO|NRO|MONEDA|FECHA|TOTAL|SUBTOTAL|IGV)\\b",
)
private val DOCUMENT_NUMBER = Regex(
    "(?<![A-Z0-9])([A-Z0-9]{1,4})\\s*[-–—]\\s*([0-9OIL|]{1,12})(?![A-Z0-9])",
)
private val NUMBER_LABEL = Regex("\\b(?:NRO\\.?|NUMERO|COMPROBANTE|SERIE|CORRELATIVO)\\b")
private val SERIES_LABEL = Regex("\\bSERIE\\b\\s*[:#-]?")
private val CORRELATIVE_LABEL = Regex("\\b(?:CORRELATIVO|NUMERO|NRO\\.?)\\b\\s*[:#-]?")
private val SERIES_VALUE = Regex("(?<![A-Z0-9])[A-Z][A-Z0-9]{0,3}(?![A-Z0-9])")
private val CORRELATIVE_VALUE = Regex("(?<![A-Z0-9])[0-9OIL|]{1,12}(?![A-Z0-9])")
private val DATE_LABEL = Regex("\\bFECHA(?: DE (?:EMISION|VENCIMIENTO|TRASLADO|ENTREGA))?\\b\\s*[:#-]?")
private val DATE_ROLE_LABEL = Regex(
    "\\bFECHA(?: DE (EMISION|VENCIMIENTO|TRASLADO|ENTREGA))?\\b\\s*[:#-]?",
)
private val HEADER_DATE_TOKEN = Regex(
    "(?<![A-Z0-9.,+/%_\u2019'|‒−–—-])[0-9OIL|]{1,4}[./-][0-9OIL|]{1,2}" +
        "[./-][0-9OIL|]{2,4}(?![A-Z0-9.,:+/%_\u2019'|‒−–—-])",
)
private val CURRENCY_LABEL = Regex("\\b(?:M[O0]NEDA|CURRENCY)\\b\\s*[:#-]?")
private val PEN_CODE = Regex("(?<![A-Z])(?:PEN|SOLES?)(?![A-Z])")
private val PEN_SYMBOL = Regex("(?<![A-Z])S\\s*/")
private val USD_CODE = Regex("(?<![A-Z])(?:USD|DOLARES?)(?![A-Z])")
private val USD_SYMBOL = Regex("(?<![A-Z])US\\s*\\$")
private val CURRENCY_STANDALONE = Regex(
    "(?:PEN|SOLES?|S\\s*/|USD|DOLARES?|US\\s*\\$)",
)
private val PEN = CurrencyCode.of("PEN")
private val USD = CurrencyCode.of("USD")
private val FIELD_SEPARATOR_CHARACTERS = charArrayOf(' ', ':', '#', '-', '–', '—')
private const val HEADER_LIMIT_PERCENT = 45
private const val MAX_ROW_GAP_PERCENT = 45
private const val MAX_BELOW_GAP_PERCENT = 4
private const val MAX_ALIGNMENT_DRIFT_PERCENT = 5
private const val FALLBACK_BLOCK_POSITION = 100_000
private const val POSITION_SCALE = 1_000
private const val MIN_LEGAL_NAME_LENGTH = 3
private const val MAX_LEGAL_NAME_LENGTH = 256
