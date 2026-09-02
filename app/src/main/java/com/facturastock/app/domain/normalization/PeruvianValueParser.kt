package com.facturastock.app.domain.normalization

import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.ExactDecimalPolicy
import com.facturastock.app.domain.model.InvoiceTextBoundingBox
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.UnitCost
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DateTimeException
import java.time.LocalDate

/** Códigos SUNAT canónicos que este primer normalizador reconoce. */
enum class InvoiceUnitCode {
    NIU,
    KGM,
    LTR,
}

/**
 * Parser léxico puro para facturas peruanas. Enumera gramáticas válidas en lugar de eliminar
 * separadores a ciegas; si sobreviven dos valores distintos devuelve un candidato no resuelto.
 */
object PeruvianValueParser {
    private val pen = CurrencyCode.of("PEN")

    fun parseMoney(
        source: CandidateSource,
        currencyContext: CurrencyCode? = null,
    ): Candidate<Money>? {
        if (!source.isWithinParserBudget()) return null
        val evidence = PeruvianTextNormalizer.normalize(source)
        val selection = selectMonetaryTokens(evidence, currencyContext) ?: return null
        val parsed = selection.entries.mapNotNull { entry ->
            parseNumericToken(entry.token)?.let { token ->
                ParsedMonetaryEntry(token = token, currencies = entry.currencies)
            }
        }
        if (parsed.isEmpty()) return null

        val values = parsed
            .flatMap { entry ->
                entry.token.interpretations.flatMap { amount ->
                    entry.currencies.mapNotNull { currency -> amount.toMoney(currency) }
                }
            }
            .distinct()
        val warnings = mutableListOf<CandidateWarning>()
        if (selection.entries.size > 1) warnings += CandidateWarning.MULTIPLE_VALUES_FOUND
        if (parsed.any { it.token.interpretations.size > 1 }) {
            warnings += CandidateWarning.AMBIGUOUS_NUMBER_SEPARATOR
        }
        if (parsed.any { entry ->
                entry.token.interpretations.any { amount ->
                    entry.currencies.any { currency -> !amount.hasExactScaleFor(currency) }
                }
            }
        ) {
            warnings += CandidateWarning.UNSUPPORTED_FRACTION_PRECISION
        }
        if (parsed.any { entry ->
                entry.token.interpretations.any { amount ->
                    entry.currencies.any { currency ->
                        amount.hasExactScaleFor(currency) && amount.toMoney(currency) == null
                    }
                }
            }
        ) {
            warnings += CandidateWarning.UNSUPPORTED_NUMERIC_VALUE
        }
        if (selection.fromContext) warnings += CandidateWarning.CURRENCY_FROM_CONTEXT
        if (selection.detectedCurrencies.size > 1) {
            warnings += CandidateWarning.CURRENCY_CONFLICT
        }
        val corrections = parsed.mapNotNull { entry -> entry.token.correction }
        if (corrections.isNotEmpty()) warnings += CandidateWarning.OCR_CORRECTION_APPLIED

        val requiresResolution = warnings.any(CandidateWarning::requiresReview) || values.size != 1
        if (values.isEmpty() && warnings.isEmpty()) return null
        return candidate(
            source = source,
            evidence = evidence.withCorrections(corrections),
            value = values.singleOrNull().takeUnless { requiresResolution },
            warnings = warnings,
            alternatives = if (requiresResolution) values else emptyList(),
        )
    }

    fun parseMoney(
        rawText: String,
        boundingBox: InvoiceTextBoundingBox? = null,
        confidencePermille: Int? = null,
        currencyContext: CurrencyCode? = null,
    ): Candidate<Money>? = parseMoney(
        CandidateSource(rawText, boundingBox, confidencePermille),
        currencyContext,
    )

