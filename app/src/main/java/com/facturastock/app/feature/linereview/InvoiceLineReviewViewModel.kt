package com.facturastock.app.feature.linereview

import androidx.lifecycle.SavedStateHandle
import com.facturastock.app.core.coroutines.DispatcherProvider
import com.facturastock.app.core.id.UuidGenerator
import com.facturastock.app.core.time.AppClock
import com.facturastock.app.domain.error.StorageError
import com.facturastock.app.domain.error.StorageException
import com.facturastock.app.domain.model.CurrencyCode
import com.facturastock.app.domain.model.InventoryTaxTreatment
import com.facturastock.app.domain.model.InvoiceLineEdit
import com.facturastock.app.domain.model.InvoiceLineEditField
import com.facturastock.app.domain.model.InvoiceLineEditOrigin
import com.facturastock.app.domain.model.InvoiceLineEditValue
import com.facturastock.app.domain.model.InvoiceLinesEdit
import com.facturastock.app.domain.model.InvoiceLineValueSource
import com.facturastock.app.domain.model.Money
import com.facturastock.app.domain.model.id.DraftId
import com.facturastock.app.domain.model.id.LineId
import com.facturastock.app.domain.repository.SaveInvoiceLinesEditResult
import com.facturastock.app.domain.usecase.InvoiceLineReviewCalculator
import com.facturastock.app.domain.usecase.InvoiceLineReviewValidator
import com.facturastock.app.domain.usecase.InvoiceLineValidation
import com.facturastock.app.domain.usecase.InvoiceLineValidationErrorCode
import com.facturastock.app.domain.usecase.InvoiceLinesFinancialSummary
import com.facturastock.app.domain.usecase.InvoiceLinesReviewSnapshot
import com.facturastock.app.domain.usecase.LoadInvoiceLinesReviewUseCase
import com.facturastock.app.domain.usecase.SaveInvoiceLinesEditUseCase
import com.facturastock.app.feature.common.RouteArgumentKeys
import com.facturastock.app.feature.common.UdfViewModel
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.Action
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.CardProjection
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.Confidence
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.DeletedLine
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.Editor
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.Effect
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.Field
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.FieldError
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.FieldId
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.Line
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.State
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.Summary
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.SummaryIssue
import com.facturastock.app.feature.linereview.InvoiceLineReviewContract.ValueOrigin
import com.facturastock.app.ui.format.currencyLabelForDisplay
import com.facturastock.app.ui.format.formatCurrencyAmountForDisplay
import com.facturastock.app.ui.format.formatForDisplay
import com.facturastock.app.ui.format.formatSignedForDisplay
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

