package com.facturastock.app.domain.normalization

import com.facturastock.app.domain.config.TaxRate
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.InvoiceOcrSnapshot
import com.facturastock.app.domain.model.InvoiceTextBlock
import com.facturastock.app.domain.model.InvoiceTextBoundingBox
import com.facturastock.app.domain.model.InvoiceTextElement
import com.facturastock.app.domain.model.InvoiceTextGeometry
import com.facturastock.app.domain.model.InvoiceTextLine
import com.facturastock.app.domain.model.InvoiceTextPage
import com.facturastock.app.domain.model.InvoiceTextPoint
import com.facturastock.app.domain.model.Money
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import java.util.Locale

/** Parser puro del bloque financiero impreso. Nunca escribe ni corrige el snapshot recibido. */
class InvoiceTotalsParser {
    fun parse(
        snapshot: InvoiceOcrSnapshot,
        context: InvoiceTotalsParseContext,
    ): InvoiceTotalsParseResult {
        require(context.draftId == snapshot.draftId) {
            "El contexto financiero pertenece a otro borrador"
        }
        require(context.runId == snapshot.runId) {
            "El contexto financiero pertenece a otra ejecución OCR"
        }
        val referenceIgvRate = context.referenceIgvRate
        val currencyContext = context.currency
        val taxRoundingMode = context.taxRoundingMode
        val rows = snapshot.document.pages.flatMap(InvoiceTextPage::toTotalsRows)
        val direct = rows.mapNotNull { row -> row.parseDirect(currencyContext) }
        val paired = rows.parseLabelsAboveValues(currencyContext)
        val parsedCandidates = (direct + paired)
            .distinctBy { parsed -> parsed.kind to parsed.row.pathKey }
            .sortedWith(compareBy({ it.row.page.pageIndex }, { it.row.centerY }, { it.row.pathKey }))
        val signals = rows.mapNotNull(TotalsRow::detectLabelSignal)
        val eligibleClusters = buildSummaryClusters(parsedCandidates, signals)
            .filter(SummaryCluster::isEligible)
        val selectedCluster = eligibleClusters.maxWithOrNull(SummaryCluster.ORDER)
        // Los clústeres fuertes anteriores se conservan como alternativas impresas. Los débiles
        // solo sobreviven si son el clúster final, para no convertir cabeceras de tabla en datos.
        val activeClusters = eligibleClusters.filter { cluster ->
            !cluster.hasWeakSummaryContext || cluster === selectedCluster
        }
        val weakSummaryPaths = activeClusters.filter(SummaryCluster::hasWeakSummaryContext)
            .flatMap(SummaryCluster::rows)
            .map(TotalsRow::pathKey)
            .toSet()
        val parsedRows = activeClusters.flatMap(SummaryCluster::parsedRows)
        val eligibleSignals = activeClusters.flatMap(SummaryCluster::signals)
        val selectedParsedRows = selectedCluster?.parsedRows.orEmpty()
        val selectedSignals = selectedCluster?.signals.orEmpty()
        val allIgvLabels = selectedSignals.map(TotalLabelSignal::label)
            .filter { label -> label.kind == TotalKind.IGV }
        val hasIgvLabel = selectedParsedRows.any { parsed ->
            parsed.kind == TotalKind.IGV && parsed.label.correction == null
        } || allIgvLabels.any { label -> label.correction == null }

        val amountOccurrences = parsedRows.map { parsed ->
            parsed.toMoneyOccurrence(parsed.row.pathKey in weakSummaryPaths)
        }
        val rateOccurrences = buildRateOccurrences(parsedRows, eligibleSignals, weakSummaryPaths)
        val roundingOccurrences = parsedRows.filter { parsed -> parsed.kind == TotalKind.ROUNDING }
            .map { parsed ->
                parsed.toRoundingOccurrence(parsed.row.pathKey in weakSummaryPaths)
            }

        // Cálculo y selección usan exclusivamente el clúster inferior. El modelo público
        // conserva las ocurrencias de otros bloques fuertes como alternativas explicables.
        val selectedAmountOccurrences = selectedParsedRows.map { parsed ->
            parsed.toMoneyOccurrence(parsed.row.pathKey in weakSummaryPaths)
        }
        val selectedRateOccurrences = buildRateOccurrences(
            parsedRows = selectedParsedRows,
            signals = selectedSignals,
            weakSummaryPaths = weakSummaryPaths,
        )
        val selectedRoundingOccurrences = selectedParsedRows
            .filter { parsed -> parsed.kind == TotalKind.ROUNDING }
            .map { parsed ->
                parsed.toRoundingOccurrence(parsed.row.pathKey in weakSummaryPaths)
            }

        val allCharges = parsedRows.toOtherCharges(weakSummaryPaths)
        val selectedCharges = selectedParsedRows.toOtherCharges(weakSummaryPaths)
        val selectedChargeSignature = selectedCharges.chargeSignature()
        val hasDifferentChargeGroups = activeClusters.asSequence()
            .filter { cluster -> cluster !== selectedCluster }
            .map { cluster -> cluster.parsedRows.toOtherCharges(weakSummaryPaths).chargeSignature() }
            .any { signature -> signature != selectedChargeSignature }

        val read = ReadInvoiceTotals(
            taxableOperations = amountOccurrences.field(TotalKind.TAXABLE, selectedAmountOccurrences),
            exemptOperations = amountOccurrences.field(TotalKind.EXEMPT, selectedAmountOccurrences),
            unaffectedOperations = amountOccurrences.field(TotalKind.UNAFFECTED, selectedAmountOccurrences),
            discounts = amountOccurrences.field(TotalKind.DISCOUNT, selectedAmountOccurrences),
            subtotal = amountOccurrences.field(TotalKind.SUBTOTAL, selectedAmountOccurrences),
            igv = amountOccurrences.field(TotalKind.IGV, selectedAmountOccurrences),
            printedIgvRate = rateOccurrences.toField(selectedRateOccurrences),
            otherCharges = selectedCharges,
            rounding = roundingOccurrences.toField(selectedRoundingOccurrences),
            total = amountOccurrences.field(TotalKind.TOTAL, selectedAmountOccurrences),
        )

        val calculationRead = ReadInvoiceTotals(
            taxableOperations = selectedAmountOccurrences.field(TotalKind.TAXABLE),
            exemptOperations = selectedAmountOccurrences.field(TotalKind.EXEMPT),
            unaffectedOperations = selectedAmountOccurrences.field(TotalKind.UNAFFECTED),
            discounts = selectedAmountOccurrences.field(TotalKind.DISCOUNT),
            subtotal = selectedAmountOccurrences.field(TotalKind.SUBTOTAL),
            igv = selectedAmountOccurrences.field(TotalKind.IGV),
            printedIgvRate = selectedRateOccurrences.toField(),
            otherCharges = selectedCharges,
            rounding = selectedParsedRows.filter { parsed -> parsed.kind == TotalKind.ROUNDING }
                .map { parsed ->
                    parsed.toRoundingOccurrence(parsed.row.pathKey in weakSummaryPaths)
                }
                .toField(),
            total = selectedAmountOccurrences.field(TotalKind.TOTAL),
        )

        val warnings = mutableListOf<InvoiceTotalsWarning>()
        val unresolvedSignals = eligibleSignals.filterNot { signal ->
            parsedRows.any { parsed -> parsed.represents(signal) }
        }
        val selectedUnresolvedSignals = selectedSignals.filterNot { signal ->
            selectedParsedRows.any { parsed -> parsed.represents(signal) }
        }
        if (unresolvedSignals.isNotEmpty()) {
            warnings += InvoiceTotalsWarning.UNRESOLVED_PRINTED_FIELD
        }
        if (
            currencyContext == null && unresolvedSignals.any { signal ->
                signal.amountText != null && signal.amountText.explicitCurrency() == null
            }
        ) {
            warnings += InvoiceTotalsWarning.MISSING_CURRENCY_FOR_PRINTED_AMOUNTS
        }
        if (parsedRows.any(ParsedTotalRow::usedCurrencyContext)) {
            warnings += InvoiceTotalsWarning.CURRENCY_FROM_CONTEXT
        }
        val currencies = read.allMoneyCandidates().flatMap { candidate ->
            (listOfNotNull(candidate.value) + candidate.alternatives).map(Money::currency)
        }.toMutableSet().apply {
            allCharges.flatMap { charge ->
                listOfNotNull(charge.amount.candidate.value) + charge.amount.candidate.alternatives
            }.mapTo(this, Money::currency)
        }
        if (currencyContext != null) currencies += currencyContext
        val currency = currencies.singleOrNull()
        if (currencies.size > 1) warnings += InvoiceTotalsWarning.CURRENCY_CONFLICT
        val hasMultiplePrintedValues = read.hasDifferentPrintedAlternatives() ||
            hasDifferentChargeGroups
        if (hasMultiplePrintedValues) {
            warnings += InvoiceTotalsWarning.MULTIPLE_PRINTED_VALUES
        }
        if (read.hasPriorOnlyAlternatives() || allCharges.size > selectedCharges.size) {
            warnings += InvoiceTotalsWarning.PRIOR_SUMMARY_ALTERNATIVES
        }
        if (read.hasOcrCorrections()) warnings += InvoiceTotalsWarning.OCR_CORRECTION_APPLIED

        val calculated = calculate(
            read = calculationRead,
            currency = currency,
            hasIgvLabel = hasIgvLabel,
            unresolvedKinds = selectedUnresolvedSignals.map { signal -> signal.label.kind }.toSet(),
            referenceIgvRate = referenceIgvRate,
            taxRoundingMode = taxRoundingMode,
            warnings = warnings,
        )
        val componentDifferences = compareCalculatedComponents(calculationRead, calculated, warnings)
        val reconciliation = if (hasMultiplePrintedValues) {
            null
        } else {
            reconcile(
                read = calculationRead,
                calculated = calculated,
                componentDifferences = componentDifferences,
                warnings = warnings,
            )
        }

        return InvoiceTotalsParseResult(
            draftId = snapshot.draftId,
            runId = snapshot.runId,
            currency = currency,
            referenceIgvRate = referenceIgvRate,
            taxRoundingMode = taxRoundingMode,
            read = read,
            calculated = calculated,
            reconciliation = reconciliation,
            warnings = warnings.distinct(),
        )
    }
}

private enum class TotalKind {
    TAXABLE,
    EXEMPT,
    UNAFFECTED,
    DISCOUNT,
    SUBTOTAL,
    IGV,
    OTHER_CHARGE,
    ROUNDING,
    TOTAL,
}

private data class LabelDefinition(
    val kind: TotalKind,
    val aliases: Set<String>,
    val chargeKind: InvoiceOtherChargeKind? = null,
    val roundingReason: InvoiceRoundingReason? = null,
    val strength: Int = 0,
)