    fun parseUnitCost(
        source: CandidateSource,
        currencyContext: CurrencyCode? = null,
    ): Candidate<UnitCost>? {
        if (!source.isWithinParserBudget()) return null
        val evidence = PeruvianTextNormalizer.normalize(source)
        val selection = selectMonetaryTokens(evidence, currencyContext) ?: return null
        val parsed = selection.entries.mapNotNull { entry ->
            parseNumericToken(entry.token)?.let { token ->
                ParsedMonetaryEntry(token = token, currencies = entry.currencies)
            }
        }
        if (parsed.isEmpty()) return null
        val values = parsed
            .flatMap { entry ->
                entry.token.interpretations.flatMap { amount ->
                    entry.currencies.mapNotNull { currency -> amount.toUnitCost(currency) }
                }
            }
            .distinct()
        val warnings = mutableListOf<CandidateWarning>()
        if (selection.entries.size > 1) warnings += CandidateWarning.MULTIPLE_VALUES_FOUND
        if (parsed.any { it.token.interpretations.size > 1 }) {
            warnings += CandidateWarning.AMBIGUOUS_NUMBER_SEPARATOR
        }
        if (selection.fromContext) warnings += CandidateWarning.CURRENCY_FROM_CONTEXT
        if (selection.detectedCurrencies.size > 1) {
            warnings += CandidateWarning.CURRENCY_CONFLICT
        }
        val corrections = parsed.mapNotNull { entry -> entry.token.correction }
        if (corrections.isNotEmpty()) warnings += CandidateWarning.OCR_CORRECTION_APPLIED
        if (values.isEmpty() && parsed.any { entry ->
                entry.token.interpretations.any { amount -> amount.signum() >= 0 }
            }
        ) {
            warnings += CandidateWarning.UNSUPPORTED_NUMERIC_VALUE
        }
        val unresolved = warnings.any(CandidateWarning::requiresReview) || values.size != 1
        if (values.isEmpty() && warnings.none(CandidateWarning::requiresReview)) return null
        return candidate(
            source,
            evidence.withCorrections(corrections),
            values.singleOrNull().takeUnless { unresolved },
            warnings,
            if (unresolved) values else emptyList(),
        )
    }

    fun parseUnitCost(
        rawText: String,
        boundingBox: InvoiceTextBoundingBox? = null,
        confidencePermille: Int? = null,
        currencyContext: CurrencyCode? = null,
    ): Candidate<UnitCost>? = parseUnitCost(
        CandidateSource(rawText, boundingBox, confidencePermille),
        currencyContext,
    )

    fun parseQuantity(source: CandidateSource): Candidate<Quantity>? {
        if (!source.isWithinParserBudget()) return null
        val evidence = PeruvianTextNormalizer.normalize(source)
        val tokens = extractNumericTokens(evidence.comparisonText)
        if (tokens.isEmpty()) return null
        val parsed = tokens.mapNotNull(::parseNumericToken)
        if (parsed.isEmpty()) return null
        val values = parsed
            .flatMap(ParsedNumericToken::interpretations)
            .mapNotNull(BigDecimal::toQuantity)
            .distinct()
        val warnings = mutableListOf<CandidateWarning>()
        if (tokens.size > 1) warnings += CandidateWarning.MULTIPLE_VALUES_FOUND
        if (parsed.any { it.interpretations.size > 1 }) {
            warnings += CandidateWarning.AMBIGUOUS_NUMBER_SEPARATOR
        }
        val corrections = parsed.mapNotNull(ParsedNumericToken::correction)
        if (corrections.isNotEmpty()) warnings += CandidateWarning.OCR_CORRECTION_APPLIED
        if (values.isEmpty() && parsed.any { token ->
                token.interpretations.any { amount -> amount.signum() > 0 }
            }
        ) {
            warnings += CandidateWarning.UNSUPPORTED_NUMERIC_VALUE
        }
        if (values.isEmpty() && warnings.none(CandidateWarning::requiresReview)) return null
        val unresolved = warnings.any(CandidateWarning::requiresReview) || values.size != 1
        return candidate(
            source,
            evidence.withCorrections(corrections),
            values.singleOrNull().takeUnless { unresolved },
            warnings,
            if (unresolved) values else emptyList(),
        )
    }

    fun parseQuantity(
        rawText: String,
        boundingBox: InvoiceTextBoundingBox? = null,
        confidencePermille: Int? = null,
    ): Candidate<Quantity>? = parseQuantity(
        CandidateSource(rawText, boundingBox, confidencePermille),
    )

