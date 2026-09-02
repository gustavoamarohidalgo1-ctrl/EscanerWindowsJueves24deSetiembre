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
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.Quantity
import com.facturastock.app.domain.model.RucValidator
import java.math.BigDecimal
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Parser puro de tablas OCR. Primero segmenta por geometría y solo luego interpreta cada celda. */
class InvoiceLineItemsParser {
    fun parse(
        snapshot: InvoiceOcrSnapshot,
        currencyContext: CurrencyCode? = null,
    ): InvoiceLineItemsParseResult = parse(
        snapshot = snapshot,
        currencyContext = currencyContext,
        parsedHeader = InvoiceHeaderParser().parse(snapshot),
    )

    /** Camino del orquestador: conserva el mismo contrato sin volver a parsear la cabecera. */
    internal fun parse(
        snapshot: InvoiceOcrSnapshot,
        currencyContext: CurrencyCode?,
        parsedHeader: InvoiceHeaderParseResult,
    ): InvoiceLineItemsParseResult {
        require(parsedHeader.draftId == snapshot.draftId && parsedHeader.runId == snapshot.runId) {
            "La cabecera preinterpretada pertenece a otro snapshot"
        }
        val documentCurrency = parsedHeader.explicitCurrency()
        val hasCurrencyConflict = currencyContext != null && documentCurrency != null &&
            currencyContext != documentCurrency
        val currency = when {
            hasCurrencyConflict -> null
            currencyContext != null -> currencyContext
            else -> documentCurrency
        }
        var previousColumns: List<ColumnBand>? = null
        val parsed = buildList {
            snapshot.document.pages.forEach { page ->
                val atoms = page.toTableAtoms()
                val visualRows = atoms.groupVisualRows()
                val detectedHeader = findTableHeader(page, visualRows)
                val header = detectedHeader ?: previousColumns
                    ?.takeIf { columns -> columns.all { column -> column.pageWidthPx == page.widthPx } }
                    ?.let { columns ->
                        TableHeader(
                            columns = columns.map { column -> column.copy() },
                            headerBottomPx = 0,
                            headerCenterYPx = 0,
                            headerAtomPaths = emptySet(),
                            expectedColumns = columns.map(ColumnBand::kind).toSet(),
                            weakGeometry = true,
                        )
                    }
                if (header != null) {
                    addAll(parsePageRows(page, visualRows, header, currency))
                    previousColumns = header.columns
                }
            }
        }
        val ordered = parsed
            .sortedWith(compareBy<MutableParsedRow>({ it.pageIndex }, { it.topPx }, { it.pathKey }))
            .mapIndexed { index, row -> row.toResult(index) }
        return InvoiceLineItemsParseResult(
            draftId = snapshot.draftId,
            runId = snapshot.runId,
            currency = currency,
            items = ordered,
            warnings = if (hasCurrencyConflict) {
                listOf(InvoiceLineItemsWarning.CURRENCY_CONFLICT)
            } else {
                emptyList()
            },
        )
    }
}

private enum class TableColumn {
    CODE,
    BARCODE,
    DESCRIPTION,
    QUANTITY,
    UNIT,
    UNIT_COST,
    DISCOUNT,
    TAX,
    TOTAL,
}

private data class TableAtom(
    val page: InvoiceTextPage,
    val rawText: String,
    val key: String,
    val box: InvoiceTextBoundingBox?,
    val evidence: CandidateEvidence,
    val confidencePermille: Int?,
    val blockPosition: Int?,
    val linePosition: Int?,
    val elementPosition: Int?,
    val weakGeometry: Boolean,
) {
    // La procedencia es inmutable y se usa repetidamente al agrupar, ordenar y deduplicar.
    val pathKey: String =
        "${page.pageIndex}:${blockPosition ?: -1}:${linePosition ?: -1}:${elementPosition ?: -1}"
}

private class VisualRow(firstAtom: TableAtom) {
    private val mutableAtoms = mutableListOf(firstAtom)

    val atoms: List<TableAtom>
        get() = mutableAtoms

    // groupVisualRows es el único punto de mutación: mantener la unión incremental evita
    // reconstruir listas y cajas en cada comparación vertical sin cambiar sus extremos.
    var box: InvoiceTextBoundingBox = requireNotNull(firstAtom.box)
        private set

    val topPx: Int
        get() = box.topPx

    val bottomPx: Int
        get() = box.bottomPx

    val centerYPx: Int
        get() = box.topPx + (box.bottomPx - box.topPx) / 2

    val heightPx: Int
        get() = box.bottomPx - box.topPx

    val orderedAtoms: List<TableAtom>
        get() = atoms.sortedWith(
            compareBy<TableAtom>({ it.box?.leftPx ?: Int.MAX_VALUE }, { it.pathKey }),
        )

    val comparisonText: String
        get() = orderedAtoms.joinToString(" ", transform = TableAtom::key).collapseSpaces()

    fun add(atom: TableAtom) {
        val atomBox = requireNotNull(atom.box)
        mutableAtoms += atom
        box = InvoiceTextBoundingBox(
            leftPx = min(box.leftPx, atomBox.leftPx),
            topPx = min(box.topPx, atomBox.topPx),
            rightPx = max(box.rightPx, atomBox.rightPx),
            bottomPx = max(box.bottomPx, atomBox.bottomPx),
        )
    }
}

private data class HeaderHit(
    val kind: TableColumn,
    val atoms: List<TableAtom>,
) {
    val box: InvoiceTextBoundingBox
        get() = requireNotNull(atoms.mapNotNull(TableAtom::box).unionBoxes())

    val centerXPx: Int
        get() = box.leftPx + (box.rightPx - box.leftPx) / 2
}

private data class ColumnBand(
    val kind: TableColumn,
    val leftPx: Int,
    val rightPx: Int,
    val pageWidthPx: Int,
    val headerKey: String,
) {
    init {
        require(leftPx in 0 until rightPx && rightPx <= pageWidthPx)
    }
}

private data class TableHeader(
    val columns: List<ColumnBand>,
    val headerBottomPx: Int,
    val headerCenterYPx: Int,
    val headerAtomPaths: Set<String>,
    val expectedColumns: Set<TableColumn>,
    val weakGeometry: Boolean,
) {
    val rateHintColumns: Set<TableColumn> = columns
        .filter { column ->
            (column.kind == TableColumn.DISCOUNT || column.kind == TableColumn.TAX) &&
                '%' in column.headerKey
        }
        .map(ColumnBand::kind)
        .toSet()
}

private data class AssignedVisualRow(
    val visualRow: VisualRow,
    val cells: Map<TableColumn, List<TableAtom>>,
    val ambiguousAssignment: Boolean,
) {
    val productAnchor: Boolean
        get() = cells.any { (column, atoms) ->
            column != TableColumn.DESCRIPTION && atoms.any { atom -> atom.rawText.isNotBlank() }
        }

    val descriptionOnly: Boolean
        get() = cells.isNotEmpty() && cells.keys == setOf(TableColumn.DESCRIPTION)
}