private data class RecognizedTotalLabel(
    val kind: TotalKind,
    val canonicalLabel: String,
    val sourceLabel: String,
    val chargeKind: InvoiceOtherChargeKind?,
    val roundingReason: InvoiceRoundingReason?,
    val strength: Int,
    val correction: AppliedOcrCorrection?,
)

private data class TotalsAtom(
    val page: InvoiceTextPage,
    val rawText: String,
    val box: InvoiceTextBoundingBox?,
    val evidence: CandidateEvidence,
    val confidencePermille: Int?,
    val blockPosition: Int?,
    val linePosition: Int?,
    val elementPosition: Int?,
) {
    val pathKey: String = listOf(
        page.pageIndex,
        blockPosition ?: Int.MAX_VALUE,
        linePosition ?: Int.MAX_VALUE,
        elementPosition ?: Int.MAX_VALUE,
    ).joinToString(":")
}

private data class TotalsRow(
    val page: InvoiceTextPage,
    val atoms: List<TotalsAtom>,
) {
    val orderedAtoms: List<TotalsAtom> = atoms.sortedWith(
        compareBy<TotalsAtom>({ it.box?.leftPx ?: Int.MAX_VALUE }, { it.pathKey }),
    )
    val rawText: String = orderedAtoms.joinToString(" ", transform = TotalsAtom::rawText)
    val box: InvoiceTextBoundingBox? = orderedAtoms.mapNotNull(TotalsAtom::box).unionBoxes()
    val centerY: Int = box?.let { bounds -> bounds.topPx + (bounds.bottomPx - bounds.topPx) / 2 }
        ?: orderedAtoms.minOfOrNull { atom -> atom.linePosition ?: Int.MAX_VALUE }
        ?: Int.MAX_VALUE
    val pathKey: String = orderedAtoms.joinToString("|") { atom -> atom.pathKey }

    fun source(raw: String = rawText): CandidateSource {
        val single = orderedAtoms.singleOrNull()
        return CandidateSource(
            rawText = raw,
            boundingBox = box,
            confidencePermille = orderedAtoms.mapNotNull(TotalsAtom::confidencePermille).minOrNull(),
            sourceImageId = page.sourceImageId,
            pageIndex = page.pageIndex,
            cornerPoints = single?.evidence?.cornerPoints.orEmpty(),
            blockPosition = single?.blockPosition,
            linePosition = single?.linePosition,
            elementPosition = single?.elementPosition,
            clockwiseAngleTenths = single?.evidence?.clockwiseAngleTenths,
        )
    }

    fun evidence(): List<CandidateEvidence> = orderedAtoms.map(TotalsAtom::evidence).distinct()

    fun evidence(labelCorrection: AppliedOcrCorrection?): List<CandidateEvidence> {
        val values = evidence()
        if (labelCorrection == null || values.isEmpty()) return values
        return values.mapIndexed { index, item ->
            if (index == 0) {
                item.copy(corrections = (item.corrections + labelCorrection).distinct())
            } else {
                item
            }
        }
    }

    fun rateEvidence(labelCorrection: AppliedOcrCorrection?): List<CandidateEvidence> =
        evidence(labelCorrection).map { item ->
            val numericCorrections = PRINTED_RATE.findAll(item.comparisonText).mapNotNull { match ->
                val original = match.groupValues[1]
                val corrected = original.correctNumericConfusables()
                corrected.takeIf { value -> value != original }?.let { value ->
                    AppliedOcrCorrection(
                        originalFragment = original,
                        correctedFragment = value,
                        reason = OcrCorrectionReason.NUMERIC_CONFUSABLE_IN_NUMERIC_CONTEXT,
                    )
                }
            }.toList()
            item.copy(corrections = (item.corrections + numericCorrections).distinct())
        }

    fun amountSource(): CandidateSource {
        val valueAtom = orderedAtoms.lastOrNull { atom ->
            val comparison = atom.evidence.comparisonText
            '%' !in comparison && MONEY_DIGIT.containsMatchIn(comparison)
        } ?: return source()
        return CandidateSource(
            rawText = valueAtom.rawText,
            boundingBox = valueAtom.box,
            confidencePermille = valueAtom.confidencePermille,
            sourceImageId = valueAtom.page.sourceImageId,
            pageIndex = valueAtom.page.pageIndex,
            cornerPoints = valueAtom.evidence.cornerPoints,
            blockPosition = valueAtom.blockPosition,
            linePosition = valueAtom.linePosition,
            elementPosition = valueAtom.elementPosition,
            clockwiseAngleTenths = valueAtom.evidence.clockwiseAngleTenths,
        )
    }

    fun amountSignComesFromRateLabel(): Boolean {
        val valueIndex = orderedAtoms.indexOfLast { atom ->
            val comparison = atom.evidence.comparisonText
            '%' !in comparison && MONEY_DIGIT.containsMatchIn(comparison)
        }
        if (valueIndex <= 0) return false
        return orderedAtoms.subList(0, valueIndex).asReversed().firstOrNull { atom ->
            atom.evidence.comparisonText.any { character -> character in ACCOUNTING_SIGNS }
        }?.evidence?.comparisonText?.contains('%') == true
    }


    fun rateSource(): CandidateSource {
        val rateAtom = orderedAtoms.firstOrNull { atom -> '%' in atom.evidence.comparisonText }
            ?: return source()
        return CandidateSource(
            rawText = rateAtom.rawText,
            boundingBox = rateAtom.box,
            confidencePermille = rateAtom.confidencePermille,
            sourceImageId = rateAtom.page.sourceImageId,
            pageIndex = rateAtom.page.pageIndex,
            cornerPoints = rateAtom.evidence.cornerPoints,
            blockPosition = rateAtom.blockPosition,
            linePosition = rateAtom.linePosition,
            elementPosition = rateAtom.elementPosition,
            clockwiseAngleTenths = rateAtom.evidence.clockwiseAngleTenths,
        )
    }

    fun rateComparisonText(): String = orderedAtoms.asSequence()
        .map(TotalsAtom::evidence)
        .map(CandidateEvidence::comparisonText)
        .filter { text -> '%' in text }
        .joinToString(" ")
}

private data class ParsedSignedMoney(
    val candidate: Candidate<Money>,
    val printedSign: InvoicePrintedSign,
    val usedCurrencyContext: Boolean,
)

private data class PrintedCurrencyMarkers(
    val currencies: List<CurrencyCode>,
    val markerCount: Int,
)

private data class ParsedTotalRow(
    val kind: TotalKind,
    val label: RecognizedTotalLabel,
    val row: TotalsRow,
    val amount: ParsedSignedMoney,
    val rate: Candidate<TaxRate>?,
    val relation: InvoiceTotalReason,
) {
    val usedCurrencyContext: Boolean
        get() = amount.usedCurrencyContext
}

private data class TotalLabelSignal(
    val row: TotalsRow,
    val label: RecognizedTotalLabel,
    val amountText: String?,
)

private data class RecognizedLabelPrefix(
    val label: RecognizedTotalLabel,
    val remainder: String,
)

private data class MoneyOccurrence(
    val kind: TotalKind,
    val occurrence: InvoiceTotalOccurrence<Money>,
    val strength: Int,
    val centerY: Int,
)

private data class SummarySignalKey(
    val kind: TotalKind,
    val chargeKind: InvoiceOtherChargeKind?,
)

private data class SummaryCluster(
    val rows: List<TotalsRow>,
    val parsedRows: List<ParsedTotalRow>,
    val signals: List<TotalLabelSignal>,
) {
    private val labels: List<Pair<TotalsRow, RecognizedTotalLabel>> =
        (parsedRows.map { parsed -> parsed.row to parsed.label } +
            signals.map { signal -> signal.row to signal.label })
            .distinctBy { (row, label) -> row.pathKey to label.summarySignalKey() }

    val pageIndex: Int = rows.first().page.pageIndex
    val bottomY: Int = rows.maxOf(TotalsRow::centerY)
    val hasStrongTotal: Boolean = labels.any { (_, label) -> label.isStrongTotal() }
    private val height: Int = rows.first().page.heightPx
    private val keys: Set<SummarySignalKey> = labels.map { (_, label) -> label.summarySignalKey() }.toSet()
    private val hasAnchor: Boolean = labels.any { (_, label) -> label.isSummaryAnchor() }
    private val anyStrongTotalIsLowEnough: Boolean = labels.any { (row, label) ->
        label.isStrongTotal() && row.box != null &&
            row.centerY * 100 >= height * MIN_STRONG_TOTAL_VERTICAL_PERCENT
    }
    private val robustStrongTotalIsLowEnough: Boolean = parsedRows.any { parsed ->
        parsed.label.isStrongTotal() && parsed.relation != InvoiceTotalReason.DIRECTLY_BELOW_LABEL &&
            parsed.row.box != null &&
            parsed.row.centerY * 100 >= height * MIN_STRONG_TOTAL_VERTICAL_PERCENT
    }
    private val hasRobustSummaryContext: Boolean = robustStrongTotalIsLowEnough ||
        hasAnchor && keys.size >= MIN_SUMMARY_CLUSTER_KINDS
    val hasWeakSummaryContext: Boolean
        get() = isEligible() && !hasRobustSummaryContext

    fun isEligible(): Boolean {
        if (anyStrongTotalIsLowEnough) return true
        val isolatedTotalIsLowEnough = labels.any { (row, label) ->
            label.kind == TotalKind.TOTAL && !label.isStrongTotal() && row.box != null &&
                row.centerY * 100 >= height * MIN_ISOLATED_TOTAL_VERTICAL_PERCENT
        }
        if (isolatedTotalIsLowEnough) return true
        val clusterIsLow = bottomY * 100 >= height * MIN_SMALL_CLUSTER_VERTICAL_PERCENT
        return hasAnchor && (keys.size >= MIN_SUMMARY_CLUSTER_KINDS ||
            keys.size >= MIN_SMALL_SUMMARY_CLUSTER_KINDS && clusterIsLow)
    }

    companion object {
        val ORDER: Comparator<SummaryCluster> = compareBy(
            SummaryCluster::pageIndex,
            SummaryCluster::hasRobustSummaryContext,
            SummaryCluster::bottomY,
            { cluster -> cluster.keys.size },
            SummaryCluster::hasStrongTotal,
        )
    }
}

private fun buildSummaryClusters(
    parsed: List<ParsedTotalRow>,
    signals: List<TotalLabelSignal>,
): List<SummaryCluster> {
    val sourceRows = (parsed.map(ParsedTotalRow::row) + signals.map(TotalLabelSignal::row))
        .distinctBy(TotalsRow::pathKey)
        .groupBy { row -> row.page.pageIndex }
    return sourceRows.values.flatMap { pageRows ->
        val ordered = pageRows.sortedWith(compareBy(TotalsRow::centerY, TotalsRow::pathKey))
        val rowClusters = mutableListOf<MutableList<TotalsRow>>()
        ordered.forEach { row ->
            val current = rowClusters.lastOrNull()
            if (current == null || !current.last().isNearSummaryRow(row)) {
                rowClusters += mutableListOf(row)
            } else {
                current += row
            }
        }
        rowClusters.map { rows ->
            val paths = rows.map(TotalsRow::pathKey).toSet()
            SummaryCluster(
                rows = rows,
                parsedRows = parsed.filter { item -> item.row.pathKey in paths },
                signals = signals.filter { item -> item.row.pathKey in paths },
            )
        }
    }
}