    fun parseDate(source: CandidateSource): Candidate<LocalDate>? {
        if (!source.isWithinParserBudget()) return null
        val evidence = PeruvianTextNormalizer.normalize(source)
        val matches = DATE_TOKEN.findAll(evidence.comparisonText).map(MatchResult::value).toList()
        if (matches.isEmpty()) return null
        val parsed = matches.mapNotNull(::parseDateToken)
        if (parsed.isEmpty()) return null

        val warnings = parsed.flatMap(ParsedDateToken::warnings).toMutableList()
        if (matches.size > 1) warnings += CandidateWarning.MULTIPLE_VALUES_FOUND
        val corrections = parsed.mapNotNull(ParsedDateToken::correction)
        if (corrections.isNotEmpty()) warnings += CandidateWarning.OCR_CORRECTION_APPLIED
        val possibleDates = parsed.flatMap(ParsedDateToken::dates).distinct()
        val certainDates = parsed.mapNotNull(ParsedDateToken::certainDate).distinct()
        if (possibleDates.isEmpty() && certainDates.isEmpty()) {
            // Dos dígitos de año conservan evidencia dudosa aunque no inventemos el siglo.
            if (warnings.none(CandidateWarning::requiresReview)) return null
            return candidate(
                source,
                evidence.withCorrections(corrections),
                value = null,
                warnings = warnings,
                alternatives = emptyList(),
            )
        }
        val allDates = (certainDates + possibleDates).distinct()
        val unresolved = warnings.any(CandidateWarning::requiresReview) ||
            matches.size > 1 || allDates.size != 1
        return candidate(
            source,
            evidence.withCorrections(corrections),
            allDates.singleOrNull().takeUnless { unresolved },
            warnings,
            if (unresolved) allDates else emptyList(),
        )
    }

    fun parseDate(
        rawText: String,
        boundingBox: InvoiceTextBoundingBox? = null,
        confidencePermille: Int? = null,
    ): Candidate<LocalDate>? = parseDate(CandidateSource(rawText, boundingBox, confidencePermille))

    fun parseUnit(source: CandidateSource): Candidate<InvoiceUnitCode>? {
        if (!source.isWithinParserBudget()) return null
        val evidence = PeruvianTextNormalizer.normalize(source)
        val recognized = UNIT_TOKEN.findAll(evidence.comparisonText)
            .filterNot { match ->
                touchesUnitCompositionSyntax(evidence.comparisonText, match.range)
            }
            .flatMap { match ->
                val normalizedToken = match.value.removeSuffix(".")
                if (normalizedToken == WEAK_UNIT_ALIAS &&
                    match.range != evidence.comparisonText.indices
                ) {
                    emptySequence()
                } else {
                    recognizeUnits(match.value).asSequence()
                }
            }
            .toList()
        if (recognized.isEmpty()) return null
        val values = recognized.map(RecognizedUnit::value).distinct()
        val warnings = recognized.flatMap(RecognizedUnit::warnings).toMutableList()
        if (recognized.size > 1) warnings += CandidateWarning.MULTIPLE_VALUES_FOUND
        val corrections = recognized.mapNotNull(RecognizedUnit::correction)
        if (corrections.isNotEmpty()) warnings += CandidateWarning.OCR_CORRECTION_APPLIED
        val unresolved = warnings.any(CandidateWarning::requiresReview) || values.size != 1
        return candidate(
            source,
            evidence.withCorrections(corrections),
            values.singleOrNull().takeUnless { unresolved },
            warnings,
            if (unresolved) values else emptyList(),
        )
    }

    fun parseUnit(
        rawText: String,
        boundingBox: InvoiceTextBoundingBox? = null,
        confidencePermille: Int? = null,
    ): Candidate<InvoiceUnitCode>? = parseUnit(CandidateSource(rawText, boundingBox, confidencePermille))