private data class MutableParsedRow(
    val pageIndex: Int,
    val expectedColumns: Set<TableColumn>,
    val rateHintColumns: Set<TableColumn>,
    val currency: CurrencyCode?,
    val cells: MutableMap<TableColumn, MutableList<TableAtom>>,
    val allAtoms: MutableList<TableAtom>,
    var topPx: Int,
    var bottomPx: Int,
    var joinedDescription: Boolean,
    var ambiguousAssignment: Boolean,
    var weakGeometry: Boolean,
) {
    val pathKey: String
        get() = allAtoms.minOf(TableAtom::pathKey)

    fun appendDescription(row: AssignedVisualRow) {
        val continuation = row.cells[TableColumn.DESCRIPTION].orEmpty()
        if (continuation.isEmpty()) return
        cells.getOrPut(TableColumn.DESCRIPTION, ::mutableListOf).addAll(continuation)
        allAtoms += row.visualRow.orderedAtoms
        topPx = min(topPx, row.visualRow.topPx)
        bottomPx = max(bottomPx, row.visualRow.bottomPx)
        joinedDescription = true
        ambiguousAssignment = ambiguousAssignment || row.ambiguousAssignment
        weakGeometry = weakGeometry || row.visualRow.atoms.any(TableAtom::weakGeometry)
    }

    fun toResult(position: Int): ParsedInvoiceLineItem {
        val orderedAtoms = allAtoms
            .distinctBy(TableAtom::pathKey)
            .sortedWith(
                compareBy<TableAtom>(
                    { it.box?.topPx ?: Int.MAX_VALUE },
                    { it.box?.leftPx ?: Int.MAX_VALUE },
                    { it.pathKey },
                ),
            )
        val orderedEvidence = orderedAtoms.map(TableAtom::evidence)
        val code = cells.candidateForText(TableColumn.CODE)
        val barcode = cells.candidateForText(TableColumn.BARCODE)
        val description = cells.candidateForText(TableColumn.DESCRIPTION)
        val quantitySource = cells.candidateSource(TableColumn.QUANTITY)
        val quantity = quantitySource
            ?.takeIf(CandidateSource::isClosedNumberCell)
            ?.let(PeruvianValueParser::parseQuantity)
            ?.markUnresolvedWhenIdentifierLike(quantitySource)
        val unit = cells.candidateSource(TableColumn.UNIT)?.let(PeruvianValueParser::parseUnit)

        val parsedCost = cells.candidateSource(TableColumn.UNIT_COST)
            ?.parseClosedUnitCost(currency)
        val parsedTotal = cells.candidateSource(TableColumn.TOTAL)
            ?.parseClosedMoney(currency)
        val parsedDiscount = cells.candidateSource(TableColumn.DISCOUNT)
            ?.parseAdjustment(currency, TableColumn.DISCOUNT in rateHintColumns)
        val parsedTax = cells.candidateSource(TableColumn.TAX)
            ?.parseAdjustment(currency, TableColumn.TAX in rateHintColumns)

        val candidates = listOfNotNull(
            code,
            barcode,
            description,
            quantity,
            unit,
            parsedCost?.candidate,
            parsedTotal?.candidate,
            parsedDiscount?.candidate,
            parsedTax?.candidate,
        )
        val missingExpected = expectedColumns.any { column ->
            when (column) {
                TableColumn.CODE -> code == null
                TableColumn.BARCODE -> barcode == null
                TableColumn.DESCRIPTION -> description == null
                TableColumn.QUANTITY -> quantity == null
                TableColumn.UNIT -> unit == null
                TableColumn.UNIT_COST -> parsedCost?.candidate == null
                TableColumn.DISCOUNT -> parsedDiscount?.candidate == null
                TableColumn.TAX -> parsedTax?.candidate == null
                TableColumn.TOTAL -> parsedTotal?.candidate == null
            }
        }
        val usedDocumentCurrency = listOfNotNull(
            parsedCost,
            parsedTotal,
            parsedDiscount,
            parsedTax,
        ).any(ParsedWithContext<*>::usedCurrencyContext)
        val hasUnresolved = candidates.any(Candidate<*>::requiresReview)
        val hasCorrection = candidates.any { candidate ->
            CandidateWarning.OCR_CORRECTION_APPLIED in candidate.warnings
        }
        val resolvedCurrencies = buildList {
            currency?.let(::add)
            parsedCost?.candidate?.value?.currency?.let(::add)
            parsedTotal?.candidate?.value?.currency?.let(::add)
            listOfNotNull(parsedDiscount?.candidate?.value, parsedTax?.candidate?.value)
                .filterIsInstance<InvoiceLineAdjustment.Amount>()
                .mapTo(this) { adjustment -> adjustment.value.currency }
        }.distinct()
        val hasCurrencyConflict = resolvedCurrencies.size > 1 || candidates.any { candidate ->
            CandidateWarning.CURRENCY_CONFLICT in candidate.warnings
        }
        val arithmeticMismatch = if (
            TableColumn.DISCOUNT !in expectedColumns && TableColumn.TAX !in expectedColumns
        ) {
            val quantityValue = quantity?.value
            val costValue = parsedCost?.candidate?.value
            val totalValue = parsedTotal?.candidate?.value
            quantityValue != null && costValue != null && totalValue != null &&
                costValue.currency == totalValue.currency &&
                quantityValue.value.multiply(costValue.amount).compareTo(totalValue.toMajor()) != 0
        } else {
            false
        }
        val warnings = buildList {
            if (missingExpected) add(InvoiceLineItemWarning.INCOMPLETE_ROW)
            if (description == null) add(InvoiceLineItemWarning.MISSING_DESCRIPTION)
            if (hasUnresolved) add(InvoiceLineItemWarning.UNRESOLVED_FIELD)
            if (arithmeticMismatch) add(InvoiceLineItemWarning.ARITHMETIC_MISMATCH)
            if (hasCurrencyConflict) add(InvoiceLineItemWarning.CURRENCY_CONFLICT)
            if (ambiguousAssignment) add(InvoiceLineItemWarning.AMBIGUOUS_COLUMN_ASSIGNMENT)
            if (weakGeometry) add(InvoiceLineItemWarning.WEAK_GEOMETRY_MATCH)
            if (hasCorrection) add(InvoiceLineItemWarning.OCR_CORRECTION_APPLIED)
            if (joinedDescription) add(InvoiceLineItemWarning.MULTILINE_DESCRIPTION_JOINED)
            if (usedDocumentCurrency) add(InvoiceLineItemWarning.CURRENCY_FROM_DOCUMENT)
        }.distinct()
        val rawText = orderedAtoms
            .groupBy { atom -> atom.box?.topPx ?: Int.MAX_VALUE }
            .values
            .joinToString("\n") { rowAtoms ->
                rowAtoms.joinToString(" ", transform = TableAtom::rawText)
            }
        return ParsedInvoiceLineItem(
            position = position,
            pageIndex = pageIndex,
            rawText = rawText,
            evidence = orderedEvidence,
            boundingBox = allAtoms.mapNotNull(TableAtom::box).unionBoxes(),
            code = code,
            barcode = barcode,
            description = description,
            quantity = quantity,
            unit = unit,
            unitCost = parsedCost?.candidate,
            discount = parsedDiscount?.candidate,
            tax = parsedTax?.candidate,
            total = parsedTotal?.candidate,
            warnings = warnings,
        )
    }
}