private fun RecognizedTotalLabel.isSummaryAnchor(): Boolean =
    kind in SUMMARY_ANCHOR_KINDS || kind == TotalKind.TOTAL && strength >= STRONG_TOTAL_STRENGTH

private fun RecognizedTotalLabel.isStrongTotal(): Boolean =
    kind == TotalKind.TOTAL && strength >= STRONG_TOTAL_STRENGTH

private fun RecognizedTotalLabel.summarySignalKey(): SummarySignalKey =
    SummarySignalKey(kind, chargeKind)

private fun ParsedTotalRow.represents(signal: TotalLabelSignal): Boolean =
    kind == signal.label.kind && signal.row.atoms.any { signalAtom ->
        row.atoms.any { parsedAtom -> parsedAtom.pathKey == signalAtom.pathKey }
    }

private fun TotalsRow.isNearSummaryRow(other: TotalsRow): Boolean {
    if (page.pageIndex != other.page.pageIndex) return false
    val maxDistance = if (box != null && other.box != null) {
        page.heightPx / SUMMARY_CLUSTER_DIVISOR
    } else {
        MAX_NON_GEOMETRIC_ROW_GAP
    }
    return kotlin.math.abs(centerY - other.centerY) <= maxDistance
}

private fun TotalsRow.isGeometricallyLowerSummary(): Boolean =
    box != null && centerY * 100 >= page.heightPx * MIN_SUMMARY_VERTICAL_PERCENT

private fun InvoiceTextPage.toTotalsRows(): List<TotalsRow> {
    val atoms = buildList {
        blocks.forEach { block ->
            if (block.lines.isEmpty()) {
                add(block.toAtom(this@toTotalsRows))
            } else {
                block.lines.forEach { line ->
                    val elementAtoms = line.elements.mapNotNull { element ->
                        element.toAtom(this@toTotalsRows, block, line)
                    }
                    if (elementAtoms.size >= MIN_ELEMENT_ATOMS) {
                        addAll(elementAtoms)
                    } else {
                        add(line.toAtom(this@toTotalsRows, block))
                    }
                }
            }
        }
        if (isEmpty() && text.isNotBlank()) {
            text.lineSequence().filter(String::isNotBlank).forEachIndexed { index, raw ->
                val source = CandidateSource(
                    rawText = raw,
                    sourceImageId = sourceImageId,
                    pageIndex = pageIndex,
                    linePosition = index,
                )
                add(
                    TotalsAtom(
                        page = this@toTotalsRows,
                        rawText = raw,
                        box = null,
                        evidence = PeruvianTextNormalizer.normalize(source),
                        confidencePermille = null,
                        blockPosition = null,
                        linePosition = index,
                        elementPosition = null,
                    ),
                )
            }
        }
    }
    if (atoms.isEmpty()) return emptyList()

    val geometric = atoms.filter { atom -> atom.box != null }.sortedWith(
        compareBy({ atom -> atom.box?.topPx }, { atom -> atom.box?.leftPx }, TotalsAtom::pathKey),
    )
    val rows = mutableListOf<MutableList<TotalsAtom>>()
    geometric.forEach { atom ->
        val target = rows.lastOrNull { row -> row.belongsToSameVisualRow(atom) }
        if (target == null) rows += mutableListOf(atom) else target += atom
    }
    atoms.filter { atom -> atom.box == null }.forEach { atom -> rows += mutableListOf(atom) }
    return rows.map { row -> TotalsRow(this, row) }
        .sortedWith(compareBy({ row -> row.centerY }, TotalsRow::pathKey))
}

private fun MutableList<TotalsAtom>.belongsToSameVisualRow(atom: TotalsAtom): Boolean {
    val atomBox = atom.box ?: return false
    val rowBox = mapNotNull(TotalsAtom::box).unionBoxes() ?: return false
    val overlap = minOf(rowBox.bottomPx, atomBox.bottomPx) - maxOf(rowBox.topPx, atomBox.topPx)
    val smallerHeight = minOf(rowBox.height, atomBox.height)
    if (overlap > 0 && overlap * 100 >= smallerHeight * MIN_VERTICAL_OVERLAP_PERCENT) return true
    val rowCenter = rowBox.topPx + rowBox.height / 2
    val atomCenter = atomBox.topPx + atomBox.height / 2
    return kotlin.math.abs(rowCenter - atomCenter) <= maxOf(MIN_ROW_CENTER_DISTANCE_PX, smallerHeight / 2)
}

private fun InvoiceTextBlock.toAtom(page: InvoiceTextPage): TotalsAtom {
    val box = geometry.effectiveBox()
    val source = CandidateSource.from(page, this).copy(boundingBox = box)
    return TotalsAtom(
        page = page,
        rawText = text,
        box = box,
        evidence = PeruvianTextNormalizer.normalize(source),
        confidencePermille = null,
        blockPosition = position,
        linePosition = null,
        elementPosition = null,
    )
}

private fun InvoiceTextLine.toAtom(page: InvoiceTextPage, block: InvoiceTextBlock): TotalsAtom {
    val box = geometry.effectiveBox()
        ?: elements.mapNotNull { element -> element.geometry.effectiveBox() }.unionBoxes()
        ?: block.geometry.effectiveBox()
    val source = CandidateSource.from(page, block, this, box)
    return TotalsAtom(
        page = page,
        rawText = text,
        box = box,
        evidence = PeruvianTextNormalizer.normalize(source),
        confidencePermille = confidencePermille,
        blockPosition = block.position,
        linePosition = position,
        elementPosition = null,
    )
}

private fun InvoiceTextElement.toAtom(
    page: InvoiceTextPage,
    block: InvoiceTextBlock,
    line: InvoiceTextLine,
): TotalsAtom? {
    val box = geometry.effectiveBox() ?: return null
    val source = CandidateSource.from(page, block, line, this, box)
    return TotalsAtom(
        page = page,
        rawText = text,
        box = box,
        evidence = PeruvianTextNormalizer.normalize(source),
        confidencePermille = confidencePermille,
        blockPosition = block.position,
        linePosition = line.position,
        elementPosition = position,
    )
}

private fun TotalsRow.parseDirect(currencyContext: CurrencyCode?): ParsedTotalRow? {
    if (rawText.looksLikeFooterNoise()) return null
    val normalized = PeruvianTextNormalizer.normalize(source()).normalizedText
    val amountMatch = normalized.findSignedMoneySuffix() ?: return null
    if (amountMatch.range.last != normalized.lastIndex) return null
    val amountText = amountMatch.value.trim()
    val labelText = normalized.substring(0, amountMatch.range.first).trimLabelSeparators()
    if (labelText.isEmpty()) return null
    val label = recognizeTotalLabel(labelText) ?: return null
    if (label.sourceLabel in EXCLUDED_SUMMARY_LABELS) return null
    val amountSource = amountSource()
    val sourceAmountText = PeruvianTextNormalizer.normalize(amountSource).normalizedText.trim()
    val extractedSign = amountText.readPrintedSign() ?: return null
    val sourceSign = sourceAmountText.readPrintedSign()
    val parsingText = if (
        orderedAtoms.size > 1 && extractedSign != InvoicePrintedSign.NONE &&
        sourceSign == InvoicePrintedSign.NONE && amountSignComesFromRateLabel()
    ) {
        sourceAmountText
    } else {
        amountText
    }
    var amount = parseSignedMoney(amountSource, parsingText, currencyContext) ?: return null
    if (label.correction != null) amount = amount.withLabelCorrection(label.correction)
    return ParsedTotalRow(
        kind = label.kind,
        label = label,
        row = this,
        amount = amount,
        rate = if (label.kind == TotalKind.IGV) parseRate(label) else null,
        relation = if (orderedAtoms.size == 1) {
            InvoiceTotalReason.SAME_LINE_AS_LABEL
        } else {
            InvoiceTotalReason.SAME_ROW_AS_LABEL
        },
    )
}

private fun TotalsRow.detectLabelSignal(): TotalLabelSignal? {
    if (rawText.looksLikeFooterNoise()) return null
    val normalized = PeruvianTextNormalizer.normalize(source()).normalizedText
    val amountMatch = normalized.findSignedMoneySuffix()
        ?.takeIf { match -> match.range.last == normalized.lastIndex }
    if (amountMatch != null) {
        val labelText = normalized.substring(0, amountMatch.range.first).trimLabelSeparators()
        val label = recognizeTotalLabel(labelText)
        if (label != null) {
            if (label.sourceLabel in EXCLUDED_SUMMARY_LABELS) return null
            return TotalLabelSignal(this, label, amountMatch.value.trim())
        }
    }
    recognizeWholeLabel()?.let { label ->
        return TotalLabelSignal(this, label, amountText = null)
    }
    val prefix = normalized.findRecognizedLabelPrefix() ?: return null
    if (prefix.label.sourceLabel in EXCLUDED_SUMMARY_LABELS) return null
    return TotalLabelSignal(this, prefix.label, amountText = prefix.remainder)
}

/** Reconoce una etiqueta cerrada al inicio aunque el importe que la sigue esté malformado. */
private fun String.findRecognizedLabelPrefix(): RecognizedLabelPrefix? {
    val candidateEnds = indices.asSequence()
        .filter { index -> this[index].isWhitespace() || this[index] in LABEL_VALUE_SEPARATORS }
        .map { index -> index }
        .sortedDescending()
    candidateEnds.forEach { endExclusive ->
        val prefix = substring(0, endExclusive).trim().trim(':', '=')
        val remainder = substring(endExclusive).trimStart(' ', ':', '=')
        if (prefix.isEmpty() || !MALFORMED_AMOUNT_START.containsMatchIn(remainder)) return@forEach
        val label = recognizeTotalLabel(prefix) ?: return@forEach
        return RecognizedLabelPrefix(label, remainder)
    }
    return null
}

private fun TotalsRow.recognizeWholeLabel(): RecognizedTotalLabel? {
    if (rawText.looksLikeFooterNoise()) return null
    val normalized = PeruvianTextNormalizer.normalize(source()).normalizedText.trimLabelSeparators()
    val label = recognizeTotalLabel(normalized) ?: return null
    return label.takeUnless { recognized -> recognized.sourceLabel in EXCLUDED_SUMMARY_LABELS }
}

