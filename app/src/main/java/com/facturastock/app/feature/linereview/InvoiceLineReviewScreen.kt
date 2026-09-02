package com.facturastock.app.feature.linereview

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.facturastock.app.R
import com.facturastock.app.domain.model.InventoryTaxTreatment
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.Action
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.Confidence
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.DeletedLine
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.Editor
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.Field
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.FieldError
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.FieldId
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.Line
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.State
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.Summary
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.SummaryIssue
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.ValueOrigin
import com.facturastock.app.ui.components.FacturaStockDialog
import com.facturastock.app.ui.components.FacturaStockPrimaryButton
import com.facturastock.app.ui.components.FacturaStockSecondaryButton
import com.facturastock.app.ui.components.RecoverableError
import com.facturastock.app.ui.format.currencyLabelForDisplay
import com.facturastock.app.ui.format.formatCurrencyAmountForDisplay
import com.facturastock.app.ui.theme.FacturaStockDesign
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * Lista movil de hasta cien lineas. Los controles y tarjetas son perezosos; el resumen y la
 * restauracion quedan fuera de la lista para seguir visibles durante desplazamiento y teclado.
 */
@Composable
fun InvoiceLineReviewScreen(
    state: State,
    onAction: (Action) -> Unit,
    modifier: Modifier = Modifier,
    requestedScrollLineId: LineId? = null,
    onRequestedScrollHandled: () -> Unit = {},
    onCardPresentationsReady: () -> Unit = {},
) {
    val spacing = FacturaStockDesign.spacing
    val listState = rememberLazyListState()
    val resources = LocalResources.current
    val configuration = LocalConfiguration.current
    val cardCopy = remember(resources, configuration) { resources.lineCardCopy() }
    val cardLocale = remember(configuration) {
        configuration.locales[0] ?: Locale.ROOT
    }
    val currentOnCardPresentationsReady by rememberUpdatedState(onCardPresentationsReady)
    val requestedCardFocusRequester = remember { FocusRequester() }
    var focusTargetLineId by remember { mutableStateOf<LineId?>(null) }
    var announceLineId by remember { mutableStateOf<LineId?>(null) }
    val warningIconPainter = painterResource(R.drawable.ic_warning)
    val deleteIconPainter = painterResource(R.drawable.ic_delete)
    val moreActionsIconPainter = painterResource(R.drawable.ic_more_vert)
    val cardIcons = remember(warningIconPainter, deleteIconPainter, moreActionsIconPainter) {
        LineCardIcons(
            warning = warningIconPainter,
            delete = deleteIconPainter,
            moreActions = moreActionsIconPainter,
        )
    }
    val orderedLines = remember(state.lines) { state.orderedLines }
    val pendingCount = remember(state.lines) { state.lines.count(Line::isPending) }
    val visibleLines = remember(orderedLines, state.searchQuery, state.pendingOnly) {
        state.filterOrderedLines(orderedLines)
    }
    val globalIndexById = remember(orderedLines) {
        orderedLines.mapIndexed { index, line -> line.lineId to index }.toMap()
    }
    val cardPresentationsState = produceState<Map<LineId, LineCardPresentationEntry>>(
        emptyMap(),
        orderedLines,
        state.currencyLabel,
        cardCopy,
        cardLocale,
    ) {
        val previous = value
        value = withContext(Dispatchers.Default) {
            val prepared = LinkedHashMap<LineId, LineCardPresentationEntry>(orderedLines.size)
            orderedLines.forEachIndexed { index, line ->
                currentCoroutineContext().ensureActive()
                if (index % PRESENTATION_CANCELLATION_BATCH_SIZE == 0) yield()
                val reusable = previous[line.lineId]?.takeIf { entry ->
                    entry.matches(line, state.currencyLabel, cardCopy, cardLocale)
                }
                prepared[line.lineId] = reusable ?: LineCardPresentationEntry(
                    source = line,
                    currencyLabel = state.currencyLabel,
                    copy = cardCopy,
                    locale = cardLocale,
                    presentation = line.toCardPresentation(
                        state.currencyLabel,
                        cardCopy,
                        cardLocale,
                    ),
                )
            }
            prepared
        }
    }
    val precomputedCardPresentations = cardPresentationsState.value
    val cardPresentationsReady = remember(
        precomputedCardPresentations,
        orderedLines,
        state.currencyLabel,
        cardCopy,
        cardLocale,
    ) {
        orderedLines.all { line ->
            precomputedCardPresentations[line.lineId]
                ?.matches(line, state.currencyLabel, cardCopy, cardLocale) == true
        }
    }

    LaunchedEffect(
        cardPresentationsReady,
        orderedLines,
        state.currencyLabel,
        cardCopy,
        cardLocale,
    ) {
        if (cardPresentationsReady) currentOnCardPresentationsReady()
    }
    // Chrome común a las cien filas: evita crear CardColors y BorderStroke por cada item que
    // entra en el viewport durante un desplazamiento largo.
    val lineCardShape = MaterialTheme.shapes.medium
    val lineCardColors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    )
    val pendingLineCardBorder = BorderStroke(
        spacing.borderThin,
        FacturaStockDesign.semanticColors.warning,
    )
    val reviewedLineCardBorder = BorderStroke(
        spacing.borderThin,
        MaterialTheme.colorScheme.outlineVariant,
    )

    LaunchedEffect(
        requestedScrollLineId,
        visibleLines,
        state.searchQuery,
        state.pendingOnly,
    ) {
        val requestedId = requestedScrollLineId ?: return@LaunchedEffect
        val visibleIndex = visibleLines.indexOfFirst { line -> line.lineId == requestedId }
        if (visibleIndex >= 0) {
            focusTargetLineId = requestedId
            announceLineId = requestedId
            try {
                // El encabezado es el primer item estable de la LazyColumn.
                val targetIndex = visibleIndex + 1
                InvoiceLineReviewScrollPolicy.stagingIndex(
                    currentIndex = listState.firstVisibleItemIndex,
                    targetIndex = targetIndex,
                    lastIndex = visibleLines.size,
                )?.let { stagingIndex ->
                    // Un salto largo no debe componer cada tarjeta intermedia. Solo se anima la cola.
                    listState.scrollToItem(stagingIndex)
                }
                listState.animateScrollToItem(targetIndex)
                snapshotFlow {
                    cardPresentationsState.value[requestedId]?.matches(
                        line = visibleLines[visibleIndex],
                        currencyLabel = state.currencyLabel,
                        copy = cardCopy,
                        locale = cardLocale,
                    ) == true
                }.first { ready -> ready }
                // Espera a que la rama de tarjeta real sustituya al placeholder y reajusta el
                // destino tras el cambio de altura antes de solicitar foco accesible.
                withFrameNanos { }
                listState.animateScrollToItem(targetIndex)
                requestedCardFocusRequester.requestFocus()
                // El requester/focusable queda anclado; solo el anuncio es de un uso.
                announceLineId = null
                onRequestedScrollHandled()
            } finally {
                // También evita dejar una región viva si la solicitud cambia a mitad del salto.
                if (announceLineId == requestedId) announceLineId = null
            }
        } else if (state.searchQuery.isNotBlank()) {
            // Una búsqueda no debe esconder la corrección que acaba de solicitar el ViewModel.
            onAction(Action.SearchChanged(""))
        } else if (state.pendingOnly) {
            onAction(Action.PendingFilterToggled)
        } else {
            onRequestedScrollHandled()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .testTag(InvoiceLineReviewTestTags.SCREEN),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag(InvoiceLineReviewTestTags.LIST),
            contentPadding = PaddingValues(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            item(
                key = "line_review_controls",
                contentType = "controls",
            ) {
                LineReviewControls(
                    saveFailure = state.saveFailure,
                    failure = state.failure,
                    searchQuery = state.searchQuery,
                    pendingOnly = state.pendingOnly,
                    pendingCount = pendingCount,
                    lineCount = state.lines.size,
                    canAddLine = state.canAddLine,
                    onAction = onAction,
                )
            }

            if (visibleLines.isEmpty()) {
                item(
                    key = "line_review_empty",
                    contentType = "empty",
                ) {
                    EmptyLinesContent(
                        filtered = state.lines.isNotEmpty(),
                        canAddLine = state.canAddLine,
                        onAdd = { onAction(Action.AddLine) },
                    )
                }
            } else {
                items(
                    items = visibleLines,
                    key = { line -> line.lineId.value },
                    contentType = { "invoice_line_card" },
                ) { line ->
                    val globalIndex = globalIndexById[line.lineId] ?: -1
                    val presentation = precomputedCardPresentations[line.lineId]
                        ?.takeIf { entry ->
                            entry.matches(line, state.currencyLabel, cardCopy, cardLocale)
                        }
                        ?.presentation
                    val focusRequester = requestedCardFocusRequester.takeIf {
                        focusTargetLineId == line.lineId
                    }
                    if (presentation == null) {
                        LineCardPreparationPlaceholder(
                            lineId = line.lineId,
                            loadingDescription = cardCopy.loadingDescription,
                            cardShape = lineCardShape,
                            cardColors = lineCardColors,
                            cardBorder = reviewedLineCardBorder,
                            focusRequester = focusRequester,
                        )
                    } else {
                        InvoiceLineCard(
                            line = line,
                            presentation = presentation,
                            copy = cardCopy,
                            icons = cardIcons,
                            cardShape = lineCardShape,
                            cardColors = lineCardColors,
                            cardBorder = if (presentation.isPending) {
                                pendingLineCardBorder
                            } else {
                                reviewedLineCardBorder
                            },
                            canMoveUp = globalIndex > 0,
                            canMoveDown = globalIndex in 0 until orderedLines.lastIndex,
                            focusRequester = focusRequester,
                            announceAsLiveRegion = announceLineId == line.lineId,
                            onAction = onAction,
                        )
                    }
                }
            }
        }

        state.restorableDeletion?.let { deletion ->
            RestoreDeletionBar(deletion = deletion, onAction = onAction)
        }

        LineReviewSummaryBar(
            summary = state.summary,
            continueAttempted = state.continueAttempted,
            pendingCount = pendingCount,
            isSaving = state.isSaving,
            isContinueActionEnabled = state.isContinueActionEnabled,
            onAction = onAction,
        )
    }

    state.editor?.let { editor ->
        LineEditorDialog(
            editor = editor,
            summary = state.summary,
            currencyLabel = state.currencyLabel,
            storageFull = state.failure == InvoiceLineReviewContract.Failure.STORAGE_FULL,
            onAction = onAction,
        )
    }

    state.deletionCandidate?.let { line ->
        DeleteLineDialog(line = line, onAction = onAction)
    }
}