private data class ParsedWithContext<T : Any>(
    val candidate: Candidate<T>?,
    val usedCurrencyContext: Boolean,
)

private fun parsePageRows(
    page: InvoiceTextPage,
    rows: List<VisualRow>,
    header: TableHeader,
    currency: CurrencyCode?,
): List<MutableParsedRow> {
    val bodyRows = rows
        .flatMap(VisualRow::atoms)
        .filterNot { atom -> atom.pathKey in header.headerAtomPaths }
        .filter { atom ->
            val box = atom.box ?: return@filter false
            val centerY = box.topPx + (box.bottomPx - box.topPx) / 2
            centerY > header.headerCenterYPx
        }
        .groupVisualRows()
    val parsed = mutableListOf<MutableParsedRow>()
    val pendingDescriptions = mutableListOf<AssignedVisualRow>()
    var current: MutableParsedRow? = null
    val medianHeight = bodyRows.map(VisualRow::heightPx).medianOr(DEFAULT_ROW_HEIGHT_PX)
    val continuationGap = max(MIN_CONTINUATION_GAP_PX, medianHeight * 3 / 2)

    for (row in bodyRows) {
        if (row.isRepeatedHeader()) continue
        val assigned = row.assignToColumns(header.columns)
        if (assigned.cells.isEmpty()) continue
        if (row.isTableTerminator(assigned)) break
        if (row.isExcludedMetadataRow(assigned)) continue

        if (assigned.descriptionOnly) {
            val previous = current
            if (
                previous != null && row.topPx - previous.bottomPx <= continuationGap &&
                assigned.isSafeDescriptionContinuation()
            ) {
                previous.appendDescription(assigned)
            } else {
                pendingDescriptions += assigned
            }
            continue
        }

        if (!assigned.isAdmissibleProductRow()) continue
        val rowCells = assigned.cells.mapValuesTo(mutableMapOf()) { (_, atoms) -> atoms.toMutableList() }
        val allAtoms = assigned.visualRow.orderedAtoms.toMutableList()
        if (pendingDescriptions.isNotEmpty()) {
            pendingDescriptions.mapNotNullTo(parsed) { pending ->
                pending.toDescriptionOnlyRow(page, header, currency)
            }
            pendingDescriptions.clear()
        }
        current = MutableParsedRow(
            pageIndex = page.pageIndex,
            expectedColumns = header.expectedColumns,
            rateHintColumns = header.rateHintColumns,
            currency = currency,
            cells = rowCells,
            allAtoms = allAtoms,
            topPx = allAtoms.mapNotNull(TableAtom::box).minOfOrNull(InvoiceTextBoundingBox::topPx)
                ?: row.topPx,
            bottomPx = allAtoms.mapNotNull(TableAtom::box).maxOfOrNull(InvoiceTextBoundingBox::bottomPx)
                ?: row.bottomPx,
            joinedDescription = false,
            ambiguousAssignment = assigned.ambiguousAssignment,
            weakGeometry = header.weakGeometry || allAtoms.any(TableAtom::weakGeometry),
        ).also(parsed::add)
    }
    pendingDescriptions.mapNotNullTo(parsed) { pending ->
        pending.toDescriptionOnlyRow(page, header, currency)
    }
    return parsed
}

private fun AssignedVisualRow.isSafeDescriptionContinuation(): Boolean =
    cells[TableColumn.DESCRIPTION].orEmpty().all { atom ->
        atom.rawText.looksLikeProductDescription()
    }

private fun AssignedVisualRow.toDescriptionOnlyRow(
    page: InvoiceTextPage,
    header: TableHeader,
    currency: CurrencyCode?,
): MutableParsedRow? {
    val atoms = visualRow.orderedAtoms
    if (header.headerAtomPaths.isEmpty()) return null
    if (atoms.none { atom -> atom.rawText.looksLikeProductDescription() }) return null
    return MutableParsedRow(
        pageIndex = page.pageIndex,
        expectedColumns = header.expectedColumns,
        rateHintColumns = header.rateHintColumns,
        currency = currency,
        cells = cells.mapValuesTo(mutableMapOf()) { (_, value) -> value.toMutableList() },
        allAtoms = atoms.toMutableList(),
        topPx = visualRow.topPx,
        bottomPx = visualRow.bottomPx,
        joinedDescription = false,
        ambiguousAssignment = ambiguousAssignment,
        weakGeometry = header.weakGeometry || atoms.any(TableAtom::weakGeometry),
    )
}

private fun AssignedVisualRow.isAdmissibleProductRow(): Boolean {
    if (!productAnchor) return false
    if (TableColumn.DESCRIPTION in cells) return true
    if (TableColumn.CODE in cells || TableColumn.BARCODE in cells) return true
    val meaningfulAnchors = cells.keys.count { column ->
        column == TableColumn.QUANTITY || column == TableColumn.UNIT ||
            column == TableColumn.UNIT_COST || column == TableColumn.DISCOUNT ||
            column == TableColumn.TAX || column == TableColumn.TOTAL
    }
    return meaningfulAnchors >= MIN_ANCHORS_WITHOUT_DESCRIPTION
}

private fun InvoiceHeaderParseResult.explicitCurrency(): CurrencyCode? {
    val candidate = currency.selected ?: return null
    return candidate.value.takeIf {
        !candidate.requiresReview &&
            InvoiceHeaderReason.EXPLICIT_CURRENCY_LABEL in candidate.reasons
    }
}

private fun InvoiceTextPage.toTableAtoms(): List<TableAtom> = blocks.flatMap { block ->
    if (block.lines.isEmpty()) {
        block.text.lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .mapIndexed { lineIndex, text ->
                val box = block.geometry.effectiveBox()
                val source = CandidateSource(
                    rawText = text,
                    boundingBox = box,
                    sourceImageId = sourceImageId,
                    pageIndex = pageIndex,
                    cornerPoints = block.geometry.cornerPoints,
                    blockPosition = block.position,
                    linePosition = lineIndex,
                )
                source.toAtom(this, weakGeometry = true)
            }
            .toList()
    } else {
        block.lines.flatMap { line -> line.toTableAtoms(this, block) }
    }
}.filter { atom -> atom.rawText.isNotBlank() }