    private fun selectMonetaryTokens(
        evidence: CandidateEvidence,
        currencyContext: CurrencyCode?,
    ): MonetarySelection? {
        val text = evidence.comparisonText
        val penMarkers = PEN_MARKER.findAll(text)
            .map { match -> CurrencyMarker(match.range, pen) }
            .toList()
        val isoCurrencyMatches = ISO_CURRENCY.findAll(text)
            .mapNotNull { match ->
                runCatching { CurrencyCode.of(match.value) }.getOrNull()?.let { currency ->
                    CurrencyMarker(match.range, currency)
                }
            }
            .toList()
        val markers = (penMarkers + isoCurrencyMatches)
            .distinctBy { marker -> marker.range to marker.currency }
            .sortedBy { marker -> marker.range.first }

        val tokens = extractNumericTokens(text)
        if (tokens.isEmpty()) return null
        if (markers.isEmpty()) {
            val context = currencyContext ?: return null
            return MonetarySelection(
                entries = tokens.map { token -> MonetaryEntry(token, listOf(context)) },
                fromContext = true,
                detectedCurrencies = listOf(context),
            )
        }
        val markerGroups = groupCurrencyMarkers(text, markers)
        val assignments = mutableListOf<MonetaryMarkerAssignment>()
        var followingTokenIndex = 0
        markerGroups.forEach { markerGroup ->
            while (
                followingTokenIndex < tokens.size &&
                tokens[followingTokenIndex].range.last < markerGroup.range.first
            ) {
                followingTokenIndex++
            }
            var afterGroupIndex = followingTokenIndex
            while (
                afterGroupIndex < tokens.size &&
                tokens[afterGroupIndex].range.first <= markerGroup.range.last
            ) {
                afterGroupIndex++
            }
            val following = tokens.getOrNull(afterGroupIndex)?.takeIf { token ->
                isAdjacentValue(text, markerGroup.range, token.range)
            }
            val preceding = tokens.getOrNull(followingTokenIndex - 1)?.takeIf { token ->
                isAdjacentValue(text, markerGroup.range, token.range)
            }
            val selectedTokens = listOfNotNull(preceding, following).distinctBy(TextToken::range)
            if (selectedTokens.any { token ->
                    isSignedOrParenthesizedAmount(text, markerGroup.range, token.range)
                }
            ) {
                return null
            }
            selectedTokens.forEach { token ->
                markerGroup.currencies.forEach { currency ->
                    assignments += MonetaryMarkerAssignment(token, currency)
                }
            }
        }
        val entries = assignments
            .groupBy { assignment -> assignment.token.range }
            .values
            .map { tokenAssignments ->
                val explicitCurrencies = tokenAssignments
                    .map(MonetaryMarkerAssignment::currency)
                    .distinct()
                MonetaryEntry(
                    token = tokenAssignments.first().token,
                    currencies = buildList {
                        addAll(explicitCurrencies)
                        if (currencyContext != null && currencyContext !in explicitCurrencies) {
                            add(currencyContext)
                        }
                    },
                )
            }
            .sortedBy { entry -> entry.token.range.first }
        if (entries.isEmpty()) return null
        return MonetarySelection(
            entries = entries,
            fromContext = false,
            detectedCurrencies = buildList {
                addAll(markers.map(CurrencyMarker::currency))
                if (currencyContext != null) add(currencyContext)
            }.distinct(),
        )
    }
}

private data class MonetarySelection(
    val entries: List<MonetaryEntry>,
    val fromContext: Boolean,
    val detectedCurrencies: List<CurrencyCode>,
)

private data class MonetaryEntry(
    val token: TextToken,
    val currencies: List<CurrencyCode>,
)

private data class CurrencyMarker(val range: IntRange, val currency: CurrencyCode)

private data class CurrencyMarkerGroup(
    val range: IntRange,
    val currencies: List<CurrencyCode>,
)

private data class MonetaryMarkerAssignment(
    val token: TextToken,
    val currency: CurrencyCode,
)

private data class TextToken(val text: String, val range: IntRange)

private data class ParsedNumericToken(
    val interpretations: List<BigDecimal>,
    val correction: AppliedOcrCorrection?,
)

private data class ParsedMonetaryEntry(
    val token: ParsedNumericToken,
    val currencies: List<CurrencyCode>,
)