private fun List<TotalsRow>.parseLabelsAboveValues(
    currencyContext: CurrencyCode?,
): List<ParsedTotalRow> = mapIndexedNotNull { index, labelRow ->
    val label = labelRow.recognizeWholeLabel() ?: return@mapIndexedNotNull null
    val valueRow = getOrNull(index + 1) ?: return@mapIndexedNotNull null
    if (valueRow.page.pageIndex != labelRow.page.pageIndex) return@mapIndexedNotNull null
    if (!labelRow.canPairWithValueBelow(valueRow)) return@mapIndexedNotNull null
    val normalizedValue = PeruvianTextNormalizer.normalize(valueRow.source()).normalizedText.trim()
    val fullAmount = normalizedValue.findSignedMoneySuffix()
    if (fullAmount == null || fullAmount.range != normalizedValue.indices) {
        return@mapIndexedNotNull null
    }
    var amount = parseSignedMoney(valueRow.amountSource(), normalizedValue, currencyContext)
        ?: return@mapIndexedNotNull null
    if (label.correction != null) amount = amount.withLabelCorrection(label.correction)
    val combined = TotalsRow(labelRow.page, labelRow.atoms + valueRow.atoms)
    ParsedTotalRow(
        kind = label.kind,
        label = label,
        row = combined,
        amount = amount,
        rate = if (label.kind == TotalKind.IGV) labelRow.parseRate(label) else null,
        relation = InvoiceTotalReason.DIRECTLY_BELOW_LABEL,
    )
}

private fun TotalsRow.canPairWithValueBelow(value: TotalsRow): Boolean {
    val labelBox = box
    val valueBox = value.box
    if (labelBox == null || valueBox == null) {
        val labelAtom = orderedAtoms.singleOrNull() ?: return false
        val valueAtom = value.orderedAtoms.singleOrNull() ?: return false
        return labelAtom.blockPosition == valueAtom.blockPosition &&
            labelAtom.linePosition != null && valueAtom.linePosition == labelAtom.linePosition + 1
    }
    val gap = valueBox.topPx - labelBox.bottomPx
    if (gap < 0 || gap > maxOf(labelBox.height * MAX_BELOW_HEIGHT_MULTIPLIER, page.heightPx / 25)) {
        return false
    }
    val horizontalOverlap = minOf(labelBox.rightPx, valueBox.rightPx) -
        maxOf(labelBox.leftPx, valueBox.leftPx)
    val smallerWidth = minOf(labelBox.width, valueBox.width)
    val aligned = horizontalOverlap > 0 &&
        horizontalOverlap * 100 >= smallerWidth * MIN_HORIZONTAL_ALIGNMENT_PERCENT
    val valueCenter = valueBox.leftPx + valueBox.width / 2
    return aligned || valueCenter in labelBox.leftPx..labelBox.rightPx
}

private fun TotalsRow.parseRate(label: RecognizedTotalLabel): Candidate<TaxRate>? {
    val evidence = PeruvianTextNormalizer.normalize(rateSource())
    val comparisonText = rateComparisonText()
    val matches = PRINTED_RATE.findAll(comparisonText).toList()
    val printedPercentCount = comparisonText.count { character -> character == '%' }
    if (matches.isEmpty() && printedPercentCount == 0) return null
    val warnings = mutableListOf<CandidateWarning>()
    val corrections = mutableListOf<AppliedOcrCorrection>()
    val rates = matches.mapNotNull { match ->
        if (match.hasAdjacentAccountingSign(comparisonText)) return@mapNotNull null
        val rawToken = match.groupValues[1]
        val corrected = rawToken.correctNumericConfusables()
        if (corrected != rawToken) {
            warnings += CandidateWarning.OCR_CORRECTION_APPLIED
            if (rawToken in evidence.comparisonText) {
                corrections += AppliedOcrCorrection(
                    originalFragment = rawToken,
                    correctedFragment = corrected,
                    reason = OcrCorrectionReason.NUMERIC_CONFUSABLE_IN_NUMERIC_CONTEXT,
                )
            }
        }
        if (
            (rawToken.contains(',') || rawToken.contains('.')) &&
            rawToken.substringAfterLast(',', rawToken.substringAfterLast('.')).length == 3
        ) {
            warnings += CandidateWarning.AMBIGUOUS_NUMBER_SEPARATOR
        }
        val decimalText = corrected.replace(',', '.')
        val decimal = decimalText.takeIf { token -> token.length <= MAX_RATE_TOKEN_LENGTH }
            ?.let { token -> runCatching { BigDecimal(token) }.getOrNull() }
            ?.takeIf { value -> value.precision() <= MAX_RATE_PRECISION && value.scale() <= MAX_RATE_SCALE }
        decimal?.let { value -> runCatching { TaxRate(value) }.getOrNull() }
    }.fold(emptyList<TaxRate>()) { distinct, rate ->
        if (distinct.any { item -> item.percent.compareTo(rate.percent) == 0 }) distinct else distinct + rate
    }
    if (matches.size > 1) warnings += CandidateWarning.MULTIPLE_VALUES_FOUND
    if (rates.size < matches.size || matches.size < printedPercentCount) {
        warnings += CandidateWarning.UNSUPPORTED_NUMERIC_VALUE
    }
    if (label.correction != null) {
        warnings += CandidateWarning.OCR_CORRECTION_APPLIED
    }
    if (rates.isEmpty()) warnings += CandidateWarning.UNSUPPORTED_NUMERIC_VALUE
    val distinctWarnings = warnings.distinct()
    val unresolved = rates.size != 1 || distinctWarnings.any(CandidateWarning::requiresReview)
    return Candidate(
        value = rates.singleOrNull()?.takeUnless { unresolved },
        evidence = evidence.copy(corrections = corrections.distinct()),
        boundingBox = box,
        confidencePermille = orderedAtoms.mapNotNull(TotalsAtom::confidencePermille).minOrNull(),
        warnings = distinctWarnings,
        alternatives = if (unresolved) rates else emptyList(),
    )
}

private fun MatchResult.hasAdjacentAccountingSign(text: String): Boolean {
    val previous = text.substring(0, range.first).trimEnd().lastOrNull()
    val next = text.substring(range.last + 1).trimStart().firstOrNull()
    return previous in ACCOUNTING_SIGNS || next in ACCOUNTING_SIGNS
}

private fun parseSignedMoney(
    source: CandidateSource,
    amountText: String,
    currencyContext: CurrencyCode?,
): ParsedSignedMoney? {
    val sign = amountText.readPrintedSign() ?: return null
    val unsignedText = amountText.removeAccountingSign(sign) ?: return null
    val printedCurrencies = unsignedText.readPrintedCurrencies()
    val explicitCurrency = printedCurrencies.currencies.distinct().singleOrNull()
    // El parser regional exige una frontera alrededor del número. Algunos comprobantes
    // imprimen el marcador pegado (S/100.00, 100.00PEN); separamos solo la copia de trabajo y
    // restauramos la evidencia original debajo.
    val parserText = PARSER_CURRENCY_MARKER.replace(unsignedText) { match ->
        when (match.toCurrencyOrNull()) {
            null -> match.value
            USD -> " USD "
            else -> " ${match.value} "
        }
    }.trim()
    val parsed = PeruvianValueParser.parseMoney(
        source.copy(rawText = parserText),
        explicitCurrency ?: currencyContext,
    ) ?: return null
    val usedContext = explicitCurrency == null && currencyContext != null
    val promoted = if (usedContext) parsed.promoteCurrencyContext() else parsed
    var signed = promoted.mapValues { money ->
        if (sign.isNegative) Money.ofMinor(-money.minorUnits, money.currency) else money
    }
    if (printedCurrencies.markerCount > 1) {
        signed = signed.forceReview(CandidateWarning.MULTIPLE_VALUES_FOUND)
    }
    val originalEvidence = PeruvianTextNormalizer.normalize(source).copy(
        corrections = signed.evidence.corrections,
    )
    return ParsedSignedMoney(
        candidate = signed.copy(evidence = originalEvidence),
        printedSign = sign,
        usedCurrencyContext = usedContext,
    )
}

private fun ParsedSignedMoney.withLabelCorrection(
    correction: AppliedOcrCorrection,
): ParsedSignedMoney {
    val values = (listOfNotNull(candidate.value) + candidate.alternatives).distinct()
    return copy(
        candidate = Candidate(
            value = null,
            evidence = candidate.evidence.copy(
                corrections = candidate.evidence.corrections,
            ),
            boundingBox = candidate.boundingBox,
            confidencePermille = candidate.confidencePermille,
            warnings = (candidate.warnings + CandidateWarning.OCR_CORRECTION_APPLIED).distinct(),
            alternatives = values,
        ),
    )
}

private fun String.readPrintedSign(): InvoicePrintedSign? {
    val value = trim()
    val outerParentheses = value.startsWith('(') && value.endsWith(')')
    val currencyOutsideParentheses = CURRENCY_BEFORE_PARENTHESES.containsMatchIn(value) && value.endsWith(')')
    val parenthesized = outerParentheses || currencyOutsideParentheses
    if ((value.contains('(') || value.contains(')')) && !parenthesized) return null
    if (parenthesized && (value.count { it == '(' } != 1 || value.count { it == ')' } != 1)) return null
    val signs = value.filter { character -> character in ACCOUNTING_SIGNS }
    if (parenthesized && signs.isNotEmpty()) return null
    if (signs.length > 1) return null
    return when {
        parenthesized -> InvoicePrintedSign.PARENTHESES_NEGATIVE
        signs.singleOrNull() == '+' -> InvoicePrintedSign.POSITIVE
        signs.singleOrNull() != null -> InvoicePrintedSign.NEGATIVE
        else -> InvoicePrintedSign.NONE
    }
}

private val InvoicePrintedSign.isNegative: Boolean
    get() = this == InvoicePrintedSign.NEGATIVE || this == InvoicePrintedSign.PARENTHESES_NEGATIVE

private fun String.removeAccountingSign(sign: InvoicePrintedSign): String? {
    var value = trim()
    if (sign == InvoicePrintedSign.PARENTHESES_NEGATIVE) {
        value = value.replaceFirst("(", "").replaceFirst(")", "").trim()
    }
    if (sign == InvoicePrintedSign.POSITIVE || sign == InvoicePrintedSign.NEGATIVE) {
        val signIndex = value.indexOfFirst { character -> character in ACCOUNTING_SIGNS }
        if (signIndex < 0) return null
        value = value.removeRange(signIndex, signIndex + 1).trim()
    }
    return value.takeIf { unsigned -> unsigned.none { character -> character in ACCOUNTING_SIGNS } }
}

private fun String.explicitCurrency(): CurrencyCode? =
    readPrintedCurrencies().currencies.distinct().singleOrNull()

private fun String.readPrintedCurrencies(): PrintedCurrencyMarkers {
    val matches = PRINTED_CURRENCY_MARKER.findAll(this).toList()
    val currencies = matches.mapNotNull(MatchResult::toCurrencyOrNull)
    return PrintedCurrencyMarkers(currencies, currencies.size)
}