@Composable
private fun LineReviewControls(
    saveFailure: Boolean,
    failure: InvoiceLineReviewContract.Failure?,
    searchQuery: String,
    pendingOnly: Boolean,
    pendingCount: Int,
    lineCount: Int,
    canAddLine: Boolean,
    onAction: (Action) -> Unit,
) {
    val spacing = FacturaStockDesign.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        if (saveFailure) {
            RecoverableError(
                title = stringResource(R.string.line_review_save_error_title),
                message = stringResource(
                    if (failure == InvoiceLineReviewContract.Failure.STORAGE_FULL) {
                        R.string.storage_full_recoverable_message
                    } else {
                        R.string.line_review_save_error_message
                    },
                ),
                actionLabel = stringResource(R.string.line_review_retry_save),
                onAction = { onAction(Action.RetrySave) },
            )
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { query -> onAction(Action.SearchChanged(query)) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(InvoiceLineReviewTestTags.SEARCH),
            label = { Text(stringResource(R.string.line_review_search_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Search,
            ),
            trailingIcon = if (searchQuery.isNotEmpty()) {
                {
                    TextButton(onClick = { onAction(Action.SearchChanged("")) }) {
                        Text(stringResource(R.string.line_review_search_clear))
                    }
                }
            } else {
                null
            },
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            val filterStateDescription = if (pendingOnly) {
                stringResource(R.string.line_review_pending_count, pendingCount, lineCount)
            } else {
                stringResource(R.string.line_review_total_count, lineCount)
            }
            FilterChip(
                selected = pendingOnly,
                onClick = { onAction(Action.PendingFilterToggled) },
                label = { Text(stringResource(R.string.line_review_pending_filter)) },
                leadingIcon = if (pendingCount > 0) {
                    {
                        Icon(
                            painter = painterResource(R.drawable.ic_warning),
                            contentDescription = null,
                            modifier = Modifier.size(spacing.iconSmall),
                        )
                    }
                } else {
                    null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(InvoiceLineReviewTestTags.PENDING_FILTER)
                    .semantics {
                        stateDescription = filterStateDescription
                    },
            )
            FacturaStockSecondaryButton(
                text = stringResource(R.string.line_review_add_line),
                onClick = { onAction(Action.AddLine) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(InvoiceLineReviewTestTags.ADD_LINE),
                enabled = canAddLine,
            )
        }

        if (!canAddLine && lineCount >= InvoiceLineReviewContract.MAX_LINES) {
            Text(
                text = stringResource(R.string.line_review_limit_reached),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Text(
            text = if (pendingCount == 0) {
                stringResource(R.string.line_review_all_reviewed)
            } else {
                stringResource(
                    R.string.line_review_pending_count,
                    pendingCount,
                    lineCount,
                )
            },
            color = if (pendingCount == 0) {
                FacturaStockDesign.semanticColors.success
            } else {
                FacturaStockDesign.semanticColors.warning
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun EmptyLinesContent(
    filtered: Boolean,
    canAddLine: Boolean,
    onAdd: () -> Unit,
) {
    val spacing = FacturaStockDesign.spacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = spacing.xl)
            .testTag(InvoiceLineReviewTestTags.EMPTY_FILTER),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(
                if (filtered) {
                    R.string.line_review_filter_empty_title
                } else {
                    R.string.line_review_empty_title
                },
            ),
            modifier = Modifier.semantics { heading() },
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(modifier = Modifier.height(spacing.xs))
        Text(
            text = stringResource(
                if (filtered) {
                    R.string.line_review_filter_empty_message
                } else {
                    R.string.line_review_empty_message
                },
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
        if (!filtered) {
            Spacer(modifier = Modifier.height(spacing.md))
            FacturaStockPrimaryButton(
                text = stringResource(R.string.line_review_add_line),
                onClick = onAdd,
                modifier = Modifier.testTag(InvoiceLineReviewTestTags.EMPTY_ADD_LINE),
                enabled = canAddLine,
            )
        }
    }
}

@Immutable
private data class LineCardCopy(
    val loadingDescription: String,
    val descriptionLabel: String,
    val descriptionPending: String,
    val valuePending: String,
    val metricQuantity: String,
    val metricCost: String,
    val metricIgv: String,
    val metricTotal: String,
    val taxExemptValue: String,
    val pendingStatus: String,
    val reviewedStatus: String,
    val confidenceHigh: String,
    val confidenceMedium: String,
    val confidenceLow: String,
    val confidenceUnknown: String,
    val savingLine: String,
    val confirmReviewed: String,
    val moveUp: String,
    val moveDown: String,
    val delete: String,
    val edit: String,
    val lineNumberFormat: String,
    val quantityUnitFormat: String,
    val metricValueFormat: String,
    val currencyAmountFormat: String,
    val confidencePercentFormat: String,
    val confirmAccessibilityFormat: String,
    val moreActionsAccessibilityFormat: String,
    val moveUpAccessibilityFormat: String,
    val moveDownAccessibilityFormat: String,
    val deleteAccessibilityFormat: String,
    val editAccessibilityFormat: String,
)

@Stable
private class LineCardIcons(
    val warning: Painter,
    val delete: Painter,
    val moreActions: Painter,
)

@Immutable
private data class LineCardPresentation(
    val lineNumber: String,
    val description: String,
    val quantityMetric: String,
    val costMetric: String,
    val igvMetric: String,
    val totalMetric: String,
    val confidence: String,
    val isPending: Boolean,
    val confidenceWarning: Boolean,
    val canConfirmReviewed: Boolean,
    val pendingStatus: String,
    val spokenCard: String,
    val confirmAccessibility: String,
    val moreActionsAccessibility: String,
    val moveUpAccessibility: String,
    val moveDownAccessibility: String,
    val deleteAccessibility: String,
    val editAccessibility: String,
)

private data class LineCardPresentationEntry(
    val source: Line,
    val currencyLabel: String,
    /** La identidad cambia con Configuration y evita reutilizar traducciones anteriores. */
    val copy: LineCardCopy,
    val locale: Locale,
    val presentation: LineCardPresentation,
) {
    fun matches(
        line: Line,
        currencyLabel: String,
        copy: LineCardCopy,
        locale: Locale,
    ): Boolean =
        this.currencyLabel == currencyLabel &&
            this.copy === copy &&
            this.locale == locale &&
            (source === line || source.hasSameCardPresentationAs(line))
}

private fun Line.hasSameCardPresentationAs(other: Line): Boolean =
    position == other.position &&
        fields == other.fields &&
        confidence == other.confidence &&
        confidencePercent == other.confidencePercent &&
        requiresReview == other.requiresReview &&
        confirmedByUser == other.confirmedByUser &&
        taxTreatment == other.taxTreatment &&
        cardProjection == other.cardProjection

/** Resuelve una sola vez por pantalla el texto estático que comparten hasta cien tarjetas. */
private fun Resources.lineCardCopy(): LineCardCopy = LineCardCopy(
    loadingDescription = getString(R.string.line_review_loading),
    descriptionLabel = getString(R.string.line_review_description_label),
    descriptionPending = getString(R.string.line_review_description_pending),
    valuePending = getString(R.string.line_review_value_pending),
    metricQuantity = getString(R.string.line_review_metric_quantity),
    metricCost = getString(R.string.line_review_metric_cost),
    metricIgv = getString(R.string.line_review_metric_igv),
    metricTotal = getString(R.string.line_review_metric_total),
    taxExemptValue = getString(R.string.line_review_tax_exempt_value),
    pendingStatus = getString(R.string.line_review_pending_status),
    reviewedStatus = getString(R.string.line_review_reviewed_status),
    confidenceHigh = getString(R.string.line_review_confidence_high),
    confidenceMedium = getString(R.string.line_review_confidence_medium),
    confidenceLow = getString(R.string.line_review_confidence_low),
    confidenceUnknown = getString(R.string.line_review_confidence_unknown),
    savingLine = getString(R.string.line_review_saving_line),
    confirmReviewed = getString(R.string.line_review_confirm_reviewed),
    moveUp = getString(R.string.line_review_move_up),
    moveDown = getString(R.string.line_review_move_down),
    delete = getString(R.string.line_review_delete),
    edit = getString(R.string.line_review_edit),
    lineNumberFormat = getString(R.string.line_review_line_number),
    quantityUnitFormat = getString(R.string.line_review_quantity_unit_value),
    metricValueFormat = getString(R.string.line_review_metric_value),
    currencyAmountFormat = getString(R.string.line_review_currency_amount),
    confidencePercentFormat = getString(R.string.line_review_confidence_percent),
    confirmAccessibilityFormat = getString(R.string.line_review_confirm_accessibility),
    moreActionsAccessibilityFormat = getString(
        R.string.line_review_more_actions_accessibility,
    ),
    moveUpAccessibilityFormat = getString(R.string.line_review_move_up_accessibility),
    moveDownAccessibilityFormat = getString(R.string.line_review_move_down_accessibility),
    deleteAccessibilityFormat = getString(R.string.line_review_delete_accessibility),
    editAccessibilityFormat = getString(R.string.line_review_edit_accessibility),
)

@Composable
private fun LineCardPreparationPlaceholder(
    lineId: LineId,
    loadingDescription: String,
    cardShape: Shape,
    cardColors: CardColors,
    cardBorder: BorderStroke,
    focusRequester: FocusRequester?,
) {
    val spacing = FacturaStockDesign.spacing
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(InvoiceLineReviewTestTags.card(lineId))
            .semantics {
                contentDescription = loadingDescription
            }
            .requestedFocus(focusRequester),
        shape = cardShape,
        colors = cardColors,
        border = cardBorder,
    ) {
        Text(
            text = loadingDescription,
            modifier = Modifier
                .padding(spacing.md)
                .semantics { hideFromAccessibility() },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun InvoiceLineCard(
    line: Line,
    presentation: LineCardPresentation,
    copy: LineCardCopy,
    icons: LineCardIcons,
    cardShape: Shape,
    cardColors: CardColors,
    cardBorder: BorderStroke,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    focusRequester: FocusRequester?,
    announceAsLiveRegion: Boolean,
    onAction: (Action) -> Unit,
) {
    val spacing = FacturaStockDesign.spacing

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(InvoiceLineReviewTestTags.card(line.lineId))
            .semantics {
                contentDescription = presentation.spokenCard
                stateDescription = presentation.pendingStatus
                if (announceAsLiveRegion) liveRegion = LiveRegionMode.Polite
            }
            .requestedFocus(focusRequester),
        shape = cardShape,
        colors = cardColors,
        border = cardBorder,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { hideFromAccessibility() },
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = presentation.lineNumber,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        text = presentation.description,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                ConfidenceBadge(
                    label = presentation.confidence,
                    warning = presentation.confidenceWarning,
                    warningIcon = icons.warning,
                )
            }

            CardMetric(
                text = presentation.quantityMetric,
                modifier = Modifier.semantics { hideFromAccessibility() },
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { hideFromAccessibility() },
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                CardMetric(
                    text = presentation.costMetric,
                    modifier = Modifier.weight(1f),
                )
                CardMetric(
                    text = presentation.igvMetric,
                    modifier = Modifier.weight(1f),
                )
            }
            CardMetric(
                text = presentation.totalMetric,
                modifier = Modifier.semantics { hideFromAccessibility() },
            )

            if (line.isPersisting) {
                Text(
                    text = copy.savingLine,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            if (presentation.canConfirmReviewed) {
                FacturaStockSecondaryButton(
                    text = copy.confirmReviewed,
                    onClick = { onAction(Action.ConfirmLineReviewed(line.lineId)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(InvoiceLineReviewTestTags.confirm(line.lineId))
                        .semantics {
                            contentDescription = presentation.confirmAccessibility
                        },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FacturaStockSecondaryButton(
                    text = copy.delete,
                    onClick = { onAction(Action.RequestDelete(line.lineId)) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag(InvoiceLineReviewTestTags.delete(line.lineId))
                        .semantics {
                            contentDescription = presentation.deleteAccessibility
                        },
                    enabled = !line.isPersisting,
                    leadingIconPainter = icons.delete,
                )
                FacturaStockPrimaryButton(
                    text = copy.edit,
                    onClick = { onAction(Action.EditLine(line.lineId)) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag(InvoiceLineReviewTestTags.edit(line.lineId))
                        .semantics {
                            contentDescription = presentation.editAccessibility
                        },
                    enabled = !line.isPersisting,
                )
                LineReorderMenu(
                    line = line,
                    copy = copy,
                    presentation = presentation,
                    moreActionsIcon = icons.moreActions,
                    canMoveUp = canMoveUp,
                    canMoveDown = canMoveDown,
                    onAction = onAction,
                )
            }
        }
    }
}

@Composable
private fun LineReorderMenu(
    line: Line,
    copy: LineCardCopy,
    presentation: LineCardPresentation,
    moreActionsIcon: Painter,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onAction: (Action) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = FacturaStockDesign.spacing
    var expanded by remember(line.lineId) { mutableStateOf(false) }
    val reorderEnabled = (canMoveUp || canMoveDown) && !line.isPersisting

    Box(modifier = modifier.size(spacing.minimumTouchTarget)) {
        IconButton(
            onClick = { expanded = true },
            enabled = reorderEnabled,
            modifier = Modifier
                .fillMaxSize()
                .testTag(InvoiceLineReviewTestTags.moreActions(line.lineId))
                .semantics {
                    contentDescription = presentation.moreActionsAccessibility
                },
        ) {
            Icon(
                painter = moreActionsIcon,
                contentDescription = null,
                modifier = Modifier.size(spacing.icon),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(copy.moveUp) },
                onClick = {
                    expanded = false
                    onAction(Action.MoveLineUp(line.lineId))
                },
                enabled = canMoveUp && !line.isPersisting,
                modifier = Modifier
                    .heightIn(min = spacing.minimumTouchTarget)
                    .testTag(InvoiceLineReviewTestTags.moveUp(line.lineId))
                    .semantics {
                        contentDescription = presentation.moveUpAccessibility
                    },
            )
            DropdownMenuItem(
                text = { Text(copy.moveDown) },
                onClick = {
                    expanded = false
                    onAction(Action.MoveLineDown(line.lineId))
                },
                enabled = canMoveDown && !line.isPersisting,
                modifier = Modifier
                    .heightIn(min = spacing.minimumTouchTarget)
                    .testTag(InvoiceLineReviewTestTags.moveDown(line.lineId))
                    .semantics {
                        contentDescription = presentation.moveDownAccessibility
                    },
            )
        }
    }
}

private fun Modifier.requestedFocus(focusRequester: FocusRequester?): Modifier =
    if (focusRequester == null) {
        this
    } else {
        focusRequester(focusRequester).focusable()
    }

private fun Line.toCardPresentation(
    currencyLabel: String,
    copy: LineCardCopy,
    locale: Locale,
): LineCardPresentation {
    val lineNumber = copy.lineNumberFormat.formatForCard(locale, position + 1)
    val projected = cardProjection
    val description = shownCardValue(
        value = projected?.description ?: InvoiceLineReviewContract.limitCardDescription(
            field(FieldId.DESCRIPTION).value,
        ),
        fieldId = FieldId.DESCRIPTION,
        copy = copy,
    )
    val quantity = shownCardValue(
        value = projected?.quantity ?: field(FieldId.QUANTITY).value,
        fieldId = FieldId.QUANTITY,
        copy = copy,
    )
    val unit = shownCardValue(
        value = projected?.unit ?: field(FieldId.UNIT).value,
        fieldId = FieldId.UNIT,
        copy = copy,
    )
    val quantityAndUnit = copy.quantityUnitFormat.formatForCard(
        locale,
        quantity,
        unit,
    ).trim()
    val unitCost = projected?.let { projection ->
        shownCardValue(projection.unitCost, FieldId.UNIT_COST, copy)
    } ?: shownCardMoney(field(FieldId.UNIT_COST), currencyLabel, copy, locale)
    val igv = if (
        taxTreatment == InventoryTaxTreatment.EXEMPT && field(FieldId.IGV).value.isBlank()
    ) {
        copy.taxExemptValue
    } else {
        projected?.let { projection ->
            shownCardValue(projection.igv, FieldId.IGV, copy)
        } ?: shownCardMoney(field(FieldId.IGV), currencyLabel, copy, locale)
    }
    val total = projected?.let { projection ->
        shownCardValue(projection.total, FieldId.TOTAL, copy)
    } ?: shownCardMoney(field(FieldId.TOTAL), currencyLabel, copy, locale)
    val confidence = when (this.confidence) {
        Confidence.HIGH -> copy.confidenceHigh
        Confidence.MEDIUM -> copy.confidenceMedium
        Confidence.LOW -> copy.confidenceLow
        Confidence.UNKNOWN -> copy.confidenceUnknown
    }.let { base ->
        confidencePercent?.let { percent ->
            copy.confidencePercentFormat.formatForCard(locale, base, percent)
        } ?: base
    }
    val pending = isPending
    val confidenceWarning = this.confidence == Confidence.LOW ||
        this.confidence == Confidence.UNKNOWN || pending
    val pendingStatus = if (pending) copy.pendingStatus else copy.reviewedStatus
    val spokenCard = buildString {
        append(lineNumber)
        append(". ").append(copy.descriptionLabel).append(": ").append(description)
        append(". ").append(copy.metricQuantity).append(": ").append(quantityAndUnit)
        append(". ").append(copy.metricCost).append(": ").append(unitCost)
        append(". ").append(copy.metricIgv).append(": ").append(igv)
        append(". ").append(copy.metricTotal).append(": ").append(total)
        append(". ").append(confidence)
    }
    return LineCardPresentation(
        lineNumber = lineNumber,
        description = description,
        quantityMetric = copy.metricValueFormat.formatForCard(
            locale,
            copy.metricQuantity,
            quantityAndUnit,
        ),
        costMetric = copy.metricValueFormat.formatForCard(locale, copy.metricCost, unitCost),
        igvMetric = copy.metricValueFormat.formatForCard(locale, copy.metricIgv, igv),
        totalMetric = copy.metricValueFormat.formatForCard(locale, copy.metricTotal, total),
        confidence = confidence,
        isPending = pending,
        confidenceWarning = confidenceWarning,
        canConfirmReviewed = canConfirmReviewed,
        pendingStatus = pendingStatus,
        spokenCard = spokenCard,
        confirmAccessibility = copy.confirmAccessibilityFormat.formatForCard(
            locale,
            lineNumber,
            description,
        ),
        moreActionsAccessibility = copy.moreActionsAccessibilityFormat.formatForCard(
            locale,
            lineNumber,
            description,
        ),
        moveUpAccessibility = copy.moveUpAccessibilityFormat.formatForCard(
            locale,
            lineNumber,
            description,
        ),
        moveDownAccessibility = copy.moveDownAccessibilityFormat.formatForCard(
            locale,
            lineNumber,
            description,
        ),
        deleteAccessibility = copy.deleteAccessibilityFormat.formatForCard(
            locale,
            lineNumber,
            description,
        ),
        editAccessibility = copy.editAccessibilityFormat.formatForCard(
            locale,
            lineNumber,
            description,
        ),
    )
}

private fun shownCardValue(field: Field, copy: LineCardCopy): String =
    shownCardValue(field.value, field.id, copy)

private fun shownCardValue(
    value: String,
    fieldId: FieldId,
    copy: LineCardCopy,
): String = value.ifBlank {
    if (fieldId == FieldId.DESCRIPTION) copy.descriptionPending else copy.valuePending
}

private fun shownCardMoney(
    field: Field,
    currencyLabel: String,
    copy: LineCardCopy,
    locale: Locale,
): String {
    if (field.value.isBlank()) return shownCardValue(field, copy)
    if (currencyLabel.isBlank()) return field.value
    return formatCurrencyAmountForDisplay(currencyLabel, field.value)
        ?: copy.currencyAmountFormat.formatForCard(
            locale,
            currencyLabelForDisplay(currencyLabel),
            field.value,
        )
}

private fun String.formatForCard(locale: Locale, vararg arguments: Any): String =
    String.format(locale, this, *arguments)

/** Texto plano ya localizado fuera del hilo principal: evita cuatro Column y cuatro layouts extra. */
@Composable
private fun CardMetric(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier,
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun LineMetric(
    @StringRes labelRes: Int,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(labelRes),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun ConfidenceBadge(
    label: String,
    warning: Boolean,
    warningIcon: Painter,
) {
    val spacing = FacturaStockDesign.spacing
    val container = if (warning) {
        FacturaStockDesign.semanticColors.warningContainer
    } else {
        FacturaStockDesign.semanticColors.successContainer
    }
    val content = if (warning) {
        FacturaStockDesign.semanticColors.onWarningContainer
    } else {
        FacturaStockDesign.semanticColors.onSuccessContainer
    }
    Surface(
        modifier = Modifier.semantics { stateDescription = label },
        shape = MaterialTheme.shapes.small,
        color = container,
        contentColor = content,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (warning) {
                Icon(
                    painter = warningIcon,
                    contentDescription = null,
                    modifier = Modifier.size(spacing.iconSmall),
                )
            }
            Text(text = label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun RestoreDeletionBar(
    deletion: DeletedLine,
    onAction: (Action) -> Unit,
) {
    val spacing = FacturaStockDesign.spacing
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(InvoiceLineReviewTestTags.RESTORE_BANNER)
            .semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = spacing.xs,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = spacing.md, vertical = spacing.sm),
        ) {
            Text(
                text = stringResource(
                    R.string.line_review_deleted_message,
                    deletion.description.ifBlank {
                        stringResource(R.string.line_review_description_pending)
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    onClick = { onAction(Action.DismissRestore) },
                    enabled = !deletion.isRestoring,
                ) {
                    Text(
                        text = stringResource(R.string.line_review_dismiss_restore),
                        color = MaterialTheme.colorScheme.inversePrimary,
                    )
                }
                TextButton(
                    onClick = { onAction(Action.RestoreDeletedLine) },
                    enabled = !deletion.isRestoring,
                    modifier = Modifier.testTag(InvoiceLineReviewTestTags.RESTORE),
                ) {
                    Text(
                        text = stringResource(
                            if (deletion.isRestoring) {
                                R.string.line_review_restoring
                            } else {
                                R.string.line_review_restore
                            },
                        ),
                        color = MaterialTheme.colorScheme.inversePrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun LineReviewSummaryBar(
    summary: Summary,
    continueAttempted: Boolean,
    pendingCount: Int,
    isSaving: Boolean,
    isContinueActionEnabled: Boolean,
    onAction: (Action) -> Unit,
) {
    val spacing = FacturaStockDesign.spacing
    val pending = stringResource(R.string.line_review_summary_pending)
    val lineSum = summary.lineSum.ifBlank { pending }
    val invoiceTotal = summary.invoiceTotal.ifBlank { pending }
    val difference = summary.exactDifference.ifBlank { pending }
    val differenceMessage = summaryStatusMessage(summary, difference)
    val spoken = listOf(
        stringResource(R.string.line_review_summary_sum_label) + ": " + lineSum,
        stringResource(R.string.line_review_summary_invoice_label) + ": " + invoiceTotal,
        differenceMessage,
    ).joinToString(separator = ". ")

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(InvoiceLineReviewTestTags.SUMMARY)
            .semantics {
                contentDescription = spoken
                liveRegion = LiveRegionMode.Polite
            },
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = spacing.xs,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.md, vertical = spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { hideFromAccessibility() },
                horizontalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                LineMetric(
                    labelRes = R.string.line_review_summary_sum_label,
                    value = lineSum,
                    modifier = Modifier.weight(1f),
                )
                LineMetric(
                    labelRes = R.string.line_review_summary_invoice_label,
                    value = invoiceTotal,
                    modifier = Modifier.weight(1f),
                )
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(InvoiceLineReviewTestTags.DIFFERENCE)
                    .semantics { hideFromAccessibility() },
                shape = MaterialTheme.shapes.small,
                color = when {
                    !summary.isComparable -> MaterialTheme.colorScheme.surfaceContainer
                    summary.hasDifference -> FacturaStockDesign.semanticColors.warningContainer
                    else -> FacturaStockDesign.semanticColors.successContainer
                },
                contentColor = when {
                    !summary.isComparable -> MaterialTheme.colorScheme.onSurfaceVariant
                    summary.hasDifference -> FacturaStockDesign.semanticColors.onWarningContainer
                    else -> FacturaStockDesign.semanticColors.onSuccessContainer
                },
            ) {
                Column(modifier = Modifier.padding(spacing.xs)) {
                    Text(
                        text = stringResource(R.string.line_review_summary_difference_label),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(text = difference, style = MaterialTheme.typography.titleMedium)
                    Text(text = differenceMessage, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (continueAttempted && pendingCount > 0) {
                Text(
                    text = pluralStringResource(
                        R.plurals.line_review_blocking_message,
                        pendingCount,
                        pendingCount,
                    ),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            FacturaStockPrimaryButton(
                text = stringResource(
                    if (isSaving) {
                        R.string.line_review_saving_changes
                    } else {
                        R.string.line_review_link_products
                    },
                ),
                onClick = { onAction(Action.LinkProducts) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(InvoiceLineReviewTestTags.LINK_PRODUCTS),
                enabled = isContinueActionEnabled,
            )
        }
    }
}

@Composable
private fun LineEditorDialog(
    editor: Editor,
    summary: Summary,
    currencyLabel: String,
    storageFull: Boolean,
    onAction: (Action) -> Unit,
) {
    val spacing = FacturaStockDesign.spacing
    val title = stringResource(
        if (editor.isNew) {
            R.string.line_review_editor_new_title
        } else {
            R.string.line_review_editor_title
        },
    )
    val focusManager = LocalFocusManager.current
    val focusRequesters = remember(editor.line.lineId) {
        FieldId.entries.associateWith { FocusRequester() }
    }

    Dialog(
        onDismissRequest = { onAction(Action.CloseEditor) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing)
                .imePadding()
                .testTag(InvoiceLineReviewTestTags.EDITOR)
                .semantics {
                    paneTitle = title
                    isTraversalGroup = true
                },
            color = MaterialTheme.colorScheme.surface,
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val footerMaxHeight = maxHeight * EDITOR_FOOTER_MAX_HEIGHT_FRACTION
                Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.lg, vertical = spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            modifier = Modifier.semantics { heading() },
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            text = stringResource(R.string.line_review_editor_hint),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(
                        onClick = { onAction(Action.CloseEditor) },
                        modifier = Modifier.testTag(InvoiceLineReviewTestTags.CLOSE_EDITOR),
                    ) {
                        Text(stringResource(R.string.line_review_editor_close))
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .testTag(InvoiceLineReviewTestTags.EDITOR_LIST),
                    contentPadding = PaddingValues(
                        start = spacing.lg,
                        end = spacing.lg,
                        bottom = spacing.lg,
                    ),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    if (editor.saveFailure) {
                        item(key = "editor_save_failure", contentType = "error") {
                            RecoverableError(
                                title = stringResource(R.string.line_review_save_error_title),
                                message = stringResource(
                                    if (storageFull) {
                                        R.string.storage_full_recoverable_message
                                    } else {
                                        R.string.line_review_save_error_message
                                    },
                                ),
                                actionLabel = stringResource(R.string.line_review_retry_save),
                                onAction = { onAction(Action.RetrySave) },
                            )
                        }
                    }
                    item(key = "editor_tax_treatment", contentType = "tax_treatment") {
                        TaxTreatmentSelector(
                            selected = editor.line.taxTreatment,
                            hasExplicitEvidence = editor.line.field(FieldId.IGV).value.isNotBlank(),
                            enabled = !editor.line.isPersisting,
                            onSelected = { treatment ->
                                onAction(Action.EditorTaxTreatmentChanged(treatment))
                            },
                        )
                    }
                    items(
                        items = editor.line.fields,
                        key = { field -> field.id.name },
                        contentType = { "line_editor_field" },
                    ) { field ->
                        val next = FieldId.entries.getOrNull(field.id.ordinal + 1)
                        EditorField(
                            field = field,
                            currencyLabel = currencyLabel,
                            focusRequester = focusRequesters.getValue(field.id),
                            imeAction = if (next == null) ImeAction.Done else ImeAction.Next,
                            onAction = onAction,
                            onNext = {
                                next?.let { focusRequesters.getValue(it).requestFocus() }
                            },
                            onDone = focusManager::clearFocus,
                        )
                    }
                    item(key = "editor_delete", contentType = "action") {
                        FacturaStockSecondaryButton(
                            text = stringResource(R.string.line_review_delete),
                            onClick = {
                                onAction(Action.RequestDelete(editor.line.lineId))
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !editor.line.isPersisting,
                            leadingIconRes = R.drawable.ic_delete,
                        )
                    }
                }

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = footerMaxHeight),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = spacing.xs,
                ) {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(
                                horizontal = spacing.lg,
                                vertical = spacing.sm,
                            ),
                    ) {
                        EditorSummary(summary)
                        Spacer(modifier = Modifier.height(spacing.sm))
                        if (editor.line.canConfirmReviewed) {
                            FacturaStockSecondaryButton(
                                text = stringResource(R.string.line_review_confirm_reviewed),
                                onClick = {
                                    onAction(Action.ConfirmLineReviewed(editor.line.lineId))
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(modifier = Modifier.height(spacing.xs))
                        }
                        FacturaStockPrimaryButton(
                            text = stringResource(R.string.line_review_editor_close),
                            onClick = { onAction(Action.CloseEditor) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(InvoiceLineReviewTestTags.EDITOR_DONE),
                            enabled = !editor.line.isPersisting,
                        )
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun TaxTreatmentSelector(
    selected: InventoryTaxTreatment,
    hasExplicitEvidence: Boolean,
    enabled: Boolean,
    onSelected: (InventoryTaxTreatment) -> Unit,
) {
    val spacing = FacturaStockDesign.spacing
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(InvoiceLineReviewTestTags.TAX_TREATMENT),
    ) {
        Text(
            text = stringResource(R.string.line_review_tax_treatment_label),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleSmall,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            listOf(
                InventoryTaxTreatment.EXCLUDED to R.string.line_review_tax_excluded,
                InventoryTaxTreatment.INCLUDED to R.string.line_review_tax_included,
                InventoryTaxTreatment.EXEMPT to R.string.line_review_tax_exempt,
            ).forEach { (treatment, label) ->
                FilterChip(
                    selected = selected == treatment,
                    onClick = { onSelected(treatment) },
                    label = {
                        Text(text = stringResource(label))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled,
                )
            }
        }
        if (selected == InventoryTaxTreatment.UNKNOWN) {
            Text(
                text = stringResource(R.string.line_review_tax_treatment_required),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        } else if (
            selected in setOf(
                InventoryTaxTreatment.INCLUDED,
                InventoryTaxTreatment.EXCLUDED,
            ) && !hasExplicitEvidence
        ) {
            Text(
                text = stringResource(R.string.line_review_tax_evidence_required),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun EditorSummary(summary: Summary) {
    val spacing = FacturaStockDesign.spacing
    val pending = stringResource(R.string.line_review_summary_pending)
    val lineSum = summary.lineSum.ifBlank { pending }
    val difference = summary.exactDifference.ifBlank { pending }
    val spoken = listOf(
        stringResource(R.string.line_review_summary_sum_label) + ": " + lineSum,
        stringResource(R.string.line_review_summary_difference_label) + ": " + difference,
        summaryStatusMessage(summary, difference),
    ).joinToString(". ")
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(InvoiceLineReviewTestTags.EDITOR_SUMMARY)
            .semantics {
                contentDescription = spoken
                liveRegion = LiveRegionMode.Polite
            },
        shape = MaterialTheme.shapes.small,
        color = when {
            !summary.isComparable -> MaterialTheme.colorScheme.surfaceContainer
            summary.hasDifference -> FacturaStockDesign.semanticColors.warningContainer
            else -> FacturaStockDesign.semanticColors.successContainer
        },
        contentColor = when {
            !summary.isComparable -> MaterialTheme.colorScheme.onSurfaceVariant
            summary.hasDifference -> FacturaStockDesign.semanticColors.onWarningContainer
            else -> FacturaStockDesign.semanticColors.onSuccessContainer
        },
    ) {
        Row(
            modifier = Modifier
                .padding(spacing.sm)
                .semantics { hideFromAccessibility() },
            horizontalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.line_review_summary_sum_label),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(text = lineSum, style = MaterialTheme.typography.titleMedium)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.line_review_summary_difference_label),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(text = difference, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun summaryStatusMessage(summary: Summary, difference: String): String = when {
    summary.issue == SummaryIssue.ARITHMETIC_OVERFLOW ->
        stringResource(R.string.line_review_summary_overflow)

    summary.issue == SummaryIssue.UNRESOLVED_LINES -> pluralStringResource(
        R.plurals.line_review_summary_unresolved,
        summary.unresolvedLineCount,
        summary.unresolvedLineCount,
    )

    summary.issue == SummaryIssue.NOT_COMPARABLE || !summary.isComparable ->
        stringResource(R.string.line_review_summary_incomplete)

    summary.hasDifference -> stringResource(R.string.line_review_summary_differs, difference)
    else -> stringResource(R.string.line_review_summary_matches)
}

@Composable
private fun EditorField(
    field: Field,
    currencyLabel: String,
    focusRequester: FocusRequester,
    imeAction: ImeAction,
    onAction: (Action) -> Unit,
    onNext: () -> Unit,
    onDone: () -> Unit,
) {
    val spacing = FacturaStockDesign.spacing
    val error = field.error?.takeIf { field.touched }
    val keyboard = when (field.id) {
        FieldId.QUANTITY,
        FieldId.UNIT_COST,
        FieldId.DISCOUNT,
        FieldId.IGV,
        FieldId.TOTAL,
        -> KeyboardType.Decimal

        FieldId.DESCRIPTION,
        FieldId.CODE,
        FieldId.UNIT,
        -> KeyboardType.Text
    }
    val capitalization = when (field.id) {
        FieldId.CODE,
        FieldId.UNIT,
        -> KeyboardCapitalization.Characters

        FieldId.DESCRIPTION -> KeyboardCapitalization.Sentences
        else -> KeyboardCapitalization.None
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = field.value,
            onValueChange = { value ->
                onAction(Action.EditorFieldChanged(field.id, value))
            },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { focusState ->
                    onAction(
                        Action.EditorFieldFocusChanged(
                            field = field.id,
                            focused = focusState.isFocused,
                        ),
                    )
                }
                .testTag(InvoiceLineReviewTestTags.editorField(field.id)),
            label = {
                val baseLabel = stringResource(fieldLabelRes(field.id))
                Text(
                    text = if (field.id.isMoney && currencyLabel.isNotBlank()) {
                        stringResource(
                            R.string.line_review_field_with_currency,
                            baseLabel,
                            currencyLabelForDisplay(currencyLabel),
                        )
                    } else {
                        baseLabel
                    },
                )
            },
            singleLine = field.id != FieldId.DESCRIPTION,
            minLines = if (field.id == FieldId.DESCRIPTION) 2 else 1,
            maxLines = if (field.id == FieldId.DESCRIPTION) 4 else 1,
            isError = error != null,
            supportingText = error?.let { value ->
                { Text(stringResource(fieldErrorRes(value))) }
            },
            keyboardOptions = KeyboardOptions(
                capitalization = capitalization,
                keyboardType = keyboard,
                imeAction = imeAction,
            ),
            keyboardActions = KeyboardActions(
                onNext = { onNext() },
                onDone = { onDone() },
            ),
        )
        FieldOriginSupport(field)
        Spacer(modifier = Modifier.height(spacing.xxs))
    }
}

@Composable
private fun FieldOriginSupport(field: Field) {
    val spacing = FacturaStockDesign.spacing
    val sourceText = stringResource(originLabelRes(field.origin))
    val confidenceText = confidenceLabel(field.confidence, percent = null)
    val ocrReference = field.ocrValue
        ?.takeIf(String::isNotBlank)
        ?.let { value -> stringResource(R.string.line_review_ocr_reference, value) }
    val calculatedReference = field.calculatedValue
        ?.takeIf(String::isNotBlank)
        ?.let { value -> stringResource(R.string.line_review_calculated_reference, value) }
    val spokenDescription = listOfNotNull(
        sourceText,
        confidenceText,
        ocrReference,
        calculatedReference,
    ).joinToString(". ")
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(InvoiceLineReviewTestTags.editorFieldOrigin(field.id))
            .semantics {
                stateDescription = spokenDescription
            },
        shape = MaterialTheme.shapes.small,
        color = when (field.origin) {
            ValueOrigin.OCR -> FacturaStockDesign.semanticColors.infoContainer
            ValueOrigin.CALCULATED -> MaterialTheme.colorScheme.secondaryContainer
            ValueOrigin.WRITTEN -> FacturaStockDesign.semanticColors.successContainer
            ValueOrigin.MISSING -> MaterialTheme.colorScheme.surfaceContainer
        },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = spacing.sm, vertical = spacing.xs),
            verticalArrangement = Arrangement.spacedBy(spacing.xxs),
        ) {
            Text(text = sourceText, style = MaterialTheme.typography.labelMedium)
            Text(
                text = confidenceText,
                style = MaterialTheme.typography.bodySmall,
            )
            field.ocrValue?.takeIf(String::isNotBlank)?.let { ocr ->
                ReferenceValue(
                    text = stringResource(R.string.line_review_ocr_reference, ocr),
                    sameAsEffective = ocr == field.value,
                )
            }
            field.calculatedValue?.takeIf(String::isNotBlank)?.let { calculated ->
                ReferenceValue(
                    text = stringResource(
                        R.string.line_review_calculated_reference,
                        calculated,
                    ),
                    sameAsEffective = calculated == field.value,
                )
            }
        }
    }
}

@Composable
private fun ReferenceValue(
    text: String,
    sameAsEffective: Boolean,
) {
    Text(
        text = if (sameAsEffective) {
            text + " · " + stringResource(R.string.line_review_reference_same)
        } else {
            text
        },
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun DeleteLineDialog(
    line: Line,
    onAction: (Action) -> Unit,
) {
    val description = line.field(FieldId.DESCRIPTION).value.ifBlank {
        stringResource(R.string.line_review_description_pending)
    }
    FacturaStockDialog(
        title = stringResource(R.string.line_review_delete_title),
        message = stringResource(R.string.line_review_delete_message, description),
        confirmLabel = stringResource(R.string.line_review_delete_confirm),
        dismissLabel = stringResource(R.string.action_cancel),
        onConfirm = { onAction(Action.ConfirmDelete) },
        onDismiss = { onAction(Action.CancelDelete) },
        modifier = Modifier.testTag(InvoiceLineReviewTestTags.DELETE_DIALOG),
    )
}

private val FieldId.isMoney: Boolean
    get() = when (this) {
        FieldId.UNIT_COST,
        FieldId.DISCOUNT,
        FieldId.IGV,
        FieldId.TOTAL,
        -> true

        FieldId.DESCRIPTION,
        FieldId.CODE,
        FieldId.QUANTITY,
        FieldId.UNIT,
        -> false
    }

@Composable
private fun confidenceLabel(
    confidence: Confidence,
    percent: Int?,
): String {
    val base = stringResource(confidenceLabelRes(confidence))
    return percent?.let { value ->
        stringResource(R.string.line_review_confidence_percent, base, value)
    } ?: base
}

@StringRes
private fun confidenceLabelRes(confidence: Confidence): Int = when (confidence) {
    Confidence.HIGH -> R.string.line_review_confidence_high
    Confidence.MEDIUM -> R.string.line_review_confidence_medium
    Confidence.LOW -> R.string.line_review_confidence_low
    Confidence.UNKNOWN -> R.string.line_review_confidence_unknown
}

@StringRes
private fun fieldLabelRes(field: FieldId): Int = when (field) {
    FieldId.DESCRIPTION -> R.string.line_review_description_label
    FieldId.CODE -> R.string.line_review_code_label
    FieldId.QUANTITY -> R.string.line_review_quantity_label
    FieldId.UNIT -> R.string.line_review_unit_label
    FieldId.UNIT_COST -> R.string.line_review_unit_cost_label
    FieldId.DISCOUNT -> R.string.line_review_discount_label
    FieldId.IGV -> R.string.line_review_igv_label
    FieldId.TOTAL -> R.string.line_review_total_label
}

@StringRes
private fun originLabelRes(origin: ValueOrigin): Int = when (origin) {
    ValueOrigin.OCR -> R.string.line_review_source_ocr
    ValueOrigin.CALCULATED -> R.string.line_review_source_calculated
    ValueOrigin.WRITTEN -> R.string.line_review_source_written
    ValueOrigin.MISSING -> R.string.line_review_source_missing
}

@StringRes
private fun fieldErrorRes(error: FieldError): Int = when (error) {
    FieldError.REQUIRED -> R.string.line_review_error_required
    FieldError.INVALID_DECIMAL -> R.string.line_review_error_decimal
    FieldError.INVALID_AMOUNT -> R.string.line_review_error_amount
    FieldError.NEGATIVE_QUANTITY -> R.string.line_review_error_negative_quantity
    FieldError.NEGATIVE_AMOUNT -> R.string.line_review_error_negative_amount
    FieldError.TOO_LONG -> R.string.line_review_error_too_long
}

private const val EDITOR_FOOTER_MAX_HEIGHT_FRACTION = 0.45f
private const val PRESENTATION_CANCELLATION_BATCH_SIZE = 8