private fun InvoiceTextLine.toTableAtoms(
    page: InvoiceTextPage,
    block: InvoiceTextBlock,
): List<TableAtom> {
    val usableElements = elements.mapNotNull { element ->
        element.geometry.effectiveBox()?.let { box -> element to box }
    }
    if (elements.size >= MIN_SPLIT_ELEMENT_COUNT && usableElements.size == elements.size) {
        return usableElements.map { (element, box) ->
            CandidateSource.from(page, block, this, element, box)
                .toAtom(page, weakGeometry = false)
        }
    }
    val lineBox = geometry.effectiveBox()
        ?: elements.mapNotNull { element -> element.geometry.effectiveBox() }.unionBoxes()
        ?: block.geometry.effectiveBox()
    val inherited = geometry.effectiveBox() == null &&
        elements.mapNotNull { element -> element.geometry.effectiveBox() }.unionBoxes() == null
    return listOf(
        CandidateSource.from(page, block, this, lineBox)
            .toAtom(page, weakGeometry = inherited || lineBox == null),
    )
}

private fun CandidateSource.toAtom(
    page: InvoiceTextPage,
    weakGeometry: Boolean,
): TableAtom {
    val evidence = PeruvianTextNormalizer.normalize(this)
    return TableAtom(
        page = page,
        rawText = rawText,
        key = evidence.comparisonText.toSearchKey(),
        box = boundingBox,
        evidence = evidence,
        confidencePermille = confidencePermille,
        blockPosition = blockPosition,
        linePosition = linePosition,
        elementPosition = elementPosition,
        weakGeometry = weakGeometry,
    )
}

private fun List<TableAtom>.groupVisualRows(): List<VisualRow> {
    val geometric = filter { atom -> atom.box != null }
        .sortedWith(
            compareBy<TableAtom>(
                { it.box?.topPx ?: Int.MAX_VALUE },
                { it.box?.leftPx ?: Int.MAX_VALUE },
                { it.pathKey },
            ),
        )
    val rows = mutableListOf<VisualRow>()
    geometric.forEach { atom ->
        val box = requireNotNull(atom.box)
        val atomCenter = box.topPx + (box.bottomPx - box.topPx) / 2
        val best = rows
            .asSequence()
            .filter { row -> row.isVerticallyCompatible(box) }
            .minByOrNull { row -> abs(row.centerYPx - atomCenter) }
        if (best == null) {
            rows += VisualRow(atom)
        } else {
            best.add(atom)
        }
    }
    return rows.sortedWith(compareBy<VisualRow>({ it.topPx }, { it.box.leftPx }))
}

private fun VisualRow.isVerticallyCompatible(box: InvoiceTextBoundingBox): Boolean {
    val overlap = min(bottomPx, box.bottomPx) - max(topPx, box.topPx)
    val smallerHeight = min(heightPx, box.bottomPx - box.topPx)
    if (overlap > 0 && overlap * 100 >= smallerHeight * MIN_VERTICAL_OVERLAP_PERCENT) {
        return true
    }
    val otherCenter = box.topPx + (box.bottomPx - box.topPx) / 2
    val allowedCenterDistance = max(MIN_ROW_CENTER_DISTANCE_PX, min(heightPx, box.bottomPx - box.topPx) * 4 / 5)
    return abs(centerYPx - otherCenter) <= allowedCenterDistance
}

private fun findTableHeader(
    page: InvoiceTextPage,
    rows: List<VisualRow>,
): TableHeader? {
    rows.forEachIndexed { index, row ->
        val singleHits = row.headerHits()
        if (singleHits.isValidHeader()) {
            return page.toHeader(singleHits, row.bottomPx, row.atoms)
        }

        val next = rows.getOrNull(index + 1)
        if (next != null && next.topPx - row.bottomPx <= max(row.heightPx, next.heightPx)) {
            val combinedHits = (singleHits + next.headerHits()).mergeHeaderHits()
            if (combinedHits.isValidHeader()) {
                return page.toHeader(
                    combinedHits,
                    max(row.bottomPx, next.bottomPx),
                    row.atoms + next.atoms,
                )
            }
        }
    }
    return null
}

private fun InvoiceTextPage.toHeader(
    hits: List<HeaderHit>,
    bottomPx: Int,
    contextAtoms: List<TableAtom>,
): TableHeader {
    val merged = hits.mergeHeaderHits().sortedBy(HeaderHit::centerXPx)
    val supplementalHeaderPaths = contextAtoms
        .filter { atom -> HEADER_CONNECTOR.matches(atom.key) }
        .filter { atom ->
            val candidateBox = atom.box ?: return@filter false
            val compatibleHitBoxes = merged.map(HeaderHit::box).filter { hitBox ->
                val overlap = min(candidateBox.bottomPx, hitBox.bottomPx) -
                    max(candidateBox.topPx, hitBox.topPx)
                val smallerHeight = min(
                    candidateBox.bottomPx - candidateBox.topPx,
                    hitBox.bottomPx - hitBox.topPx,
                )
                overlap > 0 && overlap * 100 >= smallerHeight * HEADER_CONNECTOR_OVERLAP_PERCENT &&
                    (candidateBox.bottomPx - candidateBox.topPx) * 100 >=
                    (hitBox.bottomPx - hitBox.topPx) * HEADER_CONNECTOR_HEIGHT_PERCENT
            }
            if (compatibleHitBoxes.isEmpty()) return@filter false
            val centerX = candidateBox.leftPx +
                (candidateBox.rightPx - candidateBox.leftPx) / 2
            val insideHit = compatibleHitBoxes.any { hitBox ->
                centerX in hitBox.leftPx until hitBox.rightPx
            }
            val insideHeaderCorridor = compatibleHitBoxes.size >= 2 &&
                centerX in compatibleHitBoxes.minOf(InvoiceTextBoundingBox::leftPx) until
                compatibleHitBoxes.maxOf(InvoiceTextBoundingBox::rightPx)
            insideHit || insideHeaderCorridor
        }
        .map(TableAtom::pathKey)
        .toSet()
    val columns = merged.mapIndexed { index, hit ->
        val previous = merged.getOrNull(index - 1)
        val next = merged.getOrNull(index + 1)
        val left = previous?.let { prior -> (prior.centerXPx + hit.centerXPx) / 2 } ?: 0
        val right = next?.let { following -> (hit.centerXPx + following.centerXPx) / 2 } ?: widthPx
        ColumnBand(
            kind = hit.kind,
            leftPx = left.coerceIn(0, widthPx - 1),
            rightPx = right.coerceIn(left + 1, widthPx),
            pageWidthPx = widthPx,
            headerKey = hit.atoms.joinToString(" ", transform = TableAtom::key),
        )
    }
    return TableHeader(
        columns = columns,
        headerBottomPx = bottomPx,
        headerCenterYPx = merged
            .map { hit -> hit.box.topPx + (hit.box.bottomPx - hit.box.topPx) / 2 }
            .sorted()
            .let { centers -> centers[(centers.size - 1) / 2] },
        headerAtomPaths = merged.flatMap(HeaderHit::atoms).map(TableAtom::pathKey).toSet() +
            supplementalHeaderPaths,
        expectedColumns = columns.map(ColumnBand::kind).toSet(),
        weakGeometry = merged.any { hit -> hit.atoms.any(TableAtom::weakGeometry) },
    )
}