private fun MatchResult.toCurrencyOrNull(): CurrencyCode? =
    when (value.replace(" ", "").uppercase(Locale.ROOT)) {
        "S/" -> PEN
        "US$" -> USD
        else -> runCatching { CurrencyCode.of(value) }.getOrNull()
    }

private fun <T : Any> Candidate<T>.promoteCurrencyContext(): Candidate<T> {
    if (CandidateWarning.CURRENCY_FROM_CONTEXT !in warnings) return this
    val remaining = warnings - CandidateWarning.CURRENCY_FROM_CONTEXT
    val alternative = alternatives.singleOrNull()
    return if (value == null && alternative != null && remaining.none(CandidateWarning::requiresReview)) {
        Candidate(
            value = alternative,
            evidence = evidence,
            boundingBox = boundingBox,
            confidencePermille = confidencePermille,
            warnings = remaining,
        )
    } else {
        Candidate(
            value = value,
            evidence = evidence,
            boundingBox = boundingBox,
            confidencePermille = confidencePermille,
            warnings = remaining,
            alternatives = alternatives,
        )
    }
}

private fun <T : Any, R : Any> Candidate<T>.mapValues(transform: (T) -> R): Candidate<R> = Candidate(
    value = value?.let(transform),
    evidence = evidence,
    boundingBox = boundingBox,
    confidencePermille = confidencePermille,
    warnings = warnings,
    alternatives = alternatives.map(transform).distinct(),
)

private fun <T : Any> Candidate<T>.forceReview(warning: CandidateWarning): Candidate<T> {
    val possibleValues = (listOfNotNull(value) + alternatives).distinct()
    return Candidate(
        value = null,
        evidence = evidence,
        boundingBox = boundingBox,
        confidencePermille = confidencePermille,
        warnings = (warnings + warning).distinct(),
        alternatives = possibleValues,
    )
}

private fun ParsedTotalRow.toMoneyOccurrence(weakSummaryContext: Boolean): MoneyOccurrence {
    val signedCentralField = amount.printedSign.isNegative && kind in SIGNED_CONTEXT_REQUIRED_KINDS
    val occurrenceCandidate = if (signedCentralField) {
        amount.candidate.forceReview(CandidateWarning.SIGNED_TOTAL_REQUIRES_DOCUMENT_CONTEXT)
    } else {
        amount.candidate
    }
    val occurrence = InvoiceTotalOccurrence(
        candidate = occurrenceCandidate,
        label = label.canonicalLabel,
        rawText = row.rawText,
        pageIndex = row.page.pageIndex,
        evidence = row.evidence(label.correction),
        boundingBox = row.box,
        printedSign = amount.printedSign,
        reasons = buildList {
            add(InvoiceTotalReason.EXPLICIT_LABEL)
            add(relation)
            if (row.isGeometricallyLowerSummary()) add(InvoiceTotalReason.LOWER_SUMMARY_BLOCK)
        },
        warnings = buildList {
            if (label.correction != null) add(InvoiceTotalOccurrenceWarning.OCR_LABEL_CORRECTION)
            if (kind == TotalKind.OTHER_CHARGE && amount.printedSign.isNegative) {
                add(InvoiceTotalOccurrenceWarning.NEGATIVE_CHARGE_REQUIRES_REVIEW)
            }
            if (row.box == null) add(InvoiceTotalOccurrenceWarning.WEAK_GEOMETRY_MATCH)
            if (weakSummaryContext) add(InvoiceTotalOccurrenceWarning.WEAK_SUMMARY_CONTEXT)
        },
    )
    return MoneyOccurrence(kind, occurrence, label.strength, row.centerY)
}

private fun ParsedTotalRow.toRateOccurrence(
    weakSummaryContext: Boolean,
): InvoiceTotalOccurrence<TaxRate>? =
    rate?.let { candidate -> row.toRateOccurrence(label, candidate, weakSummaryContext) }

private fun buildRateOccurrences(
    parsedRows: List<ParsedTotalRow>,
    signals: List<TotalLabelSignal>,
    weakSummaryPaths: Set<String>,
): List<InvoiceTotalOccurrence<TaxRate>> = buildList {
    parsedRows.filter { parsed -> parsed.kind == TotalKind.IGV }
        .mapNotNullTo(this) { parsed ->
            parsed.toRateOccurrence(parsed.row.pathKey in weakSummaryPaths)
        }
    signals.mapNotNull { signal ->
        val row = signal.row
        val label = signal.label
        if (label.kind != TotalKind.IGV) return@mapNotNull null
        row.parseRate(label)?.let { candidate ->
            row.toRateOccurrence(label, candidate, row.pathKey in weakSummaryPaths)
        }
    }.forEach { occurrence ->
        if (none { existing ->
                existing.rawText == occurrence.rawText &&
                    existing.pageIndex == occurrence.pageIndex &&
                    existing.evidence == occurrence.evidence
            }
        ) {
            add(occurrence)
        }
    }
}

private fun TotalsRow.toRateOccurrence(
    label: RecognizedTotalLabel,
    candidate: Candidate<TaxRate>,
    weakSummaryContext: Boolean,
): InvoiceTotalOccurrence<TaxRate> = InvoiceTotalOccurrence(
    candidate = candidate,
    label = label.canonicalLabel,
    rawText = rawText,
    pageIndex = page.pageIndex,
    evidence = rateEvidence(label.correction),
    boundingBox = box,
    reasons = listOf(InvoiceTotalReason.EXPLICIT_LABEL),
    warnings = buildList {
        if (label.correction != null) add(InvoiceTotalOccurrenceWarning.OCR_LABEL_CORRECTION)
        if (box == null) add(InvoiceTotalOccurrenceWarning.WEAK_GEOMETRY_MATCH)
        if (weakSummaryContext) add(InvoiceTotalOccurrenceWarning.WEAK_SUMMARY_CONTEXT)
    },
)

private fun ParsedTotalRow.toRoundingOccurrence(
    weakSummaryContext: Boolean,
): InvoiceTotalOccurrence<InvoiceRoundingAdjustment> {
    val reason = requireNotNull(label.roundingReason)
    val mappedValue = amount.candidate.mapValues { signedAmount ->
        InvoiceRoundingAdjustment(
            signedAmount = signedAmount,
            direction = when {
                signedAmount.minorUnits > 0L -> InvoiceRoundingDirection.INCREASE
                signedAmount.minorUnits < 0L -> InvoiceRoundingDirection.DECREASE
                else -> InvoiceRoundingDirection.UNCHANGED
            },
            reason = reason,
        )
    }
    val missingSign = amount.printedSign == InvoicePrintedSign.NONE
    val mapped = if (missingSign) {
        mappedValue.withUnspecifiedRoundingDirection()
    } else {
        mappedValue
    }
    return InvoiceTotalOccurrence(
        candidate = mapped,
        label = label.canonicalLabel,
        rawText = row.rawText,
        pageIndex = row.page.pageIndex,
        evidence = row.evidence(label.correction),
        boundingBox = row.box,
        printedSign = amount.printedSign,
        reasons = buildList {
            add(InvoiceTotalReason.EXPLICIT_LABEL)
            add(relation)
            if (row.isGeometricallyLowerSummary()) add(InvoiceTotalReason.LOWER_SUMMARY_BLOCK)
        },
        warnings = buildList {
            if (label.correction != null) add(InvoiceTotalOccurrenceWarning.OCR_LABEL_CORRECTION)
            if (missingSign) add(InvoiceTotalOccurrenceWarning.MISSING_EXPLICIT_SIGN)
            if (row.box == null) add(InvoiceTotalOccurrenceWarning.WEAK_GEOMETRY_MATCH)
            if (weakSummaryContext) add(InvoiceTotalOccurrenceWarning.WEAK_SUMMARY_CONTEXT)
        },
    )
}

private fun Candidate<InvoiceRoundingAdjustment>.withUnspecifiedRoundingDirection(): Candidate<InvoiceRoundingAdjustment> {
    val interpretations = (listOfNotNull(value) + alternatives).flatMap { adjustment ->
        val units = adjustment.signedAmount.minorUnits
        val magnitude = if (units < 0L) Math.negateExact(units) else units
        if (magnitude == 0L) {
            listOf(
                adjustment.copy(
                    signedAmount = Money.ofMinor(0L, adjustment.signedAmount.currency),
                    direction = InvoiceRoundingDirection.UNCHANGED,
                ),
            )
        } else {
            listOf(
                adjustment.copy(
                    signedAmount = Money.ofMinor(magnitude, adjustment.signedAmount.currency),
                    direction = InvoiceRoundingDirection.INCREASE,
                ),
                adjustment.copy(
                    signedAmount = Money.ofMinor(-magnitude, adjustment.signedAmount.currency),
                    direction = InvoiceRoundingDirection.DECREASE,
                ),
            )
        }
    }.distinct()
    return Candidate(
        value = null,
        evidence = evidence,
        boundingBox = boundingBox,
        confidencePermille = confidencePermille,
        warnings = (warnings + CandidateWarning.MISSING_EXPLICIT_SIGN).distinct(),
        alternatives = interpretations,
    )
}

private fun List<ParsedTotalRow>.toOtherCharges(
    weakSummaryPaths: Set<String>,
): List<InvoiceOtherCharge> = filter { parsed -> parsed.kind == TotalKind.OTHER_CHARGE }
    .mapIndexed { index, parsed ->
        InvoiceOtherCharge(
            position = index,
            kind = requireNotNull(parsed.label.chargeKind),
            label = parsed.label.canonicalLabel,
            amount = parsed.toMoneyOccurrence(parsed.row.pathKey in weakSummaryPaths).occurrence,
        )
    }

private fun List<MoneyOccurrence>.field(
    kind: TotalKind,
    preferred: List<MoneyOccurrence> = this,
): InvoiceTotalField<Money> {
    val matches = filter { occurrence -> occurrence.kind == kind }.sortedWith(
        compareBy<MoneyOccurrence>(
            { it.occurrence.pageIndex },
            { it.centerY },
            { it.strength },
        ),
    )
    val selectedMatch = preferred.filter { occurrence -> occurrence.kind == kind }.sortedWith(
        compareBy<MoneyOccurrence>(
            { it.occurrence.pageIndex },
            { it.centerY },
            { it.strength },
        ),
    ).lastOrNull()
    val selected = selectedMatch?.occurrence
    return InvoiceTotalField(
        selected = selected,
        alternatives = matches.filterNot { occurrence -> occurrence == selectedMatch }
            .map(MoneyOccurrence::occurrence),
    )
}

private fun <T : Any> List<InvoiceTotalOccurrence<T>>.toField(
    preferred: List<InvoiceTotalOccurrence<T>> = this,
): InvoiceTotalField<T> {
    val sorted = sortedWith(compareBy({ occurrence -> occurrence.pageIndex }, { occurrence -> occurrence.boundingBox?.topPx ?: Int.MIN_VALUE }))
    val preferredSorted = preferred.sortedWith(
        compareBy(
            { occurrence -> occurrence.pageIndex },
            { occurrence -> occurrence.boundingBox?.topPx ?: Int.MIN_VALUE },
        ),
    )
    val selected = preferredSorted.lastOrNull()
    return InvoiceTotalField(
        selected = selected,
        alternatives = sorted.filterNot { occurrence -> occurrence == selected },
    )
}