private data class ParsedDateToken(
    val certainDate: LocalDate?,
    val dates: List<LocalDate>,
    val warnings: List<CandidateWarning>,
    val correction: AppliedOcrCorrection?,
)

private data class RecognizedUnit(
    val value: InvoiceUnitCode,
    val warnings: List<CandidateWarning>,
    val correction: AppliedOcrCorrection?,
)

private fun parseNumericToken(token: TextToken): ParsedNumericToken? {
    if (token.text.length > ExactDecimalPolicy.MAX_INPUT_CHARACTERS) return null
    val corrected = correctNumericConfusables(token.text)
    val interpretations = enumerateLocalizedNumbers(corrected.corrected).mapNotNull { value ->
        runCatching { BigDecimal(value) }.getOrNull()
    }.distinct()
    if (interpretations.isEmpty()) return null
    return ParsedNumericToken(interpretations, corrected.correction)
}

private fun enumerateLocalizedNumbers(token: String): List<String> {
    if (token.startsWith('+') || token.startsWith('-')) return emptyList()
    if (!token.any(Char::isDigit)) return emptyList()
    if (' ' in token) return emptyList()
    val interpretations = mutableListOf<String>()
    if (US_NUMBER.matches(token)) interpretations += token.replace(",", "")
    if (EU_NUMBER.matches(token)) interpretations += token.replace(".", "").replace(',', '.')
    return interpretations.distinct()
}

private data class CorrectedToken(
    val corrected: String,
    val correction: AppliedOcrCorrection?,
)

private fun correctNumericConfusables(token: String): CorrectedToken {
    val corrected = buildString(token.length) {
        token.forEach { character ->
            append(
                when (character) {
                    'O' -> '0'
                    'I', 'L', '|' -> '1'
                    else -> character
                },
            )
        }
    }
    val correction = corrected.takeIf { it != token }?.let {
        AppliedOcrCorrection(
            originalFragment = token,
            correctedFragment = it,
            reason = OcrCorrectionReason.NUMERIC_CONFUSABLE_IN_NUMERIC_CONTEXT,
        )
    }
    return CorrectedToken(corrected, correction)
}

private fun extractNumericTokens(text: String): List<TextToken> =
    NUMBER_TOKEN.findAll(text)
        .filterNot { match -> touchesExponentSyntax(text, match.range) }
        .filterNot { match -> touchesDetachedNumericSyntax(text, match.range) }
        .map { match -> TextToken(match.value, match.range) }
        .toList()

private fun touchesExponentSyntax(text: String, range: IntRange): Boolean {
    var before = text.previousNonWhitespaceIndex(range.first - 1)
    if (before >= 0 && text[before] in EXPONENT_SIGN_CHARACTERS) {
        before = text.previousNonWhitespaceIndex(before - 1)
    }
    val precededByExponent = before >= 0 && text[before] == 'E' &&
        text.previousNonWhitespaceIndex(before - 1).let { base ->
            base >= 0 && text[base] in NUMERIC_CONTEXT_CHARACTERS
        }

    var after = text.nextNonWhitespaceIndex(range.last + 1)
    if (after < text.length && text[after] == 'E') {
        after = text.nextNonWhitespaceIndex(after + 1)
        if (after < text.length && text[after] in EXPONENT_SIGN_CHARACTERS) {
            after = text.nextNonWhitespaceIndex(after + 1)
        }
        if (after < text.length && text[after] in NUMERIC_CONTEXT_CHARACTERS) return true
    }
    return precededByExponent
}

private fun touchesDetachedNumericSyntax(text: String, range: IntRange): Boolean {
    val previousIndex = text.previousNonWhitespaceIndex(range.first - 1)
    val nextIndex = text.nextNonWhitespaceIndex(range.last + 1)
    val previous = text.getOrNull(previousIndex)
    val next = text.getOrNull(nextIndex)
    val slashIsCurrencyMarker = previous == '/' &&
        isPenCurrencySlash(text, previousIndex)
    val colonFollowsNumericText = previous == ':' &&
        text.previousNonWhitespaceIndex(previousIndex - 1).let { index ->
            text.getOrNull(index) in NUMERIC_CONTEXT_CHARACTERS
        }
    val invalidPrevious = when {
        previous == '/' -> !slashIsCurrencyMarker
        previous == ':' -> colonFollowsNumericText
        else -> previous in DETACHED_NUMERIC_SYNTAX
    }
    return invalidPrevious || next in DETACHED_NUMERIC_SYNTAX
}