private fun VisualRow.headerHits(): List<HeaderHit> {
    val atoms = orderedAtoms
    val sourceLineSequences = atoms
        .groupBy { atom -> atom.blockPosition to atom.linePosition }
        .values
        .filter { sequence -> sequence.size >= MIN_SPLIT_ELEMENT_COUNT }
        .map { sequence ->
            sequence.sortedWith(
                compareBy<TableAtom>(
                    { it.elementPosition ?: Int.MAX_VALUE },
                    { it.box?.leftPx ?: Int.MAX_VALUE },
                ),
            )
        }
    val sequences = (listOf(atoms) + sourceLineSequences)
        .distinctBy { sequence -> sequence.joinToString("|") { atom -> atom.pathKey } }
    val spans = buildList {
        sequences.forEach { sequence ->
            for (start in sequence.indices) {
                for (size in MAX_HEADER_SPAN downTo 1) {
                    if (start + size > sequence.size) continue
                    val selected = sequence.subList(start, start + size)
                    val key = selected.joinToString(" ", transform = TableAtom::key).collapseSpaces()
                    classifyHeader(key)?.let { kind -> add(HeaderHit(kind, selected)) }
                }
            }
        }
    }.distinctBy { hit -> hit.kind to hit.atoms.map(TableAtom::pathKey) }
        .sortedWith(
        compareByDescending<HeaderHit> { hit -> hit.atoms.size }
            .thenBy { hit -> hit.box.leftPx },
    )
    val used = mutableSetOf<String>()
    return spans.filter { hit ->
        val paths = hit.atoms.map(TableAtom::pathKey)
        if (paths.any(used::contains)) {
            false
        } else {
            used += paths
            true
        }
    }
}

private fun List<HeaderHit>.mergeHeaderHits(): List<HeaderHit> = groupBy(HeaderHit::kind)
    .map { (kind, grouped) ->
        HeaderHit(
            kind = kind,
            atoms = grouped.flatMap(HeaderHit::atoms).distinctBy(TableAtom::pathKey),
        )
    }

private fun List<HeaderHit>.isValidHeader(): Boolean {
    val kinds = map(HeaderHit::kind).toSet()
    return TableColumn.DESCRIPTION in kinds && kinds.any { kind ->
        kind == TableColumn.QUANTITY ||
            kind == TableColumn.UNIT_COST ||
            kind == TableColumn.TOTAL
    }
}

private fun classifyHeader(key: String): TableColumn? = when {
    BARCODE_HEADER.matches(key) -> TableColumn.BARCODE
    CODE_HEADER.matches(key) -> TableColumn.CODE
    DESCRIPTION_HEADER.matches(key) -> TableColumn.DESCRIPTION
    QUANTITY_HEADER.matches(key) -> TableColumn.QUANTITY
    UNIT_HEADER.matches(key) -> TableColumn.UNIT
    UNIT_COST_HEADER.matches(key) -> TableColumn.UNIT_COST
    DISCOUNT_HEADER.matches(key) -> TableColumn.DISCOUNT
    TAX_HEADER.matches(key) -> TableColumn.TAX
    TOTAL_HEADER.matches(key) -> TableColumn.TOTAL
    else -> null
}

private fun VisualRow.assignToColumns(columns: List<ColumnBand>): AssignedVisualRow {
    var ambiguous = false
    val assigned = mutableMapOf<TableColumn, MutableList<TableAtom>>()
    orderedAtoms.forEach { atom ->
        val box = atom.box ?: return@forEach
        val overlaps = columns.map { column ->
            val overlap = min(box.rightPx, column.rightPx) - max(box.leftPx, column.leftPx)
            column to max(0, overlap)
        }.sortedByDescending { (_, overlap) -> overlap }
        val center = box.leftPx + (box.rightPx - box.leftPx) / 2
        val containing = columns.firstOrNull { column -> center in column.leftPx until column.rightPx }
        val selected = containing ?: overlaps.firstOrNull()?.first ?: return@forEach
        val bestOverlap = overlaps.firstOrNull()?.second ?: 0
        val secondOverlap = overlaps.getOrNull(1)?.second ?: 0
        if (bestOverlap > 0 && secondOverlap * 100 >= bestOverlap * AMBIGUOUS_OVERLAP_PERCENT) {
            ambiguous = true
        }
        assigned.getOrPut(selected.kind, ::mutableListOf) += atom
    }
    return AssignedVisualRow(
        visualRow = this,
        cells = assigned.mapValues { (_, atoms) ->
            atoms.sortedWith(compareBy<TableAtom>({ it.box?.leftPx ?: Int.MAX_VALUE }, { it.pathKey }))
        },
        ambiguousAssignment = ambiguous,
    )
}

private fun VisualRow.isRepeatedHeader(): Boolean = headerHits().isValidHeader()

private fun VisualRow.isTableTerminator(assigned: AssignedVisualRow): Boolean {
    val key = comparisonText.trim()
    if (key.isEmpty()) return false
    val descriptionKey = assigned.cells[TableColumn.DESCRIPTION].orEmpty()
        .joinToString(" ", transform = TableAtom::key)
        .collapseSpaces()
    if (EXACT_FOOTER_IDENTITY.matches(descriptionKey)) return true
    if (FOOTER_ROW.containsMatchIn(key) && !assigned.hasStrongProductStructure()) return true
    if (SUMMARY_LABEL_CELL.matches(key)) return true
    val descriptionAtoms = assigned.cells[TableColumn.DESCRIPTION].orEmpty()
    val descriptionIsOnlySummary = descriptionAtoms.isNotEmpty() &&
        descriptionAtoms.all { atom -> SUMMARY_LABEL_CELL.matches(atom.key.trim()) }
    if (descriptionIsOnlySummary) return true
    val summaryOutsideProductIdentity = assigned.cells.any { (column, atoms) ->
        column != TableColumn.DESCRIPTION && column != TableColumn.CODE &&
            column != TableColumn.BARCODE &&
            atoms.any { atom -> SUMMARY_LABEL_CELL.matches(atom.key.trim()) }
    }
    val hasProductIdentity = assigned.cells[TableColumn.DESCRIPTION].orEmpty()
        .any { atom -> !SUMMARY_LABEL_CELL.matches(atom.key.trim()) } ||
        TableColumn.CODE in assigned.cells || TableColumn.BARCODE in assigned.cells
    return summaryOutsideProductIdentity && !hasProductIdentity
}