private fun calculate(
    read: ReadInvoiceTotals,
    currency: CurrencyCode?,
    hasIgvLabel: Boolean,
    unresolvedKinds: Set<TotalKind>,
    referenceIgvRate: TaxRate,
    taxRoundingMode: RoundingMode?,
    warnings: MutableList<InvoiceTotalsWarning>,
): CalculatedInvoiceTotals {
    if (currency == null) return CalculatedInvoiceTotals()
    val taxable = read.taxableOperations.resolvedMoney(currency)
    val exempt = read.exemptOperations.resolvedMoney(currency)
    val unaffected = read.unaffectedOperations.resolvedMoney(currency)
    val discount = read.discounts.resolvedMoney(currency)
    val discountIsZeroOrAbsent = TotalKind.DISCOUNT !in unresolvedKinds &&
        (read.discounts.selected == null || discount?.minorUnits == 0L)
    if (!discountIsZeroOrAbsent) {
        warnings += InvoiceTotalsWarning.DISCOUNT_SEMANTICS_NOT_EVALUATED
    }

    val subtotalValue = if (
        taxable != null && exempt != null && unaffected != null && discountIsZeroOrAbsent
    ) {
        safeMoney(warnings) {
            taxable + exempt + unaffected
        }
    } else {
        null
    }
    val calculatedSubtotal = subtotalValue?.let { value ->
        CalculatedInvoiceAmount(
            value = value,
            reason = InvoiceTotalsCalculationReason.OPERATIONS_NET_OF_DISCOUNTS,
            components = buildList {
                add(InvoiceTotalsComponent.TAXABLE_OPERATIONS)
                add(InvoiceTotalsComponent.EXEMPT_OPERATIONS)
                add(InvoiceTotalsComponent.UNAFFECTED_OPERATIONS)
            },
        )
    }

    val hasPrintedRateEvidence = read.printedIgvRate.all.isNotEmpty()
    val printedRates = read.printedIgvRate.all.flatMap { occurrence ->
        listOfNotNull(occurrence.candidate.value) + occurrence.candidate.alternatives
    }.distinctBy { rate -> rate.percent.stripTrailingZeros() }
    val printedRate = printedRates.singleOrNull()?.takeIf {
        read.printedIgvRate.selected?.candidate?.value != null &&
            read.printedIgvRate.all.none(InvoiceTotalOccurrence<TaxRate>::requiresReview)
    }
    if (printedRate != null && printedRate.percent.compareTo(referenceIgvRate.percent) != 0) {
        warnings += InvoiceTotalsWarning.REFERENCE_TAX_RATE_DIFFERS_FROM_PRINTED
    }
    val appliedRate = when {
        hasPrintedRateEvidence -> printedRate?.let { rate ->
            AppliedInvoiceTaxRate(rate, InvoiceTaxRateSource.PRINTED_ON_DOCUMENT)
        }
        hasIgvLabel -> AppliedInvoiceTaxRate(
            referenceIgvRate,
            InvoiceTaxRateSource.CONFIGURED_REFERENCE_SUPPORTED_BY_IGV_LABEL,
        )
        else -> null
    }
    val calculatedIgvValue = if (taxable != null && appliedRate != null) {
        calculateExactTax(taxable, appliedRate.rate, taxRoundingMode, warnings)
    } else {
        null
    }
    val calculatedIgv = calculatedIgvValue?.let { value ->
        CalculatedInvoiceAmount(
            value = value,
            reason = when (appliedRate?.source) {
                InvoiceTaxRateSource.PRINTED_ON_DOCUMENT ->
                    InvoiceTotalsCalculationReason.TAXABLE_OPERATIONS_TIMES_PRINTED_RATE
                InvoiceTaxRateSource.CONFIGURED_REFERENCE_SUPPORTED_BY_IGV_LABEL ->
                    InvoiceTotalsCalculationReason.TAXABLE_OPERATIONS_TIMES_SUPPORTED_REFERENCE_RATE
                null -> error("Una tasa calculada debe conservar su fuente")
            },
            components = listOf(InvoiceTotalsComponent.TAXABLE_OPERATIONS),
        )
    }

    val readSubtotal = read.subtotal.resolvedMoney(currency)
    val base = if (discountIsZeroOrAbsent) calculatedSubtotal?.value ?: readSubtotal else null
    val readIgv = read.igv.resolvedMoney(currency)
    val tax = calculatedIgv?.value ?: readIgv
    val charges = read.otherCharges.map { charge ->
        charge.amount.candidate.value?.takeUnless { charge.amount.requiresReview }
    }
    val chargesResolved = TotalKind.OTHER_CHARGE !in unresolvedKinds &&
        charges.none { charge -> charge == null } &&
        charges.filterNotNull().all { charge -> charge.currency == currency }
    val roundingOccurrence = read.rounding.selected
    val roundingValues = read.rounding.all.flatMap { occurrence ->
        listOfNotNull(occurrence.candidate.value) + occurrence.candidate.alternatives
    }.distinct()
    val rounding = roundingOccurrence?.candidate?.value?.takeIf {
        roundingValues.size <= 1 && read.rounding.all.none(InvoiceTotalOccurrence<InvoiceRoundingAdjustment>::requiresReview)
    }
    val roundingResolved = TotalKind.ROUNDING !in unresolvedKinds &&
        (roundingOccurrence == null || rounding != null)

    val taxableWithoutIgv = !hasIgvLabel && taxable?.minorUnits?.let { units -> units != 0L } == true
    if (taxableWithoutIgv) warnings += InvoiceTotalsWarning.MISSING_IGV_FOR_TAXABLE_OPERATIONS
    val noIgvTreatmentIsSupported = taxable?.minorUnits == 0L ||
        exempt?.minorUnits?.let { units -> units != 0L } == true ||
        unaffected?.minorUnits?.let { units -> units != 0L } == true
    val missingTaxTreatmentEvidence = !hasIgvLabel && !taxableWithoutIgv &&
        !noIgvTreatmentIsSupported
    if (missingTaxTreatmentEvidence) {
        warnings += InvoiceTotalsWarning.MISSING_TAX_TREATMENT_EVIDENCE
    }
    // Una etiqueta IGV permite calcular un valor diagnóstico con la tasa de referencia, pero
    // nunca debe sustituir silenciosamente un importe IGV impreso que quedó ilegible o ambiguo.
    // En ese caso se conserva [calculatedIgv], mientras el total y la reconciliación se bloquean.
    val printedIgvResolved = TotalKind.IGV !in unresolvedKinds &&
        (read.igv.all.isEmpty() || readIgv != null)
    val taxResolved = printedIgvResolved && (
        tax != null || !hasIgvLabel && !taxableWithoutIgv && noIgvTreatmentIsSupported
    )
    val beforeRoundingValue = if (base != null && chargesResolved && taxResolved) {
        safeMoney(warnings) {
            var value: Money = base
            if (tax != null) value += tax
            charges.filterNotNull().forEach { charge -> value += charge }
            value
        }
    } else {
        null
    }
    val beforeRounding = beforeRoundingValue?.let { value ->
        CalculatedInvoiceAmount(
            value = value,
            reason = InvoiceTotalsCalculationReason.PRINTED_COMPONENTS_BEFORE_ROUNDING,
            components = buildList {
                if (calculatedSubtotal != null) {
                    addAll(calculatedSubtotal.components)
                } else {
                    add(InvoiceTotalsComponent.READ_SUBTOTAL)
                }
                when {
                    calculatedIgv != null -> add(InvoiceTotalsComponent.CALCULATED_IGV)
                    readIgv != null -> add(InvoiceTotalsComponent.READ_IGV)
                }
                if (charges.filterNotNull().isNotEmpty()) add(InvoiceTotalsComponent.OTHER_CHARGES)
            }.distinct(),
        )
    }
    val finalValue = if (beforeRoundingValue != null && roundingResolved) {
        if (rounding == null) beforeRoundingValue else safeMoney(warnings) {
            beforeRoundingValue + rounding.signedAmount
        }
    } else {
        null
    }
    val finalTotal = finalValue?.let { value ->
        CalculatedInvoiceAmount(
            value = value,
            reason = InvoiceTotalsCalculationReason.PRINTED_COMPONENTS_AFTER_ROUNDING,
            components = buildList {
                addAll(beforeRounding?.components.orEmpty())
                if (rounding != null) add(InvoiceTotalsComponent.PRINTED_ROUNDING)
            }.distinct(),
        )
    }
    return CalculatedInvoiceTotals(
        subtotal = calculatedSubtotal,
        igv = calculatedIgv,
        totalBeforeRounding = beforeRounding,
        total = finalTotal,
        appliedIgvRate = appliedRate,
    )
}

private fun calculateExactTax(
    taxable: Money,
    rate: TaxRate,
    roundingMode: RoundingMode?,
    warnings: MutableList<InvoiceTotalsWarning>,
): Money? {
    val normalizedPercent = rate.percent.stripTrailingZeros()
    if (
        normalizedPercent.precision() > MAX_RATE_PRECISION ||
        kotlin.math.abs(normalizedPercent.scale()) > MAX_RATE_SCALE
    ) {
        warnings += InvoiceTotalsWarning.CALCULATION_OVERFLOW
        return null
    }
    val major = taxable.toMajor().multiply(normalizedPercent).movePointLeft(2)
    return try {
        Money.fromMajor(major, taxable.currency, roundingMode ?: RoundingMode.UNNECESSARY)
    } catch (_: RuntimeException) {
        val requiresRounding = runCatching {
            major.setScale(taxable.currency.defaultFractionDigits, RoundingMode.UNNECESSARY)
        }.isFailure
        warnings += if (requiresRounding) {
            InvoiceTotalsWarning.CALCULATION_REQUIRES_ROUNDING
        } else {
            InvoiceTotalsWarning.CALCULATION_OVERFLOW
        }
        null
    }
}

private fun reconcile(
    read: ReadInvoiceTotals,
    calculated: CalculatedInvoiceTotals,
    componentDifferences: ComponentDifferences,
    warnings: MutableList<InvoiceTotalsWarning>,
): InvoiceTotalsReconciliation? {
    val before = calculated.totalBeforeRounding?.value ?: return null
    val final = calculated.total?.value ?: return null
    val readTotal = read.total.resolvedMoney(final.currency) ?: return null
    if (readTotal.currency != final.currency || before.currency != final.currency) return null
    val differenceBefore = safeMoney(warnings) { readTotal - before } ?: return null
    val difference = safeMoney(warnings) { readTotal - final } ?: return null
    if (difference.minorUnits != 0L) warnings += InvoiceTotalsWarning.PRINTED_TOTAL_DIFFERS_FROM_CALCULATED
    return InvoiceTotalsReconciliation(
        readTotal = readTotal,
        calculatedBeforeRounding = before,
        calculatedTotal = final,
        explicitRounding = read.rounding.selected?.candidate?.value,
        differenceBeforeRounding = differenceBefore,
        difference = difference,
        subtotalDifference = componentDifferences.subtotal,
        igvDifference = componentDifferences.igv,
    )
}