private fun isPenCurrencySlash(text: String, slashIndex: Int): Boolean {
    if (slashIndex !in text.indices || text[slashIndex] != '/') return false
    val markerStart = text.previousNonWhitespaceIndex(slashIndex - 1)
    if (text.getOrNull(markerStart) != 'S') return false
    val beforeMarker = text.getOrNull(markerStart - 1)
    val afterSlash = text.getOrNull(slashIndex + 1)
    return beforeMarker !in 'A'..'Z' && afterSlash !in 'A'..'Z'
}

private fun String.previousNonWhitespaceIndex(fromIndex: Int): Int {
    var index = minOf(fromIndex, lastIndex)
    while (index >= 0 && this[index].isWhitespace()) index--
    return index
}

private fun String.nextNonWhitespaceIndex(fromIndex: Int): Int {
    var index = maxOf(fromIndex, 0)
    while (index < length && this[index].isWhitespace()) index++
    return index
}

private fun parseDateToken(token: String): ParsedDateToken? {
    val corrected = correctNumericConfusables(token)
    val separators = corrected.corrected.filter { it == '/' || it == '-' || it == '.' }
    if (separators.length != 2 || separators[0] != separators[1]) return null
    val parts = corrected.corrected.split(separators[0])
    if (parts.size != 3 || parts.any { part -> part.any { !it.isDigit() } }) return null
    val correction = corrected.correction

    if (parts[0].length == 4) {
        val date = strictDate(parts[0], parts[1], parts[2]) ?: return null
        return ParsedDateToken(date, listOf(date), emptyList(), correction)
    }
    if (parts[2].length == 2) {
        val shortYear = parts[2].toIntOrNull() ?: return null
        val dmyPossible = hasPossibleYearEndingIn(
            shortYear = shortYear,
            month = parts[1],
            day = parts[0],
        )
        val mdyPossible = hasPossibleYearEndingIn(
            shortYear = shortYear,
            month = parts[0],
            day = parts[1],
        )
        if (!dmyPossible && !mdyPossible) return null
        return ParsedDateToken(
            certainDate = null,
            dates = emptyList(),
            warnings = buildList {
                add(CandidateWarning.TWO_DIGIT_YEAR)
                if (dmyPossible && mdyPossible && parts[0] != parts[1]) {
                    add(CandidateWarning.AMBIGUOUS_DATE_ORDER)
                } else if (!dmyPossible && mdyPossible) {
                    add(CandidateWarning.NON_PERUVIAN_DATE_ORDER)
                }
            },
            correction = correction,
        )
    }
    if (parts[2].length != 4) return null

    val dmy = strictDate(parts[2], parts[1], parts[0])
    val mdy = strictDate(parts[2], parts[0], parts[1])
    return when {
        dmy == null && mdy == null -> null
        dmy != null && mdy == null -> ParsedDateToken(dmy, listOf(dmy), emptyList(), correction)
        dmy == null && mdy != null -> ParsedDateToken(
            certainDate = null,
            dates = listOf(mdy),
            warnings = listOf(CandidateWarning.NON_PERUVIAN_DATE_ORDER),
            correction = correction,
        )
        dmy == mdy -> ParsedDateToken(dmy, listOf(requireNotNull(dmy)), emptyList(), correction)
        else -> ParsedDateToken(
            certainDate = null,
            dates = listOf(requireNotNull(dmy), requireNotNull(mdy)),
            warnings = listOf(CandidateWarning.AMBIGUOUS_DATE_ORDER),
            correction = correction,
        )
    }
}

private fun hasPossibleYearEndingIn(shortYear: Int, month: String, day: String): Boolean {
    var year = if (shortYear == 0) 100 else shortYear
    while (year <= 9_999) {
        if (strictDate(year.toString(), month, day) != null) return true
        year += 100
    }
    return false
}