@HiltViewModel
class InvoiceLineReviewViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val loadInvoiceLinesReviewUseCase: LoadInvoiceLinesReviewUseCase,
    private val saveInvoiceLinesEditUseCase: SaveInvoiceLinesEditUseCase,
    private val validator: InvoiceLineReviewValidator,
    private val calculator: InvoiceLineReviewCalculator,
    private val uuidGenerator: UuidGenerator,
    private val appClock: AppClock,
    dispatcherProvider: DispatcherProvider,
) : UdfViewModel<State, Action, Effect>(
    initialState = initialLineReviewState(savedStateHandle),
    dispatcherProvider = dispatcherProvider,
) {
    private data class SaveRequest(
        val edit: InvoiceLinesEdit,
        val lineage: Long,
        val expectedRevision: Long?,
        val changedLineIds: Set<LineId> = emptySet(),
        val advanceToken: Long? = null,
        val backToken: Long? = null,
    )

    private val saveRequests = Channel<SaveRequest>(Channel.CONFLATED)
    private var currentEdit: InvoiceLinesEdit? = null
    private var currency: CurrencyCode? = null
    private var invoiceTotal: Money? = null
    private var persistedRevision: Long? = null
    /** Base durable confirmada de la que desciende [currentEdit]; no avanza por snapshots ajenos. */
    private var localWriteBaseRevision: Long? = null
    private var localEditLineage = 0L
    private var loadJob: Job? = null
    private var rebaseJob: Job? = null
    private var rebaseGeneration = 0L
    private var retryRequiresRebase = false
    private var advanceToken = 0L
    private var advanceInFlight = false
    private var advanceValidationJob: Job? = null
    private var backInFlight = false
    private var backToken = 0L
    private var renderGeneration = 0L
    private var asyncRenderGeneration = 0L
    private var renderJob: Job? = null
    private var hasPendingRender = false
    private var pendingChangedLineIds: Set<LineId>? = emptySet()
    private var newEditorLineId: LineId? = null
    private val persistingLineIds = linkedSetOf<LineId>()
    private val interactedFields = linkedSetOf<Pair<LineId, FieldId>>()
    private val focusedFields = linkedSetOf<Pair<LineId, FieldId>>()
    private val locallyChangedFields = linkedMapOf<LineId, MutableSet<InvoiceLineEditField>>()
    private val locallyChangedRows = linkedSetOf<LineId>()
    private val locallyConfirmedRows = linkedSetOf<LineId>()
    private var localOrderChanged = false

    init {
        startWriter()
        if (uiState.value.draftId != null) load()
    }

    override fun onAction(action: Action) {
        when (action) {
            Action.Start -> if (uiState.value.draftId == null) {
                executeMain { emitEffect(Effect.CloseInvalidRoute) }
            }
            is Action.SearchChanged -> updateSearch(action.query)
            Action.PendingFilterToggled -> togglePendingFilter()
            Action.AddLine -> addLine()
            is Action.EditLine -> openEditor(action.lineId)
            is Action.EditorFieldChanged -> changeEditorField(action.field, action.value)
            is Action.EditorFieldFocusChanged -> onFieldFocusChanged(action.field, action.focused)
            is Action.EditorTaxTreatmentChanged -> changeTaxTreatment(action.treatment)
            Action.CloseEditor -> closeEditor()
            is Action.ConfirmLineReviewed -> confirmLine(action.lineId)
            is Action.RequestDelete -> requestDelete(action.lineId)
            Action.ConfirmDelete -> deleteRequestedLine()
            Action.CancelDelete -> clearDeletionRequest()
            Action.RestoreDeletedLine -> restoreDeletedLine()
            Action.DismissRestore -> dismissRestore()
            is Action.MoveLineUp -> moveLine(action.lineId, -1)
            is Action.MoveLineDown -> moveLine(action.lineId, 1)
            Action.LinkProducts -> linkProducts()
            Action.RetryLoad -> load()
            Action.RetrySave -> retrySave()
            Action.BackSelected -> saveBeforeBack()
        }
    }

    private fun load() {
        val draftId = uiState.value.draftId ?: return
        loadJob?.cancel()
        loadJob = executeMain {
            updateState { copy(isLoading = currentEdit == null, failure = null) }
            try {
                loadInvoiceLinesReviewUseCase.observe(draftId).collect { snapshot ->
                    if (snapshot == null) {
                        cancelAdvance()
                        cancelBack()
                        val cancelledRebase = cancelRebase()
                        val mustReport = uiState.value.failure !=
                            InvoiceLineReviewContract.Failure.INVALID_ROUTE
                        updateState {
                            copy(
                                isLoading = false,
                                isSaving = false,
                                saveFailure = saveFailure || cancelledRebase,
                                failure = InvoiceLineReviewContract.Failure.INVALID_ROUTE,
                            )
                        }
                        if (mustReport) emitEffect(Effect.CloseInvalidRoute)
                    } else {
                        acceptObservedSnapshot(snapshot)
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                cancelAdvance()
                val cancelledRebase = cancelRebase()
                updateState {
                    copy(
                        isLoading = false,
                        isSaving = if (cancelledRebase && !backInFlight) false else isSaving,
                        saveFailure = saveFailure || cancelledRebase,
                        failure = InvoiceLineReviewContract.Failure.LOAD_FAILED,
                    )
                }
            }
        }
    }

    /** Conserva el delta optimista local y adopta automáticamente cambios Room cuando está idle. */
    private suspend fun acceptObservedSnapshot(snapshot: InvoiceLinesReviewSnapshot) {
        val previousCurrency = currency
        val previousInvoiceTotal = invoiceTotal
        currency = snapshot.draft.currency
        invoiceTotal = snapshot.draft.total
        persistedRevision = maxRevision(persistedRevision, snapshot.persistedEditRevision)
        val currencyChanged = previousCurrency != currency
        val invoiceTotalChanged = previousInvoiceTotal != invoiceTotal
        val globalPresentationChanged = currencyChanged || invoiceTotalChanged
        val globalChangedLineIds: Set<LineId>? = if (currencyChanged) null else emptySet()
        val local = currentEdit
        if (local == null) {
            beginLocalLineage(snapshot.persistedEditRevision)
            currentEdit = snapshot.edit
            retryRequiresRebase = false
            clearLocalDelta()
            renderInitial(edit = snapshot.edit, isLoading = false, saveFailure = false)
            return
        }
        val persisted = snapshot.edit
        val hasLocalDelta = locallyChangedFields.isNotEmpty() ||
            locallyChangedRows.isNotEmpty() ||
            locallyConfirmedRows.isNotEmpty() ||
            localOrderChanged
        when {
            persisted.revision < local.revision -> if (globalPresentationChanged) {
                render(
                    edit = local,
                    isLoading = false,
                    saveFailure = uiState.value.saveFailure,
                    failure = null,
                    changedLineIds = globalChangedLineIds,
                )
            } else {
                updateState { copy(isLoading = false, failure = null) }
            }
            persisted.revision == local.revision && persisted != local -> {
                retryRequiresRebase = true
                if (globalPresentationChanged) {
                    render(
                        edit = local,
                        isLoading = false,
                        saveFailure = uiState.value.saveFailure,
                        failure = null,
                        changedLineIds = globalChangedLineIds,
                    )
                } else {
                    updateState { copy(isLoading = false, failure = null) }
                }
            }
            persisted.revision > local.revision && hasLocalDelta -> {
                retryRequiresRebase = true
                if (globalPresentationChanged) {
                    render(
                        edit = local,
                        isLoading = false,
                        saveFailure = uiState.value.saveFailure,
                        failure = null,
                        changedLineIds = globalChangedLineIds,
                    )
                } else {
                    updateState { copy(isLoading = false, failure = null) }
                }
            }
            persisted.revision > local.revision -> {
                val completeBackAfterAdoption = backInFlight
                cancelAdvance()
                cancelBack()
                cancelRebase()
                beginLocalLineage(snapshot.persistedEditRevision)
                currentEdit = persisted
                retryRequiresRebase = false
                clearLocalDelta()
                render(
                    edit = persisted,
                    isLoading = false,
                    saveFailure = false,
                    isSaving = false,
                    failure = null,
                )
                if (completeBackAfterAdoption) emitEffect(Effect.Back)
            }
            globalPresentationChanged -> render(
                edit = local,
                isLoading = false,
                saveFailure = uiState.value.saveFailure,
                failure = null,
                changedLineIds = globalChangedLineIds,
            )
            else -> updateState { copy(isLoading = false, failure = null) }
        }
    }

    private fun updateSearch(query: String) {
        val value = query.take(MAX_SEARCH_LENGTH)
        savedStateHandle[SAVED_QUERY] = value
        executeMain { updateState { copy(searchQuery = value) } }
    }

    private fun togglePendingFilter() {
        val selected = !uiState.value.pendingOnly
        savedStateHandle[SAVED_PENDING_FILTER] = selected
        executeMain { updateState { copy(pendingOnly = selected) } }
    }

    private fun openEditor(lineId: LineId) {
        if (isNewEditorPendingPublication()) return
        if (currentEdit?.activeLines?.none { it.lineId == lineId } != false) return
        cancelAdvance()
        renderGeneration += 1L
        focusedFields.clear()
        newEditorLineId = null
        savedStateHandle[SAVED_EDITOR_LINE_ID] = lineId.value
        executeMain {
            updateState {
                copy(editor = lines.firstOrNull { it.lineId == lineId }?.let(::Editor))
            }
        }
    }

    private fun closeEditor() {
        if (isNewEditorPendingPublication()) return
        renderGeneration += 1L
        focusedFields.clear()
        newEditorLineId = null
        savedStateHandle[SAVED_EDITOR_LINE_ID] = null
        executeMain { updateState { copy(editor = null) } }
    }

    private fun addLine() {
        val current = currentEdit ?: return
        // El editor se publica después de preparar la tarjeta en Default. Bloquear por la fuente
        // autoritativa impide que un doble toque cree dos filas durante esa ventana.
        if (newEditorLineId != null || savedEditorLineId() != null) return
        if (current.activeLines.size >= InvoiceLinesEdit.MAX_ACTIVE_LINES) return
        cancelAdvance()
        cancelBack()
        val now = mutationTime(current)
        val line = InvoiceLineEdit(
            lineId = LineId.from(uuidGenerator.newUuid()),
            position = current.activeLines.size,
            origin = InvoiceLineEditOrigin.USER,
            sourcePosition = null,
            ocrRawText = null,
            requiresReview = false,
            reviewConfirmedByUser = true,
            createdAt = now,
            updatedAt = now,
        )
        val retainedLines = if (current.lines.size >= InvoiceLinesEdit.MAX_RETAINED_LINES) {
            val currentlyRestorable = LineId.parse(
                savedStateHandle.get<String>(SAVED_LAST_DELETED_LINE_ID),
            )
            val tombstones = current.lines.filter(InvoiceLineEdit::isDeleted)
            val purgeCandidates = tombstones.filterNot { it.lineId == currentlyRestorable }
                .ifEmpty { tombstones }
            val oldestTombstone = purgeCandidates
                .minWithOrNull(
                    compareBy<InvoiceLineEdit>(
                        { requireNotNull(it.deletedAt) },
                        { it.lineId.value },
                    ),
                )
            current.lines.filterNot { existing -> existing.lineId == oldestTombstone?.lineId }
        } else {
            current.lines
        }
        val recalculatedLine = calculator.recalculate(line, currency)
        val next = current.copy(
            // El historial es acotado: al llegar a 500 se descarta primero el tombstone
            // más antiguo, nunca una línea activa ni la eliminación restaurable reciente.
            lines = retainedLines + recalculatedLine,
            revision = Math.incrementExact(current.revision),
            updatedAt = now,
        )
        locallyChangedRows += line.lineId
        currentEdit = next
        persistingLineIds += line.lineId
        newEditorLineId = line.lineId
        savedStateHandle[SAVED_EDITOR_LINE_ID] = line.lineId.value
        render(
            next,
            changedLineIds = setOf(line.lineId),
        )
        enqueueSave(next, setOf(line.lineId))
    }

    private fun changeEditorField(fieldId: FieldId, rawValue: String) {
        val current = currentEdit ?: return
        val editorId = uiState.value.editor?.line?.lineId ?: return
        val oldLine = current.activeLines.firstOrNull { it.lineId == editorId } ?: return
        val value = sanitize(fieldId, rawValue)
        if (oldLine.value(fieldId.toDomain()).written == value &&
            oldLine.value(fieldId.toDomain()).selectedSource == InvoiceLineValueSource.WRITTEN
        ) {
            return
        }
        cancelAdvance()
        cancelBack()
        val now = mutationTime(current)
        val domainField = fieldId.toDomain()
        val changedLine = oldLine.withValue(
            field = domainField,
            value = oldLine.value(domainField).copy(
                written = value,
                selectedSource = InvoiceLineValueSource.WRITTEN,
            ),
            touchedFields = oldLine.touchedFields + domainField,
            updatedAt = now,
        )
        val recalculatedLine = calculator.recalculate(changedLine, currency)
        val next = current.copy(
            lines = current.lines.replaceLine(recalculatedLine),
            revision = Math.incrementExact(current.revision),
            updatedAt = now,
        )
        locallyChangedFields.getOrPut(editorId, ::linkedSetOf) += domainField
        interactedFields += editorId to fieldId
        currentEdit = next
        persistingLineIds += editorId
        publishEditorFieldValue(editorId, fieldId, value)
        render(next, changedLineIds = setOf(editorId))
        enqueueSave(next, setOf(editorId))
    }

    private fun onFieldFocusChanged(fieldId: FieldId, focused: Boolean) {
        val lineId = uiState.value.editor?.line?.lineId ?: return
        val key = lineId to fieldId
        if (focused) {
            focusedFields += key
            return
        }
        // Compose emite un `false` inicial al adjuntar el campo; solo un foco real cuenta.
        if (!focusedFields.remove(key)) return
        interactedFields += lineId to fieldId
        currentEdit?.let { edit ->
            render(edit, changedLineIds = setOf(lineId))
        }
    }

    private fun changeTaxTreatment(treatment: InventoryTaxTreatment) {
        if (treatment == InventoryTaxTreatment.UNKNOWN) return
        val current = currentEdit ?: return
        val editorId = uiState.value.editor?.line?.lineId ?: return
        val oldLine = current.activeLines.firstOrNull { it.lineId == editorId } ?: return
        if (oldLine.taxTreatment == treatment) return
        cancelAdvance()
        cancelBack()
        val now = mutationTime(current)
        val changedLine = calculator.recalculate(
            oldLine.copy(taxTreatment = treatment, updatedAt = now),
            currency,
        )
        val next = current.copy(
            lines = current.lines.replaceLine(changedLine),
            revision = Math.incrementExact(current.revision),
            updatedAt = now,
        )
        locallyChangedRows += editorId
        currentEdit = next
        persistingLineIds += editorId
        publishEditorTaxTreatment(editorId, treatment)
        render(next, changedLineIds = setOf(editorId))
        enqueueSave(next, setOf(editorId))
    }

    private fun confirmLine(lineId: LineId) {
        val current = currentEdit ?: return
        val line = current.activeLines.firstOrNull { it.lineId == lineId } ?: return
        if (!validator.validate(line, currency).isValid || line.reviewConfirmedByUser) return
        cancelAdvance()
        cancelBack()
        val now = mutationTime(current)
        val confirmed = line.copy(
            reviewConfirmedByUser = true,
            touchedFields = line.touchedFields + line.reviewRequiredFields,
            updatedAt = now,
        )
        val next = current.copy(
            lines = current.lines.replaceLine(confirmed),
            revision = Math.incrementExact(current.revision),
            updatedAt = now,
        )
        locallyConfirmedRows += lineId
        currentEdit = next
        persistingLineIds += lineId
        render(
            next,
            changedLineIds = setOf(lineId),
        )
        enqueueSave(next, setOf(lineId))
    }

    private fun requestDelete(lineId: LineId) {
        if (isNewEditorPendingPublication()) return
        if (currentEdit?.activeLines?.none { it.lineId == lineId } != false) return
        cancelAdvance()
        renderGeneration += 1L
        savedStateHandle[SAVED_DELETE_LINE_ID] = lineId.value
        executeMain { updateState { copy(pendingDeletionLineId = lineId) } }
    }

    private fun clearDeletionRequest() {
        renderGeneration += 1L
        savedStateHandle[SAVED_DELETE_LINE_ID] = null
        executeMain { updateState { copy(pendingDeletionLineId = null) } }
    }

    private fun deleteRequestedLine() {
        val current = currentEdit ?: return
        val lineId = uiState.value.pendingDeletionLineId ?: return
        val target = current.activeLines.firstOrNull { it.lineId == lineId } ?: return
        cancelAdvance()
        cancelBack()
        val now = mutationTime(current)
        val deleted = target.copy(deletedAt = now, updatedAt = now)
        val survivors = current.activeLines.filterNot { it.lineId == lineId }
            .mapIndexed { index, line -> line.copy(position = index, updatedAt = now) }
        val survivorById = survivors.associateBy(InvoiceLineEdit::lineId)
        val nextLines = current.lines.map { line ->
            when (line.lineId) {
                lineId -> deleted
                else -> survivorById[line.lineId] ?: line
            }
        }
        val next = current.copy(
            lines = nextLines,
            revision = Math.incrementExact(current.revision),
            updatedAt = now,
        )
        locallyChangedRows += lineId
        localOrderChanged = true
        currentEdit = next
        persistingLineIds += lineId
        savedStateHandle[SAVED_DELETE_LINE_ID] = null
        savedStateHandle[SAVED_LAST_DELETED_LINE_ID] = lineId.value
        removeDismissedRestore(lineId)
        if (uiState.value.editor?.line?.lineId == lineId) {
            newEditorLineId = null
            savedStateHandle[SAVED_EDITOR_LINE_ID] = null
        }
        render(next)
        enqueueSave(next, setOf(lineId))
    }

    private fun restoreDeletedLine() {
        val current = currentEdit ?: return
        if (current.activeLines.size >= InvoiceLinesEdit.MAX_ACTIVE_LINES) return
        val lineId = uiState.value.restorableDeletion?.lineId ?: return
        val target = current.lines.firstOrNull { it.lineId == lineId && it.isDeleted } ?: return
        cancelAdvance()
        cancelBack()
        val now = mutationTime(current)
        val insertion = target.position.coerceIn(0, current.activeLines.size)
        val reordered = current.activeLines.toMutableList().apply {
            add(insertion, target.copy(deletedAt = null, updatedAt = now))
        }.mapIndexed { index, line -> line.copy(position = index, updatedAt = now) }
        val reorderedById = reordered.associateBy(InvoiceLineEdit::lineId)
        val nextLines = current.lines.map { line -> reorderedById[line.lineId] ?: line }
        val next = current.copy(
            lines = nextLines,
            revision = Math.incrementExact(current.revision),
            updatedAt = now,
        )
        locallyChangedRows += lineId
        localOrderChanged = true
        currentEdit = next
        persistingLineIds += lineId
        if (LineId.parse(savedStateHandle.get<String>(SAVED_LAST_DELETED_LINE_ID)) == lineId) {
            savedStateHandle[SAVED_LAST_DELETED_LINE_ID] = null
        }
        render(next)
        enqueueSave(next, setOf(lineId))
    }

    private fun dismissRestore() {
        val lineId = uiState.value.restorableDeletion?.lineId ?: return
        renderGeneration += 1L
        val dismissed = dismissedRestoreIds() + lineId
        savedStateHandle[SAVED_DISMISSED_RESTORE_IDS] = ArrayList(dismissed.map(LineId::value))
        executeMain { updateState { copy(restorableDeletion = null) } }
    }

    private fun moveLine(lineId: LineId, delta: Int) {
        val current = currentEdit ?: return
        val active = current.activeLines.toMutableList()
        val source = active.indexOfFirst { it.lineId == lineId }
        val destination = source + delta
        if (source < 0 || destination !in active.indices) return
        cancelAdvance()
        cancelBack()
        val now = mutationTime(current)
        val moved = active.removeAt(source)
        active.add(destination, moved)
        val reordered = active.mapIndexed { index, line -> line.copy(position = index, updatedAt = now) }
        val reorderedById = reordered.associateBy(InvoiceLineEdit::lineId)
        val next = current.copy(
            lines = current.lines.map { line -> reorderedById[line.lineId] ?: line },
            revision = Math.incrementExact(current.revision),
            updatedAt = now,
        )
        localOrderChanged = true
        currentEdit = next
        persistingLineIds += reordered.map(InvoiceLineEdit::lineId)
        render(next)
        enqueueSave(next, reordered.mapTo(linkedSetOf()) { it.lineId })
    }

    private fun linkProducts() {
        if (isNewEditorPendingPublication()) return
        if (
            advanceInFlight ||
            backInFlight ||
            rebaseJob?.isActive == true ||
            uiState.value.isSaving ||
            uiState.value.saveFailure ||
            uiState.value.failure != null
        ) {
            return
        }
        val edit = currentEdit ?: return
        if (edit.activeLines.isEmpty()) return
        advanceValidationJob?.cancel()
        advanceInFlight = true
        val token = ++advanceToken
        val validationJob = executeMain {
            while (advanceInFlight && token == advanceToken && currentEdit === edit) {
                val projectionGeneration = renderGeneration
                val currencySnapshot = currency
                val invoiceTotalSnapshot = invoiceTotal
                val persistingSnapshot = persistingLineIds.toSet()
                val interactedSnapshot = interactedFields.toSet()
                val dismissedRestoreSnapshot = dismissedRestoreIds()
                val preferredDeletedId = LineId.parse(
                    savedStateHandle.get<String>(SAVED_LAST_DELETED_LINE_ID),
                )
                val validationProjection = withContext(dispatcherProvider.default) {
                    val validationByLineId = LinkedHashMap<LineId, InvoiceLineValidation>(
                        edit.activeLines.size,
                    )
                    val revealedFields = linkedSetOf<Pair<LineId, FieldId>>()
                    edit.activeLines.forEachIndexed { index, line ->
                        if (index % LINK_VALIDATION_CANCELLATION_BATCH_SIZE == 0) {
                            currentCoroutineContext().ensureActive()
                            yield()
                        }
                        val validation = validator.validate(line, currencySnapshot)
                        validationByLineId[line.lineId] = validation
                        validation.errors.forEach { error ->
                            revealedFields += line.lineId to error.field.toUi()
                        }
                    }
                    val allInteractedFields = interactedSnapshot + revealedFields
                    val projectionContext = currentCoroutineContext()
                    LinkValidationProjection(
                        revealedFields = revealedFields,
                        render = buildRenderProjection(
                            edit = edit,
                            currency = currencySnapshot,
                            invoiceTotal = invoiceTotalSnapshot,
                            persistingLineIds = persistingSnapshot,
                            interactedFields = allInteractedFields,
                            dismissedRestoreIds = dismissedRestoreSnapshot,
                            preferredDeletedId = preferredDeletedId,
                            existingLines = emptyList(),
                            changedLineIds = null,
                            validationByLineId = validationByLineId,
                            cancellationCheck = { index ->
                                if (index % LINK_VALIDATION_CANCELLATION_BATCH_SIZE == 0) {
                                    projectionContext.ensureActive()
                                }
                            },
                        ),
                    )
                }
                if (!advanceInFlight || token != advanceToken || currentEdit !== edit) {
                    return@executeMain
                }
                if (projectionGeneration != renderGeneration) continue

                val currentState = uiState.value
                if (currentState.failure == InvoiceLineReviewContract.Failure.LOAD_FAILED) {
                    advanceInFlight = false
                    advanceValidationJob = null
                    updateState { copy(isSaving = false) }
                    return@executeMain
                }
                interactedFields += validationProjection.revealedFields
                val firstPending = validationProjection.render.lines.firstOrNull(Line::isPending)
                val willAdvance = firstPending == null
                if (!willAdvance) advanceInFlight = false
                // La validación es global. Solo cuando ya está lista invalida un render parcial;
                // si se cancela antes, ese render sigue siendo el productor de fallback.
                asyncRenderGeneration += 1L
                renderJob?.cancel()
                applyRenderProjection(
                    projection = validationProjection.render,
                    editorLineId = currentState.editor?.line?.lineId,
                    editorIsNew = currentState.editor?.isNew == true,
                    pendingDeletionLineId = currentState.pendingDeletionLineId,
                    isLoading = false,
                    saveFailure = if (willAdvance) false else currentState.saveFailure,
                    isSaving = if (willAdvance) true else currentState.isSaving,
                    continueAttempted = true,
                    failure = if (willAdvance) null else currentState.failure,
                )
                clearPendingRender()
                if (firstPending != null) {
                    emitEffect(Effect.FocusLine(firstPending.lineId))
                } else {
                    saveRequests.trySend(saveRequest(edit, advanceToken = token))
                }
                advanceValidationJob = null
                return@executeMain
            }
        }
        advanceValidationJob = validationJob
        validationJob.invokeOnCompletion {
            executeMain {
                if (advanceValidationJob === validationJob) {
                    advanceValidationJob = null
                    if (advanceInFlight && token == advanceToken) {
                        advanceInFlight = false
                        updateState { copy(isSaving = false) }
                    }
                }
            }
        }
    }

    private fun saveBeforeBack() {
        if (backInFlight) return
        // Back pasa a ser el único propietario del estado de guardado. Una rebase tardía no
        // puede reemplazar este request terminal dentro del canal conflado.
        cancelRebase()
        val edit = currentEdit
        if (edit == null) {
            executeMain { emitEffect(Effect.Back) }
            return
        }
        cancelAdvance()
        renderGeneration += 1L
        backInFlight = true
        val token = Math.incrementExact(backToken)
        backToken = token
        executeMain { updateState { copy(isSaving = true, saveFailure = false) } }
        saveRequests.trySend(saveRequest(edit, backToken = token))
    }

    private fun retrySave() {
        if (backInFlight) return
        val edit = currentEdit ?: return
        // Retry toma posesión del flujo de persistencia. Una validación Link previa no puede
        // conservar su token ni navegar con una revisión reemplazada por la rebase.
        cancelAdvance()
        renderGeneration += 1L
        if (retryRequiresRebase) {
            rebaseAndRetry()
            return
        }
        executeMain { updateState { copy(saveFailure = false, isSaving = true) } }
        saveRequests.trySend(
            saveRequest(
                edit,
                changedLineIds = edit.activeLines.mapTo(linkedSetOf()) { it.lineId },
            ),
        )
    }

    private fun rebaseAndRetry() {
        if (rebaseJob?.isActive == true) return
        val draftId = currentEdit?.draftId ?: return
        val generation = Math.incrementExact(rebaseGeneration)
        rebaseGeneration = generation
        val job = executeIo(
            before = { updateState { copy(saveFailure = false, isSaving = true) } },
            operation = { loadInvoiceLinesReviewUseCase(draftId) },
            onSuccess = { snapshot ->
                if (generation != rebaseGeneration || !retryRequiresRebase) {
                    return@executeIo
                }
                if (snapshot == null) {
                    retryRequiresRebase = false
                    updateState {
                        copy(
                            isSaving = false,
                            saveFailure = true,
                            failure = InvoiceLineReviewContract.Failure.INVALID_ROUTE,
                        )
                    }
                    emitEffect(Effect.CloseInvalidRoute)
                    return@executeIo
                }
                val latestLocal = currentEdit?.takeIf { edit -> edit.draftId == draftId }
                    ?: return@executeIo
                val latestObservedRevision = persistedRevision
                if (
                    latestObservedRevision != null &&
                    latestObservedRevision > snapshot.persistedEditRevision
                ) {
                    updateState { copy(isSaving = false, saveFailure = true) }
                    return@executeIo
                }
                currency = snapshot.draft.currency
                invoiceTotal = snapshot.draft.total
                persistedRevision = maxRevision(persistedRevision, snapshot.persistedEditRevision)
                val rebased = calculator.recalculate(
                    snapshot.edit.mergeLocalDelta(
                        local = latestLocal,
                        changedFields = locallyChangedFields,
                        changedRows = locallyChangedRows,
                        confirmedRows = locallyConfirmedRows,
                        orderChanged = localOrderChanged,
                        updatedAt = mutationTime(maxOf(latestLocal, snapshot.edit)),
                    ),
                    currency,
                )
                retryRequiresRebase = false
                beginLocalLineage(snapshot.persistedEditRevision)
                currentEdit = rebased
                render(rebased, isSaving = true)
                saveRequests.trySend(
                    saveRequest(
                        rebased,
                        changedLineIds = rebased.activeLines.mapTo(linkedSetOf()) { it.lineId },
                    ),
                )
            },
            onFailure = { error ->
                if (generation != rebaseGeneration || !retryRequiresRebase) {
                    return@executeIo
                }
                val blockingReadFailure = uiState.value.failure.takeIf { failure ->
                    failure.isBlockingReadFailure()
                }
                updateState {
                    copy(
                        isSaving = false,
                        saveFailure = true,
                        failure = blockingReadFailure ?: if (error.isInsufficientSpace()) {
                            InvoiceLineReviewContract.Failure.STORAGE_FULL
                        } else {
                            null
                        },
                    )
                }
            },
        )
        rebaseJob = job
        job.invokeOnCompletion {
            executeMain {
                if (generation == rebaseGeneration && rebaseJob === job) {
                    rebaseJob = null
                }
            }
        }
    }

    private fun enqueueSave(edit: InvoiceLinesEdit, changedLineIds: Set<LineId>) {
        saveRequests.trySend(saveRequest(edit, changedLineIds = changedLineIds))
    }

    private fun saveRequest(
        edit: InvoiceLinesEdit,
        changedLineIds: Set<LineId> = emptySet(),
        advanceToken: Long? = null,
        backToken: Long? = null,
    ): SaveRequest = SaveRequest(
        edit = edit,
        lineage = localEditLineage,
        expectedRevision = localWriteBaseRevision,
        changedLineIds = changedLineIds,
        advanceToken = advanceToken,
        backToken = backToken,
    )

    private fun beginLocalLineage(baseRevision: Long) {
        localEditLineage = Math.incrementExact(localEditLineage)
        localWriteBaseRevision = baseRevision
    }

    private fun startWriter() {
        executeMain {
            for (request in saveRequests) {
                // Solo una confirmación de este mismo linaje puede adelantar su base. Una
                // revisión Room ajena fuerza CONFLICT/STALE y, por tanto, rebase en vez de overwrite.
                val baseRevision = if (request.lineage == localEditLineage) {
                    localWriteBaseRevision
                } else {
                    request.expectedRevision
                }
                var storageFull = false
                val result = try {
                    withContext(dispatcherProvider.io) {
                        saveInvoiceLinesEditUseCase(request.edit, baseRevision)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    storageFull = error.isInsufficientSpace()
                    null
                }
                handleSaveResult(request, result, storageFull)
            }
        }
    }

    private suspend fun handleSaveResult(
        request: SaveRequest,
        result: SaveInvoiceLinesEditResult?,
        storageFull: Boolean,
    ) {
        val success = result == SaveInvoiceLinesEditResult.SAVED ||
            result == SaveInvoiceLinesEditResult.ALREADY_SAVED
        val belongsToCurrentLineage = request.lineage == localEditLineage
        val isCurrent = belongsToCurrentLineage &&
            currentEdit?.revision == request.edit.revision
        val supersededByObservedRevision = persistedRevision?.let { revision ->
            revision > request.edit.revision
        } == true
        val currentSaveSucceeded = success && isCurrent && !supersededByObservedRevision
        if (success) {
            persistedRevision = maxRevision(persistedRevision, request.edit.revision)
        }
        if (success && belongsToCurrentLineage && !supersededByObservedRevision) {
            localWriteBaseRevision = maxRevision(
                localWriteBaseRevision,
                request.edit.revision,
            )
        }
        val blockingReadFailure = uiState.value.failure.takeIf { failure ->
            failure.isBlockingReadFailure()
        }
        if (currentSaveSucceeded) {
            cancelRebase()
            retryRequiresRebase = false
            val linesWhoseSavingStateChanged = persistingLineIds.toSet()
            persistingLineIds.clear()
            clearLocalDelta()
            render(
                request.edit,
                saveFailure = false,
                isSaving = false,
                failure = blockingReadFailure,
                changedLineIds = linesWhoseSavingStateChanged,
            )
            updateState {
                if (failure.isBlockingReadFailure()) this else copy(failure = null)
            }
        } else if (isCurrent && (!success || supersededByObservedRevision)) {
            retryRequiresRebase = supersededByObservedRevision ||
                result == SaveInvoiceLinesEditResult.STALE_REVISION ||
                result == SaveInvoiceLinesEditResult.CONFLICT
            val linesWhoseSavingStateChanged = persistingLineIds.toSet()
            persistingLineIds.clear()
            render(
                edit = request.edit,
                saveFailure = true,
                isSaving = false,
                failure = blockingReadFailure ?: if (storageFull) {
                    InvoiceLineReviewContract.Failure.STORAGE_FULL
                } else {
                    null
                },
                changedLineIds = linesWhoseSavingStateChanged,
            )
        }

        request.backToken?.let { token ->
            if (backInFlight && token == backToken) {
                backInFlight = false
                updateState { copy(isSaving = false) }
                when {
                    currentSaveSucceeded -> emitEffect(Effect.Back)
                    result == SaveInvoiceLinesEditResult.DRAFT_NOT_EDITABLE ->
                        emitEffect(Effect.CloseInvalidRoute)
                }
            }
            return
        }
        val token = request.advanceToken ?: return
        if (!advanceInFlight || token != advanceToken) return
        advanceInFlight = false
        advanceValidationJob = null
        updateState { copy(isSaving = false) }
        if (uiState.value.failure.isBlockingReadFailure()) return
        when {
            currentSaveSucceeded -> request.edit.activeLines.firstOrNull()?.lineId?.let { firstLineId ->
                emitEffect(Effect.OpenProductLinking(request.edit.draftId, firstLineId))
            }
            result == SaveInvoiceLinesEditResult.DRAFT_NOT_EDITABLE ->
                emitEffect(Effect.CloseInvalidRoute)
        }
    }

    /**
     * La primera proyeccion puede validar/formatear cien filas. Se calcula en Default antes de
     * publicar el primer estado listo, manteniendo Main libre para dibujar y responder al input.
     */
    private suspend fun renderInitial(
        edit: InvoiceLinesEdit,
        isLoading: Boolean,
        saveFailure: Boolean,
    ) {
        markPendingRender(changedLineIds = null)
        val projectionGeneration = ++asyncRenderGeneration
        renderJob?.cancel()
        val currencySnapshot = currency
        val invoiceTotalSnapshot = invoiceTotal
        val persistingSnapshot = persistingLineIds.toSet()
        val interactedSnapshot = interactedFields.toSet()
        val dismissedRestoreSnapshot = dismissedRestoreIds()
        val preferredDeletedId = LineId.parse(
            savedStateHandle.get<String>(SAVED_LAST_DELETED_LINE_ID),
        )
        val existingLinesSnapshot = uiState.value.lines
        val projection = withContext(dispatcherProvider.default) {
            val projectionContext = currentCoroutineContext()
            buildRenderProjection(
                edit = edit,
                currency = currencySnapshot,
                invoiceTotal = invoiceTotalSnapshot,
                persistingLineIds = persistingSnapshot,
                interactedFields = interactedSnapshot,
                dismissedRestoreIds = dismissedRestoreSnapshot,
                preferredDeletedId = preferredDeletedId,
                existingLines = existingLinesSnapshot,
                changedLineIds = null,
                cancellationCheck = projectionContext::ensureInvoiceLineProjectionActive,
            )
        }
        // Una edición pudo ocurrir mientras Default preparaba la proyección global.
        if (projectionGeneration != asyncRenderGeneration || currentEdit !== edit) return
        applyRenderProjection(
            projection = projection,
            editorLineId = savedEditorLineId(),
            editorIsNew = false,
            pendingDeletionLineId = savedDeletionLineId(),
            isLoading = isLoading,
            saveFailure = saveFailure,
            isSaving = uiState.value.isSaving,
            continueAttempted = uiState.value.continueAttempted,
            failure = null,
        )
        clearPendingRender()
    }

    private fun render(
        edit: InvoiceLinesEdit,
        isLoading: Boolean = false,
        saveFailure: Boolean = uiState.value.saveFailure,
        isSaving: Boolean = uiState.value.isSaving,
        continueAttempted: Boolean = uiState.value.continueAttempted,
        failure: InvoiceLineReviewContract.Failure? = uiState.value.failure,
        /** null fuerza una proyeccion completa; un set permite reutilizar las demas filas. */
        changedLineIds: Set<LineId>? = null,
    ) {
        renderGeneration += 1L
        val effectiveChangedLineIds = markPendingRender(changedLineIds)
        val projectionGeneration = ++asyncRenderGeneration
        renderJob?.cancel()
        val currencySnapshot = currency
        val invoiceTotalSnapshot = invoiceTotal
        val persistingSnapshot = persistingLineIds.toSet()
        val interactedSnapshot = interactedFields.toSet()
        val dismissedRestoreSnapshot = dismissedRestoreIds()
        val preferredDeletedId = LineId.parse(
            savedStateHandle.get<String>(SAVED_LAST_DELETED_LINE_ID),
        )
        val stateSnapshot = uiState.value
        val existingLinesSnapshot = stateSnapshot.lines
        val controlSnapshot = stateSnapshot.toRenderControlSnapshot()
        renderJob = executeMain {
            val projection = withContext(dispatcherProvider.default) {
                val projectionContext = currentCoroutineContext()
                buildRenderProjection(
                    edit = edit,
                    currency = currencySnapshot,
                    invoiceTotal = invoiceTotalSnapshot,
                    persistingLineIds = persistingSnapshot,
                    interactedFields = interactedSnapshot,
                    dismissedRestoreIds = dismissedRestoreSnapshot,
                    preferredDeletedId = preferredDeletedId,
                    existingLines = existingLinesSnapshot,
                    changedLineIds = effectiveChangedLineIds,
                    cancellationCheck = projectionContext::ensureInvoiceLineProjectionActive,
                )
            }
            if (projectionGeneration != asyncRenderGeneration || currentEdit !== edit) {
                return@executeMain
            }
            val latestEditorLineId = savedEditorLineId()
            applyRenderProjection(
                projection = projection,
                editorLineId = latestEditorLineId,
                editorIsNew = latestEditorLineId == newEditorLineId,
                pendingDeletionLineId = savedDeletionLineId(),
                isLoading = isLoading,
                saveFailure = saveFailure,
                isSaving = isSaving,
                continueAttempted = continueAttempted,
                failure = failure,
                expectedControlState = controlSnapshot,
            )
            clearPendingRender()
        }
    }

    private fun buildRenderProjection(
        edit: InvoiceLinesEdit,
        currency: CurrencyCode?,
        invoiceTotal: Money?,
        persistingLineIds: Set<LineId>,
        interactedFields: Set<Pair<LineId, FieldId>>,
        dismissedRestoreIds: Set<LineId>,
        preferredDeletedId: LineId?,
        existingLines: List<Line>,
        changedLineIds: Set<LineId>?,
        validationByLineId: Map<LineId, InvoiceLineValidation> = emptyMap(),
        cancellationCheck: ((Int) -> Unit)? = null,
    ): RenderProjection {
        val existingById = if (changedLineIds == null) {
            emptyMap()
        } else {
            existingLines.associateBy(Line::lineId)
        }
        val lines = edit.activeLines.mapIndexed { index, domainLine ->
            cancellationCheck?.invoke(index)
            val existing = existingById[domainLine.lineId]
            if (
                existing != null &&
                domainLine.lineId !in changedLineIds.orEmpty() &&
                existing.position == domainLine.position &&
                existing.cardProjection?.currencyLabel == currency?.value.orEmpty()
            ) {
                val isPersisting = domainLine.lineId in persistingLineIds
                if (existing.isPersisting == isPersisting) {
                    existing
                } else {
                    existing.copy(isPersisting = isPersisting)
                }
            } else {
                domainLine.toUiLine(
                    currency = currency,
                    validation = validationByLineId[domainLine.lineId]
                        ?: validator.validate(domainLine, currency),
                    persistingLineIds = persistingLineIds,
                    interactedFields = interactedFields,
                )
            }
        }
        val summary = calculator.summarize(edit, invoiceTotal, currency).toUiSummary()
        val availableDeleted = edit.lines.filter { line ->
            line.isDeleted && line.lineId !in dismissedRestoreIds
        }
        val latestDeleted = (
            availableDeleted.firstOrNull { line -> line.lineId == preferredDeletedId }
                ?: availableDeleted.maxWithOrNull(
                compareBy<InvoiceLineEdit>(
                    { requireNotNull(it.deletedAt) },
                    { it.lineId.value },
                ),
            )
            )?.toDeletedLine()
        return RenderProjection(
            lines = lines,
            summary = summary,
            latestDeleted = latestDeleted,
        )
    }

    /** Debe ejecutarse en Main; permite publicar una proyección preparada en Default una vez. */
    private fun applyRenderProjection(
        projection: RenderProjection,
        editorLineId: LineId?,
        editorIsNew: Boolean,
        pendingDeletionLineId: LineId?,
        isLoading: Boolean,
        saveFailure: Boolean,
        isSaving: Boolean,
        continueAttempted: Boolean,
        failure: InvoiceLineReviewContract.Failure?,
        expectedControlState: RenderControlSnapshot? = null,
    ) {
        val projectedEditorLine = editorLineId?.let { id ->
            projection.lines.firstOrNull { it.lineId == id }
        }
        if (editorLineId != null && projectedEditorLine == null) {
            savedStateHandle[SAVED_EDITOR_LINE_ID] = null
            if (newEditorLineId == editorLineId) newEditorLineId = null
        }
        updateState {
            val resolvedIsLoading = if (
                expectedControlState == null || this.isLoading == expectedControlState.isLoading
            ) {
                isLoading
            } else {
                this.isLoading
            }
            val resolvedIsSaving = if (
                expectedControlState == null || this.isSaving == expectedControlState.isSaving
            ) {
                isSaving
            } else {
                this.isSaving
            }
            val resolvedSaveFailure = if (
                expectedControlState == null || this.saveFailure == expectedControlState.saveFailure
            ) {
                saveFailure
            } else {
                this.saveFailure
            }
            val resolvedFailure = if (
                expectedControlState == null || this.failure == expectedControlState.failure
            ) {
                failure
            } else {
                this.failure
            }
            val resolvedContinueAttempted = if (
                expectedControlState == null ||
                this.continueAttempted == expectedControlState.continueAttempted
            ) {
                continueAttempted
            } else {
                this.continueAttempted
            }
            copy(
                isLoading = resolvedIsLoading,
                currencyLabel = currency?.value.orEmpty(),
                lines = projection.lines,
                editor = projectedEditorLine?.let { line ->
                    Editor(
                        line = line,
                        isNew = editorIsNew || editor?.isNew == true,
                        saveFailure = resolvedSaveFailure,
                    )
                },
                pendingDeletionLineId = pendingDeletionLineId,
                restorableDeletion = projection.latestDeleted?.takeUnless { deleted ->
                    deleted.lineId in dismissedRestoreIds()
                },
                summary = projection.summary,
                isSaving = resolvedIsSaving,
                saveFailure = resolvedSaveFailure,
                failure = resolvedFailure,
                continueAttempted = resolvedContinueAttempted,
            )
        }
    }

    private data class RenderProjection(
        val lines: List<Line>,
        val summary: Summary,
        val latestDeleted: DeletedLine?,
    )

    private data class RenderControlSnapshot(
        val isLoading: Boolean,
        val isSaving: Boolean,
        val saveFailure: Boolean,
        val failure: InvoiceLineReviewContract.Failure?,
        val continueAttempted: Boolean,
    )

    private fun State.toRenderControlSnapshot() = RenderControlSnapshot(
        isLoading = isLoading,
        isSaving = isSaving,
        saveFailure = saveFailure,
        failure = failure,
        continueAttempted = continueAttempted,
    )

    private data class LinkValidationProjection(
        val revealedFields: Set<Pair<LineId, FieldId>>,
        val render: RenderProjection,
    )

    private fun cancelAdvance() {
        advanceValidationJob?.cancel()
        advanceValidationJob = null
        if (!advanceInFlight) return
        advanceInFlight = false
        advanceToken += 1L
        executeMain { updateState { copy(isSaving = false) } }
    }

    private fun cancelBack() {
        if (!backInFlight) return
        backInFlight = false
        backToken = Math.incrementExact(backToken)
        executeMain { updateState { copy(isSaving = false) } }
    }

    private fun cancelRebase(): Boolean {
        val cancelledActiveRebase = rebaseJob?.isActive == true
        rebaseGeneration = Math.incrementExact(rebaseGeneration)
        rebaseJob?.cancel()
        rebaseJob = null
        return cancelledActiveRebase
    }

    /** Mantiene responsivo el TextField controlado mientras tarjetas y resumen esperan Default. */
    private fun publishEditorFieldValue(lineId: LineId, fieldId: FieldId, value: String) {
        executeMain {
            updateState {
                val currentEditor = editor?.takeIf { candidate ->
                    candidate.line.lineId == lineId
                } ?: return@updateState this
                val fields = currentEditor.line.fields.toMutableList()
                fields[fieldId.ordinal] = fields[fieldId.ordinal].copy(
                    value = value,
                    origin = ValueOrigin.WRITTEN,
                    touched = true,
                )
                copy(
                    editor = currentEditor.copy(
                        line = currentEditor.line.copy(fields = fields),
                    ),
                )
            }
        }
    }

    /** Refleja el FilterChip seleccionado sin esperar la proyección financiera global. */
    private fun publishEditorTaxTreatment(
        lineId: LineId,
        treatment: InventoryTaxTreatment,
    ) {
        executeMain {
            updateState {
                val currentEditor = editor?.takeIf { candidate ->
                    candidate.line.lineId == lineId
                } ?: return@updateState this
                copy(
                    editor = currentEditor.copy(
                        line = currentEditor.line.copy(taxTreatment = treatment),
                    ),
                )
            }
        }
    }

    /**
     * Si un render no publicado es sustituido, el siguiente hereda todas sus filas inválidas. Un
     * null representa una proyección global y domina cualquier conjunto parcial posterior.
     */
    private fun markPendingRender(changedLineIds: Set<LineId>?): Set<LineId>? {
        pendingChangedLineIds = when {
            !hasPendingRender -> changedLineIds
            pendingChangedLineIds == null || changedLineIds == null -> null
            else -> pendingChangedLineIds.orEmpty() + changedLineIds
        }
        hasPendingRender = true
        return pendingChangedLineIds
    }

    private fun clearPendingRender() {
        hasPendingRender = false
        pendingChangedLineIds = emptySet()
    }

    /** El diálogo nuevo tiene prioridad sobre tarjetas antiguas hasta que su proyección aparezca. */
    private fun isNewEditorPendingPublication(): Boolean =
        newEditorLineId != null && uiState.value.editor?.line?.lineId != newEditorLineId

    private fun clearLocalDelta() {
        locallyChangedFields.clear()
        locallyChangedRows.clear()
        locallyConfirmedRows.clear()
        localOrderChanged = false
    }

    private fun savedEditorLineId(): LineId? =
        LineId.parse(savedStateHandle.get<String>(SAVED_EDITOR_LINE_ID))

    private fun savedDeletionLineId(): LineId? =
        LineId.parse(savedStateHandle.get<String>(SAVED_DELETE_LINE_ID))

    private fun dismissedRestoreIds(): Set<LineId> =
        savedStateHandle.get<ArrayList<String>>(SAVED_DISMISSED_RESTORE_IDS)
            .orEmpty()
            .mapNotNull(LineId::parse)
            .toSet()

    private fun removeDismissedRestore(lineId: LineId) {
        val remaining = dismissedRestoreIds() - lineId
        savedStateHandle[SAVED_DISMISSED_RESTORE_IDS] =
            ArrayList(remaining.map(LineId::value))
    }

    private fun mutationTime(edit: InvoiceLinesEdit): Instant =
        canonicalInstant(maxOf(appClock.now(), edit.updatedAt))
}

private fun initialLineReviewState(savedStateHandle: SavedStateHandle): State {
    val draftId = DraftId.parse(savedStateHandle.get<String>(RouteArgumentKeys.DRAFT_ID))
    return State(
        draftId = draftId,
        isLoading = draftId != null,
        searchQuery = savedStateHandle.get<String>(SAVED_QUERY).orEmpty(),
        pendingOnly = savedStateHandle.get<Boolean>(SAVED_PENDING_FILTER) ?: false,
        failure = if (draftId == null) InvoiceLineReviewContract.Failure.INVALID_ROUTE else null,
    )
}

private fun InvoiceLineEdit.toUiLine(
    currency: CurrencyCode?,
    validation: InvoiceLineValidation,
    persistingLineIds: Set<LineId>,
    interactedFields: Set<Pair<LineId, FieldId>>,
): Line {
    val fields = FieldId.entries.map { id ->
        val domain = id.toDomain()
        val value = value(domain)
        Field(
            id = id,
            value = value.selectedValue.orEmpty(),
            origin = value.selectedSource.toUi(),
            ocrValue = value.ocrRaw ?: value.ocr,
            calculatedValue = value.calculated,
            confidence = fieldConfidencePermille[domain].toUiConfidence(),
            touched = domain in touchedFields || (lineId to id) in interactedFields,
            error = validation.errorFor(domain)?.code?.toUiError(),
        )
    }
    val description = fields[FieldId.DESCRIPTION.ordinal].value
    val code = fields[FieldId.CODE.ordinal].value
    return Line(
        lineId = lineId,
        position = position,
        fields = fields,
        confidence = confidencePermille.toUiConfidence(),
        confidencePercent = confidencePermille?.let { it / 10 },
        requiresReview = requiresReview || reviewRequiredFields.any { it !in touchedFields },
        confirmedByUser = reviewConfirmedByUser,
        taxTreatment = taxTreatment,
        isPersisting = lineId in persistingLineIds,
        cardProjection = CardProjection(
            description = InvoiceLineReviewContract.limitCardDescription(description),
            quantity = fields[FieldId.QUANTITY.ordinal].value,
            unit = fields[FieldId.UNIT.ordinal].value,
            unitCost = fields[FieldId.UNIT_COST.ordinal].value.toCardMoney(currency),
            igv = fields[FieldId.IGV.ordinal].value.toCardMoney(currency),
            total = fields[FieldId.TOTAL.ordinal].value.toCardMoney(currency),
            currencyLabel = currency?.value.orEmpty(),
            normalizedSearchText = InvoiceLineReviewContract.normalizeForSearch(
                "$description $code",
            ),
        ),
    )
}

/** El parseo y NumberFormat monetario se resuelven al proyectar estado, no al componer una fila. */
private fun String.toCardMoney(currency: CurrencyCode?): String {
    if (isBlank() || currency == null) return this
    return formatCurrencyAmountForDisplay(currency.value, this)
        ?: "${currencyLabelForDisplay(currency.value)} $this"
}

private fun InvoiceLineEdit.withValue(
    field: InvoiceLineEditField,
    value: InvoiceLineEditValue,
    touchedFields: Set<InvoiceLineEditField>,
    updatedAt: Instant,
): InvoiceLineEdit = when (field) {
    InvoiceLineEditField.DESCRIPTION -> copy(description = value, touchedFields = touchedFields, updatedAt = updatedAt)
    InvoiceLineEditField.CODE -> copy(code = value, touchedFields = touchedFields, updatedAt = updatedAt)
    InvoiceLineEditField.QUANTITY -> copy(quantity = value, touchedFields = touchedFields, updatedAt = updatedAt)
    InvoiceLineEditField.UNIT -> copy(unit = value, touchedFields = touchedFields, updatedAt = updatedAt)
    InvoiceLineEditField.UNIT_COST -> copy(unitCost = value, touchedFields = touchedFields, updatedAt = updatedAt)
    InvoiceLineEditField.DISCOUNT -> copy(discount = value, touchedFields = touchedFields, updatedAt = updatedAt)
    InvoiceLineEditField.IGV -> copy(igv = value, touchedFields = touchedFields, updatedAt = updatedAt)
    InvoiceLineEditField.TOTAL -> copy(total = value, touchedFields = touchedFields, updatedAt = updatedAt)
}

internal fun List<InvoiceLineEdit>.replaceLine(replacement: InvoiceLineEdit): List<InvoiceLineEdit> =
    map { line -> if (line.lineId == replacement.lineId) replacement else line }

private fun InvoiceLinesEdit.mergeLocalDelta(
    local: InvoiceLinesEdit,
    changedFields: Map<LineId, Set<InvoiceLineEditField>>,
    changedRows: Set<LineId>,
    confirmedRows: Set<LineId>,
    orderChanged: Boolean,
    updatedAt: Instant,
): InvoiceLinesEdit {
    val remoteById = lines.associateBy(InvoiceLineEdit::lineId).toMutableMap()
    val localById = local.lines.associateBy(InvoiceLineEdit::lineId)
    changedRows.forEach { id -> localById[id]?.let { remoteById[id] = it } }
    changedFields.forEach { (id, fields) ->
        val remote = remoteById[id] ?: localById[id] ?: return@forEach
        val localLine = localById[id] ?: return@forEach
        var merged = remote
        fields.forEach { field ->
            merged = merged.withValue(
                field = field,
                value = localLine.value(field),
                touchedFields = merged.touchedFields + localLine.touchedFields.filter { it in fields },
                updatedAt = updatedAt,
            )
        }
        remoteById[id] = merged
    }
    confirmedRows.forEach { id ->
        val remote = remoteById[id] ?: return@forEach
        val localLine = localById[id] ?: return@forEach
        remoteById[id] = remote.copy(
            reviewConfirmedByUser = localLine.reviewConfirmedByUser,
            touchedFields = remote.touchedFields + localLine.reviewRequiredFields,
            updatedAt = updatedAt,
        )
    }
    val active = if (orderChanged) {
        val localOrder = local.activeLines.map(InvoiceLineEdit::lineId)
        val ordered = localOrder.mapNotNull(remoteById::get).filterNot(InvoiceLineEdit::isDeleted)
        ordered + remoteById.values.filterNot { line ->
            line.isDeleted || line.lineId in localOrder
        }.sortedBy(InvoiceLineEdit::position)
    } else {
        remoteById.values.filterNot(InvoiceLineEdit::isDeleted).sortedBy(InvoiceLineEdit::position)
    }.mapIndexed { index, line -> line.copy(position = index, updatedAt = updatedAt) }
    val activeById = active.associateBy(InvoiceLineEdit::lineId)
    val mergedLines = remoteById.values
        .map { line -> activeById[line.lineId] ?: line }
        .trimRetainedHistory()
    return copy(
        lines = mergedLines,
        revision = Math.incrementExact(maxOf(revision, local.revision)),
        updatedAt = updatedAt,
    )
}

private fun List<InvoiceLineEdit>.trimRetainedHistory(): List<InvoiceLineEdit> {
    if (size <= InvoiceLinesEdit.MAX_RETAINED_LINES) return this
    val overflow = size - InvoiceLinesEdit.MAX_RETAINED_LINES
    val discardedIds = filter(InvoiceLineEdit::isDeleted)
        .sortedWith(
            compareBy<InvoiceLineEdit>(
                { requireNotNull(it.deletedAt) },
                { it.lineId.value },
            ),
        )
        .take(overflow)
        .mapTo(hashSetOf(), InvoiceLineEdit::lineId)
    return filterNot { line -> line.lineId in discardedIds }
}

private fun InvoiceLinesFinancialSummary.toUiSummary(): Summary {
    val sum = lineSum?.formatMoney().orEmpty()
    val total = invoiceTotal?.formatMoney().orEmpty()
    val delta = difference?.formatSignedMoney().orEmpty()
    return Summary(
        lineSum = sum,
        invoiceTotal = total,
        exactDifference = delta,
        hasDifference = difference?.minorUnits?.let { it != 0L } ?: false,
        issue = when {
            arithmeticOverflow -> SummaryIssue.ARITHMETIC_OVERFLOW
            unresolvedLineIds.isNotEmpty() -> SummaryIssue.UNRESOLVED_LINES
            difference == null -> SummaryIssue.NOT_COMPARABLE
            else -> SummaryIssue.NONE
        },
        unresolvedLineCount = unresolvedLineIds.size,
        // La Screen construye la frase TalkBack con recursos localizados.
        spokenDescription = "",
    )
}

private fun Money.formatMoney(): String = formatForDisplay()

private fun Money.formatSignedMoney(): String = formatSignedForDisplay()

private fun InvoiceLineEdit.toDeletedLine(): DeletedLine = DeletedLine(
    lineId = lineId,
    description = description.selectedValue.orEmpty(),
    formerPosition = position,
)

private fun FieldId.toDomain(): InvoiceLineEditField = InvoiceLineEditField.valueOf(name)

private fun InvoiceLineEditField.toUi(): FieldId = FieldId.valueOf(name)

private fun InvoiceLineValueSource?.toUi(): ValueOrigin = when (this) {
    InvoiceLineValueSource.OCR -> ValueOrigin.OCR
    InvoiceLineValueSource.CALCULATED -> ValueOrigin.CALCULATED
    InvoiceLineValueSource.WRITTEN -> ValueOrigin.WRITTEN
    null -> ValueOrigin.MISSING
}

private fun Int?.toUiConfidence(): Confidence = when (this) {
    null -> Confidence.UNKNOWN
    in 900..1_000 -> Confidence.HIGH
    in 700..899 -> Confidence.MEDIUM
    else -> Confidence.LOW
}

private fun InvoiceLineValidationErrorCode.toUiError(): FieldError = when (this) {
    InvoiceLineValidationErrorCode.REQUIRED -> FieldError.REQUIRED
    InvoiceLineValidationErrorCode.INVALID_DECIMAL -> FieldError.INVALID_DECIMAL
    InvoiceLineValidationErrorCode.INVALID_AMOUNT -> FieldError.INVALID_AMOUNT
    InvoiceLineValidationErrorCode.NEGATIVE_QUANTITY -> FieldError.NEGATIVE_QUANTITY
    InvoiceLineValidationErrorCode.NEGATIVE_AMOUNT -> FieldError.NEGATIVE_AMOUNT
    InvoiceLineValidationErrorCode.TOO_LONG -> FieldError.TOO_LONG
}

private fun sanitize(id: FieldId, raw: String): String = when (id) {
    FieldId.DESCRIPTION -> raw.take(InvoiceLineReviewValidator.MAX_DESCRIPTION_LENGTH)
    FieldId.CODE -> raw.take(InvoiceLineReviewValidator.MAX_CODE_LENGTH)
    FieldId.UNIT -> raw.take(InvoiceLineReviewValidator.MAX_UNIT_LENGTH)
    FieldId.QUANTITY,
    FieldId.UNIT_COST,
    FieldId.DISCOUNT,
    FieldId.IGV,
    FieldId.TOTAL,
    -> raw.filterIndexed { index, character ->
        character.isDigit() || character == '.' || character == ',' ||
            ((character == '-' || character == '+') && index == 0)
    }.take(MAX_NUMERIC_LENGTH)
}

private fun maxOf(first: InvoiceLinesEdit, second: InvoiceLinesEdit): InvoiceLinesEdit =
    if (first.updatedAt >= second.updatedAt) first else second

private fun canonicalInstant(value: Instant): Instant = Instant.ofEpochMilli(value.toEpochMilli())

private fun Throwable.isInsufficientSpace(): Boolean =
    this is StorageException && error == StorageError.InsufficientSpace

private fun InvoiceLineReviewContract.Failure?.isBlockingReadFailure(): Boolean =
    this == InvoiceLineReviewContract.Failure.LOAD_FAILED ||
        this == InvoiceLineReviewContract.Failure.INVALID_ROUTE

private fun maxRevision(current: Long?, candidate: Long): Long =
    current?.let { revision -> maxOf(revision, candidate) } ?: candidate

/** Acota el trabajo obsoleto cuando una carga de cien filas se cancela desde otro hilo. */
internal fun CoroutineContext.ensureInvoiceLineProjectionActive(index: Int) {
    if (index % PROJECTION_CANCELLATION_BATCH_SIZE == 0) ensureActive()
}

private const val SAVED_QUERY = "line_review.search"
private const val SAVED_PENDING_FILTER = "line_review.pending_filter"
private const val SAVED_EDITOR_LINE_ID = "line_review.editor_line_id"
private const val SAVED_DELETE_LINE_ID = "line_review.delete_line_id"
private const val SAVED_DISMISSED_RESTORE_IDS = "line_review.dismissed_restore_ids"
private const val SAVED_LAST_DELETED_LINE_ID = "line_review.last_deleted_line_id"
private const val MAX_SEARCH_LENGTH = 256
private const val LINK_VALIDATION_CANCELLATION_BATCH_SIZE = 8
private const val PROJECTION_CANCELLATION_BATCH_SIZE = 8
private const val MAX_NUMERIC_LENGTH = 128