private data class ComponentDifferences(
    val subtotal: Money?,
    val igv: Money?,
)

private fun compareCalculatedComponents(
    read: ReadInvoiceTotals,
    calculated: CalculatedInvoiceTotals,
    warnings: MutableList<InvoiceTotalsWarning>,
): ComponentDifferences {
    val calculatedSubtotal = calculated.subtotal?.value
    val subtotal = differenceBetween(
        read = calculatedSubtotal?.let { money -> read.subtotal.resolvedMoney(money.currency) },
        calculated = calculatedSubtotal,
        warnings = warnings,
    )
    val calculatedIgv = calculated.igv?.value
    val igv = differenceBetween(
        read = calculatedIgv?.let { money -> read.igv.resolvedMoney(money.currency) },
        calculated = calculatedIgv,
        warnings = warnings,
    )
    if (subtotal?.minorUnits?.let { units -> units != 0L } == true) {
        warnings += InvoiceTotalsWarning.PRINTED_SUBTOTAL_DIFFERS_FROM_CALCULATED
    }
    if (igv?.minorUnits?.let { units -> units != 0L } == true) {
        warnings += InvoiceTotalsWarning.PRINTED_IGV_DIFFERS_FROM_CALCULATED
    }
    return ComponentDifferences(subtotal, igv)
}

private fun differenceBetween(
    read: Money?,
    calculated: Money?,
    warnings: MutableList<InvoiceTotalsWarning>,
): Money? {
    if (read == null || calculated == null || read.currency != calculated.currency) return null
    return safeMoney(warnings) { read - calculated }
}

private fun InvoiceTotalField<Money>.resolvedMoney(currency: CurrencyCode): Money? {
    val values = all.flatMap { occurrence ->
        listOfNotNull(occurrence.candidate.value) + occurrence.candidate.alternatives
    }.distinct()
    if (values.size > 1 || all.any(InvoiceTotalOccurrence<Money>::requiresReview)) return null
    return selected?.candidate?.value?.takeIf { money -> money.currency == currency }
}

private inline fun safeMoney(
    warnings: MutableList<InvoiceTotalsWarning>,
    block: () -> Money,
): Money? = try {
    block()
} catch (_: RuntimeException) {
    warnings += InvoiceTotalsWarning.CALCULATION_OVERFLOW
    null
}

private fun ReadInvoiceTotals.allMoneyCandidates(): List<Candidate<Money>> = buildList {
    listOf(
        taxableOperations,
        exemptOperations,
        unaffectedOperations,
        discounts,
        subtotal,
        igv,
        total,
    ).forEach { field -> field.all.mapTo(this) { occurrence -> occurrence.candidate } }
    otherCharges.mapTo(this) { charge -> charge.amount.candidate }
    rounding.all.mapTo(this) { occurrence ->
        occurrence.candidate.mapValues(InvoiceRoundingAdjustment::signedAmount)
    }
}

private fun ReadInvoiceTotals.hasDifferentPrintedAlternatives(): Boolean {
    val differentMoney = listOf(
        taxableOperations,
        exemptOperations,
        unaffectedOperations,
        discounts,
        subtotal,
        igv,
        total,
    ).any { field ->
        field.all.flatMap { occurrence ->
            listOfNotNull(occurrence.candidate.value) + occurrence.candidate.alternatives
        }.distinct().size > 1
    }
    val differentRates = printedIgvRate.all.flatMap { occurrence ->
        listOfNotNull(occurrence.candidate.value) + occurrence.candidate.alternatives
    }.distinctBy { rate -> rate.percent.stripTrailingZeros() }.size > 1
    val differentRounding = rounding.all.flatMap { occurrence ->
        listOfNotNull(occurrence.candidate.value) + occurrence.candidate.alternatives
    }.distinct().size > 1
    return differentMoney || differentRates || differentRounding
}

private data class ChargeValueSignature(
    val kind: InvoiceOtherChargeKind,
    val label: String,
    val printedSign: InvoicePrintedSign,
    val values: Set<Money>,
    val requiresReview: Boolean,
)

private fun List<InvoiceOtherCharge>.chargeSignature(): Map<ChargeValueSignature, Int> =
    groupingBy { charge ->
        ChargeValueSignature(
            kind = charge.kind,
            label = charge.label,
            printedSign = charge.amount.printedSign,
            values = (listOfNotNull(charge.amount.candidate.value) + charge.amount.candidate.alternatives).toSet(),
            requiresReview = charge.amount.requiresReview,
        )
    }.eachCount()

private fun ReadInvoiceTotals.hasPriorOnlyAlternatives(): Boolean =
    listOf(
        taxableOperations,
        exemptOperations,
        unaffectedOperations,
        discounts,
        subtotal,
        igv,
        total,
    ).any { field -> field.selected == null && field.alternatives.isNotEmpty() } ||
        printedIgvRate.selected == null && printedIgvRate.alternatives.isNotEmpty() ||
        rounding.selected == null && rounding.alternatives.isNotEmpty()

private fun ReadInvoiceTotals.hasOcrCorrections(): Boolean =
    allMoneyCandidates().any { candidate ->
        CandidateWarning.OCR_CORRECTION_APPLIED in candidate.warnings ||
            candidate.evidence.corrections.isNotEmpty()
    } || printedIgvRate.all.any { occurrence ->
        CandidateWarning.OCR_CORRECTION_APPLIED in occurrence.candidate.warnings ||
            occurrence.candidate.evidence.corrections.isNotEmpty() ||
            occurrence.evidence.any { evidence -> evidence.corrections.isNotEmpty() }
    }

private fun String.correctNumericConfusables(): String = map { character ->
    when (character) {
        'O' -> '0'
        'I', 'L', '|' -> '1'
        else -> character
    }
}.joinToString("")

private fun recognizeTotalLabel(rawLabel: String): RecognizedTotalLabel? {
    val withoutRate = rawLabel.replace(RATE_LABEL_TOKEN, " ")
        .toLabelKey()
        .collapseSpaces()
        .trimLabelSeparators()
    findLabelDefinition(withoutRate)?.let { definition ->
        return definition.toRecognition(withoutRate, correction = null)
    }
    val correctedKeys = enumerateLabelCorrections(withoutRate)
        .mapNotNull { corrected -> findLabelDefinition(corrected)?.let { definition -> corrected to definition } }
        .distinctBy { (_, definition) -> definition.kind to definition.chargeKind }
    val unique = correctedKeys.singleOrNull() ?: return null
    val (corrected, definition) = unique
    return definition.toRecognition(
        source = withoutRate,
        correction = AppliedOcrCorrection(
            originalFragment = withoutRate,
            correctedFragment = corrected,
            reason = OcrCorrectionReason.FIXED_VOCABULARY_CONFUSABLE,
        ),
    )
}

private fun findLabelDefinition(key: String): LabelDefinition? =
    LABEL_DEFINITIONS.firstOrNull { definition -> key in definition.aliases }

private fun LabelDefinition.toRecognition(
    source: String,
    correction: AppliedOcrCorrection?,
): RecognizedTotalLabel = RecognizedTotalLabel(
    kind = kind,
    canonicalLabel = aliases.first(),
    sourceLabel = source,
    chargeKind = chargeKind,
    roundingReason = roundingReason,
    strength = strength,
    correction = correction,
)

private fun enumerateLabelCorrections(value: String): Set<String> {
    var candidates = setOf("")
    value.forEach { character ->
        val replacements = when (character) {
            '0' -> listOf('0', 'O')
            '1', '|' -> listOf(character, 'I', 'L')
            'L' -> listOf('L', 'I')
            else -> listOf(character)
        }
        candidates = candidates.flatMap { prefix -> replacements.map { replacement -> prefix + replacement } }
            .take(MAX_LABEL_CORRECTIONS)
            .toSet()
    }
    return candidates - value
}

private fun String.toLabelKey(): String {
    val decomposed = Normalizer.normalize(this, Normalizer.Form.NFD)
    return decomposed
        .filterNot { character -> Character.getType(character) == Character.NON_SPACING_MARK.toInt() }
        .uppercase(Locale.ROOT)
        .replace(LABEL_PUNCTUATION, " ")
        .collapseSpaces()
        .trim()
}

private fun String.trimLabelSeparators(): String = trim().trim(' ', ':', '=', '-', '‒', '−', '–', '—')

private fun String.collapseSpaces(): String = trim().replace(MULTIPLE_SPACES, " ")

private fun String.looksLikeFooterNoise(): Boolean {
    val key = toLabelKey()
    return FOOTER_NOISE.containsMatchIn(key) || count { character -> character == '|' } >= MIN_QR_SEPARATORS
}

private fun String.findSignedMoneySuffix(): MatchResult? =
    // Probar cada inicio permite descartar un falso marcador ISO (por ejemplo, la etiqueta IGV)
    // y continuar con el número real. Elegir el inicio más a la izquierda conserva tanto
    // REDONDEO‒S/0.01 como REDONDEO S/-0.01.
    sequenceOf(SIGNED_MONEY_SUFFIX, COMPACT_SIGNED_MONEY_SUFFIX)
        .flatMap { pattern ->
            indices.asSequence().mapNotNull { start ->
                pattern.find(this, start)?.takeIf { match -> match.range.first == start }
            }
        }
        .filter { match -> match.range.last == lastIndex }
        .filter(MatchResult::hasSupportedCurrencyMarkers)
        .minWithOrNull(compareBy({ match -> match.range.first }, { match -> -match.value.length }))

private fun MatchResult.hasSupportedCurrencyMarkers(): Boolean =
    PRINTED_CURRENCY_MARKER.findAll(value).all { marker ->
        marker.toCurrencyOrNull() != null || marker.isNumericConfusableFragment(value)
    }

private fun MatchResult.isNumericConfusableFragment(container: String): Boolean {
    if (value.any { character -> character !in NUMERIC_CONFUSABLE_CHARACTERS }) return false
    val previous = container.getOrNull(range.first - 1)
    val next = container.getOrNull(range.last + 1)
    return previous in NUMERIC_NEIGHBOR_CHARACTERS || next in NUMERIC_NEIGHBOR_CHARACTERS
}