private fun strictDate(year: String, month: String, day: String): LocalDate? {
    val yearValue = year.toIntOrNull() ?: return null
    val monthValue = month.toIntOrNull() ?: return null
    val dayValue = day.toIntOrNull() ?: return null
    if (yearValue !in 1..9_999) return null
    return try {
        LocalDate.of(yearValue, monthValue, dayValue)
    } catch (_: DateTimeException) {
        null
    }
}

private fun recognizeUnits(rawToken: String): List<RecognizedUnit> {
    val token = rawToken.removeSuffix(".")
    UNIT_ALIASES[token]?.let { canonical ->
        val warnings = if (token == canonical.name) {
            emptyList()
        } else {
            listOf(CandidateWarning.UNIT_ALIAS_NORMALIZED)
        }
        return listOf(RecognizedUnit(canonical, warnings, correction = null))
    }

    return enumerateUnitCorrections(token)
        .mapNotNull { corrected ->
            val canonical = UNIT_ALIASES[corrected] ?: return@mapNotNull null
            RecognizedUnit(
                value = canonical,
                warnings = buildList {
                    add(CandidateWarning.OCR_CORRECTION_APPLIED)
                    if (corrected != canonical.name) {
                        add(CandidateWarning.UNIT_ALIAS_NORMALIZED)
                    }
                },
                correction = AppliedOcrCorrection(
                    originalFragment = token,
                    correctedFragment = corrected,
                    reason = OcrCorrectionReason.UNIT_CONFUSABLE_IN_UNIT_CONTEXT,
                ),
            )
        }
        .distinct()
}

private fun enumerateUnitCorrections(token: String): List<String> =
    token.fold(listOf("")) { prefixes, character ->
        val replacements = when (character) {
            '1', '|' -> listOf('I', 'L')
            '6' -> listOf('G')
            '0' -> listOf('O')
            else -> listOf(character)
        }
        prefixes.flatMap { prefix -> replacements.map { replacement -> prefix + replacement } }
    }.filterNot { corrected -> corrected == token }

private fun BigDecimal.toMoney(currency: CurrencyCode): Money? {
    if (signum() < 0) return null
    return runCatching { Money.fromMajor(this, currency) }.getOrNull()
}

private fun BigDecimal.hasExactScaleFor(currency: CurrencyCode): Boolean =
    try {
        setScale(currency.defaultFractionDigits, RoundingMode.UNNECESSARY)
        true
    } catch (_: ArithmeticException) {
        false
    }

private fun BigDecimal.toUnitCost(currency: CurrencyCode): UnitCost? {
    if (signum() < 0) return null
    return runCatching { UnitCost.of(this, currency) }.getOrNull()
}

private fun BigDecimal.toQuantity(): Quantity? =
    takeIf { signum() > 0 }?.let { runCatching { Quantity.of(it) }.getOrNull() }

private fun CandidateEvidence.withCorrections(
    corrections: List<AppliedOcrCorrection>,
): CandidateEvidence = copy(corrections = corrections.distinct())

private fun <T : Any> candidate(
    source: CandidateSource,
    evidence: CandidateEvidence,
    value: T?,
    warnings: List<CandidateWarning>,
    alternatives: List<T>,
): Candidate<T> = Candidate(
    value = value,
    evidence = evidence,
    boundingBox = source.boundingBox,
    confidencePermille = source.confidencePermille,
    warnings = warnings.distinct(),
    alternatives = alternatives.distinct(),
)

private fun groupCurrencyMarkers(
    text: String,
    markers: List<CurrencyMarker>,
): List<CurrencyMarkerGroup> {
    val groups = mutableListOf<CurrencyMarkerGroup>()
    markers.forEach { marker ->
        val previous = groups.lastOrNull()
        if (previous != null && isAdjacentValue(text, previous.range, marker.range)) {
            groups[groups.lastIndex] = previous.copy(
                range = previous.range.first..marker.range.last,
                currencies = (previous.currencies + marker.currency).distinct(),
            )
        } else {
            groups += CurrencyMarkerGroup(marker.range, listOf(marker.currency))
        }
    }
    return groups
}