private fun VisualRow.isExcludedMetadataRow(assigned: AssignedVisualRow): Boolean {
    val key = comparisonText.trim()
    val descriptionKey = assigned.cells[TableColumn.DESCRIPTION].orEmpty()
        .joinToString(" ", transform = TableAtom::key)
        .collapseSpaces()
    val joinedRawText = orderedAtoms.joinToString("", transform = TableAtom::rawText)
    if (joinedRawText.looksLikeBareQrPayload()) return true
    if (
        orderedAtoms.any { atom -> atom.rawText.looksLikeBareHash() } &&
        !assigned.hasStrongProductStructure()
    ) {
        return true
    }
    if (EXACT_METADATA_IDENTITY.matches(descriptionKey)) return true
    if (!NON_PRODUCT_METADATA_ROW.containsMatchIn(key)) return false
    if (TableColumn.CODE in assigned.cells || TableColumn.BARCODE in assigned.cells) return false
    val productSignals = assigned.cells.keys.count { column ->
        column == TableColumn.QUANTITY || column == TableColumn.UNIT ||
            column == TableColumn.UNIT_COST || column == TableColumn.DISCOUNT ||
            column == TableColumn.TAX || column == TableColumn.TOTAL
    }
    return productSignals < MIN_METADATA_PRODUCT_SIGNALS
}

private fun AssignedVisualRow.hasStrongProductStructure(): Boolean {
    val hasIdentity = TableColumn.DESCRIPTION in cells || TableColumn.CODE in cells ||
        TableColumn.BARCODE in cells
    if (!hasIdentity) return false
    val productSignals = cells.keys.count { column ->
        column == TableColumn.QUANTITY || column == TableColumn.UNIT ||
            column == TableColumn.UNIT_COST || column == TableColumn.DISCOUNT ||
            column == TableColumn.TAX || column == TableColumn.TOTAL
    }
    return productSignals >= MIN_FOOTER_PRODUCT_SIGNALS
}

private fun String.looksLikeBareQrPayload(): Boolean {
    if (count { character -> character == '|' } < MIN_QR_SEPARATORS) return false
    val digits = count(Char::isDigit)
    return digits >= MIN_QR_DIGITS
}

private fun String.looksLikeBareHash(): Boolean {
    val compact = trim().replace(MULTIPLE_SPACES, "").uppercase(Locale.ROOT)
    return BARE_HASH.matches(compact) && compact.any(Char::isDigit) &&
        compact.any { character -> character in 'A'..'F' }
}

private fun MutableMap<TableColumn, MutableList<TableAtom>>.candidateForText(
    column: TableColumn,
): Candidate<String>? {
    val atoms = this[column].orEmpty()
    if (atoms.isEmpty()) return null
    val source = atoms.toCandidateSource()
    val evidence = PeruvianTextNormalizer.normalize(source)
    val value = evidence.normalizedText.trim().takeIf(String::isNotEmpty) ?: return null
    return Candidate(
        value = value,
        evidence = evidence,
        boundingBox = source.boundingBox,
        confidencePermille = source.confidencePermille,
    )
}

private fun MutableMap<TableColumn, MutableList<TableAtom>>.candidateSource(
    column: TableColumn,
): CandidateSource? = this[column].orEmpty().takeIf(List<TableAtom>::isNotEmpty)?.toCandidateSource()

private fun List<TableAtom>.toCandidateSource(): CandidateSource {
    val ordered = sortedWith(
        compareBy<TableAtom>(
            { it.box?.topPx ?: Int.MAX_VALUE },
            { it.box?.leftPx ?: Int.MAX_VALUE },
            { it.blockPosition ?: Int.MAX_VALUE },
            { it.linePosition ?: Int.MAX_VALUE },
            { it.elementPosition ?: Int.MAX_VALUE },
        ),
    )
    val single = ordered.singleOrNull()
    return CandidateSource(
        rawText = ordered.joinToString(" ", transform = TableAtom::rawText),
        boundingBox = ordered.mapNotNull(TableAtom::box).unionBoxes(),
        confidencePermille = ordered.mapNotNull(TableAtom::confidencePermille).minOrNull(),
        sourceImageId = ordered.first().page.sourceImageId,
        pageIndex = ordered.first().page.pageIndex,
        cornerPoints = single?.evidence?.cornerPoints.orEmpty(),
        blockPosition = single?.blockPosition,
        linePosition = single?.linePosition,
        elementPosition = single?.elementPosition,
        clockwiseAngleTenths = single?.evidence?.clockwiseAngleTenths,
    )
}

private fun CandidateSource.parseClosedMoney(currency: CurrencyCode?): ParsedWithContext<Money> {
    val normalizedText = normalizedCellText()
    if (!CLOSED_MONEY_CELL.matches(normalizedText)) return ParsedWithContext(null, false)
    val explicitDollarCurrency = USD_MARKED_CELL.matchEntire(normalizedText)?.let { USD }
    val parsed = PeruvianValueParser.parseMoney(this, explicitDollarCurrency ?: currency)
    val promoted = parsed.promoteTrustedCurrency()
    return if (explicitDollarCurrency != null) {
        promoted.copy(usedCurrencyContext = false)
    } else {
        promoted
    }
}

private fun CandidateSource.parseClosedUnitCost(currency: CurrencyCode?): ParsedWithContext<com.facturastock.app.domain.model.UnitCost> {
    val normalizedText = normalizedCellText()
    if (!CLOSED_MONEY_CELL.matches(normalizedText)) return ParsedWithContext(null, false)
    val explicitDollarCurrency = USD_MARKED_CELL.matchEntire(normalizedText)?.let { USD }
    val parsed = PeruvianValueParser.parseUnitCost(this, explicitDollarCurrency ?: currency)
    val promoted = parsed.promoteTrustedCurrency()
    return if (explicitDollarCurrency != null) {
        promoted.copy(usedCurrencyContext = false)
    } else {
        promoted
    }
}

private fun CandidateSource.parseAdjustment(
    currency: CurrencyCode?,
    expectsRate: Boolean,
): ParsedWithContext<InvoiceLineAdjustment> {
    if ('%' in normalizedCellText() || expectsRate) {
        return ParsedWithContext(parseRateAdjustment(percentMayBeOmitted = expectsRate), false)
    }
    val money = parseClosedMoney(currency)
    return ParsedWithContext(
        candidate = money.candidate?.mapValues { value -> InvoiceLineAdjustment.Amount(value) },
        usedCurrencyContext = money.usedCurrencyContext,
    )
}

private fun CandidateSource.isClosedNumberCell(): Boolean =
    CLOSED_NUMBER_CELL.matches(normalizedCellText())

private fun Candidate<Quantity>.markUnresolvedWhenIdentifierLike(
    source: CandidateSource,
): Candidate<Quantity> {
    val normalized = source.normalizedCellText()
    val looksLikeIdentifier = GTIN_LIKE_NUMBER.matches(normalized) ||
        RucValidator.isWellFormed(normalized) && RucValidator.hasValidChecksum(normalized)
    if (!looksLikeIdentifier) return this
    val possibleValues = (listOfNotNull(value) + alternatives).distinct()
    return Candidate(
        value = null,
        evidence = evidence,
        boundingBox = boundingBox,
        confidencePermille = confidencePermille,
        warnings = (warnings + CandidateWarning.UNSUPPORTED_NUMERIC_VALUE).distinct(),
        alternatives = possibleValues,
    )
}