private fun InvoiceTextGeometry.effectiveBox(): InvoiceTextBoundingBox? = boundingBox
    ?: cornerPoints.takeIf { points -> points.size == CORNER_COUNT }?.let { points ->
        InvoiceTextBoundingBox(
            leftPx = points.minOf(InvoiceTextPoint::xPx),
            topPx = points.minOf(InvoiceTextPoint::yPx),
            rightPx = points.maxOf(InvoiceTextPoint::xPx) + 1,
            bottomPx = points.maxOf(InvoiceTextPoint::yPx) + 1,
        )
    }

private fun List<InvoiceTextBoundingBox>.unionBoxes(): InvoiceTextBoundingBox? =
    takeIf(List<InvoiceTextBoundingBox>::isNotEmpty)?.let { boxes ->
        InvoiceTextBoundingBox(
            leftPx = boxes.minOf(InvoiceTextBoundingBox::leftPx),
            topPx = boxes.minOf(InvoiceTextBoundingBox::topPx),
            rightPx = boxes.maxOf(InvoiceTextBoundingBox::rightPx),
            bottomPx = boxes.maxOf(InvoiceTextBoundingBox::bottomPx),
        )
    }

private val InvoiceTextBoundingBox.width: Int
    get() = rightPx - leftPx

private val InvoiceTextBoundingBox.height: Int
    get() = bottomPx - topPx

private val LABEL_DEFINITIONS = listOf(
    LabelDefinition(
        TotalKind.TAXABLE,
        linkedSetOf(
            "OPERACIONES GRAVADAS", "OPERACION GRAVADA", "OP GRAVADAS", "OP GRAVADA",
            "VENTA GRAVADA", "BASE IMPONIBLE", "TOTAL OPERACIONES GRAVADAS",
        ),
    ),
    LabelDefinition(
        TotalKind.EXEMPT,
        linkedSetOf(
            "OPERACIONES EXONERADAS", "OPERACION EXONERADA", "OP EXONERADAS", "OP EXONERADA",
            "VENTA EXONERADA", "TOTAL OPERACIONES EXONERADAS",
        ),
    ),
    LabelDefinition(
        TotalKind.UNAFFECTED,
        linkedSetOf(
            "OPERACIONES INAFECTAS", "OPERACION INAFECTA", "OP INAFECTAS", "OP INAFECTA",
            "VENTA INAFECTA", "TOTAL OPERACIONES INAFECTAS",
        ),
    ),
    LabelDefinition(
        TotalKind.DISCOUNT,
        linkedSetOf("DESCUENTO GLOBAL", "TOTAL DESCUENTOS", "DESCUENTO", "DSCTO", "DCTO"),
    ),
    LabelDefinition(
        TotalKind.SUBTOTAL,
        linkedSetOf("SUBTOTAL", "SUB TOTAL", "VALOR DE VENTA", "TOTAL VALOR DE VENTA"),
    ),
    LabelDefinition(
        TotalKind.IGV,
        linkedSetOf("IGV", "TOTAL IGV", "IMPUESTO GENERAL A LAS VENTAS"),
    ),
    LabelDefinition(
        TotalKind.OTHER_CHARGE,
        linkedSetOf("OTROS CARGOS", "CARGOS ADICIONALES"),
        chargeKind = InvoiceOtherChargeKind.OTHER_CHARGES,
    ),
    LabelDefinition(
        TotalKind.OTHER_CHARGE,
        linkedSetOf("ISC"),
        chargeKind = InvoiceOtherChargeKind.ISC,
    ),
    LabelDefinition(
        TotalKind.OTHER_CHARGE,
        linkedSetOf("ICBPER"),
        chargeKind = InvoiceOtherChargeKind.ICBPER,
    ),
    LabelDefinition(
        TotalKind.OTHER_CHARGE,
        linkedSetOf("PERCEPCION"),
        chargeKind = InvoiceOtherChargeKind.PERCEPTION,
    ),
    LabelDefinition(
        TotalKind.ROUNDING,
        linkedSetOf("DIFERENCIA DE REDONDEO", "DIF REDONDEO"),
        roundingReason = InvoiceRoundingReason.PRINTED_ROUNDING_DIFFERENCE,
    ),
    LabelDefinition(
        TotalKind.ROUNDING,
        linkedSetOf("AJUSTE DE REDONDEO", "REDONDEO"),
        roundingReason = InvoiceRoundingReason.PRINTED_ROUNDING,
    ),
    LabelDefinition(
        TotalKind.TOTAL,
        linkedSetOf("TOTAL A PAGAR", "IMPORTE TOTAL", "TOTAL DEL COMPROBANTE", "TOTAL VENTA"),
        strength = 2,
    ),
    LabelDefinition(TotalKind.TOTAL, linkedSetOf("TOTAL"), strength = 1),
)

private const val TOTALS_CURRENCY_PATTERN = "(?:S\\s*/|US\\$|[A-Z]{3})"
private const val TOTALS_NUMBER_PATTERN = "[0-9OIL|]+(?:[., ][0-9OIL|]+)*"
private val SIGNED_MONEY_SUFFIX = Regex(
    "(?i)(?<![\\p{L}\\p{N}.,+/%_’'‒−–—\\-])(?:" +
        "\\(\\s*(?:$TOTALS_CURRENCY_PATTERN\\s*)?$TOTALS_NUMBER_PATTERN" +
        "(?:\\s*$TOTALS_CURRENCY_PATTERN)?\\s*\\)|" +
        "$TOTALS_CURRENCY_PATTERN\\s*\\(\\s*$TOTALS_NUMBER_PATTERN\\s*\\)|" +
        "(?:[+\\-‒−–—]\\s*)?(?:$TOTALS_CURRENCY_PATTERN\\s*)?" +
        "(?:[+\\-‒−–—]\\s*)?$TOTALS_NUMBER_PATTERN" +
        "(?:\\s*$TOTALS_CURRENCY_PATTERN)?\\s*[+\\-‒−–—]?" +
        ")\\s*$",
)
private val COMPACT_SIGNED_MONEY_SUFFIX = Regex(
    "(?i)[+\\-‒−–—]\\s*(?:$TOTALS_CURRENCY_PATTERN\\s*)?" +
        "$TOTALS_NUMBER_PATTERN(?:\\s*$TOTALS_CURRENCY_PATTERN)?\\s*$",
)
private val PRINTED_RATE = Regex(
    """(?<![\p{L}\p{N}.,+\-‒−–—])([0-9OIL|]+(?:[.,][0-9OIL|]+)?)\s*%(?![\p{L}\p{N}.,%+\-‒−–—])""",
)
private val RATE_LABEL_TOKEN = Regex(
    """(?<![\p{L}\p{N}])(?:\(\s*(?:[+\-‒−–—]\s*)?[.,]?[0-9OIL|]+(?:[.,][0-9OIL|]+)?\s*%\s*\)|(?:[+\-‒−–—]\s*)?[.,]?[0-9OIL|]+(?:[.,][0-9OIL|]+)?\s*%)""",
)
private val MONEY_DIGIT = Regex("[0-9OIL|]")
private val PRINTED_CURRENCY_MARKER = Regex("""(?i)(?<![A-Z])(?:S\s*/|US\$|[A-Z]{3})(?![A-Z])""")
private val PARSER_CURRENCY_MARKER = PRINTED_CURRENCY_MARKER
private val CURRENCY_BEFORE_PARENTHESES = Regex("(?i)^$TOTALS_CURRENCY_PATTERN\\s*\\(")
private val LABEL_PUNCTUATION = Regex("[._:/]+")
private val MULTIPLE_SPACES = Regex("(?U)\\s+")
private val LABEL_VALUE_SEPARATORS = setOf(':', '=', '-', '‒', '−', '–', '—')
private val MALFORMED_AMOUNT_START = Regex(
    "(?i)^[+\\-‒−–—]?(?:\\s*)(?:\\(|S\\s*/|US\\$|[A-Z]{3}(?![A-Z])|[.,0-9OIL|])",
)
private val FOOTER_NOISE = Regex(
    "^(?:QR(?:\\b| )|HASH(?:\\b| )|SUNAT(?:\\b| )|WWW(?:\\b| )|HTTPS?(?:\\b| )|" +
        "REPRESENTACION IMPRESA|FIRMA(?:\\b| )|OBSERVACION(?:ES)?(?:\\b| )|" +
        "PAGINA [0-9]+|CONTINUA(?:\\b|$)|DATOS DEL CLIENTE|CLIENTE(?:\\b| ))",
)
private val EXCLUDED_SUMMARY_LABELS = setOf(
    "SUBTOTAL PAGINA",
    "TOTAL PAGINA",
    "TOTAL ACUMULADO",
    "TOTAL TRANSPORTE",
)
private val ACCOUNTING_SIGNS = setOf('+', '-', '‒', '−', '–', '—')
private val NUMERIC_CONFUSABLE_CHARACTERS = setOf('O', 'I', 'L')
private val NUMERIC_NEIGHBOR_CHARACTERS = setOf('.', ',', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'O', 'I', 'L', '|')
private val SIGNED_CONTEXT_REQUIRED_KINDS = setOf(
    TotalKind.TAXABLE,
    TotalKind.EXEMPT,
    TotalKind.UNAFFECTED,
    TotalKind.SUBTOTAL,
    TotalKind.IGV,
    TotalKind.TOTAL,
)
private val SUMMARY_ANCHOR_KINDS = setOf(
    TotalKind.TAXABLE,
    TotalKind.EXEMPT,
    TotalKind.UNAFFECTED,
    TotalKind.DISCOUNT,
    TotalKind.IGV,
    TotalKind.OTHER_CHARGE,
    TotalKind.ROUNDING,
)

private const val CORNER_COUNT = 4
private const val MIN_ELEMENT_ATOMS = 2
private const val MIN_VERTICAL_OVERLAP_PERCENT = 50
private const val MIN_HORIZONTAL_ALIGNMENT_PERCENT = 30
private const val MIN_ROW_CENTER_DISTANCE_PX = 6
private const val MAX_NON_GEOMETRIC_ROW_GAP = 6
private const val MAX_BELOW_HEIGHT_MULTIPLIER = 3
private const val MIN_QR_SEPARATORS = 2
private const val MAX_LABEL_CORRECTIONS = 64
private const val MAX_RATE_TOKEN_LENGTH = 32
private const val MAX_RATE_PRECISION = 38
private const val MAX_RATE_SCALE = 18
private const val MIN_SUMMARY_VERTICAL_PERCENT = 45
private const val MIN_STRONG_TOTAL_VERTICAL_PERCENT = 25
private const val MIN_ISOLATED_TOTAL_VERTICAL_PERCENT = 80
private const val MIN_SMALL_CLUSTER_VERTICAL_PERCENT = 70
private const val SUMMARY_CLUSTER_DIVISOR = 10
private const val MIN_SUMMARY_CLUSTER_KINDS = 3
private const val MIN_SMALL_SUMMARY_CLUSTER_KINDS = 2
private const val STRONG_TOTAL_STRENGTH = 2

private val PEN = CurrencyCode.of("PEN")
private val USD = CurrencyCode.of("USD")