private fun isSignedOrParenthesizedAmount(
    text: String,
    marker: IntRange,
    value: IntRange,
): Boolean {
    val expressionStart = minOf(marker.first, value.first)
    val expressionEnd = maxOf(marker.last, value.last)
    val previous = text.getOrNull(text.previousNonWhitespaceIndex(expressionStart - 1))
    val next = text.getOrNull(text.nextNonWhitespaceIndex(expressionEnd + 1))
    return previous in MONETARY_SIGN_CHARACTERS ||
        next in MONETARY_SIGN_CHARACTERS ||
        previous == '(' ||
        next == ')'
}

private fun touchesUnitCompositionSyntax(text: String, range: IntRange): Boolean {
    val previous = text.getOrNull(text.previousNonWhitespaceIndex(range.first - 1))
    val next = text.getOrNull(text.nextNonWhitespaceIndex(range.last + 1))
    return previous in UNIT_COMPOSITION_CHARACTERS || next in UNIT_COMPOSITION_CHARACTERS
}

private fun isAdjacentValue(text: String, marker: IntRange, value: IntRange): Boolean {
    val between = when {
        value.first > marker.last -> (marker.last + 1) until value.first
        marker.first > value.last -> (value.last + 1) until marker.first
        else -> return false
    }
    return between.all { index ->
        val character = text[index]
        character.isWhitespace() || character == ':' || character == '='
    }
}

private val NUMBER_TOKEN = Regex(
    """(?<![\p{L}\p{N}.,+/%_’'‒−–—\-])[+-]?[0-9OIL|]+(?:[.,][0-9OIL|]+| +[0-9OIL|]+)*(?![\p{L}\p{N}.,+/%_’'‒−–—\-])""",
)
private val DATE_TOKEN = Regex(
    """(?<![\p{L}\p{N}.,+/%_’'|‒−–—\-])[0-9OIL|]{1,4}[./-][0-9OIL|]{1,2}[./-][0-9OIL|]{2,4}(?![\p{L}\p{N}.,:+/%_’'|‒−–—\-])""",
)
private val PEN_MARKER = Regex("""(?<![A-Z])(?:PEN|S\s*/)(?![A-Z])""")
private val ISO_CURRENCY = Regex("""(?<![A-Z])[A-Z]{3}(?![A-Z])""")
private val UNIT_TOKEN = Regex("""(?<![A-Z0-9|])[A-Z0-9|]{1,4}\.?(?![A-Z0-9|])""")
private val US_NUMBER = Regex("""(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\.[0-9]+)?""")
private val EU_NUMBER = Regex("""(?:[0-9]+|[0-9]{1,3}(?:\.[0-9]{3})+)(?:,[0-9]+)?""")
private val DETACHED_NUMERIC_SYNTAX =
    setOf('+', '-', '‒', '−', '–', '—', '.', ',', '/', '%', ':', '_', '\'', '’')
private val MONETARY_SIGN_CHARACTERS = setOf('+', '-', '‒', '−', '–', '—')
private val EXPONENT_SIGN_CHARACTERS = MONETARY_SIGN_CHARACTERS
private val UNIT_COMPOSITION_CHARACTERS = setOf('/', '\\', '-', '‐', '‑', '‒', '–', '—', '.', '*', '×', '^')
private val NUMERIC_CONTEXT_CHARACTERS = setOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'O', 'I', 'L', '|')
private const val WEAK_UNIT_ALIAS = "UN"
private const val MAX_PARSER_TEXT_CHARACTERS = 64 * 1024

private fun CandidateSource.isWithinParserBudget(): Boolean =
    rawText.length <= MAX_PARSER_TEXT_CHARACTERS

private val UNIT_ALIASES = mapOf(
    "NIU" to InvoiceUnitCode.NIU,
    "UND" to InvoiceUnitCode.NIU,
    "UN" to InvoiceUnitCode.NIU,
    "KGM" to InvoiceUnitCode.KGM,
    "KG" to InvoiceUnitCode.KGM,
    "LTR" to InvoiceUnitCode.LTR,
)