private fun CandidateSource.normalizedCellText(): String =
    PeruvianTextNormalizer.normalize(this).normalizedText.trim()

private fun CandidateSource.parseRateAdjustment(
    percentMayBeOmitted: Boolean,
): Candidate<InvoiceLineAdjustment>? {
    val evidence = PeruvianTextNormalizer.normalize(this)
    val grammar = if (percentMayBeOmitted) CLOSED_RATE_CELL else CLOSED_PERCENT_CELL
    val match = grammar.matchEntire(evidence.comparisonText.trim()) ?: return null
    val rawToken = match.groupValues[1]
    val correctedToken = rawToken.map { character ->
        when (character) {
            'O' -> '0'
            'I', 'L', '|' -> '1'
            else -> character
        }
    }.joinToString("")
    val correction = if (rawToken != correctedToken) {
        AppliedOcrCorrection(
            originalFragment = rawToken,
            correctedFragment = correctedToken,
            reason = OcrCorrectionReason.NUMERIC_CONFUSABLE_IN_NUMERIC_CONTEXT,
        )
    } else {
        null
    }
    val separators = correctedToken.filter { character -> character == ',' || character == '.' }
    if (separators.toSet().size > 1 || separators.length > 1) return null
    val warnings = mutableListOf<CandidateWarning>()
    val interpretations = when {
        separators.isEmpty() -> listOfNotNull(correctedToken.toExactDecimalOrNull())
        else -> {
            val separator = separators.single()
            val fractionDigits = correctedToken.substringAfter(separator).length
            val decimal = correctedToken.replace(separator, '.').toExactDecimalOrNull()
            if (fractionDigits == GROUPING_FRACTION_LENGTH) {
                warnings += CandidateWarning.AMBIGUOUS_NUMBER_SEPARATOR
                listOfNotNull(decimal, correctedToken.replace(separator.toString(), "").toExactDecimalOrNull())
            } else {
                listOfNotNull(decimal)
            }
        }
    }
    val rates = interpretations.distinct().mapNotNull { percent ->
        runCatching { TaxRate(percent) }.getOrNull()
    }.map(InvoiceLineAdjustment::Rate)
    if (correction != null) warnings += CandidateWarning.OCR_CORRECTION_APPLIED
    if (rates.isEmpty()) warnings += CandidateWarning.UNSUPPORTED_NUMERIC_VALUE
    val distinctWarnings = warnings.distinct()
    val unresolved = distinctWarnings.any(CandidateWarning::requiresReview) || rates.size != 1
    val correctedEvidence = evidence.copy(
        corrections = listOfNotNull(correction),
    )
    return Candidate(
        value = rates.singleOrNull().takeUnless { unresolved },
        evidence = correctedEvidence,
        boundingBox = boundingBox,
        confidencePermille = confidencePermille,
        warnings = distinctWarnings,
        alternatives = if (unresolved) rates else emptyList(),
    )
}

private fun String.toExactDecimalOrNull(): BigDecimal? {
    if (length > MAX_RATE_TOKEN_LENGTH) return null
    return runCatching { BigDecimal(this) }.getOrNull()
        ?.takeIf { decimal -> decimal.scale() <= MAX_RATE_SCALE }
}

private fun <T : Any> Candidate<T>?.promoteTrustedCurrency(): ParsedWithContext<T> {
    val candidate = this ?: return ParsedWithContext(null, false)
    val fromContext = CandidateWarning.CURRENCY_FROM_CONTEXT in candidate.warnings
    if (!fromContext) return ParsedWithContext(candidate, false)
    val remainingWarnings = candidate.warnings - CandidateWarning.CURRENCY_FROM_CONTEXT
    val soleAlternative = candidate.alternatives.singleOrNull()
    val promoted = if (
        candidate.value == null && soleAlternative != null &&
        remainingWarnings.none(CandidateWarning::requiresReview)
    ) {
        Candidate(
            value = soleAlternative,
            evidence = candidate.evidence,
            boundingBox = candidate.boundingBox,
            confidencePermille = candidate.confidencePermille,
            warnings = remainingWarnings,
        )
    } else {
        Candidate(
            value = candidate.value,
            evidence = candidate.evidence,
            boundingBox = candidate.boundingBox,
            confidencePermille = candidate.confidencePermille,
            warnings = remainingWarnings,
            alternatives = candidate.alternatives,
        )
    }
    return ParsedWithContext(promoted, true)
}

private fun <T : Any, R : Any> Candidate<T>.mapValues(transform: (T) -> R): Candidate<R> = Candidate(
    value = value?.let(transform),
    evidence = evidence,
    boundingBox = boundingBox,
    confidencePermille = confidencePermille,
    warnings = warnings,
    alternatives = alternatives.map(transform).distinct(),
)

private fun InvoiceTextGeometry.effectiveBox(): InvoiceTextBoundingBox? = boundingBox
    ?: cornerPoints.takeIf { points -> points.size == CORNER_COUNT }?.let { points ->
        InvoiceTextBoundingBox(
            leftPx = points.minOf { point -> point.xPx },
            topPx = points.minOf { point -> point.yPx },
            rightPx = points.maxOf { point -> point.xPx } + 1,
            bottomPx = points.maxOf { point -> point.yPx } + 1,
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

private fun List<Int>.medianOr(default: Int): Int {
    if (isEmpty()) return default
    val ordered = sorted()
    return ordered[ordered.size / 2]
}

private fun String.toSearchKey(): String {
    val decomposed = Normalizer.normalize(this, Normalizer.Form.NFD)
    return decomposed
        .filterNot { character -> Character.getType(character) == Character.NON_SPACING_MARK.toInt() }
        .uppercase(Locale.ROOT)
        .replace(HEADER_PUNCTUATION, " ")
        .collapseSpaces()
        .trim()
}

private fun String.collapseSpaces(): String = trim().replace(MULTIPLE_SPACES, " ")

private fun String.looksLikeProductDescription(): Boolean {
    val key = toSearchKey()
    if (key.length < MIN_DESCRIPTION_LENGTH) return false
    if (looksLikeBareHash()) return false
    if (isTableTerminatorText(key)) return false
    return key.any { character -> character in 'A'..'Z' }
}

private fun isTableTerminatorText(key: String): Boolean =
    SUMMARY_LABEL_CELL.matches(key) || FOOTER_ROW.containsMatchIn(key)

private val BARCODE_HEADER = Regex("^(?:CODIGO DE BARRAS|COD BARRAS|BARRAS|BARCODE|EAN|GTIN|UPC)$")
private val CODE_HEADER = Regex("^(?:CODIGO|COD|SKU|ITEM|ITEM CODIGO)$")
private val DESCRIPTION_HEADER = Regex(
    "^(?:DESCRIPCION|DETALLE|PRODUCTO|ARTICULO|CONCEPTO|GLOSA|DENOMINACION|" +
        "BIEN SERVICIO|BIEN O SERVICIO|DESCRIPCION DEL PRODUCTO|DESCRIPCION DEL BIEN)$",
)
private val QUANTITY_HEADER = Regex("^(?:CANTIDAD|CANT|CANTD|CTD|QTY|CANT PEDIDA)$")
private val UNIT_HEADER = Regex("^(?:UNIDAD|UNID|UND|UM|U M|U MEDIDA|UN|MEDIDA)$")
private val UNIT_COST_HEADER = Regex(
    "^(?:P UNIT|P UNITARIO|P U|PUNIT|PRECIO|PRECIO UNITARIO|PRECIO UNIT|PREC UNIT|" +
        "VALOR UNITARIO|VALOR UNIT|V UNITARIO|V UNIT|COSTO|COSTO UNITARIO)$",
)
private val DISCOUNT_HEADER = Regex("^(?:DSCTO|DCTO|DESC|DESCUENTO|DESCUENTO %|DSCTO %)$")
private val TAX_HEADER = Regex("^(?:IGV|IGV %|IMPUESTO|IMPUESTO %)$")
private val TOTAL_HEADER = Regex(
    "^(?:IMPORTE|TOTAL|IMP TOTAL|IMPORTE TOTAL|VALOR TOTAL|VALOR VENTA)$",
)

private val SUMMARY_LABEL_CELL = Regex(
    "^(?:OP(?:ERACION(?:ES)?)? (?:GRAVADA|GRAVADAS|EXONERADA|EXONERADAS|INAFECTA|INAFECTAS)|" +
        "SUBTOTAL|TOTAL(?: A PAGAR)?|IMPORTE TOTAL|BASE IMPONIBLE|IGV(?: [0-9OIL|.,]+ ?%)?|" +
        "ISC|ICBPER|PERCEPCION|RETENCION|DETRACCION|ANTICIPO|REDONDEO|VUELTO|SALDO|" +
        "DESCUENTO GLOBAL|SON|TOTAL EN LETRAS)" +
        "(?: (?:S|PEN|USD)? ?[0-9OIL|]+(?:[., ][0-9OIL|]+)*)?$",
)
private val FOOTER_ROW = Regex(
    "^(?:QR(?:\\b| )|HASH(?:\\b| )|SUNAT(?:\\b| )|WWW\\.|HTTPS?://|" +
        "REPRESENTACION IMPRESA|FIRMA(?:\\b| )|OBSERVACION(?:ES)?(?:\\b| )|" +
        "PAGINA [0-9]+|CONTINUA(?:\\b|$)|CLIENTE(?:\\b| )|DATOS DEL CLIENTE)",
)
private val EXACT_FOOTER_IDENTITY = Regex(
    "^(?:QR|HASH|SUNAT|REPRESENTACION IMPRESA|FIRMA|OBSERVACION(?:ES)?|" +
        "PAGINA [0-9]+(?: DE [0-9]+)?|CONTINUA|CLIENTE|DATOS DEL CLIENTE)$",
)
private val EXACT_METADATA_IDENTITY = Regex(
    "^(?:RUC|DNI|CE|EMISOR|DIRECCION|FECHA|SERIE|NRO|NUMERO|MONEDA)$",
)
private val NON_PRODUCT_METADATA_ROW = Regex(
    "^(?:RUC|DNI|CE|CLIENTE|EMISOR|DIRECCION|FECHA|SERIE|NRO|NUMERO|MONEDA|" +
        "LOTE|GUIA|ORDEN)(?:\\b|\\s|:)",
)
private val BARE_HASH = Regex("^[A-F0-9]{24,128}$")
private val HEADER_CONNECTOR = Regex(
    "^(?:DE|DEL|LA|LAS|LOS|Y|O|UNITARIO|MEDIDA|BARRAS|SERVICIO)$",
)
private val CLOSED_NUMBER_CELL = Regex("^[0-9OIL|]+(?:[., ][0-9OIL|]+)*$")
private val GTIN_LIKE_NUMBER = Regex("^(?:[0-9]{8}|[0-9]{12}|[0-9]{13}|[0-9]{14})$")
private val CLOSED_MONEY_CELL = Regex(
    "^(?:(?:S/|PEN|USD|US\\$)\\s*)?[0-9OIL|]+(?:[., ][0-9OIL|]+)*" +
        "(?:\\s*(?:S/|PEN|USD|US\\$))?$",
    RegexOption.IGNORE_CASE,
)
private val USD_MARKED_CELL = Regex(
    "^(?:US\\$\\s*[0-9OIL|]+(?:[., ][0-9OIL|]+)*|" +
        "[0-9OIL|]+(?:[., ][0-9OIL|]+)*\\s*US\\$)$",
    RegexOption.IGNORE_CASE,
)
private val CLOSED_PERCENT_CELL = Regex(
    "^(?:(?:DSCTO|DCTO|DESC(?:UENTO)?|IGV|IMPUESTO)\\s*[:=]?\\s*)?" +
        "([0-9OIL|]+(?:[.,][0-9OIL|]+)?)\\s*%$",
)
private val CLOSED_RATE_CELL = Regex(
    "^(?:(?:DSCTO|DCTO|DESC(?:UENTO)?|IGV|IMPUESTO)\\s*[:=]?\\s*)?" +
        "([0-9OIL|]+(?:[.,][0-9OIL|]+)?)\\s*%?$",
)
private val HEADER_PUNCTUATION = Regex("[._:/\\-]+")
private val MULTIPLE_SPACES = Regex("\\s+")

private const val CORNER_COUNT = 4
private const val MIN_SPLIT_ELEMENT_COUNT = 2
private const val MIN_VERTICAL_OVERLAP_PERCENT = 35
private const val MIN_ROW_CENTER_DISTANCE_PX = 6
private const val AMBIGUOUS_OVERLAP_PERCENT = 80
private const val MAX_HEADER_SPAN = 3
private const val HEADER_CONNECTOR_OVERLAP_PERCENT = 70
private const val HEADER_CONNECTOR_HEIGHT_PERCENT = 70
private const val DEFAULT_ROW_HEIGHT_PX = 32
private const val MIN_CONTINUATION_GAP_PX = 12
private const val GROUPING_FRACTION_LENGTH = 3
private const val MAX_RATE_TOKEN_LENGTH = 16
private const val MAX_RATE_SCALE = 4
private const val MIN_DESCRIPTION_LENGTH = 2
private const val MIN_ANCHORS_WITHOUT_DESCRIPTION = 2
private const val MIN_QR_SEPARATORS = 2
private const val MIN_QR_DIGITS = 8
private const val MIN_METADATA_PRODUCT_SIGNALS = 1
private const val MIN_FOOTER_PRODUCT_SIGNALS = 1

private val USD: CurrencyCode = CurrencyCode.of("USD")
